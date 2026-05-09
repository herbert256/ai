package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    MAIN, AI_PROVIDER_EDIT, AI_PROVIDER_ADD, AI_SETUP,
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
    AI_INTERNAL_PROMPTS, AI_INTERNAL_PROMPT_EDIT,
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
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    initialProviderId: String? = null,
    initialEditingAgentId: String? = null,
    initialEditingInternalPromptId: String? = null,
    initialInternalPromptCategory: String? = null
) {
    var currentSubScreen by remember { mutableStateOf(initialSubScreen) }
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
    var selectedProviderId by remember(initialProviderId) {
        mutableStateOf(initialProviderId)
    }
    val selectedProvider: AppService? = selectedProviderId?.let { AppService.findById(it) }
    var editingAgentId by remember { mutableStateOf(initialEditingAgentId) }
    var editingFlockId by remember { mutableStateOf<String?>(null) }
    var editingSwarmId by remember { mutableStateOf<String?>(null) }
    var editingParametersId by remember { mutableStateOf<String?>(null) }
    var editingSystemPromptId by remember { mutableStateOf<String?>(null) }
    var editingInternalPromptId by remember { mutableStateOf(initialEditingInternalPromptId) }
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
    var providersActiveOnly by remember { mutableStateOf(true) }

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
            SettingsSubScreen.AI_PROVIDER_ADD -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
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
            SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB -> currentSubScreen = SettingsSubScreen.AI_PROMPTS_SETUP
            SettingsSubScreen.AI_INTERNAL_PROMPTS -> currentSubScreen = SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB
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
                activeOnly = providersActiveOnly,
                onActiveOnlyChange = { providersActiveOnly = it },
                onProviderSelected = { selectedProviderId = it.id; currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT },
                onAddProvider = { currentSubScreen = SettingsSubScreen.AI_PROVIDER_ADD }
            )
        }
        SettingsSubScreen.AI_PROVIDER_ADD -> {
            ProviderAddScreen(
                onBack = goBack, onNavigateHome = onNavigateHome,
                onSaved = { service ->
                    selectedProviderId = service.id
                    currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
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
                    }
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
                onNavigate = { currentSubScreen = it }
            )
        }
        SettingsSubScreen.AI_INTERNAL_PROMPTS_HUB -> {
            InternalPromptsHubScreen(
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
                swarm = swarm, aiSettings = aiSettings, loadingModelsFor = loadingModelsFor,
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
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_LOCAL_LITERT_MODELS -> {
            LocalLiteRtModelsScreen(onBack = goBack, onNavigateHome = onNavigateHome)
        }
        SettingsSubScreen.AI_LOCAL_LLMS -> {
            LocalLlmsScreen(onBack = goBack, onNavigateHome = onNavigateHome)
        }
        SettingsSubScreen.AI_IMPORT_EXPORT -> {
            ImportExportScreen(
                aiSettings = aiSettings,
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSave = onSaveAi,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveArtificialAnalysisApiKey = onSaveArtificialAnalysisApiKey,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_REFRESH -> {
            RefreshScreen(
                aiSettings = aiSettings,
                openRouterApiKey = generalSettings.openRouterApiKey,
                artificialAnalysisApiKey = generalSettings.artificialAnalysisApiKey,
                onSave = onSaveAi,
                onRefreshAllModels = onRefreshAllModels,
                onTestApiKey = onTestAiModel,
                onProviderStateChange = onProviderStateChange,
                onOpenProvider = { svc ->
                    selectedProviderId = svc.id
                    currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
                },
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

    LaunchedEffect(userName, defaultEmail, tracingEnabled, modelNameLayout, showBackButton) {
        val updated = generalSettings.copy(
            userName = userName, defaultEmail = defaultEmail,
            tracingEnabled = tracingEnabled, modelNameLayout = modelNameLayout,
            showBackButton = showBackButton
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
                showBackButton = showBackButton
            )
            if (updated != generalSettings) onSave(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "settings_main", title = "Settings", onBackClick = onBack)
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = userName, onValueChange = { userName = it },
                label = { Text("User name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors()
            )
            OutlinedTextField(
                value = defaultEmail, onValueChange = { defaultEmail = it },
                label = { Text("Default email address") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors()
            )

            // Master switch for API tracing. Off → no new trace files,
            // the Hub "AI API Traces" card and every 🐞 ladybug icon in
            // the result screens disappear.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("API tracing", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Record every API request and response. Turn off to hide the AI API Traces card and the 🐞 trace icons.",
                        fontSize = 12.sp, color = AppColors.TextSecondary
                    )
                }
                Switch(
                    checked = tracingEnabled,
                    onCheckedChange = { tracingEnabled = it }
                )
            }

            // Model name layout — controls how combined provider+model
            // labels render across the app. MODEL_ONLY is the dense
            // default; PROVIDER_AND_MODEL adds the provider's display
            // name (joined with " · ") for users running the same
            // model on multiple providers.
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Model name layout", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "How model labels render across rows and pickers.",
                    fontSize = 12.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = modelNameLayout == com.ai.viewmodel.ModelNameLayout.MODEL_ONLY,
                        onClick = { modelNameLayout = com.ai.viewmodel.ModelNameLayout.MODEL_ONLY }
                    )
                    Text("Model name only", fontSize = 14.sp, color = Color.White,
                        modifier = Modifier.clickable { modelNameLayout = com.ai.viewmodel.ModelNameLayout.MODEL_ONLY }.padding(start = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = modelNameLayout == com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL,
                        onClick = { modelNameLayout = com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL }
                    )
                    Text("Provider and model name", fontSize = 14.sp, color = Color.White,
                        modifier = Modifier.clickable { modelNameLayout = com.ai.viewmodel.ModelNameLayout.PROVIDER_AND_MODEL }.padding(start = 4.dp))
                }
            }

            // When off, the visible "< Back" button disappears from
            // every TitleBar and the screen title left-aligns.
            // System / gesture back still works (TitleBar's
            // BackHandler is registered independently).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show < Back", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Show the < Back button in the top bar. Off → title aligns left; system / gesture back still works.",
                        fontSize = 12.sp, color = AppColors.TextSecondary
                    )
                }
                Switch(
                    checked = showBackButton,
                    onCheckedChange = { showBackButton = it }
                )
            }
        }
    }
}
