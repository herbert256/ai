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
    availableMoonshotModels: List<String>,
    availableCohereModels: List<String>,
    availableAi21Models: List<String>,
    availableDashScopeModels: List<String>,
    availableFireworksModels: List<String>,
    availableCerebrasModels: List<String>,
    availableSambaNovaModels: List<String>,
    availableBaichuanModels: List<String>,
    availableStepFunModels: List<String>,
    availableMiniMaxModels: List<String>,
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
    onFetchMoonshotModels: (String) -> Unit = {},
    onFetchCohereModels: (String) -> Unit = {},
    onFetchAi21Models: (String) -> Unit = {},
    onFetchDashScopeModels: (String) -> Unit = {},
    onFetchFireworksModels: (String) -> Unit = {},
    onFetchCerebrasModels: (String) -> Unit = {},
    onFetchSambaNovaModels: (String) -> Unit = {},
    onFetchBaichuanModels: (String) -> Unit = {},
    onFetchStepFunModels: (String) -> Unit = {},
    onFetchMiniMaxModels: (String) -> Unit = {},
    onFetchNvidiaModels: (String) -> Unit = {},
    onFetchReplicateModels: (String) -> Unit = {},
    onFetchHuggingFaceInferenceModels: (String) -> Unit = {},
    onFetchLambdaModels: (String) -> Unit = {},
    onFetchLeptonModels: (String) -> Unit = {},
    onFetchYiModels: (String) -> Unit = {},
    onFetchDoubaoModels: (String) -> Unit = {},
    onFetchRekaModels: (String) -> Unit = {},
    onFetchWriterModels: (String) -> Unit = {}
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
            AiService.MOONSHOT -> onFetchMoonshotModels(apiKey)
            AiService.COHERE -> onFetchCohereModels(apiKey)
            AiService.AI21 -> onFetchAi21Models(apiKey)
            AiService.DASHSCOPE -> onFetchDashScopeModels(apiKey)
            AiService.FIREWORKS -> onFetchFireworksModels(apiKey)
            AiService.CEREBRAS -> onFetchCerebrasModels(apiKey)
            AiService.SAMBANOVA -> onFetchSambaNovaModels(apiKey)
            AiService.BAICHUAN -> onFetchBaichuanModels(apiKey)
            AiService.STEPFUN -> onFetchStepFunModels(apiKey)
            AiService.MINIMAX -> onFetchMiniMaxModels(apiKey)
            AiService.NVIDIA -> onFetchNvidiaModels(apiKey)
            AiService.REPLICATE -> onFetchReplicateModels(apiKey)
            AiService.HUGGINGFACE -> onFetchHuggingFaceInferenceModels(apiKey)
            AiService.LAMBDA -> onFetchLambdaModels(apiKey)
            AiService.LEPTON -> onFetchLeptonModels(apiKey)
            AiService.YI -> onFetchYiModels(apiKey)
            AiService.DOUBAO -> onFetchDoubaoModels(apiKey)
            AiService.REKA -> onFetchRekaModels(apiKey)
            AiService.WRITER -> onFetchWriterModels(apiKey)
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
            availableMoonshotModels = availableMoonshotModels,
            availableCohereModels = availableCohereModels,
            availableAi21Models = availableAi21Models,
            availableDashScopeModels = availableDashScopeModels,
            availableFireworksModels = availableFireworksModels,
            availableCerebrasModels = availableCerebrasModels,
            availableSambaNovaModels = availableSambaNovaModels,
            availableBaichuanModels = availableBaichuanModels,
            availableStepFunModels = availableStepFunModels,
            availableMiniMaxModels = availableMiniMaxModels,
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
    availableMoonshotModels: List<String>,
    availableCohereModels: List<String>,
    availableAi21Models: List<String>,
    availableDashScopeModels: List<String>,
    availableFireworksModels: List<String>,
    availableCerebrasModels: List<String>,
    availableSambaNovaModels: List<String>,
    availableBaichuanModels: List<String>,
    availableStepFunModels: List<String>,
    availableMiniMaxModels: List<String>,
    availableNvidiaModels: List<String> = emptyList(),
    availableReplicateModels: List<String> = emptyList(),
    availableHuggingFaceInferenceModels: List<String> = emptyList(),
    availableLambdaModels: List<String> = emptyList(),
    availableLeptonModels: List<String> = emptyList(),
    availableYiModels: List<String> = emptyList(),
    availableDoubaoModels: List<String> = emptyList(),
    availableRekaModels: List<String> = emptyList(),
    availableWriterModels: List<String> = emptyList(),
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
    var showModelDialog by remember { mutableStateOf(false) }
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

    // Build available models map from individual parameters
    val availableModelsMap = mapOf(
        AiService.OPENAI to availableChatGptModels,
        AiService.ANTHROPIC to availableClaudeModels,
        AiService.GOOGLE to availableGeminiModels,
        AiService.XAI to availableGrokModels,
        AiService.GROQ to availableGroqModels,
        AiService.DEEPSEEK to availableDeepSeekModels,
        AiService.MISTRAL to availableMistralModels,
        AiService.PERPLEXITY to availablePerplexityModels,
        AiService.TOGETHER to availableTogetherModels,
        AiService.OPENROUTER to availableOpenRouterModels,
        AiService.SILICONFLOW to availableSiliconFlowModels,
        AiService.ZAI to availableZaiModels,
        AiService.MOONSHOT to availableMoonshotModels,
        AiService.COHERE to availableCohereModels,
        AiService.AI21 to availableAi21Models,
        AiService.DASHSCOPE to availableDashScopeModels,
        AiService.FIREWORKS to availableFireworksModels,
        AiService.CEREBRAS to availableCerebrasModels,
        AiService.SAMBANOVA to availableSambaNovaModels,
        AiService.BAICHUAN to availableBaichuanModels,
        AiService.STEPFUN to availableStepFunModels,
        AiService.MINIMAX to availableMiniMaxModels,
        AiService.NVIDIA to availableNvidiaModels,
        AiService.REPLICATE to availableReplicateModels,
        AiService.HUGGINGFACE to availableHuggingFaceInferenceModels,
        AiService.LAMBDA to availableLambdaModels,
        AiService.LEPTON to availableLeptonModels,
        AiService.YI to availableYiModels,
        AiService.DOUBAO to availableDoubaoModels,
        AiService.REKA to availableRekaModels,
        AiService.WRITER to availableWriterModels
    )

    // Get models for selected provider
    val modelsForProvider = run {
        val modelSource = aiSettings.getModelSource(selectedProvider)
        val availableModelsForProvider = availableModelsMap[selectedProvider] ?: emptyList()
        val apiModels = if (modelSource == ModelSource.API) availableModelsForProvider else emptyList()
        val manualModels = if (modelSource == ModelSource.MANUAL) aiSettings.getManualModels(selectedProvider) else emptyList()
        val defaultModels = defaultProviderConfig(selectedProvider).manualModels
        (apiModels + manualModels).ifEmpty { defaultModels.ifEmpty { listOf(model) } }
    }

    // Helper to check if provider uses API model source
    fun getModelSourceForProvider(provider: AiService): ModelSource {
        return aiSettings.getModelSource(provider)
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
                    text = testResult!!,
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
