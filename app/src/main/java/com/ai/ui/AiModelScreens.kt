package com.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiService

/**
 * Model Search screen - search across all provider model lists.
 */
@Composable
fun ModelSearchScreen(
    aiSettings: AiSettings,
    developerMode: Boolean,
    loadingModelsFor: Set<AiService> = emptySet(),
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSaveAiSettings: (AiSettings) -> Unit,
    onTestAiModel: suspend (AiService, String, String) -> String?,
    onFetchModels: (AiService, String) -> Unit,
    onNavigateToChatParams: (AiService, String) -> Unit,
    onNavigateToModelInfo: (AiService, String) -> Unit
) {
    // Check if any provider is loading
    val isLoading = loadingModelsFor.isNotEmpty()
    var searchQuery by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<ModelSearchItem?>(null) }

    // State for model action selection
    var showActionDialog by remember { mutableStateOf(false) }
    var showAgentEdit by remember { mutableStateOf(false) }

    // Show action popup when a model is clicked
    val actionModel = selectedModel
    if (showActionDialog && actionModel != null) {
        val model = actionModel
        AlertDialog(
            onDismissRequest = {
                showActionDialog = false
                selectedModel = null
            },
            title = {
                Text(
                    text = model.modelName,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    text = "What would you like to do with this model?",
                    color = Color(0xFFAAAAAA)
                )
            },
            confirmButton = {},
            dismissButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start AI Chat
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            val provider = model.provider
                            onNavigateToChatParams(provider, model.modelName)
                            selectedModel = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("💬", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Start AI Chat", color = Color(0xFF6B9BFF))
                        }
                    }
                    // Create AI Agent
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            showAgentEdit = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🤖", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Create AI Agent", color = Color(0xFF6B9BFF))
                        }
                    }
                    // Model Info
                    TextButton(
                        onClick = {
                            showActionDialog = false
                            val provider = model.provider
                            onNavigateToModelInfo(provider, model.modelName)
                            selectedModel = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ℹ️", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Model Info", color = Color(0xFF6B9BFF))
                        }
                    }
                }
            },
            containerColor = Color(0xFF2A2A2A)
        )
    }

    // Show AgentEditScreen when Create AI Agent is selected
    val editModel = selectedModel
    if (showAgentEdit && editModel != null) {
        val provider = providerFromName(editModel.providerName)
        val prefilledAgent = AiAgent(
            id = java.util.UUID.randomUUID().toString(),
            name = "",
            provider = provider,
            model = editModel.modelName,
            apiKey = aiSettings.getApiKey(provider)
        )

        AgentEditScreen(
            agent = prefilledAgent,
            aiSettings = aiSettings,
            developerMode = developerMode,
            existingNames = aiSettings.agents.map { it.name }.toSet(),
            onTestAiModel = onTestAiModel,
            onFetchModelsForProvider = onFetchModels,
            forceAddMode = true,
            onSave = { newAgent ->
                val newAgents = aiSettings.agents + newAgent
                onSaveAiSettings(aiSettings.copy(agents = newAgents))
                showAgentEdit = false
                selectedModel = null
            },
            onBack = {
                showAgentEdit = false
                selectedModel = null
            },
            onNavigateHome = onBackToHome
        )
        return
    }

    // Combine all models with their provider info
    val allModels = remember(aiSettings) {
        buildList {
            for (service in AiService.entries) {
                if (!aiSettings.isProviderActive(service)) continue
                val models = aiSettings.getModels(service)
                models.forEach { add(ModelSearchItem(service, service.displayName, it)) }
            }
        }
    }

    // Filter models based on search query
    val filteredModels = remember(searchQuery, allModels) {
        if (searchQuery.isBlank()) {
            allModels
        } else {
            allModels.filter {
                it.modelName.contains(searchQuery, ignoreCase = true) ||
                it.providerName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Model Search",
            onBackClick = onBackToAiSetup,
            onAiClick = onBackToHome
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Search input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search models") },
            placeholder = { Text("Enter model name or provider...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFFF9800),
                unfocusedBorderColor = Color(0xFF555555),
                focusedLabelColor = Color(0xFFFF9800)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Loading indicator or results count
        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFFF9800)
                )
                Text(
                    text = "Loading models from API...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
        } else {
            Text(
                text = "${filteredModels.size} models found",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Model list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredModels) { item ->
                ModelSearchResultCard(
                    item = item,
                    onClick = {
                        selectedModel = item
                        showActionDialog = true
                    }
                )
            }
        }
    }
}

/**
 * Helper to convert provider name to AiService.
 */
internal fun providerFromName(name: String): AiService {
    return AiService.entries.find { it.displayName == name } ?: AiService.entries.first()
}

/**
 * Data class for model search results.
 */
internal data class ModelSearchItem(
    val provider: AiService,
    val providerName: String,
    val modelName: String
)

/**
 * Card displaying a model search result.
 */
@Composable
private fun ModelSearchResultCard(
    item: ModelSearchItem,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Provider indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Text(
                    text = item.providerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // Arrow indicator
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF888888)
            )
        }
    }
}

// ============================================================================
// Model Info Screen
// ============================================================================

/**
 * Data class to hold aggregated model information from multiple sources.
 */
data class ModelInfoData(
    val modelName: String,
    val provider: AiService,
    // OpenRouter data
    val openRouterName: String? = null,
    val openRouterDescription: String? = null,
    val contextLength: Int? = null,
    val maxCompletionTokens: Int? = null,
    val promptPricing: String? = null,
    val completionPricing: String? = null,
    val modality: String? = null,
    val tokenizer: String? = null,
    val instructType: String? = null,
    val isModerated: Boolean? = null,
    // Hugging Face data
    val huggingFaceAuthor: String? = null,
    val huggingFaceDownloads: Long? = null,
    val huggingFaceLikes: Int? = null,
    val huggingFaceTags: List<String>? = null,
    val huggingFacePipelineTag: String? = null,
    val huggingFaceLibrary: String? = null,
    val huggingFaceLicense: String? = null,
    val huggingFaceLastModified: String? = null,
    val huggingFaceGated: Boolean? = null,
    // AI-generated description
    val aiDescription: String? = null
)

/**
 * Screen displaying detailed model information from multiple sources.
 */
@Composable
fun ModelInfoScreen(
    provider: AiService,
    modelName: String,
    openRouterApiKey: String,
    huggingFaceApiKey: String,
    aiSettings: AiSettings,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var modelInfo by remember { mutableStateOf<ModelInfoData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Fetch model info from available sources
    LaunchedEffect(modelName) {
        isLoading = true
        errorMessage = null

        try {
            var openRouterData: com.ai.data.OpenRouterModelInfo? = null
            var huggingFaceData: com.ai.data.HuggingFaceModelInfo? = null
            var aiDescription: String? = null

            // Try OpenRouter API if we have an API key
            if (openRouterApiKey.isNotBlank()) {
                try {
                    val api = com.ai.data.AiApiFactory.createOpenRouterModelsApi("https://openrouter.ai/api/")
                    val response = api.listModelsDetailed("Bearer $openRouterApiKey")
                    if (response.isSuccessful) {
                        val models = response.body()?.data ?: emptyList()
                        // Try to find the model by exact match or partial match
                        openRouterData = models.find { it.id.equals(modelName, ignoreCase = true) }
                            ?: models.find { it.id.contains(modelName, ignoreCase = true) }
                            ?: models.find { modelName.contains(it.id, ignoreCase = true) }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ModelInfo", "OpenRouter API error: ${e.message}")
                }
            }

            // Try Hugging Face API if we have an API key
            if (huggingFaceApiKey.isNotBlank()) {
                try {
                    val api = com.ai.data.AiApiFactory.createHuggingFaceApi()
                    // Try different model ID formats
                    val modelIds = listOf(
                        modelName,
                        modelName.replace(":", "/"),
                        modelName.substringAfter("/")
                    ).distinct()

                    for (modelId in modelIds) {
                        try {
                            val response = api.getModelInfo(modelId, "Bearer $huggingFaceApiKey")
                            if (response.isSuccessful) {
                                huggingFaceData = response.body()
                                break
                            }
                        } catch (e: Exception) {
                            // Try next format
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ModelInfo", "HuggingFace API error: ${e.message}")
                }
            }

            // Try AI prompt named "model_info" if it exists
            val modelInfoPrompt = aiSettings.getPromptByName("model_info")
            if (modelInfoPrompt != null) {
                val modelInfoAgent = aiSettings.getAgentForPrompt(modelInfoPrompt)
                if (modelInfoAgent != null) {
                    try {
                        // Resolve prompt with variables
                        val resolvedPrompt = modelInfoPrompt.resolvePrompt(
                            model = modelName,
                            provider = provider.displayName,
                            agent = modelInfoAgent.name
                        )
                        // Use effective API key and model (agent's or provider's)
                        val effectiveAgent = modelInfoAgent.copy(
                            apiKey = aiSettings.getEffectiveApiKeyForAgent(modelInfoAgent),
                            model = aiSettings.getEffectiveModelForAgent(modelInfoAgent)
                        )
                        val repository = com.ai.data.AiAnalysisRepository()
                        val result = repository.analyzePlayerWithAgent(effectiveAgent, resolvedPrompt)
                        if (result.error == null && !result.analysis.isNullOrBlank()) {
                            aiDescription = result.analysis
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ModelInfo", "AI prompt error: ${e.message}")
                    }
                }
            }

            // Combine data from all sources
            modelInfo = ModelInfoData(
                modelName = modelName,
                provider = provider,
                // OpenRouter
                openRouterName = openRouterData?.name,
                openRouterDescription = openRouterData?.description,
                contextLength = openRouterData?.context_length ?: openRouterData?.top_provider?.context_length,
                maxCompletionTokens = openRouterData?.top_provider?.max_completion_tokens,
                promptPricing = openRouterData?.pricing?.prompt,
                completionPricing = openRouterData?.pricing?.completion,
                modality = openRouterData?.architecture?.modality,
                tokenizer = openRouterData?.architecture?.tokenizer,
                instructType = openRouterData?.architecture?.instruct_type,
                isModerated = openRouterData?.top_provider?.is_moderated,
                // Hugging Face
                huggingFaceAuthor = huggingFaceData?.author,
                huggingFaceDownloads = huggingFaceData?.downloads,
                huggingFaceLikes = huggingFaceData?.likes,
                huggingFaceTags = huggingFaceData?.tags,
                huggingFacePipelineTag = huggingFaceData?.pipeline_tag,
                huggingFaceLibrary = huggingFaceData?.library_name,
                huggingFaceLicense = huggingFaceData?.cardData?.license,
                huggingFaceLastModified = huggingFaceData?.lastModified,
                huggingFaceGated = huggingFaceData?.gated,
                // AI-generated
                aiDescription = aiDescription
            )

        } catch (e: Exception) {
            errorMessage = "Error fetching model info: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Model Info",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFFF9800))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Fetching model information...",
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        } else if (errorMessage != null) {
            Text(
                text = errorMessage ?: "",
                color = Color(0xFFFF6B6B)
            )
        } else if (modelInfo != null) {
            val info = modelInfo ?: return

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Model name header
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A3A4A)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = info.modelName,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "Provider: ${info.provider.displayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFAAAAAA)
                            )
                            if (info.openRouterName != null && info.openRouterName != info.modelName) {
                                Text(
                                    text = "Name: ${info.openRouterName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFAAAAAA)
                                )
                            }
                        }
                    }
                }

                // AI-generated introduction (from model_info agent)
                if (info.aiDescription != null) {
                    item {
                        ModelInfoSection(title = "AI Introduction") {
                            Text(
                                text = info.aiDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Description from OpenRouter
                if (info.openRouterDescription != null) {
                    item {
                        ModelInfoSection(title = "Description") {
                            Text(
                                text = info.openRouterDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Technical specs
                item {
                    ModelInfoSection(title = "Technical Specifications") {
                        if (info.contextLength != null) {
                            ModelInfoRow("Context Length", formatNumber(info.contextLength) + " tokens")
                        }
                        if (info.maxCompletionTokens != null) {
                            ModelInfoRow("Max Completion", formatNumber(info.maxCompletionTokens) + " tokens")
                        }
                        if (info.modality != null) {
                            ModelInfoRow("Modality", info.modality)
                        }
                        if (info.tokenizer != null) {
                            ModelInfoRow("Tokenizer", info.tokenizer)
                        }
                        if (info.instructType != null) {
                            ModelInfoRow("Instruct Type", info.instructType)
                        }
                        if (info.isModerated != null) {
                            ModelInfoRow("Moderated", if (info.isModerated) "Yes" else "No")
                        }
                    }
                }

                // Pricing
                if (info.promptPricing != null || info.completionPricing != null) {
                    item {
                        ModelInfoSection(title = "Pricing (per token)") {
                            if (info.promptPricing != null) {
                                val price = info.promptPricing.toDoubleOrNull()
                                if (price != null) {
                                    ModelInfoRow("Input", formatPricing(price))
                                }
                            }
                            if (info.completionPricing != null) {
                                val price = info.completionPricing.toDoubleOrNull()
                                if (price != null) {
                                    ModelInfoRow("Output", formatPricing(price))
                                }
                            }
                        }
                    }
                }

                // Hugging Face info
                val hasHuggingFaceInfo = info.huggingFaceAuthor != null ||
                        info.huggingFaceDownloads != null ||
                        info.huggingFaceLikes != null

                if (hasHuggingFaceInfo) {
                    item {
                        ModelInfoSection(title = "Hugging Face") {
                            if (info.huggingFaceAuthor != null) {
                                ModelInfoRow("Author", info.huggingFaceAuthor)
                            }
                            if (info.huggingFaceDownloads != null) {
                                ModelInfoRow("Downloads", formatNumber(info.huggingFaceDownloads))
                            }
                            if (info.huggingFaceLikes != null) {
                                ModelInfoRow("Likes", formatNumber(info.huggingFaceLikes))
                            }
                            if (info.huggingFacePipelineTag != null) {
                                ModelInfoRow("Pipeline", info.huggingFacePipelineTag)
                            }
                            if (info.huggingFaceLibrary != null) {
                                ModelInfoRow("Library", info.huggingFaceLibrary)
                            }
                            if (info.huggingFaceLicense != null) {
                                ModelInfoRow("License", info.huggingFaceLicense)
                            }
                            if (info.huggingFaceGated == true) {
                                ModelInfoRow("Access", "Gated (requires approval)")
                            }
                            if (info.huggingFaceLastModified != null) {
                                ModelInfoRow("Last Updated", info.huggingFaceLastModified.take(10))
                            }
                        }
                    }
                }

                // Tags
                if (!info.huggingFaceTags.isNullOrEmpty()) {
                    item {
                        ModelInfoSection(title = "Tags") {
                            Text(
                                text = info.huggingFaceTags.take(20).joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCCCCCC)
                            )
                        }
                    }
                }

                // No info found message
                val hasAnyInfo = info.openRouterDescription != null ||
                        info.contextLength != null ||
                        hasHuggingFaceInfo

                if (!hasAnyInfo) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF3A3A4A)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No additional information found for this model in OpenRouter or Hugging Face databases.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFAAAAAA)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelInfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF9800)
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAAAAA)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
    }
}

private fun formatNumber(number: Number): String {
    val value = number.toLong()
    return when {
        value >= 1_000_000_000 -> String.format("%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1fK", value / 1_000.0)
        else -> value.toString()
    }
}

private fun formatPricing(pricePerToken: Double): String {
    // Convert to price per million tokens
    val pricePerMillion = pricePerToken * 1_000_000
    return when {
        pricePerMillion >= 1 -> String.format("$%.2f / 1M tokens", pricePerMillion)
        pricePerMillion >= 0.01 -> String.format("$%.4f / 1M tokens", pricePerMillion)
        else -> String.format("$%.6f / 1M tokens", pricePerMillion)
    }
}
