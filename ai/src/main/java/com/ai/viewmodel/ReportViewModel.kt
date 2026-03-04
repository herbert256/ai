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

    private data class ReportTask(
        val resultId: String,
        val reportAgent: ReportAgent,
        val runtimeAgent: Agent,
        val resolvedParams: AgentParameters
    )

    fun showGenericAgentSelection(title: String, prompt: String) {
        appViewModel.updateUiState { it.copy(
            genericPromptTitle = title, genericPromptText = prompt,
            showGenericAgentSelection = true, showGenericReportsDialog = false,
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(), genericReportsAgentResults = emptyMap(),
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
            val mergedParams = aiSettings.mergeParameters(parametersIds)
            val overrideParams = mergedParams ?: state.reportAdvancedParameters

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

            appViewModel.updateUiState { it.copy(
                showGenericAgentSelection = false, showGenericReportsDialog = true,
                genericReportsProgress = 0, genericReportsTotal = reportTasks.size,
                genericReportsSelectedAgents = selectedAgentIds + allModelIds,
                genericReportsAgentResults = emptyMap(), currentReportId = null
            ) }

            val userMatch = AppViewModel.USER_TAG_REGEX.find(prompt)
            val rapportText = userMatch?.groupValues?.get(1)?.trim() ?: state.externalOpenHtml
            val aiPrompt = if (userMatch != null) prompt.replace(userMatch.value, "").trim() else prompt

            val report = ReportStorage.createReportAsync(
                context = context, title = title.ifBlank { "AI Report" },
                prompt = aiPrompt, agents = reportTasks.map { it.reportAgent },
                rapportText = rapportText, reportType = reportType, closeText = state.externalCloseHtml
            )
            val reportId = report.id

            try {
                appViewModel.updateUiState { it.copy(currentReportId = reportId) }
                ApiTracer.currentReportId = reportId

                val semaphore = Semaphore(AppViewModel.REPORT_CONCURRENCY_LIMIT)
                coroutineScope {
                    reportTasks.map { task ->
                        async {
                            semaphore.withPermit { executeReportTask(context, reportId, aiPrompt, overrideParams, task) }
                        }
                    }.awaitAll()
                }

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

    private suspend fun executeReportTask(context: Context, reportId: String, aiPrompt: String, overrideParams: AgentParameters?, task: ReportTask) {
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
                baseUrl
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

        appViewModel.updateUiState { state ->
            state.copy(
                genericReportsProgress = state.genericReportsProgress + 1,
                genericReportsAgentResults = state.genericReportsAgentResults + (task.resultId to response)
            )
        }
    }

    private fun calculateResponseCost(context: Context, provider: AppService, model: String, tokenUsage: TokenUsage?): Double? {
        if (tokenUsage == null) return null
        return tokenUsage.apiCost ?: run {
            val pricing = PricingCache.getPricing(context, provider, model)
            tokenUsage.inputTokens * pricing.promptPrice + tokenUsage.outputTokens * pricing.completionPrice
        }
    }

    fun stopGenericReports(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        reportGenerationJob?.cancel()
        reportGenerationJob = null
        val state = appViewModel.uiState.value
        val selected = state.genericReportsSelectedAgents
        val current = state.genericReportsAgentResults
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

        appViewModel.updateUiState { it.copy(
            genericReportsProgress = state.genericReportsTotal, genericReportsAgentResults = updatedResults
        ) }
    }

    fun dismissGenericReportsDialog() {
        ApiTracer.currentReportId = null
        appViewModel.updateUiState { it.copy(
            showGenericReportsDialog = false, genericPromptTitle = "", genericPromptText = "",
            genericReportsProgress = 0, genericReportsTotal = 0,
            genericReportsSelectedAgents = emptySet(), genericReportsAgentResults = emptyMap(),
            currentReportId = null, reportAdvancedParameters = null
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
}
