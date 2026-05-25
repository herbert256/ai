package com.ai.data

import android.content.Context
import com.ai.model.Settings
import com.ai.ui.admin.ProviderCostGroup
import com.ai.ui.admin.buildProviderCostGroups
import com.ai.ui.hub.reportHasProblems
import com.ai.ui.hub.reportIsRunning
import com.ai.ui.settings.SettingsPreferences
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.providerHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Report + secondary-result lifetime totals — the "Statistics - Reports"
 *  screen. Heavy (one report scan + a secondary read per report). */
internal data class ReportSectionData(
    val reports: ReportStats,
    val secondaries: Map<SecondaryKind, Int>,
    val metaByName: Map<String, Int>,
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
)

/** Knowledge-base totals — shown on AI Statistics. */
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
    val totalCalls: Int,
    val totalTokens: Long,
    val totalCost: Double,
    val pricingStats: String,
)

/** Spend & usage for the dedicated screen — reuses the AI Usage cost-grouping.
 *  Off the main thread (per-model getPricing). */
internal suspend fun computeUsageGroups(
    context: Context,
    settingsPrefs: SettingsPreferences,
): UsageGroupsResult = withContext(Dispatchers.IO) {
    val stats = settingsPrefs.loadUsageStats()
    val groups = buildProviderCostGroups(context, stats)
    UsageGroupsResult(
        groups = groups,
        totalCalls = stats.values.sumOf { it.callCount },
        totalTokens = stats.values.sumOf { it.totalTokens },
        totalCost = groups.sumOf { it.totalCost },
        pricingStats = PricingCache.getPricingStats(context),
    )
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
): ReportSectionData = withContext(Dispatchers.IO) {
    val all = ReportStorage.getAllReports(context)

    val activeTranslationReportIds = translationRuns.values
        .filter { !it.isFinished && !it.cancelled }
        .map { it.sourceReportId }
        .toSet()
    val running = all.filter { reportIsRunning(it, activeTranslationReportIds) }
    val runningIds = running.mapTo(HashSet()) { it.id }

    // One secondary read per report feeds BOTH the problems split and
    // the by-kind counts.
    val secByKind = linkedMapOf(
        SecondaryKind.RERANK to 0, SecondaryKind.META to 0,
        SecondaryKind.MODERATION to 0, SecondaryKind.TRANSLATE to 0
    )
    val metaByName = HashMap<String, Int>()
    var problems = 0
    for (r in all) {
        val secs = SecondaryResultStorage.listForReport(context, r.id)
        for (s in secs) {
            secByKind[s.kind] = (secByKind[s.kind] ?: 0) + 1
            if (s.kind == SecondaryKind.META) {
                val name = s.metaPromptName?.takeIf { it.isNotBlank() } ?: "Meta"
                metaByName[name] = (metaByName[name] ?: 0) + 1
            }
        }
        if (r.id !in runningIds && reportHasProblems(r, secs)) problems++
    }

    val reportStats = ReportStats(
        total = all.size,
        running = running.size,
        problems = problems,
        completed = all.count { it.completedAt != null },
        agentCalls = all.sumOf { it.agents.size },
        erroredCalls = all.sumOf { r -> r.agents.count { it.reportStatus == ReportStatus.ERROR } },
        stopped = all.sumOf { r -> r.agents.count { it.reportStatus == ReportStatus.STOPPED } },
        spend = all.sumOf { it.totalCost },
    )
    ReportSectionData(
        reports = reportStats,
        secondaries = secByKind,
        metaByName = metaByName.entries.sortedByDescending { it.value }.associate { it.key to it.value },
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

    val rows = providers.map { p ->
        val cfg = aiSettings.getProvider(p)
        val models = cfg.models.filter { it.isNotBlank() }
        val modelSet = models.toHashSet()
        val typeCounts = LinkedHashMap<String, Int>()
        for (m in models) {
            val t = aiSettings.getModelType(p, m) ?: ModelType.UNKNOWN
            typeCounts[t] = (typeCounts[t] ?: 0) + 1
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
