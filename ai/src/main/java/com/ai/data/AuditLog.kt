package com.ai.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One row of metadata for an audit file under `<filesDir>/audit/`.
 *  Drives the Monitor → Audit list. */
data class AuditFileInfo(
    val reportId: String,
    val lastModified: Long,
    val lineCount: Int
)

/**
 * Per-report **audit log**: an append-only, human-readable trail of every
 * mutating user action, every batch start/end, and every API call (twice —
 * a technical line + a functional line) for one report.
 *
 * One file per report: `<filesDir>/audit/<reportId>.log`. Lines are
 * `yyyy-MM-dd HH:mm:ss.SSS <message>` (ms precision). The first line of a
 * report's file is `Start AI report with internal id: <UUID>`.
 *
 * Design mirrors [AppLog]: a held app context, a [lock] serialising every
 * append, and a fail-soft append path that never throws into caller code.
 * Unlike [AppLog] there is no daily rotation and no single held writer —
 * many reports may be appended to concurrently, so each append opens the
 * report's file with `append=true`, writes one line, and closes. Audit
 * volume is low relative to the per-call file logger, so the open/close
 * cost is irrelevant and it sidesteps a per-report writer map.
 *
 * Retention: the audit file is **kept** when its report is deleted (a
 * trailing `Report deleted` line is appended). The Audit list is sourced
 * from these files, so deleted reports still appear.
 */
object AuditLog {
    private const val DIR_NAME = "audit"
    private const val FILE_SUFFIX = ".log"
    private val LINE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    private var auditDir: File? = null
    @Volatile private var appContext: Context? = null
    private val lock = ReentrantLock()

    /** User toggle (Settings → Logging → "Audit log", default true). When
     *  false every audit write is dropped — [append] is the single sink all
     *  the start / technical / functional / action lines funnel through, so
     *  gating it here turns the whole audit log off. */
    @Volatile var enabled: Boolean = true

    fun init(context: Context) = lock.withLock {
        appContext = context.applicationContext
        auditDir = File(context.filesDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }
    }

    // ===== Append API =====

    /** First line of a report's audit file. */
    fun start(reportId: String) {
        append(reportId, "Start AI report with internal id: $reportId")
    }

    /** Append one functional / action / batch line for [reportId]. */
    fun append(reportId: String, message: String) {
        if (!enabled) return
        if (reportId.isBlank()) return
        val dir = auditDir ?: return  // pre-init() callers are dropped (logcat-free)
        lock.withLock {
            try {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, safeName(reportId))
                FileOutputStream(file, /* append = */ true).bufferedWriter().use { w ->
                    val ts = LINE_TIMESTAMP_FORMAT.format(Instant.now())
                    w.write("$ts ${redactSecret(message)}")
                    w.newLine()
                }
            } catch (e: Exception) {
                // Bury — auditing must never throw into caller code.
                android.util.Log.w("AuditLog", "append failed: ${e.message}")
            }
        }
    }

    /**
     * The technical line written for **every** API call (success or error).
     * `API <method> <host/path> · in N out M · $cost` on success;
     * `API <method> <host/path> · ERROR <status> <msg>` on failure.
     */
    fun appendApiTechnical(
        reportId: String,
        method: String,
        url: String,
        inTokens: Int?,
        outTokens: Int?,
        costUsd: Double?,
        statusCode: Int?,
        error: String?
    ) {
        val hostPath = shortUrl(url)
        val line = if (error != null || (statusCode != null && statusCode !in 200..299)) {
            val status = statusCode?.toString() ?: "—"
            val msg = error?.takeIf { it.isNotBlank() }?.let { " ${it.take(180)}" } ?: ""
            "API $method $hostPath · ERROR $status$msg"
        } else {
            val inN = inTokens ?: 0
            val outN = outTokens ?: 0
            "API $method $hostPath · in $inN out $outN · ${fmtCost(costUsd)}"
        }
        append(reportId, line)
    }

    /**
     * Convenience wrapper used by the central dispatch hook: derives the
     * token counts and the cost from [usage] (provider self-reported
     * `apiCost` when present, otherwise [PricingCache.computeCost] for
     * [service]/[model]) and writes the technical line. No-op when
     * [reportId] is null — the audit is per-report.
     */
    fun appendApiCall(
        reportId: String?,
        service: AppService,
        model: String,
        url: String,
        usage: TokenUsage?,
        statusCode: Int?,
        error: String?
    ) {
        if (reportId.isNullOrBlank()) return
        val cost = usage?.let { u ->
            u.apiCost ?: appContext?.let { ctx ->
                try { PricingCache.computeCost(u, PricingCache.getPricing(ctx, service, model)) }
                catch (_: Exception) { null }
            }
        }
        appendApiTechnical(
            reportId = reportId,
            method = "POST",
            url = url,
            inTokens = usage?.inputTokens,
            outTokens = usage?.outputTokens,
            costUsd = cost,
            statusCode = statusCode,
            error = error
        )
    }

    // ===== Read side (Monitor screen) =====

    /** All audit lines for [reportId], oldest-first (file order). */
    fun lines(reportId: String): List<String> = lock.withLock {
        val dir = auditDir ?: return emptyList()
        val file = File(dir, safeName(reportId))
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }

    /** Metadata for every report that has an audit file, newest-activity-first. */
    fun auditReports(): List<AuditFileInfo> = lock.withLock {
        val dir = auditDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.map { f ->
                AuditFileInfo(
                    reportId = f.name.removeSuffix(FILE_SUFFIX),
                    lastModified = f.lastModified(),
                    lineCount = try { f.readLines().count { it.isNotEmpty() } } catch (_: Exception) { 0 }
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    /** Delete one report's audit file. Returns true on success. */
    fun deleteAudit(reportId: String): Boolean = lock.withLock {
        val dir = auditDir ?: return false
        val file = File(dir, safeName(reportId))
        if (!file.exists()) return false
        return try { file.delete() } catch (_: Exception) { false }
    }

    /** Delete every audit file. Returns the count removed. */
    fun clearAll(): Int = lock.withLock {
        val dir = auditDir ?: return 0
        if (!dir.exists()) return 0
        var n = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(FILE_SUFFIX) && f.delete()) n++
        }
        n
    }

    /** True when at least one report has an audit file. Cheap existence check
     *  for the Monitor hub card. */
    fun hasAny(): Boolean = lock.withLock {
        val dir = auditDir ?: return false
        if (!dir.exists()) return false
        dir.listFiles()?.any { it.isFile && it.name.endsWith(FILE_SUFFIX) } == true
    }

    // ===== Helpers =====

    private fun safeName(reportId: String): String =
        reportId.replace(Regex("[^A-Za-z0-9._-]"), "_") + FILE_SUFFIX

    private fun shortUrl(url: String): String = try {
        val u = java.net.URI(url)
        val host = u.host ?: ""
        val path = u.path ?: ""
        if (host.isBlank()) url.substringBefore("?") else "$host$path"
    } catch (_: Exception) { url.substringBefore("?") }

    private fun fmtCost(c: Double?): String =
        if (c == null) "$?" else "$" + BigDecimal(c).setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

    /** Same secret shapes [AppLog.redactSecret] guards against — a URL or
     *  message that leaks a Bearer token / raw key / Google `key=` param. */
    private fun redactSecret(text: String): String {
        if (text.isBlank()) return text
        var out = text
        out = out.replace(BEARER_REGEX) { m -> "${m.groupValues[1]} [REDACTED]" }
        out = out.replace(RAW_KEY_REGEX) { m -> "${m.groupValues[1]}[REDACTED]" }
        out = out.replace(GOOGLE_KEY_REGEX) { _ -> "key=[REDACTED]" }
        return out
    }

    private val BEARER_REGEX = Regex("""(?i)(Bearer|Basic)\s+[A-Za-z0-9._\-+/=]+""")
    private val RAW_KEY_REGEX = Regex("""(sk-|xai-|gsk_|key-)[A-Za-z0-9_\-]{16,}""")
    private val GOOGLE_KEY_REGEX = Regex("""key=[A-Za-z0-9_\-]{16,}""")
}
