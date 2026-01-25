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
import com.ai.data.AiHistoryManager
import com.ai.data.AiHistoryFileInfo

/**
 * AI hub screen - the home page of the app.
 * Shows links to New AI Report, AI History, and Prompt History.
 * Also has navigation icons for Settings, Trace, and Help.
 */
@Composable
fun AiHubScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToTrace: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToNewReport: () -> Unit,
    onNavigateToPromptHistory: () -> Unit,
    viewModel: AiViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large AI logo
        Text(
            text = "AI",
            fontSize = 72.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 24.dp)
        )

        // Cards
        HubCard(icon = "\uD83D\uDCDD", title = "New AI Report", onClick = onNavigateToNewReport)
        Spacer(modifier = Modifier.height(8.dp))
        HubCard(icon = "\uD83D\uDD52", title = "Prompt History", onClick = onNavigateToPromptHistory)
        Spacer(modifier = Modifier.height(8.dp))
        HubCard(icon = "\uD83D\uDCDA", title = "AI History", onClick = onNavigateToHistory)
        Spacer(modifier = Modifier.height(8.dp))
        HubCard(icon = "\u2699\uFE0F", title = "Settings", onClick = onNavigateToSettings)
        Spacer(modifier = Modifier.height(8.dp))
        HubCard(icon = "\u2753", title = "Help", onClick = onNavigateToHelp)

        // API Traces card (developer mode only)
        if (uiState.generalSettings.developerMode) {
            Spacer(modifier = Modifier.height(8.dp))
            HubCard(icon = "\uD83D\uDC1E", title = "API Traces", onClick = onNavigateToTrace)
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

private const val HISTORY_PAGE_SIZE = 25

/**
 * AI History screen showing generated reports with pagination.
 * Layout matches the API Trace Log screen.
 */
@Composable
fun AiHistoryScreenNav(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack
) {
    val context = LocalContext.current
    var historyFiles by remember { mutableStateOf(AiHistoryManager.getHistoryFiles()) }
    var currentPage by remember { mutableIntStateOf(0) }

    val totalPages = (historyFiles.size + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE
    val startIndex = currentPage * HISTORY_PAGE_SIZE
    val endIndex = minOf(startIndex + HISTORY_PAGE_SIZE, historyFiles.size)
    val currentPageFiles = if (historyFiles.isNotEmpty() && startIndex < historyFiles.size) {
        historyFiles.subList(startIndex, endIndex)
    } else {
        emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "AI History",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
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
        if (historyFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No AI reports yet",
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(currentPageFiles) { fileInfo ->
                    AiHistoryRowNav(
                        fileInfo = fileInfo,
                        context = context
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear button
        Button(
            onClick = {
                AiHistoryManager.clearHistory()
                historyFiles = emptyList()
                currentPage = 0
            },
            enabled = historyFiles.isNotEmpty(),
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

/**
 * Single row in AI History showing title with date/time.
 * Clicking opens actions menu.
 */
@Composable
private fun AiHistoryRowNav(
    fileInfo: AiHistoryFileInfo,
    context: android.content.Context
) {
    // Extract title from HTML file (cached)
    val reportTitle = remember(fileInfo.file) {
        extractTitleFromHtmlFile(fileInfo.file)
    }
    val dateFormat = remember { java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US) }
    var showActions by remember { mutableStateOf(false) }

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
                    text = reportTitle,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = dateFormat.format(java.util.Date(fileInfo.file.lastModified())),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }

            // Action buttons (shown when clicked)
            if (showActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF252525))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { openHistoryFileInChromeNav(context, fileInfo.file) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3366BB)
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Chrome", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { shareHistoryFileNav(context, fileInfo.file) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Share", fontSize = 12.sp)
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
    initialPrompt: String = ""
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Load last used title and prompt from SharedPreferences
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val lastTitle = remember { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, "") ?: "" }
    val lastPrompt = remember { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, "") ?: "" }

    // Use initialTitle/initialPrompt if provided (from prompt history), otherwise use last saved values
    var title by remember { mutableStateOf(initialTitle.ifEmpty { lastTitle }) }
    var prompt by remember { mutableStateOf(initialPrompt.ifEmpty { lastPrompt }) }

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
    }
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
                .agent-response { white-space: pre-wrap; }
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
            </style>
        </head>
        <body>
            <div class="container">
                <h1>$title</h1>

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
        """)

        if (response.error != null) {
            htmlBuilder.append("""
                    <div class="error">Error: ${response.error}</div>
            """)
        } else {
            val analysis = response.analysis?.replace("<", "&lt;")?.replace(">", "&gt;") ?: "No response"
            htmlBuilder.append("""
                    <div class="agent-response">$analysis</div>
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

        htmlBuilder.append("</div>")
    }

    htmlBuilder.append("""
                <div class="prompt-section">
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
    var historyEntries by remember { mutableStateOf(settingsPrefs.loadPromptHistory()) }
    var currentPage by remember { mutableIntStateOf(0) }

    val totalPages = (historyEntries.size + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE
    val startIndex = currentPage * HISTORY_PAGE_SIZE
    val endIndex = minOf(startIndex + HISTORY_PAGE_SIZE, historyEntries.size)
    val currentPageEntries = if (historyEntries.isNotEmpty() && startIndex < historyEntries.size) {
        historyEntries.subList(startIndex, endIndex)
    } else {
        emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = "Prompt History",
            onBackClick = onNavigateBack,
            onAiClick = onNavigateHome
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
        if (historyEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No prompt history yet",
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
                historyEntries = emptyList()
                currentPage = 0
            },
            enabled = historyEntries.isNotEmpty(),
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
        onGenerate = { selectedAgentIds ->
            viewModel.saveAiReportAgents(selectedAgentIds)
            viewModel.generateGenericAiReports(selectedAgentIds)
        },
        onStop = { viewModel.stopGenericAiReports() },
        onShare = { shareGenericAiReports(context, uiState) },
        onOpenInBrowser = { openGenericAiReportsInChrome(context, uiState) },
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
    onGenerate: (Set<String>) -> Unit,
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

    // Save to AI history when report completes
    val context = LocalContext.current
    LaunchedEffect(isComplete) {
        if (isComplete) {
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) { "unknown" }
            val html = convertGenericAiReportsToHtml(uiState, appVersion)
            com.ai.data.AiHistoryManager.saveReport(html, com.ai.data.AiReportType.GENERAL)
        }
    }

    // Viewer state
    var showViewer by remember { mutableStateOf(false) }
    var selectedAgentForViewer by remember { mutableStateOf<String?>(null) }

    // Show viewer screen when activated
    if (showViewer) {
        AiReportsViewerScreen(
            agentResults = reportsAgentResults,
            aiSettings = uiState.aiSettings,
            promptText = uiState.genericAiPromptText,
            initialSelectedAgentId = selectedAgentForViewer,
            onDismiss = { showViewer = false }
        )
        return
    }

    // Agent selection state - filter out DUMMY agents when not in developer mode
    val allConfiguredAgents = uiState.aiSettings.getConfiguredAgents()
    val configuredAgents = if (uiState.generalSettings.developerMode) {
        allConfiguredAgents
    } else {
        allConfiguredAgents.filter { it.provider != com.ai.data.AiService.DUMMY }
    }
    var selectedAgentIds by remember {
        mutableStateOf(
            if (savedAgentIds.isNotEmpty()) {
                savedAgentIds.filter { id -> configuredAgents.any { it.id == id } }.toSet()
            } else {
                configuredAgents.map { it.id }.toSet()
            }
        )
    }

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
                else -> uiState.genericAiPromptTitle
            },
            onBackClick = onDismiss,
            onAiClick = onNavigateHome
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!isGenerating) {
            // Select all / Select none buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { selectedAgentIds = configuredAgents.map { it.id }.toSet() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Select all")
                }
                OutlinedButton(
                    onClick = { selectedAgentIds = emptySet() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Select none")
                }
            }

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
                    if (configuredAgents.isEmpty()) {
                        Text(
                            text = "No AI agents configured. Please configure agents in Settings > AI Setup.",
                            color = Color(0xFFAAAAAA)
                        )
                    } else {
                        configuredAgents.sortedBy { it.name.lowercase() }.forEach { agent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedAgentIds = if (agent.id in selectedAgentIds) {
                                            selectedAgentIds - agent.id
                                        } else {
                                            selectedAgentIds + agent.id
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = agent.id in selectedAgentIds,
                                    onCheckedChange = { checked ->
                                        selectedAgentIds = if (checked) {
                                            selectedAgentIds + agent.id
                                        } else {
                                            selectedAgentIds - agent.id
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = agent.name,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${agent.provider.displayName} - ${agent.model}",
                                        fontSize = 12.sp,
                                        color = Color(0xFFAAAAAA)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Generate button
            Button(
                onClick = { onGenerate(selectedAgentIds) },
                enabled = selectedAgentIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6)
                )
            ) {
                Text("Generate Reports")
            }

        } else {
            // Progress/Results UI
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Show all selected agents with their status
                    reportsSelectedAgents.mapNotNull { agentId ->
                        uiState.aiSettings.getAgentById(agentId)
                    }.sortedBy { it.name.lowercase() }.forEach { agent ->
                        val result = reportsAgentResults[agent.id]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = agent.name,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            when {
                                result == null -> {
                                    // Still pending - show small spinner
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.Gray
                                    )
                                }
                                result.isSuccess -> {
                                    Text(
                                        text = "✓",
                                        color = Color(0xFF4CAF50),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
                                else -> {
                                    Text(
                                        text = "✗",
                                        color = Color(0xFFF44336),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                        }
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

            // Action buttons at the bottom
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
                        onClick = onShare,
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
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Full-screen viewer for AI agent responses.
 * Shows buttons for each agent at the top, displays HTML-converted markdown content.
 */
@Composable
fun AiReportsViewerScreen(
    agentResults: Map<String, com.ai.data.AiAnalysisResponse>,
    aiSettings: AiSettings,
    promptText: String = "",
    initialSelectedAgentId: String? = null,
    onDismiss: () -> Unit
) {
    // Get agents with successful results
    val agentsWithResults = agentResults.entries
        .filter { it.value.isSuccess }
        .mapNotNull { (agentId, result) ->
            val agent = aiSettings.getAgentById(agentId)
            if (agent != null) Triple(agentId, agent, result) else null
        }
        .sortedBy { it.second.name.lowercase() }

    // Selected agent state
    var selectedAgentId by remember {
        mutableStateOf(initialSelectedAgentId ?: agentsWithResults.firstOrNull()?.first)
    }

    // Get the selected agent's result
    val selectedResult = selectedAgentId?.let { agentResults[it] }
    val selectedAgent = selectedAgentId?.let { aiSettings.getAgentById(it) }

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
        val titleText = selectedAgent?.name ?: "View Reports"
        AiTitleBar(
            title = titleText,
            onBackClick = onDismiss,
            onAiClick = onDismiss
        )

        // Agent selection buttons - wrapping flow layout
        if (agentsWithResults.isNotEmpty()) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                agentsWithResults.forEach { (agentId, agent, _) ->
                    val isSelected = agentId == selectedAgentId
                    Button(
                        onClick = { selectedAgentId = agentId },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF8B5CF6) else Color(0xFF3A3A4A)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = agent.name,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Provider - Model subtitle below buttons
            if (selectedAgent != null) {
                Text(
                    text = "${selectedAgent.provider.displayName} - ${selectedAgent.model}",
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
            } else if (selectedResult?.analysis != null) {
                // Show the HTML content in a WebView-like display
                val htmlContent = remember(selectedResult.analysis) {
                    convertMarkdownToSimpleHtml(selectedResult.analysis)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // HTML content rendered as styled text
                    HtmlContentDisplay(htmlContent = htmlContent)

                    // Citations section (if available)
                    selectedResult.citations?.takeIf { it.isNotEmpty() }?.let { citations ->
                        Spacer(modifier = Modifier.height(16.dp))
                        CitationsSection(citations = citations)
                    }

                    // Search results section (if available)
                    selectedResult.searchResults?.takeIf { it.isNotEmpty() }?.let { searchResults ->
                        Spacer(modifier = Modifier.height(16.dp))
                        SearchResultsSection(searchResults = searchResults)
                    }

                    // Related questions section (if available)
                    selectedResult.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { relatedQuestions ->
                        Spacer(modifier = Modifier.height(16.dp))
                        RelatedQuestionsSection(relatedQuestions = relatedQuestions)
                    }

                    // Prompt section
                    if (promptText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Prompt",
                            fontSize = 18.sp,
                            color = Color(0xFF6B9BFF),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = promptText,
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
