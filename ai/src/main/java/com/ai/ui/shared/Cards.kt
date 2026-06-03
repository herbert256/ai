package com.ai.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reusable list / hub / report-row cards, extracted from SharedComponents.kt. */

/** Monitor-style tappable card row: large icon, title/subtitle, chevron. */
@Composable
fun IconLinkCard(
    icon: String,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mi = LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                mi.forFactoryGlyph(icon),
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(42.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, fontSize = 11.sp, color = AppColors.TextTertiary)
                }
            }
            Text("›", fontSize = 22.sp, color = AppColors.TextTertiary)
        }
    }
}

/** Standard card header for cards that previously had only a title line. */
@Composable
fun IconCardHeader(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val mi = LocalMetadataIcons.current
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            mi.forFactoryGlyph(icon),
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(42.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Reusable list item card with title, subtitle, and delete button. */
@Composable
fun SettingsListItemCard(
    title: String,
    subtitle: String,
    extraLine: String? = null,
    subtitleColor: Color = AppColors.TextTertiary,
    onClick: () -> Unit,
    /** Null hides the trailing X — used by fixed-list screens (e.g.
     *  Other Internal prompts) where rows are editable but not deletable. */
    onDelete: (() -> Unit)?,
    /** Optional widget rendered just before the trailing delete X —
     *  e.g. a 🐞 trace link on a model-cooldown row. */
    trailing: (@Composable () -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppColors.TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, fontSize = 14.sp, color = subtitleColor)
                if (extraLine != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = extraLine, fontSize = 12.sp, color = AppColors.TextDim)
                }
            }
            trailing?.invoke()
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Text("X", color = AppColors.DangerAccent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Reusable collapsible card with a clickable title header.
 *
 * [helpTopic] — when non-null, an ❓ icon sits between the summary
 * and the chevron. Tapping it routes through [LocalNavigateToHelp]
 * to the full-screen help page for that topic. Tap stays inside
 * the icon — does NOT propagate to the row's expand/collapse
 * handler.
 */
@Composable
fun CollapsibleCard(
    title: String,
    icon: String? = null,
    defaultExpanded: Boolean = false,
    summary: String? = null,
    helpTopic: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val navigateHelp = LocalNavigateToHelp.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Text(
                        LocalMetadataIcons.current.forFactoryGlyph(icon),
                        fontSize = 28.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(42.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary, modifier = Modifier.weight(1f)
                )
                if (summary != null && !expanded) {
                    Text(
                        text = summary, style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary, modifier = Modifier.padding(end = 8.dp)
                    )
                }
                if (helpTopic != null) {
                    Text(
                        text = com.ai.data.MetadataIconsHolder.current.help, fontSize = 14.sp, color = AppColors.InfoAccent,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { navigateHelp(helpTopic) }
                    )
                }
                Text(text = if (expanded) "▾" else "▸", color = AppColors.TextTertiary, fontSize = 16.sp)
            }
            if (expanded) { content() }
        }
    }
}

/** Hub navigation card - clickable card with title and description. */
@Composable
fun HubCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color = AppColors.CardBackground
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppColors.TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, fontSize = 14.sp, color = AppColors.TextTertiary)
        }
    }
}

/** Two right-aligned per-row icons that every list of AI reports
 *  carries: 🔧 opens the report at the Manage screen (the historical
 *  default), 👁 opens it at the View tile grid. Each icon is its own
 *  clickable Text — tapping an icon does NOT bubble up to the row's
 *  outer clickable. */
@Composable
fun ReportRowActionIcons(
    onOpenManage: () -> Unit,
    onOpenView: () -> Unit,
    /** Accepted for call-site compatibility but no longer rendered:
     *  AI-report lists now show only 👁 view + 🔧 manage. Deleting a
     *  report happens from Report - manage. */
    @Suppress("UNUSED_PARAMETER") onDelete: ((String) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") reportId: String? = null
) {
    Text(
        com.ai.data.MetadataIconsHolder.current.view, fontSize = 22.sp,
        modifier = Modifier
            .clickable { onOpenView() }
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
    )
    Text(
        com.ai.data.MetadataIconsHolder.current.openManage, fontSize = 18.sp,
        modifier = Modifier
            .clickable { onOpenManage() }
            .padding(start = 6.dp, end = 0.dp, top = 4.dp, bottom = 4.dp)
    )
}

/** One full report-list row: per-report icon (when icon-gen is on), the
 *  title (single-line, ellipsised), and the trailing 🔧 / 👁 action pair.
 *  The outer row is clickable → [onOpenManage]. */
@Composable
fun ReportListRow(
    report: com.ai.data.Report,
    onOpenManage: (String) -> Unit,
    onOpenView: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var showDelete by remember(report.id) { mutableStateOf(false) }
    if (showDelete) {
        DeleteConfirmationDialog(
            entityType = "Report",
            entityName = report.title.ifBlank { "Untitled" },
            onConfirm = { showDelete = false; onDelete(report.id) },
            onDismiss = { showDelete = false }
        )
    }
    val iconGenEnabled = LocalIconGenEnabled.current
    val defaultLogo = LocalMetadataIcons.current.reportIcon
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onOpenManage(report.id) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (if (iconGenEnabled) report.icon?.takeIf { it.isNotBlank() } else null) ?: defaultLogo,
            fontSize = 22.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = report.title.ifBlank { "Untitled" },
            fontSize = 14.sp, color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        ReportRowActionIcons(
            onOpenManage = { onOpenManage(report.id) },
            onOpenView = { onOpenView(report.id) },
            onDelete = { showDelete = true },
            reportId = report.id
        )
    }
}
