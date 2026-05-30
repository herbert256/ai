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
                    navController.navigate(NavRoutes.aiReports()) { popUpTo(NavRoutes.AI) { inclusive = false } }
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
    val rootNavigateToReportsHub: () -> Unit = {
        navController.navigate(NavRoutes.AI_REPORTS_HUB) {
            popUpTo(NavRoutes.AI_REPORTS_HUB) { inclusive = false }
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
    // View subsystem owns its own bottom bar (centred 🔧). A View screen
    // publishes a spec here while mounted; when present we render the
    // View bar instead of the generic one.
    val viewBottomBarState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.ai.ui.report.view.helpers.ViewBottomBarSpec?>(null)
    }
    // Section icon for the shared top bar's left slot, by current route:
    // 💬 on the AI Chat routes, 🧹 on the Housekeeping routes. Tapping
    // the icon (or screen title) goes Home from the section's main
    // screen, else to the section's main screen. Settings / AI Setup
    // supply their own via SettingsScreen; every other route → null
    // (AI logo / report glyph).
    val currentNavRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val housekeepingSubRoutes = setOf(
        NavRoutes.AI_BACKUP_RESTORE, NavRoutes.AI_TRIM_BY_AGE,
        NavRoutes.AI_UPDATE_FROM_CLOUD, NavRoutes.AI_RESET,
        NavRoutes.AI_APPLOG_LIST, NavRoutes.AI_TEST, NavRoutes.AI_COSTS_MAINTENANCE,
        // 2-levels-deep, housekeeping-only screens (the 🧹 used to vanish
        // here). Reset confirmations, Test-all-models, App-log detail.
        // AI_REFRESH / AI_IMPORT_EXPORT are intentionally absent — shared
        // with AI Setup, where they must keep the 🤖 icon.
        NavRoutes.AI_RESET_RUNTIME, NavRoutes.AI_RESET_INFO_PROVIDERS,
        NavRoutes.AI_RESET_CONFIGURATION, NavRoutes.AI_RESET_ASSETS,
        NavRoutes.AI_RESET_APPLICATION, NavRoutes.AI_TEST_ALL_MODELS,
        NavRoutes.AI_STRESS_TEST,
        NavRoutes.AI_APPLOG_DETAIL
    )
    // Every AI Reports screen that has no dynamic report glyph of its own
    // shows the report default icon (📝) in the left slot instead of the
    // AI logo. Tapping it goes to the AI Reports hub — except on the hub,
    // where it goes Home. Screens that DO carry a report icon (Manage /
    // View with a generated icon) pass it via reportIcon, which takes
    // precedence over this section icon, so they're unaffected.
    val reportSectionRoutes = setOf(
        NavRoutes.AI_NEW_REPORT_HUB, NavRoutes.AI_NEW_REPORT, NavRoutes.AI_NEW_REPORT_WITH_PARAMS,
        NavRoutes.AI_SEARCH_REPORTS, NavRoutes.AI_ALL_REPORTS, NavRoutes.AI_EXAMPLES,
        NavRoutes.AI_PROMPT_HISTORY, NavRoutes.AI_EXAMPLE_PROMPT_PICKER,
        NavRoutes.AI_SEARCH, NavRoutes.AI_LOCAL_SEARCH, NavRoutes.AI_QUICK_LOCAL_SEARCH,
        NavRoutes.AI_LOCAL_SEMANTIC_SEARCH,
        NavRoutes.AI_REPORTS, NavRoutes.AI_REPORT_INFO, NavRoutes.AI_REPORT_MODEL,
        NavRoutes.AI_VIEW_PICK_REPORT, NavRoutes.AI_MANAGE_PICK_REPORT,
        NavRoutes.AI_REPORT_MANAGE
    )
    // AI Models section (🧠) and AI Knowledge section (📚): standalone
    // screens with no report glyph that previously fell back to the AI
    // logo. Sub-screens jump to their section hub; the hub goes Home.
    val modelSectionRoutes = setOf(NavRoutes.AI_MODEL_INFO, NavRoutes.AI_MANUAL_OVERRIDE_ADD)
    val knowledgeSectionRoutes = setOf(NavRoutes.AI_KNOWLEDGE_NEW, NavRoutes.AI_KNOWLEDGE_DETAIL)
    // One-off screens with no section hub — show a fitting local glyph
    // whose tap goes Home. About uses the same ℹ️ it has on the home page.
    val homeIconByRoute: Map<String, String> = mapOf(
        NavRoutes.AI_COST_CONFIG to "💲",
        NavRoutes.AI_MANUAL_COST_OVERRIDE_ADD to "💲",
        NavRoutes.AI_API_TEST to "🧪",
        NavRoutes.AI_API_TEST_EDIT to "🧪",
        NavRoutes.DOCUMENTATION to "📖",
        NavRoutes.DOCUMENTATION_MANUAL to "📖",
        NavRoutes.ABOUT to "ℹ️"
    )
    val reportDefaultIcon = rootUiStateForLayout.generalSettings.metadataIcons.reportIcon
    val sectionTopIcon: com.ai.ui.shared.TopBarLeftIcon? = when {
        currentNavRoute == null -> null
        currentNavRoute == NavRoutes.AI_REPORTS_HUB ->
            com.ai.ui.shared.TopBarLeftIcon(reportDefaultIcon, rootNavigateHome)
        currentNavRoute in reportSectionRoutes ->
            com.ai.ui.shared.TopBarLeftIcon(reportDefaultIcon, rootNavigateToReportsHub)
        currentNavRoute == NavRoutes.AI_CHATS_HUB ->
            com.ai.ui.shared.TopBarLeftIcon("💬", navigateHome)
        currentNavRoute.startsWith("ai_chat") || currentNavRoute.startsWith("ai_dual_chat") ->
            com.ai.ui.shared.TopBarLeftIcon("💬") {
                if (!navController.popBackStack(NavRoutes.AI_CHATS_HUB, false))
                    navController.navigate(NavRoutes.AI_CHATS_HUB)
            }
        currentNavRoute == NavRoutes.AI_HOUSEKEEPING ->
            com.ai.ui.shared.TopBarLeftIcon("🧹", navigateHome)
        currentNavRoute in housekeepingSubRoutes ->
            com.ai.ui.shared.TopBarLeftIcon("🧹") {
                if (!navController.popBackStack(NavRoutes.AI_HOUSEKEEPING, false))
                    navController.navigate(NavRoutes.AI_HOUSEKEEPING)
            }
        currentNavRoute == NavRoutes.AI_MODEL_SEARCH ->
            com.ai.ui.shared.TopBarLeftIcon("🧠", navigateHome)
        currentNavRoute in modelSectionRoutes ->
            com.ai.ui.shared.TopBarLeftIcon("🧠") {
                if (!navController.popBackStack(NavRoutes.AI_MODEL_SEARCH, false))
                    navController.navigate(NavRoutes.AI_MODEL_SEARCH)
            }
        currentNavRoute == NavRoutes.AI_KNOWLEDGE ->
            com.ai.ui.shared.TopBarLeftIcon("📚", navigateHome)
        currentNavRoute in knowledgeSectionRoutes ->
            com.ai.ui.shared.TopBarLeftIcon("📚") {
                if (!navController.popBackStack(NavRoutes.AI_KNOWLEDGE, false))
                    navController.navigate(NavRoutes.AI_KNOWLEDGE)
            }
        homeIconByRoute[currentNavRoute] != null ->
            com.ai.ui.shared.TopBarLeftIcon(homeIconByRoute.getValue(currentNavRoute), navigateHome)
        else -> null
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalTopBarLeftIcon provides sectionTopIcon,
        com.ai.ui.report.view.helpers.LocalViewBottomBar provides viewBottomBarState,
        com.ai.ui.shared.LocalModelNameLayout provides rootUiStateForLayout.generalSettings.modelNameLayout,
        com.ai.ui.shared.LocalNavigateToModelInfo provides rootNavigateToModelInfo,
        com.ai.ui.shared.LocalNavigateToModelInfoView provides rootNavigateToModelInfoView,
        com.ai.ui.shared.LocalNavigateToAgentView provides rootNavigateToAgentView,
        com.ai.ui.shared.LocalNavigateToFlockView provides rootNavigateToFlockView,
        com.ai.ui.shared.LocalNavigateToSwarmView provides rootNavigateToSwarmView,
        com.ai.ui.shared.LocalIconGenEnabled provides rootUiStateForLayout.generalSettings.reportIconOn(),
        com.ai.ui.shared.LocalMetadataEnabled provides rootUiStateForLayout.generalSettings.metadataEnabled,
        com.ai.ui.shared.LocalMetadataIcons provides rootUiStateForLayout.generalSettings.metadataIcons,
        com.ai.ui.shared.LocalBottomIconState provides bottomBarIconState,
        com.ai.ui.shared.LocalNavigateHome provides rootNavigateHome,
        com.ai.ui.shared.LocalNavigateToReportsHub provides rootNavigateToReportsHub,
        com.ai.ui.shared.LocalNavigateToHelp provides rootNavigateHelp,
        com.ai.ui.shared.LocalNavigateToRoute provides { route -> navController.navigate(route) }
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
    val viewBar = viewBottomBarState.value
    if (viewBar != null) {
        // A View screen is active → render the View-owned bottom bar.
        com.ai.ui.report.view.helpers.ViewBottomBar(spec = viewBar)
    } else if (currentRoute != NavRoutes.AI &&
        currentRoute != NavRoutes.HELP &&
        currentRoute != NavRoutes.HELP_FOR_TOPIC
    ) {
        // Help screens never have a bottom bar.
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
    onNavigateToTraceCategory: (String) -> Unit = {},
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
    initialInternalPromptCategory: String? = null,
    sectionIconOverride: com.ai.ui.shared.TopBarLeftIcon? = null
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
        onNavigateToTraceCategory = onNavigateToTraceCategory,
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
        initialInternalPromptCategory = initialInternalPromptCategory,
        sectionIconOverride = sectionIconOverride
    )
}

@Composable
fun SetupScreenNav(
    viewModel: AppViewModel, onNavigateBack: () -> Unit, onNavigateHome: () -> Unit,
    onNavigateToCostConfig: () -> Unit = {}, onNavigateToTrace: (String) -> Unit = {},
    onNavigateToTraceCategory: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToHelpTopic: (String) -> Unit = {}
) {
    SettingsScreenNav(viewModel = viewModel, onNavigateBack = onNavigateBack, onNavigateHome = onNavigateHome,
        onNavigateToCostConfig = onNavigateToCostConfig, onNavigateToTrace = onNavigateToTrace,
        onNavigateToTraceCategory = onNavigateToTraceCategory,
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

/** Build a fresh ChatSession seeded from a META secondary result so
 *  the user can continue the analysis conversationally instead of
 *  copy-pasting it into a separate chat. The originating report's
 *  prompt and every agent response ride along as hidden system-prompt
 *  context; the meta prose itself becomes the assistant's visible
 *  first turn, so the thread reads "here's the comparison — now ask
 *  me about it". Persisted via [com.ai.data.ChatHistoryManager];
 *  returns the new session id for AI_CHAT_CONTINUE, or null if the
 *  report/row is gone or no provider/model resolves.
 *
 *  Provider/model default to the model that produced the meta row (it
 *  already holds the analysis context); if that provider has since
 *  been removed we fall back to the first report agent whose provider
 *  still resolves. [activeLanguage] picks a translated META body when
 *  the user is viewing a non-Original language. The new session is
 *  independent of the source report. */
internal suspend fun continueMetaInChat(
    context: android.content.Context,
    reportId: String,
    resultId: String,
    activeLanguage: String?
): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val report = com.ai.data.ReportStorage.getReport(context, reportId) ?: return@withContext null
    val row = com.ai.data.SecondaryResultStorage.get(context, reportId, resultId)
        ?: return@withContext null

    // Body: the active-language translation if the user is viewing one,
    // else the meta row's own (Original) content.
    val metaBody = (activeLanguage
        ?.takeIf { it.isNotBlank() }
        ?.let { lang ->
            com.ai.data.SecondaryResultStorage
                .listForReport(context, reportId, com.ai.data.SecondaryKind.TRANSLATE)
                .firstOrNull {
                    it.translateSourceKind == "META" &&
                        it.translateSourceTargetId == resultId &&
                        it.targetLanguage == lang &&
                        !it.content.isNullOrBlank()
                }?.content
        }
        ?: row.content)?.takeIf { it.isNotBlank() } ?: return@withContext null

    // Prefer the model that produced the meta; fall back to the first
    // report agent whose provider still resolves.
    val rowProvider = AppService.findById(row.providerId)
    val (provider, model) = if (rowProvider != null && !row.model.isNullOrBlank()) {
        rowProvider to row.model!!
    } else {
        val fallback = report.agents.firstOrNull {
            AppService.findById(it.provider) != null && !it.responseBody.isNullOrBlank()
        }
        val fp = fallback?.let { AppService.findById(it.provider) }
        if (fp != null) fp to fallback.model else return@withContext null
    }

    val responsesBlock = report.agents
        .filter { !it.responseBody.isNullOrBlank() }
        .joinToString("\n\n---\n\n") { a ->
            "## ${com.ai.ui.shared.shortModelName(a.model)}\n${a.responseBody}"
        }
    val metaName = row.metaPromptName?.takeIf { it.isNotBlank() } ?: "meta-analysis"
    val systemPrompt = buildString {
        appendLine("You are continuing a multi-model analysis from one of the user's reports.")
        appendLine()
        appendLine("ORIGINAL PROMPT")
        appendLine(report.prompt)
        appendLine()
        appendLine("MODEL RESPONSES")
        appendLine(responsesBlock)
        appendLine()
        append("You produced the \"$metaName\" analysis shown to the user as your first message. ")
        append("Answer the user's follow-up questions about it — drill into specifics, ")
        append("justify or revise points, and compare the responses as asked.")
    }

    val now = System.currentTimeMillis()
    val session = com.ai.data.ChatSession(
        provider = provider,
        model = model,
        messages = listOf(
            com.ai.data.ChatMessage(role = "assistant", content = metaBody, timestamp = now)
        ),
        parameters = com.ai.data.ChatParameters(systemPrompt = systemPrompt),
        createdAt = now,
        updatedAt = now,
        title = "💬 $metaName"
    )
    if (com.ai.data.ChatHistoryManager.saveSession(session)) session.id else null
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
    // Provide the active/last-viewed report's icon so the View top bar's
    // left report-glyph has something to show on report-agnostic screens
    // (Model Info, provider/flock/swarm info, …). Null when no report is
    // active → the bar falls back to a neutral glyph.
    val iconCtx = androidx.compose.ui.platform.LocalContext.current
    val reportIconState = androidx.compose.runtime.produceState<String?>(null, currentReportId) {
        value = currentReportId?.let { rid ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.ai.data.ReportStorage.getReport(iconCtx, rid)?.icon
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalNavigateToCurrentReport provides navToActiveView,
        com.ai.ui.shared.LocalReportIcon provides reportIconState.value
    ) {
        content()
    }
}
