package com.ai.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ai.data.*
import com.ai.model.*
import com.ai.viewmodel.*
import com.ai.ui.chat.*
import com.ai.ui.hub.*
import com.ai.ui.report.*
import com.ai.ui.history.*
import com.ai.ui.models.*
import com.ai.ui.search.*
import com.ai.ui.settings.*
import com.ai.ui.admin.*
import com.ai.ui.shared.*

/**
 * Main navigation host for the app.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = viewModel(),
    reportViewModel: ReportViewModel = remember { ReportViewModel(appViewModel) },
    chatViewModel: ChatViewModel = remember { ChatViewModel(appViewModel) },
    externalTitle: String? = null,
    externalSystem: String? = null,
    externalPrompt: String? = null,
    externalInstructions: String? = null,
    onExternalIntentHandled: () -> Unit = {},
    sharedContent: com.ai.data.SharedContent? = null,
    onSharedContentHandled: () -> Unit = {}
) {
    // App-wide background resume sweep — walks every report
    // modified in the last 7 days and runs the same per-report
    // stale-resume pass that fires when a report is opened.
    // LaunchedEffect(Unit) fires once per composition; the
    // start method's cancel-prior pattern (Job stored on
    // AppViewModel) handles Activity rotation cleanly.
    val sweepContext = LocalContext.current
    LaunchedEffect(Unit) {
        reportViewModel.startBackgroundResumeSweep(sweepContext)
    }

    val safePopBack: () -> Unit = {
        if (navController.previousBackStackEntry != null) navController.popBackStack()
    }
    val navigateHome: () -> Unit = {
        navController.navigate(NavRoutes.AI) { popUpTo(NavRoutes.AI) { inclusive = true } }
    }

    // Handle external intent.
    //
    // ACTION_NEW_REPORT is exported, so any installed app can fire it.
    // Bare-prompt intents (no `<instructions>` block) merely pre-fill
    // the new-report editor — the user still picks models and taps
    // Generate manually, so no API credits move without consent.
    // Anything with instructions, however, can auto-generate, drive
    // model selection, email/share/browser the result, and finish()
    // the activity. That class of intent must pass through an explicit
    // confirmation screen before any of those side effects run.
    val pendingExternalReport = remember {
        mutableStateOf<com.ai.ui.share.PendingExternalReport?>(null)
    }
    LaunchedEffect(externalPrompt) {
        if (externalPrompt != null) {
            fun extractTag(tag: String, text: String): String? =
                Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
            fun extractAllTags(tag: String, text: String): List<String> =
                Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

            val marker = "-- end prompt --"
            val aiPrompt: String
            val instructions: String

            if (externalInstructions != null) {
                aiPrompt = externalPrompt.trim(); instructions = externalInstructions
            } else if (externalPrompt.contains(marker)) {
                val parts = externalPrompt.split(marker, limit = 2)
                aiPrompt = parts[0].trim(); instructions = parts.getOrElse(1) { "" }
            } else {
                navController.navigate(NavRoutes.aiNewReportWithParams(externalTitle ?: "", externalPrompt)) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
                onExternalIntentHandled(); return@LaunchedEffect
            }

            pendingExternalReport.value = com.ai.ui.share.PendingExternalReport(
                title = externalTitle,
                systemPrompt = externalSystem,
                aiPrompt = aiPrompt,
                openHtml = extractTag("open", instructions),
                closeHtml = extractTag("close", instructions),
                reportType = extractTag("type", instructions),
                email = extractTag("email", instructions),
                nextAction = extractTag("next", instructions),
                hasReturn = Regex("<return>", RegexOption.IGNORE_CASE).containsMatchIn(instructions),
                hasEdit = Regex("<edit>", RegexOption.IGNORE_CASE).containsMatchIn(instructions),
                hasSelect = Regex("<select>", RegexOption.IGNORE_CASE).containsMatchIn(instructions),
                agentNames = extractAllTags("agent", instructions),
                flockNames = extractAllTags("flock", instructions),
                swarmNames = extractAllTags("swarm", instructions),
                modelSpecs = extractAllTags("model", instructions)
            )
            // Clear the source-of-truth extras so a configuration
            // change doesn't re-stage the confirmation after the user
            // has cancelled or confirmed it.
            onExternalIntentHandled()
        }
    }

    pendingExternalReport.value?.let { staged ->
        com.ai.ui.share.ExternalIntentConfirmScreen(
            intent = staged,
            onCancel = { pendingExternalReport.value = null },
            onConfirm = {
                appViewModel.setExternalInstructions(
                    closeHtml = staged.closeHtml,
                    reportType = staged.reportType,
                    email = staged.email,
                    nextAction = staged.nextAction,
                    returnAfterNext = staged.hasReturn,
                    agentNames = staged.agentNames,
                    flockNames = staged.flockNames,
                    swarmNames = staged.swarmNames,
                    modelSpecs = staged.modelSpecs,
                    edit = staged.hasEdit,
                    select = staged.hasSelect,
                    openHtml = staged.openHtml,
                    systemPrompt = staged.systemPrompt
                )
                if (staged.hasEdit) {
                    navController.navigate(NavRoutes.aiNewReportWithParams(staged.title ?: "", staged.aiPrompt)) {
                        popUpTo(NavRoutes.AI) { inclusive = false }
                    }
                } else {
                    val fullPrompt = if (staged.openHtml != null)
                        "${staged.aiPrompt}\n<user>${staged.openHtml}</user>" else staged.aiPrompt
                    reportViewModel.showGenericAgentSelection(staged.title ?: "", fullPrompt)
                    navController.navigate(NavRoutes.AI_REPORTS) { popUpTo(NavRoutes.AI) { inclusive = false } }
                }
                pendingExternalReport.value = null
            }
        )
        return
    }

    // Share-target overlay — when the launching Intent was an
    // ACTION_SEND / ACTION_SEND_MULTIPLE the user picks a destination
    // here before the standard nav graph takes over. Renders on top
    // of the back-stack until consumed.
    if (sharedContent != null && !sharedContent.isEmpty) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        com.ai.ui.share.ShareChooserScreen(
            shared = sharedContent,
            onCancel = onSharedContentHandled,
            onSendToReport = {
                scope.launch {
                    routeShareToReport(context, appViewModel, navController, sharedContent)
                    onSharedContentHandled()
                }
            },
            onSendToChat = {
                appViewModel.updateUiState { it.copy(chatStarterText = sharedContent.text) }
                // Land on the configure-on-the-fly provider picker so
                // the user picks model/parameters; the staged starter
                // text follows them into ChatSessionScreen via UiState.
                navController.navigate(NavRoutes.AI_CHAT_PROVIDER) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
                onSharedContentHandled()
            },
            onSendToKnowledge = {
                // Build the queue once: any attachment URIs + the URL
                // text (when present). The previous flow first wrote
                // the uri list, then conditionally overwrote it with
                // a URL-only list ONLY when uris was empty — a share
                // carrying both `text=https://…` AND a PDF therefore
                // dropped the URL silently. Knowledge consumes the
                // queue and branches on content:// vs http:// per
                // entry, so the merged list is fine.
                val urlText = if (sharedContent.isUrl) sharedContent.text?.trim().orEmpty() else ""
                val queue = sharedContent.uris + listOfNotNull(urlText.takeIf { it.isNotBlank() })
                appViewModel.updateUiState { it.copy(pendingKnowledgeUris = queue) }
                navController.navigate(NavRoutes.AI_KNOWLEDGE) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
                onSharedContentHandled()
            }
        )
        return
    }

    // Surface the user's "Model name layout" preference + a global
    // navigate-to-Model-Info so any Composable in the tree can format
    // combined provider+model labels via [com.ai.ui.shared.modelLabel]
    // and make them clickable via [Modifier.modelInfoClickable]
    // without prop-drilling.
    val rootUiStateForLayout by appViewModel.uiState.collectAsState()
    val rootNavigateToModelInfo: (AppService, String) -> Unit = { p, m ->
        navController.navigate(NavRoutes.aiModelInfo(p.id, m))
    }
    // View-flavoured siblings — set as CompositionLocals so any View
    // screen can navigate to the read-only model-info / agent / flock
    // / swarm pages without prop-drilling through 30+ args.
    val rootNavigateToModelInfoView: (AppService, String) -> Unit = { p, m ->
        navController.navigate(NavRoutes.aiModelInfoView(p.id, m))
    }
    val rootNavigateToAgentView: (String) -> Unit = { id ->
        navController.navigate(NavRoutes.aiAgentView(id))
    }
    val rootNavigateToFlockView: (String) -> Unit = { id ->
        navController.navigate(NavRoutes.aiFlockView(id))
    }
    val rootNavigateToSwarmView: (String) -> Unit = { id ->
        navController.navigate(NavRoutes.aiSwarmView(id))
    }
    val rootNavigateHome: () -> Unit = {
        navController.navigate(NavRoutes.AI) {
            popUpTo(NavRoutes.AI) { inclusive = false }
            launchSingleTop = true
        }
    }
    val rootNavigateHelp: (String?) -> Unit = { topic ->
        if (topic.isNullOrBlank()) navController.navigate(NavRoutes.HELP)
        else navController.navigate(NavRoutes.helpForTopic(topic))
    }
    // The bottom icon bar is now the fixed layout (the old top-bar
    // alternative has been retired) — every TitleBar publishes its
    // action icons here, and AppNavHost paints the bar at the bottom
    // of every nav destination except the Hub.
    val bottomBarIconState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.ai.ui.shared.TitleBarIcons?>(null)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalModelNameLayout provides rootUiStateForLayout.generalSettings.modelNameLayout,
        com.ai.ui.shared.LocalNavigateToModelInfo provides rootNavigateToModelInfo,
        com.ai.ui.shared.LocalNavigateToModelInfoView provides rootNavigateToModelInfoView,
        com.ai.ui.shared.LocalNavigateToAgentView provides rootNavigateToAgentView,
        com.ai.ui.shared.LocalNavigateToFlockView provides rootNavigateToFlockView,
        com.ai.ui.shared.LocalNavigateToSwarmView provides rootNavigateToSwarmView,
        com.ai.ui.shared.LocalIconGenEnabled provides rootUiStateForLayout.generalSettings.iconGenEnabled,
        com.ai.ui.shared.LocalShowBackArrow provides rootUiStateForLayout.generalSettings.showBackArrow,
        com.ai.ui.shared.LocalBottomIconState provides bottomBarIconState,
        com.ai.ui.shared.LocalNavigateHome provides rootNavigateHome,
        com.ai.ui.shared.LocalNavigateToHelp provides rootNavigateHelp
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.AI,
        modifier = Modifier.weight(1f)
    ) {

        // ===== Hub =====
        composable(NavRoutes.AI) {
            val hubContext = LocalContext.current
            val hubScope = rememberCoroutineScope()
            HubScreen(
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onNavigateToTraces = { navController.navigate(NavRoutes.TRACE_LIST) },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
                onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) },
                onNavigateToReportsHub = { navController.navigate(NavRoutes.AI_REPORTS_HUB) },
                onNavigateToUsage = { navController.navigate(NavRoutes.AI_USAGE) },
                onNavigateToChatsHub = { navController.navigate(NavRoutes.AI_CHATS_HUB) },
                onNavigateToAiSetup = { navController.navigate(NavRoutes.AI_SETUP) },
                onNavigateToHousekeeping = { navController.navigate(NavRoutes.AI_HOUSEKEEPING) },
                onNavigateToModelSearch = { navController.navigate(NavRoutes.AI_MODEL_SEARCH) },
                onNavigateToKnowledge = { navController.navigate(NavRoutes.AI_KNOWLEDGE) },
                onOpenLatestReport = {
                    // Resume where the user last was: prefer the
                    // (reportId, mode) tuple recorded by
                    // LastReportTracker at every report-open call
                    // site. Falls back to the most-recently-created
                    // report (legacy behaviour) when no entry exists
                    // yet, or when the recorded report has since
                    // been deleted.
                    hubScope.launch {
                        val tracked = com.ai.data.LastReportTracker.read()
                        val (resolvedId, viewMode) = withContext(Dispatchers.IO) {
                            val all = ReportStorage.getAllReports(hubContext)
                            val pick = tracked
                                ?.takeIf { (id, _) -> all.any { it.id == id } }
                            if (pick != null) {
                                pick
                            } else {
                                if (tracked != null) com.ai.data.LastReportTracker.clear()
                                val fallback = all.firstOrNull()?.id
                                    ?: return@withContext null
                                fallback to false
                            }
                        } ?: return@launch
                        reportViewModel.restoreCompletedReport(hubContext, resolvedId)
                        navController.navigate(
                            if (viewMode) NavRoutes.aiReportView()
                            else NavRoutes.aiReportManage()
                        )
                    }
                },
                viewModel = appViewModel
            )
        }

        // ===== Settings =====
        composable(NavRoutes.SETTINGS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) })
        }
        composable(NavRoutes.AI_SETUP) {
            SetupScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) })
        }

        // ===== Reports =====
        composable(NavRoutes.AI_REPORTS_HUB) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            ReportsHubScreen(
                onNavigateBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenReportManage = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = true)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                },
                onNavigateToNewAiReport = { navController.navigate(NavRoutes.AI_NEW_REPORT_HUB) },
                onNavigateToSearchAiReports = { navController.navigate(NavRoutes.AI_SEARCH_REPORTS) },
                onNavigateToAllReports = { navController.navigate(NavRoutes.AI_ALL_REPORTS) },
                reportViewModel = reportViewModel
            )
        }
        composable(NavRoutes.AI_NEW_REPORT_HUB) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.hub.NewAiReportScreen(
                onNavigateBack = safePopBack,
                onNavigateHome = navigateHome,
                onNavigateToNewReport = { navController.navigate(NavRoutes.AI_NEW_REPORT) },
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) },
                onNavigateToExamplePrompts = { navController.navigate(NavRoutes.AI_EXAMPLE_PROMPT_PICKER) },
                hasExamplePrompts = uiState.aiSettings.examplePrompts.isNotEmpty()
            )
        }
        composable(NavRoutes.AI_SEARCH_REPORTS) {
            com.ai.ui.hub.SearchAiReportsScreen(
                onNavigateBack = safePopBack,
                onNavigateHome = navigateHome,
                onNavigateToQuickLocalSearch = { navController.navigate(NavRoutes.AI_QUICK_LOCAL_SEARCH) },
                onNavigateToLocalSearch = { navController.navigate(NavRoutes.AI_LOCAL_SEARCH) },
                onNavigateToSearch = { navController.navigate(NavRoutes.AI_SEARCH) },
                onNavigateToLocalSemanticSearch = { navController.navigate(NavRoutes.AI_LOCAL_SEMANTIC_SEARCH) }
            )
        }
        composable(NavRoutes.AI_ALL_REPORTS) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalReportListIconBundle provides com.ai.ui.shared.ReportListIconBundle(
                    onOpenManage = { rid ->
                        com.ai.data.LastReportTracker.record(rid, view = false)
                        scope.launch {
                            reportViewModel.restoreCompletedReport(context, rid)
                            navController.navigate(NavRoutes.aiReportManage())
                        }
                    },
                    onOpenView = { rid ->
                        com.ai.data.LastReportTracker.record(rid, view = true)
                        scope.launch {
                            reportViewModel.restoreCompletedReport(context, rid)
                            navController.navigate(NavRoutes.aiReportView())
                        }
                    }
                )
            ) {
                com.ai.ui.hub.AllAiReportsScreen(
                    onNavigateBack = safePopBack,
                    onNavigateHome = navigateHome
                )
            }
        }
        composable(NavRoutes.AI_SEARCH) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val uiState by appViewModel.uiState.collectAsState()
            SemanticSearchScreen(
                aiSettings = uiState.aiSettings,
                repository = appViewModel.repository,
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenReport = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = true)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                }
            )
        }
        composable(NavRoutes.AI_LOCAL_SEMANTIC_SEARCH) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            com.ai.ui.search.LocalSemanticSearchScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenReport = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = true)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) }
            )
        }
        composable(NavRoutes.AI_LOCAL_SEARCH) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            com.ai.ui.search.LocalSearchScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenReport = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = true)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                }
            )
        }
        composable(NavRoutes.AI_REPORT_MANAGE) {
            com.ai.ui.report.ReportManageScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_QUICK_LOCAL_SEARCH) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            com.ai.ui.search.QuickLocalSearchScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenReport = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = true)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                }
            )
        }
        composable(NavRoutes.AI_NEW_REPORT) {
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) })
        }
        composable(NavRoutes.AI_NEW_REPORT_WITH_PARAMS) { entry ->
            val title = try { java.net.URLDecoder.decode(entry.arguments?.getString("title") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val prompt = try { java.net.URLDecoder.decode(entry.arguments?.getString("prompt") ?: "", "UTF-8") } catch (_: Exception) { "" }
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                initialTitle = title, initialPrompt = prompt)
        }
        composable(
            NavRoutes.AI_REPORTS,
            arguments = listOf(
                navArgument("initialView") {
                    type = NavType.StringType; defaultValue = "false"; nullable = true
                }
            )
        ) { entry ->
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // Query-flag seeded by the per-row 👁 View icon on every
            // report list (History, Hub running/problems, all four
            // search screens, Trace detail's report-jump, and the
            // +Report previous-report picker). When true, [ReportsScreenNav]
            // flips [showViewReportScreen] on first composition so the
            // user lands on the View tile grid instead of the default
            // Manage screen. Bare navigation ("ai_reports") leaves it
            // false — Manage as before.
            val initialView = entry.arguments?.getString("initialView") == "true"
            // Real-time tracker updates live inside ReportScreen,
            // which watches the local showViewReportScreen flag and
            // updates LastReportTracker on every Manage ↔ View
            // toggle. Doing it here at the nav arg level would
            // freeze the recorded mode to the entry path even after
            // the user toggled inside the screen.
            //
            // Per-report system-prompt setter surfaced via Composition-
            // Local so [ReportRunScreen]'s Edit Row 2 "System prompt"
            // dialog can fire it without adding another arg to
            // [ReportsScreen]'s 60+ param signature.
            CompositionLocalProvider(
                com.ai.ui.shared.LocalSystemPromptChange provides { id ->
                    appViewModel.setReportSystemPromptId(id)
                }
            ) {
            ReportsScreenNav(viewModel = appViewModel, reportViewModel = reportViewModel,
                initialView = initialView,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTrace = { navController.navigate(NavRoutes.traceListForReport(it)) },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToTraceListFiltered = { rid, cat ->
                    navController.navigate(NavRoutes.traceListForReportCategory(rid, cat))
                },
                onNavigateToTraceRunList = { runId ->
                    navController.navigate(NavRoutes.traceListForRun(runId))
                },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToInternalPromptEdit = { id ->
                    navController.navigate(NavRoutes.settingsInternalPromptEdit(id))
                },
                onNavigateToAgentsEdit = { navController.navigate(NavRoutes.SETTINGS_AGENTS) },
                onNavigateToFlocksEdit = { navController.navigate(NavRoutes.SETTINGS_FLOCKS) },
                onNavigateToSwarmsEdit = { navController.navigate(NavRoutes.SETTINGS_SWARMS) },
                onNavigateToInternalPromptsByCategory = { cat ->
                    navController.navigate(NavRoutes.settingsInternalPromptsByCategory(cat))
                },
                onContinueWithCurrent = { rid, aid ->
                    scope.launch {
                        val sessionId = continueReportInChat(
                            context, rid, aid,
                            aiSettings = appViewModel.uiState.value.aiSettings
                        ) ?: return@launch
                        navController.navigate(NavRoutes.aiChatContinue(sessionId))
                    }
                },
                onContinueWithAgentPicker = { rid, aid ->
                    scope.launch {
                        val response = readReportAgentResponse(context, rid, aid) ?: return@launch
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = response,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                        navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT)
                    }
                },
                onContinueWithOnTheFly = { rid, aid ->
                    scope.launch {
                        val response = readReportAgentResponse(context, rid, aid) ?: return@launch
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = response,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                        navController.navigate(NavRoutes.AI_CHAT_PROVIDER)
                    }
                },
                onChatWithReportPrompt = { prompt ->
                    appViewModel.updateUiState {
                        it.copy(
                            chatStarterText = prompt,
                            chatStarterImageBase64 = null,
                            chatStarterImageMime = null
                        )
                    }
                    navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT)
                },
                onNavigateToAppLog = { filename, search ->
                    navController.navigate(NavRoutes.aiAppLogDetail(filename, search))
                },
                onOpenReportManage = { rid ->
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, rid)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { rid ->
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, rid)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                })
            }
        }
        composable(NavRoutes.AI_PROMPT_HISTORY) {
            PromptHistoryScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectEntry = { navController.navigate(NavRoutes.aiNewReportWithParams(it.title, it.prompt)) })
        }
        composable(NavRoutes.AI_EXAMPLE_PROMPT_PICKER) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.history.ExamplePromptPickerScreen(
                aiSettings = uiState.aiSettings,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectEntry = { navController.navigate(NavRoutes.aiNewReportWithParams(it.title, it.text)) }
            )
        }

        // ===== History =====
        composable(NavRoutes.AI_HISTORY) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            HistoryScreenNav(
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onOpenReportResult = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = true)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                }
            )
        }

        // ===== Usage =====
        composable(NavRoutes.AI_USAGE) {
            val uiState by appViewModel.uiState.collectAsState()
            UsageScreen(
                openRouterApiKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.entries.firstOrNull { it.crossProviderModelList }?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                },
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) })
        }
        composable(NavRoutes.AI_COST_CONFIG) {
            val uiState by appViewModel.uiState.collectAsState()
            CostConfigurationScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome)
        }

        // ===== Models =====
        composable(NavRoutes.AI_MODEL_SEARCH) {
            val uiState by appViewModel.uiState.collectAsState()
            // Browse mode: tap a row → open that model's Model Info
            // page. Picker stays mounted on the back stack so back
            // from Model Info returns to the list, not the Hub.
            com.ai.ui.report.ReportSelectModelsScreen(
                aiSettings = uiState.aiSettings,
                titleText = "AI Models",
                onConfirm = { (p, m) -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_MODEL_INFO) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            if (provider != null) {
                // OpenRouter key has two storage slots: External Services
                // (generalSettings.openRouterApiKey) and the OpenRouter
                // chat-provider entry (aiSettings.getApiKey(OPENROUTER)).
                // Prefer the External Services one — that's the canonical
                // catalog-lookup key — and fall back to the provider key
                // for users who only ever used OpenRouter as a chat host.
                val orKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.entries.firstOrNull { it.crossProviderModelList }?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                }
                ModelInfoScreen(provider = provider, modelName = model,
                    openRouterApiKey = orKey,
                    huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey, aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    onSaveSettings = { appViewModel.updateSettings(it) },
                    onTestAiModel = { s, k, m -> appViewModel.testAiModel(s, k, m) },
                    onFetchModels = appViewModel::fetchModels,
                    onStartChat = { p, m -> navController.navigate(NavRoutes.aiChatParams(p.id, m)) },
                    onNavigateToTracesForModel = { p, m -> navController.navigate(NavRoutes.traceListForModel(p.id, m)) },
                    onNavigateToAddManualOverride = { p, m -> navController.navigate(NavRoutes.aiManualOverrideAdd(p.id, m)) },
                    onNavigateToAddCostOverride = { p, m -> navController.navigate(NavRoutes.aiManualCostOverrideAdd(p.id, m)) },
                    onNavigateToProviderEdit = { p -> navController.navigate(NavRoutes.settingsProviderEdit(p.id)) },
                    onNavigateToBlockedModels = { navController.navigate(NavRoutes.SETTINGS_BLOCKED_MODELS) },
                    onNavigateToInaccessibleModels = { navController.navigate(NavRoutes.SETTINGS_INACCESSIBLE_MODELS) },
                    onNavigateToCooldowns = { navController.navigate(NavRoutes.SETTINGS_MODEL_COOLDOWNS) },
                    onNavigateToModelTypes = { navController.navigate(NavRoutes.SETTINGS_MANUAL_MODEL_TYPES) },
                    onNavigateToAgentEdit = { aid -> navController.navigate(NavRoutes.settingsAgentEdit(aid)) },
                    onNavigateToFlockEdit = { fid -> navController.navigate(NavRoutes.settingsFlockEdit(fid)) },
                    onNavigateToSwarmEdit = { sid -> navController.navigate(NavRoutes.settingsSwarmEdit(sid)) },
                    onOpenReport = { rid ->
                        scope.launch {
                            reportViewModel.restoreCompletedReport(context, rid)
                            navController.navigate(NavRoutes.AI_REPORTS)
                        }
                    },
                    onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome)
            }
        }
        // View-flavoured Model Info — read-only sibling of AI_MODEL_INFO
        // reached from any model-name click on a View Report screen.
        composable(NavRoutes.AI_MODEL_INFO_VIEW) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            if (provider != null) {
                val orKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.entries.firstOrNull { it.crossProviderModelList }?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                }
                ViewSubScreenWithTitleNav(
                    navController = navController,
                    currentReportId = uiState.currentReportId
                ) {
                    com.ai.ui.models.ModelInfoViewScreen(
                        provider = provider,
                        modelName = model,
                        openRouterApiKey = orKey,
                        huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey,
                        aiSettings = uiState.aiSettings,
                        repository = appViewModel.repository,
                        onOpenReport = { rid ->
                            scope.launch {
                                reportViewModel.restoreCompletedReport(context, rid)
                                navController.navigate(NavRoutes.AI_REPORTS)
                            }
                        },
                        onBack = safePopBack
                    )
                }
            }
        }
        composable(NavRoutes.AI_AGENT_VIEW) { entry ->
            val agentId = entry.arguments?.getString("agentId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = uiState.currentReportId
            ) {
                com.ai.ui.settings.AgentViewScreen(
                    agentId = agentId,
                    aiSettings = uiState.aiSettings,
                    onBack = safePopBack
                )
            }
        }
        composable(NavRoutes.AI_FLOCK_VIEW) { entry ->
            val flockId = entry.arguments?.getString("flockId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = uiState.currentReportId
            ) {
                com.ai.ui.settings.FlockViewScreen(
                    flockId = flockId,
                    aiSettings = uiState.aiSettings,
                    onBack = safePopBack
                )
            }
        }
        composable(NavRoutes.AI_SWARM_VIEW) { entry ->
            val swarmId = entry.arguments?.getString("swarmId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = uiState.currentReportId
            ) {
                com.ai.ui.settings.SwarmViewScreen(
                    swarmId = swarmId,
                    aiSettings = uiState.aiSettings,
                    onBack = safePopBack
                )
            }
        }
        composable(NavRoutes.AI_MANUAL_OVERRIDE_ADD) { entry ->
            val providerId = entry.arguments?.getString("provider") ?: ""
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            ManualModelOverrideEntryScreen(
                aiSettings = uiState.aiSettings,
                providerId = providerId,
                modelId = model,
                onSave = { appViewModel.updateSettings(it) },
                onBack = safePopBack
            )
        }
        composable(NavRoutes.AI_MANUAL_COST_OVERRIDE_ADD) { entry ->
            val providerId = entry.arguments?.getString("provider") ?: ""
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            ManualCostOverrideEntryScreen(
                aiSettings = uiState.aiSettings,
                providerId = providerId,
                modelId = model,
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }

        // ===== Chat =====
        composable(NavRoutes.AI_CHATS_HUB) {
            val uiState by appViewModel.uiState.collectAsState()
            ChatsHubScreen(aiSettings = uiState.aiSettings, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToAgentSelect = { navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT) },
                onNavigateToNewChat = { navController.navigate(NavRoutes.AI_CHAT_PROVIDER) },
                onNavigateToChatHistory = { navController.navigate(NavRoutes.AI_CHAT_HISTORY) },
                onNavigateToChatSearch = { navController.navigate(NavRoutes.AI_CHAT_SEARCH) },
                onResumeSession = { sessionId -> navController.navigate(NavRoutes.aiChatContinue(sessionId)) },
                onNavigateToManage = { navController.navigate(NavRoutes.AI_CHAT_MANAGE) },
                // Hub card "Chat with a local LLM" — same destination
                // as a configure-on-the-fly chat that picked the
                // synthetic LOCAL provider, so the standard
                // AI_CHAT_SESSION composable handles routing.
                onNavigateToLocalLlmChat = { model -> navController.navigate(NavRoutes.aiChatSession("LOCAL", model)) },
                onNavigateToDualChat = { navController.navigate(NavRoutes.AI_DUAL_CHAT_SETUP) },
                onStartWithPhoto = { mime, b64 ->
                    // Stage the photo for the chat session screen at
                    // the end of the configure-on-the-fly chain. The
                    // user picks provider / model / params normally;
                    // ChatSessionScreen seeds attachedImage from
                    // these UiState fields on first composition and
                    // clears them via onConsumeStarter.
                    appViewModel.updateUiState { it.copy(chatStarterImageBase64 = b64, chatStarterImageMime = mime) }
                    navController.navigate(NavRoutes.AI_CHAT_PROVIDER)
                })
        }
        composable(NavRoutes.AI_CHAT_AGENT_SELECT) {
            val uiState by appViewModel.uiState.collectAsState()
            SelectAgentScreen(aiSettings = uiState.aiSettings,
                onSelectAgent = { navController.navigate(NavRoutes.aiChatWithAgent(it.id)) },
                onBack = safePopBack, onNavigateHome = navigateHome)
        }
        composable(NavRoutes.AI_CHAT_WITH_AGENT) { entry ->
            val agentId = entry.arguments?.getString("agentId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            val agent = remember(agentId, uiState.aiSettings.agents) { uiState.aiSettings.agents.find { it.id == agentId } }

            if (agent != null) {
                val resolvedParams = uiState.aiSettings.resolveAgentParameters(agent)
                val chatParams = ChatParameters(
                    temperature = resolvedParams.temperature, maxTokens = resolvedParams.maxTokens,
                    topP = resolvedParams.topP, topK = resolvedParams.topK,
                    frequencyPenalty = resolvedParams.frequencyPenalty, presencePenalty = resolvedParams.presencePenalty,
                    systemPrompt = resolvedParams.systemPrompt ?: "", searchEnabled = resolvedParams.searchEnabled,
                    returnCitations = resolvedParams.returnCitations, searchRecency = resolvedParams.searchRecency,
                    webSearchTool = resolvedParams.webSearchTool
                )
                val endpointUrl = uiState.aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val customBaseUrl = if (endpointUrl != agent.provider.baseUrl) endpointUrl else null
                val effectiveApiKey = uiState.aiSettings.getEffectiveApiKeyForAgent(agent)
                val effectiveModel = uiState.aiSettings.getEffectiveModelForAgent(agent)
                val agentChatContext = LocalContext.current

                ChatSessionScreen(
                    provider = agent.provider, model = effectiveModel, parameters = chatParams,
                    userName = uiState.generalSettings.userName, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onSendMessageStream = { messages, webSearch, reasoning, kbs -> chatViewModel.sendChatMessageStream(agent.provider, effectiveApiKey, effectiveModel, messages, sessionParams = chatParams, baseUrl = customBaseUrl, webSearchTool = webSearch, reasoningEffort = reasoning, context = agentChatContext, knowledgeBaseIds = kbs) },
                    onRecordStatistics = { inp, out -> chatViewModel.recordChatStatistics(agent.provider, effectiveModel, inp, out) },
                    aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    isVisionCapable = uiState.aiSettings.isVisionCapable(agent.provider, effectiveModel),
                    onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                    initialUserInput = uiState.chatStarterText,
                    initialUserImageBase64 = uiState.chatStarterImageBase64,
                    initialUserImageMime = uiState.chatStarterImageMime,
                    onConsumeStarter = {
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = null,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                    }
                )
            } else {
                LaunchedEffect(Unit) { safePopBack() }
            }
        }
        composable(NavRoutes.AI_KNOWLEDGE) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.knowledge.KnowledgeListScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenKb = { kbId -> navController.navigate(NavRoutes.aiKnowledgeDetail(kbId)) },
                onCreateKb = { navController.navigate(NavRoutes.AI_KNOWLEDGE_NEW) },
                pendingUris = uiState.pendingKnowledgeUris,
                onConsumePending = { appViewModel.updateUiState { it.copy(pendingKnowledgeUris = emptyList()) } }
            )
        }
        composable(NavRoutes.AI_KNOWLEDGE_NEW) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.knowledge.NewKnowledgeBaseScreen(
                aiSettings = uiState.aiSettings,
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onCreated = { kbId ->
                    navController.popBackStack()
                    navController.navigate(NavRoutes.aiKnowledgeDetail(kbId))
                }
            )
        }
        composable(NavRoutes.AI_KNOWLEDGE_DETAIL) { entry ->
            val kbId = entry.arguments?.getString("kbId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.knowledge.KnowledgeDetailScreen(
                aiSettings = uiState.aiSettings,
                repository = appViewModel.repository,
                kbId = kbId,
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                pendingUris = uiState.pendingKnowledgeUris,
                onConsumePending = { appViewModel.updateUiState { it.copy(pendingKnowledgeUris = emptyList()) } }
            )
        }
        composable(NavRoutes.AI_CHAT_MANAGE) {
            com.ai.ui.chat.ChatManageScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_CHAT_SEARCH) {
            ChatSearchScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectSession = { navController.navigate(NavRoutes.aiChatContinue(it)) })
        }
        composable(NavRoutes.AI_CHAT_PROVIDER) {
            val uiState by appViewModel.uiState.collectAsState()
            // Configure-on-the-fly entry: same picker as the New
            // Report's +Model button. Row click jumps straight to
            // AI_CHAT_PARAMS with the chosen (provider, model).
            com.ai.ui.report.ReportSelectModelsScreen(
                aiSettings = uiState.aiSettings,
                titleText = "Pick model for chat",
                onConfirm = { (provider, model) ->
                    navController.navigate(NavRoutes.aiChatParams(provider.id, model))
                },
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_CHAT_PARAMS) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                ChatParametersScreen(provider = provider, model = model, aiSettings = uiState.aiSettings,
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onStartChat = { params -> appViewModel.setChatParameters(params); navController.navigate(NavRoutes.aiChatSession(provider.id, model)) })
            }
        }
        composable(NavRoutes.AI_CHAT_SESSION) { entry ->
            val context = LocalContext.current
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                val apiKey = uiState.aiSettings.getApiKey(provider)
                val isLocal = provider.id == AppService.LOCAL.id
                ChatSessionScreen(
                    provider = provider, model = model, parameters = uiState.chatParameters,
                    userName = uiState.generalSettings.userName, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    // LOCAL routes through MediaPipe LLM Inference; the
                    // remote streaming protocol (web-search / reasoning
                    // params) doesn't apply, so we just forward the
                    // turn list to LocalLlm.generate via
                    // ChatViewModel.sendLocalLlmStream. The chat screen
                    // calls this with its current ChatSession.knowledgeBaseIds
                    // attached as the trailing argument (4th
                    // parameter) — see ChatScreens.kt for the wiring.
                    onSendMessageStream = if (isLocal) {
                        { messages, _, _, kbs -> chatViewModel.sendLocalLlmStream(context, model, messages, kbs) }
                    } else {
                        { messages, webSearch, reasoning, kbs ->
                            chatViewModel.sendChatMessageStream(provider, apiKey, model, messages,
                                sessionParams = uiState.chatParameters,
                                webSearchTool = webSearch, reasoningEffort = reasoning,
                                context = context, knowledgeBaseIds = kbs)
                        }
                    },
                    onRecordStatistics = if (isLocal) {
                        { _, _ -> /* no remote billing for local */ }
                    } else {
                        { inp, out -> chatViewModel.recordChatStatistics(provider, model, inp, out) }
                    },
                    aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    isVisionCapable = !isLocal && uiState.aiSettings.isVisionCapable(provider, model),
                    onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                    initialUserInput = uiState.chatStarterText,
                    initialUserImageBase64 = uiState.chatStarterImageBase64,
                    initialUserImageMime = uiState.chatStarterImageMime,
                    onConsumeStarter = {
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = null,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                    }
                )
            }
        }
        composable(NavRoutes.AI_CHAT_HISTORY) {
            ChatHistoryScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectSession = { navController.navigate(NavRoutes.aiChatContinue(it)) },
                onOpenTraces = { sid -> navController.navigate(NavRoutes.traceListForReport(sid)) })
        }
        composable(NavRoutes.AI_CHAT_CONTINUE) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            val session = remember(sessionId) { ChatHistoryManager.loadSession(sessionId) }
            if (session != null) {
                val apiKey = uiState.aiSettings.getApiKey(session.provider)
                val sessionContext = LocalContext.current
                val isLocalSession = session.provider.id == AppService.LOCAL.id
                ChatSessionScreen(
                    provider = session.provider, model = session.model, parameters = session.parameters,
                    userName = uiState.generalSettings.userName, initialMessages = session.messages, sessionId = session.id,
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onSendMessageStream = if (isLocalSession) {
                        { messages, _, _, kbs -> chatViewModel.sendLocalLlmStream(sessionContext, session.model, messages, kbs) }
                    } else {
                        { messages, webSearch, reasoning, kbs ->
                            chatViewModel.sendChatMessageStream(session.provider, apiKey, session.model, messages,
                                sessionParams = session.parameters,
                                webSearchTool = webSearch, reasoningEffort = reasoning,
                                context = sessionContext, knowledgeBaseIds = kbs)
                        }
                    },
                    onRecordStatistics = if (isLocalSession) {
                        { _, _ -> /* no remote billing for local */ }
                    } else {
                        { inp, out -> chatViewModel.recordChatStatistics(session.provider, session.model, inp, out) }
                    },
                    aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    isVisionCapable = !isLocalSession && uiState.aiSettings.isVisionCapable(session.provider, session.model),
                    onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) }
                )
            } else {
                LaunchedEffect(Unit) { safePopBack() }
            }
        }

        // ===== Dual Chat =====
        composable(NavRoutes.AI_DUAL_CHAT_SETUP) {
            val uiState by appViewModel.uiState.collectAsState()
            DualChatSetupScreen(aiSettings = uiState.aiSettings, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onStartSession = { config -> appViewModel.setDualChatConfig(config); navController.navigate(NavRoutes.AI_DUAL_CHAT_SESSION) })
        }
        composable(NavRoutes.AI_DUAL_CHAT_SESSION) {
            val uiState by appViewModel.uiState.collectAsState()
            DualChatSessionScreen(appViewModel = appViewModel, chatViewModel = chatViewModel, aiSettings = uiState.aiSettings,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) })
        }

        // ===== Admin =====
        composable(NavRoutes.AI_HOUSEKEEPING) {
            val uiState by appViewModel.uiState.collectAsState()
            val hasActiveProvider = uiState.aiSettings.getActiveServices().isNotEmpty()
            val ctx = LocalContext.current
            // Recompute on every screen-resume so trimming everything
            // away (or a fresh restore that left nothing behind) hides
            // the card the moment the user pops back here.
            val resumeTick = com.ai.ui.shared.resumeRefreshTick()
            val hasTrimmable by produceState(initialValue = false, resumeTick) {
                value = withContext(Dispatchers.IO) {
                    com.ai.data.ReportStorage.getAllReports(ctx).isNotEmpty() ||
                        com.ai.data.ChatHistoryManager.getSessionCount() > 0 ||
                        com.ai.data.ApiTracer.hasAnyTraceFile()
                }
            }
            com.ai.ui.admin.HousekeepingScreen(
                onBackToHome = navigateHome,
                hasActiveProvider = hasActiveProvider,
                hasTrimmable = hasTrimmable,
                onNavigateToBackupRestore = { navController.navigate(NavRoutes.AI_BACKUP_RESTORE) },
                onNavigateToImportExport = { navController.navigate(NavRoutes.AI_IMPORT_EXPORT) },
                onNavigateToRefresh = { navController.navigate(NavRoutes.AI_REFRESH) },
                onNavigateToTrimByAge = { navController.navigate(NavRoutes.AI_TRIM_BY_AGE) },
                onNavigateToReset = { navController.navigate(NavRoutes.AI_RESET) },
                onNavigateToAppLog = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onNavigateToTest = { navController.navigate(NavRoutes.AI_TEST) },
                onNavigateToUpdateFromCloud = { navController.navigate(NavRoutes.AI_UPDATE_FROM_CLOUD) }
            )
        }
        composable(NavRoutes.AI_UPDATE_FROM_CLOUD) {
            com.ai.ui.admin.UpdateFromCloudScreen(onBack = safePopBack)
        }
        composable(NavRoutes.AI_TEST) {
            com.ai.ui.admin.TestScreen(
                onBack = safePopBack,
                onOpenTestAllModels = { navController.navigate(NavRoutes.AI_TEST_ALL_MODELS) }
            )
        }
        composable(NavRoutes.AI_TEST_ALL_MODELS) {
            com.ai.ui.report.ModelTestScreen(
                engine = reportViewModel.modelTestEngine,
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToTraceRunList = { runId -> navController.navigate(NavRoutes.traceListForRun(runId)) },
                onNavigateToModelInfo = { svc, model ->
                    navController.navigate(NavRoutes.aiModelInfo(svc.id, model))
                },
                onNavigateToProvider = { svc ->
                    navController.navigate(NavRoutes.settingsProviderEdit(svc.id))
                },
                onBack = safePopBack
            )
        }
        composable(NavRoutes.AI_APPLOG_LIST) {
            com.ai.ui.admin.AppLogListScreen(
                onBack = safePopBack,
                onSelectLog = { name -> navController.navigate(NavRoutes.aiAppLogDetail(name)) }
            )
        }
        composable(
            NavRoutes.AI_APPLOG_DETAIL,
            arguments = listOf(
                navArgument("search") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val filename = entry.arguments?.getString("filename") ?: ""
            val search = entry.arguments?.getString("search") ?: ""
            com.ai.ui.admin.AppLogDetailScreen(
                filename = filename,
                onBack = safePopBack,
                onNavigateToTrace = { tf -> navController.navigate(NavRoutes.traceDetail(tf)) },
                initialSearch = search
            )
        }
        composable(NavRoutes.AI_BACKUP_RESTORE) {
            val uiState by appViewModel.uiState.collectAsState()
            val hasActiveProvider = uiState.aiSettings.getActiveServices().isNotEmpty()
            com.ai.ui.admin.BackupRestoreScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                restoreOnly = !hasActiveProvider
            )
        }
        composable(NavRoutes.AI_TRIM_BY_AGE) {
            com.ai.ui.admin.TrimByAgeScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_RESET) {
            com.ai.ui.admin.ResetScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onOpenRuntimeData = { navController.navigate(NavRoutes.AI_RESET_RUNTIME) },
                onOpenInfoProviders = { navController.navigate(NavRoutes.AI_RESET_INFO_PROVIDERS) },
                onOpenConfiguration = { navController.navigate(NavRoutes.AI_RESET_CONFIGURATION) },
                onOpenAssets = { navController.navigate(NavRoutes.AI_RESET_ASSETS) },
                onOpenApplication = { navController.navigate(NavRoutes.AI_RESET_APPLICATION) }
            )
        }
        composable(NavRoutes.AI_RESET_RUNTIME) {
            val ctx = LocalContext.current
            com.ai.ui.admin.ResetRuntimeDataScreen(
                onClearRuntimeData = {
                    appViewModel.clearAllRuntimeData(ctx).also {
                        reportViewModel.modelTestEngine.clearRun()
                    }
                },
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_RESET_INFO_PROVIDERS) {
            val ctx = LocalContext.current
            com.ai.ui.admin.ResetInfoProvidersScreen(
                onClearInfoProviders = { appViewModel.clearInfoProviderCaches(ctx) },
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_RESET_CONFIGURATION) {
            val ctx = LocalContext.current
            com.ai.ui.admin.ResetConfigurationScreen(
                onClearConfiguration = { appViewModel.clearAllConfiguration(ctx) },
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_RESET_ASSETS) {
            val ctx = LocalContext.current
            com.ai.ui.admin.ResetAssetsScreen(
                onRestartProvidersFromAsset = { com.ai.data.ProviderRegistry.restartFromAsset(ctx) },
                onResetInternalPromptsFromAsset = { appViewModel.resetInternalPromptsFromAssets() },
                onResetExamplePromptsFromAsset = { appViewModel.resetExamplePromptsFromAssets() },
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_RESET_APPLICATION) {
            val ctx = LocalContext.current
            com.ai.ui.admin.ResetApplicationScreen(
                onResetApplication = { onComplete -> appViewModel.resetApplication(ctx, onComplete) },
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onStartRefreshAll = {
                    appViewModel.startRefreshAll()
                    navController.navigate(NavRoutes.AI_REFRESH)
                },
                onStartRefreshWorkers = {
                    appViewModel.startRefreshWorkers()
                    navController.navigate(NavRoutes.AI_REFRESH)
                },
                onNavigateToImportExport = {
                    navController.navigate(NavRoutes.AI_IMPORT_EXPORT)
                }
            )
        }
        composable(NavRoutes.AI_IMPORT_EXPORT) {
            SettingsScreenNav(
                viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                onNavigateToRefresh = {
                    // Pop AI_IMPORT_EXPORT off the stack as we navigate
                    // to AI_REFRESH so a back-press from Refresh lands
                    // on the Housekeeping hub (the screen the user
                    // originally came from) rather than bouncing back
                    // into Import / Export.
                    navController.navigate(NavRoutes.AI_REFRESH) {
                        popUpTo(NavRoutes.AI_IMPORT_EXPORT) { inclusive = true }
                    }
                },
                initialSubScreen = SettingsSubScreen.AI_IMPORT_EXPORT
            )
        }
        composable(NavRoutes.AI_REFRESH) {
            SettingsScreenNav(
                viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_REFRESH
            )
        }
        composable(NavRoutes.HELP) {
            HelpScreen(
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                onNavigateToHelpHome = { /* already on Help home */ },
                onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) }
            )
        }
        composable(NavRoutes.DOCUMENTATION) {
            com.ai.ui.admin.DocumentationScreen(
                onBack = safePopBack,
                docsSubdir = "technical",
                title = "Technical documentation",
                helpTopic = "technical_documentation"
            )
        }
        composable(NavRoutes.DOCUMENTATION_MANUAL) {
            com.ai.ui.admin.DocumentationScreen(
                onBack = safePopBack,
                docsSubdir = "manual",
                title = "Manual",
                helpTopic = "manual"
            )
        }
        composable(NavRoutes.ABOUT) {
            com.ai.ui.admin.AboutScreen(
                onBack = safePopBack,
                onOpenManual = { navController.navigate(NavRoutes.DOCUMENTATION_MANUAL) },
                onOpenTechnicalDocs = { navController.navigate(NavRoutes.DOCUMENTATION) }
            )
        }
        composable(NavRoutes.HELP_FOR_TOPIC) { entry ->
            val topicId = try {
                java.net.URLDecoder.decode(entry.arguments?.getString("topicId") ?: "", "UTF-8")
            } catch (_: Exception) { "" }
            HelpScreen(
                topicId = topicId, onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                onNavigateToHelpHome = { navController.navigate(NavRoutes.HELP) },
                onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) }
            )
        }
        composable(NavRoutes.TRACE_LIST) {
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() })
        }
        composable(NavRoutes.TRACE_LIST_FOR_REPORT) { entry ->
            val reportId = entry.arguments?.getString("reportId") ?: ""
            // 📝 Memo on the TitleBar pops back to the AI Reports
            // result page — same anchor every "deeper than result"
            // overlay points at. Falls back to plain navigate when
            // AI_REPORTS isn't on the back stack (e.g. a deep link).
            val backToReport: () -> Unit = {
                if (!navController.popBackStack(NavRoutes.AI_REPORTS, false))
                    navController.navigate(NavRoutes.AI_REPORTS)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalNavigateToCurrentReport provides backToReport
            ) {
                val uiState by appViewModel.uiState.collectAsState()
                TraceListScreen(aiSettings = uiState.aiSettings,
                    onBack = safePopBack, onNavigateHome = navigateHome,
                    onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                    onClearTraces = { appViewModel.clearTraces() }, reportId = reportId)
            }
        }
        composable(NavRoutes.TRACE_LIST_FOR_REPORT_CATEGORY) { entry ->
            val reportId = entry.arguments?.getString("reportId") ?: ""
            val category = try {
                java.net.URLDecoder.decode(entry.arguments?.getString("category") ?: "", "UTF-8")
            } catch (_: Exception) { "" }
            val backToReport: () -> Unit = {
                if (!navController.popBackStack(NavRoutes.AI_REPORTS, false))
                    navController.navigate(NavRoutes.AI_REPORTS)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalNavigateToCurrentReport provides backToReport
            ) {
                val uiState by appViewModel.uiState.collectAsState()
                TraceListScreen(aiSettings = uiState.aiSettings,
                    onBack = safePopBack, onNavigateHome = navigateHome,
                    onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                    onClearTraces = { appViewModel.clearTraces() },
                    reportId = reportId, initialCategory = category)
            }
        }
        composable(NavRoutes.TRACE_LIST_FOR_MODEL) { entry ->
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, modelFilter = model)
        }
        composable(NavRoutes.TRACE_LIST_FOR_RUN) { entry ->
            val runId = try { java.net.URLDecoder.decode(entry.arguments?.getString("runId") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, runIdFilter = runId)
        }
        composable(NavRoutes.TRACE_DETAIL) { entry ->
            val filename = entry.arguments?.getString("filename") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            val traceDetailContext = LocalContext.current
            val traceDetailScope = rememberCoroutineScope()
            TraceDetailScreen(
                filename = filename, aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onEditRequest = { navController.navigate(NavRoutes.AI_API_TEST_EDIT) },
                onNavigateToProvider = { p -> navController.navigate(NavRoutes.settingsProviderEdit(p.id)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToEditAgent = { id -> navController.navigate(NavRoutes.settingsAgentEdit(id)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                onOpenReport = { reportId ->
                    traceDetailScope.launch {
                        reportViewModel.restoreCompletedReport(traceDetailContext, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { reportId ->
                    traceDetailScope.launch {
                        reportViewModel.restoreCompletedReport(traceDetailContext, reportId)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                }
            )
        }
        composable(NavRoutes.SETTINGS_PROVIDER_EDIT) { entry ->
            val pid = entry.arguments?.getString("providerId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT,
                initialProviderId = pid)
        }
        composable(NavRoutes.SETTINGS_AGENT_EDIT) { entry ->
            val aid = entry.arguments?.getString("agentId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_AGENT_EDIT,
                initialEditingAgentId = aid)
        }
        composable(NavRoutes.SETTINGS_INTERNAL_PROMPT_EDIT) { entry ->
            val pid = entry.arguments?.getString("promptId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT,
                initialEditingInternalPromptId = pid)
        }
        composable(NavRoutes.SETTINGS_AGENTS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_AGENTS)
        }
        composable(NavRoutes.SETTINGS_FLOCK_EDIT) { entry ->
            val fid = entry.arguments?.getString("flockId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_FLOCK_EDIT,
                initialEditingFlockId = fid)
        }
        composable(NavRoutes.SETTINGS_SWARM_EDIT) { entry ->
            val sid = entry.arguments?.getString("swarmId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_SWARM_EDIT,
                initialEditingSwarmId = sid)
        }
        composable(NavRoutes.SETTINGS_BLOCKED_MODELS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_BLOCKED_MODELS)
        }
        composable(NavRoutes.SETTINGS_INACCESSIBLE_MODELS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_INACCESSIBLE_MODELS)
        }
        composable(NavRoutes.SETTINGS_MODEL_COOLDOWNS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_MODEL_COOLDOWNS)
        }
        composable(NavRoutes.SETTINGS_MANUAL_MODEL_TYPES) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_MANUAL_MODEL_TYPES)
        }
        composable(NavRoutes.SETTINGS_FLOCKS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_FLOCKS)
        }
        composable(NavRoutes.SETTINGS_SWARMS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_SWARMS)
        }
        composable(NavRoutes.SETTINGS_INTERNAL_PROMPTS_BY_CATEGORY) { entry ->
            val cat = try {
                java.net.URLDecoder.decode(entry.arguments?.getString("category") ?: "meta", "UTF-8")
            } catch (_: Exception) { "meta" }
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS,
                initialInternalPromptCategory = cat)
        }
        composable(NavRoutes.AI_API_TEST) {
            ApiTestScreen(onBackClick = safePopBack, onNavigateHome = navigateHome,
                onNavigateToEditRequest = { navController.navigate(NavRoutes.AI_API_TEST_EDIT) }, viewModel = appViewModel)
        }
        composable(NavRoutes.AI_API_TEST_EDIT) {
            EditApiRequestScreen(onBackClick = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTraceDetail = { navController.navigate(NavRoutes.traceDetail(it)) })
        }
    }
    // Hide the bar on the home Hub — that screen has no TitleBar
    // (it's the centered "AI" logo) so the bar would just show
    // the bare Home + Help fallback. The Hub already routes home /
    // help via its own card list; no need for a duplicate strip.
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    if (currentRoute != NavRoutes.AI) {
        com.ai.ui.shared.BottomIconBar(icons = bottomBarIconState.value)
    }
    } // end Column
    } // end CompositionLocalProvider
}

// ===== Navigation Wrappers =====

@Composable
fun SettingsScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToHelpTopic: (String) -> Unit = {},
    /** Forwarded into ImportExportScreen so the post-API-keys-import
     *  dialog's "Run Refresh all" branch can land the user on the
     *  Refresh sub-screen (where the progress overlay paints). */
    onNavigateToRefresh: () -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    initialProviderId: String? = null,
    initialEditingAgentId: String? = null,
    initialEditingFlockId: String? = null,
    initialEditingSwarmId: String? = null,
    initialEditingInternalPromptId: String? = null,
    initialInternalPromptCategory: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val refreshAllState by viewModel.refreshAllState.collectAsState()
    SettingsScreen(
        generalSettings = uiState.generalSettings, aiSettings = uiState.aiSettings,
        loadingModelsFor = uiState.loadingModelsFor,
        fetchModelsErrors = uiState.fetchModelsErrors,
        onFetchModels = viewModel::fetchModels,
        onFetchModelsAwait = { s, k -> viewModel.fetchModelsAwait(s, k, flipToApiOnSuccess = true) },
        onBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onSaveGeneral = { viewModel.updateGeneralSettings(it) }, onSaveAi = { viewModel.updateSettings(it) },
        onTestAiModel = { s, k, m -> viewModel.testAiModel(s, k, m) },
        onProviderStateChange = { s, st -> viewModel.updateProviderState(s, st) },
        onProviderTestedOk = { s, m -> viewModel.markProviderTestedOk(s, m) },
        onProviderTestedOkNoFetch = { s, m -> viewModel.markProviderTestedOk(s, m, fetchAfter = false) },
        onReplaceDefaultAgent = { s, m -> viewModel.replaceDefaultAgent(s, m) },
        onRefreshAllModels = { settings, force, progress -> viewModel.refreshAllModelLists(settings, force, progress) },
        refreshAllState = refreshAllState,
        onStartRefreshAll = { viewModel.startRefreshAll() },
        onStartRefreshWorkers = { viewModel.startRefreshWorkers() },
        onClearRefreshAllState = { viewModel.clearRefreshAllState() },
        onNavigateToRefresh = onNavigateToRefresh,
        onSaveHuggingFaceApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(huggingFaceApiKey = it)) },
        onSaveOpenRouterApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(openRouterApiKey = it)) },
        onSaveArtificialAnalysisApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(artificialAnalysisApiKey = it)) },
        onNavigateToCostConfig = onNavigateToCostConfig,
        onNavigateToHelpTopic = onNavigateToHelpTopic,
        onTestModelWithPrompt = { s, k, m, p -> viewModel.testModelWithPrompt(s, k, m, p) },
        onTestSpecificModel = { s, k, m, p -> viewModel.testSpecificModel(s, k, m, p) },
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        initialSubScreen = initialSubScreen,
        initialProviderId = initialProviderId,
        initialEditingAgentId = initialEditingAgentId,
        initialEditingFlockId = initialEditingFlockId,
        initialEditingSwarmId = initialEditingSwarmId,
        initialEditingInternalPromptId = initialEditingInternalPromptId,
        initialInternalPromptCategory = initialInternalPromptCategory
    )
}

@Composable
fun SetupScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToHelpTopic: (String) -> Unit = {}
) {
    SettingsScreenNav(viewModel = viewModel, onNavigateBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onNavigateToCostConfig = onNavigateToCostConfig, onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onNavigateToHelpTopic = onNavigateToHelpTopic,
        initialSubScreen = SettingsSubScreen.AI_SETUP)
}

/** Route a SharedContent payload onto the New Report flow. Text /
 *  subject become title + prompt; the first image attachment (if
 *  any) becomes the report's vision attachment via base64; non-image
 *  attachments don't have a per-report home (Knowledge handles
 *  documents) so they're dropped quietly here — the user can still
 *  send the same payload to Knowledge separately. */
private suspend fun routeShareToReport(
    context: android.content.Context,
    appViewModel: AppViewModel,
    navController: androidx.navigation.NavHostController,
    shared: com.ai.data.SharedContent
) {
    val title = shared.subject?.takeIf { it.isNotBlank() } ?: ""
    val prompt = shared.text?.takeIf { it.isNotBlank() } ?: ""
    // Partition URIs by mime — first image-typed one becomes the
    // vision attachment; everything else queues for auto-attach as
    // a new knowledge base on the New Report screen. This honours
    // the chooser copy ("text → prompt; images → vision; files →
    // Knowledge") instead of silently dropping the docs.
    fun mimeOf(uri: String): String? =
        runCatching { context.contentResolver.getType(android.net.Uri.parse(uri)) }.getOrNull()
            ?: shared.mime
    val (imageUris, nonImageUris) = shared.uris.partition { mimeOf(it)?.startsWith("image/") == true }
    val firstImageUri = imageUris.firstOrNull()
    if (firstImageUri != null) {
        // Decode + downscale + JPEG-encode rather than streaming the raw
        // bytes — a 12 MP phone photo would otherwise spike memory and
        // ship a multi-MB base64 blob over the wire.
        val pair = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.ai.data.loadImageAsBase64(context, android.net.Uri.parse(firstImageUri))
            }.getOrNull()
        }
        if (pair != null) {
            appViewModel.updateUiState {
                it.copy(reportImageBase64 = pair.second, reportImageMime = pair.first)
            }
        }
    }
    if (nonImageUris.isNotEmpty()) {
        appViewModel.updateUiState { it.copy(pendingReportKnowledgeUris = nonImageUris) }
    }
    navController.navigate(NavRoutes.aiNewReportWithParams(title, prompt)) {
        popUpTo(NavRoutes.AI) { inclusive = false }
    }
}

/** Build a fresh ChatSession seeded with the report's prompt as the
 *  user turn and the chosen agent's response as the assistant turn,
 *  persist it via [com.ai.data.ChatHistoryManager], and return the
 *  session id so the caller can navigate to AI_CHAT_CONTINUE. The
 *  new session is independent of the source report — editing or
 *  deleting either one does not affect the other.
 *
 *  Uses the agent's actual provider/model so the user keeps talking
 *  to the same model that produced the report response. The first
 *  user turn carries the report's vision attachment if there was
 *  one. The new session inherits the agent's resolved system prompt
 *  + parameters from current settings (same mapping the
 *  AI_CHAT_WITH_AGENT route uses); if the agent has been deleted
 *  since the report was written we fall back to ChatParameters
 *  defaults. */
private suspend fun continueReportInChat(
    context: android.content.Context,
    reportId: String,
    agentId: String,
    aiSettings: com.ai.model.Settings
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val report = com.ai.data.ReportStorage.getReport(context, reportId) ?: return@withContext null
    val agent = report.agents.firstOrNull { it.agentId == agentId } ?: return@withContext null
    val provider = AppService.findById(agent.provider) ?: return@withContext null
    val responseBody = agent.responseBody?.takeIf { it.isNotBlank() } ?: return@withContext null

    val settingsAgent = aiSettings.getAgentById(agentId)
    val chatParams = if (settingsAgent != null) {
        val rp = aiSettings.resolveAgentParameters(settingsAgent)
        com.ai.data.ChatParameters(
            temperature = rp.temperature, maxTokens = rp.maxTokens,
            topP = rp.topP, topK = rp.topK,
            frequencyPenalty = rp.frequencyPenalty, presencePenalty = rp.presencePenalty,
            systemPrompt = rp.systemPrompt ?: "",
            searchEnabled = rp.searchEnabled, returnCitations = rp.returnCitations,
            searchRecency = rp.searchRecency, webSearchTool = rp.webSearchTool
        )
    } else com.ai.data.ChatParameters()

    val now = System.currentTimeMillis()
    val session = com.ai.data.ChatSession(
        provider = provider,
        model = agent.model,
        messages = listOf(
            com.ai.data.ChatMessage(
                role = "user",
                content = report.prompt,
                timestamp = report.timestamp,
                imageBase64 = report.imageBase64,
                imageMime = report.imageMime
            ),
            com.ai.data.ChatMessage(
                role = "assistant",
                content = responseBody,
                timestamp = (agent.durationMs ?: 0L).let { d -> if (d > 0) report.timestamp + d else now }
            )
        ),
        parameters = chatParams,
        createdAt = now,
        updatedAt = now
    )
    if (com.ai.data.ChatHistoryManager.saveSession(session)) session.id else null
}

/** Fetch just the assistant response text for one agent of one
 *  report. Used by the "Continue in chat … with this response only"
 *  flows to stash the text as `chatStarterText` before routing into
 *  the agent picker / configure-on-the-fly chains. */
private suspend fun readReportAgentResponse(
    context: android.content.Context,
    reportId: String,
    agentId: String
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val report = com.ai.data.ReportStorage.getReport(context, reportId) ?: return@withContext null
    val agent = report.agents.firstOrNull { it.agentId == agentId } ?: return@withContext null
    agent.responseBody?.takeIf { it.isNotBlank() }
}

/** Wraps the four standalone Jetpack-Nav View screens
 *  (ModelInfoView / AgentView / FlockView / SwarmView) in a
 *  CompositionLocalProvider that exposes a "navigate to main View
 *  of the active report" callback via [LocalNavigateToCurrentReport].
 *  ViewScreenTitleBar reads that local to decide whether the report
 *  title is clickable + where it goes. When there's no active
 *  report ([currentReportId] is null) the local resolves to null
 *  and the title stays inert — matching the existing rule on Help
 *  pages and other no-report-context screens. */
@androidx.compose.runtime.Composable
private fun ViewSubScreenWithTitleNav(
    navController: androidx.navigation.NavHostController,
    currentReportId: String?,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    val navToActiveView: (() -> Unit)? = currentReportId?.let {
        {
            // Pop back to the existing AI_REPORTS route if it's
            // already on the stack — preserves the user's mode +
            // overlay state. Otherwise navigate fresh with
            // initialView=true so ReportsScreenNav seeds the View
            // tile-grid overlay on first composition.
            if (!navController.popBackStack(NavRoutes.AI_REPORTS, false)) {
                navController.navigate(NavRoutes.aiReportView())
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalNavigateToCurrentReport provides navToActiveView
    ) {
        content()
    }
}
