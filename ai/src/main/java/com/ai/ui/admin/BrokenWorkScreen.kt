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
import com.ai.viewmodel.BrokenReport

/** Full-screen list of reports that carry interrupted batches the
 *  background scan detected but did NOT fix. Reached from the ⚠️ that
 *  replaces the top-bar AI logo while [items] is non-empty. One row per
 *  report; tapping a row opens that report's Manage screen, where the
 *  user can manually Regenerate / retry the broken work. */
@Composable
fun BrokenWorkScreen(
    items: List<BrokenReport>,
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
            subject = "Interrupted batches the app detected",
            onBackClick = onBack,
            reportIcon = warningGlyph,
            onReportIconClick = onNavigateHome,
            onTitleClick = onNavigateHome
        )

        Text(
            "Batches interrupted by an app kill or error. The app no longer fixes these automatically — tap a report to open it and Regenerate / retry the broken work.",
            fontSize = 11.sp, color = AppColors.TextTertiary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("(nothing broken)", color = AppColors.TextTertiary)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(items, key = { _, r -> r.reportId }) { index, report ->
                    BrokenWorkItem(report, warningGlyph, index, onClick = { onOpenReport(report.reportId) })
                }
            }
        }
    }
}

@Composable
private fun BrokenWorkItem(report: BrokenReport, warningGlyph: String, index: Int, onClick: () -> Unit) {
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
                    report.title.ifBlank { "(untitled report)" },
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${report.totalCount} broken — ${report.kinds.joinToString(", ") { it.label }}",
                    fontSize = 11.sp, color = AppColors.WarningAccent,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(report.timestamp).toString(),
                    fontSize = 10.sp, color = AppColors.TextTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", fontSize = 18.sp, color = AppColors.TextTertiary)
        }
    }
}
