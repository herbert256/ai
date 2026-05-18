package com.ai.data

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persists [RegenerateJob] as <filesDir>/regenerate/<reportId>.json.
 * One job per report (the user spec keeps a single in-flight
 * Regenerate batch per report at a time).
 *
 * Mirrors [SecondaryResultStorage]'s init/lock pattern with one
 * file per report instead of a per-report sub-directory.
 */
object RegenerateBatchStorage {
    private const val REGEN_DIR = "regenerate"
    private val gson = createAppGson()
    private val lock = ReentrantLock()
    @Volatile private var rootDir: File? = null

    fun init(context: Context) {
        if (rootDir == null) lock.withLock {
            if (rootDir == null) {
                val dir = File(context.filesDir, REGEN_DIR)
                if (!dir.exists()) dir.mkdirs()
                rootDir = dir
            }
        }
    }

    private fun fileFor(reportId: String): File? {
        val root = rootDir ?: return null
        if (reportId.isBlank() || reportId == "." || reportId == ".."
            || reportId.contains('/') || reportId.contains('\\')) {
            AppLog.e("RegenerateBatchStorage", "Refusing to resolve job file for suspect id $reportId")
            return null
        }
        val file = File(root, "$reportId.json")
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            AppLog.e("RegenerateBatchStorage", "Refusing to resolve job file that escapes root: $reportId")
            return null
        }
        return file
    }

    fun get(context: Context, reportId: String): RegenerateJob? {
        init(context)
        return lock.withLock {
            val file = fileFor(reportId) ?: return@withLock null
            if (!file.exists()) return@withLock null
            try {
                gson.fromJson(file.readText(), RegenerateJob::class.java)
            } catch (e: Exception) {
                AppLog.w("RegenerateBatchStorage", "parse failed for $reportId: ${e.message}")
                null
            }
        }
    }

    fun save(context: Context, job: RegenerateJob) {
        init(context)
        lock.withLock {
            val file = fileFor(job.reportId) ?: return
            file.writeTextAtomic(gson.toJson(job))
        }
    }

    fun delete(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val file = fileFor(reportId) ?: return
            if (file.exists()) try { file.delete() } catch (_: Exception) {}
        }
    }

    /** Used by the 30 s background sweep to find every report that
     *  currently has a persisted Regenerate job (no kind filter —
     *  the orchestrator decides what to do based on each job's
     *  status). Returns the reportId portion of every JSON file's
     *  name under <filesDir>/regenerate/. */
    fun listActiveReports(context: Context): List<String> {
        init(context)
        return lock.withLock {
            val root = rootDir ?: return@withLock emptyList()
            val files = root.listFiles { f -> f.isFile && f.extension == "json" }
                ?: return@withLock emptyList()
            files.mapNotNull { it.nameWithoutExtension.takeIf { id -> id.isNotBlank() } }
        }
    }
}
