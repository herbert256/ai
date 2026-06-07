package com.ai.data

import android.content.Context

/**
 * Process-wide rolling tally of token usage for the Live Dashboard's
 * "Spend & tokens" card. Fed from the single usage chokepoint
 * (`SettingsPreferences.updateUsageStats`), so every cost-bearing call —
 * report, chat, secondary, translation, fan-meta title/icon — is counted.
 *
 * Tokens are summed directly; cost is computed lazily at query time
 * ([costWithin]) because the feed point has no Context for the pricing
 * cache, and because pricing can change. Cost is pricing-derived (not the
 * occasional provider-reported `apiCost`), which is close enough for a live
 * rate gauge. Only the last [WINDOW_MS] (5 min) of events are retained,
 * pruned on every record. In-memory only; resets on process death.
 */
object ApiUsageRates {

    private const val WINDOW_MS = 5 * 60 * 1000L

    private class Ev(
        val t: Long,
        val provider: AppService,
        val model: String,
        val inTok: Int,
        val outTok: Int,
    )

    private val lock = Any()
    private val events = ArrayDeque<Ev>()

    /** Record one call's token usage. No-op when both counts are zero
     *  (e.g. an errored call that reported nothing). */
    fun record(provider: AppService, model: String, inputTokens: Int, outputTokens: Int) {
        if (inputTokens <= 0 && outputTokens <= 0) return
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        synchronized(lock) {
            events.addLast(Ev(now, provider, model, inputTokens, outputTokens))
            while (events.isNotEmpty() && events.first().t < cutoff) events.removeFirst()
        }
    }

    data class Tokens(val inTok: Long = 0, val outTok: Long = 0)

    /** Input/output token totals over the trailing [windowMs]. */
    fun tokensWithin(windowMs: Long): Tokens {
        val cutoff = System.currentTimeMillis() - windowMs
        var i = 0L; var o = 0L
        synchronized(lock) {
            for (e in events) if (e.t >= cutoff) { i += e.inTok; o += e.outTok }
        }
        return Tokens(i, o)
    }

    /** Dollars over the trailing [windowMs], priced through [PricingCache].
     *  Events are grouped by (provider, model) so each model is priced once.
     *  Returns null while the pricing cache is still preloading so the live
     *  dashboard doesn't briefly display DEFAULT-priced spend. */
    fun costWithin(context: Context, windowMs: Long): Double? {
        if (!PricingCache.isPreloadCompleted()) return null
        val cutoff = System.currentTimeMillis() - windowMs
        val sums = HashMap<String, LongArray>()       // key -> [in, out]
        val provOf = HashMap<String, AppService>()
        val modelOf = HashMap<String, String>()
        synchronized(lock) {
            for (e in events) {
                if (e.t < cutoff) continue
                val k = "${e.provider.id}::${e.model}"
                val acc = sums.getOrPut(k) { LongArray(2) }
                acc[0] += e.inTok; acc[1] += e.outTok
                provOf[k] = e.provider; modelOf[k] = e.model
            }
        }
        var cost = 0.0
        for ((k, acc) in sums) {
            val p = PricingCache.getPricing(context, provOf.getValue(k), modelOf.getValue(k))
            cost += acc[0] * p.promptPrice + acc[1] * p.completionPrice
        }
        return cost
    }
}
