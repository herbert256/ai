package com.ai.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI History screen showing generated reports with pagination.
 * Layout matches the API Trace Log screen.
 */
@Composable
fun HistoryScreenNav(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    developerMode: Boolean = false
) {
    val context = LocalContext.current
    var allReports by remember { mutableStateOf(com.ai.data.ReportStorage.getAllReports(context)) }
    var filteredReports by remember { mutableStateOf(allReports) }
    var currentPage by remember { mutableIntStateOf(0) }

    // Search state
    var searchExpanded by remember { mutableStateOf(false) }
    var searchTitle by remember { mutableStateOf("") }
    var searchPrompt by remember { mutableStateOf("") }
    var searchReport by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Selected report for viewing
    var selectedReportId by remember { mutableStateOf<String?>(null) }

    // Show viewer screen when a report is selected
    val reportId = selectedReportId
    if (reportId != null) {
        ReportsViewerScreen(
            reportId = reportId,
            onDismiss = { selectedReportId = null },
            onNavigateHome = onNavigateHome
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Calculate page size based on available height
        // Title bar ~48dp, search button ~48dp, pagination ~48dp, header ~40dp, clear button ~48dp, spacing ~48dp = ~280dp overhead
        // Each row is approximately 56dp
        val availableHeight = maxHeight - 280.dp
        val rowHeight = 56.dp
        val pageSize = maxOf(1, (availableHeight / rowHeight).toInt())

        val totalPages = (filteredReports.size + pageSize - 1) / pageSize
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, filteredReports.size)
        val currentPageReports = if (filteredReports.isNotEmpty() && startIndex < filteredReports.size) {
            filteredReports.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // Reset to valid page if needed
        LaunchedEffect(pageSize, filteredReports.size) {
            if (currentPage >= totalPages && totalPages > 0) {
                currentPage = totalPages - 1
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(
                title = "AI History",
                onBackClick = onNavigateBack,
                onAiClick = onNavigateHome
            )

            Spacer(modifier = Modifier.height(8.dp))

        // Search section - collapsed or expanded
        if (!searchExpanded) {
            // Collapsed: just show Search button
            Button(
                onClick = { searchExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Blue
                )
            ) {
                Text(if (isSearchActive) "Search (active)" else "Search")
            }
        } else {
            // Expanded: show search fields
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = AppColors.SurfaceDark
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchTitle,
                        onValueChange = { searchTitle = it },
                        label = { Text("Title", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = AppColors.outlinedFieldColors()
                    )
                    OutlinedTextField(
                        value = searchPrompt,
                        onValueChange = { searchPrompt = it },
                        label = { Text("Prompt", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = AppColors.outlinedFieldColors()
                    )
                    OutlinedTextField(
                        value = searchReport,
                        onValueChange = { searchReport = it },
                        label = { Text("Report", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = AppColors.outlinedFieldColors()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                filteredReports = allReports.filter { report ->
                                    searchInReport(report, searchTitle, searchPrompt, searchReport)
                                }
                                currentPage = 0
                                isSearchActive = searchTitle.isNotBlank() || searchPrompt.isNotBlank() || searchReport.isNotBlank()
                                searchExpanded = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Blue
                            )
                        ) {
                            Text("Search")
                        }
                        OutlinedButton(
                            onClick = {
                                searchTitle = ""
                                searchPrompt = ""
                                searchReport = ""
                                filteredReports = allReports
                                currentPage = 0
                                isSearchActive = false
                                searchExpanded = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Pagination controls
        if (totalPages > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3366BB),
                        disabledContainerColor = AppColors.DividerDark
                    )
                ) {
                    Text("◀ Prev")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Page ${currentPage + 1} of $totalPages",
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                    enabled = currentPage < totalPages - 1,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3366BB),
                        disabledContainerColor = AppColors.DividerDark
                    )
                ) {
                    Text("Next ▶")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Table header
        Card(
            colors = CardDefaults.cardColors(
                containerColor = AppColors.SurfaceDark
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Title",
                    color = AppColors.Blue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = "Date/Time",
                    color = AppColors.Blue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // History list
        if (filteredReports.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (allReports.isEmpty()) "No AI reports yet" else "No matching reports",
                    color = AppColors.TextSecondary,
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(currentPageReports, key = { it.id }) { report ->
                    HistoryReportRow(
                        report = report,
                        context = context,
                        developerMode = developerMode,
                        onViewReport = { selectedReportId = report.id },
                        onDeleteReport = {
                            com.ai.data.ReportStorage.deleteReport(context, report.id)
                            allReports = com.ai.data.ReportStorage.getAllReports(context)
                            filteredReports = if (isSearchActive) {
                                allReports.filter { r -> searchInReport(r, searchTitle, searchPrompt, searchReport) }
                            } else {
                                allReports
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear button
        Button(
            onClick = {
                com.ai.data.ReportStorage.deleteAllReports(context)
                allReports = emptyList()
                filteredReports = emptyList()
                currentPage = 0
            },
            enabled = allReports.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFCC3333),
                disabledContainerColor = AppColors.BorderUnfocused
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear history")
        }
        }
    }
}

/**
 * Search in an AI Report object for matching title, prompt, or response content.
 */
private fun searchInReport(
    report: com.ai.data.Report,
    searchTitle: String,
    searchPrompt: String,
    searchReport: String
): Boolean {
    val titleLower = searchTitle.lowercase()
    val promptLower = searchPrompt.lowercase()
    val reportLower = searchReport.lowercase()

    // Check title
    if (titleLower.isNotBlank() && !report.title.lowercase().contains(titleLower)) {
        return false
    }

    // Check prompt
    if (promptLower.isNotBlank() && !report.prompt.lowercase().contains(promptLower)) {
        return false
    }

    // Check report content (search in all agent responses)
    if (reportLower.isNotBlank()) {
        val hasMatchingContent = report.agents.any { agent ->
            agent.responseBody?.lowercase()?.contains(reportLower) == true
        }
        if (!hasMatchingContent) {
            return false
        }
    }

    return true
}

/**
 * Single row in AI History showing title with date/time.
 * Clicking opens actions menu with View, Share, and Browser buttons.
 */
@Composable
private fun HistoryReportRow(
    report: com.ai.data.Report,
    context: android.content.Context,
    developerMode: Boolean,
    onViewReport: () -> Unit,
    onDeleteReport: () -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US) }
    var showActions by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Share dialog
    if (showShareDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = {
                Text("Share Report", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Choose format to share:", color = AppColors.TextSecondary)
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            shareReportAsJson(context, report.id)
                        }
                    ) {
                        Text("JSON", color = AppColors.Blue)
                    }
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            shareReportAsHtml(context, report.id, developerMode)
                        }
                    ) {
                        Text("HTML", color = AppColors.Green)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) {
                    Text("Cancel", color = AppColors.TextTertiary)
                }
            },
            containerColor = AppColors.SurfaceDark,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text("Delete Report", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Are you sure you want to delete \"${report.title}\"?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteReport()
                    }
                ) {
                    Text("Delete", color = AppColors.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = AppColors.TextTertiary)
                }
            },
            containerColor = AppColors.SurfaceDark,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF3A3A3A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showActions = !showActions }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = report.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = dateFormat.format(java.util.Date(report.timestamp)),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }

            // Action buttons (shown when clicked) - same as Reports Ready page
            if (showActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF252525))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onViewReport,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2196F3)
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("View", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showShareDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Green
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Share", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { openReportInChrome(context, report.id, developerMode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Purple
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Browser", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFCC3333)
                        ),
                        modifier = Modifier.weight(0.6f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text("✕", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
