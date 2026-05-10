package com.ai.ui.report

import android.app.Activity
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
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== rememberSaveable savers =====
//
// These are needed so the multi-step picker / scope / overlay state
// in ReportScreen survives the back-pop hop through the Help screen
// (and any other Compose Navigation destination that removes the
// AI_REPORTS Composable from the composition while it's painted).
// Without these the user lands back at the report root mid-flow.

private val InternalPromptSaver: Saver<InternalPrompt?, Any> = listSaver(
    save = { p ->
        if (p == null) emptyList()
        else listOf(p.id, p.name, p.reference, p.category, p.agent, p.text, p.title)
    },
    restore = { l ->
        if (l.isEmpty()) null
        else InternalPrompt(
            id = l[0] as String,
            name = l[1] as String,
            reference = l[2] as Boolean,
            category = l[3] as String,
            agent = l[4] as String,
            text = l[5] as String,
            title = l[6] as String
        )
    }
)

private val TargetLanguageSaver: Saver<TargetLanguage?, Any> = listSaver(
    save = { tl -> if (tl == null) emptyList() else listOf(tl.name, tl.native) },
    restore = { l -> if (l.isEmpty()) null else TargetLanguage(l[0] as String, l[1] as String) }
)

private val SecondaryScopeSaver: Saver<SecondaryScope, String> = Saver(
    save = { it.encode() },
    restore = { SecondaryScope.decodeOrAllReports(it) }
)

private val SecondaryLanguageScopeSaver: Saver<SecondaryLanguageScope, Any> = listSaver(
    save = { sls ->
        when (sls) {
            is SecondaryLanguageScope.AllPresent -> listOf("ALL")
            is SecondaryLanguageScope.Selected -> listOf("SEL") + sls.languages.toList()
        }
    },
    restore = { l ->
        when {
            l.isEmpty() -> SecondaryLanguageScope.AllPresent
            l[0] == "SEL" -> SecondaryLanguageScope.Selected(l.drop(1).filterIsInstance<String>().toSet())
            else -> SecondaryLanguageScope.AllPresent
        }
    }
)

private val AppServiceSaver: Saver<AppService?, String> = Saver(
    save = { it?.id ?: "" },
    restore = { s -> if (s.isBlank()) null else AppService.findById(s) }
)

// ===== Navigation Wrapper =====

@Composable
fun ReportsScreenNav(
    viewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToTraceListFiltered: (String, String) -> Unit = { _, _ -> },
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToInternalPromptEdit: (String) -> Unit = {},
    onContinueWithCurrent: (String, String) -> Unit = { _, _ -> },
    onContinueWithAgentPicker: (String, String) -> Unit = { _, _ -> },
    onContinueWithOnTheFly: (String, String) -> Unit = { _, _ -> },
    /** Start a fresh chat seeded with the report's prompt. Called from
     *  the result-screen title bar's 💬 icon. AppNavHost stashes the
     *  text into chatStarterText and routes to the agent picker. */
    onChatWithReportPrompt: (String) -> Unit = {},
    onNavigateToAgentsEdit: () -> Unit = {},
    onNavigateToFlocksEdit: () -> Unit = {},
    onNavigateToSwarmsEdit: () -> Unit = {},
    onNavigateToInternalPromptsByCategory: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val agentResults by reportViewModel.agentResults.collectAsState()
    val runningFanOutPairs by viewModel.runningFanOutPairs.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiSettings = uiState.aiSettings

    // If we re-enter the screen on a finished report whose in-memory agent results were
    // lost (Activity recreation, process death), rebuild them from ReportStorage so the
    // status icons reflect actual outcomes instead of spinning hourglasses forever.
    LaunchedEffect(uiState.currentReportId, agentResults.isEmpty(), uiState.genericReportsTotal) {
        val rid = uiState.currentReportId
        if (rid != null && agentResults.isEmpty() && uiState.genericReportsTotal > 0) {
            reportViewModel.hydrateAgentResultsFromStorage(context, rid)
        }
    }
    // A new report always starts with an empty model selection — no auto-fill from the
    // previous run. Save/load helpers are kept so an explicit "reuse previous" action can
    // be wired later, but they're intentionally not invoked here.
    val initialModels = emptyList<ReportModel>()

    val handleDismiss = {
        reportViewModel.dismissGenericReportsDialog()
        viewModel.clearExternalInstructions()
        onNavigateBack()
    }
    val handleNavigateHome = {
        reportViewModel.dismissGenericReportsDialog()
        viewModel.clearExternalInstructions()
        onNavigateHome()
    }
    val handleContinueInBackground = { reportViewModel.continueReportInBackground(); onNavigateHome() }

    ReportsScreen(
        uiState = uiState,
        reportsAgentResults = agentResults,
        runningFanOutPairs = runningFanOutPairs,
        initialModels = initialModels,
        onRunSecondary = { reportId, metaPrompt, picks, scopeChoice, languageScope ->
            reportViewModel.runMetaPrompt(scope, context, reportId, metaPrompt, picks, scopeChoice, languageScope)
        },
        onRunFanOut = { reportId, metaPrompt, scopeChoice ->
            reportViewModel.runFanOutPrompt(scope, context, reportId, metaPrompt, scopeChoice)
        },
        onRunFanIn = { reportId, metaPrompt, pick ->
            reportViewModel.runFanInPrompt(scope, context, reportId, metaPrompt, pick)
        },
        onRunModelFanIn = { reportId, metaPrompt, pick, activePid, activeMdl ->
            reportViewModel.runModelFanInPrompt(scope, context, reportId, metaPrompt, pick, activePid, activeMdl)
        },
        onCreateReportFromFanOut = { sourceRid, activePid, activeMdl ->
            scope.launch {
                val newId = reportViewModel.createReportFromFanOut(context, sourceRid, activePid, activeMdl)
                    ?: return@launch
                // Already on AI_REPORTS; restoreCompletedReport flips
                // UiState (showGenericReportsDialog + currentReportId)
                // so the result screen recomposes with the new report.
                reportViewModel.restoreCompletedReport(context, newId)
            }
        },
        onRunLocalRerank = { reportId, modelName ->
            reportViewModel.runLocalRerank(scope, context, reportId, modelName)
        },
        onRunRerank = { reportId, pick ->
            reportViewModel.runRerank(scope, context, reportId, pick)
        },
        onDeleteSecondary = { reportId, resultId ->
            reportViewModel.deleteSecondaryResult(context, reportId, resultId)
        },
        onBulkDeleteSecondaries = { reportId, ids, onDone ->
            reportViewModel.bulkDeleteSecondaryResults(context, reportId, ids, onDone)
        },
        onGenerate = { models, paramsIds, reportType ->
            val agentIds = models.filter { it.type == "agent" }.mapNotNull { it.agentId }.toSet()
            val swarmIds = models.filter { it.sourceType == "swarm" && it.type == "model" }.mapNotNull { it.sourceId }.toSet()
            val directIds = models.filter { it.sourceType == "model" }.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            viewModel.saveReportAgents(agentIds)
            viewModel.saveReportModels(models.map(::encodeSavedReportModelSelection).toSet())
            reportViewModel.generateGenericReports(
                scope = scope, context = context, selectedAgentIds = agentIds, selectedSwarmIds = swarmIds,
                directModelIds = directIds, parametersIds = paramsIds, reportType = reportType
            )
        },
        onStop = { reportViewModel.stopGenericReports(context, scope) },
        onDismiss = handleDismiss,
        onNavigateHome = handleNavigateHome,
        onContinueInBackground = handleContinueInBackground,
        advancedParameters = uiState.reportAdvancedParameters,
        onAdvancedParametersChange = { viewModel.setReportAdvancedParameters(it) },
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToTraceFile = onNavigateToTraceFile,
        onNavigateToTraceListFiltered = onNavigateToTraceListFiltered,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onRemoveAgent = { rid, aid -> reportViewModel.removeAgentFromReport(context, rid, aid) },
        onRegenerateAgent = { rid, aid -> reportViewModel.regenerateAgent(scope, context, rid, aid) },
        onClearExternalInstructions = viewModel::clearExternalInstructions,
        onEditModels = { rid -> scope.launch { reportViewModel.prepareEditModels(context, rid) } },
        onUpdateModelList = { rid, edited ->
            scope.launch { reportViewModel.stageModelListForRegenerate(context, rid, edited) }
        },
        onMarkParametersChanged = {
            viewModel.updateUiState { it.copy(hasPendingParametersChange = true) }
        },
        onRegenerate = { rid -> reportViewModel.regenerateReport(context, rid, scope) },
        onUpdatePrompt = { rid, prompt ->
            scope.launch { reportViewModel.updateReportPrompt(context, rid, prompt) }
        },
        onUpdateTitle = { rid, title ->
            scope.launch { reportViewModel.updateReportTitle(context, rid, title) }
        },
        onAttachKnowledgeBases = { ids -> viewModel.updateUiState { it.copy(attachedKnowledgeBaseIds = ids) } },
        onDeleteReport = { rid ->
            reportViewModel.deleteReport(context, rid)
            onNavigateBack()
        },
        onCopyReport = { rid -> reportViewModel.copyReport(context, rid, scope) },
        onTogglePinReport = { rid -> reportViewModel.toggleReportPinned(context, rid, scope) },
        onConsumePendingModels = { reportViewModel.clearPendingReportModels() },
        onExport = { rid, fmt, det, act, onProgress ->
            shareReportAsExport(context, rid, fmt, det, act, uiState.aiSettings, viewModel.repository, onProgress)
        },
        onExportAll = { rid, onProgress ->
            bulkExportAndShare(context, rid, onProgress)
        },
        translationRuns = reportViewModel.translationRuns.collectAsState().value.values.toList(),
        onStartTranslation = { sourceId, langName, langNative, prov, model ->
            reportViewModel.startTranslation(scope, context, sourceId, langName, langNative, prov, model)
        },
        onCancelTranslation = { runId -> reportViewModel.cancelTranslation(runId) },
        onConsumeTranslation = { runId -> reportViewModel.consumeTranslationRun(runId) },
        onContinueWithCurrent = onContinueWithCurrent,
        onContinueWithAgentPicker = onContinueWithAgentPicker,
        onContinueWithOnTheFly = onContinueWithOnTheFly,
        onChatWithReportPrompt = onChatWithReportPrompt,
        onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
        onNavigateToAgentsEdit = onNavigateToAgentsEdit,
        onNavigateToFlocksEdit = onNavigateToFlocksEdit,
        onNavigateToSwarmsEdit = onNavigateToSwarmsEdit,
        onNavigateToInternalPromptsByCategory = onNavigateToInternalPromptsByCategory,
        onResumeStaleFanOut = { rid, mp ->
            reportViewModel.resumeStaleFanOutPairs(scope, context, rid, mp)
        },
        onRecoverStaleSecondaries = { rid ->
            reportViewModel.recoverStaleSecondariesAsync(scope, context, rid)
        },
        onRestartFailedTranslations = { rid, runId ->
            reportViewModel.restartFailedTranslations(scope, context, rid, runId)
        },
        onStartMissingTranslations = { rid, runId ->
            reportViewModel.startMissingTranslations(scope, context, rid, runId)
        },
        onRestartFailedFanOut = { rid, mp ->
            reportViewModel.rerunFailedFanOutPairs(scope, context, rid, mp)
        },
        onRerunCompleteFanOut = { rid, mp ->
            reportViewModel.rerunCompleteFanOut(scope, context, rid, mp)
        },
        onDeleteFanOutModel = { rid, pid, prov, model ->
            reportViewModel.deleteFanOutModel(context, rid, pid, prov, model)
        }
    )
}

// ===== Helpers =====

internal data class FormattedPricing(val text: String, val isDefault: Boolean)

internal fun formatPricingPerMillion(context: android.content.Context, provider: AppService, model: String): FormattedPricing {
    val p = PricingCache.getPricing(context, provider, model)
    val fmtIn = if (p.promptPrice * 1_000_000 < 0.01 && p.promptPrice > 0) "<.01" else "%.2f".format(p.promptPrice * 1_000_000)
    val fmtOut = if (p.completionPrice * 1_000_000 < 0.01 && p.completionPrice > 0) "<.01" else "%.2f".format(p.completionPrice * 1_000_000)
    return FormattedPricing("$fmtIn / $fmtOut", p.source == "DEFAULT")
}

private fun loadSavedReportModels(viewModel: AppViewModel, aiSettings: Settings): List<ReportModel> {
    val agentIds = viewModel.loadReportAgents()
    val agentModels = agentIds.mapNotNull { id -> aiSettings.getAgentById(id)?.let { expandAgentToModel(it, aiSettings) } }
    val savedSelections = viewModel.loadReportModels()
    val savedModels = savedSelections.flatMap { decodeSavedReportModelSelection(it, aiSettings) }
    return deduplicateModels(agentModels + savedModels)
}

private fun encodeSavedReportModelSelection(model: ReportModel): String {
    return when (model.sourceType) {
        "swarm" -> model.sourceId?.let { "swarm-id:$it" } ?: "swarm:${model.provider.id}:${model.model}"
        else -> "swarm:${model.provider.id}:${model.model}"
    }
}

private fun decodeSavedReportModelSelection(selection: String, aiSettings: Settings): List<ReportModel> {
    return when {
        selection.startsWith("swarm-id:") -> {
            aiSettings.getSwarmById(selection.removePrefix("swarm-id:"))
                ?.let { expandSwarmToModels(it, aiSettings) }
                ?: emptyList()
        }
        selection.startsWith("swarm:") -> {
            val parts = selection.removePrefix("swarm:").split(":", limit = 2)
            val provider = AppService.findById(parts.getOrNull(0) ?: return emptyList()) ?: return emptyList()
            val model = parts.getOrNull(1) ?: return emptyList()
            listOf(toReportModel(provider, model))
        }
        else -> emptyList()
    }
}

// ===== Main Reports Screen =====

@Composable
fun ReportsScreen(
    uiState: UiState,
    reportsAgentResults: Map<String, AnalysisResponse>,
    runningFanOutPairs: Set<String> = emptySet(),
    initialModels: List<ReportModel> = emptyList(),
    onGenerate: (List<ReportModel>, List<String>, ReportType) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss,
    onContinueInBackground: () -> Unit = onNavigateHome,
    advancedParameters: AgentParameters? = null,
    onAdvancedParametersChange: (AgentParameters?) -> Unit = {},
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToTraceListFiltered: (String, String) -> Unit = { _, _ -> },
    onClearExternalInstructions: () -> Unit = {},
    onEditModels: (String) -> Unit = {},
    onUpdateModelList: (String, List<ReportModel>) -> Unit = { _, _ -> },
    onMarkParametersChanged: () -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onUpdatePrompt: (String, String) -> Unit = { _, _ -> },
    onUpdateTitle: (String, String) -> Unit = { _, _ -> },
    onAttachKnowledgeBases: (List<String>) -> Unit = {},
    onDeleteReport: (String) -> Unit = {},
    onCopyReport: (String) -> Unit = {},
    onTogglePinReport: (String) -> Unit = {},
    onConsumePendingModels: () -> Unit = {},
    onRunSecondary: (String, com.ai.model.InternalPrompt, List<Pair<AppService, String>>, com.ai.data.SecondaryScope, com.ai.data.SecondaryLanguageScope) -> Unit = { _, _, _, _, _ -> },
    onRunFanOut: (String, com.ai.model.InternalPrompt, com.ai.data.SecondaryScope) -> Unit = { _, _, _ -> },
    onRunFanIn: (String, com.ai.model.InternalPrompt, Pair<AppService, String>) -> Unit = { _, _, _ -> },
    /** Model-scoped fan-in run path. Args: reportId, prompt, picked
     *  model, active provider id (the L2 page's), active model name. */
    onRunModelFanIn: (String, com.ai.model.InternalPrompt, Pair<AppService, String>, String, String) -> Unit = { _, _, _, _, _ -> },
    /** Promote the L2 active model's fan-out conversation into a
     *  fresh AI Report. Args: source reportId, active provider id,
     *  active model. The new report's id is built inside the
     *  ReportViewModel; this lambda navigates after the save. */
    onCreateReportFromFanOut: (String, String, String) -> Unit = { _, _, _ -> },
    onRunLocalRerank: (String, String) -> Unit = { _, _ -> },
    onRunRerank: (String, Pair<AppService, String>) -> Unit = { _, _ -> },
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
    onExport: suspend (String, ReportExportFormat, ReportExportDetail, ReportExportAction, (Int, Int) -> Unit) -> Unit = { _, _, _, _, _ -> },
    onExportAll: suspend (String, (Int, Int) -> Unit) -> Unit = { _, _ -> },
    translationRuns: List<com.ai.viewmodel.ReportViewModel.TranslationRunState> = emptyList(),
    onStartTranslation: (String, String, String, AppService, String) -> Unit = { _, _, _, _, _ -> },
    onCancelTranslation: (String) -> Unit = {},
    onConsumeTranslation: (String) -> Unit = {},
    onContinueWithCurrent: (String, String) -> Unit = { _, _ -> },
    onContinueWithAgentPicker: (String, String) -> Unit = { _, _ -> },
    onContinueWithOnTheFly: (String, String) -> Unit = { _, _ -> },
    onChatWithReportPrompt: (String) -> Unit = {},
    onNavigateToInternalPromptEdit: (String) -> Unit = {},
    onResumeStaleFanOut: (String, com.ai.model.InternalPrompt) -> Unit = { _, _ -> },
    onRestartFailedFanOut: (String, com.ai.model.InternalPrompt) -> Unit = { _, _ -> },
    onRerunCompleteFanOut: (String, com.ai.model.InternalPrompt) -> Unit = { _, _ -> },
    onDeleteFanOutModel: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** Mark every blank-content / no-error / no-duration secondary
     *  on the report as errored. Fired once per report open from a
     *  LaunchedEffect — animated-hourglass icons should only spin
     *  while something's actually running, but on app restart no
     *  in-memory job survives, so any "still in progress" row is
     *  an orphan placeholder. */
    onRecoverStaleSecondaries: (String) -> Unit = {},
    /** Re-run every errored row in the named translation run.
     *  Wired to ReportViewModel.restartFailedTranslations. */
    onRestartFailedTranslations: (String, String) -> Unit = { _, _ -> },
    /** Run every expected translation item not yet covered by the
     *  named run's persisted rows. Wired to
     *  ReportViewModel.startMissingTranslations. */
    onStartMissingTranslations: (String, String) -> Unit = { _, _ -> },
    /** Deep-link callbacks fired by the full-screen +Agent / +Flock /
     *  +Swarm / Meta / Fan out pickers' "Edit X" buttons. AppNavHost
     *  wires each to the matching Settings sub-screen route. */
    onNavigateToAgentsEdit: () -> Unit = {},
    onNavigateToFlocksEdit: () -> Unit = {},
    onNavigateToSwarmsEdit: () -> Unit = {},
    onNavigateToInternalPromptsByCategory: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val aiSettings = uiState.aiSettings
    val scope = rememberCoroutineScope()

    val reportsTotal = uiState.genericReportsTotal
    val reportsProgress = uiState.genericReportsProgress
    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0
    val currentReportId = uiState.currentReportId

    // Per-kind row counts + the actual meta-run list for the result
    // page. The list drives the per-run rows shown below the agent
    // rows. Polled while a meta batch is in flight so newly added
    // rows / status changes surface promptly without the user
    // bouncing in/out of the screen.
    var secondaryCounts by remember { mutableStateOf(SecondaryResultStorage.Counts(0, 0, 0, 0)) }
    var secondaryRuns by remember { mutableStateOf(emptyList<com.ai.data.SecondaryResult>()) }
    var translationRunSummaries by remember { mutableStateOf(emptyList<TranslationRunSummary>()) }
    var fanOutSummaries by remember { mutableStateOf(emptyList<FanOutRunSummary>()) }
    var secondaryTotals by remember { mutableStateOf(SecondaryTotals.ZERO) }
    // Carried straight from Report.costsFromDeletedItems on disk —
    // bumped by every user-initiated delete on this report (rows,
    // fan-out pairs, secondaries, translations). Surfaces as its own
    // line above the Total footer when non-zero.
    var costsFromDeletedItems by remember { mutableStateOf(0.0) }
    // Mirrored from Report.icon / Report.iconErrorMessage on disk so
    // the inline 'icon' row can render ⏳ / emoji / ❌ without the
    // composable having to subscribe to the whole Report object.
    // Re-fetched on every uiState.iconRefreshTick bump (fired by
    // ReportViewModel.kickOffIconGeneration when it writes either
    // field) so a mid-flight resolution flips the row in real time.
    var reportIcon by remember { mutableStateOf<String?>(null) }
    var reportIconError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentReportId, uiState.iconRefreshTick) {
        val rid = currentReportId
        if (rid == null) {
            reportIcon = null
            reportIconError = null
        } else {
            val r = withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, rid) }
            reportIcon = r?.icon
            reportIconError = r?.iconErrorMessage
        }
    }
    // Bumped from every overlay-driven delete so the parent screen's
    // counts / row list re-read from disk on the way back. Without
    // this the LaunchedEffect below has no reason to refire (the user
    // didn't change report id, completion state, or batch count) and
    // the deleted row keeps showing in the inline meta-runs list and
    // the View counters above it.
    var secondaryRefreshTick by remember { mutableStateOf(0) }
    // One-shot recovery sweep per report open. Marks every stuck
    // placeholder secondary (blank content + null errorMessage + null
    // durationMs that no in-memory job claims) as "Interrupted by app
    // restart" so the inline rows / fan out summary / cost summary all
    // show ❌ instead of an animated hourglass spinning forever. The
    // bump on secondaryRefreshTick forces the polling LaunchedEffect
    // below to immediately re-read disk and pick up the marked rows.
    LaunchedEffect(currentReportId) {
        val rid = currentReportId ?: return@LaunchedEffect
        onRecoverStaleSecondaries(rid)
        kotlinx.coroutines.delay(150)
        secondaryRefreshTick++
    }
    val onDeleteSecondaryWithRefresh: (String, String) -> Unit = { rid, sid ->
        onDeleteSecondary(rid, sid)
        secondaryRefreshTick++
    }
    // Recompute when any translation run finishes — the persisted
    // summary appears in translationRunSummaries on the next reload,
    // which is what flips the live row to the static one.
    val anyTranslationFinished = translationRuns.any { it.isFinished }
    val finishedSignature = translationRuns.filter { it.isFinished }.map { it.runId }.toSet()
    LaunchedEffect(currentReportId, isComplete, uiState.activeSecondaryBatches, finishedSignature, secondaryRefreshTick) {
        val rid = currentReportId ?: run {
            secondaryCounts = SecondaryResultStorage.Counts(0, 0, 0, 0)
            secondaryRuns = emptyList()
            translationRunSummaries = emptyList()
            fanOutSummaries = emptyList()
            secondaryTotals = SecondaryTotals.ZERO
            costsFromDeletedItems = 0.0
            return@LaunchedEffect
        }
        costsFromDeletedItems = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getReport(context, rid)?.costsFromDeletedItems ?: 0.0
        }
        suspend fun reload() {
            withContext(Dispatchers.IO) {
                val all = SecondaryResultStorage.listForReport(context, rid)
                // Fan-out pair rows (N×(M-1) per-(answerer, source)
                // responses) collapse into a single summary row per
                // fan out prompt, mirroring how Translation collapses N
                // per-call rows. Fan_in combine-reports rows do
                // NOT fold — each run is a standalone meta call so it
                // keeps its own row in secondaryRuns alongside Compare /
                // Summarize / etc.
                secondaryRuns = all
                    .filter { it.kind != SecondaryKind.TRANSLATE }
                    .filter { it.fanOutSourceAgentId == null }
                    .sortedByDescending { it.timestamp }
                translationRunSummaries = buildTranslationRunSummaries(
                    all.filter { it.kind == SecondaryKind.TRANSLATE }
                )
                fanOutSummaries = buildFanOutSummaries(
                    all.filter { it.fanOutSourceAgentId != null }
                )
                // Derive counts from `all` instead of calling
                // SecondaryResultStorage.countForReport — that function
                // does its own listFiles + per-file Gson parse, so on
                // the 500ms batching tick we'd be re-parsing every
                // file twice (once via the cached listForReport above,
                // once for counts). The counts are pure projections
                // of the same data.
                secondaryCounts = SecondaryResultStorage.Counts(
                    rerank = all.count { it.kind == SecondaryKind.RERANK },
                    meta = all.count { it.kind == SecondaryKind.META },
                    moderation = all.count { it.kind == SecondaryKind.MODERATION },
                    translate = all.count { it.kind == SecondaryKind.TRANSLATE }
                )
                // Totals span every persisted secondary including
                // TRANSLATE rows — those translation calls show up
                // in the cost table as separate rows and should sum
                // into the bottom-line total here too.
                secondaryTotals = SecondaryTotals(
                    inputTokens = all.sumOf { it.tokenUsage?.inputTokens ?: 0 },
                    outputTokens = all.sumOf { it.tokenUsage?.outputTokens ?: 0 },
                    inputCost = all.sumOf { it.inputCost ?: 0.0 },
                    outputCost = all.sumOf { it.outputCost ?: 0.0 }
                )
            }
        }
        reload()
        if (uiState.activeSecondaryBatches > 0) {
            // While any batch is in flight repoll every 500 ms so
            // brand-new ⏳ rows appear and finished rows flip to ✅/❌
            // in place. The loop self-cancels when the LaunchedEffect
            // re-keys (batch count back to 0).
            while (true) {
                delay(500)
                reload()
            }
        }
    }

    // Once any individual run flips to finished, fold its rows into
    // the persisted summary and consume just that run so its live row
    // gives way to the static one. Other in-flight runs keep going.
    LaunchedEffect(finishedSignature) {
        if (finishedSignature.isNotEmpty()) {
            // Allow the LaunchedEffect above (keyed on the same
            // signature) to reload and append the persisted secondary
            // first; 200 ms is long enough for the IO read to publish
            // the new translationRunSummaries before we drop the live row.
            // Wrap the consume in NonCancellable so navigating away
            // (or another translation finishing in the same window
            // and re-keying this effect) can't cancel the consume
            // mid-call — that previously left the live row stranded
            // alongside the persisted summary, producing a duplicate.
            delay(200)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                finishedSignature.forEach { onConsumeTranslation(it) }
            }
        }
    }
    // Overlay state for any flow that can hand off to a Compose
    // Navigation destination (trace detail, model info, …) needs to
    // be saveable: the AI_REPORTS Composable is removed from
    // composition while the user is on the destination, so plain
    // remember{} state would reset on back-pop and the user would
    // land back at the report root instead of the overlay they came
    // from. rememberSaveable persists through the back-stack.
    var openMetaResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var openTranslationRunId by rememberSaveable { mutableStateOf<String?>(null) }

    var showViewer by rememberSaveable { mutableStateOf(false) }
    var selectedAgentForViewer by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerSection by rememberSaveable { mutableStateOf<String?>(null) }
    // Per-row click → focused single-model viewer. Distinct from the
    // multi-agent ReportsViewerScreen reached via View → Results.
    var singleResultAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showEditPrompt by rememberSaveable { mutableStateOf(false) }
    var showEditTitle by rememberSaveable { mutableStateOf(false) }
    var showEditParameters by rememberSaveable { mutableStateOf(false) }
    var showAdvancedParameters by rememberSaveable { mutableStateOf(false) }
    // Translate flow state.
    var showTranslateLanguagePicker by rememberSaveable { mutableStateOf(false) }
    var showTranslateModelPicker by rememberSaveable(stateSaver = TargetLanguageSaver) { mutableStateOf<TargetLanguage?>(null) }
    var models by remember { mutableStateOf(initialModels) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var showInfoPicker by rememberSaveable { mutableStateOf(false) }
    // Action-row pickers — hoisted up here so they render as proper
    // full-screen overlays. When they lived inside GenerationPhase the
    // parent TitleBar + ActionRow had already painted above them, so
    // the picker visibly stacked on top of a half-drawn screen.
    var showViewPicker by rememberSaveable { mutableStateOf(false) }
    var showEditPicker by rememberSaveable { mutableStateOf(false) }
    var showMetaPicker by rememberSaveable { mutableStateOf(false) }
    var showFanOutPicker by rememberSaveable { mutableStateOf(false) }
    var showRerankPicker by rememberSaveable { mutableStateOf(false) }

    // One-shot consumer: when ReportViewModel (Edit models / Regenerate flows) drops a
    // pre-built model list into uiState.pendingReportModels, copy it into the local
    // selection state and clear the signal so we don't keep re-applying it.
    LaunchedEffect(uiState.pendingReportModels) {
        if (uiState.pendingReportModels.isNotEmpty()) {
            models = deduplicateModels(uiState.pendingReportModels)
            onConsumePendingModels()
        }
    }
    var showSelectFlock by rememberSaveable { mutableStateOf(false) }
    var showSelectAgent by rememberSaveable { mutableStateOf(false) }
    var showSelectSwarm by rememberSaveable { mutableStateOf(false) }
    var showSelectProvider by rememberSaveable { mutableStateOf(false) }
    var pendingProvider by rememberSaveable(stateSaver = AppServiceSaver) { mutableStateOf<AppService?>(null) }
    var showSelectAllModels by rememberSaveable { mutableStateOf(false) }
    var showSelectFromReport by rememberSaveable { mutableStateOf(false) }
    var selectedParametersIds by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var externalAutoGenerated by rememberSaveable { mutableStateOf(false) }
    // Meta prompt state — replaces the old kind-specific state. The
    // user picks one Meta prompt at a time from the new Meta card or
    // the unified Meta hub; type=chat goes through the scope screen
    // first, type=rerank/moderation skips it.
    var secondaryPickerMetaPrompt by rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    var secondaryScopeMetaPrompt by rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    var pendingSecondaryScope by rememberSaveable(stateSaver = SecondaryScopeSaver) { mutableStateOf<SecondaryScope>(SecondaryScope.AllReports) }
    var pendingLanguageScope by rememberSaveable(stateSaver = SecondaryLanguageScopeSaver) { mutableStateOf<SecondaryLanguageScope>(SecondaryLanguageScope.AllPresent) }
    // Fan-out confirm dialog: shown after the scope screen, before
    // kicking off N answerers × S sources calls. The user can still
    // cancel from here if the count looks too high.
    var fanOutConfirmMetaPrompt by rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    // Fan_in run model picker. Triggered from the fan out detail
    // screen's "Combine reports and all fan out responses" button.
    var fanInPickerPrompt by rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    // First step of the fan_in flow: pick which fan_in prompt
    // to run. Once chosen we hand off to fanInPickerPrompt above.
    var showFanInPromptPicker by rememberSaveable { mutableStateOf(false) }
    // Model-scoped fan-in (categories initiator / requester /
    // model) flow state. Triggered from L2's "Create a model fan
    // in report" expandable. The active provider/model identify the
    // L2 page that was active when the user tapped a sub-button —
    // they're stored here so the picker → model picker chain can
    // reach all the way to ReportViewModel.runModelFanInPrompt
    // without re-deriving them.
    var modelFanInActivePid by rememberSaveable { mutableStateOf<String?>(null) }
    var modelFanInActiveMdl by rememberSaveable { mutableStateOf<String?>(null) }
    var modelFanInPickerPrompt by rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    // Unified Meta screen overlay reached from the Actions card.
    var showMetaScreen by rememberSaveable { mutableStateOf(false) }
    // Per-name (or per-legacy-kind) list overlay reached from the View
    // card buttons. The kind is still useful for routing through
    // SecondaryResultsScreen (rendering picks the chat-type META path
    // vs the structured RERANK / MODERATION / TRANSLATE branches), so
    // we carry both. rememberSaveable so navigating away (e.g. tapping
    // a 🐞 → trace detail) and popping back lands on the same list,
    // not the top of the report screen.
    var listKind by rememberSaveable { mutableStateOf<SecondaryKind?>(null) }
    var listFilterByName by rememberSaveable { mutableStateOf<String?>(null) }

    // Screen keepalive during generation
    DisposableEffect(isGenerating, isComplete) {
        if (isGenerating && !isComplete) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // External model resolution. Tracks unresolved entries so a
    // share-target that mistypes a name (or omits the provider slash
    // on a model spec) doesn't silently drop the entry — a Toast
    // below names the unresolved entries so the user can fix the
    // intent and retry.
    data class ExternalResolution(val resolved: List<ReportModel>, val unresolved: List<String>)
    val externalRes = remember(uiState.externalAgentNames, uiState.externalFlockNames, uiState.externalSwarmNames, uiState.externalModelSpecs) {
        val result = mutableListOf<ReportModel>()
        val missing = mutableListOf<String>()
        uiState.externalAgentNames.forEach { name ->
            val a = aiSettings.agents.find { it.name.equals(name, ignoreCase = true) }
            val rm = a?.let { expandAgentToModel(it, aiSettings) }
            if (rm != null) result.add(rm) else missing.add("agent: $name")
        }
        uiState.externalFlockNames.forEach { name ->
            val f = aiSettings.flocks.find { it.name.equals(name, ignoreCase = true) }
            if (f != null) result.addAll(expandFlockToModels(f, aiSettings)) else missing.add("flock: $name")
        }
        uiState.externalSwarmNames.forEach { name ->
            val s = aiSettings.swarms.find { it.name.equals(name, ignoreCase = true) }
            if (s != null) result.addAll(expandSwarmToModels(s, aiSettings)) else missing.add("swarm: $name")
        }
        uiState.externalModelSpecs.forEach { spec ->
            val parts = spec.split("/", limit = 2)
            val provider = AppService.findById(parts.getOrNull(0) ?: "") ?: AppService.entries.find { it.id.equals(parts.getOrNull(0), ignoreCase = true) }
            val model = parts.getOrNull(1)
            if (provider != null && model != null) result.add(toReportModel(provider, model))
            else missing.add("model: $spec")
        }
        ExternalResolution(deduplicateModels(result), missing)
    }
    val externalModels = externalRes.resolved
    LaunchedEffect(externalRes.unresolved) {
        if (externalRes.unresolved.isNotEmpty()) {
            android.widget.Toast.makeText(
                context,
                "Unresolved external entries: ${externalRes.unresolved.joinToString(", ")}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // Auto-generate for external models
    LaunchedEffect(externalModels, uiState.externalReportType) {
        if (externalModels.isNotEmpty() && !externalAutoGenerated && !isGenerating && uiState.externalReportType != null && !uiState.externalSelect) {
            externalAutoGenerated = true
            val updatedModels = deduplicateModels(models + externalModels)
            models = updatedModels
            val type = if (uiState.externalReportType.equals("table", ignoreCase = true)) ReportType.TABLE else ReportType.CLASSIC
            onGenerate(updatedModels, selectedParametersIds, type)
        }
    }

    // Apply external models to selection
    LaunchedEffect(externalModels) {
        if (externalModels.isNotEmpty() && !externalAutoGenerated) {
            models = deduplicateModels(models + externalModels)
        }
    }

    // Auto email on completion
    LaunchedEffect(isComplete, currentReportId) {
        if (isComplete && currentReportId != null) {
            val email = uiState.externalEmail
            if (email != null && email.isNotBlank()) {
                emailReportAsHtml(context, currentReportId, email)
                if (uiState.externalReturn) activity?.finish()
            }
            val next = uiState.externalNextAction
            if (next != null) {
                delay(500)
                when (next.lowercase()) {
                    "view" -> showViewer = true
                    "share" -> shareReportAsHtml(context, currentReportId)
                    "browser" -> openReportInChrome(context, currentReportId)
                    "email" -> if (uiState.generalSettings.defaultEmail.isNotBlank()) emailReportAsHtml(context, currentReportId, uiState.generalSettings.defaultEmail)
                }
                if (uiState.externalReturn) { delay(1000); activity?.finish() }
            }
            if (
                uiState.externalEmail != null ||
                uiState.externalNextAction != null ||
                uiState.externalReturn ||
                uiState.externalReportType != null ||
                uiState.externalAgentNames.isNotEmpty() ||
                uiState.externalFlockNames.isNotEmpty() ||
                uiState.externalSwarmNames.isNotEmpty() ||
                uiState.externalModelSpecs.isNotEmpty()
            ) {
                onClearExternalInstructions()
            }
        }
    }

    // Full-screen overlays
    if (showViewer && currentReportId != null) {
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showViewer = false; viewerSection = null }) {
            ReportsViewerScreen(reportId = currentReportId, initialSelectedAgentId = selectedAgentForViewer, initialSection = viewerSection, onDismiss = { showViewer = false; viewerSection = null }, onNavigateHome = onNavigateHome, onNavigateToTraceFile = onNavigateToTraceFile)
        }
        return
    }
    val singleAgentId = singleResultAgentId
    if (singleAgentId != null && currentReportId != null) {
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { singleResultAgentId = null }) {
            ReportSingleResultScreen(
                reportId = currentReportId,
                agentId = singleAgentId,
                onBack = { singleResultAgentId = null },
                onNavigateHome = onNavigateHome,
                onNavigateToModelInfo = onNavigateToModelInfo,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onRemoveAgent = { rid, aid ->
                    onRemoveAgent(rid, aid)
                    // Refire the totals LaunchedEffect so the
                    // freshly-bumped Report.costsFromDeletedItems
                    // appears on the result page without waiting
                    // for some other state change.
                    secondaryRefreshTick++
                },
                onRegenerateAgent = onRegenerateAgent,
                onContinueWithCurrent = onContinueWithCurrent,
                onContinueWithAgentPicker = onContinueWithAgentPicker,
                onContinueWithOnTheFly = onContinueWithOnTheFly
            )
        }
        return
    }
    if (showAdvancedParameters) {
        ReportAdvancedParametersScreen(currentParameters = advancedParameters, onApply = { onAdvancedParametersChange(it); showAdvancedParameters = false }, onBack = { showAdvancedParameters = false })
        return
    }

    // Selection overlay dialogs
    if (showSelectFlock) {
        ReportSelectFlockScreen(
            aiSettings = aiSettings,
            onSelectFlock = {
                models = deduplicateModels(models + expandFlockToModels(it, aiSettings))
                showSelectFlock = false
            },
            onBack = { showSelectFlock = false },
            onEditFlocks = onNavigateToFlocksEdit
        )
        return
    }
    if (showSelectAgent) {
        ReportSelectAgentScreen(
            aiSettings = aiSettings,
            onSelectAgent = {
                expandAgentToModel(it, aiSettings)?.let { m -> models = deduplicateModels(models + m) }
                showSelectAgent = false
            },
            onBack = { showSelectAgent = false },
            onEditAgents = onNavigateToAgentsEdit
        )
        return
    }
    if (showSelectSwarm) {
        ReportSelectSwarmScreen(
            aiSettings = aiSettings,
            onSelectSwarm = {
                models = deduplicateModels(models + expandSwarmToModels(it, aiSettings))
                showSelectSwarm = false
            },
            onBack = { showSelectSwarm = false },
            onEditSwarms = onNavigateToSwarmsEdit
        )
        return
    }
    if (showSelectProvider) { ReportSelectProviderDialog(aiSettings, onSelectProvider = { pendingProvider = it; showSelectProvider = false }, onDismiss = { showSelectProvider = false }); return }
    if (pendingProvider != null) { ReportSelectModelDialog(pendingProvider!!, aiSettings, onSelectModel = { models = deduplicateModels(models + toReportModel(pendingProvider!!, it)); pendingProvider = null }, onDismiss = { pendingProvider = null }); return }
    if (showSelectAllModels) {
        val already = remember(models) { models.map { it.provider to it.model }.toSet() }
        ReportSelectModelsScreen(
            aiSettings = aiSettings,
            alreadyAdded = already,
            onConfirm = { (prov, m) ->
                models = deduplicateModels(models + toReportModel(prov, m))
                showSelectAllModels = false
            },
            onBack = { showSelectAllModels = false },
            onNavigateHome = onNavigateHome
        )
        return
    }
    if (showSelectFromReport) {
        ReportSelectFromReportScreen(
            onConfirm = { report ->
                // Convert each per-agent row from the chosen report into
                // a ReportModel — try the saved-agent path first (so
                // provenance like "via swarm X" is preserved), fall
                // through to a direct (provider, model) entry when the
                // agent has been deleted since the report ran.
                val copied = report.agents.mapNotNull { ra ->
                    val savedAgent = aiSettings.getAgentById(ra.agentId)
                    if (savedAgent != null) expandAgentToModel(savedAgent, aiSettings)
                    else AppService.findById(ra.provider)?.let { prov -> toReportModel(prov, ra.model) }
                }
                models = deduplicateModels(models + copied)
                showSelectFromReport = false
            },
            onBack = { showSelectFromReport = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Scope screen — shown before the picker for chat-type Meta
    // prompts. Lets the user pick the input set (all model results /
    // top-N from a rerank / a manual subset) and, when translation
    // rows exist, which target languages to fan out to.
    val scopeMetaPrompt = secondaryScopeMetaPrompt
    if (scopeMetaPrompt != null && currentReportId != null) {
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
                val rr = all.filter { it.kind == com.ai.data.SecondaryKind.RERANK }
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
        SecondaryScopeScreen(
            metaPrompt = scopeMetaPrompt,
            agents = sd.agents,
            reranks = sd.reranks,
            languages = sd.languages,
            totalReports = sd.totalReports,
            onContinue = { chosenScope, chosenLangScope ->
                pendingSecondaryScope = chosenScope
                pendingLanguageScope = chosenLangScope
                secondaryScopeMetaPrompt = null
                if (scopeMetaPrompt.category == "fan_out") {
                    // No model picker for fan out — answerers are always
                    // every successful report-model. Jump straight to
                    // the call-count confirm dialog.
                    fanOutConfirmMetaPrompt = scopeMetaPrompt
                } else {
                    secondaryPickerMetaPrompt = scopeMetaPrompt
                }
            },
            onBack = { secondaryScopeMetaPrompt = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Fan-out confirm screen: shown after the scope screen, before
    // the runner kicks off. Re-derives the (answerers, sources) set
    // from the chosen scope so the user can see exactly how many
    // calls they're authorising — full-screen so the per-pair preview
    // has room to breathe.
    val fanOutMp = fanOutConfirmMetaPrompt
    if (fanOutMp != null && currentReportId != null) {
        val rid = currentReportId
        data class FanOutCounts(
            val answerers: Int,
            val pairs: Int,
            val answererNames: List<String>
        )
        val countsState = produceState<FanOutCounts?>(initialValue = null, rid, pendingSecondaryScope) {
            value = withContext(Dispatchers.IO) {
                val report = com.ai.data.ReportStorage.getReport(context, rid) ?: return@withContext null
                val successful = report.agents.filter {
                    it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                }
                val sources = when (val sc = pendingSecondaryScope) {
                    com.ai.data.SecondaryScope.AllReports -> successful
                    is com.ai.data.SecondaryScope.TopRanked -> {
                        val rerank = com.ai.data.SecondaryResultStorage.get(context, rid, sc.rerankResultId)
                        val ids = com.ai.data.extractTopRankedIds(rerank?.content, sc.count)
                        if (ids.isNullOrEmpty()) successful
                        else ids.mapNotNull { successful.getOrNull(it - 1) }
                    }
                    is com.ai.data.SecondaryScope.Manual -> successful.filter { it.agentId in sc.agentIds }
                }
                val pairs = successful.sumOf { ans -> sources.count { it.agentId != ans.agentId } }
                fun label(a: com.ai.data.ReportAgent): String =
                    a.agentName.takeIf { it.isNotBlank() } ?: "${a.provider} · ${a.model}"
                FanOutCounts(
                    answerers = successful.size,
                    pairs = pairs,
                    answererNames = successful.map(::label)
                )
            }
        }
        val counts = countsState.value
        val scopeLabel = when (val sc = pendingSecondaryScope) {
            com.ai.data.SecondaryScope.AllReports -> "All reports"
            is com.ai.data.SecondaryScope.TopRanked -> "Top ${sc.count} ranked"
            is com.ai.data.SecondaryScope.Manual -> "Manual selection (${sc.agentIds.size})"
        }
        BackHandler { fanOutConfirmMetaPrompt = null }
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(
                helpTopic = "report_fan_out_confirm",
                title = "Run ${fanOutMp.name}",
                onBackClick = { fanOutConfirmMetaPrompt = null }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Running ${fanOutMp.name} will call every model once for every other model's response. Each call uses the fan-out prompt with @RESPONSE@ filled in.",
                    fontSize = 13.sp, color = AppColors.TextSecondary
                )
                if (fanOutMp.text.isNotBlank()) {
                    Text("Fan-out prompt", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            fanOutMp.text, fontSize = 11.sp, color = AppColors.TextDim,
                            modifier = Modifier.padding(12.dp), maxLines = 12, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (counts == null) {
                            Text("Loading…", fontSize = 13.sp, color = AppColors.TextTertiary)
                        } else {
                            val gridText = if (counts.answerers > 0 && counts.pairs % counts.answerers == 0) {
                                val perReport = counts.pairs / counts.answerers
                                "${counts.answerers} report${if (counts.answerers == 1) "" else "s"} × $perReport response${if (perReport == 1) "" else "s"} = ${counts.pairs} call${if (counts.pairs == 1) "" else "s"}"
                            } else {
                                "${counts.pairs} call${if (counts.pairs == 1) "" else "s"}"
                            }
                            Text(
                                gridText, fontSize = 15.sp, color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text("Scope", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                                Text(scopeLabel, fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
                if (counts != null && counts.answererNames.isNotEmpty()) {
                    Text("Models in this fan-out (${counts.answerers})", fontSize = 13.sp,
                        color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            counts.answererNames.forEach { name ->
                                Text(name, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { fanOutConfirmMetaPrompt = null }, modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Cancel", maxLines = 1, softWrap = false) }
                Button(
                    onClick = {
                        val mp = fanOutConfirmMetaPrompt ?: return@Button
                        val sc = pendingSecondaryScope
                        fanOutConfirmMetaPrompt = null
                        pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                        pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
                        onRunFanOut(rid, mp, sc)
                    },
                    enabled = counts != null && counts.pairs > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                ) { Text("Run", maxLines = 1, softWrap = false) }
            }
        }
        return
    }

    // Meta prompt model picker. Reuses the same multi-select screen
    // with the Meta prompt's name as the label.
    val pickerMetaPrompt = secondaryPickerMetaPrompt
    if (pickerMetaPrompt != null && currentReportId != null) {
        val rid = currentReportId
        ReportSelectModelsScreen(
            aiSettings = aiSettings,
            // Single-pick: tap fires the meta run for one model and pops
            // back. Users wanting two runs just open the picker twice.
            titleText = "${pickerMetaPrompt.name} — pick model",
            onConfirm = { pick ->
                onRunSecondary(rid, pickerMetaPrompt, listOf(pick), pendingSecondaryScope, pendingLanguageScope)
                secondaryPickerMetaPrompt = null
                pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
            },
            onBack = { secondaryPickerMetaPrompt = null },
            onNavigateHome = onNavigateHome
        )
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
            onConfirm = { pick ->
                onRunFanIn(rid, fanInPicker, pick)
                fanInPickerPrompt = null
            },
            onBack = { fanInPickerPrompt = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Model-scoped fan-in flow (single fan-in-model category).
    // Triggered from L2's "New Fan In" button. Two-step picker
    // chain mirroring the legacy fan-in path — first pick a prompt
    // from the fan-in-model bucket, then pick the model the run
    // will fire on. After model confirm we call the model-scoped
    // runner and pop back so the L2 page's polling tick surfaces
    // the placeholder row at the top.
    val modelFanInPicker = modelFanInPickerPrompt
    if (modelFanInPicker == null && modelFanInActivePid != null && modelFanInActiveMdl != null && currentReportId != null) {
        val list = aiSettings.internalPrompts.filter { it.category == "fan-in-model" }
        CompositionLocalProvider(LocalNavigateToCurrentReport provides {
            modelFanInActivePid = null
            modelFanInActiveMdl = null
        }) {
            ReportSelectInternalPromptScreen(
                titleText = "Pick a Fan In, model prompt",
                category = "fan-in-model",
                prompts = list,
                onSelectPrompt = { modelFanInPickerPrompt = it },
                onBack = {
                    modelFanInActivePid = null
                    modelFanInActiveMdl = null
                },
                onEditPrompts = {
                    modelFanInActivePid = null
                    modelFanInActiveMdl = null
                    onNavigateToInternalPromptsByCategory("fan-in-model")
                }
            )
        }
        return
    }
    if (modelFanInPicker != null && currentReportId != null
        && modelFanInActivePid != null && modelFanInActiveMdl != null
    ) {
        val rid = currentReportId
        val activePid = modelFanInActivePid!!
        val activeMdl = modelFanInActiveMdl!!
        CompositionLocalProvider(LocalNavigateToCurrentReport provides {
            modelFanInActivePid = null
            modelFanInActiveMdl = null
            modelFanInPickerPrompt = null
        }) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "${modelFanInPicker.name} — pick model",
                modelTypeFilter = null,
                onConfirm = { pick ->
                    onRunModelFanIn(rid, modelFanInPicker, pick, activePid, activeMdl)
                    modelFanInActivePid = null
                    modelFanInActiveMdl = null
                    modelFanInPickerPrompt = null
                },
                onBack = {
                    modelFanInActivePid = null
                    modelFanInActiveMdl = null
                    modelFanInPickerPrompt = null
                },
                onNavigateHome = onNavigateHome
            )
        }
        return
    }

    // Translate overlays. Order: language picker → model picker →
    // progress screen. The first two close the picker once a choice is
    // made; the progress screen sticks around until the run finishes
    // and the user taps "To translated report" (or Cancel).
    if (showTranslateLanguagePicker) {
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showTranslateLanguagePicker = false }) {
            LanguageSelectionScreen(
                onConfirm = { lang ->
                    showTranslateLanguagePicker = false
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
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showTranslateModelPicker = null }) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "Pick translation model",
                onConfirm = { (prov, m) ->
                    onStartTranslation(currentReportId, pickingTranslateModelFor.name, pickingTranslateModelFor.native, prov, m)
                    showTranslateModelPicker = null
                },
                onBack = { showTranslateModelPicker = null },
                onNavigateHome = onNavigateHome
            )
        }
        return
    }

    if (showRerankPicker && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showRerankPicker = false }) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "Pick rerank model",
                onConfirm = { pick ->
                    showRerankPicker = false
                    onRunRerank(rid, pick)
                },
                onBack = { showRerankPicker = false },
                onNavigateHome = onNavigateHome,
                modelTypeFilter = com.ai.data.ModelType.RERANK
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
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { openMetaResultId = null }) {
            SecondaryResultDetailScreen(
                result = openMetaResult,
                onDelete = {
                    onDeleteSecondaryWithRefresh(rid, openMetaResult.id)
                    openMetaResultId = null
                },
                onBack = { openMetaResultId = null },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToModelInfo = onNavigateToModelInfo
            )
        }
        return
    }

    val openRunId = openTranslationRunId
    if (openRunId != null && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { openTranslationRunId = null }) {
            TranslationRunDetailScreen(
                reportId = rid,
                runId = openRunId,
                onDelete = { resultId -> onDeleteSecondaryWithRefresh(rid, resultId) },
                onBack = { openTranslationRunId = null },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToTraceList = { onNavigateToTraceListFiltered(rid, "Translation") },
                onNavigateToModelInfo = onNavigateToModelInfo,
                onRestartFailed = { srcRid, runId ->
                    onRestartFailedTranslations(srcRid, runId)
                    secondaryRefreshTick++
                },
                onStartMissing = { srcRid, runId ->
                    onStartMissingTranslations(srcRid, runId)
                    secondaryRefreshTick++
                },
                // Live state for the in-flight run — lets the detail
                // screen show queued / running items in addition to
                // whatever's already persisted as a SecondaryResult.
                // Null after the run finishes; the screen falls back
                // to its persisted-only path.
                liveRun = translationRuns.firstOrNull { it.runId == openRunId && !it.isFinished },
                // Delete = cancel the Job + drop persisted rows + consume
                // the live map entry so nothing about this run lingers.
                onCancelRun = { id -> onCancelTranslation(id) },
                onConsumeRun = { id -> onConsumeTranslation(id) }
            )
        }
        return
    }

    val openListKind = listKind
    if (openListKind != null && currentReportId != null) {
        val rid = currentReportId
        val fanInList = aiSettings.internalPrompts.filter { it.category == "fan_in" }
        // Per-model fan-in list driving the L2 "New Fan In" button.
        // Same internalPrompts source, filtered to the single
        // fan-in-model category.
        val fanInModelList = aiSettings.internalPrompts.filter { it.category == "fan-in-model" }
        val fanOutPrompt = if (openListKind == SecondaryKind.META && listFilterByName != null) {
            aiSettings.internalPrompts.firstOrNull {
                it.category == "fan_out" && it.name == listFilterByName
            }
        } else null
        // Fan-in picker — full-screen replacement of the
        // previous AlertDialog. Rendered as an overlay on top of the
        // secondary list via the early-return pattern.
        if (showFanInPromptPicker && fanInList.isNotEmpty()) {
            CompositionLocalProvider(LocalNavigateToCurrentReport provides { showFanInPromptPicker = false; listKind = null; listFilterByName = null }) {
                ReportSelectInternalPromptScreen(
                    titleText = "Run an fan-in prompt",
                    category = "fan_in",
                    prompts = fanInList,
                    onSelectPrompt = {
                        showFanInPromptPicker = false
                        fanInPickerPrompt = it
                    },
                    onBack = { showFanInPromptPicker = false },
                    onEditPrompts = {
                        showFanInPromptPicker = false
                        onNavigateToInternalPromptsByCategory("fan_in")
                    }
                )
            }
            return
        }
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { listKind = null; listFilterByName = null }) {
        SecondaryResultsScreen(
            reportId = rid,
            kind = openListKind,
            nameFilter = listFilterByName,
            isBatching = uiState.activeSecondaryBatches > 0,
            runningFanOutPairs = runningFanOutPairs,
            fanInPrompts = fanInList,
            fanInModelPrompts = fanInModelList,
            fanOutPrompt = fanOutPrompt,
            onRunFanIn = if (fanInList.isNotEmpty()) {
                {
                    if (fanInList.size == 1) fanInPickerPrompt = fanInList.first()
                    else showFanInPromptPicker = true
                }
            } else null,
            onRunModelFanIn = { activePid, activeMdl ->
                modelFanInActivePid = activePid
                modelFanInActiveMdl = activeMdl
                // Auto-pick when exactly one prompt exists (skips the
                // picker step, mirroring the legacy fan_in
                // single-prompt behaviour).
                if (fanInModelList.size == 1) modelFanInPickerPrompt = fanInModelList.first()
            },
            onCreateReportFromFanOut = { activePid, activeMdl ->
                // Drop the L1/L2 overlay state before the parent flips
                // currentReportId — otherwise the new report would
                // briefly render its (empty) META L2 view instead of
                // the result page.
                listKind = null; listFilterByName = null
                onCreateReportFromFanOut(rid, activePid, activeMdl)
            },
            onDelete = { resultId -> onDeleteSecondaryWithRefresh(rid, resultId) },
            onBulkDelete = { ids ->
                // Off-thread sweep — N can be hundreds (≈ N(N-1) for
                // a Fan out with N agents). Routes through the parent
                // callback so the actual launch lives on the report
                // VM's viewModelScope; the previous screen-scoped
                // forEach abandoned partial deletes when the user
                // navigated away mid-sweep.
                onBulkDeleteSecondaries(rid, ids) { secondaryRefreshTick++ }
            },
            onBack = { listKind = null; listFilterByName = null },
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo,
            onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
            onResumeStaleFanOut = { mp -> onResumeStaleFanOut(rid, mp) },
            onRestartFailedFanOut = { mp -> onRestartFailedFanOut(rid, mp) },
            onRerunCompleteFanOut = { mp ->
                onRerunCompleteFanOut(rid, mp)
                secondaryRefreshTick++
            },
            onDeleteFanOutModel = { mpid, prov, model ->
                onDeleteFanOutModel(rid, mpid, prov, model)
                secondaryRefreshTick++
            }
        )
        }
        return
    }

    // Helper used by the Meta card and the Meta hub's "Add" list.
    // Every Meta-category prompt now goes through the scope screen.
    // fan_out prompts have their own button + popup and are routed
    // via launchFanOutPrompt below.
    val launchMetaPrompt: (com.ai.model.InternalPrompt) -> Unit = { mp ->
        secondaryScopeMetaPrompt = mp
    }
    // fan_out prompts always run the scope screen → confirm dialog
    // → runFanOutPrompt path. The scope screen hides the language
    // picker and the post-scope routing on line 645 jumps straight to
    // the call-count confirm dialog when category == "fan_out".
    val launchFanOutPrompt: (com.ai.model.InternalPrompt) -> Unit = { mp ->
        secondaryScopeMetaPrompt = mp
    }

    if (showMetaScreen && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showMetaScreen = false }) {
            ReportMetaScreen(
                reportId = rid,
                isRunning = uiState.activeSecondaryBatches > 0,
                metaPrompts = aiSettings.internalPrompts.filter { it.category.equals("meta", ignoreCase = true) },
                onLaunchMetaPrompt = launchMetaPrompt,
                onDelete = { resultId -> onDeleteSecondaryWithRefresh(rid, resultId) },
                onBack = { showMetaScreen = false },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToModelInfo = onNavigateToModelInfo
            )
        }
        return
    }

    if (showInfoPicker) {
        val unique = remember(models) { models.distinctBy { it.deduplicationKey } }
        AlertDialog(
            onDismissRequest = { showInfoPicker = false },
            title = { Text("Model info") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    unique.forEach { rm ->
                        Text(
                            "${rm.provider.id} — ${rm.model}",
                            fontSize = 14.sp, color = Color.White,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showInfoPicker = false
                                onNavigateToModelInfo(rm.provider, rm.model)
                            }.padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInfoPicker = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }

    // Share dialog
    if (showDeleteConfirm && currentReportId != null) {
        // Capture the report id at dialog-open time so a background
        // mutation of currentReportId between Delete tap and lambda
        // execution can't end up deleting the wrong report.
        val ridAtOpen = currentReportId
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete report?") },
            text = { Text("This permanently removes the saved report from disk.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteReport(ridAtOpen)
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    if (showExport && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showExport = false }) {
            ReportExportScreen(
                onBack = { showExport = false },
                onNavigateHome = onNavigateHome,
                onExport = { fmt, det, act, onProgress -> onExport(rid, fmt, det, act, onProgress) },
                onExportAll = { onProgress -> onExportAll(rid, onProgress) }
            )
        }
        return
    }

    if (showEditParameters) {
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showEditParameters = false }) {
            ReportAdvancedParametersScreen(
                currentParameters = uiState.reportAdvancedParameters,
                onApply = {
                    onAdvancedParametersChange(it)
                    onMarkParametersChanged()
                    showEditParameters = false
                },
                onBack = { showEditParameters = false }
            )
        }
        return
    }

    if (showEditPrompt && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showEditPrompt = false }) {
            ReportEditPromptScreen(
                initialPrompt = uiState.genericPromptText,
                onBack = { showEditPrompt = false },
                onNavigateHome = onNavigateHome,
                onUpdate = { newPrompt ->
                    showEditPrompt = false
                    onUpdatePrompt(rid, newPrompt)
                }
            )
        }
        return
    }
    if (showEditTitle && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showEditTitle = false }) {
            ReportEditTitleScreen(
                initialTitle = uiState.genericPromptTitle,
                onBack = { showEditTitle = false },
                onNavigateHome = onNavigateHome,
                onUpdate = { newTitle ->
                    showEditTitle = false
                    onUpdateTitle(rid, newTitle)
                }
            )
        }
        return
    }

    // Action-row picker overlays (Meta / Fan out / View / Edit). These
    // render at ReportsScreen scope — not inside GenerationPhase —
    // so the parent TitleBar and the action row don't paint above
    // them when the user opens a picker.
    if (showMetaPicker) {
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showMetaPicker = false }) {
            ReportSelectInternalPromptScreen(
                titleText = "Run a Meta prompt",
                category = "meta",
                prompts = aiSettings.internalPrompts.filter { it.category.equals("meta", ignoreCase = true) },
                onSelectPrompt = {
                    showMetaPicker = false
                    launchMetaPrompt(it)
                },
                onBack = { showMetaPicker = false },
                onEditPrompts = {
                    showMetaPicker = false
                    onNavigateToInternalPromptsByCategory("meta")
                }
            )
        }
        return
    }
    if (showFanOutPicker) {
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showFanOutPicker = false }) {
            ReportSelectInternalPromptScreen(
                titleText = "Run a Fan out prompt",
                category = "fan_out",
                prompts = aiSettings.internalPrompts.filter { it.category == "fan_out" },
                onSelectPrompt = {
                    showFanOutPicker = false
                    launchFanOutPrompt(it)
                },
                onBack = { showFanOutPicker = false },
                onEditPrompts = {
                    showFanOutPicker = false
                    onNavigateToInternalPromptsByCategory("fan_out")
                }
            )
        }
        return
    }
    if (showViewPicker) {
        val successful = reportsAgentResults.count { it.value.isSuccess }
        val totalAgents = reportsAgentResults.size
        val secondaryCost = secondaryTotals.inputCost + secondaryTotals.outputCost
        val secondaryTokens = secondaryTotals.inputTokens + secondaryTotals.outputTokens
        val promptPreview = uiState.genericPromptText.lineSequence()
            .firstOrNull { it.isNotBlank() }?.take(80) ?: "(empty)"
        val viewBuckets = buildViewBuckets(secondaryRuns)
        val options = buildList {
            add(ReportActionOption(
                label = "Reports",
                detail = if (totalAgents == 0) "No agent results yet"
                else "$successful of $totalAgents agents succeeded",
                onClick = {
                    showViewPicker = false
                    selectedAgentForViewer = null; viewerSection = null; showViewer = true
                }
            ))
            add(ReportActionOption(
                label = "Prompt",
                detail = promptPreview,
                onClick = {
                    showViewPicker = false
                    selectedAgentForViewer = null; viewerSection = "prompt"; showViewer = true
                }
            ))
            add(ReportActionOption(
                label = "Costs",
                detail = if (secondaryTokens > 0)
                    "Tokens & cost — secondary so far: %.4f USD".format(secondaryCost)
                else "Tokens & cost breakdown",
                onClick = {
                    showViewPicker = false
                    selectedAgentForViewer = null; viewerSection = "costs"; showViewer = true
                }
            ))
            viewBuckets.forEach { (name, kind, count) ->
                add(ReportActionOption(
                    label = name,
                    detail = "$count run${if (count == 1) "" else "s"}",
                    secondary = com.ai.data.legacyKindDisplayName(kind),
                    onClick = {
                        showViewPicker = false
                        listKind = kind; listFilterByName = name
                    }
                ))
            }
        }
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showViewPicker = false }) {
            ReportActionPickerScreen(
                titleText = "View",
                helpTopic = "report_view_picker",
                options = options,
                onBack = { showViewPicker = false }
            )
        }
        return
    }
    if (showEditPicker && currentReportId != null) {
        val rid = currentReportId
        val titlePreview = uiState.genericPromptTitle.ifBlank { "(untitled)" }
        val promptPreview = uiState.genericPromptText.lineSequence()
            .firstOrNull { it.isNotBlank() }?.take(80) ?: "(empty)"
        val modelCount = reportsAgentResults.size
        val options = listOf(
            ReportActionOption(
                label = "Prompt",
                detail = promptPreview,
                onClick = { showEditPicker = false; showEditPrompt = true }
            ),
            ReportActionOption(
                label = "Title",
                detail = titlePreview,
                onClick = { showEditPicker = false; showEditTitle = true }
            ),
            ReportActionOption(
                label = "Models",
                detail = if (modelCount == 0) "Adjust the model list"
                else "$modelCount model${if (modelCount == 1) "" else "s"} on this report",
                onClick = { showEditPicker = false; onEditModels(rid) }
            ),
            ReportActionOption(
                label = "Parameters",
                detail = "Temperature, max tokens, top_p, stop sequences",
                onClick = { showEditPicker = false; showEditParameters = true }
            )
        )
        CompositionLocalProvider(LocalNavigateToCurrentReport provides { showEditPicker = false }) {
            ReportActionPickerScreen(
                titleText = "Edit",
                helpTopic = "report_edit_picker",
                options = options,
                onBack = { showEditPicker = false }
            )
        }
        return
    }

    // Main UI
    val foldSubject = com.ai.ui.shared.LocalSubjectToTitleBar.current
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        // Static page title in the menu bar by default; the dynamic
        // prompt title surfaces as a green sub-header inside the body.
        // When subject-to-title-bar is on AND we're in the results
        // phase AND a prompt title exists, the prompt title takes the
        // bar slot and the green line drops out below.
        val barTitle = run {
            val promptTitle = uiState.genericPromptTitle
            val base = when {
                !isGenerating -> "AI Report — Models"
                foldSubject && promptTitle.isNotBlank() -> promptTitle
                else -> "AI Report"
            }
            // Prepend the resolved icon emoji once kickOffIconGeneration
            // finishes — visible on the result-screen title bar so the
            // user sees the same icon they'll see in the hub / history
            // list. Falls through cleanly when the icon hasn't resolved
            // yet (or icon-gen wasn't configured).
            if (!reportIcon.isNullOrEmpty()) "$reportIcon $base" else base
        }
        TitleBar(
            helpTopic = "report_result_generation",
            title = barTitle,
            onBackClick = onDismiss,
            onReload = if (isGenerating && currentReportId != null && isComplete) {
                { showRegenerateConfirm = true }
            } else null,
            onTrace = if (isGenerating && currentReportId != null) {
                { onNavigateToTrace(currentReportId) }
            } else null,
            onDelete = if (isGenerating && currentReportId != null) {
                { showDeleteConfirm = true }
            } else null,
            onInfo = if (isGenerating && models.isNotEmpty()) {
                { showInfoPicker = true }
            } else null,
            // 💬: start a fresh chat seeded with the report's prompt.
            // Wired only on the results page (isGenerating == true) and
            // only when the prompt text is non-blank.
            onChat = if (isGenerating && uiState.genericPromptText.isNotBlank()) {
                { onChatWithReportPrompt(uiState.genericPromptText) }
            } else null
        )
        if (showRegenerateConfirm && currentReportId != null) {
            val rid = currentReportId
            val agentCount = models.size
            com.ai.ui.shared.ReloadConfirmationDialog(
                target = "",
                title = "Regenerate every agent?",
                message = "Re-fire the API call for all $agentCount model${if (agentCount == 1) "" else "s"} on this report. The existing responses, costs, and traces are replaced. Secondary results (Meta, Fan out, Translate) are kept.",
                confirmLabel = "Regenerate",
                onConfirm = {
                    showRegenerateConfirm = false
                    onRegenerate(rid)
                },
                onDismiss = { showRegenerateConfirm = false }
            )
        }
        // Dynamic per-report sub-header — renders below the static
        // page title for the result phase so the user can see this
        // particular report's title without it eating the menu bar.
        // Subject-to-title-bar mode hoists this title into the bar
        // above instead (see the TitleBar `title =` block).
        if (isGenerating && !foldSubject) {
            val reportTitle = uiState.genericPromptTitle
            if (reportTitle.isNotBlank()) {
                Text(
                    text = reportTitle,
                    fontSize = 18.sp,
                    color = AppColors.Green,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (!isGenerating) {
            // Selection phase
            val editModeRid = uiState.editModeReportId
            SelectionPhase(
                models = models,
                aiSettings = aiSettings,
                selectedParametersIds = selectedParametersIds,
                advancedParameters = advancedParameters,
                editModeReportId = editModeRid,
                onAddFlock = { showSelectFlock = true },
                onAddAgent = { showSelectAgent = true },
                onAddSwarm = { showSelectSwarm = true },
                onAddModel = { showSelectProvider = true },
                onAddAllModels = { showSelectAllModels = true },
                onAddFromReport = { showSelectFromReport = true },
                onRemoveModel = { i -> models = models.filterIndexed { idx, _ -> idx != i } },
                onClearAll = { models = emptyList() },
                onAdvancedParams = { showAdvancedParameters = true },
                onParametersChange = { selectedParametersIds = it },
                onGenerate = { type -> if (models.isNotEmpty()) onGenerate(models, selectedParametersIds, type) },
                onUpdateModelList = { if (editModeRid != null) onUpdateModelList(editModeRid, models) },
                attachedKnowledgeBaseIds = uiState.attachedKnowledgeBaseIds,
                onAttachKnowledgeBases = onAttachKnowledgeBases
            )
        } else {
            // Generation phase
            GenerationPhase(
                uiState = uiState,
                isComplete = isComplete,
                reportsProgress = reportsProgress,
                reportsTotal = reportsTotal,
                reportsAgentResults = reportsAgentResults,
                currentReportId = currentReportId,
                onStop = onStop,
                onContinueInBackground = onContinueInBackground,
                onViewAgent = { agentId -> singleResultAgentId = agentId },
                onShare = { showExport = true },
                onTrace = { currentReportId?.let(onNavigateToTrace) },
                onDelete = { showDeleteConfirm = true },
                onCopy = { currentReportId?.let(onCopyReport) },
                onTogglePin = { currentReportId?.let(onTogglePinReport) },
                onTranslate = { showTranslateLanguagePicker = true },
                onOpenViewPicker = { showViewPicker = true },
                onOpenEditPicker = { showEditPicker = true },
                onOpenMetaPicker = { showMetaPicker = true },
                onOpenFanOutPicker = { showFanOutPicker = true },
                onOpenRerankPicker = { showRerankPicker = true },
                secondaryCounts = secondaryCounts,
                costsFromDeletedItems = costsFromDeletedItems,
                secondaryRuns = secondaryRuns,
                secondaryTotals = secondaryTotals,
                translationRuns = translationRuns,
                onCancelTranslation = onCancelTranslation,
                translationRunSummaries = translationRunSummaries,
                fanOutSummaries = fanOutSummaries,
                onViewSecondaryName = { name, kind -> listKind = kind; listFilterByName = name },
                onOpenSecondaryRun = { id -> openMetaResultId = id },
                onOpenTranslationRun = { runId -> openTranslationRunId = runId },
                onOpenMeta = { showMetaScreen = true },
                metaPrompts = aiSettings.internalPrompts.filter { it.category.equals("meta", ignoreCase = true) },
                fanOutPrompts = aiSettings.internalPrompts.filter { it.category == "fan_out" },
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToTraceListFiltered = onNavigateToTraceListFiltered,
                reportIcon = reportIcon,
                reportIconError = reportIconError
            )
        }
    }
}

// ===== Selection Phase =====

@Composable
private fun ColumnScope.SelectionPhase(
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
    onUpdateModelList: () -> Unit,
    attachedKnowledgeBaseIds: List<String> = emptyList(),
    onAttachKnowledgeBases: (List<String>) -> Unit = {}
) {
    val context = LocalContext.current

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

    // Selected models list
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (models.isEmpty()) {
            Text("No models selected", color = AppColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            models.forEachIndexed { index, entry ->
                val pricing = formatPricingPerMillion(context, entry.provider, entry.model)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.model, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            com.ai.ui.shared.VisionBadge(aiSettings.isVisionCapable(entry.provider, entry.model))
                            com.ai.ui.shared.WebSearchBadge(aiSettings.isWebSearchCapable(entry.provider, entry.model))
                            com.ai.ui.shared.ReasoningBadge(aiSettings.isReasoningCapable(entry.provider, entry.model))
                        }
                        Text("${entry.provider.id}${if (entry.sourceName.isNotBlank()) " via ${entry.sourceName}" else ""}", fontSize = 11.sp, color = AppColors.TextTertiary)
                    }
                    Text(pricing.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (pricing.isDefault) AppColors.SurfaceDark else AppColors.Red,
                        modifier = if (pricing.isDefault) Modifier.background(AppColors.TextDim, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp, vertical = 1.dp) else Modifier)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("\u2715", color = AppColors.Red, fontSize = 14.sp, modifier = Modifier.clickable { onRemoveModel(index) })
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Knowledge attach \u2014 multi-select over saved KBs, snapshot lives
    // in UiState.attachedKnowledgeBaseIds and gets copied onto the
    // new Report when generation kicks off. analyzeWithAgent reads
    // it via the per-call Report at dispatch time.
    val ctx = LocalContext.current
    var showKbDialog by remember { mutableStateOf(false) }
    val kbRefreshTick = com.ai.ui.shared.resumeRefreshTick()
    val allKbs = remember(kbRefreshTick) { com.ai.data.KnowledgeStore.listKnowledgeBases(ctx) }
    if (allKbs.isNotEmpty()) {
        OutlinedButton(
            onClick = { showKbDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) {
            val n = attachedKnowledgeBaseIds.size
            val label = if (n == 0) "\ud83d\udcda Attach knowledge" else "\ud83d\udcda Attached: $n"
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

    // Bottom buttons
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (models.isNotEmpty()) OutlinedButton(onClick = onClearAll, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Clear", maxLines = 1, softWrap = false) }
        OutlinedButton(onClick = onAdvancedParams, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) {
            Text(if (advancedParameters != null) "Params \u2713" else "Params", fontSize = 13.sp, maxLines = 1, softWrap = false)
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Bottom action — Generate for a fresh report, or Update model list when the user
    // entered via Edit / Models on a finished report. The Update path stages the new
    // list and pops back without running; the user re-runs from Actions / Regenerate.
    if (editModeReportId != null) {
        Button(
            onClick = onUpdateModelList,
            enabled = models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update model list", maxLines = 1, softWrap = false) }
    } else {
        Button(
            onClick = { onGenerate(ReportType.CLASSIC) },
            enabled = models.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text("Generate", maxLines = 1, softWrap = false) }
    }
}

// ===== Generation Phase =====

@Composable
private fun ColumnScope.GenerationPhase(
    uiState: UiState,
    isComplete: Boolean,
    reportsProgress: Int,
    reportsTotal: Int,
    reportsAgentResults: Map<String, AnalysisResponse>,
    currentReportId: String?,
    onStop: () -> Unit,
    onContinueInBackground: () -> Unit,
    onViewAgent: (String) -> Unit,
    onShare: () -> Unit,
    onTrace: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onTranslate: () -> Unit = {},
    /** Open the action-row pickers. The picker bodies live at
     *  ReportsScreen scope so they render as proper full-screen
     *  overlays — these callbacks just flip the parent's visibility
     *  state. */
    onOpenViewPicker: () -> Unit = {},
    onOpenEditPicker: () -> Unit = {},
    onOpenMetaPicker: () -> Unit = {},
    onOpenFanOutPicker: () -> Unit = {},
    onOpenRerankPicker: () -> Unit = {},
    secondaryCounts: SecondaryResultStorage.Counts = SecondaryResultStorage.Counts(0, 0, 0, 0),
    /** Sum of costs the user dropped from this report via Delete actions
     *  on agents / secondaries / fan-out pairs / translations. Surfaces
     *  as a dedicated row above the Total footer when non-zero. */
    costsFromDeletedItems: Double = 0.0,
    secondaryRuns: List<com.ai.data.SecondaryResult> = emptyList(),
    secondaryTotals: SecondaryTotals = SecondaryTotals.ZERO,
    translationRuns: List<com.ai.viewmodel.ReportViewModel.TranslationRunState> = emptyList(),
    onCancelTranslation: (String) -> Unit = {},
    translationRunSummaries: List<TranslationRunSummary> = emptyList(),
    fanOutSummaries: List<FanOutRunSummary> = emptyList(),
    onViewSecondaryName: (String, SecondaryKind) -> Unit = { _, _ -> },
    onOpenSecondaryRun: (String) -> Unit = {},
    onOpenTranslationRun: (String) -> Unit = {},
    onOpenMeta: () -> Unit = {},
    metaPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    fanOutPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    /** Open a single trace file in the trace detail view. Wired to the
     *  per-row 🐞 icons next to agent / secondary rows. */
    onNavigateToTraceFile: (String) -> Unit = {},
    /** Open the trace list filtered to (reportId, category). Wired to
     *  the per-row 🐞 on translation runs which collapse multiple
     *  per-call traces into a single category-scoped list. */
    onNavigateToTraceListFiltered: (String, String) -> Unit = { _, _ -> },
    /** Report.icon mirrored from disk, populated by the parent's
     *  iconRefreshTick-keyed effect. Null while the icon-gen call is
     *  in flight or when the prompt isn't configured. */
    reportIcon: String? = null,
    /** Report.iconErrorMessage mirrored from disk. Set when icon-gen
     *  errored; the inline 'icon' row flips to ❌ when non-null. */
    reportIconError: String? = null
) {
    val context = LocalContext.current
    val aiSettings = uiState.aiSettings

    fun resolveModelForResult(agentId: String, result: AnalysisResponse): String {
        return aiSettings.getAgentById(agentId)?.let { aiSettings.getEffectiveModelForAgent(it) }
            ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringAfter(':')
            ?: result.service.defaultModel
    }

    // ===== Action row (lives at the top of the page) =====
    // While the run is in flight: STOP / Background. Once complete:
    // a single FlowRow of CompactButtons. View / Edit each fan out
    // into a popup picker; the rest fire directly. The legacy
    // View / Edit / Actions sectioned cards have collapsed into
    // this row.
    @OptIn(ExperimentalLayoutApi::class)
    @Composable fun ActionRow(content: @Composable FlowRowScope.() -> Unit) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content
        )
    }
    if (!isComplete) {
        ActionRow {
            CompactButton(onClick = onStop, color = AppColors.Red, text = "STOP")
            CompactButton(onClick = onContinueInBackground, color = AppColors.SurfaceDark, text = "Background")
        }
    } else {
        // Read the persisted pinned flag so the button toggles between
        // "Pin" and "Unpin" rather than always saying the same thing.
        // Re-keyed when the user taps the button (currentReportId
        // doesn't change but the report file does).
        var pinTick by remember(currentReportId) { mutableStateOf(0) }
        val isPinned by produceState(initialValue = false, currentReportId, pinTick) {
            value = currentReportId?.let { rid ->
                withContext(Dispatchers.IO) { ReportStorage.getReport(context, rid)?.pinned == true }
            } ?: false
        }
        // The View / Edit / Meta / Fan out pickers live at ReportsScreen
        // scope so they can render as proper full-screen overlays.
        // GenerationPhase only owns the trigger callbacks now.
        ActionRow {
            CompactButton(onClick = onOpenViewPicker, color = AppColors.Purple, text = "View")
            CompactButton(onClick = onOpenEditPicker, color = AppColors.Indigo, text = "Edit")
            // Regenerate moved to the title-bar 🔄 icon (with a
            // confirm dialog naming the row count) so the action
            // row stays focused on per-report navigation choices.
            CompactButton(onClick = onShare, color = AppColors.Blue, text = "Export")
            CompactButton(onClick = onCopy, color = AppColors.Purple, text = "Copy")
            CompactButton(
                onClick = { onTogglePin(); pinTick++ },
                color = AppColors.Orange,
                text = if (isPinned) "Unpin" else "Pin"
            )
            CompactButton(onClick = onTranslate, color = AppColors.Indigo, text = "Translate")
            CompactButton(onClick = onOpenRerankPicker, color = AppColors.Orange, text = "Rerank")
            CompactButton(
                onClick = onOpenMetaPicker,
                color = AppColors.Orange,
                text = "Meta",
                enabled = metaPrompts.isNotEmpty()
            )
            CompactButton(
                onClick = onOpenFanOutPicker,
                color = AppColors.Orange,
                text = "Fan out",
                enabled = fanOutPrompts.isNotEmpty()
            )
        }

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
        liveTranslationCost + costsFromDeletedItems

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
    val showSecondaryRuns = isComplete && secondaryRuns.isNotEmpty()
    val showFanOutSummaries = fanOutSummaries.isNotEmpty()
    val showLiveTranslations = isComplete && activeTranslationRuns.isNotEmpty()
    val showTranslationSummaries = isComplete && translationRunSummaries.isNotEmpty()
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
    val newRowTrigger = "${secondaryRuns.size}|${fanOutSummaries.size}|${activeTranslationRuns.size}|${translationRunSummaries.size}"
    LaunchedEffect(currentReportId, newRowTrigger) {
        if (currentReportId == null) return@LaunchedEffect
        resultListState.scrollToItem(0)
    }
    LazyColumn(state = resultListState, modifier = Modifier.weight(1f)) {
        // Meta runs \u2014 one row per individual rerank / summarize /
        // compare / moderation result on this report, sharing the
        // agent rows' layout (status icon + label + cost). Status
        // mirrors the agent rows: \u23F3 while the placeholder is still
        // empty, \u2705 on success, \u274C on error. Tapping opens that run's
        // detail screen. TRANSLATE rows are excluded \u2014 they're cost
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
                    when {
                        run.errorMessage != null -> Text("\u274C", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        running -> {
                            val transition = rememberInfiniteTransition(label = "meta-run-${run.id}")
                            val angle by transition.animateFloat(
                                initialValue = 0f, targetValue = 360f,
                                animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                                label = "meta-run-rot-${run.id}"
                            )
                            Text("\u23F3", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
                        }
                        else -> Text("\u2705", fontSize = 16.sp, modifier = Modifier.width(24.dp))
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
                    val langSuffix = run.targetLanguage?.let { " \u00B7 $it" } ?: ""
                    Column(modifier = Modifier.weight(1f)) {
                        // Fan-in rows label by the PROMPT used (its
                        // description / title) rather than the model
                        // \u2014 the user picks the prompt to characterise
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
                    when {
                        run.pendingCount > 0 -> Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            AnimatedHourglass(fontSize = 16.sp)
                        }
                        run.errorCount > 0 -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        else -> Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
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
            items(translationRunSummaries, key = { "trs-${it.runId}" }) { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenTranslationRun(run.runId) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusEmoji = if (run.errorCount > 0) "❌" else "✅"
                    Text(statusEmoji, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    RowTypeCell("translate")
                    val info = listOfNotNull(run.targetLanguage, run.model).joinToString(" · ")
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            info.ifBlank { "Translate" },
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
                it.category == "internal" && it.name == "icon"
            }
            val iconAgent = iconPrompt?.let { p ->
                aiSettings.agents.firstOrNull { it.name == p.agent }
            }
            if (iconPrompt != null && iconAgent != null) {
                item(key = "row-icon") {
                    val running = reportIcon == null && reportIconError == null
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                            Text(
                                com.ai.ui.shared.modelLabel(iconAgent.provider.id, iconAgent.model),
                                fontSize = 13.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
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
                // Status icon - newly-staged rows get a NEW badge (no result yet because
                // the user hasn't re-run); pending hourglass spins; success/failure static.
                if (row.isNew) {
                    Text(text = "🆕", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                } else if (result == null) {
                    val transition = rememberInfiniteTransition(label = "hourglass")
                    val angle by transition.animateFloat(
                        initialValue = 0f, targetValue = 360f,
                        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                        label = "hourglass-rotation"
                    )
                    Text(text = "\u23F3", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
                } else {
                    Text(
                        text = if (result.isSuccess) "\u2705" else "\u274C",
                        fontSize = 16.sp, modifier = Modifier.width(24.dp)
                    )
                }
                RowTypeCell("report")
                Column(modifier = Modifier.weight(1f)) {
                    Text(com.ai.ui.shared.modelLabel(row.providerDisplay, displayName),
                        fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (result?.tokenUsage != null) {
                    val cost = PricingCache.computeCost(result.tokenUsage, PricingCache.getPricing(context, result.service, resolveModelForResult(agentId, result)))
                    Text(formatCents(cost), fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
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

/** Group non-translate Meta rows by user-given prompt name (or, for
 *  legacy rows that pre-date the Meta-prompt CRUD, by the kind's
 *  display label). Each bucket carries the row count plus the kind
 *  the routing should use to open [SecondaryResultsScreen]. */
private fun buildViewBuckets(
    rows: List<com.ai.data.SecondaryResult>
): List<Triple<String, SecondaryKind, Int>> {
    if (rows.isEmpty()) return emptyList()
    data class Bucket(var kind: SecondaryKind, var count: Int)
    val byName = LinkedHashMap<String, Bucket>()
    rows.forEach { r ->
        if (r.kind == SecondaryKind.TRANSLATE) return@forEach
        val name = r.metaPromptName?.takeIf { it.isNotBlank() } ?: com.ai.data.legacyKindDisplayName(r.kind)
        val existing = byName[name]
        if (existing == null) byName[name] = Bucket(r.kind, 1)
        else existing.count += 1
    }
    return byName.entries.map { Triple(it.key, it.value.kind, it.value.count) }
}

/** Aggregated tokens + cost across every persisted secondary result on
 *  a report (rerank / summarize / compare / moderation / translate),
 *  loaded alongside the per-row list and summed once so the totals
 *  banner doesn't have to scan the list on every recomposition. */
private data class SecondaryTotals(
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
private data class TranslationRunSummary(
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
private data class FanOutRunSummary(
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
    val totalCost: Double,
    /** Latest timestamp across the run; used to sort against the other
     *  meta rows. */
    val timestamp: Long
)

/** Group fan-out pair rows by Meta-prompt name. Fan_in rows are
 *  excluded by the caller — each is its own row in secondaryRuns since
 *  there's nothing to fold (one click → one row). Legacy rows missing
 *  `metaPromptName` fall back to `metaPromptId` to keep them grouped. */
private fun buildFanOutSummaries(rows: List<com.ai.data.SecondaryResult>): List<FanOutRunSummary> {
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
                totalCost = items.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) },
                timestamp = items.maxOf { it.timestamp }
            )
        }
        .sortedByDescending { it.timestamp }
}

private fun buildTranslationRunSummaries(rows: List<com.ai.data.SecondaryResult>): List<TranslationRunSummary> {
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

/** Fixed-width "type" cell used by every row in the result list:
 *  agent rows show "report", secondary rows show their kind, the
 *  translation-run summary shows "translate". Lowercase to match the
 *  user-facing convention. Constant width so the model column to its
 *  right lines up across rows. */
@Composable
private fun RowTypeCell(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = AppColors.TextTertiary,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier.width(72.dp).padding(end = 6.dp)
    )
}

/** Compact action button shared across the Reports result page's
 *  View / Edit / Actions rows. Sized to its label (no width filling),
 *  with thin vertical padding so a row of these takes a fraction of
 *  the height a default Material Button would. [leading] runs before
 *  the label and is used by the Meta button to host its spinning ⏳
 *  while a batch is in flight. */
@Composable
private fun CompactButton(
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

/** Spinning ⏳ glyph used by the Meta button's in-flight indicator and
 *  any other "secondary run is happening" cue on the report screen. */
@Composable
internal fun AnimatedHourglass(fontSize: androidx.compose.ui.unit.TextUnit = 12.sp) {
    val transition = rememberInfiniteTransition(label = "secondary-hourglass")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
        label = "secondary-hourglass-rotation"
    )
    Text(text = "⏳", fontSize = fontSize, modifier = Modifier.rotate(angle))
}
