package com.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Extracts plain text from a [KnowledgeSourceType]. Each implementation
 * normalises whitespace and returns a single string ready for chunking.
 *
 * - [TextExtractor]      — read straight, normalise newlines.
 * - [MarkdownExtractor]  — same; the chunker treats markdown as text
 *                          but keeps paragraph boundaries intact.
 * - [PdfExtractor]       — PDFBox-Android extracts page-by-page; we
 *                          drop empty pages and join with double
 *                          newlines so paragraph chunking still works.
 * - [HtmlExtractor]      — Jsoup pulls visible body text, drops nav
 *                          / scripts / boilerplate.
 */
internal object KnowledgeExtractors {
    @Volatile private var pdfBoxInited = false

    fun extract(
        context: Context,
        type: KnowledgeSourceType,
        // For files: a SAF Uri the caller has read permission on. For
        // URLs: the URL string itself (origin field on KnowledgeSource).
        origin: String
    ): String {
        return when (type) {
            KnowledgeSourceType.TEXT -> readUriText(context, Uri.parse(origin))
            KnowledgeSourceType.MARKDOWN -> readUriText(context, Uri.parse(origin))
            KnowledgeSourceType.PDF -> readUriPdf(context, Uri.parse(origin))
            KnowledgeSourceType.DOCX -> readUriDocx(context, Uri.parse(origin))
            KnowledgeSourceType.ODT -> readUriOdt(context, Uri.parse(origin))
            KnowledgeSourceType.XLSX -> readUriXlsx(context, Uri.parse(origin))
            KnowledgeSourceType.ODS -> readUriOds(context, Uri.parse(origin))
            KnowledgeSourceType.IMAGE -> readUriImage(context, Uri.parse(origin))
            KnowledgeSourceType.URL -> fetchUrlAsText(origin)
        }
    }

    private fun readUriText(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Could not open $uri" }.bufferedReader().readText()
        }.normalised()
    }

    private fun readUriPdf(context: Context, uri: Uri): String {
        if (!pdfBoxInited) {
            // PDFBox-Android needs its resource loader bound to the
            // application context once per process; subsequent calls
            // are no-ops.
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInited = true
        }
        val text = context.contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Could not open $uri" }
            PDDocument.load(inp).use { doc ->
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.getText(doc)
            }
        }.normalised()
        if (text.isNotBlank()) return text
        // Fall back to OCR when PDFBox returned nothing — image-only
        // PDFs (scans, screenshot PDFs) have no text layer to strip.
        // PdfRenderer wants a ParcelFileDescriptor, not a stream;
        // re-open the SAF Uri in "r" mode.
        return runCatching { ocrPdf(context, uri) }
            .getOrDefault("")
            .normalised()
    }

    /** Render each page of [uri] to a Bitmap and run ML Kit Latin
     *  text recognition over it. Pages with no recognised text
     *  contribute nothing; results join with double newlines so
     *  paragraph chunking still works. Synchronous from the caller's
     *  perspective — internally bridges ML Kit's Task<Text> API to a
     *  suspend point so the calling IO dispatcher serialises page
     *  renders and bitmap recycling without overlap. */
    private fun ocrPdf(context: Context, uri: Uri): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                requireNotNull(pfd) { "Could not open $uri for OCR" }
                PdfRenderer(pfd).use { renderer ->
                    val sb = StringBuilder()
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            // 200 DPI ≈ 200/72 ≈ 2.78x scale; clamp at
                            // ~2400px on the long side to keep memory
                            // manageable for poster-size pages.
                            val scale = 2.78f.coerceAtMost(2400f / maxOf(page.width, page.height))
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val pageText = ocrBitmap(recognizer, bitmap)
                                if (pageText.isNotBlank()) {
                                    if (sb.isNotEmpty()) sb.append("\n\n")
                                    sb.append(pageText)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    sb.toString()
                }
            }
        } finally {
            // ML Kit's recognizer holds native resources; close so
            // the OS can release them between source imports.
            runCatching { recognizer.close() }
        }
    }

    /** Standalone image OCR. Loads the bitmap once via SAF, runs ML
     *  Kit, returns the recognised text. Used by JPG / PNG sources;
     *  PDF OCR uses the same [ocrBitmap] helper after rendering each
     *  page. Sub-sampling guard avoids OOM on huge phone photos. */
    private fun readUriImage(context: Context, uri: Uri): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            // Decode bounds first so we can downsample to ~2400px on
            // the long side — same cap PDF OCR uses, keeps memory
            // honest on 100-megapixel phone shots.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { inp ->
                requireNotNull(inp) { "Could not open $uri" }
                BitmapFactory.decodeStream(inp, null, bounds)
            }
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (maxDim / sampleSize > 2400) sampleSize *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri).use { inp ->
                requireNotNull(inp) { "Could not open $uri" }
                BitmapFactory.decodeStream(inp, null, opts)
            } ?: return ""
            return try {
                ocrBitmap(recognizer, bitmap).normalised()
            } finally {
                bitmap.recycle()
            }
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /** Bridge ML Kit's Task<Text> async API to a synchronous String
     *  via a suspend point. The calling IO dispatcher serialises
     *  overlapping work across pages or images so we never run two
     *  recognizer.process calls concurrently against the same
     *  client. */
    private fun ocrBitmap(recognizer: TextRecognizer, bitmap: Bitmap): String =
        runBlocking {
            suspendCancellableCoroutine<String> { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> cont.resume(result.text) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        }

    /** Walk a .docx (Office Open XML) zip, find word/document.xml, and
     *  pull the visible text out via a streaming XmlPullParser pass.
     *  Drops every other entry — styles, themes, headers, comments
     *  etc. don't carry knowledge content. <w:p> becomes a paragraph
     *  break (\n\n), <w:t> contributes its text, <w:tab> becomes \t. */
    private fun readUriDocx(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Could not open $uri" }
            ZipInputStream(inp).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        return@use parseOfficeXml(
                            zin,
                            paragraphLocalNames = setOf("p"),
                            textLocalNames = setOf("t"),
                            tabLocalNames = setOf("tab")
                        )
                    }
                    entry = zin.nextEntry
                }
                ""
            }
        }.normalised()
    }

    /** Same idea for ODT — the zip's content.xml carries the body
     *  text. <text:p> → paragraph break, <text:span>/<text:p> text
     *  contribution, <text:tab> → \t. */
    private fun readUriOdt(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Could not open $uri" }
            ZipInputStream(inp).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        return@use parseOfficeXml(
                            zin,
                            paragraphLocalNames = setOf("p", "h"),
                            textLocalNames = emptySet(), // ODT puts text directly under <text:p>
                            tabLocalNames = setOf("tab")
                        )
                    }
                    entry = zin.nextEntry
                }
                ""
            }
        }.normalised()
    }

    /** Streaming XML extractor shared between DOCX and ODT. Both
     *  formats wrap text inside the same paragraph / inline pattern;
     *  the only differences are the local element names and where
     *  the runs live (DOCX nests text in <w:t>, ODT lets text fall
     *  directly under <text:p>). XmlPullParser ignores namespaces by
     *  local-name comparison so we don't have to hardcode prefixes. */
    private fun parseOfficeXml(
        stream: InputStream,
        paragraphLocalNames: Set<String>,
        textLocalNames: Set<String>,
        tabLocalNames: Set<String>
    ): String {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")
        val sb = StringBuilder()
        var inText = textLocalNames.isEmpty() // ODT path: text everywhere; DOCX path: only inside <w:t>
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when {
                        name in paragraphLocalNames -> {
                            if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n")
                        }
                        name in tabLocalNames -> sb.append('\t')
                        textLocalNames.isNotEmpty() && name in textLocalNames -> inText = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (textLocalNames.isNotEmpty() && parser.name in textLocalNames) inText = false
                }
                XmlPullParser.TEXT -> {
                    if (inText) {
                        val chunk = parser.text
                        if (!chunk.isNullOrEmpty()) sb.append(chunk)
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    /** Walk an .xlsx (Office Open XML spreadsheet). Two-pass:
     *  1. Read xl/sharedStrings.xml into an indexed list — strings
     *     are deduped across the workbook; cells reference them by
     *     index rather than embedding.
     *  2. For each xl/worksheets/sheet*.xml, walk cells (<c>);
     *     resolve t="s" via the shared-strings table, fall through
     *     to inline values for everything else. Each row becomes a
     *     tab-separated line; sheets are separated by a blank line
     *     and a "[sheet N]" header so the chunker preserves
     *     boundaries. */
    private fun readUriXlsx(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Could not open $uri" }
            // First pass: pull every relevant entry into memory so we
            // can two-pass the workbook (shared strings → sheets) without
            // having to re-open the SAF stream (zip entries are
            // forward-only). Phone-sized spreadsheets are small enough
            // that holding them is cheap.
            val sharedStrings = mutableListOf<String>()
            val sheetXmls = mutableListOf<Pair<String, ByteArray>>()
            ZipInputStream(inp).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "xl/sharedStrings.xml") {
                        sharedStrings += parseXlsxSharedStrings(zin.readBytes().inputStream())
                    } else if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
                        sheetXmls += name to zin.readBytes()
                    }
                    entry = zin.nextEntry
                }
            }
            val sb = StringBuilder()
            sheetXmls.sortedBy { it.first }.forEachIndexed { idx, (_, bytes) ->
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("[sheet ").append(idx + 1).append("]\n")
                sb.append(parseXlsxSheet(bytes.inputStream(), sharedStrings))
            }
            sb.toString()
        }.normalised()
    }

    /** Returns the indexed list of strings in xl/sharedStrings.xml.
     *  A <si> may contain a single <t> or several <r><t>…</t></r>
     *  rich-text runs — flatten by concatenating every <t> under a
     *  given <si>. */
    private fun parseXlsxSharedStrings(stream: InputStream): List<String> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inT = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> { current.clear(); depth = 1 }
                        "t" -> if (depth > 0) inT = true
                    }
                }
                XmlPullParser.TEXT -> if (inT) current.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inT = false
                        "si" -> { out += current.toString(); current.clear(); depth = 0 }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** Walk a single sheet, emit one tab-separated row per <row>.
     *  Cell types: t="s" → resolve via [sharedStrings]; t="inlineStr"
     *  → text under <is><t>; everything else → text of <v> as-is. */
    private fun parseXlsxSheet(stream: InputStream, sharedStrings: List<String>): String {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")
        val sb = StringBuilder()
        val rowCells = mutableListOf<String>()
        var inV = false
        var inInlineT = false
        var cellType: String? = null
        val cellText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> rowCells.clear()
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        cellText.clear()
                    }
                    "v" -> inV = true
                    "t" -> if (cellType == "inlineStr") inInlineT = true
                }
                XmlPullParser.TEXT -> {
                    if (inV || inInlineT) cellText.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inV = false
                    "t" -> inInlineT = false
                    "c" -> {
                        val raw = cellText.toString().trim()
                        val resolved = if (cellType == "s") {
                            raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                        } else raw
                        rowCells += resolved
                    }
                    "row" -> {
                        // Drop fully-empty rows so chunking doesn't
                        // get drowned in blank lines from sparse sheets.
                        if (rowCells.any { it.isNotBlank() }) {
                            sb.append(rowCells.joinToString("\t")).append('\n')
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    /** ODS spreadsheet: content.xml carries one <table:table> per
     *  sheet. Cells live in <table:table-cell> with text in
     *  <text:p>. We treat <table:table-cell> as a tab separator and
     *  <table:table-row> as a newline. <table:table> boundaries get
     *  a blank line + "[sheet N]" header to mirror the XLSX path. */
    private fun readUriOds(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Could not open $uri" }
            ZipInputStream(inp).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == "content.xml") {
                        return@use parseOdsContent(zin.readBytes().inputStream())
                    }
                    entry = zin.nextEntry
                }
                ""
            }
        }.normalised()
    }

    private fun parseOdsContent(stream: InputStream): String {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")
        val sb = StringBuilder()
        var sheetIdx = 0
        val rowCells = mutableListOf<String>()
        val cellText = StringBuilder()
        var inP = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "table" -> {
                        sheetIdx++
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append("[sheet ").append(sheetIdx).append("]\n")
                    }
                    "table-row" -> rowCells.clear()
                    "table-cell" -> cellText.clear()
                    "p" -> inP = true
                }
                XmlPullParser.TEXT -> if (inP) cellText.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "p" -> {
                        inP = false
                        // Multiple <text:p> inside one cell → join
                        // them with a space so the value doesn't run
                        // on. Trim later when emitting the row.
                        if (cellText.isNotEmpty() && !cellText.endsWith(" ")) cellText.append(' ')
                    }
                    "table-cell" -> rowCells += cellText.toString().trim()
                    "table-row" -> {
                        if (rowCells.any { it.isNotBlank() }) {
                            sb.append(rowCells.joinToString("\t")).append('\n')
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString()
    }

    private fun fetchUrlAsText(url: String): String {
        // Jsoup handles the HTTP fetch + the HTML→text pass. We strip
        // <script> / <style> by default; Jsoup's text() walks visible
        // node text only.
        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; AI-Reports-RAG/1.0)")
            .timeout(20_000)
            .get()
        // Drop nav / footer / aside boilerplate when present —
        // doesn't catch every site but cleans up the easy cases.
        doc.select("script, style, noscript, nav, footer, aside, header").forEach { it.remove() }
        return doc.body()?.text().orEmpty().normalised()
    }

    private fun String.normalised(): String =
        replace("\r\n", "\n")
            .replace("\r", "\n")
            // Collapse runs of >2 newlines so paragraph splits stay
            // meaningful but giant whitespace gaps disappear.
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}

/**
 * Greedy paragraph-based chunker.
 *
 * 1. Split source on blank lines (paragraphs).
 * 2. Greedily merge until the running window approaches
 *    [maxCharsPerChunk] (~4 chars ≈ 1 token; 2048 chars ≈ 512 tokens).
 * 3. Carry the last [overlapChars] of each emitted chunk into the
 *    start of the next one so context survives a paragraph cut.
 *
 * Returns a list of chunk texts. Chunk metadata (id, sourceId,
 * ordinal) is added by the indexing service when it pairs each
 * chunk with its embedding.
 */
internal object KnowledgeChunker {
    fun chunk(
        text: String,
        maxCharsPerChunk: Int = 2048,
        overlapChars: Int = 200
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val paragraphs = text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        val current = StringBuilder()
        var carry = ""

        fun flush() {
            if (current.isNotEmpty()) {
                val emitted = current.toString().trim()
                if (emitted.isNotEmpty()) {
                    out.add(emitted)
                    carry = emitted.takeLast(overlapChars)
                }
                current.clear()
            }
        }

        for (p in paragraphs) {
            // Single paragraph already too big — split mid-paragraph
            // by hard char count so giant blobs (e.g. PDF tables
            // collapsed onto one line) still chunk.
            if (p.length > maxCharsPerChunk) {
                flush()
                var cursor = 0
                while (cursor < p.length) {
                    val end = (cursor + maxCharsPerChunk).coerceAtMost(p.length)
                    val piece = p.substring(cursor, end)
                    if (carry.isNotEmpty()) {
                        out.add("$carry\n\n$piece".trim())
                    } else {
                        out.add(piece.trim())
                    }
                    carry = piece.takeLast(overlapChars)
                    cursor = end
                }
                continue
            }
            // Emitting this paragraph would push us past the cap →
            // flush what we have, prepend the overlap, then start a
            // fresh window with the new paragraph.
            if (current.length + p.length + 2 > maxCharsPerChunk) {
                flush()
                if (carry.isNotEmpty()) current.append(carry).append("\n\n")
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(p)
        }
        flush()
        return out
    }
}
