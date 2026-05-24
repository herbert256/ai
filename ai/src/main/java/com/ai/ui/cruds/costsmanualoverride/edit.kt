package com.ai.ui.cruds.costsmanualoverride

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.Settings
import com.ai.ui.admin.AddManualOverrideScreen

/** Edit an existing manual cost override. Reuses the rich
 *  [AddManualOverrideScreen] form (provider/model picker + 👯 dup). */
@Composable
internal fun CostOverrideEdit(
    row: CostOverrideRow,
    aiSettings: Settings,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    AddManualOverrideScreen(
        aiSettings = aiSettings,
        initialProviderId = row.providerId,
        initialModel = row.model,
        initialInputPerMillion = row.promptPrice * 1_000_000,
        initialOutputPerMillion = row.completionPrice * 1_000_000,
        isEditingExisting = true,
        originalProviderId = row.providerId,
        originalModel = row.model,
        onSave = { provider, model, inp, outp, isAddMode ->
            // Plain edit (not duplicate) repointed to a different
            // (provider, model): prune the original composite key first so
            // the edit is a MOVE, not a duplicate (mirrors the
            // blocked/test-excluded CRUD pattern). In duplicate mode keep
            // the original as a parallel override.
            if (!isAddMode && (provider.id != row.providerId || model != row.model)) {
                AppService.findById(row.providerId)?.let {
                    PricingCache.removeManualPricing(context, it, row.model)
                }
            }
            PricingCache.setManualPricing(context, provider, model, inp, outp); onSaved()
        },
        onBack = onBack,
        onNavigateHome = onNavigateHome
    )
}
