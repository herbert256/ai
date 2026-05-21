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
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*
import com.ai.ui.history.*
import com.ai.ui.models.*
import com.ai.ui.search.*
import com.ai.ui.settings.*
import com.ai.ui.admin.*
import com.ai.ui.shared.*

/**
 * Main navigation host for the app.
 */
import androidx.navigation.NavGraphBuilder

internal fun NavGraphBuilder.reportRoutes(
    navController: NavHostController,
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    chatViewModel: ChatViewModel,
    safePopBack: () -> Unit,
    navigateHome: () -> Unit
) {
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
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.hub.SearchAiReportsScreen(
                onNavigateBack = safePopBack,
                onNavigateHome = navigateHome,
                onNavigateToQuickLocalSearch = { navController.navigate(NavRoutes.AI_QUICK_LOCAL_SEARCH) },
                onNavigateToLocalSearch = { navController.navigate(NavRoutes.AI_LOCAL_SEARCH) },
                onNavigateToSearch = { navController.navigate(NavRoutes.AI_SEARCH) },
                onNavigateToLocalSemanticSearch = { navController.navigate(NavRoutes.AI_LOCAL_SEMANTIC_SEARCH) },
                experimentalFeatures = uiState.generalSettings.experimentalFeaturesEnabled
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
        composable(NavRoutes.AI_REPORT_MANAGE) {
            com.ai.ui.report.manage.ReportManageScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome
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
                },
                navArgument("initialReportsAgentId") {
                    type = NavType.StringType; defaultValue = ""; nullable = true
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
            // Additional seed for the View tile grid's Reports
            // sub-overlay, used by Model Info View's Last-Usage rows
            // ([NavRoutes.aiReportViewAtAgent]). Non-blank → seeds
            // ViewAiReportScreen's reportsViewInitialAgentId and
            // flips reportsViewOpen on first composition.
            val initialReportsAgentId = entry.arguments?.getString("initialReportsAgentId")
                ?.takeIf { it.isNotBlank() }
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
            val translationRunsForLocal by reportViewModel.translation.translationRuns.collectAsState()
            val activeTranslationReportIds = remember(translationRunsForLocal) {
                translationRunsForLocal.values
                    .filter { !it.isFinished && !it.cancelled }
                    .map { it.sourceReportId }
                    .toSet()
            }
            CompositionLocalProvider(
                com.ai.ui.shared.LocalSystemPromptChange provides { id ->
                    appViewModel.setReportSystemPromptId(id)
                },
                com.ai.ui.shared.LocalActiveTranslationReportIds provides activeTranslationReportIds
            ) {
            ReportsScreenNav(viewModel = appViewModel, reportViewModel = reportViewModel,
                initialView = initialView,
                initialReportsAgentId = initialReportsAgentId,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                // After a delete-report the user always lands on
                // the AI Reports hub. popUpTo clears the deleted
                // report's Manage screen off the back stack so
                // hitting Back from the hub doesn't return to a
                // now-stale Manage view.
                onNavigateToReportsHub = {
                    navController.navigate(NavRoutes.AI_REPORTS_HUB) {
                        popUpTo(NavRoutes.AI_REPORTS_HUB) { inclusive = true }
                    }
                },
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
        composable(NavRoutes.AI_UPDATE_FROM_CLOUD) {
            com.ai.ui.admin.UpdateFromCloudScreen(onBack = safePopBack)
        }
        composable(NavRoutes.AI_TEST_ALL_MODELS) {
            com.ai.ui.report.manage.ModelTestScreen(
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
}
