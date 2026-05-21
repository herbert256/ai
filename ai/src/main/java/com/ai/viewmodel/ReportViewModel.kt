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

/**
 * ViewModel for AI report generation: task building, concurrent execution, cost calculation.
 * Delegates to AppViewModel for shared state and settings.
 */
class ReportViewModel(private val appViewModel: AppViewModel) {

    private var reportGenerationJob: Job? = null
    @Volatile private var reportRunningInBackground = false

    // Active fan-out jobs keyed by "$reportId|$metaPromptId". Used
    // by rerunCompleteFanOut / rerunFailedFanOutPairs / resumeStaleFanOutPairs
    // so the destructive "wipe + rerun" paths can cancel and join the
    // existing run before deleting placeholders. Without this, surviving
    // coroutines from the previous run kept calling
    // SecondaryResultStorage.save on the just-deleted ids, resurrecting
    // zombie rows alongside the freshly-created placeholders and
    // double-billing the user.
    internal val fanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun fanOutJobKey(reportId: String, metaPromptId: String) = "$reportId|$metaPromptId"
    private fun registerFanOutJob(reportId: String, metaPromptId: String, job: Job) {
        val key = fanOutJobKey(reportId, metaPromptId)
        fanOutJobs[key] = job
        job.invokeOnCompletion { fanOutJobs.remove(key, job) }
    }

    /** Tracks in-flight fan-icons batches keyed by
     *  (reportId, metaPromptId). Separate map from [fanOutJobs] so
     *  a launched fan-icons batch on the same fan-out doesn't get
     *  cancelled by deleteFanOutModel etc. */
    internal val fanIconsJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    internal fun fanIconsJobKey(reportId: String, metaPromptId: String) = "$reportId|icons|$metaPromptId"
    internal fun registerFanIconsJob(reportId: String, metaPromptId: String, job: Job) {
        val key = fanIconsJobKey(reportId, metaPromptId)
        fanIconsJobs[key] = job
        job.invokeOnCompletion { fanIconsJobs.remove(key, job) }
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

    // Sibling map for the per-agent "Report icons" run (Create →
    // Report icons). Same cancel-prior-run-on-retap semantics as
    // iconFanOutJobs; cleaned up by deleteReport.
    // Per-agent "Report icons" 3-tier chain jobs. Each successful
    // agent gets its own entry the moment its primary call finishes
    // (so a fast row's icon search can start while a slow row is
    // still generating). Keyed by "$reportId|$agentId" — same shape
    // as the existing agentIconFanOutJobs map — so deleteReport's
    // prefix sweep on "$reportId|" cancels them too.
    internal val reportIconsJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    internal fun reportIconJobKey(reportId: String, agentId: String) = "$reportId|$agentId"
    internal fun registerReportIconForAgentJob(reportId: String, agentId: String, job: Job) {
        val key = reportIconJobKey(reportId, agentId)
        reportIconsJobs.put(key, job)?.cancel()
        job.invokeOnCompletion { reportIconsJobs.remove(key, job) }
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

    // Tracks in-flight resumeStaleFanOutPairs scans per (reportId,
    // metaPromptId). The L1 screen fires resumeStaleFanOutPairs from a
    // LaunchedEffect that re-keys whenever fanOutPrompt changes
    // identity — and fanOutPrompt is recomputed on every aiSettings
    // change (i.e., any settings save, even unrelated ones). Without a
    // guard, touching Settings while a Fan out is running would re-issue
    // the listForReport scan + recovery enqueue, stacking duplicate
    // work on the executor's semaphore.
    private val staleResumeScans = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Per-pair fan-out coroutines, keyed by SecondaryResult.id (the
    // placeholder id). Populated inside runFanOutPrompt /
    // rerunFanOutPlaceholders right before the async block enters its
    // HTTP path, removed via invokeOnCompletion. deleteFanOutModel
    // and rerunCompleteFanOut cancelAndJoin the relevant entries
    // BEFORE deleting their rows, so a coroutine mid-flight can't
    // land a completion via saveIfStillPresent after the delete —
    // which would either silently drop the just-purchased result
    // (exists() returns false) or, worse, resurrect the row in a
    // half-written state. Concurrent map for cross-thread access
    // from the UI-thread Delete handler.
    internal val fanOutPairJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // Tracks single-call Meta/Rerank/Moderation placeholders the
    // report-open auto-resume sweep is currently re-issuing, so a
    // rapid back-then-forward navigation can't double-fire the same
    // row. Keyed by SecondaryResult.id; entries removed in the
    // finally of [resumeStaleMetaPlaceholder].
    private val resumingMetaIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Separate flow from UiState so per-task completions don't force the UiState equality
    // checker to re-compare every other field. UI subscribers observe this independently.
    private val _agentResults = MutableStateFlow<Map<String, AnalysisResponse>>(emptyMap())
    val agentResults: StateFlow<Map<String, AnalysisResponse>> = _agentResults.asStateFlow()

    /** Authoritative Fan Out runtime state. Phase C of the fan-out
     *  redesign creates this engine alongside the existing
     *  fan-out paths in this ViewModel; Phase E wires the UI to it
     *  and removes the duplicate paths. Phase F deletes the
     *  legacy `runningFanOutPairs`, `fanOutPairJobs`, and the 500 ms
     *  polling loop. */
    val fanOutEngine: FanOutEngine = FanOutEngine(appViewModel, this)

    /** Per-report orchestrator for the "Regenerate report" batch
     *  job. Replaces the legacy one-shot [regenerateReport] call —
     *  the title-bar 🔁 icon's confirm dialog now calls
     *  `regenerateBatchEngine.enqueueAndStart` instead. */
    val regenerateBatchEngine: RegenerateBatchEngine = RegenerateBatchEngine(appViewModel, this)

    /** Runtime owner for the "Test all models" run (Housekeeping →
     *  Test). One run, persisted to its own JSON document. */
    val modelTestEngine: ModelTestEngine = ModelTestEngine(appViewModel)
    val translation = TranslationRunManager(appViewModel, this)
    val iconGen = IconGenerationManager(appViewModel, this)

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
            genericPromptTitle = title, genericPromptText = prompt,
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
            val mergedParams = aiSettings.mergeParameters(parametersIds)
            val advanced = state.reportAdvancedParameters
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
                    // returnCitations defaults to true and combines with
                    // AND so an explicit opt-out anywhere in the chain is
                    // honoured — same semantic as Settings.mergeParameters.
                    // Previously the overlay clobbered to advanced's value
                    // (true on a fresh dialog open), silently re-enabling
                    // citations on every preset-disabled run.
                    returnCitations = advanced.returnCitations && mergedParams.returnCitations,
                    searchRecency = advanced.searchRecency ?: mergedParams.searchRecency,
                    webSearchTool = advanced.webSearchTool || mergedParams.webSearchTool,
                    reasoningEffort = advanced.reasoningEffort ?: mergedParams.reasoningEffort
                )
            }
            // The per-report 🌐 toggle adds webSearchTool=true on top of any
            // existing override so it ORs onto each agent's pinned default.
            // Same overlay for the per-report 🧠 reasoning level — non-
            // reasoning models drop the field at dispatch.
            val withWeb = if (state.reportWebSearchTool) {
                (baseOverride ?: AgentParameters()).copy(webSearchTool = true)
            } else baseOverride
            val overrideParams = if (state.reportReasoningEffort != null) {
                (withWeb ?: AgentParameters()).copy(reasoningEffort = state.reportReasoningEffort)
            } else withWeb

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
            val reportTasks = buildReportTasks(aiSettings, agents, allModelMembers, selectionParamsById, externalSystemPrompt, reportLevelSystemPrompt)

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
                runId = runId
            )
            val reportId = report.id
            val reportStartMs = System.currentTimeMillis()
            AppLog.i("Report", "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))")

            // reportId is minted inside the launch, so the log-id
            // context element is applied here rather than at the
            // launch site (cf. reportLogContext used elsewhere).
            withContext(AppLog.currentLogId.asContextElement(reportId)) {
            withTracerTags(reportId = reportId, category = "Report", runId = runId) {
                appViewModel.updateUiState { it.copy(currentReportId = reportId) }

                iconGen.kickOffIconGeneration(context, reportId, aiPrompt, aiSettings)
                iconGen.kickOffLanguageGeneration(context, reportId, aiPrompt, aiSettings)
                iconGen.kickOffReportTitleGeneration(context, reportId, aiPrompt, aiSettings)

                try {
                    coroutineScope {
                        // Interleave by host so a picks list clustered by
                        // provider (e.g. four OpenAI rows followed by
                        // four Anthropic) doesn't have the first four
                        // launches all hammer one host while holding
                        // outer cap permits idle. Round-robin + jitter
                        // within each host bucket spreads load
                        // immediately and varies the run-to-run order.
                        interleaveByHost(reportTasks) { providerHost(it.runtimeAgent.provider) }.map { task ->
                            async {
                                // Non-blocking per-host gate, outside the outer
                                // caps — a task on a capped host yields the
                                // coroutine (delay, not Thread.sleep) instead of
                                // blocking an OkHttp thread, so other hosts'
                                // tasks proceed. The interceptor skips its own
                                // acquire via permitPreAcquired.
                                val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
                                try {
                                    ApiCallCaps.global.withPermit {
                                        ApiCallCaps.report.withPermit {
                                            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                                executeReportTask(context, reportId, aiPrompt, overrideParams, task, imageBase64, imageMime)
                                            }
                                        }
                                    }
                                } finally {
                                    releaser.release()
                                }
                                // Per-task auto-fire: kick off this
                                // agent's 3-tier icon chain the moment
                                // its primary call settles to SUCCESS,
                                // instead of waiting for every other
                                // agent in the report. The chain
                                // launches on viewModelScope, registers
                                // in reportIconsJobs, and runs
                                // independently — the outer async
                                // here returns as soon as the chain is
                                // scheduled, so awaitAll below still
                                // tracks only the primary calls.
                                val perModelOn = appViewModel.uiState.value.generalSettings.perModelIconGenEnabled
                                if (perModelOn) {
                                    val ra = ReportStorage.getReport(context, reportId)
                                        ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                                    if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                                        iconGen.runReportIconsForAgent(context, reportId, ra, aiPrompt, aiSettings)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    val finalReport = ReportStorage.getReport(context, reportId)
                    val ok = finalReport?.agents?.count { it.reportStatus == ReportStatus.SUCCESS } ?: 0
                    val fail = finalReport?.agents?.count { it.reportStatus == ReportStatus.ERROR } ?: 0
                    AppLog.i("Report", "← end \"${title.ifBlank { "AI Report" }}\" ok=$ok fail=$fail in ${System.currentTimeMillis() - reportStartMs}ms")
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
                }
            }
            }
        }
    }



    private fun buildReportTasks(
        aiSettings: Settings, agents: List<Agent>, modelMembers: List<SwarmMember>,
        selectionParamsById: Map<String, List<String>>, externalSystemPrompt: String?,
        /** Optional per-report system prompt picked on the model-selection
         *  screen. When non-null, wins over agent / flock / external; when
         *  null, the existing per-agent → per-flock → external resolution
         *  chain applies. */
        reportLevelSystemPrompt: String? = null
    ): List<ReportTask> {
        val agentTasks = agents.map { agent ->
            val ea = agent.copy(
                apiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
                model = aiSettings.getEffectiveModelForAgent(agent)
            )
            val selParams = aiSettings.mergeParameters(selectionParamsById[agent.id] ?: emptyList())
            var params = selParams ?: aiSettings.resolveAgentParameters(agent)
            val spText = reportLevelSystemPrompt
                ?: resolveSystemPromptText(aiSettings, agent.systemPromptId, findFlockSystemPromptIdForAgent(aiSettings, agent.id))
                ?: externalSystemPrompt
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(agent.id, ReportAgent(agent.id, agent.name, ea.provider.id, ea.model, ReportStatus.PENDING), ea, params)
        }

        val modelTasks = modelMembers.map { member ->
            val sid = "swarm:${member.provider.id}:${member.model}"
            val spText = reportLevelSystemPrompt
                ?: findSwarmSystemPromptIdForMember(aiSettings, member.provider, member.model)?.let { aiSettings.getSystemPromptById(it)?.prompt }
                ?: externalSystemPrompt
            var params = aiSettings.mergeParameters(selectionParamsById[sid] ?: emptyList()) ?: AgentParameters()
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

    /** True when a Google model is benched in [ModelCooldownStore]
     *  because the provider answered a >1h 429. The dispatch
     *  runners delete the in-flight item instead of erroring it. */
    internal fun isBenched(provider: AppService, model: String): Boolean =
        provider.apiFormat == com.ai.data.ApiFormat.GOOGLE &&
            com.ai.data.ModelCooldownStore.isUnavailable(provider.id, model)

    private suspend fun executeReportTask(
        context: Context, reportId: String, aiPrompt: String, overrideParams: AgentParameters?, task: ReportTask,
        imageBase64: String? = null, imageMime: String? = null,
        // Skip the genericReportsProgress increment when re-running a
        // single agent on a finished report — the agent was already
        // counted as complete the first time around, so bumping the
        // counter again would push past total and break the progress
        // bar / completion-equality check.
        isRegeneration: Boolean = false
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
            if (!isRegeneration) {
                appViewModel.updateUiState { state ->
                    state.copy(genericReportsProgress = state.genericReportsProgress + 1)
                }
            }
            return
        }
        ReportStorage.markAgentRunningAsync(context, reportId, task.resultId, aiPrompt)

        // Pull the report's attached KB ids so analyzeWithAgent can
        // do RAG retrieval on this turn. Cheap re-read; the
        // alternative (caching on the ReportTask) is more surface
        // area than payoff.
        val knowledgeBaseIds = ReportStorage.getReport(context, reportId)?.knowledgeBaseIds.orEmpty()
        val startTime = System.currentTimeMillis()
        val response = try {
            val baseUrl = appViewModel.uiState.value.aiSettings.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
            appViewModel.repository.analyzeWithAgent(
                task.runtimeAgent,
                "",
                aiPrompt,
                task.resolvedParams,
                overrideParams,
                context,
                baseUrl,
                imageBase64,
                imageMime,
                knowledgeBaseIds = knowledgeBaseIds,
                aiSettings = appViewModel.uiState.value.aiSettings
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Honor structured cancellation (Stop / nav-away) instead of
            // persisting a fake error onto the agent row.
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
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (response.isSuccess) {
                ReportStorage.markAgentSuccessAsync(context, reportId, task.resultId,
                    response.httpStatusCode ?: 200, response.httpHeaders, response.analysis,
                    response.tokenUsage, cost,
                    response.tokenUsage?.let { frozenInputCost },
                    response.tokenUsage?.let { frozenOutputCost },
                    response.citations, response.searchResults,
                    response.relatedQuestions, response.rawUsageJson, durationMs)
            } else {
                ReportStorage.markAgentErrorAsync(context, reportId, task.resultId,
                    response.httpStatusCode, response.error, response.httpHeaders, response.analysis, durationMs)
            }
        }

        if (response.error == null && response.tokenUsage != null) {
            val usage = response.tokenUsage
            appViewModel.settingsPrefs.updateUsageStatsAsync(task.runtimeAgent.provider, task.runtimeAgent.model,
                usage.inputTokens, usage.outputTokens, usage.totalTokens)
        }

        _agentResults.update { it + (task.resultId to response) }
        if (!isRegeneration) {
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
            genericPromptTitle = report.title, genericPromptText = report.prompt,
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
    suspend fun updateReportTitle(context: Context, reportId: String, newTitle: String) {
        withContext(Dispatchers.IO) {
            ReportStorage.updateReportTitle(context, reportId, newTitle)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
        appViewModel.updateUiState { it.copy(genericPromptTitle = newTitle) }
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
            val reportLevelSystemPrompt = state.reportSystemPromptId
                ?.let { ai.getSystemPromptById(it)?.prompt }
            val tasks = buildReportTasks(ai, agents, swarmMembers + directModels, emptyMap(), state.externalSystemPrompt, reportLevelSystemPrompt)
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
                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                    iconGen.kickOffIconGeneration(context, reportId, report.prompt, ai)
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
                    val baseOverride = state.reportAdvancedParameters
                    val withWeb = if (finalReport.webSearchTool) (baseOverride ?: AgentParameters()).copy(webSearchTool = true) else baseOverride
                    val overrideParams = if (finalReport.reasoningEffort != null) (withWeb ?: AgentParameters()).copy(reasoningEffort = finalReport.reasoningEffort) else withWeb
                    coroutineScope {
                        // Interleave by host — same rationale as the
                        // fresh-run path: a per-provider-clustered task
                        // list otherwise has its first N launches sit
                        // on a single host's per-host cap while holding
                        // outer cap permits idle.
                        interleaveByHost(tasksToRun) { providerHost(it.runtimeAgent.provider) }.map { task ->
                            async {
                                // Non-blocking per-host gate — see
                                // generateGenericReports for the rationale.
                                val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
                                try {
                                    ApiCallCaps.global.withPermit {
                                        ApiCallCaps.report.withPermit {
                                            withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                                executeReportTask(context, reportId, finalReport.prompt, overrideParams, task,
                                                    finalReport.imageBase64, finalReport.imageMime, isRegeneration = false)
                                            }
                                        }
                                    }
                                } finally {
                                    releaser.release()
                                }
                                // Per-task auto-fire — same shape as
                                // generateGenericReports. Each agent's
                                // chain kicks off the moment its primary
                                // call settles to SUCCESS; the agent's
                                // own clearReportAgentIconState (at the
                                // top of runReportIconsForAgent) wipes
                                // any stale icon + iconCalls rows so
                                // the re-fire is clean.
                                val perModelOn = appViewModel.uiState.value.generalSettings.perModelIconGenEnabled
                                if (perModelOn) {
                                    val ra = ReportStorage.getReport(context, reportId)
                                        ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                                    if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                                        iconGen.runReportIconsForAgent(context, reportId, ra, finalReport.prompt, ai)
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
            val reportLevelSystemPrompt = state.reportSystemPromptId
                ?.let { ai.getSystemPromptById(it)?.prompt }
            val tasks = buildReportTasks(
                ai, agents, swarmMembers + directModels, emptyMap(),
                state.externalSystemPrompt, reportLevelSystemPrompt
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
                val baseOverride = state.reportAdvancedParameters
                val withWeb = if (report.webSearchTool)
                    (baseOverride ?: AgentParameters()).copy(webSearchTool = true)
                else baseOverride
                val overrideParams = if (report.reasoningEffort != null)
                    (withWeb ?: AgentParameters()).copy(reasoningEffort = report.reasoningEffort)
                else withWeb
                coroutineScope {
                    interleaveByHost(tasks) { providerHost(it.runtimeAgent.provider) }.map { task ->
                        async {
                            val releaser = acquireOrRequeue(providerHost(task.runtimeAgent.provider))
                            try {
                                ApiCallCaps.global.withPermit {
                                    ApiCallCaps.report.withPermit {
                                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                            executeReportTask(
                                                context, reportId, report.prompt, overrideParams, task,
                                                report.imageBase64, report.imageMime,
                                                isRegeneration = true
                                            )
                                        }
                                    }
                                }
                            } finally {
                                releaser.release()
                            }
                            // Per-agent icon-chain auto-fire — same shape as regenerateReport.
                            val perModelOn = appViewModel.uiState.value.generalSettings.perModelIconGenEnabled
                            if (perModelOn) {
                                val ra = ReportStorage.getReport(context, reportId)
                                    ?.agents?.firstOrNull { it.agentId == task.reportAgent.agentId }
                                if (ra?.reportStatus == ReportStatus.SUCCESS && !ra.responseBody.isNullOrBlank()) {
                                    iconGen.runReportIconsForAgent(context, reportId, ra, report.prompt, ai)
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

        // Group non-translate rows by their Meta prompt id so we can
        // re-run each one. Legacy rows (no metaPromptId) are skipped:
        // we don't have enough info to regenerate them under the new
        // CRUD-driven flow, so leaving them in place preserves their
        // history.
        val nonTranslate = all.filter { it.kind != SecondaryKind.TRANSLATE }
        val groups = nonTranslate
            .filter { !it.metaPromptId.isNullOrBlank() }
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
            runMetaPrompt(context, reportId, mp, picks, safeScope)?.join()
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
                translation.startTranslation(context, reportId, run.lang, run.native, listOf(run.provider to run.model)).join()
            }
        }
    }

    /** Delete a report file and, if it's the one currently shown, dismiss the screen state. */
    fun deleteReport(context: Context, reportId: String) {
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
        fanOutJobs.entries.filter { it.key.startsWith(fanOutPrefix) }.forEach { it.value.cancel() }
        iconFanOutJobs.remove(reportId)?.cancel()
        languageIconFanOutJobs.remove(reportId)?.cancel()
        appViewModel.clearLanguageIconFanOut(reportId)
        // reportIconsJobs is now keyed by "$reportId|$agentId" (one
        // job per per-agent chain) — sweep by prefix like the fan-out
        // pair jobs above.
        reportIconsJobs.entries.filter { it.key.startsWith(fanOutPrefix) }.forEach { it.value.cancel() }
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
        ReportStorage.deleteReport(context, reportId)
        if (cleared) dismissGenericReportsDialog()
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
        val rebuilt = report.agents.mapNotNull { ra ->
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
            genericReportsProgress = report.agents.size,
            genericReportsSelectedAgents = report.agents.map { ra -> ra.agentId }.toSet(),
            genericPromptTitle = report.title,
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
            showGenericReportsDialog = false, genericPromptTitle = "", genericPromptText = "",
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(),
            currentReportId = null, reportAdvancedParameters = null,
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

    // ===== Meta prompt results =====

    /** Kick off a Rerank or Summarize run for [reportId] across [picks]
     *  (provider/model pairs). One [SecondaryResult] per pick is persisted
     *  independently — multi-model picks produce N separate viewable
     *  results. Each call launches its own coroutine; multiple batches
     *  can run concurrently, so a second click while a first is still
     *  in flight does NOT cancel the earlier one. UiState's
     *  [com.ai.viewmodel.UiState.activeSecondaryBatches] counter is
     *  bumped on entry and decremented in a finally block so the Meta
     *  screen's hourglass / poll loop reflects "anything running" no
     *  matter how many overlap. */
    /** Rerank the current report's responses using a locally-installed
     *  MediaPipe TextEmbedder. Embed the prompt + each successful
     *  agent response, score by cosine similarity to the prompt, and
     *  emit the same JSON shape the chat-model / Cohere rerank flows
     *  produce so downstream code (Top-Ranked scope, HTML export,
     *  detail screen) keeps working unchanged. SecondaryResult is
     *  saved with providerId="LOCAL" so cost / usage rows stay
     *  separate from remote provider activity. */
    fun runLocalRerank(
        context: Context,
        reportId: String,
        modelName: String
    ): Job {
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "Report rerank (local)") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val responses = report.agents
                        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        .map { it.responseBody!! }
                    if (responses.isEmpty()) return@withTracerTags
                    val agentName = "Local / ${shortModelName(modelName)}"
                    val placeholder = SecondaryResultStorage.create(context, reportId, SecondaryKind.RERANK, "LOCAL", modelName, agentName)
                    ReportStorage.bumpReportTimestamp(context, reportId)

                    val started = System.currentTimeMillis()
                    val queryVec = com.ai.data.local.LocalEmbedder.embed(context, modelName, listOf(report.prompt))?.firstOrNull()
                    val docVecs = com.ai.data.local.LocalEmbedder.embed(context, modelName, responses)
                    val durationMs = System.currentTimeMillis() - started

                    if (queryVec == null || docVecs == null) {
                        SecondaryResultStorage.save(context, placeholder.copy(
                            errorMessage = "Local embedder failed — check that $modelName is installed (Housekeeping → Local LiteRT models).",
                            durationMs = durationMs
                        ))
                        return@withTracerTags
                    }

                    // Cosine score per doc, descending. Scores rescaled
                    // 0-100 to match the chat-model rerank output.
                    val scored = docVecs.mapIndexed { idx, vec ->
                        val sim = com.ai.data.EmbeddingsStore.cosine(queryVec, vec)
                        Triple(idx + 1, sim, ((sim.coerceIn(-1.0, 1.0) + 1.0) * 50.0).toInt().coerceIn(0, 100))
                    }.sortedByDescending { it.second }

                    val arr = com.google.gson.JsonArray()
                    scored.forEachIndexed { rank, (originalId, sim, score) ->
                        arr.add(com.google.gson.JsonObject().apply {
                            addProperty("id", originalId)
                            addProperty("rank", rank + 1)
                            addProperty("score", score)
                            addProperty("reason", "Cosine similarity: %.4f".format(sim))
                        })
                    }
                    SecondaryResultStorage.save(context, placeholder.copy(
                        content = arr.toString(),
                        errorMessage = null,
                        durationMs = durationMs
                    ))
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Single-pick Rerank runner exposed to the report action row. For
     *  the synthetic LOCAL provider this delegates to [runLocalRerank];
     *  for any other provider it resolves the `rerank` Internal prompt,
     *  builds the @RESULTS@ block from the report's successful agent
     *  responses, and dispatches through [executeSecondaryTask] with
     *  [SecondaryKind.RERANK]. The dispatch already routes RERANK-typed
     *  models (Cohere rerank-v3.5 etc.) to the dedicated rerank API and
     *  routes chat models through the standard analyse path. */
    fun runRerank(
        context: Context,
        reportId: String,
        pick: Pair<AppService, String>,
        /** Honoured only as a single language: rerank is one call against
         *  one set of bodies. AllPresent or a Selected set whose first
         *  non-empty entry is "" means "rank the original bodies"; a
         *  non-empty entry means "rank the translated bodies for that
         *  language". Multi-language Selected just picks the first. */
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent
    ): Job? {
        val (provider, model) = pick
        AppLog.i("Rerank", "→ start report=$reportId via ${provider.id}/$model")
        if (provider.id == AppService.LOCAL.id) {
            return runLocalRerank(context, reportId, model)
        }
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rerankPrompt = aiSettings.getInternalPromptByName("rerank")
            ?: return null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "Report rerank") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successfulCount = report.agents.count {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successfulCount == 0) return@withTracerTags
                    val sourceLanguage: String? = (languageScope as? SecondaryLanguageScope.Selected)
                        ?.languages?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    val (questionForPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, sourceLanguage, includeIds = null)
                    val langCtx = lookupLanguageTranslations(report, allSecondaries, sourceLanguage)
                    val titleForPrompt = langCtx?.title ?: (report.title ?: "")
                    val resolvedPrompt = resolveSecondaryPrompt(
                        rerankPrompt.text, question = questionForPrompt, results = resultsBlock,
                        count = successfulCount, title = titleForPrompt
                    )
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.RERANK, rerankPrompt,
                        provider, model, resolvedPrompt, aiSettings, report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = langCtx?.native
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Run a moderation pass on this report — classifies every
     *  successful agent's response via the provider's native
     *  /v1/moderations endpoint (Mistral compatible). One batch
     *  call, one persisted SecondaryResult with structured JSON
     *  content (per-input flagged + categories + scores).
     *
     *  executeSecondaryTask short-circuits on `kind == MODERATION`
     *  and routes through [com.ai.data.callModerationApi] rather
     *  than the chat path, so the [resolvedPrompt] arg here is
     *  unused at runtime — a stub InternalPrompt covers the
     *  metaPromptId / metaPromptName columns on the persisted row. */
    fun runModeration(
        context: Context,
        reportId: String,
        pick: Pair<AppService, String>,
        /** Same single-language semantics as rerank. When set, the
         *  moderation API receives translated bodies (fallback per-
         *  agent to the original) and the persisted row is tagged
         *  with the language so it appears under that section. */
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent
    ): Job? {
        val (provider, model) = pick
        AppLog.i("Moderation", "→ start report=$reportId via ${provider.id}/$model")
        val aiSettings = appViewModel.uiState.value.aiSettings
        // Stub prompt — moderation is a fixed-API call; the
        // InternalPrompt is only used to label the persisted row.
        // If the user has a custom "moderation" prompt configured
        // (legacy), use it; otherwise mint a synthetic one.
        val moderationPrompt = aiSettings.getInternalPromptByName("moderation")
            ?: com.ai.model.InternalPrompt(
                id = "moderation",
                name = "Moderation",
                category = "moderation",
                text = ""
            )
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = "Report moderation") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successfulCount = report.agents.count {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successfulCount == 0) return@withTracerTags
                    val sourceLanguage: String? = (languageScope as? SecondaryLanguageScope.Selected)
                        ?.languages?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    val native = sourceLanguage?.let { lang ->
                        val secondaries = SecondaryResultStorage.listForReport(context, reportId)
                        lookupLanguageTranslations(report, secondaries, lang)?.native
                    }
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.MODERATION, moderationPrompt,
                        provider, model, resolvedPrompt = "", aiSettings = aiSettings, report = report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = native
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Fan-out Meta runner: for each successful report-model
     *  (the "answerer") and each source model in [scopeChoice], runs
     *  the prompt with `@RESPONSE@` substituted by the source's
     *  response body. Self-pairs are skipped. Always runs on the
     *  Original language; fan out does not fan out to translations.
     *
     *  Persists one [SecondaryResult] per (answerer, source) with
     *  kind=META and fanOutSourceAgentId=source.agentId so the result
     *  drill-in can group by answerer then by source.
     */
    fun runFanOutPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        /** Subset of report-agent ids that should act as "answerers"
         *  (the model receiving the prompt with @RESPONSE@ filled in).
         *  Null = use every successful agent — preserves the pre-feature
         *  default for callers that haven't been updated. The fan-out
         *  confirmation screen passes the user's checked Responder set
         *  so picking 2 responders × 3 initiators yields exactly the
         *  6-minus-self pairs the user expects, instead of always
         *  fanning out every successful agent on the answerer side. */
        responderAgentIds: Set<String>? = null,
        /** English-language name (e.g. "Dutch") to draw the per-pair
         *  source body + prompt from. Null = run on the original
         *  untranslated text — the historical default. When non-null,
         *  each source agent's TRANSLATE row for that language supplies
         *  the body fed into @RESPONSE@; missing translations fall back
         *  to the original body for that one pair. The placeholder is
         *  tagged with targetLanguage so the L1 list groups the run
         *  under the chosen language. Fan-out is single-language by
         *  construction; the scope screen enforces this. */
        sourceLanguage: String? = null
    ): Job? {
        // Dedupe against an already-running fan out for this
        // (report, metaPrompt) — a UI double-tap on the launch
        // button or a second caller via a separate path would
        // otherwise create a parallel batch with its own
        // placeholders, doubling pairs and cost.
        fanOutJobs[fanOutJobKey(reportId, metaPrompt.id)]?.let { existing ->
            if (existing.isActive) return existing
        }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val fanOutStartMs = System.currentTimeMillis()
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat, runId = runId) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags
                    AppLog.i("FanOut", "→ start \"${metaPrompt.name}\" (report=$reportId, ${successful.size} successful agents)")
                    val sources = when (scopeChoice) {
                        SecondaryScope.AllReports -> successful
                        is SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                            val topIds = extractTopRankedIds(rerank?.content, scopeChoice.count)
                            if (topIds.isNullOrEmpty()) successful
                            else topIds.mapNotNull { idx -> successful.getOrNull(idx - 1) }
                        }
                        is SecondaryScope.Manual -> successful.filter { it.agentId in scopeChoice.agentIds }
                    }
                    if (sources.isEmpty()) return@withTracerTags
                    // Apply the per-side Responder selection (when the
                    // caller passed one). Null falls back to "every
                    // successful agent" — the pre-feature default that
                    // other call sites (e.g. rerunFanOutPlaceholders)
                    // still rely on.
                    val answerers = if (responderAgentIds == null) successful
                        else successful.filter { it.agentId in responderAgentIds }
                    if (answerers.isEmpty()) return@withTracerTags
                    // Translation lookup. Build (translatedBodyByAgent,
                    // translatedPrompt, native) once per run. Missing
                    // per-agent translations fall back to the original
                    // body for that one pair — keeps the run useful
                    // even when the translation set is partial.
                    data class LangCtx(val native: String?, val prompt: String, val bodies: Map<String, String>)
                    val langCtx: LangCtx? = sourceLanguage?.let { lang ->
                        val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                        val translates = allSecondaries.filter {
                            it.kind == SecondaryKind.TRANSLATE &&
                                it.targetLanguage == lang &&
                                !it.content.isNullOrBlank()
                        }
                        val native = translates.firstNotNullOfOrNull { it.targetLanguageNative }
                        val translatedPrompt = translates.firstOrNull {
                            it.translateSourceKind == "PROMPT" && it.translateSourceTargetId == "prompt"
                        }?.content ?: report.prompt
                        val bodies = translates
                            .filter { it.translateSourceKind == "AGENT" && !it.translateSourceTargetId.isNullOrBlank() }
                            .associate { it.translateSourceTargetId!! to (it.content ?: "") }
                        LangCtx(native, translatedPrompt, bodies)
                    }
                    val langSuffix = sourceLanguage?.let { " [$it]" } ?: ""
                    // Pre-create every (answerer, source) placeholder
                    // up-front so the Report Result screen's fan out
                    // summary row and the fan out detail screen's L1/L2
                    // counts read the *full* expected work immediately
                    // (e.g. "30 pairs · 30 pending" for 6×5) and tick
                    // down as calls complete — instead of waking up
                    // with "6 pairs · 6 pending" because only the first
                    // source per answerer had been seeded.
                    data class PendingPair(val answerer: ReportAgent, val source: ReportAgent, val placeholder: SecondaryResult)
                    val pending = mutableListOf<PendingPair>()
                    for (answerer in answerers) {
                        val provider = AppService.findById(answerer.provider) ?: continue
                        for (source in sources) {
                            if (source.agentId == answerer.agentId) continue
                            val agentName = "${provider.id} / ${shortModelName(answerer.model)}$langSuffix"
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.META, provider.id, answerer.model, agentName
                            ) {
                                it.copy(
                                    metaPromptId = metaPrompt.id,
                                    metaPromptName = metaPrompt.name,
                                    fanOutSourceAgentId = source.agentId,
                                    runId = runId,
                                    targetLanguage = sourceLanguage,
                                    targetLanguageNative = langCtx?.native
                                )
                            }
                            pending.add(PendingPair(answerer, source, placeholder))
                        }
                    }
                    // Launch one coroutine per pair. Per-provider
                    // concurrency + per-minute rate are enforced through
                    // ProviderThrottle, but we acquire the permit here
                    // (not inside the OkHttp interceptor) so the UI's
                    // queued / running distinction lines up with the
                    // throttle state:
                    //   - pair enters async, hasn't called acquire yet
                    //     → not in runningFanOutPairs → reads as "queued"
                    //   - acquire returns (permit + per-minute slot held)
                    //     → flip to runningFanOutPairs → reads as "running"
                    // The OkHttp interceptor sees permitPreAcquired=true
                    // on the worker (propagated via TagPropagatingExecutor)
                    // and skips its own acquire — no double-counting.
                    //
                    // Per-host suspending caps below — sized to each
                    // host's ProviderThrottle concurrent limit — replace
                    // the single global Semaphore(8) that earlier hot-
                    // fixed Dispatchers.IO starvation. A global cap
                    // ignored the per-provider setting; with per-host
                    // caps Provider A at concurrent=5 can run 5 pairs
                    // in parallel even while Provider B is at its own
                    // separate cap. The suspending withPermit releases
                    // the IO thread while a pair waits its turn, so
                    // the blocking ProviderThrottle.acquire below
                    // (java.util.concurrent.Semaphore.acquire) returns
                    // immediately for every pair the suspending gate
                    // admitted — no IO threads pinned in waiting.
                    val perHostCaps: Map<String, kotlinx.coroutines.sync.Semaphore> = pending
                        .mapNotNull { AppService.findById(it.answerer.provider) }
                        .map { providerHost(it) }
                        .distinct()
                        .associateWith { host ->
                            val (_, concurrent) = ProviderThrottle.limitsFor(host)
                            kotlinx.coroutines.sync.Semaphore(concurrent)
                        }
                    coroutineScope {
                        // Interleave by host so the pair list doesn't
                        // fire in (a1,s1)(a1,s2)…(a1,sN)(a2,s1)… order —
                        // that has the first N launches all queue on
                        // a1's per-host cap while holding the outer
                        // (global + fan-out) caps idle. Round-robin
                        // across host buckets + jitter within each
                        // bucket spreads the very first slot across
                        // every host the run touches.
                        interleaveByHost(pending) { p ->
                            AppService.findById(p.answerer.provider)?.let { providerHost(it) }
                        }.map { item ->
                            // CoroutineStart.LAZY so the async body
                            // doesn't run until we've registered the
                            // Deferred in fanOutPairJobs below. Without
                            // LAZY there's a microsecond window between
                            // `async {}` returning and the registration
                            // line where the pair is mid-flight but
                            // can't be looked up by deleteFanOutModel,
                            // letting it slip past cancellation and
                            // either land a result on a row that's
                            // about to be deleted (silent drop) or
                            // worse, burn the API call entirely.
                            val deferred = async(start = CoroutineStart.LAZY) {
                                val provider = AppService.findById(item.answerer.provider) ?: return@async
                                val host = providerHost(provider)
                                val hostCap = perHostCaps[host]
                                    ?: kotlinx.coroutines.sync.Semaphore(1)
                                // Acquire order: per-host cap FIRST, then
                                // outer global + fan-out. A pair waiting on
                                // a saturated per-host cap (e.g. 12 of 15
                                // pairs targeting one host) suspends here
                                // without holding the outer caps — those
                                // stay free for other batches and for
                                // other hosts' pairs (which interleave
                                // ahead via interleaveByHost).
                                if (hostCap.availablePermits == 0)
                                    AppLog.v("Caps", "pair=${item.placeholder.id} WAIT hostCap (host=$host)")
                                hostCap.withPermit {
                                // Non-blocking per-host gate (concurrent +
                                // per-minute window), BEFORE the outer global /
                                // fan-out caps. A capped pair yields the
                                // coroutine (delay, not Thread.sleep) so other
                                // hosts' pairs proceed; the Throttled mark drives
                                // the L1 "Throttled N" counter while it waits.
                                val releaser = acquireOrRequeue(
                                    host,
                                    onThrottled = { appViewModel.updateThrottledFanOutPairs { it + item.placeholder.id } },
                                    onCleared = { appViewModel.updateThrottledFanOutPairs { it - item.placeholder.id } }
                                )
                                try {
                                if (ApiCallCaps.global.availablePermits == 0)
                                    AppLog.v("Caps", "pair=${item.placeholder.id} WAIT global ${ApiCallCaps.snapshot().let { "${it.globalInFlight}/${it.globalMax}" }}")
                                ApiCallCaps.global.withPermit {
                                if (ApiCallCaps.fanOut.availablePermits == 0)
                                    AppLog.v("Caps", "pair=${item.placeholder.id} WAIT fanOut ${ApiCallCaps.snapshot().let { "${it.fanOutInFlight}/${it.fanOutMax}" }}")
                                ApiCallCaps.fanOut.withPermit {
                                AppLog.d("FanOut", "queued pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}")
                                    // Bail if the user deleted this pair (via the
                                    // L2 trash icon or deleteFanOutModel) while
                                    // we were queued on the throttle. Without
                                    // this, executeSecondaryTask still fires the
                                    // HTTP call and the saveIfStillPresent check
                                    // below would suppress the disk write — but
                                    // we'd still burn the API call and the
                                    // tokens. Checking here skips the call too.
                                    if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) {
                                        AppLog.d("FanOut", "skip pair ${item.placeholder.id} — deleted before launch")
                                        return@async
                                    }
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        appViewModel.updateRunningFanOutPairs { it + item.placeholder.id }
                                        val pairStart = System.currentTimeMillis()
                                        AppLog.d("FanOut", "→ pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}")
                                        try {
                                            val questionForPair = langCtx?.prompt ?: report.prompt
                                            val bodyForPair = langCtx?.bodies?.get(item.source.agentId)
                                                ?: (item.source.responseBody ?: "")
                                            val resolvedBase = resolveSecondaryPrompt(
                                                metaPrompt.text,
                                                question = questionForPair,
                                                results = "",
                                                count = sources.size,
                                                title = report.title
                                            )
                                            val resolved = resolvedBase.replace("@RESPONSE@", bodyForPair)
                                            executeSecondaryTask(
                                                context, reportId, SecondaryKind.META, metaPrompt,
                                                provider, item.answerer.model, resolved, aiSettings, report,
                                                targetLanguage = sourceLanguage,
                                                targetLanguageNative = langCtx?.native,
                                                fanOutSourceAgentId = item.source.agentId,
                                                existingPlaceholder = item.placeholder
                                            )
                                            // Note: the per-pair icon chain is NOT fired
                                            // inline anymore — it lives in a separate
                                            // user-launched batch (runFanIconsBatch).
                                            // The "Find Icons" button on the fan-out L1
                                            // launches it after the parent fan-out
                                            // completes.
                                        } finally {
                                            appViewModel.updateRunningFanOutPairs { it - item.placeholder.id }
                                            AppLog.d("FanOut", "← pair ans=${item.answerer.agentId} src=${item.source.agentId} ${System.currentTimeMillis() - pairStart}ms")
                                        }
                                    }
                                } // ApiCallCaps.fanOut.withPermit
                                } // ApiCallCaps.global.withPermit
                                } finally {
                                    releaser.release()
                                }
                                } // hostCap.withPermit
                            }
                            // Register the per-pair Job so deleteFanOutModel /
                            // rerunCompleteFanOut can target it for cancelAndJoin
                            // before deleting the row, closing the "result lands
                            // after delete and is silently dropped" race.
                            // Registration happens BEFORE start() so a concurrent
                            // delete can always find the Job.
                            fanOutPairJobs[item.placeholder.id] = deferred
                            deferred.invokeOnCompletion {
                                fanOutPairJobs.remove(item.placeholder.id, deferred)
                            }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
                    AppLog.i("FanOut", "← end \"${metaPrompt.name}\" (${pending.size} pairs in ${System.currentTimeMillis() - fanOutStartMs}ms)")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
        registerFanOutJob(reportId, metaPrompt.id, job)
        return job
    }

    /** Re-launch a list of fan-out pair placeholders. Re-uses the per-
     *  provider semaphore + running-pair bookkeeping pattern from
     *  [runFanOutPrompt], so the L1 progress bar / stats keep
     *  reflecting "running" once a permit is held and "queued" while a
     *  pair is waiting. The caller has already cleared each
     *  placeholder's content/errorMessage on disk (so the row reads as
     *  pending again). */
    private fun rerunFanOutPlaceholders(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        placeholders: List<SecondaryResult>
    ): Job? {
        if (placeholders.isEmpty()) return null
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    val sourceCount = successful.size
                    // All placeholders in one rerun batch share a
                    // single language by construction (fan-out is
                    // single-language). Lift the translation lookup
                    // once so we don't re-list secondaries per pair.
                    val rerunLang = placeholders.firstNotNullOfOrNull { it.targetLanguage }
                    data class LangCtx(val native: String?, val prompt: String, val bodies: Map<String, String>)
                    val rerunLangCtx: LangCtx? = rerunLang?.let { lang ->
                        val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                        val translates = allSecondaries.filter {
                            it.kind == SecondaryKind.TRANSLATE &&
                                it.targetLanguage == lang &&
                                !it.content.isNullOrBlank()
                        }
                        val native = translates.firstNotNullOfOrNull { it.targetLanguageNative }
                        val translatedPrompt = translates.firstOrNull {
                            it.translateSourceKind == "PROMPT" && it.translateSourceTargetId == "prompt"
                        }?.content ?: report.prompt
                        val bodies = translates
                            .filter { it.translateSourceKind == "AGENT" && !it.translateSourceTargetId.isNullOrBlank() }
                            .associate { it.translateSourceTargetId!! to (it.content ?: "") }
                        LangCtx(native, translatedPrompt, bodies)
                    }
                    // Per-pair pre-acquire mirrors runFanOutPrompt so the
                    // queued / running flip on the UI lines up with the
                    // permit being held. See that function for the full
                    // explanation. Per-host suspending caps mirror the
                    // ProviderThrottle concurrent limit so each host gets
                    // its own gate (vs a single global cap, which under-
                    // utilised fast providers).
                    val perHostCaps: Map<String, kotlinx.coroutines.sync.Semaphore> = placeholders
                        .mapNotNull { AppService.findById(it.providerId) }
                        .map { providerHost(it) }
                        .distinct()
                        .associateWith { host ->
                            val (_, concurrent) = ProviderThrottle.limitsFor(host)
                            kotlinx.coroutines.sync.Semaphore(concurrent)
                        }
                    coroutineScope {
                        // Interleave by host — same rationale as
                        // runFanOutPrompt: avoid having the first N
                        // launches all queue on one host's per-host
                        // cap while the outer caps sit idle.
                        interleaveByHost(placeholders) { ph ->
                            AppService.findById(ph.providerId)?.let { providerHost(it) }
                        }.map { ph ->
                            // CoroutineStart.LAZY mirrors runFanOutPrompt
                            // — see the comment there for why the start
                            // is gated on the post-registration .start()
                            // call below.
                            val deferred = async(start = CoroutineStart.LAZY) {
                                val provider = AppService.findById(ph.providerId) ?: return@async
                                val source = successful.firstOrNull { it.agentId == ph.fanOutSourceAgentId }
                                    ?: return@async
                                val host = providerHost(provider)
                                val hostCap = perHostCaps[host]
                                    ?: kotlinx.coroutines.sync.Semaphore(1)
                                // Acquire order: per-host cap → non-blocking
                                // ProviderThrottle gate (yields on a capped host
                                // instead of Thread.sleep) → outer global +
                                // fan-out. See runFanOutPrompt for the full
                                // rationale; same Throttled-mark for the UI.
                                hostCap.withPermit {
                                val releaser = acquireOrRequeue(
                                    host,
                                    onThrottled = { appViewModel.updateThrottledFanOutPairs { it + ph.id } },
                                    onCleared = { appViewModel.updateThrottledFanOutPairs { it - ph.id } }
                                )
                                try {
                                ApiCallCaps.global.withPermit {
                                ApiCallCaps.fanOut.withPermit {
                                AppLog.d("FanOut", "queued rerun ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}")
                                    if (!SecondaryResultStorage.exists(context, reportId, ph.id)) {
                                        AppLog.d("FanOut", "skip rerun ${ph.id} — deleted before launch")
                                        return@async
                                    }
                                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                                        appViewModel.updateRunningFanOutPairs { it + ph.id }
                                        val rerunStart = System.currentTimeMillis()
                                        AppLog.d("FanOut", "→ rerun pair ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}")
                                        try {
                                            val questionForPair = rerunLangCtx?.prompt ?: report.prompt
                                            val bodyForPair = rerunLangCtx?.bodies?.get(source.agentId)
                                                ?: (source.responseBody ?: "")
                                            val resolvedBase = resolveSecondaryPrompt(
                                                metaPrompt.text,
                                                question = questionForPair,
                                                results = "",
                                                count = sourceCount,
                                                title = report.title
                                            )
                                            val resolved = resolvedBase.replace("@RESPONSE@", bodyForPair)
                                            executeSecondaryTask(
                                                context, reportId, SecondaryKind.META, metaPrompt,
                                                provider, ph.model, resolved, aiSettings, report,
                                                targetLanguage = ph.targetLanguage,
                                                targetLanguageNative = ph.targetLanguageNative,
                                                fanOutSourceAgentId = source.agentId,
                                                existingPlaceholder = ph
                                            )
                                            // Icon chain no longer auto-fires here —
                                            // the user explicitly launches the
                                            // fan-icons batch via the L1 button.
                                        } finally {
                                            appViewModel.updateRunningFanOutPairs { it - ph.id }
                                            AppLog.d("FanOut", "← rerun pair ph=${ph.id} ${System.currentTimeMillis() - rerunStart}ms")
                                        }
                                    }
                                } // ApiCallCaps.fanOut.withPermit
                                } // ApiCallCaps.global.withPermit
                                } finally {
                                    releaser.release()
                                }
                                } // hostCap.withPermit
                            }
                            // Per-pair Job registration mirrors runFanOutPrompt
                            // — see the comment there for the cancel-on-delete
                            // race the registration closes.
                            fanOutPairJobs[ph.id] = deferred
                            deferred.invokeOnCompletion {
                                fanOutPairJobs.remove(ph.id, deferred)
                            }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
        registerFanOutJob(reportId, metaPrompt.id, job)
        return job
    }

    /** Reset the given rows on disk so they look queued again (clears
     *  content / errorMessage / token usage / costs / duration), then
     *  feed them into [rerunFanOutPlaceholders]. The cleared rows reuse
     *  their original placeholder ids so the UI doesn't see them
     *  vanish-and-reappear. */
    private fun resetAndRelaunch(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        rows: List<SecondaryResult>
    ): Job? {
        if (rows.isEmpty()) return null
        val reset = rows.map { r ->
            r.copy(
                content = null,
                errorMessage = null,
                tokenUsage = null,
                inputCost = null,
                outputCost = null,
                durationMs = null,
                icon = null,
                iconWinningTier = null,
                iconErrorMessage = null,
                iconInputTokens = 0,
                iconOutputTokens = 0,
                iconInputCost = 0.0,
                iconOutputCost = 0.0
            ).also { SecondaryResultStorage.save(context, it) }
        }
        return rerunFanOutPlaceholders(context, reportId, metaPrompt, reset)
    }

    /** Auto-resume every interrupted Translation, Fan-out, and
     *  single-call Meta/Rerank/Moderation run on the report. Fires
     *  on report open (replaces the previous mark-as-errored sweep).
     *
     *  - **Translation**: groups disk rows by `translationRunId`; for
     *    each runId that isn't already in [_translationRuns], calls
     *    [startMissingTranslations] which dispatches every expected
     *    item (prompt + successful agents + meta secondaries) that
     *    doesn't yet have a row.
     *  - **Fan-out**: groups stale fan-out placeholder rows by
     *    `metaPromptId`; for each one whose [com.ai.model.InternalPrompt]
     *    still exists in settings, calls [resumeStaleFanOutPairs].
     *  - **Single Meta/Rerank/Moderation**: walks stale rows where
     *    `fanOutSourceAgentId == null && fanInOf == null &&
     *    translationRunId == null` and re-issues each via
     *    [resumeStaleMetaPlaceholder] using the placeholder's persisted
     *    `metaPromptId`, `providerId`, `model`, and `secondaryScope`.
     *  - **Unrecoverable** (legacy rows missing fields, prompts since
     *    deleted, fan-in / model-fan-in rows whose substitution data
     *    can't be reconstructed): falls back to the existing
     *    "No data yet" marker so the row renders ❌
     *    and the user can manually retry.
     *
     *  Only rows interrupted by app death (content blank, errorMessage
     *  null, durationMs null) are touched — previously-errored rows are
     *  left as-is. Translation runs use the same `translationRunId`
     *  that was assigned at start, so the resume queues under the
     *  original group on the result page. */
    fun resumeStaleRunsForReport(
        context: Context,
        reportId: String
    ): Job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
        val running = appViewModel.runningFanOutPairs.value
        // Only runs that are actually in flight block step 1 — a
        // run that previously finished (or one a reconcile rebuilt
        // with finished=true) can still have disk placeholders that
        // step 1 must re-dispatch. Snapshot taken once; the
        // reconcile sweep (step 0 below) is awaited per-entry so by
        // the time step 1 reads this snapshot it's still pre-
        // reconcile — runs the reconcile is about to re-seed (via
        // its own startMissingTranslations call) are NOT in this
        // set, so step 1 would double-dispatch — runTranslationSubset's
        // persistedRowId dedupe handles that race cleanly.
        val activeTranslationRunIds = translation.translationRuns.value.values
            .filter { !it.isFinished && !it.cancelled }
            .map { it.runId }
            .toSet()
        val rows = SecondaryResultStorage.listForReport(context, reportId)
        val aiSettings = appViewModel.uiState.value.aiSettings

        // 0. Reconcile any in-memory translation runs for this report
        //    that are flagged !isFinished && !cancelled but have no
        //    live dispatch job. Those are demonstrably stuck — a
        //    previous flow's coroutine (cross-translate, restart-
        //    failed, etc.) died before flipping `finished` — and the
        //    disk-rebuild via reconcileStalledTranslationRun is safe
        //    because there's no worker mid-write to race.
        //
        //    Ordering: this step runs BEFORE step 1's
        //    startMissingTranslations dispatches. activeTranslationRunIds
        //    (snapshotted above) still includes these runIds, so step
        //    1's "skip if already active" guard keeps it from racing
        //    the reconcile's async rebuild. Same-session navigate-back
        //    catches the stuck state; fresh app start has an empty
        //    _translationRuns so this filter matches nothing and step
        //    1 owns the dispatch path as today.
        translation.translationRuns.value.values
            .filter {
                it.sourceReportId == reportId &&
                    !it.isFinished && !it.cancelled &&
                    translation.translationJobs[it.runId]?.isActive != true
            }
            .forEach { translation.reconcileStalledTranslationRun(context, reportId, it.runId) }

        // 1. Translation: walk every distinct translationRunId on disk
        //    that isn't actively running in memory and queue the
        //    missing items. startMissingTranslations needs at least one
        //    anchor row for the run; any runId with zero rows on disk
        //    can't be resumed (no provider/model/language to read).
        val translationRunIds = rows
            .filter { it.kind == SecondaryKind.TRANSLATE && it.translationRunId != null }
            .map { it.translationRunId!! }
            .distinct()
        translationRunIds.forEach { runId ->
            if (runId in activeTranslationRunIds) return@forEach
            translation.startMissingTranslations(context, reportId, runId)
        }

        // 2. Fan-out pairs: group stale per-pair rows by metaPromptId
        //    and dispatch resumeStaleFanOutPairs once per prompt.
        //    resumeStaleFanOutPairs is itself a no-op when the in-flight
        //    set already covers the run (defence-in-depth dedupe).
        val stalePairsByPromptId = rows
            .filter { it.kind == SecondaryKind.META &&
                it.fanOutSourceAgentId != null &&
                it.content.isNullOrBlank() &&
                it.errorMessage == null &&
                it.durationMs == null &&
                it.id !in running }
            .groupBy { it.metaPromptId }
        stalePairsByPromptId.forEach { (promptId, pairs) ->
            val prompt = promptId?.let { pid ->
                aiSettings.internalPrompts.firstOrNull { it.id == pid }
            }
            if (prompt != null) {
                resumeStaleFanOutPairs(context, reportId, prompt)
            } else {
                // Prompt deleted: mark each as ❌ so the row stops
                // spinning. The user can drop the row and re-pick a
                // prompt from scratch.
                pairs.forEach { markRowAsInterrupted(context, reportId, it.id, "Interrupted — fan-out prompt deleted") }
            }
        }

        // 3. Single-call Meta/Rerank/Moderation: re-issue each stale
        //    placeholder via executeSecondaryTask. Fan-in single
        //    (fanInOf != null) and model-fan-in (scopeProviderId != null)
        //    rows are skipped here — they go to the legacy mark-as-❌
        //    branch below since their substitution inputs aren't
        //    derivable from the placeholder alone.
        val staleSingleMeta = rows.filter {
            it.kind != SecondaryKind.TRANSLATE &&
                it.content.isNullOrBlank() &&
                it.errorMessage == null &&
                it.durationMs == null &&
                it.fanOutSourceAgentId == null &&
                it.fanInOf == null &&
                it.scopeProviderId == null &&
                it.scopeModel == null &&
                it.translationRunId == null &&
                it.id !in running &&
                it.metaPromptId != null &&
                aiSettings.internalPrompts.any { p -> p.id == it.metaPromptId } &&
                AppService.findById(it.providerId) != null
        }
        staleSingleMeta.forEach { row ->
            resumeStaleMetaPlaceholder(context, reportId, row)
        }

        // 5. Regenerate batch: ask the engine to reconcile any
        //    persisted RegenerateJob for this report. Idempotent;
        //    no-op when DONE / CANCELLED, revives a stale RUNNING
        //    orchestrator (process-kill recovery), or auto-resumes
        //    a PAUSED_ON_ERROR job whose offending row was fixed.
        regenerateBatchEngine.reconcile(context, reportId)

        // 4. Legacy fallback: any other stale row we can't reconstruct
        //    (fan-in single, model-fan-in, deleted prompt for a non-
        //    fan-out single meta, deleted provider) gets the honest ❌
        //    so it stops spinning.
        val handledIds = staleSingleMeta.map { it.id }.toSet() +
            stalePairsByPromptId.values.flatten().map { it.id }.toSet()
        rows.forEach { row ->
            if (row.errorMessage != null) return@forEach
            if (!row.content.isNullOrBlank()) return@forEach
            if (row.durationMs != null) return@forEach
            if (row.id in running) return@forEach
            if (row.id in handledIds) return@forEach
            // Translation rows in an active or newly-resumed run are
            // covered by startMissingTranslations; skip them here.
            if (row.kind == SecondaryKind.TRANSLATE &&
                row.translationRunId != null &&
                (row.translationRunId in activeTranslationRunIds ||
                    row.translationRunId in translationRunIds)) return@forEach
            // Anything still standing here is unrecoverable — mark ❌.
            markRowAsInterrupted(context, reportId, row.id, "No data yet")
        }
    }

    /** App-wide background resume sweep. Walks every report whose
     *  timestamp is within the last 7 days and calls
     *  [resumeStaleRunsForReport] on it — catches translation /
     *  fan-out / single-Meta placeholders for reports the user
     *  hasn't opened recently (the per-report on-open trigger
     *  inside [com.ai.ui.report.manage.ReportScreen] misses those).
     *
     *  Idempotent vs already-running work: the called function's
     *  guards (activeTranslationRunIds snapshot, the active-job
     *  check inside [reconcileStalledTranslationRun], the
     *  in-flight dedupe inside [resumeStaleFanOutPairs]) make
     *  this safe to retrigger.
     *
     *  Lifecycle: one loop at a time. The Job is stored on
     *  [AppViewModel.backgroundResumeSweepJob] so a re-creation
     *  of ReportViewModel (Activity config change tearing down
     *  the `remember{}` ReportViewModel inside AppNavHost)
     *  cancels the prior loop before starting the fresh one. */
    fun startBackgroundResumeSweep(context: Context) {
        appViewModel.backgroundResumeSweepJob?.cancel()
        appViewModel.backgroundResumeSweepJob = appViewModel.viewModelScope.launch(
            reportLogContext("background-resume-sweep")
        ) {
            while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                try {
                    resumeStaleRunsForRecentReports(context)
                } catch (e: Exception) {
                    AppLog.w("BgResumeSweep", "iteration failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    /** Walks every report newer than 7 days and triggers the
     *  per-report stale-runs resume. Sequential await — a
     *  parallel fan-out across 100 reports' disk scans would
     *  saturate IO without giving anything meaningful in return,
     *  and [resumeStaleRunsForReport] spawns its own per-runId
     *  launches anyway. */
    private suspend fun resumeStaleRunsForRecentReports(context: Context) {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val recent = withContext(Dispatchers.IO) {
            ReportStorage.getAllReports(context).filter { it.timestamp >= cutoff }
        }
        if (recent.isEmpty()) return
        AppLog.d("BgResumeSweep", "scanning ${recent.size} report${if (recent.size == 1) "" else "s"} (last 7 days)")
        recent.forEach { report ->
            // resumeStaleRunsForReport returns the orchestrating
            // Job; join it so the next iteration's IO doesn't
            // pile on top. The actual per-runId dispatches it
            // spawns are fire-and-forget inside their own
            // viewModelScope launches.
            resumeStaleRunsForReport(context, report.id).join()
        }
    }

    /** TOCTOU-safe re-read + save pair that flips a stuck placeholder
     *  into a terminal errored row. Used by [resumeStaleRunsForReport]'s
     *  fallback branch for rows that can't be reconstructed (legacy
     *  rows missing fields, prompts since deleted, fan-in single
     *  rows). Re-reads the row right before saving so an in-flight
     *  completion landing between our list scan and the save isn't
     *  clobbered. */
    private fun markRowAsInterrupted(
        context: Context,
        reportId: String,
        rowId: String,
        message: String
    ) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null) return
        if (!current.content.isNullOrBlank()) return
        if (current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(
            errorMessage = message,
            durationMs = 0
        ))
    }

    /** Re-issue a single interrupted META / RERANK / MODERATION
     *  placeholder. Looks up the [com.ai.model.InternalPrompt] by
     *  [SecondaryResult.metaPromptId] and the [AppService] by
     *  [SecondaryResult.providerId], decodes the persisted
     *  [SecondaryResult.secondaryScope], rebuilds the resolved prompt
     *  from the current report state, and calls [executeSecondaryTask]
     *  with the same placeholder so the in-place row transitions
     *  from ⏳ to ✅/❌ rather than being replaced by a fresh row. */
    internal fun resumeStaleMetaPlaceholder(
        context: Context,
        reportId: String,
        placeholder: SecondaryResult
    ): Job? {
        if (!resumingMetaIds.add(placeholder.id)) return null
        val promptId = placeholder.metaPromptId ?: run {
            resumingMetaIds.remove(placeholder.id); return null
        }
        val state = appViewModel.uiState.value
        val aiSettings = state.aiSettings
        val metaPrompt = aiSettings.internalPrompts.firstOrNull { it.id == promptId } ?: run {
            resumingMetaIds.remove(placeholder.id); return null
        }
        val provider = AppService.findById(placeholder.providerId) ?: run {
            resumingMetaIds.remove(placeholder.id); return null
        }
        val model = placeholder.model
        val scope = com.ai.data.SecondaryScope.decodeOrAllReports(placeholder.secondaryScope)
        val kind = placeholder.kind
        val lang = placeholder.targetLanguage
        val langNative = placeholder.targetLanguageNative
        val cat = "Report ${kind.name.lowercase()}: ${metaPrompt.name}"

        AppLog.i("Resume", "→ re-issue ${kind.name} \"${metaPrompt.name}\" report=$reportId row=${placeholder.id} via ${provider.id}/$model")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    // Same scope → includeIds resolution as runMetaPrompt.
                    val includeIds: Set<Int>? = when (scope) {
                        com.ai.data.SecondaryScope.AllReports -> null
                        is com.ai.data.SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scope.rerankResultId)
                            com.ai.data.extractTopRankedIds(rerank?.content, scope.count)?.toSet()
                        }
                        is com.ai.data.SecondaryScope.Manual -> {
                            val successful = report.agents.filter {
                                it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                            }
                            val ids = successful.mapIndexedNotNull { idx, a ->
                                if (a.agentId in scope.agentIds) idx + 1 else null
                            }
                            if (ids.isEmpty()) null else ids.toSet()
                        }
                    }
                    val successfulCount = if (includeIds != null) includeIds.size
                        else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, lang, includeIds)
                    val resolvedPrompt = resolveSecondaryPrompt(
                        metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                        count = successfulCount, title = report.title
                    )
                    val referenceLegend = if (metaPrompt.reference) buildReferenceLegend(report, includeIds) else null
                    executeSecondaryTask(
                        context, reportId, kind, metaPrompt,
                        provider, model, resolvedPrompt, aiSettings, report,
                        lang, langNative, referenceLegend,
                        existingPlaceholder = placeholder,
                        scopeEncoded = placeholder.secondaryScope
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                resumingMetaIds.remove(placeholder.id)
            }
        }
    }

    /** Re-enqueue fan-out pair placeholders that look stuck — the row is
     *  on disk as a pending placeholder (no content, no errorMessage)
     *  but its id is *not* in [AppViewModel.runningFanOutPairs]. This is the
     *  case after the app process is killed mid-run: the placeholders
     *  survive on disk but the launching coroutines are gone. The Fan out
     *  L1 screen calls this on entry. */
    fun resumeStaleFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        val key = fanOutJobKey(reportId, metaPrompt.id)
        // Drop the call if a previous resume scan for the same
        // (report, prompt) is in flight OR has dispatched a rerun
        // that hasn't finished yet. The lifetime of the key extends
        // past the scan body all the way through the dispatched
        // rerun Job — without that, a second resume scan firing
        // milliseconds after the first scan body exits could re-read
        // the same stale placeholders (still blank on disk, the
        // rerun hasn't filled them yet) and dispatch a duplicate
        // rerun, double-billing the user. Both
        // [resumeStaleRunsForReport] (report-open orchestrator) and
        // any direct caller share the same guard.
        if (!staleResumeScans.add(key)) return null
        val scanJob = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val running = appViewModel.runningFanOutPairs.value
            val stale = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPrompt.id &&
                        it.fanOutSourceAgentId != null &&
                        it.fanInOf == null &&
                        it.content.isNullOrBlank() &&
                        it.errorMessage == null &&
                        // durationMs is stamped on every successful + errored
                        // save and cleared by resetAndRelaunch. A row with
                        // durationMs set but blank content is a successful
                        // empty-body completion — re-firing it would
                        // duplicate-bill the user (same fix as the L1 stats
                        // classifier in SecondaryResultsScreen).
                        it.durationMs == null &&
                        it.id !in running
                }
            val rerunJob = if (stale.isNotEmpty()) {
                rerunFanOutPlaceholders(context, reportId, metaPrompt, stale)
            } else null
            // Wait for the rerun to fully complete before releasing
            // the dedup key — only at that point can we safely admit
            // a new scan; until then a fresh scan would re-read the
            // same placeholders (still blank) and re-dispatch.
            rerunJob?.join()
        }
        scanJob.invokeOnCompletion { staleResumeScans.remove(key) }
        return scanJob
    }

    /** Re-run a single fan-out pair row. Resets it on disk (clears
     *  content / errorMessage / token usage / cost / duration so it
     *  reads as queued again) and dispatches via [resetAndRelaunch].
     *  Used by the L3 "Fan out - pair" TitleBar's 🔄 reload icon. */
    fun rerunSingleFanOutPair(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pair: SecondaryResult
    ): Job? = resetAndRelaunch(context, reportId, metaPrompt, listOf(pair))

    /** Re-run fan-out pair rows that errored. Resets the rows on disk
     *  (clears errorMessage so they read as queued again) and dispatches
     *  via [rerunFanOutPlaceholders]. */
    fun rerunFailedFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null
            }
        return resetAndRelaunch(context, reportId, metaPrompt, failed)
    }

    /** Drop every errored fan-out pair row for this metaPromptId
     *  without re-firing. Wired to the L1 Fan out detail screen's
     *  "Remove failed items" button. */
    fun removeFailedFanOutPairs(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null
            }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
        // Removed rows weren't in runningFanOutPairs (errored is a
        // terminal state), so no need to update that set.
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** L2-scoped "Restart failed items". Re-fires only the errored
     *  pair rows for this metaPromptId where the active (provider,
     *  model) is the answerer — pairs where another model is the
     *  answerer are left alone so a partial failure in one row of L1
     *  doesn't drag in other models' calls. Throttled through the
     *  same [rerunFanOutPlaceholders] semaphore as the original run. */
    fun rerunFailedFanOutPairsForModel(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        providerId: String,
        model: String
    ): Job? {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
        if (failed.isEmpty()) return null
        return resetAndRelaunch(context, reportId, metaPrompt, failed)
    }

    /** L2-scoped "Remove failed items". Mirrors
     *  [removeFailedFanOutPairs] but only drops rows where the
     *  active (provider, model) is the answerer. */
    fun removeFailedFanOutPairsForModel(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
        val failed = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPrompt.id &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Drop every fan_out pair-row + every fan_in combine-row for this
     *  metaPromptId on this report, then kick a fresh
     *  [runFanOutPrompt]. Used by the Fan out L1 "Rerun the complete
     *  Fan out" button. */
    fun rerunCompleteFanOut(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt
    ): Job? {
        // Cancel + join any in-flight fan out run for this (report,
        // metaPrompt) before deleting placeholders. Without this,
        // surviving coroutines would call SecondaryResultStorage.save
        // on the just-deleted ids, resurrecting zombie rows beside
        // the freshly-created placeholders and double-billing.
        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            fanOutJobs[fanOutJobKey(reportId, metaPrompt.id)]?.let { existing ->
                existing.cancelAndJoin()
            }
            // Wipe every per-pair fan-out row tied to this metaPromptId
            // so the rerun starts from a clean grid. The previous filter
            // additionally tried to clean up fan_in rows via
            // `fanInOf == metaPrompt.id`, but fanInOf is set
            // to the FAN_IN prompt's id (line ~1326), never the
            // fan out prompt's id, so that clause was always false — dead
            // code disguised as cleanup. Fan-in rows on the same
            // report are left alone here; rerunning the fan out doesn't
            // know which fan-in derivatives came from this fan out
            // (no explicit link is stored), so deleting them all would
            // be over-aggressive. Users wanting a fully clean slate
            // can delete the fan-in rows individually.
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPrompt.id && it.fanOutSourceAgentId != null
                }
                .forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            runFanOutPrompt(context, reportId, metaPrompt)?.join()
        }
    }

    /** Drop every fan-out pair row for this report's metaPromptId where
     *  the model appears as the answerer (providerId, model match) OR
     *  as the source (the row's fanOutSourceAgentId points at an agent
     *  whose (provider, model) matches). Other Fan out runs on the same
     *  report (different metaPromptId) are untouched.
     *
     *  Returns a Job so the caller (or tests) can await completion of
     *  the cancellation sweep + disk delete. The cancellation phase
     *  cancelAndJoins every per-pair coroutine for the rows we're about
     *  to delete (registered in [fanOutPairJobs]), so a coroutine that's
     *  mid-HTTP or mid-save can't land a completion via
     *  saveIfStillPresent AFTER the delete (silently dropping a result
     *  the user paid for) or interleave a write with the delete. */
    fun deleteFanOutModel(
        context: Context,
        reportId: String,
        metaPromptId: String,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
        val report = ReportStorage.getReport(context, reportId) ?: return@launch
        // Provider ids are looked up via AppService.findById (case-
        // insensitive); the on-disk values can be either case
        // depending on when the row was written. Compare equalsIgnoreCase
        // so a UI delete with a re-cased id still matches.
        val matchingAgentIds = report.agents
            .filter { it.provider.equals(providerId, ignoreCase = true) && it.model == model }
            .map { it.agentId }
            .toSet()
        val toDelete = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
            .filter {
                it.metaPromptId == metaPromptId &&
                    it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    (
                        (it.providerId.equals(providerId, ignoreCase = true) && it.model == model) ||
                            (it.fanOutSourceAgentId in matchingAgentIds)
                    )
            }
        if (toDelete.isEmpty()) return@launch
        // Cancel + join the per-pair coroutines BEFORE deleting their
        // rows. Each cancelled coroutine either bails inside its
        // ProviderThrottle.acquire suspend point (no HTTP fired) or
        // throws CancellationException out of the HTTP suspend (no
        // saveIfStillPresent call). Either way, no zombie write lands
        // after we delete the row. join() waits for the finally
        // blocks (running-set removal + permit release) to run.
        val toDeleteIds = toDelete.map { it.id }
        toDeleteIds.forEach { id ->
            fanOutPairJobs[id]?.cancelAndJoin()
        }
        val costDelta = toDelete.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        toDeleteIds.forEach { SecondaryResultStorage.delete(context, reportId, it) }
        // The per-pair finally blocks already removed these ids from
        // runningFanOutPairs during join, but a stale-cache window
        // could leave the UI showing them as "running" until the next
        // refresh tick. Force-prune the set so the trash icon's
        // visual effect is immediate.
        val deletedIds = toDeleteIds.toSet()
        appViewModel.updateRunningFanOutPairs { it - deletedIds }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Combines a fan-out run's per-pair responses plus every
     *  successful agent's response body into a single combined report
     *  via a single chat call on [pick]. The template's iterable block
     *  `\n\n***Report*** @REPORT@@RESPONSES@` is expanded once per
     *  successful (source) agent, with its `@RESPONSES@` populated
     *  from the latest fan-out response row of every other answerer.
     *
     *  Persists one [SecondaryResult] with kind=META and
     *  fanInOf=metaPrompt.id, so the fan out detail screen can show
     *  it inline above the L1 list while the View bucket still buckets
     *  by `metaPromptName`.
     */
    fun runFanInPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pick: Pair<AppService, String>,
        /** English-name source language inherited from the parent
         *  fan-out (null = Original). When non-null, every @-token the
         *  fan-in template substitutes — @QUESTION@, @TITLE@, and the
         *  per-source @REPORT@ body — comes from the matching
         *  translation rows. The persisted combined-report is also
         *  tagged with the language so it groups under that section
         *  in the report list. */
        sourceLanguage: String? = null
    ): Job? {
        AppLog.i("FanIn", "→ start \"${metaPrompt.name}\" report=$reportId via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    // Wait for any in-flight fan out runs on this report
                    // to finish before reading their per-pair rows —
                    // otherwise the combine call would build its
                    // payload from an arbitrary partial subset of
                    // responses while the user thinks the report is
                    // "all of them". Each fan out run is keyed by its
                    // own metaPromptId; a snapshot of the active jobs
                    // for THIS report is enough.
                    val fanOutJobsForReport = fanOutJobs.entries
                        .filter { it.key.startsWith("$reportId|") }
                        .map { it.value }
                    fanOutJobsForReport.forEach { it.join() }
                    val fanOutRows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                        .filter { it.fanOutSourceAgentId != null }
                        // Drop errored rows before bucketing — without
                        // this, the firstOrNull pick below grabs the
                        // OLDEST row (we sort ascending), which is the
                        // failed first attempt. A successful retry
                        // landed afterwards is then ignored. Filtering
                        // up front leaves only valid responses in the
                        // bucket, so the firstOrNull picks the oldest
                        // successful one — closer to the user's
                        // expected "completed run" semantics.
                        .filter { it.errorMessage == null && !it.content.isNullOrBlank() }
                    // Bucket fan out rows by (providerId, model, sourceAgentId).
                    // Two report rows can legitimately share (provider,
                    // model) — e.g. an Agent UUID row and a swarm:provider:model
                    // row pointing at the same model under different
                    // agentIds. Bucketing into a list keeps every
                    // matching row, and the answerer-side resolution
                    // below picks the best-fit row per (other.provider,
                    // other.model, other.agentId, source.agentId).
                    val byPair = LinkedHashMap<String, MutableList<SecondaryResult>>()
                    fanOutRows.sortedBy { it.timestamp }.forEach { r ->
                        val src = r.fanOutSourceAgentId ?: return@forEach
                        byPair.getOrPut("${r.providerId}|${r.model}|$src") { mutableListOf() }.add(r)
                    }
                    val consumed = HashSet<String>()
                    // Translation context for the parent fan-out's
                    // language. Null when sourceLanguage is null
                    // (Original); otherwise carries the translated
                    // prompt/title/native + per-agent translated body
                    // map. Built once per call from the secondaries we
                    // already listed for the fan-out lookup.
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    val langCtx = lookupLanguageTranslations(report, allSecondaries, sourceLanguage)
                    val perReport: List<Pair<String, List<String>>> = successful.map { source ->
                        val fanOutResponses = successful.mapNotNull other@{ other ->
                            if (other.agentId == source.agentId) return@other null
                            // Pick the next un-consumed row for this
                            // (provider, model, source) bucket so two
                            // distinct other-agents sharing (provider,
                            // model) each get their own response.
                            val bucket = byPair["${other.provider}|${other.model}|${source.agentId}"]
                                ?: return@other null
                            val row = bucket.firstOrNull { it.id !in consumed }
                                ?: return@other null
                            consumed += row.id
                            if (row.errorMessage != null) return@other null
                            val c = row.content
                            if (c.isNullOrBlank()) null else c.trim()
                        }
                        // Each @REPORT@ slot: translated body when
                        // available, original otherwise. Without this,
                        // a Dutch fan-in feeds the picked model the
                        // Dutch @RESPONSES@ but English @REPORT@ — the
                        // assistant typically replies in English and
                        // the row ends up mismatched against its tag.
                        val sourceBody = langCtx?.bodiesByAgentId?.get(source.agentId)
                            ?: source.responseBody?.trim().orEmpty()
                        sourceBody to fanOutResponses
                    }
                    if (perReport.all { it.second.isEmpty() }) {
                        val (provider, model) = pick
                        val agentName = "${provider.id} / ${shortModelName(model)}"
                        SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
                        ) {
                            it.copy(
                                metaPromptId = metaPrompt.id,
                                metaPromptName = metaPrompt.name,
                                fanInOf = metaPrompt.id,
                                errorMessage = "No fan-out responses available — run the fan-out prompt first."
                            )
                        }
                        return@withTracerTags
                    }
                    val resolved = resolveFanInPrompt(
                        template = metaPrompt.text,
                        question = langCtx?.prompt ?: report.prompt,
                        count = perReport.size,
                        fanOutCount = (perReport.size - 1).coerceAtLeast(0),
                        perReport = perReport,
                        title = langCtx?.title ?: report.title
                    )
                    val (provider, model) = pick
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = langCtx?.native,
                        fanInOf = metaPrompt.id
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Model-scoped variant of [runFanInPrompt]. Combines only the
     *  fan-out entries that involve the specified active model
     *  (provider, modelName) into one combined-report row, instead
     *  of the legacy "total" fan_in that combines every entry on the
     *  report. Driven by the three new categories `initiator`
     *  (active is source), `requester` (active is answerer), and
     *  `model` (both). The resulting row carries scopeProviderId /
     *  scopeModel so the L2 page can filter to its own model's rows.
     *
     *  Math for 10 models with active = A:
     *    initiator — 9 fan-out responses where A is the source.
     *               @RESPONDERS@ holds those bodies; @INITIATOR@ is
     *               A's own report response.
     *    requester — 9 pairs (other_i's report response, A's fan-out
     *               response to other_i). @RESPONDER_PAIRS@ holds them.
     *    model — both blocks populated. */
    fun runModelFanInPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        pick: Pair<AppService, String>,
        activeProviderId: String,
        activeModel: String,
        /** Inherited from the parent fan-out; same semantics as
         *  [runFanInPrompt]. Drives the substituted text for
         *  @QUESTION@, @TITLE@, @INITIATOR@, and the source-body
         *  half of every @RESPONDER_PAIRS@ entry. */
        sourceLanguage: String? = null
    ): Job? {
        AppLog.i("ModelFanIn", "→ start \"${metaPrompt.name}\" report=$reportId active=$activeProviderId/$activeModel via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    // Wait for in-flight fan-out runs on this report —
                    // same race-free pattern as runFanInPrompt.
                    val fanOutJobsForReport = fanOutJobs.entries
                        .filter { it.key.startsWith("$reportId|") }
                        .map { it.value }
                    fanOutJobsForReport.forEach { it.join() }

                    // Resolve the active model's agents on this report.
                    // Swarm with duplicate (provider, model) members can
                    // produce multiple agentIds for the same model — we
                    // count rows from any of them.
                    val activeAgents = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS
                            && it.provider == activeProviderId
                            && it.model == activeModel
                            && !it.responseBody.isNullOrBlank()
                    }
                    val activeAgentIds = activeAgents.map { it.agentId }.toHashSet()
                    // Translation context inherited from the parent
                    // fan-out. When set, @INITIATOR@ and the source-
                    // body half of every @RESPONDER_PAIRS@ entry come
                    // from the per-agent translation rows.
                    val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                    val langCtx = lookupLanguageTranslations(report, allSecondaries, sourceLanguage)
                    val initiatorBody = activeAgents.firstOrNull()?.let { agent ->
                        langCtx?.bodiesByAgentId?.get(agent.agentId)
                            ?: agent.responseBody?.trim().orEmpty()
                    }.orEmpty()

                    // Per-pair fan-out rows on this report.
                    val fanOutRows = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                        .filter { it.fanOutSourceAgentId != null && it.fanInOf == null }
                        .filter { it.errorMessage == null && !it.content.isNullOrBlank() }
                        .sortedBy { it.timestamp }

                    // The unified fan-in-model template can use both
                    // @RESPONDERS@ and @RESPONDER_PAIRS@; resolve both
                    // unconditionally and let the prompt body opt in
                    // by reference.

                    // @RESPONDERS@: rows where the active model is the
                    // SOURCE (others responded TO active's report). One
                    // row per other answerer; bucket and pick the
                    // freshest non-errored row per (other-provider,
                    // other-model, source-agent) triple.
                    val responders: List<String> = fanOutRows
                        .filter { it.fanOutSourceAgentId in activeAgentIds }
                        .groupBy { "${it.providerId}|${it.model}" }
                        .values
                        .mapNotNull { bucket -> bucket.lastOrNull()?.content?.trim()?.takeIf { it.isNotBlank() } }

                    // @RESPONDER_PAIRS@: rows where the active model is
                    // the ANSWERER (active responded to others). Each
                    // pair = (other's report response, active's fan-out
                    // response). Bucket by source agent.
                    val responderPairs: List<Pair<String, String>> = fanOutRows
                        .filter { it.providerId == activeProviderId && it.model == activeModel }
                        .groupBy { it.fanOutSourceAgentId.orEmpty() }
                        .mapNotNull { (srcAgentId, bucket) ->
                            if (srcAgentId.isBlank()) return@mapNotNull null
                            val source = report.agents.firstOrNull { it.agentId == srcAgentId } ?: return@mapNotNull null
                            val srcBody = langCtx?.bodiesByAgentId?.get(source.agentId)
                                ?: source.responseBody?.trim().orEmpty()
                            val resp = bucket.lastOrNull()?.content?.trim().orEmpty()
                            if (srcBody.isBlank() || resp.isBlank()) null else srcBody to resp
                        }

                    val (provider, model) = pick

                    // Bail with an error placeholder when there's
                    // nothing to combine — same shape as the legacy
                    // fan_in does for empty fan-out states.
                    val nothingToCombine = responders.isEmpty() && responderPairs.isEmpty()
                    if (nothingToCombine) {
                        val agentName = "${provider.id} / ${shortModelName(model)}"
                        SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
                        ) {
                            it.copy(
                                metaPromptId = metaPrompt.id,
                                metaPromptName = metaPrompt.name,
                                fanInOf = metaPrompt.id,
                                scopeProviderId = activeProviderId,
                                scopeModel = activeModel,
                                errorMessage = "No fan-out responses available for ${activeProviderId} / ${activeModel} — run the fan-out prompt first."
                            )
                        }
                        return@withTracerTags
                    }

                    val resolved = com.ai.data.resolveModelFanInPrompt(
                        template = metaPrompt.text,
                        question = langCtx?.prompt ?: report.prompt,
                        title = langCtx?.title ?: report.title,
                        initiatorBody = initiatorBody,
                        responders = responders,
                        responderPairs = responderPairs
                    )

                    // Pre-create the placeholder so the scope fields
                    // are persisted from the start (executeSecondaryTask
                    // doesn't take scopeProviderId / scopeModel — we
                    // pass the staged row in via existingPlaceholder).
                    // Language tag goes here too so the row groups
                    // under the right language section.
                    val langSuffix = sourceLanguage?.let { " [$it]" } ?: ""
                    val agentName = "${provider.id} / ${shortModelName(model)}$langSuffix"
                    val placeholder = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.META, provider.id, model, agentName
                    ) {
                        it.copy(
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            fanInOf = metaPrompt.id,
                            scopeProviderId = activeProviderId,
                            scopeModel = activeModel,
                            targetLanguage = sourceLanguage,
                            targetLanguageNative = langCtx?.native
                        )
                    }

                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
                        targetLanguage = sourceLanguage,
                        targetLanguageNative = langCtx?.native,
                        fanInOf = metaPrompt.id,
                        existingPlaceholder = placeholder
                    )
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Build a fresh AI Report from the L2 active model's fan-out
     *  conversation. Promotes "active model said X, the others
     *  responded" into a standalone report:
     *
     *  - prompt = active model's report response on the source
     *    report (verbatim)
     *  - title  = "From: <orig title> · <provider> / <model>"
     *  - agents = one synthesised [ReportAgent] per fan-out row
     *    where active is the source (i.e. fanOutSourceAgentId in
     *    activeAgentIds). Carries the answerer's body, error,
     *    tokens, cost, and durationMs straight through so the
     *    new result page reads like a normal completed report.
     *
     *  Buckets per (other-provider, other-model, source-agent),
     *  keeping the latest row — same dedup the L2 page uses for
     *  its on-screen list, so retries don't pile up.
     *
     *  Returns the new report id on success, null when there's
     *  nothing to copy (active agent missing / no fan-out rows /
     *  active's responseBody blank). */
    suspend fun createReportFromFanOut(
        context: Context,
        sourceReportId: String,
        activeProviderId: String,
        activeModel: String
    ): String? = withContext(Dispatchers.IO) {
        val source = ReportStorage.getReport(context, sourceReportId) ?: return@withContext null
        val activeAgents = source.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS
                && it.provider == activeProviderId && it.model == activeModel
                && !it.responseBody.isNullOrBlank()
        }
        val active = activeAgents.firstOrNull() ?: return@withContext null
        val activeAgentIds = activeAgents.map { it.agentId }.toHashSet()

        val raw = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.META)
            .filter { it.fanOutSourceAgentId in activeAgentIds && it.fanInOf == null }
        val bucketed = LinkedHashMap<String, SecondaryResult>()
        raw.sortedBy { it.timestamp }.forEach { r ->
            bucketed["${r.providerId}|${r.model}|${r.fanOutSourceAgentId}"] = r
        }
        val rows = bucketed.values.toList()
        if (rows.isEmpty()) return@withContext null

        val newAgents = rows.map { row ->
            ReportAgent(
                agentId = java.util.UUID.randomUUID().toString(),
                agentName = "${row.providerId} / ${shortModelName(row.model)}",
                provider = row.providerId,
                model = row.model,
                reportStatus = if (row.errorMessage != null) ReportStatus.ERROR else ReportStatus.SUCCESS,
                responseBody = row.content,
                errorMessage = row.errorMessage,
                tokenUsage = row.tokenUsage,
                cost = ((row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)).takeIf { it > 0.0 },
                durationMs = row.durationMs
            )
        }

        val now = System.currentTimeMillis()
        val srcTitle = source.title.ifBlank { "AI Report" }
        val newTitle = "From: $srcTitle · $activeProviderId / $activeModel"
        val newReport = Report(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = now,
            title = newTitle,
            prompt = active.responseBody.orEmpty(),
            agents = newAgents.toMutableList(),
            completedAt = now,
            sourceReportId = sourceReportId,
            totalCost = newAgents.mapNotNull { it.cost }.sum()
        )
        // Mirror the source's icon + language visible state onto the
        // new report. Without this the inline icon / language rows on
        // the new report's Report - manage screen would sit with a
        // spinning ⏳ forever (icon null + error null = "generating",
        // languageName null + error null = "detecting"), even though
        // nothing is actually running — the fan-out derivation never
        // schedules those API calls. Same shape as
        // ReportStorage.copyReport's icon + language carry-over.
        // Costs / tokens / trace files stay at defaults: those calls
        // were paid for by the source.
        newReport.icon = source.icon
        newReport.iconErrorMessage = source.iconErrorMessage
        newReport.languageName = source.languageName
        newReport.languageIcon = source.languageIcon
        newReport.languageIconErrorMessage = source.languageIconErrorMessage
        ReportStorage.persistReport(context, newReport)
        newReport.id
    }

    fun runMetaPrompt(
        context: Context,
        reportId: String,
        metaPrompt: com.ai.model.InternalPrompt,
        picks: List<Pair<AppService, String>>,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        languageScope: SecondaryLanguageScope = SecondaryLanguageScope.AllPresent
    ): Job? {
        if (picks.isEmpty()) return null
        val kind = SecondaryKind.META
        AppLog.i("Meta", "→ start \"${metaPrompt.name}\" report=$reportId — ${picks.size} pick(s)")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }

        return appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            // Tag every API call this batch makes with the parent
            // report's id and a Meta-prompt-name category. Without the
            // reportId tag the resulting trace files would land with
            // reportId=null and the report's JSON export wouldn't
            // pull them in. withTracerTags saves and restores both
            // values so this works correctly when nested.
            val cat = "Report meta: ${metaPrompt.name}"
            try {
              withTracerTags(reportId = reportId, category = cat) {
                val state = appViewModel.uiState.value
                val aiSettings = state.aiSettings
                val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                // Bump the parent report's timestamp so it sorts to the top
                // of the History list — adding a meta result is a real
                // update to the report, not a passive read.
                ReportStorage.bumpReportTimestamp(context, reportId)
                // Resolve scope: AllReports → no filter; TopRanked → parse
                // the chosen rerank, take the top-N original ids. If parsing
                // fails (legacy / malformed rerank output) fall back to
                // AllReports rather than blocking the user.
                val includeIds: Set<Int>? = when (scopeChoice) {
                    SecondaryScope.AllReports -> null
                    is SecondaryScope.TopRanked -> {
                        val rerank = SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                        val ids = extractTopRankedIds(rerank?.content, scopeChoice.count)
                        if (ids.isNullOrEmpty()) null else ids.toSet()
                    }
                    is SecondaryScope.Manual -> {
                        // Manual is expressed as agentIds; convert to the
                        // 1-based original-id indices used by buildLanguageInputs
                        // / buildResultsBlock.
                        val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        val ids = successful.mapIndexedNotNull { idx, a ->
                            if (a.agentId in scopeChoice.agentIds) idx + 1 else null
                        }
                        if (ids.isEmpty()) null else ids.toSet()
                    }
                }
                val successfulCount = if (includeIds != null) includeIds.size
                    else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }

                // Multi-language fan-out: one batch per language present
                // on the report. The Original language is encoded as null
                // and the SecondaryResult.targetLanguage stays null for
                // it; translations get the human English name.
                val translationLanguages = run {
                    val nativeByLang = LinkedHashMap<String, String?>()
                    allSecondaries
                        .filter { it.kind == SecondaryKind.TRANSLATE && !it.targetLanguage.isNullOrBlank() }
                        .forEach { tr ->
                            val l = tr.targetLanguage!!
                            if (l !in nativeByLang) nativeByLang[l] = tr.targetLanguageNative
                        }
                    when (languageScope) {
                        SecondaryLanguageScope.AllPresent -> nativeByLang
                        is SecondaryLanguageScope.Selected -> {
                            val filtered = LinkedHashMap<String, String?>()
                            nativeByLang.forEach { (k, v) -> if (k in languageScope.languages) filtered[k] = v }
                            filtered
                        }
                    }
                }
                // The original (untranslated) source is included by
                // default and when the user kept it ticked under
                // Selected. The empty-string sentinel "" in
                // SecondaryLanguageScope.Selected.languages signals
                // "include original".
                val includeOriginal = when (languageScope) {
                    SecondaryLanguageScope.AllPresent -> true
                    is SecondaryLanguageScope.Selected -> "" in languageScope.languages
                }
                val languages: List<Pair<String?, String?>> =
                    (if (includeOriginal) listOf<Pair<String?, String?>>(null to null) else emptyList()) +
                    translationLanguages.map { (lang, native) -> lang to native }

                // Reference legend — built when the prompt's reference
                // flag is on. Computed once per batch.
                val referenceLegend = if (metaPrompt.reference)
                    buildReferenceLegend(report, includeIds) else null

                // Multi-language meta: instead of fanning out N×M
                // independent META rows (one per language × pick), run
                // the meta ONCE in a single "seed" language and then
                // append cross-translation items to each other
                // selected language's existing translation run. The
                // seed prefers Original when it's in the selection,
                // otherwise the first non-original language. Picks
                // still produce M META rows in the seed language.
                if (languages.isEmpty()) return@withTracerTags
                val seedLang: Pair<String?, String?> =
                    if (languages.any { it.first == null }) null to null
                    else languages.first()
                val nonSeedLanguages = languages.filter { it != seedLang }

                // Phase 1: seed-language meta run (the only META rows
                // produced by this invocation).
                val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, seedLang.first, includeIds)
                // @TITLE@: prefer the per-language TITLE translation
                // row when one exists; fall back to the original title.
                // Without this, a Dutch seed run would send Dutch
                // QUESTION + RESULTS but an English title — the model
                // tends to mirror the title's language in its reply.
                val seedTitle = lookupLanguageTranslations(report, allSecondaries, seedLang.first)?.title
                    ?: (report.title ?: "")
                val resolvedPrompt = resolveSecondaryPrompt(
                    metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                    count = successfulCount, title = seedTitle
                )
                // Pre-create placeholders so we know each row's id up
                // front — needed for phase 2's cross-translate items
                // which reference these rows by id.
                val seedPlaceholders: List<SecondaryResult> = picks.map { (provider, model) ->
                    val langSuffix = seedLang.first?.let { " [$it]" } ?: ""
                    val agentName = "${provider.id} / ${shortModelName(model)}$langSuffix"
                    SecondaryResultStorage.create(
                        context, reportId, kind, provider.id, model, agentName
                    ) {
                        it.copy(
                            targetLanguage = seedLang.first,
                            targetLanguageNative = seedLang.second,
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            secondaryScope = scopeChoice.encode()
                        )
                    }
                }
                coroutineScope {
                    picks.zip(seedPlaceholders).map { (pick, ph) ->
                        async {
                            ApiCallCaps.global.withPermit {
                                executeSecondaryTask(
                                    context, reportId, kind, metaPrompt,
                                    pick.first, pick.second, resolvedPrompt, aiSettings, report,
                                    seedLang.first, seedLang.second, referenceLegend,
                                    existingPlaceholder = ph,
                                    scopeEncoded = scopeChoice.encode()
                                )
                            }
                        }
                    }.awaitAll()
                }

                // Phase 2: cross-translate the seed METAs to each
                // non-seed language. Re-read each placeholder from disk
                // so we pick up the now-saved content / errorMessage.
                // Errored seed rows are skipped — nothing useful to
                // cross-translate.
                if (nonSeedLanguages.isNotEmpty()) {
                    val completedSeedMetas = seedPlaceholders.mapNotNull { ph ->
                        SecondaryResultStorage.get(context, reportId, ph.id)
                    }.filter { !it.content.isNullOrBlank() && it.errorMessage == null }
                    if (completedSeedMetas.isNotEmpty()) {
                        val secondariesAfter = SecondaryResultStorage.listForReport(context, reportId)
                        for ((lang, langNative) in nonSeedLanguages) {
                            if (lang == null) continue
                            translation.addCrossTranslationItems(
                                context, reportId,
                                targetLanguageName = lang,
                                targetLanguageNative = langNative ?: lang,
                                sourceMetas = completedSeedMetas,
                                allSecondaries = secondariesAfter
                            )
                        }
                    }
                }
              }
            } finally {
                // activeSecondaryBatches must drop even if the tag
                // block throws or is cancelled mid-batch — otherwise
                // the Meta screen's hourglass / poll loop would think
                // a batch is still in flight forever.
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
            }
        }
    }


    internal suspend fun executeSecondaryTask(
        context: Context, reportId: String, kind: SecondaryKind,
        metaPrompt: com.ai.model.InternalPrompt,
        provider: AppService, model: String, resolvedPrompt: String, aiSettings: Settings,
        report: Report,
        targetLanguage: String? = null,
        targetLanguageNative: String? = null,
        referenceLegend: String? = null,
        fanOutSourceAgentId: String? = null,
        fanInOf: String? = null,
        /** Optional pre-created placeholder. When the caller has staged
         *  a row up-front (fan-out does this so all N×(M-1) pair
         *  rows surface as ⏳ on the fan out detail screen the moment the
         *  run starts) we run against that row instead of creating a
         *  fresh one — otherwise the placeholder duplicates and the
         *  pre-created row never gets a result. */
        existingPlaceholder: SecondaryResult? = null,
        scopeEncoded: String? = null
    ) {
        val apiKey = aiSettings.getApiKey(provider)
        val langSuffix = targetLanguage?.let { " [$it]" } ?: ""
        val agentName = "${provider.id} / ${shortModelName(model)}$langSuffix"
        val placeholder = existingPlaceholder ?: SecondaryResultStorage.create(
            context, reportId, kind, provider.id, model, agentName
        ) {
            it.copy(
                targetLanguage = targetLanguage,
                targetLanguageNative = targetLanguageNative,
                metaPromptId = metaPrompt.id,
                metaPromptName = metaPrompt.name,
                fanOutSourceAgentId = fanOutSourceAgentId,
                fanInOf = fanInOf,
                secondaryScope = scopeEncoded
            )
        }

        // Model benched on a >1h 429 by an earlier call — skip the
        // doomed call but keep the row as a visible red error (don't
        // delete it). runOnePair re-reads the row, sees the
        // errorMessage, and marks the pair ERROR rather than dropping
        // it; a single secondary run shows the same red error row.
        if (isBenched(provider, model)) {
            AppLog.w("Secondary", "skip benched ${provider.id}/$model — marking row ${placeholder.id} errored")
            SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                errorMessage = "${provider.id}/$model is rate-limited (benched) — skipped"
            ))
            return
        }

        // Moderation runs through the dedicated /v1/moderations
        // endpoint — one batch call classifying every report response.
        // No chat prompt, no per-response loop here (the API takes the
        // input array and returns one result per input). The structured
        // JSON content is rendered as a flagged-categories table by the
        // detail screen.
        if (kind == SecondaryKind.MODERATION) {
            // When the caller picked a target language on the scope
            // screen, classify the TRANSLATED bodies instead of the
            // originals — otherwise a "Run moderation in Dutch" click
            // still moderates the English text. Falls back to the
            // original body per-agent when a translation row is
            // missing so a partial set still classifies coherently.
            val translatedBodies: Map<String, String>? = targetLanguage?.let { lang ->
                val secondaries = SecondaryResultStorage.listForReport(context, reportId)
                lookupLanguageTranslations(report, secondaries, lang)?.bodiesByAgentId
            }
            val responses = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { agent -> translatedBodies?.get(agent.agentId) ?: agent.responseBody!! }
            val (_, r) = com.ai.data.callModerationApi(provider, apiKey, model, responses)
            // Persist Mistral's reported token usage + per-token cost
            // so the result row shows cents like the other meta runs.
            // Falls through to no-cost when the API didn't report usage.
            val tu = r.tokenUsage
            val pricing = PricingCache.getPricing(context, provider, model)
            val inCost = tu?.let { it.inputTokens * pricing.promptPrice }
            val outCost = tu?.let { it.outputTokens * pricing.completionPrice }
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                tokenUsage = tu,
                inputCost = inCost,
                outputCost = outCost,
                durationMs = r.durationMs
            ))
            // Skip usage-stats too if the row was deleted while in
            // flight — the user dropped this run, so we shouldn't bill
            // the per-provider token counters for it either.
            if (saved && r.errorMessage == null) {
                val inT = tu?.inputTokens ?: 0
                val outT = tu?.outputTokens ?: 0
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, inT, outT, inT + outT, kind = "moderation"
                )
            }
            return
        }

        // Rerank-typed models (Cohere rerank-v3.5 etc.) don't have a chat
        // endpoint — they take query + documents and return relevance
        // scores. Detect that and route to the dedicated rerank API,
        // converting the response back to the structured JSON the rest
        // of the system already consumes (HTML export, Top-Ranked scope).
        val isRerankApiPath = kind == SecondaryKind.RERANK
            && aiSettings.getModelType(provider, model) == com.ai.data.ModelType.RERANK
        if (isRerankApiPath) {
            val docs = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { it.responseBody!! }
            val r = com.ai.data.callRerankApi(provider, apiKey, model, report.prompt, docs)
            // Per-query pricing: cost = billedSearchUnits × perQueryPrice.
            // Stored on inputCost so the report cost table renders
            // alongside chat/summarize rows. The provider may omit the
            // billed-units block on an error response — fall back to 1
            // unit per call so cost is at least roughly tracked.
            val pricing = PricingCache.getPricing(context, provider, model)
            val units = r.billedSearchUnits ?: if (r.errorMessage == null) 1 else 0
            val rerankCost = if (units > 0) units * pricing.perQueryPrice else null
            val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                inputCost = rerankCost,
                durationMs = r.durationMs
            ))
            if (saved && r.errorMessage == null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, 0, 0, 0, kind = "rerank", searchUnits = units
                )
            }
            return
        }

        val agent = Agent(
            id = "secondary:${kind.name}:${provider.id}:$model",
            name = agentName, provider = provider, model = model, apiKey = apiKey
        )
        val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
        val start = System.currentTimeMillis()
        val response = try {
            appViewModel.repository.analyzeWithAgent(
                agent, "", resolvedPrompt, AgentParameters(), null, context, baseUrl
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't translate cancellation into a fake error stored on
            // the placeholder — re-throw so structured concurrency
            // works. (Used to surface as "StandaloneCoroutine was
            // canceled" on the Meta detail screen because the generic
            // catch below swallowed it.)
            throw e
        } catch (e: Exception) {
            AnalysisResponse(provider, null, e.message ?: "Unknown error", agentName = agentName)
        }
        val duration = System.currentTimeMillis() - start
        val pricing = PricingCache.getPricing(context, provider, model)
        val tu = response.tokenUsage
        val inCost = tu?.let { it.inputTokens * pricing.promptPrice }
        val outCost = tu?.let { it.outputTokens * pricing.completionPrice }

        // For chat-type Meta prompts with reference=true, append the
        // deterministic legend so each [N] in the model's prose has a
        // "[N] = Provider / Model" entry at the bottom regardless of
        // whether the model bothered to include one. Only when the
        // call actually produced content — an error response is left
        // untouched so the failure is visible.
        val finalContent = if (response.error == null
                && !response.analysis.isNullOrBlank() && !referenceLegend.isNullOrBlank()) {
            "${response.analysis.trimEnd()}\n\n---\n\n## References\n\n$referenceLegend\n"
        } else response.analysis
        // A benched-on-this-call >1h 429 is no longer special-cased —
        // it flows through the normal save path below so the row
        // stays as a visible red error carrying the real API error,
        // instead of silently disappearing.
        val saved = SecondaryResultStorage.saveIfStillPresent(context, placeholder.copy(
            content = finalContent,
            errorMessage = response.error,
            tokenUsage = tu,
            inputCost = inCost,
            outputCost = outCost,
            durationMs = duration
        ))

        if (saved && response.error == null && tu != null) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, tu.inputTokens, tu.outputTokens, tu.totalTokens,
                kind = when (kind) {
                    SecondaryKind.RERANK -> "rerank"
                    SecondaryKind.META -> "meta"
                    SecondaryKind.MODERATION -> "moderation"
                    SecondaryKind.TRANSLATE -> "translate"
                }
            )
        }
    }

    fun deleteSecondaryResult(context: Context, reportId: String, resultId: String) {
        // Read the row's cost + kind BEFORE deleting so we can carry
        // the cost into the report's costsFromDeletedItems tally and
        // decide whether to cascade. The user dropped the row from
        // the report; the API spend is real and should still surface
        // on the result page.
        val deleted = SecondaryResultStorage.get(context, reportId, resultId)
        var costDelta = deleted?.let { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } ?: 0.0
        SecondaryResultStorage.delete(context, reportId, resultId)
        // Cascade: when a META row is deleted, its cross-translate
        // TRANSLATE rows (translateSourceKind = "META",
        // translateSourceTargetId = this meta id) become orphans —
        // they'd still surface as language tabs on the View screen but
        // no longer reachable through any meta tile. Drop them too so
        // the on-disk state stays consistent.
        if (deleted?.kind == SecondaryKind.META) {
            val orphans = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter { it.translateSourceKind == "META" && it.translateSourceTargetId == resultId }
            orphans.forEach { tr ->
                costDelta += (tr.inputCost ?: 0.0) + (tr.outputCost ?: 0.0)
                SecondaryResultStorage.delete(context, reportId, tr.id)
            }
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        // Bump the parent report's timestamp — removing a meta /
        // translation row is a real change to what the report contains
        // and should sort the report to the top of History, same as an
        // additive change does.
        ReportStorage.bumpReportTimestamp(context, reportId)
    }

    /** Bulk-delete every secondary result row in [resultIds] off the
     *  UI thread on viewModelScope. Survives the screen scope being
     *  cancelled mid-loop — the previous screen-scoped sweep
     *  abandoned hundreds of rows when the user navigated away
     *  during a Fan-out delete. Returns once the sweep finishes. */
    fun bulkDeleteSecondaryResults(context: Context, reportId: String, resultIds: List<String>, onComplete: () -> Unit = {}) {
        appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            // Snapshot the per-id costs + kinds before deleting so we
            // can bump costsFromDeletedItems with the total and
            // cascade-delete META cross-translate orphans.
            var costDelta = 0.0
            val deletedMetaIds = mutableSetOf<String>()
            resultIds.forEach { id ->
                runCatching {
                    SecondaryResultStorage.get(context, reportId, id)?.let { r ->
                        costDelta += (r.inputCost ?: 0.0) + (r.outputCost ?: 0.0)
                        if (r.kind == SecondaryKind.META) deletedMetaIds.add(id)
                    }
                    SecondaryResultStorage.delete(context, reportId, id)
                }
            }
            // Cascade: every TRANSLATE row pointing back at a
            // just-deleted META row is now an orphan; drop it too.
            if (deletedMetaIds.isNotEmpty()) {
                val orphans = SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                    .filter { it.translateSourceKind == "META" && it.translateSourceTargetId in deletedMetaIds }
                orphans.forEach { tr ->
                    costDelta += (tr.inputCost ?: 0.0) + (tr.outputCost ?: 0.0)
                    SecondaryResultStorage.delete(context, reportId, tr.id)
                }
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
            withContext(Dispatchers.Main) { onComplete() }
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
    fun regenerateAgent(context: Context, reportId: String, agentId: String) {
        // viewModelScope: same survival rationale as
        // generateGenericReports — a screen-scoped scope here would
        // turn the in-flight call into ERROR on disk if the user
        // navigates away before the new response lands.
        appViewModel.viewModelScope.launch(reportLogContext(reportId)) {
            withTracerTags(reportId = reportId, category = "Report regenerate agent") {
            val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
            val ra = report.agents.find { it.agentId == agentId } ?: return@withTracerTags
            val provider = AppService.findById(ra.provider) ?: return@withTracerTags
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
            }
        }
    }

    /** Remove a single agent from a report (storage + in-memory results
     *  flow + the genericReportsSelectedAgents set the UI iterates). The
     *  Report screen's row click leads to a single-result viewer with a
     *  "Remove model from report" button — that's this. */
    fun removeAgentFromReport(context: Context, reportId: String, agentId: String) {
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
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
        }
        ReportStorage.bumpReportTimestamp(context, reportId)
        _agentResults.update { it - agentId }
        appViewModel.updateUiState { state ->
            if (state.currentReportId != reportId) state
            else state.copy(
                genericReportsSelectedAgents = state.genericReportsSelectedAgents - agentId,
                genericReportsTotal = (state.genericReportsTotal - 1).coerceAtLeast(0),
                genericReportsProgress = (state.genericReportsProgress - 1).coerceAtLeast(0)
            )
        }
    }

}
