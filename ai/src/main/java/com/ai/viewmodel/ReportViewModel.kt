package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import com.ai.ui.report.translationRunGroupingId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val fanOutJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private fun fanOutJobKey(reportId: String, metaPromptId: String) = "$reportId|$metaPromptId"
    private fun registerFanOutJob(reportId: String, metaPromptId: String, job: Job) {
        val key = fanOutJobKey(reportId, metaPromptId)
        fanOutJobs[key] = job
        job.invokeOnCompletion { fanOutJobs.remove(key, job) }
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

    // Separate flow from UiState so per-task completions don't force the UiState equality
    // checker to re-compare every other field. UI subscribers observe this independently.
    private val _agentResults = MutableStateFlow<Map<String, AnalysisResponse>>(emptyMap())
    val agentResults: StateFlow<Map<String, AnalysisResponse>> = _agentResults.asStateFlow()

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

            val reportTasks = buildReportTasks(aiSettings, agents, allModelMembers, selectionParamsById, externalSystemPrompt)

            _agentResults.value = emptyMap()
            appViewModel.updateUiState { it.copy(
                showGenericAgentSelection = false, showGenericReportsDialog = true,
                genericReportsProgress = 0, genericReportsTotal = reportTasks.size,
                genericReportsSelectedAgents = selectedAgentIds + allModelIds,
                currentReportId = null
            ) }

            val userMatch = AppViewModel.USER_TAG_REGEX.find(prompt)
            val rapportText = userMatch?.groupValues?.get(1)?.trim() ?: state.externalOpenHtml
            val aiPrompt = if (userMatch != null) prompt.replace(userMatch.value, "").trim() else prompt

            val report = ReportStorage.createReportAsync(
                context = context, title = title.ifBlank { "AI Report" },
                prompt = aiPrompt, agents = reportTasks.map { it.reportAgent },
                rapportText = rapportText, reportType = reportType, closeText = state.externalCloseHtml,
                imageBase64 = imageBase64, imageMime = imageMime,
                webSearchTool = state.reportWebSearchTool,
                reasoningEffort = state.reportReasoningEffort,
                knowledgeBaseIds = state.attachedKnowledgeBaseIds
            )
            val reportId = report.id
            val reportStartMs = System.currentTimeMillis()
            AppLog.i("Report", "→ start \"${title.ifBlank { "AI Report" }}\" (id=$reportId, ${reportTasks.size} agent(s))")

            withTracerTags(reportId = reportId, category = "Report") {
                appViewModel.updateUiState { it.copy(currentReportId = reportId) }

                kickOffIconGeneration(context, reportId, aiPrompt, aiSettings)

                val semaphore = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                try {
                    coroutineScope {
                        reportTasks.map { task ->
                            async {
                                semaphore.withPermit { executeReportTask(context, reportId, aiPrompt, overrideParams, task, imageBase64, imageMime) }
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


    /** Background helper that runs the bundled `internal/icon` prompt
     *  against its pinned agent and writes the resolved emoji onto the
     *  Report. Best-effort: silently no-ops when the prompt is missing,
     *  the pinned agent has been deleted / renamed, or the agent isn't
     *  resolvable via [Settings.agents] by name. The call is launched
     *  on viewModelScope so it runs in parallel with per-agent dispatch
     *  and survives the user navigating away from the result screen.
     *  Failures are persisted to [Report.iconErrorMessage] so the
     *  result-page row can render ❌. */
    private fun kickOffIconGeneration(
        context: Context,
        reportId: String,
        promptText: String,
        aiSettings: Settings
    ) {
        // Master switch — when the user disabled per-report icon-gen
        // in Settings, skip the LLM call entirely. Existing on-disk
        // icon values stay intact.
        if (!appViewModel.uiState.value.generalSettings.iconGenEnabled) return
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "internal" && it.name == "icon"
        } ?: return
        // Case-insensitive match so a user who has the agent registered
        // as "DeepSeek" still resolves the bundled prompt's
        // (lowercase-tail) "Deepseek" pin without manual editing. Same
        // safety against future bundled-vs-user casing drift.
        val rawAgent = aiSettings.agents.firstOrNull {
            it.name.equals(iconPrompt.agent, ignoreCase = true)
        } ?: return
        // The Agent stored in aiSettings.agents carries an empty apiKey
        // field — keys live on the Provider. Resolve the same way
        // buildReportTasks does so the dispatch sees a real key (and a
        // real model when the agent is pinned to a default-model alias).
        val agent = rawAgent.copy(
            apiKey = aiSettings.getEffectiveApiKeyForAgent(rawAgent),
            model = aiSettings.getEffectiveModelForAgent(rawAgent)
        )
        val resolved = iconPrompt.text.replace("@PROMPT@", promptText)
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            withTracerTags(reportId = reportId, category = "Report icon") {
                runCatching {
                    val baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent)
                    val response = appViewModel.repository.analyzeWithAgent(
                        agent, "", resolved, AgentParameters(),
                        null, context, baseUrl
                    )
                    val emoji = response.analysis?.trim().orEmpty().take(8)
                    if (response.error == null && emoji.isNotEmpty()) {
                        val tu = response.tokenUsage
                        val pricing = PricingCache.getPricing(context, agent.provider, agent.model)
                        val inT = tu?.inputTokens ?: 0
                        val outT = tu?.outputTokens ?: 0
                        val inC = inT * pricing.promptPrice
                        val outC = outT * pricing.completionPrice
                        ReportStorage.updateReportIcon(
                            context, reportId, emoji,
                            inputTokens = inT, outputTokens = outT,
                            inputCost = inC, outputCost = outC
                        )
                    } else {
                        ReportStorage.updateReportIconError(
                            context, reportId,
                            response.error ?: "empty response"
                        )
                    }
                }.onFailure {
                    ReportStorage.updateReportIconError(
                        context, reportId,
                        it.message ?: "icon-gen failed"
                    )
                }
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
        }
    }



    private fun buildReportTasks(
        aiSettings: Settings, agents: List<Agent>, modelMembers: List<SwarmMember>,
        selectionParamsById: Map<String, List<String>>, externalSystemPrompt: String?
    ): List<ReportTask> {
        val agentTasks = agents.map { agent ->
            val ea = agent.copy(
                apiKey = aiSettings.getEffectiveApiKeyForAgent(agent),
                model = aiSettings.getEffectiveModelForAgent(agent)
            )
            val selParams = aiSettings.mergeParameters(selectionParamsById[agent.id] ?: emptyList())
            var params = selParams ?: aiSettings.resolveAgentParameters(agent)
            val spText = resolveSystemPromptText(aiSettings, agent.systemPromptId, findFlockSystemPromptIdForAgent(aiSettings, agent.id)) ?: externalSystemPrompt
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(agent.id, ReportAgent(agent.id, agent.name, ea.provider.id, ea.model, ReportStatus.PENDING), ea, params)
        }

        val modelTasks = modelMembers.map { member ->
            val sid = "swarm:${member.provider.id}:${member.model}"
            val spText = findSwarmSystemPromptIdForMember(aiSettings, member.provider, member.model)?.let { aiSettings.getSystemPromptById(it)?.prompt } ?: externalSystemPrompt
            var params = aiSettings.mergeParameters(selectionParamsById[sid] ?: emptyList()) ?: AgentParameters()
            if (spText != null) params = params.copy(systemPrompt = spText)

            ReportTask(sid,
                ReportAgent(sid, "${member.provider.id} / ${member.model}", member.provider.id, member.model, ReportStatus.PENDING),
                Agent(sid, "${member.provider.id} / ${member.model}", member.provider, member.model, aiSettings.getApiKey(member.provider)),
                params
            )
        }
        return agentTasks + modelTasks
    }

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

        // Persist the terminal state under NonCancellable so a Stop /
        // navigate-away that arrives between the API return and this
        // disk write doesn't strand the agent row in RUNNING on disk.
        // The async helpers themselves marshal the I/O off-thread.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (response.isSuccess) {
                ReportStorage.markAgentSuccessAsync(context, reportId, task.resultId,
                    response.httpStatusCode ?: 200, response.httpHeaders, response.analysis,
                    response.tokenUsage, cost, response.citations, response.searchResults,
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
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
            val tasks = buildReportTasks(ai, agents, swarmMembers + directModels, emptyMap(), state.externalSystemPrompt)
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
            if (tasksToRun.isEmpty() && removedIds.isEmpty() && !cascadeAll) return@launch

            withTracerTags(reportId = reportId, category = "Report regenerate") {
                // Re-run icon-gen only when the user edited the prompt.
                // A pure model-list / parameters regenerate keeps the
                // existing icon — the report's content didn't change.
                if (state.hasPendingPromptChange) {
                    ReportStorage.clearReportIcon(context, reportId)
                    appViewModel.updateUiState { it.copy(iconRefreshTick = it.iconRefreshTick + 1) }
                    kickOffIconGeneration(context, reportId, report.prompt, ai)
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
                    val sem = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                    coroutineScope {
                        tasksToRun.map { task ->
                            async {
                                sem.withPermit {
                                    executeReportTask(context, reportId, finalReport.prompt, overrideParams, task,
                                        finalReport.imageBase64, finalReport.imageMime, isRegeneration = false)
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
                startTranslation(context, reportId, run.lang, run.native, run.provider, run.model).join()
            }
        }
    }

    /** Delete a report file and, if it's the one currently shown, dismiss the screen state. */
    fun deleteReport(context: Context, reportId: String) {
        val cleared = appViewModel.uiState.value.currentReportId == reportId
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
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = reportId, category = "Report rerank (local)") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    val responses = report.agents
                        .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                        .map { it.responseBody!! }
                    if (responses.isEmpty()) return@withTracerTags
                    val agentName = "Local / $modelName"
                    val placeholder = SecondaryResultStorage.create(context, reportId, SecondaryKind.RERANK, "LOCAL", modelName, agentName)
                    SecondaryResultStorage.save(context, placeholder)
                    ReportStorage.bumpReportTimestamp(context, reportId)

                    val started = System.currentTimeMillis()
                    val queryVec = com.ai.data.LocalEmbedder.embed(context, modelName, listOf(report.prompt))?.firstOrNull()
                    val docVecs = com.ai.data.LocalEmbedder.embed(context, modelName, responses)
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
        pick: Pair<AppService, String>
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
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                withTracerTags(reportId = reportId, category = "Report rerank") {
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successfulCount = report.agents.count {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successfulCount == 0) return@withTracerTags
                    val resultsBlock = buildResultsBlock(report)
                    val resolvedPrompt = resolveSecondaryPrompt(
                        rerankPrompt.text, question = report.prompt, results = resultsBlock,
                        count = successfulCount, title = report.title
                    )
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.RERANK, rerankPrompt,
                        provider, model, resolvedPrompt, aiSettings, report
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
        responderAgentIds: Set<String>? = null
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
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
                            val agentName = "${provider.id} / ${answerer.model}"
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.META, provider.id, answerer.model, agentName
                            ).copy(
                                metaPromptId = metaPrompt.id,
                                metaPromptName = metaPrompt.name,
                                fanOutSourceAgentId = source.agentId
                            )
                            SecondaryResultStorage.save(context, placeholder)
                            pending.add(PendingPair(answerer, source, placeholder))
                        }
                    }
                    // Launch one coroutine per pair. Per-provider
                    // concurrency + per-minute rate are now enforced
                    // globally inside ProviderThrottleInterceptor, so
                    // we no longer need a per-batch semaphore here —
                    // the OkHttp interceptor caps in-flight calls per
                    // host across every flow in the app (report, meta,
                    // chat, translate, …) using the user-tunable
                    // GeneralSettings.maxConcurrentCallsPerProvider.
                    coroutineScope {
                        pending.map { item ->
                            async {
                                val provider = AppService.findById(item.answerer.provider) ?: return@async
                                appViewModel.updateRunningFanOutPairs { it + item.placeholder.id }
                                val pairStart = System.currentTimeMillis()
                                AppLog.d("FanOut", "→ pair ans=${item.answerer.agentId} src=${item.source.agentId} ${provider.id}/${item.answerer.model}")
                                try {
                                    val resolvedBase = resolveSecondaryPrompt(
                                        metaPrompt.text,
                                        question = report.prompt,
                                        results = "",
                                        count = sources.size,
                                        title = report.title
                                    )
                                    val resolved = resolvedBase.replace("@RESPONSE@", item.source.responseBody ?: "")
                                    executeSecondaryTask(
                                        context, reportId, SecondaryKind.META, metaPrompt,
                                        provider, item.answerer.model, resolved, aiSettings, report,
                                        fanOutSourceAgentId = item.source.agentId,
                                        existingPlaceholder = item.placeholder
                                    )
                                } finally {
                                    appViewModel.updateRunningFanOutPairs { it - item.placeholder.id }
                                    AppLog.d("FanOut", "← pair ans=${item.answerer.agentId} src=${item.source.agentId} ${System.currentTimeMillis() - pairStart}ms")
                                }
                            }
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
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
                    // Per-provider gating now lives in
                    // ProviderThrottleInterceptor — see runFanOutPrompt
                    // for the matching change.
                    coroutineScope {
                        placeholders.map { ph ->
                            async {
                                val provider = AppService.findById(ph.providerId) ?: return@async
                                val source = successful.firstOrNull { it.agentId == ph.fanOutSourceAgentId }
                                    ?: return@async
                                appViewModel.updateRunningFanOutPairs { it + ph.id }
                                val rerunStart = System.currentTimeMillis()
                                AppLog.d("FanOut", "→ rerun pair ph=${ph.id} src=${source.agentId} ${provider.id}/${ph.model}")
                                try {
                                    val resolvedBase = resolveSecondaryPrompt(
                                        metaPrompt.text,
                                        question = report.prompt,
                                        results = "",
                                        count = sourceCount,
                                        title = report.title
                                    )
                                    val resolved = resolvedBase.replace("@RESPONSE@", source.responseBody ?: "")
                                    executeSecondaryTask(
                                        context, reportId, SecondaryKind.META, metaPrompt,
                                        provider, ph.model, resolved, aiSettings, report,
                                        fanOutSourceAgentId = source.agentId,
                                        existingPlaceholder = ph
                                    )
                                } finally {
                                    appViewModel.updateRunningFanOutPairs { it - ph.id }
                                    AppLog.d("FanOut", "← rerun pair ph=${ph.id} ${System.currentTimeMillis() - rerunStart}ms")
                                }
                            }
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
                durationMs = null
            ).also { SecondaryResultStorage.save(context, it) }
        }
        return rerunFanOutPlaceholders(context, reportId, metaPrompt, reset)
    }

    /** Mark every stuck placeholder secondary on the report as errored.
     *  The Report Result screen calls this on entry: animated-hourglass
     *  rows should only spin while something is actually running, but
     *  on app restart no in-memory job survives, so any row with blank
     *  content + null errorMessage + null durationMs is an orphan. We
     *  flip them to "Interrupted by app restart" so the row icon shows
     *  ❌ honestly instead of a never-ending spinner.
     *
     *  Fan-out per-pair rows (fanOutSourceAgentId != null) are skipped
     *  here because they have a dedicated resume flow at L1
     *  ([resumeStaleFanOutPairs]) — the user can re-enter L1 to relaunch
     *  them. Other secondary kinds (fan-in / fan_in combine,
     *  meta runs, Rerank, Moderation, Translate per-call) get the
     *  honest red ❌. */
    fun recoverStaleSecondariesAsync(
        context: Context,
        reportId: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val running = appViewModel.runningFanOutPairs.value
        val activeTranslationRunIds = _translationRuns.value.keys
        val rows = SecondaryResultStorage.listForReport(context, reportId)
        rows.forEach { row ->
            // Already terminal — nothing to do.
            if (row.errorMessage != null) return@forEach
            if (!row.content.isNullOrBlank()) return@forEach
            if (row.durationMs != null) return@forEach
            // Fan-out per-pair: leave alone, L1 handles resume.
            if (row.fanOutSourceAgentId != null) return@forEach
            // Defence in depth — id is currently mid-flight (shouldn't
            // happen at startup but guards against a navigate-back-to-
            // Report-Result-mid-run race).
            if (row.id in running) return@forEach
            // Translation row that belongs to a still-active in-memory
            // run — also defence in depth; per-item save means the row
            // already has its outcome stamped in the active path.
            if (row.kind == SecondaryKind.TRANSLATE &&
                row.translationRunId != null &&
                row.translationRunId in activeTranslationRunIds) return@forEach
            // TOCTOU close: re-read the row right before saving the
            // "Interrupted" marker. A non-fan-out, non-translation
            // META/RERANK/MODERATION call in flight has its placeholder
            // on disk as blank; if executeSecondaryTask saves the
            // completion *between* our initial listForReport above and
            // this save, the stale in-memory copy here would overwrite
            // the real result with the interrupted marker. Re-reading
            // shrinks the race window to the get → save microseconds
            // (a full lock per row would close it entirely but isn't
            // worth the complexity for a one-shot startup sweep).
            val current = SecondaryResultStorage.get(context, reportId, row.id) ?: return@forEach
            if (current.errorMessage != null) return@forEach
            if (!current.content.isNullOrBlank()) return@forEach
            if (current.durationMs != null) return@forEach
            SecondaryResultStorage.save(context, current.copy(
                errorMessage = "Interrupted by app restart",
                durationMs = 0
            ))
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
        // (report, prompt) is already in flight. The L1 LaunchedEffect
        // can re-key spuriously when aiSettings flows a new copy.
        if (!staleResumeScans.add(key)) return null
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
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
                if (stale.isNotEmpty()) {
                    rerunFanOutPlaceholders(context, reportId, metaPrompt, stale)
                }
            } finally {
                staleResumeScans.remove(key)
            }
        }
        return job
    }

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
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
     *  report (different metaPromptId) are untouched. */
    fun deleteFanOutModel(
        context: Context,
        reportId: String,
        metaPromptId: String,
        providerId: String,
        model: String
    ) {
        val report = ReportStorage.getReport(context, reportId) ?: return
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
        val costDelta = toDelete.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
        toDelete.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
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
        pick: Pair<AppService, String>
    ): Job? {
        AppLog.i("FanIn", "→ start \"${metaPrompt.name}\" report=$reportId via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
                        (source.responseBody?.trim().orEmpty()) to fanOutResponses
                    }
                    if (perReport.all { it.second.isEmpty() }) {
                        val (provider, model) = pick
                        val agentName = "${provider.id} / $model"
                        val placeholder = SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
                        ).copy(
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            fanInOf = metaPrompt.id,
                            errorMessage = "No fan-out responses available — run the fan-out prompt first."
                        )
                        SecondaryResultStorage.save(context, placeholder)
                        return@withTracerTags
                    }
                    val resolved = resolveFanInPrompt(
                        template = metaPrompt.text,
                        question = report.prompt,
                        count = perReport.size,
                        fanOutCount = (perReport.size - 1).coerceAtLeast(0),
                        perReport = perReport,
                        title = report.title
                    )
                    val (provider, model) = pick
                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
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
        activeModel: String
    ): Job? {
        AppLog.i("ModelFanIn", "→ start \"${metaPrompt.name}\" report=$reportId active=$activeProviderId/$activeModel via ${pick.first.id}/${pick.second}")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
                    val initiatorBody = activeAgents.firstOrNull()?.responseBody?.trim().orEmpty()

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
                            val srcBody = source.responseBody?.trim().orEmpty()
                            val resp = bucket.lastOrNull()?.content?.trim().orEmpty()
                            if (srcBody.isBlank() || resp.isBlank()) null else srcBody to resp
                        }

                    val (provider, model) = pick

                    // Bail with an error placeholder when there's
                    // nothing to combine — same shape as the legacy
                    // fan_in does for empty fan-out states.
                    val nothingToCombine = responders.isEmpty() && responderPairs.isEmpty()
                    if (nothingToCombine) {
                        val agentName = "${provider.id} / $model"
                        val placeholder = SecondaryResultStorage.create(
                            context, reportId, SecondaryKind.META, provider.id, model, agentName
                        ).copy(
                            metaPromptId = metaPrompt.id,
                            metaPromptName = metaPrompt.name,
                            fanInOf = metaPrompt.id,
                            scopeProviderId = activeProviderId,
                            scopeModel = activeModel,
                            errorMessage = "No fan-out responses available for ${activeProviderId} / ${activeModel} — run the fan-out prompt first."
                        )
                        SecondaryResultStorage.save(context, placeholder)
                        return@withTracerTags
                    }

                    val resolved = com.ai.data.resolveModelFanInPrompt(
                        template = metaPrompt.text,
                        question = report.prompt,
                        title = report.title,
                        initiatorBody = initiatorBody,
                        responders = responders,
                        responderPairs = responderPairs
                    )

                    // Pre-create the placeholder so the scope fields
                    // are persisted from the start (executeSecondaryTask
                    // doesn't take scopeProviderId / scopeModel — we
                    // pass the staged row in via existingPlaceholder).
                    val agentName = "${provider.id} / $model"
                    val placeholder = SecondaryResultStorage.create(
                        context, reportId, SecondaryKind.META, provider.id, model, agentName
                    ).copy(
                        metaPromptId = metaPrompt.id,
                        metaPromptName = metaPrompt.name,
                        fanInOf = metaPrompt.id,
                        scopeProviderId = activeProviderId,
                        scopeModel = activeModel
                    )
                    SecondaryResultStorage.save(context, placeholder)

                    executeSecondaryTask(
                        context, reportId, SecondaryKind.META, metaPrompt,
                        provider, model, resolved, aiSettings, report,
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
                agentName = "${row.providerId} / ${row.model}",
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

        return appViewModel.viewModelScope.launch(Dispatchers.IO) {
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

                // Per-batch semaphore: bounds the in-batch fan-out across
                // every (language, pick) tuple. Overlapping batches are
                // not capped against each other (intentional; that's the
                // whole point of allowing concurrent runs).
                val sem = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                // Reference legend — built when the prompt's reference
                // flag is on. Computed once per batch.
                val referenceLegend = if (metaPrompt.reference)
                    buildReferenceLegend(report, includeIds) else null
                coroutineScope {
                    languages.flatMap { (lang, langNative) ->
                        val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, lang, includeIds)
                        val resolvedPrompt = resolveSecondaryPrompt(
                            metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                            count = successfulCount, title = report.title
                        )
                        picks.map { (provider, model) ->
                            async {
                                sem.withPermit {
                                    executeSecondaryTask(
                                        context, reportId, kind, metaPrompt,
                                        provider, model, resolvedPrompt, aiSettings, report,
                                        lang, langNative, referenceLegend,
                                        scopeEncoded = scopeChoice.encode()
                                    )
                                }
                            }
                        }
                    }.awaitAll()
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


    private suspend fun executeSecondaryTask(
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
        val agentName = "${provider.id} / $model$langSuffix"
        val placeholder = existingPlaceholder ?: run {
            val fresh = SecondaryResultStorage.create(context, reportId, kind, provider.id, model, agentName)
                .copy(
                    targetLanguage = targetLanguage,
                    targetLanguageNative = targetLanguageNative,
                    metaPromptId = metaPrompt.id,
                    metaPromptName = metaPrompt.name,
                    fanOutSourceAgentId = fanOutSourceAgentId,
                    fanInOf = fanInOf,
                    secondaryScope = scopeEncoded
                )
            SecondaryResultStorage.save(context, fresh)
            fresh
        }

        // Moderation runs through the dedicated /v1/moderations
        // endpoint — one batch call classifying every report response.
        // No chat prompt, no per-response loop here (the API takes the
        // input array and returns one result per input). The structured
        // JSON content is rendered as a flagged-categories table by the
        // detail screen.
        if (kind == SecondaryKind.MODERATION) {
            val responses = report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .map { it.responseBody!! }
            val (_, r) = com.ai.data.callModerationApi(provider, apiKey, model, responses)
            // Persist Mistral's reported token usage + per-token cost
            // so the result row shows cents like the other meta runs.
            // Falls through to no-cost when the API didn't report usage.
            val tu = r.tokenUsage
            val pricing = PricingCache.getPricing(context, provider, model)
            val inCost = tu?.let { it.inputTokens * pricing.promptPrice }
            val outCost = tu?.let { it.outputTokens * pricing.completionPrice }
            SecondaryResultStorage.save(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                tokenUsage = tu,
                inputCost = inCost,
                outputCost = outCost,
                durationMs = r.durationMs
            ))
            if (r.errorMessage == null) {
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
            SecondaryResultStorage.save(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                inputCost = rerankCost,
                durationMs = r.durationMs
            ))
            if (r.errorMessage == null) {
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
        SecondaryResultStorage.save(context, placeholder.copy(
            content = finalContent,
            errorMessage = response.error,
            tokenUsage = tu,
            inputCost = inCost,
            outputCost = outCost,
            durationMs = duration
        ))

        if (response.error == null && tu != null) {
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
        // Read the row's cost BEFORE deleting so we can carry it
        // into the report's costsFromDeletedItems tally. The user
        // dropped the row from the report; the API spend is real
        // and should still surface on the result page.
        val cost = SecondaryResultStorage.get(context, reportId, resultId)?.let {
            (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0)
        } ?: 0.0
        SecondaryResultStorage.delete(context, reportId, resultId)
        if (cost > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, cost)
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
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            // Snapshot the per-id costs before deleting so we can
            // bump costsFromDeletedItems with the total.
            var costDelta = 0.0
            resultIds.forEach { id ->
                runCatching {
                    SecondaryResultStorage.get(context, reportId, id)?.let { r ->
                        costDelta += (r.inputCost ?: 0.0) + (r.outputCost ?: 0.0)
                    }
                    SecondaryResultStorage.delete(context, reportId, id)
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
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
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

    // ===== Translate =====

    enum class TranslationStatus { PENDING, RUNNING, DONE, ERROR }
    enum class TranslationKind { PROMPT, AGENT_RESPONSE, META }

    /** One row on the translation progress screen. [sourceText] is what
     *  gets fed to the model; [translatedText] is filled in on DONE.
     *  [costDollars] is the per-call cost in USD (input + output) for
     *  the running tally — the live result-list row passes it through
     *  formatCents() which multiplies by 100, same convention every
     *  other cost cell in the app uses. [target] is the
     *  SecondaryResult.id when the source is a chat-type META row —
     *  used so the per-language overlay can re-attach the translated
     *  content to the right meta result. */
    data class TranslationItem(
        val id: String,
        val label: String,
        val kind: TranslationKind,
        val sourceText: String,
        val target: String? = null,
        val status: TranslationStatus = TranslationStatus.PENDING,
        val translatedText: String? = null,
        val errorMessage: String? = null,
        val costDollars: Double = 0.0,
        /** Token usage from the translation API call. Stored so the new
         *  Report can attribute per-row cost to the translation
         *  operation rather than carrying the source-run figures
         *  through unchanged. Null until the call returns. */
        val tokenUsage: com.ai.data.TokenUsage? = null,
        /** Wall-clock duration of the translation API call. Surfaced
         *  in the Costs table so translate rows aren't blank in the
         *  Seconds column. Null until the call returns. */
        val durationMs: Long? = null
    )

    data class TranslationRunState(
        val runId: String,
        val sourceReportId: String,
        val targetLanguageName: String,
        val targetLanguageNative: String,
        val items: List<TranslationItem>,
        val totalCostDollars: Double = 0.0,
        val finished: Boolean = false,
        val cancelled: Boolean = false
    ) {
        val total: Int get() = items.size
        val completed: Int get() = items.count { it.status == TranslationStatus.DONE || it.status == TranslationStatus.ERROR }
        val isRunning: Boolean get() = !finished && !cancelled && completed < total
        val isFinished: Boolean get() = finished
    }

    // Multiple concurrent translation runs: each Translate click
    // allocates a fresh runId and runs in parallel with any others
    // already in flight. Map keyed by runId so the UI can render one
    // live row per run and Cancel can target a specific one.
    private val _translationRuns = MutableStateFlow<Map<String, TranslationRunState>>(emptyMap())
    val translationRuns: StateFlow<Map<String, TranslationRunState>> = _translationRuns.asStateFlow()
    private val translationJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Snapshot the report's translatable items, kick off the runner.
     *  Concurrency capped at 3 — translations are often the slowest
     *  operation in the app and respecting provider rate limits is
     *  more important than maximum throughput. Each translated piece
     *  is persisted as a TRANSLATE [SecondaryResult] on the SOURCE
     *  report, tagged with the language. The viewer / HTML exports
     *  group those rows by language to render Original | Dutch | …
     *  views. Structured-JSON meta results (rerank, moderation) are
     *  skipped. */
    fun startTranslation(
        context: Context,
        sourceReportId: String,
        targetLanguageName: String,
        targetLanguageNative: String,
        provider: AppService,
        model: String
    ): Job {
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val generalSettings = state.generalSettings
            val sourceReport = ReportStorage.getReport(context, sourceReportId) ?: run {
                _translationRuns.update { it - runId }
                return@launch
            }
            val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

            // Build the work list. Order: prompt → agent responses (in
            // success order) → summaries → compares. Reranks and
            // moderation results are skipped (structured JSON, no
            // human-language content to translate).
            val items = mutableListOf<TranslationItem>()
            items += TranslationItem(
                id = "prompt",
                label = "Report prompt",
                kind = TranslationKind.PROMPT,
                sourceText = sourceReport.prompt
            )
            sourceReport.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .forEach { agent ->
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    items += TranslationItem(
                        id = "agent:${agent.agentId}",
                        label = "$provDisplay / ${agent.model}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = agent.responseBody!!,
                        target = agent.agentId
                    )
                }
            // Every chat-type Meta result is a candidate for translation.
            // Label the row by the user-given Meta prompt name so the
            // progress screen / per-call detail show "Compare 1: …" or
            // "Critique 2: …" — driven entirely by the CRUD prompt name,
            // not a hardcoded "Summary" / "Compare".
            secondaries.filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
                .forEachIndexed { idx, s ->
                    val provDisplay = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    items += TranslationItem(
                        id = "meta:${s.id}",
                        label = "$name ${idx + 1}: $provDisplay / ${s.model}",
                        kind = TranslationKind.META,
                        sourceText = s.content!!,
                        target = s.id
                    )
                }

            _translationRuns.update { it + (runId to TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = items
            )) }
            AppLog.i("Translation", "→ start $targetLanguageName ($targetLanguageNative) for report=$sourceReportId — ${items.size} items via ${provider.id}/$model")

            val template = aiSettings.getInternalPromptByName("Translate")?.text.orEmpty()
            val apiKey = aiSettings.getApiKey(provider)
            val baseUrl = aiSettings.getEffectiveEndpointUrl(provider)
            val pricing = PricingCache.getPricing(context, provider, model)

            // Tag every translation call's trace with the SOURCE report
            // id — translations live on that report now, no separate
            // translated copy to keep traces with.
            withTracerTags(reportId = sourceReportId, category = "Translation") {
                // Concurrency 3: translation calls are typically the slowest
                // I/O in the app; cap so a 30-item report doesn't blow past
                // provider rate limits.
                val sem = Semaphore(3)
                coroutineScope {
                    items.map { item ->
                        async {
                            sem.withPermit { runOneTranslation(runId, context, provider, apiKey, model, baseUrl, template, targetLanguageName, item, pricing) }
                        }
                    }.awaitAll()
                }

                // Per-item rows were already persisted inside
                // runOneTranslation as each call settled, so the
                // batch survives a redeploy / OS kill mid-run. Just
                // bump the parent report's timestamp once at the end
                // so the History list resorts. Skipped on cancel —
                // the cancelled item stays unpersisted and the
                // timestamp bump is moot.
                val finalState = _translationRuns.value[runId] ?: return@withTracerTags
                if (finalState.cancelled) {
                    AppLog.i("Translation", "← cancelled $targetLanguageName for report=$sourceReportId")
                    return@withTracerTags
                }
                ReportStorage.bumpReportTimestamp(context, sourceReportId)
                _translationRuns.update { runs ->
                    val cur = runs[runId] ?: return@update runs
                    runs + (runId to cur.copy(finished = true))
                }
                val okCount = finalState.items.count { it.translatedText?.isNotBlank() == true }
                val failCount = finalState.items.count { it.errorMessage != null }
                AppLog.i("Translation", "← done $targetLanguageName for report=$sourceReportId — ok=$okCount fail=$failCount")
            }
        }
        translationJobs[runId] = job
        job.invokeOnCompletion { translationJobs.remove(runId) }
        return job
    }

    private suspend fun runOneTranslation(
        runId: String,
        context: Context,
        provider: AppService,
        apiKey: String,
        model: String,
        baseUrl: String,
        template: String,
        targetLanguageName: String,
        item: TranslationItem,
        pricing: PricingCache.ModelPricing
    ) {
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(items = cur.items.map { if (it.id == item.id) it.copy(status = TranslationStatus.RUNNING) else it }))
        }
        AppLog.d("Translation", "→ item ${item.id} \"${item.label}\" kind=${item.kind} srcLen=${item.sourceText.length}")
        val resolved = template
            .replace("@LANGUAGE@", targetLanguageName)
            .replace("@TEXT@", item.sourceText)
        val agent = Agent(
            id = "translate:${provider.id}:$model",
            name = "Translate / ${provider.id} / $model",
            provider = provider, model = model, apiKey = apiKey
        )
        val callStart = System.currentTimeMillis()
        val response = try {
            appViewModel.repository.analyzeWithAgent(
                agent, "", resolved, AgentParameters(), null, context, baseUrl
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AnalysisResponse(provider, null, e.message ?: "Unknown error", agentName = agent.name)
        }
        val callDurationMs = System.currentTimeMillis() - callStart
        val tu = response.tokenUsage
        val costDollars = if (tu != null) PricingCache.computeCost(tu, pricing) else 0.0
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            val updated = cur.items.map {
                if (it.id != item.id) it
                else if (response.isSuccess) it.copy(
                    status = TranslationStatus.DONE,
                    translatedText = response.analysis,
                    costDollars = costDollars,
                    tokenUsage = tu,
                    durationMs = callDurationMs
                )
                else it.copy(
                    status = TranslationStatus.ERROR,
                    errorMessage = response.error ?: "Empty response",
                    costDollars = costDollars,
                    tokenUsage = tu,
                    durationMs = callDurationMs
                )
            }
            runs + (runId to cur.copy(items = updated, totalCostDollars = updated.sumOf { it.costDollars }))
        }
        // Persist this item as soon as the call settles so the row
        // survives a process kill mid-batch. Previously the whole
        // batch was held in-memory and only flushed to disk via
        // saveTranslationSecondaries after awaitAll(); a redeploy
        // or an OS kill halfway through silently lost everything.
        // Use the in-memory runId as the on-disk translationRunId so
        // every item this batch writes groups under the same run on
        // the result screen and survives restart with the same
        // identity.
        val freshRun = _translationRuns.value[runId]
        val freshItem = freshRun?.items?.firstOrNull { it.id == item.id }
        if (freshRun != null && freshItem != null) {
            saveOneTranslationItem(context, runId, freshRun, freshItem, provider, model, pricing)
        }
        // Roll the translation call into per-(provider, model) usage so
        // it shows up on the AI Usage screen alongside report / chat
        // calls. Skipped on error responses so the row reflects only
        // billable traffic.
        if (response.isSuccess && tu != null) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                provider, model, tu.inputTokens, tu.outputTokens, tu.totalTokens,
                kind = "translate"
            )
        }
        AppLog.d(
            "Translation",
            "← item ${item.id} ${if (response.isSuccess) "ok" else "err"} ${callDurationMs}ms" +
                (tu?.let { " in=${it.inputTokens} out=${it.outputTokens}" } ?: "") +
                " cost=${"%.5f".format(costDollars)}"
        )
    }

    /** Persist one TRANSLATE [SecondaryResult] for a single completed
     *  (or errored) translation item. Replaces the all-at-once flush
     *  that used to happen at the end of [startTranslation] — moving
     *  the save inline lets a half-finished batch survive process
     *  death with the rows that did complete still on disk. */
    private fun saveOneTranslationItem(
        context: Context,
        runId: String,
        run: TranslationRunState,
        item: TranslationItem,
        translateProvider: AppService,
        translateModel: String,
        translatePricing: PricingCache.ModelPricing
    ) {
        val tu = item.tokenUsage
        // Tier-aware split — runOneTranslation already computes the
        // total via PricingCache.computeCost. The persisted in / out
        // halves now go through computeInOutCost so a long-context
        // translation (>200k tokens) doesn't drift from the canonical
        // total recorded in the parent run.
        val (inCost, outCost) = tu?.let { PricingCache.computeInOutCost(it, translatePricing) }
            ?.let { it.first to it.second } ?: (null to null)
        val labelPrefix = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}"
        val (srcKind, srcTargetId) = when (item.kind) {
            TranslationKind.PROMPT -> "PROMPT" to "prompt"
            TranslationKind.AGENT_RESPONSE -> "AGENT" to (item.target ?: "")
            TranslationKind.META -> "META" to (item.target ?: "")
        }
        SecondaryResultStorage.save(context, SecondaryResult(
            id = java.util.UUID.randomUUID().toString(),
            reportId = run.sourceReportId,
            kind = SecondaryKind.TRANSLATE,
            providerId = translateProvider.id,
            model = translateModel,
            agentName = labelPrefix,
            timestamp = System.currentTimeMillis(),
            content = item.translatedText,
            errorMessage = item.errorMessage,
            tokenUsage = tu,
            inputCost = inCost,
            outputCost = outCost,
            durationMs = item.durationMs,
            translateSourceKind = srcKind,
            translateSourceTargetId = srcTargetId,
            targetLanguage = run.targetLanguageName,
            targetLanguageNative = run.targetLanguageNative,
            translationRunId = runId
        ))
    }

    // saveTranslationSecondaries (the bulk all-at-end flush) was
    // replaced by saveOneTranslationItem, called inline as each
    // translation call settles — so a half-finished batch persists
    // the rows it did complete instead of losing everything on a
    // redeploy / OS kill.

    fun cancelTranslation(runId: String) {
        translationJobs[runId]?.cancel()
        _translationRuns.update { runs ->
            val cur = runs[runId] ?: return@update runs
            runs + (runId to cur.copy(cancelled = true))
        }
    }

    fun consumeTranslationRun(runId: String) {
        _translationRuns.update { it - runId }
    }

    /** Re-run every errored translation row in [runId]: deletes the
     *  failed [SecondaryResult]s on disk, rebuilds [TranslationItem]s
     *  from the current report state, and dispatches them through
     *  [runOneTranslation]. The runId is preserved so the rerun rows
     *  group under the same translation run on the result screen. */
    fun restartFailedTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val rows = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId && it.errorMessage != null }
        if (rows.isEmpty()) return@launch
        runTranslationSubset(context, sourceReportId, runId, rows.map { it.translateSourceTargetId.orEmpty() to it.translateSourceKind.orEmpty() }, deleteRowIds = rows.map { it.id })
    }

    /** Run every expected translation item that has no row in [runId]
     *  yet: prompt + each successful agent + each chat-type Meta row.
     *  Used after an interrupted batch to fill in the items that
     *  never got persisted. */
    fun startMissingTranslations(
        context: Context,
        sourceReportId: String,
        runId: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val existing = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .filter { translationRunGroupingId(it) == runId }
        // Each existing row covers one (kind, target) pair — even
        // errored ones count as "covered" since they're addressed by
        // restartFailedTranslations.
        val covered = existing.map { (it.translateSourceKind.orEmpty()) to (it.translateSourceTargetId.orEmpty()) }.toSet()
        val report = ReportStorage.getReport(context, sourceReportId) ?: return@launch
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)
        val expected = mutableListOf<Pair<String, String>>()
        expected += "PROMPT" to "prompt"
        report.agents
            .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
            .forEach { expected += "AGENT" to it.agentId }
        secondaries.filter { it.kind == SecondaryKind.META && !it.content.isNullOrBlank() }
            .forEach { expected += "META" to it.id }
        val missing = expected.filterNot { it.first to it.second in covered }
            .map { it.second to it.first } // (target, kind) tuples — runTranslationSubset wants that order
        if (missing.isEmpty()) return@launch
        runTranslationSubset(context, sourceReportId, runId, missing, deleteRowIds = emptyList())
    }

    /** Shared core for [restartFailedTranslations] /
     *  [startMissingTranslations]. Reads the existing run rows to
     *  pick up provider / model / language, deletes any rows in
     *  [deleteRowIds] (used by the restart path so failed rows
     *  don't double up), builds [TranslationItem]s for each
     *  (target, kind) pair, populates [_translationRuns] under
     *  [runId], and dispatches via [runOneTranslation]. */
    private suspend fun runTranslationSubset(
        context: Context,
        sourceReportId: String,
        runId: String,
        targetKindPairs: List<Pair<String, String>>,
        deleteRowIds: List<String>
    ) {
        if (targetKindPairs.isEmpty()) return
        val anchor = SecondaryResultStorage
            .listForReport(context, sourceReportId, SecondaryKind.TRANSLATE)
            .firstOrNull { translationRunGroupingId(it) == runId } ?: return
        val provider = AppService.findById(anchor.providerId) ?: return
        val model = anchor.model
        val targetLanguageName = anchor.targetLanguage ?: return
        val targetLanguageNative = anchor.targetLanguageNative ?: targetLanguageName

        val report = ReportStorage.getReport(context, sourceReportId) ?: return
        val secondaries = SecondaryResultStorage.listForReport(context, sourceReportId)

        val items = targetKindPairs.mapNotNull { (targetId, kind) ->
            when (kind) {
                "PROMPT" -> TranslationItem(
                    id = "prompt", label = "Report prompt",
                    kind = TranslationKind.PROMPT, sourceText = report.prompt
                )
                "AGENT" -> {
                    val ag = report.agents.firstOrNull { it.agentId == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(ag.provider)?.id ?: ag.provider
                    TranslationItem(
                        id = "agent:${ag.agentId}",
                        label = "$prov / ${ag.model}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = ag.responseBody.orEmpty(),
                        target = ag.agentId
                    )
                }
                "META" -> {
                    val s = secondaries.firstOrNull { it.id == targetId } ?: return@mapNotNull null
                    val prov = AppService.findById(s.providerId)?.id ?: s.providerId
                    val name = s.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(s.kind)
                    TranslationItem(
                        id = "meta:${s.id}", label = "$name: $prov / ${s.model}",
                        kind = TranslationKind.META, sourceText = s.content.orEmpty(),
                        target = s.id
                    )
                }
                else -> null
            }
        }
        if (items.isEmpty()) return

        // Delete the rows we're replacing so the rerun doesn't double
        // up under the same (target, kind) pair.
        deleteRowIds.forEach { SecondaryResultStorage.delete(context, sourceReportId, it) }

        val state = appViewModel.uiState.value
        val aiSettings = state.aiSettings
        val template = aiSettings.getInternalPromptByName("Translate")?.text.orEmpty()
        val apiKey = aiSettings.getApiKey(provider)
        val baseUrl = aiSettings.getEffectiveEndpointUrl(provider)
        val pricing = PricingCache.getPricing(context, provider, model)

        // Merge our items into _translationRuns under this runId so
        // runOneTranslation can read the active TranslationRunState.
        // If a state already exists (live run still in flight),
        // append; otherwise create one.
        _translationRuns.update { runs ->
            val cur = runs[runId]
            val merged = if (cur != null) cur.copy(items = cur.items + items)
            else TranslationRunState(
                runId = runId,
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = items
            )
            runs + (runId to merged)
        }

        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        try {
            withTracerTags(reportId = sourceReportId, category = "Translation") {
                val sem = Semaphore(3)
                coroutineScope {
                    items.map { item ->
                        async {
                            sem.withPermit {
                                runOneTranslation(runId, context, provider, apiKey, model, baseUrl, template, targetLanguageName, item, pricing)
                            }
                        }
                    }.awaitAll()
                }
            }
            ReportStorage.bumpReportTimestamp(context, sourceReportId)
        } finally {
            appViewModel.updateUiState {
                it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
            }
        }
    }
}
