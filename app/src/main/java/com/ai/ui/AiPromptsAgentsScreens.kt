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
    availableChatGptModels: List<String>,
    availableClaudeModels: List<String>,
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    availableSiliconFlowModels: List<String>,
    availableZaiModels: List<String>,
    availableDummyModels: List<String>,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSave: (AiSettings) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String? = { _, _, _ -> null },
    onFetchChatGptModels: (String) -> Unit = {},
    onFetchClaudeModels: (String) -> Unit = {},
    onFetchGeminiModels: (String) -> Unit = {},
    onFetchGrokModels: (String) -> Unit = {},
    onFetchGroqModels: (String) -> Unit = {},
    onFetchDeepSeekModels: (String) -> Unit = {},
    onFetchMistralModels: (String) -> Unit = {},
    onFetchPerplexityModels: (String) -> Unit = {},
    onFetchTogetherModels: (String) -> Unit = {},
    onFetchOpenRouterModels: (String) -> Unit = {},
    onFetchSiliconFlowModels: (String) -> Unit = {},
    onFetchZaiModels: (String) -> Unit = {},
    onFetchDummyModels: (String) -> Unit = {}
) {
    var showAddScreen by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<AiAgent?>(null) }
    var copyingAgent by remember { mutableStateOf<AiAgent?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<AiAgent?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Helper to fetch models for a provider
    val fetchModelsForProvider: (AiService, String) -> Unit = { provider, apiKey ->
        when (provider) {
            AiService.OPENAI -> onFetchChatGptModels(apiKey)
            AiService.ANTHROPIC -> onFetchClaudeModels(apiKey)
            AiService.GOOGLE -> onFetchGeminiModels(apiKey)
            AiService.XAI -> onFetchGrokModels(apiKey)
            AiService.GROQ -> onFetchGroqModels(apiKey)
            AiService.DEEPSEEK -> onFetchDeepSeekModels(apiKey)
            AiService.MISTRAL -> onFetchMistralModels(apiKey)
            AiService.PERPLEXITY -> onFetchPerplexityModels(apiKey)
            AiService.TOGETHER -> onFetchTogetherModels(apiKey)
            AiService.OPENROUTER -> onFetchOpenRouterModels(apiKey)
            AiService.SILICONFLOW -> onFetchSiliconFlowModels(apiKey)
            AiService.ZAI -> onFetchZaiModels(apiKey)
            AiService.DUMMY -> onFetchDummyModels(apiKey)
        }
    }

    // Show Add/Edit/Copy screen (full screen)
    if (showAddScreen || editingAgent != null || copyingAgent != null) {
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
            availableChatGptModels = availableChatGptModels,
            availableClaudeModels = availableClaudeModels,
            availableGeminiModels = availableGeminiModels,
            availableGrokModels = availableGrokModels,
            availableGroqModels = availableGroqModels,
            availableDeepSeekModels = availableDeepSeekModels,
            availableMistralModels = availableMistralModels,
            availablePerplexityModels = availablePerplexityModels,
            availableTogetherModels = availableTogetherModels,
            availableOpenRouterModels = availableOpenRouterModels,
            availableSiliconFlowModels = availableSiliconFlowModels,
            availableZaiModels = availableZaiModels,
            availableDummyModels = availableDummyModels,
            existingNames = aiSettings.agents.map { it.name }.toSet(),
            onTestAiModel = onTestAiModel,
            onFetchModelsForProvider = fetchModelsForProvider,
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
            onNavigateHome = onBackToHome
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

                // Agent list - filter out DUMMY agents when not in developer mode and apply search
                val baseAgents = if (developerMode) {
                    aiSettings.agents
                } else {
                    aiSettings.agents.filter { it.provider != AiService.DUMMY }
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
 * Full-screen agent add/edit screen.
 */
@Composable
internal fun AgentEditScreen(
    agent: AiAgent?,
    aiSettings: AiSettings,
    developerMode: Boolean,
    availableChatGptModels: List<String>,
    availableClaudeModels: List<String>,
    availableGeminiModels: List<String>,
    availableGrokModels: List<String>,
    availableGroqModels: List<String>,
    availableDeepSeekModels: List<String>,
    availableMistralModels: List<String>,
    availablePerplexityModels: List<String>,
    availableTogetherModels: List<String>,
    availableOpenRouterModels: List<String>,
    availableSiliconFlowModels: List<String>,
    availableZaiModels: List<String>,
    availableDummyModels: List<String>,
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
    prefillName: String = ""
) {
    val isEditing = agent != null && !forceAddMode
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

    // State - default to first available provider when creating new agent, or prefill values
    val defaultProvider = agent?.provider ?: prefillProvider ?: availableProviders.firstOrNull() ?: AiService.OPENAI
    var name by remember { mutableStateOf(agent?.name ?: prefillName) }
    var selectedProvider by remember { mutableStateOf(defaultProvider) }
    var model by remember { mutableStateOf(agent?.model ?: prefillModel) }
    var showModelDialog by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(agent?.apiKey ?: prefillApiKey) }
    var showKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Parameters preset selection
    var selectedParamsId by remember { mutableStateOf<String?>(null) }
    var selectedParamsName by remember { mutableStateOf("") }
    var showParamsDialog by remember { mutableStateOf(false) }

    // Initialize selectedParamsName from existing agent parameters if they match a preset
    LaunchedEffect(Unit) {
        if (agent != null) {
            // Try to find a matching params preset
            val matchingParams = aiSettings.params.find { params ->
                params.temperature == agent.parameters.temperature &&
                params.maxTokens == agent.parameters.maxTokens &&
                params.topP == agent.parameters.topP &&
                params.topK == agent.parameters.topK &&
                params.frequencyPenalty == agent.parameters.frequencyPenalty &&
                params.presencePenalty == agent.parameters.presencePenalty &&
                params.systemPrompt == agent.parameters.systemPrompt &&
                params.seed == agent.parameters.seed &&
                params.responseFormatJson == agent.parameters.responseFormatJson &&
                params.searchEnabled == agent.parameters.searchEnabled &&
                params.returnCitations == agent.parameters.returnCitations &&
                params.searchRecency == agent.parameters.searchRecency
            }
            if (matchingParams != null) {
                selectedParamsId = matchingParams.id
                selectedParamsName = matchingParams.name
            }
        }
    }

    // Endpoint state - tracks the selected endpoint ID and URL for the agent
    var selectedEndpointId by remember { mutableStateOf(agent?.endpointId) }
    var showEndpointDialog by remember { mutableStateOf(false) }

    // Get models for selected provider
    val modelsForProvider = when (selectedProvider) {
        AiService.OPENAI -> {
            val apiModels = if (aiSettings.chatGptModelSource == ModelSource.API) availableChatGptModels else emptyList()
            val manualModels = if (aiSettings.chatGptModelSource == ModelSource.MANUAL) aiSettings.chatGptManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.ANTHROPIC -> {
            val apiModels = if (aiSettings.claudeModelSource == ModelSource.API) availableClaudeModels else emptyList()
            val manualModels = if (aiSettings.claudeModelSource == ModelSource.MANUAL) aiSettings.claudeManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { CLAUDE_MODELS }
        }
        AiService.GOOGLE -> {
            val apiModels = if (aiSettings.geminiModelSource == ModelSource.API) availableGeminiModels else emptyList()
            val manualModels = if (aiSettings.geminiModelSource == ModelSource.MANUAL) aiSettings.geminiManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
        AiService.XAI -> {
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
            val apiModels = if (aiSettings.siliconFlowModelSource == ModelSource.API) availableSiliconFlowModels else emptyList()
            val manualModels = if (aiSettings.siliconFlowModelSource == ModelSource.MANUAL) aiSettings.siliconFlowManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { SILICONFLOW_MODELS }
        }
        AiService.ZAI -> {
            val apiModels = if (aiSettings.zaiModelSource == ModelSource.API) availableZaiModels else emptyList()
            val manualModels = if (aiSettings.zaiModelSource == ModelSource.MANUAL) aiSettings.zaiManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { ZAI_MODELS }
        }
        AiService.DUMMY -> {
            val apiModels = if (aiSettings.dummyModelSource == ModelSource.API) availableDummyModels else emptyList()
            val manualModels = if (aiSettings.dummyModelSource == ModelSource.MANUAL) aiSettings.dummyManualModels else emptyList()
            (apiModels + manualModels).ifEmpty { listOf(model) }
        }
    }

    // Helper to check if provider uses API model source
    fun getModelSourceForProvider(provider: AiService): ModelSource {
        return when (provider) {
            AiService.OPENAI -> aiSettings.chatGptModelSource
            AiService.GOOGLE -> aiSettings.geminiModelSource
            AiService.XAI -> aiSettings.grokModelSource
            AiService.GROQ -> aiSettings.groqModelSource
            AiService.DEEPSEEK -> aiSettings.deepSeekModelSource
            AiService.MISTRAL -> aiSettings.mistralModelSource
            AiService.PERPLEXITY -> aiSettings.perplexityModelSource
            AiService.TOGETHER -> aiSettings.togetherModelSource
            AiService.OPENROUTER -> aiSettings.openRouterModelSource
            AiService.DUMMY -> aiSettings.dummyModelSource
            AiService.ANTHROPIC -> ModelSource.MANUAL // Claude has hardcoded models
            AiService.SILICONFLOW -> ModelSource.MANUAL // SiliconFlow has hardcoded models
            AiService.ZAI -> ModelSource.MANUAL // Z.AI has hardcoded models
        }
    }

    // Fetch models on initial load (for edit mode) and when provider changes
    LaunchedEffect(selectedProvider) {
        // Use agent's API key if set, otherwise fall back to provider's API key
        val effectiveApiKey = apiKey.ifBlank { aiSettings.getApiKey(selectedProvider) }

        // Fetch if using API model source and API key is available
        if (getModelSourceForProvider(selectedProvider) == ModelSource.API && effectiveApiKey.isNotBlank()) {
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
                        // Get parameters from selected preset or use empty parameters
                        val params = selectedParamsId?.let { id ->
                            aiSettings.getParamsById(id)?.toAgentParameters()
                        } ?: AiAgentParameters()
                        val newAgent = AiAgent(
                            id = agent?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            provider = selectedProvider,
                            model = model,
                            apiKey = apiKey.trim(),
                            endpointId = selectedEndpointId,
                            parameters = params
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
                    onClick = { showModelDialog = true },
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
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Show", color = Color(0xFF6B9BFF))
                }
            }

            // Test API Key button - uses provider key/model if agent values are empty
            val effectiveApiKey = apiKey.ifBlank { aiSettings.getApiKey(selectedProvider) }
            val effectiveModel = model.ifBlank { aiSettings.getModel(selectedProvider) }
            Button(
                onClick = {
                    testResult = null
                    isTesting = true
                    coroutineScope.launch {
                        val error = if (effectiveApiKey.isBlank()) {
                            "No API key available (agent or provider)"
                        } else {
                            onTestAiModel(selectedProvider, effectiveApiKey.trim(), effectiveModel)
                        }
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
                enabled = !isTesting && effectiveApiKey.isNotBlank(),
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

            // Test result message
            if (testResult != null) {
                Text(
                    text = testResult!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testSuccess) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }

            // Parameters preset selection
            Text(
                text = "Parameters (optional - select a preset)",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = selectedParamsName,
                    onValueChange = { /* Read-only - use Select button */ },
                    placeholder = { Text("No parameters selected", color = Color(0xFF666666)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    readOnly = true
                )
                Button(
                    onClick = { showParamsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Select", fontSize = 12.sp)
                }
            }

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
                        // Get parameters from selected preset or use empty parameters
                        val params = selectedParamsId?.let { id ->
                            aiSettings.getParamsById(id)?.toAgentParameters()
                        } ?: AiAgentParameters()
                        val newAgent = AiAgent(
                            id = agent?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            provider = selectedProvider,
                            model = model,
                            apiKey = apiKey.trim(),
                            endpointId = selectedEndpointId,
                            parameters = params
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

    // Parameters Selection Dialog
    if (showParamsDialog) {
        AlertDialog(
            onDismissRequest = { showParamsDialog = false },
            title = { Text("Select Parameters", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // None option (clears parameters)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedParamsId = null
                                selectedParamsName = ""
                                showParamsDialog = false
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedParamsId == null) Color(0xFF6366F1).copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("None", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text("No parameters preset", fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    // Available params presets
                    if (aiSettings.params.isNotEmpty()) {
                        Text(
                            "Available Presets",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        aiSettings.params.sortedBy { it.name.lowercase() }.forEach { params ->
                            val configuredCount = listOfNotNull(
                                params.temperature,
                                params.maxTokens,
                                params.topP,
                                params.topK,
                                params.frequencyPenalty,
                                params.presencePenalty,
                                params.systemPrompt?.takeIf { it.isNotBlank() },
                                params.seed
                            ).size + listOf(
                                params.responseFormatJson,
                                params.searchEnabled,
                                params.returnCitations
                            ).count { it } + (if (params.searchRecency != null) 1 else 0)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedParamsId = params.id
                                        selectedParamsName = params.name
                                        showParamsDialog = false
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedParamsId == params.id) Color(0xFF6366F1).copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(params.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text("$configuredCount parameter${if (configuredCount == 1) "" else "s"} configured", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                        }
                    } else {
                        Text(
                            "No parameter presets configured.\nGo to AI Setup > Parameters to create presets.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showParamsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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

    // Model Selection Dialog
    if (showModelDialog) {
        val defaultModel = aiSettings.getModel(selectedProvider)

        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Select Model (${modelsForProvider.size})", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Default option (clears model, uses provider's default at runtime)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                model = ""
                                showModelDialog = false
                                testResult = null
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (model.isBlank()) Color(0xFF6366F1).copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Default (use provider setting)", fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(defaultModel, fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    // Available models
                    if (modelsForProvider.isNotEmpty()) {
                        Text(
                            "Available Models",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        modelsForProvider.forEach { m ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = m
                                        showModelDialog = false
                                        testResult = null
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (model == m) Color(0xFF6366F1).copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(m, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Get default model for a provider.
 */
fun getDefaultModelForProvider(provider: AiService): String {
    return provider.defaultModel
}
