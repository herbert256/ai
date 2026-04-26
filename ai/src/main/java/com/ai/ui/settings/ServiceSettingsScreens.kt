package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.data.EndpointRule
import com.ai.data.ProviderRegistry
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.SelectModelScreen
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.launch

// ===== Models list (one entry per provider) =====

@Composable
fun ModelsListScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onProviderSelected: (AppService) -> Unit
) {
    BackHandler { onBackToAiSetup() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "Models", onBackClick = onBackToAiSetup, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))
        // Only show providers with a working API key (state == "ok"), matching the "Active"
        // filter on the Providers screen.
        val activeProviders = AppService.entries
            .filter { aiSettings.getProviderState(it) == "ok" }
            .sortedBy { it.displayName }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (activeProviders.isEmpty()) {
                Text(
                    "No active providers yet. Set an API key and test it in Providers.",
                    fontSize = 13.sp, color = AppColors.TextTertiary
                )
            }
            activeProviders.forEach { provider ->
                val config = aiSettings.getProvider(provider)
                val model = config.model
                val count = config.models.size
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onProviderSelected(provider) },
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.displayName, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (model.isNotBlank()) {
                                Text(model, fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text("$count", fontSize = 14.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }
    }
}

// ===== Per-provider model settings (subset of ProviderSettingsScreen) =====

@Composable
fun ProviderModelSettingsScreen(
    service: AppService,
    aiSettings: Settings,
    isLoadingModels: Boolean = false,
    onBack: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onFetchModels: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val config = aiSettings.getProvider(service)
    val apiKey = config.apiKey
    var modelSource by remember { mutableStateOf(config.modelSource) }
    var models by remember { mutableStateOf(config.models) }
    // Inline add/edit state for the Manual CRUD form.
    var manualInput by remember { mutableStateOf("") }
    var editingOriginal by remember { mutableStateOf<String?>(null) }

    // Mirror external updates (e.g. completed Fetch Models) into local state so the count
    // and list refresh as soon as the new model list lands in settings.
    LaunchedEffect(config.models) {
        if (config.models != models) models = config.models
    }

    // Auto-save — only the fields this screen exposes; other config fields preserved via copy of the current provider config.
    LaunchedEffect(modelSource, models) {
        val current = aiSettings.getProvider(service)
        val updated = current.copy(modelSource = modelSource, models = models)
        if (updated != current) onSave(aiSettings.withProvider(service, updated))
    }

    // When switching away from Manual mode, clear any half-typed/edit state to avoid surprises on return.
    LaunchedEffect(modelSource) {
        if (modelSource != ModelSource.MANUAL) { manualInput = ""; editingOriginal = null }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = "${service.displayName} models", onBackClick = onBack, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = modelSource == ModelSource.API,
                    onClick = { modelSource = ModelSource.API },
                    label = { Text("API") }
                )
                FilterChip(
                    selected = modelSource == ModelSource.MANUAL,
                    onClick = { modelSource = ModelSource.MANUAL },
                    label = { Text("Manual") }
                )
            }
            if (modelSource == ModelSource.API && apiKey.isNotBlank()) {
                Button(
                    onClick = { onFetchModels(apiKey) },
                    enabled = !isLoadingModels,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                ) { Text(if (isLoadingModels) "Fetching..." else "Fetch Models", maxLines = 1, softWrap = false) }
            } else if (modelSource == ModelSource.API && apiKey.isBlank()) {
                Text("Set an API key for ${service.displayName} in Providers to fetch models.", fontSize = 12.sp, color = AppColors.TextTertiary)
            }

            // Manual-only add/update form — placed above the list so the CRUD affordances are obvious.
            if (modelSource == ModelSource.MANUAL) {
                val trimmed = manualInput.trim()
                val isEditing = editingOriginal != null
                val isDuplicate = trimmed.isNotBlank() && trimmed != editingOriginal && trimmed in models
                val canSubmit = trimmed.isNotBlank() && !isDuplicate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualInput, onValueChange = { manualInput = it },
                        label = { Text(if (isEditing) "Edit model ID" else "Model ID") },
                        placeholder = { Text("e.g. gpt-4o-mini") },
                        modifier = Modifier.weight(1f),
                        singleLine = true, colors = AppColors.outlinedFieldColors(),
                        isError = isDuplicate
                    )
                    Button(
                        onClick = {
                            val t = trimmed
                            models = if (isEditing) models.map { if (it == editingOriginal) t else it } else models + t
                            manualInput = ""
                            editingOriginal = null
                        },
                        enabled = canSubmit,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                    ) { Text(if (isEditing) "Update" else "Add", maxLines = 1, softWrap = false) }
                    if (isEditing) {
                        TextButton(onClick = { manualInput = ""; editingOriginal = null }) {
                            Text("Cancel", color = AppColors.TextTertiary, maxLines = 1, softWrap = false)
                        }
                    }
                }
                if (isDuplicate) {
                    Text("Already in list", fontSize = 12.sp, color = AppColors.Red)
                }
            }

            if (models.isNotEmpty()) {
                Text("${models.size} models available", fontSize = 12.sp, color = AppColors.TextTertiary)
                HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.padding(vertical = 4.dp))
                models.sorted().forEach { modelId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToModelInfo(service, modelId) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modelId,
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (modelSource == ModelSource.MANUAL) {
                            TextButton(
                                onClick = {
                                    models = models.filter { it != modelId }
                                    if (editingOriginal == modelId) { editingOriginal = null; manualInput = "" }
                                },
                                modifier = Modifier.semantics { contentDescription = "Delete $modelId" }
                            ) {
                                Text("\u2715", fontSize = 14.sp, color = AppColors.TextTertiary, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProviderSettingsScreen(
    service: AppService,
    aiSettings: Settings,
    isLoadingModels: Boolean = false,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onFetchModels: (String) -> Unit = {},
    onTestApiKey: suspend (AppService, String, String) -> String?,
    onProviderStateChange: (String) -> Unit = {},
    onTestModelWithPrompt: (suspend (String) -> Pair<Boolean, String?>)? = null,
    onNavigateToTrace: ((String) -> Unit)? = null,
    onNavigateToModels: () -> Unit = {}
) {
    BackHandler { onBackToSettings() }
    val scope = rememberCoroutineScope()

    val config = aiSettings.getProvider(service)
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var defaultModel by remember { mutableStateOf(config.model) }
    var adminUrl by remember { mutableStateOf(config.adminUrl) }
    var modelListUrl by remember { mutableStateOf(config.modelListUrl) }
    var selectedParametersIds by remember { mutableStateOf(config.parametersIds) }
    var isInactive by remember { mutableStateOf(aiSettings.getProviderState(service) == "inactive") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showModelSelector by remember { mutableStateOf(false) }

    // ===== Provider definition (catalog) state =====
    // Mirrors every field on ProviderDefinition / AppService so the user can edit the
    // catalog entry that ships in setup.json. Auto-saves via ProviderRegistry.update().
    var defDisplayName by remember(service.id) { mutableStateOf(service.displayName) }
    var defBaseUrl by remember(service.id) { mutableStateOf(service.baseUrl) }
    var defAdminUrl by remember(service.id) { mutableStateOf(service.adminUrl) }
    var defDefaultModel by remember(service.id) { mutableStateOf(service.defaultModel) }
    var defOpenRouterName by remember(service.id) { mutableStateOf(service.openRouterName ?: "") }
    var defApiFormat by remember(service.id) { mutableStateOf(service.apiFormat) }
    var defChatPath by remember(service.id) { mutableStateOf(service.chatPath) }
    var defModelsPath by remember(service.id) { mutableStateOf(service.modelsPath ?: "") }
    var defSeedFieldName by remember(service.id) { mutableStateOf(service.seedFieldName) }
    var defModelListFormat by remember(service.id) { mutableStateOf(service.modelListFormat) }
    var defDefaultModelSource by remember(service.id) { mutableStateOf(service.defaultModelSource ?: "API") }
    var defModelFilter by remember(service.id) { mutableStateOf(service.modelFilter ?: "") }
    var defLitellmPrefix by remember(service.id) { mutableStateOf(service.litellmPrefix ?: "") }
    var defCostTicksDivisor by remember(service.id) { mutableStateOf(service.costTicksDivisor?.toString() ?: "") }
    var defExtractApiCost by remember(service.id) { mutableStateOf(service.extractApiCost) }
    var defSupportsCitations by remember(service.id) { mutableStateOf(service.supportsCitations) }
    var defSupportsSearchRecency by remember(service.id) { mutableStateOf(service.supportsSearchRecency) }
    var defHardcodedModelsText by remember(service.id) {
        mutableStateOf(service.hardcodedModels?.joinToString(", ") ?: "")
    }
    var defEndpointRules by remember(service.id) { mutableStateOf(service.endpointRules) }
    var newRulePrefix by remember(service.id) { mutableStateOf("") }
    var newRuleType by remember(service.id) { mutableStateOf("responses") }

    LaunchedEffect(
        defDisplayName, defBaseUrl, defAdminUrl, defDefaultModel, defOpenRouterName, defApiFormat,
        defChatPath, defModelsPath, defSeedFieldName, defModelListFormat, defDefaultModelSource,
        defModelFilter, defLitellmPrefix, defCostTicksDivisor, defExtractApiCost,
        defSupportsCitations, defSupportsSearchRecency, defHardcodedModelsText, defEndpointRules
    ) {
        // Don't push back garbage during the very first composition. Only update if the user
        // actually changed something — i.e. a field differs from its catalog source value.
        val same = defDisplayName == service.displayName &&
            defBaseUrl == service.baseUrl &&
            defAdminUrl == service.adminUrl &&
            defDefaultModel == service.defaultModel &&
            defOpenRouterName == (service.openRouterName ?: "") &&
            defApiFormat == service.apiFormat &&
            defChatPath == service.chatPath &&
            defModelsPath == (service.modelsPath ?: "") &&
            defSeedFieldName == service.seedFieldName &&
            defModelListFormat == service.modelListFormat &&
            defDefaultModelSource == (service.defaultModelSource ?: "API") &&
            defModelFilter == (service.modelFilter ?: "") &&
            defLitellmPrefix == (service.litellmPrefix ?: "") &&
            defCostTicksDivisor == (service.costTicksDivisor?.toString() ?: "") &&
            defExtractApiCost == service.extractApiCost &&
            defSupportsCitations == service.supportsCitations &&
            defSupportsSearchRecency == service.supportsSearchRecency &&
            defHardcodedModelsText == (service.hardcodedModels?.joinToString(", ") ?: "") &&
            defEndpointRules == service.endpointRules
        if (same) return@LaunchedEffect
        if (defDisplayName.isBlank() || defBaseUrl.isBlank() || defDefaultModel.isBlank()) return@LaunchedEffect
        val hardcoded = defHardcodedModelsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        ProviderRegistry.update(AppService(
            id = service.id,
            displayName = defDisplayName.trim(),
            baseUrl = defBaseUrl.trim(),
            adminUrl = defAdminUrl.trim(),
            defaultModel = defDefaultModel.trim(),
            openRouterName = defOpenRouterName.trim().ifBlank { null },
            apiFormat = defApiFormat,
            chatPath = defChatPath.trim().ifBlank { "v1/chat/completions" },
            modelsPath = defModelsPath.trim().ifBlank { null },
            prefsKey = service.prefsKey,
            seedFieldName = defSeedFieldName.trim().ifBlank { "seed" },
            supportsCitations = defSupportsCitations,
            supportsSearchRecency = defSupportsSearchRecency,
            extractApiCost = defExtractApiCost,
            costTicksDivisor = defCostTicksDivisor.trim().toDoubleOrNull(),
            modelListFormat = defModelListFormat,
            modelFilter = defModelFilter.trim().ifBlank { null },
            litellmPrefix = defLitellmPrefix.trim().ifBlank { null },
            hardcodedModels = hardcoded.ifEmpty { null },
            defaultModelSource = defDefaultModelSource,
            endpointRules = defEndpointRules
        ))
    }

    // Model count shown on the Models link — read live from settings so it reflects updates
    // made on the Models sub-screen.
    val modelsCount = aiSettings.getProvider(service).models.size

    // Auto-save — only the fields this screen edits; modelSource / models are owned by the
    // Models sub-screen and preserved via copy() of the current config.
    LaunchedEffect(apiKey, defaultModel, adminUrl, modelListUrl, selectedParametersIds) {
        val current = aiSettings.getProvider(service)
        val updated = current.copy(
            apiKey = apiKey, model = defaultModel, adminUrl = adminUrl,
            modelListUrl = modelListUrl, parametersIds = selectedParametersIds
        )
        if (updated == current) return@LaunchedEffect
        var newSettings = aiSettings.withProvider(service, updated)
        // Keep the default agent named after the provider in sync with the provider's
        // default model, so changing the model here also updates the matching default agent.
        if (current.model != defaultModel) {
            val updatedAgents = newSettings.agents.map { a ->
                if (a.provider.id == service.id && a.name == service.displayName) a.copy(model = defaultModel) else a
            }
            if (updatedAgents != newSettings.agents) newSettings = newSettings.copy(agents = updatedAgents)
        }
        onSave(newSettings)
    }

    if (showParamsDialog) {
        com.ai.ui.chat.ParametersSelectorDialog(
            aiSettings = aiSettings, selectedIds = selectedParametersIds,
            onConfirm = { selectedParametersIds = it; showParamsDialog = false },
            onDismiss = { showParamsDialog = false }
        )
    }

    // Full-screen overlay for model selection. Using the full-screen overlay pattern (early
    // return with `showModelSelector`) preserves this screen's remember state underneath.
    if (showModelSelector) {
        SelectModelScreen(
            provider = service, aiSettings = aiSettings, currentModel = defaultModel,
            onSelectModel = { defaultModel = it; showModelSelector = false },
            onBack = { showModelSelector = false }, onNavigateHome = onBackToHome,
            // Auto-refresh on entry when the provider's model source is API; the screen
            // shows an inline spinner while the fetch runs.
            onRefresh = if (apiKey.isNotBlank()) ({ onFetchModels(apiKey) }) else null,
            isRefreshing = isLoadingModels,
            onNavigateToProviderModels = { showModelSelector = false; onNavigateToModels() }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = service.displayName, onBackClick = onBackToSettings, onAiClick = onBackToHome)
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // State toggle
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Provider inactive", modifier = Modifier.weight(1f), color = Color.White)
                    Switch(
                        checked = isInactive,
                        onCheckedChange = { inactive ->
                            isInactive = inactive
                            if (inactive) {
                                onProviderStateChange("inactive")
                            } else {
                                scope.launch {
                                    if (apiKey.isNotBlank()) {
                                        val error = onTestApiKey(service, apiKey, defaultModel)
                                        onProviderStateChange(if (error == null) "ok" else "error")
                                    } else {
                                        onProviderStateChange("not-used")
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // API Key
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("API Key", fontWeight = FontWeight.Bold, color = Color.White)
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; testResult = null },
                        label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, colors = AppColors.outlinedFieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (apiKey.isNotBlank()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isTesting = true; testResult = null
                                        val error = onTestApiKey(service, apiKey, defaultModel)
                                        testSuccess = error == null
                                        testResult = error ?: "Connection successful"
                                        onProviderStateChange(if (error == null) "ok" else "error")
                                        isTesting = false
                                    }
                                },
                                enabled = !isTesting, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                            ) { Text(if (isTesting) "Testing..." else "Test", maxLines = 1, softWrap = false) }
                        }
                    }
                    testResult?.let {
                        Text(it, color = if (testSuccess) AppColors.Green else AppColors.Red, fontSize = 12.sp)
                    }
                }
            }

            // Default Model — opens the shared SelectModelScreen overlay.
            Card(
                modifier = Modifier.fillMaxWidth().clickable { showModelSelector = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Default Model", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            text = defaultModel.ifBlank { "Tap to select a model" },
                            fontSize = 12.sp,
                            color = if (defaultModel.isBlank()) AppColors.TextTertiary else AppColors.Blue,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(">", fontSize = 16.sp, color = AppColors.Blue)
                }
            }

            // Parameters
            val pNames = selectedParametersIds.mapNotNull { aiSettings.getParametersById(it)?.name }
            OutlinedButton(
                onClick = { showParamsDialog = true }, modifier = Modifier.fillMaxWidth(),
                colors = if (pNames.isNotEmpty()) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
            ) { Text(if (pNames.isNotEmpty()) "Parameters: ${pNames.joinToString(", ")}" else "Parameters (none)", maxLines = 1, overflow = TextOverflow.Ellipsis) }

            // Models — link to the dedicated per-provider Models screen (AI Setup → Models → this provider)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToModels() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Models", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                    if (modelsCount > 0) {
                        Text("$modelsCount", fontSize = 14.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Text(">", fontSize = 16.sp, color = AppColors.Blue)
                }
            }

            // Per-config overrides — these stay in the user's runtime ProviderConfig.
            CollapsibleCard(title = "Advanced (per-config overrides)", summary = null) {
                OutlinedTextField(
                    value = modelListUrl, onValueChange = { modelListUrl = it },
                    label = { Text("Custom model list URL") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = adminUrl, onValueChange = { adminUrl = it },
                    label = { Text("Admin URL (override)") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }

            // ===== Provider definition (catalog) =====
            // Edits here flow into ProviderRegistry — the same store that loads from
            // assets/setup.json on first launch — so every field that ships in the
            // bundled catalog is editable.

            CollapsibleCard(title = "Definition · Basics", summary = null) {
                OutlinedTextField(value = defDisplayName, onValueChange = { defDisplayName = it },
                    label = { Text("Display name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defBaseUrl, onValueChange = { defBaseUrl = it },
                    label = { Text("Base URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defAdminUrl, onValueChange = { defAdminUrl = it },
                    label = { Text("Admin URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defDefaultModel, onValueChange = { defDefaultModel = it },
                    label = { Text("Default model") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }

            CollapsibleCard(title = "Definition · API", summary = defApiFormat.name) {
                Text("Format", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiFormat.entries.forEach { fmt ->
                        FilterChip(selected = defApiFormat == fmt, onClick = { defApiFormat = fmt },
                            label = { Text(fmt.name, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(value = defChatPath, onValueChange = { defChatPath = it },
                    label = { Text("Chat path") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defModelsPath, onValueChange = { defModelsPath = it },
                    label = { Text("Models path (blank = no list API)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                Text("Model list format", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("object", "array").forEach {
                        FilterChip(selected = defModelListFormat == it,
                            onClick = { defModelListFormat = it }, label = { Text(it) })
                    }
                }
                OutlinedTextField(value = defSeedFieldName, onValueChange = { defSeedFieldName = it },
                    label = { Text("Seed field name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }

            CollapsibleCard(title = "Definition · Models", summary = defDefaultModelSource) {
                Text("Default source", fontSize = 12.sp, color = AppColors.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("API", "MANUAL").forEach {
                        FilterChip(selected = defDefaultModelSource == it,
                            onClick = { defDefaultModelSource = it }, label = { Text(it) })
                    }
                }
                OutlinedTextField(value = defModelFilter, onValueChange = { defModelFilter = it },
                    label = { Text("Model filter regex (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defHardcodedModelsText, onValueChange = { defHardcodedModelsText = it },
                    label = { Text("Hardcoded models (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }

            CollapsibleCard(title = "Definition · Pricing & cost", summary = null) {
                OutlinedTextField(value = defOpenRouterName, onValueChange = { defOpenRouterName = it },
                    label = { Text("OpenRouter name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defLitellmPrefix, onValueChange = { defLitellmPrefix = it },
                    label = { Text("LiteLLM prefix") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defCostTicksDivisor, onValueChange = { defCostTicksDivisor = it },
                    label = { Text("Cost ticks divisor") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Extract API cost", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = defExtractApiCost, onCheckedChange = { defExtractApiCost = it })
                }
            }

            CollapsibleCard(title = "Definition · Features", summary = null) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Supports citations", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = defSupportsCitations, onCheckedChange = { defSupportsCitations = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Supports search recency", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = defSupportsSearchRecency, onCheckedChange = { defSupportsSearchRecency = it })
                }
            }

            CollapsibleCard(title = "Definition · Endpoint rules", summary = "${defEndpointRules.size}") {
                defEndpointRules.forEachIndexed { idx, rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${rule.modelPrefix} → ${rule.endpointType}", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            defEndpointRules = defEndpointRules.toMutableList().apply { removeAt(idx) }
                        }) { Text("✕", color = AppColors.TextTertiary) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = newRulePrefix, onValueChange = { newRulePrefix = it },
                        label = { Text("Prefix") }, singleLine = true,
                        modifier = Modifier.weight(1f), colors = AppColors.outlinedFieldColors())
                    listOf("responses", "chat").forEach {
                        FilterChip(selected = newRuleType == it, onClick = { newRuleType = it },
                            label = { Text(it, fontSize = 11.sp) })
                    }
                }
                Button(
                    onClick = {
                        val p = newRulePrefix.trim()
                        if (p.isNotBlank()) {
                            defEndpointRules = defEndpointRules + EndpointRule(p, newRuleType)
                            newRulePrefix = ""
                        }
                    },
                    enabled = newRulePrefix.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                ) { Text("Add rule", maxLines = 1, softWrap = false) }
            }

            CollapsibleCard(title = "Definition · Storage", summary = "id=${service.id}") {
                Text("ID and prefs key are immutable — changing them would orphan stored API keys, models, and usage statistics.",
                    fontSize = 11.sp, color = AppColors.TextTertiary)
                OutlinedTextField(value = service.id, onValueChange = {},
                    label = { Text("ID") }, singleLine = true, readOnly = true, enabled = false,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = service.prefsKey, onValueChange = {},
                    label = { Text("Prefs key") }, singleLine = true, readOnly = true, enabled = false,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
            }
        }
    }
}
