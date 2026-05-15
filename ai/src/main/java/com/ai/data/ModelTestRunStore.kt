package com.ai.data

import android.content.Context
import java.io.File
import kotlin.concurrent.withLock

/**
 * Persists the single "last test run" for the Test-all-models feature
 * to `<filesDir>/test_run.json`. There's exactly one run — a fresh
 * "Test all models" press overwrites it. The engine flushes here on
 * each item completion (so a crash mid-run keeps partial results) and
 * once more on run completion.
 *
 * Single-document JSON store, same shape as [ChatHistoryManager]'s
 * per-session save/load — `createAppGson()` + a [java.util.concurrent
 * .locks.ReentrantLock] + crash-safe [writeTextAtomic].
 */
object ModelTestRunStore {
    private const val FILE = "test_run.json"
    private val gson = createAppGson()
    private val lock = java.util.concurrent.locks.ReentrantLock()

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Overwrite the single persisted run. Returns false on I/O
     *  failure (disk full / permission) — caller can ignore, the
     *  in-memory StateFlow is still authoritative for the session. */
    fun save(context: Context, run: ModelTestRunState): Boolean = lock.withLock {
        try {
            file(context).writeTextAtomic(gson.toJson(run))
        } catch (e: Exception) {
            AppLog.w("ModelTestRunStore", "save failed: ${e.message}")
            false
        }
    }

    /** Read the last run, or null when none has been persisted. */
    fun load(context: Context): ModelTestRunState? = lock.withLock {
        val f = file(context)
        if (!f.exists()) return null
        try {
            f.bufferedReader().use { gson.fromJson(it, ModelTestRunState::class.java) }
        } catch (e: Exception) {
            AppLog.w("ModelTestRunStore", "load failed: ${e.message}")
            null
        }
    }

    /** Drop the persisted run (runtime-data reset path). */
    fun delete(context: Context) = lock.withLock {
        try { file(context).delete() } catch (_: Exception) {}
    }
}
