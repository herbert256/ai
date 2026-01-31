package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.OPENAI)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.OPENAI)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.OPENAI)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.OPENAI)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.OPENAI).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.OPENAI)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.OPENAI)) }

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
    val hasChanges = apiKey != aiSettings.getApiKey(AiService.OPENAI) ||
            defaultModel != aiSettings.getModel(AiService.OPENAI) ||
            modelSource != aiSettings.getModelSource(AiService.OPENAI) ||
            manualModels != aiSettings.getManualModels(AiService.OPENAI) ||
            adminUrl != aiSettings.getProvider(AiService.OPENAI).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.OPENAI) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.OPENAI) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.OPENAI)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.OPENAI).isNotBlank() && aiSettings.getModelSource(AiService.OPENAI) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.OPENAI))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "OpenAI",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.OPENAI, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.OPENAI, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.ANTHROPIC)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.ANTHROPIC)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.ANTHROPIC)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.ANTHROPIC)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.ANTHROPIC).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.ANTHROPIC)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.ANTHROPIC)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.ANTHROPIC)) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.ANTHROPIC) ||
            defaultModel != aiSettings.getModel(AiService.ANTHROPIC) ||
            modelSource != aiSettings.getModelSource(AiService.ANTHROPIC) ||
            manualModels != aiSettings.getManualModels(AiService.ANTHROPIC) ||
            adminUrl != aiSettings.getProvider(AiService.ANTHROPIC).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.ANTHROPIC) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.ANTHROPIC) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.ANTHROPIC)

    AiServiceSettingsScreenTemplate(
        title = "Anthropic",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.ANTHROPIC, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.ANTHROPIC, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.GOOGLE)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.GOOGLE)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.GOOGLE)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.GOOGLE)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.GOOGLE).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.GOOGLE)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.GOOGLE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.GOOGLE)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.GOOGLE) ||
            defaultModel != aiSettings.getModel(AiService.GOOGLE) ||
            modelSource != aiSettings.getModelSource(AiService.GOOGLE) ||
            manualModels != aiSettings.getManualModels(AiService.GOOGLE) ||
            adminUrl != aiSettings.getProvider(AiService.GOOGLE).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.GOOGLE) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.GOOGLE) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.GOOGLE)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.GOOGLE).isNotBlank() && aiSettings.getModelSource(AiService.GOOGLE) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.GOOGLE))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Google",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.GOOGLE, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.GOOGLE, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.XAI)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.XAI)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.XAI)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.XAI)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.XAI).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.XAI)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.XAI)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.XAI)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.XAI) ||
            defaultModel != aiSettings.getModel(AiService.XAI) ||
            modelSource != aiSettings.getModelSource(AiService.XAI) ||
            manualModels != aiSettings.getManualModels(AiService.XAI) ||
            adminUrl != aiSettings.getProvider(AiService.XAI).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.XAI) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.XAI) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.XAI)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.XAI).isNotBlank() && aiSettings.getModelSource(AiService.XAI) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.XAI))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "xAI",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.XAI, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.XAI, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.GROQ)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.GROQ)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.GROQ)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.GROQ)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.GROQ).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.GROQ)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.GROQ)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.GROQ)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.GROQ) ||
            defaultModel != aiSettings.getModel(AiService.GROQ) ||
            modelSource != aiSettings.getModelSource(AiService.GROQ) ||
            manualModels != aiSettings.getManualModels(AiService.GROQ) ||
            adminUrl != aiSettings.getProvider(AiService.GROQ).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.GROQ) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.GROQ) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.GROQ)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.GROQ).isNotBlank() && aiSettings.getModelSource(AiService.GROQ) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.GROQ))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Groq",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.GROQ, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.GROQ, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.DEEPSEEK)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.DEEPSEEK)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.DEEPSEEK)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.DEEPSEEK)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.DEEPSEEK).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.DEEPSEEK)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.DEEPSEEK)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.DEEPSEEK) ||
            defaultModel != aiSettings.getModel(AiService.DEEPSEEK) ||
            modelSource != aiSettings.getModelSource(AiService.DEEPSEEK) ||
            manualModels != aiSettings.getManualModels(AiService.DEEPSEEK) ||
            adminUrl != aiSettings.getProvider(AiService.DEEPSEEK).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.DEEPSEEK) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.DEEPSEEK) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.DEEPSEEK)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.DEEPSEEK).isNotBlank() && aiSettings.getModelSource(AiService.DEEPSEEK) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.DEEPSEEK))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "DeepSeek",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.DEEPSEEK, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.DEEPSEEK, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.MISTRAL)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.MISTRAL)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.MISTRAL)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.MISTRAL)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.MISTRAL).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.MISTRAL)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.MISTRAL)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.MISTRAL) ||
            defaultModel != aiSettings.getModel(AiService.MISTRAL) ||
            modelSource != aiSettings.getModelSource(AiService.MISTRAL) ||
            manualModels != aiSettings.getManualModels(AiService.MISTRAL) ||
            adminUrl != aiSettings.getProvider(AiService.MISTRAL).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.MISTRAL) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.MISTRAL) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.MISTRAL)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.MISTRAL).isNotBlank() && aiSettings.getModelSource(AiService.MISTRAL) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.MISTRAL))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Mistral",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.MISTRAL, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.MISTRAL, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.PERPLEXITY)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.PERPLEXITY)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.PERPLEXITY)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.PERPLEXITY)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.PERPLEXITY).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.PERPLEXITY)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.PERPLEXITY)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.PERPLEXITY)) }

    val effectiveModels = if (modelSource == ModelSource.API) emptyList() else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.PERPLEXITY) ||
            defaultModel != aiSettings.getModel(AiService.PERPLEXITY) ||
            modelSource != aiSettings.getModelSource(AiService.PERPLEXITY) ||
            manualModels != aiSettings.getManualModels(AiService.PERPLEXITY) ||
            adminUrl != aiSettings.getProvider(AiService.PERPLEXITY).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.PERPLEXITY) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.PERPLEXITY) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.PERPLEXITY)

    AiServiceSettingsScreenTemplate(
        title = "Perplexity",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.PERPLEXITY, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.PERPLEXITY, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.TOGETHER)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.TOGETHER)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.TOGETHER)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.TOGETHER)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.TOGETHER).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.TOGETHER)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.TOGETHER)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.TOGETHER)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.TOGETHER) ||
            defaultModel != aiSettings.getModel(AiService.TOGETHER) ||
            modelSource != aiSettings.getModelSource(AiService.TOGETHER) ||
            manualModels != aiSettings.getManualModels(AiService.TOGETHER) ||
            adminUrl != aiSettings.getProvider(AiService.TOGETHER).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.TOGETHER) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.TOGETHER) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.TOGETHER)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.TOGETHER).isNotBlank() && aiSettings.getModelSource(AiService.TOGETHER) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.TOGETHER))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Together",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.TOGETHER, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.TOGETHER, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.OPENROUTER)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.OPENROUTER)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.OPENROUTER)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.OPENROUTER)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.OPENROUTER).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.OPENROUTER)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.OPENROUTER)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.OPENROUTER)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.OPENROUTER) ||
            defaultModel != aiSettings.getModel(AiService.OPENROUTER) ||
            modelSource != aiSettings.getModelSource(AiService.OPENROUTER) ||
            manualModels != aiSettings.getManualModels(AiService.OPENROUTER) ||
            adminUrl != aiSettings.getProvider(AiService.OPENROUTER).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.OPENROUTER) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.OPENROUTER) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.OPENROUTER)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.OPENROUTER).isNotBlank() && aiSettings.getModelSource(AiService.OPENROUTER) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.OPENROUTER))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "OpenRouter",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.OPENROUTER, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.OPENROUTER, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.SILICONFLOW)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.SILICONFLOW)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.SILICONFLOW)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.SILICONFLOW)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.SILICONFLOW).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.SILICONFLOW)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.SILICONFLOW)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.SILICONFLOW) ||
            defaultModel != aiSettings.getModel(AiService.SILICONFLOW) ||
            modelSource != aiSettings.getModelSource(AiService.SILICONFLOW) ||
            manualModels != aiSettings.getManualModels(AiService.SILICONFLOW) ||
            adminUrl != aiSettings.getProvider(AiService.SILICONFLOW).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.SILICONFLOW) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.SILICONFLOW) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.SILICONFLOW)

    AiServiceSettingsScreenTemplate(
        title = "SiliconFlow",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.SILICONFLOW, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.SILICONFLOW, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.ZAI)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.ZAI)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.ZAI)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.ZAI)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.ZAI).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.ZAI)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.ZAI)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.ZAI) ||
            defaultModel != aiSettings.getModel(AiService.ZAI) ||
            modelSource != aiSettings.getModelSource(AiService.ZAI) ||
            manualModels != aiSettings.getManualModels(AiService.ZAI) ||
            adminUrl != aiSettings.getProvider(AiService.ZAI).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.ZAI) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.ZAI) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.ZAI)

    AiServiceSettingsScreenTemplate(
        title = "Z.AI",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.ZAI, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.ZAI, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.MOONSHOT)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.MOONSHOT)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.MOONSHOT)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.MOONSHOT)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.MOONSHOT).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.MOONSHOT)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.MOONSHOT)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.MOONSHOT) ||
            defaultModel != aiSettings.getModel(AiService.MOONSHOT) ||
            modelSource != aiSettings.getModelSource(AiService.MOONSHOT) ||
            manualModels != aiSettings.getManualModels(AiService.MOONSHOT) ||
            adminUrl != aiSettings.getProvider(AiService.MOONSHOT).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.MOONSHOT) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.MOONSHOT) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.MOONSHOT)

    AiServiceSettingsScreenTemplate(
        title = "Moonshot",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.MOONSHOT, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.MOONSHOT, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.COHERE)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.COHERE)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.COHERE)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.COHERE)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.COHERE).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.COHERE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.COHERE)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.COHERE) ||
            defaultModel != aiSettings.getModel(AiService.COHERE) ||
            modelSource != aiSettings.getModelSource(AiService.COHERE) ||
            manualModels != aiSettings.getManualModels(AiService.COHERE) ||
            adminUrl != aiSettings.getProvider(AiService.COHERE).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.COHERE) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.COHERE) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.COHERE)

    AiServiceSettingsScreenTemplate(
        title = "Cohere",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.COHERE, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.COHERE, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.AI21)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.AI21)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.AI21)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.AI21)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.AI21).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.AI21)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.AI21)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.AI21) ||
            defaultModel != aiSettings.getModel(AiService.AI21) ||
            modelSource != aiSettings.getModelSource(AiService.AI21) ||
            manualModels != aiSettings.getManualModels(AiService.AI21) ||
            adminUrl != aiSettings.getProvider(AiService.AI21).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.AI21) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.AI21) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.AI21)

    AiServiceSettingsScreenTemplate(
        title = "AI21",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.AI21, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.AI21, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.DASHSCOPE)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.DASHSCOPE)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.DASHSCOPE)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.DASHSCOPE)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.DASHSCOPE).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.DASHSCOPE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.DASHSCOPE)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.DASHSCOPE) ||
            defaultModel != aiSettings.getModel(AiService.DASHSCOPE) ||
            modelSource != aiSettings.getModelSource(AiService.DASHSCOPE) ||
            manualModels != aiSettings.getManualModels(AiService.DASHSCOPE) ||
            adminUrl != aiSettings.getProvider(AiService.DASHSCOPE).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.DASHSCOPE) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.DASHSCOPE) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.DASHSCOPE)

    AiServiceSettingsScreenTemplate(
        title = "DashScope",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.DASHSCOPE, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.DASHSCOPE, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.FIREWORKS)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.FIREWORKS)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.FIREWORKS)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.FIREWORKS)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.FIREWORKS).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.FIREWORKS)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.FIREWORKS)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.FIREWORKS) ||
            defaultModel != aiSettings.getModel(AiService.FIREWORKS) ||
            modelSource != aiSettings.getModelSource(AiService.FIREWORKS) ||
            manualModels != aiSettings.getManualModels(AiService.FIREWORKS) ||
            adminUrl != aiSettings.getProvider(AiService.FIREWORKS).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.FIREWORKS) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.FIREWORKS) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.FIREWORKS)

    AiServiceSettingsScreenTemplate(
        title = "Fireworks",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.FIREWORKS, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.FIREWORKS, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.CEREBRAS)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.CEREBRAS)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.CEREBRAS)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.CEREBRAS)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.CEREBRAS).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.CEREBRAS)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.CEREBRAS)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.CEREBRAS) ||
            defaultModel != aiSettings.getModel(AiService.CEREBRAS) ||
            modelSource != aiSettings.getModelSource(AiService.CEREBRAS) ||
            manualModels != aiSettings.getManualModels(AiService.CEREBRAS) ||
            adminUrl != aiSettings.getProvider(AiService.CEREBRAS).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.CEREBRAS) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.CEREBRAS) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.CEREBRAS)

    AiServiceSettingsScreenTemplate(
        title = "Cerebras",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.CEREBRAS, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.CEREBRAS, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.SAMBANOVA)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.SAMBANOVA)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.SAMBANOVA)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.SAMBANOVA)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.SAMBANOVA).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.SAMBANOVA)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.SAMBANOVA)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.SAMBANOVA) ||
            defaultModel != aiSettings.getModel(AiService.SAMBANOVA) ||
            modelSource != aiSettings.getModelSource(AiService.SAMBANOVA) ||
            manualModels != aiSettings.getManualModels(AiService.SAMBANOVA) ||
            adminUrl != aiSettings.getProvider(AiService.SAMBANOVA).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.SAMBANOVA) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.SAMBANOVA) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.SAMBANOVA)

    AiServiceSettingsScreenTemplate(
        title = "SambaNova",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.SAMBANOVA, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.SAMBANOVA, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.BAICHUAN)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.BAICHUAN)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.BAICHUAN)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.BAICHUAN)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.BAICHUAN).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.BAICHUAN)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.BAICHUAN)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.BAICHUAN) ||
            defaultModel != aiSettings.getModel(AiService.BAICHUAN) ||
            modelSource != aiSettings.getModelSource(AiService.BAICHUAN) ||
            manualModels != aiSettings.getManualModels(AiService.BAICHUAN) ||
            adminUrl != aiSettings.getProvider(AiService.BAICHUAN).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.BAICHUAN) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.BAICHUAN) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.BAICHUAN)

    AiServiceSettingsScreenTemplate(
        title = "Baichuan",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.BAICHUAN, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.BAICHUAN, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.STEPFUN)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.STEPFUN)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.STEPFUN)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.STEPFUN)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.STEPFUN).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.STEPFUN)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.STEPFUN)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.STEPFUN) ||
            defaultModel != aiSettings.getModel(AiService.STEPFUN) ||
            modelSource != aiSettings.getModelSource(AiService.STEPFUN) ||
            manualModels != aiSettings.getManualModels(AiService.STEPFUN) ||
            adminUrl != aiSettings.getProvider(AiService.STEPFUN).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.STEPFUN) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.STEPFUN) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.STEPFUN)

    AiServiceSettingsScreenTemplate(
        title = "StepFun",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.STEPFUN, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.STEPFUN, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.MINIMAX)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.MINIMAX)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.MINIMAX)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.MINIMAX)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.MINIMAX).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.MINIMAX)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.MINIMAX)) }

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

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.MINIMAX) ||
            defaultModel != aiSettings.getModel(AiService.MINIMAX) ||
            modelSource != aiSettings.getModelSource(AiService.MINIMAX) ||
            manualModels != aiSettings.getManualModels(AiService.MINIMAX) ||
            adminUrl != aiSettings.getProvider(AiService.MINIMAX).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.MINIMAX) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.MINIMAX) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.MINIMAX)

    AiServiceSettingsScreenTemplate(
        title = "MiniMax",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.MINIMAX, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.MINIMAX, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.NVIDIA)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.NVIDIA)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.NVIDIA)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.NVIDIA)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.NVIDIA).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.NVIDIA)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.NVIDIA)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.NVIDIA)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.NVIDIA) ||
            defaultModel != aiSettings.getModel(AiService.NVIDIA) ||
            modelSource != aiSettings.getModelSource(AiService.NVIDIA) ||
            manualModels != aiSettings.getManualModels(AiService.NVIDIA) ||
            adminUrl != aiSettings.getProvider(AiService.NVIDIA).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.NVIDIA) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.NVIDIA) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.NVIDIA)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.NVIDIA).isNotBlank() && aiSettings.getModelSource(AiService.NVIDIA) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.NVIDIA))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "NVIDIA",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.NVIDIA, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.NVIDIA, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.REPLICATE)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.REPLICATE)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.REPLICATE)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.REPLICATE)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.REPLICATE).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.REPLICATE)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.REPLICATE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.REPLICATE)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.REPLICATE) ||
            defaultModel != aiSettings.getModel(AiService.REPLICATE) ||
            modelSource != aiSettings.getModelSource(AiService.REPLICATE) ||
            manualModels != aiSettings.getManualModels(AiService.REPLICATE) ||
            adminUrl != aiSettings.getProvider(AiService.REPLICATE).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.REPLICATE) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.REPLICATE) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.REPLICATE)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.REPLICATE).isNotBlank() && aiSettings.getModelSource(AiService.REPLICATE) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.REPLICATE))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Replicate",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.REPLICATE, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.REPLICATE, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.HUGGINGFACE)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.HUGGINGFACE)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.HUGGINGFACE)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.HUGGINGFACE)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.HUGGINGFACE).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.HUGGINGFACE)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.HUGGINGFACE)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.HUGGINGFACE)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.HUGGINGFACE) ||
            defaultModel != aiSettings.getModel(AiService.HUGGINGFACE) ||
            modelSource != aiSettings.getModelSource(AiService.HUGGINGFACE) ||
            manualModels != aiSettings.getManualModels(AiService.HUGGINGFACE) ||
            adminUrl != aiSettings.getProvider(AiService.HUGGINGFACE).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.HUGGINGFACE) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.HUGGINGFACE) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.HUGGINGFACE)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.HUGGINGFACE).isNotBlank() && aiSettings.getModelSource(AiService.HUGGINGFACE) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.HUGGINGFACE))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Hugging Face",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.HUGGINGFACE, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.HUGGINGFACE, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.LAMBDA)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.LAMBDA)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.LAMBDA)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.LAMBDA)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.LAMBDA).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.LAMBDA)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.LAMBDA)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.LAMBDA)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.LAMBDA) ||
            defaultModel != aiSettings.getModel(AiService.LAMBDA) ||
            modelSource != aiSettings.getModelSource(AiService.LAMBDA) ||
            manualModels != aiSettings.getManualModels(AiService.LAMBDA) ||
            adminUrl != aiSettings.getProvider(AiService.LAMBDA).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.LAMBDA) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.LAMBDA) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.LAMBDA)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.LAMBDA).isNotBlank() && aiSettings.getModelSource(AiService.LAMBDA) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.LAMBDA))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Lambda",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.LAMBDA, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.LAMBDA, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.LEPTON)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.LEPTON)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.LEPTON)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.LEPTON)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.LEPTON).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.LEPTON)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.LEPTON)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.LEPTON)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.LEPTON) ||
            defaultModel != aiSettings.getModel(AiService.LEPTON) ||
            modelSource != aiSettings.getModelSource(AiService.LEPTON) ||
            manualModels != aiSettings.getManualModels(AiService.LEPTON) ||
            adminUrl != aiSettings.getProvider(AiService.LEPTON).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.LEPTON) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.LEPTON) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.LEPTON)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.LEPTON).isNotBlank() && aiSettings.getModelSource(AiService.LEPTON) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.LEPTON))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Lepton",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.LEPTON, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.LEPTON, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.YI)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.YI)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.YI)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.YI)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.YI).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.YI)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.YI)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.YI)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.YI) ||
            defaultModel != aiSettings.getModel(AiService.YI) ||
            modelSource != aiSettings.getModelSource(AiService.YI) ||
            manualModels != aiSettings.getManualModels(AiService.YI) ||
            adminUrl != aiSettings.getProvider(AiService.YI).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.YI) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.YI) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.YI)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.YI).isNotBlank() && aiSettings.getModelSource(AiService.YI) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.YI))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "01.AI",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.YI, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.YI, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.DOUBAO)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.DOUBAO)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.DOUBAO)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.DOUBAO)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.DOUBAO).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.DOUBAO)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.DOUBAO)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.DOUBAO)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.DOUBAO) ||
            defaultModel != aiSettings.getModel(AiService.DOUBAO) ||
            modelSource != aiSettings.getModelSource(AiService.DOUBAO) ||
            manualModels != aiSettings.getManualModels(AiService.DOUBAO) ||
            adminUrl != aiSettings.getProvider(AiService.DOUBAO).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.DOUBAO) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.DOUBAO) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.DOUBAO)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.DOUBAO).isNotBlank() && aiSettings.getModelSource(AiService.DOUBAO) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.DOUBAO))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Doubao",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.DOUBAO, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.DOUBAO, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.REKA)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.REKA)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.REKA)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.REKA)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.REKA).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.REKA)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.REKA)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.REKA)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.REKA) ||
            defaultModel != aiSettings.getModel(AiService.REKA) ||
            modelSource != aiSettings.getModelSource(AiService.REKA) ||
            manualModels != aiSettings.getManualModels(AiService.REKA) ||
            adminUrl != aiSettings.getProvider(AiService.REKA).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.REKA) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.REKA) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.REKA)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.REKA).isNotBlank() && aiSettings.getModelSource(AiService.REKA) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.REKA))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Reka",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.REKA, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.REKA, endpoints))
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
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(AiService.WRITER)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(AiService.WRITER)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(AiService.WRITER)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(AiService.WRITER)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(AiService.WRITER).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(AiService.WRITER)) }
    var endpoints by remember { mutableStateOf(aiSettings.getEndpointsForProvider(AiService.WRITER)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(AiService.WRITER)) }

    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    val hasChanges = apiKey != aiSettings.getApiKey(AiService.WRITER) ||
            defaultModel != aiSettings.getModel(AiService.WRITER) ||
            modelSource != aiSettings.getModelSource(AiService.WRITER) ||
            manualModels != aiSettings.getManualModels(AiService.WRITER) ||
            adminUrl != aiSettings.getProvider(AiService.WRITER).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(AiService.WRITER) ||
            selectedParametersIds != aiSettings.getParametersIds(AiService.WRITER) ||
            endpoints != aiSettings.getEndpointsForProvider(AiService.WRITER)

    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(AiService.WRITER).isNotBlank() && aiSettings.getModelSource(AiService.WRITER) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(AiService.WRITER))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = "Writer",
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(AiService.WRITER, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(AiService.WRITER, endpoints))
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
