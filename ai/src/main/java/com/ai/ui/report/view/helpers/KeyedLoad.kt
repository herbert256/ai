package com.ai.ui.report.view.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Load data for [key] off the main thread, reload whenever any of
 * [reloadOn] emits, and return it — or null while nothing has loaded for
 * THIS key yet.
 *
 * Built for screens that swap their target in place (a title-bar report
 * swipe, a pager page, a language switch). A bare
 * `produceState(initial, id, dataVersion)` gets two things wrong there:
 *  - produceState keeps its previous value until the new load lands, so
 *    the previous target's content renders under the new target's header;
 *  - every version bump restarts the producer and discards the in-flight
 *    load, so while the new target is busy (a running batch bumps several
 *    times a second) its load never lands and the old content stays.
 * Here the producer is keyed on [key] alone; version bumps are conflated
 * into one follow-up load after the current one finishes, and a value is
 * only returned when it was loaded for the current [key].
 *
 * [key] must cover every input [load] reads (report id, result id,
 * language, …) — the returned value is matched against it.
 */
@Composable
fun <K : Any, T : Any> rememberKeyedLoad(
    key: K,
    vararg reloadOn: Flow<*>,
    load: suspend (K) -> T
): T? {
    val currentLoad by rememberUpdatedState(load)
    val flows = reloadOn.toList()
    val state = produceState<Pair<K, T>?>(initialValue = null, key) {
        val trigger: Flow<Unit> = if (flows.isEmpty()) flowOf(Unit) else combine(flows) { }
        trigger.conflate().collect {
            value = key to withContext(Dispatchers.IO) { currentLoad(key) }
        }
    }
    return state.value?.takeIf { it.first == key }?.second
}

/** Box for a [rememberKeyedLoad] whose result can itself be null — keeps
 *  "loaded, and the answer is null" apart from "not loaded for this key". */
data class LoadedValue<T>(val value: T?)
