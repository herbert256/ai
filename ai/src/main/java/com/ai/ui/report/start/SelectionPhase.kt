package com.ai.ui.report.start
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.modelInfoClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pre-generation half of the Reports result page. Owns the model
 *  picker chips (+Agent / +Flock / +Swarm / +Report / +Model), the
 *  list of currently-selected models with badges + per-row pricing,
 *  the Knowledge attach row, and the bottom Generate / Update
 *  buttons. Receives every action as a hoisted callback so it stays
 *  independent of [com.ai.viewmodel.ReportViewModel].
 *
 *  Split out of `ReportScreen.kt` so the two big phases (selection
 *  vs generation) live in their own files; the parent `ReportsScreen`
 *  still owns the state and decides which phase to compose. */
@Composable
internal fun ColumnScope.SelectionPhase(
    models: List<ReportModel>,
    aiSettings: Settings,
    selectedParametersIds: List<String>,
    advancedParameters: AgentParameters?,
    editModeReportId: String?,
    onAddFlock: () -> Unit,
    onAddAgent: () -> Unit,
    onAddSwarm: () -> Unit,
    onAddModel: () -> Unit,
    onAddAllModels: () -> Unit,
    onAddFromReport: () -> Unit,
    onRemoveModel: (Int) -> Unit,
    onClearAll: () -> Unit,
    onAdvancedParams: () -> Unit,
    onParametersChange: (List<String>) -> Unit,
    onGenerate: (ReportType) -> Unit,
    /** Persist the current picked models as a named swarm (F53) — null
     *  hides the affordance. */
    onSaveSelectionAsSwarm: ((String) -> Unit)? = null,
    /** Per-row parameter presets (F47) — keyed by agent id /
     *  "swarm:provider:model"; 🌡️ on each row opens the preset picker. */
    rowParams: Map<String, List<String>> = emptyMap(),
    onPickRowParams: ((String) -> Unit)? = null,
    onUpdateModelList: () -> Unit,
    attachedKnowledgeBaseIds: List<String> = emptyList(),
    onAttachKnowledgeBases: (List<String>) -> Unit = {},
    /** Master experimental-features gate. When false the Knowledge
     *  attach button is hidden — already-attached KBs on the report
     *  keep sending context at API time. */
    experimentalFeatures: Boolean = false,
    /** Per-report system prompt override. When non-null at generation
     *  time, replaces the per-agent / per-flock / external-intent
     *  system prompt for every agent in this report. */
    selectedSystemPromptId: String? = null,
    onSystemPromptChange: (String?) -> Unit = {}
) {
    val context = LocalContext.current

    // Primary CTA hoisted to the top of SelectionPhase — Next
    // (advances to "Report - select workers", where Generate report
    // fires) for a fresh report, Update model list when entered via
    // Edit / Models on a finished report. Gated on
    // `models.isNotEmpty()`, same behaviour as the previous
    // bottom-of-page version. Clear stays at the bottom as a
    // disposal action.
    if (editModeReportId != null) {
        OutlinedButton(
            onClick = onUpdateModelList,
            enabled = models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Update model list", maxLines = 1, softWrap = false) }
    } else {
        OutlinedButton(
            onClick = { onGenerate(ReportType.CLASSIC) },
            enabled = models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Next", maxLines = 1, softWrap = false) }
    }
    Spacer(modifier = Modifier.height(8.dp))

    // +Report only makes sense when at least one saved report exists
    // — querying ReportStorage on entry. SelectionPhase doesn't get
    // re-composed mid-flight so a one-shot read is enough. Default
    // false so the row doesn't briefly flicker the button before the
    // IO read returns.
    val hasAnyReport by androidx.compose.runtime.produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getAllReports(context).isNotEmpty()
        }
    }

    // Add buttons. The all-models picker (+Model) supersedes the
    // provider-then-model two-step, so the +Provider variant has been
    // dropped from the row. The unused onAddModel callback stays in
    // the signature for now but is ignored here. Local LLMs surface
    // inside +Model under the "Local" provider when installed —
    // there's no separate +Local button. +Model sits at the rightmost
    // position; +Report is omitted when no saved reports exist.
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val entries = mutableListOf(
            "Agent" to onAddAgent,
            "Flock" to onAddFlock,
            "Swarm" to onAddSwarm
        )
        if (hasAnyReport) entries.add("Report" to onAddFromReport)
        entries.add("Model" to onAddAllModels)
        entries.forEach { (label, action) ->
            OutlinedButton(onClick = action, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 40.dp), colors = AppColors.outlinedButtonColors()) {
                Text("+$label", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Count line — shows the selected model count between the +chip row
    // and the list so the user sees at a glance how many models the
    // report will run against. Sort the list by model name so the
    // order is stable as items are added; the model id is the user-
    // facing identifier and is what they expect to scan alphabetically.
    // Summed price context — the same roll-up the flock/swarm pickers
    // show, so committing a large panel carries a sense of magnitude
    // (catalog $/M rates; actual spend depends on token counts).
    val summedPerMillion = remember(models) {
        var inP = 0.0; var outP = 0.0
        models.forEach { e ->
            val p = aiSettings.getModelPricing(e.provider, e.model)
                ?: com.ai.data.PricingCache.getPricing(context, e.provider, e.model)
            inP += p.promptPrice; outP += p.completionPrice
        }
        inP * 1_000_000 to outP * 1_000_000
    }
    Text(
        when (val n = models.size) {
            0 -> "No models selected"
            1 -> "1 model"
            else -> "$n models"
        } + if (models.isNotEmpty())
            String.format(
                java.util.Locale.US,
                " · combined \$%.2f in / \$%.2f out per 1M tokens",
                summedPerMillion.first, summedPerMillion.second
            )
        else "",
        fontSize = 11.sp, color = AppColors.TextTertiary,
        modifier = Modifier.padding(bottom = 4.dp)
    )

    // Save the hand-assembled panel as a reusable swarm — it used to be
    // recoverable only via the report it came from or by rebuilding it
    // in Settings.
    if (onSaveSelectionAsSwarm != null && models.size >= 2) {
        var showSaveSwarm by remember { mutableStateOf(false) }
        var swarmName by remember { mutableStateOf("") }
        Text(
            "💾 Save selection as swarm",
            fontSize = 11.sp, color = AppColors.InfoAccent,
            modifier = Modifier
                .clickable { swarmName = ""; showSaveSwarm = true }
                .padding(bottom = 6.dp)
        )
        if (showSaveSwarm) {
            AlertDialog(
                onDismissRequest = { showSaveSwarm = false },
                title = { Text("Save as swarm") },
                text = {
                    Column {
                        Text("Saves the ${models.size} picked models as a reusable swarm (Setup → Swarms).", fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = swarmName, onValueChange = { swarmName = it },
                            label = { Text("Swarm name") },
                            singleLine = true, colors = AppColors.outlinedFieldColors()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = swarmName.isNotBlank(),
                        onClick = {
                            showSaveSwarm = false
                            onSaveSelectionAsSwarm(swarmName.trim())
                        }
                    ) { Text("Save", maxLines = 1, softWrap = false) }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveSwarm = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
                }
            )
        }
    }

    // Selected models list — sorted by model name (case-insensitive),
    // with sortedIndices so the per-row delete callback still removes
    // the right entry from the caller's original list. Each row gets
    // the shared advisory dim treatment (alpha 0.4 + leading
    // ⏳/🚫/🔒 badge + reason caption) when one of cooldown / blocked
    // / inaccessible applies, matching every other model picker.
    val advisory = com.ai.ui.shared.rememberModelAdvisoryLookup(aiSettings)
    val sortedIndices = remember(models) {
        models.indices.sortedBy { models[it].model.lowercase() }
    }
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (models.isEmpty()) {
            // Empty state already covered by the count line above.
        } else {
            sortedIndices.forEach { index ->
                val entry = models[index]
                val pricing = formatPricingPerMillion(context, entry.provider, entry.model)
                val state = advisory.stateFor(entry.provider.id, entry.model)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .modelInfoClickable(entry.provider, entry.model)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).alpha(state.rowAlpha)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(com.ai.ui.shared.shortModelName(entry.model), fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(entry.provider, entry.model))
                            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(entry.provider, entry.model))
                            com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(entry.provider, entry.model))
                            com.ai.ui.shared.ModelAdvisoryBadges(state)
                        }
                        Text("${entry.provider.id}${if (entry.sourceName.isNotBlank()) " via ${entry.sourceName}" else ""}", fontSize = 11.sp, color = AppColors.TextTertiary)
                        com.ai.ui.shared.ModelAdvisoryCaptions(state)
                    }
                    Text(pricing.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (pricing.isDefault) AppColors.SurfaceDark else AppColors.DangerAccent,
                        modifier = (if (pricing.isDefault) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                            .alpha(state.rowAlpha))
                    if (onPickRowParams != null) {
                        // Per-model 🌡️ — comparing models under DIFFERENT
                        // settings in one report (GPT @0.2 vs Claude @1.0)
                        // used to be impossible; the picked presets ride
                        // selectionParamsById into dispatch and regenerate.
                        val rowKey = if (entry.type == "agent" && !entry.agentId.isNullOrBlank()) entry.agentId!!
                            else "swarm:${entry.provider.id}:${entry.model}"
                        val hasParams = rowParams[rowKey].orEmpty().isNotEmpty()
                        Text(
                            "🌡️", fontSize = 14.sp,
                            modifier = Modifier
                                .alpha(if (hasParams) 1f else 0.45f)
                                .clickable { onPickRowParams(rowKey) }
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // ✕ stays clickable independently and at full
                    // opacity so the user can always remove a dimmed
                    // model from the list.
                    Text(com.ai.data.MetadataIconsHolder.current.closeMark, color = AppColors.DangerAccent, fontSize = 14.sp, modifier = Modifier.clickable { onRemoveModel(index) })
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Knowledge attach — multi-select over saved KBs, snapshot lives
    // in UiState.attachedKnowledgeBaseIds and gets copied onto the
    // new Report when generation kicks off. analyzeWithAgent reads
    // it via the per-call Report at dispatch time.
    val ctx = LocalContext.current
    var showKbDialog by remember { mutableStateOf(false) }
    val kbRefreshTick = com.ai.ui.shared.resumeRefreshTick()
    val allKbs = remember(kbRefreshTick) { com.ai.data.KnowledgeStore.listKnowledgeBases(ctx) }
    if (experimentalFeatures && allKbs.isNotEmpty()) {
        OutlinedButton(
            onClick = { showKbDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) {
            val n = attachedKnowledgeBaseIds.size
            val icon = com.ai.data.MetadataIconsHolder.current.library
            val label = if (n == 0) "$icon Attach knowledge" else "$icon Attached: $n"
            Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (showKbDialog) {
        com.ai.ui.knowledge.KnowledgeAttachDialog(
            knowledgeBases = allKbs,
            initialSelectedIds = attachedKnowledgeBaseIds.toSet(),
            onDismiss = { showKbDialog = false },
            onConfirm = { selected ->
                onAttachKnowledgeBases(selected.toList())
                showKbDialog = false
            }
        )
    }

    // Parameters + System prompt preset selectors now live on the
    // bottom-bar 🌡️ / 🎭 icons (wired on this screen's TitleBar in
    // SelectModels.kt); the inline buttons were removed.

    // Clear-all moved to the 🧽 bottom-bar icon (wired on this screen's
    // TitleBar in SelectModels.kt, shown when the selection is non-empty).
}
