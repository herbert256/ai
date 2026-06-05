package com.ai.ui.admin

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.BrokenBatch

/** Full-screen list of batches that carry work needing attention —
 *  unfinished (stranded by an app-kill) and/or errored items — that the
 *  read-only background scan detected but did NOT fix. Reached from the
 *  ⚠️ that replaces the top-bar AI logo while [items] is non-empty. One
 *  card per batch; tapping a card opens that report's Manage screen. */
@Composable
fun BrokenWorkScreen(
    items: List<BrokenBatch>,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReport: (String) -> Unit
) {
    BackHandler { onBack() }
    val warningGlyph = LocalMetadataIcons.current.statusWarning

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "broken_work",
            title = "Broken work",
            subject = "Batch work that needs attention",
            onBackClick = onBack,
            reportIcon = warningGlyph,
            onReportIconClick = onNavigateHome,
            onTitleClick = onNavigateHome
        )

        Text(
            "Batches with unfinished (app-kill) or errored items the scan detected. The app no longer fixes these automatically — tap a card to open the report.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("(nothing broken)", color = AppColors.TextTertiary)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(items, key = { _, b -> "${b.reportId}|${b.kind}|${b.key}" }) { index, batch ->
                    BrokenWorkItem(batch, warningGlyph, index, onClick = { onOpenReport(batch.reportId) })
                }
            }
        }
    }
}

@Composable
private fun BrokenWorkItem(batch: BrokenBatch, warningGlyph: String, index: Int, onClick: () -> Unit) {
    val background = if (index % 2 == 0) AppColors.CardBackground else AppColors.CardBackgroundAlt
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(warningGlyph, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    batch.reportTitle.ifBlank { "(untitled report)" },
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    batch.batchName,
                    fontSize = 12.sp, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (batch.unfinishedCount > 0) {
                    Text(
                        "${batch.unfinishedCount} unfinished",
                        fontSize = 11.sp, color = AppColors.WarningAccent, maxLines = 1
                    )
                }
                if (batch.errorCount > 0) {
                    Text(
                        "${batch.errorCount} error${if (batch.errorCount == 1) "" else "s"}",
                        fontSize = 11.sp, color = AppColors.DangerAccent, maxLines = 1
                    )
                }
                Text(
                    DateUtils.getRelativeTimeSpanString(batch.timestamp).toString(),
                    fontSize = 10.sp, color = AppColors.TextTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", fontSize = 18.sp, color = AppColors.TextTertiary)
        }
    }
}
