package com.ai.ui.report

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Per-agent icon UI snapshot mirrored from disk for the result
 *  list. Populated by [com.ai.viewmodel.ReportViewModel.runReportIcons]
 *  writing through [ReportStorage.updateReportAgentIcon]; the parent
 *  screen rebuilds this map on every iconRefreshTick bump. */
data class AgentIconRow(val icon: String?, val cost: Double)

/** One item in a conditional "View" group (Meta / Rerank / Fan-out /
 *  Fan-in / Fan-in-model / Translate). Carries its on-screen label,
 *  the lambda that opens that item's detail, and the source
 *  [com.ai.model.InternalPrompt] when one is available (meta-style
 *  rows). The InternalPrompt drives the [ViewAiReportScreen] meta
 *  tile's dynamic icon — its cached per-prompt emoji replaces the
 *  static 🧠 fallback. */
internal data class EveryItem(
    val label: String,
    val prompt: com.ai.model.InternalPrompt? = null,
    /** Target language (English name, e.g. "Dutch") when the row
     *  this item represents was produced by a translation pass.
     *  Drives the [ViewAiReportScreen] meta tile's icon to the
     *  cached `translation_icon` emoji for that language instead
     *  of the prompt's own per-prompt emoji. Null on rows that
     *  aren't translation-scoped. */
    val targetLanguage: String? = null,
    val open: () -> Unit
)

/** Group [secondaryRuns] into the six conditional kinds the View
 *  surface offers. Returns a map keyed by `"meta"` / `"rerank"` /
 *  `"fan_out"` / `"fan_in"` / `"fan-in-model"` / `"translate"`.
 *  Empty lists for kinds with no rows so callers can hide that
 *  tile / button entirely. */
internal fun buildEveryItems(
    secondaryRuns: List<com.ai.data.SecondaryResult>,
    aiSettings: com.ai.model.Settings,
    onOpenSecondaryRun: (String) -> Unit,
    onViewSecondaryName: (String, SecondaryKind) -> Unit,
    onOpenTranslationRun: (String) -> Unit
): Map<String, List<EveryItem>> {
    val nameToCat = aiSettings.internalPrompts.associate { it.name to it.category.lowercase() }
    val promptByName = aiSettings.internalPrompts.associateBy { it.name }
    fun categoryOf(row: com.ai.data.SecondaryResult): String? =
        row.metaPromptName?.let { nameToCat[it] }

    val meta = secondaryRuns
        .filter { it.kind == SecondaryKind.META && categoryOf(it) == "meta" }
        .map { row ->
            EveryItem(
                label = row.metaPromptName ?: "Meta",
                prompt = row.metaPromptName?.let { promptByName[it] },
                targetLanguage = row.targetLanguage?.takeIf { it.isNotBlank() },
                open = { onOpenSecondaryRun(row.id) }
            )
        }
    val rerank = secondaryRuns
        .filter { it.kind == SecondaryKind.RERANK }
        .map { row -> EveryItem(row.metaPromptName ?: "Rerank") { onOpenSecondaryRun(row.id) } }
    val moderation = secondaryRuns
        .filter { it.kind == SecondaryKind.MODERATION }
        .map { row -> EveryItem(row.metaPromptName ?: "Moderation") { onOpenSecondaryRun(row.id) } }
    val fanIn = secondaryRuns
        .filter { it.kind == SecondaryKind.META && categoryOf(it) == "fan_in" }
        .map { row -> EveryItem(row.metaPromptName ?: "Fan-in") { onOpenSecondaryRun(row.id) } }
    val fanInModel = secondaryRuns
        .filter { it.kind == SecondaryKind.META && categoryOf(it) == "fan-in-model" }
        .map { row -> EveryItem(row.metaPromptName ?: "Fan-in-model") { onOpenSecondaryRun(row.id) } }
    // Fan-out: one item per distinct prompt name. Tap opens the
    // SecondaryResultsScreen with nameFilter set; the screen
    // auto-renders the L2 fan-out drill-in.
    val fanOut = secondaryRuns
        .filter { it.kind == SecondaryKind.META && categoryOf(it) == "fan_out" }
        .mapNotNull { it.metaPromptName }
        .distinct()
        .map { name -> EveryItem(name) { onViewSecondaryName(name, SecondaryKind.META) } }
    // Translate: one item per translationRunId.
    val translate = secondaryRuns
        .filter { it.kind == SecondaryKind.TRANSLATE }
        .groupBy { it.translationRunId ?: "lang:${it.targetLanguage.orEmpty()}" }
        .map { (runId, rows) ->
            val first = rows.first()
            val label = first.targetLanguageNative ?: first.targetLanguage ?: "(language)"
            EveryItem(label) { onOpenTranslationRun(runId) }
        }
    return mapOf(
        "meta" to meta,
        "rerank" to rerank,
        "moderation" to moderation,
        "fan_out" to fanOut,
        "fan_in" to fanIn,
        "fan-in-model" to fanInModel,
        "translate" to translate
    )
}

/** Post-generation half of the Reports result page. Owns the
 *  two-row action bar, the pending-changes banner, and the
 *  scrollable result list (agent rows, secondary rows, fan-out
 *  summaries, translation rows, totals footer). Receives every
 *  action as a hoisted callback so it stays independent of
 *  [com.ai.viewmodel.ReportViewModel].
 *
 *  Split out of `ReportScreen.kt` together with [SelectionPhase]
 *  so the two big phases live in their own files. Helpers used
 *  only by this phase (the data-classes describing aggregated
 *  rows, the build* fns, RowTypeCell, CompactButton) live in the
 *  same file. */
/** Bundle of every lambda callback consumed by [GenerationPhase].
 *  Bundling slashes the ReportsScreen call site's bytecode (33+
 *  function-typed args become one) — load-bearing for the JVM 64 KB
 *  per-method limit on the parent. Defaulted no-ops so callers can
 *  build the bundle piecewise. */
internal data class GenerationPhaseHandlers(
    val onViewAgent: (String) -> Unit = {},
    val onShare: () -> Unit = {},
    val onTrace: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onTogglePin: () -> Unit = {},
    val onTranslate: () -> Unit = {},
    val onOpenMetaPicker: () -> Unit = {},
    val onOpenFanOutPicker: () -> Unit = {},
    val onOpenRerankPicker: () -> Unit = {},
    val onOpenModerationPicker: () -> Unit = {},
    val onOpenHtmlPreview: () -> Unit = {},
    val onViewReports: () -> Unit = {},
    val onViewPrompt: () -> Unit = {},
    val onViewCosts: () -> Unit = {},
    val onViewIcons: () -> Unit = {},
    /** Open the App Log Viewer filtered to this report's log-id. */
    val onViewLog: () -> Unit = {},
    val onEditTitle: () -> Unit = {},
    val onEditPromptInline: () -> Unit = {},
    val onEditModelsInline: () -> Unit = {},
    val onEditParametersInline: () -> Unit = {},
    val onRequestRegenerate: () -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onRequestExport: () -> Unit = {},
    val onCancelTranslation: (String) -> Unit = { _ -> },
    val onViewSecondaryName: (String, SecondaryKind) -> Unit = { _, _ -> },
    /** Open the fan-icons drill-in for a fan-out's metaPrompt
     *  name. Routes to SecondaryResultsScreen with the icon-mode
     *  flag, which mounts FanOutScreen in ICONS mode. */
    val onViewFanIcons: (String) -> Unit = { _ -> },
    val onOpenSecondaryRun: (String) -> Unit = { _ -> },
    val onOpenTranslationRun: (String) -> Unit = { _ -> },
    val onOpenMeta: () -> Unit = {},
    val onNavigateToTraceFile: (String) -> Unit = { _ -> },
    val onNavigateToTraceListFiltered: (String, String) -> Unit = { _, _ -> },
    val onOpenIconDetail: () -> Unit = {},
    val onOpenLanguageDetail: () -> Unit = {},
    val onOpenAgentIconDetail: (String) -> Unit = { _ -> },
    val onPrevReport: () -> Unit = {},
    val onNextReport: () -> Unit = {},
    val onMissingPromptIcon: (com.ai.model.InternalPrompt) -> Unit = { _ -> },
    val onOpenInternalPromptIconDetail: (com.ai.model.InternalPrompt) -> Unit = { _ -> },
    val onMissingTranslationIcon: (String) -> Unit = { _ -> },
    val onOpenTranslationIconDetail: (String) -> Unit = { _ -> }
)

@Composable
internal fun ColumnScope.GenerationPhase(
    uiState: UiState,
    isComplete: Boolean,
    reportsProgress: Int,
    reportsTotal: Int,
    reportsAgentResults: Map<String, AnalysisResponse>,
    currentReportId: String?,
    handlers: GenerationPhaseHandlers,
    secondaryCounts: SecondaryResultStorage.Counts = SecondaryResultStorage.Counts(0, 0, 0, 0),
    /** Sum of costs the user dropped from this report via Delete actions
     *  on agents / secondaries / fan-out pairs / translations. Surfaces
     *  as a dedicated row above the Total footer when non-zero. */
    costsFromDeletedItems: Double = 0.0,
    secondaryRuns: List<com.ai.data.SecondaryResult> = emptyList(),
    secondaryTotals: SecondaryTotals = SecondaryTotals.ZERO,
    translationRuns: List<com.ai.viewmodel.ReportViewModel.TranslationRunState> = emptyList(),
    translationRunSummaries: List<TranslationRunSummary> = emptyList(),
    fanOutSummaries: List<FanOutRunSummary> = emptyList(),
    metaPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    fanOutPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    /** Report.icon mirrored from disk, populated by the parent's
     *  iconRefreshTick-keyed effect. Null while the icon-gen call is
     *  in flight or when the prompt isn't configured. */
    reportIcon: String? = null,
    /** Report.iconErrorMessage mirrored from disk. Set when icon-gen
     *  errored; the inline 'icon' row flips to ❌ when non-null. */
    reportIconError: String? = null,
    /** Report.iconCost mirrored from disk. Rendered on the right of
     *  the inline 'icon' row + summed into the report total. */
    reportIconCost: Double = 0.0,
    /** Report.iconModel mirrored from disk. Non-null when the user
     *  picked an icon from the "Alternative icons" flow — the row's
     *  middle text displays this instead of the bundled icon-prompt
     *  agent's resolved model. */
    reportIconModel: String? = null,
    /** Report.languageIconInputCost + outputCost in USD. Folded into
     *  the report total so the language-detection call is visible
     *  in the grand-total row. */
    languageIconCost: Double = 0.0,
    /** Per-agent icon results mirrored from disk, keyed by agentId.
     *  The parent screen rebuilds this on every iconRefreshTick bump
     *  so the row picks up new emojis / cleared values without a
     *  manual subscribe. Rows without an entry (or with a null
     *  [AgentIconRow.icon]) render the default ✅/❌/⏳/🆕 cell. */
    agentIconRows: Map<String, AgentIconRow> = emptyMap(),
    hasPrevReport: Boolean = false,
    hasNextReport: Boolean = false
) {
    // Local aliases so the existing body keeps reading short names
    // — avoids touching every call site inside this 1000-line phase.
    val onViewAgent = handlers.onViewAgent
    val onShare = handlers.onShare
    val onTrace = handlers.onTrace
    val onDelete = handlers.onDelete
    val onCopy = handlers.onCopy
    val onTogglePin = handlers.onTogglePin
    val onTranslate = handlers.onTranslate
    val onOpenMetaPicker = handlers.onOpenMetaPicker
    val onOpenFanOutPicker = handlers.onOpenFanOutPicker
    val onOpenRerankPicker = handlers.onOpenRerankPicker
    val onOpenModerationPicker = handlers.onOpenModerationPicker
    val onOpenHtmlPreview = handlers.onOpenHtmlPreview
    val onViewReports = handlers.onViewReports
    val onViewPrompt = handlers.onViewPrompt
    val onViewCosts = handlers.onViewCosts
    val onViewIcons = handlers.onViewIcons
    val onViewLog = handlers.onViewLog
    val onEditTitle = handlers.onEditTitle
    val onEditPromptInline = handlers.onEditPromptInline
    val onEditModelsInline = handlers.onEditModelsInline
    val onEditParametersInline = handlers.onEditParametersInline
    val onRequestRegenerate = handlers.onRequestRegenerate
    val onRequestDelete = handlers.onRequestDelete
    val onRequestExport = handlers.onRequestExport
    val onCancelTranslation = handlers.onCancelTranslation
    val onViewSecondaryName = handlers.onViewSecondaryName
    val onViewFanIcons = handlers.onViewFanIcons
    val onOpenSecondaryRun = handlers.onOpenSecondaryRun
    val onOpenTranslationRun = handlers.onOpenTranslationRun
    val onOpenMeta = handlers.onOpenMeta
    val onNavigateToTraceFile = handlers.onNavigateToTraceFile
    val onNavigateToTraceListFiltered = handlers.onNavigateToTraceListFiltered
    val onOpenIconDetail = handlers.onOpenIconDetail
    val onOpenLanguageDetail = handlers.onOpenLanguageDetail
    val onOpenAgentIconDetail = handlers.onOpenAgentIconDetail
    val onPrevReport = handlers.onPrevReport
    val onNextReport = handlers.onNextReport
    val onMissingPromptIcon = handlers.onMissingPromptIcon
    val onOpenInternalPromptIconDetail = handlers.onOpenInternalPromptIconDetail
    val onMissingTranslationIcon = handlers.onMissingTranslationIcon
    val onOpenTranslationIconDetail = handlers.onOpenTranslationIconDetail

    val context = LocalContext.current
    val aiSettings = uiState.aiSettings

    fun resolveModelForResult(agentId: String, result: AnalysisResponse): String {
        return aiSettings.getAgentById(agentId)?.let { aiSettings.getEffectiveModelForAgent(it) }
            ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringAfter(':')
            ?: result.service.defaultModel
    }

    // ===== Action row (lives at the top of the page) =====
    // Two-tier toggle: Row 1 has View / Edit / Create / Action; tapping
    // a Row 1 button opens Row 2 (its sub-actions) inline; tapping the
    // same Row 1 button again closes Row 2. Sub-actions fire and then
    // collapse Row 2. The TitleBar 🔄 / 🗑 / 📤 / 💬 / ℹ️ icons stay
    // wired in parallel — duplicates with Row "Action" are intentional.
    @OptIn(ExperimentalLayoutApi::class)
    @Composable fun ActionRow(content: @Composable FlowRowScope.() -> Unit) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
    var pinTick by remember(currentReportId) { mutableStateOf(0) }
    val isPinned by produceState(initialValue = false, currentReportId, pinTick) {
        value = currentReportId?.let { rid ->
            withContext(Dispatchers.IO) { ReportStorage.getReport(context, rid)?.pinned == true }
        } ?: false
    }

    // Row 1 active group: "view" / "edit" / "create" / "action" / null.
    // rememberSaveable so a rotation doesn't collapse the sub-row in
    // the middle of the user reading it.
    var activeBar by rememberSaveable { mutableStateOf<String?>(null) }
    // Row 3 active "every:" kind under View. Possible values: "meta" /
    // "rerank" / "fan_out" / "fan_in" / "fan-in-model" / "translate" /
    // null. Only meaningful when activeBar == "view".
    var activeEveryKind by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggleBar(name: String) {
        activeBar = if (activeBar == name) null else name
        // Switching Row 1 group → drop any Row 3 selection.
        activeEveryKind = null
    }
    fun close() { activeBar = null; activeEveryKind = null }

    // Per-group colour. Active button uses the full colour; inactive
    // Row-1 buttons fall back to a dim tint so the open group is
    // visually anchored.
    val viewColor = AppColors.Purple
    val editColor = AppColors.Indigo
    val createColor = AppColors.Orange
    val actionColor = AppColors.Red
    fun rowOneColor(name: String, active: Color): Color =
        if (activeBar == name) active else active.copy(alpha = 0.32f)

    // Per-kind / per-category item lists driving the View row's
    // "every:" buttons + Row 3 picker. Each item knows how to open
    // its detail directly. Builder hoisted to top-level so the new
    // [ViewAiReportScreen] shares the same grouping logic.
    // (Fan-icons are surfaced as a sibling list row off each
    //  fanOutSummary — see the items(fanOutSummaries) block —
    //  not as a View-row group, since the fan-out pair rows that
    //  carry the icons never enter `secondaryRuns`.)
    val everyItems = remember(secondaryRuns, aiSettings) {
        buildEveryItems(secondaryRuns, aiSettings,
            onOpenSecondaryRun, onViewSecondaryName, onOpenTranslationRun)
    }

    // ----- Row 1 -----
    // Same horizontal layout as the original ActionRow (View / Edit /
    // Create / Action) but with prev / next chevrons right-aligned on
    // the same line. Switching from FlowRow to a regular Row so the
    // weight-1 Spacer pushes the chevrons to the trailing edge; the
    // four buttons are short enough that wrapping isn't a real risk
    // on any phone we care about.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // "View" Row 1 button removed — the same set of sub-views is now
        // reachable via the bottom-bar ℹ️ icon (see [ViewAiReportScreen]).
        // The "view" -> { ... } Row 2 case below is also gone for the same
        // reason; the legacy handlers + GenerationPhaseHandlers fields
        // stay in place so re-adding the button is a one-line revert.
        CompactButton(onClick = { toggleBar("edit") }, color = rowOneColor("edit", editColor), text = "Edit")
        CompactButton(onClick = { toggleBar("create") }, color = rowOneColor("create", createColor), text = "Create")
        CompactButton(onClick = { toggleBar("action") }, color = rowOneColor("action", actionColor), text = "Action")
        Spacer(modifier = Modifier.weight(1f))
        // Inner Row so the two chevrons sit at zero spacing — the
        // outer Row's spacedBy(6.dp) would otherwise add a gap
        // between them. Bigger glyphs (32 dp) so they read clearly
        // as nav arrows, and a +6 dp rightward offset so the icon's
        // visible-glyph right edge lines up with the cost column on
        // the result-list rows below (the chevron SVG has built-in
        // whitespace on each side of the stroke, so an icon flush
        // to the right edge of the row still LOOKS left of the cost
        // numbers — the offset compensates). 48 dp Material touch
        // target stays via LocalMinimumInteractiveComponentSize.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.offset(x = 10.dp)
        ) {
            IconButton(
                onClick = onPrevReport,
                enabled = hasPrevReport,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous AI report",
                    tint = if (hasPrevReport) AppColors.Blue else AppColors.TextDisabled,
                    modifier = Modifier.size(48.dp)
                )
            }
            IconButton(
                onClick = onNextReport,
                enabled = hasNextReport,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "Next AI report",
                    tint = if (hasNextReport) AppColors.Blue else AppColors.TextDisabled,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }

    // ----- Row 2 (contextual) -----
    // "view" -> { ... } case removed — the sub-buttons it exposed now
    // live on [ViewAiReportScreen], reached from the bottom-bar ℹ️.
    // `everyItems` + `activeEveryKind` are still read by ViewAiReportScreen
    // via the shared builder; the Row 3 expansion lived only here.
    when (activeBar) {
        "edit" -> {
            Spacer(modifier = Modifier.height(4.dp))
            ActionRow {
                CompactButton(onClick = { close(); onEditTitle() }, color = editColor, text = "Title")
                CompactButton(onClick = { close(); onEditPromptInline() }, color = editColor, text = "Prompt")
                CompactButton(
                    onClick = { close(); onEditModelsInline() },
                    color = editColor, text = "Models",
                    enabled = currentReportId != null
                )
                CompactButton(onClick = { close(); onEditParametersInline() }, color = editColor, text = "Parameters")
            }
        }
        "create" -> {
            Spacer(modifier = Modifier.height(4.dp))
            ActionRow {
                CompactButton(
                    onClick = { close(); onOpenMetaPicker() },
                    color = createColor, text = "Meta",
                    enabled = metaPrompts.isNotEmpty()
                )
                CompactButton(onClick = {
                    // The rerank picker enumerates every configured
                    // provider's models filtered to RERANK type; on
                    // catalog-heavy setups that first composition can
                    // take a noticeable beat. Toast on tap so the user
                    // knows the click landed.
                    android.widget.Toast.makeText(context, "Loading rerank models…", android.widget.Toast.LENGTH_SHORT).show()
                    close(); onOpenRerankPicker()
                }, color = createColor, text = "Rerank")
                CompactButton(onClick = {
                    // Same loading-toast rationale as rerank — the
                    // moderation picker filters every provider's
                    // catalog down to MODERATION-typed entries.
                    android.widget.Toast.makeText(context, "Loading moderation models…", android.widget.Toast.LENGTH_SHORT).show()
                    close(); onOpenModerationPicker()
                }, color = createColor, text = "Moderation")
                CompactButton(
                    onClick = { close(); onOpenFanOutPicker() },
                    color = createColor, text = "Fan out",
                    enabled = fanOutPrompts.isNotEmpty()
                )
                CompactButton(onClick = { close(); onTranslate() }, color = createColor, text = "Translate")
            }
        }
        "action" -> {
            Spacer(modifier = Modifier.height(4.dp))
            ActionRow {
                CompactButton(
                    onClick = { close(); onRequestRegenerate() },
                    color = actionColor, text = "Regenerate",
                    enabled = currentReportId != null && isComplete
                )
                CompactButton(onClick = { close(); onCopy() }, color = actionColor, text = "Copy")
                CompactButton(onClick = { close(); onRequestDelete() }, color = actionColor, text = "Delete")
                CompactButton(
                    onClick = { onTogglePin(); pinTick++; close() },
                    color = actionColor, text = if (isPinned) "Unpin" else "Pin"
                )
                CompactButton(
                    onClick = { close(); onRequestExport() },
                    color = actionColor, text = "Export",
                    enabled = currentReportId != null && isComplete
                )
            }
        }
        else -> { /* no Row 2 */ }
    }
    Spacer(modifier = Modifier.height(8.dp))

    // Pending-changes banner: surfaces edits the user made (prompt / models / parameters)
    // since the report ran, so they know a Regenerate is needed to see the new outputs.
    val pendingPrompt = uiState.hasPendingPromptChange
    val pendingModels = uiState.stagedReportModels.isNotEmpty()
    val pendingParams = uiState.hasPendingParametersChange
    if (isComplete && (pendingPrompt || pendingModels || pendingParams)) {
        val parts = listOfNotNull(
            "prompt".takeIf { pendingPrompt },
            "models".takeIf { pendingModels },
            "parameters".takeIf { pendingParams }
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.Orange.copy(alpha = 0.18f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", fontSize = 16.sp, color = AppColors.Orange, modifier = Modifier.padding(end = 8.dp))
                Text(
                    "Changes pending: ${parts.joinToString(", ")}. Tap Regenerate to apply.",
                    fontSize = 12.sp, color = AppColors.TextSecondary
                )
            }
        }
    }

    // When the user staged a new model list via Edit / Models, the result rows below
    // are derived from the staged list (not the on-disk agent set) so added rows appear
    // and removed rows disappear immediately. The progress bar is hidden in that mode
    // because the X/Y count is meaningless until they re-run.
    val staged = uiState.stagedReportModels
    val isStagedMode = isComplete && staged.isNotEmpty()

    // Per-agent token + cost rollup. Cost is recomputed every
    // recomposition (no remember) so a cold PricingCache that loads
    // *after* the first composition picks up real values once any
    // recomposition fires — e.g. after the user touches the screen
    // or the next batching tick lands. Memoising on
    // reportsAgentResults alone would fossilise DEFAULT_PRICING for
    // a finished report whose pricing tier wasn't preloaded yet.
    // Token sums share a memo since they only depend on tokenUsage.
    val selectedAgents = uiState.genericReportsSelectedAgents
    // Filter against selectedAgents — _agentResults can briefly hold
    // entries for agents the user just removed via "Remove from
    // report" until the next refresh tick. Counting them double-bills
    // the cost banner during the eviction window.
    val activeAgentIds: Set<String> = selectedAgents
    val agentCost = reportsAgentResults.entries
        .filter { (agentId, _) -> activeAgentIds.isEmpty() || agentId in activeAgentIds }
        .sumOf { (agentId, resp) ->
            resp.tokenUsage?.let {
                PricingCache.computeCost(it, PricingCache.getPricing(context, resp.service, resolveModelForResult(agentId, resp)))
            } ?: 0.0
        }
    val (agentInputTokens, agentOutputTokens) = remember(reportsAgentResults, activeAgentIds) {
        var input = 0; var output = 0
        reportsAgentResults.forEach { (agentId, r) ->
            if (activeAgentIds.isNotEmpty() && agentId !in activeAgentIds) return@forEach
            r.tokenUsage?.let { input += it.inputTokens; output += it.outputTokens }
        }
        input to output
    }
    // Live in-flight translation runs aren't persisted as TRANSLATE
    // SecondaryResults until the whole batch finishes, so secondaryTotals
    // (computed from disk) misses every per-call cost during a running
    // translation — the bottom-of-screen run row was the only place that
    // showed the live tally. Fold the in-memory state in here so the
    // top banner ticks up with each call. When the run finishes its
    // rows persist and the live row is consumed within ~200ms (no
    // double-count window worth worrying about). Single pass — was
    // three separate filter+sum walks before.
    val liveTranslation = remember(translationRuns) {
        var input = 0; var output = 0; var cost = 0.0
        translationRuns.forEach { run ->
            if (run.isFinished) return@forEach
            cost += run.totalCostDollars
            run.items.forEach { item ->
                item.tokenUsage?.let {
                    input += it.inputTokens; output += it.outputTokens
                }
            }
        }
        Triple(input, output, cost)
    }
    val liveTranslationInputTokens = liveTranslation.first
    val liveTranslationOutputTokens = liveTranslation.second
    val liveTranslationCost = liveTranslation.third

    val totalInputTokens = agentInputTokens + secondaryTotals.inputTokens + liveTranslationInputTokens
    val totalOutputTokens = agentOutputTokens + secondaryTotals.outputTokens + liveTranslationOutputTokens
    val totalCost = agentCost + secondaryTotals.inputCost + secondaryTotals.outputCost +
        liveTranslationCost + costsFromDeletedItems + reportIconCost + languageIconCost

    // Totals — sums tokens and cents across the per-agent rows, every
    // persisted meta run (rerank / summarize / compare / moderation /
    // translate), and any in-flight translation batch's live state.
    // Rendered as the last item inside the LazyColumn below so the
    // layout matches the rest of the rows.
    val showTotals = totalInputTokens > 0 || totalOutputTokens > 0 || totalCost > 0.0

    // Progress is in-flight UI: shown only while at least one agent is
    // still pending. Drops out once every agent finishes (or in
    // staged-edit mode where the X/Y count is meaningless until the
    // user re-runs).
    if (!isStagedMode && !isComplete) {
        Text("$reportsProgress / $reportsTotal complete", color = AppColors.TextSecondary, fontSize = 14.sp)
        LinearProgressIndicator(
            progress = { if (reportsTotal > 0) reportsProgress.toFloat() / reportsTotal else 0f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = AppColors.Purple
        )
    }

    data class DisplayRow(val rowId: String, val displayName: String, val providerDisplay: String, val isNew: Boolean)
    val displayRows: List<DisplayRow> = remember(isStagedMode, staged, selectedAgents, reportsAgentResults, aiSettings) {
        val rows = if (isStagedMode) {
            staged.map { m ->
                val rowId = if (m.type == "agent" && !m.agentId.isNullOrBlank()) m.agentId!!
                            else "swarm:${m.provider.id}:${m.model}"
                // Carry both the model and the provider display name so
                // the row label can honour the user's "Model name layout"
                // setting (model-only vs provider+model).
                DisplayRow(rowId, m.model, m.provider.id, !reportsAgentResults.containsKey(rowId))
            }
        } else {
            selectedAgents.map { agentId ->
                val result = reportsAgentResults[agentId]
                val name = result?.let { resolveModelForResult(agentId, it) }
                    ?: aiSettings.getAgentById(agentId)?.let { aiSettings.getEffectiveModelForAgent(it) }
                    ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringAfter(':')
                    ?: agentId
                val providerDisplay = result?.service?.id
                    ?: aiSettings.getAgentById(agentId)?.provider?.id
                    ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringBefore(':')?.let {
                        AppService.findById(it)?.id ?: it
                    }
                    ?: ""
                DisplayRow(agentId, name, providerDisplay, false)
            }
        }
        rows.sortedWith(compareBy({ it.displayName.lowercase() }, { it.providerDisplay.lowercase() }))
    }

    val activeTranslationRuns = remember(translationRuns) {
        translationRuns.filter { !it.isFinished && !it.cancelled }
    }
    // Suppress the persisted summary row for any runId that has a
    // live run currently in flight — restartFailedTranslations
    // re-fires errored rows under the same runId, so the persisted
    // summary (covering the kept OKs) and the live row (covering
    // the in-flight retries) would otherwise double up on the main
    // screen. Once the rerun finishes the runId is consumed and
    // the persisted summary takes over with the full call count.
    val activeTranslationRunIds = remember(activeTranslationRuns) {
        activeTranslationRuns.map { it.runId }.toSet()
    }
    val visibleTranslationSummaries = remember(translationRunSummaries, activeTranslationRunIds) {
        translationRunSummaries.filter { it.runId !in activeTranslationRunIds }
    }
    val showSecondaryRuns = isComplete && secondaryRuns.isNotEmpty()
    val showFanOutSummaries = fanOutSummaries.isNotEmpty()
    val showLiveTranslations = isComplete && activeTranslationRuns.isNotEmpty()
    val showTranslationSummaries = isComplete && visibleTranslationSummaries.isNotEmpty()
    // Anchor the list to the top while it's first loading. The
    // displayRows section composes immediately (it's derived from
    // uiState's selected agents) but the secondary / fan out /
    // translation sections come back a tick later from disk via
    // the polling LaunchedEffect above. Without this, those
    // sections prepend above the agent rows that the user is
    // already looking at, and LazyColumn's default anchoring
    // strands them above the fold. Re-keyed on currentReportId so
    // the scroll-to-top fires fresh per report open and doesn't
    // disturb later interaction.
    val resultListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Scroll to the top on report open AND every time a new
    // secondary / fan-out / translation row is appended. New entries
    // prepend at the top of the list; without the auto-scroll the
    // user would have to scroll up manually to find them on a long
    // report. The trigger is the joined per-section size — when a
    // translation moves from active → summary the trigger still
    // changes and we re-anchor to the top, which is what the user
    // wants to see.
    val newRowTrigger = "${secondaryRuns.size}|${fanOutSummaries.size}|${activeTranslationRuns.size}|${visibleTranslationSummaries.size}"
    LaunchedEffect(currentReportId, newRowTrigger) {
        if (currentReportId == null) return@LaunchedEffect
        resultListState.scrollToItem(0)
    }
    // Capture the icon-gen-enabled flag here (Composable scope) so the
    // LazyListScope DSL inside the LazyColumn below can gate the icon
    // row without invoking @Composable functions.
    val iconGenEnabledForRow = com.ai.ui.shared.LocalIconGenEnabled.current
    LazyColumn(state = resultListState, modifier = Modifier.weight(1f)) {
        // Meta runs — one row per individual rerank / summarize /
        // compare / moderation result on this report, sharing the
        // agent rows' layout (status icon + label + cost). Status
        // mirrors the agent rows: ⏳ while the placeholder is still
        // empty, ✅ on success, ❌ on error. Tapping opens that run's
        // detail screen. TRANSLATE rows are excluded — they're cost
        // records rather than user-actionable runs.
        if (showSecondaryRuns) {
            items(secondaryRuns, key = { "sr-${it.id}" }) { run ->
                // durationMs is stamped on every successful and errored
                // save and cleared by resetAndRelaunch. A row with
                // durationMs set and blank content is a successful
                // empty-body completion — without the durationMs check
                // the row read as Running… forever instead of ✅.
                val running = run.errorMessage == null
                    && run.content.isNullOrBlank()
                    && run.durationMs == null
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenSecondaryRun(run.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Resolve the InternalPrompt that produced this
                    // row (by id — stable across renames). Falls
                    // back to null when the prompt was deleted.
                    // Cached emoji + miss-side kick-off live inside
                    // the `else` (success) branch below so failed
                    // and running rows keep ❌ / ⏳ unchanged.
                    val resolvedPrompt = androidx.compose.runtime.remember(
                        run.fanInOf, run.metaPromptId,
                        aiSettings.internalPrompts.size
                    ) {
                        aiSettings.internalPrompts.firstOrNull {
                            it.id == run.fanInOf || it.id == run.metaPromptId
                        }
                    }
                    when {
                        run.errorMessage != null -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        running -> {
                            val transition = rememberInfiniteTransition(label = "meta-run-${run.id}")
                            val angle by transition.animateFloat(
                                initialValue = 0f, targetValue = 360f,
                                animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                                label = "meta-run-rot-${run.id}"
                            )
                            Text("⏳", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
                        }
                        else -> {
                            // SUCCESS branch: when the toggle is on
                            // AND we resolve to a known InternalPrompt
                            // AND its (name, title) emoji is cached,
                            // replace ✅ with the emoji (clickable to
                            // open the Meta-icon detail screen).
                            // Otherwise fall back to plain ✅.
                            val emoji = if (
                                uiState.generalSettings.useInternalPromptsIcons &&
                                resolvedPrompt != null &&
                                resolvedPrompt.name.isNotBlank()
                            ) {
                                val tick = uiState.iconRefreshTick
                                androidx.compose.runtime.remember(
                                    resolvedPrompt.id, resolvedPrompt.name,
                                    resolvedPrompt.title, tick
                                ) {
                                    val cached = com.ai.data.InternalPromptIconCache
                                        .get(resolvedPrompt.name, resolvedPrompt.title)
                                    if (cached == null) onMissingPromptIcon(resolvedPrompt)
                                    cached
                                }
                            } else null
                            if (emoji != null) {
                                Text(
                                    emoji, fontSize = 16.sp,
                                    modifier = Modifier
                                        .width(24.dp)
                                        .clickable { onOpenInternalPromptIconDetail(resolvedPrompt!!) }
                                )
                            } else {
                                Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                            }
                        }
                    }
                    // Live row "type" cell: fan_in rows always
                    // surface as "fan-in" (matching the prompt
                    // category — these are combine-reports follow-ups
                    // to a fan out run). The remaining rows use the
                    // Meta prompt name ("Compare", "Critique", …)
                    // verbatim, falling back to the routing label
                    // for legacy rows that pre-date the Meta-prompt
                    // CRUD.
                    val typeLabel = when {
                        run.fanInOf != null -> "fan-in"
                        run.metaPromptName?.isNotBlank() == true -> run.metaPromptName.lowercase()
                        else -> when (run.kind) {
                            SecondaryKind.RERANK -> "rerank"
                            SecondaryKind.META -> "meta"
                            SecondaryKind.MODERATION -> "moderate"
                            SecondaryKind.TRANSLATE -> "translate"
                        }
                    }
                    RowTypeCell(typeLabel)
                    val langSuffix = run.targetLanguage?.let { " · $it" } ?: ""
                    Column(modifier = Modifier.weight(1f)) {
                        // Fan-in rows label by the PROMPT used (its
                        // description / title) rather than the model
                        // — the user picks the prompt to characterise
                        // the run, the model is incidental. Falls
                        // back to the prompt name when no title is
                        // set, then the model label for legacy rows
                        // whose prompt id no longer resolves.
                        val text = if (run.fanInOf != null) {
                            val prompt = aiSettings.internalPrompts.firstOrNull {
                                it.id == run.fanInOf || it.id == run.metaPromptId
                            }
                            val label = prompt?.title?.takeIf { it.isNotBlank() }
                                ?: prompt?.name?.takeIf { it.isNotBlank() }
                                ?: run.metaPromptName?.takeIf { it.isNotBlank() }
                                ?: run.let {
                                    val runProv = AppService.findById(it.providerId)?.id ?: it.providerId
                                    com.ai.ui.shared.modelLabel(runProv, it.model)
                                }
                            "$label$langSuffix"
                        } else {
                            val runProv = AppService.findById(run.providerId)?.id ?: run.providerId
                            "${com.ai.ui.shared.modelLabel(runProv, run.model)}$langSuffix"
                        }
                        Text(
                            text,
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val totalCost = (run.inputCost ?: 0.0) + (run.outputCost ?: 0.0)
                    if (totalCost > 0.0) {
                        Text(formatCents(totalCost), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                    // Per-row 🐞 removed — SecondaryResultDetailScreen
                    // (the row's tap target) carries the same trace
                    // icon in its title bar, so the inline duplicate
                    // was redundant.
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

        // Fan-out summary rows — one per Meta-prompt name with at
        // least one fan-out pair (or fan_in combine-reports) row on
        // disk. A single Run Fan out click produces N×(M-1) per-pair
        // responses plus an optional combine-reports follow-up; we
        // collapse them into a single line here so the agent list
        // doesn't balloon. Tap → SecondaryResultsScreen, which already
        // detects fan out rows and renders them via FanOutDrillInView.
        if (showFanOutSummaries) {
            items(fanOutSummaries, key = { "cm-${it.metaPromptName}" }) { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        onViewSecondaryName(run.metaPromptName, run.kind)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Resolve the InternalPrompt that produced this
                    // fan-out run so its cached emoji can replace ✅
                    // (mirrors the meta-run row classifier above).
                    // Fan-out summaries identify by prompt NAME (no
                    // promptId on the summary itself), so we walk the
                    // user's fan_out prompts and match by name.
                    val fanOutPrompt = androidx.compose.runtime.remember(
                        run.metaPromptName, aiSettings.internalPrompts.size
                    ) {
                        aiSettings.internalPrompts.firstOrNull {
                            it.category == "fan_out" && it.name == run.metaPromptName
                        }
                    }
                    when {
                        run.pendingCount > 0 -> Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            AnimatedHourglass(fontSize = 16.sp)
                        }
                        run.errorCount > 0 -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        else -> {
                            // Success: try the per-prompt cached emoji
                            // before falling back to ✅. Cache miss fires
                            // a one-shot kick-off via onMissingPromptIcon;
                            // the row recomposes on iconRefreshTick once
                            // the call lands.
                            val emoji = if (
                                uiState.generalSettings.useInternalPromptsIcons &&
                                fanOutPrompt != null &&
                                fanOutPrompt.name.isNotBlank()
                            ) {
                                val tick = uiState.iconRefreshTick
                                androidx.compose.runtime.remember(
                                    fanOutPrompt.id, fanOutPrompt.name,
                                    fanOutPrompt.title, tick
                                ) {
                                    val cached = com.ai.data.InternalPromptIconCache
                                        .get(fanOutPrompt.name, fanOutPrompt.title)
                                    if (cached == null) onMissingPromptIcon(fanOutPrompt)
                                    cached
                                }
                            } else null
                            if (emoji != null) {
                                Text(
                                    emoji, fontSize = 16.sp,
                                    modifier = Modifier
                                        .width(24.dp)
                                        .clickable { onOpenInternalPromptIconDetail(fanOutPrompt!!) }
                                )
                            } else {
                                Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                            }
                        }
                    }
                    RowTypeCell("fan-out")
                    val pendingSuffix = if (run.pendingCount > 0) " · ${run.pendingCount} pending" else ""
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${run.metaPromptName} · ${run.pairCount} pairs$pendingSuffix",
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (run.totalCost > 0.0) {
                        Text(formatCents(run.totalCost), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                    // Per-row 🐞 removed — the fan out drill-in screen
                    // and its per-pair detail surfaces already expose
                    // the trace files through their own title bars.
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)

                // Sibling "fan-icons" row — shown once a Find Icons
                // batch has landed at least one emoji / icon error on
                // this run's pairs. Tap opens the same pair drill-in
                // in ICONS mode.
                if (run.iconCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            onViewFanIcons(run.metaPromptName)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎨", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        RowTypeCell("fan-icons")
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${run.metaPromptName} · ${run.iconCount} icon${if (run.iconCount == 1) "" else "s"}",
                                fontSize = 13.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (run.iconCost > 0.0) {
                            Text(formatCents(run.iconCost), fontSize = 10.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
            }
        }

        // Live translation rows — one per active run. Hourglass spins
        // while items are in flight; the text leads with "n / N" so
        // progress is visible at a glance, and the cost cell ticks up
        // as each call returns. Tap the row to drill into the per-run
        // detail screen; the Delete button there handles cancellation,
        // so the row itself doesn't carry an inline Cancel.
        if (showLiveTranslations) {
            items(activeTranslationRuns, key = { "tr-live-${it.runId}" }) { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable { onOpenTranslationRun(run.runId) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        AnimatedHourglass(fontSize = 16.sp)
                    }
                    RowTypeCell("translate")
                    val progress = "${run.completed} / ${run.total}"
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "$progress · ${run.targetLanguageName.ifBlank { "Translate" }}",
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (run.totalCostDollars > 0.0) {
                        Text(formatCents(run.totalCostDollars), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

        // Translation runs — one row per Translate invocation. Each
        // run produces several TRANSLATE secondaries (prompt + each
        // agent + each summary + each compare); they're collapsed
        // here so the user sees a single line per click. Tapping
        // opens TranslationRunDetailScreen with the call list.
        if (showTranslationSummaries) {
            items(visibleTranslationSummaries, key = { "trs-${it.runId}" }) { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenTranslationRun(run.runId) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Per-language icon swap — on success (no errored
                    // calls) AND the master switch on AND a cached
                    // emoji exists for run.targetLanguage, render the
                    // emoji in place of ✅. Cache miss kicks off a
                    // single background generation; iconRefreshTick
                    // keys the remember so the row recomposes when
                    // the call lands. Failed runs (errorCount > 0)
                    // keep ❌ unchanged.
                    when {
                        run.errorCount > 0 -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        else -> {
                            val lang = run.targetLanguage
                            val emoji = if (
                                uiState.generalSettings.useInternalPromptsIcons &&
                                !lang.isNullOrBlank()
                            ) {
                                val tick = uiState.iconRefreshTick
                                androidx.compose.runtime.remember(lang, tick) {
                                    val cached = com.ai.data.InternalPromptIconCache
                                        .get("translation_icon", lang)
                                    if (cached == null) onMissingTranslationIcon(lang)
                                    cached
                                }
                            } else null
                            if (emoji != null && lang != null) {
                                Text(
                                    emoji, fontSize = 16.sp,
                                    modifier = Modifier
                                        .width(24.dp)
                                        .clickable { onOpenTranslationIconDetail(lang) }
                                )
                            } else {
                                Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                            }
                        }
                    }
                    RowTypeCell("translate")
                    // Language name + item count — no model name (the
                    // run spreads items across many models now; the
                    // detail screen shows the per-model breakdown).
                    val lang = run.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translate"
                    val info = "$lang - ${run.callCount} item${if (run.callCount == 1) "" else "s"}"
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            info,
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (run.totalCost > 0.0) {
                        Text(formatCents(run.totalCost), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                    // Per-row 🐞 removed — TranslationRunDetailScreen
                    // (the row's tap target) carries the same trace
                    // icon in its title bar.
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

        // Icon row — surfaces the background `internal/icon` LLM call
        // kicked off at report start (kickOffIconGeneration). Hidden
        // when the prompt isn't configured or its pinned agent has
        // been deleted / renamed, so the row never shows up empty.
        // Spinner while the call is in flight, the resolved emoji on
        // success, ❌ on failure. Status mirrors the disk fields
        // Report.icon / Report.iconErrorMessage.
        run {
            val iconPrompt = aiSettings.internalPrompts.firstOrNull {
                it.category == "icons" && it.name == "icon"
            }
            val iconAgent = iconPrompt?.let { p ->
                aiSettings.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) }
            }
            if (iconGenEnabledForRow && iconPrompt != null && iconAgent != null) {
                item(key = "row-icon") {
                    val running = reportIcon == null && reportIconError == null
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable { onOpenIconDetail() },
                        verticalAlignment = Alignment.CenterVertically) {
                        when {
                            reportIconError != null -> Text("❌", fontSize = 16.sp,
                                modifier = Modifier.width(24.dp))
                            running -> {
                                val transition = rememberInfiniteTransition(label = "icon-hourglass")
                                val angle by transition.animateFloat(
                                    initialValue = 0f, targetValue = 360f,
                                    animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                                    label = "icon-hourglass-rot"
                                )
                                Text("⏳", fontSize = 16.sp,
                                    modifier = Modifier.width(24.dp).rotate(angle))
                            }
                            else -> Text(reportIcon!!, fontSize = 16.sp,
                                modifier = Modifier.width(24.dp))
                        }
                        RowTypeCell("icon")
                        Column(modifier = Modifier.weight(1f)) {
                            // On failure surface the recorded reason so
                            // the user sees *why* it errored (rate limit /
                            // 401 / etc.) instead of the harmless model
                            // label that would otherwise appear next to
                            // the ❌. Running and success show the
                            // resolved model label — falling back to
                            // getEffectiveModelForAgent when the agent's
                            // model field is blank (i.e., it's pinned to
                            // the provider's default).
                            val effectiveModel = aiSettings.getEffectiveModelForAgent(iconAgent)
                            val text = reportIconError
                                ?: reportIconModel
                                ?: com.ai.ui.shared.modelLabel(iconAgent.provider.id, effectiveModel)
                            val color = if (reportIconError != null) AppColors.Red else Color.White
                            Text(
                                text, fontSize = 13.sp, color = color,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (reportIconCost > 0.0) {
                            Text(formatCents(reportIconCost), fontSize = 10.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
                }
                // Language row — sits directly under the icon row.
                // Same loading/error/success states (⏳ / ❌ / emoji),
                // tap on the icon cell opens the language detail
                // screen. Hidden when icon-gen is off (the gate
                // above covers this since the same flag drives both).
                if (currentReportId != null) {
                    item(key = "row-language") {
                        LanguageRow(
                            reportId = currentReportId,
                            iconRefreshTick = uiState.iconRefreshTick,
                            onOpenDetail = onOpenLanguageDetail
                        )
                    }
                }
            }
        }

        items(displayRows, key = { "row-${it.rowId}" }) { row ->
            val agentId = row.rowId
            val result = reportsAgentResults[agentId]
            val displayName = row.displayName

            // Always clickable — pending / running / errored rows
            // open the same detail screen so the user can remove or
            // re-run the agent. Staged-only rows (no agent on disk
            // yet) land on the detail screen's "Result not found"
            // empty state, where the back gesture returns them here.
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clickable { onViewAgent(agentId) },
                verticalAlignment = Alignment.CenterVertically) {
                // Status icon — newly-staged rows get a NEW badge, pending
                // hourglass spins, success/failure static. When a Report
                // icons run has landed an emoji for this row, the emoji
                // replaces the ✅ AND becomes its own click target that
                // opens the per-agent icon detail. The outer row's
                // .clickable still fires for taps outside the 24 dp icon
                // cell — Compose hit-testing prefers the innermost
                // .clickable inside the bounds.
                val agentIconEmoji = agentIconRows[agentId]?.icon
                if (row.isNew) {
                    Text(text = "🆕", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                } else if (result == null) {
                    val transition = rememberInfiniteTransition(label = "hourglass")
                    val angle by transition.animateFloat(
                        initialValue = 0f, targetValue = 360f,
                        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                        label = "hourglass-rotation"
                    )
                    Text(text = "⏳", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
                } else if (result.isSuccess && !agentIconEmoji.isNullOrBlank()) {
                    Text(
                        text = agentIconEmoji,
                        fontSize = 16.sp,
                        modifier = Modifier.width(24.dp)
                            .clickable { onOpenAgentIconDetail(agentId) }
                    )
                } else {
                    Text(
                        text = if (result.isSuccess) "✅" else "❌",
                        fontSize = 16.sp, modifier = Modifier.width(24.dp)
                    )
                }
                RowTypeCell("report")
                Column(modifier = Modifier.weight(1f)) {
                    Text(com.ai.ui.shared.modelLabel(row.providerDisplay, displayName),
                        fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (result?.tokenUsage != null) {
                    val baseCost = PricingCache.computeCost(result.tokenUsage, PricingCache.getPricing(context, result.service, resolveModelForResult(agentId, result)))
                    // Fold per-agent icon cost into the row's right-side
                    // cost cell so the user sees a single total per row —
                    // icon spend doesn't get its own column.
                    val totalCost = baseCost + (agentIconRows[agentId]?.cost ?: 0.0)
                    Text(formatCents(totalCost), fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
                // Per-row 🐞 removed — ReportSingleResultScreen (the
                // row's tap target) carries the same trace icon in
                // its title bar.
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }

        // Above the total: surfaces cost the user dropped from the
        // report via Delete actions. Hidden when zero. Sits just
        // above the Total footer so the user's eye lands on
        // (deleted) → (total) in reading order.
        if (costsFromDeletedItems > 0.0) {
            item(key = "footer-deleted-costs") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🗑", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    RowTypeCell("deleted")
                    Text(
                        "Costs from deleted items",
                        fontSize = 13.sp, color = AppColors.TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatCents(costsFromDeletedItems),
                        fontSize = 10.sp, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Footer total row — visually matches the agent rows above:
        // [icon 24dp][type cell][label weight 1f][cost on right].
        // Σ stands in for the per-row status icon; the type cell reads
        // "total" to keep the column alignment.
        if (showTotals) {
            item(key = "footer-total") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💰", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    RowTypeCell("total")
                    Text(
                        "$totalInputTokens / $totalOutputTokens tok",
                        fontSize = 13.sp, color = AppColors.Blue,
                        modifier = Modifier.weight(1f)
                    )
                    // No "¢" suffix here so the right-hand column
                    // aligns with the per-row cost cells, which print
                    // formatCents(cost) raw.
                    Text(
                        formatCents(totalCost),
                        fontSize = 10.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** Fixed-width "type" cell used by every row in the result list:
 *  agent rows show "report", secondary rows show their kind, the
 *  translation-run summary shows "translate". Lowercase to match the
 *  user-facing convention. Constant width so the model column to its
 *  right lines up across rows. */
@Composable
internal fun RowTypeCell(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = AppColors.TextTertiary,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        // Start padding adds a visible gap between every row's
        // leftmost 24 dp status cell (✅ / emoji / ⏳ / ❌ / 🆕)
        // and the type label here — the two were touching before.
        modifier = Modifier.width(80.dp).padding(start = 8.dp, end = 6.dp)
    )
}

/** Inline 'language' row — the second of the two AI-derived report
 *  attributes (after the icon row above). Loads [Report.languageName]
 *  / [Report.languageIcon] / [Report.languageIconErrorMessage]
 *  directly via produceState (keeps the parent ReportsScreen body
 *  off the JVM 64 KB per-method bytecode ceiling). Re-reads on every
 *  [iconRefreshTick] bump so a freshly-detected language flips the
 *  row in real time without the parent having to thread state. */
@Composable
internal fun LanguageRow(
    reportId: String,
    iconRefreshTick: Int,
    onOpenDetail: () -> Unit,
) {
    val context = LocalContext.current
    data class LangSnapshot(val name: String?, val icon: String?, val error: String?, val cost: Double)
    val snapshot = produceState(initialValue = LangSnapshot(null, null, null, 0.0), reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val r = com.ai.data.ReportStorage.getReport(context, reportId)
            LangSnapshot(
                r?.languageName, r?.languageIcon, r?.languageIconErrorMessage,
                (r?.languageIconInputCost ?: 0.0) + (r?.languageIconOutputCost ?: 0.0)
            )
        }
    }.value
    val running = snapshot.icon == null && snapshot.error == null
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        .clickable { onOpenDetail() },
        verticalAlignment = Alignment.CenterVertically) {
        when {
            snapshot.error != null -> Text("❌", fontSize = 16.sp,
                modifier = Modifier.width(24.dp))
            running -> {
                val transition = rememberInfiniteTransition(label = "lang-hourglass")
                val angle by transition.animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                    label = "lang-hourglass-rot"
                )
                Text("⏳", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
            }
            else -> Text(snapshot.icon!!, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        }
        RowTypeCell("language")
        Column(modifier = Modifier.weight(1f)) {
            val text = when {
                snapshot.error != null -> snapshot.error
                running -> "Detecting…"
                else -> snapshot.name ?: "(unknown)"
            }
            val color = if (snapshot.error != null) AppColors.Red else Color.White
            Text(
                text, fontSize = 13.sp, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (snapshot.cost > 0.0) {
            Text(formatCents(snapshot.cost), fontSize = 10.sp,
                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}

/** Compact action button shared across the Reports result page's
 *  View / Edit / Actions rows. Sized to its label (no width filling),
 *  with thin vertical padding so a row of these takes a fraction of
 *  the height a default Material Button would. [leading] runs before
 *  the label and is used by the Meta button to host its spinning ⏳
 *  while a batch is in flight. */
@Composable
internal fun CompactButton(
    onClick: () -> Unit,
    color: Color,
    text: String,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier
            .heightIn(min = 28.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
    ) {
        if (leading != null) leading()
        Text(text, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

/** Aggregated tokens + cost across every persisted secondary result on
 *  a report (rerank / summarize / compare / moderation / translate),
 *  loaded alongside the per-row list and summed once so the totals
 *  banner doesn't have to scan the list on every recomposition. */
internal data class SecondaryTotals(
    val inputTokens: Int,
    val outputTokens: Int,
    val inputCost: Double,
    val outputCost: Double
) {
    companion object { val ZERO = SecondaryTotals(0, 0, 0.0, 0.0) }
}

/** One synthetic row for the agent list per Translate invocation. The
 *  translate flow writes N TRANSLATE secondaries (prompt + each agent
 *  + each summary + each compare); collapsing them here keeps the
 *  result list at one row per user-initiated run with a single status
 *  / cost. */
internal data class TranslationRunSummary(
    /** Either [SecondaryResult.translationRunId] when present, or a
     *  synthetic "lang:<targetLanguage>" key for legacy rows. The
     *  detail screen rebuilds the same key to find its rows. */
    val runId: String,
    val targetLanguage: String?,
    val targetLanguageNative: String?,
    /** Model used for every call in the run (a single Translate
     *  invocation always picks one model). Surfaced on the run row
     *  so the user can see which model produced the translation
     *  without drilling in. */
    val model: String?,
    val callCount: Int,
    val errorCount: Int,
    val totalCost: Double,
    /** Timestamp of the latest call in the run — used to sort
     *  translation rows newest-first alongside the other meta
     *  rows. */
    val timestamp: Long
)

/** Single synthetic row for the agent list per fan-out Meta run. A
 *  fan out click produces N×(M-1) per-pair responses (kind=META,
 *  fanOutSourceAgentId set); collapsing them here keeps the result list
 *  at one line per user-initiated fan out run, mirroring how
 *  [TranslationRunSummary] collapses Translate's per-call rows. */
internal data class FanOutRunSummary(
    /** Meta-prompt display name — used both as the row label and as the
     *  routing key for [onViewSecondaryName] which opens
     *  [SecondaryResultsScreen]'s fan out drill-in. */
    val metaPromptName: String,
    val kind: SecondaryKind,
    val pairCount: Int,
    /** Rows still in flight (placeholder content + no error). > 0 keeps
     *  the spinner spinning on the summary row. */
    val pendingCount: Int,
    val errorCount: Int,
    /** Pairs that have a fan-out icon (emoji landed) or an icon-chain
     *  error. > 0 surfaces a sibling "fan-icons" row in the list. */
    val iconCount: Int,
    /** Summed icon-chain (tier 1/2/3) call cost across the run's
     *  pairs — rendered on the sibling "fan-icons" row. Separate from
     *  [totalCost], which covers only the fan-out pair calls. */
    val iconCost: Double,
    val totalCost: Double,
    /** Latest timestamp across the run; used to sort against the other
     *  meta rows. */
    val timestamp: Long
)

/** Group fan-out pair rows by Meta-prompt name. Fan_in rows are
 *  excluded by the caller — each is its own row in secondaryRuns since
 *  there's nothing to fold (one click → one row). Legacy rows missing
 *  `metaPromptName` fall back to `metaPromptId` to keep them grouped. */
internal fun buildFanOutSummaries(rows: List<com.ai.data.SecondaryResult>): List<FanOutRunSummary> {
    if (rows.isEmpty()) return emptyList()
    return rows
        .groupBy { it.metaPromptName?.takeIf { n -> n.isNotBlank() } ?: (it.metaPromptId ?: "") }
        .filterKeys { it.isNotBlank() }
        .map { (name, items) ->
            // durationMs is stamped on every successful + errored save;
            // a row with durationMs set but blank content is a successful
            // empty-body completion, not pending. Mirrors the L1 stats
            // classifier in SecondaryResultsScreen.
            val pending = items.count {
                it.content.isNullOrBlank() && it.errorMessage == null && it.durationMs == null
            }
            FanOutRunSummary(
                metaPromptName = name,
                kind = SecondaryKind.META,
                pairCount = items.size,
                pendingCount = pending,
                errorCount = items.count { it.errorMessage != null },
                iconCount = items.count {
                    !it.icon.isNullOrBlank() || !it.iconErrorMessage.isNullOrBlank()
                },
                iconCost = items.sumOf { it.iconInputCost + it.iconOutputCost },
                totalCost = items.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) },
                timestamp = items.maxOf { it.timestamp }
            )
        }
        .sortedByDescending { it.timestamp }
}

internal fun buildTranslationRunSummaries(rows: List<com.ai.data.SecondaryResult>): List<TranslationRunSummary> {
    if (rows.isEmpty()) return emptyList()
    return rows.groupBy { translationRunGroupingId(it) }
        .map { (runId, items) ->
            val first = items.first()
            TranslationRunSummary(
                runId = runId,
                targetLanguage = first.targetLanguage,
                targetLanguageNative = first.targetLanguageNative,
                model = first.model.takeIf { it.isNotBlank() },
                callCount = items.size,
                errorCount = items.count { it.errorMessage != null },
                totalCost = items.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) },
                timestamp = items.maxOf { it.timestamp }
            )
        }
        .sortedByDescending { it.timestamp }
}
