package com.ai.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.data.*
import com.ai.model.*
import com.ai.viewmodel.*
import com.ai.ui.chat.*
import com.ai.ui.hub.*
import com.ai.ui.report.*
import com.ai.ui.history.*
import com.ai.ui.models.*
import com.ai.ui.settings.*
import com.ai.ui.admin.*
import com.ai.ui.shared.*

/**
 * Main navigation host for the app.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = viewModel(),
    reportViewModel: ReportViewModel = remember { ReportViewModel(appViewModel) },
    chatViewModel: ChatViewModel = remember { ChatViewModel(appViewModel) },
    externalTitle: String? = null,
    externalSystem: String? = null,
    externalPrompt: String? = null,
    externalInstructions: String? = null,
    onExternalIntentHandled: () -> Unit = {}
) {
    val safePopBack: () -> Unit = {
        if (navController.previousBackStackEntry != null) navController.popBackStack()
    }
    val navigateHome: () -> Unit = {
        navController.navigate(NavRoutes.AI) { popUpTo(NavRoutes.AI) { inclusive = true } }
    }

    // Handle external intent
    LaunchedEffect(externalPrompt) {
        if (externalPrompt != null) {
            fun extractTag(tag: String, text: String): String? =
                Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
            fun extractAllTags(tag: String, text: String): List<String> =
                Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

            val marker = "-- end prompt --"
            val aiPrompt: String
            val instructions: String

            if (externalInstructions != null) {
                aiPrompt = externalPrompt.trim(); instructions = externalInstructions
            } else if (externalPrompt.contains(marker)) {
                val parts = externalPrompt.split(marker, limit = 2)
                aiPrompt = parts[0].trim(); instructions = parts.getOrElse(1) { "" }
            } else {
                navController.navigate(NavRoutes.aiNewReportWithParams(externalTitle ?: "", externalPrompt)) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
                onExternalIntentHandled(); return@LaunchedEffect
            }

            val openHtml = extractTag("open", instructions)
            val closeHtml = extractTag("close", instructions)
            val reportType = extractTag("type", instructions)
            val email = extractTag("email", instructions)
            val nextAction = extractTag("next", instructions)
            val hasReturn = Regex("<return>", RegexOption.IGNORE_CASE).containsMatchIn(instructions)
            val hasEdit = Regex("<edit>", RegexOption.IGNORE_CASE).containsMatchIn(instructions)
            val hasSelect = Regex("<select>", RegexOption.IGNORE_CASE).containsMatchIn(instructions)

            val agentNames = extractAllTags("agent", instructions)
            val flockNames = extractAllTags("flock", instructions)
            val swarmNames = extractAllTags("swarm", instructions)
            val modelSpecs = extractAllTags("model", instructions)

            appViewModel.setExternalInstructions(closeHtml, reportType, email, nextAction, hasReturn, agentNames, flockNames, swarmNames, modelSpecs, hasEdit, hasSelect, openHtml, systemPrompt = externalSystem)

            if (hasEdit) {
                navController.navigate(NavRoutes.aiNewReportWithParams(externalTitle ?: "", aiPrompt)) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
            } else {
                val fullPrompt = if (openHtml != null) "$aiPrompt\n<user>$openHtml</user>" else aiPrompt
                reportViewModel.showGenericAgentSelection(externalTitle ?: "", fullPrompt)
                navController.navigate(NavRoutes.AI_REPORTS) { popUpTo(NavRoutes.AI) { inclusive = false } }
            }
            onExternalIntentHandled()
        }
    }

    NavHost(navController = navController, startDestination = NavRoutes.AI, modifier = modifier) {

        // ===== Hub =====
        composable(NavRoutes.AI) {
            HubScreen(
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onNavigateToTraces = { navController.navigate(NavRoutes.TRACE_LIST) },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
                onNavigateToReportsHub = { navController.navigate(NavRoutes.AI_REPORTS_HUB) },
                onNavigateToUsage = { navController.navigate(NavRoutes.AI_USAGE) },
                onNavigateToChatsHub = { navController.navigate(NavRoutes.AI_CHATS_HUB) },
                onNavigateToAiSetup = { navController.navigate(NavRoutes.AI_SETUP) },
                onNavigateToHousekeeping = { navController.navigate(NavRoutes.AI_HOUSEKEEPING) },
                onNavigateToModelSearch = { navController.navigate(NavRoutes.AI_MODEL_SEARCH) },
                viewModel = appViewModel
            )
        }

        // ===== Settings =====
        composable(NavRoutes.SETTINGS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) })
        }
        composable(NavRoutes.AI_SETUP) {
            SetupScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) })
        }

        // ===== Reports =====
        composable(NavRoutes.AI_REPORTS_HUB) {
            ReportsHubScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToNewReport = { navController.navigate(NavRoutes.AI_NEW_REPORT) },
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) },
                onNavigateToHistory = { navController.navigate(NavRoutes.AI_HISTORY) })
        }
        composable(NavRoutes.AI_NEW_REPORT) {
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.AI_REPORTS) })
        }
        composable(NavRoutes.AI_NEW_REPORT_WITH_PARAMS) { entry ->
            val title = try { java.net.URLDecoder.decode(entry.arguments?.getString("title") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val prompt = try { java.net.URLDecoder.decode(entry.arguments?.getString("prompt") ?: "", "UTF-8") } catch (_: Exception) { "" }
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                initialTitle = title, initialPrompt = prompt)
        }
        composable(NavRoutes.AI_REPORTS) {
            ReportsScreenNav(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTrace = { navController.navigate(NavRoutes.traceListForReport(it)) })
        }
        composable(NavRoutes.AI_PROMPT_HISTORY) {
            PromptHistoryScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectEntry = { navController.navigate(NavRoutes.aiNewReportWithParams(it.title, it.prompt)) })
        }

        // ===== History =====
        composable(NavRoutes.AI_HISTORY) {
            HistoryScreenNav(onNavigateBack = safePopBack, onNavigateHome = navigateHome)
        }

        // ===== Usage =====
        composable(NavRoutes.AI_USAGE) {
            val uiState by appViewModel.uiState.collectAsState()
            UsageScreen(
                openRouterApiKey = AppService.findById("OPENROUTER")?.let { uiState.aiSettings.getApiKey(it) } ?: "",
                onBack = safePopBack, onNavigateHome = navigateHome)
        }
        composable(NavRoutes.AI_COST_CONFIG) {
            val uiState by appViewModel.uiState.collectAsState()
            CostConfigurationScreen(aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome)
        }

        // ===== Models =====
        composable(NavRoutes.AI_MODEL_SEARCH) {
            val uiState by appViewModel.uiState.collectAsState()
            ModelSearchScreen(aiSettings = uiState.aiSettings,
                loadingModelsFor = uiState.loadingModelsFor, onBackToAiSetup = safePopBack, onBackToHome = navigateHome,
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) })
        }
        composable(NavRoutes.AI_MODEL_INFO) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                ModelInfoScreen(provider = provider, modelName = model,
                    openRouterApiKey = AppService.findById("OPENROUTER")?.let { uiState.aiSettings.getApiKey(it) } ?: "",
                    huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey, aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    onSaveSettings = { appViewModel.updateSettings(it) },
                    onTestAiModel = { s, k, m -> appViewModel.testAiModel(s, k, m) },
                    onFetchModels = appViewModel::fetchModels,
                    onStartChat = { p, m -> navController.navigate(NavRoutes.aiChatParams(p.id, m)) },
                    onNavigateToTracesForModel = { p, m -> navController.navigate(NavRoutes.traceListForModel(p.id, m)) },
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome)
            }
        }

        // ===== Chat =====
        composable(NavRoutes.AI_CHATS_HUB) {
            val uiState by appViewModel.uiState.collectAsState()
            ChatsHubScreen(aiSettings = uiState.aiSettings, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToAgentSelect = { navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT) },
                onNavigateToNewChat = { navController.navigate(NavRoutes.AI_CHAT_PROVIDER) },
                onNavigateToChatHistory = { navController.navigate(NavRoutes.AI_CHAT_HISTORY) },
                onNavigateToChatSearch = { navController.navigate(NavRoutes.AI_CHAT_SEARCH) },
                onNavigateToDualChat = { navController.navigate(NavRoutes.AI_DUAL_CHAT_SETUP) })
        }
        composable(NavRoutes.AI_CHAT_AGENT_SELECT) {
            val uiState by appViewModel.uiState.collectAsState()
            SelectAgentScreen(aiSettings = uiState.aiSettings,
                onSelectAgent = { navController.navigate(NavRoutes.aiChatWithAgent(it.id)) },
                onBack = safePopBack, onNavigateHome = navigateHome)
        }
        composable(NavRoutes.AI_CHAT_WITH_AGENT) { entry ->
            val agentId = entry.arguments?.getString("agentId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            val agent = remember(agentId, uiState.aiSettings.agents) { uiState.aiSettings.agents.find { it.id == agentId } }

            if (agent != null) {
                val resolvedParams = uiState.aiSettings.resolveAgentParameters(agent)
                val chatParams = ChatParameters(
                    temperature = resolvedParams.temperature, maxTokens = resolvedParams.maxTokens,
                    topP = resolvedParams.topP, topK = resolvedParams.topK,
                    frequencyPenalty = resolvedParams.frequencyPenalty, presencePenalty = resolvedParams.presencePenalty,
                    systemPrompt = resolvedParams.systemPrompt ?: "", searchEnabled = resolvedParams.searchEnabled,
                    returnCitations = resolvedParams.returnCitations, searchRecency = resolvedParams.searchRecency
                )
                val endpointUrl = uiState.aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val customBaseUrl = if (endpointUrl != agent.provider.baseUrl) endpointUrl else null
                val effectiveApiKey = uiState.aiSettings.getEffectiveApiKeyForAgent(agent)
                val effectiveModel = uiState.aiSettings.getEffectiveModelForAgent(agent)

                ChatSessionScreen(
                    provider = agent.provider, model = effectiveModel, parameters = chatParams,
                    userName = uiState.generalSettings.userName, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onSendMessageStream = { messages -> chatViewModel.sendChatMessageStream(agent.provider, effectiveApiKey, effectiveModel, messages, customBaseUrl) },
                    onRecordStatistics = { inp, out -> chatViewModel.recordChatStatistics(agent.provider, effectiveModel, inp, out) }
                )
            } else {
                LaunchedEffect(Unit) { safePopBack() }
            }
        }
        composable(NavRoutes.AI_CHAT_SEARCH) {
            ChatSearchScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectSession = { navController.navigate(NavRoutes.aiChatContinue(it)) })
        }
        composable(NavRoutes.AI_CHAT_PROVIDER) {
            val uiState by appViewModel.uiState.collectAsState()
            ChatSelectProviderScreen(aiSettings = uiState.aiSettings, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectProvider = { navController.navigate(NavRoutes.aiChatModel(it.id)) })
        }
        composable(NavRoutes.AI_CHAT_MODEL) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                SelectModelScreen(provider = provider, aiSettings = uiState.aiSettings, currentModel = "",
                    onSelectModel = { navController.navigate(NavRoutes.aiChatParams(provider.id, it)) },
                    onBack = safePopBack, onNavigateHome = navigateHome)
            }
        }
        composable(NavRoutes.AI_CHAT_PARAMS) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                ChatParametersScreen(provider = provider, model = model, aiSettings = uiState.aiSettings,
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onStartChat = { params -> appViewModel.setChatParameters(params); navController.navigate(NavRoutes.aiChatSession(provider.id, model)) })
            }
        }
        composable(NavRoutes.AI_CHAT_SESSION) { entry ->
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                val apiKey = uiState.aiSettings.getApiKey(provider)
                ChatSessionScreen(
                    provider = provider, model = model, parameters = uiState.chatParameters,
                    userName = uiState.generalSettings.userName, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onSendMessageStream = { messages -> chatViewModel.sendChatMessageStream(provider, apiKey, model, messages) },
                    onRecordStatistics = { inp, out -> chatViewModel.recordChatStatistics(provider, model, inp, out) }
                )
            }
        }
        composable(NavRoutes.AI_CHAT_HISTORY) {
            ChatHistoryScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectSession = { navController.navigate(NavRoutes.aiChatContinue(it)) })
        }
        composable(NavRoutes.AI_CHAT_CONTINUE) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            val session = remember(sessionId) { ChatHistoryManager.loadSession(sessionId) }
            if (session != null) {
                val apiKey = uiState.aiSettings.getApiKey(session.provider)
                ChatSessionScreen(
                    provider = session.provider, model = session.model, parameters = session.parameters,
                    userName = uiState.generalSettings.userName, initialMessages = session.messages, sessionId = session.id,
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onSendMessageStream = { messages -> chatViewModel.sendChatMessageStream(session.provider, apiKey, session.model, messages) },
                    onRecordStatistics = { inp, out -> chatViewModel.recordChatStatistics(session.provider, session.model, inp, out) }
                )
            } else {
                LaunchedEffect(Unit) { safePopBack() }
            }
        }

        // ===== Dual Chat =====
        composable(NavRoutes.AI_DUAL_CHAT_SETUP) {
            val uiState by appViewModel.uiState.collectAsState()
            DualChatSetupScreen(aiSettings = uiState.aiSettings, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onStartSession = { config -> appViewModel.setDualChatConfig(config); navController.navigate(NavRoutes.AI_DUAL_CHAT_SESSION) })
        }
        composable(NavRoutes.AI_DUAL_CHAT_SESSION) {
            val uiState by appViewModel.uiState.collectAsState()
            DualChatSessionScreen(appViewModel = appViewModel, chatViewModel = chatViewModel, aiSettings = uiState.aiSettings,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome)
        }

        // ===== Admin =====
        composable(NavRoutes.AI_HOUSEKEEPING) {
            HousekeepingScreenNav(viewModel = appViewModel, onNavigateHome = navigateHome)
        }
        composable(NavRoutes.HELP) {
            HelpScreen(onBack = safePopBack, onNavigateHome = navigateHome)
        }
        composable(NavRoutes.TRACE_LIST) {
            TraceListScreen(onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() })
        }
        composable(NavRoutes.TRACE_LIST_FOR_REPORT) { entry ->
            val reportId = entry.arguments?.getString("reportId") ?: ""
            TraceListScreen(onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, reportId = reportId)
        }
        composable(NavRoutes.TRACE_LIST_FOR_MODEL) { entry ->
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            TraceListScreen(onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, modelFilter = model)
        }
        composable(NavRoutes.TRACE_DETAIL) { entry ->
            val filename = entry.arguments?.getString("filename") ?: ""
            TraceDetailScreen(filename = filename, onBack = safePopBack, onNavigateHome = navigateHome,
                onEditRequest = { navController.navigate(NavRoutes.AI_API_TEST_EDIT) })
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
}

// ===== Navigation Wrappers =====

@Composable
fun SettingsScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN
) {
    val uiState by viewModel.uiState.collectAsState()
    SettingsScreen(
        generalSettings = uiState.generalSettings, aiSettings = uiState.aiSettings,
        loadingModelsFor = uiState.loadingModelsFor, onFetchModels = viewModel::fetchModels,
        onBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onSaveGeneral = { viewModel.updateGeneralSettings(it) }, onSaveAi = { viewModel.updateSettings(it) },
        onTestAiModel = { s, k, m -> viewModel.testAiModel(s, k, m) },
        onProviderStateChange = { s, st -> viewModel.updateProviderState(s, st) },
        onRefreshAllModels = { settings, force, progress -> viewModel.refreshAllModelLists(settings, force, progress) },
        onSaveHuggingFaceApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(huggingFaceApiKey = it)) },
        onSaveOpenRouterApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(openRouterApiKey = it)) },
        onNavigateToCostConfig = onNavigateToCostConfig,
        onTestModelWithPrompt = { s, k, m, p -> viewModel.testModelWithPrompt(s, k, m, p) },
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        initialSubScreen = initialSubScreen
    )
}

@Composable
fun SetupScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    SettingsScreenNav(viewModel = viewModel, onNavigateBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onNavigateToCostConfig = onNavigateToCostConfig, onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        initialSubScreen = SettingsSubScreen.AI_SETUP)
}

@Composable
fun HousekeepingScreenNav(viewModel: AppViewModel, onNavigateHome: () -> Unit) {
    HousekeepingScreen(onBackToHome = onNavigateHome)
}
