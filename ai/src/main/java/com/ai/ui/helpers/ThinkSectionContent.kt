package com.ai.ui.helpers
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors

private sealed class ContentSegment {
    data class Text(val content: String) : ContentSegment()
    data class Think(val content: String) : ContentSegment()
    data class Table(val table: MarkdownTable) : ContentSegment()
}

private fun parseContentWithThinkSections(text: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0
    thinkPattern.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) {
            val before = text.substring(lastEnd, match.range.first).trim()
            if (before.isNotEmpty()) appendTextWithTables(segments, before)
        }
        val thinkContent = match.groupValues[1].trim()
        if (thinkContent.isNotEmpty()) segments.add(ContentSegment.Think(thinkContent))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        val remaining = text.substring(lastEnd).trim()
        if (remaining.isNotEmpty()) appendTextWithTables(segments, remaining)
    }
    if (segments.isEmpty() && text.isNotBlank()) appendTextWithTables(segments, text)
    return segments
}

/** Split a Text chunk further on GFM table blocks. Each table becomes
 *  its own segment so it can render via a Compose Row/Column grid
 *  rather than getting flattened to AnnotatedString (which has no
 *  table primitive). */
private fun appendTextWithTables(segments: MutableList<ContentSegment>, text: String) {
    val (placeheld, tables) = parseGfmTables(text)
    if (tables.isEmpty()) {
        segments.add(ContentSegment.Text(text))
        return
    }
    var pos = 0
    MD_TABLE_PLACEHOLDER_REGEX.findAll(placeheld).forEach { match ->
        // Bounds-check the placeholder index — the regex is a plain
        // alphanumeric token (`MDTBL\d+`), so model output that happens
        // to contain a literal `MDTBL999` alongside one real table
        // matches here too. Treat any out-of-range hit as user text
        // rather than indexing past tables.size and crashing.
        val idx = match.groupValues[1].toIntOrNull()
        if (idx == null || idx !in tables.indices) return@forEach
        if (match.range.first > pos) {
            val chunk = placeheld.substring(pos, match.range.first).trim()
            if (chunk.isNotEmpty()) segments.add(ContentSegment.Text(chunk))
        }
        segments.add(ContentSegment.Table(tables[idx]))
        pos = match.range.last + 1
    }
    if (pos < placeheld.length) {
        val tail = placeheld.substring(pos).trim()
        if (tail.isNotEmpty()) segments.add(ContentSegment.Text(tail))
    }
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
            is ContentSegment.Table -> MarkdownTableSection(segment.table)
        }
    }
}

@Composable
private fun MarkdownTableSection(table: MarkdownTable) {
    val divider = Color(0xFF3A3A3A)
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .background(Color(0xFF222222), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .border(1.dp, divider, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
    ) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A3A), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .padding(8.dp)
        ) {
            for ((i, h) in table.headers.withIndex()) {
                Text(
                    text = stripInlineMarkdown(h),
                    color = Color(0xFF9FCFFF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    textAlign = textAlignFor(table.alignments.getOrNull(i))
                )
            }
        }
        for ((rowIdx, row) in table.rows.withIndex()) {
            HorizontalDivider(color = divider, thickness = 1.dp)
            Row(modifier = Modifier
                .fillMaxWidth()
                .background(if (rowIdx % 2 == 1) Color(0xFF262626) else Color(0xFF222222))
                .padding(8.dp)
            ) {
                for (i in 0 until table.headers.size) {
                    Text(
                        text = stripInlineMarkdown(row.getOrNull(i) ?: ""),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        textAlign = textAlignFor(table.alignments.getOrNull(i))
                    )
                }
            }
        }
    }
}

private fun textAlignFor(a: TableAlign?) = when (a) {
    TableAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
    TableAlign.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
    else -> androidx.compose.ui.text.style.TextAlign.Start
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
