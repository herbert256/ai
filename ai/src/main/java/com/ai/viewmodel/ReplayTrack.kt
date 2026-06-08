package com.ai.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * One "variation replay" track — a keyed `StateFlow<Map<String, S>>` of replay
 * states plus the matching per-key coroutine map, with the register / set /
 * update / cancel / prefix-clear plumbing that the four replay flows
 * (temperature / reasoning-effort / web-search / prompt-edit) each hand-rolled
 * in `ReportViewModel`, `FanOutEngine`, and `MetaEditManager` (audit R03).
 *
 * Scope: this owns ONLY the state/job plumbing — the per-site dispatch (rebuild
 * the call, fire it) and accept logic stay where they are, since each site
 * replays a different target (report agent vs fan-out pair vs meta row). Each
 * site holds one `ReplayTrack` per mode instead of a `MutableStateFlow` + a
 * `ConcurrentHashMap<String, Job>` + bespoke update/cancel helpers.
 */
class ReplayTrack<S> {
    private val _states = MutableStateFlow<Map<String, S>>(emptyMap())
    val states: StateFlow<Map<String, S>> = _states.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun get(key: String): S? = _states.value[key]

    /** Set/replace the state at [key]. */
    fun set(key: String, state: S) { _states.update { it + (key to state) } }

    /** Drop the state at [key] (leaves any job alone). */
    fun drop(key: String) { _states.update { it - key } }

    /** Apply [transform] to the state at [key] if present; no-op otherwise. */
    fun update(key: String, transform: (S) -> S) {
        _states.update { it[key]?.let { s -> it + (key to transform(s)) } ?: it }
    }

    /** Record [job] for [key]; self-cleans from the map on completion. Callers
     *  cancel any prior job via [cancelJob] before re-dispatching. */
    fun registerJob(key: String, job: Job) {
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
    }

    /** Cancel [key]'s job, leaving its state untouched (re-dispatch path). */
    fun cancelJob(key: String) { jobs.remove(key)?.cancel() }

    /** Cancel [key]'s job AND drop its state (the user-facing "clear"). */
    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        _states.update { it - key }
    }

    /** Cancel every job and drop every state whose key starts with [prefix]
     *  (per-report teardown). */
    fun cancelByPrefix(prefix: String) {
        jobs.keys.filter { it.startsWith(prefix) }.forEach { jobs.remove(it)?.cancel() }
        _states.update { it.filterKeys { k -> !k.startsWith(prefix) } }
    }

    fun jobActive(key: String): Boolean = jobs[key]?.isActive == true
}
