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
 * into its own .html file inside a directory tree, one directory per
 * language. The user gets a navigable mini-site: a zip-root index
 * lists each language; each language directory has Reports plus one
 * directory per chat-type Meta prompt name + Prompt; the Original
 * directory additionally has Reranks / Moderations / Costs / JSON
 * traces (those don't translate).
 *
 * Tree:
 *
 *   index.html         — zip-root: links to each language directory
 *   style.css
 *   original/
 *     index.html       — language root: links to sections below
 *     Reports/         — every successful agent's response page
 *       index.html
 *       <01_provider_model>.html ...
 *     <metaPromptName>/  one directory per chat-type Meta prompt
 *                        present on the report (e.g. "Compare/",
 *                        "Critique/", "Synthesize/"). All translatable.
 *     Reranks/, Moderations/ (Original only — structured JSON, not
 *                             translated)
 *     Prompt/
 *       index.html
 *     Costs/           — Original only; sums all calls including
 *                        translation API calls
 *       index.html
 *     JSON/            — Original only; the captured API traces
 *       index.html
 *       <category>/
 *         <01_method_host_status>/
 *           index.html
 *           request_headers.html  request_body.html
 *           response_headers.html response_body.html
 *   dutch/             — translated language directory
 *     index.html
 *     Reports/         — translated agent responses
 *     <metaPromptName>/, Prompt/
 *   german/, …
 */
internal fun buildZippedHtmlBytes(context: Context, report: Report): ByteArray {
    val data = buildHtmlReportData(context, report)
    val languages = buildLanguageViews(data)
    val originalView = languages.first()
    // Trace + report indexes are built off the Original view only —
    // translations don't add new traces beyond those tagged with the
    // source report's id (which already live in originalView.data).
    val originalTraces = traceLocsForOwn(originalView.data, jsonRoot = "original/JSON/")
    val originalReportIndex = buildReportIndex(originalView.data, basePath = "original/", origin = "this")
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos.buffered()).use { zos ->
        emit(zos, "style.css", sharedCss())
        emit(zos, "index.html", zipRootIndex(data, languages))
        languages.forEach { lv ->
            val isOriginal = (lv.key == "original")
            emitLanguageSections(
                zos = zos,
                data = lv.data,
                lv = lv,
                traceIndex = if (isOriginal) originalTraces else emptyList(),
                reportIndex = if (isOriginal) originalReportIndex else emptyList(),
                basePath = "${lv.key}/",
                isOriginal = isOriginal
            )
        }
    }
    return baos.toByteArray()
}

/** Emit every section file for one language at the given [basePath]
 *  prefix. [isOriginal] gates Reranks / Moderations / Costs / JSON —
 *  those are emitted only inside `original/` since they don't have
 *  meaningful per-language variants (rerank/moderation are structured
 *  JSON; costs and traces are global to the report). */
private fun emitLanguageSections(
    zos: ZipOutputStream,
    data: HtmlReportData,
    lv: HtmlLanguageView,
    traceIndex: List<TraceLoc>,
    reportIndex: List<ReportLoc>,
    basePath: String,
    isOriginal: Boolean
) {
    emit(zos, "${basePath}index.html", languageIndex(data, lv, basePath, isOriginal))

    if (data.agents.isNotEmpty()) emitReports(zos, data, traceIndex, basePath, lv.displayName)
    emitMetaSections(zos, data, traceIndex, basePath, lv.displayName)
    if (isOriginal) {
        emitSecondaryKind(zos, data, SecondaryKind.RERANK, "Reranks", traceIndex, basePath, lv.displayName)
        emitSecondaryKind(zos, data, SecondaryKind.MODERATION, "Moderations", traceIndex, basePath, lv.displayName)
    }
    if (data.prompt.isNotBlank()) emitPrompt(zos, data, basePath, lv.displayName)
    if (isOriginal) {
        if (data.agents.any { it.inputCost != null } || data.secondary.any { it.inputTokens != null }) {
            emitCosts(zos, data, basePath, lv.displayName)
        }
        if (data.traces.any { it.origin == "this" }) {
            emitTraces(zos, data, reportIndex, basePath, lv.displayName)
        }
    }
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

/** Build TraceLocs for [data]'s own (origin="this") traces, addressed
 *  under [jsonRoot]. Each report side (main / source) owns one tree:
 *  /JSON/ for main, /Source/JSON/ for source. The combined index
 *  passed to the main side stitches both together so a translated
 *  main report can still link agent pages to the source-inherited
 *  traces. */
private fun traceLocsForOwn(data: HtmlReportData, jsonRoot: String): List<TraceLoc> {
    val own = data.traces.filter { it.origin == "this" }
    val out = mutableListOf<TraceLoc>()
    own.groupBy { (it.category ?: "Other").trim().ifBlank { "Other" } }
        .forEach { (cat, list) ->
            val catSafe = safeName(cat)
            list.forEachIndexed { idx, t ->
                out += TraceLoc(
                    providerLabel = providerLabelFor(t.hostname),
                    model = t.model,
                    category = t.category,
                    zipPath = "$jsonRoot$catSafe/${traceDirName(idx, t)}/index.html"
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

// ===== Report index (reverse of TraceLoc) — every report/secondary
//      page keyed by its (provider, model, category, origin) so a
//      trace page can link back to the page it was built for. =====

private data class ReportLoc(
    val providerLabel: String,
    val model: String?,
    val category: String,        // "Report" / "Report rerank" / etc.
    val zipPath: String,         // zip-root-relative, includes any "Source/" prefix
    val origin: String           // "this" or "source"
)

private fun buildReportIndex(data: HtmlReportData, basePath: String, origin: String): List<ReportLoc> {
    val out = mutableListOf<ReportLoc>()
    data.agents.forEachIndexed { idx, a ->
        val filename = itemFilename(idx, "${a.providerDisplay}_${a.model}")
        out += ReportLoc(a.providerDisplay, a.model, "Report", "${basePath}Reports/$filename", origin)
    }
    // Chat-type META rows: one section per user-given prompt name.
    // Trace category matches the runMetaPrompt tag — "Report meta:
    // <name>" — so the bug-link picker can find the right trace file
    // for each card. First-seen order preserved so directory listing
    // matches the in-app history.
    val metaByName = LinkedHashMap<String, MutableList<HtmlSecondaryData>>()
    data.secondary.filter { it.kind == SecondaryKind.META }.forEach { s ->
        val name = s.metaPromptName?.takeIf { it.isNotBlank() }
            ?: com.ai.data.legacyKindDisplayName(s.kind)
        metaByName.getOrPut(name) { mutableListOf() }.add(s)
    }
    metaByName.forEach { (name, items) ->
        items.forEachIndexed { idx, s ->
            val filename = itemFilename(idx, "${s.providerDisplay}_${s.model}")
            out += ReportLoc(s.providerDisplay, s.model, "Report meta: $name", "${basePath}${safeName(name)}/$filename", origin)
        }
    }
    val structuredLabels = mapOf(
        SecondaryKind.RERANK to ("Reranks" to "Report rerank"),
        SecondaryKind.MODERATION to ("Moderations" to "Report moderation")
    )
    structuredLabels.forEach { (kind, pair) ->
        val (sectionLabel, traceCategory) = pair
        val items = data.secondary.filter { it.kind == kind }
        items.forEachIndexed { idx, s ->
            val filename = itemFilename(idx, "${s.providerDisplay}_${s.model}")
            out += ReportLoc(s.providerDisplay, s.model, traceCategory, "${basePath}$sectionLabel/$filename", origin)
        }
    }
    return out
}

private fun List<ReportLoc>.findReportFor(t: HtmlTraceData): ReportLoc? {
    val cat = t.category ?: return null
    val provider = providerLabelFor(t.hostname)
    return firstOrNull { loc ->
        loc.origin == t.origin &&
            loc.category == cat &&
            loc.providerLabel == provider &&
            (t.model == null || loc.model == null || loc.model == t.model)
    }
}

/** "📄 report" link from a trace page at [pageDepth] (counts "../"
 *  hops to the zip root) back to the report/secondary page that was
 *  built from this trace — inverse of [bugLink]. */
private fun reportLink(loc: ReportLoc?, pageDepth: Int): String {
    if (loc == null) return ""
    val rel = "../".repeat(pageDepth) + loc.zipPath
    return "<a class='bug' href='${esc(rel)}' title='View report page built from this call'>📄</a>"
}

// ===== Sections present in one language directory =====

private fun languageSections(data: HtmlReportData, isOriginal: Boolean): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    if (data.prompt.isNotBlank()) out += "Prompt" to "Prompt/"
    if (isOriginal && (data.agents.any { it.inputCost != null } || data.secondary.any { it.inputTokens != null })) {
        out += "Costs" to "Costs/"
    }
    if (data.agents.isNotEmpty()) out += "Reports" to "Reports/"
    // One entry per chat-type Meta prompt name present in this
    // language view. First-seen order preserved so the link list
    // matches the on-disk directory order.
    val metaNames = LinkedHashSet<String>()
    data.secondary.filter { it.kind == SecondaryKind.META }.forEach { s ->
        val name = s.metaPromptName?.takeIf { it.isNotBlank() }
            ?: com.ai.data.legacyKindDisplayName(s.kind)
        metaNames += name
    }
    metaNames.forEach { name -> out += name to "${safeName(name)}/" }
    if (isOriginal) {
        val byKind: (SecondaryKind, String) -> Unit = { kind, label ->
            if (data.secondary.any { it.kind == kind }) out += label to "$label/"
        }
        byKind(SecondaryKind.RERANK, "Reranks")
        byKind(SecondaryKind.MODERATION, "Moderations")
        if (data.traces.isNotEmpty()) out += "JSON" to "JSON/"
    }
    return out
}

// ===== Indexes =====

/** Zip-root index page — lists each language directory and links into
 *  it. With no translations there's still a single "Original" entry. */
private fun zipRootIndex(data: HtmlReportData, languages: List<HtmlLanguageView>): String {
    val sb = StringBuilder()
    sb.append(htmlHead(title = data.title.ifBlank { "AI Report" }, depth = 0))
    sb.append("<nav>").append("<span class='here'>${esc(data.title.ifBlank { "AI Report" })}</span>").append("</nav>")
    sb.append("<main>")
    sb.append("<h1>").append(esc(data.title.ifBlank { "AI Report" })).append("</h1>")
    sb.append("<div class='meta'>").append(esc(data.timestamp)).append("</div>")
    if (!data.rapportText.isNullOrBlank()) sb.append("<div class='rapport'>${convertMarkdownToHtmlForExport(data.rapportText)}</div>")
    sb.append("<h2>Languages</h2><ul class='section-list'>")
    languages.forEach { lv ->
        val native = lv.nativeName?.let { " <span class='count'>${esc(it)}</span>" } ?: ""
        sb.append("<li><a href='${esc(lv.key)}/index.html'>").append(esc(lv.displayName)).append(native).append("</a></li>")
    }
    sb.append("</ul>")
    if (!data.closeText.isNullOrBlank()) sb.append("<div class='close-text'>${convertMarkdownToHtmlForExport(data.closeText)}</div>")
    sb.append("</main></body></html>")
    return sb.toString()
}

/** Per-language index page — lists the sections inside this language's
 *  directory. Sits at `<langKey>/index.html`. Links upward to the zip
 *  root via "../index.html". */
private fun languageIndex(data: HtmlReportData, lv: HtmlLanguageView, basePath: String, isOriginal: Boolean): String {
    val sb = StringBuilder()
    sb.append(htmlHead(title = "${lv.displayName} - ${data.title}", depth = 0, basePath = basePath))
    // Two-step breadcrumb back up to the zip root.
    sb.append("<nav>")
        .append("<a href='../index.html'>${esc(data.title.ifBlank { "AI Report" })}</a>")
        .append(" <span class='sep'>›</span> ")
        .append("<span class='here'>${esc(lv.displayName)}</span>")
        .append("</nav>")
    sb.append("<main>")
    sb.append("<h1>").append(esc(lv.displayName))
    if (lv.nativeName != null) sb.append(" <span class='count'>").append(esc(lv.nativeName)).append("</span>")
    sb.append("</h1>")
    sb.append("<h2>Sections</h2><ul class='section-list'>")
    languageSections(data, isOriginal).forEach { (label, href) ->
        sb.append("<li><a href='${esc(href)}index.html'>").append(esc(label)).append("</a></li>")
    }
    sb.append("</ul></main></body></html>")
    return sb.toString()
}

// ===== Reports section =====

private fun emitReports(zos: ZipOutputStream, data: HtmlReportData, traceIndex: List<TraceLoc>, basePath: String, langDisplay: String?) {
    val maxAnchor = data.agents.mapNotNull { it.anchorIndex }.maxOrNull() ?: 0
    val items = data.agents.mapIndexed { idx, a ->
        Triple(itemFilename(idx, "${a.providerDisplay}_${a.model}"), a.providerDisplay + " / " + a.model, a)
    }
    // Section index
    val sb = StringBuilder()
    sb.append(htmlHead("Reports - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Reports" to null), data, langDisplay))
    sb.append("<main><h1>Reports</h1><ul class='item-list'>")
    items.forEach { (filename, label, _) ->
        sb.append("<li><a href='${esc(filename)}'>").append(esc(label)).append("</a></li>")
    }
    sb.append("</ul></main></body></html>")
    emit(zos, "${basePath}Reports/index.html", sb.toString())
    // Per-agent files
    items.forEach { (filename, label, a) ->
        emit(zos, "${basePath}Reports/$filename", reportPage(label, a, data, maxAnchor, traceIndex, basePath, langDisplay))
    }
}

private fun reportPage(label: String, a: HtmlAgentData, data: HtmlReportData, maxAnchor: Int, traceIndex: List<TraceLoc>, basePath: String, langDisplay: String?): String {
    val sb = StringBuilder()
    sb.append(htmlHead("$label - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Reports" to "index.html", label to null), data, langDisplay))
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

// ===== Secondary sections (chat-type Meta by prompt name; structured
//      Reranks / Moderations) =====

/** Emit one folder per chat-type Meta prompt name, named by the
 *  user-given name (e.g. "Compare/", "Critique/"). Each folder gets
 *  an index of its rows + per-row HTML files. Trace-link lookup uses
 *  the matching "Report meta: <name>" category that runMetaPrompt
 *  tagged the trace with. */
private fun emitMetaSections(zos: ZipOutputStream, data: HtmlReportData, traceIndex: List<TraceLoc>, basePath: String, langDisplay: String?) {
    val maxAnchor = data.agents.mapNotNull { it.anchorIndex }.maxOrNull() ?: 0
    val byName = LinkedHashMap<String, MutableList<HtmlSecondaryData>>()
    data.secondary.filter { it.kind == SecondaryKind.META }.forEach { s ->
        val name = s.metaPromptName?.takeIf { it.isNotBlank() }
            ?: com.ai.data.legacyKindDisplayName(s.kind)
        byName.getOrPut(name) { mutableListOf() }.add(s)
    }
    byName.forEach { (name, items) ->
        val label = name
        val safeLabel = safeName(name)
        val withFiles = items.mapIndexed { idx, s ->
            Triple(itemFilename(idx, "${s.providerDisplay}_${s.model}"), "${s.providerDisplay} / ${s.model}", s)
        }
        val sb = StringBuilder()
        sb.append(htmlHead("$label - ${data.title}", depth = 1, basePath = basePath))
        sb.append(breadcrumb(1, listOf(label to null), data, langDisplay))
        sb.append("<main><h1>").append(esc(label)).append("</h1><ul class='item-list'>")
        withFiles.forEach { (filename, itemLabel, s) ->
            sb.append("<li><a href='${esc(filename)}'>").append(esc(itemLabel))
                .append(" <span class='ts'>").append(esc(s.timestamp)).append("</span></a></li>")
        }
        sb.append("</ul></main></body></html>")
        emit(zos, "${basePath}$safeLabel/index.html", sb.toString())
        val traceCategory = "Report meta: $name"
        withFiles.forEach { (filename, itemLabel, s) ->
            emit(zos, "${basePath}$safeLabel/$filename", secondaryPage(label, itemLabel, s, data, maxAnchor, traceIndex, traceCategory, basePath, langDisplay))
        }
    }
}

private fun emitSecondaryKind(zos: ZipOutputStream, data: HtmlReportData, kind: SecondaryKind, label: String, traceIndex: List<TraceLoc>, basePath: String, langDisplay: String?) {
    val items = data.secondary.filter { it.kind == kind }
    if (items.isEmpty()) return
    val maxAnchor = data.agents.mapNotNull { it.anchorIndex }.maxOrNull() ?: 0
    val withFiles = items.mapIndexed { idx, s ->
        Triple(itemFilename(idx, "${s.providerDisplay}_${s.model}"), "${s.providerDisplay} / ${s.model}", s)
    }
    // Section index
    val sb = StringBuilder()
    sb.append(htmlHead("$label - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf(label to null), data, langDisplay))
    sb.append("<main><h1>").append(esc(label)).append("</h1><ul class='item-list'>")
    withFiles.forEach { (filename, itemLabel, s) ->
        sb.append("<li><a href='${esc(filename)}'>").append(esc(itemLabel))
            .append(" <span class='ts'>").append(esc(s.timestamp)).append("</span></a></li>")
    }
    sb.append("</ul></main></body></html>")
    emit(zos, "${basePath}$label/index.html", sb.toString())
    // Structured kinds — fixed trace-category tags. Chat-type META is
    // routed through emitMetaSections; this branch never sees META.
    val traceCategory = when (kind) {
        SecondaryKind.RERANK -> "Report rerank"
        SecondaryKind.MODERATION -> "Report moderation"
        SecondaryKind.TRANSLATE -> "Report translate"
        SecondaryKind.META -> "Report meta"
    }
    withFiles.forEach { (filename, itemLabel, s) ->
        emit(zos, "${basePath}$label/$filename", secondaryPage(label, itemLabel, s, data, maxAnchor, traceIndex, traceCategory, basePath, langDisplay))
    }
}

private fun secondaryPage(section: String, itemLabel: String, s: HtmlSecondaryData, data: HtmlReportData, maxAnchor: Int, traceIndex: List<TraceLoc>, traceCategory: String, basePath: String, langDisplay: String?): String {
    val sb = StringBuilder()
    sb.append(htmlHead("$itemLabel - ${data.title}", depth = 1, basePath = basePath))
    // Breadcrumb hop back to the section index — the per-row file
    // sits in the same directory as the index, so a bare
    // "index.html" relative link works regardless of whether the
    // directory name was filesystem-safed.
    sb.append(breadcrumb(1, listOf(section to "index.html", itemLabel to null), data, langDisplay))
    sb.append("<main>")
    val match = traceIndex.findMatch(s.providerDisplay, s.model, traceCategory)
    sb.append("<h1>").append(esc(itemLabel)).append(bugLink(match, pageDepth = 1, basePath = basePath)).append("</h1>")
    sb.append("<div class='meta'>").append(esc(s.timestamp)).append("</div>")
    if (s.errorMessage != null) {
        sb.append("<div class='error'>Error: ${esc(s.errorMessage)}</div>")
    } else if (!s.content.isNullOrBlank()) {
        when (s.kind) {
            SecondaryKind.RERANK -> {
                val agentsByAnchor = data.agents.mapNotNull { a ->
                    a.anchorIndex?.let { it to "${a.providerDisplay} · ${a.model}" }
                }.toMap()
                sb.append("<div class='response'>${renderRerankContentLocal(s.content, maxAnchor, agentsByAnchor)}</div>")
            }
            SecondaryKind.META -> {
                // Chat-type Meta content can reference report rows via
                // bracketed [N] tags — keep them as bracketed numbers
                // here (the per-Meta page doesn't host the agent cards
                // and inter-page anchors aren't worth wiring up).
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

// ===== Prompt =====

private fun emitPrompt(zos: ZipOutputStream, data: HtmlReportData, basePath: String, langDisplay: String?) {
    val sb = StringBuilder()
    sb.append(htmlHead("Prompt - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Prompt" to null), data, langDisplay))
    sb.append("<main><h1>Prompt</h1><pre class='prompt'>").append(esc(data.prompt)).append("</pre></main></body></html>")
    emit(zos, "${basePath}Prompt/index.html", sb.toString())
}

// ===== Costs =====

private fun emitCosts(zos: ZipOutputStream, data: HtmlReportData, basePath: String, langDisplay: String?) {
    val sb = StringBuilder()
    sb.append(htmlHead("Costs - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("Costs" to null), data, langDisplay))
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
        val type = it.metaPromptName?.takeIf { n -> n.isNotBlank() }?.lowercase()
            ?: when (it.kind) {
                SecondaryKind.RERANK -> "rerank"
                SecondaryKind.META -> "meta"
                SecondaryKind.MODERATION -> "moderation"
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

/** Emit the JSON/ tree under one language directory. Only Original
 *  gets a JSON tree — translation API calls land on the source report
 *  id so they're already in the same trace set as the originals. */
private fun emitTraces(zos: ZipOutputStream, data: HtmlReportData, reportIndex: List<ReportLoc>, basePath: String, langDisplay: String?) {
    val ownTraces = data.traces.filter { it.origin == "this" }
    val ownGroups = ownTraces.groupBy { (it.category ?: "Other").trim().ifBlank { "Other" } }
    val ownKeys = ownGroups.keys.sortedWith(compareBy({ it == "Other" }, { it.lowercase() }))

    val sb = StringBuilder()
    sb.append(htmlHead("JSON traces - ${data.title}", depth = 1, basePath = basePath))
    sb.append(breadcrumb(1, listOf("JSON" to null), data, langDisplay))
    sb.append("<main><h1>JSON traces</h1>")
    if (ownKeys.isNotEmpty()) {
        sb.append("<h2>Categories</h2><ul class='item-list'>")
        ownKeys.forEach { k -> sb.append("<li><a href='").append(esc(safeName(k))).append("/index.html'>")
            .append(esc(k)).append(" <span class='count'>(").append(ownGroups[k]!!.size).append(")</span></a></li>") }
        sb.append("</ul>")
    }
    sb.append("</main></body></html>")
    emit(zos, "${basePath}JSON/index.html", sb.toString())

    // Own categories
    ownKeys.forEach { cat -> emitTraceCategory(zos, "${basePath}JSON/${safeName(cat)}", data, cat, ownGroups[cat]!!,
        depth = 2,
        basePath = basePath,
        crumbs = listOf("JSON" to "../index.html", cat to null),
        reportIndex = reportIndex,
        langDisplay = langDisplay) }
}

private fun emitTraceCategory(zos: ZipOutputStream, dirPath: String, data: HtmlReportData, category: String, traces: List<HtmlTraceData>, depth: Int, basePath: String, crumbs: List<Pair<String, String?>>, reportIndex: List<ReportLoc>, langDisplay: String?) {
    // Category index — list of traces.
    val sb = StringBuilder()
    sb.append(htmlHead("$category - ${data.title}", depth = depth, basePath = basePath))
    sb.append(breadcrumb(depth, crumbs, data, langDisplay))
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
        // Per-trace index — metadata + 4 links + a back-link to the
        // report/secondary page that was built from this call (when one
        // exists).
        val reportMatch = reportIndex.findReportFor(t)
        val tsb = StringBuilder()
        tsb.append(htmlHead("Trace ${t.method} ${t.hostname} ${t.statusCode} - ${data.title}", depth = depth + 1, basePath = basePath))
        tsb.append(breadcrumb(depth + 1, traceCrumbs, data, langDisplay))
        tsb.append("<main><h1>Trace").append(reportLink(reportMatch, depth + 1 + basePath.count { it == '/' })).append("</h1>")
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
        emit(zos, "$dirPath/$dir/request_headers.html", tracePartPage(data, t, "Request headers", t.requestHeaders, depth + 1, basePath, traceCrumbs, langDisplay))
        emit(zos, "$dirPath/$dir/request_body.html", tracePartPage(data, t, "Request body", t.requestBody, depth + 1, basePath, traceCrumbs, langDisplay))
        emit(zos, "$dirPath/$dir/response_headers.html", tracePartPage(data, t, "Response headers", t.responseHeaders, depth + 1, basePath, traceCrumbs, langDisplay))
        emit(zos, "$dirPath/$dir/response_body.html", tracePartPage(data, t, "Response body", t.responseBody, depth + 1, basePath, traceCrumbs, langDisplay))
    }
}

private fun tracePartPage(data: HtmlReportData, t: HtmlTraceData, partLabel: String, body: String, depth: Int, basePath: String, crumbs: List<Pair<String, String?>>, langDisplay: String?): String {
    // Replace the trailing "Trace" crumb with a link to the trace's
    // index so the part page can navigate up one step to the trace's
    // overview and then onwards.
    val partCrumbs = crumbs.dropLast(1) + ("Trace" to "index.html") + (partLabel to null)
    val sb = StringBuilder()
    sb.append(htmlHead("$partLabel - ${t.method} ${t.hostname} - ${data.title}", depth = depth, basePath = basePath))
    sb.append(breadcrumb(depth, partCrumbs, data, langDisplay))
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
 *  "current page" label and renders without a link. Earlier entries
 *  are rendered as links pointing at the supplied (relative) URL.
 *  When [langDisplay] is non-null an extra "language" hop is inserted
 *  between the zip-root link and the section crumbs ("AI Report >
 *  Original > Reports > Foo / Bar"). [depth] counts levels within the
 *  language directory; the zip-root link adds one extra ../ to clear
 *  the language directory. */
private fun breadcrumb(depth: Int, crumbs: List<Pair<String, String?>>, data: HtmlReportData, langDisplay: String? = null): String {
    val sb = StringBuilder()
    sb.append("<nav>")
    val toZipRoot = "../".repeat(depth + (if (langDisplay != null) 1 else 0)) + "index.html"
    sb.append("<a href='").append(esc(toZipRoot)).append("'>").append(esc(data.title.ifBlank { "AI Report" })).append("</a>")
    if (langDisplay != null) {
        sb.append(" <span class='sep'>›</span> ")
        val toLangRoot = "../".repeat(depth) + "index.html"
        sb.append("<a href='").append(esc(toLangRoot)).append("'>").append(esc(langDisplay)).append("</a>")
    }
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
/* Tables: only as wide as their content, horizontally centered, but
   never wider than the viewport (minus body padding). The position
   + left/transform trio re-centers each table on the viewport
   instead of being clipped by main's 900px max-width — narrow
   tables remain centered as before, wide tables (cost / rerank /
   etc.) can extend toward the screen edges. */
table{border-collapse:collapse;width:max-content;max-width:calc(100vw - 32px);margin:8px auto;font-size:13px;position:relative;left:50%;transform:translateX(-50%)}
th,td{padding:6px 10px;border-bottom:1px solid #333;vertical-align:top;text-align:left}
th{background:#252525;color:#9FCFFF}
.num{text-align:right;font-family:monospace}
.cost-table .total td{color:#6B9BFF;font-weight:bold;border-top:2px solid #444}
/* The .cost-table-wrap is now a thin shell — overflow-x kept as a
   safety net for absurd column counts; sizing is on the inner
   table per the general rule above. */
.cost-table-wrap{overflow-x:auto;margin:0}
.cost-table{white-space:nowrap}
.cost-table th, .cost-table td{white-space:nowrap}
.cost-table th[data-sort]:hover{background:#2c2c2c}
.md-table{width:auto;border-collapse:collapse;margin:12px 0;font-size:14px;background:#222}
.md-table th{background:#2a2a3a;color:#9FCFFF;font-weight:600;text-align:left;padding:8px 12px;border:1px solid #3a3a3a}
.md-table td{padding:8px 12px;border:1px solid #333;vertical-align:top}
.md-table tbody tr:nth-child(even){background:#262626}
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
private fun renderRerankContentLocal(content: String, maxAnchor: Int, agentsByAnchor: Map<Int, String> = emptyMap()): String {
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
            sb.append("<table><tr><th>Rank</th><th>Result</th><th>Model</th><th>Score</th><th>Reason</th></tr>")
            rows.forEach { (id, rs, reason) ->
                val rank = rs.first?.toString() ?: ""
                val score = rs.second?.let { "%.0f".format(it) } ?: ""
                val modelLabel = agentsByAnchor[id]?.let { esc(it) } ?: ""
                sb.append("<tr><td class='num'>").append(rank)
                    .append("</td><td>[").append(id).append("]</td><td>").append(modelLabel)
                    .append("</td><td class='num'>").append(score)
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
