package com.ai.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AgentParameters
import com.ai.data.AnalysisRepository
import com.ai.data.ApiFactory
import com.ai.data.AppService
import com.ai.data.ChatHistoryManager
import com.ai.data.ChatSession
import com.ai.data.HuggingFaceCache
import com.ai.data.HuggingFaceModelInfo
import com.ai.data.OpenRouterModelInfo
import com.ai.data.PricingCache
import com.ai.data.PromptCache
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.data.createAppGson
import com.ai.data.withTraceCategory
import com.ai.model.Agent
import com.ai.model.Settings
import com.ai.ui.report.ContentWithThinkSections
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToAgentView
import com.ai.ui.shared.LocalNavigateToFlockView
import com.ai.ui.shared.LocalNavigateToSwarmView
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.formatCompactNumber
import com.ai.ui.shared.shortModelName
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Read-only "View" sibling of [ModelInfoScreen]. Matches the View
 * family aesthetic ([ViewScreenTitleBar], purple-bordered gradient
 * hero card, 56sp model glyph, blue section headers on
 * [AppColors.CardBackground] cards).
 *
 * Differences vs the management ModelInfoScreen:
 *  - No Actions card (Start Chat / Create Agent / Test).
 *  - No "Model in AI config" card (block / inaccessible / cooldown /
 *    type-override list links).
 *  - No "Add manual cost override" / "Add manual override" buttons.
 *  - No "Show all" button on the Sources card.
 *  - Tapping a source button opens [ParsedSourceOverlay] (a
 *    structured key-value tree) instead of the raw JSON dump.
 *  - Workers card is moved to the bottom of the page.
 *  - AI Introduction stays — same on-demand fetch + cache as Manage.
 *  - Workers rows navigate to AgentView / FlockView / SwarmView
 *    (read-only) via [LocalNavigateToAgentView] / FlockView /
 *    SwarmView CompositionLocals.
 */
@Composable
fun ModelInfoViewScreen(
    provider: AppService,
    modelName: String,
    openRouterApiKey: String,
    huggingFaceApiKey: String,
    aiSettings: Settings,
    repository: AnalysisRepository,
    onOpenReport: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Source-overlay state. Null = nothing open. The overlay renders
    // the parsed JsonElement view (no raw JSON dump) and a back
    // press unwraps it.
    var sourceOverlay by remember { mutableStateOf<SourceOverlayState?>(null) }

    // OpenRouter + HuggingFace lookups — same shape as the Manage
    // screen's produceState blocks. Each card consuming this data
    // renders as soon as the side it needs arrives.
    val orInfo by produceState<OpenRouterModelInfo?>(initialValue = null, provider, modelName) {
        if (openRouterApiKey.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val models = ModelInfoLookupCache.getOpenRouterModels(openRouterApiKey)
                fun norm(s: String) = s.replace('.', '-').lowercase()
                val targetNorm = norm(modelName)
                val orName = provider.openRouterName
                val prefixedTargetNorm = if (orName != null) norm("$orName/$modelName") else null
                models.firstOrNull { norm(it.id) == prefixedTargetNorm }
                    ?: models.firstOrNull { norm(it.id).endsWith("/$targetNorm") }
                    ?: models.firstOrNull { norm(it.id) == targetNorm }
            } catch (_: Exception) { null }
        }
    }
    val hfInfo by produceState<HuggingFaceModelInfo?>(initialValue = null, provider, modelName) {
        value = withContext(Dispatchers.IO) {
            val cached = HuggingFaceCache.get(context, provider.id, modelName)
            if (cached != null) return@withContext cached.info
            if (huggingFaceApiKey.isBlank()) {
                HuggingFaceCache.put(context, provider.id, modelName, null)
                return@withContext null
            }
            val baseCandidate = if ("/" in modelName) modelName
                else (provider.openRouterName ?: provider.id)
                    .takeIf { it.isNotBlank() }?.let { "$it/$modelName" }
            if (baseCandidate == null) {
                HuggingFaceCache.put(context, provider.id, modelName, null)
                return@withContext null
            }
            val variants = sequenceOf(baseCandidate, baseCandidate.replace('-', '.'), baseCandidate.replace('.', '-')).distinct()
            var found: HuggingFaceModelInfo? = null
            for (cand in variants) {
                try {
                    val resp = ApiFactory.createHuggingFaceApi().getModelInfo(cand, "Bearer $huggingFaceApiKey")
                    if (resp.isSuccessful) { found = resp.body(); break }
                } catch (_: Exception) {}
            }
            HuggingFaceCache.put(context, provider.id, modelName, found)
            found
        }
    }
    val usageEntry by produceState<com.ai.model.UsageStats?>(initialValue = null, provider, modelName) {
        value = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            SettingsPreferences(prefs, context.filesDir).loadUsageStats()["${provider.id}::$modelName"]
        }
    }
    val usageCost by produceState<Double?>(initialValue = null, usageEntry) {
        val ue = usageEntry ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val pricing = PricingCache.getPricing(context, ue.provider, ue.model)
            ue.inputTokens * pricing.promptPrice + ue.outputTokens * pricing.completionPrice
        }
    }
    val recentUsages by produceState<List<ViewUsageEntry>>(initialValue = emptyList(), provider, modelName) {
        value = withContext(Dispatchers.IO) {
            computeUsages(context, provider, modelName, onOpenReport)
        }
    }

    // AI introduction — on-demand fetch + PromptCache lookup. Same
    // shape as the Manage screen so the cached body shows on screen
    // open and a tap forces a fresh call.
    var aiIntro by remember(provider, modelName) { mutableStateOf<String?>(null) }
    var aiLoading by remember(provider, modelName) { mutableStateOf(false) }
    val introTemplate = remember(aiSettings) {
        aiSettings.getInternalPromptByName("Model info")?.text.orEmpty()
    }
    val pageApiKey = aiSettings.getApiKey(provider)
    val introResolvedPrompt = remember(introTemplate, provider, modelName) {
        introTemplate
            .replace("@MODEL@", modelName)
            .replace("@PROVIDER@", provider.id)
            .replace("@AGENT@", "${provider.id} / $modelName")
    }
    val introCacheKey = remember(introResolvedPrompt, provider, modelName) {
        PromptCache.keyFor(introResolvedPrompt, "${provider.id}:$modelName")
    }
    val canRequestIntro = pageApiKey.isNotBlank()
    LaunchedEffect(introCacheKey) {
        PromptCache.get(introCacheKey)?.let { aiIntro = it }
    }
    val requestIntroduction: () -> Unit = req@{
        if (!canRequestIntro || aiLoading) return@req
        val selfAgent = Agent(
            id = "model_info_view_self:${provider.id}:$modelName",
            name = "${provider.id} / $modelName",
            provider = provider, model = modelName,
            apiKey = pageApiKey
        )
        scope.launch {
            aiLoading = true
            try {
                withTraceCategory("Model self-intro") {
                    val response = withContext(Dispatchers.IO) {
                        repository.analyzePlayerWithAgent(selfAgent, introResolvedPrompt, AgentParameters())
                    }
                    if (response.isSuccess) {
                        aiIntro = response.analysis
                        response.analysis?.let { PromptCache.put(introCacheKey, it) }
                    }
                }
            } catch (_: Exception) {} finally {
                aiLoading = false
            }
        }
    }

    // When a source overlay is open we mount it as a full-screen
    // replacement (same convention every other View overlay uses).
    sourceOverlay?.let { overlay ->
        ParsedSourceOverlay(
            sourceName = overlay.sourceName,
            modelName = modelName,
            rawJson = overlay.rawJson,
            calledUrl = overlay.calledUrl,
            onBack = { sourceOverlay = null }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = "${provider.id} / ${shortModelName(modelName)}",
            screenTitle = "Model Info",
            subject = null,
            helpTopic = "model_info_view",
            onBack = onBack
        )

        // Pre-compute every per-source raw JSON string + the typed-
        // source data so the Sources card can render the seven
        // buttons immediately.
        val gson = remember { createAppGson(prettyPrint = true) }
        val liteLLMRaw = remember(provider, modelName) {
            PricingCache.getLiteLLMRawEntry(context, provider, modelName)
        }
        val modelsDevRaw = remember(provider, modelName) {
            PricingCache.getModelsDevRawEntry(context, provider, modelName)
        }
        val heliconeRaw = remember(provider, modelName) {
            PricingCache.getHeliconeRawEntry(context, provider, modelName)
        }
        val llmPricesRaw = remember(provider, modelName) {
            PricingCache.getLLMPricesRawEntry(context, provider, modelName)
        }
        val aaRaw = remember(provider, modelName) {
            PricingCache.getArtificialAnalysisRawEntry(context, provider, modelName)
        }

        // Workers — agents / flocks / swarms matching this model.
        val matchedAgents = remember(aiSettings.agents, provider, modelName) {
            aiSettings.agents.filter { it.provider == provider && it.model == modelName }
        }
        val matchedAgentIds = remember(matchedAgents) { matchedAgents.map { it.id }.toSet() }
        val matchedFlocks = remember(aiSettings.flocks, matchedAgentIds) {
            aiSettings.flocks.filter { f -> f.agentIds.any { it in matchedAgentIds } }
        }
        val matchedSwarms = remember(aiSettings.swarms, provider, modelName) {
            aiSettings.swarms.filter { s ->
                s.members.any { it.provider == provider && it.model == modelName }
            }
        }
        val hasWorkers = matchedAgents.isNotEmpty() || matchedFlocks.isNotEmpty() || matchedSwarms.isNotEmpty()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1) Hero card — purple gradient + 56 sp glyph + name.
            item { HeroCard(provider = provider, modelName = modelName) }

            // 2) Capabilities — read-only chips.
            item { CapabilitiesCard(aiSettings = aiSettings, provider = provider, modelName = modelName) }

            // 3) Sources buttons — tap opens ParsedSourceOverlay.
            item {
                SourcesCard(
                    hfRaw = hfInfo?.let { gson.toJson(it) },
                    orRaw = orInfo?.let { gson.toJson(it) },
                    liteLLMRaw = liteLLMRaw,
                    modelsDevRaw = modelsDevRaw,
                    heliconeRaw = heliconeRaw,
                    llmPricesRaw = llmPricesRaw,
                    aaRaw = aaRaw,
                    onOpen = { name, body, url ->
                        sourceOverlay = SourceOverlayState(name, body, url)
                    }
                )
            }

            // 4) Costs — per-tier rows; NO Add manual cost override.
            item { CostsCard(provider = provider, modelName = modelName) }

            // 5) Provider row — read-only.
            item { ProviderCard(provider = provider) }

            // 6) Description (OpenRouter, conditional).
            orInfo?.description?.let { desc ->
                item { SectionCard(title = "Description") { Text(desc, fontSize = 13.sp, color = AppColors.TextSecondary) } }
            }

            // 7) Technical specs.
            orInfo?.let { or ->
                item {
                    SectionCard(title = "Technical Specifications") {
                        or.context_length?.let { KeyValueRow("Context Length", formatCompactNumber(it.toLong())) }
                        or.top_provider?.max_completion_tokens?.let { KeyValueRow("Max Completion", formatCompactNumber(it.toLong())) }
                        or.architecture?.modality?.let { KeyValueRow("Modality", it) }
                        or.architecture?.tokenizer?.let { KeyValueRow("Tokenizer", it) }
                        or.architecture?.instruct_type?.let { KeyValueRow("Instruct Type", it) }
                        or.top_provider?.is_moderated?.let { KeyValueRow("Moderated", if (it) "Yes" else "No") }
                        or.knowledge_cutoff?.let { KeyValueRow("Knowledge Cutoff", it) }
                        or.expiration_date?.let { KeyValueRow("⚠ Expires", it) }
                    }
                }
            }

            // 8) HuggingFace.
            hfInfo?.let { hf ->
                item {
                    SectionCard(title = "HuggingFace") {
                        hf.author?.let { KeyValueRow("Author", it) }
                        hf.pipeline_tag?.let { KeyValueRow("Pipeline", it) }
                        hf.library_name?.let { KeyValueRow("Library", it) }
                        hf.downloads?.let { KeyValueRow("Downloads", formatCompactNumber(it)) }
                        hf.likes?.let { KeyValueRow("Likes", formatCompactNumber(it.toLong())) }
                        hf.cardData?.license?.let { KeyValueRow("License", it) }
                        hf.cardData?.base_model?.let { KeyValueRow("Base Model", it) }
                        hf.cardData?.language?.takeIf { it.isNotEmpty() }?.let { KeyValueRow("Languages", it.joinToString(", ")) }
                    }
                }
            }

            // 9) Tags.
            hfInfo?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                item {
                    SectionCard(title = "Tags") {
                        Text(tags.joinToString(", "), fontSize = 12.sp, color = AppColors.TextTertiary)
                    }
                }
            }

            // 10) AI Introduction — on-demand fetch + cache.
            if (canRequestIntro || aiIntro != null) {
                item {
                    SectionCard(title = "AI Introduction") {
                        when {
                            aiLoading -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Generating…", fontSize = 13.sp, color = AppColors.TextTertiary)
                            }
                            aiIntro != null -> {
                                ContentWithThinkSections(aiIntro ?: "")
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Ask again",
                                    fontSize = 12.sp,
                                    color = AppColors.Blue,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { requestIntroduction() }
                                )
                            }
                            else -> Button(
                                onClick = requestIntroduction,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                            ) { Text("Ask the model to introduce itself", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                        }
                    }
                }
            }

            // 11) AI Usage — cumulative counter.
            item {
                SectionCard(title = "AI Usage") {
                    val ue = usageEntry
                    if (ue == null) {
                        Text("No usage recorded yet for this model.", fontSize = 12.sp, color = AppColors.TextTertiary)
                    } else {
                        Text(
                            "${ue.callCount} calls · ${formatCompactNumber(ue.inputTokens)} in / ${formatCompactNumber(ue.outputTokens)} out",
                            fontSize = 13.sp, color = Color.White
                        )
                        usageCost?.let {
                            Text(
                                "Cost: " + if (it < 0.01 && it > 0)
                                    String.format(Locale.US, "$%.6f", it)
                                else String.format(Locale.US, "$%.4f", it),
                                fontSize = 13.sp, color = AppColors.Green
                            )
                        }
                    }
                }
            }

            // 12) Last usage — recent chats / reports / secondaries.
            if (recentUsages.isNotEmpty()) {
                item { LastUsageCard(entries = recentUsages) }
            }

            // 13) Workers — MOVED to the bottom per the user's spec.
            if (hasWorkers) {
                item { WorkersCard(matchedAgents, matchedFlocks, matchedSwarms) }
            }
        }
    }
}

// ───── Card helpers ─────

@Composable
private fun HeroCard(provider: AppService, modelName: String) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        AppColors.Purple.copy(alpha = 0.32f),
                        AppColors.Indigo.copy(alpha = 0.08f)
                    )
                )
            )
            .border(1.dp, AppColors.Purple.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = "🤖", fontSize = 56.sp)
            Text(
                text = shortModelName(modelName),
                fontSize = 22.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = provider.id,
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
        content()
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = AppColors.TextTertiary)
        Text(value, fontSize = 13.sp, color = Color.White)
    }
}

@Composable
private fun ProviderCard(provider: AppService) {
    SectionCard(title = "Provider") {
        Text(provider.id, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun CapabilitiesCard(aiSettings: Settings, provider: AppService, modelName: String) {
    val cfg = aiSettings.getProvider(provider)
    val visionEffective = aiSettings.isVisionCapable(provider, modelName)
    val webEffective = aiSettings.isWebSearchCapable(provider, modelName)
    val reasoningEffective = aiSettings.isReasoningCapable(provider, modelName)
    SectionCard(title = "Capabilities") {
        KeyValueRow("Vision 👁", if (visionEffective) "yes" else "no")
        KeyValueRow("Web search 🌐", if (webEffective) "yes" else "no")
        KeyValueRow("Thinking 🧠", if (reasoningEffective) "yes" else "no")
        cfg.modelCapabilities[modelName]?.supportsPdfInput?.let { pdf ->
            KeyValueRow("PDF input 📄", if (pdf) "yes" else "no")
        }
        cfg.modelCapabilities[modelName]?.deprecationDate?.let { date ->
            val replacement = cfg.modelCapabilities[modelName]?.deprecationReplacement
            val msg = if (replacement.isNullOrBlank()) "⚠ Deprecated $date" else "⚠ Deprecated $date → use $replacement"
            Text(msg, fontSize = 13.sp, color = AppColors.Orange)
        }
        cfg.modelCapabilities[modelName]?.defaultTemperature?.let {
            KeyValueRow("Default temperature", it.toString())
        }
        cfg.modelCapabilities[modelName]?.defaultStopSequences?.takeIf { it.isNotEmpty() }?.let {
            KeyValueRow("Default stops", it.joinToString(", "))
        }
    }
}

@Composable
private fun SourcesCard(
    hfRaw: String?,
    orRaw: String?,
    liteLLMRaw: String?,
    modelsDevRaw: String?,
    heliconeRaw: String?,
    llmPricesRaw: String?,
    aaRaw: String?,
    onOpen: (sourceName: String, body: String, calledUrl: String?) -> Unit
) {
    SectionCard(title = "Sources") {
        // Two rows of four / three buttons — same layout as the
        // management screen, minus the "Show all" button below.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceButton("HuggingFace", hfRaw, modifier = Modifier.weight(1f)) {
                onOpen("HuggingFace", hfRaw ?: "{}", "https://huggingface.co/api/models")
            }
            SourceButton("OpenRouter", orRaw, modifier = Modifier.weight(1f)) {
                onOpen("OpenRouter", orRaw ?: "{}", "https://openrouter.ai/api/v1/models")
            }
            SourceButton("LiteLLM", liteLLMRaw, modifier = Modifier.weight(1f)) {
                onOpen("LiteLLM", liteLLMRaw ?: "{}", "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json")
            }
            SourceButton("models.dev", modelsDevRaw, modifier = Modifier.weight(1f)) {
                onOpen("models.dev", modelsDevRaw ?: "{}", "https://models.dev/api.json")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceButton("Helicone", heliconeRaw, modifier = Modifier.weight(1f)) {
                onOpen("Helicone", heliconeRaw ?: "{}", "https://www.helicone.ai/api/llm-costs")
            }
            SourceButton("llm-prices", llmPricesRaw, modifier = Modifier.weight(1f)) {
                onOpen("llm-prices", llmPricesRaw ?: "{}", "https://raw.githubusercontent.com/simonw/llm-prices/main/data/")
            }
            SourceButton("AA", aaRaw, modifier = Modifier.weight(1f)) {
                onOpen("Artificial Analysis", aaRaw ?: "{}", "https://artificialanalysis.ai/api/v2/data/llms/models")
            }
        }
    }
}

@Composable
private fun SourceButton(label: String, raw: String?, modifier: Modifier, onClick: () -> Unit) {
    val has = raw != null
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = if (has) AppColors.Green else AppColors.Red),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
}

@Composable
private fun CostsCard(provider: AppService, modelName: String) {
    val context = LocalContext.current
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
    SectionCard(title = "Costs (per million tokens)") {
        if (rows.isEmpty()) {
            Text(
                "No catalog entry — lookup falls back to the built-in default.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
        } else {
            rows.forEach { (label, p) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Text(
                        "${"%.4f".format(Locale.US, p.promptPrice * 1_000_000)} / ${"%.4f".format(Locale.US, p.completionPrice * 1_000_000)}",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.Green
                    )
                }
                if (p.perQueryPrice > 0.0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$label · per 1k searches",
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                        Text(
                            "${"%.2f".format(Locale.US, p.perQueryPrice * 1000)}",
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.Green
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkersCard(
    matchedAgents: List<com.ai.model.Agent>,
    matchedFlocks: List<com.ai.model.Flock>,
    matchedSwarms: List<com.ai.model.Swarm>
) {
    val navAgent = LocalNavigateToAgentView.current
    val navFlock = LocalNavigateToFlockView.current
    val navSwarm = LocalNavigateToSwarmView.current
    SectionCard(title = "Workers") {
        matchedAgents.forEach { a -> WorkerRow("Agent", a.name) { navAgent(a.id) } }
        matchedFlocks.forEach { f -> WorkerRow("Flock", f.name) { navFlock(f.id) } }
        matchedSwarms.forEach { s -> WorkerRow("Swarm", s.name) { navSwarm(s.id) } }
    }
}

@Composable
private fun WorkerRow(label: String, name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label:", fontSize = 12.sp, color = AppColors.Blue,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(80.dp)
        )
        Text(
            name, fontSize = 13.sp, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("›", fontSize = 14.sp, color = AppColors.TextTertiary)
    }
}

@Composable
private fun LastUsageCard(entries: List<ViewUsageEntry>) {
    SectionCard(title = "Last usage") {
        val dateFmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
        entries.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { e.onOpen() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    e.typeLabel, fontSize = 12.sp, color = AppColors.Orange,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    e.title, fontSize = 13.sp, color = Color.White,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    dateFmt.format(java.util.Date(e.timestamp)),
                    fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ───── Parsed source overlay ─────

private data class SourceOverlayState(
    val sourceName: String,
    val rawJson: String,
    val calledUrl: String?
)

/**
 * Replacement for the management screen's [ModelRawInfoScreen]. Parses
 * the raw JSON via Gson and renders it as a recursive key-value tree
 * (instead of dumping the pretty-printed source text). Same View
 * scaffold + card style as the rest of the View family.
 */
@Composable
private fun ParsedSourceOverlay(
    sourceName: String,
    modelName: String,
    rawJson: String,
    calledUrl: String?,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val element = remember(rawJson) {
        try { JsonParser.parseString(rawJson) } catch (_: Exception) { null }
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = modelName,
            screenTitle = "Source",
            subject = sourceName,
            helpTopic = "model_info_view",
            onBack = onBack
        )
        if (calledUrl != null) {
            Text(
                calledUrl,
                fontSize = 11.sp, color = AppColors.TextTertiary,
                fontFamily = FontFamily.Monospace,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
            )
        }
        Box(
            modifier = Modifier.fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.CardBackground)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (element == null || element.isJsonNull) {
                Text("(no data)", fontSize = 13.sp, color = AppColors.TextTertiary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ParsedJsonValue(element, depth = 0)
                }
            }
        }
    }
}

@Composable
private fun ParsedJsonValue(element: JsonElement, depth: Int) {
    when {
        element.isJsonNull -> Text("null", fontSize = 13.sp, color = AppColors.TextTertiary)
        element is JsonPrimitive -> Text(
            text = primitiveDisplay(element),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = primitiveColor(element)
        )
        element is JsonArray -> {
            if (element.size() == 0) {
                Text("[]", fontSize = 13.sp, color = AppColors.TextTertiary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    element.forEachIndexed { idx, child ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = (depth * 12).dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "[$idx]:",
                                fontSize = 12.sp,
                                color = AppColors.Indigo,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                ParsedJsonValue(child, depth + 1)
                            }
                        }
                    }
                }
            }
        }
        element is JsonObject -> {
            if (element.size() == 0) {
                Text("{}", fontSize = 13.sp, color = AppColors.TextTertiary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((key, child) in element.entrySet()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = (depth * 12).dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "$key:",
                                fontSize = 12.sp,
                                color = AppColors.Blue,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                ParsedJsonValue(child, depth + 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun primitiveDisplay(p: JsonPrimitive): String = when {
    p.isBoolean -> p.asBoolean.toString()
    p.isNumber -> p.asNumber.toString()
    p.isString -> p.asString
    else -> p.toString()
}

private fun primitiveColor(p: JsonPrimitive): Color = when {
    p.isBoolean -> AppColors.Orange
    p.isNumber -> AppColors.Yellow
    p.isString -> AppColors.Green
    else -> Color.White
}

// ───── Local types + helpers (mirrors the Manage screen's shape) ─────

private data class ViewUsageEntry(
    val timestamp: Long,
    val typeLabel: String,
    val title: String,
    val onOpen: () -> Unit
)

private fun computeUsages(
    context: android.content.Context,
    provider: AppService,
    model: String,
    onOpenReport: (String) -> Unit
): List<ViewUsageEntry> {
    val out = mutableListOf<ViewUsageEntry>()
    fun chatTitle(s: ChatSession): String {
        val firstLine = s.messages.firstOrNull { it.role == "user" }?.content?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }?.trim() ?: return "Chat session"
        return if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
    }
    ChatHistoryManager.init(context)
    ChatHistoryManager.getAllSessions().forEach { s ->
        if (s.provider.id == provider.id && s.model == model) {
            out += ViewUsageEntry(s.updatedAt, "Chat", chatTitle(s)) {}
        }
    }
    val reports = ReportStorage.getAllReports(context).sortedByDescending { it.timestamp }
    val cap = 30
    for (report in reports) {
        if (out.size >= cap) break
        report.agents.forEach { agent ->
            if (agent.provider == provider.id && agent.model == model) {
                out += ViewUsageEntry(
                    report.timestamp, "Report",
                    report.title.ifBlank { report.prompt.take(80) }
                ) { onOpenReport(report.id) }
            }
        }
        SecondaryResultStorage.listForReport(context, report.id).forEach { sec ->
            if (sec.providerId == provider.id && sec.model == model) {
                val typeLabel = when (sec.kind) {
                    SecondaryKind.RERANK -> "Rerank"
                    SecondaryKind.META -> sec.metaPromptName?.takeIf { it.isNotBlank() } ?: "Meta"
                    SecondaryKind.MODERATION -> "Moderate"
                    SecondaryKind.TRANSLATE -> "Translate"
                }
                out += ViewUsageEntry(
                    sec.timestamp, typeLabel,
                    "from ${report.title.ifBlank { report.prompt.take(60) }}"
                ) { onOpenReport(report.id) }
            }
        }
    }
    return out.sortedByDescending { it.timestamp }.take(10)
}

/** Tiny per-process cache for the OpenRouter models list — same
 *  shape as the Manage screen's [ModelInfoCache] but kept private
 *  here so this file doesn't leak through to the Manage screen. */
private object ModelInfoLookupCache {
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
