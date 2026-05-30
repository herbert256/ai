package com.ai.data

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide live retry pressure for the Live Dashboard. Counts each
 * in-line retry attempt (429 from [RateLimitRetryInterceptor], 529 from
 * [OverloadedRetryInterceptor]) over a trailing window, and how many calls
 * are *currently* parked in a retry backoff sleep. In-memory only; resets
 * on process death.
 */
object RetryStats {

    private const val WINDOW_MS = 5 * 60 * 1000L

    private val lock = Any()
    private val attempts = ArrayDeque<Long>()
    private val inFlight = AtomicInteger(0)

    /** One retry attempt happened (about to sleep + reissue). */
    fun record() {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        synchronized(lock) {
            attempts.addLast(now)
            while (attempts.isNotEmpty() && attempts.first() < cutoff) attempts.removeFirst()
        }
    }

    /** Bracket the backoff sleep so [inFlightCount] reflects calls that are
     *  right now waiting to retry. */
    fun enterBackoff() { inFlight.incrementAndGet() }
    fun exitBackoff() { inFlight.decrementAndGet() }

    fun inFlightCount(): Int = inFlight.get().coerceAtLeast(0)

    /** Retry attempts in the trailing [windowMs]. */
    fun retriesWithin(windowMs: Long): Int {
        val cutoff = System.currentTimeMillis() - windowMs
        synchronized(lock) { return attempts.count { it >= cutoff } }
    }
}
