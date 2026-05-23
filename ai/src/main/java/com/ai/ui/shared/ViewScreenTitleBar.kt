package com.ai.ui.shared

import androidx.compose.runtime.Composable

/**
 * The standalone View-family title bar (Model Info, provider / flock /
 * swarm info, Help, HTML preview). A thin wrapper over the single
 * shared [AppTopBarChrome] — report glyph (the active / last-viewed
 * report) on the left, white screen title / orange report title /
 * green subject in the centre, AI logo → Home on the right.
 *
 * Publishes a [com.ai.ui.report.view.helpers.ViewBottomBarSpec] into
 * [com.ai.ui.report.view.helpers.LocalViewBottomBar] so these screens
 * get the View bottom bar (centred 🔧 when [onOpenManage] is non-null +
 * right-aligned ❓ help) instead of the generic strip.
 */
@Composable
fun ViewScreenTitleBar(
    reportTitle: String?,
    screenTitle: String?,
    subject: String?,
    helpTopic: String,
    onBack: () -> Unit,
    onOpenManage: (() -> Unit)? = null,
    /** Optional override for the report-icon / title tap target. Null →
     *  [LocalNavigateToCurrentReport] (open the active report's View
     *  hub) then [onBack]. */
    onTitleClick: (() -> Unit)? = null,
    /** Horizontal swipe handlers; return true if a matching prev/next
     *  report was found. Null disables the swipe. */
    onSwipePrev: (() -> Boolean)? = null,
    onSwipeNext: (() -> Boolean)? = null
) {
    // Publish the View-owned bottom-bar spec while mounted (centred 🔧
    // when onOpenManage is set + right-aligned ❓ help). AppNavHost
    // prefers ViewBottomBar whenever this spec is non-null.
    val viewBottomBarState = com.ai.ui.report.view.helpers.LocalViewBottomBar.current
    if (viewBottomBarState != null) {
        androidx.compose.runtime.SideEffect {
            viewBottomBarState.value = com.ai.ui.report.view.helpers.ViewBottomBarSpec(
                onManage = onOpenManage, helpTopic = helpTopic
            )
        }
        androidx.compose.runtime.DisposableEffect(viewBottomBarState) {
            onDispose { viewBottomBarState.value = null }
        }
    }
    val navToCurrentReport = LocalNavigateToCurrentReport.current
    val titleClick: () -> Unit = onTitleClick ?: navToCurrentReport ?: onBack
    AppTopBarChrome(
        screenTitle = screenTitle,
        secondLine = reportTitle,
        thirdLine = subject,
        reportIcon = LocalReportIcon.current?.takeIf { it.isNotBlank() } ?: "📄",
        onReportIconClick = titleClick,
        onTitleClick = titleClick,
        onSwipePrev = onSwipePrev,
        onSwipeNext = onSwipeNext
    )
}
