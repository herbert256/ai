package com.ai.ui

import androidx.compose.runtime.*
import com.ai.data.AiService

/**
 * Returns the default endpoints for a provider, or an empty list if no defaults.
 */
fun defaultEndpointsForProvider(service: AiService): List<AiEndpoint> = when (service) {
    // Providers with 2 endpoints
    AiService.OPENAI -> listOf(
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
    AiService.DEEPSEEK -> listOf(
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
    AiService.MISTRAL -> listOf(
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
    AiService.SILICONFLOW -> listOf(
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
    AiService.ZAI -> listOf(
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
    // Providers with 1 endpoint
    AiService.MOONSHOT -> listOf(
        AiEndpoint(
            id = "moonshot-chat-completions",
            name = "Chat Completions",
            url = "https://api.moonshot.cn/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.COHERE -> listOf(
        AiEndpoint(
            id = "cohere-chat-completions",
            name = "Chat Completions",
            url = "https://api.cohere.ai/compatibility/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.AI21 -> listOf(
        AiEndpoint(
            id = "ai21-chat-completions",
            name = "Chat Completions",
            url = "https://api.ai21.com/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.DASHSCOPE -> listOf(
        AiEndpoint(
            id = "dashscope-chat-completions",
            name = "Chat Completions",
            url = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.FIREWORKS -> listOf(
        AiEndpoint(
            id = "fireworks-chat-completions",
            name = "Chat Completions",
            url = "https://api.fireworks.ai/inference/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.CEREBRAS -> listOf(
        AiEndpoint(
            id = "cerebras-chat-completions",
            name = "Chat Completions",
            url = "https://api.cerebras.ai/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.SAMBANOVA -> listOf(
        AiEndpoint(
            id = "sambanova-chat-completions",
            name = "Chat Completions",
            url = "https://api.sambanova.ai/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.BAICHUAN -> listOf(
        AiEndpoint(
            id = "baichuan-chat-completions",
            name = "Chat Completions",
            url = "https://api.baichuan-ai.com/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.STEPFUN -> listOf(
        AiEndpoint(
            id = "stepfun-chat-completions",
            name = "Chat Completions",
            url = "https://api.stepfun.com/v1/chat/completions",
            isDefault = true
        )
    )
    AiService.MINIMAX -> listOf(
        AiEndpoint(
            id = "minimax-chat-completions",
            name = "Chat Completions",
            url = "https://api.minimax.io/v1/chat/completions",
            isDefault = true
        )
    )
    // Providers with no default endpoints
    else -> emptyList()
}

/**
 * Unified provider settings screen for all 31 AI services.
 */
@Composable
fun ProviderSettingsScreen(
    service: AiService,
    aiSettings: AiSettings,
    availableModels: List<String> = emptyList(),
    isLoadingModels: Boolean = false,
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit = {},
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(service)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(service)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(service)) }
    var manualModels by remember { mutableStateOf(aiSettings.getManualModels(service)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(service).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(service)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(service)) }

    val defaultEndpoints = defaultEndpointsForProvider(service)
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(service).let { saved ->
                if (saved.isEmpty() && defaultEndpoints.isNotEmpty()) defaultEndpoints else saved
            }
        )
    }

    // Effective model list for dropdown
    val effectiveModels = if (modelSource == ModelSource.API) availableModels else manualModels

    // Track if there are unsaved changes
    val hasChanges = apiKey != aiSettings.getApiKey(service) ||
            defaultModel != aiSettings.getModel(service) ||
            modelSource != aiSettings.getModelSource(service) ||
            manualModels != aiSettings.getManualModels(service) ||
            adminUrl != aiSettings.getProvider(service).adminUrl ||
            modelListUrl != aiSettings.getModelListUrl(service) ||
            selectedParametersIds != aiSettings.getParametersIds(service) ||
            endpoints != aiSettings.getEndpointsForProvider(service)

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(service).isNotBlank() && aiSettings.getModelSource(service) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(service))
        }
    }

    AiServiceSettingsScreenTemplate(
        title = service.displayName,
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        onSave = {
            onSave(aiSettings.withProvider(service, ProviderConfig(
                apiKey = apiKey,
                model = defaultModel,
                modelSource = modelSource,
                manualModels = manualModels,
                adminUrl = adminUrl,
                modelListUrl = modelListUrl,
                parametersIds = selectedParametersIds
            )).withEndpoints(service, endpoints))
        },
        hasChanges = hasChanges,
        apiKey = apiKey,
        defaultModel = defaultModel,
        availableModels = effectiveModels,
        onDefaultModelChange = { defaultModel = it },
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(service, apiKey, defaultModel) },
        onClearApiKey = { apiKey = "" },
        onCreateAgent = onCreateAgent,
        onProviderStateChange = onProviderStateChange
    ) {
        ApiKeyInputSection(
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            onTestApiKey = { onTestApiKey(service, apiKey, defaultModel) }
        )
        ParametersSelector(
            aiSettings = aiSettings,
            selectedParametersIds = selectedParametersIds,
            onParamsSelected = { ids -> selectedParametersIds = ids }
        )
        EndpointsSection(
            endpoints = endpoints,
            defaultEndpointUrl = service.baseUrl,
            onEndpointsChange = { endpoints = it }
        )
        ModelListUrlSection(
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(service),
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
