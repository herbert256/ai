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
fun AiHistoryScreenNav(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    developerMode: Boolean = false
) {
    val context = LocalContext.current
    var allReports by remember { mutableStateOf(com.ai.data.AiReportStorage.getAllReports(context)) }
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
    if (selectedReportId != null) {
        AiReportsViewerScreen(
            reportId = selectedReportId!!,
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
            AiTitleBar(
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
                    containerColor = Color(0xFF6B9BFF)
                )
            ) {
                Text(if (isSearchActive) "Search (active)" else "Search")
            }
        } else {
            // Expanded: show search fields
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2A2A2A)
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
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedLabelColor = Color(0xFF6B9BFF),
                            unfocusedLabelColor = Color.Gray,
                            cursorColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = searchPrompt,
                        onValueChange = { searchPrompt = it },
                        label = { Text("Prompt", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedLabelColor = Color(0xFF6B9BFF),
                            unfocusedLabelColor = Color.Gray,
                            cursorColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = searchReport,
                        onValueChange = { searchReport = it },
                        label = { Text("Report", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6B9BFF),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedLabelColor = Color(0xFF6B9BFF),
                            unfocusedLabelColor = Color.Gray,
                            cursorColor = Color.White
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                filteredReports = allReports.filter { report ->
                                    searchInAiReport(report, searchTitle, searchPrompt, searchReport)
                                }
                                currentPage = 0
                                isSearchActive = searchTitle.isNotBlank() || searchPrompt.isNotBlank() || searchReport.isNotBlank()
                                searchExpanded = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6B9BFF)
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
                        disabledContainerColor = Color(0xFF333333)
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
                        disabledContainerColor = Color(0xFF333333)
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
                containerColor = Color(0xFF2A2A2A)
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
                    color = Color(0xFF6B9BFF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = "Date/Time",
                    color = Color(0xFF6B9BFF),
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
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(currentPageReports) { report ->
                    AiHistoryReportRow(
                        report = report,
                        context = context,
                        developerMode = developerMode,
                        onViewReport = { selectedReportId = report.id },
                        onDeleteReport = {
                            com.ai.data.AiReportStorage.deleteReport(context, report.id)
                            allReports = com.ai.data.AiReportStorage.getAllReports(context)
                            filteredReports = if (isSearchActive) {
                                allReports.filter { r -> searchInAiReport(r, searchTitle, searchPrompt, searchReport) }
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
                com.ai.data.AiReportStorage.deleteAllReports(context)
                allReports = emptyList()
                filteredReports = emptyList()
                currentPage = 0
            },
            enabled = allReports.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFCC3333),
                disabledContainerColor = Color(0xFF444444)
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
private fun searchInAiReport(
    report: com.ai.data.AiReport,
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
private fun AiHistoryReportRow(
    report: com.ai.data.AiReport,
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
                    Text("Choose format to share:", color = Color(0xFFAAAAAA))
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            shareAiReportAsJson(context, report.id)
                        }
                    ) {
                        Text("JSON", color = Color(0xFF6B9BFF))
                    }
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            shareAiReportAsHtml(context, report.id, developerMode)
                        }
                    ) {
                        Text("HTML", color = Color(0xFF4CAF50))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            },
            containerColor = Color(0xFF2A2A2A),
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
                    Text("Delete", color = Color(0xFFFF6B6B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            },
            containerColor = Color(0xFF2A2A2A),
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
                            containerColor = Color(0xFF4CAF50)
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Share", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { openAiReportInChrome(context, report.id, developerMode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B5CF6)
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

/**
 * Extracts the title from an HTML file by finding the h1 element.
 * Returns the title, or the filename if not found.
 */
private fun extractTitleFromHtmlFile(file: java.io.File): String {
    return try {
        val html = file.readText()
        // Look for the title in <h1>...</h1>
        val startMarker = "<h1>"
        val endMarker = "</h1>"
        val startIndex = html.indexOf(startMarker)
        if (startIndex == -1) return file.nameWithoutExtension

        val contentStart = startIndex + startMarker.length
        val endIndex = html.indexOf(endMarker, contentStart)
        if (endIndex == -1) return file.nameWithoutExtension

        val title = html.substring(contentStart, endIndex)
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

        if (title.isNotBlank()) title else file.nameWithoutExtension
    } catch (e: Exception) {
        file.nameWithoutExtension
    }
}

/**
 * Extracts the prompt from an HTML file by finding the prompt-text pre element.
 * Returns the first 3 lines of the prompt, or empty string if not found.
 */
private fun extractPromptFromHtmlFile(file: java.io.File): String {
    return try {
        val html = file.readText()
        // Look for the prompt in <pre class="prompt-text">...</pre>
        val startMarker = """<pre class="prompt-text">"""
        val endMarker = "</pre>"
        val startIndex = html.indexOf(startMarker)
        if (startIndex == -1) return ""

        val contentStart = startIndex + startMarker.length
        val endIndex = html.indexOf(endMarker, contentStart)
        if (endIndex == -1) return ""

        val promptHtml = html.substring(contentStart, endIndex)
        // Unescape HTML entities
        val prompt = promptHtml
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")

        // Get first 3 non-empty lines
        val lines = prompt.trim().lines().filter { it.isNotBlank() }.take(3)
        lines.joinToString("\n")
    } catch (e: Exception) {
        ""
    }
}

/**
 * Share a history file via Android share sheet.
 */
private fun shareHistoryFileNav(context: android.content.Context, file: java.io.File) {
    try {
        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, "AI Report - ${file.nameWithoutExtension}")
            putExtra(Intent.EXTRA_TEXT, "AI analysis report.\n\nOpen the attached HTML file in a browser to view the report.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share AI Report"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to share: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Open a history file in Chrome browser.
 */
private fun openHistoryFileInChromeNav(context: android.content.Context, file: java.io.File) {
    try {
        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.android.chrome")
        }

        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            intent.setPackage(null)
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to open in Chrome: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}
