package com.ai.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Monotonic counter bumped on every secondary-result write / delete
 *  (the single choke point all of translation / fan-out / meta / fan-in
 *  pass through). Compose screens that load secondary data with
 *  produceState observe this via collectAsState and fold it into their
 *  key, so rows that complete or are deleted while the screen stays
 *  mounted refresh in place instead of going stale until a remount.
 *
 *  The legacy [version] flow is kept for callers that do not know their
 *  report scope. Screens that do know it should use [versionFor], so a
 *  large batch on one report does not make unrelated report screens
 *  re-read their already-mounted secondary data on every completed row. */
object SecondaryDataVersion {
    private val lock = Any()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val reportVersions = HashMap<String, MutableStateFlow<Int>>()
    private val kindVersions = HashMap<String, MutableStateFlow<Int>>()

    fun versionFor(reportId: String?, kind: SecondaryKind? = null): StateFlow<Int> {
        val id = reportId?.takeIf { it.isNotBlank() } ?: return version
        return synchronized(lock) {
            if (kind == null) {
                reportVersions.getOrPut(id) { MutableStateFlow(0) }
            } else {
                kindVersions.getOrPut(kindKey(id, kind)) { MutableStateFlow(0) }
            }
        }
    }

    fun bump() {
        _version.update { it + 1 }
    }

    fun bump(reportId: String?, kind: SecondaryKind?) {
        val id = reportId?.takeIf { it.isNotBlank() } ?: return bump()
        synchronized(lock) {
            reportVersions[id]?.update { it + 1 }
            if (kind != null) kindVersions[kindKey(id, kind)]?.update { it + 1 }
        }
    }

    fun bumpReport(reportId: String?) {
        val id = reportId?.takeIf { it.isNotBlank() } ?: return bump()
        synchronized(lock) {
            reportVersions[id]?.update { it + 1 }
            kindVersions.forEach { (key, flow) ->
                if (key.startsWith("$id|")) flow.update { it + 1 }
            }
        }
    }

    fun bumpMany(scopes: Iterable<Pair<String, SecondaryKind>>) {
        val reportIds = LinkedHashSet<String>()
        val kinds = LinkedHashSet<Pair<String, SecondaryKind>>()
        for ((reportId, kind) in scopes) {
            if (reportId.isBlank()) continue
            reportIds += reportId
            kinds += reportId to kind
        }
        if (reportIds.isEmpty()) return
        synchronized(lock) {
            reportIds.forEach { reportVersions[it]?.update { v -> v + 1 } }
            kinds.forEach { (reportId, kind) ->
                kindVersions[kindKey(reportId, kind)]?.update { it + 1 }
            }
        }
    }

    private fun kindKey(reportId: String, kind: SecondaryKind): String = "$reportId|${kind.name}"
}
