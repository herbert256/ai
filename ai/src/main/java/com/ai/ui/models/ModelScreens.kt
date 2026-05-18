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

/** One row in the "Last usage" card on the Model Info screen.
 *  Aggregated from chat history, reports, and per-report secondary
 *  results so the user sees every concrete place this (provider,
 *  model) pair has been used recently. */
private data class ModelUsageEntry(
    val timestamp: Long,
    val typeLabel: String,
    val title: String,
    val onOpen: () -> Unit
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
    ChatHistoryManager.getAllSessions().forEach { s ->
        if (s.provider.id == provider.id && s.model == model) {
            out += ModelUsageEntry(
                timestamp = s.updatedAt, typeLabel = "Chat", title = chatTitle(s),
                onOpen = {} // chat session deep-link not supported; row is informational
            )
        }
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
    for (report in reports) {
        if (out.size >= candidateCap) break
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
            val providerHost = runCatching {
                java.net.URI(provider.baseUrl).host?.lowercase()
            }.getOrNull()
            ApiTracer.getTraceFiles().count { tf ->
                tf.model == modelName &&
                    (providerHost == null || tf.hostname.equals(providerHost, ignoreCase = true))
            }
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
                val models = ModelInfoCache.getOpenRouterModels(openRouterApiKey)
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
    // the "Model info" name. Falls back to empty when the user has
    // deleted that entry (the next app start will re-seed it from
    // assets/prompts.json). Default AgentParameters — there's no
    // longer an agent binding to inherit temperature / max_tokens
    // preset from.
    val modelInfoPromptTemplate = remember(aiSettings) {
        aiSettings.getInternalPromptByName("Model info")?.text.orEmpty()
    }
    val pageApiKey = aiSettings.getApiKey(provider)
    val introResolvedPrompt = remember(modelInfoPromptTemplate, provider, modelName) {
        modelInfoPromptTemplate
            .replace("@MODEL@", modelName)
            .replace("@PROVIDER@", provider.id)
            .replace("@AGENT@", "${provider.id} / $modelName")
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "model_info",
            title = "Model Info",
            subject = modelName,
            onBackClick = onNavigateBack,
            onOpenView = onOpenView,
            onTrace = if (ApiTracer.isTracingEnabled && traceCount > 0) {
                { onNavigateToTracesForModel(provider, modelName) }
            } else null
        )
        com.ai.ui.shared.HardcodedSubjectRow(modelName)

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
                val hasCostOverride = remember(provider, modelName) {
                    PricingCache.getManualPricing(context, provider, modelName) != null
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
                                    if (isProviderActive) {
                                        Button(
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
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                                        ) { Text(if (testRunning) "Testing…" else "Test", maxLines = 1, softWrap = false) }
                                    }
                                }
                                testResult?.let { r ->
                                    val passed = testPassed == true
                                    Text(
                                        text = if (passed) "✅ $r" else "❌ $r",
                                        fontSize = 12.sp,
                                        color = if (passed) AppColors.Green else AppColors.Red
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
                                        color = AppColors.Blue
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
                                        color = AppColors.Blue
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
                                            rawView = RawView(
                                                title = "HuggingFace · $modelName", body = body,
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_huggingface"],
                                                calledUrl = "https://huggingface.co/api/models/$modelName"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasHF) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("HuggingFace", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            val body = info?.openRouterInfo?.let { gson.toJson(it) } ?: "(no OpenRouter data)"
                                            rawView = RawView(
                                                title = "OpenRouter · $modelName", body = body,
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_openrouter"],
                                                calledUrl = "https://openrouter.ai/api/v1/models/$modelName/endpoints"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasOR) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("OpenRouter", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "LiteLLM · $modelName", body = liteLLMRaw ?: "(no LiteLLM data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_litellm"],
                                                calledUrl = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasLiteLLM) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("LiteLLM", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "models.dev · $modelName", body = modelsDevRaw ?: "(no models.dev data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_models_dev"],
                                                calledUrl = "https://models.dev/api.json"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasModelsDev) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("models.dev", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "Helicone · $modelName", body = heliconeRaw ?: "(no Helicone data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_helicone"],
                                                calledUrl = "https://www.helicone.ai/api/llm-costs"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasHelicone) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("Helicone", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "llm-prices.com · $modelName", body = llmPricesRaw ?: "(no llm-prices data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_llm_prices"],
                                                calledUrl = "https://raw.githubusercontent.com/simonw/llm-prices/main/data/${provider.id.lowercase()}.json"
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (hasLLMPrices) AppColors.Green else AppColors.Red),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                    ) { Text("llm-prices", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                                    Button(
                                        onClick = {
                                            rawView = RawView(
                                                title = "Artificial Analysis · $modelName", body = aaRaw ?: "(no Artificial Analysis data)",
                                                provider = com.ai.ui.admin.INFO_PROVIDERS_BY_TOPIC["info_provider_artificial_analysis"],
                                                calledUrl = "https://artificialanalysis.ai/api/v2/data/llms/models"
                                            )
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
                                        rawView = RawView(title = "All sources · $modelName", body = body)
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
                                            // Provider name → help topic when it
                                            // resolves to one of the seven info
                                            // providers ("Override" stays plain).
                                            InfoProviderName(
                                                name = label,
                                                fontSize = 13.sp,
                                                plainColor = Color.White,
                                                onNavigateToHelpTopic = onNavigateToHelpTopic,
                                                modifier = Modifier.weight(1f)
                                            )
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
                                    InfoProviderName(
                                        name = visionSrcText, fontSize = 12.sp,
                                        plainColor = AppColors.TextTertiary,
                                        onNavigateToHelpTopic = onNavigateToHelpTopic
                                    )
                                }
                                Row {
                                    Text(webLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                    InfoProviderName(
                                        name = webSrcText, fontSize = 12.sp,
                                        plainColor = AppColors.TextTertiary,
                                        onNavigateToHelpTopic = onNavigateToHelpTopic
                                    )
                                }
                                Row {
                                    Text(reasoningLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
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
                                // Provider-recommended sampling defaults
                                // (Mistral default_model_temperature, Together
                                // config.stop). Shown so the user can see what
                                // the upstream considers neutral before
                                // diverging in a Parameters preset.
                                cfg.modelCapabilities[modelName]?.defaultTemperature?.let { t ->
                                    Row {
                                        Text("Default temperature: $t", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                        Text("Provider", fontSize = 12.sp, color = AppColors.TextTertiary)
                                    }
                                }
                                cfg.modelCapabilities[modelName]?.defaultStopSequences?.takeIf { it.isNotEmpty() }?.let { stops ->
                                    Row {
                                        Text("Default stops: ${stops.joinToString(", ")}",
                                            fontSize = 13.sp, color = Color.White,
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
                                Button(
                                    onClick = { onNavigateToAddManualOverride(provider, modelName) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
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
                                Text("Provider", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                Text(provider.id, fontSize = 14.sp, color = Color.White)
                            }
                        }
                    }

                    // The dedicated "API Traces" card is gone — the
                    // title-bar 🐞 icon opens the same model-filtered
                    // trace list when traceCount > 0.

                    // Usage entry for this provider/model (cumulative across reports + chats).
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("AI Usage", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                val ue = usageEntry
                                if (ue == null) {
                                    Text("No usage recorded yet for this model", fontSize = 12.sp, color = AppColors.TextTertiary)
                                } else {
                                    Text(
                                        "${ue.callCount} calls, ${formatCompactNumber(ue.inputTokens)} in / ${formatCompactNumber(ue.outputTokens)} out",
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
                            ModelInfoSection("Description", "OpenRouter", onNavigateToHelpTopic) {
                                Text(desc, fontSize = 13.sp, color = Color(0xFFCCCCCC))
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
                                or.expiration_date?.let { ModelInfoRow("⚠ Expires", it) }
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
                                    Text("Last usage", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                                    val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US) }
                                    recentUsages.forEach { entry ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable { entry.onOpen() }.padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                entry.typeLabel, fontSize = 12.sp, color = AppColors.Orange,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                entry.title, fontSize = 13.sp, color = Color.White,
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
                                                "AI Usage", fontSize = 12.sp, color = AppColors.Orange,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${ueRow.callCount} calls · ${ueRow.totalTokens} tokens",
                                                fontSize = 13.sp, color = Color.White,
                                                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
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
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            content()
            if (source != null) {
                val ref = com.ai.ui.admin.infoProviderForDisplayName(source)
                if (ref != null) {
                    // Linked footer: full label is one tappable Text so
                    // the entire "Source: …" line is the affordance.
                    Text(
                        "Source: $source",
                        fontSize = 10.sp, color = AppColors.Blue,
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
            name, fontSize = fontSize, color = AppColors.Blue,
            modifier = modifier.clickable { onNavigateToHelpTopic(ref.topicId) }
        )
    } else {
        Text(name, fontSize = fontSize, color = plainColor, modifier = modifier)
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
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
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
            com.ai.ui.shared.HardcodedSubjectRow(provider.displayName)
        }
        if (provider != null) {
            calledUrl?.let { url ->
                Text(
                    url,
                    fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace,
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
            "$label:", fontSize = 12.sp, color = AppColors.Blue,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(128.dp)
        )
        Text(
            value, fontSize = 13.sp, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("›", fontSize = 14.sp, color = AppColors.TextTertiary)
    }
}
