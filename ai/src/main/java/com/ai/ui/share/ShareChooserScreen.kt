package com.ai.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.SharedContent
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * Lightweight chooser shown when another app shares content into
 * this one. The user picks where the payload should go: a Report
 * (multi-model analysis), a Chat (single-model conversation), or
 * a Knowledge base (RAG source). The chooser lives between the
 * receiving Activity and the standard nav graph; tapping a card
 * fires the corresponding callback and clears the share state.
 */
@Composable
fun ShareChooserScreen(
    shared: SharedContent,
    onCancel: () -> Unit,
    onSendToReport: () -> Unit,
    onSendToChat: () -> Unit,
    onSendToKnowledge: () -> Unit
) {
    BackHandler { onCancel() }
    val hasText = !shared.text.isNullOrBlank()
    val hasUris = shared.uris.isNotEmpty()

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp)) {
        TitleBar(title = "Send to AI", onBackClick = onCancel)
        Spacer(modifier = Modifier.height(12.dp))

        // Show a short preview of what was shared so the user can
        // double-check before picking a destination.
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shared.subject?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                if (hasText) {
                    Text(shared.text!!.take(300) + if (shared.text.length > 300) "…" else "",
                        fontSize = 12.sp, color = AppColors.TextSecondary)
                }
                if (hasUris) {
                    val n = shared.uris.size
                    Text(
                        if (n == 1) "1 attachment" else "$n attachments",
                        fontSize = 11.sp, color = AppColors.TextTertiary
                    )
                }
                shared.mime?.let {
                    Text("type: $it", fontSize = 10.sp, color = AppColors.TextTertiary)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Three destinations. Cards stay tappable even when the
        // payload is "weak" for that route — e.g. only a file shared
        // can still go to Report (image attached for vision); only
        // text shared can still go to Knowledge as a paste-in URL or
        // raw note. Caller does the heavier validation.
        ShareCard(
            icon = "📝",
            title = "New Report",
            description = "Multi-model analysis. Text becomes the prompt; images attach for vision; files attach as Knowledge.",
            enabled = hasText || hasUris,
            onClick = onSendToReport
        )
        Spacer(modifier = Modifier.height(12.dp))
        ShareCard(
            icon = "💬",
            title = "New Chat",
            description = "Open a chat with this text staged as the first turn.",
            enabled = hasText,
            onClick = onSendToChat
        )
        Spacer(modifier = Modifier.height(12.dp))
        ShareCard(
            icon = "📚",
            title = "Add to Knowledge",
            description = "Open the Knowledge screen with the file or URL pre-staged.",
            enabled = hasUris || shared.isUrl,
            onClick = onSendToKnowledge
        )
    }
}

@Composable
private fun ShareCard(
    icon: String,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (enabled) AppColors.CardBackgroundAlt else Color(0xFF1A2A3A))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 24.sp,
                modifier = if (enabled) Modifier else Modifier.alpha(0.4f))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.White else AppColors.TextDim)
                Text(description, fontSize = 11.sp,
                    color = if (enabled) AppColors.TextTertiary else AppColors.TextDim)
            }
        }
    }
}
