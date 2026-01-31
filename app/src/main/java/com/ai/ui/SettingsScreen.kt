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
    AI_MOONSHOT,
    AI_COHERE,
    AI_AI21,
    AI_DASHSCOPE,
    AI_FIREWORKS,
    AI_CEREBRAS,
    AI_SAMBANOVA,
    AI_BAICHUAN,
    AI_STEPFUN,
    AI_MINIMAX,
    AI_NVIDIA,
    AI_REPLICATE,
    AI_HUGGINGFACE,
    AI_LAMBDA,
    AI_LEPTON,
    AI_YI,
    AI_DOUBAO,
    AI_REKA,
    AI_WRITER,
    // AI architecture
    AI_SETUP,       // Hub with navigation cards
    AI_AI_SETTINGS, // AI Settings hub (Prompts, Costs)
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
    availableChatGptModels: List<String>,
    isLoadingChatGptModels: Boolean,
    availableClaudeModels: List<String> = emptyList(),
    isLoadingClaudeModels: Boolean = false,
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
    availableSiliconFlowModels: List<String> = emptyList(),
    isLoadingSiliconFlowModels: Boolean = false,
    availableZaiModels: List<String> = emptyList(),
    isLoadingZaiModels: Boolean = false,
    availableMoonshotModels: List<String> = emptyList(),
    isLoadingMoonshotModels: Boolean = false,
    availableCohereModels: List<String> = emptyList(),
    isLoadingCohereModels: Boolean = false,
    availableAi21Models: List<String> = emptyList(),
    isLoadingAi21Models: Boolean = false,
    availableDashScopeModels: List<String> = emptyList(),
    isLoadingDashScopeModels: Boolean = false,
    availableFireworksModels: List<String> = emptyList(),
    isLoadingFireworksModels: Boolean = false,
    availableCerebrasModels: List<String> = emptyList(),
    isLoadingCerebrasModels: Boolean = false,
    availableSambaNovaModels: List<String> = emptyList(),
    isLoadingSambaNovaModels: Boolean = false,
    availableBaichuanModels: List<String> = emptyList(),
    isLoadingBaichuanModels: Boolean = false,
    availableStepFunModels: List<String> = emptyList(),
    isLoadingStepFunModels: Boolean = false,
    availableMiniMaxModels: List<String> = emptyList(),
    isLoadingMiniMaxModels: Boolean = false,
    availableNvidiaModels: List<String> = emptyList(),
    isLoadingNvidiaModels: Boolean = false,
    availableReplicateModels: List<String> = emptyList(),
    isLoadingReplicateModels: Boolean = false,
    availableHuggingFaceInferenceModels: List<String> = emptyList(),
    isLoadingHuggingFaceInferenceModels: Boolean = false,
    availableLambdaModels: List<String> = emptyList(),
    isLoadingLambdaModels: Boolean = false,
    availableLeptonModels: List<String> = emptyList(),
    isLoadingLeptonModels: Boolean = false,
    availableYiModels: List<String> = emptyList(),
    isLoadingYiModels: Boolean = false,
    availableDoubaoModels: List<String> = emptyList(),
    isLoadingDoubaoModels: Boolean = false,
    availableRekaModels: List<String> = emptyList(),
    isLoadingRekaModels: Boolean = false,
    availableWriterModels: List<String> = emptyList(),
    isLoadingWriterModels: Boolean = false,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit = onBack,
    onSaveGeneral: (GeneralSettings) -> Unit,
    onTrackApiCallsChanged: (Boolean) -> Unit = {},
    onSaveAi: (AiSettings) -> Unit,
    onFetchChatGptModels: (String) -> Unit,
    onFetchClaudeModels: (String) -> Unit = {},
    onFetchGeminiModels: (String) -> Unit,
    onFetchGrokModels: (String) -> Unit,
    onFetchGroqModels: (String) -> Unit,
    onFetchDeepSeekModels: (String) -> Unit,
    onFetchMistralModels: (String) -> Unit,
    onFetchPerplexityModels: (String) -> Unit,
    onFetchTogetherModels: (String) -> Unit,
    onFetchOpenRouterModels: (String) -> Unit,
    onFetchSiliconFlowModels: (String) -> Unit = {},
    onFetchZaiModels: (String) -> Unit = {},
    onFetchMoonshotModels: (String) -> Unit = {},
    onFetchCohereModels: (String) -> Unit = {},
    onFetchAi21Models: (String) -> Unit = {},
    onFetchDashScopeModels: (String) -> Unit = {},
    onFetchFireworksModels: (String) -> Unit = {},
    onFetchCerebrasModels: (String) -> Unit = {},
    onFetchSambaNovaModels: (String) -> Unit = {},
    onFetchBaichuanModels: (String) -> Unit = {},
    onFetchStepFunModels: (String) -> Unit = {},
    onFetchMiniMaxModels: (String) -> Unit = {},
    onFetchNvidiaModels: (String) -> Unit = {},
    onFetchReplicateModels: (String) -> Unit = {},
    onFetchHuggingFaceInferenceModels: (String) -> Unit = {},
    onFetchLambdaModels: (String) -> Unit = {},
    onFetchLeptonModels: (String) -> Unit = {},
    onFetchYiModels: (String) -> Unit = {},
    onFetchDoubaoModels: (String) -> Unit = {},
    onFetchRekaModels: (String) -> Unit = {},
    onFetchWriterModels: (String) -> Unit = {},
    onTestAiModel: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onProviderStateChange: (AiService, String) -> Unit = { _, _ -> },
    onRefreshAllModels: suspend (AiSettings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() },
    onSaveHuggingFaceApiKey: (String) -> Unit = {},
    onSaveOpenRouterApiKey: (String) -> Unit = {},
    onNavigateToCostConfig: () -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.MAIN
) {
    var currentSubScreen by remember { mutableStateOf(initialSubScreen) }

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
            SettingsSubScreen.AI_MOONSHOT,
            SettingsSubScreen.AI_COHERE,
            SettingsSubScreen.AI_AI21,
            SettingsSubScreen.AI_DASHSCOPE,
            SettingsSubScreen.AI_FIREWORKS,
            SettingsSubScreen.AI_CEREBRAS,
            SettingsSubScreen.AI_SAMBANOVA,
            SettingsSubScreen.AI_BAICHUAN,
            SettingsSubScreen.AI_STEPFUN,
            SettingsSubScreen.AI_MINIMAX,
            SettingsSubScreen.AI_NVIDIA,
            SettingsSubScreen.AI_REPLICATE,
            SettingsSubScreen.AI_HUGGINGFACE,
            SettingsSubScreen.AI_LAMBDA,
            SettingsSubScreen.AI_LEPTON,
            SettingsSubScreen.AI_YI,
            SettingsSubScreen.AI_DOUBAO,
            SettingsSubScreen.AI_REKA,
            SettingsSubScreen.AI_WRITER -> currentSubScreen = SettingsSubScreen.AI_PROVIDERS
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
            // AI_AI_SETTINGS is deprecated - redirect to AI_SETUP
            SettingsSubScreen.AI_AI_SETTINGS -> {
                currentSubScreen = SettingsSubScreen.AI_SETUP
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
        SettingsSubScreen.AI_SETTINGS -> AiSettingsScreen(
            aiSettings = aiSettings,
            developerMode = generalSettings.developerMode,
            huggingFaceApiKey = generalSettings.huggingFaceApiKey,
            openRouterApiKey = generalSettings.openRouterApiKey,
            onBackToSettings = { currentSubScreen = SettingsSubScreen.MAIN },
            onBackToHome = onNavigateHome,
            onNavigate = { currentSubScreen = it },
            onSave = onSaveAi,
            onSaveHuggingFaceApiKey = onSaveHuggingFaceApiKey,
            onSaveOpenRouterApiKey = onSaveOpenRouterApiKey,
            onRefreshAllModels = onRefreshAllModels,
            onTestApiKey = onTestAiModel,
            onProviderStateChange = onProviderStateChange
        )
        SettingsSubScreen.AI_OPENAI -> ProviderSettingsScreen(
            service = AiService.OPENAI,
            aiSettings = aiSettings,
            availableModels = availableChatGptModels,
            isLoadingModels = isLoadingChatGptModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchChatGptModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.OPENAI, aiSettings.getApiKey(AiService.OPENAI), aiSettings.getModel(AiService.OPENAI)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.OPENAI, state) }
        )
        SettingsSubScreen.AI_ANTHROPIC -> ProviderSettingsScreen(
            service = AiService.ANTHROPIC,
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.ANTHROPIC, aiSettings.getApiKey(AiService.ANTHROPIC), aiSettings.getModel(AiService.ANTHROPIC)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.ANTHROPIC, state) }
        )
        SettingsSubScreen.AI_GOOGLE -> ProviderSettingsScreen(
            service = AiService.GOOGLE,
            aiSettings = aiSettings,
            availableModels = availableGeminiModels,
            isLoadingModels = isLoadingGeminiModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGeminiModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.GOOGLE, aiSettings.getApiKey(AiService.GOOGLE), aiSettings.getModel(AiService.GOOGLE)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.GOOGLE, state) }
        )
        SettingsSubScreen.AI_XAI -> ProviderSettingsScreen(
            service = AiService.XAI,
            aiSettings = aiSettings,
            availableModels = availableGrokModels,
            isLoadingModels = isLoadingGrokModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGrokModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.XAI, aiSettings.getApiKey(AiService.XAI), aiSettings.getModel(AiService.XAI)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.XAI, state) }
        )
        SettingsSubScreen.AI_GROQ -> ProviderSettingsScreen(
            service = AiService.GROQ,
            aiSettings = aiSettings,
            availableModels = availableGroqModels,
            isLoadingModels = isLoadingGroqModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchGroqModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.GROQ, aiSettings.getApiKey(AiService.GROQ), aiSettings.getModel(AiService.GROQ)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.GROQ, state) }
        )
        SettingsSubScreen.AI_DEEPSEEK -> ProviderSettingsScreen(
            service = AiService.DEEPSEEK,
            aiSettings = aiSettings,
            availableModels = availableDeepSeekModels,
            isLoadingModels = isLoadingDeepSeekModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchDeepSeekModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.DEEPSEEK, aiSettings.getApiKey(AiService.DEEPSEEK), aiSettings.getModel(AiService.DEEPSEEK)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.DEEPSEEK, state) }
        )
        SettingsSubScreen.AI_MISTRAL -> ProviderSettingsScreen(
            service = AiService.MISTRAL,
            aiSettings = aiSettings,
            availableModels = availableMistralModels,
            isLoadingModels = isLoadingMistralModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchMistralModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.MISTRAL, aiSettings.getApiKey(AiService.MISTRAL), aiSettings.getModel(AiService.MISTRAL)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.MISTRAL, state) }
        )
        SettingsSubScreen.AI_PERPLEXITY -> ProviderSettingsScreen(
            service = AiService.PERPLEXITY,
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.PERPLEXITY, aiSettings.getApiKey(AiService.PERPLEXITY), aiSettings.getModel(AiService.PERPLEXITY)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.PERPLEXITY, state) }
        )
        SettingsSubScreen.AI_TOGETHER -> ProviderSettingsScreen(
            service = AiService.TOGETHER,
            aiSettings = aiSettings,
            availableModels = availableTogetherModels,
            isLoadingModels = isLoadingTogetherModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchTogetherModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.TOGETHER, aiSettings.getApiKey(AiService.TOGETHER), aiSettings.getModel(AiService.TOGETHER)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.TOGETHER, state) }
        )
        SettingsSubScreen.AI_OPENROUTER -> ProviderSettingsScreen(
            service = AiService.OPENROUTER,
            aiSettings = aiSettings,
            availableModels = availableOpenRouterModels,
            isLoadingModels = isLoadingOpenRouterModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchOpenRouterModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.OPENROUTER, aiSettings.getApiKey(AiService.OPENROUTER), aiSettings.getModel(AiService.OPENROUTER)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.OPENROUTER, state) }
        )
        SettingsSubScreen.AI_SILICONFLOW -> ProviderSettingsScreen(
            service = AiService.SILICONFLOW,
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.SILICONFLOW, aiSettings.getApiKey(AiService.SILICONFLOW), aiSettings.getModel(AiService.SILICONFLOW)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.SILICONFLOW, state) }
        )
        SettingsSubScreen.AI_ZAI -> ProviderSettingsScreen(
            service = AiService.ZAI,
            aiSettings = aiSettings,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.ZAI, aiSettings.getApiKey(AiService.ZAI), aiSettings.getModel(AiService.ZAI)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.ZAI, state) }
        )
        SettingsSubScreen.AI_MOONSHOT -> ProviderSettingsScreen(
            service = AiService.MOONSHOT,
            aiSettings = aiSettings,
            availableModels = availableMoonshotModels,
            isLoadingModels = isLoadingMoonshotModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchMoonshotModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.MOONSHOT, aiSettings.getApiKey(AiService.MOONSHOT), aiSettings.getModel(AiService.MOONSHOT)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.MOONSHOT, state) }
        )
        SettingsSubScreen.AI_COHERE -> ProviderSettingsScreen(
            service = AiService.COHERE,
            aiSettings = aiSettings,
            availableModels = availableCohereModels,
            isLoadingModels = isLoadingCohereModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchCohereModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.COHERE, aiSettings.getApiKey(AiService.COHERE), aiSettings.getModel(AiService.COHERE)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.COHERE, state) }
        )
        SettingsSubScreen.AI_AI21 -> ProviderSettingsScreen(
            service = AiService.AI21,
            aiSettings = aiSettings,
            availableModels = availableAi21Models,
            isLoadingModels = isLoadingAi21Models,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchAi21Models,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.AI21, aiSettings.getApiKey(AiService.AI21), aiSettings.getModel(AiService.AI21)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.AI21, state) }
        )
        SettingsSubScreen.AI_DASHSCOPE -> ProviderSettingsScreen(
            service = AiService.DASHSCOPE,
            aiSettings = aiSettings,
            availableModels = availableDashScopeModels,
            isLoadingModels = isLoadingDashScopeModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchDashScopeModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.DASHSCOPE, aiSettings.getApiKey(AiService.DASHSCOPE), aiSettings.getModel(AiService.DASHSCOPE)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.DASHSCOPE, state) }
        )
        SettingsSubScreen.AI_FIREWORKS -> ProviderSettingsScreen(
            service = AiService.FIREWORKS,
            aiSettings = aiSettings,
            availableModels = availableFireworksModels,
            isLoadingModels = isLoadingFireworksModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchFireworksModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.FIREWORKS, aiSettings.getApiKey(AiService.FIREWORKS), aiSettings.getModel(AiService.FIREWORKS)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.FIREWORKS, state) }
        )
        SettingsSubScreen.AI_CEREBRAS -> ProviderSettingsScreen(
            service = AiService.CEREBRAS,
            aiSettings = aiSettings,
            availableModels = availableCerebrasModels,
            isLoadingModels = isLoadingCerebrasModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchCerebrasModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.CEREBRAS, aiSettings.getApiKey(AiService.CEREBRAS), aiSettings.getModel(AiService.CEREBRAS)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.CEREBRAS, state) }
        )
        SettingsSubScreen.AI_SAMBANOVA -> ProviderSettingsScreen(
            service = AiService.SAMBANOVA,
            aiSettings = aiSettings,
            availableModels = availableSambaNovaModels,
            isLoadingModels = isLoadingSambaNovaModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchSambaNovaModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.SAMBANOVA, aiSettings.getApiKey(AiService.SAMBANOVA), aiSettings.getModel(AiService.SAMBANOVA)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.SAMBANOVA, state) }
        )
        SettingsSubScreen.AI_BAICHUAN -> ProviderSettingsScreen(
            service = AiService.BAICHUAN,
            aiSettings = aiSettings,
            availableModels = availableBaichuanModels,
            isLoadingModels = isLoadingBaichuanModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchBaichuanModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.BAICHUAN, aiSettings.getApiKey(AiService.BAICHUAN), aiSettings.getModel(AiService.BAICHUAN)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.BAICHUAN, state) }
        )
        SettingsSubScreen.AI_STEPFUN -> ProviderSettingsScreen(
            service = AiService.STEPFUN,
            aiSettings = aiSettings,
            availableModels = availableStepFunModels,
            isLoadingModels = isLoadingStepFunModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchStepFunModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.STEPFUN, aiSettings.getApiKey(AiService.STEPFUN), aiSettings.getModel(AiService.STEPFUN)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.STEPFUN, state) }
        )
        SettingsSubScreen.AI_MINIMAX -> ProviderSettingsScreen(
            service = AiService.MINIMAX,
            aiSettings = aiSettings,
            availableModels = availableMiniMaxModels,
            isLoadingModels = isLoadingMiniMaxModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchMiniMaxModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.MINIMAX, aiSettings.getApiKey(AiService.MINIMAX), aiSettings.getModel(AiService.MINIMAX)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.MINIMAX, state) }
        )
        SettingsSubScreen.AI_NVIDIA -> ProviderSettingsScreen(
            service = AiService.NVIDIA,
            aiSettings = aiSettings,
            availableModels = availableNvidiaModels,
            isLoadingModels = isLoadingNvidiaModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchNvidiaModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.NVIDIA, aiSettings.getApiKey(AiService.NVIDIA), aiSettings.getModel(AiService.NVIDIA)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.NVIDIA, state) }
        )
        SettingsSubScreen.AI_REPLICATE -> ProviderSettingsScreen(
            service = AiService.REPLICATE,
            aiSettings = aiSettings,
            availableModels = availableReplicateModels,
            isLoadingModels = isLoadingReplicateModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchReplicateModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.REPLICATE, aiSettings.getApiKey(AiService.REPLICATE), aiSettings.getModel(AiService.REPLICATE)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.REPLICATE, state) }
        )
        SettingsSubScreen.AI_HUGGINGFACE -> ProviderSettingsScreen(
            service = AiService.HUGGINGFACE,
            aiSettings = aiSettings,
            availableModels = availableHuggingFaceInferenceModels,
            isLoadingModels = isLoadingHuggingFaceInferenceModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchHuggingFaceInferenceModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.HUGGINGFACE, aiSettings.getApiKey(AiService.HUGGINGFACE), aiSettings.getModel(AiService.HUGGINGFACE)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.HUGGINGFACE, state) }
        )
        SettingsSubScreen.AI_LAMBDA -> ProviderSettingsScreen(
            service = AiService.LAMBDA,
            aiSettings = aiSettings,
            availableModels = availableLambdaModels,
            isLoadingModels = isLoadingLambdaModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchLambdaModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.LAMBDA, aiSettings.getApiKey(AiService.LAMBDA), aiSettings.getModel(AiService.LAMBDA)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.LAMBDA, state) }
        )
        SettingsSubScreen.AI_LEPTON -> ProviderSettingsScreen(
            service = AiService.LEPTON,
            aiSettings = aiSettings,
            availableModels = availableLeptonModels,
            isLoadingModels = isLoadingLeptonModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchLeptonModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.LEPTON, aiSettings.getApiKey(AiService.LEPTON), aiSettings.getModel(AiService.LEPTON)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.LEPTON, state) }
        )
        SettingsSubScreen.AI_YI -> ProviderSettingsScreen(
            service = AiService.YI,
            aiSettings = aiSettings,
            availableModels = availableYiModels,
            isLoadingModels = isLoadingYiModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchYiModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.YI, aiSettings.getApiKey(AiService.YI), aiSettings.getModel(AiService.YI)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.YI, state) }
        )
        SettingsSubScreen.AI_DOUBAO -> ProviderSettingsScreen(
            service = AiService.DOUBAO,
            aiSettings = aiSettings,
            availableModels = availableDoubaoModels,
            isLoadingModels = isLoadingDoubaoModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchDoubaoModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.DOUBAO, aiSettings.getApiKey(AiService.DOUBAO), aiSettings.getModel(AiService.DOUBAO)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.DOUBAO, state) }
        )
        SettingsSubScreen.AI_REKA -> ProviderSettingsScreen(
            service = AiService.REKA,
            aiSettings = aiSettings,
            availableModels = availableRekaModels,
            isLoadingModels = isLoadingRekaModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchRekaModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.REKA, aiSettings.getApiKey(AiService.REKA), aiSettings.getModel(AiService.REKA)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.REKA, state) }
        )
        SettingsSubScreen.AI_WRITER -> ProviderSettingsScreen(
            service = AiService.WRITER,
            aiSettings = aiSettings,
            availableModels = availableWriterModels,
            isLoadingModels = isLoadingWriterModels,
            onBackToAiSettings = { currentSubScreen = SettingsSubScreen.AI_PROVIDERS },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onFetchModels = onFetchWriterModels,
            onTestApiKey = onTestAiModel,
            onCreateAgent = { navigateToAddAgent(AiService.WRITER, aiSettings.getApiKey(AiService.WRITER), aiSettings.getModel(AiService.WRITER)) },
            onProviderStateChange = { state -> onProviderStateChange(AiService.WRITER, state) }
        )
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
        // AI_AI_SETTINGS is deprecated - redirect to AI_SETUP
        SettingsSubScreen.AI_AI_SETTINGS -> {
            LaunchedEffect(Unit) { currentSubScreen = SettingsSubScreen.AI_SETUP }
        }
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
            availableClaudeModels = availableClaudeModels,
            availableGeminiModels = availableGeminiModels,
            availableGrokModels = availableGrokModels,
            availableGroqModels = availableGroqModels,
            availableDeepSeekModels = availableDeepSeekModels,
            availableMistralModels = availableMistralModels,
            availablePerplexityModels = availablePerplexityModels,
            availableTogetherModels = availableTogetherModels,
            availableOpenRouterModels = availableOpenRouterModels,
            availableSiliconFlowModels = availableSiliconFlowModels,
            availableZaiModels = availableZaiModels,
            availableMoonshotModels = availableMoonshotModels,
            availableCohereModels = availableCohereModels,
            availableAi21Models = availableAi21Models,
            availableDashScopeModels = availableDashScopeModels,
            availableFireworksModels = availableFireworksModels,
            availableCerebrasModels = availableCerebrasModels,
            availableSambaNovaModels = availableSambaNovaModels,
            availableBaichuanModels = availableBaichuanModels,
            availableStepFunModels = availableStepFunModels,
            availableMiniMaxModels = availableMiniMaxModels,
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_SETUP },
            onBackToHome = onNavigateHome,
            onSave = onSaveAi,
            onTestAiModel = onTestAiModel,
            onFetchChatGptModels = onFetchChatGptModels,
            onFetchClaudeModels = onFetchClaudeModels,
            onFetchGeminiModels = onFetchGeminiModels,
            onFetchGrokModels = onFetchGrokModels,
            onFetchGroqModels = onFetchGroqModels,
            onFetchDeepSeekModels = onFetchDeepSeekModels,
            onFetchMistralModels = onFetchMistralModels,
            onFetchPerplexityModels = onFetchPerplexityModels,
            onFetchTogetherModels = onFetchTogetherModels,
            onFetchOpenRouterModels = onFetchOpenRouterModels,
            onFetchSiliconFlowModels = onFetchSiliconFlowModels,
            onFetchZaiModels = onFetchZaiModels,
            onFetchMoonshotModels = onFetchMoonshotModels,
            onFetchCohereModels = onFetchCohereModels,
            onFetchAi21Models = onFetchAi21Models,
            onFetchDashScopeModels = onFetchDashScopeModels,
            onFetchFireworksModels = onFetchFireworksModels,
            onFetchCerebrasModels = onFetchCerebrasModels,
            onFetchSambaNovaModels = onFetchSambaNovaModels,
            onFetchBaichuanModels = onFetchBaichuanModels,
            onFetchStepFunModels = onFetchStepFunModels,
            onFetchMiniMaxModels = onFetchMiniMaxModels
        )
        SettingsSubScreen.AI_ADD_AGENT -> {
            // Helper to fetch models for a provider
            val fetchModelsForProvider: (com.ai.data.AiService, String) -> Unit = { provider, apiKey ->
                when (provider) {
                    com.ai.data.AiService.OPENAI -> onFetchChatGptModels(apiKey)
                    com.ai.data.AiService.ANTHROPIC -> onFetchClaudeModels(apiKey)
                    com.ai.data.AiService.GOOGLE -> onFetchGeminiModels(apiKey)
                    com.ai.data.AiService.XAI -> onFetchGrokModels(apiKey)
                    com.ai.data.AiService.GROQ -> onFetchGroqModels(apiKey)
                    com.ai.data.AiService.DEEPSEEK -> onFetchDeepSeekModels(apiKey)
                    com.ai.data.AiService.MISTRAL -> onFetchMistralModels(apiKey)
                    com.ai.data.AiService.PERPLEXITY -> onFetchPerplexityModels(apiKey)
                    com.ai.data.AiService.TOGETHER -> onFetchTogetherModels(apiKey)
                    com.ai.data.AiService.OPENROUTER -> onFetchOpenRouterModels(apiKey)
                    com.ai.data.AiService.SILICONFLOW -> onFetchSiliconFlowModels(apiKey)
                    com.ai.data.AiService.ZAI -> onFetchZaiModels(apiKey)
                    com.ai.data.AiService.MOONSHOT -> onFetchMoonshotModels(apiKey)
                    com.ai.data.AiService.COHERE -> onFetchCohereModels(apiKey)
                    com.ai.data.AiService.AI21 -> onFetchAi21Models(apiKey)
                    com.ai.data.AiService.DASHSCOPE -> onFetchDashScopeModels(apiKey)
                    com.ai.data.AiService.FIREWORKS -> onFetchFireworksModels(apiKey)
                    com.ai.data.AiService.CEREBRAS -> onFetchCerebrasModels(apiKey)
                    com.ai.data.AiService.SAMBANOVA -> onFetchSambaNovaModels(apiKey)
                    com.ai.data.AiService.BAICHUAN -> onFetchBaichuanModels(apiKey)
                    com.ai.data.AiService.STEPFUN -> onFetchStepFunModels(apiKey)
                    com.ai.data.AiService.MINIMAX -> onFetchMiniMaxModels(apiKey)
                    com.ai.data.AiService.NVIDIA -> onFetchNvidiaModels(apiKey)
                    com.ai.data.AiService.REPLICATE -> onFetchReplicateModels(apiKey)
                    com.ai.data.AiService.HUGGINGFACE -> onFetchHuggingFaceInferenceModels(apiKey)
                    com.ai.data.AiService.LAMBDA -> onFetchLambdaModels(apiKey)
                    com.ai.data.AiService.LEPTON -> onFetchLeptonModels(apiKey)
                    com.ai.data.AiService.YI -> onFetchYiModels(apiKey)
                    com.ai.data.AiService.DOUBAO -> onFetchDoubaoModels(apiKey)
                    com.ai.data.AiService.REKA -> onFetchRekaModels(apiKey)
                    com.ai.data.AiService.WRITER -> onFetchWriterModels(apiKey)
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
                availableClaudeModels = availableClaudeModels,
                availableGeminiModels = availableGeminiModels,
                availableGrokModels = availableGrokModels,
                availableGroqModels = availableGroqModels,
                availableDeepSeekModels = availableDeepSeekModels,
                availableMistralModels = availableMistralModels,
                availablePerplexityModels = availablePerplexityModels,
                availableTogetherModels = availableTogetherModels,
                availableOpenRouterModels = availableOpenRouterModels,
                availableSiliconFlowModels = availableSiliconFlowModels,
                availableZaiModels = availableZaiModels,
                availableMoonshotModels = availableMoonshotModels,
                availableCohereModels = availableCohereModels,
                availableAi21Models = availableAi21Models,
                availableDashScopeModels = availableDashScopeModels,
                availableFireworksModels = availableFireworksModels,
                availableCerebrasModels = availableCerebrasModels,
                availableSambaNovaModels = availableSambaNovaModels,
                availableBaichuanModels = availableBaichuanModels,
                availableStepFunModels = availableStepFunModels,
                availableMiniMaxModels = availableMiniMaxModels,
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
            developerMode = generalSettings.developerMode,
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
            availableModels = mapOf(
                AiService.OPENAI to availableChatGptModels,
                AiService.ANTHROPIC to availableClaudeModels,
                AiService.GOOGLE to availableGeminiModels,
                AiService.XAI to availableGrokModels,
                AiService.GROQ to availableGroqModels,
                AiService.DEEPSEEK to availableDeepSeekModels,
                AiService.MISTRAL to availableMistralModels,
                AiService.PERPLEXITY to availablePerplexityModels,
                AiService.TOGETHER to availableTogetherModels,
                AiService.OPENROUTER to availableOpenRouterModels,
                AiService.SILICONFLOW to availableSiliconFlowModels,
                AiService.ZAI to availableZaiModels,
                AiService.MOONSHOT to availableMoonshotModels,
                AiService.COHERE to availableCohereModels,
                AiService.AI21 to availableAi21Models,
                AiService.DASHSCOPE to availableDashScopeModels,
                AiService.FIREWORKS to availableFireworksModels,
                AiService.CEREBRAS to availableCerebrasModels,
                AiService.SAMBANOVA to availableSambaNovaModels,
                AiService.BAICHUAN to availableBaichuanModels,
                AiService.STEPFUN to availableStepFunModels,
                AiService.MINIMAX to availableMiniMaxModels,
                AiService.NVIDIA to availableNvidiaModels,
                AiService.REPLICATE to availableReplicateModels,
                AiService.HUGGINGFACE to availableHuggingFaceInferenceModels,
                AiService.LAMBDA to availableLambdaModels,
                AiService.LEPTON to availableLeptonModels,
                AiService.YI to availableYiModels,
                AiService.DOUBAO to availableDoubaoModels,
                AiService.REKA to availableRekaModels,
                AiService.WRITER to availableWriterModels
            ),
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
                availableModels = mapOf(
                    AiService.OPENAI to availableChatGptModels,
                    AiService.ANTHROPIC to availableClaudeModels,
                    AiService.GOOGLE to availableGeminiModels,
                    AiService.XAI to availableGrokModels,
                    AiService.GROQ to availableGroqModels,
                    AiService.DEEPSEEK to availableDeepSeekModels,
                    AiService.MISTRAL to availableMistralModels,
                    AiService.PERPLEXITY to availablePerplexityModels,
                    AiService.TOGETHER to availableTogetherModels,
                    AiService.OPENROUTER to availableOpenRouterModels,
                    AiService.SILICONFLOW to availableSiliconFlowModels,
                    AiService.ZAI to availableZaiModels,
                    AiService.MOONSHOT to availableMoonshotModels,
                    AiService.COHERE to availableCohereModels,
                    AiService.AI21 to availableAi21Models,
                    AiService.DASHSCOPE to availableDashScopeModels,
                    AiService.FIREWORKS to availableFireworksModels,
                    AiService.CEREBRAS to availableCerebrasModels,
                    AiService.SAMBANOVA to availableSambaNovaModels,
                    AiService.BAICHUAN to availableBaichuanModels,
                    AiService.STEPFUN to availableStepFunModels,
                    AiService.MINIMAX to availableMiniMaxModels,
                    AiService.NVIDIA to availableNvidiaModels,
                    AiService.REPLICATE to availableReplicateModels,
                    AiService.HUGGINGFACE to availableHuggingFaceInferenceModels,
                    AiService.LAMBDA to availableLambdaModels,
                    AiService.LEPTON to availableLeptonModels,
                    AiService.YI to availableYiModels,
                    AiService.DOUBAO to availableDoubaoModels,
                    AiService.REKA to availableRekaModels,
                    AiService.WRITER to availableWriterModels
                ),
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
            onBackToAiSetup = { currentSubScreen = SettingsSubScreen.AI_AI_SETTINGS },
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

    fun saveSettings() {
        onSave(generalSettings.copy(
            userName = userName.ifBlank { "user" },
            developerMode = developerMode,
            trackApiCalls = trackApiCalls,
            huggingFaceApiKey = huggingFaceApiKey,
            openRouterApiKey = openRouterApiKey,
            fullScreenMode = fullScreenMode
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
