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
    // App-wide background resume sweep — walks every report
    // modified in the last 7 days and runs the same per-report
    // stale-resume pass that fires when a report is opened.
    // LaunchedEffect(Unit) fires once per composition; the
    // start method's cancel-prior pattern (Job stored on
    // AppViewModel) handles Activity rotation cleanly.
    val sweepContext = LocalContext.current
    LaunchedEffect(Unit) {
        reportViewModel.secondary.startBackgroundResumeSweep(sweepContext)
    }

    val safePopBack: () -> Unit = {
        if (navController.previousBackStackEntry != null) navController.popBackStack()
    }
    val navigateHome: () -> Unit = {
        navController.navigate(NavRoutes.AI) { popUpTo(NavRoutes.AI) { inclusive = true } }
    }

    // Handle external intent.
    //
    // ACTION_NEW_REPORT is exported, so any installed app can fire it.
    // Bare-prompt intents (no `<instructions>` block) merely pre-fill
    // the new-report editor — the user still picks models and taps
    // Generate manually, so no API credits move without consent.
    // Anything with instructions, however, can auto-generate, drive
    // model selection, email/share/browser the result, and finish()
    // the activity. That class of intent must pass through an explicit
    // confirmation screen before any of those side effects run.
    val pendingExternalReport = remember {
        mutableStateOf<com.ai.ui.share.PendingExternalReport?>(null)
    }
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

            pendingExternalReport.value = com.ai.ui.share.PendingExternalReport(
                title = externalTitle,
                systemPrompt = externalSystem,
                aiPrompt = aiPrompt,
                openHtml = extractTag("open", instructions),
                closeHtml = extractTag("close", instructions),
                reportType = extractTag("type", instructions),
                email = extractTag("email", instructions),
                nextAction = extractTag("next", instructions),
                hasReturn = Regex("<return>", RegexOption.IGNORE_CASE).containsMatchIn(instructions),
                hasEdit = Regex("<edit>", RegexOption.IGNORE_CASE).containsMatchIn(instructions),
                hasSelect = Regex("<select>", RegexOption.IGNORE_CASE).containsMatchIn(instructions),
                agentNames = extractAllTags("agent", instructions),
                flockNames = extractAllTags("flock", instructions),
                swarmNames = extractAllTags("swarm", instructions),
                modelSpecs = extractAllTags("model", instructions)
            )
            // Clear the source-of-truth extras so a configuration
            // change doesn't re-stage the confirmation after the user
            // has cancelled or confirmed it.
            onExternalIntentHandled()
        }
    }

    pendingExternalReport.value?.let { staged ->
        com.ai.ui.share.ExternalIntentConfirmScreen(
            intent = staged,
            onCancel = { pendingExternalReport.value = null },
            onConfirm = {
                appViewModel.setExternalInstructions(
                    closeHtml = staged.closeHtml,
                    reportType = staged.reportType,
                    email = staged.email,
                    nextAction = staged.nextAction,
                    returnAfterNext = staged.hasReturn,
                    agentNames = staged.agentNames,
                    flockNames = staged.flockNames,
                    swarmNames = staged.swarmNames,
                    modelSpecs = staged.modelSpecs,
                    edit = staged.hasEdit,
                    select = staged.hasSelect,
                    openHtml = staged.openHtml,
                    systemPrompt = staged.systemPrompt
                )
                if (staged.hasEdit) {
                    navController.navigate(NavRoutes.aiNewReportWithParams(staged.title ?: "", staged.aiPrompt)) {
                        popUpTo(NavRoutes.AI) { inclusive = false }
                    }
                } else {
                    val fullPrompt = if (staged.openHtml != null)
                        "${staged.aiPrompt}\n<user>${staged.openHtml}</user>" else staged.aiPrompt
                    reportViewModel.showGenericAgentSelection(staged.title ?: "", fullPrompt)
                    navController.navigate(NavRoutes.AI_REPORTS) { popUpTo(NavRoutes.AI) { inclusive = false } }
                }
                pendingExternalReport.value = null
            }
        )
        return
    }

    // Share-target overlay — when the launching Intent was an
    // ACTION_SEND / ACTION_SEND_MULTIPLE the user picks a destination
    // here before the standard nav graph takes over. Renders on top
    // of the back-stack until consumed.
    if (sharedContent != null && !sharedContent.isEmpty) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val uiStateForShare by appViewModel.uiState.collectAsState()
        com.ai.ui.share.ShareChooserScreen(
            shared = sharedContent,
            experimentalFeatures = uiStateForShare.generalSettings.experimentalFeaturesEnabled,
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
                // Build the queue once: any attachment URIs + the URL
                // text (when present). The previous flow first wrote
                // the uri list, then conditionally overwrote it with
                // a URL-only list ONLY when uris was empty — a share
                // carrying both `text=https://…` AND a PDF therefore
                // dropped the URL silently. Knowledge consumes the
                // queue and branches on content:// vs http:// per
                // entry, so the merged list is fine.
                val urlText = if (sharedContent.isUrl) sharedContent.text?.trim().orEmpty() else ""
                val queue = sharedContent.uris + listOfNotNull(urlText.takeIf { it.isNotBlank() })
                appViewModel.updateUiState { it.copy(pendingKnowledgeUris = queue) }
                navController.navigate(NavRoutes.AI_KNOWLEDGE) {
                    popUpTo(NavRoutes.AI) { inclusive = false }
                }
                onSharedContentHandled()
            }
        )
        return
    }

    // Surface the user's "Model name layout" preference + a global
    // navigate-to-Model-Info so any Composable in the tree can format
    // combined provider+model labels via [com.ai.ui.shared.modelLabel]
    // and make them clickable via [Modifier.modelInfoClickable]
    // without prop-drilling.
    val rootUiStateForLayout by appViewModel.uiState.collectAsState()
    val rootNavigateToModelInfo: (AppService, String) -> Unit = { p, m ->
        navController.navigate(NavRoutes.aiModelInfo(p.id, m))
    }
    // View-flavoured siblings — set as CompositionLocals so any View
    // screen can navigate to the read-only model-info / agent / flock
    // / swarm pages without prop-drilling through 30+ args.
    val rootNavigateToModelInfoView: (AppService, String) -> Unit = { p, m ->
        navController.navigate(NavRoutes.aiModelInfoView(p.id, m))
    }
    val rootNavigateToAgentView: (String) -> Unit = { id ->
        navController.navigate(NavRoutes.aiAgentView(id))
    }
    val rootNavigateToFlockView: (String) -> Unit = { id ->
        navController.navigate(NavRoutes.aiFlockView(id))
    }
    val rootNavigateToSwarmView: (String) -> Unit = { id ->
        navController.navigate(NavRoutes.aiSwarmView(id))
    }
    val rootNavigateHome: () -> Unit = {
        navController.navigate(NavRoutes.AI) {
            popUpTo(NavRoutes.AI) { inclusive = false }
            launchSingleTop = true
        }
    }
    val rootNavigateHelp: (String?) -> Unit = { topic ->
        if (topic.isNullOrBlank()) navController.navigate(NavRoutes.HELP)
        else navController.navigate(NavRoutes.helpForTopic(topic))
    }
    // The bottom icon bar is now the fixed layout (the old top-bar
    // alternative has been retired) — every TitleBar publishes its
    // action icons here, and AppNavHost paints the bar at the bottom
    // of every nav destination except the Hub.
    val bottomBarIconState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.ai.ui.shared.TitleBarIcons?>(null)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalModelNameLayout provides rootUiStateForLayout.generalSettings.modelNameLayout,
        com.ai.ui.shared.LocalNavigateToModelInfo provides rootNavigateToModelInfo,
        com.ai.ui.shared.LocalNavigateToModelInfoView provides rootNavigateToModelInfoView,
        com.ai.ui.shared.LocalNavigateToAgentView provides rootNavigateToAgentView,
        com.ai.ui.shared.LocalNavigateToFlockView provides rootNavigateToFlockView,
        com.ai.ui.shared.LocalNavigateToSwarmView provides rootNavigateToSwarmView,
        com.ai.ui.shared.LocalIconGenEnabled provides rootUiStateForLayout.generalSettings.iconGenEnabled,
        com.ai.ui.shared.LocalBottomIconState provides bottomBarIconState,
        com.ai.ui.shared.LocalNavigateHome provides rootNavigateHome,
        com.ai.ui.shared.LocalNavigateToHelp provides rootNavigateHelp
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.AI,
        modifier = Modifier.weight(1f)
    ) {

        // ===== Hub =====
        reportRoutes(navController, appViewModel, reportViewModel, chatViewModel, safePopBack, navigateHome)
        settingsAdminRoutes(navController, appViewModel, reportViewModel, chatViewModel, safePopBack, navigateHome)
        knowledgeSearchRoutes(navController, appViewModel, reportViewModel, chatViewModel, safePopBack, navigateHome)
        developerRoutes(navController, appViewModel, reportViewModel, chatViewModel, safePopBack, navigateHome)
        chatRoutes(navController, appViewModel, reportViewModel, chatViewModel, safePopBack, navigateHome)
    }
    // Hide the bar on the home Hub — that screen has no TitleBar
    // (it's the centered "AI" logo) so the bar would just show
    // the bare Home + Help fallback. The Hub already routes home /
    // help via its own card list; no need for a duplicate strip.
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    if (currentRoute != NavRoutes.AI) {
        com.ai.ui.shared.BottomIconBar(icons = bottomBarIconState.value)
    }
    } // end Column
    } // end CompositionLocalProvider
}

// ===== Navigation Wrappers =====

@Composable
fun SettingsScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToHelpTopic: (String) -> Unit = {},
    /** Forwarded into ImportExportScreen so the post-API-keys-import
     *  dialog's "Run Refresh all" branch can land the user on the
     *  Refresh sub-screen (where the progress overlay paints). */
    onNavigateToRefresh: () -> Unit = {},
    /** Optional 👁 → View-route hooks for the Agent / Flock / Swarm
     *  Edit sub-screens. Wired by the top-level SETTINGS route in
     *  AppNavHost to navController.navigate(NavRoutes.aiXView(id));
     *  default no-op leaves the icon hidden on call sites that don't
     *  wire them. */
    onNavigateToAgentView: ((String) -> Unit)? = null,
    onNavigateToFlockView: ((String) -> Unit)? = null,
    onNavigateToSwarmView: ((String) -> Unit)? = null,
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    initialProviderId: String? = null,
    initialEditingAgentId: String? = null,
    initialEditingFlockId: String? = null,
    initialEditingSwarmId: String? = null,
    initialEditingInternalPromptId: String? = null,
    initialInternalPromptCategory: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val refreshAllState by viewModel.refreshAllState.collectAsState()
    SettingsScreen(
        generalSettings = uiState.generalSettings, aiSettings = uiState.aiSettings,
        loadingModelsFor = uiState.loadingModelsFor,
        fetchModelsErrors = uiState.fetchModelsErrors,
        onFetchModels = viewModel::fetchModels,
        onFetchModelsAwait = { s, k -> viewModel.fetchModelsAwait(s, k, flipToApiOnSuccess = true) },
        onBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onSaveGeneral = { viewModel.updateGeneralSettings(it) }, onSaveAi = { viewModel.updateSettings(it) },
        onTestAiModel = { s, k, m -> viewModel.testAiModel(s, k, m) },
        onProviderStateChange = { s, st -> viewModel.updateProviderState(s, st) },
        onProviderTestedOk = { s, m -> viewModel.markProviderTestedOk(s, m) },
        onProviderTestedOkNoFetch = { s, m -> viewModel.markProviderTestedOk(s, m, fetchAfter = false) },
        onReplaceDefaultAgent = { s, m -> viewModel.replaceDefaultAgent(s, m) },
        onRefreshAllModels = { settings, force, progress -> viewModel.refreshAllModelLists(settings, force, progress) },
        refreshAllState = refreshAllState,
        onStartRefreshAll = { viewModel.startRefreshAll() },
        onStartRefreshWorkers = { viewModel.startRefreshWorkers() },
        onClearRefreshAllState = { viewModel.clearRefreshAllState() },
        onNavigateToRefresh = onNavigateToRefresh,
        onSaveHuggingFaceApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(huggingFaceApiKey = it)) },
        onSaveOpenRouterApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(openRouterApiKey = it)) },
        onSaveArtificialAnalysisApiKey = { viewModel.updateGeneralSettings(viewModel.uiState.value.generalSettings.copy(artificialAnalysisApiKey = it)) },
        onNavigateToCostConfig = onNavigateToCostConfig,
        onNavigateToHelpTopic = onNavigateToHelpTopic,
        onTestModelWithPrompt = { s, k, m, p -> viewModel.testModelWithPrompt(s, k, m, p) },
        onTestSpecificModel = { s, k, m, p -> viewModel.testSpecificModel(s, k, m, p) },
        onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onNavigateToAgentView = onNavigateToAgentView,
        onNavigateToFlockView = onNavigateToFlockView,
        onNavigateToSwarmView = onNavigateToSwarmView,
        initialSubScreen = initialSubScreen,
        initialProviderId = initialProviderId,
        initialEditingAgentId = initialEditingAgentId,
        initialEditingFlockId = initialEditingFlockId,
        initialEditingSwarmId = initialEditingSwarmId,
        initialEditingInternalPromptId = initialEditingInternalPromptId,
        initialInternalPromptCategory = initialInternalPromptCategory
    )
}

@Composable
fun SetupScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToHelpTopic: (String) -> Unit = {}
) {
    SettingsScreenNav(viewModel = viewModel, onNavigateBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onNavigateToCostConfig = onNavigateToCostConfig, onNavigateToTrace = onNavigateToTrace,
        onNavigateToModelInfo = onNavigateToModelInfo,
        onNavigateToHelpTopic = onNavigateToHelpTopic,
        initialSubScreen = SettingsSubScreen.AI_SETUP)
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
        // Decode + downscale + JPEG-encode rather than streaming the raw
        // bytes — a 12 MP phone photo would otherwise spike memory and
        // ship a multi-MB base64 blob over the wire.
        val pair = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.ai.data.loadImageAsBase64(context, android.net.Uri.parse(firstImageUri))
            }.getOrNull()
        }
        if (pair != null) {
            appViewModel.updateUiState {
                it.copy(reportImageBase64 = pair.second, reportImageMime = pair.first)
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
internal suspend fun continueReportInChat(
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
internal suspend fun readReportAgentResponse(
    context: android.content.Context,
    reportId: String,
    agentId: String
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val report = com.ai.data.ReportStorage.getReport(context, reportId) ?: return@withContext null
    val agent = report.agents.firstOrNull { it.agentId == agentId } ?: return@withContext null
    agent.responseBody?.takeIf { it.isNotBlank() }
}

/** Wraps the four standalone Jetpack-Nav View screens
 *  (ModelInfoView / AgentView / FlockView / SwarmView) in a
 *  CompositionLocalProvider that exposes a "navigate to main View
 *  of the active report" callback via [LocalNavigateToCurrentReport].
 *  ViewScreenTitleBar reads that local to decide whether the report
 *  title is clickable + where it goes. When there's no active
 *  report ([currentReportId] is null) the local resolves to null
 *  and the title stays inert — matching the existing rule on Help
 *  pages and other no-report-context screens. */
@androidx.compose.runtime.Composable
internal fun ViewSubScreenWithTitleNav(
    navController: androidx.navigation.NavHostController,
    currentReportId: String?,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    val navToActiveView: (() -> Unit)? = currentReportId?.let {
        {
            // Pop back to the existing AI_REPORTS route if it's
            // already on the stack — preserves the user's mode +
            // overlay state. Otherwise navigate fresh with
            // initialView=true so ReportsScreenNav seeds the View
            // tile-grid overlay on first composition.
            if (!navController.popBackStack(NavRoutes.AI_REPORTS, false)) {
                navController.navigate(NavRoutes.aiReportView())
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalNavigateToCurrentReport provides navToActiveView
    ) {
        content()
    }
}
