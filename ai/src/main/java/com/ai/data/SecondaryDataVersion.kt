package com.ai.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Monotonic counter bumped on every secondary-result write / delete
 *  (the single choke point all of translation / fan-out / meta / fan-in
 *  pass through). Compose screens that load secondary data with
 *  produceState observe this via collectAsState and fold it into their
 *  key, so rows that complete or are deleted while the screen stays
 *  mounted refresh in place instead of going stale until a remount.
 *
 *  Global (like UiState.iconRefreshTick) rather than per-report: a
 *  cross-report bump just re-reads the open report's already-cached
 *  rows, which is cheap and harmless. */
object SecondaryDataVersion {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    fun bump() { _version.update { it + 1 } }
}
