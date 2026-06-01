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

    @Volatile private var appContext: Context? = null
    @Volatile private var env: String = ""

    /** Install the default uncaught-exception handler. Call as early as
     *  possible (MainActivity.onCreate) with the application context. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        env = runCatching { buildEnv(ctx) }.getOrDefault("env: unavailable")
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
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val report = buildString {
            append("=== AI crash report ($kind) ===\n")
            append("time: ").append(ts).append('\n')
            append(env).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append('\n')
            append(Log.getStackTraceString(throwable))
        }
        // Only a FATAL claims the dedicated last-crash slot — a CAUGHT
        // (already non-fatal, mirrored to AppLog) must not clobber a
        // pending fatal report the user hasn't shared yet.
        if (kind == "FATAL") appContext?.let { ctx ->
            runCatching {
                val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
                File(dir, LAST).writeText(report)
            }
        }
        // Mirror into the shareable AppLog daily file (best-effort; no-ops
        // cleanly if AppLog.init hasn't run yet).
        runCatching { AppLog.e("Crash", report) }
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
