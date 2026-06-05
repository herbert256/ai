package com.ai.viewmodel

import com.ai.data.BatchItem
import com.ai.data.BatchRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
abstract class BatchEngine<RunKey, ItemKey, ItemState : BatchItem<ItemKey>, RunState : BatchRun<ItemKey, ItemState>> {

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
}
