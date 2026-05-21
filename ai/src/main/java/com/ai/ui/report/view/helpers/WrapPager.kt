package com.ai.ui.report.view.helpers

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable

/** Map a (potentially wrapping/infinite) pager page to the real item
 *  index in `[0, realCount)`. Returns 0 when there are no items. */
fun Int.wrapTo(realCount: Int): Int =
    if (realCount > 0) ((this % realCount) + realCount) % realCount else 0

// Constant span (independent of item count) so a pager whose item count
// changes over its lifetime (e.g. the fan-out responder pager, which
// re-counts per initiator) never overflows: every wrapped target page
// stays comfortably inside [0, SPAN). SPAN is huge so an actual edge is
// thousands of swipes away.
private const val SPAN = 100_000
private const val CENTER = 50_000

/**
 * A [PagerState] that wraps both ways so the user can swipe forever.
 * With more than one item it uses a huge constant page span and a centred
 * start aligned so `currentPage.wrapTo(realCount) == initialIndex`; with
 * ≤1 item it stays a normal single/empty pager (nothing to wrap, so the
 * body swipe still works over that region). Content must index via
 * [wrapTo] (always re-reading the *live* item count).
 */
@Composable
fun rememberWrapPager(realCount: Int, initialIndex: Int = 0): PagerState {
    val wrap = realCount > 1
    val n = realCount.coerceAtLeast(1)
    val start = if (wrap) CENTER - (CENTER % n) + initialIndex.coerceIn(0, n - 1) else 0
    return rememberPagerState(initialPage = start, pageCount = { if (wrap) SPAN else n })
}

/** Centre page of a wrapping pager aligned to [index] (for the live item
 *  count), for `LaunchedEffect { scrollToPage(wrapCenterPage(count, idx)) }`. */
fun wrapCenterPage(realCount: Int, index: Int): Int {
    val n = realCount.coerceAtLeast(1)
    return if (realCount > 1) CENTER - (CENTER % n) + index.coerceIn(0, n - 1) else 0
}
