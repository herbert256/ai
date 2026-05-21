package com.ai.ui.cruds.costsmanualoverride

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ai.data.PricingCache
import com.ai.model.Settings
import com.ai.ui.admin.AddManualOverrideScreen

/** Add a new manual cost override. [prefill] is set when arriving via
 *  Copy — the prices carry over and the user repoints the model. */
@Composable
internal fun CostOverrideAdd(
    prefill: CostOverrideRow?,
    aiSettings: Settings,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    AddManualOverrideScreen(
        aiSettings = aiSettings,
        initialProviderId = prefill?.providerId,
        initialModel = prefill?.model,
        initialInputPerMillion = prefill?.promptPrice?.times(1_000_000),
        initialOutputPerMillion = prefill?.completionPrice?.times(1_000_000),
        onSave = { provider, model, inp, outp ->
            PricingCache.setManualPricing(context, provider, model, inp, outp); onSaved()
        },
        onBack = onBack,
        onNavigateHome = onNavigateHome
    )
}
