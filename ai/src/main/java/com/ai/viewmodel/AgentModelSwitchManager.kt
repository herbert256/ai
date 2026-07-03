package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.AgentParameters
import com.ai.data.AnalysisResponse
import com.ai.data.ApiCallCaps
import com.ai.data.AppService
import com.ai.data.AuditLog
import com.ai.data.PricingCache
import com.ai.data.ProviderThrottle
import com.ai.data.ReportAgent
import com.ai.data.ReportStorage
import com.ai.data.withTraceFilenameSink
import com.ai.data.withTracerTags
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Tracer / usage-stats category for the model-switch candidate call. */
private const val AGENT_MODEL_SWITCH_CALL_KIND = "report/model-switch"

/**
 * "Switch model / agent" for a PRIMARY answer — the report-agent
 * counterpart of [SecondaryModelSwitchManager]. Re-runs the report's own
 * prompt (image + knowledge bases + captured config included) against a
 * user-picked agent or provider+model, holds the candidate IN MEMORY
 * (preview), and only commits on Use.
 *
 * Apply is NOT an in-place mutation: a report row's id encodes its
 * provider+model ("swarm:provider:model") and is referenced by
 * secondaries, notes and icon records, so re-pointing the row would
 * desync every rebuild path. Instead Use appends a fresh row carrying
 * the previewed response and removes the old row through the same
 * orphan cascade Edit-models uses — no other answer is re-run or
 * re-billed, and the removed row's spend lands in the report's
 * costs-from-deleted-items bank like any other removal.
 *
 * Reuses [ModelSwitchSelection] / [ModelSwitchResult] / [ModelSwitchState]
 * so the existing pick + preview screens work verbatim.
 */
class AgentModelSwitchManager internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) {
    private val _states = MutableStateFlow<Map<String, ModelSwitchState>>(emptyMap())
    val states: StateFlow<Map<String, ModelSwitchState>> = _states.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun clear(reportId: String, agentId: String) {
        val key = ModelSwitchState.key(reportId, agentId)
        jobs.remove(key)?.cancel()
        _states.update { it - key }
    }

    fun cancelAllForReport(reportId: String) {
        val prefix = "$reportId|"
        jobs.keys.filter { it.startsWith(prefix) }.forEach { jobs.remove(it)?.cancel() }
        _states.update { it.filterKeys { k -> !k.startsWith(prefix) } }
    }

    fun startModelSwitch(context: Context, reportId: String, agentId: String, selection: ModelSwitchSelection): Job {
        val key = ModelSwitchState.key(reportId, agentId)
        jobs.remove(key)?.cancel()
        _states.update { it + (key to ModelSwitchState(reportId, agentId, selection, ModelSwitchResult.Running, isRunning = true)) }
        fun finish(result: ModelSwitchResult) =
            _states.update { m -> m[key]?.let { m + (key to it.copy(result = result, isRunning = false)) } ?: m }
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                val result = runCandidate(context, reportId, agentId, selection)
                finish(result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(ModelSwitchResult.Error((e.message ?: "Model switch failed").take(2000), null, null, null))
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
        return job
    }

    private suspend fun runCandidate(
        context: Context, reportId: String, agentId: String, selection: ModelSwitchSelection
    ): ModelSwitchResult {
        val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
            ?: return ModelSwitchResult.Error("Report not found", null, null, null)
        if (report.agents.none { it.agentId == agentId }) {
            return ModelSwitchResult.Error("This model response no longer exists in the report", null, null, null)
        }
        // A report answers each provider+model at most once — switching onto
        // a pair that's already answered would collide with that row's id.
        if (report.agents.any { it.agentId != agentId && it.provider == selection.provider.id && it.model == selection.model }) {
            return ModelSwitchResult.Error(
                "This report already has an answer from ${selection.provider.id} / ${shortModelName(selection.model)}.",
                null, null, null
            )
        }
        val state = appViewModel.uiState.value
        val ai = state.aiSettings
        // Build a runnable task for the NEW pair via the shared replay-task
        // builder (report-captured config), using a synthetic direct-model row.
        val synthetic = ReportAgent(
            agentId = "swarm:${selection.provider.id}:${selection.model}",
            agentName = selection.label,
            provider = selection.provider.id,
            model = selection.model
        )
        val task = reportViewModel.buildTemperatureSweepTask(report, state, synthetic)
            ?: return ModelSwitchResult.Error("${selection.provider.id} / ${selection.model} is not runnable on this device", null, null, null)
        val canReason = ai.acceptsReasoningEffortParam(task.runtimeAgent.provider, task.runtimeAgent.model)
        val canWeb = ai.isWebSearchCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
        val canVision = ai.isVisionCapable(task.runtimeAgent.provider, task.runtimeAgent.model)
        val baseOverride = reportViewModel.resolveReportOverrideParams(
            ai, report.parameterPresetIds, report.advancedParameters,
            report.webSearchTool, report.reasoningEffort
        )
        val gatedOverride = (baseOverride ?: AgentParameters()).copy(
            webSearchTool = (baseOverride?.webSearchTool == true || report.webSearchTool) && canWeb,
            reasoningEffort = if (canReason) baseOverride?.reasoningEffort else null
        )
        // The picked agent's own presets / system prompt win over the
        // report-level config — same overlay the prompt-edit replay uses.
        val selectionOverride = promptEditOverrideParams(ai, selection.paramsIds, selection.systemPromptId)
        val baseResolved = if (canReason) task.resolvedParams else task.resolvedParams.copy(reasoningEffort = null)
        val finalParams = overlayAgentParameters(
            overlayAgentParameters(baseResolved, gatedOverride),
            selectionOverride
        )!!.let { merged ->
            merged.copy(
                webSearchTool = merged.webSearchTool && canWeb,
                reasoningEffort = if (canReason) merged.reasoningEffort else null
            )
        }
        val effectiveImage = if (canVision) report.imageBase64 else null
        val effectiveImageMime = if (canVision) report.imageMime else null
        val baseUrl = ai.getEffectiveEndpointUrlForAgent(task.runtimeAgent)
        return withTracerTags(reportId = reportId, category = AGENT_MODEL_SWITCH_CALL_KIND) {
            val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val startTime = System.currentTimeMillis()
            val response = try {
                val permitHold = acquireThrottledPermits(ApiCallCaps.report, providerHost(task.runtimeAgent.provider))
                try {
                    withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                        withTraceFilenameSink(traceSink) {
                            appViewModel.repository.analyzeWithAgentStreaming(
                                task.runtimeAgent, "", report.prompt,
                                finalParams, null,
                                context, baseUrl, effectiveImage, effectiveImageMime,
                                knowledgeBaseIds = report.knowledgeBaseIds,
                                aiSettings = ai
                            ) { /* transient candidate; no live preview */ }
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
            val traceFile = traceSink.get()
            if (response.error == null && response.tokenUsage != null) {
                appViewModel.settingsPrefs.updateUsageStatsAsync(
                    task.runtimeAgent.provider, task.runtimeAgent.model,
                    response.tokenUsage, kind = AGENT_MODEL_SWITCH_CALL_KIND, durationMs = durationMs
                )
            }
            if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                val pricing = PricingCache.getPricing(context, task.runtimeAgent.provider, task.runtimeAgent.model)
                val (inCost, outCost) = response.tokenUsage?.let { PricingCache.computeInOutCost(it, pricing) } ?: (null to null)
                ModelSwitchResult.Success(response.analysis, response.tokenUsage, inCost, outCost, durationMs, traceFile)
            } else {
                ModelSwitchResult.Error(response.error ?: "No response body", response.httpStatusCode, durationMs, traceFile)
            }
        }
    }

    /** Commit the previewed candidate: append the new pair's row carrying
     *  the previewed response, then remove the old row through the standard
     *  cascade (its spend lands in costs-from-deleted-items). Returns null
     *  when there is no successful preview to apply. */
    fun applyModelSwitch(context: Context, reportId: String, agentId: String): Job? {
        val key = ModelSwitchState.key(reportId, agentId)
        val st = _states.value[key] ?: return null
        val success = st.result as? ModelSwitchResult.Success ?: return null
        val selection = st.selection
        return appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            val report = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) } ?: return@launch
            val oldAgent = report.agents.firstOrNull { it.agentId == agentId } ?: return@launch
            val newId = "swarm:${selection.provider.id}:${selection.model}"
            // Re-check the collision guard — another flow may have added the
            // pair while the preview sat open.
            if (report.agents.any { it.agentId != agentId && it.provider == selection.provider.id && it.model == selection.model }) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context,
                        "This report already has an answer from ${selection.provider.id} / ${shortModelName(selection.model)}.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val agentName = "${selection.provider.id} / ${shortModelName(selection.model)}"
            withContext(Dispatchers.IO) {
                if (newId != agentId) {
                    ReportStorage.appendAgents(context, reportId, listOf(
                        ReportAgent(agentId = newId, agentName = agentName,
                            provider = selection.provider.id, model = selection.model)
                    ))
                    // Future regenerates replay the picked presets for this row.
                    if (selection.paramsIds.isNotEmpty()) {
                        ReportStorage.setSelectionParamsForRow(context, reportId, newId, selection.paramsIds)
                    }
                }
                ReportStorage.markAgentSuccess(
                    context, reportId, newId, httpStatus = 200,
                    responseHeaders = null, responseBody = success.content,
                    tokenUsage = success.tokenUsage, cost = null,
                    inputCost = success.inputCost, outputCost = success.outputCost,
                    durationMs = success.durationMs, traceFile = success.traceFile
                )
            }
            if (newId != agentId) {
                // Old row out through the same cascade Edit-models uses.
                if (report.workerConfig.useReportModels) {
                    reportViewModel.removeReportModelEverywhereInternal(context, reportId, oldAgent.provider, oldAgent.model)
                } else {
                    reportViewModel.removeAgentInternal(context, reportId, agentId)
                }
            }
            withContext(Dispatchers.IO) { ReportStorage.bumpReportTimestamp(context, reportId) }
            AuditLog.append(reportId, "Switched report model ${oldAgent.provider}/${oldAgent.model} → ${selection.provider.id}/${selection.model}")
            // Live screen sync: swap the driver-set id and the in-memory row.
            appViewModel.updateUiState { s ->
                if (s.currentReportId != reportId) s
                else s.copy(genericReportsSelectedAgents = (s.genericReportsSelectedAgents - agentId) + newId)
            }
            reportViewModel._agentResults.update { m ->
                (m - agentId) + (newId to AnalysisResponse(
                    service = selection.provider, analysis = success.content, error = null,
                    agentName = agentName, tokenUsage = success.tokenUsage
                ))
            }
            // Per-model icon/title for the fresh row, matching what a newly
            // generated answer gets (gated on the same settings).
            val g = appViewModel.uiState.value.generalSettings
            if (g.perModelIconOn() || g.perModelTitleOn()) {
                val ra = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
                    ?.agents?.firstOrNull { it.agentId == newId }
                if (ra != null && !ra.responseBody.isNullOrBlank()) {
                    reportViewModel.iconGen.runPerModelEnrichment(
                        context, reportId, ra, report.prompt, appViewModel.uiState.value.aiSettings,
                        g.perModelIconOn(), g.perModelTitleOn()
                    )
                }
            }
            _states.update { it - key }
        }
    }
}
