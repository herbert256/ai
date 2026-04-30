package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiFormat
import com.ai.data.AppService
import com.ai.data.ProviderRegistry
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.SelectModelScreen
import com.ai.ui.shared.VisionBadge
import com.ai.ui.shared.WebSearchBadge
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.launch

/** Tiny pill showing the inferred model kind ("chat", "embedding", "rerank", ...) so
 *  users can tell at a glance which entries in a long list are non-chat. Color-coded
 *  for the common kinds and falls back to a neutral gray for anything unexpected. */
@Composable
private fun ModelTypeChip(type: String) {
    val color = when (type) {
        com.ai.data.ModelType.CHAT -> AppColors.Blue
        com.ai.data.ModelType.EMBEDDING -> AppColors.Purple
        com.ai.data.ModelType.RERANK -> AppColors.Indigo
        com.ai.data.ModelType.IMAGE -> AppColors.Orange
        com.ai.data.ModelType.TTS, com.ai.data.ModelType.STT -> AppColors.Green
        com.ai.data.ModelType.MODERATION, com.ai.data.ModelType.CLASSIFY -> AppColors.Red
        else -> AppColors.TextDim
    }
    Text(
        text = type,
        fontSize = 9.sp,
        color = Color.White,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(color.copy(alpha = 0.4f), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Prompt sent to every model by the "Test all models" action — short, deterministic,
 *  cheap, and surfaces shaping issues (refusal, JSON wrapping, mode-locked models). */
private const val MODEL_TEST_PROMPT =
    "This is a test, only return the exact string 'ok', nothing more."

private sealed class ModelTestStatus {
    object Running : ModelTestStatus()
    object Ok : ModelTestStatus()
    data class Fail(val traceFile: String?) : ModelTestStatus()
}

@Composable
private fun ModelTestStatusIcon(
    status: ModelTestStatus?,
    onTraceClick: (String) -> Unit
) {
    when (status) {
        null -> Spacer(modifier = Modifier.width(0.dp))
        ModelTestStatus.Running -> {
            val transition = rememberInfiniteTransition(label = "model-test-hourglass")
            val angle by transition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                label = "model-test-hourglass-rotation"
            )
            Text("⏳", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp).rotate(angle))
        }
        ModelTestStatus.Ok -> Text("✅", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
        is ModelTestStatus.Fail -> {
            val traceFile = status.traceFile
            Text(
                "❌", fontSize = 14.sp,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .then(if (traceFile != null) Modifier.clickable { onTraceClick(traceFile) } else Modifier)
            )
        }
    }
}

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
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onTestSpecificModel: (suspend (String, String) -> Pair<Boolean, String?>)? = null,
    onNavigateToTrace: ((String) -> Unit)? = null
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val config = aiSettings.getProvider(service)
    val apiKey = config.apiKey
    var modelSource by remember { mutableStateOf(config.modelSource) }
    var models by remember { mutableStateOf(config.models) }
    // Inline add/edit state for the Manual CRUD form.
    var manualInput by remember { mutableStateOf("") }
    var editingOriginal by remember { mutableStateOf<String?>(null) }

    // Per-model test state — drives the trailing icon on each row.
    // Statuses: "running", "ok", "fail" (with optional traceFile for the X-link).
    var testStatuses by remember(service.id) { mutableStateOf<Map<String, ModelTestStatus>>(emptyMap()) }
    var testInProgress by remember { mutableStateOf(false) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onFetchModels(apiKey) },
                        enabled = !isLoadingModels,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                    ) { Text(if (isLoadingModels) "Fetching..." else "Fetch Models", maxLines = 1, softWrap = false) }
                    if (onTestSpecificModel != null && models.isNotEmpty()) {
                        Button(
                            onClick = {
                                val targets = models.toList()
                                testStatuses = targets.associateWith { ModelTestStatus.Running }
                                testInProgress = true
                                scope.launch {
                                    val sem = kotlinx.coroutines.sync.Semaphore(5)
                                    kotlinx.coroutines.coroutineScope {
                                        targets.forEach { m ->
                                            launch {
                                                sem.acquire()
                                                try {
                                                    val (ok, trace) = onTestSpecificModel(m, MODEL_TEST_PROMPT)
                                                    testStatuses = testStatuses + (m to if (ok) ModelTestStatus.Ok else ModelTestStatus.Fail(trace))
                                                } finally {
                                                    sem.release()
                                                }
                                            }
                                        }
                                    }
                                    testInProgress = false
                                }
                            },
                            enabled = !testInProgress,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                        ) { Text(if (testInProgress) "Testing..." else "Test all models", maxLines = 1, softWrap = false) }
                    }
                }
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
                        VisionBadge(aiSettings.isVisionCapable(service, modelId))
                        WebSearchBadge(aiSettings.isWebSearchCapable(service, modelId))
                        // getModelType() consults the user's manual overrides first, so
                        // edits in AI Setup → Manual model types overrides surface here
                        // immediately without needing a re-fetch.
                        aiSettings.getModelType(service, modelId)?.let { kind ->
                            ModelTypeChip(kind)
                        }
                        // Test-status icon when a Test all models run has touched this row.
                        ModelTestStatusIcon(
                            status = testStatuses[modelId],
                            onTraceClick = { traceFile ->
                                onNavigateToTrace?.invoke(traceFile)
                            }
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
    // Trace file captured for the most recent failed test, so the failure row can
    // link straight to the request/response that produced the error.
    var testTraceFile by remember { mutableStateOf<String?>(null) }
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
    // One path-state entry per ModelType — provider-level overrides for the global
    // default paths configured in AI Setup → Model Types.
    var defTypePaths by remember(service.id) {
        mutableStateOf(com.ai.data.ModelType.ALL.associateWith { service.typePaths[it] ?: "" })
    }
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

    LaunchedEffect(
        defDisplayName, defBaseUrl, defAdminUrl, defDefaultModel, defOpenRouterName, defApiFormat,
        defTypePaths, defModelsPath, defSeedFieldName, defModelListFormat, defDefaultModelSource,
        defModelFilter, defLitellmPrefix, defCostTicksDivisor, defExtractApiCost,
        defSupportsCitations, defSupportsSearchRecency, defHardcodedModelsText
    ) {
        // Don't push back garbage during the very first composition. Only update if the user
        // actually changed something — i.e. a field differs from its catalog source value.
        val same = defDisplayName == service.displayName &&
            defBaseUrl == service.baseUrl &&
            defAdminUrl == service.adminUrl &&
            defDefaultModel == service.defaultModel &&
            defOpenRouterName == (service.openRouterName ?: "") &&
            defApiFormat == service.apiFormat &&
            defTypePaths.filterValues { it.isNotBlank() } == service.typePaths &&
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
            defHardcodedModelsText == (service.hardcodedModels?.joinToString(", ") ?: "")
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
            typePaths = defTypePaths.mapValues { it.value.trim() }.filterValues { it.isNotBlank() },
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
            defaultModelSource = defDefaultModelSource
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
        // default model — update it if it exists, create it if it doesn't (so a freshly
        // configured provider automatically gets a usable default agent).
        if (current.model != defaultModel && defaultModel.isNotBlank()) {
            val idx = newSettings.agents.indexOfFirst { it.provider.id == service.id && it.name == service.displayName }
            val updatedAgents = if (idx >= 0) {
                newSettings.agents.toMutableList().also { it[idx] = it[idx].copy(model = defaultModel) }
            } else {
                newSettings.agents + Agent(
                    id = java.util.UUID.randomUUID().toString(),
                    name = service.displayName,
                    provider = service,
                    model = defaultModel,
                    apiKey = ""
                )
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
                                        // Resolve the latest AppService from the registry — the
                                        // captured `service` may be stale after auto-save edits to
                                        // apiFormat / typePaths / etc. ran during this screen.
                                        val fresh = AppService.findById(service.id) ?: service
                                        val error = onTestApiKey(fresh, apiKey, defaultModel)
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
                                        isTesting = true; testResult = null; testTraceFile = null
                                        val startedAt = System.currentTimeMillis()
                                        val fresh = AppService.findById(service.id) ?: service
                                        val error = onTestApiKey(fresh, apiKey, defaultModel)
                                        testSuccess = error == null
                                        testResult = error ?: "Connection successful"
                                        // On failure, attach the trace from this run so the
                                        // user can jump to the captured request/response.
                                        testTraceFile = if (error != null) {
                                            com.ai.data.ApiTracer.getTraceFiles()
                                                .firstOrNull { it.timestamp >= startedAt }
                                                ?.filename
                                        } else null
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
                    val tf = testTraceFile
                    if (!testSuccess && tf != null && onNavigateToTrace != null) {
                        TextButton(
                            onClick = { onNavigateToTrace(tf) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Text("Show trace", fontSize = 12.sp, color = AppColors.Blue, maxLines = 1, softWrap = false)
                        }
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

            // Parameters — same blue-card pattern as Default Model / Models so a long
            // list of selected presets wraps onto a second line instead of overflowing
            // the row width.
            val pNames = selectedParametersIds.mapNotNull { aiSettings.getParametersById(it)?.name }
            Card(
                modifier = Modifier.fillMaxWidth().clickable { showParamsDialog = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Parameters", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            text = if (pNames.isNotEmpty()) pNames.joinToString(", ") else "Tap to select",
                            fontSize = 12.sp,
                            color = if (pNames.isEmpty()) AppColors.TextTertiary else AppColors.Blue
                        )
                    }
                    Text(">", fontSize = 16.sp, color = AppColors.Blue)
                }
            }

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
                // Per-type path overrides — one row per ModelType. Blank means "use the
                // default from AI Setup → Model Types" (or the hardcoded fallback if that's
                // also blank). The placeholder shows the eventual fallback so the user
                // sees what will be used if they leave the field empty.
                Text("Type paths", fontSize = 12.sp, color = AppColors.TextTertiary)
                com.ai.data.ModelType.ALL.forEach { type ->
                    val current = defTypePaths[type] ?: ""
                    val placeholder = com.ai.data.ModelType.DEFAULT_PATHS[type] ?: ""
                    OutlinedTextField(
                        value = current,
                        onValueChange = { defTypePaths = defTypePaths + (type to it) },
                        label = { Text(type.replaceFirstChar { c -> c.uppercase() }) },
                        placeholder = { Text(placeholder, fontSize = 11.sp, color = AppColors.TextDim) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedFieldColors()
                    )
                }
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
