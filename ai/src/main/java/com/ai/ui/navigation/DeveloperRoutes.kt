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
import androidx.navigation.NavBackStackEntry

/** Dispatch a Broken-work recovery action to the right engine. Hydrates
 *  the report's run first for the engines that act on in-memory state
 *  (Fan Out / Tournament / Judges / Compare); resume, Fan Meta and
 *  Translation are disk-based / self-hydrating. [restart] re-fires (errors)
 *  or re-dispatches (unfinished); otherwise the items are dropped. Fan Meta
 *  "delete unfinished" is a no-op — there's no item row to drop. */
private suspend fun recoverBrokenBatch(
    context: android.content.Context,
    rvm: ReportViewModel,
    batch: BrokenBatch,
    mode: BrokenItemMode,
    restart: Boolean,
    rowIds: Set<String>? = null,
) {
    val rid = batch.reportId
    val errors = mode == BrokenItemMode.ERRORS
    when (batch.kind) {
        BatchFamilyKind.FAN_OUT -> {
            rvm.fanOutEngine.hydrate(context, rid)
            if (rowIds != null) {
                if (restart) rvm.fanOutEngine.restartPairsByIds(context, batch.key, rowIds).join()
                else rvm.fanOutEngine.removePairsByIds(context, batch.key, rowIds).join()
            } else if (restart) {
                if (errors) rvm.fanOutEngine.restartFailedPairs(context, batch.key).join()
                else rvm.fanOutEngine.resumeStaleRunsForReport(context, rid).join()
            } else {
                if (errors) rvm.fanOutEngine.removeFailedPairs(context, batch.key).join()
                else rvm.fanOutEngine.removeUnfinishedPairs(context, batch.key).join()
            }
        }
        BatchFamilyKind.FAN_META -> {
            val mp = batch.key.substringAfter('|')
            if (rowIds != null) {
                if (restart) rvm.iconGen.restartFanMetaRows(context, rid, mp, rowIds).join()
                else if (errors) rvm.iconGen.clearFanMetaRows(context, rid, rowIds).join()
            } else if (restart) {
                if (errors) rvm.iconGen.restartFanMetaErrors(context, rid, mp).join()
                else rvm.iconGen.runFanMetaBatch(context, rid, mp)?.join()
            } else if (errors) {
                rvm.iconGen.clearFanMetaErrors(context, rid, mp).join()
            }
        }
        BatchFamilyKind.TOURNAMENT -> {
            rvm.tournamentEngine.hydrate(context, rid)
            if (rowIds != null) {
                if (restart) rvm.tournamentEngine.restartMatchesByIds(context, rid, rowIds).join()
                else rvm.tournamentEngine.removeMatchesByIds(context, rid, rowIds).join()
            } else if (restart) {
                if (errors) rvm.tournamentEngine.restartFailedMatches(context, rid).join()
                else rvm.tournamentEngine.resumeStaleRunsForReport(context, rid).join()
            } else {
                if (errors) rvm.tournamentEngine.removeFailedMatches(context, rid).join()
                else rvm.tournamentEngine.removeUnfinishedMatches(context, rid).join()
            }
        }
        BatchFamilyKind.JUDGES -> {
            rvm.judgeEvalEngine.hydrate(context, rid)
            if (rowIds != null) {
                if (restart) rvm.judgeEvalEngine.restartCellsByIds(context, rid, rowIds).join()
                else rvm.judgeEvalEngine.removeCellsByIds(context, rid, rowIds).join()
            } else if (restart) {
                if (errors) rvm.judgeEvalEngine.restartFailedCells(context, rid).join()
                else rvm.judgeEvalEngine.resumeStaleRunsForReport(context, rid).join()
            } else {
                if (errors) rvm.judgeEvalEngine.removeFailedCells(context, rid).join()
                else rvm.judgeEvalEngine.removeUnfinishedCells(context, rid).join()
            }
        }
        BatchFamilyKind.COMPARE -> {
            rvm.compareEngine.hydrate(context, rid)
            if (rowIds != null) {
                if (restart) rvm.compareEngine.restartCellsByIds(context, rid, rowIds).join()
                else rvm.compareEngine.removeCellsByIds(context, rid, rowIds).join()
            } else if (restart) {
                if (errors) rvm.compareEngine.restartFailedCells(context, rid).join()
                else rvm.compareEngine.resumeStaleRunsForReport(context, rid).join()
            } else {
                if (errors) rvm.compareEngine.removeFailedCells(context, rid).join()
                else rvm.compareEngine.removeUnfinishedCells(context, rid).join()
            }
        }
        BatchFamilyKind.TRANSRANK -> {
            // batch.key is the per-language run key ("$reportId|$sourceRunId").
            rvm.translatorRankEngine.hydrate(context, rid)
            if (rowIds != null) {
                if (restart) rvm.translatorRankEngine.restartCellsByIds(context, batch.key, rowIds).join()
                else rvm.translatorRankEngine.removeCellsByIds(context, batch.key, rowIds).join()
            } else if (restart) {
                if (errors) rvm.translatorRankEngine.restartFailedCells(context, batch.key).join()
                else rvm.translatorRankEngine.resumeStaleRunsForReport(context, rid).join()
            } else {
                if (errors) rvm.translatorRankEngine.removeFailedCells(context, batch.key).join()
                else rvm.translatorRankEngine.removeUnfinishedCells(context, batch.key).join()
            }
        }
        BatchFamilyKind.TRANSLATION -> {
            if (rowIds != null) {
                if (restart) rvm.translation.restartTranslationRowsByIds(context, rid, batch.key, rowIds).join()
                else rvm.translation.removeTranslationRowsByIds(context, rid, batch.key, rowIds).join()
            } else if (restart) {
                if (errors) rvm.translation.restartFailedTranslations(context, rid, batch.key).join()
                else rvm.translation.startMissingTranslations(context, rid, batch.key).join()
            } else {
                if (errors) rvm.translation.removeFailedTranslations(context, rid, batch.key).join()
                else rvm.translation.removeUnfinishedTranslations(context, rid, batch.key).join()
            }
        }
        BatchFamilyKind.REGENERATE -> {
            if (restart) rvm.regenerateBatchEngine.restart(context, rid).join()
            else rvm.regenerateBatchEngine.deleteJob(context, rid).join()
        }
        BatchFamilyKind.OTHER -> {
            // Single Meta/Rerank/Moderation calls — act per matching row.
            val targets = matchingBrokenRows(context, batch, mode)
                .filter { rowIds == null || it.id in rowIds }
            if (restart) targets.mapNotNull { rvm.secondary.resumeStaleMetaPlaceholder(context, rid, it) }
                .forEach { it.join() }
            else rvm.secondary.bulkDeleteSecondaryResults(context, rid, targets.map { it.id }).join()
        }
        BatchFamilyKind.RESPONSES -> {
            // Primary report agents — restart regenerates the agent, delete
            // drops it (no API call). ERRORS mode acts on ERROR + STOPPED agents
            // (incl. "Stopped by user"), UNFINISHED on the PENDING/RUNNING ones a
            // process kill stranded. [rowIds] null means the whole line.
            val report = ReportStorage.getReport(context, rid) ?: return
            val modeAgentIds = report.agents.filter {
                if (errors) it.reportStatus == ReportStatus.ERROR || it.reportStatus == ReportStatus.STOPPED
                else it.reportStatus == ReportStatus.PENDING || it.reportStatus == ReportStatus.RUNNING
            }.map { it.agentId }
            val targets = if (rowIds != null) modeAgentIds.filter { it in rowIds } else modeAgentIds
            targets.map { agentId ->
                if (restart) rvm.regenerateAgent(context, rid, agentId)
                else rvm.removeAgentFromReport(context, rid, agentId)
            }.forEach { it.join() }
        }
    }
}

/** Registers a Monitor-subtree screen. Identical to [composable] but wraps
 *  the destination in [LocalMonitorNav], so the screen's [TitleBar] renders
 *  the four 📡 🐞 📜 📊 "jump to a Monitor part" icons at the start of its
 *  bottom icon row. Screens registered with plain [composable] are
 *  unaffected. */
private fun NavGraphBuilder.monitorComposable(
    route: String,
    monitorNav: MonitorNav,
    active: MonitorPart? = null,
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route) { entry ->
    CompositionLocalProvider(LocalMonitorNav provides monitorNav.copy(active = active)) { content(entry) }
}

internal fun NavGraphBuilder.developerRoutes(
    navController: NavHostController,
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    chatViewModel: ChatViewModel,
    safePopBack: () -> Unit,
    navigateHome: () -> Unit
) {
        // The 📈 icon + title on the aggregate sub-screens jump back to the
        // Statistics hub (popping any existing instance, not stacking).
        val toStatistics: () -> Unit = {
            navController.navigate(NavRoutes.AI_STATISTICS) {
                popUpTo(NavRoutes.AI_STATISTICS) { inclusive = true }
                launchSingleTop = true
            }
        }
        // The four "jump to a Monitor part" actions, provided around every
        // screen in the Monitor subtree (via [monitorComposable]) so each
        // one's TitleBar carries 📡 🐞 📜 📊 at the start of its icon row.
        // Each jump pops back to the Monitor hub first, so hopping between
        // sections keeps the back stack flat (Monitor → section) instead of
        // piling siblings up. popUpTo a route that isn't on the stack (the
        // screen was reached from outside Monitor) is a no-op, so the jump
        // still works there.
        val jumpToMonitorPart: (String) -> Unit = { route ->
            navController.navigate(route) {
                popUpTo(NavRoutes.AI_MONITOR) { inclusive = false }
                launchSingleTop = true
            }
        }
        val monitorNav = MonitorNav(
            onLiveDashboard = { jumpToMonitorPart(NavRoutes.AI_LIVE_DASHBOARD) },
            onTraces = { jumpToMonitorPart(NavRoutes.TRACE_LIST) },
            onAppLog = { jumpToMonitorPart(NavRoutes.AI_APPLOG_LIST) },
            onAudit = { jumpToMonitorPart(NavRoutes.AI_AUDIT_LIST) },
            onStatistics = { jumpToMonitorPart(NavRoutes.AI_STATISTICS) },
        )
        // Open the API-trace list pre-filtered to one dimension value.
        // Shared by the trace-stats Status card and the breakdown screens.
        val openTraceFilter: (String, String) -> Unit = { field, value ->
            navController.navigate(NavRoutes.traceListFiltered(
                host = value.takeIf { field == "host" },
                status = value.takeIf { field == "status" },
                category = value.takeIf { field == "category" },
                model = value.takeIf { field == "model" },
            ))
        }
        composable(NavRoutes.AI_MONITOR) {
            AiMonitorScreen(
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToLiveDashboard = { navController.navigate(NavRoutes.AI_LIVE_DASHBOARD) },
                onNavigateToTraces = { navController.navigate(NavRoutes.TRACE_LIST) },
                onNavigateToAppLog = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onNavigateToAudit = { navController.navigate(NavRoutes.AI_AUDIT_LIST) },
                onNavigateToStatistics = { navController.navigate(NavRoutes.AI_STATISTICS) },
                onNavigateToCrashReports = { navController.navigate(NavRoutes.AI_CRASH_REPORTS) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_COSTS_MAINTENANCE) })
        }
        monitorComposable(NavRoutes.AI_CRASH_REPORTS, monitorNav) {
            AiCrashReportsScreen(
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToMonitor = { navController.navigate(NavRoutes.AI_MONITOR) })
        }
        monitorComposable(NavRoutes.AI_STATISTICS, monitorNav, MonitorPart.STATISTICS) {
            AiStatisticsScreen(
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.AI_STAT_REPORTS) },
                onNavigateToProviders = { navController.navigate(NavRoutes.AI_STAT_PROVIDERS) },
                onNavigateToModels = { navController.navigate(NavRoutes.AI_STAT_MODELS) },
                onNavigateToSpendUsage = { navController.navigate(NavRoutes.AI_SPEND_USAGE) },
                onNavigateToCostsTier = { navController.navigate(NavRoutes.AI_COSTS_TIER) },
                onNavigateToTraceStats = { navController.navigate(NavRoutes.AI_TRACE_STATS) },
                onNavigateToLogStats = { navController.navigate(NavRoutes.AI_LOG_STATS) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_COSTS_MAINTENANCE) })
        }
        monitorComposable(NavRoutes.AI_LIVE_DASHBOARD, monitorNav, MonitorPart.LIVE_DASHBOARD) {
            AiLiveDashboardScreen(
                appViewModel = appViewModel,
                reportViewModel = reportViewModel,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onOpenTraceFilter = openTraceFilter)
        }
        monitorComposable(NavRoutes.AI_TRACE_STATS, monitorNav) {
            AiTraceStatsScreen(onBack = safePopBack, onNavigateHome = navigateHome, onNavigateToStatistics = toStatistics,
                onOpenTraceFilter = openTraceFilter,
                onOpenBreakdown = { navController.navigate(NavRoutes.aiTraceBreakdown(it)) })
        }
        monitorComposable(NavRoutes.AI_TRACE_BREAKDOWN, monitorNav) { entry ->
            AiTraceBreakdownScreen(
                dim = entry.arguments?.getString("dim") ?: "host",
                onBack = safePopBack, onNavigateHome = navigateHome, onNavigateToStatistics = toStatistics,
                onOpenTraceFilter = openTraceFilter)
        }
        monitorComposable(NavRoutes.AI_LOG_STATS, monitorNav) {
            AiLogStatsScreen(onBack = safePopBack, onNavigateHome = navigateHome, onNavigateToStatistics = toStatistics)
        }
        monitorComposable(NavRoutes.AI_STAT_REPORTS, monitorNav) {
            AiStatReportsScreen(
                reportViewModel = reportViewModel,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToStatistics = toStatistics)
        }
        monitorComposable(NavRoutes.AI_STAT_PROVIDERS, monitorNav) {
            AiStatProvidersScreen(
                appViewModel = appViewModel,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToStatistics = toStatistics)
        }
        monitorComposable(NavRoutes.AI_STAT_MODELS, monitorNav) {
            AiStatModelsScreen(
                appViewModel = appViewModel,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToStatistics = toStatistics)
        }
        monitorComposable(NavRoutes.AI_SPEND_USAGE, monitorNav) {
            val uiState by appViewModel.uiState.collectAsState()
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            AiSpendUsageScreen(
                openRouterApiKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.entries.firstOrNull { it.crossProviderModelList }?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                },
                onBack = safePopBack, onNavigateHome = navigateHome,
                onOpenProvider = { pid -> navController.navigate(NavRoutes.aiUsageProvider(pid)) },
                onOpenType = { prefix -> navController.navigate(NavRoutes.aiUsageTypeGroup(prefix)) },
                onNavigateToStatistics = toStatistics,
                onNavigateToTraceProvider = { pid -> navController.navigate(NavRoutes.traceListFiltered(provider = pid)) },
                onNavigateToTraceCategory = { category -> navController.navigate(NavRoutes.traceListFiltered(category = category)) },
                onOpenReportCosts = { reportId ->
                    LastReportTracker.record(reportId, view = false)
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.aiReportManage(ManagePickKind.COSTS.arg))
                    }
                },
                onNavigateToTraceReport = { reportId -> navController.navigate(NavRoutes.traceListForReport(reportId)) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_COSTS_MAINTENANCE) })
        }
        monitorComposable(NavRoutes.AI_USAGE_PROVIDER, monitorNav) { entry ->
            val pid = try { java.net.URLDecoder.decode(entry.arguments?.getString("providerId") ?: "", "UTF-8") } catch (_: Exception) { "" }
            AiSpendUsageProviderScreen(
                providerId = pid,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToStatistics = toStatistics)
        }
        monitorComposable(NavRoutes.AI_USAGE_TYPE_GROUP, monitorNav) { entry ->
            val prefix = try { java.net.URLDecoder.decode(entry.arguments?.getString("groupPrefix") ?: "", "UTF-8") } catch (_: Exception) { "" }
            AiSpendUsageTypeGroupScreen(
                groupPrefix = prefix,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onOpenType = { category -> navController.navigate(NavRoutes.aiUsageType(category)) },
                onNavigateToTraceCategory = { category -> navController.navigate(NavRoutes.traceListFiltered(category = category)) },
                onNavigateToStatistics = toStatistics)
        }
        monitorComposable(NavRoutes.AI_USAGE_TYPE, monitorNav) { entry ->
            val prefix = try { java.net.URLDecoder.decode(entry.arguments?.getString("typePrefix") ?: "", "UTF-8") } catch (_: Exception) { "" }
            AiSpendUsageTypeScreen(
                typePrefix = prefix,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToStatistics = toStatistics)
        }
        monitorComposable(NavRoutes.AI_COSTS_TIER, monitorNav) {
            AiCostsTierScreen(
                appViewModel = appViewModel,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToStatistics = toStatistics,
                onNavigateToTraceCategory = { navController.navigate(NavRoutes.traceListFiltered(category = it)) })
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
                onNavigateToManageData = { navController.navigate(NavRoutes.AI_MANAGE_DATA) },
                onNavigateToTrimByAge = { navController.navigate(NavRoutes.AI_TRIM_BY_AGE) },
                onNavigateToTest = { navController.navigate(NavRoutes.AI_TEST) },
                onNavigateToUpdateFromCloud = { navController.navigate(NavRoutes.AI_UPDATE_FROM_CLOUD) },
                onNavigateToCosts = { navController.navigate(NavRoutes.AI_COSTS_MAINTENANCE) },
                onNavigateToPromptTranslations = { navController.navigate(NavRoutes.AI_PROMPT_TRANSLATIONS) },
                onNavigateToCaches = { navController.navigate(NavRoutes.AI_CACHES) }
            )
        }
        composable(NavRoutes.AI_CACHES) {
            com.ai.ui.admin.CachesHubScreen(
                registry = com.ai.ui.admin.cacheRegistry(cacheRefreshDispatcher(appViewModel)),
                onOpenCache = { id -> navController.navigate(NavRoutes.aiCacheEntries(id)) },
                onBack = safePopBack
            )
        }
        composable(
            NavRoutes.AI_CACHE_ENTRIES,
            arguments = listOf(navArgument("cacheId") { type = NavType.StringType })
        ) { backStackEntry ->
            com.ai.ui.admin.CacheEntriesScreen(
                registry = com.ai.ui.admin.cacheRegistry(cacheRefreshDispatcher(appViewModel)),
                initialCacheId = backStackEntry.arguments?.getString("cacheId") ?: "prompts",
                onBack = safePopBack
            )
        }
        composable(NavRoutes.AI_PROMPT_TRANSLATIONS) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.admin.PromptTranslationsScreen(
                onBack = safePopBack, onNavigateHome = navigateHome,
                aiSettings = uiState.aiSettings,
                onAskModelText = { service, model, prompt -> appViewModel.askModelText(service, model, prompt) }
            )
        }
        composable(NavRoutes.AI_TEST) {
            com.ai.ui.admin.TestScreen(
                onBack = safePopBack,
                onOpenTestAllModels = { navController.navigate(NavRoutes.AI_TEST_ALL_MODELS) },
                onOpenStressTest = { navController.navigate(NavRoutes.AI_STRESS_TEST) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_TEST_EXCLUDED_MODELS) }
            )
        }
        composable(NavRoutes.AI_STRESS_TEST) {
            com.ai.ui.admin.StressTestScreen(
                engine = reportViewModel.stressTestEngine,
                onBack = safePopBack,
                onStarted = {
                    // Straight to the main Live Dashboard — the stress run is
                    // just normal app activity the dashboard already shows.
                    navController.navigate(NavRoutes.AI_LIVE_DASHBOARD) {
                        popUpTo(NavRoutes.AI_STRESS_TEST) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        monitorComposable(NavRoutes.AI_APPLOG_LIST, monitorNav, MonitorPart.APP_LOG) {
            com.ai.ui.admin.AppLogListScreen(
                onBack = safePopBack,
                onSelectLog = { name -> navController.navigate(NavRoutes.aiAppLogDetail(name)) },
                onStats = { navController.navigate(NavRoutes.AI_LOG_STATS) }
            )
        }
        val toMonitorHub: () -> Unit = {
            navController.navigate(NavRoutes.AI_MONITOR) {
                popUpTo(NavRoutes.AI_MONITOR) { inclusive = false }
                launchSingleTop = true
            }
        }
        monitorComposable(NavRoutes.AI_AUDIT_LIST, monitorNav, MonitorPart.AUDIT) {
            com.ai.ui.admin.AuditListScreen(
                onBack = safePopBack,
                onNavigateToMonitor = toMonitorHub,
                onSelect = { reportId -> navController.navigate(NavRoutes.aiAuditDetail(reportId)) }
            )
        }
        monitorComposable(NavRoutes.AI_AUDIT_DETAIL, monitorNav, MonitorPart.AUDIT) { entry ->
            val rid = entry.arguments?.getString("reportId") ?: ""
            com.ai.ui.admin.AuditDetailScreen(
                reportId = rid,
                onBack = safePopBack,
                onNavigateToMonitor = toMonitorHub,
                onNavigateToTrace = { tf -> navController.navigate(NavRoutes.traceDetail(tf)) }
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
            val context = LocalContext.current
            com.ai.ui.admin.TrimByAgeScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onDeleteReport = { reportId -> reportViewModel.deleteReport(context, reportId) },
                onDeleteReports = { reportIds -> reportViewModel.bulkDeleteReports(context, reportIds) }
            )
        }
        composable(NavRoutes.AI_MANAGE_DATA) {
            val uiState by appViewModel.uiState.collectAsState()
            val hasAnyKeyedProvider = com.ai.data.AppService.entries.any {
                uiState.aiSettings.getApiKey(it).isNotBlank()
            }
            com.ai.ui.admin.ManageDataScreen(
                onBack = { navController.popBackStack() },
                hasAnyKeyedProvider = hasAnyKeyedProvider,
                onRefreshAll = {
                    appViewModel.startRefreshAll()
                    navController.navigate(NavRoutes.AI_REFRESH)
                },
                onRefreshWorkers = {
                    appViewModel.startRefreshWorkers()
                    navController.navigate(NavRoutes.AI_REFRESH)
                },
                onRefreshInfoProviders = { navController.navigate(NavRoutes.AI_REFRESH_INFO_PROVIDERS) },
                onResetApplication = { navController.navigate(NavRoutes.AI_RESET_APPLICATION) },
                onRestoreProviders = { navController.navigate(NavRoutes.AI_RESET_ASSETS) },
                onClearInfoProviders = { navController.navigate(NavRoutes.AI_RESET_INFO_PROVIDERS) },
                onClearRuntime = { navController.navigate(NavRoutes.AI_RESET_RUNTIME) },
                onClearConfiguration = { navController.navigate(NavRoutes.AI_RESET_CONFIGURATION) },
                onRestoreAssets = { navController.navigate(NavRoutes.AI_RESET_ASSETS) }
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
                onResetSystemPromptsFromAsset = { appViewModel.resetSystemPromptsFromAssets() },
                onResetDefaultMetaItemsFromAsset = { appViewModel.resetDefaultMetaItemsFromAssets() },
                onResetWorkersFromAsset = { appViewModel.resetWorkersFromAssets() },
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
                initialSubScreen = SettingsSubScreen.AI_IMPORT_EXPORT,
                // Reached from Housekeeping → show 🧹 (not the AI Setup 🤖)
                // and route the tap back to the Housekeeping hub.
                sectionIconOverride = com.ai.ui.shared.TopBarLeftIcon(com.ai.data.MetadataIconsHolder.current.housekeeping) {
                    if (!navController.popBackStack(NavRoutes.AI_HOUSEKEEPING, false))
                        navController.navigate(NavRoutes.AI_HOUSEKEEPING)
                }
            )
        }
        composable(NavRoutes.AI_REFRESH) {
            SettingsScreenNav(
                viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },

                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_REFRESH,
                // Reached from Housekeeping → show 🧹 (not the AI Setup 🤖)
                // and route the tap back to the Housekeeping hub.
                sectionIconOverride = com.ai.ui.shared.TopBarLeftIcon(com.ai.data.MetadataIconsHolder.current.housekeeping) {
                    if (!navController.popBackStack(NavRoutes.AI_HOUSEKEEPING, false))
                        navController.navigate(NavRoutes.AI_HOUSEKEEPING)
                }
            )
        }
        // Deep-link into the Refresh screen's Info Providers sub-page, used by
        // the Manage-data hub's "Refresh" button on the Info-providers card.
        composable(NavRoutes.AI_REFRESH_INFO_PROVIDERS) {
            SettingsScreenNav(
                viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToHelpTopic = { id -> navController.navigate(NavRoutes.helpForTopic(id)) },
                initialSubScreen = SettingsSubScreen.AI_REFRESH,
                refreshOpenInfoProviders = true,
                sectionIconOverride = com.ai.ui.shared.TopBarLeftIcon(com.ai.data.MetadataIconsHolder.current.housekeeping) {
                    if (!navController.popBackStack(NavRoutes.AI_HOUSEKEEPING, false))
                        navController.navigate(NavRoutes.AI_HOUSEKEEPING)
                }
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
                onOpenTechnicalDocs = { navController.navigate(NavRoutes.DOCUMENTATION) },
                onOpenDependencies = { navController.navigate(NavRoutes.DEPENDENCIES) }
            )
        }
        composable(NavRoutes.DEPENDENCIES) {
            com.ai.ui.admin.DependenciesScreen(onBack = safePopBack)
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
        monitorComposable(NavRoutes.TRACE_LIST, monitorNav, MonitorPart.TRACES) {
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() },
                onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) },
                onStats = { navController.navigate(NavRoutes.AI_TRACE_STATS) })
        }
        composable(NavRoutes.TRACE_LIST_FOR_REPORT) { entry ->
            val reportId = entry.arguments?.getString("reportId") ?: ""
            // 📝 Memo on the TitleBar pops back to the AI Reports
            // result page — same anchor every "deeper than result"
            // overlay points at. Falls back to plain navigate when
            // AI_REPORTS isn't on the back stack (e.g. a deep link).
            val backToReport: () -> Unit = {
                if (!navController.popBackStack(NavRoutes.AI_REPORTS, false))
                    navController.navigate(NavRoutes.aiReports())
            }
            androidx.compose.runtime.CompositionLocalProvider(
                com.ai.ui.shared.LocalNavigateToCurrentReport provides backToReport
            ) {
                val uiState by appViewModel.uiState.collectAsState()
                TraceListScreen(aiSettings = uiState.aiSettings,
                    onBack = safePopBack, onNavigateHome = navigateHome,
                    onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                    onClearTraces = { appViewModel.clearTraces() }, reportId = reportId,
                    onNavigateToTraceList = { navController.navigate(NavRoutes.TRACE_LIST) },
                    onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                    onAutoSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) {
                        popUpTo(NavRoutes.TRACE_LIST_FOR_REPORT) { inclusive = true }
                    } },
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
                    navController.navigate(NavRoutes.aiReports())
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
                    onNavigateToTraceList = { navController.navigate(NavRoutes.TRACE_LIST) },
                    onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                    onAutoSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) {
                        popUpTo(NavRoutes.TRACE_LIST_FOR_REPORT_CATEGORY) { inclusive = true }
                    } },
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
                onNavigateToTraceList = { navController.navigate(NavRoutes.TRACE_LIST) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onAutoSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) {
                    popUpTo(NavRoutes.TRACE_LIST_FOR_MODEL) { inclusive = true }
                } },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) })
        }
        composable(NavRoutes.TRACE_LIST_FOR_RUN) { entry ->
            val runId = try { java.net.URLDecoder.decode(entry.arguments?.getString("runId") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, runIdFilter = runId,
                onNavigateToTraceList = { navController.navigate(NavRoutes.TRACE_LIST) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onAutoSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) {
                    popUpTo(NavRoutes.TRACE_LIST_FOR_RUN) { inclusive = true }
                } },
                onSettings = { navController.navigate(NavRoutes.SETTINGS_LOGGING) })
        }
        composable(
            NavRoutes.TRACE_LIST_FILTERED,
            arguments = listOf(
                navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("status") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("model") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("provider") { type = NavType.StringType; nullable = true; defaultValue = null },
            )
        ) { entry ->
            fun arg(k: String) = entry.arguments?.getString(k)?.let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }
            val uiState by appViewModel.uiState.collectAsState()
            TraceListScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() },
                initialHostname = arg("host"), initialStatusClass = arg("status"),
                initialCategory = arg("category"), modelFilter = arg("model"),
                initialProvider = arg("provider"),
                onNavigateToTraceList = { navController.navigate(NavRoutes.TRACE_LIST) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_APPLOG_LIST) },
                onAutoSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) {
                    popUpTo(NavRoutes.TRACE_LIST_FILTERED) { inclusive = true }
                } },
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
                },
                onNavigateToTraceList = { navController.navigate(NavRoutes.TRACE_LIST) }
            )
        }
        composable(NavRoutes.AI_BROKEN_WORK) {
            val brokenWorkContext = LocalContext.current
            val brokenWorkScope = rememberCoroutineScope()
            val broken by appViewModel.brokenBatches.collectAsState()
            var busyBrokenWorkActions by remember { mutableStateOf<Set<String>>(emptySet()) }
            fun launchBrokenWorkAction(batch: BrokenBatch, mode: BrokenItemMode, restart: Boolean, rowIds: Set<String>? = null) {
                val actionKey = brokenWorkActionKey(batch, mode, rowIds.orEmpty())
                if (actionKey in busyBrokenWorkActions) return
                busyBrokenWorkActions = busyBrokenWorkActions + actionKey
                brokenWorkScope.launch(Dispatchers.IO) {
                    try {
                        recoverBrokenBatch(
                            brokenWorkContext,
                            reportViewModel,
                            batch,
                            mode,
                            restart = restart,
                            rowIds = rowIds
                        )
                        reportViewModel.secondary.refreshBrokenBatches(brokenWorkContext)
                    } finally {
                        withContext(Dispatchers.Main) {
                            busyBrokenWorkActions = busyBrokenWorkActions - actionKey
                        }
                    }
                }
            }
            BrokenWorkScreen(
                items = broken,
                busyKeys = busyBrokenWorkActions,
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenReport = { reportId ->
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    brokenWorkScope.launch {
                        reportViewModel.restoreCompletedReport(brokenWorkContext, reportId)
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                // Card-level Continue for the 6 batch-screen families: restore
                // the report, stash a one-shot "open + re-queue this batch"
                // request, and land on Manage — where ConsumePendingBatchOpen
                // shows the build popup, re-queues, and opens the batch screen.
                // popUpTo(inclusive) drops Broken-work so back from Manage lands
                // on the hub, not an emptied Broken-work screen.
                onContinue = { batch ->
                    com.ai.data.LastReportTracker.record(batch.reportId, view = false)
                    brokenWorkScope.launch {
                        val fanOutName = if (batch.kind == BatchFamilyKind.FAN_OUT ||
                            batch.kind == BatchFamilyKind.FAN_META) {
                            withContext(Dispatchers.IO) {
                                (matchingBrokenRows(brokenWorkContext, batch, BrokenItemMode.ERRORS) +
                                    matchingBrokenRows(brokenWorkContext, batch, BrokenItemMode.UNFINISHED))
                                    .firstNotNullOfOrNull { it.metaPromptName?.takeIf { n -> n.isNotBlank() } }
                            }
                        } else null
                        reportViewModel.restoreCompletedReport(brokenWorkContext, batch.reportId)
                        appViewModel.requestBatchOpen(
                            PendingBatchOpen(batch.reportId, batch.kind, batch.key, fanOutName)
                        )
                        navController.navigate(NavRoutes.aiReportManage()) {
                            popUpTo(NavRoutes.AI_BROKEN_WORK) { inclusive = true }
                        }
                    }
                },
                // Card tap → the item's own screen (view-only PendingBatchOpen,
                // no re-queue). OTHER carries the errored secondary's result id so
                // its detail opens; FAN_OUT/FAN_META carry the metaPromptName the
                // fan-out list filters by; the rest open their batch screen.
                onOpenItem = { batch ->
                    com.ai.data.LastReportTracker.record(batch.reportId, view = false)
                    brokenWorkScope.launch {
                        val key: String
                        val fanOutName: String?
                        when (batch.kind) {
                            BatchFamilyKind.OTHER -> {
                                key = withContext(Dispatchers.IO) {
                                    matchingBrokenRows(brokenWorkContext, batch, BrokenItemMode.ERRORS).firstOrNull()?.id
                                } ?: batch.key
                                fanOutName = null
                            }
                            BatchFamilyKind.FAN_OUT, BatchFamilyKind.FAN_META -> {
                                key = batch.key
                                fanOutName = withContext(Dispatchers.IO) {
                                    (matchingBrokenRows(brokenWorkContext, batch, BrokenItemMode.ERRORS) +
                                        matchingBrokenRows(brokenWorkContext, batch, BrokenItemMode.UNFINISHED))
                                        .firstNotNullOfOrNull { it.metaPromptName?.takeIf { n -> n.isNotBlank() } }
                                }
                            }
                            else -> { key = batch.key; fanOutName = null }
                        }
                        reportViewModel.restoreCompletedReport(brokenWorkContext, batch.reportId)
                        appViewModel.requestBatchOpen(
                            PendingBatchOpen(batch.reportId, batch.kind, key, fanOutName, viewOnly = true)
                        )
                        navController.navigate(NavRoutes.aiReportManage())
                    }
                },
                onRestart = { batch, mode ->
                    launchBrokenWorkAction(batch, mode, restart = true)
                },
                onDelete = { batch, mode ->
                    launchBrokenWorkAction(batch, mode, restart = false)
                },
                onRestartItems = { batch, mode, rowIds ->
                    launchBrokenWorkAction(batch, mode, restart = true, rowIds = rowIds)
                },
                onDeleteItems = { batch, mode, rowIds ->
                    launchBrokenWorkAction(batch, mode, restart = false, rowIds = rowIds)
                },
                onOpenModel = { reportId, agentId ->
                    // Tap a broken agent → its Model response screen (restore
                    // the report first, like opening it to manage).
                    com.ai.data.LastReportTracker.record(reportId, view = false)
                    brokenWorkScope.launch {
                        reportViewModel.restoreCompletedReport(brokenWorkContext, reportId)
                        navController.navigate(NavRoutes.aiReportModel(reportId, agentId))
                    }
                },
                onOpenTrace = { filename -> navController.navigate(NavRoutes.traceDetail(filename)) },
                loadItems = { batch, mode -> loadBrokenItems(brokenWorkContext, batch, mode) },
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

/** Dispatch a Caches → entry 🔄 that needs API keys / the view model: model
 *  lists via [AppViewModel.fetchModelsAwait], the OpenRouter supported-params +
 *  AA / llm-stats / OpenRouter pricing tiers via their small VM refresh
 *  methods. The key-free pricing tiers (LiteLLM / models.dev / llm-prices /
 *  Helicone / Requesty) refresh inline in the descriptor and never reach here. */
private fun cacheRefreshDispatcher(appViewModel: AppViewModel): suspend (String, String) -> Unit = { cacheId, entryId ->
    when (cacheId) {
        "modellists" -> AppService.findById(entryId)?.let { svc ->
            appViewModel.fetchModelsAwait(svc, appViewModel.uiState.value.aiSettings.getApiKey(svc))
        }
        "params" -> appViewModel.refreshSupportedParamsCacheAwait()
        "pricing" -> when (entryId) {
            "Artificial Analysis" -> appViewModel.refreshAaPricingCacheAwait()
            "llm-stats" -> appViewModel.refreshLlmStatsPricingCacheAwait()
            "OpenRouter" -> appViewModel.refreshOpenRouterPricingCacheAwait()
        }
    }
}
