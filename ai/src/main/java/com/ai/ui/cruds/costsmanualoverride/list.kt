package com.ai.ui.cruds.costsmanualoverride

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage
import com.ai.ui.shared.formatTokenPricePerMillion
import com.ai.ui.shared.resumeRefreshTick
import com.ai.ui.shared.shortModelName

/** One manual price-override row, derived from the
 *  `PricingCache.getAllManualPricing` map (keyed "provider:model"). */
internal data class CostOverrideRow(
    val providerId: String,
    val model: String,
    val promptPrice: Double,
    val completionPrice: Double
) {
    val key get() = "$providerId:$model"
}

private sealed interface Mode {
    data object List : Mode
    data class View(val row: CostOverrideRow) : Mode
    data class Edit(val row: CostOverrideRow) : Mode
    data class Add(val prefill: CostOverrideRow?) : Mode
}

/**
 * Manual cost-override CRUD (AI Setup → Costs). Writes straight to
 * [PricingCache] (overrides aren't part of Settings); the store isn't
 * reactive, so a refresh trigger re-reads after each write. Bulk
 * cleanup / CSV moved to Housekeeping → Costs.
 */
@Composable
fun CostManualOverridesCrud(
    aiSettings: Settings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val resumeTick = resumeRefreshTick()
    val rows = remember(refreshTrigger, resumeTick) {
        PricingCache.getAllManualPricing(context).entries.map { (k, p) ->
            val parts = k.split(":", limit = 2)
            CostOverrideRow(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, p.promptPrice, p.completionPrice)
        }.sortedBy { it.key.lowercase() }
    }
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val refreshAndList = { refreshTrigger++; mode = Mode.List }
    val remove: (CostOverrideRow) -> Unit = { r ->
        AppService.findById(r.providerId)?.let { PricingCache.removeManualPricing(context, it, r.model); refreshTrigger++ }
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Manual cost overrides",
            helpTopic = "cost_config",
            items = rows,
            line = { "${it.providerId} · ${shortModelName(it.model)} · ${formatTokenPricePerMillion(it.promptPrice)} / ${formatTokenPricePerMillion(it.completionPrice)}" },
            itemKey = { it.key },
            onView = { mode = Mode.View(it) },
            onEdit = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add(null) },
            onCopy = { mode = Mode.Add(it) },
            onDelete = { remove(it) },
            deleteName = { "${it.providerId} · ${it.model}" },
            onBack = onBack,
            addLabel = "Add manual override",
            emptyMessage = "No manual price overrides configured.\nPricing uses automatic lookup."
        )
        is Mode.View -> CostOverrideView(
            row = m.row,
            onEdit = { mode = Mode.Edit(m.row) },
            onCopy = { mode = Mode.Add(m.row) },
            onDelete = { remove(m.row); toList() },
            onBack = toList
        )
        is Mode.Edit -> CostOverrideEdit(
            row = m.row, aiSettings = aiSettings,
            onSaved = refreshAndList, onBack = toList, onNavigateHome = onNavigateHome
        )
        is Mode.Add -> CostOverrideAdd(
            prefill = m.prefill, aiSettings = aiSettings,
            onSaved = refreshAndList, onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
