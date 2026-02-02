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
            val marker = "-- end prompt --"
            if (externalPrompt.contains(marker)) {
                // Parse marker: prompt above, instructions below
                val parts = externalPrompt.split(marker, limit = 2)
                val aiPrompt = parts[0].trim()
                val instructions = parts.getOrElse(1) { "" }

                // Extract instruction tags
                fun extractTag(tag: String, text: String): String? {
                    val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                    return regex.find(text)?.groupValues?.get(1)?.trim()
                }
                fun extractAllTags(tag: String, text: String): List<String> {
                    val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                    return regex.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                }
                val openHtml = extractTag("open", instructions)
                val closeHtml = extractTag("close", instructions)
                val reportType = extractTag("type", instructions)
                val email = extractTag("email", instructions)
                val nextAction = extractTag("next", instructions)
                val hasReturn = Regex("<return>", RegexOption.IGNORE_CASE).containsMatchIn(instructions)

                // Extract API selection tags (can appear multiple times)
                val agentNames = extractAllTags("agent", instructions)
                val flockNames = extractAllTags("flock", instructions)
                val swarmNames = extractAllTags("swarm", instructions)
                val modelSpecs = extractAllTags("model", instructions)

                // Wrap openHtml as <user> tag so existing ViewModel parsing handles it
                val fullPrompt = if (openHtml != null) "$aiPrompt\n<user>$openHtml</user>" else aiPrompt

                // Store instructions in ViewModel and set up for report generation
                viewModel.setExternalInstructions(closeHtml, reportType, email, nextAction, hasReturn, agentNames, flockNames, swarmNames, modelSpecs)
                viewModel.showGenericAiAgentSelection(externalTitle ?: "", fullPrompt)

                // Navigate directly to reports screen (skip New Report)
                navController.navigate(NavRoutes.AI_REPORTS) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
            } else {
                // No marker - current behavior: navigate to New Report screen
                navController.navigate(NavRoutes.aiNewReportWithParams(externalTitle ?: "", externalPrompt)) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
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
                openRouterApiKey = com.ai.data.AiService.findById("OPENROUTER")?.let { uiState.aiSettings.getApiKey(it) } ?: "",
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
                loadingModelsFor = uiState.loadingModelsFor,
                onBackToAiSetup = { navController.popBackStack() },
                onBackToHome = navigateHome,
                onSaveAiSettings = { viewModel.updateAiSettings(it) },
                onTestAiModel = { service, apiKey, model -> viewModel.testAiModel(service, apiKey, model) },
                onFetchModels = viewModel::fetchModels,
                onNavigateToChatParams = { provider, model ->
                    navController.navigate(NavRoutes.aiChatParams(provider.id, model))
                },
                onNavigateToModelInfo = { provider, model ->
                    navController.navigate(NavRoutes.aiModelInfo(provider.id, model))
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
            val provider = com.ai.data.AiService.findById(providerName)
            val uiState by viewModel.uiState.collectAsState()

            if (provider != null) {
                ModelInfoScreen(
                    provider = provider,
                    modelName = model,
                    openRouterApiKey = com.ai.data.AiService.findById("OPENROUTER")?.let { uiState.aiSettings.getApiKey(it) } ?: "",
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
            SelectAgentScreen(
                aiSettings = uiState.aiSettings,
                onSelectAgent = { agent ->
                    navController.navigate(NavRoutes.aiChatWithAgent(agent.id))
                },
                onBack = { navController.popBackStack() },
                onNavigateHome = navigateHome
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateHome = navigateHome,
                onSelectProvider = { provider ->
                    navController.navigate(NavRoutes.aiChatModel(provider.id))
                }
            )
        }

        composable(NavRoutes.AI_CHAT_MODEL) { backStackEntry ->
            val providerName = backStackEntry.arguments?.getString("provider") ?: ""
            val provider = com.ai.data.AiService.findById(providerName)
            val uiState by viewModel.uiState.collectAsState()

            if (provider != null) {
                SelectModelScreen(
                    provider = provider,
                    aiSettings = uiState.aiSettings,
                    currentModel = "",
                    onSelectModel = { model ->
                        navController.navigate(NavRoutes.aiChatParams(provider.id, model))
                    },
                    onBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome
                )
            }
        }

        composable(NavRoutes.AI_CHAT_PARAMS) { backStackEntry ->
            val providerName = backStackEntry.arguments?.getString("provider") ?: ""
            val encodedModel = backStackEntry.arguments?.getString("model") ?: ""
            val model = try { java.net.URLDecoder.decode(encodedModel, "UTF-8") } catch (e: Exception) { encodedModel }
            val provider = com.ai.data.AiService.findById(providerName)

            if (provider != null) {
                ChatParametersScreen(
                    provider = provider,
                    model = model,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateHome = navigateHome,
                    onStartChat = { params ->
                        // Store params in ViewModel and navigate to session
                        viewModel.setChatParameters(params)
                        navController.navigate(NavRoutes.aiChatSession(provider.id, model))
                    }
                )
            }
        }

        composable(NavRoutes.AI_CHAT_SESSION) { backStackEntry ->
            val providerName = backStackEntry.arguments?.getString("provider") ?: ""
            val encodedModel = backStackEntry.arguments?.getString("model") ?: ""
            val model = try { java.net.URLDecoder.decode(encodedModel, "UTF-8") } catch (e: Exception) { encodedModel }
            val provider = com.ai.data.AiService.findById(providerName)
            val uiState by viewModel.uiState.collectAsState()

            if (provider != null) {
                val apiKey = uiState.aiSettings.getApiKey(provider)

                ChatSessionScreen(
                    provider = provider,
                    model = model,
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
        loadingModelsFor = uiState.loadingModelsFor,
        onFetchModels = viewModel::fetchModels,
        onBack = onNavigateBack,
        onNavigateHome = onNavigateHome,
        onSaveGeneral = { viewModel.updateGeneralSettings(it) },
        onTrackApiCallsChanged = { viewModel.updateTrackApiCalls(it) },
        onSaveAi = { viewModel.updateAiSettings(it) },
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
