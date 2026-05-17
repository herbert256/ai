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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    screenTitle: String,
    subject: String?,
    helpTopic: String,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit
) {
    val navigateHome = LocalNavigateHome.current
    val navigateHelp = LocalNavigateToHelp.current
    val logoInteractionSource = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.brand_glyph),
                contentDescription = "Home",
                modifier = Modifier.size(52.dp).clickable(
                    interactionSource = logoInteractionSource,
                    indication = null,
                    onClick = { navigateHome() }
                )
            )
            Text(
                text = reportTitle.orEmpty(),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "❓",
                fontSize = 28.sp,
                color = AppColors.Blue,
                modifier = Modifier.clickable { navigateHelp(helpTopic) }
            )
        }
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
