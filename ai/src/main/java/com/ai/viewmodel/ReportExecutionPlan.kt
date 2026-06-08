package com.ai.viewmodel

import com.ai.data.AgentParameters

/**
 * A pre-flight description of what a report run will do, computed BEFORE any
 * network call fires (audit R10). It turns a resolved model selection + the
 * active metadata/secondary settings into concrete call counts, a per-provider
 * breakdown, capability flags, skipped-model reasons, and a rough output-cost
 * ceiling.
 *
 * It is deliberately a pure value object with a pure [buildReportExecutionPlan]
 * builder so the same plan can (a) back a confirmation/preview UI before
 * expensive runs (U05) and (b) be asserted directly in tests (T05) — instead of
 * each surface re-deriving the same call-count rules.
 */
data class PlannedModel(
    val providerId: String,
    val model: String,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    val supportsWebSearch: Boolean = false,
    val isLocal: Boolean = false,
    /** Output price per token when known; drives the rough cost ceiling. */
    val outputPricePerToken: Double? = null,
)

/** A model the user picked that won't actually run, and why (blocked, no key,
 *  inaccessible, …). Surfaced so the user understands "why fewer than I chose". */
data class SkippedModel(val providerId: String, val model: String, val reason: String)

data class ReportExecutionPlan(
    val models: List<PlannedModel>,
    val skipped: List<SkippedModel>,
    val primaryCallCount: Int,
    val metadataCallCount: Int,
    val secondaryCallCount: Int,
    val perProviderCounts: Map<String, Int>,
    val webSearchEnabled: Boolean,
    val reasoningRequested: Boolean,
    val includesLocalModels: Boolean,
    /** Upper-bound output cost (USD): Σ maxTokens × outputPricePerToken over
     *  priced models. Null when no selected model has a known price or no token
     *  ceiling is set. A ceiling, not a prediction — real cost depends on
     *  actual output length and input tokens. */
    val estimatedOutputCostCeilingUsd: Double?,
) {
    /** Distinct providers the run will hit, sorted for stable display. */
    val providers: List<String> get() = perProviderCounts.keys.sorted()

    val totalCallCount: Int get() = primaryCallCount + metadataCallCount + secondaryCallCount

    /** Compact human-readable lines for a preview surface (U05). */
    fun summaryLines(): List<String> = buildList {
        add("$primaryCallCount primary call${plural(primaryCallCount)} across ${providers.size} provider${plural(providers.size)}")
        if (metadataCallCount > 0) add("$metadataCallCount metadata call${plural(metadataCallCount)} (titles / icons / language)")
        if (secondaryCallCount > 0) add("$secondaryCallCount secondary call${plural(secondaryCallCount)} (auto rerank / moderation)")
        if (skipped.isNotEmpty()) add("${skipped.size} model${plural(skipped.size)} skipped")
        if (webSearchEnabled) add("web search enabled")
        if (reasoningRequested) add("reasoning requested")
        if (includesLocalModels) add("includes on-device model${plural(models.count { it.isLocal })}")
        estimatedOutputCostCeilingUsd?.let { add("≤ ~\$${"%.4f".format(it)} output cost") }
        add("$totalCallCount total API call${plural(totalCallCount)}")
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}

/**
 * Build a [ReportExecutionPlan] from a resolved selection and the effective
 * metadata/secondary settings. Pure: callers resolve which models run vs skip
 * (model-state filtering, missing keys, etc.) and pass the effective metadata
 * flags; this only counts and aggregates.
 *
 * @param metadataEnabled  the master metadata gate. When false, every metadata
 *                         sub-flag is forced off (mirrors GeneralSettings).
 */
fun buildReportExecutionPlan(
    selected: List<PlannedModel>,
    skipped: List<SkippedModel> = emptyList(),
    params: AgentParameters? = null,
    metadataEnabled: Boolean = true,
    reportTitleAiMode: Boolean = false,
    reportIconEnabled: Boolean = false,
    reportLanguageEnabled: Boolean = false,
    perModelTitleEnabled: Boolean = false,
    perModelIconEnabled: Boolean = false,
    autoRerankAndModeration: Boolean = false,
): ReportExecutionPlan {
    val n = selected.size
    val perProvider = selected.groupingBy { it.providerId }.eachCount()

    // Metadata jobs, each gated by the master switch (mirrors reportIconOn etc.).
    var metadata = 0
    if (metadataEnabled) {
        if (reportTitleAiMode) metadata += 1   // background AI short title
        if (reportIconEnabled) metadata += 1    // report emoji
        if (reportLanguageEnabled) metadata += 1 // language + flag
        if (perModelTitleEnabled) metadata += n  // one per model
        if (perModelIconEnabled) metadata += n   // one per model
    }

    // Auto rerank + moderation need at least two answers to compare.
    val secondary = if (autoRerankAndModeration && n >= 2) 2 else 0

    val tokenCeiling = params?.maxTokens
    val costCeiling: Double? = if (tokenCeiling != null) {
        val priced = selected.mapNotNull { it.outputPricePerToken }
        if (priced.isEmpty()) null else priced.sumOf { it * tokenCeiling }
    } else null

    return ReportExecutionPlan(
        models = selected,
        skipped = skipped,
        primaryCallCount = n,
        metadataCallCount = metadata,
        secondaryCallCount = secondary,
        perProviderCounts = perProvider,
        webSearchEnabled = params?.webSearchTool == true || params?.searchEnabled == true,
        reasoningRequested = !params?.reasoningEffort.isNullOrBlank(),
        includesLocalModels = selected.any { it.isLocal },
        estimatedOutputCostCeilingUsd = costCeiling,
    )
}
