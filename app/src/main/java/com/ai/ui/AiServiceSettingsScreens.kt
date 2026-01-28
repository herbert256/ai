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
    var adminUrl by remember { mutableStateOf(aiSettings.chatGptAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.chatGptModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.OPENAI)) }

    // Effective model list for dropdown
    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    // Track if there are unsaved changes
    val hasChanges = apiKey != aiSettings.chatGptApiKey ||
            defaultModel != aiSettings.chatGptModel ||
            modelSource != aiSettings.chatGptModelSource ||
            manualModels != aiSettings.chatGptManualModels ||
            adminUrl != aiSettings.chatGptAdminUrl ||
            modelListUrl != aiSettings.chatGptModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.OPENAI)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.chatGptApiKey.isNotBlank() && aiSettings.chatGptModelSource == ModelSource.API) {
            onFetchModels(aiSettings.chatGptApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "OpenAI",
        accentColor = Color(0xFF10A37F),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                chatGptApiKey = apiKey,
                chatGptModel = defaultModel,
                chatGptModelSource = modelSource,
                chatGptManualModels = manualModels,
                chatGptAdminUrl = adminUrl,
                chatGptModelListUrl = modelListUrl
            ).withEndpoints(AiService.OPENAI, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.OPENAI, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.OPENAI, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.OPENAI),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.OPENAI.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.claudeAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.claudeModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.ANTHROPIC)) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.claudeApiKey ||
            defaultModel != aiSettings.claudeModel ||
            modelSource != aiSettings.claudeModelSource ||
            manualModels != aiSettings.claudeManualModels ||
            adminUrl != aiSettings.claudeAdminUrl ||
            modelListUrl != aiSettings.claudeModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.ANTHROPIC)

    AiServiceSettingsScreenTemplate(
        title = "Anthropic",
        accentColor = Color(0xFFD97706),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                claudeApiKey = apiKey,
                claudeModel = defaultModel,
                claudeModelSource = modelSource,
                claudeManualModels = manualModels,
                claudeAdminUrl = adminUrl,
                claudeModelListUrl = modelListUrl
            ).withEndpoints(AiService.ANTHROPIC, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.ANTHROPIC, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.ANTHROPIC, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.ANTHROPIC),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.ANTHROPIC.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.geminiAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.geminiModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.GOOGLE)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.geminiApiKey ||
            defaultModel != aiSettings.geminiModel ||
            modelSource != aiSettings.geminiModelSource ||
            manualModels != aiSettings.geminiManualModels ||
            adminUrl != aiSettings.geminiAdminUrl ||
            modelListUrl != aiSettings.geminiModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.GOOGLE)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.geminiApiKey.isNotBlank() && aiSettings.geminiModelSource == ModelSource.API) {
            onFetchModels(aiSettings.geminiApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Google",
        accentColor = Color(0xFF4285F4),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                geminiApiKey = apiKey,
                geminiModel = defaultModel,
                geminiModelSource = modelSource,
                geminiManualModels = manualModels,
                geminiAdminUrl = adminUrl,
                geminiModelListUrl = modelListUrl
            ).withEndpoints(AiService.GOOGLE, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.GOOGLE, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.GOOGLE, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.GOOGLE),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.GOOGLE.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.grokAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.grokModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.XAI)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.grokApiKey ||
            defaultModel != aiSettings.grokModel ||
            modelSource != aiSettings.grokModelSource ||
            manualModels != aiSettings.grokManualModels ||
            adminUrl != aiSettings.grokAdminUrl ||
            modelListUrl != aiSettings.grokModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.XAI)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.grokApiKey.isNotBlank() && aiSettings.grokModelSource == ModelSource.API) {
            onFetchModels(aiSettings.grokApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "xAI",
        accentColor = Color(0xFFFFFFFF),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                grokApiKey = apiKey,
                grokModel = defaultModel,
                grokModelSource = modelSource,
                grokManualModels = manualModels,
                grokAdminUrl = adminUrl,
                grokModelListUrl = modelListUrl
            ).withEndpoints(AiService.XAI, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.XAI, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.XAI, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.XAI),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.XAI.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.groqAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.groqModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.GROQ)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.groqApiKey ||
            defaultModel != aiSettings.groqModel ||
            modelSource != aiSettings.groqModelSource ||
            manualModels != aiSettings.groqManualModels ||
            adminUrl != aiSettings.groqAdminUrl ||
            modelListUrl != aiSettings.groqModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.GROQ)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.groqApiKey.isNotBlank() && aiSettings.groqModelSource == ModelSource.API) {
            onFetchModels(aiSettings.groqApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Groq",
        accentColor = Color(0xFFF55036),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                groqApiKey = apiKey,
                groqModel = defaultModel,
                groqModelSource = modelSource,
                groqManualModels = manualModels,
                groqAdminUrl = adminUrl,
                groqModelListUrl = modelListUrl
            ).withEndpoints(AiService.GROQ, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.GROQ, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.GROQ, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.GROQ),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.GROQ.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.deepSeekAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.deepSeekModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.DEEPSEEK)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.deepSeekApiKey ||
            defaultModel != aiSettings.deepSeekModel ||
            modelSource != aiSettings.deepSeekModelSource ||
            manualModels != aiSettings.deepSeekManualModels ||
            adminUrl != aiSettings.deepSeekAdminUrl ||
            modelListUrl != aiSettings.deepSeekModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.DEEPSEEK)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.deepSeekApiKey.isNotBlank() && aiSettings.deepSeekModelSource == ModelSource.API) {
            onFetchModels(aiSettings.deepSeekApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "DeepSeek",
        accentColor = Color(0xFF4D6BFE),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                deepSeekApiKey = apiKey,
                deepSeekModel = defaultModel,
                deepSeekModelSource = modelSource,
                deepSeekManualModels = manualModels,
                deepSeekAdminUrl = adminUrl,
                deepSeekModelListUrl = modelListUrl
            ).withEndpoints(AiService.DEEPSEEK, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.DEEPSEEK, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.DEEPSEEK, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.DEEPSEEK),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.DEEPSEEK.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.mistralAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.mistralModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.MISTRAL)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.mistralApiKey ||
            defaultModel != aiSettings.mistralModel ||
            modelSource != aiSettings.mistralModelSource ||
            manualModels != aiSettings.mistralManualModels ||
            adminUrl != aiSettings.mistralAdminUrl ||
            modelListUrl != aiSettings.mistralModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.MISTRAL)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.mistralApiKey.isNotBlank() && aiSettings.mistralModelSource == ModelSource.API) {
            onFetchModels(aiSettings.mistralApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Mistral",
        accentColor = Color(0xFFFF7000),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                mistralApiKey = apiKey,
                mistralModel = defaultModel,
                mistralModelSource = modelSource,
                mistralManualModels = manualModels,
                mistralAdminUrl = adminUrl,
                mistralModelListUrl = modelListUrl
            ).withEndpoints(AiService.MISTRAL, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.MISTRAL, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.MISTRAL, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.MISTRAL),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.MISTRAL.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.perplexityAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.perplexityModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.PERPLEXITY)) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.perplexityApiKey ||
            defaultModel != aiSettings.perplexityModel ||
            modelSource != aiSettings.perplexityModelSource ||
            manualModels != aiSettings.perplexityManualModels ||
            adminUrl != aiSettings.perplexityAdminUrl ||
            modelListUrl != aiSettings.perplexityModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.PERPLEXITY)

    AiServiceSettingsScreenTemplate(
        title = "Perplexity",
        accentColor = Color(0xFF20B2AA),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                perplexityApiKey = apiKey,
                perplexityModel = defaultModel,
                perplexityModelSource = modelSource,
                perplexityManualModels = manualModels,
                perplexityAdminUrl = adminUrl,
                perplexityModelListUrl = modelListUrl
            ).withEndpoints(AiService.PERPLEXITY, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.PERPLEXITY, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.PERPLEXITY, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.PERPLEXITY),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.PERPLEXITY.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.togetherAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.togetherModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.TOGETHER)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.togetherApiKey ||
            defaultModel != aiSettings.togetherModel ||
            modelSource != aiSettings.togetherModelSource ||
            manualModels != aiSettings.togetherManualModels ||
            adminUrl != aiSettings.togetherAdminUrl ||
            modelListUrl != aiSettings.togetherModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.TOGETHER)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.togetherApiKey.isNotBlank() && aiSettings.togetherModelSource == ModelSource.API) {
            onFetchModels(aiSettings.togetherApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Together",
        accentColor = Color(0xFF6366F1),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                togetherApiKey = apiKey,
                togetherModel = defaultModel,
                togetherModelSource = modelSource,
                togetherManualModels = manualModels,
                togetherAdminUrl = adminUrl,
                togetherModelListUrl = modelListUrl
            ).withEndpoints(AiService.TOGETHER, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.TOGETHER, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.TOGETHER, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.TOGETHER),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.TOGETHER.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.openRouterAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.openRouterModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.OPENROUTER)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.openRouterApiKey ||
            defaultModel != aiSettings.openRouterModel ||
            modelSource != aiSettings.openRouterModelSource ||
            manualModels != aiSettings.openRouterManualModels ||
            adminUrl != aiSettings.openRouterAdminUrl ||
            modelListUrl != aiSettings.openRouterModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.OPENROUTER)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.openRouterApiKey.isNotBlank() && aiSettings.openRouterModelSource == ModelSource.API) {
            onFetchModels(aiSettings.openRouterApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "OpenRouter",
        accentColor = Color(0xFF6B5AED),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                openRouterApiKey = apiKey,
                openRouterModel = defaultModel,
                openRouterModelSource = modelSource,
                openRouterManualModels = manualModels,
                openRouterAdminUrl = adminUrl,
                openRouterModelListUrl = modelListUrl
            ).withEndpoints(AiService.OPENROUTER, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.OPENROUTER, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.OPENROUTER, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.OPENROUTER),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.OPENROUTER.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.siliconFlowAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.siliconFlowModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.SILICONFLOW)) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.siliconFlowApiKey ||
            defaultModel != aiSettings.siliconFlowModel ||
            modelSource != aiSettings.siliconFlowModelSource ||
            manualModels != aiSettings.siliconFlowManualModels ||
            adminUrl != aiSettings.siliconFlowAdminUrl ||
            modelListUrl != aiSettings.siliconFlowModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.SILICONFLOW)

    AiServiceSettingsScreenTemplate(
        title = "SiliconFlow",
        accentColor = Color(0xFF00B4D8),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                siliconFlowApiKey = apiKey,
                siliconFlowModel = defaultModel,
                siliconFlowModelSource = modelSource,
                siliconFlowManualModels = manualModels,
                siliconFlowAdminUrl = adminUrl,
                siliconFlowModelListUrl = modelListUrl
            ).withEndpoints(AiService.SILICONFLOW, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.SILICONFLOW, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.SILICONFLOW, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.SILICONFLOW),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.SILICONFLOW.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.zaiAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.zaiModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.ZAI)) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.zaiApiKey ||
            defaultModel != aiSettings.zaiModel ||
            modelSource != aiSettings.zaiModelSource ||
            manualModels != aiSettings.zaiManualModels ||
            adminUrl != aiSettings.zaiAdminUrl ||
            modelListUrl != aiSettings.zaiModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.ZAI)

    AiServiceSettingsScreenTemplate(
        title = "Z.AI",
        accentColor = Color(0xFF6366F1),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                zaiApiKey = apiKey,
                zaiModel = defaultModel,
                zaiModelSource = modelSource,
                zaiManualModels = manualModels,
                zaiAdminUrl = adminUrl,
                zaiModelListUrl = modelListUrl
            ).withEndpoints(AiService.ZAI, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.ZAI, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.ZAI, apiKey, defaultModel) }
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
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.ZAI),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.ZAI.baseUrl,
            onEndpointsChange = { endpoints = it }
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
    var adminUrl by remember { mutableStateOf(aiSettings.dummyAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.dummyModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.DUMMY)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.dummyApiKey ||
            defaultModel != aiSettings.dummyModel ||
            modelSource != aiSettings.dummyModelSource ||
            manualModels != aiSettings.dummyManualModels ||
            adminUrl != aiSettings.dummyAdminUrl ||
            modelListUrl != aiSettings.dummyModelListUrl ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.DUMMY)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.dummyApiKey.isNotBlank() && aiSettings.dummyModelSource == ModelSource.API) {
            onFetchModels(aiSettings.dummyApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Dummy",
        accentColor = Color(0xFF888888),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                dummyApiKey = apiKey,
                dummyModel = defaultModel,
                dummyModelSource = modelSource,
                dummyManualModels = manualModels,
                dummyAdminUrl = adminUrl,
                dummyModelListUrl = modelListUrl
            ).withEndpoints(AiService.DUMMY, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
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
        UnifiedModelSelectionSection(
            modelSource = modelSource,
            manualModels = manualModels,
            availableApiModels = availableModels,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onManualModelsChange = { manualModels = it },
            onFetchModels = { onFetchModels(apiKey) }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.DUMMY),
            onModelListUrlChange = { modelListUrl = it }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.DUMMY.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
    }
}
