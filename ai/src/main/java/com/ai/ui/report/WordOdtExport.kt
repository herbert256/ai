package com.ai.ui.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ai.data.SecondaryKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ===== Block model =====
// Both .docx and .odt are zipped XML packages with very different schemas
// but a similar paragraph-based logical model. We build a flat list of
// blocks once and emit it in each format. Lossy by design — markdown
// inline formatting is stripped to plain text — but headings, bullets,
// fenced code, and simple tables survive. Headings drive the TOC field
// each format embeds at the front of the document so Word/LibreOffice
// can populate page numbers when the file is opened.

internal enum class DocBlockKind { HEADING, PARAGRAPH, BULLET, CODE, TABLE, TOC }
internal data class DocBlock(
    val kind: DocBlockKind,
    val text: String = "",
    val level: Int = 0,
    val tableHeader: List<String> = emptyList(),
    val tableRows: List<List<String>> = emptyList()
)

/** Build the flat block list from the same data the Medium HTML export
 *  uses. The export shows the full set of report content: title +
 *  rapport, every per-agent response (with citations / search results /
 *  related questions), every meta result (rerank / chat-type META rows
 *  bucketed by user-given prompt name / moderation), the prompt, the
 *  per-call cost table, and every captured API trace with redacted
 *  bodies. When translations exist on the report each translated
 *  language is appended after the originals with a "Language: X"
 *  heading and the translated prompt / agents / Meta sections. The
 *  lone DOCX/ODT affordance is the leading TOC block — Word and
 *  LibreOffice fill the page numbers in when the document is
 *  opened. */
private fun buildMediumBlocks(data: com.ai.ui.report.HtmlReportData, short: Boolean): List<DocBlock> {
    val out = mutableListOf<DocBlock>()
    out += DocBlock(DocBlockKind.HEADING, data.title.ifBlank { "Untitled" }, 1)
    if (!short) {
        // Short reports skip the index per spec — no TOC field. Complete
        // reports get a Word/LibreOffice TOC the consuming app populates
        // with page numbers when the document is opened.
        out += DocBlock(DocBlockKind.HEADING, "Index", 2)
        out += DocBlock(DocBlockKind.TOC)
    }

    if (!data.rapportText.isNullOrBlank()) {
        out += mdToBlocks(data.rapportText, headingBase = 2)
    }

    val languages = com.ai.ui.report.buildLanguageViews(data)
    val maxAnchor = data.agents.mapNotNull { it.anchorIndex }.maxOrNull() ?: 0

    languages.forEachIndexed { i, lv ->
        val isOriginal = (lv.key == "original")
        if (i > 0) out += DocBlock(DocBlockKind.HEADING, "Language: ${lv.displayName}", 2)
        appendLanguageContent(out, lv.data, short, isOriginal, maxAnchor)
    }

    if (!short) {
        // Short skips the cost table and the JSON-trace dump per spec.
        appendCosts(out, data)
        appendTraces(out, data.traces)
    }

    if (!data.closeText.isNullOrBlank()) {
        out += mdToBlocks(data.closeText, headingBase = 2)
    }
    return out
}

/** Emit one language's narrative content into [out]: Reports, one
 *  section per chat-type META prompt name, (Original-only Reranks /
 *  Moderations,) Prompt. [headingBoost] is added to all headings so a
 *  translated language's Reports heading nests one level under its
 *  "Language: X" parent. */
private fun appendLanguageContent(
    out: MutableList<DocBlock>,
    data: com.ai.ui.report.HtmlReportData,
    short: Boolean,
    isOriginal: Boolean,
    maxAnchor: Int
) {
    if (data.agents.isNotEmpty()) {
        out += DocBlock(DocBlockKind.HEADING, "Reports", 2)
        for (a in data.agents) {
            val anchor = a.anchorIndex?.let { "[$it] " } ?: ""
            out += DocBlock(DocBlockKind.HEADING, "$anchor${a.providerDisplay} / ${a.model}", 3)
            if (a.errorMessage != null) {
                out += DocBlock(DocBlockKind.PARAGRAPH, "Error: ${a.errorMessage}")
            }
            if (!a.responseText.isNullOrBlank()) {
                out += mdToBlocks(a.responseText, headingBase = 4)
            }
            a.citations?.takeIf { it.isNotEmpty() }?.let { cites ->
                out += DocBlock(DocBlockKind.HEADING, "Sources", 4)
                cites.forEachIndexed { i, url -> out += DocBlock(DocBlockKind.BULLET, "${i + 1}. $url") }
            }
            a.searchResults?.takeIf { it.isNotEmpty() }?.let { results ->
                out += DocBlock(DocBlockKind.HEADING, "Search results", 4)
                results.forEachIndexed { i, r ->
                    val url = r.url ?: ""
                    val name = r.name ?: url
                    val snippet = if (!r.snippet.isNullOrBlank()) " — ${r.snippet}" else ""
                    out += DocBlock(DocBlockKind.BULLET, "${i + 1}. $name ($url)$snippet")
                }
            }
            a.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { qs ->
                out += DocBlock(DocBlockKind.HEADING, "Related questions", 4)
                qs.forEachIndexed { i, q -> out += DocBlock(DocBlockKind.BULLET, "${i + 1}. $q") }
            }
        }
    }
    // Chat-type META rows bucket by user-given prompt name. The
    // section heading IS the name — "Compare", "Critique", … — so a
    // user with several Meta prompts gets a section per prompt
    // instead of everything jammed under a single hardcoded label.
    appendMetaByName(out, data.secondary, maxAnchor)
    if (isOriginal && !short) {
        // Reranks / Moderations are structured JSON — never translated;
        // skipped entirely on the per-language repeats too. Short skips
        // them per spec.
        appendSecondary(out, data.secondary, SecondaryKind.RERANK, "Reranks", maxAnchor)
    }
    if (isOriginal) appendSecondary(out, data.secondary, SecondaryKind.MODERATION, "Moderations", maxAnchor)
    if (data.prompt.isNotBlank()) {
        out += DocBlock(DocBlockKind.HEADING, "Prompt", 2)
        data.prompt.split(Regex("\n\\s*\n")).forEach { para ->
            val t = para.trim()
            if (t.isNotEmpty()) out += DocBlock(DocBlockKind.PARAGRAPH, t)
        }
    }
}

/** Group chat-type META rows by user-given prompt name, then emit one
 *  section per name (preserving first-seen order). Each section is a
 *  level-2 heading; rows nest underneath at level 3. */
private fun appendMetaByName(
    out: MutableList<DocBlock>, secondary: List<com.ai.ui.report.HtmlSecondaryData>,
    maxAnchor: Int
) {
    val byName = LinkedHashMap<String, MutableList<com.ai.ui.report.HtmlSecondaryData>>()
    secondary.filter { it.kind == SecondaryKind.META }.forEach { s ->
        val name = s.metaPromptName?.takeIf { it.isNotBlank() }
            ?: com.ai.data.legacyKindDisplayName(s.kind)
        byName.getOrPut(name) { mutableListOf() }.add(s)
    }
    byName.forEach { (name, items) ->
        out += DocBlock(DocBlockKind.HEADING, name, 2)
        for (s in items) {
            out += DocBlock(DocBlockKind.HEADING, "${s.providerDisplay} / ${s.model}  (${s.timestamp})", 3)
            if (s.errorMessage != null) {
                out += DocBlock(DocBlockKind.PARAGRAPH, "Error: ${s.errorMessage}")
                continue
            }
            if (s.content.isNullOrBlank()) continue
            // Markdown rendering for every chat-type Meta row regardless
            // of the prompt name. [N] anchor tags are kept as bracketed
            // numbers — DOCX/ODT can't link back to specific cards
            // without a lot of bookmark machinery.
            out += mdToBlocks(s.content, headingBase = 4)
        }
    }
    @Suppress("UNUSED_PARAMETER") val unused = maxAnchor
}

private fun appendSecondary(
    out: MutableList<DocBlock>, secondary: List<com.ai.ui.report.HtmlSecondaryData>,
    kind: SecondaryKind, heading: String, maxAnchor: Int
) {
    val items = secondary.filter { it.kind == kind }
    if (items.isEmpty()) return
    out += DocBlock(DocBlockKind.HEADING, heading, 2)
    for (s in items) {
        out += DocBlock(DocBlockKind.HEADING, "${s.providerDisplay} / ${s.model}  (${s.timestamp})", 3)
        if (s.errorMessage != null) {
            out += DocBlock(DocBlockKind.PARAGRAPH, "Error: ${s.errorMessage}")
            continue
        }
        if (s.content.isNullOrBlank()) continue
        when (kind) {
            SecondaryKind.RERANK -> {
                val table = parseRerankTable(s.content, maxAnchor)
                if (table != null) {
                    out += table
                } else {
                    out += mdToBlocks(s.content, headingBase = 4)
                }
            }
            else -> out += mdToBlocks(s.content, headingBase = 4)
        }
    }
}

private fun appendCosts(out: MutableList<DocBlock>, data: com.ai.ui.report.HtmlReportData) {
    val agentRows = data.agents.filter { it.inputCost != null }
    val secondaryRows = data.secondary.filter { it.inputTokens != null }
    if (agentRows.isEmpty() && secondaryRows.isEmpty()) return
    out += DocBlock(DocBlockKind.HEADING, "Costs", 2)

    data class Row(val type: String, val provider: String, val model: String, val tier: String,
                   val secs: String, val inT: Int, val outT: Int, val inC: Double, val outC: Double)
    val rows = mutableListOf<Row>()
    for (a in agentRows) {
        rows += Row(
            "report", a.providerDisplay, a.model, a.pricingTier ?: "",
            a.durationMs?.let { "%.1f".format(it / 1000.0) } ?: "",
            a.inputTokens ?: 0, a.outputTokens ?: 0,
            (a.inputCost ?: 0.0) * 100, (a.outputCost ?: 0.0) * 100
        )
    }
    for (s in secondaryRows) {
        val type = s.metaPromptName?.takeIf { it.isNotBlank() }?.lowercase()
            ?: when (s.kind) {
                SecondaryKind.RERANK -> "rerank"
                SecondaryKind.META -> "meta"
                SecondaryKind.MODERATION -> "moderation"
                SecondaryKind.TRANSLATE -> "translate"
            }
        rows += Row(
            type, s.providerDisplay, s.model, s.pricingTier ?: "",
            s.durationMs?.let { "%.1f".format(it / 1000.0) } ?: "",
            s.inputTokens ?: 0, s.outputTokens ?: 0,
            (s.inputCost ?: 0.0) * 100, (s.outputCost ?: 0.0) * 100
        )
    }
    rows.sortByDescending { it.inC + it.outC }
    val tIn = rows.sumOf { it.inT }
    val tOut = rows.sumOf { it.outT }
    val tInC = rows.sumOf { it.inC }
    val tOutC = rows.sumOf { it.outC }

    val tableRows = mutableListOf<List<String>>()
    rows.forEach { r ->
        tableRows += listOf(
            r.type, r.provider, r.model, r.tier, r.secs,
            r.inT.toString(), r.outT.toString(),
            "%.2f".format(r.inC), "%.2f".format(r.outC),
            "%.2f".format(r.inC + r.outC)
        )
    }
    tableRows += listOf(
        "Total", "", "", "", "",
        tIn.toString(), tOut.toString(),
        "%.2f".format(tInC), "%.2f".format(tOutC),
        "%.2f".format(tInC + tOutC)
    )
    out += DocBlock(
        kind = DocBlockKind.TABLE,
        tableHeader = listOf(
            "Type", "Provider", "Model", "Tier", "Seconds",
            "Input tokens", "Output tokens", "Input cents", "Output cents", "Total cents"
        ),
        tableRows = tableRows
    )
}

private fun appendTraces(out: MutableList<DocBlock>, traces: List<com.ai.ui.report.HtmlTraceData>) {
    if (traces.isEmpty()) return
    out += DocBlock(DocBlockKind.HEADING, "API traces (JSON)", 2)
    val groups = traces.groupBy { it.category ?: "Other" }
    val orderedKeys = groups.keys.sortedWith(compareBy({ it == "Other" }, { it.lowercase() }))
    for (cat in orderedKeys) {
        val list = groups[cat]!!
        out += DocBlock(DocBlockKind.HEADING, "$cat (${list.size})", 3)
        for (t in list) {
            val origin = if (t.origin == "source") "[source] " else ""
            val title = "$origin${t.timestamp} · ${t.method} · ${t.hostname}" +
                (if (!t.model.isNullOrBlank()) " / ${t.model}" else "") + " · ${t.statusCode}"
            out += DocBlock(DocBlockKind.HEADING, title, 4)
            out += DocBlock(DocBlockKind.PARAGRAPH, "${t.method} ${t.url}")
            out += DocBlock(DocBlockKind.HEADING, "Request headers", 5)
            out += DocBlock(DocBlockKind.CODE, t.requestHeaders.ifBlank { "(none)" })
            out += DocBlock(DocBlockKind.HEADING, "Request body", 5)
            out += DocBlock(DocBlockKind.CODE, t.requestBody.ifBlank { "(empty)" })
            out += DocBlock(DocBlockKind.HEADING, "Response headers", 5)
            out += DocBlock(DocBlockKind.CODE, t.responseHeaders.ifBlank { "(none)" })
            out += DocBlock(DocBlockKind.HEADING, "Response body", 5)
            out += DocBlock(DocBlockKind.CODE, t.responseBody.ifBlank { "(empty)" })
        }
    }
}

/** Try to parse a rerank result as the JSON array the rerank prompt
 *  asks for. On success return a TABLE block with rank/result/score/
 *  reason columns; on failure return null and let the caller fall
 *  back to plain markdown rendering. */
private fun parseRerankTable(content: String, maxAnchor: Int): DocBlock? {
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return try {
        @Suppress("DEPRECATION")
        val arr = com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (arr.size() == 0 || !arr.all { it.isJsonObject }) return null
        val rows = arr.mapNotNull { el ->
            val obj = el.asJsonObject
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt
            val score = obj.get("score")?.takeIf { it.isJsonPrimitive }?.asDouble
            val reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
            Triple(id, rank to score, reason)
        }.sortedBy { it.second.first ?: Int.MAX_VALUE }
        if (rows.isEmpty()) return null
        DocBlock(
            kind = DocBlockKind.TABLE,
            tableHeader = listOf("Rank", "Result", "Score", "Reason"),
            tableRows = rows.map { (id, rs, reason) ->
                val resultLabel = if (id in 1..maxAnchor) "[$id]" else "[$id]"
                listOf(rs.first?.toString() ?: "", resultLabel, rs.second?.let { "%.0f".format(it) } ?: "", reason ?: "")
            }
        )
    } catch (_: Exception) { null }
}

/** Tiny markdown subset → blocks. Splits on blank lines. Recognises
 *  ATX headings (#, ##, ###), unordered (- / *) and ordered (1.) lists,
 *  and triple-backtick fenced code. Inline `**bold**`, `_italic_`,
 *  ``code``, and `[text](url)` are flattened to plain text. */
internal fun mdToBlocks(md: String, headingBase: Int): List<DocBlock> {
    val out = mutableListOf<DocBlock>()
    val cleaned = md.replace("\r\n", "\n")
    val paragraphs = cleaned.split(Regex("\n\\s*\n"))
    for (raw in paragraphs) {
        val para = raw.trim()
        if (para.isEmpty()) continue
        val h3 = Regex("^###\\s+(.*)").find(para)
        val h2 = Regex("^##\\s+(.*)").find(para)
        val h1 = Regex("^#\\s+(.*)").find(para)
        val fence = Regex("^```[a-zA-Z0-9]*\n(.*?)\n```$", RegexOption.DOT_MATCHES_ALL).find(para)
        when {
            fence != null -> out += DocBlock(DocBlockKind.CODE, fence.groupValues[1])
            h3 != null -> out += DocBlock(DocBlockKind.HEADING, stripInline(h3.groupValues[1]), (headingBase + 2).coerceIn(1, 6))
            h2 != null -> out += DocBlock(DocBlockKind.HEADING, stripInline(h2.groupValues[1]), (headingBase + 1).coerceIn(1, 6))
            h1 != null -> out += DocBlock(DocBlockKind.HEADING, stripInline(h1.groupValues[1]), headingBase.coerceIn(1, 6))
            else -> {
                val lines = para.lines()
                val bulletPattern = Regex("^\\s*([-*]|\\d+\\.)\\s+.*")
                if (lines.all { bulletPattern.matches(it) }) {
                    lines.forEach { line ->
                        val text = line.replace(Regex("^\\s*([-*]|\\d+\\.)\\s+"), "")
                        out += DocBlock(DocBlockKind.BULLET, stripInline(text))
                    }
                } else {
                    out += DocBlock(DocBlockKind.PARAGRAPH, stripInline(para))
                }
            }
        }
    }
    return out
}

private fun stripInline(s: String): String = s
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("__(.+?)__"), "$1")
    .replace(Regex("\\*(.+?)\\*"), "$1")
    .replace(Regex("(?<![A-Za-z0-9])_(.+?)_(?![A-Za-z0-9])"), "$1")
    .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1")

private fun escXml(s: String): String {
    val out = StringBuilder(s.length + 16)
    for (c in s) when (c) {
        '&' -> out.append("&amp;")
        '<' -> out.append("&lt;")
        '>' -> out.append("&gt;")
        '"' -> out.append("&quot;")
        '\'' -> out.append("&apos;")
        else -> out.append(c)
    }
    return out.toString()
}

// ===== DOCX (Office Open XML) =====

internal fun buildDocxBytes(context: Context, report: com.ai.data.Report, short: Boolean = false): ByteArray {
    val data = com.ai.ui.report.buildHtmlReportData(context, report)
    val blocks = buildMediumBlocks(data, short)
    val docXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        blocks.forEach { b ->
            when (b.kind) {
                DocBlockKind.HEADING -> {
                    val lvl = b.level.coerceIn(1, 6)
                    append("""<w:p><w:pPr><w:pStyle w:val="Heading$lvl"/></w:pPr>""")
                    append("""<w:r><w:t xml:space="preserve">${escXml(b.text)}</w:t></w:r></w:p>""")
                }
                DocBlockKind.PARAGRAPH -> {
                    append("<w:p>")
                    val parts = b.text.split("\n")
                    parts.forEachIndexed { i, line ->
                        if (i > 0) append("<w:r><w:br/></w:r>")
                        append("""<w:r><w:t xml:space="preserve">${escXml(line)}</w:t></w:r>""")
                    }
                    append("</w:p>")
                }
                DocBlockKind.BULLET -> {
                    append("""<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>""")
                    append("""<w:r><w:t xml:space="preserve">${escXml(b.text)}</w:t></w:r></w:p>""")
                }
                DocBlockKind.CODE -> {
                    append("<w:p>")
                    val parts = b.text.split("\n")
                    parts.forEachIndexed { i, line ->
                        if (i > 0) append("<w:r><w:br/></w:r>")
                        append("""<w:r><w:rPr><w:rFonts w:ascii="Courier New" w:hAnsi="Courier New"/></w:rPr><w:t xml:space="preserve">${escXml(line)}</w:t></w:r>""")
                    }
                    append("</w:p>")
                }
                DocBlockKind.TABLE -> {
                    append("<w:tbl>")
                    append("""<w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:left w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:right w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="auto"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="auto"/></w:tblBorders></w:tblPr>""")
                    if (b.tableHeader.isNotEmpty()) {
                        append("<w:tr>")
                        b.tableHeader.forEach { cell ->
                            append("""<w:tc><w:tcPr></w:tcPr><w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">${escXml(cell)}</w:t></w:r></w:p></w:tc>""")
                        }
                        append("</w:tr>")
                    }
                    b.tableRows.forEach { row ->
                        append("<w:tr>")
                        row.forEach { cell ->
                            append("""<w:tc><w:tcPr></w:tcPr><w:p><w:r><w:t xml:space="preserve">${escXml(cell)}</w:t></w:r></w:p></w:tc>""")
                        }
                        append("</w:tr>")
                    }
                    append("</w:tbl>")
                    // Word requires a paragraph after every table.
                    append("<w:p/>")
                }
                DocBlockKind.TOC -> {
                    // SDT-wrapped TOC field. Word renders the placeholder
                    // text on first open; right-clicking and choosing
                    // "Update Field" populates the actual entries with
                    // page numbers. Kept simple — no fancy formatting,
                    // no page breaks before/after.
                    append("<w:p>")
                    append("""<w:r><w:fldChar w:fldCharType="begin"/></w:r>""")
                    append("""<w:r><w:instrText xml:space="preserve"> TOC \o "1-6" \h \z \u </w:instrText></w:r>""")
                    append("""<w:r><w:fldChar w:fldCharType="separate"/></w:r>""")
                    append("""<w:r><w:t xml:space="preserve">Right-click and choose "Update Field" to populate the index with page numbers.</w:t></w:r>""")
                    append("""<w:r><w:fldChar w:fldCharType="end"/></w:r>""")
                    append("</w:p>")
                }
            }
        }
        append("</w:body></w:document>")
    }

    val numberingXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/></w:lvl></w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
</w:numbering>"""

    val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:pPr><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading4"><w:name w:val="heading 4"/><w:pPr><w:outlineLvl w:val="3"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading5"><w:name w:val="heading 5"/><w:pPr><w:outlineLvl w:val="4"/></w:pPr><w:rPr><w:b/><w:sz w:val="22"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading6"><w:name w:val="heading 6"/><w:pPr><w:outlineLvl w:val="5"/></w:pPr><w:rPr><w:b/><w:i/><w:sz w:val="22"/></w:rPr></w:style>
</w:styles>"""

    val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
</Types>"""

    val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    val docRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
</Relationships>"""

    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        zos.writeEntry("[Content_Types].xml", contentTypes)
        zos.writeEntry("_rels/.rels", rootRels)
        zos.writeEntry("word/_rels/document.xml.rels", docRels)
        zos.writeEntry("word/document.xml", docXml)
        zos.writeEntry("word/styles.xml", stylesXml)
        zos.writeEntry("word/numbering.xml", numberingXml)
    }
    return baos.toByteArray()
}

// ===== ODT (OpenDocument Text) =====

internal fun buildOdtBytes(context: Context, report: com.ai.data.Report, short: Boolean = false): ByteArray {
    val data = com.ai.ui.report.buildHtmlReportData(context, report)
    val blocks = buildMediumBlocks(data, short)
    val contentXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">""")
        append("""<office:automatic-styles>""")
        append("""<style:style style:name="CodeP" style:family="paragraph" style:parent-style-name="Standard"><style:text-properties style:font-name="Courier New"/></style:style>""")
        append("""<style:style style:name="ToCBody" style:family="paragraph" style:parent-style-name="Standard"/>""")
        append("""</office:automatic-styles>""")
        append("<office:body><office:text>")
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            when (b.kind) {
                DocBlockKind.HEADING -> {
                    append("""<text:h text:outline-level="${b.level.coerceIn(1, 6)}">${escXml(b.text)}</text:h>""")
                    i++
                }
                DocBlockKind.PARAGRAPH -> {
                    val parts = b.text.split("\n")
                    parts.forEach { line -> append("<text:p>${escXml(line)}</text:p>") }
                    i++
                }
                DocBlockKind.CODE -> {
                    val parts = b.text.split("\n")
                    parts.forEach { line -> append("""<text:p text:style-name="CodeP">${escXml(line)}</text:p>""") }
                    i++
                }
                DocBlockKind.BULLET -> {
                    // ODT lists need explicit <text:list> wrapping. Group
                    // consecutive BULLET blocks into a single list element
                    // so LibreOffice doesn't start a new list per item.
                    append("<text:list>")
                    while (i < blocks.size && blocks[i].kind == DocBlockKind.BULLET) {
                        append("<text:list-item><text:p>${escXml(blocks[i].text)}</text:p></text:list-item>")
                        i++
                    }
                    append("</text:list>")
                }
                DocBlockKind.TABLE -> {
                    val colCount = maxOf(b.tableHeader.size, b.tableRows.firstOrNull()?.size ?: 0)
                    append("""<table:table table:name="Table${i}">""")
                    repeat(colCount) { append("<table:table-column/>") }
                    if (b.tableHeader.isNotEmpty()) {
                        append("<table:table-header-rows><table:table-row>")
                        b.tableHeader.forEach { cell ->
                            append("<table:table-cell><text:p>${escXml(cell)}</text:p></table:table-cell>")
                        }
                        append("</table:table-row></table:table-header-rows>")
                    }
                    b.tableRows.forEach { row ->
                        append("<table:table-row>")
                        row.forEach { cell ->
                            append("<table:table-cell><text:p>${escXml(cell)}</text:p></table:table-cell>")
                        }
                        append("</table:table-row>")
                    }
                    append("</table:table>")
                    i++
                }
                DocBlockKind.TOC -> {
                    // text:table-of-content with text:protected and an
                    // index-body whose entries LibreOffice will refresh on
                    // open. The placeholder text gets replaced when the
                    // user updates the TOC (Tools → Update → Indexes).
                    append("""<text:table-of-content text:name="ToC1" text:protected="true">""")
                    append("""<text:table-of-content-source text:outline-level="6">""")
                    append("""<text:index-title-template/>""")
                    repeat(6) { lvl ->
                        append("""<text:table-of-content-entry-template text:outline-level="${lvl + 1}" text:style-name="Standard">""")
                        append("""<text:index-entry-chapter/><text:index-entry-text/>""")
                        append("""<text:index-entry-tab-stop style:type="right" style:leader-char="."/>""")
                        append("""<text:index-entry-page-number/></text:table-of-content-entry-template>""")
                    }
                    append("""</text:table-of-content-source>""")
                    append("""<text:index-body>""")
                    append("""<text:p text:style-name="ToCBody">Use Tools → Update → Indexes to populate the index with page numbers.</text:p>""")
                    append("""</text:index-body>""")
                    append("""</text:table-of-content>""")
                    i++
                }
            }
        }
        append("</office:text></office:body></office:document-content>")
    }

    val manifestXml = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
<manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
</manifest:manifest>"""

    val mimetypeBytes = "application/vnd.oasis.opendocument.text".toByteArray()

    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        // ODT spec: "mimetype" must be the first entry, stored uncompressed,
        // with no extra fields. LibreOffice tolerates deviations; strict
        // validators don't.
        val mimetypeEntry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mimetypeBytes.size.toLong()
            compressedSize = mimetypeBytes.size.toLong()
            crc = CRC32().apply { update(mimetypeBytes) }.value
        }
        zos.putNextEntry(mimetypeEntry)
        zos.write(mimetypeBytes)
        zos.closeEntry()
        zos.writeEntry("META-INF/manifest.xml", manifestXml)
        zos.writeEntry("content.xml", contentXml)
    }
    return baos.toByteArray()
}

private fun ZipOutputStream.writeEntry(name: String, body: String) {
    putNextEntry(ZipEntry(name))
    write(body.toByteArray(Charsets.UTF_8))
    closeEntry()
}

// ===== Dispatchers =====

internal fun shareReportAsDocxOrOdt(
    context: Context, reportId: String,
    format: ReportExportFormat, detail: ReportExportDetail, action: ReportExportAction
): Boolean {
    val report = com.ai.data.ReportStorage.getReport(context, reportId) ?: return false
    val safeTitle = report.title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    val ts = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
    val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
    val isShort = detail == ReportExportDetail.SHORT
    val (bytes, ext, mime, formatLabel) = when (format) {
        ReportExportFormat.DOCX -> Quad(buildDocxBytes(context, report, isShort), "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "MS Word")
        ReportExportFormat.ODT -> Quad(buildOdtBytes(context, report, isShort), "odt",
            "application/vnd.oasis.opendocument.text", "OpenDocument")
        else -> return false
    }
    val file = File(dir, "ai_report_${safeTitle}_$ts.$ext")
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    when (action) {
        ReportExportAction.SHARE -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AI Report - ${report.title}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share AI Report ($formatLabel)"))
        }
        ReportExportAction.VIEW -> {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
    return true
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
