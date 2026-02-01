package com.ai.ui

import android.content.Intent
import android.net.Uri

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

// Common data classes for unified HTML report rendering
private data class HtmlReportData(
    val title: String,
    val prompt: String,
    val timestamp: String,
    val rapportText: String?,
    val developerMode: Boolean,
    val agents: List<HtmlAgentData>,
    val reportType: com.ai.data.ReportType = com.ai.data.ReportType.CLASSIC
)

private data class HtmlAgentData(
    val agentId: String,
    val agentName: String,
    val provider: com.ai.data.AiService?,
    val providerDisplay: String,
    val model: String,
    val responseText: String?,
    val errorMessage: String?,
    val citations: List<String>?,
    val searchResults: List<com.ai.data.SearchResult>?,
    val relatedQuestions: List<String>?,
    val rawUsageJson: String?,
    val responseHeaders: String?,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null
)

// Helper function to convert generic AI reports to HTML
internal fun convertGenericAiReportsToHtml(context: android.content.Context, uiState: AiUiState, appVersion: String): String {
    val data = HtmlReportData(
        title = uiState.genericAiPromptTitle,
        prompt = uiState.genericAiPromptText,
        timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date()),
        rapportText = null,
        developerMode = uiState.generalSettings.developerMode,
        agents = uiState.genericAiReportsAgentResults.entries.mapNotNull { (agentId, response) ->
            val agent = uiState.aiSettings.getAgentById(agentId) ?: return@mapNotNull null
            HtmlAgentData(
                agentId = agentId,
                agentName = agent.name,
                provider = agent.provider,
                providerDisplay = agent.provider.displayName,
                model = agent.model,
                responseText = response.analysis,
                errorMessage = response.error,
                citations = response.citations,
                searchResults = response.searchResults,
                relatedQuestions = response.relatedQuestions,
                rawUsageJson = response.rawUsageJson,
                responseHeaders = response.httpHeaders,
                inputTokens = response.tokenUsage?.inputTokens,
                outputTokens = response.tokenUsage?.outputTokens,
                inputCost = response.tokenUsage?.let { tu ->
                    val pricing = com.ai.data.PricingCache.getPricing(context, agent.provider, agent.model)
                    tu.inputTokens * pricing.promptPrice
                },
                outputCost = response.tokenUsage?.let { tu ->
                    val pricing = com.ai.data.PricingCache.getPricing(context, agent.provider, agent.model)
                    tu.outputTokens * pricing.completionPrice
                }
            )
        }.sortedBy { it.agentName.lowercase() }
    )
    return renderHtmlReport(data, appVersion)
}

// Helper functions for sharing/opening generic AI reports
internal fun shareGenericAiReports(context: android.content.Context, uiState: AiUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertGenericAiReportsToHtml(context, uiState, appVersion)

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

        val gson = com.ai.data.createAiGson(prettyPrint = true)
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

        val html = convertAiReportToHtml(context, report, appVersion, developerMode)

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

/**
 * Emails the AI Report as HTML to the specified email address.
 * Uses ACTION_SEND with the email pre-filled so it goes directly to the email client.
 * Returns true if the intent was launched successfully.
 */
internal fun emailAiReportAsHtml(context: android.content.Context, reportId: String, emailAddress: String, developerMode: Boolean = false): Boolean {
    try {
        val report = com.ai.data.AiReportStorage.getReport(context, reportId) ?: return false

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }

        val html = convertAiReportToHtml(context, report, appVersion, developerMode)

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
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(emailAddress))
            putExtra(Intent.EXTRA_SUBJECT, "AI Report - ${report.title}")
            putExtra(Intent.EXTRA_TEXT, "AI analysis report: ${report.title}.\n\nOpen the attached HTML file in a browser to view the report.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Email AI Report"))
        return true
    } catch (e: Exception) {
        return false
    }
}

internal fun openGenericAiReportsInChrome(context: android.content.Context, uiState: AiUiState) {
    try {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        val html = convertGenericAiReportsToHtml(context, uiState, appVersion)

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

        val html = convertAiReportToHtml(context, report, appVersion, developerMode)

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
internal fun convertAiReportToHtml(context: android.content.Context, report: com.ai.data.AiReport, appVersion: String, developerMode: Boolean = false): String {
    val data = HtmlReportData(
        title = report.title,
        prompt = report.prompt,
        timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(report.timestamp)),
        rapportText = report.rapportText,
        developerMode = developerMode,
        reportType = report.reportType,
        agents = report.agents.map { agent ->
            val providerEnum = com.ai.data.AiService.findById(agent.provider)
            val providerDisplay = providerEnum?.displayName ?: agent.provider
            val errorMsg = if (agent.reportStatus == com.ai.data.ReportStatus.ERROR || agent.errorMessage != null) {
                agent.errorMessage ?: "Unknown error"
            } else null
            HtmlAgentData(
                agentId = agent.agentId,
                agentName = agent.agentName,
                provider = providerEnum,
                providerDisplay = providerDisplay,
                model = agent.model,
                responseText = if (errorMsg == null) agent.responseBody else null,
                errorMessage = errorMsg,
                citations = agent.citations,
                searchResults = agent.searchResults,
                relatedQuestions = agent.relatedQuestions,
                rawUsageJson = null,
                responseHeaders = agent.responseHeaders,
                inputTokens = agent.tokenUsage?.inputTokens,
                outputTokens = agent.tokenUsage?.outputTokens,
                inputCost = agent.tokenUsage?.let { tu ->
                    providerEnum?.let { p ->
                        val pricing = com.ai.data.PricingCache.getPricing(context, p, agent.model)
                        tu.inputTokens * pricing.promptPrice
                    }
                },
                outputCost = agent.tokenUsage?.let { tu ->
                    providerEnum?.let { p ->
                        val pricing = com.ai.data.PricingCache.getPricing(context, p, agent.model)
                        tu.outputTokens * pricing.completionPrice
                    }
                },
                durationMs = agent.durationMs
            )
        }.sortedBy { it.agentName.lowercase() }
    )
    return renderHtmlReport(data, appVersion)
}

/**
 * Renders an HTML report from unified HtmlReportData.
 * Single source of truth for the HTML/CSS/JS template.
 */
private fun renderHtmlReport(data: HtmlReportData, appVersion: String): String {
    if (data.reportType == com.ai.data.ReportType.TABLE) {
        return renderTableHtmlReport(data, appVersion)
    }
    val title = data.title
    val prompt = data.prompt
    val timestamp = data.timestamp
    val agentList = data.agents

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
                .cost-table { width: 100%; border-collapse: collapse; margin: 20px 0; font-size: 0.85em; }
                .cost-table th { color: #6B9BFF; text-align: left; padding: 6px 8px; border-bottom: 2px solid #333; font-weight: bold; }
                .cost-table td { padding: 4px 8px; border-bottom: 1px solid #2a2a2a; }
                .cost-table td.num { text-align: right; font-family: monospace; }
                .cost-table tr:last-child td { border-bottom: 2px solid #333; }
                .cost-table .total-row td { border-top: 2px solid #333; font-weight: bold; color: #6B9BFF; }
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
    """.trimIndent())

    // Add user-tagged content if present (from <user>...</user> in prompt)
    // Placed after title, before agent buttons, with matching title-style divider below
    data.rapportText?.takeIf { it.isNotBlank() }?.let { rapportText ->
        htmlBuilder.append(rapportText)
        htmlBuilder.append("""<hr style="border:none;border-top:2px solid #333;margin:10px 0 0 0">""")
    }

    htmlBuilder.append("""
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
        htmlBuilder.append("""
                <div id="agent-${agent.agentId}" class="agent-result $activeClass">
                    <div class="agent-header">${agent.providerDisplay} - ${agent.model}</div>
                    <div id="Report-${agent.agentId}" class="report-content">
        """)

        if (agent.errorMessage != null) {
            htmlBuilder.append("""
                    <div class="error">Error: ${agent.errorMessage}</div>
            """)
        } else {
            val rawAnalysis = agent.responseText ?: "No response"
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

        // Add usage data for this agent when developer mode is on
        if (data.developerMode && agent.rawUsageJson != null) {
            val escapedJson = agent.rawUsageJson.replace("<", "&lt;").replace(">", "&gt;")
            htmlBuilder.append("""
                    <div class="usage-label">API Usage:</div>
                    <pre class="usage-json">$escapedJson</pre>
            """)
        }

        // Add HTTP headers for this agent when developer mode is on
        if (data.developerMode && agent.responseHeaders != null) {
            val escapedHeaders = agent.responseHeaders.replace("<", "&lt;").replace(">", "&gt;")
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

    // Cost summary table sorted by total cost descending
    val agentsWithCosts = agentList.filter { it.inputTokens != null || it.outputTokens != null }
    if (agentsWithCosts.isNotEmpty()) {
        val sorted = agentsWithCosts.sortedByDescending { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        htmlBuilder.append("""
                <div class="prompt-section">
                    <div class="prompt-label">Costs</div>
                    <table class="cost-table">
                        <tr>
                            <th>Provider</th>
                            <th>Model</th>
                            <th style="text-align:right">Secs</th>
                            <th style="text-align:right">In tokens</th>
                            <th style="text-align:right">Out tokens</th>
                            <th style="text-align:right">In ¢</th>
                            <th style="text-align:right">Out ¢</th>
                            <th style="text-align:right">Total ¢</th>
                        </tr>
        """)
        fun fmtCents(v: Double): String = "%.2f".format(v)
        fun fmtSecs(ms: Long?): String = if (ms != null) "%.2f".format(ms / 1000.0) else ""
        var totalIn = 0
        var totalOut = 0
        var totalInCost = 0.0
        var totalOutCost = 0.0
        sorted.forEach { agent ->
            val inTok = agent.inputTokens ?: 0
            val outTok = agent.outputTokens ?: 0
            val inCost = (agent.inputCost ?: 0.0) * 100
            val outCost = (agent.outputCost ?: 0.0) * 100
            totalIn += inTok
            totalOut += outTok
            totalInCost += inCost
            totalOutCost += outCost
            htmlBuilder.append("""
                        <tr>
                            <td>${agent.providerDisplay}</td>
                            <td>${agent.model}</td>
                            <td class="num">${fmtSecs(agent.durationMs)}</td>
                            <td class="num">${"%,d".format(inTok)}</td>
                            <td class="num">${"%,d".format(outTok)}</td>
                            <td class="num">${fmtCents(inCost)}</td>
                            <td class="num">${fmtCents(outCost)}</td>
                            <td class="num">${fmtCents(inCost + outCost)}</td>
                        </tr>
            """)
        }
        htmlBuilder.append("""
                        <tr class="total-row">
                            <td colspan="3">Total</td>
                            <td class="num">${"%,d".format(totalIn)}</td>
                            <td class="num">${"%,d".format(totalOut)}</td>
                            <td class="num">${fmtCents(totalInCost)}</td>
                            <td class="num">${fmtCents(totalOutCost)}</td>
                            <td class="num">${fmtCents(totalInCost + totalOutCost)}</td>
                        </tr>
                    </table>
                </div>
        """)
    }

    htmlBuilder.append("""
                <div class="footer">
                    Generated by AI v$appVersion on $timestamp
                </div>
                <div style="text-align: center; font-size: 11px; color: #888; margin-top: 16px; padding: 8px;">
                    This app, named AI, is AI Slop, vibe coded with Claude Code by a boomer. 20.000+ java source lines I do not understand at all, give me Cobol and I can understand it, but OOP Java? No way !
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
 * Renders a Table-style HTML report with horizontal scrolling cards.
 * Each card shows provider, model, conclusion, motivation (or full response as fallback).
 */
private fun renderTableHtmlReport(data: HtmlReportData, appVersion: String): String {
    val title = data.title
    val prompt = data.prompt
    val timestamp = data.timestamp
    val agentList = data.agents

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
                h1 { color: #6B9BFF; border-bottom: 2px solid #333; padding-bottom: 10px; margin: 0 0 10px 0; }
                .cards-row {
                    display: flex;
                    flex-direction: row;
                    gap: 12px;
                    overflow-x: auto;
                    padding: 12px 0;
                    align-items: stretch;
                }
                .card {
                    min-width: 220px;
                    max-width: 280px;
                    flex: 0 0 auto;
                    background: #2a2a2a;
                    border-radius: 8px;
                    padding: 12px;
                    display: flex;
                    flex-direction: column;
                }
                .card-provider { font-size: 11px; color: #888; }
                .card-model { font-size: 14px; color: #6B9BFF; font-weight: 600; margin-bottom: 8px; }
                .card-label-conclusion { font-size: 11px; color: #4CAF50; font-weight: bold; }
                .card-label-motivation { font-size: 11px; color: #FF9800; font-weight: bold; margin-top: 8px; }
                .card-text { font-size: 13px; color: #fff; line-height: 1.5; margin-top: 4px; }
                .card-text-dim { font-size: 13px; color: #ccc; line-height: 1.5; margin-top: 4px; }
                .card-fallback { font-size: 13px; color: #fff; line-height: 1.5; }
                .card-fallback p { margin: 0 0 0.8em 0; }
                .card-fallback h2, .card-fallback h3, .card-fallback h4 { color: #6B9BFF; margin: 0.8em 0 0.3em 0; }
                .card-fallback ul { margin: 0.3em 0; padding-left: 1.2em; }
                .card-fallback code { background: #333; padding: 2px 4px; border-radius: 3px; font-family: monospace; font-size: 0.9em; }
                .card-fallback pre { background: #222; padding: 8px; border-radius: 4px; overflow-x: auto; font-size: 0.85em; }
                .card-fallback pre code { background: none; padding: 0; }
                .error { color: #ff6b6b; font-size: 13px; }
                .prompt-section { margin: 20px 0; }
                .prompt-label { color: #6B9BFF; font-weight: bold; font-size: 1.1em; margin-bottom: 10px; }
                .prompt-text {
                    white-space: pre-wrap;
                    margin: 0;
                    font-family: monospace;
                    font-size: 0.9em;
                    color: #ccc;
                }
                .cost-table { width: 100%; border-collapse: collapse; margin: 20px 0; font-size: 0.85em; }
                .cost-table th { color: #6B9BFF; text-align: left; padding: 6px 8px; border-bottom: 2px solid #333; font-weight: bold; }
                .cost-table td { padding: 4px 8px; border-bottom: 1px solid #2a2a2a; }
                .cost-table td.num { text-align: right; font-family: monospace; }
                .cost-table tr:last-child td { border-bottom: 2px solid #333; }
                .cost-table .total-row td { border-top: 2px solid #333; font-weight: bold; color: #6B9BFF; }
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
                .think-btn:hover { border-color: #888; color: #ccc; }
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
                .think-content.visible { display: block; }
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
            <h1>$title</h1>
    """.trimIndent())

    // Rapport text
    data.rapportText?.takeIf { it.isNotBlank() }?.let { rapportText ->
        htmlBuilder.append(rapportText)
        htmlBuilder.append("""<hr style="border:none;border-top:2px solid #333;margin:10px 0 0 0">""")
    }

    // Horizontal cards row
    val successfulAgents = agentList.filter { it.errorMessage == null }
    if (successfulAgents.isNotEmpty()) {
        htmlBuilder.append("""<div class="cards-row">""")
        successfulAgents.forEach { agent ->
            val rawResponse = agent.responseText ?: ""
            val conclusion = extractTagContent(rawResponse, "conclusion")
            val motivation = extractTagContent(rawResponse, "motivation")

            htmlBuilder.append("""<div class="card">""")
            htmlBuilder.append("""<div class="card-provider">${agent.providerDisplay}</div>""")
            htmlBuilder.append("""<div class="card-model">${agent.model}</div>""")

            if (conclusion != null || motivation != null) {
                if (conclusion != null) {
                    val escapedConclusion = conclusion.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    htmlBuilder.append("""<div class="card-label-conclusion">Conclusion</div>""")
                    htmlBuilder.append("""<div class="card-text">${escapedConclusion.replace("\n", "<br>")}</div>""")
                }
                if (motivation != null) {
                    val escapedMotivation = motivation.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    htmlBuilder.append("""<div class="card-label-motivation">Motivation</div>""")
                    htmlBuilder.append("""<div class="card-text-dim">${escapedMotivation.replace("\n", "<br>")}</div>""")
                }
            } else {
                // Fallback: render full response with markdown conversion
                val processed = processThinkSectionsForHtml(rawResponse, agent.agentId)
                htmlBuilder.append("""<div class="card-fallback">$processed</div>""")
            }

            htmlBuilder.append("""</div>""") // close card
        }
        htmlBuilder.append("""</div>""") // close cards-row
    }

    // Error agents
    val failedAgents = agentList.filter { it.errorMessage != null }
    if (failedAgents.isNotEmpty()) {
        failedAgents.forEach { agent ->
            htmlBuilder.append("""<div class="error">${agent.agentName}: ${agent.errorMessage}</div>""")
        }
    }

    // Prompt section
    htmlBuilder.append("""
                <div class="prompt-section">
                    <div class="prompt-label">Prompt:</div>
                    <pre class="prompt-text">${prompt.replace("<", "&lt;").replace(">", "&gt;")}</pre>
                </div>
    """)

    // Cost summary table
    val agentsWithCosts = agentList.filter { it.inputTokens != null || it.outputTokens != null }
    if (agentsWithCosts.isNotEmpty()) {
        val sorted = agentsWithCosts.sortedByDescending { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        htmlBuilder.append("""
                <div class="prompt-section">
                    <div class="prompt-label">Costs</div>
                    <table class="cost-table">
                        <tr>
                            <th>Provider</th>
                            <th>Model</th>
                            <th style="text-align:right">Secs</th>
                            <th style="text-align:right">In tokens</th>
                            <th style="text-align:right">Out tokens</th>
                            <th style="text-align:right">In ¢</th>
                            <th style="text-align:right">Out ¢</th>
                            <th style="text-align:right">Total ¢</th>
                        </tr>
        """)
        fun fmtCents(v: Double): String = "%.2f".format(v)
        fun fmtSecs(ms: Long?): String = if (ms != null) "%.2f".format(ms / 1000.0) else ""
        var totalIn = 0
        var totalOut = 0
        var totalInCost = 0.0
        var totalOutCost = 0.0
        sorted.forEach { agent ->
            val inTok = agent.inputTokens ?: 0
            val outTok = agent.outputTokens ?: 0
            val inCost = (agent.inputCost ?: 0.0) * 100
            val outCost = (agent.outputCost ?: 0.0) * 100
            totalIn += inTok
            totalOut += outTok
            totalInCost += inCost
            totalOutCost += outCost
            htmlBuilder.append("""
                        <tr>
                            <td>${agent.providerDisplay}</td>
                            <td>${agent.model}</td>
                            <td class="num">${fmtSecs(agent.durationMs)}</td>
                            <td class="num">${"%,d".format(inTok)}</td>
                            <td class="num">${"%,d".format(outTok)}</td>
                            <td class="num">${fmtCents(inCost)}</td>
                            <td class="num">${fmtCents(outCost)}</td>
                            <td class="num">${fmtCents(inCost + outCost)}</td>
                        </tr>
            """)
        }
        htmlBuilder.append("""
                        <tr class="total-row">
                            <td colspan="3">Total</td>
                            <td class="num">${"%,d".format(totalIn)}</td>
                            <td class="num">${"%,d".format(totalOut)}</td>
                            <td class="num">${fmtCents(totalInCost)}</td>
                            <td class="num">${fmtCents(totalOutCost)}</td>
                            <td class="num">${fmtCents(totalInCost + totalOutCost)}</td>
                        </tr>
                    </table>
                </div>
        """)
    }

    htmlBuilder.append("""
                <div class="footer">
                    Generated by AI v$appVersion on $timestamp
                </div>
                <div style="text-align: center; font-size: 11px; color: #888; margin-top: 16px; padding: 8px;">
                    This app, named AI, is AI Slop, vibe coded with Claude Code by a boomer. 20.000+ java source lines I do not understand at all, give me Cobol and I can understand it, but OOP Java? No way !
                </div>
        </body>
        </html>
    """.trimIndent())

    return htmlBuilder.toString()
}
