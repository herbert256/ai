package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.AppLog
import com.ai.data.ReportStorage
import com.ai.data.fullCost
import com.ai.data.BatchItem
import com.ai.data.BatchItemStatus
import com.ai.data.BatchRun
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.withTracerTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared template for the four sibling batch engines whose items are
 * [SecondaryResult] rows of one [SecondaryKind] — Tournament, Judge the
 * judges, Compare with meta, and Rank the translators. Sits between the
 * generic [BatchEngine] registries and the concrete engines, owning the
 * flows the four used to copy verbatim (interrupted-row stamping, stale-row
 * detection, stop-in-flight, cancel-by-report, in-flight queries).
 *
 * The deferred adopters — [FanOutEngine] (three result phases per pair,
 * cooldown-aware predicates) and [TranslationRunManager] (no role markers,
 * per-language grouping) — keep extending [BatchEngine] directly until they
 * opt in. Item keys are `String` for every row-backed engine, so that type
 * parameter is fixed here.
 */
abstract class SecondaryBatchEngine<RunKey : Any, ItemState : BatchItem<String>, RunState : BatchRun<String, ItemState>> :
    BatchEngine<RunKey, String, ItemState, RunState>() {

    // ===== Hooks — one-liners per engine =====

    /** The app view model — scope, build progress, uiState counters. */
    protected abstract val appViewModel: AppViewModel

    /** The report view model — log context, worker runner. */
    protected abstract val reportViewModel: ReportViewModel

    /** The [SecondaryKind] this engine's rows are stored under. */
    protected abstract val secondaryKind: SecondaryKind

    /** AppLog tag, e.g. "Tournament" / "JudgeEval". */
    protected abstract val logTag: String

    /** What one item is called in user-visible strings — "match" / "cell" /
     *  "score" — so promoted flows keep each engine's wording byte-identical. */
    protected abstract val itemNoun: String

    /** The reportId a run key belongs to — identity for the reportId-keyed
     *  engines, `substringBefore("|")` for TransRank's composite key. */
    protected abstract fun reportIdOf(runKey: RunKey): String

    /** Every run key of [reportId] — `listOf(reportId)` for the single-run
     *  engines, the loaded per-language keys for TransRank. */
    protected abstract fun runKeysForReport(reportId: String): List<RunKey>

    /** [item] flipped to a terminal ERROR with [message] and a zero duration —
     *  the in-memory mirror of [markRowInterrupted]. */
    protected abstract fun terminalizeItem(item: ItemState, message: String): ItemState

    /** Parse one disk [row] back into this engine's item state — the
     *  `toMatchState` / `toJudgeCellState` / … converter. Null when the row
     *  is missing the fields an item needs. */
    protected abstract fun itemFromRow(row: SecondaryResult): ItemState?

    /** [item] re-marked RUNNING — hydrate's preserve-a-live-coroutine merge
     *  ([itemsFromGroup]) uses it on a disk placeholder that can't yet show
     *  the RUNNING status of its in-process worker. */
    protected abstract fun markItemRunning(item: ItemState): ItemState

    /** True when [row] is one of [run]'s items — the per-engine role / run-id
     *  filter in front of [isStaleRow] (MATCH role for Tournament, CELL role
     *  for Judges, CELL role + matching run id for TransRank). Default: every
     *  row of [secondaryKind] (Compare, whose rows are all cells). */
    protected open fun isItemRow(run: RunState?, row: SecondaryResult): Boolean = true

    /** [item] reset to a blank PENDING placeholder — the in-memory mirror of
     *  [clearRowForRerun]. */
    protected abstract fun resetItemToPending(item: ItemState): ItemState

    /** [row] cleared back to a blank placeholder for a re-judge. The default
     *  keeps the row's provider/model (the fixed-judge engines, where the
     *  retry must go to the same judge); the dynamic-host engines override to
     *  also restore their pre-judge sentinel provider/model/agentName. */
    protected open fun clearRowForRerun(row: SecondaryResult): SecondaryResult =
        row.copy(
            content = null, errorMessage = null,
            inputCost = null, outputCost = null, tokenUsage = null, durationMs = null,
            timestamp = System.currentTimeMillis()
        )

    /** Which items the Broken-work "Continue" re-queues — stranded PENDING +
     *  errored. Open so a future adopter can exclude e.g. cooldown-benched
     *  rows (FanOut). */
    protected open fun isBrokenForContinue(item: ItemState): Boolean =
        item.status == BatchItemStatus.PENDING || item.status == BatchItemStatus.ERROR

    /** The Broken-work Continue popup's build-stage label, e.g.
     *  "Re-queuing tournament". */
    protected abstract val requeueBuildLabel: String

    /** Recompute + persist the run's AGGREGATE row from the settled items.
     *  Default no-op for engines without an aggregate row (Compare).
     *  Implementations must swallow their own exceptions — this is called
     *  from `finally` / NonCancellable paths where a throw would escape the
     *  coroutine and crash the app. */
    protected open fun recomputeAggregate(context: Context, runKey: RunKey) {}

    /** [SecondaryResult.id] of [run]'s AGGREGATE row, or null for engines
     *  without one (Compare). Deleted alongside the last item. */
    protected open fun aggregateRowIdOf(run: RunState): String? = null

    /** Rebuild this engine's run(s) for [reportId] from disk and publish them
     *  — already public on every engine; the resume template calls it before
     *  scanning. */
    abstract suspend fun hydrate(context: Context, reportId: String)

    /** Whether [run] can be re-dispatched at all. False for a synthetic
     *  (prompt-unavailable, blank-text) run, which hydrates read-only —
     *  re-running it would fire empty prompts. Checked BEFORE any row is
     *  cleared, so an unresumable run never strands cleared rows. */
    protected abstract fun canRedispatch(context: Context, run: RunState): Boolean

    /** Engine-specific repair when a resume scan finds no stale rows at all
     *  (e.g. Tournament's truncated-matrix re-sync). Default no-op. */
    protected open fun onNoStaleRows(context: Context, runKey: RunKey) {}

    /** Map stale / freshly-cleared [rows] back to the engine's pending items
     *  and dispatch them — own tracer tags + throttled batch. Rows whose
     *  context no longer resolves (agent gone, translation row gone) are
     *  silently skipped. Shared by the resume scan and the rerun paths. */
    protected abstract suspend fun redispatchRows(context: Context, runKey: RunKey, rows: List<SecondaryResult>)

    // ===== Promoted flows (byte-identical across the four engines) =====

    /** The startRun scaffold every engine copied: refuse to double-launch
     *  (returning the live Job), bump the global active-batches counter,
     *  mint a runId, run [body] under the report's log context + tracer
     *  tags, and ALWAYS decrement the counter, finalize leftover rows and
     *  release a stuck build popup on the way out. [body] does the
     *  engine-specific part: enumerate items, persist placeholders,
     *  publish the run, dispatch, recompute. */
    protected fun launchRun(
        context: Context,
        runKey: RunKey,
        buildKey: String?,
        traceCategory: String,
        body: suspend (runId: String) -> Unit,
    ): Job {
        runJobOf(runKey)?.let {
            if (it.isActive) {
                // The UI arms the "Preparing…" build popup BEFORE calling
                // into the engine and relies on this launch's finally to
                // release it. Refusing a double-launch must release it too
                // — otherwise the non-dismissable modal never receives
                // finishBuild/clearBuild and wedges the screen over the
                // still-running batch (whose Cancel button deletes it).
                if (buildKey != null) {
                    val p = appViewModel.batchBuildProgress.value[buildKey]
                    if (p != null && !p.done) appViewModel.clearBuild(buildKey)
                }
                return it
            }
        }
        // A prior (finished) run on this key is superseded, not kept:
        // this launch mints a fresh runId, so the old rows would stay on
        // disk forever — hidden by hydrateNewestRun in every list, yet
        // still feeding the Manage second-results aggregate (one old ❌
        // pinned it at error) and undeletable except with the report.
        // Delete the old run first, rolling its spend into the report's
        // deleted-items tally; the new coroutine joins the disk sweep
        // before dispatching. Captured here, synchronously, so deleteRun
        // grabs the OLD (inactive) run job — not the one launched below.
        val supersededDelete = if (_runs.value[runKey] != null) deleteRun(context, runKey) else null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val reportId = reportIdOf(runKey)
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                supersededDelete?.join()
                withTracerTags(reportId = reportId, category = traceCategory, runId = runId) {
                    body(runId)
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverItems(context, runKey)
                if (buildKey != null) {
                    val p = appViewModel.batchBuildProgress.value[buildKey]
                    if (p != null && !p.done) appViewModel.clearBuild(buildKey)
                }
            }
        }
        registerRunJob(runKey, job)
        return job
    }

    /** The hydrate scaffold of the single-run-per-report engines: list this
     *  kind's rows, group them by [runIdOf] (rows with a blank run id are
     *  ignored), keep the NEWEST group (legacy multi-run rows may exist),
     *  and publish the run [buildRun] makes of it — unless its delete is
     *  still mid-flight. No groups at all drops the run instead. */
    protected suspend fun hydrateNewestRun(
        context: Context,
        reportId: String,
        runKey: RunKey,
        runIdOf: (SecondaryResult) -> String?,
        buildRun: (runId: String, group: List<SecondaryResult>) -> RunState,
    ) {
        val rows = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, secondaryKind)
        }
        val byRun = rows.filter { !runIdOf(it).isNullOrBlank() }.groupBy { runIdOf(it)!! }
        if (byRun.isEmpty()) {
            dropRun(runKey)
            return
        }
        val (runId, group) = byRun.maxByOrNull { (_, g) -> g.maxOf { it.timestamp } }!!
        val run = buildRun(runId, group)
        _runs.update { if (isDeleting(runKey)) it else it + (runKey to run) }
    }

    /** Disk rows → item map for hydrate, preserving a live RUNNING status
     *  from the currently-published run that the disk placeholder can't
     *  show yet. Non-item rows (the AGGREGATE) fall out via [itemFromRow]. */
    protected fun itemsFromGroup(runKey: RunKey, group: List<SecondaryResult>): Map<String, ItemState> {
        val current = _runs.value[runKey]?.items
        return group.mapNotNull { itemFromRow(it) }
            .associateBy { it.key }
            .mapValues { (k, diskItem) ->
                if (diskItem.status == BatchItemStatus.PENDING && current?.get(k)?.status == BatchItemStatus.RUNNING)
                    markItemRunning(diskItem) else diskItem
            }
    }

    /** Cancel + delete the whole run (items + aggregate row), rolling the
     *  spend into the report's deleted-items tally. The cheap part drops
     *  the run from the flow synchronously; the disk sweep runs in the
     *  background (see [deleteRunDeferred]). Open — TransRank overrides
     *  with a disk sweep keyed by its source translation run. */
    open fun deleteRun(context: Context, runKey: RunKey): Job {
        val run = _runs.value[runKey] ?: return appViewModel.viewModelScope.launch { }
        val reportId = reportIdOf(runKey)
        // Capture the live coroutines before the deferred delete drops the run.
        val runJob = runJobOf(runKey)
        val itemJobs = run.items.values.mapNotNull { itemJobOf(it.id) }
        return deleteRunDeferred(appViewModel.viewModelScope, runKey, runJob, itemJobs) {
            // Disk-truth costs: the run was dropped from _runs before the join,
            // so an in-flight item that settled during the cancel never
            // mirrored into this snapshot — the rows are read-then-deleted
            // anyway, so sum what's actually on them.
            val costDelta = run.items.values.sumOf {
                SecondaryResultStorage.get(context, reportId, it.id)?.fullCost() ?: it.totalCost
            }
            run.items.values.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            aggregateRowIdOf(run)?.let { SecondaryResultStorage.delete(context, reportId, it) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }

    /** Stamp an "interrupted" error on a row — unless it already reached a
     *  terminal state on disk (result, error, or duration present), so a row
     *  that settled concurrently is never overwritten. */
    protected fun markRowInterrupted(context: Context, reportId: String, rowId: String, message: String) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null || !current.content.isNullOrBlank() || current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(errorMessage = message, durationMs = 0))
    }

    /** A row that never produced anything — no content, no error, no duration
     *  — and has no live coroutine in this process. The shared stale filter of
     *  every resume scan and run-end finalizer. */
    protected fun isStaleRow(row: SecondaryResult): Boolean =
        row.content.isNullOrBlank() && row.errorMessage == null &&
            row.durationMs == null && !hasItemJob(row.id)

    /** NonCancellable disk-truth mirror for a per-item `finally`: re-read
     *  the row and settle the in-memory item on what's actually persisted,
     *  so a stop / cancel mid-call can't leave it stuck at RUNNING (the
     *  per-screen 3s re-hydrate that used to recover that case is gone). A
     *  deleted row drops the item; an unparseable row terminalizes it. */
    protected suspend fun settleItemFromDisk(context: Context, runKey: RunKey, itemKey: String, rowId: String) {
        withContext(NonCancellable) {
            val saved = SecondaryResultStorage.get(context, reportIdOf(runKey), rowId)
            if (saved == null) dropItem(runKey, itemKey)
            else transitionItem(runKey, itemKey) {
                itemFromRow(saved)
                    ?: terminalizeItem(it, "${itemNoun.replaceFirstChar(Char::uppercase)} row could not be parsed")
            }
        }
    }

    /** Disk half of a type-A bench re-queue: clear the error the failed
     *  attempt persisted so the re-dispatched row reads as a fresh PENDING
     *  placeholder. Callers reset their in-memory item alongside. */
    protected fun clearRowForBenchRequeue(context: Context, reportId: String, rowId: String) {
        SecondaryResultStorage.get(context, reportId, rowId)?.let { saved ->
            SecondaryResultStorage.save(
                context,
                saved.copy(content = null, errorMessage = null, durationMs = null, tokenUsage = null)
            )
        }
    }

    /** Cancel this run's outer Job + every per-item coroutine and JOIN them
     *  (so no in-flight write lands after a re-queue) WITHOUT deleting rows
     *  or dropping the run — the keep-state counterpart of
     *  [cancelAllForReport], used by the Broken-work "Continue". */
    protected suspend fun stopInFlightKeepingState(runKey: RunKey) {
        runJobOf(runKey)?.cancelAndJoin()
        _runs.value[runKey]?.items?.values?.forEach { itemJobOf(it.id)?.cancelAndJoin() }
    }

    /** Row ids whose worker Job is live in THIS process — the read-only
     *  broken-work scan's in-flight exclusion (parallel to
     *  [FanOutEngine.inFlightRowIds]). Empty after a process kill, which is
     *  exactly when leftover PENDING rows are genuinely abandoned. */
    fun inFlightRowIds(): Set<String> = itemJobIds()

    /** Top-level runs currently alive in this process. Covers pre-created
     *  rows that are still waiting for a per-item Job during the build. */
    fun activeRunKeys(): Set<RunKey> = activeRunJobKeys()

    /** Best-effort cancel of every in-flight run/item for [reportId] (called
     *  from the synchronous report-delete path). */
    fun cancelAllForReport(reportId: String) {
        runKeysForReport(reportId).forEach { k ->
            runJobOf(k)?.cancel()
            _runs.value[k]?.items?.values?.forEach { itemJobOf(it.id)?.cancel() }
            _runs.update { it - k }
        }
    }

    /** Re-dispatch every stale row a process kill left PENDING (blank
     *  placeholder on disk, no live Job) — the app-kill recovery and the
     *  regenerate-batch path, one scan per run key of [reportId]. Bounded by
     *  [BatchResume]: a row that can never complete is terminalized after
     *  [BatchResume.MAX_ATTEMPTS] instead of re-dispatching (and re-billing)
     *  forever; [resetAttempts] grants an explicit user re-fire a fresh
     *  budget. The stale filter is sentinel-independent (content blank + no
     *  duration), so an interrupted-after-worker row is still found. */
    fun resumeStaleRunsForReport(context: Context, reportId: String, resetAttempts: Boolean = false): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            // hydrate runs before the scan guard, so it needs its own guard —
            // this launch is a direct child of viewModelScope and no global
            // coroutine exception handler exists, so an uncaught throw here
            // would crash the app (the background resume sweep only join()s
            // this Job, it can't catch it).
            try {
                hydrate(context, reportId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(logTag, "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            for (runKey in runKeysForReport(reportId)) {
                if (!beginResumeScan(runKey)) continue
                try {
                    val run = _runs.value[runKey] ?: continue
                    if (!canRedispatch(context, run)) continue
                    val diskById = SecondaryResultStorage.listForReport(context, reportId, secondaryKind)
                        .filter { isItemRow(run, it) && isStaleRow(it) }
                        .associateBy { it.id }
                    if (diskById.isEmpty()) {
                        onNoStaleRows(context, runKey)
                        continue
                    }
                    val itemsById = run.items.values.associateBy { it.id }
                    val staleRows = run.items.values
                        .filter { it.status == BatchItemStatus.PENDING && it.id in diskById }
                        .mapNotNull { diskById[it.id] }
                    if (resetAttempts) BatchResume.resetAttempts(staleRows.map { it.id })
                    val retryRows = BatchResume.capForRetry(staleRows) { row ->
                        markRowInterrupted(context, reportId, row.id, "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts")
                        itemsById[row.id]?.let { item ->
                            transitionItem(runKey, item.key) {
                                terminalizeItem(it, "Interrupted — no result after resume attempts")
                            }
                        }
                    }
                    if (retryRows.isEmpty()) continue
                    redispatchRows(context, runKey, retryRows)
                    recomputeAggregate(context, runKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Contain the throw — a failed resume just leaves the run
                    // as-is until the next open / sweep.
                    AppLog.w(logTag, "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    endResumeScan(runKey)
                }
            }
        }

    /** Reset the items behind [itemKeys] to blank PENDING placeholders (one
     *  disk read per item feeds both the cost rollup and the clear; the
     *  cleared rows are written back in ONE batched [SecondaryResultStorage.saveAll]
     *  — single storage lock + data-version bump instead of a save per row),
     *  roll the cleared spend into deleted-items, then re-dispatch them via
     *  [redispatchRows]. The build popup (when [buildKey] is set) covers the
     *  reset phase and is released before the dispatch, which keeps running
     *  in the background. */
    protected suspend fun rerunItemsBlocking(context: Context, runKey: RunKey, itemKeys: List<String>, buildKey: String? = null) {
        if (itemKeys.isEmpty()) { buildKey?.let { appViewModel.finishBuild(it) }; return }
        val run = _runs.value[runKey]
            ?: run { buildKey?.let { appViewModel.finishBuild(it) }; return }
        // A synthetic (prompt-unavailable) run can't be re-run — checked
        // BEFORE any row is cleared so it never strands cleared rows.
        if (!canRedispatch(context, run)) { buildKey?.let { appViewModel.finishBuild(it) }; return }
        val reportId = reportIdOf(runKey)
        var clearedCostDelta = 0.0
        val clearedRows = mutableListOf<SecondaryResult>()
        // Build stage: resetting each broken item to a PENDING placeholder is
        // the "Preparing N / M…" phase the Broken-work Continue popup covers.
        if (buildKey != null) appViewModel.beginBuild(buildKey, itemKeys.size, requeueBuildLabel)
        for (k in itemKeys) {
            val item = run.items[k] ?: continue
            val cur = SecondaryResultStorage.get(context, reportId, item.id) ?: continue
            clearedCostDelta += cur.fullCost()
            val cleared = clearRowForRerun(cur)
            transitionItem(runKey, k) { resetItemToPending(it) }
            clearedRows.add(cleared)
        }
        // The counter tracks the batched persist — the slow part; the
        // clear loop above is cached reads + in-memory copies.
        if (clearedRows.isNotEmpty()) SecondaryResultStorage.saveAll(
            context, clearedRows,
            onProgress = { n -> if (buildKey != null) appViewModel.updateBuild(buildKey, n) }
        )
        if (clearedCostDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, clearedCostDelta)
        // Build phase complete — release the popup so the UI navigates to the
        // batch screen while the dispatch below keeps running in the background.
        if (buildKey != null) appViewModel.finishBuild(buildKey)
        if (clearedRows.isEmpty()) return
        redispatchRows(context, runKey, clearedRows)
    }

    /** Rerun the items matching [predicate] — clear → PENDING → re-dispatch —
     *  then recompute the aggregate from the fresh results. */
    protected suspend fun restartItemsWhere(context: Context, runKey: RunKey, predicate: (ItemState) -> Boolean) {
        val run = _runs.value[runKey] ?: return
        rerunItemsBlocking(context, runKey, run.items.values.filter(predicate).map { it.key })
        recomputeAggregate(context, runKey)
    }

    /** Broken-work "Continue": stop this run's in-flight items (keeping the
     *  run + its finished items), then re-queue every broken item (stranded
     *  PENDING + errored) in one batch, driving the build-stage popup off
     *  [buildKey]. Finished items are untouched. */
    fun continueBrokenBatch(context: Context, runKey: RunKey, buildKey: String?): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                stopInFlightKeepingState(runKey)
                hydrate(context, reportIdOf(runKey))
                _runs.value[runKey]?.let { run ->
                    val keys = run.items.values.filter { isBrokenForContinue(it) }.map { it.key }
                    rerunItemsBlocking(context, runKey, keys, buildKey)
                    recomputeAggregate(context, runKey)
                }
            } catch (e: CancellationException) {
                buildKey?.let { appViewModel.clearBuild(it) }
                throw e
            } catch (e: Exception) {
                AppLog.w(logTag, "continue broken batch failed report=${reportIdOf(runKey)}: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // Release the popup so the overlay still opens when there was
                // nothing to re-queue (the normal path finishes it before dispatch).
                buildKey?.let {
                    if (appViewModel.batchBuildProgress.value[it]?.done != true) appViewModel.finishBuild(it)
                }
            }
        }

    /** Delete every item matching [predicate] without re-firing — the
     *  Broken-work "delete errored / unfinished / picked rows" actions.
     *  Cancels + joins the victims' coroutines, deletes their rows (spend
     *  rolled into the report's deleted-items tally, read from the disk row's
     *  fullCost with the in-memory cost as fallback), then either recomputes
     *  the aggregate over what's left or — when nothing is left — deletes the
     *  aggregate row and drops the whole run (an empty run would otherwise
     *  read as never-terminal). */
    // One Mutex per run key — serializes concurrent removeItemsMatching calls
    // on the same run (e.g. a manual "remove failed cells" tap racing the
    // removeModelFromReport cascade) so overlapping victims can't have their
    // cost double-counted into the report's deleted-items tally.
    private val removeLocks = ConcurrentHashMap<RunKey, Mutex>()

    protected suspend fun removeItemsMatching(context: Context, runKey: RunKey, predicate: (ItemState) -> Boolean) {
        removeLocks.getOrPut(runKey) { Mutex() }.withLock {
        val run = _runs.value[runKey] ?: return
        val victims = run.items.values.filter(predicate)
        if (victims.isEmpty()) return
        val reportId = reportIdOf(runKey)
        victims.forEach { itemJobOf(it.id)?.cancelAndJoin() }
        val costDelta = victims.sumOf {
            SecondaryResultStorage.get(context, reportId, it.id)?.fullCost() ?: it.totalCost
        }
        victims.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
        val victimKeys = victims.map { it.key }.toSet()
        if ((run.items - victimKeys).isEmpty()) {
            aggregateRowIdOf(run)?.let { SecondaryResultStorage.delete(context, reportId, it) }
            dropRun(runKey)
        } else {
            _runs.update { runs ->
                val cur = runs[runKey] ?: return@update runs
                runs + (runKey to copyWithItems(cur, cur.items - victimKeys))
            }
            recomputeAggregate(context, runKey)
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }

    /** Does [item] belong to the report model ([providerId], [model])?
     *  [agentIds] are the report agentIds carrying that (provider, model),
     *  for engines whose items reference the answer by agentId (Compare:
     *  the scored agent; Tournament: either competitor) rather than by the
     *  judge/translator provider+model (JudgeEval, TransRank). Default never
     *  matches — an engine opts in by overriding. Used only to propagate a
     *  report-model removal across batches when "Use report models" is on. */
    protected open fun itemMatchesModel(
        item: ItemState, providerId: String, model: String, agentIds: Set<String>
    ): Boolean = false

    /** Drop every item belonging to report model ([providerId], [model]) from
     *  all of [reportId]'s runs — the batch half of the "Use report models"
     *  unified-removal: cancels the items' coroutines, deletes their rows,
     *  rolls spend into the deleted-items tally, and recomputes/drops each run.
     *  Idempotent and a no-op for engines that don't override [itemMatchesModel]. */
    suspend fun removeModelFromReport(
        context: Context, reportId: String, providerId: String, model: String, agentIds: Set<String>
    ) {
        runKeysForReport(reportId).forEach { key ->
            if (_runs.value.containsKey(key)) {
                removeItemsMatching(context, key) { itemMatchesModel(it, providerId, model, agentIds) }
            }
        }
    }

    /** Run-end finalizer for startRun's `finally`: stamp every leftover stale
     *  row "Interrupted" (disk + in-memory), clear its [BatchResume] attempt
     *  counter, and recompute the aggregate from the settled items so an
     *  interrupted run doesn't leave a stale ranking. NonCancellable — it runs
     *  while the launching coroutine is being cancelled. */
    protected suspend fun finalizeLeftoverItems(context: Context, runKey: RunKey) {
        withContext(NonCancellable) {
            val reportId = reportIdOf(runKey)
            val run = _runs.value[runKey]
            val leftover = SecondaryResultStorage.listForReport(context, reportId, secondaryKind)
                .filter { isItemRow(run, it) && isStaleRow(it) }
            val itemsById = _runs.value[runKey]?.items?.values?.associateBy { it.id }.orEmpty()
            BatchResume.finalizeLeftover(leftover) { row ->
                markRowInterrupted(context, reportId, row.id, "Interrupted — run stopped before this $itemNoun finished")
                itemsById[row.id]?.let { item ->
                    transitionItem(runKey, item.key) {
                        if (it.status == BatchItemStatus.PENDING || it.status == BatchItemStatus.RUNNING)
                            terminalizeItem(it, "Interrupted")
                        else it
                    }
                }
            }
            recomputeAggregate(context, runKey)
        }
    }
}
