package com.ai.data

/** Bucket counts for final Fan Out pair HTTP statuses. */
data class FanOutHttpStatusCounts(
    val ok200: Int = 0,
    val rate429: Int = 0,
    val overloaded529: Int = 0,
    val client4xx: Int = 0,
    val server5xx: Int = 0,
    val other: Int = 0,
    val noHttp: Int = 0
) {
    val total: Int get() = ok200 + rate429 + overloaded529 + client4xx + server5xx + other + noHttp
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
    val totalPairs: Int,
    val modelCount: Int,
    val noHttpCount: Int,
    val rows: List<FanOutHttpStatusRow>
)

fun computeFanOutHttpStatusStats(pairs: Collection<PairState>): FanOutHttpStatusStats {
    val grouped = LinkedHashMap<String, MutableFanOutHttpStatusCounts>()
    pairs.forEach { pair ->
        val key = "${pair.providerId}|${pair.model}"
        grouped.getOrPut(key) { MutableFanOutHttpStatusCounts(pair.providerId, pair.model) }
            .add(pair.httpStatusCode)
    }
    val rows = grouped.values
        .map { it.toRow() }
        .sortedWith(
            compareByDescending<FanOutHttpStatusRow> { it.counts.non200 }
                .thenBy { it.model.lowercase() }
                .thenBy { it.providerId.lowercase() }
        )
    return FanOutHttpStatusStats(
        totalPairs = rows.sumOf { it.counts.total },
        modelCount = rows.size,
        noHttpCount = rows.sumOf { it.counts.noHttp },
        rows = rows
    )
}

private class MutableFanOutHttpStatusCounts(
    private val providerId: String,
    private val model: String
) {
    private var ok200 = 0
    private var rate429 = 0
    private var overloaded529 = 0
    private var client4xx = 0
    private var server5xx = 0
    private var other = 0
    private var noHttp = 0

    fun add(code: Int?) {
        when {
            code == null -> noHttp++
            code == 200 -> ok200++
            code == 429 -> rate429++
            code == 529 -> overloaded529++
            code in 400..499 -> client4xx++
            code in 500..599 -> server5xx++
            else -> other++
        }
    }

    fun toRow(): FanOutHttpStatusRow = FanOutHttpStatusRow(
        providerId = providerId,
        model = model,
        counts = FanOutHttpStatusCounts(
            ok200 = ok200,
            rate429 = rate429,
            overloaded529 = overloaded529,
            client4xx = client4xx,
            server5xx = server5xx,
            other = other,
            noHttp = noHttp
        )
    )
}
