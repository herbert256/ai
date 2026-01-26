package com.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    // AI settings structure
    AI_SETTINGS,
    AI_OPENAI,
    AI_ANTHROPIC,
    AI_GOOGLE,
    AI_XAI,
    AI_GROQ,
    AI_DEEPSEEK,
    AI_MISTRAL,
    AI_PERPLEXITY,
    AI_TOGETHER,
    AI_OPENROUTER,
    AI_SILICONFLOW,
    AI_ZAI,
    AI_DUMMY,
    // AI architecture
    AI_SETUP,       // Hub with navigation cards
    AI_PROVIDERS,   // Provider model configuration
    AI_AGENTS,      // Agents CRUD
    AI_ADD_AGENT,   // Add new agent (direct to AgentEditScreen)
    AI_SWARMS,      // Swarms CRUD
    AI_ADD_SWARM,   // Add new swarm
    AI_EDIT_SWARM   // Edit existing swarm
}

/**
 * Root settings screen that manages navigation between settings sub-screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    generalSettings: GeneralSettings,
    aiSettings: AiSettings,
    availableChatGptModels: List<String>,
    isLoadingChatGptModels: Boolean,
    availableGeminiModels: List<String>,
    isLoadingGeminiModels: Boolean,
    availableGrokModels: List<String>,
    isLoadingGrokModels: Boolean,
    availableGroqModels: List<String>,
    isLoadingGroqModels: Boolean,
    availableDeepSeekModels: List<String>,
    isLoadingDeepSeekModels: Boolean,
    availableMistralModels: List<String>,
    isLoadingMistralModels: Boolean,
    availablePerplexityModels: List<String>,
    isLoadingPerplexityModels: Boolean,
    availableTogetherModels: List<String>,
    isLoadingTogetherModels: Boolean,
    availableOpenRouterModels: List<String>,
    isLoadingOpenRouterModels: Boolean,
    availableDummyModels: List<String>,
    isLoadingDummyModels: Boolean,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onTrackApiCallsChanged: (Boolean) -> Unit = {},
    onSaveAi: (AiSettings) -> Unit,
    onFetchChatGptModels: (String) -> Unit,
    onFetchGeminiModels: (String) -> Unit,
    onFetchGrokModels: (String) -> Unit,
    onFetchGroqModels: (String) -> Unit,
    onFetchDeepSeekModels: (String) -> Unit,
    onFetchMistralModels: (String) -> Unit,
    onFetchPerplexityModels: (String) -> Unit,
    onFetchTogetherModels: (String) -> Unit,
    onFetchOpenRouterModels: (String) -> Unit,
    onFetchDummyModels: (String) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onRefreshAllModels: suspend (AiSettings) -> Map<String, Int> = { emptyMap() },
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN
) {
    var currentSubScreen by remember { mutableStateOf(initialSubScreen) }

    // State for pre-filling agent creation from provider screen
    var prefillAgentProvider by remember { mutableStateOf<AiService?>(null) }
    var prefillAgentApiKey by remember { mutableStateOf("") }
    var prefillAgentModel by remember { mutableStateOf("") }

    // State for swarm editing
    var editingSwarmId by remember { mutableStateOf<String?>(null) }

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
            SettingsSubScreen.AI_OPENAI,
            SettingsSubScreen.AI_ANTHROPIC,
            SettingsSubScreen.AI_GOOGLE,
            SettingsSubScreen.AI_XAI,
            SettingsSubScreen.AI_GROQ,
            SettingsSubScreen.AI_DEEPSEEK,
            SettingsSubScreen.AI_MISTRAL,
            SettingsSubScreen.AI_PERPLEXITY,
            SettingsSubScreen.AI_TOGETHER,
            SettingsSubScreen.AI_OPENROUTER,
            SettingsSubScreen.AI_SILICONFLOW,
            SettingsSubScreen.AI_ZAI,
            SettingsSubScreen.AI_DUMMY -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
            // AI screens navigate back to AI_SETUP (or home if AI_SETUP was the initial screen)
            SettingsSubScreen.AI_PROVIDERS,
            SettingsSubScreen.AI_AGENTS,
            SettingsSubScreen.AI_SWARMS -> {
                if (initialSubScreen == SettingsSubScreen.AI_SETUP) {
                    currentSubScreen = SettingsSubScreen.AI_SETUP
                } else {
                    currentSubScreen = SettingsSubScreen.AI_SETUP
                }
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
            // Swarm screens go back to AI_SWARMS
            SettingsSubScreen.AI_ADD_SWARM,
            SettingsSubScreen.AI_EDIT_SWARM -> currentSubScreen = SettingsSubScreen.AI_SWARMS
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
        SettingsSubScreen.AI_SETTINGS -> AiSettingsScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            onBackToSettings = { currentSubScreen = SettingsSubScreen.MAIN },
            onBackToHome = onNavigateHome,
            onNavigate = { currentSubScreen = it },
            onSave = onSaveAi
        )
        SettingsSubScreen.AI_OPENAI -> ChatGptSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableChatGptModels,
            isLoadingModels = isLoadingChatGptModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchChatGptModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.OPENAI, aiSettings.chatGptApiKey, aiSettings.chatGptModel) }
        )
        SettingsSubScreen.AI_ANTHROPIC -> ClaudeSettingsScreen(
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.ANTHROPIC, aiSettings.claudeApiKey, aiSettings.claudeModel) }
        )
        SettingsSubScreen.AI_GOOGLE -> GeminiSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableGeminiModels,
            isLoadingModels = isLoadingGeminiModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGeminiModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.GOOGLE, aiSettings.geminiApiKey, aiSettings.geminiModel) }
        )
        SettingsSubScreen.AI_XAI -> GrokSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableGrokModels,
            isLoadingModels = isLoadingGrokModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGrokModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.XAI, aiSettings.grokApiKey, aiSettings.grokModel) }
        )
        SettingsSubScreen.AI_GROQ -> GroqSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableGroqModels,
            isLoadingModels = isLoadingGroqModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGroqModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.GROQ, aiSettings.groqApiKey, aiSettings.groqModel) }
        )
        SettingsSubScreen.AI_DEEPSEEK -> DeepSeekSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableDeepSeekModels,
            isLoadingModels = isLoadingDeepSeekModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchDeepSeekModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.DEEPSEEK, aiSettings.deepSeekApiKey, aiSettings.deepSeekModel) }
        )
        SettingsSubScreen.AI_MISTRAL -> MistralSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableMistralModels,
            isLoadingModels = isLoadingMistralModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchMistralModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.MISTRAL, aiSettings.mistralApiKey, aiSettings.mistralModel) }
        )
        SettingsSubScreen.AI_PERPLEXITY -> PerplexitySettingsScreen(
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.PERPLEXITY, aiSettings.perplexityApiKey, aiSettings.perplexityModel) }
        )
        SettingsSubScreen.AI_TOGETHER -> TogetherSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableTogetherModels,
            isLoadingModels = isLoadingTogetherModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchTogetherModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.TOGETHER, aiSettings.togetherApiKey, aiSettings.togetherModel) }
        )
        SettingsSubScreen.AI_OPENROUTER -> OpenRouterSettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableOpenRouterModels,
            isLoadingModels = isLoadingOpenRouterModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchOpenRouterModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.OPENROUTER, aiSettings.openRouterApiKey, aiSettings.openRouterModel) }
        )
        SettingsSubScreen.AI_SILICONFLOW -> SiliconFlowSettingsScreen(
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.SILICONFLOW, aiSettings.siliconFlowApiKey, aiSettings.siliconFlowModel) }
        )
        SettingsSubScreen.AI_ZAI -> ZaiSettingsScreen(
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.ZAI, aiSettings.zaiApiKey, aiSettings.zaiModel) }
        )
        SettingsSubScreen.AI_DUMMY -> DummySettingsScreen(
            aiSettings = aiSettings,
            availableModels = availableDummyModels,
            isLoadingModels = isLoadingDummyModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchDummyModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.DUMMY, aiSettings.dummyApiKey, aiSettings.dummyModel) }
        )
        // Three-tier AI architecture screens
        SettingsSubScreen.AI_SETUP -> AiSetupScreen(
            aiSettings = aiSettings,
            huggingFaceApiKey = generalSettings.huggingFaceApiKey,
            developerMode = generalSettings.developerMode,
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
            onRefreshAllModels = onRefreshAllModels,
            onTestApiKey = onTestAiModel,
            onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey
        )
        SettingsSubScreen.AI_PROVIDERS -> AiProvidersScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onNavigate = { currentSubScreen = it }
        )
        SettingsSubScreen.AI_AGENTS -> AiAgentsScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            availableChatGptModels = availableChatGptModels,
            availableGeminiModels = availableGeminiModels,
            availableGrokModels = availableGrokModels,
            availableGroqModels = availableGroqModels,
            availableDeepSeekModels = availableDeepSeekModels,
            availableMistralModels = availableMistralModels,
            availablePerplexityModels = availablePerplexityModels,
            availableTogetherModels = availableTogetherModels,
            availableOpenRouterModels = availableOpenRouterModels,
            availableDummyModels = availableDummyModels,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestAiModel = onTestAiModel,
            onFetchChatGptModels = onFetchChatGptModels,
            onFetchGeminiModels = onFetchGeminiModels,
            onFetchGrokModels = onFetchGrokModels,
            onFetchGroqModels = onFetchGroqModels,
            onFetchDeepSeekModels = onFetchDeepSeekModels,
            onFetchMistralModels = onFetchMistralModels,
            onFetchPerplexityModels = onFetchPerplexityModels,
            onFetchTogetherModels = onFetchTogetherModels,
            onFetchOpenRouterModels = onFetchOpenRouterModels,
            onFetchDummyModels = onFetchDummyModels
        )
        SettingsSubScreen.AI_ADD_AGENT -> {
            // Helper to fetch models for a provider
            val fetchModelsForProvider: (com.ai.data.AiService, String) -> Unit = { provider, apiKey ->
                when (provider) {
                    com.ai.data.AiService.OPENAI -> onFetchChatGptModels(apiKey)
                    com.ai.data.AiService.GOOGLE -> onFetchGeminiModels(apiKey)
                    com.ai.data.AiService.XAI -> onFetchGrokModels(apiKey)
                    com.ai.data.AiService.GROQ -> onFetchGroqModels(apiKey)
                    com.ai.data.AiService.DEEPSEEK -> onFetchDeepSeekModels(apiKey)
                    com.ai.data.AiService.MISTRAL -> onFetchMistralModels(apiKey)
                    com.ai.data.AiService.PERPLEXITY -> onFetchPerplexityModels(apiKey)
                    com.ai.data.AiService.TOGETHER -> onFetchTogetherModels(apiKey)
                    com.ai.data.AiService.OPENROUTER -> onFetchOpenRouterModels(apiKey)
                    com.ai.data.AiService.DUMMY -> onFetchDummyModels(apiKey)
                    com.ai.data.AiService.ANTHROPIC -> {} // Claude has hardcoded models
                    com.ai.data.AiService.SILICONFLOW -> {} // SiliconFlow has hardcoded models
                    com.ai.data.AiService.ZAI -> {} // Z.AI has hardcoded models
                }
            }

            // Calculate prefill name with unique suffix if needed
            val prefillName = prefillAgentProvider?.let { provider ->
                generateUniqueAgentName(provider.displayName)
            } ?: ""

            AgentEditScreen(
                agent = null,
                aiSettings = aiSettings,
                developerMode = generalSettings.developerMode,
                availableChatGptModels = availableChatGptModels,
                availableGeminiModels = availableGeminiModels,
                availableGrokModels = availableGrokModels,
                availableGroqModels = availableGroqModels,
                availableDeepSeekModels = availableDeepSeekModels,
                availableMistralModels = availableMistralModels,
                availablePerplexityModels = availablePerplexityModels,
                availableTogetherModels = availableTogetherModels,
                availableOpenRouterModels = availableOpenRouterModels,
                availableDummyModels = availableDummyModels,
                existingNames = aiSettings.agents.map { it.name }.toSet(),
                onTestAiModel = onTestAiModel,
                onFetchModelsForProvider = fetchModelsForProvider,
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
                onNavigateHome = onNavigateHome
            )
        }
        SettingsSubScreen.AI_SWARMS -> AiSwarmsScreen(
            aiSettings = aiSettings,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onAddSwarm = { currentSubScreen = SettingsSubScreen.AI_ADD_SWARM },
            onEditSwarm = { swarmId ->
                editingSwarmId = swarmId
                currentSubScreen = SettingsSubScreen.AI_EDIT_SWARM
            }
        )
        SettingsSubScreen.AI_ADD_SWARM -> SwarmEditScreen(
            swarm = null,
            aiSettings = aiSettings,
            existingNames = aiSettings.swarms.map { it.name }.toSet(),
            onSave = { newSwarm ->
                val newSwarms = aiSettings.swarms + newSwarm
                onSaveAi(aiSettings.copy(swarms = newSwarms))
                currentSubScreen = SettingsSubScreen.AI_SWARMS
            },
            onBack = { currentSubScreen = SettingsSubScreen.AI_SWARMS },
            onNavigateHome = onNavigateHome
        )
        SettingsSubScreen.AI_EDIT_SWARM -> {
            val swarm = editingSwarmId?.let { aiSettings.getSwarmById(it) }
            SwarmEditScreen(
                swarm = swarm,
                aiSettings = aiSettings,
                existingNames = aiSettings.swarms.filter { it.id != editingSwarmId }.map { it.name }.toSet(),
                onSave = { updatedSwarm ->
                    val newSwarms = aiSettings.swarms.map { if (it.id == updatedSwarm.id) updatedSwarm else it }
                    onSaveAi(aiSettings.copy(swarms = newSwarms))
                    editingSwarmId = null
                    currentSubScreen = SettingsSubScreen.AI_SWARMS
                },
                onBack = {
                    editingSwarmId = null
                    currentSubScreen = SettingsSubScreen.AI_SWARMS
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

    fun saveSettings() {
        onSave(generalSettings.copy(
            userName = userName.ifBlank { "user" },
            developerMode = developerMode,
            trackApiCalls = trackApiCalls,
            huggingFaceApiKey = huggingFaceApiKey
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

/**
 * Reusable navigation card for settings menu.
 */
@Composable
private fun SettingsNavigationCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFAAAAAA)
                )
            }
            Text(
                text = ">",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFF888888)
            )
        }
    }
}
