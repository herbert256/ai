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
    /** Persisted USD cost split, captured at run completion using
     *  the [com.ai.data.PricingCache] prices in effect at that
     *  moment. Pinned at run time — the View / Manage cost cards
     *  read these straight from disk so a later catalog re-price
     *  doesn't shift the historical agent cost. Null on legacy
     *  rows written before the freeze landed; the renderer falls
     *  back to live PricingCache recomputation for those. */
    var inputCost: Double? = null,
    var outputCost: Double? = null,
    var citations: List<String>? = null,
    var searchResults: List<SearchResult>? = null,
    var relatedQuestions: List<String>? = null,
    var rawUsageJson: String? = null,
    var durationMs: Long? = null,
    /** Per-agent icon produced by Create → Report icons. Filled
     *  by [ReportViewModel.runReportIcons] which fires the
     *  internal/icon prompt against this agent's own (provider,
     *  model) with @PROMPT@ replaced by [responseBody]. Null until
     *  the user runs Report icons; null too when the call errored
     *  (see [iconErrorMessage]) — the row's ✅ stays unchanged in
     *  that case. */
    var icon: String? = null,
    var iconErrorMessage: String? = null,
    var iconInputTokens: Int = 0,
    var iconOutputTokens: Int = 0,
    var iconInputCost: Double = 0.0,
    var iconOutputCost: Double = 0.0,
    /** Which tier of the 3-tier Create → Report icons chain produced
     *  the agent's current icon. 1 = chat continuation, 2 = one-shot
     *  internal/report, 3 = fixed-agent fallback against
     *  internal/report_3. Null when no tier succeeded and the
     *  icon is the 📝 fallback, or when the icon was set manually
     *  via Find alternative icons. Used by the Icon lookup screen
     *  to derive the bundled prompt name when [iconPromptUsed] is
     *  null (legacy rows). */
    var iconWinningTier: Int? = null,
    /** Bundled prompt name that produced the currently-displayed
     *  emoji on this agent — e.g. "report" / "report_2" / "report_3"
     *  for the 3-tier chain or "report_alt" after a Find-alt pick.
     *  Surfaces on the Icon lookup screen as the green subject row.
     *  Null on legacy rows; the screen falls back to deriving the
     *  name from [iconWinningTier]. */
    var iconPromptUsed: String? = null
)

/** One captured API call from the 3-tier Create → Report icons
 *  chain. Stored on [Report.iconCalls] so the per-call All-tab in
 *  the cost export can render every attempt — including the failed
 *  earlier tiers that the chain skipped past. Same field set the
 *  export's renderCostsView Row already shows.
 *
 *  Provider + model identify the actual model that billed the call
 *  (tier 3 = DeepSeek even when the agent row itself uses a
 *  different model), which is what the global UsageStats ledger
 *  attributes too. */
data class IconCallRecord(
    val agentId: String,
    val tier: Int,
    val provider: String,
    val model: String,
    val pricingTier: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val inputCost: Double,
    val outputCost: Double,
    val durationMs: Long? = null,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    /** When non-null, overrides the agentId-based classifier used by
     *  the cost table. Find-alternative-icons fan-out calls set this
     *  to the bundled `_alt` prompt name (`main_alt`, `meta_alt`,
     *  `report_alt`, `language_alt`, `translation_alt`) so each
     *  alt call surfaces as its own per-call row labelled by prompt
     *  name. Null for the legacy 3-tier per-agent / per-pair records
     *  — those keep using the existing agentId classifier. */
    val type: String? = null,
    /** When non-null, the cost on this record is attributed to a
     *  specific [SecondaryResult] on the same report. Used by
     *  meta_alt (the user's tapped secondary-result row) and
     *  translation_alt (the first TRANSLATE row of the language) so
     *  the cost table can subtract the attributed portion from the
     *  SR's own cost row — avoiding double-counting once the per-
     *  call alt rows are added below it. */
    val attributedToSecondaryId: String? = null
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
    val sourceReportId: String? = null,
    /** Knowledge bases attached to this report. Each agent call has
     *  its prompt prefixed with a context block built from top-K
     *  chunks across these KBs (see KnowledgeService.retrieve /
     *  formatContextBlock). Empty when no RAG is wired. */
    val knowledgeBaseIds: List<String> = emptyList(),
    /** User-pinned flag. Pinned reports surface as their own group
     *  above the recent rows on the AI Reports hub. Persisted on the
     *  Report file so it survives across launches. */
    var pinned: Boolean = false,
    /** Sum of input + output cost (USD) for every row deleted from
     *  this report — agent rows, secondary results, fan-out pairs,
     *  fan-in rows, translations, etc. Bumped on every delete by the
     *  callers that own the deletion path; surfaced as a dedicated
     *  "Costs from deleted items" line on the result page just above
     *  the total when non-zero. Lets the user see what the API
     *  actually billed for the run even after they've trimmed the
     *  visible rows. */
    var costsFromDeletedItems: Double = 0.0,
    /** Resolved emoji for this report. Filled by a background LLM
     *  call kicked off at the start of generation that runs the
     *  bundled `internal/icon` prompt against its pinned agent. Null
     *  while the call is in flight, while no `internal/icon` prompt
     *  is configured, on call failure (see [iconErrorMessage]), or
     *  on legacy reports created before the feature shipped.
     *  Surfaces everywhere a report is shown — hub Existing reports,
     *  History rows, search hits, the result-screen title bar. */
    var icon: String? = null,
    /** Failure reason from the icon-gen call. Null while running, on
     *  success, or when the call was never kicked off. Set instead of
     *  [icon] when the LLM returned an error or an empty body. */
    var iconErrorMessage: String? = null,
    /** Token usage + USD cost of the icon-gen call, split input vs
     *  output so the call surfaces as a proper row in the per-call
     *  cost tables (in-app View → Costs and the HTML export).
     *  Computed at write time from the call's token usage × the
     *  (provider, model) pricing tier; rolled into the report total
     *  alongside agent and secondary spend. All zero while running,
     *  on missing pricing, or on legacy reports. */
    var iconInputTokens: Int = 0,
    var iconOutputTokens: Int = 0,
    var iconInputCost: Double = 0.0,
    var iconOutputCost: Double = 0.0,
    /** Trace filename captured for the report-icon call (the bundled
     *  internal/icon prompt run). Lets the icon detail screen wire
     *  its 🐞 directly to that trace. Null when tracing is off, on
     *  legacy reports, or during the cold window before the call
     *  returned. */
    var iconTraceFile: String? = null,
    /** When non-null, the model that produced the currently displayed
     *  icon (set by "Find alternative icons" — the user picked a
     *  fan-out result over the bundled-agent default). When null, the
     *  icon row / detail screen fall back to the pinned icon-prompt
     *  agent label. Stored as "<providerId>/<modelId>" so the row
     *  never has to re-resolve through the agent list. */
    var iconModel: String? = null,
    /** Bundled prompt name that produced the currently-displayed
     *  [icon]. "main" on initial gen; "main_alt" after a Find-alt
     *  pick. Surfaces on the Icon lookup screen as the green
     *  subject row. Null on legacy reports — Icon lookup falls
     *  back to "main". */
    var iconPromptUsed: String? = null,
    /** Per-call audit log for the 3-tier Create → Report icons
     *  chain. Cleared whenever a fresh chain run starts; otherwise
     *  every tier appends one [IconCallRecord]. The export's per-
     *  call All-tab pulls from this list so every attempt (failed
     *  earlier tiers + the one that won) surfaces as its own row. */
    var iconCalls: MutableList<IconCallRecord> = mutableListOf(),
    /** UUID shared by every API trace produced during the initial
     *  generation of this report — the L1 🐞 icon in the title bar
     *  deep-links the trace list to this id so the user sees only
     *  the calls this report's run made. Null on reports persisted
     *  before this field existed. */
    var runId: String? = null,
    /** Detected English name of the prompt's source language
     *  (e.g. "Dutch", "English"), produced by the bundled
     *  `internal/language_icon` prompt running against its pinned
     *  agent (DeepSeek by default) when icon-gen is on. Null while
     *  the detection call is in flight, on failure (see
     *  [languageIconErrorMessage]), or on legacy reports created
     *  before the feature shipped. */
    var languageName: String? = null,
    /** Fitting emoji for [languageName], picked by the same
     *  bundled-agent call. Surfaces as the icon cell on the
     *  Report - manage screen's "language" row. Null whenever
     *  [languageName] is null. */
    var languageIcon: String? = null,
    /** When non-null, the model that produced the currently
     *  displayed [languageIcon] — set by the language-icon "Find
     *  alternative icons" fan-out pick. Null = bundled agent
     *  default; falls back to the pinned `internal/language_icon`
     *  agent label in the detail screen. Stored as
     *  "<providerId>/<modelId>". */
    var languageIconModel: String? = null,
    /** Bundled prompt name that produced the currently-displayed
     *  [languageIcon]. "language" on initial gen (the second-call
     *  emoji prompt); "language_alt" after a Find-alt pick.
     *  Surfaces on the Icon lookup screen as the green subject
     *  row. Null on legacy reports — Icon lookup falls back to
     *  "language". */
    var languageIconPromptUsed: String? = null,
    /** Failure reason from the language-icon call. Null while
     *  running, on success, or when the call was never kicked off.
     *  Surfaces on the manage-screen row as a ❌ + message and on
     *  the detail screen's Response card. */
    var languageIconErrorMessage: String? = null,
    /** Token usage + USD cost of the SECOND language-flow call (the
     *  one that picks an emoji for the already-detected language).
     *  Surfaces as the cost row labelled "language-icon" on the
     *  Report - manage screen and HTML export. Mirrors the icon-gen
     *  cost fields above. All zero while running, on missing pricing,
     *  or on legacy reports written before the two-call split.
     *  Bumped per-call when "Find alternative icons" runs a
     *  language-icon fan-out. */
    var languageIconInputTokens: Int = 0,
    var languageIconOutputTokens: Int = 0,
    var languageIconInputCost: Double = 0.0,
    var languageIconOutputCost: Double = 0.0,
    /** Trace filename of the language-icon API call. Wires 🐞 on
     *  the language detail screen. Null when tracing is off, on
     *  legacy reports, or during the cold window. */
    var languageIconTraceFile: String? = null,
    /** Raw assistant text returned by the language-icon model (the
     *  single emoji). The language detail screen renders this
     *  verbatim so the user sees exactly what the model said. Null
     *  on legacy reports / before the call returned. */
    var languageIconRawResponse: String? = null,
    /** Token usage + USD cost of the FIRST language-flow call (the
     *  one that detects [languageName]). Tracked separately from
     *  the icon call above so the cost table can show two rows:
     *  type = "language" for the detection call, type =
     *  "language-icon" for the icon call. Zero on legacy reports
     *  whose single combined call was logged under
     *  [languageIconInputCost] instead. */
    var languageInputTokens: Int = 0,
    var languageOutputTokens: Int = 0,
    var languageInputCost: Double = 0.0,
    var languageOutputCost: Double = 0.0,
    /** Trace filename of the language-detection API call. Wires 🐞
     *  on the detection row's cost popup. Null when tracing was off
     *  at call time or on legacy reports. */
    var languageTraceFile: String? = null,
    /** Raw assistant text returned by the language-detection model
     *  (typically a single `language: …` line). Null on legacy
     *  reports / before the call returned. */
    var languageRawResponse: String? = null
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
        sourceReportId: String? = null,
        knowledgeBaseIds: List<String> = emptyList(),
        runId: String? = null
    ): Report {
        init(context)
        val report = Report(explicitId ?: UUID.randomUUID().toString(), System.currentTimeMillis(), title, prompt,
            agents.toMutableList(), rapportText = rapportText, reportType = reportType, closeText = closeText,
            imageBase64 = imageBase64, imageMime = imageMime, webSearchTool = webSearchTool,
            reasoningEffort = reasoningEffort, sourceReportId = sourceReportId,
            knowledgeBaseIds = knowledgeBaseIds, runId = runId)
        lock.withLock { saveReport(report) }
        return report
    }

    fun updateAgentStatus(
        context: Context, reportId: String, agentId: String, status: ReportStatus,
        httpStatus: Int? = null, requestHeaders: String? = null, requestBody: String? = null,
        responseHeaders: String? = null, responseBody: String? = null, errorMessage: String? = null,
        tokenUsage: TokenUsage? = null, cost: Double? = null,
        inputCost: Double? = null, outputCost: Double? = null,
        citations: List<String>? = null,
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
            if (cost != null) {
                agent.cost = cost
                report.totalCost = report.agents.mapNotNull { it.cost }.sum() +
                    report.agents.sumOf { it.iconInputCost + it.iconOutputCost }
            }
            // Pin the in / out cost halves at run time so a later
            // catalog re-price doesn't shift the historical
            // numbers shown on the Costs cards.
            if (inputCost != null) agent.inputCost = inputCost
            if (outputCost != null) agent.outputCost = outputCost
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
        inputCost: Double? = null, outputCost: Double? = null,
        citations: List<String>? = null, searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null, rawUsageJson: String? = null, durationMs: Long? = null
    ) = updateAgentStatus(context, reportId, agentId, ReportStatus.SUCCESS, httpStatus,
        responseHeaders = responseHeaders, responseBody = responseBody, tokenUsage = tokenUsage,
        cost = cost, inputCost = inputCost, outputCost = outputCost,
        citations = citations, searchResults = searchResults,
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
        sourceReportId: String? = null,
        knowledgeBaseIds: List<String> = emptyList(),
        runId: String? = null
    ): Report = withContext(Dispatchers.IO) { createReport(context, title, prompt, agents, rapportText, reportType, closeText, imageBase64, imageMime, webSearchTool, reasoningEffort, explicitId, sourceReportId, knowledgeBaseIds, runId) }

    suspend fun markAgentRunningAsync(context: Context, reportId: String, agentId: String, requestHeaders: String? = null, requestBody: String? = null) =
        withContext(Dispatchers.IO) { markAgentRunning(context, reportId, agentId, requestHeaders, requestBody) }

    suspend fun markAgentSuccessAsync(
        context: Context, reportId: String, agentId: String, httpStatus: Int,
        responseHeaders: String?, responseBody: String?, tokenUsage: TokenUsage?, cost: Double?,
        inputCost: Double? = null, outputCost: Double? = null,
        citations: List<String>? = null, searchResults: List<SearchResult>? = null,
        relatedQuestions: List<String>? = null, rawUsageJson: String? = null, durationMs: Long? = null
    ) = withContext(Dispatchers.IO) {
        markAgentSuccess(
            context, reportId, agentId, httpStatus, responseHeaders, responseBody, tokenUsage, cost,
            inputCost, outputCost,
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
    }
    fun deleteAllReports(context: Context): Int {
        init(context)
        var deleted = 0
        lock.withLock {
            reportsDir?.listFiles { f -> f.extension == "json" }?.forEach { f ->
                val reportId = f.nameWithoutExtension
                if (f.delete()) deleted++
                SecondaryResultStorage.deleteAllForReport(context, reportId)
            }
        }
        return deleted
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
        return try { gson.fromJson(file.readText(), Report::class.java) } catch (e: Exception) {
            AppLog.e("ReportStorage", "Failed to load report $reportId: ${e.message}"); null
        }
    }

    private fun loadAllReports(): List<Report> {
        val files = reportsDir?.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try { gson.fromJson(file.readText(), Report::class.java) } catch (e: Exception) {
                AppLog.e("ReportStorage", "Failed to load ${file.name}: ${e.message}"); null
            }
        }
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

    /** Add the prior cost back onto an agent's now-fresh cost
     *  fields after a successful Regenerate-batch agent re-dispatch.
     *  Without this the new call's cost overwrites the previous
     *  run's cost; the user paid for both, so the displayed cost
     *  should be the sum. */
    fun accumulateAgentCost(
        context: Context, reportId: String, agentId: String,
        addInputTokens: Int, addOutputTokens: Int,
        addInputCost: Double, addOutputCost: Double
    ) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            val idx = report.agents.indexOfFirst { it.agentId == agentId }
            if (idx < 0) return
            val a = report.agents[idx]
            val newIn = (a.inputCost ?: 0.0) + addInputCost
            val newOut = (a.outputCost ?: 0.0) + addOutputCost
            val updated = a.copy(
                inputCost = newIn,
                outputCost = newOut,
                cost = newIn + newOut,
                tokenUsage = TokenUsage(
                    inputTokens = (a.tokenUsage?.inputTokens ?: 0) + addInputTokens,
                    outputTokens = (a.tokenUsage?.outputTokens ?: 0) + addOutputTokens
                )
            )
            val newAgents = report.agents.toMutableList().also { it[idx] = updated }
            val newTotal = newAgents.mapNotNull { it.cost }.sum() +
                newAgents.sumOf { it.iconInputCost + it.iconOutputCost }
            saveReport(report.copy(
                agents = newAgents,
                totalCost = newTotal,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    /** Add prior icon-gen cost back onto the report's icon* fields
     *  after a successful Regenerate-batch ICON-phase re-dispatch. */
    fun accumulateReportIconCost(
        context: Context, reportId: String,
        addInputTokens: Int, addOutputTokens: Int,
        addInputCost: Double, addOutputCost: Double
    ) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            saveReport(report.copy(
                iconInputTokens = report.iconInputTokens + addInputTokens,
                iconOutputTokens = report.iconOutputTokens + addOutputTokens,
                iconInputCost = report.iconInputCost + addInputCost,
                iconOutputCost = report.iconOutputCost + addOutputCost,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    /** Add prior language-flow costs back onto the report's
     *  language* + languageIcon* fields after a successful
     *  Regenerate-batch LANGUAGE-phase re-dispatch. Both the
     *  detection call and the icon call are folded into one
     *  accumulator since the engine treats LANGUAGE as one phase. */
    fun accumulateReportLanguageCost(
        context: Context, reportId: String,
        addLangInputTokens: Int, addLangOutputTokens: Int,
        addLangInputCost: Double, addLangOutputCost: Double,
        addLangIconInputTokens: Int, addLangIconOutputTokens: Int,
        addLangIconInputCost: Double, addLangIconOutputCost: Double
    ) {
        init(context)
        lock.withLock {
            val report = loadReport(reportId) ?: return
            saveReport(report.copy(
                languageInputTokens = report.languageInputTokens + addLangInputTokens,
                languageOutputTokens = report.languageOutputTokens + addLangOutputTokens,
                languageInputCost = report.languageInputCost + addLangInputCost,
                languageOutputCost = report.languageOutputCost + addLangOutputCost,
                languageIconInputTokens = report.languageIconInputTokens + addLangIconInputTokens,
                languageIconOutputTokens = report.languageIconOutputTokens + addLangIconOutputTokens,
                languageIconInputCost = report.languageIconInputCost + addLangIconInputCost,
                languageIconOutputCost = report.languageIconOutputCost + addLangIconOutputCost,
                timestamp = System.currentTimeMillis()
            ))
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
        promptUsed: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                icon = icon, iconErrorMessage = null,
                iconInputTokens = inputTokens, iconOutputTokens = outputTokens,
                iconInputCost = inputCost, iconOutputCost = outputCost,
                iconTraceFile = traceFile,
                iconPromptUsed = promptUsed ?: report.iconPromptUsed,
                timestamp = System.currentTimeMillis()
            ))
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
            saveReport(report.copy(
                iconInputTokens = report.iconInputTokens + inputTokens,
                iconOutputTokens = report.iconOutputTokens + outputTokens,
                iconInputCost = report.iconInputCost + inputCost,
                iconOutputCost = report.iconOutputCost + outputCost,
                timestamp = System.currentTimeMillis()
            ))
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
        rawResponse: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                languageName = name,
                languageIconErrorMessage = null,
                languageInputTokens = inputTokens,
                languageOutputTokens = outputTokens,
                languageInputCost = inputCost,
                languageOutputCost = outputCost,
                languageTraceFile = traceFile,
                languageRawResponse = rawResponse,
                timestamp = System.currentTimeMillis()
            ))
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
        promptUsed: String? = null
    ): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                languageIcon = icon,
                languageIconModel = model,
                languageIconErrorMessage = null,
                languageIconInputTokens = inputTokens,
                languageIconOutputTokens = outputTokens,
                languageIconInputCost = inputCost,
                languageIconOutputCost = outputCost,
                languageIconTraceFile = traceFile,
                languageIconRawResponse = rawResponse,
                languageIconPromptUsed = promptUsed ?: report.languageIconPromptUsed,
                timestamp = System.currentTimeMillis()
            ))
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
            saveReport(report.copy(
                languageIconInputTokens = report.languageIconInputTokens + inputTokens,
                languageIconOutputTokens = report.languageIconOutputTokens + outputTokens,
                languageIconInputCost = report.languageIconInputCost + inputCost,
                languageIconOutputCost = report.languageIconOutputCost + outputCost,
                timestamp = System.currentTimeMillis()
            ))
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

    /** Reset every language-flow field on [reportId] — both the
     *  detection call (languageName + cost fields) and the icon
     *  call (languageIcon + cost / error / trace fields). Used by
     *  [com.ai.viewmodel.RegenerateBatchEngine] when the LANGUAGE
     *  phase starts so the row shows ⏳ before the dispatcher fires. */
    fun clearReportLanguage(context: Context, reportId: String): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            saveReport(report.copy(
                languageName = null,
                languageIcon = null,
                languageIconModel = null,
                languageIconPromptUsed = null,
                languageIconErrorMessage = null,
                languageIconInputTokens = 0,
                languageIconOutputTokens = 0,
                languageIconInputCost = 0.0,
                languageIconOutputCost = 0.0,
                languageIconTraceFile = null,
                languageIconRawResponse = null,
                languageInputTokens = 0,
                languageOutputTokens = 0,
                languageInputCost = 0.0,
                languageOutputCost = 0.0,
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
            val newTotal = newAgents.mapNotNull { it.cost }.sum() +
                newAgents.sumOf { it.iconInputCost + it.iconOutputCost }
            saveReport(report.copy(
                agents = newAgents, totalCost = newTotal,
                timestamp = System.currentTimeMillis()
            ))
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
            val newTotal = newAgents.mapNotNull { it.cost }.sum() +
                newAgents.sumOf { it.iconInputCost + it.iconOutputCost }
            saveReport(report.copy(
                agents = newAgents, totalCost = newTotal,
                timestamp = System.currentTimeMillis()
            ))
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
        /** Bundled prompt that produced [icon] — "report_2" for
         *  tier 1, "report" for tier 2, "report_3" for tier 3.
         *  Surfaces on the Icon lookup screen. */
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
                iconWinningTier = winningTier,
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

    /** Append one [IconCallRecord] onto [Report.iconCalls]. Called by
     *  [com.ai.viewmodel.ReportViewModel.runReportIcons] after every
     *  tier API call so the export's per-call All-tab can show each
     *  attempt as its own row. */
    fun appendIconCall(context: Context, reportId: String, record: IconCallRecord): Boolean {
        init(context)
        return lock.withLock {
            val report = loadReport(reportId) ?: return@withLock false
            val newCalls = (report.iconCalls + record).toMutableList()
            saveReport(report.copy(
                iconCalls = newCalls,
                timestamp = System.currentTimeMillis()
            ))
            true
        }
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
            val newCalls = report.iconCalls.filterNot { it.agentId in pairIds }.toMutableList()
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
            val newTotal = newAgents.mapNotNull { it.cost }.sum() +
                newAgents.sumOf { it.iconInputCost + it.iconOutputCost }
            saveReport(report.copy(
                agents = newAgents, iconCalls = newCalls, totalCost = newTotal,
                timestamp = System.currentTimeMillis()
            ))
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
            val newTotal = newAgents.mapNotNull { it.cost }.sum()
            saveReport(report.copy(
                agents = newAgents, totalCost = newTotal,
                iconCalls = mutableListOf(),
                timestamp = System.currentTimeMillis()
            ))
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
            report.totalCost = report.agents.mapNotNull { it.cost }.sum() +
                report.agents.sumOf { it.iconInputCost + it.iconOutputCost }
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
            // Per-agent icon belongs to the previous response; clear
            // it too so a regenerate doesn't keep a stale emoji from
            // an answer that no longer exists.
            agent.icon = null
            agent.iconErrorMessage = null
            agent.iconInputTokens = 0
            agent.iconOutputTokens = 0
            agent.iconInputCost = 0.0
            agent.iconOutputCost = 0.0
            report.totalCost = report.agents.mapNotNull { it.cost }.sum() +
                report.agents.sumOf { it.iconInputCost + it.iconOutputCost }
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
                costsFromDeletedItems = 0.0
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
