package com.ai.ui.settings

import androidx.activity.compose.BackHandler
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
    developerMode: Boolean,
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
        items = aiSettings.agents.filter { aiSettings.isProviderActive(it.provider) },
        addLabel = "Add Agent",
        emptyMessage = "No agents configured",
        sortKey = { it.name },
        itemTitle = { it.name },
        itemSubtitle = { "${it.provider.displayName} \u00B7 ${aiSettings.getEffectiveModelForAgent(it)}" },
        onAdd = onAddAgent,
        onEdit = { onEditAgent(it.id) },
        onDelete = { agent -> onSave(aiSettings.removeAgent(agent.id)) },
        onBack = onBackToAiSetup,
        onHome = onBackToHome,
        deleteEntityType = "Agent",
        deleteEntityName = { it.name }
    )
}

@Composable
fun AgentEditScreen(
    agent: Agent?,
    aiSettings: Settings,
    developerMode: Boolean,
    existingNames: Set<String>,
    onTestAiModel: suspend (AppService, String, String) -> String?,
    onFetchModels: (AppService, String) -> Unit,
    onSave: (Agent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val scope = rememberCoroutineScope()
    val isEditing = agent != null

    var name by remember { mutableStateOf(agent?.name ?: "") }
    var selectedProvider by remember { mutableStateOf(agent?.provider ?: AppService.entries.firstOrNull { aiSettings.isProviderActive(it) } ?: AppService.entries.first()) }
    var model by remember { mutableStateOf(agent?.model ?: "") }
    var apiKey by remember { mutableStateOf(agent?.apiKey ?: "") }
    var selectedEndpointId by remember { mutableStateOf(agent?.endpointId) }
    var selectedParamsIds by remember { mutableStateOf(agent?.paramsIds ?: emptyList()) }
    var selectedSystemPromptId by remember { mutableStateOf(agent?.systemPromptId) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
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
            onConfirm = { selectedParamsIds = it; showParamsDialog = false }, onDismiss = { showParamsDialog = false })
    }
    if (showSystemPromptDialog) {
        SystemPromptSelectorDialog(aiSettings = aiSettings, selectedId = selectedSystemPromptId,
            onSelect = { selectedSystemPromptId = it; showSystemPromptDialog = false }, onDismiss = { showSystemPromptDialog = false })
    }

    // Full-screen overlays
    when (overlayMode) {
        1 -> { SelectProviderScreen(aiSettings = aiSettings, onSelectProvider = { selectedProvider = it; model = ""; overlayMode = 0 }, onBack = { overlayMode = 0 }, onNavigateHome = onNavigateHome); return }
        2 -> { SelectModelScreen(provider = selectedProvider, aiSettings = aiSettings, currentModel = model, showDefaultOption = true,
            onSelectModel = { model = it; overlayMode = 0 }, onBack = { overlayMode = 0 }, onNavigateHome = onNavigateHome); return }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TitleBar(title = if (isEditing) "Edit Agent" else "Add Agent", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Agent name") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                isError = name.isNotBlank() && nameError != null,
                supportingText = if (name.isNotBlank() && nameError != null) { { Text(nameError!!, color = AppColors.Red) } } else null
            )

            // Provider selection
            OutlinedButton(onClick = { overlayMode = 1 }, modifier = Modifier.fillMaxWidth()) {
                Text("Provider: ${selectedProvider.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Model selection
            val effectiveModel = model.ifBlank { aiSettings.getModel(selectedProvider) }
            OutlinedButton(onClick = { overlayMode = 2 }, modifier = Modifier.fillMaxWidth()) {
                Text("Model: ${effectiveModel.ifBlank { "(default)" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // API Key (optional override)
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it; testResult = null },
                label = { Text("API Key (optional, overrides provider)") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, colors = AppColors.outlinedFieldColors()
            )

            // Endpoint
            val endpoints = aiSettings.getEndpointsForProvider(selectedProvider)
            if (endpoints.size > 1) {
                val epName = selectedEndpointId?.let { id -> endpoints.find { it.id == id }?.name } ?: "Default"
                OutlinedButton(onClick = {
                    selectedEndpointId = if (selectedEndpointId == null) endpoints.firstOrNull { !it.isDefault }?.id else null
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Endpoint: $epName", maxLines = 1, overflow = TextOverflow.Ellipsis)
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

            // Test
            if (apiKey.isNotBlank() || aiSettings.getApiKey(selectedProvider).isNotBlank()) {
                Button(
                    onClick = {
                        scope.launch {
                            isTesting = true; testResult = null
                            val key = apiKey.ifBlank { aiSettings.getApiKey(selectedProvider) }
                            val error = onTestAiModel(selectedProvider, key, effectiveModel)
                            testSuccess = error == null; testResult = error ?: "Success"
                            isTesting = false
                        }
                    },
                    enabled = !isTesting, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                ) { Text(if (isTesting) "Testing..." else "Test Connection") }
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
        ) { Text(if (isEditing) "Save" else "Create") }
    }
}
