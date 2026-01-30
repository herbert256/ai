package com.ai.ui

import android.content.Intent

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
        if (developerMode && response.rawUsageJson != null) {
            val escapedJson = response.rawUsageJson.replace("<", "&lt;").replace(">", "&gt;")
            htmlBuilder.append("""
                    <div class="usage-label">API Usage:</div>
                    <pre class="usage-json">$escapedJson</pre>
            """)
        }

        // Add HTTP headers for this agent when developer mode is on
        if (developerMode && response.httpHeaders != null) {
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

    // Add rapport text if present (text below "-- rapport --" from external apps)
    // This is raw HTML content, inserted as-is without escaping
    report.rapportText?.takeIf { it.isNotBlank() }?.let { rapportText ->
        htmlBuilder.append(rapportText)
    }

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
        if (developerMode && agent.responseHeaders != null) {
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
