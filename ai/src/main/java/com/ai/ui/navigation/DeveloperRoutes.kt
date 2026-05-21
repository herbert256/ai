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

internal fun NavGraphBuilder.developerRoutes(
    navController: NavHostController,
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    chatViewModel: ChatViewModel,
    safePopBack: () -> Unit,
    navigateHome: () -> Unit
) {
        composable(NavRoutes.AI_USAGE) {
            val uiState by appViewModel.uiState.collectAsState()
            UsageScreen(
                openRouterApiKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.entries.firstOrNull { it.crossProviderModelList }?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                },
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_COSTS_MAINTENANCE) })
        }
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
                onNavigateToUpdateFromCloud = { navController.navigate(NavRoutes.AI_UPDATE_FROM_CLOUD) },
                onNavigateToCosts = { navController.navigate(NavRoutes.AI_COSTS_MAINTENANCE) }
            )
        }
        composable(NavRoutes.AI_TEST) {
            com.ai.ui.admin.TestScreen(
                onBack = safePopBack,
                onOpenTestAllModels = { navController.navigate(NavRoutes.AI_TEST_ALL_MODELS) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_TEST_EXCLUDED_MODELS) }
            )
        }
        composable(NavRoutes.AI_APPLOG_LIST) {
            com.ai.ui.admin.AppLogListScreen(
                onBack = safePopBack,
                onSelectLog = { name -> navController.navigate(NavRoutes.aiAppLogDetail(name)) }
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
                onClearTraces = { appViewModel.clearTraces() },
                onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) })
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
                    onClearTraces = { appViewModel.clearTraces() }, reportId = reportId,
                    onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) })
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
                    reportId = reportId, initialCategory = category,
                    onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) })
            }
        }
        composable(NavRoutes.TRACE_LIST_FOR_MODEL) { entry ->
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, modelFilter = model,
                onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) })
        }
        composable(NavRoutes.TRACE_LIST_FOR_RUN) { entry ->
            val runId = try { java.net.URLDecoder.decode(entry.arguments?.getString("runId") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, runIdFilter = runId,
                onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) })
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
        composable(NavRoutes.AI_API_TEST) {
            ApiTestScreen(onBackClick = safePopBack, onNavigateHome = navigateHome,
                onNavigateToEditRequest = { navController.navigate(NavRoutes.AI_API_TEST_EDIT) }, viewModel = appViewModel)
        }
        composable(NavRoutes.AI_API_TEST_EDIT) {
            EditApiRequestScreen(onBackClick = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTraceDetail = { navController.navigate(NavRoutes.traceDetail(it)) })
        }
}
