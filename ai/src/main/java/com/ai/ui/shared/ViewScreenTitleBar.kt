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
 *    orange (e.g. "Costs - view", "Meta - view").
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
    /** Override the AI-logo tap target. Default = navigate to the
     *  app home (via [LocalNavigateHome]). Child View screens pass
     *  their own onBack here so a logo tap returns to the parent
     *  View tile grid instead of jumping all the way home. */
    onLogoClick: (() -> Unit)? = null
) {
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val logoInteractionSource = remember { MutableInteractionSource() }
    val effectiveLogoClick: () -> Unit = onLogoClick ?: { navigateHome() }
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
                // y = -7 dp matches the offset on the AI logo in the
                // standard TitleBar (SharedComponents.kt), so both
                // bars place the logo at the same screen y.
                modifier = Modifier
                    .offset(y = (-7).dp)
                    .size(52.dp)
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
            val titleModifier = Modifier.weight(1f).let {
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
                fontSize = 28.sp,
                color = AppColors.Blue,
                modifier = Modifier.clickable { navigateHelp(helpTopic) }
            )
        }
        if (!screenTitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
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
