package com.ai.viewmodel

import android.content.Context
import com.ai.data.BatchItem
import com.ai.data.BatchItemStatus
import com.ai.data.BatchRun
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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

    /** True when [row] is one of [run]'s items — the per-engine role / run-id
     *  filter in front of [isStaleRow] (MATCH role for Tournament, CELL role
     *  for Judges, CELL role + matching run id for TransRank). Default: every
     *  row of [secondaryKind] (Compare, whose rows are all cells). */
    protected open fun isItemRow(run: RunState?, row: SecondaryResult): Boolean = true

    /** Recompute + persist the run's AGGREGATE row from the settled items.
     *  Default no-op for engines without an aggregate row (Compare).
     *  Implementations must swallow their own exceptions — this is called
     *  from `finally` / NonCancellable paths where a throw would escape the
     *  coroutine and crash the app. */
    protected open fun recomputeAggregate(context: Context, runKey: RunKey) {}

    // ===== Promoted flows (byte-identical across the four engines) =====

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
            BatchResume.finalizeLeftover(leftover) { row ->
                markRowInterrupted(context, reportId, row.id, "Interrupted — run stopped before this $itemNoun finished")
                _runs.value[runKey]?.items?.values?.firstOrNull { it.id == row.id }?.let { item ->
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
