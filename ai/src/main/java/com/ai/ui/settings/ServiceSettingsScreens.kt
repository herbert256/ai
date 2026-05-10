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
import com.ai.data.Endpoint
import com.ai.data.MaxTokensRule
import com.ai.data.ModelPattern
import com.ai.data.ProviderRegistry
import com.ai.data.createAppGson
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.google.gson.reflect.TypeToken
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.SelectModelScreen
import com.ai.ui.shared.ReasoningBadge
import com.ai.ui.shared.VisionBadge
import com.ai.ui.shared.WebSearchBadge
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.launch

// ===== JSON helpers for the structured ProviderDefinition fields =====
//
// List<ModelPattern> / List<MaxTokensRule> / List<Endpoint> are
// edited as pretty-printed JSON in multi-line text fields. Empty
// list → empty string (no JSON brackets) so a fresh provider with
// no patterns reads as a plain blank field instead of "[]". Parsers
// return null on syntax failure so the auto-save can fall back to
// the persisted value instead of clobbering it mid-edit.
private fun patternsToJson(list: List<ModelPattern>): String =
    if (list.isEmpty()) "" else createAppGson(prettyPrint = true).toJson(list)

private fun patternsToJsonNullable(list: List<ModelPattern>?): String =
    list?.let { createAppGson(prettyPrint = true).toJson(it) } ?: ""

private fun maxTokensRulesToJson(list: List<MaxTokensRule>): String =
    if (list.isEmpty()) "" else createAppGson(prettyPrint = true).toJson(list)

private fun endpointsToJson(list: List<Endpoint>): String =
    if (list.isEmpty()) "" else createAppGson(prettyPrint = true).toJson(list)

private fun parsePatterns(text: String): List<ModelPattern>? {
    if (text.isBlank()) return emptyList()
    return runCatching {
        val type = object : TypeToken<List<ModelPattern>>() {}.type
        createAppGson().fromJson<List<ModelPattern>>(text, type)
    }.getOrNull()
}

private fun parseMaxTokensRules(text: String): List<MaxTokensRule>? {
    if (text.isBlank()) return emptyList()
    return runCatching {
        val type = object : TypeToken<List<MaxTokensRule>>() {}.type
        createAppGson().fromJson<List<MaxTokensRule>>(text, type)
    }.getOrNull()
}

private fun parseEndpoints(text: String): List<Endpoint>? {
    if (text.isBlank()) return emptyList()
    return runCatching {
        val type = object : TypeToken<List<Endpoint>>() {}.type
        createAppGson().fromJson<List<Endpoint>>(text, type)
    }.getOrNull()
}

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
    onProviderSelected: (AppService) -> Unit,
    onRefreshAllModels: suspend (Settings, Boolean, ((String) -> Unit)?) -> Map<String, Int> = { _, _, _ -> emptyMap() }
) {
    BackHandler { onBackToAiSetup() }
    val scope = rememberCoroutineScope()
    var refreshInProgress by remember { mutableStateOf(false) }
    var refreshProgressText by remember { mutableStateOf("") }
    var refreshResults by remember { mutableStateOf<Map<String, Int>?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(helpTopic = "models", title = "Models", onBackClick = onBackToAiSetup)
        Spacer(modifier = Modifier.height(12.dp))
        // Only show providers with a working API key (state == "ok"), matching the "Active"
        // filter on the Providers screen.
        val activeProviders = AppService.entries
            .filter { aiSettings.getProviderState(it) == "ok" }
            .sortedBy { it.id }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (activeProviders.isEmpty()) {
                Text(
                    "No active providers yet. Set an API key and test it in Providers.",
                    fontSize = 13.sp, color = AppColors.TextTertiary
                )
            }
            activeProviders.forEach { provider ->
                val config = aiSettings.getProvider(provider)
                val model = provider.defaultModel
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
                            Text(provider.id, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (model.isNotBlank()) {
                                Text(model, fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text("$count", fontSize = 14.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
        }

        // Bulk refresh button — same code path as Refresh → Models so a
        // user already on this screen doesn't have to bounce back to
        // Housekeeping. forceRefresh=true matches the Refresh-screen
        // behaviour: cache-validity check is bypassed.
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    refreshInProgress = true
                    refreshProgressText = ""
                    try {
                        refreshResults = onRefreshAllModels(aiSettings, true) { msg -> refreshProgressText = msg }
                    } finally {
                        refreshInProgress = false
                    }
                }
            },
            enabled = !refreshInProgress && activeProviders.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
        ) {
            Text(
                if (refreshInProgress) "Refreshing…" else "Call all API retrieve models lists",
                maxLines = 1, softWrap = false
            )
        }
    }

    if (refreshInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Refreshing models") },
            text = {
                Column {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    if (refreshProgressText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(refreshProgressText, fontSize = 12.sp, color = AppColors.TextTertiary)
                    }
                }
            },
            confirmButton = {}
        )
    }
    refreshResults?.let { results ->
        AlertDialog(
            onDismissRequest = { refreshResults = null },
            title = { Text("Model Refresh Results") },
            text = {
                Column {
                    if (results.isEmpty()) {
                        Text("No providers with API source were eligible for refresh.",
                            fontSize = 13.sp, color = AppColors.TextTertiary)
                    } else {
                        results.entries.sortedBy { it.key }.forEach { (name, count) ->
                            val color = if (count > 0) AppColors.Green else AppColors.Red
                            Text("$name: ${if (count > 0) "$count models" else "failed"}",
                                fontSize = 13.sp, color = color)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { refreshResults = null }) {
                    Text("OK", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

// ===== Per-provider model settings (subset of ProviderSettingsScreen) =====

@Composable
fun ProviderModelSettingsScreen(
    service: AppService,
    aiSettings: Settings,
    isLoadingModels: Boolean = false,
    fetchError: com.ai.viewmodel.FetchModelsError? = null,
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
    // ModelSource lives on AppService.defaultModelSource (single
    // source of truth). Local var mirrors the catalog value; the
    // filter chips below write back via withModelSource →
    // ProviderRegistry.update.
    var modelSource by remember(service.id) { mutableStateOf(aiSettings.getModelSource(service)) }
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
    // Auto-save the model list — modelSource writes through
    // withModelSource (catalog field) on every chip change. The two-way
    // sync above can race with this effect: an external Fetch Models
    // completion mutates config.models → first effect copies it into
    // local `models` → this effect refires and sees the captured
    // `aiSettings` is a recomposition behind, so calling
    // `onSave(aiSettings.withProvider(...))` rolls back any other
    // field that changed in the same window. Guard by skipping the
    // save when local state already matches what's in settings.
    LaunchedEffect(models) {
        if (models == config.models) return@LaunchedEffect
        val current = aiSettings.getProvider(service)
        val updated = current.copy(models = models)
        if (updated != current) onSave(aiSettings.withProvider(service, updated))
    }
    LaunchedEffect(modelSource) {
        if (aiSettings.getModelSource(service) != modelSource) {
            aiSettings.withModelSource(service, modelSource)
        }
    }

    // When switching away from Manual mode, clear any half-typed/edit state to avoid surprises on return.
    LaunchedEffect(modelSource) {
        if (modelSource != ModelSource.MANUAL) { manualInput = ""; editingOriginal = null }
    }

    val foldSubject = com.ai.ui.shared.LocalSubjectToTitleBar.current
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "models_per_provider",
            title = if (foldSubject) service.id else "Models",
            onBackClick = onBack
        )
        if (!foldSubject) {
            Text(
                text = service.id,
                fontSize = 18.sp, color = AppColors.Green,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
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
            // Action row — visible for both API and Manual modes. The
            // Fetch button is API-only (manual lists are user-curated);
            // Test all / Remove failed work against whatever models are
            // currently in the list, regardless of source.
            if (apiKey.isNotBlank() && (models.isNotEmpty() || modelSource == ModelSource.API)) {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (modelSource == ModelSource.API) {
                        Button(
                            onClick = { onFetchModels(apiKey) },
                            enabled = !isLoadingModels,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                        ) { Text(if (isLoadingModels) "Fetching..." else "Fetch Models", maxLines = 1, softWrap = false) }
                    }
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
                                                    // runCatching contains a thrown exception
                                                    // inside a single test instead of letting
                                                    // it propagate up structured-concurrency-
                                                    // style and cancel every sibling. The
                                                    // sibling cancellations would otherwise
                                                    // leave them stuck on Running forever.
                                                    val r = runCatching { onTestSpecificModel(m, MODEL_TEST_PROMPT) }
                                                    val (ok, trace) = r.getOrElse { false to null }
                                                    // Drop late writes for models the user
                                                    // already removed (the dropdown's prune
                                                    // happens on the main thread; we re-check
                                                    // against the current models list).
                                                    if (m in models) {
                                                        testStatuses = testStatuses + (m to if (ok) ModelTestStatus.Ok else ModelTestStatus.Fail(trace))
                                                    }
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
                    // Surface the prune affordance only after a Test all
                    // run has finished and at least one model came back
                    // failing. Removing them updates the local `models`
                    // list, which the auto-save LaunchedEffect picks up
                    // and persists; the matching testStatuses entries
                    // are dropped at the same time so the badge counter
                    // stays accurate.
                    val failedModels = remember(testStatuses) {
                        testStatuses.entries
                            .filter { it.value is ModelTestStatus.Fail }
                            .map { it.key }
                    }
                    if (!testInProgress && failedModels.isNotEmpty()) {
                        Button(
                            onClick = {
                                val drop = failedModels.toSet()
                                models = models.filterNot { it in drop }
                                testStatuses = testStatuses - drop
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
                        ) { Text("Remove ${failedModels.size} failed", maxLines = 1, softWrap = false) }
                    }
                }
                // Inline failure surface for the Fetch Models button — same
                // 🐞 deep-link pattern as the per-provider Test button.
                if (fetchError != null && !isLoadingModels) {
                    com.ai.ui.shared.FetchModelsErrorRow(
                        error = fetchError,
                        onNavigateToTrace = onNavigateToTrace
                    )
                }
            } else if (modelSource == ModelSource.API && apiKey.isBlank()) {
                Text("Set an API key for ${service.id} in Providers to fetch models.", fontSize = 12.sp, color = AppColors.TextTertiary)
            }

            // Manual-only add/update form — placed above the list so the CRUD affordances are obvious.
            if (modelSource == ModelSource.MANUAL) {
                val trimmed = manualInput.trim()
                val isEditing = editingOriginal != null
                val isDuplicate = trimmed.isNotBlank() && trimmed != editingOriginal && trimmed in models
                // Whitespace inside the id breaks the URL-path / JSON-body
                // build at dispatch time. Reject before the user gets a
                // 404 from the provider.
                val hasWhitespace = trimmed.any { it.isWhitespace() }
                val canSubmit = trimmed.isNotBlank() && !isDuplicate && !hasWhitespace
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
                        isError = isDuplicate || hasWhitespace
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
                } else if (hasWhitespace) {
                    Text("Model id can't contain whitespace", fontSize = 12.sp, color = AppColors.Red)
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
                        ReasoningBadge(aiSettings.isReasoningCapable(service, modelId))
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
    fetchError: com.ai.viewmodel.FetchModelsError? = null,
    onBackToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onFetchModels: (String) -> Unit = {},
    /** Suspend variant of [onFetchModels] used by the activation flow.
     *  Returns null on success or an error message on failure. */
    onFetchModelsAwait: suspend (AppService, String) -> String? = { _, _ -> null },
    onTestApiKey: suspend (AppService, String, String) -> String?,
    onProviderStateChange: (String) -> Unit = {},
    onProviderTestedOk: (defaultModel: String) -> Unit = {},
    /** Variant of [onProviderTestedOk] for the activation flow: same
     *  state flip + agent creation, but skips the embedded background
     *  model-list fetch since the activation flow has already done it. */
    onProviderTestedOkNoFetch: (defaultModel: String) -> Unit = onProviderTestedOk,
    /** Called when the user picks a new default model and the API-key
     *  test against that model passes. Drops the existing default
     *  agent (named after the provider) and recreates a fresh one
     *  pointing at the new model, re-adding it to the
     *  "default agents" flock. */
    onReplaceDefaultAgent: (defaultModel: String) -> Unit = {},
    onTestModelWithPrompt: (suspend (String) -> Pair<Boolean, String?>)? = null,
    onNavigateToTrace: ((String) -> Unit)? = null,
    onNavigateToModels: () -> Unit = {},
    /** Open the per-provider help page (ℹ on the TitleBar). The
     *  derived topic id is `provider_<lowercased+alnum-only id>`;
     *  a missing [com.ai.ui.admin.HELP_TOPICS] entry falls through
     *  to the home Help page. */
    onNavigateToHelpTopic: (String) -> Unit = {}
) {
    BackHandler { onBackToSettings() }
    val scope = rememberCoroutineScope()

    val config = aiSettings.getProvider(service)
    var apiKey by remember { mutableStateOf(config.apiKey) }
    // Default model lives on AppService (single source of truth, loaded
    // from assets/providers.json and edited via the picker below). The
    // local var mirrors it; auto-save flows through ProviderRegistry.update
    // in the Definition LaunchedEffect.
    var defaultModel by remember(service.id) { mutableStateOf(service.defaultModel) }
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
    var defBaseUrl by remember(service.id) { mutableStateOf(service.baseUrl) }
    var defAdminUrl by remember(service.id) { mutableStateOf(service.adminUrl) }
    // The catalog default model is the same `defaultModel` var that
    // backs the API Key card's Default Model picker — single source
    // of truth, no shadow state.
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
    // ---- New flag fields ----
    var defAuxHostsText by remember(service.id) { mutableStateOf(service.auxHosts.joinToString(", ")) }
    var defNativeRerankUrl by remember(service.id) { mutableStateOf(service.nativeRerankUrl ?: "") }
    var defNativeModerationUrl by remember(service.id) { mutableStateOf(service.nativeModerationUrl ?: "") }
    var defNativeCapabilityUrl by remember(service.id) { mutableStateOf(service.nativeCapabilityUrl ?: "") }
    var defPricingFromModelList by remember(service.id) { mutableStateOf(service.pricingFromModelList) }
    var defCrossProviderModelList by remember(service.id) { mutableStateOf(service.crossProviderModelList) }
    var defMergeHardcodedModels by remember(service.id) { mutableStateOf(service.mergeHardcodedModels) }
    var defExternalReasoningUntrusted by remember(service.id) { mutableStateOf(service.externalReasoningSignalUntrusted) }
    // List<ModelPattern> / List<MaxTokensRule> / List<Endpoint> are
    // edited as JSON text — verbose for simple cases, but the only
    // sane way to express the structured shape directly. Parsed back
    // on auto-save; un-parseable JSON falls back to the persisted
    // value so the user keeps typing without losing prior state.
    var defResponsesApiPatternsJson by remember(service.id) { mutableStateOf(patternsToJson(service.responsesApiPatterns)) }
    var defReasoningModelPatternsJson by remember(service.id) { mutableStateOf(patternsToJson(service.reasoningModelPatterns)) }
    var defReasoningEffortAcceptPatternsJson by remember(service.id) { mutableStateOf(patternsToJsonNullable(service.reasoningEffortAcceptPatterns)) }
    var defWebSearchModelPatternsJson by remember(service.id) { mutableStateOf(patternsToJson(service.webSearchModelPatterns)) }
    var defAdaptiveThinkingPatternsJson by remember(service.id) { mutableStateOf(patternsToJson(service.adaptiveThinkingPatterns)) }
    var defMaxTokensDefaultsJson by remember(service.id) { mutableStateOf(maxTokensRulesToJson(service.maxTokensDefaults)) }
    var defBuiltInEndpointsJson by remember(service.id) { mutableStateOf(endpointsToJson(service.builtInEndpoints)) }

    LaunchedEffect(
        defBaseUrl, defAdminUrl, defaultModel, defOpenRouterName, defApiFormat,
        defTypePaths, defModelsPath, defSeedFieldName, defModelListFormat, defDefaultModelSource,
        defModelFilter, defLitellmPrefix, defCostTicksDivisor, defExtractApiCost,
        defSupportsCitations, defSupportsSearchRecency, defHardcodedModelsText,
        defAuxHostsText, defNativeRerankUrl, defNativeModerationUrl, defNativeCapabilityUrl,
        defPricingFromModelList, defCrossProviderModelList, defMergeHardcodedModels,
        defExternalReasoningUntrusted, defResponsesApiPatternsJson, defReasoningModelPatternsJson,
        defReasoningEffortAcceptPatternsJson, defWebSearchModelPatternsJson,
        defAdaptiveThinkingPatternsJson, defMaxTokensDefaultsJson, defBuiltInEndpointsJson
    ) {
        // Don't push back garbage during the very first composition. Only update if the user
        // actually changed something — i.e. a field differs from its catalog source value.
        val same = defBaseUrl == service.baseUrl &&
            defAdminUrl == service.adminUrl &&
            defaultModel == service.defaultModel &&
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
            defHardcodedModelsText == (service.hardcodedModels?.joinToString(", ") ?: "") &&
            defAuxHostsText == service.auxHosts.joinToString(", ") &&
            defNativeRerankUrl == (service.nativeRerankUrl ?: "") &&
            defNativeModerationUrl == (service.nativeModerationUrl ?: "") &&
            defNativeCapabilityUrl == (service.nativeCapabilityUrl ?: "") &&
            defPricingFromModelList == service.pricingFromModelList &&
            defCrossProviderModelList == service.crossProviderModelList &&
            defMergeHardcodedModels == service.mergeHardcodedModels &&
            defExternalReasoningUntrusted == service.externalReasoningSignalUntrusted &&
            defResponsesApiPatternsJson == patternsToJson(service.responsesApiPatterns) &&
            defReasoningModelPatternsJson == patternsToJson(service.reasoningModelPatterns) &&
            defReasoningEffortAcceptPatternsJson == patternsToJsonNullable(service.reasoningEffortAcceptPatterns) &&
            defWebSearchModelPatternsJson == patternsToJson(service.webSearchModelPatterns) &&
            defAdaptiveThinkingPatternsJson == patternsToJson(service.adaptiveThinkingPatterns) &&
            defMaxTokensDefaultsJson == maxTokensRulesToJson(service.maxTokensDefaults) &&
            defBuiltInEndpointsJson == endpointsToJson(service.builtInEndpoints)
        if (same) return@LaunchedEffect
        if (defBaseUrl.isBlank()) return@LaunchedEffect
        val hardcoded = defHardcodedModelsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val auxHosts = defAuxHostsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
        // For the JSON-shaped fields, fall back to the existing service value
        // when the user's edit doesn't parse — preserves the persisted shape
        // mid-edit instead of silently dropping it on every keystroke.
        val responsesApiPatterns = parsePatterns(defResponsesApiPatternsJson) ?: service.responsesApiPatterns
        val reasoningModelPatterns = parsePatterns(defReasoningModelPatternsJson) ?: service.reasoningModelPatterns
        val reasoningEffortAcceptPatterns =
            if (defReasoningEffortAcceptPatternsJson.isBlank()) null
            else parsePatterns(defReasoningEffortAcceptPatternsJson) ?: service.reasoningEffortAcceptPatterns
        val webSearchModelPatterns = parsePatterns(defWebSearchModelPatternsJson) ?: service.webSearchModelPatterns
        val adaptiveThinkingPatterns = parsePatterns(defAdaptiveThinkingPatternsJson) ?: service.adaptiveThinkingPatterns
        val maxTokensDefaults = parseMaxTokensRules(defMaxTokensDefaultsJson) ?: service.maxTokensDefaults
        val builtInEndpoints = parseEndpoints(defBuiltInEndpointsJson) ?: service.builtInEndpoints
        ProviderRegistry.update(AppService(
            id = service.id,
            baseUrl = defBaseUrl.trim(),
            adminUrl = defAdminUrl.trim(),
            defaultModel = defaultModel.trim(),
            openRouterName = defOpenRouterName.trim().ifBlank { null },
            apiFormat = defApiFormat,
            typePaths = defTypePaths.mapValues { it.value.trim() }.filterValues { it.isNotBlank() },
            modelsPath = defModelsPath.trim().ifBlank { null },
            seedFieldName = defSeedFieldName.trim().ifBlank { "seed" },
            supportsCitations = defSupportsCitations,
            supportsSearchRecency = defSupportsSearchRecency,
            extractApiCost = defExtractApiCost,
            costTicksDivisor = defCostTicksDivisor.trim().toDoubleOrNull()?.takeIf { it > 0.0 },
            modelListFormat = defModelListFormat,
            // Persist a regex only if it actually compiles. A bad
            // pattern (`*`, `[unclosed`) would otherwise blow up at
            // dispatch time with PatternSyntaxException; dropping
            // it here keeps the auto-save edge non-destructive —
            // the user keeps the typed value in the field, the
            // persisted modelFilter just stays at the last good
            // value until they fix the typo.
            modelFilter = defModelFilter.trim().ifBlank { null }
                ?.let { typed ->
                    if (runCatching { Regex(typed) }.isSuccess) typed else service.modelFilter
                },
            litellmPrefix = defLitellmPrefix.trim().ifBlank { null },
            hardcodedModels = hardcoded.ifEmpty { null },
            defaultModelSource = defDefaultModelSource,
            auxHosts = auxHosts,
            nativeRerankUrl = defNativeRerankUrl.trim().ifBlank { null },
            nativeModerationUrl = defNativeModerationUrl.trim().ifBlank { null },
            nativeCapabilityUrl = defNativeCapabilityUrl.trim().ifBlank { null },
            pricingFromModelList = defPricingFromModelList,
            crossProviderModelList = defCrossProviderModelList,
            mergeHardcodedModels = defMergeHardcodedModels,
            externalReasoningSignalUntrusted = defExternalReasoningUntrusted,
            responsesApiPatterns = responsesApiPatterns,
            reasoningModelPatterns = reasoningModelPatterns,
            reasoningEffortAcceptPatterns = reasoningEffortAcceptPatterns,
            webSearchModelPatterns = webSearchModelPatterns,
            adaptiveThinkingPatterns = adaptiveThinkingPatterns,
            maxTokensDefaults = maxTokensDefaults,
            builtInEndpoints = builtInEndpoints
        ))
    }

    // Model count shown on the Models link — read live from settings so it reflects updates
    // made on the Models sub-screen.
    val modelsCount = aiSettings.getProvider(service).models.size

    // Auto-save the per-user fields this screen edits (apiKey,
    // parametersIds). The default model is part of the catalog now —
    // its writeback runs in the Definition LaunchedEffect above via
    // ProviderRegistry.update.
    LaunchedEffect(apiKey, selectedParametersIds) {
        val current = aiSettings.getProvider(service)
        val updated = current.copy(
            apiKey = apiKey,
            parametersIds = selectedParametersIds
        )
        if (updated == current) return@LaunchedEffect
        onSave(aiSettings.withProvider(service, updated))
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
            onSelectModel = { newModel ->
                val previous = defaultModel
                defaultModel = newModel
                showModelSelector = false
                if (newModel != previous && newModel.isNotBlank() && apiKey.isNotBlank()) {
                    scope.launch {
                        val fresh = AppService.findById(service.id) ?: service
                        val error = onTestApiKey(fresh, apiKey, newModel)
                        if (error == null) onReplaceDefaultAgent(newModel)
                    }
                }
            },
            onBack = { showModelSelector = false }, onNavigateHome = onBackToHome,
            // Auto-refresh on entry when the provider's model source is API; the screen
            // shows an inline spinner while the fetch runs.
            onRefresh = if (apiKey.isNotBlank()) ({ onFetchModels(apiKey) }) else null,
            isRefreshing = isLoadingModels,
            fetchError = fetchError,
            onNavigateToTrace = onNavigateToTrace,
            onNavigateToProviderModels = { showModelSelector = false; onNavigateToModels() }
        )
        return
    }

    val foldSubject = com.ai.ui.shared.LocalSubjectToTitleBar.current
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "provider_edit",
            title = if (foldSubject) service.id else "Provider",
            onBackClick = onBackToSettings,
            onInfo = { onNavigateToHelpTopic(com.ai.ui.admin.providerHelpTopicId(service.id)) }
        )
        if (!foldSubject) {
            Text(
                text = service.id,
                fontSize = 18.sp, color = AppColors.Green,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // State toggle
            val navigateHelp = com.ai.ui.shared.LocalNavigateToHelp.current
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Provider inactive", modifier = Modifier.weight(1f), color = Color.White)
                    Text(
                        text = "❓", fontSize = 14.sp, color = AppColors.Blue,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { navigateHelp("provider_card_state") }
                    )
                    Switch(
                        checked = isInactive,
                        onCheckedChange = { inactive ->
                            isInactive = inactive
                            if (inactive) {
                                onProviderStateChange("inactive")
                            } else {
                                scope.launch {
                                    if (apiKey.isBlank()) {
                                        onProviderStateChange("not-used")
                                        return@launch
                                    }
                                    // Resolve the latest AppService from the registry — the
                                    // captured `service` may be stale after auto-save edits to
                                    // apiFormat / typePaths / etc. ran during this screen.
                                    val fresh = AppService.findById(service.id) ?: service
                                    // Activation gate: model-list fetch + API-key test
                                    // both have to pass before we flip to "ok" and create
                                    // the default agent. Either failure leaves the
                                    // provider in "error" state with no agent created.
                                    val fetchError = onFetchModelsAwait(fresh, apiKey)
                                    if (fetchError != null) {
                                        onProviderStateChange("error")
                                        return@launch
                                    }
                                    val testError = onTestApiKey(fresh, apiKey, defaultModel)
                                    if (testError == null) onProviderTestedOkNoFetch(defaultModel)
                                    else onProviderStateChange("error")
                                }
                            }
                        }
                    )
                }
            }

            // API Key card — credential, then Models / Default Model
            // rows, then Test. Putting the catalog + bound-model rows
            // BETWEEN the key and the Test button keeps the
            // typing → picking → testing flow on one card, top to bottom.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("API Key", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                        Text(
                            text = "❓", fontSize = 14.sp, color = AppColors.Blue,
                            modifier = Modifier.clickable { navigateHelp("provider_card_apikey") }
                        )
                    }
                    var showApiKey by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it; testResult = null },
                        label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, colors = AppColors.outlinedFieldColors(),
                        // Mask the key by default — shoulder-surfing protection.
                        // The eye toggle still lets the user verify the value
                        // when pasting / re-typing.
                        visualTransformation = if (showApiKey)
                            androidx.compose.ui.text.input.VisualTransformation.None
                        else
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            if (apiKey.isNotEmpty()) {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(if (showApiKey) "🙈" else "👁", fontSize = 16.sp)
                                }
                            }
                        }
                    )
                    // Models — picker entry to the dedicated per-provider
                    // Models screen (AI Setup → Models → this provider).
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onNavigateToModels() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Models", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                        if (modelsCount > 0) {
                            Text("$modelsCount", fontSize = 14.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                        Text(">", fontSize = 16.sp, color = AppColors.Blue)
                    }
                    // Default Model — opens the shared SelectModelScreen overlay.
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showModelSelector = true }
                            .padding(vertical = 6.dp),
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                                        // Successful test atomically flips state to "ok"
                                        // AND adds this provider's default agent to the
                                        // "default agents" flock — mirrors Refresh All →
                                        // Default agents but scoped to one provider.
                                        if (error == null) onProviderTestedOk(defaultModel)
                                        else onProviderStateChange("error")
                                        isTesting = false
                                    }
                                },
                                enabled = !isTesting, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                            ) { Text(if (isTesting) "Testing..." else "Test", maxLines = 1, softWrap = false) }
                            Text(
                                "Test uses the default model as set above",
                                fontSize = 11.sp, color = AppColors.TextTertiary
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        testResult?.let {
                            Text(it,
                                color = if (testSuccess) AppColors.Green else AppColors.Red,
                                fontSize = 12.sp, modifier = Modifier.weight(1f))
                        } ?: Spacer(modifier = Modifier.weight(1f))
                        val tf = testTraceFile
                        if (!testSuccess && tf != null && onNavigateToTrace != null && com.ai.data.ApiTracer.isTracingEnabled) {
                            Text("🐞", fontSize = 18.sp,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable { onNavigateToTrace(tf) })
                        }
                    }
                }
            }


            // ===== Provider definition (catalog) =====
            // Edits here flow into ProviderRegistry — the same store that loads from
            // assets/setup.json on first launch — so every field that ships in the
            // bundled catalog is editable.

            CollapsibleCard(
                title = "Basics",
                summary = null,
                helpTopic = "provider_card_basics"
            ) {
                // The provider id (which is also its display label) is
                // shown in the screen's title bar above; this card edits
                // only the catalog values that aren't the identity. Pre-
                // unification builds had a separate "Display name" field
                // here that mapped to the now-removed AppService.displayName
                // — the unification refactor collapsed id + displayName +
                // prefsKey into the single id, so a rename is a Add /
                // Remove operation rather than an in-place edit.
                OutlinedTextField(value = defBaseUrl, onValueChange = { defBaseUrl = it },
                    label = { Text("Base URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                OutlinedTextField(value = defAdminUrl, onValueChange = { defAdminUrl = it },
                    label = { Text("Admin URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors())
                Text(
                    "Default model is set on the API Key card above — it's the same single field the catalog ships and the user edits.",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
            }

            CollapsibleCard(
                title = "API",
                summary = defApiFormat.name,
                helpTopic = "provider_card_api"
            ) {
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

            CollapsibleCard(
                title = "Models",
                summary = defDefaultModelSource,
                helpTopic = "provider_card_models"
            ) {
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

            CollapsibleCard(
                title = "Pricing & cost",
                summary = null,
                helpTopic = "provider_card_pricing"
            ) {
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

            CollapsibleCard(
                title = "Features",
                summary = null,
                helpTopic = "provider_card_features"
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Supports citations", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = defSupportsCitations, onCheckedChange = { defSupportsCitations = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Supports search recency", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = defSupportsSearchRecency, onCheckedChange = { defSupportsSearchRecency = it })
                }
            }

            // ===== New flag fields — explained inline =====

            CollapsibleCard(
                title = "Native APIs",
                summary = null,
                helpTopic = "provider_card_native"
            ) {
                Text(
                    "Optional dedicated endpoints exposed by some providers alongside the OpenAI-compat shim. " +
                        "Leave blank when the provider has no such endpoint — the dispatcher will return an explanatory error to the user instead of routing the call.",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                Text("Aux hosts (comma-separated)", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Alternate hostnames the provider's traffic lands on besides the baseUrl host (e.g. api.cohere.com for Cohere). Used by the trace list's Provider filter so calls hitting these hosts are still attributed to this provider. Example: api.cohere.com",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defAuxHostsText, onValueChange = { defAuxHostsText = it },
                    label = { Text("auxHosts") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Native rerank URL", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Full URL of a Cohere v2/rerank-shaped endpoint. Set when the provider has a dedicated rerank API. Example: https://api.cohere.com/v2/rerank",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defNativeRerankUrl, onValueChange = { defNativeRerankUrl = it },
                    label = { Text("nativeRerankUrl") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Native moderation URL", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Full URL of a Mistral v1/moderations-shaped endpoint. Set when the provider has a dedicated moderation API. Example: https://api.mistral.ai/v1/moderations",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defNativeModerationUrl, onValueChange = { defNativeModerationUrl = it },
                    label = { Text("nativeModerationUrl") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Native capability URL", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Full URL of a Cohere-shaped /v1/models capability listing (with `endpoints` / `supports_vision` / `context_length`). Set when the provider's compat shim strips that data but a separate native host returns it. Example: https://api.cohere.com/v1/models",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defNativeCapabilityUrl, onValueChange = { defNativeCapabilityUrl = it },
                    label = { Text("nativeCapabilityUrl") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )
            }

            CollapsibleCard(
                title = "Capability flags",
                summary = null,
                helpTopic = "provider_card_capability"
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pricing from /models", color = Color.White)
                        Text(
                            "When ON, the provider's /v1/models response carries authoritative pricing (input/output per million tokens) and the fetcher harvests it as a self-report tier. Only Together AI ships this today.",
                            fontSize = 11.sp, color = AppColors.TextTertiary
                        )
                    }
                    Switch(checked = defPricingFromModelList, onCheckedChange = { defPricingFromModelList = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cross-provider model list", color = Color.White)
                        Text(
                            "When ON, this provider's /v1/models response drives pricing + type fan-out into every other provider via the openRouterName prefix. Only OpenRouter does this. Exactly one provider should have this flag set.",
                            fontSize = 11.sp, color = AppColors.TextTertiary
                        )
                    }
                    Switch(checked = defCrossProviderModelList, onCheckedChange = { defCrossProviderModelList = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Merge hardcoded models", color = Color.White)
                        Text(
                            "When ON, the model fetcher unions the persisted Hardcoded Models with the API list. Useful when /v1/models omits valid endpoints (e.g. OpenAI's TTS / image / moderation models aren't in /v1/models).",
                            fontSize = 11.sp, color = AppColors.TextTertiary
                        )
                    }
                    Switch(checked = defMergeHardcodedModels, onCheckedChange = { defMergeHardcodedModels = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("External reasoning signal untrusted", color = Color.White)
                        Text(
                            "When ON, the model fetcher's `reasoning: true` signal from /models metadata is ignored — reasoning capability is decided exclusively by the patterns below. xAI uses this because some always-on reasoning variants reject the reasoning_effort parameter.",
                            fontSize = 11.sp, color = AppColors.TextTertiary
                        )
                    }
                    Switch(checked = defExternalReasoningUntrusted, onCheckedChange = { defExternalReasoningUntrusted = it })
                }
            }

            CollapsibleCard(
                title = "Model patterns",
                summary = null,
                helpTopic = "provider_card_patterns"
            ) {
                Text(
                    "Each pattern matches against modelId.lowercase(). Set any combination of `exact`, `prefix`, `contains`, `suffix` — match succeeds when EVERY non-null part matches. Empty list = feature off for this provider; the field stays blank in the asset bundle.",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )

                Text("Responses API patterns", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Models routed to OpenAI-style /v1/responses instead of /v1/chat/completions. Example: [{\"prefix\":\"gpt-5\"},{\"prefix\":\"o3\"}]",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defResponsesApiPatternsJson, onValueChange = { defResponsesApiPatternsJson = it },
                    label = { Text("responsesApiPatterns (JSON)") },
                    minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Reasoning model patterns", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Models that gate the 🧠 reasoning badge + thinking dispatch path. Example: [{\"contains\":\"opus-4\"},{\"contains\":\"sonnet-4\"}]",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defReasoningModelPatternsJson, onValueChange = { defReasoningModelPatternsJson = it },
                    label = { Text("reasoningModelPatterns (JSON)") },
                    minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Reasoning effort accept patterns (optional)", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Models that accept the reasoning_effort request parameter. Leave BLANK to fall back to reasoningModelPatterns. Set to a narrower list when always-on reasoning variants reject the param. Example: [{\"prefix\":\"grok-3\"},{\"exact\":\"grok-4\"},{\"suffix\":\"-reasoning\"}]",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defReasoningEffortAcceptPatternsJson, onValueChange = { defReasoningEffortAcceptPatternsJson = it },
                    label = { Text("reasoningEffortAcceptPatterns (JSON, optional)") },
                    minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Web-search model patterns", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Models that gate the 🌐 web-search tool descriptor in the request body. Example: [{\"contains\":\"claude-3-5\"},{\"contains\":\"opus-4\"}]",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defWebSearchModelPatternsJson, onValueChange = { defWebSearchModelPatternsJson = it },
                    label = { Text("webSearchModelPatterns (JSON)") },
                    minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Adaptive thinking patterns", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Anthropic-only — Claude builds requiring the newer `thinking.type:adaptive` + `output_config.effort` request shape (Claude Opus 4.7+). Older 3.7 / 4.x models still use the budget_tokens shape and should not appear here. Example: [{\"contains\":\"claude-opus-4-7\"}]",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defAdaptiveThinkingPatternsJson, onValueChange = { defAdaptiveThinkingPatternsJson = it },
                    label = { Text("adaptiveThinkingPatterns (JSON)") },
                    minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )

                Text("Max-tokens defaults", fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Text(
                    "Per-family default max_tokens used when the agent didn't set one. Anthropic-only — Anthropic requires max_tokens on every request and the cap differs by family. First matching rule wins (top-down). Example: [{\"pattern\":{\"contains\":\"opus-4\"},\"maxTokens\":32000},{\"pattern\":{\"contains\":\"sonnet-4\"},\"maxTokens\":8192}]",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defMaxTokensDefaultsJson, onValueChange = { defMaxTokensDefaultsJson = it },
                    label = { Text("maxTokensDefaults (JSON)") },
                    minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )
            }

            CollapsibleCard(
                title = "Built-in endpoints",
                summary = null,
                helpTopic = "provider_card_endpoints"
            ) {
                Text(
                    "Endpoints the user can pick between for this provider (e.g. OpenAI's Chat Completions vs Responses API). Empty list → a single synthesised default is used. Each entry is `{id, name, url, isDefault}` — the first `isDefault: true` shows up first in the picker. Example: [{\"id\":\"openai-chat\",\"name\":\"Chat Completions\",\"url\":\"https://api.openai.com/v1/chat/completions\",\"isDefault\":true},{\"id\":\"openai-responses\",\"name\":\"Responses API\",\"url\":\"https://api.openai.com/v1/responses\"}]",
                    fontSize = 11.sp, color = AppColors.TextTertiary
                )
                OutlinedTextField(
                    value = defBuiltInEndpointsJson, onValueChange = { defBuiltInEndpointsJson = it },
                    label = { Text("builtInEndpoints (JSON)") },
                    minLines = 3, maxLines = 12,
                    modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedFieldColors()
                )
            }

            // Parameters — same blue-card pattern as Default Model / Models so a long
            // list of selected presets wraps onto a second line instead of overflowing
            // the row width. Sits at the bottom because it's a power-user preset
            // (not part of the basic API-key + model setup flow).
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
        }
    }
}
