package com.ai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    const val AI_STATISTICS = "ai_statistics"
    const val AI_SETUP = "ai_setup"
    const val AI_CHAT_PROVIDER = "ai_chat_provider"
    const val AI_CHAT_MODEL = "ai_chat_model/{provider}"
    const val AI_CHAT_PARAMS = "ai_chat_params/{provider}/{model}"
    const val AI_CHAT_SESSION = "ai_chat_session/{provider}/{model}"
    const val AI_CHAT_HISTORY = "ai_chat_history"
    const val AI_CHAT_CONTINUE = "ai_chat_continue/{sessionId}"

    fun traceDetail(filename: String) = "trace_detail/$filename"
    fun aiChatContinue(sessionId: String) = "ai_chat_continue/$sessionId"
    fun aiChatModel(provider: String) = "ai_chat_model/$provider"
    fun aiChatParams(provider: String, model: String): String {
        val encodedModel = java.net.URLEncoder.encode(model, "UTF-8")
        return "ai_chat_params/$provider/$encodedModel"
    }
    fun aiChatSession(provider: String, model: String): String {
        val encodedModel = java.net.URLEncoder.encode(model, "UTF-8")
        return "ai_chat_session/$provider/$encodedModel"
    }
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
                onNavigateToStatistics = { navController.navigate(NavRoutes.AI_STATISTICS) },
                onNavigateToNewChat = { navController.navigate(NavRoutes.AI_CHAT_PROVIDER) },
                onNavigateToChatHistory = { navController.navigate(NavRoutes.AI_CHAT_HISTORY) },
                onNavigateToAiSetup = { navController.navigate(NavRoutes.AI_SETUP) },
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

        composable(NavRoutes.AI_SETUP) {
            AiSetupScreenNav(
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
                onNavigateToAiReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) }
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
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) },
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

        composable(NavRoutes.AI_STATISTICS) {
            AiStatisticsScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        // Chat screens
        composable(NavRoutes.AI_CHAT_PROVIDER) {
            val uiState by viewModel.uiState.collectAsState()
            ChatSelectProviderScreen(
                aiSettings = uiState.aiSettings,
                developerMode = uiState.generalSettings.developerMode,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectProvider = { provider ->
                    navController.navigate(NavRoutes.aiChatModel(provider.name))
                }
            )
        }

        composable(NavRoutes.AI_CHAT_MODEL) { backStackEntry ->
            val providerName = backStackEntry.arguments?.getString("provider") ?: ""
            val provider = try { com.ai.data.AiService.valueOf(providerName) } catch (e: Exception) { null }
            val uiState by viewModel.uiState.collectAsState()

            if (provider != null) {
                val availableModels = when (provider) {
                    com.ai.data.AiService.CHATGPT -> uiState.availableChatGptModels
                    com.ai.data.AiService.GEMINI -> uiState.availableGeminiModels
                    com.ai.data.AiService.GROK -> uiState.availableGrokModels
                    com.ai.data.AiService.GROQ -> uiState.availableGroqModels
                    com.ai.data.AiService.DEEPSEEK -> uiState.availableDeepSeekModels
                    com.ai.data.AiService.MISTRAL -> uiState.availableMistralModels
                    com.ai.data.AiService.TOGETHER -> uiState.availableTogetherModels
                    com.ai.data.AiService.OPENROUTER -> uiState.availableOpenRouterModels
                    com.ai.data.AiService.DUMMY -> uiState.availableDummyModels
                    else -> emptyList()
                }
                val isLoadingModels = when (provider) {
                    com.ai.data.AiService.CHATGPT -> uiState.isLoadingChatGptModels
                    com.ai.data.AiService.GEMINI -> uiState.isLoadingGeminiModels
                    com.ai.data.AiService.GROK -> uiState.isLoadingGrokModels
                    com.ai.data.AiService.GROQ -> uiState.isLoadingGroqModels
                    com.ai.data.AiService.DEEPSEEK -> uiState.isLoadingDeepSeekModels
                    com.ai.data.AiService.MISTRAL -> uiState.isLoadingMistralModels
                    com.ai.data.AiService.TOGETHER -> uiState.isLoadingTogetherModels
                    com.ai.data.AiService.OPENROUTER -> uiState.isLoadingOpenRouterModels
                    com.ai.data.AiService.DUMMY -> uiState.isLoadingDummyModels
                    else -> false
                }

                ChatSelectModelScreen(
                    provider = provider,
                    aiSettings = uiState.aiSettings,
                    availableModels = availableModels,
                    isLoadingModels = isLoadingModels,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome,
                    onSelectModel = { model ->
                        navController.navigate(NavRoutes.aiChatParams(provider.name, model))
                    },
                    onFetchModels = { apiKey ->
                        when (provider) {
                            com.ai.data.AiService.CHATGPT -> viewModel.fetchChatGptModels(apiKey)
                            com.ai.data.AiService.GEMINI -> viewModel.fetchGeminiModels(apiKey)
                            com.ai.data.AiService.GROK -> viewModel.fetchGrokModels(apiKey)
                            com.ai.data.AiService.GROQ -> viewModel.fetchGroqModels(apiKey)
                            com.ai.data.AiService.DEEPSEEK -> viewModel.fetchDeepSeekModels(apiKey)
                            com.ai.data.AiService.MISTRAL -> viewModel.fetchMistralModels(apiKey)
                            com.ai.data.AiService.TOGETHER -> viewModel.fetchTogetherModels(apiKey)
                            com.ai.data.AiService.OPENROUTER -> viewModel.fetchOpenRouterModels(apiKey)
                            com.ai.data.AiService.DUMMY -> viewModel.fetchDummyModels(apiKey)
                            else -> {}
                        }
                    }
                )
            }
        }

        composable(NavRoutes.AI_CHAT_PARAMS) { backStackEntry ->
            val providerName = backStackEntry.arguments?.getString("provider") ?: ""
            val encodedModel = backStackEntry.arguments?.getString("model") ?: ""
            val model = try { java.net.URLDecoder.decode(encodedModel, "UTF-8") } catch (e: Exception) { encodedModel }
            val provider = try { com.ai.data.AiService.valueOf(providerName) } catch (e: Exception) { null }

            if (provider != null) {
                ChatParametersScreen(
                    provider = provider,
                    model = model,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome,
                    onStartChat = { params ->
                        // Store params in ViewModel and navigate to session
                        viewModel.setChatParameters(params)
                        navController.navigate(NavRoutes.aiChatSession(provider.name, model))
                    }
                )
            }
        }

        composable(NavRoutes.AI_CHAT_SESSION) { backStackEntry ->
            val providerName = backStackEntry.arguments?.getString("provider") ?: ""
            val encodedModel = backStackEntry.arguments?.getString("model") ?: ""
            val model = try { java.net.URLDecoder.decode(encodedModel, "UTF-8") } catch (e: Exception) { encodedModel }
            val provider = try { com.ai.data.AiService.valueOf(providerName) } catch (e: Exception) { null }
            val uiState by viewModel.uiState.collectAsState()

            if (provider != null) {
                val apiKey = when (provider) {
                    com.ai.data.AiService.CHATGPT -> uiState.aiSettings.chatGptApiKey
                    com.ai.data.AiService.CLAUDE -> uiState.aiSettings.claudeApiKey
                    com.ai.data.AiService.GEMINI -> uiState.aiSettings.geminiApiKey
                    com.ai.data.AiService.GROK -> uiState.aiSettings.grokApiKey
                    com.ai.data.AiService.GROQ -> uiState.aiSettings.groqApiKey
                    com.ai.data.AiService.DEEPSEEK -> uiState.aiSettings.deepSeekApiKey
                    com.ai.data.AiService.MISTRAL -> uiState.aiSettings.mistralApiKey
                    com.ai.data.AiService.PERPLEXITY -> uiState.aiSettings.perplexityApiKey
                    com.ai.data.AiService.TOGETHER -> uiState.aiSettings.togetherApiKey
                    com.ai.data.AiService.OPENROUTER -> uiState.aiSettings.openRouterApiKey
                    com.ai.data.AiService.SILICONFLOW -> uiState.aiSettings.siliconFlowApiKey
                    com.ai.data.AiService.ZAI -> uiState.aiSettings.zaiApiKey
                    com.ai.data.AiService.DUMMY -> uiState.aiSettings.dummyApiKey
                }

                ChatSessionScreen(
                    provider = provider,
                    model = model,
                    apiKey = apiKey,
                    parameters = uiState.chatParameters,
                    userName = uiState.generalSettings.userName,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome,
                    onSendMessage = { messages, _ ->
                        viewModel.sendChatMessage(provider, apiKey, model, messages)
                    }
                )
            }
        }

        // Chat history screen
        composable(NavRoutes.AI_CHAT_HISTORY) {
            val uiState by viewModel.uiState.collectAsState()
            ChatHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectSession = { sessionId ->
                    navController.navigate(NavRoutes.aiChatContinue(sessionId))
                },
                pageSize = uiState.generalSettings.paginationPageSize
            )
        }

        // Continue chat session
        composable(NavRoutes.AI_CHAT_CONTINUE) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            val uiState by viewModel.uiState.collectAsState()
            val session = remember(sessionId) {
                com.ai.data.ChatHistoryManager.loadSession(sessionId)
            }

            if (session != null) {
                val apiKey = uiState.aiSettings.getApiKey(session.provider)

                ChatSessionScreen(
                    provider = session.provider,
                    model = session.model,
                    apiKey = apiKey,
                    parameters = session.parameters,
                    userName = uiState.generalSettings.userName,
                    initialMessages = session.messages,
                    sessionId = session.id,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome,
                    onSendMessage = { messages, _ ->
                        viewModel.sendChatMessage(session.provider, apiKey, session.model, messages)
                    }
                )
            } else {
                // Session not found - navigate back
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
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
    onNavigateHome: () -> Unit,
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN
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
        onFetchModelsAfterImport = { viewModel.fetchModelsForApiSourceProviders(it) },
        initialSubScreen = initialSubScreen
    )
}

/**
 * Wrapper for SettingsScreen that starts at AI Setup.
 */
@Composable
fun AiSetupScreenNav(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    SettingsScreenNav(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onNavigateHome = onNavigateHome,
        initialSubScreen = SettingsSubScreen.AI_SETUP
    )
}
