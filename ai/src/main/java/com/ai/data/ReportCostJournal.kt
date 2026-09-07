package com.ai.data

import java.io.File
import java.io.IOException

/** Write-ahead accounting: one small durable record per completed attempt.
 * The report's UUID ledger deduplicates replays after a crash between append
 * and unlink. Optional aggregate statistics do not control this journal. */
object ReportCostJournal {
    private val lock = Any()
    // Serialize flush/delete, but never make enqueuers wait for ReportStorage.
    private val flushLock = Any()
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
    fun deleteForReport(filesDir: File, reportId: String) = synchronized(flushLock) { synchronized(lock) {
        require(reportId.matches(Regex("[A-Za-z0-9_-]+")))
        File(File(filesDir, DIR), reportId).deleteRecursively()
        Unit
    } }
    fun flush(filesDir: File?) = synchronized(flushLock) {
        val root = filesDir ?: return@synchronized
        var failures = 0
        var firstFailure: Exception? = null
        fun failed(e: Exception) { failures++; if (firstFailure == null) firstFailure = e }
        val directories = synchronized(lock) { File(root, DIR).listFiles().orEmpty().filter { it.isDirectory } }
        directories.forEach { dir ->
            if (!dir.name.matches(Regex("[A-Za-z0-9_-]+"))) return@forEach
            // Retain malformed entries for repair, but do not let one poison
            // every later record, another report, or aggregate statistics.
            val snapshot = synchronized(lock) { dir.listFiles { f -> f.extension == "json" }.orEmpty().toList() }
            snapshot.chunked(128).forEach { files ->
                val valid = files.mapNotNull { file ->
                    try {
                        val text = file.readText()
                        val record = gson.fromJson(text, ReportApiCallCost::class.java)
                            ?: throw IOException("Empty pending cost record")
                        require(record.id == file.nameWithoutExtension && !record.type.isNullOrBlank() &&
                            !record.provider.isNullOrBlank() && !record.model.isNullOrBlank() && !record.pricingTier.isNullOrBlank() &&
                            record.inputTokens >= 0 && record.outputTokens >= 0 && record.searchUnits >= 0 &&
                            record.inputCost.isFinite() && record.outputCost.isFinite()) { "Invalid pending cost record: ${file.name}" }
                        Triple(file, record, text)
                    } catch (e: Exception) { failed(e); null }
                }
                if (valid.isNotEmpty()) try {
                    // UUID deduplication makes retry safe after append succeeds
                    // but a journal unlink fails or the process is interrupted.
                    ReportStorage.appendApiCallCosts(root, dir.name, valid.map { it.second })
                    synchronized(lock) {
                        valid.forEach { (file, _, capturedText) ->
                            // A retry may have replaced a same-ID record since the snapshot.
                            // Acknowledge only the exact durable payload just appended.
                            if (file.exists() && file.readText() == capturedText && !file.delete())
                                failed(IOException("Could not remove acknowledged cost record: ${file.name}"))
                        }
                    }
                } catch (e: Exception) { failed(e) }
            }
            synchronized(lock) { if (dir.listFiles().isNullOrEmpty()) dir.delete() }
        }
        if (failures > 0) throw IOException("$failures pending report cost records or batches need retry or repair", firstFailure)
    }
}
