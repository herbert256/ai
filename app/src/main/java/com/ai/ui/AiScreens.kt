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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ai.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AiHistoryManager
import com.ai.data.AiHistoryFileInfo
import com.ai.data.ChatHistoryManager

/**
 * AI hub screen - the home page of the app.
 * Shows links to AI Reports, AI Statistics, AI Chat, and AI Models.
 * Also has navigation icons for Settings, Trace, and Help.
 */
@Composable
fun AiHubScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToTrace: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToReportsHub: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToCosts: () -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToChatHistory: () -> Unit,
    onNavigateToAiSetup: () -> Unit,
    onNavigateToModelSearch: () -> Unit,
    viewModel: AiViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    // Chat choice dialog state
    var showChatChoiceDialog by remember { mutableStateOf(false) }
    val hasChatHistory = remember { ChatHistoryManager.getSessionCount() > 0 }

    // Check if any provider has an API key configured
    val hasAnyApiKey = uiState.aiSettings.chatGptApiKey.isNotBlank() ||
            uiState.aiSettings.claudeApiKey.isNotBlank() ||
            uiState.aiSettings.geminiApiKey.isNotBlank() ||
            uiState.aiSettings.grokApiKey.isNotBlank() ||
            uiState.aiSettings.groqApiKey.isNotBlank() ||
            uiState.aiSettings.deepSeekApiKey.isNotBlank() ||
            uiState.aiSettings.mistralApiKey.isNotBlank() ||
            uiState.aiSettings.perplexityApiKey.isNotBlank() ||
            uiState.aiSettings.togetherApiKey.isNotBlank() ||
            uiState.aiSettings.openRouterApiKey.isNotBlank()

    // Check if any agents are defined (excluding DUMMY agents when not in developer mode)
    val hasAnyAgent = uiState.aiSettings.agents.any { agent ->
        if (uiState.generalSettings.developerMode) true
        else agent.provider != com.ai.data.AiService.DUMMY
    }

    // Check if setup is complete (no warnings)
    val isSetupComplete = hasAnyApiKey &&
            hasAnyAgent &&
            uiState.aiSettings.swarms.isNotEmpty()

    // Calculate number of cards and required height
    val cardHeight = 50.dp  // HubCard height (icon 34sp + padding)
    val cardSpacing = 7.dp
    val largeSpacing = 28.dp
    val warningHeight = 76.dp  // Warning card height including spacer

    // Count cards that will be shown
    var cardCount = 3  // AI Setup, Settings, Help (always shown)
    if (isSetupComplete) cardCount += 3  // AI Reports, AI Statistics, AI Costs
    if (hasAnyAgent) cardCount += 2  // AI Chat, AI Models
    if (uiState.generalSettings.developerMode) cardCount += 1  // API Traces

    // Calculate total height needed for cards
    val cardsHeight = (cardHeight * cardCount) + (cardSpacing * (cardCount - 1)) + largeSpacing
    val warningCardHeight = if (!isSetupComplete) warningHeight else 0.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        // Calculate logo size based on available height
        val availableForLogo = maxHeight - cardsHeight - warningCardHeight
        val logoSize = availableForLogo.coerceIn(90.dp, 260.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App logo with dynamic size
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "AI App Logo",
                modifier = Modifier.size(logoSize)
            )

            // Warning if no API keys configured
            if (!hasAnyApiKey) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4A2A2A)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "❌", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "No API keys configured. Go to AI Setup → AI Providers to add an API key.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF8080)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            } else if (!hasAnyAgent) {
                // Warning if no agents configured (only show if API keys exist, excludes DUMMY when not in dev mode)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4A3A2A)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "⚠️", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "No AI agents configured. Go to AI Setup → AI Agents to add your first agent.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFFCC80)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            } else if (uiState.aiSettings.swarms.isEmpty()) {
                // Warning if no swarms configured (only show if agents exist)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF3A3A4A)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "\uD83D\uDC1D", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "No AI swarms configured. Go to AI Setup → AI Swarms to create your first swarm.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0B0FF)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Cards - only show AI features when setup is complete
            if (isSetupComplete) {
                HubCard(icon = "\uD83D\uDCDD", title = "AI Reports", onClick = onNavigateToReportsHub)
                Spacer(modifier = Modifier.height(7.dp))
            }
            // AI Chat - show when any agent is defined
            if (hasAnyAgent) {
                HubCard(icon = "\uD83D\uDCAC", title = "AI Chat", onClick = {
                    if (hasChatHistory) {
                        showChatChoiceDialog = true
                    } else {
                        onNavigateToNewChat()
                    }
                })
                Spacer(modifier = Modifier.height(7.dp))
            }

            // Chat choice dialog
            if (showChatChoiceDialog) {
                AlertDialog(
                    onDismissRequest = { showChatChoiceDialog = false },
                    title = { Text("AI Chat", color = Color.White) },
                    text = {
                        Text(
                            "Would you like to start a new chat or continue with a previous chat?",
                            color = Color(0xFFAAAAAA)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showChatChoiceDialog = false
                            onNavigateToNewChat()
                        }) {
                            Text("Start new chat", color = Color(0xFF6B9BFF))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showChatChoiceDialog = false
                            onNavigateToChatHistory()
                        }) {
                            Text("Continue with previous chat", color = Color(0xFF8B5CF6))
                        }
                    },
                    containerColor = Color(0xFF2A2A2A)
                )
            }
            // AI Models - show when any agent is defined
            if (hasAnyAgent) {
                HubCard(icon = "\uD83E\uDDE0", title = "AI Models", onClick = onNavigateToModelSearch)
                Spacer(modifier = Modifier.height(7.dp))
            }
            if (isSetupComplete) {
                HubCard(icon = "\uD83D\uDCCA", title = "AI Statistics", onClick = onNavigateToStatistics)
                Spacer(modifier = Modifier.height(7.dp))
                HubCard(icon = "\uD83D\uDCB0", title = "AI Costs", onClick = onNavigateToCosts)
                Spacer(modifier = Modifier.height(7.dp))
            }
            HubCard(icon = "\uD83E\uDD16", title = "AI Setup", onClick = onNavigateToAiSetup)
            Spacer(modifier = Modifier.height(28.dp))
            HubCard(icon = "\u2699\uFE0F", title = "Settings", onClick = onNavigateToSettings)
            Spacer(modifier = Modifier.height(7.dp))
            HubCard(icon = "\u2753", title = "Help", onClick = onNavigateToHelp)

            // API Traces card (developer mode only)
            if (uiState.generalSettings.developerMode) {
                Spacer(modifier = Modifier.height(7.dp))
                HubCard(icon = "\uD83D\uDC1E", title = "API Traces", onClick = onNavigateToTrace)
            }
        }
    }
}

/**
 * Compact card component for the AI Hub screen.
 */
@Composable
private fun HubCard(
    icon: String,
    title: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A3A4A)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 34.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

/**
 * Search within an HTML file for content in specific sections.
 * Returns true if all non-empty search terms are found in their respective sections.
 */
private fun searchInHtmlFile(
    file: java.io.File,
    titleSearch: String,
    promptSearch: String,
    reportSearch: String
): Boolean {
    if (titleSearch.isBlank() && promptSearch.isBlank() && reportSearch.isBlank()) {
        return true  // No search criteria, match all
    }

    return try {
        val content = file.readText()

        // Search in Title section
        if (titleSearch.isNotBlank()) {
            val titlePattern = """<div id="Title">(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val titleMatch = titlePattern.find(content)
            if (titleMatch == null || !titleMatch.groupValues[1].contains(titleSearch, ignoreCase = true)) {
                return false
            }
        }

        // Search in Prompt section
        if (promptSearch.isNotBlank()) {
            val promptPattern = """<div id="Prompt"[^>]*>(.*?)</div>\s*<div class="footer">""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val promptMatch = promptPattern.find(content)
            if (promptMatch == null || !promptMatch.groupValues[1].contains(promptSearch, ignoreCase = true)) {
                return false
            }
        }

        // Search in Report sections (any agent's report)
        if (reportSearch.isNotBlank()) {
            val reportPattern = """<div id="Report-[^"]*"[^>]*>(.*?)</div></div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val reportMatches = reportPattern.findAll(content)
            val foundInReport = reportMatches.any { it.groupValues[1].contains(reportSearch, ignoreCase = true) }
            if (!foundInReport) {
                return false
            }
        }

        true
    } catch (e: Exception) {
        false
    }
}

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

            // Action buttons (shown when clicked) - same as Report Ready page
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

/**
 * AI Reports hub screen.
 * Shows links to New AI Report, Prompt History, and AI History.
 */
@Composable
fun AiReportsHubScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToNewReport: () -> Unit,
    onNavigateToPromptHistory: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI Reports",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(24.dp))

        // New AI Report card
        HubCard(
            icon = "\uD83D\uDCDD",
            title = "New AI Report",
            onClick = onNavigateToNewReport
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Start with a previous prompt card
        HubCard(
            icon = "\uD83D\uDD04",
            title = "Start with a previous prompt",
            onClick = onNavigateToPromptHistory
        )

        Spacer(modifier = Modifier.height(12.dp))

        // View previous reports card
        HubCard(
            icon = "\uD83D\uDCDA",
            title = "View previous reports",
            onClick = onNavigateToHistory
        )
    }
}

/**
 * New AI Report screen for entering a custom prompt.
 * Used as a navigation destination.
 */
@Composable
fun AiNewReportScreen(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToAiReports: () -> Unit = {},
    initialTitle: String = "",
    initialPrompt: String = "",
    useLastSavedValues: Boolean = true
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Load last used title and prompt from SharedPreferences (only if useLastSavedValues is true)
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val lastTitle = if (useLastSavedValues) prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, "") ?: "" else ""
    val lastPrompt = if (useLastSavedValues) prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, "") ?: "" else ""

    // Use initialTitle/initialPrompt if provided (from prompt history or external app),
    // otherwise use last saved values (if enabled), otherwise start empty
    var title by remember(initialTitle, initialPrompt, useLastSavedValues) {
        mutableStateOf(initialTitle.ifEmpty { lastTitle })
    }
    var prompt by remember(initialTitle, initialPrompt, useLastSavedValues) {
        mutableStateOf(initialPrompt.ifEmpty { lastPrompt })
    }

    // Navigate to AI Reports screen when agent selection is triggered
    LaunchedEffect(uiState.showGenericAiAgentSelection) {
        if (uiState.showGenericAiAgentSelection) {
            viewModel.dismissGenericAiAgentSelection()
            onNavigateToAiReports()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "New AI Report",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Submit button at top
        Button(
            onClick = {
                if (title.isNotBlank() && prompt.isNotBlank()) {
                    // Save as last used title and prompt
                    prefs.edit()
                        .putString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, title)
                        .putString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, prompt)
                        .apply()

                    // Save to prompt history
                    val settingsPrefs = SettingsPreferences(prefs)
                    settingsPrefs.savePromptToHistory(title, prompt)

                    viewModel.showGenericAiAgentSelection(title, prompt)
                }
            },
            enabled = title.isNotBlank() && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF8B5CF6)
            )
        ) {
            Text("Submit", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title field
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            placeholder = { Text("Enter a title for the report") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444),
                focusedLabelColor = Color(0xFF8B5CF6),
                unfocusedLabelColor = Color.Gray,
                cursorColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Prompt field
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("AI Prompt") },
            placeholder = { Text("Enter your prompt for the AI...") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            minLines = 10,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF8B5CF6),
                unfocusedBorderColor = Color(0xFF444444),
                focusedLabelColor = Color(0xFF8B5CF6),
                unfocusedLabelColor = Color.Gray,
                cursorColor = Color.White
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Clear button
        OutlinedButton(
            onClick = {
                title = ""
                prompt = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear")
        }
    }
}

/**
 * Process <think>...</think> sections in AI response for HTML output.
 * Replaces think sections with collapsible buttons and hidden content.
 */
private fun processThinkSectionsForHtml(text: String, agentId: String): String {
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    var result = text
    var thinkIndex = 0

    result = thinkPattern.replace(result) { match ->
        val thinkContent = match.groupValues[1]
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val id = "${agentId}-${thinkIndex++}"
        """<button id="think-btn-$id" class="think-btn" onclick="toggleThink('$id')">Think</button><div id="think-$id" class="think-content">$thinkContent</div>"""
    }

    // Process remaining text parts - convert markdown to HTML
    // We need to be careful here - the think sections are already processed
    // So we only convert the parts that aren't our inserted HTML
    val parts = result.split(Regex("(<button.*?</button><div.*?</div>)"))
    val processedParts = parts.mapIndexed { index, part ->
        if (index % 2 == 0) {
            // This is regular text, convert markdown to HTML
            convertMarkdownToHtmlForExport(part)
        } else {
            // This is our inserted HTML, keep it as-is
            part
        }
    }

    return processedParts.joinToString("")
}

/**
 * Converts markdown to HTML for HTML file export.
 * Similar to convertMarkdownToSimpleHtml but returns HTML string for embedding.
 */
private fun convertMarkdownToHtmlForExport(markdown: String): String {
    if (markdown.isBlank()) return ""

    // First normalize line endings and remove multiple blank lines
    var html = markdown
        .replace("\r\n", "\n")
        .replace(Regex("\n{3,}"), "\n\n")  // Replace 3+ newlines with 2

    // Escape HTML entities first
    html = html
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // Basic markdown to HTML conversion
    html = html
        // Code blocks (triple backticks) - must be before other processing
        .replace(Regex("```([\\s\\S]*?)```")) { match ->
            "<pre><code>${match.groupValues[1].trim()}</code></pre>"
        }
        // Inline code (single backticks)
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        // Headers
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        // Bold
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        // Italic
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        // Bullet points
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        // Numbered lists
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        // Line breaks - convert double newlines to paragraph breaks
        .replace("\n\n", "</p><p>")
        .replace("\n", "<br>")

    // Wrap consecutive <li> items in <ul>
    html = html.replace(Regex("(<li>.*?</li>)+")) { match ->
        "<ul>${match.value}</ul>"
    }

    // Clean up excessive whitespace in HTML
    html = html
        // Remove multiple consecutive <br> tags (2 or more become 1)
        .replace(Regex("(<br>){2,}"), "<br>")
        // Remove <br> before block elements (headings, lists, pre)
        .replace(Regex("<br>(<h[234]>)"), "$1")
        .replace(Regex("<br>(<ul>)"), "$1")
        .replace(Regex("<br>(<pre>)"), "$1")
        // Remove <br> after block elements
        .replace(Regex("(</h[234]>)<br>"), "$1")
        .replace(Regex("(</ul>)<br>"), "$1")
        .replace(Regex("(</pre>)<br>"), "$1")
        // Clean up empty paragraphs
        .replace(Regex("<p></p>"), "")
        .replace(Regex("</p><p><br></p><p>"), "</p><p>")

    // Wrap in paragraph if not empty
    if (html.isNotBlank()) {
        html = "<p>$html</p>"
    }

    return html
}

// Helper function to convert generic AI reports to HTML
internal fun convertGenericAiReportsToHtml(uiState: AiUiState, appVersion: String): String {
    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date())

    val agentResults = uiState.genericAiReportsAgentResults
    val title = uiState.genericAiPromptTitle
    val prompt = uiState.genericAiPromptText
    val developerMode = uiState.generalSettings.developerMode

    // Get sorted list of agents with results
    val agentList = agentResults.entries.mapNotNull { (agentId, response) ->
        val agent = uiState.aiSettings.getAgentById(agentId)
        if (agent != null) Triple(agentId, agent, response) else null
    }.sortedBy { it.second.name.lowercase() }

    val htmlBuilder = StringBuilder()
    htmlBuilder.append("""
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>AI Report - $title</title>
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    background: #1a1a1a;
                    color: #e0e0e0;
                    margin: 0;
                    padding: 20px;
                    line-height: 1.6;
                }
                .container { max-width: 800px; margin: 0 auto; }
                h1 { color: #6B9BFF; border-bottom: 2px solid #333; padding-bottom: 10px; }
                h2 { color: #6B9BFF; font-size: 1.1em; margin-top: 30px; margin-bottom: 10px; }
                .prompt-section { margin: 30px 0; }
                .prompt-label { color: #6B9BFF; font-weight: bold; font-size: 1.1em; margin-bottom: 10px; }
                .prompt-text {
                    white-space: pre-wrap;
                    margin: 0;
                    font-family: monospace;
                    font-size: 0.9em;
                    color: #ccc;
                }
                .agent-buttons {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 8px;
                    margin: 20px 0;
                }
                .agent-btn {
                    padding: 8px 16px;
                    border: 1px solid #444;
                    background: transparent;
                    color: #e0e0e0;
                    cursor: pointer;
                    font-size: 14px;
                }
                .agent-btn:hover {
                    border-color: #6B9BFF;
                }
                .agent-btn.active {
                    border-color: #6B9BFF;
                    color: #6B9BFF;
                }
                .agent-result {
                    display: none;
                    margin: 20px 0;
                }
                .agent-result.active {
                    display: block;
                }
                .agent-header { color: #6B9BFF; font-weight: bold; font-size: 1.1em; margin-bottom: 10px; }
                .agent-response { }
                .agent-response p { margin: 0 0 1em 0; }
                .agent-response h2 { color: #6B9BFF; font-size: 1.3em; margin: 1.2em 0 0.5em 0; }
                .agent-response h3 { color: #6B9BFF; font-size: 1.15em; margin: 1em 0 0.4em 0; }
                .agent-response h4 { color: #6B9BFF; font-size: 1.05em; margin: 0.8em 0 0.3em 0; }
                .agent-response ul { margin: 0.5em 0; padding-left: 1.5em; }
                .agent-response li { margin: 0.3em 0; }
                .agent-response code { background: #333; padding: 2px 6px; border-radius: 3px; font-family: monospace; font-size: 0.9em; }
                .agent-response pre { background: #2a2a2a; padding: 12px; border-radius: 6px; overflow-x: auto; margin: 1em 0; }
                .agent-response pre code { background: none; padding: 0; }
                .agent-response strong { color: #fff; }
                .agent-response em { font-style: italic; }
                .error { color: #ff6b6b; }
                .usage-section { margin: 30px 0; }
                .sources-section { margin-top: 20px; }
                .sources-label { color: #8B5CF6; font-weight: bold; font-size: 1em; margin-bottom: 10px; }
                .source-link { color: #64B5F6; text-decoration: underline; margin-left: 5px; }
                .source-item { margin: 4px 0; }
                .search-results-section { margin-top: 20px; }
                .search-results-label { color: #FF9800; font-weight: bold; font-size: 1em; margin-bottom: 10px; }
                .search-result { margin: 8px 0; }
                .search-result-title { color: #64B5F6; text-decoration: underline; font-weight: 500; }
                .search-result-snippet { color: #aaa; font-size: 0.9em; margin-top: 2px; }
                .related-questions-section { margin-top: 20px; }
                .related-questions-label { color: #4CAF50; font-weight: bold; font-size: 1em; margin-bottom: 10px; }
                .related-question { margin: 4px 0; color: #e0e0e0; }
                .usage-label { color: #6B9BFF; font-weight: bold; font-size: 1.1em; margin-top: 20px; margin-bottom: 10px; }
                .usage-json {
                    white-space: pre-wrap;
                    margin: 0 0 15px 0;
                    font-family: monospace;
                    font-size: 0.85em;
                    color: #aaa;
                }
                .headers-text {
                    white-space: pre-wrap;
                    margin: 0 0 15px 0;
                    font-family: monospace;
                    font-size: 0.8em;
                    color: #777;
                }
                .usage-agent { color: #888; font-size: 0.9em; margin-top: 15px; margin-bottom: 5px; }
                .footer {
                    margin-top: 40px;
                    padding-top: 20px;
                    border-top: 1px solid #333;
                    color: #666;
                    font-size: 0.9em;
                    text-align: center;
                }
                .think-btn {
                    padding: 4px 12px;
                    border: 1px solid #666;
                    background: #2a2a2a;
                    color: #aaa;
                    cursor: pointer;
                    font-size: 13px;
                    margin: 8px 0;
                    border-radius: 4px;
                }
                .think-btn:hover {
                    border-color: #888;
                    color: #ccc;
                }
                .think-content {
                    display: none;
                    background: #252525;
                    border-left: 3px solid #555;
                    padding: 12px;
                    margin: 8px 0;
                    color: #999;
                    font-size: 0.9em;
                    white-space: pre-wrap;
                }
                .think-content.visible {
                    display: block;
                }
            </style>
            <script>
                function toggleThink(id) {
                    var content = document.getElementById('think-' + id);
                    var btn = document.getElementById('think-btn-' + id);
                    if (content.classList.contains('visible')) {
                        content.classList.remove('visible');
                        btn.textContent = 'Think';
                    } else {
                        content.classList.add('visible');
                        btn.textContent = 'Hide Think';
                    }
                }
            </script>
        </head>
        <body>
            <div class="container">
                <div id="Title"><h1>$title</h1></div>

                <div class="agent-buttons">
    """.trimIndent())

    // Add agent buttons
    agentList.forEachIndexed { index, (agentId, agent, _) ->
        val activeClass = if (index == 0) "active" else ""
        htmlBuilder.append("""
                    <button class="agent-btn $activeClass" onclick="showAgent('$agentId')">${agent.name}</button>
        """)
    }

    htmlBuilder.append("""
                </div>
    """)

    // Add each agent's response section
    agentList.forEachIndexed { index, (agentId, agent, response) ->
        val activeClass = if (index == 0) "active" else ""
        htmlBuilder.append("""
                <div id="agent-$agentId" class="agent-result $activeClass">
                    <div class="agent-header">${agent.provider.displayName} - ${agent.model}</div>
                    <div id="Report-$agentId" class="report-content">
        """)

        if (response.error != null) {
            htmlBuilder.append("""
                    <div class="error">Error: ${response.error}</div>
            """)
        } else {
            val rawAnalysis = response.analysis ?: "No response"
            // Process <think>...</think> sections before escaping HTML
            val processedAnalysis = processThinkSectionsForHtml(rawAnalysis, agentId)
            htmlBuilder.append("""
                    <div class="agent-response">$processedAnalysis</div>
            """)
        }

        // Add citations if available
        response.citations?.takeIf { it.isNotEmpty() }?.let { citations ->
            htmlBuilder.append("""
                    <div class="sources-section">
                        <div class="sources-label">Sources</div>
            """)
            citations.forEachIndexed { index, url ->
                val escapedUrl = url.replace("\"", "&quot;")
                htmlBuilder.append("""
                        <div class="source-item">${index + 1}.<a href="$escapedUrl" class="source-link" target="_blank">$escapedUrl</a></div>
                """)
            }
            htmlBuilder.append("</div>")
        }

        // Add search results if available
        response.searchResults?.takeIf { it.isNotEmpty() }?.let { searchResults ->
            htmlBuilder.append("""
                    <div class="search-results-section">
                        <div class="search-results-label">Search Results</div>
            """)
            searchResults.forEachIndexed { index, result ->
                val title = (result.name ?: result.url ?: "Link").replace("<", "&lt;").replace(">", "&gt;")
                val url = result.url?.replace("\"", "&quot;") ?: ""
                val snippet = result.snippet?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""
                htmlBuilder.append("""
                        <div class="search-result">
                            ${index + 1}. <a href="$url" class="search-result-title" target="_blank">$title</a>
                """)
                if (snippet.isNotEmpty()) {
                    htmlBuilder.append("""
                            <div class="search-result-snippet">$snippet</div>
                    """)
                }
                htmlBuilder.append("</div>")
            }
            htmlBuilder.append("</div>")
        }

        // Add related questions if available
        response.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { relatedQuestions ->
            htmlBuilder.append("""
                    <div class="related-questions-section">
                        <div class="related-questions-label">Related Questions</div>
            """)
            relatedQuestions.forEachIndexed { index, question ->
                val escapedQuestion = question.replace("<", "&lt;").replace(">", "&gt;")
                htmlBuilder.append("""
                        <div class="related-question">${index + 1}. $escapedQuestion</div>
                """)
            }
            htmlBuilder.append("</div>")
        }

        // Add usage data for this agent when developer mode is on
        if (developerMode && agent.provider != com.ai.data.AiService.DUMMY && response.rawUsageJson != null) {
            val escapedJson = response.rawUsageJson.replace("<", "&lt;").replace(">", "&gt;")
            htmlBuilder.append("""
                    <div class="usage-label">API Usage:</div>
                    <pre class="usage-json">$escapedJson</pre>
            """)
        }

        // Add HTTP headers for this agent when developer mode is on
        if (developerMode && agent.provider != com.ai.data.AiService.DUMMY && response.httpHeaders != null) {
            val escapedHeaders = response.httpHeaders.replace("<", "&lt;").replace(">", "&gt;")
            htmlBuilder.append("""
                    <div class="usage-label">HTTP Headers:</div>
                    <pre class="headers-text">$escapedHeaders</pre>
            """)
        }

        htmlBuilder.append("</div></div>")  // Close report-content and agent-result divs
    }

    htmlBuilder.append("""
                <div id="Prompt" class="prompt-section">
                    <div class="prompt-label">Prompt:</div>
                    <pre class="prompt-text">${prompt.replace("<", "&lt;").replace(">", "&gt;")}</pre>
                </div>
    """)

    htmlBuilder.append("""
                <div class="footer">
                    Generated by AI v$appVersion on $timestamp
                </div>
            </div>

            <script>
                function showAgent(agentId) {
                    // Hide all agent results
                    document.querySelectorAll('.agent-result').forEach(el => {
                        el.classList.remove('active');
                    });
                    // Deactivate all buttons
                    document.querySelectorAll('.agent-btn').forEach(el => {
                        el.classList.remove('active');
                    });
                    // Show selected agent result
                    document.getElementById('agent-' + agentId).classList.add('active');
                    // Activate clicked button
                    event.target.classList.add('active');
                }
            </script>
        </body>
        </html>
    """.trimIndent())

    return htmlBuilder.toString()
}

// Helper functions for sharing/opening generic AI reports
internal fun shareGenericAiReports(context: android.content.Context, uiState: AiUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertGenericAiReportsToHtml(uiState, appVersion)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val title = uiState.genericAiPromptTitle
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val htmlFile = java.io.File(cacheDir, "ai_$timestamp.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, "AI Report - $title")
            putExtra(Intent.EXTRA_TEXT, "AI analysis report: $title.\n\nOpen the attached HTML file in a browser to view the report.")
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
 * Shares the AI-REPORT as JSON using the standard Android share mechanism.
 */
internal fun shareAiReportAsJson(context: android.content.Context, reportId: String) {
    try {
        val report = com.ai.data.AiReportStorage.getReport(context, reportId)
        if (report == null) {
            android.widget.Toast.makeText(context, "Report not found", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(report)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val jsonFile = java.io.File(cacheDir, "ai_report_$timestamp.json")
        jsonFile.writeText(json)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            jsonFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "AI Report - ${report.title}")
            putExtra(Intent.EXTRA_TEXT, "AI Report: ${report.title}\n\nAttached as JSON file.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share AI Report (JSON)"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to share: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Shares the AI-REPORT as HTML using the standard Android share mechanism.
 */
internal fun shareAiReportAsHtml(context: android.content.Context, reportId: String, developerMode: Boolean = false) {
    try {
        val report = com.ai.data.AiReportStorage.getReport(context, reportId)
        if (report == null) {
            android.widget.Toast.makeText(context, "Report not found", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        val html = convertAiReportToHtml(report, appVersion, developerMode)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val htmlFile = java.io.File(cacheDir, "ai_report_$timestamp.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, "AI Report - ${report.title}")
            putExtra(Intent.EXTRA_TEXT, "AI analysis report: ${report.title}.\n\nOpen the attached HTML file in a browser to view the report.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share AI Report (HTML)"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to share: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

internal fun openGenericAiReportsInChrome(context: android.content.Context, uiState: AiUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertGenericAiReportsToHtml(uiState, appVersion)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val htmlFile = java.io.File(cacheDir, "ai_$timestamp.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to open in Chrome: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Opens the AI Report in Chrome browser, generating HTML on demand from the stored AI-REPORT object.
 */
internal fun openAiReportInChrome(context: android.content.Context, reportId: String, developerMode: Boolean = false) {
    try {
        val report = com.ai.data.AiReportStorage.getReport(context, reportId)
        if (report == null) {
            android.widget.Toast.makeText(context, "Report not found", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        val html = convertAiReportToHtml(report, appVersion, developerMode)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val htmlFile = java.io.File(cacheDir, "ai_$timestamp.html")
        htmlFile.writeText(html)

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            htmlFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "text/html")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context,
            "Failed to open in Chrome: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Converts a stored AI-REPORT object to HTML format.
 */
internal fun convertAiReportToHtml(report: com.ai.data.AiReport, appVersion: String, developerMode: Boolean = false): String {
    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(report.timestamp))

    val title = report.title
    val prompt = report.prompt

    // Get sorted list of agents with results
    val agentList = report.agents.sortedBy { it.agentName.lowercase() }

    val htmlBuilder = StringBuilder()
    htmlBuilder.append("""
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>AI Report - $title</title>
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    background: #1a1a1a;
                    color: #e0e0e0;
                    margin: 0;
                    padding: 20px;
                    line-height: 1.6;
                }
                .container { max-width: 800px; margin: 0 auto; }
                h1 { color: #6B9BFF; border-bottom: 2px solid #333; padding-bottom: 10px; }
                h2 { color: #6B9BFF; font-size: 1.1em; margin-top: 30px; margin-bottom: 10px; }
                .prompt-section { margin: 30px 0; }
                .prompt-label { color: #6B9BFF; font-weight: bold; font-size: 1.1em; margin-bottom: 10px; }
                .prompt-text {
                    white-space: pre-wrap;
                    margin: 0;
                    font-family: monospace;
                    font-size: 0.9em;
                    color: #ccc;
                }
                .agent-buttons {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 8px;
                    margin: 20px 0;
                }
                .agent-btn {
                    padding: 8px 16px;
                    border: 1px solid #444;
                    background: transparent;
                    color: #e0e0e0;
                    cursor: pointer;
                    font-size: 14px;
                }
                .agent-btn:hover {
                    border-color: #6B9BFF;
                }
                .agent-btn.active {
                    border-color: #6B9BFF;
                    color: #6B9BFF;
                }
                .agent-result {
                    display: none;
                    margin: 20px 0;
                }
                .agent-result.active {
                    display: block;
                }
                .agent-header { color: #6B9BFF; font-weight: bold; font-size: 1.1em; margin-bottom: 10px; }
                .agent-response { }
                .agent-response p { margin: 0 0 1em 0; }
                .agent-response h2 { color: #6B9BFF; font-size: 1.3em; margin: 1.2em 0 0.5em 0; }
                .agent-response h3 { color: #6B9BFF; font-size: 1.15em; margin: 1em 0 0.4em 0; }
                .agent-response h4 { color: #6B9BFF; font-size: 1.05em; margin: 0.8em 0 0.3em 0; }
                .agent-response ul { margin: 0.5em 0; padding-left: 1.5em; }
                .agent-response li { margin: 0.3em 0; }
                .agent-response code { background: #333; padding: 2px 6px; border-radius: 3px; font-family: monospace; font-size: 0.9em; }
                .agent-response pre { background: #2a2a2a; padding: 12px; border-radius: 6px; overflow-x: auto; margin: 1em 0; }
                .agent-response pre code { background: none; padding: 0; }
                .agent-response strong { color: #fff; }
                .agent-response em { font-style: italic; }
                .error { color: #ff6b6b; }
                .usage-section { margin: 30px 0; }
                .sources-section { margin-top: 20px; }
                .sources-label { color: #8B5CF6; font-weight: bold; font-size: 1em; margin-bottom: 10px; }
                .source-link { color: #64B5F6; text-decoration: underline; margin-left: 5px; }
                .source-item { margin: 4px 0; }
                .search-results-section { margin-top: 20px; }
                .search-results-label { color: #FF9800; font-weight: bold; font-size: 1em; margin-bottom: 10px; }
                .search-result { margin: 8px 0; }
                .search-result-title { color: #64B5F6; text-decoration: underline; font-weight: 500; }
                .search-result-snippet { color: #aaa; font-size: 0.9em; margin-top: 2px; }
                .related-questions-section { margin-top: 20px; }
                .related-questions-label { color: #4CAF50; font-weight: bold; font-size: 1em; margin-bottom: 10px; }
                .related-question { margin: 4px 0; color: #e0e0e0; }
                .usage-label { color: #6B9BFF; font-weight: bold; font-size: 1.1em; margin-top: 20px; margin-bottom: 10px; }
                .usage-json {
                    white-space: pre-wrap;
                    margin: 0 0 15px 0;
                    font-family: monospace;
                    font-size: 0.85em;
                    color: #aaa;
                }
                .headers-text {
                    white-space: pre-wrap;
                    margin: 0 0 15px 0;
                    font-family: monospace;
                    font-size: 0.8em;
                    color: #777;
                }
                .usage-agent { color: #888; font-size: 0.9em; margin-top: 15px; margin-bottom: 5px; }
                .footer {
                    margin-top: 40px;
                    padding-top: 20px;
                    border-top: 1px solid #333;
                    color: #666;
                    font-size: 0.9em;
                    text-align: center;
                }
                .think-btn {
                    padding: 4px 12px;
                    border: 1px solid #666;
                    background: #2a2a2a;
                    color: #aaa;
                    cursor: pointer;
                    font-size: 13px;
                    margin: 8px 0;
                    border-radius: 4px;
                }
                .think-btn:hover {
                    border-color: #888;
                    color: #ccc;
                }
                .think-content {
                    display: none;
                    background: #252525;
                    border-left: 3px solid #555;
                    padding: 12px;
                    margin: 8px 0;
                    color: #999;
                    font-size: 0.9em;
                    white-space: pre-wrap;
                }
                .think-content.visible {
                    display: block;
                }
            </style>
            <script>
                function toggleThink(id) {
                    var content = document.getElementById('think-' + id);
                    var btn = document.getElementById('think-btn-' + id);
                    if (content.classList.contains('visible')) {
                        content.classList.remove('visible');
                        btn.textContent = 'Think';
                    } else {
                        content.classList.add('visible');
                        btn.textContent = 'Hide Think';
                    }
                }
            </script>
        </head>
        <body>
            <div class="container">
                <div id="Title"><h1>$title</h1></div>

                <div class="agent-buttons">
    """.trimIndent())

    // Add agent buttons
    agentList.forEachIndexed { index, agent ->
        val activeClass = if (index == 0) "active" else ""
        htmlBuilder.append("""
                    <button class="agent-btn $activeClass" onclick="showAgent('${agent.agentId}')">${agent.agentName}</button>
        """)
    }

    htmlBuilder.append("""
                </div>
    """)

    // Add each agent's response section
    agentList.forEachIndexed { index, agent ->
        val activeClass = if (index == 0) "active" else ""
        // Get display name for provider
        val providerDisplayName = try {
            com.ai.data.AiService.valueOf(agent.provider).displayName
        } catch (e: Exception) {
            agent.provider
        }

        htmlBuilder.append("""
                <div id="agent-${agent.agentId}" class="agent-result $activeClass">
                    <div class="agent-header">$providerDisplayName - ${agent.model}</div>
                    <div id="Report-${agent.agentId}" class="report-content">
        """)

        if (agent.reportStatus == com.ai.data.ReportStatus.ERROR || agent.errorMessage != null) {
            val errorMsg = agent.errorMessage ?: "Unknown error"
            htmlBuilder.append("""
                    <div class="error">Error: $errorMsg</div>
            """)
        } else {
            val rawAnalysis = agent.responseBody ?: "No response"
            // Process <think>...</think> sections before escaping HTML
            val processedAnalysis = processThinkSectionsForHtml(rawAnalysis, agent.agentId)
            htmlBuilder.append("""
                    <div class="agent-response">$processedAnalysis</div>
            """)
        }

        // Add citations if available
        agent.citations?.takeIf { it.isNotEmpty() }?.let { citations ->
            htmlBuilder.append("""
                    <div class="sources-section">
                        <div class="sources-label">Sources</div>
            """)
            citations.forEachIndexed { idx, url ->
                val escapedUrl = url.replace("\"", "&quot;")
                htmlBuilder.append("""
                        <div class="source-item">${idx + 1}.<a href="$escapedUrl" class="source-link" target="_blank">$escapedUrl</a></div>
                """)
            }
            htmlBuilder.append("</div>")
        }

        // Add search results if available
        agent.searchResults?.takeIf { it.isNotEmpty() }?.let { searchResults ->
            htmlBuilder.append("""
                    <div class="search-results-section">
                        <div class="search-results-label">Search Results</div>
            """)
            searchResults.forEachIndexed { idx, result ->
                val resultTitle = (result.name ?: result.url ?: "Link").replace("<", "&lt;").replace(">", "&gt;")
                val url = result.url?.replace("\"", "&quot;") ?: ""
                val snippet = result.snippet?.replace("<", "&lt;")?.replace(">", "&gt;") ?: ""
                htmlBuilder.append("""
                        <div class="search-result">
                            ${idx + 1}. <a href="$url" class="search-result-title" target="_blank">$resultTitle</a>
                """)
                if (snippet.isNotEmpty()) {
                    htmlBuilder.append("""
                            <div class="search-result-snippet">$snippet</div>
                    """)
                }
                htmlBuilder.append("</div>")
            }
            htmlBuilder.append("</div>")
        }

        // Add related questions if available
        agent.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { relatedQuestions ->
            htmlBuilder.append("""
                    <div class="related-questions-section">
                        <div class="related-questions-label">Related Questions</div>
            """)
            relatedQuestions.forEachIndexed { idx, question ->
                val escapedQuestion = question.replace("<", "&lt;").replace(">", "&gt;")
                htmlBuilder.append("""
                        <div class="related-question">${idx + 1}. $escapedQuestion</div>
                """)
            }
            htmlBuilder.append("</div>")
        }

        // Add HTTP headers for this agent when developer mode is on
        if (developerMode && agent.provider != "DUMMY" && agent.responseHeaders != null) {
            val escapedHeaders = agent.responseHeaders!!.replace("<", "&lt;").replace(">", "&gt;")
            htmlBuilder.append("""
                    <div class="usage-label">HTTP Headers:</div>
                    <pre class="headers-text">$escapedHeaders</pre>
            """)
        }

        htmlBuilder.append("</div></div>")  // Close report-content and agent-result divs
    }

    htmlBuilder.append("""
                <div id="Prompt" class="prompt-section">
                    <div class="prompt-label">Prompt:</div>
                    <pre class="prompt-text">${prompt.replace("<", "&lt;").replace(">", "&gt;")}</pre>
                </div>
    """)

    htmlBuilder.append("""
                <div class="footer">
                    Generated by AI v$appVersion on $timestamp
                </div>
            </div>

            <script>
                function showAgent(agentId) {
                    // Hide all agent results
                    document.querySelectorAll('.agent-result').forEach(el => {
                        el.classList.remove('active');
                    });
                    // Deactivate all buttons
                    document.querySelectorAll('.agent-btn').forEach(el => {
                        el.classList.remove('active');
                    });
                    // Show selected agent result
                    document.getElementById('agent-' + agentId).classList.add('active');
                    // Activate clicked button
                    event.target.classList.add('active');
                }
            </script>
        </body>
        </html>
    """.trimIndent())

    return htmlBuilder.toString()
}

/**
 * Prompt History screen showing previously used prompts with pagination.
 * Layout matches the API Trace Log screen.
 */
@Composable
fun PromptHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onSelectEntry: (PromptHistoryEntry) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs) }
    val allHistoryEntries = remember { mutableStateOf(settingsPrefs.loadPromptHistory()) }
    var searchText by remember { mutableStateOf("") }
    var currentPage by remember { mutableIntStateOf(0) }

    // Filter entries based on search text
    val filteredEntries = remember(allHistoryEntries.value, searchText) {
        if (searchText.isBlank()) {
            allHistoryEntries.value
        } else {
            val searchLower = searchText.lowercase()
            allHistoryEntries.value.filter { entry ->
                entry.title.lowercase().contains(searchLower) ||
                entry.prompt.lowercase().contains(searchLower)
            }
        }
    }

    // Reset to first page when search changes
    LaunchedEffect(searchText) {
        currentPage = 0
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Calculate page size based on available height
        // Title bar ~48dp, search ~56dp, pagination ~48dp, header ~40dp, clear button ~48dp, spacing ~40dp = ~280dp overhead
        // Each row is approximately 56dp
        val availableHeight = maxHeight - 280.dp
        val rowHeight = 56.dp
        val pageSize = maxOf(1, (availableHeight / rowHeight).toInt())

        val totalPages = (filteredEntries.size + pageSize - 1) / pageSize
        val startIndex = currentPage * pageSize
        val endIndex = minOf(startIndex + pageSize, filteredEntries.size)
        val currentPageEntries = if (filteredEntries.isNotEmpty() && startIndex < filteredEntries.size) {
            filteredEntries.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // Reset to valid page if needed
        LaunchedEffect(pageSize, filteredEntries.size) {
            if (currentPage >= totalPages && totalPages > 0) {
                currentPage = totalPages - 1
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AiTitleBar(
                title = "Prompt History",
                onBackClick = onNavigateBack,
                onAiClick = onNavigateHome
            )

            Spacer(modifier = Modifier.height(8.dp))

        // Search field
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Search in title or prompt...", color = Color(0xFF888888)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF6B9BFF),
                unfocusedBorderColor = Color(0xFF555555),
                cursorColor = Color(0xFF6B9BFF)
            ),
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { searchText = "" }) {
                        Text("✕", color = Color(0xFF888888))
                    }
                }
            }
        )

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
        if (filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (allHistoryEntries.value.isEmpty()) "No prompt history yet" else "No matches found",
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(currentPageEntries) { entry ->
                    PromptHistoryRow(
                        entry = entry,
                        onClick = { onSelectEntry(entry) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear button
        Button(
            onClick = {
                settingsPrefs.clearPromptHistory()
                allHistoryEntries.value = emptyList()
                searchText = ""
                currentPage = 0
            },
            enabled = allHistoryEntries.value.isNotEmpty(),
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
 * Single row in Prompt History showing title and timestamp.
 * Matches the trace log table row style.
 */
@Composable
private fun PromptHistoryRow(
    entry: PromptHistoryEntry,
    onClick: () -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US) }
    val formattedDate = remember(entry.timestamp) { dateFormat.format(java.util.Date(entry.timestamp)) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF3A3A3A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.title,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.5f)
            )
            Text(
                text = formattedDate,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * Navigation wrapper for AI Reports screen.
 */
@Composable
fun AiReportsScreenNav(
    viewModel: AiViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Reset state when leaving the screen
    val handleDismiss = {
        viewModel.dismissGenericAiReportsDialog()
        onNavigateBack()
    }

    val handleNavigateHome = {
        viewModel.dismissGenericAiReportsDialog()
        onNavigateHome()
    }

    AiReportsScreen(
        uiState = uiState,
        savedAgentIds = viewModel.loadAiReportAgents(),
        savedSwarmIds = viewModel.loadAiReportSwarms(),
        onGenerate = { combinedAgentIds, directAgentIds, selectedSwarmIds ->
            viewModel.saveAiReportAgents(directAgentIds)
            viewModel.saveAiReportSwarms(selectedSwarmIds)
            viewModel.generateGenericAiReports(combinedAgentIds)
        },
        onStop = { viewModel.stopGenericAiReports() },
        onShare = { shareGenericAiReports(context, uiState) },
        onOpenInBrowser = {
            // Use the stored AI-REPORT object to generate HTML on demand
            val reportId = uiState.currentReportId
            if (reportId != null) {
                openAiReportInChrome(context, reportId, uiState.generalSettings.developerMode)
            }
        },
        onDismiss = handleDismiss,
        onNavigateHome = handleNavigateHome
    )
}

/**
 * Full-screen AI Reports generation and results screen.
 * Shows agent selection first, then progress and results.
 */
@Composable
fun AiReportsScreen(
    uiState: AiUiState,
    savedAgentIds: Set<String>,
    savedSwarmIds: Set<String>,
    onGenerate: (Set<String>, Set<String>, Set<String>) -> Unit,  // combinedAgentIds, directAgentIds, swarmIds
    onStop: () -> Unit,
    onShare: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss
) {
    val reportsTotal = uiState.genericAiReportsTotal
    val reportsProgress = uiState.genericAiReportsProgress
    val reportsAgentResults = uiState.genericAiReportsAgentResults
    val reportsSelectedAgents = uiState.genericAiReportsSelectedAgents

    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0

    val context = LocalContext.current

    // Viewer state
    var showViewer by remember { mutableStateOf(false) }
    var selectedAgentForViewer by remember { mutableStateOf<String?>(null) }

    // Share dialog state
    var showShareDialog by remember { mutableStateOf(false) }

    // Share dialog
    val currentReportId = uiState.currentReportId
    if (showShareDialog && currentReportId != null) {
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
                            shareAiReportAsJson(context, currentReportId)
                        }
                    ) {
                        Text("JSON", color = Color(0xFF6B9BFF))
                    }
                    TextButton(
                        onClick = {
                            showShareDialog = false
                            shareAiReportAsHtml(context, currentReportId, uiState.generalSettings.developerMode)
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

    // Show viewer screen when activated (uses stored AI-REPORT from persistent storage)
    if (showViewer && currentReportId != null) {
        AiReportsViewerScreen(
            reportId = currentReportId,
            initialSelectedAgentId = selectedAgentForViewer,
            onDismiss = { showViewer = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Selection mode: true = Swarms, false = Agents
    var isSwarmMode by remember { mutableStateOf(true) }

    // Search query
    var searchQuery by remember { mutableStateOf("") }

    // Filter out DUMMY agents when not in developer mode
    val allConfiguredAgents = uiState.aiSettings.getConfiguredAgents()
    val configuredAgents = if (uiState.generalSettings.developerMode) {
        allConfiguredAgents
    } else {
        allConfiguredAgents.filter { it.provider != com.ai.data.AiService.DUMMY }
    }

    // Swarm selection state
    val swarms = uiState.aiSettings.swarms
    val validSwarmIds = swarms.map { it.id }.toSet()
    val validSavedSwarms = savedSwarmIds.filter { it in validSwarmIds }.toSet()
    var selectedSwarmIds by remember {
        mutableStateOf(
            // Use saved swarm IDs if available, otherwise default to none selected
            validSavedSwarms
        )
    }

    // Direct agent selection state (separate from swarm-based selection)
    // Filter to only include agents that still exist (excluding DUMMY when not in dev mode)
    val validAgentIds = configuredAgents.map { it.id }.toSet()
    val validSavedAgents = savedAgentIds.filter { it in validAgentIds }.toSet()
    var directlySelectedAgentIds by remember { mutableStateOf(validSavedAgents) }

    // Get agents from selected swarms (excluding DUMMY when not in dev mode)
    val swarmAgentIds = uiState.aiSettings.getAgentsForSwarms(selectedSwarmIds)
        .filter { uiState.generalSettings.developerMode || it.provider != com.ai.data.AiService.DUMMY }
        .map { it.id }.toSet()

    // Combined unique agent IDs (from swarms + directly selected)
    val combinedAgentIds = swarmAgentIds + directlySelectedAgentIds

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = when {
                isComplete -> "Report Ready"
                isGenerating -> "Generating Report"
                isSwarmMode -> "Select Swarm(s)"
                else -> "Select Agent(s)"
            },
            onBackClick = onDismiss,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!isGenerating) {
            // Generate button at top
            Button(
                onClick = { onGenerate(combinedAgentIds, directlySelectedAgentIds, selectedSwarmIds) },
                enabled = combinedAgentIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6)
                )
            ) {
                Text("Generate Reports (${combinedAgentIds.size} agent${if (combinedAgentIds.size == 1) "" else "s"})")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mode toggle buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { isSwarmMode = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSwarmMode) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                ) {
                    Text("Swarms")
                }
                Button(
                    onClick = { isSwarmMode = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isSwarmMode) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                ) {
                    Text("Agents")
                }
            }

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                placeholder = { Text(if (isSwarmMode) "Search swarms..." else "Search agents...") },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", color = Color.Gray)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6B9BFF),
                    unfocusedBorderColor = Color(0xFF444444)
                )
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isSwarmMode) {
                        // Swarm selection mode
                        val filteredSwarms = swarms
                            .filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
                            .sortedBy { it.name.lowercase() }
                        if (swarms.isEmpty()) {
                            Text(
                                text = "No AI swarms configured. Please configure swarms in Settings > AI Setup > AI Swarms.",
                                color = Color(0xFFAAAAAA)
                            )
                        } else if (filteredSwarms.isEmpty()) {
                            Text(
                                text = "No swarms match \"$searchQuery\"",
                                color = Color(0xFFAAAAAA)
                            )
                        } else {
                            filteredSwarms.forEach { swarm ->
                                val swarmAgentsList = uiState.aiSettings.getAgentsForSwarm(swarm)
                                    .filter { uiState.generalSettings.developerMode || it.provider != com.ai.data.AiService.DUMMY }
                                val swarmAgentIdsList = swarmAgentsList.map { it.id }.toSet()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (swarm.id in selectedSwarmIds) {
                                                // Deselecting: remove swarm and its agents from direct selection
                                                selectedSwarmIds = selectedSwarmIds - swarm.id
                                                directlySelectedAgentIds = directlySelectedAgentIds - swarmAgentIdsList
                                            } else {
                                                selectedSwarmIds = selectedSwarmIds + swarm.id
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = swarm.id in selectedSwarmIds,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                selectedSwarmIds = selectedSwarmIds + swarm.id
                                            } else {
                                                // Deselecting: remove swarm and its agents from direct selection
                                                selectedSwarmIds = selectedSwarmIds - swarm.id
                                                directlySelectedAgentIds = directlySelectedAgentIds - swarmAgentIdsList
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = swarm.name,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                        Text(
                                            text = if (swarmAgentsList.isEmpty()) {
                                                "No agents"
                                            } else {
                                                "${swarmAgentsList.size} agent${if (swarmAgentsList.size == 1) "" else "s"}: ${swarmAgentsList.take(3).joinToString(", ") { it.name }}${if (swarmAgentsList.size > 3) "..." else ""}"
                                            },
                                            fontSize = 12.sp,
                                            color = Color(0xFFAAAAAA)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Agent selection mode
                        val filteredAgents = configuredAgents
                            .filter { agent ->
                                searchQuery.isBlank() ||
                                agent.name.contains(searchQuery, ignoreCase = true) ||
                                agent.provider.displayName.contains(searchQuery, ignoreCase = true) ||
                                agent.model.contains(searchQuery, ignoreCase = true)
                            }
                            .sortedBy { it.name.lowercase() }
                        if (configuredAgents.isEmpty()) {
                            Text(
                                text = "No AI agents configured. Please configure agents in Settings > AI Setup > AI Agents.",
                                color = Color(0xFFAAAAAA)
                            )
                        } else if (filteredAgents.isEmpty()) {
                            Text(
                                text = "No agents match \"$searchQuery\"",
                                color = Color(0xFFAAAAAA)
                            )
                        } else {
                            filteredAgents.forEach { agent ->
                                val isFromSwarm = agent.id in swarmAgentIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            directlySelectedAgentIds = if (agent.id in directlySelectedAgentIds) {
                                                directlySelectedAgentIds - agent.id
                                            } else {
                                                directlySelectedAgentIds + agent.id
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = agent.id in directlySelectedAgentIds || isFromSwarm,
                                        onCheckedChange = { checked ->
                                            directlySelectedAgentIds = if (checked) {
                                                directlySelectedAgentIds + agent.id
                                            } else {
                                                directlySelectedAgentIds - agent.id
                                            }
                                        },
                                        enabled = !isFromSwarm  // Disable if already included via swarm
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = agent.name,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isFromSwarm) Color(0xFF6B9BFF) else Color.White
                                        )
                                        Text(
                                            text = if (isFromSwarm) {
                                                "${agent.provider.displayName} / ${agent.model} (via swarm)"
                                            } else {
                                                "${agent.provider.displayName} / ${agent.model}"
                                            },
                                            fontSize = 12.sp,
                                            color = Color(0xFFAAAAAA)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Select all / Select none buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (isSwarmMode) {
                            selectedSwarmIds = swarms.map { it.id }.toSet()
                        } else {
                            directlySelectedAgentIds = configuredAgents.map { it.id }.toSet()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Select all")
                }
                OutlinedButton(
                    onClick = {
                        if (isSwarmMode) {
                            selectedSwarmIds = emptySet()
                        } else {
                            directlySelectedAgentIds = emptySet()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Select none")
                }
            }

        } else {
            // Action buttons at top when complete
            if (isComplete) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showViewer = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2196F3)
                        )
                    ) {
                        Text("View")
                    }
                    Button(
                        onClick = { showShareDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Text("Share")
                    }
                    Button(
                        onClick = onOpenInBrowser,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B5CF6)
                        )
                    ) {
                        Text("Browser")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Report title (shown both during generation and when complete)
            if (uiState.genericAiPromptTitle.isNotBlank()) {
                Text(
                    text = uiState.genericAiPromptTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Calculate costs for each agent
            val agentCosts = remember(reportsAgentResults) {
                reportsAgentResults.mapNotNull { (agentId, result) ->
                    val agent = uiState.aiSettings.getAgentById(agentId) ?: return@mapNotNull null
                    val tokenUsage = result.tokenUsage ?: return@mapNotNull null
                    // DUMMY provider always has 0 cost
                    if (agent.provider == com.ai.data.AiService.DUMMY) {
                        return@mapNotNull agentId to 0.0
                    }
                    val pricing = com.ai.data.PricingCache.getPricing(context, agent.provider, agent.model)
                    if (pricing != null) {
                        val inputCost = tokenUsage.inputTokens * pricing.promptPrice
                        val outputCost = tokenUsage.outputTokens * pricing.completionPrice
                        agentId to (inputCost + outputCost)
                    } else {
                        null
                    }
                }.toMap()
            }
            val totalCost = agentCosts.values.sum()

            // Progress/Results UI
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Table header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Agent",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Input",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Output",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cents",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(70.dp)
                        )
                    }
                    HorizontalDivider(color = Color(0xFF404040))

                    // Show all selected agents with their status
                    reportsSelectedAgents.mapNotNull { agentId ->
                        uiState.aiSettings.getAgentById(agentId)
                    }.sortedBy { it.name.lowercase() }.forEach { agent ->
                        val result = reportsAgentResults[agent.id]
                        val cost = agentCosts[agent.id]
                        val tokenUsage = result?.tokenUsage
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status icon first
                            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                                when {
                                    result == null -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.Gray
                                        )
                                    }
                                    result.isSuccess -> {
                                        Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    else -> {
                                        Text("✗", color = Color(0xFFF44336), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Agent name
                            Text(
                                text = agent.name,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            // Input tokens
                            Text(
                                text = tokenUsage?.inputTokens?.toString() ?: "",
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFAAAAAA),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(50.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Output tokens
                            Text(
                                text = tokenUsage?.outputTokens?.toString() ?: "",
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFAAAAAA),
                                fontSize = 11.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(50.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Cost in cents (right-aligned, only show if available)
                            Text(
                                text = if (cost != null) String.format("%.4f", cost * 100) else "",
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF4CAF50),
                                fontSize = 12.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(70.dp)
                            )
                        }
                    }

                    // Calculate total tokens
                    val totalInputTokens = reportsAgentResults.values.sumOf { it.tokenUsage?.inputTokens ?: 0 }
                    val totalOutputTokens = reportsAgentResults.values.sumOf { it.tokenUsage?.outputTokens ?: 0 }

                    // Total cost row - always show, accumulates as agents complete
                    HorizontalDivider(color = Color(0xFF404040))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Total",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        // Total input tokens
                        Text(
                            text = if (totalInputTokens > 0) totalInputTokens.toString() else "",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Total output tokens
                        Text(
                            text = if (totalOutputTokens > 0) totalOutputTokens.toString() else "",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFAAAAAA),
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(50.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = String.format("%.4f", totalCost * 100),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50),
                            fontSize = 12.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(70.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // STOP button while generating
            if (isGenerating && !isComplete) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFCC3333)
                    )
                ) {
                    Text("STOP", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Full-screen viewer for AI agent responses.
 * Shows buttons for each agent at the top, displays HTML-converted markdown content.
 * Uses the stored AI-REPORT object from persistent storage as the data source.
 */
@Composable
fun AiReportsViewerScreen(
    reportId: String,
    initialSelectedAgentId: String? = null,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss
) {
    val context = LocalContext.current

    // Load the report from storage
    val report = remember(reportId) {
        com.ai.data.AiReportStorage.getReport(context, reportId)
    }

    if (report == null) {
        // Report not found
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AiTitleBar(
                title = "View Reports",
                onBackClick = onDismiss,
                onAiClick = onNavigateHome
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Report not found",
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }
        }
        return
    }

    // Get agents with successful results from the stored report
    val agentsWithResults = report.agents
        .filter { it.reportStatus == com.ai.data.ReportStatus.SUCCESS }
        .sortedBy { it.agentName.lowercase() }

    // Selected agent state
    var selectedAgentId by remember {
        mutableStateOf(initialSelectedAgentId ?: agentsWithResults.firstOrNull()?.agentId)
    }

    // Get the selected agent's data from the stored report
    val selectedReportAgent = selectedAgentId?.let { id ->
        report.agents.find { it.agentId == id }
    }

    // Scroll state that resets when agent changes
    val scrollState = rememberScrollState()
    LaunchedEffect(selectedAgentId) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header - show agent name in title bar
        val titleText = selectedReportAgent?.agentName ?: "View Reports"
        AiTitleBar(
            title = titleText,
            onBackClick = onDismiss,
            onAiClick = onNavigateHome
        )

        // Agent selection buttons - wrapping flow layout
        if (agentsWithResults.isNotEmpty()) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                agentsWithResults.forEach { agent ->
                    val isSelected = agent.agentId == selectedAgentId
                    Button(
                        onClick = { selectedAgentId = agent.agentId },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF8B5CF6) else Color(0xFF3A3A4A)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = agent.agentName,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Provider - Model subtitle below buttons
            if (selectedReportAgent != null) {
                // Get display name for provider
                val providerDisplayName = try {
                    com.ai.data.AiService.valueOf(selectedReportAgent.provider).displayName
                } catch (e: Exception) {
                    selectedReportAgent.provider
                }
                Text(
                    text = "$providerDisplayName - ${selectedReportAgent.model}",
                    fontSize = 18.sp,
                    color = Color(0xFF6B9BFF),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }

        // Content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            if (agentsWithResults.isEmpty()) {
                // No results
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No successful reports to display",
                        color = Color(0xFFAAAAAA),
                        fontSize = 16.sp
                    )
                }
            } else if (selectedReportAgent?.responseBody != null) {
                // Show the content with collapsible think sections
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // Content rendered with think sections as collapsible blocks
                    ContentWithThinkSections(analysis = selectedReportAgent.responseBody!!)

                    // Citations section (if available)
                    selectedReportAgent.citations?.takeIf { it.isNotEmpty() }?.let { citations ->
                        Spacer(modifier = Modifier.height(16.dp))
                        CitationsSection(citations = citations)
                    }

                    // Search results section (if available)
                    selectedReportAgent.searchResults?.takeIf { it.isNotEmpty() }?.let { searchResults ->
                        Spacer(modifier = Modifier.height(16.dp))
                        SearchResultsSection(searchResults = searchResults)
                    }

                    // Related questions section (if available)
                    selectedReportAgent.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { relatedQuestions ->
                        Spacer(modifier = Modifier.height(16.dp))
                        RelatedQuestionsSection(relatedQuestions = relatedQuestions)
                    }

                    // Prompt section (from stored report)
                    if (report.prompt.isNotBlank()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Prompt",
                            fontSize = 18.sp,
                            color = Color(0xFF6B9BFF),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = report.prompt,
                            fontSize = 14.sp,
                            color = Color.White,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else {
                // No analysis for selected agent
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No analysis available",
                        color = Color(0xFFAAAAAA),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * Represents a segment of AI response content.
 */
private sealed class ContentSegment {
    data class Text(val content: String) : ContentSegment()
    data class Think(val content: String) : ContentSegment()
}

/**
 * Parses AI response text into segments, extracting <think>...</think> sections.
 */
private fun parseContentWithThinkSections(text: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0

    thinkPattern.findAll(text).forEach { match ->
        // Add text before this think section
        if (match.range.first > lastEnd) {
            val textBefore = text.substring(lastEnd, match.range.first).trim()
            if (textBefore.isNotEmpty()) {
                segments.add(ContentSegment.Text(textBefore))
            }
        }
        // Add the think section
        val thinkContent = match.groupValues[1].trim()
        if (thinkContent.isNotEmpty()) {
            segments.add(ContentSegment.Think(thinkContent))
        }
        lastEnd = match.range.last + 1
    }

    // Add remaining text after last think section
    if (lastEnd < text.length) {
        val remainingText = text.substring(lastEnd).trim()
        if (remainingText.isNotEmpty()) {
            segments.add(ContentSegment.Text(remainingText))
        }
    }

    // If no segments were created (no think tags), add the whole text
    if (segments.isEmpty() && text.isNotBlank()) {
        segments.add(ContentSegment.Text(text))
    }

    return segments
}

/**
 * Composable to display a collapsible think section.
 */
@Composable
private fun ThinkSection(content: String) {
    var isExpanded by remember { mutableStateOf(false) }

    Column {
        Button(
            onClick = { isExpanded = !isExpanded },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2A2A2A)
            ),
            border = BorderStroke(1.dp, Color(0xFF666666)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = if (isExpanded) "Hide Think" else "Think",
                color = Color(0xFFAAAAAA),
                fontSize = 13.sp
            )
        }

        if (isExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF252525))
            ) {
                // Left border
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF555555))
                )
                // Content
                Text(
                    text = content,
                    color = Color(0xFF999999),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Displays content with think sections as collapsible blocks.
 */
@Composable
private fun ContentWithThinkSections(analysis: String) {
    val segments = remember(analysis) {
        parseContentWithThinkSections(analysis)
    }

    segments.forEach { segment ->
        when (segment) {
            is ContentSegment.Text -> {
                val htmlContent = remember(segment.content) {
                    convertMarkdownToSimpleHtml(segment.content)
                }
                HtmlContentDisplay(htmlContent = htmlContent)
            }
            is ContentSegment.Think -> {
                ThinkSection(content = segment.content)
            }
        }
    }
}

/**
 * Converts markdown text to simple HTML, removing multiple blank lines.
 */
private fun convertMarkdownToSimpleHtml(markdown: String): String {
    // First normalize line endings and remove multiple blank lines
    var html = markdown
        .replace("\r\n", "\n")
        .replace(Regex("\n{3,}"), "\n\n")  // Replace 3+ newlines with 2

    // Basic markdown to HTML conversion
    html = html
        // Escape HTML entities first
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        // Headers
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        // Bold
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        // Italic
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        // Bullet points
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        // Numbered lists
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        // Line breaks - convert double newlines to paragraph breaks
        .replace("\n\n", "</p><p>")
        .replace("\n", "<br>")

    // Wrap consecutive <li> items in <ul>
    html = html.replace(Regex("(<li>.*?</li>)+")) { match ->
        "<ul>${match.value}</ul>"
    }

    // Wrap in paragraph if not empty
    if (html.isNotBlank()) {
        html = "<p>$html</p>"
    }

    return html
}

/**
 * Displays HTML content as styled Compose text.
 */
@Composable
private fun HtmlContentDisplay(htmlContent: String) {
    // Parse and display HTML content
    val annotatedString = remember(htmlContent) {
        parseHtmlToAnnotatedString(htmlContent)
    }

    Text(
        text = annotatedString,
        color = Color.White,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Parses HTML to AnnotatedString for display.
 */
private fun parseHtmlToAnnotatedString(html: String): androidx.compose.ui.text.AnnotatedString {
    // First clean up HTML entities and structural tags
    val cleanHtml = html
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("<p>", "")
        .replace("</p>", "\n\n")
        .replace("<br>", "\n")
        .replace("<ul>", "\n")
        .replace("</ul>", "\n")
        .replace("<li>", "  \u2022 ")
        .replace("</li>", "\n")
        .replace(Regex("\n{3,}"), "\n\n")  // Remove excess blank lines
        .trim()

    return androidx.compose.ui.text.buildAnnotatedString {
        // Process tags
        val tagPattern = Regex("<(/?)(h[123]|strong|em)>")
        var lastEnd = 0

        val matches = tagPattern.findAll(cleanHtml).toList()
        val styleStack = mutableListOf<Pair<String, Int>>()

        for (match in matches) {
            // Add text before this tag
            if (match.range.first > lastEnd) {
                append(cleanHtml.substring(lastEnd, match.range.first))
            }

            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2]

            if (!isClosing) {
                styleStack.add(tagName to length)
            } else {
                // Find matching opening tag
                val openTagIndex = styleStack.indexOfLast { it.first == tagName }
                if (openTagIndex >= 0) {
                    val (_, startPos) = styleStack.removeAt(openTagIndex)
                    val style = when (tagName) {
                        "h1" -> androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            color = Color.White
                        )
                        "h2" -> androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color(0xFF8BB8FF)
                        )
                        "h3" -> androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color(0xFF9FCFFF)
                        )
                        "strong" -> androidx.compose.ui.text.SpanStyle(
                            fontWeight = FontWeight.Bold
                        )
                        "em" -> androidx.compose.ui.text.SpanStyle(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = Color(0xFFCCCCCC)
                        )
                        else -> null
                    }
                    if (style != null) {
                        addStyle(style, startPos, length)
                    }
                }
            }

            lastEnd = match.range.last + 1
        }

        // Add remaining text
        if (lastEnd < cleanHtml.length) {
            append(cleanHtml.substring(lastEnd))
        }
    }
}

/**
 * Displays a list of citations (URLs) returned by the AI service.
 */
@Composable
private fun CitationsSection(citations: List<String>) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Sources",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color(0xFF8B5CF6),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        citations.forEachIndexed { index, url ->
            Row(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore if URL can't be opened
                        }
                    }
            ) {
                Text(
                    text = "${index + 1}. ",
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp
                )
                Text(
                    text = url,
                    color = Color(0xFF64B5F6),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                )
            }
        }
    }
}

/**
 * Displays search results returned by the AI service.
 */
@Composable
private fun SearchResultsSection(searchResults: List<com.ai.data.SearchResult>) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Search Results",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color(0xFFFF9800),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        searchResults.forEachIndexed { index, result ->
            if (result.url != null) {
                Column(
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .clickable {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(result.url))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Ignore if URL can't be opened
                            }
                        }
                ) {
                    // Title/Name with number
                    Row {
                        Text(
                            text = "${index + 1}. ",
                            color = Color(0xFFAAAAAA),
                            fontSize = 14.sp
                        )
                        Text(
                            text = result.name ?: result.url,
                            color = Color(0xFF64B5F6),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )
                    }
                    // URL if different from name
                    if (result.name != null && result.name != result.url) {
                        Text(
                            text = result.url,
                            color = Color(0xFF888888),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                        )
                    }
                    // Snippet if available
                    if (!result.snippet.isNullOrBlank()) {
                        Text(
                            text = result.snippet,
                            color = Color(0xFFBBBBBB),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays related questions returned by the AI service (e.g., Perplexity).
 */
@Composable
private fun RelatedQuestionsSection(relatedQuestions: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "Related Questions",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color(0xFF4CAF50),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        relatedQuestions.forEachIndexed { index, question ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "${index + 1}. ",
                    color = Color(0xFFAAAAAA),
                    fontSize = 14.sp
                )
                Text(
                    text = question,
                    color = Color(0xFFE0E0E0),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
