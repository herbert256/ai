package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.GeneralSettings

enum class SettingsSubScreen {
    MAIN, AI_PROVIDER_EDIT, AI_PROVIDER_ADD, AI_SETUP, AI_PROVIDERS,
    AI_MODELS_SETUP,
    AI_MODELS, AI_MODEL_EDIT,
    AI_MODEL_TYPES,
    AI_MANUAL_MODEL_TYPES,
    AI_AGENTS, AI_AGENT_EDIT,
    AI_FLOCKS, AI_FLOCK_EDIT,
    AI_SWARMS, AI_SWARM_EDIT,
    AI_PROMPTS, AI_PROMPT_EDIT,
    AI_PARAMETERS, AI_PARAMETERS_EDIT,
    AI_SYSTEM_PROMPTS, AI_SYSTEM_PROMPT_EDIT,
    AI_EXTERNAL_SERVICES,
    AI_IMPORT_EXPORT,
    AI_REFRESH
}

@Composable
fun SettingsScreen(
    generalSettings: GeneralSettings,
    aiSettings: Settings,
    loadingModelsFor: Set<AppService> = emptySet(),
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onSaveAi: (Settings) -> Unit,
    onFetchModels: (AppService, String) -> Unit = { _, _ -> },
    onTestAiModel: suspend (AppService, String, String) -> String? = { _, _, _ -> null },
    onProviderStateChange: (AppService, String) -> Unit = { _, _ -> },
    onRefreshAllModels: suspend (Settings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onNavigateToCostConfig: () -> Unit = {},
    onTestModelWithPrompt: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onTestSpecificModel: suspend (AppService, String, String, String) -> Pair<Boolean, String?> = { _, _, _, _ -> Pair(false, null) },
    onNavigateToTrace: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN,
    initialProviderId: String? = null,
    initialEditingAgentId: String? = null
) {
    var currentSubScreen by remember { mutableStateOf(initialSubScreen) }
    var selectedProvider by remember { mutableStateOf(initialProviderId?.let { AppService.findById(it) }) }
    var editingAgentId by remember { mutableStateOf(initialEditingAgentId) }
    var editingFlockId by remember { mutableStateOf<String?>(null) }
    var editingSwarmId by remember { mutableStateOf<String?>(null) }
    var editingPromptId by remember { mutableStateOf<String?>(null) }
    var editingParametersId by remember { mutableStateOf<String?>(null) }
    var editingSystemPromptId by remember { mutableStateOf<String?>(null) }
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
            SettingsSubScreen.AI_MODEL_EDIT -> {
                val from = modelEditFromProvider
                modelEditFromProvider = false
                currentSubScreen = if (from) SettingsSubScreen.AI_PROVIDER_EDIT else SettingsSubScreen.AI_MODELS
            }
            SettingsSubScreen.AI_MODELS, SettingsSubScreen.AI_MODEL_TYPES,
            SettingsSubScreen.AI_MANUAL_MODEL_TYPES -> currentSubScreen = SettingsSubScreen.AI_MODELS_SETUP
            SettingsSubScreen.AI_PROVIDERS, SettingsSubScreen.AI_MODELS_SETUP,
            SettingsSubScreen.AI_AGENTS, SettingsSubScreen.AI_FLOCKS,
            SettingsSubScreen.AI_SWARMS, SettingsSubScreen.AI_PROMPTS, SettingsSubScreen.AI_PARAMETERS,
            SettingsSubScreen.AI_SYSTEM_PROMPTS, SettingsSubScreen.AI_EXTERNAL_SERVICES,
            SettingsSubScreen.AI_IMPORT_EXPORT, SettingsSubScreen.AI_REFRESH -> currentSubScreen = SettingsSubScreen.AI_SETUP
            SettingsSubScreen.AI_AGENT_EDIT -> { editingAgentId = null; currentSubScreen = SettingsSubScreen.AI_AGENTS }
            SettingsSubScreen.AI_FLOCK_EDIT -> { editingFlockId = null; currentSubScreen = SettingsSubScreen.AI_FLOCKS }
            SettingsSubScreen.AI_SWARM_EDIT -> { editingSwarmId = null; currentSubScreen = SettingsSubScreen.AI_SWARMS }
            SettingsSubScreen.AI_PROMPT_EDIT -> { editingPromptId = null; currentSubScreen = SettingsSubScreen.AI_PROMPTS }
            SettingsSubScreen.AI_PARAMETERS_EDIT -> { editingParametersId = null; currentSubScreen = SettingsSubScreen.AI_PARAMETERS }
            SettingsSubScreen.AI_SYSTEM_PROMPT_EDIT -> { editingSystemPromptId = null; currentSubScreen = SettingsSubScreen.AI_SYSTEM_PROMPTS }
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
                onBackToSettings = goBack, onBackToHome = onNavigateHome,
                onNavigate = { currentSubScreen = it }, onSave = onSaveAi,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onNavigateToCostConfig = onNavigateToCostConfig
            )
        }
        SettingsSubScreen.AI_PROVIDERS -> {
            ProvidersScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome,
                activeOnly = providersActiveOnly,
                onActiveOnlyChange = { providersActiveOnly = it },
                onProviderSelected = { selectedProvider = it; currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT },
                onAddProvider = { currentSubScreen = SettingsSubScreen.AI_PROVIDER_ADD }
            )
        }
        SettingsSubScreen.AI_PROVIDER_ADD -> {
            ProviderAddScreen(
                onBack = goBack, onNavigateHome = onNavigateHome,
                onSaved = { service ->
                    selectedProvider = service
                    currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
                }
            )
        }
        SettingsSubScreen.AI_PROVIDER_EDIT -> {
            selectedProvider?.let { provider ->
                ProviderSettingsScreen(
                    service = provider, aiSettings = aiSettings,
                    isLoadingModels = provider in loadingModelsFor,
                    onBackToSettings = goBack, onBackToHome = onNavigateHome,
                    onSave = onSaveAi,
                    onFetchModels = {
                        val fresh = AppService.findById(provider.id) ?: provider
                        onFetchModels(fresh, it)
                    },
                    onTestApiKey = onTestAiModel, onProviderStateChange = { onProviderStateChange(provider, it) },
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
                onProviderSelected = { selectedProvider = it; currentSubScreen = SettingsSubScreen.AI_MODEL_EDIT }
            )
        }
        SettingsSubScreen.AI_MODEL_EDIT -> {
            selectedProvider?.let { provider ->
                ProviderModelSettingsScreen(
                    service = provider, aiSettings = aiSettings,
                    isLoadingModels = provider in loadingModelsFor,
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
                existingNames = aiSettings.agents.filter { it.id != (agent?.id ?: "") }.map { it.name.lowercase() }.toSet(),
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
                existingNames = aiSettings.flocks.filter { it.id != (flock?.id ?: "") }.map { it.name.lowercase() }.toSet(),
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
                existingNames = aiSettings.swarms.filter { it.id != (swarm?.id ?: "") }.map { it.name.lowercase() }.toSet(),
                onSave = { saved ->
                    val updated = if (swarm != null) aiSettings.copy(swarms = aiSettings.swarms.map { if (it.id == swarm.id) saved else it })
                    else aiSettings.copy(swarms = aiSettings.swarms + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_PROMPTS -> {
            PromptsScreen(
                aiSettings = aiSettings, onBackToAiSetup = goBack, onBackToHome = onNavigateHome, onSave = onSaveAi,
                onAddPrompt = { editingPromptId = null; currentSubScreen = SettingsSubScreen.AI_PROMPT_EDIT },
                onEditPrompt = { editingPromptId = it; currentSubScreen = SettingsSubScreen.AI_PROMPT_EDIT }
            )
        }
        SettingsSubScreen.AI_PROMPT_EDIT -> {
            val prompt = editingPromptId?.let { aiSettings.getPromptById(it) }
            PromptEditScreen(
                prompt = prompt, aiSettings = aiSettings,
                existingNames = aiSettings.prompts.filter { it.id != (prompt?.id ?: "") }.map { it.name.lowercase() }.toSet(),
                onSave = { saved ->
                    val updated = if (prompt != null) aiSettings.copy(prompts = aiSettings.prompts.map { if (it.id == prompt.id) saved else it })
                    else aiSettings.copy(prompts = aiSettings.prompts + saved)
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
                existingNames = aiSettings.parameters.filter { it.id != (params?.id ?: "") }.map { it.name.lowercase() }.toSet(),
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
                existingNames = aiSettings.systemPrompts.filter { it.id != (sp?.id ?: "") }.map { it.name.lowercase() }.toSet(),
                onSave = { saved ->
                    val updated = if (sp != null) aiSettings.copy(systemPrompts = aiSettings.systemPrompts.map { if (it.id == sp.id) saved else it })
                    else aiSettings.copy(systemPrompts = aiSettings.systemPrompts + saved)
                    onSaveAi(updated); goBack()
                },
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_EXTERNAL_SERVICES -> {
            ExternalServicesScreen(
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_IMPORT_EXPORT -> {
            ImportExportScreen(
                aiSettings = aiSettings,
                generalSettings = generalSettings,
                huggingFaceApiKey = generalSettings.huggingFaceApiKey, openRouterApiKey = generalSettings.openRouterApiKey,
                onSave = onSaveAi,
                onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey, onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
                onSaveGeneral = onSaveGeneral,
                onBack = goBack, onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_REFRESH -> {
            RefreshScreen(
                aiSettings = aiSettings,
                openRouterApiKey = generalSettings.openRouterApiKey,
                onSave = onSaveAi,
                onRefreshAllModels = onRefreshAllModels,
                onTestApiKey = onTestAiModel,
                onProviderStateChange = onProviderStateChange,
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

    LaunchedEffect(userName, defaultEmail) {
        val updated = generalSettings.copy(userName = userName, defaultEmail = defaultEmail)
        if (updated != generalSettings) onSave(updated)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Settings", onBackClick = onBack, onAiClick = onNavigateHome)
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
        }
    }
}
