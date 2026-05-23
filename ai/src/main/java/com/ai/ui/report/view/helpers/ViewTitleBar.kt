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
import com.ai.ui.shared.AiLogoButton
import com.ai.ui.shared.ReportGlyphIcon
import com.ai.ui.shared.LocalNavigateHome
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.LocalReportIcon
import com.ai.ui.shared.LocalNavigateToHelp

/**
 * The View family's own 3-column top bar — a self-contained copy of the
 * old `shared.ViewScreenTitleBar`, kept under `report/view/helpers` so
 * the View subsystem owns its chrome and shares no code with the app's
 * generic title bar.
 *
 * Three stacked rows: AI logo (left → Home) · centred white screen
 * title + orange report title + optional green subject · ❓ help (right).
 * Horizontal swipe loads the prev/next report of the same View kind
 * silently (no transient message).
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
    onSwipeNext: (() -> Boolean)? = null,
    /** Optional left-aligned ☝️/✋ "one vs all" bottom-bar toggle.
     *  [oneOrAll] = current state (true = ✋ all, false = ☝️ one); null =
     *  no toggle. [onToggleOneOrAll] flips it. */
    oneOrAll: Boolean? = null,
    onToggleOneOrAll: (() -> Unit)? = null
) {
    val navigateHome = LocalNavigateHome.current
    val logoInteractionSource = remember { MutableInteractionSource() }
    val effectiveLogoClick: () -> Unit = { navigateHome() }
    // Publish the View bottom-bar spec while mounted (always non-null so
    // the View bar — not the generic one — renders for this screen; the
    // 🔧 itself only shows when onOpenManage is non-null).
    val viewBottomBarState = LocalViewBottomBar.current
    if (viewBottomBarState != null) {
        // Republish every recomposition (onOpenManage may change), and
        // clear unconditionally on dispose. Only one ViewTitleBar is ever
        // composed at a time (View screens are mutually exclusive
        // full-screen overlays), so onDispose of the leaving screen runs
        // before the entering screen's SideEffect in the same apply pass —
        // no stale spec lingers and the next screen's spec always wins.
        SideEffect { viewBottomBarState.value = ViewBottomBarSpec(onManage = onOpenManage, showAll = oneOrAll, onToggleOneOrAll = onToggleOneOrAll, helpTopic = helpTopic) }
        DisposableEffect(viewBottomBarState) {
            onDispose { viewBottomBarState.value = null }
        }
    }
    // Swipe feedback uses the app's standard Android Toast (same as the
    // shared horizontalSwipeNavigation edge toasts), not a bespoke pill.
    val swipeDensity = LocalDensity.current
    val swipeThresholdPx = with(swipeDensity) { 80.dp.toPx() }
    val swipeDragX = remember { mutableFloatStateOf(0f) }
    val swipeEnabled = onSwipePrev != null || onSwipeNext != null
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
                                        dx > swipeThresholdPx -> onSwipePrev?.invoke()
                                        dx < -swipeThresholdPx -> onSwipeNext?.invoke()
                                    }
                                    swipeDragX.floatValue = 0f
                                },
                                onDragCancel = { swipeDragX.floatValue = 0f },
                                onHorizontalDrag = { _, d -> swipeDragX.floatValue += d }
                            )
                        }
                    } else Modifier
                )
                // No horizontal outset — match the Manage TitleBar exactly:
                // the Row is plain fillMaxWidth inside the screen's 16dp
                // padding, and the edge icons sit at offset(±10) just like
                // Manage. (The old outset pushed the icons ~16dp further
                // toward the screen edges than Manage.)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val navToCurrentReport = LocalNavigateToCurrentReport.current
            val titleClick: () -> Unit = onTitleClick ?: navToCurrentReport ?: onBack
            // Left — dynamic report icon (reuses the Manage TitleBar's
            // report-glyph renderer + position/size). Tap follows the
            // same target as the centre title: Manage on the hub, the
            // View hub on every other screen.
            ReportGlyphIcon(
                emoji = LocalReportIcon.current?.takeIf { it.isNotBlank() } ?: "📄",
                boxSize = 66.dp,
                modifier = Modifier
                    .align(Alignment.Top)
                    .offset(x = (-10).dp)
                    .padding(top = 4.dp)
                    .clickable(
                        interactionSource = logoInteractionSource,
                        indication = null,
                        onClick = titleClick
                    )
            )
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
            // Right — AI logo → app Home (mirrored, matching the Manage
            // TitleBar's right-edge logo position/size).
            AiLogoButton(
                onClick = effectiveLogoClick,
                size = 66.dp,
                mirrored = true,
                modifier = Modifier
                    .align(Alignment.Top)
                    .offset(x = 10.dp)
                    .padding(top = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
