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
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.ui.helpers.formatPricingPerMillion
import com.ai.ui.settings.AgentEditScreen
import com.ai.data.preferences.SettingsPreferences
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One row in the "Last usage" card on the Model Info screen.
 *  Aggregated from chat history, reports, and per-report secondary
 *  results so the user sees every concrete place this (provider,
 *  model) pair has been used recently. */
private data class ModelUsageEntry(
    val timestamp: Long,
    val typeLabel: String,
    val title: String,
    val onOpen: () -> Unit,
    /** Whether tapping the row navigates anywhere. Chat rows have no
     *  deep-link, so their row shouldn't appear clickable (no ripple). */
    val navigable: Boolean = true
)

/** Walk every chat session, report, and per-report secondary result;
 *  keep the rows whose (provider, model) matches; sort newest first
 *  and return the top 10. Caller invokes from a coroutine on
 *  Dispatchers.IO since each store is on-disk. */
private fun computeModelUsages(
    context: android.content.Context,
    provider: AppService,
    model: String,
    onOpenReport: (String) -> Unit
): List<ModelUsageEntry> {
    val out = mutableListOf<ModelUsageEntry>()
    fun chatTitle(s: ChatSession): String {
        val firstLine = s.messages.firstOrNull { it.role == "user" }?.content?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }?.trim()
            ?: return "Chat session"
        return if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
    }
    ChatHistoryManager.init(context)
    // Bound chat collection to the newest matching sessions: the final list is
    // take(10) after a cross-source sort, but if chats were added unbounded a
    // user with >30 sessions on this model would exhaust the candidate cap
    // before any report/secondary candidate was even considered, dropping a
    // recent report that should have ranked top-10.
    ChatHistoryManager.getAllSessions()
        .filter { it.provider.id == provider.id && it.model == model }
        .sortedByDescending { it.updatedAt }
        .take(30)
        .forEach { s ->
            out += ModelUsageEntry(
                timestamp = s.updatedAt, typeLabel = "Chat", title = chatTitle(s),
                onOpen = {}, // chat session deep-link not supported; row is informational
                navigable = false
            )
        }
    // Walk reports newest-first and stop once we have a comfortable
    // surplus of candidates — the final list is take(10) after a
    // fan out-source sort, so a 3× cap (30) covers chat / report / per-
    // report secondary tiers without re-parsing every old report's
    // secondary index. Previously this scanned every report PLUS
    // every secondary file on every report on every Model Info open,
    // which dominated the screen open time once the user had a few
    // dozen reports on disk.
    val reports = ReportStorage.getAllReports(context).sortedByDescending { it.timestamp }
    val candidateCap = 30
    // Cap only the report/secondary candidates (the chat candidates above are
    // already capped separately), so reports are always considered regardless
    // of how many chat sessions exist.
    val reportStartSize = out.size
    for (report in reports) {
        if (out.size - reportStartSize >= candidateCap) break
        report.agents.forEach { agent ->
            if (agent.provider == provider.id && agent.model == model) {
                out += ModelUsageEntry(
                    timestamp = report.timestamp, typeLabel = "Report",
                    title = report.title.ifBlank { report.prompt.take(80) },
                    onOpen = { onOpenReport(report.id) }
                )
            }
        }
        SecondaryResultStorage.listForReport(context, report.id).forEach { sec ->
            if (sec.providerId == provider.id && sec.model == model) {
                val typeLabel = when (sec.kind) {
                    SecondaryKind.RERANK -> "Rerank"
                    SecondaryKind.META -> sec.metaPromptName?.takeIf { it.isNotBlank() } ?: "Meta"
                    SecondaryKind.MODERATION -> "Moderate"
                    SecondaryKind.TRANSLATE -> "Translate"
                    SecondaryKind.TOURNAMENT -> "Tournament"
                    SecondaryKind.JUDGES -> "Judges"
                    SecondaryKind.COMPARE -> "Compare"
                    SecondaryKind.TRANSRANK -> "Rank translators"
                }
                out += ModelUsageEntry(
                    timestamp = sec.timestamp, typeLabel = typeLabel,
                    title = "from ${report.title.ifBlank { report.prompt.take(60) }}",
                    onOpen = { onOpenReport(report.id) }
                )
            }
        }
    }
    return out.sortedByDescending { it.timestamp }.take(10)
}

private data class ModelInfoData(
    val openRouterInfo: OpenRouterModelInfo? = null,
    val huggingFaceInfo: HuggingFaceModelInfo? = null,
    val hasPricing: Boolean = false
)

// ===== Model Info Screen =====

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelInfoScreen(
    provider: AppService,
    modelName: String,
    openRouterApiKey: String,
    huggingFaceApiKey: String,
    aiSettings: Settings,
    repository: com.ai.data.AnalysisRepository,
    settingsPrefs: SettingsPreferences,
    onSaveSettings: (Settings) -> Unit,
    onTestAiModel: suspend (AppService, String, String) -> String?,
    onFetchModels: (AppService, String) -> Unit,
    onStartChat: (AppService, String) -> Unit,
    onNavigateToTracesForModel: (AppService, String) -> Unit,
    onNavigateToAddManualOverride: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToAddCostOverride: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToProviderEdit: (AppService) -> Unit = {},
    /** "Model in AI configuration" card click targets — each opens the
     *  matching SettingsScreen subscreen so the user lands on the list
     *  containing this model's entry. */
    onNavigateToBlockedModels: () -> Unit = {},
    onNavigateToInaccessibleModels: () -> Unit = {},
    onNavigateToCooldowns: () -> Unit = {},
    onNavigateToModelTypes: () -> Unit = {},
    /** "Workers" card click targets — each opens the worker's edit
     *  screen, deep-linked by id. */
    onNavigateToAgentEdit: (String) -> Unit = {},
    onNavigateToFlockEdit: (String) -> Unit = {},
    onNavigateToSwarmEdit: (String) -> Unit = {},
    onOpenReport: (String) -> Unit = {},
    /** Open a per-info-provider help topic. Wired by AppNavHost to
     *  the helpForTopic(id) route. Used by the Costs card, the
     *  Capabilities source labels, and the per-section "Source:"
     *  footer to make every info-provider name clickable. */
    onNavigateToHelpTopic: (String) -> Unit = {},
    /** Optional 👁 view-screen hook. Wired by AppNavHost to
     *  navController.navigate(NavRoutes.aiModelInfoView(...));
     *  back returns here via Jetpack Nav. */
    onOpenView: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current
    var aiDescription by remember { mutableStateOf<String?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }
    var showAgentEdit by remember { mutableStateOf(false) }
    // null when the raw-view overlay isn't shown; otherwise (title, json) for the source.
    /** State for the raw-info detail overlay opened from a Source
     *  button. When [provider] is non-null the overlay renders the
     *  restructured Source-detail layout (fixed "Info provider"
     *  title + green provider name + dim called URL); the "Show all"
     *  combined view leaves [provider] null and falls back to the
     *  legacy title-only shape. */
    data class RawView(
        val title: String,
        val body: String,
        val provider: com.ai.ui.admin.InfoProviderRef? = null,
        val calledUrl: String? = null
    )
    var rawView by remember { mutableStateOf<RawView?>(null) }

    // Trace count + usage entry — loaded off the main thread because
    // ApiTracer.getTraceFiles() may parse every captured trace file
    // on cold cache, loadUsageStats() reads SharedPreferences, and
    // PricingCache.getPricing can fan out into a disk-backed cache
    // load. Doing all three synchronously inside `remember` blocks
    // dominated the screen open time once the user had a heavy
    // trace dir or a fresh process. The card hides until the value
    // arrives so the slot isn't filled with a stale zero.
    val traceCount by produceState(initialValue = 0, provider, modelName) {
        value = withContext(Dispatchers.IO) {
            // Match on (hostname, model) — model name alone is not unique
            // across providers (gpt-4o exists on OpenAI / Azure / OpenRouter
            // proxies / etc.), so the previous count conflated calls to the
            // same model name on every provider into the per-provider total.
            // The synthetic LOCAL provider has a blank baseUrl, so derive its
            // host explicitly ("local", matching LocalLlm/LocalEmbedder traces)
            // rather than falling back to model-name-only matching — which would
            // conflate same-name models across providers, the exact bug the host
            // match exists to prevent.
            val providerHost = if (provider.id == "LOCAL") "local"
                else runCatching { java.net.URI(provider.baseUrl).host?.lowercase() }.getOrNull()
            if (providerHost == null) return@withContext 0
            ApiTracer.getTraceFiles().count { tf ->
                tf.model == modelName && tf.hostname.equals(providerHost, ignoreCase = true)
            }
        }
    }
    val usageEntry by produceState<com.ai.model.UsageStats?>(initialValue = null, provider, modelName) {
        value = withContext(Dispatchers.IO) {
            // Usage stats are keyed "${provider.id}::$model::$kind" (3-part).
            // A 2-part lookup never matched, so the AI Usage card always read
            // "No usage recorded yet". Aggregate across kinds via the same
            // prefix match the View screen uses.
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

    // Full-screen overlay rendering the raw JSON for one of the three
    // catalog sources (HuggingFace, OpenRouter, LiteLLM). Returns to
    // Model Info on back.
    rawView?.let { rv ->
        ModelRawInfoScreen(
            title = rv.title, body = rv.body,
            provider = rv.provider, calledUrl = rv.calledUrl,
            onNavigateToHelpTopic = onNavigateToHelpTopic,
            onBack = { rawView = null }, onNavigateHome = onNavigateHome
        )
        return
    }

    // Full-screen overlay for creating an agent from this model.
    if (showAgentEdit) {
        AgentEditScreen(
            agent = Agent(java.util.UUID.randomUUID().toString(), "${provider.id} $modelName", provider, modelName, aiSettings.getApiKey(provider)),
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

    // OpenRouter catalog lookup — runs in parallel with the HF call
    // below. Each card consuming OR data renders as soon as this
    // arrives; the rest of the page doesn't wait. The page paints
    // immediately on entry — cards that need this just stay hidden
    // until orInfo becomes non-null.
    val orInfo by produceState<OpenRouterModelInfo?>(initialValue = null, provider, modelName) {
        if (openRouterApiKey.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val models = OpenRouterModelInfoCache.getOpenRouterModels(openRouterApiKey)
                // Provider APIs and OpenRouter disagree on punctuation —
                // Anthropic ships "claude-opus-4-6" while OpenRouter
                // catalogs it as "anthropic/claude-opus-4.6". Normalize
                // both sides by treating '.' and '-' as equivalent.
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

    // HuggingFace lookup — cached for a week (HuggingFaceCache), including
    // misses so the next visit short-circuits without a network call. Runs
    // in its own background coroutine so a DNS timeout on huggingface.co
    // can't stall the rest of the page; the HF-dependent cards just
    // appear when the call returns (or never, for a cached miss).
    // The HF model id that actually resolved (a "-"/"." variant may match
    // instead of the literal modelName). Surfaced as the raw-source "called
    // URL" so the displayed URL reflects the request that succeeded.
    var hfMatchedId by remember(provider, modelName) { mutableStateOf<String?>(null) }
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
                    val resp = com.ai.data.withTraceCategory("info/huggingface") {
                        ApiFactory.createHuggingFaceApi().getModelInfo(cand, "Bearer $huggingFaceApiKey")
                    }
                    if (resp.isSuccessful) { found = resp.body(); hfMatchedId = cand; break }
                } catch (_: Exception) { /* swallow; cache the miss below */ }
            }
            HuggingFaceCache.put(context, provider.id, modelName, found)
            found
        }
    }

    // Combined view kept for compat with existing read sites (info?.
    // huggingFaceInfo etc. throughout the LazyColumn). Recomposes as
    // soon as either side arrives.
    val modelInfo: ModelInfoData? = remember(orInfo, hfInfo) {
        if (orInfo == null && hfInfo == null) null
        else ModelInfoData(openRouterInfo = orInfo, huggingFaceInfo = hfInfo, hasPricing = orInfo?.pricing != null)
    }

    // AI description — opt-in. The "model_info" internal prompt + the
    // page's own (provider, model) build a request that asks the model to
    // introduce itself. We only peek at PromptCache on screen open so a
    // previously-completed result shows immediately; otherwise the user
    // gets a button. The configured agent's resolved Parameters preset
    // still propagates so the user's temperature / max_tokens carry over.
    val scope = rememberCoroutineScope()
    // Model-info template lives in Settings.internalPrompts under
    // the "model_info" name. Falls back to empty when the user has
    // deleted that entry (the next app start will re-seed it from
    // assets/internal-prompts/). Default AgentParameters — there's no
    // longer an agent binding to inherit temperature / max_tokens
    // preset from.
    val modelInfoPromptTemplate = remember(aiSettings) {
        aiSettings.getInternalPromptByName("model-info")?.text.orEmpty()
    }
    val pageApiKey = aiSettings.getApiKey(provider)
    val introResolvedPrompt = remember(modelInfoPromptTemplate, provider, modelName) {
        modelInfoPromptTemplate
            .replace("@MODEL@", modelName)
            .replace("@PROVIDER@", provider.id)
            .replace("@AGENT@", "${provider.id} / $modelName")
    }
    val introCacheKey = remember(introResolvedPrompt, provider, modelName) {
        PromptCache.keyFor(introResolvedPrompt, "${provider.id}:$modelName", variant = "params=default|systemPrompt=")
    }
    val canRequestIntro = pageApiKey.isNotBlank()
    LaunchedEffect(introCacheKey) {
        // Read via getRaw (no destructive 48h TTL) like the View screen, so a
        // cached intro doesn't silently disappear after 48h and re-prompt an
        // "Ask" on the same model the user already introduced.
        withContext(Dispatchers.IO) { PromptCache.getRaw(introCacheKey) }?.let { aiDescription = it.response }
    }
    val requestIntroduction: () -> Unit = req@{
        if (!canRequestIntro || isAiLoading) return@req
        val selfAgent = Agent(
            id = "model_info_self:${provider.id}:$modelName",
            name = "${provider.id} / $modelName",
            provider = provider, model = modelName,
            apiKey = pageApiKey
        )
        scope.launch {
            isAiLoading = true
            try {
                com.ai.data.withTraceCategory("Model self-intro") {
                    val response = withContext(Dispatchers.IO) {
                        repository.analyzePlayerWithAgent(selfAgent, introResolvedPrompt, AgentParameters())
                    }
                    if (response.isSuccess) {
                        aiDescription = response.analysis
                        response.analysis?.let { PromptCache.put(introCacheKey, it) }
                    }
                }
            } catch (_: Exception) {} finally {
                isAiLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "model_info",
            title = "Model Info",
            subject = "${provider.id} · $modelName",
            onBackClick = onNavigateBack,
            onOpenView = onOpenView,
            onTrace = if (ApiTracer.ladybugLinksEnabled && traceCount > 0) {
                { onNavigateToTracesForModel(provider, modelName) }
            } else null
        )

        run {
            // The page paints immediately — every card uses produceState
            // (or in-memory aiSettings) so its data arrives in the
            // background. Cards that need OR / HF info just stay hidden
            // until their respective lookup returns. No global
            // "Loading model info…" spinner gates the screen anymore.
            val info = modelInfo
                // Aggregated last-10 usages across chat, reports, and
                // per-report secondaries (translate / meta / rerank /
                // moderate). Empty list = card hidden.
                val recentUsages by produceState<List<ModelUsageEntry>>(initialValue = emptyList(), provider, modelName) {
                    value = withContext(Dispatchers.IO) {
                        computeModelUsages(context, provider, modelName, onOpenReport)
                    }
                }
                // Hoisted out of LazyColumn — the LazyListScope.() -> Unit
                // lambda below isn't @Composable, so remember /
                // rememberCoroutineScope / collectAsState calls have to
                // live in the surrounding Column scope.
                val isProviderActive = aiSettings.isProviderActive(provider)
                val testScope = rememberCoroutineScope()
                var testRunning by remember { mutableStateOf(false) }
                var testResult by remember { mutableStateOf<String?>(null) }
                var testPassed by remember { mutableStateOf<Boolean?>(null) }
                val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
                val cooldownUntil = cooldowns["${provider.id}:$modelName"]
                    ?.takeIf { it > System.currentTimeMillis() }
                val hasTypeOverride = aiSettings.modelTypeOverrides.any {
                    it.providerId == provider.id && it.modelId == modelName
                }
                val hasCostOverride by produceState(initialValue = false, provider, modelName) {
                    value = withContext(Dispatchers.IO) {
                        PricingCache.getManualPricing(context, provider, modelName) != null
                    }
                }
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
                // CloudPrice — lazy per-model detail lookup, mirroring the
                // HuggingFace block above: check the per-model cache, else hit
                // /api/v1/models/{id} live (bare id + dash<->dot variants),
                // cache the result (incl. a negative cache on 404), and show
                // the raw JSON. Retrofit-based, so a 404 is a silent
                // isSuccessful == false — no error toast.
                val cloudPriceRaw by produceState<String?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) {
                        if (!aiSettings.isInfoProviderEnabled(com.ai.data.InfoProvider.CLOUDPRICE.id)) return@withContext null
                        com.ai.data.CloudPriceModelCache.get(context, provider.id, modelName)?.let { return@withContext it.json }
                        val variants = sequenceOf(modelName, modelName.replace('-', '.'), modelName.replace('.', '-')).distinct()
                        var found: String? = null
                        for (cand in variants) {
                            try {
                                val resp = com.ai.data.withTraceCategory("info/cloudprice") {
                                    ApiFactory.createCloudPriceApi().getModel(cand)
                                }
                                if (resp.isSuccessful) {
                                    found = resp.body()?.string()?.let { raw ->
                                        runCatching { com.ai.data.createAppGson(prettyPrint = true).toJson(com.google.gson.JsonParser.parseString(raw)) }.getOrDefault(raw)
                                    }
                                    break
                                }
                            } catch (_: Exception) {}
                        }
                        com.ai.data.CloudPriceModelCache.put(context, provider.id, modelName, found)
                        found
                    }
                }
                // CloudPrice model description, parsed from the live detail
                // JSON (data.description). Drives the conditional CloudPrice
                // "Description" card after the OpenRouter one.
                val cloudPriceDescription = remember(cloudPriceRaw) {
                    cloudPriceRaw?.let { raw ->
                        runCatching {
                            com.google.gson.JsonParser.parseString(raw).asJsonObject
                                .getAsJsonObject("data")?.get("description")
                                ?.takeIf { it.isJsonPrimitive }?.asString
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }
                val tierBreakdown by produceState<PricingCache.TierBreakdown?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) {
                        PricingCache.getTierBreakdown(context, provider, modelName)
                    }
                }
                // Capability sidecars from each info provider — drive the
                // per-source detail cards further down. Loaded off the main
                // thread; each card hides when its meta is null.
                val aaMeta by produceState<PricingCache.ArtificialAnalysisMeta?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) { PricingCache.getArtificialAnalysisMeta(provider, modelName) }
                }
                val llmStatsMeta by produceState<PricingCache.LlmStatsMeta?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) { PricingCache.getLlmStatsMeta(provider, modelName) }
                }
                val modelsDevMeta by produceState<PricingCache.ModelsDevMeta?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) { PricingCache.getModelsDevMeta(provider, modelName) }
                }
                val requestyMeta by produceState<PricingCache.RequestyMeta?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) { PricingCache.getRequestyMeta(provider, modelName) }
                }
                val trueFoundryMeta by produceState<PricingCache.TrueFoundryMeta?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) { PricingCache.getTrueFoundryMeta(provider, modelName) }
                }
                val liteLLMMeta by produceState<PricingCache.LiteLLMMeta?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) { PricingCache.getLiteLLMMeta(provider, modelName) }
                }
                val genaiPricesMeta by produceState<PricingCache.GenaiPricesMeta?>(initialValue = null, provider, modelName) {
                    value = withContext(Dispatchers.IO) { PricingCache.getGenaiPricesMeta(provider, modelName) }
                }
                // CloudPrice live detail, parsed into label/value detail rows +
                // capability flags for the two CloudPrice cards.
                val cloudPriceData = remember(cloudPriceRaw) {
                    cloudPriceRaw?.let { raw ->
                        runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject.getAsJsonObject("data") }.getOrNull()
                    }
                }
                val cloudPriceRows: List<Pair<String, String>> = remember(cloudPriceData) {
                    val d = cloudPriceData ?: return@remember emptyList()
                    fun s(k: String) = d.get(k)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                    fun i(k: String) = d.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
                    buildList {
                        s("family")?.let { add("Family" to it) }
                        s("tier")?.let { add("Tier" to it) }
                        s("version")?.let { add("Version" to it) }
                        s("type")?.let { add("Type" to it) }
                        s("tokenizer")?.let { add("Tokenizer" to it) }
                        (d.getAsJsonObject("modalities"))?.let { m ->
                            val inp = m.getAsJsonArray("input")?.mapNotNull { e -> e.asString }?.joinToString(", ")
                            val out = m.getAsJsonArray("output")?.mapNotNull { e -> e.asString }?.joinToString(", ")
                            if (!inp.isNullOrBlank() && !out.isNullOrBlank()) add("Modalities" to "$inp → $out")
                        }
                        i("context_window")?.let { add("Context Window" to formatCompactNumber(it.toLong())) }
                        i("max_output_tokens")?.let { add("Max Output" to formatCompactNumber(it.toLong())) }
                        d.getAsJsonArray("supported_reasoning_efforts")?.mapNotNull { it.asString }?.takeIf { it.isNotEmpty() }
                            ?.let { add("Reasoning efforts" to it.joinToString(", ")) }
                        s("knowledge_cutoff")?.let { add("Knowledge cutoff" to it) }
                        s("training_data_cutoff")?.let { add("Training cutoff" to it) }
                        s("release_date")?.let { add("Released" to it) }
                        s("earliest_deprecation_date")?.let { add("Earliest deprecation" to it) }
                        d.get("deprecated")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean?.let { if (it) add("Deprecated" to "Yes") }
                        i("provider_count")?.let { add("Providers serving" to it.toString()) }
                        i("tool_use_system_prompt_tokens")?.let { if (it > 0) add("Tool-use overhead" to "$it tok") }
                    }
                }
                val cloudPriceCaps: List<Pair<String, Boolean>> = remember(cloudPriceData) {
                    cloudPriceData?.getAsJsonObject("capabilities")?.entrySet()?.mapNotNull { (k, v) ->
                        if (v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) k to v.asBoolean else null
                    } ?: emptyList()
                }
                val blockedReason = aiSettings.blockedModels
                    .firstOrNull { it.providerId == provider.id && it.model == modelName }?.reason
                val inaccessibleReason = aiSettings.inaccessibleModels
                    .firstOrNull { it.providerId == provider.id && it.model == modelName }?.reason
                val inAnyConfig = hasTypeOverride || hasCostOverride ||
                    cooldownUntil != null || blockedReason != null || inaccessibleReason != null
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
                val inAnyWorker = matchedAgents.isNotEmpty() || matchedFlocks.isNotEmpty() || matchedSwarms.isNotEmpty()
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Model name moved to the green sub-header above the
                    // LazyColumn. The 🐞 ladybug + the dedicated "API
                    // Traces" card both live behind the title-bar 🐞
                    // icon now. Provider link gets its own card just
                    // below Capabilities (further down).

                    val hasUsageStats = (usageEntry?.callCount ?: 0) > 0
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Actions", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
                                // FlowRow + content-width buttons so every label
                                // ("Create Agent" etc.) shows in full and wraps to
                                // the next line if they don't all fit on one row.
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { onStartChat(provider, modelName) },
                                        colors = AppColors.outlinedButtonColors()
                                    ) { Text("Start Chat", maxLines = 1, softWrap = false) }
                                    OutlinedButton(
                                        onClick = { showAgentEdit = true },
                                        colors = AppColors.outlinedButtonColors()
                                    ) { Text("Create Agent", maxLines = 1, softWrap = false) }
                                    if (isProviderActive) {
                                        OutlinedButton(
                                            onClick = {
                                                testRunning = true
                                                testResult = null
                                                testPassed = null
                                                testScope.launch {
                                                    val apiKey = aiSettings.getApiKey(provider)
                                                    val err = onTestAiModel(provider, apiKey, modelName)
                                                    testPassed = err == null
                                                    testResult = err ?: "OK"
                                                    testRunning = false
                                                }
                                            },
                                            enabled = !testRunning,
                                            colors = AppColors.outlinedButtonColors()
                                        ) { Text(if (testRunning) "Testing…" else "Test", maxLines = 1, softWrap = false) }
                                    }
                                }
                                testResult?.let { r ->
                                    val passed = testPassed == true
                                    val mi = com.ai.ui.shared.LocalMetadataIcons.current
                                    Text(
                                        text = if (passed) "${mi.statusDone} $r" else "${mi.statusFailed} $r",
                                        fontSize = 12.sp,
                                        color = if (passed) AppColors.SuccessAccent else AppColors.DangerAccent
                                    )
                                }
                            }
                        }
                    }

                    // "Model in AI configuration" card — shown only when
                    // at least one of the five configuration lists has
                    // an entry for this (provider, model). Each row is
                    // clickable and deep-links into the matching CRUD.
                    if (inAnyConfig) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "Model in AI configuration",
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        color = AppColors.InfoAccent
                                    )
                                    if (hasTypeOverride) {
                                        ModelConfigRow(
                                            label = "Model overrides",
                                            value = aiSettings.getModelType(provider, modelName) ?: "—",
                                            onClick = { onNavigateToAddManualOverride(provider, modelName) }
                                        )
                                    }
                                    if (hasCostOverride) {
                                        ModelConfigRow(
                                            label = "Cost overrides",
                                            value = "manual pricing set",
                                            onClick = { onNavigateToAddCostOverride(provider, modelName) }
                                        )
                                    }
                                    if (cooldownUntil != null) {
                                        ModelConfigRow(
                                            label = "Model cooldowns",
                                            value = com.ai.data.ModelCooldownStore.cooldownCaption(cooldownUntil),
                                            onClick = onNavigateToCooldowns
                                        )
                                    }
                                    if (blockedReason != null) {
                                        ModelConfigRow(
                                            label = "Blocked models",
                                            value = if (blockedReason.isBlank()) "blocked" else blockedReason,
                                            onClick = onNavigateToBlockedModels
                                        )
                                    }
                                    if (inaccessibleReason != null) {
                                        ModelConfigRow(
                                            label = "Inaccessible models",
                                            value = if (inaccessibleReason.isBlank()) "inaccessible" else inaccessibleReason,
                                            onClick = onNavigateToInaccessibleModels
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // "Workers" card — Agents matching (provider, model),
                    // Flocks containing such an agent, Swarms with such a member.
                    if (inAnyWorker) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "Workers",
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        color = AppColors.InfoAccent
                                    )
                                    matchedAgents.forEach { a ->
                                        ModelConfigRow(
                                            label = "Agent",
                                            value = a.name,
                                            onClick = { onNavigateToAgentEdit(a.id) }
                                        )
                                    }
                                    matchedFlocks.forEach { f ->
                                        ModelConfigRow(
                                            label = "Flock",
                                            value = f.name,
                                            onClick = { onNavigateToFlockEdit(f.id) }
                                        )
                                    }
                                    matchedSwarms.forEach { s ->
                                        ModelConfigRow(
                                            label = "Swarm",
                                            value = s.name,
                                            onClick = { onNavigateToSwarmEdit(s.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }


                    // Per-tier price snapshot — LiteLLM / OpenRouter / Override
                    // shown as $/M-token rows when populated. Default tier is
                    // omitted; if all three are missing, render a single
                    // "no source-specific price" line so the card still
                    // explains why the cost lookup will fall back.
                    item {
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
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Costs (per million tokens)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
                                if (tierBreakdown == null) {
                                    Text("Loading cost sources…", fontSize = 12.sp, color = AppColors.TextTertiary)
                                } else if (rows.isEmpty()) {
                                    Text("No LiteLLM / models.dev / OpenRouter / Override entry — lookup falls back to the built-in default.",
                                        fontSize = 12.sp, color = AppColors.TextTertiary)
                                } else {
                                    rows.forEach { (label, p) ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Provider name → help topic when it
                                            // resolves to one of the seven info
                                            // providers ("Override" stays plain).
                                            InfoProviderName(
                                                name = label,
                                                fontSize = 13.sp,
                                                plainColor = AppColors.TextPrimary,
                                                onNavigateToHelpTopic = onNavigateToHelpTopic,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                "${"%.4f".format(Locale.US, p.promptPrice * 1_000_000)} / ${"%.4f".format(Locale.US, p.completionPrice * 1_000_000)}",
                                                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.SuccessAccent
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
                                                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.SuccessAccent
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = { onNavigateToAddCostOverride(provider, modelName) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = AppColors.outlinedButtonColors()
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
                        val mi = com.ai.ui.shared.LocalMetadataIcons.current
                        val visionSrc = "Vision ${mi.view}: ${if (visionEffective) "yes" else "no"}" to
                            source(visionPinned, providerVision, litellmVision, modelsDevVision)
                        val webSrc = "Web search ${mi.web}: ${if (webEffective) "yes" else "no"}" to
                            source(webPinned, providerWeb, litellmWeb, modelsDevWeb)
                        val reasoningSrc = "Thinking ${mi.reportModelIcon}: ${if (reasoningEffective) "yes" else "no"}" to
                            source(reasoningPinned, providerReasoning, litellmReasoning, modelsDevReasoning)
                        val (visionLabel, visionSrcText) = visionSrc
                        val (webLabel, webSrcText) = webSrc
                        val (reasoningLabel, reasoningSrcText) = reasoningSrc
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Capabilities", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
                                Row {
                                    Text(visionLabel, fontSize = 13.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                                    InfoProviderName(
                                        name = visionSrcText, fontSize = 12.sp,
                                        plainColor = AppColors.TextTertiary,
                                        onNavigateToHelpTopic = onNavigateToHelpTopic
                                    )
                                }
                                Row {
                                    Text(webLabel, fontSize = 13.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                                    InfoProviderName(
                                        name = webSrcText, fontSize = 12.sp,
                                        plainColor = AppColors.TextTertiary,
                                        onNavigateToHelpTopic = onNavigateToHelpTopic
                                    )
                                }
                                Row {
                                    Text(reasoningLabel, fontSize = 13.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                                    InfoProviderName(
                                        name = reasoningSrcText, fontSize = 12.sp,
                                        plainColor = AppColors.TextTertiary,
                                        onNavigateToHelpTopic = onNavigateToHelpTopic
                                    )
                                }
                                // PDF input — currently only Anthropic
                                // self-reports this on its /v1/models, so
                                // the source label is fixed when present.
                                cfg.modelCapabilities[modelName]?.supportsPdfInput?.let { pdf ->
                                    Row {
                                        Text("PDF input ${mi.document}: ${if (pdf) "yes" else "no"}",
                                            fontSize = 13.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                                        Text("Provider self-report", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                }
                                cfg.modelCapabilities[modelName]?.deprecationDate?.let { date ->
                                    val replacement = cfg.modelCapabilities[modelName]?.deprecationReplacement
                                    val msg = if (replacement.isNullOrBlank()) "${mi.warningPlain} Deprecated $date"
                                        else "${mi.warningPlain} Deprecated $date ${mi.arrowRight} use $replacement"
                                    Row {
                                        Text(msg, fontSize = 13.sp, color = AppColors.WarningAccent, modifier = Modifier.weight(1f))
                                        Text("Provider", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                }
                                // Provider-recommended sampling defaults
                                // (Mistral default_model_temperature, Together
                                // config.stop). Shown so the user can see what
                                // the upstream considers neutral before
                                // diverging in a Parameters preset.
                                cfg.modelCapabilities[modelName]?.defaultTemperature?.let { t ->
                                    Row {
                                        Text("Default temperature: $t", fontSize = 13.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                                        Text("Provider", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                }
                                cfg.modelCapabilities[modelName]?.defaultStopSequences?.takeIf { it.isNotEmpty() }?.let { stops ->
                                    Row {
                                        Text("Default stops: ${stops.joinToString(", ")}",
                                            fontSize = 13.sp, color = AppColors.TextPrimary,
                                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f))
                                        Text("Provider", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                // Add / edit manual override — opens the same form the
                                // Manual model types CRUD uses, pre-filled with this
                                // (provider, model). If an override already exists
                                // for this pair the form opens in edit mode.
                                OutlinedButton(
                                    onClick = { onNavigateToAddManualOverride(provider, modelName) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = AppColors.outlinedButtonColors()
                                ) { Text("Add manual override", fontSize = 14.sp, maxLines = 1, softWrap = false) }
                            }
                        }
                    }

                    // Provider link — moved out of the former top header
                    // card so the model name can stand alone as the
                    // page subject. Tapping the row opens the
                    // provider's edit page in Settings.
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                            modifier = Modifier.fillMaxWidth().clickable { onNavigateToProviderEdit(provider) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Provider", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
                                Text(provider.id, fontSize = 14.sp, color = AppColors.TextPrimary)
                            }
                        }
                    }

                    // The dedicated "API Traces" card is gone — the
                    // title-bar 🐞 icon opens the same model-filtered
                    // trace list when traceCount > 0.

                    // Usage entry for this provider/model (cumulative across
                    // reports + chats). Hidden entirely when there's no usage yet.
                    usageEntry?.let { ue ->
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Usage", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
                                    Text(
                                        "${ue.callCount} calls, ${formatCompactNumber(ue.inputTokens)} in / ${formatCompactNumber(ue.outputTokens)} out",
                                        fontSize = 13.sp, color = AppColors.TextPrimary
                                    )
                                    usageCost?.let {
                                        Text(
                                            "Cost: " + if (it < 0.01 && it > 0) String.format(Locale.US, "$%.6f", it) else String.format(Locale.US, "$%.4f", it),
                                            fontSize = 13.sp, color = AppColors.SuccessAccent
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // OpenRouter description
                    info?.openRouterInfo?.description?.let { desc ->
                        item {
                            ModelInfoSection("Description", "OpenRouter", onNavigateToHelpTopic) {
                                Text(desc, fontSize = 13.sp, color = AppColors.TextSecondary)
                            }
                        }
                    }

                    // CloudPrice description (conditional — only when the live
                    // per-model lookup returned a description). Sits right after
                    // the OpenRouter one.
                    cloudPriceDescription?.let { desc ->
                        item {
                            ModelInfoSection("Description", "CloudPrice", onNavigateToHelpTopic) {
                                Text(desc, fontSize = 13.sp, color = AppColors.TextSecondary)
                            }
                        }
                    }

                    // Technical specs
                    info?.openRouterInfo?.let { or ->
                        item {
                            ModelInfoSection("Technical Specifications", "OpenRouter", onNavigateToHelpTopic) {
                                or.context_length?.let { ModelInfoRow("Context Length", formatCompactNumber(it.toLong())) }
                                or.top_provider?.max_completion_tokens?.let { ModelInfoRow("Max Completion", formatCompactNumber(it.toLong())) }
                                or.architecture?.modality?.let { ModelInfoRow("Modality", it) }
                                or.architecture?.tokenizer?.let { ModelInfoRow("Tokenizer", it) }
                                or.architecture?.instruct_type?.let { ModelInfoRow("Instruct Type", it) }
                                or.top_provider?.is_moderated?.let { ModelInfoRow("Moderated", if (it) "Yes" else "No") }
                                or.knowledge_cutoff?.let { ModelInfoRow("Knowledge Cutoff", it) }
                                or.expiration_date?.let { ModelInfoRow("${com.ai.data.MetadataIconsHolder.current.warningPlain} Expires", it) }
                            }
                        }
                    }

                    // The OpenRouter Pricing card is gone — Cost Config
                    // (Settings → Cost Config) plus the per-row prices on
                    // every model picker / selection screen already
                    // surface effective pricing from the layered lookup;
                    // duplicating just the OpenRouter tier here was
                    // misleading whenever LiteLLM / models.dev / a manual
                    // override won the lookup.

                    // HuggingFace info
                    info?.huggingFaceInfo?.let { hf ->
                        item {
                            ModelInfoSection("HuggingFace", "HuggingFace", onNavigateToHelpTopic) {
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
                            ModelInfoSection("Tags", "HuggingFace", onNavigateToHelpTopic) {
                                Text(tags.joinToString(", "), fontSize = 12.sp, color = AppColors.TextTertiary)
                            }
                        }
                    }

                    // ───────── Extra per-info-provider detail cards ─────────
                    // Each hides when its source has no data for this model.

                    // CloudPrice — details (release / cutoff / deprecation dates,
                    // family / tier / version, tokenizer, modalities, limits).
                    if (cloudPriceRows.isNotEmpty()) {
                        item {
                            ModelInfoSection("CloudPrice details", "CloudPrice", onNavigateToHelpTopic) {
                                cloudPriceRows.forEach { (label, value) -> ModelInfoRow(label, value) }
                            }
                        }
                    }

                    // CloudPrice — full capability flags.
                    if (cloudPriceCaps.isNotEmpty()) {
                        item {
                            ModelInfoSection("CloudPrice capabilities", "CloudPrice", onNavigateToHelpTopic) {
                                cloudPriceCaps.forEach { (key, on) ->
                                    ModelInfoRow(key.replace('_', ' ').replaceFirstChar { it.uppercase() }, if (on) "Yes" else "No")
                                }
                            }
                        }
                    }

                    // Artificial Analysis — benchmark + speed scores.
                    aaMeta?.let { aa ->
                        if (aa.intelligenceIndex != null || aa.outputSpeed != null || aa.firstChunkSeconds != null || aa.modelCreator != null) {
                            item {
                                ModelInfoSection("Benchmarks", "Artificial Analysis", onNavigateToHelpTopic) {
                                    aa.intelligenceIndex?.let { ModelInfoRow("Intelligence Index", String.format(Locale.US, "%.1f", it)) }
                                    aa.outputSpeed?.let { ModelInfoRow("Output Speed", String.format(Locale.US, "%.1f tok/s", it)) }
                                    aa.firstChunkSeconds?.let { ModelInfoRow("Time to First Token", String.format(Locale.US, "%.2f s", it)) }
                                    aa.modelCreator?.let { ModelInfoRow("Creator", it) }
                                }
                            }
                        }
                    }

                    // llm-stats — per-category benchmark scores + modalities.
                    llmStatsMeta?.let { ls ->
                        val scores = ls.topScores
                        if (!scores.isNullOrEmpty() || !ls.modalities.isNullOrEmpty() || ls.organization != null) {
                            item {
                                ModelInfoSection("Benchmark scores", "llm-stats", onNavigateToHelpTopic) {
                                    ls.organization?.let { ModelInfoRow("Organization", it) }
                                    ls.modalities?.takeIf { it.isNotEmpty() }?.let { ModelInfoRow("Modalities", it.joinToString(", ")) }
                                    scores?.toSortedMap()?.forEach { (k, v) ->
                                        ModelInfoRow(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, String.format(Locale.US, "%.2f", v))
                                    }
                                }
                            }
                        }
                    }

                    // models.dev — capability flags + token limits.
                    modelsDevMeta?.let { md ->
                        item {
                            ModelInfoSection("models.dev", "models.dev", onNavigateToHelpTopic) {
                                md.supportsVision?.let { ModelInfoRow("Vision", if (it) "Yes" else "No") }
                                md.supportsToolCall?.let { ModelInfoRow("Tool calling", if (it) "Yes" else "No") }
                                md.supportsReasoning?.let { ModelInfoRow("Reasoning", if (it) "Yes" else "No") }
                                md.maxInputTokens?.let { ModelInfoRow("Context", formatCompactNumber(it.toLong())) }
                                md.maxOutputTokens?.let { ModelInfoRow("Max Output", formatCompactNumber(it.toLong())) }
                            }
                        }
                    }

                    // Requesty — capability flags + token limits.
                    requestyMeta?.let { rq ->
                        item {
                            ModelInfoSection("Requesty", "Requesty", onNavigateToHelpTopic) {
                                rq.supportsVision?.let { ModelInfoRow("Vision", if (it) "Yes" else "No") }
                                rq.supportsReasoning?.let { ModelInfoRow("Reasoning", if (it) "Yes" else "No") }
                                rq.supportsToolCalling?.let { ModelInfoRow("Tool calling", if (it) "Yes" else "No") }
                                rq.supportsWebSearch?.let { ModelInfoRow("Web search", if (it) "Yes" else "No") }
                                rq.supportsComputerUse?.let { ModelInfoRow("Computer use", if (it) "Yes" else "No") }
                                rq.maxInputTokens?.let { ModelInfoRow("Context", formatCompactNumber(it.toLong())) }
                                rq.maxOutputTokens?.let { ModelInfoRow("Max Output", formatCompactNumber(it.toLong())) }
                            }
                        }
                    }

                    // TrueFoundry — capability flags + token limits.
                    trueFoundryMeta?.let { tf ->
                        item {
                            ModelInfoSection("TrueFoundry", "TrueFoundry", onNavigateToHelpTopic) {
                                tf.supportsVision?.let { ModelInfoRow("Vision", if (it) "Yes" else "No") }
                                tf.supportsToolCalling?.let { ModelInfoRow("Tool calling", if (it) "Yes" else "No") }
                                tf.supportsReasoning?.let { ModelInfoRow("Reasoning", if (it) "Yes" else "No") }
                                tf.maxInputTokens?.let { ModelInfoRow("Context", formatCompactNumber(it.toLong())) }
                                tf.maxOutputTokens?.let { ModelInfoRow("Max Output", formatCompactNumber(it.toLong())) }
                            }
                        }
                    }

                    // LiteLLM — mode, endpoints, and feature flags.
                    liteLLMMeta?.let { ll ->
                        item {
                            ModelInfoSection("LiteLLM", "LiteLLM", onNavigateToHelpTopic) {
                                ll.mode?.let { ModelInfoRow("Mode", it) }
                                ll.supportedEndpoints?.takeIf { it.isNotEmpty() }?.let { ModelInfoRow("Endpoints", it.joinToString(", ")) }
                                ll.supportsVision?.let { ModelInfoRow("Vision", if (it) "Yes" else "No") }
                                ll.supportsWebSearch?.let { ModelInfoRow("Web search", if (it) "Yes" else "No") }
                                ll.supportsReasoning?.let { ModelInfoRow("Reasoning", if (it) "Yes" else "No") }
                                ll.supportsSystemMessages?.let { ModelInfoRow("System messages", if (it) "Yes" else "No") }
                                ll.supportsResponseSchema?.let { ModelInfoRow("Response schema", if (it) "Yes" else "No") }
                                ll.supportsNativeStreaming?.let { ModelInfoRow("Native streaming", if (it) "Yes" else "No") }
                                ll.toolUseSystemPromptTokens?.let { if (it > 0) ModelInfoRow("Tool-use overhead", "$it tok") }
                            }
                        }
                    }

                    // genai-prices — context window.
                    genaiPricesMeta?.maxInputTokens?.let { ctx ->
                        item {
                            ModelInfoSection("genai-prices", "genai-prices", onNavigateToHelpTopic) {
                                ModelInfoRow("Context Window", formatCompactNumber(ctx.toLong()))
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
                                    else -> OutlinedButton(
                                        onClick = requestIntroduction,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = AppColors.outlinedButtonColors()
                                    ) { Text("Ask the model to introduce itself", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                                }
                            }
                        }
                    }

                    // Last 10 usages of this model — chat sessions,
                    // reports, and per-report secondaries (translate /
                    // meta / rerank / moderate). Card is also shown
                    // when only the cumulative AI Usage counter has
                    // entries for this model (one-shot test calls /
                    // model refresh probes increment usage stats
                    // without persisting a chat or report). Pinned at
                    // the bottom of the screen — the model's
                    // catalog / source-of-truth metadata is what the
                    // user usually opens this screen to read; the
                    // usage history is reference information for
                    // when they're ready to dig into past activity.
                    if (recentUsages.isNotEmpty() || hasUsageStats) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Last usage", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
                                    val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US) }
                                    recentUsages.forEach { entry ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .let { if (entry.navigable) it.clickable { entry.onOpen() } else it }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                entry.typeLabel, fontSize = 12.sp, color = AppColors.WarningAccent,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                entry.title, fontSize = 13.sp, color = AppColors.TextPrimary,
                                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                dateFormat.format(java.util.Date(entry.timestamp)),
                                                fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    // Cumulative AI Usage counter — appended after
                                    // the specific events. Captures one-shot test
                                    // calls / model refreshes that bumped the
                                    // counter but didn't persist any session row.
                                    val ueRow = usageEntry
                                    if (hasUsageStats && ueRow != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "AI Usage", fontSize = 12.sp, color = AppColors.WarningAccent,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${ueRow.callCount} calls · ${ueRow.totalTokens} tokens",
                                                fontSize = 13.sp, color = AppColors.TextPrimary,
                                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
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
                        val hasLiteLLM = liteLLMRaw != null
                        val hasModelsDev = modelsDevRaw != null
                        val hasHelicone = heliconeRaw != null
                        val hasLLMPrices = llmPricesRaw != null
                        val hasAa = aaRaw != null
                        val hasRequesty = requestyRaw != null
                        val hasLlmStats = llmStatsRaw != null
                        val hasGenaiPrices = genaiPricesRaw != null
                        val hasTrueFoundry = trueFoundryRaw != null
                        val hasCloudPrice = cloudPriceRaw != null
                        // Info providers the user switched off are hidden, not
                        // shown as empty/red. (Their tier rows in Costs already
                        // drop out because the gated finders return null.)
                        val enHF = aiSettings.isInfoProviderEnabled("huggingface")
                        val enOR = aiSettings.isInfoProviderEnabled("openrouter")
                        val enLiteLLM = aiSettings.isInfoProviderEnabled("litellm")
                        val enModelsDev = aiSettings.isInfoProviderEnabled("modelsdev")
                        val enHelicone = aiSettings.isInfoProviderEnabled("helicone")
                        val enLLMPrices = aiSettings.isInfoProviderEnabled("llmprices")
                        val enAa = aiSettings.isInfoProviderEnabled("aa")
                        val enRequesty = aiSettings.isInfoProviderEnabled("requesty")
                        val enLlmStats = aiSettings.isInfoProviderEnabled("llmstats")
                        val enGenaiPrices = aiSettings.isInfoProviderEnabled("genaiprices")
                        val enTrueFoundry = aiSettings.isInfoProviderEnabled("truefoundry")
                        val enCloudPrice = aiSettings.isInfoProviderEnabled("cloudprice")
                        // Two rows of buttons in their own card — first the
                        // four catalog sources, then the three additional
                        // pricing tiers (Helicone / llm-prices.com / AA).
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Sources", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (enHF) Button(
                                        onClick = {
                                            val body = info?.huggingFaceInfo?.let { gson.toJson(it) } ?: "(no HuggingFace data)"
                                            rawView = RawView(
                                                title = "HuggingFace · $modelName", body = body,
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_huggingface"],
                                                calledUrl = "https://huggingface.co/api/models/${hfMatchedId ?: modelName}"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasHF) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("HuggingFace", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enOR) Button(
                                        onClick = {
                                            val body = info?.openRouterInfo?.let { gson.toJson(it) } ?: "(no OpenRouter data)"
                                            rawView = RawView(
                                                title = "OpenRouter · $modelName", body = body,
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_openrouter"],
                                                calledUrl = "https://openrouter.ai/api/v1/models/$modelName/endpoints"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasOR) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("OpenRouter", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enLiteLLM) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "LiteLLM · $modelName", body = liteLLMRaw ?: "(no LiteLLM data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_litellm"],
                                                calledUrl = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasLiteLLM) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("LiteLLM", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enModelsDev) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "models.dev · $modelName", body = modelsDevRaw ?: "(no models.dev data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_models_dev"],
                                                calledUrl = "https://models.dev/api.json"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasModelsDev) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("models.dev", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (enHelicone) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "Helicone · $modelName", body = heliconeRaw ?: "(no Helicone data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_helicone"],
                                                calledUrl = "https://www.helicone.ai/api/llm-costs"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasHelicone) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("Helicone", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enLLMPrices) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "llm-prices.com · $modelName", body = llmPricesRaw ?: "(no llm-prices data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_llm_prices"],
                                                calledUrl = "https://raw.githubusercontent.com/simonw/llm-prices/main/data/${provider.id.lowercase()}.json"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasLLMPrices) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("llm-prices", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enAa) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "Artificial Analysis · $modelName", body = aaRaw ?: "(no Artificial Analysis data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_artificial_analysis"],
                                                calledUrl = "https://artificialanalysis.ai/api/v2/data/llms/models"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasAa) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("Artificial Analysis", fontSize = 10.sp, maxLines = 1, softWrap = false) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (enRequesty) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "Requesty · $modelName", body = requestyRaw ?: "(no Requesty data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_requesty"],
                                                calledUrl = "https://router.requesty.ai/v1/models"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasRequesty) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("Requesty", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enLlmStats) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "llm-stats · $modelName", body = llmStatsRaw ?: "(no llm-stats data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_llm_stats"],
                                                calledUrl = "https://api.llm-stats.com/stats/v1/models"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasLlmStats) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("llm-stats", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (enGenaiPrices) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "genai-prices · $modelName", body = genaiPricesRaw ?: "(no genai-prices data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_genai_prices"],
                                                calledUrl = "https://raw.githubusercontent.com/pydantic/genai-prices/main/prices/data_slim.json"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasGenaiPrices) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("genai-prices", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enTrueFoundry) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "TrueFoundry · $modelName", body = trueFoundryRaw ?: "(no TrueFoundry data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_truefoundry"],
                                                calledUrl = "https://github.com/truefoundry/models"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasTrueFoundry) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("TrueFoundry", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    if (enCloudPrice) Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "CloudPrice · $modelName", body = cloudPriceRaw ?: "(no CloudPrice data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_cloudprice"],
                                                calledUrl = "https://ai.cloudprice.net/api/v1/models/$modelName"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasCloudPrice) AppColors.SuccessAccent else AppColors.DangerAccent),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("CloudPrice", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedButton(
                                    onClick = {
                                        // Concatenate every source's raw JSON into one
                                        // pretty-printed dump — saves tapping seven
                                        // buttons in turn when comparing entries.
                                        val sections = listOfNotNull(
                                            ("HuggingFace" to info?.huggingFaceInfo?.let { gson.toJson(it) }).takeIf { enHF },
                                            ("OpenRouter" to info?.openRouterInfo?.let { gson.toJson(it) }).takeIf { enOR },
                                            ("LiteLLM" to liteLLMRaw).takeIf { enLiteLLM },
                                            ("models.dev" to modelsDevRaw).takeIf { enModelsDev },
                                            ("Helicone" to heliconeRaw).takeIf { enHelicone },
                                            ("llm-prices.com" to llmPricesRaw).takeIf { enLLMPrices },
                                            ("Artificial Analysis" to aaRaw).takeIf { enAa },
                                            ("Requesty" to requestyRaw).takeIf { enRequesty },
                                            ("llm-stats" to llmStatsRaw).takeIf { enLlmStats },
                                            ("genai-prices" to genaiPricesRaw).takeIf { enGenaiPrices },
                                            ("TrueFoundry" to trueFoundryRaw).takeIf { enTrueFoundry },
                                            ("CloudPrice" to cloudPriceRaw).takeIf { enCloudPrice }
                                        )
                                        val body = sections.joinToString("\n\n") { (label, raw) ->
                                            "=== $label ===\n${raw ?: "(no $label data)"}"
                                        }
                                        rawView = RawView(title = "All sources · $modelName", body = body)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = AppColors.outlinedButtonColors()
                                ) { Text("Show all", fontSize = 13.sp, maxLines = 1, softWrap = false) }
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
    source: String?,
    onNavigateToHelpTopic: (String) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.InfoAccent)
            content()
            if (source != null) {
                val ref = com.ai.ui.admin.infoProviderForDisplayName(source)
                if (ref != null) {
                    // Linked footer: full label is one tappable Text so
                    // the entire "Source: …" line is the affordance.
                    Text(
                        "Source: $source",
                        fontSize = 10.sp, color = AppColors.InfoAccent,
                        modifier = Modifier.padding(top = 4.dp).clickable { onNavigateToHelpTopic(ref.topicId) }
                    )
                } else {
                    Text("Source: $source", fontSize = 10.sp, color = AppColors.TextDim,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** Render a display name as a help-topic link when it matches one of
 *  the seven info providers, or a plain Text otherwise. Used by the
 *  Cost breakdown rows + Capabilities source labels so every place
 *  the user sees a provider name can drill into the matching help. */
@Composable
private fun InfoProviderName(
    name: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    plainColor: Color,
    onNavigateToHelpTopic: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ref = com.ai.ui.admin.infoProviderForDisplayName(name)
    if (ref != null) {
        Text(
            name, fontSize = fontSize, color = AppColors.InfoAccent,
            modifier = modifier.clickable { onNavigateToHelpTopic(ref.topicId) }
        )
    } else {
        Text(name, fontSize = fontSize, color = plainColor, modifier = modifier)
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(mi.iconizedText(label), fontSize = 13.sp, color = AppColors.TextTertiary)
        Text(mi.iconizedText(value), fontSize = 13.sp, color = AppColors.TextPrimary)
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
    /** Set for the seven info-provider Source buttons. Triggers the
     *  restructured layout: fixed "Info provider" title bar with the
     *  provider's help topic wired to the ❓ icon, green provider
     *  name + dim called-URL line above the JSON card. The "Show
     *  all" combined view leaves this null and falls back to the
     *  legacy title-only shape. */
    provider: com.ai.ui.admin.InfoProviderRef? = null,
    calledUrl: String? = null,
    onNavigateToHelpTopic: (String) -> Unit = {},
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val annotated = remember(body) { colorizeJson(body) }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            // ❓ describes THIS screen ("Raw catalog data" / Source
            // detail layout). The convention from the home-help icon
            // legend: ❓ = help for the current screen, ℹ️ = drill
            // into a details target (here, the per-provider help).
            helpTopic = "model_raw",
            title = if (provider != null) "Info provider" else title,
            subject = provider?.displayName,
            onBackClick = onBack,
            // ℹ️ → per-provider help page (LiteLLM, OpenRouter, …).
            onInfo = if (provider != null) {
                { onNavigateToHelpTopic(provider.topicId) }
            } else null,
            onCopy = body.takeIf { it.isNotBlank() }?.let {
                { com.ai.ui.shared.copyToClipboard(context, body, "raw catalog JSON") }
            },
            onShare = body.takeIf { it.isNotBlank() }?.let {
                { com.ai.ui.shared.shareText(context, body, "Model info — ${provider?.displayName ?: title}") }
            }
        )
        if (provider != null) {
            calledUrl?.let { url ->
                Text(
                    url,
                    fontSize = 11.sp, color = AppColors.TextPrimary, fontFamily = FontFamily.Monospace,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )
            }
        }
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
    val keyStyle = SpanStyle(color = AppColors.InfoAccent)
    val stringStyle = SpanStyle(color = AppColors.SuccessAccent)
    val numStyle = SpanStyle(color = AppColors.WarningAccent)
    val boolStyle = SpanStyle(color = AppColors.PrimaryAccent)
    val nullStyle = SpanStyle(color = AppColors.TextTertiary)
    val punctStyle = SpanStyle(color = AppColors.TextPrimary)
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
                c == 't' && i + 4 <= n && json.regionMatches(i, "true", 0, 4) && jsonLiteralBoundary(json, i + 4) -> {
                    withStyle(boolStyle) { append("true") }; i += 4
                }
                c == 'f' && i + 5 <= n && json.regionMatches(i, "false", 0, 5) && jsonLiteralBoundary(json, i + 5) -> {
                    withStyle(boolStyle) { append("false") }; i += 5
                }
                c == 'n' && i + 4 <= n && json.regionMatches(i, "null", 0, 4) && jsonLiteralBoundary(json, i + 4) -> {
                    withStyle(nullStyle) { append("null") }; i += 4
                }
                else -> {
                    withStyle(punctStyle) { append(c) }; i++
                }
            }
        }
    }
}

private fun jsonLiteralBoundary(json: String, index: Int): Boolean =
    index >= json.length || !json[index].isLetterOrDigit()

/** One clickable row inside the "Model in AI configuration" /
 *  "Workers" cards on Model Info. Label on the left (blue, fixed
 *  width), value on the right (white, ellipsised). Drops a trailing
 *  ›  hint so the user knows the row navigates. */
@Composable
private fun ModelConfigRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label:", fontSize = 12.sp, color = AppColors.InfoAccent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(128.dp)
        )
        Text(
            value, fontSize = 13.sp, color = AppColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("›", fontSize = 14.sp, color = AppColors.TextTertiary)
    }
}
