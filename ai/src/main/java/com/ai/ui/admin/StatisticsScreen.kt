package com.ai.ui.admin

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class StatWithCost(val stat: UsageStats, val inputCost: Double, val outputCost: Double, val totalCost: Double, val pricingSource: String)
internal data class ProviderCostGroup(val provider: AppService, val models: List<StatWithCost>, val totalCost: Double, val totalCalls: Int)

/** Pure (no-Compose) resolution of raw [UsageStats] into per-provider
 *  cost groups, sorted by spend. New rows carry persisted call-time
 *  cost, so this is a cheap grouping pass; only legacy rows written
 *  before cost caching fall back to [PricingCache.getPricing]. */
internal fun buildProviderCostGroups(context: Context, stats: Map<String, UsageStats>): List<ProviderCostGroup> =
    stats.values.groupBy { it.provider }.map { (provider, providerStats) ->
        val models = providerStats.map { stat ->
            if (stat.inputCost != null || stat.outputCost != null) {
                val ic = stat.inputCost ?: 0.0
                val oc = stat.outputCost ?: 0.0
                StatWithCost(stat, ic, oc, ic + oc, stat.pricingSource ?: "")
            } else {
                val pricing = PricingCache.getPricing(context, stat.provider, stat.model)
                val ic = if (stat.searchUnits > 0) stat.searchUnits * pricing.perQueryPrice
                         else stat.inputTokens * pricing.promptPrice
                val oc = if (stat.searchUnits > 0) 0.0
                         else stat.outputTokens * pricing.completionPrice
                StatWithCost(stat, ic, oc, ic + oc, pricing.source)
            }
        }.sortedByDescending { it.totalCost }
        ProviderCostGroup(provider, models, models.sumOf { it.totalCost }, models.sumOf { it.stat.callCount })
    }.sortedByDescending { it.totalCost }

@Composable
internal fun UsageProviderCard(
    group: ProviderCostGroup, isExpanded: Boolean, onToggle: () -> Unit,
    onModelClick: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.provider.id, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("${group.totalCalls} calls", fontSize = 12.sp, color = AppColors.TextTertiary)
                }
                Text(formatCurrency(group.totalCost), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.SuccessAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isExpanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (isExpanded) {
                HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.padding(vertical = 8.dp))
                group.models.forEach { swc ->
                    UsageModelRow(swc, onClick = { onModelClick(swc.stat.model) })
                }
            }
        }
    }
}

@Composable
private fun UsageModelRow(swc: StatWithCost, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(com.ai.ui.shared.shortModelName(swc.stat.model), fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // Type pill — only shown for non-default kinds (rerank,
                // summarize). The default "report" kind matches the
                // implicit assumption and would just be visual noise.
                // Defensive cast: legacy rows written before the kind
                // field existed deserialize to a runtime-null String
                // even though the property is declared non-null;
                // SettingsPreferences.loadUsageStats backfills these on
                // load but coerce again here so an in-flight write
                // can't trip the renderer.
                @Suppress("USELESS_CAST")
                val kind = (swc.stat.kind as String?) ?: "report"
                if (kind != "report") {
                    val kindColor = when (kind) {
                        "rerank" -> AppColors.WarningAccent
                        "summarize" -> AppColors.SecondaryAccent
                        "compare" -> AppColors.PrimaryAccent
                        "moderation" -> AppColors.DangerAccent
                        "translate" -> AppColors.InfoAccent
                        else -> AppColors.TextDim
                    }
                    Text(
                        text = kind,
                        fontSize = 9.sp,
                        color = kindColor,
                        modifier = Modifier.padding(start = 6.dp).background(AppColors.SurfaceDark, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            val secondaryLine = if (swc.stat.kind == "rerank") {
                "${swc.stat.callCount} calls, ${formatCompactNumber(swc.stat.searchUnits)} search units"
            } else {
                "${swc.stat.callCount} calls, ${formatCompactNumber(swc.stat.inputTokens)}/${formatCompactNumber(swc.stat.outputTokens)} tokens"
            }
            Text(secondaryLine, fontSize = 11.sp, color = AppColors.TextTertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatCurrency(swc.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent)
            val sourceColor = when (swc.pricingSource) {
                "OVERRIDE" -> AppColors.WarningAccent; "OPENROUTER" -> AppColors.InfoAccent; "LITELLM" -> AppColors.PrimaryAccent
                else -> AppColors.TextDim
            }
            Text(swc.pricingSource, fontSize = 10.sp, color = sourceColor)
        }
    }
}

/**
 * Direct-entry wrapper around [AddManualOverrideScreen] for the "Add
 * manual cost override" link on Model Info — opens the same form
 * pre-filled with the given provider/model. If a cost override already
 * exists for that pair, its current input/output prices are loaded so
 * the screen doubles as an edit form. Saving writes through PricingCache
 * .setManualPricing and pops back to the caller.
 */
@Composable
fun ManualCostOverrideEntryScreen(
    aiSettings: Settings,
    providerId: String,
    modelId: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    val existing = AppService.findById(providerId)?.let { svc ->
        PricingCache.getManualPricing(context, svc, modelId)
    }
    AddManualOverrideScreen(
        aiSettings = aiSettings,
        initialProviderId = providerId,
        initialModel = modelId,
        initialInputPerMillion = existing?.promptPrice?.times(1_000_000),
        initialOutputPerMillion = existing?.completionPrice?.times(1_000_000),
        // When an override is already on file for this (provider, model),
        // surface the bottom-bar 👯 — the user can keep the prices and
        // repoint at a different (provider, model) to add a parallel
        // override without retyping.
        isEditingExisting = existing != null,
        originalProviderId = providerId.takeIf { existing != null },
        originalModel = modelId.takeIf { existing != null },
        onSave = { provider, model, inp, outp, isAddMode ->
            // Plain edit (not duplicate) that repointed to a new key:
            // prune the original so the edit is a MOVE, not a duplicate.
            if (!isAddMode && existing != null && (provider.id != providerId || model != modelId)) {
                AppService.findById(providerId)?.let {
                    PricingCache.removeManualPricing(context, it, modelId)
                }
            }
            PricingCache.setManualPricing(context, provider, model, inp, outp)
            onBack()
        },
        onBack = onBack,
        onNavigateHome = onNavigateHome
    )
}

@Composable
internal fun AddManualOverrideScreen(
    aiSettings: Settings,
    /** Save callback. The trailing Boolean is `isAddMode` — true when
     *  the form is in add/duplicate mode (caller should KEEP any original
     *  override as a parallel entry), false on a plain edit (caller should
     *  MOVE: prune the original key when the user repointed). */
    onSave: (AppService, String, Double, Double, Boolean) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    initialProviderId: String? = null,
    initialModel: String? = null,
    initialInputPerMillion: Double? = null,
    initialOutputPerMillion: Double? = null,
    /** True when the caller has loaded an existing override into the
     *  form. Surfaces the bottom-bar 👯 duplicate icon. */
    isEditingExisting: Boolean = false,
    /** When [isEditingExisting], the (provider, model) of the source
     *  override — used to disable Save in duplicate mode when the
     *  user hasn't repointed yet, so the original isn't overwritten. */
    originalProviderId: String? = null,
    originalModel: String? = null
) {
    BackHandler { onBack() }
    var resetTick by remember { mutableStateOf(0) }
    var selectedProvider by remember(resetTick) {
        mutableStateOf(initialProviderId?.let { AppService.findById(it) } ?: AppService.entries.firstOrNull())
    }
    var model by remember(resetTick) { mutableStateOf(initialModel ?: "") }
    var inputPrice by remember(resetTick) { mutableStateOf(initialInputPerMillion?.let { "%.4f".format(Locale.US, it) } ?: "") }
    var outputPrice by remember(resetTick) { mutableStateOf(initialOutputPerMillion?.let { "%.4f".format(Locale.US, it) } ?: "") }
    var showProviderSelect by remember { mutableStateOf(false) }
    var showModelSelect by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val dup = com.ai.ui.shared.rememberDuplicateMode(
        isEditingExisting = isEditingExisting
    )
    val isAddMode = dup.isAddMode
    val keyMatchesOriginal = originalProviderId != null && originalModel != null &&
        selectedProvider?.id == originalProviderId && model == originalModel

    // Full-screen overlays for selection
    if (showProviderSelect && selectedProvider != null) {
        com.ai.ui.shared.SelectProviderScreen(aiSettings = aiSettings,
            onSelectProvider = { selectedProvider = it; showProviderSelect = false },
            onBack = { showProviderSelect = false }, onNavigateHome = onNavigateHome)
        return
    }
    if (showModelSelect && selectedProvider != null) {
        com.ai.ui.shared.SelectModelScreen(provider = selectedProvider!!, aiSettings = aiSettings, currentModel = model,
            onSelectModel = { model = it; showModelSelect = false },
            onBack = { showModelSelect = false }, onNavigateHome = onNavigateHome)
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "cost_override",
            title = if (isAddMode) "Add Override" else "Edit Override",
            subject = "Set input/output \$/M for one model",
            onBackClick = onBack,
            onCopyReport = null,
            onClear = { resetTick++ }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            val inp = inputPrice.toDoubleOrNull()?.div(1_000_000)
            val outp = outputPrice.toDoubleOrNull()?.div(1_000_000)
            if (inp != null && outp != null && selectedProvider != null && model.isNotBlank()) onSave(selectedProvider!!, model, inp, outp, isAddMode)
        }, enabled = selectedProvider != null && model.isNotBlank() && inputPrice.toDoubleOrNull() != null && outputPrice.toDoubleOrNull() != null &&
            !(isAddMode && keyMatchesOriginal),
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppColors.SuccessAccent)
        ) { Text(if (isAddMode) "Add" else "Save", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showProviderSelect = true }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                Text("Provider: ${selectedProvider?.id ?: "Select"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model") }, modifier = Modifier.weight(1f), singleLine = true, colors = AppColors.outlinedFieldColors())
                OutlinedButton(onClick = { showModelSelect = true }, colors = AppColors.outlinedButtonColors()) { Text("Select", maxLines = 1, softWrap = false) }
            }

            // Show current pricing for reference
            if (selectedProvider != null && model.isNotBlank()) {
                val current = PricingCache.getPricingWithoutOverride(context, selectedProvider!!, model)
                Text("Current: input ${formatTokenPricePerMillion(current.promptPrice)}, output ${formatTokenPricePerMillion(current.completionPrice)} (${current.source})",
                    fontSize = 11.sp, color = AppColors.TextTertiary)
            }

            OutlinedTextField(value = inputPrice, onValueChange = { inputPrice = it },
                label = { Text("Input price ($/1M tokens)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
            OutlinedTextField(value = outputPrice, onValueChange = { outputPrice = it },
                label = { Text("Output price ($/1M tokens)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
        }

    }
}

// Formatting helpers
private fun formatCurrency(value: Double): String {
    return if (value < 0.01 && value > 0) String.format(Locale.US, "$%.6f", value)
    else String.format(Locale.US, "$%.4f", value)
}
