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
        val traceFile: String?
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
        val traceFile: String?
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
        val traceFile: String?
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
        val traceFile: String?
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

/**
 * ViewModel for AI report generation: task building, concurrent execution, cost calculation.
 * Delegates to AppViewModel for shared state and settings.
 */
class ReportViewModel(private val appViewModel: AppViewModel) {

    private var reportGenerationJob: Job? = null
    @Volatile private var reportRunningInBackground = false
    private val temperatureSweepJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val _temperatureSweepStates = MutableStateFlow<Map<String, TemperatureSweepState>>(emptyMap())
    val temperatureSweepStates: StateFlow<Map<String, TemperatureSweepState>> = _temperatureSweepStates.asStateFlow()
    private val reasoningEffortSweepJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val _reasoningEffortSweepStates = MutableStateFlow<Map<String, ReasoningEffortSweepState>>(emptyMap())
    val reasoningEffortSweepStates: StateFlow<Map<String, ReasoningEffortSweepState>> = _reasoningEffortSweepStates.asStateFlow()
    private val webSearchReplayJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val _webSearchReplayStates = MutableStateFlow<Map<String, WebSearchReplayState>>(emptyMap())
    val webSearchReplayStates: StateFlow<Map<String, WebSearchReplayState>> = _webSearchReplayStates.asStateFlow()
    private val promptEditReplayJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val _promptEditReplayStates = MutableStateFlow<Map<String, PromptEditReplayState>>(emptyMap())
    val promptEditReplayStates: StateFlow<Map<String, PromptEditReplayState>> = _promptEditReplayStates.asStateFlow()

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

    /** Tracks in-flight fan-meta batches keyed by
     *  (reportId, metaPromptId). Separate map from [fanOutJobs] so
     *  a launched fan-meta batch on the same fan-out doesn't get
     *  cancelled by deleteFanOutModel etc. */
    internal val fanMetaJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    internal fun fanMetaJobKey(reportId: String, metaPromptId: String) = "$reportId|meta|$metaPromptId"
    internal fun registerFanMetaJob(reportId: String, metaPromptId: String, job: Job) {
        val key = fanMetaJobKey(reportId, metaPromptId)
        fanMetaJobs[key] = job
        job.invokeOnCompletion { fanMetaJobs.remove(key, job) }
    }

    /** Coroutine context for a report-section launch: `Dispatchers.IO`
     *  plus an [AppLog.currentLogId] context element so every [AppLog]
     *  line written by the coroutine (and its children) is tagged
     *  ` [#<logId>]` — letting the App Log Viewer isolate one report's
     *  activity. Drop-in for `Dispatchers.IO` at report-section
     *  `viewModelScope.launch` sites; `return@launch` stays valid
     *  because the `launch` call itself is unchanged. */
    internal fun reportLogContext(logId: String?) =
        Dispatchers.IO + AppLog.currentLogId.asContextElement(logId)

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

    // Separate flow from UiState so per-task completions don't force the UiState equality
    // checker to re-compare every other field. UI subscribers observe this independently.
    internal val _agentResults = MutableStateFlow<Map<String, AnalysisResponse>>(emptyMap())
    val agentResults: StateFlow<Map<String, AnalysisResponse>> = _agentResults.asStateFlow()

    /** Authoritative Fan Out runtime state. Phase C of the fan-out
     *  redesign creates this engine alongside the existing
     *  fan-out paths in this ViewModel; Phase E wires the UI to it
     *  and removes the duplicate paths. Phase F deletes the
     *  legacy `runningFanOutPairs`, `fanOutPairJobs`, and the 500 ms
     *  polling loop. */
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

    /** The "Change response"-style edit flows (regenerate / prompt-edit / chat /
     *  temperature / reasoning / web-search) for a plain META secondary result,
     *  surfaced by the dedicated Meta detail screen. See [MetaEditManager]. */
    val metaEditManager: MetaEditManager = MetaEditManager(appViewModel, this)

    /** Per-report orchestrator for the "Regenerate report" batch
     *  job. Replaces the legacy one-shot [regenerateReport] call —
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
    /** Round-robin + 429-fallback runner for "workers"-category prompts.
     *  Reusable engine; no batch is converted onto it yet. */
    val workerRunner = WorkerRunner(appViewModel)

    private data class ReportTask(
        val resultId: String,
        val reportAgent: ReportAgent,
        val runtimeAgent: Agent,
        val resolvedParams: AgentParameters
    )

    fun showGenericAgentSelection(
        title: String, prompt: String,
        imageBase64: String? = null, imageMime: String? = null,
        webSearchTool: Boolean = false,
        reasoningEffort: String? = null
    ) {
        _agentResults.value = emptyMap()
        appViewModel.updateUiState { it.copy(
            genericPromptTitle = title, genericPromptTitleLong = "", genericPromptText = prompt,
            reportImageBase64 = imageBase64, reportImageMime = imageMime,
            reportWebSearchTool = webSearchTool,
            reportReasoningEffort = reasoningEffort,
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
        selectedSwarmIds: Set<String> = emptySet(),
        directModelIds: Set<String> = emptySet(),
        parametersIds: List<String> = emptyList(),
        selectionParamsById: Map<String, List<String>> = emptyMap(),
        reportType: ReportType = ReportType.CLASSIC
    ) {
        reportGenerationJob?.cancel()
        // Outer launch on viewModelScope so navigating away from the
        // result screen doesn't cancel the in-flight OkHttp calls.
        // A screen-scoped scope here previously turned every
        // still-running agent into ERROR on disk: the cancellation
        // surfaced as IOException("Canceled"), executeReportTask's
        // catch (Exception) converted it to a real error response,
        // and the NonCancellable terminal write persisted that error.
        // continueReportInBackground() only sets a flag — without
        // viewModelScope here, "background" can't actually happen.
        reportGenerationJob = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
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
            appViewModel.updateUiState { it.copy(
                reportImageBase64 = null, reportImageMime = null,
                reportWebSearchTool = false, reportReasoningEffort = null
            ) }
            // Layer the per-report advanced overlay on top of any preset
            // merge — "later non-null wins" (matches Settings.mergeParameters
            // semantics for the preset chain). Either layer alone produces
            // the right result; together the user's explicit per-report
            // tweaks win over preset defaults instead of being shadowed by
            // them. Bool fields OR upward.
            val overrideParams = resolveReportOverrideParams(
                aiSettings, parametersIds, state.reportAdvancedParameters,
                state.reportWebSearchTool, state.reportReasoningEffort
            )

            val agents = selectedAgentIds.mapNotNull { aiSettings.getAgentById(it) }
            val swarmMembers = aiSettings.getMembersForSwarms(selectedSwarmIds)
            val swarmMemberIds = swarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val uniqueDirectModelIds = directModelIds.filter { it !in swarmMemberIds }.toSet()

            val directModels = uniqueDirectModelIds.mapNotNull { modelId ->
                val parts = modelId.removePrefix("swarm:").split(":", limit = 2)
                val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
                SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
            }

            val allModelMembers = swarmMembers + directModels
            val allModelIds = swarmMemberIds + uniqueDirectModelIds

            val reportLevelSystemPrompt = state.reportSystemPromptId
                ?.let { aiSettings.getSystemPromptById(it)?.prompt }
            val directModelSids = directModels.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val preGenParamsActive = state.reportAdvancedParameters != null ||
                state.reportWebSearchTool || state.reportReasoningEffort != null
            val reportTasks = buildReportTasks(
                aiSettings, agents, allModelMembers, selectionParamsById, externalSystemPrompt,
                reportLevelSystemPrompt, state.generalSettings, directModelSids, preGenParamsActive
            )

            _agentResults.value = emptyMap()
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
            val report = ReportStorage.createReportAsync(
                context = context, title = title.ifBlank { "AI Report" },
                prompt = aiPrompt, agents = reportTasks.map { it.reportAgent },
                rapportText = rapportText, reportType = reportType, closeText = state.externalCloseHtml,
                imageBase64 = imageBase64, imageMime = imageMime,
                webSearchTool = state.reportWebSearchTool,
                reasoningEffort = state.reportReasoningEffort,
                knowledgeBaseIds = state.attachedKnowledgeBaseIds,
                runId = runId,
                // Capture the generation config so Regenerate replays these
                // exact selections instead of the live UiState/Settings.
                parameterPresetIds = parametersIds,
                advancedParameters = state.reportAdvancedParameters,
                selectionParamsById = selectionParamsById,
                reportSystemPromptId = state.reportSystemPromptId
            )
            val reportId = report.id
            val reportStartMs = System.currentTimeMillis()
            AppLog.i("Report", "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))")

            // reportId is minted inside the launch, so the log-id
            // context element is applied here rather than at the
            // launch site (cf. reportLogContext used elsewhere).
            withContext(AppLog.currentLogId.asContextElement(reportId)) {
            withTracerTags(reportId = reportId, category = "report/prompt", runId = runId) {
                appViewModel.updateUiState { it.copy(currentReportId = reportId) }

                iconGen.kickOffLanguageGeneration(context, reportId, aiPrompt, aiSettings)
                // Title first, then icon (icon is derived from the long title).
                iconGen.kickOffReportTitleGeneration(context, reportId, aiPrompt, aiSettings, thenIcon = true)

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
                    if (reportRunningInBackground) {
                        reportRunningInBackground = false
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Report \"$title\" is ready", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                } finally {
                    // Reset the background flag on cancel paths too —
                    // without this, a Stop mid-run leaves the flag
                    // stuck at true and the next "background" toast
                    // fires spuriously when an unrelated job
                    // completes.
                    reportRunningInBackground = false
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
        }
    }



    /** Resolve the per-report override [AgentParameters] from the captured
     *  generation config: the preset chain merged ([Settings.mergeParameters]),
     *  the advanced overlay on top (later non-null wins; bool fields OR;
     *  returnCitations ANDs so an opt-out anywhere is honoured), then the
     *  per-report 🌐 web / 🧠 reasoning toggles. Shared by the fresh run and
     *  Regenerate so both apply the same selections. */
    private fun resolveReportOverrideParams(
        aiSettings: Settings,
        parameterPresetIds: List<String>,
        advanced: AgentParameters?,
        webSearchTool: Boolean,
        reasoningEffort: String?
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
        return if (reasoningEffort != null) (withWeb ?: AgentParameters()).copy(reasoningEffort = reasoningEffort) else withWeb
    }

    private fun buildReportTasks(
        aiSettings: Settings, agents: List<Agent>, modelMembers: List<SwarmMember>,
        selectionParamsById: Map<String, List<String>>, externalSystemPrompt: String?,
        /** Optional per-report system prompt picked on the model-selection
         *  screen. When non-null, wins over agent / flock / external; when
         *  null, the existing per-agent → per-flock → external resolution
         *  chain applies. */
        reportLevelSystemPrompt: String? = null,
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
        preGenParamsActive: Boolean = false
    ): List<ReportTask> {
        val appSp = general.appWideSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt }
        val rmSp = general.reportModelSystemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt }
        val appPar = aiSettings.mergeParameters(general.appWideParametersIds)
        val rmPar = aiSettings.mergeParameters(general.reportModelParametersIds)

        val agentTasks = agents.map { agent ->
            val ea = agent.copy(
                apiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
                model = aiSettings.getEffectiveModelForAgent(agent)
            )
            val selParams = aiSettings.mergeParameters(selectionParamsById[agent.id] ?: emptyList())
            // selection → agent presets → app-wide (universal floor).
            var params = selParams
                ?: aiSettings.mergeParameters(agent.paramsIds)
                ?: appPar
                ?: AgentParameters()
            val spText = reportLevelSystemPrompt
                ?: resolveSystemPromptText(aiSettings, agent.systemPromptId, findFlockSystemPromptIdForAgent(aiSettings, agent.id))
                ?: externalSystemPrompt
                ?: appSp
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(agent.id, ReportAgent(agent.id, agent.name, ea.provider.id, ea.model, ReportStatus.PENDING), ea, params)
        }

        val modelTasks = modelMembers.map { member ->
            val sid = "swarm:${member.provider.id}:${member.model}"
            val isDirect = sid in directModelSids
            // Bare/direct models get the provider + report-model fallbacks;
            // swarm members get only their swarm level. App-wide is the
            // universal floor for both.
            val providerConfig = aiSettings.getProvider(member.provider)
            val spText = reportLevelSystemPrompt
                ?: findSwarmSystemPromptIdForMember(aiSettings, member.provider, member.model)?.let { aiSettings.getSystemPromptById(it)?.prompt }
                ?: (if (isDirect) providerConfig.systemPromptId?.let { aiSettings.getSystemPromptById(it)?.prompt } else null)
                ?: (if (isDirect) rmSp else null)
                ?: externalSystemPrompt
                ?: appSp
            val selPar = aiSettings.mergeParameters(selectionParamsById[sid]?.takeIf { it.isNotEmpty() } ?: emptyList())
            var params = selPar
                ?: (if (isDirect) aiSettings.mergeParameters(providerConfig.parametersIds) else null)
                ?: (if (isDirect && !preGenParamsActive) rmPar else null)
                ?: (if (!preGenParamsActive) appPar else null)
                ?: AgentParameters()
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(sid,
                ReportAgent(sid, "${member.provider.id} / ${shortModelName(member.model)}", member.provider.id, member.model, ReportStatus.PENDING),
                Agent(sid, "${member.provider.id} / ${shortModelName(member.model)}", member.provider, member.model, aiSettings.getApiKey(member.provider)),
                params
            )
        }
        // One task per provider:model. Agent-sourced tasks lead and
        // win over swarm / direct-model tasks of the same pair —
        // swarms are re-expanded wholesale here from their ids, so
        // without this the generation total drifts above the count
        // the model picker showed (the picker's deduplicateModels
        // already collapsed these cross-source duplicates).
        val seen = mutableSetOf<String>()
        return (agentTasks + modelTasks).filter { task ->
            seen.add("${task.runtimeAgent.provider}:${task.runtimeAgent.model}")
        }
    }

    private fun buildTemperatureSweepTask(report: Report, state: UiState, reportAgent: ReportAgent): ReportTask? {
        val ai = state.aiSettings
        val provider = AppService.findById(reportAgent.provider) ?: return null
        val reportLevelSystemPrompt = report.reportSystemPromptId
            ?.let { ai.getSystemPromptById(it)?.prompt }
        val preGenParamsActive = report.advancedParameters != null || report.parameterPresetIds.isNotEmpty() ||
            report.webSearchTool || report.reasoningEffort != null
        val currentAgent = reportAgent.agentId
            .takeUnless { it.startsWith("swarm:") }
            ?.let { ai.getAgentById(it) }
        val sid = "swarm:${provider.id}:${reportAgent.model}"
        val task = if (currentAgent != null) {
            buildReportTasks(
                ai, listOf(currentAgent), emptyList(), report.selectionParamsById,
                state.externalSystemPrompt, reportLevelSystemPrompt,
                state.generalSettings, emptySet(), preGenParamsActive
            ).firstOrNull()
        } else {
            buildReportTasks(
                ai, emptyList(), listOf(SwarmMember(provider, reportAgent.model)),
                report.selectionParamsById, state.externalSystemPrompt, reportLevelSystemPrompt,
                state.generalSettings, setOf(sid), preGenParamsActive
            ).firstOrNull()
        } ?: return null
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
            runtimeAgent = runtimeAgent
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
        if (!appViewModel.uiState.value.generalSettings.autoCreateRerankAndModeration) return
        if (successCount < 1) return  // nothing to rank / moderate
        val hasKind = { k: SecondaryKind ->
            SecondaryResultStorage.listForReport(context, reportId, k).isNotEmpty()
        }
        val rerankPick = firstModelOfType(aiSettings, ModelType.RERANK)
        if (rerankPick == null) AppLog.i("Report", "auto-rerank skipped: no rerank-capable model")
        else if (!hasKind(SecondaryKind.RERANK)) secondary.runRerank(context, reportId, rerankPick)
        val modPick = firstModelOfType(aiSettings, ModelType.MODERATION)
        if (modPick == null) AppLog.i("Report", "auto-moderation skipped: no moderation-capable model")
        else if (!hasKind(SecondaryKind.MODERATION)) secondary.runModeration(context, reportId, modPick)
    }

    /** Resolve a [DefaultMetaItem]'s target to a (provider, model) pick:
     *  a non-blank provider+model wins; otherwise the named agent; and as
     *  a convenience, an agentName that is actually a provider id falls
     *  back to that provider's default model. Null when unresolvable. */
    private fun resolveMetaTarget(s: Settings, item: com.ai.model.DefaultMetaItem): Pair<AppService, String>? {
        if (item.providerName.isNotBlank() && item.modelName.isNotBlank())
            return AppService.findById(item.providerName)?.let { it to item.modelName }
        if (item.agentName.isNotBlank()) {
            s.agents.firstOrNull { it.name.equals(item.agentName, ignoreCase = true) }
                ?.let { return it.provider to s.getEffectiveModelForAgent(it) }
            AppService.findById(item.agentName)?.let { if (s.isProviderActive(it)) return it to s.getModel(it) }
        }
        return null
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
        if (successCount < 1) return
        val items = aiSettings.defaultMetaItems
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
            val pick = resolveMetaTarget(aiSettings, item)
            if (pick == null) {
                AppLog.i("Report", "auto-meta '${item.metaName}': no resolvable model"); continue
            }
            secondary.runMetaPrompt(context, reportId, prompt, listOf(pick))
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
            if (!isRegeneration && !headless) {
                appViewModel.updateUiState { state ->
                    state.copy(genericReportsProgress = state.genericReportsProgress + 1)
                }
            }
            return
        }
        // The resolved prompt is the request BODY, not headers — passing it
        // positionally landed it in requestHeaders (Bug 57). Name the arg.
        ReportStorage.markAgentRunningAsync(context, reportId, task.resultId, requestBody = aiPrompt)

        // Pull the report's attached KB ids so analyzeWithAgent can
        // do RAG retrieval on this turn. Cheap re-read; the
        // alternative (caching on the ReportTask) is more surface
        // area than payoff.
        val knowledgeBaseIds = ReportStorage.getReport(context, reportId)?.knowledgeBaseIds.orEmpty()
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
            val baseUrl = appViewModel.uiState.value.aiSettings.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
            com.ai.data.withTraceFilenameSink(traceSink) {
                if (headless) {
                    // Background / best-effort calls have no card to stream into.
                    appViewModel.repository.analyzeWithAgent(
                        task.runtimeAgent, "", aiPrompt, task.resolvedParams, overrideParams,
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
                        task.runtimeAgent, "", aiPrompt, task.resolvedParams, overrideParams,
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
        val persisted = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (response.isSuccess) {
                ReportStorage.markAgentSuccessAsync(context, reportId, task.resultId,
                    response.httpStatusCode ?: 200, response.httpHeaders, response.analysis,
                    response.tokenUsage, cost,
                    response.tokenUsage?.let { frozenInputCost },
                    response.tokenUsage?.let { frozenOutputCost },
                    response.citations, response.searchResults,
                    response.relatedQuestions, response.rawUsageJson, durationMs,
                    traceFile = traceSink.get())
            } else {
                ReportStorage.markAgentErrorAsync(context, reportId, task.resultId,
                    response.httpStatusCode, response.error, response.httpHeaders, response.analysis, durationMs,
                    traceFile = traceSink.get())
            }
        }
        if (!persisted) {
            cost?.takeIf { it > 0.0 }?.let {
                ReportStorage.bumpCostsFromDeletedItems(context, reportId, it)
            }
        }

        if (response.error == null && response.tokenUsage != null) {
            val usage = response.tokenUsage
            appViewModel.settingsPrefs.updateUsageStatsAsync(task.runtimeAgent.provider, task.runtimeAgent.model,
                usage.inputTokens, usage.outputTokens, usage.totalTokens)
        }

        val stillPresent = ReportStorage.getReport(context, reportId)
            ?.agents
            ?.any { it.agentId == task.resultId } == true
        if (!stillPresent) {
            AppLog.d("Report", "skip UI publish for deleted agent=${task.resultId} report=$reportId")
            return
        }
        if (!headless) _agentResults.update { it + (task.resultId to response) }
        if (!isRegeneration && !headless) {
            appViewModel.updateUiState { state ->
                state.copy(genericReportsProgress = state.genericReportsProgress + 1)
            }
        }
        AppLog.d(
            "Report",
            "← task ${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} agent=${task.resultId} " +
                (if (response.isSuccess) "ok" else "err") +
                " ${durationMs}ms" +
                (response.tokenUsage?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") +
                (cost?.let { " cost=${"%.5f".format(it)}" } ?: "")
        )
    }

    private fun updateTemperatureSweepState(key: String, transform: (TemperatureSweepState) -> TemperatureSweepState) {
        _temperatureSweepStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

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
        val key = TemperatureSweepState.key(reportId, agentId)
        temperatureSweepJobs.remove(key)?.cancel()
        _temperatureSweepStates.update { it - key }
    }

    fun applyTemperatureCandidate(context: Context, reportId: String, agentId: String, candidateIndex: Int) {
        val key = TemperatureSweepState.key(reportId, agentId)
        val candidate = _temperatureSweepStates.value[key]?.candidates
            ?.getOrNull(candidateIndex) as? TemperatureSweepCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = candidate.response,
                changeSource = RESPONSE_CHANGE_SOURCE_TEMPERATURE,
                changeValue = formatSweepTemperature(candidate.temperature)
            )
            _temperatureSweepStates.update { it - key }
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
        temperatureSweepJobs.remove(key)?.cancel()
        _temperatureSweepStates.update {
            it + (key to TemperatureSweepState(
                reportId = reportId,
                agentId = agentId,
                candidates = temps.map { temp -> TemperatureSweepCandidate.Pending(temp) },
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
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
                val supportedParams = PricingCache.getSupportedParameters(context, task.runtimeAgent.provider, task.runtimeAgent.model)
                if (supportedParams != null && supportedParams.none { it.equals("temperature", ignoreCase = true) }) {
                    val msg = "${task.runtimeAgent.provider.id}/${task.runtimeAgent.model} does not report temperature support."
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
                    report.webSearchTool, report.reasoningEffort
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
                            ApiCallCaps.global.withPermit {
                                ApiCallCaps.report.withPermit {
                                    val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
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
                                        releaser.release()
                                    }
                                }
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
                                usage.inputTokens, usage.outputTokens, usage.totalTokens,
                                kind = MODEL_TEMPERATURE_CALL_KIND
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
        temperatureSweepJobs[key] = job
        job.invokeOnCompletion { temperatureSweepJobs.remove(key, job) }
        return job
    }

    private fun updateReasoningEffortSweepState(key: String, transform: (ReasoningEffortSweepState) -> ReasoningEffortSweepState) {
        _reasoningEffortSweepStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

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
        val key = ReasoningEffortSweepState.key(reportId, agentId)
        reasoningEffortSweepJobs.remove(key)?.cancel()
        _reasoningEffortSweepStates.update { it - key }
    }

    fun applyReasoningEffortCandidate(context: Context, reportId: String, agentId: String, candidateIndex: Int) {
        val key = ReasoningEffortSweepState.key(reportId, agentId)
        val candidate = _reasoningEffortSweepStates.value[key]?.candidates
            ?.getOrNull(candidateIndex) as? ReasoningEffortCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = candidate.response,
                changeSource = RESPONSE_CHANGE_SOURCE_REASONING_EFFORT,
                changeValue = formatSweepReasoningEffort(candidate.effort)
            )
            _reasoningEffortSweepStates.update { it - key }
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
        reasoningEffortSweepJobs.remove(key)?.cancel()
        _reasoningEffortSweepStates.update {
            it + (key to ReasoningEffortSweepState(
                reportId = reportId,
                agentId = agentId,
                candidates = requestedEfforts.map { effort -> ReasoningEffortCandidate.Pending(effort) },
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
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
                    report.webSearchTool, report.reasoningEffort
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
                            ApiCallCaps.global.withPermit {
                                ApiCallCaps.report.withPermit {
                                    val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
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
                                        releaser.release()
                                    }
                                }
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
                                usage.inputTokens, usage.outputTokens, usage.totalTokens,
                                kind = MODEL_REASONING_CALL_KIND
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
        reasoningEffortSweepJobs[key] = job
        job.invokeOnCompletion { reasoningEffortSweepJobs.remove(key, job) }
        return job
    }

    private fun updateWebSearchReplayState(key: String, transform: (WebSearchReplayState) -> WebSearchReplayState) {
        _webSearchReplayStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

    fun clearWebSearchReplay(reportId: String, agentId: String) {
        val key = WebSearchReplayState.key(reportId, agentId)
        webSearchReplayJobs.remove(key)?.cancel()
        _webSearchReplayStates.update { it - key }
    }

    fun applyWebSearchReplay(context: Context, reportId: String, agentId: String) {
        val key = WebSearchReplayState.key(reportId, agentId)
        val result = _webSearchReplayStates.value[key]?.result as? WebSearchReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = result.response,
                changeSource = RESPONSE_CHANGE_SOURCE_WEB_SEARCH
            )
            _webSearchReplayStates.update { it - key }
        }
    }

    fun startWebSearchReplay(
        context: Context,
        reportId: String,
        agentId: String
    ): Job {
        val key = WebSearchReplayState.key(reportId, agentId)
        webSearchReplayJobs.remove(key)?.cancel()
        _webSearchReplayStates.update {
            it + (key to WebSearchReplayState(
                reportId = reportId,
                agentId = agentId,
                result = WebSearchReplayResult.Running,
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
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
                    report.webSearchTool, report.reasoningEffort
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
                        ApiCallCaps.global.withPermit {
                            ApiCallCaps.report.withPermit {
                                val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
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
                                    releaser.release()
                                }
                            }
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
                            usage.inputTokens, usage.outputTokens, usage.totalTokens,
                            kind = MODEL_WEB_SEARCH_CALL_KIND
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
        webSearchReplayJobs[key] = job
        job.invokeOnCompletion { webSearchReplayJobs.remove(key, job) }
        return job
    }

    private fun updatePromptEditReplayState(key: String, transform: (PromptEditReplayState) -> PromptEditReplayState) {
        _promptEditReplayStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

    fun clearPromptEditReplay(reportId: String, agentId: String) {
        val key = PromptEditReplayState.key(reportId, agentId)
        promptEditReplayJobs.remove(key)?.cancel()
        _promptEditReplayStates.update { it - key }
    }

    fun applyPromptEditReplay(context: Context, reportId: String, agentId: String) {
        val key = PromptEditReplayState.key(reportId, agentId)
        val result = _promptEditReplayStates.value[key]?.result as? PromptEditReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            ReportStorage.applyAgentChatResponse(
                context = context,
                reportId = reportId,
                agentId = agentId,
                body = result.response,
                changeSource = RESPONSE_CHANGE_SOURCE_EDIT
            )
            _promptEditReplayStates.update { it - key }
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
        promptEditReplayJobs.remove(key)?.cancel()
        _promptEditReplayStates.update {
            it + (key to PromptEditReplayState(
                reportId = reportId,
                agentId = agentId,
                result = PromptEditReplayResult.Running,
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
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
                    report.webSearchTool, report.reasoningEffort
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
                        ApiCallCaps.global.withPermit {
                            ApiCallCaps.report.withPermit {
                                val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
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
                                    releaser.release()
                                }
                            }
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
                            usage.inputTokens,
                            usage.outputTokens,
                            usage.totalTokens,
                            kind = MODEL_PROMPT_EDIT_CALL_KIND
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
        promptEditReplayJobs[key] = job
        job.invokeOnCompletion { promptEditReplayJobs.remove(key, job) }
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
        coroutineScope {
            // Interleave by host so a picks list clustered by provider
            // doesn't have the first launches all hammer one host while
            // holding outer cap permits idle.
            interleaveByHost(reportTasks) { providerHost(it.runtimeAgent.provider) }.map { task ->
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
                    ApiCallCaps.global.withPermit {
                        ApiCallCaps.report.withPermit {
                            val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
                            try {
                                withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                    executeReportTask(context, reportId, aiPrompt, overrideParams, task,
                                        imageBase64, imageMime, headless = headless)
                                }
                            } finally {
                                releaser.release()
                            }
                        }
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

    /** Fire-and-forget: create + run ONE report fully in the background from
     *  an explicit [prompt] / [title] + the swarm [swarmId], on its OWN
     *  independent coroutine — NOT the shared [reportGenerationJob], so many
     *  can run at once and none cancels another, and it touches no live
     *  single-report UI state (no dialog, no _agentResults, no progress, no
     *  currentReportId). Returns immediately. Backs the Stress test, which
     *  submits every Example Prompt and finishes at once. */
    fun submitBackgroundReport(
        context: Context, prompt: String, title: String, swarmId: String,
        onReportCreated: ((String) -> Unit)? = null,
    ) {
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val swarmMembers = aiSettings.getMembersForSwarms(setOf(swarmId))
            val reportTasks = buildReportTasks(
                aiSettings, emptyList(), swarmMembers, emptyMap(), state.externalSystemPrompt,
                null, state.generalSettings, emptySet(), false
            )
            if (reportTasks.isEmpty()) {
                AppLog.w("Report", "background report skipped — no active models for swarm $swarmId")
                return@launch
            }
            val runId = java.util.UUID.randomUUID().toString()
            val report = ReportStorage.createReportAsync(
                context = context, title = title.ifBlank { "AI Report" },
                prompt = prompt, agents = reportTasks.map { it.reportAgent },
                reportType = ReportType.CLASSIC, runId = runId
            )
            val reportId = report.id
            onReportCreated?.invoke(reportId)
            val startMs = System.currentTimeMillis()
            withContext(AppLog.currentLogId.asContextElement(reportId)) {
                withTracerTags(reportId = reportId, category = "report/prompt", runId = runId) {
                    AppLog.i("Report", "→ start (bg) \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))")
                    iconGen.kickOffLanguageGeneration(context, reportId, prompt, aiSettings)
                    // Title first, then icon (icon is derived from the long title).
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
        // editing), fall back to the report's persisted agent set.
        val rebuilt = appViewModel.uiState.value.stagedReportModels
            .ifEmpty { reportToModels(report, ai) }
        _agentResults.value = emptyMap()
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
        appViewModel.updateUiState { it.copy(
            stagedReportModels = models,
            pendingReportModels = emptyList(),
            editModeReportId = null
        ) }
        restoreCompletedReport(context, reportId)
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
        appViewModel.updateUiState { it.copy(
            genericPromptText = newPrompt,
            hasPendingPromptChange = true
        ) }
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
        // orange line (blank long → falls back to short via barTitle).
        appViewModel.updateUiState { it.copy(genericPromptTitle = newTitle, genericPromptTitleLong = newTitleLong) }
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
    fun restartReportInfoErrors(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ai = appViewModel.uiState.value.aiSettings
            val g = appViewModel.uiState.value.generalSettings
            withTracerTags(reportId = reportId, category = "Report info restart errors") {
                // Report-level rows. Icon is derived from the title, so when
                // the title errored we re-run title→icon together (chaining the
                // icon only if it also errored); an icon-only error regenerates
                // just the icon from the stored long title.
                val titleErr = !report.titleErrorMessage.isNullOrBlank()
                val iconErr = !report.iconErrorMessage.isNullOrBlank()
                if (titleErr) {
                    ReportStorage.clearReportTitleError(context, reportId)
                    if (iconErr) ReportStorage.clearReportIcon(context, reportId)
                    iconGen.kickOffReportTitleGeneration(context, reportId, report.prompt, ai, thenIcon = iconErr)
                } else if (iconErr) {
                    ReportStorage.clearReportIcon(context, reportId)
                    iconGen.kickOffIconGeneration(context, reportId, report.prompt, ai)
                }
                if (!report.languageIconErrorMessage.isNullOrBlank()) {
                    ReportStorage.clearReportLanguage(context, reportId)
                    iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
                }
                // Per-model rows: re-run just the agents whose icon or model-title
                // errored (and only the side that failed).
                report.agents.forEach { ra ->
                    val iconErr = !ra.iconErrorMessage.isNullOrBlank()
                    val titleErr = !ra.modelTitleErrorMessage.isNullOrBlank()
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
    }

    fun regenerateReportInfo(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ai = appViewModel.uiState.value.aiSettings
            val g = appViewModel.uiState.value.generalSettings
            withTracerTags(reportId = reportId, category = "Report info regenerate") {
                iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
                // Title first, then icon (icon is derived from the long title).
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

    /**
     * Re-run a previously generated report end-to-end with the same prompt, agent set,
     * and parameter selections.
     */
    fun regenerateReport(context: Context, reportId: String) {
        // viewModelScope so navigating away mid-regenerate doesn't
        // cancel in-flight calls and persist them as ERROR. Same
        // bug class fixed in generateGenericReports.
        appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            AuditLog.append(reportId, "Regenerating the report")
            val state = appViewModel.uiState.value
            val ai = state.aiSettings
            val staged = state.stagedReportModels
            // Prefer a staged list from Edit Models — falls back to the on-disk agent set.
            val rebuilt = if (staged.isNotEmpty()) staged else reportToModels(report, ai)
            val agentIds = rebuilt.filter { it.type == "agent" }.mapNotNull { it.agentId }.toSet()
            val swarmIds = rebuilt.filter { it.sourceType == "swarm" && it.type == "model" }.mapNotNull { it.sourceId }.toSet()
            val directIds = rebuilt.filter { it.sourceType == "model" }.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val agents = agentIds.mapNotNull { ai.getAgentById(it) }
            val swarmMembers = ai.getMembersForSwarms(swarmIds)
            val swarmMemberIds = swarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val uniqueDirectIds = directIds.filter { it !in swarmMemberIds }.toSet()
            val directModels = uniqueDirectIds.mapNotNull { mid ->
                val parts = mid.removePrefix("swarm:").split(":", limit = 2)
                val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
                SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
            }
            // Replay the report's CAPTURED generation config (system prompt,
            // per-model param selections, preset/advanced params) rather than
            // whatever the live UiState/Settings hold now.
            val reportLevelSystemPrompt = report.reportSystemPromptId
                ?.let { ai.getSystemPromptById(it)?.prompt }
            val directModelSids = directModels.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val preGenParamsActive = report.advancedParameters != null || report.parameterPresetIds.isNotEmpty() ||
                report.webSearchTool || report.reasoningEffort != null
            val tasks = buildReportTasks(
                ai, agents, swarmMembers + directModels, report.selectionParamsById, state.externalSystemPrompt,
                reportLevelSystemPrompt, state.generalSettings, directModelSids, preGenParamsActive
            )
            val existingIds = report.agents.map { it.agentId }.toSet()
            val newTasks = tasks.filter { it.resultId !in existingIds }
            val removedIds = existingIds - tasks.map { it.resultId }.toSet()

            // Decide what gets refreshed:
            //  - prompt or parameters changed → cascade everything: every
            //    agent, then every existing meta, then every translation.
            //  - only the model list changed → additive: add the new
            //    agents, drop the removed ones, leave everything else
            //    alone. Existing meta runs and translations still
            //    reference the old agent set; the user can re-pick
            //    individually if they want them refreshed.
            //  - nothing changed → no-op.
            val cascadeAll = state.hasPendingPromptChange || state.hasPendingParametersChange
            val tasksToRun = if (cascadeAll) tasks else newTasks
            // Silent-drop guard: if the user (or an imported example
            // report) gave us a non-empty model list but every entry
            // was filtered out — orphaned agent ids that don't match
            // any configured Agent, or provider ids that aren't in
            // the local ProviderRegistry — surface a toast so the
            // regenerate doesn't appear to do nothing. Without this
            // the user taps Regenerate and the screen just sits.
            if (rebuilt.isNotEmpty() && tasks.isEmpty()) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context,
                        "Regenerate had nothing to run — none of the report's agents or providers are configured on this device.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            if (tasksToRun.isEmpty() && removedIds.isEmpty() && !cascadeAll) return@launch

            withTracerTags(reportId = reportId, category = "Report regenerate") {
                // Re-run icon-gen only when the user edited the prompt.
                // A pure model-list / parameters regenerate keeps the
                // existing icon — the report's content didn't change.
                if (state.hasPendingPromptChange) {
                    ReportStorage.clearReportIcon(context, reportId)
                    ReportStorage.clearReportTitleError(context, reportId)
                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                    // Prompt changed → regenerate the title too, then the icon
                    // (which derives from the new long title).
                    iconGen.kickOffReportTitleGeneration(context, reportId, report.prompt, ai, thenIcon = true)
                    iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
                }
                for (id in removedIds) ReportStorage.removeAgent(context, reportId, id)
                if (newTasks.isNotEmpty()) ReportStorage.appendAgents(context, reportId, newTasks.map { it.reportAgent })
                // Reset existing-but-rerunning agents to PENDING so the
                // result row shows the spinning hourglass while the new
                // call is in flight. New agents are PENDING already via
                // appendAgents.
                for (task in tasksToRun) {
                    if (task.resultId in existingIds) ReportStorage.resetAgentToPending(context, reportId, task.resultId)
                }
                val tasksToRunIds = tasksToRun.map { it.resultId }.toSet()
                _agentResults.update { existing ->
                    existing.filterKeys { k -> k !in removedIds && k !in tasksToRunIds }
                }
                ReportStorage.bumpReportTimestamp(context, reportId)
                // The result-row list is driven by genericReportsSelectedAgents;
                // sync it with the post-mutation agent set so newly-added rows
                // appear (with the spinning hourglass via empty _agentResults)
                // and removed rows disappear. Reset progress to count only the
                // agents not being re-run — each task-to-run will bump progress
                // on completion (isRegeneration = false below) until it equals
                // total again. Without this, additive regenerate would silently
                // drop new rows from the UI.
                val finalAgentIds = tasks.map { it.resultId }.toSet()
                appViewModel.updateUiState { s -> s.copy(
                    stagedReportModels = emptyList(),
                    pendingReportModels = emptyList(),
                    hasPendingPromptChange = false,
                    hasPendingParametersChange = false,
                    genericReportsSelectedAgents = finalAgentIds,
                    genericReportsTotal = finalAgentIds.size,
                    genericReportsProgress = finalAgentIds.size - tasksToRunIds.size
                ) }

                if (tasksToRun.isNotEmpty()) {
                    val finalReport = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    // Same captured config as the task build above (presets +
                    // advanced + the report's own web/reasoning flags).
                    val overrideParams = resolveReportOverrideParams(
                        ai, finalReport.parameterPresetIds, finalReport.advancedParameters,
                        finalReport.webSearchTool, finalReport.reasoningEffort
                    )
                    coroutineScope {
                        // Interleave by host — same rationale as the
                        // fresh-run path: a per-provider-clustered task
                        // list otherwise has its first N launches sit
                        // on a single host's per-host cap while holding
                        // outer cap permits idle.
                        interleaveByHost(tasksToRun) { providerHost(it.runtimeAgent.provider) }.map { task ->
                            async {
                                // Canonical order global → report → per-host
                                // (host gate INSIDE global), else it deadlocks
                                // against the global→host metadata/interceptor
                                // path. See runReportPrimaryCalls for the full
                                // rationale.
                                ApiCallCaps.global.withPermit {
                                    ApiCallCaps.report.withPermit {
                                        val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
                                        try {
                                            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                                executeReportTask(context, reportId, finalReport.prompt, overrideParams, task,
                                                    finalReport.imageBase64, finalReport.imageMime, isRegeneration = false)
                                            }
                                        } finally {
                                            releaser.release()
                                        }
                                    }
                                }
                                // Per-task auto-fire — same shape as
                                // generateGenericReports. Each agent's
                                // worker-based title→icon enrichment kicks
                                // off the moment its primary call settles
                                // to SUCCESS; generateIconFromTitle's
                                // clearReportAgentIconState wipes any stale
                                // icon + iconCalls rows so the re-fire is
                                // clean.
                                val g = appViewModel.uiState.value.generalSettings
                                if (g.perModelIconOn() || g.perModelTitleOn()) {
                                    val ra = ReportStorage.getReport(context, reportId)
                                        ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                                    if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                                        iconGen.runPerModelEnrichment(context, reportId, ra, finalReport.prompt, ai,
                                            g.perModelIconOn(), g.perModelTitleOn())
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                // Cascade: prompt / params change invalidates every meta
                // result and every translation. Re-fire each meta kind
                // with its original picks (RERANK first because chat-
                // type META runs may consume it as Top-Ranked scope),
                // then re-fire translations sequentially (translation
                // jobs are mutually exclusive — startTranslation cancels
                // the previous one). Picks come from the persisted rows so
                // the user gets the same coverage they had before.
                if (cascadeAll) cascadeMetasAndTranslations(context, reportId)
            }
        }
    }

    /** Re-fire EVERY agent on [reportId] from scratch, regardless of
     *  the model-list / prompt / parameters diff that the public
     *  [regenerateReport] uses to decide what to re-run. Each agent
     *  is reset to PENDING and re-dispatched via [executeReportTask].
     *  Returns immediately — dispatch runs on viewModelScope. Used
     *  by [com.ai.viewmodel.RegenerateBatchEngine]'s AGENTS phase.
     *
     *  Mirrors the agent-dispatch portion of [regenerateReport] but
     *  skips the prompt/params diff, the staged-edit-models merge,
     *  and the secondary/translation cascade — the engine handles
     *  cascading itself one phase at a time. */
    fun forceRegenerateAllAgents(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            trackRegenerateJob(reportId, coroutineContext[Job]!!)
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val state = appViewModel.uiState.value
            val ai = state.aiSettings
            val rebuilt = reportToModels(report, ai)
            val agentIds = rebuilt.filter { it.type == "agent" }.mapNotNull { it.agentId }.toSet()
            val swarmIds = rebuilt.filter { it.sourceType == "swarm" && it.type == "model" }
                .mapNotNull { it.sourceId }.toSet()
            val directIds = rebuilt.filter { it.sourceType == "model" }
                .map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val agents = agentIds.mapNotNull { ai.getAgentById(it) }
            val swarmMembers = ai.getMembersForSwarms(swarmIds)
            val swarmMemberIds = swarmMembers.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val uniqueDirectIds = directIds.filter { it !in swarmMemberIds }.toSet()
            val directModels = uniqueDirectIds.mapNotNull { mid ->
                val parts = mid.removePrefix("swarm:").split(":", limit = 2)
                val provider = AppService.findById(parts.getOrNull(0) ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                SwarmMember(provider, parts.getOrNull(1) ?: return@mapNotNull null)
            }
            // Replay the report's CAPTURED generation config (system prompt,
            // per-model param selections, preset/advanced params), exactly
            // like regenerateReport — NOT the live UiState. Reading state.*
            // here meant a batch regenerate after a restart or a settings
            // edit silently re-ran every agent with a different system
            // prompt / parameter set than the report was generated with.
            val reportLevelSystemPrompt = report.reportSystemPromptId
                ?.let { ai.getSystemPromptById(it)?.prompt }
            val directModelSids = directModels.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            val preGenParamsActive = report.advancedParameters != null || report.parameterPresetIds.isNotEmpty() ||
                report.webSearchTool || report.reasoningEffort != null
            val tasks = buildReportTasks(
                ai, agents, swarmMembers + directModels, report.selectionParamsById,
                state.externalSystemPrompt, reportLevelSystemPrompt,
                state.generalSettings, directModelSids, preGenParamsActive
            )
            if (tasks.isEmpty()) return@launch
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
            _agentResults.update { existing ->
                existing.filterKeys { k -> k !in tasks.map { it.resultId }.toSet() }
            }
            ReportStorage.bumpReportTimestamp(context, reportId)
            withTracerTags(reportId = reportId, category = "Batch regenerate agents") {
                // Same captured config as the task build above (presets +
                // advanced + the report's own web/reasoning flags) — shared
                // with regenerateReport so both paths replay identically.
                val overrideParams = resolveReportOverrideParams(
                    ai, report.parameterPresetIds, report.advancedParameters,
                    report.webSearchTool, report.reasoningEffort
                )
                coroutineScope {
                    interleaveByHost(tasks) { providerHost(it.runtimeAgent.provider) }.map { task ->
                        async {
                            // Canonical order global → report → per-host (host
                            // gate INSIDE global) to avoid the global↔host
                            // deadlock vs the metadata/interceptor path.
                            ApiCallCaps.global.withPermit {
                                ApiCallCaps.report.withPermit {
                                    val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
                                    try {
                                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                            executeReportTask(
                                                context, reportId, report.prompt, overrideParams, task,
                                                report.imageBase64, report.imageMime,
                                                isRegeneration = true
                                            )
                                        }
                                    } finally {
                                        releaser.release()
                                    }
                                }
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

    private suspend fun cascadeMetasAndTranslations(
        context: Context, reportId: String
    ) {
        val all = SecondaryResultStorage.listForReport(context, reportId)
        if (all.isEmpty()) return

        // Group META rows by their Meta prompt id so we can re-run each
        // one. Only kind == META: RERANK / MODERATION rows can also carry
        // a resolvable metaPromptId, but runMetaPrompt hardcodes kind =
        // META — so cascading them here would silently re-type a persisted
        // Rerank / Moderation row into a Meta result (vanishing from its
        // bucket + the TopRanked-scope dropdown), and a rerank-only model
        // would be dispatched down the chat path and error. Legacy rows
        // (no metaPromptId) are skipped: not enough info to regenerate
        // under the CRUD-driven flow, so leaving them preserves history.
        val metaRows = all.filter { it.kind == SecondaryKind.META }
        val groups = metaRows
            .filter { !it.metaPromptId.isNullOrBlank() }
            // Fan-out pair rows (fanOutSourceAgentId) and fan-in rows
            // (fanInOf) are owned by FanOutEngine, not this generic Meta
            // cascade. They carry a metaPromptId too, so without this
            // guard a prompt/param-edit regenerate would sweep them here
            // and recreate them via runMetaPrompt — which drops the
            // fanOutSourceAgentId / fanInOf linkage, breaking the
            // fan-out drill-in (it can no longer hydrate the pair grid).
            // Leave them untouched; the fan-out re-runs through its own
            // engine path.
            .filter { it.fanOutSourceAgentId == null && it.fanInOf == null }
            .groupBy { it.metaPromptId!! }
        val metaPromptsLookup = appViewModel.uiState.value.aiSettings.internalPrompts.associateBy { it.id }
        for ((metaPromptId, rows) in groups) {
            val mp = metaPromptsLookup[metaPromptId] ?: continue
            val picks = rows
                .mapNotNull { meta ->
                    val provider = AppService.findById(meta.providerId) ?: return@mapNotNull null
                    provider to meta.model
                }
                .distinct()
            if (picks.isEmpty()) continue
            // Recover the scope the user originally ran with (persisted
            // on the row via secondaryScope). For TopRanked, only honour
            // it if the referenced rerank still exists on the (post-
            // cascade) report — otherwise the rerank itself may have
            // been wiped or be mid-rerun, and we fall back to AllReports
            // so the cascade doesn't reference a stale id. Manual scope
            // is keyed on agentIds which survive a prompt-only edit.
            val sampleScope = rows.firstOrNull { !it.secondaryScope.isNullOrBlank() }?.secondaryScope
            val decoded = SecondaryScope.decodeOrAllReports(sampleScope)
            val safeScope: SecondaryScope = when (decoded) {
                is SecondaryScope.TopRanked -> {
                    val rerankStillThere = SecondaryResultStorage.get(context, reportId, decoded.rerankResultId) != null
                    if (rerankStillThere) decoded else SecondaryScope.AllReports
                }
                else -> decoded
            }
            for (m in rows) SecondaryResultStorage.delete(context, reportId, m.id)
            secondary.runMetaPrompt(context, reportId, mp, picks, safeScope)?.join()
        }

        val byKind = all.groupBy { it.kind }

        val translates = byKind[SecondaryKind.TRANSLATE].orEmpty()
        if (translates.isNotEmpty()) {
            data class TranslateRun(val lang: String, val native: String, val provider: AppService, val model: String)
            val translateRuns = translates
                .mapNotNull { meta ->
                    val lang = meta.targetLanguage ?: return@mapNotNull null
                    val native = meta.targetLanguageNative ?: lang
                    val provider = AppService.findById(meta.providerId) ?: return@mapNotNull null
                    TranslateRun(lang, native, provider, meta.model)
                }
                .distinct()
            for (t in translates) SecondaryResultStorage.delete(context, reportId, t.id)
            for (run in translateRuns) {
                translation.startTranslation(context, reportId, run.lang, run.native, listOf(run.provider to run.model)).second.join()
            }
        }
    }

    private fun cancelReportOwnedWorkBeforeDelete(reportId: String): Boolean {
        val cleared = appViewModel.uiState.value.currentReportId == reportId
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
        //   - reportGenerationJob is the agent-fanout for the initial
        //     generation; if the user trashes mid-generation it needs
        //     to die too. We only cancel it when the deleted report
        //     is the currently-active one — a delete from the hub
        //     while a different report is generating mustn't kill the
        //     active run.
        if (cleared) reportGenerationJob?.cancel()
        val fanOutPrefix = "$reportId|"
        // Fan-out runs + per-pair coroutines are owned by the engine now.
        fanOutEngine.cancelAllForReport(reportId)
        // Tournament runs + per-match coroutines likewise.
        tournamentEngine.cancelAllForReport(reportId)
        // Judge-the-judges runs + per-cell coroutines likewise.
        judgeEvalEngine.cancelAllForReport(reportId)
        // Compare-with-meta runs + per-cell coroutines likewise.
        compareEngine.cancelAllForReport(reportId)
        // Plain-meta edit sweeps / replays (MetaDetailScreen ✏️) likewise.
        metaEditManager.cancelAllForReport(reportId)
        // Translation runs + the regenerate-batch orchestrator are also
        // report-owned and were NOT cancelled here — a translation
        // completing after the delete writes via SecondaryResultStorage
        // and would recreate the just-deleted report's storage dir (a
        // zombie report), and a regenerate batch would keep dispatching
        // agent calls against a gone report.
        translation.cancelAllForReport(reportId)
        temperatureSweepJobs.entries
            .filter { it.key.startsWith(fanOutPrefix) }
            .forEach { it.value.cancel() }
        _temperatureSweepStates.update { states ->
            states.filterKeys { !it.startsWith(fanOutPrefix) }
        }
        reasoningEffortSweepJobs.entries
            .filter { it.key.startsWith(fanOutPrefix) }
            .forEach { it.value.cancel() }
        _reasoningEffortSweepStates.update { states ->
            states.filterKeys { !it.startsWith(fanOutPrefix) }
        }
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
                appViewModel.clearAgentIconFanOut(agentId)
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
        if (cleared) dismissGenericReportsDialog()
        return cleared
    }

    /** Delete a report file and, if it's the one currently shown, dismiss the screen state. */
    fun deleteReport(context: Context, reportId: String) {
        cancelReportOwnedWorkBeforeDelete(reportId)
        // The disk delete (report file + per-report secondary dir) off the
        // main thread; the cancellations above are non-blocking and must
        // run synchronously first so nothing is still writing as we delete.
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
        ids.forEach { cancelReportOwnedWorkBeforeDelete(it) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
        scope.launch(Dispatchers.IO) {
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
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return
        // Only rebuild entries for agents that actually FINISHED
        // (SUCCESS / ERROR / STOPPED). A report opened while it's still
        // generating — now common since background/stress reports let you
        // open one mid-run — has PENDING / RUNNING agents with a null
        // responseBody; mapping those would yield AnalysisResponse(
        // analysis=null, error=null) → isSuccess=false → a spurious ❌ on
        // every not-yet-finished model. Omitting them leaves the row's
        // result null so it renders the spinner. (Mirrors the terminal-only
        // filter in hydrateAgentResultsFromStorage.)
        val terminal = report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS ||
                it.reportStatus == ReportStatus.ERROR ||
                it.reportStatus == ReportStatus.STOPPED
        }
        val rebuilt = terminal.mapNotNull { ra ->
            val service = AppService.findById(ra.provider) ?: return@mapNotNull null
            ra.agentId to AnalysisResponse(
                service = service, analysis = ra.responseBody, error = ra.errorMessage,
                agentName = ra.agentName, tokenUsage = ra.tokenUsage,
                citations = ra.citations, searchResults = ra.searchResults,
                relatedQuestions = ra.relatedQuestions, rawUsageJson = ra.rawUsageJson,
                httpHeaders = ra.responseHeaders, httpStatusCode = ra.httpStatus
            )
        }.toMap()
        _agentResults.value = rebuilt
        appViewModel.updateUiState { it.copy(
            currentReportId = report.id,
            genericReportsTotal = report.agents.size,
            // Progress = finished agents only, so a still-generating report
            // opened mid-run shows the real X/Y, not a premature 100%.
            genericReportsProgress = terminal.size,
            genericReportsSelectedAgents = report.agents.map { ra -> ra.agentId }.toSet(),
            genericPromptTitle = report.title,
            genericPromptTitleLong = report.titleLong.orEmpty(),
            genericPromptText = report.prompt,
            showGenericReportsDialog = true
        ) }
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
        // Only restore agents that have actually finished (SUCCESS / ERROR / STOPPED).
        // PENDING and RUNNING entries stay missing so the screen renders the spinning
        // hourglass instead of a stale ❌ during a fresh generation.
        val rebuilt = report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS ||
                it.reportStatus == ReportStatus.ERROR ||
                it.reportStatus == ReportStatus.STOPPED
        }.mapNotNull { ra ->
            val service = AppService.findById(ra.provider) ?: return@mapNotNull null
            ra.agentId to AnalysisResponse(
                service = service,
                analysis = ra.responseBody,
                error = ra.errorMessage,
                agentName = ra.agentName,
                tokenUsage = ra.tokenUsage,
                citations = ra.citations,
                searchResults = ra.searchResults,
                relatedQuestions = ra.relatedQuestions,
                rawUsageJson = ra.rawUsageJson,
                httpHeaders = ra.responseHeaders,
                httpStatusCode = ra.httpStatus
            )
        }.toMap()
        if (rebuilt.isNotEmpty()) {
            // Merge: prefer in-memory entries over disk so a fresh
            // success that hasn't been written yet isn't overwritten
            // by a stale RUNNING agent's still-on-disk state. Rows
            // missing from memory get the rebuilt entry.
            val merged = rebuilt + _agentResults.value
            _agentResults.value = merged
        }
    }

    fun dismissGenericReportsDialog() {
        // The report job's withTracerTags block restores tags on its
        // own when the job ends or is cancelled — no manual clear here.
        _agentResults.value = emptyMap()
        appViewModel.updateUiState { it.copy(
            showGenericReportsDialog = false, genericPromptTitle = "", genericPromptTitleLong = "", genericPromptText = "",
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null, reportAdvancedParameters = null,
            reportParametersIds = emptyList(),
            reportSystemPromptId = null,
            stagedReportModels = emptyList(), editModeReportId = null,
            pendingReportModels = emptyList(),
            hasPendingPromptChange = false, hasPendingParametersChange = false
        ) }
    }

    fun continueReportInBackground() {
        reportRunningInBackground = true
        appViewModel.updateUiState { it.copy(showGenericReportsDialog = false) }
    }

    fun cancel() {
        reportGenerationJob?.cancel()
        reportGenerationJob = null
        // Cancellation triggers the report job's finally inside
        // withTracerTags, which restores the previous (reportId,
        // category) — no manual clear here.
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
    fun regenerateAgent(context: Context, reportId: String, agentId: String) {
        // viewModelScope: same survival rationale as
        // generateGenericReports — a screen-scoped scope here would
        // turn the in-flight call into ERROR on disk if the user
        // navigates away before the new response lands.
        appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            trackRegenerateJob(reportId, coroutineContext[Job]!!)
            withTracerTags(reportId = reportId, category = "Report regenerate agent") {
            val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
            val ra = report.agents.find { it.agentId == agentId } ?: return@withTracerTags
            val provider = AppService.findById(ra.provider) ?: return@withTracerTags
            AuditLog.append(reportId, "Regenerating report model ${ra.provider}/${ra.model}")
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings

            val task = if (agentId.startsWith("swarm:")) {
                val runtimeAgent = Agent(agentId, ra.agentName, provider, ra.model, aiSettings.getApiKey(provider))
                ReportTask(agentId, ra, runtimeAgent, AgentParameters())
            } else {
                val savedAgent = aiSettings.getAgentById(agentId)
                if (savedAgent != null) {
                    val ea = savedAgent.copy(
                        apiKey = aiSettings.getEffectiveApiKeyForAgent(savedAgent),
                        model = aiSettings.getEffectiveModelForAgent(savedAgent)
                    )
                    val params = aiSettings.resolveAgentParameters(savedAgent)
                    ReportTask(agentId, ra, ea, params)
                } else {
                    val runtimeAgent = Agent(agentId, ra.agentName, provider, ra.model, aiSettings.getApiKey(provider))
                    ReportTask(agentId, ra, runtimeAgent, AgentParameters())
                }
            }

            // Drop the old result so the report row reverts to ⏳ until
            // executeReportTask publishes the new one.
            _agentResults.update { it - agentId }
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
            val baseOverride = state.reportAdvancedParameters
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
    fun removeAgentFromReport(context: Context, reportId: String, agentId: String) {
        // Storage read-modify-write + per-orphan deletes off the main
        // thread — this is fired from a UI click and was blocking it.
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val removedStatus = ReportStorage.getReport(context, reportId)
                ?.agents
                ?.firstOrNull { it.agentId == agentId }
                ?.reportStatus
            val removedWasFinished = removedStatus == ReportStatus.SUCCESS ||
                removedStatus == ReportStatus.ERROR ||
                removedStatus == ReportStatus.STOPPED
            ReportStorage.removeAgent(context, reportId, agentId)
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
            _agentResults.update { it - agentId }
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

    /** Generate (or regenerate) the AI title for one user note. Called from
     *  the note editor on every save (add/edit) via [com.ai.ui.shared.
     *  LocalGenerateNoteTitle]. Delegates to the worker-title flow. */
    fun generateUserNoteTitle(context: Context, reportId: String, noteId: String, noteText: String) {
        iconGen.kickOffUserNoteTitle(context, reportId, noteId, noteText, appViewModel.uiState.value.aiSettings)
    }

}
