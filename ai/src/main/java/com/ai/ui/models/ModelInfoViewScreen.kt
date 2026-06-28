package com.ai.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
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
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.data.preferences.SettingsPreferences
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
    settingsPrefs: SettingsPreferences,
    onOpenReport: (String) -> Unit,
    /** Per-row Last-Usage tap → that report's View Reports screen,
     *  pre-scrolled to the agent matching this provider/model. Wired
     *  from AppNavHost via the AI_REPORTS route's new
     *  `initialReportsAgentId` query-param. Default delegates to
     *  [onOpenReport] for back-compat. */
    onOpenReportAtAgent: (reportId: String, agentId: String) -> Unit = { rid, _ -> onOpenReport(rid) },
    /** HeroCard provider-name tap → the View Provider screen.
     *  Wired from AppNavHost via NavRoutes.aiProviderView. Default
     *  no-op leaves the name un-clickable on legacy call sites. */
    onOpenProvider: ((AppService) -> Unit)? = null,
    onOpenManage: (() -> Unit)? = null,
    /** Open the Trace screen filtered to a category (+ optional model).
     *  No-op hides the per-source 🐞. */
    onNavigateToTraceFiltered: (category: String, model: String?) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Per-source 🐞 gate (one cached off-thread load). The HuggingFace
    // lookup is per-model — its trace is model-tagged, so the 🐞 shows only
    // when THIS model was looked up and filters to it. The OpenRouter fetch
    // is a bulk all-models call (no per-model trace), so its 🐞 is
    // category-only.
    val infoFlags by androidx.compose.runtime.produceState(false to false, modelName) {
        value = withContext(Dispatchers.IO) {
            val files = com.ai.data.ApiTracer.getTraceFiles()
            val hf = files.any { it.category == "info/huggingface" && it.model == modelName }
            val or = files.any { it.category == "info/provider" }
            hf to or
        }
    }

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
                val models = OpenRouterModelInfoCache.getOpenRouterModels(openRouterApiKey)
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
            if (!aiSettings.isInfoProviderEnabled(com.ai.data.InfoProvider.HUGGINGFACE.id)) return@withContext null
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
                    val resp = com.ai.data.withTracerTags(category = "info/huggingface", model = modelName) {
                        ApiFactory.createHuggingFaceApi().getModelInfo(cand, "Bearer $huggingFaceApiKey")
                    }
                    if (resp.isSuccessful) { found = resp.body(); break }
                } catch (_: Exception) {}
            }
            HuggingFaceCache.put(context, provider.id, modelName, found)
            found
        }
    }
    // Usage stats live under the 3-part key "${provider.id}::$model::$kind"
    // (kind defaults to "report"). An older single-key lookup
    // "${provider.id}::$modelName" returned nothing, leaving this
    // card permanently empty. Aggregate across every kind for the
    // (provider, model) pair so the card reflects total spend
    // including rerank / summarize / meta / moderation / translate
    // calls — what users intuitively expect from "AI Usage".
    val usageEntry by produceState<com.ai.model.UsageStats?>(initialValue = null, provider, modelName) {
        value = withContext(Dispatchers.IO) {
            val all = settingsPrefs.loadUsageStats()
            val prefix = "${provider.id}::$modelName::"
            val matching = all.filterKeys { it.startsWith(prefix) }.values
            if (matching.isEmpty()) null
            else com.ai.model.UsageStats(
                provider = provider, model = modelName,
                callCount = matching.sumOf { it.callCount },
                inputTokens = matching.sumOf { it.inputTokens },
                outputTokens = matching.sumOf { it.outputTokens },
                kind = "all",
                searchUnits = matching.sumOf { it.searchUnits }
            )
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
            computeUsages(context, provider, modelName, onOpenReportAtAgent)
        }
    }

    // AI introduction — on-demand card. Uses the new
    // `model_intro_view` internal prompt (separate from the legacy
    // `model_info` one the Manage screen reads). On mount we
    // SHOW the cached body immediately via [PromptCache.getRaw]
    // (no spinner). Fresh calls require an explicit tap because the
    // self-introduction is a paid model request.
    var aiIntro by remember(provider, modelName) { mutableStateOf<String?>(null) }
    var aiLoading by remember(provider, modelName) { mutableStateOf(false) }
    val introTemplate = remember(aiSettings) {
        aiSettings.getInternalPromptByName("model-intro")?.text.orEmpty()
    }
    val pageApiKey = aiSettings.getApiKey(provider)
    val introResolvedPrompt = remember(introTemplate, provider, modelName) {
        introTemplate
            .replace("@MODEL@", modelName)
            .replace("@PROVIDER@", provider.id)
            .replace("@AGENT@", "${provider.id} / $modelName")
    }
    val introCacheKey = remember(introResolvedPrompt, provider, modelName) {
        PromptCache.keyFor(introResolvedPrompt, "${provider.id}:$modelName", variant = "params=default|systemPrompt=")
    }
    val canRequestIntro = pageApiKey.isNotBlank() && introTemplate.isNotBlank()
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
    // Load the cached body only. getRaw avoids PromptCache's destructive 48h
    // TTL so an older cached intro stays visible until the user explicitly
    // asks again.
    LaunchedEffect(introCacheKey) {
        val raw = withContext(Dispatchers.IO) { PromptCache.getRaw(introCacheKey) }
        if (raw != null) aiIntro = raw.response
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
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // Title bar shows the AI Report Title when this screen is
        // opened from a report context (the
        // [com.ai.ui.shared.LocalReportTitle] CompositionLocal is
        // provided by ReportScreen). When opened from a non-report
        // route (settings, model browse, …) the local is null and
        // the title bar stays blank — provider / model name still
        // shows in the HeroCard below.
        // Prefer the AI Report Title when opened from a report; otherwise
        // (settings / model browse) fall back to the model name so the
        // orange line is never blank.
        val reportTitleFromContext = com.ai.ui.shared.LocalReportTitle.current
            ?.takeIf { it.isNotBlank() } ?: com.ai.ui.shared.shortModelName(modelName)
        ViewScreenTitleBar(
            reportTitle = reportTitleFromContext,
            screenTitle = "Model Info",
            subject = null,
            helpTopic = "model_info_view",
            onOpenManage = onOpenManage,
            onBack = onBack
        )

        // Pre-compute every per-source raw JSON string + the typed-
        // source data off the main thread so the Sources card can
        // render without doing disk-backed catalog reads in composition.
        val gson = remember { createAppGson(prettyPrint = true) }
        val liteLLMRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getLiteLLMRawEntry(context, provider, modelName)
            }
        }
        val modelsDevRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getModelsDevRawEntry(context, provider, modelName)
            }
        }
        val heliconeRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getHeliconeRawEntry(context, provider, modelName)
            }
        }
        val llmPricesRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getLLMPricesRawEntry(context, provider, modelName)
            }
        }
        val aaRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getArtificialAnalysisRawEntry(context, provider, modelName)
            }
        }
        val requestyRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getRequestyRawEntry(context, provider, modelName)
            }
        }
        val llmStatsRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getLlmStatsRawEntry(context, provider, modelName)
            }
        }
        val genaiPricesRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getGenaiPricesRawEntry(context, provider, modelName)
            }
        }
        val trueFoundryRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getTrueFoundryRawEntry(context, provider, modelName)
            }
        }
        val cloudPriceRaw by produceState<String?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getCloudPriceRawEntry(context, provider, modelName)
            }
        }
        val tierBreakdown by produceState<PricingCache.TierBreakdown?>(initialValue = null, provider, modelName) {
            value = withContext(Dispatchers.IO) {
                PricingCache.getTierBreakdown(context, provider, modelName)
            }
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
            //    The provider name inside is clickable → View Provider
            //    screen (see HeroCard).
            item { HeroCard(provider = provider, modelName = modelName, onOpenProvider = onOpenProvider) }

            // 2) Capabilities — read-only chips.
            item { CapabilitiesCard(aiSettings = aiSettings, provider = provider, modelName = modelName) }

            // 3) Sources — vertical list of tappable rows.
            item {
                SourcesCard(
                    hfRaw = hfInfo?.let { gson.toJson(it) },
                    orRaw = orInfo?.let { gson.toJson(it) },
                    liteLLMRaw = liteLLMRaw,
                    modelsDevRaw = modelsDevRaw,
                    heliconeRaw = heliconeRaw,
                    llmPricesRaw = llmPricesRaw,
                    aaRaw = aaRaw,
                    requestyRaw = requestyRaw,
                    llmStatsRaw = llmStatsRaw,
                    genaiPricesRaw = genaiPricesRaw,
                    trueFoundryRaw = trueFoundryRaw,
                    cloudPriceRaw = cloudPriceRaw,
                    enabled = { aiSettings.isInfoProviderEnabled(it) },
                    hfTrace = if (infoFlags.first) ({ onNavigateToTraceFiltered("info/huggingface", modelName) }) else null,
                    orTrace = if (infoFlags.second) ({ onNavigateToTraceFiltered("info/provider", null) }) else null,
                    onOpen = { name, body, url ->
                        sourceOverlay = SourceOverlayState(name, body, url)
                    }
                )
            }

            // 4) Costs — per-tier rows; NO Add manual cost override.
            item { CostsCard(tierBreakdown = tierBreakdown) }

            // (Provider card removed — provider name in HeroCard is
            // the clickable entry point to the View Provider screen.)

            // 5) Description (OpenRouter, conditional).
            orInfo?.description?.let { desc ->
                item { SectionCard(title = "Description") { Text(desc, fontSize = 13.sp, color = AppColors.TextSecondary) } }
            }

            // 6) Technical specs.
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
                        or.expiration_date?.let { KeyValueRow("${com.ai.data.MetadataIconsHolder.current.warningPlain} Expires", it) }
                    }
                }
            }

            // 7) HuggingFace.
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

            // 8) Tags.
            hfInfo?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                item {
                    SectionCard(title = "Tags") {
                        Text(tags.joinToString(", "), fontSize = 12.sp, color = AppColors.TextTertiary)
                    }
                }
            }

            // 9) AI Usage — cumulative counter (now aggregating
            //    across every UsageStats kind for this (provider, model)).
            item {
                SectionCard(title = "Usage") {
                    val ue = usageEntry
                    if (ue == null) {
                        Text("No usage recorded yet for this model.", fontSize = 12.sp, color = AppColors.TextTertiary)
                    } else {
                        Text(
                            "${ue.callCount} calls · ${formatCompactNumber(ue.inputTokens)} in / ${formatCompactNumber(ue.outputTokens)} out",
                            fontSize = 13.sp, color = AppColors.TextPrimary
                        )
                        usageCost?.let {
                            Text(
                                "Cost: " + if (it < 0.01 && it > 0)
                                    String.format(Locale.US, "$%.6f", it)
                                else String.format(Locale.US, "$%.4f", it),
                                fontSize = 13.sp, color = AppColors.SuccessAccent
                            )
                        }
                    }
                }
            }

            // 10) Last usage — recent reports using this model as a
            //     main report agent. Per-row tap → View Reports for
            //     that report, pre-scrolled to the matching agent.
            if (recentUsages.isNotEmpty()) {
                item { LastUsageCard(entries = recentUsages) }
            }

            // 11) Workers — agents / flocks / swarms matching this model.
            if (hasWorkers) {
                item { WorkersCard(matchedAgents, matchedFlocks, matchedSwarms) }
            }

            // 12) AI Introduction — LAST card. Cached body shows
            //     immediately; fresh calls require an explicit tap.
            if (canRequestIntro || aiIntro != null) {
                item {
                    SectionCard(title = "Introduction") {
                        when {
                            aiLoading && aiIntro == null -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Generating…", fontSize = 13.sp, color = AppColors.TextTertiary)
                            }
                            aiIntro != null -> {
                                ContentWithThinkSections(aiIntro ?: "")
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (aiLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Refreshing…", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    } else {
                                        Text(
                                            text = "Ask again",
                                            fontSize = 12.sp,
                                            color = AppColors.InfoAccent,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.clickable { requestIntroduction() }
                                        )
                                    }
                                }
                            }
                            else -> Text(
                                text = "Ask the model to introduce itself",
                                fontSize = 12.sp,
                                color = AppColors.InfoAccent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { requestIntroduction() }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ───── Card helpers ─────

@Composable
private fun HeroCard(provider: AppService, modelName: String, onOpenProvider: ((AppService) -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        AppColors.PrimaryAccent.copy(alpha = 0.32f),
                        AppColors.SecondaryAccent.copy(alpha = 0.08f)
                    )
                )
            )
            .border(1.dp, AppColors.PrimaryAccent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = com.ai.data.MetadataIconsHolder.current.agent, fontSize = 56.sp)
            Text(
                text = shortModelName(modelName),
                fontSize = 22.sp,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // Provider name: blue + tappable → View Provider screen.
            // Trailing › hint signals navigability; falls back to a
            // plain grey label when no onOpenProvider callback was
            // wired (legacy callers).
            val providerModifier = if (onOpenProvider != null) {
                Modifier.clickable { onOpenProvider(provider) }
            } else Modifier
            Text(
                text = if (onOpenProvider != null) "${provider.id}  ›" else provider.id,
                fontSize = 13.sp,
                color = if (onOpenProvider != null) AppColors.InfoAccent else AppColors.TextSecondary,
                fontWeight = if (onOpenProvider != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = providerModifier
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
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
        content()
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(mi.iconizedText(label), fontSize = 13.sp, color = AppColors.TextTertiary)
        Text(mi.iconizedText(value), fontSize = 13.sp, color = AppColors.TextPrimary)
    }
}

@Composable
private fun CapabilitiesCard(aiSettings: Settings, provider: AppService, modelName: String) {
    val cfg = aiSettings.getProvider(provider)
    val visionEffective = aiSettings.isVisionCapable(provider, modelName)
    val webEffective = aiSettings.isWebSearchCapable(provider, modelName)
    val reasoningEffective = aiSettings.isReasoningCapable(provider, modelName)
    SectionCard(title = "Capabilities") {
        val mi = com.ai.ui.shared.LocalMetadataIcons.current
        KeyValueRow("Vision ${mi.view}", if (visionEffective) "yes" else "no")
        KeyValueRow("Web search ${mi.web}", if (webEffective) "yes" else "no")
        KeyValueRow("Thinking ${mi.reportModelIcon}", if (reasoningEffective) "yes" else "no")
        cfg.modelCapabilities[modelName]?.supportsPdfInput?.let { pdf ->
            KeyValueRow("PDF input ${mi.document}", if (pdf) "yes" else "no")
        }
        cfg.modelCapabilities[modelName]?.deprecationDate?.let { date ->
            val replacement = cfg.modelCapabilities[modelName]?.deprecationReplacement
            val msg = if (replacement.isNullOrBlank()) "${mi.warningPlain} Deprecated $date" else "${mi.warningPlain} Deprecated $date ${mi.arrowRight} use $replacement"
            Text(msg, fontSize = 13.sp, color = AppColors.WarningAccent)
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
    requestyRaw: String?,
    llmStatsRaw: String?,
    genaiPricesRaw: String?,
    trueFoundryRaw: String?,
    cloudPriceRaw: String?,
    /** Info providers switched off under AI Setup are hidden here. */
    enabled: (String) -> Boolean,
    hfTrace: (() -> Unit)? = null,
    orTrace: (() -> Unit)? = null,
    onOpen: (sourceName: String, body: String, calledUrl: String?) -> Unit
) {
    // Vertical list of clickable source rows. Each row carries an
    // icon + label + present/absent indicator. Tapping a present
    // source opens [ParsedSourceOverlay]; tapping an absent row is
    // a no-op (the row is faded so the user reads it as inactive).
    SectionCard(title = "Sources") {
        if (enabled("huggingface")) SourceRow(com.ai.data.MetadataIconsHolder.current.huggingface, "HuggingFace", hfRaw, onTrace = hfTrace) {
            onOpen("HuggingFace", hfRaw ?: "{}", "https://huggingface.co/api/models")
        }
        if (enabled("openrouter")) SourceRow(com.ai.data.MetadataIconsHolder.current.web, "OpenRouter", orRaw, onTrace = orTrace) {
            onOpen("OpenRouter", orRaw ?: "{}", "https://openrouter.ai/api/v1/models")
        }
        if (enabled("litellm")) SourceRow(com.ai.data.MetadataIconsHolder.current.bookmark, "LiteLLM", liteLLMRaw) {
            onOpen("LiteLLM", liteLLMRaw ?: "{}", "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json")
        }
        if (enabled("modelsdev")) SourceRow(com.ai.data.MetadataIconsHolder.current.packageBox, "models.dev", modelsDevRaw) {
            onOpen("models.dev", modelsDevRaw ?: "{}", "https://models.dev/api.json")
        }
        if (enabled("helicone")) SourceRow(com.ai.data.MetadataIconsHolder.current.hot, "Helicone", heliconeRaw) {
            onOpen("Helicone", heliconeRaw ?: "{}", "https://www.helicone.ai/api/llm-costs")
        }
        if (enabled("llmprices")) SourceRow(com.ai.data.MetadataIconsHolder.current.cost, "llm-prices", llmPricesRaw) {
            onOpen("llm-prices", llmPricesRaw ?: "{}", "https://raw.githubusercontent.com/simonw/llm-prices/main/data/")
        }
        if (enabled("aa")) SourceRow(com.ai.data.MetadataIconsHolder.current.chart, "Artificial Analysis", aaRaw) {
            onOpen("Artificial Analysis", aaRaw ?: "{}", "https://artificialanalysis.ai/api/v2/data/llms/models")
        }
        if (enabled("requesty")) SourceRow(com.ai.data.MetadataIconsHolder.current.web, "Requesty", requestyRaw) {
            onOpen("Requesty", requestyRaw ?: "{}", "https://router.requesty.ai/v1/models")
        }
        if (enabled("llmstats")) SourceRow(com.ai.data.MetadataIconsHolder.current.chart, "llm-stats", llmStatsRaw) {
            onOpen("llm-stats", llmStatsRaw ?: "{}", "https://api.llm-stats.com/stats/v1/models")
        }
        if (enabled("genaiprices")) SourceRow(com.ai.data.MetadataIconsHolder.current.cost, "genai-prices", genaiPricesRaw) {
            onOpen("genai-prices", genaiPricesRaw ?: "{}", "https://raw.githubusercontent.com/pydantic/genai-prices/main/prices/data_slim.json")
        }
        if (enabled("truefoundry")) SourceRow(com.ai.data.MetadataIconsHolder.current.packageBox, "TrueFoundry", trueFoundryRaw) {
            onOpen("TrueFoundry", trueFoundryRaw ?: "{}", "https://github.com/truefoundry/models")
        }
        if (enabled("cloudprice")) SourceRow(com.ai.data.MetadataIconsHolder.current.web, "CloudPrice", cloudPriceRaw, isLast = true) {
            onOpen("CloudPrice", cloudPriceRaw ?: "{}", "https://ai.cloudprice.net/api/v1/models")
        }
    }
}

@Composable
private fun SourceRow(icon: String, label: String, raw: String?, isLast: Boolean = false, onTrace: (() -> Unit)? = null, onClick: () -> Unit) {
    val hasData = raw != null
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = hasData) { if (hasData) onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .alpha(if (hasData) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = AppColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (hasData) com.ai.data.MetadataIconsHolder.current.checkMark else "·",
            color = if (hasData) AppColors.SuccessAccent else AppColors.TextTertiary,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
        )
        // 🐞 trace-link — present when this source has been fetched at
        // least once (its `<info/…>` trace exists). Full opacity even on
        // an absent row so a prior fetch's trace stays reachable.
        if (onTrace != null) {
            Text(
                com.ai.data.MetadataIconsHolder.current.traces, fontSize = 16.sp,
                modifier = Modifier
                    .alpha(1f)
                    .clickable { onTrace() }
                    .padding(start = 12.dp)
            )
        }
    }
    if (!isLast) {
        HorizontalDivider(color = AppColors.DividerDark.copy(alpha = 0.6f), thickness = 1.dp)
    }
}

@Composable
private fun CostsCard(tierBreakdown: PricingCache.TierBreakdown?) {
    val rows = listOfNotNull(
        tierBreakdown?.litellm?.let { "LiteLLM" to it },
        tierBreakdown?.modelsDev?.let { "models.dev" to it },
        tierBreakdown?.helicone?.let { "Helicone" to it },
        tierBreakdown?.llmPrices?.let { "llm-prices.com" to it },
        tierBreakdown?.artificialAnalysis?.let { "Artificial Analysis" to it },
        tierBreakdown?.llmStats?.let { "llm-stats" to it },
        tierBreakdown?.openrouter?.let { "OpenRouter" to it },
        tierBreakdown?.requesty?.let { "Requesty" to it },
        tierBreakdown?.genaiPrices?.let { "genai-prices" to it },
        tierBreakdown?.trueFoundry?.let { "TrueFoundry" to it },
        tierBreakdown?.override?.let { "Override" to it }
    )
    SectionCard(title = "Costs (per million tokens)") {
        if (tierBreakdown == null) {
            Text("Loading cost sources…", fontSize = 12.sp, color = AppColors.TextTertiary)
        } else if (rows.isEmpty()) {
            Text(
                "No catalog entry — lookup falls back to the built-in default.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
        } else {
            rows.forEach { (label, p) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                    Text(
                        "${"%.4f".format(Locale.US, p.promptPrice * 1_000_000)} / ${"%.4f".format(Locale.US, p.completionPrice * 1_000_000)}",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.SuccessAccent
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
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.SuccessAccent
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
            "$label:", fontSize = 12.sp, color = AppColors.InfoAccent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(80.dp)
        )
        Text(
            name, fontSize = 13.sp, color = AppColors.TextPrimary,
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
                    e.typeLabel, fontSize = 12.sp, color = AppColors.WarningAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    e.title, fontSize = 13.sp, color = AppColors.TextPrimary,
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
            .background(AppColors.AppBackground)
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

private const val MAX_JSON_RENDER_DEPTH = 12

@Composable
private fun ParsedJsonValue(element: JsonElement, depth: Int) {
    // Cap composition recursion + cumulative indent: pathologically deep
    // catalog records would otherwise blow the composition depth and push
    // content off-screen. Show a raw, expandable-on-tap blob past the cap.
    if (depth > MAX_JSON_RENDER_DEPTH && (element is JsonArray || element is JsonObject)) {
        var expanded by remember { mutableStateOf(false) }
        if (!expanded) {
            Text(
                "… (deeply nested — tap to show raw)",
                fontSize = 12.sp, color = AppColors.TextTertiary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { expanded = true }
            )
            return
        }
        Text(
            element.toString(), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = AppColors.TextSecondary,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
        return
    }
    // Clamp the indent so very deep (but under the cap) nesting doesn't push
    // content off the right edge.
    val indentDp = (minOf(depth, MAX_JSON_RENDER_DEPTH) * 12).dp
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
                            modifier = Modifier.fillMaxWidth().padding(start = indentDp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "[$idx]:",
                                fontSize = 12.sp,
                                color = AppColors.SecondaryAccent,
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
                            modifier = Modifier.fillMaxWidth().padding(start = indentDp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "$key:",
                                fontSize = 12.sp,
                                color = AppColors.InfoAccent,
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
    p.isBoolean -> AppColors.WarningAccent
    p.isNumber -> AppColors.CautionAccent
    p.isString -> AppColors.SuccessAccent
    else -> AppColors.TextPrimary
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
    onOpenReportAtAgent: (reportId: String, agentId: String) -> Unit
): List<ViewUsageEntry> {
    // Per the user spec: only "main report model" usage — drop
    // chats and secondaries (rerank / meta / moderation / translate
    // calls live under their own model paths anyway and would
    // double-count here). One row per matching report; the tap
    // opens View Reports for that report, pre-scrolled to the
    // agent that actually used this provider/model.
    val out = mutableListOf<ViewUsageEntry>()
    val reports = ReportStorage.getAllReports(context).sortedByDescending { it.timestamp }
    for (report in reports) {
        if (out.size >= 5) break
        val matchingAgent = report.agents.firstOrNull { it.provider == provider.id && it.model == model }
            ?: continue
        out += ViewUsageEntry(
            timestamp = report.timestamp,
            typeLabel = "Report",
            title = report.title.ifBlank { report.prompt.take(80) }
        ) { onOpenReportAtAgent(report.id, matchingAgent.agentId.ifBlank { matchingAgent.agentName }) }
    }
    return out
}
