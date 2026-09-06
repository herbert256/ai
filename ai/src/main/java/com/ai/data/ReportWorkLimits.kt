package com.ai.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response

data class ReportWorkReview(val id: String, val reportId: String, val label: String, val items: Int)
private data class SavedReportLimit(val requestsLeft: Int, val stopAtCost: Double?)

/** Shared, explicit request ceiling for all report HTTP traffic, including
 * fallback attempts and metadata. The spend stop uses acknowledged costs;
 * already submitted concurrent calls can exceed it. */
object ReportWorkLimits {
    const val MAX_ITEMS = 5_000
    private val lock = Any()
    private var root: File? = null
    private val gson = createAppGson()
    private val pending = MutableStateFlow<List<ReportWorkReview>>(emptyList())
    val reviews = pending.asStateFlow()
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    val reviewedReport = ThreadLocal<String?>()
    private val scopes = java.util.concurrent.ConcurrentHashMap<String,Int>()
    fun beginScope(reportId: String) { scopes.merge(reportId,1,Int::plus) }
    fun endScope(reportId: String) { scopes.computeIfPresent(reportId) { _, count -> if (count <= 1) null else count-1 } }
    fun init(context: Context) { synchronized(lock) { root = context.filesDir } }
    fun checkSize(size: Int) { require(size in 0..MAX_ITEMS) { "This operation has $size items. Limit is $MAX_ITEMS; reduce participants or scope." } }
    suspend fun review(reportId: String, label: String, items: Int) {
        checkSize(items)
        if (items == 0 || reviewedReport.get() == reportId || scopes.containsKey(reportId)) return
        val review = ReportWorkReview(UUID.randomUUID().toString(),reportId,label,items)
        val waiter = CompletableDeferred<Boolean>()
        waiters[review.id] = waiter
        pending.update { it + review }
        try {
            if (!waiter.await()) throw kotlinx.coroutines.CancellationException("Work cancelled at preview")
        } finally { waiters.remove(review.id); pending.update { list -> list.filterNot { it.id == review.id } } }
    }
    fun approve(review: ReportWorkReview, requests: Int, additionalSpend: Double?) {
        require(requests in 1..MAX_ITEMS)
        require(additionalSpend == null || (additionalSpend.isFinite() && additionalSpend > 0))
        val files = synchronized(lock) { root } ?: throw IOException("Report limits are not initialized")
        val cost = ReportStorage.reportCostByFilesDir(files,review.reportId)
        synchronized(lock) {
            val file = limitFile(files, review.reportId)
            file.parentFile?.mkdirs()
            if (!file.writeTextAtomic(gson.toJson(SavedReportLimit(requests, additionalSpend?.plus(cost)))))
                throw IOException("Could not save work limits")
        }
        waiters[review.id]?.complete(true)
    }
    fun decline(id: String) { waiters[id]?.complete(false) }
    private fun limitFile(files: File, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return File(files,"report_work_limits/$id.json")
    }
    fun reserveRequest(reportId: String?) {
        if (reportId == null) return
        val files = synchronized(lock) { root } ?: return
        val hasSpendStop = synchronized(lock) { limitFile(files,reportId).takeIf { it.exists() }?.let { gson.fromJson(it.readText(), SavedReportLimit::class.java).stopAtCost != null } == true }
        val currentCost = if (hasSpendStop) ReportStorage.reportCostByFilesDir(files,reportId) else 0.0
        synchronized(lock) {
            val file = limitFile(files,reportId)
            // Legacy reports get a finite ceiling until the next explicit preview.
            val limit = if (file.exists()) gson.fromJson(file.readText(),SavedReportLimit::class.java) else SavedReportLimit(1000,null)
            if (limit.requestsLeft <= 0) throw IOException("Report request limit reached. Start a new operation and review its work limit.")
            if (limit.stopAtCost != null && currentCost >= limit.stopAtCost) throw IOException("Report spend stop reached. Review the limit before continuing.")
            file.parentFile?.mkdirs()
            if (!file.writeTextAtomic(gson.toJson(limit.copy(requestsLeft=limit.requestsLeft-1)))) throw IOException("Could not reserve report request")
        }
    }
}
class ReportWorkLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        ReportWorkLimits.reserveRequest(ApiTracer.currentReportId)
        return chain.proceed(chain.request())
    }
}
