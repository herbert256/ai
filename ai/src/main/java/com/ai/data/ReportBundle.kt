package com.ai.data

import android.content.Context
import android.net.Uri
import com.ai.data.preferences.SettingsPreferences
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Per-report export / import bundle. Used by the Housekeeping →
 * Export & Import "AI Reports" card. Produces (and consumes) a
 * zip with this layout:
 *
 * ```
 * meta.json                  — exportVersion + appVersion + counts
 * report.json                — Report JSON (verbatim from ReportStorage)
 * secondary/<resultId>.json  — every SecondaryResult bound to the report
 * traces/<filename>.json     — every ApiTrace tagged with the reportId
 * ```
 *
 * Import path always lands the bundle as a NEW report (fresh UUID
 * for the report, fresh UUIDs for each secondary, fresh trace
 * filenames via `ApiTracer.saveTrace(filename = null)`) so
 * re-importing the same zip never clobbers existing data.
 *
 * Knowledge-base file contents are intentionally NOT packed —
 * the report still lists its KB ids on import but content lookups
 * for KBs that don't exist on the target install return empty.
 * Importing KBs is a separate flow.
 */

/** Summary returned by [readReportZip] for the caller's toast.
 *  The caller computes per-install "missing entity" counts
 *  separately (it has [com.ai.model.Settings] in scope) so this
 *  helper stays free of UI-layer dependencies. */
internal data class ReportImportSummary(
    val title: String,
    val newReportId: String,
    val secondaryCount: Int,
    val traceCount: Int
)

/** One in-flight report import, surfaced as a transient row at the top of
 *  the Reports hub's "Latest AI Reports" card. NOT persisted — it lives only
 *  in [ReportImportProgress] until the import finishes and the real report
 *  (whose id equals [id]) lands on disk. [filesTotal] is 0 until the bundle
 *  has been inflated and the file count is known; the hub shows the [title]
 *  meanwhile, then switches to "Loading file X of Y". */
data class ImportInProgress(
    val id: String,
    val title: String,
    val filesDone: Int = 0,
    val filesTotal: Int = 0
)

/** Live registry of report imports in flight. The Reports hub collects
 *  [active] and renders one spinning-hourglass row per entry, with the title
 *  line reading "Loading file X of Y". [readReportZip] drives the counters;
 *  the caller brackets the whole import with [start] / [finish] so the row
 *  appears the instant the user taps Import — before any slow work. The
 *  import's [id] is reused as the new report id so the hub can hide the
 *  freshly-persisted (but still-importing) report and hand off seamlessly to
 *  the real row when [finish] removes the placeholder. */
object ReportImportProgress {
    private val _active = MutableStateFlow<List<ImportInProgress>>(emptyList())
    val active: StateFlow<List<ImportInProgress>> = _active.asStateFlow()

    fun start(id: String, title: String) = _active.update { list ->
        if (list.any { it.id == id }) list else list + ImportInProgress(id, title)
    }

    fun setTotal(id: String, total: Int) = _active.update { list ->
        list.map { if (it.id == id) it.copy(filesTotal = total) else it }
    }

    fun advance(id: String, done: Int) = _active.update { list ->
        list.map { if (it.id == id) it.copy(filesDone = done) else it }
    }

    fun finish(id: String) = _active.update { list -> list.filterNot { it.id == id } }
}

/** End-to-end report import from a content [uri]. Brackets the whole
 *  thing with a [ReportImportProgress] placeholder row (so the spinning
 *  "Loading file X of Y" row shows on the Reports hub the instant the
 *  user taps Import, before any slow per-trace / per-secondary write),
 *  inflates the zip onto a fresh report id, then counts the entities the
 *  imported report references that aren't configured locally — providers
 *  and worker (Agent) ids — so the returned toast can warn that a later
 *  Regenerate / "Run a new …" might silently skip those rows. The import
 *  id doubles as the new report id (see [readReportZip]). Returns the
 *  ready-to-toast success message; throws on a malformed bundle (the
 *  caller toasts the failure). Shared by the Housekeeping Export & Import
 *  card and the Reports hub's 📥 import icon. */
suspend fun importReportFromUri(context: Context, uri: Uri): String {
    val importId = UUID.randomUUID().toString()
    ReportImportProgress.start(importId, "report")
    try {
        return withContext(Dispatchers.IO) {
            val summary = context.contentResolver.openInputStream(uri)?.use { input ->
                readReportZip(context, input, importId)
            } ?: error("Could not open input stream")
            // Re-read the freshly-imported Report off disk so the missing-
            // entity counts reflect what's actually persisted.
            val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            val currentAgentIds = SettingsPreferences(prefs, context.filesDir)
                .loadSettings().agents.map { it.id }.toSet()
            val saved = ReportStorage.getReport(context, summary.newReportId)
            val agents = saved?.agents.orEmpty()
            val missingProviders = agents.map { it.provider }.distinct()
                .count { AppService.findById(it) == null }
            val missingAgents = agents.count { it.agentId !in currentAgentIds }
            val base = "Imported AI Report \"${summary.title}\" (${summary.secondaryCount} secondaries, ${summary.traceCount} traces)"
            base + buildString {
                if (missingProviders > 0) append(" · $missingProviders providers missing")
                if (missingAgents > 0) append(" · $missingAgents agents missing")
                if (missingProviders > 0 || missingAgents > 0) {
                    append(" — Regenerate / new sub-runs against them will be skipped")
                }
            }
        }
    } finally {
        ReportImportProgress.finish(importId)
    }
}

private const val EXPORT_VERSION = 1
private val gson = createAppGson()

/** Write a single report's bundle (report JSON + every secondary +
 *  every trace tagged with this reportId) into [out]. Caller is
 *  responsible for closing the stream — typically via the
 *  [com.ai.ui.shared.shareExport] helper which stages to cache
 *  and shares atomically. */
internal fun writeReportZip(context: Context, reportId: String, out: OutputStream) {
    val report = ReportStorage.getReport(context, reportId)
        ?: error("Report $reportId not found")
    val secondaries = SecondaryResultStorage.listForReport(context, reportId)
    val traceInfos = ApiTracer.getTraceFilesForReport(reportId)

    // Strip per-install references that can't travel cleanly inside
    // the bundle:
    //   • knowledgeBaseIds — KB blobs aren't packed, and a later
    //     regenerate on the target install would silently feed
    //     zero context to the model, producing different output
    //     with no error. Better to land an imported report with
    //     no KB attachment than to misleadingly suggest one.
    val sanitized = report.copy(knowledgeBaseIds = emptyList())

    ZipOutputStream(out).use { zip ->
        // 1) report.json — sanitised serialised Report.
        zip.writeEntry("report.json", gson.toJson(sanitized).toByteArray(Charsets.UTF_8))

        // 2) secondary/<resultId>.json — one entry per persisted row.
        for (sec in secondaries) {
            zip.writeEntry("secondary/${sec.id}.json",
                gson.toJson(sec).toByteArray(Charsets.UTF_8))
        }

        // 3) traces/<originalFilename>.json — original filename
        //    preserved so the receiver can see when each trace was
        //    written. On import we re-mint filenames anyway, but
        //    preserving the original here keeps the zip readable
        //    out-of-band.
        var traceCount = 0
        for (info in traceInfos) {
            val raw = ApiTracer.readTraceFileRaw(info.filename) ?: continue
            zip.writeEntry("traces/${info.filename}", raw.toByteArray(Charsets.UTF_8))
            traceCount++
        }

        // 4) meta.json — last, after counts are known.
        val meta = JsonObject().apply {
            addProperty("exportVersion", EXPORT_VERSION)
            addProperty("appVersion", appVersionName(context))
            addProperty("originalReportId", report.id)
            addProperty("originalTitle", report.title)
            addProperty("secondaryCount", secondaries.size)
            addProperty("traceCount", traceCount)
            addProperty("exportedAt", System.currentTimeMillis())
        }
        zip.writeEntry("meta.json", gson.toJson(meta).toByteArray(Charsets.UTF_8))
    }
}

/** Read a per-report zip from [input], persist it as a NEW report
 *  on this install, and return a summary for the caller's toast.
 *  Always mints a fresh report UUID + fresh secondary UUIDs + fresh
 *  trace filenames so re-importing the same zip is safe — the
 *  user just ends up with duplicates. */
internal fun readReportZip(
    context: Context,
    input: InputStream,
    // When non-null, [readReportZip] drives the matching [ReportImportProgress]
    // row (file counter) AND mints the new report under this exact id, so the
    // hub can correlate the placeholder row with the report that lands. The
    // caller is responsible for the bracketing start()/finish() calls.
    importId: String? = null
): ReportImportSummary {
    // Per-report bundles are small (low-MB at worst). Buffer the
    // whole zip into memory so we can read entries in any order;
    // ZipInputStream is single-pass otherwise.
    val entries = mutableMapOf<String, ByteArray>()
    var totalBytes = 0L
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                if (entries.size >= MAX_BUNDLE_ENTRIES)
                    error("Bundle has too many entries (> $MAX_BUNDLE_ENTRIES)")
                // Read the INFLATED stream with a per-entry cap so a zip-bomb
                // entry can't exhaust memory before validation. See audit data bug 4.
                val bytes = readBytesCapped(zip, MAX_BUNDLE_ENTRY_BYTES)
                totalBytes += bytes.size
                if (totalBytes > MAX_BUNDLE_TOTAL_BYTES)
                    error("Bundle is too large (> ${MAX_BUNDLE_TOTAL_BYTES / (1024 * 1024)} MB inflated)")
                entries[entry.name] = bytes
            }
            zip.closeEntry()
        }
    }

    // The bundle is inflated — we now know how many files will be persisted
    // (every trace + every secondary + the report itself). Publish the total
    // so the hub row flips from "Loading <title>…" to "Loading file X of Y".
    // The per-file writes below are the slow part (one atomic write each), so
    // we tick the counter as each one lands.
    var filesDone = 0
    if (importId != null) {
        val secondaryEntryCount = entries.keys.count { it.startsWith("secondary/") && it.endsWith(".json") }
        val traceEntryCount = entries.keys.count { it.startsWith("traces/") && it.endsWith(".json") }
        ReportImportProgress.setTotal(importId, secondaryEntryCount + traceEntryCount + 1)
    }
    fun tick() { if (importId != null) ReportImportProgress.advance(importId, ++filesDone) }

    val metaBytes = entries["meta.json"]
        ?: error("Missing meta.json — not a valid AI Report bundle")
    // A non-object root (`.asJsonObject`) or a non-numeric exportVersion
    // (`.asInt`) would otherwise throw a generic Gson exception past the
    // controlled `error()` import path below.
    val metaRoot = runCatching { JsonParser.parseString(String(metaBytes, Charsets.UTF_8)) }.getOrNull()
    if (metaRoot == null || !metaRoot.isJsonObject) {
        error("Malformed meta.json — not a valid AI Report bundle")
    }
    val meta = metaRoot.asJsonObject
    val version = meta.get("exportVersion")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
    if (version !in 1..EXPORT_VERSION) {
        error("Unsupported export version: $version (this install accepts 1..$EXPORT_VERSION)")
    }

    val reportBytes = entries["report.json"]
        ?: error("Missing report.json")
    val parsedReport = gson.fromJson(String(reportBytes, Charsets.UTF_8), Report::class.java)
        ?: error("report.json could not be parsed as a Report")

    // Re-key the report onto a fresh UUID so we never clobber an
    // existing same-id report on this install. Report.id is val so
    // we go through data-class .copy. Also re-stamp the timestamp to
    // "now" so a freshly imported report (Housekeeping or an example)
    // sorts to the top of the timestamp-descending report lists.
    // When an [importId] was supplied, reuse it verbatim — it's already a
    // fresh UUID, and matching the report id to the progress key lets the hub
    // hide the still-importing report behind its placeholder row.
    val newReportId = importId ?: UUID.randomUUID().toString()

    // Pass 1 — assign a fresh id to every secondary up front, so the
    // cross-references that point at secondary ids (a TRANSLATE row's
    // translateSourceTargetId, a fan-out pair's iconCalls.agentId, and
    // any IconCallRecord.attributedToSecondaryId) can be rewritten onto
    // the NEW ids before anything is persisted. Without this remap the
    // imported report's language tabs and alt-cost attribution silently
    // reference ids that no longer exist on this install.
    val parsedSecondaries = entries.entries
        .filter { it.key.startsWith("secondary/") && it.key.endsWith(".json") }
        .mapNotNull { (key, bytes) ->
            // Contain a single malformed secondary so it skips instead of
            // aborting the whole import. See audit data bug 6.
            try { gson.fromJson(String(bytes, Charsets.UTF_8), SecondaryResult::class.java) }
            catch (e: Exception) { AppLog.w("ImportExport", "skipped bad secondary $key: ${e.message}"); null }
        }
    val secIdMap: Map<String, String> =
        parsedSecondaries.associate { it.id to UUID.randomUUID().toString() }
    // Fresh translationRunId per imported run group. _translationRuns is
    // keyed GLOBALLY by runId, so reusing the bundle's ids would collide
    // with an existing run on this install (or a second import of the same
    // bundle). One new id per distinct old runId keeps each run's rows
    // grouped together.
    val translateRunIdMap: Map<String, String> =
        parsedSecondaries.mapNotNull { it.translationRunId?.takeIf { id -> id.isNotBlank() } }
            .distinct()
            .associateWith { UUID.randomUUID().toString() }

    // Pass 2 — import every trace under a freshly-minted filename and
    // record old→new so the rows' traceFile pointers can be rewritten
    // to point at the file that actually landed. saveTrace returns null
    // when tracing is off (nothing written) or the write failed — only
    // those that truly landed are mapped and counted, so the toast can't
    // claim traces were imported when none were.
    var traceCount = 0
    val traceFileMap = mutableMapOf<String, String>()
    entries.entries
        .filter { it.key.startsWith("traces/") && it.key.endsWith(".json") }
        .forEach { (key, bytes) ->
            // Count every trace entry handled (even a malformed skip) so the
            // progress counter advances monotonically toward the total.
            tick()
            // Contain a single malformed trace so it skips instead of aborting
            // the whole import. See audit data bug 6.
            val parsed = try { gson.fromJson(String(bytes, Charsets.UTF_8), ApiTrace::class.java) }
                catch (e: Exception) { AppLog.w("ImportExport", "skipped bad trace $key: ${e.message}"); return@forEach }
                ?: return@forEach
            val newName = ApiTracer.saveTrace(parsed.copy(reportId = newReportId), filename = null)
                ?: return@forEach
            // Zip entry is "traces/<originalFilename>"; the basename is
            // exactly the value stored in the rows' traceFile fields.
            traceFileMap[key.removePrefix("traces/")] = newName
            traceCount++
        }

    // A trace pointer with no imported file (tracing was off, or it
    // wasn't in the bundle) becomes null — a blank 🐞 beats a dead link
    // to a filename that never existed on this install.
    fun remapTrace(old: String?): String? = old?.let { traceFileMap[it] }
    // Pass 3a — persist the report with every trace pointer + secondary
    // cross-reference remapped onto the new ids.
    val remappedAgents = parsedReport.agents.map { a ->
        a.copy(
            traceFile = remapTrace(a.traceFile),
            iconTraceFile = remapTrace(a.iconTraceFile),
            modelTitleTraceFile = remapTrace(a.modelTitleTraceFile)
        )
    }.toMutableList()
    val remappedIconCalls = parsedReport.iconCalls.map { c ->
        c.copy(
            // agentId is a fan-out PAIR id (a secondary, in secIdMap) or a
            // real agent id (kept — agents keep their ids on import).
            agentId = secIdMap[c.agentId] ?: c.agentId,
            // attributedToSecondaryId is ALWAYS a secondary ref → null when
            // its target wasn't in the bundle (e.g. a row that failed to
            // parse) rather than a dead id, so alt-cost attribution doesn't
            // silently point at a secondary that doesn't exist here.
            attributedToSecondaryId = c.attributedToSecondaryId?.let { secIdMap[it] }
        )
    }.toMutableList()
    // User notes point at ids that the import re-keys: a REPORT note targets
    // the report id, a SECONDARY note a secondary id, a FANOUT_RUN note a
    // runKey(reportId, metaPromptId). Remap them or the notes orphan and
    // render as "Deleted item". AGENT notes need no remap (agents keep ids).
    val remappedNotes = parsedReport.userNotes.map { note ->
        when (note.targetKind) {
            "REPORT" -> note.copy(targetId = newReportId)
            "SECONDARY" -> note.copy(targetId = secIdMap[note.targetId] ?: note.targetId)
            "FANOUT_RUN" -> note.copy(targetId = runKey(newReportId, note.targetId.substringAfter("|", "")))
            else -> note
        }
    }.toMutableList()
    val report = parsedReport.copy(
        id = newReportId,
        timestamp = System.currentTimeMillis(),
        agents = remappedAgents,
        userNotes = remappedNotes,
        iconCalls = remappedIconCalls,
        iconTraceFile = remapTrace(parsedReport.iconTraceFile),
        titleTraceFile = remapTrace(parsedReport.titleTraceFile),
        titleLongTraceFile = remapTrace(parsedReport.titleLongTraceFile),
        languageTraceFile = remapTrace(parsedReport.languageTraceFile),
        languageIconTraceFile = remapTrace(parsedReport.languageIconTraceFile)
    )
    ReportStorage.persistNewReport(context, report)
    tick()

    // Pass 3b — persist every secondary onto its new id, with reportId,
    // translate cross-link, and trace pointer remapped.
    var secondaryCount = 0
    parsedSecondaries.forEach { parsed ->
        tick()
        val rekeyed = parsed.copy(
            id = secIdMap.getValue(parsed.id),
            reportId = newReportId,
            // Pass through the targets that are NOT secondary ids: AGENT /
            // AGENT_TITLE carry an agent id (agents keep their ids on import),
            // PROMPT / TITLE / TITLE_LONG carry a literal sentinel
            // ("prompt"/"title"/"titleLong"). Only META and FANOUT_TITLE point
            // at a secondary id → remap via secIdMap (null when the source
            // wasn't in the bundle so the drill-in doesn't chase a dead id).
            // Previously the else-branch ran secIdMap on the sentinels /
            // agent ids and nulled them, destroying title-translation links.
            translateSourceTargetId = when (parsed.translateSourceKind) {
                "AGENT", "AGENT_TITLE", "PROMPT", "TITLE", "TITLE_LONG" -> parsed.translateSourceTargetId
                else -> parsed.translateSourceTargetId?.let { secIdMap[it] }
            },
            translationRunId = parsed.translationRunId?.let { translateRunIdMap[it] ?: it },
            traceFile = remapTrace(parsed.traceFile)
        )
        SecondaryResultStorage.save(context, rekeyed)
        secondaryCount++
    }

    return ReportImportSummary(
        title = report.title,
        newReportId = newReportId,
        secondaryCount = secondaryCount,
        traceCount = traceCount
    )
}

private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

// Import resource caps — a report bundle is low-MB at worst, so these only ever
// reject pathological / zip-bomb inputs. See audit data bug 4.
private const val MAX_BUNDLE_ENTRIES = 100_000
private const val MAX_BUNDLE_ENTRY_BYTES = 64L * 1024 * 1024     // 64 MB inflated, per entry
private const val MAX_BUNDLE_TOTAL_BYTES = 256L * 1024 * 1024    // 256 MB inflated, whole bundle

/** Read the (inflated) stream into memory, aborting once [cap] bytes are
 *  exceeded so a high-ratio zip-bomb entry can't exhaust the heap. */
private fun readBytesCapped(stream: InputStream, cap: Long): ByteArray {
    val buf = ByteArray(64 * 1024)
    val out = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = stream.read(buf)
        if (n <= 0) break
        total += n
        if (total > cap) error("Bundle entry exceeds the ${cap / (1024 * 1024)} MB per-entry limit")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun appVersionName(context: Context): String = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (_: Exception) { "?" }

// ---------------------------------------------------------------------------
// Bundled example reports
//
// The APK ships a few ready-made report bundles under assets/examples/, indexed
// by assets/examples/index.xml. The Reports hub lists them and imports one on
// first open (same [readReportZip] path as a user-supplied zip — fresh UUIDs,
// nothing clobbered). index.xml is authored alongside the zips; we parse it with
// the platform XmlPullParser rather than adding a JSON sidecar.
// ---------------------------------------------------------------------------

/** One row of assets/examples/index.xml — the title + icon shown on the
 *  Reports hub and the asset zip to import when the example is opened. */
internal data class ExampleEntry(
    val title: String,
    val icon: String,
    val zipFile: String
)

/** Parse assets/examples/index.xml. Returns [] on any error (missing file,
 *  malformed XML) — the example card simply hides itself when empty. */
internal fun loadExampleIndex(context: Context): List<ExampleEntry> = try {
    context.assets.open("examples/index.xml").use { input ->
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val out = mutableListOf<ExampleEntry>()
        var title = ""; var icon = ""; var zip = ""
        var text = ""
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG ->
                    if (parser.name == "example") { title = ""; icon = ""; zip = "" }
                org.xmlpull.v1.XmlPullParser.TEXT -> text = parser.text
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                    "report_title" -> title = text.trim()
                    "report_icon" -> icon = text.trim()
                    "zip_file" -> zip = text.trim()
                    "example" -> if (zip.isNotBlank()) out.add(ExampleEntry(title, icon, zip))
                }
            }
            event = parser.next()
        }
        out
    }
} catch (_: Exception) { emptyList() }

/** Import a bundled example zip from assets/examples/ as a new report.
 *  Reuses [readReportZip], so the example lands with a fresh report id. */
internal fun importExampleReport(context: Context, zipFile: String, importId: String? = null): ReportImportSummary =
    context.assets.open("examples/$zipFile").use { readReportZip(context, it, importId) }
