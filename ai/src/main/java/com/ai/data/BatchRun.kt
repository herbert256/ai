package com.ai.data

/** One item of a batch run — a fan-out pair, tournament match, judge
 *  cell, or compare cell. The shared surface the generic engine base and
 *  the L1 count helpers need: a stable disk id, the in-map [key], the
 *  lifecycle [status], and the rolled-up [totalCost]. Each concrete state
 *  carries far more (verdict / score / title / icon / per-call costs);
 *  this is only the common spine. */
interface BatchItem<K> {
    val id: String
    val key: K
    val status: BatchItemStatus
    val totalCost: Double
}

/** One batch run for a report — a map of [items] plus the derived status
 *  counters every L1 screen shows. The counters live here as default
 *  getters so all four run-state types share one definition instead of
 *  copying the same five `count { it.status == … }` getters. A concrete
 *  run may override (e.g. Fan Out's [totalCost] also folds in fan-in
 *  rows) and adds its own domain getters (totalMatches, judgeKeys, …). */
interface BatchRun<K, S : BatchItem<K>> {
    val reportId: String
    val items: Map<K, S>

    val total: Int get() = items.size
    val doneCount: Int get() = items.values.count { it.status == BatchItemStatus.DONE }
    val errorCount: Int get() = items.values.count { it.status == BatchItemStatus.ERROR }
    val runningCount: Int get() = items.values.count { it.status == BatchItemStatus.RUNNING }
    val queuedCount: Int get() = items.values.count { it.status == BatchItemStatus.PENDING }
    val totalCost: Double get() = items.values.sumOf { it.totalCost }
    /** True once every item has reached a terminal state. */
    val allTerminal: Boolean get() = items.isNotEmpty() &&
        items.values.all { it.status == BatchItemStatus.DONE || it.status == BatchItemStatus.ERROR }
}
