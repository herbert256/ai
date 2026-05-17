package com.ai.ui.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ai.data.ReportStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * "Export all" — build every supported export for the report and bundle
 * them into a single zip the user can hand off via the standard share
 * sheet.
 *
 * Layout depends on whether the report has more than one language view
 * and on the [language] selector:
 *
 * - `null` (All languages) AND >1 language view — every language slice
 *   from [buildLanguageViews] gets its own top-level directory:
 *     original/docs/<title>_short.html  …  original/docs/<title>_complete.odt
 *     original/html/                    (per-language Zipped HTML)
 *     dutch/docs/…                      german/docs/…
 *     json/                             (language-agnostic trace bundle)
 * - `null` AND only 1 language view (the common no-translations case)
 *   collapses to the same flat layout the explicit One-language modes
 *   produce; the wrapping `original/` dir is dropped.
 * - `""` or non-empty (One language) — flat layout for that single
 *   language:
 *     docs/<title>_short.html  …  docs/<title>_complete.odt
 *     html/                           (Zipped HTML for that language)
 *     json/                           (trace bundle, unchanged)
 *
 * `onProgress(done, total)` ticks once per generated artifact. PDF is
 * the slowest leg — each render boots a WebView, lays out the entire
 * HTML, and slices the bitmap into pages — so PDFs dominate the wall
 * time, multiplied by the language count in All-languages mode.
 */
internal suspend fun bulkExportAndShare(
    context: Context,
    reportId: String,
    /** Language filter — see [ExportLanguage]. [ExportLanguage.All]
     *  with >1 translation triggers the per-language top-level dir
     *  layout; any single-language selector produces the flat
     *  `docs/` + `html/` + `json/` bundle. */
    language: ExportLanguage,
    onProgress: (Int, Int) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    // Whole flow runs on IO so the file writes (DOCX/ODT can be MBs
    // when the report has many captured traces) and the gson parse
    // passes inside convertReportToHtml don't block the Main thread.
    // If Main is starved while we set up the bundle, the WebView render
    // that follows can't service its frame callbacks and the
    // CompletableDeferred inside renderHtmlToPdfFile times out.
    val report = ReportStorage.getReport(context, reportId) ?: return@withContext false

    val safeTitle = report.title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    val ts = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
    val workDir = File(context.cacheDir, "export_all_$ts").also {
        // Wipe any leftovers from a prior failed run; otherwise stale
        // files would land in the bundle alongside fresh ones.
        if (it.exists()) it.deleteRecursively()
        it.mkdirs()
    }
    val appVersion = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    val baseData = buildHtmlReportData(context, report)
    val allViews = buildLanguageViews(baseData)
    val viewsToRender: List<HtmlLanguageView> = when (val key = language.matchingKey()) {
        null -> allViews
        else -> allViews.filter { it.key == key }.ifEmpty { allViews.take(1) }
    }
    // Per-language top-level dirs only when there's actually >1
    // language. The common case (a report with no translations) keeps
    // the pre-refactor flat `docs/` + `html/` + `json/` layout instead
    // of pointlessly wrapping the single Original view in `original/`.
    val perLanguageDirs = (language == ExportLanguage.All && viewsToRender.size > 1)
    // 9 artifacts per language (2 HTML + 2 DOCX + 2 ODT + 2 PDF +
    // 1 zipped HTML) plus a single trace bundle.
    val total = viewsToRender.size * 9 + 1
    var done = 0
    suspend fun bump() {
        done++
        // Hop to Main so the AlertDialog's slot recomposes immediately;
        // posting from IO works but races with frame timing.
        withContext(Dispatchers.Main) { onProgress(done, total) }
    }
    withContext(Dispatchers.Main) { onProgress(done, total) }

    try {
        // Per-language zipped HTML, keyed by lv.key — emitted into the
        // master zip below either at `<langKey>/html/` (all mode) or
        // `html/` (one-language mode).
        val zippedHtmlPerLang = LinkedHashMap<String, ByteArray>()
        viewsToRender.forEach { lv ->
            val langDir = if (perLanguageDirs) {
                File(workDir, lv.key).also { it.mkdirs() }
            } else workDir
            val data = lv.data

            // HTML Short / Complete
            File(langDir, "${safeTitle}_short.html").writeText(buildShortHtmlFromData(data)); bump()
            File(langDir, "${safeTitle}_complete.html")
                .writeText(convertReportToHtmlFromData(data, appVersion)); bump()
            // DOCX Short / Complete
            File(langDir, "${safeTitle}_short.docx")
                .writeBytes(buildDocxBytesFromData(data, short = true)); bump()
            File(langDir, "${safeTitle}_complete.docx")
                .writeBytes(buildDocxBytesFromData(data, short = false)); bump()
            // ODT Short / Complete
            File(langDir, "${safeTitle}_short.odt")
                .writeBytes(buildOdtBytesFromData(data, short = true)); bump()
            File(langDir, "${safeTitle}_complete.odt")
                .writeBytes(buildOdtBytesFromData(data, short = false)); bump()
            // Suggest GC before the PDF renders so chromium has a clean
            // heap to work with — paranoid but harmless.
            System.gc()
            // PDF Short — no TOC page. WebView lives on Main, so hop.
            run {
                val pdfShort = File(langDir, "${safeTitle}_short.pdf")
                val staticHtml = makeStaticForPdf(buildShortHtmlFromData(data))
                withContext(Dispatchers.Main) {
                    renderHtmlToPdfFile(context, staticHtml, pdfShort, withTocPage = false, timeoutMs = 120_000L)
                }
            }
            bump()
            System.gc()
            // PDF Complete — JS-injected TOC page with computed page numbers
            run {
                val pdfComplete = File(langDir, "${safeTitle}_complete.pdf")
                val staticHtml = makeStaticForPdf(convertReportToHtmlFromData(data, appVersion))
                withContext(Dispatchers.Main) {
                    renderHtmlToPdfFile(context, staticHtml, pdfComplete, withTocPage = true, timeoutMs = 120_000L)
                }
            }
            bump()
            // Per-language Zipped HTML — filter buildLanguageViews to
            // this single language inside the sub-zip too.
            val zhLang: ExportLanguage = if (lv.key == LangTab.ORIGINAL_KEY) ExportLanguage.Original
                                         else ExportLanguage.Single(lv.displayName)
            zippedHtmlPerLang[lv.key] = buildZippedHtmlBytes(context, report, language = zhLang); bump()
        }

        // JSON traces zip — language-agnostic, one copy at the root.
        // Null when the report has no captured traces.
        val traceZipBytes = buildJsonTraceZipBytes(context, report); bump()

        val outDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val masterZip = File(outDir, "ai_report_${safeTitle}_all${language.fileTag()}_$ts.zip")
        ZipOutputStream(masterZip.outputStream().buffered()).use { zos ->
            if (perLanguageDirs) {
                viewsToRender.forEach { lv ->
                    val langDir = File(workDir, lv.key)
                    langDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                        val entry = ZipEntry("${lv.key}/docs/${f.name}").apply { time = f.lastModified() }
                        zos.putNextEntry(entry)
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    zippedHtmlPerLang[lv.key]?.let { unpackInto(zos, it, "${lv.key}/html/") }
                }
            } else {
                workDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val entry = ZipEntry("docs/${f.name}").apply { time = f.lastModified() }
                    zos.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                val onlyLang = viewsToRender.first()
                zippedHtmlPerLang[onlyLang.key]?.let { unpackInto(zos, it, "html/") }
            }
            traceZipBytes?.let { unpackInto(zos, it, "json/") }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", masterZip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AI Report (all formats) - ${report.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // startActivity from IO works, but Activity transition wants Main.
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, "Share AI Report (all formats)"))
        }
        true
    } finally {
        // Per-format files have been zipped; the staging dir is dead
        // weight. Master zip lives in the exports cache and is what the
        // share intent points at.
        runCatching { workDir.deleteRecursively() }
    }
}

/** Stream every entry of [innerZipBytes] into [zos] under [prefix].
 *  Skips directory-only entries (those are recreated implicitly by
 *  the file paths). Used by the bulk export to flatten the Zipped
 *  HTML and the trace bundle into the master zip rather than nesting
 *  archives. */
internal fun unpackInto(zos: ZipOutputStream, innerZipBytes: ByteArray, prefix: String) {
    ZipInputStream(innerZipBytes.inputStream()).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.isDirectory) { zis.closeEntry(); continue }
            val out = ZipEntry(prefix + entry.name).apply {
                if (entry.time != -1L) time = entry.time
            }
            zos.putNextEntry(out)
            zis.copyTo(zos)
            zos.closeEntry()
            zis.closeEntry()
        }
    }
}
