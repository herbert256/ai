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
import com.ai.data.loadExampleIndex
import com.ai.ui.hub.rememberExampleOpener
import com.ai.ui.hub.rememberHomeReportLists
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tappable row in a picker card. */
private data class PickerEntry(val title: String, val onOpen: () -> Unit)
private data class PickerCardData(
    val emoji: String, val color: Color, val label: String, val entries: List<PickerEntry>
)

/**
 * View-styled "pick a report to view" screen — a real Navigation
 * destination opened from the View hub's 📋. Mirrors the AI Reports
 * hub's five buckets (Running / Problems / Pinned / Latest / Examples)
 * but with title-only rows (no per-row icons): tapping a row opens that
 * report straight in View. Each card shows up to five at a glance and
 * scrolls inside for more; empty buckets are greyed and sink to the
 * bottom.
 */
@Composable
fun ReportPickerScreen(
    reportViewModel: ReportViewModel,
    onBack: () -> Unit,
    onOpenReportView: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()

    val allReports by produceState(initialValue = emptyList<Report>(), refreshTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
    }
    val pinned = remember(allReports) { allReports.filter { it.pinned }.sortedByDescending { it.timestamp } }
    val latest = remember(allReports) { allReports.filter { !it.pinned } }
    val homeLists by rememberHomeReportLists(refreshTick, reportViewModel)
    val examples by produceState(initialValue = emptyList<ExampleEntry>(), Unit) {
        value = withContext(Dispatchers.IO) { loadExampleIndex(context) }
    }
    // Examples import-then-open (same flow the AI Reports hub uses); we
    // always open them in View.
    val openExample = rememberExampleOpener(onOpenReportView, onOpenReportView)

    fun reportEntries(reports: List<Report>) = reports.map { r ->
        PickerEntry(r.title.ifBlank { "Untitled" }) { onOpenReportView(r.id) }
    }

    val cards = listOf(
        PickerCardData("⏳", AppColors.Orange, "Running AI reports", reportEntries(homeLists.running)),
        PickerCardData("⚠️", AppColors.Red, "AI Reports with problems", reportEntries(homeLists.problems)),
        PickerCardData("📌", AppColors.Yellow, "Pinned AI Reports", reportEntries(pinned)),
        PickerCardData("🕘", AppColors.Blue, "Latest AI Reports", reportEntries(latest)),
        PickerCardData("💡", AppColors.Purple, "Example AI Reports",
            examples.map { e -> PickerEntry(e.title) { openExample(e, true) } })
    )
    // Non-empty cards first; empty (greyed) ones sink to the bottom.
    val ordered = cards.sortedBy { it.entries.isEmpty() }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = null,
            screenTitle = "Pick a report",
            subject = "Open any report in View",
            helpTopic = "report_picker",
            onBack = onBack
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
    val empty = data.entries.isEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().alpha(if (empty) 0.35f else 1f)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(data.emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(data.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = data.color)
            }
            if (empty) {
                Text("(none)", color = AppColors.TextTertiary, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp, start = 26.dp))
            } else {
                // Up to ~5 rows visible; the rest scroll inside the card.
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    data.entries.forEach { e ->
                        Text(
                            e.title,
                            color = Color.White, fontSize = 14.sp,
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
}
