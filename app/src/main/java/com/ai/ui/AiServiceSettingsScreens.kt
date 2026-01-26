package com.ai.ui

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
 * ChatGPT settings screen.
 */
@Composable
fun ChatGptSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.chatGptApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.chatGptModel) }
    var modelSource by remember { mutableStateOf(aiSettings.chatGptModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.chatGptManualModels) }

    // Track if there are unsaved changes
    val hasChanges = apiKey != aiSettings.chatGptApiKey ||
            defaultModel != aiSettings.chatGptModel ||
            modelSource != aiSettings.chatGptModelSource ||
            manualModels != aiSettings.chatGptManualModels

    AiServiceSettingsScreenTemplate(
        title = "OpenAI",
        subtitle = "OpenAI",
        accentColor = Color(0xFF10A37F),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                chatGptApiKey = apiKey,
                chatGptModel = defaultModel,
                chatGptModelSource = modelSource,
                chatGptManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.OPENAI.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.OPENAI, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.OPENAI, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Claude settings screen.
 */
@Composable
fun ClaudeSettingsScreen(
    aiSettings: AiSettings,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.claudeApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.claudeModel) }
    var modelSource by remember { mutableStateOf(aiSettings.claudeModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.claudeManualModels) }

    val hasChanges = apiKey != aiSettings.claudeApiKey ||
            defaultModel != aiSettings.claudeModel ||
            modelSource != aiSettings.claudeModelSource ||
            manualModels != aiSettings.claudeManualModels

    AiServiceSettingsScreenTemplate(
        title = "Anthropic",
        subtitle = "Anthropic",
        accentColor = Color(0xFFD97706),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                claudeApiKey = apiKey,
                claudeModel = defaultModel,
                claudeModelSource = modelSource,
                claudeManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.ANTHROPIC.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.ANTHROPIC, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.ANTHROPIC, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = emptyList(),
            isLoadingModels = false,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { }
        )
    }
}

/**
 * Gemini settings screen.
 */
@Composable
fun GeminiSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.geminiApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.geminiModel) }
    var modelSource by remember { mutableStateOf(aiSettings.geminiModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.geminiManualModels) }

    val hasChanges = apiKey != aiSettings.geminiApiKey ||
            defaultModel != aiSettings.geminiModel ||
            modelSource != aiSettings.geminiModelSource ||
            manualModels != aiSettings.geminiManualModels

    AiServiceSettingsScreenTemplate(
        title = "Google",
        subtitle = "Google",
        accentColor = Color(0xFF4285F4),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                geminiApiKey = apiKey,
                geminiModel = defaultModel,
                geminiModelSource = modelSource,
                geminiManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.GOOGLE.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.GOOGLE, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.GOOGLE, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Grok settings screen.
 */
@Composable
fun GrokSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.grokApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.grokModel) }
    var modelSource by remember { mutableStateOf(aiSettings.grokModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.grokManualModels) }

    val hasChanges = apiKey != aiSettings.grokApiKey ||
            defaultModel != aiSettings.grokModel ||
            modelSource != aiSettings.grokModelSource ||
            manualModels != aiSettings.grokManualModels

    AiServiceSettingsScreenTemplate(
        title = "xAI",
        subtitle = "xAI",
        accentColor = Color(0xFFFFFFFF),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                grokApiKey = apiKey,
                grokModel = defaultModel,
                grokModelSource = modelSource,
                grokManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.XAI.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.XAI, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.XAI, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Groq settings screen.
 */
@Composable
fun GroqSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.groqApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.groqModel) }
    var modelSource by remember { mutableStateOf(aiSettings.groqModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.groqManualModels) }

    val hasChanges = apiKey != aiSettings.groqApiKey ||
            defaultModel != aiSettings.groqModel ||
            modelSource != aiSettings.groqModelSource ||
            manualModels != aiSettings.groqManualModels

    AiServiceSettingsScreenTemplate(
        title = "Groq",
        subtitle = "Groq",
        accentColor = Color(0xFFF55036),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                groqApiKey = apiKey,
                groqModel = defaultModel,
                groqModelSource = modelSource,
                groqManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.GROQ.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.GROQ, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.GROQ, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * DeepSeek settings screen.
 */
@Composable
fun DeepSeekSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.deepSeekApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.deepSeekModel) }
    var modelSource by remember { mutableStateOf(aiSettings.deepSeekModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.deepSeekManualModels) }

    val hasChanges = apiKey != aiSettings.deepSeekApiKey ||
            defaultModel != aiSettings.deepSeekModel ||
            modelSource != aiSettings.deepSeekModelSource ||
            manualModels != aiSettings.deepSeekManualModels

    AiServiceSettingsScreenTemplate(
        title = "DeepSeek",
        subtitle = "DeepSeek AI",
        accentColor = Color(0xFF4D6BFE),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                deepSeekApiKey = apiKey,
                deepSeekModel = defaultModel,
                deepSeekModelSource = modelSource,
                deepSeekManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.DEEPSEEK.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.DEEPSEEK, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.DEEPSEEK, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Mistral settings screen.
 */
@Composable
fun MistralSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.mistralApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.mistralModel) }
    var modelSource by remember { mutableStateOf(aiSettings.mistralModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.mistralManualModels) }

    val hasChanges = apiKey != aiSettings.mistralApiKey ||
            defaultModel != aiSettings.mistralModel ||
            modelSource != aiSettings.mistralModelSource ||
            manualModels != aiSettings.mistralManualModels

    AiServiceSettingsScreenTemplate(
        title = "Mistral",
        subtitle = "Mistral AI",
        accentColor = Color(0xFFFF7000),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                mistralApiKey = apiKey,
                mistralModel = defaultModel,
                mistralModelSource = modelSource,
                mistralManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.MISTRAL.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.MISTRAL, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.MISTRAL, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * Perplexity settings screen.
 */
@Composable
fun PerplexitySettingsScreen(
    aiSettings: AiSettings,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.perplexityApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.perplexityModel) }
    var modelSource by remember { mutableStateOf(aiSettings.perplexityModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.perplexityManualModels) }

    val hasChanges = apiKey != aiSettings.perplexityApiKey ||
            defaultModel != aiSettings.perplexityModel ||
            modelSource != aiSettings.perplexityModelSource ||
            manualModels != aiSettings.perplexityManualModels

    AiServiceSettingsScreenTemplate(
        title = "Perplexity",
        subtitle = "Perplexity AI",
        accentColor = Color(0xFF20B2AA),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                perplexityApiKey = apiKey,
                perplexityModel = defaultModel,
                perplexityModelSource = modelSource,
                perplexityManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.PERPLEXITY.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.PERPLEXITY, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.PERPLEXITY, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = emptyList(),
            isLoadingModels = false,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { }
        )
    }
}

/**
 * Together AI settings screen.
 */
@Composable
fun TogetherSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.togetherApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.togetherModel) }
    var modelSource by remember { mutableStateOf(aiSettings.togetherModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.togetherManualModels) }

    val hasChanges = apiKey != aiSettings.togetherApiKey ||
            defaultModel != aiSettings.togetherModel ||
            modelSource != aiSettings.togetherModelSource ||
            manualModels != aiSettings.togetherManualModels

    AiServiceSettingsScreenTemplate(
        title = "Together",
        subtitle = "Together AI",
        accentColor = Color(0xFF6366F1),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                togetherApiKey = apiKey,
                togetherModel = defaultModel,
                togetherModelSource = modelSource,
                togetherManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.TOGETHER.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.TOGETHER, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.TOGETHER, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * OpenRouter AI settings screen.
 */
@Composable
fun OpenRouterSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.openRouterApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.openRouterModel) }
    var modelSource by remember { mutableStateOf(aiSettings.openRouterModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.openRouterManualModels) }

    val hasChanges = apiKey != aiSettings.openRouterApiKey ||
            defaultModel != aiSettings.openRouterModel ||
            modelSource != aiSettings.openRouterModelSource ||
            manualModels != aiSettings.openRouterManualModels

    AiServiceSettingsScreenTemplate(
        title = "OpenRouter",
        subtitle = "OpenRouter AI",
        accentColor = Color(0xFF6B5AED),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                openRouterApiKey = apiKey,
                openRouterModel = defaultModel,
                openRouterModelSource = modelSource,
                openRouterManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.OPENROUTER.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.OPENROUTER, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.OPENROUTER, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}

/**
 * SiliconFlow settings screen.
 */
@Composable
fun SiliconFlowSettingsScreen(
    aiSettings: AiSettings,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.siliconFlowApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.siliconFlowModel) }
    var modelSource by remember { mutableStateOf(aiSettings.siliconFlowModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.siliconFlowManualModels) }

    val hasChanges = apiKey != aiSettings.siliconFlowApiKey ||
            defaultModel != aiSettings.siliconFlowModel ||
            modelSource != aiSettings.siliconFlowModelSource ||
            manualModels != aiSettings.siliconFlowManualModels

    AiServiceSettingsScreenTemplate(
        title = "SiliconFlow",
        subtitle = "SiliconFlow AI",
        accentColor = Color(0xFF00B4D8),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                siliconFlowApiKey = apiKey,
                siliconFlowModel = defaultModel,
                siliconFlowModelSource = modelSource,
                siliconFlowManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.SILICONFLOW.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.SILICONFLOW, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.SILICONFLOW, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = emptyList(),
            isLoadingModels = false,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { }
        )
    }
}

/**
 * Z.AI settings screen (uses ZhipuAI GLM models).
 */
@Composable
fun ZaiSettingsScreen(
    aiSettings: AiSettings,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.zaiApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.zaiModel) }
    var modelSource by remember { mutableStateOf(aiSettings.zaiModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.zaiManualModels) }

    val hasChanges = apiKey != aiSettings.zaiApiKey ||
            defaultModel != aiSettings.zaiModel ||
            modelSource != aiSettings.zaiModelSource ||
            manualModels != aiSettings.zaiManualModels

    AiServiceSettingsScreenTemplate(
        title = "Z.AI",
        subtitle = "ZhipuAI GLM Models",
        accentColor = Color(0xFF6366F1),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                zaiApiKey = apiKey,
                zaiModel = defaultModel,
                zaiModelSource = modelSource,
                zaiManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.ZAI.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.ZAI, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.ZAI, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = emptyList(),
            isLoadingModels = false,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { }
        )
    }
}

/**
 * Dummy settings screen (for testing with local HTTP server).
 */
@Composable
fun DummySettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.dummyApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.dummyModel) }
    var modelSource by remember { mutableStateOf(aiSettings.dummyModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.dummyManualModels) }

    val hasChanges = apiKey != aiSettings.dummyApiKey ||
            defaultModel != aiSettings.dummyModel ||
            modelSource != aiSettings.dummyModelSource ||
            manualModels != aiSettings.dummyManualModels

    AiServiceSettingsScreenTemplate(
        title = "Dummy",
        subtitle = "Local test server (port 54321)",
        accentColor = Color(0xFF888888),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                dummyApiKey = apiKey,
                dummyModel = defaultModel,
                dummyModelSource = modelSource,
                dummyManualModels = manualModels
            ))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        adminUrl = AiService.DUMMY.adminUrl,
        onTestApiKey = { onTestApiKey(AiService.DUMMY, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        // Info card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A3A2A)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Test Provider",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00E676)
                )
                Text(
                    text = "Uses local HTTP server on port 54321. API key must be \"dummy\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )
                Text(
                    text = "Response: \"Hi, Greetings from AI\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF00E676)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.DUMMY, apiKey, defaultModel) }
        )
        DefaultModelInputSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it }
        )
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
    }
}
