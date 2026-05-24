package com.ai.ui.helpers
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*

import android.content.Context
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage

/**
 * Per-screen filter used by [ViewScreenTitleBar]'s swipe gesture to
 * skip reports that don't carry the currently-active sub-View kind.
 *
 * Walks the newest-first report id list from the current report
 * outward in the chosen direction and stops at the first candidate
 * that satisfies the filter. Drill-deeper screens (FanInModel,
 * FanOutPair, IconsView) opt out by simply not passing swipe
 * lambdas to the title bar.
 */
sealed class ViewSwipeFilter {
    /** Main / Costs / Prompt / Reports — every report matches. */
    object Any : ViewSwipeFilter()
    /** Rerank / Moderation — match by SecondaryResult.kind. */
    data class HasKind(val kind: SecondaryKind) : ViewSwipeFilter()
    /** Meta / Fan-in / Fan-out — match by metaPromptName, with an
     *  optional fan-in / fan-out discriminator. */
    data class HasMeta(
        val metaPromptName: String,
        val requireFanIn: Boolean = false,
        val requireFanOut: Boolean = false,
    ) : ViewSwipeFilter()
    /** Translate — the kind-only variant of [HasKind] returns the
     *  first row's translationRunId so [TranslateViewScreen] can
     *  re-target on swap. */
    object Translate : ViewSwipeFilter()
}

data class SwipeMatch(
    val reportId: String,
    /** id of the first matching SecondaryResult row on the new
     *  report. Null for [ViewSwipeFilter.Any]. */
    val resultId: String? = null,
    /** First TRANSLATE row's translationRunId on the new report,
     *  populated only for [ViewSwipeFilter.Translate]. */
    val translationRunId: String? = null,
)

enum class SwipeDirection { Prev, Next }

/**
 * Walk [reportIdsNewestFirst] starting from [currentReportId] in the
 * chosen direction; return the first candidate whose secondary
 * results satisfy [filter], or null if none.
 *
 * Direction convention matches the existing Main View swipe + the
 * Manage screen's < / > chevrons:
 *  - Prev = older = higher index in the newest-first list.
 *  - Next = newer = lower index.
 */
fun findSwipeMatch(
    context: Context,
    reportIdsNewestFirst: List<String>,
    currentReportId: String,
    direction: SwipeDirection,
    filter: ViewSwipeFilter,
): SwipeMatch? {
    val n = reportIdsNewestFirst.size
    if (n == 0) return null
    val currentIdx = reportIdsNewestFirst.indexOf(currentReportId)
    if (currentIdx < 0) return null
    val step = if (direction == SwipeDirection.Prev) +1 else -1
    // Walk all n positions, wrapping modulo the list size. For the
    // unfiltered [ViewSwipeFilter.Any] mode the k = n step lands back on
    // the current report so a lone report wraps to itself instead of
    // dead-ending. For FILTERED modes we stop before k = n: if the only
    // matching report is the current one, return null so the swipe is a
    // genuine no-op (no needless restoreCompletedReport reload + flash).
    val lastK = if (filter is ViewSwipeFilter.Any) n else n - 1
    for (k in 1..lastK) {
        val i = (((currentIdx + step * k) % n) + n) % n
        val match = matchOn(context, reportIdsNewestFirst[i], filter)
        if (match != null) return match
    }
    return null
}

private fun matchOn(
    context: Context,
    reportId: String,
    filter: ViewSwipeFilter,
): SwipeMatch? = when (filter) {
    is ViewSwipeFilter.Any -> SwipeMatch(reportId = reportId)
    is ViewSwipeFilter.HasKind -> {
        val rows = SecondaryResultStorage.listForReport(context, reportId, filter.kind)
        rows.firstOrNull()?.let { SwipeMatch(reportId = reportId, resultId = it.id) }
    }
    is ViewSwipeFilter.HasMeta -> {
        val rows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
        val pick: SecondaryResult? = rows.firstOrNull { r ->
            r.metaPromptName == filter.metaPromptName &&
                (!filter.requireFanIn || r.fanInOf != null) &&
                (!filter.requireFanOut || r.fanOutSourceAgentId != null)
        }
        pick?.let { SwipeMatch(reportId = reportId, resultId = it.id) }
    }
    is ViewSwipeFilter.Translate -> {
        val rows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSLATE)
        val pick = rows.firstOrNull { it.translationRunId != null } ?: rows.firstOrNull()
        pick?.let {
            SwipeMatch(
                reportId = reportId,
                resultId = it.id,
                // Always hand back a usable grouping id — for a legacy row
                // with a null translationRunId, the synthetic "lang:<lang>"
                // id; otherwise the caller would skip the update and leave a
                // stale cross-report run id pointing at the old report.
                translationRunId = translationRunGroupingId(it),
            )
        }
    }
}
