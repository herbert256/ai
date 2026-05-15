package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.withContext
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.*
import com.ai.ui.chat.ParametersSelectorDialog
import com.ai.ui.chat.SystemPromptSelectorDialog
import com.ai.ui.shared.*
import kotlinx.coroutines.launch

@Composable
fun AgentsScreen(
    aiSettings: Settings,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (Settings) -> Unit,
    onTestAiModel: suspend (AppService, String, String) -> String? = { _, _, _ -> null },
    onFetchModels: (AppService, String) -> Unit = { _, _ -> },
    onAddAgent: () -> Unit,
    onEditAgent: (String) -> Unit
) {
    CrudListScreen(
        title = "Agents",
        helpTopic = "agents_list",
        // Show every saved agent regardless of provider state. Hiding
        // agents whose provider is currently inactive (the previous
        // filter `isProviderActive(it.provider)`) gave the user no way
        // to delete or edit those rows \u2014 they remain referenced from
        // flocks / swarms / saved reports and silently kept eating
        // settings storage. Inactive providers still surface in the
        // subtitle so the user can see why an agent might not be
        // runnable right now.
        items = aiSettings.agents,
        addLabel = "Add Agent",
        emptyMessage = "No agents configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { agent ->
            val active = aiSettings.isProviderActive(agent.provider)
            val model = aiSettings.getEffectiveModelForAgent(agent)
            val tail = if (active) "" else " \u00B7 (inactive)"
            "${agent.provider.id} \u00B7 $model$tail"
        },
        onAdd = onAddAgent,
        onEdit = { onEditAgent(it.id) },
        onDelete = { agent -> onSave(aiSettings.removeAgent(agent.id)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Agent",
        deleteEntityName = { it.name },
        itemKey = { it.id }
    )
}

@Composable
fun AgentEditScreen(
    agent: Agent?,
    aiSettings: Settings,
    existingNames: Set<String>,
    onTestAiModel: suspend (AppService, String, String) -> String?,
    onFetchModels: (AppService, String) -> Unit,
    onSave: (Agent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    loadingModelsFor: Set<AppService> = emptySet(),
    fetchModelsErrors: Map<String, com.ai.viewmodel.FetchModelsError> = emptyMap(),
    onNavigateToTrace: ((String) -> Unit)? = null,
    /** Optional callback fired when the agent picks a LiteLLM-listed
     *  endpoint that isn't yet in the provider's configured endpoints —
     *  the parent should append it to aiSettings.endpoints. Without this
     *  the LiteLLM choices are still selectable but won't persist beyond
     *  this edit session. */
    onAddEndpoint: (AppService, com.ai.model.Endpoint) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val isEditing = agent != null

    // Empty-registry guard: ProviderRegistry.remove(id) lets the user
    // wipe every provider via the admin UI, which would crash the
    // .first() fallback below. Bail to a placeholder so they can
    // navigate back instead of seeing a NoSuchElementException.
    val initialProvider = agent?.provider
        ?: AppService.entries.firstOrNull { aiSettings.isProviderActive(it) }
        ?: AppService.entries.firstOrNull()
    if (initialProvider == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            TitleBar(helpTopic = "agent_edit", title = "Agent", onBackClick = onBack)
            Text(
                "No providers configured. Add a provider in Settings → Provider before creating an Agent.",
                color = AppColors.TextSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        return
    }

    var name by remember { mutableStateOf(agent?.name ?: "") }
    var selectedProvider by remember { mutableStateOf(initialProvider) }
    var model by remember { mutableStateOf(agent?.model ?: "") }
    var apiKey by remember { mutableStateOf(agent?.apiKey ?: "") }
    var selectedEndpointId by remember { mutableStateOf(agent?.endpointId) }
    var selectedParamsIds by remember { mutableStateOf(agent?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember { mutableStateOf(agent?.systemPromptId) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var lastTraceFile by remember { mutableStateOf<String?>(null) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    // Overlay: 0=none, 1=provider, 2=model
    var overlayMode by remember { mutableIntStateOf(0) }

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in existingNames -> "Name already exists"
        else -> null
    }

    if (showParamsDialog) {
        ParametersSelectorDialog(aiSettings = aiSettings, selectedIds = selectedParamsIds,
            // Dedupe in case the dialog handed back the same id twice
            // (the picker has no internal de-duplication on Confirm,
            // and a duplicate-id list silently double-applies the
            // same preset at runtime).
            onConfirm = { selectedParamsIds = it.distinct(); showParamsDialog = false }, onDismiss = { showParamsDialog = false })
    }
    if (showSystemPromptDialog) {
        SystemPromptSelectorDialog(aiSettings = aiSettings, selectedId = selectedSystemPromptId,
            onSelect = { selectedSystemPromptId = it; showSystemPromptDialog = false }, onDismiss = { showSystemPromptDialog = false })
    }

    // Full-screen overlays
    when (overlayMode) {
        1 -> { SelectProviderScreen(aiSettings = aiSettings, onSelectProvider = { selectedProvider = it; model = ""; overlayMode = 0 }, onBack = { overlayMode = 0 }, onNavigateHome = onNavigateHome); return }
        2 -> {
            val effectiveKey = apiKey.ifBlank { aiSettings.getApiKey(selectedProvider) }
            SelectModelScreen(
                provider = selectedProvider, aiSettings = aiSettings, currentModel = model, showDefaultOption = true,
                onSelectModel = { model = it; overlayMode = 0 },
                onBack = { overlayMode = 0 }, onNavigateHome = onNavigateHome,
                onRefresh = if (effectiveKey.isNotBlank()) ({ onFetchModels(selectedProvider, effectiveKey) }) else null,
                isRefreshing = selectedProvider in loadingModelsFor,
                fetchError = fetchModelsErrors[selectedProvider.id],
                onNavigateToTrace = onNavigateToTrace
            )
            return
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "agent_edit",
            title = if (!isEditing) "Add Agent" else "Edit Agent",
            subject = name,
            onBackClick = onBack
        )

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Agent name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            // Provider selection
            OutlinedButton(onClick = { overlayMode = 1 }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                Text("Provider: ${selectedProvider.id}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Model selection
            val effectiveModel = model.ifBlank { aiSettings.getModel(selectedProvider) }
            OutlinedButton(onClick = { overlayMode = 2 }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                Text("Model: ${effectiveModel.ifBlank { "(default)" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // API Key (optional override)
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it; testResult = null },
                label = { Text("API Key (optional, overrides provider)") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors()
            )

            // Endpoint — combines configured per-provider endpoints with
            // any extra paths LiteLLM lists in `supported_endpoints` for
            // the selected model. Picking a LiteLLM-derived option
            // materializes a real Endpoint via onAddEndpoint so it persists
            // on the provider's endpoint list.
            val endpoints = aiSettings.getEndpointsForProvider(selectedProvider)
            val effModel = model.ifBlank { aiSettings.getModel(selectedProvider) }
            val litellmPaths = remember(selectedProvider, effModel) {
                if (effModel.isNotBlank()) com.ai.data.PricingCache.liteLLMSupportedEndpoints(selectedProvider, effModel) ?: emptyList()
                else emptyList()
            }
            val knownUrls = endpoints.map { it.url }.toSet()
            val litellmExtras = litellmPaths.mapNotNull { path ->
                val cleaned = path.trimStart('/')
                val base = selectedProvider.baseUrl.trimEnd('/')
                val full = "$base/$cleaned"
                if (full in knownUrls) null else path to full
            }
            if (endpoints.size > 1 || litellmExtras.isNotEmpty()) {
                var endpointMenuOpen by remember { mutableStateOf(false) }
                val epLabel = selectedEndpointId?.let { id -> endpoints.find { it.id == id }?.name } ?: "Default"
                Box {
                    OutlinedButton(
                        onClick = { endpointMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Endpoint: $epLabel", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(expanded = endpointMenuOpen, onDismissRequest = { endpointMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Default") },
                            onClick = { selectedEndpointId = null; endpointMenuOpen = false }
                        )
                        endpoints.filter { !it.isDefault }.forEach { ep ->
                            DropdownMenuItem(
                                text = { Text(ep.name) },
                                onClick = { selectedEndpointId = ep.id; endpointMenuOpen = false }
                            )
                        }
                        if (litellmExtras.isNotEmpty()) {
                            HorizontalDivider()
                            litellmExtras.forEach { (path, full) ->
                                DropdownMenuItem(
                                    text = { Text("LiteLLM: $path") },
                                    onClick = {
                                        val newId = "litellm-${java.util.UUID.randomUUID()}"
                                        val ep = com.ai.model.Endpoint(id = newId, name = "LiteLLM $path", url = full, isDefault = false)
                                        onAddEndpoint(selectedProvider, ep)
                                        selectedEndpointId = newId
                                        endpointMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // System prompt + parameters
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val spName = selectedSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.name }
                OutlinedButton(
                    onClick = { showSystemPromptDialog = true }, modifier = Modifier.weight(1f),
                    colors = if (spName != null) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
                ) { Text(spName ?: "System Prompt", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val pNames = selectedParamsIds.mapNotNull { aiSettings.getParametersById(it)?.name }
                OutlinedButton(
                    onClick = { showParamsDialog = true }, modifier = Modifier.weight(1f),
                    colors = if (pNames.isNotEmpty()) ButtonDefaults.outlinedButtonColors(containerColor = AppColors.Purple.copy(alpha = 0.2f)) else ButtonDefaults.outlinedButtonColors()
                ) { Text(if (pNames.isNotEmpty()) pNames.joinToString(", ") else "Parameters", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            // Per-litellm: warn when the model is known not to accept system
            // messages — anything chosen on either the System Prompt button
            // above or via the Parameters preset's systemPrompt field would
            // otherwise be silently folded or dropped at the dispatch layer.
            run {
                val spActive = selectedSystemPromptId != null ||
                    selectedParamsIds.any { aiSettings.getParametersById(it)?.systemPrompt?.isNotBlank() == true }
                if (!spActive) return@run
                val supportsSystem = if (effectiveModel.isNotBlank())
                    com.ai.data.PricingCache.liteLLMSupportsSystemMessages(selectedProvider, effectiveModel)
                else null
                if (supportsSystem == false) {
                    Text(
                        "⚠ ${selectedProvider.id} / $effectiveModel does not accept system messages — the system prompt may be ignored or folded into the user message at dispatch time.",
                        fontSize = 11.sp, color = AppColors.Red
                    )
                }
            }

            // Test
            if (apiKey.isNotBlank() || aiSettings.getApiKey(selectedProvider).isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                isTesting = true; testResult = null; lastTraceFile = null
                                val key = apiKey.ifBlank { aiSettings.getApiKey(selectedProvider) }
                                // Snapshot the trace folder so we can identify the file produced by THIS test.
                                // Trace enumeration parses every file on cold cache — push to IO so a large
                                // trace dir doesn't hitch the picker animation.
                                val before = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.ai.data.ApiTracer.getTraceFiles().firstOrNull()?.timestamp ?: 0L
                                }
                                val error = onTestAiModel(selectedProvider, key, effectiveModel)
                                // Filter the post-test traces by hostname (matches the dispatcher path's
                                // own filter in fetchModels error reporting). Without the host correlation
                                // a concurrent flow's trace landing in the same window would hijack the row.
                                val providerHost = runCatching {
                                    java.net.URI(selectedProvider.baseUrl).host?.lowercase()
                                }.getOrNull()
                                lastTraceFile = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.ai.data.ApiTracer.getTraceFiles().firstOrNull {
                                        it.timestamp > before &&
                                            (providerHost == null || it.hostname.equals(providerHost, ignoreCase = true))
                                    }?.filename
                                }
                                isTesting = false
                            }
                        },
                        enabled = !isTesting, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                    ) { Text(if (isTesting) "Testing..." else "Test Agent", maxLines = 1, softWrap = false) }

                    val traceFile = lastTraceFile
                    if (traceFile != null && onNavigateToTrace != null && com.ai.data.ApiTracer.isTracingEnabled) {
                        Text("🐞", fontSize = 22.sp,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .clickable { onNavigateToTrace(traceFile) })
                    }
                }
                testResult?.let { Text(it, color = if (testSuccess) AppColors.Green else AppColors.Red, fontSize = 12.sp) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val id = agent?.id ?: java.util.UUID.randomUUID().toString()
                onSave(Agent(id, name.trim(), selectedProvider, model, apiKey, selectedEndpointId, selectedParamsIds, selectedSystemPromptId))
            },
            enabled = nameError == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text(if (isEditing) "Save" else "Create", maxLines = 1, softWrap = false) }
    }
}
