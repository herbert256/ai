package com.eval.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eval.data.AiHistoryManager
import com.eval.data.AiHistoryFileInfo

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
            .padding(16.dp)
    ) {
        AiTitleBar(
            title = null,
            onBackClick = null,
            onAiClick = {},
            leftContent = {
                TitleBarIcon(icon = "\u2699", onClick = onNavigateToSettings, fontSize = 28)  // Settings
                if (uiState.generalSettings.trackApiCalls) {
                    TitleBarIcon(icon = "\uD83D\uDC1B", onClick = onNavigateToTrace, fontSize = 24)  // Bug/Trace
                }
                TitleBarIcon(icon = "?", onClick = onNavigateToHelp, fontSize = 28)  // Help
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // New AI Report card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToNewReport() },
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A3A4A)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\uD83D\uDCDD",
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "New AI Report",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "Create a new AI report with custom prompt",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Prompt History card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToPromptHistory() },
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A3A4A)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\uD83D\uDD52",
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Prompt History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "View and reuse previously used prompts",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // AI History card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToHistory() },
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A3A4A)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\uD83D\uDCDA",
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "AI History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "View previously generated AI reports",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        }
    }
}

/**
 * AI History screen showing generated reports with pagination.
 * Used as a navigation destination.
 */
@Composable
fun AiHistoryScreenNav(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val historyFiles = remember { mutableStateOf(AiHistoryManager.getHistoryFiles()) }
    var currentPage by remember { mutableStateOf(0) }
    val pageSize = 10

    val totalPages = if (historyFiles.value.isEmpty()) 1 else (historyFiles.value.size + pageSize - 1) / pageSize
    val startIndex = currentPage * pageSize
    val endIndex = minOf(startIndex + pageSize, historyFiles.value.size)
    val currentPageFiles = if (historyFiles.value.isNotEmpty()) {
        historyFiles.value.subList(startIndex, endIndex)
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
            onAiClick = onNavigateBack
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (historyFiles.value.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No AI reports yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF888888)
                )
            }
        } else {
            // History list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentPageFiles.forEach { fileInfo ->
                    AiHistoryRowNav(
                        fileInfo = fileInfo,
                        context = context
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pagination
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0
                ) {
                    Text(
                        "Previous",
                        color = if (currentPage > 0) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                }

                Text(
                    text = "${currentPage + 1} / $totalPages",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color.White
                )

                TextButton(
                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                    enabled = currentPage < totalPages - 1
                ) {
                    Text(
                        "Next",
                        color = if (currentPage < totalPages - 1) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                }
            }
        }
    }
}

/**
 * Single row in AI History showing filename with share and chrome actions.
 */
@Composable
private fun AiHistoryRowNav(
    fileInfo: AiHistoryFileInfo,
    context: android.content.Context
) {
    // Extract prompt from HTML file (cached)
    val promptPreview = remember(fileInfo.file) {
        extractPromptFromHtmlFile(fileInfo.file)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A3A4A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Filename
                Text(
                    text = fileInfo.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                // Actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Share button
                    TextButton(
                        onClick = { shareHistoryFileNav(context, fileInfo.file) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("share", color = Color(0xFF6B9BFF), fontSize = 14.sp)
                    }

                    // Chrome button
                    TextButton(
                        onClick = { openHistoryFileInChromeNav(context, fileInfo.file) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("chrome", color = Color(0xFF6B9BFF), fontSize = 14.sp)
                    }
                }
            }

            // Prompt preview (first 3 lines)
            if (promptPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = promptPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    maxLines = 3,
                    lineHeight = 14.sp
                )
            }
        }
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
            onAiClick = onNavigateBack
        )

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

        Spacer(modifier = Modifier.height(24.dp))

        // Submit button
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
    }
}

// Helper function to convert generic AI reports to HTML
internal fun convertGenericAiReportsToHtml(uiState: AiUiState, appVersion: String): String {
    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date())

    val agentResults = uiState.genericAiReportsAgentResults
    val title = uiState.genericAiPromptTitle
    val prompt = uiState.genericAiPromptText

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
                h2 { color: #4CAF50; margin-top: 30px; }
                .prompt-section {
                    background: #2a2a2a;
                    border-radius: 8px;
                    padding: 15px;
                    margin: 20px 0;
                }
                .prompt-label { color: #888; font-size: 0.9em; margin-bottom: 5px; }
                .prompt-text { white-space: pre-wrap; }
                .agent-result {
                    background: #252525;
                    border-radius: 8px;
                    padding: 15px;
                    margin: 15px 0;
                    border-left: 4px solid #6B9BFF;
                }
                .agent-name { color: #6B9BFF; font-weight: bold; font-size: 1.1em; }
                .agent-model { color: #888; font-size: 0.9em; margin-bottom: 10px; }
                .agent-response { white-space: pre-wrap; }
                .error { color: #ff6b6b; }
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
                <h1>AI Report: $title</h1>

                <div class="prompt-section">
                    <div class="prompt-label">Prompt:</div>
                    <div class="prompt-text">${prompt.replace("<", "&lt;").replace(">", "&gt;")}</div>
                </div>
    """.trimIndent())

    // Add each agent's response
    agentResults.forEach { (agentId, response) ->
        val agentName = uiState.aiSettings.getAgentById(agentId)?.name ?: "Unknown Agent"
        val model = uiState.aiSettings.getAgentById(agentId)?.model ?: ""

        htmlBuilder.append("""
                <div class="agent-result">
                    <div class="agent-name">$agentName</div>
                    <div class="agent-model">$model</div>
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

        htmlBuilder.append("</div>")
    }

    htmlBuilder.append("""
                <div class="footer">
                    Generated by AI v$appVersion on $timestamp
                </div>
            </div>
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

        // Save to AI history
        com.eval.data.AiHistoryManager.saveReport(html, com.eval.data.AiReportType.GENERAL)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val title = uiState.genericAiPromptTitle
        val htmlFile = java.io.File(cacheDir, "generic_ai_reports.html")
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

        // Save to AI history
        com.eval.data.AiHistoryManager.saveReport(html, com.eval.data.AiReportType.GENERAL)

        val cacheDir = java.io.File(context.cacheDir, "ai_analysis")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        val htmlFile = java.io.File(cacheDir, "generic_ai_reports.html")
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
 * Used as a navigation destination.
 */
@Composable
fun PromptHistoryScreen(
    onNavigateBack: () -> Unit,
    onSelectEntry: (PromptHistoryEntry) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs) }
    val historyEntries = remember { mutableStateOf(settingsPrefs.loadPromptHistory()) }
    var currentPage by remember { mutableStateOf(0) }
    val pageSize = 25

    val totalPages = if (historyEntries.value.isEmpty()) 1 else (historyEntries.value.size + pageSize - 1) / pageSize
    val startIndex = currentPage * pageSize
    val endIndex = minOf(startIndex + pageSize, historyEntries.value.size)
    val currentPageEntries = if (historyEntries.value.isNotEmpty()) {
        historyEntries.value.subList(startIndex, endIndex)
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
            onAiClick = onNavigateBack
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (historyEntries.value.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No prompt history yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF888888)
                )
            }
        } else {
            // History list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currentPageEntries.forEach { entry ->
                    PromptHistoryRow(
                        entry = entry,
                        onClick = { onSelectEntry(entry) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pagination
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0
                ) {
                    Text(
                        "Previous",
                        color = if (currentPage > 0) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                }

                Text(
                    text = "${currentPage + 1} / $totalPages",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color.White
                )

                TextButton(
                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                    enabled = currentPage < totalPages - 1
                ) {
                    Text(
                        "Next",
                        color = if (currentPage < totalPages - 1) Color(0xFF6B9BFF) else Color(0xFF444444)
                    )
                }
            }
        }
    }
}

/**
 * Single row in Prompt History showing title and timestamp.
 */
@Composable
private fun PromptHistoryRow(
    entry: PromptHistoryEntry,
    onClick: () -> Unit
) {
    val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
    val formattedDate = remember(entry.timestamp) { dateFormat.format(java.util.Date(entry.timestamp)) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A3A4A)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Title and timestamp row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Prompt preview (truncated)
            Text(
                text = if (entry.prompt.length > 100) entry.prompt.take(100) + "..." else entry.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAAAAA),
                maxLines = 2
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
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    AiReportsScreen(
        uiState = uiState,
        savedAgentIds = viewModel.loadAiReportAgents(),
        onGenerate = { selectedAgentIds ->
            viewModel.saveAiReportAgents(selectedAgentIds)
            viewModel.generateGenericAiReports(selectedAgentIds)
        },
        onShare = { shareGenericAiReports(context, uiState) },
        onOpenInBrowser = { openGenericAiReportsInChrome(context, uiState) },
        onDismiss = {
            viewModel.dismissGenericAiReportsDialog()
            onNavigateBack()
        }
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
    onShare: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit
) {
    val reportsTotal = uiState.genericAiReportsTotal
    val reportsProgress = uiState.genericAiReportsProgress
    val reportsAgentResults = uiState.genericAiReportsAgentResults
    val reportsSelectedAgents = uiState.genericAiReportsSelectedAgents

    val isGenerating = reportsTotal > 0
    val isComplete = reportsProgress >= reportsTotal && reportsTotal > 0

    // Viewer state
    var showViewer by remember { mutableStateOf(false) }
    var selectedAgentForViewer by remember { mutableStateOf<String?>(null) }

    // Show viewer screen when activated
    if (showViewer) {
        AiReportsViewerScreen(
            agentResults = reportsAgentResults,
            aiSettings = uiState.aiSettings,
            initialSelectedAgentId = selectedAgentForViewer,
            onDismiss = { showViewer = false }
        )
        return
    }

    // Agent selection state
    val configuredAgents = uiState.aiSettings.getConfiguredAgents()
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
                else -> "Report: ${uiState.genericAiPromptTitle}"
            },
            onBackClick = onDismiss,
            onAiClick = onDismiss
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

            Spacer(modifier = Modifier.height(8.dp))

            // Cancel button
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
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

            // Close/Cancel button
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isComplete) "Close" else "Cancel")
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
    agentResults: Map<String, com.eval.data.AiAnalysisResponse>,
    aiSettings: AiSettings,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header - show provider and model of selected agent
        val titleText = if (selectedAgent != null) {
            "${selectedAgent.provider.displayName} - ${selectedAgent.model}"
        } else {
            "View Reports"
        }
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
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
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
                        .verticalScroll(rememberScrollState())
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
private fun SearchResultsSection(searchResults: List<com.eval.data.SearchResult>) {
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
