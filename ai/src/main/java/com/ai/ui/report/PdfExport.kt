package com.ai.ui.report

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import com.ai.data.AnalysisRepository
import com.ai.data.ApiTrace
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.data.PromptCache
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class IntroCostRow(
    val introducedFor: String,
    val runProvider: String,
    val runModel: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cost: Double
)

enum class ReportExportFormat { HTML, PDF, JSON }
enum class ReportExportDetail { SHORT, MEDIUM, COMPREHENSIVE }

private const val REDACTED = "[REDACTED]"
private val SENSITIVE_HEADERS = setOf("authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie")
private val SENSITIVE_JSON_KEYS = setOf("api_key", "apikey", "authorization", "token", "access_token", "refresh_token", "password", "secret")

/**
 * Top-level dispatcher: build the right document for (format × detail) and hand it to
 * Android's standard share sheet. JSON ignores the detail level. PDF and HTML support
 * SHORT (title + prompt + per-model results), MEDIUM (current rich HTML report), or
 * COMPREHENSIVE (index + prompt + cost table + per-model: intro / MD result / redacted
 * request+response JSON+headers / links + about footer).
 *
 * `onProgress(done, total)` advances during the per-model intro fetches that the
 * comprehensive variants kick off. Other variants report 0/1 and 1/1 only.
 */
suspend fun shareReportAsExport(
    context: Context,
    reportId: String,
    format: ReportExportFormat,
    detail: ReportExportDetail,
    aiSettings: Settings,
    repository: AnalysisRepository,
    onProgress: (Int, Int) -> Unit
): Boolean {
    val report = ReportStorage.getReport(context, reportId) ?: return false

    if (format == ReportExportFormat.JSON) {
        onProgress(0, 1)
        shareReportAsJson(context, reportId)
        onProgress(1, 1)
        return true
    }

    val html = when (detail) {
        ReportExportDetail.SHORT -> { onProgress(0, 1); buildShortHtml(report).also { onProgress(1, 1) } }
        ReportExportDetail.MEDIUM -> { onProgress(0, 1); convertReportToHtml(context, report, getAppVersion(context)).also { onProgress(1, 1) } }
        ReportExportDetail.COMPREHENSIVE -> buildComprehensiveHtml(context, report, aiSettings, repository, onProgress)
    }

    val safeTitle = report.title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    val baseName = "ai_report_${safeTitle}_${pdfTimestamp()}"
    return when (format) {
        ReportExportFormat.HTML -> { shareHtmlFile(context, html, "$baseName.html", report.title); true }
        ReportExportFormat.PDF -> { sharePdfFromHtml(context, html, "$baseName.pdf", report.title); true }
        ReportExportFormat.JSON -> true // unreachable
    }
}

/** Original entry point — kept for backwards compatibility with any caller that still
 *  asks for the comprehensive PDF directly. */
suspend fun shareReportAsPdf(
    context: Context,
    reportId: String,
    aiSettings: Settings,
    repository: AnalysisRepository,
    onProgress: (Int, Int) -> Unit
): Boolean = shareReportAsExport(context, reportId, ReportExportFormat.PDF, ReportExportDetail.COMPREHENSIVE, aiSettings, repository, onProgress)

private suspend fun buildComprehensiveHtml(
    context: Context,
    report: Report,
    aiSettings: Settings,
    repository: AnalysisRepository,
    onProgress: (Int, Int) -> Unit
): String {
    val traces = ApiTracer.getTraceFilesForReport(report.id)
        .mapNotNull { ApiTracer.readTraceFile(it.filename) }

    val successfulAgents = report.agents.filter {
        it.reportStatus != ReportStatus.PENDING && it.reportStatus != ReportStatus.STOPPED
    }
    val uniqueModels = successfulAgents
        .map { (it.provider to it.model) }
        .distinct()

    val totalSteps = uniqueModels.size + 1
    val intros = mutableMapOf<String, String?>()
    val introCosts = mutableListOf<IntroCostRow>()

    val introPrompt = aiSettings.prompts.find { it.name.equals("intro", ignoreCase = true) }
        ?: aiSettings.prompts.find { it.name.lowercase().contains("intro") }
    val introAgent = introPrompt?.agentId?.let { aiSettings.getAgentById(it) }

    uniqueModels.forEachIndexed { i, (providerId, model) ->
        onProgress(i, totalSteps)
        val key = "$providerId::$model"
        val provider = AppService.findById(providerId) ?: run { intros[key] = null; return@forEachIndexed }
        if (introPrompt == null || introAgent == null) { intros[key] = null; return@forEachIndexed }
        val effective = introAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(introAgent),
            model = aiSettings.getEffectiveModelForAgent(introAgent)
        )
        val resolved = introPrompt.promptText
            .replace("@MODEL@", model)
            .replace("@PROVIDER@", provider.displayName)
            .replace("@AGENT@", introAgent.name)
        val cacheKey = PromptCache.keyFor(resolved, introAgent.id)
        val cached = PromptCache.get(cacheKey)
        if (cached != null) { intros[key] = cached; return@forEachIndexed }
        if (effective.apiKey.isBlank()) { intros[key] = null; return@forEachIndexed }
        intros[key] = try {
            val resp = withContext(Dispatchers.IO) {
                repository.analyzePlayerWithAgent(effective, resolved, aiSettings.resolveAgentParameters(introAgent))
            }
            // Live (non-cached) intro call — record its cost so it shows up in the PDF
            // cost table separately from the report's own agent calls.
            resp.tokenUsage?.let { tu ->
                val pricing = PricingCache.getPricing(context, effective.provider, effective.model)
                val cost = tu.apiCost ?: (tu.inputTokens * pricing.promptPrice + tu.outputTokens * pricing.completionPrice)
                introCosts += IntroCostRow(
                    introducedFor = "${provider.displayName} / $model",
                    runProvider = effective.provider.displayName,
                    runModel = effective.model,
                    inputTokens = tu.inputTokens.toLong(),
                    outputTokens = tu.outputTokens.toLong(),
                    cost = cost
                )
            }
            resp.analysis?.also { PromptCache.put(cacheKey, it) }
        } catch (_: Exception) { null }
    }
    onProgress(totalSteps, totalSteps)
    return buildPdfHtml(context, report, traces, intros, introCosts, getAppVersion(context))
}

// ===== Short HTML — title + prompt + per-model results =====

private fun buildShortHtml(report: Report): String {
    val agents = report.agents.filter { it.reportStatus != ReportStatus.PENDING && it.reportStatus != ReportStatus.STOPPED }
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><style>")
    sb.append("""
        body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 12pt; color: #1d1d1d; line-height: 1.5; margin: 18px; }
        h1 { font-size: 20pt; margin: 0 0 8px 0; }
        h2 { font-size: 14pt; color: #0b2c5a; border-bottom: 1px solid #cfcfcf; padding-bottom: 4px; margin: 18px 0 6px 0; }
        h3 { font-size: 12pt; color: #0b2c5a; margin: 12px 0 4px 0; }
        .prompt { font-family: 'Courier New', 'Menlo', monospace; font-size: 10pt; background: #f5f5f7; padding: 8px 10px; white-space: pre-wrap; word-break: break-word; border-left: 3px solid #b0c4de; }
        .err { color: #b00020; }
    """.trimIndent())
    sb.append("</style></head><body>")
    sb.append("<h1>").append(esc(report.title)).append("</h1>")
    sb.append("<h2>Prompt</h2><div class='prompt'>").append(esc(report.prompt)).append("</div>")
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
    }
    sb.append("</body></html>")
    return sb.toString()
}

// ===== Sharers =====

private fun shareHtmlFile(context: Context, html: String, fileName: String, reportTitle: String) {
    val file = File(context.cacheDir, fileName)
    file.writeText(html)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/html"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AI Report - $reportTitle")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share AI Report (HTML)"))
}

private suspend fun sharePdfFromHtml(context: Context, html: String, fileName: String, reportTitle: String) {
    val output = File(context.cacheDir, fileName)
    withContext(Dispatchers.Main) { renderHtmlToPdfFile(context, html, output) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AI Report - $reportTitle")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share AI Report (PDF)"))
}

// ===== HTML composition =====

private fun buildPdfHtml(
    context: Context,
    report: Report,
    traces: List<ApiTrace>,
    intros: Map<String, String?>,
    introCosts: List<IntroCostRow>,
    appVersion: String
): String {
    val agents = report.agents.filter { it.reportStatus != ReportStatus.PENDING && it.reportStatus != ReportStatus.STOPPED }
    val sb = StringBuilder(8 * 1024)
    sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><style>")
    sb.append("""
        body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 11pt; color: #1d1d1d; line-height: 1.45; margin: 0; padding: 0; }
        h1 { font-size: 22pt; margin: 0 0 6px 0; }
        h2 { font-size: 16pt; margin: 12px 0 6px 0; padding-bottom: 4px; border-bottom: 1px solid #cfcfcf; color: #0b2c5a; }
        h3 { font-size: 12pt; margin: 14px 0 4px 0; color: #1a73e8; }
        .meta { color: #666; font-size: 9pt; margin-bottom: 12px; }
        .page { page-break-before: always; }
        .toc a { display: block; color: #1a73e8; text-decoration: none; padding: 3px 0; font-size: 11pt; }
        .toc a.sub { padding-left: 14px; color: #444; }
        .code { font-family: 'Courier New', 'Menlo', monospace; font-size: 9pt; background: #f5f5f7; padding: 8px 10px; white-space: pre-wrap; word-break: break-all; border-left: 3px solid #b0c4de; margin: 6px 0; }
        table { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 10pt; }
        th, td { border: 1px solid #d0d0d0; padding: 5px 8px; text-align: left; vertical-align: top; }
        th { background: #efefef; }
        td.num, th.num { text-align: right; font-family: 'Courier New', monospace; }
        .total td { font-weight: bold; background: #fafafa; }
        .intro { background: #f0f6ff; border-left: 3px solid #1a73e8; padding: 8px 10px; margin: 8px 0; font-size: 11pt; color: #333; }
        .links { margin: 6px 0 14px 0; font-size: 10pt; }
        .links a { color: #1a73e8; margin-right: 14px; text-decoration: none; }
        .err { color: #b00020; }
        .about { color: #555; font-size: 10pt; }
        ul, ol { margin: 4px 0 8px 22px; }
        p { margin: 4px 0; }
        .result h1 { font-size: 14pt; margin: 10px 0 4px 0; }
        .result h2 { font-size: 13pt; border-bottom: none; padding-bottom: 0; color: #0b2c5a; margin: 8px 0 4px 0; }
        .result h3 { font-size: 12pt; color: #0b2c5a; margin: 8px 0 4px 0; }
    """.trimIndent())
    sb.append("</style></head><body>")

    val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    sb.append("<h1>${esc(report.title)}</h1>")
    sb.append("<div class='meta'>Generated ").append(now).append(" · AI app v").append(esc(appVersion)).append("</div>")

    // Index
    sb.append("<h2>Index</h2><div class='toc'>")
    sb.append("<a href='#prompt'>1. Prompt</a>")
    sb.append("<a href='#costs'>2. Cost summary</a>")
    sb.append("<a href='#models'>3. Model results</a>")
    agents.forEachIndexed { i, a ->
        val anchor = "a-$i"
        sb.append("<a class='sub' href='#$anchor'>${esc(a.agentName.ifBlank { "${a.provider} / ${a.model}" })}</a>")
    }
    sb.append("<a href='#about'>4. About this report</a>")
    sb.append("</div>")

    // Prompt
    sb.append("<h2 id='prompt' class='page'>1. Prompt</h2>")
    sb.append("<div class='code'>").append(esc(report.prompt)).append("</div>")

    // Cost summary
    sb.append("<h2 id='costs' class='page'>2. Cost summary</h2>")
    sb.append("<table>")
    sb.append("<tr><th>Agent</th><th>Provider</th><th>Model</th><th class='num'>In</th><th class='num'>Out</th><th class='num'>Cost (¢)</th></tr>")
    var totalIn = 0L; var totalOut = 0L; var totalCost = 0.0
    for (a in agents) {
        val provider = AppService.findById(a.provider)
        val tu = a.tokenUsage
        val pricing = provider?.let { PricingCache.getPricing(context, it, a.model) }
        val inT: Long = (tu?.inputTokens ?: 0).toLong()
        val outT: Long = (tu?.outputTokens ?: 0).toLong()
        val cost = a.cost ?: (if (tu != null && pricing != null) tu.inputTokens * pricing.promptPrice + tu.outputTokens * pricing.completionPrice else 0.0)
        totalIn += inT; totalOut += outT; totalCost += cost
        sb.append("<tr><td>").append(esc(a.agentName)).append("</td><td>")
            .append(esc(provider?.displayName ?: a.provider)).append("</td><td>")
            .append(esc(a.model)).append("</td><td class='num'>")
            .append(inT).append("</td><td class='num'>")
            .append(outT).append("</td><td class='num'>")
            .append("%.2f".format(cost * 100)).append("</td></tr>")
    }
    if (introCosts.isNotEmpty()) {
        sb.append("<tr><td colspan='6' style='background:#fafafa; font-style:italic; color:#444;'>")
            .append("Introductions (live calls — cached intros cost nothing)</td></tr>")
        for (ic in introCosts) {
            totalIn += ic.inputTokens; totalOut += ic.outputTokens; totalCost += ic.cost
            sb.append("<tr><td>Intro: ").append(esc(ic.introducedFor)).append("</td><td>")
                .append(esc(ic.runProvider)).append("</td><td>")
                .append(esc(ic.runModel)).append("</td><td class='num'>")
                .append(ic.inputTokens).append("</td><td class='num'>")
                .append(ic.outputTokens).append("</td><td class='num'>")
                .append("%.2f".format(ic.cost * 100)).append("</td></tr>")
        }
    }
    sb.append("<tr class='total'><td colspan='3'>Total</td><td class='num'>")
        .append(totalIn).append("</td><td class='num'>")
        .append(totalOut).append("</td><td class='num'>")
        .append("%.2f".format(totalCost * 100)).append("</td></tr></table>")

    // Per-model pages
    sb.append("<h2 id='models' class='page'>3. Model results</h2>")
    agents.forEachIndexed { i, a ->
        val anchor = "a-$i"
        val provider = AppService.findById(a.provider)
        sb.append("<div class='page' id='$anchor'>")
        sb.append("<h2>").append(esc(provider?.displayName ?: a.provider)).append(" / ").append(esc(a.model)).append("</h2>")

        // Intro
        val introKey = "${a.provider}::${a.model}"
        val intro = intros[introKey]
        if (!intro.isNullOrBlank()) {
            sb.append("<h3>Introduction</h3><div class='intro'>")
                .append(convertMarkdownToHtmlForExport(intro)).append("</div>")
        }

        // Links
        val links = mutableListOf<Pair<String, String>>()
        provider?.adminUrl?.takeIf { it.isNotBlank() }?.let { links += "Provider console" to it }
        provider?.openRouterName?.takeIf { it.isNotBlank() }?.let {
            links += "Model on OpenRouter" to "https://openrouter.ai/$it/${a.model}"
        }
        if (links.isNotEmpty()) {
            sb.append("<div class='links'>")
            links.forEach { (label, url) -> sb.append("<a href='").append(esc(url)).append("'>").append(esc(label)).append("</a>") }
            sb.append("</div>")
        }

        // Result (MD-rendered)
        sb.append("<h3>Result</h3>")
        if (a.reportStatus == ReportStatus.ERROR) {
            sb.append("<p class='err'>Error: ").append(esc(a.errorMessage ?: "unknown error")).append("</p>")
        }
        if (!a.responseBody.isNullOrBlank()) {
            sb.append("<div class='result'>").append(convertMarkdownToHtmlForExport(a.responseBody!!)).append("</div>")
        }

        // Match a captured trace (by model) to recover request payload + headers.
        val trace = traces.firstOrNull { it.model == a.model }
        sb.append("<h3>Request JSON</h3><div class='code'>")
            .append(esc(redactJsonString(trace?.request?.body ?: a.requestBody) ?: "(not captured)")).append("</div>")
        sb.append("<h3>Response JSON</h3><div class='code'>")
            .append(esc(redactJsonString(trace?.response?.body ?: a.responseBody) ?: "(not captured)")).append("</div>")
        sb.append("<h3>Request headers</h3><div class='code'>")
            .append(esc(redactHeaders(trace?.request?.headers ?: parseHeaderText(a.requestHeaders)))).append("</div>")
        sb.append("<h3>Response headers</h3><div class='code'>")
            .append(esc(redactHeaders(trace?.response?.headers ?: parseHeaderText(a.responseHeaders)))).append("</div>")

        sb.append("</div>")
    }

    // About
    sb.append("<div class='page' id='about'>")
    sb.append("<h2>4. About this report</h2>")
    sb.append("<p class='about'>Generated by the AI app (Android, version ").append(esc(appVersion))
        .append(") on ").append(now).append(".</p>")
    sb.append("<p class='about'>The app fans a single prompt out to multiple AI agents in parallel. Each model answers independently — the per-model pages above show the response, the on-the-wire request and response JSON (with API keys / authorization tokens redacted), the HTTP headers, and a short introduction generated by the configured 'intro' Internal Prompt. Per-call token counts and cost estimates are summarised in the cost table; cost figures use the cached OpenRouter / LiteLLM pricing where available and are best-effort.</p>")
    sb.append("</div>")

    sb.append("</body></html>")
    return sb.toString()
}

// ===== Redaction helpers (PDF only — runtime traces stay unredacted) =====

private fun redactJsonString(text: String?): String? {
    if (text.isNullOrBlank()) return text
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

private fun redactHeaders(headers: Map<String, String>?): String {
    if (headers.isNullOrEmpty()) return "(none)"
    return headers.entries.joinToString("\n") { (name, value) ->
        val safe = if (name.lowercase(Locale.US) in SENSITIVE_HEADERS) REDACTED else value
        "$name: $safe"
    }
}

private fun parseHeaderText(text: String?): Map<String, String> {
    if (text.isNullOrBlank()) return emptyMap()
    return text.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()
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
private suspend fun renderHtmlToPdfFile(context: Context, html: String, output: File): Unit =
    suspendCancellableCoroutine { cont ->
        val pageWidth = 1240
        val pageHeight = 1754
        val webView = WebView(context.applicationContext)
        webView.settings.javaScriptEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.post {
                    try {
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(pageWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        val totalHeight = view.measuredHeight.coerceAtLeast(pageHeight)
                        view.layout(0, 0, pageWidth, totalHeight)

                        val pdf = PdfDocument()
                        var rendered = 0
                        var pageNum = 1
                        while (rendered < totalHeight) {
                            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                            val page = pdf.startPage(pageInfo)
                            val canvas = page.canvas
                            canvas.drawColor(AndroidColor.WHITE)
                            canvas.save()
                            canvas.translate(0f, -rendered.toFloat())
                            view.draw(canvas)
                            canvas.restore()
                            pdf.finishPage(page)
                            rendered += pageHeight
                            pageNum++
                        }
                        if (output.exists()) output.delete()
                        FileOutputStream(output).use { pdf.writeTo(it) }
                        pdf.close()
                        if (cont.isActive) cont.resume(Unit)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
