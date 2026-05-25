package com.ai.data

import android.content.Context
import com.ai.model.Settings
import com.ai.ui.admin.ProviderCostGroup
import com.ai.ui.admin.buildProviderCostGroups
import com.ai.ui.hub.reportHasProblems
import com.ai.ui.hub.reportIsRunning
import com.ai.ui.settings.SettingsPreferences
import com.ai.viewmodel.TranslationRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lifetime/aggregate counters shown on the lower half of the AI
 *  Dashboard. Everything here is a one-shot disk computation produced
 *  by [computeDashboardAggregates] — distinct from the live in-memory
 *  ops state (caps, throttle, cooldowns) the screen polls separately. */
internal data class DashboardAggregates(
    val reports: ReportStats,
    val secondaries: Map<SecondaryKind, Int>,
    val metaByName: Map<String, Int>,
    val providersConfigured: Int,
    val providersWithKey: Int,
    val totalModels: Int,
    val kbCount: Int,
    val kbChunks: Int,
    val kbChars: Long,
    val kbSourcesByType: Map<KnowledgeSourceType, Int>,
    val kbFailed: Int,
    val modelsCached: Int,
    val modelCacheStale: Int,
    val pricingStats: String,
    val openRouterCacheAge: String,
    val manualOverrides: Int,
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
        for (m in aiSettings.getProvider(p).models) {
            if (m.isBlank()) continue
            val src = PricingCache.getPricing(context, p, m).source
            tierCounts[src] = (tierCounts[src] ?: 0) + 1
        }
    }
    tierCounts
}

/** Pricing-tier source tags as they appear on [PricingCache.ModelPricing.source]
 *  (set by the catalog parsers / explicit constructions — NOT getPricing's log
 *  labels). Drives the "Costs tier" card so it lists every tier even at zero.
 *  Note: OpenRouter self-report and cross-provider both tag "OPENROUTER", and
 *  Together self-report tags "TOGETHER" — the source can't tell self from
 *  fallback, so each appears once. */
internal val PRICING_TIER_ORDER = listOf(
    "OVERRIDE", "LITELLM", "MODELSDEV", "LLMPRICES", "ARTIFICIALANALYSIS",
    "OPENROUTER", "TOGETHER", "HELICONE", "DEFAULT"
)

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

/** Single-pass aggregate computation for the AI Dashboard. Reuses the
 *  hub's running/problems predicates and the AI Usage cost-grouping so
 *  the numbers match those screens exactly. Disk-heavy (one report
 *  scan + one secondary read per report + usage stats + KB manifests)
 *  — always call on [Dispatchers.IO] via the screen's slow tick, never
 *  on the live ticker. */
internal suspend fun computeDashboardAggregates(
    context: Context,
    aiSettings: Settings,
    settingsPrefs: SettingsPreferences,
    translationRuns: Map<String, TranslationRunState>,
): DashboardAggregates = withContext(Dispatchers.IO) {
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

    // Providers / models / model-list cache freshness.
    val providers = ProviderRegistry.getAll()
    val now = System.currentTimeMillis()
    var cached = 0
    var stale = 0
    for (p in providers) {
        val at = ModelListCache.fetchedAt(context, p.id) ?: continue
        cached++
        if (now - at > MODEL_CACHE_STALE_MS) stale++
    }

    // Knowledge bases.
    val kbs = KnowledgeStore.listKnowledgeBases(context)
    val allSources = kbs.flatMap { it.sources }

    DashboardAggregates(
        reports = reportStats,
        secondaries = secByKind,
        metaByName = metaByName.entries.sortedByDescending { it.value }.associate { it.key to it.value },
        providersConfigured = providers.size,
        providersWithKey = providers.count { aiSettings.getApiKey(it).isNotBlank() },
        totalModels = providers.sumOf { aiSettings.getProvider(it).models.size },
        kbCount = kbs.size,
        kbChunks = kbs.sumOf { it.totalChunks },
        kbChars = kbs.sumOf { it.totalChars },
        kbSourcesByType = allSources.groupingBy { it.type }.eachCount(),
        kbFailed = allSources.count { it.errorMessage != null },
        modelsCached = cached,
        modelCacheStale = stale,
        pricingStats = PricingCache.getPricingStats(context),
        openRouterCacheAge = PricingCache.getOpenRouterCacheAge(context),
        manualOverrides = PricingCache.getAllManualPricing(context).size,
    )
}
