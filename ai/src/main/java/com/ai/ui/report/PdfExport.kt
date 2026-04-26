package com.ai.ui.report

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private const val REDACTED = "[REDACTED]"
private val SENSITIVE_HEADERS = setOf("authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie")
private val SENSITIVE_JSON_KEYS = setOf("api_key", "apikey", "authorization", "token", "access_token", "refresh_token", "password", "secret")

/**
 * Build a comprehensive HTML for the report and hand it to the Android print framework so
 * the user lands on the system "Save as PDF" sheet. Page-break-before:always CSS keeps each
 * model on its own page; the print framework honours that.
 *
 * Per-model: intro (from the 'intro' Internal prompt, cached via PromptCache for 48h),
 * MD-rendered result, redacted request/response JSON + headers, optional links.
 * Document head: index, prompt, cost table. Document tail: about-this-app block.
 *
 * `onProgress(done, total)` advances as the per-model intro fetches complete, so the caller
 * can show a progress dialog. Once intros are ready and the WebView's loaded the HTML,
 * PrintManager.print(...) opens the system save sheet.
 */
suspend fun shareReportAsPdf(
    context: Context,
    reportId: String,
    aiSettings: Settings,
    repository: AnalysisRepository,
    onProgress: (Int, Int) -> Unit
): Boolean {
    val report = ReportStorage.getReport(context, reportId) ?: return false
    val traces = ApiTracer.getTraceFilesForReport(reportId)
        .mapNotNull { ApiTracer.readTraceFile(it.filename) }

    val successfulAgents = report.agents.filter {
        it.reportStatus != ReportStatus.PENDING && it.reportStatus != ReportStatus.STOPPED
    }
    val uniqueModels = successfulAgents
        .map { (it.provider to it.model) }
        .distinct()

    val totalSteps = uniqueModels.size + 1
    val intros = mutableMapOf<String, String?>()

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
            resp.analysis?.also { PromptCache.put(cacheKey, it) }
        } catch (_: Exception) { null }
    }
    onProgress(uniqueModels.size, totalSteps)

    val html = buildPdfHtml(context, report, traces, intros, getAppVersion(context))

    val jobName = "AI Report - ${report.title.ifBlank { "Untitled" }} - ${pdfTimestamp()}"
    withContext(Dispatchers.Main) { openPrintDialog(context, html, jobName) }
    onProgress(totalSteps, totalSteps)
    return true
}

// ===== HTML composition =====

private fun buildPdfHtml(
    context: Context,
    report: Report,
    traces: List<ApiTrace>,
    intros: Map<String, String?>,
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

// Hold the WebView until the system print spooler is done with the adapter, otherwise the
// adapter starts driving a GC'd view and the print job fails silently.
private val webViewKeepalive = mutableListOf<WebView>()

private suspend fun openPrintDialog(context: Context, html: String, jobName: String): Unit =
    suspendCancellableCoroutine { cont ->
        val webView = WebView(context.applicationContext)
        webViewKeepalive += webView
        webView.settings.javaScriptEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                try {
                    val attrs = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build()
                    val adapter = view.createPrintDocumentAdapter(jobName)
                    val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    pm.print(jobName, adapter, attrs)
                    if (cont.isActive) cont.resume(Unit)
                } catch (e: Exception) {
                    webViewKeepalive.remove(webView)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
