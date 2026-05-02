package com.ai.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ai.data.*
import com.ai.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        scope: kotlinx.coroutines.CoroutineScope,
        context: Context,
        selectedAgentIds: Set<String>,
        selectedSwarmIds: Set<String> = emptySet(),
        directModelIds: Set<String> = emptySet(),
        parametersIds: List<String> = emptyList(),
        selectionParamsById: Map<String, List<String>> = emptyMap(),
        reportType: ReportType = ReportType.CLASSIC
    ) {
        reportGenerationJob?.cancel()
        reportGenerationJob = scope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val prompt = state.genericPromptText
            val title = state.genericPromptTitle
            val externalSystemPrompt = state.externalSystemPrompt
            val imageBase64 = state.reportImageBase64
            val imageMime = state.reportImageMime
            val mergedParams = aiSettings.mergeParameters(parametersIds)
            val baseOverride = mergedParams ?: state.reportAdvancedParameters
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
                reasoningEffort = state.reportReasoningEffort
            )
            val reportId = report.id

            withTracerTags(reportId = reportId, category = "Report") {
                appViewModel.updateUiState { it.copy(currentReportId = reportId) }

                val semaphore = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                coroutineScope {
                    reportTasks.map { task ->
                        async {
                            semaphore.withPermit { executeReportTask(context, reportId, aiPrompt, overrideParams, task, imageBase64, imageMime) }
                        }
                    }.awaitAll()
                }
                appViewModel.updateUiState { it.copy(reportImageBase64 = null, reportImageMime = null, reportWebSearchTool = false, reportReasoningEffort = null) }

                if (reportRunningInBackground) {
                    reportRunningInBackground = false
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Report \"$title\" is ready", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resolveSystemPromptText(aiSettings: Settings, agentSpId: String?, groupSpId: String?): String? {
        return (groupSpId ?: agentSpId)?.let { aiSettings.getSystemPromptById(it)?.prompt }
    }

    private fun findFlockSystemPromptIdForAgent(aiSettings: Settings, agentId: String): String? {
        return aiSettings.flocks.filter { agentId in it.agentIds && it.systemPromptId != null }
            .firstNotNullOfOrNull { flock -> flock.systemPromptId?.takeIf { aiSettings.getSystemPromptById(it) != null } }
    }

    private fun findSwarmSystemPromptIdForMember(aiSettings: Settings, provider: AppService, model: String): String? {
        return aiSettings.swarms.filter { swarm ->
            swarm.systemPromptId != null && swarm.members.any { it.provider.id == provider.id && it.model == model }
        }.firstNotNullOfOrNull { swarm -> swarm.systemPromptId?.takeIf { aiSettings.getSystemPromptById(it) != null } }
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
                ReportAgent(sid, "${member.provider.displayName} / ${member.model}", member.provider.id, member.model, ReportStatus.PENDING),
                Agent(sid, "${member.provider.displayName} / ${member.model}", member.provider, member.model, aiSettings.getApiKey(member.provider)),
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
        ReportStorage.markAgentRunningAsync(context, reportId, task.resultId, aiPrompt)

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
                imageMime
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Honor structured cancellation (Stop / nav-away) instead of
            // persisting a fake error onto the agent row.
            throw e
        } catch (e: Exception) {
            AnalysisResponse(service = task.runtimeAgent.provider, analysis = null, error = e.message ?: "Unknown error")
        }
        val durationMs = System.currentTimeMillis() - startTime
        val cost = calculateResponseCost(context, task.runtimeAgent.provider, task.runtimeAgent.model, response.tokenUsage)

        if (response.isSuccess) {
            ReportStorage.markAgentSuccessAsync(context, reportId, task.resultId,
                response.httpStatusCode ?: 200, response.httpHeaders, response.analysis,
                response.tokenUsage, cost, response.citations, response.searchResults,
                response.relatedQuestions, response.rawUsageJson, durationMs)
        } else {
            ReportStorage.markAgentErrorAsync(context, reportId, task.resultId,
                response.httpStatusCode, response.error, response.httpHeaders, response.analysis, durationMs)
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
    }

    private fun calculateResponseCost(context: Context, provider: AppService, model: String, tokenUsage: TokenUsage?): Double? {
        if (tokenUsage == null) return null
        return PricingCache.computeCost(tokenUsage, PricingCache.getPricing(context, provider, model))
    }

    /**
     * Reverse the persisted ReportAgent rows into ReportModel entries the selection screen
     * understands. Real-agent rows (UUID id, still resolvable in aiSettings) come back as
     * agent-typed models; "swarm:provider:model" rows and orphaned ones come back as
     * direct provider/model entries.
     */
    private fun reportToModels(report: com.ai.data.Report, aiSettings: Settings): List<ReportModel> {
        return report.agents.mapNotNull { ra ->
            val provider = AppService.findById(ra.provider) ?: return@mapNotNull null
            if (ra.agentId.startsWith("swarm:")) toReportModel(provider, ra.model)
            else aiSettings.getAgentById(ra.agentId)?.let { expandAgentToModel(it, aiSettings) }
                ?: toReportModel(provider, ra.model)
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
     * Update the saved report's title + prompt (and the matching UiState) without
     * triggering generation. Used by the Edit-prompt overlay — the user reviews the new
     * prompt on the result screen and re-runs via the Actions / Regenerate button when
     * they're ready. The model list and parameter set on disk are untouched.
     */
    suspend fun updateReportPrompt(context: Context, reportId: String, newTitle: String, newPrompt: String) {
        withContext(Dispatchers.IO) {
            ReportStorage.updateReportPromptAndTitle(context, reportId, newTitle, newPrompt)
        }
        appViewModel.updateUiState { it.copy(
            genericPromptTitle = newTitle, genericPromptText = newPrompt,
            hasPendingPromptChange = true
        ) }
    }

    /**
     * Re-run a previously generated report end-to-end with the same prompt, agent set,
     * and parameter selections.
     */
    fun regenerateReport(context: Context, reportId: String, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return@launch
            val ai = appViewModel.uiState.value.aiSettings
            val staged = appViewModel.uiState.value.stagedReportModels
            // Prefer a staged list from Edit Models — falls back to the on-disk agent set.
            val rebuilt = if (staged.isNotEmpty()) staged else reportToModels(report, ai)
            val agentIds = rebuilt.filter { it.type == "agent" }.mapNotNull { it.agentId }.toSet()
            val swarmIds = rebuilt.filter { it.sourceType == "swarm" && it.type == "model" }.mapNotNull { it.sourceId }.toSet()
            val directIds = rebuilt.filter { it.sourceType == "model" }.map { "swarm:${it.provider.id}:${it.model}" }.toSet()
            // Reset the screen state and consume the staged list, then drive a generation pass.
            // Re-seed the image fields and the web-search toggle from the saved report so a
            // regenerate carries the same attachment + tool flag without the user having
            // to re-attach or re-tick.
            _agentResults.value = emptyMap()
            appViewModel.updateUiState { it.copy(
                showGenericReportsDialog = false, currentReportId = null,
                genericReportsProgress = 0, genericReportsTotal = 0,
                genericReportsSelectedAgents = emptySet(),
                genericPromptTitle = report.title, genericPromptText = report.prompt,
                reportImageBase64 = report.imageBase64, reportImageMime = report.imageMime,
                reportWebSearchTool = report.webSearchTool,
                reportReasoningEffort = report.reasoningEffort,
                pendingReportModels = emptyList(),
                stagedReportModels = emptyList(),
                hasPendingPromptChange = false,
                hasPendingParametersChange = false
            ) }
            generateGenericReports(
                scope = scope, context = context, selectedAgentIds = agentIds,
                selectedSwarmIds = swarmIds, directModelIds = directIds,
                parametersIds = emptyList(), reportType = report.reportType
            )
        }
    }

    /** Delete a report file and, if it's the one currently shown, dismiss the screen state. */
    fun deleteReport(context: Context, reportId: String) {
        val cleared = appViewModel.uiState.value.currentReportId == reportId
        ReportStorage.deleteReport(context, reportId)
        if (cleared) dismissGenericReportsDialog()
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
        if (_agentResults.value.isNotEmpty()) return
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
        if (rebuilt.isNotEmpty()) _agentResults.value = rebuilt
    }

    fun stopGenericReports(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        reportGenerationJob?.cancel()
        reportGenerationJob = null
        val state = appViewModel.uiState.value
        val selected = state.genericReportsSelectedAgents
        val current = _agentResults.value
        val reportId = state.currentReportId

        val updatedResults = selected.associate { agentId ->
            current[agentId]?.let { agentId to it } ?: run {
                val agent = state.aiSettings.getAgentById(agentId)
                val service = agent?.provider
                    ?: agentId.takeIf { it.startsWith("swarm:") }?.removePrefix("swarm:")?.substringBefore(':')?.let { AppService.findById(it) }
                    ?: AppService.entries.firstOrNull() ?: AppService.findById("OPENAI")!!
                agentId to AnalysisResponse(service = service, analysis = null, error = "Stopped by user")
            }
        }

        // No need to touch ApiTracer.currentReportId here — the report
        // job's own withTracerTags block restores the previous value
        // when its scope ends (via the agent fan-out completing).

        if (reportId != null) {
            scope.launch(Dispatchers.IO) {
                selected.filterNot { current.containsKey(it) }.forEach { agentId ->
                    ReportStorage.markAgentStoppedAsync(context, reportId, agentId)
                }
            }
        }

        _agentResults.value = updatedResults
        appViewModel.updateUiState { it.copy(genericReportsProgress = state.genericReportsTotal) }
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

    // ===== Secondary results: rerank + summarize =====

    /** Resolve the prompt template for [kind]: prefer a user-supplied
     *  Internal Prompt named "rerank", "summarize", or "compare", fall back
     *  to the GeneralSettings dedicated field, finally fall back to the
     *  hardcoded default in [SecondaryPrompts]. Moderation doesn't use a
     *  chat prompt — it goes through the dedicated /v1/moderations
     *  endpoint — so this returns "" for MODERATION; callers must check
     *  the kind before using the result. */
    private fun resolveTemplate(aiSettings: Settings, generalSettings: GeneralSettings, kind: SecondaryKind): String {
        if (kind == SecondaryKind.MODERATION) return ""
        val fromGs = when (kind) {
            SecondaryKind.RERANK -> generalSettings.rerankPrompt
            SecondaryKind.SUMMARIZE -> generalSettings.summarizePrompt
            SecondaryKind.COMPARE -> generalSettings.comparePrompt
            SecondaryKind.MODERATION -> "" // unreachable
            SecondaryKind.TRANSLATE -> "" // unreachable — translation runs through its own startTranslation flow
        }
        if (fromGs.isNotBlank()) return fromGs
        return when (kind) {
            SecondaryKind.RERANK -> SecondaryPrompts.DEFAULT_RERANK
            SecondaryKind.SUMMARIZE -> SecondaryPrompts.DEFAULT_SUMMARIZE
            SecondaryKind.COMPARE -> SecondaryPrompts.DEFAULT_COMPARE
            SecondaryKind.MODERATION -> "" // unreachable
            SecondaryKind.TRANSLATE -> "" // unreachable
        }
    }

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
    fun runSecondary(
        scope: kotlinx.coroutines.CoroutineScope,
        context: Context,
        reportId: String,
        kind: SecondaryKind,
        picks: List<Pair<AppService, String>>,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports
    ) {
        if (picks.isEmpty()) return
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }

        scope.launch(Dispatchers.IO) {
            // Tag every API call this batch makes with the parent
            // report's id and a kind-specific category. Without the
            // reportId tag the resulting trace files would land with
            // reportId=null and the report's JSON export wouldn't
            // pull them in (same goes for a translated report's
            // source/ bucket — meta runs on the source report would
            // be invisible). withTracerTags saves and restores both
            // values so this works correctly when nested.
            val cat = when (kind) {
                SecondaryKind.RERANK -> "Report rerank"
                SecondaryKind.SUMMARIZE -> "Report summarize"
                SecondaryKind.COMPARE -> "Report compare"
                SecondaryKind.MODERATION -> "Report moderation"
                SecondaryKind.TRANSLATE -> "Report translate"
            }
            try {
              withTracerTags(reportId = reportId, category = cat) {
                val state = appViewModel.uiState.value
                val aiSettings = state.aiSettings
                val generalSettings = state.generalSettings
                val report = ReportStorage.getReport(context, reportId) ?: return@launch
                val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
                // Bump the parent report's timestamp so it sorts to the top
                // of the History list — adding a meta result is a real
                // update to the report, not a passive read.
                ReportStorage.bumpReportTimestamp(context, reportId)
                val template = resolveTemplate(aiSettings, generalSettings, kind)
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
                }
                val successfulCount = if (includeIds != null) includeIds.size
                    else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }

                // Multi-language fan-out for SUMMARIZE / COMPARE: one
                // batch per language present on the report. RERANK and
                // MODERATION always run on the original — their content
                // is structured JSON / per-response classifications and
                // doesn't translate. The Original language is encoded
                // as null and the SecondaryResult.targetLanguage stays
                // null for it; translations get the human English name.
                val translationLanguages = if (kind == SecondaryKind.SUMMARIZE || kind == SecondaryKind.COMPARE) {
                    val nativeByLang = LinkedHashMap<String, String?>()
                    allSecondaries
                        .filter { it.kind == SecondaryKind.TRANSLATE && !it.targetLanguage.isNullOrBlank() }
                        .forEach { tr ->
                            val l = tr.targetLanguage!!
                            if (l !in nativeByLang) nativeByLang[l] = tr.targetLanguageNative
                        }
                    nativeByLang
                } else LinkedHashMap()
                val languages: List<Pair<String?, String?>> = listOf<Pair<String?, String?>>(null to null) +
                    translationLanguages.map { (lang, native) -> lang to native }

                // Per-batch semaphore: bounds the in-batch fan-out across
                // every (language, pick) tuple. Overlapping batches are
                // not capped against each other (intentional; that's the
                // whole point of allowing concurrent runs).
                val sem = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                coroutineScope {
                    languages.flatMap { (lang, langNative) ->
                        val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, lang, includeIds)
                        val resolvedPrompt = resolveSecondaryPrompt(
                            template, question = translatedPrompt, results = resultsBlock,
                            count = successfulCount, title = report.title
                        )
                        picks.map { (provider, model) ->
                            async {
                                sem.withPermit {
                                    executeSecondaryTask(context, reportId, kind, provider, model, resolvedPrompt, aiSettings, report, lang, langNative)
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

    /** Build the (prompt, @RESULTS@ block) pair for one language's
     *  Summarize / Compare batch. When [language] is null the original
     *  prompt + raw agent bodies are returned. Otherwise translated
     *  text is taken from TRANSLATE rows tagged with [language] —
     *  PROMPT:prompt for the question, AGENT:<agentId> for each
     *  agent's body. Falls back to the original text per-item if a
     *  translation is missing so a partial translation set still
     *  produces a coherent batch. */
    private fun buildLanguageInputs(
        report: Report,
        secondaries: List<SecondaryResult>,
        language: String?,
        includeIds: Set<Int>?
    ): Pair<String, String> {
        if (language == null) return report.prompt to buildResultsBlock(report, includeIds)
        val byTarget = secondaries
            .filter { it.kind == SecondaryKind.TRANSLATE && it.targetLanguage == language && !it.content.isNullOrBlank() }
            .associateBy { (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "") }
        val translatedPrompt = byTarget["PROMPT:prompt"]?.content ?: report.prompt
        val sb = StringBuilder()
        val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        var emitted = 0
        val total = if (includeIds != null) successful.indices.count { (it + 1) in includeIds } else successful.size
        successful.forEachIndexed { idx, agent ->
            val originalId = idx + 1
            if (includeIds != null && originalId !in includeIds) return@forEachIndexed
            val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
            sb.append("[").append(originalId).append("] provider=").append(provDisplay)
                .append(" model=").append(agent.model).append('\n')
            val body = byTarget["AGENT:${agent.agentId}"]?.content ?: (agent.responseBody?.trim() ?: "")
            sb.append(body)
            emitted++
            if (emitted != total) sb.append("\n\n")
        }
        return translatedPrompt to sb.toString()
    }

    private suspend fun executeSecondaryTask(
        context: Context, reportId: String, kind: SecondaryKind,
        provider: AppService, model: String, resolvedPrompt: String, aiSettings: Settings,
        report: Report,
        targetLanguage: String? = null,
        targetLanguageNative: String? = null
    ) {
        val apiKey = aiSettings.getApiKey(provider)
        val langSuffix = targetLanguage?.let { " [$it]" } ?: ""
        val agentName = "${provider.displayName} / $model$langSuffix"
        val placeholder = SecondaryResultStorage.create(context, reportId, kind, provider.id, model, agentName)
            .copy(targetLanguage = targetLanguage, targetLanguageNative = targetLanguageNative)
        SecondaryResultStorage.save(context, placeholder)

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
            SecondaryResultStorage.save(context, placeholder.copy(
                content = r.content,
                errorMessage = r.errorMessage,
                durationMs = r.durationMs
            ))
            if (r.errorMessage == null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    provider, model, 0, 0, 0, kind = "moderation"
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

        SecondaryResultStorage.save(context, placeholder.copy(
            content = response.analysis,
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
                    SecondaryKind.SUMMARIZE -> "summarize"
                    SecondaryKind.COMPARE -> "compare"
                    SecondaryKind.MODERATION -> "moderation"
                    SecondaryKind.TRANSLATE -> "translate"
                }
            )
        }
    }

    fun deleteSecondaryResult(context: Context, reportId: String, resultId: String) {
        SecondaryResultStorage.delete(context, reportId, resultId)
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
    fun regenerateAgent(scope: kotlinx.coroutines.CoroutineScope, context: Context, reportId: String, agentId: String) {
        scope.launch(Dispatchers.IO) {
            withTracerTags(reportId = reportId, category = "Report regenerate agent") {
            val report = ReportStorage.getReport(context, reportId) ?: return@launch
            val ra = report.agents.find { it.agentId == agentId } ?: return@launch
            val provider = AppService.findById(ra.provider) ?: return@launch
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
            // Mirror the override-params recipe used by regenerateReport so
            // the per-row "Call model API again" path also picks up any
            // pending Edit Parameters changes the user staged via the
            // banner — without this the banner was a lie for this flow.
            // The report's web-search-tool flag is OR'd on top so a regen
            // doesn't lose 🌐 if the original report had it on.
            val baseOverride = state.reportAdvancedParameters
            val withWeb = if (report.webSearchTool) {
                (baseOverride ?: AgentParameters()).copy(webSearchTool = true)
            } else baseOverride
            // Same overlay for the per-report 🧠 reasoning level — read
            // off the saved report so a per-row re-run keeps the level
            // even if the user navigated away and lost UiState.
            val overrideParams = if (report.reasoningEffort != null) {
                (withWeb ?: AgentParameters()).copy(reasoningEffort = report.reasoningEffort)
            } else withWeb
            executeReportTask(
                context, reportId, report.prompt, overrideParams, task,
                report.imageBase64, report.imageMime, isRegeneration = true
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
    enum class TranslationKind { PROMPT, AGENT_RESPONSE, SUMMARY, COMPARE }

    /** One row on the translation progress screen. [sourceText] is what
     *  gets fed to the model; [translatedText] is filled in on DONE.
     *  [costCents] is the per-call cost in cents (input + output) for
     *  the running tally. [target] is the SecondaryResult.id when the
     *  source is a SUMMARY / COMPARE — used so the new report can
     *  recreate that meta result with translated content. */
    data class TranslationItem(
        val id: String,
        val label: String,
        val kind: TranslationKind,
        val sourceText: String,
        val target: String? = null,
        val status: TranslationStatus = TranslationStatus.PENDING,
        val translatedText: String? = null,
        val errorMessage: String? = null,
        val costCents: Double = 0.0,
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
        val sourceReportId: String,
        val targetLanguageName: String,
        val targetLanguageNative: String,
        val items: List<TranslationItem>,
        val totalCostCents: Double = 0.0,
        val finished: Boolean = false,
        val cancelled: Boolean = false
    ) {
        val total: Int get() = items.size
        val completed: Int get() = items.count { it.status == TranslationStatus.DONE || it.status == TranslationStatus.ERROR }
        val isRunning: Boolean get() = !finished && !cancelled && completed < total
        val isFinished: Boolean get() = finished
    }

    private val _translationRun = MutableStateFlow<TranslationRunState?>(null)
    val translationRun: StateFlow<TranslationRunState?> = _translationRun.asStateFlow()
    private var translationJob: Job? = null

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
        scope: kotlinx.coroutines.CoroutineScope,
        context: Context,
        sourceReportId: String,
        targetLanguageName: String,
        targetLanguageNative: String,
        provider: AppService,
        model: String
    ) {
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val generalSettings = state.generalSettings
            val sourceReport = ReportStorage.getReport(context, sourceReportId) ?: run {
                _translationRun.value = null
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
                    val provDisplay = AppService.findById(agent.provider)?.displayName ?: agent.provider
                    items += TranslationItem(
                        id = "agent:${agent.agentId}",
                        label = "$provDisplay / ${agent.model}",
                        kind = TranslationKind.AGENT_RESPONSE,
                        sourceText = agent.responseBody!!,
                        target = agent.agentId
                    )
                }
            secondaries.filter { it.kind == SecondaryKind.SUMMARIZE && !it.content.isNullOrBlank() }
                .forEachIndexed { idx, s ->
                    val provDisplay = AppService.findById(s.providerId)?.displayName ?: s.providerId
                    items += TranslationItem(
                        id = "summary:${s.id}",
                        label = "Summary ${idx + 1}: $provDisplay / ${s.model}",
                        kind = TranslationKind.SUMMARY,
                        sourceText = s.content!!,
                        target = s.id
                    )
                }
            secondaries.filter { it.kind == SecondaryKind.COMPARE && !it.content.isNullOrBlank() }
                .forEachIndexed { idx, s ->
                    val provDisplay = AppService.findById(s.providerId)?.displayName ?: s.providerId
                    items += TranslationItem(
                        id = "compare:${s.id}",
                        label = "Compare ${idx + 1}: $provDisplay / ${s.model}",
                        kind = TranslationKind.COMPARE,
                        sourceText = s.content!!,
                        target = s.id
                    )
                }

            _translationRun.value = TranslationRunState(
                sourceReportId = sourceReportId,
                targetLanguageName = targetLanguageName,
                targetLanguageNative = targetLanguageNative,
                items = items
            )

            val template = generalSettings.translatePrompt.ifBlank { SecondaryPrompts.DEFAULT_TRANSLATE }
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
                            sem.withPermit { runOneTranslation(context, provider, apiKey, model, baseUrl, template, targetLanguageName, item, pricing) }
                        }
                    }.awaitAll()
                }

                // All done — write a TRANSLATE SecondaryResult per item
                // onto the source report. No new report is created.
                val finalState = _translationRun.value ?: return@launch
                if (finalState.cancelled) return@launch
                saveTranslationSecondaries(context, finalState, provider, model, pricing)
                ReportStorage.bumpReportTimestamp(context, sourceReportId)
                _translationRun.update { it?.copy(finished = true) }
            }
        }
    }

    private suspend fun runOneTranslation(
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
        _translationRun.update { state ->
            state?.copy(items = state.items.map { if (it.id == item.id) it.copy(status = TranslationStatus.RUNNING) else it })
        }
        val resolved = template
            .replace("@LANGUAGE@", targetLanguageName)
            .replace("@TEXT@", item.sourceText)
        val agent = Agent(
            id = "translate:${provider.id}:$model",
            name = "Translate / ${provider.displayName} / $model",
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
        val costCents = costDollars * 100
        _translationRun.update { state ->
            if (state == null) return@update null
            val updated = state.items.map {
                if (it.id != item.id) it
                else if (response.isSuccess) it.copy(
                    status = TranslationStatus.DONE,
                    translatedText = response.analysis,
                    costCents = costCents,
                    tokenUsage = tu,
                    durationMs = callDurationMs
                )
                else it.copy(
                    status = TranslationStatus.ERROR,
                    errorMessage = response.error ?: "Empty response",
                    costCents = costCents,
                    tokenUsage = tu,
                    durationMs = callDurationMs
                )
            }
            state.copy(items = updated, totalCostCents = updated.sumOf { it.costCents })
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
    }

    /** Persist one TRANSLATE [SecondaryResult] per translated item onto
     *  the source report. The viewer and HTML/Zipped HTML exporters
     *  group these rows by [SecondaryResult.targetLanguage] to render
     *  the Original | Dutch | German picker. Errored rows are saved
     *  with [SecondaryResult.errorMessage] set so the user still sees
     *  the failed call in the cost table / language view. */
    private fun saveTranslationSecondaries(
        context: Context,
        run: TranslationRunState,
        translateProvider: AppService,
        translateModel: String,
        translatePricing: PricingCache.ModelPricing
    ) {
        // One UUID for every row this invocation writes — lets the
        // result page collapse them into a single aggregate
        // "translation run" row even when the same language has been
        // translated more than once.
        val runId = java.util.UUID.randomUUID().toString()
        for (item in run.items) {
            val tu = item.tokenUsage
            val inCost = tu?.let { it.inputTokens * translatePricing.promptPrice }
            val outCost = tu?.let { it.outputTokens * translatePricing.completionPrice }
            val labelPrefix = "Translate: ${item.label.ifBlank { item.kind.name.lowercase() }}"
            val (srcKind, srcTargetId) = when (item.kind) {
                TranslationKind.PROMPT -> "PROMPT" to "prompt"
                TranslationKind.AGENT_RESPONSE -> "AGENT" to (item.target ?: "")
                TranslationKind.SUMMARY -> "SUMMARY" to (item.target ?: "")
                TranslationKind.COMPARE -> "COMPARE" to (item.target ?: "")
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
    }

    fun cancelTranslation() {
        translationJob?.cancel()
        _translationRun.update { it?.copy(cancelled = true) }
    }

    fun consumeTranslationRun() {
        _translationRun.value = null
    }
}
