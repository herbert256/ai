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

    class Releaser internal constructor(private val sem: java.util.concurrent.Semaphore) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) sem.release() }
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

    /** Gate on per-minute rate first, then on concurrency. The
     *  rate-limit branch appends a timestamp on admission — even if a
     *  later concurrency-acquire blocks, the slot stays "used" for the
     *  full minute, which is the safe direction (over-throttling
     *  rather than silently exceeding the user's setting).
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
            return Releaser(java.util.concurrent.Semaphore(Int.MAX_VALUE))
        }
        val override = ProviderRegistry.findByHost(host)
        val perMinuteLimit = (override?.maxCallsPerProviderPerMinute
            ?: NetworkSettings.maxCallsPerProviderPerMinute).coerceAtLeast(1)
        val concurrentLimit = (override?.maxConcurrentCallsPerProvider
            ?: NetworkSettings.maxConcurrentCallsPerProvider).coerceAtLeast(1)
        val window = windows.computeIfAbsent(host) { java.util.concurrent.ConcurrentLinkedDeque() }
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
        // Concurrency gate.
        val sem = sems.computeIfAbsent(host) {
            java.util.concurrent.Semaphore(concurrentLimit)
        }
        val concurrentWaitStart = if (sem.availablePermits() == 0) System.currentTimeMillis() else 0L
        sem.acquire()
        if (concurrentWaitStart > 0L) {
            AppLog.d("Throttle", "concurrent-cap wait ${System.currentTimeMillis() - concurrentWaitStart}ms on $host (cap=$concurrentLimit)")
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
            return Outcome.Acquired(Releaser(java.util.concurrent.Semaphore(Int.MAX_VALUE)))
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
        val releaser = ProviderThrottle.acquire(request.url.host)
        try {
            return chain.proceed(request)
        } finally {
            releaser.release()
        }
    }
}

