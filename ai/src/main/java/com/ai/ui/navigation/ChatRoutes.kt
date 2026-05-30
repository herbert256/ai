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

internal fun NavGraphBuilder.chatRoutes(
    navController: NavHostController,
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    chatViewModel: ChatViewModel,
    safePopBack: () -> Unit,
    navigateHome: () -> Unit
) {
        composable(NavRoutes.AI_CHATS_HUB) {
            val uiState by appViewModel.uiState.collectAsState()
            ChatsHubScreen(aiSettings = uiState.aiSettings, experimentalFeatures = uiState.generalSettings.experimentalFeaturesEnabled, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToAgentSelect = { navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT) },
                onNavigateToNewChat = { navController.navigate(NavRoutes.AI_CHAT_PROVIDER) },
                onNavigateToChatHistory = { navController.navigate(NavRoutes.AI_CHAT_HISTORY) },
                onNavigateToChatSearch = { navController.navigate(NavRoutes.AI_CHAT_SEARCH) },
                onResumeSession = { sessionId -> navController.navigate(NavRoutes.aiChatContinue(sessionId)) },
                onNavigateToManage = { navController.navigate(NavRoutes.AI_CHAT_MANAGE) },
                // Hub card "Chat with a local LLM" — same destination
                // as a configure-on-the-fly chat that picked the
                // synthetic LOCAL provider, so the standard
                // AI_CHAT_SESSION composable handles routing.
                onNavigateToLocalLlmChat = { model -> navController.navigate(NavRoutes.aiChatSession("LOCAL", model)) },
                onNavigateToDualChat = { navController.navigate(NavRoutes.AI_DUAL_CHAT_SETUP) },
                onStartWithPhoto = { mime, b64 ->
                    // Stage the photo for the chat session screen at
                    // the end of the configure-on-the-fly chain. The
                    // user picks provider / model / params normally;
                    // ChatSessionScreen seeds attachedImage from
                    // these UiState fields on first composition and
                    // clears them via onConsumeStarter.
                    appViewModel.updateUiState { it.copy(chatStarterImageBase64 = b64, chatStarterImageMime = mime) }
                    navController.navigate(NavRoutes.AI_CHAT_PROVIDER)
                })
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
                    returnCitations = resolvedParams.returnCitations, searchRecency = resolvedParams.searchRecency,
                    webSearchTool = resolvedParams.webSearchTool
                )
                val endpointUrl = uiState.aiSettings.getEffectiveEndpointUrlForAgent(agent)
                val customBaseUrl = if (endpointUrl != agent.provider.baseUrl) endpointUrl else null
                val effectiveApiKey = uiState.aiSettings.getEffectiveApiKeyForAgent(agent)
                val effectiveModel = uiState.aiSettings.getEffectiveModelForAgent(agent)
                val agentChatContext = LocalContext.current

                ChatSessionScreen(
                    provider = agent.provider, model = effectiveModel, parameters = chatParams,
                    userName = uiState.generalSettings.userName, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onSendMessageStream = { messages, webSearch, reasoning, kbs -> chatViewModel.sendChatMessageStream(agent.provider, effectiveApiKey, effectiveModel, messages, sessionParams = chatParams, baseUrl = customBaseUrl, webSearchTool = webSearch, reasoningEffort = reasoning, context = agentChatContext, knowledgeBaseIds = kbs) },
                    onRecordStatistics = { inp, out -> chatViewModel.recordChatStatistics(agent.provider, effectiveModel, inp, out) },
                    aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    isVisionCapable = uiState.aiSettings.isVisionCapable(agent.provider, effectiveModel),
                    onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                    initialUserInput = uiState.chatStarterText,
                    initialUserImageBase64 = uiState.chatStarterImageBase64,
                    initialUserImageMime = uiState.chatStarterImageMime,
                    onConsumeStarter = {
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = null,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                    },
                    experimentalFeatures = uiState.generalSettings.experimentalFeaturesEnabled
                )
            } else {
                LaunchedEffect(Unit) { safePopBack() }
            }
        }
        composable(NavRoutes.AI_CHAT_MANAGE) {
            com.ai.ui.chat.ChatManageScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_CHAT_SEARCH) {
            ChatSearchScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectSession = { navController.navigate(NavRoutes.aiChatContinue(it)) })
        }
        composable(NavRoutes.AI_CHAT_PROVIDER) {
            val uiState by appViewModel.uiState.collectAsState()
            // Configure-on-the-fly entry: same picker as the New
            // Report's +Model button. Row click jumps straight to
            // AI_CHAT_PARAMS with the chosen (provider, model).
            com.ai.ui.other.ReportSelectModelsScreen(
                aiSettings = uiState.aiSettings,
                titleText = "Pick model for chat",
                onConfirm = { (provider, model) ->
                    navController.navigate(NavRoutes.aiChatParams(provider.id, model))
                },
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
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
            val context = LocalContext.current
            val provider = AppService.findById(entry.arguments?.getString("provider") ?: "")
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            if (provider != null) {
                val apiKey = uiState.aiSettings.getApiKey(provider)
                val isLocal = provider.id == AppService.LOCAL.id
                ChatSessionScreen(
                    provider = provider, model = model, parameters = uiState.chatParameters,
                    userName = uiState.generalSettings.userName, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    // LOCAL routes through MediaPipe LLM Inference; the
                    // remote streaming protocol (web-search / reasoning
                    // params) doesn't apply, so we just forward the
                    // turn list to LocalLlm.generate via
                    // ChatViewModel.sendLocalLlmStream. The chat screen
                    // calls this with its current ChatSession.knowledgeBaseIds
                    // attached as the trailing argument (4th
                    // parameter) — see ChatScreens.kt for the wiring.
                    onSendMessageStream = if (isLocal) {
                        { messages, _, _, kbs -> chatViewModel.sendLocalLlmStream(context, model, messages, kbs) }
                    } else {
                        { messages, webSearch, reasoning, kbs ->
                            chatViewModel.sendChatMessageStream(provider, apiKey, model, messages,
                                sessionParams = uiState.chatParameters,
                                webSearchTool = webSearch, reasoningEffort = reasoning,
                                context = context, knowledgeBaseIds = kbs)
                        }
                    },
                    onRecordStatistics = if (isLocal) {
                        { _, _ -> /* no remote billing for local */ }
                    } else {
                        { inp, out -> chatViewModel.recordChatStatistics(provider, model, inp, out) }
                    },
                    aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    isVisionCapable = !isLocal && uiState.aiSettings.isVisionCapable(provider, model),
                    onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                    initialUserInput = uiState.chatStarterText,
                    initialUserImageBase64 = uiState.chatStarterImageBase64,
                    initialUserImageMime = uiState.chatStarterImageMime,
                    onConsumeStarter = {
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = null,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                    },
                    experimentalFeatures = uiState.generalSettings.experimentalFeaturesEnabled
                )
            }
        }
        composable(NavRoutes.AI_CHAT_HISTORY) {
            ChatHistoryScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectSession = { navController.navigate(NavRoutes.aiChatContinue(it)) },
                onOpenTraces = { sid -> navController.navigate(NavRoutes.traceListForReport(sid)) },
                onHousekeeping = { navController.navigate(NavRoutes.AI_TRIM_BY_AGE) })
        }
        composable(NavRoutes.AI_CHAT_CONTINUE) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            val session = remember(sessionId) { ChatHistoryManager.loadSession(sessionId) }
            if (session != null) {
                val apiKey = uiState.aiSettings.getApiKey(session.provider)
                val sessionContext = LocalContext.current
                val isLocalSession = session.provider.id == AppService.LOCAL.id
                ChatSessionScreen(
                    provider = session.provider, model = session.model, parameters = session.parameters,
                    userName = uiState.generalSettings.userName, initialMessages = session.messages, sessionId = session.id,
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                    onSendMessageStream = if (isLocalSession) {
                        { messages, _, _, kbs -> chatViewModel.sendLocalLlmStream(sessionContext, session.model, messages, kbs) }
                    } else {
                        { messages, webSearch, reasoning, kbs ->
                            chatViewModel.sendChatMessageStream(session.provider, apiKey, session.model, messages,
                                sessionParams = session.parameters,
                                webSearchTool = webSearch, reasoningEffort = reasoning,
                                context = sessionContext, knowledgeBaseIds = kbs)
                        }
                    },
                    onRecordStatistics = if (isLocalSession) {
                        { _, _ -> /* no remote billing for local */ }
                    } else {
                        { inp, out -> chatViewModel.recordChatStatistics(session.provider, session.model, inp, out) }
                    },
                    aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    isVisionCapable = !isLocalSession && uiState.aiSettings.isVisionCapable(session.provider, session.model),
                    onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                    experimentalFeatures = uiState.generalSettings.experimentalFeaturesEnabled
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
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) })
        }

        // ===== Admin =====
}
