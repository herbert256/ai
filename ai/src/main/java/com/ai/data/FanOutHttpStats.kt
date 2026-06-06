package com.ai.data

/** Bucket counts of Fan Out HTTP responses for one model. Counts every
 *  response RECEIVED (each retry attempt included), not final per-pair
 *  status — so a 429 that's retried then succeeds shows as one 429 + one 200.
 *  Built in memory by [RunHttpStats]. */
data class FanOutHttpStatusCounts(
    val ok200: Int = 0,
    val rate429: Int = 0,
    val overloaded529: Int = 0,
    val client4xx: Int = 0,
    val server5xx: Int = 0,
    val other: Int = 0
) {
    val total: Int get() = ok200 + rate429 + overloaded529 + client4xx + server5xx + other
    val non200: Int get() = total - ok200
}

data class FanOutHttpStatusRow(
    val providerId: String,
    val model: String,
    val counts: FanOutHttpStatusCounts
) {
    val modelKey: String get() = "$providerId|$model"
}

data class FanOutHttpStatusStats(
    val totalResponses: Int,
    val modelCount: Int,
    val rows: List<FanOutHttpStatusRow>
)
