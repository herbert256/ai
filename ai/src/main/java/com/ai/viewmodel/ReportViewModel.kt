package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.helpers.translationRunGroupingId
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

sealed class TemperatureSweepCandidate(open val temperature: Float) {
    data class Pending(override val temperature: Float) : TemperatureSweepCandidate(temperature)
    data class Running(override val temperature: Float) : TemperatureSweepCandidate(temperature)
    data class Success(
        override val temperature: Float,
        val response: String,
        val tokenUsage: TokenUsage?,
        val cost: Double?,
        val durationMs: Long,
        val traceFile: String?,
        val replayEvidence: SecondaryReplayEvidence? = null
    ) : TemperatureSweepCandidate(temperature)
    data class Error(
        override val temperature: Float,
        val message: String,
        val httpStatusCode: Int?,
        val durationMs: Long?,
        val traceFile: String?
    ) : TemperatureSweepCandidate(temperature)
}

data class TemperatureSweepState(
    val reportId: String,
    val agentId: String,
    val candidates: List<TemperatureSweepCandidate>,
    val isRunning: Boolean = false,
    val unavailableMessage: String? = null
) {
    companion object {
        fun key(reportId: String, agentId: String): String = "$reportId|$agentId"
    }
}

sealed class ReasoningEffortCandidate(open val effort: String?) {
    data class Pending(override val effort: String?) : ReasoningEffortCandidate(effort)
    data class Running(override val effort: String?) : ReasoningEffortCandidate(effort)
    data class Success(
        override val effort: String?,
        val response: String,
        val tokenUsage: TokenUsage?,
        val cost: Double?,
        val durationMs: Long,
        val traceFile: String?,
        val replayEvidence: SecondaryReplayEvidence? = null
    ) : ReasoningEffortCandidate(effort)
    data class Error(
        override val effort: String?,
        val message: String,
        val httpStatusCode: Int?,
        val durationMs: Long?,
        val traceFile: String?
    ) : ReasoningEffortCandidate(effort)
}

data class ReasoningEffortSweepState(
    val reportId: String,
    val agentId: String,
    val candidates: List<ReasoningEffortCandidate>,
    val isRunning: Boolean = false,
    val unavailableMessage: String? = null
) {
    companion object {
        fun key(reportId: String, agentId: String): String = "$reportId|$agentId"
    }
}

sealed class WebSearchReplayResult {
    data object Pending : WebSearchReplayResult()
    data object Running : WebSearchReplayResult()
    data class Success(
        val response: String,
        val tokenUsage: TokenUsage?,
        val cost: Double?,
        val durationMs: Long,
        val traceFile: String?,
        val replayEvidence: SecondaryReplayEvidence? = null
    ) : WebSearchReplayResult()
    data class Error(
        val message: String,
        val httpStatusCode: Int?,
        val durationMs: Long?,
        val traceFile: String?
    ) : WebSearchReplayResult()
}

data class WebSearchReplayState(
    val reportId: String,
    val agentId: String,
    val result: WebSearchReplayResult = WebSearchReplayResult.Pending,
    val isRunning: Boolean = false,
    val unavailableMessage: String? = null
) {
    companion object {
        fun key(reportId: String, agentId: String): String = "$reportId|$agentId"
    }
}

sealed class PromptEditReplayResult {
    data object Pending : PromptEditReplayResult()
    data object Running : PromptEditReplayResult()
    data class Success(
        val response: String,
        val tokenUsage: TokenUsage?,
        val cost: Double?,
        val durationMs: Long,
        val traceFile: String?,
        val replayEvidence: SecondaryReplayEvidence? = null
    ) : PromptEditReplayResult()
    data class Error(
        val message: String,
        val httpStatusCode: Int?,
        val durationMs: Long?,
        val traceFile: String?
    ) : PromptEditReplayResult()
}

data class PromptEditReplayState(
    val reportId: String,
    val agentId: String,
    val result: PromptEditReplayResult = PromptEditReplayResult.Pending,
    val isRunning: Boolean = false,
    val unavailableMessage: String? = null
) {
    companion object {
        fun key(reportId: String, agentId: String): String = "$reportId|$agentId"
    }
}

private const val WEB_SEARCH_REPLAY_PROMPT_SUFFIX =
    "Give the most actual information, do a websearch for this."

internal fun formatSweepTemperature(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

internal fun formatSweepReasoningEffort(effort: String?): String =
    effort?.replaceFirstChar { it.uppercase() } ?: "None"

private fun webSearchReplayPrompt(prompt: String): String =
    if (prompt.isBlank()) WEB_SEARCH_REPLAY_PROMPT_SUFFIX
    else prompt.trimEnd() + "\n\n" + WEB_SEARCH_REPLAY_PROMPT_SUFFIX

/** One re-runnable Report-info metadata item — the target of the per-item
 *  🔄 reload on the Get-info detail screens. [ReportViewModel.regenerateMetaItem]
 *  maps each to its generator (+ cache eviction where needed). */
enum class MetaRegenKind {
    REPORT_TITLE_SHORT, REPORT_TITLE_LONG, REPORT_ICON,
    LANGUAGE_NAME, LANGUAGE_ICON, MODEL_TITLE, MODEL_ICON,
    /** Fan-out pair title+icon (one worker call fills both); the id slot
     *  carries the PAIR row id. */
    PAIR_FAN_META
}

/** The live per-agent results map plus the report it belongs to. Agent ids
 *  are NOT unique across reports (direct-model rows are "swarm:provider:model"
 *  in every report), so a bare map can't tell report A's answer from report
 *  B's — a late completion from A would flip B's matching row. Writers go
 *  through [ReportViewModel.updateAgentResults], which checks [reportId] in
 *  the same atomic update as the write. */
data class ReportAgentResults(
    val reportId: String? = null,
    val results: Map<String, AnalysisResponse> = emptyMap()
)

/**
 * ViewModel for AI report generation: task building, concurrent execution, cost calculation.
 * Delegates to AppViewModel for shared state and settings.
 */
class ReportViewModel(private val appViewModel: AppViewModel) {

    private var configurationSaveJob: Job? = null
    /** Primary generation jobs by the report id they are producing — one per
     *  report, so starting a new report no longer cancels one still running
     *  in the background (a single shared job used to, marking its
     *  unfinished answers STOPPED). The Broken-work scan reads this to tell
     *  a live run's PENDING/RUNNING agents (not broken) apart from agents a
     *  process kill stranded (interrupted). See [isReportGenerating]. */
    private val generationJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** Generations launched but still before [ReportStorage.createReportAsync]
     *  (no report yet). A new Generate cancels these — a double tap — but
     *  never a run that already created its report. */
    private val uncommittedGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    /** The most recently launched generation: the only one allowed to drive
     *  the generation screen (claim currentReportId, restore the selection
     *  screen on a preparation failure). */
    @Volatile private var latestGenerationJob: Job? = null
    /** Reports whose generation the user left running (completion toast). */
    private val backgroundGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Variation-replay tracks: each owns its StateFlow<Map<String,S>> + per-key
    // job map + register/set/update/cancel/prefix-clear plumbing (see ReplayTrack).
    private val temperatureSweep = ReplayTrack<TemperatureSweepState>()
    val temperatureSweepStates: StateFlow<Map<String, TemperatureSweepState>> = temperatureSweep.states
    private val reasoningEffortSweep = ReplayTrack<ReasoningEffortSweepState>()
    val reasoningEffortSweepStates: StateFlow<Map<String, ReasoningEffortSweepState>> = reasoningEffortSweep.states
    private val webSearchReplay = ReplayTrack<WebSearchReplayState>()
    val webSearchReplayStates: StateFlow<Map<String, WebSearchReplayState>> = webSearchReplay.states
    private val promptEditReplay = ReplayTrack<PromptEditReplayState>()
    val promptEditReplayStates: StateFlow<Map<String, PromptEditReplayState>> = promptEditReplay.states

    /** In-flight regenerate jobs (single-agent regenerateAgent +
     *  forceRegenerateAllAgents), keyed by reportId, so deleteReport can
     *  cancel them — otherwise they run to completion against a deleted
     *  report and their terminal writes can recreate its storage dir. */
    private val regenerateJobs = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Job>>()
    private fun trackRegenerateJob(reportId: String, job: Job) {
        val set = regenerateJobs.computeIfAbsent(reportId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
        set.add(job)
        job.invokeOnCompletion { set.remove(job); if (set.isEmpty()) regenerateJobs.remove(reportId, set) }
    }

    /** Single-agent regenerate ("Call model API again") jobs, keyed by
     *  "$reportId|$agentId", so removeAgentInternal can cancel the specific
     *  in-flight call when its agent is removed — otherwise it runs to a
     *  (billed) completion whose write then no-ops onto the gone agent.
     *  Mirrors the sweep/replay/icon per-agent cancellation. */
    private val regenerateAgentJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun regenerateAgentKey(reportId: String, agentId: String) = "$reportId|$agentId"

    /** True while any primary work for [reportId] is live in THIS process —
     *  the initial generation ([generationJobs]), a single/all-agent
     *  regenerate ([regenerateJobs]), or a regenerate-batch run. After a
     *  process kill all of these are empty, so the report's still-PENDING/
     *  RUNNING agents correctly read as interrupted. Consumed by the
     *  Broken-work scan via [BrokenWorkPolicy.agentProblems]. */
    fun isReportGenerating(reportId: String): Boolean =
        generationJobs[reportId]?.isActive == true ||
            regenerateJobs[reportId]?.any { it.isActive } == true ||
            regenerateBatchEngine.isActivelyRunning(reportId)

    internal fun hasActiveReportCalls(context: Context, reportId: String): Boolean =
        appViewModel.runningInfoJobs.value.any { it.startsWith("$reportId|") } ||
        resumingMetaIds.any { SecondaryResultStorage.get(context,reportId,it) != null } ||
        generationJobs[reportId]?.isActive == true ||
        regenerateJobs[reportId]?.any { it.isActive } == true ||
        fanOutEngine.hasActiveCalls(reportId) || tournamentEngine.hasActiveCalls(reportId) ||
        judgeEvalEngine.hasActiveCalls(reportId) || compareEngine.hasActiveCalls(reportId) ||
        translatorRankEngine.hasActiveCalls(reportId) ||
        translation.translationRuns.value.values.any { it.sourceReportId == reportId && !it.isFinished && !it.cancelled }

    // The in-flight fan-meta batch job now lives in the FanOutEngine run-job
    // registry under a namespaced "|meta|" key (FanOutEngine.registerFanMetaJob)
    // — it's a decorator pass over that engine's pairs, so it belongs there.
    // Kept out of the fan-out pair operations (deleteFanOutModel etc.) the same
    // way as before: those cancel per-pair ITEM jobs, never the run job.

    /** Coroutine context for a report-section launch: `Dispatchers.IO`
     *  plus the [com.ai.data.CrashReporter] handler. Drop-in for
     *  `Dispatchers.IO` at report-section `viewModelScope.launch`
     *  sites; `return@launch` stays valid because the `launch` call
     *  itself is unchanged. */
    internal fun reportLogContext() =
        Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler

    // Outer Jobs for "Find alternative icons" fan-outs, keyed by
    // reportId. Cancelling the entry cascades to every per-pair child
    // launch inside startIconFanOut so a deleteReport can stop the
    // whole search in one call instead of leaving N orphan HTTP-calls
    // running on viewModelScope.
    internal val iconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** Mirror of [iconFanOutJobs] for the language-icon alt-picker. */
    internal val languageIconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    internal fun registerIconFanOutJob(reportId: String, job: Job) {
        // Cancel any prior in-flight run for the same report — a user
        // who hits Find Icons twice in a row should get the latest
        // selection, not two overlapping searches.
        iconFanOutJobs.put(reportId, job)?.cancel()
        job.invokeOnCompletion { iconFanOutJobs.remove(reportId, job) }
    }


    // Per-agent alternative-icons fan-out jobs (Agent icon detail →
    // Find alternative icons). Keyed by "$reportId|$agentId" so
    // deleteReport's prefix cancel sweeps them too.
    internal val agentIconFanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    internal fun agentIconJobKey(reportId: String, agentId: String) = "$reportId|$agentId"
    internal fun registerAgentIconFanOutJob(reportId: String, agentId: String, job: Job) {
        val key = agentIconJobKey(reportId, agentId)
        agentIconFanOutJobs.put(key, job)?.cancel()
        job.invokeOnCompletion { agentIconFanOutJobs.remove(key, job) }
    }

    // Tracks single-call Meta/Rerank/Moderation placeholders the
    // report-open auto-resume sweep is currently re-issuing, so a
    // rapid back-then-forward navigation can't double-fire the same
    // row. Keyed by SecondaryResult.id; entries removed in the
    // finally of [resumeStaleMetaPlaceholder].
    internal val resumingMetaIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Bumped by every [restoreCompletedReport] call — see its guard. */
    private val restoreSeq = java.util.concurrent.atomic.AtomicLong()

    // Separate flow from UiState so per-task completions don't force the UiState equality
    // checker to re-compare every other field. UI subscribers observe this independently.
    // Stamped with its owning report — see ReportAgentResults.
    private val _agentResults = MutableStateFlow(ReportAgentResults())
    val agentResults: StateFlow<ReportAgentResults> = _agentResults.asStateFlow()

    /** Replace the results map wholesale and hand it to [reportId] (null =
     *  no report on screen). Called wherever currentReportId changes, just
     *  before the flip. */
    private fun resetAgentResults(reportId: String?, results: Map<String, AnalysisResponse> = emptyMap()) {
        _agentResults.value = ReportAgentResults(reportId, results)
    }

    /** Apply [transform] to [reportId]'s results — a no-op once the map
     *  belongs to another report. Check and write are one atomic update, so
     *  a completion racing a report switch can't land in the new report. */
    internal fun updateAgentResults(
        reportId: String,
        transform: (Map<String, AnalysisResponse>) -> Map<String, AnalysisResponse>
    ) {
        _agentResults.update { cur -> if (cur.reportId == reportId) cur.copy(results = transform(cur.results)) else cur }
    }

    /** Terminal (SUCCESS / ERROR / STOPPED) agents of [report] as results.
     *  PENDING / RUNNING agents are left out so their rows keep the
     *  hourglass instead of showing a spurious ❌ (analysis=null, error=null
     *  → isSuccess=false). */
    private fun terminalAgentResults(report: Report): Map<String, AnalysisResponse> =
        report.agents.filter { it.isTerminal() }.mapNotNull { ra ->
            val service = AppService.findById(ra.provider) ?: return@mapNotNull null
            ra.agentId to AnalysisResponse(
                service = service, analysis = ra.responseBody, error = ra.errorMessage,
                agentName = ra.agentName, tokenUsage = ra.tokenUsage,
                citations = ra.citations, searchResults = ra.searchResults,
                relatedQuestions = ra.relatedQuestions, rawUsageJson = ra.rawUsageJson,
                httpHeaders = ra.responseHeaders, httpStatusCode = ra.httpStatus
            )
        }.toMap()

    private fun ReportAgent.isTerminal() = reportStatus == ReportStatus.SUCCESS ||
        reportStatus == ReportStatus.ERROR || reportStatus == ReportStatus.STOPPED

    /** Bump the live progress counter, but only while [reportId] is the
     *  report on screen — checked inside the update so it can't race a
     *  report switch. */
    private fun bumpGenerationProgress(reportId: String) {
        appViewModel.updateUiState { s ->
            if (s.currentReportId != reportId) s
            else s.copy(genericReportsProgress = s.genericReportsProgress + 1)
        }
    }

    /** Authoritative Fan Out runtime state. The UI subscribes to this
     *  engine directly; the old per-pair maps and polling loop this
     *  ViewModel used to own for fan-out have been removed. */
    val fanOutEngine: FanOutEngine = FanOutEngine(appViewModel, this)

    /** Runtime owner for pairwise Tournament runs (head-to-head judging
     *  aggregated to a rerank-compatible ranking). Sibling of
     *  [fanOutEngine]; see [TournamentEngine]. */
    val tournamentEngine: TournamentEngine = TournamentEngine(appViewModel, this)

    /** Runtime owner for the "Judge the judges" batch — gives every judge
     *  (the worker models named by the Tournament prompt) the same random
     *  matches and scores their inter-judge agreement. See [JudgeEvalEngine]. */
    val judgeEvalEngine: JudgeEvalEngine = JudgeEvalEngine(appViewModel, this)

    /** Runtime owner for the "Compare with meta" batch — scores how closely
     *  each report answer matches each chosen meta result, on a grid judged by
     *  the worker engine. Sibling of [tournamentEngine]; see [CompareEngine]. */
    val compareEngine: CompareEngine = CompareEngine(appViewModel, this)

    /** Runtime owner for the "Rank the translators" batch (🏅 on a Translations
     *  row) — scores each translated item with the other models and ranks the
     *  translator models by average score. See [TranslatorRankEngine]. */
    val translatorRankEngine: TranslatorRankEngine = TranslatorRankEngine(appViewModel, this)

    /** The "Change response"-style edit flows (regenerate / prompt-edit / chat /
     *  temperature / reasoning / web-search) for a plain META secondary result,
     *  surfaced by the dedicated Meta detail screen. See [MetaEditManager]. */
    val metaEditManager: MetaEditManager = MetaEditManager(appViewModel, this)

    /** "Switch model / agent" preview+apply for any secondary result
     *  (Meta / Fan-in / Rerank / Moderation). See [SecondaryModelSwitchManager]. */
    val secondaryModelSwitch: SecondaryModelSwitchManager = SecondaryModelSwitchManager(appViewModel, this)

    /** "Switch model / agent" for a PRIMARY answer — preview + append-new /
     *  remove-old apply. See [AgentModelSwitchManager]. */
    val agentModelSwitch: AgentModelSwitchManager = AgentModelSwitchManager(appViewModel, this)

    /** Per-report orchestrator for the "Regenerate report" batch
     *  job. Replaces the legacy one-shot regenerateReport call —
     *  the title-bar 🔁 icon's confirm dialog now calls
     *  `regenerateBatchEngine.enqueueAndStart` instead. */
    val regenerateBatchEngine: RegenerateBatchEngine = RegenerateBatchEngine(appViewModel, this)

    /** Runtime owner for the "Test all models" run (Housekeeping →
     *  Test). One run, persisted to its own JSON document. */
    val modelTestEngine: ModelTestEngine = ModelTestEngine(appViewModel)
    /** Housekeeping → Test → Stress test: wipe runtime data, then report
     *  every Example Prompt with swarm "Level 2", sequentially. */
    val stressTestEngine: StressTestEngine = StressTestEngine(appViewModel, this)
    val translation = TranslationRunManager(appViewModel, this)
    val iconGen = IconGenerationManager(appViewModel, this)
    val secondary = SecondaryRunManager(appViewModel, this)
    /** The Broken-work scan's result, surfaced for the Reports-hub cards so
     *  the "problems" card derives from the exact list that lights the ⚠️
     *  badge — one routine, two surfaces. */
    val brokenBatches get() = appViewModel.brokenBatches
    /** Round-robin + 429-fallback runner for "workers"-category prompts.
     *  Reusable engine; no batch is converted onto it yet. */
    val workerRunner = WorkerRunner(appViewModel)

    internal data class ReportTask(
        val resultId: String,
        val reportAgent: ReportAgent,
        val runtimeAgent: Agent,
        val resolvedParams: AgentParameters,
        val defaultPrompt: String? = null
    )

    fun showGenericAgentSelection(
        title: String, prompt: String,
        imageBase64: String? = null, imageMime: String? = null,
        webSearchTool: Boolean = false,
        reasoningEffort: String? = null,
        metadataDisabled: Boolean = false
    ) {
        resetAgentResults(null)
        appViewModel.updateUiState { it.copy(
            genericPromptTitle = title, genericPromptTitleLong = "", genericPromptText = prompt,
            reportImageBase64 = imageBase64, reportImageMime = imageMime,
            reportWebSearchTool = webSearchTool,
            reportReasoningEffort = reasoningEffort,
            reportMetadataDisabled = metadataDisabled,
            // A new report starts from clean pre-generation settings.
            // restoreCompletedReport loads the OPENED report's presets /
            // advanced overlay / system prompt into these same fields, and
            // several ways into a new report (➕, hub, external request)
            // skip the dismiss that clears them — the next report silently
            // ran with the last opened report's config. Chosen on the
            // setup screen after this call, so nothing is lost.
            reportParametersIds = emptyList(),
            reportAdvancedParameters = null,
            reportSystemPromptId = null,
            editModeReportId = null,
            showGenericAgentSelection = true, showGenericReportsDialog = false,
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null
        ) }
    }

    fun dismissGenericAgentSelection() {
        appViewModel.updateUiState { it.copy(showGenericAgentSelection = false) }
    }

    fun generateGenericReports(
        context: Context,
        selectedAgentIds: Set<String>,
        /** Explicit "swarm:provider:model" ids of the swarm-sourced models the
         *  user KEPT — NOT swarm ids (those would re-expand to the full swarm,
         *  re-adding members the user removed on the select-models screen).
         *  Kept distinct from [directModelIds] so swarm members still skip the
         *  per-report-model param fallback (see directModelSids). */
        swarmModelIds: Set<String> = emptySet(),
        directModelIds: Set<String> = emptySet(),
        parametersIds: List<String> = emptyList(),
        selectionParamsById: Map<String, List<String>> = emptyMap(),
        reportType: ReportType = ReportType.CLASSIC,
        /** Worker routing picked on "Report - select workers"; stamped
         *  onto the new Report and consulted by every worker flow. */
        workerConfig: ReportWorkerConfig = ReportWorkerConfig(),
        selectedModels: List<ReportModel> = emptyList()
    ) {
        // A double tap: cancel earlier launches that haven't created their
        // report yet. Runs that did are left alone — they may be continuing
        // in the background while the user starts this one.
        uncommittedGenerations.forEach { it.cancel() }
        // Outer launch on viewModelScope so navigating away from the
        // result screen doesn't cancel the in-flight OkHttp calls.
        // A screen-scoped scope here previously turned every
        // still-running agent into ERROR on disk: the cancellation
        // surfaced as IOException("Canceled"), executeReportTask's
        // catch (Exception) converted it to a real error response,
        // and the NonCancellable terminal write persisted that error.
        // continueReportInBackground() only sets a flag — without
        // viewModelScope here, "background" can't actually happen.
        val generationJob = appViewModel.viewModelScope.launch(
            Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler, start = CoroutineStart.LAZY
        ) {
            val thisJob = kotlin.coroutines.coroutineContext[Job]!!
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val effectiveParametersIds = (parametersIds + state.reportParametersIds).distinct()
            val prompt = state.genericPromptText
            val title = state.genericPromptTitle
            val externalSystemPrompt = state.externalSystemPrompt
            val imageBase64 = state.reportImageBase64
            val imageMime = state.reportImageMime
            // Drop the per-report image / per-report flags from UiState
            // as soon as we've captured them into local vals. Otherwise
            // a megabyte-sized base64 photo stays resident on UiState
            // until the report finishes (or forever if the user navigates
            // away and never comes back). The locals here keep the
            // bytes alive for the agents that need them.
            // Attached knowledge bases too: they were never cleared, so every
            // later report silently retrieved from this report's files.
            // And the external request's generation inputs — system prompt,
            // context values, open / close HTML, model list — belong to THIS
            // report only. They used to stay in UiState, so the next report
            // started in the app sent the earlier request's system prompt to
            // every model without one of its own (an Eval request carries the
            // position's FEN there: that model analysed the OTHER position).
            // Only the post-completion actions stay, for this report.
            val externalIntentAtLaunch = state.externalIntent
            val externalPostCompletion = ExternalIntent(
                email = externalIntentAtLaunch.email,
                nextAction = externalIntentAtLaunch.nextAction,
                returnAfterNext = externalIntentAtLaunch.returnAfterNext,
                reportId = externalIntentAtLaunch.reportId
            )
            appViewModel.updateUiState { it.copy(
                reportImageBase64 = null, reportImageMime = null,
                reportWebSearchTool = false, reportReasoningEffort = null,
                reportMetadataDisabled = false,
                attachedKnowledgeBaseIds = emptyList(),
                externalIntent = if (it.externalIntent == externalIntentAtLaunch) externalPostCompletion else it.externalIntent
            ) }
            // Layer the per-report advanced overlay on top of any preset
            // merge — "later non-null wins" (matches Settings.mergeParameters
            // semantics for the preset chain). Either layer alone produces
            // the right result; together the user's explicit per-report
            // tweaks win over preset defaults instead of being shadowed by
            // them. Bool fields OR upward.
            val overrideParams = resolveReportOverrideParams(
                aiSettings, effectiveParametersIds, state.reportAdvancedParameters,
                state.reportWebSearchTool, state.reportReasoningEffort, state.reportSystemPromptId
            )?.let { params ->
                params.systemPrompt?.let { params.copy(systemPrompt = state.externalIntent.context.expandPrompt(it)) } ?: params
            }

            val agents = selectedAgentIds.mapNotNull { aiSettings.getAgentById(it) }
            // Resolve the kept swarm members from their explicit ids — do NOT
            // re-expand swarm ids (that's the "ran all 12 after removing 3" bug).
            val swarmMembers = swarmModelIds.mapNotNull { mid ->
                val parts = mid.removePrefix("swarm:").split(":", limit = 2)
                val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
                SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
            }
            val uniqueDirectModelIds = directModelIds.toSet()
            // A pair picked BOTH as a swarm member and a direct +Model is
            // classified as DIRECT (the more specific pick), so it gets the
            // provider / report-model / app-wide param + system-prompt
            // fallbacks a bare +Model expects — drop it from the swarm side to
            // avoid the dedup collapsing it to a swarm member (which skips
            // those fallbacks, insertion-order-dependently).
            val uniqueSwarmMembers = swarmMembers.filter {
                "swarm:${it.provider.id}:${it.model}" !in uniqueDirectModelIds
            }
            val uniqueSwarmMemberIds = uniqueSwarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()

            val directModels = uniqueDirectModelIds.mapNotNull { modelId ->
                val parts = modelId.removePrefix("swarm:").split(":", limit = 2)
                val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
                SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
            }

            val allModelMembers = uniqueSwarmMembers + directModels
            val allModelIds = uniqueSwarmMemberIds + uniqueDirectModelIds

            val directModelSids = directModels.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val preGenParamsActive = state.reportAdvancedParameters != null ||
                state.reportWebSearchTool || state.reportReasoningEffort != null
            val reportTasks = buildReportTasks(
                aiSettings, agents, allModelMembers, selectionParamsById, externalSystemPrompt,
                state.generalSettings, directModelSids, preGenParamsActive, selectedModels
            ).map { task ->
                task.resolvedParams.systemPrompt?.let { system ->
                    task.copy(resolvedParams = task.resolvedParams.copy(systemPrompt = state.externalIntent.context.expandPrompt(system)))
                } ?: task
            }

            resetAgentResults(null)
            appViewModel.updateUiState { it.copy(
                showGenericAgentSelection = false, showGenericReportsDialog = true,
                genericReportsProgress = 0, genericReportsTotal = reportTasks.size,
                // Drive the result-row list off the ACTUAL dispatched
                // tasks, not the raw picker selection — buildReportTasks
                // deduped cross-source provider:model collisions, so
                // `selectedAgentIds + allModelIds` would leave the
                // deduped-away ids stranded as permanently-PENDING rows.
                genericReportsSelectedAgents = reportTasks.map { it.resultId }.toSet(),
                currentReportId = null
            ) }

            val userMatch = AppViewModel.USER_TAG_REGEX.find(prompt)
            val rapportText = userMatch?.groupValues?.get(1)?.trim() ?: state.externalOpenHtml
            val aiPrompt = if (userMatch != null) prompt.replace(userMatch.value, "").trim() else prompt

            val runId = java.util.UUID.randomUUID().toString()
            val plannedReportId = java.util.UUID.randomUUID().toString()
            try {
                com.ai.data.ReportWorkLimits.checkSize(reportTasks.size)
                preparePrimaryExecution(context, aiPrompt, reportTasks, overrideParams,
                    state.attachedKnowledgeBaseIds, aiSettings, appViewModel.repository, state.externalIntent.context)
            } catch (e: Exception) {
                if (latestGenerationJob == thisJob && ownsGenerationScreen()) {
                    // Back to the selection screen with everything this run
                    // consumed restored, so a retry runs the same request.
                    appViewModel.updateUiState { it.copy(showGenericReportsDialog=false,showGenericAgentSelection=true,
                        reportImageBase64=imageBase64,reportImageMime=imageMime,reportWebSearchTool=state.reportWebSearchTool,
                        reportReasoningEffort=state.reportReasoningEffort,reportMetadataDisabled=state.reportMetadataDisabled,
                        attachedKnowledgeBaseIds=state.attachedKnowledgeBaseIds,
                        externalIntent=if (it.externalIntent == externalPostCompletion) externalIntentAtLaunch else it.externalIntent) }
                    if (e !is kotlinx.coroutines.CancellationException) withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context,e.message ?: "Could not prepare report",android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@launch
            }
            val report = ReportStorage.createReportAsync(
                context = context, title = title.ifBlank { "AI Report" },
                prompt = reportPromptOverview(aiPrompt, reportTasks), agents = reportTasks.map { it.reportAgent },
                config = CreateReportConfig(
                    explicitId = plannedReportId,
                    rapportText = rapportText, reportType = reportType, closeText = state.externalCloseHtml,
                    imageBase64 = imageBase64, imageMime = imageMime,
                    webSearchTool = state.reportWebSearchTool,
                    reasoningEffort = state.reportReasoningEffort,
                    metadataDisabled = state.reportMetadataDisabled,
                    workerConfig = workerConfig,
                    knowledgeBaseIds = state.attachedKnowledgeBaseIds,
                    runId = runId,
                    // Capture the generation config so Regenerate replays these
                    // exact selections instead of the live UiState/Settings.
                    parameterPresetIds = effectiveParametersIds,
                    advancedParameters = state.reportAdvancedParameters,
                    selectionParamsById = selectionParamsById,
                    reportSystemPromptId = state.reportSystemPromptId,
                    externalSystemPrompt = externalSystemPrompt?.let { state.externalIntent.context.expandPrompt(it) }
                )
            )
            val reportId = report.id
            // Register as this report's generation (the Broken-work scan then
            // doesn't flag its in-flight PENDING/RUNNING agents as interrupted)
            // and leave the double-tap set: a newer Generate must not cancel a
            // run that has created its report. Removed in the finally below.
            generationJobs[reportId] = thisJob
            uncommittedGenerations.remove(thisJob)
            // An external request's post-completion actions belong to this
            // report — stamp it, if the request is still the one this run
            // started with and hasn't been claimed by another report.
            val ext = externalPostCompletion
            if (ext.reportId == null && (ext.email != null || ext.nextAction != null || ext.returnAfterNext)) {
                appViewModel.updateUiState { s ->
                    if (s.externalIntent == ext) s.copy(externalIntent = ext.copy(reportId = reportId)) else s
                }
            }
            val reportStartMs = System.currentTimeMillis()
            AppLog.i("Report", "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))")

            withTracerTags(reportId = reportId, category = "report/prompt", runId = runId) {
                // Take over the screen only while the user is still on this
                // run's generation screen. If they left while it prepared (or
                // a newer Generate / an opened report took over), the report
                // runs in the background instead of hijacking whatever report
                // is now on screen.
                if (claimGenerationScreen(thisJob, reportId)) resetAgentResults(reportId)
                else backgroundGenerations += reportId

                iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, aiSettings)
                // Generate titles, then the icon, each from the original question.
                iconGen.kickOffReportTitleGeneration(context, reportId, report.prompt, aiSettings, thenIcon = true)

                try {
                    runReportPrimaryCalls(
                        context, reportId, aiPrompt, overrideParams, reportTasks,
                        aiSettings, imageBase64, imageMime, headless = false
                    )
                    val finalReport = ReportStorage.getReport(context, reportId)
                    val ok = finalReport?.agents?.count { it.reportStatus == ReportStatus.SUCCESS } ?: 0
                    val fail = finalReport?.agents?.count { it.reportStatus == ReportStatus.ERROR } ?: 0
                    AppLog.i("Report", "← end \"${title.ifBlank { "AI Report" }}\" ok=$ok fail=$fail in ${System.currentTimeMillis() - reportStartMs}ms")
                    maybeAutoCreateSecondaries(context, reportId, aiSettings, ok)
                    maybeAutoCreateDefaultMetas(context, reportId, aiSettings, ok)
                    if (backgroundGenerations.remove(reportId)) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Report \"$title\" is ready", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                } finally {
                    // Drop the background mark on cancel paths too (a Stop
                    // mid-run must not leave a toast pending) and deregister.
                    backgroundGenerations.remove(reportId)
                    generationJobs.remove(reportId, thisJob)
                    // If the run was cancelled (Stop, or a newer report start
                    // cancelling this shared job), terminalize any rows still
                    // PENDING/RUNNING as STOPPED — otherwise the report reads as
                    // "generating" forever (its agents never reach a terminal
                    // status, completedAt stays null, the hub keeps it under
                    // "running"). NonCancellable so the write survives the
                    // cancellation; a no-op on normal completion.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        ReportStorage.stopNonTerminalAgentsAsync(context, reportId)
                    }
                }
            }
        }
        uncommittedGenerations += generationJob
        generationJob.invokeOnCompletion { uncommittedGenerations.remove(generationJob) }
        latestGenerationJob = generationJob
        generationJob.start()
    }

    /** The user is still on the generation screen of a new report: it's
     *  showing, and no report has been opened in it yet. */
    private fun ownsGenerationScreen(): Boolean {
        val s = appViewModel.uiState.value
        return s.showGenericReportsDialog && s.currentReportId == null
    }

    /** Atomically hand the generation screen to [reportId] — only if [job]
     *  is still the latest generation and the screen is still waiting for it
     *  (see [ownsGenerationScreen]). Checked inside the update so it can't
     *  race the user opening another report. */
    private fun claimGenerationScreen(job: Job, reportId: String): Boolean {
        if (latestGenerationJob != job) return false
        var claimed = false
        appViewModel.updateUiState { s ->
            if (s.showGenericReportsDialog && s.currentReportId == null) {
                claimed = true
                s.copy(currentReportId = reportId)
            } else { claimed = false; s }
        }
        // resetAgentResults runs after the claim: nothing publishes for this
        // report until it owns both (writers check the results owner).
        return claimed
    }



    /** Resolve the per-report override [AgentParameters] from the captured
     *  generation config: the preset chain merged ([Settings.mergeParameters]),
     *  the advanced overlay on top (later non-null wins; bool fields OR;
     *  returnCitations ANDs so an opt-out anywhere is honoured), then the
     *  per-report 🌐 web / 🧠 reasoning toggles. Shared by the fresh run and
     *  Regenerate so both apply the same selections. */
    internal fun resolveReportOverrideParams(
        aiSettings: Settings,
        parameterPresetIds: List<String>,
        advanced: AgentParameters?,
        webSearchTool: Boolean,
        reasoningEffort: String?,
        reportSystemPromptId: String? = null
    ): AgentParameters? {
        val mergedParams = aiSettings.mergeParameters(parameterPresetIds)
        val baseOverride = when {
            mergedParams == null && advanced == null -> null
            mergedParams == null -> advanced
            advanced == null -> mergedParams
            else -> AgentParameters(
                temperature = advanced.temperature ?: mergedParams.temperature,
                maxTokens = advanced.maxTokens ?: mergedParams.maxTokens,
                topP = advanced.topP ?: mergedParams.topP,
                topK = advanced.topK ?: mergedParams.topK,
                frequencyPenalty = advanced.frequencyPenalty ?: mergedParams.frequencyPenalty,
                presencePenalty = advanced.presencePenalty ?: mergedParams.presencePenalty,
                systemPrompt = advanced.systemPrompt ?: mergedParams.systemPrompt,
                stopSequences = advanced.stopSequences ?: mergedParams.stopSequences,
                seed = advanced.seed ?: mergedParams.seed,
                responseFormatJson = advanced.responseFormatJson || mergedParams.responseFormatJson,
                searchEnabled = advanced.searchEnabled || mergedParams.searchEnabled,
                returnCitations = advanced.returnCitations && mergedParams.returnCitations,
                searchRecency = advanced.searchRecency ?: mergedParams.searchRecency,
                webSearchTool = advanced.webSearchTool || mergedParams.webSearchTool,
                reasoningEffort = advanced.reasoningEffort ?: mergedParams.reasoningEffort
            )
        }
        val withWeb = if (webSearchTool) (baseOverride ?: AgentParameters()).copy(webSearchTool = true) else baseOverride
        val withReasoning = if (reasoningEffort != null) (withWeb ?: AgentParameters()).copy(reasoningEffort = reasoningEffort) else withWeb
        // The explicit report system prompt also wins over text embedded in a preset.
        val system = reportSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt }
        return if (system != null) (withReasoning ?: AgentParameters()).copy(systemPrompt = system) else withReasoning
    }

    private fun buildReportTasks(
        aiSettings: Settings, agents: List<Agent>, modelMembers: List<SwarmMember>,
        selectionParamsById: Map<String, List<String>>, externalSystemPrompt: String?,
        /** App-wide / report-model default presets from GeneralSettings.
         *  App-wide is the universal lowest fallback; report-model applies
         *  to bare/direct models only and is skipped when a pre-gen
         *  override is active. */
        general: GeneralSettings = GeneralSettings(),
        /** sids of true bare/direct models (not swarm members) — only these
         *  receive the provider + report-model fallbacks. */
        directModelSids: Set<String> = emptySet(),
        /** When a pre-generation params override (🌡️ / web / reasoning) is
         *  active, the report-model + app-wide PARAM fallbacks are skipped. */
        preGenParamsActive: Boolean = false,
        selectedModels: List<ReportModel> = emptyList()
    ): List<ReportTask> {
        val selections = selectedModels.associateBy { it.agentId ?: "swarm:${it.provider.id}:${it.model}" }
        fun groupPrompt(id: String): String? = selections[id]?.let { selection ->
            val spId = when(selection.sourceType) {
                "flock" -> selection.sourceId?.let { aiSettings.getFlockById(it)?.systemPromptId }
                "swarm" -> selection.sourceId?.let { aiSettings.getSwarmById(it)?.systemPromptId }
                else -> null
            }
            spId?.let { aiSettings.getSystemPromptById(it)?.prompt }
        }
        fun defaultPrompt(id: String, agentId: String? = null): String? {
            val selected = selections[id]
            return aiSettings.resolveDefaultPrompt(agentId, selected?.sourceType, selected?.sourceId)?.prompt
        }
        val appSp = general.appWideSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt }
        val rmSp = general.reportModelSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt }

        val agentTasks = agents.map { agent ->
            val ea = agent.copy(
                apiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
                model = aiSettings.getEffectiveModelForAgent(agent)
            )
            // Per-FIELD merge down the chain, not first-non-null-LEVEL: report
            // generation "merges down this chain" (doc/parameters.md), so a
            // selection preset that sets only temperature must not wipe the
            // agent/app-wide maxTokens. Concatenate the id lists low→high
            // (app-wide, agent, selection) and fold once — mergeParameters
            // already does per-field "later wins", so selection wins per field,
            // falling back to agent then app-wide per field.
            var params = aiSettings.mergeParameters(
                general.appWideParametersIds + (selections[agent.id]?.paramsIds ?: agent.paramsIds) + (selectionParamsById[agent.id] ?: emptyList())
            ) ?: AgentParameters()
            val spText = groupPrompt(agent.id)
                ?: agent.systemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt }
                ?: externalSystemPrompt
                ?: appSp
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(agent.id, ReportAgent(agent.id, agent.name, ea.provider.id, ea.model, ReportStatus.PENDING), ea, params, defaultPrompt(agent.id, agent.id))
        }

        val modelTasks = modelMembers.map { member ->
            val sid = "swarm:${member.provider.id}:${member.model}"
            val isDirect = sid in directModelSids
            // Bare/direct models get the provider + report-model fallbacks;
            // swarm members get only their swarm level. App-wide is the
            // universal floor for both.
            val providerConfig = aiSettings.getProvider(member.provider)
            val spText = groupPrompt(sid)
                ?: (if (isDirect) providerConfig.systemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } else null)
                ?: (if (isDirect) rmSp else null)
                ?: externalSystemPrompt
                ?: appSp
            // Per-FIELD merge down the chain (see the agent block). Precedence
            // high→low: selection > provider(direct) > report-model(direct,
            // pre-gen-gated) > app-wide(pre-gen-gated). Concatenate low→high
            // and fold once so a per-model 🌡️ pick that sets only one field
            // doesn't wipe the provider/report-model/app-wide others.
            val paramIds = buildList {
                if (!preGenParamsActive) addAll(general.appWideParametersIds)
                if (isDirect && !preGenParamsActive) addAll(general.reportModelParametersIds)
                if (isDirect) addAll(providerConfig.parametersIds)
                addAll(selections[sid]?.paramsIds.orEmpty())
                addAll(selectionParamsById[sid] ?: emptyList())
            }
            var params = aiSettings.mergeParameters(paramIds) ?: AgentParameters()
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(sid,
                ReportAgent(sid, "${member.provider.id} / ${shortModelName(member.model)}", member.provider.id, member.model, ReportStatus.PENDING),
                Agent(sid, "${member.provider.id} / ${shortModelName(member.model)}", member.provider, member.model, aiSettings.getApiKey(member.provider)),
                params, defaultPrompt(sid)
            )
        }
        // Preserve each named Agent's identity, even when Agents share a model.
        // Bare model tasks deduplicate by their provider/model result id.
        val seen = mutableSetOf<String>()
        return (agentTasks + modelTasks).filter { task ->
            seen.add(task.resultId)
        }
    }

    /** Whether a report was generated with a pre-generation params override
     *  (🌡️ advanced values / web-search / reasoning toggle). Matches the
     *  fresh-run predicate in generateGenericReports EXACTLY — parameter
     *  PRESETS alone do NOT count (doc/parameters.md: "any advanced value,
     *  web-search, or reasoning toggle"). Every replay path (sweeps,
     *  regenerate, staged edit-models) reads this so it suppresses the same
     *  report-model / app-wide fallbacks the fresh run did — the replay
     *  predicates used to add `parameterPresetIds.isNotEmpty()`, silently
     *  changing preset-only reports' params between the original run and
     *  every regenerate. */
    private fun reportPreGenParamsActive(report: Report): Boolean =
        report.advancedParameters != null || report.webSearchTool || report.reasoningEffort != null

    internal fun buildTemperatureSweepTask(report: Report, state: UiState, reportAgent: ReportAgent): ReportTask? {
        val ai = state.aiSettings
        val provider = AppService.findById(reportAgent.provider) ?: return null
        val preGenParamsActive = reportPreGenParamsActive(report)
        val currentAgent = reportAgent.agentId
            .takeUnless { it.startsWith("swarm:") }
            ?.let { ai.getAgentById(it) }
        val sid = "swarm:${provider.id}:${reportAgent.model}"
        val task = if (currentAgent != null) {
            buildReportTasks(
                ai, listOf(currentAgent.copy(provider = provider, model = reportAgent.model)), emptyList(), report.selectionParamsById,
                // The report's OWN external system prompt, never the live
                // one (the last external request's) — see Report.externalSystemPrompt.
                report.externalSystemPrompt,
                state.generalSettings, emptySet(), preGenParamsActive
            ).firstOrNull()
        } else {
            buildReportTasks(
                ai, emptyList(), listOf(SwarmMember(provider, reportAgent.model)),
                report.selectionParamsById, report.externalSystemPrompt,
                state.generalSettings, setOf(sid), preGenParamsActive
            ).firstOrNull()
        } ?: ReportTask(reportAgent.agentId, reportAgent,
            Agent(id=reportAgent.agentId,name=reportAgent.agentName,provider=provider,model=reportAgent.model,apiKey=ai.getApiKey(provider)),
            reportAgent.executionConfig?.parameters ?: AgentParameters())
        val endpointId = currentAgent?.endpointId?.takeIf { currentAgent.provider.id == provider.id }
        val apiKey = currentAgent
            ?.takeIf { it.provider.id == provider.id }
            ?.let { ai.getEffectiveApiKeyForAgent(it.copy(provider = provider, model = reportAgent.model)) }
            ?: ai.getApiKey(provider)
        val runtimeAgent = Agent(
            id = reportAgent.agentId,
            name = currentAgent?.name ?: reportAgent.agentName,
            provider = provider,
            model = reportAgent.model,
            apiKey = apiKey,
            endpointId = endpointId,
            paramsIds = currentAgent?.paramsIds ?: emptyList(),
            systemPromptId = currentAgent?.systemPromptId
        )
        return task.copy(
            resultId = reportAgent.agentId,
            reportAgent = reportAgent.copy(reportStatus = ReportStatus.PENDING),
            runtimeAgent = runtimeAgent,
            resolvedParams = reportAgent.pendingExecutionConfig?.parameters ?: reportAgent.executionConfig?.parameters ?: task.resolvedParams
        )
    }

    /** True when a Google model is benched in [ModelCooldownStore]
     *  because the provider answered a >1h 429. The dispatch
     *  runners delete the in-flight item instead of erroring it. */
    internal fun isBenched(provider: AppService, model: String): Boolean =
        provider.apiFormat == com.ai.data.ApiFormat.GOOGLE &&
            com.ai.data.ModelCooldownStore.isUnavailable(provider.id, model)

    /** First (provider, model) pair across active providers whose resolved
     *  model type matches [type] (e.g. [ModelType.RERANK] / [ModelType.MODERATION]).
     *  "First found" = first active provider in [AppService.entries] order,
     *  first matching model in that provider's list. Null when none. */
    private fun firstModelOfType(s: Settings, type: String): Pair<AppService, String>? {
        for (svc in s.getActiveServices()) {
            s.getModels(svc).firstOrNull { s.getModelType(svc, it) == type }?.let { return svc to it }
        }
        return null
    }

    /** On a normal report completion, auto-create one Rerank and one
     *  Moderation when the "Auto create Rerank and Moderation" setting is on
     *  (default). Each uses the first capable model found; a kind is skipped
     *  when no capable model exists or that report already has one of that
     *  kind. Calls the same [SecondaryRunManager] entry points the manual UI
     *  uses — which launch their own jobs on viewModelScope, so this is
     *  non-blocking. Only invoked from the fresh-generation path. */
    private fun maybeAutoCreateSecondaries(
        context: Context, reportId: String, aiSettings: Settings, successCount: Int
    ) {
        if (ReportStorage.getReport(context, reportId)?.workerConfig?.primaryAnswersOnly == true) return
        if (!appViewModel.uiState.value.generalSettings.autostartItemsEnabled) return
        if (!appViewModel.uiState.value.generalSettings.autoCreateRerankAndModeration) return
        if (successCount < 1) return  // nothing to rank / moderate
        val hasKind = { k: SecondaryKind ->
            SecondaryResultStorage.listForReport(context, reportId, k).isNotEmpty()
        }
        // Rerank now runs through its worker swarm (chat-JSON, with a
        // native rerank member auto-routed), so it no longer needs a
        // rerank-capable model selected up front. A rerank ORDERS answers, so
        // it's only meaningful with ≥2 — auto-creating it on a 1-answer report
        // (common: single-model reports) wasted a worker call ranking a lone
        // item and left a junk row. Moderation is fine at 1, so it keeps the
        // <1 gate above.
        if (successCount >= 2 && !hasKind(SecondaryKind.RERANK)) secondary.runRerank(context, reportId)
        // Moderation still needs a native moderation model (only Mistral
        // is wired), so gate on one being active to avoid an error row.
        val hasModModel = firstModelOfType(aiSettings, ModelType.MODERATION) != null
        if (!hasModModel) AppLog.i("Report", "auto-moderation skipped: no moderation-capable model")
        else if (!hasKind(SecondaryKind.MODERATION)) secondary.runModeration(context, reportId)
    }

    /** On a normal report completion, auto-create one META secondary per
     *  configured [Settings.defaultMetaItems] row. Driven purely by the
     *  list (no settings toggle): each row names a category-`meta` Internal
     *  Prompt and a target. Idempotent — a meta prompt that already has a
     *  result for this report is skipped, and rows with no resolvable
     *  prompt/model are logged and skipped. Uses the same
     *  [SecondaryRunManager.runMetaPrompt] the manual UI calls. */
    private fun maybeAutoCreateDefaultMetas(
        context: Context, reportId: String, aiSettings: Settings, successCount: Int
    ) {
        if (ReportStorage.getReport(context, reportId)?.workerConfig?.primaryAnswersOnly == true) return
        if (!appViewModel.uiState.value.generalSettings.autostartItemsEnabled) return
        if (successCount < 1) return
        val items = aiSettings.defaultMetaItems.filter { it.active }
        if (items.isEmpty()) return
        val existingMetaNames = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .mapNotNull { it.metaPromptName?.lowercase() }.toMutableSet()
        for (item in items) {
            val prompt = aiSettings.internalPrompts.firstOrNull {
                it.category == "meta" && it.name.equals(item.metaName, ignoreCase = true)
            }
            if (prompt == null) {
                AppLog.i("Report", "auto-meta skipped: no meta prompt '${item.metaName}'"); continue
            }
            if (prompt.name.lowercase() in existingMetaNames) continue  // idempotent
            // Meta runs through the Meta worker swarm now; the default
            // item's configured target is no longer needed to dispatch.
            secondary.runMetaPrompt(context, reportId, prompt)
            existingMetaNames += prompt.name.lowercase()  // guard against duplicate rows in one pass
        }
    }

    private suspend fun executeReportTask(
        context: Context, reportId: String, aiPrompt: String, overrideParams: AgentParameters?, task: ReportTask,
        imageBase64: String? = null, imageMime: String? = null,
        // Skip the genericReportsProgress increment when re-running a
        // single agent on a finished report — the agent was already
        // counted as complete the first time around, so bumping the
        // counter again would push past total and break the progress
        // bar / completion-equality check.
        isRegeneration: Boolean = false,
        // Headless = a background report (Stress test) that isn't the
        // one shown in the live generic-reports dialog: skip ALL the
        // shared single-report UI-state writes (_agentResults +
        // genericReportsProgress) so concurrent background reports don't
        // clobber the foreground report's progress/results. Disk
        // persistence (markAgent*Async) is per-report and always runs.
        headless: Boolean = false
    ) {
        AppLog.d("Report", "→ task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId}${if (isRegeneration) " (regen)" else ""}")
        // The shared single-report UI state (_agentResults + the
        // genericReports* counters) always describes the report currently
        // on screen. A run continued in the background keeps landing tasks
        // while the user may have opened a DIFFERENT report — its
        // completions must not bump the visible report's progress, and
        // (since direct-model agent ids are deterministic across reports,
        // "swarm:provider:model") its responses must not flip the other
        // report's matching rows. updateAgentResults / bumpGenerationProgress
        // check the owner inside the write itself: a separate check-then-
        // write let a completion racing a report switch slip into the newly
        // opened report.
        // Model already benched by an earlier run — skip the doomed
        // call, but keep the agent as a visible red error row (don't
        // remove it / shrink the total). Still counts as progress so
        // the run can reach completion.
        if (isBenched(task.runtimeAgent.provider, task.runtimeAgent.model)) {
            AppLog.w("Report", "skip benched ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} — marking agent ${task.resultId} errored")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                ReportStorage.markAgentErrorAsync(
                    context, reportId, task.resultId, null,
                    "${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} is rate-limited (benched) — skipped"
                )
            }
            if (!headless) {
                // Publish the error into _agentResults too, mirroring the
                // normal failure path — GenerationPhase renders result==null
                // as an infinite hourglass, and the only re-hydration trigger
                // (Nav) fires solely when agentResults is empty, so without
                // this the benched row spun forever (even after the run
                // completed) until the user left and reopened the report.
                // Regeneration paths NEED this publish: they remove the
                // agent's entry before dispatching, so gating it on
                // !isRegeneration (as the progress bump below rightly is,
                // matching the normal completion path's split) left every
                // benched row of a regenerate spinning forever.
                updateAgentResults(reportId) { it + (task.resultId to AnalysisResponse(
                    service = task.runtimeAgent.provider, analysis = null,
                    error = "${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} is rate-limited (benched) — skipped"
                )) }
                if (!isRegeneration) bumpGenerationProgress(reportId)
            }
            return
        }
        // The resolved prompt is the request BODY, not headers — passing it
        // positionally landed it in requestHeaders (Bug 57). Name the arg.
        val storedReport = ReportKnowledge.prepare(context, reportId, appViewModel.repository,
            appViewModel.uiState.value.aiSettings) ?: return
        val previous = storedReport.agents.firstOrNull { it.agentId == task.resultId } ?: return
        val execution = (previous.pendingExecutionConfig ?: previous.executionConfig)?.let { saved ->
            if (saved.refreshPrompt) {
                val question = appViewModel.repository.resolveReportPrompt(aiPrompt, task.runtimeAgent)
                val prompt = storedReport.knowledgeContext?.takeIf { it.isNotBlank() }?.let { "$it\n\n$question" } ?: question
                saved.copy(prompt = prompt, refreshPrompt = false, capturedAt = System.currentTimeMillis())
            } else if (isRegeneration) saved else storedReport.knowledgeContext?.takeIf { it.isNotBlank() }
                ?.let { saved.copy(prompt="$it\n\n${saved.prompt}") } ?: saved
        } ?: run {
            val params = appViewModel.repository.effectiveReportParameters(task.resolvedParams, overrideParams,
                task.runtimeAgent.provider, task.runtimeAgent.model, context)
            val question = appViewModel.repository.resolveReportPrompt(aiPrompt, task.runtimeAgent)
            val prompt = storedReport.knowledgeContext?.takeIf { it.isNotBlank() }?.let { "$it\n\n$question" } ?: question
            com.ai.data.ReportExecutionConfig(params,
                appViewModel.uiState.value.aiSettings.getEffectiveEndpointUrlForAgent(task.runtimeAgent), prompt,
                baseParameters = task.resolvedParams)
        }
        val attemptId = ReportStorage.beginAgentAttempt(context, reportId, task.resultId, execution) ?: return
        val knowledgeBaseIds = emptyList<String>() // context is part of the saved execution prompt
        val startTime = System.currentTimeMillis()
        // Capture the primary call's trace filename so it can be stored on
        // the agent row (read directly by the per-model viewer's 🐞 instead
        // of a fragile ApiTracer time-based guess).
        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
        // Per-call hang protection lives at the shared dispatch chokepoint
        // (AnalysisRepository.analyze → withApiCallTimeout), which bounds
        // DNS-phase hangs for every flow and surfaces a timeout as an
        // IOException — handled by the catch (Exception) below like any
        // other failed call.
        val response = try {
            val baseUrl = execution.endpointUrl
            com.ai.data.withTraceFilenameSink(traceSink) {
                if (headless) {
                    // Background / best-effort calls have no card to stream into.
                    appViewModel.repository.analyzeWithAgent(
                        task.runtimeAgent, "", execution.prompt, execution.parameters, null,
                        context, baseUrl, imageBase64, imageMime,
                        knowledgeBaseIds = knowledgeBaseIds,
                        aiSettings = appViewModel.uiState.value.aiSettings
                    )
                } else {
                    // Stream the answer (keeps the connection active on long
                    // generations; result + cost stay as exact as the
                    // non-streaming call, falling back to it when needed). The
                    // live per-chunk row preview was removed, so chunks aren't
                    // accumulated for display.
                    appViewModel.repository.analyzeWithAgentStreaming(
                        task.runtimeAgent, "", execution.prompt, execution.parameters, null,
                        context, baseUrl, imageBase64, imageMime,
                        knowledgeBaseIds = knowledgeBaseIds,
                        aiSettings = appViewModel.uiState.value.aiSettings
                    ) { /* chunk ignored — no live preview */ }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Honor structured cancellation (Stop / nav-away) instead of
            // persisting a fake error onto the agent row. The job-level
            // finally terminalizes any row left PENDING/RUNNING as STOPPED.
            throw e
        } catch (e: Exception) {
            // Cap the persisted error string — OutOfMemoryError /
            // StackOverflowError can carry kilobyte-sized messages
            // that bloat the report JSON file with no diagnostic
            // value beyond the first line.
            AnalysisResponse(service = task.runtimeAgent.provider, analysis = null,
                error = (e.message ?: "Unknown error").take(2000))
        }
        val durationMs = System.currentTimeMillis() - startTime
        val cost = calculateResponseCost(context, task.runtimeAgent.provider, task.runtimeAgent.model, response.tokenUsage)
        // Pin the in / out cost halves at run time using the
        // [PricingCache] prices in effect right now. The Costs
        // cards prefer the persisted split, so a later catalog
        // re-price won't shift the historical numbers shown on
        // an old report. Mirrors the secondary-result path's
        // long-standing "freeze on completion" behaviour.
        val (frozenInputCost, frozenOutputCost) = response.tokenUsage?.let { tu ->
            val pricing = PricingCache.getPricing(context, task.runtimeAgent.provider, task.runtimeAgent.model)
            PricingCache.computeInOutCost(tu, pricing)
        } ?: (0.0 to 0.0)

        // Persist the terminal state under NonCancellable so a Stop /
        // navigate-away that arrives between the API return and this
        // disk write doesn't strand the agent row in RUNNING on disk.
        // The async helpers themselves marshal the I/O off-thread.
        // A benched-on-this-call >1h 429 flows through the normal
        // error path — it stays as a visible red row, same as any
        // other failure, instead of being removed from the run.
        val persisted = persistReportCompletion(recordUsage = {
            if (response.tokenUsage != null) {
                val usage = response.tokenUsage
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    task.runtimeAgent.provider, task.runtimeAgent.model, usage, durationMs = durationMs
                )
            }

        }, saveAnswer = {
            ReportStorage.updateAgentStatus(context, reportId, task.resultId,
                if (response.isSuccess) ReportStatus.SUCCESS else ReportStatus.ERROR,
                com.ai.data.AgentStatusPatch(httpStatus=response.httpStatusCode,
                    finishReason=response.finishReason,
                    requestBody=execution.prompt, responseHeaders=response.httpHeaders, responseBody=response.analysis,
                    errorMessage=response.error, tokenUsage=response.tokenUsage, cost=cost,
                    inputCost=response.tokenUsage?.let { frozenInputCost }, outputCost=response.tokenUsage?.let { frozenOutputCost },
                    citations=response.citations, searchResults=response.searchResults, relatedQuestions=response.relatedQuestions,
                    rawUsageJson=response.rawUsageJson, durationMs=durationMs, traceFile=traceSink.get(), attemptId=attemptId))
        })

        if (!persisted) {
            cost?.takeIf { it > 0.0 }?.let {
                ReportStorage.bumpCostsFromDeletedItems(context, reportId, it)
            }
        }


        val stillPresent = ReportStorage.getReport(context, reportId)
            ?.agents
            ?.any { it.agentId == task.resultId && it.attemptId == attemptId } == true
        if (!stillPresent) {
            AppLog.d("Report", "skip UI publish for deleted agent=${task.resultId} report=$reportId")
            // The agent was removed mid-run. removeAgentInternal already
            // decremented genericReportsTotal for this (unfinished) slot, so
            // DON'T bump progress here — doing both was a double-compensation
            // that pushed final progress to total+1 and fired isComplete one
            // task early (a still-running sibling kept its hourglass on a
            // report shown as complete). The removal path now owns the
            // accounting; the counters stay balanced without a late bump.
            return
        }
        if (!headless) updateAgentResults(reportId) { it + (task.resultId to response) }
        if (!isRegeneration && !headless) bumpGenerationProgress(reportId)
        AppLog.d(
            "Report",
            "← task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId} " +
                (if (response.isSuccess) "ok" else "err") +
                " ${durationMs}ms" +
                (response.tokenUsage?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") +
                (cost?.let { " cost=${String.format(java.util.Locale.US, "%.5f", it)}" } ?: "")
        )
    }

    private fun updateTemperatureSweepState(key: String, transform: (TemperatureSweepState) -> TemperatureSweepState) =
        temperatureSweep.update(key, transform)

    private fun setTemperatureSweepCandidate(
        key: String,
        index: Int,
        candidate: TemperatureSweepCandidate
    ) {
        updateTemperatureSweepState(key) { state ->
            state.copy(candidates = state.candidates.mapIndexed { i, old -> if (i == index) candidate else old })
        }
    }

    fun clearTemperatureSweep(reportId: String, agentId: String) {
        temperatureSweep.cancel(TemperatureSweepState.key(reportId, agentId))
    }

    fun applyTemperatureCandidate(context: Context, reportId: String, agentId: String, candidateIndex: Int) {
        val key = TemperatureSweepState.key(reportId, agentId)
        val candidate = temperatureSweep.get(key)?.candidates
            ?.getOrNull(candidateIndex) as? TemperatureSweepCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = candidate.response,
                changeSource = RESPONSE_CHANGE_SOURCE_TEMPERATURE,
                changeValue = formatSweepTemperature(candidate.temperature)
            )
            temperatureSweep.drop(key)
        }
    }

    fun startTemperatureSweep(
        context: Context,
        reportId: String,
        agentId: String,
        temperatures: List<Float>
    ): Job {
        val key = TemperatureSweepState.key(reportId, agentId)
        val temps = temperatures.take(3)
        temperatureSweep.cancelJob(key)
        temperatureSweep.set(key, TemperatureSweepState(
            reportId = reportId,
            agentId = agentId,
            candidates = temps.map { temp -> TemperatureSweepCandidate.Pending(temp) },
            isRunning = true
        ))
        val job = appViewModel.viewModelScope.launch(reportLogContext()) {
            try {
                val report = ReportStorage.getReport(context, reportId) ?: run {
                    updateTemperatureSweepState(key) {
                        it.copy(isRunning = false, unavailableMessage = "Report not found")
                    }
                    return@launch
                }
                val state = appViewModel.uiState.value
                val ai = state.aiSettings
                val savedAgent = report.agents.firstOrNull { it.agentId == agentId } ?: run {
                    updateTemperatureSweepState(key) {
                        it.copy(isRunning = false, unavailableMessage = "Model response no longer exists in this report")
                    }
                    return@launch
                }
                val task = buildTemperatureSweepTask(report, state, savedAgent) ?: run {
                    updateTemperatureSweepState(key) {
                        it.copy(isRunning = false, unavailableMessage = "Model response no longer matches a runnable report agent")
                    }
                    return@launch
                }
                // The cached catalog can describe a different endpoint or reasoning
                // mode. Shared dispatch validates the actual requested experiment.
                val temperatureRange = temperatureRangeForProvider(task.runtimeAgent.provider)
                val invalidTemp = temps.firstOrNull { !temperatureRange.contains(it) }
                if (temps.isEmpty() || invalidTemp != null) {
                    val msg = if (temps.isEmpty()) {
                        "Choose at least one temperature."
                    } else {
                        "${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} allows temperature " +
                            "${formatSweepTemperature(temperatureRange.min)}..${formatSweepTemperature(temperatureRange.max)}."
                    }
                    updateTemperatureSweepState(key) { sweep ->
                        sweep.copy(
                            isRunning = false,
                            unavailableMessage = msg,
                            candidates = sweep.candidates.map { candidate ->
                                TemperatureSweepCandidate.Error(candidate.temperature, msg, null, null, null)
                            }
                        )
                    }
                    return@launch
                }
                val canReason = ai.acceptsReasoningEffortParam(task.runtimeAgent.provider, task.runtimeAgent.model)
                val canWeb = ai.isWebSearchCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
                val canVision = ai.isVisionCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
                val baseOverride = resolveReportOverrideParams(
                    ai, report.parameterPresetIds, report.advancedParameters,
                    report.webSearchTool, report.reasoningEffort, report.reportSystemPromptId
                )
                val gatedOverride = (baseOverride ?: AgentParameters()).copy(
                    webSearchTool = (baseOverride?.webSearchTool == true || report.webSearchTool) && canWeb,
                    reasoningEffort = if (canReason) baseOverride?.reasoningEffort else null
                )
                val effectiveImage = if (canVision) report.imageBase64 else null
                val effectiveImageMime = if (canVision) report.imageMime else null
                val baseUrl = ai.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
                val knowledgeBaseIds = report.knowledgeBaseIds

                withTracerTags(reportId = reportId, category = MODEL_TEMPERATURE_CALL_KIND) {
                    temps.forEachIndexed { index, temp ->
                        setTemperatureSweepCandidate(key, index, TemperatureSweepCandidate.Running(temp))
                        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                        val startTime = System.currentTimeMillis()
                        val response = try {
                                    val permitHold = acquireThrottledPermits(ApiCallCaps.report, providerHost(task.runtimeAgent.provider))
                                    try {
                                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                            withTraceFilenameSink(traceSink) {
                                                appViewModel.repository.analyzeWithAgentStreaming(
                                                    task.runtimeAgent, "", report.prompt,
                                                    task.resolvedParams,
                                                    gatedOverride.copy(temperature = temp),
                                                    context, baseUrl, effectiveImage, effectiveImageMime,
                                                    knowledgeBaseIds = knowledgeBaseIds,
                                                    aiSettings = ai
                                                ) { /* transient comparison; no live preview */ }
                                            }
                                        }
                                    } finally {
                                        permitHold.dispose()
                                    }

                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AnalysisResponse(
                                service = task.runtimeAgent.provider,
                                analysis = null,
                                error = (e.message ?: "Unknown error").take(2000)
                            )
                        }
                        val durationMs = System.currentTimeMillis() - startTime
                        val cost = calculateResponseCost(context, task.runtimeAgent.provider, task.runtimeAgent.model, response.tokenUsage)
                        val traceFile = traceSink.get()
                        if (response.error == null && response.tokenUsage != null) {
                            val usage = response.tokenUsage
                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                task.runtimeAgent.provider, task.runtimeAgent.model,
                                usage,
                                kind = MODEL_TEMPERATURE_CALL_KIND,
                                durationMs = durationMs
                            )
                        }
                        val candidate = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                            TemperatureSweepCandidate.Success(
                                temperature = temp,
                                response = response.analysis,
                                tokenUsage = response.tokenUsage,
                                cost = cost,
                                durationMs = durationMs,
                                traceFile = traceFile
                            )
                        } else {
                            TemperatureSweepCandidate.Error(
                                temperature = temp,
                                message = response.error ?: "No response body",
                                httpStatusCode = response.httpStatusCode,
                                durationMs = durationMs,
                                traceFile = traceFile
                            )
                        }
                        setTemperatureSweepCandidate(key, index, candidate)
                    }
                }
                updateTemperatureSweepState(key) { it.copy(isRunning = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTemperatureSweepState(key) {
                    it.copy(isRunning = false, unavailableMessage = (e.message ?: "Temperature sweep failed").take(2000))
                }
            }
        }
        temperatureSweep.registerJob(key, job)
        return job
    }

    private fun updateReasoningEffortSweepState(key: String, transform: (ReasoningEffortSweepState) -> ReasoningEffortSweepState) =
        reasoningEffortSweep.update(key, transform)

    private fun setReasoningEffortCandidate(
        key: String,
        index: Int,
        candidate: ReasoningEffortCandidate
    ) {
        updateReasoningEffortSweepState(key) { state ->
            state.copy(candidates = state.candidates.mapIndexed { i, old -> if (i == index) candidate else old })
        }
    }

    fun clearReasoningEffortSweep(reportId: String, agentId: String) {
        reasoningEffortSweep.cancel(ReasoningEffortSweepState.key(reportId, agentId))
    }

    fun applyReasoningEffortCandidate(context: Context, reportId: String, agentId: String, candidateIndex: Int) {
        val key = ReasoningEffortSweepState.key(reportId, agentId)
        val candidate = reasoningEffortSweep.get(key)?.candidates
            ?.getOrNull(candidateIndex) as? ReasoningEffortCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = candidate.response,
                changeSource = RESPONSE_CHANGE_SOURCE_REASONING_EFFORT,
                changeValue = formatSweepReasoningEffort(candidate.effort)
            )
            reasoningEffortSweep.drop(key)
        }
    }

    fun startReasoningEffortSweep(
        context: Context,
        reportId: String,
        agentId: String,
        efforts: List<String?>
    ): Job {
        val key = ReasoningEffortSweepState.key(reportId, agentId)
        val supportedFixedEfforts = setOf("low", "medium", "high")
        val requestedEfforts = efforts.take(4).map { raw ->
            raw?.trim()?.lowercase(Locale.US)?.takeIf { it in supportedFixedEfforts }
        }.ifEmpty { listOf("low", "high") }
        reasoningEffortSweep.cancelJob(key)
        reasoningEffortSweep.set(key, ReasoningEffortSweepState(
            reportId = reportId,
            agentId = agentId,
            candidates = requestedEfforts.map { effort -> ReasoningEffortCandidate.Pending(effort) },
            isRunning = true
        ))
        val job = appViewModel.viewModelScope.launch(reportLogContext()) {
            try {
                val report = ReportStorage.getReport(context, reportId) ?: run {
                    updateReasoningEffortSweepState(key) {
                        it.copy(isRunning = false, unavailableMessage = "Report not found")
                    }
                    return@launch
                }
                val state = appViewModel.uiState.value
                val ai = state.aiSettings
                val savedAgent = report.agents.firstOrNull { it.agentId == agentId } ?: run {
                    updateReasoningEffortSweepState(key) {
                        it.copy(isRunning = false, unavailableMessage = "Model response no longer exists in this report")
                    }
                    return@launch
                }
                val task = buildTemperatureSweepTask(report, state, savedAgent) ?: run {
                    updateReasoningEffortSweepState(key) {
                        it.copy(isRunning = false, unavailableMessage = "Model response no longer matches a runnable report agent")
                    }
                    return@launch
                }
                if (!ai.acceptsReasoningEffortParam(task.runtimeAgent.provider, task.runtimeAgent.model)) {
                    val msg = "${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} does not accept controllable reasoning effort."
                    updateReasoningEffortSweepState(key) { sweep ->
                        sweep.copy(
                            isRunning = false,
                            unavailableMessage = msg,
                            candidates = sweep.candidates.map { candidate ->
                                ReasoningEffortCandidate.Error(candidate.effort, msg, null, null, null)
                            }
                        )
                    }
                    return@launch
                }
                val supportedLevels = ai.getProvider(task.runtimeAgent.provider)
                    .modelCapabilities[task.runtimeAgent.model]
                    ?.reasoningEffortLevels
                    ?.map { it.lowercase(Locale.US) }
                    ?.toSet()
                val canWeb = ai.isWebSearchCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
                val canVision = ai.isVisionCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
                val baseOverride = resolveReportOverrideParams(
                    ai, report.parameterPresetIds, report.advancedParameters,
                    report.webSearchTool, report.reasoningEffort, report.reportSystemPromptId
                )
                val baseNoReasoningOverride = (baseOverride ?: AgentParameters()).copy(
                    webSearchTool = (baseOverride?.webSearchTool == true || report.webSearchTool) && canWeb,
                    reasoningEffort = null
                )
                val effectiveImage = if (canVision) report.imageBase64 else null
                val effectiveImageMime = if (canVision) report.imageMime else null
                val baseUrl = ai.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
                val knowledgeBaseIds = report.knowledgeBaseIds

                withTracerTags(reportId = reportId, category = MODEL_REASONING_CALL_KIND) {
                    requestedEfforts.forEachIndexed { index, effort ->
                        if (effort != null && supportedLevels != null && effort !in supportedLevels) {
                            val msg = "${formatSweepReasoningEffort(effort)} reasoning effort is not reported as supported by " +
                                "${task.runtimeAgent.provider.id}/${task.runtimeAgent.model}."
                            setReasoningEffortCandidate(
                                key,
                                index,
                                ReasoningEffortCandidate.Error(effort, msg, null, null, null)
                            )
                            return@forEachIndexed
                        }
                        setReasoningEffortCandidate(key, index, ReasoningEffortCandidate.Running(effort))
                        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                        val startTime = System.currentTimeMillis()
                        val response = try {
                                    val permitHold = acquireThrottledPermits(ApiCallCaps.report, providerHost(task.runtimeAgent.provider))
                                    try {
                                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                            withTraceFilenameSink(traceSink) {
                                                appViewModel.repository.analyzeWithAgentStreaming(
                                                    task.runtimeAgent, "", report.prompt,
                                                    if (effort == null) task.resolvedParams.copy(reasoningEffort = null) else task.resolvedParams,
                                                    baseNoReasoningOverride.copy(reasoningEffort = effort),
                                                    context, baseUrl, effectiveImage, effectiveImageMime,
                                                    knowledgeBaseIds = knowledgeBaseIds,
                                                    aiSettings = ai
                                                ) { /* transient comparison; no live preview */ }
                                            }
                                        }
                                    } finally {
                                        permitHold.dispose()
                                    }

                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AnalysisResponse(
                                service = task.runtimeAgent.provider,
                                analysis = null,
                                error = (e.message ?: "Unknown error").take(2000)
                            )
                        }
                        val durationMs = System.currentTimeMillis() - startTime
                        val cost = calculateResponseCost(context, task.runtimeAgent.provider, task.runtimeAgent.model, response.tokenUsage)
                        val traceFile = traceSink.get()
                        if (response.error == null && response.tokenUsage != null) {
                            val usage = response.tokenUsage
                            appViewModel.settingsPrefs.updateUsageStatsAsync(
                                task.runtimeAgent.provider, task.runtimeAgent.model,
                                usage,
                                kind = MODEL_REASONING_CALL_KIND,
                                durationMs = durationMs
                            )
                        }
                        val candidate = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                            ReasoningEffortCandidate.Success(
                                effort = effort,
                                response = response.analysis,
                                tokenUsage = response.tokenUsage,
                                cost = cost,
                                durationMs = durationMs,
                                traceFile = traceFile
                            )
                        } else {
                            ReasoningEffortCandidate.Error(
                                effort = effort,
                                message = response.error ?: "No response body",
                                httpStatusCode = response.httpStatusCode,
                                durationMs = durationMs,
                                traceFile = traceFile
                            )
                        }
                        setReasoningEffortCandidate(key, index, candidate)
                    }
                }
                updateReasoningEffortSweepState(key) { it.copy(isRunning = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateReasoningEffortSweepState(key) {
                    it.copy(isRunning = false, unavailableMessage = (e.message ?: "Reasoning effort sweep failed").take(2000))
                }
            }
        }
        reasoningEffortSweep.registerJob(key, job)
        return job
    }

    private fun updateWebSearchReplayState(key: String, transform: (WebSearchReplayState) -> WebSearchReplayState) =
        webSearchReplay.update(key, transform)

    fun clearWebSearchReplay(reportId: String, agentId: String) {
        webSearchReplay.cancel(WebSearchReplayState.key(reportId, agentId))
    }

    fun applyWebSearchReplay(context: Context, reportId: String, agentId: String) {
        val key = WebSearchReplayState.key(reportId, agentId)
        val result = webSearchReplay.get(key)?.result as? WebSearchReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = result.response,
                changeSource = RESPONSE_CHANGE_SOURCE_WEB_SEARCH
            )
            webSearchReplay.drop(key)
        }
    }

    fun startWebSearchReplay(
        context: Context,
        reportId: String,
        agentId: String
    ): Job {
        val key = WebSearchReplayState.key(reportId, agentId)
        webSearchReplay.cancelJob(key)
        webSearchReplay.set(key, WebSearchReplayState(
            reportId = reportId,
            agentId = agentId,
            result = WebSearchReplayResult.Running,
            isRunning = true
        ))
        val job = appViewModel.viewModelScope.launch(reportLogContext()) {
            try {
                val report = ReportStorage.getReport(context, reportId) ?: run {
                    updateWebSearchReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = WebSearchReplayResult.Error("Report not found", null, null, null),
                            unavailableMessage = "Report not found"
                        )
                    }
                    return@launch
                }
                val state = appViewModel.uiState.value
                val ai = state.aiSettings
                val savedAgent = report.agents.firstOrNull { it.agentId == agentId } ?: run {
                    val msg = "Model response no longer exists in this report"
                    updateWebSearchReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = WebSearchReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                val task = buildTemperatureSweepTask(report, state, savedAgent) ?: run {
                    val msg = "Model response no longer matches a runnable report agent"
                    updateWebSearchReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = WebSearchReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                if (!ai.isWebSearchCapable(task.runtimeAgent.provider, task.runtimeAgent.model)) {
                    val msg = "${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} does not report web-search support."
                    updateWebSearchReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = WebSearchReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                val canReason = ai.acceptsReasoningEffortParam(task.runtimeAgent.provider, task.runtimeAgent.model)
                val canVision = ai.isVisionCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
                val baseOverride = resolveReportOverrideParams(
                    ai, report.parameterPresetIds, report.advancedParameters,
                    report.webSearchTool, report.reasoningEffort, report.reportSystemPromptId
                )
                val webOverride = (baseOverride ?: AgentParameters()).copy(
                    webSearchTool = true,
                    reasoningEffort = if (canReason) baseOverride?.reasoningEffort else null
                )
                val resolvedParams = if (canReason) task.resolvedParams else task.resolvedParams.copy(reasoningEffort = null)
                val effectiveImage = if (canVision) report.imageBase64 else null
                val effectiveImageMime = if (canVision) report.imageMime else null
                val baseUrl = ai.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
                val knowledgeBaseIds = report.knowledgeBaseIds
                val replayPrompt = webSearchReplayPrompt(report.prompt)

                withTracerTags(reportId = reportId, category = MODEL_WEB_SEARCH_CALL_KIND) {
                    val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                    val startTime = System.currentTimeMillis()
                    val response = try {
                                val permitHold = acquireThrottledPermits(ApiCallCaps.report, providerHost(task.runtimeAgent.provider))
                                try {
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        withTraceFilenameSink(traceSink) {
                                            appViewModel.repository.analyzeWithAgentStreaming(
                                                task.runtimeAgent, "", replayPrompt,
                                                resolvedParams, webOverride,
                                                context, baseUrl, effectiveImage, effectiveImageMime,
                                                knowledgeBaseIds = knowledgeBaseIds,
                                                aiSettings = ai
                                            ) { /* transient comparison; no live preview */ }
                                        }
                                    }
                                } finally {
                                    permitHold.dispose()
                                }

                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AnalysisResponse(
                            service = task.runtimeAgent.provider,
                            analysis = null,
                            error = (e.message ?: "Unknown error").take(2000)
                        )
                    }
                    val durationMs = System.currentTimeMillis() - startTime
                    val cost = calculateResponseCost(context, task.runtimeAgent.provider, task.runtimeAgent.model, response.tokenUsage)
                    val traceFile = traceSink.get()
                    if (response.error == null && response.tokenUsage != null) {
                        val usage = response.tokenUsage
                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                            task.runtimeAgent.provider, task.runtimeAgent.model,
                            usage,
                            kind = MODEL_WEB_SEARCH_CALL_KIND,
                            durationMs = durationMs
                        )
                    }
                    val result = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                        WebSearchReplayResult.Success(
                            response = response.analysis,
                            tokenUsage = response.tokenUsage,
                            cost = cost,
                            durationMs = durationMs,
                            traceFile = traceFile
                        )
                    } else {
                        WebSearchReplayResult.Error(
                            message = response.error ?: "No response body",
                            httpStatusCode = response.httpStatusCode,
                            durationMs = durationMs,
                            traceFile = traceFile
                        )
                    }
                    updateWebSearchReplayState(key) { it.copy(isRunning = false, result = result) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: "Web search replay failed").take(2000)
                updateWebSearchReplayState(key) {
                    it.copy(
                        isRunning = false,
                        result = WebSearchReplayResult.Error(msg, null, null, null),
                        unavailableMessage = msg
                    )
                }
            }
        }
        webSearchReplay.registerJob(key, job)
        return job
    }

    private fun updatePromptEditReplayState(key: String, transform: (PromptEditReplayState) -> PromptEditReplayState) =
        promptEditReplay.update(key, transform)

    fun clearPromptEditReplay(reportId: String, agentId: String) {
        promptEditReplay.cancel(PromptEditReplayState.key(reportId, agentId))
    }

    fun applyPromptEditReplay(context: Context, reportId: String, agentId: String) {
        val key = PromptEditReplayState.key(reportId, agentId)
        val result = promptEditReplay.get(key)?.result as? PromptEditReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = result.response,
                changeSource = RESPONSE_CHANGE_SOURCE_EDIT
            )
            promptEditReplay.drop(key)
        }
    }

    fun startPromptEditReplay(
        context: Context,
        reportId: String,
        agentId: String,
        prompt: String,
        parameterPresetIds: List<String>,
        systemPromptId: String?
    ): Job {
        val key = PromptEditReplayState.key(reportId, agentId)
        val editedPrompt = prompt.trim()
        promptEditReplay.cancelJob(key)
        promptEditReplay.set(key, PromptEditReplayState(
            reportId = reportId,
            agentId = agentId,
            result = PromptEditReplayResult.Running,
            isRunning = true
        ))
        val job = appViewModel.viewModelScope.launch(reportLogContext()) {
            try {
                if (editedPrompt.isBlank()) {
                    val msg = "Prompt is empty"
                    updatePromptEditReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = PromptEditReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                val report = ReportStorage.getReport(context, reportId) ?: run {
                    val msg = "Report not found"
                    updatePromptEditReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = PromptEditReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                val state = appViewModel.uiState.value
                val ai = state.aiSettings
                val savedAgent = report.agents.firstOrNull { it.agentId == agentId } ?: run {
                    val msg = "Model response no longer exists in this report"
                    updatePromptEditReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = PromptEditReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                val task = buildTemperatureSweepTask(report, state, savedAgent) ?: run {
                    val msg = "Model response no longer matches a runnable report agent"
                    updatePromptEditReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = PromptEditReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                val canReason = ai.acceptsReasoningEffortParam(task.runtimeAgent.provider, task.runtimeAgent.model)
                val canWeb = ai.isWebSearchCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
                val canVision = ai.isVisionCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
                val baseOverride = resolveReportOverrideParams(
                    ai, report.parameterPresetIds, report.advancedParameters,
                    report.webSearchTool, report.reasoningEffort, report.reportSystemPromptId
                )
                val gatedOverride = (baseOverride ?: AgentParameters()).copy(
                    webSearchTool = (baseOverride?.webSearchTool == true || report.webSearchTool) && canWeb,
                    reasoningEffort = if (canReason) baseOverride?.reasoningEffort else null
                )
                val screenOverride = promptEditOverrideParams(ai, parameterPresetIds, systemPromptId)
                val baseResolved = if (canReason) task.resolvedParams else task.resolvedParams.copy(reasoningEffort = null)
                val finalParams = overlayAgentParameters(
                    overlayAgentParameters(baseResolved, gatedOverride),
                    screenOverride
                )!!.let { merged ->
                    merged.copy(
                        webSearchTool = merged.webSearchTool && canWeb,
                        reasoningEffort = if (canReason) merged.reasoningEffort else null
                    )
                }
                val effectiveImage = if (canVision) report.imageBase64 else null
                val effectiveImageMime = if (canVision) report.imageMime else null
                val baseUrl = ai.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
                val knowledgeBaseIds = report.knowledgeBaseIds

                withTracerTags(reportId = reportId, category = MODEL_PROMPT_EDIT_CALL_KIND) {
                    val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
                    val startTime = System.currentTimeMillis()
                    val response = try {
                                val permitHold = acquireThrottledPermits(ApiCallCaps.report, providerHost(task.runtimeAgent.provider))
                                try {
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        withTraceFilenameSink(traceSink) {
                                            appViewModel.repository.analyzeWithAgentStreaming(
                                                task.runtimeAgent, "", editedPrompt,
                                                finalParams, null,
                                                context, baseUrl, effectiveImage, effectiveImageMime,
                                                knowledgeBaseIds = knowledgeBaseIds,
                                                aiSettings = ai
                                            ) { /* transient prompt edit; no live preview */ }
                                        }
                                    }
                                } finally {
                                    permitHold.dispose()
                                }

                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AnalysisResponse(
                            service = task.runtimeAgent.provider,
                            analysis = null,
                            error = (e.message ?: "Unknown error").take(2000)
                        )
                    }
                    val durationMs = System.currentTimeMillis() - startTime
                    val cost = calculateResponseCost(context, task.runtimeAgent.provider, task.runtimeAgent.model, response.tokenUsage)
                    val traceFile = traceSink.get()
                    if (response.error == null && response.tokenUsage != null) {
                        val usage = response.tokenUsage
                        appViewModel.settingsPrefs.updateUsageStatsAsync(
                            task.runtimeAgent.provider,
                            task.runtimeAgent.model,
                            usage,
                            kind = MODEL_PROMPT_EDIT_CALL_KIND,
                            durationMs = durationMs
                        )
                    }
                    val result = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                        PromptEditReplayResult.Success(
                            response = response.analysis,
                            tokenUsage = response.tokenUsage,
                            cost = cost,
                            durationMs = durationMs,
                            traceFile = traceFile
                        )
                    } else {
                        PromptEditReplayResult.Error(
                            message = response.error ?: "No response body",
                            httpStatusCode = response.httpStatusCode,
                            durationMs = durationMs,
                            traceFile = traceFile
                        )
                    }
                    updatePromptEditReplayState(key) { it.copy(isRunning = false, result = result) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: "Prompt edit replay failed").take(2000)
                updatePromptEditReplayState(key) {
                    it.copy(
                        isRunning = false,
                        result = PromptEditReplayResult.Error(msg, null, null, null),
                        unavailableMessage = msg
                    )
                }
            }
        }
        promptEditReplay.registerJob(key, job)
        return job
    }

    /** Run [reportTasks]' primary calls for [reportId], interleaved by host
     *  and throttled (global → report → per-host), each firing its own
     *  per-model enrichment the moment it lands. Suspends until every
     *  primary call has settled. Shared by the foreground generic-report
     *  flow and the background Stress-test runner; [headless] forwards to
     *  [executeReportTask] to suppress the live single-report UI writes. */
    private suspend fun runReportPrimaryCalls(
        context: Context, reportId: String, aiPrompt: String,
        overrideParams: AgentParameters?, reportTasks: List<ReportTask>,
        aiSettings: Settings, imageBase64: String?, imageMime: String?, headless: Boolean
    ) {
        ReportKnowledge.prepare(context, reportId, appViewModel.repository, aiSettings)
        com.ai.data.ReportWorkLimits.checkSize(reportTasks.size)
        interleaveByHost(reportTasks) { providerHost(it.runtimeAgent.provider) }.chunked(64).forEach { window ->
        coroutineScope {
            // Interleave by host so a picks list clustered by provider
            // doesn't have the first launches all hammer one host while
            // holding outer cap permits idle.
            window.map { task ->
                async {
                    // Canonical acquire order: global → report → per-host
                    // (acquireOrRequeue). The per-host gate MUST be acquired
                    // INSIDE global, never before it: the metadata / secondary
                    // calls (auto rerank/moderation/meta, per-model enrichment,
                    // report title/icon) hold the coroutine `global` permit and
                    // then the OkHttp interceptor blocking-acquires the per-host
                    // permit — i.e. global→host. If a report agent took the host
                    // permit FIRST and then waited for global, that inverts the
                    // order and deadlocks (agent holds host, waits global; meta
                    // holds global, waits host) — observed freezing whole runs
                    // on the busiest hosts. acquireOrRequeue is the non-blocking
                    // gate (delay, not Thread.sleep); the interceptor skips its
                    // own acquire via permitPreAcquired.
                            val permitHold = acquireThrottledPermits(ApiCallCaps.report, providerHost(task.runtimeAgent.provider))
                            try {
                                withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                    executeReportTask(context, reportId, aiPrompt, overrideParams, task,
                                        imageBase64, imageMime, headless = headless)
                                }
                            } finally {
                                permitHold.dispose()
                            }

                    // Per-agent enrichment auto-fire — icon and/or title per
                    // the two toggles; launches independently (fire-and-forget),
                    // so awaitAll still tracks only the primary calls.
                    val g = appViewModel.uiState.value.generalSettings
                    if (g.perModelIconOn() || g.perModelTitleOn()) {
                        val ra = ReportStorage.getReport(context, reportId)
                            ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                        if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                            iconGen.runPerModelEnrichment(context, reportId, ra, aiPrompt, aiSettings,
                                g.perModelIconOn(), g.perModelTitleOn())
                        }
                    }
                }
            }.awaitAll()
        }
        }
    }

    /** Fire-and-forget: create + run ONE report fully in the background from
     *  an explicit [prompt] / [title] + the swarm [swarmId], on its OWN
     *  independent coroutine — NOT a [generationJobs] entry, so many
     *  can run at once and none cancels another, and it touches no live
     *  single-report UI state (no dialog, no _agentResults, no progress, no
     *  currentReportId). Returns immediately. Backs the Stress test, which
     *  submits every Example Prompt and finishes at once. */
    fun submitBackgroundReport(
        context: Context, prompt: String, title: String, swarmId: String,
        onReportCreated: ((String) -> Unit)? = null,
    ) {
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val selected = aiSettings.getSwarmById(swarmId)?.let { com.ai.model.expandSwarmToModels(it, aiSettings) }.orEmpty()
            val swarmMembers = selected.map { SwarmMember(it.provider, it.model) }
            val reportTasks = buildReportTasks(
                aiSettings, emptyList(), swarmMembers, emptyMap(), null,
                state.generalSettings, emptySet(), false, selected
            )
            if (reportTasks.isEmpty()) {
                AppLog.w("Report", "background report skipped — no active models for swarm $swarmId")
                return@launch
            }
            val runId = java.util.UUID.randomUUID().toString()
            preparePrimaryExecution(context, prompt, reportTasks, null, emptyList(), aiSettings, appViewModel.repository)
            val report = ReportStorage.createReportAsync(
                context = context, title = title.ifBlank { "AI Report" },
                prompt = reportPromptOverview(prompt, reportTasks), agents = reportTasks.map { it.reportAgent },
                config = CreateReportConfig(reportType = ReportType.CLASSIC, runId = runId)
            )
            val reportId = report.id
            // Register the job under the freshly-minted reportId so this live
            // background run counts as generating: the Broken-work scan then
            // stops flagging its in-flight agents as interrupted, and
            // deleteReport cancels it instead of letting its billed calls run
            // on against a deleted report. Registered here (not at launch)
            // because the reportId doesn't exist until createReportAsync.
            trackRegenerateJob(reportId, coroutineContext[Job]!!)
            onReportCreated?.invoke(reportId)
            val startMs = System.currentTimeMillis()
            withTracerTags(reportId = reportId, category = "report/prompt", runId = runId) {
                AppLog.i("Report", "→ start (bg) \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))")
                iconGen.kickOffLanguageGeneration(context, reportId, prompt, aiSettings)
                // Generate titles, then the icon, each from the original question.
                iconGen.kickOffReportTitleGeneration(context, reportId, prompt, aiSettings, thenIcon = true)
                runReportPrimaryCalls(
                    context, reportId, prompt, null, reportTasks,
                    aiSettings, null, null, headless = true
                )
                val finalReport = ReportStorage.getReport(context, reportId)
                val ok = finalReport?.agents?.count { it.reportStatus == ReportStatus.SUCCESS } ?: 0
                val fail = finalReport?.agents?.count { it.reportStatus == ReportStatus.ERROR } ?: 0
                AppLog.i("Report", "← end (bg) id=$reportId ok=$ok fail=$fail in ${System.currentTimeMillis() - startMs}ms")
                maybeAutoCreateSecondaries(context, reportId, aiSettings, ok)
                maybeAutoCreateDefaultMetas(context, reportId, aiSettings, ok)
            }
        }
    }

    /**
     * Tear down the current finished-report state and pre-fill the selection screen with
     * the prompt + model list from a saved report so the user can edit which models run
     * before re-generating.
     */
    suspend fun prepareEditModels(context: Context, reportId: String) {
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        val ai = appViewModel.uiState.value.aiSettings
        // Prefer an already-staged list (so the user comes back to whatever they were
        // editing) — but only this report's own staged list — fall back to
        // the report's persisted agent set.
        val rebuilt = appViewModel.uiState.value
            .takeIf { it.stagedChangesReportId == reportId }?.stagedReportModels.orEmpty()
            .ifEmpty { reportToModels(report, ai) }
        resetAgentResults(null)
        appViewModel.updateUiState { it.copy(
            showGenericReportsDialog = false,
            genericPromptTitle = report.title, genericPromptTitleLong = report.titleLong.orEmpty(),
            genericPromptText = report.prompt,
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null,
            pendingReportModels = rebuilt,
            editModeReportId = reportId
        ) }
    }

    /**
     * Save the user's edited model list as the staged set for a future Regenerate, then
     * restore the Result-phase state for `reportId` so the user lands back on the Report
     * Result screen. Called from the selection screen's "Update model list" button.
     */
    suspend fun stageModelListForRegenerate(context: Context, reportId: String, models: List<ReportModel>) {
        appViewModel.updateUiState { it.withStagedOwner(reportId).copy(
            stagedReportModels = models,
            pendingReportModels = emptyList(),
            editModeReportId = null
        ) }
        restoreCompletedReport(context, reportId)
    }

    /** Stamp [reportId] as the owner of the staged/pending regenerate
     *  state, dropping another report's leftovers first — staged changes
     *  must never travel to a different report via swipe navigation. */
    private fun UiState.withStagedOwner(reportId: String): UiState =
        if (stagedChangesReportId == null || stagedChangesReportId == reportId)
            copy(stagedChangesReportId = reportId)
        else copy(
            stagedChangesReportId = reportId,
            stagedReportModels = emptyList(),
            hasPendingPromptChange = false,
            hasPendingParametersChange = false
        )

    /** Edit / Parameters saved new values for the open report — raise the
     *  pending-changes banner on it (and only it). */
    fun markParametersChanged(context: Context) {
        val state = appViewModel.uiState.value
        val rid = state.currentReportId ?: return
        appViewModel.updateUiState { s ->
            s.withStagedOwner(rid).copy(hasPendingParametersChange = true)
        }
        val previousSave = configurationSaveJob
        configurationSaveJob = appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            previousSave?.join()
            val report = ReportStorage.getReport(context, rid) ?: return@launch
            val overlay = resolveReportOverrideParams(state.aiSettings, state.reportParametersIds,
                state.reportAdvancedParameters, report.webSearchTool, report.reasoningEffort, state.reportSystemPromptId)
            val executions = report.agents.mapNotNull { agent -> (agent.pendingExecutionConfig ?: agent.executionConfig)?.let { saved ->
                // Older reports did not retain their lower-level defaults; preserve the
                // captured configuration rather than guessing an unrelated group.
                val base = saved.baseParameters ?: saved.parameters
                agent.agentId to saved.copy(parameters = appViewModel.repository.mergeParameters(base, overlay),
                    baseParameters = base, capturedAt = System.currentTimeMillis())
            } }.toMap()
            ReportStorage.updateGenerationParameters(context, rid, state.reportParametersIds,
                state.reportAdvancedParameters, state.reportSystemPromptId, executions)
        }
    }

    /**
     * Title-bar 🔁 confirmed. Applies any staged Edit-Models list to the
     * report on disk FIRST, clears the pending-change banner flags, then
     * enqueues the Regenerate batch. The batch engine builds its task list
     * from `report.agents` on disk, so staged adds/removals must land there
     * before [RegenerateBatchEngine.enqueueAndStart] runs — this carries the
     * staged-list contract of the deleted one-shot regenerateReport over to
     * the batch path ("Update model list" used to be silently ignored).
     */
    fun regenerateReportBatch(context: Context, reportId: String, erroredOnly: Boolean = false) {
        appViewModel.viewModelScope.launch(reportLogContext()) {
            configurationSaveJob?.join()
            if (!erroredOnly) {
                // "Retry failed" must NOT consume staged Edit-Models changes —
                // it re-runs only what already errored; the staged list stays
                // banner-visible for a later full Regenerate.
                //
                // Cascade by impact (doc/manual.md "Edit / regenerate"): a
                // MODEL-LIST-ONLY edit makes Regenerate additive — only the
                // newly appended agents run; existing answers and every
                // secondary row are kept untouched. A prompt or parameters
                // change still re-runs everything.
                val pre = appViewModel.uiState.value
                val modelListOnly = pre.stagedChangesReportId == reportId &&
                    pre.stagedReportModels.isNotEmpty() &&
                    !pre.hasPendingPromptChange && !pre.hasPendingParametersChange
                val appliedNewIds = applyStagedModelList(context, reportId)
                appViewModel.updateUiState {
                    // Staged state belonging to a different report survives a
                    // plain regenerate here — it stays banner-visible on its
                    // own report and must not be silently discarded.
                    if (it.stagedChangesReportId != null && it.stagedChangesReportId != reportId) it
                    else it.copy(
                        stagedReportModels = emptyList(), pendingReportModels = emptyList(),
                        hasPendingPromptChange = false, hasPendingParametersChange = false,
                        stagedChangesReportId = null
                    )
                }
                if (modelListOnly) {
                    // Nothing runnable staged (already toasted) — don't fall
                    // through to a full re-run of every existing answer.
                    if (appliedNewIds == null) return@launch
                    regenerateBatchEngine.enqueueAndStart(
                        context, reportId, onlyAgentIds = appliedNewIds
                    )
                    return@launch
                }
            }
            regenerateBatchEngine.enqueueAndStart(context, reportId, erroredOnly)
        }
    }

    /** Merge the staged Edit-Models list into the report's on-disk agent
     *  set: append new rows as PENDING (replaying the report's captured
     *  config), remove dropped rows through the full orphan cascade.
     *  Returns the ids of the newly appended agents (empty set when the
     *  edit was removals-only), or null when no staged list was applied
     *  at all — caller uses this for the additive model-list-only
     *  regenerate. */
    private suspend fun applyStagedModelList(context: Context, reportId: String): Set<String>? {
        val state = appViewModel.uiState.value
        val staged = state.stagedReportModels
        // Owner gate: the staged list belongs to exactly one report. Applying
        // report X's list to report Y would delete every Y agent not in X's
        // set — the swipe-navigation data-loss bug.
        if (staged.isEmpty() || state.stagedChangesReportId != reportId) return null
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return null
        val ai = state.aiSettings
        val agentIds = staged.filter { it.type == "agent" }.mapNotNull { it.agentId }.toSet()
        // Kept swarm members from their explicit (provider, model) — never
        // re-expand swarm ids, which re-adds removed members.
        val swarmMembers = staged.filter { it.sourceType == "swarm" && it.type == "model" }.map { SwarmMember(it.provider, it.model) }
        val swarmMemberIds = swarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
        val directIds = staged.filter { it.sourceType == "model" }.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
        val agents = agentIds.mapNotNull { ai.getAgentById(it) }
        val directModels = (directIds - swarmMemberIds).mapNotNull { mid ->
            val parts = mid.removePrefix("swarm:").split(":", limit = 2)
            val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
            SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
        }
        // Replay the report's CAPTURED generation config for the new rows,
        // matching forceRegenerateAllAgents (NOT the live UiState).
        val directModelSids = directModels.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
        val preGenParamsActive = reportPreGenParamsActive(report)
        val tasks = buildReportTasks(
            ai, agents, swarmMembers + directModels, report.selectionParamsById,
            report.externalSystemPrompt,
            state.generalSettings, directModelSids, preGenParamsActive, staged
        )
        if (tasks.isEmpty()) {
            // Every staged entry was filtered out (orphaned agent ids /
            // unknown providers) — leaving removedIds = all agents would
            // wipe the report. Surface it instead of silently mutating.
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context,
                    "Edited model list had nothing to run — none of the picked agents or providers are configured on this device.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
            return null
        }
        val existingIds = report.agents.map { it.agentId }.toSet()
        val overlay = resolveReportOverrideParams(ai, report.parameterPresetIds, report.advancedParameters,
            report.webSearchTool, report.reasoningEffort, report.reportSystemPromptId)
        val newAgents = tasks.filter { it.resultId !in existingIds }.map { task ->
            task.reportAgent.copy(executionConfig = com.ai.data.ReportExecutionConfig(
                appViewModel.repository.mergeParameters(task.resolvedParams, overlay),
                ai.getEffectiveEndpointUrlForAgent(task.runtimeAgent), report.prompt,
                baseParameters = task.resolvedParams, refreshPrompt = true))
        }
        val removedIds = existingIds - tasks.map { it.resultId }.toSet()
        withContext(Dispatchers.IO) {
            for (id in removedIds) {
                val agent = report.agents.firstOrNull { it.agentId == id }
                // "Use report models" unifies the report's answer models with
                // the batch pools — route through the everywhere-cascade there,
                // mirroring removeAgentFromReport.
                if (report.workerConfig.useReportModels && agent != null) {
                    removeReportModelEverywhereInternal(context, reportId, agent.provider, agent.model)
                } else {
                    removeAgentInternal(context, reportId, id)
                }
            }
            if (newAgents.isNotEmpty()) ReportStorage.appendAgents(context, reportId, newAgents)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
        if (newAgents.isNotEmpty()) {
            // Sync the result-row driver set so the appended rows appear
            // (hourglass via empty _agentResults) on the open report.
            appViewModel.updateUiState { s ->
                if (s.currentReportId != reportId) s
                else s.copy(
                    genericReportsSelectedAgents = s.genericReportsSelectedAgents + newAgents.map { it.agentId },
                    genericReportsTotal = s.genericReportsTotal + newAgents.size
                )
            }
        }
        AuditLog.append(reportId, "Applied edited model list (+${newAgents.size} / -${removedIds.size} models)")
        return newAgents.map { it.agentId }.toSet()
    }

    /**
     * Update the saved report's prompt (and the matching UiState) without
     * triggering generation. Used by the Edit-prompt overlay — the user reviews the new
     * prompt on the result screen and re-runs via the Actions / Regenerate button when
     * they're ready. The model list and parameter set on disk are untouched.
     */
    suspend fun updateReportPrompt(context: Context, reportId: String, newPrompt: String) {
        withContext(Dispatchers.IO) {
            ReportStorage.updateReportPromptText(context, reportId, newPrompt)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
        // The pending-change flag belongs to [reportId] (staged owner); the
        // on-screen prompt text only while it's still the report shown — the
        // user may have switched reports during the disk write.
        appViewModel.updateUiState { s ->
            val staged = s.withStagedOwner(reportId).copy(hasPendingPromptChange = true)
            if (s.currentReportId == reportId) staged.copy(genericPromptText = newPrompt) else staged
        }
    }

    /**
     * Update the report's title in place. Title is metadata only — no
     * outbound API call references it — so this never sets
     * [com.ai.model.UiState.hasPendingPromptChange] and the user does
     * not need to regenerate to see the new title applied.
     */
    suspend fun updateReportTitle(context: Context, reportId: String, newTitle: String, newTitleLong: String) {
        withContext(Dispatchers.IO) {
            ReportStorage.updateReportTitle(context, reportId, newTitle, newTitleLong)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
        // Edit title now sets both: short drives list cards, long drives the
        // orange line (blank long → falls back to short via barTitle). Only
        // while [reportId] is still the report on screen.
        appViewModel.updateUiState { s ->
            if (s.currentReportId != reportId) s
            else s.copy(genericPromptTitle = newTitle, genericPromptTitleLong = newTitleLong)
        }
    }

    /** Manually set one agent's per-model title (Get-info → Edit model
     *  title). In-place text edit; bumps iconRefreshTick so the Get-info
     *  rows re-read. */
    suspend fun updateModelTitle(context: Context, reportId: String, agentId: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            ReportStorage.setReportAgentModelTitleText(context, reportId, agentId, newTitle)
        }
        appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
    }

    /** Apply a picked Find-alt report title (short/long) — persists the chosen
     *  text + the alternative model + an "_alt" provenance marker (not its
     *  cost; see [ReportStorage.setReportTitleAltChoice]) so the Get-info
     *  title card reflects the alternative API call. Auto-applies on pick,
     *  mirroring how alt icon picks apply. */
    fun applyAlternativeReportTitle(context: Context, reportId: String, long: Boolean, title: String, model: String) {
        appViewModel.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ReportStorage.setReportTitleAltChoice(context, reportId, long, title, model)
                ReportStorage.bumpReportTimestamp(context, reportId)
            }
            appViewModel.updateUiState {
                if (it.currentReportId != reportId) it
                else if (long) it.copy(genericPromptTitleLong = title) else it.copy(genericPromptTitle = title)
            }
        }
    }

    /** Per-model sibling of [applyAlternativeReportTitle]. */
    fun applyAlternativeModelTitle(context: Context, reportId: String, agentId: String, title: String, model: String) {
        appViewModel.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ReportStorage.setReportModelTitleAltChoice(context, reportId, agentId, title, model)
            }
            appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
        }
    }

    /** Manually overwrite a fan-out pair's title (the new pair-title editor
     *  on "Edit titles"). Persists straight to the SecondaryResult row;
     *  `manual` promptUsed distinguishes it from a Find-alt pick
     *  (`model_title_alt`). Bumps iconRefreshTick so the list / overview
     *  re-read from disk. */
    suspend fun updateFanOutPairTitle(context: Context, reportId: String, pairId: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            SecondaryResultStorage.setFanOutTitle(context, reportId, pairId, newTitle, promptUsed = "manual")
        }
        appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
    }

    /**
     * Regenerate only the report's **metadata** — the jobs shown on the
     * "Report - Get info" screen: report icon, language, title, and the
     * per-model icon / title for each completed agent. Re-runs the same
     * kick-offs the initial generation fires (each gated by its own
     * enabled flag), leaving the model responses and secondary results
     * untouched. Nothing is cleared first, so the ReportStorage cost
     * writers (which are additive) ADD this run's token cost on top of
     * the first run's — both runs count. Wired to the 🔄 on Get-info.
     */
    /** "Restart errors" on Report - Get info: re-fire ONLY the info jobs that
     *  ended in an error (red ❌), clearing each one's error first so its row
     *  flips from ❌ back to pending/running. Successful jobs are left alone. */
    fun restartReportInfoErrors(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(reportLogContext()) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ai = appViewModel.uiState.value.aiSettings
            val g = appViewModel.uiState.value.generalSettings
            withTracerTags(reportId = reportId, category = "Report info restart errors") {
                // Report-level rows: chain title and icon retries when both
                // errored; an icon-only error regenerates
                // just the icon from the original question.
                // Every row is gated on the same feature toggle the Broken-work
                // INFO badge (BrokenWorkPolicy.infoProblems) and the Get-info
                // rows use — a stamped error for a since-disabled feature must
                // not re-fire billed calls the UI no longer shows.
                val titleErr = g.reportTitleAiOn() && !report.titleErrorMessage.isNullOrBlank()
                val iconErr = g.reportIconOn() && !report.iconErrorMessage.isNullOrBlank()
                if (titleErr) {
                    ReportStorage.clearReportTitleError(context, reportId)
                    if (iconErr) ReportStorage.clearReportIcon(context, reportId)
                    iconGen.kickOffReportTitleGeneration(context, reportId, report.prompt, ai, thenIcon = iconErr)
                } else if (iconErr) {
                    ReportStorage.clearReportIcon(context, reportId)
                    iconGen.kickOffIconGeneration(context, reportId, report.prompt, ai)
                }
                if (g.reportLanguageOn() && !report.languageIconErrorMessage.isNullOrBlank()) {
                    ReportStorage.clearReportLanguage(context, reportId)
                    iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
                }
                // Per-model rows: re-run just the agents whose icon or model-title
                // errored (and only the side that failed), each side gated on its
                // feature toggle.
                report.agents.forEach { ra ->
                    val iconErr = g.perModelIconOn() && !ra.iconErrorMessage.isNullOrBlank()
                    val titleErr = g.perModelTitleOn() && !ra.modelTitleErrorMessage.isNullOrBlank()
                    if (!iconErr && !titleErr) return@forEach
                    if (ra.reportStatus != ReportStatus.SUCCESS || ra.responseBody.isNullOrBlank()) return@forEach
                    if (iconErr) ReportStorage.clearReportAgentIconState(context, reportId, ra.agentId)
                    if (titleErr) ReportStorage.clearReportAgentModelTitleError(context, reportId, ra.agentId)
                    iconGen.runPerModelEnrichment(
                        context, reportId, ra, report.prompt, ai,
                        iconOn = iconErr, titleOn = titleErr
                    )
                }
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            }
        }

    fun regenerateReportInfo(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportLogContext()) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ai = appViewModel.uiState.value.aiSettings
            val g = appViewModel.uiState.value.generalSettings
            withTracerTags(reportId = reportId, category = "Report info regenerate") {
                iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
                // Generate titles, then the icon, each from the original question.
                iconGen.kickOffReportTitleGeneration(context, reportId, report.prompt, ai, thenIcon = true)
                if (g.perModelIconOn() || g.perModelTitleOn()) {
                    report.agents
                        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        .forEach { ra ->
                            iconGen.runPerModelEnrichment(
                                context, reportId, ra, report.prompt, ai,
                                g.perModelIconOn(), g.perModelTitleOn()
                            )
                        }
                }
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            }
        }
    }

    /** Re-run ONE Report-info metadata item — the per-item 🔄 reload on the
     *  Get-info detail screens. Titles and the language icon are MetaCache-
     *  backed, so the relevant entry is evicted first to force a fresh call
     *  (clearing only the short / long / language-icon entry, not its sibling,
     *  so the untouched one keeps its cached value). */
    fun regenerateMetaItem(context: Context, reportId: String, kind: MetaRegenKind, agentId: String?) {
        appViewModel.viewModelScope.launch(reportLogContext()) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ai = appViewModel.uiState.value.aiSettings
            withTracerTags(reportId = reportId, category = "Report info regenerate") {
                when (kind) {
                    MetaRegenKind.REPORT_TITLE_SHORT -> {
                        // Variant computed from the effective prompt and metadata format — must
                        // mirror kickOffReportTitleGeneration exactly or the
                        // eviction misses the cache key and the regen no-ops.
                        val prompt = ai.internalPrompts.firstOrNull {
                            it.category == "workers" && it.name == "report-title-short"
                        }?.withReportInfoWorkers(report)
                        com.ai.data.MetaCache.remove(
                            "report/title-short",
                            report.prompt,
                            reportTitleCacheVariant(prompt, ai)
                        )
                        iconGen.kickOffReportTitleGeneration(context, reportId, report.prompt, ai, thenIcon = false)
                    }
                    MetaRegenKind.REPORT_TITLE_LONG -> {
                        val prompt = ai.internalPrompts.firstOrNull {
                            it.category == "workers" && it.name == "report-title-long"
                        }?.withReportInfoWorkers(report)
                        com.ai.data.MetaCache.remove(
                            "report/title-long",
                            report.prompt,
                            reportTitleCacheVariant(prompt, ai)
                        )
                        iconGen.kickOffReportTitleGeneration(context, reportId, report.prompt, ai, thenIcon = false)
                    }
                    MetaRegenKind.REPORT_ICON ->
                        iconGen.kickOffIconGeneration(context, reportId, report.prompt, ai)
                    MetaRegenKind.LANGUAGE_NAME ->
                        iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
                    MetaRegenKind.LANGUAGE_ICON -> {
                        val iconPrompt = ai.internalPrompts.firstOrNull {
                            it.category == "workers" && it.name == "report-language-icon"
                        }?.withReportInfoWorkers(report)
                        report.languageName?.takeIf { it.isNotBlank() }?.let {
                            com.ai.data.MetaCache.remove(
                                "language-icon",
                                it,
                                metaCacheVariantForInternalPrompt(iconPrompt, ai)
                            )
                        }
                        iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
                    }
                    MetaRegenKind.MODEL_TITLE -> {
                        val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@withTracerTags
                        iconGen.runPerModelEnrichment(context, reportId, ra, report.prompt, ai, iconOn = false, titleOn = true)
                    }
                    MetaRegenKind.MODEL_ICON -> {
                        val ra = report.agents.firstOrNull { it.agentId == agentId } ?: return@withTracerTags
                        iconGen.runPerModelEnrichment(context, reportId, ra, report.prompt, ai, iconOn = true, titleOn = false)
                    }
                    MetaRegenKind.PAIR_FAN_META -> {
                        // Cheap automatic reload for a fan-out pair's icon +
                        // title: clear this pair's fan-meta state (keeping
                        // cost) and let runFanMetaBatch re-dispatch the now-
                        // empty pair additively.
                        val pairId = agentId ?: return@withTracerTags
                        val row = withContext(Dispatchers.IO) {
                            SecondaryResultStorage.get(context, reportId, pairId)
                        } ?: return@withTracerTags
                        val promptId = row.metaPromptId ?: return@withTracerTags
                        withContext(Dispatchers.IO) {
                            SecondaryResultStorage.clearFanOutTitleStateKeepingCost(context, reportId, pairId)
                            SecondaryResultStorage.clearFanOutIconStateKeepingCost(context, reportId, pairId)
                        }
                        iconGen.runFanMetaBatch(context, reportId, promptId)
                    }
                }
                appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
            }
        }
    }

    /** Re-fire EVERY agent on [reportId] from scratch, regardless of any
     *  model-list / prompt / parameters diff. Each agent is reset to
     *  PENDING and re-dispatched via [executeReportTask]. Returns
     *  immediately — dispatch runs on viewModelScope. Used by
     *  [com.ai.viewmodel.RegenerateBatchEngine]'s AGENTS phase.
     *
     *  Skips the staged-edit-models merge and the secondary/translation
     *  cascade that the removed one-shot regenerateReport used to do
     *  inline — the engine handles cascading itself one phase at a time. */
    /** [onlyAgentIds] scopes the re-dispatch to a subset of the report's
     *  agents (the batch engine passes its phase task rows — the full set
     *  on a normal Regenerate, just the errored ones on "Retry failed").
     *  Null = every agent, the historical behavior. */
    fun forceRegenerateAllAgents(context: Context, reportId: String, onlyAgentIds: Set<String>? = null) {
        appViewModel.viewModelScope.launch(reportLogContext()) {
            trackRegenerateJob(reportId, coroutineContext[Job]!!)
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val state = appViewModel.uiState.value
            val ai = state.aiSettings
            val tasks = report.agents.filter { onlyAgentIds == null || it.agentId in onlyAgentIds }
                .mapNotNull { buildTemperatureSweepTask(report, state, it) }
            val plannedIds = tasks.map { it.resultId }.toSet()
            report.agents.filter { (onlyAgentIds == null || it.agentId in onlyAgentIds) && it.agentId !in plannedIds }.forEach {
                ReportStorage.updateAgentStatus(context,reportId,it.agentId,ReportStatus.ERROR,
                    com.ai.data.AgentStatusPatch(errorMessage="Saved provider ${it.provider} is unavailable; configure it before replay."))
            }
            if (tasks.isEmpty()) return@launch
            com.ai.data.ReportWorkLimits.checkSize(tasks.size)
            ReportKnowledge.prepare(context, reportId, appViewModel.repository, ai)
            // Reset every existing agent to PENDING so the row shows
            // ⏳ while the new dispatch is in flight. Use the
            // *KeepingCost variant so prior expenditure stays on
            // disk; the dispatcher's additive cost write adds the
            // new call's cost onto the prior.
            val existingIds = report.agents.map { it.agentId }.toSet()
            for (task in tasks) {
                if (task.resultId in existingIds) {
                    ReportStorage.resetAgentToPendingKeepingCost(context, reportId, task.resultId)
                }
            }
            val taskIds = tasks.map { it.resultId }.toSet()
            updateAgentResults(reportId) { existing -> existing.filterKeys { k -> k !in taskIds } }
            ReportStorage.bumpReportTimestamp(context, reportId)
            withTracerTags(reportId = reportId, category = "Batch regenerate agents") {
                // Same captured config as the task build above (presets +
                // advanced + the report's own web/reasoning flags) — shared
                // with regenerateReport so both paths replay identically.
                val overrideParams = resolveReportOverrideParams(
                    ai, report.parameterPresetIds, report.advancedParameters,
                    report.webSearchTool, report.reasoningEffort, report.reportSystemPromptId
                )
                interleaveByHost(tasks) { providerHost(it.runtimeAgent.provider) }.chunked(64).forEach { window ->
                coroutineScope {
                    window.map { task ->
                        async {
                            // Canonical order global → report → per-host (host
                            // gate INSIDE global) to avoid the global↔host
                            // deadlock vs the metadata/interceptor path.
                                    val permitHold = acquireThrottledPermits(ApiCallCaps.report, providerHost(task.runtimeAgent.provider))
                                    try {
                                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                            executeReportTask(
                                                context, reportId, report.prompt, overrideParams, task,
                                                report.imageBase64, report.imageMime,
                                                isRegeneration = true
                                            )
                                        }
                                    } finally {
                                        permitHold.dispose()
                                    }

                            // Per-agent enrichment auto-fire — same shape as regenerateReport.
                            val g = appViewModel.uiState.value.generalSettings
                            if (g.perModelIconOn() || g.perModelTitleOn()) {
                                val ra = ReportStorage.getReport(context, reportId)
                                    ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                                if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                                    iconGen.runPerModelEnrichment(context, reportId, ra, report.prompt, ai,
                                        g.perModelIconOn(), g.perModelTitleOn())
                                }
                            }
                        }
                    }.awaitAll()
                }
                }
            }
        }
    }

    private fun cancelReportOwnedWorkBeforeDelete(reportId: String, context: Context? = null): Boolean {
        val cleared = appViewModel.uiState.value.currentReportId == reportId
        // Title fan-outs (report/model/pair) + alt-translation jobs share
        // iconFanOutJobs under prefixed keys the plain-reportId remove below
        // never matched — needs the report loaded to map agent/pair ids, so
        // it's gated on a context (delete paths pass one).
        context?.let { iconGen.cancelTitleFanOutsForReport(it, reportId) }
        // Cancel every in-flight coroutine attached to this report
        // BEFORE deleting it from disk. Otherwise:
        //   - Fan-out pair coroutines (up to N×(N-1) of them) keep
        //     consuming the per-provider throttle + Dispatchers.IO
        //     threads, racing to write to a SecondaryResultStorage row
        //     that's already gone. With a 33-model fan-out that's
        //     >1000 orphan coroutines, enough to starve the dispatcher
        //     so the next Generate button press queues forever.
        //   - The "Find alternative icons" fan-out has the same shape
        //     and gets the same treatment.
        //   - The report's own primary generation job ([generationJobs])
        //     is the agent fan-out for the initial generation; if the user
        //     trashes mid-generation it needs to die too. Keyed per report,
        //     so deleting a different (even the viewed) report can't cancel
        //     another report's run and strand its agents as "Stopped".
        generationJobs[reportId]?.cancel()
        val fanOutPrefix = "$reportId|"
        // Fan-out runs + per-pair coroutines are owned by the engine now.
        fanOutEngine.cancelAllForReport(reportId)
        // Tournament runs + per-match coroutines likewise.
        tournamentEngine.cancelAllForReport(reportId)
        // Judge-the-judges runs + per-cell coroutines likewise.
        judgeEvalEngine.cancelAllForReport(reportId)
        // Compare-with-meta runs + per-cell coroutines likewise.
        compareEngine.cancelAllForReport(reportId)
        // Rank-the-translators runs + per-cell coroutines likewise — the one
        // batch engine missing from this teardown, so a delete mid-rank left
        // its judge cells firing billed calls against the gone report and
        // its run entry lingering in _runs for the session.
        translatorRankEngine.cancelAllForReport(reportId)
        // Plain-meta edit sweeps / replays (MetaDetailScreen ✏️) likewise.
        metaEditManager.cancelAllForReport(reportId)
        // "Switch model / agent" preview candidates for any secondary kind.
        secondaryModelSwitch.cancelAllForReport(reportId)
        agentModelSwitch.cancelAllForReport(reportId)
        // Translation runs + the regenerate-batch orchestrator are also
        // report-owned and were NOT cancelled here — a translation
        // completing after the delete writes via SecondaryResultStorage
        // and would recreate the just-deleted report's storage dir (a
        // zombie report), and a regenerate batch would keep dispatching
        // agent calls against a gone report.
        translation.cancelAllForReport(reportId)
        temperatureSweep.cancelByPrefix(fanOutPrefix)
        reasoningEffortSweep.cancelByPrefix(fanOutPrefix)
        webSearchReplay.cancelByPrefix(fanOutPrefix)
        promptEditReplay.cancelByPrefix(fanOutPrefix)
        // Synchronous: the async cancel() returns before its launch body
        // cancels the orchestrator, so the batch could still be dispatching
        // when we delete below.
        regenerateBatchEngine.cancelJobNow(reportId)
        // Single-agent + force-all regenerate jobs — untracked before, so a
        // completion landing after the delete could recreate the storage dir.
        regenerateJobs.remove(reportId)?.forEach { it.cancel() }
        iconFanOutJobs.remove(reportId)?.cancel()
        languageIconFanOutJobs.remove(reportId)?.cancel()
        appViewModel.clearLanguageIconFanOut(reportId)
        // Per-agent alt-icon jobs also live under the same reportId
        // prefix — collect and cancel them by agentId so their
        // candidate maps clear too. Same prefix key as the fan-out
        // pair jobs, scoped by a different ConcurrentHashMap.
        agentIconFanOutJobs.entries
            .filter { it.key.startsWith(fanOutPrefix) }
            .forEach { entry ->
                entry.value.cancel()
                // key format is "$reportId|$agentId"; split once and
                // drop the per-agent candidate map slot too.
                val agentId = entry.key.removePrefix(fanOutPrefix)
                appViewModel.clearAgentIconFanOut(reportId, agentId)
            }
        // Same shape as agentIconFanOutJobs above but keyed by
        // pair (SecondaryResult) id under the report.
        iconGen.pairIconFanOutJobs.entries
            .filter { it.key.startsWith(fanOutPrefix) }
            .forEach { entry ->
                entry.value.cancel()
                val pairId = entry.key.removePrefix(fanOutPrefix)
                appViewModel.clearPairIconFanOut(pairId)
            }
        appViewModel.clearIconFanOut(reportId)
        appViewModel.clearAgentFanOutsForReport(reportId)
        if (cleared) dismissGenericReportsDialog()
        return cleared
    }

    /** Delete a report file and, if it's the one currently shown, dismiss the screen state. */
    fun deleteReport(context: Context, reportId: String) {
        cancelReportOwnedWorkBeforeDelete(reportId, context)
        // The disk delete (report file + per-report secondary dir) off the
        // main thread; the cancellations above are non-blocking and must
        // run synchronously first so nothing is still writing as we delete.
        appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            ReportStorage.deleteReport(context, reportId)
        }
    }

    fun bulkDeleteReports(
        context: Context,
        reportIds: List<String>,
        onProgress: ((deleted: Int, total: Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ): Job {
        val ids = reportIds.distinct().filter { it.isNotBlank() }
        ids.forEach { cancelReportOwnedWorkBeforeDelete(it, context) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            ids.forEachIndexed { index, reportId ->
                ReportStorage.deleteReport(context, reportId)
                if (onProgress != null) {
                    withContext(Dispatchers.Main) { onProgress(index + 1, ids.size) }
                }
            }
            if (onComplete != null) withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /** Toggle the persisted pinned flag for [reportId]. Pinned reports
     *  surface as their own section on the AI Reports hub. */
    fun toggleReportPinned(context: Context, reportId: String, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            val r = ReportStorage.getReport(context, reportId) ?: return@launch
            ReportStorage.setReportPinned(context, reportId, !r.pinned)
        }
    }

    /** Duplicate [reportId] (new id, " (Copy)" title suffix, every agent
     *  result preserved) and open the copy on the result screen so the
     *  user lands on the duplicate ready to edit / regenerate without
     *  losing the original. Returns false (silently) when the source
     *  report can't be loaded. */
    fun copyReport(context: Context, reportId: String, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            val newId = withContext(Dispatchers.IO) { ReportStorage.copyReport(context, reportId) } ?: return@launch
            restoreCompletedReport(context, newId)
        }
    }

    fun clearPendingReportModels() {
        val cur = appViewModel.uiState.value
        if (cur.pendingReportModels.isEmpty()) return
        appViewModel.updateUiState { it.copy(pendingReportModels = emptyList()) }
    }

    /**
     * Open a previously generated report on the Reports result screen. Pulls the report
     * back out of ReportStorage, rebuilds _agentResults, and seeds the UiState fields the
     * Reports screen reads (currentReportId, genericReports* counters, prompt/title) so
     * the screen renders as if the report had just finished — agents listed, View / Share
     * / Browser / Email / Trace action row, etc.
     */
    suspend fun restoreCompletedReport(context: Context, reportId: String) {
        val seq = restoreSeq.incrementAndGet()
        configurationSaveJob?.join()
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        // A newer switch was requested while this one read the disk (fast
        // swipes launch one restore each, unordered): only the latest may
        // apply, or a slow older read lands last and yanks the screen back.
        if (restoreSeq.get() != seq) return
        // Only FINISHED agents get an entry (see terminalAgentResults) — a
        // report opened mid-run keeps the spinner on its unfinished rows.
        // The map is handed to this report BEFORE the currentReportId flip:
        // from here on, a late completion from the previously open report
        // is rejected by updateAgentResults' owner check instead of landing
        // on this report's row for the same model.
        resetAgentResults(report.id, terminalAgentResults(report))
        appViewModel.updateUiState { it.copy(
            currentReportId = report.id,
            genericReportsTotal = report.agents.size,
            // Progress = finished agents only, so a still-generating report
            // opened mid-run shows the real X/Y, not a premature 100%.
            genericReportsProgress = report.agents.count { ra -> ra.isTerminal() },
            genericReportsSelectedAgents = report.agents.map { ra -> ra.agentId }.toSet(),
            genericPromptTitle = report.title,
            genericPromptTitleLong = report.titleLong.orEmpty(),
            genericPromptText = report.prompt,
            reportParametersIds = report.parameterPresetIds,
            reportAdvancedParameters = report.advancedParameters,
            reportSystemPromptId = report.reportSystemPromptId,
            showGenericReportsDialog = true
        ) }
        // Close the open-mid-run race: a task that landed between the
        // disk read above and the hand-off skipped both its result publish
        // and its progress bump (the owner was still the previous report).
        // Re-read and merge monotonically — entries already in memory win,
        // and tasks landing after the hand-off publish themselves, so the
        // two passes together cover every interleaving without leaving a
        // row stuck on ⏳ or progress stuck one short of total.
        val report2 = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        val rebuilt2 = terminalAgentResults(report2)
        updateAgentResults(reportId) { rebuilt2 + it }
        val terminal2 = report2.agents.count { it.isTerminal() }
        appViewModel.updateUiState { s ->
            if (s.currentReportId != reportId) s
            else s.copy(genericReportsProgress = maxOf(s.genericReportsProgress, terminal2))
        }
    }

    /**
     * Rebuild _agentResults from a persisted ReportStorage entry. Called when the screen
     * comes back to a finished report whose in-memory results were lost (e.g. after Activity
     * recreation or process death) — UiState still has currentReportId and the
     * genericReports* counters, but our StateFlow restarted empty.
     */
    suspend fun hydrateAgentResultsFromStorage(context: Context, reportId: String) {
        // Merge with the in-memory map instead of skipping when ANY
        // agents are already populated. The previous early-return
        // left a half-finished restore on the screen — rows that
        // weren't in _agentResults stayed missing until a manual
        // refresh.
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        // Only finished agents — PENDING / RUNNING rows keep the hourglass.
        val rebuilt = terminalAgentResults(report)
        _agentResults.update { cur ->
            when {
                // Merge: prefer in-memory entries over disk so a fresh
                // success that hasn't been written yet isn't overwritten
                // by a stale RUNNING agent's still-on-disk state. Rows
                // missing from memory get the rebuilt entry.
                cur.reportId == reportId ->
                    if (rebuilt.isEmpty()) cur else cur.copy(results = rebuilt + cur.results)
                // The map restarted without this report as its owner
                // (Activity recreation) — adopt it, but only while this
                // report is still the one on screen. Never merge into
                // another report's map: its entries share our agent ids.
                appViewModel.uiState.value.currentReportId == reportId ->
                    ReportAgentResults(reportId, rebuilt)
                else -> cur
            }
        }
    }

    fun dismissGenericReportsDialog() {
        // The report job's withTracerTags block restores tags on its
        // own when the job ends or is cancelled — no manual clear here.
        resetAgentResults(null)
        appViewModel.updateUiState { it.copy(
            showGenericReportsDialog = false, genericPromptTitle = "", genericPromptTitleLong = "", genericPromptText = "",
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null, reportAdvancedParameters = null,
            reportParametersIds = emptyList(),
            reportSystemPromptId = null,
            stagedReportModels = emptyList(), editModeReportId = null,
            pendingReportModels = emptyList(),
            attachedKnowledgeBaseIds = emptyList(),
            hasPendingPromptChange = false, hasPendingParametersChange = false,
            stagedChangesReportId = null
        ) }
    }

    fun continueReportInBackground() {
        appViewModel.uiState.value.currentReportId?.let { backgroundGenerations += it }
        appViewModel.updateUiState { it.copy(showGenericReportsDialog = false) }
    }

    /** True while [reportId]'s primary generation is actively running in
     *  this process — gates the Stop button on the progress bar. */
    fun isGenerationActive(reportId: String): Boolean =
        generationJobs[reportId]?.isActive == true

    /** Stop-and-keep for the in-flight primary generation: cancels the
     *  shared generation job (its finally terminalizes still-PENDING/
     *  RUNNING rows as STOPPED on disk, NonCancellable), then re-hydrates
     *  the screen from disk so the report settles as complete with every
     *  already-answered model kept. Guarded to the actively generating
     *  report so a Stop tapped on a stale screen can never kill a
     *  different report's run. Stopped rows read as failed afterwards, so
     *  the Regenerate dialog's "Retry failed" resumes exactly what the
     *  Stop cut off. */
    fun stopGeneration(context: Context, reportId: String) {
        val job = generationJobs[reportId] ?: return
        appViewModel.viewModelScope.launch(reportLogContext()) {
            // Join so the finally's NonCancellable STOPPED writes land
            // before the re-hydration below reads the rows back.
            job.cancelAndJoin()
            // Only re-open it if it's still the report on screen — the user
            // may have moved on during the join.
            if (appViewModel.uiState.value.currentReportId == reportId) restoreCompletedReport(context, reportId)
            AuditLog.append(reportId, "Generation stopped by user — completed answers kept")
        }
    }


    /** Re-run the API call for a single agent on a finished report,
     *  replacing its persisted result. Mirrors the flow [generateGenericReports]
     *  uses for a fresh run (rebuild ReportTask → executeReportTask) but
     *  scoped to one agent so the rest of the report's results stay
     *  intact. The in-memory _agentResults entry is cleared first so the
     *  Report row reverts to ⏳ while the call is in flight, then
     *  populated again when the new response lands.
     *
     *  Handles both row types: real-Agent ids (UUID, looked up in
     *  aiSettings.agents) and "swarm:provider:model" ids (rebuilt
     *  on-the-fly from the parsed parts). When a real-agent row points
     *  at an agent that's since been deleted, falls back to the direct
     *  shape so the regenerate still goes through. */
    fun regenerateAgent(context: Context, reportId: String, agentId: String): Job {
        // viewModelScope: same survival rationale as
        // generateGenericReports — a screen-scoped scope here would
        // turn the in-flight call into ERROR on disk if the user
        // navigates away before the new response lands. Returns the Job so
        // the Broken-work recovery can join one agent's regenerate.
        return appViewModel.viewModelScope.launch(reportLogContext()) {
            trackRegenerateJob(reportId, coroutineContext[Job]!!)
            // Also register per-agent so removeAgentInternal can cancel THIS
            // call specifically when its agent is removed mid-flight.
            // put()?.cancel() (not a plain put): a second regenerate of the
            // same agent while the first is still in flight otherwise
            // replaced the map entry without cancelling the first job —
            // two billed calls on one row, additive cost, and remove could
            // then cancel only the second, leaving the first as an
            // uncancellable billed orphan.
            val agentJobKey = regenerateAgentKey(reportId, agentId)
            regenerateAgentJobs.put(agentJobKey, coroutineContext[Job]!!)?.cancel()
            coroutineContext[Job]!!.invokeOnCompletion {
                regenerateAgentJobs.remove(agentJobKey, coroutineContext[Job])
            }
            withTracerTags(reportId = reportId, category = "Report regenerate agent") {
            val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
            val ra = report.agents.find { it.agentId == agentId } ?: return@withTracerTags
            val provider = AppService.findById(ra.provider) ?: return@withTracerTags
            AuditLog.append(reportId, "Regenerating report model ${ra.provider}/${ra.model}")
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings

            // Rebuild the task through the same captured-config path the
            // four sweep/replay flows use (report-level system prompt,
            // per-model selection params, preset/advanced fallbacks) —
            // NOT the live Settings defaults. The previous inline rebuild
            // dispatched swarm/direct rows with bare AgentParameters()
            // and saved agents without the report's system prompt, so
            // "Call model API again" and Broken-work restarts silently
            // answered under a different config than their siblings.
            val task = buildTemperatureSweepTask(report, state, ra) ?: return@withTracerTags

            // Drop the old result so the report row reverts to ⏳ until
            // executeReportTask publishes the new one.
            updateAgentResults(reportId) { it - agentId }
            // Reset the *persisted* row too. The full regenerateReport
            // path calls this for every agent; the single-agent path
            // only cleared the in-memory entry, so a failed re-run left
            // the old content / error on disk and a successful one
            // accumulated onto the stale additive split costs. Reset to
            // PENDING and clear cost / trace before dispatch so the row
            // reflects exactly this fresh attempt.
            ReportStorage.resetAgentToPending(context, reportId, agentId)
            // Rebuild the request from the model's CURRENT capabilities
            // rather than blindly replaying the original report's flags.
            // The user may have toggled vision / web-search / reasoning
            // overrides since the report was generated, or the model's
            // /models response may have been refreshed with a different
            // capability set; either way "Call model API again" should
            // produce a request that fits today's view of the model.
            //
            // Negatives only — we never invent flags the report didn't
            // originally carry. Dropping unsupported features avoids
            // 400s like "model X does not support reasoning_effort"
            // even though the dispatcher's static gate would also
            // strip them; pre-stripping keeps cost / token estimates
            // and the per-call trace clean of speculative parameters.
            val effectiveModel = task.runtimeAgent.model
            // acceptsReasoningEffortParam (not isReasoningCapable): an
            // always-on reasoning model like grok-4.3 reasons but rejects
            // the reasoning_effort parameter — keeping the badge on while
            // still stripping the parameter from the request is the right
            // mirror of what a fresh report would send.
            val canReason = aiSettings.acceptsReasoningEffortParam(provider, effectiveModel)
            val canWeb = aiSettings.isWebSearchCapable(provider, effectiveModel)
            val canVision = aiSettings.isVisionCapable(provider, effectiveModel)
            // The report's CAPTURED preset/advanced params — reading the
            // live state.reportAdvancedParameters here meant the override
            // was null after reopening a report (dismiss clears it), or
            // worse, another report's pre-gen tweak. Web/reasoning are
            // folded in below through the capability gates.
            val baseOverride = resolveReportOverrideParams(
                aiSettings, report.parameterPresetIds, report.advancedParameters,
                webSearchTool = false, reasoningEffort = null, reportSystemPromptId = report.reportSystemPromptId
            )
            // The "off" branches must always materialise an override so
            // the dispatcher receives an explicit webSearchTool=false /
            // reasoningEffort=null and won't fall back to the agent's
            // default (which may have one or both flags on). The
            // previous fallback `baseOverride?.copy(...) ?: baseOverride`
            // returned null when baseOverride was already null, leaving
            // the dispatcher to use the agent's default and the strip
            // to silently no-op.
            val withWeb = if (report.webSearchTool && canWeb) {
                (baseOverride ?: AgentParameters()).copy(webSearchTool = true)
            } else (baseOverride ?: AgentParameters()).copy(webSearchTool = false)
            val overrideParams = if (report.reasoningEffort != null && canReason) {
                withWeb.copy(reasoningEffort = report.reasoningEffort)
            } else withWeb.copy(reasoningEffort = null)
            val effectiveImage = if (canVision) report.imageBase64 else null
            val effectiveImageMime = if (canVision) report.imageMime else null
            // Bump the parent report's timestamp so it sorts to the top
            // of the History list — re-running an agent is a real
            // update, not a passive read. Mirrors what the meta-run /
            // translate flows already do.
            ReportStorage.bumpReportTimestamp(context, reportId)
            executeReportTask(
                context, reportId, report.prompt, overrideParams, task,
                effectiveImage, effectiveImageMime, isRegeneration = true
            )

            // The per-model title + icon are derived from THIS agent's
            // response, so the fresh response invalidates them. Re-fire the
            // per-model enrichment exactly as the initial generation does in
            // runReportPrimaryCalls — a bare regenerateAgent calls
            // executeReportTask directly and would otherwise skip it, leaving
            // the "Report - Get info" model-title / model-icon rows spinning
            // on the hourglass forever (a SUCCESS agent with no title/icon
            // reads as RUNNING, but nothing was ever launched). Gated by the
            // same two toggles that decide whether those rows exist at all.
            val gen = appViewModel.uiState.value.generalSettings
            val iconOn = gen.perModelIconOn()
            val titleOn = gen.perModelTitleOn()
            if (iconOn || titleOn) {
                val freshRa = ReportStorage.getReport(context, reportId)
                    ?.agents?.firstOrNull { it.agentId == agentId }
                if (freshRa?.reportStatus == ReportStatus.SUCCESS && !freshRa.responseBody.isNullOrBlank()) {
                    // Wipe the now-stale per-model enrichment (icon + any
                    // prior title error) so the new response's title/icon
                    // regenerate cleanly and a previous ❌ is retried.
                    if (iconOn) ReportStorage.clearReportAgentIconState(context, reportId, agentId)
                    if (titleOn) ReportStorage.clearReportAgentModelTitleError(context, reportId, agentId)
                    iconGen.runPerModelEnrichment(
                        context, reportId, freshRa, report.prompt, aiSettings, iconOn, titleOn
                    )
                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                }
            }
            }
        }
    }

    /** Remove a single agent from a report (storage + in-memory results
     *  flow + the genericReportsSelectedAgents set the UI iterates). The
     *  Report screen's row click leads to a single-result viewer with a
     *  "Remove model from report" button — that's this. */
    fun removeAgentFromReport(context: Context, reportId: String, agentId: String): Job {
        // Storage read-modify-write + per-orphan deletes off the main
        // thread — this is fired from a UI click and was blocking it.
        // Returns the Job so Broken-work recovery can join the removal.
        return appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            // "Use report models" unifies the report's answer models with the
            // batch pools — removing one model must remove it from the report
            // AND every batch. Delegate to the unified path in that mode.
            val report = ReportStorage.getReport(context, reportId)
            val agent = report?.agents?.firstOrNull { it.agentId == agentId }
            if (report?.workerConfig?.useReportModels == true && agent != null) {
                removeReportModelEverywhereInternal(context, reportId, agent.provider, agent.model)
            } else {
                removeAgentInternal(context, reportId, agentId)
            }
        }
    }

    /** Remove ONE agent from a report (storage + in-memory results flow + the
     *  genericReportsSelectedAgents set). Does not touch batches — callers that
     *  need the batch cascade go through [removeReportModelEverywhereInternal].*/
    internal suspend fun removeAgentInternal(context: Context, reportId: String, agentId: String) {
        // Cancel any in-flight per-agent work BEFORE the storage delete below,
        // so a zombie write can't land for an agent that's about to be gone
        // (and its later spend isn't silently dropped from cost accounting).
        temperatureSweep.cancel(TemperatureSweepState.key(reportId, agentId))
        reasoningEffortSweep.cancel(ReasoningEffortSweepState.key(reportId, agentId))
        webSearchReplay.cancel(WebSearchReplayState.key(reportId, agentId))
        promptEditReplay.cancel(PromptEditReplayState.key(reportId, agentId))
        agentIconFanOutJobs.remove(agentIconJobKey(reportId, agentId))?.cancel()
        // The sixth job family: a single-agent "Call model API again" in
        // flight for this agent. f4682ae61 covered the other five; without
        // this the regenerate ran to a billed completion whose write no-ops
        // onto the now-gone agent.
        regenerateAgentJobs.remove(regenerateAgentKey(reportId, agentId))?.cancel()
        run {
            val removedStatus = ReportStorage.getReport(context, reportId)
                ?.agents
                ?.firstOrNull { it.agentId == agentId }
                ?.reportStatus
            val removedWasFinished = removedStatus == ReportStatus.SUCCESS ||
                removedStatus == ReportStatus.ERROR ||
                removedStatus == ReportStatus.STOPPED
            // Guard the counter update on the agent actually existing — a
            // double-tap on "Remove model" would otherwise decrement the
            // total twice for one agent.
            val actuallyRemoved = ReportStorage.removeAgent(context, reportId, agentId)
            // Cascade: every TRANSLATE row whose translateSourceKind =
            // "AGENT" and translateSourceTargetId == this agent's id is
            // now an orphan. Drop them so the on-disk state matches the
            // META cascade in deleteSecondaryResult. Their cost rolls
            // into costsFromDeletedItems so the cost view continues to
            // reflect the real API spend.
            val orphans = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter { it.translateSourceKind == "AGENT" && it.translateSourceTargetId == agentId }
            if (orphans.isNotEmpty()) {
                var costDelta = 0.0
                orphans.forEach { tr ->
                    costDelta += (tr.inputCost ?: 0.0) + (tr.outputCost ?: 0.0)
                    SecondaryResultStorage.delete(context, reportId, tr.id)
                }
                ReportStorage.removeIconCallsForSecondaryIds(context, reportId, orphans.map { it.id }.toSet())
                if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            }
            ReportStorage.bumpReportTimestamp(context, reportId)
            updateAgentResults(reportId) { it - agentId }
            if (actuallyRemoved) {
                appViewModel.updateUiState { state ->
                    if (state.currentReportId != reportId) {
                        state
                    } else {
                        val newTotal = (state.genericReportsTotal - 1).coerceAtLeast(0)
                        val newProgress = if (removedWasFinished) {
                            (state.genericReportsProgress - 1).coerceAtLeast(0)
                        } else {
                            state.genericReportsProgress.coerceAtMost(newTotal)
                        }
                        state.copy(
                            genericReportsSelectedAgents = state.genericReportsSelectedAgents - agentId,
                            genericReportsTotal = newTotal,
                            genericReportsProgress = newProgress
                        )
                    }
                }
            }
        }
    }

    /** Public entry for the batch UIs: under "Use report models", remove a
     *  report model (provider, model) from the report's agents AND from every
     *  batch. Returns the Job so callers can join. */
    fun removeReportModelEverywhere(context: Context, reportId: String, providerId: String, model: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO + com.ai.data.CrashReporter.coroutineHandler) {
            removeReportModelEverywhereInternal(context, reportId, providerId, model)
        }

    /** Remove report model (provider, model) from the report's agents and then
     *  prune it from every batch run of the report — FanOut pairs (answerer or
     *  source), Judge cells (judge or either response), Tournament matches
     *  (either competitor), Compare cells (scored agent) and TranslatorRank
     *  score cells (translator). Idempotent. The GLOBAL judge swarm is left
     *  untouched: it's shared across reports and isn't consulted while
     *  REPORT_MODELS drives the batch pool from the report's own agents. */
    internal suspend fun removeReportModelEverywhereInternal(
        context: Context, reportId: String, providerId: String, model: String
    ) {
        val report = ReportStorage.getReport(context, reportId) ?: return
        val agentIds = report.agents
            .filter { it.provider.equals(providerId, ignoreCase = true) && it.model == model }
            .map { it.agentId }
            .toSet()
        // Report agents first (storage + TRANSLATE orphan cascade + uiState).
        agentIds.forEach { removeAgentInternal(context, reportId, it) }
        // Each engine only prunes runs already present in its in-memory
        // _runs map — hydrate from disk first so a batch the user hasn't
        // opened this session (and is therefore still empty in-memory) isn't
        // silently skipped by the sweep below.
        fanOutEngine.hydrate(context, reportId)
        judgeEvalEngine.hydrate(context, reportId)
        tournamentEngine.hydrate(context, reportId)
        compareEngine.hydrate(context, reportId)
        translatorRankEngine.hydrate(context, reportId)
        // Then every batch (each call is idempotent / a no-op when absent).
        // agentIds were computed above, before removeAgentInternal deleted
        // them — FanOut needs them passed in since it can no longer find
        // them by re-reading the (now agent-less) report from disk.
        fanOutEngine.deleteModelFromReport(context, reportId, providerId, model, agentIds)
        judgeEvalEngine.removeModelFromReport(context, reportId, providerId, model, agentIds)
        tournamentEngine.removeModelFromReport(context, reportId, providerId, model, agentIds)
        compareEngine.removeModelFromReport(context, reportId, providerId, model, agentIds)
        translatorRankEngine.removeModelFromReport(context, reportId, providerId, model, agentIds)
    }

    /** Generate (or regenerate) the AI title for one user note. Called from
     *  the note editor on every save (add/edit) via [com.ai.ui.shared.
     *  LocalGenerateNoteTitle]. Delegates to the worker-title flow. */
    fun generateUserNoteTitle(context: Context, reportId: String, noteId: String, noteText: String) {
        iconGen.kickOffUserNoteTitle(context, reportId, noteId, noteText, appViewModel.uiState.value.aiSettings)
    }

}
