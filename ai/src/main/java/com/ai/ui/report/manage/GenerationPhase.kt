package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.ai.viewmodel.TranslationRunState

/** Per-agent icon UI snapshot mirrored from disk for the result
 *  list. Populated by [com.ai.viewmodel.ReportViewModel.runReportIcons]
 *  writing through [ReportStorage.updateReportAgentIcon]; the parent
 *  screen rebuilds this map on every iconRefreshTick bump. */
data class AgentIconRow(val icon: String?, val cost: Double)

/** Per-agent model-title mirror — the generated ≤4-word title and its
 *  folded cost. Parallel to [AgentIconRow]; rebuilt on every
 *  iconRefreshTick bump. When [title] is non-blank it replaces the model
 *  name on the 'report' row and its [cost] folds into the row total. */
data class AgentModelTitle(val title: String?, val cost: Double)

/** One item in a conditional "View" group (Meta / Rerank / Fan-out /
 *  Fan-in / Translate). Carries its on-screen label,
 *  the lambda that opens that item's detail, and the source
 *  [com.ai.model.InternalPrompt] when one is available (meta-style
 *  rows). The InternalPrompt drives the [ViewAiReportScreen] meta
 *  tile's dynamic icon — its cached per-prompt emoji replaces the
 *  static 🧠 fallback. */
internal data class EveryItem(
    val label: String,
    val prompt: com.ai.model.InternalPrompt? = null,
    /** Languages this item has content in. Null = item is not
     *  language-aware (always enabled, e.g. rerank / moderation /
     *  fan_in / fan_out / translate). Non-null = the set of language
     *  identifiers where this item has content; "" represents
     *  Original, every other entry matches
     *  [SecondaryResult.targetLanguage] (English displayName). The
     *  View screen uses this to gray out a tile whose set doesn't
     *  contain the active language. */
    val availableLanguages: Set<String>? = null,
    /** Underlying META SecondaryResult rows backing this tile (the
     *  group of rows sharing one metaPromptName). Exposed so the
     *  View screen's "Language missing" popup can resolve per-meta
     *  source text in any picked source language. Null for non-meta
     *  items. */
    val sourceRows: List<com.ai.data.SecondaryResult>? = null,
    /** The String? carries the View screen's currently-selected
     *  language at click time so the opened sub-screen can lock
     *  itself to that language. null = no force (Report - Manage
     *  path); "" = force Original; non-empty displayName = force
     *  that language. See [SecondaryResultDetailScreen.forcedLanguage]. */
    val open: (String?) -> Unit
)

/** Group [secondaryRuns] into the conditional kinds the View
 *  surface offers. Returns a map keyed by `"meta"` / `"rerank"` /
 *  `"moderation"` / `"fan_out"` / `"fan_in"` / `"translate"`.
 *  Empty lists for kinds with no rows so callers can hide that
 *  tile / button entirely. */
internal fun buildEveryItems(
    secondaryRuns: List<com.ai.data.SecondaryResult>,
    aiSettings: com.ai.model.Settings,
    onOpenSecondaryRun: (String, String?) -> Unit,
    onViewSecondaryName: (String, SecondaryKind, String?) -> Unit,
    onOpenTranslationRun: (String) -> Unit,
    /** Display name of the report's detected source language (e.g.
     *  "English"). When non-null, any META row whose
     *  `targetLanguage` matches this — or a META TRANSLATE row
     *  whose `targetLanguage` matches — counts as Original ("")
     *  for the per-tile availability check. Lets a back-translation
     *  populate the Original tab so the meta tile un-grays. */
    reportLanguageName: String? = null,
    /** TRANSLATE rows on this report. Callers must pass them in
     *  separately because [secondaryRuns] excludes TRANSLATE at the
     *  runtime layer. Used to compute cross-translate / back-
     *  translate availability for each meta tile so the tile
     *  un-grays once translations exist. */
    translates: List<com.ai.data.SecondaryResult> = emptyList()
): Map<String, List<EveryItem>> {
    val promptByName = aiSettings.internalPrompts.associateBy { it.name }

    // Meta rows: collapse multi-language groups into ONE EveryItem
    // per metaPromptName. Single-language groups keep their direct
    // [onOpenSecondaryRun] navigation; multi-language groups fall
    // through to [onViewSecondaryName] so SecondaryResultsScreen
    // shows its language-picker strip and the user picks which
    // language to open. Trailing String? = the View screen's
    // currently-selected language at click time, threaded into the
    // opened sub-screen as a lock; null on the Report - Manage path.
    // Per-meta-id cross-translate set, used to compute each meta
    // EveryItem's availableLanguages. Key = META id, value = set of
    // languages with a non-blank META TRANSLATE row pointing at it.
    val translateByMetaId: Map<String, Set<String>> = translates
        .asSequence()
        .filter {
            it.translateSourceKind == "META" &&
                !it.translateSourceTargetId.isNullOrBlank() &&
                !it.content.isNullOrBlank() &&
                !it.targetLanguage.isNullOrBlank()
        }
        .groupBy { it.translateSourceTargetId!! }
        .mapValues { (_, rows) -> rows.mapNotNullTo(mutableSetOf()) { it.targetLanguage } }
    // Each META row gets its own EveryItem (and therefore its own
    // tile on Report - view), even when several share the same
    // metaPromptName. Previously we collapsed same-name rows into
    // one tile that opened a list picker; now the user sees every
    // run laid out alongside the rest of the grid.
    fun fold(lang: String): String =
        if (reportLanguageName != null && lang == reportLanguageName) "" else lang
    // Bucket by the row's OWN fan-out/fan-in markers, not by looking up its
    // prompt name in a global name→category map — Internal Prompt names are
    // only unique within (category, name), so e.g. "Compare" can legally
    // exist as both a plain Meta prompt and a Fan-in prompt, and a
    // name-keyed map would collapse to whichever one happens to be later in
    // Settings, misrouting the other's rows into the wrong tile.
    val meta = secondaryRuns
        .filter { it.kind == SecondaryKind.META && it.fanOutSourceAgentId == null && it.fanInOf == null }
        .map { row ->
            val name = row.metaPromptName ?: "Meta"
            val prompt = promptByName[name]
            val displayName = secondaryPromptDisplayName(name)
            val own = row.targetLanguage?.takeIf { it.isNotBlank() } ?: ""
            val langs = mutableSetOf<String>()
            langs.add(if (own.isEmpty()) "" else fold(own))
            translateByMetaId[row.id]?.forEach { trLang -> langs.add(fold(trLang)) }
            EveryItem(
                label = displayName,
                prompt = prompt,
                availableLanguages = langs,
                sourceRows = listOf(row),
                open = { lang -> onOpenSecondaryRun(row.id, lang) }
            )
        }
    val rerank = secondaryRuns
        .filter { it.kind == SecondaryKind.RERANK }
        .map { row -> EveryItem(
            label = row.metaPromptName?.let(::secondaryPromptDisplayName) ?: "Rerank",
            sourceRows = listOf(row),
            open = { lang -> onOpenSecondaryRun(row.id, lang) }
        ) }
    val moderation = secondaryRuns
        .filter { it.kind == SecondaryKind.MODERATION }
        .map { row -> EveryItem(
            label = row.metaPromptName?.let(::secondaryPromptDisplayName) ?: "Moderation",
            sourceRows = listOf(row),
            open = { lang -> onOpenSecondaryRun(row.id, lang) }
        ) }
    val fanIn = secondaryRuns
        .filter { it.kind == SecondaryKind.META && it.fanInOf != null }
        .map { row -> EveryItem(
            label = row.metaPromptName ?: "Fan-in",
            prompt = row.metaPromptName?.let { promptByName[it] },
            sourceRows = listOf(row),
            open = { lang -> onOpenSecondaryRun(row.id, lang) }
        ) }
    // Fan-out: one item per distinct prompt name. Tap opens the
    // SecondaryResultsScreen with nameFilter set; the screen
    // auto-renders the L2 fan-out drill-in.
    val fanOut = secondaryRuns
        .filter { it.kind == SecondaryKind.META && it.fanOutSourceAgentId != null }
        .mapNotNull { it.metaPromptName }
        .distinct()
        .map { name ->
            EveryItem(
                label = name,
                prompt = promptByName[name],
                open = { lang -> onViewSecondaryName(name, SecondaryKind.META, lang) }
            )
        }
    // Translate: one item per translationRunId. The locked-language
    // parameter is ignored — a translation run is inherently
    // single-language; there's no picker to suppress downstream.
    // sourceRows surfaces the run's first row so the Report - view
    // tile click can route to a content-only TranslateViewScreen
    // (it reads translationRunId off the seed row to load the run).
    // TRANSLATE rows are stripped from [secondaryRuns] (RuntimeState removes
    // them) and arrive in the dedicated [translates] param — read them from
    // there, or this tile is always empty.
    val translate = translates
        .filter { it.kind == SecondaryKind.TRANSLATE }
        .groupBy { it.translationRunId ?: "lang:${it.targetLanguage.orEmpty()}" }
        .map { (runId, rows) ->
            val first = rows.first()
            val label = first.targetLanguageNative ?: first.targetLanguage ?: "(language)"
            EveryItem(
                label = label,
                sourceRows = listOf(first),
                open = { _ -> onOpenTranslationRun(runId) }
            )
        }
    return mapOf(
        "meta" to meta,
        "rerank" to rerank,
        "moderation" to moderation,
        "fan_out" to fanOut,
        "fan_in" to fanIn,
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
    val onViewPrompt: () -> Unit = {},
    val onViewCosts: () -> Unit = {},
    val onViewIcons: () -> Unit = {},
    /** Open the App Log Viewer filtered to this report's log-id. */
    val onViewLog: () -> Unit = {},
    val onEditTitle: () -> Unit = {},
    /** Open the "Report - Get info" metadata-jobs screen. */
    val onGetInfo: () -> Unit = {},
    val onEditPromptInline: () -> Unit = {},
    val onEditModelsInline: () -> Unit = {},
    val onEditParametersInline: () -> Unit = {},
    val onRequestRegenerate: () -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onRequestExport: () -> Unit = {},
    /** Stop the in-flight primary generation for this report, keeping
     *  every already-completed answer (remaining rows settle STOPPED). */
    val onStopGeneration: (String) -> Unit = { _ -> },
    /** True while this report is the live primary generation in this
     *  process — gates the Stop button next to the progress bar. */
    val isGenerationActive: (String) -> Boolean = { false },
    val onCancelTranslation: (String) -> Unit = { _ -> },
    val onViewSecondaryName: (String, SecondaryKind) -> Unit = { _, _ -> },
    /** Open the Fan Meta drill-in for a fan-out's metaPrompt name.
     *  Routes to SecondaryResultsScreen with the meta-mode flag,
     *  which mounts the separate FanMetaScreen. */
    val onViewFanMeta: (String) -> Unit = { _ -> },
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
    /** Per-row variant of [onOpenInternalPromptIconDetail] — stamps
     *  the source SecondaryResult id alongside the prompt so a later
     *  Find-alternative-icons pick lands on that specific row's
     *  `icon` field rather than the shared per-(name,title) cache
     *  entry. Wired at the inline meta-emoji + the View screen's
     *  meta tile click. */
    val onOpenInternalPromptIconDetailForRow: (com.ai.model.InternalPrompt, String) -> Unit = { _, _ -> },
    val onMissingTranslationIcon: (String) -> Unit = { _ -> },
    val onOpenTranslationIconDetail: (String) -> Unit = { _ -> },
    /** Rebuild a translation run's in-memory state from disk —
     *  surfaced from a 10-second poll on this screen whenever the
     *  hourglass row's completed-equals-total stall is detected.
     *  Two-arg shape so the wiring at ReportScreen can pass
     *  `translationLifecycle.onReconcileStalled` directly without
     *  building an extra lambda — that lambda allocation pushed
     *  ReportsScreen over the 64 KB method-bytecode ceiling. */
    val onReconcileStalledTranslation: (sourceReportId: String, runId: String) -> Unit = { _, _ -> }
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
    /** Tapping the green report-name line opens the main View hub
     *  ("View a report"). Plumbed from ReportRunScreen's
     *  onOpenViewReport (= Main's openViewReportFromManage). */
    onOpenViewReport: () -> Unit = {},
    /** Reports the running total cost up to the host so it can show it
     *  in the bottom icon bar. 0.0 when there's nothing billable yet. */
    onTotalCostChange: (Double) -> Unit = {},
    /** Opens the per-report system-prompt picker dialog from the
     *  Edit Row 2 "System prompt" button. Plumbed separately from
     *  [GenerationPhaseHandlers] so the dialog state + body stay
     *  inside [ReportRunScreen], keeping the bytecode out of
     *  [ReportsScreen] which sits at the JVM 64 KB ceiling. */
    editSystemPromptTrigger: () -> Unit = {},
    secondaryCounts: SecondaryResultStorage.Counts = SecondaryResultStorage.Counts(0, 0, 0, 0),
    /** Sum of costs the user dropped from this report via Delete actions
     *  on agents / secondaries / fan-out pairs / translations. Surfaces
     *  as a dedicated row above the Total footer when non-zero. */
    costsFromDeletedItems: Double = 0.0,
    secondaryRuns: List<com.ai.data.SecondaryResult> = emptyList(),
    /** Raw TRANSLATE rows on this report — passed separately
     *  because secondaryRuns excludes TRANSLATE at the runtime
     *  layer. Threaded into buildEveryItems so meta tiles fold
     *  cross-translate / back-translate availability correctly. */
    translateRows: List<com.ai.data.SecondaryResult> = emptyList(),
    secondaryTotals: SecondaryTotals = SecondaryTotals.ZERO,
    translationRuns: List<com.ai.viewmodel.TranslationRunState> = emptyList(),
    translationRunSummaries: List<TranslationRunSummary> = emptyList(),
    fanOutSummaries: List<FanOutRunSummary> = emptyList(),
    metaPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    fanOutPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    /** True once the report's disk read has landed for the current
     *  report id. The 'icon' row only spins its hourglass when loaded,
     *  so a finished row doesn't flash it during the initial read. */
    loaded: Boolean = false,
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
     *  the report total so the language-icon call is visible in the
     *  grand-total row. */
    languageIconCost: Double = 0.0,
    /** Report.languageInputCost + languageOutputCost in USD — the
     *  first call of the 2-step language flow (detection). Surfaces
     *  as the cost-table `language` row but also has to be folded
     *  into Report-Manage's total so the two screens agree. */
    languageDetectCost: Double = 0.0,
    /** Report.languageName mirrored from disk. When non-null and the
     *  report has any TRANSLATE secondaries, untranslated meta rows
     *  surface this as their "default language" tag so the user can
     *  tell originals from translations at a glance. */
    languageName: String? = null,
    /** Per-agent icon results mirrored from disk, keyed by agentId.
     *  The parent screen rebuilds this on every iconRefreshTick bump
     *  so the row picks up new emojis / cleared values without a
     *  manual subscribe. Rows without an entry (or with a null
     *  [AgentIconRow.icon]) render the default ✅/❌/⏳/🆕 cell. */
    agentIconRows: Map<String, AgentIconRow> = emptyMap(),
    agentModelTitles: Map<String, AgentModelTitle> = emptyMap(),
    showModelNamesInReportRows: Boolean = false,
    /** Persisted agent records (frozen cost split pinned at run
     *  completion). The bottom-bar total prefers these over a live
     *  recompute so it matches the Report-Costs screen, which reads the
     *  same frozen split. Empty while the report's disk read is pending. */
    agentRecordsByAgentId: Map<String, com.ai.data.ReportAgent> = emptyMap(),
    // Aggregate of the metadata jobs now shown on "Report - Get info":
    // whether any job is enabled (→ render the info row), the aggregate
    // status, and the summed cost (also folded into the grand total).
    infoEnabled: Boolean = false,
    infoState: InfoJobState = InfoJobState.DONE,
    infoMetaTotal: Double = 0.0,
    // Aggregate of every secondary result, now shown on "Report - second
    // results": whether any secondary exists (→ render the second row), the
    // aggregate status, and the summed secondary cost.
    secondEnabled: Boolean = false,
    secondState: InfoJobState = InfoJobState.DONE,
    secondTotal: Double = 0.0,
    hasPrevReport: Boolean = false,
    hasNextReport: Boolean = false,
    /** True while a full-screen overlay (e.g. Get-info) is layered on top
     *  of the still-composed hub. Pauses the hub's background effects (the
     *  10 s stalled-translation reconcile + the scroll-to-top anchor) so
     *  the hidden list doesn't keep doing disk reads / scroll itself. */
    paused: Boolean = false
) {
    // Local aliases so the existing body keeps reading short names
    // — avoids touching every call site inside this 1000-line phase.
    // The 'report' row tap opens the standalone Report-model route. Read
    // the navigator here (not in ReportsScreen, which is at the JVM 64 KB
    // method ceiling) and resolve at the click site.
    val navigateToReportModel = com.ai.ui.shared.LocalNavigateToReportModel.current
    val onViewAgent: (String) -> Unit = { agentId ->
        currentReportId?.let { rid -> navigateToReportModel(rid, agentId) }
    }
    val onShare = handlers.onShare
    val onTrace = handlers.onTrace
    val onDelete = handlers.onDelete
    val onTranslate = handlers.onTranslate
    val onOpenMetaPicker = handlers.onOpenMetaPicker
    val onOpenFanOutPicker = handlers.onOpenFanOutPicker
    val onOpenRerankPicker = handlers.onOpenRerankPicker
    val onOpenModerationPicker = handlers.onOpenModerationPicker
    val onOpenHtmlPreview = handlers.onOpenHtmlPreview
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
    val onViewFanMeta = handlers.onViewFanMeta
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
    val onOpenInternalPromptIconDetailForRow = handlers.onOpenInternalPromptIconDetailForRow
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
    // Two-tier toggle: Row 1 has Edit / Create; tapping a Row 1 button
    // opens Row 2 (its sub-actions) inline; tapping the same Row 1
    // button again closes Row 2. Sub-actions fire and then collapse
    // Row 2. The "Action" group is gone — Regenerate / Delete / Share /
    // Chat / View live on the title bar, Pin and Copy on the bottom
    // bar (📌 / 👯).
    @OptIn(ExperimentalLayoutApi::class)
    @Composable fun ActionRow(
        startPadding: Dp = 0.dp,
        content: @Composable FlowRowScope.() -> Unit
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = startPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
    // Per-kind / per-category item lists driving the View row's
    // "every:" buttons + Row 3 picker. Each item knows how to open
    // its detail directly. Builder hoisted to top-level so the new
    // [ViewAiReportScreen] shares the same grouping logic.
    // (Fan Meta are surfaced as a sibling list row off each
    //  fanOutSummary — see the items(fanOutSummaries) block —
    //  not as a View-row group, since the fan-out pair rows that
    //  carry the icons never enter `secondaryRuns`.)
    val everyItems = remember(secondaryRuns, translateRows, aiSettings.internalPrompts) {
        // Report - Manage path doesn't lock languages — discard the
        // trailing String? from buildEveryItems' new signature.
        buildEveryItems(secondaryRuns, aiSettings,
            onOpenSecondaryRun = { id, _ -> onOpenSecondaryRun(id) },
            onViewSecondaryName = { name, kind, _ -> onViewSecondaryName(name, kind) },
            onOpenTranslationRun = onOpenTranslationRun,
            translates = translateRows)
    }

    // Cost / token rollup lifted above Row 1 so the row's trailing
    // 💰 + cents slot has [totalCost] / [showTotals] in scope. (Was
    // computed further down, just above the old green subject row.)
    // When the user staged a new model list via Edit / Models, the result rows below
    // are derived from the staged list (not the on-disk agent set) so added rows appear
    // and removed rows disappear immediately. The progress bar is hidden in that mode
    // because the X/Y count is meaningless until they re-run.
    // Owner-gated: another report's staged list must not leak in here via
    // swipe navigation (it would render foreign rows and invite a
    // destructive Regenerate on the wrong report).
    val stagedOwnedHere = uiState.stagedChangesReportId != null &&
        uiState.stagedChangesReportId == currentReportId
    val staged = if (stagedOwnedHere) uiState.stagedReportModels else emptyList()
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
            // Prefer the cost split frozen at run completion (the prices in
            // effect when the report ran) so this total matches the
            // Report-Costs screen, which reads the same persisted split.
            // Fall back to a live recompute only while an agent is still
            // in-flight / not yet persisted (no frozen split yet) — that
            // path keeps ticking the banner up during generation and, being
            // un-memoised, still picks up real prices after a cold cache.
            val frozen = agentRecordsByAgentId[agentId]?.let { ra ->
                if (ra.inputCost != null || ra.outputCost != null)
                    (ra.inputCost ?: 0.0) + (ra.outputCost ?: 0.0) else null
            }
            frozen ?: resp.tokenUsage?.let {
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
    val liveTranslation = remember(translationRuns, translateRows) {
        // Exclude runs whose rows already persisted so the window between
        // rows-persisted and live-state-evicted doesn't count the run's cost
        // twice — once here and once via secondaryTotals.
        val persistedRunIds = translateRows.map { translationRunGroupingId(it) }.toSet()
        var input = 0; var output = 0; var cost = 0.0
        translationRuns.forEach { run ->
            if (run.isFinished || run.runId in persistedRunIds) return@forEach
            cost += run.totalCostDollars
            run.items.values.forEach { item ->
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
    // Structured fallback for reports without a current ledger. The host
    // prefers the lifetime ledger, which also includes earlier attempts:
    //   agentCost                  → the per-model report rows
    //   secondaryTotals in/out     → the meta / fan-out / translation rows
    //   secondaryTotals.fanOutMeta → the per-run "fan-meta" rows (title+icon)
    //   infoMetaTotal              → the single "info" row (UNCONDITIONAL
    //                                metadata spend, not toggle-gated)
    //   costsFromDeletedItems      → the "Costs from deleted items" row
    //   liveTranslationCost        → the in-flight translation rows
    val totalCost = agentCost + secondaryTotals.inputCost + secondaryTotals.outputCost +
        liveTranslationCost + costsFromDeletedItems + secondaryTotals.fanOutMetaCost + infoMetaTotal
    val showTotals = totalInputTokens > 0 || totalOutputTokens > 0 || totalCost > 0.0

    // Report the running total up to the host (ReportRunScreen) so it can
    // surface it in the statistics line under the title bar (all three
    // report screens).
    androidx.compose.runtime.LaunchedEffect(totalCost, showTotals) {
        onTotalCostChange(if (showTotals) totalCost else 0.0)
    }

    // (The report title now renders as the bar's orange second line —
    // see Run.kt's TitleBar subject = promptTitle.)

    // The running total cost is surfaced in the title-bar statistics line
    // (via onTotalCostChange → ReportRunScreen → ReportStatsLine), not in
    // the body list.

    // The Edit ✏️ and Create 🆕 icons now open full-screen overlays
    // (ReportEditOverviewScreen / ReportCreateOverviewScreen, mounted in
    // ReportRunScreen) instead of bottom-bar pop-ups.

    // (No Spacer here — the stats line above carries the 8 dp bottom gap,
    // identical on all three report screens, so the top divider sits at
    // the same height everywhere.)

    // Pending-changes banner: surfaces edits the user made (prompt / models / parameters)
    // since the report ran, so they know a Regenerate is needed to see the new outputs.
    val pendingPrompt = stagedOwnedHere && uiState.hasPendingPromptChange
    val pendingModels = staged.isNotEmpty()
    val pendingParams = stagedOwnedHere && uiState.hasPendingParametersChange
    if (isComplete && (pendingPrompt || pendingModels || pendingParams)) {
        val parts = listOfNotNull(
            "prompt".takeIf { pendingPrompt },
            "models".takeIf { pendingModels },
            "parameters".takeIf { pendingParams }
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.WarningAccent.copy(alpha = 0.18f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(com.ai.data.MetadataIconsHolder.current.statusWarning, fontSize = 16.sp, color = AppColors.WarningAccent, modifier = Modifier.padding(end = 8.dp))
                Text(
                    "Changes pending: ${parts.joinToString(", ")}. Tap Regenerate to apply.",
                    fontSize = 12.sp, color = AppColors.TextSecondary
                )
            }
        }
    }

    // Progress is in-flight UI: shown only while at least one agent is
    // still pending. Drops out once every agent finishes (or in
    // staged-edit mode where the X/Y count is meaningless until the
    // user re-runs).
    if (!isStagedMode && !isComplete) {
        var confirmStop by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$reportsProgress / $reportsTotal complete", color = AppColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            // Stop-and-keep: only offered while THIS report is the live
            // generation in this process (a stale screen can't kill a
            // different report's run — the view-model guards too).
            if (currentReportId != null && handlers.isGenerationActive(currentReportId)) {
                TextButton(onClick = { confirmStop = true }) {
                    Text("⏹ Stop", color = AppColors.DangerAccent, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
        if (confirmStop && currentReportId != null) {
            AlertDialog(
                onDismissRequest = { confirmStop = false },
                title = { Text("Stop generating?") },
                text = { Text("Remaining API calls are cancelled; every answer that already completed is kept. Stopped models settle as \"Stopped by user\" — the Regenerate dialog's Retry failed re-runs just those later.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStop = false
                        handlers.onStopGeneration(currentReportId)
                    }) { Text("Stop", color = AppColors.DangerAccent, maxLines = 1) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmStop = false }) { Text("Keep running", maxLines = 1) }
                }
            )
        }
        LinearProgressIndicator(
            progress = { if (reportsTotal > 0) reportsProgress.toFloat() / reportsTotal else 0f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = AppColors.PrimaryAccent
        )
    }

    data class DisplayRow(val rowId: String, val displayName: String, val providerDisplay: String, val isNew: Boolean)
    val displayRows: List<DisplayRow> = remember(isStagedMode, staged, selectedAgents, reportsAgentResults, aiSettings.agents, agentRecordsByAgentId) {
        val rows = if (isStagedMode) {
            staged.map { m ->
                val rowId = if (m.type == "agent" && !m.agentId.isNullOrBlank()) m.agentId
                            else "swarm:${m.provider.id}:${m.model}"
                // Carry both the model and the provider display name so
                // the row label can honour the user's "Model name layout"
                // setting (model-only vs provider+model).
                DisplayRow(rowId, m.model, m.provider.id, !reportsAgentResults.containsKey(rowId))
            }
        } else {
            selectedAgents.map { agentId ->
                val result = reportsAgentResults[agentId]
                val name = agentRecordsByAgentId[agentId]?.model?.takeIf { it.isNotBlank() }
                    ?: result?.let { resolveModelForResult(agentId, it) }?.takeIf { it.isNotBlank() }
                    ?: aiSettings.getAgentById(agentId)?.let { aiSettings.getEffectiveModelForAgent(it) }
                    ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringAfter(':')
                    ?: agentId
                val providerDisplay = agentRecordsByAgentId[agentId]?.provider?.takeIf { it.isNotBlank() }
                    ?: result?.service?.id
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
    // Auto-reconcile stalled translation rows. A run with
    // completed == total but still flagged !isFinished is the
    // diagnostic signature of a cancelled `addCrossTranslationItems`
    // / `startMissingTranslations` coroutine: disk has work the
    // in-memory items list never picked up, the worker that would
    // have flipped `finished = true` never ran, and the manage
    // screen's hourglass keeps spinning over an apparently-done
    // row. Re-firing every 10 s (and once on screen open) so a
    // user revisiting a stalled report self-heals without needing
    // to force-stop the app.
    //
    // `rememberUpdatedState` so the loop body reads the latest
    // activeTranslationRuns instead of capturing the snapshot at
    // launch.
    val latestActiveRuns = rememberUpdatedState(activeTranslationRuns)
    // `paused` is a plain Boolean param — without the same wrapper the
    // long-lived loop below reads the value captured at effect launch
    // forever (the overlay gate never engages once launched unpaused,
    // and never releases once launched paused).
    val latestPaused = rememberUpdatedState(paused)
    LaunchedEffect(currentReportId) {
        // Per-runId guard so a reconcile that cannot flip a genuinely
        // stuck run to finished is attempted once (per report open),
        // not re-fired forever every 10 s — repeated reconciles do disk
        // reads / state rebuilds with no progress.
        val reconciled = mutableSetOf<String>()
        while (true) {
            val rid = currentReportId
            // Skip the reconcile sweep while a Get-info overlay is layered
            // on top — the hub is hidden, so there's nothing to self-heal
            // for the user right now.
            if (rid != null && !latestPaused.value) {
                latestActiveRuns.value.forEach { run ->
                    if (run.total > 0 && run.completed == run.total && run.runId !in reconciled) {
                        reconciled.add(run.runId)
                        handlers.onReconcileStalledTranslation(rid, run.runId)
                    }
                }
            }
            kotlinx.coroutines.delay(10_000L)
        }
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
    // NOTE: `paused` only GATES the body — it is deliberately NOT a key. Keying
    // on it re-ran the effect when an overlay set paused=true then cleared it,
    // snapping a long list back to the top on overlay close (audit reports#4).
    LaunchedEffect(currentReportId, newRowTrigger) {
        if (currentReportId == null || paused) return@LaunchedEffect
        resultListState.scrollToItem(0)
    }
    LazyColumn(state = resultListState, modifier = Modifier.weight(1f)) {
        // Regenerate batch — top of body when a RegenerateJob is
        // active for this report. Hoisted into its own composable
        // (RegenerateBatchManageRow) to keep this LazyColumn body
        // under the JVM 64 KB per-method ceiling. The row composable
        // also paints its own trailing HorizontalDivider so it
        // visually separates from the next row.
        item(key = "regen-batch-row") {
            RegenerateBatchManageRow()
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
                // hourglass spins. Once green (success) the cell shows this
                // model's generated icon when one has landed, else ✅; ❌ on
                // failure. (Icon detail + model-title live on Get-info.)
                if (row.isNew) {
                    Text(text = com.ai.data.MetadataIconsHolder.current.add, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                } else if (result == null) {
                    val transition = rememberInfiniteTransition(label = "hourglass")
                    val angle by transition.animateFloat(
                        initialValue = 0f, targetValue = 360f,
                        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                        label = "hourglass-rotation"
                    )
                    Text(text = "⏳", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
                } else if (result.isSuccess) {
                    val emoji = agentIconRows[agentId]?.icon?.takeIf { it.isNotBlank() }
                    Text(text = emoji ?: com.ai.data.MetadataIconsHolder.current.statusDone, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                } else {
                    Text(text = com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                }
                RowTypeCell("report")
                Column(modifier = Modifier.weight(1f)) {
                    // Keep the recorded model identity visible beside any generated title.
                    val modelLabel = com.ai.ui.shared.modelLabel(row.providerDisplay, displayName)
                    val modelTitle = if (showModelNamesInReportRows) null
                        else agentModelTitles[agentId]?.title?.takeIf { it.isNotBlank() }
                    Text(if (modelTitle.isNullOrBlank()) modelLabel else "$modelLabel · $modelTitle",
                        fontSize = 13.sp, color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (result?.tokenUsage != null) {
                    // Just the model's own response cost — meta costs moved
                    // to the info row / Get-info screen.
                    val frozen = agentRecordsByAgentId[agentId]?.let { agent ->
                        if (agent.inputCost != null || agent.outputCost != null)
                            (agent.inputCost ?: 0.0) + (agent.outputCost ?: 0.0)
                        else null
                    }
                    val baseCost = frozen ?: PricingCache.computeCost(
                        result.tokenUsage,
                        PricingCache.getPricing(context, result.service, resolveModelForResult(agentId, result))
                    )
                    Text(formatCents(baseCost), fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
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
                    Text(com.ai.data.MetadataIconsHolder.current.delete, fontSize = 16.sp, modifier = Modifier.width(24.dp))
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

        // (Total cost now lives in the bottom icon bar, not the list.)
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
        modifier = Modifier.width(96.dp).padding(start = 8.dp, end = 6.dp)
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
    data class LangSnapshot(val name: String?, val icon: String?, val error: String?, val cost: Double, val loaded: Boolean)
    val snapshot = produceState(initialValue = LangSnapshot(null, null, null, 0.0, false), reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val r = com.ai.data.ReportStorage.getReport(context, reportId)
            LangSnapshot(
                r?.languageName, r?.languageIcon, r?.languageIconErrorMessage,
                (r?.languageIconInputCost ?: 0.0) + (r?.languageIconOutputCost ?: 0.0),
                loaded = true
            )
        }
    }.value
    // Detection is "running" only once the disk read has landed and the
    // language NAME still isn't known. A null icon with a known name
    // means the icon-gen step didn't fire (or pre-dates the feature) —
    // a steady state, not "still working". And before the read lands we
    // render a blank cell rather than flashing the hourglass.
    val running = snapshot.loaded && snapshot.name == null && snapshot.error == null
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        .clickable { onOpenDetail() },
        verticalAlignment = Alignment.CenterVertically) {
        when {
            snapshot.error != null -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 16.sp,
                modifier = Modifier.width(24.dp))
            !snapshot.loaded -> Spacer(modifier = Modifier.width(24.dp))
            running -> Box(modifier = Modifier.width(24.dp),
                contentAlignment = Alignment.Center) {
                AnimatedHourglass(fontSize = 16.sp)
            }
            // Name is set; show the language-specific icon if one was
            // generated, otherwise a neutral 🌐 placeholder so the row
            // doesn't shift width.
            else -> Text(snapshot.icon ?: com.ai.ui.shared.LocalMetadataIcons.current.languageIcon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        }
        RowTypeCell("language")
        Column(modifier = Modifier.weight(1f)) {
            val text = when {
                snapshot.error != null -> snapshot.error
                !snapshot.loaded -> ""
                running -> "Detecting…"
                else -> snapshot.name ?: "(unknown)"
            }
            val color = if (snapshot.error != null) AppColors.DangerAccent else AppColors.TextPrimary
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

/** Title row — sibling of [LanguageRow]. Mirrors its loading /
 *  error / success states for the AI-generated report title.
 *  Status: `⏳` while running (no titlePromptUsed yet + no error),
 *  `❌` on failure, `🏷️` on success. Tap opens the existing
 *  Edit-Title overlay. */
@Composable
internal fun TitleRow(
    reportId: String,
    iconRefreshTick: Int,
    onOpenDetail: () -> Unit,
) {
    val context = LocalContext.current
    data class TitleSnapshot(val title: String?, val promptUsed: String?, val error: String?, val cost: Double, val loaded: Boolean)
    val snapshot = produceState(initialValue = TitleSnapshot(null, null, null, 0.0, false), reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            val r = com.ai.data.ReportStorage.getReport(context, reportId)
            TitleSnapshot(
                r?.title, r?.titlePromptUsed, r?.titleErrorMessage,
                (r?.titleInputCost ?: 0.0) + (r?.titleOutputCost ?: 0.0) +
                    (r?.titleLongInputCost ?: 0.0) + (r?.titleLongOutputCost ?: 0.0),
                loaded = true
            )
        }
    }.value
    // Running = disk read landed AND the AI hasn't returned yet AND
    // hasn't errored. A [titlePromptUsed] of "report_title" means the
    // call landed. Before the read lands, render a blank cell rather
    // than flashing the hourglass.
    val running = snapshot.loaded && snapshot.promptUsed == null && snapshot.error == null
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        .clickable { onOpenDetail() },
        verticalAlignment = Alignment.CenterVertically) {
        when {
            snapshot.error != null -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 16.sp,
                modifier = Modifier.width(24.dp))
            !snapshot.loaded -> Spacer(modifier = Modifier.width(24.dp))
            running -> Box(modifier = Modifier.width(24.dp),
                contentAlignment = Alignment.Center) {
                AnimatedHourglass(fontSize = 16.sp)
            }
            else -> Text(com.ai.data.MetadataIconsHolder.current.label, fontSize = 16.sp, modifier = Modifier.width(24.dp))
        }
        RowTypeCell("title")
        Column(modifier = Modifier.weight(1f)) {
            val text = when {
                snapshot.error != null -> snapshot.error
                !snapshot.loaded -> ""
                running -> "Generating…"
                else -> snapshot.title ?: "(no title)"
            }
            val color = if (snapshot.error != null) AppColors.DangerAccent else AppColors.TextPrimary
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

/** Aggregated tokens + cost across every persisted secondary result on
 *  a report (rerank / summarize / compare / moderation / translate),
 *  loaded alongside the per-row list and summed once so the totals
 *  banner doesn't have to scan the list on every recomposition. */
internal data class SecondaryTotals(
    val inputTokens: Int,
    val outputTokens: Int,
    val inputCost: Double,
    val outputCost: Double,
    /** The per-fan-out-pair Fan Meta chain spend: `iconInputCost +
     *  iconOutputCost + titleInputCost + titleOutputCost` across every
     *  fan-out pair. The fan-meta worker call bills BOTH the title and the
     *  icon to the pair's SecondaryResult, so summing the icon half alone
     *  (the old behaviour) under-counted by the title cost — which the
     *  Report-costs screen counts as its `fan/meta` rows. Folded into the
     *  Report-Manage grand-total so the two figures agree. */
    val fanOutMetaCost: Double = 0.0
) {
    companion object { val ZERO = SecondaryTotals(0, 0, 0.0, 0.0, 0.0) }
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
     *  error. > 0 surfaces a sibling "Fan Meta" row in the list. */
    val iconCount: Int,
    /** Pairs the Fan Meta batch still has to process — content present
     *  but no icon and no icon error yet. > 0 keeps the spinner on the
     *  sibling "Fan Meta" row while a Find-Icons batch is in flight. */
    val iconPendingCount: Int,
    /** Pairs whose icon chain ended in an error (iconErrorMessage set,
     *  no icon). > 0 puts a ❌ on the sibling "Fan Meta" row once the
     *  batch is no longer pending, so a run that finished WITH errors
     *  reads as failed instead of silently showing the plain icon. */
    val iconErrorCount: Int,
    /** Summed icon-chain (tier 1/2/3) call cost across the run's
     *  pairs — rendered on the sibling "Fan Meta" row. Separate from
     *  [totalCost], which covers only the fan-out pair calls. */
    val iconCost: Double,
    /** Title counterparts of [iconCount] / [iconPendingCount] /
     *  [iconErrorCount] / [iconCost] — drive the sibling "fan-meta" row. */
    val titleCount: Int,
    val titlePendingCount: Int,
    val titleErrorCount: Int,
    val titleCost: Double,
    val totalCost: Double,
    /** Latest timestamp across the run; used to sort against the other
     *  meta rows. */
    val timestamp: Long
)

/** Group fan-out pair rows by Meta-prompt name. Fan_in rows are
 *  excluded by the caller — each is its own row in secondaryRuns since
 *  there's nothing to fold (one click → one row). Legacy rows missing
 *  `metaPromptName` fall back to `metaPromptId` to keep them grouped. */
internal fun buildFanOutSummaries(rows: List<com.ai.data.SecondaryResult>, unattributed: List<com.ai.data.FanMetaAttempt> = emptyList()): List<FanOutRunSummary> {
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
                iconPendingCount = items.count {
                    !it.content.isNullOrBlank() &&
                        it.icon.isNullOrBlank() && it.iconErrorMessage.isNullOrBlank()
                },
                iconErrorCount = items.count { !it.iconErrorMessage.isNullOrBlank() },
                iconCost = items.sumOf { it.iconInputCost + it.iconOutputCost },
                titleCount = items.count {
                    !it.title.isNullOrBlank() || !it.titleErrorMessage.isNullOrBlank()
                },
                titlePendingCount = items.count {
                    !it.content.isNullOrBlank() &&
                        it.title.isNullOrBlank() && it.titleErrorMessage.isNullOrBlank()
                },
                titleErrorCount = items.count { !it.titleErrorMessage.isNullOrBlank() },
                titleCost = items.sumOf { it.titleInputCost + it.titleOutputCost } +
                    unattributed.filter { a -> items.any { it.titleRunId == a.runId } }.sumOf { it.cost },
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
