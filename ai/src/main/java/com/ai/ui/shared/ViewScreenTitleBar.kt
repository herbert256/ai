package com.ai.ui.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    // Three-column Row: AI logo on the left, a centre Column with
    // the white report title + orange screen title (+ optional
    // green subject) stacked, and the ❓ help icon on the right.
    // Putting both text rows in the SAME centre Column guarantees
    // their horizontal centres line up — they share the column's
    // bounds, so there is no alignment math to keep in sync.
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
                // screen edges.
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
            // Left column — AI logo.
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
            // Centre column — stacked title texts. All children use
            // fillMaxWidth + textAlign Center so they share the same
            // horizontal centre. The column is vertically centred
            // inside the Row (verticalAlignment Center) so it sits
            // inside the 76 dp logo's vertical span.
            var bigSizeFits by remember(reportTitle) { mutableStateOf(true) }
            val navigateToManage = LocalNavigateToCurrentReport.current
            val titleClickable = navigateToManage != null && !reportTitle.isNullOrBlank()
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val reportTitleMod = Modifier.fillMaxWidth().let {
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
                    modifier = reportTitleMod
                )
                if (!screenTitle.isNullOrBlank()) {
                    Text(
                        text = screenTitle,
                        color = AppColors.Orange,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
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
            // Right column — help icon.
            Text(
                text = "❓",
                fontSize = 40.sp,
                color = AppColors.Blue,
                modifier = Modifier
                    .clickable { navigateHelp(helpTopic) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
