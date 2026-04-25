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
import com.ai.data.AppService
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
    onCreateAgent: () -> Unit = {},
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
        if (updated != current) onSave(aiSettings.withProvider(service, updated))
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
            isRefreshing = isLoadingModels
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
                        Button(
                            onClick = onCreateAgent,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                        ) { Text("Create Agent", maxLines = 1, softWrap = false) }
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

            // Model List URL
            CollapsibleCard(title = "Advanced", summary = null) {
                OutlinedTextField(
                    value = modelListUrl, onValueChange = { modelListUrl = it },
                    label = { Text("Custom model list URL") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
                OutlinedTextField(
                    value = adminUrl, onValueChange = { adminUrl = it },
                    label = { Text("Admin URL") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = AppColors.outlinedFieldColors()
                )
            }
        }
    }
}
