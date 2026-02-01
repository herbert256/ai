package com.ai.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        // Header - show agent name and model in title bar
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
                val providerDisplayName = com.ai.data.AiService.findById(selectedReportAgent.provider)?.displayName
                    ?: selectedReportAgent.provider
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
                    ContentWithThinkSections(analysis = selectedReportAgent.responseBody ?: "")

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

/**
 * Extract content between XML-like tags from text.
 * Returns the content between <tagName>...</tagName>, or null if not found.
 */
internal fun extractTagContent(text: String, tagName: String): String? {
    val pattern = Regex("<$tagName>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL)
    return pattern.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Table viewer for AI reports - shows horizontal scrolling cards, one per successful worker.
 * Each card displays: provider name, model name, conclusion, and motivation.
 */
@Composable
fun AiReportsTableViewerScreen(
    reportId: String,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss
) {
    val context = LocalContext.current

    val report = remember(reportId) {
        com.ai.data.AiReportStorage.getReport(context, reportId)
    }

    if (report == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AiTitleBar(
                title = "Table View",
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

    val successfulAgents = report.agents
        .filter { it.reportStatus == com.ai.data.ReportStatus.SUCCESS }
        .sortedBy { it.agentName.lowercase() }

    // Calculate total cost
    val totalCost = report.agents.mapNotNull { it.cost }.sum()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AiTitleBar(
            title = report.title,
            onBackClick = onDismiss,
            onAiClick = onNavigateHome
        )

        // Rapport text section (from <user> tags)
        if (!report.rapportText.isNullOrBlank()) {
            Text(
                text = report.rapportText,
                fontSize = 14.sp,
                color = Color(0xFFCCCCCC),
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (successfulAgents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No successful reports to display",
                    color = Color(0xFFAAAAAA),
                    fontSize = 16.sp
                )
            }
        } else {
            // Horizontal scrolling cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                successfulAgents.forEach { agent ->
                    val responseBody = agent.responseBody ?: ""
                    val conclusion = extractTagContent(responseBody, "conclusion")
                    val motivation = extractTagContent(responseBody, "motivation")

                    val providerDisplayName = com.ai.data.AiService.findById(agent.provider)?.displayName
                        ?: agent.provider

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A2A2A)
                        ),
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Row 1: Provider name (small gray) + Model name (blue)
                            Text(
                                text = providerDisplayName,
                                fontSize = 11.sp,
                                color = Color(0xFF888888),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = agent.model,
                                fontSize = 14.sp,
                                color = Color(0xFF6B9BFF),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Row 2: Conclusion
                            if (conclusion != null) {
                                Text(
                                    text = "Conclusion",
                                    fontSize = 11.sp,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = conclusion,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Row 3: Motivation
                            if (motivation != null) {
                                Text(
                                    text = "Motivation",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFF9800),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = motivation,
                                    fontSize = 13.sp,
                                    color = Color(0xFFCCCCCC),
                                    lineHeight = 18.sp
                                )
                            }

                            // Fallback: if no tags found, show full response
                            if (conclusion == null && motivation == null) {
                                Text(
                                    text = responseBody,
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Footer: Prompt + Cost
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (report.prompt.isNotBlank()) {
                Text(
                    text = "Prompt",
                    fontSize = 12.sp,
                    color = Color(0xFF6B9BFF),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = report.prompt,
                    fontSize = 12.sp,
                    color = Color(0xFFAAAAAA),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = "Total cost: ${String.format("%.4f", totalCost * 100)} cents  |  ${successfulAgents.size} workers",
                fontSize = 12.sp,
                color = Color(0xFF4CAF50),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
