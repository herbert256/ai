package com.ai.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ReportListRow
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Paginated browser of every saved report, no vertical scroll.
 *  The body height is measured by [BoxWithConstraints], split into
 *  fixed pages of [ReportListRow]s, and rendered through a
 *  [HorizontalPager] so the user swipes between pages instead of
 *  scrolling. A small "Page X of Y" header sits above the pager.
 *
 *  Row taps + the per-row 🔧 / 👁 / 🗑 icons consume
 *  [com.ai.ui.shared.LocalReportListIconBundle] — wired by
 *  AppNavHost at the route mount.  */
@Composable
fun AllAiReportsScreen(
    onNavigateBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    reportViewModel: ReportViewModel
) {
    val context = LocalContext.current
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    // Bumped after a row 🗑 delete completes — re-fires the disk
    // scan so the deleted row drops from the page immediately.
    var deleteTick by remember { mutableStateOf(0) }
    val reports by produceState(initialValue = emptyList<Report>(), refreshTick, deleteTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
    }
    val bundle = com.ai.ui.shared.LocalReportListIconBundle.current
    // Search + sort — the only order used to be newest-first.
    var search by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var sortMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("newest") }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val visibleReports = remember(reports, search, sortMode) {
        val filtered = if (search.isBlank()) reports
            else reports.filter { it.title.contains(search, ignoreCase = true) }
        when (sortMode) {
            "title" -> filtered.sortedBy { it.title.lowercase() }
            "cost" -> filtered.sortedByDescending { it.totalCost }
            else -> filtered // getAllReports is already newest-first
        }
    }
    // Multi-select: long-press a row to enter; Back exits selection first.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }
    androidx.activity.compose.BackHandler(enabled = selectionMode) { exitSelection() }
    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            title = "All reports", subject = "Browse every saved report, newest first",
            helpTopic = "all_ai_reports_screen",
            onBackClick = onNavigateBack
        )
        if (reports.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(com.ai.data.MetadataIconsHolder.current.library, fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No reports yet", color = AppColors.TextTertiary, fontSize = 14.sp)
                }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = search, onValueChange = { search = it },
                placeholder = { Text("Search by title…", fontSize = 13.sp) },
                singleLine = true, colors = AppColors.outlinedFieldColors(),
                modifier = Modifier.weight(1f)
            )
            Box {
                androidx.compose.material3.TextButton(onClick = { sortMenuOpen = true }) {
                    Text(
                        when (sortMode) { "title" -> "Title"; "cost" -> "Cost"; else -> "Newest" } + " ▾",
                        fontSize = 12.sp, maxLines = 1
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false },
                    modifier = Modifier.background(AppColors.SurfaceDark)
                ) {
                    listOf("newest" to "Newest first", "title" to "Title A–Z", "cost" to "Cost, highest first").forEach { (v, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label, fontSize = 13.sp, color = if (sortMode == v) AppColors.InfoAccent else AppColors.TextPrimary) },
                            onClick = { sortMode = v; sortMenuOpen = false }
                        )
                    }
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Per-row height ≈ 56 dp (matches History row math).
            // Reserve a touch of vertical headroom for the page header.
            val rowHeightDp = 56
            val headerReserveDp = 36
            val rowsPerPage = (((maxHeight.value - headerReserveDp) / rowHeightDp).toInt()).coerceAtLeast(1)
            val totalPages = ((visibleReports.size + rowsPerPage - 1) / rowsPerPage).coerceAtLeast(1)
            val pagerState = rememberPagerState(initialPage = 0, pageCount = { totalPages })
            // Re-clamp the active page when the dataset shrinks (e.g.
            // after a 🗑 delete drops the last row of the last page).
            LaunchedEffect(totalPages) {
                if (pagerState.currentPage >= totalPages) {
                    pagerState.scrollToPage((totalPages - 1).coerceAtLeast(0))
                }
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Page ${pagerState.currentPage + 1} of $totalPages",
                    color = AppColors.TextTertiary, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )
                // Multi-select header — visible while selection mode is on
                // (entered by long-pressing a row).
                if (selectionMode) {
                    var confirmDeleteSelected by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${selectedIds.size} selected", fontSize = 13.sp, color = AppColors.TextSecondary)
                        Spacer(modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { selectedIds = visibleReports.mapTo(HashSet()) { it.id } }) {
                            Text("All", fontSize = 13.sp, maxLines = 1)
                        }
                        androidx.compose.material3.TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                val ids = selectedIds
                                exportScope.launch {
                                    val (file, count) = withContext(Dispatchers.IO) {
                                        com.ai.ui.report.other.zipReports(context, ids)
                                    }
                                    if (file == null) {
                                        android.widget.Toast.makeText(context, "Nothing to export.", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", file
                                        )
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share $count report(s)"))
                                    }
                                }
                            }
                        ) { Text("Export", fontSize = 13.sp, maxLines = 1) }
                        androidx.compose.material3.TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { confirmDeleteSelected = true }
                        ) { Text("Delete", fontSize = 13.sp, color = AppColors.DangerAccent, maxLines = 1) }
                        androidx.compose.material3.TextButton(onClick = { exitSelection() }) { Text("Done", fontSize = 13.sp, maxLines = 1) }
                    }
                    if (confirmDeleteSelected) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirmDeleteSelected = false },
                            title = { Text("Delete ${selectedIds.size} report(s)?") },
                            text = { Text("Permanently deletes the selected reports from disk, including every secondary result. This cannot be undone.") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    confirmDeleteSelected = false
                                    val ids = selectedIds.toList()
                                    exitSelection()
                                    ids.forEach { reportViewModel.deleteReport(context, it) }
                                    deleteTick++
                                }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1) }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel", maxLines = 1) }
                            }
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) { pageIndex ->
                    val from = pageIndex * rowsPerPage
                    val to = (from + rowsPerPage).coerceAtMost(visibleReports.size)
                    val slice = if (from < to) visibleReports.subList(from, to) else emptyList()
                    Column(modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Top
                    ) {
                        slice.forEach { r ->
                            ReportListRow(
                                report = r,
                                onOpenManage = bundle.onOpenManage,
                                onOpenView = bundle.onOpenView,
                                onDelete = { rid ->
                                    reportViewModel.deleteReport(context, rid)
                                    deleteTick++
                                },
                                selectionMode = selectionMode,
                                selected = r.id in selectedIds,
                                onToggleSelect = { rid ->
                                    selectedIds = if (rid in selectedIds) selectedIds - rid else selectedIds + rid
                                },
                                onEnterSelection = { rid ->
                                    selectionMode = true
                                    selectedIds = setOf(rid)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
