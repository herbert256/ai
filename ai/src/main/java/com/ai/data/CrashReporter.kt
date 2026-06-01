package com.ai.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide crash capture, in two layers:
 *
 *  1. A [Thread] default uncaught-exception handler ([init]) that records
 *     a report and then **chains to the previous handler** — the process
 *     still terminates. We never pretend to recover an uncaught
 *     (main-thread) crash; the goal is to capture it, not to limp on in an
 *     undefined state.
 *  2. [coroutineHandler], for background coroutine scopes — records a
 *     non-fatal entry and lets the app keep running (a transient bug in a
 *     resume sweep shouldn't take the whole app down).
 *
 * The most recent FATAL report is written to
 * `<filesDir>/crash/last-crash.txt` so the next launch can offer a one-tap
 * share. Every report is also mirrored into the [AppLog] daily file, so it
 * rides the existing App Log share path. The fatal write is synchronous and
 * allocation-light because it runs in a dying process.
 *
 * Privacy: a report carries only environment + the stack trace (with
 * causes) — no prompt / report content. The user shares it deliberately.
 */
object CrashReporter {
    private const val DIR = "crash"
    private const val LAST = "last-crash.txt"
    /** Browsable history entries: `report_<millis>.txt`. */
    private const val HIST_PREFIX = "report_"
    private const val HIST_SUFFIX = ".txt"
    /** Keep at most this many history entries; older ones are pruned on init. */
    private const val MAX_HISTORY = 30

    /** Summary row for the Crash reports screen. [id] is the file name. */
    data class CrashReportInfo(val id: String, val whenLabel: String, val kind: String, val summary: String)

    @Volatile private var appContext: Context? = null
    @Volatile private var env: String = ""

    /** Install the default uncaught-exception handler. Call as early as
     *  possible (MainActivity.onCreate) with the application context. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        env = runCatching { buildEnv(ctx) }.getOrDefault("env: unavailable")
        runCatching { pruneHistory(ctx) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport("FATAL", thread, throwable) }
            // Chain so the OS still tears the process down.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Context element for background scopes: capture + keep running. */
    val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
        runCatching { writeReport("CAUGHT", Thread.currentThread(), throwable) }
    }

    /** Read the last fatal crash report without clearing it (so it
     *  survives a second crash before the user acts). Null when none. */
    fun peekPendingCrashReport(): String? {
        val ctx = appContext ?: return null
        val f = File(File(ctx.filesDir, DIR), LAST)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    /** Drop the stored fatal report — called once the user has shared or
     *  dismissed it. */
    fun clearPendingCrashReport() {
        val ctx = appContext ?: return
        runCatching { File(File(ctx.filesDir, DIR), LAST).delete() }
    }

    private fun writeReport(kind: String, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(now))
        val report = buildString {
            append("=== AI crash report ($kind) ===\n")
            append("time: ").append(ts).append('\n')
            append(env).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append('\n')
            append(Log.getStackTraceString(throwable))
        }
        appContext?.let { ctx ->
            runCatching {
                val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
                // Browsable history entry (both FATAL and CAUGHT).
                File(dir, "$HIST_PREFIX$now$HIST_SUFFIX").writeText(report)
                // Only a FATAL claims the dedicated last-crash slot — a CAUGHT
                // (already non-fatal, mirrored to AppLog) must not clobber a
                // pending fatal report the user hasn't shared yet.
                if (kind == "FATAL") File(dir, LAST).writeText(report)
            }
        }
        // Mirror into the shareable AppLog daily file (best-effort; no-ops
        // cleanly if AppLog.init hasn't run yet).
        runCatching { AppLog.e("Crash", report) }
    }

    // ----- Browsable history (Crash reports screen) -----

    /** True when at least one history report exists — drives the Monitor
     *  hub's conditional "Crash reports" entry. */
    fun hasReports(): Boolean {
        val dir = crashDir() ?: return false
        return (dir.listFiles { f -> f.name.startsWith(HIST_PREFIX) }?.isNotEmpty()) == true
    }

    /** Saved reports, newest first. */
    fun listReports(): List<CrashReportInfo> {
        val dir = crashDir() ?: return emptyList()
        val files = dir.listFiles { f -> f.name.startsWith(HIST_PREFIX) && f.name.endsWith(HIST_SUFFIX) }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            CrashReportInfo(
                id = f.name,
                whenLabel = text.lineSequence().firstOrNull { it.startsWith("time: ") }
                    ?.removePrefix("time: ")?.trim() ?: "?",
                kind = when {
                    "(FATAL)" in text -> "FATAL"
                    "(CAUGHT)" in text -> "CAUGHT"
                    else -> "?"
                },
                // First line after the env block + blank line = the exception.
                summary = text.lineSequence().dropWhile { it.isNotBlank() }
                    .drop(1).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "(no stack)"
            )
        }
    }

    /** Full text of one report, or null. [id] must be a history file name. */
    fun readReport(id: String): String? {
        if (!id.startsWith(HIST_PREFIX)) return null
        val dir = crashDir() ?: return null
        val f = File(dir, id)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    fun deleteReport(id: String) {
        if (!id.startsWith(HIST_PREFIX)) return
        val dir = crashDir() ?: return
        runCatching { File(dir, id).delete() }
    }

    /** Drop every history entry and the pending prompt. */
    fun clearAllReports() {
        val dir = crashDir() ?: return
        dir.listFiles { f -> f.name.startsWith(HIST_PREFIX) }?.forEach { runCatching { it.delete() } }
        clearPendingCrashReport()
    }

    private fun crashDir(): File? = appContext?.let { File(it.filesDir, DIR) }?.takeIf { it.isDirectory }

    private fun pruneHistory(ctx: Context) {
        val files = File(ctx.filesDir, DIR)
            .listFiles { f -> f.name.startsWith(HIST_PREFIX) } ?: return
        if (files.size <= MAX_HISTORY) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_HISTORY)
            .forEach { runCatching { it.delete() } }
    }

    private fun buildEnv(ctx: Context): String = buildString {
        val label = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.applicationInfo).toString()
        }.getOrDefault("AI")
        val ver = runCatching { com.ai.BuildConfig.VERSION_NAME }.getOrDefault("?")
        val built = runCatching {
            val millis = ctx.assets.open("build-timestamp.txt")
                .bufferedReader().use { it.readText().trim() }.toLong()
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(millis))
        }.getOrDefault("?")
        append("app: ").append(label).append(" v").append(ver)
            .append(" (built ").append(built).append(")\n")
        append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("android: API ").append(Build.VERSION.SDK_INT)
            .append(" (").append(Build.VERSION.RELEASE).append(")\n")
        // Locale is first-class here: a comma-decimal locale already caused
        // one crash, so it must be obvious in every report.
        append("locale: ").append(Locale.getDefault().toString())
    }
}
