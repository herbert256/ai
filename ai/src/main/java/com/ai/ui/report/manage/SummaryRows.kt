package com.ai.ui.report.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.util.Locale

internal enum class ReportSection { Report, Info, SecondResults }

/** Fixed navigation above each section's scrolling content. All three rows
 * remain available even when metadata or secondary results are empty. */
@Composable
internal fun ReportSectionNavigation(
    active: ReportSection,
    reportCost: Double,
    infoState: InfoJobState,
    infoCost: Double,
    secondState: InfoJobState,
    secondCost: Double,
    onReport: () -> Unit,
    onInfo: () -> Unit,
    onSecond: () -> Unit,
    reportIcon: String? = null
) {
    val resolvedReportIcon = reportIcon ?: com.ai.ui.shared.LocalReportIcon.current
    Column(Modifier.fillMaxWidth().background(AppColors.AppBackground)) {
        ReportSectionRow("Report", "Read the model responses", InfoJobState.DONE,
            resolvedReportIcon, reportCost, active == ReportSection.Report, onReport)
        ReportSectionRow("Info", "Titles, icons and language", infoState,
            resolvedReportIcon, infoCost, active == ReportSection.Info, onInfo)
        ReportSectionRow("Second result", "Comparisons, rankings and more", secondState,
            com.ai.ui.shared.LocalMetadataIcons.current.meta, secondCost,
            active == ReportSection.SecondResults, onSecond)
        HorizontalDivider(color = AppColors.TextSecondary, thickness = 4.dp)
    }
}

@Composable
private fun ReportSectionRow(
    label: String,
    description: String,
    state: InfoJobState,
    icon: String?,
    cost: Double,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) AppColors.SelectionHighlight else AppColors.AppBackground)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .heightIn(min = 40.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InfoStatusCell(state, doneIcon = icon)
        Text(label, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = AppColors.TextPrimary, maxLines = 1,
            modifier = Modifier.width(104.dp).padding(start = 8.dp, end = 6.dp))
        Text(description, fontSize = 12.sp, color = AppColors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(formatCents(cost), fontSize = 10.sp, color = AppColors.TextSecondary,
            fontFamily = FontFamily.Monospace, maxLines = 1,
            modifier = Modifier.padding(start = 6.dp))
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}

/** The "nnn costed calls / x.xxx s duration / x.xx ¢" statistics line shown
 *  under the title bar of all three report screens.
 *
 *  Call count + duration are re-read from disk (keyed on [refreshKey] + the
 *  primary and secondary data versions — the same sources the Report-information Totals
 *  use, so the numbers match). The cost is the shared lifetime ledger total
 *  passed in as [costDollars], updated as completed calls are persisted.
 *  Legacy reports use the hub's structured total until their ledger is
 *  migrated. Calls means ledger entries with usage/cost, not raw
 *  HTTP attempts (failed/retried HTTP operations remain in traces).
 *  Tap → the report's costs screen. */
@Composable
internal fun ReportStatsLine(
    reportId: String,
    costDollars: Double,
    refreshKey: Any?,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val secDataVersion by SecondaryDataVersion.versionFor(reportId).collectAsState()
    val reportDataVersion by com.ai.data.ReportDataVersion.versionFor(reportId).collectAsState()
    val refresh by rememberUpdatedState(Triple(refreshKey, secDataVersion, reportDataVersion))
    val loaded by produceState<Pair<Report, List<SecondaryResult>>?>(null, reportId) {
        value = null
        var reportMtime = -1L
        var cachedReport: Report? = null
        snapshotFlow { refresh }.conflate().collect {
            value = withContext(Dispatchers.IO) {
                val mtime = ReportStorage.reportLastModified(context, reportId)
                val r = if (cachedReport != null && mtime == reportMtime) cachedReport
                    else ReportStorage.getReport(context, reportId)
                cachedReport = r
                reportMtime = mtime
                r?.let { it to SecondaryResultStorage.listForReport(context, reportId) }
            }
        }
    }
    val apiCalls = loaded?.first?.let { rememberReportCostData(it)?.rows?.size } ?: 0
    val durationMs = loaded?.let { totalApiDurationMs(it.first, it.second) } ?: 0L
    // The bottom padding is the ONLY gap between this line and the list's
    // top divider on all three report screens (Manage dropped its own
    // pre-list Spacer), so the divider sits at the same height everywhere
    // and nothing jumps when switching report sections.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)
            .let { m -> if (onClick != null) m.clickable { onClick() } else m },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (loaded == null) "Loading costs…" else "$apiCalls costed ${if (apiCalls == 1) "call" else "calls"}", fontSize = 10.sp,
            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            if (loaded == null) "…" else String.format(Locale.US, "%.3f s duration", durationMs / 1000.0),
            fontSize = 10.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            if (loaded == null) "…" else formatCentsValue(costDollars * 100, 2), fontSize = 10.sp,
            color = AppColors.InfoAccent, fontFamily = FontFamily.Monospace
        )
    }
}
