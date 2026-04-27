package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock

data class ReportAgent(
    val agentId: String,
    val agentName: String,
    val provider: String,
    val model: String,
    var reportStatus: ReportStatus = ReportStatus.PENDING,
    var httpStatus: Int? = null,
    var requestHeaders: String? = null,
    var requestBody: String? = null,
    var responseHeaders: String? = null,
    var responseBody: String? = null,
    var errorMessage: String? = null,
    var tokenUsage: TokenUsage? = null,
    var cost: Double? = null,
    var citations: List<String>? = null,
    var searchResults: List<SearchResult>? = null,
    var relatedQuestions: List<String>? = null,
    var rawUsageJson: String? = null,
    var durationMs: Long? = null
)

enum class ReportType { CLASSIC, TABLE }

enum class ReportStatus { PENDING, RUNNING, SUCCESS, ERROR, STOPPED }

data class Report(
    val id: String,
    val timestamp: Long,
    val title: String,
    val prompt: String,
    val agents: MutableList<ReportAgent>,
    var totalCost: Double = 0.0,
    var completedAt: Long? = null,
    val rapportText: String? = null,
    val reportType: ReportType = ReportType.CLASSIC,
    val closeText: String? = null,
    /** Optional vision attachment captured at submission time. Persisted so
     *  Regenerate can re-run with the same image without forcing the user
     *  to re-attach. base64-encoded bytes; nullable mime type. */
    val imageBase64: String? = null,
    val imageMime: String? = null,
    /** Whether the per-report 🌐 web-search toggle was on at submission.
     *  Re-seeded into UiState by regenerateReport so the rerun gets the
     *  same tool descriptor injected for every agent. */
    val webSearchTool: Boolean = false
)

/**
 * Thread-safe report persistence. Stores each report as JSON file in /files/reports/.
 */
object ReportStorage {
    private const val REPORTS_DIR = "reports"
    private val gson = createAppGson()
    private val lock = ReentrantLock()
    @Volatile private var reportsDir: File? = null

    fun init(context: Context) {
        if (reportsDir == null) lock.withLock {
            if (reportsDir == null) {
                val dir = File(context.filesDir, REPORTS_DIR)
                if (!dir.exists()) dir.mkdirs()
                reportsDir = dir
            }
        }
    }

    fun createReport(
        context: Context, title: String, prompt: String, agents: List<ReportAgent>,
        rapportText: String? = null, reportType: ReportType = ReportType.CLASSIC, closeText: String? = null,
        imageBase64: String? = null, imageMime: String? = null,
        webSearchTool: Boolean = false
    ): Report {
        init(context)
        val report = Report(UUID.randomUUID().toString(), System.currentTimeMillis(), title, prompt,
            agents.toMutableList(), rapportText = rapportText, reportType = reportType, closeText = closeText,
            imageBase64 = imageBase64, imageMime = imageMime, webSearchTool = webSearchTool)
        lock.withLock { saveReport(report) }
        return report
    }

    fun updateAgentStatus(
        context: Context, reportId: String, agentId: String, status: ReportStatus,
        httpStatus: Int? = null, requestHeaders: String? = null, requestBody: String? = null,
        responseHeaders: String? = null, responseBody: String? = null, errorMessage: String? = null,
        tokenUsage: TokenUsage? = null, cost: Double? = null, citations: List<String>? = null,
        searchResults: List<SearchResult>? = null, relatedQuestions: List<String>? = null,
        rawUsageJson: String? = null, durationMs: Long? = null
    ) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            val agent = report.agents.find { it.agentId == agentId } ?: return
            agent.reportStatus = status
            agent.httpStatus = httpStatus
            if (requestHeaders != null) agent.requestHeaders = requestHeaders
            if (requestBody != null) agent.requestBody = requestBody
            if (responseHeaders != null) agent.responseHeaders = responseHeaders
            if (responseBody != null) agent.responseBody = responseBody
            if (errorMessage != null) agent.errorMessage = errorMessage
            if (tokenUsage != null) agent.tokenUsage = tokenUsage
            if (cost != null) { agent.cost = cost; report.totalCost = report.agents.mapNotNull { it.cost }.sum() }
            if (citations != null) agent.citations = citations
            if (searchResults != null) agent.searchResults = searchResults
            if (relatedQuestions != null) agent.relatedQuestions = relatedQuestions
            if (rawUsageJson != null) agent.rawUsageJson = rawUsageJson
            if (durationMs != null) agent.durationMs = durationMs
            if (report.agents.all { it.reportStatus in listOf(ReportStatus.SUCCESS, ReportStatus.ERROR, ReportStatus.STOPPED) }) {
                report.completedAt = System.currentTimeMillis()
            }
            saveReport(report)
        }
    }

    fun markAgentRunning(context: Context, reportId: String, agentId: String, requestHeaders: String? = null, requestBody: String? = null) =
        updateAgentStatus(context, reportId, agentId, ReportStatus.RUNNING, requestHeaders = requestHeaders, requestBody = requestBody)

    fun markAgentSuccess(
        context: Context, reportId: String, agentId: String, httpStatus: Int,
        responseHeaders: String?, responseBody: String?, tokenUsage: TokenUsage?, cost: Double?,
        citations: List<String>? = null, searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null, rawUsageJson: String? = null, durationMs: Long? = null
    ) = updateAgentStatus(context, reportId, agentId, ReportStatus.SUCCESS, httpStatus,
        responseHeaders = responseHeaders, responseBody = responseBody, tokenUsage = tokenUsage,
        cost = cost, citations = citations, searchResults = searchResults,
        relatedQuestions = relatedQuestions, rawUsageJson = rawUsageJson, durationMs = durationMs)

    fun markAgentError(
        context: Context, reportId: String, agentId: String, httpStatus: Int?,
        errorMessage: String?, responseHeaders: String? = null, responseBody: String? = null, durationMs: Long? = null
    ) = updateAgentStatus(context, reportId, agentId, ReportStatus.ERROR, httpStatus,
        errorMessage = errorMessage, responseHeaders = responseHeaders, responseBody = responseBody, durationMs = durationMs)

    fun markAgentStopped(context: Context, reportId: String, agentId: String) =
        updateAgentStatus(context, reportId, agentId, ReportStatus.STOPPED, errorMessage = "Stopped by user")

    // Async variants
    suspend fun createReportAsync(
        context: Context, title: String, prompt: String, agents: List<ReportAgent>,
        rapportText: String? = null, reportType: ReportType = ReportType.CLASSIC, closeText: String? = null,
        imageBase64: String? = null, imageMime: String? = null,
        webSearchTool: Boolean = false
    ): Report = withContext(Dispatchers.IO) { createReport(context, title, prompt, agents, rapportText, reportType, closeText, imageBase64, imageMime, webSearchTool) }

    suspend fun markAgentRunningAsync(context: Context, reportId: String, agentId: String, requestHeaders: String? = null, requestBody: String? = null) =
        withContext(Dispatchers.IO) { markAgentRunning(context, reportId, agentId, requestHeaders, requestBody) }

    suspend fun markAgentSuccessAsync(
        context: Context, reportId: String, agentId: String, httpStatus: Int,
        responseHeaders: String?, responseBody: String?, tokenUsage: TokenUsage?, cost: Double?,
        citations: List<String>? = null, searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null, rawUsageJson: String? = null, durationMs: Long? = null
    ) = withContext(Dispatchers.IO) {
        markAgentSuccess(
            context, reportId, agentId, httpStatus, responseHeaders, responseBody, tokenUsage, cost,
            citations, searchResults, relatedQuestions, rawUsageJson, durationMs
        )
    }

    suspend fun markAgentErrorAsync(
        context: Context, reportId: String, agentId: String, httpStatus: Int?,
        errorMessage: String?, responseHeaders: String? = null, responseBody: String? = null, durationMs: Long? = null
    ) = withContext(Dispatchers.IO) { markAgentError(context, reportId, agentId, httpStatus, errorMessage, responseHeaders, responseBody, durationMs) }

    suspend fun markAgentStoppedAsync(context: Context, reportId: String, agentId: String) =
        withContext(Dispatchers.IO) { markAgentStopped(context, reportId, agentId) }

    fun getReport(context: Context, reportId: String): Report? { init(context); return lock.withLock { loadReport(reportId) } }
    fun getAllReports(context: Context): List<Report> { init(context); return lock.withLock { loadAllReports().sortedByDescending { it.timestamp } } }
    fun deleteReport(context: Context, reportId: String) { init(context); lock.withLock { File(reportsDir, "$reportId.json").delete() } }
    fun deleteAllReports(context: Context) { init(context); lock.withLock { reportsDir?.listFiles { f -> f.extension == "json" }?.forEach { it.delete() } } }

    private fun loadReport(reportId: String): Report? {
        val file = File(reportsDir ?: return null, "$reportId.json")
        if (!file.exists()) return null
        return try { gson.fromJson(file.readText(), Report::class.java) } catch (e: Exception) {
            android.util.Log.e("ReportStorage", "Failed to load report $reportId: ${e.message}"); null
        }
    }

    private fun loadAllReports(): List<Report> {
        val files = reportsDir?.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try { gson.fromJson(file.readText(), Report::class.java) } catch (e: Exception) {
                android.util.Log.e("ReportStorage", "Failed to load ${file.name}: ${e.message}"); null
            }
        }
    }

    private fun saveReport(report: Report) {
        File(reportsDir ?: return, "${report.id}.json").writeTextAtomic(gson.toJson(report))
    }

    fun updateReportPromptAndTitle(context: Context, reportId: String, newTitle: String, newPrompt: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val updated = report.copy(title = newTitle, prompt = newPrompt)
            saveReport(updated)
            true
        }
    }
}
