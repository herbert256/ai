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
import com.ai.ui.report.start.NewAiReportScreen
import com.ai.ui.report.start.NewReportScreen
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

/**
 * Identifies which Manage screen a report was picked from via its 🗂️
 * icon, so the filtered [ReportPickerScreen] can (a) title itself and
 * filter the list to reports relevant to that screen, and (b) return
 * to that same screen for the chosen report. The [arg] is the route
 * token carried by [NavRoutes.aiManagePickReport] and (for the overlay
 * kinds) by [NavRoutes.aiReportManage]'s `initialManageOverlay`.
 */
enum class ManagePickKind(
    val arg: String,
    val screenTitle: String,
    val subject: String
) {
    MANAGE("manage", "Pick a report", "Switch to another report"),
    FAN_OUT("fan_out", "Pick a report with a fan-out", "Only reports that have a fan-out"),
    META("meta", "Pick a report with meta results", "Only reports with meta results"),
    EDIT_PROMPT("edit_prompt", "Pick a report to edit", "Switch to another report's prompt"),
    EDIT_TITLE("edit_title", "Pick a report to edit", "Switch to another report's title"),
    COSTS("costs", "Pick a report", "Open the report costs");

    companion object {
        fun fromArg(s: String?): ManagePickKind? = entries.firstOrNull { it.arg == s }
    }
}

/** Per-kind picker filter. Null = every report qualifies (no secondary
 *  load needed). FAN_OUT / META load the report's secondaries. */
fun ManagePickKind.reportFilter(): ((Report, List<SecondaryResult>) -> Boolean)? = when (this) {
    ManagePickKind.FAN_OUT -> { _, secs -> secs.any { it.fanOutSourceAgentId != null && it.fanInOf == null } }
    ManagePickKind.META -> { _, secs -> secs.any { it.kind == SecondaryKind.META } }
    else -> null
}

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
            val uiState by appViewModel.uiState.collectAsState()
            if (uiState.generalSettings.appHomeMode == AppHomeMode.HOME_BAR) {
                LaunchedEffect(hubContext) {
                    val latestId = withContext(Dispatchers.IO) {
                        ReportStorage.getAllReports(hubContext).firstOrNull()?.id
                    }
                    if (latestId == null) {
                        // No reports: an already-configured user (has API keys)
                        // goes to the Reports hub; only an unconfigured one to
                        // First launch. Disk read so it's correct regardless of
                        // bootstrap timing.
                        val hasKeys = withContext(Dispatchers.IO) {
                            appViewModel.settingsPrefs.loadSettings().hasAnyApiKey()
                        }
                        navController.navigate(
                            if (hasKeys) NavRoutes.AI_REPORTS_HUB else NavRoutes.AI_FIRST_LAUNCH
                        ) {
                            popUpTo(NavRoutes.AI) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        com.ai.data.LastReportTracker.record(latestId, view = false)
                        reportViewModel.restoreCompletedReport(hubContext, latestId)
                        navController.navigate(NavRoutes.aiReportManage()) {
                            popUpTo(NavRoutes.AI) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                return@composable
            }
            HubScreen(
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onNavigateToMonitor = { navController.navigate(NavRoutes.AI_MONITOR) },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
                onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) },
                onNavigateToReportsHub = { navController.navigate(NavRoutes.AI_REPORTS_HUB) },
                onNavigateToChatsHub = { navController.navigate(NavRoutes.AI_CHATS_HUB) },
                onNavigateToAiSetup = { navController.navigate(NavRoutes.AI_SETUP) },
                onNavigateToHousekeeping = { navController.navigate(NavRoutes.AI_HOUSEKEEPING) },
                onNavigateToKnowledge = { navController.navigate(NavRoutes.AI_KNOWLEDGE) },
                onNavigateToExamples = { navController.navigate(NavRoutes.AI_EXAMPLES) },
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

        composable(NavRoutes.AI_FIRST_LAUNCH) {
            // Never sit on First launch once any provider has an API key — e.g.
            // the user imported keys from here and pressed back. Re-checked on
            // each ON_RESUME so it fires when First launch returns to the
            // foreground (not while the user is still on the import screen),
            // then routes to the Reports hub and drops First launch off the stack.
            val firstLaunchOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            val firstLaunchScope = rememberCoroutineScope()
            androidx.compose.runtime.DisposableEffect(firstLaunchOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        firstLaunchScope.launch {
                            val hasKeys = appViewModel.uiState.value.aiSettings.hasAnyApiKey() ||
                                withContext(Dispatchers.IO) { appViewModel.settingsPrefs.loadSettings().hasAnyApiKey() }
                            if (hasKeys) {
                                navController.navigate(NavRoutes.AI_REPORTS_HUB) {
                                    popUpTo(NavRoutes.AI_FIRST_LAUNCH) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                }
                firstLaunchOwner.lifecycle.addObserver(observer)
                onDispose { firstLaunchOwner.lifecycle.removeObserver(observer) }
            }
            // "Import API keys" opens the file picker DIRECTLY (no import/export
            // screen). On a file with ≥1 provider key: apply, force Refresh all,
            // and engage the restart lock (only Restart works until the app
            // restarts). Info-provider-only / empty files just toast.
            val firstLaunchContext = androidx.compose.ui.platform.LocalContext.current
            val importKeysLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                firstLaunchScope.launch {
                    val json = withContext(Dispatchers.IO) {
                        runCatching { firstLaunchContext.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
                    }
                    if (json.isNullOrBlank()) {
                        android.widget.Toast.makeText(firstLaunchContext, "File is empty or unreadable", android.widget.Toast.LENGTH_SHORT).show(); return@launch
                    }
                    val current = appViewModel.uiState.value
                    val result = com.ai.ui.settings.applyApiKeysJson(json, current.aiSettings)
                    if (result == null) {
                        android.widget.Toast.makeText(firstLaunchContext, "Expected a JSON object like {\"OpenAI\": \"sk-...\"}", android.widget.Toast.LENGTH_LONG).show(); return@launch
                    }
                    val updatedGs = current.generalSettings.copy(
                        huggingFaceApiKey = result.huggingFaceApiKey ?: current.generalSettings.huggingFaceApiKey,
                        openRouterApiKey = result.openRouterApiKey ?: current.generalSettings.openRouterApiKey,
                        artificialAnalysisApiKey = result.artificialAnalysisApiKey ?: current.generalSettings.artificialAnalysisApiKey
                    )
                    if (updatedGs != current.generalSettings) appViewModel.updateGeneralSettings(updatedGs)
                    appViewModel.updateSettings(result.settings)
                    if (result.settings.hasAnyApiKey()) {
                        // ≥1 provider key → forced Refresh all on the EXISTING
                        // Refresh-all screen; the restart lock takes over once it
                        // finishes (only Restart works then).
                        appViewModel.startRefreshAll()
                        appViewModel.engageRestartLock()
                        navController.navigate(NavRoutes.AI_REFRESH)
                    } else {
                        val msg = if (result.imported > 0) "${result.imported} key(s) imported, but no AI-provider key found" else "No usable keys found in the file"
                        android.widget.Toast.makeText(firstLaunchContext, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            FirstLaunchScreen(
                onImportApiKeys = { importKeysLauncher.launch(arrayOf("application/json", "text/*")) },
                onAiSetup = { navController.navigate(NavRoutes.AI_SETUP) },
                onExampleReports = { navController.navigate(NavRoutes.AI_EXAMPLES) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_HOUSEKEEPING) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onMainHelp = { navController.navigate(NavRoutes.HELP) },
                onAbout = { navController.navigate(NavRoutes.ABOUT) }
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
                reportViewModel = reportViewModel,
                onHousekeeping = { navController.navigate(NavRoutes.AI_TRIM_BY_AGE) }
            )
        }
        composable(NavRoutes.AI_EXAMPLES) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            com.ai.ui.hub.AllAiExamplesScreen(
                onNavigateBack = safePopBack,
                onOpenReportManage = { rid ->
                    com.ai.data.LastReportTracker.record(rid, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, rid)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onOpenReportView = { rid ->
                    com.ai.data.LastReportTracker.record(rid, view = true)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, rid)
                        navController.navigate(NavRoutes.aiReportView())
                    }
                }
            )
        }
        composable(NavRoutes.AI_NEW_REPORT_HUB) {
            val uiState by appViewModel.uiState.collectAsState()
            NewAiReportScreen(
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
                    onNavigateHome = navigateHome,
                    reportViewModel = reportViewModel
                )
            }
        }
        composable(NavRoutes.AI_REPORT_MANAGE) {
            val context = LocalContext.current
            com.ai.ui.report.other.ReportManageScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onDeleteReport = { rid -> reportViewModel.deleteReport(context, rid) },
                onDeleteReports = { ids, progress, complete ->
                    reportViewModel.bulkDeleteReports(context, ids, progress, complete)
                }
            )
        }
        composable(NavRoutes.AI_NEW_REPORT) {
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.aiReports()) },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) })
        }
        composable(NavRoutes.AI_NEW_REPORT_WITH_PARAMS) { entry ->
            val title = try { java.net.URLDecoder.decode(entry.arguments?.getString("title") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val prompt = try { java.net.URLDecoder.decode(entry.arguments?.getString("prompt") ?: "", "UTF-8") } catch (_: Exception) { "" }
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.aiReports()) },
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
                },
                navArgument("initialManageOverlay") {
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
            // Seed token for a Manage sub-overlay, set when a report was
            // picked from a Manage screen's 🗂️ (e.g. "meta", "edit_prompt").
            // [SeedInitialManageOverlay] consumes it once on first composition.
            val initialManageOverlay = entry.arguments?.getString("initialManageOverlay")
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
                com.ai.ui.shared.LocalGenerateNoteTitle provides { rid, nid, txt ->
                    reportViewModel.generateUserNoteTitle(context, rid, nid, txt)
                },
                com.ai.ui.shared.LocalContinueMetaInChat provides { rid, resid, lang ->
                    scope.launch {
                        val sid = continueMetaInChat(context, rid, resid, lang) ?: return@launch
                        navController.navigate(NavRoutes.aiChatContinue(sid))
                    }
                },
                com.ai.ui.shared.LocalActiveTranslationReportIds provides activeTranslationReportIds,
                com.ai.ui.shared.LocalNavigateToReportInfo provides { rid ->
                    navController.navigate(NavRoutes.aiReportInfo(rid))
                },
                com.ai.ui.shared.LocalNavigateToReportModel provides { rid, aid ->
                    navController.navigate(NavRoutes.aiReportModel(rid, aid))
                },
                com.ai.ui.shared.LocalNavigateToReportPicker provides {
                    navController.navigate(NavRoutes.AI_VIEW_PICK_REPORT)
                },
                com.ai.ui.shared.LocalNavigateToManagePicker provides { kind ->
                    navController.navigate(NavRoutes.aiManagePickReport(kind))
                },
                // Top-right AI logo on Manage / View screens → Reports hub
                // (not all the way Home).
                com.ai.ui.shared.LocalReportHubNav provides {
                    navController.navigate(NavRoutes.AI_REPORTS_HUB) {
                        popUpTo(NavRoutes.AI_REPORTS_HUB) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                // Get-info detail screens: edit the generating prompt + re-run
                // one metadata item (the 🔄 reload).
                com.ai.ui.shared.LocalEditInternalPrompt provides { promptId ->
                    navController.navigate(NavRoutes.settingsInternalPromptEdit(promptId))
                },
                com.ai.ui.shared.LocalRegenerateMetaItem provides { rid, kind, agentId ->
                    reportViewModel.regenerateMetaItem(context, rid, kind, agentId)
                },
                com.ai.ui.shared.LocalApplyAltReportTitle provides { rid, long, title, model ->
                    reportViewModel.applyAlternativeReportTitle(context, rid, long, title, model)
                },
                com.ai.ui.shared.LocalApplyAltModelTitle provides { rid, agentId, title, model ->
                    reportViewModel.applyAlternativeModelTitle(context, rid, agentId, title, model)
                }
            ) {
            ReportsScreenNav(viewModel = appViewModel, reportViewModel = reportViewModel,
                initialView = initialView,
                initialReportsAgentId = initialReportsAgentId,
                initialManageOverlay = initialManageOverlay,
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
            com.ai.ui.other.ModelTestScreen(
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
        // Standalone "Report information" screen — a real route (not the
        // Manage/View overlay pattern), opened from the Manage hub ℹ️.
        composable(NavRoutes.AI_REPORT_INFO) { entry ->
            val rid = entry.arguments?.getString("reportId") ?: ""
            com.ai.ui.navigation.ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = rid
            ) {
                com.ai.ui.report.info.ReportInfoScreen(
                    reportId = rid,
                    onBack = safePopBack,
                    onOpenTrace = { fn -> navController.navigate(NavRoutes.traceDetail(fn)) },
                    onOpenModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p, m)) }
                )
            }
        }
        // View-styled "pick a report to view" screen (from the View hub 📋).
        composable(NavRoutes.AI_VIEW_PICK_REPORT) {
            val pickContext = LocalContext.current
            val pickScope = rememberCoroutineScope()
            com.ai.ui.navigation.ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = null
            ) {
                com.ai.ui.report.view.ReportPickerScreen(
                    reportViewModel = reportViewModel,
                    onBack = safePopBack,
                    currentReportId = appViewModel.uiState.value.currentReportId,
                    onOpenReportView = { rid ->
                        pickScope.launch {
                            reportViewModel.restoreCompletedReport(pickContext, rid)
                            navController.navigate(NavRoutes.aiReportView())
                        }
                    }
                )
            }
        }
        // Filtered "pick a report" screen opened from a Manage screen's 🗂️.
        // Same picker as the View hub's, but filtered to reports relevant to
        // the source screen and returning to that same screen on pick.
        composable(
            NavRoutes.AI_MANAGE_PICK_REPORT,
            arguments = listOf(navArgument("kind") { type = NavType.StringType })
        ) { entry ->
            val kind = ManagePickKind.fromArg(entry.arguments?.getString("kind"))
                ?: ManagePickKind.MANAGE
            val pickContext = LocalContext.current
            val pickScope = rememberCoroutineScope()
            com.ai.ui.navigation.ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = null
            ) {
                com.ai.ui.report.view.ReportPickerScreen(
                    reportViewModel = reportViewModel,
                    onBack = safePopBack,
                    screenTitle = kind.screenTitle,
                    subject = kind.subject,
                    filter = kind.reportFilter(),
                    currentReportId = appViewModel.uiState.value.currentReportId,
                    onOpenReportView = { rid ->
                        pickScope.launch {
                            reportViewModel.restoreCompletedReport(pickContext, rid)
                            // Return to the SAME screen the 🗂️ was tapped on:
                            // MANAGE → the hub; FAN_OUT → the View tile grid
                            // (where the report's fan-out(s) are chosen — there's
                            // no view-only fan-out picker, and the run-a-fan-out
                            // picker would risk an accidental re-run); the rest
                            // seed their Manage overlay on entry.
                            val dest = when (kind) {
                                ManagePickKind.MANAGE -> NavRoutes.aiReportManage()
                                ManagePickKind.FAN_OUT -> NavRoutes.aiReportView()
                                else -> NavRoutes.aiReportManage(kind.arg)
                            }
                            navController.navigate(dest)
                        }
                    }
                )
            }
        }
        // Standalone per-model "Report model" screen — replaces the old
        // single-result overlay (real route, opened from a Manage 'report' row).
        composable(NavRoutes.AI_REPORT_MODEL) { entry ->
            val rid = entry.arguments?.getString("reportId") ?: ""
            // Encoded in NavRoutes.aiReportModel (swarm/fan-out ids carry '/').
            val aid = try { java.net.URLDecoder.decode(entry.arguments?.getString("agentId") ?: "", "UTF-8") } catch (_: Exception) { entry.arguments?.getString("agentId") ?: "" }
            val rmContext = LocalContext.current
            val rmScope = rememberCoroutineScope()
            val temperatureSweepStates by reportViewModel.temperatureSweepStates.collectAsState()
            val reasoningEffortSweepStates by reportViewModel.reasoningEffortSweepStates.collectAsState()
            val webSearchReplayStates by reportViewModel.webSearchReplayStates.collectAsState()
            val promptEditReplayStates by reportViewModel.promptEditReplayStates.collectAsState()
            com.ai.ui.navigation.ViewSubScreenWithTitleNav(
                navController = navController,
                currentReportId = rid
            ) {
                // Local Icon-lookup / Edit-title overlays so this standalone
                // route's model icon + title taps reach the same screens the
                // manage overlay stack offers. Find-alternatives is hidden
                // (its picker lives only in the manage overlay stack).
                val rmAiSettings = com.ai.ui.shared.LocalAiSettings.current
                val rmDataVersion by com.ai.data.ReportDataVersion.versionFor(rid).collectAsState()
                val rmReport by produceState<com.ai.data.Report?>(null, rid, rmDataVersion) {
                    value = withContext(Dispatchers.IO) {
                        com.ai.ui.report.view.helpers.ViewReportCache.get(rmContext, rid)
                    }
                }
                val agentIconFanOut by appViewModel.agentIconFanOutByAgent.collectAsState()
                var iconDetailFor by remember { mutableStateOf<String?>(null) }
                var editTitleFor by remember { mutableStateOf<String?>(null) }
                val rmAgents = rmReport?.agents
                val etf = editTitleFor
                val idf = iconDetailFor
                val editAgent = etf?.let { id -> rmAgents?.firstOrNull { it.agentId == id } }
                val iconAgent = idf?.let { id -> rmAgents?.firstOrNull { it.agentId == id } }
                if (etf != null && editAgent != null) {
                    com.ai.ui.report.manage.ReportEditModelTitleScreen(
                        reportId = rid,
                        agentId = etf,
                        modelName = "${editAgent.provider} · ${shortModelName(editAgent.model)}",
                        initialTitle = editAgent.modelTitle.orEmpty(),
                        aiSettings = rmAiSettings,
                        traceFilename = editAgent.modelTitleTraceFile,
                        onNavigateToTraceFile = { fn -> navController.navigate(NavRoutes.traceDetail(fn)) },
                        onBack = { editTitleFor = null },
                        showFindAlternatives = false,
                        onUpdate = { newTitle ->
                            editTitleFor = null
                            rmScope.launch { reportViewModel.updateModelTitle(rmContext, rid, etf, newTitle) }
                        }
                    )
                } else if (idf != null && iconAgent != null && rmReport != null) {
                    com.ai.ui.report.manage.AgentIconDetailOverlay(
                        agentId = idf,
                        aiSettings = rmAiSettings,
                        currentReportId = rid,
                        loadedReportPrompt = rmReport!!.prompt,
                        effectiveReportIcon = rmReport!!.icon,
                        loadedReportTitle = rmReport!!.title,
                        agentRecordsByAgentId = rmAgents.orEmpty().associateBy { it.agentId },
                        agentIconFanOutByAgent = agentIconFanOut,
                        onNavigateToTraceFile = { fn -> navController.navigate(NavRoutes.traceDetail(fn)) },
                        onFindAlternativeIcons = { },
                        onApplyIcon = { emoji -> reportViewModel.iconGen.pickAgentIcon(rmContext, rid, idf, emoji) },
                        onClose = { iconDetailFor = null },
                        showFindAlternatives = false
                    )
                } else {
                com.ai.ui.report.manage.view.ReportModelScreen(
                    reportId = rid,
                    agentId = aid,
                    onOpenAgentIcon = { iconDetailFor = it },
                    onEditModelTitle = { editTitleFor = it },
                    onBack = safePopBack,
                    onNavigateHome = navigateHome,
                    onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                    onNavigateToTraceFile = { fn -> navController.navigate(NavRoutes.traceDetail(fn)) },
                    onNavigateToViewReports = { aid -> navController.navigate(NavRoutes.aiReportViewAtAgent(aid)) },
                    onRemoveAgent = { r, a -> reportViewModel.removeAgentFromReport(rmContext, r, a) },
                    onRegenerateAgent = { r, a -> reportViewModel.regenerateAgent(rmContext, r, a) },
                    temperatureSweepStates = temperatureSweepStates,
                    onStartTemperatureSweep = { r, a, temps ->
                        reportViewModel.startTemperatureSweep(rmContext, r, a, temps)
                    },
                    onApplyTemperatureCandidate = { r, a, index ->
                        reportViewModel.applyTemperatureCandidate(rmContext, r, a, index)
                    },
                    onClearTemperatureSweep = { r, a ->
                        reportViewModel.clearTemperatureSweep(r, a)
                    },
                    reasoningEffortSweepStates = reasoningEffortSweepStates,
                    onStartReasoningEffortSweep = { r, a, efforts ->
                        reportViewModel.startReasoningEffortSweep(rmContext, r, a, efforts)
                    },
                    onApplyReasoningEffortCandidate = { r, a, index ->
                        reportViewModel.applyReasoningEffortCandidate(rmContext, r, a, index)
                    },
                    onClearReasoningEffortSweep = { r, a ->
                        reportViewModel.clearReasoningEffortSweep(r, a)
                    },
                    webSearchReplayStates = webSearchReplayStates,
                    onStartWebSearchReplay = { r, a ->
                        reportViewModel.startWebSearchReplay(rmContext, r, a)
                    },
                    onApplyWebSearchReplay = { r, a ->
                        reportViewModel.applyWebSearchReplay(rmContext, r, a)
                    },
                    onClearWebSearchReplay = { r, a ->
                        reportViewModel.clearWebSearchReplay(r, a)
                    },
                    promptEditReplayStates = promptEditReplayStates,
                    onStartPromptEditReplay = { r, a, prompt, paramsIds, systemPromptId ->
                        reportViewModel.startPromptEditReplay(rmContext, r, a, prompt, paramsIds, systemPromptId)
                    },
                    onApplyPromptEditReplay = { r, a ->
                        reportViewModel.applyPromptEditReplay(rmContext, r, a)
                    },
                    onClearPromptEditReplay = { r, a ->
                        reportViewModel.clearPromptEditReplay(r, a)
                    },
                    onContinueWithCurrent = { r, a ->
                        rmScope.launch {
                            val sessionId = continueReportInChat(rmContext, r, a,
                                aiSettings = appViewModel.uiState.value.aiSettings) ?: return@launch
                            navController.navigate(NavRoutes.aiChatContinue(sessionId))
                        }
                    },
                    onContinueWithAgentPicker = { r, a ->
                        rmScope.launch {
                            val response = readReportAgentResponse(rmContext, r, a) ?: return@launch
                            appViewModel.updateUiState { it.copy(chatStarterText = response, chatStarterImageBase64 = null, chatStarterImageMime = null) }
                            navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT)
                        }
                    },
                    onContinueWithOnTheFly = { r, a ->
                        rmScope.launch {
                            val response = readReportAgentResponse(rmContext, r, a) ?: return@launch
                            appViewModel.updateUiState { it.copy(chatStarterText = response, chatStarterImageBase64 = null, chatStarterImageMime = null) }
                            navController.navigate(NavRoutes.AI_CHAT_PROVIDER)
                        }
                    }
                )
                }
            }
        }
}
