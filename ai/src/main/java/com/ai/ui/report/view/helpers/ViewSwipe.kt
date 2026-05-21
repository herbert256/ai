package com.ai.ui.report.view.helpers

import androidx.compose.ui.Modifier
import com.ai.ui.shared.horizontalSwipeNavigation

/**
 * Body swipe for View report screens: a horizontal swipe anywhere on the
 * screen body (the blank space below the content, plus any area an inner
 * HorizontalPager doesn't consume) fires the screen's prev/next action —
 * the same thing the [ViewTitleBar] swipe does. Right = previous,
 * left = next (matching the title-bar convention). No edge toasts; the
 * handlers themselves decide whether anything happens at the ends.
 *
 * Inner [androidx.compose.foundation.pager.HorizontalPager]s and the
 * [ViewTitleBar] keep their own gestures — children consume horizontal
 * drags in their bounds first, so this only fires elsewhere.
 */
fun Modifier.viewBodySwipe(key: Any?, onPrev: () -> Unit, onNext: () -> Unit): Modifier =
    this.horizontalSwipeNavigation(
        key1 = key,
        atFirst = false,
        atLast = false,
        onSwipeLeft = onNext,
        onSwipeRight = onPrev
    )
