package com.ai.ui.report

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ai.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== Data Classes =====

internal data class HtmlReportData(
    val title: String, val prompt: String, val timestamp: String,
    val rapportText: String?, val closeText: String?,
    val agents: List<HtmlAgentData>, val reportType: ReportType = ReportType.CLASSIC
)

internal data class HtmlAgentData(
    val agentId: String, val agentName: String, val provider: AppService?,
    val providerDisplay: String, val model: String,
    val responseText: String?, val errorMessage: String?,
    val citations: List<String>?, val searchResults: List<SearchResult>?,
    val relatedQuestions: List<String>?, val rawUsageJson: String?, val responseHeaders: String?,
    val inputTokens: Int? = null, val outputTokens: Int? = null,
    val inputCost: Double? = null, val outputCost: Double? = null, val durationMs: Long? = null
)

// ===== Public Functions =====

internal fun shareReportAsJson(context: android.content.Context, reportId: String) {
    val report = ReportStorage.getReport(context, reportId) ?: run { Toast.makeText(context, "Report not found", Toast.LENGTH_SHORT).show(); return }
    try {
        val json = createAppGson(prettyPrint = true).toJson(report)
        val file = writeToCache(context, "ai_report_${timestamp()}.json", json)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "Share AI Report (JSON)"))
    } catch (e: Exception) { Toast.makeText(context, "Error sharing: ${e.message}", Toast.LENGTH_SHORT).show() }
}

internal fun shareReportAsHtml(context: android.content.Context, reportId: String) {
    val report = ReportStorage.getReport(context, reportId) ?: run { Toast.makeText(context, "Report not found", Toast.LENGTH_SHORT).show(); return }
    try {
        val html = convertReportToHtml(context, report, getAppVersion(context))
        val file = writeToCache(context, "ai_report_${timestamp()}.html", html)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"; putExtra(Intent.EXTRA_SUBJECT, "AI Report - ${report.title}")
            putExtra(Intent.EXTRA_TEXT, "AI Report attached. Open the HTML file in a browser to view.")
            putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share AI Report (HTML)"))
    } catch (e: Exception) { Toast.makeText(context, "Error sharing: ${e.message}", Toast.LENGTH_SHORT).show() }
}

internal fun emailReportAsHtml(context: android.content.Context, reportId: String, emailAddress: String): Boolean {
    val report = ReportStorage.getReport(context, reportId) ?: return false
    return try {
        val html = convertReportToHtml(context, report, getAppVersion(context))
        val file = writeToCache(context, "ai_report_${timestamp()}.html", html)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(emailAddress))
            putExtra(Intent.EXTRA_SUBJECT, "AI Report - ${report.title}")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Email AI Report"))
        true
    } catch (e: Exception) { Toast.makeText(context, "Error emailing: ${e.message}", Toast.LENGTH_SHORT).show(); false }
}

internal fun openReportInChrome(context: android.content.Context, reportId: String) {
    val report = ReportStorage.getReport(context, reportId) ?: run { Toast.makeText(context, "Report not found", Toast.LENGTH_SHORT).show(); return }
    try {
        val html = convertReportToHtml(context, report, getAppVersion(context))
        val file = writeToCache(context, "ai_${timestamp()}.html", html)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "text/html"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(intent)
    } catch (e: Exception) { Toast.makeText(context, "Error opening: ${e.message}", Toast.LENGTH_SHORT).show() }
}

// ===== HTML Conversion =====

private fun convertReportToHtml(context: android.content.Context, report: Report, appVersion: String): String {
    val agents = report.agents
        .filter { it.reportStatus != ReportStatus.STOPPED && it.reportStatus != ReportStatus.PENDING }
        .sortedBy { it.agentName.lowercase() }
        .map { agent ->
            val provider = AppService.findById(agent.provider)
            val pricing = provider?.let { PricingCache.getPricing(context, it, agent.model) }
            val tu = agent.tokenUsage
            val inCost = if (tu != null && pricing != null) tu.inputTokens * pricing.promptPrice else null
            val outCost = if (tu != null && pricing != null) tu.outputTokens * pricing.completionPrice else null
            HtmlAgentData(
                agentId = agent.agentId, agentName = agent.agentName, provider = provider,
                providerDisplay = provider?.displayName ?: agent.provider, model = agent.model,
                responseText = agent.responseBody, errorMessage = agent.errorMessage?.takeIf { agent.reportStatus == ReportStatus.ERROR },
                citations = agent.citations, searchResults = agent.searchResults, relatedQuestions = agent.relatedQuestions,
                rawUsageJson = agent.rawUsageJson, responseHeaders = agent.responseHeaders,
                inputTokens = tu?.inputTokens, outputTokens = tu?.outputTokens, inputCost = inCost, outputCost = outCost, durationMs = agent.durationMs
            )
        }

    val data = HtmlReportData(
        title = report.title, prompt = report.prompt,
        timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(report.timestamp)),
        rapportText = report.rapportText, closeText = report.closeText,
        agents = agents, reportType = report.reportType
    )

    return renderHtmlReport(data, appVersion)
}

// ===== Unified HTML Report =====
// Emits BOTH layouts (One-by-one / All-together) and a top-level toggle to switch
// between them on the fly. The initial layout follows data.reportType so pre-existing
// reports saved as TABLE open in All-together by default.

private fun renderHtmlReport(data: HtmlReportData, appVersion: String): String {
    val defaultAllTogether = data.reportType == ReportType.TABLE
    val sb = StringBuilder()
    sb.append(htmlHead(data.title))
    sb.append("<body><div class='container'>")
    sb.append("<h1>${esc(data.title)}</h1>")
    data.rapportText?.let { sb.append("<div class='rapport'>${convertMarkdownToHtmlForExport(it)}</div>") }

    // Layout toggle
    sb.append("<div class='layout-toggle'>")
    sb.append("<button id='layout-btn-oneByOne' class='layout-btn${if (!defaultAllTogether) " active" else ""}' onclick=\"showLayout('oneByOne')\">One by one</button>")
    sb.append("<button id='layout-btn-allTogether' class='layout-btn${if (defaultAllTogether) " active" else ""}' onclick=\"showLayout('allTogether')\">All together</button>")
    sb.append("</div>")

    // ----- One-by-one layout -----
    sb.append("<div id='layout-oneByOne' class='layout'${if (defaultAllTogether) " style='display:none'" else ""}>")
    sb.append("<div class='agent-buttons'>")
    data.agents.forEachIndexed { i, a -> sb.append("<button class='agent-btn${if (i == 0) " active" else ""}' onclick=\"showAgent('${escId(a.agentId)}')\">${esc(a.agentName)}</button>") }
    sb.append("</div>")

    data.agents.forEachIndexed { i, a ->
        sb.append("<div id='agent-${escId(a.agentId)}' class='agent-result${if (i == 0) " active" else ""}'>")
        sb.append("<div class='agent-header'>${esc(a.providerDisplay)} - ${esc(a.model)}</div>")
        sb.append("<div class='report-content'>")
        if (a.errorMessage != null) sb.append("<div class='error'>Error: ${esc(a.errorMessage)}</div>")
        if (a.responseText != null) sb.append("<div class='agent-response'>${processThinkSections(a.responseText, a.agentId)}</div>")
        a.citations?.takeIf { it.isNotEmpty() }?.let { cites ->
            sb.append("<div class='sources-section'><div class='sources-label'>Sources</div>")
            cites.forEachIndexed { ci, url -> sb.append("<div class='source-item'>${ci + 1}. <a href='${esc(url)}'>${esc(url)}</a></div>") }
            sb.append("</div>")
        }
        a.searchResults?.takeIf { it.isNotEmpty() }?.let { results ->
            sb.append("<div class='search-results-section'><div class='search-results-label'>Search Results</div>")
            results.forEachIndexed { ri, r -> if (r.url != null) sb.append("<div class='search-result'>${ri + 1}. <a href='${esc(r.url)}' class='search-result-title'>${esc(r.name ?: r.url)}</a>${if (!r.snippet.isNullOrBlank()) "<div class='search-result-snippet'>${esc(r.snippet)}</div>" else ""}</div>") }
            sb.append("</div>")
        }
        a.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { qs ->
            sb.append("<div class='related-questions-section'><div class='related-questions-label'>Related Questions</div>")
            qs.forEachIndexed { qi, q -> sb.append("<div class='related-question'>${qi + 1}. ${esc(q)}</div>") }
            sb.append("</div>")
        }
        sb.append("</div></div>")
    }
    sb.append("</div>") // /#layout-oneByOne

    // ----- All-together layout -----
    sb.append("<div id='layout-allTogether' class='layout'${if (!defaultAllTogether) " style='display:none'" else ""}>")
    sb.append("<div class='table-grid'>")
    data.agents.forEach { a ->
        sb.append("<div class='table-card'>")
        sb.append("<div class='card-header'>${esc(a.providerDisplay)}</div>")
        sb.append("<div class='card-model'>${esc(a.model)}</div>")
        if (a.errorMessage != null) {
            sb.append("<div class='error'>Error: ${esc(a.errorMessage)}</div>")
        } else if (a.responseText != null) {
            val conclusion = extractTagContent(a.responseText, "conclusion")
            val motivation = extractTagContent(a.responseText, "motivation")
            if (conclusion != null) sb.append("<div class='card-conclusion'><div class='card-section-label' style='color:#4CAF50'>Conclusion</div>${convertMarkdownToHtmlForExport(conclusion)}</div>")
            if (motivation != null) sb.append("<div class='card-motivation'><div class='card-section-label' style='color:#FF9800'>Motivation</div>${convertMarkdownToHtmlForExport(motivation)}</div>")
            if (conclusion == null && motivation == null) sb.append("<div class='card-body'>${convertMarkdownToHtmlForExport(a.responseText)}</div>")
        }
        sb.append("</div>")
    }
    sb.append("</div>")
    sb.append("</div>") // /#layout-allTogether

    // Shared footer (prompt, costs, close text) is outside the layouts so it's always visible.
    appendPromptAndCosts(sb, data)
    data.closeText?.let { sb.append("<div class='close-text'>${convertMarkdownToHtmlForExport(it)}</div>") }
    sb.append("<div class='footer'>Generated by AI v$appVersion on ${data.timestamp}</div>")
    sb.append("</div>")
    sb.append(htmlScript())
    sb.append("</body></html>")
    return sb.toString()
}

// ===== HTML Helpers =====

private fun appendPromptAndCosts(sb: StringBuilder, data: HtmlReportData) {
    val hasPrompt = data.prompt.isNotBlank()
    val hasCosts = data.agents.any { it.inputTokens != null }
    if (!hasPrompt && !hasCosts) return

    sb.append("<div class='section-buttons'>")
    if (hasPrompt) sb.append("<button id='btn-prompt' class='section-btn' onclick=\"toggleSection('prompt')\">Prompt</button>")
    if (hasCosts) sb.append("<button id='btn-costs' class='section-btn' onclick=\"toggleSection('costs')\">Costs</button>")
    sb.append("</div>")

    if (hasPrompt) sb.append("<div id='section-prompt' class='section-content' style='display:none'><div class='prompt-section'><div class='prompt-label'>Prompt:</div><pre class='prompt-text'>${esc(data.prompt)}</pre></div></div>")

    if (hasCosts) {
        sb.append("<div id='section-costs' class='section-content' style='display:none'><div class='prompt-section'><div class='prompt-label'>Costs</div>")
        sb.append("<table class='cost-table'><tr><th>Provider</th><th>Model</th><th style='text-align:right'>Seconds</th><th style='text-align:right'>Input<br>tokens</th><th style='text-align:right'>Output<br>tokens</th><th style='text-align:right'>Input<br>cents</th><th style='text-align:right'>Output<br>cents</th><th style='text-align:right'>Total<br>cents</th></tr>")
        val sorted = data.agents.filter { it.inputCost != null }.sortedByDescending { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        var tIn = 0; var tOut = 0; var tInC = 0.0; var tOutC = 0.0
        sorted.forEach { a ->
            val ic = (a.inputCost ?: 0.0) * 100; val oc = (a.outputCost ?: 0.0) * 100
            tIn += a.inputTokens ?: 0; tOut += a.outputTokens ?: 0; tInC += ic; tOutC += oc
            val secs = a.durationMs?.let { "%.1f".format(it / 1000.0) } ?: ""
            sb.append("<tr><td>${esc(a.providerDisplay)}</td><td>${esc(a.model)}</td><td class='num'>$secs</td><td class='num'>${a.inputTokens ?: 0}</td><td class='num'>${a.outputTokens ?: 0}</td><td class='num'>${"%.2f".format(ic)}</td><td class='num'>${"%.2f".format(oc)}</td><td class='num'>${"%.2f".format(ic + oc)}</td></tr>")
        }
        sb.append("<tr class='total-row'><td colspan='3'>Total</td><td class='num'>$tIn</td><td class='num'>$tOut</td><td class='num'>${"%.2f".format(tInC)}</td><td class='num'>${"%.2f".format(tOutC)}</td><td class='num'>${"%.2f".format(tInC + tOutC)}</td></tr>")
        sb.append("</table></div></div>")
    }
}

private fun processThinkSections(text: String, agentId: String): String {
    val pattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    var idx = 0
    val result = pattern.replace(text) { m ->
        val content = esc(m.groupValues[1])
        val id = "${escId(agentId)}-${idx++}"
        """<button id="think-btn-$id" class="think-btn" onclick="toggleThink('$id')">Think</button><div id="think-$id" class="think-content">$content</div>"""
    }
    val parts = result.split(Regex("(<button.*?</button><div.*?</div>)"))
    return parts.mapIndexed { i, p -> if (i % 2 == 0) convertMarkdownToHtmlForExport(p) else p }.joinToString("")
}

internal fun convertMarkdownToHtmlForExport(markdown: String): String {
    var html = markdown.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n")
    html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL), "<pre><code>$1</code></pre>")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace("\n\n", "</p><p>").replace("\n", "<br>")
    html = html.replace(Regex("(<li>.*?</li>)+")) { "<ul>${it.value}</ul>" }
    html = html.replace(Regex("(<br>\\s*)+<br>"), "<br>")
        .replace(Regex("<br>\\s*(<h[234]>)"), "$1").replace(Regex("(</h[234]>)\\s*<br>"), "$1")
        .replace(Regex("<br>\\s*(<ul>)"), "$1").replace(Regex("(</ul>)\\s*<br>"), "$1")
        .replace(Regex("<br>\\s*(<pre>)"), "$1").replace(Regex("(</pre>)\\s*<br>"), "$1")
        .replace(Regex("<p>\\s*</p>"), "")
    if (html.isNotBlank()) html = "<p>$html</p>"
    return html
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun escId(s: String) = s.replace("'", "").replace("\"", "").replace("&", "").replace("<", "").replace(">", "")
private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
private fun getAppVersion(context: android.content.Context): String = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown" } catch (_: Exception) { "unknown" }

private fun writeToCache(context: android.content.Context, filename: String, content: String): File {
    val dir = File(context.cacheDir, "ai_analysis").also { it.mkdirs() }
    return File(dir, filename).also { it.writeText(content) }
}

// ===== HTML Template =====

private fun htmlHead(title: String) = """<!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>AI Report - ${esc(title)}</title>
<style>
body{background:#1a1a1a;color:#e0e0e0;font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:0;padding:16px}
.container{max-width:800px;margin:0 auto}
h1{color:#fff;font-size:24px;margin-bottom:16px}
.layout-toggle{display:flex;gap:8px;margin-bottom:16px}
.layout-btn{background:transparent;color:#6B9BFF;border:1px solid #555;border-radius:4px;padding:8px 16px;cursor:pointer;font-weight:bold;font-size:14px}
.layout-btn.active{background:#6B9BFF;color:#1a1a1a;border-color:#6B9BFF}
.agent-buttons{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:16px}
.agent-btn{background:transparent;color:#e0e0e0;border:1px solid #555;border-radius:16px;padding:4px 12px;cursor:pointer;font-size:13px}
.agent-btn.active{background:#8B5CF6;color:#fff;border-color:#8B5CF6}
.agent-result{display:none}.agent-result.active{display:block}
.agent-header{color:#6B9BFF;font-size:18px;font-weight:600;margin-bottom:12px}
.agent-response{line-height:1.6}
.error{color:#ff6b6b;padding:8px;background:#2a1a1a;border-radius:4px;margin-bottom:8px}
.think-btn{background:#333;color:#999;border:1px solid #555;border-radius:4px;padding:4px 12px;cursor:pointer;font-size:13px;margin:8px 0}
.think-content{display:none;background:#252525;border-left:3px solid #555;padding:12px;margin:8px 0;color:#999;font-size:14px;line-height:1.5;white-space:pre-wrap}
.section-buttons{margin-top:16px;display:flex;gap:8px}
.section-btn{background:transparent;color:#6B9BFF;border:1px solid #555;border-radius:4px;padding:8px 16px;cursor:pointer;font-weight:bold;font-size:14px}
.section-btn.active{background:#6B9BFF;color:#1a1a1a}
.section-content{display:none;margin-top:8px}
.prompt-section{background:#252525;border-radius:8px;padding:16px;margin-top:8px}
.prompt-label{color:#6B9BFF;font-weight:bold;margin-bottom:8px}
.prompt-text{white-space:pre-wrap;color:#ccc;font-size:14px;line-height:1.5}
.cost-table{width:100%;border-collapse:collapse;font-size:12px;margin-top:8px}
.cost-table th{color:#6B9BFF;text-align:left;padding:4px 8px;border-bottom:1px solid #444}
.cost-table td{padding:4px 8px;border-bottom:1px solid #333}
.cost-table .num{text-align:right;font-family:monospace}
.total-row td{color:#6B9BFF;font-weight:bold;border-top:2px solid #444}
.sources-section,.search-results-section,.related-questions-section{background:#252525;border-radius:8px;padding:16px;margin-top:16px}
.sources-label{color:#8B5CF6;font-weight:bold;font-size:16px;margin-bottom:12px}
.search-results-label{color:#FF9800;font-weight:bold;font-size:16px;margin-bottom:12px}
.related-questions-label{color:#4CAF50;font-weight:bold;font-size:16px;margin-bottom:12px}
a{color:#64B5F6}
.footer{color:#666;font-size:12px;margin-top:24px;text-align:center}
h2{color:#8BB8FF;font-size:20px}h3{color:#9FCFFF;font-size:17px}h4{color:#B0D0FF;font-size:15px}
strong{font-weight:bold}em{color:#ccc;font-style:italic}
code{background:#333;padding:2px 6px;border-radius:3px;font-family:monospace;font-size:13px}
pre code{display:block;background:#252525;padding:12px;border-radius:6px;overflow-x:auto;white-space:pre-wrap}
ul{padding-left:20px}li{margin:4px 0}
.table-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:16px;margin-bottom:16px}
.table-card{background:#252525;border-radius:8px;padding:16px;overflow:hidden}
.card-header{color:#6B9BFF;font-weight:600;font-size:14px}.card-model{color:#999;font-size:12px;margin-bottom:8px}
.card-section-label{font-weight:bold;font-size:14px;margin-bottom:4px}
.card-body,.card-conclusion,.card-motivation{font-size:14px;line-height:1.5}
.rapport{background:#1a2a1a;border-left:3px solid #4CAF50;padding:12px;margin-bottom:16px;font-size:14px}
.close-text{margin-top:16px;padding:12px;background:#252525;border-radius:8px}
.usage-label{color:#999;font-size:12px;margin-top:12px}.usage-json,.headers-text{font-size:11px;color:#888;background:#1a1a1a;padding:8px;border-radius:4px;overflow-x:auto}
</style></head>
"""

private fun htmlScript() = """
<script>
function showLayout(which){var a=document.getElementById('layout-oneByOne');var b=document.getElementById('layout-allTogether');var ba=document.getElementById('layout-btn-oneByOne');var bb=document.getElementById('layout-btn-allTogether');if(which==='oneByOne'){a.style.display='block';b.style.display='none';ba.classList.add('active');bb.classList.remove('active')}else{a.style.display='none';b.style.display='block';ba.classList.remove('active');bb.classList.add('active')}}
function showAgent(id){document.querySelectorAll('.agent-result').forEach(e=>e.classList.remove('active'));document.querySelectorAll('.agent-btn').forEach(b=>b.classList.remove('active'));var el=document.getElementById('agent-'+id);if(el)el.classList.add('active');event.target.classList.add('active')}
function toggleThink(id){var el=document.getElementById('think-'+id);var btn=document.getElementById('think-btn-'+id);if(el.style.display==='block'){el.style.display='none';btn.textContent='Think'}else{el.style.display='block';btn.textContent='Hide Think'}}
function toggleSection(id){var el=document.getElementById('section-'+id);var btn=document.getElementById('btn-'+id);var isVisible=el.style.display==='block';document.querySelectorAll('.section-content').forEach(e=>e.style.display='none');document.querySelectorAll('.section-btn').forEach(b=>b.classList.remove('active'));if(!isVisible){el.style.display='block';btn.classList.add('active')}}
</script>
"""
