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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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

/** Compact token-count label — 1024 → "1k", 128000 → "128k", 1_000_000
 *  → "1M". Used in the Min-context dropdown so the option labels stay
 *  short enough not to wrap. */
private fun formatTokenCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
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
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Optional filters — null/false = unrestricted. All saveable so the
    // choices survive back-stack visits.
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var providerFilterId by rememberSaveable { mutableStateOf<String?>(null) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var visionOnly by rememberSaveable { mutableStateOf(false) }
    var webSearchOnly by rememberSaveable { mutableStateOf(false) }
    // Minimum context length tier (tokens). null = no filter. Tiers picked
    // to span the common ranges — 8k for legacy, 32k for early Claude /
    // GPT-3.5-turbo-16k, 128k for GPT-4o, 200k for Claude 3+, 1M for
    // Gemini 1.5/2.x and frontier Anthropic.
    var minContextFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var hasPricingOnly by rememberSaveable { mutableStateOf(false) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    // Inverse of [hasPricingOnly] — only models running on the
    // ModelPricing("default", 25/75 ¢/M-token) fallback. Useful for
    // hunting down which entries still need a real pricing source.
    var defaultPricingOnly by rememberSaveable { mutableStateOf(false) }
    // Surface entries where 2+ catalog tiers disagree on the prompt /
    // completion rate beyond 1% — driver of "which model has the
    // sources fighting?"
    var conflictingPricingOnly by rememberSaveable { mutableStateOf(false) }

    val activeServices = remember(aiSettings) { aiSettings.getActiveServices().sortedBy { it.displayName.lowercase() } }
    val providerFilter = providerFilterId?.let { id -> activeServices.firstOrNull { it.id == id } }

    // Build aggregated model list from all active providers. Settings.withModels
    // dedupes per-provider before persisting, so cross-provider uniqueness on
    // (provider.id, modelName) is guaranteed by construction.
    val allModels = remember(aiSettings) {
        activeServices.flatMap { service ->
            val models = aiSettings.getModels(service)
            if (models.isNotEmpty()) models.map { ModelSearchItem(service, service.displayName, it) }
            else listOf(ModelSearchItem(service, service.displayName, aiSettings.getModel(service)))
        }.sortedWith(compareBy({ it.providerName }, { it.modelName }))
    }

    val filteredModels = remember(searchQuery, typeFilter, providerFilterId, visionOnly, webSearchOnly, minContextFilter, hasPricingOnly, freeOnly, defaultPricingOnly, conflictingPricingOnly, allModels) {
        var list = allModels
        if (typeFilter != null) list = list.filter { aiSettings.getModelType(it.provider, it.modelName) == typeFilter }
        if (providerFilterId != null) list = list.filter { it.provider.id == providerFilterId }
        if (visionOnly) list = list.filter { aiSettings.isVisionCapable(it.provider, it.modelName) }
        if (webSearchOnly) list = list.filter { aiSettings.isWebSearchCapable(it.provider, it.modelName) }
        if (minContextFilter != null) {
            val min = minContextFilter!!
            list = list.filter { item ->
                // Provider's own /models endpoint first, then models.dev
                // fallback. Models with no context length data anywhere
                // are excluded — there's no way to know if they qualify.
                val ctx = aiSettings.getProvider(item.provider).modelCapabilities[item.modelName]?.contextLength
                    ?: com.ai.data.PricingCache.modelsDevMaxInputTokens(item.provider, item.modelName)
                ctx != null && ctx >= min
            }
        }
        if (hasPricingOnly || freeOnly || defaultPricingOnly) {
            list = list.filter { item ->
                val pricing = com.ai.data.PricingCache.getPricing(context, item.provider, item.modelName)
                val real = pricing.source != "DEFAULT"
                val free = real && pricing.promptPrice == 0.0 && pricing.completionPrice == 0.0
                (!hasPricingOnly || real) && (!freeOnly || free) && (!defaultPricingOnly || !real)
            }
        }
        if (conflictingPricingOnly) {
            list = list.filter { item ->
                com.ai.data.PricingCache.pricesConflict(context, item.provider, item.modelName)
            }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            list = list.filter { it.providerName.lowercase().contains(q) || it.modelName.lowercase().contains(q) }
        }
        list
    }

    val isLoading = loadingModelsFor.isNotEmpty()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Models", onBackClick = onBackToAiSetup, onAiClick = onBackToHome)

        // Type + Provider dropdowns share a row; capability checkboxes
        // sit on a row below. Each dropdown is anchored in its own Box
        // so the menu drops from the button it belongs to.
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { typeMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, AppColors.BorderUnfocused)
                ) {
                    Text(
                        text = typeFilter?.replaceFirstChar { it.uppercase() } ?: "All types",
                        fontSize = 13.sp,
                        color = if (typeFilter != null) Color.White else AppColors.TextTertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("▾", color = AppColors.TextTertiary)
                }
                DropdownMenu(
                    expanded = typeMenuExpanded,
                    onDismissRequest = { typeMenuExpanded = false },
                    modifier = Modifier.background(Color(0xFF2D2D2D))
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("All types", fontSize = 13.sp,
                                color = if (typeFilter == null) AppColors.Blue else Color.White)
                        },
                        onClick = { typeFilter = null; typeMenuExpanded = false }
                    )
                    com.ai.data.ModelType.ALL.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Text(type.replaceFirstChar { it.uppercase() }, fontSize = 13.sp,
                                    color = if (typeFilter == type) AppColors.Blue else Color.White)
                            },
                            onClick = { typeFilter = type; typeMenuExpanded = false }
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { providerMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, AppColors.BorderUnfocused)
                ) {
                    Text(
                        text = providerFilter?.displayName ?: "All providers",
                        fontSize = 13.sp,
                        color = if (providerFilter != null) Color.White else AppColors.TextTertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("▾", color = AppColors.TextTertiary)
                }
                DropdownMenu(
                    expanded = providerMenuExpanded,
                    onDismissRequest = { providerMenuExpanded = false },
                    modifier = Modifier.background(Color(0xFF2D2D2D))
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("All providers", fontSize = 13.sp,
                                color = if (providerFilterId == null) AppColors.Blue else Color.White)
                        },
                        onClick = { providerFilterId = null; providerMenuExpanded = false }
                    )
                    activeServices.forEach { service ->
                        val mc = aiSettings.getModels(service).size
                        DropdownMenuItem(
                            text = {
                                Text("${service.displayName} ($mc)", fontSize = 13.sp,
                                    color = if (providerFilterId == service.id) AppColors.Blue else Color.White)
                            },
                            onClick = { providerFilterId = service.id; providerMenuExpanded = false }
                        )
                    }
                }
            }
        }

        // Min-context dropdown — sits on its own row because the label
        // is wider than the chip-style filters below.
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            OutlinedButton(
                onClick = { contextMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, AppColors.BorderUnfocused)
            ) {
                val label = minContextFilter?.let { "Min context ≥ ${formatTokenCount(it)}" } ?: "Any context length"
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = if (minContextFilter != null) Color.White else AppColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("▾", color = AppColors.TextTertiary)
            }
            DropdownMenu(
                expanded = contextMenuExpanded,
                onDismissRequest = { contextMenuExpanded = false },
                modifier = Modifier.background(Color(0xFF2D2D2D))
            ) {
                DropdownMenuItem(
                    text = {
                        Text("Any context length", fontSize = 13.sp,
                            color = if (minContextFilter == null) AppColors.Blue else Color.White)
                    },
                    onClick = { minContextFilter = null; contextMenuExpanded = false }
                )
                listOf(8_192, 32_768, 128_000, 200_000, 1_000_000).forEach { tier ->
                    DropdownMenuItem(
                        text = {
                            Text("≥ ${formatTokenCount(tier)}", fontSize = 13.sp,
                                color = if (minContextFilter == tier) AppColors.Blue else Color.White)
                        },
                        onClick = { minContextFilter = tier; contextMenuExpanded = false }
                    )
                }
            }
        }

        // Capability + pricing chips. FlowRow so they wrap onto a second
        // line on narrower screens instead of clipping.
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = visionOnly,
                onClick = { visionOnly = !visionOnly },
                label = { Text("👁 Vision", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Blue.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Blue
                )
            )
            FilterChip(
                selected = webSearchOnly,
                onClick = { webSearchOnly = !webSearchOnly },
                label = { Text("🌐 Web search", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Blue.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Blue
                )
            )
            FilterChip(
                selected = hasPricingOnly,
                onClick = { hasPricingOnly = !hasPricingOnly },
                label = { Text("💲 Has pricing", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Green.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Green
                )
            )
            FilterChip(
                selected = freeOnly,
                onClick = { freeOnly = !freeOnly },
                label = { Text("🎁 Free only", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Green.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Green
                )
            )
            FilterChip(
                selected = defaultPricingOnly,
                onClick = { defaultPricingOnly = !defaultPricingOnly },
                label = { Text("⚠ Default 25/75", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Orange.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Orange
                )
            )
            FilterChip(
                selected = conflictingPricingOnly,
                onClick = { conflictingPricingOnly = !conflictingPricingOnly },
                label = { Text("⚡ Conflicting pricing", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Red.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Red
                )
            )
        }

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
                    isReasoningCapable = aiSettings.isReasoningCapable(item.provider, item.modelName),
                    onClick = { onNavigateToModelInfo(item.provider, item.modelName) }
                )
            }
        }
    }
}

@Composable
private fun ModelSearchResultCard(item: ModelSearchItem, isVisionCapable: Boolean, isWebSearchCapable: Boolean, isReasoningCapable: Boolean, onClick: () -> Unit) {
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
                    com.ai.ui.shared.ReasoningBadge(isReasoningCapable)
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
    onNavigateToAddCostOverride: (AppService, String) -> Unit = { _, _ -> },
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
            onAddEndpoint = { p, ep ->
                val current = aiSettings.getEndpointsForProvider(p)
                onSaveSettings(aiSettings.withEndpoints(p, current + ep))
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

                // HuggingFace lookup — cached for a week (HuggingFaceCache),
                // including misses so we don't hammer HF with re-tries for
                // models that simply don't have an HF mirror.
                run {
                    val cached = HuggingFaceCache.get(context, provider.id, modelName)
                    if (cached != null) {
                        hfInfo = cached.info
                        return@run
                    }
                    if (huggingFaceApiKey.isBlank()) return@run
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
                    // Cache the outcome — Body or null — so the next visit
                    // within the TTL window short-circuits without a call.
                    HuggingFaceCache.put(context, provider.id, modelName, hfInfo)
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
    // Model-info template lives on GeneralSettings.modelInfoPrompt now
    // (used to be an Internal Prompt). Empty falls back to
    // SecondaryPrompts.DEFAULT_MODEL_INFO. Default AgentParameters since
    // there's no longer an agent binding to inherit a temperature /
    // max_tokens preset from.
    val modelInfoPromptTemplate = remember {
        val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val gs = SettingsPreferences(prefs, context.filesDir).loadGeneralSettings()
        gs.modelInfoPrompt.ifBlank { com.ai.data.SecondaryPrompts.DEFAULT_MODEL_INFO }
    }
    val pageApiKey = aiSettings.getApiKey(provider)
    val introResolvedPrompt = remember(modelInfoPromptTemplate, provider, modelName) {
        modelInfoPromptTemplate
            .replace("@MODEL@", modelName)
            .replace("@PROVIDER@", provider.displayName)
            .replace("@AGENT@", "${provider.displayName} / $modelName")
    }
    val introCacheKey = remember(introResolvedPrompt, provider, modelName) {
        PromptCache.keyFor(introResolvedPrompt, "${provider.id}:$modelName")
    }
    val canRequestIntro = pageApiKey.isNotBlank()
    LaunchedEffect(introCacheKey) {
        PromptCache.get(introCacheKey)?.let { aiDescription = it }
    }
    val requestIntroduction: () -> Unit = req@{
        if (!canRequestIntro || isAiLoading) return@req
        val selfAgent = Agent(
            id = "model_info_self:${provider.id}:$modelName",
            name = "${provider.displayName} / $modelName",
            provider = provider, model = modelName,
            apiKey = pageApiKey
        )
        scope.launch {
            isAiLoading = true
            val previousCategory = com.ai.data.ApiTracer.currentCategory
            com.ai.data.ApiTracer.currentCategory = "Model self-intro"
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.analyzePlayerWithAgent(selfAgent, introResolvedPrompt, AgentParameters())
                }
                if (response.isSuccess) {
                    aiDescription = response.analysis
                    response.analysis?.let { PromptCache.put(introCacheKey, it) }
                }
            } catch (_: Exception) {} finally {
                com.ai.data.ApiTracer.currentCategory = previousCategory
                isAiLoading = false
            }
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
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Actions", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
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
                        val modelsDevRaw = remember(provider, modelName) {
                            PricingCache.getModelsDevRawEntry(context, provider, modelName)
                        }
                        val hasModelsDev = modelsDevRaw != null
                        val heliconeRaw = remember(provider, modelName) {
                            PricingCache.getHeliconeRawEntry(context, provider, modelName)
                        }
                        val hasHelicone = heliconeRaw != null
                        val llmPricesRaw = remember(provider, modelName) {
                            PricingCache.getLLMPricesRawEntry(context, provider, modelName)
                        }
                        val hasLLMPrices = llmPricesRaw != null
                        val aaRaw = remember(provider, modelName) {
                            PricingCache.getArtificialAnalysisRawEntry(context, provider, modelName)
                        }
                        val hasAa = aaRaw != null
                        // Two rows of buttons in their own card — first the
                        // four catalog sources, then the three additional
                        // pricing tiers (Helicone / llm-prices.com / AA).
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Sources", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            val body = info?.huggingFaceInfo?.let { gson.toJson(it) } ?: "(no HuggingFace data)"
                                            rawView = "HuggingFace · $modelName" to body
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasHF) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("HuggingFace", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            val body = info?.openRouterInfo?.let { gson.toJson(it) } ?: "(no OpenRouter data)"
                                            rawView = "OpenRouter · $modelName" to body
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasOR) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("OpenRouter", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = "LiteLLM · $modelName" to (liteLLMRaw ?: "(no LiteLLM data)")
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasLiteLLM) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("LiteLLM", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = "models.dev · $modelName" to (modelsDevRaw ?: "(no models.dev data)")
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasModelsDev) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("models.dev", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            rawView = "Helicone · $modelName" to (heliconeRaw ?: "(no Helicone data)")
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasHelicone) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("Helicone", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = "llm-prices.com · $modelName" to (llmPricesRaw ?: "(no llm-prices data)")
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasLLMPrices) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("llm-prices", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = "Artificial Analysis · $modelName" to (aaRaw ?: "(no Artificial Analysis data)")
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasAa) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("Artificial Analysis", fontSize = 10.sp, maxLines = 1, softWrap = false) }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = {
                                        // Concatenate every source's raw JSON into one
                                        // pretty-printed dump — saves tapping seven
                                        // buttons in turn when comparing entries.
                                        val sections = listOf(
                                            "HuggingFace" to info?.huggingFaceInfo?.let { gson.toJson(it) },
                                            "OpenRouter" to info?.openRouterInfo?.let { gson.toJson(it) },
                                            "LiteLLM" to liteLLMRaw,
                                            "models.dev" to modelsDevRaw,
                                            "Helicone" to heliconeRaw,
                                            "llm-prices.com" to llmPricesRaw,
                                            "Artificial Analysis" to aaRaw
                                        )
                                        val body = sections.joinToString("\n\n") { (label, raw) ->
                                            "=== $label ===\n${raw ?: "(no $label data)"}"
                                        }
                                        rawView = "All sources · $modelName" to body
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                                ) { Text("Show all", fontSize = 13.sp, maxLines = 1, softWrap = false) }
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
                            breakdown.modelsDev?.let { "models.dev" to it },
                            breakdown.helicone?.let { "Helicone" to it },
                            breakdown.llmPrices?.let { "llm-prices.com" to it },
                            breakdown.artificialAnalysis?.let { "Artificial Analysis" to it },
                            breakdown.openrouter?.let { "OpenRouter" to it },
                            breakdown.override?.let { "Override" to it }
                        )
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Costs (per million tokens)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                if (rows.isEmpty()) {
                                    Text("No LiteLLM / models.dev / OpenRouter / Override entry — lookup falls back to the built-in default.",
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
                                        // Rerank-mode models bill per
                                        // search-unit, not per token —
                                        // surface that as an extra row
                                        // when LiteLLM provided one so
                                        // the user knows where the cost
                                        // is going to come from.
                                        if (p.perQueryPrice > 0.0) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("$label · per 1k searches",
                                                    fontSize = 11.sp, color = AppColors.TextTertiary,
                                                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                                                Text(
                                                    "${"%.2f".format(Locale.US, p.perQueryPrice * 1000)}",
                                                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.Green
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { onNavigateToAddCostOverride(provider, modelName) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                                ) { Text("Add manual cost override", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                            }
                        }
                    }

                    // Capability summary — read-only. The user pins overrides
                    // through the Manual model overrides CRUD (Add manual
                    // override button below). Source line walks the same
                    // layered lookup Settings.isVisionCapable /
                    // isWebSearchCapable use, so the displayed yes/no
                    // matches the value the rest of the app sees, and
                    // "Auto-detected from name" is genuinely the last
                    // tier (only fires when every catalog source is
                    // silent).
                    item {
                        val cfg = aiSettings.getProvider(provider)
                        val visionPinned = modelName in cfg.visionModels ||
                            aiSettings.modelTypeOverrides.any {
                                it.providerId == provider.id && it.modelId == modelName && it.supportsVision
                            }
                        val webPinned = modelName in cfg.webSearchModels ||
                            aiSettings.modelTypeOverrides.any {
                                it.providerId == provider.id && it.modelId == modelName && it.supportsWebSearch
                            }
                        val reasoningPinned = modelName in cfg.reasoningModels ||
                            aiSettings.modelTypeOverrides.any {
                                it.providerId == provider.id && it.modelId == modelName && it.supportsReasoning
                            }
                        val providerVision = cfg.modelCapabilities[modelName]?.supportsVision
                        val providerWeb = cfg.modelCapabilities[modelName]?.supportsFunctionCalling
                        val providerReasoning = cfg.modelCapabilities[modelName]?.supportsReasoning
                        val litellmVision = com.ai.data.PricingCache.liteLLMSupportsVision(provider, modelName)
                        val litellmWeb = com.ai.data.PricingCache.liteLLMSupportsWebSearch(provider, modelName)
                        val litellmReasoning = com.ai.data.PricingCache.liteLLMSupportsReasoning(provider, modelName)
                        val modelsDevVision = com.ai.data.PricingCache.modelsDevSupportsVision(provider, modelName)
                        val modelsDevWeb = com.ai.data.PricingCache.modelsDevSupportsToolCall(provider, modelName)
                        val modelsDevReasoning = com.ai.data.PricingCache.modelsDevSupportsReasoning(provider, modelName)
                        val visionEffective = aiSettings.isVisionCapable(provider, modelName)
                        val webEffective = aiSettings.isWebSearchCapable(provider, modelName)
                        val reasoningEffective = aiSettings.isReasoningCapable(provider, modelName)
                        // Walk the chain and label the first tier that
                        // produced the answer. Order mirrors the slow
                        // lookup in Settings exactly. Auto-detect lands
                        // last and only when every authoritative source
                        // is silent.
                        fun source(pinned: Boolean, prov: Boolean?, ll: Boolean?, md: Boolean?): String = when {
                            pinned -> "Pinned"
                            prov != null -> "Provider /models"
                            ll != null -> "LiteLLM"
                            md != null -> "models.dev"
                            else -> "Auto-detected from name"
                        }
                        val visionSrc = "Vision 👁: ${if (visionEffective) "yes" else "no"}" to
                            source(visionPinned, providerVision, litellmVision, modelsDevVision)
                        val webSrc = "Web search 🌐: ${if (webEffective) "yes" else "no"}" to
                            source(webPinned, providerWeb, litellmWeb, modelsDevWeb)
                        val reasoningSrc = "Thinking 🧠: ${if (reasoningEffective) "yes" else "no"}" to
                            source(reasoningPinned, providerReasoning, litellmReasoning, modelsDevReasoning)
                        val (visionLabel, visionSrcText) = visionSrc
                        val (webLabel, webSrcText) = webSrc
                        val (reasoningLabel, reasoningSrcText) = reasoningSrc
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Capabilities", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                Row {
                                    Text(visionLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(visionSrcText, fontSize = 12.sp, color = AppColors.TextTertiary)
                                }
                                Row {
                                    Text(webLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(webSrcText, fontSize = 12.sp, color = AppColors.TextTertiary)
                                }
                                Row {
                                    Text(reasoningLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(reasoningSrcText, fontSize = 12.sp, color = AppColors.TextTertiary)
                                }
                                // PDF input — currently only Anthropic
                                // self-reports this on its /v1/models, so
                                // the source label is fixed when present.
                                cfg.modelCapabilities[modelName]?.supportsPdfInput?.let { pdf ->
                                    Row {
                                        Text("PDF input 📄: ${if (pdf) "yes" else "no"}",
                                            fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                        Text("Provider self-report", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                }
                                cfg.modelCapabilities[modelName]?.deprecationDate?.let { date ->
                                    val replacement = cfg.modelCapabilities[modelName]?.deprecationReplacement
                                    val msg = if (replacement.isNullOrBlank()) "⚠ Deprecated $date"
                                        else "⚠ Deprecated $date → use $replacement"
                                    Row {
                                        Text(msg, fontSize = 13.sp, color = AppColors.Orange, modifier = Modifier.weight(1f))
                                        Text("Provider", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                // Add / edit manual override — opens the same form the
                                // Manual model types CRUD uses, pre-filled with this
                                // (provider, model). If an override already exists
                                // for this pair the form opens in edit mode.
                                Button(
                                    onClick = { onNavigateToAddManualOverride(provider, modelName) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                                ) { Text("Add manual override", fontSize = 14.sp, maxLines = 1, softWrap = false) }
                            }
                        }
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
                                or.knowledge_cutoff?.let { ModelInfoRow("Knowledge Cutoff", it) }
                                or.expiration_date?.let { ModelInfoRow("⚠ Expires", it) }
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
 *  in both axes so long lines aren't cut off. Field names render in a
 *  different color than their values via [colorizeJson]. */
@Composable
private fun ModelRawInfoScreen(
    title: String,
    body: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val annotated = remember(body) { colorizeJson(body) }
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
                    text = annotated,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Tokenize pretty-printed JSON and emit colored spans:
 *   keys        → blue
 *   strings     → green
 *   numbers     → orange
 *   true/false  → purple
 *   null        → dim grey
 *   punctuation → white (default)
 *
 * A string is treated as a key when the next non-whitespace char after
 * its closing quote is ':'. Walks char-by-char so it handles escaped
 * quotes inside strings correctly. Falls back to plain white for
 * non-JSON inputs (e.g. the "(no LiteLLM data)" placeholder).
 */
private fun colorizeJson(json: String): androidx.compose.ui.text.AnnotatedString {
    val keyStyle = SpanStyle(color = AppColors.Blue)
    val stringStyle = SpanStyle(color = AppColors.Green)
    val numStyle = SpanStyle(color = AppColors.Orange)
    val boolStyle = SpanStyle(color = AppColors.Purple)
    val nullStyle = SpanStyle(color = AppColors.TextTertiary)
    val punctStyle = SpanStyle(color = Color.White)
    return buildAnnotatedString {
        var i = 0
        val n = json.length
        while (i < n) {
            val c = json[i]
            when {
                c == '"' -> {
                    val start = i
                    i++
                    while (i < n) {
                        when (json[i]) {
                            '\\' -> i = (i + 2).coerceAtMost(n)
                            '"' -> { i++; break }
                            else -> i++
                        }
                    }
                    var j = i
                    while (j < n && json[j].isWhitespace()) j++
                    val isKey = j < n && json[j] == ':'
                    withStyle(if (isKey) keyStyle else stringStyle) { append(json.substring(start, i)) }
                }
                c.isDigit() || (c == '-' && i + 1 < n && json[i + 1].isDigit()) -> {
                    val start = i
                    if (c == '-') i++
                    while (i < n && (json[i].isDigit() || json[i] == '.' || json[i] == 'e' || json[i] == 'E' || json[i] == '+' || json[i] == '-')) i++
                    withStyle(numStyle) { append(json.substring(start, i)) }
                }
                c == 't' && i + 4 <= n && json.regionMatches(i, "true", 0, 4) -> {
                    withStyle(boolStyle) { append("true") }; i += 4
                }
                c == 'f' && i + 5 <= n && json.regionMatches(i, "false", 0, 5) -> {
                    withStyle(boolStyle) { append("false") }; i += 5
                }
                c == 'n' && i + 4 <= n && json.regionMatches(i, "null", 0, 4) -> {
                    withStyle(nullStyle) { append("null") }; i += 4
                }
                else -> {
                    withStyle(punctStyle) { append(c) }; i++
                }
            }
        }
    }
}
