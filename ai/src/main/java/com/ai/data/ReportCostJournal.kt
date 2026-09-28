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

    // Per-report back-off. A report whose pending costs keep failing
    // (unreadable report file, full storage, malformed record) is retried
    // after 2 s, 4 s, 8 s … up to 10 min instead of on every flush, and is
    // logged once per failure streak rather than on every attempt.
    private class RetryState(val failures: Int, val nextAttemptMs: Long)
    private val retryStates = java.util.concurrent.ConcurrentHashMap<String, RetryState>()
    private const val RETRY_BASE_MS = 2_000L
    private const val RETRY_MAX_MS = 10 * 60_000L
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** Milliseconds until the earliest backed-off report is due again, or
     *  null when no report is waiting on a retry. */
    fun nextRetryDelayMs(): Long? =
        retryStates.values.minOfOrNull { it.nextAttemptMs }?.let { (it - nowMs()).coerceAtLeast(0L) }

    private fun noteFailure(reportId: String, cause: Exception?) {
        val previous = retryStates[reportId]
        val failures = (previous?.failures ?: 0) + 1
        val delay = (RETRY_BASE_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(RETRY_MAX_MS)
        retryStates[reportId] = RetryState(failures, nowMs() + delay)
        if (previous == null) {
            AppLog.e("ReportCosts", "Pending call costs for report $reportId could not be added to its ledger; " +
                "they stay in the journal and are retried with back-off", cause)
        }
    }

    private fun noteSuccess(reportId: String) {
        val previous = retryStates.remove(reportId) ?: return
        AppLog.i("ReportCosts", "Pending call costs for report $reportId added after ${previous.failures} failed attempt(s)")
    }
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
        retryStates.remove(reportId)
        Unit
    } }
    fun flush(filesDir: File?) = synchronized(flushLock) {
        val root = filesDir ?: return@synchronized
        var failures = 0
        var firstFailure: Exception? = null
        var dirFailure: Exception? = null
        fun failed(e: Exception) {
            failures++
            if (firstFailure == null) firstFailure = e
            if (dirFailure == null) dirFailure = e
        }
        val directories = synchronized(lock) { File(root, DIR).listFiles().orEmpty().filter { it.isDirectory } }
        // Forget back-off state of reports whose journal is gone.
        retryStates.keys.retainAll(directories.mapTo(HashSet()) { it.name })
        directories.forEach { dir ->
            if (!dir.name.matches(Regex("[A-Za-z0-9_-]+"))) return@forEach
            val retry = retryStates[dir.name]
            if (retry != null && nowMs() < retry.nextAttemptMs) return@forEach
            dirFailure = null
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
            val dirError = dirFailure
            if (dirError != null) noteFailure(dir.name, dirError) else noteSuccess(dir.name)
        }
        if (failures > 0) throw IOException("$failures pending report cost records or batches need retry or repair", firstFailure)
    }
}
