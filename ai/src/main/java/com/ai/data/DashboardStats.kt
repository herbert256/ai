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
    val usageGroups: List<ProviderCostGroup>,
    val totalCalls: Int,
    val totalTokens: Long,
    val totalUsageCost: Double,
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

    // Usage / spend — reuse the AI Usage cost-grouping verbatim.
    val stats = settingsPrefs.loadUsageStats()
    val usageGroups = buildProviderCostGroups(context, stats)
    val totalCalls = stats.values.sumOf { it.callCount }
    val totalTokens = stats.values.sumOf { it.totalTokens }
    val totalUsageCost = usageGroups.sumOf { it.totalCost }

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
        usageGroups = usageGroups,
        totalCalls = totalCalls,
        totalTokens = totalTokens,
        totalUsageCost = totalUsageCost,
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
