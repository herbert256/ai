package com.ai.ui.report.manage
import com.ai.ui.report.manage.view.*
import com.ai.ui.report.other.TargetLanguage
import com.ai.ui.report.other.LanguageSelectionScreen
import com.ai.ui.report.start.ModelSelectionScreen
import com.ai.ui.report.start.SelectionOverlayDialogs

import com.ai.ui.other.*
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AltEditPayload
import com.ai.viewmodel.AltPromptFlow
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.ResolvedAltPrompt
import com.ai.viewmodel.ReasoningEffortSweepState
import com.ai.viewmodel.TemperatureSweepState
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReportsScreen(
    uiState: UiState,
    reportsAgentResults: Map<String, AnalysisResponse>,
    runningFanOutPairs: Set<String> = emptySet(),
    /** See [FanRuntimeBundle] — three pair sets + the Fan Meta
     *  batch launcher bundled to keep this composable under the
     *  JVM 64 KB per-method bytecode limit. */
    fanRuntime: FanRuntimeBundle = FanRuntimeBundle(),
    /** Authoritative Fan Out runtime owner. Passed through from
     *  [ReportsScreenNav] so the redesigned [FanOutScreen] under
     *  [SecondaryResultsScreen] subscribes directly to its
     *  StateFlow. */
    fanOutEngine: com.ai.viewmodel.FanOutEngine? = null,
    /** Live state of any "Find alternative icons" fan-out, keyed by
     *  reportId. Collected from [AppViewModel.iconFanOutByReport] in
     *  [ReportsScreenNav] and threaded through here. */
    iconFanOutByReport: Map<String, List<IconCandidate>> = emptyMap(),
    /** Kick off an icon fan-out for the given report. */
    onStartIconFanOut: (reportId: String, promptText: String, models: List<ReportModel>) -> Unit = { _, _, _ -> },
    /** Commit a picked icon from the Alternative icons screen. */
    onPickAlternativeIcon: (reportId: String, emoji: String, iconModel: String) -> Unit = { _, _, _ -> },
    /** Cancel + drop all candidates for a report's icon fan-out. Used
     *  by the Alternative icons screen's Restart button — the user
     *  wants to start over. Costs already bumped on the Report by
     *  completed calls stay; they accumulate by design. */
    onRestartIconFanOut: (reportId: String) -> Unit = { _ -> },
    /** Bundle of the four language-icon fan-out parameters — passed
     *  as one slot so ReportsScreen's parameter list stays narrow.
     *  The inline showIconDetail / showFindIconsPicker /
     *  showAlternativeIcons overlays multiplex onto the language
     *  flow when [targetLanguageIcon] is true; the routers know how
     *  to dispatch via this bundle. */
    languageIconCallbacks: LanguageIconCallbacks = LanguageIconCallbacks(),
    /** Per-agent counterparts of the three icon-fan-out callbacks.
     *  Same shape but routed to [ReportViewModel.startAgentIconFanOut]
     *  /pickAgentIcon/restartAgentIconFanOut, which target a single
     *  [ReportAgent.icon] instead of [Report.icon]. */
    onStartAgentIconFanOut: (reportId: String, agentId: String, models: List<ReportModel>) -> Unit = { _, _, _ -> },
    onPickAgentIcon: (reportId: String, agentId: String, emoji: String) -> Unit = { _, _, _ -> },
    onRestartAgentIconFanOut: (reportId: String, agentId: String) -> Unit = { _, _ -> },
    /** "Find alternative titles" — live candidates + start/restart for the
     *  report title (keyed by reportId) and per-model titles (keyed by
     *  agentId). Transient: a picked title only fills the editor field. */
    titleFanOutByReport: Map<String, List<com.ai.viewmodel.TitleCandidate>> = emptyMap(),
    titleFanOutByAgent: Map<String, List<com.ai.viewmodel.TitleCandidate>> = emptyMap(),
    onStartReportTitleFanOut: (reportId: String, promptText: String, models: List<ReportModel>, long: Boolean, paramsIds: List<String>, systemPromptId: String?) -> Unit = { _, _, _, _, _, _ -> },
    onStartModelTitleFanOut: (reportId: String, agentId: String, models: List<ReportModel>, paramsIds: List<String>, systemPromptId: String?) -> Unit = { _, _, _, _, _ -> },
    onRestartReportTitleFanOut: (reportId: String) -> Unit = { _ -> },
    onRestartModelTitleFanOut: (agentId: String) -> Unit = { _ -> },
    /** Live per-item "Find alternative translation" candidate state
     *  mirrored from [AppViewModel.altTranslationByItem], keyed by the
     *  translation item id. The AlternativeTranslationsScreen reads from
     *  here while [altTranslateTarget] is non-null. */
    altTranslationByItem: Map<String, List<com.ai.viewmodel.TranslationCandidate>> = emptyMap(),
    onStartAltTranslationFanOut: (reportId: String, itemId: String, targetLanguageName: String, isTitleKind: Boolean, sourceText: String, traceType: String, models: List<ReportModel>, paramsIds: List<String>, systemPromptId: String?) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onApplyAltTranslation: (reportId: String, runId: String, itemId: String, persistedRowId: String?, candidate: com.ai.viewmodel.TranslationCandidate.Done) -> Unit = { _, _, _, _, _ -> },
    onRestartAltTranslationFanOut: (itemId: String) -> Unit = { _ -> },
    /** Bundle of the four per-Internal-Prompt icon callbacks —
     *  bundled into one parameter so the `ReportsScreen` parameter
     *  list stays under the JVM 64 KB per-method bytecode limit. */
    promptIconCallbacks: InternalPromptIconCallbacks = InternalPromptIconCallbacks(),
    /** Live per-prompt alternative-icons candidate state mirrored
     *  from [AppViewModel.internalPromptIconFanOutByPrompt], keyed
     *  by `name + U+001F + title`. The shared
     *  AlternativeIconsScreen reads from here when
     *  [promptIconDetailForId] is non-null. */
    internalPromptIconFanOutByPrompt: Map<String, List<IconCandidate>> = emptyMap(),
    /** Bundle of the four translation-icon callbacks — bundled
     *  into one parameter so the `ReportsScreen` parameter list
     *  doesn't blow past the JVM 64 KB per-method bytecode limit.
     *  Routed by [ReportsScreenNav] to
     *  [ReportViewModel.kickOffTranslationIcon /
     *  startTranslationIconFanOut / pickTranslationIcon /
     *  restartTranslationIconFanOut]. */
    translationIconCallbacks: TranslationIconCallbacks = TranslationIconCallbacks(),
    /** Per-agent alternative-icons candidate state mirrored from
     *  [AppViewModel.agentIconFanOutByAgent], keyed by agentId. The
     *  Alternative icons screen reads from here when the active flow
     *  is per-agent (i.e. [fanOutTargetAgentId] is non-null). */
    agentIconFanOutByAgent: Map<String, List<IconCandidate>> = emptyMap(),
    /** Per-fan-out-pair counterparts of the three icon-fan-out
     *  callbacks. Same shape but routed to
     *  [ReportViewModel.startPairIconFanOut /
     *  pickPairIconAlternative / restartPairIconFanOut]. */
    onStartPairIconFanOut: (reportId: String, pairId: String, models: List<ReportModel>) -> Unit = { _, _, _ -> },
    onPickPairIcon: (reportId: String, pairId: String, emoji: String) -> Unit = { _, _, _ -> },
    onRestartPairIconFanOut: (reportId: String, pairId: String) -> Unit = { _, _ -> },
    /** Per-pair alternative-icons candidate state mirrored from
     *  [AppViewModel.pairIconFanOutByPair], keyed by the
     *  SecondaryResult id of the pair the alt run was launched on.
     *  Read by AlternativeIconsRouter when [pairIconDetailFor] is
     *  non-null. */
    pairIconFanOutByPair: Map<String, List<IconCandidate>> = emptyMap(),
    /** Per-fan-out-pair TITLE Find-alt counterparts — the L3 META
     *  "Find alternative title" button. Routed to
     *  [ReportViewModel.startPairTitleFanOut /
     *  pickPairTitleAlternative / restartPairTitleFanOut]. */
    onStartPairTitleFanOut: (reportId: String, pairId: String, models: List<ReportModel>) -> Unit = { _, _, _ -> },
    onPickPairTitle: (reportId: String, pairId: String, title: String) -> Unit = { _, _, _ -> },
    onRestartPairTitleFanOut: (reportId: String, pairId: String) -> Unit = { _, _ -> },
    pairTitleFanOutByPair: Map<String, List<com.ai.viewmodel.TitleCandidate>> = emptyMap(),
    /** Find-alternative pre-pick "Edit prompt" support: resolve a flow's
     *  alt prompt (markers replaced) for the editor, and stash the user's
     *  edited text (null = clear) for the next start*FanOut to consume. */
    onResolveAltPrompt: suspend (AltPromptFlow) -> ResolvedAltPrompt? = { null },
    onStashAltEdit: (AltEditPayload?) -> Unit = { },
    /** Navigate to the surrounding AI report on disk — sorted
     *  newest-first like the hub. hasPrevReport / hasNextReport
     *  gate the chevron icons' enabled state. */
    onPrevReport: () -> Unit = {},
    onNextReport: () -> Unit = {},
    hasPrevReport: Boolean = false,
    hasNextReport: Boolean = false,
    initialModels: List<ReportModel> = emptyList(),
    onGenerate: (List<ReportModel>, List<String>, ReportType) -> Unit,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss,
    advancedParameters: AgentParameters? = null,
    onAdvancedParametersChange: (AgentParameters?) -> Unit = {},
    onParametersIdsChange: (List<String>) -> Unit = {},
    /** Persist the per-report system prompt picked in SelectionPhase
     *  onto UiState so the dispatch sees it at generation time. */
    onSystemPromptChange: (String?) -> Unit = {},
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToAppLog: (filename: String, search: String) -> Unit = { _, _ -> },
    onNavigateToTraceListFiltered: (String, String) -> Unit = { _, _ -> },
    onNavigateToTraceRunList: (String) -> Unit = {},
    onClearExternalInstructions: () -> Unit = {},
    onEditModels: (String) -> Unit = {},
    onUpdateModelList: (String, List<ReportModel>) -> Unit = { _, _ -> },
    onMarkParametersChanged: () -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    /** Regenerate only the metadata jobs (icon / title / language /
     *  per-model) — wired to the 🔄 while the Get-info layer is open. */
    onRegenerateInfo: (String) -> Unit = {},
    /** "Restart errors" on Get-info — re-fire only the errored info jobs. */
    onRestartInfoErrors: (String) -> Unit = {},
    /** Report-level info jobs whose call is actively in flight (clock vs
     *  hourglass on the Get-info rows). */
    runningInfoJobs: Set<String> = emptySet(),
    onUpdatePrompt: (String, String) -> Unit = { _, _ -> },
    onUpdateTitle: (String, String, String) -> Unit = { _, _, _ -> },
    onUpdateModelTitle: (String, String, String) -> Unit = { _, _, _ -> },
    onUpdatePairTitle: (String, String, String) -> Unit = { _, _, _ -> },
    onAttachKnowledgeBases: (List<String>) -> Unit = {},
    onDeleteReport: (String) -> Unit = {},
    onCopyReport: (String) -> Unit = {},
    onTogglePinReport: (String) -> Unit = {},
    onConsumePendingModels: () -> Unit = {},
    onRunSecondary: (String, com.ai.model.InternalPrompt, List<Pair<AppService, String>>, com.ai.data.SecondaryScope, com.ai.data.SecondaryLanguageScope, List<String>, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    /** Fired by the View screen's "Language missing" popup. Routes
     *  to ReportViewModel.translateMissingItems. */
    onTranslateMissingItems: (String, List<com.ai.viewmodel.TranslateMissingItem>, String, String) -> Unit = { _, _, _, _ -> },
    onRunFanOut: (String, com.ai.model.InternalPrompt, com.ai.data.SecondaryScope, Set<String>?, String?, List<String>, String?, Boolean) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onRunFanIn: (String, com.ai.model.InternalPrompt, Pair<AppService, String>, String?, List<String>, String?) -> Unit = { _, _, _, _, _, _ -> },
    /** Promote the L2 active model's fan-out conversation into a
     *  fresh AI Report. Args: source reportId, active provider id,
     *  active model. The new report's id is built inside the
     *  ReportViewModel; this lambda navigates after the save. */
    onCreateReportFromFanOut: (String, String, String) -> Unit = { _, _, _ -> },
    onRunLocalRerank: (String, String) -> Unit = { _, _ -> },
    onRunRerank: (String, Pair<AppService, String>, com.ai.data.SecondaryLanguageScope, List<String>, String?) -> Unit = { _, _, _, _, _ -> },
    onRunModeration: (String, Pair<AppService, String>, com.ai.data.SecondaryLanguageScope) -> Unit = { _, _, _ -> },
    onRunTournament: (String, AppService, String) -> Unit = { _, _, _ -> },
    onDeleteSecondary: (String, String) -> Unit = { _, _ -> },
    /** Bulk delete on the report VM's viewModelScope so a Stop /
     *  navigate-away during a Fan-out delete doesn't abandon a half-
     *  finished sweep. The screen-scoped fallback is the same forEach
     *  but on rememberCoroutineScope's scope, which dies with the
     *  screen. */
    onBulkDeleteSecondaries: (String, List<String>, () -> Unit) -> Unit = { rid, ids, done ->
        ids.forEach { onDeleteSecondary(rid, it) }
        done()
    },
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onRemoveAgent: (String, String) -> Unit = { _, _ -> },
    onRegenerateAgent: (String, String) -> Unit = { _, _ -> },
    temperatureSweepStates: Map<String, TemperatureSweepState> = emptyMap(),
    onStartTemperatureSweep: (String, String, List<Float>) -> Unit = { _, _, _ -> },
    onApplyTemperatureCandidate: (String, String, Int) -> Unit = { _, _, _ -> },
    onClearTemperatureSweep: (String, String) -> Unit = { _, _ -> },
    reasoningEffortSweepStates: Map<String, ReasoningEffortSweepState> = emptyMap(),
    onStartReasoningEffortSweep: (String, String, List<String?>) -> Unit = { _, _, _ -> },
    onApplyReasoningEffortCandidate: (String, String, Int) -> Unit = { _, _, _ -> },
    onClearReasoningEffortSweep: (String, String) -> Unit = { _, _ -> },
    onExport: suspend (String, ReportExportFormat, ReportExportDetail, ReportExportAction, ExportLanguage, (Int, Int) -> Unit) -> Unit = { _, _, _, _, _, _ -> },
    onExportAll: suspend (String, ExportLanguage, (Int, Int) -> Unit) -> Unit = { _, _, _ -> },
    translationRuns: List<com.ai.viewmodel.TranslationRunState> = emptyList(),
    /** Item ids currently parked on a provider rate / concurrency gate;
     *  feeds the translation L1 "Throttled" stat. */
    throttledTranslationItems: Set<String> = emptySet(),
    onStartTranslation: (String, String, String, List<Pair<AppService, String>>, List<String>, String?) -> Unit = { _, _, _, _, _, _ -> },
    translationLifecycle: TranslationLifecycleCallbacks = TranslationLifecycleCallbacks(),
    onContinueWithCurrent: (String, String) -> Unit = { _, _ -> },
    onContinueWithAgentPicker: (String, String) -> Unit = { _, _ -> },
    onContinueWithOnTheFly: (String, String) -> Unit = { _, _ -> },
    onChatWithReportPrompt: (String) -> Unit = {},
    onNavigateToInternalPromptEdit: (String) -> Unit = {},
    onResumeStaleFanOut: (String, com.ai.model.InternalPrompt) -> Unit = { _, _ -> },
    onRestartFailedFanOut: (String, com.ai.model.InternalPrompt) -> Unit = { _, _ -> },
    onRemoveFailedFanOut: (String, com.ai.model.InternalPrompt) -> Unit = { _, _ -> },
    onRestartFailedFanOutForModel: (String, com.ai.model.InternalPrompt, String, String) -> Unit = { _, _, _, _ -> },
    onRemoveFailedFanOutForModel: (String, com.ai.model.InternalPrompt, String, String) -> Unit = { _, _, _, _ -> },
    onRerunCompleteFanOut: (String, com.ai.model.InternalPrompt) -> Unit = { _, _ -> },
    /** Re-run a single fan-out pair from the L3 "Fan out - pair"
     *  TitleBar's 🔄 reload icon. */
    onRerunFanOutPair: (String, com.ai.model.InternalPrompt, com.ai.data.SecondaryResult) -> Unit = { _, _, _ -> },
    onDeleteFanOutModel: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** Mark every blank-content / no-error / no-duration secondary
     *  on the report as errored. Fired once per report open from a
     *  LaunchedEffect — animated-hourglass icons should only spin
     *  while something's actually running, but on app restart no
     *  in-memory job survives, so any "still in progress" row is
     *  an orphan placeholder. */
    onResumeStaleRuns: (String) -> Unit = {},
    /** Re-run every errored row in the named translation run.
     *  Wired to ReportViewModel.restartFailedTranslations. */
    onRestartFailedTranslations: (String, String) -> Unit = { _, _ -> },
    /** Drop every errored row from the named translation run
     *  without re-firing. Wired to
     *  ReportViewModel.removeFailedTranslations. */
    onRemoveFailedTranslations: (String, String) -> Unit = { _, _ -> },
    /** Drop only the errored rows whose model is currently benched.
     *  Wired to ReportViewModel.removeBenchedTranslations. */
    onRemoveBenchedTranslations: (String, String) -> Unit = { _, _ -> },
    /** Delete every row of the named translation run and dispatch
     *  the full set fresh, throttled by the runner's Semaphore(3).
     *  Wired to ReportViewModel.restartAllTranslations. */
    onRestartAllTranslations: (String, String) -> Unit = { _, _ -> },
    /** Run every expected translation item not yet covered by the
     *  named run's persisted rows. Wired to
     *  ReportViewModel.startMissingTranslations. */
    onStartMissingTranslations: (String, String) -> Unit = { _, _ -> },
    /** Reconstruct a finished translation run from its persisted
     *  TRANSLATE rows so the run screen can render it after the live
     *  state is gone. Wired to
     *  ReportViewModel.buildPersistedTranslationRunState. */
    onBuildPersistedTranslationRun: suspend (String, String) -> com.ai.viewmodel.TranslationRunState? = { _, _ -> null },
    /** Deep-link callbacks fired by the full-screen +Agent / +Flock /
     *  +Swarm / Meta / Fan out pickers' "Edit X" buttons. AppNavHost
     *  wires each to the matching Settings sub-screen route. */
    onNavigateToAgentsEdit: () -> Unit = {},
    onNavigateToFlocksEdit: () -> Unit = {},
    onNavigateToSwarmsEdit: () -> Unit = {},
    onNavigateToInternalPromptsByCategory: (String) -> Unit = {},
    /** Bump (providerId, model) to the front of the Report-section
     *  recent-models list. Every report-section model picker fires
     *  this just before its onConfirm so the next picker render
     *  surfaces the pick under "Recent". */
    onRecordRecentReportModel: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val aiSettings = uiState.aiSettings
    val scope = rememberCoroutineScope()

    // Decoded view of GeneralSettings.recentReportModels — the
    // strings are stored as "providerId|model" so they round-trip
    // through prefs trivially; here we resolve the provider half
    // back to AppService for the picker rows. Recomputes when the
    // user picks a new model (the underlying list is mutated by
    // onRecordRecentReportModel, which bumps GeneralSettings).
    val recentReportPairs = remember(uiState.generalSettings.recentReportModels) {
        uiState.generalSettings.recentReportModels.mapNotNull { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val service = AppService.findById(parts[0]) ?: return@mapNotNull null
            service to parts[1]
        }
    }

    val reportsTotal = uiState.genericReportsTotal
    val reportsProgress = uiState.genericReportsProgress
    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0
    val currentReportId = uiState.currentReportId

    val iconGenEnabled = uiState.generalSettings.reportIconOn()
    val runtime = rememberReportRuntimeState(
        context = context,
        currentReportId = currentReportId,
        uiState = uiState,
        isComplete = isComplete,
        iconGenEnabled = iconGenEnabled,
        translationRuns = translationRuns,
        fanRuntime = fanRuntime,
        fanOutEngine = fanOutEngine,
        translationLifecycle = translationLifecycle,
        onResumeStaleRuns = onResumeStaleRuns,
        onDeleteSecondary = onDeleteSecondary
    )
    val secondaryCounts = runtime.secondaryCounts
    val secondaryRuns = runtime.secondaryRuns
    val translateRows = runtime.translateRows
    val translationRunSummaries = runtime.translationRunSummaries
    val fanOutSummaries = runtime.fanOutSummaries
    val secondaryTotals = runtime.secondaryTotals
    val costsFromDeletedItems = runtime.costsFromDeletedItems
    val reportIcon = runtime.reportIcon
    val reportIconError = runtime.reportIconError
    val reportIconCost = runtime.reportIconCost
    val languageDetectCost = runtime.languageDetectCost
    val reportIconModel = runtime.reportIconModel
    val reportIconTraceFile = runtime.reportIconTraceFile
    val languageIconCost = runtime.languageIconCost
    val languageName = runtime.languageName
    val agentIconRows = runtime.agentIconRows
    val agentModelTitles = runtime.agentModelTitles
    val infoEnabled = runtime.infoEnabled
    val infoState = runtime.infoState
    val infoMetaTotal = runtime.infoMetaTotal
    val agentRecordsByAgentId = runtime.agentRecordsByAgentId
    val loadedReportPrompt = runtime.loadedReportPrompt
    val loadedReportTitle = runtime.loadedReportTitle
    val loadedReportTimestamp = runtime.loadedReportTimestamp
    val effectiveReportIcon = runtime.effectiveReportIcon
    val onDeleteSecondaryWithRefresh = runtime.onDeleteSecondaryWithRefresh
    val onSecondaryRefresh = runtime.onSecondaryRefresh
    // Overlay state for any flow that can hand off to a Compose
    // Navigation destination (trace detail, model info, …) needs to
    // be saveable: the AI_REPORTS Composable is removed from
    // composition while the user is on the destination, so plain
    // remember{} state would reset on back-pop and the user would
    // land back at the report root instead of the overlay they came
    // from. rememberSaveable persists through the back-stack.
    val st = rememberReportsScreenState(initialModels)
    var openMetaResultId by st.openMetaResultId
    var openTranslationRunId by st.openTranslationRunId

    // Locked language carried into sub-screens when the View page's
    // top picker is non-Original. Set when a View tile is tapped,
    // cleared on close. null = no force; "" = force Original;
    // non-empty = displayName. See
    // ReportsViewerScreen.forcedLanguage for the contract.
    var viewerLockedLanguage by st.viewerLockedLanguage
    var secondaryLockedLanguage by st.secondaryLockedLanguage
    var listLockedLanguage by st.listLockedLanguage

    var showViewer by st.showViewer
    // View → Icons overlay state. Surfaces a tiny screen rendering
    // every agent's emoji (from the 3-tier per-model icon chain) at
    // very large font. Button gated on perModelIconGenEnabled at
    // GenerationPhase level — this state stays false when the toggle
    // is off so the overlay can never be invoked through other paths.
    var showIconsView by st.showIconsView
    var showIconDetail by st.showIconDetail
    var agentIconDetailFor by st.agentIconDetailFor
    var editModelTitleFor by st.editModelTitleFor
    var findTitlesFor by st.findTitlesFor
    var findTitlesLong by st.findTitlesLong
    var showAlternativeTitles by st.showAlternativeTitles
    var altPickedTitle by st.altPickedTitle
    var altPickedTitleLong by st.altPickedTitleLong
    var showFindIconsPicker by st.showFindIconsPicker
    var showAlternativeIcons by st.showAlternativeIcons
    // Multiplex flag: when true, the showIconDetail / showFindIconsPicker
    // / showAlternativeIcons overlays render the language flow
    // instead of the report-icon flow. Reset on every close path so
    // a subsequent tap on the report icon opens its own flow again.
    var targetLanguageIcon by st.targetLanguageIcon
    // ── Internal-prompt icon flow. When non-null, the user tapped
    // the cached emoji that replaces ✅ on a successful secondary
    // row and is now drilled into the Meta-icon detail screen.
    // Drives the per-prompt detail overlay AND routes the shared
    // showAlternativeIcons / showFindIconsPicker blocks below
    // through `internalPromptIconFanOutByPrompt` /
    // `onStartInternalPromptIconFanOut` /
    // `onPickInternalPromptIcon` /
    // `onRestartInternalPromptIconFanOut` so the per-prompt
    // candidate list lives separately from the per-agent and
    // per-report candidate maps. Cleared on back or after a pick.
    var promptIconDetailForId by st.promptIconDetailForId
    // Sibling of [promptIconDetailForId] — when non-null the icon
    // detail / alt-pick flow was opened from a specific Meta
    // SecondaryResult row, and the eventual alt pick should write to
    // that row's `icon` field (per-row override) instead of the
    // shared per-(name,title) cache entry. Null = legacy name-keyed
    // path (every tile sharing that name picks up the new emoji).
    var metaRowIdForPromptIcon by st.metaRowIdForPromptIcon
    // ── Translation-icon flow. Sibling of `promptIconDetailForId`
    // — when non-null, the user tapped the cached emoji on a
    // translation summary row and is now drilled into the
    // Translation-icon detail screen. Routes the shared
    // showAlternativeIcons / showFindIconsPicker blocks through
    // the per-language path; the candidate list reuses
    // `internalPromptIconFanOutByPrompt` keyed by the synthetic
    // `"translation_icon" + U+001F + language` join.
    var translationIconLanguageFor by st.translationIconLanguageFor
    // Non-null when the active "Find / View alternative icons" flow
    // is per-agent (reached from AgentIconDetailScreen). Drives the
    // Find-icons picker + Alternative-icons screen blocks to dispatch
    // through the agent-targeted callbacks instead of the report-
    // level ones, and to read from agentIconFanOutByAgent instead of
    // iconFanOutByReport. Cleared on pick or on agent detail back.
    var fanOutTargetAgentId by st.fanOutTargetAgentId
    // Per-fan-out-pair icon detail (6th adapter). When non-null the
    // user tapped a fan-out pair's icon (L2 long-press / L3 big icon)
    // and is now on the unified Icon-lookup screen for that pair.
    // Routes the Find / View alternative icons blocks through
    // pairIconFanOutByPair + the per-pair start/pick/restart
    // callbacks.
    var pairIconDetailFor by st.pairIconDetailFor
    var pairTitleDetailFor by st.pairTitleDetailFor
    var findIconsModels by st.findIconsModels
    // Accumulator for the Translate model picker — same +Agent /
    // +Flock / +Swarm / +Report / +Model affordances as Find icons,
    // routed via PickerTarget.TRANSLATION.
    var translationModels by st.translationModels
    // Which model-picker target the next +Add overlay confirm should
    // deposit into. NEW_REPORT = the SelectionPhase's `models` list
    // (existing behavior); FIND_ICONS = the [findIconsModels] list
    // backing the "Find icons" picker overlay. The +Add overlays
    // (showSelectAgent / showSelectFlock / showSelectSwarm /
    // showSelectAllModels / showSelectFromReport) are shared between
    // the two flows; this flag is what decides where the picked rows
    // land. Reset to NEW_REPORT whenever the find-icons picker closes
    // so a later New-Report +Add doesn't leak the find-icons target.
    var pickerTarget by st.pickerTarget
    var selectedAgentForViewer by st.selectedAgentForViewer
    var viewerSection by st.viewerSection
    // Per-row click → focused single-model viewer. Distinct from the
    // multi-agent ReportsViewerScreen reached via View → Results.
    var singleResultAgentId by st.singleResultAgentId
    var showExport by st.showExport
    // Tracks the in-app HTML preview's selected detail level. Null →
    // not shown. Default-COMPLETE when the action-row HTML button
    // opens it; the Export screen's "View in app" pipes its own
    // detail picker through.
    var htmlPreviewDetail by st.htmlPreviewDetail
    // Optional language filter piped through from the Export screen's
    // Language card. Plain `remember` (not rememberSaveable) — the
    // sealed-class Saver isn't worth writing for a value the user
    // re-establishes when they reopen Export. Process death falls
    // back to All.
    var htmlPreviewLanguage by st.htmlPreviewLanguage
    // Fan-out "View" overlay (content-only sibling of FanOutScreen).
    // Set to a non-null metaPromptName when the user taps a fan-out
    // tile on Report - view; cleared on back. The accompanying
    // language carries the View screen's active picker so the opened
    // screen renders the matching translated body for each pair.
    var fanOutViewName by st.fanOutViewName
    var fanOutViewLanguage by st.fanOutViewLanguage
    var showEditPrompt by st.showEditPrompt
    var altPromptEditorPassed by st.altPromptEditorPassed
    var showGetInfo by st.showGetInfo
    var showEditParameters by st.showEditParameters
    var showAdvancedParameters by st.showAdvancedParameters
    // Translate flow state.
    var showTranslateLanguagePicker by st.showTranslateLanguagePicker
    var showTranslateModelPicker by st.showTranslateModelPicker
    // "Find alternative translation" flow — the target L3 item + a
    // picker/candidate toggle. While [altTranslateTarget] is non-null
    // the translation run mount yields so the shared model picker +
    // candidate screen render over it.
    var altTranslateTarget by st.altTranslateTarget
    var showAltTranslatePicker by st.showAltTranslatePicker
    // Bumped after an alt-translation pick overwrites a row so the
    // (yielded → remounted) translation run reloads from disk.
    var translationRunRefreshTick by rememberSaveable { mutableStateOf(0) }
    // rememberSaveable so a nav-hop out of AI_REPORTS (Model Info from
    // a selected-models row, Help, etc.) and back doesn't clear the
    // selection — the user was just inspecting one row, not abandoning
    // the picker.
    var models by st.models
    var showDeleteConfirm by st.showDeleteConfirm
    var showRegenerateConfirm by st.showRegenerateConfirm
    // [ViewAiReportScreen] — opened from the result page's bottom-bar
    // ℹ️ icon. Replaces the model-info AlertDialog that used to live
    // on that slot (now removed). Surfaces every "look at this report"
    // sub-view (Prompt / Costs / Reports / HTML / Log / Icons +
    // conditional Meta / Rerank / Fan-out / Fan-in /
    // Translate) as a tile grid; tapping a tile routes through the
    // same handlers the old Row 2 "View" buttons fired, so every
    // destination is unchanged.
    var showViewReportScreen by st.showViewReportScreen
    // Seed the View tile-grid overlay on first composition when the
    // AI_REPORTS route was entered with `initialView=true`. Helper-
    // hosted so the LaunchedEffect doesn't add bytecode to
    // [ReportsScreen], which already sits at the JVM 64 KB
    // per-method ceiling.
    SeedInitialViewReportScreen { showViewReportScreen = true }
    // Sibling seed for the 🗂️-pick re-entry: opens the Manage sub-overlay
    // the report was picked from. Helper-hosted for the same 64 KB reason.
    SeedInitialManageOverlay(st)
    // Track the user's CURRENT mode (Manage vs View) in real-time so
    // the Hub's big-AI-logo "resume" knob lands them back where
    // they actually were. Extracted out of [ReportsScreen] for the
    // same bytecode-size reason as SeedInitialViewReportScreen.
    TrackLastReportMode(reportId = currentReportId, viewMode = showViewReportScreen)
    // Action-row pickers — hoisted up here so they render as proper
    // full-screen overlays. When they lived inside GenerationPhase the
    // parent TitleBar + ActionRow had already painted above them, so
    // the picker visibly stacked on top of a half-drawn screen. View
    // and Edit no longer drill into a full-screen picker — the new
    // two-row action bar exposes their sub-actions inline.
    var showMetaPicker by st.showMetaPicker
    var showFanOutPicker by st.showFanOutPicker
    var showRerankPicker by st.showRerankPicker
    var showModerationPicker by st.showModerationPicker

    // One-shot consumer: when ReportViewModel (Edit models / Regenerate flows) drops a
    // pre-built model list into uiState.pendingReportModels, copy it into the local
    // selection state and clear the signal so we don't keep re-applying it.
    LaunchedEffect(uiState.pendingReportModels) {
        if (uiState.pendingReportModels.isNotEmpty()) {
            models = deduplicateModels(uiState.pendingReportModels)
            onConsumePendingModels()
        }
    }
    var showSelectFlock by st.showSelectFlock
    var showSelectAgent by st.showSelectAgent
    var showSelectSwarm by st.showSelectSwarm
    var showSelectProvider by st.showSelectProvider
    var pendingProvider by st.pendingProvider
    var showSelectAllModels by st.showSelectAllModels
    var showSelectFromReport by st.showSelectFromReport
    var selectedParametersIds by st.selectedParametersIds
    // Meta prompt state — replaces the old kind-specific state. The
    // user picks one Meta prompt at a time from the new Meta card or
    // the unified Meta hub; type=chat goes through the scope screen
    // first, type=rerank/moderation skips it.
    var secondaryPickerMetaPrompt by st.secondaryPickerMetaPrompt
    // Meta-only Run page shown between the Scope screen and the model
    // picker. Carries an editable prompt body that lives just for this
    // single run; nulled out after the user picks a model. Mirrors
    // FanOutConfirmScreen's per-run prompt edit.
    var metaRunScreenPrompt by st.metaRunScreenPrompt
    var secondaryScopeMetaPrompt by st.secondaryScopeMetaPrompt
    var pendingSecondaryScope by st.pendingSecondaryScope
    var pendingLanguageScope by st.pendingLanguageScope
    var fanOutSelfRespond by st.fanOutSelfRespond
    // Fan-out confirm dialog: shown after the scope screen, before
    // kicking off N answerers × S sources calls. The user can still
    // cancel from here if the count looks too high.
    var fanOutConfirmMetaPrompt by st.fanOutConfirmMetaPrompt
    // Fan_in run model picker. Triggered from the fan out detail
    // screen's "Combine reports and all fan out responses" button.
    var fanInPickerPrompt by st.fanInPickerPrompt
    // Source language inherited from the parent fan-out (null =
    // Original). Captured at trigger time so the picker → onRunFanIn
    // chain can forward it to runFanInPrompt without re-reading the
    // engine.
    var fanInPickerSourceLanguage by st.fanInPickerSourceLanguage
    // First step of the fan_in flow: pick which fan_in prompt
    // to run. Once chosen we hand off to fanInPickerPrompt above.
    var showFanInPromptPicker by st.showFanInPromptPicker
    // Unified Meta screen overlay reached from the Actions card.
    var showMetaScreen by st.showMetaScreen
    // Per-name (or per-legacy-kind) list overlay reached from the View
    // card buttons. The kind is still useful for routing through
    // SecondaryResultsScreen (rendering picks the chat-type META path
    // vs the structured RERANK / MODERATION / TRANSLATE branches), so
    // we carry both. rememberSaveable so navigating away (e.g. tapping
    // a 🐞 → trace detail) and popping back lands on the same list,
    // not the top of the report screen.
    var listKind by st.listKind
    var listFilterByName by st.listFilterByName
    /** When true, the SecondaryResultsScreen for the active
     *  (kind, name) renders the Fan Meta drill-in (FanOutScreen in
     *  META mode) instead of the regular fan-out detail. */
    var listIsFanMeta by st.listIsFanMeta

    // Screen keepalive during generation
    DisposableEffect(isGenerating, isComplete) {
        if (isGenerating && !isComplete) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    HandleExternalReportInstructions(
        context = context,
        activity = activity,
        uiState = uiState,
        aiSettings = aiSettings,
        isGenerating = isGenerating,
        isComplete = isComplete,
        currentReportId = currentReportId,
        models = models,
        selectedParametersIds = selectedParametersIds,
        onModelsChange = { models = it },
        onGenerate = onGenerate,
        onOpenView = {
            // Report - Manage entry: clear any stale lock left over
            // from a previous View-tile flow so this open shows the
            // per-screen language picker as intended.
            viewerLockedLanguage = null
            showViewer = true
        },
        onClearExternalInstructions = onClearExternalInstructions
    )

    if (ReportPrimaryOverlays(
            showIconsView = showIconsView,
            singleResultAgentId = singleResultAgentId,
            currentReportId = currentReportId,
            effectiveReportIcon = effectiveReportIcon,
            loadedReportTitle = loadedReportTitle,
            showViewReportScreen = showViewReportScreen,
            showViewer = showViewer,
            htmlPreviewDetail = htmlPreviewDetail,
            openMetaResultId = openMetaResultId,
            openTranslationRunId = openTranslationRunId,
            listKind = listKind,
            secondaryRuns = secondaryRuns,
            translateRows = translateRows,
            fanOutSummaries = fanOutSummaries,
            aiSettings = aiSettings,
            uiState = uiState,
            promptIconCallbacks = promptIconCallbacks,
            translationIconCallbacks = translationIconCallbacks,
            loadedReportTimestamp = loadedReportTimestamp,
            selectedAgentForViewer = selectedAgentForViewer,
            viewerSection = viewerSection,
            agentIconDetailFor = agentIconDetailFor,
            showAdvancedParameters = showAdvancedParameters,
            advancedParameters = advancedParameters,
            viewerLockedLanguage = viewerLockedLanguage,
            onViewerLockedLanguageChange = { viewerLockedLanguage = it },
            onSecondaryLockedLanguageChange = { secondaryLockedLanguage = it },
            onListLockedLanguageChange = { listLockedLanguage = it },
            onShowIconsViewChange = { showIconsView = it },
            onSingleResultAgentIdChange = { singleResultAgentId = it },
            onShowViewReportScreenChange = { showViewReportScreen = it },
            onShowViewerChange = { showViewer = it },
            onViewerSectionChange = { viewerSection = it },
            onSelectedAgentForViewerChange = { selectedAgentForViewer = it },
            onHtmlPreviewDetailChange = { htmlPreviewDetail = it },
            onOpenMetaResultIdChange = { openMetaResultId = it },
            onOpenTranslationRunIdChange = { openTranslationRunId = it },
            onListTargetChange = { kind, name ->
                listKind = kind
                listFilterByName = name
                listIsFanMeta = false
            },
            onNavigateHome = onNavigateHome,
            onNavigateToTrace = onNavigateToTrace,
            onNavigateToAppLog = onNavigateToAppLog,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onContinueWithCurrent = onContinueWithCurrent,
            onContinueWithAgentPicker = onContinueWithAgentPicker,
            onContinueWithOnTheFly = onContinueWithOnTheFly,
            onRemoveAgent = onRemoveAgent,
            onRegenerateAgent = onRegenerateAgent,
            temperatureSweepStates = temperatureSweepStates,
            onStartTemperatureSweep = onStartTemperatureSweep,
            onApplyTemperatureCandidate = onApplyTemperatureCandidate,
            onClearTemperatureSweep = onClearTemperatureSweep,
            reasoningEffortSweepStates = reasoningEffortSweepStates,
            onStartReasoningEffortSweep = onStartReasoningEffortSweep,
            onApplyReasoningEffortCandidate = onApplyReasoningEffortCandidate,
            onClearReasoningEffortSweep = onClearReasoningEffortSweep,
            onNavigateToModelInfo = onNavigateToModelInfo,
            onOpenAgentIcon = { agentIconDetailFor = it },
            onSecondaryRefresh = onSecondaryRefresh,
            reportLanguageName = languageName,
            onDeleteSecondaryRowById = onDeleteSecondaryWithRefresh,
            onAdvancedParametersChange = onAdvancedParametersChange,
            onParametersIdsChange = onParametersIdsChange,
            onShowAdvancedParametersChange = { showAdvancedParameters = it },
            onTranslateMissingItems = onTranslateMissingItems,
            fanOutViewName = fanOutViewName,
            fanOutViewLanguage = fanOutViewLanguage,
            onOpenFanOutView = { name, lang ->
                fanOutViewLanguage = lang
                fanOutViewName = name
            },
            onCloseFanOutView = { fanOutViewName = null; fanOutViewLanguage = null }
        )
    ) return

    // +Add selection-overlay waterfall — extracted to
    // SelectionOverlayDialogs (ReportScreenNav.kt) to keep this method
    // under the JVM 64 KB ceiling. Reads/writes picker state via [st],
    // so a true result short-circuits exactly like the old in-line
    // `return`s did.
    if (SelectionOverlayDialogs(
            st = st,
            aiSettings = aiSettings,
            recentReportPairs = recentReportPairs,
            recentReportModels = uiState.generalSettings.recentReportModels,
            onNavigateToModelInfo = onNavigateToModelInfo,
            onNavigateToFlocksEdit = onNavigateToFlocksEdit,
            onNavigateToAgentsEdit = onNavigateToAgentsEdit,
            onNavigateToSwarmsEdit = onNavigateToSwarmsEdit,
            onNavigateHome = onNavigateHome,
            onRecordRecentReportModel = onRecordRecentReportModel
        )
    ) return

    // Must stay after SelectionOverlayDialogs: the Find-icons picker can
    // open +Agent/+Flock/+Swarm/+Report overlays, and those overlays need
    // priority over the picker on the next recomposition.
    if (ReportIconFlowOverlays(
            st = st,
            uiState = uiState,
            runtime = runtime,
            iconFanOutByReport = iconFanOutByReport,
            internalPromptIconFanOutByPrompt = internalPromptIconFanOutByPrompt,
            agentIconFanOutByAgent = agentIconFanOutByAgent,
            pairIconFanOutByPair = pairIconFanOutByPair,
            titleFanOutByReport = titleFanOutByReport,
            titleFanOutByAgent = titleFanOutByAgent,
            promptIconCallbacks = promptIconCallbacks,
            translationIconCallbacks = translationIconCallbacks,
            languageIconCallbacks = languageIconCallbacks,
            onStartIconFanOut = onStartIconFanOut,
            onPickAlternativeIcon = onPickAlternativeIcon,
            onRestartIconFanOut = onRestartIconFanOut,
            onStartAgentIconFanOut = onStartAgentIconFanOut,
            onPickAgentIcon = onPickAgentIcon,
            onRestartAgentIconFanOut = onRestartAgentIconFanOut,
            onStartPairIconFanOut = onStartPairIconFanOut,
            onPickPairIcon = onPickPairIcon,
            onRestartPairIconFanOut = onRestartPairIconFanOut,
            pairTitleFanOutByPair = pairTitleFanOutByPair,
            onStartPairTitleFanOut = onStartPairTitleFanOut,
            onPickPairTitle = onPickPairTitle,
            onRestartPairTitleFanOut = onRestartPairTitleFanOut,
            onStartReportTitleFanOut = onStartReportTitleFanOut,
            onStartModelTitleFanOut = onStartModelTitleFanOut,
            onRestartReportTitleFanOut = onRestartReportTitleFanOut,
            onRestartModelTitleFanOut = onRestartModelTitleFanOut,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo,
            continueChat = ContinueChatCallbacks(
                onCurrent = onContinueWithCurrent,
                onAgentPicker = onContinueWithAgentPicker,
                onOnTheFly = onContinueWithOnTheFly,
            ),
            onResolveAltPrompt = onResolveAltPrompt,
            onStashAltEdit = onStashAltEdit,
        )
    ) return

    // Scope screen — shown before the picker for chat-type Meta
    // prompts. Lets the user pick the input set (all model results /
    // top-N from a rerank / a manual subset) and, when translation
    // rows exist, which target languages to fan out to.
    val scopeMetaPrompt = secondaryScopeMetaPrompt
    // Hidden while Run / model picker is active — layered state so
    // Android back unwinds one step at a time. Rerank and Moderation
    // also need to suppress Scope once their picker fires: their
    // `onContinue` arms set `showRerankPicker` / `showModerationPicker`
    // without clearing `secondaryScopeMetaPrompt` (so back unwinds
    // cleanly), so without these guards the scope screen out-renders
    // the picker block further down and Continue looks dead.
    if (scopeMetaPrompt != null && currentReportId != null
        && fanOutConfirmMetaPrompt == null
        && metaRunScreenPrompt == null
        && secondaryPickerMetaPrompt == null
        && !showRerankPicker
        && !showModerationPicker) {
        val rid = currentReportId
        data class ScopeData(
            val agents: List<com.ai.data.ReportAgent>,
            val reranks: List<com.ai.data.SecondaryResult>,
            val languages: List<Pair<String, String?>>,
            val totalReports: Int
        )
        val scopeDataState = produceState<ScopeData?>(initialValue = null, rid) {
            value = withContext(Dispatchers.IO) {
                val report = com.ai.data.ReportStorage.getReport(context, rid)
                val all = com.ai.data.SecondaryResultStorage.listForReport(context, rid)
                // Tournament aggregate rows conform to the rerank JSON
                // contract, so they're selectable as a Top-ranked source too.
                val rr = all.filter {
                    it.kind == com.ai.data.SecondaryKind.RERANK ||
                        (it.kind == com.ai.data.SecondaryKind.TOURNAMENT && it.tournamentRole == "AGGREGATE")
                }
                val successfulAgents = report?.agents?.filter { it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }.orEmpty()
                val nativeByLang = LinkedHashMap<String, String?>()
                all.filter { it.kind == com.ai.data.SecondaryKind.TRANSLATE && !it.targetLanguage.isNullOrBlank() }
                    .forEach { tr ->
                        val l = tr.targetLanguage!!
                        if (l !in nativeByLang) nativeByLang[l] = tr.targetLanguageNative
                    }
                ScopeData(successfulAgents, rr, nativeByLang.map { it.key to it.value }, successfulAgents.size)
            }
        }
        val sd = scopeDataState.value
        if (sd == null) return
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides { secondaryScopeMetaPrompt = null }
        ) {
            SecondaryScopeScreen(
                metaPrompt = scopeMetaPrompt,
                agents = sd.agents,
                reranks = sd.reranks,
                languages = sd.languages,
                totalReports = sd.totalReports,
                initialSelfRespond = fanOutSelfRespond,
                onContinue = { chosenScope, chosenLangScope, chosenSelfRespond ->
                    pendingSecondaryScope = chosenScope
                    pendingLanguageScope = chosenLangScope
                    fanOutSelfRespond = chosenSelfRespond
                    // Forward without clearing secondaryScopeMetaPrompt;
                    // the scope render guards on fanOutConfirmMetaPrompt /
                    // metaRunScreenPrompt being non-null so the higher
                    // step takes precedence while the scope state remains
                    // available for Android-back to unwind to.
                    when (scopeMetaPrompt.category) {
                        "fan_out" -> {
                            // Run page picks initiators / responders, edits
                            // the prompt, then confirms.
                            fanOutConfirmMetaPrompt = scopeMetaPrompt
                        }
                        "rerank" -> {
                            // Rerank has no editable prompt and no per-row
                            // scope — jump straight to the model picker;
                            // pendingLanguageScope is read at confirm.
                            showRerankPicker = true
                        }
                        "moderation" -> {
                            showModerationPicker = true
                        }
                        else -> {
                            // Meta path: Scope → Run page (edit prompt) → model picker.
                            metaRunScreenPrompt = scopeMetaPrompt
                        }
                    }
                },
                onBack = { secondaryScopeMetaPrompt = null },
                onNavigateHome = onNavigateHome
            )
        }
        return
    }

    // Fan-out confirm screen: shown after the scope screen, before
    // the runner kicks off. Re-derives the (answerers, sources) set
    // from the chosen scope so the user can see exactly how many
    // calls they're authorising — full-screen so the per-pair preview
    // has room to breathe.
    val fanOutMp = fanOutConfirmMetaPrompt
    if (fanOutMp != null && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                fanOutConfirmMetaPrompt = null
                secondaryScopeMetaPrompt = null
                showFanOutPicker = false
            }
        ) {
            FanOutConfirmScreen(
                fanOutMp = fanOutMp,
                reportId = currentReportId,
                context = context,
                aiSettings = aiSettings,
                letSelfRespond = fanOutSelfRespond,
                // Back from Run → unwind to Scope (state still set);
                // back from there → Picker; back from there → main.
                onCancel = { fanOutConfirmMetaPrompt = null },
                onRun = { mp, initiators, responders, pIds, spId ->
                    // Pull the single fan-out language from the scope
                    // step's Selected set — empty string = Original
                    // (untranslated). AllPresent collapses to null
                    // for the legacy / no-translation path.
                    val sourceLanguage: String? = when (val ls = pendingLanguageScope) {
                        is com.ai.data.SecondaryLanguageScope.Selected ->
                            ls.languages.firstOrNull()?.takeIf { it.isNotEmpty() }
                        com.ai.data.SecondaryLanguageScope.AllPresent -> null
                    }
                    // Commit clears the whole fan-out stack so the user
                    // doesn't pop back into Scope/Picker after the run
                    // kicked off.
                    fanOutConfirmMetaPrompt = null
                    secondaryScopeMetaPrompt = null
                    showFanOutPicker = false
                    pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                    pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
                    onRunFanOut(
                        currentReportId, mp,
                        com.ai.data.SecondaryScope.Manual(initiators),
                        responders,
                        sourceLanguage,
                        pIds, spId,
                        fanOutSelfRespond
                    )
                    // Land on the Fan Out L1 page so the user watches the
                    // run progress instead of the report screen.
                    listKind = SecondaryKind.META
                    listFilterByName = mp.name
                    listIsFanMeta = false
                }
            )
        }
        return
    }

    // Meta Run page — full-screen prompt editor inserted between the
    // Scope screen (or the Default-scope fast-path) and the model
    // picker. The text the user lands on the picker with is whatever
    // they leave the field at.
    val metaRunMp = metaRunScreenPrompt
    if (metaRunMp != null && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                metaRunScreenPrompt = null
                secondaryScopeMetaPrompt = null
            }
        ) {
            MetaRunScreen(
                metaPrompt = metaRunMp,
                onCancel = { metaRunScreenPrompt = null },
                onContinue = { edited ->
                    metaRunScreenPrompt = null
                    secondaryPickerMetaPrompt = edited
                }
            )
        }
        return
    }

    // Meta prompt model picker. Reuses the same multi-select screen
    // with the Meta prompt's name as the label.
    val pickerMetaPrompt = secondaryPickerMetaPrompt
    if (pickerMetaPrompt != null && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                secondaryPickerMetaPrompt = null
                secondaryScopeMetaPrompt = null
            }
        ) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                // Single-pick: tap fires the meta run for one model and pops
                // back. Users wanting two runs just open the picker twice.
                titleText = "${pickerMetaPrompt.name} — pick model",
                recentEntries = recentReportPairs,
                onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
                onConfirm = { /* secondary picker uses onSecondaryParamsConfirm */ },
                onSecondaryParamsConfirm = { pick, pIds, spId ->
                    onRunSecondary(rid, pickerMetaPrompt, listOf(pick), pendingSecondaryScope, pendingLanguageScope, pIds, spId)
                    // Clear the WHOLE meta-creation stack — picker + scope —
                    // so the user lands back on the report once the run
                    // kicks off. Without clearing `secondaryScopeMetaPrompt`
                    // the scope-screen guard re-fires on recompose and the
                    // user gets bounced back to Scope (the layered state
                    // that makes Android back unwind step-by-step also has
                    // to be torn down on the forward commit path). Mirrors
                    // the fan-out commit handler.
                    secondaryPickerMetaPrompt = null
                    secondaryScopeMetaPrompt = null
                    pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                    pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
                },
                onBack = { secondaryPickerMetaPrompt = null },
                onNavigateHome = onNavigateHome
            )
        }
        return
    }

    // Fan_in model picker — shown when the user taps the
    // "Combine reports and all fan out responses" button on the fan out
    // detail screen. Single-pick; on confirm we kick the runner and
    // pop back so the fan out detail screen's inline preview can pick
    // up the placeholder via the isBatching poll.
    val fanInPicker = fanInPickerPrompt
    if (fanInPicker != null && currentReportId != null) {
        val rid = currentReportId
        ReportSelectModelsScreen(
            aiSettings = aiSettings,
            titleText = "${fanInPicker.name} — pick model",
            modelTypeFilter = null,
            recentEntries = recentReportPairs,
            onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
            onConfirm = { /* secondary picker uses onSecondaryParamsConfirm */ },
            onSecondaryParamsConfirm = { pick, pIds, spId ->
                onRunFanIn(rid, fanInPicker, pick, fanInPickerSourceLanguage, pIds, spId)
                fanInPickerPrompt = null
                fanInPickerSourceLanguage = null
            },
            onBack = {
                fanInPickerPrompt = null
                fanInPickerSourceLanguage = null
            },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Translate overlays. Order: language picker → model picker →
    // progress screen. The first two close the picker once a choice is
    // made; the progress screen sticks around until the run finishes
    // and the user taps "To translated report" (or Cancel).
    if (showTranslateLanguagePicker) {
        // Picker overlays opt OUT of the standard TitleBar swipe —
        // these composables are also used outside the AI Report flow
        // (e.g. future AI Chat surfaces), so the report-context local
        // is overridden to null here.
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showTranslateLanguagePicker = false }, com.ai.ui.shared.LocalCurrentReportIdForSwipe provides null) {
            LanguageSelectionScreen(
                onConfirm = { lang ->
                    showTranslateLanguagePicker = false
                    translationModels = emptyList()
                    showTranslateModelPicker = lang
                },
                onBack = { showTranslateLanguagePicker = false },
                onNavigateHome = onNavigateHome
            )
        }
        return
    }
    val pickingTranslateModelFor = showTranslateModelPicker
    if (pickingTranslateModelFor != null && currentReportId != null) {
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showTranslateModelPicker = null }, com.ai.ui.shared.LocalCurrentReportIdForSwipe provides null) {
            ModelSelectionScreen(
                models = translationModels,
                aiSettings = aiSettings,
                title = "Pick translation models",
                subject = "${pickingTranslateModelFor.name} (${pickingTranslateModelFor.native})",
                actionLabel = if (translationModels.size <= 1) "Start translation"
                              else "Start translation — ${translationModels.size} models",
                actionColor = AppColors.Green,
                helpTopic = "translation_models",
                onAddAgent = { pickerTarget = PickerTarget.TRANSLATION; showSelectAgent = true },
                onAddFlock = { pickerTarget = PickerTarget.TRANSLATION; showSelectFlock = true },
                onAddSwarm = { pickerTarget = PickerTarget.TRANSLATION; showSelectSwarm = true },
                onAddFromReport = { pickerTarget = PickerTarget.TRANSLATION; showSelectFromReport = true },
                onAddAllModels = { pickerTarget = PickerTarget.TRANSLATION; showSelectAllModels = true },
                onRemoveModel = { idx -> translationModels = translationModels.toMutableList().apply { removeAt(idx) } },
                onClearAll = { translationModels = emptyList() },
                onAction = { },
                onActionWithParams = { pIds, spId ->
                    onStartTranslation(
                        currentReportId,
                        pickingTranslateModelFor.name,
                        pickingTranslateModelFor.native,
                        translationModels.map { it.provider to it.model },
                        pIds, spId
                    )
                    translationModels = emptyList()
                    pickerTarget = PickerTarget.NEW_REPORT
                    showTranslateModelPicker = null
                },
                onBack = {
                    pickerTarget = PickerTarget.NEW_REPORT
                    showTranslateModelPicker = null
                }
            )
        }
        return
    }

    if (showRerankPicker && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showRerankPicker = false }, com.ai.ui.shared.LocalCurrentReportIdForSwipe provides null) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "Pick rerank model",
                recentEntries = recentReportPairs,
                onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
                onConfirm = { /* secondary picker uses onSecondaryParamsConfirm */ },
                onSecondaryParamsConfirm = { pick, pIds, spId ->
                    val ls = pendingLanguageScope
                    showRerankPicker = false
                    secondaryScopeMetaPrompt = null
                    pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                    pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
                    onRunRerank(rid, pick, ls, pIds, spId)
                },
                onBack = { showRerankPicker = false },
                onNavigateHome = onNavigateHome,
                modelTypeFilter = com.ai.data.ModelType.RERANK
            )
        }
        return
    }

    if (showModerationPicker && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides { showModerationPicker = false },
            com.ai.ui.shared.LocalCurrentReportIdForSwipe provides null
        ) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "Pick moderation model",
                recentEntries = recentReportPairs,
                onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
                onConfirm = { pick ->
                    val ls = pendingLanguageScope
                    showModerationPicker = false
                    secondaryScopeMetaPrompt = null
                    pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                    pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
                    onRunModeration(rid, pick, ls)
                },
                onBack = { showModerationPicker = false },
                onNavigateHome = onNavigateHome,
                modelTypeFilter = com.ai.data.ModelType.MODERATION
            )
        }
        return
    }

    // Per-meta-run detail overlay reached from a Meta row in the
    // result list. Routes through the same SecondaryResultDetailScreen
    // the Meta hub uses, so navigation / delete behave identically.
    val openMetaResult = openMetaResultId?.let { id -> secondaryRuns.firstOrNull { it.id == id } }
    if (openMetaResult != null && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { openMetaResultId = null }) {
            SecondaryResultDetailScreen(
                result = openMetaResult,
                onDelete = {
                    onDeleteSecondaryWithRefresh(rid, openMetaResult.id)
                    openMetaResultId = null
                    secondaryLockedLanguage = null
                },
                onBack = {
                    openMetaResultId = null
                    secondaryLockedLanguage = null
                },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToModelInfo = onNavigateToModelInfo,
                forcedLanguage = secondaryLockedLanguage,
                onDeleteRowById = { resultId -> onDeleteSecondaryWithRefresh(rid, resultId) }
            )
        }
        return
    }

    val openRunId = openTranslationRunId
    // Yield while a "Find alternative translation" flow is active so the
    // shared model picker + candidate screen (hosted below) render over
    // the run screen. Clearing altTranslateTarget remounts this block,
    // whose loadPersisted re-reads the (possibly overwritten) rows.
    if (openRunId != null && currentReportId != null && altTranslateTarget == null) {
        val rid = currentReportId
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { openTranslationRunId = null }) {
            TranslationRunScreen(
                reportId = rid,
                runId = openRunId,
                externalRefresh = translationRunRefreshTick,
                throttledItems = throttledTranslationItems,
                onChangeRunId = { openTranslationRunId = it },
                // Live state for the in-flight run. Null after the run
                // finishes — the screen then reconstructs it from disk
                // via onBuildPersistedTranslationRun.
                liveRun = translationRuns.firstOrNull { it.runId == openRunId && !it.isFinished },
                loadPersisted = { onBuildPersistedTranslationRun(rid, openRunId) },
                actions = TranslationActions(
                    // Delete = cancel + join the runner, then drop every
                    // persisted row — sequenced so nothing resurrects.
                    // Wrapped so the returned Job only completes once the
                    // refresh tick has been bumped: the L1 screen joins
                    // it before navigating back, so the report page's
                    // translation-run list is re-read with the row gone.
                    onDeleteRun = { srcRid, id ->
                        scope.launch {
                            translationLifecycle.onDeleteRun(srcRid, id)?.join()
                            onSecondaryRefresh()
                        }
                    },
                    onRestartFailed = { srcRid, runId ->
                        onRestartFailedTranslations(srcRid, runId)
                        onSecondaryRefresh()
                    },
                    onRemoveFailed = { srcRid, runId ->
                        onRemoveFailedTranslations(srcRid, runId)
                        onSecondaryRefresh()
                    },
                    onRemoveBenched = { srcRid, runId ->
                        onRemoveBenchedTranslations(srcRid, runId)
                        onSecondaryRefresh()
                    },
                    onRestartAll = { srcRid, runId ->
                        onRestartAllTranslations(srcRid, runId)
                        onSecondaryRefresh()
                    },
                    onStartMissing = { srcRid, runId ->
                        onStartMissingTranslations(srcRid, runId)
                        onSecondaryRefresh()
                    },
                    onCancelItem = { runId, itemId -> translationLifecycle.onCancelItem(runId, itemId) },
                    onDeleteSecondaryRow = { srcRid, resultId -> onDeleteSecondaryWithRefresh(srcRid, resultId) },
                    onNavigateToTraceFile = onNavigateToTraceFile,
                    onNavigateToTraceList = { onNavigateToTraceListFiltered(rid, "Translation") },
                    onNavigateToTraceRunList = onNavigateToTraceRunList,
                    onNavigateToModelInfo = onNavigateToModelInfo,
                    onNavigateHome = onNavigateHome,
                    onSetMode = translationLifecycle.onSetMode,
                    onFindAlternativeTranslation = { itemId, isTitle, src, traceType, lang, rowId ->
                        translationModels = emptyList()
                        pickerTarget = PickerTarget.TRANSLATION
                        altTranslateTarget = AltTranslateTarget(
                            reportId = rid, runId = openRunId, itemId = itemId,
                            isTitleKind = isTitle, sourceText = src, traceType = traceType,
                            targetLanguageName = lang, persistedRowId = rowId
                        )
                        altPromptEditorPassed = false
                        showAltTranslatePicker = true
                    }
                ),
                onBack = { openTranslationRunId = null }
            )
        }
        return
    }

    // ── "Find alternative translation" — model picker (Block A) then
    // candidate screen (Block B). Reached only while a translation run
    // is open (altTranslateTarget set from the L3 action above). The
    // model sub-pickers (SelectionOverlayDialogs) already render above
    // this point and fall back here on dismiss.
    val altTgt = altTranslateTarget
    // Pre-pick "Edit prompt" for Find-alternative-translation — shown
    // before the model picker, like every other find-alt flow.
    if (altTgt != null && showAltTranslatePicker && !altPromptEditorPassed && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides { altTranslateTarget = null; showAltTranslatePicker = false; altPromptEditorPassed = false; translationModels = emptyList(); onStashAltEdit(null) },
            com.ai.ui.shared.LocalCurrentReportIdForSwipe provides null
        ) {
            FindAltPromptEditorScreen(
                flow = AltPromptFlow.TranslationText(altTgt.isTitleKind, altTgt.targetLanguageName, altTgt.sourceText),
                aiSettings = aiSettings,
                onResolve = onResolveAltPrompt,
                onNext = { payload -> onStashAltEdit(payload); altPromptEditorPassed = true },
                onBack = { altTranslateTarget = null; showAltTranslatePicker = false; altPromptEditorPassed = false; translationModels = emptyList(); onStashAltEdit(null) }
            )
        }
        return
    }
    if (altTgt != null && showAltTranslatePicker && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides { altTranslateTarget = null; showAltTranslatePicker = false; altPromptEditorPassed = false; translationModels = emptyList() },
            com.ai.ui.shared.LocalCurrentReportIdForSwipe provides null
        ) {
            ModelSelectionScreen(
                models = translationModels,
                aiSettings = aiSettings,
                title = "Find alternative translation",
                subject = "${altTgt.targetLanguageName} - ${translationTypeLabel(altTgt.traceType)}",
                actionLabel = if (translationModels.size <= 1) "Find translation"
                              else "Find translation — ${translationModels.size} models",
                actionColor = AppColors.Green,
                helpTopic = "alternative_translations",
                onAddAgent = { pickerTarget = PickerTarget.TRANSLATION; showSelectAgent = true },
                onAddFlock = { pickerTarget = PickerTarget.TRANSLATION; showSelectFlock = true },
                onAddSwarm = { pickerTarget = PickerTarget.TRANSLATION; showSelectSwarm = true },
                onAddFromReport = { pickerTarget = PickerTarget.TRANSLATION; showSelectFromReport = true },
                onAddAllModels = { pickerTarget = PickerTarget.TRANSLATION; showSelectAllModels = true },
                onRemoveModel = { idx -> translationModels = translationModels.toMutableList().apply { removeAt(idx) } },
                onClearAll = { translationModels = emptyList() },
                onAction = {
                    onStartAltTranslationFanOut(altTgt.reportId, altTgt.itemId, altTgt.targetLanguageName, altTgt.isTitleKind, altTgt.sourceText, altTgt.traceType, translationModels, emptyList(), null)
                    translationModels = emptyList()
                    pickerTarget = PickerTarget.NEW_REPORT
                    altPromptEditorPassed = false
                    showAltTranslatePicker = false
                },
                onActionWithParams = { pIds, spId ->
                    onStartAltTranslationFanOut(altTgt.reportId, altTgt.itemId, altTgt.targetLanguageName, altTgt.isTitleKind, altTgt.sourceText, altTgt.traceType, translationModels, pIds, spId)
                    translationModels = emptyList()
                    pickerTarget = PickerTarget.NEW_REPORT
                    altPromptEditorPassed = false
                    showAltTranslatePicker = false
                },
                onBack = { altTranslateTarget = null; showAltTranslatePicker = false; altPromptEditorPassed = false; translationModels = emptyList() }
            )
        }
        return
    }
    if (altTgt != null && !showAltTranslatePicker && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides { altTranslateTarget = null }
        ) {
            AlternativeTranslationsScreen(
                candidates = altTranslationByItem[altTgt.itemId].orEmpty(),
                onPick = { done ->
                    onApplyAltTranslation(altTgt.reportId, altTgt.runId, altTgt.itemId, altTgt.persistedRowId, done)
                    altTranslateTarget = null
                    translationRunRefreshTick++
                },
                onRestart = {
                    onRestartAltTranslationFanOut(altTgt.itemId)
                    showAltTranslatePicker = true
                },
                onBack = { altTranslateTarget = null }
            )
        }
        return
    }

    val openListKind = listKind
    if (openListKind != null && currentReportId != null) {
        SecondaryResultsListMount(
            reportId = currentReportId,
            openListKind = openListKind,
            internalPrompts = aiSettings.internalPrompts,
            listFilterByName = listFilterByName,
            listIsFanMeta = listIsFanMeta,
            isBatching = uiState.activeSecondaryBatches > 0,
            runningFanOutPairs = runningFanOutPairs,
            fanRuntime = fanRuntime,
            fanOutEngine = fanOutEngine,
            effectiveReportIcon = effectiveReportIcon,
            loadedReportTitle = loadedReportTitle,
            showFanInPromptPicker = showFanInPromptPicker,
            onShowFanInPromptPickerChange = { showFanInPromptPicker = it },
            onFanInPickerPromptChange = { fanInPickerPrompt = it },
            onFanInPickerSourceLanguageChange = { fanInPickerSourceLanguage = it },
            onCloseList = {
                listKind = null
                listFilterByName = null
                listIsFanMeta = false
                listLockedLanguage = null
            },
            onShowResponses = { listIsFanMeta = false },
            onShowFanMeta = { listIsFanMeta = true },
            onSecondaryRefresh = onSecondaryRefresh,
            onCreateReportFromFanOut = onCreateReportFromFanOut,
            onDeleteSecondaryWithRefresh = onDeleteSecondaryWithRefresh,
            onBulkDeleteSecondaries = onBulkDeleteSecondaries,
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToTraceRunList = onNavigateToTraceRunList,
            onNavigateToModelInfo = onNavigateToModelInfo,
            onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
            onNavigateToInternalPromptsByCategory = onNavigateToInternalPromptsByCategory,
            onResumeStaleFanOut = onResumeStaleFanOut,
            onRestartFailedFanOut = onRestartFailedFanOut,
            onRemoveFailedFanOut = onRemoveFailedFanOut,
            onRestartFailedFanOutForModel = onRestartFailedFanOutForModel,
            onRemoveFailedFanOutForModel = onRemoveFailedFanOutForModel,
            onRerunCompleteFanOut = onRerunCompleteFanOut,
            onRerunFanOutPair = onRerunFanOutPair,
            onDeleteFanOutModel = onDeleteFanOutModel,
            forcedLanguage = listLockedLanguage,
            onOpenPairIconLookup = { pairId -> pairIconDetailFor = pairId },
            onFindAlternativePairIcon = { pairId ->
                pairIconDetailFor = pairId
                st.showFindIconsPicker.value = true
            },
            onFindAlternativePairTitle = { pairId ->
                pairTitleDetailFor = pairId
                st.showFindIconsPicker.value = true
            },
            iconRefreshTick = uiState.iconRefreshTick
        )
        return
    }

    if (ReportManageActionOverlays(
            st = st,
            uiState = uiState,
            runtime = runtime,
            iconGenEnabled = iconGenEnabled,
            onNavigateHome = onNavigateHome,
            onDeleteReport = onDeleteReport,
            onExport = onExport,
            onExportAll = onExportAll,
            onAdvancedParametersChange = onAdvancedParametersChange,
            onParametersIdsChange = onParametersIdsChange,
            onMarkParametersChanged = onMarkParametersChanged,
            onUpdatePrompt = onUpdatePrompt,
            onUpdateTitle = onUpdateTitle,
            onUpdateModelTitle = onUpdateModelTitle,
            onUpdatePairTitle = onUpdatePairTitle,
            onDeleteSecondaryWithRefresh = onDeleteSecondaryWithRefresh,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo,
            onNavigateToInternalPromptsByCategory = onNavigateToInternalPromptsByCategory
        )
    ) return

    val openViewReportFromManage = rememberOpenViewReportFromManage(st)

    val generationHandlers = buildGenerationPhaseHandlers(
        st = st,
        currentReportId = currentReportId,
        loadedReportTimestamp = loadedReportTimestamp,
        promptIconCallbacks = promptIconCallbacks,
        translationIconCallbacks = translationIconCallbacks,
        translationLifecycle = translationLifecycle,
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToAppLog = onNavigateToAppLog,
        onNavigateToTraceFile = onNavigateToTraceFile,
        onNavigateToTraceListFiltered = onNavigateToTraceListFiltered,
        onEditModels = onEditModels,
        onCopyReport = onCopyReport,
        onTogglePinReport = onTogglePinReport,
        onPrevReport = onPrevReport,
        onNextReport = onNextReport
    )
    if (!isGenerating) {
        com.ai.ui.report.start.ReportSelectModelsScreen(
            uiState = uiState,
            models = models,
            selectedParametersIds = selectedParametersIds,
            advancedParameters = advancedParameters,
            onDismiss = onDismiss,
            onAddFlock = { showSelectFlock = true },
            onAddAgent = { showSelectAgent = true },
            onAddSwarm = { showSelectSwarm = true },
            onAddModel = { showSelectProvider = true },
            onAddAllModels = { showSelectAllModels = true },
            onAddFromReport = { showSelectFromReport = true },
            onRemoveModel = { i -> models = models.filterIndexed { idx, _ -> idx != i } },
            onClearAllModels = { models = emptyList() },
            onAdvancedParams = { showAdvancedParameters = true },
            onParametersChange = { selectedParametersIds = it },
            onGenerate = { type -> if (models.isNotEmpty()) onGenerate(models, selectedParametersIds, type) },
            onUpdateModelList = { uiState.editModeReportId?.let { onUpdateModelList(it, models) } },
            onAttachKnowledgeBases = onAttachKnowledgeBases,
            onSystemPromptChange = onSystemPromptChange
        )
    } else {
        ReportRunScreen(
            uiState = uiState,
            isComplete = isComplete,
            reportsProgress = reportsProgress,
            reportsTotal = reportsTotal,
            reportsAgentResults = reportsAgentResults,
            currentReportId = currentReportId,
            iconGenEnabled = iconGenEnabled,
            showRegenerateConfirm = showRegenerateConfirm,
            models = models,
            st = st,
            generationHandlers = generationHandlers,
            secondaryCounts = secondaryCounts,
            costsFromDeletedItems = costsFromDeletedItems,
            secondaryRuns = secondaryRuns,
            translateRows = translateRows,
            secondaryTotals = secondaryTotals,
            translationRuns = translationRuns,
            translationRunSummaries = translationRunSummaries,
            fanOutSummaries = fanOutSummaries,
            loaded = runtime.loaded,
            reportIcon = reportIcon,
            reportIconError = reportIconError,
            reportIconCost = reportIconCost,
            reportIconModel = reportIconModel,
            languageIconCost = languageIconCost,
            languageDetectCost = languageDetectCost,
            languageName = languageName,
            agentIconRows = agentIconRows,
            agentModelTitles = agentModelTitles,
            agentRecordsByAgentId = agentRecordsByAgentId,
            infoEnabled = infoEnabled,
            infoState = infoState,
            infoMetaTotal = infoMetaTotal,
            hasPrevReport = hasPrevReport,
            hasNextReport = hasNextReport,
            onDismiss = onDismiss,
            onOpenViewReport = openViewReportFromManage,
            onRequestRegenerate = { showRegenerateConfirm = true },
            onRegenerateInfo = onRegenerateInfo,
            onRestartInfoErrors = onRestartInfoErrors,
            runningInfoJobs = runningInfoJobs,
            onDismissRegenerateConfirm = { showRegenerateConfirm = false },
            onRegenerate = onRegenerate,
            onChatWithReportPrompt = onChatWithReportPrompt,
            onRunTournament = onRunTournament
        )
    }
}
