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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
enum class ReportExportAction { SHARE, VIEW }

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
    action: ReportExportAction,
    aiSettings: Settings,
    repository: AnalysisRepository,
    onProgress: (Int, Int) -> Unit
): Boolean {
    val report = ReportStorage.getReport(context, reportId) ?: return false

    if (format == ReportExportFormat.JSON) {
        onProgress(0, 1)
        when (action) {
            ReportExportAction.SHARE -> shareReportAsJson(context, reportId)
            ReportExportAction.VIEW -> openReportAsJson(context, report)
        }
        onProgress(1, 1)
        return true
    }

    val html = when (detail) {
        ReportExportDetail.SHORT -> { onProgress(0, 1); buildShortHtml(report).also { onProgress(1, 1) } }
        ReportExportDetail.MEDIUM -> { onProgress(0, 1); convertReportToHtml(context, report, getAppVersion(context)).also { onProgress(1, 1) } }
        ReportExportDetail.COMPREHENSIVE -> buildComprehensiveHtml(context, report, aiSettings, repository, onProgress)
    }

    val safeTitle = report.title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    val detailTag = detail.name.lowercase()
    val baseName = "ai_report_${safeTitle}_${detailTag}_${pdfTimestamp()}"
    return when (format) {
        ReportExportFormat.HTML -> { dispatchHtml(context, html, "$baseName.html", report.title, action); true }
        ReportExportFormat.PDF -> { dispatchPdf(context, makeStaticForPdf(html), "$baseName.pdf", report.title, action); true }
        ReportExportFormat.JSON -> true // unreachable
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
private fun makeStaticForPdf(html: String): String {
    val override = """
        <style>
            /* Hide JS-controlled toggles + the duplicate "all together" layout. */
            .layout-toggle, .agent-buttons, .think-btn, .section-btn { display: none !important; }
            #layout-allTogether { display: none !important; }
            /* Force every JS-hidden region visible. */
            #layout-oneByOne { display: block !important; }
            .agent-result, .agent-result.active { display: block !important; }
            .think-content { display: block !important; }
            .section-content, #section-prompt, #section-costs { display: block !important; }
        </style>
    """.trimIndent()
    val idx = html.indexOf("</head>", ignoreCase = true)
    return if (idx >= 0) html.substring(0, idx) + override + html.substring(idx) else html + override
}

/** Original entry point — kept for backwards compatibility with any caller that still
 *  asks for the comprehensive PDF directly. */
suspend fun shareReportAsPdf(
    context: Context,
    reportId: String,
    aiSettings: Settings,
    repository: AnalysisRepository,
    onProgress: (Int, Int) -> Unit
): Boolean = shareReportAsExport(context, reportId, ReportExportFormat.PDF, ReportExportDetail.COMPREHENSIVE, ReportExportAction.SHARE, aiSettings, repository, onProgress)

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
        .prompt { white-space: pre-wrap; word-break: break-word; }
        .err { color: #b00020; }
    """.trimIndent())
    sb.append("</style></head><body>")
    sb.append("<h1>").append(esc(report.title)).append("</h1>")
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

private suspend fun dispatchPdf(context: Context, html: String, fileName: String, reportTitle: String, action: ReportExportAction) {
    val output = File(exportsDir(context), fileName)
    withContext(Dispatchers.Main) { renderHtmlToPdfFile(context, html, output) }
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

private fun openReportAsJson(context: Context, report: Report) {
    val json = com.ai.data.createAppGson(prettyPrint = true).toJson(report)
    val file = File(exportsDir(context), "ai_report_${pdfTimestamp()}.json")
    file.writeText(json)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/json")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
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
        @page { margin: 14mm; }
        html { background: #ece6d6; }
        body {
            font-family: 'Georgia', 'Times New Roman', serif;
            font-size: 11pt; color: #2a2622; line-height: 1.5;
            margin: 0; padding: 0; background: #ece6d6;
        }
        .page {
            page-break-before: always;
            background: #fbfbf6;
            border: 1px solid #c8b894;
            box-shadow: 0 2px 6px rgba(0,0,0,0.08);
            max-width: 760px;
            margin: 18px auto;
            padding: 26px 32px 32px 32px;
            position: relative;
        }
        .page::before {
            content: ""; display: block; height: 6px;
            background: linear-gradient(90deg, #0b2c5a 0%, #1a73e8 50%, #c8b27a 100%);
            margin: -26px -32px 22px -32px;
        }
        h1 {
            font-family: 'Helvetica Neue', 'Helvetica', 'Arial', sans-serif;
            font-size: 26pt; margin: 0 0 10px 0; color: #0b2c5a;
            border-bottom: 2px solid #c8b27a; padding-bottom: 8px;
            letter-spacing: -0.2px;
        }
        h2 {
            font-family: 'Helvetica Neue', 'Helvetica', sans-serif;
            font-size: 17pt; margin: 18px 0 10px 0;
            color: #0b2c5a; padding: 0 0 4px 12px;
            border-left: 4px solid #c8b27a;
            border-bottom: 1px solid #d8d2bf;
        }
        h3 {
            font-family: 'Helvetica Neue', 'Helvetica', sans-serif;
            font-size: 12pt; margin: 16px 0 4px 0; color: #1a73e8;
            text-transform: uppercase; letter-spacing: 0.6px;
        }
        .meta {
            color: #888; font-size: 9pt; margin-bottom: 16px;
            font-style: italic; border-bottom: 1px dotted #c8b894; padding-bottom: 8px;
        }
        .toc {
            background: rgba(255, 252, 240, 0.7); padding: 14px 18px;
            border: 1px solid #c8b894;
            margin: 8px 0;
        }
        .toc a {
            display: block; color: #0b2c5a; text-decoration: none;
            padding: 5px 0; font-size: 11pt; border-bottom: 1px dotted #d8d2bf;
        }
        .toc a:last-child { border-bottom: none; }
        .toc a.sub { padding-left: 22px; color: #555; font-size: 10pt; }
        .code {
            font-family: 'Courier New', 'Menlo', 'Monaco', monospace; font-size: 9pt;
            background: #f4f0e0; padding: 10px 14px;
            white-space: pre-wrap; word-break: break-all;
            border-left: 3px solid #1a73e8;
            margin: 8px 0; color: #2a2622;
        }
        table {
            border-collapse: collapse; width: auto; max-width: 100%;
            margin: 12px auto;
            font-family: 'Helvetica Neue', 'Helvetica', sans-serif; font-size: 10pt;
            border: 1px solid #c8b894;
        }
        th, td { border: 1px solid #e0d8c2; padding: 6px 10px; text-align: left; vertical-align: top; }
        th {
            background: #0b2c5a; color: #fbfbf6; font-weight: 600;
            text-transform: uppercase; font-size: 9pt; letter-spacing: 0.5px;
        }
        tr:nth-child(even) td { background: rgba(232, 224, 200, 0.4); }
        td.num, th.num { text-align: right; font-family: 'Courier New', monospace; }
        .total td {
            font-weight: bold; background: #f0e8c8 !important;
            border-top: 2px solid #0b2c5a;
        }
        .intro {
            background: linear-gradient(180deg, #fff8e8 0%, #faf2d8 100%);
            border-left: 4px solid #1a73e8; padding: 12px 16px;
            margin: 10px 0; font-style: italic; color: #333;
        }
        .links { margin: 10px 0 18px 0; font-size: 10pt; }
        .links a {
            display: inline-block; color: #fbfbf6 !important; background: #0b2c5a;
            margin: 0 6px 4px 0; padding: 4px 12px;
            text-decoration: none; font-size: 9pt; font-family: 'Helvetica', sans-serif;
            letter-spacing: 0.3px;
        }
        .err { color: #b00020; font-weight: bold; }
        .about { color: #555; font-size: 10pt; line-height: 1.65; }
        ul, ol { margin: 4px 0 10px 22px; }
        p { margin: 6px 0; }
        .result h1 { font-size: 15pt; margin: 12px 0 6px 0; border-bottom: none; padding-bottom: 0; }
        .result h2 { font-size: 13pt; border-bottom: none; border-left: none; padding: 0; color: #0b2c5a; margin: 10px 0 4px 0; }
        .result h3 { font-size: 12pt; color: #0b2c5a; margin: 8px 0 4px 0; text-transform: none; letter-spacing: 0; }
        blockquote { border-left: 3px solid #c8b27a; padding: 4px 12px; margin: 8px 0; color: #555; font-style: italic; }
    """.trimIndent())
    sb.append("</style></head><body>")

    val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    // Cover — title + generation meta in its own centered .page card.
    sb.append("<div class='page' id='cover'>")
    sb.append("<h1>${esc(report.title)}</h1>")
    sb.append("<div class='meta'>Generated ").append(now).append(" · AI app v").append(esc(appVersion)).append("</div>")
    sb.append("</div>")

    // Index — wrapped in its own page card so it matches the look of the rest.
    sb.append("<div class='page' id='index'>")
    sb.append("<h2>Index</h2><div class='toc'>")
    sb.append("<a href='#prompt'>1. Prompt</a>")
    sb.append("<a href='#costs'>2. Cost summary</a>")
    sb.append("<a href='#models'>3. Models</a>")
    agents.forEachIndexed { i, a ->
        val anchor = "a-$i"
        val label = a.agentName.ifBlank { "${a.provider} / ${a.model}" }
        sb.append("<a class='sub' href='#$anchor'>3.${i + 1} ${esc(label)}</a>")
    }
    sb.append("<a href='#about'>4. About this report</a>")
    sb.append("</div></div>")

    // Prompt
    sb.append("<div class='page' id='prompt'>")
    sb.append("<h2>1. Prompt</h2>")
    sb.append("<div class='code'>").append(esc(report.prompt)).append("</div>")
    sb.append("</div>")

    // Cost summary
    sb.append("<div class='page' id='costs'>")
    sb.append("<h2>2. Cost summary</h2>")
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
    sb.append("</div>")  // close costs page

    // Models section header card — gives the index "3. Models" entry something to
    // anchor against and visually separates the per-model pages from the cost
    // summary above.
    sb.append("<div class='page' id='models'>")
    sb.append("<h2>3. Models</h2>")
    sb.append("<p>Per-model results — one card per (provider, model) combination ran for this report.</p>")
    sb.append("</div>")

    // Per-model pages
    agents.forEachIndexed { i, a ->
        val anchor = "a-$i"
        val provider = AppService.findById(a.provider)
        sb.append("<div class='page' id='$anchor'>")
        sb.append("<h2>3.${i + 1} ").append(esc(provider?.displayName ?: a.provider)).append(" / ").append(esc(a.model)).append("</h2>")

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
private suspend fun renderHtmlToPdfFile(context: Context, html: String, output: File) {
    val pageWidth = 1240
    val pageHeight = 1754
    val tag = "PdfExport"
    android.util.Log.i(tag, "renderHtmlToPdfFile: starting, html=${html.length} chars, out=${output.absolutePath}")
    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    val webView = WebView(context)
    webView.settings.javaScriptEnabled = false
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
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            android.util.Log.i(tag, "onPageFinished url=$url, contentHeight=${view.contentHeight}")
            // Chromium fires onPageFinished as soon as the document finishes
            // loading, but on a "warm" process the layout pass hasn't run yet —
            // contentHeight is still 0. Poll the main handler until chromium
            // reports a non-zero contentHeight (or up to ~2s) before rendering,
            // otherwise we end up snapshotting a blank surface.
            var attempts = 0
            fun maybeRender() {
                if (view.contentHeight > 0 || attempts >= 20) {
                    renderNow(view)
                } else {
                    attempts++
                    mainHandler.postDelayed({ maybeRender() }, 100)
                }
            }
            maybeRender()
        }
    }
    android.util.Log.i(tag, "loading HTML into WebView…")
    webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    try {
        // Safety timeout — if onPageFinished never fires (rare, but better to
        // surface an error than stick the dialog forever) we cap at 30s.
        kotlinx.coroutines.withTimeout(30_000) { done.await() }
    } finally {
        webView.stopLoading()
        webView.destroy()
    }
}
