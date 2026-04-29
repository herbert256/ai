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
        webSearchTool: Boolean = false
    ) {
        _agentResults.value = emptyMap()
        appViewModel.updateUiState { it.copy(
            genericPromptTitle = title, genericPromptText = prompt,
            reportImageBase64 = imageBase64, reportImageMime = imageMime,
            reportWebSearchTool = webSearchTool,
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
            val overrideParams = if (state.reportWebSearchTool) {
                (baseOverride ?: AgentParameters()).copy(webSearchTool = true)
            } else baseOverride

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
                webSearchTool = state.reportWebSearchTool
            )
            val reportId = report.id

            try {
                appViewModel.updateUiState { it.copy(currentReportId = reportId) }
                ApiTracer.currentReportId = reportId

                val semaphore = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                coroutineScope {
                    reportTasks.map { task ->
                        async {
                            semaphore.withPermit { executeReportTask(context, reportId, aiPrompt, overrideParams, task, imageBase64, imageMime) }
                        }
                    }.awaitAll()
                }
                appViewModel.updateUiState { it.copy(reportImageBase64 = null, reportImageMime = null, reportWebSearchTool = false) }

                if (reportRunningInBackground) {
                    reportRunningInBackground = false
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Report \"$title\" is ready", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                ApiTracer.currentReportId = null
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
        imageBase64: String? = null, imageMime: String? = null
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
        appViewModel.updateUiState { state ->
            state.copy(genericReportsProgress = state.genericReportsProgress + 1)
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

        ApiTracer.currentReportId = null

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
        ApiTracer.currentReportId = null
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
        ApiTracer.currentReportId = null
    }

    // ===== Secondary results: rerank + summarize =====

    private var secondaryJob: Job? = null

    /** Resolve the prompt template for [kind]: prefer a user-supplied
     *  Internal Prompt named "rerank" or "summarize", fall back to the
     *  GeneralSettings dedicated field, finally fall back to the hardcoded
     *  default in [SecondaryPrompts]. */
    private fun resolveTemplate(aiSettings: Settings, generalSettings: GeneralSettings, kind: SecondaryKind): String {
        val targetName = if (kind == SecondaryKind.RERANK) "rerank" else "summarize"
        val internal = aiSettings.prompts.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
        if (internal != null && internal.promptText.isNotBlank()) return internal.promptText
        val fromGs = if (kind == SecondaryKind.RERANK) generalSettings.rerankPrompt else generalSettings.summarizePrompt
        if (fromGs.isNotBlank()) return fromGs
        return if (kind == SecondaryKind.RERANK) SecondaryPrompts.DEFAULT_RERANK else SecondaryPrompts.DEFAULT_SUMMARIZE
    }

    /** Kick off a Rerank or Summarize run for [reportId] across [picks]
     *  (provider/model pairs). One [SecondaryResult] per pick is persisted
     *  independently — multi-model picks produce N separate viewable
     *  results. The Report screen blocks the buttons while a run is in
     *  flight (via [SecondaryRunState] on UiState). */
    fun runSecondary(
        scope: kotlinx.coroutines.CoroutineScope,
        context: Context,
        reportId: String,
        kind: SecondaryKind,
        picks: List<Pair<AppService, String>>
    ) {
        if (picks.isEmpty()) return
        secondaryJob?.cancel()
        // Persist last selection so the next click on Rerank/Summarize for
        // this report pre-checks the same models.
        appViewModel.saveSecondaryLastSelection(reportId, kind,
            picks.map { (p, m) -> "${p.id}:$m" }.toHashSet())
        appViewModel.updateUiState { it.copy(secondaryRun = SecondaryRunState(reportId, kind, picks.size)) }

        secondaryJob = scope.launch(Dispatchers.IO) {
            val state = appViewModel.uiState.value
            val aiSettings = state.aiSettings
            val generalSettings = state.generalSettings
            val report = ReportStorage.getReport(context, reportId) ?: run {
                appViewModel.updateUiState { it.copy(secondaryRun = null) }
                return@launch
            }
            val template = resolveTemplate(aiSettings, generalSettings, kind)
            val resultsBlock = buildResultsBlock(report)
            val successfulCount = report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
            val resolvedPrompt = resolveSecondaryPrompt(
                template, question = report.prompt, results = resultsBlock,
                count = successfulCount, title = report.title
            )

            val sem = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
            coroutineScope {
                picks.map { (provider, model) ->
                    async {
                        sem.withPermit {
                            executeSecondaryTask(context, reportId, kind, provider, model, resolvedPrompt, aiSettings)
                            appViewModel.updateUiState { s ->
                                val cur = s.secondaryRun
                                if (cur != null && cur.reportId == reportId && cur.kind == kind) {
                                    s.copy(secondaryRun = cur.copy(completed = cur.completed + 1))
                                } else s
                            }
                        }
                    }
                }.awaitAll()
            }
            // Clear the in-progress state when the whole batch lands.
            appViewModel.updateUiState { s ->
                if (s.secondaryRun?.reportId == reportId && s.secondaryRun.kind == kind) s.copy(secondaryRun = null) else s
            }
        }
    }

    private suspend fun executeSecondaryTask(
        context: Context, reportId: String, kind: SecondaryKind,
        provider: AppService, model: String, resolvedPrompt: String, aiSettings: Settings
    ) {
        val apiKey = aiSettings.getApiKey(provider)
        val agentName = "${provider.displayName} / $model"
        val placeholder = SecondaryResultStorage.create(context, reportId, kind, provider.id, model, agentName)

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
                kind = when (kind) { SecondaryKind.RERANK -> "rerank"; SecondaryKind.SUMMARIZE -> "summarize" }
            )
        }
    }

    fun deleteSecondaryResult(context: Context, reportId: String, resultId: String) {
        SecondaryResultStorage.delete(context, reportId, resultId)
    }
}
