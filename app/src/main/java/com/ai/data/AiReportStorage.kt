package com.ai.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Data class representing a single agent's request/response in a report
 */
data class AiReportAgent(
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
    var durationMs: Long? = null
)

/**
 * Status of an agent's report generation
 */
enum class ReportStatus {
    PENDING,    // Waiting to start
    RUNNING,    // API request in progress
    SUCCESS,    // Completed successfully
    ERROR,      // Failed with error
    STOPPED     // Stopped by user
}

/**
 * Data class representing a complete AI Report with all agent results
 */
data class AiReport(
    val id: String,
    val timestamp: Long,
    val title: String,
    val prompt: String,
    val agents: MutableList<AiReportAgent>,
    var totalCost: Double = 0.0,
    var completedAt: Long? = null,
    val rapportText: String? = null  // Content from <user>...</user> tags, shown in HTML export
)

/**
 * Singleton storage manager for AI Reports with thread-safe operations.
 * Stores each report as an individual JSON file in /files/reports/{id}.json
 */
object AiReportStorage {
    private const val REPORTS_DIR = "reports"

    private val gson = createAiGson()
    private val lock = ReentrantLock()

    @Volatile
    private var reportsDir: File? = null

    /**
     * Initialize the storage with context (call once at app start).
     * Creates the reports directory and migrates from SharedPreferences if needed.
     */
    fun init(context: Context) {
        if (reportsDir == null) {
            lock.withLock {
                if (reportsDir == null) {
                    val dir = File(context.filesDir, REPORTS_DIR)
                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                    reportsDir = dir
                }
            }
        }
    }

    /**
     * Create a new AI Report and persist it
     * @return The created report with unique ID
     */
    fun createReport(
        context: Context,
        title: String,
        prompt: String,
        agents: List<AiReportAgent>,
        rapportText: String? = null
    ): AiReport {
        init(context)

        val report = AiReport(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            title = title,
            prompt = prompt,
            agents = agents.toMutableList(),
            rapportText = rapportText
        )

        lock.withLock {
            saveReport(report)
        }

        return report
    }

    /**
     * Update an agent's status in a report (thread-safe)
     */
    fun updateAgentStatus(
        context: Context,
        reportId: String,
        agentId: String,
        status: ReportStatus,
        httpStatus: Int? = null,
        requestHeaders: String? = null,
        requestBody: String? = null,
        responseHeaders: String? = null,
        responseBody: String? = null,
        errorMessage: String? = null,
        tokenUsage: TokenUsage? = null,
        cost: Double? = null,
        citations: List<String>? = null,
        searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null,
        durationMs: Long? = null
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
            if (cost != null) {
                agent.cost = cost
                // Recalculate total cost
                report.totalCost = report.agents.mapNotNull { it.cost }.sum()
            }
            if (citations != null) agent.citations = citations
            if (searchResults != null) agent.searchResults = searchResults
            if (relatedQuestions != null) agent.relatedQuestions = relatedQuestions
            if (durationMs != null) agent.durationMs = durationMs

            // Check if all agents are done
            if (report.agents.all { it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR || it.reportStatus == ReportStatus.STOPPED }) {
                report.completedAt = System.currentTimeMillis()
            }

            saveReport(report)
        }
    }

    /**
     * Mark an agent as running
     */
    fun markAgentRunning(
        context: Context,
        reportId: String,
        agentId: String,
        requestHeaders: String? = null,
        requestBody: String? = null
    ) {
        updateAgentStatus(
            context = context,
            reportId = reportId,
            agentId = agentId,
            status = ReportStatus.RUNNING,
            requestHeaders = requestHeaders,
            requestBody = requestBody
        )
    }

    /**
     * Mark an agent as successfully completed
     */
    fun markAgentSuccess(
        context: Context,
        reportId: String,
        agentId: String,
        httpStatus: Int,
        responseHeaders: String?,
        responseBody: String?,
        tokenUsage: TokenUsage?,
        cost: Double?,
        citations: List<String>? = null,
        searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null,
        durationMs: Long? = null
    ) {
        updateAgentStatus(
            context = context,
            reportId = reportId,
            agentId = agentId,
            status = ReportStatus.SUCCESS,
            httpStatus = httpStatus,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            tokenUsage = tokenUsage,
            cost = cost,
            citations = citations,
            searchResults = searchResults,
            relatedQuestions = relatedQuestions,
            durationMs = durationMs
        )
    }

    /**
     * Mark an agent as failed
     */
    fun markAgentError(
        context: Context,
        reportId: String,
        agentId: String,
        httpStatus: Int?,
        errorMessage: String?,
        responseHeaders: String? = null,
        responseBody: String? = null,
        durationMs: Long? = null
    ) {
        updateAgentStatus(
            context = context,
            reportId = reportId,
            agentId = agentId,
            status = ReportStatus.ERROR,
            httpStatus = httpStatus,
            errorMessage = errorMessage,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            durationMs = durationMs
        )
    }

    /**
     * Mark an agent as stopped by user
     */
    fun markAgentStopped(
        context: Context,
        reportId: String,
        agentId: String
    ) {
        updateAgentStatus(
            context = context,
            reportId = reportId,
            agentId = agentId,
            status = ReportStatus.STOPPED,
            errorMessage = "Stopped by user"
        )
    }

    /**
     * Get a report by ID
     */
    fun getReport(context: Context, reportId: String): AiReport? {
        init(context)
        return lock.withLock {
            loadReport(reportId)
        }
    }

    /**
     * Get all reports sorted by timestamp (newest first)
     */
    fun getAllReports(context: Context): List<AiReport> {
        init(context)
        return lock.withLock {
            loadAllReports().sortedByDescending { it.timestamp }
        }
    }

    /**
     * Delete a report by ID
     */
    fun deleteReport(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val file = File(reportsDir, "$reportId.json")
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Delete all reports
     */
    fun deleteAllReports(context: Context) {
        init(context)
        lock.withLock {
            reportsDir?.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
        }
    }

    /**
     * Get reports within a date range
     */
    fun getReportsByDateRange(context: Context, startTime: Long, endTime: Long): List<AiReport> {
        return getAllReports(context).filter {
            it.timestamp in startTime..endTime
        }
    }

    /**
     * Load a single report from file
     */
    private fun loadReport(reportId: String): AiReport? {
        val file = File(reportsDir ?: return null, "$reportId.json")
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            gson.fromJson(json, AiReport::class.java)
        } catch (e: Exception) {
            android.util.Log.e("AiReportStorage", "Failed to load report $reportId: ${e.message}")
            null
        }
    }

    /**
     * Load all reports from files
     */
    private fun loadAllReports(): List<AiReport> {
        val dir = reportsDir ?: return emptyList()
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val json = file.readText()
                gson.fromJson(json, AiReport::class.java)
            } catch (e: Exception) {
                android.util.Log.e("AiReportStorage", "Failed to load report from ${file.name}: ${e.message}")
                null
            }
        }
    }

    /**
     * Save a single report to file
     */
    private fun saveReport(report: AiReport) {
        try {
            val file = File(reportsDir ?: return, "${report.id}.json")
            file.writeText(gson.toJson(report))
        } catch (e: Exception) {
            android.util.Log.e("AiReportStorage", "Failed to save report ${report.id}: ${e.message}")
        }
    }
}
