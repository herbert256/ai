package com.ai.ui.share

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
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
    onSendToKnowledge: () -> Unit,
    /** Master experimental-features gate. When false the "Add to
     *  Knowledge" card is hidden — Knowledge / RAG is an experimental
     *  surface; sharing to Report or Chat stays available. */
    experimentalFeatures: Boolean = false
) {
    BackHandler { onCancel() }
    val context = LocalContext.current
    val hasText = !shared.text.isNullOrBlank()
    val hasUris = shared.uris.isNotEmpty()
    val hasImageUris = shared.uris.any { uri ->
        runCatching { context.contentResolver.getType(Uri.parse(uri)) }.getOrNull()
            ?.startsWith("image/") == true ||
            shared.mime?.startsWith("image/") == true
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(AppColors.AppBackground)
        .padding(16.dp)) {
        TitleBar(helpTopic = "share_target", title = "Share", subject = "Turn shared content into a report/chat", onBackClick = onCancel)

        // Show a short preview of what was shared so the user can
        // double-check before picking a destination.
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shared.subject?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                if (hasText) {
                    val text = shared.text.orEmpty()
                    Text(text.take(300) + if (text.length > 300) "…" else "",
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
            icon = com.ai.data.MetadataIconsHolder.current.reportIcon,
            title = "New Report",
            description = "Multi-model analysis. Text becomes the prompt; the first image attaches for vision; non-image files queue for one-tap auto-attach as a knowledge base on the New Report screen.",
            enabled = hasText || hasUris,
            onClick = onSendToReport
        )
        Spacer(modifier = Modifier.height(12.dp))
        ShareCard(
            icon = com.ai.data.MetadataIconsHolder.current.chat,
            title = "New Chat",
            description = "Open a chat with this text and first image staged as the first turn.",
            enabled = hasText || hasImageUris,
            onClick = onSendToChat
        )
        if (experimentalFeatures) {
            Spacer(modifier = Modifier.height(12.dp))
            ShareCard(
                icon = com.ai.data.MetadataIconsHolder.current.library,
                title = "Add to Knowledge",
                description = "Open the Knowledge screen with the file or URL pre-staged. Plain shared text (not a URL) can't be ingested here — use New Report instead.",
                enabled = hasUris || shared.isUrl,
                onClick = onSendToKnowledge
            )
        }
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
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
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
                    color = if (enabled) AppColors.TextPrimary else AppColors.TextDim)
                Text(description, fontSize = 11.sp,
                    color = if (enabled) AppColors.TextTertiary else AppColors.TextDim)
            }
        }
    }
}

/**
 * Chrome for the full-screen overlays AppNavHost draws BEFORE its nav graph
 * (Share, External request, Choose saved prompt). They sit outside the
 * providers that give every other screen its bottom icon bar and Help
 * navigation, so their ❓ never appeared and Help was unreachable. This
 * supplies both: a bottom bar fed by the overlay's own TitleBar, and Help
 * pages layered ON TOP of the overlay — the overlay stays composed (its state
 * survives), Back closes one help page at a time and then returns to it.
 */
@Composable
fun OverlayWithHelp(content: @Composable () -> Unit) {
    val barState = remember { mutableStateOf<com.ai.ui.shared.TitleBarIcons?>(null) }
    // Topic ids; null = Help home. Each drill-in pushes, Back pops.
    var helpStack by remember { mutableStateOf(emptyList<String?>()) }
    val openHelp: (String?) -> Unit = { topic -> helpStack = helpStack + topic }
    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalBottomIconState provides barState,
            com.ai.ui.shared.LocalNavigateToHelp provides openHelp
        ) {
            Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)) {
                Box(modifier = Modifier.weight(1f)) { content() }
                com.ai.ui.shared.BottomIconBar(icons = barState.value)
            }
        }
        if (helpStack.isNotEmpty()) {
            val closeTop = { helpStack = helpStack.dropLast(1) }
            // Opaque and touch-swallowing: taps must not reach the overlay's
            // Continue / Cancel / destination cards underneath.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.AppBackground)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { }
            ) {
                // Composed after the overlay, so its BackHandler wins.
                com.ai.ui.admin.HelpScreen(
                    topicId = helpStack.last(),
                    onBack = closeTop,
                    onNavigateHome = closeTop,
                    onNavigateToTopic = { openHelp(it) },
                    onNavigateToHelpHome = { openHelp(null) },
                    onNavigateToAbout = null
                )
            }
        }
    }
}
