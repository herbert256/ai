package com.ai.data

import android.content.Context
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch

/** Per-hostname rate + concurrency gate. Backs
 *  [ProviderThrottleInterceptor]. One [java.util.concurrent.Semaphore]
 *  per host caps in-flight calls; a sibling deque of call timestamps
 *  enforces the sliding-window per-minute rate.
 *
 *  Acquire is **synchronous** — it blocks the calling thread (an OkHttp
 *  dispatcher worker, backed by a cached thread pool, see
 *  ApiClient.kt). Returns a [Releaser] that must be called in a
 *  finally so the permit isn't leaked on exception. */
object ProviderThrottle {
    private val sems = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore>()
    private val windows = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Long>>()

    /** True on threads where the surrounding flow already acquired a
     *  per-provider permit and is holding it explicitly (Fan-out's
     *  coroutine-level acquire). The interceptor reads this on the
     *  OkHttp dispatcher thread and skips its own acquire — without
     *  the flag we'd double-count permits and halve the effective
     *  concurrency for those flows.
     *
     *  Propagated across coroutine dispatcher hops via
     *  [asContextElement]; copied onto OkHttp worker threads by
     *  [TagPropagatingExecutor]. */
    val permitPreAcquired: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** True on threads whose flow does not want the in-line 429 / 529
     *  retry loops (the `Thread.sleep`-based backoff in
     *  [RateLimitRetryInterceptor] / [OverloadedRetryInterceptor]).
     *  Set by bulk health sweeps — the "Test all models" run — where a
     *  rate-limited / overloaded response is itself the result worth
     *  recording, and a multi-second sleep would just pin a shared
     *  concurrency permit and stall the whole run. The fast bench
     *  check (long Retry-After → [ModelCooldownStore]) still runs; only
     *  the sleeping retry loop is skipped.
     *
     *  Same propagation contract as [permitPreAcquired]. */
    val suppressInlineRetry: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** Set by `runThrottledBatch` for its throttled items. When present,
     *  the 429 / 529 retry interceptors call it (via [backoffSleep])
     *  with the chosen backoff INSTEAD of `Thread.sleep`-ing in place:
     *  it releases the item's held sub-cap + global + per-host permits,
     *  sleeps the backoff holding NOTHING, then re-acquires them in the
     *  canonical order (sub-cap → global → host). A call backing off no
     *  longer hogs shared capacity other hosts / flows could use, and
     *  re-queues fairly. Null for flows that didn't register one — the
     *  retry loop then falls back to a plain in-place sleep. Same
     *  worker-thread propagation contract as [permitPreAcquired]. */
    val backoffPermitYielder: ThreadLocal<((backoffMs: Long) -> Unit)?> =
        ThreadLocal.withInitial { null }

    /** Per-item requeue signal for the type-A bench batches (Fan Out, Judge
     *  the judges). `runThrottledBatch` installs a fresh [AtomicBoolean] per
     *  attempt; the 429 / 529 retry interceptors set it `true` when they
     *  short-bench the model ([ModelCooldownStore.markShortBench]) instead of
     *  sleeping in line, telling the batch loop to re-queue the item once the
     *  bench clears. Null for every other flow (the in-line retry runs as
     *  before). Same OkHttp-worker propagation contract as
     *  [permitPreAcquired] — see [com.ai.data.TagPropagatingExecutor]. */
    val benchSignal: ThreadLocal<java.util.concurrent.atomic.AtomicBoolean?> =
        ThreadLocal.withInitial { null }

    /** Optional per-flow observer of throttle WAITS — invoked with `true`
     *  the moment a call first parks on this host's gate (per-minute window
     *  or concurrency cap) and `false` once it's admitted or the wait is
     *  abandoned (cancellation). Lets a flow whose real per-provider
     *  throttling happens deep inside a dynamic-host call surface "this row
     *  is waiting on a rate-limit" to its UI.
     *
     *  Notably Fan Meta: its worker chain is dynamic-host, so each call's
     *  throttle wait lands at [acquireOrWait] (via `ApiDispatch.withHostGate`)
     *  rather than at the batch dispatch layer's `acquireThrottledPermits` —
     *  which is why the batch's own `onThrottled` hook never fires for it and
     *  its "Throttled" counter read zero. The flow installs this observer as a
     *  [asContextElement] around its worker call; because [acquireOrWait] runs
     *  on the coroutine thread the element re-installs the value on, no
     *  OkHttp-worker propagation (`TagPropagatingExecutor`) is needed. Null =
     *  no observer (the default for every other flow → zero overhead). */
    val throttleWaitObserver: ThreadLocal<((waiting: Boolean) -> Unit)?> =
        ThreadLocal.withInitial { null }

    /** Sleep [ms] for a retry backoff. If the current flow registered a
     *  [backoffPermitYielder] (the throttled-batch flows do), delegate to
     *  it so the held permits are released for the duration; otherwise a
     *  plain `Thread.sleep`. Propagates `InterruptedException` either way
     *  (caller treats it as teardown). */
    fun backoffSleep(ms: Long) {
        val yielder = backoffPermitYielder.get()
        if (yielder != null) {
            yielder(ms)
        } else {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
        }
    }

    class Releaser internal constructor(private val sem: java.util.concurrent.Semaphore?) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        // sem == null is the no-op releaser (blank host / no real permit) —
        // releasing it must do nothing, NOT bump a sentinel Semaphore past
        // its max (which throws "Maximum permit count exceeded").
        fun release() { if (released.compareAndSet(false, true)) sem?.release() }
    }

    /** Result of [tryAcquire]. */
    sealed class Outcome {
        class Acquired(val releaser: Releaser) : Outcome()
        /** Gate is full; [availableAtMs] is the wall-clock time the
         *  caller should re-check (exact for the per-minute window,
         *  a short poll interval for the concurrency cap). */
        class Blocked(val availableAtMs: Long) : Outcome()
    }

    /** Poll interval when only the concurrency cap is full — there's
     *  no exact ETA for a permit, so re-check soon. */
    private const val CONCURRENCY_POLL_MS = 500L

    /** Resolve a per-provider override (if any) and return the effective
     *  caps the interceptor would apply. Exposed so the fan-out
     *  pre-acquire path uses the exact same lookup as the in-line
     *  interceptor — keeping the two callers in sync as the throttle
     *  source-of-truth evolves. */
    fun limitsFor(host: String): Pair<Int, Int> {
        if (host.isBlank()) return NetworkSettings.maxCallsPerProviderPerMinute to NetworkSettings.maxConcurrentCallsPerProvider
        val override = ProviderRegistry.findByHost(host)
        val perMinute = (override?.maxCallsPerProviderPerMinute
            ?: NetworkSettings.maxCallsPerProviderPerMinute).coerceAtLeast(1)
        val concurrent = (override?.maxConcurrentCallsPerProvider
            ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
        return perMinute to concurrent
    }

    /** Resolve the effective (maxRetries, backoffMs) for [host]'s 429
     *  retry loop: per-provider override → global default. maxRetries
     *  is coerced ≥ 0 (zero is a valid "no in-line retries" setting),
     *  backoffMs is coerced ≥ 1 so a typo can't degenerate into a
     *  busy loop. */
    fun retryLimitsFor429(host: String): Pair<Int, Long> {
        if (host.isBlank()) return NetworkSettings.maxRetriesOn429 to NetworkSettings.retryBackoffMs429
        val override = ProviderRegistry.findByHost(host)
        val maxRetries = (override?.maxRetriesOn429
            ?: NetworkSettings.maxRetriesOn429).coerceAtLeast(0)
        val backoffMs = (override?.retryBackoffMs429
            ?: NetworkSettings.retryBackoffMs429).coerceAtLeast(1L)
        return maxRetries to backoffMs
    }

    /** Resolve the effective (maxRetries, backoffMs) for [host]'s 529
     *  (server overloaded) retry loop. Same shape and clamping as
     *  [retryLimitsFor429] — kept separate so the two retry loops can
     *  be tuned independently. */
    fun retryLimitsFor529(host: String): Pair<Int, Long> {
        if (host.isBlank()) return NetworkSettings.maxRetriesOn529 to NetworkSettings.retryBackoffMs529
        val override = ProviderRegistry.findByHost(host)
        val maxRetries = (override?.maxRetriesOn529
            ?: NetworkSettings.maxRetriesOn529).coerceAtLeast(0)
        val backoffMs = (override?.retryBackoffMs529
            ?: NetworkSettings.retryBackoffMs529).coerceAtLeast(1L)
        return maxRetries to backoffMs
    }

    /** Gate on concurrency first, then on the per-minute rate. The
     *  rate-limit branch appends a timestamp immediately before the
     *  caller leaves this gate, so time queued behind the concurrency
     *  cap does not consume a sliding-window slot.
     *
     *  Caps are resolved at acquire time per host:
     *    per-provider override (AppService.maxCalls… / maxConcurrent…)
     *    → global default (NetworkSettings.*).
     *  Provider edits go through [ProviderRegistry.save] which calls
     *  [resetForNewLimits], so an override bump takes effect on the
     *  next acquire — no need to re-read on every iteration of the
     *  rate-limit loop. */
    fun acquire(host: String): Releaser {
        if (host.isBlank()) {
            // Hostless requests (rare; only with a malformed URL) get
            // a stub releaser so the interceptor's finally is a no-op.
            return Releaser(null)
        }
        val override = ProviderRegistry.findByHost(host)
        val perMinuteLimit = (override?.maxCallsPerProviderPerMinute
            ?: NetworkSettings.maxCallsPerProviderPerMinute).coerceAtLeast(1)
        val concurrentLimit = (override?.maxConcurrentCallsPerProvider
            ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
        // Concurrency gate.
        val sem = sems.computeIfAbsent(host) {
            java.util.concurrent.Semaphore(concurrentLimit)
        }
        val concurrentWaitStart = if (sem.availablePermits() == 0) System.currentTimeMillis() else 0L
        sem.acquire()
        if (concurrentWaitStart > 0L) {
            AppLog.d("Throttle", "concurrent-cap wait ${System.currentTimeMillis() - concurrentWaitStart}ms on $host (cap=$concurrentLimit)")
        }
        val window = windows.computeIfAbsent(host) { java.util.concurrent.ConcurrentLinkedDeque() }
        try {
            // Rate-limit gate — loop until we claim a slot in the 60 s window.
            while (true) {
                val now = System.currentTimeMillis()
                val sleepMs: Long = synchronized(window) {
                    while (true) {
                        val head = window.peekFirst() ?: break
                        if (head < now - 60_000L) window.pollFirst() else break
                    }
                    if (window.size < perMinuteLimit) {
                        window.addLast(now)
                        0L
                    } else {
                        val oldest = window.peekFirst() ?: now
                        (oldest + 60_001L - now).coerceIn(1L, 60_000L)
                    }
                }
                if (sleepMs == 0L) break
                AppLog.d("Throttle", "rate-limit wait ${sleepMs}ms on $host (queue=${window.size}/$perMinuteLimit)")
                try { Thread.sleep(sleepMs) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        } catch (t: Throwable) {
            sem.release()
            throw t
        }
        return Releaser(sem)
    }

    /** Non-blocking sibling of [acquire]. Used by the list
     *  dispatchers (Fan Out / translation / model reports) so a
     *  capped entry can yield + requeue instead of `Thread.sleep`-ing
     *  a worker. Checks the concurrency permit first (non-destructive
     *  on a miss), then the per-minute window under the same
     *  `synchronized(window)` admission [acquire] uses; on a window
     *  miss the concurrency permit is released so nothing leaks. */
    fun tryAcquire(host: String): Outcome {
        if (host.isBlank()) {
            return Outcome.Acquired(Releaser(null))
        }
        val override = ProviderRegistry.findByHost(host)
        val perMinuteLimit = (override?.maxCallsPerProviderPerMinute
            ?: NetworkSettings.maxCallsPerProviderPerMinute).coerceAtLeast(1)
        val concurrentLimit = (override?.maxConcurrentCallsPerProvider
            ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
        val sem = sems.computeIfAbsent(host) { java.util.concurrent.Semaphore(concurrentLimit) }
        if (!sem.tryAcquire()) {
            return Outcome.Blocked(System.currentTimeMillis() + CONCURRENCY_POLL_MS)
        }
        val window = windows.computeIfAbsent(host) { java.util.concurrent.ConcurrentLinkedDeque() }
        val blockedUntil: Long? = synchronized(window) {
            val now = System.currentTimeMillis()
            while (true) {
                val head = window.peekFirst() ?: break
                if (head < now - 60_000L) window.pollFirst() else break
            }
            if (window.size < perMinuteLimit) {
                window.addLast(now)
                null
            } else {
                (window.peekFirst() ?: now) + 60_001L
            }
        }
        return if (blockedUntil == null) {
            Outcome.Acquired(Releaser(sem))
        } else {
            sem.release()
            Outcome.Blocked(blockedUntil)
        }
    }

    /** Suspending per-host gate for the dispatch layer: polls [tryAcquire]
     *  and `delay`s (coroutine suspension — never Thread.sleep, never blocks
     *  a thread) until it claims a slot, then returns the [Releaser]. Lets
     *  the rate-limit WAIT happen at the coroutine layer — outside the
     *  network-call timeout and without occupying an OkHttp dispatcher slot —
     *  instead of the thread-blocking [acquire] inside the OkHttp
     *  interceptor (which is what deadlocked the dispatcher per-host limit
     *  against this throttle, and made a legit queue wait trip the DNS-hang
     *  timeout). Cancellation-aware via `delay`. */
    suspend fun acquireOrWait(host: String): Releaser {
        // Capture the observer once: the coroutine may resume on a different
        // thread after each `delay`, but the lambda is the same object, and a
        // single capture guarantees the `false` notification pairs with the
        // `true` even if the ThreadLocal isn't set on the resume thread.
        val observer = throttleWaitObserver.get()
        var notifiedWaiting = false
        try {
            while (true) {
                when (val o = tryAcquire(host)) {
                    is Outcome.Acquired -> return o.releaser
                    is Outcome.Blocked -> {
                        if (observer != null && !notifiedWaiting) {
                            observer(true)
                            notifiedWaiting = true
                        }
                        kotlinx.coroutines.delay(
                            (o.availableAtMs - System.currentTimeMillis()).coerceIn(50L, 5_000L)
                        )
                    }
                }
            }
        } finally {
            // Clear on every exit once parked — admitted OR cancelled mid-wait —
            // so a row can't stay stuck in the caller's throttled set.
            if (notifiedWaiting && observer != null) observer(false)
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    /** Drop the per-host semaphore + window maps so the next call to
     *  [acquire] builds fresh ones at the current
     *  [NetworkSettings.maxConcurrentCallsPerProvider]. Called from
     *  AppViewModel when the user changes the concurrency cap.
     *  In-flight calls still hold a permit on the old (now
     *  unreferenced) semaphore — they release correctly when they
     *  finish, the now-orphan semaphore is GC'd shortly after. Briefly
     *  during the swap the host can exceed the new cap by up to the
     *  old cap's permits; acceptable for a user-driven setting tweak. */
    fun resetForNewLimits() {
        sems.clear()
        windows.clear()
    }

    /** One-line per-host snapshot for the stall watchdog: for every host
     *  that has an active semaphore, `host conc=<avail>/<limit> win=<n>`.
     *  A host stuck at `conc=0/N` with no progress across watchdog ticks
     *  is the signature of a leaked concurrency permit (the kind of thing
     *  that froze a big sweep). Cheap, read-only. */
    fun diagnostics(): String {
        if (sems.isEmpty()) return "(no active hosts)"
        return sems.entries.joinToString("; ") { (host, sem) ->
            val limit = (ProviderRegistry.findByHost(host)?.maxConcurrentCallsPerProvider
                ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
            val win = windows[host]?.size ?: 0
            "$host conc=${sem.availablePermits()}/$limit win=$win"
        }
    }

    /** One active host's live gate state. [inUse]/[limit] is the
     *  concurrency saturation; [windowCount] is how many calls landed
     *  in the trailing 60 s sliding window (against
     *  [NetworkSettings.maxCallsPerProviderPerMinute]). */
    data class HostThrottleStat(
        val host: String,
        val free: Int,
        val limit: Int,
        val windowCount: Int
    ) {
        val inUse: Int get() = (limit - free).coerceAtLeast(0)
    }

    /** Structured sibling of [diagnostics] for the AI Dashboard — one
     *  row per host that has an active semaphore, busiest first. Cheap,
     *  read-only (semaphore permit counts + deque sizes). */
    fun snapshot(): List<HostThrottleStat> =
        sems.entries.map { (host, sem) ->
            val limit = (ProviderRegistry.findByHost(host)?.maxConcurrentCallsPerProvider
                ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
            HostThrottleStat(host, sem.availablePermits(), limit, windows[host]?.size ?: 0)
        }.sortedByDescending { it.inUse }
}

/** OkHttp application interceptor that gates every outbound request
 *  through [ProviderThrottle]. Sits inside [RateLimitRetryInterceptor]
 *  so each 429 retry re-enters this interceptor and re-acquires its
 *  own slot (we release in `finally` before the retry-interceptor's
 *  loop reissues `chain.proceed`). */
class ProviderThrottleInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Don't block the main thread — same guard as
        // RateLimitRetryInterceptor. A misuse from a UI dispatcher
        // would ANR; pass-through is the safe fallback.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return chain.proceed(chain.request())
        }
        val request = chain.request()
        // Fan-out (and any other pre-acquiring flow) already holds a
        // permit; acquiring here too would double-count and halve the
        // effective concurrency cap. The flag is set on the calling
        // coroutine thread and propagated onto this worker by
        // TagPropagatingExecutor.
        if (ProviderThrottle.permitPreAcquired.get() == true) {
            return chain.proceed(request)
        }
        val releaser = try {
            ProviderThrottle.acquire(request.url.host)
        } catch (e: InterruptedException) {
            throw e.asThrottleCancellation()
        }
        try {
            return chain.proceed(request)
        } finally {
            releaser.release()
        }
    }
}

internal fun InterruptedException.asThrottleCancellation(): kotlinx.coroutines.CancellationException {
    Thread.currentThread().interrupt()
    return kotlinx.coroutines.CancellationException("Provider throttle interrupted").also {
        it.initCause(this)
    }
}
