package com.ai.data

/** Bounds the number of items admitted by one report operation. */
object ReportWorkLimits {
    const val MAX_ITEMS = 5_000

    fun checkSize(size: Int) {
        require(size in 0..MAX_ITEMS) {
            "This operation has $size items. Limit is $MAX_ITEMS; reduce participants or scope."
        }
    }
}
