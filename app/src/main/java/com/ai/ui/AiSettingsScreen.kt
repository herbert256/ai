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
import androidx.compose.ui.unit.dp
import com.ai.data.AiService

/**
 * Main AI settings screen with navigation cards for each AI service.
 */
@Composable
fun AiSettingsScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val imported = importAiConfigFromFile(context, it, aiSettings)
            if (imported != null) {
                onSave(imported)
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
            title = "ChatGPT",
            subtitle = "OpenAI",
            accentColor = Color(0xFF10A37F),
            onClick = { onNavigate(SettingsSubScreen.AI_CHATGPT) }
        )
        AiServiceNavigationCard(
            title = "Claude",
            subtitle = "Anthropic",
            accentColor = Color(0xFFD97706),
            onClick = { onNavigate(SettingsSubScreen.AI_CLAUDE) }
        )
        AiServiceNavigationCard(
            title = "Gemini",
            subtitle = "Google",
            accentColor = Color(0xFF4285F4),
            onClick = { onNavigate(SettingsSubScreen.AI_GEMINI) }
        )
        AiServiceNavigationCard(
            title = "Grok",
            subtitle = "xAI",
            accentColor = Color(0xFFFFFFFF),
            onClick = { onNavigate(SettingsSubScreen.AI_GROK) }
        )
        AiServiceNavigationCard(
            title = "Groq",
            subtitle = "Groq",
            accentColor = Color(0xFFF55036),
            onClick = { onNavigate(SettingsSubScreen.AI_GROQ) }
        )
        AiServiceNavigationCard(
            title = "DeepSeek",
            subtitle = "DeepSeek AI",
            accentColor = Color(0xFF4D6BFE),
            onClick = { onNavigate(SettingsSubScreen.AI_DEEPSEEK) }
        )
        AiServiceNavigationCard(
            title = "Mistral",
            subtitle = "Mistral AI",
            accentColor = Color(0xFFFF7000),
            onClick = { onNavigate(SettingsSubScreen.AI_MISTRAL) }
        )
        AiServiceNavigationCard(
            title = "Perplexity",
            subtitle = "Perplexity AI",
            accentColor = Color(0xFF20B2AA),
            onClick = { onNavigate(SettingsSubScreen.AI_PERPLEXITY) }
        )
        AiServiceNavigationCard(
            title = "Together",
            subtitle = "Together AI",
            accentColor = Color(0xFF6366F1),
            onClick = { onNavigate(SettingsSubScreen.AI_TOGETHER) }
        )
        AiServiceNavigationCard(
            title = "OpenRouter",
            subtitle = "OpenRouter AI",
            accentColor = Color(0xFF6B5AED),
            onClick = { onNavigate(SettingsSubScreen.AI_OPENROUTER) }
        )
        AiServiceNavigationCard(
            title = "SiliconFlow",
            subtitle = "SiliconFlow AI",
            accentColor = Color(0xFF00B4D8),
            onClick = { onNavigate(SettingsSubScreen.AI_SILICONFLOW) }
        )
        AiServiceNavigationCard(
            title = "Z.AI",
            subtitle = "ZhipuAI GLM Models",
            accentColor = Color(0xFF6366F1),
            onClick = { onNavigate(SettingsSubScreen.AI_ZAI) }
        )
        // Dummy provider only visible in developer mode
        if (developerMode) {
            AiServiceNavigationCard(
                title = "Dummy",
                subtitle = "For testing",
                accentColor = Color(0xFF888888),
                onClick = { onNavigate(SettingsSubScreen.AI_DUMMY) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Export AI configuration button
        if (aiSettings.hasAnyApiKey()) {
            Button(
                onClick = {
                    exportAiConfigToFile(context, aiSettings)
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
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onNavigate: (SettingsSubScreen) -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModelsAfterImport: (AiSettings) -> Unit = {},
    onRetrieveModelLists: () -> Unit = {}
) {
    val context = LocalContext.current

    // File picker launcher for importing AI configuration
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val imported = importAiConfigFromFile(context, it, aiSettings)
            if (imported != null) {
                onSave(imported)
                // Fetch models for providers with API model source
                onFetchModelsAfterImport(imported)
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

        Spacer(modifier = Modifier.height(8.dp))

        // Summary info
        val configuredAgents = aiSettings.agents.count { it.apiKey.isNotBlank() }

        Text(
            text = "Configure AI services:",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // AI Providers card
        AiSetupNavigationCard(
            title = "AI Providers",
            description = "Configure model sources for each AI service",
            icon = "⚙",
            count = "${AiService.entries.size} providers",
            onClick = { onNavigate(SettingsSubScreen.AI_PROVIDERS) }
        )

        // AI Agents card
        AiSetupNavigationCard(
            title = "AI Agents",
            description = "Configure agents with provider, model, and API key",
            icon = "🤖",
            count = "$configuredAgents configured",
            onClick = { onNavigate(SettingsSubScreen.AI_AGENTS) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Search model button
        Button(
            onClick = { onNavigate(SettingsSubScreen.MODEL_SEARCH) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
        ) {
            Text("Search model")
        }

        // Export AI configuration button
        Button(
            onClick = {
                exportAiConfigToFile(context, aiSettings)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Export AI configuration")
        }

        // Import AI configuration button (opens file picker)
        Button(
            onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
        ) {
            Text("Import AI configuration")
        }

        // Retrieve model lists button - only show if at least one provider has an API key
        val hasAnyApiKey = aiSettings.chatGptApiKey.isNotBlank() ||
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
                aiSettings.zaiApiKey.isNotBlank() ||
                aiSettings.dummyApiKey.isNotBlank()

        if (hasAnyApiKey) {
            Button(
                onClick = onRetrieveModelLists,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
            ) {
                Text("Retrieve model lists")
            }
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineMedium
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )
            }
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF00E676)
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF888888)
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

        // Provider cards - navigate to individual provider screens for model config
        AiServiceNavigationCard(
            title = "ChatGPT",
            subtitle = "OpenAI",
            accentColor = Color(0xFF10A37F),
            hasApiKey = aiSettings.chatGptApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_CHATGPT) }
        )
        AiServiceNavigationCard(
            title = "Claude",
            subtitle = "Anthropic",
            accentColor = Color(0xFFD97706),
            hasApiKey = aiSettings.claudeApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_CLAUDE) }
        )
        AiServiceNavigationCard(
            title = "Gemini",
            subtitle = "Google",
            accentColor = Color(0xFF4285F4),
            hasApiKey = aiSettings.geminiApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_GEMINI) }
        )
        AiServiceNavigationCard(
            title = "Grok",
            subtitle = "xAI",
            accentColor = Color(0xFFFFFFFF),
            hasApiKey = aiSettings.grokApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_GROK) }
        )
        AiServiceNavigationCard(
            title = "Groq",
            subtitle = "Groq",
            accentColor = Color(0xFFF55036),
            hasApiKey = aiSettings.groqApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_GROQ) }
        )
        AiServiceNavigationCard(
            title = "DeepSeek",
            subtitle = "DeepSeek AI",
            accentColor = Color(0xFF4D6BFE),
            hasApiKey = aiSettings.deepSeekApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_DEEPSEEK) }
        )
        AiServiceNavigationCard(
            title = "Mistral",
            subtitle = "Mistral AI",
            accentColor = Color(0xFFFF7000),
            hasApiKey = aiSettings.mistralApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_MISTRAL) }
        )
        AiServiceNavigationCard(
            title = "Perplexity",
            subtitle = "Perplexity AI",
            accentColor = Color(0xFF20B2AA),
            hasApiKey = aiSettings.perplexityApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_PERPLEXITY) }
        )
        AiServiceNavigationCard(
            title = "Together",
            subtitle = "Together AI",
            accentColor = Color(0xFF6366F1),
            hasApiKey = aiSettings.togetherApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_TOGETHER) }
        )
        AiServiceNavigationCard(
            title = "OpenRouter",
            subtitle = "OpenRouter AI",
            accentColor = Color(0xFF6B5AED),
            hasApiKey = aiSettings.openRouterApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_OPENROUTER) }
        )
        AiServiceNavigationCard(
            title = "SiliconFlow",
            subtitle = "SiliconFlow AI",
            accentColor = Color(0xFF00B4D8),
            hasApiKey = aiSettings.siliconFlowApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_SILICONFLOW) }
        )
        AiServiceNavigationCard(
            title = "Z.AI",
            subtitle = "ZhipuAI GLM Models",
            accentColor = Color(0xFF6366F1),
            hasApiKey = aiSettings.zaiApiKey.isNotBlank(),
            onClick = { onNavigate(SettingsSubScreen.AI_ZAI) }
        )
        // Dummy provider only visible in developer mode
        if (developerMode) {
            AiServiceNavigationCard(
                title = "Dummy",
                subtitle = "For testing",
                accentColor = Color(0xFF888888),
                hasApiKey = true,  // Dummy always has a "key"
                onClick = { onNavigate(SettingsSubScreen.AI_DUMMY) }
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
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    availableDummyModels: List<String>,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSaveAiSettings: (AiSettings) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String?,
    onFetchChatGptModels: (String) -> Unit,
    onFetchGeminiModels: (String) -> Unit,
    onFetchGrokModels: (String) -> Unit,
    onFetchGroqModels: (String) -> Unit,
    onFetchDeepSeekModels: (String) -> Unit,
    onFetchMistralModels: (String) -> Unit,
    onFetchPerplexityModels: (String) -> Unit,
    onFetchTogetherModels: (String) -> Unit,
    onFetchOpenRouterModels: (String) -> Unit,
    onFetchDummyModels: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<ModelSearchItem?>(null) }

    // Auto-fetch models for providers with ModelSource = API on screen enter
    LaunchedEffect(Unit) {
        // ChatGPT
        if (aiSettings.chatGptModelSource == ModelSource.API && aiSettings.chatGptApiKey.isNotBlank()) {
            onFetchChatGptModels(aiSettings.chatGptApiKey)
        }
        // Gemini
        if (aiSettings.geminiModelSource == ModelSource.API && aiSettings.geminiApiKey.isNotBlank()) {
            onFetchGeminiModels(aiSettings.geminiApiKey)
        }
        // Grok
        if (aiSettings.grokModelSource == ModelSource.API && aiSettings.grokApiKey.isNotBlank()) {
            onFetchGrokModels(aiSettings.grokApiKey)
        }
        // Groq
        if (aiSettings.groqModelSource == ModelSource.API && aiSettings.groqApiKey.isNotBlank()) {
            onFetchGroqModels(aiSettings.groqApiKey)
        }
        // DeepSeek
        if (aiSettings.deepSeekModelSource == ModelSource.API && aiSettings.deepSeekApiKey.isNotBlank()) {
            onFetchDeepSeekModels(aiSettings.deepSeekApiKey)
        }
        // Mistral
        if (aiSettings.mistralModelSource == ModelSource.API && aiSettings.mistralApiKey.isNotBlank()) {
            onFetchMistralModels(aiSettings.mistralApiKey)
        }
        // Perplexity (no API for models, uses manual)
        // Together
        if (aiSettings.togetherModelSource == ModelSource.API && aiSettings.togetherApiKey.isNotBlank()) {
            onFetchTogetherModels(aiSettings.togetherApiKey)
        }
        // OpenRouter
        if (aiSettings.openRouterModelSource == ModelSource.API && aiSettings.openRouterApiKey.isNotBlank()) {
            onFetchOpenRouterModels(aiSettings.openRouterApiKey)
        }
        // Dummy
        if (aiSettings.dummyModelSource == ModelSource.API && aiSettings.dummyApiKey.isNotBlank()) {
            onFetchDummyModels(aiSettings.dummyApiKey)
        }
    }

    // Helper to fetch models for a provider
    val fetchModelsForProvider: (AiService, String) -> Unit = { provider, apiKey ->
        when (provider) {
            AiService.CHATGPT -> onFetchChatGptModels(apiKey)
            AiService.GEMINI -> onFetchGeminiModels(apiKey)
            AiService.GROK -> onFetchGrokModels(apiKey)
            AiService.GROQ -> onFetchGroqModels(apiKey)
            AiService.DEEPSEEK -> onFetchDeepSeekModels(apiKey)
            AiService.MISTRAL -> onFetchMistralModels(apiKey)
            AiService.PERPLEXITY -> onFetchPerplexityModels(apiKey)
            AiService.TOGETHER -> onFetchTogetherModels(apiKey)
            AiService.OPENROUTER -> onFetchOpenRouterModels(apiKey)
            AiService.DUMMY -> onFetchDummyModels(apiKey)
            AiService.CLAUDE -> {} // Claude has hardcoded models
            AiService.SILICONFLOW -> {} // SiliconFlow has hardcoded models
            AiService.ZAI -> {} // Z.AI has hardcoded models
        }
    }

    // Show AgentEditScreen when a model is selected
    if (selectedModel != null) {
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
            onSave = { newAgent ->
                val newAgents = aiSettings.agents + newAgent
                onSaveAiSettings(aiSettings.copy(agents = newAgents))
                selectedModel = null
            },
            onBack = { selectedModel = null },
            onNavigateHome = onBackToHome
        )
        return
    }

    // Combine all models with their provider info
    val allModels = remember(
        availableChatGptModels, availableGeminiModels, availableGrokModels,
        availableGroqModels, availableDeepSeekModels, availableMistralModels,
        availablePerplexityModels, availableTogetherModels, availableOpenRouterModels,
        availableDummyModels, aiSettings
    ) {
        buildList {
            // ChatGPT models
            availableChatGptModels.forEach { add(ModelSearchItem(AiService.CHATGPT, "ChatGPT", it, Color(0xFF10A37F))) }
            // Claude models (hardcoded)
            aiSettings.claudeManualModels.forEach { add(ModelSearchItem(AiService.CLAUDE, "Claude", it, Color(0xFFD97706))) }
            // Gemini models
            availableGeminiModels.forEach { add(ModelSearchItem(AiService.GEMINI, "Gemini", it, Color(0xFF4285F4))) }
            // Grok models
            availableGrokModels.forEach { add(ModelSearchItem(AiService.GROK, "Grok", it, Color(0xFFFFFFFF))) }
            // Groq models
            availableGroqModels.forEach { add(ModelSearchItem(AiService.GROQ, "Groq", it, Color(0xFFF55036))) }
            // DeepSeek models
            availableDeepSeekModels.forEach { add(ModelSearchItem(AiService.DEEPSEEK, "DeepSeek", it, Color(0xFF4D6BFE))) }
            // Mistral models
            availableMistralModels.forEach { add(ModelSearchItem(AiService.MISTRAL, "Mistral", it, Color(0xFFFF7000))) }
            // Perplexity models (hardcoded)
            aiSettings.perplexityManualModels.forEach { add(ModelSearchItem(AiService.PERPLEXITY, "Perplexity", it, Color(0xFF20B2AA))) }
            // Together models
            availableTogetherModels.forEach { add(ModelSearchItem(AiService.TOGETHER, "Together", it, Color(0xFF6366F1))) }
            // OpenRouter models
            availableOpenRouterModels.forEach { add(ModelSearchItem(AiService.OPENROUTER, "OpenRouter", it, Color(0xFF6B5AED))) }
            // SiliconFlow models (manual only)
            aiSettings.siliconFlowManualModels.forEach { add(ModelSearchItem(AiService.SILICONFLOW, "SiliconFlow", it, Color(0xFF00B4D8))) }
            // Z.AI models (manual only)
            aiSettings.zaiManualModels.forEach { add(ModelSearchItem(AiService.ZAI, "Z.AI", it, Color(0xFF6366F1))) }
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

        // Results count
        Text(
            text = "${filteredModels.size} models found",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAAAAAA)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Model list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredModels) { item ->
                ModelSearchResultCard(
                    item = item,
                    onClick = { selectedModel = item }
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
        "ChatGPT" -> AiService.CHATGPT
        "Claude" -> AiService.CLAUDE
        "Gemini" -> AiService.GEMINI
        "Grok" -> AiService.GROK
        "Groq" -> AiService.GROQ
        "DeepSeek" -> AiService.DEEPSEEK
        "Mistral" -> AiService.MISTRAL
        "Perplexity" -> AiService.PERPLEXITY
        "Together" -> AiService.TOGETHER
        "OpenRouter" -> AiService.OPENROUTER
        "SiliconFlow" -> AiService.SILICONFLOW
        "Z.AI" -> AiService.ZAI
        "Dummy" -> AiService.DUMMY
        else -> AiService.CHATGPT
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
