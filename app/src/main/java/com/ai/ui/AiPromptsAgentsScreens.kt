package com.ai.ui

import androidx.compose.foundation.background
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
    availableChatGptModels: List<String>,
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String? = { _, _, _ -> null }
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<AiAgent?>(null) }
    var copyingAgent by remember { mutableStateOf<AiAgent?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AiAgent?>(null) }

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
        val hasAnyApiKey = aiSettings.chatGptApiKey.isNotBlank() ||
                aiSettings.claudeApiKey.isNotBlank() ||
                aiSettings.geminiApiKey.isNotBlank() ||
                aiSettings.grokApiKey.isNotBlank() ||
                aiSettings.groqApiKey.isNotBlank() ||
                aiSettings.deepSeekApiKey.isNotBlank() ||
                aiSettings.mistralApiKey.isNotBlank() ||
                aiSettings.perplexityApiKey.isNotBlank() ||
                aiSettings.togetherApiKey.isNotBlank() ||
                aiSettings.openRouterApiKey.isNotBlank()

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
                    Text(text = "❌", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "No API keys configured. Go to Settings → AI Setup → AI Providers to add an API key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFF8080)
                    )
                }
            }
        } else {
            // Add button
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("+ Add Agent")
            }

            // Agent list
            if (aiSettings.agents.isEmpty()) {
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
        } else {
            // Filter out DUMMY agents when not in developer mode
            val visibleAgents = if (developerMode) {
                aiSettings.agents
            } else {
                aiSettings.agents.filter { it.provider != AiService.DUMMY }
            }
            visibleAgents.sortedBy { it.name.lowercase() }.forEach { agent ->
                AgentListItem(
                    agent = agent,
                    onEdit = { editingAgent = agent },
                    onCopy = { copyingAgent = agent },
                    onDelete = { showDeleteConfirm = agent }
                )
            }
        }
        } // end else block for hasAnyApiKey

        Spacer(modifier = Modifier.height(8.dp))

        // Back buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBackToAiSetup) {
                Text("< AI Setup")
            }
        }
    }

    // Add/Edit/Copy dialog
    if (showAddDialog || editingAgent != null || copyingAgent != null) {
        // For copy mode, create a template agent with new ID and "(Copy)" suffix
        val dialogAgent = copyingAgent?.let { agent ->
            agent.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = "${agent.name} (Copy)"
            )
        } ?: editingAgent
        val isEditMode = editingAgent != null
        val editingAgentId = editingAgent?.id

        AgentEditDialog(
            agent = dialogAgent,
            aiSettings = aiSettings,
            developerMode = developerMode,
            availableChatGptModels = availableChatGptModels,
            availableGeminiModels = availableGeminiModels,
            availableGrokModels = availableGrokModels,
            availableGroqModels = availableGroqModels,
            availableDeepSeekModels = availableDeepSeekModels,
            availableMistralModels = availableMistralModels,
            availablePerplexityModels = availablePerplexityModels,
            availableTogetherModels = availableTogetherModels,
            availableOpenRouterModels = availableOpenRouterModels,
            existingNames = aiSettings.agents.map { it.name }.toSet(),
            onTestAiModel = onTestAiModel,
            onSave = { newAgent ->
                val newAgents = if (isEditMode && editingAgentId != null) {
                    aiSettings.agents.map { if (it.id == editingAgentId) newAgent else it }
                } else {
                    aiSettings.agents + newAgent
                }
                onSave(aiSettings.copy(agents = newAgents))
                showAddDialog = false
                editingAgent = null
                copyingAgent = null
            },
            onDismiss = {
                showAddDialog = false
                editingAgent = null
                copyingAgent = null
            }
        )
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
                    text = agent.model,
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
 * Agent add/edit dialog.
 */
@Composable
private fun AgentEditDialog(
    agent: AiAgent?,
    aiSettings: AiSettings,
    developerMode: Boolean,
    availableChatGptModels: List<String>,
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    existingNames: Set<String>,
    onTestAiModel: suspend (AiService, String, String) -> String?,
    onSave: (AiAgent) -> Unit,
    onDismiss: () -> Unit
) {
    val isEditing = agent != null
    // Filter providers: must have API key configured, exclude DUMMY unless developer mode
    val availableProviders = AiService.entries.filter { provider ->
        // Always include current agent's provider when editing
        if (isEditing && provider == agent?.provider) return@filter true
        // Exclude DUMMY unless in developer mode
        if (provider == AiService.DUMMY && !developerMode) return@filter false
        // Only include providers with an API key configured
        aiSettings.getApiKey(provider).isNotBlank()
    }
    val coroutineScope = rememberCoroutineScope()

    // State - default to first available provider when creating new agent
    val defaultProvider = agent?.provider ?: availableProviders.firstOrNull() ?: AiService.CHATGPT
    var name by remember { mutableStateOf(agent?.name ?: "") }
    var selectedProvider by remember { mutableStateOf(defaultProvider) }
    var model by remember { mutableStateOf(agent?.model ?: getDefaultModelForProvider(defaultProvider)) }
    var apiKey by remember { mutableStateOf(agent?.apiKey ?: aiSettings.getApiKey(defaultProvider)) }
    var showKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testError by remember { mutableStateOf<String?>(null) }
    var showParameters by remember { mutableStateOf(false) }

    // Parameter state
    val existingParams = agent?.parameters ?: AiAgentParameters()
    var temperature by remember { mutableStateOf(existingParams.temperature?.toString() ?: "") }
    var maxTokens by remember { mutableStateOf(existingParams.maxTokens?.toString() ?: "") }
    var topP by remember { mutableStateOf(existingParams.topP?.toString() ?: "") }
    var topK by remember { mutableStateOf(existingParams.topK?.toString() ?: "") }
    var frequencyPenalty by remember { mutableStateOf(existingParams.frequencyPenalty?.toString() ?: "") }
    var presencePenalty by remember { mutableStateOf(existingParams.presencePenalty?.toString() ?: "") }
    var systemPrompt by remember { mutableStateOf(existingParams.systemPrompt ?: "") }
    var stopSequences by remember { mutableStateOf(existingParams.stopSequences?.joinToString(", ") ?: "") }
    var seed by remember { mutableStateOf(existingParams.seed?.toString() ?: "") }
    var responseFormatJson by remember { mutableStateOf(existingParams.responseFormatJson) }
    var searchEnabled by remember { mutableStateOf(existingParams.searchEnabled) }
    var returnCitations by remember { mutableStateOf(existingParams.returnCitations) }
    var searchRecency by remember { mutableStateOf(existingParams.searchRecency ?: "") }

    // Get supported parameters for selected provider
    val supportedParams = PROVIDER_SUPPORTED_PARAMETERS[selectedProvider] ?: emptySet()

    // Get models for selected provider
    val modelsForProvider = when (selectedProvider) {
        AiService.CHATGPT -> {
            val apiModels = if (aiSettings.chatGptModelSource == ModelSource.API) availableChatGptModels else emptyList()
            val manualModels = if (aiSettings.chatGptModelSource == ModelSource.MANUAL) aiSettings.chatGptManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.CLAUDE -> aiSettings.claudeManualModels.ifEmpty { CLAUDE_MODELS }
        AiService.GEMINI -> {
            val apiModels = if (aiSettings.geminiModelSource == ModelSource.API) availableGeminiModels else emptyList()
            val manualModels = if (aiSettings.geminiModelSource == ModelSource.MANUAL) aiSettings.geminiManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.GROK -> {
            val apiModels = if (aiSettings.grokModelSource == ModelSource.API) availableGrokModels else emptyList()
            val manualModels = if (aiSettings.grokModelSource == ModelSource.MANUAL) aiSettings.grokManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.GROQ -> {
            val apiModels = if (aiSettings.groqModelSource == ModelSource.API) availableGroqModels else emptyList()
            val manualModels = if (aiSettings.groqModelSource == ModelSource.MANUAL) aiSettings.groqManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.DEEPSEEK -> {
            val apiModels = if (aiSettings.deepSeekModelSource == ModelSource.API) availableDeepSeekModels else emptyList()
            val manualModels = if (aiSettings.deepSeekModelSource == ModelSource.MANUAL) aiSettings.deepSeekManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.MISTRAL -> {
            val apiModels = if (aiSettings.mistralModelSource == ModelSource.API) availableMistralModels else emptyList()
            val manualModels = if (aiSettings.mistralModelSource == ModelSource.MANUAL) aiSettings.mistralManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.PERPLEXITY -> {
            val apiModels = if (aiSettings.perplexityModelSource == ModelSource.API) availablePerplexityModels else emptyList()
            val manualModels = if (aiSettings.perplexityModelSource == ModelSource.MANUAL) aiSettings.perplexityManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.TOGETHER -> {
            val apiModels = if (aiSettings.togetherModelSource == ModelSource.API) availableTogetherModels else emptyList()
            val manualModels = if (aiSettings.togetherModelSource == ModelSource.MANUAL) aiSettings.togetherManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.OPENROUTER -> {
            val apiModels = if (aiSettings.openRouterModelSource == ModelSource.API) availableOpenRouterModels else emptyList()
            val manualModels = if (aiSettings.openRouterModelSource == ModelSource.MANUAL) aiSettings.openRouterManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.SILICONFLOW -> {
            val manualModels = aiSettings.siliconFlowManualModels
            manualModels.ifEmpty { SILICONFLOW_MODELS }
        }
        AiService.DUMMY -> aiSettings.dummyManualModels.ifEmpty { listOf("dummy-model") }
    }

    // Update model and API key when provider changes
    LaunchedEffect(selectedProvider) {
        if (!isEditing || agent?.provider != selectedProvider) {
            model = modelsForProvider.firstOrNull() ?: getDefaultModelForProvider(selectedProvider)
            // For new agents, also update API key from provider settings
            if (!isEditing) {
                apiKey = aiSettings.getApiKey(selectedProvider)
            }
        }
    }

    // Validation
    val nameError = when {
        name.isBlank() -> "Name is required"
        !isEditing && existingNames.contains(name) -> "Name already exists"
        isEditing && name != agent?.name && existingNames.contains(name) -> "Name already exists"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) "Edit Agent" else "Add Agent",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
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
                        Text(if (providerExpanded) "▲" else "▼")
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
                                }
                            )
                        }
                    }
                }

                // Model dropdown
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA)
                )
                var modelExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { modelExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = model,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Text(if (modelExpanded) "▲" else "▼")
                    }
                    DropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        modelsForProvider.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    model = m
                                    modelExpanded = false
                                }
                            )
                        }
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
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation()
                    )
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show", color = Color(0xFF6B9BFF))
                    }
                }

                // Parameters toggle
                HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Advanced Parameters",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(onClick = { showParameters = !showParameters }) {
                        Text(
                            if (showParameters) "Hide ▲" else "Show ▼",
                            color = Color(0xFF6B9BFF)
                        )
                    }
                }

                // Parameters section (collapsible)
                if (showParameters) {
                    // Temperature
                    if (AiParameter.TEMPERATURE in supportedParams) {
                        OutlinedTextField(
                            value = temperature,
                            onValueChange = { temperature = it },
                            label = { Text("Temperature (0.0-2.0)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Controls randomness. Lower = focused, Higher = creative") }
                        )
                    }

                    // Max Tokens
                    if (AiParameter.MAX_TOKENS in supportedParams) {
                        OutlinedTextField(
                            value = maxTokens,
                            onValueChange = { maxTokens = it },
                            label = { Text("Max Tokens") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Maximum response length") }
                        )
                    }

                    // Top P
                    if (AiParameter.TOP_P in supportedParams) {
                        OutlinedTextField(
                            value = topP,
                            onValueChange = { topP = it },
                            label = { Text("Top P (0.0-1.0)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Nucleus sampling threshold") }
                        )
                    }

                    // Top K
                    if (AiParameter.TOP_K in supportedParams) {
                        OutlinedTextField(
                            value = topK,
                            onValueChange = { topK = it },
                            label = { Text("Top K") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Limits vocabulary choices per token") }
                        )
                    }

                    // Frequency Penalty
                    if (AiParameter.FREQUENCY_PENALTY in supportedParams) {
                        OutlinedTextField(
                            value = frequencyPenalty,
                            onValueChange = { frequencyPenalty = it },
                            label = { Text("Frequency Penalty (-2.0 to 2.0)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Reduces repetition of frequent tokens") }
                        )
                    }

                    // Presence Penalty
                    if (AiParameter.PRESENCE_PENALTY in supportedParams) {
                        OutlinedTextField(
                            value = presencePenalty,
                            onValueChange = { presencePenalty = it },
                            label = { Text("Presence Penalty (-2.0 to 2.0)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Encourages discussing new topics") }
                        )
                    }

                    // System Prompt
                    if (AiParameter.SYSTEM_PROMPT in supportedParams) {
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            label = { Text("System Prompt") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            supportingText = { Text("Instructions for the AI's behavior") }
                        )
                    }

                    // Stop Sequences
                    if (AiParameter.STOP_SEQUENCES in supportedParams) {
                        OutlinedTextField(
                            value = stopSequences,
                            onValueChange = { stopSequences = it },
                            label = { Text("Stop Sequences") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("Comma-separated list of stop strings") }
                        )
                    }

                    // Seed
                    if (AiParameter.SEED in supportedParams) {
                        OutlinedTextField(
                            value = seed,
                            onValueChange = { seed = it },
                            label = { Text("Seed") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = { Text("For reproducible outputs") }
                        )
                    }

                    // Response Format JSON
                    if (AiParameter.RESPONSE_FORMAT in supportedParams) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = responseFormatJson,
                                onCheckedChange = { responseFormatJson = it }
                            )
                            Text("JSON Response Format")
                        }
                    }

                    // Search Enabled (Grok)
                    if (AiParameter.SEARCH_ENABLED in supportedParams) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = searchEnabled,
                                onCheckedChange = { searchEnabled = it }
                            )
                            Text("Enable Web Search")
                        }
                    }

                    // Return Citations (Perplexity)
                    if (AiParameter.RETURN_CITATIONS in supportedParams) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = returnCitations,
                                onCheckedChange = { returnCitations = it }
                            )
                            Text("Return Citations")
                        }
                    }

                    // Search Recency (Perplexity)
                    if (AiParameter.SEARCH_RECENCY in supportedParams) {
                        Text(
                            text = "Search Recency",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA)
                        )
                        var recencyExpanded by remember { mutableStateOf(false) }
                        val recencyOptions = listOf("" to "Default", "day" to "Day", "week" to "Week", "month" to "Month", "year" to "Year")
                        Box {
                            OutlinedButton(
                                onClick = { recencyExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = recencyOptions.find { it.first == searchRecency }?.second ?: "Default",
                                    modifier = Modifier.weight(1f)
                                )
                                Text(if (recencyExpanded) "▲" else "▼")
                            }
                            DropdownMenu(
                                expanded = recencyExpanded,
                                onDismissRequest = { recencyExpanded = false }
                            ) {
                                recencyOptions.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            searchRecency = value
                                            recencyExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Test error message
                if (testError != null) {
                    HorizontalDivider(color = Color(0xFF444444))
                    Text(
                        text = "API Test Failed: $testError",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF44336)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    testError = null
                    isTesting = true
                    coroutineScope.launch {
                        // Skip test for empty API key, test all providers including DUMMY
                        val error = if (apiKey.isBlank()) {
                            null
                        } else {
                            onTestAiModel(selectedProvider, apiKey.trim(), model)
                        }
                        isTesting = false
                        if (error != null) {
                            testError = error
                        } else {
                            // Build parameters
                            val params = AiAgentParameters(
                                temperature = temperature.toFloatOrNull(),
                                maxTokens = maxTokens.toIntOrNull(),
                                topP = topP.toFloatOrNull(),
                                topK = topK.toIntOrNull(),
                                frequencyPenalty = frequencyPenalty.toFloatOrNull(),
                                presencePenalty = presencePenalty.toFloatOrNull(),
                                systemPrompt = systemPrompt.takeIf { it.isNotBlank() },
                                stopSequences = stopSequences.takeIf { it.isNotBlank() }
                                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
                                seed = seed.toIntOrNull(),
                                responseFormatJson = responseFormatJson,
                                searchEnabled = searchEnabled,
                                returnCitations = returnCitations,
                                searchRecency = searchRecency.takeIf { it.isNotBlank() }
                            )
                            val newAgent = AiAgent(
                                id = agent?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                provider = selectedProvider,
                                model = model,
                                apiKey = apiKey.trim(),
                                parameters = params
                            )
                            onSave(newAgent)
                        }
                    }
                },
                enabled = !isTesting && nameError == null
            ) {
                if (isTesting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Testing...")
                    }
                } else {
                    Text(if (isEditing) "Save" else "Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Get default model for a provider.
 */
fun getDefaultModelForProvider(provider: AiService): String {
    return when (provider) {
        AiService.CHATGPT -> "gpt-4o-mini"
        AiService.CLAUDE -> "claude-sonnet-4-20250514"
        AiService.GEMINI -> "gemini-2.0-flash"
        AiService.GROK -> "grok-3-mini"
        AiService.GROQ -> "llama-3.3-70b-versatile"
        AiService.DEEPSEEK -> "deepseek-chat"
        AiService.MISTRAL -> "mistral-small-latest"
        AiService.PERPLEXITY -> "sonar"
        AiService.TOGETHER -> "meta-llama/Llama-3.3-70B-Instruct-Turbo"
        AiService.OPENROUTER -> "anthropic/claude-3.5-sonnet"
        AiService.SILICONFLOW -> "Qwen/Qwen2.5-7B-Instruct"
        AiService.DUMMY -> "dummy"
    }
}
