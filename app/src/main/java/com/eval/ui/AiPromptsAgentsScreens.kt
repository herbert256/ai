package com.eval.ui

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
import com.eval.data.AiService
import kotlinx.coroutines.launch

/**
 * AI Prompts screen - CRUD for prompt templates.
 */
@Composable
fun AiPromptsScreen(
    aiSettings: AiSettings,
    onBackToAiSetup: () -> Unit,
    onBackToGame: () -> Unit,
    onSave: (AiSettings) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<AiPrompt?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AiPrompt?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EvalTitleBar(
            title = "AI Prompts",
            onBackClick = onBackToAiSetup,
            onEvalClick = onBackToGame
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Placeholder info
        PromptPlaceholdersInfo()

        Spacer(modifier = Modifier.height(8.dp))

        // Add button
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            )
        ) {
            Text("+ Add Prompt")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Prompt list
        if (aiSettings.prompts.isEmpty()) {
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
                        text = "No prompts configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFAAAAAA)
                    )
                    Text(
                        text = "Add a prompt to get started",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888)
                    )
                }
            }
        } else {
            aiSettings.prompts.forEach { prompt ->
                PromptListItem(
                    prompt = prompt,
                    onEdit = { editingPrompt = prompt },
                    onDelete = { showDeleteConfirm = prompt }
                )
            }
        }

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

    // Add/Edit dialog
    if (showAddDialog || editingPrompt != null) {
        PromptEditDialog(
            prompt = editingPrompt,
            existingNames = aiSettings.prompts.map { it.name }.toSet(),
            onSave = { name, text ->
                val newPrompts = if (editingPrompt != null) {
                    aiSettings.prompts.map {
                        if (it.id == editingPrompt!!.id) it.copy(name = name, text = text) else it
                    }
                } else {
                    aiSettings.prompts + AiPrompt(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        text = text
                    )
                }
                onSave(aiSettings.copy(prompts = newPrompts))
                showAddDialog = false
                editingPrompt = null
            },
            onDismiss = {
                showAddDialog = false
                editingPrompt = null
            }
        )
    }

    // Delete confirmation
    showDeleteConfirm?.let { prompt ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Prompt", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Are you sure you want to delete \"${prompt.name}\"?")

                    // Check if any agents reference this prompt
                    val referencingAgents = aiSettings.agents.filter {
                        it.gamePromptId == prompt.id ||
                        it.serverPlayerPromptId == prompt.id ||
                        it.otherPlayerPromptId == prompt.id
                    }
                    if (referencingAgents.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Warning: ${referencingAgents.size} agent(s) reference this prompt.",
                            color = Color(0xFFFF9800),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newPrompts = aiSettings.prompts.filter { it.id != prompt.id }
                        onSave(aiSettings.copy(prompts = newPrompts))
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
 * Prompt list item card.
 */
@Composable
private fun PromptListItem(
    prompt: AiPrompt,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = prompt.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onEdit) {
                        Text("Edit", color = Color(0xFF6B9BFF))
                    }
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = Color(0xFFF44336))
                    }
                }
            }
            Text(
                text = prompt.text.take(100) + if (prompt.text.length > 100) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA),
                maxLines = 2
            )
        }
    }
}

/**
 * Prompt add/edit dialog.
 */
@Composable
private fun PromptEditDialog(
    prompt: AiPrompt?,
    existingNames: Set<String>,
    onSave: (name: String, text: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(prompt?.name ?: "") }
    var text by remember { mutableStateOf(prompt?.text ?: "") }
    val isEditing = prompt != null

    // Validation
    val nameError = when {
        name.isBlank() -> "Name is required"
        !isEditing && existingNames.contains(name) -> "Name already exists"
        isEditing && name != prompt?.name && existingNames.contains(name) -> "Name already exists"
        else -> null
    }
    val textError = if (text.isBlank()) "Prompt text is required" else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) "Edit Prompt" else "Add Prompt",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it, color = Color(0xFFF44336)) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Prompt text") },
                    isError = textError != null,
                    supportingText = textError?.let { { Text(it, color = Color(0xFFF44336)) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    maxLines = 10
                )

                Text(
                    text = "Use @FEN@, @PLAYER@, @SERVER@, @DATE@ as placeholders",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim(), text) },
                enabled = nameError == null && textError == null
            ) {
                Text(if (isEditing) "Save" else "Add")
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
    onBackToGame: () -> Unit,
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
        EvalTitleBar(
            title = "AI Agents",
            onBackClick = onBackToAiSetup,
            onEvalClick = onBackToGame
        )

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
            aiSettings.agents.sortedBy { it.name.lowercase() }.forEach { agent ->
                AgentListItem(
                    agent = agent,
                    onEdit = { editingAgent = agent },
                    onCopy = { copyingAgent = agent },
                    onDelete = { showDeleteConfirm = agent }
                )
            }
        }

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
        val dialogAgent = when {
            copyingAgent != null -> copyingAgent!!.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = "${copyingAgent!!.name} (Copy)"
            )
            else -> editingAgent
        }
        val isEditMode = editingAgent != null

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
                val newAgents = if (isEditMode) {
                    aiSettings.agents.map { if (it.id == editingAgent!!.id) newAgent else it }
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
    // Filter providers based on developer mode (exclude DUMMY if not in developer mode)
    val availableProviders = if (developerMode) {
        AiService.entries.toList()
    } else {
        AiService.entries.filter { it != AiService.DUMMY }
    }
    val coroutineScope = rememberCoroutineScope()

    // Helper to find prompt ID by name, with fallback to first prompt
    fun findPromptId(name: String): String {
        return aiSettings.prompts.find { it.name == name }?.id
            ?: aiSettings.prompts.firstOrNull()?.id
            ?: ""
    }

    // State
    var name by remember { mutableStateOf(agent?.name ?: "") }
    var selectedProvider by remember { mutableStateOf(agent?.provider ?: AiService.CHATGPT) }
    var model by remember { mutableStateOf(agent?.model ?: "gpt-4o-mini") }
    var apiKey by remember { mutableStateOf(agent?.apiKey ?: aiSettings.getApiKey(agent?.provider ?: AiService.CHATGPT)) }
    var showKey by remember { mutableStateOf(false) }
    var gamePromptId by remember { mutableStateOf(agent?.gamePromptId ?: findPromptId(DEFAULT_GAME_PROMPT_NAME)) }
    var serverPlayerPromptId by remember { mutableStateOf(agent?.serverPlayerPromptId ?: findPromptId(DEFAULT_SERVER_PLAYER_PROMPT_NAME)) }
    var otherPlayerPromptId by remember { mutableStateOf(agent?.otherPlayerPromptId ?: findPromptId(DEFAULT_OTHER_PLAYER_PROMPT_NAME)) }
    var isTesting by remember { mutableStateOf(false) }
    var testError by remember { mutableStateOf<String?>(null) }

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

                // Prompt selections (only if prompts exist)
                if (aiSettings.prompts.isNotEmpty()) {
                    HorizontalDivider(color = Color(0xFF444444))

                    Text(
                        text = "Prompts",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    PromptDropdown(
                        label = "Game Prompt",
                        selectedId = gamePromptId,
                        prompts = aiSettings.prompts,
                        onSelect = { gamePromptId = it }
                    )

                    PromptDropdown(
                        label = "Server Player Prompt",
                        selectedId = serverPlayerPromptId,
                        prompts = aiSettings.prompts,
                        onSelect = { serverPlayerPromptId = it }
                    )

                    PromptDropdown(
                        label = "Other Player Prompt",
                        selectedId = otherPlayerPromptId,
                        prompts = aiSettings.prompts,
                        onSelect = { otherPlayerPromptId = it }
                    )
                } else {
                    Text(
                        text = "⚠ No prompts available. Create prompts first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800)
                    )
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
                            val newAgent = AiAgent(
                                id = agent?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                provider = selectedProvider,
                                model = model,
                                apiKey = apiKey.trim(),
                                gamePromptId = gamePromptId,
                                serverPlayerPromptId = serverPlayerPromptId,
                                otherPlayerPromptId = otherPlayerPromptId
                            )
                            onSave(newAgent)
                        }
                    }
                },
                enabled = !isTesting && nameError == null && (aiSettings.prompts.isNotEmpty() || apiKey.isBlank())
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
 * Prompt dropdown selector.
 */
@Composable
private fun PromptDropdown(
    label: String,
    selectedId: String,
    prompts: List<AiPrompt>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedPrompt = prompts.find { it.id == selectedId }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAAAAAA)
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedPrompt?.name ?: "Select...",
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "▲" else "▼")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                prompts.forEach { prompt ->
                    DropdownMenuItem(
                        text = { Text(prompt.name) },
                        onClick = {
                            onSelect(prompt.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
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
        AiService.DUMMY -> "dummy"
    }
}
