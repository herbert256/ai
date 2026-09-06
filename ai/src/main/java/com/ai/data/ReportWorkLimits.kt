package com.ai.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.asContextElement
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
    // Capture only the approval of the calling coroutine. A different UI
    // operation on the same report must still receive its own preview.
    fun inheritedApproval(reportId: String?) =
        reviewedReport.asContextElement(reviewedReport.get().takeIf { it == reportId })
    fun init(context: Context) { synchronized(lock) { root = context.filesDir } }
    fun checkSize(size: Int) { require(size in 0..MAX_ITEMS) { "This operation has $size items. Limit is $MAX_ITEMS; reduce participants or scope." } }
    suspend fun review(reportId: String, label: String, items: Int) {
        checkSize(items)
        if (items == 0 || reviewedReport.get() == reportId) return
        val review = ReportWorkReview(UUID.randomUUID().toString(),reportId,label,items)
        val waiter = CompletableDeferred<Boolean>()
        waiters[review.id] = waiter
        pending.update { it + review }
        try {
            if (!waiter.await()) throw kotlinx.coroutines.CancellationException("Work cancelled at preview")
        } finally { synchronized(lock) { waiters.remove(review.id) }; pending.update { list -> list.filterNot { it.id == review.id } } }
    }
    fun approve(review: ReportWorkReview, requests: Int, additionalSpend: Double?) {
        require(requests in 1..MAX_ITEMS)
        require(additionalSpend == null || (additionalSpend.isFinite() && additionalSpend > 0))
        val files = synchronized(lock) { root } ?: throw IOException("Report limits are not initialized")
        val cost = ReportStorage.reportCostByFilesDir(files,review.reportId)
        synchronized(lock) {
            val waiter = waiters[review.id]?.takeIf { it.isActive } ?: return
            require(cost.isFinite() && (additionalSpend == null || (additionalSpend + cost).isFinite())) { "Invalid report spend total" }
            val file = limitFile(files, review.reportId)
            file.parentFile?.mkdirs()
            if (!file.writeTextAtomic(gson.toJson(SavedReportLimit(requests, additionalSpend?.plus(cost)))))
                throw IOException("Could not save work limits")
            waiter.complete(true)
        }
    }
    fun decline(id: String) { synchronized(lock) { waiters[id]?.complete(false) } }
    fun deleteForReport(reportId: String) {
        synchronized(lock) { root?.let { limitFile(it, reportId).delete() } }
    }
    private fun limitFile(files: File, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        return File(files,"report_work_limits/$id.json")
    }
    private fun readLimit(file: File): SavedReportLimit {
        if (!file.exists()) return SavedReportLimit(1000, null)
        val json = com.google.gson.JsonParser.parseString(file.readText()).asJsonObject
        val count = json.get("requestsLeft")
        require(count != null && count.isJsonPrimitive && count.asJsonPrimitive.isNumber)
        val requests = count.asBigDecimal.intValueExact()
        require(requests in 0..MAX_ITEMS)
        val spend = json.get("stopAtCost")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
            it.asDouble.also { value -> require(value.isFinite() && value >= 0) }
        }
        return SavedReportLimit(requests, spend)
    }
    fun reserveRequest(reportId: String?) {
        if (reportId == null) return
        val files = synchronized(lock) { root } ?: return
        try {
            val file = limitFile(files, reportId)
            while (true) {
                val before = synchronized(lock) { readLimit(file) }
                // Never hold the limit lock while reading ReportStorage: report
                // initialization acquires these locks in the opposite order.
                val currentCost = if (before.stopAtCost != null) ReportStorage.reportCostByFilesDir(files, reportId) else 0.0
                val reserved = synchronized(lock) {
                    val limit = readLimit(file)
                    if (limit != before) return@synchronized false
                    if (limit.requestsLeft <= 0) throw IOException("Report request limit reached. Start a new operation and review its work limit.")
                    if (limit.stopAtCost != null && (!currentCost.isFinite() || currentCost >= limit.stopAtCost))
                        throw IOException("Report spend stop reached. Review the limit before continuing.")
                    file.parentFile?.mkdirs()
                    if (!file.writeTextAtomic(gson.toJson(limit.copy(requestsLeft = limit.requestsLeft - 1))))
                        throw IOException("Could not reserve report request")
                    true
                }
                if (reserved) return
            }
        } catch (e: IOException) { throw e }
        catch (e: Exception) {
            // OkHttp only delivers IOExceptions to async request callbacks.
            // Fail closed and let a fresh preview repair malformed saved limits.
            throw IOException("Invalid saved report work limit. Start a new operation and review its limit.", e)
        }
    }
}
class ReportWorkLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        ReportWorkLimits.reserveRequest(ApiTracer.currentReportId)
        return chain.proceed(chain.request())
    }
}
