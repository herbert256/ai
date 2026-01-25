package com.eval.ui

import androidx.compose.runtime.Composable
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
    const val GAME = "game"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val TRACE_LIST = "trace_list"
    const val TRACE_DETAIL = "trace_detail/{filename}"
    const val RETRIEVE = "retrieve"
    const val AI = "ai"
    const val AI_HISTORY = "ai_history"
    const val AI_NEW_REPORT = "ai_new_report"
    const val AI_NEW_REPORT_WITH_PARAMS = "ai_new_report/{title}/{prompt}"
    const val AI_PROMPT_HISTORY = "ai_prompt_history"
    const val PLAYER_INFO = "player_info"
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
 */
@Composable
fun EvalNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewModel: GameViewModel = viewModel()
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.GAME,
        modifier = modifier
    ) {
        composable(NavRoutes.GAME) {
            GameScreenContent(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
                onNavigateToTrace = { navController.navigate(NavRoutes.TRACE_LIST) },
                onNavigateToRetrieve = { navController.navigate(NavRoutes.RETRIEVE) },
                onNavigateToAi = { navController.navigate(NavRoutes.AI) },
                onNavigateToPlayerInfo = { navController.navigate(NavRoutes.PLAYER_INFO) },
                onNavigateToAiReports = { navController.navigate(NavRoutes.AI_REPORTS) }
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.HELP) {
            HelpScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.TRACE_LIST) {
            TraceListScreen(
                onBack = { navController.popBackStack() },
                onSelectTrace = { filename ->
                    navController.navigate(NavRoutes.traceDetail(filename))
                },
                onClearTraces = { viewModel.clearTraces() }
            )
        }

        composable(NavRoutes.TRACE_DETAIL) { backStackEntry ->
            val filename = backStackEntry.arguments?.getString("filename") ?: ""
            TraceDetailScreen(
                filename = filename,
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.RETRIEVE) {
            RetrieveScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGame = { navController.navigate(NavRoutes.GAME) }
            )
        }

        composable(NavRoutes.AI) {
            AiHubScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHistory = { navController.navigate(NavRoutes.AI_HISTORY) },
                onNavigateToNewReport = { navController.navigate(NavRoutes.AI_NEW_REPORT) },
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) }
            )
        }

        composable(NavRoutes.AI_HISTORY) {
            AiHistoryScreenNav(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.AI_NEW_REPORT) {
            AiNewReportScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
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
                onNavigateToAiReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                initialTitle = title,
                initialPrompt = prompt
            )
        }

        composable(NavRoutes.AI_PROMPT_HISTORY) {
            PromptHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onSelectEntry = { entry ->
                    navController.navigate(NavRoutes.aiNewReportWithParams(entry.title, entry.prompt))
                }
            )
        }

        composable(NavRoutes.PLAYER_INFO) {
            PlayerInfoScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.AI_REPORTS) {
            AiReportsScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Wrapper for SettingsScreen that gets state from ViewModel.
 */
@Composable
fun SettingsScreenNav(
    viewModel: GameViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    SettingsScreen(
        stockfishSettings = uiState.stockfishSettings,
        boardLayoutSettings = uiState.boardLayoutSettings,
        graphSettings = uiState.graphSettings,
        interfaceVisibility = uiState.interfaceVisibility,
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
        onBack = onNavigateBack,
        onSaveStockfish = { viewModel.updateStockfishSettings(it) },
        onSaveBoardLayout = { viewModel.updateBoardLayoutSettings(it) },
        onSaveGraph = { viewModel.updateGraphSettings(it) },
        onSaveInterfaceVisibility = { viewModel.updateInterfaceVisibilitySettings(it) },
        onSaveGeneral = { viewModel.updateGeneralSettings(it) },
        onTrackApiCallsChanged = { viewModel.updateTrackApiCalls(it) },
        onDeveloperModeChanged = {
            viewModel.resetToHomepage()
            onNavigateBack()
        },
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
        onTestAiModel = { service, apiKey, model -> viewModel.testAiModel(service, apiKey, model) }
    )
}

/**
 * Wrapper for RetrieveScreen that gets state from ViewModel.
 */
@Composable
fun RetrieveScreenNav(
    viewModel: GameViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGame: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    RetrieveScreen(
        viewModel = viewModel,
        uiState = uiState,
        onBack = onNavigateBack,
        onNavigateToGame = onNavigateToGame
    )
}
