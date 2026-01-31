package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService
import kotlinx.coroutines.launch

/**
 * AI Agents screen - CRUD for agent configurations.
 */
@Composable
fun AiAgentsScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onFetchModels: (AiService, String) -> Unit = { _, _ -> }
) {
    var showAddScreen by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<AiAgent?>(null) }
    var copyingAgent by remember { mutableStateOf<AiAgent?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AiAgent?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Model selection overlay state
    var showModelSelectForProvider by remember { mutableStateOf<AiService?>(null) }
    var modelSelectCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val modelSelectProvider = showModelSelectForProvider
    if (modelSelectProvider != null) {
        SelectModelScreen(
            provider = modelSelectProvider,
            aiSettings = aiSettings,
            currentModel = "",
            showDefaultOption = true,
            onSelectModel = { model ->
                modelSelectCallback?.invoke(model)
                showModelSelectForProvider = null
            },
            onBack = { showModelSelectForProvider = null },
            onNavigateHome = onBackToHome
        )
    } else if (showAddScreen || editingAgent != null || copyingAgent != null) {
        // For copy mode, create a template agent with new ID and "(Copy)" suffix
        val dialogAgent = copyingAgent?.let { agent ->
            agent.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = "${agent.name} (Copy)"
            )
        } ?: editingAgent
        val isEditMode = editingAgent != null
        val editingAgentId = editingAgent?.id

        AgentEditScreen(
            agent = dialogAgent,
            aiSettings = aiSettings,
            developerMode = developerMode,
            existingNames = aiSettings.agents.map { it.name }.toSet(),
            onTestAiModel = onTestAiModel,
            onFetchModelsForProvider = onFetchModels,
            onSave = { newAgent ->
                val newAgents = if (isEditMode && editingAgentId != null) {
                    aiSettings.agents.map { if (it.id == editingAgentId) newAgent else it }
                } else {
                    aiSettings.agents + newAgent
                }
                onSave(aiSettings.copy(agents = newAgents))
                showAddScreen = false
                editingAgent = null
                copyingAgent = null
            },
            onBack = {
                showAddScreen = false
                editingAgent = null
                copyingAgent = null
            },
            onNavigateHome = onBackToHome,
            onNavigateToSelectModel = { provider, callback ->
                modelSelectCallback = callback
                showModelSelectForProvider = provider
            }
        )
    } else {
        // Agent list screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AiTitleBar(
                title = "AI Agents",
                onBackClick = onBackToAiSetup,
                onAiClick = onBackToHome
            )

            // Check if any provider has an API key configured
            val hasAnyApiKey = aiSettings.hasAnyApiKey()

            if (!hasAnyApiKey) {
                // Show error if no API keys configured
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4A2A2A)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "X", fontSize = 20.sp, color = Color(0xFFFF8080))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "No API keys configured. Go to Settings -> AI Setup -> AI Providers to add an API key.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF8080)
                        )
                    }
                }
            } else {
                // Add button
                Button(
                    onClick = { showAddScreen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("+ Add Agent")
                }

                // Search box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search agents...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6B9BFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    ),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Text("✕", color = Color(0xFF888888))
                            }
                        }
                    }
                )

                // Agent list - filter to agents with active providers and apply search
                val baseAgents = aiSettings.agents.filter {
                    aiSettings.isProviderActive(it.provider, developerMode)
                }
                val visibleAgents = if (searchQuery.isBlank()) {
                    baseAgents
                } else {
                    val query = searchQuery.lowercase()
                    baseAgents.filter { agent ->
                        agent.name.lowercase().contains(query) ||
                        agent.provider.displayName.lowercase().contains(query) ||
                        agent.model.lowercase().contains(query)
                    }
                }
                if (baseAgents.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No agents configured",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFAAAAAA)
                            )
                            Text(
                                text = "Add an agent to start using AI analysis",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF888888)
                            )
                        }
                    }
                } else if (visibleAgents.isEmpty()) {
                    // Search returned no results
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No agents match \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFAAAAAA)
                            )
                        }
                    }
                } else {
                    visibleAgents.sortedBy { it.name.lowercase() }.forEach { agent ->
                        AgentListItem(
                            agent = agent,
                            onEdit = { editingAgent = agent },
                            onCopy = { copyingAgent = agent },
                            onDelete = { showDeleteConfirm = agent }
                        )
                    }
                }
            }

        }

        // Delete confirmation
        showDeleteConfirm?.let { agent ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text("Delete Agent", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to delete \"${agent.name}\"?") },
                confirmButton = {
                    Button(
                        onClick = {
                            val newAgents = aiSettings.agents.filter { it.id != agent.id }
                            onSave(aiSettings.copy(agents = newAgents))
                            showDeleteConfirm = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

    }
}

/**
 * Agent list item card.
 */
@Composable
private fun AgentListItem(
    agent: AiAgent,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = agent.model.ifBlank { agent.provider.defaultModel },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Edit", color = Color(0xFF6B9BFF))
                }
                TextButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Copy", color = Color(0xFF9C27B0))
                }
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Delete", color = Color(0xFFF44336))
                }
            }
        }
    }
}

/**
 * Full-screen agent add/edit screen.
 */
@Composable
internal fun AgentEditScreen(
    agent: AiAgent?,
    aiSettings: AiSettings,
    developerMode: Boolean,
    existingNames: Set<String>,
    onTestAiModel: suspend (AiService, String, String) -> String?,
    onFetchModelsForProvider: (AiService, String) -> Unit,
    onSave: (AiAgent) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    forceAddMode: Boolean = false,
    prefillProvider: AiService? = null,
    prefillApiKey: String = "",
    prefillModel: String = "",
    prefillName: String = "",
    onNavigateToSelectModel: ((AiService, (String) -> Unit) -> Unit)? = null
) {
    val isEditing = agent != null && !forceAddMode
    // Filter providers: must be active (status "ok"), always include current agent's provider when editing
    val availableProviders = AiService.entries.filter { provider ->
        // Always include current agent's provider when editing
        if (isEditing && provider == agent?.provider) return@filter true
        aiSettings.isProviderActive(provider, developerMode)
    }.sortedBy { it.displayName.lowercase() }
    val coroutineScope = rememberCoroutineScope()

    // State - default to first available provider when creating new agent, or prefill values
    val defaultProvider = agent?.provider ?: prefillProvider ?: availableProviders.firstOrNull() ?: AiService.OPENAI
    var name by remember { mutableStateOf(agent?.name ?: prefillName) }
    var selectedProvider by remember { mutableStateOf(defaultProvider) }
    var model by remember { mutableStateOf(agent?.model ?: prefillModel) }
    var apiKey by remember { mutableStateOf(agent?.apiKey ?: prefillApiKey) }
    var showKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Parameters preset selection (multi-select) - initialized directly from agent's paramsIds
    var selectedParametersIds by remember { mutableStateOf(agent?.paramsIds ?: emptyList()) }

    // Endpoint state - tracks the selected endpoint ID and URL for the agent
    var selectedEndpointId by remember { mutableStateOf(agent?.endpointId) }
    var showEndpointDialog by remember { mutableStateOf(false) }

    // Fetch models on initial load (for edit mode) and when provider changes
    LaunchedEffect(selectedProvider) {
        // Use agent's API key if set, otherwise fall back to provider's API key
        val effectiveApiKey = apiKey.ifBlank { aiSettings.getApiKey(selectedProvider) }

        // Fetch models if API key is available
        if (effectiveApiKey.isNotBlank()) {
            onFetchModelsForProvider(selectedProvider, effectiveApiKey)
        }

        // Reset endpoint when provider changes (unless editing and same provider)
        if (!isEditing || agent?.provider != selectedProvider) {
            selectedEndpointId = aiSettings.getDefaultEndpoint(selectedProvider)?.id
        }
    }

    // Validation
    val nameError = when {
        name.isBlank() -> "Name is required"
        !isEditing && existingNames.contains(name) -> "Name already exists"
        isEditing && name != agent?.name && existingNames.contains(name) -> "Name already exists"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Title bar
        AiTitleBar(
            title = if (isEditing) "Edit Agent" else "Add Agent",
            onBackClick = onBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Top action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    saveError = null
                    isSaving = true
                    coroutineScope.launch {
                        val newAgent = AiAgent(
                            id = agent?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            provider = selectedProvider,
                            model = model,
                            apiKey = apiKey.trim(),
                            endpointId = selectedEndpointId,
                            paramsIds = selectedParametersIds
                        )
                        isSaving = false
                        onSave(newAgent)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isSaving && nameError == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                if (isSaving) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Text("Saving...")
                    }
                } else {
                    Text(if (isEditing) "Save" else "Add")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Agent Name") },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it, color = Color(0xFFF44336)) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Provider dropdown
            Text(
                text = "Provider",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
            var providerExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { providerExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = selectedProvider.displayName,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (providerExpanded) "^" else "v")
                }
                DropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    availableProviders.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                selectedProvider = provider
                                providerExpanded = false
                                // Clear test result when provider changes
                                testResult = null
                            }
                        )
                    }
                }
            }

            // Model input field with Select button
            Text(
                text = "Model (optional - uses provider default if empty)",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        testResult = null
                    },
                    placeholder = { Text("Default: ${aiSettings.getModel(selectedProvider)}", color = Color(0xFF666666)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        onNavigateToSelectModel?.invoke(selectedProvider) { selectedModel ->
                            model = selectedModel
                            testResult = null
                        }
                    },
                    enabled = onNavigateToSelectModel != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Select", fontSize = 12.sp)
                }
            }

            // Endpoint input field with Select button
            val endpointsForProvider = aiSettings.getEndpointsForProvider(selectedProvider)
            val defaultEndpoint = aiSettings.getDefaultEndpoint(selectedProvider)
            val selectedEndpoint = endpointsForProvider.find { it.id == selectedEndpointId }
            // Show URL only if a non-default endpoint is selected
            val hasCustomEndpoint = selectedEndpointId != null && selectedEndpointId != defaultEndpoint?.id
            val displayEndpointUrl = if (hasCustomEndpoint) selectedEndpoint?.url ?: "" else ""
            val defaultEndpointUrl = defaultEndpoint?.url ?: aiSettings.getEffectiveEndpointUrl(selectedProvider)

            Text(
                text = "Endpoint (optional - uses provider default if empty)",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = displayEndpointUrl,
                    onValueChange = { /* Read-only - use Select button */ },
                    placeholder = { Text("Default: $defaultEndpointUrl", color = Color(0xFF666666)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    readOnly = true
                )
                Button(
                    onClick = { showEndpointDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Select", fontSize = 12.sp)
                }
            }

            // API Key
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        // Clear test result when API key changes
                        testResult = null
                    },
                    label = { Text("API Key (optional - uses provider key if empty)") },
                    placeholder = { Text("Uses provider API key", color = Color(0xFF666666)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation()
                )
                // Only show Show/Hide button when API key field has content
                if (apiKey.isNotBlank()) {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show", color = Color(0xFF6B9BFF))
                    }
                }
            }

            // Test API Key button - only show when agent has its own API key
            if (apiKey.isNotBlank()) {
                val effectiveModel = model.ifBlank { aiSettings.getModel(selectedProvider) }
                Button(
                    onClick = {
                        testResult = null
                        isTesting = true
                        coroutineScope.launch {
                            val error = onTestAiModel(selectedProvider, apiKey.trim(), effectiveModel)
                            isTesting = false
                            if (error != null) {
                                testResult = error
                                testSuccess = false
                            } else {
                                testResult = "API key is valid"
                                testSuccess = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    if (isTesting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Text("Testing...")
                        }
                    } else {
                        Text("Test API Key")
                    }
                }
            }

            // Test result message
            if (testResult != null) {
                Text(
                    text = testResult ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testSuccess) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }

            // Parameters preset selection (multi-select)
            ParametersSelector(
                aiSettings = aiSettings,
                selectedParametersIds = selectedParametersIds,
                onParamsSelected = { ids -> selectedParametersIds = ids }
            )

            // Save error message
            if (saveError != null) {
                Text(
                    text = "Save Failed: $saveError",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336)
                )
            }
        }

        // Bottom buttons
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    saveError = null
                    isSaving = true
                    coroutineScope.launch {
                        val newAgent = AiAgent(
                            id = agent?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            provider = selectedProvider,
                            model = model,
                            apiKey = apiKey.trim(),
                            endpointId = selectedEndpointId,
                            paramsIds = selectedParametersIds
                        )
                        isSaving = false
                        onSave(newAgent)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isSaving && nameError == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                if (isSaving) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Text("Saving...")
                    }
                } else {
                    Text(if (isEditing) "Save" else "Add")
                }
            }
        }
    }

    // Endpoint Selection Dialog
    if (showEndpointDialog) {
        val endpoints = aiSettings.getEndpointsForProvider(selectedProvider)
        val defaultBaseUrl = selectedProvider.baseUrl

        AlertDialog(
            onDismissRequest = { showEndpointDialog = false },
            title = { Text("Select Endpoint", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Default option (clears endpoint, uses provider's baseUrl at runtime)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedEndpointId = null
                                showEndpointDialog = false
                                testResult = null
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedEndpointId == null) Color(0xFF6366F1).copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Default (use provider setting)", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(defaultBaseUrl, fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    // Custom endpoints
                    if (endpoints.isNotEmpty()) {
                        Text(
                            "Custom Endpoints",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        endpoints.forEach { endpoint ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedEndpointId = endpoint.id
                                        showEndpointDialog = false
                                        testResult = null
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedEndpointId == endpoint.id) Color(0xFF6366F1).copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(endpoint.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        if (endpoint.isDefault) {
                                            Text(
                                                "DEFAULT",
                                                fontSize = 10.sp,
                                                color = Color(0xFF4CAF50),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(endpoint.url, fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEndpointDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

}

