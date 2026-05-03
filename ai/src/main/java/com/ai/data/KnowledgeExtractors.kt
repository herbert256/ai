package com.ai.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import java.io.InputStream
import java.net.URL

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
        return context.contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Could not open $uri" }
            PDDocument.load(inp).use { doc ->
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.getText(doc)
            }
        }.normalised()
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
