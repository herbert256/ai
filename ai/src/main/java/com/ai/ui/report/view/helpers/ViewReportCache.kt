package com.ai.ui.report.view.helpers

import android.content.Context
import com.ai.data.Report
import com.ai.data.ReportStorage

/**
 * Tiny single-slot cache of the currently-viewed report, used by the
 * read-only View subsystem so navigating between view sub-screens
 * (Costs / Prompt / Meta / Fan / …) of the same report doesn't re-parse
 * the report JSON (incl. base64 images) from disk on every open.
 *
 * Staleness-safe: keyed by reportId AND the on-disk file's
 * last-modified timestamp, so any Manage-side edit / regenerate / icon
 * write (which rewrites the file) is detected and forces a fresh parse.
 * The mtime stat is far cheaper than the full JSON parse.
 */
object ViewReportCache {
    private var cachedId: String? = null
    private var cachedMtime: Long = 0L
    private var cached: Report? = null

    @Synchronized
    fun get(context: Context, reportId: String): Report? {
        val mtime = ReportStorage.reportLastModified(context, reportId)
        if (cachedId == reportId && cachedMtime == mtime && cached != null) {
            return cached
        }
        val report = ReportStorage.getReport(context, reportId)
        cachedId = reportId
        cachedMtime = mtime
        cached = report
        return report
    }
}
