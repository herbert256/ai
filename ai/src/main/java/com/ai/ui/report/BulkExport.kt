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
 * sheet. The bundle holds ten entries:
 *
 *   <title>_short.html      <title>_complete.html
 *   <title>_short.pdf       <title>_complete.pdf
 *   <title>_short.docx      <title>_complete.docx
 *   <title>_short.odt       <title>_complete.odt
 *   <title>_zipped_html.zip (Complete report split into per-item
 *                             HTML files; navigable mini-site)
 *   <title>_traces.zip      (only when the report has captured traces;
 *                             otherwise this entry is omitted)
 *
 * `onProgress(done, total)` ticks 10 times as each artifact is
 * generated. The PDF render leg is the slowest — each render boots a
 * WebView, lays out the entire HTML, and slices the bitmap into pages
 * — so the two PDFs alone usually account for most of the wall time.
 */
internal suspend fun bulkExportAndShare(
    context: Context,
    reportId: String,
    onProgress: (Int, Int) -> Unit
): Boolean = withContext(Dispatchers.IO) {
    // Whole flow runs on IO so the file writes (DOCX/ODT can be MBs
    // when the report has many captured traces) and the gson parse
    // passes inside convertReportToHtml don't block the Main thread.
    // If Main is starved while we set up the bundle, the WebView render
    // that follows can't service its frame callbacks and the
    // CompletableDeferred inside renderHtmlToPdfFile times out.
    val report = ReportStorage.getReport(context, reportId) ?: return@withContext false
    val total = 10
    var done = 0
    suspend fun bump() {
        done++
        // Hop to Main so the AlertDialog's slot recomposes immediately;
        // posting from IO works but races with frame timing.
        withContext(Dispatchers.Main) { onProgress(done, total) }
    }
    withContext(Dispatchers.Main) { onProgress(done, total) }

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

    try {
        // Compute each large HTML lazily and drop it as soon as the
        // step that needed it is done — holding a multi-MB Complete
        // HTML across all 10 steps was triggering chromium memory
        // pressure when the WebView for PDF Short tried to allocate
        // (the symptom: hang at step 7, render times out at 30s).

        // 1. HTML Short
        run {
            val shortHtml = buildShortHtml(context, report)
            File(workDir, "${safeTitle}_short.html").writeText(shortHtml)
        }
        bump()
        // 2. HTML Complete
        run {
            val completeHtml = convertReportToHtml(context, report, appVersion)
            File(workDir, "${safeTitle}_complete.html").writeText(completeHtml)
        }
        bump()
        // 3. DOCX Short
        File(workDir, "${safeTitle}_short.docx").writeBytes(buildDocxBytes(context, report, short = true)); bump()
        // 4. DOCX Complete
        File(workDir, "${safeTitle}_complete.docx").writeBytes(buildDocxBytes(context, report, short = false)); bump()
        // 5. ODT Short
        File(workDir, "${safeTitle}_short.odt").writeBytes(buildOdtBytes(context, report, short = true)); bump()
        // 6. ODT Complete
        File(workDir, "${safeTitle}_complete.odt").writeBytes(buildOdtBytes(context, report, short = false)); bump()
        // Suggest GC before the PDF renders so chromium has a clean
        // heap to work with — paranoid but harmless.
        System.gc()
        // 7. PDF Short — no TOC page. WebView lives on Main, so hop.
        run {
            val pdfShort = File(workDir, "${safeTitle}_short.pdf")
            val staticHtml = makeStaticForPdf(buildShortHtml(context, report))
            withContext(Dispatchers.Main) {
                renderHtmlToPdfFile(context, staticHtml, pdfShort, withTocPage = false, timeoutMs = 120_000L)
            }
        }
        bump()
        System.gc()
        // 8. PDF Complete — JS-injected TOC page with computed page numbers
        run {
            val pdfComplete = File(workDir, "${safeTitle}_complete.pdf")
            val staticHtml = makeStaticForPdf(convertReportToHtml(context, report, appVersion))
            withContext(Dispatchers.Main) {
                renderHtmlToPdfFile(context, staticHtml, pdfComplete, withTocPage = true, timeoutMs = 120_000L)
            }
        }
        bump()
        // 9. Zipped HTML — Complete report broken into one HTML file
        // per item (navigable mini-site). Held in memory; the bytes
        // get streamed back out into the master zip below under a
        // zipped_html/ directory rather than being saved as a nested
        // .zip — no zip-in-zip.
        val zippedHtmlBytes = buildZippedHtmlBytes(context, report); bump()
        // 10. JSON traces zip — same treatment. Bytes are unpacked
        // into the master zip's traces/ directory. Null when the
        // report has no captured traces.
        val traceZipBytes = buildJsonTraceZipBytes(context, report); bump()

        // Master zip: every workDir file at top level + the two zip
        // payloads expanded into their own directories so the user
        // sees a flat-on-open mini-site instead of nested archives.
        val outDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val masterZip = File(outDir, "ai_report_${safeTitle}_all_$ts.zip")
        ZipOutputStream(masterZip.outputStream().buffered()).use { zos ->
            workDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                val entry = ZipEntry(f.name).apply { time = f.lastModified() }
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            unpackInto(zos, zippedHtmlBytes, "zipped_html/")
            traceZipBytes?.let { unpackInto(zos, it, "traces/") }
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
private fun unpackInto(zos: ZipOutputStream, innerZipBytes: ByteArray, prefix: String) {
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
