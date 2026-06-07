package com.ai.ui.helpers
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*

import com.ai.data.AppService
import com.ai.data.PricingCache
import java.util.Locale

internal data class FormattedPricing(val text: String, val isDefault: Boolean)

internal fun formatPricingPerMillion(context: android.content.Context, provider: AppService, model: String): FormattedPricing {
    val p = PricingCache.getPricing(context, provider, model)
    val fmtIn = if (p.promptPrice * 1_000_000 < 0.01 && p.promptPrice > 0) "<.01" else String.format(Locale.US, "%.2f", p.promptPrice * 1_000_000)
    val fmtOut = if (p.completionPrice * 1_000_000 < 0.01 && p.completionPrice > 0) "<.01" else String.format(Locale.US, "%.2f", p.completionPrice * 1_000_000)
    return FormattedPricing("$fmtIn / $fmtOut", p.source == "DEFAULT")
}
