package com.ai.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** log4j-style severity levels. Priorities are aligned with
 *  [android.util.Log] so [AppLog]'s forwarder is a one-line dispatch. */
enum class LogLevel(val priority: Int) {
    TRACE(2),    // matches Log.VERBOSE
    DEBUG(3),    // matches Log.DEBUG
    INFO(4),     // matches Log.INFO
    WARN(5),     // matches Log.WARN
    ERROR(6),    // matches Log.ERROR
    OFF(99)      // sentinel — disables the file appender (logcat still fires)
}

/** One row of metadata for a log file under `<filesDir>/applog/`.
 *  Cached in [AppLog.cachedFiles] so the list view doesn't restat the
 *  directory on every navigation. */
data class AppLogFileInfo(
    val filename: String,
    val date: String,        // yyyy-MM-dd derived from the filename
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * In-app logger that mirrors [android.util.Log] and writes every
 * call at or above [threshold] to a daily-rotating file under
 * `<filesDir>/applog/applog_<yyyyMMdd>.log`.
 *
 * Designed to give the user a durable on-device log they can share
 * with Claude Code when the app misbehaves: forwards to logcat for
 * dev visibility, and stores plain-text rows on disk so an `adb pull`
 * (or the in-app share button) can hand the same content over.
 *
 * Implementation notes:
 * - All append paths funnel through [appendLine] which serialises on
 *   [lock]. A single [BufferedWriter] is held open across calls and
 *   flushed per line — slightly more I/O than batched but means a
 *   process kill never loses the last few lines.
 * - The writer is reopened lazily when the date rolls over (cheap
 *   check on every append: compare today's date string against the
 *   one captured when the writer last opened).
 * - Sensitive headers (`Authorization`, `x-api-key`, …) are redacted
 *   inline via [redactSecret] before the line is written. Call sites
 *   that already redact themselves (e.g. TracingInterceptor's
 *   headersToMap) pass through unchanged.
 * - The in-memory [cachedFiles] mirror keeps the file list O(1) for
 *   the viewer screen. Invalidated on every append, delete, and
 *   clear — same contract as [ApiTracer.cachedTraceFiles].
 */
object AppLog {
    private const val DIR_NAME = "applog"
    private const val FILE_PREFIX = "applog_"
    private const val FILE_SUFFIX = ".log"
    private val FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    private val DISPLAY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val LINE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    @Volatile var threshold: LogLevel = LogLevel.INFO

    private var logDir: File? = null
    private val lock = ReentrantLock()
    private var writer: BufferedWriter? = null
    private var writerDate: String? = null  // yyyyMMdd date used to open the current writer

    @Volatile private var cachedFiles: List<AppLogFileInfo>? = null

    /** Last exception message seen by [appendLine]'s catch block, or
     *  null when the file appender is healthy. Surfaced by the
     *  Application log viewer in the empty-state branch so a user
     *  can tell "logging is broken" apart from "nothing was logged
     *  yet" — these used to be indistinguishable when the writer
     *  failed silently (disk full, file-handle exhaustion, etc.). */
    @Volatile var lastWriterError: String? = null
        private set

    /** Count of [appendLine] calls that hit the catch block since
     *  the last successful flush. Reset to 0 on every successful
     *  write — non-zero means a fresh failure burst since the last
     *  good line was persisted. */
    @Volatile var droppedLineCount: Long = 0L
        private set

    fun init(context: Context) = lock.withLock {
        logDir = File(context.filesDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }
        // Apply the persisted threshold immediately so DEBUG/TRACE
        // calls inside bootstrap() are admitted on cold start instead
        // of waiting for AppViewModel's threshold mirror after
        // bootstrap completes. Read directly from the main prefs
        // (same KEY_LOG_LEVEL SettingsPreferences uses) — no
        // SettingsPreferences dependency here keeps AppLog
        // self-contained and lets it apply the threshold before any
        // higher-level singletons exist.
        try {
            val prefs = context.getSharedPreferences("eval_prefs", Context.MODE_PRIVATE)
            prefs.getString("log_level", null)?.let { raw ->
                try { threshold = LogLevel.valueOf(raw) } catch (_: Exception) {}
            }
        } catch (_: Exception) { /* prefs unreadable — keep default INFO */ }
    }

    // ===== Public API — mirrors android.util.Log =====

    fun v(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.v(tag, msg, t)
        if (threshold.priority <= LogLevel.TRACE.priority) appendLine(LogLevel.TRACE, tag, msg, t)
    }

    fun d(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.d(tag, msg, t)
        if (threshold.priority <= LogLevel.DEBUG.priority) appendLine(LogLevel.DEBUG, tag, msg, t)
    }

    fun i(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.i(tag, msg, t)
        if (threshold.priority <= LogLevel.INFO.priority) appendLine(LogLevel.INFO, tag, msg, t)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.w(tag, msg, t)
        if (threshold.priority <= LogLevel.WARN.priority) appendLine(LogLevel.WARN, tag, msg, t)
    }

    fun w(tag: String, t: Throwable) {
        android.util.Log.w(tag, t)
        if (threshold.priority <= LogLevel.WARN.priority) appendLine(LogLevel.WARN, tag, t.message ?: t.javaClass.simpleName, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e(tag, msg, t)
        if (threshold.priority <= LogLevel.ERROR.priority) appendLine(LogLevel.ERROR, tag, msg, t)
    }

    // ===== File management =====

    fun getLogFiles(): List<AppLogFileInfo> = lock.withLock {
        cachedFiles?.let { return it }
        val dir = logDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        val list = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.map { f ->
                AppLogFileInfo(
                    filename = f.name,
                    date = dateFromFilename(f.name),
                    sizeBytes = f.length(),
                    lastModified = f.lastModified()
                )
            }
            ?.sortedByDescending { it.filename }
            ?: emptyList()
        cachedFiles = list
        list
    }

    fun readLogFile(filename: String): String? = lock.withLock {
        val dir = logDir ?: return null
        val file = File(dir, filename)
        if (!file.exists()) return null
        return try { file.readText() } catch (_: Exception) { null }
    }

    fun deleteLog(filename: String): Boolean = lock.withLock {
        val dir = logDir ?: return false
        val file = File(dir, filename)
        if (!file.exists()) return false
        val ok = try { file.delete() } catch (_: Exception) { false }
        if (ok) {
            // If the active writer was pointing at this file, close it
            // so the next append reopens fresh.
            if (writer != null && writerDate != null && fileForDate(writerDate!!).name == filename) {
                closeWriterLocked()
            }
            cachedFiles?.let { current ->
                cachedFiles = current.filterNot { it.filename == filename }
            }
        }
        ok
    }

    fun deleteLogsOlderThan(cutoffMs: Long): Int = lock.withLock {
        val dir = logDir ?: return 0
        if (!dir.exists()) return 0
        var count = 0
        val deletedNames = mutableSetOf<String>()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) &&
                f.lastModified() < cutoffMs && f.delete()
            ) {
                count++
                deletedNames += f.name
            }
        }
        if (deletedNames.isNotEmpty()) {
            // Same writer-cleanup as deleteLog.
            if (writer != null && writerDate != null && fileForDate(writerDate!!).name in deletedNames) {
                closeWriterLocked()
            }
            cachedFiles?.let { current ->
                cachedFiles = current.filterNot { it.filename in deletedNames }
            }
        }
        count
    }

    fun clearLogs(): Int = lock.withLock {
        val dir = logDir ?: return 0
        if (!dir.exists()) return 0
        closeWriterLocked()
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) && f.delete()) {
                count++
            }
        }
        cachedFiles = emptyList()
        count
    }

    /** Plain string of "yyyy-MM-dd" for [filename] — derived back from
     *  the file's `applog_<yyyyMMdd>.log` shape. Used by the viewer
     *  to render the date column without re-parsing the file
     *  timestamp. */
    private fun dateFromFilename(filename: String): String {
        val core = filename.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return try {
            LocalDate.parse(core, FILE_DATE_FORMAT).format(DISPLAY_DATE_FORMAT)
        } catch (_: Exception) { core }
    }

    private fun fileForDate(yyyymmdd: String): File =
        File(logDir!!, "$FILE_PREFIX$yyyymmdd$FILE_SUFFIX")

    // ===== Internal append path =====

    private fun appendLine(level: LogLevel, tag: String, msg: String, t: Throwable?) {
        val dir = logDir ?: return  // pre-init() callers fall through to logcat only
        lock.withLock {
            try {
                if (!dir.exists()) dir.mkdirs()
                val today = FILE_DATE_FORMAT.format(LocalDate.now())
                if (writerDate != today) {
                    closeWriterLocked()
                    writer = BufferedWriter(FileWriter(File(dir, "$FILE_PREFIX$today$FILE_SUFFIX"), /* append = */ true))
                    writerDate = today
                }
                val w = writer ?: return
                val ts = LINE_TIMESTAMP_FORMAT.format(Instant.now())
                val safeMsg = redactSecret(msg)
                w.write("$ts ${level.name} $tag: $safeMsg")
                w.newLine()
                if (t != null) {
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))
                    sw.toString().lineSequence().forEach { line ->
                        if (line.isNotBlank()) {
                            w.write("    $line")
                            w.newLine()
                        }
                    }
                }
                w.flush()
                // Invalidate the cached file list: today's size grew and
                // possibly the file was just created. The next viewer
                // open does an O(N) restat — cheap with ~daily-sized N.
                cachedFiles = null
                // Reset the writer-error state — a successful flush
                // means whatever was broken before isn't anymore.
                if (lastWriterError != null || droppedLineCount > 0L) {
                    lastWriterError = null
                    droppedLineCount = 0L
                }
            } catch (e: Exception) {
                // Bury — logging failures must never throw into caller
                // code. logcat still has the original line. Record
                // *what* failed so the viewer's empty-state branch can
                // surface "writer is broken" instead of the ambiguous
                // "(no log files yet)" message.
                lastWriterError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                droppedLineCount += 1L
                android.util.Log.w("AppLog", "appendLine failed: ${e.message}")
            }
        }
    }

    private fun closeWriterLocked() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        writerDate = null
    }

    /** Replace common secret-bearing patterns in [text] with a redacted
     *  form. Catches the same shapes [TracingInterceptor] guards
     *  against — Bearer tokens, raw `sk-*` / `xai-*` / `gsk_*` keys,
     *  Google `key=<...>` query params. */
    private fun redactSecret(text: String): String {
        if (text.isBlank()) return text
        var out = text
        // Bearer / Basic auth headers
        out = out.replace(BEARER_REGEX) { m -> "${m.groupValues[1]} [REDACTED]" }
        // OpenAI / xAI / Groq style raw keys leaking into messages
        out = out.replace(RAW_KEY_REGEX) { m -> "${m.groupValues[1]}[REDACTED]" }
        // Google "?key=…" query params
        out = out.replace(GOOGLE_KEY_REGEX) { _ -> "key=[REDACTED]" }
        return out
    }

    private val BEARER_REGEX = Regex("""(?i)(Bearer|Basic)\s+[A-Za-z0-9._\-+/=]+""")
    private val RAW_KEY_REGEX = Regex("""(sk-|xai-|gsk_|key-)[A-Za-z0-9_\-]{16,}""")
    private val GOOGLE_KEY_REGEX = Regex("""key=[A-Za-z0-9_\-]{16,}""")
}
