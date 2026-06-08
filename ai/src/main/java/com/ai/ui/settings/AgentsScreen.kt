package com.ai.ui.settings

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.withContext
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.*
import com.ai.ui.shared.ParametersSelectScreen
import com.ai.ui.shared.SystemPromptSelectScreen
import com.ai.ui.shared.*
import kotlinx.coroutines.launch

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
    onAddEndpoint: (AppService, com.ai.model.Endpoint) -> Unit = { _, _ -> },
    /** Optional 👁 view-screen hook. AppNavHost wires it to
     *  navController.navigate(NavRoutes.aiAgentView(agentId)); back
     *  pops back to this Edit screen via Jetpack Nav. */
    onOpenView: (() -> Unit)? = null,
    /** 🗑 delete this agent (Setup → Workers → Agents edit). Null hides it. */
    onDelete: (() -> Unit)? = null
) {
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
        Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            TitleBar(helpTopic = "agent_edit", title = "Agent", subject = "This agent's model, prompt & settings", onBackClick = onBack)
            Text(
                "No providers configured. Add a provider in Settings → Provider before creating an Agent.",
                color = AppColors.TextSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        return
    }

    var resetTick by remember { mutableStateOf(0) }
    var name by remember(resetTick) { mutableStateOf(agent?.name ?: "") }
    var selectedProvider by remember(resetTick) { mutableStateOf(initialProvider) }
    var model by remember(resetTick) { mutableStateOf(agent?.model ?: "") }
    var selectedEndpointId by remember(resetTick) { mutableStateOf(agent?.endpointId) }
    var selectedParamsIds by remember(resetTick) { mutableStateOf(agent?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember(resetTick) { mutableStateOf(agent?.systemPromptId) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var lastTraceFile by remember { mutableStateOf<String?>(null) }
    // LiteLLM-derived endpoints the user picked in this session but which
    // aren't persisted yet — materialised onto the provider only when the
    // agent is actually saved, so backing out doesn't leave orphans.
    var pendingEndpoints by remember(resetTick) { mutableStateOf<List<Pair<AppService, com.ai.model.Endpoint>>>(emptyList()) }
    var showParamsDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    // Overlay: 0=none, 1=provider, 2=model
    var overlayMode by remember { mutableIntStateOf(0) }

    val dup = com.ai.ui.shared.rememberDuplicateMode(
        isEditingExisting = agent != null,
        onDuplicate = { name = "$name-copy" }
    )
    val isAddMode = dup.isAddMode
    // When duplicating from an Edit, the original's name has to count
    // as a collision so the user can't save `<name>-copy` over the
    // top of itself or accidentally land on the source row's name.
    // existingNames is built by the caller with the source row's name
    // excluded; add it back when we've flipped into duplicate mode.
    val effectiveExistingNames = if (isAddMode && agent != null) {
        existingNames + agent.name.lowercase()
    } else existingNames

    val nameError = when {
        name.isBlank() -> "Name is required"
        name.lowercase() in effectiveExistingNames -> "Name already exists"
        else -> null
    }

    if (showParamsDialog) {
        // Dedupe in case the picker handed back the same id twice — a
        // duplicate-id list silently double-applies the same preset.
        ParametersSelectScreen(aiSettings = aiSettings, selectedIds = selectedParamsIds,
            onConfirm = { selectedParamsIds = it.distinct() },
            onBack = { showParamsDialog = false }, onNavigateHome = onNavigateHome)
        return
    }
    if (showSystemPromptDialog) {
        SystemPromptSelectScreen(aiSettings = aiSettings, selectedId = selectedSystemPromptId,
            onSelect = { selectedSystemPromptId = it },
            onBack = { showSystemPromptDialog = false }, onNavigateHome = onNavigateHome)
        return
    }

    // Full-screen overlays
    when (overlayMode) {
        1 -> { SelectProviderScreen(aiSettings = aiSettings, onSelectProvider = { selectedProvider = it; model = ""; overlayMode = 0 }, onBack = { overlayMode = 0 }, onNavigateHome = onNavigateHome); return }
        2 -> {
            val effectiveKey = aiSettings.getApiKey(selectedProvider)
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

    fun persistSelectedEndpoint() {
        pendingEndpoints.firstOrNull { it.second.id == selectedEndpointId }
            ?.let { (provider, ep) -> onAddEndpoint(provider, ep) }
    }
    val agentId = remember { java.util.UUID.randomUUID().toString() }
    val current = if (nameError == null)
        Agent(if (isAddMode) agentId else agent!!.id, name.trim(), selectedProvider, model, "", selectedEndpointId, selectedParamsIds, selectedSystemPromptId)
    else null
    // Back confirms "Discard changes?" when edited; the Save button bypasses it.
    val back = com.ai.ui.shared.rememberConfirmedBack(current, onBack)
    BackHandler { back() }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "agent_edit",
            title = if (isAddMode) "Add Agent" else "Edit Agent",
            subject = name,
            onBackClick = back,
            // 👁 only visible on Edit (the View screen needs an
            // existing agent id) — null in Add mode.
            onOpenView = if (!isAddMode) onOpenView else null,
            // 👯 duplicate into a new agent (copy-on-edit flow), 🗑 delete it —
            // both hidden once the screen flips into copy/add mode.
            onCopyReport = dup.copyTrigger,
            onDelete = if (isAddMode) null else onDelete,
            onClear = { resetTick++ },
            onParameters = { showParamsDialog = true },
            onSystemPrompt = { showSystemPromptDialog = true }
        )
        // Save / Create CTA hoisted to the top — the form below can be long
        // enough to push a bottom button out of reach. Both persist + close.
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { persistSelectedEndpoint(); onSave(current!!); onBack() },
            enabled = current != null,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(if (isAddMode) "Create" else "Save", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Agent name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.DangerAccent) } } else null
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

            // Endpoint — combines configured per-provider endpoints with
            // any extra paths LiteLLM lists in `supported_endpoints` for
            // the selected model. Picking a LiteLLM-derived option
            // materializes a real Endpoint via onAddEndpoint so it persists
            // on the provider's endpoint list.
            // Persisted endpoints plus any not-yet-saved LiteLLM picks for
            // THIS provider so the just-picked option stays selectable.
            val endpoints = aiSettings.getEndpointsForProvider(selectedProvider) +
                pendingEndpoints.filter { it.first.id == selectedProvider.id }.map { it.second }
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
                                        // Defer persistence until the agent is
                                        // saved (see pendingEndpoints) — avoids
                                        // orphaning the endpoint on a cancelled edit.
                                        pendingEndpoints = pendingEndpoints + (selectedProvider to ep)
                                        selectedEndpointId = newId
                                        endpointMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // System prompt + parameters now live on the bottom-bar
            // 🎭 / 🌡️ icons (wired on the TitleBar above) — the inline
            // buttons were removed.
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
                        "${com.ai.data.MetadataIconsHolder.current.warningPlain} ${selectedProvider.id} / $effectiveModel does not accept system messages — the system prompt may be ignored or folded into the user message at dispatch time.",
                        fontSize = 11.sp, color = AppColors.DangerAccent
                    )
                }
            }

            // Test
            if (aiSettings.getApiKey(selectedProvider).isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isTesting = true; testResult = null; lastTraceFile = null
                                val key = aiSettings.getApiKey(selectedProvider)
                                // Snapshot the trace folder so we can identify the file produced by THIS test.
                                // Trace enumeration parses every file on cold cache — push to IO so a large
                                // trace dir doesn't hitch the picker animation.
                                val before = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.ai.data.ApiTracer.getTraceFiles().firstOrNull()?.timestamp ?: 0L
                                }
                                val error = onTestAiModel(selectedProvider, key, effectiveModel)
                                testSuccess = error == null
                                testResult = error ?: "OK"
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
                        enabled = !isTesting, colors = AppColors.outlinedButtonColors()
                    ) { Text(if (isTesting) "Testing..." else "Test Agent", maxLines = 1, softWrap = false) }

                    val traceFile = lastTraceFile
                    if (traceFile != null && onNavigateToTrace != null && com.ai.data.ApiTracer.ladybugLinksEnabled) {
                        Text(com.ai.data.MetadataIconsHolder.current.traces, fontSize = 22.sp,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .clickable { onNavigateToTrace(traceFile) })
                    }
                }
                testResult?.let { Text(it, color = if (testSuccess) AppColors.SuccessAccent else AppColors.DangerAccent, fontSize = 12.sp) }
            }
        }
    }
}
