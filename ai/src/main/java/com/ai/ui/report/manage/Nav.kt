package com.ai.ui.report.manage

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
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReportsScreenNav(
    viewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    /** Seed flag for the View tile-grid overlay. When true, the
     *  screen flips [showViewReportScreen] on first composition so
     *  the user lands on the View grid instead of Manage. Threaded
     *  here from the AI_REPORTS route's `initialView` query-param,
     *  which the per-row 👁 View icon on every report-list screen
     *  appends. Default false preserves the historical Manage entry
     *  behaviour every other callsite already depends on. */
    initialView: Boolean = false,
    /** Optional further seed paired with [initialView] — when set,
     *  the View tile grid's Reports sub-overlay opens immediately
     *  at this agent's page. Used by Model Info View's Last-Usage
     *  rows ([NavRoutes.aiReportViewAtAgent]). */
    initialReportsAgentId: String? = null,
    /** Optional Manage sub-overlay to open on first composition, set
     *  when a report was picked from a Manage screen's 🗂️ so the user
     *  returns to that same screen for the chosen report. A
     *  [com.ai.ui.navigation.ManagePickKind] arg token; consumed once
     *  by [SeedInitialManageOverlay]. */
    initialManageOverlay: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    /** Explicit navigation to the AI Reports hub. Used after a
     *  delete-report so the user always lands on the hub instead
     *  of being popped back to wherever they came from (which is
     *  often the pre-Generate model selection screen — confusing
     *  context for "the report you were just on is gone"). */
    onNavigateToReportsHub: () -> Unit = onNavigateBack,
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit = {},
    /** Open the App Log Viewer for `filename`, pre-filtered to
     *  `search`. Wired by the report screen's View → Log button. */
    onNavigateToAppLog: (filename: String, search: String) -> Unit = { _, _ -> },
    onNavigateToTraceListFiltered: (String, String) -> Unit = { _, _ -> },
    /** Open the trace list pre-filtered to a single batch's
     *  runId. Wired on the run-overview 🐞 icons. */
    onNavigateToTraceRunList: (String) -> Unit = {},
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
    onNavigateToInternalPromptsByCategory: (String) -> Unit = {},
    /** Targets for the per-row 🔧 / 👁 icons that the +Report
     *  picker (ReportSelectFromReportScreen) renders. Distinct
     *  from the row's primary tap, which copies the model list
     *  into the current selection — the icons jump to the picked
     *  report itself. Wired by AppNavHost to the same restore +
     *  navigate path the rest of the app uses. */
    onOpenReportManage: (String) -> Unit = {},
    onOpenReportView: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val agentResults by reportViewModel.agentResults.collectAsState()
    val temperatureSweepStates by reportViewModel.temperatureSweepStates.collectAsState()
    val reasoningEffortSweepStates by reportViewModel.reasoningEffortSweepStates.collectAsState()
    val webSearchReplayStates by reportViewModel.webSearchReplayStates.collectAsState()
    val promptEditReplayStates by reportViewModel.promptEditReplayStates.collectAsState()
    // Running fan-out pair ids derived from the engine's StateFlow (the
    // single source of truth) instead of a separate set — a pair is
    // "running" exactly when its PairStatus is RUNNING. distinctUntilChanged
    // so this screen only recomposes when the running set actually changes,
    // not on every per-pair content / cost transition.
    val runningFanOutPairs by remember(reportViewModel) {
        reportViewModel.fanOutEngine.runs
            .map { runs ->
                runs.values
                    .flatMap { it.pairs.values }
                    .filter { it.status == com.ai.data.PairStatus.RUNNING }
                    .map { it.id }
                    .toSet()
            }
            .distinctUntilChanged()
    }.collectAsState(initial = emptySet())
    val throttledFanOutPairs by viewModel.throttledFanOutPairs.collectAsState()
    val runningFanMetaPairs by viewModel.runningFanMetaPairs.collectAsState()
    val throttledFanMetaPairs by viewModel.throttledFanMetaPairs.collectAsState()
    val throttledTranslationItems by viewModel.throttledTranslationItems.collectAsState()
    val iconFanOutByReport by viewModel.iconFanOutByReport.collectAsState()
    val agentIconFanOutByAgent by viewModel.agentIconFanOutByAgent.collectAsState()
    val runningInfoJobs by viewModel.runningInfoJobs.collectAsState()
    val titleFanOutByReport by viewModel.titleFanOutByReport.collectAsState()
    val altTranslationByItem by viewModel.altTranslationByItem.collectAsState()
    val titleFanOutByAgent by viewModel.titleFanOutByAgent.collectAsState()
    val pairIconFanOutByPair by viewModel.pairIconFanOutByPair.collectAsState()
    val pairTitleFanOutByPair by viewModel.pairTitleFanOutByPair.collectAsState()
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
    // `<` = chronologically previous = OLDER (further down the
    // newest-first list, so higher index). `>` = chronologically
    // next = NEWER (lower index). Swapped from the original wiring
    // which had `<` jumping to a newer report and `>` to an older
    // one — the user reported them backwards.
    val hasPrevReport = currentIdx >= 0 && currentIdx < reportIdsNewestFirst.size - 1
    val hasNextReport = currentIdx > 0

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

    // Language icon fan-out callbacks bundled into one ReportsScreen
    // arg so the per-method bytecode budget stays under the JVM
    // 64 KB ceiling. The actual overlay rendering happens inline in
    // ReportsScreen via the existing icon routers (which now branch
    // on targetLanguageIcon = true to route to the language flow).
    val languageIconFanOutByReport by viewModel.languageIconFanOutByReport.collectAsState()
    val languageIconCallbacks = LanguageIconCallbacks(
        fanOutByReport = languageIconFanOutByReport,
        onStartFanOut = { rid, prompt, models ->
            reportViewModel.iconGen.startLanguageIconFanOut(context, rid, prompt, models, aiSettings)
        },
        onPickAlternative = { rid, emoji, iconModel ->
            reportViewModel.iconGen.pickAlternativeLanguageIcon(context, rid, emoji, iconModel)
        },
        onRestartFanOut = { rid -> reportViewModel.iconGen.restartLanguageIconFanOut(rid) },
    )

    // Bundle the +Report picker's row-icon callbacks plus the
    // first-composition View-screen seed into a CompositionLocal —
    // ReportsScreen's parameter list is already at the JVM 64 KB
    // per-method bytecode ceiling, so we can't add three more
    // function/boolean slots there. The picker / first-composition
    // seed read straight from the local.
    // Build the neighbour-nav callbacks once here so both ReportsScreen
    // (chevrons) and the swipe handler inside ViewAiReportScreen (via
    // the [LocalReportNeighborNav] CompositionLocal) share one source.
    // Threading the same lambdas through ReportsScreen as args would
    // push that function past the JVM 64 KB per-method ceiling.
    val neighborNav = remember(hasPrevReport, hasNextReport, currentIdx, reportIdsNewestFirst) {
        // Wrap both ways: with >1 report a swipe always moves and cycles
        // around the ends — no dead-end, no "No more reports".
        val size = reportIdsNewestFirst.size
        com.ai.ui.shared.ReportNeighborNav(
            onPrev = {
                if (size > 1 && currentIdx >= 0) {
                    val targetId = reportIdsNewestFirst[(currentIdx + 1) % size]
                    scope.launch { reportViewModel.restoreCompletedReport(context, targetId) }
                }
            },
            onNext = {
                if (size > 1 && currentIdx >= 0) {
                    val targetId = reportIdsNewestFirst[(currentIdx - 1 + size) % size]
                    scope.launch { reportViewModel.restoreCompletedReport(context, targetId) }
                }
            },
            hasPrev = size > 1,
            hasNext = size > 1
        )
    }
    // Manage → View 👁 jump holder. Lives HERE (one wrapper level
    // above the bytecode-ceiling-hitting ReportsScreen) so the
    // state declaration doesn't push ReportsScreen over its 64 KB
    // method limit. ReportPrimaryOverlays' new top-of-chain block
    // and the Manage screens' 👁 bottom-bar button both read this
    // via [LocalPendingViewOverManage]. `remember` (not
    // rememberSaveable) — transient enough to lose on rotation,
    // and we avoid needing a Saver for the sealed type.
    val pendingViewOverManage = remember { mutableStateOf<com.ai.ui.shared.ViewJump?>(null) }
    // Bumped by ReportPrimaryOverlays' layered-View "go to main
    // View" path. ViewAiReportScreen keys its inner overlay state
    // on this so a bump force-resets every sub-View (rerank /
    // moderation / fan-in / …) back to the tile grid.
    val mainViewResetTick = remember { mutableStateOf(0) }
    // Detail-screen open state for the "Regenerate report" batch.
    // Hoisted to this composable (instead of ReportsScreen) to keep
    // ReportsScreen under the JVM 64 KB method-size ceiling. Both
    // the Regenerate row's click handler (deep in the tree) and the
    // overlay mount site read this through LocalRegenerateBatchOpenState.
    val openRegenerateBatchReportId = rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.autoSaver<String?>()
    ) { mutableStateOf<String?>(null) }
    val openRegenBatchId = openRegenerateBatchReportId.value
    val openTournamentReportId = rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.autoSaver<String?>()
    ) { mutableStateOf<String?>(null) }
    val openTournamentId = openTournamentReportId.value
    val openJudgeEvalReportId = rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.autoSaver<String?>()
    ) { mutableStateOf<String?>(null) }
    val openJudgeEvalId = openJudgeEvalReportId.value
    val openCompareReportId = rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.autoSaver<String?>()
    ) { mutableStateOf<String?>(null) }
    val openCompareId = openCompareReportId.value
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportListIconBundle provides com.ai.ui.shared.ReportListIconBundle(
            onOpenManage = onOpenReportManage,
            onOpenView = onOpenReportView,
            initialView = initialView,
            initialReportsAgentId = initialReportsAgentId,
            initialManageOverlay = initialManageOverlay,
            // Route-pop hook used by the View overlay's onBack when the
            // user arrived here via the per-row 👁 icon — pops AI_REPORTS
            // so back returns to the list instead of falling through to
            // the underlying Manage screen.
            onExitToList = onNavigateBack
        ),
        com.ai.ui.shared.LocalReportNeighborNav provides neighborNav,
        com.ai.ui.shared.LocalReportIdsNewestFirst provides reportIdsNewestFirst,
        com.ai.ui.shared.LocalReportSwitchHandler provides remember(reportViewModel) {
            { id: String -> scope.launch { reportViewModel.restoreCompletedReport(context, id) } }
        },
        // Source of truth for the standard TitleBar's swipe gesture
        // on every non-View (Manage) screen. The bar auto-wires its
        // swipe handlers from this local + LocalReportIdsNewestFirst +
        // LocalReportSwitchHandler; everything outside this tree
        // (Settings/Admin/Hub/etc.) sees the default null and gets no
        // gesture. Reusable picker overlays explicitly override this
        // back to null at their call sites in ReportScreen — see the
        // `CompositionLocalProvider(LocalCurrentReportIdForSwipe provides null)`
        // wraps around each picker block.
        com.ai.ui.shared.LocalCurrentReportIdForSwipe provides uiState.currentReportId,
        com.ai.ui.shared.LocalPendingViewOverManage provides pendingViewOverManage,
        com.ai.ui.shared.LocalMainViewResetTick provides mainViewResetTick,
        com.ai.ui.shared.LocalRegenerateBatchEngine provides reportViewModel.regenerateBatchEngine,
        com.ai.ui.shared.LocalRegenerateBatchOpenState provides openRegenerateBatchReportId,
        com.ai.ui.shared.LocalTournamentEngine provides reportViewModel.tournamentEngine,
        com.ai.ui.shared.LocalTournamentOpenState provides openTournamentReportId,
        com.ai.ui.shared.LocalJudgeEvalEngine provides reportViewModel.judgeEvalEngine,
        com.ai.ui.shared.LocalJudgeEvalOpenState provides openJudgeEvalReportId,
        com.ai.ui.shared.LocalCompareEngine provides reportViewModel.compareEngine,
        com.ai.ui.shared.LocalCompareOpenState provides openCompareReportId,
        com.ai.ui.shared.LocalMetaEditManager provides reportViewModel.metaEditManager
    ) {
    // Regenerate-batch overlay — layered here (inside the provider) so it
    // sees the report-context locals (ids/switch/neighbor nav, icon
    // bundle). Previously it early-returned above this block and got the
    // defaults instead.
    if (openRegenBatchId != null) {
        RegenerateBatchOverlay(
            reportId = openRegenBatchId,
            engine = reportViewModel.regenerateBatchEngine,
            onClose = { openRegenerateBatchReportId.value = null },
            iconRefreshTick = uiState.iconRefreshTick
        )
        return@CompositionLocalProvider
    }
    if (openTournamentId != null) {
        TournamentOverlay(
            reportId = openTournamentId,
            engine = reportViewModel.tournamentEngine,
            onClose = { openTournamentReportId.value = null }
        )
        return@CompositionLocalProvider
    }
    if (openJudgeEvalId != null) {
        JudgeEvalOverlay(
            reportId = openJudgeEvalId,
            engine = reportViewModel.judgeEvalEngine,
            onClose = { openJudgeEvalReportId.value = null }
        )
        return@CompositionLocalProvider
    }
    if (openCompareId != null) {
        CompareOverlay(
            reportId = openCompareId,
            engine = reportViewModel.compareEngine,
            onClose = { openCompareReportId.value = null }
        )
        return@CompositionLocalProvider
    }
    ReportsScreen(
        uiState = uiState,
        reportsAgentResults = agentResults,
        temperatureSweepStates = temperatureSweepStates,
        reasoningEffortSweepStates = reasoningEffortSweepStates,
        webSearchReplayStates = webSearchReplayStates,
        promptEditReplayStates = promptEditReplayStates,
        runningFanOutPairs = runningFanOutPairs,
        fanRuntime = FanRuntimeBundle(
            throttledFanOutPairs = throttledFanOutPairs,
            runningFanMetaPairs = runningFanMetaPairs,
            throttledFanMetaPairs = throttledFanMetaPairs,
            onLaunchFanMetaBatch = { rid, metaPromptId ->
                reportViewModel.iconGen.runFanMetaBatch(context, rid, metaPromptId)
            },
            onClearFanMetaErrors = { rid, mp ->
                reportViewModel.iconGen.clearFanMetaErrors(context, rid, mp)
            },
            onRestartFanMetaErrors = { rid, mp ->
                reportViewModel.iconGen.restartFanMetaErrors(context, rid, mp)
            }
        ),
        fanOutEngine = reportViewModel.fanOutEngine,
        iconFanOutByReport = iconFanOutByReport,
        onStartIconFanOut = { rid, prompt, models ->
            reportViewModel.iconGen.startIconFanOut(context, rid, prompt, models, aiSettings)
        },
        onPickAlternativeIcon = { rid, emoji, iconModel ->
            reportViewModel.iconGen.pickAlternativeIcon(context, rid, emoji, iconModel)
        },
        onRestartIconFanOut = { rid -> reportViewModel.iconGen.restartIconFanOut(rid) },
        languageIconCallbacks = languageIconCallbacks,
        onStartAgentIconFanOut = { rid, agentId, models ->
            reportViewModel.iconGen.startAgentIconFanOut(context, rid, agentId, models, aiSettings)
        },
        onPickAgentIcon = { rid, agentId, emoji ->
            reportViewModel.iconGen.pickAgentIcon(context, rid, agentId, emoji)
        },
        onRestartAgentIconFanOut = { rid, agentId ->
            reportViewModel.iconGen.restartAgentIconFanOut(rid, agentId)
        },
        titleFanOutByReport = titleFanOutByReport,
        titleFanOutByAgent = titleFanOutByAgent,
        onStartReportTitleFanOut = { rid, prompt, models, long, paramsIds, systemPromptId ->
            reportViewModel.iconGen.startReportTitleFanOut(context, rid, prompt, models, aiSettings, long, paramsIds, systemPromptId)
        },
        onStartModelTitleFanOut = { rid, agentId, models, paramsIds, systemPromptId ->
            reportViewModel.iconGen.startModelTitleFanOut(context, rid, agentId, models, aiSettings, paramsIds, systemPromptId)
        },
        onRestartReportTitleFanOut = { rid -> reportViewModel.iconGen.restartReportTitleFanOut(rid) },
        onRestartModelTitleFanOut = { agentId -> reportViewModel.iconGen.restartModelTitleFanOut(agentId) },
        altTranslationByItem = altTranslationByItem,
        onStartAltTranslationFanOut = { rid, itemId, lang, isTitle, src, traceType, models, pIds, spId ->
            reportViewModel.translation.startAltTranslationFanOut(context, rid, itemId, lang, isTitle, src, traceType, models, aiSettings, pIds, spId)
        },
        onApplyAltTranslation = { rid, runId, itemId, rowId, candidate ->
            reportViewModel.translation.applyAltTranslation(context, rid, runId, itemId, rowId, candidate)
        },
        onRestartAltTranslationFanOut = { itemId -> reportViewModel.translation.restartAltTranslationFanOut(itemId) },
        onStartPairIconFanOut = { rid, pairId, models ->
            reportViewModel.iconGen.startPairIconFanOut(context, rid, pairId, models, aiSettings)
        },
        onPickPairIcon = { rid, pairId, emoji ->
            reportViewModel.iconGen.pickPairIconAlternative(context, rid, pairId, emoji)
        },
        onRestartPairIconFanOut = { rid, pairId ->
            reportViewModel.iconGen.restartPairIconFanOut(rid, pairId)
        },
        pairIconFanOutByPair = pairIconFanOutByPair,
        onStartPairTitleFanOut = { rid, pairId, models ->
            reportViewModel.iconGen.startPairTitleFanOut(context, rid, pairId, models, aiSettings)
        },
        onResolveAltPrompt = { flow ->
            reportViewModel.iconGen.resolveAltPrompt(context, aiSettings, flow)
        },
        onStashAltEdit = { reportViewModel.iconGen.pendingAltEdit = it },
        onPickPairTitle = { rid, pairId, title ->
            reportViewModel.iconGen.pickPairTitleAlternative(context, rid, pairId, title)
        },
        onRestartPairTitleFanOut = { rid, pairId ->
            reportViewModel.iconGen.restartPairTitleFanOut(rid, pairId)
        },
        pairTitleFanOutByPair = pairTitleFanOutByPair,
        promptIconCallbacks = InternalPromptIconCallbacks(
            onKickoff = { prompt ->
                reportViewModel.iconGen.kickOffInternalPromptIcon(context, prompt, aiSettings)
            },
            onStartFanOut = { prompt, picks, paramsIds, systemPromptId ->
                reportViewModel.iconGen.startInternalPromptIconFanOut(
                    context, prompt, picks, aiSettings,
                    reportId = uiState.currentReportId,
                    paramsIds = paramsIds, systemPromptId = systemPromptId
                )
            },
            onPick = { prompt, cand ->
                reportViewModel.iconGen.pickInternalPromptIcon(context, prompt, cand, aiSettings)
            },
            onRestartFanOut = { prompt ->
                reportViewModel.iconGen.restartInternalPromptIconFanOut(prompt)
            },
            onPickRow = { reportId, rowId, emoji ->
                reportViewModel.iconGen.pickMetaRowIcon(context, reportId, rowId, emoji)
            }
        ),
        internalPromptIconFanOutByPrompt = internalPromptIconFanOutByPrompt,
        translationIconCallbacks = TranslationIconCallbacks(
            onKickoff = { language ->
                reportViewModel.iconGen.kickOffTranslationIcon(context, language, aiSettings)
            },
            onStartFanOut = { language, picks ->
                reportViewModel.iconGen.startTranslationIconFanOut(
                    context, language, picks, aiSettings,
                    reportId = uiState.currentReportId
                )
            },
            onPick = { language, cand ->
                reportViewModel.iconGen.pickTranslationIcon(context, language, cand, aiSettings)
            },
            onRestartFanOut = { language ->
                reportViewModel.iconGen.restartTranslationIconFanOut(language)
            }
        ),
        agentIconFanOutByAgent = agentIconFanOutByAgent,
        onPrevReport = {
            if (hasPrevReport) {
                // OLDER = higher index in newest-first list.
                val targetId = reportIdsNewestFirst[currentIdx + 1]
                scope.launch { reportViewModel.restoreCompletedReport(context, targetId) }
            }
        },
        onNextReport = {
            if (hasNextReport) {
                // NEWER = lower index in newest-first list.
                val targetId = reportIdsNewestFirst[currentIdx - 1]
                scope.launch { reportViewModel.restoreCompletedReport(context, targetId) }
            }
        },
        hasPrevReport = hasPrevReport,
        hasNextReport = hasNextReport,
        initialModels = initialModels,
        onRunSecondary = { reportId, metaPrompt, picks, scopeChoice, languageScope, paramsIds, systemPromptId ->
            reportViewModel.secondary.runMetaPrompt(context, reportId, metaPrompt, picks, scopeChoice, languageScope, paramsIds, systemPromptId)
        },
        onTranslateMissingItems = { reportId, items, target, targetNative ->
            reportViewModel.translation.translateMissingItems(context, reportId, items, target, targetNative)
        },
        onRunFanOut = { reportId, metaPrompt, scopeChoice, responderIds, sourceLanguage, paramsIds, systemPromptId, includeSelf ->
            reportViewModel.fanOutEngine.startRun(context, reportId, metaPrompt, scopeChoice, responderIds, sourceLanguage, paramsIds = paramsIds, systemPromptId = systemPromptId, includeSelfResponses = includeSelf)
        },
        onRunFanIn = { reportId, metaPrompt, pick, sourceLanguage, paramsIds, systemPromptId ->
            reportViewModel.secondary.runFanInPrompt(context, reportId, metaPrompt, pick, sourceLanguage, paramsIds, systemPromptId)
        },
        onCreateReportFromFanOut = { sourceRid, activePid, activeMdl ->
            scope.launch {
                val newId = reportViewModel.secondary.createReportFromFanOut(context, sourceRid, activePid, activeMdl)
                    ?: return@launch
                // Already on AI_REPORTS; restoreCompletedReport flips
                // UiState (showGenericReportsDialog + currentReportId)
                // so the result screen recomposes with the new report.
                reportViewModel.restoreCompletedReport(context, newId)
            }
        },
        onRunLocalRerank = { reportId, modelName ->
            reportViewModel.secondary.runLocalRerank(context, reportId, modelName)
        },
        onRunRerank = { reportId, pick, languageScope, paramsIds, systemPromptId ->
            reportViewModel.secondary.runRerank(context, reportId, pick, languageScope, paramsIds, systemPromptId)
        },
        onRunModeration = { reportId, pick, languageScope ->
            reportViewModel.secondary.runModeration(context, reportId, pick, languageScope)
        },
        onRunTournament = { reportId ->
            reportViewModel.tournamentEngine.startRun(context, reportId)
        },
        onRunJudgeJudges = { reportId ->
            reportViewModel.judgeEvalEngine.startRun(context, reportId)
        },
        onDeleteSecondary = { reportId, resultId ->
            reportViewModel.secondary.deleteSecondaryResult(context, reportId, resultId)
        },
        onBulkDeleteSecondaries = { reportId, ids, onDone ->
            reportViewModel.secondary.bulkDeleteSecondaryResults(context, reportId, ids, onDone)
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
        onParametersIdsChange = { viewModel.setReportParametersIds(it) },
        onSystemPromptChange = { viewModel.setReportSystemPromptId(it) },
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToTraceFile = onNavigateToTraceFile,
        onNavigateToAppLog = onNavigateToAppLog,
        onNavigateToTraceListFiltered = onNavigateToTraceListFiltered,
        onNavigateToTraceRunList = onNavigateToTraceRunList,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onRemoveAgent = { rid, aid -> reportViewModel.removeAgentFromReport(context, rid, aid) },
        onRegenerateAgent = { rid, aid -> reportViewModel.regenerateAgent(context, rid, aid) },
        onStartTemperatureSweep = { rid, aid, temps ->
            reportViewModel.startTemperatureSweep(context, rid, aid, temps)
        },
        onApplyTemperatureCandidate = { rid, aid, index ->
            reportViewModel.applyTemperatureCandidate(context, rid, aid, index)
        },
        onClearTemperatureSweep = { rid, aid ->
            reportViewModel.clearTemperatureSweep(rid, aid)
        },
        onStartReasoningEffortSweep = { rid, aid, efforts ->
            reportViewModel.startReasoningEffortSweep(context, rid, aid, efforts)
        },
        onApplyReasoningEffortCandidate = { rid, aid, index ->
            reportViewModel.applyReasoningEffortCandidate(context, rid, aid, index)
        },
        onClearReasoningEffortSweep = { rid, aid ->
            reportViewModel.clearReasoningEffortSweep(rid, aid)
        },
        onStartWebSearchReplay = { rid, aid ->
            reportViewModel.startWebSearchReplay(context, rid, aid)
        },
        onApplyWebSearchReplay = { rid, aid ->
            reportViewModel.applyWebSearchReplay(context, rid, aid)
        },
        onClearWebSearchReplay = { rid, aid ->
            reportViewModel.clearWebSearchReplay(rid, aid)
        },
        onStartPromptEditReplay = { rid, aid, prompt, paramsIds, systemPromptId ->
            reportViewModel.startPromptEditReplay(context, rid, aid, prompt, paramsIds, systemPromptId)
        },
        onApplyPromptEditReplay = { rid, aid ->
            reportViewModel.applyPromptEditReplay(context, rid, aid)
        },
        onClearPromptEditReplay = { rid, aid ->
            reportViewModel.clearPromptEditReplay(rid, aid)
        },
        onClearExternalInstructions = viewModel::clearExternalInstructions,
        onEditModels = { rid -> scope.launch { reportViewModel.prepareEditModels(context, rid) } },
        onUpdateModelList = { rid, edited ->
            scope.launch { reportViewModel.stageModelListForRegenerate(context, rid, edited) }
        },
        onMarkParametersChanged = {
            viewModel.updateUiState { it.copy(hasPendingParametersChange = true) }
        },
        // Title-bar 🔁 → confirm dialog → enqueue a Regenerate
        // batch job (app-restart-survivable, phased through every
        // category) instead of the legacy one-shot regenerateReport.
        onRegenerate = { rid -> reportViewModel.regenerateBatchEngine.enqueueAndStart(context, rid) },
        onRegenerateInfo = { rid -> reportViewModel.regenerateReportInfo(context, rid) },
        onRestartInfoErrors = { rid -> reportViewModel.restartReportInfoErrors(context, rid) },
        runningInfoJobs = runningInfoJobs,
        onUpdatePrompt = { rid, prompt ->
            scope.launch { reportViewModel.updateReportPrompt(context, rid, prompt) }
        },
        onUpdateTitle = { rid, title, titleLong ->
            scope.launch { reportViewModel.updateReportTitle(context, rid, title, titleLong) }
        },
        onUpdateModelTitle = { rid, agentId, title ->
            scope.launch { reportViewModel.updateModelTitle(context, rid, agentId, title) }
        },
        onUpdatePairTitle = { rid, pairId, title ->
            scope.launch { reportViewModel.updateFanOutPairTitle(context, rid, pairId, title) }
        },
        onAttachKnowledgeBases = { ids -> viewModel.updateUiState { it.copy(attachedKnowledgeBaseIds = ids) } },
        onDeleteReport = { rid ->
            reportViewModel.deleteReport(context, rid)
            // Always land on the AI Reports hub after a delete —
            // popping the back stack would drop the user on the
            // pre-Generate model selection / wherever they came
            // from, which is confusing context for "the report
            // you were just on is gone".
            onNavigateToReportsHub()
        },
        onCopyReport = { rid -> reportViewModel.copyReport(context, rid, scope) },
        onTogglePinReport = { rid -> reportViewModel.toggleReportPinned(context, rid, scope) },
        onConsumePendingModels = { reportViewModel.clearPendingReportModels() },
        onExport = { rid, fmt, det, act, lang, onProgress ->
            shareReportAsExport(
                context, rid, fmt, det, act,
                uiState.aiSettings, viewModel.repository, onProgress,
                language = lang
            )
        },
        onExportAll = { rid, lang, onProgress ->
            bulkExportAndShare(context, rid, lang, onProgress)
        },
        // Filter to the current report — _translationRuns is keyed by
        // runId across every report's batches; without the filter, an
        // older report's in-flight (or never-cleared) translation runs
        // would show as live "translate" rows on every other report's
        // manage screen.
        translationRuns = reportViewModel.translation.translationRuns.collectAsState().value.values
            .filter { it.sourceReportId == uiState.currentReportId }
            .toList(),
        throttledTranslationItems = throttledTranslationItems,
        onStartTranslation = { sourceId, langName, langNative ->
            // Returns the new run's id so Manage can land on the Translation
            // L1 page immediately. No model picker — the translate worker
            // swarm handles model selection + fallback.
            reportViewModel.translation.startTranslation(context, sourceId, langName, langNative).first
        },
        translationLifecycle = TranslationLifecycleCallbacks(
            onCancelRun = { runId -> reportViewModel.translation.cancelTranslation(runId) },
            onCancelItem = { runId, itemId -> reportViewModel.translation.cancelTranslationItem(runId, itemId) },
            onConsumeRun = { runId -> reportViewModel.translation.consumeTranslationRun(runId) },
            onReconcileStalled = { sourceId, runId -> reportViewModel.translation.reconcileStalledTranslationRun(context, sourceId, runId) },
            onDeleteRun = { sourceId, runId -> reportViewModel.translation.deleteTranslationRun(context, sourceId, runId) }
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
        onResumeStaleFanOut = { rid, _ ->
            // The engine resumes every stale run on the report in one pass.
            reportViewModel.fanOutEngine.resumeStaleRunsForReport(context, rid)
            // Tournament runs share the same app-kill recovery contract.
            reportViewModel.tournamentEngine.resumeStaleRunsForReport(context, rid)
            // Judge-the-judges runs too.
            reportViewModel.judgeEvalEngine.resumeStaleRunsForReport(context, rid)
            // Compare-with-meta runs too.
            reportViewModel.compareEngine.resumeStaleRunsForReport(context, rid)
        },
        onResumeStaleRuns = { rid ->
            reportViewModel.secondary.resumeStaleRunsForReport(context, rid)
        },
        onRestartFailedTranslations = { rid, runId ->
            reportViewModel.translation.restartFailedTranslations(context, rid, runId)
        },
        onRemoveFailedTranslations = { rid, runId ->
            reportViewModel.translation.removeFailedTranslations(context, rid, runId)
        },
        onRemoveBenchedTranslations = { rid, runId ->
            reportViewModel.translation.removeBenchedTranslations(context, rid, runId)
        },
        onRestartAllTranslations = { rid, runId ->
            reportViewModel.translation.restartAllTranslations(context, rid, runId)
        },
        onStartMissingTranslations = { rid, runId ->
            reportViewModel.translation.startMissingTranslations(context, rid, runId)
        },
        onBuildPersistedTranslationRun = { rid, runId ->
            reportViewModel.translation.buildPersistedTranslationRunState(context, rid, runId)
        },
        onRestartFailedFanOut = { rid, mp ->
            // The engine batch-reruns every errored pair (ERROR → PENDING →
            // RUNNING per pair, reactively reflected in the flow).
            reportViewModel.fanOutEngine.restartFailedPairs(context, com.ai.data.runKey(rid, mp.id))
        },
        onRemoveFailedFanOut = { rid, mp ->
            reportViewModel.fanOutEngine.removeFailedPairs(context, com.ai.data.runKey(rid, mp.id))
        },
        onRestartFailedFanOutForModel = { rid, mp, prov, mdl ->
            reportViewModel.fanOutEngine.restartFailedPairsForModel(context, com.ai.data.runKey(rid, mp.id), prov, mdl)
        },
        onRemoveFailedFanOutForModel = { rid, mp, prov, mdl ->
            reportViewModel.fanOutEngine.removeFailedPairsForModel(context, com.ai.data.runKey(rid, mp.id), prov, mdl)
        },
        onRerunCompleteFanOut = { rid, mp ->
            reportViewModel.fanOutEngine.rerunComplete(context, com.ai.data.runKey(rid, mp.id))
        },
        onRerunFanOutPair = { rid, mp, pair ->
            reportViewModel.fanOutEngine.rerunPairById(context, com.ai.data.runKey(rid, mp.id), pair.id)
        },
        onDeleteFanOutModel = { rid, pid, prov, model ->
            reportViewModel.fanOutEngine.deleteModelFromRun(context, com.ai.data.runKey(rid, pid), prov, model)
        }
    )
    } // close CompositionLocalProvider added for LocalReportListIconBundle
}

// ===== Helpers =====

/** One-shot seed for the View tile-grid overlay reached from the
 *  per-row 👁 icon on every report list. Reads the `initialView`
 *  flag bundled into [com.ai.ui.shared.LocalReportListIconBundle]
 *  by [ReportsScreenNav]; flips [onSeed] exactly once on first
 *  composition (LaunchedEffect keyed on Unit). Extracted out of
 *  [ReportsScreen] so its bytecode doesn't push that function past
 *  the JVM 64 KB per-method ceiling. */
@Composable
internal fun SeedInitialViewReportScreen(onSeed: () -> Unit) {
    val bundle = com.ai.ui.shared.LocalReportListIconBundle.current
    LaunchedEffect(Unit) {
        if (bundle.initialView) onSeed()
    }
}

/** One-shot seed for a Manage sub-overlay, set when a report was picked
 *  from a Manage screen's 🗂️ ([com.ai.ui.navigation.ManagePickKind]).
 *  Reads the `initialManageOverlay` token bundled into
 *  [com.ai.ui.shared.LocalReportListIconBundle] by [ReportsScreenNav];
 *  flips the matching `st.show*` flag exactly once on first composition.
 *  Hosted here (not inline in [ReportsScreen]) so the `when` block's
 *  bytecode stays out of that 64 KB-ceiling method. MANAGE / FAN_OUT
 *  carry no overlay token (the picker routes them to the hub / View
 *  grid), so they never reach here. */
@Composable
internal fun SeedInitialManageOverlay(st: ReportsScreenState) {
    val bundle = com.ai.ui.shared.LocalReportListIconBundle.current
    LaunchedEffect(Unit) {
        when (com.ai.ui.navigation.ManagePickKind.fromArg(bundle.initialManageOverlay)) {
            com.ai.ui.navigation.ManagePickKind.META -> st.showMetaScreen.value = true
            com.ai.ui.navigation.ManagePickKind.EDIT_PROMPT -> st.showEditPrompt.value = true
            com.ai.ui.navigation.ManagePickKind.EDIT_TITLE -> st.showEditShortTitle.value = true
            com.ai.ui.navigation.ManagePickKind.COSTS -> {
                st.selectedAgentForViewer.value = null
                st.viewerSection.value = "costs"
                st.showViewer.value = true
            }
            else -> { /* MANAGE / FAN_OUT / null — nothing to seed */ }
        }
    }
}

/** Writes (reportId, viewMode) into [com.ai.data.LastReportTracker]
 *  whenever either changes. The home-page big-AI-logo reads this to
 *  resume the user back into their last opened report in the same
 *  mode they ended in. Extracted out of [ReportsScreen] so its
 *  LaunchedEffect doesn't add bytecode to that function — which
 *  already sits at the JVM 64 KB per-method ceiling. */
@Composable
internal fun TrackLastReportMode(reportId: String?, viewMode: Boolean) {
    LaunchedEffect(reportId, viewMode) {
        reportId
            ?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.LastReportTracker.record(it, view = viewMode) }
    }
}

internal fun loadSavedReportModels(viewModel: AppViewModel, aiSettings: Settings): List<ReportModel> {
    val agentIds = viewModel.loadReportAgents()
    val agentModels = agentIds.mapNotNull { id -> aiSettings.getAgentById(id)?.let { expandAgentToModel(it, aiSettings) } }
    val savedSelections = viewModel.loadReportModels()
    val savedModels = savedSelections.flatMap { decodeSavedReportModelSelection(it, aiSettings) }
    return deduplicateModels(agentModels + savedModels)
}

internal fun encodeSavedReportModelSelection(model: ReportModel): String {
    return when (model.sourceType) {
        "swarm" -> model.sourceId?.let { "swarm-id:$it" } ?: "swarm:${model.provider.id}:${model.model}"
        else -> "swarm:${model.provider.id}:${model.model}"
    }
}

internal fun decodeSavedReportModelSelection(selection: String, aiSettings: Settings): List<ReportModel> {
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



/** Report delete confirmation, lifted out of [ReportsScreen]. */
@Composable
internal fun DeleteReportConfirmDialog(
    reportId: String,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    // Capture the report id at dialog-open time so a background
    // mutation of currentReportId between Delete tap and lambda
    // execution can't end up deleting the wrong report.
    val ridAtOpen = reportId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete report?") },
        text = { Text("This permanently removes the saved report from disk.") },
        confirmButton = {
            TextButton(onClick = { onDelete(ridAtOpen) }) {
                Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1, softWrap = false) } }
    )
}
