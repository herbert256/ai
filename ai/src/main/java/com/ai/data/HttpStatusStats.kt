package com.ai.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Process-wide rolling tally of HTTP response codes (bucketed) plus response
 * times, feeding the Live Dashboard's "HTTP responses" and "Response times"
 * cards from one ring of events. Every network response (and every
 * network failure, recorded as code 0) flows through
 * [HttpStatusStatsInterceptor] — the innermost OkHttp interceptor — so each
 * individual attempt is counted, including the per-attempt 429s the retry
 * interceptors later swallow. That's deliberate: the dashboard wants to show
 * real rate-limit pressure, not just final outcomes.
 *
 * Only the last [WINDOW_MS] (5 min) of events are retained; older ones are
 * pruned on every record, so memory stays bounded to whatever volume the
 * app actually drove in the last five minutes. In-memory only — nothing is
 * persisted, and it resets on process death.
 */
object HttpStatusStats {

    /** Coarse code class shown on the dashboard. */
    enum class Bucket { OK2XX, R429, C4XX, S5XX, OTHER }

    /** Counts per bucket over one time window. */
    data class Counts(
        val ok2xx: Int = 0,
        val r429: Int = 0,
        val c4xx: Int = 0,
        val s5xx: Int = 0,
        val other: Int = 0,
    )

    /** Aggregate response-time stats (ms) over one time window. [count] is
     *  the number of samples; all others are 0 when count is 0. */
    data class Timing(
        val count: Int = 0,
        val avgMs: Int = 0,
        val minMs: Int = 0,
        val medianMs: Int = 0,
        val p95Ms: Int = 0,
        val maxMs: Int = 0,
    )

    /** One slow call surfaced by [slowestWithin]. */
    data class Slow(val host: String?, val model: String?, val durationMs: Long)

    /** One error response surfaced by [recentErrors]. */
    data class ErrorEvent(
        val t: Long,
        val host: String?,
        val model: String?,
        val code: Int,
        val message: String,
    )

    /** Longest window the card asks for — also the retention horizon. */
    private const val WINDOW_MS = 5 * 60 * 1000L
    /** Recent-errors ring: kept a bit longer and capped by count. */
    private const val ERROR_WINDOW_MS = 10 * 60 * 1000L
    private const val ERROR_CAP = 30

    /** [durationMs] is the time to the response (chain.proceed round-trip);
     *  null for a thrown network failure, which has no response time. */
    private class Hit(
        val t: Long, val bucket: Bucket, val durationMs: Long?,
        val host: String?, val model: String?,
    )

    private val lock = Any()
    private val hits = ArrayDeque<Hit>()
    private val errors = ArrayDeque<ErrorEvent>()

    /** 429 is split out from the 4xx family because it's the one the live
     *  view cares about most; code 0 (a thrown network failure) and any
     *  1xx/3xx fall into OTHER. */
    fun bucketOf(code: Int): Bucket = when {
        code == 429 -> Bucket.R429
        code in 200..299 -> Bucket.OK2XX
        code in 400..499 -> Bucket.C4XX
        code in 500..599 -> Bucket.S5XX
        else -> Bucket.OTHER
    }

    /** Record one response/attempt. [durationMs] is the response time (null
     *  for a thrown failure). Prunes anything older than [WINDOW_MS]. */
    fun record(code: Int, durationMs: Long? = null, host: String? = null, model: String? = null) {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        val bucket = bucketOf(code)
        synchronized(lock) {
            hits.addLast(Hit(now, bucket, durationMs, host, model))
            while (hits.isNotEmpty() && hits.first().t < cutoff) hits.removeFirst()
        }
    }

    /** Record one error response/failure for the live "recent errors" feed.
     *  Bounded by both time ([ERROR_WINDOW_MS]) and count ([ERROR_CAP]). */
    fun recordError(host: String?, model: String?, code: Int, message: String) {
        val now = System.currentTimeMillis()
        val cutoff = now - ERROR_WINDOW_MS
        synchronized(lock) {
            errors.addLast(ErrorEvent(now, host, model, code, message))
            while (errors.isNotEmpty() && (errors.first().t < cutoff || errors.size > ERROR_CAP)) {
                errors.removeFirst()
            }
        }
    }

    /** Up to [max] most-recent errors, newest first. */
    fun recentErrors(max: Int): List<ErrorEvent> {
        val cutoff = System.currentTimeMillis() - ERROR_WINDOW_MS
        synchronized(lock) {
            return errors.asReversed().asSequence()
                .filter { it.t >= cutoff }
                .take(max)
                .toList()
        }
    }

    /** The [n] slowest calls in the trailing [windowMs], slowest first. */
    fun slowestWithin(windowMs: Long, n: Int): List<Slow> {
        val cutoff = System.currentTimeMillis() - windowMs
        val out = ArrayList<Slow>()
        synchronized(lock) {
            for (h in hits) {
                val d = h.durationMs
                if (h.t >= cutoff && d != null) out.add(Slow(h.host, h.model, d))
            }
        }
        return out.sortedByDescending { it.durationMs }.take(n)
    }

    /** Bucketed counts over the trailing [windowMs]. */
    fun countsWithin(windowMs: Long): Counts {
        val cutoff = System.currentTimeMillis() - windowMs
        var ok = 0; var r429 = 0; var c4 = 0; var s5 = 0; var other = 0
        synchronized(lock) {
            for (h in hits) {
                if (h.t < cutoff) continue
                when (h.bucket) {
                    Bucket.OK2XX -> ok++
                    Bucket.R429 -> r429++
                    Bucket.C4XX -> c4++
                    Bucket.S5XX -> s5++
                    Bucket.OTHER -> other++
                }
            }
        }
        return Counts(ok, r429, c4, s5, other)
    }

    /** Aggregate response-time stats over the trailing [windowMs], across
     *  every recorded response that carried a duration (failures excluded).
     *  Percentiles use nearest-rank on the sorted sample. */
    fun timingWithin(windowMs: Long): Timing {
        val cutoff = System.currentTimeMillis() - windowMs
        val ds = ArrayList<Long>()
        synchronized(lock) {
            for (h in hits) {
                val d = h.durationMs
                if (h.t >= cutoff && d != null) ds.add(d)
            }
        }
        if (ds.isEmpty()) return Timing()
        ds.sort()
        val count = ds.size
        val avg = (ds.sum() / count).toInt()
        fun pct(p: Double): Int {
            val idx = Math.round((count - 1) * p).toInt().coerceIn(0, count - 1)
            return ds[idx].toInt()
        }
        return Timing(
            count = count,
            avgMs = avg,
            minMs = ds.first().toInt(),
            medianMs = pct(0.5),
            p95Ms = pct(0.95),
            maxMs = ds.last().toInt(),
        )
    }
}

/**
 * Innermost OkHttp interceptor: tallies every network response code into
 * [HttpStatusStats]. A thrown failure (DNS / TLS / connect / read timeout)
 * is recorded as code 0 (→ OTHER) and re-thrown so caller-side handling is
 * unchanged. Always runs — unlike [TracingInterceptor], it isn't gated on
 * the tracing toggle.
 */
class HttpStatusStatsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val start = System.currentTimeMillis()
        val host = chain.request().url.host
        // Model tag is propagated onto the dispatcher thread by ApiClient's
        // TagPropagatingExecutor; null for untagged calls (model lists, pricing).
        val model = ApiTracer.currentModel
        val response = try {
            chain.proceed(chain.request())
        } catch (e: Exception) {
            HttpStatusStats.record(0, host = host, model = model)   // failure: no response time
            HttpStatusStats.recordError(host, model, 0, "${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
            throw e
        }
        HttpStatusStats.record(response.code, System.currentTimeMillis() - start, host, model)
        // Per-run tally for the Fan out HTTP-stats screen. This is the innermost
        // interceptor, so the 429/529 retry loops re-run it on every attempt —
        // counting the retried-away responses the per-item final status hides.
        RunHttpStats.record(ApiTracer.currentRunId, ProviderRegistry.findByHost(host)?.id, model, response.code)
        if (response.code >= 400) {
            // peekBody is non-consuming — the downstream parser still reads the
            // real body. Cap at 64 KiB like the retry interceptor does.
            val body = runCatching { response.peekBody(64L * 1024L).string() }.getOrNull()
            val msg = extractApiErrorMessage(body).ifBlank { "HTTP ${response.code}" }
            HttpStatusStats.recordError(host, model, response.code, msg)
        }
        return response
    }
}
