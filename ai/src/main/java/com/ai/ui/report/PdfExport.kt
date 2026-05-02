package com.ai.ui.report

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import com.ai.data.AnalysisRepository
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ReportExportFormat(val displayName: String) {
    HTML("HTML"),
    PDF("PDF"),
    DOCX("MS Word"),
    ODT("OpenDocument"),
    JSON("JSON"),
    ZIPPED_HTML("Zipped HTML")
}
enum class ReportExportDetail { SHORT, COMPLETE }
enum class ReportExportAction { SHARE, VIEW }

internal const val REDACTED = "[REDACTED]"
internal val SENSITIVE_HEADERS = setOf("authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie")
internal val SENSITIVE_JSON_KEYS = setOf("api_key", "apikey", "authorization", "token", "access_token", "refresh_token", "password", "secret")

/**
 * Top-level dispatcher: build the right document for (format × detail) and hand it to
 * Android's standard share sheet. JSON ignores the detail level. HTML, PDF, DOCX,
 * and ODT all support SHORT (prompt + per-model results + meta items minus
 * rerank/translate, no traces, no costs, no index) or COMPLETE (the full Medium
 * HTML structure with prompt, costs, traces, index, every meta kind).
 *
 * `onProgress(done, total)` reports 0/1 → 1/1 for these paths.
 */
suspend fun shareReportAsExport(
    context: Context,
    reportId: String,
    format: ReportExportFormat,
    detail: ReportExportDetail,
    action: ReportExportAction,
    @Suppress("UNUSED_PARAMETER") aiSettings: Settings,
    @Suppress("UNUSED_PARAMETER") repository: AnalysisRepository,
    onProgress: (Int, Int) -> Unit
): Boolean {
    val report = ReportStorage.getReport(context, reportId) ?: return false

    if (format == ReportExportFormat.JSON) {
        onProgress(0, 1)
        // JSON export = zip of every trace file tagged with this
        // report (and the source report's traces too, when this is a
        // translated copy), organized into one directory per trace
        // category. Same code path for SHARE and VIEW — only the
        // intent type differs.
        shareReportAsJson(context, reportId, action)
        onProgress(1, 1)
        return true
    }

    if (format == ReportExportFormat.ZIPPED_HTML) {
        onProgress(0, 1)
        // Zipped HTML always emits the Complete content, broken into
        // one .html file per item with directories matching the view
        // picker. Detail picker is hidden for this format.
        shareReportAsZippedHtml(context, reportId, action)
        onProgress(1, 1)
        return true
    }

    if (format == ReportExportFormat.DOCX || format == ReportExportFormat.ODT) {
        onProgress(0, 1)
        val ok = shareReportAsDocxOrOdt(context, reportId, format, detail, action)
        onProgress(1, 1)
        return ok
    }

    onProgress(0, 1)
    val html = when (detail) {
        ReportExportDetail.SHORT -> buildShortHtml(context, report)
        ReportExportDetail.COMPLETE -> convertReportToHtml(context, report, getAppVersion(context))
    }
    onProgress(1, 1)

    val safeTitle = report.title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    val detailTag = detail.name.lowercase()
    val baseName = "ai_report_${safeTitle}_${detailTag}_${pdfTimestamp()}"
    return when (format) {
        ReportExportFormat.HTML -> { dispatchHtml(context, html, "$baseName.html", report.title, action); true }
        // Complete PDF gets a JS-injected TOC page at the top with real
        // page numbers; Short skips it.
        ReportExportFormat.PDF -> {
            dispatchPdf(context, makeStaticForPdf(html), "$baseName.pdf", report.title, action,
                withTocPage = detail == ReportExportDetail.COMPLETE)
            true
        }
        ReportExportFormat.JSON, ReportExportFormat.DOCX, ReportExportFormat.ODT,
        ReportExportFormat.ZIPPED_HTML -> true // handled above
    }
}

/**
 * The medium HTML export uses inline JavaScript for the agent tabs, layout
 * switcher, prompt/cost toggles, and Think-section reveal — fine in a browser,
 * dead weight in a PDF (we keep JS off for safety + the rendered DOM is what
 * gets snapshotted, so onclick handlers never run anyway). Inject a small
 * override stylesheet just before </head> that forces every JS-hidden element
 * visible and tucks the now-useless toggle buttons out of view, so the PDF
 * shows the full report top to bottom in static form.
 *
 * The HTML returned to disk for HTML exports is never touched — only the copy
 * we hand to the PDF renderer.
 */
internal fun makeStaticForPdf(html: String): String {
    // Reveal every JS-hidden region of the new Medium HTML so a static
    // bitmap snapshot shows the whole report top-to-bottom: the
    // view-picker / sub-toggles / per-item button rows are tab UI in a
    // browser, dead weight in a PDF. Each .view-block, .layout, item,
    // trace pane, etc. is forced visible. We also drop the picker
    // buttons themselves so they don't waste a row at the top of every
    // section. Heading anchors stay so the TOC page can link back.
    val override = """
        <style>
            /* Hide every interactive widget and selector row. */
            .view-picker, .layout-toggle, .agent-buttons,
            .think-btn, .section-btn,
            .cat-list, .trace-list, .trace-part-tabs,
            .view-btn, .layout-btn, .item-btn, .cat-btn, .trace-btn, .trace-part-btn { display: none !important; }
            /* Force every view-block (Reports/Summaries/Compares/Reranks/
               Moderations/Translations/Prompt/Costs/JSON) visible at once. */
            .view-block { display: block !important; }
            /* Reveal both layouts (one-by-one + all-together) and every
               per-item slot they contain so neither is hidden behind a
               JS toggle. */
            .layout, #layout-oneByOne, #layout-allTogether { display: block !important; }
            .agent-result, .agent-result.active { display: block !important; }
            .item-content, .item-content.active { display: block !important; }
            .think-content { display: block !important; }
            .section-content, #section-prompt, #section-costs { display: block !important; }
            /* JSON view: every category block, every per-trace pane,
               and every per-trace part body must be on the page so the
               PDF carries the full request/response history. */
            .cat-block { display: block !important; }
            .trace-pane { display: block !important; }
            .trace-part { display: block !important; }
            /* Print niceties. */
            body { background: #fff !important; color: #000 !important; }
            .container { max-width: none !important; }
            h1, h2, h3, h4 { page-break-after: avoid; }
            .toc-page { page-break-after: always; }
            .toc-list { font-family: -apple-system, sans-serif; font-size: 13px; line-height: 1.7; }
            .toc-entry { display: flex; align-items: baseline; gap: 8px; padding: 2px 0; }
            .toc-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .toc-page-num { font-family: monospace; font-weight: bold; }
            .toc-l1 { font-size: 16px; font-weight: bold; margin-top: 8px; }
            .toc-l2 { font-size: 14px; font-weight: bold; margin-top: 6px; }
            .toc-l3 { padding-left: 16px; }
            .toc-l4 { padding-left: 32px; color: #444; }
            .toc-l5 { padding-left: 48px; color: #555; font-size: 12px; }
            .toc-l6 { padding-left: 64px; color: #666; font-size: 12px; }
        </style>
    """.trimIndent()
    val idx = html.indexOf("</head>", ignoreCase = true)
    return if (idx >= 0) html.substring(0, idx) + override + html.substring(idx) else html + override
}

// ===== Short HTML — prompt, per-model results, meta items =====

/** Short HTML: prompt + per-model responses + meta items (Summaries,
 *  Compares, Moderations only — Rerank and Translate skipped per spec).
 *  No index, no cost table, no API trace dump. The same HTML is what
 *  buildShortHtml returns for HTML/PDF SHORT exports; DOCX/ODT have
 *  their own block-based equivalent in WordOdtExport. */
internal fun buildShortHtml(context: Context, report: Report): String {
    val agents = report.agents
        .filter { it.reportStatus != ReportStatus.PENDING && it.reportStatus != ReportStatus.STOPPED }
        .sortedBy { it.agentName.lowercase() }
    val secondary = com.ai.data.SecondaryResultStorage.listForReport(context, report.id)
    val summaries = secondary.filter { it.kind == com.ai.data.SecondaryKind.SUMMARIZE }
    val compares = secondary.filter { it.kind == com.ai.data.SecondaryKind.COMPARE }
    val moderations = secondary.filter { it.kind == com.ai.data.SecondaryKind.MODERATION }

    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><style>")
    sb.append("""
        body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 12pt; color: #1d1d1d; line-height: 1.5; margin: 18px; }
        h1 { font-size: 20pt; margin: 0 0 8px 0; }
        h2 { font-size: 14pt; color: #0b2c5a; border-bottom: 1px solid #cfcfcf; padding-bottom: 4px; margin: 18px 0 6px 0; }
        h3 { font-size: 12pt; color: #0b2c5a; margin: 12px 0 4px 0; }
        h4 { font-size: 11pt; color: #555; margin: 10px 0 4px 0; }
        .prompt { white-space: pre-wrap; word-break: break-word; }
        .err { color: #b00020; }
        .meta-ts { color: #888; font-weight: normal; font-size: 10pt; margin-left: 6px; }
    """.trimIndent())
    sb.append("</style></head><body>")
    sb.append("<h1>").append(esc(report.title)).append("</h1>")
    if (!report.rapportText.isNullOrBlank()) {
        sb.append("<div>").append(convertMarkdownToHtmlForExport(report.rapportText!!)).append("</div>")
    }

    sb.append("<h2>Prompt</h2><p class='prompt'>").append(esc(report.prompt)).append("</p>")

    sb.append("<h2>Results</h2>")
    for (a in agents) {
        val provider = AppService.findById(a.provider)
        sb.append("<h3>").append(esc(provider?.displayName ?: a.provider))
            .append(" / ").append(esc(a.model)).append("</h3>")
        if (a.reportStatus == ReportStatus.ERROR) {
            sb.append("<p class='err'>Error: ").append(esc(a.errorMessage ?: "unknown")).append("</p>")
        }
        if (!a.responseBody.isNullOrBlank()) {
            sb.append("<div>").append(convertMarkdownToHtmlForExport(a.responseBody!!)).append("</div>")
        }
        a.citations?.takeIf { it.isNotEmpty() }?.let { cites ->
            sb.append("<h4>Sources</h4><ol>")
            cites.forEach { url -> sb.append("<li><a href='").append(esc(url)).append("'>").append(esc(url)).append("</a></li>") }
            sb.append("</ol>")
        }
        a.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { qs ->
            sb.append("<h4>Related questions</h4><ol>")
            qs.forEach { q -> sb.append("<li>").append(esc(q)).append("</li>") }
            sb.append("</ol>")
        }
    }

    fun appendMeta(items: List<com.ai.data.SecondaryResult>, heading: String) {
        if (items.isEmpty()) return
        sb.append("<h2>").append(heading).append("</h2>")
        for (s in items) {
            val provDisplay = AppService.findById(s.providerId)?.displayName ?: s.providerId
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.timestamp))
            sb.append("<h3>").append(esc(provDisplay)).append(" / ").append(esc(s.model))
                .append("<span class='meta-ts'>").append(esc(ts)).append("</span></h3>")
            if (s.errorMessage != null) {
                sb.append("<p class='err'>Error: ").append(esc(s.errorMessage)).append("</p>")
            } else if (!s.content.isNullOrBlank()) {
                sb.append("<div>").append(convertMarkdownToHtmlForExport(s.content)).append("</div>")
            }
        }
    }
    appendMeta(summaries, "Summaries")
    appendMeta(compares, "Compares")
    appendMeta(moderations, "Moderations")

    if (!report.closeText.isNullOrBlank()) {
        sb.append("<div>").append(convertMarkdownToHtmlForExport(report.closeText!!)).append("</div>")
    }
    sb.append("</body></html>")
    return sb.toString()
}

// ===== Sharers =====

private fun exportsDir(context: Context): File =
    File(context.cacheDir, "exports").also { it.mkdirs() }

private fun dispatchHtml(context: Context, html: String, fileName: String, reportTitle: String, action: ReportExportAction) {
    val file = File(exportsDir(context), fileName)
    file.writeText(html)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    when (action) {
        ReportExportAction.SHARE -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AI Report - $reportTitle")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share AI Report (HTML)"))
        }
        ReportExportAction.VIEW -> {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}

private suspend fun dispatchPdf(context: Context, html: String, fileName: String, reportTitle: String, action: ReportExportAction, withTocPage: Boolean = false) {
    val output = File(exportsDir(context), fileName)
    withContext(Dispatchers.Main) { renderHtmlToPdfFile(context, html, output, withTocPage = withTocPage) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    when (action) {
        ReportExportAction.SHARE -> {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AI Report - $reportTitle")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share AI Report (PDF)"))
        }
        ReportExportAction.VIEW -> {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}

// ===== Redaction helpers (PDF only — runtime traces stay unredacted) =====

internal fun redactJsonString(text: String?): String? {
    // Return null (not "") for blank input so the call site's `?: "(not captured)"`
    // fallback fires instead of rendering an empty .code block.
    if (text.isNullOrBlank()) return null
    return try {
        @Suppress("DEPRECATION")
        val root = JsonParser().parse(text)
        redactJsonElement(root)
        com.ai.data.createAppGson(prettyPrint = true).toJson(root)
    } catch (_: Exception) { text }
}

private fun redactJsonElement(element: JsonElement?) {
    when {
        element == null || element.isJsonNull -> return
        element.isJsonObject -> {
            val obj: JsonObject = element.asJsonObject
            obj.entrySet().forEach { (key, value) ->
                if (key.lowercase(Locale.US) in SENSITIVE_JSON_KEYS) obj.add(key, JsonPrimitive(REDACTED))
                else redactJsonElement(value)
            }
        }
        element.isJsonArray -> element.asJsonArray.forEach { redactJsonElement(it) }
    }
}

internal fun redactHeaders(headers: Map<String, String>?): String {
    if (headers.isNullOrEmpty()) return "(none)"
    return headers.entries.joinToString("\n") { (name, value) ->
        val safe = if (name.lowercase(Locale.US) in SENSITIVE_HEADERS) REDACTED else value
        "$name: $safe"
    }
}

// ===== HTML escaping =====

private fun esc(s: String?): String {
    if (s == null) return ""
    val out = StringBuilder(s.length + 16)
    for (c in s) when (c) {
        '&' -> out.append("&amp;"); '<' -> out.append("&lt;"); '>' -> out.append("&gt;")
        '"' -> out.append("&quot;"); '\'' -> out.append("&#39;")
        else -> out.append(c)
    }
    return out.toString()
}

private fun pdfTimestamp(): String =
    SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())

private fun getAppVersion(context: Context): String = try {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    pi.versionName ?: "?"
} catch (_: Exception) { "?" }

// ===== HTML → PDF via WebView print adapter (must run on Main) =====

/**
 * Render the HTML into a multi-page PDF file we can hand to a standard share intent.
 *
 * The HTML is loaded into an off-screen WebView at A4 width (1240px ≈ A4 at 150 DPI). We
 * then measure the full content height with UNSPECIFIED, lay out, and slice the rendered
 * canvas into PDF pages of (1240 × 1754) px. CSS `page-break-before: always` isn't honoured
 * by this slicing path — content flows naturally and may split mid-section — but every line
 * remains readable and the per-model headings remain intact.
 *
 * Must be called from Main since WebView's measure / layout / draw require the UI thread.
 */
internal suspend fun renderHtmlToPdfFile(
    context: Context,
    html: String,
    output: File,
    withTocPage: Boolean = false,
    timeoutMs: Long = 30_000L
) {
    val pageWidth = 1240
    val pageHeight = 1754
    val tag = "PdfExport"
    val startNs = System.nanoTime()
    fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000
    android.util.Log.i(tag, "renderHtmlToPdfFile: starting, html=${html.length} chars, out=${output.absolutePath}, withToc=$withTocPage, thread=${Thread.currentThread().name}, timeoutMs=$timeoutMs")
    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    val webView = WebView(context)
    // JS is required when we ask the WebView to inject the TOC at the top
    // and pad it to a whole number of device-pixel pages so heading page
    // numbers come out correct. Otherwise stay JS-off — safer and the
    // existing slicing path doesn't need it.
    webView.settings.javaScriptEnabled = withTocPage
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    // Pre-measure + pre-layout so chromium has a real viewport when loading;
    // an unmeasured WebView produces zero-height content after load.
    webView.measure(
        View.MeasureSpec.makeMeasureSpec(pageWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY)
    )
    webView.layout(0, 0, pageWidth, pageHeight)
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    fun renderNow(view: WebView) {
        try {
            val cssDensity = view.resources.displayMetrics.density
            val contentPx = (view.contentHeight * cssDensity).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(pageWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val totalHeight = maxOf(view.measuredHeight, contentPx, pageHeight)
            android.util.Log.i(
                tag,
                "measured=${view.measuredWidth}x${view.measuredHeight}, contentHeightCss=${view.contentHeight}, contentPx=$contentPx, totalHeight=$totalHeight"
            )
            view.layout(0, 0, pageWidth, totalHeight)

            val bitmap = Bitmap.createBitmap(pageWidth, totalHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(AndroidColor.WHITE)
            view.draw(Canvas(bitmap))

            val pdf = PdfDocument()
            var rendered = 0
            var pageNum = 1
            while (rendered < totalHeight) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(AndroidColor.WHITE)
                val sliceH = minOf(pageHeight, totalHeight - rendered)
                val src = android.graphics.Rect(0, rendered, pageWidth, rendered + sliceH)
                val dst = android.graphics.Rect(0, 0, pageWidth, sliceH)
                canvas.drawBitmap(bitmap, src, dst, null)
                pdf.finishPage(page)
                rendered += pageHeight
                pageNum++
            }
            bitmap.recycle()
            if (output.exists()) output.delete()
            FileOutputStream(output).use { pdf.writeTo(it) }
            pdf.close()
            android.util.Log.i(tag, "rendered ${pageNum - 1} pages to ${output.length()} bytes")
            done.complete(Unit)
        } catch (e: Exception) {
            android.util.Log.e(tag, "PDF render failed", e)
            done.completeExceptionally(e)
        }
    }
    /** Inject a TOC page at the top of the document, pad it to a whole
     *  number of device-px pages, then write the correct page number
     *  into each TOC entry. After this completes the WebView's content
     *  is taller (by N×pageHeight px) and the slicing path produces a
     *  PDF whose first N pages are TOC and remaining pages match the
     *  original content. Sets `window.__tocReady=true` when done so the
     *  Kotlin side can stop polling and snapshot. */
    fun runTocAndRender(view: WebView) {
        val tocScript = """
            (function() {
                try {
                  var dpr = window.devicePixelRatio || 1;
                  var pageHeight = $pageHeight;
                  var headings = Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6'));
                  if (headings.length === 0) { window.__tocReady = true; return; }
                  var html = '<div class="toc-page"><h1 style="margin-top:0">Index</h1><div class="toc-list">';
                  for (var i = 0; i < headings.length; i++) {
                    var h = headings[i];
                    var lvl = parseInt(h.tagName.substring(1));
                    var text = (h.innerText || '').trim();
                    if (text.length > 140) text = text.substring(0, 140) + '…';
                    var safe = text.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
                    html += '<div class="toc-entry toc-l' + lvl + '">' +
                            '<span class="toc-text">' + safe + '</span>' +
                            '<span class="toc-page-num" data-toc-idx="' + i + '">…</span>' +
                            '</div>';
                  }
                  html += '</div></div>';
                  var mount = document.querySelector('.container') || document.body;
                  var wrap = document.createElement('div');
                  wrap.innerHTML = html;
                  var tocEl = wrap.firstChild;
                  mount.insertBefore(tocEl, mount.firstChild);
                  // Pad TOC to a whole number of device-px pages so all
                  // headings shift by exactly N pages and getBoundingClientRect
                  // values divide cleanly by pageHeight.
                  var actualPx = tocEl.getBoundingClientRect().height * dpr;
                  var paddedPx = Math.ceil(actualPx / pageHeight) * pageHeight;
                  tocEl.style.minHeight = (paddedPx / dpr) + 'px';
                  tocEl.style.boxSizing = 'border-box';
                  // Wait one frame for the min-height to apply, then write
                  // page numbers using post-padding heading positions.
                  requestAnimationFrame(function() {
                    var liveHeadings = Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6'))
                      .filter(function(h){ return !h.closest || !h.closest('.toc-page'); });
                    var pageEntries = document.querySelectorAll('.toc-page-num');
                    for (var i = 0; i < liveHeadings.length; i++) {
                      var rect = liveHeadings[i].getBoundingClientRect();
                      var yDevice = (rect.top + window.scrollY) * dpr;
                      var pageNum = Math.floor(yDevice / pageHeight) + 1;
                      if (pageEntries[i]) pageEntries[i].textContent = pageNum.toString();
                    }
                    window.__tocReady = true;
                  });
                } catch (e) {
                  // Don't block the export if TOC injection fails — proceed
                  // without page numbers rather than hanging the renderer.
                  window.__tocReady = true;
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(tocScript) {
            var attempts = 0
            fun checkReady() {
                view.evaluateJavascript("(function(){return window.__tocReady===true ? 'true':'false'})()") { result ->
                    if (result?.contains("true") == true || attempts >= 30) {
                        renderNow(view)
                    } else {
                        attempts++
                        mainHandler.postDelayed({ checkReady() }, 100)
                    }
                }
            }
            checkReady()
        }
    }
    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            android.util.Log.i(tag, "onPageStarted url=$url at +${elapsedMs()}ms")
        }
        override fun onPageFinished(view: WebView, url: String?) {
            android.util.Log.i(tag, "onPageFinished url=$url, contentHeight=${view.contentHeight} at +${elapsedMs()}ms")
            // Chromium fires onPageFinished as soon as the document finishes
            // loading, but on a "warm" process the layout pass hasn't run yet —
            // contentHeight is still 0. Poll the main handler until chromium
            // reports a non-zero contentHeight (or up to ~2s) before rendering,
            // otherwise we end up snapshotting a blank surface.
            var attempts = 0
            fun maybeRender() {
                if (view.contentHeight > 0 || attempts >= 20) {
                    if (withTocPage) runTocAndRender(view) else renderNow(view)
                } else {
                    attempts++
                    mainHandler.postDelayed({ maybeRender() }, 100)
                }
            }
            maybeRender()
        }
        override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
            android.util.Log.e(tag, "onReceivedError code=${error.errorCode} desc='${error.description}' url=${request.url} at +${elapsedMs()}ms")
        }
        override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
            android.util.Log.e(tag, "onReceivedHttpError status=${errorResponse.statusCode} url=${request.url} at +${elapsedMs()}ms")
        }
    }
    android.util.Log.i(tag, "loading HTML into WebView at +${elapsedMs()}ms")
    webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    try {
        // Safety timeout — if onPageFinished never fires (rare, but better to
        // surface an error than stick the dialog forever) we cap at the
        // configured limit. Bulk-export passes a higher value because the
        // Complete HTML can be many MB after redacted traces are inlined.
        kotlinx.coroutines.withTimeout(timeoutMs) { done.await() }
        android.util.Log.i(tag, "render complete at +${elapsedMs()}ms")
    } catch (e: Exception) {
        android.util.Log.e(tag, "renderHtmlToPdfFile failed at +${elapsedMs()}ms: ${e.javaClass.simpleName}: ${e.message}")
        throw e
    } finally {
        webView.stopLoading()
        webView.destroy()
    }
}
