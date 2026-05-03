package com.ai.ui.report

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()
    val agentResults by reportViewModel.agentResults.collectAsState()
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
        initialModels = initialModels,
        onRunSecondary = { reportId, kind, picks, scopeChoice, languageScope ->
            reportViewModel.runSecondary(scope, context, reportId, kind, picks, scopeChoice, languageScope)
        },
        onRunLocalRerank = { reportId, modelName ->
            reportViewModel.runLocalRerank(scope, context, reportId, modelName)
        },
        onDeleteSecondary = { reportId, resultId ->
            reportViewModel.deleteSecondaryResult(context, reportId, resultId)
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
        onConsumeTranslation = { runId -> reportViewModel.consumeTranslationRun(runId) }
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
    onDeleteReport: (String) -> Unit = {},
    onCopyReport: (String) -> Unit = {},
    onTogglePinReport: (String) -> Unit = {},
    onConsumePendingModels: () -> Unit = {},
    onRunSecondary: (String, SecondaryKind, List<Pair<AppService, String>>, com.ai.data.SecondaryScope, com.ai.data.SecondaryLanguageScope) -> Unit = { _, _, _, _, _ -> },
    onRunLocalRerank: (String, String) -> Unit = { _, _ -> },
    onDeleteSecondary: (String, String) -> Unit = { _, _ -> },
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onRemoveAgent: (String, String) -> Unit = { _, _ -> },
    onRegenerateAgent: (String, String) -> Unit = { _, _ -> },
    onExport: suspend (String, ReportExportFormat, ReportExportDetail, ReportExportAction, (Int, Int) -> Unit) -> Unit = { _, _, _, _, _ -> },
    onExportAll: suspend (String, (Int, Int) -> Unit) -> Unit = { _, _ -> },
    translationRuns: List<com.ai.viewmodel.ReportViewModel.TranslationRunState> = emptyList(),
    onStartTranslation: (String, String, String, AppService, String) -> Unit = { _, _, _, _, _ -> },
    onCancelTranslation: (String) -> Unit = {},
    onConsumeTranslation: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val aiSettings = uiState.aiSettings

    val reportsTotal = uiState.genericReportsTotal
    val reportsProgress = uiState.genericReportsProgress
    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0
    val currentReportId = uiState.currentReportId

    // Per-kind row counts + the actual meta-run list for the result
    // page. Counts gate the Summaries / Compares / Reranks /
    // Moderations buttons in the View row; the list drives the per-
    // run rows shown below the agent rows. Polled while a meta batch
    // is in flight so newly added rows / status changes surface
    // promptly without the user bouncing in/out of the screen.
    var secondaryCounts by remember { mutableStateOf(SecondaryResultStorage.Counts(0, 0, 0, 0, 0)) }
    var secondaryRuns by remember { mutableStateOf(emptyList<com.ai.data.SecondaryResult>()) }
    var translationRunSummaries by remember { mutableStateOf(emptyList<TranslationRunSummary>()) }
    var secondaryTotals by remember { mutableStateOf(SecondaryTotals.ZERO) }
    // Recompute when any translation run finishes — the persisted
    // summary appears in translationRunSummaries on the next reload,
    // which is what flips the live row to the static one.
    val anyTranslationFinished = translationRuns.any { it.isFinished }
    val finishedSignature = translationRuns.filter { it.isFinished }.map { it.runId }.toSet()
    LaunchedEffect(currentReportId, isComplete, uiState.activeSecondaryBatches, finishedSignature) {
        val rid = currentReportId ?: run {
            secondaryCounts = SecondaryResultStorage.Counts(0, 0, 0, 0, 0)
            secondaryRuns = emptyList()
            translationRunSummaries = emptyList()
            secondaryTotals = SecondaryTotals.ZERO
            return@LaunchedEffect
        }
        suspend fun reload() {
            withContext(Dispatchers.IO) {
                val all = SecondaryResultStorage.listForReport(context, rid)
                secondaryRuns = all
                    .filter { it.kind != SecondaryKind.TRANSLATE }
                    .sortedByDescending { it.timestamp }
                translationRunSummaries = buildTranslationRunSummaries(
                    all.filter { it.kind == SecondaryKind.TRANSLATE }
                )
                secondaryCounts = SecondaryResultStorage.countForReport(context, rid)
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
            delay(200)
            finishedSignature.forEach { onConsumeTranslation(it) }
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

    var showViewer by remember { mutableStateOf(false) }
    var selectedAgentForViewer by remember { mutableStateOf<String?>(null) }
    var viewerSection by remember { mutableStateOf<String?>(null) }
    // Per-row click → focused single-model viewer. Distinct from the
    // multi-agent ReportsViewerScreen reached via View → Results.
    var singleResultAgentId by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var showEditPrompt by remember { mutableStateOf(false) }
    var showEditTitle by remember { mutableStateOf(false) }
    var showEditParameters by remember { mutableStateOf(false) }
    var showAdvancedParameters by remember { mutableStateOf(false) }
    // Translate flow state.
    var showTranslateLanguagePicker by remember { mutableStateOf(false) }
    var showTranslateModelPicker by remember { mutableStateOf<TargetLanguage?>(null) }
    var models by remember { mutableStateOf(initialModels) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // One-shot consumer: when ReportViewModel (Edit models / Regenerate flows) drops a
    // pre-built model list into uiState.pendingReportModels, copy it into the local
    // selection state and clear the signal so we don't keep re-applying it.
    LaunchedEffect(uiState.pendingReportModels) {
        if (uiState.pendingReportModels.isNotEmpty()) {
            models = deduplicateModels(uiState.pendingReportModels)
            onConsumePendingModels()
        }
    }
    var showSelectFlock by remember { mutableStateOf(false) }
    var showSelectAgent by remember { mutableStateOf(false) }
    var showSelectSwarm by remember { mutableStateOf(false) }
    var showSelectProvider by remember { mutableStateOf(false) }
    var pendingProvider by remember { mutableStateOf<AppService?>(null) }
    var showSelectAllModels by remember { mutableStateOf(false) }
    var showSelectFromReport by remember { mutableStateOf(false) }
    var selectedParametersIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var externalAutoGenerated by remember { mutableStateOf(false) }
    // Rerank/Summarize/Compare state
    var secondaryPickerKind by remember { mutableStateOf<SecondaryKind?>(null) }
    // Two-step flow for Summarize / Compare when reranks exist: scope screen
    // first, then the model picker. Carries the chosen scope into the picker
    // so onRunSecondary can apply it. Null until the user opts to start.
    var secondaryScopeKind by remember { mutableStateOf<SecondaryKind?>(null) }
    var pendingSecondaryScope by remember { mutableStateOf<com.ai.data.SecondaryScope>(com.ai.data.SecondaryScope.AllReports) }
    var pendingLanguageScope by remember { mutableStateOf<com.ai.data.SecondaryLanguageScope>(com.ai.data.SecondaryLanguageScope.AllPresent) }
    // Unified Meta screen overlay reached from the Actions card. While
    // open, the user can browse all Rerank/Summarize/Compare entries and
    // launch new ones from the bottom Add card.
    var showMetaScreen by remember { mutableStateOf(false) }
    // Per-kind list overlays reached from the View card's
    // Summaries/Compares/Reranks/Moderations buttons. Each opens
    // SecondaryResultsScreen filtered to that one kind.
    var listKind by remember { mutableStateOf<SecondaryKind?>(null) }

    // Screen keepalive during generation
    DisposableEffect(isGenerating, isComplete) {
        if (isGenerating && !isComplete) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // External model resolution
    val externalModels = remember(uiState.externalAgentNames, uiState.externalFlockNames, uiState.externalSwarmNames, uiState.externalModelSpecs) {
        val result = mutableListOf<ReportModel>()
        uiState.externalAgentNames.forEach { name ->
            aiSettings.agents.find { it.name.equals(name, ignoreCase = true) }?.let { expandAgentToModel(it, aiSettings)?.let(result::add) }
        }
        uiState.externalFlockNames.forEach { name ->
            aiSettings.flocks.find { it.name.equals(name, ignoreCase = true) }?.let { result.addAll(expandFlockToModels(it, aiSettings)) }
        }
        uiState.externalSwarmNames.forEach { name ->
            aiSettings.swarms.find { it.name.equals(name, ignoreCase = true) }?.let { result.addAll(expandSwarmToModels(it, aiSettings)) }
        }
        uiState.externalModelSpecs.forEach { spec ->
            val parts = spec.split("/", limit = 2)
            val provider = AppService.findById(parts.getOrNull(0) ?: "") ?: AppService.entries.find { it.displayName.equals(parts.getOrNull(0), ignoreCase = true) }
            val model = parts.getOrNull(1)
            if (provider != null && model != null) result.add(toReportModel(provider, model))
        }
        deduplicateModels(result)
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
        ReportsViewerScreen(reportId = currentReportId, initialSelectedAgentId = selectedAgentForViewer, initialSection = viewerSection, onDismiss = { showViewer = false; viewerSection = null }, onNavigateHome = onNavigateHome, onNavigateToTraceFile = onNavigateToTraceFile)
        return
    }
    val singleAgentId = singleResultAgentId
    if (singleAgentId != null && currentReportId != null) {
        ReportSingleResultScreen(
            reportId = currentReportId,
            agentId = singleAgentId,
            onBack = { singleResultAgentId = null },
            onNavigateHome = onNavigateHome,
            onNavigateToModelInfo = onNavigateToModelInfo,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onRemoveAgent = onRemoveAgent,
            onRegenerateAgent = onRegenerateAgent
        )
        return
    }
    if (showAdvancedParameters) {
        ReportAdvancedParametersScreen(currentParameters = advancedParameters, onApply = { onAdvancedParametersChange(it); showAdvancedParameters = false }, onBack = { showAdvancedParameters = false })
        return
    }

    // Selection overlay dialogs
    if (showSelectFlock) { ReportSelectFlockDialog(aiSettings, onSelectFlock = { models = deduplicateModels(models + expandFlockToModels(it, aiSettings)); showSelectFlock = false }, onDismiss = { showSelectFlock = false }); return }
    if (showSelectAgent) { ReportSelectAgentDialog(aiSettings, onSelectAgent = { expandAgentToModel(it, aiSettings)?.let { m -> models = deduplicateModels(models + m) }; showSelectAgent = false }, onDismiss = { showSelectAgent = false }); return }
    if (showSelectSwarm) { ReportSelectSwarmDialog(aiSettings, onSelectSwarm = { models = deduplicateModels(models + expandSwarmToModels(it, aiSettings)); showSelectSwarm = false }, onDismiss = { showSelectSwarm = false }); return }
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

    // Scope screen — always shown before the picker for Summarize /
    // Compare. Lets the user pick the input set (all model results /
    // top-N from a rerank / a manual subset) and, when translation
    // rows exist, which target languages to fan out to.
    val scopeKind = secondaryScopeKind
    if (scopeKind != null && currentReportId != null) {
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
            kind = scopeKind,
            agents = sd.agents,
            reranks = sd.reranks,
            languages = sd.languages,
            totalReports = sd.totalReports,
            onContinue = { chosenScope, chosenLangScope ->
                pendingSecondaryScope = chosenScope
                pendingLanguageScope = chosenLangScope
                secondaryScopeKind = null
                secondaryPickerKind = scopeKind
            },
            onBack = { secondaryScopeKind = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Rerank / Summarize / Compare model picker. Reuses the same multi-select
    // screen with kind-specific labels and (for rerank) a "rerank models only"
    // toggle that narrows to ModelType.RERANK.
    val pickerKind = secondaryPickerKind
    if (pickerKind != null && currentReportId != null) {
        val rid = currentReportId
        val pickerLabel = when (pickerKind) {
            SecondaryKind.RERANK -> "Rerank"
            SecondaryKind.SUMMARIZE -> "Summarize"
            SecondaryKind.COMPARE -> "Compare"
            SecondaryKind.MODERATION -> "Moderation"
            SecondaryKind.TRANSLATE -> "Translate" // never reached — Translate has its own picker
        }
        ReportSelectModelsScreen(
            aiSettings = aiSettings,
            // Single-pick: tap fires the meta run for one model and pops
            // back. Users wanting two Reranks just open the picker twice.
            titleText = "$pickerLabel — pick model",
            modelTypeFilter = when (pickerKind) {
                SecondaryKind.RERANK -> com.ai.data.ModelType.RERANK
                SecondaryKind.MODERATION -> com.ai.data.ModelType.MODERATION
                else -> null
            },
            onConfirm = { pick ->
                onRunSecondary(rid, pickerKind, listOf(pick), pendingSecondaryScope, pendingLanguageScope)
                secondaryPickerKind = null
                pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
            },
            onLocalConfirm = { modelName ->
                onRunLocalRerank(rid, modelName)
                secondaryPickerKind = null
                pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
            },
            onBack = { secondaryPickerKind = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Translate overlays. Order: language picker → model picker →
    // progress screen. The first two close the picker once a choice is
    // made; the progress screen sticks around until the run finishes
    // and the user taps "To translated report" (or Cancel).
    if (showTranslateLanguagePicker) {
        LanguageSelectionScreen(
            onConfirm = { lang ->
                showTranslateLanguagePicker = false
                showTranslateModelPicker = lang
            },
            onBack = { showTranslateLanguagePicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }
    val pickingTranslateModelFor = showTranslateModelPicker
    if (pickingTranslateModelFor != null && currentReportId != null) {
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
        return
    }

    // Per-meta-run detail overlay reached from a Meta row in the
    // result list. Routes through the same SecondaryResultDetailScreen
    // the Meta hub uses, so navigation / delete behave identically.
    val openMetaResult = openMetaResultId?.let { id -> secondaryRuns.firstOrNull { it.id == id } }
    if (openMetaResult != null && currentReportId != null) {
        val rid = currentReportId
        SecondaryResultDetailScreen(
            result = openMetaResult,
            onDelete = {
                onDeleteSecondary(rid, openMetaResult.id)
                openMetaResultId = null
            },
            onBack = { openMetaResultId = null },
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo
        )
        return
    }

    val openRunId = openTranslationRunId
    if (openRunId != null && currentReportId != null) {
        val rid = currentReportId
        TranslationRunDetailScreen(
            reportId = rid,
            runId = openRunId,
            onDelete = { resultId -> onDeleteSecondary(rid, resultId) },
            onBack = { openTranslationRunId = null },
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToTraceList = { onNavigateToTraceListFiltered(rid, "Translation") },
            onNavigateToModelInfo = onNavigateToModelInfo
        )
        return
    }

    val openListKind = listKind
    if (openListKind != null && currentReportId != null) {
        val rid = currentReportId
        SecondaryResultsScreen(
            reportId = rid,
            kind = openListKind,
            onDelete = { resultId -> onDeleteSecondary(rid, resultId) },
            onBack = { listKind = null },
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo
        )
        return
    }

    if (showMetaScreen && currentReportId != null) {
        val rid = currentReportId
        ReportMetaScreen(
            reportId = rid,
            isRunning = uiState.activeSecondaryBatches > 0,
            onRerank = {
                pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                secondaryPickerKind = SecondaryKind.RERANK
            },
            onSummarize = { secondaryScopeKind = SecondaryKind.SUMMARIZE },
            onCompare = { secondaryScopeKind = SecondaryKind.COMPARE },
            onModerate = {
                // Moderation operates on every response, no scope step.
                pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                secondaryPickerKind = SecondaryKind.MODERATION
            },
            onDelete = { resultId -> onDeleteSecondary(rid, resultId) },
            onBack = { showMetaScreen = false },
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo
        )
        return
    }

    // Share dialog
    if (showDeleteConfirm && currentReportId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete report?") },
            text = { Text("This permanently removes the saved report from disk.") },
            confirmButton = {
                TextButton(onClick = {
                    val rid = currentReportId
                    showDeleteConfirm = false
                    onDeleteReport(rid)
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    if (showExport && currentReportId != null) {
        val rid = currentReportId
        ReportExportScreen(
            onBack = { showExport = false },
            onNavigateHome = onNavigateHome,
            onExport = { fmt, det, act, onProgress -> onExport(rid, fmt, det, act, onProgress) },
            onExportAll = { onProgress -> onExportAll(rid, onProgress) }
        )
        return
    }

    if (showEditParameters) {
        ReportAdvancedParametersScreen(
            currentParameters = uiState.reportAdvancedParameters,
            onApply = {
                onAdvancedParametersChange(it)
                onMarkParametersChanged()
                showEditParameters = false
            },
            onBack = { showEditParameters = false }
        )
        return
    }

    if (showEditPrompt && currentReportId != null) {
        val rid = currentReportId
        ReportEditPromptScreen(
            initialPrompt = uiState.genericPromptText,
            onBack = { showEditPrompt = false },
            onNavigateHome = onNavigateHome,
            onUpdate = { newPrompt ->
                showEditPrompt = false
                onUpdatePrompt(rid, newPrompt)
            }
        )
        return
    }
    if (showEditTitle && currentReportId != null) {
        val rid = currentReportId
        ReportEditTitleScreen(
            initialTitle = uiState.genericPromptTitle,
            onBack = { showEditTitle = false },
            onNavigateHome = onNavigateHome,
            onUpdate = { newTitle ->
                showEditTitle = false
                onUpdateTitle(rid, newTitle)
            }
        )
        return
    }

    // Main UI
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        // Title reflects the phase: selection (adding models) vs generation (showing results).
        TitleBar(
            title = if (isGenerating) uiState.genericPromptTitle.ifBlank { "AI Reports" } else "AI Report - Models",
            onBackClick = onDismiss, onAiClick = onNavigateHome
        )
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
                onUpdateModelList = { if (editModeRid != null) onUpdateModelList(editModeRid, models) }
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
                onViewResults = { selectedAgentForViewer = null; viewerSection = null; showViewer = true },
                onViewPrompt = { selectedAgentForViewer = null; viewerSection = "prompt"; showViewer = true },
                onViewCosts = { selectedAgentForViewer = null; viewerSection = "costs"; showViewer = true },
                onViewAgent = { agentId -> singleResultAgentId = agentId },
                onShare = { showExport = true },
                onTrace = { currentReportId?.let(onNavigateToTrace) },
                onEditPrompt = { showEditPrompt = true },
                onEditTitle = { showEditTitle = true },
                onEditModels = { currentReportId?.let(onEditModels) },
                onEditParameters = { showEditParameters = true },
                onRegenerate = { currentReportId?.let(onRegenerate) },
                onDelete = { showDeleteConfirm = true },
                onCopy = { currentReportId?.let(onCopyReport) },
                onTogglePin = { currentReportId?.let(onTogglePinReport) },
                onTranslate = { showTranslateLanguagePicker = true },
                secondaryCounts = secondaryCounts,
                secondaryRuns = secondaryRuns,
                secondaryTotals = secondaryTotals,
                translationRuns = translationRuns,
                onCancelTranslation = onCancelTranslation,
                translationRunSummaries = translationRunSummaries,
                onViewSecondaryKind = { kind -> listKind = kind },
                onOpenSecondaryRun = { id -> openMetaResultId = id },
                onOpenTranslationRun = { runId -> openTranslationRunId = runId },
                onOpenMeta = { showMetaScreen = true },
                onRerank = {
                    pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                    secondaryPickerKind = SecondaryKind.RERANK
                },
                onSummarize = { if (currentReportId != null) secondaryScopeKind = SecondaryKind.SUMMARIZE },
                onCompare = { if (currentReportId != null) secondaryScopeKind = SecondaryKind.COMPARE },
                onModerate = {
                    pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                    secondaryPickerKind = SecondaryKind.MODERATION
                }
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
    onUpdateModelList: () -> Unit
) {
    val context = LocalContext.current

    // Add buttons. The all-models picker (+Model) supersedes the
    // provider-then-model two-step, so the +Provider variant has been
    // dropped from the row. The unused onAddModel callback stays in
    // the signature for now but is ignored here.
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "Agent" to onAddAgent,
            "Flock" to onAddFlock,
            "Swarm" to onAddSwarm,
            "Model" to onAddAllModels,
            "Report" to onAddFromReport
        ).forEach { (label, action) ->
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
                        Text("${entry.provider.displayName}${if (entry.sourceName.isNotBlank()) " via ${entry.sourceName}" else ""}", fontSize = 11.sp, color = AppColors.TextTertiary)
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
    onViewResults: () -> Unit,
    onViewPrompt: () -> Unit,
    onViewCosts: () -> Unit,
    onViewAgent: (String) -> Unit,
    onShare: () -> Unit,
    onTrace: () -> Unit,
    onEditPrompt: () -> Unit,
    onEditTitle: () -> Unit = {},
    onEditModels: () -> Unit,
    onEditParameters: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onTranslate: () -> Unit = {},
    secondaryCounts: SecondaryResultStorage.Counts = SecondaryResultStorage.Counts(0, 0, 0, 0, 0),
    secondaryRuns: List<com.ai.data.SecondaryResult> = emptyList(),
    secondaryTotals: SecondaryTotals = SecondaryTotals.ZERO,
    translationRuns: List<com.ai.viewmodel.ReportViewModel.TranslationRunState> = emptyList(),
    onCancelTranslation: (String) -> Unit = {},
    translationRunSummaries: List<TranslationRunSummary> = emptyList(),
    onViewSecondaryKind: (SecondaryKind) -> Unit = {},
    onOpenSecondaryRun: (String) -> Unit = {},
    onOpenTranslationRun: (String) -> Unit = {},
    onOpenMeta: () -> Unit = {},
    onRerank: () -> Unit = {},
    onSummarize: () -> Unit = {},
    onCompare: () -> Unit = {},
    onModerate: () -> Unit = {}
) {
    val context = LocalContext.current
    val aiSettings = uiState.aiSettings

    fun resolveModelForResult(agentId: String, result: AnalysisResponse): String {
        return aiSettings.getAgentById(agentId)?.let { aiSettings.getEffectiveModelForAgent(it) }
            ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringAfter(':')
            ?: result.service.defaultModel
    }

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

    // Per-agent token + cost rollup (computed up front so it can feed
    // both the top-of-page totals banner and any per-row costs below).
    val selectedAgents = uiState.genericReportsSelectedAgents
    val agentCost = remember(reportsAgentResults) {
        reportsAgentResults.entries.sumOf { (agentId, resp) ->
            resp.tokenUsage?.let {
                PricingCache.computeCost(it, PricingCache.getPricing(context, resp.service, resolveModelForResult(agentId, resp)))
            } ?: 0.0
        }
    }
    val agentInputTokens = remember(reportsAgentResults) {
        reportsAgentResults.values.sumOf { it.tokenUsage?.inputTokens ?: 0 }
    }
    val agentOutputTokens = remember(reportsAgentResults) {
        reportsAgentResults.values.sumOf { it.tokenUsage?.outputTokens ?: 0 }
    }
    val totalInputTokens = agentInputTokens + secondaryTotals.inputTokens
    val totalOutputTokens = agentOutputTokens + secondaryTotals.outputTokens
    val totalCost = agentCost + secondaryTotals.inputCost + secondaryTotals.outputCost

    // Totals banner — at the top of the page so the bottom-line cost
    // is visible without scrolling. Sums tokens and cents across the
    // per-agent rows AND every persisted meta run (rerank / summarize
    // / compare / moderation / translate). Hidden when nothing has
    // billed anything yet.
    if (totalInputTokens > 0 || totalOutputTokens > 0 || totalCost > 0.0) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("Total: $totalInputTokens/$totalOutputTokens tok", fontSize = 12.sp, color = AppColors.Blue, modifier = Modifier.weight(1f))
            Text("${formatCents(totalCost)} ¢", fontSize = 12.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace)
        }
    }

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

    data class DisplayRow(val rowId: String, val displayName: String, val isNew: Boolean)
    val displayRows: List<DisplayRow> = if (isStagedMode) {
        staged.map { m ->
            val rowId = if (m.type == "agent" && !m.agentId.isNullOrBlank()) m.agentId!!
                        else "swarm:${m.provider.id}:${m.model}"
            // Model-only label per spec — drop the provider prefix and
            // the agent's custom name so every row reads as a bare
            // model id.
            DisplayRow(rowId, m.model, !reportsAgentResults.containsKey(rowId))
        }
    } else {
        selectedAgents.sorted().map { agentId ->
            val result = reportsAgentResults[agentId]
            val name = result?.let { resolveModelForResult(agentId, it) }
                ?: aiSettings.getAgentById(agentId)?.let { aiSettings.getEffectiveModelForAgent(it) }
                ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringAfter(':')
                ?: agentId
            DisplayRow(agentId, name, false)
        }
    }

    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        displayRows.forEach { row ->
            val agentId = row.rowId
            val result = reportsAgentResults[agentId]
            val displayName = row.displayName

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).then(
                if (result != null) Modifier.clickable { onViewAgent(agentId) } else Modifier
            ), verticalAlignment = Alignment.CenterVertically) {
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
                    Text(displayName, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (result?.tokenUsage != null) {
                    val cost = PricingCache.computeCost(result.tokenUsage, PricingCache.getPricing(context, result.service, resolveModelForResult(agentId, result)))
                    Text(formatCents(cost), fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }

        // Meta runs \u2014 one row per individual rerank / summarize /
        // compare / moderation result on this report, sharing the
        // agent rows' layout (status icon + label + cost). Status
        // mirrors the agent rows: \u23F3 while the placeholder is still
        // empty, \u2705 on success, \u274C on error. Tapping opens that run's
        // detail screen. TRANSLATE rows are excluded \u2014 they're cost
        // records rather than user-actionable runs.
        if (isComplete && secondaryRuns.isNotEmpty()) {
            secondaryRuns.forEach { run ->
                val running = run.errorMessage == null && run.content.isNullOrBlank()
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
                    val typeLabel = when (run.kind) {
                        SecondaryKind.RERANK -> "rerank"
                        SecondaryKind.SUMMARIZE -> "summarize"
                        SecondaryKind.COMPARE -> "compare"
                        SecondaryKind.MODERATION -> "moderate"
                        SecondaryKind.TRANSLATE -> "translate"
                    }
                    RowTypeCell(typeLabel)
                    val langSuffix = run.targetLanguage?.let { " \u00B7 $it" } ?: ""
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${run.model}$langSuffix",
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val totalCost = (run.inputCost ?: 0.0) + (run.outputCost ?: 0.0)
                    if (totalCost > 0.0) {
                        Text(formatCents(totalCost), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

        // Live translation rows — one per active run. Hourglass spins
        // while items are in flight; the text leads with "n / N" so
        // progress is visible at a glance, and the cost cell ticks up
        // as each call returns. Multiple translations can run in
        // parallel (different language / model / both); each gets its
        // own row and its own Cancel.
        if (isComplete) {
            translationRuns.filter { !it.isFinished && !it.cancelled }.forEach { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                    Text(
                        "Cancel", fontSize = 11.sp, color = AppColors.Red,
                        modifier = Modifier.clickable { onCancelTranslation(run.runId) }.padding(horizontal = 4.dp)
                    )
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

        // Translation runs — one row per Translate invocation. Each
        // run produces several TRANSLATE secondaries (prompt + each
        // agent + each summary + each compare); they're collapsed
        // here so the user sees a single line per click. Tapping
        // opens TranslationRunDetailScreen with the call list.
        if (isComplete && translationRunSummaries.isNotEmpty()) {
            translationRunSummaries.forEach { run ->
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
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }

    }

    Spacer(modifier = Modifier.height(4.dp))

    // Action buttons. Every action row uses [CompactButton] — text-
    // sized, slim vertical padding, and a [FlowRow] so wide rows wrap
    // gracefully on narrow viewports instead of squeezing each button
    // into a tiny column.
    if (!isComplete) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactButton(onClick = onStop, color = AppColors.Red, text = "STOP")
            CompactButton(onClick = onContinueInBackground, color = AppColors.SurfaceDark, text = "Background")
        }
    } else {
        val secondaryRunning = uiState.activeSecondaryBatches > 0

        @OptIn(ExperimentalLayoutApi::class)
        @Composable fun ActionRow(content: @Composable FlowRowScope.() -> Unit) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
        @Composable fun SectionLabel(text: String) {
            Text(text, fontSize = 10.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        }

        SectionLabel("View")
        ActionRow {
            CompactButton(onClick = onViewResults, color = AppColors.Purple, text = "Reports")
            CompactButton(onClick = onViewPrompt, color = AppColors.Blue, text = "Prompt")
            CompactButton(onClick = onViewCosts, color = AppColors.Green, text = "Costs")
            CompactButton(onClick = onTrace, color = AppColors.Indigo, text = "Trace", enabled = currentReportId != null)
            // Per-meta-kind viewers — one button per kind that has at
            // least one row on this report. Tapping opens the kind's
            // SecondaryResultsScreen so the user can browse / drill
            // into existing entries directly without going through the
            // unified Meta hub. Buttons are gated on the live count so
            // they appear/disappear as batches finish, and they live
            // in the same FlowRow so the line wraps when needed.
            if (secondaryCounts.summarize > 0) CompactButton(onClick = { onViewSecondaryKind(SecondaryKind.SUMMARIZE) }, color = AppColors.Orange, text = "Summaries")
            if (secondaryCounts.compare > 0) CompactButton(onClick = { onViewSecondaryKind(SecondaryKind.COMPARE) }, color = AppColors.Purple, text = "Compares")
            if (secondaryCounts.rerank > 0) CompactButton(onClick = { onViewSecondaryKind(SecondaryKind.RERANK) }, color = AppColors.Blue, text = "Reranks")
            if (secondaryCounts.moderation > 0) CompactButton(onClick = { onViewSecondaryKind(SecondaryKind.MODERATION) }, color = AppColors.Indigo, text = "Moderations")
        }

        SectionLabel("Edit")
        ActionRow {
            CompactButton(onClick = onEditPrompt, color = AppColors.Indigo, text = "Prompt")
            CompactButton(onClick = onEditTitle, color = AppColors.Indigo, text = "Title")
            CompactButton(onClick = onEditModels, color = AppColors.Purple, text = "Models")
            CompactButton(onClick = onEditParameters, color = AppColors.Blue, text = "Parameters")
        }

        SectionLabel("Actions")
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
        ActionRow {
            CompactButton(onClick = onRegenerate, color = AppColors.Green, text = "Regenerate")
            CompactButton(onClick = onShare, color = AppColors.Blue, text = "Export")
            CompactButton(onClick = onCopy, color = AppColors.Purple, text = "Copy")
            CompactButton(
                onClick = { onTogglePin(); pinTick++ },
                color = AppColors.Orange,
                text = if (isPinned) "Unpin" else "Pin"
            )
            CompactButton(onClick = onDelete, color = AppColors.Red, text = "Delete")
            CompactButton(onClick = onTranslate, color = AppColors.Indigo, text = "Translate")
            // Meta launchers folded directly into the Actions row.
            // Each opens the kind's model picker (Summarize / Compare
            // pop the rerank-scope step first when the report has at
            // least one rerank result). Existing entries are reachable
            // via the "Meta" row in the report list.
            CompactButton(onClick = onRerank, color = AppColors.Orange, text = "Rerank")
            CompactButton(onClick = onSummarize, color = AppColors.Orange, text = "Summarize")
            CompactButton(onClick = onCompare, color = AppColors.Orange, text = "Compare")
            CompactButton(onClick = onModerate, color = AppColors.Orange, text = "Moderation")
        }
    }
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
private fun AnimatedHourglass(fontSize: androidx.compose.ui.unit.TextUnit = 12.sp) {
    val transition = rememberInfiniteTransition(label = "secondary-hourglass")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
        label = "secondary-hourglass-rotation"
    )
    Text(text = "⏳", fontSize = fontSize, modifier = Modifier.rotate(angle))
}
