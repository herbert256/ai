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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.report.ContentWithThinkSections
import com.ai.ui.report.formatPricingPerMillion
import com.ai.ui.settings.AgentEditScreen
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
    onNavigateToModelInfo: (AppService, String) -> Unit
) {
    BackHandler { onBackToAiSetup() }
    var searchQuery by rememberSaveable { mutableStateOf("") }

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
                ModelSearchResultCard(
                    item = item,
                    isVisionCapable = aiSettings.isVisionCapable(item.provider, item.modelName),
                    isWebSearchCapable = aiSettings.isWebSearchCapable(item.provider, item.modelName),
                    onClick = { onNavigateToModelInfo(item.provider, item.modelName) }
                )
            }
        }
    }
}

@Composable
private fun ModelSearchResultCard(item: ModelSearchItem, isVisionCapable: Boolean, isWebSearchCapable: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val pricing = remember(item.provider, item.modelName) { formatPricingPerMillion(context, item.provider, item.modelName) }

    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.modelName, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    com.ai.ui.shared.VisionBadge(isVisionCapable)
                    com.ai.ui.shared.WebSearchBadge(isWebSearchCapable)
                }
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
    onSaveSettings: (Settings) -> Unit,
    onTestAiModel: suspend (AppService, String, String) -> String?,
    onFetchModels: (AppService, String) -> Unit,
    onStartChat: (AppService, String) -> Unit,
    onNavigateToTracesForModel: (AppService, String) -> Unit,
    onNavigateToAddManualOverride: (AppService, String) -> Unit = { _, _ -> },
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
    var showAgentEdit by remember { mutableStateOf(false) }
    // null when the raw-view overlay isn't shown; otherwise (title, json) for the source.
    var rawView by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Trace count + usage entry are loaded once per (provider, model). They reflect
    // on-disk state at screen open; updates only land on next visit.
    val traceCount = remember(provider, modelName) {
        ApiTracer.getTraceFiles().count { it.model == modelName }
    }
    val usageEntry = remember(provider, modelName) {
        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        SettingsPreferences(prefs, context.filesDir).loadUsageStats()["${provider.id}::$modelName"]
    }
    val usageCost = remember(usageEntry) {
        usageEntry?.let {
            val pricing = PricingCache.getPricing(context, it.provider, it.model)
            it.inputTokens * pricing.promptPrice + it.outputTokens * pricing.completionPrice
        }
    }

    // Full-screen overlay rendering the raw JSON for one of the three
    // catalog sources (HuggingFace, OpenRouter, LiteLLM). Returns to
    // Model Info on back.
    rawView?.let { (title, body) ->
        ModelRawInfoScreen(title = title, body = body, onBack = { rawView = null }, onNavigateHome = onNavigateHome)
        return
    }

    // Full-screen overlay for creating an agent from this model. Mirrors the pattern
    // ModelSearchScreen used to use before its popup was retired.
    if (showAgentEdit) {
        AgentEditScreen(
            agent = Agent("", "${provider.displayName} $modelName", provider, modelName, aiSettings.getApiKey(provider)),
            aiSettings = aiSettings,
            existingNames = aiSettings.agents.map { it.name.lowercase() }.toSet(),
            onTestAiModel = onTestAiModel,
            onFetchModels = onFetchModels,
            onSave = { agent ->
                onSaveSettings(aiSettings.copy(agents = aiSettings.agents + agent))
                showAgentEdit = false
            },
            onBack = { showAgentEdit = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Fetch model info from OpenRouter + HuggingFace
    LaunchedEffect(provider, modelName) {
        isLoading = true; errorMessage = null
        try {
            val loadedInfo = withContext(Dispatchers.IO) {
                var orInfo: OpenRouterModelInfo? = null
                var hfInfo: HuggingFaceModelInfo? = null

                // Provider APIs and OpenRouter disagree on punctuation —
                // Anthropic ships "claude-opus-4-6" while OpenRouter
                // catalogs it as "anthropic/claude-opus-4.6". Normalize
                // both sides by treating '.' and '-' as equivalent so the
                // lookup connects regardless of which form the model id
                // uses.
                fun norm(s: String): String = s.replace('.', '-').lowercase()
                val targetNorm = norm(modelName)

                // OpenRouter lookup
                if (openRouterApiKey.isNotBlank()) {
                    try {
                        val models = ModelInfoCache.getOpenRouterModels(openRouterApiKey)
                        val orName = provider.openRouterName
                        val prefixedTargetNorm = if (orName != null) norm("$orName/$modelName") else null
                        orInfo = models.firstOrNull { norm(it.id) == prefixedTargetNorm }
                            ?: models.firstOrNull { norm(it.id).endsWith("/$targetNorm") }
                            ?: models.firstOrNull { norm(it.id) == targetNorm }
                    } catch (_: Exception) {}
                }

                // HuggingFace lookup
                if (huggingFaceApiKey.isNotBlank()) {
                    // Every HF repo is "<owner>/<repo>" — a bare model name with no slash is
                    // guaranteed to 404, so build the only path that has any chance of resolving.
                    // Try both dash and dot variants since some repos use either form in
                    // the version segment.
                    val baseCandidate = if ("/" in modelName) modelName
                        else (provider.openRouterName ?: provider.displayName).takeIf { it.isNotBlank() }?.let { "$it/$modelName" }
                    if (baseCandidate != null) {
                        val variants = sequenceOf(baseCandidate, baseCandidate.replace('-', '.'), baseCandidate.replace('.', '-')).distinct()
                        for (cand in variants) {
                            try {
                                val resp = ApiFactory.createHuggingFaceApi().getModelInfo(cand, "Bearer $huggingFaceApiKey")
                                if (resp.isSuccessful) { hfInfo = resp.body(); break }
                            } catch (_: Exception) {}
                        }
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

    // AI description — opt-in. The "model_info" internal prompt + the
    // page's own (provider, model) build a request that asks the model to
    // introduce itself. We only peek at PromptCache on screen open so a
    // previously-completed result shows immediately; otherwise the user
    // gets a button. The configured agent's resolved Parameters preset
    // still propagates so the user's temperature / max_tokens carry over.
    val scope = rememberCoroutineScope()
    val modelInfoPrompt = remember(aiSettings) {
        aiSettings.prompts.find { it.name.lowercase().contains("model_info") || it.name.lowercase().contains("model info") }
    }
    val pageApiKey = aiSettings.getApiKey(provider)
    val introResolvedPrompt = remember(modelInfoPrompt, provider, modelName) {
        modelInfoPrompt?.promptText?.replace("@MODEL@", modelName)?.replace("@PROVIDER@", provider.displayName)
            ?.replace("@AGENT@", "${provider.displayName} / $modelName")
    }
    val introCacheKey = remember(introResolvedPrompt, provider, modelName) {
        introResolvedPrompt?.let { PromptCache.keyFor(it, "${provider.id}:$modelName") }
    }
    val canRequestIntro = modelInfoPrompt != null && pageApiKey.isNotBlank()
    LaunchedEffect(introCacheKey) {
        introCacheKey?.let { PromptCache.get(it) }?.let { aiDescription = it }
    }
    val requestIntroduction: () -> Unit = req@{
        if (!canRequestIntro || isAiLoading) return@req
        val prompt = introResolvedPrompt ?: return@req
        val key = introCacheKey ?: return@req
        val configuredAgent = aiSettings.getAgentById(modelInfoPrompt!!.agentId) ?: return@req
        val selfAgent = Agent(
            id = "model_info_self:${provider.id}:$modelName",
            name = "${provider.displayName} / $modelName",
            provider = provider, model = modelName,
            apiKey = pageApiKey
        )
        scope.launch {
            isAiLoading = true
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.analyzePlayerWithAgent(selfAgent, prompt, aiSettings.resolveAgentParameters(configuredAgent))
                }
                if (response.isSuccess) {
                    aiDescription = response.analysis
                    response.analysis?.let { PromptCache.put(key, it) }
                }
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

                    // Action buttons: start a chat, or create an agent for this model.
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onStartChat(provider, modelName) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                            ) { Text("Start AI Chat", maxLines = 1, softWrap = false) }
                            Button(
                                onClick = { showAgentEdit = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                            ) { Text("Create AI Agent", maxLines = 1, softWrap = false) }
                        }
                    }

                    // Catalog raw-data buttons — green when the source has an
                    // entry for this (provider, model), red otherwise.
                    // Tapping opens the pretty-printed JSON in a sub-screen
                    // so the user can inspect the full record (capability
                    // flags, context window, multi-modal pricing, etc.).
                    item {
                        val gson = remember { com.ai.data.createAppGson(prettyPrint = true) }
                        val hasHF = info?.huggingFaceInfo != null
                        val hasOR = info?.openRouterInfo != null
                        val liteLLMRaw = remember(provider, modelName) {
                            PricingCache.getLiteLLMRawEntry(context, provider, modelName)
                        }
                        val hasLiteLLM = liteLLMRaw != null
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val body = info?.huggingFaceInfo?.let { gson.toJson(it) } ?: "(no HuggingFace data)"
                                    rawView = "HuggingFace · $modelName" to body
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = if (hasHF) AppColors.Green else AppColors.Red),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("HuggingFace", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                            Button(
                                onClick = {
                                    val body = info?.openRouterInfo?.let { gson.toJson(it) } ?: "(no OpenRouter data)"
                                    rawView = "OpenRouter · $modelName" to body
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = if (hasOR) AppColors.Green else AppColors.Red),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("OpenRouter", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                            Button(
                                onClick = {
                                    rawView = "LiteLLM · $modelName" to (liteLLMRaw ?: "(no LiteLLM data)")
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = if (hasLiteLLM) AppColors.Green else AppColors.Red),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text("LiteLLM", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                        }
                    }

                    // Capability summary — read-only. The user pins overrides
                    // through the Manual model overrides CRUD (Add manual
                    // override button below). Source line tells the user
                    // where each effective flag came from.
                    item {
                        val cfgVision = aiSettings.getProvider(provider).visionModels
                        val cfgWeb = aiSettings.getProvider(provider).webSearchModels
                        val visionOverride = aiSettings.modelTypeOverrides.firstOrNull {
                            it.providerId == provider.id && it.modelId == modelName && it.supportsVision
                        } != null
                        val webOverride = aiSettings.modelTypeOverrides.firstOrNull {
                            it.providerId == provider.id && it.modelId == modelName && it.supportsWebSearch
                        } != null
                        val visionExplicit = modelName in cfgVision || visionOverride
                        val webExplicit = modelName in cfgWeb || webOverride
                        val visionHeuristic = !visionExplicit && com.ai.data.ModelType.inferVision(modelName)
                        val webHeuristic = !webExplicit && com.ai.data.ModelType.inferWebSearch(provider, modelName)
                        val visionEffective = visionExplicit || visionHeuristic
                        val webEffective = webExplicit || webHeuristic
                        fun rowText(label: String, on: Boolean, explicit: Boolean, heuristic: Boolean): Pair<String, String> {
                            val state = if (on) "yes" else "no"
                            val src = when {
                                explicit -> "Pinned."
                                heuristic -> "Auto-detected from name."
                                else -> "—"
                            }
                            return "$label: $state" to src
                        }
                        val (visionLabel, visionSrc) = rowText("Vision 👁", visionEffective, visionExplicit, visionHeuristic)
                        val (webLabel, webSrc) = rowText("Web search 🌐", webEffective, webExplicit, webHeuristic)
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Capabilities", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                Row {
                                    Text(visionLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(visionSrc, fontSize = 12.sp, color = AppColors.TextTertiary)
                                }
                                Row {
                                    Text(webLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(webSrc, fontSize = 12.sp, color = AppColors.TextTertiary)
                                }
                            }
                        }
                    }

                    // Add / edit manual override — opens the same form the
                    // Manual model types CRUD uses, pre-filled with this
                    // (provider, model). If an override already exists for
                    // this pair the form opens in edit mode.
                    item {
                        Button(
                            onClick = { onNavigateToAddManualOverride(provider, modelName) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                        ) { Text("Add manual override", fontSize = 14.sp, maxLines = 1, softWrap = false) }
                    }

                    // Trace count card — clickable, opens the Traces list filtered to this model.
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = traceCount > 0) {
                                onNavigateToTracesForModel(provider, modelName)
                            },
                            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("API Traces", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                    Text(
                                        if (traceCount == 0) "No traces recorded for this model" else "$traceCount traces — tap to view",
                                        fontSize = 12.sp, color = AppColors.TextTertiary
                                    )
                                }
                                Text("$traceCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                if (traceCount > 0) Text(" >", fontSize = 16.sp, color = AppColors.Blue)
                            }
                        }
                    }

                    // Per-tier price snapshot — LiteLLM / OpenRouter / Override
                    // shown as $/M-token rows when populated. Default tier is
                    // omitted; if all three are missing, render a single
                    // "no source-specific price" line so the card still
                    // explains why the cost lookup will fall back.
                    item {
                        val breakdown = remember(provider, modelName) {
                            PricingCache.getTierBreakdown(context, provider, modelName)
                        }
                        val rows = listOfNotNull(
                            breakdown.litellm?.let { "LiteLLM" to it },
                            breakdown.openrouter?.let { "OpenRouter" to it },
                            breakdown.override?.let { "Override" to it }
                        )
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Costs (per million tokens)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                if (rows.isEmpty()) {
                                    Text("No LiteLLM / OpenRouter / Override entry — lookup falls back to the built-in default.",
                                        fontSize = 12.sp, color = AppColors.TextTertiary)
                                } else {
                                    rows.forEach { (label, p) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                            Text(
                                                "${"%.4f".format(Locale.US, p.promptPrice * 1_000_000)} / ${"%.4f".format(Locale.US, p.completionPrice * 1_000_000)}",
                                                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.Green
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Usage entry for this provider/model (cumulative across reports + chats).
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("AI Usage", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                if (usageEntry == null) {
                                    Text("No usage recorded yet for this model", fontSize = 12.sp, color = AppColors.TextTertiary)
                                } else {
                                    Text(
                                        "${usageEntry.callCount} calls, ${formatCompactNumber(usageEntry.inputTokens)} in / ${formatCompactNumber(usageEntry.outputTokens)} out",
                                        fontSize = 13.sp, color = Color.White
                                    )
                                    usageCost?.let {
                                        Text(
                                            "Cost: " + if (it < 0.01 && it > 0) String.format(Locale.US, "$%.6f", it) else String.format(Locale.US, "$%.4f", it),
                                            fontSize = 13.sp, color = AppColors.Green
                                        )
                                    }
                                }
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

                    // AI description — opt-in. Shows the "Ask the model to
                    // introduce itself" button when no cached result is
                    // available; the spinner while a request is in flight;
                    // the rendered Markdown when we have a result.
                    if (canRequestIntro || aiDescription != null) {
                        item {
                            ModelInfoSection("AI Introduction", null) {
                                when {
                                    isAiLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text("Generating...", fontSize = 13.sp, color = AppColors.TextTertiary)
                                    }
                                    aiDescription != null -> ContentWithThinkSections(aiDescription ?: "")
                                    else -> Button(
                                        onClick = requestIntroduction,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                                    ) { Text("Ask the model to introduce itself", fontSize = 13.sp, maxLines = 1, softWrap = false) }
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

/** Full-screen pretty-printed JSON view used by the HuggingFace /
 *  OpenRouter / LiteLLM buttons on Model Info. Monospace, scrollable
 *  in both axes so long lines aren't cut off. */
@Composable
private fun ModelRawInfoScreen(
    title: String,
    body: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = title, onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
            modifier = Modifier.fillMaxSize()
        ) {
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()
            Box(modifier = Modifier.padding(12.dp).verticalScroll(vScroll).horizontalScroll(hScroll)) {
                Text(
                    text = body,
                    fontSize = 12.sp,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
