package com.ai.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Process-wide rolling tally of HTTP response codes, bucketed for the Live
 * Dashboard's "HTTP responses" card. Every network response (and every
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

    /** Longest window the card asks for — also the retention horizon. */
    private const val WINDOW_MS = 5 * 60 * 1000L

    private class Hit(val t: Long, val bucket: Bucket)

    private val lock = Any()
    private val hits = ArrayDeque<Hit>()

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

    /** Record one response/attempt. Prunes anything older than [WINDOW_MS]. */
    fun record(code: Int) {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        val bucket = bucketOf(code)
        synchronized(lock) {
            hits.addLast(Hit(now, bucket))
            while (hits.isNotEmpty() && hits.first().t < cutoff) hits.removeFirst()
        }
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
        val response = try {
            chain.proceed(chain.request())
        } catch (e: Exception) {
            HttpStatusStats.record(0)
            throw e
        }
        HttpStatusStats.record(response.code)
        return response
    }
}
