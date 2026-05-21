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
    internal fun fanOutJobKey(reportId: String, metaPromptId: String) = "$reportId|$metaPromptId"
    internal fun registerFanOutJob(reportId: String, metaPromptId: String, job: Job) {
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
    internal val staleResumeScans = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    val secondary = SecondaryRunManager(appViewModel, this)

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
