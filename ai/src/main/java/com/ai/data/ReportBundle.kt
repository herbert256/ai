package com.ai.data

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

/** Summary returned by [readReportZip] for the caller's toast. */
internal data class ReportImportSummary(
    val title: String,
    val newReportId: String,
    val secondaryCount: Int,
    val traceCount: Int
)

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

    ZipOutputStream(out).use { zip ->
        // 1) report.json — verbatim serialised Report.
        zip.writeEntry("report.json", gson.toJson(report).toByteArray(Charsets.UTF_8))

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
internal fun readReportZip(context: Context, input: InputStream): ReportImportSummary {
    // Per-report bundles are small (low-MB at worst). Buffer the
    // whole zip into memory so we can read entries in any order;
    // ZipInputStream is single-pass otherwise.
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                entries[entry.name] = readBytesFully(zip)
            }
            zip.closeEntry()
        }
    }

    val metaBytes = entries["meta.json"]
        ?: error("Missing meta.json — not a valid AI Report bundle")
    val meta = JsonParser.parseString(String(metaBytes, Charsets.UTF_8)).asJsonObject
    val version = meta.get("exportVersion")?.asInt ?: 0
    if (version !in 1..EXPORT_VERSION) {
        error("Unsupported export version: $version (this install accepts 1..$EXPORT_VERSION)")
    }

    val reportBytes = entries["report.json"]
        ?: error("Missing report.json")
    val parsedReport = gson.fromJson(String(reportBytes, Charsets.UTF_8), Report::class.java)
        ?: error("report.json could not be parsed as a Report")

    // Re-key the report onto a fresh UUID so we never clobber an
    // existing same-id report on this install. Report.id is val so
    // we go through data-class .copy.
    val newReportId = UUID.randomUUID().toString()
    val report = parsedReport.copy(id = newReportId)
    ReportStorage.persistReport(context, report)

    // Re-key every secondary row: new UUID + new reportId.
    var secondaryCount = 0
    entries.entries
        .filter { it.key.startsWith("secondary/") && it.key.endsWith(".json") }
        .forEach { (_, bytes) ->
            val parsed = gson.fromJson(String(bytes, Charsets.UTF_8), SecondaryResult::class.java)
                ?: return@forEach
            val rekeyed = parsed.copy(id = UUID.randomUUID().toString(), reportId = newReportId)
            SecondaryResultStorage.save(context, rekeyed)
            secondaryCount++
        }

    // Re-key every trace: rewrite the embedded reportId; let
    // ApiTracer.saveTrace(null) mint a fresh on-disk filename so
    // two imports of the same zip never collide on the trace dir.
    var traceCount = 0
    entries.entries
        .filter { it.key.startsWith("traces/") && it.key.endsWith(".json") }
        .forEach { (_, bytes) ->
            val parsed = gson.fromJson(String(bytes, Charsets.UTF_8), ApiTrace::class.java)
                ?: return@forEach
            val rekeyed = parsed.copy(reportId = newReportId)
            ApiTracer.saveTrace(rekeyed, filename = null)
            traceCount++
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

private fun readBytesFully(stream: InputStream): ByteArray {
    val buf = ByteArray(8 * 1024)
    val out = ByteArrayOutputStream()
    while (true) {
        val n = stream.read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun appVersionName(context: Context): String = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (_: Exception) { "?" }
