package com.ai.ui.models

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.report.formatPricingPerMillion
import com.ai.ui.settings.AgentEditScreen
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ModelSearchItem(val provider: AppService, val providerName: String, val modelName: String)

private data class ModelInfoData(
    val openRouterInfo: OpenRouterModelInfo? = null,
    val huggingFaceInfo: HuggingFaceModelInfo? = null,
    val hasPricing: Boolean = false
)

private object ModelInfoCache {
    @Volatile private var apiKey: String? = null
    @Volatile private var openRouterModels: List<OpenRouterModelInfo>? = null

    suspend fun getOpenRouterModels(apiKey: String): List<OpenRouterModelInfo> {
        if (apiKey.isBlank()) return emptyList()
        if (this.apiKey == apiKey) {
            openRouterModels?.let { return it }
        }
        val api = ApiFactory.createOpenRouterModelsApi("https://openrouter.ai/api/")
        val response = api.listModelsDetailed("Bearer $apiKey")
        val models = if (response.isSuccessful) response.body()?.data ?: emptyList() else emptyList()
        this.apiKey = apiKey
        openRouterModels = models
        return models
    }
}

@Composable
fun ModelSearchScreen(
    aiSettings: Settings,
    loadingModelsFor: Set<AppService>,
    onBackToAiSetup: () -> Unit,
    onBackToHome: () -> Unit,
    onSaveSettings: (Settings) -> Unit,
    onTestAiModel: suspend (AppService, String, String) -> String?,
    onFetchModels: (AppService, String) -> Unit,
    onNavigateToChatParams: (AppService, String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit
) {
    BackHandler { onBackToAiSetup() }
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<ModelSearchItem?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }
    var showAgentEdit by remember { mutableStateOf(false) }

    // Build aggregated model list from all active providers
    val allModels = remember(aiSettings) {
        aiSettings.getActiveServices().flatMap { service ->
            val models = aiSettings.getModels(service)
            if (models.isNotEmpty()) models.map { ModelSearchItem(service, service.displayName, it) }
            else listOf(ModelSearchItem(service, service.displayName, aiSettings.getModel(service)))
        }.sortedWith(compareBy({ it.providerName }, { it.modelName }))
    }

    val filteredModels = remember(searchQuery, allModels) {
        if (searchQuery.isBlank()) allModels
        else {
            val q = searchQuery.lowercase()
            allModels.filter { it.providerName.lowercase().contains(q) || it.modelName.lowercase().contains(q) }
        }
    }

    val isLoading = loadingModelsFor.isNotEmpty()

    // Full-screen overlay for creating agent from model
    if (showAgentEdit && selectedModel != null) {
        val m = selectedModel!!
        AgentEditScreen(
            agent = Agent("", "${m.providerName} ${m.modelName}", m.provider, m.modelName, aiSettings.getApiKey(m.provider)),
            aiSettings = aiSettings,
            existingNames = aiSettings.agents.map { it.name.lowercase() }.toSet(),
            onTestAiModel = onTestAiModel,
            onFetchModels = onFetchModels,
            onSave = { agent ->
                val updated = aiSettings.copy(agents = aiSettings.agents + agent)
                onSaveSettings(updated)
                showAgentEdit = false; selectedModel = null
            },
            onBack = { showAgentEdit = false; selectedModel = null },
            onNavigateHome = onBackToHome
        )
        return
    }

    // Action dialog for selected model
    if (showActionDialog && selectedModel != null) {
        val m = selectedModel!!
        AlertDialog(onDismissRequest = { showActionDialog = false; selectedModel = null },
            title = { Text("${m.providerName} / ${m.modelName}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showActionDialog = false; onNavigateToChatParams(m.provider, m.modelName) }, modifier = Modifier.fillMaxWidth()) { Text("Start AI Chat", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = { showActionDialog = false; showAgentEdit = true }, modifier = Modifier.fillMaxWidth()) { Text("Create AI Agent", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = { showActionDialog = false; onNavigateToModelInfo(m.provider, m.modelName); selectedModel = null }, modifier = Modifier.fillMaxWidth()) { Text("Model Info", maxLines = 1, softWrap = false) }
            }},
            confirmButton = {}, dismissButton = { TextButton(onClick = { showActionDialog = false; selectedModel = null }) { Text("Cancel", maxLines = 1, softWrap = false) } })
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Models", onBackClick = onBackToAiSetup, onAiClick = onBackToHome)

        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search models...") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, colors = AppColors.outlinedFieldColors())

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading models...", fontSize = 12.sp, color = AppColors.TextTertiary)
            }
        } else {
            Text("${filteredModels.size} models", fontSize = 12.sp, color = AppColors.TextTertiary)
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filteredModels, key = { "${it.provider.id}:${it.modelName}" }) { item ->
                ModelSearchResultCard(item = item, onClick = { selectedModel = item; showActionDialog = true })
            }
        }
    }
}

@Composable
private fun ModelSearchResultCard(item: ModelSearchItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val pricing = remember(item.provider, item.modelName) { formatPricingPerMillion(context, item.provider, item.modelName) }

    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.modelName, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.providerName, fontSize = 12.sp, color = AppColors.Blue)
            }
            Text(pricing.text, fontSize = 11.sp, color = if (pricing.isDefault) AppColors.PricingDefault else AppColors.PricingReal)
        }
    }
}

// ===== Model Info Screen =====

@Composable
fun ModelInfoScreen(
    provider: AppService,
    modelName: String,
    openRouterApiKey: String,
    huggingFaceApiKey: String,
    aiSettings: Settings,
    repository: com.ai.data.AnalysisRepository,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current
    var modelInfo by remember { mutableStateOf<ModelInfoData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var aiDescription by remember { mutableStateOf<String?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }

    // Fetch model info from OpenRouter + HuggingFace
    LaunchedEffect(provider, modelName) {
        isLoading = true; errorMessage = null
        try {
            val loadedInfo = withContext(Dispatchers.IO) {
                var orInfo: OpenRouterModelInfo? = null
                var hfInfo: HuggingFaceModelInfo? = null

                // OpenRouter lookup
                if (openRouterApiKey.isNotBlank()) {
                    try {
                        val models = ModelInfoCache.getOpenRouterModels(openRouterApiKey)
                        val orName = provider.openRouterName
                        orInfo = models.find { it.id == "$orName/$modelName" }
                            ?: models.find { it.id.endsWith("/$modelName") }
                            ?: models.find { it.id == modelName }
                    } catch (_: Exception) {}
                }

                // HuggingFace lookup
                if (huggingFaceApiKey.isNotBlank()) {
                    val hfApi = ApiFactory.createHuggingFaceApi()
                    val candidates = listOfNotNull(
                        modelName,
                        modelName.takeIf { "/" !in it }?.let { "${provider.openRouterName ?: provider.displayName}/$it" }
                    ).distinct()
                    for (candidate in candidates) {
                        try {
                            val auth = "Bearer $huggingFaceApiKey"
                            val resp = hfApi.getModelInfo(candidate, auth)
                            if (resp.isSuccessful) { hfInfo = resp.body(); break }
                        } catch (_: Exception) {}
                    }
                }

                ModelInfoData(openRouterInfo = orInfo, huggingFaceInfo = hfInfo, hasPricing = orInfo?.pricing != null)
            }
            modelInfo = loadedInfo
        } catch (e: Exception) {
            errorMessage = e.message
        } finally {
            isLoading = false
        }
    }

    // AI description (independent load)
    LaunchedEffect(provider, modelName) {
        val modelInfoPrompt = aiSettings.prompts.find { it.name.lowercase().contains("model_info") || it.name.lowercase().contains("model info") }
        if (modelInfoPrompt != null) {
            val agent = aiSettings.getAgentById(modelInfoPrompt.agentId) ?: return@LaunchedEffect
            val effectiveAgent = agent.copy(apiKey = aiSettings.getEffectiveApiKeyForAgent(agent), model = aiSettings.getEffectiveModelForAgent(agent))
            if (effectiveAgent.apiKey.isBlank()) return@LaunchedEffect
            isAiLoading = true
            try {
                val resolvedPrompt = modelInfoPrompt.promptText
                    .replace("@MODEL@", modelName).replace("@PROVIDER@", provider.displayName)
                    .replace("@AGENT@", agent.name)
                val response = withContext(Dispatchers.IO) {
                    repository.analyzePlayerWithAgent(effectiveAgent, resolvedPrompt, aiSettings.resolveAgentParameters(agent))
                }
                if (response.isSuccess) aiDescription = response.analysis
            } catch (_: Exception) {} finally { isAiLoading = false }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Model Info", onBackClick = onNavigateBack, onAiClick = onNavigateHome)

        when {
            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading model info...", color = AppColors.TextTertiary)
                }
            }
            errorMessage != null -> Text("Error: $errorMessage", color = AppColors.Red)
            else -> {
                val info = modelInfo
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Model name header
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(modelName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(provider.displayName, fontSize = 14.sp, color = AppColors.Blue)
                            }
                        }
                    }

                    // OpenRouter description
                    info?.openRouterInfo?.description?.let { desc ->
                        item {
                            ModelInfoSection("Description", "OpenRouter") {
                                Text(desc, fontSize = 13.sp, color = Color(0xFFCCCCCC))
                            }
                        }
                    }

                    // Technical specs
                    info?.openRouterInfo?.let { or ->
                        item {
                            ModelInfoSection("Technical Specifications", "OpenRouter") {
                                or.context_length?.let { ModelInfoRow("Context Length", formatCompactNumber(it.toLong())) }
                                or.top_provider?.max_completion_tokens?.let { ModelInfoRow("Max Completion", formatCompactNumber(it.toLong())) }
                                or.architecture?.modality?.let { ModelInfoRow("Modality", it) }
                                or.architecture?.tokenizer?.let { ModelInfoRow("Tokenizer", it) }
                                or.architecture?.instruct_type?.let { ModelInfoRow("Instruct Type", it) }
                                or.top_provider?.is_moderated?.let { ModelInfoRow("Moderated", if (it) "Yes" else "No") }
                            }
                        }
                    }

                    // Pricing
                    info?.openRouterInfo?.pricing?.let { p ->
                        item {
                            ModelInfoSection("Pricing", "OpenRouter") {
                                p.prompt?.toDoubleOrNull()?.let { ModelInfoRow("Input", formatTokenPricePerMillion(it)) }
                                p.completion?.toDoubleOrNull()?.let { ModelInfoRow("Output", formatTokenPricePerMillion(it)) }
                                p.image?.toDoubleOrNull()?.let { ModelInfoRow("Image", formatTokenPricePerMillion(it)) }
                            }
                        }
                    }

                    // HuggingFace info
                    info?.huggingFaceInfo?.let { hf ->
                        item {
                            ModelInfoSection("HuggingFace", "HuggingFace") {
                                hf.author?.let { ModelInfoRow("Author", it) }
                                hf.pipeline_tag?.let { ModelInfoRow("Pipeline", it) }
                                hf.library_name?.let { ModelInfoRow("Library", it) }
                                hf.downloads?.let { ModelInfoRow("Downloads", formatCompactNumber(it)) }
                                hf.likes?.let { ModelInfoRow("Likes", formatCompactNumber(it.toLong())) }
                                hf.cardData?.license?.let { ModelInfoRow("License", it) }
                                hf.cardData?.base_model?.let { ModelInfoRow("Base Model", it) }
                                hf.cardData?.language?.let { if (it.isNotEmpty()) ModelInfoRow("Languages", it.joinToString(", ")) }
                            }
                        }
                    }

                    // Tags
                    val tags = info?.huggingFaceInfo?.tags
                    if (!tags.isNullOrEmpty()) {
                        item {
                            ModelInfoSection("Tags", "HuggingFace") {
                                Text(tags.joinToString(", "), fontSize = 12.sp, color = AppColors.TextTertiary)
                            }
                        }
                    }

                    // AI description
                    if (isAiLoading || aiDescription != null) {
                        item {
                            ModelInfoSection("AI Introduction", null) {
                                if (isAiLoading) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text("Generating...", fontSize = 13.sp, color = AppColors.TextTertiary)
                                    }
                                } else {
                                    Text(aiDescription ?: "", fontSize = 13.sp, color = Color(0xFFCCCCCC), lineHeight = 18.sp)
                                }
                            }
                        }
                    }

                    // No info fallback
                    if (info?.openRouterInfo == null && info?.huggingFaceInfo == null && aiDescription == null && !isAiLoading) {
                        item {
                            Text("No information found for this model. Ensure OpenRouter and HuggingFace API keys are configured.",
                                fontSize = 13.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelInfoSection(title: String, source: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            content()
            if (source != null) {
                Text("Source: $source", fontSize = 10.sp, color = AppColors.TextDim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = AppColors.TextTertiary)
        Text(value, fontSize = 13.sp, color = Color.White)
    }
}
