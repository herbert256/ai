package com.ai.data

import java.io.File
import java.io.IOException

/** Write-ahead accounting: one small durable record per completed attempt.
 * The report's UUID ledger deduplicates replays after a crash between append
 * and unlink. Optional aggregate statistics do not control this journal. */
object ReportCostJournal {
    private val lock = Any()
    private val gson = createAppGson()
    private const val DIR = "report_cost_pending"
    fun enqueue(filesDir: File?, reportId: String, record: ReportApiCallCost) = synchronized(lock) {
        val root = filesDir ?: return@synchronized
        require(reportId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid report ID" }
        val dir = File(File(root, DIR), reportId).apply { mkdirs() }
        val file = File(dir, "${record.id}.json")
        var recovering = false
        try { ReportSaveRecovery.write(file, gson.toJson(record), reportId,
            retryLocked = { action -> synchronized(lock) { action() } },
            onSaved = { if (recovering) java.util.concurrent.CompletableFuture.runAsync { flush(root) } }) }
        finally { recovering = true }
    }
    fun flush(filesDir: File?) = synchronized(lock) {
        val root = filesDir ?: return@synchronized
        File(root, DIR).listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            if (!dir.name.matches(Regex("[A-Za-z0-9_-]+"))) return@forEach
            // Bounded chunks avoid loading a large accumulated journal at once.
            dir.listFiles { f -> f.extension == "json" }.orEmpty().asList().chunked(128).forEach { files ->
                val records = files.map { f -> gson.fromJson(f.readText(), ReportApiCallCost::class.java)
                    ?: throw IOException("Invalid pending cost record") }
                // null means a deleted report or already-acknowledged IDs. Failed
                // writes throw and retain every journal entry for the next flush.
                ReportStorage.appendApiCallCosts(root, dir.name, records)
                files.forEach { it.delete() }
            }
            if (dir.listFiles().isNullOrEmpty()) dir.delete()
        }
    }
}
