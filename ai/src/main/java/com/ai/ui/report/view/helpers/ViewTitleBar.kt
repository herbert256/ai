package com.ai.ui.report.view.helpers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateHome
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.LocalNavigateToHelp

/**
 * The View family's own 3-column top bar — a self-contained copy of the
 * old `shared.ViewScreenTitleBar`, kept under `report/view/helpers` so
 * the View subsystem owns its chrome and shares no code with the app's
 * generic title bar.
 *
 * Three stacked rows: AI logo (left → Home) · centred white screen
 * title + orange report title + optional green subject · ❓ help (right).
 * Horizontal swipe loads the prev/next report of the same View kind; a
 * transient pill shows "Loading report" / "No more reports".
 *
 * Instead of the generic `LocalBottomIconState`, it publishes a
 * [ViewBottomBarSpec] into [LocalViewBottomBar] while mounted, so
 * AppNavHost renders the View-owned [ViewBottomBar] (centred 🔧).
 */
@Composable
fun ViewTitleBar(
    reportTitle: String?,
    screenTitle: String?,
    subject: String?,
    helpTopic: String,
    onBack: () -> Unit,
    /** Optional 🔧 manage hook for the View bottom bar. Null on
     *  drill-deeper screens (FanInModel / FanOutPair / Icons) → the
     *  bottom bar renders empty. */
    onOpenManage: (() -> Unit)? = null,
    /** Optional override for the centre-title tap target. Null → falls
     *  back to [LocalNavigateToCurrentReport] then [onBack]. */
    onTitleClick: (() -> Unit)? = null,
    /** Horizontal swipe handlers; return true if a matching prev/next
     *  report was found. Null disables the swipe (drill-deeper screens). */
    onSwipePrev: (() -> Boolean)? = null,
    onSwipeNext: (() -> Boolean)? = null
) {
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val logoInteractionSource = remember { MutableInteractionSource() }
    val effectiveLogoClick: () -> Unit = { navigateHome() }
    // Publish the View bottom-bar spec while mounted (always non-null so
    // the View bar — not the generic one — renders for this screen; the
    // 🔧 itself only shows when onOpenManage is non-null). Identity check
    // on dispose avoids clobbering the next screen's publication.
    val viewBottomBarState = LocalViewBottomBar.current
    if (viewBottomBarState != null) {
        val spec = ViewBottomBarSpec(onManage = onOpenManage)
        SideEffect { viewBottomBarState.value = spec }
        DisposableEffect(Unit) {
            onDispose { if (viewBottomBarState.value === spec) viewBottomBarState.value = null }
        }
    }
    // Transient pill state ("Loading report" / "No more reports").
    val swipeStatus = remember { mutableStateOf<String?>(null) }
    val statusTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(statusTick.intValue) {
        if (swipeStatus.value != null) {
            kotlinx.coroutines.delay(1000)
            swipeStatus.value = null
        }
    }
    val swipeDensity = LocalDensity.current
    val swipeThresholdPx = with(swipeDensity) { 80.dp.toPx() }
    val swipeDragX = remember { mutableFloatStateOf(0f) }
    val swipeEnabled = onSwipePrev != null || onSwipeNext != null
    Box(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val shift = 16.dp.roundToPx()
        layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
            placeable.place(0, -shift)
        }
    }) {
        Row(
            modifier = Modifier
                .then(
                    if (swipeEnabled) {
                        Modifier.pointerInput(onSwipePrev, onSwipeNext) {
                            detectHorizontalDragGestures(
                                onDragStart = { swipeDragX.floatValue = 0f },
                                onDragEnd = {
                                    val dx = swipeDragX.floatValue
                                    when {
                                        dx > swipeThresholdPx -> {
                                            swipeStatus.value = "Loading report"
                                            statusTick.intValue++
                                            val found = onSwipePrev?.invoke() ?: false
                                            if (!found) {
                                                swipeStatus.value = "No more reports"
                                                statusTick.intValue++
                                            }
                                        }
                                        dx < -swipeThresholdPx -> {
                                            swipeStatus.value = "Loading report"
                                            statusTick.intValue++
                                            val found = onSwipeNext?.invoke() ?: false
                                            if (!found) {
                                                swipeStatus.value = "No more reports"
                                                statusTick.intValue++
                                            }
                                        }
                                    }
                                    swipeDragX.floatValue = 0f
                                },
                                onDragCancel = { swipeDragX.floatValue = 0f },
                                onHorizontalDrag = { _, d -> swipeDragX.floatValue += d }
                            )
                        }
                    } else Modifier
                )
                .layout { measurable, constraints ->
                    val outsetPx = 16.dp.roundToPx()
                    val widenedMax = if (constraints.maxWidth == Constraints.Infinity) {
                        constraints.maxWidth
                    } else {
                        constraints.maxWidth + outsetPx * 2
                    }
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = (constraints.minWidth + outsetPx * 2)
                                .coerceAtMost(widenedMax),
                            maxWidth = widenedMax
                        )
                    )
                    layout((placeable.width - outsetPx * 2).coerceAtLeast(0), placeable.height) {
                        placeable.place(-outsetPx, 0)
                    }
                }
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.brand_glyph),
                contentDescription = "Home",
                modifier = Modifier
                    .size(76.dp)
                    .clickable(
                        interactionSource = logoInteractionSource,
                        indication = null,
                        onClick = effectiveLogoClick
                    )
            )
            val navToCurrentReport = LocalNavigateToCurrentReport.current
            val titleClick: () -> Unit = onTitleClick ?: navToCurrentReport ?: onBack
            var bigSizeFits by remember(screenTitle, reportTitle) { mutableStateOf(true) }
            val hasScreenTitle = !screenTitle.isNullOrBlank()
            val topText = if (hasScreenTitle) screenTitle!! else reportTitle.orEmpty()
            Column(
                modifier = Modifier.weight(1f).clickable { titleClick() },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = topText,
                    color = Color.White,
                    fontSize = if (bigSizeFits) 24.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    onTextLayout = { result ->
                        if (bigSizeFits && result.hasVisualOverflow) {
                            bigSizeFits = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .offset(y = (-4).dp)
                )
                if (hasScreenTitle && !reportTitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = reportTitle.orEmpty(),
                        color = AppColors.Orange,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!subject.isNullOrBlank()) {
                    Text(
                        text = subject,
                        color = AppColors.Green,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Text(
                text = "❓",
                fontSize = 52.sp,
                color = AppColors.Blue,
                modifier = Modifier
                    .offset(x = 8.dp, y = (-4).dp)
                    .clickable { navigateHelp(helpTopic) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
        val status = swipeStatus.value
        if (status != null) {
            Text(
                text = status,
                color = Color.White,
                fontSize = 13.sp,
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
