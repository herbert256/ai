package com.ai.ui

import java.util.Locale

internal fun formatCompactNumber(value: Long): String {
    return when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

internal fun formatTokenPricePerMillion(pricePerToken: Double): String {
    val pricePerMillion = pricePerToken * 1_000_000
    return when {
        pricePerMillion >= 1 -> String.format(Locale.US, "$%.2f / 1M tokens", pricePerMillion)
        pricePerMillion >= 0.01 -> String.format(Locale.US, "$%.4f / 1M tokens", pricePerMillion)
        else -> String.format(Locale.US, "$%.6f / 1M tokens", pricePerMillion)
    }
}

internal fun formatUsd(value: Double, decimals: Int = 8): String {
    return String.format(Locale.US, "$%.${decimals}f", value)
}

internal fun formatCents(value: Double, decimals: Int = 4): String {
    return String.format(Locale.US, "%.${decimals}f", value * 100)
}

internal fun formatDecimal(value: Double, decimals: Int = 2): String {
    return String.format(Locale.US, "%.${decimals}f", value)
}
