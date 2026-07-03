package com.ai.ui.report.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.report.info.totalApiDurationMs
import com.ai.ui.report.manage.view.rememberReportCostData
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.formatCentsValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

// The summary rows shared by the three report screens (Manage / Get-info /
// second results). Each screen lists the OTHER screens' aggregate rows at the
// top of its body, so the three stay one tap apart; the rows render
// identically everywhere (status cell + type cell + label + cost) and each
// paints its own trailing divider, like every other list row.

/** "report" row — links the Get-info / second-results screens back to the
 *  Manage hub: the report's icon + a fixed "Manage this report" label (the
 *  report's own title already shows in the orange title-bar line). The
 *  trailing [cost] is the combined main-response cost of every model (the
 *  sum of the Manage hub's per-model "report" rows); always shown, like the
 *  other cross-link rows. */
@Composable
internal fun ReportsSummaryRow(cost: Double, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        InfoStatusCell(InfoJobState.DONE, doneIcon = com.ai.ui.shared.LocalReportIcon.current)
        RowTypeCell("report")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Manage this report", fontSize = 13.sp, color = AppColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            formatCents(cost), fontSize = 10.sp,
            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
        )
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}

/** "info" row — aggregate status + total of the report's metadata jobs;
 *  tap → "Report - Get info". When all jobs are done the status cell shows
 *  the report's own icon instead of ✅ (the [doneIcon]). */
@Composable
internal fun InfoSummaryRow(state: InfoJobState, doneIcon: String?, cost: Double, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        InfoStatusCell(state, doneIcon = doneIcon)
        RowTypeCell("info")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "icon, language, title, per-model icon / title",
                fontSize = 13.sp, color = AppColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        // Always shown — a 0 means every metadata job was a cache hit (or
        // nothing has spent yet), matching the per-job rows on Get-info.
        Text(
            formatCents(cost), fontSize = 10.sp,
            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
        )
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}

/** "second" row — aggregate status + total of every secondary result;
 *  tap → "Report - second results". The cost is always shown — 0 when no
 *  secondary results have run yet. */
@Composable
internal fun SecondSummaryRow(state: InfoJobState, cost: Double, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        InfoStatusCell(state, doneIcon = com.ai.ui.shared.LocalMetadataIcons.current.meta)
        RowTypeCell("second")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Second results",
                fontSize = 13.sp, color = AppColors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // Explicit launcher hint — this row used to render exactly like
            // the read-only info rows, so the app's headline analyses hid
            // behind a status-looking row and the bare 1/2/3 switcher.
            Text(
                "Run analyses: Meta · Fan out · Tournament · Rerank · …",
                fontSize = 10.sp, color = AppColors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        // Always shown — 0 when there are no secondary results yet.
        Text(
            formatCents(cost), fontSize = 10.sp,
            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
        )
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}

/** The "nnn api calls / x.xxx s duration / x.xx ¢" statistics line shown
 *  under the title bar of all three report screens.
 *
 *  Call count + duration are re-read from disk (keyed on [refreshKey] + the
 *  secondary-data version — the same sources the Report-information Totals
 *  use, so the numbers match). The cost is the hub's LIVE running total
 *  passed in as [costDollars]: GenerationPhase folds in-flight translation
 *  spend into it, which disk misses mid-run, and the hub stays composed under
 *  both overlay screens — so all three show the same ticking number the
 *  bottom bar used to. Tap → the report's costs screen. */
@Composable
internal fun ReportStatsLine(
    reportId: String,
    costDollars: Double,
    refreshKey: Any?,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val secDataVersion by SecondaryDataVersion.versionFor(reportId).collectAsState()
    val loaded by produceState<Pair<Report, List<SecondaryResult>>?>(null, reportId, refreshKey, secDataVersion) {
        value = withContext(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId) ?: return@withContext null
            r to SecondaryResultStorage.listForReport(context, reportId)
        }
    }
    val apiCalls = loaded?.first?.let { rememberReportCostData(it)?.rows?.size } ?: 0
    val durationMs = loaded?.let { totalApiDurationMs(it.first, it.second) } ?: 0L
    // The bottom padding is the ONLY gap between this line and the list's
    // top divider on all three report screens (Manage dropped its own
    // pre-list Spacer), so the divider sits at the same height everywhere
    // and nothing jumps when flipping 1️⃣2️⃣3️⃣.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)
            .let { m -> if (onClick != null) m.clickable { onClick() } else m },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$apiCalls api calls", fontSize = 10.sp,
            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            String.format(Locale.US, "%.3f s duration", durationMs / 1000.0),
            fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            formatCentsValue(costDollars * 100, 2), fontSize = 10.sp,
            color = AppColors.InfoAccent, fontFamily = FontFamily.Monospace
        )
    }
}
