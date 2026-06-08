package com.ai.data

import android.content.Context
import com.ai.model.Settings
import com.ai.model.UsageCategoryStats
import com.ai.model.UsageReportStats
import com.ai.ui.admin.ProviderCostGroup
import com.ai.ui.admin.buildProviderCostGroups
import com.ai.ui.hub.reportIsRunning
import com.ai.data.preferences.SettingsPreferences
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.providerHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Report + secondary-result lifetime totals — the "Reports" stats screen.
 *  Heavy (one report scan + a secondary read per report). */
internal data class ReportSectionData(
    val reports: ReportStats,
    val secondaries: Map<SecondaryKind, Int>,
    val metaByName: Map<String, Int>,
    // Agent-call status breakdown (erroredCalls/stopped live on ReportStats).
    val agentSuccess: Int,
    val agentPending: Int,                 // PENDING + RUNNING
    // Tokens & compute across all agent calls.
    val inputTokens: Long,
    val outputTokens: Long,
    val totalDurationMs: Long,
    // Secondary spend / tokens.
    val secondaryCost: Double,
    val secondaryTokens: Long,
    // Report-level feature usage.
    val pinned: Int,
    val withImage: Int,
    val withWebSearch: Int,
    val withReasoning: Int,
    val withKnowledge: Int,
    val translated: Int,
    val tableReports: Int,                 // ReportType.TABLE
    // Activity over time (by createdAt, falling back to timestamp).
    val createdToday: Int,
    val created7d: Int,
    val created30d: Int,
    val oldestCreatedAt: Long?,
    // Leaderboards (call counts), top 6.
    val topModels: List<Pair<String, Int>>,
    val topProviders: List<Pair<String, Int>>,
)

/** One provider's row on the "Providers / Models" screen. */
internal data class ProviderRow(
    val id: String,
    val active: Boolean,
    val format: String,                  // ApiFormat.name
    val host: String,
    val defaultModel: String,
    val hasKey: Boolean,
    val models: Int,
    val vision: Int,
    val webSearch: Int,
    val reasoning: Int,
    val embedding: Int,
    val blocked: Int,
    val inaccessible: Int,
    val testExcluded: Int,
    val cooling: Int,
    val cacheAgeMs: Long?,               // null = never fetched
    val concCap: Int?,                   // per-provider override (null = inherits)
    val perMinCap: Int?,
    val modelsByType: Map<String, Int>,
    val testPassed: Int,
    val testFailed: Int,
)

/** Summary of the last persisted "Test all models" run. */
internal data class TestRunSummary(
    val forTesting: Int, val passed: Int, val failed: Int, val cost: Double, val startedAt: Long,
)

/** Provider / model fleet stats — the "Providers / Models" screen. All
 *  in-memory or a cheap per-provider file-stat; no getPricing / network. */
internal data class ProviderModelData(
    val providersConfigured: Int,
    val providersActive: Int,
    val providersWithKey: Int,
    val totalModels: Int,
    val byFormat: Map<String, Int>,      // ApiFormat.name -> provider count
    val modelsByType: Map<String, Int>,
    val vision: Int,
    val webSearch: Int,
    val reasoning: Int,
    val embedding: Int,
    val blocked: Int,
    val inaccessible: Int,
    val testExcluded: Int,
    val cooling: Int,
    val cached: Int,
    val stale: Int,
    val neverCached: Int,
    val agents: Int,
    val flocks: Int,
    val swarms: Int,
    val lastTest: TestRunSummary?,
    val providers: List<ProviderRow>,    // active first, then model count desc
    // Model-capability extras (from each provider's /models metadata).
    val fnCalling: Int,
    val pdfInput: Int,
    val reasoningLevels: Int,
    val deprecated: Int,
    val withCaps: Int,
    val contextBuckets: Map<String, Int>,
    val maxContextModel: String?,
    val maxContextTokens: Int,
)

/** Knowledge-base totals — shown on the Monitor hub. */
internal data class KnowledgeData(
    val kbCount: Int,
    val kbChunks: Int,
    val kbChars: Long,
    val kbSourcesByType: Map<KnowledgeSourceType, Int>,
    val kbFailed: Int,
)

/** Spend & usage breakdown — heavy enough (per-model getPricing) that it lives
 *  on its own screen, computed only when opened. */
internal data class UsageGroupsResult(
    val groups: List<ProviderCostGroup>,
    val typeGroups: List<UsageTypeGroup>,
    val reportRows: List<UsageReportRow>,
    val totalCalls: Int,
    val totalTokens: Long,
    val totalCost: Double,
    val pricingStats: String,
)

internal data class UsageTypeGroup(
    val category: String,
    val calls: Int,
    val tokens: Long,
    val searchUnits: Long,
    val totalCost: Double,
)

internal data class UsageReportRow(
    val reportId: String,
    val title: String,
    val calls: Int,
    val tokens: Long,
    val totalCost: Double,
    val timestamp: Long,
)

/** Spend & usage for the dedicated screen — reuses the AI Usage cost-grouping.
 *  Off the main thread (per-model getPricing). */
internal suspend fun computeUsageGroups(
    context: Context,
    settingsPrefs: SettingsPreferences,
): UsageGroupsResult = withContext(Dispatchers.IO) {
    settingsPrefs.reconcileReportCostLedgers(context)
    val stats = settingsPrefs.loadUsageStats()
    val groups = buildProviderCostGroups(context, stats)
    val categoryStats = settingsPrefs.loadUsageCategoryStats()
    val reportStats = settingsPrefs.loadUsageReportStats(context)
    val typeGroups = if (categoryStats.isNotEmpty()) {
        buildUsageTypeGroups(categoryStats)
    } else {
        buildUsageTypeGroups(groups)
    }
    val reportRows = buildUsageReportRows(context, reportStats)
    UsageGroupsResult(
        groups = groups,
        typeGroups = typeGroups,
        reportRows = reportRows,
        totalCalls = stats.values.sumOf { it.callCount },
        totalTokens = stats.values.sumOf { it.totalTokens },
        totalCost = groups.sumOf { it.totalCost },
        pricingStats = PricingCache.getPricingStats(context),
    )
}

private fun buildUsageTypeGroups(stats: Map<String, UsageCategoryStats>): List<UsageTypeGroup> =
    stats.values.map { row ->
        UsageTypeGroup(
            category = row.category,
            calls = row.callCount,
            tokens = row.totalTokens,
            searchUnits = row.searchUnits,
            totalCost = row.totalCost,
        )
    }.sortedByDescending { it.totalCost }

private fun buildUsageTypeGroups(groups: List<ProviderCostGroup>): List<UsageTypeGroup> =
    groups.flatMap { it.models }
        .groupBy { it.stat.kind }
        .map { (category, rows) ->
            UsageTypeGroup(
                category = category,
                calls = rows.sumOf { it.stat.callCount },
                tokens = rows.sumOf { it.stat.totalTokens },
                searchUnits = rows.sumOf { it.stat.searchUnits },
                totalCost = rows.sumOf { it.totalCost },
            )
        }
        .sortedByDescending { it.totalCost }

private fun buildUsageReportRows(context: Context, stats: Map<String, UsageReportStats>): List<UsageReportRow> =
    stats.values.filter { row -> ReportStorage.reportLastModified(context, row.reportId) > 0L }.map { row ->
        UsageReportRow(
            reportId = row.reportId,
            title = row.title,
            calls = row.callCount,
            tokens = row.totalTokens,
            totalCost = row.totalCost,
            timestamp = row.timestamp,
        )
    }.sortedByDescending { it.totalCost }

private data class ReportUsageTotals(
    val calls: Int,
    val tokens: Long,
    val cost: Double,
)

private fun summarizeReportUsage(
    context: Context,
    report: Report,
    secondaries: List<SecondaryResult>,
): ReportUsageTotals {
    if (ReportStorage.isApiCallCostLedgerCurrent(report) && report.apiCallCosts.isNotEmpty()) {
        return ReportUsageTotals(
            calls = report.apiCallCosts.size,
            tokens = report.apiCallCosts.sumOf { (it.inputTokens + it.outputTokens).toLong() },
            cost = report.apiCallCosts.sumOf { it.inputCost + it.outputCost },
        )
    }

    var calls = 0
    var tokens = 0L
    var cost = 0.0

    fun addCall(inputTokens: Int, outputTokens: Int, rowCost: Double) {
        calls += 1
        tokens += (inputTokens + outputTokens).toLong()
        cost += rowCost.coerceAtLeast(0.0)
    }

    fun addCostRowIfPresent(
        hasCost: Boolean,
        inputTokens: Int,
        outputTokens: Int,
        rowCost: Double,
    ) {
        if (hasCost) addCall(inputTokens, outputTokens, rowCost)
    }

    val iconCalls = report.iconCalls
    val altByType: Map<String, Pair<Double, Double>> = iconCalls
        .filter { !it.type.isNullOrBlank() }
        .groupBy { it.type!! }
        .mapValues { (_, list) -> list.sumOf { it.inputCost } to list.sumOf { it.outputCost } }
    val altTokensByType: Map<String, Pair<Int, Int>> = iconCalls
        .filter { !it.type.isNullOrBlank() }
        .groupBy { it.type!! }
        .mapValues { (_, list) -> list.sumOf { it.inputTokens } to list.sumOf { it.outputTokens } }
    val altBySecondary: Map<String, Pair<Double, Double>> = iconCalls
        .filter { !it.attributedToSecondaryId.isNullOrBlank() }
        .groupBy { it.attributedToSecondaryId!! }
        .mapValues { (_, list) -> list.sumOf { it.inputCost } to list.sumOf { it.outputCost } }
    val altTokensBySecondary: Map<String, Pair<Int, Int>> = iconCalls
        .filter { !it.attributedToSecondaryId.isNullOrBlank() }
        .groupBy { it.attributedToSecondaryId!! }
        .mapValues { (_, list) -> list.sumOf { it.inputTokens } to list.sumOf { it.outputTokens } }

    report.agents
        .filter { it.tokenUsage != null && (it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR) }
        .forEach { agent ->
            val tu = agent.tokenUsage ?: return@forEach
            val persisted = agent.inputCost != null || agent.outputCost != null
            val rowCost = if (persisted) {
                (agent.inputCost ?: 0.0) + (agent.outputCost ?: 0.0)
            } else {
                val provider = AppService.findById(agent.provider)
                val computed = provider?.let {
                    PricingCache.computeCost(tu, PricingCache.getPricing(context, it, agent.model))
                }
                computed ?: agent.cost ?: 0.0
            }
            addCall(tu.inputTokens, tu.outputTokens, rowCost)
        }

    val mainAltCost = (altByType["alt/main"]?.let { it.first + it.second } ?: 0.0)
    val mainAltTokens = altTokensByType["alt/main"] ?: (0 to 0)
    addCostRowIfPresent(
        report.iconInputCost > 0.0 || report.iconOutputCost > 0.0,
        (report.iconInputTokens - mainAltTokens.first).coerceAtLeast(0),
        (report.iconOutputTokens - mainAltTokens.second).coerceAtLeast(0),
        (report.iconInputCost + report.iconOutputCost - mainAltCost).coerceAtLeast(0.0),
    )

    addCostRowIfPresent(
        report.languageInputCost > 0.0 || report.languageOutputCost > 0.0,
        report.languageInputTokens,
        report.languageOutputTokens,
        report.languageInputCost + report.languageOutputCost,
    )

    val languageAltCost = (altByType["alt/language"]?.let { it.first + it.second } ?: 0.0)
    val languageAltTokens = altTokensByType["alt/language"] ?: (0 to 0)
    addCostRowIfPresent(
        report.languageIconInputCost > 0.0 || report.languageIconOutputCost > 0.0,
        (report.languageIconInputTokens - languageAltTokens.first).coerceAtLeast(0),
        (report.languageIconOutputTokens - languageAltTokens.second).coerceAtLeast(0),
        (report.languageIconInputCost + report.languageIconOutputCost - languageAltCost).coerceAtLeast(0.0),
    )

    addCostRowIfPresent(
        report.titleInputCost > 0.0 || report.titleOutputCost > 0.0,
        report.titleInputTokens,
        report.titleOutputTokens,
        report.titleInputCost + report.titleOutputCost,
    )
    addCostRowIfPresent(
        report.titleLongInputCost > 0.0 || report.titleLongOutputCost > 0.0,
        report.titleLongInputTokens,
        report.titleLongOutputTokens,
        report.titleLongInputCost + report.titleLongOutputCost,
    )
    report.agents.forEach { agent ->
        addCostRowIfPresent(
            agent.modelTitleInputCost > 0.0 || agent.modelTitleOutputCost > 0.0,
            agent.modelTitleInputTokens,
            agent.modelTitleOutputTokens,
            agent.modelTitleInputCost + agent.modelTitleOutputCost,
        )
    }

    iconCalls.forEach {
        addCall(it.inputTokens, it.outputTokens, it.inputCost + it.outputCost)
    }

    secondaries.forEach { secondary ->
        val tu = secondary.tokenUsage
        if (tu != null) {
            val srAlt = altBySecondary[secondary.id]
            val srAltTokens = altTokensBySecondary[secondary.id]
            addCall(
                (tu.inputTokens - (srAltTokens?.first ?: 0)).coerceAtLeast(0),
                (tu.outputTokens - (srAltTokens?.second ?: 0)).coerceAtLeast(0),
                ((secondary.inputCost ?: 0.0) + (secondary.outputCost ?: 0.0) -
                    (srAlt?.let { it.first + it.second } ?: 0.0)).coerceAtLeast(0.0),
            )
        }
        if (secondary.fanOutSourceAgentId != null && secondary.fanInOf == null &&
            (secondary.titleInputCost > 0.0 || secondary.titleOutputCost > 0.0)
        ) {
            addCall(
                secondary.titleInputTokens,
                secondary.titleOutputTokens,
                secondary.titleInputCost + secondary.titleOutputCost,
            )
        }
    }

    cost += report.costsFromDeletedItems.coerceAtLeast(0.0)
    return ReportUsageTotals(calls = calls, tokens = tokens, cost = cost)
}

/** Per-model pricing-tier resolution for the dedicated "Costs tier" screen:
 *  which tier [PricingCache.getPricing] would pick for every configured model,
 *  counted by [PricingCache.ModelPricing.source] in [PRICING_TIER_ORDER] (so
 *  every tier shows even at zero). Heavy (getPricing per model) — own screen,
 *  computed only when opened. */
internal suspend fun computeTierCounts(
    context: Context,
    aiSettings: Settings,
): Map<String, Int> = withContext(Dispatchers.IO) {
    val tierCounts = LinkedHashMap<String, Int>().apply { PRICING_TIER_ORDER.forEach { put(it, 0) } }
    for (p in ProviderRegistry.getAll()) {
        val reportsCost = p.reportsCost()
        for (m in aiSettings.getProvider(p).models) {
            if (m.isBlank()) continue
            val src = if (reportsCost) "API_REPORTED" else PricingCache.getPricing(context, p, m).source
            tierCounts[src] = (tierCounts[src] ?: 0) + 1
        }
    }
    tierCounts
}

/** "Runtime" sibling of [computeTierCounts]: instead of the configured
 *  catalog, resolve the tier for each DISTINCT (provider, model) pair that was
 *  actually called — read from the API traces ([ApiTracer.getTraceFiles], which
 *  carry hostname + model). Host → provider via [ProviderRegistry.findByHost];
 *  pairs whose host maps to no registered provider, or with no model recorded,
 *  are skipped. Counts each used model once, so it mirrors the configuration
 *  view but over what really happened. */
internal suspend fun computeTierCountsRuntime(
    context: Context,
): Map<String, Int> = withContext(Dispatchers.IO) {
    val tierCounts = LinkedHashMap<String, Int>().apply { PRICING_TIER_ORDER.forEach { put(it, 0) } }
    val seen = HashSet<String>()
    for (t in ApiTracer.getTraceFiles()) {
        val model = t.model?.takeIf { it.isNotBlank() } ?: continue
        val provider = ProviderRegistry.findByHost(t.hostname) ?: continue
        if (!seen.add("${provider.id}:$model")) continue
        val src = if (provider.reportsCost()) "API_REPORTED"
                  else PricingCache.getPricing(context, provider, model).source
        tierCounts[src] = (tierCounts[src] ?: 0) + 1
    }
    tierCounts
}

/** Buckets for the "Costs tiers" card, in display order. The first,
 *  "API_REPORTED", is synthetic: models whose provider ships the cost in the
 *  response ([AppService.extractApiCost] / [AppService.costTicksDivisor]) are
 *  counted there instead of resolving a tier, since their real cost never comes
 *  from the local pricing lookup. The rest are [PricingCache.ModelPricing.source]
 *  tags as the catalog parsers set them (NOT getPricing's log labels). Note:
 *  OpenRouter self-report and cross-provider both tag "OPENROUTER", and Together
 *  self-report tags "TOGETHER" — the source can't tell self from fallback. */
internal val PRICING_TIER_ORDER = listOf(
    "API_REPORTED",
    "OVERRIDE", "LITELLM", "MODELSDEV", "LLMPRICES", "ARTIFICIALANALYSIS",
    "OPENROUTER", "TOGETHER", "HELICONE", "DEFAULT"
)

/** True when [provider] reports the per-call cost in its response, so the cost
 *  is taken straight off the body rather than from a pricing tier. */
private fun AppService.reportsCost(): Boolean = extractApiCost || costTicksDivisor != null

internal data class ReportStats(
    val total: Int,
    val running: Int,
    val problems: Int,
    val completed: Int,
    val agentCalls: Int,
    val erroredCalls: Int,
    val stopped: Int,
    val spend: Double,
)

private const val MODEL_CACHE_STALE_MS = 7L * 24 * 60 * 60 * 1000

/** Reports + secondaries for the "Statistics - Reports" screen. Reuses the
 *  hub's running/problems predicates so the numbers match the AI Reports hub.
 *  Disk-heavy: one report scan + a secondary read per report. */
internal suspend fun computeReportStats(
    context: Context,
    translationRuns: Map<String, TranslationRunState>,
    // The reports the Broken-work scan flagged (appViewModel.brokenBatches,
    // grouped by reportId). The "problems" stat counts these so it matches
    // the hub card and the top-bar ⚠️ exactly — one routine, every surface.
    problemReportIds: Set<String>,
): ReportSectionData = withContext(Dispatchers.IO) {
    val all = ReportStorage.getAllReports(context)

    val activeTranslationReportIds = translationRuns.values
        .filter { !it.isFinished && !it.cancelled }
        .map { it.sourceReportId }
        .toSet()
    val running = all.filter { reportIsRunning(it, activeTranslationReportIds) }
    val problems = all.count { it.id in problemReportIds }

    // One secondary read per report feeds the by-kind counts + cost/tokens.
    val secByKind = linkedMapOf(
        SecondaryKind.RERANK to 0, SecondaryKind.META to 0,
        SecondaryKind.MODERATION to 0, SecondaryKind.TRANSLATE to 0
    )
    val metaByName = HashMap<String, Int>()
    var secondaryCost = 0.0
    var secondaryTokens = 0L
    for (r in all) {
        val secs = SecondaryResultStorage.listForReport(context, r.id)
        for (s in secs) {
            secByKind[s.kind] = (secByKind[s.kind] ?: 0) + 1
            if (s.kind == SecondaryKind.META) {
                val name = s.metaPromptName?.takeIf { it.isNotBlank() } ?: "Meta"
                metaByName[name] = (metaByName[name] ?: 0) + 1
            }
            secondaryCost += (s.inputCost ?: 0.0) + (s.outputCost ?: 0.0)
            s.tokenUsage?.let { secondaryTokens += it.totalTokens.toLong() }
        }
    }

    // Agent-call rollups (status, tokens, compute, leaderboards) + report-level
    // feature usage + activity-over-time, all in one in-memory pass.
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    var agentCalls = 0; var errored = 0; var stopped = 0; var success = 0; var pending = 0
    var inputTokens = 0L; var outputTokens = 0L; var totalDurationMs = 0L
    val modelCalls = HashMap<String, Int>()
    val providerCalls = HashMap<String, Int>()
    for (r in all) for (a in r.agents) {
        agentCalls++
        when (a.reportStatus) {
            ReportStatus.ERROR -> errored++
            ReportStatus.STOPPED -> stopped++
            ReportStatus.SUCCESS -> success++
            ReportStatus.PENDING, ReportStatus.RUNNING -> pending++
        }
        a.tokenUsage?.let { inputTokens += it.inputTokens.toLong(); outputTokens += it.outputTokens.toLong() }
        a.durationMs?.let { totalDurationMs += it }
        if (a.model.isNotBlank()) modelCalls[a.model] = (modelCalls[a.model] ?: 0) + 1
        if (a.provider.isNotBlank()) providerCalls[a.provider] = (providerCalls[a.provider] ?: 0) + 1
    }
    fun createdAtOf(r: Report) = if (r.createdAt > 0L) r.createdAt else r.timestamp

    val reportStats = ReportStats(
        total = all.size,
        running = running.size,
        problems = problems,
        completed = all.count { it.completedAt != null },
        agentCalls = agentCalls,
        erroredCalls = errored,
        stopped = stopped,
        spend = all.sumOf { it.totalCost },
    )
    ReportSectionData(
        reports = reportStats,
        secondaries = secByKind,
        metaByName = metaByName.entries.sortedByDescending { it.value }.associate { it.key to it.value },
        agentSuccess = success,
        agentPending = pending,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalDurationMs = totalDurationMs,
        secondaryCost = secondaryCost,
        secondaryTokens = secondaryTokens,
        pinned = all.count { it.pinned },
        withImage = all.count { it.imageBase64 != null },
        withWebSearch = all.count { it.webSearchTool },
        withReasoning = all.count { it.reasoningEffort != null },
        withKnowledge = all.count { it.knowledgeBaseIds.isNotEmpty() },
        translated = all.count { it.sourceReportId != null },
        tableReports = all.count { it.reportType == ReportType.TABLE },
        createdToday = all.count { now - createdAtOf(it) < dayMs },
        created7d = all.count { now - createdAtOf(it) < 7 * dayMs },
        created30d = all.count { now - createdAtOf(it) < 30 * dayMs },
        oldestCreatedAt = all.minOfOrNull { createdAtOf(it) }?.takeIf { it > 0L },
        topModels = modelCalls.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
        topProviders = providerCalls.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
    )
}

/** Provider / model fleet stats. One in-memory pass over the registry +
 *  a cheap file-stat per provider for catalog freshness. No getPricing. */
internal suspend fun computeProviderModelStats(
    context: Context,
    aiSettings: Settings,
): ProviderModelData = withContext(Dispatchers.IO) {
    val providers = ProviderRegistry.getAll()
    val now = System.currentTimeMillis()
    val cooldowns = ModelCooldownStore.cooldowns.value
    val lastRun = ModelTestRunStore.load(context)

    // Model-capability aggregates (from /models metadata), accumulated across
    // every provider's configured models in the same pass.
    var fnCalling = 0; var pdfInput = 0; var reasoningLevels = 0; var deprecated = 0; var withCaps = 0
    var maxCtxModel: String? = null; var maxCtxTokens = 0
    val contextBuckets = linkedMapOf("≥1M" to 0, "128K–1M" to 0, "32–128K" to 0, "<32K" to 0, "unknown" to 0)

    val rows = providers.map { p ->
        val cfg = aiSettings.getProvider(p)
        val models = cfg.models.filter { it.isNotBlank() }
        val modelSet = models.toHashSet()
        val typeCounts = LinkedHashMap<String, Int>()
        for (m in models) {
            val t = aiSettings.getModelType(p, m) ?: ModelType.UNKNOWN
            typeCounts[t] = (typeCounts[t] ?: 0) + 1
            val caps = cfg.modelCapabilities[m]
            if (caps != null) withCaps++
            if (caps?.supportsFunctionCalling == true) fnCalling++
            if (caps?.supportsPdfInput == true) pdfInput++
            if (!(caps?.reasoningEffortLevels).isNullOrEmpty()) reasoningLevels++
            if (!(caps?.deprecationDate).isNullOrBlank()) deprecated++
            val ctx = caps?.contextLength
            val bucket = when {
                ctx == null || ctx <= 0 -> "unknown"
                ctx >= 1_000_000 -> "≥1M"
                ctx >= 128_000 -> "128K–1M"
                ctx >= 32_000 -> "32–128K"
                else -> "<32K"
            }
            contextBuckets[bucket] = (contextBuckets[bucket] ?: 0) + 1
            if (ctx != null && ctx > maxCtxTokens) { maxCtxTokens = ctx; maxCtxModel = m }
        }
        val at = ModelListCache.fetchedAt(context, p.id)
        val (pass, fail) = lastRun?.itemsForProvider(p.id)?.let { items ->
            items.count { it.status == TestStatus.PASS } to items.count { it.status == TestStatus.FAIL }
        } ?: (0 to 0)
        ProviderRow(
            id = p.id,
            active = aiSettings.isProviderActive(p),
            format = p.apiFormat.name,
            host = providerHost(p),
            defaultModel = p.defaultModel,
            hasKey = aiSettings.getApiKey(p).isNotBlank(),
            models = models.size,
            vision = cfg.visionCapableComputed.count { it in modelSet },
            webSearch = cfg.webSearchCapableComputed.count { it in modelSet },
            reasoning = cfg.reasoningCapableComputed.count { it in modelSet },
            embedding = typeCounts[ModelType.EMBEDDING] ?: 0,
            blocked = models.count { aiSettings.isBlocked(p.id, it) },
            inaccessible = models.count { aiSettings.isInaccessible(p.id, it) },
            testExcluded = models.count { aiSettings.isTestExcluded(p.id, it) },
            cooling = models.count { (cooldowns["${p.id}:$it"] ?: 0L) > now },
            cacheAgeMs = at?.let { now - it },
            concCap = p.maxConcurrentCallsPerProvider,
            perMinCap = p.maxCallsPerProviderPerMinute,
            modelsByType = typeCounts,
            testPassed = pass,
            testFailed = fail,
        )
    }

    fun mergeTypeCounts(): Map<String, Int> {
        val agg = LinkedHashMap<String, Int>()
        rows.forEach { r -> r.modelsByType.forEach { (t, c) -> agg[t] = (agg[t] ?: 0) + c } }
        // Present in canonical ModelType order, unknowns last.
        val ordered = LinkedHashMap<String, Int>()
        ModelType.ALL.forEach { t -> agg[t]?.let { ordered[t] = it } }
        agg.forEach { (t, c) -> if (t !in ordered) ordered[t] = c }
        return ordered
    }

    ProviderModelData(
        providersConfigured = providers.size,
        providersActive = rows.count { it.active },
        providersWithKey = rows.count { it.hasKey },
        totalModels = rows.sumOf { it.models },
        byFormat = rows.groupingBy { it.format }.eachCount(),
        modelsByType = mergeTypeCounts(),
        vision = rows.sumOf { it.vision },
        webSearch = rows.sumOf { it.webSearch },
        reasoning = rows.sumOf { it.reasoning },
        embedding = rows.sumOf { it.embedding },
        blocked = rows.sumOf { it.blocked },
        inaccessible = rows.sumOf { it.inaccessible },
        testExcluded = rows.sumOf { it.testExcluded },
        cooling = rows.sumOf { it.cooling },
        cached = rows.count { it.cacheAgeMs != null },
        stale = rows.count { it.cacheAgeMs != null && it.cacheAgeMs > MODEL_CACHE_STALE_MS },
        neverCached = rows.count { it.cacheAgeMs == null },
        agents = aiSettings.agents.size,
        flocks = aiSettings.flocks.size,
        swarms = aiSettings.swarms.size,
        lastTest = lastRun?.let {
            TestRunSummary(it.forTestingAtStart, it.doneCount, it.errorCount, it.totalCost, it.startedAt)
        },
        providers = rows.sortedWith(compareByDescending<ProviderRow> { it.active }.thenByDescending { it.models }),
        fnCalling = fnCalling,
        pdfInput = pdfInput,
        reasoningLevels = reasoningLevels,
        deprecated = deprecated,
        withCaps = withCaps,
        contextBuckets = contextBuckets,
        maxContextModel = maxCtxModel,
        maxContextTokens = maxCtxTokens,
    )
}

/** Knowledge-base totals. */
internal suspend fun computeKnowledgeStats(context: Context): KnowledgeData = withContext(Dispatchers.IO) {
    val kbs = KnowledgeStore.listKnowledgeBases(context)
    val allSources = kbs.flatMap { it.sources }
    KnowledgeData(
        kbCount = kbs.size,
        kbChunks = kbs.sumOf { it.totalChunks },
        kbChars = kbs.sumOf { it.totalChars },
        kbSourcesByType = allSources.groupingBy { it.type }.eachCount(),
        kbFailed = allSources.count { it.errorMessage != null },
    )
}

// =====================================================================
// API trace statistics
// =====================================================================

internal data class TraceStatsData(
    val tracingEnabled: Boolean,
    val total: Int,
    val partial: Int,
    val runs: Int,
    val ok2xx: Int,
    val rate429: Int,
    val client4xx: Int,        // 4xx excluding 429
    val server5xx: Int,
    val failed0: Int,          // statusCode 0 — transport failure
    val other: Int,
    val withReport: Int,
    val distinctReports: Int,
    val today: Int,
    val last7d: Int,
    val last30d: Int,
    val oldest: Long?,
    val newest: Long?,
    val byHost: List<Pair<String, Int>>,
    val byModel: List<Pair<String, Int>>,
    val byCategory: List<Pair<String, Int>>,
)

private fun <T> top(counts: Map<T, Int>, n: Int): List<Pair<T, Int>> =
    counts.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

/** Full count breakdown, sorted desc. Callers slice with .take(n). */
private fun <T> sortedDesc(counts: Map<T, Int>): List<Pair<T, Int>> =
    counts.entries.sortedByDescending { it.value }.map { it.key to it.value }

/** One pass over the cached trace list. Cheap once the cache is warm. */
internal suspend fun computeTraceStats(): TraceStatsData = withContext(Dispatchers.IO) {
    val traces = ApiTracer.getTraceFiles()
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    val hosts = HashMap<String, Int>()
    val models = HashMap<String, Int>()
    val cats = HashMap<String, Int>()
    val runs = HashSet<String>()
    val reports = HashSet<String>()
    var ok2xx = 0; var r429 = 0; var c4xx = 0; var s5xx = 0; var f0 = 0; var other = 0
    var partial = 0; var withReport = 0
    var today = 0; var w7 = 0; var w30 = 0
    for (t in traces) {
        when {
            t.statusCode in 200..299 -> ok2xx++
            t.statusCode == 429 -> r429++
            t.statusCode in 400..499 -> c4xx++
            t.statusCode in 500..599 -> s5xx++
            t.statusCode == 0 -> f0++
            else -> other++
        }
        if (t.partial) partial++
        if (t.hostname.isNotBlank()) hosts[t.hostname] = (hosts[t.hostname] ?: 0) + 1
        t.model?.takeIf { it.isNotBlank() }?.let { models[it] = (models[it] ?: 0) + 1 }
        cats[t.category?.takeIf { it.isNotBlank() } ?: "(uncategorised)"] = (cats[t.category?.takeIf { it.isNotBlank() } ?: "(uncategorised)"] ?: 0) + 1
        t.runId?.let { runs.add(it) }
        t.reportId?.let { withReport++; reports.add(it) }
        val age = now - t.timestamp
        if (age < dayMs) today++
        if (age < 7 * dayMs) w7++
        if (age < 30 * dayMs) w30++
    }
    TraceStatsData(
        tracingEnabled = ApiTracer.isTracingEnabled,
        total = traces.size, partial = partial, runs = runs.size,
        ok2xx = ok2xx, rate429 = r429, client4xx = c4xx, server5xx = s5xx, failed0 = f0, other = other,
        withReport = withReport, distinctReports = reports.size,
        today = today, last7d = w7, last30d = w30,
        oldest = traces.minOfOrNull { it.timestamp }, newest = traces.maxOfOrNull { it.timestamp },
        byHost = sortedDesc(hosts), byModel = sortedDesc(models), byCategory = sortedDesc(cats),
    )
}

// =====================================================================
// App log statistics
// =====================================================================

internal data class LogStatsData(
    val level: String,
    val writerError: String?,
    val droppedLines: Long,
    val fileCount: Int,
    val totalBytes: Long,
    val oldestDate: String?,
    val newestDate: String?,
    val totalEntries: Int,
    val byLevel: Map<String, Int>,     // ERROR/WARN/INFO/DEBUG seeded
    val topTags: List<Pair<String, Int>>,
    val files: List<Pair<String, Long>>,   // (date, sizeBytes), newest first
)

/** Header line of a log entry: "yyyy-MM-dd HH:mm:ss.SSS LEVEL Tag: message".
 *  Continuation lines (stack traces) don't match. */
private val LOG_HEADER = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} (\w+) ([^:\s]+)""")
private const val LOG_FILES_TO_PARSE = 14

internal suspend fun computeLogStats(): LogStatsData = withContext(Dispatchers.IO) {
    val files = AppLog.getLogFiles()                 // newest-first
    val byLevel = linkedMapOf("ERROR" to 0, "WARN" to 0, "INFO" to 0, "DEBUG" to 0)
    val tags = HashMap<String, Int>()
    var entries = 0
    for (f in files.take(LOG_FILES_TO_PARSE)) {
        val content = AppLog.readLogFile(f.filename) ?: continue
        for (line in content.lineSequence()) {
            val m = LOG_HEADER.find(line) ?: continue
            entries++
            val lvl = m.groupValues[1]
            if (lvl in byLevel) byLevel[lvl] = (byLevel[lvl] ?: 0) + 1
            val tag = m.groupValues[2]
            if (tag.isNotBlank()) tags[tag] = (tags[tag] ?: 0) + 1
        }
    }
    LogStatsData(
        level = AppLog.threshold.name,
        writerError = AppLog.lastWriterError,
        droppedLines = AppLog.droppedLineCount,
        fileCount = files.size,
        totalBytes = files.sumOf { it.sizeBytes },
        oldestDate = files.minByOrNull { it.date }?.date,
        newestDate = files.maxByOrNull { it.date }?.date,
        totalEntries = entries,
        byLevel = byLevel,
        topTags = top(tags, 10),
        files = files.take(LOG_FILES_TO_PARSE).map { it.date to it.sizeBytes },
    )
}
