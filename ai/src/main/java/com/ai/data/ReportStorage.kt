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
    val webSearchTool: Boolean = false,
    /** Per-report 🧠 reasoning level (low / medium / high; null = unset).
     *  Persisted alongside webSearchTool so a regenerate uses the same
     *  reasoning hint without forcing the user to re-pick. Non-thinking
     *  models drop the field at dispatch. */
    val reasoningEffort: String? = null,
    /** Set when this report is a translated copy of another report. Lets
     *  the HTML export pull the source's API traces into the JSON view
     *  alongside the translation traces. Null on regular reports. */
    val sourceReportId: String? = null
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
        webSearchTool: Boolean = false,
        reasoningEffort: String? = null,
        // Optional explicit id — used by the translation flow so the new
        // report's UUID can be reserved up front and threaded into
        // ApiTracer.currentReportId before any translation API calls run.
        // Without this the translation traces end up tagged with no
        // report id and don't surface on either report's trace screen.
        explicitId: String? = null,
        sourceReportId: String? = null
    ): Report {
        init(context)
        val report = Report(explicitId ?: UUID.randomUUID().toString(), System.currentTimeMillis(), title, prompt,
            agents.toMutableList(), rapportText = rapportText, reportType = reportType, closeText = closeText,
            imageBase64 = imageBase64, imageMime = imageMime, webSearchTool = webSearchTool,
            reasoningEffort = reasoningEffort, sourceReportId = sourceReportId)
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
            // On a SUCCESS transition, every "result" field is replaced
            // with what the new call provided — including clearing any
            // leftover errorMessage / citations / etc. from a prior
            // failed attempt. Without this a regen that recovered from
            // an error would keep the stale failure on the per-model
            // viewer (which checks errorMessage before responseBody).
            // Other transitions keep the "preserve on null" behaviour
            // so a partial mid-flight update doesn't drop earlier data.
            if (status == ReportStatus.SUCCESS) {
                agent.errorMessage = null
                agent.citations = citations
                agent.searchResults = searchResults
                agent.relatedQuestions = relatedQuestions
                agent.rawUsageJson = rawUsageJson
            } else {
                if (errorMessage != null) agent.errorMessage = errorMessage
                if (citations != null) agent.citations = citations
                if (searchResults != null) agent.searchResults = searchResults
                if (relatedQuestions != null) agent.relatedQuestions = relatedQuestions
                if (rawUsageJson != null) agent.rawUsageJson = rawUsageJson
            }
            if (tokenUsage != null) agent.tokenUsage = tokenUsage
            if (cost != null) { agent.cost = cost; report.totalCost = report.agents.mapNotNull { it.cost }.sum() }
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
        webSearchTool: Boolean = false,
        reasoningEffort: String? = null,
        explicitId: String? = null,
        sourceReportId: String? = null
    ): Report = withContext(Dispatchers.IO) { createReport(context, title, prompt, agents, rapportText, reportType, closeText, imageBase64, imageMime, webSearchTool, reasoningEffort, explicitId, sourceReportId) }

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
    fun deleteReport(context: Context, reportId: String) {
        init(context)
        lock.withLock { File(reportsDir, "$reportId.json").delete() }
        // Cascade: drop any rerank/summary meta-results associated with the
        // report so /files/secondary/<reportId>/ doesn't accumulate orphans.
        SecondaryResultStorage.deleteAllForReport(context, reportId)
    }
    fun deleteAllReports(context: Context) {
        init(context)
        lock.withLock {
            reportsDir?.listFiles { f -> f.extension == "json" }?.forEach { f ->
                val reportId = f.nameWithoutExtension
                f.delete()
                SecondaryResultStorage.deleteAllForReport(context, reportId)
            }
        }
    }

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

    /** Set a report's [Report.timestamp] to the current wall-clock time
     *  and persist. Used when a Rerank/Summarize/Compare batch is
     *  launched so the parent report sorts to the top of the History
     *  list — adding a meta result is a meaningful update to the
     *  report, not a passive read. No-op if the report can't be
     *  loaded. */
    /** Overwrite [Report.totalCost] for [reportId] without touching any
     *  other field. Used by the Translate flow to fold the
     *  prompt-translation cost (which has no per-row home) into the
     *  bottom-line total alongside the per-row sums. */
    fun setReportTotalCost(context: Context, reportId: String, totalCost: Double) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            report.totalCost = totalCost
            saveReport(report)
        }
    }

    fun bumpReportTimestamp(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            saveReport(report.copy(timestamp = System.currentTimeMillis()))
        }
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

    fun updateReportTitle(context: Context, reportId: String, newTitle: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(title = newTitle))
            true
        }
    }

    fun updateReportPromptText(context: Context, reportId: String, newPrompt: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(prompt = newPrompt))
            true
        }
    }

    /** Drop a single ReportAgent row from the report, recompute totalCost,
     *  and persist. Used by the per-model viewer's "Remove model from
     *  report" button so the user can prune dud responses without
     *  rebuilding the whole report. Returns false when the report or
     *  agent isn't found. */
    fun removeAgent(context: Context, reportId: String, agentId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            report.agents.removeAt(idx)
            report.totalCost = report.agents.mapNotNull { it.cost }.sum()
            saveReport(report)
            true
        }
    }

    /** Append [newAgents] to [reportId]'s agent list (skipping ones whose
     *  agentId already exists) and clear `completedAt` so the result
     *  screen shows "in progress" again until the new rows finish.
     *  Used by the additive Regenerate fast path — model-list-only
     *  changes get the new agents stitched onto the existing report
     *  rather than spawning a fresh report. */
    fun appendAgents(context: Context, reportId: String, newAgents: List<ReportAgent>) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            val existingIds = report.agents.mapTo(mutableSetOf()) { it.agentId }
            val toAdd = newAgents.filter { it.agentId !in existingIds }
            if (toAdd.isEmpty()) return
            report.agents.addAll(toAdd)
            report.completedAt = null
            saveReport(report)
        }
    }

    /** Reset an existing agent row to PENDING and clear every result-
     *  related field so the next API call writes a fresh outcome rather
     *  than overwriting on top of stale data. Used by the in-place
     *  Regenerate path: prompt / parameter changes mark every agent as
     *  PENDING again before the new fan-out runs. */
    fun resetAgentToPending(context: Context, reportId: String, agentId: String) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            val agent = report.agents.find { it.agentId == agentId } ?: return
            agent.reportStatus = ReportStatus.PENDING
            agent.httpStatus = null
            agent.requestHeaders = null
            agent.requestBody = null
            agent.responseHeaders = null
            agent.responseBody = null
            agent.errorMessage = null
            agent.tokenUsage = null
            agent.cost = null
            agent.citations = null
            agent.searchResults = null
            agent.relatedQuestions = null
            agent.rawUsageJson = null
            agent.durationMs = null
            report.totalCost = report.agents.mapNotNull { it.cost }.sum()
            report.completedAt = null
            saveReport(report)
        }
    }

    /** Duplicate [reportId]: new UUID, fresh timestamp, "(Copy)" title
     *  suffix, every agent row + result preserved. Secondaries are not
     *  cloned — they're tied to the original by reportId and copying
     *  them would double-count metas / translations on history /
     *  totals. Returns the new id, or null when [reportId] can't be
     *  loaded. */
    fun copyReport(context: Context, reportId: String): String? {
        init(context)
        return lock.withLock {
            val src = loadReport(reportId) ?: return@withLock null
            val newId = UUID.randomUUID().toString()
            val copy = Report(
                id = newId,
                timestamp = System.currentTimeMillis(),
                title = if (src.title.endsWith("(Copy)")) src.title else "${src.title} (Copy)",
                prompt = src.prompt,
                // Deep-copy each agent so further mutations on the
                // original don't leak into the copy through the shared
                // ReportAgent reference.
                agents = src.agents.map { it.copy() }.toMutableList(),
                totalCost = src.totalCost,
                completedAt = src.completedAt,
                rapportText = src.rapportText,
                reportType = src.reportType,
                closeText = src.closeText,
                imageBase64 = src.imageBase64,
                imageMime = src.imageMime,
                webSearchTool = src.webSearchTool,
                reasoningEffort = src.reasoningEffort,
                sourceReportId = src.sourceReportId
            )
            saveReport(copy)
            newId
        }
    }
}
