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
    const val TRACE_LIST_FOR_REPORT = "trace_list/{reportId}"
    const val TRACE_DETAIL = "trace_detail/{filename}"
    const val AI_HISTORY = "ai_history"
    const val AI_REPORTS_HUB = "ai_reports_hub"
    const val AI_NEW_REPORT = "ai_new_report"
    const val AI_NEW_REPORT_WITH_PARAMS = "ai_new_report/{title}/{prompt}"
    const val AI_PROMPT_HISTORY = "ai_prompt_history"
    const val AI_REPORTS = "ai_reports"
    const val AI_STATISTICS = "ai_statistics"
    const val AI_COSTS = "ai_costs"
    const val AI_COST_CONFIG = "ai_cost_config"
    const val AI_SETUP = "ai_setup"
    const val AI_AI_SETTINGS = "ai_ai_settings"
    const val AI_HOUSEKEEPING = "ai_housekeeping"
    const val AI_CHATS_HUB = "ai_chats_hub"
    const val AI_CHAT_AGENT_SELECT = "ai_chat_agent_select"
    const val AI_CHAT_WITH_AGENT = "ai_chat_with_agent/{agentId}"
    const val AI_CHAT_SEARCH = "ai_chat_search"
    const val AI_CHAT_PROVIDER = "ai_chat_provider"
    const val AI_CHAT_MODEL = "ai_chat_model/{provider}"
    const val AI_CHAT_PARAMS = "ai_chat_params/{provider}/{model}"
    const val AI_CHAT_SESSION = "ai_chat_session/{provider}/{model}"
    const val AI_CHAT_HISTORY = "ai_chat_history"
    const val AI_CHAT_CONTINUE = "ai_chat_continue/{sessionId}"
    const val AI_MODEL_SEARCH = "ai_model_search"
    const val AI_MODEL_INFO = "ai_model_info/{provider}/{model}"
    const val AI_API_TEST = "ai_api_test"
    const val AI_API_TEST_EDIT = "ai_api_test_edit"
    const val DEVELOPER_OPTIONS = "developer_options"

    fun traceDetail(filename: String) = "trace_detail/$filename"
    fun traceListForReport(reportId: String) = "trace_list/$reportId"
    fun aiModelInfo(provider: String, model: String): String {
        val encodedModel = java.net.URLEncoder.encode(model, "UTF-8")
        return "ai_model_info/$provider/$encodedModel"
    }
    fun aiChatContinue(sessionId: String) = "ai_chat_continue/$sessionId"
    fun aiChatWithAgent(agentId: String) = "ai_chat_with_agent/$agentId"
    fun aiChatModel(provider: String) = "ai_chat_model/$provider"
    fun aiChatParams(provider: String, model: String): String {
        val encodedModel = java.net.URLEncoder.encode(model, "UTF-8")
        return "ai_chat_params/$provider/$encodedModel"
    }
    fun aiChatSession(provider: String, model: String): String {
        // Use %20 for spaces instead of + (URLEncoder uses + which Navigation doesn't decode)
        val encodedModel = java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")
        return "ai_chat_session/$provider/$encodedModel"
    }
    fun aiNewReportWithParams(title: String, prompt: String): String {
        // Use %20 for spaces instead of + (URLEncoder uses + which Navigation doesn't decode)
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
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
                onNavigateToDeveloperOptions = { navController.navigate(NavRoutes.DEVELOPER_OPTIONS) },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
                onNavigateToReportsHub = { navController.navigate(NavRoutes.AI_REPORTS_HUB) },
                onNavigateToStatistics = { navController.navigate(NavRoutes.AI_STATISTICS) },
                onNavigateToCosts = { navController.navigate(NavRoutes.AI_COSTS) },
                onNavigateToChatsHub = { navController.navigate(NavRoutes.AI_CHATS_HUB) },
                onNavigateToAiSetup = { navController.navigate(NavRoutes.AI_SETUP) },
                onNavigateToHousekeeping = { navController.navigate(NavRoutes.AI_HOUSEKEEPING) },
                onNavigateToModelSearch = { navController.navigate(NavRoutes.AI_MODEL_SEARCH) },
                viewModel = viewModel
            )
        }

        composable(NavRoutes.SETTINGS) {
            SettingsScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) }
            )
        }

        composable(NavRoutes.AI_SETUP) {
            AiSetupScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) }
            )
        }

        composable(NavRoutes.AI_AI_SETTINGS) {
            AiAiSettingsScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) }
            )
        }

        composable(NavRoutes.AI_HOUSEKEEPING) {
            HousekeepingScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.DEVELOPER_OPTIONS) {
            DeveloperOptionsScreen(
                onBackToHome = navigateHome,
                onNavigateToApiTest = { navController.navigate(NavRoutes.AI_API_TEST) },
                onNavigateToTraces = { navController.navigate(NavRoutes.TRACE_LIST) }
            )
        }

        composable(NavRoutes.HELP) {
            HelpScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.TRACE_LIST) {
            TraceListScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectTrace = { filename ->
                    navController.navigate(NavRoutes.traceDetail(filename))
                },
                onClearTraces = { viewModel.clearTraces() }
            )
        }

        composable(NavRoutes.TRACE_LIST_FOR_REPORT) { backStackEntry ->
            val reportId = backStackEntry.arguments?.getString("reportId") ?: ""
            TraceListScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectTrace = { filename ->
                    navController.navigate(NavRoutes.traceDetail(filename))
                },
                onClearTraces = { viewModel.clearTraces() },
                reportId = reportId
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
                developerMode = uiState.generalSettings.developerMode
            )
        }

        composable(NavRoutes.AI_REPORTS_HUB) {
            AiReportsHubScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToNewReport = { navController.navigate(NavRoutes.AI_NEW_REPORT) },
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) },
                onNavigateToHistory = { navController.navigate(NavRoutes.AI_HISTORY) }
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
            PromptHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectEntry = { entry ->
                    navController.navigate(NavRoutes.aiNewReportWithParams(entry.title, entry.prompt))
                }
            )
        }

        composable(NavRoutes.AI_REPORTS) {
            val uiState by viewModel.uiState.collectAsState()
            AiReportsScreenNav(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToTrace = { reportId ->
                    navController.navigate(NavRoutes.traceListForReport(reportId))
                },
                developerMode = uiState.generalSettings.developerMode
            )
        }

        composable(NavRoutes.AI_STATISTICS) {
            AiStatisticsScreen(
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.AI_COSTS) {
            val uiState by viewModel.uiState.collectAsState()
            AiCostsScreen(
                openRouterApiKey = uiState.aiSettings.openRouterApiKey,
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.AI_COST_CONFIG) {
            val uiState by viewModel.uiState.collectAsState()
            CostConfigurationScreen(
                aiSettings = uiState.aiSettings,
                developerMode = uiState.generalSettings.developerMode,
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
            )
        }

        composable(NavRoutes.AI_MODEL_SEARCH) {
            val uiState by viewModel.uiState.collectAsState()
            ModelSearchScreen(
                aiSettings = uiState.aiSettings,
                developerMode = uiState.generalSettings.developerMode,
                availableChatGptModels = uiState.availableChatGptModels,
                availableGeminiModels = uiState.availableGeminiModels,
                availableGrokModels = uiState.availableGrokModels,
                availableGroqModels = uiState.availableGroqModels,
                availableDeepSeekModels = uiState.availableDeepSeekModels,
                availableMistralModels = uiState.availableMistralModels,
                availablePerplexityModels = uiState.availablePerplexityModels,
                availableTogetherModels = uiState.availableTogetherModels,
                availableOpenRouterModels = uiState.availableOpenRouterModels,
                availableClaudeModels = uiState.availableClaudeModels,
                availableSiliconFlowModels = uiState.availableSiliconFlowModels,
                availableZaiModels = uiState.availableZaiModels,
                availableMoonshotModels = uiState.availableMoonshotModels,
                availableCohereModels = uiState.availableCohereModels,
                availableAi21Models = uiState.availableAi21Models,
                availableDashScopeModels = uiState.availableDashScopeModels,
                availableFireworksModels = uiState.availableFireworksModels,
                availableCerebrasModels = uiState.availableCerebrasModels,
                availableSambaNovaModels = uiState.availableSambaNovaModels,
                availableBaichuanModels = uiState.availableBaichuanModels,
                availableStepFunModels = uiState.availableStepFunModels,
                availableMiniMaxModels = uiState.availableMiniMaxModels,
                availableNvidiaModels = uiState.availableNvidiaModels,
                availableReplicateModels = uiState.availableReplicateModels,
                availableHuggingFaceInferenceModels = uiState.availableHuggingFaceInferenceModels,
                availableLambdaModels = uiState.availableLambdaModels,
                availableLeptonModels = uiState.availableLeptonModels,
                availableYiModels = uiState.availableYiModels,
                availableDoubaoModels = uiState.availableDoubaoModels,
                availableRekaModels = uiState.availableRekaModels,
                availableWriterModels = uiState.availableWriterModels,
                isLoadingChatGptModels = uiState.isLoadingChatGptModels,
                isLoadingGeminiModels = uiState.isLoadingGeminiModels,
                isLoadingGrokModels = uiState.isLoadingGrokModels,
                isLoadingGroqModels = uiState.isLoadingGroqModels,
                isLoadingDeepSeekModels = uiState.isLoadingDeepSeekModels,
                isLoadingMistralModels = uiState.isLoadingMistralModels,
                isLoadingTogetherModels = uiState.isLoadingTogetherModels,
                isLoadingOpenRouterModels = uiState.isLoadingOpenRouterModels,
                isLoadingClaudeModels = uiState.isLoadingClaudeModels,
                isLoadingSiliconFlowModels = uiState.isLoadingSiliconFlowModels,
                isLoadingZaiModels = uiState.isLoadingZaiModels,
                isLoadingMoonshotModels = uiState.isLoadingMoonshotModels,
                isLoadingCohereModels = uiState.isLoadingCohereModels,
                isLoadingAi21Models = uiState.isLoadingAi21Models,
                isLoadingDashScopeModels = uiState.isLoadingDashScopeModels,
                isLoadingFireworksModels = uiState.isLoadingFireworksModels,
                isLoadingCerebrasModels = uiState.isLoadingCerebrasModels,
                isLoadingSambaNovaModels = uiState.isLoadingSambaNovaModels,
                isLoadingBaichuanModels = uiState.isLoadingBaichuanModels,
                isLoadingStepFunModels = uiState.isLoadingStepFunModels,
                isLoadingMiniMaxModels = uiState.isLoadingMiniMaxModels,
                onBackToAiSetup = { navController.popBackStack() },
                onBackToHome = navigateHome,
                onSaveAiSettings = { viewModel.updateAiSettings(it) },
                onTestAiModel = { service, apiKey, model -> viewModel.testAiModel(service, apiKey, model) },
                onFetchChatGptModels = { viewModel.fetchChatGptModels(it) },
                onFetchGeminiModels = { viewModel.fetchGeminiModels(it) },
                onFetchGrokModels = { viewModel.fetchGrokModels(it) },
                onFetchGroqModels = { viewModel.fetchGroqModels(it) },
                onFetchDeepSeekModels = { viewModel.fetchDeepSeekModels(it) },
                onFetchMistralModels = { viewModel.fetchMistralModels(it) },
                onFetchPerplexityModels = { viewModel.fetchPerplexityModels(it) },
                onFetchTogetherModels = { viewModel.fetchTogetherModels(it) },
                onFetchOpenRouterModels = { viewModel.fetchOpenRouterModels(it) },
                onFetchClaudeModels = { viewModel.fetchClaudeModels(it) },
                onFetchSiliconFlowModels = { viewModel.fetchSiliconFlowModels(it) },
                onFetchZaiModels = { viewModel.fetchZaiModels(it) },
                onFetchMoonshotModels = { viewModel.fetchMoonshotModels(it) },
                onFetchCohereModels = { viewModel.fetchCohereModels(it) },
                onFetchAi21Models = { viewModel.fetchAi21Models(it) },
                onFetchDashScopeModels = { viewModel.fetchDashScopeModels(it) },
                onFetchFireworksModels = { viewModel.fetchFireworksModels(it) },
                onFetchCerebrasModels = { viewModel.fetchCerebrasModels(it) },
                onFetchSambaNovaModels = { viewModel.fetchSambaNovaModels(it) },
                onFetchBaichuanModels = { viewModel.fetchBaichuanModels(it) },
                onFetchStepFunModels = { viewModel.fetchStepFunModels(it) },
                onFetchMiniMaxModels = { viewModel.fetchMiniMaxModels(it) },
                onFetchNvidiaModels = { viewModel.fetchNvidiaModels(it) },
                onFetchReplicateModels = { viewModel.fetchReplicateModels(it) },
                onFetchHuggingFaceInferenceModels = { viewModel.fetchHuggingFaceInferenceModels(it) },
                onFetchLambdaModels = { viewModel.fetchLambdaModels(it) },
                onFetchLeptonModels = { viewModel.fetchLeptonModels(it) },
                onFetchYiModels = { viewModel.fetchYiModels(it) },
                onFetchDoubaoModels = { viewModel.fetchDoubaoModels(it) },
                onFetchRekaModels = { viewModel.fetchRekaModels(it) },
                onFetchWriterModels = { viewModel.fetchWriterModels(it) },
                onNavigateToChatParams = { provider, model ->
                    navController.navigate(NavRoutes.aiChatParams(provider.name, model))
                },
                onNavigateToModelInfo = { provider, model ->
                    navController.navigate(NavRoutes.aiModelInfo(provider.name, model))
                }
            )
        }

        // API Test screen (developer mode)
        composable(NavRoutes.AI_API_TEST) {
            ApiTestScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToEditRequest = {
                    navController.navigate(NavRoutes.AI_API_TEST_EDIT)
                },
                viewModel = viewModel
            )
        }

        composable(NavRoutes.AI_API_TEST_EDIT) {
            EditApiRequestScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToTraceDetail = { filename ->
                    navController.navigate(NavRoutes.traceDetail(filename))
                }
            )
        }

        // Model Info screen
        composable(NavRoutes.AI_MODEL_INFO) { backStackEntry ->
            val providerName = backStackEntry.arguments?.getString("provider") ?: ""
            val model = java.net.URLDecoder.decode(
                backStackEntry.arguments?.getString("model") ?: "",
                "UTF-8"
            )
            val provider = try { com.ai.data.AiService.valueOf(providerName) } catch (e: Exception) { null }
            val uiState by viewModel.uiState.collectAsState()

            if (provider != null) {
                ModelInfoScreen(
                    provider = provider,
                    modelName = model,
                    openRouterApiKey = uiState.aiSettings.openRouterApiKey,
                    huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey,
                    aiSettings = uiState.aiSettings,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome
                )
            }
        }

        // AI Chats Hub
        composable(NavRoutes.AI_CHATS_HUB) {
            val uiState by viewModel.uiState.collectAsState()
            AiChatsHubScreen(
                aiSettings = uiState.aiSettings,
                developerMode = uiState.generalSettings.developerMode,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onNavigateToAgentSelect = { navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT) },
                onNavigateToNewChat = { navController.navigate(NavRoutes.AI_CHAT_PROVIDER) },
                onNavigateToChatHistory = { navController.navigate(NavRoutes.AI_CHAT_HISTORY) },
                onNavigateToChatSearch = { navController.navigate(NavRoutes.AI_CHAT_SEARCH) }
            )
        }

        // Chat agent select screen
        composable(NavRoutes.AI_CHAT_AGENT_SELECT) {
            val uiState by viewModel.uiState.collectAsState()
            ChatAgentSelectScreen(
                aiSettings = uiState.aiSettings,
                developerMode = uiState.generalSettings.developerMode,
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectAgent = { agentId ->
                    navController.navigate(NavRoutes.aiChatWithAgent(agentId))
                }
            )
        }

        // Chat with agent screen
        composable(NavRoutes.AI_CHAT_WITH_AGENT) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            val uiState by viewModel.uiState.collectAsState()
            val agent = remember(agentId, uiState.aiSettings.agents) {
                uiState.aiSettings.agents.find { it.id == agentId }
            }

            if (agent != null) {
                // Resolve agent parameter presets and convert to chat parameters
                val resolvedParams = uiState.aiSettings.resolveAgentParameters(agent)
                val chatParams = ChatParameters(
                    temperature = resolvedParams.temperature,
                    maxTokens = resolvedParams.maxTokens,
                    topP = resolvedParams.topP,
                    topK = resolvedParams.topK,
                    frequencyPenalty = resolvedParams.frequencyPenalty,
                    presencePenalty = resolvedParams.presencePenalty,
                    systemPrompt = resolvedParams.systemPrompt ?: "",
                    searchEnabled = resolvedParams.searchEnabled,
                    returnCitations = resolvedParams.returnCitations,
                    searchRecency = resolvedParams.searchRecency
                )

                // Get the effective endpoint URL, API key, and model for this agent
                val endpointUrl = uiState.aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val customBaseUrl = if (endpointUrl != agent.provider.baseUrl) endpointUrl else null
                val effectiveApiKey = uiState.aiSettings.getEffectiveApiKeyForAgent(agent)
                val effectiveModel = uiState.aiSettings.getEffectiveModelForAgent(agent)

                ChatSessionScreen(
                    provider = agent.provider,
                    model = effectiveModel,
                    apiKey = effectiveApiKey,
                    parameters = chatParams,
                    userName = uiState.generalSettings.userName,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome,
                    onSendMessage = { messages, _ ->
                        viewModel.sendChatMessage(agent.provider, effectiveApiKey, effectiveModel, messages)
                    },
                    onSendMessageStream = { messages ->
                        viewModel.sendChatMessageStream(agent.provider, effectiveApiKey, effectiveModel, messages, customBaseUrl)
                    },
                    onRecordStatistics = { inputTokens, outputTokens ->
                        viewModel.recordChatStatistics(agent.provider, effectiveModel, inputTokens, outputTokens)
                    }
                )
            } else {
                // Agent not found - navigate back
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        // Chat search screen
        composable(NavRoutes.AI_CHAT_SEARCH) {
            ChatSearchScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectSession = { sessionId ->
                    navController.navigate(NavRoutes.aiChatContinue(sessionId))
                }
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
                    com.ai.data.AiService.OPENAI -> uiState.availableChatGptModels
                    com.ai.data.AiService.ANTHROPIC -> uiState.availableClaudeModels
                    com.ai.data.AiService.GOOGLE -> uiState.availableGeminiModels
                    com.ai.data.AiService.XAI -> uiState.availableGrokModels
                    com.ai.data.AiService.GROQ -> uiState.availableGroqModels
                    com.ai.data.AiService.DEEPSEEK -> uiState.availableDeepSeekModels
                    com.ai.data.AiService.MISTRAL -> uiState.availableMistralModels
                    com.ai.data.AiService.TOGETHER -> uiState.availableTogetherModels
                    com.ai.data.AiService.OPENROUTER -> uiState.availableOpenRouterModels
                    com.ai.data.AiService.SILICONFLOW -> uiState.availableSiliconFlowModels
                    com.ai.data.AiService.ZAI -> uiState.availableZaiModels
                    com.ai.data.AiService.MOONSHOT -> uiState.availableMoonshotModels
                    com.ai.data.AiService.COHERE -> uiState.availableCohereModels
                    com.ai.data.AiService.AI21 -> uiState.availableAi21Models
                    com.ai.data.AiService.DASHSCOPE -> uiState.availableDashScopeModels
                    com.ai.data.AiService.FIREWORKS -> uiState.availableFireworksModels
                    com.ai.data.AiService.CEREBRAS -> uiState.availableCerebrasModels
                    com.ai.data.AiService.SAMBANOVA -> uiState.availableSambaNovaModels
                    com.ai.data.AiService.BAICHUAN -> uiState.availableBaichuanModels
                    com.ai.data.AiService.STEPFUN -> uiState.availableStepFunModels
                    com.ai.data.AiService.MINIMAX -> uiState.availableMiniMaxModels
                    com.ai.data.AiService.NVIDIA -> uiState.availableNvidiaModels
                    com.ai.data.AiService.REPLICATE -> uiState.availableReplicateModels
                    com.ai.data.AiService.HUGGINGFACE -> uiState.availableHuggingFaceInferenceModels
                    com.ai.data.AiService.LAMBDA -> uiState.availableLambdaModels
                    com.ai.data.AiService.LEPTON -> uiState.availableLeptonModels
                    com.ai.data.AiService.YI -> uiState.availableYiModels
                    com.ai.data.AiService.DOUBAO -> uiState.availableDoubaoModels
                    com.ai.data.AiService.REKA -> uiState.availableRekaModels
                    com.ai.data.AiService.WRITER -> uiState.availableWriterModels
                    else -> emptyList()
                }
                val isLoadingModels = when (provider) {
                    com.ai.data.AiService.OPENAI -> uiState.isLoadingChatGptModels
                    com.ai.data.AiService.ANTHROPIC -> uiState.isLoadingClaudeModels
                    com.ai.data.AiService.GOOGLE -> uiState.isLoadingGeminiModels
                    com.ai.data.AiService.XAI -> uiState.isLoadingGrokModels
                    com.ai.data.AiService.GROQ -> uiState.isLoadingGroqModels
                    com.ai.data.AiService.DEEPSEEK -> uiState.isLoadingDeepSeekModels
                    com.ai.data.AiService.MISTRAL -> uiState.isLoadingMistralModels
                    com.ai.data.AiService.TOGETHER -> uiState.isLoadingTogetherModels
                    com.ai.data.AiService.OPENROUTER -> uiState.isLoadingOpenRouterModels
                    com.ai.data.AiService.SILICONFLOW -> uiState.isLoadingSiliconFlowModels
                    com.ai.data.AiService.ZAI -> uiState.isLoadingZaiModels
                    com.ai.data.AiService.MOONSHOT -> uiState.isLoadingMoonshotModels
                    com.ai.data.AiService.COHERE -> uiState.isLoadingCohereModels
                    com.ai.data.AiService.AI21 -> uiState.isLoadingAi21Models
                    com.ai.data.AiService.DASHSCOPE -> uiState.isLoadingDashScopeModels
                    com.ai.data.AiService.FIREWORKS -> uiState.isLoadingFireworksModels
                    com.ai.data.AiService.CEREBRAS -> uiState.isLoadingCerebrasModels
                    com.ai.data.AiService.SAMBANOVA -> uiState.isLoadingSambaNovaModels
                    com.ai.data.AiService.BAICHUAN -> uiState.isLoadingBaichuanModels
                    com.ai.data.AiService.STEPFUN -> uiState.isLoadingStepFunModels
                    com.ai.data.AiService.MINIMAX -> uiState.isLoadingMiniMaxModels
                    com.ai.data.AiService.NVIDIA -> uiState.isLoadingNvidiaModels
                    com.ai.data.AiService.REPLICATE -> uiState.isLoadingReplicateModels
                    com.ai.data.AiService.HUGGINGFACE -> uiState.isLoadingHuggingFaceInferenceModels
                    com.ai.data.AiService.LAMBDA -> uiState.isLoadingLambdaModels
                    com.ai.data.AiService.LEPTON -> uiState.isLoadingLeptonModels
                    com.ai.data.AiService.YI -> uiState.isLoadingYiModels
                    com.ai.data.AiService.DOUBAO -> uiState.isLoadingDoubaoModels
                    com.ai.data.AiService.REKA -> uiState.isLoadingRekaModels
                    com.ai.data.AiService.WRITER -> uiState.isLoadingWriterModels
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
                    com.ai.data.AiService.OPENAI -> uiState.aiSettings.chatGptApiKey
                    com.ai.data.AiService.ANTHROPIC -> uiState.aiSettings.claudeApiKey
                    com.ai.data.AiService.GOOGLE -> uiState.aiSettings.geminiApiKey
                    com.ai.data.AiService.XAI -> uiState.aiSettings.grokApiKey
                    com.ai.data.AiService.GROQ -> uiState.aiSettings.groqApiKey
                    com.ai.data.AiService.DEEPSEEK -> uiState.aiSettings.deepSeekApiKey
                    com.ai.data.AiService.MISTRAL -> uiState.aiSettings.mistralApiKey
                    com.ai.data.AiService.PERPLEXITY -> uiState.aiSettings.perplexityApiKey
                    com.ai.data.AiService.TOGETHER -> uiState.aiSettings.togetherApiKey
                    com.ai.data.AiService.OPENROUTER -> uiState.aiSettings.openRouterApiKey
                    com.ai.data.AiService.SILICONFLOW -> uiState.aiSettings.siliconFlowApiKey
                    com.ai.data.AiService.ZAI -> uiState.aiSettings.zaiApiKey
                    com.ai.data.AiService.MOONSHOT -> uiState.aiSettings.moonshotApiKey
                    com.ai.data.AiService.COHERE -> uiState.aiSettings.cohereApiKey
                    com.ai.data.AiService.AI21 -> uiState.aiSettings.ai21ApiKey
                    com.ai.data.AiService.DASHSCOPE -> uiState.aiSettings.dashScopeApiKey
                    com.ai.data.AiService.FIREWORKS -> uiState.aiSettings.fireworksApiKey
                    com.ai.data.AiService.CEREBRAS -> uiState.aiSettings.cerebrasApiKey
                    com.ai.data.AiService.SAMBANOVA -> uiState.aiSettings.sambaNovaApiKey
                    com.ai.data.AiService.BAICHUAN -> uiState.aiSettings.baichuanApiKey
                    com.ai.data.AiService.STEPFUN -> uiState.aiSettings.stepFunApiKey
                    com.ai.data.AiService.MINIMAX -> uiState.aiSettings.miniMaxApiKey
                    com.ai.data.AiService.NVIDIA -> uiState.aiSettings.nvidiaApiKey
                    com.ai.data.AiService.REPLICATE -> uiState.aiSettings.replicateApiKey
                    com.ai.data.AiService.HUGGINGFACE -> uiState.aiSettings.huggingFaceInferenceApiKey
                    com.ai.data.AiService.LAMBDA -> uiState.aiSettings.lambdaApiKey
                    com.ai.data.AiService.LEPTON -> uiState.aiSettings.leptonApiKey
                    com.ai.data.AiService.YI -> uiState.aiSettings.yiApiKey
                    com.ai.data.AiService.DOUBAO -> uiState.aiSettings.doubaoApiKey
                    com.ai.data.AiService.REKA -> uiState.aiSettings.rekaApiKey
                    com.ai.data.AiService.WRITER -> uiState.aiSettings.writerApiKey
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
                    },
                    onSendMessageStream = { messages ->
                        viewModel.sendChatMessageStream(provider, apiKey, model, messages)
                    },
                    onRecordStatistics = { inputTokens, outputTokens ->
                        viewModel.recordChatStatistics(provider, model, inputTokens, outputTokens)
                    }
                )
            }
        }

        // Chat history screen
        composable(NavRoutes.AI_CHAT_HISTORY) {
            ChatHistoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectSession = { sessionId ->
                    navController.navigate(NavRoutes.aiChatContinue(sessionId))
                }
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
                    },
                    onSendMessageStream = { messages ->
                        viewModel.sendChatMessageStream(session.provider, apiKey, session.model, messages)
                    },
                    onRecordStatistics = { inputTokens, outputTokens ->
                        viewModel.recordChatStatistics(session.provider, session.model, inputTokens, outputTokens)
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
    onNavigateToCostConfig: () -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN
) {
    val uiState by viewModel.uiState.collectAsState()

    SettingsScreen(
        generalSettings = uiState.generalSettings,
        aiSettings = uiState.aiSettings,
        availableChatGptModels = uiState.availableChatGptModels,
        isLoadingChatGptModels = uiState.isLoadingChatGptModels,
        availableClaudeModels = uiState.availableClaudeModels,
        isLoadingClaudeModels = uiState.isLoadingClaudeModels,
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
        availableSiliconFlowModels = uiState.availableSiliconFlowModels,
        isLoadingSiliconFlowModels = uiState.isLoadingSiliconFlowModels,
        availableZaiModels = uiState.availableZaiModels,
        isLoadingZaiModels = uiState.isLoadingZaiModels,
        availableMoonshotModels = uiState.availableMoonshotModels,
        isLoadingMoonshotModels = uiState.isLoadingMoonshotModels,
        availableCohereModels = uiState.availableCohereModels,
        isLoadingCohereModels = uiState.isLoadingCohereModels,
        availableAi21Models = uiState.availableAi21Models,
        isLoadingAi21Models = uiState.isLoadingAi21Models,
        availableDashScopeModels = uiState.availableDashScopeModels,
        isLoadingDashScopeModels = uiState.isLoadingDashScopeModels,
        availableFireworksModels = uiState.availableFireworksModels,
        isLoadingFireworksModels = uiState.isLoadingFireworksModels,
        availableCerebrasModels = uiState.availableCerebrasModels,
        isLoadingCerebrasModels = uiState.isLoadingCerebrasModels,
        availableSambaNovaModels = uiState.availableSambaNovaModels,
        isLoadingSambaNovaModels = uiState.isLoadingSambaNovaModels,
        availableBaichuanModels = uiState.availableBaichuanModels,
        isLoadingBaichuanModels = uiState.isLoadingBaichuanModels,
        availableStepFunModels = uiState.availableStepFunModels,
        isLoadingStepFunModels = uiState.isLoadingStepFunModels,
        availableMiniMaxModels = uiState.availableMiniMaxModels,
        isLoadingMiniMaxModels = uiState.isLoadingMiniMaxModels,
        availableNvidiaModels = uiState.availableNvidiaModels,
        isLoadingNvidiaModels = uiState.isLoadingNvidiaModels,
        availableReplicateModels = uiState.availableReplicateModels,
        isLoadingReplicateModels = uiState.isLoadingReplicateModels,
        availableHuggingFaceInferenceModels = uiState.availableHuggingFaceInferenceModels,
        isLoadingHuggingFaceInferenceModels = uiState.isLoadingHuggingFaceInferenceModels,
        availableLambdaModels = uiState.availableLambdaModels,
        isLoadingLambdaModels = uiState.isLoadingLambdaModels,
        availableLeptonModels = uiState.availableLeptonModels,
        isLoadingLeptonModels = uiState.isLoadingLeptonModels,
        availableYiModels = uiState.availableYiModels,
        isLoadingYiModels = uiState.isLoadingYiModels,
        availableDoubaoModels = uiState.availableDoubaoModels,
        isLoadingDoubaoModels = uiState.isLoadingDoubaoModels,
        availableRekaModels = uiState.availableRekaModels,
        isLoadingRekaModels = uiState.isLoadingRekaModels,
        availableWriterModels = uiState.availableWriterModels,
        isLoadingWriterModels = uiState.isLoadingWriterModels,
        onBack = onNavigateBack,
        onNavigateHome = onNavigateHome,
        onSaveGeneral = { viewModel.updateGeneralSettings(it) },
        onTrackApiCallsChanged = { viewModel.updateTrackApiCalls(it) },
        onSaveAi = { viewModel.updateAiSettings(it) },
        onFetchChatGptModels = { viewModel.fetchChatGptModels(it) },
        onFetchClaudeModels = { viewModel.fetchClaudeModels(it) },
        onFetchGeminiModels = { viewModel.fetchGeminiModels(it) },
        onFetchGrokModels = { viewModel.fetchGrokModels(it) },
        onFetchGroqModels = { viewModel.fetchGroqModels(it) },
        onFetchDeepSeekModels = { viewModel.fetchDeepSeekModels(it) },
        onFetchMistralModels = { viewModel.fetchMistralModels(it) },
        onFetchPerplexityModels = { viewModel.fetchPerplexityModels(it) },
        onFetchTogetherModels = { viewModel.fetchTogetherModels(it) },
        onFetchOpenRouterModels = { viewModel.fetchOpenRouterModels(it) },
        onFetchSiliconFlowModels = { viewModel.fetchSiliconFlowModels(it) },
        onFetchZaiModels = { viewModel.fetchZaiModels(it) },
        onFetchMoonshotModels = { viewModel.fetchMoonshotModels(it) },
        onFetchCohereModels = { viewModel.fetchCohereModels(it) },
        onFetchAi21Models = { viewModel.fetchAi21Models(it) },
        onFetchDashScopeModels = { viewModel.fetchDashScopeModels(it) },
        onFetchFireworksModels = { viewModel.fetchFireworksModels(it) },
        onFetchCerebrasModels = { viewModel.fetchCerebrasModels(it) },
        onFetchSambaNovaModels = { viewModel.fetchSambaNovaModels(it) },
        onFetchBaichuanModels = { viewModel.fetchBaichuanModels(it) },
        onFetchStepFunModels = { viewModel.fetchStepFunModels(it) },
        onFetchMiniMaxModels = { viewModel.fetchMiniMaxModels(it) },
        onFetchNvidiaModels = { viewModel.fetchNvidiaModels(it) },
        onFetchReplicateModels = { viewModel.fetchReplicateModels(it) },
        onFetchHuggingFaceInferenceModels = { viewModel.fetchHuggingFaceInferenceModels(it) },
        onFetchLambdaModels = { viewModel.fetchLambdaModels(it) },
        onFetchLeptonModels = { viewModel.fetchLeptonModels(it) },
        onFetchYiModels = { viewModel.fetchYiModels(it) },
        onFetchDoubaoModels = { viewModel.fetchDoubaoModels(it) },
        onFetchRekaModels = { viewModel.fetchRekaModels(it) },
        onFetchWriterModels = { viewModel.fetchWriterModels(it) },
        onTestAiModel = { service, apiKey, model -> viewModel.testAiModel(service, apiKey, model) },
        onProviderStateChange = { service, state -> viewModel.updateProviderState(service, state) },
        onRefreshAllModels = { settings, forceRefresh, onProgress -> viewModel.refreshAllModelLists(settings, forceRefresh, onProgress) },
        onSaveHuggingFaceApiKey = { key ->
            viewModel.updateGeneralSettings(uiState.generalSettings.copy(huggingFaceApiKey = key))
        },
        onSaveOpenRouterApiKey = { key ->
            viewModel.updateGeneralSettings(uiState.generalSettings.copy(openRouterApiKey = key))
        },
        onNavigateToCostConfig = onNavigateToCostConfig,
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
    onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}
) {
    SettingsScreenNav(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onNavigateHome = onNavigateHome,
        onNavigateToCostConfig = onNavigateToCostConfig,
        initialSubScreen = SettingsSubScreen.AI_SETUP
    )
}

/**
 * Wrapper for SettingsScreen that starts at AI Settings (Prompts, Costs).
 */
@Composable
fun AiAiSettingsScreenNav(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}
) {
    SettingsScreenNav(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        onNavigateHome = onNavigateHome,
        onNavigateToCostConfig = onNavigateToCostConfig,
        initialSubScreen = SettingsSubScreen.AI_AI_SETTINGS
    )
}

/**
 * Wrapper for HousekeepingScreen.
 */
@Composable
fun HousekeepingScreenNav(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    HousekeepingScreen(
        aiSettings = uiState.aiSettings,
        huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey,
        openRouterApiKey = uiState.generalSettings.openRouterApiKey,
        developerMode = uiState.generalSettings.developerMode,
        availableChatGptModels = uiState.availableChatGptModels,
        availableClaudeModels = uiState.availableClaudeModels,
        availableGeminiModels = uiState.availableGeminiModels,
        availableGrokModels = uiState.availableGrokModels,
        availableGroqModels = uiState.availableGroqModels,
        availableDeepSeekModels = uiState.availableDeepSeekModels,
        availableMistralModels = uiState.availableMistralModels,
        availablePerplexityModels = uiState.availablePerplexityModels,
        availableTogetherModels = uiState.availableTogetherModels,
        availableOpenRouterModels = uiState.availableOpenRouterModels,
        availableSiliconFlowModels = uiState.availableSiliconFlowModels,
        availableZaiModels = uiState.availableZaiModels,
        availableMoonshotModels = uiState.availableMoonshotModels,
        availableCohereModels = uiState.availableCohereModels,
        availableAi21Models = uiState.availableAi21Models,
        availableDashScopeModels = uiState.availableDashScopeModels,
        availableFireworksModels = uiState.availableFireworksModels,
        availableCerebrasModels = uiState.availableCerebrasModels,
        availableSambaNovaModels = uiState.availableSambaNovaModels,
        availableBaichuanModels = uiState.availableBaichuanModels,
        availableStepFunModels = uiState.availableStepFunModels,
        availableMiniMaxModels = uiState.availableMiniMaxModels,
        availableNvidiaModels = uiState.availableNvidiaModels,
        availableReplicateModels = uiState.availableReplicateModels,
        availableHuggingFaceInferenceModels = uiState.availableHuggingFaceInferenceModels,
        availableLambdaModels = uiState.availableLambdaModels,
        availableLeptonModels = uiState.availableLeptonModels,
        availableYiModels = uiState.availableYiModels,
        availableDoubaoModels = uiState.availableDoubaoModels,
        availableRekaModels = uiState.availableRekaModels,
        availableWriterModels = uiState.availableWriterModels,
        onBackToHome = onNavigateHome,
        onSave = { settings -> viewModel.updateAiSettings(settings) },
        onRefreshAllModels = { settings, forceRefresh, onProgress -> viewModel.refreshAllModelLists(settings, forceRefresh, onProgress) },
        onTestApiKey = { service, apiKey, model -> viewModel.testAiModel(service, apiKey, model) },
        onSaveHuggingFaceApiKey = { key ->
            viewModel.updateGeneralSettings(uiState.generalSettings.copy(huggingFaceApiKey = key))
        },
        onSaveOpenRouterApiKey = { key ->
            viewModel.updateGeneralSettings(uiState.generalSettings.copy(openRouterApiKey = key))
        },
        onProviderStateChange = { service, state -> viewModel.updateProviderState(service, state) }
    )
}
