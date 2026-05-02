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
        onRunSecondary = { reportId, kind, picks, scopeChoice ->
            reportViewModel.runSecondary(scope, context, reportId, kind, picks, scopeChoice)
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
        onUpdatePrompt = { rid, title, prompt ->
            scope.launch { reportViewModel.updateReportPrompt(context, rid, title, prompt) }
        },
        onDeleteReport = { rid ->
            reportViewModel.deleteReport(context, rid)
            onNavigateBack()
        },
        onConsumePendingModels = { reportViewModel.clearPendingReportModels() },
        onExport = { rid, fmt, det, act, onProgress ->
            shareReportAsExport(context, rid, fmt, det, act, uiState.aiSettings, viewModel.repository, onProgress)
        },
        onExportAll = { rid, onProgress ->
            bulkExportAndShare(context, rid, onProgress)
        },
        translationRun = reportViewModel.translationRun.collectAsState().value,
        onStartTranslation = { sourceId, langName, langNative, prov, model ->
            reportViewModel.startTranslation(scope, context, sourceId, langName, langNative, prov, model)
        },
        onCancelTranslation = { reportViewModel.cancelTranslation() },
        onConsumeTranslation = { reportViewModel.consumeTranslationRun() }
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
    onClearExternalInstructions: () -> Unit = {},
    onEditModels: (String) -> Unit = {},
    onUpdateModelList: (String, List<ReportModel>) -> Unit = { _, _ -> },
    onMarkParametersChanged: () -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onUpdatePrompt: (String, String, String) -> Unit = { _, _, _ -> },
    onDeleteReport: (String) -> Unit = {},
    onConsumePendingModels: () -> Unit = {},
    onRunSecondary: (String, SecondaryKind, List<Pair<AppService, String>>, com.ai.data.SecondaryScope) -> Unit = { _, _, _, _ -> },
    onDeleteSecondary: (String, String) -> Unit = { _, _ -> },
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onRemoveAgent: (String, String) -> Unit = { _, _ -> },
    onRegenerateAgent: (String, String) -> Unit = { _, _ -> },
    onExport: suspend (String, ReportExportFormat, ReportExportDetail, ReportExportAction, (Int, Int) -> Unit) -> Unit = { _, _, _, _, _ -> },
    onExportAll: suspend (String, (Int, Int) -> Unit) -> Unit = { _, _ -> },
    translationRun: com.ai.viewmodel.ReportViewModel.TranslationRunState? = null,
    onStartTranslation: (String, String, String, AppService, String) -> Unit = { _, _, _, _, _ -> },
    onCancelTranslation: () -> Unit = {},
    onConsumeTranslation: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val aiSettings = uiState.aiSettings

    val reportsTotal = uiState.genericReportsTotal
    val reportsProgress = uiState.genericReportsProgress
    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0
    val currentReportId = uiState.currentReportId

    var showViewer by remember { mutableStateOf(false) }
    var selectedAgentForViewer by remember { mutableStateOf<String?>(null) }
    var viewerSection by remember { mutableStateOf<String?>(null) }
    // Per-row click → focused single-model viewer. Distinct from the
    // multi-agent ReportsViewerScreen reached via View → Results.
    var singleResultAgentId by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var showEditPrompt by remember { mutableStateOf(false) }
    var showEditParameters by remember { mutableStateOf(false) }
    var showAdvancedParameters by remember { mutableStateOf(false) }
    // Translate flow state.
    var showTranslateLanguagePicker by remember { mutableStateOf(false) }
    var showTranslateModelPicker by remember { mutableStateOf<TargetLanguage?>(null) }
    var showTranslateProgress by remember { mutableStateOf(false) }

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
    // Unified Meta screen overlay reached from the Actions card. While
    // open, the user can browse all Rerank/Summarize/Compare entries and
    // launch new ones from the bottom Add card.
    var showMetaScreen by remember { mutableStateOf(false) }

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

    // Scope screen — shown before the picker for Summarize / Compare when
    // the report has at least one rerank result. Lets the user narrow the
    // input to the top-N entries of a chosen rerank.
    val scopeKind = secondaryScopeKind
    if (scopeKind != null && currentReportId != null) {
        val rid = currentReportId
        // IO load — scope screen reads the rerank list and the parent
        // report off disk; without produceState both would parse JSON
        // on the UI thread. Loading sentinel keeps the screen blank
        // until the read finishes (typically <50 ms).
        data class ScopeData(val reranks: List<com.ai.data.SecondaryResult>, val totalReports: Int)
        val scopeDataState = produceState<ScopeData?>(initialValue = null, rid) {
            value = withContext(Dispatchers.IO) {
                val rr = com.ai.data.SecondaryResultStorage.listForReport(context, rid, com.ai.data.SecondaryKind.RERANK)
                val report = com.ai.data.ReportStorage.getReport(context, rid)
                val total = report?.agents?.count { it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() } ?: 0
                ScopeData(rr, total)
            }
        }
        val sd = scopeDataState.value
        if (sd == null) {
            // Still loading — render nothing for a frame or two.
            return
        }
        // Defensive: scope screen should never have been entered without
        // reranks present, but if reranks were deleted between click and
        // render, fall through to the picker with AllReports.
        if (sd.reranks.isEmpty()) {
            secondaryScopeKind = null
            secondaryPickerKind = scopeKind
            pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
        } else {
            SecondaryScopeScreen(
                kind = scopeKind,
                reranks = sd.reranks,
                totalReports = sd.totalReports,
                onContinue = { chosen ->
                    pendingSecondaryScope = chosen
                    secondaryScopeKind = null
                    secondaryPickerKind = scopeKind
                },
                onBack = { secondaryScopeKind = null },
                onNavigateHome = onNavigateHome
            )
            return
        }
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
                onRunSecondary(rid, pickerKind, listOf(pick), pendingSecondaryScope)
                secondaryPickerKind = null
                pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
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
                showTranslateProgress = true
            },
            onBack = { showTranslateModelPicker = null },
            onNavigateHome = onNavigateHome
        )
        return
    }
    if (showTranslateProgress) {
        TranslationProgressScreen(
            runState = translationRun,
            onCancel = onCancelTranslation,
            onConsume = onConsumeTranslation,
            onBack = { showTranslateProgress = false },
            onNavigateHome = onNavigateHome
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
            onSummarize = {
                val hasRerank = com.ai.data.SecondaryResultStorage.countForReport(context, rid).rerank > 0
                if (hasRerank) secondaryScopeKind = SecondaryKind.SUMMARIZE
                else { pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports; secondaryPickerKind = SecondaryKind.SUMMARIZE }
            },
            onCompare = {
                val hasRerank = com.ai.data.SecondaryResultStorage.countForReport(context, rid).rerank > 0
                if (hasRerank) secondaryScopeKind = SecondaryKind.COMPARE
                else { pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports; secondaryPickerKind = SecondaryKind.COMPARE }
            },
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
            initialTitle = uiState.genericPromptTitle,
            initialPrompt = uiState.genericPromptText,
            onBack = { showEditPrompt = false },
            onNavigateHome = onNavigateHome,
            onUpdate = { newTitle, newPrompt ->
                showEditPrompt = false
                onUpdatePrompt(rid, newTitle, newPrompt)
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
                onEditModels = { currentReportId?.let(onEditModels) },
                onEditParameters = { showEditParameters = true },
                onRegenerate = { currentReportId?.let(onRegenerate) },
                onDelete = { showDeleteConfirm = true },
                onMeta = { showMetaScreen = true },
                onTranslate = { showTranslateLanguagePicker = true }
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

    // Add buttons
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Button order: Agent, Flock, Swarm, Provider, Model. Note: onAddModel opens the provider
        // selector (hence "+Provider") and onAddAllModels opens the all-models picker (renamed
        // to the simpler "+Model" label per the new naming scheme).
        listOf(
            "Agent" to onAddAgent,
            "Flock" to onAddFlock,
            "Swarm" to onAddSwarm,
            "Provider" to onAddModel,
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
    onEditModels: () -> Unit,
    onEditParameters: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onMeta: () -> Unit = {},
    onTranslate: () -> Unit = {}
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

    // Progress (hidden when a staged model edit is pending — the banner above already
    // tells the user a Regenerate is needed; the green bar would just be misleading).
    if (!isStagedMode) {
        Text("$reportsProgress / $reportsTotal complete", color = AppColors.TextSecondary, fontSize = 14.sp)
        LinearProgressIndicator(
            progress = { if (reportsTotal > 0) reportsProgress.toFloat() / reportsTotal else 0f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            color = if (isComplete) AppColors.Green else AppColors.Purple
        )
    }

    // Agent results
    val selectedAgents = uiState.genericReportsSelectedAgents
    val totalCost = remember(reportsAgentResults) {
        reportsAgentResults.entries.sumOf { (agentId, resp) ->
            resp.tokenUsage?.let {
                PricingCache.computeCost(it, PricingCache.getPricing(context, resp.service, resolveModelForResult(agentId, resp)))
            } ?: 0.0
        }
    }

    data class DisplayRow(val rowId: String, val displayName: String, val isNew: Boolean)
    val displayRows: List<DisplayRow> = if (isStagedMode) {
        staged.map { m ->
            val rowId = if (m.type == "agent" && !m.agentId.isNullOrBlank()) m.agentId!!
                        else "swarm:${m.provider.id}:${m.model}"
            val name = if (m.type == "agent") (aiSettings.getAgentById(m.agentId ?: "")?.name ?: m.model)
                       else "${m.provider.displayName} / ${m.model}"
            DisplayRow(rowId, name, !reportsAgentResults.containsKey(rowId))
        }
    } else {
        selectedAgents.sorted().map { agentId ->
            val name = reportsAgentResults[agentId]?.displayName
                ?: aiSettings.getAgentById(agentId)?.name
                ?: agentId.removePrefix("swarm:").replace(":", " / ")
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

        if (reportsAgentResults.isNotEmpty()) {
            val totalIn = reportsAgentResults.values.sumOf { it.tokenUsage?.inputTokens ?: 0 }
            val totalOut = reportsAgentResults.values.sumOf { it.tokenUsage?.outputTokens ?: 0 }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Total: $totalIn/$totalOut tok", fontSize = 12.sp, color = AppColors.Blue, modifier = Modifier.weight(1f))
                Text("${formatCents(totalCost)} \u00A2", fontSize = 12.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Action buttons
    if (!isComplete) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStop, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)) { Text("STOP", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = onContinueInBackground, modifier = Modifier.weight(1f), colors = AppColors.outlinedButtonColors()) { Text("Background", maxLines = 1, softWrap = false) }
        }
    } else {
        val secondaryRunning = uiState.activeSecondaryBatches > 0

        // Section: View
        Text("View", fontSize = 11.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onViewResults, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Results", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onViewPrompt, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Prompt", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onViewCosts, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Costs", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onTrace, enabled = currentReportId != null, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Trace", fontSize = 11.sp, maxLines = 1, softWrap = false) }
        }

        // Section: Edit
        Text("Edit", fontSize = 11.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onEditPrompt, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Prompt", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onEditModels, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Models", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onEditParameters, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Parameters", fontSize = 11.sp, maxLines = 1, softWrap = false) }
        }

        // Section: Actions
        Text("Actions", fontSize = 11.sp, color = AppColors.TextTertiary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onRegenerate, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Regenerate", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onShare, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Export", fontSize = 11.sp, maxLines = 1, softWrap = false) }
            Button(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red), contentPadding = PaddingValues(horizontal = 2.dp)) { Text("Delete", fontSize = 11.sp, maxLines = 1, softWrap = false) }
        }
        // Meta + Translate share a row below the standard Actions
        // buttons. Meta opens the Rerank/Summarize/Compare/Moderation
        // hub (with a small spinning ⏳ when a batch is in flight);
        // Translate opens the language picker → model picker → progress
        // screen pipeline.
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onMeta,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                if (secondaryRunning) {
                    AnimatedHourglass()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Meta", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            Button(
                onClick = onTranslate,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                Text("Translate", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
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
