package com.ai.data

import com.ai.model.InternalPrompt

/**
 * Single source of truth for the Fan Out feature's runtime state.
 *
 * One [FanOutRunState] per active or persisted (reportId, metaPromptId)
 * pair. The runner publishes updates to a single StateFlow; the UI
 * subscribes to that StateFlow and renders without polling disk or
 * merging multiple loosely-coupled views (the previous design relied
 * on a 500 ms polling loop + a separate `runningFanOutPairs`
 * StateFlow + a `recentlySettled` grace window to mask races between
 * them). Every state transition is an atomic single-line update —
 * subscribers see exactly one value per transition, no flicker.
 *
 * The on-disk [SecondaryResult] rows stay the canonical persistence
 * layer; this model is hydrated from disk on first access and kept
 * in sync via the runner's transition lambdas. Exports / history /
 * search still read from [SecondaryResultStorage] as before.
 */

/** "${reportId}|${metaPromptId}" — unique per Fan Out run on a
 *  given report. Each prompt can have at most one run per report. */
typealias FanOutRunKey = String

/** "${answererAgentId}|${sourceAgentId}" — unique per pair within
 *  a run. Self-pairs (answerer == source) are excluded at run
 *  construction time and never appear here. */
typealias PairKey = String

fun runKey(reportId: String, metaPromptId: String): FanOutRunKey =
    "$reportId|$metaPromptId"

fun pairKey(answererAgentId: String, sourceAgentId: String): PairKey =
    "$answererAgentId|$sourceAgentId"

/** Per-pair lifecycle state. The runner moves a pair through these
 *  states in order; the UI's classifier reads from this directly. */
enum class PairStatus {
    /** Placeholder written to disk, waiting for the runner's
     *  per-host throttle permit. UI: 🕓 queued. */
    PENDING,

    /** Permit acquired, HTTP in flight. UI: ⏳ running. */
    RUNNING,

    /** Result written to disk (content non-blank, or empty body but
     *  durationMs stamped). UI: ✅ done. */
    DONE,

    /** Error stamped on disk. UI: ❌ errored. */
    ERROR
}

/** One pair within a Fan Out run. Mirrors the fan-out-specific
 *  fields of the on-disk [SecondaryResult] row plus the per-pair
 *  [status] enum. */
data class PairState(
    val id: String,                      // SecondaryResult.id (UUID)
    val answererAgentId: String,
    val sourceAgentId: String,
    val providerId: String,
    val model: String,
    val status: PairStatus,
    val content: String? = null,
    val errorMessage: String? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    val tokenUsage: TokenUsage? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val key: PairKey get() = pairKey(answererAgentId, sourceAgentId)
    val totalCost: Double get() = (inputCost ?: 0.0) + (outputCost ?: 0.0)
}

/** Promote a disk-derived [PairStatus] to RUNNING when the pair's
 *  id is in the live in-flight set ([runningSet]). The disk row of
 *  an in-flight pair looks identical to a queued one (blank
 *  content, no error, no duration), so we can't tell them apart
 *  from disk alone — the legacy `runningFanOutPairs` StateFlow
 *  fills the gap.
 *
 *  DONE / ERROR always win: once content / errorMessage has
 *  landed the pair is settled, regardless of whether its id is
 *  still in [runningSet] for a brief window. */
fun PairState.effectiveStatus(runningSet: Set<String>): PairStatus = when (status) {
    PairStatus.DONE, PairStatus.ERROR -> status
    else -> if (id in runningSet) PairStatus.RUNNING else PairStatus.PENDING
}

/** A combined-reports / fan-in row attached to a Fan Out run.
 *  Identified on disk by `fanInOf != null && fanOutSourceAgentId
 *  == null`. Whole-run fan-ins leave [scopeProviderId] /
 *  [scopeModel] null; per-model fan-ins set them to the L2 active
 *  (provider, model). */
data class CombinedReportState(
    val id: String,
    val fanInPromptId: String,
    val fanInPromptName: String,
    val scopeProviderId: String?,
    val scopeModel: String?,
    val providerId: String,
    val model: String,
    val status: PairStatus,
    val content: String? = null,
    val errorMessage: String? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    val tokenUsage: TokenUsage? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val totalCost: Double get() = (inputCost ?: 0.0) + (outputCost ?: 0.0)
    val isModelScoped: Boolean get() = scopeProviderId != null && scopeModel != null
}

/** Entire Fan Out run state. Built by hydration on first access
 *  and mutated by the runner's per-transition `update { … }`
 *  calls. UI reads from a snapshot of this. */
data class FanOutRunState(
    val key: FanOutRunKey,
    val reportId: String,
    val metaPrompt: InternalPrompt,
    val scope: SecondaryScope,
    /** Subset of report-agent ids selected as answerers on the
     *  run's launch screen. Null when the run was launched on a
     *  legacy entry path that doesn't carry the selection (use
     *  every successful agent). */
    val responderIds: Set<String>? = null,
    /** Map keyed by [PairKey] so a pair lookup is O(1). The pair
     *  count equals the answerer set × source set minus self-pairs;
     *  the actual size on construction depends on [scope] and
     *  [responderIds]. */
    val pairs: Map<PairKey, PairState> = emptyMap(),
    val combinedReports: List<CombinedReportState> = emptyList(),
    /** Set when the user taps the title-bar trash on L1. The
     *  runner stops queueing new pairs; in-flight pairs are
     *  cancelled (their per-pair coroutine sees the cancel and
     *  bails before save). */
    val cancelled: Boolean = false
) {
    val totalPairs: Int get() = pairs.size
    val doneCount: Int get() = pairs.values.count { it.status == PairStatus.DONE }
    val errorCount: Int get() = pairs.values.count { it.status == PairStatus.ERROR }
    val runningCount: Int get() = pairs.values.count { it.status == PairStatus.RUNNING }
    val queuedCount: Int get() = pairs.values.count { it.status == PairStatus.PENDING }
    val totalCost: Double get() =
        pairs.values.sumOf { it.totalCost } + combinedReports.sumOf { it.totalCost }

    /** Live RUNNING count — counts pairs whose disk row says
     *  PENDING but whose id is in [runningSet] (the legacy in-flight
     *  set the existing `runFanOutPrompt` runner maintains). Used
     *  by the L1 stats panel until the launch path is migrated to
     *  [com.ai.viewmodel.FanOutEngine.startRun]. */
    fun effectiveRunningCount(runningSet: Set<String>): Int =
        pairs.values.count { it.effectiveStatus(runningSet) == PairStatus.RUNNING }

    /** Live QUEUED count — pairs whose disk row says PENDING AND
     *  are not (yet) in [runningSet]. */
    fun effectiveQueuedCount(runningSet: Set<String>): Int =
        pairs.values.count { it.effectiveStatus(runningSet) == PairStatus.PENDING }

    /** UI helper — every pair's answerer key, deduplicated, in
     *  the order they first appear. Used by the L1 model row list
     *  so multi-agent (Swarm) answerers don't double up. */
    val answererKeys: List<String> by lazy {
        val seen = LinkedHashSet<String>()
        for (pair in pairs.values) {
            seen.add("${pair.providerId}|${pair.model}")
        }
        seen.toList()
    }
}

/** Reverse-map a persisted [SecondaryResult] pair row into a
 *  [PairState]. Returns null for rows that aren't fan-out pairs
 *  (no [SecondaryResult.fanOutSourceAgentId]) or that lack the
 *  answerer agent id information needed to construct the [PairKey].
 *
 *  Caller must supply the answerer agent id — the on-disk row only
 *  stores `providerId` + `model`, so the caller has to resolve the
 *  agent id from the parent [Report]'s agent list. */
fun SecondaryResult.toPairState(answererAgentId: String): PairState? {
    val src = fanOutSourceAgentId ?: return null
    val status = when {
        errorMessage != null -> PairStatus.ERROR
        !content.isNullOrBlank() || durationMs != null -> PairStatus.DONE
        else -> PairStatus.PENDING
    }
    return PairState(
        id = id,
        answererAgentId = answererAgentId,
        sourceAgentId = src,
        providerId = providerId,
        model = model,
        status = status,
        content = content,
        errorMessage = errorMessage,
        inputCost = inputCost,
        outputCost = outputCost,
        durationMs = durationMs,
        tokenUsage = tokenUsage,
        timestamp = timestamp
    )
}

/** Reverse-map a persisted combined-report row into a
 *  [CombinedReportState]. Returns null for rows that aren't
 *  combined reports (no [SecondaryResult.fanInOf]). */
fun SecondaryResult.toCombinedReportState(): CombinedReportState? {
    val fanIn = fanInOf ?: return null
    val name = metaPromptName?.takeIf { it.isNotBlank() } ?: fanIn
    val status = when {
        errorMessage != null -> PairStatus.ERROR
        !content.isNullOrBlank() || durationMs != null -> PairStatus.DONE
        else -> PairStatus.PENDING
    }
    return CombinedReportState(
        id = id,
        fanInPromptId = fanIn,
        fanInPromptName = name,
        scopeProviderId = scopeProviderId,
        scopeModel = scopeModel,
        providerId = providerId,
        model = model,
        status = status,
        content = content,
        errorMessage = errorMessage,
        inputCost = inputCost,
        outputCost = outputCost,
        durationMs = durationMs,
        tokenUsage = tokenUsage,
        timestamp = timestamp
    )
}
