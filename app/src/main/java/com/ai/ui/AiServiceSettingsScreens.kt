package com.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ai.data.AiService
import com.ai.data.ApiFormat
import com.ai.data.EndpointRule
import kotlinx.coroutines.launch

/**
 * Returns the default endpoints for a provider, or an empty list if no defaults.
 */
fun defaultEndpointsForProvider(service: AiService): List<AiEndpoint> = when (service.id) {
    // Providers with 2 endpoints
    "OPENAI" -> listOf(
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
    "DEEPSEEK" -> listOf(
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
    "MISTRAL" -> listOf(
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
    "SILICONFLOW" -> listOf(
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
    "ZAI" -> listOf(
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
    "MOONSHOT" -> listOf(
        AiEndpoint(
            id = "moonshot-chat-completions",
            name = "Chat Completions",
            url = "https://api.moonshot.cn/v1/chat/completions",
            isDefault = true
        )
    )
    "COHERE" -> listOf(
        AiEndpoint(
            id = "cohere-chat-completions",
            name = "Chat Completions",
            url = "https://api.cohere.ai/compatibility/v1/chat/completions",
            isDefault = true
        )
    )
    "AI21" -> listOf(
        AiEndpoint(
            id = "ai21-chat-completions",
            name = "Chat Completions",
            url = "https://api.ai21.com/v1/chat/completions",
            isDefault = true
        )
    )
    "DASHSCOPE" -> listOf(
        AiEndpoint(
            id = "dashscope-chat-completions",
            name = "Chat Completions",
            url = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions",
            isDefault = true
        )
    )
    "FIREWORKS" -> listOf(
        AiEndpoint(
            id = "fireworks-chat-completions",
            name = "Chat Completions",
            url = "https://api.fireworks.ai/inference/v1/chat/completions",
            isDefault = true
        )
    )
    "CEREBRAS" -> listOf(
        AiEndpoint(
            id = "cerebras-chat-completions",
            name = "Chat Completions",
            url = "https://api.cerebras.ai/v1/chat/completions",
            isDefault = true
        )
    )
    "SAMBANOVA" -> listOf(
        AiEndpoint(
            id = "sambanova-chat-completions",
            name = "Chat Completions",
            url = "https://api.sambanova.ai/v1/chat/completions",
            isDefault = true
        )
    )
    "BAICHUAN" -> listOf(
        AiEndpoint(
            id = "baichuan-chat-completions",
            name = "Chat Completions",
            url = "https://api.baichuan-ai.com/v1/chat/completions",
            isDefault = true
        )
    )
    "STEPFUN" -> listOf(
        AiEndpoint(
            id = "stepfun-chat-completions",
            name = "Chat Completions",
            url = "https://api.stepfun.com/v1/chat/completions",
            isDefault = true
        )
    )
    "MINIMAX" -> listOf(
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
    isLoadingModels: Boolean = false,
    providerState: String = aiSettings.getProviderState(service),
    onBackToAiSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onFetchModels: (String) -> Unit = {},
    onTestApiKey: suspend (AiService, String, String) -> String?,
    onCreateAgent: () -> Unit = {},
    onProviderStateChange: (String) -> Unit = {},
    onUpdateDefinition: (AiService) -> Unit = {},
    onDeleteProvider: (AiService) -> Unit = {}
) {
    var apiKey by remember { mutableStateOf(aiSettings.getApiKey(service)) }
    var defaultModel by remember { mutableStateOf(aiSettings.getModel(service)) }
    var modelSource by remember { mutableStateOf(aiSettings.getModelSource(service)) }
    var models by remember { mutableStateOf(aiSettings.getModels(service)) }
    var adminUrl by remember { mutableStateOf(aiSettings.getProvider(service).adminUrl) }
    var modelListUrl by remember { mutableStateOf(aiSettings.getModelListUrl(service)) }
    var selectedParametersIds by remember { mutableStateOf(aiSettings.getParametersIds(service)) }

    // Definition fields state
    var defDisplayName by remember { mutableStateOf(service.displayName) }
    var defBaseUrl by remember { mutableStateOf(service.baseUrl) }
    var defAdminUrl by remember { mutableStateOf(service.adminUrl) }
    var defDefaultModel by remember { mutableStateOf(service.defaultModel) }
    var defApiFormat by remember { mutableStateOf(service.apiFormat) }
    var defChatPath by remember { mutableStateOf(service.chatPath) }
    var defModelsPath by remember { mutableStateOf(service.modelsPath ?: "v1/models") }
    var defModelsPathNull by remember { mutableStateOf(service.modelsPath == null) }
    var defPrefsKey by remember { mutableStateOf(service.prefsKey) }
    var defOpenRouterName by remember { mutableStateOf(service.openRouterName ?: "") }
    var defSeedFieldName by remember { mutableStateOf(service.seedFieldName) }
    var defSupportsCitations by remember { mutableStateOf(service.supportsCitations) }
    var defSupportsSearchRecency by remember { mutableStateOf(service.supportsSearchRecency) }
    var defExtractApiCost by remember { mutableStateOf(service.extractApiCost) }
    var defModelListFormat by remember { mutableStateOf(service.modelListFormat) }
    var defModelFilter by remember { mutableStateOf(service.modelFilter ?: "") }
    var defLitellmPrefix by remember { mutableStateOf(service.litellmPrefix ?: "") }
    var defEndpointRules by remember { mutableStateOf(service.endpointRules) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDefAddRuleDialog by remember { mutableStateOf(false) }
    var defEditingRuleIndex by remember { mutableIntStateOf(-1) }

    val hasDefinitionChanges = defDisplayName != service.displayName ||
            defBaseUrl != service.baseUrl ||
            defAdminUrl != service.adminUrl ||
            defDefaultModel != service.defaultModel ||
            defApiFormat != service.apiFormat ||
            defChatPath != service.chatPath ||
            (if (defModelsPathNull) null else defModelsPath) != service.modelsPath ||
            defPrefsKey != service.prefsKey ||
            defOpenRouterName.ifBlank { null } != service.openRouterName ||
            defSeedFieldName != service.seedFieldName ||
            defSupportsCitations != service.supportsCitations ||
            defSupportsSearchRecency != service.supportsSearchRecency ||
            defExtractApiCost != service.extractApiCost ||
            defModelListFormat != service.modelListFormat ||
            defModelFilter.ifBlank { null } != service.modelFilter ||
            defLitellmPrefix.ifBlank { null } != service.litellmPrefix ||
            defEndpointRules != service.endpointRules

    val defaultEndpoints = defaultEndpointsForProvider(service)
    var endpoints by remember {
        mutableStateOf(
            aiSettings.getEndpointsForProvider(service).let { saved ->
                if (saved.isEmpty() && defaultEndpoints.isNotEmpty()) defaultEndpoints else saved
            }
        )
    }

    var showModelSelect by remember { mutableStateOf(false) }

    // Auto-refresh model list on page load
    LaunchedEffect(Unit) {
        if (aiSettings.getApiKey(service).isNotBlank() && aiSettings.getModelSource(service) == ModelSource.API) {
            onFetchModels(aiSettings.getApiKey(service))
        }
    }

    // Auto-save config changes
    var configSaveEnabled by remember { mutableStateOf(false) }
    val configKey = listOf<Any?>(apiKey, defaultModel, modelSource, models, adminUrl, modelListUrl, selectedParametersIds, endpoints)
    LaunchedEffect(configKey) {
        if (!configSaveEnabled) {
            configSaveEnabled = true
            return@LaunchedEffect
        }
        onSave(aiSettings.withProvider(service, ProviderConfig(
            apiKey = apiKey,
            model = defaultModel,
            modelSource = modelSource,
            models = models,
            adminUrl = adminUrl,
            modelListUrl = modelListUrl,
            parametersIds = selectedParametersIds
        )).withEndpoints(service, endpoints))
    }

    // Auto-save definition changes
    var defSaveEnabled by remember { mutableStateOf(false) }
    val defKey = listOf<Any?>(defDisplayName, defBaseUrl, defAdminUrl, defDefaultModel, defApiFormat, defChatPath, defModelsPath, defModelsPathNull, defPrefsKey, defOpenRouterName, defSeedFieldName, defSupportsCitations, defSupportsSearchRecency, defExtractApiCost, defModelListFormat, defModelFilter, defLitellmPrefix, defEndpointRules)
    LaunchedEffect(defKey) {
        if (!defSaveEnabled) {
            defSaveEnabled = true
            return@LaunchedEffect
        }
        if (hasDefinitionChanges) {
            val updatedService = AiService(
                id = service.id,
                displayName = defDisplayName,
                baseUrl = if (defBaseUrl.endsWith("/")) defBaseUrl else "$defBaseUrl/",
                adminUrl = defAdminUrl,
                defaultModel = defDefaultModel,
                openRouterName = defOpenRouterName.ifBlank { null },
                apiFormat = defApiFormat,
                chatPath = defChatPath,
                modelsPath = if (defModelsPathNull) null else defModelsPath.ifBlank { null },
                prefsKey = defPrefsKey,
                seedFieldName = defSeedFieldName,
                supportsCitations = defSupportsCitations,
                supportsSearchRecency = defSupportsSearchRecency,
                extractApiCost = defExtractApiCost,
                modelListFormat = defModelListFormat,
                modelFilter = defModelFilter.ifBlank { null },
                litellmPrefix = defLitellmPrefix.ifBlank { null },
                endpointRules = defEndpointRules
            )
            onUpdateDefinition(updatedService)
        }
    }

    if (showModelSelect) {
        SelectModelScreen(
            provider = service,
            aiSettings = aiSettings,
            currentModel = defaultModel,
            showDefaultOption = false,
            onSelectModel = { defaultModel = it; showModelSelect = false },
            onBack = { showModelSelect = false },
            onNavigateHome = onBackToHome
        )
    } else AiServiceSettingsScreenTemplate(
        title = service.displayName,
        onBackToAiSettings = onBackToAiSettings,
        onBackToHome = onBackToHome,
        apiKey = apiKey,
        adminUrl = adminUrl,
        onAdminUrlChange = { adminUrl = it },
        onTestApiKey = { onTestApiKey(service, apiKey, defaultModel) },
        onProviderStateChange = onProviderStateChange
    ) {
        // Provider state with inactive toggle
        val scope = rememberCoroutineScope()
        var isTesting by remember { mutableStateOf(false) }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Provider State",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Text(
                        text = if (isTesting) "testing..." else providerState,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA)
                    )
                }
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (providerState == "inactive") "activate" else "mark inactive",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Switch(
                        checked = providerState == "inactive",
                        onCheckedChange = { makeInactive ->
                            val providerConfig = ProviderConfig(
                                apiKey = apiKey,
                                model = defaultModel,
                                modelSource = modelSource,
                                models = models,
                                adminUrl = adminUrl,
                                modelListUrl = modelListUrl,
                                parametersIds = selectedParametersIds
                            )
                            if (makeInactive) {
                                onSave(aiSettings
                                    .withProviderState(service, "inactive")
                                    .withProvider(service, providerConfig)
                                    .withEndpoints(service, endpoints))
                                onBackToAiSettings()
                            } else {
                                scope.launch {
                                    isTesting = true
                                    val newState = if (apiKey.isBlank()) {
                                        "not-used"
                                    } else {
                                        val error = onTestApiKey(service, apiKey, defaultModel)
                                        if (error == null) "ok" else "error"
                                    }
                                    isTesting = false
                                    onSave(aiSettings
                                        .withProviderState(service, newState)
                                        .withProvider(service, providerConfig)
                                        .withEndpoints(service, endpoints))
                                    onBackToAiSettings()
                                }
                            }
                        }
                    )
                }
            }
        }

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
        ModelsSection(
            defaultModel = defaultModel,
            onDefaultModelChange = { defaultModel = it },
            onSelectDefaultModel = { showModelSelect = true },
            modelSource = modelSource,
            models = models,
            isLoadingModels = isLoadingModels,
            onModelSourceChange = { modelSource = it },
            onModelsChange = { models = it },
            onFetchModels = { onFetchModels(apiKey) },
            modelListUrl = modelListUrl,
            defaultModelListUrl = aiSettings.getDefaultModelListUrl(service),
            onModelListUrlChange = { modelListUrl = it },
            modelsPath = defModelsPath,
            modelsPathNull = defModelsPathNull,
            onModelsPathChange = { defModelsPath = it },
            onModelsPathNullChange = { defModelsPathNull = it },
            modelFilter = defModelFilter,
            onModelFilterChange = { defModelFilter = it },
            modelListFormat = defModelListFormat,
            onModelListFormatChange = { defModelListFormat = it },
            litellmPrefix = defLitellmPrefix,
            onLitellmPrefixChange = { defLitellmPrefix = it }
        )

        // Provider Definition section
        Spacer(modifier = Modifier.height(8.dp))
        CollapsibleCard(title = "Provider Definition") {
                    OutlinedTextField(
                        value = defDisplayName,
                        onValueChange = { defDisplayName = it },
                        label = { Text("Display Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    OutlinedTextField(
                        value = defBaseUrl,
                        onValueChange = { defBaseUrl = it },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    // API Format dropdown
                    var defFormatExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(
                            value = defApiFormat.name,
                            onValueChange = {},
                            label = { Text("API Format") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { defFormatExpanded = true },
                            readOnly = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6B9BFF),
                                unfocusedBorderColor = Color(0xFF444444)
                            )
                        )
                        DropdownMenu(
                            expanded = defFormatExpanded,
                            onDismissRequest = { defFormatExpanded = false }
                        ) {
                            ApiFormat.entries.forEach { format ->
                                DropdownMenuItem(
                                    text = { Text(format.name) },
                                    onClick = {
                                        defApiFormat = format
                                        when (format) {
                                            ApiFormat.ANTHROPIC -> {
                                                defChatPath = "v1/messages"
                                                defModelsPath = ""
                                                defModelsPathNull = true
                                            }
                                            ApiFormat.GOOGLE -> {
                                                defChatPath = "v1beta/models"
                                                defModelsPath = "v1beta/models"
                                            }
                                            ApiFormat.OPENAI_COMPATIBLE -> {
                                                defChatPath = "v1/chat/completions"
                                                defModelsPath = "v1/models"
                                                defModelsPathNull = false
                                            }
                                        }
                                        defFormatExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = defChatPath,
                        onValueChange = { defChatPath = it },
                        label = { Text("Chat Path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    OutlinedTextField(
                        value = defPrefsKey,
                        onValueChange = { defPrefsKey = it.lowercase().replace(Regex("[^a-z0-9_]"), "") },
                        label = { Text("Preferences Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("Used for SharedPreferences storage") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    // Advanced fields
                    OutlinedTextField(
                        value = defOpenRouterName,
                        onValueChange = { defOpenRouterName = it },
                        label = { Text("OpenRouter Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    OutlinedTextField(
                        value = defSeedFieldName,
                        onValueChange = { defSeedFieldName = it },
                        label = { Text("Seed Field Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    // Boolean toggles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Supports Citations", color = Color.White)
                        Switch(checked = defSupportsCitations, onCheckedChange = { defSupportsCitations = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Supports Search Recency", color = Color.White)
                        Switch(checked = defSupportsSearchRecency, onCheckedChange = { defSupportsSearchRecency = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Extract API Cost", color = Color.White)
                        Switch(checked = defExtractApiCost, onCheckedChange = { defExtractApiCost = it })
                    }

                    // Endpoint Rules
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Endpoint Rules", color = Color.White, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = { defEditingRuleIndex = -1; showDefAddRuleDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("+ Add", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "Route models to different API endpoints by prefix",
                        color = Color(0xFF888888),
                        fontSize = 12.sp
                    )
                    defEndpointRules.forEachIndexed { index, rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2A2A2A), shape = MaterialTheme.shapes.small)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${rule.modelPrefix}*", color = Color(0xFF6B9BFF), fontSize = 14.sp)
                                Text("\u2192 ${rule.endpointType}", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                            }
                            Row {
                                IconButton(onClick = { defEditingRuleIndex = index; showDefAddRuleDialog = true }) {
                                    Text("\u270E", color = Color(0xFF888888))
                                }
                                IconButton(onClick = {
                                    defEndpointRules = defEndpointRules.toMutableList().also { it.removeAt(index) }
                                }) {
                                    Text("\u2715", color = Color(0xFFCC4444))
                                }
                            }
                        }
                    }
                }

        // Add/Edit Rule Dialog for definition endpoint rules
        if (showDefAddRuleDialog) {
            val editing = defEditingRuleIndex >= 0
            val existingRule = if (editing) defEndpointRules[defEditingRuleIndex] else null
            var rulePrefix by remember(showDefAddRuleDialog, defEditingRuleIndex) { mutableStateOf(existingRule?.modelPrefix ?: "") }
            var ruleType by remember(showDefAddRuleDialog, defEditingRuleIndex) { mutableStateOf(existingRule?.endpointType ?: "responses") }

            AlertDialog(
                onDismissRequest = { showDefAddRuleDialog = false },
                title = { Text(if (editing) "Edit Rule" else "Add Endpoint Rule") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = rulePrefix,
                            onValueChange = { rulePrefix = it },
                            label = { Text("Model Prefix") },
                            placeholder = { Text("e.g. gpt-5, o3, o4") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("Endpoint Type:", color = Color.White, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("responses", "chat").forEach { type ->
                                FilterChip(
                                    selected = ruleType == type,
                                    onClick = { ruleType = type },
                                    label = { Text(type) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (rulePrefix.isNotBlank()) {
                                val newRule = EndpointRule(rulePrefix, ruleType)
                                defEndpointRules = if (editing) {
                                    defEndpointRules.toMutableList().also { it[defEditingRuleIndex] = newRule }
                                } else {
                                    defEndpointRules + newRule
                                }
                                showDefAddRuleDialog = false
                            }
                        }
                    ) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showDefAddRuleDialog = false }) { Text("Cancel") }
                }
            )
        }

        // Delete Provider button
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF5252)
            )
        ) {
            Text("Delete Provider")
        }

        // Delete confirmation dialog
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Provider") },
                text = { Text("Delete \"${service.displayName}\"? This will also remove all agents and swarm members using this provider.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            onDeleteProvider(service)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252))
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}
