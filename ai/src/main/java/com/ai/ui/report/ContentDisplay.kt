package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ai.data.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

private val conclusionTagRegex = Regex("<conclusion>.*?</conclusion>", RegexOption.DOT_MATCHES_ALL)
private val motivationTagRegex = Regex("<motivation>.*?</motivation>", RegexOption.DOT_MATCHES_ALL)

@Composable
fun ReportsViewerScreen(
    reportId: String,
    initialSelectedAgentId: String? = null,
    initialSection: String? = null,  // "prompt" / "costs" — driven from the Report Result View buttons
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss,
    onNavigateToTraceFile: (String) -> Unit = {}
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    val report = remember(reportId) { ReportStorage.getReport(context, reportId) }

    if (report == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TitleBar(title = "View Reports", onBackClick = onDismiss, onAiClick = onNavigateHome)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Report not found", color = AppColors.TextSecondary, fontSize = 16.sp)
            }
        }
        return
    }

    // Single-section variants: just the prompt, or just the cost table — no agent picker,
    // no per-agent body. These come from the View row's Prompt / Costs buttons.
    if (initialSection == "prompt" || initialSection == "costs") {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val title = if (initialSection == "prompt") "Prompt" else "Cost summary"
            TitleBar(title = title, onBackClick = onDismiss, onAiClick = onNavigateHome)
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                if (initialSection == "prompt") {
                    if (report.prompt.isBlank()) {
                        Text("(no prompt recorded)", color = AppColors.TextTertiary, fontSize = 14.sp)
                    } else {
                        Text(report.prompt, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
                    }
                } else {
                    val hasCosts = report.agents.any { it.tokenUsage != null && (it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR) }
                    if (!hasCosts) {
                        Text("(no usage recorded)", color = AppColors.TextTertiary, fontSize = 14.sp)
                    } else {
                        ReportCostTable(report = report)
                    }
                }
            }
        }
        return
    }

    val agentsWithResults = remember(report) {
        report.agents.filter { it.reportStatus == ReportStatus.SUCCESS }.sortedBy { it.agentName.lowercase() }
    }
    var selectedAgentId by remember { mutableStateOf(initialSelectedAgentId ?: agentsWithResults.firstOrNull()?.agentId) }
    val selectedReportAgent = selectedAgentId?.let { id -> report.agents.find { it.agentId == id } }
    val scrollState = rememberScrollState()
    LaunchedEffect(selectedAgentId) { scrollState.scrollTo(0) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val providerName = selectedReportAgent?.let { AppService.findById(it.provider)?.displayName ?: it.provider } ?: "View Reports"
        TitleBar(title = providerName, onBackClick = onDismiss, onAiClick = onNavigateHome)

        // Agent buttons
        if (agentsWithResults.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                agentsWithResults.forEach { agent ->
                    val isSelected = agent.agentId == selectedAgentId
                    Button(
                        onClick = { selectedAgentId = agent.agentId },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Purple else Color(0xFF3A3A4A)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 40.dp)
                    ) { Text(agent.agentName, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) }
                }
            }

            if (selectedReportAgent != null) {
                val provDisplay = AppService.findById(selectedReportAgent.provider)?.displayName ?: selectedReportAgent.provider
                Text("$provDisplay \u2014 ${selectedReportAgent.model}", fontSize = 18.sp, color = AppColors.Blue,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }

        // Content
        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            if (agentsWithResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No successful reports to display", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            } else if (selectedReportAgent?.responseBody != null) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    val rawBody = selectedReportAgent.responseBody ?: ""
                    val conclusion = extractTagContent(rawBody, "conclusion")
                    val motivation = extractTagContent(rawBody, "motivation")
                    val strippedBody = if (conclusion != null || motivation != null)
                        rawBody.replace(conclusionTagRegex, "").replace(motivationTagRegex, "").trim()
                    else rawBody

                    if (conclusion != null) {
                        Text("Conclusion", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        ContentWithThinkSections(analysis = conclusion)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (motivation != null) {
                        Text("Motivation", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        ContentWithThinkSections(analysis = motivation)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (strippedBody.isNotBlank()) {
                        if (conclusion != null || motivation != null) {
                            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        ContentWithThinkSections(analysis = strippedBody)
                    }

                    selectedReportAgent.citations?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); CitationsSection(it) }
                    selectedReportAgent.searchResults?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); SearchResultsSection(it) }
                    selectedReportAgent.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); RelatedQuestionsSection(it) }

                    // "Trace" button — finds the most recent on-disk trace
                    // for (reportId, agent.model) and opens its detail view.
                    // Hidden when there's no matching trace (purged, never
                    // captured, or another agent's call is selected).
                    val traceFilename = remember(reportId, selectedReportAgent.model, selectedReportAgent.agentId) {
                        ApiTracer.getTraceFiles()
                            .filter { it.reportId == reportId && it.model == selectedReportAgent.model }
                            .maxByOrNull { it.timestamp }?.filename
                    }
                    if (traceFilename != null) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { onNavigateToTraceFile(traceFilename) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                        ) { Text("Trace", fontSize = 14.sp, maxLines = 1, softWrap = false) }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No analysis available", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            }
        }
    }
}

// ===== Cost Table =====

@Composable
fun ReportCostTable(report: Report) {
    val context = LocalContext.current
    val agentsWithCosts = remember(report) {
        report.agents.filter { it.tokenUsage != null && (it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR) }
    }
    if (agentsWithCosts.isEmpty()) return

    data class CostRow(val providerDisplay: String, val model: String, val durationMs: Long?, val inputTokens: Int, val outputTokens: Int, val inputCents: Double, val outputCents: Double)

    val rows = agentsWithCosts.map { agent ->
        val providerEnum = AppService.findById(agent.provider)
        val tu = agent.tokenUsage!!
        val pricing = providerEnum?.let { PricingCache.getPricing(context, it, agent.model) }
        val inCents = (pricing?.let { tu.inputTokens * it.promptPrice } ?: 0.0) * 100
        val outCents = (pricing?.let { tu.outputTokens * it.completionPrice } ?: 0.0) * 100
        CostRow(providerEnum?.displayName ?: agent.provider, agent.model, agent.durationMs, tu.inputTokens, tu.outputTokens, inCents, outCents)
    }.sortedByDescending { it.inputCents + it.outputCents }

    var totalIn = 0; var totalOut = 0; var totalInC = 0.0; var totalOutC = 0.0
    rows.forEach { totalIn += it.inputTokens; totalOut += it.outputTokens; totalInC += it.inputCents; totalOutC += it.outputCents }

    fun fmtC(v: Double) = "%.2f".format(v)
    fun fmtS(ms: Long?) = if (ms != null) "%.1f".format(ms / 1000.0) else ""
    fun fmtT(n: Int) = "%,d".format(n)

    val hColor = AppColors.Blue; val vColor = AppColors.TextSecondary; val tColor = AppColors.Blue
    val hSize = 11.sp; val vSize = 11.sp

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Row {
                    Text("Total \u00A2", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Provider", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp).padding(start = 8.dp))
                    Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                    Text("Sec", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("In tok", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Out tok", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("In \u00A2", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Out \u00A2", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp, modifier = Modifier.width(554.dp))
                rows.forEach { r ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(fmtC(r.inputCents + r.outputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(r.providerDisplay, fontSize = vSize, color = vColor, modifier = Modifier.width(90.dp).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.model, fontSize = vSize, color = vColor, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                        Text(fmtS(r.durationMs), fontSize = vSize, color = vColor, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtT(r.inputTokens), fontSize = vSize, color = vColor, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtT(r.outputTokens), fontSize = vSize, color = vColor, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtC(r.inputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtC(r.outputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp, modifier = Modifier.width(554.dp))
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(fmtC(totalInC + totalOutC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text("Total", fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(210.dp).padding(start = 8.dp))
                    Text("", fontSize = vSize, modifier = Modifier.width(48.dp))
                    Text(fmtT(totalIn), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtT(totalOut), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtC(totalInC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtC(totalOutC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ===== Content Parsing =====

private sealed class ContentSegment {
    data class Text(val content: String) : ContentSegment()
    data class Think(val content: String) : ContentSegment()
}

private fun parseContentWithThinkSections(text: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0
    thinkPattern.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) {
            val before = text.substring(lastEnd, match.range.first).trim()
            if (before.isNotEmpty()) segments.add(ContentSegment.Text(before))
        }
        val thinkContent = match.groupValues[1].trim()
        if (thinkContent.isNotEmpty()) segments.add(ContentSegment.Think(thinkContent))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        val remaining = text.substring(lastEnd).trim()
        if (remaining.isNotEmpty()) segments.add(ContentSegment.Text(remaining))
    }
    if (segments.isEmpty() && text.isNotBlank()) segments.add(ContentSegment.Text(text))
    return segments
}

@Composable
internal fun ContentWithThinkSections(analysis: String) {
    val segments = remember(analysis) { parseContentWithThinkSections(analysis) }
    segments.forEach { segment ->
        when (segment) {
            is ContentSegment.Text -> {
                val html = remember(segment.content) { convertMarkdownToSimpleHtml(segment.content) }
                HtmlContentDisplay(htmlContent = html)
            }
            is ContentSegment.Think -> ThinkSection(content = segment.content)
        }
    }
}

@Composable
private fun ThinkSection(content: String) {
    var isExpanded by remember { mutableStateOf(false) }
    Column {
        Button(
            onClick = { isExpanded = !isExpanded },
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceDark),
            border = BorderStroke(1.dp, AppColors.TextDim),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) { Text(if (isExpanded) "Hide Think" else "Think", color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, softWrap = false) }

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525))) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(AppColors.TextDisabled))
                Text(content, color = Color(0xFF999999), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// ===== HTML Conversion =====

internal fun convertMarkdownToSimpleHtml(markdown: String): String {
    var html = markdown.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n")
    html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace("\n\n", "</p><p>").replace("\n", "<br>")
    html = html.replace(Regex("(<li>.*?</li>)+")) { "<ul>${it.value}</ul>" }
    if (html.isNotBlank()) html = "<p>$html</p>"
    return html
}

@Composable
private fun HtmlContentDisplay(htmlContent: String) {
    val annotatedString = remember(htmlContent) { parseHtmlToAnnotatedString(htmlContent) }
    Text(text = annotatedString, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth())
}

private fun parseHtmlToAnnotatedString(html: String): androidx.compose.ui.text.AnnotatedString {
    val cleanHtml = html.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("<p>", "").replace("</p>", "\n\n").replace("<br>", "\n")
        .replace("<ul>", "\n").replace("</ul>", "\n")
        .replace("<li>", "  \u2022 ").replace("</li>", "\n")
        .replace(Regex("\n{3,}"), "\n\n").trim()

    return buildAnnotatedString {
        val tagPattern = Regex("<(/?)(h[123]|strong|em)>")
        var lastEnd = 0
        val matches = tagPattern.findAll(cleanHtml).toList()
        val styleStack = mutableListOf<Pair<String, Int>>()

        for (match in matches) {
            if (match.range.first > lastEnd) append(cleanHtml.substring(lastEnd, match.range.first))
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2]
            if (!isClosing) {
                styleStack.add(tagName to length)
            } else {
                val idx = styleStack.indexOfLast { it.first == tagName }
                if (idx >= 0) {
                    val (_, startPos) = styleStack.removeAt(idx)
                    val style = when (tagName) {
                        "h1" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White)
                        "h2" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF8BB8FF))
                        "h3" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF9FCFFF))
                        "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "em" -> SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFCCCCCC))
                        else -> null
                    }
                    style?.let { addStyle(it, startPos, length) }
                }
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < cleanHtml.length) append(cleanHtml.substring(lastEnd))
    }
}

// ===== Supplementary Sections =====

@Composable
private fun CitationsSection(citations: List<String>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Sources", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Purple, modifier = Modifier.padding(bottom = 12.dp))
        citations.forEachIndexed { i, url ->
            Row(modifier = Modifier.padding(vertical = 4.dp).clickable {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } catch (_: Exception) {}
            }) {
                Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                Text(url, color = Color(0xFF64B5F6), fontSize = 14.sp, modifier = Modifier.weight(1f), textDecoration = TextDecoration.Underline)
            }
        }
    }
}

@Composable
private fun SearchResultsSection(searchResults: List<SearchResult>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Search Results", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Orange, modifier = Modifier.padding(bottom = 12.dp))
        searchResults.forEachIndexed { i, result ->
            if (result.url != null) {
                Column(modifier = Modifier.padding(vertical = 6.dp).clickable {
                    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, result.url.toUri())) } catch (_: Exception) {}
                }) {
                    Row {
                        Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                        Text(result.name ?: result.url, color = Color(0xFF64B5F6), fontSize = 14.sp, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
                    }
                    if (result.name != null && result.name != result.url) Text(result.url, color = AppColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                    if (!result.snippet.isNullOrBlank()) Text(result.snippet, color = Color(0xFFBBBBBB), fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun RelatedQuestionsSection(relatedQuestions: List<String>) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Related Questions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Green, modifier = Modifier.padding(bottom = 12.dp))
        relatedQuestions.forEachIndexed { i, q ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                Text(q, color = Color(0xFFE0E0E0), fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ===== Tag Extraction =====

internal fun extractTagContent(text: String, tagName: String): String? {
    val pattern = Regex("<$tagName>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL)
    return pattern.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
