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
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which list a +Add overlay confirm should write to. The same
 *  overlays (showSelectAgent / showSelectFlock / showSelectSwarm /
 *  showSelectAllModels / showSelectFromReport) are reused by the
 *  "Find icons" picker flow; this enum tells their `onConfirm`
 *  whether to push the picked row into the New-Report `models`
 *  list or the Find-icons `findIconsModels` list. */
private enum class PickerTarget { NEW_REPORT, FIND_ICONS }

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

/** Saver for the per-screen selected-models list (and the parallel
 *  Find-icons list). The list backs the SelectionPhase's selected-
 *  rows column, which is `modelInfoClickable` — tapping a row pops
 *  out to Model Info. That nav removes AI_REPORTS from the active
 *  composition; without a Saver, plain `remember { mutableStateOf }`
 *  loses the list and the user comes back to an empty picker. Each
 *  ReportModel is flattened to 10 strings; lists with un-resolvable
 *  provider ids (deleted between save and restore) drop those rows
 *  silently. */
private val ReportModelListSaver: Saver<List<ReportModel>, Any> = listSaver(
    save = { list ->
        list.flatMap { m ->
            listOf(
                m.provider.id, m.model, m.type, m.sourceType, m.sourceName,
                m.sourceId ?: "", m.agentId ?: "", m.endpointId ?: "",
                m.agentApiKey ?: "", m.paramsIds.joinToString(",")
            )
        }
    },
    restore = { saved ->
        val out = mutableListOf<ReportModel>()
        var i = 0
        while (i + 10 <= saved.size) {
            val providerId = saved[i] as? String
            val provider = providerId?.let { AppService.findById(it) }
            if (provider != null) {
                out.add(
                    ReportModel(
                        provider = provider,
                        model = saved[i + 1] as? String ?: "",
                        type = saved[i + 2] as? String ?: "",
                        sourceType = saved[i + 3] as? String ?: "",
                        sourceName = saved[i + 4] as? String ?: "",
                        sourceId = (saved[i + 5] as? String)?.takeIf { it.isNotEmpty() },
                        agentId = (saved[i + 6] as? String)?.takeIf { it.isNotEmpty() },
                        endpointId = (saved[i + 7] as? String)?.takeIf { it.isNotEmpty() },
                        agentApiKey = (saved[i + 8] as? String)?.takeIf { it.isNotEmpty() },
                        paramsIds = (saved[i + 9] as? String)?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
                    )
                )
            }
            i += 10
        }
        out.toList()
    }
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
    val iconFanOutByReport by viewModel.iconFanOutByReport.collectAsState()
    val agentIconFanOutByAgent by viewModel.agentIconFanOutByAgent.collectAsState()
    val internalPromptIconFanOutByPrompt by viewModel.internalPromptIconFanOutByPrompt.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiSettings = uiState.aiSettings

    // Prev / next AI-report navigation for the chevron icons on the
    // result page's action row. Sorted newest-first like the hub —
    // "<" picks the report one step UP (newer), ">" picks the one
    // below (older). Re-derived on every iconRefreshTick bump too,
    // since deleting a report (or creating one in the background)
    // changes the list. Cheap — one disk read per re-derivation, and
    // only when the current report id actually changes.
    var reportIdsNewestFirst by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(uiState.currentReportId, uiState.iconRefreshTick) {
        reportIdsNewestFirst = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getAllReports(context).map { it.id }
        }
    }
    val currentIdx = reportIdsNewestFirst.indexOf(uiState.currentReportId)
    val hasPrevReport = currentIdx > 0
    val hasNextReport = currentIdx >= 0 && currentIdx < reportIdsNewestFirst.size - 1

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

    // During an in-flight run we keep the run alive and arm the
    // completion toast (the old "Background" UX). Outside that — on a
    // finished report or before generation starts — fall through to
    // the destructive dismiss that clears UiState.
    val handleLeave = {
        val state = viewModel.uiState.value
        val running = state.currentReportId != null
            && state.showGenericReportsDialog
            && state.genericReportsProgress < state.genericReportsTotal
        if (running) reportViewModel.continueReportInBackground()
        else reportViewModel.dismissGenericReportsDialog()
        viewModel.clearExternalInstructions()
    }
    val handleDismiss = {
        handleLeave()
        onNavigateBack()
    }
    val handleNavigateHome = {
        handleLeave()
        onNavigateHome()
    }

    ReportsScreen(
        uiState = uiState,
        reportsAgentResults = agentResults,
        runningFanOutPairs = runningFanOutPairs,
        iconFanOutByReport = iconFanOutByReport,
        onStartIconFanOut = { rid, prompt, models ->
            reportViewModel.startIconFanOut(context, rid, prompt, models, aiSettings)
        },
        onPickAlternativeIcon = { rid, emoji, iconModel ->
            reportViewModel.pickAlternativeIcon(context, rid, emoji, iconModel)
        },
        onRestartIconFanOut = { rid -> reportViewModel.restartIconFanOut(rid) },
        onStartAgentIconFanOut = { rid, agentId, models ->
            reportViewModel.startAgentIconFanOut(context, rid, agentId, models, aiSettings)
        },
        onPickAgentIcon = { rid, agentId, emoji ->
            reportViewModel.pickAgentIcon(context, rid, agentId, emoji)
        },
        onRestartAgentIconFanOut = { rid, agentId ->
            reportViewModel.restartAgentIconFanOut(rid, agentId)
        },
        promptIconCallbacks = InternalPromptIconCallbacks(
            onKickoff = { prompt ->
                reportViewModel.kickOffInternalPromptIcon(context, prompt, aiSettings)
            },
            onStartFanOut = { prompt, picks ->
                reportViewModel.startInternalPromptIconFanOut(context, prompt, picks, aiSettings)
            },
            onPick = { prompt, cand ->
                reportViewModel.pickInternalPromptIcon(context, prompt, cand, aiSettings)
            },
            onRestartFanOut = { prompt ->
                reportViewModel.restartInternalPromptIconFanOut(prompt)
            }
        ),
        internalPromptIconFanOutByPrompt = internalPromptIconFanOutByPrompt,
        translationIconCallbacks = TranslationIconCallbacks(
            onKickoff = { language ->
                reportViewModel.kickOffTranslationIcon(context, language, aiSettings)
            },
            onStartFanOut = { language, picks ->
                reportViewModel.startTranslationIconFanOut(context, language, picks, aiSettings)
            },
            onPick = { language, cand ->
                reportViewModel.pickTranslationIcon(context, language, cand, aiSettings)
            },
            onRestartFanOut = { language ->
                reportViewModel.restartTranslationIconFanOut(language)
            }
        ),
        agentIconFanOutByAgent = agentIconFanOutByAgent,
        onPrevReport = {
            if (hasPrevReport) {
                val targetId = reportIdsNewestFirst[currentIdx - 1]
                scope.launch { reportViewModel.restoreCompletedReport(context, targetId) }
            }
        },
        onNextReport = {
            if (hasNextReport) {
                val targetId = reportIdsNewestFirst[currentIdx + 1]
                scope.launch { reportViewModel.restoreCompletedReport(context, targetId) }
            }
        },
        hasPrevReport = hasPrevReport,
        hasNextReport = hasNextReport,
        initialModels = initialModels,
        onRunSecondary = { reportId, metaPrompt, picks, scopeChoice, languageScope ->
            reportViewModel.runMetaPrompt(context, reportId, metaPrompt, picks, scopeChoice, languageScope)
        },
        onRunFanOut = { reportId, metaPrompt, scopeChoice, responderIds ->
            reportViewModel.runFanOutPrompt(context, reportId, metaPrompt, scopeChoice, responderIds)
        },
        onRunFanIn = { reportId, metaPrompt, pick ->
            reportViewModel.runFanInPrompt(context, reportId, metaPrompt, pick)
        },
        onRunModelFanIn = { reportId, metaPrompt, pick, activePid, activeMdl ->
            reportViewModel.runModelFanInPrompt(context, reportId, metaPrompt, pick, activePid, activeMdl)
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
            reportViewModel.runLocalRerank(context, reportId, modelName)
        },
        onRunRerank = { reportId, pick ->
            reportViewModel.runRerank(context, reportId, pick)
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
                context = context, selectedAgentIds = agentIds, selectedSwarmIds = swarmIds,
                directModelIds = directIds, parametersIds = paramsIds, reportType = reportType
            )
        },
        onDismiss = handleDismiss,
        onNavigateHome = handleNavigateHome,
        advancedParameters = uiState.reportAdvancedParameters,
        onAdvancedParametersChange = { viewModel.setReportAdvancedParameters(it) },
        onSystemPromptChange = { viewModel.setReportSystemPromptId(it) },
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToTraceFile = onNavigateToTraceFile,
        onNavigateToTraceListFiltered = onNavigateToTraceListFiltered,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onRemoveAgent = { rid, aid -> reportViewModel.removeAgentFromReport(context, rid, aid) },
        onRegenerateAgent = { rid, aid -> reportViewModel.regenerateAgent(context, rid, aid) },
        onClearExternalInstructions = viewModel::clearExternalInstructions,
        onEditModels = { rid -> scope.launch { reportViewModel.prepareEditModels(context, rid) } },
        onUpdateModelList = { rid, edited ->
            scope.launch { reportViewModel.stageModelListForRegenerate(context, rid, edited) }
        },
        onMarkParametersChanged = {
            viewModel.updateUiState { it.copy(hasPendingParametersChange = true) }
        },
        onRegenerate = { rid -> reportViewModel.regenerateReport(context, rid) },
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
            reportViewModel.startTranslation(context, sourceId, langName, langNative, prov, model)
        },
        translationLifecycle = TranslationLifecycleCallbacks(
            onCancelRun = { runId -> reportViewModel.cancelTranslation(runId) },
            onCancelItem = { runId, itemId -> reportViewModel.cancelTranslationItem(runId, itemId) },
            onConsumeRun = { runId -> reportViewModel.consumeTranslationRun(runId) }
        ),
        onContinueWithCurrent = onContinueWithCurrent,
        onContinueWithAgentPicker = onContinueWithAgentPicker,
        onContinueWithOnTheFly = onContinueWithOnTheFly,
        onChatWithReportPrompt = onChatWithReportPrompt,
        onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
        onNavigateToAgentsEdit = onNavigateToAgentsEdit,
        onNavigateToFlocksEdit = onNavigateToFlocksEdit,
        onNavigateToSwarmsEdit = onNavigateToSwarmsEdit,
        onNavigateToInternalPromptsByCategory = onNavigateToInternalPromptsByCategory,
        onRecordRecentReportModel = { providerId, model ->
            viewModel.recordRecentReportModel(providerId, model)
        },
        onResumeStaleFanOut = { rid, mp ->
            reportViewModel.resumeStaleFanOutPairs(context, rid, mp)
        },
        onResumeStaleRuns = { rid ->
            reportViewModel.resumeStaleRunsForReport(context, rid)
        },
        onRestartFailedTranslations = { rid, runId ->
            reportViewModel.restartFailedTranslations(context, rid, runId)
        },
        onRemoveFailedTranslations = { rid, runId ->
            reportViewModel.removeFailedTranslations(context, rid, runId)
        },
        onRestartAllTranslations = { rid, runId ->
            reportViewModel.restartAllTranslations(context, rid, runId)
        },
        onStartMissingTranslations = { rid, runId ->
            reportViewModel.startMissingTranslations(context, rid, runId)
        },
        onRestartFailedFanOut = { rid, mp ->
            reportViewModel.rerunFailedFanOutPairs(context, rid, mp)
        },
        onRemoveFailedFanOut = { rid, mp ->
            reportViewModel.removeFailedFanOutPairs(context, rid, mp)
        },
        onRestartFailedFanOutForModel = { rid, mp, prov, mdl ->
            reportViewModel.rerunFailedFanOutPairsForModel(context, rid, mp, prov, mdl)
        },
        onRemoveFailedFanOutForModel = { rid, mp, prov, mdl ->
            reportViewModel.removeFailedFanOutPairsForModel(context, rid, mp, prov, mdl)
        },
        onRerunCompleteFanOut = { rid, mp ->
            reportViewModel.rerunCompleteFanOut(context, rid, mp)
        },
        onRerunFanOutPair = { rid, mp, pair ->
            reportViewModel.rerunSingleFanOutPair(context, rid, mp, pair)
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

/** Three translation-run lifecycle callbacks (cancel-run /
 *  cancel-item / consume-run) bundled so [ReportsScreen]'s parameter
 *  list stays under the JVM 64 KB per-method bytecode limit. Wired
 *  by [ReportsScreenNav] to `ReportViewModel.cancelTranslation /
 *  cancelTranslationItem / consumeTranslationRun`. */
data class TranslationLifecycleCallbacks(
    val onCancelRun: (String) -> Unit = { _ -> },
    val onCancelItem: (String, String) -> Unit = { _, _ -> },
    val onConsumeRun: (String) -> Unit = { _ -> }
)

/** Four translation-icon callbacks plumbed through [ReportsScreen]
 *  as a single parameter so the Composable's parameter list stays
 *  under the JVM 64 KB per-method bytecode limit. Wired by
 *  [ReportsScreenNav] to the matching `ReportViewModel.*TranslationIcon*`
 *  methods. */
data class TranslationIconCallbacks(
    val onKickoff: (String) -> Unit = { _ -> },
    val onStartFanOut: (String, List<ReportModel>) -> Unit = { _, _ -> },
    val onPick: (String, IconCandidate.Done) -> Unit = { _, _ -> },
    val onRestartFanOut: (String) -> Unit = { _ -> }
)

/** Four per-`InternalPrompt` icon callbacks plumbed through
 *  [ReportsScreen] as a single parameter — same rationale as
 *  [TranslationIconCallbacks]. Wired by [ReportsScreenNav] to
 *  `ReportViewModel.kickOffInternalPromptIcon /
 *  startInternalPromptIconFanOut / pickInternalPromptIcon /
 *  restartInternalPromptIconFanOut`. */
data class InternalPromptIconCallbacks(
    val onKickoff: (com.ai.model.InternalPrompt) -> Unit = { _ -> },
    val onStartFanOut: (com.ai.model.InternalPrompt, List<ReportModel>) -> Unit = { _, _ -> },
    val onPick: (com.ai.model.InternalPrompt, IconCandidate.Done) -> Unit = { _, _ -> },
    val onRestartFanOut: (com.ai.model.InternalPrompt) -> Unit = { _ -> }
)

// ===== Main Reports Screen =====

@Composable
fun ReportsScreen(
    uiState: UiState,
    reportsAgentResults: Map<String, AnalysisResponse>,
    runningFanOutPairs: Set<String> = emptySet(),
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
    /** Per-agent counterparts of the three icon-fan-out callbacks.
     *  Same shape but routed to [ReportViewModel.startAgentIconFanOut]
     *  /pickAgentIcon/restartAgentIconFanOut, which target a single
     *  [ReportAgent.icon] instead of [Report.icon]. */
    onStartAgentIconFanOut: (reportId: String, agentId: String, models: List<ReportModel>) -> Unit = { _, _, _ -> },
    onPickAgentIcon: (reportId: String, agentId: String, emoji: String) -> Unit = { _, _, _ -> },
    onRestartAgentIconFanOut: (reportId: String, agentId: String) -> Unit = { _, _ -> },
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
    /** Persist the per-report system prompt picked in SelectionPhase
     *  onto UiState so the dispatch sees it at generation time. */
    onSystemPromptChange: (String?) -> Unit = {},
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
    onRunFanOut: (String, com.ai.model.InternalPrompt, com.ai.data.SecondaryScope, Set<String>?) -> Unit = { _, _, _, _ -> },
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
    /** Delete every row of the named translation run and dispatch
     *  the full set fresh, throttled by the runner's Semaphore(3).
     *  Wired to ReportViewModel.restartAllTranslations. */
    onRestartAllTranslations: (String, String) -> Unit = { _, _ -> },
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
    var reportIconCost by remember { mutableStateOf(0.0) }
    var reportIconModel by remember { mutableStateOf<String?>(null) }
    // Per-agent snapshot for the inline result list and the
    // per-agent icon detail overlay. Sourced from each ReportAgent
    // on disk, keyed by agentId. Same iconRefreshTick gates this
    // and the report-level mirror above so a single ViewModel ping
    // picks up both — Create → Report icons writes through
    // updateReportAgentIcon and bumps the tick, which rebuilds this
    // map. agentIconRows + agentRecordsByAgentId share the same
    // disk read so we don't double-IO when the overlay opens.
    var agentIconRows by remember { mutableStateOf<Map<String, AgentIconRow>>(emptyMap()) }
    var agentRecordsByAgentId by remember { mutableStateOf<Map<String, com.ai.data.ReportAgent>>(emptyMap()) }
    // The saved Report's prompt — source of truth for the @PROMPT@
    // substitution on the per-agent icon detail. Pulled from the same
    // IO read so the overlay doesn't have to refetch.
    var loadedReportPrompt by remember { mutableStateOf("") }
    // Report title — provided down the composition tree via
    // LocalReportTitle so deep TitleBars in BOTH-mode without a
    // per-screen subject can fall back to it.
    var loadedReportTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentReportId, uiState.iconRefreshTick) {
        val rid = currentReportId
        if (rid == null) {
            reportIcon = null
            reportIconError = null
            reportIconCost = 0.0
            reportIconModel = null
            agentIconRows = emptyMap()
            agentRecordsByAgentId = emptyMap()
            loadedReportPrompt = ""
            loadedReportTitle = null
        } else {
            val r = withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, rid) }
            reportIcon = r?.icon
            reportIconError = r?.iconErrorMessage
            reportIconCost = (r?.iconInputCost ?: 0.0) + (r?.iconOutputCost ?: 0.0)
            reportIconModel = r?.iconModel
            agentIconRows = r?.agents?.associate { ra ->
                ra.agentId to AgentIconRow(ra.icon, ra.iconInputCost + ra.iconOutputCost)
            } ?: emptyMap()
            agentRecordsByAgentId = r?.agents?.associate { ra -> ra.agentId to ra } ?: emptyMap()
            loadedReportPrompt = r?.prompt.orEmpty()
            loadedReportTitle = r?.title
        }
    }
    // Provided to every inline overlay below via LocalReportIcon so
    // pickers / viewer / detail screens (which don't have the Report
    // object in scope) can still render the leftmost report-icon glyph
    // in their TitleBar. Falls back to 📝 while icon-gen is in flight
    // or after it errored. Null when there's no current report OR the
    // user disabled the icon-gen feature entirely.
    val iconGenEnabled = uiState.generalSettings.iconGenEnabled
    val effectiveReportIcon = if (iconGenEnabled && currentReportId != null) (reportIcon?.takeIf { it.isNotEmpty() } ?: "📝") else null
    // Bumped from every overlay-driven delete so the parent screen's
    // counts / row list re-read from disk on the way back. Without
    // this the LaunchedEffect below has no reason to refire (the user
    // didn't change report id, completion state, or batch count) and
    // the deleted row keeps showing in the inline meta-runs list and
    // the View counters above it.
    var secondaryRefreshTick by remember { mutableStateOf(0) }
    // Auto-resume sweep per report open. Re-launches every Translation
    // run, Fan-out pair, and single-call Meta/Rerank/Moderation row
    // that an app kill left mid-flight (blank content, null
    // errorMessage, null durationMs, no in-memory claim). Anything we
    // can't reconstruct from disk (legacy rows missing fields, prompts
    // since deleted, fan-in single rows) falls back to the "Interrupted
    // by app restart" marker so it stops spinning. The bump on
    // secondaryRefreshTick forces the polling LaunchedEffect below to
    // immediately re-read disk and pick up both the new ⏳ placeholders
    // (fresh dispatch) and any ❌ markers.
    LaunchedEffect(currentReportId) {
        val rid = currentReportId ?: return@LaunchedEffect
        onResumeStaleRuns(rid)
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
                finishedSignature.forEach { translationLifecycle.onConsumeRun(it) }
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
    // View → Icons overlay state. Surfaces a tiny screen rendering
    // every agent's emoji (from the 3-tier per-model icon chain) at
    // very large font. Button gated on perModelIconGenEnabled at
    // GenerationPhase level — this state stays false when the toggle
    // is off so the overlay can never be invoked through other paths.
    var showIconsView by rememberSaveable { mutableStateOf(false) }
    var showIconDetail by rememberSaveable { mutableStateOf(false) }
    var agentIconDetailFor by rememberSaveable { mutableStateOf<String?>(null) }
    var showFindIconsPicker by rememberSaveable { mutableStateOf(false) }
    var showAlternativeIcons by rememberSaveable { mutableStateOf(false) }
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
    var promptIconDetailForId by rememberSaveable { mutableStateOf<String?>(null) }
    // ── Translation-icon flow. Sibling of `promptIconDetailForId`
    // — when non-null, the user tapped the cached emoji on a
    // translation summary row and is now drilled into the
    // Translation-icon detail screen. Routes the shared
    // showAlternativeIcons / showFindIconsPicker blocks through
    // the per-language path; the candidate list reuses
    // `internalPromptIconFanOutByPrompt` keyed by the synthetic
    // `"translation_icon" + U+001F + language` join.
    var translationIconLanguageFor by rememberSaveable { mutableStateOf<String?>(null) }
    // Non-null when the active "Find / View alternative icons" flow
    // is per-agent (reached from AgentIconDetailScreen). Drives the
    // Find-icons picker + Alternative-icons screen blocks to dispatch
    // through the agent-targeted callbacks instead of the report-
    // level ones, and to read from agentIconFanOutByAgent instead of
    // iconFanOutByReport. Cleared on pick or on agent detail back.
    var fanOutTargetAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var findIconsModels by rememberSaveable(stateSaver = ReportModelListSaver) { mutableStateOf(emptyList<ReportModel>()) }
    // Which model-picker target the next +Add overlay confirm should
    // deposit into. NEW_REPORT = the SelectionPhase's `models` list
    // (existing behavior); FIND_ICONS = the [findIconsModels] list
    // backing the "Find icons" picker overlay. The +Add overlays
    // (showSelectAgent / showSelectFlock / showSelectSwarm /
    // showSelectAllModels / showSelectFromReport) are shared between
    // the two flows; this flag is what decides where the picked rows
    // land. Reset to NEW_REPORT whenever the find-icons picker closes
    // so a later New-Report +Add doesn't leak the find-icons target.
    var pickerTarget by remember { mutableStateOf(PickerTarget.NEW_REPORT) }
    var selectedAgentForViewer by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerSection by rememberSaveable { mutableStateOf<String?>(null) }
    // Per-row click → focused single-model viewer. Distinct from the
    // multi-agent ReportsViewerScreen reached via View → Results.
    var singleResultAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    // Tracks the in-app HTML preview's selected detail level. Null →
    // not shown. Default-COMPLETE when the action-row HTML button
    // opens it; the Export screen's "View in app" pipes its own
    // detail picker through.
    var htmlPreviewDetail by rememberSaveable { mutableStateOf<ReportExportDetail?>(null) }
    var showEditPrompt by rememberSaveable { mutableStateOf(false) }
    var showEditTitle by rememberSaveable { mutableStateOf(false) }
    var showEditParameters by rememberSaveable { mutableStateOf(false) }
    var showAdvancedParameters by rememberSaveable { mutableStateOf(false) }
    // Translate flow state.
    var showTranslateLanguagePicker by rememberSaveable { mutableStateOf(false) }
    var showTranslateModelPicker by rememberSaveable(stateSaver = TargetLanguageSaver) { mutableStateOf<TargetLanguage?>(null) }
    // rememberSaveable so a nav-hop out of AI_REPORTS (Model Info from
    // a selected-models row, Help, etc.) and back doesn't clear the
    // selection — the user was just inspecting one row, not abandoning
    // the picker.
    var models by rememberSaveable(stateSaver = ReportModelListSaver) { mutableStateOf(initialModels) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }
    var showInfoPicker by rememberSaveable { mutableStateOf(false) }
    // Action-row pickers — hoisted up here so they render as proper
    // full-screen overlays. When they lived inside GenerationPhase the
    // parent TitleBar + ActionRow had already painted above them, so
    // the picker visibly stacked on top of a half-drawn screen. View
    // and Edit no longer drill into a full-screen picker — the new
    // two-row action bar exposes their sub-actions inline.
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
    // Grid render is gated on singleResultAgentId == null so a tap on
    // an icon (which sets singleResultAgentId without clearing
    // showIconsView) falls through to the Model response overlay
    // below. Back from Model response clears singleResultAgentId and
    // the grid re-renders — Android back lands on the grid instead of
    // popping all the way out to the result screen.
    if (showIconsView && singleResultAgentId == null && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides { showIconsView = false }
        ) {
            ReportIconsGridScreen(
                reportId = currentReportId,
                onOpenAgent = { agentId -> singleResultAgentId = agentId },
                onBack = { showIconsView = false }
            )
        }
        return
    }
    if (showViewer && currentReportId != null) {
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showViewer = false; viewerSection = null }) {
            ReportsViewerScreen(
                reportId = currentReportId,
                initialSelectedAgentId = selectedAgentForViewer,
                initialSection = viewerSection,
                onDismiss = { showViewer = false; viewerSection = null },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onContinueWithCurrent = onContinueWithCurrent,
                onContinueWithAgentPicker = onContinueWithAgentPicker,
                onContinueWithOnTheFly = onContinueWithOnTheFly,
                onRemoveAgent = { rid, aid ->
                    onRemoveAgent(rid, aid)
                    secondaryRefreshTick++
                },
                onRegenerateAgent = onRegenerateAgent
            )
        }
        return
    }
    val singleAgentId = singleResultAgentId
    // Layer-don't-replace: when agentIconDetailFor is set FROM the
    // model-response screen (via the big-icon tap), keep
    // singleResultAgentId alive in the back stack and let the
    // agentIconDetailFor block lower down render its overlay on top.
    // Back from the Agent Icon screen clears only agentIconDetailFor,
    // re-renders this branch, and the user lands back on Model response.
    if (singleAgentId != null && currentReportId != null && agentIconDetailFor == null) {
        // navigate-to-report shortcut (top-left report-icon tap) skips
        // every layered overlay above the result screen. Clears the
        // icons-grid flag in addition to singleResultAgentId so a user
        // who reached Model response via View → Icons lands on the
        // result screen in one tap, not back at the grid.
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { singleResultAgentId = null; showIconsView = false }) {
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
                onContinueWithOnTheFly = onContinueWithOnTheFly,
                onOpenAgentIcon = { aid -> agentIconDetailFor = aid }
            )
        }
        return
    }
    if (showAdvancedParameters) {
        ReportAdvancedParametersScreen(currentParameters = advancedParameters, onApply = { onAdvancedParametersChange(it); showAdvancedParameters = false }, onBack = { showAdvancedParameters = false })
        return
    }

    // Deposit picked rows into whichever list the active picker is
    // bound to. The same overlays serve the New-Report SelectionPhase
    // and the Find-icons picker; [pickerTarget] is what decides.
    val addToActiveTarget: (List<ReportModel>) -> Unit = { added ->
        when (pickerTarget) {
            PickerTarget.NEW_REPORT -> models = deduplicateModels(models + added)
            PickerTarget.FIND_ICONS -> findIconsModels = deduplicateModels(findIconsModels + added)
        }
    }

    // Selection overlay dialogs
    if (showSelectFlock) {
        ReportSelectFlockScreen(
            aiSettings = aiSettings,
            onSelectFlock = {
                addToActiveTarget(expandFlockToModels(it, aiSettings))
                showSelectFlock = false
            },
            onNavigateToModelInfo = onNavigateToModelInfo,
            onBack = { showSelectFlock = false },
            onEditFlocks = onNavigateToFlocksEdit
        )
        return
    }
    if (showSelectAgent) {
        ReportSelectAgentScreen(
            aiSettings = aiSettings,
            onSelectAgent = {
                expandAgentToModel(it, aiSettings)?.let { m -> addToActiveTarget(listOf(m)) }
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
                addToActiveTarget(expandSwarmToModels(it, aiSettings))
                showSelectSwarm = false
            },
            onNavigateToModelInfo = onNavigateToModelInfo,
            onBack = { showSelectSwarm = false },
            onEditSwarms = onNavigateToSwarmsEdit
        )
        return
    }
    if (showSelectProvider) { ReportSelectProviderDialog(aiSettings, onSelectProvider = { pendingProvider = it; showSelectProvider = false }, onDismiss = { showSelectProvider = false }); return }
    if (pendingProvider != null) {
        val prov = pendingProvider!!
        val recentForProv = remember(uiState.generalSettings.recentReportModels, prov) {
            recentReportPairs.filter { it.first == prov }.map { it.second }
        }
        ReportSelectModelDialog(
            prov, aiSettings,
            onSelectModel = {
                addToActiveTarget(listOf(toReportModel(prov, it)))
                pendingProvider = null
            },
            onDismiss = { pendingProvider = null },
            recentModels = recentForProv,
            onRecordRecent = { onRecordRecentReportModel(prov.id, it) }
        )
        return
    }
    if (showSelectAllModels) {
        // The "already added" hint dims rows that are already in the
        // active picker's list — for FIND_ICONS that's findIconsModels,
        // not the New-Report models list.
        val activeList = if (pickerTarget == PickerTarget.FIND_ICONS) findIconsModels else models
        val already = remember(activeList) { activeList.map { it.provider to it.model }.toSet() }
        ReportSelectModelsScreen(
            aiSettings = aiSettings,
            alreadyAdded = already,
            recentEntries = recentReportPairs,
            onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
            onConfirm = { (prov, m) ->
                addToActiveTarget(listOf(toReportModel(prov, m)))
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
                addToActiveTarget(copied)
                showSelectFromReport = false
            },
            onBack = { showSelectFromReport = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Icon-flow overlays. Must sit AFTER the +Add overlays above:
    // when the Find icons picker is open and the user taps +Model /
    // +Agent / +Flock / +Swarm / +Report, those overlays flip a
    // showSelect* flag — that flag's overlay block needs to win the
    // overlay race so the picker actually appears. If we checked the
    // icon overlays first, showFindIconsPicker would always win and
    // every +Add tap would be a silent no-op (the active overlay
    // would just re-render itself). Innermost (Alternative icons)
    // checked first so its `return` short-circuits before the parent
    // picker / icon-detail blocks run.
    // showAlternativeIcons overlay — extracted to AlternativeIconsRouter
    // below so the ReportsScreen Composable stays within the JVM
    // 64 KB per-method bytecode limit.
    if (showAlternativeIcons && currentReportId != null) {
        AlternativeIconsRouter(
            reportId = currentReportId,
            targetLanguage = translationIconLanguageFor,
            targetPromptId = promptIconDetailForId,
            targetAgentId = fanOutTargetAgentId,
            internalPrompts = aiSettings.internalPrompts,
            internalPromptIconFanOutByPrompt = internalPromptIconFanOutByPrompt,
            agentIconFanOutByAgent = agentIconFanOutByAgent,
            iconFanOutByReport = iconFanOutByReport,
            translationIconCallbacks = translationIconCallbacks,
            onPickInternalPromptIcon = promptIconCallbacks.onPick,
            onPickAgentIcon = onPickAgentIcon,
            onPickAlternativeIcon = onPickAlternativeIcon,
            onRestartInternalPromptIconFanOut = promptIconCallbacks.onRestartFanOut,
            onRestartAgentIconFanOut = onRestartAgentIconFanOut,
            onRestartIconFanOut = onRestartIconFanOut,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onCloseAll = {
                showAlternativeIcons = false
                showFindIconsPicker = false
                showIconDetail = false
                agentIconDetailFor = null
                fanOutTargetAgentId = null
                promptIconDetailForId = null
                translationIconLanguageFor = null
            },
            onRestartReopenPicker = {
                findIconsModels = emptyList()
                showAlternativeIcons = false
                showFindIconsPicker = true
            },
            onClose = { showAlternativeIcons = false }
        )
        return
    }
    if (showFindIconsPicker && currentReportId != null) {
        FindIconsPickerRouter(
            reportId = currentReportId,
            targetLanguage = translationIconLanguageFor,
            targetPromptId = promptIconDetailForId,
            targetAgentId = fanOutTargetAgentId,
            internalPrompts = aiSettings.internalPrompts,
            aiSettings = aiSettings,
            models = findIconsModels,
            genericPromptText = uiState.genericPromptText,
            translationIconCallbacks = translationIconCallbacks,
            onStartInternalPromptIconFanOut = promptIconCallbacks.onStartFanOut,
            onStartAgentIconFanOut = onStartAgentIconFanOut,
            onStartIconFanOut = onStartIconFanOut,
            onAddAgent = { pickerTarget = PickerTarget.FIND_ICONS; showSelectAgent = true },
            onAddFlock = { pickerTarget = PickerTarget.FIND_ICONS; showSelectFlock = true },
            onAddSwarm = { pickerTarget = PickerTarget.FIND_ICONS; showSelectSwarm = true },
            onAddFromReport = { pickerTarget = PickerTarget.FIND_ICONS; showSelectFromReport = true },
            onAddAllModels = { pickerTarget = PickerTarget.FIND_ICONS; showSelectAllModels = true },
            onRemoveModel = { idx -> findIconsModels = findIconsModels.toMutableList().apply { removeAt(idx) } },
            onClearAll = { findIconsModels = emptyList() },
            onConfirm = {
                findIconsModels = emptyList()
                pickerTarget = PickerTarget.NEW_REPORT
                showFindIconsPicker = false
                showAlternativeIcons = true
            },
            onBack = {
                pickerTarget = PickerTarget.NEW_REPORT
                showFindIconsPicker = false
            }
        )
        return
    }
    if (showIconDetail && currentReportId != null) {
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "icon"
        }
        val iconAgent = iconPrompt?.let { p ->
            aiSettings.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) }
        }
        if (iconPrompt != null && iconAgent != null) {
            val rid = currentReportId
            val hasActiveFanOut = iconFanOutByReport[rid].orEmpty().isNotEmpty()
            CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showIconDetail = false }) {
                ReportIconDetailScreen(
                    aiSettings = aiSettings,
                    iconPrompt = iconPrompt,
                    iconAgent = iconAgent,
                    promptText = uiState.genericPromptText,
                    icon = reportIcon,
                    errorMessage = reportIconError,
                    cost = reportIconCost,
                    iconModel = reportIconModel,
                    onFindAlternativeIcons = {
                        // When there's already an in-flight or finished
                        // fan-out for this report, the button skips the
                        // picker and jumps straight to the live list.
                        if (hasActiveFanOut) {
                            showAlternativeIcons = true
                        } else {
                            showFindIconsPicker = true
                        }
                    },
                    hasActiveFanOut = hasActiveFanOut,
                    onBack = { showIconDetail = false }
                )
            }
            return
        }
        // Icon prompt / agent missing — nothing to show. Fall through.
        showIconDetail = false
    }
    // Per-agent icon detail — reached from the leftmost emoji cell on
    // a row that Create → Report icons has populated. Same shape as
    // ReportIconDetailScreen but data is per-agent (the agent's own
    // (provider, model) ran the call, and the icon-prompt's @PROMPT@
    // was the agent's responseBody, not the report's prompt).
    agentIconDetailFor?.let { agentId ->
        val chatPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_icon_chat"
        }
        val tier2Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_icon"
        }
        val tier3Prompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "report_icon_3th"
        }
        val agent = agentRecordsByAgentId[agentId]
        val provider = agent?.let { AppService.findById(it.provider) }
        if (agent != null && provider != null) {
            val hasActiveAgentFanOut = agentIconFanOutByAgent[agentId].orEmpty().isNotEmpty()
            val agentIconTraceFilename = rememberAgentIconTrace(
                reportId = currentReportId,
                agentModel = agent.model,
                iconKey = agent.icon,
                winningTier = agent.iconWinningTier
            )
            CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
                LocalNavigateToCurrentReport provides {
                    agentIconDetailFor = null
                    fanOutTargetAgentId = null
                }
            ) {
                AgentIconDetailScreen(
                    chatPrompt = chatPrompt,
                    tier2Prompt = tier2Prompt,
                    tier3Prompt = tier3Prompt,
                    agentProvider = provider,
                    agentModel = agent.model,
                    reportPrompt = loadedReportPrompt,
                    agentResponse = agent.responseBody.orEmpty(),
                    icon = agent.icon,
                    errorMessage = agent.iconErrorMessage,
                    cost = agent.iconInputCost + agent.iconOutputCost,
                    winningTier = agent.iconWinningTier,
                    onFindAlternativeIcons = {
                        fanOutTargetAgentId = agentId
                        // Same hand-off pattern as the report-level button:
                        // when a fan-out already exists for this target,
                        // skip the picker and go straight to the live list.
                        if (hasActiveAgentFanOut) {
                            showAlternativeIcons = true
                        } else {
                            showFindIconsPicker = true
                        }
                    },
                    hasActiveFanOut = hasActiveAgentFanOut,
                    traceFilename = agentIconTraceFilename,
                    onNavigateToTraceFile = onNavigateToTraceFile,
                    onBack = {
                        agentIconDetailFor = null
                        fanOutTargetAgentId = null
                    }
                )
            }
            return
        }
        // Missing icon prompt / unknown agent / mirror not yet
        // hydrated — drop the overlay and fall through so we don't
        // sit on a blank screen.
        agentIconDetailFor = null
    }

    // Per-prompt Meta-icon detail — extracted to MetaIconDetailOverlay
    // below to keep ReportsScreen's bytecode under the JVM 64 KB
    // per-method limit.
    if (promptIconDetailForId != null) {
        val handled = MetaIconDetailOverlay(
            promptId = promptIconDetailForId!!,
            iconRefreshTick = uiState.iconRefreshTick,
            internalPrompts = aiSettings.internalPrompts,
            fanOutCandidates = internalPromptIconFanOutByPrompt,
            effectiveReportIcon = effectiveReportIcon,
            loadedReportTitle = loadedReportTitle,
            onOpenAlternativeIcons = { hasActive ->
                if (hasActive) showAlternativeIcons = true
                else showFindIconsPicker = true
            },
            onClose = { promptIconDetailForId = null }
        )
        if (handled) return
        // Prompt was deleted while the overlay was open — drop
        // the state so we don't sit on a blank screen.
        promptIconDetailForId = null
    }

    // Per-translation Translation-icon detail — extracted to a
    // helper composable below to keep ReportsScreen's bytecode
    // under the JVM 64 KB per-method limit.
    if (translationIconLanguageFor != null) {
        TranslationIconDetailOverlay(
            language = translationIconLanguageFor!!,
            iconRefreshTick = uiState.iconRefreshTick,
            fanOutCandidates = internalPromptIconFanOutByPrompt,
            effectiveReportIcon = effectiveReportIcon,
            loadedReportTitle = loadedReportTitle,
            onOpenAlternativeIcons = { hasActive ->
                if (hasActive) showAlternativeIcons = true
                else showFindIconsPicker = true
            },
            onClose = { translationIconLanguageFor = null }
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
        // Load the successful agent list off-thread so the confirmation
        // body can render two per-side selection cards over it. The
        // pre-feature counts object is gone — pair count is derived
        // live from the user's checkbox state instead.
        val successfulState = produceState<List<com.ai.data.ReportAgent>?>(initialValue = null, rid) {
            value = withContext(Dispatchers.IO) {
                com.ai.data.ReportStorage.getReport(context, rid)?.agents?.filter {
                    it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                }
            }
        }
        val successful = successfulState.value
        // Two checkbox sets — initiator (= source, whose response feeds
        // @RESPONSE@) and responder (= answerer, receives the assembled
        // prompt). Re-key on the agent-id list so a fresh report reseeds
        // the defaults instead of carrying a stale subset.
        val allIds = remember(successful) { successful?.map { it.agentId }?.toSet() ?: emptySet() }
        var selectedInitiators by remember(allIds) { mutableStateOf(allIds) }
        var selectedResponders by remember(allIds) { mutableStateOf(allIds) }
        // Self-pairs are skipped at the runner — match the dispatch
        // logic exactly so the displayed count never disagrees with
        // what actually lands as placeholders.
        val pairCount = selectedInitiators.sumOf { init ->
            selectedResponders.count { resp -> resp != init }
        }
        fun agentLabel(a: com.ai.data.ReportAgent): String =
            a.agentName.takeIf { it.isNotBlank() } ?: "${a.provider} · ${a.model}"
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
                    "Running ${fanOutMp.name} fires the prompt once per (responder, initiator) pair. Each call substitutes the initiator's response into @RESPONSE@ and sends the assembled prompt to the responder. Self-pairs are skipped.",
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
                        if (successful == null) {
                            Text("Loading…", fontSize = 13.sp, color = AppColors.TextTertiary)
                        } else {
                            val gridText = "${selectedResponders.size} responder${if (selectedResponders.size == 1) "" else "s"} × ${selectedInitiators.size} initiator${if (selectedInitiators.size == 1) "" else "s"} = $pairCount call${if (pairCount == 1) "" else "s"}"
                            Text(
                                gridText, fontSize = 15.sp, color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                if (successful != null && successful.isNotEmpty()) {
                    // Two collapsible cards — both default collapsed
                    // and all-ticked. Each row is the whole agent line
                    // with a leading Checkbox; tap-target is the row,
                    // not just the checkbox, so it's friendly on
                    // touch. Header counts track the live ticked
                    // subset, not the total — that's the number the
                    // pair-count math actually uses.
                    com.ai.ui.shared.CollapsibleCard(
                        title = "Initiator models for this Fan-Out (${selectedInitiators.size})"
                    ) {
                        successful.forEach { agent ->
                            val checked = agent.agentId in selectedInitiators
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedInitiators = if (checked) selectedInitiators - agent.agentId
                                        else selectedInitiators + agent.agentId
                                }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    agentLabel(agent), fontSize = 12.sp, color = Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    com.ai.ui.shared.CollapsibleCard(
                        title = "Responder models for this Fan-out (${selectedResponders.size})"
                    ) {
                        successful.forEach { agent ->
                            val checked = agent.agentId in selectedResponders
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedResponders = if (checked) selectedResponders - agent.agentId
                                        else selectedResponders + agent.agentId
                                }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    agentLabel(agent), fontSize = 12.sp, color = Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
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
                        val initiators = selectedInitiators
                        val responders = selectedResponders
                        fanOutConfirmMetaPrompt = null
                        pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                        pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
                        // Scope-screen output is now overridden by the
                        // explicit checkbox state — Manual(initiators)
                        // is the source of truth for the initiator side.
                        onRunFanOut(
                            rid, mp,
                            com.ai.data.SecondaryScope.Manual(initiators),
                            responders
                        )
                    },
                    enabled = pairCount > 0,
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
            recentEntries = recentReportPairs,
            onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
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
            recentEntries = recentReportPairs,
            onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides {
            modelFanInActivePid = null
            modelFanInActiveMdl = null
            modelFanInPickerPrompt = null
        }) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "${modelFanInPicker.name} — pick model",
                modelTypeFilter = null,
                recentEntries = recentReportPairs,
                onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showTranslateLanguagePicker = false }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showTranslateModelPicker = null }) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "Pick translation model",
                recentEntries = recentReportPairs,
                onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showRerankPicker = false }) {
            ReportSelectModelsScreen(
                aiSettings = aiSettings,
                titleText = "Pick rerank model",
                recentEntries = recentReportPairs,
                onRecordRecent = { (p, m) -> onRecordRecentReportModel(p.id, m) },
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { openMetaResultId = null }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { openTranslationRunId = null }) {
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
                onRemoveFailed = { srcRid, runId ->
                    onRemoveFailedTranslations(srcRid, runId)
                    secondaryRefreshTick++
                },
                onRestartAll = { srcRid, runId ->
                    onRestartAllTranslations(srcRid, runId)
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
                onCancelRun = { id -> translationLifecycle.onCancelRun(id) },
                onCancelItem = { runId, itemId -> translationLifecycle.onCancelItem(runId, itemId) },
                onConsumeRun = { id -> translationLifecycle.onConsumeRun(id) }
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
            CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showFanInPromptPicker = false; listKind = null; listFilterByName = null }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { listKind = null; listFilterByName = null }) {
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
            onRemoveFailedFanOut = { mp ->
                onRemoveFailedFanOut(rid, mp)
                secondaryRefreshTick++
            },
            onRestartFailedFanOutForModel = { mp, prov, mdl ->
                onRestartFailedFanOutForModel(rid, mp, prov, mdl)
            },
            onRemoveFailedFanOutForModel = { mp, prov, mdl ->
                onRemoveFailedFanOutForModel(rid, mp, prov, mdl)
                secondaryRefreshTick++
            },
            onRerunCompleteFanOut = { mp ->
                onRerunCompleteFanOut(rid, mp)
                secondaryRefreshTick++
            },
            onRerunFanOutPair = { mp, pair ->
                onRerunFanOutPair(rid, mp, pair)
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
        if (mp.scope.equals("Default", ignoreCase = true)) {
            // Skip SecondaryScopeScreen — run against every report
            // agent (AllReports) and every present language (AllPresent),
            // the same defaults the scope screen's Continue would have
            // written for "All". Jump straight to the model picker.
            pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
            pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
            secondaryPickerMetaPrompt = mp
        } else {
            secondaryScopeMetaPrompt = mp
        }
    }
    // fan_out: same scope-field branch as launchMetaPrompt. "Default"
    // skips BOTH the scope picker AND the run-confirm screen — fan-out
    // runs across every (answerer, source) pair of successful report
    // agents immediately. "Select" preserves the prior behaviour of
    // routing through SecondaryScopeScreen → fanOutConfirmMetaPrompt.
    val launchFanOutPrompt: (com.ai.model.InternalPrompt) -> Unit = { mp ->
        val rid = currentReportId
        if (mp.scope.equals("Default", ignoreCase = true) && rid != null) {
            // Load the successful-agent list off-thread, then fire
            // onRunFanOut with Manual(all) on both sides — mirrors
            // what the confirm screen's Run button does with both
            // checkbox sets fully ticked (the default state there).
            scope.launch {
                val successful = withContext(Dispatchers.IO) {
                    com.ai.data.ReportStorage.getReport(context, rid)?.agents?.filter {
                        it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }.orEmpty()
                }
                val ids = successful.map { it.agentId }.toSet()
                pendingSecondaryScope = com.ai.data.SecondaryScope.AllReports
                pendingLanguageScope = com.ai.data.SecondaryLanguageScope.AllPresent
                if (ids.isEmpty()) {
                    // Nothing to fan out against — fall back to the
                    // confirm screen so the user sees the empty state
                    // and a Cancel button.
                    fanOutConfirmMetaPrompt = mp
                } else {
                    onRunFanOut(rid, mp, com.ai.data.SecondaryScope.Manual(ids), ids)
                }
            }
        } else {
            secondaryScopeMetaPrompt = mp
        }
    }

    if (showMetaScreen && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showMetaScreen = false }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showExport = false }) {
            ReportExportScreen(
                onBack = { showExport = false },
                onNavigateHome = onNavigateHome,
                onExport = { fmt, det, act, onProgress -> onExport(rid, fmt, det, act, onProgress) },
                onExportAll = { onProgress -> onExportAll(rid, onProgress) },
                onViewInApp = { det -> showExport = false; htmlPreviewDetail = det }
            )
        }
        return
    }

    if (htmlPreviewDetail != null && currentReportId != null) {
        val previewDetail = htmlPreviewDetail!!
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { htmlPreviewDetail = null }) {
            HtmlPreviewScreen(
                reportId = currentReportId,
                detail = previewDetail,
                onBack = { htmlPreviewDetail = null }
            )
        }
        return
    }

    if (showEditParameters) {
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showEditParameters = false }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showEditPrompt = false }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showEditTitle = false }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showMetaPicker = false }) {
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
        CompositionLocalProvider(com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon, com.ai.ui.shared.LocalReportTitle provides loadedReportTitle, LocalNavigateToCurrentReport provides { showFanOutPicker = false }) {
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

    // Main UI
    val foldSubject = com.ai.ui.shared.LocalSubjectToTitleBarMode.current != com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        // Static page title in the menu bar by default; the dynamic
        // prompt title surfaces as a green sub-header inside the body.
        // SUBJECT folds the prompt title into the bar; BOTH renders
        // subject left + "AI Report" right (no separator); HARDCODED
        // keeps just the static label and shows the green line below.
        val promptTitle = uiState.genericPromptTitle
        val barTitle = if (!isGenerating) "AI Report — Models" else "AI Report"
        TitleBar(
            helpTopic = "report_result_generation",
            title = barTitle,
            subject = if (isGenerating) promptTitle else null,
            // Resolved emoji renders as the absolute leftmost icon
            // when present; falls back to 📝 while icon-gen is in
            // flight or after it errored. Hidden during the selection
            // phase (no report yet).
            reportIcon = if (iconGenEnabled && isGenerating) (reportIcon?.takeIf { it.isNotEmpty() } ?: "📝") else null,
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
            } else null,
            // 📤: opens the existing format-picker export sheet (HTML
            // / PDF / Word / OpenDocument / JSON / Zipped HTML). The
            // in-action-row Export button used to do this — the icon
            // replaces it.
            onShare = if (isGenerating && currentReportId != null && isComplete) {
                { showExport = true }
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
        // Tight spacing into the action row on the result page —
        // the TitleBar already pads itself 8dp at the bottom, so an
        // extra full Spacer felt loose. 2dp keeps a visible seam
        // without leaving the buttons stranded below the bar. The
        // selection phase still has its own breathing room inside
        // SelectionPhase.
        Spacer(modifier = Modifier.height(if (isGenerating) 2.dp else 8.dp))

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
                onAttachKnowledgeBases = onAttachKnowledgeBases,
                selectedSystemPromptId = uiState.reportSystemPromptId,
                onSystemPromptChange = onSystemPromptChange
            )
        } else {
            // Generation phase
            val generationHandlers = GenerationPhaseHandlers(
                onViewAgent = { agentId -> singleResultAgentId = agentId },
                onShare = { showExport = true },
                onTrace = { currentReportId?.let(onNavigateToTrace) },
                onDelete = { showDeleteConfirm = true },
                onCopy = { currentReportId?.let(onCopyReport) },
                onTogglePin = { currentReportId?.let(onTogglePinReport) },
                onTranslate = { showTranslateLanguagePicker = true },
                onOpenMetaPicker = { showMetaPicker = true },
                onOpenFanOutPicker = { showFanOutPicker = true },
                onOpenRerankPicker = { showRerankPicker = true },
                onOpenHtmlPreview = { htmlPreviewDetail = ReportExportDetail.COMPLETE },
                onViewReports = {
                    selectedAgentForViewer = null; viewerSection = null; showViewer = true
                },
                onViewPrompt = {
                    selectedAgentForViewer = null; viewerSection = "prompt"; showViewer = true
                },
                onViewCosts = {
                    selectedAgentForViewer = null; viewerSection = "costs"; showViewer = true
                },
                onViewIcons = { showIconsView = true },
                onEditTitle = { showEditTitle = true },
                onEditPromptInline = { showEditPrompt = true },
                onEditModelsInline = { currentReportId?.let { onEditModels(it) } },
                onEditParametersInline = { showEditParameters = true },
                onRequestRegenerate = { showRegenerateConfirm = true },
                onRequestDelete = { showDeleteConfirm = true },
                onRequestExport = { showExport = true },
                onCancelTranslation = translationLifecycle.onCancelRun,
                onViewSecondaryName = { name, kind -> listKind = kind; listFilterByName = name },
                onOpenSecondaryRun = { id -> openMetaResultId = id },
                onOpenTranslationRun = { runId -> openTranslationRunId = runId },
                onOpenMeta = { showMetaScreen = true },
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToTraceListFiltered = onNavigateToTraceListFiltered,
                onOpenIconDetail = { showIconDetail = true },
                onOpenAgentIconDetail = { agentId -> agentIconDetailFor = agentId },
                onPrevReport = onPrevReport,
                onNextReport = onNextReport,
                onMissingPromptIcon = promptIconCallbacks.onKickoff,
                onOpenInternalPromptIconDetail = { prompt -> promptIconDetailForId = prompt.id },
                onMissingTranslationIcon = translationIconCallbacks.onKickoff,
                onOpenTranslationIconDetail = { language -> translationIconLanguageFor = language }
            )
            GenerationPhase(
                uiState = uiState,
                isComplete = isComplete,
                reportsProgress = reportsProgress,
                reportsTotal = reportsTotal,
                reportsAgentResults = reportsAgentResults,
                currentReportId = currentReportId,
                handlers = generationHandlers,
                secondaryCounts = secondaryCounts,
                costsFromDeletedItems = costsFromDeletedItems,
                secondaryRuns = secondaryRuns,
                secondaryTotals = secondaryTotals,
                translationRuns = translationRuns,
                translationRunSummaries = translationRunSummaries,
                fanOutSummaries = fanOutSummaries,
                metaPrompts = aiSettings.internalPrompts.filter { it.category.equals("meta", ignoreCase = true) },
                fanOutPrompts = aiSettings.internalPrompts.filter { it.category == "fan_out" },
                reportIcon = reportIcon,
                reportIconError = reportIconError,
                reportIconCost = reportIconCost,
                reportIconModel = reportIconModel,
                agentIconRows = agentIconRows,
                hasPrevReport = hasPrevReport,
                hasNextReport = hasNextReport
            )
        }
    }
}

/** Detail overlay reached by tapping the inline 'icon' row on the
 *  AI Report result page. Renders the model name used for the
 *  icon-gen call, the resolved prompt sent to that model, and the
 *  response (the emoji on success, the error string on failure). */
@Composable
private fun ReportIconDetailScreen(
    aiSettings: Settings,
    iconPrompt: InternalPrompt,
    iconAgent: Agent,
    promptText: String,
    icon: String?,
    errorMessage: String?,
    cost: Double,
    /** When non-null, the report has had its icon replaced via "Find
     *  alternative icons" — show this label instead of the bundled
     *  pinned agent's model. */
    iconModel: String?,
    /** Fires either the picker overlay (no active fan-out) or jumps
     *  straight to the live "Alternative icons" list (active /
     *  completed fan-out — see [hasActiveFanOut]). */
    onFindAlternativeIcons: () -> Unit,
    /** Controls the button label: "View alternative icons" while a
     *  fan-out exists for this report, "Find alternative icons"
     *  otherwise. */
    hasActiveFanOut: Boolean,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val effectiveModel = aiSettings.getEffectiveModelForAgent(iconAgent)
    val resolvedPrompt = iconPrompt.text.replace("@PROMPT@", promptText)
    val running = icon == null && errorMessage == null
    val modelLabel = iconModel ?: com.ai.ui.shared.modelLabel(iconAgent.provider.id, effectiveModel)
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "report_icon_detail",
            title = "Icon",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Model", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold)
                    Text(modelLabel, fontSize = 14.sp, color = Color.White)
                    if (cost > 0.0) {
                        Text("Cost: ${formatCents(cost)} ¢",
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Prompt", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    Text(resolvedPrompt, fontSize = 13.sp, color = Color.White,
                        lineHeight = 18.sp)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Response", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    when {
                        errorMessage != null -> Text(errorMessage,
                            fontSize = 13.sp, color = AppColors.Red, lineHeight = 18.sp)
                        running -> Text("(running…)",
                            fontSize = 13.sp, color = AppColors.TextTertiary)
                        icon != null -> Text(icon, fontSize = 36.sp, color = Color.White)
                        else -> Text("(no response)",
                            fontSize = 13.sp, color = AppColors.TextTertiary)
                    }
                }
            }

            Button(
                onClick = onFindAlternativeIcons,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) {
                Text(
                    if (hasActiveFanOut) "View alternative icons" else "Find alternative icons",
                    maxLines = 1, softWrap = false
                )
            }
        }
    }
}

/** Per-agent variant of [ReportIconDetailScreen] reached by tapping
 *  the emoji that replaces a row's ✅ once Create → Report icons has
 *  landed. Same three-card layout (Model / Prompt / Response) so the
 *  user's mental model carries over from the report-level icon; the
 *  Prompt card shows the icon template with @PROMPT@ substituted by
 *  THIS agent's responseBody (not the report's prompt), and the
 *  Model card identifies the agent that ran the call. No
 *  "Find alternative icons" affordance — per-agent icons don't have
 *  a fan-out variant by design. */

/** Resolve the most recent "Report icons tier N" trace file backing
 *  an agent's icon call. Tier 1 / 2 traces use the agent's own model;
 *  tier 3 falls back to a bundled icon agent so its trace's model is
 *  different — match on category-tier and only tighten by model on
 *  tiers 1/2. Null winningTier means the chain exhausted: surface the
 *  most recent "Report icons" trace regardless of tier so the user
 *  still has a path to the failure.
 *
 *  Extracted as a separate @Composable so the call site doesn't push
 *  the parent ReportsScreen over the JVM 64KB method-size limit. */
@Composable
private fun rememberAgentIconTrace(
    reportId: String?,
    agentModel: String,
    iconKey: String?,
    winningTier: Int?
): String? {
    val state = androidx.compose.runtime.produceState<String?>(
        initialValue = null, reportId, agentModel, iconKey, winningTier
    ) {
        value = withContext(Dispatchers.IO) {
            val candidates = ApiTracer.getTraceFiles().filter {
                it.reportId == reportId &&
                    it.category?.startsWith("Report icons ") == true
            }
            when (winningTier) {
                null -> candidates.maxByOrNull { it.timestamp }?.filename
                3 -> candidates.filter { it.category?.contains("tier 3") == true }
                    .maxByOrNull { it.timestamp }?.filename
                else -> candidates.filter {
                    it.model == agentModel &&
                        it.category?.contains("tier $winningTier") == true
                }.maxByOrNull { it.timestamp }?.filename
            }
        }
    }
    return state.value
}

@Composable
private fun AgentIconDetailScreen(
    /** The chat-continuation third-turn prompt (internal/report_icon_chat).
     *  Used as the Prompt card body when [winningTier] == 1. */
    chatPrompt: InternalPrompt?,
    /** The one-shot dual-substitution prompt (internal/report_icon).
     *  Used when [winningTier] == 2 (resolves @PROMPT@ + @RESPONSE@). */
    tier2Prompt: InternalPrompt?,
    /** The fixed-agent fallback prompt (internal/report_icon_3th).
     *  Used when [winningTier] == 3 (resolves @RESPONSE@ only). */
    tier3Prompt: InternalPrompt?,
    agentProvider: AppService,
    agentModel: String,
    /** The report's prompt — substituted for @PROMPT@ where the
     *  winning tier's template uses it. */
    reportPrompt: String,
    /** The agent's responseBody — substituted for @RESPONSE@.
     *  Falls back to "(no response)" when blank. */
    agentResponse: String,
    icon: String?,
    errorMessage: String?,
    cost: Double,
    /** 1 / 2 / 3 = the tier whose call produced the displayed icon.
     *  Null = no tier succeeded (icon is the 📝 fallback) OR icon
     *  was picked manually via Find alternative icons. */
    winningTier: Int?,
    /** Fires either the picker overlay (no active per-agent fan-out)
     *  or jumps straight to the live "Alternative icons" list
     *  (active / completed fan-out — see [hasActiveFanOut]). */
    onFindAlternativeIcons: () -> Unit,
    /** Controls the button label, parallel to the report-level Icon
     *  detail: "View alternative icons" while a per-agent fan-out
     *  exists, "Find alternative icons" otherwise. */
    hasActiveFanOut: Boolean,
    /** Filename of the most recent "Report icons tier N" trace that
     *  matches this agent's icon call (caller resolves it via the
     *  reportId / model / winningTier triple). Null hides the icon. */
    traceFilename: String? = null,
    onNavigateToTraceFile: (String) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val running = icon == null && errorMessage == null
    val responseForSubstitution = if (agentResponse.isNotBlank()) agentResponse else "(no response)"
    // Tier-aware prompt rendering. When the winning tier is known,
    // resolve THAT tier's template — the user gets the actual
    // prompt that produced this icon, not a stale guess.
    data class TierView(val label: String, val body: String)
    val tierView: TierView? = when (winningTier) {
        1 -> chatPrompt?.let {
            TierView(
                "Tier 1 — chat continuation",
                buildString {
                    append("[user] ")
                    append(reportPrompt)
                    append("\n\n[assistant] ")
                    append(responseForSubstitution)
                    append("\n\n[user] ")
                    append(it.text)
                }
            )
        }
        2 -> tier2Prompt?.let {
            TierView(
                "Tier 2 — one-shot template",
                it.text.replace("@PROMPT@", reportPrompt).replace("@RESPONSE@", responseForSubstitution)
            )
        }
        3 -> tier3Prompt?.let {
            TierView(
                "Tier 3 — fixed-agent fallback",
                it.text.replace("@RESPONSE@", responseForSubstitution)
            )
        }
        else -> null
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "agent_icon_detail",
            title = "Agent icon",
            onBackClick = onBack,
            onTrace = if (ApiTracer.isTracingEnabled && traceFilename != null) {
                { onNavigateToTraceFile(traceFilename) }
            } else null
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Model", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold)
                    Text(com.ai.ui.shared.modelLabel(agentProvider.id, agentModel),
                        fontSize = 14.sp, color = Color.White)
                    if (cost > 0.0) {
                        Text("Cost: ${formatCents(cost)} ¢",
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val label = tierView?.label ?: "Prompt"
                    Text(label, fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    Text(
                        tierView?.body
                            ?: "No tier succeeded — icon is the 📝 fallback (or was picked manually).",
                        fontSize = 13.sp, color = Color.White, lineHeight = 18.sp
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Response", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    when {
                        errorMessage != null -> Text(errorMessage,
                            fontSize = 13.sp, color = AppColors.Red, lineHeight = 18.sp)
                        running -> Text("(running…)",
                            fontSize = 13.sp, color = AppColors.TextTertiary)
                        icon != null -> Text(icon, fontSize = 36.sp, color = Color.White)
                        else -> Text("(no response)",
                            fontSize = 13.sp, color = AppColors.TextTertiary)
                    }
                }
            }

            Button(
                onClick = onFindAlternativeIcons,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) {
                Text(
                    if (hasActiveFanOut) "View alternative icons" else "Find alternative icons",
                    maxLines = 1, softWrap = false
                )
            }
        }
    }
}

/** Full-screen "Meta icon" detail for an [InternalPrompt]. Reached
 *  by tapping the cached emoji that replaces ✅ on a successful
 *  secondary-result row. Renders three cards mirroring
 *  [AgentIconDetailScreen]:
 *    - **Model card** — provider/model that produced the displayed
 *      emoji + cumulative cost across initial generation + every
 *      alternative-icons candidate.
 *    - **Prompt card** — the resolved `internal/prompt_icon` text
 *      with `@NAME@` + `@TITLE@` substituted.
 *    - **Response card** — the raw API response (often a single
 *      glyph, but the body can carry prose around it) plus the
 *      resolved emoji at 36 sp.
 *  Bottom button toggles "Find alternative icons" vs "View
 *  alternative icons" based on [hasActiveFanOut]. */

/** Routing helper for the shared `FindIconsSelectionScreen` —
 *  picks the right onFindIcons dispatch based on which icon flow
 *  is active. Extracted out of `ReportsScreen` so the parent
 *  stays under the JVM 64 KB per-method bytecode limit. */
@Composable
private fun FindIconsPickerRouter(
    reportId: String,
    targetLanguage: String?,
    targetPromptId: String?,
    targetAgentId: String?,
    internalPrompts: List<com.ai.model.InternalPrompt>,
    aiSettings: Settings,
    models: List<ReportModel>,
    genericPromptText: String,
    translationIconCallbacks: TranslationIconCallbacks,
    onStartInternalPromptIconFanOut: (com.ai.model.InternalPrompt, List<ReportModel>) -> Unit,
    onStartAgentIconFanOut: (String, String, List<ReportModel>) -> Unit,
    onStartIconFanOut: (String, String, List<ReportModel>) -> Unit,
    onAddAgent: () -> Unit,
    onAddFlock: () -> Unit,
    onAddSwarm: () -> Unit,
    onAddFromReport: () -> Unit,
    onAddAllModels: () -> Unit,
    onRemoveModel: (Int) -> Unit,
    onClearAll: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val targetPrompt = targetPromptId?.let { id ->
        internalPrompts.firstOrNull { it.id == id }
    }
    FindIconsSelectionScreen(
        models = models,
        aiSettings = aiSettings,
        onAddAgent = onAddAgent,
        onAddFlock = onAddFlock,
        onAddSwarm = onAddSwarm,
        onAddFromReport = onAddFromReport,
        onAddAllModels = onAddAllModels,
        onRemoveModel = onRemoveModel,
        onClearAll = onClearAll,
        onFindIcons = {
            when {
                targetLanguage != null -> translationIconCallbacks.onStartFanOut(targetLanguage, models)
                targetPrompt != null -> onStartInternalPromptIconFanOut(targetPrompt, models)
                targetAgentId != null -> onStartAgentIconFanOut(reportId, targetAgentId, models)
                else -> onStartIconFanOut(reportId, genericPromptText, models)
            }
            onConfirm()
        },
        onBack = onBack
    )
}

/** Routing helper for the shared `AlternativeIconsScreen` — picks
 *  the right candidate list + onPick / onRestart callbacks based
 *  on which icon flow is active (per-translation > per-prompt >
 *  per-agent > per-report). Extracted out of `ReportsScreen` so
 *  the parent stays under the JVM 64 KB per-method bytecode
 *  limit. */
@Composable
private fun AlternativeIconsRouter(
    reportId: String,
    targetLanguage: String?,
    targetPromptId: String?,
    targetAgentId: String?,
    internalPrompts: List<com.ai.model.InternalPrompt>,
    internalPromptIconFanOutByPrompt: Map<String, List<IconCandidate>>,
    agentIconFanOutByAgent: Map<String, List<IconCandidate>>,
    iconFanOutByReport: Map<String, List<IconCandidate>>,
    translationIconCallbacks: TranslationIconCallbacks,
    onPickInternalPromptIcon: (com.ai.model.InternalPrompt, IconCandidate.Done) -> Unit,
    onPickAgentIcon: (String, String, String) -> Unit,
    onPickAlternativeIcon: (String, String, String) -> Unit,
    onRestartInternalPromptIconFanOut: (com.ai.model.InternalPrompt) -> Unit,
    onRestartAgentIconFanOut: (String, String) -> Unit,
    onRestartIconFanOut: (String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onCloseAll: () -> Unit,
    onRestartReopenPicker: () -> Unit,
    onClose: () -> Unit
) {
    val targetPrompt = targetPromptId?.let { id ->
        internalPrompts.firstOrNull { it.id == id }
    }
    val promptKey = targetPrompt?.let { it.name + "" + it.title }
    val translationKey = targetLanguage?.let { "translation_icon" + "" + it }
    val candidates = when {
        translationKey != null -> internalPromptIconFanOutByPrompt[translationKey].orEmpty()
        promptKey != null -> internalPromptIconFanOutByPrompt[promptKey].orEmpty()
        targetAgentId != null -> agentIconFanOutByAgent[targetAgentId].orEmpty()
        else -> iconFanOutByReport[reportId].orEmpty()
    }
    AlternativeIconsScreen(
        reportId = reportId,
        candidates = candidates,
        onPickIcon = { emoji, iconModel ->
            when {
                targetLanguage != null -> {
                    val cand = candidates.filterIsInstance<IconCandidate.Done>()
                        .firstOrNull { "${it.provider.id}/${it.model}" == iconModel }
                    if (cand != null) translationIconCallbacks.onPick(targetLanguage, cand)
                }
                targetPrompt != null -> {
                    val cand = candidates.filterIsInstance<IconCandidate.Done>()
                        .firstOrNull { "${it.provider.id}/${it.model}" == iconModel }
                    if (cand != null) onPickInternalPromptIcon(targetPrompt, cand)
                }
                targetAgentId != null -> onPickAgentIcon(reportId, targetAgentId, emoji)
                else -> onPickAlternativeIcon(reportId, emoji, iconModel)
            }
            onCloseAll()
        },
        onRestart = {
            when {
                targetLanguage != null -> translationIconCallbacks.onRestartFanOut(targetLanguage)
                targetPrompt != null -> onRestartInternalPromptIconFanOut(targetPrompt)
                targetAgentId != null -> onRestartAgentIconFanOut(reportId, targetAgentId)
                else -> onRestartIconFanOut(reportId)
            }
            onRestartReopenPicker()
        },
        onNavigateToTraceFile = onNavigateToTraceFile,
        onBack = onClose
    )
}

/** Helper composable for the per-Internal-Prompt Meta-icon
 *  detail overlay. Extracted out of `ReportsScreen` so the
 *  parent stays under the JVM 64 KB per-method bytecode limit.
 *  Returns true when the overlay rendered, false when the prompt
 *  id didn't resolve and the caller should drop the state. */
@Composable
private fun MetaIconDetailOverlay(
    promptId: String,
    iconRefreshTick: Int,
    internalPrompts: List<com.ai.model.InternalPrompt>,
    fanOutCandidates: Map<String, List<IconCandidate>>,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    onOpenAlternativeIcons: (Boolean) -> Unit,
    onClose: () -> Unit
): Boolean {
    val prompt = internalPrompts.firstOrNull { it.id == promptId } ?: return false
    val entry = remember(promptId, prompt.name, prompt.title, iconRefreshTick) {
        com.ai.data.InternalPromptIconCache.getEntry(prompt.name, prompt.title)
    }
    val promptKey = prompt.name + "" + prompt.title
    val hasActiveFanOut = fanOutCandidates[promptKey].orEmpty().isNotEmpty()
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides { onClose() }
    ) {
        InternalPromptIconDetailScreen(
            subject = prompt.name,
            title = "Meta icon",
            helpTopic = "internal_prompt_icon_detail",
            promptCardLabel = "Prompt — internal/prompt_icon",
            entry = entry,
            hasActiveFanOut = hasActiveFanOut,
            onFindAlternativeIcons = { onOpenAlternativeIcons(hasActiveFanOut) },
            onBack = onClose
        )
    }
    return true
}

/** Helper composable for the per-translation Meta-icon detail
 *  overlay. Extracted out of `ReportsScreen` so the parent stays
 *  under the JVM 64 KB per-method bytecode limit. Reads the
 *  cache entry by the synthetic `("translation_icon", language)`
 *  key, computes whether a per-language fan-out is in flight,
 *  and renders the generic [InternalPromptIconDetailScreen]. */
@Composable
private fun TranslationIconDetailOverlay(
    language: String,
    iconRefreshTick: Int,
    fanOutCandidates: Map<String, List<IconCandidate>>,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    onOpenAlternativeIcons: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val entry = remember(language, iconRefreshTick) {
        com.ai.data.InternalPromptIconCache.getEntry("translation_icon", language)
    }
    val translationKey = "translation_icon" + "" + language
    val hasActiveFanOut = fanOutCandidates[translationKey].orEmpty().isNotEmpty()
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides { onClose() }
    ) {
        InternalPromptIconDetailScreen(
            subject = language,
            title = "Translation icon",
            helpTopic = "translation_icon_detail",
            promptCardLabel = "Prompt — internal/translation_icon",
            entry = entry,
            hasActiveFanOut = hasActiveFanOut,
            onFindAlternativeIcons = { onOpenAlternativeIcons(hasActiveFanOut) },
            onBack = onClose
        )
    }
}

@Composable
private fun InternalPromptIconDetailScreen(
    /** Bar subject — for the per-prompt flow this is the
     *  `InternalPrompt.name`; for the per-translation flow it's the
     *  language name (e.g. "Dutch"). */
    subject: String,
    /** Bar title — "Meta icon" / "Translation icon" / …. */
    title: String,
    /** Help topic id keyed to this rendering context — one of
     *  `internal_prompt_icon_detail` / `translation_icon_detail`. */
    helpTopic: String,
    /** Label rendered on the Prompt card — "Prompt — internal/prompt_icon"
     *  / "Prompt — internal/translation_icon". Lets the caller match the
     *  underlying bundled prompt name. */
    promptCardLabel: String,
    entry: com.ai.data.InternalPromptIconCache.CacheEntry?,
    hasActiveFanOut: Boolean,
    onFindAlternativeIcons: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val running = entry == null
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = helpTopic,
            title = title,
            subject = subject,
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Model", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold)
                    val modelLabel = if (entry != null && entry.providerId.isNotBlank()) {
                        com.ai.ui.shared.modelLabel(entry.providerId, entry.model)
                    } else "(pending)"
                    Text(modelLabel, fontSize = 14.sp, color = Color.White)
                    val cost = (entry?.inputCost ?: 0.0) + (entry?.outputCost ?: 0.0)
                    if (cost > 0.0) {
                        Text("Cost: ${formatCents(cost)} ¢",
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(promptCardLabel,
                        fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    val body = when {
                        running -> "(generating…)"
                        entry!!.promptText.isNotBlank() -> entry.promptText
                        else -> "(prompt text not captured)"
                    }
                    Text(body, fontSize = 13.sp, color = Color.White, lineHeight = 18.sp)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Response", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp))
                    when {
                        running -> {
                            Text("⏳", fontSize = 36.sp, color = Color.White)
                            Text("(running…)", fontSize = 13.sp, color = AppColors.TextTertiary,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        else -> {
                            Text(entry!!.emoji, fontSize = 36.sp, color = Color.White)
                            if (entry.responseText.isNotBlank() && entry.responseText.trim() != entry.emoji) {
                                Text(entry.responseText,
                                    fontSize = 12.sp, color = AppColors.TextTertiary,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onFindAlternativeIcons,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
            ) {
                Text(
                    if (hasActiveFanOut) "View alternative icons" else "Find alternative icons",
                    maxLines = 1, softWrap = false
                )
            }
        }
    }
}

/** Minimal viewer: every agent's per-model icon (populated by the
 *  3-tier chain on [com.ai.data.ReportAgent.icon]) rendered at very
 *  large font and nothing else. Reached from View → Icons, gated by
 *  the perModelIconGenEnabled setting at the button level. Agents
 *  whose chain hasn't landed (icon null/blank) are skipped — the
 *  user only sees what actually resolved. Tapping a glyph routes to
 *  that agent's Model response page via [onOpenAgent]. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReportIconsGridScreen(reportId: String, onOpenAgent: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val reportState = produceState<com.ai.data.Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val report = reportState.value
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(helpTopic = "report_icons_grid", title = "Icons", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))
        val iconAgents = report?.agents.orEmpty().mapNotNull { a ->
            a.icon?.takeIf { it.isNotBlank() }?.let { a.agentId to it }
        }
        // BoxWithConstraints measures the available area so we can
        // pick the largest gap (16 → 0 dp) such that every emoji fits
        // without scrolling. iconW / iconH are approximations of an
        // emoji's bounding box at 72 sp — exact text metrics aren't
        // needed, we just want a proportional indicator of overflow.
        // If even the 0 dp gap overflows (lots of agents on a small
        // screen), fall back to a vertical scroll so the bottom rows
        // stay reachable instead of getting clipped off-screen.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val n = iconAgents.size.coerceAtLeast(1)
            val iconW = 80.dp
            val iconH = 90.dp
            val gapOptions = listOf(16.dp, 12.dp, 8.dp, 4.dp, 2.dp, 0.dp)
            val fittingGap = gapOptions.firstOrNull { g ->
                val perRow = ((maxWidth + g).value / (iconW + g).value).toInt().coerceAtLeast(1)
                val rows = (n + perRow - 1) / perRow
                val totalH = rows * iconH.value + (rows - 1).coerceAtLeast(0) * g.value
                totalH <= maxHeight.value
            }
            val gap = fittingGap ?: 0.dp
            val fits = fittingGap != null
            val flowContent: @Composable () -> Unit = {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    iconAgents.forEach { (agentId, glyph) ->
                        Text(
                            glyph, fontSize = 72.sp, color = Color.White,
                            modifier = Modifier.clickable { onOpenAgent(agentId) }
                        )
                    }
                }
            }
            if (fits) {
                // Centred render when the grid fits — preserves the
                // "single big emoji floats in the middle" look on
                // reports with few agents.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    flowContent()
                }
            } else {
                // Overflowing grid — wrap in a vertical scroll so the
                // user can swipe through every row. Top-aligned so the
                // first row sits flush with the title bar.
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                ) {
                    flowContent()
                }
            }
        }
    }
}
