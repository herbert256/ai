package com.ai.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-run count of HTTP 429 (rate-limit) responses received, keyed by
 * (runId, "providerId|model").
 *
 * The [RateLimitRetryInterceptor] records each 429 here even though it then
 * retries the request — which usually ends 200, so the per-item *final* status
 * the Fan Out HTTP-stats screen shows hides the rate-limit pressure entirely.
 * A screen scoped to a run (it has the runId) reads these to surface the 429s
 * that were retried away.
 *
 * In-memory only; resets on process death (the retries themselves are runtime
 * events, not persisted on the items).
 */
object RunRetryStats {
    private val byRun = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>>()

    /** Record one 429 seen for ([runId], [providerId], [model]). No-op when
     *  any part is missing (e.g. a call outside a tagged run, or an
     *  unresolved host). */
    fun record429(runId: String?, providerId: String?, model: String?) {
        if (runId.isNullOrBlank() || providerId.isNullOrBlank() || model.isNullOrBlank()) return
        byRun.getOrPut(runId) { ConcurrentHashMap() }
            .getOrPut("$providerId|$model") { AtomicInteger(0) }
            .incrementAndGet()
    }

    /** "providerId|model" → 429 count for [runId] (empty when none). */
    fun retries429ForRun(runId: String): Map<String, Int> =
        byRun[runId]?.mapValues { it.value.get() } ?: emptyMap()
}
