package com.ai.viewmodel

import android.content.Context
import com.ai.data.AgentParameters
import com.ai.data.AnalysisResponse
import com.ai.data.ApiCallCaps
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.data.ProviderThrottle
import com.ai.data.RESPONSE_CHANGE_SOURCE_EDIT
import com.ai.data.RESPONSE_CHANGE_SOURCE_REASONING_EFFORT
import com.ai.data.RESPONSE_CHANGE_SOURCE_TEMPERATURE
import com.ai.data.RESPONSE_CHANGE_SOURCE_WEB_SEARCH
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResultStorage
import com.ai.data.temperatureRangeForProvider
import com.ai.data.withTraceFilenameSink
import com.ai.data.withTracerTags
import com.ai.model.Agent
import com.ai.model.Settings
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * The "Change response"-style editing flows for a plain META [SecondaryResult]
 * (a meta-prompt run — Compare / Critique / Summarize / …). A structural copy
 * of [FanOutEngine]'s sweep/replay section, but keyed by the meta row id
 * (`resultId`) and rebuilding the call the way [SecondaryRunManager] runs a meta
 * prompt (the report's answers as the results block) instead of a fan-out pair's
 * single source. The generic sweep/replay SCREENS are reused as-is; the chosen
 * candidate is committed back to the same row via
 * [SecondaryResultStorage.updateContent].
 */
class MetaEditManager internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) {
    // Replay state-flow + per-key job plumbing is shared via ReplayTrack
    // (audit R03); the public *States flows delegate to each track.
    private val temperatureTrack = ReplayTrack<TemperatureSweepState>()
    private val reasoningEffortTrack = ReplayTrack<ReasoningEffortSweepState>()
    private val webSearchReplayTrack = ReplayTrack<WebSearchReplayState>()
    private val promptEditReplayTrack = ReplayTrack<PromptEditReplayState>()

    val temperatureSweepStates: StateFlow<Map<String, TemperatureSweepState>> get() = temperatureTrack.states
    val reasoningEffortSweepStates: StateFlow<Map<String, ReasoningEffortSweepState>> get() = reasoningEffortTrack.states
    val webSearchReplayStates: StateFlow<Map<String, WebSearchReplayState>> get() = webSearchReplayTrack.states
    val promptEditReplayStates: StateFlow<Map<String, PromptEditReplayState>> get() = promptEditReplayTrack.states

    private companion object {
        const val TEMPERATURE_KIND = "meta/temperature"
        const val REASONING_KIND = "meta/reasoning"
        const val WEB_SEARCH_KIND = "meta/web-search"
        const val PROMPT_EDIT_KIND = "meta/prompt-edit"
        const val MODEL_SWITCH_KIND = "meta/model-switch"
        const val WEB_SEARCH_SUFFIX = "Give the most actual information, do a websearch for this."
    }

    // ----- state helpers (delegate to the shared ReplayTrack) -----
    private fun updateTemperatureSweepState(key: String, transform: (TemperatureSweepState) -> TemperatureSweepState) =
        temperatureTrack.update(key, transform)
    private fun setTemperatureSweepCandidate(key: String, index: Int, candidate: TemperatureSweepCandidate) {
        updateTemperatureSweepState(key) { s -> s.copy(candidates = s.candidates.mapIndexed { i, old -> if (i == index) candidate else old }) }
    }
    fun clearTemperatureSweep(reportId: String, resultId: String) =
        temperatureTrack.cancel(TemperatureSweepState.key(reportId, resultId))
    private fun updateReasoningEffortSweepState(key: String, transform: (ReasoningEffortSweepState) -> ReasoningEffortSweepState) =
        reasoningEffortTrack.update(key, transform)
    private fun setReasoningEffortCandidate(key: String, index: Int, candidate: ReasoningEffortCandidate) {
        updateReasoningEffortSweepState(key) { s -> s.copy(candidates = s.candidates.mapIndexed { i, old -> if (i == index) candidate else old }) }
    }
    fun clearReasoningEffortSweep(reportId: String, resultId: String) =
        reasoningEffortTrack.cancel(ReasoningEffortSweepState.key(reportId, resultId))
    private fun updateWebSearchReplayState(key: String, transform: (WebSearchReplayState) -> WebSearchReplayState) =
        webSearchReplayTrack.update(key, transform)
    fun clearWebSearchReplay(reportId: String, resultId: String) =
        webSearchReplayTrack.cancel(WebSearchReplayState.key(reportId, resultId))
    private fun updatePromptEditReplayState(key: String, transform: (PromptEditReplayState) -> PromptEditReplayState) =
        promptEditReplayTrack.update(key, transform)
    fun clearPromptEditReplay(reportId: String, resultId: String) =
        promptEditReplayTrack.cancel(PromptEditReplayState.key(reportId, resultId))

    // ----- the call -----
    private data class MetaReplayTask(
        val reportId: String,
        val resultId: String,
        val provider: AppService,
        val model: String,
        val agent: Agent,
        val prompt: String,
        val resolvedParams: AgentParameters,
        val baseUrl: String,
        val aiSettings: Settings,
        val credentialAgentId: String?
    )

    internal data class MetaVariationCallResult(
        val response: AnalysisResponse,
        val cost: Double?,
        val durationMs: Long,
        val traceFile: String?,
        val replayEvidence: com.ai.data.SecondaryReplayEvidence
    )

    /** Replay the captured request without consulting current source answers or
     *  prompt templates. A selected Agent supplies its own settings and endpoint;
     *  a raw model switch keeps the captured parameters. */
    private suspend fun buildMetaReplayTask(
        context: Context, reportId: String, resultId: String,
        overrideProvider: AppService? = null, overrideModel: String? = null,
        overrideParamsIds: List<String>? = null, overrideSystemPromptId: String? = null,
        overrideCredentialAgentId: String? = null
    ): MetaReplayTask {
        val row = SecondaryResultStorage.get(context, reportId, resultId) ?: error("This result no longer exists")
        val aiSettings = appViewModel.uiState.value.aiSettings
        val saved = row.executionConfig
            ?: error("Saved request settings are unavailable. Create a new analysis to use current inputs.")
        val provider = overrideProvider ?: AppService.findById(row.providerId)
            ?: error("Provider ${row.providerId} is not registered")
        val model = overrideModel ?: row.model
        val credentialId = if (overrideProvider != null) overrideCredentialAgentId else saved.credentialAgentId
        val credential = credentialId?.takeIf { it.isNotBlank() }?.let { id ->
            aiSettings.getAgentById(id)?.takeIf { it.provider == provider }
                ?: error("The saved credential Agent is unavailable. Choose another Agent to continue.")
        }
        val agent = (credential ?: Agent(id = "meta:${row.id}", name = row.agentName,
            provider = provider, model = model, apiKey = aiSettings.getApiKey(provider)))
            .copy(provider = provider, model = model, apiKey = credential?.apiKey?.takeIf { it.isNotBlank() } ?: aiSettings.getApiKey(provider))
        val params = if (overrideProvider == null || overrideCredentialAgentId == null) saved.parameters else {
            val promptId = row.fanInOf ?: row.metaPromptId
            val metaPrompt = promptId?.let(aiSettings::getInternalPromptById)
            resolveSecondaryParams(appViewModel.uiState.value.generalSettings, aiSettings,
                overrideParamsIds.orEmpty(), overrideSystemPromptId, metaPrompt, credential)
        }
        return MetaReplayTask(reportId, resultId, provider, model, agent,
            com.ai.data.stripThinkSections(saved.prompt), params,
            if (overrideProvider == null) saved.endpointUrl else aiSettings.getEffectiveEndpointUrlForAgent(agent),
            aiSettings, credentialId?.takeIf { it.isNotBlank() })
    }

    private fun webSearchPrompt(prompt: String): String =
        if (prompt.isBlank()) WEB_SEARCH_SUFFIX else prompt.trimEnd() + "\n\n" + WEB_SEARCH_SUFFIX

    private suspend fun runMetaVariationCall(
        context: Context,
        task: MetaReplayTask,
        kind: String,
        prompt: String = task.prompt,
        resolvedParams: AgentParameters = task.resolvedParams,
        overrideParams: AgentParameters? = null
    ): MetaVariationCallResult = withTracerTags(reportId = task.reportId, category = kind) {
        val traceSink = AtomicReference<String?>(null)
        val startTime = System.currentTimeMillis()
        val response = try {
            ApiCallCaps.fanOut.withPermit {
                ApiCallCaps.global.withPermit {
                    val releaser = acquireOrRequeue(providerHost(task.provider))
                    try {
                        withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                            withTraceFilenameSink(traceSink) {
                                appViewModel.repository.analyzeWithAgent(
                                    task.agent, "", prompt, resolvedParams, overrideParams,
                                    context, task.baseUrl, aiSettings = task.aiSettings
                                )
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
            AnalysisResponse(task.provider, null, (e.message ?: "Unknown error").take(2000), agentName = task.agent.name)
        }
        val durationMs = System.currentTimeMillis() - startTime
        val cost = calculateResponseCost(context, task.provider, task.model, response.tokenUsage)
        if (response.error == null && response.tokenUsage != null) {
            val u = response.tokenUsage
            appViewModel.settingsPrefs.updateUsageStatsAsync(task.provider, task.model, u, kind = kind, durationMs = durationMs)
        }
        val (inputCost, outputCost) = response.tokenUsage?.let {
            PricingCache.computeInOutCost(it, PricingCache.getPricing(context, task.provider, task.model))
        } ?: (null to null)
        val execution = com.ai.data.ReportExecutionConfig(
            appViewModel.repository.mergeParameters(resolvedParams, overrideParams), task.baseUrl,
            appViewModel.repository.resolveReportPrompt(prompt, task.agent), credentialAgentId = task.credentialAgentId)
        MetaVariationCallResult(response, cost, durationMs, traceSink.get(),
            com.ai.data.SecondaryReplayEvidence(execution, response.tokenUsage, inputCost, outputCost, durationMs, traceSink.get()))
    }

    /** Commit a chosen candidate / chat reply onto the meta row. */
    suspend fun applyMetaContent(
        context: Context, reportId: String, resultId: String,
        content: String, changeSource: String, changeValue: String? = null,
        replayEvidence: com.ai.data.SecondaryReplayEvidence? = null
    ) {
        withContext(Dispatchers.IO) {
            val source = changeSource.takeIf { it.isNotBlank() } ?: return@withContext
            SecondaryResultStorage.updateContent(context, reportId, resultId, content, source, changeValue, replayEvidence)
            ReportStorage.bumpReportTimestamp(context, reportId)
            if (SecondaryResultStorage.get(context, reportId, resultId)?.fanInOf != null) {
                reportViewModel.fanOutEngine.hydrate(context, reportId)
            }
        }
    }

    /** "Reload" — re-run the meta prompt in place with its saved settings. */
    fun regenerateMeta(context: Context, reportId: String, resultId: String): Job? {
        val row = SecondaryResultStorage.get(context, reportId, resultId) ?: return null
        return reportViewModel.secondary.resumeStaleMetaPlaceholder(context, reportId, row)
    }

    /** Run the meta / fan-in / rerank-chat call against a switched
     *  provider/model (+ that selection's presets) and return the in-memory
     *  candidate — used by [SecondaryModelSwitchManager] for the preview. The
     *  candidate is NOT written to the row until the user applies it. */
    internal suspend fun runModelSwitchMeta(
        context: Context, reportId: String, resultId: String,
        provider: AppService, model: String, paramsIds: List<String>, systemPromptId: String?, credentialAgentId: String? = null
    ): MetaVariationCallResult {
        val task = buildMetaReplayTask(context, reportId, resultId, provider, model, paramsIds, systemPromptId, credentialAgentId)
        return runMetaVariationCall(context, task, MODEL_SWITCH_KIND)
    }

    // ----- Temperature sweep -----
    fun startTemperatureSweep(context: Context, reportId: String, resultId: String, temperatures: List<Float>): Job {
        val key = TemperatureSweepState.key(reportId, resultId)
        val temps = temperatures.take(3)
        temperatureTrack.cancelJob(key)
        temperatureTrack.set(key, TemperatureSweepState(reportId, resultId, temps.map { t -> TemperatureSweepCandidate.Pending(t) }, isRunning = true))
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildMetaReplayTask(context, reportId, resultId)
                // The cached catalog can describe a different endpoint or reasoning
                // mode. Shared dispatch validates the actual requested experiment.
                val range = temperatureRangeForProvider(task.provider)
                val invalid = temps.firstOrNull { !range.contains(it) }
                if (temps.isEmpty() || invalid != null) {
                    val msg = if (temps.isEmpty()) "Choose at least one temperature."
                        else "${task.provider.id}/${task.model} allows temperature ${formatSweepTemperature(range.min)}..${formatSweepTemperature(range.max)}."
                    updateTemperatureSweepState(key) { s -> s.copy(isRunning = false, unavailableMessage = msg, candidates = s.candidates.map { TemperatureSweepCandidate.Error(it.temperature, msg, null, null, null) }) }
                    return@launch
                }
                val baseParams = task.resolvedParams
                temps.forEachIndexed { index, temp ->
                    setTemperatureSweepCandidate(key, index, TemperatureSweepCandidate.Running(temp))
                    val result = runMetaVariationCall(context, task, TEMPERATURE_KIND, resolvedParams = baseParams, overrideParams = AgentParameters(temperature = temp))
                    val r = result.response
                    setTemperatureSweepCandidate(key, index,
                        if (r.isSuccess && !r.analysis.isNullOrBlank())
                            TemperatureSweepCandidate.Success(temp, r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile, result.replayEvidence)
                        else TemperatureSweepCandidate.Error(temp, r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
                updateTemperatureSweepState(key) { it.copy(isRunning = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTemperatureSweepState(key) { it.copy(isRunning = false, unavailableMessage = (e.message ?: "Temperature sweep failed").take(2000)) }
            }
        }
        temperatureTrack.registerJob(key, job)
        return job
    }

    fun applyTemperatureCandidate(context: Context, reportId: String, resultId: String, candidateIndex: Int) {
        val key = TemperatureSweepState.key(reportId, resultId)
        val c = temperatureTrack.get(key)?.candidates?.getOrNull(candidateIndex) as? TemperatureSweepCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, c.response, RESPONSE_CHANGE_SOURCE_TEMPERATURE, formatSweepTemperature(c.temperature), c.replayEvidence)
            temperatureTrack.drop(key)
        }
    }

    // ----- Reasoning-effort sweep -----
    fun startReasoningEffortSweep(context: Context, reportId: String, resultId: String, efforts: List<String?>): Job {
        val key = ReasoningEffortSweepState.key(reportId, resultId)
        reasoningEffortTrack.cancelJob(key)
        reasoningEffortTrack.set(key, ReasoningEffortSweepState(reportId, resultId, efforts.map { e -> ReasoningEffortCandidate.Pending(e) }, isRunning = true))
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildMetaReplayTask(context, reportId, resultId)
                if (!task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)) {
                    val msg = "${task.provider.id}/${task.model} does not accept controllable reasoning effort."
                    updateReasoningEffortSweepState(key) { s -> s.copy(isRunning = false, unavailableMessage = msg, candidates = s.candidates.map { ReasoningEffortCandidate.Error(it.effort, msg, null, null, null) }) }
                    return@launch
                }
                val supportedLevels = task.aiSettings.getProvider(task.provider).modelCapabilities[task.model]
                    ?.reasoningEffortLevels?.map { it.lowercase(Locale.US) }?.toSet()
                val baseParams = task.resolvedParams.copy(reasoningEffort = null)
                efforts.forEachIndexed { index, effort ->
                    if (effort != null && supportedLevels != null && effort !in supportedLevels) {
                        val msg = "${formatSweepReasoningEffort(effort)} reasoning effort is not reported as supported by ${task.provider.id}/${task.model}."
                        setReasoningEffortCandidate(key, index, ReasoningEffortCandidate.Error(effort, msg, null, null, null)); return@forEachIndexed
                    }
                    setReasoningEffortCandidate(key, index, ReasoningEffortCandidate.Running(effort))
                    val result = runMetaVariationCall(context, task, REASONING_KIND,
                        resolvedParams = if (effort == null) baseParams else baseParams.copy(reasoningEffort = task.resolvedParams.reasoningEffort),
                        overrideParams = effort?.let { AgentParameters(reasoningEffort = it) })
                    val r = result.response
                    setReasoningEffortCandidate(key, index,
                        if (r.isSuccess && !r.analysis.isNullOrBlank())
                            ReasoningEffortCandidate.Success(effort, r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile, result.replayEvidence)
                        else ReasoningEffortCandidate.Error(effort, r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
                updateReasoningEffortSweepState(key) { it.copy(isRunning = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateReasoningEffortSweepState(key) { it.copy(isRunning = false, unavailableMessage = (e.message ?: "Reasoning effort sweep failed").take(2000)) }
            }
        }
        reasoningEffortTrack.registerJob(key, job)
        return job
    }

    fun applyReasoningEffortCandidate(context: Context, reportId: String, resultId: String, candidateIndex: Int) {
        val key = ReasoningEffortSweepState.key(reportId, resultId)
        val c = reasoningEffortTrack.get(key)?.candidates?.getOrNull(candidateIndex) as? ReasoningEffortCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, c.response, RESPONSE_CHANGE_SOURCE_REASONING_EFFORT, formatSweepReasoningEffort(c.effort), c.replayEvidence)
            reasoningEffortTrack.drop(key)
        }
    }

    // ----- Web-search replay -----
    fun startWebSearchReplay(context: Context, reportId: String, resultId: String): Job {
        val key = WebSearchReplayState.key(reportId, resultId)
        webSearchReplayTrack.cancelJob(key)
        webSearchReplayTrack.set(key, WebSearchReplayState(reportId, resultId, WebSearchReplayResult.Running, isRunning = true))
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildMetaReplayTask(context, reportId, resultId)
                if (!task.aiSettings.isWebSearchCapable(task.provider, task.model)) {
                    val msg = "${task.provider.id}/${task.model} does not report web-search support."
                    updateWebSearchReplayState(key) { it.copy(isRunning = false, result = WebSearchReplayResult.Error(msg, null, null, null), unavailableMessage = msg) }
                    return@launch
                }
                val baseParams = task.resolvedParams
                val result = runMetaVariationCall(context, task, WEB_SEARCH_KIND, prompt = webSearchPrompt(task.prompt), resolvedParams = baseParams, overrideParams = AgentParameters(webSearchTool = true))
                val r = result.response
                updateWebSearchReplayState(key) {
                    it.copy(isRunning = false, result = if (r.isSuccess && !r.analysis.isNullOrBlank())
                        WebSearchReplayResult.Success(r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile, result.replayEvidence)
                    else WebSearchReplayResult.Error(r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: "Web search replay failed").take(2000)
                updateWebSearchReplayState(key) { it.copy(isRunning = false, result = WebSearchReplayResult.Error(msg, null, null, null), unavailableMessage = msg) }
            }
        }
        webSearchReplayTrack.registerJob(key, job)
        return job
    }

    fun applyWebSearchReplay(context: Context, reportId: String, resultId: String) {
        val key = WebSearchReplayState.key(reportId, resultId)
        val result = webSearchReplayTrack.get(key)?.result as? WebSearchReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, result.response, RESPONSE_CHANGE_SOURCE_WEB_SEARCH, replayEvidence = result.replayEvidence)
            webSearchReplayTrack.drop(key)
        }
    }

    // ----- Prompt-edit replay -----
    fun startPromptEditReplay(context: Context, reportId: String, resultId: String, prompt: String, parameterPresetIds: List<String>, systemPromptId: String?): Job {
        val key = PromptEditReplayState.key(reportId, resultId)
        val editedPrompt = prompt.trim()
        promptEditReplayTrack.cancelJob(key)
        promptEditReplayTrack.set(key, PromptEditReplayState(reportId, resultId, PromptEditReplayResult.Running, isRunning = true))
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                if (editedPrompt.isBlank()) {
                    updatePromptEditReplayState(key) { it.copy(isRunning = false, result = PromptEditReplayResult.Error("Prompt is empty", null, null, null), unavailableMessage = "Prompt is empty") }
                    return@launch
                }
                val task = buildMetaReplayTask(context, reportId, resultId)
                val baseParams = task.resolvedParams
                val screenOverride = promptEditOverrideParams(task.aiSettings, parameterPresetIds, systemPromptId)
                val finalParams = overlayAgentParameters(baseParams, screenOverride)!!
                val result = runMetaVariationCall(context, task, PROMPT_EDIT_KIND, prompt = editedPrompt, resolvedParams = finalParams, overrideParams = null)
                val r = result.response
                updatePromptEditReplayState(key) {
                    it.copy(isRunning = false, result = if (r.isSuccess && !r.analysis.isNullOrBlank())
                        PromptEditReplayResult.Success(r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile, result.replayEvidence)
                    else PromptEditReplayResult.Error(r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: "Prompt edit replay failed").take(2000)
                updatePromptEditReplayState(key) { it.copy(isRunning = false, result = PromptEditReplayResult.Error(msg, null, null, null), unavailableMessage = msg) }
            }
        }
        promptEditReplayTrack.registerJob(key, job)
        return job
    }

    fun applyPromptEditReplay(context: Context, reportId: String, resultId: String) {
        val key = PromptEditReplayState.key(reportId, resultId)
        val result = promptEditReplayTrack.get(key)?.result as? PromptEditReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, result.response, RESPONSE_CHANGE_SOURCE_EDIT, replayEvidence = result.replayEvidence)
            promptEditReplayTrack.drop(key)
        }
    }

    /** Resolve the editable prompt seed for the prompt-edit screen. */
    suspend fun resolveMetaPrompt(context: Context, reportId: String, resultId: String): String? =
        withContext(Dispatchers.IO) { runCatching { buildMetaReplayTask(context, reportId, resultId).prompt }.getOrNull() }

    fun cancelAllForReport(reportId: String) {
        val prefix = "$reportId|"
        temperatureTrack.cancelByPrefix(prefix)
        reasoningEffortTrack.cancelByPrefix(prefix)
        webSearchReplayTrack.cancelByPrefix(prefix)
        promptEditReplayTrack.cancelByPrefix(prefix)
    }
}
