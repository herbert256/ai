package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock

object ReportDataVersion {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    fun bump() { _version.update { it + 1 } }
}

/**
 * Thread-safe report persistence. Stores each report as JSON file in /files/reports/.
 */
object ReportStorage {
    private const val REPORTS_DIR = "reports"
    /** iconCalls `type` values for the report-level Find-alt title
     *  fan-out (short + long report title, per-model title). These are
     *  the only alt records with no structured cost field; see
     *  [computeReportTotalCost]. Pair-title alts share "alt/model_title"
     *  but carry attributedToSecondaryId, so the caller also filters on
     *  that being null. */
    private val TITLE_ALT_TYPES = setOf("alt/report_title", "alt/report_title_long", "alt/model_title")
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
        sourceReportId: String? = null,
        knowledgeBaseIds: List<String> = emptyList(),
        runId: String? = null,
        // Generation config captured for Regenerate replay (see Report).
        parameterPresetIds: List<String> = emptyList(),
        advancedParameters: AgentParameters? = null,
        selectionParamsById: Map<String, List<String>> = emptyMap(),
        reportSystemPromptId: String? = null
    ): Report {
        init(context)
        val now = System.currentTimeMillis()
        val report = Report(explicitId ?: UUID.randomUUID().toString(), now, createdAt = now, title = title, prompt = prompt,
            agents = agents.toMutableList(), rapportText = rapportText, reportType = reportType, closeText = closeText,
            imageBase64 = imageBase64, imageMime = imageMime, webSearchTool = webSearchTool,
            reasoningEffort = reasoningEffort, sourceReportId = sourceReportId,
            knowledgeBaseIds = knowledgeBaseIds, runId = runId,
            parameterPresetIds = parameterPresetIds, advancedParameters = advancedParameters,
            selectionParamsById = selectionParamsById, reportSystemPromptId = reportSystemPromptId)
        lock.withLock { saveReport(report) }
        return report
    }

    /** Full cost of the report's OWN generation: primary agent calls + per-agent
     *  icon + per-agent model-title + the report-level icon / title / language-
     *  detect / language-icon metadata calls. (Secondary results — Rerank / Meta
     *  / Translate — live in separate rows; the Manage screen sums those on top
     *  of this.) Previously the recompute used only `agents.cost + agent icons`,
     *  so the stored total under-counted every report-level metadata call.
     *  `costsFromDeletedItems` is tracked separately and intentionally excluded. */
    private fun computeReportTotalCost(report: Report): Double =
        report.agents.mapNotNull { it.cost }.sum() +
            report.agents.sumOf { it.iconInputCost + it.iconOutputCost } +
            report.agents.sumOf { it.modelTitleInputCost + it.modelTitleOutputCost } +
            report.iconInputCost + report.iconOutputCost +
            report.titleInputCost + report.titleOutputCost +
            report.titleLongInputCost + report.titleLongOutputCost +
            report.languageInputCost + report.languageOutputCost +
            report.languageIconInputCost + report.languageIconOutputCost +
            // Find-alt title fan-out (report-title + model-title) is the
            // one alt category with NO structured cost home — its spend
            // lives only in the iconCalls audit (runTitleCandidate writes
            // appendIconCall but bumps no titleInputCost / modelTitleInputCost).
            // The cost table already counts it via its per-call rows, so
            // without this term totalCost undercounts every alternative-
            // title search. Restrict to attributedToSecondaryId == null so
            // the pair-title-alt records (which DO have a structured home on
            // their SecondaryResult and share the "alt/model_title" type)
            // are excluded.
            report.iconCalls
                .filter { it.attributedToSecondaryId == null && it.type in TITLE_ALT_TYPES }
                .sumOf { it.inputCost + it.outputCost } +
            // User-note AI titles (workers/user-note) also have no structured
            // cost home — their spend lives only in the iconCalls audit
            // (type "note/title"). Count it here so the lifetime total
            // includes it; the cost table renders the matching per-call row.
            report.iconCalls
                .filter { it.type == "note/title" }
                .sumOf { it.inputCost + it.outputCost }

    fun updateAgentStatus(
        context: Context, reportId: String, agentId: String, status: ReportStatus,
        httpStatus: Int? = null, requestHeaders: String? = null, requestBody: String? = null,
        responseHeaders: String? = null, responseBody: String? = null, errorMessage: String? = null,
        tokenUsage: TokenUsage? = null, cost: Double? = null,
        inputCost: Double? = null, outputCost: Double? = null,
        citations: List<String>? = null,
        searchResults: List<SearchResult>? = null, relatedQuestions: List<String>? = null,
        rawUsageJson: String? = null, durationMs: Long? = null, traceFile: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val agent = report.agents.find { it.agentId == agentId } ?: return@withLock false
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
            // Additive cost + token writes: the new call's
            // numbers are ADDED onto whatever's already on disk
            // so a Regenerate-batch re-dispatch shows (prior +
            // new) instead of just the latest call's expenditure.
            // For fresh runs the prior is null/0 so additive ≡
            // overwrite — no change in behaviour. Error paths
            // pass cost=null and so don't touch the counters.
            if (tokenUsage != null) {
                agent.tokenUsage = TokenUsage(
                    inputTokens = (agent.tokenUsage?.inputTokens ?: 0) + tokenUsage.inputTokens,
                    outputTokens = (agent.tokenUsage?.outputTokens ?: 0) + tokenUsage.outputTokens
                )
            }
            if (cost != null) {
                agent.cost = (agent.cost ?: 0.0) + cost
            }
            if (inputCost != null) agent.inputCost = (agent.inputCost ?: 0.0) + inputCost
            if (outputCost != null) agent.outputCost = (agent.outputCost ?: 0.0) + outputCost
            // Recompute the report total whenever any cost field changed,
            // not only on the primary-cost path (Bug 28): an icon-cost bump
            // arriving with cost=null would otherwise leave totalCost stale.
            if (cost != null || inputCost != null || outputCost != null) {
                report.totalCost = computeReportTotalCost(report)
            }
            if (durationMs != null) agent.durationMs = durationMs
            if (traceFile != null) agent.traceFile = traceFile
            if (report.agents.all { it.reportStatus in listOf(ReportStatus.SUCCESS, ReportStatus.ERROR, ReportStatus.STOPPED) }) {
                report.completedAt = System.currentTimeMillis()
            }
            saveReport(report)
            true
        }
    }

    fun markAgentRunning(context: Context, reportId: String, agentId: String, requestHeaders: String? = null, requestBody: String? = null) =
        updateAgentStatus(context, reportId, agentId, ReportStatus.RUNNING, requestHeaders = requestHeaders, requestBody = requestBody)

    fun markAgentSuccess(
        context: Context, reportId: String, agentId: String, httpStatus: Int,
        responseHeaders: String?, responseBody: String?, tokenUsage: TokenUsage?, cost: Double?,
        inputCost: Double? = null, outputCost: Double? = null,
        citations: List<String>? = null, searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null, rawUsageJson: String? = null, durationMs: Long? = null,
        traceFile: String? = null
    ) = updateAgentStatus(context, reportId, agentId, ReportStatus.SUCCESS, httpStatus,
        responseHeaders = responseHeaders, responseBody = responseBody, tokenUsage = tokenUsage,
        cost = cost, inputCost = inputCost, outputCost = outputCost,
        citations = citations, searchResults = searchResults,
        relatedQuestions = relatedQuestions, rawUsageJson = rawUsageJson, durationMs = durationMs,
        traceFile = traceFile)

    fun markAgentError(
        context: Context, reportId: String, agentId: String, httpStatus: Int?,
        errorMessage: String?, responseHeaders: String? = null, responseBody: String? = null, durationMs: Long? = null,
        traceFile: String? = null
    ) = updateAgentStatus(context, reportId, agentId, ReportStatus.ERROR, httpStatus,
        errorMessage = errorMessage, responseHeaders = responseHeaders, responseBody = responseBody, durationMs = durationMs,
        traceFile = traceFile)

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
        sourceReportId: String? = null,
        knowledgeBaseIds: List<String> = emptyList(),
        runId: String? = null,
        parameterPresetIds: List<String> = emptyList(),
        advancedParameters: AgentParameters? = null,
        selectionParamsById: Map<String, List<String>> = emptyMap(),
        reportSystemPromptId: String? = null
    ): Report = withContext(Dispatchers.IO) { createReport(context, title, prompt, agents, rapportText, reportType, closeText, imageBase64, imageMime, webSearchTool, reasoningEffort, explicitId, sourceReportId, knowledgeBaseIds, runId, parameterPresetIds, advancedParameters, selectionParamsById, reportSystemPromptId) }

    suspend fun markAgentRunningAsync(context: Context, reportId: String, agentId: String, requestHeaders: String? = null, requestBody: String? = null) =
        withContext(Dispatchers.IO) { markAgentRunning(context, reportId, agentId, requestHeaders, requestBody) }

    suspend fun markAgentSuccessAsync(
        context: Context, reportId: String, agentId: String, httpStatus: Int,
        responseHeaders: String?, responseBody: String?, tokenUsage: TokenUsage?, cost: Double?,
        inputCost: Double? = null, outputCost: Double? = null,
        citations: List<String>? = null, searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null, rawUsageJson: String? = null, durationMs: Long? = null,
        traceFile: String? = null
    ) = withContext(Dispatchers.IO) {
        markAgentSuccess(
            context, reportId, agentId, httpStatus, responseHeaders, responseBody, tokenUsage, cost,
            inputCost, outputCost,
            citations, searchResults, relatedQuestions, rawUsageJson, durationMs, traceFile
        )
    }

    suspend fun markAgentErrorAsync(
        context: Context, reportId: String, agentId: String, httpStatus: Int?,
        errorMessage: String?, responseHeaders: String? = null, responseBody: String? = null, durationMs: Long? = null,
        traceFile: String? = null
    ) = withContext(Dispatchers.IO) { markAgentError(context, reportId, agentId, httpStatus, errorMessage, responseHeaders, responseBody, durationMs, traceFile) }

    suspend fun markAgentStoppedAsync(context: Context, reportId: String, agentId: String) =
        withContext(Dispatchers.IO) { markAgentStopped(context, reportId, agentId) }

    /** Terminalize every still-PENDING/RUNNING agent on a report as STOPPED.
     *  Called from the report job's finally when a run is cancelled (Stop, or
     *  a newer report start cancelling the shared job) so a half-finished
     *  report doesn't read as "generating" forever — its `completedAt` then
     *  gets set and the hub's running predicate clears. No-op when every agent
     *  is already terminal (the normal-completion path). */
    suspend fun stopNonTerminalAgentsAsync(context: Context, reportId: String) = withContext(Dispatchers.IO) {
        val report = getReport(context, reportId) ?: return@withContext
        report.agents
            .filter { it.reportStatus == ReportStatus.PENDING || it.reportStatus == ReportStatus.RUNNING }
            .forEach { markAgentStopped(context, reportId, it.agentId) }
    }

    fun getReport(context: Context, reportId: String): Report? { init(context); return lock.withLock { loadReport(reportId) } }
    /** Cheap last-modified timestamp of the report's on-disk JSON (0 when
     *  absent). Lets a read-only cache (the View subsystem) detect edits
     *  without re-parsing the file. */
    fun reportLastModified(context: Context, reportId: String): Long {
        init(context)
        val dir = reportsDir ?: return 0L
        return File(dir, "$reportId.json").lastModified()
    }
    fun getAllReports(context: Context): List<Report> { init(context); return lock.withLock { loadAllReports().sortedByDescending { it.timestamp } } }
    fun deleteReport(context: Context, reportId: String) {
        init(context)
        // loadReport rejects traversal markers, but loadAllReports trusts
        // the on-disk JSON's embedded id and surfaces it to UI delete
        // actions. A restored / imported report with id="../prefs/x"
        // would then point delete at the wrong file. Gate the delete
        // path with the same flat-id + canonical-child rule used on
        // write-side.
        if (!isSafeFlatId(reportId)) {
            AppLog.w("ReportStorage", "Refusing to delete report with suspect id $reportId")
            return
        }
        val dir = reportsDir ?: return
        lock.withLock {
            val target = File(dir, "$reportId.json")
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                AppLog.w("ReportStorage", "Refusing to delete report that escapes reportsDir: $reportId")
                return@withLock
            }
            target.delete()
        }
        // Cascade: drop any rerank/summary meta-results associated with the
        // report so /files/secondary/<reportId>/ doesn't accumulate orphans.
        SecondaryResultStorage.deleteAllForReport(context, reportId)
        RegenerateBatchStorage.delete(context, reportId)
        ApiTracer.init(context)
        ApiTracer.deleteTracesForReport(reportId)
        ReportDataVersion.bump()
    }
    fun deleteAllReports(context: Context): Int {
        init(context)
        val deletedIds = mutableListOf<String>()
        lock.withLock {
            reportsDir?.listFiles { f -> f.extension == "json" }?.forEach { f ->
                if (f.delete()) deletedIds += f.nameWithoutExtension
            }
        }
        deletedIds.forEach { reportId ->
            SecondaryResultStorage.deleteAllForReport(context, reportId)
            RegenerateBatchStorage.delete(context, reportId)
            ApiTracer.init(context)
            ApiTracer.deleteTracesForReport(reportId)
        }
        if (deletedIds.isNotEmpty()) ReportDataVersion.bump()
        return deletedIds.size
    }

    fun reportExists(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock { loadReport(reportId) != null }
    }

    private fun loadReport(reportId: String): Report? {
        // Defence in depth: every internal caller passes UUIDs but
        // deep-link entry points (intent extras, share-target, etc.)
        // can in principle hand in a reportId containing path
        // separators. Refuse anything that doesn't look like a flat
        // file name to keep loadReport from escaping reportsDir.
        if (reportId.contains('/') || reportId.contains('\\') || reportId.contains("..")) {
            AppLog.w("ReportStorage", "Rejected reportId with path traversal markers: $reportId")
            return null
        }
        val dir = reportsDir ?: return null
        val file = File(dir, "$reportId.json")
        if (!file.exists()) return null
        return try { gson.fromJson(file.readText(), Report::class.java)?.let(::normalizeReport) } catch (e: Exception) {
            AppLog.e("ReportStorage", "Failed to load report $reportId: ${e.message}"); null
        }
    }

    private fun loadAllReports(): List<Report> {
        val files = reportsDir?.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try { gson.fromJson(file.readText(), Report::class.java)?.let(::normalizeReport) } catch (e: Exception) {
                AppLog.e("ReportStorage", "Failed to load ${file.name}: ${e.message}"); null
            }
        }
    }

    /** Gson instantiates via Unsafe (no constructor call), so fields
     *  added after a report was persisted deserialize as null. Re-assert
     *  non-null defaults for those new fields so the rest of the app can
     *  treat them as the non-null types they're declared as. */
    private fun normalizeReport(r: Report): Report {
        var res = r
        if ((res.promptHistory as List<PromptRevision>?) == null) {
            res = res.copy(promptHistory = emptyList())
        }
        if ((res.knowledgeBaseIds as List<String>?) == null) {
            res = res.copy(knowledgeBaseIds = emptyList())
        }
        if ((res.parameterPresetIds as List<String>?) == null) {
            res = res.copy(parameterPresetIds = emptyList())
        }
        if ((res.selectionParamsById as Map<String, List<String>>?) == null) {
            res = res.copy(selectionParamsById = emptyMap())
        }
        // iconCalls / userNotes are declared MutableList, but the
        // NullSafeFieldAdapterFactory coerces a *missing* field to the
        // IMMUTABLE emptyList() singleton (reflection bypasses the type).
        // An `as MutableList` cast on that throws ClassCastException
        // ("EmptyList cannot be cast to MutableList") and the whole report
        // fails to load. Copy into a real MutableList instead — this both
        // dodges the cast and gives the in-place mutators (removeAgent etc.)
        // a writable list. A plain field read needs no checkcast, so this
        // is safe even when the field holds an immutable empty.
        res = res.copy(
            iconCalls = res.iconCalls.toMutableList(),
            userNotes = res.userNotes.toMutableList()
        )
        return res
    }

    private fun saveReport(report: Report) {
        val dir = reportsDir ?: return
        // Defence in depth: a runtime-import JSON payload can carry a
        // crafted `id` ("../prefs/foo") that would otherwise escape
        // reportsDir on write. Every internal caller uses UUIDs; the
        // import path (ImportExportScreen.applyRuntimeReports) trusts
        // the embedded id verbatim. Gate write-side too so the rule
        // can't be bypassed by adding another import call site.
        if (!isSafeFlatId(report.id)) {
            AppLog.e("ReportStorage", "Refusing to save report with suspect id ${report.id}")
            return
        }
        val target = File(dir, "${report.id}.json")
        if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            AppLog.e("ReportStorage", "Refusing to save report that escapes reportsDir: ${report.id}")
            return
        }
        val ok = target.writeTextAtomic(gson.toJson(report))
        if (!ok) {
            // Surface the failure in logcat — disk-full or permission
            // races would otherwise leave the in-memory state diverged
            // from disk until the next reload, where the fresh load
            // would silently hand back the pre-update report.
            AppLog.e("ReportStorage", "Failed to save report ${report.id} (writeTextAtomic returned false)")
        } else {
            ReportDataVersion.bump()
        }
    }

    private fun isSafeFlatId(id: String): Boolean =
        id.isNotBlank() && id != "." && id != ".." &&
            !id.contains('/') && !id.contains('\\')

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
            val updated = report.copy(title = newTitle, titleLong = null, prompt = newPrompt)
            saveReport(updated)
            true
        }
    }

    /** Manual title edit — writes both the short [Report.title] and the
     *  long [Report.titleLong]. A blank long title stores null so the
     *  orange line falls back to the short title via [barTitle]. */
    fun updateReportTitle(context: Context, reportId: String, newTitle: String, newTitleLong: String?): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(title = newTitle, titleLong = newTitleLong?.takeIf { it.isNotBlank() }))
            true
        }
    }

    /** Toggle (or set) the user's pinned flag on [reportId]. Pinning
     *  doesn't change the report's body — it's strictly a hub-level
     *  promotion signal. */
    fun setReportPinned(context: Context, reportId: String, pinned: Boolean) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            report.pinned = pinned
            saveReport(report)
        }
    }

    /** Persist the resolved emoji + token usage + split cost from the
     *  icon-gen call. Clears any prior [Report.iconErrorMessage] so a
     *  successful retry overwrites a previous failure. Bumps the
     *  timestamp so screens that key on it pick up the change. */
    fun updateReportIcon(
        context: Context, reportId: String, icon: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double,
        traceFile: String? = null,
        /** Bundled prompt name that produced [icon] — "main" on the
         *  bundled initial-gen path, "main_alt" after a Find-alt pick. */
        promptUsed: String? = null,
        durationMs: Long? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            // Additive cost / token writes (see updateAgentStatus).
            val updated = report.copy(
                icon = icon, iconErrorMessage = null,
                iconInputTokens = report.iconInputTokens + inputTokens,
                iconOutputTokens = report.iconOutputTokens + outputTokens,
                iconInputCost = report.iconInputCost + inputCost,
                iconOutputCost = report.iconOutputCost + outputCost,
                iconTraceFile = traceFile,
                iconPromptUsed = promptUsed ?: report.iconPromptUsed,
                iconDurationMs = durationMs ?: report.iconDurationMs,
                timestamp = System.currentTimeMillis()
            )
            updated.totalCost = computeReportTotalCost(updated)
            saveReport(updated)
            true
        }
    }

    /** Persist a failure reason for the icon-gen call. Leaves
     *  [Report.icon] alone (so a previously-resolved icon survives a
     *  retry that errored). */
    fun updateReportIconError(context: Context, reportId: String, error: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(iconErrorMessage = error,
                timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Persist the AI-generated title + token usage + split cost.
     *  Clears any prior [Report.titleErrorMessage] so a successful
     *  retry overwrites a previous failure. Bumps the timestamp so
     *  screens that key on it pick up the change. Mirrors
     *  [updateReportIcon]. */
    fun updateReportTitleFromAi(
        context: Context, reportId: String, newTitle: String,
        titleLong: String? = null,
        promptUsed: String? = null,
        // SHORT call (≤25, drives Report.title) → title* fields.
        shortInputTokens: Int = 0, shortOutputTokens: Int = 0,
        shortInputCost: Double = 0.0, shortOutputCost: Double = 0.0,
        shortTraceFile: String? = null, shortModel: String? = null, shortDurationMs: Long? = null,
        // LONG call (≤50, drives Report.titleLong) → titleLong* fields.
        longInputTokens: Int = 0, longOutputTokens: Int = 0,
        longInputCost: Double = 0.0, longOutputCost: Double = 0.0,
        longTraceFile: String? = null, longModel: String? = null, longDurationMs: Long? = null,
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val updated = report.copy(
                title = newTitle, titleLong = titleLong, titleErrorMessage = null,
                titleInputTokens = report.titleInputTokens + shortInputTokens,
                titleOutputTokens = report.titleOutputTokens + shortOutputTokens,
                titleInputCost = report.titleInputCost + shortInputCost,
                titleOutputCost = report.titleOutputCost + shortOutputCost,
                titleTraceFile = shortTraceFile,
                titleModel = shortModel,
                titleDurationMs = shortDurationMs ?: report.titleDurationMs,
                titleLongInputTokens = report.titleLongInputTokens + longInputTokens,
                titleLongOutputTokens = report.titleLongOutputTokens + longOutputTokens,
                titleLongInputCost = report.titleLongInputCost + longInputCost,
                titleLongOutputCost = report.titleLongOutputCost + longOutputCost,
                titleLongTraceFile = longTraceFile,
                titleLongModel = longModel,
                titleLongDurationMs = longDurationMs ?: report.titleLongDurationMs,
                titlePromptUsed = promptUsed ?: report.titlePromptUsed,
                timestamp = System.currentTimeMillis()
            )
            updated.totalCost = computeReportTotalCost(updated)
            saveReport(updated)
            true
        }
    }

    /** Persist a failure reason for the AI title-gen call. Leaves
     *  [Report.title] alone (so a previously-resolved title survives
     *  a retry that errored). */
    fun updateReportTitleError(context: Context, reportId: String, error: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(titleErrorMessage = error,
                timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Clear a prior report-title error so a "Restart errors" re-run reads
     *  the row as pending/running again instead of ❌. */
    fun clearReportTitleError(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            if (report.titleErrorMessage == null) return@withLock false
            saveReport(report.copy(titleErrorMessage = null, timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Reset the title-gen row for a full regenerate while keeping all
     *  previously accrued title cost/token/trace fields additive. */
    fun clearReportTitleKeepingCost(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                titleErrorMessage = null,
                titlePromptUsed = null,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Additive update for "Find alternative icons" fan-out calls.
     *  Every per-(provider, model) call bumps the report's icon
     *  cost regardless of whether the user later picks that result —
     *  the icon row's cost should always reflect total tokens spent
     *  searching for an icon for this report. */
    fun bumpReportIconCost(
        context: Context, reportId: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val updated = report.copy(
                iconInputTokens = report.iconInputTokens + inputTokens,
                iconOutputTokens = report.iconOutputTokens + outputTokens,
                iconInputCost = report.iconInputCost + inputCost,
                iconOutputCost = report.iconOutputCost + outputCost,
                timestamp = System.currentTimeMillis()
            )
            // Find-alt report-icon fan-out spend feeds totalCost too;
            // without this recompute the dashboards / bottom-bar total
            // undercount every alt-icon search on the report icon.
            updated.totalCost = computeReportTotalCost(updated)
            saveReport(updated)
            true
        }
    }

    /** Commit the user's pick from the "Alternative icons" screen.
     *  Replaces the emoji, sets [Report.iconModel] to the
     *  "<providerId>/<modelId>" label, and clears any prior
     *  [Report.iconErrorMessage]. Cost fields are left alone — every
     *  fan-out call has already bumped them via [bumpReportIconCost]. */
    fun setReportIconChoice(
        context: Context, reportId: String,
        icon: String, iconModel: String,
        /** Bundled prompt that produced the picked emoji — typically
         *  "main_alt". Surfaces on the Icon lookup screen. */
        promptUsed: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                icon = icon, iconErrorMessage = null, iconModel = iconModel,
                iconPromptUsed = promptUsed ?: report.iconPromptUsed,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Persist a successful language-DETECTION result (first of two
     *  calls in the language flow). Stores the English language name
     *  + the detection call's tokens / cost / trace / raw response
     *  under the new `language*` fields. The second call (icon) is
     *  written separately by [updateReportLanguageIcon] and uses the
     *  `languageIcon*` fields. Clears any prior
     *  [Report.languageIconErrorMessage] so a retry that succeeds
     *  doesn't leave a stale error visible. */
    fun updateReportLanguageDetect(
        context: Context, reportId: String,
        name: String?,
        inputTokens: Int = 0, outputTokens: Int = 0,
        inputCost: Double = 0.0, outputCost: Double = 0.0,
        traceFile: String? = null,
        rawResponse: String? = null,
        durationMs: Long? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            // Additive cost / token writes (see updateAgentStatus).
            val updated = report.copy(
                languageName = name,
                languageIconErrorMessage = null,
                languageInputTokens = report.languageInputTokens + inputTokens,
                languageOutputTokens = report.languageOutputTokens + outputTokens,
                languageInputCost = report.languageInputCost + inputCost,
                languageOutputCost = report.languageOutputCost + outputCost,
                languageTraceFile = traceFile,
                languageRawResponse = rawResponse,
                languageDurationMs = durationMs ?: report.languageDurationMs,
                timestamp = System.currentTimeMillis()
            )
            updated.totalCost = computeReportTotalCost(updated)
            saveReport(updated)
            true
        }
    }

    /** Persist the SECOND call in the language flow: the fitting
     *  emoji for the already-detected [Report.languageName]. Stores
     *  the icon + optional model attribution + the second call's
     *  tokens / cost / trace / raw response under the `languageIcon*`
     *  fields. Clears any prior [Report.languageIconErrorMessage]. */
    fun updateReportLanguageIcon(
        context: Context, reportId: String,
        icon: String?,
        model: String? = null,
        inputTokens: Int = 0, outputTokens: Int = 0,
        inputCost: Double = 0.0, outputCost: Double = 0.0,
        traceFile: String? = null,
        rawResponse: String? = null,
        /** Bundled prompt name that produced [icon] — "language" on
         *  the initial second-call gen, "language_alt" after a
         *  Find-alt pick. Surfaces on the Icon lookup screen. */
        promptUsed: String? = null,
        durationMs: Long? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            // Additive cost / token writes (see updateAgentStatus).
            val updated = report.copy(
                languageIcon = icon,
                languageIconModel = model,
                languageIconErrorMessage = null,
                languageIconInputTokens = report.languageIconInputTokens + inputTokens,
                languageIconOutputTokens = report.languageIconOutputTokens + outputTokens,
                languageIconInputCost = report.languageIconInputCost + inputCost,
                languageIconOutputCost = report.languageIconOutputCost + outputCost,
                languageIconTraceFile = traceFile,
                languageIconRawResponse = rawResponse,
                languageIconPromptUsed = promptUsed ?: report.languageIconPromptUsed,
                languageIconDurationMs = durationMs ?: report.languageIconDurationMs,
                timestamp = System.currentTimeMillis()
            )
            updated.totalCost = computeReportTotalCost(updated)
            saveReport(updated)
            true
        }
    }

    /** Additive cost bump for language-icon "Find alternative icons"
     *  fan-out calls. Every per-(provider, model) call adds to the
     *  report's language-icon cost so the row reflects total tokens
     *  spent searching for an icon for this language. Mirrors
     *  [bumpReportIconCost]. */
    fun bumpReportLanguageIconCost(
        context: Context, reportId: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val updated = report.copy(
                languageIconInputTokens = report.languageIconInputTokens + inputTokens,
                languageIconOutputTokens = report.languageIconOutputTokens + outputTokens,
                languageIconInputCost = report.languageIconInputCost + inputCost,
                languageIconOutputCost = report.languageIconOutputCost + outputCost,
                timestamp = System.currentTimeMillis()
            )
            // See bumpReportIconCost: language-icon Find-alt spend feeds
            // totalCost too, so recompute it here as well.
            updated.totalCost = computeReportTotalCost(updated)
            saveReport(updated)
            true
        }
    }

    /** Persist a failure reason for the language-icon call. Leaves
     *  any previously-resolved [Report.languageName] /
     *  [Report.languageIcon] alone (so a retry that errored doesn't
     *  blank out an earlier success). */
    fun updateReportLanguageError(context: Context, reportId: String, error: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                languageIconErrorMessage = error,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Commit a user pick from the language-icon "Alternative icons"
     *  screen. Replaces the emoji + sets [Report.languageIconModel]
     *  to the "<providerId>/<modelId>" label; leaves
     *  [Report.languageName] alone (the picker only changes the
     *  emoji, not the detected language). Clears any prior
     *  [Report.languageIconErrorMessage]. */
    fun setReportLanguageChoice(
        context: Context, reportId: String,
        icon: String, iconModel: String,
        /** Bundled prompt that produced the picked emoji — typically
         *  "language_alt". Surfaces on the Icon lookup screen. */
        promptUsed: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                languageIcon = icon,
                languageIconModel = iconModel,
                languageIconErrorMessage = null,
                languageIconPromptUsed = promptUsed ?: report.languageIconPromptUsed,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Wipe icon + error + tokens + cost so a regenerate-with-prompt-
     *  change run starts fresh on ⏳. Used by [regenerateReport] when
     *  the prompt was edited. */
    /** Regenerate-batch variant of [clearReportIcon] — clears the
     *  icon + iconErrorMessage so the row re-reads as "running"
     *  but PRESERVES the icon* cost / token counters. The
     *  dispatcher's additive cost write on the new icon-gen call
     *  adds its expenditure onto the prior. */
    fun clearReportIconKeepingCost(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                icon = null, iconErrorMessage = null,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    fun clearReportIcon(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                icon = null, iconErrorMessage = null, iconModel = null,
                iconInputTokens = 0, iconOutputTokens = 0,
                iconInputCost = 0.0, iconOutputCost = 0.0,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Reset language-flow placeholder fields on [reportId] —
     *  the detected language + the language-icon emoji + any
     *  error so the row reads as "running" again. Preserves
     *  language* / languageIcon* cost + token fields so the
     *  Regenerate-batch additive cost writes add the new call's
     *  numbers onto the prior expenditure. Used by
     *  [com.ai.viewmodel.RegenerateBatchEngine] when the LANGUAGE
     *  phase starts. */
    fun clearReportLanguage(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                languageName = null,
                languageIcon = null,
                languageIconErrorMessage = null,
                languageIconTraceFile = null,
                languageIconRawResponse = null,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Per-agent icon success path. Used by Create → Report icons:
     *  for each agent whose primary call succeeded, fires the
     *  internal/icon prompt against that agent's own (provider, model)
     *  with @PROMPT@ substituted by the agent's responseBody. The
     *  emoji + token usage + per-call cost lands here. Cost is rolled
     *  into Report.totalCost via the same `agents.sumOf(...)`
     *  recompute path the per-row cost cell reads from. */
    fun updateReportAgentIcon(
        context: Context, reportId: String, agentId: String,
        icon: String, inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val updated = report.agents[idx].copy(
                icon = icon, iconErrorMessage = null,
                iconInputTokens = inputTokens, iconOutputTokens = outputTokens,
                iconInputCost = inputCost, iconOutputCost = outputCost
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            val newReport = report.copy(agents = newAgents, timestamp = System.currentTimeMillis())
            newReport.totalCost = computeReportTotalCost(newReport)
            saveReport(newReport)
            true
        }
    }

    /** Per-agent icon failure path. Records the reason so the
     *  per-agent detail screen can surface it; the row itself stays
     *  on ✅ by design (the agent's primary call succeeded; only the
     *  secondary icon call didn't). */
    fun updateReportAgentIconError(
        context: Context, reportId: String, agentId: String, error: String
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val updated = report.agents[idx].copy(iconErrorMessage = error)
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            saveReport(report.copy(
                agents = newAgents,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Additive update for per-agent "Find alternative icons" fan-out
     *  calls. Mirrors [bumpReportIconCost] but writes onto the matching
     *  [ReportAgent]. Every per-pair call bumps the agent's icon cost
     *  whether or not the user later picks that result — the row's
     *  cost cell already folds these into the agent's primary cost. */
    fun bumpReportAgentIconCost(
        context: Context, reportId: String, agentId: String,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val updated = report.agents[idx].copy(
                iconInputTokens = report.agents[idx].iconInputTokens + inputTokens,
                iconOutputTokens = report.agents[idx].iconOutputTokens + outputTokens,
                iconInputCost = report.agents[idx].iconInputCost + inputCost,
                iconOutputCost = report.agents[idx].iconOutputCost + outputCost
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            val newReport = report.copy(agents = newAgents, timestamp = System.currentTimeMillis())
            newReport.totalCost = computeReportTotalCost(newReport)
            saveReport(newReport)
            true
        }
    }

    /** Commit a user-picked emoji from the per-agent "Alternative
     *  icons" list onto the matching [ReportAgent]. Replaces icon +
     *  clears iconErrorMessage, leaves cost fields alone — those have
     *  already been bumped per-call by [bumpReportAgentIconCost]. */
    fun setReportAgentIconChoice(
        context: Context, reportId: String, agentId: String, icon: String,
        /** Bundled prompt that produced the picked emoji. For
         *  Find-alt picks this is "report_alt"; surfaces on the
         *  Icon lookup screen's subject row. */
        promptUsed: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val prev = report.agents[idx]
            val updated = prev.copy(
                icon = icon, iconErrorMessage = null,
                // Find alternative icons is a manual pick — null the
                // tier flag so the per-agent detail screen falls back
                // to a "manual pick" branch instead of mis-attributing
                // to one of the 3 chain tiers.
                iconWinningTier = null,
                iconPromptUsed = promptUsed ?: prev.iconPromptUsed
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            saveReport(report.copy(
                agents = newAgents,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** 3-tier chain variant of [setReportAgentIconChoice]. Writes the
     *  winning emoji + the tier (1 / 2 / 3) that produced it; null
     *  tier records the 📝 fallback case where every tier failed.
     *  Cost fields untouched — the chain bumped them per call via
     *  [bumpReportAgentIconCost]. */
    fun setReportAgentIconAndTier(
        context: Context, reportId: String, agentId: String,
        icon: String, winningTier: Int?,
        /** Bundled prompt that produced [icon] — "report_1" for
         *  tier 1, "report_2" for tier 2, "report_3" for tier 3.
         *  Surfaces on the Icon lookup screen. */
        promptUsed: String? = null,
        traceFile: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val prev = report.agents[idx]
            val updated = prev.copy(
                icon = icon, iconErrorMessage = null,
                iconWinningTier = winningTier,
                iconTraceFile = traceFile ?: prev.iconTraceFile,
                iconPromptUsed = promptUsed ?: prev.iconPromptUsed
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            saveReport(report.copy(
                agents = newAgents,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
    }

    /** Write the per-agent model-title (and its cost/trace) produced by
     *  [com.ai.viewmodel.IconGenerationManager.runModelTitleForAgent].
     *  Costs are additive so a re-fire accumulates rather than clobbers. */
    fun updateReportAgentModelTitle(
        context: Context, reportId: String, agentId: String, title: String,
        model: String?,
        inputTokens: Int, outputTokens: Int,
        inputCost: Double, outputCost: Double,
        traceFile: String? = null,
        promptUsed: String? = null,
        durationMs: Long? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val prev = report.agents[idx]
            val updated = prev.copy(
                modelTitle = title, modelTitleErrorMessage = null,
                modelTitleModel = model ?: prev.modelTitleModel,
                modelTitleInputTokens = prev.modelTitleInputTokens + inputTokens,
                modelTitleOutputTokens = prev.modelTitleOutputTokens + outputTokens,
                modelTitleInputCost = prev.modelTitleInputCost + inputCost,
                modelTitleOutputCost = prev.modelTitleOutputCost + outputCost,
                modelTitleTraceFile = traceFile ?: prev.modelTitleTraceFile,
                modelTitleDurationMs = durationMs ?: prev.modelTitleDurationMs,
                modelTitlePromptUsed = promptUsed ?: prev.modelTitlePromptUsed
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            val newReport = report.copy(agents = newAgents, timestamp = System.currentTimeMillis())
            newReport.totalCost = computeReportTotalCost(newReport)
            saveReport(newReport)
            true
        }
    }

    /** Record a per-agent model-title generation failure so the row can
     *  surface it (and we don't retry on every recomposition). */
    fun updateReportAgentModelTitleError(
        context: Context, reportId: String, agentId: String, error: String
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val updated = report.agents[idx].copy(modelTitleErrorMessage = error)
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            saveReport(report.copy(agents = newAgents, timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Clear a per-agent model-title error so a "Restart errors" re-run reads
     *  the row as pending/running again instead of ❌. */
    fun clearReportAgentModelTitleError(context: Context, reportId: String, agentId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0 || report.agents[idx].modelTitleErrorMessage == null) return@withLock false
            val updated = report.agents[idx].copy(modelTitleErrorMessage = null)
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            saveReport(report.copy(agents = newAgents, timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Manually set an agent's model-title text (Get-info → Edit model
     *  title). Text-only — costs/trace/model are untouched; clears any
     *  prior error. Doesn't re-run anything. */
    fun setReportAgentModelTitleText(
        context: Context, reportId: String, agentId: String, title: String
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val updated = report.agents[idx].copy(
                modelTitle = title, modelTitleErrorMessage = null
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            saveReport(report.copy(agents = newAgents, timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Append one [IconCallRecord] onto [Report.iconCalls]. Called by
     *  [com.ai.viewmodel.ReportViewModel.runReportIcons] after every
     *  tier API call so the export's per-call All-tab can show each
     *  attempt as its own row. */
    fun appendIconCall(context: Context, reportId: String, record: IconCallRecord): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val newCalls = (report.iconCalls + record).toMutableList()
            val updated = report.copy(
                iconCalls = newCalls,
                timestamp = System.currentTimeMillis()
            )
            // Title-alt records are the only iconCalls that feed totalCost
            // directly (computeReportTotalCost sums TITLE_ALT_TYPES). Every
            // other type already has its cost bumped onto a structured
            // field by the caller, for which this recompute is a harmless
            // idempotent refresh. Either way the report total stays live as
            // the alt fan-out streams in.
            updated.totalCost = computeReportTotalCost(updated)
            saveReport(updated)
            true
        }
    }

    // -----------------------------------------------------------------
    // User notes — free-text annotations the user attaches to a report
    // and its parts. All notes for a report live on [Report.userNotes]
    // (one JSON file); the targetKind/targetId pair identifies what each
    // note is pinned to. See [UserNote].
    // -----------------------------------------------------------------

    /** Append a new [UserNote] to the report. Returns the created note
     *  (with its generated id + timestamps) or null when the report
     *  can't be loaded / [text] is blank. */
    fun addUserNote(
        context: Context, reportId: String,
        targetKind: String, targetId: String, text: String
    ): UserNote? {
        init(context)
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock null
            val now = System.currentTimeMillis()
            val note = UserNote(
                id = UUID.randomUUID().toString(),
                targetKind = targetKind, targetId = targetId,
                text = trimmed, createdAt = now, updatedAt = now
            )
            val newNotes = (report.userNotes + note).toMutableList()
            saveReport(report.copy(userNotes = newNotes, timestamp = now))
            note
        }
    }

    /** Replace the body of an existing note (by id). Returns false when
     *  the report / note isn't found or [text] is blank. */
    fun updateUserNote(context: Context, reportId: String, noteId: String, text: String): Boolean {
        init(context)
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            if (report.userNotes.none { it.id == noteId }) return@withLock false
            val now = System.currentTimeMillis()
            // Clear the stale title — the edited text gets a fresh AI title
            // (the caller re-fires note-title generation on save).
            val newNotes = report.userNotes.map {
                if (it.id == noteId) it.copy(text = trimmed, title = null, updatedAt = now) else it
            }.toMutableList()
            saveReport(report.copy(userNotes = newNotes, timestamp = now))
            true
        }
    }

    /** Set the AI-generated title on a note (by id). No-op when the report
     *  or note is gone (e.g. the note was deleted while the title call was
     *  in flight). */
    fun setUserNoteTitle(context: Context, reportId: String, noteId: String, title: String): Boolean {
        init(context)
        val trimmed = title.trim()
        if (trimmed.isBlank()) return false
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            if (report.userNotes.none { it.id == noteId }) return@withLock false
            val newNotes = report.userNotes.map {
                if (it.id == noteId) it.copy(title = trimmed) else it
            }.toMutableList()
            saveReport(report.copy(userNotes = newNotes, timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Remove a note by id. Returns false when nothing was removed. The
     *  note's `note/title` iconCalls are pruned and their cost rolled into
     *  [Report.costsFromDeletedItems] so the lifetime total stays stable
     *  (same convention as [removeAgent]). */
    fun deleteUserNote(context: Context, reportId: String, noteId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val newNotes = report.userNotes.filterNot { it.id == noteId }.toMutableList()
            if (newNotes.size == report.userNotes.size) return@withLock false
            report.userNotes = newNotes
            rollNoteTitleCostsToDeleted(report, setOf(noteId))
            report.totalCost = computeReportTotalCost(report)
            saveReport(report.copy(timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Move the `note/title` iconCall spend for [noteIds] into
     *  [Report.costsFromDeletedItems] and drop those records, so deleting a
     *  note doesn't make the lifetime total shrink or leave an orphan cost
     *  row. Mutates [report] in place. */
    private fun rollNoteTitleCostsToDeleted(report: Report, noteIds: Set<String>) {
        if (noteIds.isEmpty()) return
        val removed = report.iconCalls.filter { it.type == "note/title" && it.agentId in noteIds }
        if (removed.isEmpty()) return
        removed.sumOf { it.inputCost + it.outputCost }.takeIf { it > 0.0 }
            ?.let { report.costsFromDeletedItems += it }
        report.iconCalls = report.iconCalls
            .filterNot { it.type == "note/title" && it.agentId in noteIds }
            .toMutableList()
    }

    /** Drop every fan-out icon-chain [IconCallRecord] from the
     *  report's iconCalls audit log — the records whose agentId is a
     *  fan-out pair id (in [pairIds], since fan-out tier calls record
     *  the pair's UUID as agentId). Used when the user deletes a
     *  fan-out's icons without deleting the fan-out itself. */
    fun removeFanOutIconCalls(context: Context, reportId: String, pairIds: Set<String>): Boolean {
        init(context)
        if (pairIds.isEmpty()) return false
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val newCalls = report.iconCalls
                .filterNot { it.agentId in pairIds || it.attributedToSecondaryId in pairIds }
                .toMutableList()
            if (newCalls.size == report.iconCalls.size) return@withLock false
            saveReport(report.copy(iconCalls = newCalls, timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Remove per-call audit rows attributed to deleted secondary results.
     *  Their spend is already carried by the deleted SecondaryResult's
     *  aggregate cost and gets moved into costsFromDeletedItems by the
     *  caller; keeping the audit rows would double-count the same calls. */
    fun removeIconCallsForSecondaryIds(context: Context, reportId: String, secondaryIds: Set<String>): Boolean {
        init(context)
        if (secondaryIds.isEmpty()) return false
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val newCalls = report.iconCalls
                .filterNot { it.attributedToSecondaryId in secondaryIds || it.agentId in secondaryIds }
                .toMutableList()
            if (newCalls.size == report.iconCalls.size) return@withLock false
            saveReport(report.copy(iconCalls = newCalls, timestamp = System.currentTimeMillis()))
            true
        }
    }

    /** Per-agent counterpart of [clearAllReportAgentIcons]: wipes
     *  ONE agent's icon fields and removes its entries from the
     *  report's iconCalls audit log. Called at the top of the
     *  per-agent icon driver so a re-fire (regenerate) starts the
     *  agent's chain on a clean slate without disturbing other
     *  agents' icons. */
    fun clearReportAgentIconState(context: Context, reportId: String, agentId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val cleared = report.agents[idx].copy(
                icon = null, iconErrorMessage = null,
                iconInputTokens = 0, iconOutputTokens = 0,
                iconInputCost = 0.0, iconOutputCost = 0.0,
                iconWinningTier = null
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = cleared }
            val newCalls = report.iconCalls.filter { it.agentId != agentId }.toMutableList()
            val newReport = report.copy(agents = newAgents, iconCalls = newCalls, timestamp = System.currentTimeMillis())
            newReport.totalCost = computeReportTotalCost(newReport)
            saveReport(newReport)
            true
        }
    }

    /** Wipe per-agent icon fields across every agent in the report.
     *  Used by Create → Report icons at the start of a re-run so the
     *  second run doesn't show stale emojis from the first while
     *  the new calls are still in flight. Also clears the per-call
     *  audit list and the per-agent winning-tier flags so the new
     *  run starts with a clean slate everywhere. */
    fun clearAllReportAgentIcons(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val newAgents = report.agents.map { a ->
                a.copy(
                    icon = null, iconErrorMessage = null,
                    iconInputTokens = 0, iconOutputTokens = 0,
                    iconInputCost = 0.0, iconOutputCost = 0.0,
                    iconWinningTier = null
                )
            }.toMutableList()
            val newReport = report.copy(
                agents = newAgents,
                iconCalls = mutableListOf(),
                timestamp = System.currentTimeMillis()
            )
            newReport.totalCost = computeReportTotalCost(newReport)
            saveReport(newReport)
            true
        }
    }

    fun updateReportPromptText(context: Context, reportId: String, newPrompt: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            if (report.prompt == newPrompt) return@withLock true   // no-op, no history churn
            // Push the superseded prompt onto the revision timeline,
            // skipping blanks and exact dupes of the latest entry.
            val history = if (report.prompt.isNotBlank() &&
                report.promptHistory.lastOrNull()?.prompt != report.prompt) {
                report.promptHistory + PromptRevision(report.prompt)
            } else report.promptHistory
            saveReport(report.copy(prompt = newPrompt, promptHistory = history))
            true
        }
    }

    /** Drop a single ReportAgent row from the report, recompute totalCost,
     *  and persist. The deleted row's cost (if any) is added to
     *  [Report.costsFromDeletedItems] so the result page can still
     *  show the API spend for the run after the row is gone. Used by
     *  the per-model viewer's "Remove model from report" button so
     *  the user can prune dud responses without rebuilding the whole
     *  report. Returns false when the report or agent isn't found. */
    fun removeAgent(context: Context, reportId: String, agentId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return@withLock false
            val removed = report.agents.removeAt(idx)
            removed.cost?.takeIf { it > 0.0 }?.let {
                report.costsFromDeletedItems += it
            }
            // Per-agent icon spend also stays out of the live total
            // once the row is gone — same as the primary cost field.
            (removed.iconInputCost + removed.iconOutputCost).takeIf { it > 0.0 }?.let {
                report.costsFromDeletedItems += it
            }
            // Per-agent model-title spend is summed into totalCost too
            // (computeReportTotalCost adds modelTitleInputCost/OutputCost),
            // so it must likewise roll into costsFromDeletedItems or the
            // deleted row's title spend vanishes from the run's history.
            (removed.modelTitleInputCost + removed.modelTitleOutputCost).takeIf { it > 0.0 }?.let {
                report.costsFromDeletedItems += it
            }
            val removedCalls = report.iconCalls.filter { it.agentId == agentId }
            val structuredIconTypes = setOf<String?>(null, "model/icons", "alt/report")
            removedCalls
                .filterNot { it.type in structuredIconTypes }
                .sumOf { it.inputCost + it.outputCost }
                .takeIf { it > 0.0 }
                ?.let { report.costsFromDeletedItems += it }
            if (removedCalls.isNotEmpty()) {
                report.iconCalls = report.iconCalls.filterNot { it.agentId == agentId }.toMutableList()
            }
            // Prune the deleted agent's user notes — same spirit as the
            // iconCalls prune above, so the 📒 all-notes list doesn't show
            // notes pinned to a model that's gone. Their note/title spend
            // rolls into costsFromDeletedItems (like the agent's own costs).
            val prunedNoteIds = report.userNotes
                .filter { it.targetKind == "AGENT" && it.targetId == agentId }
                .map { it.id }.toSet()
            report.userNotes = report.userNotes
                .filterNot { it.targetKind == "AGENT" && it.targetId == agentId }
                .toMutableList()
            rollNoteTitleCostsToDeleted(report, prunedNoteIds)
            report.totalCost = computeReportTotalCost(report)
            saveReport(report)
            true
        }
    }

    /** Bump [Report.costsFromDeletedItems] by [deltaUsd] for [reportId]
     *  and persist. Called by the secondary-result + translation delete
     *  paths so cost stays accounted for after the row disappears.
     *  Negative or zero deltas are ignored; missing reports no-op. */
    fun bumpCostsFromDeletedItems(context: Context, reportId: String, deltaUsd: Double) {
        if (deltaUsd <= 0.0) return
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            report.costsFromDeletedItems += deltaUsd
            saveReport(report)
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
    /** Regenerate-batch variant of [resetAgentToPending] — clears
     *  every "result" field on an agent (status, body, error,
     *  citations, duration, icon) BUT preserves cost + tokenUsage
     *  so the dispatcher's additive cost write adds the new
     *  call's expenditure onto the prior. */
    fun resetAgentToPendingKeepingCost(context: Context, reportId: String, agentId: String) {
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
            agent.citations = null
            agent.searchResults = null
            agent.relatedQuestions = null
            agent.rawUsageJson = null
            agent.durationMs = null
            // Per-agent main-call icon belongs to the previous
            // response; clear it (but NOT the icon-call cost
            // counters which represent prior expenditure).
            agent.icon = null
            agent.iconErrorMessage = null
            report.completedAt = null
            saveReport(report)
        }
    }

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
            // The split-cost halves are written *additively* by
            // updateAgentStatus, so a re-run would accumulate onto the
            // stale values if we left them. Clear them with `cost`.
            agent.inputCost = null
            agent.outputCost = null
            agent.citations = null
            agent.searchResults = null
            agent.relatedQuestions = null
            agent.rawUsageJson = null
            agent.durationMs = null
            // Trace pointer belongs to the response we just discarded;
            // a pending/error row must not point 🐞 at a call that no
            // longer corresponds to its content.
            agent.traceFile = null
            // Per-agent icon belongs to the previous response; clear
            // it too so a regenerate doesn't keep a stale emoji from
            // an answer that no longer exists.
            agent.icon = null
            agent.iconErrorMessage = null
            agent.iconInputTokens = 0
            agent.iconOutputTokens = 0
            agent.iconInputCost = 0.0
            agent.iconOutputCost = 0.0
            agent.iconTraceFile = null
            report.totalCost = computeReportTotalCost(report)
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
    /** Persist a fully-formed [Report] verbatim. Used by the
     *  "Create Report from fan-out" flow which constructs a complete
     *  report off-screen (prompt + ready-made agent rows) and just
     *  needs it on disk. Caller is responsible for setting
     *  completedAt / totalCost / sourceReportId. Mirrors the same
     *  init + lock + saveReport pattern as [createReport]. */
    fun persistReport(context: Context, report: Report) {
        init(context)
        lock.withLock { saveReport(report) }
    }

    fun copyReport(context: Context, reportId: String): String? {
        init(context)
        return lock.withLock {
            val src = loadReport(reportId) ?: return@withLock null
            val newId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            // Deep-copy each agent so further mutations on the original
            // don't leak into the copy through the shared ReportAgent
            // reference, AND terminalize any in-flight row: a copy
            // carries no generation job, so a PENDING / RUNNING status
            // duplicated verbatim (the user duplicated mid-run) would
            // spin forever on the copy with nothing driving it. Mark
            // those STOPPED — mirrors the stop-non-terminal sweep used
            // when a real run is cancelled.
            val copiedAgents = src.agents.map { a ->
                val c = a.copy()
                if (c.reportStatus == ReportStatus.PENDING || c.reportStatus == ReportStatus.RUNNING) {
                    c.reportStatus = ReportStatus.STOPPED
                }
                c
            }.toMutableList()
            val copy = Report(
                id = newId,
                timestamp = now,
                createdAt = now,
                title = if (src.title.endsWith("(Copy)")) src.title else "${src.title} (Copy)",
                titleLong = src.titleLong,
                prompt = src.prompt,
                agents = copiedAgents,
                totalCost = src.totalCost,
                // Every row on the copy is terminal now, so the copy is
                // complete even if the source was still generating. Keep
                // the source's stamp when it had one, else stamp now.
                completedAt = src.completedAt ?: now,
                rapportText = src.rapportText,
                reportType = src.reportType,
                closeText = src.closeText,
                imageBase64 = src.imageBase64,
                imageMime = src.imageMime,
                webSearchTool = src.webSearchTool,
                reasoningEffort = src.reasoningEffort,
                sourceReportId = src.sourceReportId,
                // RAG context the source report ran against — without
                // copying this list a Regenerate on the copy runs with
                // zero attached KBs and silently produces different
                // output than the original. `pinned` intentionally stays
                // at the default false: a copy shouldn't inherit pin
                // status, that's a fresh user choice on the new entry.
                knowledgeBaseIds = src.knowledgeBaseIds,
                // No history of deletions on the brand-new copy — the
                // running tally on the source reflected rows the user
                // had trimmed from THAT report. Starting it at 0 lets
                // the user trim the copy and have its tally reflect
                // only what they deleted there.
                costsFromDeletedItems = 0.0,
                // Captured generation config — without these a Regenerate
                // on the copy replays with default params / no system
                // prompt and silently diverges from the original, even
                // though KB / web / reasoning were copied above.
                parameterPresetIds = src.parameterPresetIds,
                advancedParameters = src.advancedParameters,
                selectionParamsById = src.selectionParamsById,
                reportSystemPromptId = src.reportSystemPromptId
            )
            // Mirror the icon + its error from the source. The copy
            // makes no new API call, so without this the copy sits
            // with icon=null and errorMessage=null, which the result-
            // screen icon row interprets as "still generating" — the
            // spinner stayed forever. Icon tokens / costs stay at 0
            // on the copy: the icon-gen API call was already billed
            // on the source.
            copy.icon = src.icon
            copy.iconErrorMessage = src.iconErrorMessage
            // Same shape for the language-detection visible state:
            // without these three, the language row would also spin
            // ⏳ "Detecting…" forever on the copy even though no
            // detection is running. Tokens / costs / trace files /
            // raw responses + the icon-prompt metadata are
            // deliberately left at defaults — the source already
            // paid for those calls; double-counting would skew any
            // cross-report aggregate.
            copy.languageName = src.languageName
            copy.languageIcon = src.languageIcon
            copy.languageIconErrorMessage = src.languageIconErrorMessage
            saveReport(copy)
            newId
        }
    }
}
