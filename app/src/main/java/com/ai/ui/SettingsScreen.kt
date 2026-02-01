package com.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.data.AiService

/**
 * Settings sub-screen navigation enum.
 */
enum class SettingsSubScreen {
    MAIN,
    AI_PROVIDER_EDIT,  // Dynamic provider editing (uses selectedProvider state)
    AI_ADD_PROVIDER,   // Add new custom provider definition
    AI_EDIT_PROVIDER_DEF,  // Edit provider definition (displayName, baseUrl, etc.)
    // AI architecture
    AI_SETUP,       // Hub with navigation cards
    AI_PROVIDERS,   // Provider model configuration
    AI_AGENTS,      // Agents CRUD
    AI_ADD_AGENT,   // Add new agent (direct to AgentEditScreen)
    AI_SWARMS,      // Flocks CRUD
    AI_ADD_SWARM,   // Add new flock
    AI_EDIT_SWARM,  // Edit existing flock
    AI_FLOCKS,      // Swarms CRUD
    AI_ADD_FLOCK,   // Add new swarm
    AI_EDIT_FLOCK,  // Edit existing swarm
    AI_PROMPTS,     // AI Prompts CRUD
    AI_ADD_PROMPT,  // Add new prompt
    AI_EDIT_PROMPT, // Edit existing prompt
    AI_PARAMETERS,      // AI Parameters CRUD
    AI_ADD_PARAMETERS,  // Add new parameters
    AI_EDIT_PARAMETERS  // Edit existing parameters
}

/**
 * Root settings screen that manages navigation between settings sub-screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    generalSettings: GeneralSettings,
    aiSettings: AiSettings,
    loadingModelsFor: Set<AiService> = emptySet(),
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onTrackApiCallsChanged: (Boolean) -> Unit = {},
    onSaveAi: (AiSettings) -> Unit,
    onFetchModels: (AiService, String) -> Unit = { _, _ -> },
    onTestAiModel: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onProviderStateChange: (AiService, String) -> Unit = { _, _ -> },
    onRefreshAllModels: suspend (AiSettings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onNavigateToCostConfig: () -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN
) {
    var currentSubScreen by remember { mutableStateOf(initialSubScreen) }

    // State for dynamic provider editing
    var selectedProvider by remember { mutableStateOf<AiService?>(null) }

    // State for pre-filling agent creation from provider screen
    var prefillAgentProvider by remember { mutableStateOf<AiService?>(null) }
    var prefillAgentApiKey by remember { mutableStateOf("") }
    var prefillAgentModel by remember { mutableStateOf("") }

    // State for flock editing
    var editingFlockId by remember { mutableStateOf<String?>(null) }

    // State for swarm editing
    var editingSwarmId by remember { mutableStateOf<String?>(null) }

    // State for prompt editing
    var editingPromptId by remember { mutableStateOf<String?>(null) }

    // State for params editing
    var editingParametersId by remember { mutableStateOf<String?>(null) }

    // Helper to generate unique agent name
    fun generateUniqueAgentName(baseName: String): String {
        val existingNames = aiSettings.agents.map { it.name }.toSet()
        if (baseName !in existingNames) return baseName
        var counter = 2
        while ("$baseName $counter" in existingNames) {
            counter++
        }
        return "$baseName $counter"
    }

    // Helper to navigate to add agent with pre-filled data
    fun navigateToAddAgent(provider: AiService, apiKey: String, model: String) {
        prefillAgentProvider = provider
        prefillAgentApiKey = apiKey
        prefillAgentModel = model
        currentSubScreen = SettingsSubScreen.AI_ADD_AGENT
    }

    // Handle Android back button
    BackHandler {
        when (currentSubScreen) {
            SettingsSubScreen.MAIN -> onBack()
            SettingsSubScreen.AI_PROVIDER_EDIT -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            SettingsSubScreen.AI_ADD_PROVIDER,
            SettingsSubScreen.AI_EDIT_PROVIDER_DEF -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            // AI screens navigate back to AI_SETUP
            SettingsSubScreen.AI_PROVIDERS,
            SettingsSubScreen.AI_AGENTS,
            SettingsSubScreen.AI_SWARMS,
            SettingsSubScreen.AI_FLOCKS -> {
                currentSubScreen = SettingsSubScreen.AI_SETUP
            }
            // AI Prompts goes back to AI_SETUP
            SettingsSubScreen.AI_PROMPTS -> {
                currentSubScreen = SettingsSubScreen.AI_SETUP
            }
            // AI Parameters goes back to AI_SETUP
            SettingsSubScreen.AI_PARAMETERS -> {
                currentSubScreen = SettingsSubScreen.AI_SETUP
            }
            // AI_SETUP goes back home if it was the initial screen, otherwise to MAIN
            SettingsSubScreen.AI_SETUP -> {
                if (initialSubScreen == SettingsSubScreen.AI_SETUP) {
                    onBack()
                } else {
                    currentSubScreen = SettingsSubScreen.MAIN
                }
            }
            // Add agent goes back to AI_PROVIDERS
            SettingsSubScreen.AI_ADD_AGENT -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            // Flock screens go back to AI_SWARMS
            SettingsSubScreen.AI_ADD_SWARM,
            SettingsSubScreen.AI_EDIT_SWARM -> currentSubScreen = SettingsSubScreen.AI_SWARMS
            // Prompt screens go back to AI_PROMPTS
            SettingsSubScreen.AI_ADD_PROMPT,
            SettingsSubScreen.AI_EDIT_PROMPT -> currentSubScreen = SettingsSubScreen.AI_PROMPTS
            // Parameters screens go back to AI_PARAMETERS
            SettingsSubScreen.AI_ADD_PARAMETERS,
            SettingsSubScreen.AI_EDIT_PARAMETERS -> currentSubScreen = SettingsSubScreen.AI_PARAMETERS
            else -> currentSubScreen = SettingsSubScreen.MAIN
        }
    }

    when (currentSubScreen) {
        SettingsSubScreen.MAIN -> SettingsMainScreen(
            generalSettings = generalSettings,
            onBack = onBack,
            onNavigateHome = onNavigateHome,
            onSave = onSaveGeneral,
            onTrackApiCallsChanged = onTrackApiCallsChanged
        )
        SettingsSubScreen.AI_PROVIDER_EDIT -> {
            val provider = selectedProvider
            if (provider != null) {
                ProviderSettingsScreen(
                    service = provider,
                    aiSettings = aiSettings,
                    isLoadingModels = provider in loadingModelsFor,
                    onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
                    onBackToHome = onNavigateHome,
                    onSave = onSaveAi,
                    onFetchModels = { key -> onFetchModels(provider, key) },
                    onTestApiKey = onTestAiModel,
                    onCreateAgent = { navigateToAddAgent(provider, aiSettings.getApiKey(provider), aiSettings.getModel(provider)) },
                    onProviderStateChange = { state -> onProviderStateChange(provider, state) }
                )
            }
        }
        SettingsSubScreen.AI_ADD_PROVIDER -> ProviderDefinitionEditorScreen(
            provider = null,
            onSave = { newService ->
                com.ai.data.ProviderRegistry.add(newService)
                // Initialize provider config in settings
                onSaveAi(aiSettings.withProvider(newService, defaultProviderConfig(newService)))
                currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            },
            onBack = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onNavigateHome = onNavigateHome
        )
        SettingsSubScreen.AI_EDIT_PROVIDER_DEF -> {
            val provider = selectedProvider
            if (provider != null) {
                ProviderDefinitionEditorScreen(
                    provider = provider,
                    onSave = { updatedService ->
                        com.ai.data.ProviderRegistry.update(updatedService)
                        currentSubScreen = SettingsSubScreen.AI_PROVIDERS
                    },
                    onBack = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
                    onNavigateHome = onNavigateHome
                )
            }
        }
        // Three-tier AI architecture screens
        SettingsSubScreen.AI_SETUP -> AiSetupScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            openRouterApiKey = generalSettings.openRouterApiKey,
            onBackToSettings = {
                // If AI_SETUP is the initial screen (accessed from home), go home
                // Otherwise go back to Settings main screen
                if (initialSubScreen == SettingsSubScreen.AI_SETUP) {
                    onBack()
                } else {
                    currentSubScreen = SettingsSubScreen.MAIN
                }
            },
            onBackToHome = onNavigateHome,
            onNavigate = { currentSubScreen = it },
            onSave = onSaveAi,
            onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey,
            onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
            onRefreshAllModels = onRefreshAllModels,
            onTestApiKey = onTestAiModel,
            onProviderStateChange = onProviderStateChange,
            onNavigateToCostConfig = onNavigateToCostConfig
        )
        SettingsSubScreen.AI_PROVIDERS -> AiProvidersScreen(
            aiSettings = aiSettings,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onProviderSelected = { service ->
                selectedProvider = service
                currentSubScreen = SettingsSubScreen.AI_PROVIDER_EDIT
            },
            onAddProvider = { currentSubScreen = SettingsSubScreen.AI_ADD_PROVIDER },
            onEditProviderDef = { service ->
                selectedProvider = service
                currentSubScreen = SettingsSubScreen.AI_EDIT_PROVIDER_DEF
            },
            onDeleteProvider = { service ->
                com.ai.data.ProviderRegistry.remove(service.id)
                // Remove provider config from settings
                onSaveAi(aiSettings.removeProvider(service))
            }
        )
        SettingsSubScreen.AI_AGENTS -> AiAgentsScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestAiModel = onTestAiModel,
            onFetchModels = onFetchModels
        )
        SettingsSubScreen.AI_ADD_AGENT -> {
            // Calculate prefill name with unique suffix if needed
            val prefillName = prefillAgentProvider?.let { provider ->
                generateUniqueAgentName(provider.displayName)
            } ?: ""

            var addAgentModelSelectProvider by remember { mutableStateOf<AiService?>(null) }
            var addAgentPendingModel by remember { mutableStateOf<String?>(null) }

            val agentModelProvider = addAgentModelSelectProvider
            if (agentModelProvider != null) {
                SelectModelScreen(
                    provider = agentModelProvider,
                    aiSettings = aiSettings,
                    currentModel = "",
                    showDefaultOption = true,
                    onSelectModel = { model ->
                        addAgentPendingModel = model
                        addAgentModelSelectProvider = null
                    },
                    onBack = { addAgentModelSelectProvider = null },
                    onNavigateHome = onNavigateHome
                )
            } else {
                AgentEditScreen(
                    agent = null,
                    aiSettings = aiSettings,
                    developerMode = generalSettings.developerMode,
                    existingNames = aiSettings.agents.map { it.name }.toSet(),
                    onTestAiModel = onTestAiModel,
                    onFetchModelsForProvider = onFetchModels,
                    forceAddMode = true,
                    prefillProvider = prefillAgentProvider,
                    prefillApiKey = prefillAgentApiKey,
                    prefillModel = prefillAgentModel,
                    prefillName = prefillName,
                    onSave = { newAgent ->
                        val newAgents = aiSettings.agents + newAgent
                        onSaveAi(aiSettings.copy(agents = newAgents))
                        // Clear prefill data
                        prefillAgentProvider = null
                        prefillAgentApiKey = ""
                        prefillAgentModel = ""
                        currentSubScreen = SettingsSubScreen.AI_PROVIDERS
                    },
                    onBack = {
                        // Clear prefill data on back
                        prefillAgentProvider = null
                        prefillAgentApiKey = ""
                        prefillAgentModel = ""
                        currentSubScreen = SettingsSubScreen.AI_PROVIDERS
                    },
                    onNavigateHome = onNavigateHome,
                    pendingModelSelection = addAgentPendingModel,
                    onPendingModelConsumed = { addAgentPendingModel = null },
                    onNavigateToSelectModel = { provider ->
                        addAgentModelSelectProvider = provider
                    }
                )
            }
        }
        SettingsSubScreen.AI_SWARMS -> AiFlocksScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onAddFlock = { currentSubScreen = SettingsSubScreen.AI_ADD_SWARM },
            onEditFlock = { flockId ->
                editingFlockId = flockId
                currentSubScreen = SettingsSubScreen.AI_EDIT_SWARM
            }
        )
        SettingsSubScreen.AI_ADD_SWARM -> FlockEditScreen(
            flock = null,
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            existingNames = aiSettings.flocks.map { it.name }.toSet(),
            onSave = { newFlock ->
                val newFlocks = aiSettings.flocks + newFlock
                onSaveAi(aiSettings.copy(flocks = newFlocks))
                currentSubScreen = SettingsSubScreen.AI_SWARMS
            },
            onBack = { currentSubScreen = SettingsSubScreen.AI_SWARMS },
            onNavigateHome = onNavigateHome
        )
        SettingsSubScreen.AI_EDIT_SWARM -> {
            val flock = editingFlockId?.let { aiSettings.getFlockById(it) }
            FlockEditScreen(
                flock = flock,
                aiSettings = aiSettings,
                developerMode = generalSettings.developerMode,
                existingNames = aiSettings.flocks.filter { it.id != editingFlockId }.map { it.name }.toSet(),
                onSave = { updatedFlock ->
                    val newFlocks = aiSettings.flocks.map { if (it.id == updatedFlock.id) updatedFlock else it }
                    onSaveAi(aiSettings.copy(flocks = newFlocks))
                    editingFlockId = null
                    currentSubScreen = SettingsSubScreen.AI_SWARMS
                },
                onBack = {
                    editingFlockId = null
                    currentSubScreen = SettingsSubScreen.AI_SWARMS
                },
                onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_FLOCKS -> AiSwarmsScreen(
            aiSettings = aiSettings,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onAddSwarm = { currentSubScreen = SettingsSubScreen.AI_ADD_FLOCK },
            onEditSwarm = { swarmId ->
                editingSwarmId = swarmId
                currentSubScreen = SettingsSubScreen.AI_EDIT_FLOCK
            }
        )
        SettingsSubScreen.AI_ADD_FLOCK -> SwarmEditScreen(
            swarm = null,
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            existingNames = aiSettings.swarms.map { it.name }.toSet(),
            availableModels = AiService.entries.associateWith { aiSettings.getModels(it) },
            onSave = { newSwarm ->
                val newSwarms = aiSettings.swarms + newSwarm
                onSaveAi(aiSettings.copy(swarms = newSwarms))
                currentSubScreen = SettingsSubScreen.AI_FLOCKS
            },
            onBack = { currentSubScreen = SettingsSubScreen.AI_FLOCKS },
            onNavigateHome = onNavigateHome
        )
        SettingsSubScreen.AI_EDIT_FLOCK -> {
            val swarm = editingSwarmId?.let { aiSettings.getSwarmById(it) }
            SwarmEditScreen(
                swarm = swarm,
                aiSettings = aiSettings,
                developerMode = generalSettings.developerMode,
                existingNames = aiSettings.swarms.filter { it.id != editingSwarmId }.map { it.name }.toSet(),
                availableModels = AiService.entries.associateWith { aiSettings.getModels(it) },
                onSave = { updatedSwarm ->
                    val newSwarms = aiSettings.swarms.map { if (it.id == updatedSwarm.id) updatedSwarm else it }
                    onSaveAi(aiSettings.copy(swarms = newSwarms))
                    editingSwarmId = null
                    currentSubScreen = SettingsSubScreen.AI_FLOCKS
                },
                onBack = {
                    editingSwarmId = null
                    currentSubScreen = SettingsSubScreen.AI_FLOCKS
                },
                onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_PROMPTS -> AiPromptsScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onAddPrompt = { currentSubScreen = SettingsSubScreen.AI_ADD_PROMPT },
            onEditPrompt = { promptId ->
                editingPromptId = promptId
                currentSubScreen = SettingsSubScreen.AI_EDIT_PROMPT
            }
        )
        SettingsSubScreen.AI_ADD_PROMPT -> PromptEditScreen(
            prompt = null,
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            existingNames = aiSettings.prompts.map { it.name }.toSet(),
            onSave = { newPrompt ->
                val newPrompts = aiSettings.prompts + newPrompt
                onSaveAi(aiSettings.copy(prompts = newPrompts))
                currentSubScreen = SettingsSubScreen.AI_PROMPTS
            },
            onBack = { currentSubScreen = SettingsSubScreen.AI_PROMPTS },
            onNavigateHome = onNavigateHome
        )
        SettingsSubScreen.AI_EDIT_PROMPT -> {
            val prompt = editingPromptId?.let { aiSettings.getPromptById(it) }
            PromptEditScreen(
                prompt = prompt,
                aiSettings = aiSettings,
                developerMode = generalSettings.developerMode,
                existingNames = aiSettings.prompts.filter { it.id != editingPromptId }.map { it.name }.toSet(),
                onSave = { updatedPrompt ->
                    val newPrompts = aiSettings.prompts.map { if (it.id == updatedPrompt.id) updatedPrompt else it }
                    onSaveAi(aiSettings.copy(prompts = newPrompts))
                    editingPromptId = null
                    currentSubScreen = SettingsSubScreen.AI_PROMPTS
                },
                onBack = {
                    editingPromptId = null
                    currentSubScreen = SettingsSubScreen.AI_PROMPTS
                },
                onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_PARAMETERS -> AiParametersListScreen(
            aiSettings = aiSettings,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onAddParameters = { currentSubScreen = SettingsSubScreen.AI_ADD_PARAMETERS },
            onEditParameters = { paramsId ->
                editingParametersId = paramsId
                currentSubScreen = SettingsSubScreen.AI_EDIT_PARAMETERS
            }
        )
        SettingsSubScreen.AI_ADD_PARAMETERS -> ParametersEditScreen(
            params = null,
            existingNames = aiSettings.parameters.map { it.name }.toSet(),
            onSave = { newParams ->
                val newParamsList = aiSettings.parameters + newParams
                onSaveAi(aiSettings.copy(parameters = newParamsList))
                currentSubScreen = SettingsSubScreen.AI_PARAMETERS
            },
            onBack = { currentSubScreen = SettingsSubScreen.AI_PARAMETERS },
            onNavigateHome = onNavigateHome
        )
        SettingsSubScreen.AI_EDIT_PARAMETERS -> {
            val params = editingParametersId?.let { aiSettings.getParametersById(it) }
            ParametersEditScreen(
                params = params,
                existingNames = aiSettings.parameters.filter { it.id != editingParametersId }.map { it.name }.toSet(),
                onSave = { updatedParams ->
                    val newParamsList = aiSettings.parameters.map { if (it.id == updatedParams.id) updatedParams else it }
                    onSaveAi(aiSettings.copy(parameters = newParamsList))
                    editingParametersId = null
                    currentSubScreen = SettingsSubScreen.AI_PARAMETERS
                },
                onBack = {
                    editingParametersId = null
                    currentSubScreen = SettingsSubScreen.AI_PARAMETERS
                },
                onNavigateHome = onNavigateHome
            )
        }
    }
}

/**
 * Main settings screen showing general settings directly.
 */
@Composable
private fun SettingsMainScreen(
    generalSettings: GeneralSettings,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onSave: (GeneralSettings) -> Unit,
    onTrackApiCallsChanged: (Boolean) -> Unit = {}
) {
    var userName by remember { mutableStateOf(generalSettings.userName) }
    var developerMode by remember { mutableStateOf(generalSettings.developerMode) }
    var trackApiCalls by remember { mutableStateOf(generalSettings.trackApiCalls) }
    var huggingFaceApiKey by remember { mutableStateOf(generalSettings.huggingFaceApiKey) }
    var openRouterApiKey by remember { mutableStateOf(generalSettings.openRouterApiKey) }
    var fullScreenMode by remember { mutableStateOf(generalSettings.fullScreenMode) }
    var defaultEmail by remember { mutableStateOf(generalSettings.defaultEmail) }

    fun saveSettings() {
        onSave(generalSettings.copy(
            userName = userName.ifBlank { "user" },
            developerMode = developerMode,
            trackApiCalls = trackApiCalls,
            huggingFaceApiKey = huggingFaceApiKey,
            openRouterApiKey = openRouterApiKey,
            fullScreenMode = fullScreenMode,
            defaultEmail = defaultEmail
        ))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title bar
        AiTitleBar(
            title = "Settings",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        // User name card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "User",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                OutlinedTextField(
                    value = userName,
                    onValueChange = {
                        userName = it
                        saveSettings()
                    },
                    label = { Text("Your name") },
                    placeholder = { Text("user") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                OutlinedTextField(
                    value = defaultEmail,
                    onValueChange = {
                        defaultEmail = it
                        saveSettings()
                    },
                    label = { Text("Default email") },
                    placeholder = { Text("name@example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
            }
        }

        // External Services card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "External Services",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                OutlinedTextField(
                    value = huggingFaceApiKey,
                    onValueChange = {
                        huggingFaceApiKey = it
                        saveSettings()
                    },
                    label = { Text("Hugging Face API Key") },
                    placeholder = { Text("hf_...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF9800),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
                Text(
                    text = "Used for fetching model info. Get your token at huggingface.co/settings/tokens",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )

                OutlinedTextField(
                    value = openRouterApiKey,
                    onValueChange = {
                        openRouterApiKey = it
                        saveSettings()
                    },
                    label = { Text("OpenRouter API Key") },
                    placeholder = { Text("sk-or-...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF9800),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )
                Text(
                    text = "Used for AI Housekeeping. Get your key at openrouter.ai/settings/keys",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )
            }
        }

        // Display settings card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Display",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                // Full screen mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Full screen mode", color = Color.White)
                        Text(
                            text = "Hide the system status bar",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                    Switch(
                        checked = fullScreenMode,
                        onCheckedChange = {
                            fullScreenMode = it
                            saveSettings()
                        }
                    )
                }
            }
        }

        // Developer settings card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Developer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                // Developer mode toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Developer mode", color = Color.White)
                        Text(
                            text = "Enable developer features",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                    Switch(
                        checked = developerMode,
                        onCheckedChange = {
                            developerMode = it
                            saveSettings()
                        }
                    )
                }

                HorizontalDivider(color = Color(0xFF404040))

                // Track API calls toggle (disabled when developer mode is off)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Track API calls",
                            color = if (developerMode) Color.White else Color(0xFF666666)
                        )
                        Text(
                            text = "Log all API requests for debugging",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (developerMode) Color(0xFFAAAAAA) else Color(0xFF555555)
                        )
                    }
                    Switch(
                        checked = trackApiCalls,
                        onCheckedChange = {
                            trackApiCalls = it
                            saveSettings()
                            onTrackApiCallsChanged(it)
                        },
                        enabled = developerMode
                    )
                }
            }
        }
    }
}

