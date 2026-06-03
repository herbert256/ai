package com.ai.ui.report.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ExampleEntry
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.loadExampleIndex
import com.ai.ui.hub.rememberExampleOpener
import com.ai.ui.hub.rememberHomeReportLists
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tappable row in a picker card. */
private data class PickerEntry(val title: String, val onOpen: () -> Unit)
private data class PickerCardData(
    val emoji: String, val color: Color, val label: String, val entries: List<PickerEntry>
)

/**
 * View-styled "pick a report" screen — a real Navigation destination
 * opened from the View hub's 🗂️ and, with a [filter], from each Manage
 * screen's 🗂️. Mirrors the AI Reports hub's five buckets (Running /
 * Problems / Pinned / Latest / Examples) but with title-only rows (no
 * per-row icons): tapping a row hands the report id to [onOpenReportView]
 * (the caller decides where it opens). Each card shows up to five at a
 * glance and scrolls inside for more; empty buckets are greyed and sink
 * to the bottom.
 *
 * When [filter] is non-null the buckets are restricted to reports whose
 * `(report, secondaries)` satisfy it (e.g. "only reports with a
 * fan-out"); the Examples bucket is dropped, since example secondaries
 * aren't loaded without importing. Null [filter] = the original
 * everything-included behaviour, no secondary load.
 */
@Composable
fun ReportPickerScreen(
    reportViewModel: ReportViewModel,
    onBack: () -> Unit,
    onOpenReportView: (String) -> Unit,
    screenTitle: String = "Pick a report",
    subject: String = "Open any report in View",
    filter: ((Report, List<SecondaryResult>) -> Boolean)? = null,
    /** When set, this report (the one the 🗂️ was tapped on) is omitted
     *  from every bucket — you can't "pick another report" and land back
     *  on the one you're already on. */
    currentReportId: String? = null
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()

    val allReports by produceState(initialValue = emptyList<Report>(), refreshTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
    }
    // When filtering, resolve the set of qualifying report ids off-thread
    // (one cached secondary read per report). Null = no filter → keep all.
    val allowedIds by produceState<Set<String>?>(
        initialValue = if (filter == null) null else emptySet(),
        allReports, filter != null
    ) {
        value = if (filter == null) null
        else withContext(Dispatchers.IO) {
            allReports.filter { r ->
                filter(r, SecondaryResultStorage.listForReport(context, r.id))
            }.map { it.id }.toSet()
        }
    }
    val pinned = remember(allReports) { allReports.filter { it.pinned }.sortedByDescending { it.timestamp } }
    val homeLists by rememberHomeReportLists(refreshTick, reportViewModel)
    // "Latest" excludes anything already shown under "Running" or "with
    // problems" so a report surfaces in only one card.
    val latest = remember(allReports, homeLists) {
        val shown = (homeLists.running + homeLists.problems).mapTo(HashSet()) { it.id }
        allReports.filter { !it.pinned && it.id !in shown }
    }
    val examples by produceState(initialValue = emptyList<ExampleEntry>(), Unit) {
        value = withContext(Dispatchers.IO) { loadExampleIndex(context) }
    }
    // Examples import-then-open (same flow the AI Reports hub uses); we
    // always open them in View.
    val openExample = rememberExampleOpener(onOpenReportView, onOpenReportView)

    val ids = allowedIds
    fun reportEntries(reports: List<Report>) =
        (if (ids == null) reports else reports.filter { it.id in ids })
            .filter { it.id != currentReportId }
            .map { r ->
                PickerEntry(r.title.ifBlank { "Untitled" }) { onOpenReportView(r.id) }
            }

    val cards = listOf(
        PickerCardData("⏳", AppColors.WarningAccent, "Running AI reports", reportEntries(homeLists.running)),
        PickerCardData(com.ai.data.MetadataIconsHolder.current.statusWarning, AppColors.DangerAccent, "AI Reports with problems", reportEntries(homeLists.problems)),
        PickerCardData(com.ai.data.MetadataIconsHolder.current.pin, AppColors.CautionAccent, "Pinned AI Reports", reportEntries(pinned)),
        PickerCardData(com.ai.data.MetadataIconsHolder.current.clockRecent, AppColors.InfoAccent, "Latest AI Reports", reportEntries(latest)),
        PickerCardData(com.ai.data.MetadataIconsHolder.current.tip, AppColors.PrimaryAccent, "Example AI Reports",
            // Examples can't be capability-filtered without importing, so
            // hide the bucket whenever a filter is active.
            if (filter != null) emptyList()
            else examples.map { e -> PickerEntry(e.title) { openExample(e, true) } })
    )
    // Only show buckets that actually have reports — empty cards (which
    // used to render a greyed "(none)") are dropped entirely.
    val ordered = cards.filter { it.entries.isNotEmpty() }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            title = screenTitle,
            subject = subject,
            helpTopic = "report_picker",
            onBackClick = onBack
        )
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            ordered.forEach { PickerCard(it) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PickerCard(data: PickerCardData) {
    // Only non-empty cards reach here (empty buckets are filtered out by
    // the caller), so there's no greyed "(none)" state.
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(data.emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(data.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = data.color)
            }
            // Up to ~5 rows visible; the rest scroll inside the card.
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                data.entries.forEach { e ->
                    Text(
                        e.title,
                        color = AppColors.TextPrimary, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { e.onOpen() }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
