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
    AI_PROVIDERS_SETUP, AI_PROVIDERS,
    AI_MODELS_SETUP,
    AI_MODELS, AI_MODEL_EDIT,
    AI_MODEL_TYPES,
    AI_MANUAL_MODEL_TYPES,
    AI_WORKERS_SETUP,
    AI_AGENTS, AI_AGENT_EDIT,
    AI_FLOCKS, AI_FLOCK_EDIT,
    AI_SWARMS, AI_SWARM_EDIT,
    AI_PARAMETERS, AI_PARAMETERS_EDIT,
    AI_SYSTEM_PROMPTS, AI_SYSTEM_PROMPT_EDIT,
    AI_INTERNAL_PROMPTS_HUB,
    AI_FAN_PROMPTS_HUB,
    AI_INTERNAL_PROMPTS, AI_INTERNAL_PROMPT_EDIT,
    AI_EXAMPLE_PROMPTS, AI_EXAMPLE_PROMPT_EDIT,
    AI_EXTERNAL_SERVICES,
    AI_PROMPTS_SETUP,
    AI_LOCAL_MODELS_SETUP,
    AI_LOCAL_LITERT_MODELS,
    AI_LOCAL_LLMS,
    AI_IMPORT_EXPORT,
    AI_REFRESH
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
    onNavigateToProviderAdmin: () -> Unit = {},
    onTestModelWithPrompt: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onTestSpecificModel: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onLoadBundledPrompts: () -> Int = { 0 },
    onResetBundledPrompts: () -> Int = { 0 },
    onLoadBundledExamples: () -> Int = { 0 },
    refreshAllState: com.ai.viewmodel.RefreshAllState? = null,
    onStartRefreshAll: () -> Unit = {},
    onClearRefreshAllState: () -> Unit = {},
    onNavigateToHelpTopic: (String) -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    initialProviderId: String? = null,
    initialEditingAgentId: String? = null,
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
    var editingFlockId by remember { mutableStateOf<String?>(null) }
    var editingSwarmId by remember { mutableStateOf<String?>(null) }
    var editingParametersId by remember { mutableStateOf<String?>(null) }
    var editingSystemPromptId by remember { mutableStateOf<String?>(null) }
    var editingInternalPromptId by remember { mutableStateOf(initialEditingInternalPromptId) }
    var editingExamplePromptId by remember { mutableStateOf<String?>(null) }
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
    // Active/All filter on the Providers list — hoisted up here (rather than left as
    // rememberSaveable inside ProvidersScreen) because the sub-screen `when` block
    // destroys ProvidersScreen's composition entirely on navigation, which throws
    // its rememberSaveable state away.

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
            SettingsSubScreen.AI_PROVIDERS -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS_SETUP
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
            SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB,
            SettingsSubScreen.AI_EXAMPLE_PROMPTS -> currentSubScreen = SettingsSubScreen.AI_PROMPTS_SETUP
            SettingsSubScreen.AI_FAN_PROMPTS_HUB -> currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB
            // Back from a per-category list lands on whichever hub
            // owns that category — Fan out/in for any of the five
            // fan-* buckets, the main Internal Prompts hub for meta /
            // internal. selectedInternalCategory is set when the list
            // is opened, so it's authoritative here.
            SettingsSubScreen.AI_INTERNAL_PROMPTS -> currentSubScreen =
                if (selectedInternalCategory in setOf("fan_out", "fan_in", "fan-in-model"))
                    SettingsSubScreen.AI_FAN_PROMPTS_HUB
                else
                    SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB
            SettingsSubScreen.AI_LOCAL_LITERT_MODELS,
            SettingsSubScreen.AI_LOCAL_LLMS -> currentSubScreen = SettingsSubScreen.AI_LOCAL_MODELS_SETUP
            SettingsSubScreen.AI_PROVIDERS_SETUP,
            SettingsSubScreen.AI_MODELS_SETUP,
            SettingsSubScreen.AI_WORKERS_SETUP,
            SettingsSubScreen.AI_PROMPTS_SETUP,
            SettingsSubScreen.AI_LOCAL_MODELS_SETUP,
            SettingsSubScreen.AI_PARAMETERS,
            SettingsSubScreen.AI_EXTERNAL_SERVICES,
            SettingsSubScreen.AI_IMPORT_EXPORT, SettingsSubScreen.AI_REFRESH -> currentSubScreen = SettingsSubScreen.AI_SETUP
            SettingsSubScreen.AI_AGENT_EDIT -> { editingAgentId = null; currentSubScreen = SettingsSubScreen.AI_AGENTS }
            SettingsSubScreen.AI_FLOCK_EDIT -> { editingFlockId = null; currentSubScreen = SettingsSubScreen.AI_FLOCKS }
            SettingsSubScreen.AI_SWARM_EDIT -> { editingSwarmId = null; currentSubScreen = SettingsSubScreen.AI_SWARMS }
            SettingsSubScreen.AI_PARAMETERS_EDIT -> { editingParametersId = null; currentSubScreen = SettingsSubScreen.AI_PARAMETERS }
            SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT -> { editingSystemPromptId = null; currentSubScreen = SettingsSubScreen.AI_SYSTEM_PROMPTS }
            SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT -> { editingInternalPromptId = null; currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS }
            SettingsSubScreen.AI_EXAMPLE_PROMPT_EDIT -> { editingExamplePromptId = null; currentSubScreen = SettingsSubScreen.AI_EXAMPLE_PROMPTS }
        }
    }

    BackHandler { goBack() }

    when (currentSubScreen) {
        SettingsSubScreen.MAIN -> {
            SettingsMainScreen(
                generalSettings = generalSettings, onSave = onSaveGeneral,
                onBack = onBack, onNavigateHome = onNavigateHome
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
        SettingsSubScreen.AI_PROVIDERS_SETUP -> {
            ProvidersSetupScreen(
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it },
                onNavigateToProviderAdmin = onNavigateToProviderAdmin
            )
        }
        SettingsSubScreen.AI_PROVIDERS -> {
            ProvidersScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome,
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
                onBack = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }
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
                onLoadBundledPrompts = onLoadBundledPrompts,
                onResetBundledPrompts = onResetBundledPrompts,
                onLoadBundledExamples = onLoadBundledExamples
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
            ManualModelTypesScreen(
                aiSettings = aiSettings,
                onBack = goBack, onNavigateHome = onNavigateHome,
                onSave = onSaveAi
            )
        }
        SettingsSubScreen.AI_AGENTS -> {
            AgentsScreen(
                aiSettings = aiSettings,
                onBackToAiSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onTestAiModel = onTestAiModel, onFetchModels = onFetchModels,
                onAddAgent = { editingAgentId = null; currentSubScreen = SettingsSubScreen.AI_AGENT_EDIT },
                onEditAgent = { editingAgentId = it; currentSubScreen = SettingsSubScreen.AI_AGENT_EDIT }
            )
        }
        SettingsSubScreen.AI_AGENT_EDIT -> {
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
                onNavigateToTrace = onNavigateToTrace
            )
        }
        SettingsSubScreen.AI_FLOCKS -> {
            FlocksScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onAddFlock = { editingFlockId = null; currentSubScreen = SettingsSubScreen.AI_FLOCK_EDIT },
                onEditFlock = { editingFlockId = it; currentSubScreen = SettingsSubScreen.AI_FLOCK_EDIT }
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
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SWARMS -> {
            SwarmsScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onAddSwarm = { editingSwarmId = null; currentSubScreen = SettingsSubScreen.AI_SWARM_EDIT },
                onEditSwarm = { editingSwarmId = it; currentSubScreen = SettingsSubScreen.AI_SWARM_EDIT }
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
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_PARAMETERS -> {
            ParametersListScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onAddParameters = { editingParametersId = null; currentSubScreen = SettingsSubScreen.AI_PARAMETERS_EDIT },
                onEditParameters = { editingParametersId = it; currentSubScreen = SettingsSubScreen.AI_PARAMETERS_EDIT }
            )
        }
        SettingsSubScreen.AI_PARAMETERS_EDIT -> {
            val params = editingParametersId?.let { aiSettings.getParametersById(it) }
            ParametersEditScreen(
                params = params,
                existingNames = aiSettings.parameters.filter { it.id != (params?.id ?: "") }.map { it.name.lowercase(java.util.Locale.ROOT) }.toSet(),
                onSave = { saved ->
                    val updated = if (params != null) aiSettings.copy(parameters = aiSettings.parameters.map { if (it.id == params.id) saved else it })
                    else aiSettings.copy(parameters = aiSettings.parameters + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SYSTEM_PROMPTS -> {
            SystemPromptsListScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onAddSystemPrompt = { editingSystemPromptId = null; currentSubScreen = SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT },
                onEditSystemPrompt = { editingSystemPromptId = it; currentSubScreen = SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT }
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
            InternalPromptsListScreen(
                aiSettings = aiSettings,
                categoryFilter = selectedInternalCategory,
                onBackToPromptsSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onAddInternalPrompt = { editingInternalPromptId = null; currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT },
                onEditInternalPrompt = { editingInternalPromptId = it; currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPT_EDIT }
            )
        }
        SettingsSubScreen.AI_EXAMPLE_PROMPTS -> {
            ExamplePromptsListScreen(
                aiSettings = aiSettings,
                onBackToPromptsSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onAddExamplePrompt = { editingExamplePromptId = null; currentSubScreen = SettingsSubScreen.AI_EXAMPLE_PROMPT_EDIT },
                onEditExamplePrompt = { editingExamplePromptId = it; currentSubScreen = SettingsSubScreen.AI_EXAMPLE_PROMPT_EDIT }
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
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
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
                importOnly = importOnly
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
                onClearRefreshAllState = onClearRefreshAllState,
                onOpenProvider = { svc ->
                    selectedProviderId = svc.id
                    currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
                },
                onNavigateToHelpTopic = onNavigateToHelpTopic,
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
    onNavigateHome: () -> Unit
) {
    var userName by remember { mutableStateOf(generalSettings.userName) }
    var defaultEmail by remember { mutableStateOf(generalSettings.defaultEmail) }
    var tracingEnabled by remember { mutableStateOf(generalSettings.tracingEnabled) }
    var modelNameLayout by remember { mutableStateOf(generalSettings.modelNameLayout) }
    var showBackButton by remember { mutableStateOf(generalSettings.showBackButton) }
    var subjectToTitleBarMode by remember { mutableStateOf(generalSettings.subjectToTitleBarMode) }
    var iconBarAtBottom by remember { mutableStateOf(generalSettings.iconBarAtBottom) }
    var iconGenEnabled by remember { mutableStateOf(generalSettings.iconGenEnabled) }
    var showKnowledgeCard by remember { mutableStateOf(generalSettings.showKnowledgeCard) }
    // Stored as strings so partial / empty edits don't fight the
    // user mid-keystroke. On save, parse → coerceAtLeast(1) so a
    // typo can never produce a 0-second timeout that would fail
    // every call instantly.
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
    var logLevel by remember { mutableStateOf(generalSettings.logLevel) }

    LaunchedEffect(userName, defaultEmail, tracingEnabled, modelNameLayout, showBackButton, subjectToTitleBarMode, iconBarAtBottom, iconGenEnabled, showKnowledgeCard, streamingReadTimeoutText, nonStreamingReadTimeoutText, maxCallsPerMinuteText, maxConcurrentCallsText, logLevel) {
        val updated = generalSettings.copy(
            userName = userName, defaultEmail = defaultEmail,
            tracingEnabled = tracingEnabled, modelNameLayout = modelNameLayout,
            showBackButton = showBackButton, subjectToTitleBarMode = subjectToTitleBarMode,
            iconBarAtBottom = iconBarAtBottom,
            iconGenEnabled = iconGenEnabled,
            showKnowledgeCard = showKnowledgeCard,
            streamingReadTimeoutSec = streamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
                ?: generalSettings.streamingReadTimeoutSec,
            nonStreamingReadTimeoutSec = nonStreamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
                ?: generalSettings.nonStreamingReadTimeoutSec,
            maxCallsPerProviderPerMinute = maxCallsPerMinuteText.toIntOrNull()?.coerceAtLeast(1)
                ?: generalSettings.maxCallsPerProviderPerMinute,
            maxConcurrentCallsPerProvider = maxConcurrentCallsText.toIntOrNull()?.coerceAtLeast(1)
                ?: generalSettings.maxConcurrentCallsPerProvider,
            logLevel = logLevel
        )
        if (updated != generalSettings) {
            // Debounce keystrokes — every character used to fire a
            // disk write through onSave. Wait 400ms of quiet before
            // persisting; cancellation on a re-key swallows the
            // pending save automatically.
            kotlinx.coroutines.delay(400)
            onSave(updated)
        }
    }
    // Flush any pending debounced edit when the screen leaves
    // composition. Without this, a user typing then immediately
    // tapping back within the 400ms debounce window loses the typed
    // change — the LaunchedEffect's coroutine cancels before the
    // delay returns, so onSave never fires.
    DisposableEffect(Unit) {
        onDispose {
            val updated = generalSettings.copy(
                userName = userName, defaultEmail = defaultEmail,
                tracingEnabled = tracingEnabled, modelNameLayout = modelNameLayout,
                showBackButton = showBackButton, subjectToTitleBarMode = subjectToTitleBarMode,
                iconBarAtBottom = iconBarAtBottom,
            iconGenEnabled = iconGenEnabled,
                showKnowledgeCard = showKnowledgeCard,
                streamingReadTimeoutSec = streamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
                    ?: generalSettings.streamingReadTimeoutSec,
                nonStreamingReadTimeoutSec = nonStreamingReadTimeoutText.toIntOrNull()?.coerceAtLeast(1)
                    ?: generalSettings.nonStreamingReadTimeoutSec,
                maxCallsPerProviderPerMinute = maxCallsPerMinuteText.toIntOrNull()?.coerceAtLeast(1)
                    ?: generalSettings.maxCallsPerProviderPerMinute,
                maxConcurrentCallsPerProvider = maxConcurrentCallsText.toIntOrNull()?.coerceAtLeast(1)
                    ?: generalSettings.maxConcurrentCallsPerProvider,
                logLevel = logLevel
            )
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "settings_main", title = "Settings", onBackClick = onBack)
        Spacer(modifier = Modifier.height(12.dp))

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

            // Master switch for API tracing. Off → no new trace files,
            // the Hub "AI API Traces" card and every 🐞 ladybug icon in
            // the result screens disappear.
            ToggleSettingCard(
                title = "API tracing",
                description = "Record every API request and response. Turn off to hide the AI API Traces card and the 🐞 trace icons.",
                checked = tracingEnabled,
                onCheckedChange = { tracingEnabled = it }
            )

            // Per-call read timeouts. Streaming applies to SSE chat /
            // report calls (the response trickles in chunks — the
            // timeout is the gap between chunks). Non-streaming
            // applies to analyze / fetch-models / meta / rerank /
            // translate calls that block waiting for the full body.
            // Both default to the BuildConfig values (600 / 120 s);
            // a typo defaulting to blank leaves the previous value in
            // place rather than poisoning every call with 0 s.
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

            // Per-provider throttle. Both caps apply per provider
            // hostname and across every flow in the app — report
            // streams, fan-out pairs, meta, translate, chat, model
            // fetches and provider tests all share the same gate.
            // Enforced globally by ProviderThrottleInterceptor in the
            // OkHttp chain.
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

            // In-app log threshold. Every call at this level or higher
            // is mirrored to the daily-rotating file under
            // <filesDir>/applog/ so the user can share the log with
            // Claude Code for troubleshooting. OFF disables the file
            // appender entirely (logcat still works during dev).
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

            // Model name layout — controls how combined provider+model
            // labels render across the app. MODEL_ONLY is the dense
            // default; PROVIDER_AND_MODEL adds the provider's display
            // name (joined with " · ") for users running the same
            // model on multiple providers.
            SettingCard("Model name layout", "How model labels render across rows and pickers.") {
                // Inner Column with no inter-row spacing so the two
                // radios sit tight; the SettingCard's outer 8dp gap
                // would otherwise push them apart.
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

            // Compact-header mode. When on, screens that today show a
            // fixed title plus a green subject sub-header (Model
            // Info, Trace detail, Knowledge base, Translation run, …)
            // fold the subject into the title bar and drop the green
            // line. Drives LocalSubjectToTitleBarMode.
            // Tri-state: HARDCODED keeps the legacy two-row layout
            // (fixed label + green subject line below). SUBJECT folds
            // the subject into the bar and hides the green line. BOTH
            // shows "<fixed> / <subject>" in the bar and hides the
            // green line. Drives LocalSubjectToTitleBarMode.
            SettingCard(
                "Subject to title bar",
                "Detail screens have a fixed label and a dynamic subject. Pick which one (or both) goes in the title bar."
            ) {
                Column {
                    RadioRow(
                        selected = subjectToTitleBarMode == com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED,
                        label = "Hardcoded screen title",
                        onClick = { subjectToTitleBarMode = com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED }
                    )
                    RadioRow(
                        selected = subjectToTitleBarMode == com.ai.viewmodel.SubjectToTitleBarMode.SUBJECT,
                        label = "Dynamic subject name",
                        onClick = { subjectToTitleBarMode = com.ai.viewmodel.SubjectToTitleBarMode.SUBJECT }
                    )
                    RadioRow(
                        selected = subjectToTitleBarMode == com.ai.viewmodel.SubjectToTitleBarMode.BOTH,
                        label = "Both",
                        onClick = { subjectToTitleBarMode = com.ai.viewmodel.SubjectToTitleBarMode.BOTH }
                    )
                }
            }

            // Move every TitleBar action icon (Home / Help / Trace /
            // Delete / Info / Reload / Chat / Memo + the back arrow)
            // into a fixed bar at the bottom of the screen. The top
            // bar then shows only the screen title. Drives
            // LocalIconBarAtBottom; the bar lives at AppNavHost scope
            // so it survives nav transitions.
            ToggleSettingCard(
                title = "Icon bar at bottom",
                description = "Move every action icon (Home / Help / Trace / Delete / Info / Reload / Chat / Memo) and the back arrow into a fixed bar at the bottom of the screen. The top bar then shows only the screen title.",
                checked = iconBarAtBottom,
                onCheckedChange = { iconBarAtBottom = it }
            )

            // Master switch for the per-report icon-gen feature.
            // When off, no background LLM call is fired at report
            // start, the icon row on the result page is hidden, the
            // leftmost report icon (and its tied 📝 memo) drops from
            // every title bar, and per-row icon prefixes on the hub /
            // history / search hits / pickers fall back to the static
            // 🕘 / 📌 (or no prefix). Persisted icon values stay on
            // disk — turning the setting back on brings them back.
            ToggleSettingCard(
                title = "Generate report icons",
                description = "Run a small LLM call at the start of every report to pick a fitting emoji icon. The icon shows in the title bar, hub list, history, and search hits. Turn this off to skip the call and hide every report-icon affordance.",
                checked = iconGenEnabled,
                onCheckedChange = { iconGenEnabled = it }
            )

            // When off, the visible "< Back" button disappears from
            // every TitleBar and the screen title left-aligns.
            // System / gesture back still works (TitleBar's
            // BackHandler is registered independently).
            ToggleSettingCard(
                title = "Show < Back",
                description = "Show the < Back button in the top bar. Off → title aligns left; system / gesture back still works.",
                checked = showBackButton,
                onCheckedChange = { showBackButton = it }
            )

            // The Knowledge / RAG flow is hidden by default — most
            // users don't need it on a fresh install. Turning the
            // toggle on surfaces the AI Knowledge card on the home
            // Hub. The subsystem itself stays functional whether or
            // not the card shows (KBs still attach to chats /
            // reports; share-target Knowledge still works).
            ToggleSettingCard(
                title = "Show AI Knowledge card on home page",
                description = "Show the AI Knowledge / RAG card on the Hub. Off (default) hides the card — knowledge bases still work via the share-target chooser, and any KB already attached to a chat or report is unaffected.",
                checked = showKnowledgeCard,
                onCheckedChange = { showKnowledgeCard = it }
            )
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
