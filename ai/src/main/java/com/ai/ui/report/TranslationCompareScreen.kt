package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Side-by-side (top/bottom) comparison of an original and its
 * translation. Each pane is independently scrollable so a long
 * original next to a short translation (or vice versa) doesn't lock
 * the user into a single shared scroll. Reached from the "Translation
 * info" button on a translated report's per-agent / per-summary /
 * per-compare detail screen.
 */
@Composable
internal fun TranslationCompareScreen(
    title: String,
    originalLabel: String,
    originalContent: String,
    translatedLabel: String,
    translatedContent: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val foldSubject = com.ai.ui.shared.LocalSubjectToTitleBarMode.current != com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(
            helpTopic = "translation_compare",
            title = "Translation compare",
            subject = title,
            onBackClick = onBack,
            onCopy = translatedContent.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "translation") }
            },
            onShare = translatedContent.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.shareText(context, body, "Translation — $title") }
            },
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )
        if (!foldSubject) {
            Text(
                text = title,
                fontSize = 18.sp, color = AppColors.Green,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Top pane — original.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                originalLabel,
                fontSize = 14.sp, color = AppColors.Blue, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (originalContent.isBlank()) {
                    Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                } else {
                    ContentWithThinkSections(analysis = originalContent)
                }
            }
        }

        HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

        // Bottom pane — translation.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                translatedLabel,
                fontSize = 14.sp, color = AppColors.Green, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (translatedContent.isBlank()) {
                    Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                } else {
                    ContentWithThinkSections(analysis = translatedContent)
                }
            }
        }
    }
}
