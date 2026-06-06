package com.ai.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory, per-run tally of every HTTP response received, keyed by
 * (runId, "providerId|model").
 *
 * Recorded by [HttpStatusStatsInterceptor] — the innermost OkHttp interceptor,
 * so the retry loops (429 / 529) re-run it on every attempt. That means a 429
 * that's retried and then succeeds is counted as one 429 + one 200, surfacing
 * the rate-limit pressure the per-item FINAL status would hide.
 *
 * Memory only — resets on process death. The Fan out stats screen reads this
 * (not the persisted pair statuses) and only shows its icon when [hasRun] is
 * true, so after a restart there's nothing to show.
 */
object RunHttpStats {
    private class Counts {
        val ok200 = AtomicInteger()
        val rate429 = AtomicInteger()
        val overloaded529 = AtomicInteger()
        val client4xx = AtomicInteger()
        val server5xx = AtomicInteger()
        val other = AtomicInteger()
        fun add(code: Int) {
            when {
                code == 200 -> ok200
                code == 429 -> rate429
                code == 529 -> overloaded529
                code in 400..499 -> client4xx
                code in 500..599 -> server5xx
                else -> other
            }.incrementAndGet()
        }
        fun snapshot() = FanOutHttpStatusCounts(
            ok200 = ok200.get(), rate429 = rate429.get(), overloaded529 = overloaded529.get(),
            client4xx = client4xx.get(), server5xx = server5xx.get(), other = other.get()
        )
    }

    private val byRun = ConcurrentHashMap<String, ConcurrentHashMap<String, Counts>>()

    /** Record one HTTP response for ([runId], [providerId], [model]). No-op
     *  when any part is missing (untagged call / unresolved host) or [code]
     *  is non-positive (a connection failure, not an HTTP response). */
    fun record(runId: String?, providerId: String?, model: String?, code: Int) {
        if (runId.isNullOrBlank() || providerId.isNullOrBlank() || model.isNullOrBlank() || code <= 0) return
        byRun.getOrPut(runId) { ConcurrentHashMap() }
            .getOrPut("$providerId|$model") { Counts() }
            .add(code)
    }

    /** True once any response has been recorded for [runId] this session. */
    fun hasRun(runId: String?): Boolean =
        !runId.isNullOrBlank() && byRun[runId]?.isNotEmpty() == true

    /** Aggregated per-model stats for [runId] (empty when none in memory). */
    fun statsForRun(runId: String?): FanOutHttpStatusStats {
        val models = if (runId.isNullOrBlank()) null else byRun[runId]
        if (models.isNullOrEmpty()) return FanOutHttpStatusStats(0, 0, emptyList())
        val rows = models.map { (key, counts) ->
            FanOutHttpStatusRow(key.substringBefore('|'), key.substringAfter('|', ""), counts.snapshot())
        }.sortedWith(
            compareByDescending<FanOutHttpStatusRow> { it.counts.non200 }
                .thenBy { it.model.lowercase() }
                .thenBy { it.providerId.lowercase() }
        )
        return FanOutHttpStatusStats(
            totalResponses = rows.sumOf { it.counts.total },
            modelCount = rows.size,
            rows = rows
        )
    }
}
