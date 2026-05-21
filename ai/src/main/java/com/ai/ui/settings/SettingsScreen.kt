package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.GeneralSettings

enum class SettingsSubScreen {
    MAIN, AI_PROVIDER_EDIT, AI_SETUP,
    AI_PROVIDERS,
    AI_MODELS_SETUP,
    AI_MODELS, AI_MODEL_EDIT,
    AI_MODEL_TYPES,
    AI_MANUAL_MODEL_TYPES,
    AI_WORKERS_SETUP,
    AI_AGENTS, AI_AGENT_EDIT,
    AI_FLOCKS, AI_FLOCK_EDIT,
    AI_SWARMS, AI_SWARM_EDIT,
    AI_PARAMETERS,
    AI_SYSTEM_PROMPTS, AI_SYSTEM_PROMPT_EDIT,
    AI_FAN_PROMPTS_HUB,
    AI_INTERNAL_PROMPTS, AI_INTERNAL_PROMPT_EDIT,
    AI_EXAMPLE_PROMPTS, AI_EXAMPLE_PROMPT_EDIT,
    AI_EXTERNAL_SERVICES,
    AI_PROMPTS_SETUP,
    AI_INTERNAL_PROMPTS_HUB,
    AI_LOCAL_MODELS_SETUP,
    AI_LOCAL_LITERT_MODELS,
    AI_LOCAL_LLMS,
    AI_MODEL_COOLDOWNS,
    AI_BLOCKED_MODELS,
    AI_TEST_EXCLUDED_MODELS,
    AI_INACCESSIBLE_MODELS,
    AI_IMPORT_EXPORT,
    AI_REFRESH,
    // Four preference buckets carved out of the main Settings screen
    // so the top page stays a short nav list. Each sub-screen owns
    // its own help topic and renders only the cards from its bucket.
    SETTINGS_NETWORK,
    SETTINGS_NETWORK_API_CALLS,
    SETTINGS_UI,
    SETTINGS_LOGGING,
    SETTINGS_OTHER
}

@Composable
fun SettingsScreen(
    generalSettings: GeneralSettings,
    aiSettings: Settings,
    loadingModelsFor: Set<AppService> = emptySet(),
    fetchModelsErrors: Map<String, com.ai.viewmodel.FetchModelsError> = emptyMap(),
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onSaveAi: (Settings) -> Unit,
    onFetchModels: (AppService, String) -> Unit = { _, _ -> },
    onFetchModelsAwait: suspend (AppService, String) -> String? = { _, _ -> null },
    onTestAiModel: suspend (AppService, String, String) -> String? = { _, _, _ -> null },
    onProviderStateChange: (AppService, String) -> Unit = { _, _ -> },
    onProviderTestedOk: (AppService, String) -> Unit = { _, _ -> },
    onProviderTestedOkNoFetch: (AppService, String) -> Unit = onProviderTestedOk,
    onReplaceDefaultAgent: (AppService, String) -> Unit = { _, _ -> },
    onRefreshAllModels: suspend (Settings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onSaveArtificialAnalysisApiKey: (String) -> Unit = {},
    onNavigateToCostConfig: () -> Unit = {},
    onTestModelWithPrompt: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onTestSpecificModel: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    refreshAllState: com.ai.viewmodel.RefreshAllState? = null,
    onStartRefreshAll: () -> Unit = {},
    onStartRefreshWorkers: () -> Unit = {},
    onClearRefreshAllState: () -> Unit = {},
    /** Replace the current sub-screen with the Refresh page. Used by
     *  the post-API-keys-import dialog so "Run Refresh all" lands the
     *  user on the progress overlay it just kicked off. */
    onNavigateToRefresh: () -> Unit = {},
    onNavigateToHelpTopic: (String) -> Unit = {},
    /** Optional 👁-bar hooks wired from AppNavHost to the matching
     *  View screen route. When set, the Agent / Flock / Swarm Edit
     *  screens render a 👁 in their bottom bar; tap navigates to the
     *  read-only View sibling and back returns here via Jetpack Nav.
     *  Default no-op keeps the icon hidden on legacy call sites. */
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
    // rememberSaveable so a navigation hop OUT of Settings and back
    // (e.g. tapping a per-card ❓ that opens HelpScreen) restores the
    // user to the same sub-screen they left, instead of resetting to
    // the initial entry point. SettingsSubScreen is an enum →
    // Bundle's serializable saver handles it.
    var currentSubScreen by rememberSaveable { mutableStateOf(initialSubScreen) }
    // Hold the runtime selection as the AppService id (a String) so a
    // mutating ProviderRegistry doesn't blow it away. The previous
    // approach keyed remember on AppService.entries.size to "re-resolve
    // post-bootstrap", but that ALSO re-resolved on Add provider /
    // Import / Reset — which silently dropped the user's runtime
    // selection back to initialProviderId, bouncing them out of any
    // open provider edit. Now: store the id, look the AppService up
    // lazily by id below. The cold-launch race the previous comment
    // worried about is handled by the lookup returning null until
    // bootstrap finishes, then succeeding on the next recomposition.
    // rememberSaveable so the AI_PROVIDER_EDIT sub-screen knows
    // which provider to show after a navigation hop back into
    // Settings (per-card ❓ → HelpScreen → back).
    var selectedProviderId by rememberSaveable(initialProviderId) {
        mutableStateOf(initialProviderId)
    }
    val selectedProvider: AppService? = selectedProviderId?.let { AppService.findById(it) }
    var editingAgentId by remember { mutableStateOf(initialEditingAgentId) }
    var editingFlockId by remember { mutableStateOf(initialEditingFlockId) }
    var editingSwarmId by remember { mutableStateOf(initialEditingSwarmId) }
    var editingSystemPromptId by remember { mutableStateOf<String?>(null) }
    var editingInternalPromptId by remember { mutableStateOf(initialEditingInternalPromptId) }
    var editingExamplePromptId by remember { mutableStateOf<String?>(null) }
    // "providerId:model" key of the blocked-model row being edited;
    // null = adding a new one.
    // Which Internal Prompts CRUD bucket is currently open. Set by the
    // four cards on Prompt Management; the AI_INTERNAL_PROMPTS list
    // and AI_INTERNAL_PROMPT_EDIT screens filter / pin on it. When the
    // caller deep-links into AI_INTERNAL_PROMPT_EDIT (e.g. Fan out L1's
    // "Edit the used Fan out prompt") we derive the bucket from the
    // prompt being edited so the edit screen pins the right category.
    var selectedInternalCategory by remember {
        mutableStateOf(
            initialInternalPromptCategory
                ?: initialEditingInternalPromptId
                    ?.let { aiSettings.getInternalPromptById(it) }
                    ?.category
                ?: "internal"
        )
    }
    // Once the deep-linked prompt resolves (settings load is async on
    // cold start), pin the category to the prompt's actual bucket. The
    // initial `remember` runs once when settings are still empty —
    // without this LaunchedEffect a deep-link into a "meta" prompt
    // would save back as "internal".
    LaunchedEffect(initialEditingInternalPromptId, aiSettings) {
        if (initialInternalPromptCategory == null && initialEditingInternalPromptId != null) {
            val resolved = aiSettings.getInternalPromptById(initialEditingInternalPromptId)?.category
            if (resolved != null && resolved != selectedInternalCategory) {
                selectedInternalCategory = resolved
            }
        }
    }
    // Tracks whether the user entered AI_MODEL_EDIT via the Providers → Models link, so
    // pressing back returns to the provider edit rather than the Models list.
    var modelEditFromProvider by remember { mutableStateOf(false) }
    // Providers-list scroll position — hoisted to SettingsScreen because
    // the sub-screen `when` block destroys ProvidersScreen's composition
    // entirely on navigation into AI_PROVIDER_EDIT, throwing any
    // rememberScrollState there away. SettingsScreen itself survives the
    // switch, so a remember here keeps the list scrolled where the user
    // left it. ScrollState.Saver also keeps it across process death.
    val providersListScrollState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.ScrollState.Saver
    ) { androidx.compose.foundation.ScrollState(0) }

    val goBack: () -> Unit = goBack@ {
        // If the user landed directly on a deep-linked sub-screen (e.g. opened
        // Export/Import or Refresh from Housekeeping), back from that screen
        // should exit to the caller, not climb the Settings hierarchy.
        if (currentSubScreen == initialSubScreen && initialSubScreen != SettingsSubScreen.MAIN) {
            onBack(); return@goBack
        }
        when (currentSubScreen) {
            SettingsSubScreen.MAIN -> onBack()
            SettingsSubScreen.AI_SETUP -> if (initialSubScreen == SettingsSubScreen.AI_SETUP) onBack() else currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.AI_PROVIDER_EDIT -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            SettingsSubScreen.AI_MODEL_EDIT -> {
                val from = modelEditFromProvider
                modelEditFromProvider = false
                currentSubScreen = if (from) SettingsSubScreen.AI_PROVIDER_EDIT else SettingsSubScreen.AI_MODELS
            }
            SettingsSubScreen.AI_MODELS, SettingsSubScreen.AI_MODEL_TYPES,
            SettingsSubScreen.AI_MANUAL_MODEL_TYPES -> currentSubScreen = SettingsSubScreen.AI_MODELS_SETUP
            SettingsSubScreen.AI_AGENTS, SettingsSubScreen.AI_FLOCKS,
            SettingsSubScreen.AI_SWARMS -> currentSubScreen = SettingsSubScreen.AI_WORKERS_SETUP
            SettingsSubScreen.AI_SYSTEM_PROMPTS,
            SettingsSubScreen.AI_EXAMPLE_PROMPTS -> currentSubScreen = SettingsSubScreen.AI_PROMPTS_SETUP
            // The Internal prompts hub now sits between Prompt
            // management and the per-category lists; the Fan out/in
            // sub-hub is a child of the Internal prompts hub.
            SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB -> currentSubScreen = SettingsSubScreen.AI_PROMPTS_SETUP
            SettingsSubScreen.AI_FAN_PROMPTS_HUB -> currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB
            // Back from a per-category list lands on whichever hub
            // owns that category — Fan out/in for any of the fan-*
            // buckets, Internal prompts for meta / internal / icons.
            // selectedInternalCategory is set when the list is opened,
            // so it's authoritative here.
            SettingsSubScreen.AI_INTERNAL_PROMPTS -> currentSubScreen =
                if (selectedInternalCategory in setOf("fan_out", "fan_in", "fan-in-model"))
                    SettingsSubScreen.AI_FAN_PROMPTS_HUB
                else
                    SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB
            SettingsSubScreen.AI_LOCAL_LITERT_MODELS,
            SettingsSubScreen.AI_LOCAL_LLMS -> currentSubScreen = SettingsSubScreen.AI_LOCAL_MODELS_SETUP
            SettingsSubScreen.AI_PROVIDERS,
            SettingsSubScreen.AI_MODELS_SETUP,
            SettingsSubScreen.AI_WORKERS_SETUP,
            SettingsSubScreen.AI_PROMPTS_SETUP,
            SettingsSubScreen.AI_LOCAL_MODELS_SETUP,
            SettingsSubScreen.AI_PARAMETERS,
            SettingsSubScreen.AI_EXTERNAL_SERVICES,
            SettingsSubScreen.AI_MODEL_COOLDOWNS,
            SettingsSubScreen.AI_BLOCKED_MODELS,
            SettingsSubScreen.AI_TEST_EXCLUDED_MODELS,
            SettingsSubScreen.AI_INACCESSIBLE_MODELS,
            SettingsSubScreen.AI_IMPORT_EXPORT, SettingsSubScreen.AI_REFRESH -> currentSubScreen = SettingsSubScreen.AI_SETUP
            SettingsSubScreen.AI_AGENT_EDIT -> { editingAgentId = null; currentSubScreen = SettingsSubScreen.AI_AGENTS }
            SettingsSubScreen.AI_FLOCK_EDIT -> { editingFlockId = null; currentSubScreen = SettingsSubScreen.AI_FLOCKS }
            SettingsSubScreen.AI_SWARM_EDIT -> { editingSwarmId = null; currentSubScreen = SettingsSubScreen.AI_SWARMS }
            SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT -> { editingSystemPromptId = null; currentSubScreen = SettingsSubScreen.AI_SYSTEM_PROMPTS }
            SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT -> { editingInternalPromptId = null; currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS }
            SettingsSubScreen.AI_EXAMPLE_PROMPT_EDIT -> { editingExamplePromptId = null; currentSubScreen = SettingsSubScreen.AI_EXAMPLE_PROMPTS }
            SettingsSubScreen.SETTINGS_NETWORK,
            SettingsSubScreen.SETTINGS_UI,
            SettingsSubScreen.SETTINGS_LOGGING,
            SettingsSubScreen.SETTINGS_OTHER -> currentSubScreen = SettingsSubScreen.MAIN
            SettingsSubScreen.SETTINGS_NETWORK_API_CALLS ->
                currentSubScreen = SettingsSubScreen.SETTINGS_NETWORK
        }
    }

    BackHandler { goBack() }

    // 🧹 jump targets for sub-screens with a clear Housekeeping
    // counterpart (Models setup / Providers → Refresh; Test-excluded /
    // Inaccessible models → Test all models). Navigates by route via the
    // AppNavHost-provided local, so no per-mount prop-drilling.
    val navHk = com.ai.ui.shared.LocalNavigateToHousekeeping.current
    val hkRefresh = { navHk(com.ai.ui.navigation.NavRoutes.AI_REFRESH) }
    val hkTest = { navHk(com.ai.ui.navigation.NavRoutes.AI_TEST) }

    when (currentSubScreen) {
        SettingsSubScreen.MAIN -> {
            SettingsMainScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = onBack, onNavigateHome = onNavigateHome,
                onOpenSubScreen = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.AI_SETUP -> {
            SetupScreen(
                aiSettings = aiSettings,
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                aaApiKey = generalSettings.artificialAnalysisApiKey,
                onBackToSettings = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }, onSave = onSaveAi,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveArtificialAnalysisApiKey = onSaveArtificialAnalysisApiKey,
                onNavigateToCostConfig = onNavigateToCostConfig
            )
        }
        SettingsSubScreen.AI_PROVIDERS -> {
            ProvidersScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome,
                scrollState = providersListScrollState,
                onHousekeeping = hkRefresh,
                onProviderSelected = { selectedProviderId = it.id; currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT },
                onAddProvider = { name ->
                    // Stub provider — every other field is empty / default;
                    // the user fills the rest in on the existing edit
                    // screen (single source of truth, including the
                    // SelectModelScreen entry for default model).
                    val service = com.ai.data.AppService(
                        id = name, baseUrl = "", adminUrl = "", defaultModel = ""
                    )
                    if (com.ai.data.ProviderRegistry.add(service)) {
                        selectedProviderId = name
                        currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
                    }
                }
            )
        }
        SettingsSubScreen.AI_PROVIDER_EDIT -> {
            selectedProvider?.let { provider ->
                ProviderSettingsScreen(
                    service = provider, aiSettings = aiSettings,
                    isLoadingModels = provider in loadingModelsFor,
                    fetchError = fetchModelsErrors[provider.id],
                    onBackToSettings = goBack, onBackToHome = onNavigateHome,
                    onSave = onSaveAi,
                    onFetchModels = {
                        val fresh = AppService.findById(provider.id) ?: provider
                        onFetchModels(fresh, it)
                    },
                    onFetchModelsAwait = { svc, key -> onFetchModelsAwait(svc, key) },
                    onTestApiKey = onTestAiModel, onProviderStateChange = { onProviderStateChange(provider, it) },
                    onProviderTestedOk = { defaultModel -> onProviderTestedOk(provider, defaultModel) },
                    onProviderTestedOkNoFetch = { defaultModel -> onProviderTestedOkNoFetch(provider, defaultModel) },
                    onReplaceDefaultAgent = { defaultModel -> onReplaceDefaultAgent(provider, defaultModel) },
                    onTestModelWithPrompt = { prompt ->
                        val fresh = AppService.findById(provider.id) ?: provider
                        onTestModelWithPrompt(fresh, aiSettings.getApiKey(fresh), aiSettings.getModel(fresh), prompt)
                    },
                    onNavigateToTrace = onNavigateToTrace,
                    onNavigateToModels = {
                        // Jump directly into the Models sub-screen for this provider; back returns here.
                        modelEditFromProvider = true
                        currentSubScreen = SettingsSubScreen.AI_MODEL_EDIT
                    },
                    onNavigateToHelpTopic = onNavigateToHelpTopic
                )
            } ?: goBack()
        }
        SettingsSubScreen.AI_MODELS -> {
            ModelsListScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome,
                onProviderSelected = { selectedProviderId = it.id; currentSubScreen = SettingsSubScreen.AI_MODEL_EDIT },
                onRefreshAllModels = onRefreshAllModels
            )
        }
        SettingsSubScreen.AI_MODEL_EDIT -> {
            selectedProvider?.let { provider ->
                ProviderModelSettingsScreen(
                    service = provider, aiSettings = aiSettings,
                    isLoadingModels = provider in loadingModelsFor,
                    fetchError = fetchModelsErrors[provider.id],
                    onBack = goBack, onBackToHome = onNavigateHome,
                    onSave = onSaveAi,
                    onFetchModels = {
                        // Use the registry's current AppService so a baseUrl edit on
                        // the catalog flows through immediately.
                        val fresh = AppService.findById(provider.id) ?: provider
                        onFetchModels(fresh, it)
                    },
                    onNavigateToModelInfo = onNavigateToModelInfo,
                    onTestSpecificModel = { model, prompt ->
                        val fresh = AppService.findById(provider.id) ?: provider
                        onTestSpecificModel(fresh, aiSettings.getApiKey(fresh), model, prompt)
                    },
                    onNavigateToTrace = onNavigateToTrace
                )
            } ?: goBack()
        }
        SettingsSubScreen.AI_MODELS_SETUP -> {
            ModelsSetupScreen(
                aiSettings = aiSettings,
                hasActiveProvider = aiSettings.getActiveServices().isNotEmpty(),
                experimentalFeatures = generalSettings.experimentalFeaturesEnabled,
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it },
                onHousekeeping = hkRefresh
            )
        }
        SettingsSubScreen.AI_WORKERS_SETUP -> {
            WorkersSetupScreen(
                aiSettings = aiSettings,
                hasApiKey = aiSettings.hasAnyApiKey(),
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.AI_PROMPTS_SETUP -> {
            PromptsSetupScreen(
                aiSettings = aiSettings,
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it },
                onOpenInternalPromptsHub = { currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB }
            )
        }
        SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB -> {
            InternalPromptsHubScreen(
                aiSettings = aiSettings,
                onBack = goBack, onBackToHome = onNavigateHome,
                onOpenInternalPrompts = { cat ->
                    selectedInternalCategory = cat
                    currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS
                },
                onOpenFanInOutHub = { currentSubScreen = SettingsSubScreen.AI_FAN_PROMPTS_HUB }
            )
        }
        SettingsSubScreen.AI_FAN_PROMPTS_HUB -> {
            FanInOutPromptsHubScreen(
                aiSettings = aiSettings,
                onBack = goBack, onBackToHome = onNavigateHome,
                onOpenInternalPrompts = { cat ->
                    selectedInternalCategory = cat
                    currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS
                }
            )
        }
        SettingsSubScreen.AI_LOCAL_MODELS_SETUP -> {
            LocalModelsSetupScreen(
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.AI_MODEL_TYPES -> {
            ModelTypesScreen(
                generalSettings = generalSettings,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onSave = onSaveGeneral
            )
        }
        SettingsSubScreen.AI_MANUAL_MODEL_TYPES -> {
            com.ai.ui.cruds.models.manualoverrides.ManualOverridesCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_MODEL_COOLDOWNS -> {
            com.ai.ui.cruds.models.cooldowns.ModelCooldownsCrud(
                aiSettings = aiSettings,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onNavigateToTrace = onNavigateToTrace
            )
        }
        SettingsSubScreen.AI_BLOCKED_MODELS -> {
            com.ai.ui.cruds.models.blocked.BlockedModelsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_TEST_EXCLUDED_MODELS -> {
            com.ai.ui.cruds.models.testexcluded.TestExcludedModelsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onHousekeeping = hkTest
            )
        }
        SettingsSubScreen.AI_INACCESSIBLE_MODELS -> {
            com.ai.ui.cruds.models.inaccessible.InaccessibleModelsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onHousekeeping = hkTest
            )
        }
        SettingsSubScreen.AI_AGENTS -> {
            com.ai.ui.cruds.workers.agents.AgentsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome,
                deps = com.ai.ui.cruds.workers.agents.AgentEditDeps(
                    onTestAiModel = onTestAiModel,
                    onFetchModels = onFetchModels,
                    loadingModelsFor = loadingModelsFor,
                    fetchModelsErrors = fetchModelsErrors,
                    onNavigateToTrace = onNavigateToTrace,
                    onAddEndpoint = { provider, ep ->
                        val current = aiSettings.getEndpointsForProvider(provider)
                        onSaveAi(aiSettings.withEndpoints(provider, current + ep))
                    }
                )
            )
        }
        SettingsSubScreen.AI_AGENT_EDIT -> {
            // Deep-link entry (SETTINGS_AGENT_EDIT route) — the AI_AGENTS
            // list itself uses AgentsCrud's own internal edit overlay.
            val agent = editingAgentId?.let { aiSettings.getAgentById(it) }
            AgentEditScreen(
                agent = agent, aiSettings = aiSettings,
                existingNames = aiSettings.agents.filter { it.id != (agent?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onTestAiModel = onTestAiModel, onFetchModels = onFetchModels,
                onSave = { saved ->
                    val updated = if (agent != null) aiSettings.copy(agents = aiSettings.agents.map { if (it.id == agent.id) saved else it })
                    else aiSettings.copy(agents = aiSettings.agents + saved)
                    onSaveAi(updated); goBack()
                },
                onAddEndpoint = { provider, ep ->
                    val current = aiSettings.getEndpointsForProvider(provider)
                    onSaveAi(aiSettings.withEndpoints(provider, current + ep))
                },
                onBack = goBack, onNavigateHome = onNavigateHome,
                loadingModelsFor = loadingModelsFor,
                fetchModelsErrors = fetchModelsErrors,
                onNavigateToTrace = onNavigateToTrace,
                onOpenView = agent?.id?.let { aid ->
                    onNavigateToAgentView?.let { { it(aid) } }
                }
            )
        }
        SettingsSubScreen.AI_FLOCKS -> {
            com.ai.ui.cruds.workers.flocks.FlocksCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_FLOCK_EDIT -> {
            val flock = editingFlockId?.let { aiSettings.getFlockById(it) }
            FlockEditScreen(
                flock = flock, aiSettings = aiSettings,
                existingNames = aiSettings.flocks.filter { it.id != (flock?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onSave = { saved ->
                    val updated = if (flock != null) aiSettings.copy(flocks = aiSettings.flocks.map { if (it.id == flock.id) saved else it })
                    else aiSettings.copy(flocks = aiSettings.flocks + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome,
                onOpenView = flock?.id?.let { fid ->
                    onNavigateToFlockView?.let { { it(fid) } }
                }
            )
        }
        SettingsSubScreen.AI_SWARMS -> {
            com.ai.ui.cruds.workers.swarms.SwarmsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SWARM_EDIT -> {
            val swarm = editingSwarmId?.let { aiSettings.getSwarmById(it) }
            SwarmEditScreen(
                swarm = swarm, aiSettings = aiSettings,
                existingNames = aiSettings.swarms.filter { it.id != (swarm?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onSave = { saved ->
                    val updated = if (swarm != null) aiSettings.copy(swarms = aiSettings.swarms.map { if (it.id == swarm.id) saved else it })
                    else aiSettings.copy(swarms = aiSettings.swarms + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome,
                onOpenView = swarm?.id?.let { sid ->
                    onNavigateToSwarmView?.let { { it(sid) } }
                }
            )
        }
        SettingsSubScreen.AI_PARAMETERS -> {
            com.ai.ui.cruds.parameters.ParametersCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SYSTEM_PROMPTS -> {
            com.ai.ui.cruds.prompts.system.SystemPromptsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT -> {
            val sp = editingSystemPromptId?.let { aiSettings.getSystemPromptById(it) }
            SystemPromptEditScreen(
                systemPrompt = sp,
                existingNames = aiSettings.systemPrompts.filter { it.id != (sp?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onSave = { saved ->
                    val updated = if (sp != null) aiSettings.copy(systemPrompts = aiSettings.systemPrompts.map { if (it.id == sp.id) saved else it })
                    else aiSettings.copy(systemPrompts = aiSettings.systemPrompts + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_INTERNAL_PROMPTS -> {
            com.ai.ui.cruds.prompts.internal.InternalPromptCrud(
                aiSettings = aiSettings,
                category = selectedInternalCategory,
                onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_EXAMPLE_PROMPTS -> {
            com.ai.ui.cruds.prompts.examples.ExamplePromptsCrud(
                aiSettings = aiSettings, onSave = onSaveAi,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_EXAMPLE_PROMPT_EDIT -> {
            val ep = editingExamplePromptId?.let { aiSettings.getExamplePromptById(it) }
            key(ep?.id) {
                ExamplePromptEditScreen(
                    examplePrompt = ep,
                    onSave = { saved ->
                        val updated = if (ep != null) aiSettings.copy(examplePrompts = aiSettings.examplePrompts.map { if (it.id == ep.id) saved else it })
                        else aiSettings.copy(examplePrompts = aiSettings.examplePrompts + saved)
                        onSaveAi(updated); goBack()
                    },
                    onBack = goBack, onNavigateHome = onNavigateHome
                )
            }
        }
        SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT -> {
            val ip = editingInternalPromptId?.let { aiSettings.getInternalPromptById(it) }
            // Deep-link safety: when the caller asked to edit a
            // specific id but aiSettings hasn't bootstrapped yet, ip
            // resolves to null. The InternalPromptEditScreen captures
            // its initial state via remember{} on first composition,
            // so an empty form shown here would silently create a
            // duplicate prompt on Save once the user typed a name.
            // Treat the not-yet-loaded case as a transient loading
            // state — InternalPromptEditScreen is keyed on ip?.id so
            // it re-initialises when the lookup resolves.
            if (editingInternalPromptId != null && ip == null) {
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
                ) {
                    TitleBar(helpTopic = "settings_main", title = "Loading…", onBackClick = goBack)
                }
            } else {
                key(ip?.id) {
                    InternalPromptEditScreen(
                        internalPrompt = ip,
                        // Names are unique within a category, not across all
                        // internal prompts — so "Compare" under meta and
                        // "Compare" under fan_in can coexist.
                        existingNames = aiSettings.internalPrompts
                            .filter { it.id != (ip?.id ?: "") && it.category == selectedInternalCategory }
                            .map { it.name.lowercase(java.util.Locale.ROOT) }
                            .toSet(),
                        agentNames = aiSettings.agents.map { it.name },
                        fixedCategory = selectedInternalCategory,
                        onSave = { saved ->
                            val updated = if (ip != null) aiSettings.copy(internalPrompts = aiSettings.internalPrompts.map { if (it.id == ip.id) saved else it })
                            else aiSettings.copy(internalPrompts = aiSettings.internalPrompts + saved)
                            onSaveAi(updated); goBack()
                        },
                        onBack = goBack, onNavigateHome = onNavigateHome
                    )
                }
            }
        }
        SettingsSubScreen.AI_EXTERNAL_SERVICES -> {
            ExternalServicesScreen(
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveArtificialAnalysisApiKey = onSaveArtificialAnalysisApiKey,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onNavigateToHelpTopic = onNavigateToHelpTopic
            )
        }
        SettingsSubScreen.AI_LOCAL_LITERT_MODELS -> {
            LocalLiteRtModelsScreen(onBack = goBack, onNavigateHome = onNavigateHome)
        }
        SettingsSubScreen.AI_LOCAL_LLMS -> {
            LocalLlmsScreen(onBack = goBack, onNavigateHome = onNavigateHome)
        }
        SettingsSubScreen.AI_IMPORT_EXPORT -> {
            // Fold to Import-only when no provider is active yet —
            // there's nothing meaningful to export at that point, but
            // Import from another install is exactly the use case.
            // Same condition Housekeeping uses for its rename.
            val importOnly = aiSettings.getActiveServices().isEmpty()
            ImportExportScreen(
                aiSettings = aiSettings,
                generalSettings = generalSettings,
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSave = onSaveAi,
                onSaveGeneral = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome,
                importOnly = importOnly,
                onStartRefreshAll = onStartRefreshAll,
                onStartRefreshWorkers = onStartRefreshWorkers,
                onNavigateToRefresh = onNavigateToRefresh
            )
        }
        SettingsSubScreen.AI_REFRESH -> {
            RefreshScreen(
                aiSettings = aiSettings,
                openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSave = onSaveAi,
                refreshAllState = refreshAllState,
                onStartRefreshAll = onStartRefreshAll,
                onStartRefreshWorkers = onStartRefreshWorkers,
                onClearRefreshAllState = onClearRefreshAllState,
                onOpenProvider = { svc ->
                    selectedProviderId = svc.id
                    currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
                },
                onNavigateToHelpTopic = onNavigateToHelpTopic,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_NETWORK -> {
            NetworkSettingsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onOpenSubScreen = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.SETTINGS_NETWORK_API_CALLS -> {
            MaximalApiCallsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_UI -> {
            UiTweaksSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_LOGGING -> {
            LoggingAndTracingSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.SETTINGS_OTHER -> {
            OtherSettingsSubScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
    }
}

// ===== Main Settings Screen (General) =====

@Composable
private fun SettingsMainScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSubScreen: (SettingsSubScreen) -> Unit = {}
) {
    // No local preference state on the main screen any more — every
    // editable card lives in one of the four sub-screens reached via
    // the nav rows below.
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_main", title = "Settings", onBackClick = onBack)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Nav rows into the four preference buckets. Each opens
            // its own full-screen sub-screen with its own TitleBar +
            // help topic. The main Settings page is now purely a
            // table-of-contents — every actual control lives one tap
            // deeper.
            SettingsNavCard(
                icon = "🌐",
                title = "Network settings",
                description = "Read timeouts, per-provider throttling, 429 / 529 retry policy.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_NETWORK) }
            )
            SettingsNavCard(
                icon = "🎨",
                title = "UI tweaks",
                description = "Model name layout, full-screen, experimental features.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_UI) }
            )
            SettingsNavCard(
                icon = "📜",
                title = "Logging and tracing",
                description = "API tracing master switch and application log level.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_LOGGING) }
            )
            SettingsNavCard(
                icon = "⚙️",
                title = "Other settings",
                description = "Identity (Name + Email) and Generate report icons.",
                onClick = { onOpenSubScreen(SettingsSubScreen.SETTINGS_OTHER) }
            )
        }
    }
}

/** Tap-target row used on the main Settings screen to drill into a
 *  preference sub-screen. Visual style mirrors SetupNavCard so the
 *  navigation pattern feels consistent across the hub-style screens. */
@Composable
private fun SettingsNavCard(
    icon: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(description, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
            Text(">", fontSize = 16.sp, color = AppColors.Blue)
        }
    }
}

// ===== Carved-out preference buckets =====
//
// Each sub-screen owns its slice of GeneralSettings: it mirrors the
// fields it cares about into local state, debounces saves through
// the same 400ms pattern the main screen uses, and flushes any
// pending edit on dispose so a quick back-tap doesn't lose the
// last keystroke. The other fields on the parent GeneralSettings
// flow through unchanged via .copy(), so the three sub-screens
// don't clobber each other even when the user navigates between
// them quickly.

/** Network read timeouts + per-provider throttling + per-provider
 *  retries. Each field stored as text so partial / empty edits
 *  don't fight the keystroke; parsed back via toIntOrNull /
 *  toLongOrNull on save, with the previous value preserved when
 *  the field is blank or non-numeric. */
@Composable
private fun NetworkSettingsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenSubScreen: (SettingsSubScreen) -> Unit
) {
    var streamingReadTimeoutText by remember {
        mutableStateOf(generalSettings.streamingReadTimeoutSec.toString())
    }
    var nonStreamingReadTimeoutText by remember {
        mutableStateOf(generalSettings.nonStreamingReadTimeoutSec.toString())
    }
    var maxCallsPerMinuteText by remember {
        mutableStateOf(generalSettings.maxCallsPerProviderPerMinute.toString())
    }
    var maxConcurrentCallsText by remember {
        mutableStateOf(generalSettings.maxConcurrentCallsPerProvider.toString())
    }
    var maxRetriesText by remember {
        mutableStateOf(generalSettings.maxRetriesOn429.toString())
    }
    var retryBackoffMs429Text by remember {
        mutableStateOf(generalSettings.retryBackoffMs429.toString())
    }
    var maxRetries529Text by remember {
        mutableStateOf(generalSettings.maxRetriesOn529.toString())
    }
    var retryBackoffMs529Text by remember {
        mutableStateOf(generalSettings.retryBackoffMs529.toString())
    }

    fun build(): GeneralSettings = generalSettings.copy(
        streamingReadTimeoutSec = streamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.streamingReadTimeoutSec,
        nonStreamingReadTimeoutSec = nonStreamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.nonStreamingReadTimeoutSec,
        maxCallsPerProviderPerMinute = maxCallsPerMinuteText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxCallsPerProviderPerMinute,
        maxConcurrentCallsPerProvider = maxConcurrentCallsText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentCallsPerProvider,
        // 0 is a valid maxRetries setting (no in-line retries) — coerce ≥ 0.
        maxRetriesOn429 = maxRetriesText.toIntOrNull()?.coerceAtLeast(0)
            ?: generalSettings.maxRetriesOn429,
        retryBackoffMs429 = retryBackoffMs429Text.toLongOrNull()?.coerceAtLeast(1L)
            ?: generalSettings.retryBackoffMs429,
        maxRetriesOn529 = maxRetries529Text.toIntOrNull()?.coerceAtLeast(0)
            ?: generalSettings.maxRetriesOn529,
        retryBackoffMs529 = retryBackoffMs529Text.toLongOrNull()?.coerceAtLeast(1L)
            ?: generalSettings.retryBackoffMs529
    )

    LaunchedEffect(
        streamingReadTimeoutText, nonStreamingReadTimeoutText,
        maxCallsPerMinuteText, maxConcurrentCallsText,
        maxRetriesText, retryBackoffMs429Text,
        maxRetries529Text, retryBackoffMs529Text
    ) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_network", title = "Network settings", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Nav row to the Maximal API calls sub-screen. Styled like
            // SettingCard (same surface, title weight, chevron) so it
            // sits in visually with the other cards on this page —
            // tapping opens the deeper screen instead of expanding.
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onOpenSubScreen(SettingsSubScreen.SETTINGS_NETWORK_API_CALLS)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Maximal API calls",
                            fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▸", color = AppColors.TextTertiary)
                    }
                }
            }
            SettingCard(
                "Network read timeouts",
                "How long the app waits for an API response before giving up. Streaming applies to chat / report SSE streams (the timeout is the gap between chunks, so the long default is normal). Non-streaming applies to analyze, meta, rerank, fetch-models, translate — everything that blocks for the full response body. Provider-test calls always cap at 30 s regardless."
            ) {
                OutlinedTextField(
                    value = streamingReadTimeoutText,
                    onValueChange = { streamingReadTimeoutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Streaming (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = nonStreamingReadTimeoutText,
                    onValueChange = { nonStreamingReadTimeoutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Non-streaming (seconds)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Per-provider throttling",
                "Caps the load the app puts on any single provider. Calls beyond the per-minute rate sleep until the sliding window opens up; concurrent calls beyond the cap queue on a per-host semaphore. Defaults: 30 calls/minute, 3 in flight at once."
            ) {
                OutlinedTextField(
                    value = maxCallsPerMinuteText,
                    onValueChange = { maxCallsPerMinuteText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max calls per provider per minute") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = maxConcurrentCallsText,
                    onValueChange = { maxConcurrentCallsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max concurrent calls per provider") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "429 error handling",
                "When a provider answers HTTP 429 (rate-limited), the OkHttp client waits and re-issues the same request up to this many times. Set retries to 0 to disable in-line retries entirely (the outer retry layer still gets a chance on transient 4xx). Defaults: 3 retries, 1000 ms between each."
            ) {
                OutlinedTextField(
                    value = maxRetriesText,
                    onValueChange = { maxRetriesText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max retries on 429") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = retryBackoffMs429Text,
                    onValueChange = { retryBackoffMs429Text = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Wait between retries (ms)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "529 error handling",
                "When a provider answers HTTP 529 (server overloaded), the OkHttp client waits and re-issues the same request up to this many times. Set retries to 0 to disable in-line retries entirely (the outer retry layer still gets a chance on transient 5xx). Defaults: 3 retries, 1000 ms between each."
            ) {
                OutlinedTextField(
                    value = maxRetries529Text,
                    onValueChange = { maxRetries529Text = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max retries on 529") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = retryBackoffMs529Text,
                    onValueChange = { retryBackoffMs529Text = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Wait between retries (ms)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}

/** Maximal API calls — global + per-kind concurrency caps. Sits
 *  one tap deeper than Network settings; each field flows into
 *  [com.ai.data.ApiCallCaps] via [updateGeneralSettings]. */
@Composable
private fun MaximalApiCallsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var apiText by remember { mutableStateOf(generalSettings.maxConcurrentApiCalls.toString()) }
    var reportText by remember { mutableStateOf(generalSettings.maxConcurrentReportCalls.toString()) }
    var translationText by remember { mutableStateOf(generalSettings.maxConcurrentTranslationCalls.toString()) }
    var fanOutText by remember { mutableStateOf(generalSettings.maxConcurrentFanOutCalls.toString()) }
    var fanIconsText by remember { mutableStateOf(generalSettings.maxConcurrentFanIconsCalls.toString()) }
    var testText by remember { mutableStateOf(generalSettings.maxTestApiCalls.toString()) }

    fun build(): GeneralSettings = generalSettings.copy(
        maxConcurrentApiCalls = apiText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentApiCalls,
        maxConcurrentReportCalls = reportText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentReportCalls,
        maxConcurrentTranslationCalls = translationText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentTranslationCalls,
        maxConcurrentFanOutCalls = fanOutText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentFanOutCalls,
        maxConcurrentFanIconsCalls = fanIconsText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxConcurrentFanIconsCalls,
        maxTestApiCalls = testText.toIntOrNull()?.coerceAtLeast(1)
            ?: generalSettings.maxTestApiCalls
    )

    LaunchedEffect(apiText, reportText, translationText, fanOutText, fanIconsText, testText) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "settings_network_api_calls",
            title = "Maximal API calls",
            onBackClick = onBack
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingCard(
                "Concurrent API calls at the same time",
                "Hard global ceiling on every API call the app keeps in flight at once — reports, translations, fan-out, and any sub-dispatcher under them. Calls beyond the cap suspend until a permit frees up. Default 50."
            ) {
                OutlinedTextField(
                    value = apiText,
                    onValueChange = { apiText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent API calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Model reports API calls",
                "Cap on the primary per-agent calls fired during a new-report run. The global cap still wins if it's lower. Default 15."
            ) {
                OutlinedTextField(
                    value = reportText,
                    onValueChange = { reportText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Model reports calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Translations API calls",
                "Cap on per-item translation calls inside a translation run. With multi-model translation runs, the cap is on the total across models, not per model. Default 15."
            ) {
                OutlinedTextField(
                    value = translationText,
                    onValueChange = { translationText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Translation calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Fan Out API calls",
                "Cap on per-pair fan-out calls. The per-provider cap (Network settings → Per-provider throttling) still applies on top, so a single-provider fan-out still respects that limit. Default 15."
            ) {
                OutlinedTextField(
                    value = fanOutText,
                    onValueChange = { fanOutText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Fan Out calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Fan Icons API calls",
                "Cap on the fan-icons batch — the emoji-generation chain the user launches from a fan-out's Find Icons button. Separate from the fan-out cap so the two can run side-by-side without halving each other's budget. Default 15."
            ) {
                OutlinedTextField(
                    value = fanIconsText,
                    onValueChange = { fanIconsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Fan Icons calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard(
                "Concurrent Test all models API calls",
                "Cap on the \"Test all models\" run (Housekeeping → Test). A run probes every configured model of every active provider, so this controls how hard that sweep hits the network. Default 40."
            ) {
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Concurrent Test all models calls") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}

/** Visual / layout preferences that don't affect the network layer.
 *  Two cards: Model name layout, Show AI Knowledge card on home page. */
@Composable
private fun UiTweaksSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var modelNameLayout by remember { mutableStateOf(generalSettings.modelNameLayout) }
    var showKnowledgeCard by remember { mutableStateOf(generalSettings.showKnowledgeCard) }
    var fullScreen by remember { mutableStateOf(generalSettings.fullScreen) }
    var experimentalFeatures by remember { mutableStateOf(generalSettings.experimentalFeaturesEnabled) }

    fun build(): GeneralSettings = generalSettings.copy(
        modelNameLayout = modelNameLayout,
        showKnowledgeCard = showKnowledgeCard,
        fullScreen = fullScreen,
        experimentalFeaturesEnabled = experimentalFeatures
    )

    LaunchedEffect(modelNameLayout, showKnowledgeCard, fullScreen, experimentalFeatures) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_ui", title = "UI tweaks", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("Model name layout", "How model labels render across rows and pickers.") {
                Column {
                    RadioRow(
                        selected = modelNameLayout == com.ai.viewmodel.ModelNameLayout.MODEL_ONLY,
                        label = "Model name only",
                        onClick = { modelNameLayout = com.ai.viewmodel.ModelNameLayout.MODEL_ONLY }
                    )
                    RadioRow(
                        selected = modelNameLayout == com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL,
                        label = "Provider and model name",
                        onClick = { modelNameLayout = com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL }
                    )
                }
            }
            ToggleSettingCard(
                title = "Experimental features",
                description = "Master gate for on-device Local LLMs, LiteRT embedders, AI Knowledge / RAG, and Local Semantic Search. Off (default) hides those UI surfaces — installed model files and KBs stay on disk, and any KB already attached to a chat or report keeps sending context at API time.",
                checked = experimentalFeatures,
                onCheckedChange = { experimentalFeatures = it }
            )
            if (experimentalFeatures) {
                ToggleSettingCard(
                    title = "Show AI Knowledge card on home page",
                    description = "Show the AI Knowledge / RAG card on the Hub. Off hides the card — knowledge bases still work via the share-target chooser, and any KB already attached to a chat or report is unaffected.",
                    checked = showKnowledgeCard,
                    onCheckedChange = { showKnowledgeCard = it }
                )
            }
            ToggleSettingCard(
                title = "Full screen",
                description = "Hide the Android status bar (clock / battery / signal) so the app uses the full screen height. Swipe down from the top edge to reveal the bar transiently.",
                checked = fullScreen,
                onCheckedChange = { fullScreen = it }
            )
        }
    }
}

/** Everything that doesn't fit the network / UI / logging buckets:
 *  the user's Name + Email used for outbound prompts and email
 *  exports, plus the master switch for the per-report icon-gen
 *  feature. Keeps Settings main as a pure nav list. */
@Composable
private fun OtherSettingsSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var userName by remember { mutableStateOf(generalSettings.userName) }
    var defaultEmail by remember { mutableStateOf(generalSettings.defaultEmail) }
    var reportTitleMode by remember { mutableStateOf(generalSettings.reportTitleMode) }
    var iconGenEnabled by remember { mutableStateOf(generalSettings.iconGenEnabled) }
    var perModelIconGenEnabled by remember { mutableStateOf(generalSettings.perModelIconGenEnabled) }
    var useInternalPromptsIcons by remember { mutableStateOf(generalSettings.useInternalPromptsIcons) }

    fun build(): GeneralSettings = generalSettings.copy(
        userName = userName,
        defaultEmail = defaultEmail,
        reportTitleMode = reportTitleMode,
        iconGenEnabled = iconGenEnabled,
        perModelIconGenEnabled = perModelIconGenEnabled,
        useInternalPromptsIcons = useInternalPromptsIcons
    )

    LaunchedEffect(userName, defaultEmail, reportTitleMode, iconGenEnabled, perModelIconGenEnabled, useInternalPromptsIcons) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_other", title = "Other settings", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingCard("Identity", "Used as the human side of the conversation in agent prompts; the email pre-fills the export sheet so you don't retype it on every send.") {
                OutlinedTextField(
                    value = userName, onValueChange = { userName = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = defaultEmail, onValueChange = { defaultEmail = it },
                    label = { Text("Email address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
            SettingCard("Report title", "How a new report's title is decided. Manual keeps the Title input field on the New AI Report screen. AI (default) hides the field and runs a background LLM call after report start that fills the title from the prompt body — the resolved title shows on the 'title' row of the Manage report screen, alongside the icon and language rows.") {
                Column {
                    RadioRow(
                        selected = reportTitleMode == com.ai.viewmodel.ReportTitleMode.Manual,
                        label = "Manual — type a title yourself",
                        onClick = { reportTitleMode = com.ai.viewmodel.ReportTitleMode.Manual }
                    )
                    RadioRow(
                        selected = reportTitleMode == com.ai.viewmodel.ReportTitleMode.AI,
                        label = "AI — generate from the prompt",
                        onClick = { reportTitleMode = com.ai.viewmodel.ReportTitleMode.AI }
                    )
                }
            }
            ToggleSettingCard(
                title = "Generate report icons",
                description = "Run a small LLM call at the start of every report to pick a fitting emoji icon. The icon shows in the title bar, hub list, history, and search hits. Turn this off to skip the call and hide every report-icon affordance.",
                checked = iconGenEnabled,
                onCheckedChange = { iconGenEnabled = it }
            )
            ToggleSettingCard(
                title = "Generate per model icons",
                description = "Auto-run the 3-tier per-agent icon chain (chat continuation → one-shot template → fixed-agent fallback) at the end of every report run. Each successful agent's leftmost ✅ flips to a returned emoji once the chain finishes for that row. Costs accumulate on the row's cost cell and post to Usage statistics with kind=\"icon\".",
                checked = perModelIconGenEnabled,
                onCheckedChange = { perModelIconGenEnabled = it }
            )
            ToggleSettingCard(
                title = "Use internal prompts icons",
                description = "Generate a small emoji for each Internal Prompt and show it as a leading glyph on the secondary-result rows of the report result page (compare / critique / rerank / fan-out / …). One LLM call per (name, title) — results cached persistently and reused across reports. Renaming a prompt or editing its title invalidates only that entry.",
                checked = useInternalPromptsIcons,
                onCheckedChange = { useInternalPromptsIcons = it }
            )
        }
    }
}

/** Diagnostic preferences: master API tracing switch + application
 *  log severity threshold. Both flow to background subsystems
 *  (ApiTracer / AppLog) on save; the change takes effect on the
 *  next traced call / next log line. */
@Composable
private fun LoggingAndTracingSubScreen(
    generalSettings: GeneralSettings,
    onSave: (GeneralSettings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var tracingEnabled by remember { mutableStateOf(generalSettings.tracingEnabled) }
    var logLevel by remember { mutableStateOf(generalSettings.logLevel) }

    fun build(): GeneralSettings = generalSettings.copy(
        tracingEnabled = tracingEnabled,
        logLevel = logLevel
    )

    LaunchedEffect(tracingEnabled, logLevel) {
        val updated = build()
        if (updated != generalSettings) {
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val updated = build()
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(helpTopic = "settings_logging", title = "Logging and tracing", onBackClick = onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleSettingCard(
                title = "API tracing",
                description = "Record every API request and response. Turn off to hide the AI API Traces card and the 🐞 trace icons.",
                checked = tracingEnabled,
                onCheckedChange = { tracingEnabled = it }
            )
            SettingCard(
                "Application log level",
                "Severity threshold for the in-app file logger. Calls at or above this level are appended to a daily-rotating file in app storage. View / clear under Housekeeping → Application log. OFF disables the file appender."
            ) {
                Column {
                    com.ai.data.LogLevel.entries.forEach { lvl ->
                        RadioRow(
                            selected = logLevel == lvl,
                            label = lvl.name,
                            onClick = { logLevel = lvl }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // Cards start collapsed so the Settings screen lands on a compact
    // title-only list. Tap the header row to expand description + body.
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) {
                if (description != null) {
                    Text(description, fontSize = 11.sp, color = AppColors.TextTertiary)
                }
                content()
            }
        }
    }
}

/** Switch on the same row as the title, description below — denser
 *  than [SettingCard] for the boolean preferences that don't need a
 *  full-width control beneath them. Starts collapsed; tap the header
 *  to expand and reveal the Switch + description. */
@Composable
private fun ToggleSettingCard(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (expanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (checked) "On" else "Off",
                        fontSize = 12.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.weight(1f))
                    Switch(checked = checked, onCheckedChange = onCheckedChange)
                }
                if (description != null) {
                    Text(description, fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }
        }
    }
}

/** Radio + label on one row. Default RadioButton ships with a 48dp
 *  touch-target padding which leaves a wide gap between stacked rows
 *  — fine for accessibility but visually noisy here. We let the
 *  default through (don't shrink the touch target) but wrap rows in
 *  a no-spacing Column so they sit at their natural minimum. */
@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp))
    }
}
