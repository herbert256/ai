package com.ai.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
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
import com.ai.ui.search.*
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
    onExternalIntentHandled: () -> Unit = {},
    sharedContent: com.ai.data.SharedContent? = null,
    onSharedContentHandled: () -> Unit = {}
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

    // Share-target overlay — when the launching Intent was an
    // ACTION_SEND / ACTION_SEND_MULTIPLE the user picks a destination
    // here before the standard nav graph takes over. Renders on top
    // of the back-stack until consumed.
    if (sharedContent != null && !sharedContent.isEmpty) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        com.ai.ui.share.ShareChooserScreen(
            shared = sharedContent,
            onCancel = onSharedContentHandled,
            onSendToReport = {
                scope.launch {
                    routeShareToReport(context, appViewModel, navController, sharedContent)
                    onSharedContentHandled()
                }
            },
            onSendToChat = {
                appViewModel.updateUiState { it.copy(chatStarterText = sharedContent.text) }
                // Land on the configure-on-the-fly provider picker so
                // the user picks model/parameters; the staged starter
                // text follows them into ChatSessionScreen via UiState.
                navController.navigate(NavRoutes.AI_CHAT_PROVIDER) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
                onSharedContentHandled()
            },
            onSendToKnowledge = {
                appViewModel.updateUiState { it.copy(pendingKnowledgeUris = sharedContent.uris) }
                // URLs land in the AI_KNOWLEDGE list screen as the
                // "type a URL" path on a new KB; the URL itself
                // travels via chatStarterText? No — keep that for
                // chat. URLs go through pendingKnowledgeUris too;
                // the Knowledge screen branches on `content://` vs
                // `http://` schemes when it consumes the queue.
                if (sharedContent.isUrl && sharedContent.uris.isEmpty()) {
                    appViewModel.updateUiState { it.copy(pendingKnowledgeUris = listOf(sharedContent.text!!.trim())) }
                }
                navController.navigate(NavRoutes.AI_KNOWLEDGE) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
                onSharedContentHandled()
            }
        )
        return
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
                onNavigateToKnowledge = { navController.navigate(NavRoutes.AI_KNOWLEDGE) },
                viewModel = appViewModel
            )
        }

        // ===== Settings =====
        composable(NavRoutes.SETTINGS) {
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToProviderAdmin = { navController.navigate(NavRoutes.AI_PROVIDER_ADMIN) })
        }
        composable(NavRoutes.AI_SETUP) {
            SetupScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToProviderAdmin = { navController.navigate(NavRoutes.AI_PROVIDER_ADMIN) })
        }

        // ===== Reports =====
        composable(NavRoutes.AI_REPORTS_HUB) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            ReportsHubScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToNewReport = { navController.navigate(NavRoutes.AI_NEW_REPORT) },
                onNavigateToPromptHistory = { navController.navigate(NavRoutes.AI_PROMPT_HISTORY) },
                onNavigateToHistory = { navController.navigate(NavRoutes.AI_HISTORY) },
                onNavigateToSearch = { navController.navigate(NavRoutes.AI_SEARCH) },
                onNavigateToLocalSemanticSearch = { navController.navigate(NavRoutes.AI_LOCAL_SEMANTIC_SEARCH) },
                onNavigateToLocalSearch = { navController.navigate(NavRoutes.AI_LOCAL_SEARCH) },
                onNavigateToQuickLocalSearch = { navController.navigate(NavRoutes.AI_QUICK_LOCAL_SEARCH) },
                onNavigateToManage = { navController.navigate(NavRoutes.AI_REPORT_MANAGE) },
                onOpenReport = { reportId ->
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.AI_REPORTS)
                    }
                },
                onStartWithPhoto = { mime, b64 ->
                    // Stage the camera photo in the same UiState
                    // fields the share-target chooser writes; the
                    // New AI Report screen seeds attachedImage from
                    // them on first composition and clears them.
                    appViewModel.updateUiState { it.copy(reportImageBase64 = b64, reportImageMime = mime) }
                    navController.navigate(NavRoutes.AI_NEW_REPORT)
                })
        }
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
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.AI_REPORTS)
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
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.AI_REPORTS)
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
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.AI_REPORTS)
                    }
                }
            )
        }
        composable(NavRoutes.AI_REPORT_MANAGE) {
            com.ai.ui.report.ReportManageScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_QUICK_LOCAL_SEARCH) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            com.ai.ui.search.QuickLocalSearchScreen(
                onBack = safePopBack,
                onNavigateHome = navigateHome,
                onOpenReport = { reportId ->
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.AI_REPORTS)
                    }
                }
            )
        }
        composable(NavRoutes.AI_NEW_REPORT) {
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) })
        }
        composable(NavRoutes.AI_NEW_REPORT_WITH_PARAMS) { entry ->
            val title = try { java.net.URLDecoder.decode(entry.arguments?.getString("title") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val prompt = try { java.net.URLDecoder.decode(entry.arguments?.getString("prompt") ?: "", "UTF-8") } catch (_: Exception) { "" }
            NewReportScreen(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToReports = { navController.navigate(NavRoutes.AI_REPORTS) },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                initialTitle = title, initialPrompt = prompt)
        }
        composable(NavRoutes.AI_REPORTS) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            ReportsScreenNav(viewModel = appViewModel, reportViewModel = reportViewModel,
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToTrace = { navController.navigate(NavRoutes.traceListForReport(it)) },
                onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToTraceListFiltered = { rid, cat ->
                    navController.navigate(NavRoutes.traceListForReportCategory(rid, cat))
                },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onContinueWithCurrent = { rid, aid ->
                    scope.launch {
                        val sessionId = continueReportInChat(
                            context, rid, aid,
                            aiSettings = appViewModel.uiState.value.aiSettings
                        ) ?: return@launch
                        navController.navigate(NavRoutes.aiChatContinue(sessionId))
                    }
                },
                onContinueWithAgentPicker = { rid, aid ->
                    scope.launch {
                        val response = readReportAgentResponse(context, rid, aid) ?: return@launch
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = response,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                        navController.navigate(NavRoutes.AI_CHAT_AGENT_SELECT)
                    }
                },
                onContinueWithOnTheFly = { rid, aid ->
                    scope.launch {
                        val response = readReportAgentResponse(context, rid, aid) ?: return@launch
                        appViewModel.updateUiState {
                            it.copy(
                                chatStarterText = response,
                                chatStarterImageBase64 = null,
                                chatStarterImageMime = null
                            )
                        }
                        navController.navigate(NavRoutes.AI_CHAT_PROVIDER)
                    }
                })
        }
        composable(NavRoutes.AI_PROMPT_HISTORY) {
            PromptHistoryScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectEntry = { navController.navigate(NavRoutes.aiNewReportWithParams(it.title, it.prompt)) })
        }

        // ===== History =====
        composable(NavRoutes.AI_HISTORY) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            HistoryScreenNav(
                onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onOpenReportResult = { reportId ->
                    scope.launch {
                        reportViewModel.restoreCompletedReport(context, reportId)
                        navController.navigate(NavRoutes.AI_REPORTS)
                    }
                },
                onNavigateToTraceList = { rid -> navController.navigate(NavRoutes.traceListForReport(rid)) }
            )
        }

        // ===== Usage =====
        composable(NavRoutes.AI_USAGE) {
            val uiState by appViewModel.uiState.collectAsState()
            UsageScreen(
                openRouterApiKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.findById("OPENROUTER")?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                },
                onBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) })
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
                // OpenRouter key has two storage slots: External Services
                // (generalSettings.openRouterApiKey) and the OpenRouter
                // chat-provider entry (aiSettings.getApiKey(OPENROUTER)).
                // Prefer the External Services one — that's the canonical
                // catalog-lookup key — and fall back to the provider key
                // for users who only ever used OpenRouter as a chat host.
                val orKey = uiState.generalSettings.openRouterApiKey.ifBlank {
                    AppService.findById("OPENROUTER")?.let { uiState.aiSettings.getApiKey(it) } ?: ""
                }
                ModelInfoScreen(provider = provider, modelName = model,
                    openRouterApiKey = orKey,
                    huggingFaceApiKey = uiState.generalSettings.huggingFaceApiKey, aiSettings = uiState.aiSettings,
                    repository = appViewModel.repository,
                    onSaveSettings = { appViewModel.updateSettings(it) },
                    onTestAiModel = { s, k, m -> appViewModel.testAiModel(s, k, m) },
                    onFetchModels = appViewModel::fetchModels,
                    onStartChat = { p, m -> navController.navigate(NavRoutes.aiChatParams(p.id, m)) },
                    onNavigateToTracesForModel = { p, m -> navController.navigate(NavRoutes.traceListForModel(p.id, m)) },
                    onNavigateToAddManualOverride = { p, m -> navController.navigate(NavRoutes.aiManualOverrideAdd(p.id, m)) },
                    onNavigateToAddCostOverride = { p, m -> navController.navigate(NavRoutes.aiManualCostOverrideAdd(p.id, m)) },
                    onNavigateBack = safePopBack, onNavigateHome = navigateHome)
            }
        }
        composable(NavRoutes.AI_MANUAL_OVERRIDE_ADD) { entry ->
            val providerId = entry.arguments?.getString("provider") ?: ""
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            ManualModelOverrideEntryScreen(
                aiSettings = uiState.aiSettings,
                providerId = providerId,
                modelId = model,
                onSave = { appViewModel.updateSettings(it) },
                onBack = safePopBack
            )
        }
        composable(NavRoutes.AI_MANUAL_COST_OVERRIDE_ADD) { entry ->
            val providerId = entry.arguments?.getString("provider") ?: ""
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            val uiState by appViewModel.uiState.collectAsState()
            ManualCostOverrideEntryScreen(
                aiSettings = uiState.aiSettings,
                providerId = providerId,
                modelId = model,
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }

        // ===== Chat =====
        composable(NavRoutes.AI_CHATS_HUB) {
            val uiState by appViewModel.uiState.collectAsState()
            ChatsHubScreen(aiSettings = uiState.aiSettings, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
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
                    }
                )
            } else {
                LaunchedEffect(Unit) { safePopBack() }
            }
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
            // Configure-on-the-fly entry: one rich picker (search +
            // type / provider / capability filters) instead of the old
            // two-step provider→model flow. Row click jumps straight
            // to AI_CHAT_PARAMS with the chosen (provider, model).
            com.ai.ui.models.ModelSearchScreen(
                aiSettings = uiState.aiSettings,
                loadingModelsFor = uiState.loadingModelsFor,
                onBackToAiSetup = safePopBack, onBackToHome = navigateHome,
                onNavigateToModelInfo = { _, _ -> },
                onPickModel = { provider, model ->
                    navController.navigate(NavRoutes.aiChatParams(provider.id, model))
                }
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
                val isLocal = provider.id == "LOCAL"
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
                    }
                )
            }
        }
        composable(NavRoutes.AI_CHAT_HISTORY) {
            ChatHistoryScreen(onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onSelectSession = { navController.navigate(NavRoutes.aiChatContinue(it)) },
                onOpenTraces = { sid -> navController.navigate(NavRoutes.traceListForReport(sid)) })
        }
        composable(NavRoutes.AI_CHAT_CONTINUE) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            val session = remember(sessionId) { ChatHistoryManager.loadSession(sessionId) }
            if (session != null) {
                val apiKey = uiState.aiSettings.getApiKey(session.provider)
                val sessionContext = LocalContext.current
                val isLocalSession = session.provider.id == "LOCAL"
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
                    isVisionCapable = !isLocalSession && uiState.aiSettings.isVisionCapable(session.provider, session.model),
                    onNavigateToTraceFile = { navController.navigate(NavRoutes.traceDetail(it)) }
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
        composable(NavRoutes.AI_HOUSEKEEPING) {
            HousekeepingScreenNav(
                viewModel = appViewModel,
                onNavigateHome = navigateHome,
                onNavigateToImportExport = { navController.navigate(NavRoutes.AI_IMPORT_EXPORT) },
                onNavigateToRefresh = { navController.navigate(NavRoutes.AI_REFRESH) }
            )
        }
        composable(NavRoutes.AI_PROVIDER_ADMIN) {
            val uiState by appViewModel.uiState.collectAsState()
            com.ai.ui.admin.ProviderAdminScreen(
                aiSettings = uiState.aiSettings,
                onBack = safePopBack,
                onNavigateHome = navigateHome
            )
        }
        composable(NavRoutes.AI_IMPORT_EXPORT) {
            SettingsScreenNav(
                viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToProviderAdmin = { navController.navigate(NavRoutes.AI_PROVIDER_ADMIN) },
                initialSubScreen = SettingsSubScreen.AI_IMPORT_EXPORT
            )
        }
        composable(NavRoutes.AI_REFRESH) {
            SettingsScreenNav(
                viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToProviderAdmin = { navController.navigate(NavRoutes.AI_PROVIDER_ADMIN) },
                initialSubScreen = SettingsSubScreen.AI_REFRESH
            )
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
        composable(NavRoutes.TRACE_LIST_FOR_REPORT_CATEGORY) { entry ->
            val reportId = entry.arguments?.getString("reportId") ?: ""
            val category = try {
                java.net.URLDecoder.decode(entry.arguments?.getString("category") ?: "", "UTF-8")
            } catch (_: Exception) { "" }
            TraceListScreen(onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() },
                reportId = reportId, initialCategory = category)
        }
        composable(NavRoutes.TRACE_LIST_FOR_MODEL) { entry ->
            val model = try { java.net.URLDecoder.decode(entry.arguments?.getString("model") ?: "", "UTF-8") } catch (_: Exception) { "" }
            TraceListScreen(onBack = safePopBack, onNavigateHome = navigateHome,
                onSelectTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onClearTraces = { appViewModel.clearTraces() }, modelFilter = model)
        }
        composable(NavRoutes.TRACE_DETAIL) { entry ->
            val filename = entry.arguments?.getString("filename") ?: ""
            val uiState by appViewModel.uiState.collectAsState()
            TraceDetailScreen(
                filename = filename, aiSettings = uiState.aiSettings,
                onBack = safePopBack, onNavigateHome = navigateHome,
                onEditRequest = { navController.navigate(NavRoutes.AI_API_TEST_EDIT) },
                onNavigateToProvider = { p -> navController.navigate(NavRoutes.settingsProviderEdit(p.id)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToEditAgent = { id -> navController.navigate(NavRoutes.settingsAgentEdit(id)) }
            )
        }
        composable(NavRoutes.SETTINGS_PROVIDER_EDIT) { entry ->
            val pid = entry.arguments?.getString("providerId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToProviderAdmin = { navController.navigate(NavRoutes.AI_PROVIDER_ADMIN) },
                initialSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT,
                initialProviderId = pid)
        }
        composable(NavRoutes.SETTINGS_AGENT_EDIT) { entry ->
            val aid = entry.arguments?.getString("agentId")
            SettingsScreenNav(viewModel = appViewModel, onNavigateBack = safePopBack, onNavigateHome = navigateHome,
                onNavigateToCostConfig = { navController.navigate(NavRoutes.AI_COST_CONFIG) },
                onNavigateToTrace = { navController.navigate(NavRoutes.traceDetail(it)) },
                onNavigateToModelInfo = { p, m -> navController.navigate(NavRoutes.aiModelInfo(p.id, m)) },
                onNavigateToProviderAdmin = { navController.navigate(NavRoutes.AI_PROVIDER_ADMIN) },
                initialSubScreen = SettingsSubScreen.AI_AGENT_EDIT,
                initialEditingAgentId = aid)
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
    onNavigateToProviderAdmin: () -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    initialProviderId: String? = null,
    initialEditingAgentId: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    SettingsScreen(
        generalSettings = uiState.generalSettings, aiSettings = uiState.aiSettings,
        loadingModelsFor = uiState.loadingModelsFor,
        fetchModelsErrors = uiState.fetchModelsErrors,
        onFetchModels = viewModel::fetchModels,
        onBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onSaveGeneral = { viewModel.updateGeneralSettings(it) }, onSaveAi = { viewModel.updateSettings(it) },
        onTestAiModel = { s, k, m -> viewModel.testAiModel(s, k, m) },
        onProviderStateChange = { s, st -> viewModel.updateProviderState(s, st) },
        onProviderTestedOk = { s, m -> viewModel.markProviderTestedOk(s, m) },
        onRefreshAllModels = { settings, force, progress -> viewModel.refreshAllModelLists(settings, force, progress) },
        onSaveHuggingFaceApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(huggingFaceApiKey = it)) },
        onSaveOpenRouterApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(openRouterApiKey = it)) },
        onSaveArtificialAnalysisApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(artificialAnalysisApiKey = it)) },
        onNavigateToCostConfig = onNavigateToCostConfig,
        onNavigateToProviderAdmin = onNavigateToProviderAdmin,
        onTestModelWithPrompt = { s, k, m, p -> viewModel.testModelWithPrompt(s, k, m, p) },
        onTestSpecificModel = { s, k, m, p -> viewModel.testSpecificModel(s, k, m, p) },
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onLoadBundledPrompts = { viewModel.loadBundledInternalPrompts() },
        initialSubScreen = initialSubScreen,
        initialProviderId = initialProviderId,
        initialEditingAgentId = initialEditingAgentId
    )
}

@Composable
fun SetupScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToProviderAdmin: () -> Unit = {}
) {
    SettingsScreenNav(viewModel = viewModel, onNavigateBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onNavigateToCostConfig = onNavigateToCostConfig, onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onNavigateToProviderAdmin = onNavigateToProviderAdmin,
        initialSubScreen = SettingsSubScreen.AI_SETUP)
}

@Composable
fun HousekeepingScreenNav(
    viewModel: AppViewModel,
    onNavigateHome: () -> Unit,
    onNavigateToImportExport: () -> Unit = {},
    onNavigateToRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    HousekeepingScreen(
        onBackToHome = onNavigateHome,
        onClearUsageStatistics = { viewModel.clearUsageStatistics() },
        onClearRuntimeData = { viewModel.clearAllRuntimeData(context) },
        onClearConfiguration = { viewModel.clearAllConfiguration(context) },
        onResetApplication = { onComplete -> viewModel.resetApplication(context, onComplete) },
        onNavigateToImportExport = onNavigateToImportExport,
        onNavigateToRefresh = onNavigateToRefresh
    )
}

/** Route a SharedContent payload onto the New Report flow. Text /
 *  subject become title + prompt; the first image attachment (if
 *  any) becomes the report's vision attachment via base64; non-image
 *  attachments don't have a per-report home (Knowledge handles
 *  documents) so they're dropped quietly here — the user can still
 *  send the same payload to Knowledge separately. */
private suspend fun routeShareToReport(
    context: android.content.Context,
    appViewModel: AppViewModel,
    navController: androidx.navigation.NavHostController,
    shared: com.ai.data.SharedContent
) {
    val title = shared.subject?.takeIf { it.isNotBlank() } ?: ""
    val prompt = shared.text?.takeIf { it.isNotBlank() } ?: ""
    // Partition URIs by mime — first image-typed one becomes the
    // vision attachment; everything else queues for auto-attach as
    // a new knowledge base on the New Report screen. This honours
    // the chooser copy ("text → prompt; images → vision; files →
    // Knowledge") instead of silently dropping the docs.
    fun mimeOf(uri: String): String? =
        runCatching { context.contentResolver.getType(android.net.Uri.parse(uri)) }.getOrNull()
            ?: shared.mime
    val (imageUris, nonImageUris) = shared.uris.partition { mimeOf(it)?.startsWith("image/") == true }
    val firstImageUri = imageUris.firstOrNull()
    if (firstImageUri != null) {
        val pair: Pair<String, ByteArray?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val u = android.net.Uri.parse(firstImageUri)
            val resolved = context.contentResolver.getType(u) ?: "image/png"
            val bytes: ByteArray? = context.contentResolver.openInputStream(u)?.use { it.readBytes() }
            resolved to bytes
        }
        val (mime, bytes) = pair
        if (bytes != null) {
            appViewModel.updateUiState {
                it.copy(reportImageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                        reportImageMime = mime)
            }
        }
    }
    if (nonImageUris.isNotEmpty()) {
        appViewModel.updateUiState { it.copy(pendingReportKnowledgeUris = nonImageUris) }
    }
    navController.navigate(NavRoutes.aiNewReportWithParams(title, prompt)) {
        popUpTo(NavRoutes.AI) { inclusive = false }
    }
}

/** Build a fresh ChatSession seeded with the report's prompt as the
 *  user turn and the chosen agent's response as the assistant turn,
 *  persist it via [com.ai.data.ChatHistoryManager], and return the
 *  session id so the caller can navigate to AI_CHAT_CONTINUE. The
 *  new session is independent of the source report — editing or
 *  deleting either one does not affect the other.
 *
 *  Uses the agent's actual provider/model so the user keeps talking
 *  to the same model that produced the report response. The first
 *  user turn carries the report's vision attachment if there was
 *  one. The new session inherits the agent's resolved system prompt
 *  + parameters from current settings (same mapping the
 *  AI_CHAT_WITH_AGENT route uses); if the agent has been deleted
 *  since the report was written we fall back to ChatParameters
 *  defaults. */
private suspend fun continueReportInChat(
    context: android.content.Context,
    reportId: String,
    agentId: String,
    aiSettings: com.ai.model.Settings
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val report = com.ai.data.ReportStorage.getReport(context, reportId) ?: return@withContext null
    val agent = report.agents.firstOrNull { it.agentId == agentId } ?: return@withContext null
    val provider = AppService.findById(agent.provider) ?: return@withContext null
    val responseBody = agent.responseBody?.takeIf { it.isNotBlank() } ?: return@withContext null

    val settingsAgent = aiSettings.getAgentById(agentId)
    val chatParams = if (settingsAgent != null) {
        val rp = aiSettings.resolveAgentParameters(settingsAgent)
        com.ai.data.ChatParameters(
            temperature = rp.temperature, maxTokens = rp.maxTokens,
            topP = rp.topP, topK = rp.topK,
            frequencyPenalty = rp.frequencyPenalty, presencePenalty = rp.presencePenalty,
            systemPrompt = rp.systemPrompt ?: "",
            searchEnabled = rp.searchEnabled, returnCitations = rp.returnCitations,
            searchRecency = rp.searchRecency, webSearchTool = rp.webSearchTool
        )
    } else com.ai.data.ChatParameters()

    val now = System.currentTimeMillis()
    val session = com.ai.data.ChatSession(
        provider = provider,
        model = agent.model,
        messages = listOf(
            com.ai.data.ChatMessage(
                role = "user",
                content = report.prompt,
                timestamp = report.timestamp,
                imageBase64 = report.imageBase64,
                imageMime = report.imageMime
            ),
            com.ai.data.ChatMessage(
                role = "assistant",
                content = responseBody,
                timestamp = (agent.durationMs ?: 0L).let { d -> if (d > 0) report.timestamp + d else now }
            )
        ),
        parameters = chatParams,
        createdAt = now,
        updatedAt = now
    )
    if (com.ai.data.ChatHistoryManager.saveSession(session)) session.id else null
}

/** Fetch just the assistant response text for one agent of one
 *  report. Used by the "Continue in chat … with this response only"
 *  flows to stash the text as `chatStarterText` before routing into
 *  the agent picker / configure-on-the-fly chains. */
private suspend fun readReportAgentResponse(
    context: android.content.Context,
    reportId: String,
    agentId: String
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val report = com.ai.data.ReportStorage.getReport(context, reportId) ?: return@withContext null
    val agent = report.agents.firstOrNull { it.agentId == agentId } ?: return@withContext null
    agent.responseBody?.takeIf { it.isNotBlank() }
}
