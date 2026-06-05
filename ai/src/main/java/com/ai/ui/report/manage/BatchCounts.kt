package com.ai.ui.report.manage

import com.ai.data.BatchItemStatus

/** How a batch treats a benched (rate-limited) item in its L1 stats. */
enum class BenchMode {
    /** No Bench bucket — benched items count wherever their status puts
     *  them. Worker-swarm batches with no benched predicate (Tournament,
     *  Compare). */
    NONE,

    /** Fixed-model "A" batches (Fan Out, Judges): a short-benched model
     *  parks ALL of its items, so any non-done item of a benched model is
     *  carved into the Bench bucket — out of Error / Run / Wait / Queue. */
    MODEL_PARKED,

    /** Worker-swarm "B" batches that still split errors (Fan Meta,
     *  Translation): only an item that ERRORED on a benched model is
     *  carved into Bench; Run / Wait / Queue ignore the bench. The caller
     *  folds Bench back into the displayed Error column (no Bench column). */
    ERRORED,
}

/** The seven live-batch counters shown by [BatchStatsRow]. Cost is left
 *  to each caller — it varies (per-item sum vs a pre-aggregated field,
 *  with or without a "¢" suffix). */
data class BatchCounts(
    val total: Int,
    val done: Int,
    val error: Int,
    val bench: Int,
    val running: Int,
    val wait: Int,
    val queued: Int,
)

/** Single-pass derivation of the L1 stat counters for one batch, shared
 *  by every batch-kind screen so the carve lives in exactly one place.
 *  Each item lands in exactly one bucket. A throttled (rate-gated) item
 *  counts only under [BatchCounts.wait], never also under Run — uniform
 *  across all kinds.
 *
 *  Per-item bucket precedence:
 *  DONE → Bench (per [benchMode]) → ERROR → Wait (throttled) →
 *  Run (RUNNING) → Queue (PENDING).
 *
 *  @param statusOf the displayed lifecycle status; pass a lens (e.g.
 *    `PairState.titleStatus`) where it isn't the raw `status` field.
 *  @param benchedOf whether the item's model is benched; required for
 *    [BenchMode.MODEL_PARKED] / [BenchMode.ERRORED], ignored for
 *    [BenchMode.NONE]. */
fun <T> deriveBatchCounts(
    items: Collection<T>,
    idOf: (T) -> String,
    statusOf: (T) -> BatchItemStatus,
    throttledIds: Set<String>,
    benchedOf: ((T) -> Boolean)? = null,
    benchMode: BenchMode = BenchMode.NONE,
): BatchCounts {
    var done = 0; var error = 0; var bench = 0
    var running = 0; var wait = 0; var queued = 0
    for (item in items) {
        val status = statusOf(item)
        if (status == BatchItemStatus.DONE) { done++; continue }
        val benched = benchedOf?.invoke(item) == true
        val toBench = when (benchMode) {
            BenchMode.NONE -> false
            BenchMode.MODEL_PARKED -> benched
            BenchMode.ERRORED -> benched && status == BatchItemStatus.ERROR
        }
        if (toBench) { bench++; continue }
        when {
            status == BatchItemStatus.ERROR -> error++
            idOf(item) in throttledIds -> wait++
            status == BatchItemStatus.RUNNING -> running++
            else -> queued++   // PENDING
        }
    }
    return BatchCounts(items.size, done, error, bench, running, wait, queued)
}
