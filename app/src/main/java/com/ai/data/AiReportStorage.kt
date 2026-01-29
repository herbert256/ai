package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    var relatedQuestions: List<String>? = null
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
    val rapportText: String? = null  // Text below "-- rapport --" line, shown in HTML export
)

/**
 * Singleton storage manager for AI Reports with thread-safe operations
 */
object AiReportStorage {
    private const val PREFS_NAME = "ai_reports_storage"
    private const val KEY_REPORTS = "reports"

    private val gson = Gson()
    private val lock = ReentrantLock()

    private var prefs: SharedPreferences? = null

    /**
     * Initialize the storage with context (call once at app start)
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            val reports = loadAllReports(context).toMutableMap()
            reports[report.id] = report
            saveReports(reports)
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
        relatedQuestions: List<String>? = null
    ) {
        init(context)

        lock.withLock {
            val reports = loadAllReports(context).toMutableMap()
            val report = reports[reportId] ?: return

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

            // Check if all agents are done
            if (report.agents.all { it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR || it.reportStatus == ReportStatus.STOPPED }) {
                report.completedAt = System.currentTimeMillis()
            }

            reports[reportId] = report
            saveReports(reports)
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
        relatedQuestions: List<String>? = null
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
            relatedQuestions = relatedQuestions
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
        responseBody: String? = null
    ) {
        updateAgentStatus(
            context = context,
            reportId = reportId,
            agentId = agentId,
            status = ReportStatus.ERROR,
            httpStatus = httpStatus,
            errorMessage = errorMessage,
            responseHeaders = responseHeaders,
            responseBody = responseBody
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
            loadAllReports(context)[reportId]
        }
    }

    /**
     * Get all reports sorted by timestamp (newest first)
     */
    fun getAllReports(context: Context): List<AiReport> {
        init(context)
        return lock.withLock {
            loadAllReports(context).values.sortedByDescending { it.timestamp }
        }
    }

    /**
     * Delete a report by ID
     */
    fun deleteReport(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val reports = loadAllReports(context).toMutableMap()
            reports.remove(reportId)
            saveReports(reports)
        }
    }

    /**
     * Delete all reports
     */
    fun deleteAllReports(context: Context) {
        init(context)
        lock.withLock {
            saveReports(emptyMap())
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
     * Load all reports from SharedPreferences
     */
    private fun loadAllReports(context: Context): Map<String, AiReport> {
        init(context)
        val json = prefs?.getString(KEY_REPORTS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, AiReport>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            android.util.Log.e("AiReportStorage", "Failed to load reports: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Save all reports to SharedPreferences
     */
    private fun saveReports(reports: Map<String, AiReport>) {
        try {
            val json = gson.toJson(reports)
            prefs?.edit()?.putString(KEY_REPORTS, json)?.apply()
        } catch (e: Exception) {
            android.util.Log.e("AiReportStorage", "Failed to save reports: ${e.message}")
        }
    }
}
