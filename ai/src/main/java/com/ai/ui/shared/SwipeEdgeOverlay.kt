package com.ai.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Wraps a `HorizontalPager` in a Box that surfaces a transient
 * "No more <label>" pill at the top-centre when the user tries to
 * swipe past the pager's first or last page. Mirrors the Main View
 * screen's swipe-past-edge pill (the one anchored over the View
 * tile grid in [com.ai.ui.report.ViewAiReportScreen]). The pill
 * fades out after one second and re-arms on the next over-swipe.
 *
 * The detection runs at [PointerEventPass.Final] so it never
 * consumes drags away from the pager — the pager still owns its
 * own gesture stream. We just observe the per-pointer delta total
 * and, on release, fire the overlay when:
 *   • the total horizontal drag exceeded a small threshold AND
 *   • the pager can't scroll further in that direction
 *     (`!canScrollForward` for left-drag, `!canScrollBackward`
 *     for right-drag).
 *
 * Single-page pagers ([PagerState.pageCount] = 1) report
 * `canScrollForward == false && canScrollBackward == false`, so a
 * past-edge swipe on a translation-less Meta / Fan-in row still
 * surfaces the pill.
 */
@Composable
fun SwipeEdgeNoMoreOverlay(
    pagerState: PagerState,
    noMoreLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var status by remember { mutableStateOf<String?>(null) }
    // tick bumps every time the pill is re-armed so the auto-
    // dismissal coroutine restarts its timer instead of being
    // eaten by a stale delay.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        if (status != null) {
            delay(1000)
            status = null
        }
    }
    val density = LocalDensity.current
    val thresholdPx = with(density) { 60.dp.toPx() }
    Box(
        modifier = modifier.pointerInput(pagerState) {
            // Accumulator scoped to this pointerInput coroutine —
            // survives across pointer events but is recreated when
            // pagerState changes (so a re-key safely resets).
            var dragTotalX = 0f
            awaitPointerEventScope {
                while (true) {
                    // Pump pointer events at Final pass so the
                    // pager has already had its shot at consuming
                    // the drag — we just observe.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed) {
                        dragTotalX += change.positionChangeIgnoreConsumed().x
                    } else if (change.changedToUp()) {
                        if (dragTotalX < -thresholdPx && !pagerState.canScrollForward) {
                            status = noMoreLabel
                            tick++
                        } else if (dragTotalX > thresholdPx && !pagerState.canScrollBackward) {
                            status = noMoreLabel
                            tick++
                        }
                        dragTotalX = 0f
                    }
                }
            }
        }
    ) {
        content()
        status?.let { s ->
            Text(
                text = s,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.SurfaceDark.copy(alpha = 0.95f))
                    .border(1.dp, AppColors.Blue.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}
