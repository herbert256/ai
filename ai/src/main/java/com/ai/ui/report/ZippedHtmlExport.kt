package com.ai.ui.report

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Zipped HTML" export — bundles every section of the Complete report
 * into its own .html file inside a directory tree that mirrors the
 * Medium HTML's view picker. The user gets a navigable mini-site:
 * top-level index links to each section's index, which links to each
 * item's own page; traces drill down one extra level to a per-trace
 * directory whose four files (request_headers, request_body,
 * response_headers, response_body) are the redacted parts of that
 * single API call.
 *
 * Tree:
 *
 *   index.html
 *   style.css
 *   Reports/
 *     index.html
 *     <01_provider_model>.html ...
 *   Summaries/
 *     index.html
 *     <01_provider_model>.html ...
 *   Compares/, Reranks/, Moderations/, Translations/  (same shape)
 *   Prompt/
 *     index.html
 *   Costs/
 *     index.html
 *   JSON/
 *     index.html
 *     <category>/
 *       index.html
 *       <01_method_host_status>/
 *         index.html
 *         request_headers.html
 *         request_body.html
 *         response_headers.html
 *         response_body.html
 *
 * Source-report traces (translated reports) live under JSON/source/
 * mirroring the same per-category / per-trace structure.
 */
internal fun buildZippedHtmlBytes(context: Context, report: Report): ByteArray {
    val data = buildHtmlReportData(context, report)
    val mainTraceIndex = buildTraceIndex(data, traceJsonRoot = "JSON/")
    // If this is a translated report, also emit the original report's
    // HTML tree under Source/ so each Translation page can link back
    // to both the source page and the result page in the current
    // report. The source uses its own trace-index pointing into the
    // main report's JSON/source/ tree (no duplicate trace files).
    val sourceData = report.sourceReportId?.let { sid ->
        ReportStorage.getReport(context, sid)?.let { buildHtmlReportData(context, it) }
    }
    val sourceTraceIndex = sourceData?.let { buildTraceIndex(it, traceJsonRoot = "JSON/source/") }
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos.buffered()).use { zos ->
        emit(zos, "style.css", sharedCss())
        emitReportSections(zos, data, mainTraceIndex, basePath = "", isMain = true, sourceData = sourceData)
        if (sourceData != null && sourceTraceIndex != null) {
            emitReportSections(zos, sourceData, sourceTraceIndex, basePath = "Source/", isMain = false, sourceData = null)
        }
    }
    return baos.toByteArray()
}

/** Emit every section file for a single report at the given [basePath]
 *  prefix. [isMain] gates two things: only the main report owns the
 *  Translations and JSON sections (source's traces are reachable
 *  through the main JSON tree under JSON/source/). [sourceData] is
 *  threaded into the Translations renderer so each translation entry
 *  can link to its matching page under Source/. */
private fun emitReportSections(
    zos: ZipOutputStream,
    data: HtmlReportData,
    traceIndex: List<TraceLoc>,
    basePath: String,
    isMain: Boolean,
    sourceData: HtmlReportData?
) {
    emit(zos, "${basePath}index.html", rootIndex(data, basePath, hasSource = isMain && sourceData != null))

    if (data.agents.isNotEmpty()) emitReports(zos, data, traceIndex, basePath)
    emitSecondaryKind(zos, data, SecondaryKind.SUMMARIZE, "Summaries", traceIndex, basePath)
    emitSecondaryKind(zos, data, SecondaryKind.COMPARE, "Compares", traceIndex, basePath)
    emitSecondaryKind(zos, data, SecondaryKind.RERANK, "Reranks", traceIndex, basePath)
    emitSecondaryKind(zos, data, SecondaryKind.MODERATION, "Moderations", traceIndex, basePath)
    if (isMain) emitTranslations(zos, data, sourceData)
    if (data.prompt.isNotBlank()) emitPrompt(zos, data, basePath)
    if (data.agents.any { it.inputCost != null } || data.secondary.any { it.inputTokens != null }) {
        emitCosts(zos, data, basePath)
    }
    if (isMain && data.traces.isNotEmpty()) emitTraces(zos, data)
}

// ===== Trace index — looks up the zip path of a matching trace =====

/** A single trace's identity + the zip-relative path to its index page,
 *  used when emitting agent / secondary HTML files to drop a 🐞 link
 *  to the right trace directory. */
private data class TraceLoc(
    val providerLabel: String,
    val model: String?,
    val category: String?,
    val zipPath: String
)

/** Build the same per-category, per-trace directory naming as
 *  emitTraces. [traceJsonRoot] is the zip-root-relative directory the
 *  trace index lives under — "JSON/" for the main report (own traces
 *  flat, source traces under JSON/source/) or "JSON/source/" when
 *  building the source's index (only own traces, all under
 *  JSON/source/). */
private fun buildTraceIndex(data: HtmlReportData, traceJsonRoot: String): List<TraceLoc> {
    val out = mutableListOf<TraceLoc>()
    val ownTraces = data.traces.filter { it.origin == "this" }
    val sourceTraces = data.traces.filter { it.origin == "source" }
    val ownGroups = ownTraces.groupBy { (it.category ?: "Other").trim().ifBlank { "Other" } }
    ownGroups.forEach { (cat, list) ->
        val catSafe = safeName(cat)
        list.forEachIndexed { idx, t ->
            val dir = traceDirName(idx, t)
            out += TraceLoc(
                providerLabel = providerLabelFor(t.hostname),
                model = t.model,
                category = t.category,
                zipPath = "$traceJsonRoot$catSafe/$dir/index.html"
            )
        }
    }
    val sourceGroups = sourceTraces.groupBy { (it.category ?: "Other").trim().ifBlank { "Other" } }
    sourceGroups.forEach { (cat, list) ->
        val catSafe = safeName(cat)
        list.forEachIndexed { idx, t ->
            val dir = traceDirName(idx, t)
            out += TraceLoc(
                providerLabel = providerLabelFor(t.hostname),
                model = t.model,
                category = t.category,
                zipPath = "${traceJsonRoot}source/$catSafe/$dir/index.html"
            )
        }
    }
    return out
}

/** First trace whose provider + model match, optionally narrowed to one
 *  of [categoryHints] (e.g. "Report rerank" for a rerank page). Null
 *  when nothing matches. */
private fun List<TraceLoc>.findMatch(providerLabel: String, model: String?, vararg categoryHints: String): TraceLoc? {
    val hints = categoryHints.toSet()
    return firstOrNull { loc ->
        loc.providerLabel == providerLabel &&
            (model == null || loc.model == model) &&
            (hints.isEmpty() || loc.category in hints)
    }
}

private fun providerLabelFor(host: String): String =
    AppService.entries.firstOrNull { svc ->
        runCatching { java.net.URI(svc.baseUrl).host }.getOrNull()
            ?.equals(host, ignoreCase = true) == true
    }?.displayName ?: host

/** Render a "🐞 trace" link relative to a page at [pageDepth] inside
 *  the report's tree, with [basePath] adding extra ../ levels for
 *  source pages under "Source/". */
private fun bugLink(loc: TraceLoc?, pageDepth: Int, basePath: String = ""): String {
    if (loc == null) return ""
    val totalDepth = pageDepth + basePath.count { it == '/' }
    val rel = "../".repeat(totalDepth) + loc.zipPath
    return "<a class='bug' href='${esc(rel)}' title='View API trace'>🐞</a>"
}

// ===== Sections present (used by the root index to list links) =====

private fun rootSections(data: HtmlReportData): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    if (data.prompt.isNotBlank()) out += "Prompt" to "Prompt/"
    if (data.agents.any { it.inputCost != null } || data.secondary.any { it.inputTokens != null }) out += "Costs" to "Costs/"
    if (data.agents.isNotEmpty()) out += "Reports" to "Reports/"
    val byKind: (SecondaryKind, String) -> Unit = { kind, label ->
        if (data.secondary.any { it.kind == kind }) out += label to "$label/"
    }
    byKind(SecondaryKind.SUMMARIZE, "Summaries")
    byKind(SecondaryKind.COMPARE, "Compares")
    byKind(SecondaryKind.RERANK, "Reranks")
    byKind(SecondaryKind.MODERATION, "Moderations")
    if (data.secondary.any { it.kind == SecondaryKind.TRANSLATE }) out += "Translations" to "Translations/"
    if (data.traces.isNotEmpty()) out += "JSON" to "JSON/"
    return out
}

// ===== Root index =====

private fun rootIndex(data: HtmlReportData, basePath: String, hasSource: Boolean): String {
    val sb = StringBuilder()
    sb.append(htmlHead(title = data.title.ifBlank { "AI Report" }, depth = 0, basePath = basePath))
    sb.append("<nav>").append("<a href='index.html'>${esc(data.title.ifBlank { "AI Report" })}</a>").append("</nav>")
    sb.append("<main>")
    sb.append("<h1>").append(esc(data.title.ifBlank { "AI Report" })).append("</h1>")
    sb.append("<div class='meta'>").append(esc(data.timestamp)).append("</div>")
    if (!data.rapportText.isNullOrBlank()) sb.append("<div class='rapport'>${convertMarkdownToHtmlForExport(data.rapportText)}</div>")
    sb.append("<h2>Sections</h2><ul class='section-list'>")
    rootSections(data).forEach { (label, href) ->
        sb.append("<li><a href='${esc(href)}index.html'>").append(esc(label)).append("</a></li>")
    }
    if (hasSource) {
        sb.append("<li><a href='Source/index.html'>Source <span class='count'>(original report)</span></a></li>")
    }
    sb.append("</ul>")
    if (!data.closeText.isNullOrBlank()) sb.append("<div class='close-text'>${convertMarkdownToHtmlForExport(data.closeText)}</div>")
    sb.append("</main></body></html>")
    return sb.toString()
}

// ===== Reports section =====

private fun emitReports(zos: ZipOutputStream, data: HtmlReportData, traceIndex: List<TraceLoc>, basePath: String = "") {
    val maxAnchor = data.agents.mapNotNull { it.anchorIndex }.maxOrNull() ?: 0
    val items = data.agents.mapIndexed { idx, a ->
        Triple(itemFilename(idx, "${a.providerDisplay}_${a.model}"), a.providerDisplay + " / " + a.model, a)
    }
    // Section index
    val sb = StringBuilder()
    sb.append(htmlHead("Reports - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Reports" to null), data))
    sb.append("<main><h1>Reports</h1><ul class='item-list'>")
    items.forEach { (filename, label, _) ->
        sb.append("<li><a href='${esc(filename)}'>").append(esc(label)).append("</a></li>")
    }
    sb.append("</ul></main></body></html>")
    emit(zos, "${basePath}Reports/index.html", sb.toString())
    // Per-agent files
    items.forEach { (filename, label, a) ->
        emit(zos, "${basePath}Reports/$filename", reportPage(label, a, data, maxAnchor, traceIndex, basePath))
    }
}

private fun reportPage(label: String, a: HtmlAgentData, data: HtmlReportData, maxAnchor: Int, traceIndex: List<TraceLoc>, basePath: String): String {
    val sb = StringBuilder()
    sb.append(htmlHead("$label - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Reports" to "index.html", label to null), data))
    sb.append("<main>")
    // Match by (provider displayName, model) and the "Report" category
    // — that's the per-agent run on the original report. For
    // translated reports there's no own-side trace for the agent run
    // (the response carries over from the source), so the lookup
    // naturally lands on a source-side trace.
    val match = traceIndex.findMatch(a.providerDisplay, a.model, "Report")
    sb.append("<h1>").append(esc(a.providerDisplay)).append(" / ").append(esc(a.model))
        .append(bugLink(match, pageDepth = 1, basePath = basePath)).append("</h1>")
    if (a.errorMessage != null) sb.append("<div class='error'>Error: ${esc(a.errorMessage)}</div>")
    if (!a.responseText.isNullOrBlank()) sb.append("<div class='response'>${processThinkSections(a.responseText, a.agentId)}</div>")
    a.citations?.takeIf { it.isNotEmpty() }?.let { cites ->
        sb.append("<h3>Sources</h3><ol class='sources'>")
        cites.forEach { url -> sb.append("<li><a href='").append(esc(url)).append("'>").append(esc(url)).append("</a></li>") }
        sb.append("</ol>")
    }
    a.searchResults?.takeIf { it.isNotEmpty() }?.let { results ->
        sb.append("<h3>Search results</h3><ol class='search-results'>")
        results.forEach { r ->
            val url = r.url ?: ""
            val name = r.name ?: url
            sb.append("<li><a href='").append(esc(url)).append("'>").append(esc(name)).append("</a>")
            if (!r.snippet.isNullOrBlank()) sb.append("<div class='snippet'>").append(esc(r.snippet)).append("</div>")
            sb.append("</li>")
        }
        sb.append("</ol>")
    }
    a.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { qs ->
        sb.append("<h3>Related questions</h3><ol class='related'>")
        qs.forEach { q -> sb.append("<li>").append(esc(q)).append("</li>") }
        sb.append("</ol>")
    }
    sb.append("</main></body></html>")
    @Suppress("UNUSED_PARAMETER") val unused = maxAnchor
    return sb.toString()
}

// ===== Secondary (Summaries / Compares / Reranks / Moderations) =====

private fun emitSecondaryKind(zos: ZipOutputStream, data: HtmlReportData, kind: SecondaryKind, label: String, traceIndex: List<TraceLoc>, basePath: String = "") {
    val items = data.secondary.filter { it.kind == kind }
    if (items.isEmpty()) return
    val maxAnchor = data.agents.mapNotNull { it.anchorIndex }.maxOrNull() ?: 0
    val withFiles = items.mapIndexed { idx, s ->
        Triple(itemFilename(idx, "${s.providerDisplay}_${s.model}"), "${s.providerDisplay} / ${s.model}", s)
    }
    // Section index
    val sb = StringBuilder()
    sb.append(htmlHead("$label - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf(label to null), data))
    sb.append("<main><h1>").append(esc(label)).append("</h1><ul class='item-list'>")
    withFiles.forEach { (filename, itemLabel, s) ->
        sb.append("<li><a href='${esc(filename)}'>").append(esc(itemLabel))
            .append(" <span class='ts'>").append(esc(s.timestamp)).append("</span></a></li>")
    }
    sb.append("</ul></main></body></html>")
    emit(zos, "${basePath}$label/index.html", sb.toString())
    // Per-item files
    val traceCategory = when (kind) {
        SecondaryKind.SUMMARIZE -> "Report summarize"
        SecondaryKind.COMPARE -> "Report compare"
        SecondaryKind.RERANK -> "Report rerank"
        SecondaryKind.MODERATION -> "Report moderation"
        SecondaryKind.TRANSLATE -> "Report translate"
    }
    withFiles.forEach { (filename, itemLabel, s) ->
        emit(zos, "${basePath}$label/$filename", secondaryPage(label, itemLabel, s, data, maxAnchor, traceIndex, traceCategory, basePath))
    }
}

private fun secondaryPage(section: String, itemLabel: String, s: HtmlSecondaryData, data: HtmlReportData, maxAnchor: Int, traceIndex: List<TraceLoc>, traceCategory: String, basePath: String): String {
    val sb = StringBuilder()
    sb.append(htmlHead("$itemLabel - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf(section to "index.html", itemLabel to null), data))
    sb.append("<main>")
    val match = traceIndex.findMatch(s.providerDisplay, s.model, traceCategory)
    sb.append("<h1>").append(esc(itemLabel)).append(bugLink(match, pageDepth = 1, basePath = basePath)).append("</h1>")
    sb.append("<div class='meta'>").append(esc(s.timestamp)).append("</div>")
    if (s.errorMessage != null) {
        sb.append("<div class='error'>Error: ${esc(s.errorMessage)}</div>")
    } else if (!s.content.isNullOrBlank()) {
        when (s.kind) {
            SecondaryKind.RERANK -> sb.append("<div class='response'>${renderRerankContentLocal(s.content, maxAnchor)}</div>")
            SecondaryKind.COMPARE -> {
                val linkified = Regex("""\[(\d+)\]""").replace(s.content) { m ->
                    val id = m.groupValues[1].toIntOrNull() ?: return@replace m.value
                    if (id in 1..maxAnchor) "[$id]" else m.value
                }
                sb.append("<div class='response'>${convertMarkdownToHtmlForExport(linkified)}</div>")
            }
            else -> sb.append("<div class='response'>${convertMarkdownToHtmlForExport(s.content)}</div>")
        }
    }
    sb.append("</main></body></html>")
    return sb.toString()
}

// ===== Translations (meta-only — no translated content) =====

private fun emitTranslations(zos: ZipOutputStream, data: HtmlReportData, sourceData: HtmlReportData?) {
    val items = data.secondary.filter { it.kind == SecondaryKind.TRANSLATE }
    if (items.isEmpty()) return
    val withFiles = items.mapIndexed { idx, s ->
        val what = s.agentName.removePrefix("Translate:").trim().ifBlank { s.agentName }
        Triple(itemFilename(idx, what), what, s)
    }
    val sb = StringBuilder()
    sb.append(htmlHead("Translations - ${data.title}", depth = 1))
    sb.append(breadcrumb(1, listOf("Translations" to null), data))
    sb.append("<main><h1>Translations</h1><ul class='item-list'>")
    withFiles.forEach { (filename, what, _) ->
        sb.append("<li><a href='${esc(filename)}'>").append(esc(what)).append("</a></li>")
    }
    sb.append("</ul></main></body></html>")
    emit(zos, "Translations/index.html", sb.toString())
    withFiles.forEach { (filename, what, s) ->
        val pageSb = StringBuilder()
        pageSb.append(htmlHead("Translation: $what - ${data.title}", depth = 1))
        pageSb.append(breadcrumb(1, listOf("Translations" to "index.html", what to null), data))
        pageSb.append("<main>")
        pageSb.append("<h1>Translation: ").append(esc(what)).append("</h1>")
        // Two cross-tree links: "Original" → matching page under
        // Source/, "Result" → matching page in the current report.
        // Resolved via translateSourceKind / translateSourceTargetId
        // (set when the translation flow created the SecondaryResult)
        // and translatedFromSecondaryId (stamped on the translated
        // copies of Summary/Compare secondaries).
        val (sourceLink, resultLink) = resolveTranslationLinks(s, data, sourceData)
        if (sourceLink != null || resultLink != null) {
            pageSb.append("<div class='translate-links'>")
            if (sourceLink != null) {
                pageSb.append("<a class='cross-link' href='${esc(sourceLink)}'>📜 Original (Source)</a>")
            }
            if (resultLink != null) {
                pageSb.append("<a class='cross-link' href='${esc(resultLink)}'>🌐 Result</a>")
            }
            pageSb.append("</div>")
        }
        pageSb.append("<table class='kv'>")
        pageSb.append("<tr><th>What</th><td>").append(esc(what)).append("</td></tr>")
        pageSb.append("<tr><th>Provider / Model</th><td>").append(esc(s.providerDisplay)).append(" / ").append(esc(s.model)).append("</td></tr>")
        pageSb.append("<tr><th>Timestamp</th><td>").append(esc(s.timestamp)).append("</td></tr>")
        s.durationMs?.let { pageSb.append("<tr><th>Seconds</th><td class='num'>").append("%.1f".format(it / 1000.0)).append("</td></tr>") }
        s.inputTokens?.let { pageSb.append("<tr><th>Input tokens</th><td class='num'>").append(it).append("</td></tr>") }
        s.outputTokens?.let { pageSb.append("<tr><th>Output tokens</th><td class='num'>").append(it).append("</td></tr>") }
        s.inputCost?.let { pageSb.append("<tr><th>Input cents</th><td class='num'>").append("%.2f".format(it * 100)).append("</td></tr>") }
        s.outputCost?.let { pageSb.append("<tr><th>Output cents</th><td class='num'>").append("%.2f".format(it * 100)).append("</td></tr>") }
        if (s.inputCost != null || s.outputCost != null) {
            val total = (s.inputCost ?: 0.0) + (s.outputCost ?: 0.0)
            pageSb.append("<tr><th>Total cents</th><td class='num'>").append("%.2f".format(total * 100)).append("</td></tr>")
        }
        pageSb.append("</table>")
        pageSb.append("</main></body></html>")
        emit(zos, "Translations/$filename", pageSb.toString())
    }
}

/** Resolve a translation entry to its (Original under Source/, Result
 *  in the current report) link pair. Each is relative to the
 *  Translations/ directory (so "../Source/Reports/01_X.html" reaches
 *  Source/Reports/01_X.html). Either side returns null when the
 *  matching page can't be found — typically because the translation
 *  metadata is from before the link fields existed on SecondaryResult,
 *  or because the source report could not be loaded. */
internal fun resolveTranslationLinks(
    translate: HtmlSecondaryData,
    currentData: HtmlReportData,
    sourceData: HtmlReportData?
): Pair<String?, String?> {
    val kind = translate.translateSourceKind ?: return null to null
    val targetId = translate.translateSourceTargetId ?: ""

    // Helper: agent file name in a given report (matches itemFilename's
    // index-based naming used by emitReports).
    fun agentFileFor(d: HtmlReportData?, agentId: String): String? {
        if (d == null) return null
        val idx = d.agents.indexOfFirst { it.agentId == agentId }.takeIf { it >= 0 } ?: return null
        val a = d.agents[idx]
        return itemFilename(idx, "${a.providerDisplay}_${a.model}")
    }
    // Helper: secondary file name in a given report keyed by its id.
    fun secondaryFileFor(d: HtmlReportData?, secId: String, kindFilter: SecondaryKind): String? {
        if (d == null) return null
        val list = d.secondary.filter { it.kind == kindFilter }
        val idx = list.indexOfFirst { it.id == secId }.takeIf { it >= 0 } ?: return null
        val s = list[idx]
        return itemFilename(idx, "${s.providerDisplay}_${s.model}")
    }

    return when (kind) {
        "PROMPT" -> {
            val src = if (sourceData != null) "../Source/Prompt/index.html" else null
            val res = "../Prompt/index.html"
            src to res
        }
        "AGENT" -> {
            val src = agentFileFor(sourceData, targetId)?.let { "../Source/Reports/$it" }
            // The translated report keeps source's agentId, so look up
            // by the same id on the current report.
            val res = agentFileFor(currentData, targetId)?.let { "../Reports/$it" }
            src to res
        }
        "SUMMARY", "COMPARE" -> {
            val sectionDir = if (kind == "SUMMARY") "Summaries" else "Compares"
            val secKind = if (kind == "SUMMARY") SecondaryKind.SUMMARIZE else SecondaryKind.COMPARE
            val src = secondaryFileFor(sourceData, targetId, secKind)?.let { "../Source/$sectionDir/$it" }
            // The translated copy preserves translatedFromSecondaryId,
            // pointing at the source's id we just used as the lookup
            // key. Find the translated entry by that back-reference.
            val translatedList = currentData.secondary.filter { it.kind == secKind }
            val translatedIdx = translatedList.indexOfFirst { it.translatedFromSecondaryId == targetId }
            val res = if (translatedIdx >= 0) {
                val t = translatedList[translatedIdx]
                "../$sectionDir/" + itemFilename(translatedIdx, "${t.providerDisplay}_${t.model}")
            } else null
            src to res
        }
        else -> null to null
    }
}

// ===== Prompt =====

private fun emitPrompt(zos: ZipOutputStream, data: HtmlReportData, basePath: String = "") {
    val sb = StringBuilder()
    sb.append(htmlHead("Prompt - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Prompt" to null), data))
    sb.append("<main><h1>Prompt</h1><pre class='prompt'>").append(esc(data.prompt)).append("</pre></main></body></html>")
    emit(zos, "${basePath}Prompt/index.html", sb.toString())
}

// ===== Costs =====

private fun emitCosts(zos: ZipOutputStream, data: HtmlReportData, basePath: String = "") {
    val sb = StringBuilder()
    sb.append(htmlHead("Costs - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Costs" to null), data))
    sb.append("<main><h1>Costs</h1>")
    // Scroll container so the wide nowrap table can overflow horizontally
    // on narrow viewports without forcing the whole page to scroll.
    sb.append("<div class='cost-table-wrap'>")
    sb.append("<table class='cost-table sortable'>")
    sb.append("<thead><tr>")
    sb.append("<th data-sort='str'>Type</th>")
    sb.append("<th data-sort='str'>Provider</th>")
    sb.append("<th data-sort='str'>Model</th>")
    sb.append("<th data-sort='str'>Tier</th>")
    sb.append("<th class='num' data-sort='num'>Seconds</th>")
    sb.append("<th class='num' data-sort='num'>Input tokens</th>")
    sb.append("<th class='num' data-sort='num'>Output tokens</th>")
    sb.append("<th class='num' data-sort='num'>Input cents</th>")
    sb.append("<th class='num' data-sort='num'>Output cents</th>")
    sb.append("<th class='num' data-sort='num'>Total cents</th>")
    sb.append("</tr></thead>")
    sb.append("<tbody>")
    data class Row(val type: String, val provider: String, val model: String, val tier: String, val durationMs: Long?, val inT: Int, val outT: Int, val inC: Double, val outC: Double)
    val agentRows = data.agents.filter { it.inputCost != null }.map {
        Row("report", it.providerDisplay, it.model, it.pricingTier ?: "", it.durationMs, it.inputTokens ?: 0, it.outputTokens ?: 0, (it.inputCost ?: 0.0) * 100, (it.outputCost ?: 0.0) * 100)
    }
    val secondaryRows = data.secondary.filter { it.inputTokens != null }.map {
        val type = when (it.kind) {
            SecondaryKind.RERANK -> "rerank"; SecondaryKind.SUMMARIZE -> "summarize"
            SecondaryKind.COMPARE -> "compare"; SecondaryKind.MODERATION -> "moderation"
            SecondaryKind.TRANSLATE -> "translate"
        }
        Row(type, it.providerDisplay, it.model, it.pricingTier ?: "", it.durationMs, it.inputTokens ?: 0, it.outputTokens ?: 0, (it.inputCost ?: 0.0) * 100, (it.outputCost ?: 0.0) * 100)
    }
    val sorted = (agentRows + secondaryRows).sortedByDescending { it.inC + it.outC }
    var tIn = 0; var tOut = 0; var tInC = 0.0; var tOutC = 0.0
    sorted.forEach { r ->
        tIn += r.inT; tOut += r.outT; tInC += r.inC; tOutC += r.outC
        val secs = r.durationMs?.let { "%.1f".format(it / 1000.0) } ?: ""
        sb.append("<tr><td>").append(esc(r.type)).append("</td><td>").append(esc(r.provider))
            .append("</td><td>").append(esc(r.model)).append("</td><td>").append(esc(r.tier))
            .append("</td><td class='num'>").append(secs)
            .append("</td><td class='num'>").append(r.inT).append("</td><td class='num'>").append(r.outT)
            .append("</td><td class='num'>").append("%.2f".format(r.inC))
            .append("</td><td class='num'>").append("%.2f".format(r.outC))
            .append("</td><td class='num'>").append("%.2f".format(r.inC + r.outC)).append("</td></tr>")
    }
    sb.append("</tbody>")
    sb.append("<tfoot><tr class='total'><td colspan='5'>Total</td>")
    sb.append("<td class='num'>").append(tIn).append("</td><td class='num'>").append(tOut).append("</td>")
    sb.append("<td class='num'>").append("%.2f".format(tInC)).append("</td>")
    sb.append("<td class='num'>").append("%.2f".format(tOutC)).append("</td>")
    sb.append("<td class='num'>").append("%.2f".format(tInC + tOutC)).append("</td></tr></tfoot>")
    sb.append("</table>")
    sb.append("</div>")
    // Inline sort script — only this page uses it, so no point dragging
    // it into the shared style.css. Click a header to sort by that
    // column; click again to reverse. Total row stays parked in
    // <tfoot> regardless of sort direction.
    sb.append(costSortScript())
    sb.append("</main></body></html>")
    emit(zos, "${basePath}Costs/index.html", sb.toString())
}

private fun costSortScript(): String = """
<script>
(function(){
  var table = document.querySelector('table.sortable');
  if (!table) return;
  var headers = table.querySelectorAll('thead th');
  var tbody = table.querySelector('tbody');
  var current = -1, asc = true;
  headers.forEach(function(h, idx){
    var label = h.textContent;
    h.dataset.label = label;
    h.style.cursor = 'pointer';
    h.style.userSelect = 'none';
    h.addEventListener('click', function(){
      if (current === idx) asc = !asc; else { current = idx; asc = true; }
      var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));
      var kind = h.dataset.sort || 'str';
      rows.sort(function(a, b){
        var av = (a.children[idx].textContent || '').trim();
        var bv = (b.children[idx].textContent || '').trim();
        if (kind === 'num') {
          var an = parseFloat(av); if (isNaN(an)) an = -Infinity;
          var bn = parseFloat(bv); if (isNaN(bn)) bn = -Infinity;
          return asc ? an - bn : bn - an;
        }
        return asc ? av.localeCompare(bv) : bv.localeCompare(av);
      });
      rows.forEach(function(r){ tbody.appendChild(r); });
      headers.forEach(function(h2){ h2.textContent = h2.dataset.label; });
      h.textContent = h.dataset.label + (asc ? ' ▲' : ' ▼');
    });
  });
})();
</script>
""".trimIndent()

// ===== Traces =====

private fun emitTraces(zos: ZipOutputStream, data: HtmlReportData) {
    val ownTraces = data.traces.filter { it.origin == "this" }
    val sourceTraces = data.traces.filter { it.origin == "source" }

    // JSON/index.html — list of "this" categories + a link to source/ when present
    val sb = StringBuilder()
    sb.append(htmlHead("JSON traces - ${data.title}", depth = 1))
    sb.append(breadcrumb(1, listOf("JSON" to null), data))
    sb.append("<main><h1>JSON traces</h1>")
    val ownGroups = ownTraces.groupBy { (it.category ?: "Other").trim().ifBlank { "Other" } }
    val ownKeys = ownGroups.keys.sortedWith(compareBy({ it == "Other" }, { it.lowercase() }))
    if (ownKeys.isNotEmpty()) {
        sb.append("<h2>Categories</h2><ul class='item-list'>")
        ownKeys.forEach { k -> sb.append("<li><a href='").append(esc(safeName(k))).append("/index.html'>")
            .append(esc(k)).append(" <span class='count'>(").append(ownGroups[k]!!.size).append(")</span></a></li>") }
        sb.append("</ul>")
    }
    if (sourceTraces.isNotEmpty()) {
        sb.append("<h2>Source report</h2><ul class='item-list'>")
        sb.append("<li><a href='source/index.html'>source/ <span class='count'>(").append(sourceTraces.size).append(")</span></a></li>")
        sb.append("</ul>")
    }
    sb.append("</main></body></html>")
    emit(zos, "JSON/index.html", sb.toString())

    // Own categories
    ownKeys.forEach { cat -> emitTraceCategory(zos, "JSON/${safeName(cat)}", data, cat, ownGroups[cat]!!, depth = 2,
        crumbs = listOf("JSON" to "../index.html", cat to null)) }

    // Source categories — under JSON/source/<category>/...
    if (sourceTraces.isNotEmpty()) {
        val sourceGroups = sourceTraces.groupBy { (it.category ?: "Other").trim().ifBlank { "Other" } }
        val sourceKeys = sourceGroups.keys.sortedWith(compareBy({ it == "Other" }, { it.lowercase() }))
        // JSON/source/index.html
        val srcSb = StringBuilder()
        srcSb.append(htmlHead("JSON traces from source report - ${data.title}", depth = 2))
        srcSb.append(breadcrumb(2, listOf("JSON" to "../index.html", "source" to null), data))
        srcSb.append("<main><h1>JSON traces — source report</h1><ul class='item-list'>")
        sourceKeys.forEach { k -> srcSb.append("<li><a href='").append(esc(safeName(k)))
            .append("/index.html'>").append(esc(k)).append(" <span class='count'>(").append(sourceGroups[k]!!.size).append(")</span></a></li>") }
        srcSb.append("</ul></main></body></html>")
        emit(zos, "JSON/source/index.html", srcSb.toString())

        sourceKeys.forEach { cat -> emitTraceCategory(zos, "JSON/source/${safeName(cat)}", data, cat, sourceGroups[cat]!!,
            depth = 3, crumbs = listOf("JSON" to "../../index.html", "source" to "../index.html", cat to null)) }
    }
}

private fun emitTraceCategory(zos: ZipOutputStream, dirPath: String, data: HtmlReportData, category: String, traces: List<HtmlTraceData>, depth: Int, crumbs: List<Pair<String, String?>>) {
    // Category index — list of traces.
    val sb = StringBuilder()
    sb.append(htmlHead("$category - ${data.title}", depth))
    sb.append(breadcrumb(depth, crumbs, data))
    sb.append("<main><h1>").append(esc(category)).append("</h1><ul class='trace-list'>")
    traces.forEachIndexed { idx, t ->
        val dir = traceDirName(idx, t)
        sb.append("<li><a href='").append(esc(dir)).append("/index.html'>")
        sb.append(esc(t.timestamp)).append(" · ").append(esc(t.method)).append(" · ").append(esc(t.hostname))
        if (!t.model.isNullOrBlank()) sb.append(" / ").append(esc(t.model))
        sb.append(" · ").append(t.statusCode).append("</a></li>")
    }
    sb.append("</ul></main></body></html>")
    emit(zos, "$dirPath/index.html", sb.toString())

    // Per-trace dirs — index.html plus the four part files.
    val crumbsChain = crumbs.toMutableList()
    val parentLabel = crumbsChain.removeAt(crumbsChain.lastIndex).first
    crumbsChain += parentLabel to "index.html"
    traces.forEachIndexed { idx, t ->
        val dir = traceDirName(idx, t)
        val traceCrumbs = crumbsChain + ("Trace" to null)
        // Per-trace index — metadata + 4 links.
        val tsb = StringBuilder()
        tsb.append(htmlHead("Trace ${t.method} ${t.hostname} ${t.statusCode} - ${data.title}", depth + 1))
        tsb.append(breadcrumb(depth + 1, traceCrumbs, data))
        tsb.append("<main><h1>Trace</h1>")
        tsb.append("<table class='kv'>")
        tsb.append("<tr><th>Timestamp</th><td>").append(esc(t.timestamp)).append("</td></tr>")
        tsb.append("<tr><th>Method</th><td>").append(esc(t.method)).append("</td></tr>")
        tsb.append("<tr><th>URL</th><td>").append(esc(t.url)).append("</td></tr>")
        tsb.append("<tr><th>Host</th><td>").append(esc(t.hostname)).append("</td></tr>")
        if (!t.model.isNullOrBlank()) tsb.append("<tr><th>Model</th><td>").append(esc(t.model)).append("</td></tr>")
        tsb.append("<tr><th>Status</th><td>").append(t.statusCode).append("</td></tr>")
        if (t.category != null) tsb.append("<tr><th>Category</th><td>").append(esc(t.category)).append("</td></tr>")
        tsb.append("<tr><th>Origin</th><td>").append(esc(t.origin)).append("</td></tr>")
        tsb.append("</table>")
        tsb.append("<h2>Parts</h2><ul class='item-list'>")
        tsb.append("<li><a href='request_headers.html'>Request headers</a></li>")
        tsb.append("<li><a href='request_body.html'>Request body</a></li>")
        tsb.append("<li><a href='response_headers.html'>Response headers</a></li>")
        tsb.append("<li><a href='response_body.html'>Response body</a></li>")
        tsb.append("</ul>")
        tsb.append("</main></body></html>")
        emit(zos, "$dirPath/$dir/index.html", tsb.toString())
        // 4 part files
        emit(zos, "$dirPath/$dir/request_headers.html", tracePartPage(data, t, "Request headers", t.requestHeaders, depth + 1, traceCrumbs))
        emit(zos, "$dirPath/$dir/request_body.html", tracePartPage(data, t, "Request body", t.requestBody, depth + 1, traceCrumbs))
        emit(zos, "$dirPath/$dir/response_headers.html", tracePartPage(data, t, "Response headers", t.responseHeaders, depth + 1, traceCrumbs))
        emit(zos, "$dirPath/$dir/response_body.html", tracePartPage(data, t, "Response body", t.responseBody, depth + 1, traceCrumbs))
    }
}

private fun tracePartPage(data: HtmlReportData, t: HtmlTraceData, partLabel: String, body: String, depth: Int, crumbs: List<Pair<String, String?>>): String {
    // Replace the trailing "Trace" crumb with a link to the trace's
    // index so the part page can navigate up one step to the trace's
    // overview and then onwards.
    val partCrumbs = crumbs.dropLast(1) + ("Trace" to "index.html") + (partLabel to null)
    val sb = StringBuilder()
    sb.append(htmlHead("$partLabel - ${t.method} ${t.hostname} - ${data.title}", depth))
    sb.append(breadcrumb(depth, partCrumbs, data))
    sb.append("<main><h1>").append(esc(partLabel)).append("</h1>")
    sb.append("<div class='meta'>").append(esc(t.method)).append(" ").append(esc(t.url)).append("</div>")
    val display = body.ifBlank { "(empty)" }
    val colorized = colorizedJsonHtml(display)
    if (colorized != null) {
        sb.append("<pre class='code json'>").append(colorized).append("</pre>")
    } else {
        sb.append("<pre class='code'>").append(esc(display)).append("</pre>")
    }
    sb.append("</main></body></html>")
    return sb.toString()
}

/** If [text] parses as JSON, return pretty-printed HTML with
 *  per-token spans for syntax colouring; otherwise return null so
 *  the caller falls back to plain escaped text. The output is meant
 *  to live inside a <pre> — newlines + spaces are literal. */
internal fun colorizedJsonHtml(text: String): String? {
    val trimmed = text.trim()
    // Quick reject: anything not starting with { or [ isn't worth parsing.
    if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return null
    val root: JsonElement = try {
        @Suppress("DEPRECATION") JsonParser().parse(trimmed)
    } catch (_: Exception) { return null }
    val sb = StringBuilder()
    fun renderString(s: String, cls: String) {
        sb.append("<span class='").append(cls).append("'>\"")
            .append(esc(s)).append("\"</span>")
    }
    fun render(el: JsonElement, indent: Int) {
        when {
            el.isJsonNull -> sb.append("<span class='j-null'>null</span>")
            el.isJsonPrimitive -> {
                val p = el.asJsonPrimitive
                when {
                    p.isString -> renderString(p.asString, "j-str")
                    p.isBoolean -> sb.append("<span class='j-bool'>").append(p.asBoolean).append("</span>")
                    p.isNumber -> sb.append("<span class='j-num'>").append(p.asNumber.toString()).append("</span>")
                    else -> sb.append(esc(p.toString()))
                }
            }
            el.isJsonArray -> {
                val arr = el.asJsonArray
                if (arr.size() == 0) { sb.append("[]"); return }
                sb.append("[\n")
                val pad = "  ".repeat(indent + 1)
                arr.forEachIndexed { i, child ->
                    sb.append(pad)
                    render(child, indent + 1)
                    if (i < arr.size() - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  ".repeat(indent)).append("]")
            }
            el.isJsonObject -> {
                val obj = el.asJsonObject
                if (obj.size() == 0) { sb.append("{}"); return }
                sb.append("{\n")
                val entries = obj.entrySet().toList()
                val pad = "  ".repeat(indent + 1)
                entries.forEachIndexed { i, (k, v) ->
                    sb.append(pad)
                    renderString(k, "j-key")
                    sb.append(": ")
                    render(v, indent + 1)
                    if (i < entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append("  ".repeat(indent)).append("}")
            }
        }
    }
    render(root, 0)
    return sb.toString()
}

// ===== Helpers =====

private fun emit(zos: ZipOutputStream, path: String, content: String) {
    zos.putNextEntry(ZipEntry(path))
    zos.write(content.toByteArray(Charsets.UTF_8))
    zos.closeEntry()
}

internal fun safeName(s: String): String =
    s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "_" }.take(80)

internal fun itemFilename(idx: Int, label: String): String {
    val n = "%02d".format(idx + 1)
    return "${n}_${safeName(label)}.html"
}

internal fun traceDirName(idx: Int, t: HtmlTraceData): String {
    val n = "%02d".format(idx + 1)
    val tail = listOfNotNull(t.method, t.hostname, t.statusCode.toString()).joinToString("_")
    return "${n}_${safeName(tail)}"
}

/** Breadcrumb header. The last entry's URL is null — it's the
 *  "current page" label and renders without a link. Earlier entries are
 *  rendered as links pointing at the supplied (relative) URL. The
 *  leading "AI Report" link always goes back to the root index.html. */
private fun breadcrumb(depth: Int, crumbs: List<Pair<String, String?>>, data: HtmlReportData): String {
    val sb = StringBuilder()
    sb.append("<nav>")
    val rootRel = "../".repeat(depth) + "index.html"
    sb.append("<a href='").append(esc(rootRel)).append("'>").append(esc(data.title.ifBlank { "AI Report" })).append("</a>")
    crumbs.forEach { (label, href) ->
        sb.append(" <span class='sep'>›</span> ")
        if (href != null) sb.append("<a href='").append(esc(href)).append("'>").append(esc(label)).append("</a>")
        else sb.append("<span class='here'>").append(esc(label)).append("</span>")
    }
    sb.append("</nav>")
    return sb.toString()
}

/** HTML head fragment. style.css lives at the zip root and is
 *  referenced via "../" repeated for the file's full zip-root depth.
 *  [depth] is the depth within the report's tree (e.g. 1 for
 *  Reports/X.html); [basePath] adds the prefix for source pages
 *  ("Source/" → 1 extra level). */
private fun htmlHead(title: String, depth: Int, basePath: String = ""): String {
    val totalDepth = depth + basePath.count { it == '/' }
    val css = "../".repeat(totalDepth) + "style.css"
    return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
        "<title>${esc(title)}</title>" +
        "<link rel='stylesheet' href='${esc(css)}'>" +
        "</head><body>"
}

private fun sharedCss(): String = """
body{background:#1a1a1a;color:#e0e0e0;font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:0;padding:16px;line-height:1.5}
nav{background:#252525;padding:8px 12px;border-radius:4px;margin-bottom:16px;font-size:13px}
nav a{color:#64B5F6;text-decoration:none}
nav a:hover{text-decoration:underline}
nav .sep{color:#666;margin:0 4px}
nav .here{color:#fff;font-weight:bold}
main{max-width:900px;margin:0 auto}
h1{color:#6B9BFF;font-size:22px;margin-bottom:8px}
.bug{font-size:16px;text-decoration:none;margin-left:8px;vertical-align:middle;opacity:0.8}
.bug:hover{opacity:1}
.translate-links{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
.cross-link{background:#252525;color:#64B5F6;padding:6px 12px;border-radius:4px;text-decoration:none;font-size:13px}
.cross-link:hover{background:#2c2c2c}
h2{color:#8BB8FF;font-size:18px;margin-top:24px}
h3{color:#9FCFFF;font-size:15px;margin-top:16px}
.meta{color:#888;font-size:12px;margin-bottom:16px}
.section-list,.item-list,.trace-list{list-style:none;padding:0}
.section-list li,.item-list li,.trace-list li{background:#252525;border-radius:4px;padding:8px 12px;margin-bottom:6px}
.section-list a,.item-list a,.trace-list a{color:#64B5F6;text-decoration:none;display:block}
.section-list a:hover,.item-list a:hover,.trace-list a:hover{text-decoration:underline}
.count,.ts{color:#888;font-size:11px;margin-left:8px}
.error{background:#2a1a1a;border-radius:4px;padding:8px 12px;color:#ff6b6b;margin-bottom:12px}
.response,.rapport,.close-text{background:#252525;border-radius:6px;padding:12px 16px;margin-top:8px}
.rapport{border-left:3px solid #4CAF50}
table{border-collapse:collapse;width:100%;margin-top:8px;font-size:13px}
th,td{padding:6px 10px;border-bottom:1px solid #333;vertical-align:top;text-align:left}
th{background:#252525;color:#9FCFFF}
.num{text-align:right;font-family:monospace}
.cost-table .total td{color:#6B9BFF;font-weight:bold;border-top:2px solid #444}
/* Break out of main's max-width:900px so wide cost tables don't get
   horizontally cropped on a wide screen. width:max-content sizes to
   the columns; max-width caps at viewport - body padding; the
   left/transform pair re-centers on the viewport instead of main. */
.cost-table-wrap{width:max-content;max-width:calc(100vw - 32px);margin:8px auto;position:relative;left:50%;transform:translateX(-50%);overflow-x:auto}
.cost-table{width:auto;white-space:nowrap;margin:0 auto}
.cost-table th, .cost-table td{white-space:nowrap}
.cost-table th[data-sort]:hover{background:#2c2c2c}
.kv th{width:160px;background:#252525}
pre{background:#1a1a1a;border:1px solid #333;border-radius:4px;padding:10px;color:#ccc;font-family:monospace;font-size:12px;white-space:pre-wrap;word-break:break-all;overflow-x:auto;line-height:1.4}
pre.prompt{font-size:13px;color:#e0e0e0}
pre.code{max-height:none}
/* JSON syntax colouring for trace request/response bodies */
pre.json{color:#bbb}
pre.json .j-key{color:#9FCFFF}
pre.json .j-str{color:#A5D6A7}
pre.json .j-num{color:#FFB74D}
pre.json .j-bool{color:#CE93D8}
pre.json .j-null{color:#888;font-style:italic}
.snippet{color:#aaa;font-size:11px;margin-top:2px}
""".trimIndent()

// ===== Inline helpers (mirror the ReportExport.kt versions, kept
//      local so this file doesn't reach into private state) =====

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

/** Matches the ReportExport renderRerankContent — try to parse the
 *  rerank JSON array, render as a table; otherwise fall back to
 *  markdown with [N] tags preserved (no anchor links since this page
 *  doesn't host the agent cards). */
private fun renderRerankContentLocal(content: String, maxAnchor: Int): String {
    @Suppress("UNUSED_PARAMETER") val unused = maxAnchor
    val cleaned = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val asArray = try {
        @Suppress("DEPRECATION")
        JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null }
    if (asArray != null && asArray.size() > 0 && asArray.all { it.isJsonObject }) {
        val rows = asArray.mapNotNull { el ->
            val obj = el.asJsonObject
            val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt
            val score = obj.get("score")?.takeIf { it.isJsonPrimitive }?.asDouble
            val reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
            Triple(id, rank to score, reason)
        }.sortedBy { it.second.first ?: Int.MAX_VALUE }
        if (rows.isNotEmpty()) {
            val sb = StringBuilder()
            sb.append("<table><tr><th>Rank</th><th>Result</th><th>Score</th><th>Reason</th></tr>")
            rows.forEach { (id, rs, reason) ->
                val rank = rs.first?.toString() ?: ""
                val score = rs.second?.let { "%.0f".format(it) } ?: ""
                sb.append("<tr><td class='num'>").append(rank)
                    .append("</td><td>[").append(id).append("]</td><td class='num'>").append(score)
                    .append("</td><td>").append(esc(reason ?: "")).append("</td></tr>")
            }
            sb.append("</table>")
            return sb.toString()
        }
    }
    return convertMarkdownToHtmlForExport(content)
}

// ===== Dispatcher =====

internal fun shareReportAsZippedHtml(context: Context, reportId: String, action: ReportExportAction) {
    val report = ReportStorage.getReport(context, reportId) ?: return
    val safeTitle = report.title.ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
    val ts = SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
    val dir = File(context.cacheDir, "exports").also { it.mkdirs() }
    val outFile = File(dir, "ai_report_${safeTitle}_zipped_html_$ts.zip")
    outFile.writeBytes(buildZippedHtmlBytes(context, report))

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val intent = when (action) {
        ReportExportAction.SHARE -> Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AI Report (zipped HTML) - ${report.title}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ReportExportAction.VIEW -> Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val chooser = if (action == ReportExportAction.SHARE) Intent.createChooser(intent, "Share AI Report (zipped HTML)") else intent
    context.startActivity(chooser)
}
