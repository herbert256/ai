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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.chatGptApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.chatGptModel) }
    var modelSource by remember { mutableStateOf(aiSettings.chatGptModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.chatGptManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.chatGptAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.chatGptModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.chatGptParametersIds) }

    // Default OpenAI endpoints - Chat Completions for gpt-4o etc, Responses for gpt-5.x/o3/o4
    val defaultOpenAiEndpoints = listOf(
        AiEndpoint(
            id = "openai-chat-completions",
            name = "Chat Completions (gpt-4o, gpt-4, gpt-3.5)",
            url = "https://api.openai.com/v1/chat/completions",
            isDefault = true
        ),
        AiEndpoint(
            id = "openai-responses",
            name = "Responses (gpt-5.x, o3, o4)",
            url = "https://api.openai.com/v1/responses",
            isDefault = false
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.OPENAI).ifEmpty { defaultOpenAiEndpoints }
        )
    }

    // Effective model list for dropdown
    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    // Track if there are unsaved changes
    val hasChanges = apiKey != aiSettings.chatGptApiKey ||
            defaultModel != aiSettings.chatGptModel ||
            modelSource != aiSettings.chatGptModelSource ||
            manualModels != aiSettings.chatGptManualModels ||
            adminUrl != aiSettings.chatGptAdminUrl ||
            modelListUrl != aiSettings.chatGptModelListUrl ||
            selectedParametersIds != aiSettings.chatGptParametersIds ||
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
                chatGptModelListUrl = modelListUrl,
                chatGptParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.OPENAI, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.OPENAI.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.OPENAI),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.claudeApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.claudeModel) }
    var modelSource by remember { mutableStateOf(aiSettings.claudeModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.claudeManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.claudeAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.claudeModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.ANTHROPIC)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.claudeParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.claudeApiKey ||
            defaultModel != aiSettings.claudeModel ||
            modelSource != aiSettings.claudeModelSource ||
            manualModels != aiSettings.claudeManualModels ||
            adminUrl != aiSettings.claudeAdminUrl ||
            modelListUrl != aiSettings.claudeModelListUrl ||
            selectedParametersIds != aiSettings.claudeParametersIds ||
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
                claudeModelListUrl = modelListUrl,
                claudeParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.ANTHROPIC, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.ANTHROPIC.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.ANTHROPIC),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.geminiApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.geminiModel) }
    var modelSource by remember { mutableStateOf(aiSettings.geminiModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.geminiManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.geminiAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.geminiModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.GOOGLE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.geminiParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.geminiApiKey ||
            defaultModel != aiSettings.geminiModel ||
            modelSource != aiSettings.geminiModelSource ||
            manualModels != aiSettings.geminiManualModels ||
            adminUrl != aiSettings.geminiAdminUrl ||
            modelListUrl != aiSettings.geminiModelListUrl ||
            selectedParametersIds != aiSettings.geminiParametersIds ||
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
                geminiModelListUrl = modelListUrl,
                geminiParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.GOOGLE, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.GOOGLE.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.GOOGLE),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.grokApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.grokModel) }
    var modelSource by remember { mutableStateOf(aiSettings.grokModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.grokManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.grokAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.grokModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.XAI)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.grokParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.grokApiKey ||
            defaultModel != aiSettings.grokModel ||
            modelSource != aiSettings.grokModelSource ||
            manualModels != aiSettings.grokManualModels ||
            adminUrl != aiSettings.grokAdminUrl ||
            modelListUrl != aiSettings.grokModelListUrl ||
            selectedParametersIds != aiSettings.grokParametersIds ||
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
                grokModelListUrl = modelListUrl,
                grokParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.XAI, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.XAI.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.XAI),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.groqApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.groqModel) }
    var modelSource by remember { mutableStateOf(aiSettings.groqModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.groqManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.groqAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.groqModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.GROQ)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.groqParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.groqApiKey ||
            defaultModel != aiSettings.groqModel ||
            modelSource != aiSettings.groqModelSource ||
            manualModels != aiSettings.groqManualModels ||
            adminUrl != aiSettings.groqAdminUrl ||
            modelListUrl != aiSettings.groqModelListUrl ||
            selectedParametersIds != aiSettings.groqParametersIds ||
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
                groqModelListUrl = modelListUrl,
                groqParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.GROQ, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.GROQ.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.GROQ),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.deepSeekApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.deepSeekModel) }
    var modelSource by remember { mutableStateOf(aiSettings.deepSeekModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.deepSeekManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.deepSeekAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.deepSeekModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.deepSeekParametersIds) }

    // Default DeepSeek endpoints - Standard chat and Beta (FIM/prefix completion)
    val defaultDeepSeekEndpoints = listOf(
        AiEndpoint(
            id = "deepseek-chat-completions",
            name = "Chat Completions (Standard)",
            url = "https://api.deepseek.com/chat/completions",
            isDefault = true
        ),
        AiEndpoint(
            id = "deepseek-beta",
            name = "Beta (FIM/Prefix Completion)",
            url = "https://api.deepseek.com/beta/completions",
            isDefault = false
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.DEEPSEEK).ifEmpty { defaultDeepSeekEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.deepSeekApiKey ||
            defaultModel != aiSettings.deepSeekModel ||
            modelSource != aiSettings.deepSeekModelSource ||
            manualModels != aiSettings.deepSeekManualModels ||
            adminUrl != aiSettings.deepSeekAdminUrl ||
            modelListUrl != aiSettings.deepSeekModelListUrl ||
            selectedParametersIds != aiSettings.deepSeekParametersIds ||
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
                deepSeekModelListUrl = modelListUrl,
                deepSeekParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.DEEPSEEK, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.DEEPSEEK.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.DEEPSEEK),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.mistralApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.mistralModel) }
    var modelSource by remember { mutableStateOf(aiSettings.mistralModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.mistralManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.mistralAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.mistralModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.mistralParametersIds) }

    // Default Mistral endpoints - Standard chat and Codestral for code generation
    val defaultMistralEndpoints = listOf(
        AiEndpoint(
            id = "mistral-chat-completions",
            name = "Chat Completions (Standard)",
            url = "https://api.mistral.ai/v1/chat/completions",
            isDefault = true
        ),
        AiEndpoint(
            id = "mistral-codestral",
            name = "Codestral (Code Generation)",
            url = "https://codestral.mistral.ai/v1/chat/completions",
            isDefault = false
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.MISTRAL).ifEmpty { defaultMistralEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.mistralApiKey ||
            defaultModel != aiSettings.mistralModel ||
            modelSource != aiSettings.mistralModelSource ||
            manualModels != aiSettings.mistralManualModels ||
            adminUrl != aiSettings.mistralAdminUrl ||
            modelListUrl != aiSettings.mistralModelListUrl ||
            selectedParametersIds != aiSettings.mistralParametersIds ||
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
                mistralModelListUrl = modelListUrl,
                mistralParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.MISTRAL, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.MISTRAL.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.MISTRAL),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.perplexityApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.perplexityModel) }
    var modelSource by remember { mutableStateOf(aiSettings.perplexityModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.perplexityManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.perplexityAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.perplexityModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.PERPLEXITY)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.perplexityParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.perplexityApiKey ||
            defaultModel != aiSettings.perplexityModel ||
            modelSource != aiSettings.perplexityModelSource ||
            manualModels != aiSettings.perplexityManualModels ||
            adminUrl != aiSettings.perplexityAdminUrl ||
            modelListUrl != aiSettings.perplexityModelListUrl ||
            selectedParametersIds != aiSettings.perplexityParametersIds ||
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
                perplexityModelListUrl = modelListUrl,
                perplexityParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.PERPLEXITY, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.PERPLEXITY.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.PERPLEXITY),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.togetherApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.togetherModel) }
    var modelSource by remember { mutableStateOf(aiSettings.togetherModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.togetherManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.togetherAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.togetherModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.TOGETHER)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.togetherParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.togetherApiKey ||
            defaultModel != aiSettings.togetherModel ||
            modelSource != aiSettings.togetherModelSource ||
            manualModels != aiSettings.togetherManualModels ||
            adminUrl != aiSettings.togetherAdminUrl ||
            modelListUrl != aiSettings.togetherModelListUrl ||
            selectedParametersIds != aiSettings.togetherParametersIds ||
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
                togetherModelListUrl = modelListUrl,
                togetherParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.TOGETHER, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.TOGETHER.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.TOGETHER),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.openRouterApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.openRouterModel) }
    var modelSource by remember { mutableStateOf(aiSettings.openRouterModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.openRouterManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.openRouterAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.openRouterModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.OPENROUTER)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.openRouterParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.openRouterApiKey ||
            defaultModel != aiSettings.openRouterModel ||
            modelSource != aiSettings.openRouterModelSource ||
            manualModels != aiSettings.openRouterManualModels ||
            adminUrl != aiSettings.openRouterAdminUrl ||
            modelListUrl != aiSettings.openRouterModelListUrl ||
            selectedParametersIds != aiSettings.openRouterParametersIds ||
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
                openRouterModelListUrl = modelListUrl,
                openRouterParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.OPENROUTER, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.OPENROUTER.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.OPENROUTER),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.siliconFlowApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.siliconFlowModel) }
    var modelSource by remember { mutableStateOf(aiSettings.siliconFlowModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.siliconFlowManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.siliconFlowAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.siliconFlowModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.siliconFlowParametersIds) }

    // Default SiliconFlow endpoints - Chat Completions (OpenAI) and Messages (Anthropic)
    val defaultSiliconFlowEndpoints = listOf(
        AiEndpoint(
            id = "siliconflow-chat-completions",
            name = "Chat Completions (OpenAI compatible)",
            url = "https://api.siliconflow.com/v1/chat/completions",
            isDefault = true
        ),
        AiEndpoint(
            id = "siliconflow-messages",
            name = "Messages (Anthropic compatible)",
            url = "https://api.siliconflow.com/messages",
            isDefault = false
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.SILICONFLOW).ifEmpty { defaultSiliconFlowEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.siliconFlowApiKey ||
            defaultModel != aiSettings.siliconFlowModel ||
            modelSource != aiSettings.siliconFlowModelSource ||
            manualModels != aiSettings.siliconFlowManualModels ||
            adminUrl != aiSettings.siliconFlowAdminUrl ||
            modelListUrl != aiSettings.siliconFlowModelListUrl ||
            selectedParametersIds != aiSettings.siliconFlowParametersIds ||
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
                siliconFlowModelListUrl = modelListUrl,
                siliconFlowParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.SILICONFLOW, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.SILICONFLOW.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.SILICONFLOW),
            onModelListUrlChange = { modelListUrl = it }
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
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.zaiApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.zaiModel) }
    var modelSource by remember { mutableStateOf(aiSettings.zaiModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.zaiManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.zaiAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.zaiModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.zaiParametersIds) }

    // Default Z.AI endpoints - General chat and Coding-specific
    val defaultZaiEndpoints = listOf(
        AiEndpoint(
            id = "zai-chat-completions",
            name = "Chat Completions (General)",
            url = "https://api.z.ai/api/paas/v4/chat/completions",
            isDefault = true
        ),
        AiEndpoint(
            id = "zai-coding",
            name = "Coding (GLM Coding Plan)",
            url = "https://api.z.ai/api/coding/paas/v4/chat/completions",
            isDefault = false
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.ZAI).ifEmpty { defaultZaiEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.zaiApiKey ||
            defaultModel != aiSettings.zaiModel ||
            modelSource != aiSettings.zaiModelSource ||
            manualModels != aiSettings.zaiManualModels ||
            adminUrl != aiSettings.zaiAdminUrl ||
            modelListUrl != aiSettings.zaiModelListUrl ||
            selectedParametersIds != aiSettings.zaiParametersIds ||
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
                zaiModelListUrl = modelListUrl,
                zaiParametersIds = selectedParametersIds
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
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.ZAI, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.ZAI.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.ZAI),
            onModelListUrlChange = { modelListUrl = it }
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
 * Moonshot settings screen (Kimi models).
 */
@Composable
fun MoonshotSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.moonshotApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.moonshotModel) }
    var modelSource by remember { mutableStateOf(aiSettings.moonshotModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.moonshotManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.moonshotAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.moonshotModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.moonshotParametersIds) }

    val defaultMoonshotEndpoints = listOf(
        AiEndpoint(
            id = "moonshot-chat-completions",
            name = "Chat Completions",
            url = "https://api.moonshot.cn/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.MOONSHOT).ifEmpty { defaultMoonshotEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.moonshotApiKey ||
            defaultModel != aiSettings.moonshotModel ||
            modelSource != aiSettings.moonshotModelSource ||
            manualModels != aiSettings.moonshotManualModels ||
            adminUrl != aiSettings.moonshotAdminUrl ||
            modelListUrl != aiSettings.moonshotModelListUrl ||
            selectedParametersIds != aiSettings.moonshotParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.MOONSHOT)

    AiServiceSettingsScreenTemplate(
        title = "Moonshot",
        accentColor = Color(0xFF7C3AED),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                moonshotApiKey = apiKey,
                moonshotModel = defaultModel,
                moonshotModelSource = modelSource,
                moonshotManualModels = manualModels,
                moonshotAdminUrl = adminUrl,
                moonshotModelListUrl = modelListUrl,
                moonshotParametersIds = selectedParametersIds
            ).withEndpoints(AiService.MOONSHOT, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.MOONSHOT, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.MOONSHOT, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.MOONSHOT.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.MOONSHOT),
            onModelListUrlChange = { modelListUrl = it }
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
 * Cohere settings screen.
 */
@Composable
fun CohereSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.cohereApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.cohereModel) }
    var modelSource by remember { mutableStateOf(aiSettings.cohereModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.cohereManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.cohereAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.cohereModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.cohereParametersIds) }

    val defaultCohereEndpoints = listOf(
        AiEndpoint(
            id = "cohere-chat-completions",
            name = "Chat Completions",
            url = "https://api.cohere.ai/compatibility/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.COHERE).ifEmpty { defaultCohereEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.cohereApiKey ||
            defaultModel != aiSettings.cohereModel ||
            modelSource != aiSettings.cohereModelSource ||
            manualModels != aiSettings.cohereManualModels ||
            adminUrl != aiSettings.cohereAdminUrl ||
            modelListUrl != aiSettings.cohereModelListUrl ||
            selectedParametersIds != aiSettings.cohereParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.COHERE)

    AiServiceSettingsScreenTemplate(
        title = "Cohere",
        accentColor = Color(0xFF39594D),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                cohereApiKey = apiKey,
                cohereModel = defaultModel,
                cohereModelSource = modelSource,
                cohereManualModels = manualModels,
                cohereAdminUrl = adminUrl,
                cohereModelListUrl = modelListUrl,
                cohereParametersIds = selectedParametersIds
            ).withEndpoints(AiService.COHERE, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.COHERE, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.COHERE, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.COHERE.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.COHERE),
            onModelListUrlChange = { modelListUrl = it }
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
 * AI21 settings screen.
 */
@Composable
fun Ai21SettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.ai21ApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.ai21Model) }
    var modelSource by remember { mutableStateOf(aiSettings.ai21ModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.ai21ManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.ai21AdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.ai21ModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.ai21ParametersIds) }

    val defaultAi21Endpoints = listOf(
        AiEndpoint(
            id = "ai21-chat-completions",
            name = "Chat Completions",
            url = "https://api.ai21.com/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.AI21).ifEmpty { defaultAi21Endpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.ai21ApiKey ||
            defaultModel != aiSettings.ai21Model ||
            modelSource != aiSettings.ai21ModelSource ||
            manualModels != aiSettings.ai21ManualModels ||
            adminUrl != aiSettings.ai21AdminUrl ||
            modelListUrl != aiSettings.ai21ModelListUrl ||
            selectedParametersIds != aiSettings.ai21ParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.AI21)

    AiServiceSettingsScreenTemplate(
        title = "AI21",
        accentColor = Color(0xFFFF6F00),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                ai21ApiKey = apiKey,
                ai21Model = defaultModel,
                ai21ModelSource = modelSource,
                ai21ManualModels = manualModels,
                ai21AdminUrl = adminUrl,
                ai21ModelListUrl = modelListUrl,
                ai21ParametersIds = selectedParametersIds
            ).withEndpoints(AiService.AI21, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.AI21, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.AI21, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.AI21.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.AI21),
            onModelListUrlChange = { modelListUrl = it }
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
 * DashScope settings screen.
 */
@Composable
fun DashScopeSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.dashScopeApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.dashScopeModel) }
    var modelSource by remember { mutableStateOf(aiSettings.dashScopeModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.dashScopeManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.dashScopeAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.dashScopeModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.dashScopeParametersIds) }

    val defaultDashScopeEndpoints = listOf(
        AiEndpoint(
            id = "dashscope-chat-completions",
            name = "Chat Completions",
            url = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.DASHSCOPE).ifEmpty { defaultDashScopeEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.dashScopeApiKey ||
            defaultModel != aiSettings.dashScopeModel ||
            modelSource != aiSettings.dashScopeModelSource ||
            manualModels != aiSettings.dashScopeManualModels ||
            adminUrl != aiSettings.dashScopeAdminUrl ||
            modelListUrl != aiSettings.dashScopeModelListUrl ||
            selectedParametersIds != aiSettings.dashScopeParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.DASHSCOPE)

    AiServiceSettingsScreenTemplate(
        title = "DashScope",
        accentColor = Color(0xFFFF6A00),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                dashScopeApiKey = apiKey,
                dashScopeModel = defaultModel,
                dashScopeModelSource = modelSource,
                dashScopeManualModels = manualModels,
                dashScopeAdminUrl = adminUrl,
                dashScopeModelListUrl = modelListUrl,
                dashScopeParametersIds = selectedParametersIds
            ).withEndpoints(AiService.DASHSCOPE, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.DASHSCOPE, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.DASHSCOPE, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.DASHSCOPE.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.DASHSCOPE),
            onModelListUrlChange = { modelListUrl = it }
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
 * Fireworks settings screen.
 */
@Composable
fun FireworksSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.fireworksApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.fireworksModel) }
    var modelSource by remember { mutableStateOf(aiSettings.fireworksModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.fireworksManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.fireworksAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.fireworksModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.fireworksParametersIds) }

    val defaultFireworksEndpoints = listOf(
        AiEndpoint(
            id = "fireworks-chat-completions",
            name = "Chat Completions",
            url = "https://api.fireworks.ai/inference/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.FIREWORKS).ifEmpty { defaultFireworksEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.fireworksApiKey ||
            defaultModel != aiSettings.fireworksModel ||
            modelSource != aiSettings.fireworksModelSource ||
            manualModels != aiSettings.fireworksManualModels ||
            adminUrl != aiSettings.fireworksAdminUrl ||
            modelListUrl != aiSettings.fireworksModelListUrl ||
            selectedParametersIds != aiSettings.fireworksParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.FIREWORKS)

    AiServiceSettingsScreenTemplate(
        title = "Fireworks",
        accentColor = Color(0xFFE34234),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                fireworksApiKey = apiKey,
                fireworksModel = defaultModel,
                fireworksModelSource = modelSource,
                fireworksManualModels = manualModels,
                fireworksAdminUrl = adminUrl,
                fireworksModelListUrl = modelListUrl,
                fireworksParametersIds = selectedParametersIds
            ).withEndpoints(AiService.FIREWORKS, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.FIREWORKS, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.FIREWORKS, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.FIREWORKS.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.FIREWORKS),
            onModelListUrlChange = { modelListUrl = it }
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
 * Cerebras settings screen.
 */
@Composable
fun CerebrasSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.cerebrasApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.cerebrasModel) }
    var modelSource by remember { mutableStateOf(aiSettings.cerebrasModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.cerebrasManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.cerebrasAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.cerebrasModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.cerebrasParametersIds) }

    val defaultCerebrasEndpoints = listOf(
        AiEndpoint(
            id = "cerebras-chat-completions",
            name = "Chat Completions",
            url = "https://api.cerebras.ai/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.CEREBRAS).ifEmpty { defaultCerebrasEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.cerebrasApiKey ||
            defaultModel != aiSettings.cerebrasModel ||
            modelSource != aiSettings.cerebrasModelSource ||
            manualModels != aiSettings.cerebrasManualModels ||
            adminUrl != aiSettings.cerebrasAdminUrl ||
            modelListUrl != aiSettings.cerebrasModelListUrl ||
            selectedParametersIds != aiSettings.cerebrasParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.CEREBRAS)

    AiServiceSettingsScreenTemplate(
        title = "Cerebras",
        accentColor = Color(0xFF00A3E0),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                cerebrasApiKey = apiKey,
                cerebrasModel = defaultModel,
                cerebrasModelSource = modelSource,
                cerebrasManualModels = manualModels,
                cerebrasAdminUrl = adminUrl,
                cerebrasModelListUrl = modelListUrl,
                cerebrasParametersIds = selectedParametersIds
            ).withEndpoints(AiService.CEREBRAS, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.CEREBRAS, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.CEREBRAS, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.CEREBRAS.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.CEREBRAS),
            onModelListUrlChange = { modelListUrl = it }
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
 * SambaNova settings screen.
 */
@Composable
fun SambaNovaSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.sambaNovaApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.sambaNovaModel) }
    var modelSource by remember { mutableStateOf(aiSettings.sambaNovaModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.sambaNovaManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.sambaNovaAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.sambaNovaModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.sambaNovaParametersIds) }

    val defaultSambaNovaEndpoints = listOf(
        AiEndpoint(
            id = "sambanova-chat-completions",
            name = "Chat Completions",
            url = "https://api.sambanova.ai/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.SAMBANOVA).ifEmpty { defaultSambaNovaEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.sambaNovaApiKey ||
            defaultModel != aiSettings.sambaNovaModel ||
            modelSource != aiSettings.sambaNovaModelSource ||
            manualModels != aiSettings.sambaNovaManualModels ||
            adminUrl != aiSettings.sambaNovaAdminUrl ||
            modelListUrl != aiSettings.sambaNovaModelListUrl ||
            selectedParametersIds != aiSettings.sambaNovaParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.SAMBANOVA)

    AiServiceSettingsScreenTemplate(
        title = "SambaNova",
        accentColor = Color(0xFF6B21A8),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                sambaNovaApiKey = apiKey,
                sambaNovaModel = defaultModel,
                sambaNovaModelSource = modelSource,
                sambaNovaManualModels = manualModels,
                sambaNovaAdminUrl = adminUrl,
                sambaNovaModelListUrl = modelListUrl,
                sambaNovaParametersIds = selectedParametersIds
            ).withEndpoints(AiService.SAMBANOVA, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.SAMBANOVA, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.SAMBANOVA, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.SAMBANOVA.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.SAMBANOVA),
            onModelListUrlChange = { modelListUrl = it }
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
 * Baichuan settings screen.
 */
@Composable
fun BaichuanSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.baichuanApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.baichuanModel) }
    var modelSource by remember { mutableStateOf(aiSettings.baichuanModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.baichuanManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.baichuanAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.baichuanModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.baichuanParametersIds) }

    val defaultBaichuanEndpoints = listOf(
        AiEndpoint(
            id = "baichuan-chat-completions",
            name = "Chat Completions",
            url = "https://api.baichuan-ai.com/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.BAICHUAN).ifEmpty { defaultBaichuanEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.baichuanApiKey ||
            defaultModel != aiSettings.baichuanModel ||
            modelSource != aiSettings.baichuanModelSource ||
            manualModels != aiSettings.baichuanManualModels ||
            adminUrl != aiSettings.baichuanAdminUrl ||
            modelListUrl != aiSettings.baichuanModelListUrl ||
            selectedParametersIds != aiSettings.baichuanParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.BAICHUAN)

    AiServiceSettingsScreenTemplate(
        title = "Baichuan",
        accentColor = Color(0xFF1E88E5),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                baichuanApiKey = apiKey,
                baichuanModel = defaultModel,
                baichuanModelSource = modelSource,
                baichuanManualModels = manualModels,
                baichuanAdminUrl = adminUrl,
                baichuanModelListUrl = modelListUrl,
                baichuanParametersIds = selectedParametersIds
            ).withEndpoints(AiService.BAICHUAN, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.BAICHUAN, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.BAICHUAN, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.BAICHUAN.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.BAICHUAN),
            onModelListUrlChange = { modelListUrl = it }
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
 * StepFun settings screen.
 */
@Composable
fun StepFunSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.stepFunApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.stepFunModel) }
    var modelSource by remember { mutableStateOf(aiSettings.stepFunModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.stepFunManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.stepFunAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.stepFunModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.stepFunParametersIds) }

    val defaultStepFunEndpoints = listOf(
        AiEndpoint(
            id = "stepfun-chat-completions",
            name = "Chat Completions",
            url = "https://api.stepfun.com/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.STEPFUN).ifEmpty { defaultStepFunEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.stepFunApiKey ||
            defaultModel != aiSettings.stepFunModel ||
            modelSource != aiSettings.stepFunModelSource ||
            manualModels != aiSettings.stepFunManualModels ||
            adminUrl != aiSettings.stepFunAdminUrl ||
            modelListUrl != aiSettings.stepFunModelListUrl ||
            selectedParametersIds != aiSettings.stepFunParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.STEPFUN)

    AiServiceSettingsScreenTemplate(
        title = "StepFun",
        accentColor = Color(0xFF00BFA5),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                stepFunApiKey = apiKey,
                stepFunModel = defaultModel,
                stepFunModelSource = modelSource,
                stepFunManualModels = manualModels,
                stepFunAdminUrl = adminUrl,
                stepFunModelListUrl = modelListUrl,
                stepFunParametersIds = selectedParametersIds
            ).withEndpoints(AiService.STEPFUN, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.STEPFUN, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.STEPFUN, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.STEPFUN.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.STEPFUN),
            onModelListUrlChange = { modelListUrl = it }
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
 * MiniMax settings screen.
 */
@Composable
fun MiniMaxSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onFetchModels: (String) -> Unit,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.miniMaxApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.miniMaxModel) }
    var modelSource by remember { mutableStateOf(aiSettings.miniMaxModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.miniMaxManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.miniMaxAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.miniMaxModelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.miniMaxParametersIds) }

    val defaultMiniMaxEndpoints = listOf(
        AiEndpoint(
            id = "minimax-chat-completions",
            name = "Chat Completions",
            url = "https://api.minimax.io/v1/chat/completions",
            isDefault = true
        )
    )
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(AiService.MINIMAX).ifEmpty { defaultMiniMaxEndpoints }
        )
    }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.miniMaxApiKey ||
            defaultModel != aiSettings.miniMaxModel ||
            modelSource != aiSettings.miniMaxModelSource ||
            manualModels != aiSettings.miniMaxManualModels ||
            adminUrl != aiSettings.miniMaxAdminUrl ||
            modelListUrl != aiSettings.miniMaxModelListUrl ||
            selectedParametersIds != aiSettings.miniMaxParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.MINIMAX)

    AiServiceSettingsScreenTemplate(
        title = "MiniMax",
        accentColor = Color(0xFFEC407A),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                miniMaxApiKey = apiKey,
                miniMaxModel = defaultModel,
                miniMaxModelSource = modelSource,
                miniMaxManualModels = manualModels,
                miniMaxAdminUrl = adminUrl,
                miniMaxModelListUrl = modelListUrl,
                miniMaxParametersIds = selectedParametersIds
            ).withEndpoints(AiService.MINIMAX, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.MINIMAX, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.MINIMAX, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.MINIMAX.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.MINIMAX),
            onModelListUrlChange = { modelListUrl = it }
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
 * NVIDIA settings screen.
 */
@Composable
fun NvidiaSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.nvidiaApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.nvidiaModel) }
    var modelSource by remember { mutableStateOf(aiSettings.nvidiaModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.nvidiaManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.nvidiaAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.nvidiaModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.NVIDIA)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.nvidiaParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.nvidiaApiKey ||
            defaultModel != aiSettings.nvidiaModel ||
            modelSource != aiSettings.nvidiaModelSource ||
            manualModels != aiSettings.nvidiaManualModels ||
            adminUrl != aiSettings.nvidiaAdminUrl ||
            modelListUrl != aiSettings.nvidiaModelListUrl ||
            selectedParametersIds != aiSettings.nvidiaParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.NVIDIA)

    LaunchedEffect(Unit) {
        if (aiSettings.nvidiaApiKey.isNotBlank() && aiSettings.nvidiaModelSource == ModelSource.API) {
            onFetchModels(aiSettings.nvidiaApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "NVIDIA",
        accentColor = Color(0xFF76B900),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                nvidiaApiKey = apiKey,
                nvidiaModel = defaultModel,
                nvidiaModelSource = modelSource,
                nvidiaManualModels = manualModels,
                nvidiaAdminUrl = adminUrl,
                nvidiaModelListUrl = modelListUrl,
                nvidiaParametersIds = selectedParametersIds
            ).withEndpoints(AiService.NVIDIA, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.NVIDIA, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.NVIDIA, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.NVIDIA.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.NVIDIA),
            onModelListUrlChange = { modelListUrl = it }
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
 * Replicate settings screen.
 */
@Composable
fun ReplicateSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.replicateApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.replicateModel) }
    var modelSource by remember { mutableStateOf(aiSettings.replicateModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.replicateManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.replicateAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.replicateModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.REPLICATE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.replicateParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.replicateApiKey ||
            defaultModel != aiSettings.replicateModel ||
            modelSource != aiSettings.replicateModelSource ||
            manualModels != aiSettings.replicateManualModels ||
            adminUrl != aiSettings.replicateAdminUrl ||
            modelListUrl != aiSettings.replicateModelListUrl ||
            selectedParametersIds != aiSettings.replicateParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.REPLICATE)

    LaunchedEffect(Unit) {
        if (aiSettings.replicateApiKey.isNotBlank() && aiSettings.replicateModelSource == ModelSource.API) {
            onFetchModels(aiSettings.replicateApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Replicate",
        accentColor = Color(0xFF000000),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                replicateApiKey = apiKey,
                replicateModel = defaultModel,
                replicateModelSource = modelSource,
                replicateManualModels = manualModels,
                replicateAdminUrl = adminUrl,
                replicateModelListUrl = modelListUrl,
                replicateParametersIds = selectedParametersIds
            ).withEndpoints(AiService.REPLICATE, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.REPLICATE, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.REPLICATE, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.REPLICATE.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.REPLICATE),
            onModelListUrlChange = { modelListUrl = it }
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
 * Hugging Face Inference settings screen.
 */
@Composable
fun HuggingFaceInferenceSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.huggingFaceInferenceApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.huggingFaceInferenceModel) }
    var modelSource by remember { mutableStateOf(aiSettings.huggingFaceInferenceModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.huggingFaceInferenceManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.huggingFaceInferenceAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.huggingFaceInferenceModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.HUGGINGFACE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.huggingFaceInferenceParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.huggingFaceInferenceApiKey ||
            defaultModel != aiSettings.huggingFaceInferenceModel ||
            modelSource != aiSettings.huggingFaceInferenceModelSource ||
            manualModels != aiSettings.huggingFaceInferenceManualModels ||
            adminUrl != aiSettings.huggingFaceInferenceAdminUrl ||
            modelListUrl != aiSettings.huggingFaceInferenceModelListUrl ||
            selectedParametersIds != aiSettings.huggingFaceInferenceParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.HUGGINGFACE)

    LaunchedEffect(Unit) {
        if (aiSettings.huggingFaceInferenceApiKey.isNotBlank() && aiSettings.huggingFaceInferenceModelSource == ModelSource.API) {
            onFetchModels(aiSettings.huggingFaceInferenceApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Hugging Face",
        accentColor = Color(0xFFFFD21E),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                huggingFaceInferenceApiKey = apiKey,
                huggingFaceInferenceModel = defaultModel,
                huggingFaceInferenceModelSource = modelSource,
                huggingFaceInferenceManualModels = manualModels,
                huggingFaceInferenceAdminUrl = adminUrl,
                huggingFaceInferenceModelListUrl = modelListUrl,
                huggingFaceInferenceParametersIds = selectedParametersIds
            ).withEndpoints(AiService.HUGGINGFACE, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.HUGGINGFACE, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.HUGGINGFACE, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.HUGGINGFACE.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.HUGGINGFACE),
            onModelListUrlChange = { modelListUrl = it }
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
 * Lambda settings screen.
 */
@Composable
fun LambdaSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.lambdaApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.lambdaModel) }
    var modelSource by remember { mutableStateOf(aiSettings.lambdaModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.lambdaManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.lambdaAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.lambdaModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.LAMBDA)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.lambdaParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.lambdaApiKey ||
            defaultModel != aiSettings.lambdaModel ||
            modelSource != aiSettings.lambdaModelSource ||
            manualModels != aiSettings.lambdaManualModels ||
            adminUrl != aiSettings.lambdaAdminUrl ||
            modelListUrl != aiSettings.lambdaModelListUrl ||
            selectedParametersIds != aiSettings.lambdaParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.LAMBDA)

    LaunchedEffect(Unit) {
        if (aiSettings.lambdaApiKey.isNotBlank() && aiSettings.lambdaModelSource == ModelSource.API) {
            onFetchModels(aiSettings.lambdaApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Lambda",
        accentColor = Color(0xFF1F41BF),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                lambdaApiKey = apiKey,
                lambdaModel = defaultModel,
                lambdaModelSource = modelSource,
                lambdaManualModels = manualModels,
                lambdaAdminUrl = adminUrl,
                lambdaModelListUrl = modelListUrl,
                lambdaParametersIds = selectedParametersIds
            ).withEndpoints(AiService.LAMBDA, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.LAMBDA, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.LAMBDA, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.LAMBDA.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.LAMBDA),
            onModelListUrlChange = { modelListUrl = it }
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
 * Lepton settings screen.
 */
@Composable
fun LeptonSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.leptonApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.leptonModel) }
    var modelSource by remember { mutableStateOf(aiSettings.leptonModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.leptonManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.leptonAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.leptonModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.LEPTON)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.leptonParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.leptonApiKey ||
            defaultModel != aiSettings.leptonModel ||
            modelSource != aiSettings.leptonModelSource ||
            manualModels != aiSettings.leptonManualModels ||
            adminUrl != aiSettings.leptonAdminUrl ||
            modelListUrl != aiSettings.leptonModelListUrl ||
            selectedParametersIds != aiSettings.leptonParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.LEPTON)

    LaunchedEffect(Unit) {
        if (aiSettings.leptonApiKey.isNotBlank() && aiSettings.leptonModelSource == ModelSource.API) {
            onFetchModels(aiSettings.leptonApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Lepton",
        accentColor = Color(0xFF3B82F6),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                leptonApiKey = apiKey,
                leptonModel = defaultModel,
                leptonModelSource = modelSource,
                leptonManualModels = manualModels,
                leptonAdminUrl = adminUrl,
                leptonModelListUrl = modelListUrl,
                leptonParametersIds = selectedParametersIds
            ).withEndpoints(AiService.LEPTON, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.LEPTON, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.LEPTON, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.LEPTON.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.LEPTON),
            onModelListUrlChange = { modelListUrl = it }
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
 * 01.AI (Yi) settings screen.
 */
@Composable
fun YiSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.yiApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.yiModel) }
    var modelSource by remember { mutableStateOf(aiSettings.yiModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.yiManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.yiAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.yiModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.YI)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.yiParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.yiApiKey ||
            defaultModel != aiSettings.yiModel ||
            modelSource != aiSettings.yiModelSource ||
            manualModels != aiSettings.yiManualModels ||
            adminUrl != aiSettings.yiAdminUrl ||
            modelListUrl != aiSettings.yiModelListUrl ||
            selectedParametersIds != aiSettings.yiParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.YI)

    LaunchedEffect(Unit) {
        if (aiSettings.yiApiKey.isNotBlank() && aiSettings.yiModelSource == ModelSource.API) {
            onFetchModels(aiSettings.yiApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "01.AI",
        accentColor = Color(0xFFFFB81C),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                yiApiKey = apiKey,
                yiModel = defaultModel,
                yiModelSource = modelSource,
                yiManualModels = manualModels,
                yiAdminUrl = adminUrl,
                yiModelListUrl = modelListUrl,
                yiParametersIds = selectedParametersIds
            ).withEndpoints(AiService.YI, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.YI, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.YI, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.YI.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.YI),
            onModelListUrlChange = { modelListUrl = it }
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
 * Doubao settings screen.
 */
@Composable
fun DoubaoSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.doubaoApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.doubaoModel) }
    var modelSource by remember { mutableStateOf(aiSettings.doubaoModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.doubaoManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.doubaoAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.doubaoModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.DOUBAO)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.doubaoParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.doubaoApiKey ||
            defaultModel != aiSettings.doubaoModel ||
            modelSource != aiSettings.doubaoModelSource ||
            manualModels != aiSettings.doubaoManualModels ||
            adminUrl != aiSettings.doubaoAdminUrl ||
            modelListUrl != aiSettings.doubaoModelListUrl ||
            selectedParametersIds != aiSettings.doubaoParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.DOUBAO)

    LaunchedEffect(Unit) {
        if (aiSettings.doubaoApiKey.isNotBlank() && aiSettings.doubaoModelSource == ModelSource.API) {
            onFetchModels(aiSettings.doubaoApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Doubao",
        accentColor = Color(0xFF1890FF),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                doubaoApiKey = apiKey,
                doubaoModel = defaultModel,
                doubaoModelSource = modelSource,
                doubaoManualModels = manualModels,
                doubaoAdminUrl = adminUrl,
                doubaoModelListUrl = modelListUrl,
                doubaoParametersIds = selectedParametersIds
            ).withEndpoints(AiService.DOUBAO, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.DOUBAO, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.DOUBAO, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.DOUBAO.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.DOUBAO),
            onModelListUrlChange = { modelListUrl = it }
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
 * Reka settings screen.
 */
@Composable
fun RekaSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.rekaApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.rekaModel) }
    var modelSource by remember { mutableStateOf(aiSettings.rekaModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.rekaManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.rekaAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.rekaModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.REKA)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.rekaParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.rekaApiKey ||
            defaultModel != aiSettings.rekaModel ||
            modelSource != aiSettings.rekaModelSource ||
            manualModels != aiSettings.rekaManualModels ||
            adminUrl != aiSettings.rekaAdminUrl ||
            modelListUrl != aiSettings.rekaModelListUrl ||
            selectedParametersIds != aiSettings.rekaParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.REKA)

    LaunchedEffect(Unit) {
        if (aiSettings.rekaApiKey.isNotBlank() && aiSettings.rekaModelSource == ModelSource.API) {
            onFetchModels(aiSettings.rekaApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Reka",
        accentColor = Color(0xFFFF6B35),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                rekaApiKey = apiKey,
                rekaModel = defaultModel,
                rekaModelSource = modelSource,
                rekaManualModels = manualModels,
                rekaAdminUrl = adminUrl,
                rekaModelListUrl = modelListUrl,
                rekaParametersIds = selectedParametersIds
            ).withEndpoints(AiService.REKA, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.REKA, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.REKA, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.REKA.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.REKA),
            onModelListUrlChange = { modelListUrl = it }
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
 * Writer settings screen.
 */
@Composable
fun WriterSettingsScreen(
    aiSettings: AiSettings,
    availableModels: List<String>,
    isLoadingModels: Boolean,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit,
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.writerApiKey) }
    var defaultModel by remember { mutableStateOf(aiSettings.writerModel) }
    var modelSource by remember { mutableStateOf(aiSettings.writerModelSource) }
    var manualModels by remember { mutableStateOf(aiSettings.writerManualModels) }
    var adminUrl by remember { mutableStateOf(aiSettings.writerAdminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.writerModelListUrl) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.WRITER)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.writerParametersIds) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.writerApiKey ||
            defaultModel != aiSettings.writerModel ||
            modelSource != aiSettings.writerModelSource ||
            manualModels != aiSettings.writerManualModels ||
            adminUrl != aiSettings.writerAdminUrl ||
            modelListUrl != aiSettings.writerModelListUrl ||
            selectedParametersIds != aiSettings.writerParametersIds ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.WRITER)

    LaunchedEffect(Unit) {
        if (aiSettings.writerApiKey.isNotBlank() && aiSettings.writerModelSource == ModelSource.API) {
            onFetchModels(aiSettings.writerApiKey)
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Writer",
        accentColor = Color(0xFF0066FF),
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.copy(
                writerApiKey = apiKey,
                writerModel = defaultModel,
                writerModelSource = modelSource,
                writerManualModels = manualModels,
                writerAdminUrl = adminUrl,
                writerModelListUrl = modelListUrl,
                writerParametersIds = selectedParametersIds
            ).withEndpoints(AiService.WRITER, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(AiService.WRITER, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(AiService.WRITER, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = AiService.WRITER.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(AiService.WRITER),
            onModelListUrlChange = { modelListUrl = it }
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
