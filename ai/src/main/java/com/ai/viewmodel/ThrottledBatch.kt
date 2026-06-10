package com.ai.viewmodel

import com.ai.data.ProviderThrottle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The one place that owns the app's concurrent-batch throttle contract,
 * shared by every "fire N API calls, throttled" flow (Test all models,
 * Fan-out, Fan-in, Fan Meta, workers). Hand-rolling this several times
 * is what let the acquisition order drift and deadlock — keeping it here
 * means the order is defined exactly once.
 *
 * For each item it acquires, in this **canonical order**, then runs [body]:
 *
 *   [subCap]  →  [ApiCallCaps.global]  →  per-host ([acquireOrRequeue])
 *
 * The per-flow [subCap] is acquired BEFORE the shared `global` cap on
 * purpose: an item queued on its own flow cap (e.g. one of 1000 Fan Meta
 * pairs waiting for a Fan Meta permit) then holds NOTHING shared, so it
 * can't hog `global` from other flows — which is what let a Fan Meta run
 * starve a concurrent fan-out run of every global permit. Acquiring the
 * private cap first and the shared cap last is also the standard
 * deadlock-avoidance ordering (only one flow ever waits on a given
 * `subCap`, and everyone acquires the shared `global` last). `global` is
 * still acquired BEFORE the per-host gate, so the global↔per-host ordering
 * that the earlier deadlock fix established is preserved. [acquireOrRequeue]
 * is the suspending per-host gate (concurrency + per-minute window, yields
 * via `delay` instead of pinning an IO thread) — it already enforces the
 * per-host concurrency cap, so callers no longer need a separate per-host
 * `Semaphore`. While [body] runs, `ProviderThrottle.permitPreAcquired` is
 * set so the OkHttp `ProviderThrottleInterceptor` skips re-acquiring the
 * same per-host permit (which would otherwise self-deadlock same-host
 * calls).
 *
 * The three permits are acquired manually (not `withPermit`) and handed to
 * a [PermitHold] so the 429/529 backoff can give them ALL back and re-take
 * them: `ProviderThrottle.backoffPermitYielder` is registered into the
 * body's context, and the retry interceptors call it instead of sleeping
 * in place. Releasing only some of the permits during the sleep would
 * deadlock (holding `host` while re-acquiring `global` inverts the
 * canonical order); releasing all three and re-acquiring in order is
 * deadlock-safe and re-queues the call fairly. [PermitHold] serialises the
 * yield (on the OkHttp worker thread) against the per-item `finally`
 * cleanup (on the coroutine, e.g. on cancellation) so the two can never
 * double-release.
 *
 * Concurrency shape mirrors the loops it replaces: items are spread with
 * [interleaveByHost], each runs in its own `async(LAZY)` registered via
 * [register] **before** `start()` (preserving the cancel-before-delete
 * race fix), and the whole batch is awaited in a [coroutineScope] — so a
 * body that throws cancels its siblings, exactly as before.
 *
 * Must be called from inside a coroutine (it suspends on `awaitAll`).
 *
 * @param items     the work items.
 * @param hostOf    resolves an item's provider host; an item that returns
 *                  null is skipped (no host to throttle against).
 * @param subCap    the per-flow cap (`ApiCallCaps.fanOut` / `fanMeta`, or
 *                  a flow-private `Semaphore` like Test-all's ioCap).
 * @param onThrottled / onCleared  per-item UI marks while the host gate
 *                  makes the item wait / clears it.
 * @param register  records each item's [Deferred] before it starts, for
 *                  cancellation maps. Called on the launching coroutine.
 * @param timeoutMs optional per-item ceiling (Test-all uses 60_000) so a
 *                  hung call can't pin its permits forever. Wrapped inside
 *                  the permits; [body] should treat a
 *                  TimeoutCancellationException as a failed item.
 * @param dynamicHost when true the item has NO fixed host — [body] chooses
 *                  the host(s) itself per call (the worker round-robin chain
 *                  spans several providers and changes on 429-fallback). In
 *                  that mode only `subCap` + `global` are acquired here and
 *                  `permitPreAcquired` is NOT set, so each inner call acquires
 *                  its own per-host permit through `ProviderThrottleInterceptor`
 *                  (sub → global → host order preserved, no same-host
 *                  re-acquire). `ProviderThrottle.poolCoolingWaiter` is also
 *                  installed, so the worker chain's all-rate-limited retry
 *                  waits a cooling pool out with the permits RELEASED
 *                  ([PermitHold.yieldSuspending]) instead of pinning them.
 *                  [hostOf] is ignored. Default false keeps the
 *                  fixed-host path (and its null = skip) byte-for-byte.
 * @param body      the per-item work (the network call + its own persist /
 *                  status / deleted-check logic). Runs with the permits
 *                  held and `permitPreAcquired = true` (fixed-host mode).
 */
internal suspend fun <T> runThrottledBatch(
    items: List<T>,
    hostOf: (T) -> String?,
    subCap: Semaphore,
    onThrottled: (T) -> Unit = {},
    onCleared: (T) -> Unit = {},
    register: (T, Deferred<*>) -> Unit = { _, _ -> },
    timeoutMs: Long? = null,
    dynamicHost: Boolean = false,
    // ---- Type-A bench-and-requeue (Fan Out, Judge the judges) ----
    // When [benchEnabled] and [benchKey] resolves an item's (providerId,
    // model), the item is gated while that model is short-benched (holding
    // NO permits), and a 429/529 inside [body] (which short-benches the model
    // + sets the per-item bench signal) re-queues it instead of erroring —
    // up to [ModelCooldownStore.typeABenchMaxAttempts] times. [onBenchRetry]
    // resets the item's persisted row back to PENDING before each re-dispatch
    // (the body already stamped it errored). Off by default → fixed-host path
    // is byte-for-byte unchanged for every other flow.
    benchEnabled: Boolean = false,
    benchKey: (T) -> Pair<String, String>? = { null },
    onBenchRetry: (T) -> Unit = {},
    body: suspend (T) -> Unit,
) {
    if (items.isEmpty()) return
    coroutineScope {
        interleaveByHost(items) { if (dynamicHost) null else hostOf(it) }.map { item ->
            val deferred = async(start = CoroutineStart.LAZY) {
                if (dynamicHost) {
                    // Worker-style: host is picked per call inside body, so
                    // acquire only subCap + global (blank host = no-op gate)
                    // and DON'T set permitPreAcquired — each inner worker call
                    // self-throttles its own provider host via the interceptor.
                    val hold = acquireThrottledPermits(
                        subCap, "",
                        onThrottled = { onThrottled(item) },
                        onCleared = { onCleared(item) }
                    )
                    try {
                        // Pool-cooling waits run permit-free: when the worker
                        // chain finds EVERY candidate cooling it calls the
                        // installed waiter instead of delaying in place, so an
                        // item waiting out a cooling pool doesn't pin sub-cap /
                        // global capacity other items could use (the
                        // dynamic-host sibling of backoffPermitYielder).
                        withContext(
                            ProviderThrottle.poolCoolingWaiter
                                .asContextElement({ ms -> hold.yieldSuspending(ms) })
                        ) {
                            if (timeoutMs != null) withTimeout(timeoutMs) { body(item) }
                            else body(item)
                        }
                    } finally {
                        hold.dispose()
                    }
                    return@async
                }
                val host = hostOf(item) ?: return@async
                // Type-A bench-and-requeue loop. Each iteration: gate while the
                // model is short-benched (no permits held), then run one body
                // attempt with a fresh bench signal; a 429/529 sets the signal
                // (the interceptor short-benched the model) → un-error + re-gate
                // + retry, bounded by typeABenchMaxAttempts. A clear signal
                // means the body settled (done / genuine error) — return.
                val benchPair = if (benchEnabled) benchKey(item) else null
                if (benchPair != null) {
                    val (benchPid, benchMdl) = benchPair
                    var benchAttempts = 0
                    while (true) {
                        while (com.ai.data.ModelCooldownStore.isShortBenched(benchPid, benchMdl)) {
                            val until = com.ai.data.ModelCooldownStore.shortBenchUntil(benchPid, benchMdl) ?: break
                            kotlinx.coroutines.delay((until - System.currentTimeMillis()).coerceIn(50L, 1_000L))
                        }
                        val hold = acquireThrottledPermits(
                            subCap, host,
                            onThrottled = { onThrottled(item) },
                            onCleared = { onCleared(item) }
                        )
                        val sig = java.util.concurrent.atomic.AtomicBoolean(false)
                        try {
                            withContext(
                                ProviderThrottle.permitPreAcquired.asContextElement(true) +
                                    ProviderThrottle.backoffPermitYielder
                                        .asContextElement({ ms -> hold.yieldFor(ms) }) +
                                    ProviderThrottle.benchSignal.asContextElement(sig)
                            ) {
                                if (timeoutMs != null) withTimeout(timeoutMs) { body(item) }
                                else body(item)
                            }
                        } finally {
                            hold.dispose()
                        }
                        if (!sig.get()) return@async
                        benchAttempts++
                        if (benchAttempts >= com.ai.data.ModelCooldownStore.typeABenchMaxAttempts) return@async
                        onBenchRetry(item)
                    }
                }
                // Acquire sub-cap → global → host, but with the outer two
                // RELEASED while parked on the per-host gate, so a per-flow
                // cap counts only pairs holding a live provider slot (real
                // in-flight calls) — not pairs queued behind a busy provider.
                // Returns a hold owning all three; a cancellation mid-wait
                // releases everything + clears the throttled mark internally,
                // so there's nothing to dispose if it throws.
                val hold = acquireThrottledPermits(
                    subCap, host,
                    onThrottled = { onThrottled(item) },
                    onCleared = { onCleared(item) }
                )
                try {
                    withContext(
                        ProviderThrottle.permitPreAcquired.asContextElement(true) +
                            ProviderThrottle.backoffPermitYielder
                                .asContextElement({ ms -> hold.yieldFor(ms) })
                    ) {
                        if (timeoutMs != null) withTimeout(timeoutMs) { body(item) }
                        else body(item)
                    }
                } finally {
                    hold.dispose()
                }
            }
            register(item, deferred)
            deferred.start()
            deferred
        }.awaitAll()
    }
}

/**
 * Owns one throttled item's three nested permits (sub-cap → global →
 * per-host) for its whole lifetime. The 429/529 backoff hands them all
 * back and re-takes them via [yieldFor]; the per-item `finally` releases
 * whatever is still held via [dispose]. Both run under one lock and track
 * `held` / `done`, so a cancellation racing a mid-flight backoff can never
 * double-release (which would inflate a semaphore's permit count and break
 * the cap) nor leak. [yieldFor] runs on the OkHttp worker thread, so it
 * uses the blocking / `tryAcquire` permit APIs (it must not suspend); the
 * blocking re-acquire is done OUTSIDE the lock so cancellation isn't stuck
 * behind it.
 */
internal class PermitHold(
    private val subCap: Semaphore,
    private val global: Semaphore,
    private val host: String,
    @Volatile private var hostReleaser: com.ai.data.ProviderThrottle.Releaser,
) {
    private val lock = Any()
    private var held = true
    private var done = false

    /** Suspending counterpart of [yieldFor] for the pool-cooling retry
     *  (see `ProviderThrottle.poolCoolingWaiter`): release all permits,
     *  delay [ms] holding nothing (cancellable), then re-acquire in
     *  canonical order with the suspending semaphore API. Only installed
     *  by the dynamic-host batch path, whose host is "" (a no-op stub
     *  releaser), so the blocking `ProviderThrottle.acquire` below never
     *  actually blocks. A no-op if already [dispose]d; a cancellation
     *  mid-delay or mid-re-acquire leaves `held = false` with every
     *  partial acquisition rolled back, so [dispose] releases nothing. */
    suspend fun yieldSuspending(ms: Long) {
        synchronized(lock) {
            if (!held || done) return
            hostReleaser.release()
            global.release()
            subCap.release()
            held = false
        }
        kotlinx.coroutines.delay(ms)
        if (synchronized(lock) { done }) return
        var gotSub = false
        var gotGlobal = false
        var gotHost: com.ai.data.ProviderThrottle.Releaser? = null
        try {
            subCap.acquire()
            gotSub = true
            global.acquire()
            gotGlobal = true
            gotHost = com.ai.data.ProviderThrottle.acquire(host)
        } catch (t: Throwable) {
            gotHost?.release()
            if (gotGlobal) global.release()
            if (gotSub) subCap.release()
            throw t
        }
        val newHostReleaser = gotHost
        synchronized(lock) {
            if (done) {
                // Disposed while we were re-acquiring: undo so nothing
                // leaks; dispose() released nothing (held was false).
                newHostReleaser.release()
                global.release()
                subCap.release()
            } else {
                hostReleaser = newHostReleaser
                held = true
            }
        }
    }

    /** Backoff yield: release all three permits, sleep [ms] holding
     *  nothing, then re-acquire in canonical order and re-queue. A no-op
     *  if already [dispose]d (the permits stay released). */
    fun yieldFor(ms: Long) {
        synchronized(lock) {
            if (!held || done) return
            hostReleaser.release()
            global.release()
            subCap.release()
            held = false
        }
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
        if (synchronized(lock) { done }) return
        // Re-acquire OUTSIDE the lock (these block on capacity / the
        // per-minute window) so a concurrent dispose() isn't delayed.
        // Track each permit as we take it: an interrupt landing mid-way
        // (the Thread.sleep below, or a blocking host acquire) would
        // otherwise leak whatever we'd already taken — `held` is still
        // false here, so dispose() releases nothing.
        var gotSub = false
        var gotGlobal = false
        var gotHost: com.ai.data.ProviderThrottle.Releaser? = null
        try {
            while (!subCap.tryAcquire()) Thread.sleep(20)
            gotSub = true
            while (!global.tryAcquire()) Thread.sleep(20)
            gotGlobal = true
            gotHost = com.ai.data.ProviderThrottle.acquire(host)
        } catch (t: Throwable) {
            gotHost?.release()
            if (gotGlobal) global.release()
            if (gotSub) subCap.release()
            if (t is InterruptedException) Thread.currentThread().interrupt()
            throw t
        }
        val newHostReleaser = gotHost
        synchronized(lock) {
            if (done) {
                // Disposed while we were re-acquiring: undo so nothing
                // leaks; dispose() released nothing (held was false).
                newHostReleaser.release()
                global.release()
                subCap.release()
            } else {
                hostReleaser = newHostReleaser
                held = true
            }
        }
    }

    /** Final release from the coroutine's `finally`: give back whatever is
     *  currently held, exactly once. */
    fun dispose() {
        synchronized(lock) {
            done = true
            if (held) {
                hostReleaser.release()
                global.release()
                subCap.release()
                held = false
            }
        }
    }
}

/**
 * Shared resume/rerun guard for the per-row batch flows (fan-out pairs,
 * fan-in reruns, Fan Meta, single Meta). The 30 s background
 * sweep / report-open relaunch re-dispatches any row still missing its
 * result (content / icon / title); without a bound, a row that can *never*
 * complete (provider since removed, etc.) would be re-run — and re-billed —
 * every cycle. This object owns the only shared part of that: the per-row
 * attempt counter and the give-up policy. Each caller still supplies its own
 * "incomplete" filter and its own [terminalize] action (errorMessage /
 * setFanOutIconError / setFanOutTitleError) — only the counting is here.
 */
internal object BatchResume {
    /** Re-dispatch attempts a row gets before it's terminalized instead. */
    const val MAX_ATTEMPTS = 3

    // rowId -> failed re-dispatch count. In-memory / per-session is enough:
    // the runaway being bounded is the 30s sweep loop within a live session.
    private val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Of [incomplete] rows, [terminalize] (and forget) the ones already
     *  retried [MAX_ATTEMPTS] times; count the rest as one more attempt and
     *  return them for re-dispatch. Terminalize FIRST so a caller's own disk
     *  re-scan skips the now-errored rows. */
    fun capForRetry(
        incomplete: List<com.ai.data.SecondaryResult>,
        terminalize: (com.ai.data.SecondaryResult) -> Unit,
    ): List<com.ai.data.SecondaryResult> {
        val (giveUp, retry) = incomplete.partition { (attempts[it.id] ?: 0) >= MAX_ATTEMPTS }
        giveUp.forEach { terminalize(it); attempts.remove(it.id) }
        retry.forEach { attempts.merge(it.id, 1, Int::plus) }
        return retry
    }

    /** Forget the retry counts for [ids], so an EXPLICIT user re-fire
     *  (Regenerate) gets a fresh [MAX_ATTEMPTS] budget instead of being
     *  instantly terminalized by counts the background 30s sweep ran up. */
    fun resetAttempts(ids: Collection<String>) {
        ids.forEach { attempts.remove(it) }
    }

    /** Run-end finalizer: [terminalize] (and forget) every leftover row.
     *  Caller filters to its own "incomplete & not in flight" set first. */
    fun finalizeLeftover(
        leftover: List<com.ai.data.SecondaryResult>,
        terminalize: (com.ai.data.SecondaryResult) -> Unit,
    ) {
        leftover.forEach { terminalize(it); attempts.remove(it.id) }
    }
}
