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
import java.util.zip.ZipOutputStream

/**
 * "Export all" — build every supported export for the report and bundle
 * them into a single zip the user can hand off via the standard share
 * sheet. The bundle holds nine entries:
 *
 *   <title>_short.html      <title>_complete.html
 *   <title>_short.pdf       <title>_complete.pdf
 *   <title>_short.docx      <title>_complete.docx
 *   <title>_short.odt       <title>_complete.odt
 *   <title>_traces.zip      (only when the report has captured traces;
 *                             otherwise this entry is omitted)
 *
 * `onProgress(done, total)` ticks 9 times as each artifact is generated.
 * The PDF render leg is the slowest — each render boots a WebView, lays
 * out the entire HTML, and slices the bitmap into pages — so the two
 * PDFs alone usually account for most of the wall time.
 */
internal suspend fun bulkExportAndShare(
    context: Context,
    reportId: String,
    onProgress: (Int, Int) -> Unit
): Boolean {
    val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return false
    val total = 9
    var done = 0
    fun bump() { done++; onProgress(done, total) }
    onProgress(done, total)

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
        // Pre-render the HTML for both detail levels — used by the HTML
        // entries directly, and by the PDF render below (after the
        // makeStaticForPdf override).
        val shortHtml = withContext(Dispatchers.IO) { buildShortHtml(context, report) }
        val completeHtml = withContext(Dispatchers.IO) { convertReportToHtml(context, report, appVersion) }

        // 1. HTML Short
        File(workDir, "${safeTitle}_short.html").writeText(shortHtml); bump()
        // 2. HTML Complete
        File(workDir, "${safeTitle}_complete.html").writeText(completeHtml); bump()
        // 3. DOCX Short
        File(workDir, "${safeTitle}_short.docx").writeBytes(
            withContext(Dispatchers.IO) { buildDocxBytes(context, report, short = true) }
        ); bump()
        // 4. DOCX Complete
        File(workDir, "${safeTitle}_complete.docx").writeBytes(
            withContext(Dispatchers.IO) { buildDocxBytes(context, report, short = false) }
        ); bump()
        // 5. ODT Short
        File(workDir, "${safeTitle}_short.odt").writeBytes(
            withContext(Dispatchers.IO) { buildOdtBytes(context, report, short = true) }
        ); bump()
        // 6. ODT Complete
        File(workDir, "${safeTitle}_complete.odt").writeBytes(
            withContext(Dispatchers.IO) { buildOdtBytes(context, report, short = false) }
        ); bump()
        // 7. PDF Short — no TOC page
        val pdfShort = File(workDir, "${safeTitle}_short.pdf")
        withContext(Dispatchers.Main) {
            renderHtmlToPdfFile(context, makeStaticForPdf(shortHtml), pdfShort, withTocPage = false)
        }
        bump()
        // 8. PDF Complete — JS-injected TOC page with computed page numbers
        val pdfComplete = File(workDir, "${safeTitle}_complete.pdf")
        withContext(Dispatchers.Main) {
            renderHtmlToPdfFile(context, makeStaticForPdf(completeHtml), pdfComplete, withTocPage = true)
        }
        bump()
        // 9. JSON traces zip — skipped if the report has no captured
        // traces (the bundle still gets the eight document files).
        val traceZipBytes = buildJsonTraceZipBytes(context, report)
        if (traceZipBytes != null) {
            File(workDir, "${safeTitle}_traces.zip").writeBytes(traceZipBytes)
        }
        bump()

        // Master zip: every artifact in workDir at top level (no
        // intermediate directory), so the user just unzips and reads.
        val outDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val masterZip = File(outDir, "ai_report_${safeTitle}_all_$ts.zip")
        withContext(Dispatchers.IO) {
            ZipOutputStream(masterZip.outputStream().buffered()).use { zos ->
                workDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val entry = ZipEntry(f.name).apply { time = f.lastModified() }
                    zos.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", masterZip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AI Report (all formats) - ${report.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share AI Report (all formats)"))
        return true
    } finally {
        // Per-format files have been zipped; the staging dir is dead
        // weight. Master zip lives in the exports cache and is what the
        // share intent points at.
        runCatching { workDir.deleteRecursively() }
    }
}
