package com.ai.viewmodel

import com.ai.data.BatchItem
import com.ai.data.BatchRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Shared base for the four batch engines — Fan Out, Tournament, Judges,
 *  Compare. Owns the per-report run-state [StateFlow] and the identical
 *  item transition / drop machinery every engine used to copy verbatim.
 *
 *  Subclasses supply [copyWithItems] (how to rebuild a run with a
 *  replaced item map) and keep their own kind-specific hydrate /
 *  dispatch / per-item runner, calling the inherited [transitionItem] /
 *  [dropItem] / [dropRun] from there.
 *
 *  Type parameters: [RunKey] the `_runs` map key (a reportId, or
 *  `"reportId|metaPromptId"` for Fan Out's multi-run-per-report);
 *  [ItemKey] the per-item map key; [ItemState] one [BatchItem];
 *  [RunState] one [BatchRun]. */
abstract class BatchEngine<RunKey : Any, ItemKey, ItemState : BatchItem<ItemKey>, RunState : BatchRun<ItemKey, ItemState>> {

    protected val _runs = MutableStateFlow<Map<RunKey, RunState>>(emptyMap())
    val runs: StateFlow<Map<RunKey, RunState>> = _runs.asStateFlow()

    /** Rebuild [run] with its item map replaced — e.g.
     *  `run.copy(matches = items)`. */
    protected abstract fun copyWithItems(run: RunState, items: Map<ItemKey, ItemState>): RunState

    /** Atomic state transition for one item, keyed by [itemKey]. No-op if
     *  the run / item isn't loaded, or the update is identity. */
    protected fun transitionItem(runKey: RunKey, itemKey: ItemKey, update: (ItemState) -> ItemState) {
        _runs.update { runs ->
            val run = runs[runKey] ?: return@update runs
            val cur = run.items[itemKey] ?: return@update runs
            val next = update(cur)
            if (next == cur) runs else runs + (runKey to copyWithItems(run, run.items + (itemKey to next)))
        }
    }

    /** Like [transitionItem] but locates the item by its disk
     *  [BatchItem.id] rather than its map key. */
    protected fun transitionItemById(runKey: RunKey, itemId: String, update: (ItemState) -> ItemState) {
        _runs.update { runs ->
            val run = runs[runKey] ?: return@update runs
            val cur = run.items.values.firstOrNull { it.id == itemId } ?: return@update runs
            val next = update(cur)
            if (next == cur) runs else runs + (runKey to copyWithItems(run, run.items + (cur.key to next)))
        }
    }

    /** Remove one item from a run. */
    protected fun dropItem(runKey: RunKey, itemKey: ItemKey) {
        _runs.update { runs ->
            val run = runs[runKey] ?: return@update runs
            if (itemKey !in run.items) runs else runs + (runKey to copyWithItems(run, run.items - itemKey))
        }
    }

    /** Remove an entire run from the flow. */
    protected fun dropRun(runKey: RunKey) {
        _runs.update { it - runKey }
    }

    // ===== Deferred delete =====
    //
    // Deleting a big run is dominated by per-item file deletes. Splitting the
    // delete lets the UI react at once: the cheap part (stop the batch, drop the
    // in-memory run, mark it deleting) runs synchronously; the slow disk work
    // runs in the background. [deletingRuns] lets list/hydrate code hide a run
    // whose rows are still on disk so the row vanishes the instant the user
    // confirms.

    private val _deletingRuns = MutableStateFlow<Set<RunKey>>(emptySet())
    val deletingRuns: StateFlow<Set<RunKey>> = _deletingRuns.asStateFlow()

    /** True while [runKey]'s delete is mid-flight (disk work still finishing).
     *  Hydrate paths consult this so they don't re-publish a run from rows
     *  that haven't been deleted off disk yet. */
    protected fun isDeleting(runKey: RunKey): Boolean = runKey in _deletingRuns.value

    /** Split a run delete: do the cheap, user-visible part synchronously before
     *  returning — mark the run deleting, cancel the batch coroutines, drop the
     *  in-memory run — then run the slow [diskWork] on [scope] (after joining the
     *  cancelled coroutines so no late write resurrects a deleted row). The
     *  returned Job completes when the background work is done. [runJob] /
     *  [itemJobs] must be captured by the caller before this runs. */
    protected fun deleteRunDeferred(
        scope: CoroutineScope,
        runKey: RunKey,
        runJob: Job?,
        itemJobs: List<Job>,
        diskWork: suspend () -> Unit,
    ): Job {
        _deletingRuns.update { it + runKey }
        runJob?.cancel()
        itemJobs.forEach { it.cancel() }
        dropRun(runKey)
        return scope.launch(Dispatchers.IO) {
            try {
                runJob?.join()
                itemJobs.forEach { it.join() }
                diskWork()
            } finally {
                _deletingRuns.update { it - runKey }
            }
        }
    }

    // ===== Shared job lifecycle (audit R01) =====
    //
    // Every concrete engine historically kept its own `runJobs` /
    // `cellJobs|pairJobs` / `resumeScans` maps plus the same cancel-by-run /
    // cancel-by-item / resume-dedupe logic. These registries promote that into
    // the base so each engine gets identical cancellation and resume semantics
    // (important for report deletion, screen reopen, process-death recovery,
    // and "restart failed"). Additive — an engine opts in by calling these
    // instead of rolling its own maps; nothing here runs until adopted.

    private val runJobs = ConcurrentHashMap<RunKey, Job>()
    /** Item jobs keyed by [BatchItem.id] (stable across map-key reshuffles). */
    private val itemJobs = ConcurrentHashMap<String, Job>()
    private val resumeScans = ConcurrentHashMap.newKeySet<RunKey>()

    /** Record a run's coroutine, superseding (cancelling) any prior job for the
     *  same [runKey]; self-cleans from the registry on completion. */
    protected fun registerRunJob(runKey: RunKey, job: Job) {
        runJobs.put(runKey, job)?.cancel()
        job.invokeOnCompletion { runJobs.remove(runKey, job) }
    }

    /** Record one item's coroutine by its [BatchItem.id], superseding any prior
     *  job for the same id; self-cleans on completion. */
    protected fun registerItemJob(itemId: String, job: Job) {
        itemJobs.put(itemId, job)?.cancel()
        job.invokeOnCompletion { itemJobs.remove(itemId, job) }
    }

    /** True while [runKey]'s registered run coroutine is still active. */
    protected fun isRunActive(runKey: RunKey): Boolean = runJobs[runKey]?.isActive == true

    /** Cancel a run's coroutine and the coroutines of the given [itemIds]
     *  (the run's [BatchItem.id]s). Safe to call with a stale id set. */
    protected fun cancelRun(runKey: RunKey, itemIds: Collection<String> = emptyList()) {
        runJobs.remove(runKey)?.cancel()
        itemIds.forEach { itemJobs.remove(it)?.cancel() }
    }

    /** Cancel a single item's coroutine by its [BatchItem.id]. */
    protected fun cancelItem(itemId: String) {
        itemJobs.remove(itemId)?.cancel()
    }

    /** Cancel every registered run and item coroutine and clear resume state. */
    protected fun cancelAll() {
        runJobs.values.forEach { it.cancel() }
        runJobs.clear()
        itemJobs.values.forEach { it.cancel() }
        itemJobs.clear()
        resumeScans.clear()
    }

    /** Claim the resume-scan slot for [runKey]: true if this caller may scan
     *  (must pair with [endResumeScan]); false if a scan is already in flight —
     *  dedupes the 30s background sweep racing a screen-reopen relaunch. */
    protected fun beginResumeScan(runKey: RunKey): Boolean = resumeScans.add(runKey)

    /** Release the resume-scan slot claimed by [beginResumeScan]. */
    protected fun endResumeScan(runKey: RunKey) {
        resumeScans.remove(runKey)
    }

    /** A loaded run with at least one item still pending or running. */
    protected fun hasUnfinishedItems(runKey: RunKey): Boolean {
        val run = _runs.value[runKey] ?: return false
        return run.runningCount + run.queuedCount > 0
    }

    // Read-only views into the registries, for engines that need the raw Job
    // (e.g. to `cancelAndJoin()` in a suspend delete/resume path, or to report
    // which rows are live in-process for the broken-work scan).

    /** The registered run coroutine for [runKey], or null. */
    protected fun runJobOf(runKey: RunKey): Job? = runJobs[runKey]

    /** The registered coroutine for item [itemId] (its [BatchItem.id]), or null. */
    protected fun itemJobOf(itemId: String): Job? = itemJobs[itemId]

    /** Whether an item coroutine is currently registered for [itemId]. */
    protected fun hasItemJob(itemId: String): Boolean = itemJobs.containsKey(itemId)

    /** Ids of every currently-registered item coroutine. */
    protected fun itemJobIds(): Set<String> = itemJobs.keys.toSet()

    /** Run keys whose registered run coroutine is still active. */
    protected fun activeRunJobKeys(): Set<RunKey> = runJobs.filterValues { it.isActive }.keys.toSet()

    /** Every currently-registered run key (active or not) — for engines whose
     *  run key is composite and which cancel/join a whole report's runs by key
     *  prefix (e.g. FanOut's `"reportId|metaPromptId"`). */
    protected fun runJobKeys(): Set<RunKey> = runJobs.keys.toSet()
}
