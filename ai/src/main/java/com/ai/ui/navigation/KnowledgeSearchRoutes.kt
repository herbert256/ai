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

internal fun NavGraphBuilder.knowledgeSearchRoutes(
    navController: NavHostController,
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    chatViewModel: ChatViewModel,
    safePopBack: () -> Unit,
    navigateHome: () -> Unit
) {
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
                },
                onHousekeeping = { navController.navigate(NavRoutes.AI_TRIM_BY_AGE) }
            )
        }

        // ===== Usage =====
        composable(NavRoutes.AI_MODEL_SEARCH) {
            val uiState by appViewModel.uiState.collectAsState()
            // Browse mode: tap a row → open that model's Model Info
            // page. Picker stays mounted on the back stack so back
            // from Model Info returns to the list, not the Hub.
            com.ai.ui.other.ReportSelectModelsScreen(
                aiSettings = uiState.aiSettings,
                titleText = "AI Models",
                onConfirm = { (p, m) -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
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
}
