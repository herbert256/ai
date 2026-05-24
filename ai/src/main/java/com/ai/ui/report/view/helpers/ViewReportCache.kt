package com.ai.ui.report.view.helpers

import android.content.Context
import com.ai.data.Report
import com.ai.data.ReportStorage

/**
 * Tiny LRU cache of recently-viewed reports, used by the read-only View
 * subsystem so navigating between view sub-screens (Costs / Prompt /
 * Meta / Fan / …) of the same report doesn't re-parse the report JSON
 * (incl. base64 images) from disk on every open.
 *
 * Holds a small number of entries (not just one) so a sub-View overlay
 * (e.g. Translate reading `currentReportId`) and the parent grid reading
 * a different report id — common mid-swipe, or a Costs view of report A
 * while the grid restored report B — don't keep evicting each other and
 * degrade the optimisation to a re-parse on every alternating access.
 *
 * Staleness-safe: each entry is keyed by reportId AND the on-disk file's
 * last-modified timestamp, so any Manage-side edit / regenerate / icon
 * write (which rewrites the file) is detected and forces a fresh parse.
 * The mtime stat is far cheaper than the full JSON parse.
 */
object ViewReportCache {
    private const val CAPACITY = 3
    private data class Entry(val mtime: Long, val report: Report?)

    // Access-ordered LRU map (eldest = least-recently-used first).
    private val cache = object : LinkedHashMap<String, Entry>(CAPACITY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > CAPACITY
    }

    @Synchronized
    fun get(context: Context, reportId: String): Report? {
        val mtime = ReportStorage.reportLastModified(context, reportId)
        val hit = cache[reportId]
        if (hit != null && hit.mtime == mtime && hit.report != null) {
            return hit.report
        }
        val report = ReportStorage.getReport(context, reportId)
        cache[reportId] = Entry(mtime, report)
        return report
    }
}
