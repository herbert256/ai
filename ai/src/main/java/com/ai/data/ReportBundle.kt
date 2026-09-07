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
            // Direct-model / swarm rows carry a synthetic "swarm:provider:model"
            // agentId that never appears in Settings.agents — and the
            // regenerate path parses those ids itself without needing an
            // Agent record. Counting them as "missing" falsely warned on
            // every such row (the common case) even on the install that
            // created the bundle. Only real saved-Agent rows can be missing.
            val missingAgents = agents.count {
                !it.agentId.startsWith("swarm:") && it.agentId !in currentAgentIds
            }
            val base = "Imported AI Report \"${summary.title}\" (${summary.secondaryCount} secondaries, ${summary.traceCount} traces)"
            base + buildString {
                if (missingProviders > 0) append(" · $missingProviders providers missing")
                if (missingAgents > 0) append(" · $missingAgents agents missing")
                if (missingProviders > 0 || missingAgents > 0) {
                    append(" — saved model identities are preserved; configure credentials for replay")
                }
            }
        }
    } finally {
        ReportImportProgress.finish(importId)
    }
}

private const val EXPORT_VERSION = 2
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
        zip.writeEntry("report.json", ReportExportRedaction.json(gson.toJson(sanitized)).toByteArray(Charsets.UTF_8))

        // 2) secondary/<resultId>.json — one entry per persisted row.
        for (sec in secondaries) {
            zip.writeEntry("secondary/${sec.id}.json",
                ReportExportRedaction.json(gson.toJson(sec)).toByteArray(Charsets.UTF_8))
        }

        // 3) traces/<originalFilename>.json — original filename
        //    preserved so the receiver can see when each trace was
        //    written. On import we re-mint filenames anyway, but
        //    preserving the original here keeps the zip readable
        //    out-of-band.
        var traceCount = 0
        for (info in traceInfos) {
            val raw = ApiTracer.readTraceFileRaw(info.filename) ?: continue
            zip.writeEntry("traces/${info.filename}", ReportExportRedaction.json(raw).toByteArray(Charsets.UTF_8))
            traceCount++
        }

        val evidence = ReportEvidenceStore.files(reportId)
        evidence.forEach { file -> zip.writeEntry("evidence/${file.name}", ReportExportRedaction.json(file.readText()).toByteArray(Charsets.UTF_8)) }
        // 4) meta.json — last, after counts are known.
        val meta = JsonObject().apply {
            addProperty("exportVersion", EXPORT_VERSION)
            addProperty("appVersion", appVersionName(context))
            addProperty("originalReportId", report.id)
            addProperty("originalTitle", report.title)
            addProperty("secondaryCount", secondaries.size)
            addProperty("evidenceCount", evidence.size)
            addProperty("traceCount", traceCount)
            addProperty("exportedAt", System.currentTimeMillis())
        }
        zip.writeEntry("meta.json", gson.toJson(meta).toByteArray(Charsets.UTF_8))
    }
}

private val activeReportImports = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    val newReportId = importId ?: UUID.randomUUID().toString()
    require(newReportId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid import identity" }
    require(!ReportStorage.reportFileExists(context, newReportId)) { "Import identity already exists" }
    val stage = java.io.File(context.cacheDir, "report-import-$newReportId")
    val journal = java.io.File(context.filesDir, "report_import_journal/$newReportId")
    val entries = mutableMapOf<String, java.io.File>()
    var committed = false
    require(activeReportImports.add(newReportId)) { "This report import is already in progress" }
    try {
    // Another import may have committed between the first existence check
    // and acquiring this destination identity.
    require(!ReportStorage.reportFileExists(context, newReportId)) { "Import identity already exists" }
    require(stage.mkdirs() || stage.isDirectory) { "Cannot create import staging directory" }
    require(journal.parentFile!!.mkdirs() || journal.parentFile!!.isDirectory) { "Cannot create import journal" }
    var totalBytes = 0L
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                require(entries.size < MAX_BUNDLE_ENTRIES) { "Bundle has too many entries" }
                require(entry.name !in entries) { "Duplicate bundle entry: ${entry.name}" }
                require(entry.name.matches(Regex("(?:report|meta)\\.json|(?:secondary|traces|evidence)/[A-Za-z0-9_.-]+\\.json"))) { "Unrecognized or unsafe bundle entry: ${entry.name}" }
                val target = java.io.File(stage, "entry_${entries.size}")
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024); var entryBytes = 0L
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        entryBytes += n; totalBytes += n
                        require(entryBytes <= MAX_BUNDLE_ENTRY_BYTES && totalBytes <= MAX_BUNDLE_TOTAL_BYTES) { "Bundle exceeds import size limits" }
                        out.write(buffer, 0, n)
                    }
                }
                entries[entry.name] = target
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
    val metaRoot = runCatching { JsonParser.parseString(metaBytes.readText()) }.getOrNull()
    if (metaRoot == null || !metaRoot.isJsonObject) {
        error("Malformed meta.json — not a valid AI Report bundle")
    }
    val meta = metaRoot.asJsonObject
    val version = bundleInteger(meta, "exportVersion")
    if (version !in 1..EXPORT_VERSION) {
        error("Unsupported export version: $version (this install accepts 1..$EXPORT_VERSION)")
    }

    val reportBytes = entries["report.json"]
        ?: error("Missing report.json")
    val reportJson = bundleObject(JsonParser.parseString(reportBytes.readText()), "report.json")
    validateBundleReport(reportJson)
    val parsedReport = gson.fromJson(reportJson, Report::class.java)?.let(ReportStorage::normalizeReport)
        ?: error("report.json could not be parsed as a Report")

    require((parsedReport.id as String?) != null && (parsedReport.title as String?) != null && (parsedReport.prompt as String?) != null && (parsedReport.agents as List<*>?) != null) { "Malformed report fields" }
    // Re-key the report onto a fresh UUID so we never clobber an
    // existing same-id report on this install. Report.id is val so
    // we go through data-class .copy. Also re-stamp the timestamp to
    // "now" so a freshly imported report (Housekeeping or an example)
    // sorts to the top of the timestamp-descending report lists.
    // When an [importId] was supplied, reuse it verbatim — it's already a
    // fresh UUID, and matching the report id to the progress key lets the hub
    // hide the still-importing report behind its placeholder row.
    // Pass 1 — assign a fresh id to every secondary up front, so the
    // cross-references that point at secondary ids (a TRANSLATE row's
    // translateSourceTargetId, a fan-out pair's iconCalls.agentId, and
    // any IconCallRecord.attributedToSecondaryId) can be rewritten onto
    // the NEW ids before anything is persisted. Without this remap the
    // imported report's language tabs and alt-cost attribution silently
    // reference ids that no longer exist on this install.
    val parsedSecondaries = entries.entries
        .filter { it.key.startsWith("secondary/") && it.key.endsWith(".json") }
        .map { (key, bytes) ->
            val json = bundleObject(JsonParser.parseString(bytes.readText()), key)
            bundleStrings(json, key, "id", "reportId", "providerId", "model", "agentName")
            bundleEnum(json, "kind", SecondaryKind.entries.map { it.name })
            validateBundleExecution(json)
            val row = gson.fromJson(json, SecondaryResult::class.java) ?: error("Malformed secondary: $key")
            require((row.id as String?) != null && row.id.isNotBlank() && (row.kind as SecondaryKind?) != null && row.reportId == parsedReport.id) { "Mismatched secondary identity: $key" }
            row
        }
    require(parsedSecondaries.map { it.id }.distinct().size == parsedSecondaries.size) { "Duplicate secondary IDs" }
    require(parsedReport.agents.map { it.agentId }.distinct().size == parsedReport.agents.size) { "Duplicate Agent IDs" }
    require(parsedSecondaries.size == bundleInteger(meta, "secondaryCount")) { "Secondary count does not match manifest" }
    val traceEntries = entries.filterKeys { it.startsWith("traces/") }
    require(traceEntries.size == bundleInteger(meta, "traceCount")) { "Trace count does not match manifest" }
    traceEntries.forEach { (key,file) ->
        val trace = gson.fromJson(ReportExportRedaction.json(file.readText()), ApiTrace::class.java) ?: error("Malformed trace: $key")
        require(trace.hostname.isNotBlank() && (trace.request as Any?) != null && (trace.response as Any?) != null) { "Malformed trace: $key" }
    }
    val evidenceEntries = entries.filterKeys { it.startsWith("evidence/") }
    if (version >= 2) require(evidenceEntries.size == bundleInteger(meta, "evidenceCount")) { "Evidence count does not match manifest" }
    evidenceEntries.forEach { (key, file) ->
        val json = bundleObject(JsonParser.parseString(file.readText()), key)
        if (key.substringAfter('/').startsWith("run_")) {
            bundleStrings(json, key, "sourceSnapshotId")
            require(evidenceEntries.containsKey("evidence/${json.get("sourceSnapshotId").asString}.json")) { "Missing run source snapshot" }
            val prompt = bundleObject(json.get("prompt"), "$key prompt")
            bundleStrings(prompt, key, "id", "name", "text")
        } else {
            bundleStrings(json, key, "prompt", "title")
            bundleObjects(json, "answers", required = true).forEach {
                bundleStrings(it, key, "id", "name", "provider", "model", "body")
            }
            json.get("secondaryBodies")?.let { value ->
                val bodies = bundleObject(value, "$key secondaryBodies")
                bodies.entrySet().forEach { (_, body) -> require(body.isJsonPrimitive && body.asJsonPrimitive.isString) { "Malformed secondary evidence body" } }
            }
        }
    }
    // Validate the core rows before staging final locations. A journal lets startup
    // remove interrupted imports; the final report JSON is the commit marker.
    require(journal.writeTextAtomic("prepared")) { "Cannot stage import transaction" }
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
    // Fresh trace-run runId per distinct old id, same GLOBAL-keying reason as
    // translationRunId: ApiTracer.getTraceFilesForRun filters by runId alone,
    // so reusing the bundle's runIds would make the original report's (and a
    // re-import's) run-filtered 🐞 trace lists mix both reports' calls.
    // Lazily populated so every run-id source (report, agents, secondaries'
    // runId/iconRunId/titleRunId/tournamentJudgeRunId/compareRunId, and the
    // traces themselves) maps consistently. Blank/null pass through.
    val runIdMap = translateRunIdMap.toMutableMap()
    fun remapRunId(old: String?): String? =
        old?.takeIf { it.isNotBlank() }?.let { runIdMap.getOrPut(it) { UUID.randomUUID().toString() } } ?: old

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
            val parsed = gson.fromJson(ReportExportRedaction.json(bytes.readText()), ApiTrace::class.java)
            val newName = "import_${newReportId}_${UUID.randomUUID()}.json"
            ApiTracer.init(context)
            require(ApiTracer.saveTrace(parsed.copy(reportId = newReportId, runId = remapRunId(parsed.runId)), filename = newName, importExisting = true) != null) { "Could not persist imported trace" }
            // Zip entry is "traces/<originalFilename>"; the basename is
            // exactly the value stored in the rows' traceFile fields.
            traceFileMap[key.removePrefix("traces/")] = newName
            traceCount++
        }

    // A trace pointer with no imported file (tracing was off, or it
    // wasn't in the bundle) becomes null — a blank 🐞 beats a dead link
    // to a filename that never existed on this install.
    fun remapTrace(old: String?): String? = old?.let { traceFileMap[it] }
    fun remapMetaAttempt(attempt: FanMetaAttempt) = attempt.copy(
        runId = remapRunId(attempt.runId) ?: attempt.runId,
        traceFile = remapTrace(attempt.traceFile))
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
    var report = parsedReport.copy(
        id = newReportId,
        timestamp = System.currentTimeMillis(),
        agents = remappedAgents,
        userNotes = remappedNotes,
        iconCalls = remappedIconCalls,
        unattributedFanMetaAttempts = parsedReport.unattributedFanMetaAttempts.orEmpty().map(::remapMetaAttempt),
        // The per-call cost ledger rows carry 🐞 traceFile pointers too —
        // verbatim copies referenced filenames that were never created here
        // (imported traces are re-minted under fresh names), or on a
        // same-install re-import the ORIGINAL report's traces, which die
        // with it. Remap like every other trace pointer; a blank 🐞 beats a
        // dead link.
        apiCallCosts = parsedReport.apiCallCosts.map { it.copy(traceFile = remapTrace(it.traceFile)) }.toMutableList(),
        iconTraceFile = remapTrace(parsedReport.iconTraceFile),
        titleTraceFile = remapTrace(parsedReport.titleTraceFile),
        titleLongTraceFile = remapTrace(parsedReport.titleLongTraceFile),
        languageTraceFile = remapTrace(parsedReport.languageTraceFile),
        languageIconTraceFile = remapTrace(parsedReport.languageIconTraceFile),
        runId = remapRunId(parsedReport.runId)
    )
    // Stage secondaries until every one has been serialized successfully.
    val stagedSecondary = java.io.File(stage, "secondary").apply { mkdirs() }
    val evidenceIdMap = mutableMapOf<String,String>()
    evidenceEntries.filterKeys { !it.substringAfter('/').startsWith("run_") }.forEach { (key, file) ->
        val snapshot = gson.fromJson(file.readText(), ReportSourceSnapshot::class.java) ?: error("Malformed evidence: $key")
        val rewritten = snapshot.copy(secondaryBodies = snapshot.secondaryBodies.mapKeys { (id,_) -> secIdMap[id] ?: id })
        val json = gson.toJson(rewritten); val hash = ReportEvidenceStore.digest(json)
        evidenceIdMap[key.substringAfter('/').removeSuffix(".json")] = hash
        ReportEvidenceStore.importFile(context,newReportId,hash,json)
    }
    evidenceEntries.filterKeys { it.substringAfter('/').startsWith("run_") }.forEach { (key,file) ->
        val oldId = key.substringAfter("run_").removeSuffix(".json")
        val suffix = listOf("_text","_title").firstOrNull { oldId.endsWith(it) }.orEmpty()
        val base = oldId.removeSuffix(suffix)
        val newId = (translateRunIdMap[base] ?: remapRunId(base)) + suffix
        val manifest = gson.fromJson(file.readText(), ReportRunManifest::class.java) ?: error("Malformed run manifest")
        val sourceId = evidenceIdMap[manifest.sourceSnapshotId] ?: error("Missing run source snapshot")
        ReportEvidenceStore.importFile(context,newReportId,"run_$newId",gson.toJson(manifest.copy(sourceSnapshotId=sourceId)))
    }

    report = report.copy(conclusion = report.conclusion?.let { decision -> decision.copy(
        sourceId = if (decision.sourceKind == "meta") secIdMap[decision.sourceId] ?: decision.sourceId else decision.sourceId,
        snapshotId = evidenceIdMap[decision.snapshotId] ?: error("Missing conclusion source snapshot")
    ) })

    // Pass 3b — persist every secondary onto its new id, with reportId,
    // translate cross-link, and trace pointer remapped.
    var secondaryCount = 0
    parsedSecondaries.forEach { parsed ->
        tick()
        val rekeyed = parsed.copy(
            id = secIdMap.getValue(parsed.id),
            sourceSnapshotId = parsed.sourceSnapshotId?.let { evidenceIdMap[it] ?: error("Missing source snapshot: $it") },
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
            traceFile = remapTrace(parsed.traceFile),
            // Re-mint the batch/trace run ids too (globally keyed) so the
            // imported rows' run-filtered trace lists and batch grouping
            // don't collide with the source report's.
            runId = remapRunId(parsed.runId),
            iconRunId = remapRunId(parsed.iconRunId),
            titleRunId = remapRunId(parsed.titleRunId),
            fanMetaAttempts = parsed.fanMetaAttempts.orEmpty().map(::remapMetaAttempt),
            tournamentJudgeRunId = remapRunId(parsed.tournamentJudgeRunId),
            compareRunId = remapRunId(parsed.compareRunId),
            // compareToResultId is a secondary id too: the meta row a
            // COMPARE cell was scored against / the TRANSLATE row a
            // TRANSRANK cell ranks. Verbatim copies pointed every imported
            // cell at the OLD ids, so per-meta averages computed over an
            // empty set and drill-ins couldn't resolve their scored-against
            // text. Null (not a dead id) when the target wasn't bundled.
            compareToResultId = parsed.compareToResultId?.let { secIdMap[it] }
        )
        require(java.io.File(stagedSecondary, "${rekeyed.id}.json").writeTextAtomic(gson.toJson(rekeyed))) { "Could not stage imported secondary" }
        secondaryCount++
    }
    val secondaryTarget = java.io.File(context.filesDir, "secondary/$newReportId")
    secondaryTarget.parentFile?.mkdirs()
    require(!secondaryTarget.exists() && stagedSecondary.renameTo(secondaryTarget)) { "Could not commit imported secondary files" }
    require(ReportStorage.persistNewReport(context, report, recoverOnFailure = false)) { "Could not commit imported report" }
    committed = true
    tick()
    return ReportImportSummary(
        title = report.title,
        newReportId = newReportId,
        secondaryCount = secondaryCount,
        traceCount = traceCount
    )
    } finally {
        try {
            stage.deleteRecursively()
            if (!committed) rollbackReportImport(context, newReportId)
            journal.delete()
        } finally { activeReportImports.remove(newReportId) }
    }
}

/** Validate before Gson's permissive coercions can turn malformed input into
 * null non-null fields or silently truncate a fractional version/count. */
private fun bundleObject(value: com.google.gson.JsonElement?, label: String): JsonObject {
    require(value != null && value.isJsonObject) { "Malformed $label: expected an object" }
    return value.asJsonObject
}
private fun bundleInteger(json: JsonObject, key: String): Int {
    val value = json.get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid manifest $key" }
    val number = try { value.asBigDecimal.intValueExact() }
        catch (_: ArithmeticException) { error("Invalid manifest $key: expected a whole number") }
    require(number >= 0) { "Invalid manifest $key: must not be negative" }
    return number
}
private fun bundleStrings(json: JsonObject, label: String, vararg keys: String) {
    keys.forEach { key ->
        val value = json.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Malformed $label: $key must be text" }
    }
}
private fun bundleObjects(json: JsonObject, key: String, required: Boolean = false): List<JsonObject> {
    val value = json.get(key) ?: run { require(!required) { "Missing $key" }; return emptyList() }
    require(value.isJsonArray) { "Malformed $key: expected a list" }
    return value.asJsonArray.map { bundleObject(it, "$key item") }
}
private fun bundleEnum(json: JsonObject, key: String, names: List<String>, default: String? = null) {
    if (!json.has(key) && default != null) json.addProperty(key, default)
    bundleStrings(json, key, key)
    require(json.get(key).asString in names) { "Unknown $key: ${json.get(key)}" }
}
private fun validateBundleExecution(json: JsonObject) {
    json.get("executionConfig")?.takeUnless { it.isJsonNull }?.let {
        val config = bundleObject(it, "executionConfig")
        bundleStrings(config, "executionConfig", "endpointUrl", "prompt")
        bundleObject(config.get("parameters"), "execution parameters")
    }
}
private fun validateBundleReport(json: JsonObject) {
    bundleStrings(json, "report", "id", "title", "prompt")
    require(json.get("id").asString.isNotBlank()) { "Missing report identity" }
    bundleEnum(json, "reportType", ReportType.entries.map { it.name }, ReportType.CLASSIC.name)
    require(!json.has("_imageBase64") && !json.has("_knowledgeContext")) { "Bundle contains unresolved local content references" }
    bundleObjects(json, "agents", required = true).forEach { agent ->
        bundleStrings(agent, "agent", "agentId", "agentName", "provider", "model")
        require(listOf("agentId", "provider", "model").all { agent.get(it).asString.isNotBlank() }) { "Missing agent identity" }
        bundleEnum(agent, "reportStatus", ReportStatus.entries.map { it.name }, ReportStatus.PENDING.name)
        require(listOf("_responseBody", "_requestBody", "_rawUsageJson").none { agent.has(it) }) { "Bundle contains unresolved answer content" }
        bundleObjects(agent, "answerHistory").forEach { revision ->
            bundleStrings(revision, "answer revision", "id", "prompt", "body", "provider", "model", "source")
            require(!revision.has("_body") && !revision.has("_prompt")) { "Unresolved answer revision content" }
            revision.get("citations")?.let { require(it.isJsonArray && it.asJsonArray.all { node -> node.isJsonPrimitive && node.asJsonPrimitive.isString }) { "Invalid revision citations" } }
        }
        validateBundleExecution(agent)
        bundleObjects(agent, "chatMessages").forEach { bundleStrings(it, "chat message", "role", "content") }
    }
    json.get("conclusion")?.takeUnless { it.isJsonNull }?.let {
        val decision = bundleObject(it,"conclusion")
        bundleStrings(decision,"conclusion","sourceKind","sourceId","sourceLabel","body","rationale","uncertainty","dissent","sources","snapshotId")
        require(decision.get("sourceKind").asString in setOf("answer","meta")) { "Invalid conclusion source" }
        require(!decision.has("_body")) { "Unresolved conclusion content" }
    }
    bundleObjects(json, "userNotes").forEach { bundleStrings(it, "note", "id", "targetKind", "targetId", "text") }
    bundleObjects(json, "iconCalls").forEach { bundleStrings(it, "icon call", "agentId", "provider", "model", "pricingTier") }
    bundleObjects(json, "apiCallCosts").forEach { bundleStrings(it, "cost record", "id", "type", "provider", "model", "pricingTier") }
    bundleObjects(json, "promptHistory").forEach { bundleStrings(it, "prompt revision", "prompt") }
    listOf("knowledgeBaseIds", "parameterPresetIds").forEach { key ->
        json.get(key)?.let { value ->
            require(value.isJsonArray && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) { "Malformed $key" }
        }
    }
}

/** Called at process start as well as after a failed import. No existing report
 * is removed: its presence means the parent-last transaction committed. */
internal fun recoverReportImports(context: Context) {
    java.io.File(context.filesDir, "report_import_journal").listFiles().orEmpty().forEach { marker ->
        if (marker.name.matches(Regex("[A-Za-z0-9_-]+"))) rollbackReportImport(context, marker.name)
        marker.delete()
    }
}
private fun rollbackReportImport(context: Context, id: String) {
    if (java.io.File(context.filesDir, "reports/$id.json").exists()) return
    java.io.File(context.filesDir, "secondary/$id").deleteRecursively()
    java.io.File(context.filesDir, "report_evidence/$id").deleteRecursively()
    ApiTracer.init(context)
    java.io.File(context.filesDir, "trace").listFiles().orEmpty().filter { it.name.startsWith("import_${id}_") }.forEach { ApiTracer.deleteTrace(it.name) }
    java.io.File(context.cacheDir, "report-import-$id").deleteRecursively()
}

private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

// Import resource caps — a report bundle is low-MB at worst, so these only ever
// reject pathological / zip-bomb inputs. See audit data bug 4.
private const val MAX_BUNDLE_ENTRIES = 10_000
private const val MAX_BUNDLE_ENTRY_BYTES = 16L * 1024 * 1024     // 16 MB inflated, per entry
private const val MAX_BUNDLE_TOTAL_BYTES = 128L * 1024 * 1024    // 128 MB inflated, whole bundle

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
