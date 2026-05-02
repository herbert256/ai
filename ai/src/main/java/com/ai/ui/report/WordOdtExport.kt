package com.ai.ui.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
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
// inline formatting (`**bold**`, `_italic_`, ``code``) is stripped to
// plain text, since spinning up real run-property handling for each
// schema would dwarf the value of this export path. Headings, bullets,
// and code-fence paragraphs survive.

internal enum class DocBlockKind { HEADING, PARAGRAPH, BULLET, CODE }
internal data class DocBlock(val kind: DocBlockKind, val text: String, val level: Int = 0)

private fun reportToBlocks(context: Context, report: Report): List<DocBlock> {
    val out = mutableListOf<DocBlock>()
    out += DocBlock(DocBlockKind.HEADING, report.title.ifBlank { "Untitled" }, 1)
    if (report.prompt.isNotBlank()) {
        out += DocBlock(DocBlockKind.HEADING, "Prompt", 2)
        report.prompt.split(Regex("\n\\s*\n")).forEach { para ->
            val t = para.trim()
            if (t.isNotEmpty()) out += DocBlock(DocBlockKind.PARAGRAPH, t)
        }
    }
    val agents = report.agents.filter { it.reportStatus != ReportStatus.PENDING && it.reportStatus != ReportStatus.STOPPED }
    if (agents.isNotEmpty()) {
        out += DocBlock(DocBlockKind.HEADING, "Results", 2)
        for (a in agents) {
            val provider = AppService.findById(a.provider)
            val label = "${provider?.displayName ?: a.provider} / ${a.model}"
            out += DocBlock(DocBlockKind.HEADING, label, 3)
            if (a.reportStatus == ReportStatus.ERROR && !a.errorMessage.isNullOrBlank()) {
                out += DocBlock(DocBlockKind.PARAGRAPH, "Error: ${a.errorMessage}")
            }
            if (!a.responseBody.isNullOrBlank()) {
                out += mdToBlocks(a.responseBody!!, headingBase = 4)
            }
        }
    }
    val secondaries = SecondaryResultStorage.listForReport(context, report.id)
    appendSecondarySection(out, secondaries.filter { it.kind == SecondaryKind.SUMMARIZE }, "Summaries")
    appendSecondarySection(out, secondaries.filter { it.kind == SecondaryKind.COMPARE }, "Compares")
    appendSecondarySection(out, secondaries.filter { it.kind == SecondaryKind.RERANK }, "Reranks")
    appendSecondarySection(out, secondaries.filter { it.kind == SecondaryKind.MODERATION }, "Moderations")
    return out
}

private fun appendSecondarySection(out: MutableList<DocBlock>, items: List<SecondaryResult>, heading: String) {
    if (items.isEmpty()) return
    out += DocBlock(DocBlockKind.HEADING, heading, 2)
    for (s in items) {
        val provider = AppService.findById(s.providerId)?.displayName ?: s.providerId
        out += DocBlock(DocBlockKind.HEADING, "$provider / ${s.model}", 3)
        if (s.errorMessage != null) out += DocBlock(DocBlockKind.PARAGRAPH, "Error: ${s.errorMessage}")
        if (!s.content.isNullOrBlank()) out += mdToBlocks(s.content, headingBase = 4)
    }
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

internal fun buildDocxBytes(context: Context, report: Report): ByteArray {
    val blocks = reportToBlocks(context, report)
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

internal fun buildOdtBytes(context: Context, report: Report): ByteArray {
    val blocks = reportToBlocks(context, report)
    val contentXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">""")
        append("""<office:automatic-styles>""")
        append("""<style:style style:name="CodeP" style:family="paragraph" style:parent-style-name="Standard"><style:text-properties style:font-name="Courier New"/></style:style>""")
        append("""</office:automatic-styles>""")
        append("<office:body><office:text>")
        // ODT lists need explicit <text:list> wrapping. Group consecutive
        // BULLET blocks into a single list element so LibreOffice doesn't
        // start a new list per item.
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
                    append("<text:list>")
                    while (i < blocks.size && blocks[i].kind == DocBlockKind.BULLET) {
                        append("<text:list-item><text:p>${escXml(blocks[i].text)}</text:p></text:list-item>")
                        i++
                    }
                    append("</text:list>")
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
    format: ReportExportFormat, action: ReportExportAction
): Boolean {
    val report = ReportStorage.getReport(context, reportId) ?: return false
    val safeTitle = report.title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    val ts = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
    val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
    val (bytes, ext, mime, formatLabel) = when (format) {
        ReportExportFormat.DOCX -> Quad(buildDocxBytes(context, report), "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "MS Word")
        ReportExportFormat.ODT -> Quad(buildOdtBytes(context, report), "odt",
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
