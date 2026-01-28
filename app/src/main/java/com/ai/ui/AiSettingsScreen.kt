package com.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService
import kotlinx.coroutines.launch

/**
 * Main AI settings screen with navigation cards for each AI service.
 */
@Composable
fun AiSettingsScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    huggingFaceApiKey: String = "",
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit,
    onSaveHuggingFaceApiKey: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importAiConfigFromFile(context, it, aiSettings)
            if (result != null) {
                onSave(result.aiSettings)
                result.huggingFaceApiKey?.let { key -> onSaveHuggingFaceApiKey(key) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Analysis",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "AI Services",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp)
        )

        AiServiceNavigationCard(
            title = "OpenAI",
            accentColor = Color(0xFF10A37F),
            adminUrl = AiService.OPENAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_OPENAI) }
        )
        AiServiceNavigationCard(
            title = "Anthropic",
            accentColor = Color(0xFFD97706),
            adminUrl = AiService.ANTHROPIC.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_ANTHROPIC) }
        )
        AiServiceNavigationCard(
            title = "Google",
            accentColor = Color(0xFF4285F4),
            adminUrl = AiService.GOOGLE.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_GOOGLE) }
        )
        AiServiceNavigationCard(
            title = "xAI",
            accentColor = Color(0xFFFFFFFF),
            adminUrl = AiService.XAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_XAI) }
        )
        AiServiceNavigationCard(
            title = "Groq",
            accentColor = Color(0xFFF55036),
            adminUrl = AiService.GROQ.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_GROQ) }
        )
        AiServiceNavigationCard(
            title = "DeepSeek",
            accentColor = Color(0xFF4D6BFE),
            adminUrl = AiService.DEEPSEEK.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_DEEPSEEK) }
        )
        AiServiceNavigationCard(
            title = "Mistral",
            accentColor = Color(0xFFFF7000),
            adminUrl = AiService.MISTRAL.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_MISTRAL) }
        )
        AiServiceNavigationCard(
            title = "Perplexity",
            accentColor = Color(0xFF20B2AA),
            adminUrl = AiService.PERPLEXITY.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_PERPLEXITY) }
        )
        AiServiceNavigationCard(
            title = "Together",
            accentColor = Color(0xFF6366F1),
            adminUrl = AiService.TOGETHER.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_TOGETHER) }
        )
        AiServiceNavigationCard(
            title = "OpenRouter",
            accentColor = Color(0xFF6B5AED),
            adminUrl = AiService.OPENROUTER.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_OPENROUTER) }
        )
        AiServiceNavigationCard(
            title = "SiliconFlow",
            accentColor = Color(0xFF00B4D8),
            adminUrl = AiService.SILICONFLOW.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_SILICONFLOW) }
        )
        AiServiceNavigationCard(
            title = "Z.AI",
            accentColor = Color(0xFF6366F1),
            adminUrl = AiService.ZAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_ZAI) }
        )
        // Dummy provider only visible in developer mode
        if (developerMode) {
            AiServiceNavigationCard(
                title = "Dummy",
                accentColor = Color(0xFF888888),
                adminUrl = AiService.DUMMY.adminUrl,
                onEdit = { onNavigate(SettingsSubScreen.AI_DUMMY) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Export AI configuration button
        if (aiSettings.hasAnyApiKey()) {
            Button(
                onClick = {
                    exportAiConfigToFile(context, aiSettings, huggingFaceApiKey)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("Export AI configuration")
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Export API keys only button
            Button(
                onClick = {
                    exportApiKeysToClipboard(context, aiSettings)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196F3)
                )
            ) {
                Text("Export API keys")
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Import AI configuration button (opens file picker)
        Button(
            onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            )
        ) {
            Text("Import AI configuration")
        }

    }
}

/**
 * AI Setup hub screen with navigation cards for Providers, Prompts, and Agents.
 */
@Composable
fun AiSetupScreen(
    aiSettings: AiSettings,
    developerMode: Boolean = false,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onNavigateToCostConfig: () -> Unit = {},
    onNavigateToApiTest: () -> Unit = {},
    onSave: (AiSettings) -> Unit = {},
    onSaveHuggingFaceApiKey: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importAiConfigFromFile(context, it, aiSettings)
            if (result != null) {
                onSave(result.aiSettings)
                result.huggingFaceApiKey?.let { key -> onSaveHuggingFaceApiKey(key) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Setup",
            onBackClick = onBackToSettings,
            onAiClick = onBackToHome
        )

        // Import AI configuration button - only show if no swarm named "default agents"
        val hasDefaultAgentsSwarm = aiSettings.swarms.any { it.name.equals("default agents", ignoreCase = true) }
        if (!hasDefaultAgentsSwarm) {
            Button(
                onClick = {
                    filePickerLauncher.launch(arrayOf("application/json", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Import AI configuration")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Summary info (exclude DUMMY agents when not in developer mode)
        val configuredAgents = aiSettings.agents.count { agent ->
            agent.apiKey.isNotBlank() && (developerMode || agent.provider != AiService.DUMMY)
        }

        // AI Providers card (exclude DUMMY when not in developer mode)
        val providerCount = if (developerMode) AiService.entries.size else AiService.entries.size - 1
        AiSetupNavigationCard(
            title = "AI Providers",
            description = "Configure model sources for each AI service",
            icon = "⚙",
            count = "$providerCount providers",
            onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) }
        )

        // AI Agents card
        val hasApiKey = aiSettings.hasAnyApiKey()
        AiSetupNavigationCard(
            title = "AI Agents",
            description = "Configure agents with provider, model, and API key",
            icon = "🤖",
            count = "$configuredAgents configured",
            onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) },
            enabled = hasApiKey
        )

        // AI Swarms card
        val configuredSwarms = aiSettings.swarms.size
        AiSetupNavigationCard(
            title = "AI Swarms",
            description = "Group agents into swarms for report generation",
            icon = "🐝",
            count = "$configuredSwarms configured",
            onClick = { onNavigate(SettingsSubScreen.AI_SWARMS) },
            enabled = hasApiKey
        )

        // AI Prompts card
        val configuredPrompts = aiSettings.prompts.size
        AiSetupNavigationCard(
            title = "AI Prompts",
            description = "Internal prompts for AI-powered features",
            icon = "📝",
            count = "$configuredPrompts configured",
            onClick = { onNavigate(SettingsSubScreen.AI_PROMPTS) },
            enabled = hasApiKey
        )

        // AI Costs card - use aiSettings as key to refresh after import
        val manualPricingCount = remember(aiSettings) {
            com.ai.data.PricingCache.getAllManualPricing(context).size
        }
        AiSetupNavigationCard(
            title = "AI Costs",
            description = "Configure manual price overrides per model",
            icon = "💰",
            count = "$manualPricingCount configured",
            onClick = onNavigateToCostConfig,
            enabled = hasApiKey
        )

        // API Test card (developer mode only)
        if (developerMode) {
            AiSetupNavigationCard(
                title = "API Test",
                description = "Test API calls with custom settings",
                icon = "🧪",
                count = "",
                onClick = onNavigateToApiTest
            )
        }

    }
}

/**
 * Navigation card for AI Setup screen.
 */
@Composable
private fun AiSetupNavigationCard(
    title: String,
    description: String,
    icon: String,
    count: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1A1A1A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineMedium,
                color = if (enabled) Color.Unspecified else Color(0xFF555555)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.White else Color(0xFF555555)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFFAAAAAA) else Color(0xFF444444)
                )
            }
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFF00E676) else Color(0xFF444444)
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) Color(0xFF888888) else Color(0xFF333333)
                )
            }
        }
    }
}

/**
 * AI Providers screen - configure model source for each provider.
 */
@Composable
fun AiProvidersScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Providers",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        // Provider cards - sorted alphabetically, navigate to individual provider screens
        AiServiceNavigationCard(
            title = "Anthropic",
            accentColor = Color(0xFFD97706),
            hasApiKey = aiSettings.claudeApiKey.isNotBlank(),
            adminUrl = AiService.ANTHROPIC.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_ANTHROPIC) }
        )
        AiServiceNavigationCard(
            title = "DeepSeek",
            accentColor = Color(0xFF4D6BFE),
            hasApiKey = aiSettings.deepSeekApiKey.isNotBlank(),
            adminUrl = AiService.DEEPSEEK.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_DEEPSEEK) }
        )
        AiServiceNavigationCard(
            title = "Google",
            accentColor = Color(0xFF4285F4),
            hasApiKey = aiSettings.geminiApiKey.isNotBlank(),
            adminUrl = AiService.GOOGLE.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_GOOGLE) }
        )
        AiServiceNavigationCard(
            title = "Groq",
            accentColor = Color(0xFFF55036),
            hasApiKey = aiSettings.groqApiKey.isNotBlank(),
            adminUrl = AiService.GROQ.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_GROQ) }
        )
        AiServiceNavigationCard(
            title = "Mistral",
            accentColor = Color(0xFFFF7000),
            hasApiKey = aiSettings.mistralApiKey.isNotBlank(),
            adminUrl = AiService.MISTRAL.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_MISTRAL) }
        )
        AiServiceNavigationCard(
            title = "OpenAI",
            accentColor = Color(0xFF10A37F),
            hasApiKey = aiSettings.chatGptApiKey.isNotBlank(),
            adminUrl = AiService.OPENAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_OPENAI) }
        )
        AiServiceNavigationCard(
            title = "OpenRouter",
            accentColor = Color(0xFF6B5AED),
            hasApiKey = aiSettings.openRouterApiKey.isNotBlank(),
            adminUrl = AiService.OPENROUTER.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_OPENROUTER) }
        )
        AiServiceNavigationCard(
            title = "Perplexity",
            accentColor = Color(0xFF20B2AA),
            hasApiKey = aiSettings.perplexityApiKey.isNotBlank(),
            adminUrl = AiService.PERPLEXITY.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_PERPLEXITY) }
        )
        AiServiceNavigationCard(
            title = "SiliconFlow",
            accentColor = Color(0xFF00B4D8),
            hasApiKey = aiSettings.siliconFlowApiKey.isNotBlank(),
            adminUrl = AiService.SILICONFLOW.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_SILICONFLOW) }
        )
        AiServiceNavigationCard(
            title = "Together",
            accentColor = Color(0xFF6366F1),
            hasApiKey = aiSettings.togetherApiKey.isNotBlank(),
            adminUrl = AiService.TOGETHER.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_TOGETHER) }
        )
        AiServiceNavigationCard(
            title = "xAI",
            accentColor = Color(0xFFFFFFFF),
            hasApiKey = aiSettings.grokApiKey.isNotBlank(),
            adminUrl = AiService.XAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_XAI) }
        )
        AiServiceNavigationCard(
            title = "Z.AI",
            accentColor = Color(0xFF6366F1),
            hasApiKey = aiSettings.zaiApiKey.isNotBlank(),
            adminUrl = AiService.ZAI.adminUrl,
            onEdit = { onNavigate(SettingsSubScreen.AI_ZAI) }
        )
        // Dummy provider only visible in developer mode (always last)
        if (developerMode) {
            AiServiceNavigationCard(
                title = "Dummy",
                accentColor = Color(0xFF888888),
                hasApiKey = true,  // Dummy always has a "key"
                adminUrl = AiService.DUMMY.adminUrl,
                onEdit = { onNavigate(SettingsSubScreen.AI_DUMMY) }
            )
        }
    }
}

/**
 * Model Search screen - search across all provider model lists.
 */
@Composable
fun ModelSearchScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    availableChatGptModels: List<String>,
    availableClaudeModels: List<String>,
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    availableSiliconFlowModels: List<String>,
    availableZaiModels: List<String>,
    availableDummyModels: List<String>,
    isLoadingChatGptModels: Boolean = false,
    isLoadingClaudeModels: Boolean = false,
    isLoadingGeminiModels: Boolean = false,
    isLoadingGrokModels: Boolean = false,
    isLoadingGroqModels: Boolean = false,
    isLoadingDeepSeekModels: Boolean = false,
    isLoadingMistralModels: Boolean = false,
    isLoadingTogetherModels: Boolean = false,
    isLoadingOpenRouterModels: Boolean = false,
    isLoadingSiliconFlowModels: Boolean = false,
    isLoadingZaiModels: Boolean = false,
    isLoadingDummyModels: Boolean = false,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSaveAiSettings: (AiSettings) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String?,
    onFetchChatGptModels: (String) -> Unit,
    onFetchClaudeModels: (String) -> Unit,
    onFetchGeminiModels: (String) -> Unit,
    onFetchGrokModels: (String) -> Unit,
    onFetchGroqModels: (String) -> Unit,
    onFetchDeepSeekModels: (String) -> Unit,
    onFetchMistralModels: (String) -> Unit,
    onFetchPerplexityModels: (String) -> Unit,
    onFetchTogetherModels: (String) -> Unit,
    onFetchOpenRouterModels: (String) -> Unit,
    onFetchSiliconFlowModels: (String) -> Unit,
    onFetchZaiModels: (String) -> Unit,
    onFetchDummyModels: (String) -> Unit,
    onNavigateToChatParams: (AiService, String) -> Unit,
    onNavigateToModelInfo: (AiService, String) -> Unit
) {
    // Check if any provider is loading
    val isLoading = isLoadingChatGptModels || isLoadingClaudeModels || isLoadingGeminiModels || isLoadingGrokModels ||
            isLoadingGroqModels || isLoadingDeepSeekModels || isLoadingMistralModels ||
            isLoadingTogetherModels || isLoadingOpenRouterModels || isLoadingSiliconFlowModels ||
            isLoadingZaiModels || isLoadingDummyModels
    var searchQuery by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<ModelSearchItem?>(null) }

    // Helper to fetch models for a provider (used by manual refresh button)
    val fetchModelsForProvider: (AiService, String) -> Unit = { provider, apiKey ->
        when (provider) {
            AiService.OPENAI -> onFetchChatGptModels(apiKey)
            AiService.ANTHROPIC -> onFetchClaudeModels(apiKey)
            AiService.GOOGLE -> onFetchGeminiModels(apiKey)
            AiService.XAI -> onFetchGrokModels(apiKey)
            AiService.GROQ -> onFetchGroqModels(apiKey)
            AiService.DEEPSEEK -> onFetchDeepSeekModels(apiKey)
            AiService.MISTRAL -> onFetchMistralModels(apiKey)
            AiService.PERPLEXITY -> onFetchPerplexityModels(apiKey)
            AiService.TOGETHER -> onFetchTogetherModels(apiKey)
            AiService.OPENROUTER -> onFetchOpenRouterModels(apiKey)
            AiService.SILICONFLOW -> onFetchSiliconFlowModels(apiKey)
            AiService.ZAI -> onFetchZaiModels(apiKey)
            AiService.DUMMY -> onFetchDummyModels(apiKey)
        }
    }

    // State for model action selection
    var showActionDialog by remember { mutableStateOf(false) }
    var showAgentEdit by remember { mutableStateOf(false) }

    // Show action popup when a model is clicked
    if (showActionDialog && selectedModel != null) {
        val model = selectedModel!!
        AlertDialog(
            onDismissRequest = {
                showActionDialog = false
                selectedModel = null
            },
            title = {
                Text(
                    text = model.modelName,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    text = "What would you like to do with this model?",
                    color = Color(0xFFAAAAAA)
                )
            },
            confirmButton = {},
            dismissButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start AI Chat
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            val provider = model.provider
                            onNavigateToChatParams(provider, model.modelName)
                            selectedModel = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("💬", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Start AI Chat", color = Color(0xFF6B9BFF))
                        }
                    }
                    // Create AI Agent
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            showAgentEdit = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🤖", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Create AI Agent", color = Color(0xFF6B9BFF))
                        }
                    }
                    // Model Info
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            val provider = model.provider
                            onNavigateToModelInfo(provider, model.modelName)
                            selectedModel = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ℹ️", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Model Info", color = Color(0xFF6B9BFF))
                        }
                    }
                }
            },
            containerColor = Color(0xFF2A2A2A)
        )
    }

    // Show AgentEditScreen when Create AI Agent is selected
    if (showAgentEdit && selectedModel != null) {
        val provider = providerFromName(selectedModel!!.providerName)
        val prefilledAgent = AiAgent(
            id = java.util.UUID.randomUUID().toString(),
            name = "",
            provider = provider,
            model = selectedModel!!.modelName,
            apiKey = aiSettings.getApiKey(provider),
            parameters = AiAgentParameters()
        )

        AgentEditScreen(
            agent = prefilledAgent,
            aiSettings = aiSettings,
            developerMode = developerMode,
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
            availableDummyModels = availableDummyModels,
            existingNames = aiSettings.agents.map { it.name }.toSet(),
            onTestAiModel = onTestAiModel,
            onFetchModelsForProvider = fetchModelsForProvider,
            forceAddMode = true,
            onSave = { newAgent ->
                val newAgents = aiSettings.agents + newAgent
                onSaveAiSettings(aiSettings.copy(agents = newAgents))
                showAgentEdit = false
                selectedModel = null
            },
            onBack = {
                showAgentEdit = false
                selectedModel = null
            },
            onNavigateHome = onBackToHome
        )
        return
    }

    // Combine all models with their provider info
    val allModels = remember(
        availableChatGptModels, availableClaudeModels, availableGeminiModels, availableGrokModels,
        availableGroqModels, availableDeepSeekModels, availableMistralModels,
        availablePerplexityModels, availableTogetherModels, availableOpenRouterModels,
        availableSiliconFlowModels, availableZaiModels, availableDummyModels, aiSettings
    ) {
        buildList {
            // OpenAI models
            availableChatGptModels.forEach { add(ModelSearchItem(AiService.OPENAI, "OpenAI", it, Color(0xFF10A37F))) }
            // Anthropic models (API or fallback to manual)
            val claudeModels = if (availableClaudeModels.isNotEmpty()) availableClaudeModels else aiSettings.claudeManualModels
            claudeModels.forEach { add(ModelSearchItem(AiService.ANTHROPIC, "Anthropic", it, Color(0xFFD97706))) }
            // Google models
            availableGeminiModels.forEach { add(ModelSearchItem(AiService.GOOGLE, "Google", it, Color(0xFF4285F4))) }
            // xAI models
            availableGrokModels.forEach { add(ModelSearchItem(AiService.XAI, "xAI", it, Color(0xFFFFFFFF))) }
            // Groq models
            availableGroqModels.forEach { add(ModelSearchItem(AiService.GROQ, "Groq", it, Color(0xFFF55036))) }
            // DeepSeek models
            availableDeepSeekModels.forEach { add(ModelSearchItem(AiService.DEEPSEEK, "DeepSeek", it, Color(0xFF4D6BFE))) }
            // Mistral models
            availableMistralModels.forEach { add(ModelSearchItem(AiService.MISTRAL, "Mistral", it, Color(0xFFFF7000))) }
            // Perplexity models (hardcoded - no API)
            aiSettings.perplexityManualModels.forEach { add(ModelSearchItem(AiService.PERPLEXITY, "Perplexity", it, Color(0xFF20B2AA))) }
            // Together models
            availableTogetherModels.forEach { add(ModelSearchItem(AiService.TOGETHER, "Together", it, Color(0xFF6366F1))) }
            // OpenRouter models
            availableOpenRouterModels.forEach { add(ModelSearchItem(AiService.OPENROUTER, "OpenRouter", it, Color(0xFF6B5AED))) }
            // SiliconFlow models (API or fallback to manual)
            val siliconFlowModels = if (availableSiliconFlowModels.isNotEmpty()) availableSiliconFlowModels else aiSettings.siliconFlowManualModels
            siliconFlowModels.forEach { add(ModelSearchItem(AiService.SILICONFLOW, "SiliconFlow", it, Color(0xFF00B4D8))) }
            // Z.AI models (API or fallback to manual)
            val zaiModels = if (availableZaiModels.isNotEmpty()) availableZaiModels else aiSettings.zaiManualModels
            zaiModels.forEach { add(ModelSearchItem(AiService.ZAI, "Z.AI", it, Color(0xFF6366F1))) }
            // Dummy models (only in developer mode)
            if (developerMode) {
                availableDummyModels.forEach { add(ModelSearchItem(AiService.DUMMY, "Dummy", it, Color(0xFF888888))) }
            }
        }
    }

    // Filter models based on search query
    val filteredModels = remember(searchQuery, allModels) {
        if (searchQuery.isBlank()) {
            allModels
        } else {
            allModels.filter {
                it.modelName.contains(searchQuery, ignoreCase = true) ||
                it.providerName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Model Search",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Search input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search models") },
            placeholder = { Text("Enter model name or provider...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFF9800),
                unfocusedBorderColor = Color(0xFF555555),
                focusedLabelColor = Color(0xFFFF9800)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Loading indicator or results count
        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFFF9800)
                )
                Text(
                    text = "Loading models from API...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
        } else {
            Text(
                text = "${filteredModels.size} models found",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Model list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredModels) { item ->
                ModelSearchResultCard(
                    item = item,
                    onClick = {
                        selectedModel = item
                        showActionDialog = true
                    }
                )
            }
        }
    }
}

/**
 * Helper to convert provider name to AiService.
 */
private fun providerFromName(name: String): AiService {
    return when (name) {
        "OpenAI" -> AiService.OPENAI
        "Anthropic" -> AiService.ANTHROPIC
        "Google" -> AiService.GOOGLE
        "xAI" -> AiService.XAI
        "Groq" -> AiService.GROQ
        "DeepSeek" -> AiService.DEEPSEEK
        "Mistral" -> AiService.MISTRAL
        "Perplexity" -> AiService.PERPLEXITY
        "Together" -> AiService.TOGETHER
        "OpenRouter" -> AiService.OPENROUTER
        "SiliconFlow" -> AiService.SILICONFLOW
        "Z.AI" -> AiService.ZAI
        "Dummy" -> AiService.DUMMY
        else -> AiService.OPENAI
    }
}

/**
 * Data class for model search results.
 */
private data class ModelSearchItem(
    val provider: AiService,
    val providerName: String,
    val modelName: String,
    val accentColor: Color
)

/**
 * Card displaying a model search result.
 */
@Composable
private fun ModelSearchResultCard(
    item: ModelSearchItem,
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
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Provider color indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(item.accentColor, shape = CircleShape)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Text(
                    text = item.providerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = item.accentColor
                )
            }
            // Arrow indicator
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF888888)
            )
        }
    }
}

// ============================================================================
// Model Info Screen
// ============================================================================

/**
 * Data class to hold aggregated model information from multiple sources.
 */
data class ModelInfoData(
    val modelName: String,
    val provider: AiService,
    // OpenRouter data
    val openRouterName: String? = null,
    val openRouterDescription: String? = null,
    val contextLength: Int? = null,
    val maxCompletionTokens: Int? = null,
    val promptPricing: String? = null,
    val completionPricing: String? = null,
    val modality: String? = null,
    val tokenizer: String? = null,
    val instructType: String? = null,
    val isModerated: Boolean? = null,
    // Hugging Face data
    val huggingFaceAuthor: String? = null,
    val huggingFaceDownloads: Long? = null,
    val huggingFaceLikes: Int? = null,
    val huggingFaceTags: List<String>? = null,
    val huggingFacePipelineTag: String? = null,
    val huggingFaceLibrary: String? = null,
    val huggingFaceLicense: String? = null,
    val huggingFaceLastModified: String? = null,
    val huggingFaceGated: Boolean? = null,
    // AI-generated description
    val aiDescription: String? = null
)

/**
 * Screen displaying detailed model information from multiple sources.
 */
@Composable
fun ModelInfoScreen(
    provider: AiService,
    modelName: String,
    openRouterApiKey: String,
    huggingFaceApiKey: String,
    aiSettings: AiSettings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var modelInfo by remember { mutableStateOf<ModelInfoData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Fetch model info from available sources
    LaunchedEffect(modelName) {
        isLoading = true
        errorMessage = null

        try {
            var openRouterData: com.ai.data.OpenRouterModelInfo? = null
            var huggingFaceData: com.ai.data.HuggingFaceModelInfo? = null
            var aiDescription: String? = null

            // Try OpenRouter API if we have an API key
            if (openRouterApiKey.isNotBlank()) {
                try {
                    val api = com.ai.data.AiApiFactory.createOpenRouterModelsApi()
                    val response = api.listModelsDetailed("Bearer $openRouterApiKey")
                    if (response.isSuccessful) {
                        val models = response.body()?.data ?: emptyList()
                        // Try to find the model by exact match or partial match
                        openRouterData = models.find { it.id.equals(modelName, ignoreCase = true) }
                            ?: models.find { it.id.contains(modelName, ignoreCase = true) }
                            ?: models.find { modelName.contains(it.id, ignoreCase = true) }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ModelInfo", "OpenRouter API error: ${e.message}")
                }
            }

            // Try Hugging Face API if we have an API key
            if (huggingFaceApiKey.isNotBlank()) {
                try {
                    val api = com.ai.data.AiApiFactory.createHuggingFaceApi()
                    // Try different model ID formats
                    val modelIds = listOf(
                        modelName,
                        modelName.replace(":", "/"),
                        modelName.substringAfter("/")
                    ).distinct()

                    for (modelId in modelIds) {
                        try {
                            val response = api.getModelInfo(modelId, "Bearer $huggingFaceApiKey")
                            if (response.isSuccessful) {
                                huggingFaceData = response.body()
                                break
                            }
                        } catch (e: Exception) {
                            // Try next format
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ModelInfo", "HuggingFace API error: ${e.message}")
                }
            }

            // Try AI prompt named "model_info" if it exists
            val modelInfoPrompt = aiSettings.getPromptByName("model_info")
            if (modelInfoPrompt != null) {
                val modelInfoAgent = aiSettings.getAgentForPrompt(modelInfoPrompt)
                if (modelInfoAgent != null) {
                    try {
                        // Resolve prompt with variables
                        val resolvedPrompt = modelInfoPrompt.resolvePrompt(
                            model = modelName,
                            provider = provider.displayName,
                            agent = modelInfoAgent.name
                        )
                        val repository = com.ai.data.AiAnalysisRepository()
                        val result = repository.analyzePlayerWithAgent(modelInfoAgent, resolvedPrompt)
                        if (result.error == null && !result.analysis.isNullOrBlank()) {
                            aiDescription = result.analysis
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ModelInfo", "AI prompt error: ${e.message}")
                    }
                }
            }

            // Combine data from all sources
            modelInfo = ModelInfoData(
                modelName = modelName,
                provider = provider,
                // OpenRouter
                openRouterName = openRouterData?.name,
                openRouterDescription = openRouterData?.description,
                contextLength = openRouterData?.context_length ?: openRouterData?.top_provider?.context_length,
                maxCompletionTokens = openRouterData?.top_provider?.max_completion_tokens,
                promptPricing = openRouterData?.pricing?.prompt,
                completionPricing = openRouterData?.pricing?.completion,
                modality = openRouterData?.architecture?.modality,
                tokenizer = openRouterData?.architecture?.tokenizer,
                instructType = openRouterData?.architecture?.instruct_type,
                isModerated = openRouterData?.top_provider?.is_moderated,
                // Hugging Face
                huggingFaceAuthor = huggingFaceData?.author,
                huggingFaceDownloads = huggingFaceData?.downloads,
                huggingFaceLikes = huggingFaceData?.likes,
                huggingFaceTags = huggingFaceData?.tags,
                huggingFacePipelineTag = huggingFaceData?.pipeline_tag,
                huggingFaceLibrary = huggingFaceData?.library_name,
                huggingFaceLicense = huggingFaceData?.cardData?.license,
                huggingFaceLastModified = huggingFaceData?.lastModified,
                huggingFaceGated = huggingFaceData?.gated,
                // AI-generated
                aiDescription = aiDescription
            )

        } catch (e: Exception) {
            errorMessage = "Error fetching model info: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Model Info",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFFF9800))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Fetching model information...",
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        } else if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = Color(0xFFFF6B6B)
            )
        } else if (modelInfo != null) {
            val info = modelInfo!!

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Model name header
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A3A4A)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = info.modelName,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Provider: ${info.provider.displayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFAAAAAA)
                            )
                            if (info.openRouterName != null && info.openRouterName != info.modelName) {
                                Text(
                                    text = "Name: ${info.openRouterName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFAAAAAA)
                                )
                            }
                        }
                    }
                }

                // AI-generated introduction (from model_info agent)
                if (info.aiDescription != null) {
                    item {
                        ModelInfoSection(title = "AI Introduction") {
                            Text(
                                text = info.aiDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Description from OpenRouter
                if (info.openRouterDescription != null) {
                    item {
                        ModelInfoSection(title = "Description") {
                            Text(
                                text = info.openRouterDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Technical specs
                item {
                    ModelInfoSection(title = "Technical Specifications") {
                        if (info.contextLength != null) {
                            ModelInfoRow("Context Length", formatNumber(info.contextLength) + " tokens")
                        }
                        if (info.maxCompletionTokens != null) {
                            ModelInfoRow("Max Completion", formatNumber(info.maxCompletionTokens) + " tokens")
                        }
                        if (info.modality != null) {
                            ModelInfoRow("Modality", info.modality)
                        }
                        if (info.tokenizer != null) {
                            ModelInfoRow("Tokenizer", info.tokenizer)
                        }
                        if (info.instructType != null) {
                            ModelInfoRow("Instruct Type", info.instructType)
                        }
                        if (info.isModerated != null) {
                            ModelInfoRow("Moderated", if (info.isModerated) "Yes" else "No")
                        }
                    }
                }

                // Pricing
                if (info.promptPricing != null || info.completionPricing != null) {
                    item {
                        ModelInfoSection(title = "Pricing (per token)") {
                            if (info.promptPricing != null) {
                                val price = info.promptPricing.toDoubleOrNull()
                                if (price != null) {
                                    ModelInfoRow("Input", formatPricing(price))
                                }
                            }
                            if (info.completionPricing != null) {
                                val price = info.completionPricing.toDoubleOrNull()
                                if (price != null) {
                                    ModelInfoRow("Output", formatPricing(price))
                                }
                            }
                        }
                    }
                }

                // Hugging Face info
                val hasHuggingFaceInfo = info.huggingFaceAuthor != null ||
                        info.huggingFaceDownloads != null ||
                        info.huggingFaceLikes != null

                if (hasHuggingFaceInfo) {
                    item {
                        ModelInfoSection(title = "Hugging Face") {
                            if (info.huggingFaceAuthor != null) {
                                ModelInfoRow("Author", info.huggingFaceAuthor)
                            }
                            if (info.huggingFaceDownloads != null) {
                                ModelInfoRow("Downloads", formatNumber(info.huggingFaceDownloads))
                            }
                            if (info.huggingFaceLikes != null) {
                                ModelInfoRow("Likes", formatNumber(info.huggingFaceLikes))
                            }
                            if (info.huggingFacePipelineTag != null) {
                                ModelInfoRow("Pipeline", info.huggingFacePipelineTag)
                            }
                            if (info.huggingFaceLibrary != null) {
                                ModelInfoRow("Library", info.huggingFaceLibrary)
                            }
                            if (info.huggingFaceLicense != null) {
                                ModelInfoRow("License", info.huggingFaceLicense)
                            }
                            if (info.huggingFaceGated == true) {
                                ModelInfoRow("Access", "Gated (requires approval)")
                            }
                            if (info.huggingFaceLastModified != null) {
                                ModelInfoRow("Last Updated", info.huggingFaceLastModified.take(10))
                            }
                        }
                    }
                }

                // Tags
                if (!info.huggingFaceTags.isNullOrEmpty()) {
                    item {
                        ModelInfoSection(title = "Tags") {
                            Text(
                                text = info.huggingFaceTags.take(20).joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCCCCCC)
                            )
                        }
                    }
                }

                // No info found message
                val hasAnyInfo = info.openRouterDescription != null ||
                        info.contextLength != null ||
                        hasHuggingFaceInfo

                if (!hasAnyInfo) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF3A3A4A)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No additional information found for this model in OpenRouter or Hugging Face databases.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFAAAAAA)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelInfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF9800)
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

private fun formatNumber(number: Number): String {
    val value = number.toLong()
    return when {
        value >= 1_000_000_000 -> String.format("%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

private fun formatPricing(pricePerToken: Double): String {
    // Convert to price per million tokens
    val pricePerMillion = pricePerToken * 1_000_000
    return when {
        pricePerMillion >= 1 -> String.format("$%.2f / 1M tokens", pricePerMillion)
        pricePerMillion >= 0.01 -> String.format("$%.4f / 1M tokens", pricePerMillion)
        else -> String.format("$%.6f / 1M tokens", pricePerMillion)
    }
}

/**
 * AI Housekeeping screen with maintenance actions like refresh models, generate agents, import/export.
 */
@Composable
fun HousekeepingScreen(
    aiSettings: AiSettings,
    huggingFaceApiKey: String = "",
    developerMode: Boolean = false,
    availableChatGptModels: List<String> = emptyList(),
    availableClaudeModels: List<String> = emptyList(),
    availableGeminiModels: List<String> = emptyList(),
    availableGrokModels: List<String> = emptyList(),
    availableGroqModels: List<String> = emptyList(),
    availableDeepSeekModels: List<String> = emptyList(),
    availableMistralModels: List<String> = emptyList(),
    availablePerplexityModels: List<String> = emptyList(),
    availableTogetherModels: List<String> = emptyList(),
    availableOpenRouterModels: List<String> = emptyList(),
    availableSiliconFlowModels: List<String> = emptyList(),
    availableZaiModels: List<String> = emptyList(),
    availableDummyModels: List<String> = emptyList(),
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onRefreshAllModels: suspend (AiSettings) -> Map<String, Int> = { emptyMap() },
    onTestApiKey: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onSaveHuggingFaceApiKey: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State for refresh model lists
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshResults by remember { mutableStateOf<Map<String, Int>?>(null) }
    var showResultsDialog by remember { mutableStateOf(false) }

    // State for generate default agents
    var isGenerating by remember { mutableStateOf(false) }
    var generationResults by remember { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    var showGenerationResultsDialog by remember { mutableStateOf(false) }

    // State for fetch model specs
    var isFetchingSpecs by remember { mutableStateOf(false) }
    var specsResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showSpecsResultDialog by remember { mutableStateOf(false) }

    // State for refresh OpenRouter pricing
    var isRefreshingPricing by remember { mutableStateOf(false) }
    var pricingRefreshCount by remember { mutableStateOf(0) }

    // State for import model costs
    var importCostsResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }  // (imported, skipped)
    var showImportCostsResultDialog by remember { mutableStateOf(false) }

    // File picker launcher for importing model costs CSV
    val costsCsvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importModelCostsFromCsv(context, it)
            importCostsResult = result
            showImportCostsResultDialog = true
        }
    }

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val result = importAiConfigFromFile(context, it, aiSettings)
            if (result != null) {
                val importedSettings = result.aiSettings
                onSave(importedSettings)
                result.huggingFaceApiKey?.let { key -> onSaveHuggingFaceApiKey(key) }

                // Automatically refresh model lists and generate default agents after import
                if (importedSettings.hasAnyApiKey()) {
                    scope.launch {
                        // 1. Refresh model lists
                        isRefreshing = true
                        onRefreshAllModels(importedSettings)
                        isRefreshing = false

                        // 2. Generate default agents
                        isGenerating = true
                        val results = mutableListOf<Pair<String, Boolean>>()

                        val providersToTest = AiService.entries.filter { provider ->
                            val apiKey = importedSettings.getApiKey(provider)
                            apiKey.isNotBlank() && (provider != AiService.DUMMY || developerMode)
                        }

                        var updatedAgents = importedSettings.agents.toMutableList()

                        for (provider in providersToTest) {
                            val apiKey = importedSettings.getApiKey(provider)
                            val model = provider.defaultModel

                            val testResult = onTestApiKey(provider, apiKey, model)
                            val isWorking = testResult == null

                            if (isWorking) {
                                val existingAgentIndex = updatedAgents.indexOfFirst {
                                    it.name == provider.displayName
                                }

                                if (existingAgentIndex >= 0) {
                                    val existingAgent = updatedAgents[existingAgentIndex]
                                    updatedAgents[existingAgentIndex] = existingAgent.copy(
                                        model = model,
                                        apiKey = apiKey,
                                        provider = provider
                                    )
                                } else {
                                    val newAgent = AiAgent(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = provider.displayName,
                                        provider = provider,
                                        model = model,
                                        apiKey = apiKey,
                                        parameters = AiAgentParameters()
                                    )
                                    updatedAgents.add(newAgent)
                                }
                            }

                            results.add(provider.displayName to isWorking)
                        }

                        val successCount = results.count { it.second }
                        if (successCount > 0) {
                            val defaultAgentIds = updatedAgents
                                .filter { agent ->
                                    AiService.entries.any { it.displayName == agent.name }
                                }
                                .map { it.id }

                            val updatedSwarms = importedSettings.swarms.toMutableList()
                            val existingSwarmIndex = updatedSwarms.indexOfFirst {
                                it.name == "default agents"
                            }

                            if (existingSwarmIndex >= 0) {
                                updatedSwarms[existingSwarmIndex] = updatedSwarms[existingSwarmIndex].copy(
                                    agentIds = defaultAgentIds
                                )
                            } else {
                                val newSwarm = AiSwarm(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = "default agents",
                                    agentIds = defaultAgentIds
                                )
                                updatedSwarms.add(newSwarm)
                            }

                            onSave(importedSettings.copy(
                                agents = updatedAgents,
                                swarms = updatedSwarms
                            ))
                        }

                        generationResults = results
                        isGenerating = false
                        showGenerationResultsDialog = true
                    }
                }
            }
        }
    }

    // Results dialog for refresh model lists
    if (showResultsDialog && refreshResults != null) {
        // Only show providers that are set to MANUAL model source
        val manualProviders = buildList {
            if (aiSettings.claudeApiKey.isNotBlank() && aiSettings.claudeModelSource == ModelSource.MANUAL) {
                add("Anthropic" to aiSettings.claudeManualModels.size)
            }
            if (aiSettings.perplexityApiKey.isNotBlank() && aiSettings.perplexityModelSource == ModelSource.MANUAL) {
                add("Perplexity" to aiSettings.perplexityManualModels.size)
            }
            if (aiSettings.siliconFlowApiKey.isNotBlank() && aiSettings.siliconFlowModelSource == ModelSource.MANUAL) {
                add("SiliconFlow" to aiSettings.siliconFlowManualModels.size)
            }
            if (aiSettings.zaiApiKey.isNotBlank() && aiSettings.zaiModelSource == ModelSource.MANUAL) {
                add("Z.AI" to aiSettings.zaiManualModels.size)
            }
        }

        AlertDialog(
            onDismissRequest = { showResultsDialog = false },
            title = { Text("Refresh Results") },
            text = {
                Column {
                    Text("Models fetched from API:")
                    refreshResults!!.forEach { (provider, count) ->
                        Text("• $provider: $count models")
                    }
                    if (manualProviders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Manual model lists (no API):")
                        manualProviders.forEach { (provider, count) ->
                            Text("• $provider: $count models")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showResultsDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Results dialog for generate default agents
    if (showGenerationResultsDialog && generationResults != null) {
        AlertDialog(
            onDismissRequest = { showGenerationResultsDialog = false },
            title = { Text("Generate Default Agents") },
            text = {
                Column {
                    val successCount = generationResults!!.count { it.second }
                    val failCount = generationResults!!.count { !it.second }

                    if (successCount > 0) {
                        Text("✅ Created/updated $successCount agent(s)")
                        Text("Added to 'default agents' swarm")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (failCount > 0) {
                        Text("❌ Failed: $failCount provider(s)")
                        generationResults!!.filter { !it.second }.forEach { (provider, _) ->
                            Text("• $provider")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGenerationResultsDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Specs result dialog
    if (showSpecsResultDialog) {
        AlertDialog(
            onDismissRequest = { showSpecsResultDialog = false },
            title = { Text("Model Specifications") },
            text = {
                if (specsResult != null) {
                    Text("Successfully saved:\n• ${specsResult!!.first} pricing entries\n• ${specsResult!!.second} parameter entries\n\nFiles saved to app storage.")
                } else {
                    Text("Failed to fetch model specifications.\nPlease check your OpenRouter API key.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpecsResultDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "AI Housekeeping",
            onBackClick = onBackToHome,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Refresh model lists button
        Button(
            onClick = {
                scope.launch {
                    isRefreshing = true
                    refreshResults = onRefreshAllModels(aiSettings)
                    isRefreshing = false
                    showResultsDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRefreshing,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refreshing...")
            } else {
                Text("Refresh model lists")
            }
        }

        // Generate default agents button
        Button(
            onClick = {
                scope.launch {
                    isGenerating = true
                    val results = mutableListOf<Pair<String, Boolean>>()

                    val providersToTest = AiService.entries.filter { provider ->
                        val apiKey = aiSettings.getApiKey(provider)
                        apiKey.isNotBlank() && (provider != AiService.DUMMY || developerMode)
                    }

                    var updatedAgents = aiSettings.agents.toMutableList()

                    for (provider in providersToTest) {
                        val apiKey = aiSettings.getApiKey(provider)
                        val model = provider.defaultModel

                        val testResult = onTestApiKey(provider, apiKey, model)
                        val isWorking = testResult == null

                        if (isWorking) {
                            val existingAgentIndex = updatedAgents.indexOfFirst {
                                it.name == provider.displayName
                            }

                            if (existingAgentIndex >= 0) {
                                val existingAgent = updatedAgents[existingAgentIndex]
                                updatedAgents[existingAgentIndex] = existingAgent.copy(
                                    model = model,
                                    apiKey = apiKey,
                                    provider = provider
                                )
                            } else {
                                val newAgent = AiAgent(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = provider.displayName,
                                    provider = provider,
                                    model = model,
                                    apiKey = apiKey,
                                    parameters = AiAgentParameters()
                                )
                                updatedAgents.add(newAgent)
                            }
                        }

                        results.add(provider.displayName to isWorking)
                    }

                    val successCount = results.count { it.second }
                    if (successCount > 0) {
                        val defaultAgentIds = updatedAgents
                            .filter { agent ->
                                AiService.entries.any { it.displayName == agent.name }
                            }
                            .map { it.id }

                        val updatedSwarms = aiSettings.swarms.toMutableList()
                        val existingSwarmIndex = updatedSwarms.indexOfFirst {
                            it.name == "default agents"
                        }

                        if (existingSwarmIndex >= 0) {
                            updatedSwarms[existingSwarmIndex] = updatedSwarms[existingSwarmIndex].copy(
                                agentIds = defaultAgentIds
                            )
                        } else {
                            val newSwarm = AiSwarm(
                                id = java.util.UUID.randomUUID().toString(),
                                name = "default agents",
                                agentIds = defaultAgentIds
                            )
                            updatedSwarms.add(newSwarm)
                        }

                        onSave(aiSettings.copy(
                            agents = updatedAgents,
                            swarms = updatedSwarms
                        ))
                    }

                    generationResults = results
                    isGenerating = false
                    showGenerationResultsDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGenerating,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing API keys...")
            } else {
                Text("Generate default agents")
            }
        }

        // Get model specifications from OpenRouter button
        Button(
            onClick = {
                scope.launch {
                    isFetchingSpecs = true
                    specsResult = com.ai.data.PricingCache.fetchAndSaveModelSpecifications(
                        context,
                        aiSettings.openRouterApiKey
                    )
                    isFetchingSpecs = false
                    showSpecsResultDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isFetchingSpecs && aiSettings.openRouterApiKey.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            if (isFetchingSpecs) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Fetching specifications...")
            } else {
                Text("Get model specifications from OpenRouter")
            }
        }

        // Refresh OpenRouter model cost cached data button
        Button(
            onClick = {
                scope.launch {
                    isRefreshingPricing = true
                    val pricing = com.ai.data.PricingCache.fetchOpenRouterPricing(aiSettings.openRouterApiKey)
                    if (pricing.isNotEmpty()) {
                        com.ai.data.PricingCache.saveOpenRouterPricing(context, pricing)
                        pricingRefreshCount = pricing.size
                        android.widget.Toast.makeText(context, "Refreshed ${pricing.size} model prices", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "Failed to fetch pricing data", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    isRefreshingPricing = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRefreshingPricing && aiSettings.openRouterApiKey.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            if (isRefreshingPricing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refreshing pricing...")
            } else {
                Text("Refresh OpenRouter model cost cached data")
            }
        }

        // Export AI configuration button
        val hasApiKeyForExport = aiSettings.chatGptApiKey.isNotBlank() ||
                aiSettings.claudeApiKey.isNotBlank() ||
                aiSettings.geminiApiKey.isNotBlank() ||
                aiSettings.grokApiKey.isNotBlank() ||
                aiSettings.groqApiKey.isNotBlank() ||
                aiSettings.deepSeekApiKey.isNotBlank() ||
                aiSettings.mistralApiKey.isNotBlank() ||
                aiSettings.perplexityApiKey.isNotBlank() ||
                aiSettings.togetherApiKey.isNotBlank() ||
                aiSettings.openRouterApiKey.isNotBlank() ||
                aiSettings.siliconFlowApiKey.isNotBlank() ||
                aiSettings.zaiApiKey.isNotBlank()
        val visibleAgentsForExport = if (developerMode) aiSettings.agents else aiSettings.agents.filter { it.provider != AiService.DUMMY }
        val canExport = hasApiKeyForExport &&
                visibleAgentsForExport.isNotEmpty() &&
                aiSettings.swarms.isNotEmpty()

        if (canExport) {
            Button(
                onClick = {
                    exportAiConfigToFile(context, aiSettings, huggingFaceApiKey)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Export AI configuration")
            }
        }

        // Import AI configuration button
        Button(
            onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Import AI configuration")
        }

        // Export model costs button
        Button(
            onClick = {
                exportModelCostsToCsv(
                    context = context,
                    aiSettings = aiSettings,
                    developerMode = developerMode,
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
                    availableDummyModels = availableDummyModels
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Export model costs")
        }

        // Import model costs button
        Button(
            onClick = {
                costsCsvPickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Import model costs")
        }

        // Import costs result dialog
        if (showImportCostsResultDialog && importCostsResult != null) {
            val (imported, skipped) = importCostsResult!!
            AlertDialog(
                onDismissRequest = { showImportCostsResultDialog = false },
                title = { Text("Import Model Costs", color = Color.White) },
                text = {
                    Text(
                        "Imported $imported model price overrides.\n" +
                        if (skipped > 0) "Skipped $skipped rows (empty or invalid)." else "",
                        color = Color(0xFFAAAAAA)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showImportCostsResultDialog = false }) {
                        Text("OK")
                    }
                },
                containerColor = Color(0xFF2A2A2A)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clean up card
        var showCleanupDaysDialog by remember { mutableStateOf<String?>(null) }
        var cleanupDaysInput by remember { mutableStateOf("30") }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Clean up",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCleanupDaysDialog = "chats" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("Chats", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "reports" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("Reports", fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCleanupDaysDialog = "statistics" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("Statistics", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showCleanupDaysDialog = "traces" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000))
                    ) {
                        Text("API Trace", fontSize = 12.sp)
                    }
                }
            }
        }

        // Cleanup days dialog
        if (showCleanupDaysDialog != null) {
            val cleanupType = showCleanupDaysDialog!!
            val title = when (cleanupType) {
                "chats" -> "Clean up Chats"
                "reports" -> "Clean up Reports"
                "statistics" -> "Clean up Statistics"
                "traces" -> "Clean up API Traces"
                else -> "Clean up"
            }
            AlertDialog(
                onDismissRequest = {
                    showCleanupDaysDialog = null
                    cleanupDaysInput = "30"
                },
                title = { Text(title, color = Color.White) },
                text = {
                    Column {
                        if (cleanupType == "statistics") {
                            Text(
                                "Statistics don't have timestamps. Enter 0 to clear all statistics.",
                                color = Color(0xFFAAAAAA),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            "Delete data older than how many days?",
                            color = Color(0xFFAAAAAA)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cleanupDaysInput,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() }) {
                                    cleanupDaysInput = newValue
                                }
                            },
                            label = { Text("Days to keep") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF9800),
                                unfocusedBorderColor = Color(0xFF555555),
                                focusedLabelColor = Color(0xFFFF9800)
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val days = cleanupDaysInput.toIntOrNull() ?: 30
                            val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
                            var deletedCount = 0

                            when (cleanupType) {
                                "chats" -> {
                                    val sessions = com.ai.data.ChatHistoryManager.getAllSessions()
                                    sessions.forEach { session ->
                                        if (session.updatedAt < cutoffTime) {
                                            if (com.ai.data.ChatHistoryManager.deleteSession(session.id)) {
                                                deletedCount++
                                            }
                                        }
                                    }
                                    android.widget.Toast.makeText(context, "Deleted $deletedCount chat(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "reports" -> {
                                    val reports = com.ai.data.AiReportStorage.getAllReports(context)
                                    reports.forEach { report ->
                                        if (report.timestamp < cutoffTime) {
                                            com.ai.data.AiReportStorage.deleteReport(context, report.id)
                                            deletedCount++
                                        }
                                    }
                                    android.widget.Toast.makeText(context, "Deleted $deletedCount report(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "statistics" -> {
                                    if (days == 0) {
                                        SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)).clearUsageStats()
                                        android.widget.Toast.makeText(context, "Cleared all statistics", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Enter 0 to clear statistics (no timestamp data)", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                "traces" -> {
                                    deletedCount = com.ai.data.ApiTracer.deleteTracesOlderThan(cutoffTime)
                                    android.widget.Toast.makeText(context, "Deleted $deletedCount trace(s)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }

                            showCleanupDaysDialog = null
                            cleanupDaysInput = "30"
                        }
                    ) {
                        Text("Delete", color = Color(0xFFFF6B6B))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCleanupDaysDialog = null
                            cleanupDaysInput = "30"
                        }
                    ) {
                        Text("Cancel", color = Color(0xFF6B9BFF))
                    }
                },
                containerColor = Color(0xFF2A2A2A)
            )
        }
    }
}

/**
 * Export model costs to CSV file.
 * Only includes models that can be used for agents (matching Model Search).
 * Prices are in USD ticks (one millionth of a dollar) per million tokens.
 */
private fun exportModelCostsToCsv(
    context: android.content.Context,
    aiSettings: AiSettings,
    developerMode: Boolean,
    availableChatGptModels: List<String>,
    availableClaudeModels: List<String>,
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    availableSiliconFlowModels: List<String>,
    availableZaiModels: List<String>,
    availableDummyModels: List<String>
) {
    val pricingCache = com.ai.data.PricingCache

    // Get all pricing sources
    val overridePricing = pricingCache.getAllManualPricing(context)
    val openRouterPricing = pricingCache.getOpenRouterPricing(context)
    val litellmPricing = pricingCache.getLiteLLMPricing(context)
    val fallbackPricing = pricingCache.getFallbackPricing()

    // Build model list the same way as Model Search - only models that can be used for agents
    data class ProviderModel(val provider: String, val model: String)
    val allModels = mutableListOf<ProviderModel>()

    // OpenAI models (API or fallback to manual)
    val chatGptModels = availableChatGptModels.ifEmpty { aiSettings.chatGptManualModels }
    chatGptModels.forEach { allModels.add(ProviderModel("OPENAI", it)) }

    // Anthropic models (API or fallback to manual)
    val claudeModels = availableClaudeModels.ifEmpty { aiSettings.claudeManualModels }
    claudeModels.forEach { allModels.add(ProviderModel("ANTHROPIC", it)) }

    // Google models
    availableGeminiModels.forEach { allModels.add(ProviderModel("GOOGLE", it)) }

    // xAI models
    availableGrokModels.forEach { allModels.add(ProviderModel("XAI", it)) }

    // Groq models
    availableGroqModels.forEach { allModels.add(ProviderModel("GROQ", it)) }

    // DeepSeek models
    availableDeepSeekModels.forEach { allModels.add(ProviderModel("DEEPSEEK", it)) }

    // Mistral models
    availableMistralModels.forEach { allModels.add(ProviderModel("MISTRAL", it)) }

    // Perplexity models (hardcoded - no API, use manual models)
    val perplexityModels = availablePerplexityModels.ifEmpty { aiSettings.perplexityManualModels }
    perplexityModels.forEach { allModels.add(ProviderModel("PERPLEXITY", it)) }

    // Together models
    availableTogetherModels.forEach { allModels.add(ProviderModel("TOGETHER", it)) }

    // OpenRouter models
    availableOpenRouterModels.forEach { allModels.add(ProviderModel("OPENROUTER", it)) }

    // SiliconFlow models (API or fallback to manual)
    val siliconFlowModels = availableSiliconFlowModels.ifEmpty { aiSettings.siliconFlowManualModels }
    siliconFlowModels.forEach { allModels.add(ProviderModel("SILICONFLOW", it)) }

    // Z.AI models (API or fallback to manual)
    val zaiModels = availableZaiModels.ifEmpty { aiSettings.zaiManualModels }
    zaiModels.forEach { allModels.add(ProviderModel("ZAI", it)) }

    // Dummy models (only in developer mode)
    if (developerMode) {
        availableDummyModels.forEach { allModels.add(ProviderModel("DUMMY", it)) }
    }

    // Sort by provider then model
    val sortedModels = allModels.sortedWith(compareBy({ it.provider }, { it.model }))

    // Helper to convert price per token to USD per million tokens
    // Price per token * 1,000,000 (for million tokens) = dollars per million
    fun toDollarsPerMillion(pricePerToken: Double?): String {
        if (pricePerToken == null) return ""
        val dollars = pricePerToken * 1e6
        return String.format(java.util.Locale.US, "%.2f", dollars)
    }

    // Build CSV - prices in USD per million tokens
    val csv = StringBuilder()
    csv.appendLine("Provider,Model,Override $/M In,Override $/M Out,OpenRouter $/M In,OpenRouter $/M Out,LiteLLM $/M In,LiteLLM $/M Out,Fallback $/M In,Fallback $/M Out")

    for (pm in sortedModels) {
        val overrideKey = "${pm.provider}:${pm.model}"
        val override = overridePricing[overrideKey]

        // For OpenRouter, try both the full key and provider/model format
        val openRouterKey = if (pm.provider == "OPENROUTER") pm.model else findOpenRouterKey(pm.provider, pm.model, openRouterPricing)
        val openRouter = openRouterPricing[openRouterKey]

        // For LiteLLM, try direct and with prefix
        val litellmKey = findLiteLLMKey(pm.provider, pm.model, litellmPricing)
        val litellm = litellmPricing[litellmKey]

        val fallback = fallbackPricing[pm.model]

        csv.appendLine(buildString {
            append(escapeCsvField(pm.provider))
            append(",")
            append(escapeCsvField(pm.model))
            append(",")
            append(toDollarsPerMillion(override?.promptPrice))
            append(",")
            append(toDollarsPerMillion(override?.completionPrice))
            append(",")
            append(toDollarsPerMillion(openRouter?.promptPrice))
            append(",")
            append(toDollarsPerMillion(openRouter?.completionPrice))
            append(",")
            append(toDollarsPerMillion(litellm?.promptPrice))
            append(",")
            append(toDollarsPerMillion(litellm?.completionPrice))
            append(",")
            append(toDollarsPerMillion(fallback?.promptPrice))
            append(",")
            append(toDollarsPerMillion(fallback?.completionPrice))
        })
    }

    // Save and share CSV file
    try {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "model_costs_$timestamp.csv"
        val exportDir = java.io.File(context.cacheDir ?: return, "exports")
        exportDir.mkdirs()
        val file = java.io.File(exportDir, fileName)
        file.writeText(csv.toString())

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(shareIntent, "Export Model Costs"))
    } catch (e: Exception) {
        android.util.Log.e("HousekeepingScreen", "Failed to export model costs: ${e.message}")
        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * Import model costs from CSV file.
 * Reads the Override $/M In and Override $/M Out columns and sets them as manual pricing overrides.
 * CSV format: Provider,Model,Override $/M In,Override $/M Out,...
 * Returns (imported count, skipped count).
 */
private fun importModelCostsFromCsv(context: android.content.Context, uri: android.net.Uri): Pair<Int, Int> {
    var imported = 0
    var skipped = 0

    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val reader = inputStream?.bufferedReader() ?: return Pair(0, 0)

        val lines = reader.readLines()
        reader.close()

        if (lines.isEmpty()) return Pair(0, 0)

        // Skip header row
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                skipped++
                continue
            }

            // Parse CSV line (handling quoted fields)
            val fields = parseCsvLine(line)
            if (fields.size < 4) {
                skipped++
                continue
            }

            val providerName = fields[0].trim()
            val model = fields[1].trim()
            val overrideInputStr = fields[2].trim()
            val overrideOutputStr = fields[3].trim()

            // Skip if override columns are empty
            if (overrideInputStr.isBlank() || overrideOutputStr.isBlank()) {
                skipped++
                continue
            }

            // Parse prices (in dollars per million tokens)
            val overrideInputDollarsPerM = overrideInputStr.toDoubleOrNull()
            val overrideOutputDollarsPerM = overrideOutputStr.toDoubleOrNull()

            if (overrideInputDollarsPerM == null || overrideOutputDollarsPerM == null) {
                skipped++
                continue
            }

            // Convert from dollars per million to price per token
            val promptPricePerToken = overrideInputDollarsPerM / 1_000_000.0
            val completionPricePerToken = overrideOutputDollarsPerM / 1_000_000.0

            // Map provider name to AiService
            val provider = try {
                com.ai.data.AiService.valueOf(providerName)
            } catch (e: IllegalArgumentException) {
                skipped++
                continue
            }

            // Set manual pricing
            com.ai.data.PricingCache.setManualPricing(
                context = context,
                provider = provider,
                model = model,
                promptPrice = promptPricePerToken,
                completionPrice = completionPricePerToken
            )
            imported++
        }

        android.util.Log.d("HousekeepingScreen", "Imported $imported model cost overrides, skipped $skipped")
    } catch (e: Exception) {
        android.util.Log.e("HousekeepingScreen", "Failed to import model costs: ${e.message}")
        android.widget.Toast.makeText(context, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }

    return Pair(imported, skipped)
}

/**
 * Parse a CSV line handling quoted fields.
 */
private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false

    for (char in line) {
        when {
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                fields.add(current.toString())
                current = StringBuilder()
            }
            else -> current.append(char)
        }
    }
    fields.add(current.toString())

    return fields
}

private fun mapOpenRouterPrefixToProvider(prefix: String): String? {
    return when (prefix) {
        "openai" -> "OPENAI"
        "anthropic" -> "ANTHROPIC"
        "google" -> "GOOGLE"
        "x-ai" -> "XAI"
        "deepseek" -> "DEEPSEEK"
        "mistralai" -> "MISTRAL"
        "perplexity" -> "PERPLEXITY"
        "meta-llama", "meta" -> "TOGETHER"
        else -> null
    }
}

private fun mapLiteLLMPrefixToProvider(prefix: String): String? {
    return when (prefix) {
        "openai" -> "OPENAI"
        "anthropic", "claude" -> "ANTHROPIC"
        "gemini" -> "GOOGLE"
        "xai" -> "XAI"
        "groq" -> "GROQ"
        "deepseek" -> "DEEPSEEK"
        "mistral" -> "MISTRAL"
        "perplexity" -> "PERPLEXITY"
        "together_ai", "together" -> "TOGETHER"
        else -> null
    }
}

private fun findOpenRouterKey(provider: String, model: String, pricing: Map<String, com.ai.data.PricingCache.ModelPricing>): String? {
    // Try direct match first
    if (pricing.containsKey(model)) return model

    // Try with provider prefix
    val prefix = when (provider) {
        "OPENAI" -> "openai"
        "ANTHROPIC" -> "anthropic"
        "GOOGLE" -> "google"
        "XAI" -> "x-ai"
        "DEEPSEEK" -> "deepseek"
        "MISTRAL" -> "mistralai"
        "PERPLEXITY" -> "perplexity"
        else -> null
    }
    if (prefix != null) {
        val key = "$prefix/$model"
        if (pricing.containsKey(key)) return key
    }

    // Try to find a partial match
    return pricing.keys.find { it.endsWith("/$model") }
}

private fun findLiteLLMKey(provider: String, model: String, pricing: Map<String, com.ai.data.PricingCache.ModelPricing>): String? {
    // Try direct match first
    if (pricing.containsKey(model)) return model

    // Try with provider prefix
    val prefix = when (provider) {
        "GOOGLE" -> "gemini"
        "XAI" -> "xai"
        "GROQ" -> "groq"
        "DEEPSEEK" -> "deepseek"
        "TOGETHER" -> "together_ai"
        else -> null
    }
    if (prefix != null) {
        val key = "$prefix/$model"
        if (pricing.containsKey(key)) return key
    }

    return null
}

private fun escapeCsvField(value: String): String {
    return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
}

/**
 * API Test screen for testing API calls with custom settings.
 * Only available in developer mode.
 */
@Composable
fun ApiTestScreen(
    onBackClick: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceDetail: (String) -> Unit,
    viewModel: AiViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    // Load last test values from persistent storage
    var selectedProvider by remember {
        val lastProvider = prefs.getString("last_test_provider", null)
        val provider = lastProvider?.let {
            try { com.ai.data.AiService.valueOf(it) } catch (e: Exception) { null }
        } ?: com.ai.data.AiService.OPENAI
        mutableStateOf(provider)
    }
    var apiUrl by remember {
        mutableStateOf(prefs.getString("last_test_api_url", null) ?: selectedProvider.baseUrl)
    }
    var apiKey by remember {
        mutableStateOf(prefs.getString("last_test_api_key", null) ?: "")
    }
    var model by remember {
        mutableStateOf(prefs.getString("last_test_model", null) ?: selectedProvider.defaultModel)
    }
    var prompt by remember {
        mutableStateOf(prefs.getString("last_test_prompt", null) ?: "Hello, how are you?")
    }
    var isLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(true) }

    // Update URL and model when provider changes (only if user manually changes provider)
    LaunchedEffect(selectedProvider) {
        if (!isInitialized) {
            apiUrl = selectedProvider.baseUrl
            model = selectedProvider.defaultModel
        }
        isInitialized = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTitleBar(
            title = "API Test",
            onBackClick = onBackClick,
            onAiClick = onNavigateHome
        )

        // Provider dropdown
        Text("AI Provider", color = Color.Gray, fontSize = 14.sp)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedProvider.displayName)
                Spacer(modifier = Modifier.weight(1f))
                Text("▼")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                com.ai.data.AiService.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        onClick = {
                            selectedProvider = provider
                            expanded = false
                        }
                    )
                }
            }
        }

        // API URL field
        OutlinedTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it },
            label = { Text("API URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // API Key field
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // Model field
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        // Prompt field
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Submit button
        Button(
            onClick = {
                if (prompt.isNotBlank()) {
                    // Save to persistent storage as "last test"
                    prefs.edit()
                        .putString("last_test_provider", selectedProvider.name)
                        .putString("last_test_api_url", apiUrl)
                        .putString("last_test_api_key", apiKey)
                        .putString("last_test_model", model)
                        .putString("last_test_prompt", prompt)
                        .apply()

                    isLoading = true
                    scope.launch {
                        // Enable API tracing temporarily
                        val wasTracingEnabled = com.ai.data.ApiTracer.isTracingEnabled
                        com.ai.data.ApiTracer.isTracingEnabled = true

                        // Get trace files before the call
                        val traceDir = java.io.File(context.filesDir, "trace")
                        val traceFilesBefore = traceDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

                        try {
                            // Make the API call using the repository
                            val repository = com.ai.data.AiAnalysisRepository()
                            val response = repository.testApiConnection(
                                service = selectedProvider,
                                apiKey = apiKey,
                                model = model,
                                baseUrl = apiUrl,
                                prompt = prompt
                            )

                            // Find the new trace file
                            val traceFilesAfter = traceDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                            val newTraceFile = (traceFilesAfter - traceFilesBefore).firstOrNull()

                            // Restore tracing state
                            com.ai.data.ApiTracer.isTracingEnabled = wasTracingEnabled

                            isLoading = false

                            // Navigate to trace detail if we found a new trace file
                            if (newTraceFile != null) {
                                onNavigateToTraceDetail(newTraceFile)
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    if (response.isSuccess) "Success but no trace file found" else "Error: ${response.error}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (e: Exception) {
                            com.ai.data.ApiTracer.isTracingEnabled = wasTracingEnabled
                            isLoading = false
                            android.widget.Toast.makeText(
                                context,
                                "Error: ${e.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            enabled = !isLoading && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Testing...")
            } else {
                Text("Submit")
            }
        }
    }
}
