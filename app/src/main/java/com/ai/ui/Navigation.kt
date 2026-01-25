package com.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Navigation routes for the app.
 */
object NavRoutes {
    const val AI = "ai"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val TRACE_LIST = "trace_list"
    const val TRACE_DETAIL = "trace_detail/{filename}"
    const val AI_HISTORY = "ai_history"
    const val AI_NEW_REPORT = "ai_new_report"
    const val AI_NEW_REPORT_WITH_PARAMS = "ai_new_report/{title}/{prompt}"
    const val AI_PROMPT_HISTORY = "ai_prompt_history"
    const val AI_REPORTS = "ai_reports"

    fun traceDetail(filename: String) = "trace_detail/$filename"
    fun aiNewReportWithParams(title: String, prompt: String): String {
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8")
        return "ai_new_report/$encodedTitle/$encodedPrompt"
    }
}

/**
 * Main navigation host for the app.
 * @param externalTitle Optional title from external app intent
 * @param externalPrompt Optional prompt from external app intent
 * @param onExternalIntentHandled Callback when external intent has been processed
 */
@Composable
fun AiNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewModel: AiViewModel = viewModel(),
    externalTitle: String? = null,
    externalPrompt: String? = null,
    onExternalIntentHandled: () -> Unit = {}
) {
    // Navigate to home, clearing the back stack
    val navigateHome: () -> Unit = {
        navController.navigate(NavRoutes.AI) {
            popUpTo(NavRoutes.AI) { inclusive = true }
        }
    }

    // Handle external intent - navigate to new report when external prompt is provided
    LaunchedEffect(externalPrompt) {
        if (externalPrompt != null) {
            navController.navigate(NavRoutes.aiNewReportWithParams(externalTitle ?: "", externalPrompt)) {
                // Clear back stack so back button goes to home
                popUpTo(NavRoutes.AI) { inclusive = false }
            }
            onExternalIntentHandled()
        }
    }

    NavHost(
        navController = navController,
        startDestination = NavRoutes.AI,
        modifier = modifier
    ) {
        composable(NavRoutes.AI) {
            AiHubScreen(
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onNavigateToTrace = { navController.navigate(NavRoutes.TRACE_LIST) },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
                onNavigateToHistory = { navController.navigate(NavRoutes.AI_HISTORY) },
                onNavigateToNewReport = { navController.navigate(NavRoutes.AI_NEW_REPORT) },
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) },
                viewModel = viewModel
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.HELP) {
            HelpScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.TRACE_LIST) {
            val uiState by viewModel.uiState.collectAsState()
            TraceListScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectTrace = { filename ->
                    navController.navigate(NavRoutes.traceDetail(filename))
                },
                onClearTraces = { viewModel.clearTraces() },
                pageSize = uiState.generalSettings.paginationPageSize
            )
        }

        composable(NavRoutes.TRACE_DETAIL) { backStackEntry ->
            val filename = backStackEntry.arguments?.getString("filename") ?: ""
            TraceDetailScreen(
                filename = filename,
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.AI_HISTORY) {
            val uiState by viewModel.uiState.collectAsState()
            AiHistoryScreenNav(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                pageSize = uiState.generalSettings.paginationPageSize
            )
        }

        composable(NavRoutes.AI_NEW_REPORT) {
            AiNewReportScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToAiReports = { navController.navigate(NavRoutes.AI_REPORTS) }
            )
        }

        composable(NavRoutes.AI_NEW_REPORT_WITH_PARAMS) { backStackEntry ->
            val encodedTitle = backStackEntry.arguments?.getString("title") ?: ""
            val encodedPrompt = backStackEntry.arguments?.getString("prompt") ?: ""
            val title = try { java.net.URLDecoder.decode(encodedTitle, "UTF-8") } catch (e: Exception) { encodedTitle }
            val prompt = try { java.net.URLDecoder.decode(encodedPrompt, "UTF-8") } catch (e: Exception) { encodedPrompt }
            AiNewReportScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToAiReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                initialTitle = title,
                initialPrompt = prompt
            )
        }

        composable(NavRoutes.AI_PROMPT_HISTORY) {
            val uiState by viewModel.uiState.collectAsState()
            PromptHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectEntry = { entry ->
                    navController.navigate(NavRoutes.aiNewReportWithParams(entry.title, entry.prompt))
                },
                pageSize = uiState.generalSettings.paginationPageSize
            )
        }

        composable(NavRoutes.AI_REPORTS) {
            AiReportsScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }
    }
}

/**
 * Wrapper for SettingsScreen that gets state from ViewModel.
 */
@Composable
fun SettingsScreenNav(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    SettingsScreen(
        generalSettings = uiState.generalSettings,
        aiSettings = uiState.aiSettings,
        availableChatGptModels = uiState.availableChatGptModels,
        isLoadingChatGptModels = uiState.isLoadingChatGptModels,
        availableGeminiModels = uiState.availableGeminiModels,
        isLoadingGeminiModels = uiState.isLoadingGeminiModels,
        availableGrokModels = uiState.availableGrokModels,
        isLoadingGrokModels = uiState.isLoadingGrokModels,
        availableGroqModels = uiState.availableGroqModels,
        isLoadingGroqModels = uiState.isLoadingGroqModels,
        availableDeepSeekModels = uiState.availableDeepSeekModels,
        isLoadingDeepSeekModels = uiState.isLoadingDeepSeekModels,
        availableMistralModels = uiState.availableMistralModels,
        isLoadingMistralModels = uiState.isLoadingMistralModels,
        availablePerplexityModels = uiState.availablePerplexityModels,
        isLoadingPerplexityModels = uiState.isLoadingPerplexityModels,
        availableTogetherModels = uiState.availableTogetherModels,
        isLoadingTogetherModels = uiState.isLoadingTogetherModels,
        availableOpenRouterModels = uiState.availableOpenRouterModels,
        isLoadingOpenRouterModels = uiState.isLoadingOpenRouterModels,
        availableDummyModels = uiState.availableDummyModels,
        isLoadingDummyModels = uiState.isLoadingDummyModels,
        onBack = onNavigateBack,
        onNavigateHome = onNavigateHome,
        onSaveGeneral = { viewModel.updateGeneralSettings(it) },
        onTrackApiCallsChanged = { viewModel.updateTrackApiCalls(it) },
        onSaveAi = { viewModel.updateAiSettings(it) },
        onFetchChatGptModels = { viewModel.fetchChatGptModels(it) },
        onFetchGeminiModels = { viewModel.fetchGeminiModels(it) },
        onFetchGrokModels = { viewModel.fetchGrokModels(it) },
        onFetchGroqModels = { viewModel.fetchGroqModels(it) },
        onFetchDeepSeekModels = { viewModel.fetchDeepSeekModels(it) },
        onFetchMistralModels = { viewModel.fetchMistralModels(it) },
        onFetchPerplexityModels = { viewModel.fetchPerplexityModels(it) },
        onFetchTogetherModels = { viewModel.fetchTogetherModels(it) },
        onFetchOpenRouterModels = { viewModel.fetchOpenRouterModels(it) },
        onFetchDummyModels = { viewModel.fetchDummyModels(it) },
        onTestAiModel = { service, apiKey, model -> viewModel.testAiModel(service, apiKey, model) },
        onFetchModelsAfterImport = { viewModel.fetchModelsForApiSourceProviders(it) }
    )
}
