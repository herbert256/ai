package com.ai.ui.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R

/**
 * Layout-locked title bar used by every Report - view screen
 * ([com.ai.ui.report.ViewAiReportScreen] + every `*ViewScreen.kt`
 * destination it opens). Distinct from the standard [TitleBar] so
 * the View family has a consistent look that emphasises content
 * and de-emphasises management chrome.
 *
 * Three stacked rows:
 *  - Row 1 (always present): AI logo (left, taps go home), the
 *    parent report's title centred in white, and the help icon
 *    on the right.
 *  - Row 2 (always present): the screen's hard-coded label in
 *    orange (e.g. "Costs", "Meta").
 *  - Row 3 (only when [subject] is non-blank): screen-specific
 *    context in larger green text (e.g. the meta prompt name for
 *    Meta / Rerank / Moderation / Fan-in / Fan-in-model / Fan-out,
 *    or the target language for Translate).
 *
 * Rows 1 + 2 sit at the same y on every View screen. Row 3 either
 * renders (taller bar) or collapses entirely (shorter bar) — its
 * absence pulls the body content up; rows that ARE shown stay in
 * the same relative position.
 *
 * No action icons. No `LocalBottomIconState` publication — the
 * bottom-bar slot stays whatever the parent set it to (empty
 * under a View screen).
 */
@Composable
fun ViewScreenTitleBar(
    reportTitle: String?,
    screenTitle: String?,
    subject: String?,
    helpTopic: String,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    /** Optional 🔧 manage hook for the bottom-bar. When non-null the
     *  View title bar publishes a [com.ai.ui.shared.TitleBarIcons]
     *  with this slot filled (every other slot null) into
     *  [com.ai.ui.shared.LocalBottomIconState], so the global
     *  [com.ai.ui.shared.BottomIconBar] renders the wrench glyph.
     *  When null the title bar keeps its long-standing "nuke any
     *  parent's published icons on every recomposition" behaviour
     *  so help pages and other context-less View screens stay
     *  icon-free. */
    onOpenManage: (() -> Unit)? = null
) {
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val logoInteractionSource = remember { MutableInteractionSource() }
    // AI logo always navigates to the app Hub — matches the
    // standard [TitleBar] and the universal rule "top-left AI icon
    // goes home from anywhere in the app". The previous
    // onLogoClick override that let sub-View screens send the logo
    // to their own onBack is gone; Android back / gesture still
    // routes through each screen's BackHandler.
    val effectiveLogoClick: () -> Unit = { navigateHome() }
    // Publish — or clear — the global BottomIconBar state.
    //
    // When [onOpenManage] is provided we build a [TitleBarIcons]
    // with only the wrench slot filled (every other slot null) and
    // mount it via the same SideEffect / DisposableEffect pattern
    // the standard TitleBar uses (SharedComponents.kt). The
    // DisposableEffect's onDispose runs an identity check before
    // clearing so a race against the next screen's publication
    // doesn't wipe its just-published state.
    //
    // When [onOpenManage] is null we keep the long-standing
    // proactive-null behaviour — without it any parent screen's
    // TitleBar icons would linger at the bottom of an icon-free
    // View screen (help pages, etc.).
    val bottomIconState = com.ai.ui.shared.LocalBottomIconState.current
    if (bottomIconState != null) {
        if (onOpenManage != null) {
            val capturedIcons = com.ai.ui.shared.TitleBarIcons(
                helpTopic = helpTopic, onBack = null, onChat = null, onInfo = null,
                onOpenView = null, onOpenManage = onOpenManage, onCopy = null,
                onShare = null, onReload = null, onDelete = null, onTrace = null,
                onTranslationCompare = null, onMemo = null,
                onCopyReport = null, onPin = null, isPinned = false
            )
            androidx.compose.runtime.SideEffect { bottomIconState.value = capturedIcons }
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { if (bottomIconState.value === capturedIcons) bottomIconState.value = null }
            }
        } else {
            androidx.compose.runtime.SideEffect {
                if (bottomIconState.value != null) bottomIconState.value = null
            }
        }
    }
    // Pull the whole bar up 16 dp AND shrink its measured height by
    // the same amount so the AI logo lands at the same y as the
    // Report - manage TitleBar (which uses the same trick — see
    // SharedComponents.kt TitleBar). Without this the View bar
    // would sit 16 dp lower than the manage bar because both
    // screens add a 16 dp top padding to their root Column.
    Column(modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val shift = 16.dp.roundToPx()
        layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
            placeable.place(0, -shift)
        }
    }) {
        Row(
            modifier = Modifier
                // Outset 12 dp on each side so the AI logo and help
                // icon visually break out of the parent screen's
                // 16 dp horizontal padding and sit closer to the
                // screen edges. Rows 2 + 3 stay inside the parent
                // padding for a slightly nested look.
                .layout { measurable, constraints ->
                    val outsetPx = 12.dp.roundToPx()
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
                // The View bar's logo is bigger than the standard
                // TitleBar's because the View family suppresses the
                // bottom action icons — the logo grows into the
                // vertical space the orange screen title occupies.
                // No upward offset here (the standard TitleBar uses
                // -7 dp): the bigger logo plus the orange title row
                // below it want the AI logo to sit a touch lower
                // than the standard bar's, not pulled tight to the
                // top.
                modifier = Modifier
                    .size(76.dp)
                    .clickable(
                        interactionSource = logoInteractionSource,
                        indication = null,
                        onClick = effectiveLogoClick
                    )
            )
            // Prefer the bigger 24 sp size; if the title would overflow
            // the centre slot, drop to 18 sp instead. We never
            // ellipsize — overflow at the smaller size clips cleanly
            // and the user's title stays readable.
            var bigSizeFits by remember(reportTitle) { mutableStateOf(true) }
            // Tapping the report title jumps back to the report's
            // Manage screen. Hooked off [LocalNavigateToCurrentReport]
            // which the Report - manage host wraps the View overlay
            // in; help pages don't provide it, so on a help screen
            // the title is plain text.
            val navigateToManage = LocalNavigateToCurrentReport.current
            val titleClickable = navigateToManage != null && !reportTitle.isNullOrBlank()
            // Lift the report title up tight against the top of the
            // bar so it leaves vertical room for the orange screen
            // title that sits in the same band below. The 76 dp logo
            // dominates the Row's measured height so shifting this
            // smaller Text by -14 dp doesn't change the Row's height.
            val titleModifier = Modifier.weight(1f).offset(y = (-14).dp).let {
                if (titleClickable) it.clickable { navigateToManage!!.invoke() } else it
            }
            Text(
                text = reportTitle.orEmpty(),
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
                modifier = titleModifier
            )
            Text(
                text = "❓",
                fontSize = 40.sp,
                color = AppColors.Blue,
                modifier = Modifier
                    .offset(y = (-7).dp)
                    .clickable { navigateHelp(helpTopic) }
            )
        }
        if (!screenTitle.isNullOrBlank()) {
            // Pull the orange screen title up so it sits inside the
            // vertical span of the (now bigger) AI logo + help icon
            // row. The icons are at the left / right edges and the
            // orange title is centred — they share vertical space
            // without overlapping horizontally.
            val viewTitleLift = (-32).dp
            // Horizontal alignment with the report title above: the
            // report title lives between a 76 dp logo (with -12 dp
            // outset) on the left and the ~40 dp ❓ icon (with the
            // same outset) on the right, so its centre is shifted
            // ~18 dp to the right of the container centre. The
            // orange title is full-width centred — left-pad it by
            // twice that diff so its centre matches.
            val centreAlignPadStart = 36.dp
            Text(
                text = screenTitle,
                color = AppColors.Orange,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = centreAlignPadStart)
                    .offset(y = viewTitleLift)
            )
            if (!subject.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subject,
                    color = AppColors.Green,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = centreAlignPadStart)
                        .offset(y = viewTitleLift)
                )
            }
        } else if (!subject.isNullOrBlank()) {
            // Help pages render subject without a screen title — keep
            // its prior position unchanged in that case.
            Spacer(modifier = Modifier.height(2.dp))
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
        Spacer(modifier = Modifier.height(8.dp))
    }
}
