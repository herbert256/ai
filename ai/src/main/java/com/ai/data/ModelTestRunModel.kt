package com.ai.data

/**
 * Runtime + persisted state for the "Test all models" feature
 * (Housekeeping → Test → Test all models). One run tests every
 * configured model of every active provider with the provider-screen
 * probe ("Reply with exactly: OK"). Mirrors [FanOutRunModel] in shape
 * so the L1/L2/L3 screens can reuse the Fan Out stats panel + progress
 * bar logic.
 *
 * Only one run is ever kept — the latest. It's persisted to
 * `<filesDir>/test_run.json` by [ModelTestRunStore] and reloaded on
 * screen open.
 */

/** "$providerId:$model" — unit-of-work key. Colon separator matches
 *  the [ModelCooldownStore] key convention. */
typealias ModelTestKey = String

fun modelTestKey(providerId: String, model: String): ModelTestKey = "$providerId:$model"

/** Per-(provider, model) test lifecycle. */
enum class TestStatus {
    /** Queued, waiting for a concurrency permit. UI: 🕓. */
    PENDING,

    /** Permit acquired, probe in flight. UI: ⏳. */
    RUNNING,

    /** Probe call succeeded. UI: ✅. */
    PASS,

    /** Probe call failed (HTTP error, parse error, network). UI: ❌. */
    FAIL
}

/** One (provider, model) unit of work. Mirrors [PairState]. */
data class ModelTestState(
    val key: ModelTestKey,
    val providerId: String,
    val model: String,
    val status: TestStatus,
    val errorMessage: String? = null,
    val responseText: String? = null,
    val durationMs: Long? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val traceFilename: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val totalCost: Double get() = (inputCost ?: 0.0) + (outputCost ?: 0.0)
}

/** Whole test run. Built once per "Test all models" press, mutated by
 *  [com.ai.viewmodel.ModelTestEngine]'s per-item transitions, and
 *  persisted by [ModelTestRunStore]. The counter helpers mirror
 *  [FanOutRunState] so the L1 stats panel maps 1:1. */
data class ModelTestRunState(
    val startedAt: Long = System.currentTimeMillis(),
    /** UUID shared by every API trace produced during this Test all
     *  models run — the L1 🐞 icon deep-links the trace list to this
     *  id so the user sees only the calls this run made. Null on
     *  states persisted before this field existed. */
    val runId: String? = null,
    /** Keyed by [ModelTestKey] for O(1) lookup. */
    val items: Map<ModelTestKey, ModelTestState> = emptyMap(),
    /** Catalog snapshot captured at [com.ai.viewmodel.ModelTestEngine.startRun]
     *  time, used by the L1 top-row stats panel. These are *catalog*
     *  counts (everything in the selected providers' model lists),
     *  partitioned by the skip rule that applied: inaccessible (tier-
     *  gated, e.g. Together non-serverless), excluded (cost > 5¢ +
     *  manual), or "no chat" (non-testable types — image / TTS / etc.).
     *  Stable for the lifetime of the run; the second-row [doneCount]
     *  / [errorCount] / etc. track the actual probe progress. */
    val catalogTotal: Int = 0,
    val inaccessibleAtStart: Int = 0,
    val excludedAtStart: Int = 0,
    val noChatAtStart: Int = 0
) {
    val total: Int get() = items.size
    val doneCount: Int get() = items.values.count { it.status == TestStatus.PASS }
    /** Top-row "For testing" — derived so the four catalog buckets +
     *  this always sum back to [catalogTotal]. */
    val forTestingAtStart: Int
        get() = (catalogTotal - inaccessibleAtStart - excludedAtStart - noChatAtStart).coerceAtLeast(0)
    val errorCount: Int get() = items.values.count { it.status == TestStatus.FAIL }
    val runningCount: Int get() = items.values.count { it.status == TestStatus.RUNNING }
    val queuedCount: Int get() = items.values.count { it.status == TestStatus.PENDING }
    val totalCost: Double get() = items.values.sumOf { it.totalCost }

    /** Distinct providerIds in first-seen order — drives the L1 list.
     *  Computed getter (not a constructor property) so Gson ignores it. */
    val providerIds: List<String>
        get() {
            val seen = LinkedHashSet<String>()
            items.values.forEach { seen.add(it.providerId) }
            return seen.toList()
        }

    fun itemsForProvider(providerId: String): List<ModelTestState> =
        items.values.filter { it.providerId == providerId }
}
