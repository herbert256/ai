package com.ai.ui.history

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ai.data.*
import com.ai.ui.report.*
import com.ai.ui.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreenNav(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenReportResult: (String) -> Unit = {},
    onNavigateToTraceList: (String) -> Unit = {}
) {
    BackHandler { onNavigateBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allReports by remember { mutableStateOf(emptyList<Report>()) }
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    LaunchedEffect(refreshTick) {
        // getAllReports re-reads + parses every report JSON, including
        // any image-attached reports which can be MB-sized. Off the UI
        // thread so the History screen opens without jank. Re-fires on
        // every ON_RESUME so navigating away to delete / regenerate a
        // report and coming back shows the updated list.
        allReports = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
    }
    var searchTitle by remember { mutableStateOf("") }
    var searchPrompt by remember { mutableStateOf("") }
    var searchReport by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(0) }

    val isSearchActive = searchTitle.isNotBlank() || searchPrompt.isNotBlank() || searchReport.isNotBlank()
    val filteredReports = remember(allReports, searchTitle, searchPrompt, searchReport) {
        if (!isSearchActive) allReports
        else allReports.filter { report ->
            (searchTitle.isBlank() || report.title.contains(searchTitle, ignoreCase = true)) &&
            (searchPrompt.isBlank() || report.prompt.contains(searchPrompt, ignoreCase = true)) &&
            (searchReport.isBlank() || report.agents.any { it.responseBody?.contains(searchReport, ignoreCase = true) == true })
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        val rowHeight = 56
        val overhead = if (searchExpanded) 280 else 150
        val pageSize = maxOf(1, ((maxHeight.value - overhead) / rowHeight).toInt())
        val totalPages = if (filteredReports.isEmpty()) 1 else (filteredReports.size + pageSize - 1) / pageSize

        LaunchedEffect(totalPages) { if (currentPage >= totalPages) currentPage = (totalPages - 1).coerceAtLeast(0) }

        val startIndex = currentPage * pageSize
        val pageItems = filteredReports.subList(startIndex.coerceAtMost(filteredReports.size), (startIndex + pageSize).coerceAtMost(filteredReports.size))

        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(title = "History", onBackClick = onNavigateBack, onAiClick = onNavigateHome)

            // Search toggle
            if (!searchExpanded) {
                OutlinedButton(onClick = { searchExpanded = true }, modifier = Modifier.fillMaxWidth(), colors = AppColors.outlinedButtonColors()) {
                    Text(if (isSearchActive) "Search (active)" else "Search")
                }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = searchTitle, onValueChange = { searchTitle = it; currentPage = 0 },
                            label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                        OutlinedTextField(value = searchPrompt, onValueChange = { searchPrompt = it; currentPage = 0 },
                            label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                        OutlinedTextField(value = searchReport, onValueChange = { searchReport = it; currentPage = 0 },
                            label = { Text("Response") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { searchTitle = ""; searchPrompt = ""; searchReport = "" }, colors = AppColors.outlinedButtonColors()) { Text("Clear", maxLines = 1, softWrap = false) }
                            OutlinedButton(onClick = { searchExpanded = false }, colors = AppColors.outlinedButtonColors()) { Text("Close", maxLines = 1, softWrap = false) }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pagination
            if (totalPages > 1) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { currentPage = (currentPage - 1).coerceAtLeast(0) }, enabled = currentPage > 0) { Text("< Prev", maxLines = 1, softWrap = false) }
                    Text("${currentPage + 1} / $totalPages (${filteredReports.size})", fontSize = 12.sp, color = AppColors.TextTertiary)
                    TextButton(onClick = { currentPage = (currentPage + 1).coerceAtMost(totalPages - 1) }, enabled = currentPage < totalPages - 1) { Text("Next >", maxLines = 1, softWrap = false) }
                }
            }

            // Table header
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Title", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1.5f))
                    Text("Date/Time", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Report rows — tap a row to open the live Report Result screen.
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(pageItems, key = { it.id }) { report ->
                    HistoryReportRow(report = report,
                        onOpen = { onOpenReportResult(report.id) },
                        onOpenTraces = { onNavigateToTraceList(report.id) },
                        onDeleteReport = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ReportStorage.deleteReport(context, report.id) }
                                allReports = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                ReportStorage.deleteAllReports(context)
                allReports = emptyList(); currentPage = 0
            }, enabled = allReports.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear History", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun HistoryReportRow(report: Report, onOpen: () -> Unit, onOpenTraces: () -> Unit, onDeleteReport: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.US) }

    // Per-row trace existence — drives the 🐞 icon's visibility. Cheap
    // lookup against the cached trace-file metadata; no JSON parse.
    val hasTraces by produceState(initialValue = false, report.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles().any { it.reportId == report.id }
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmationDialog(entityType = "Report", entityName = report.title,
            onConfirm = { showDeleteConfirm = false; onDeleteReport() }, onDismiss = { showDeleteConfirm = false })
    }

    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(report.title, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.5f))
            Text(dateFormat.format(Date(report.timestamp)), fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            if (ApiTracer.isTracingEnabled && hasTraces) {
                Text("🐞", fontSize = 16.sp,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clickable { onOpenTraces() })
            }
            TextButton(onClick = { showDeleteConfirm = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("✕", fontSize = 14.sp, color = AppColors.Red, maxLines = 1, softWrap = false)
            }
        }
    }
}
