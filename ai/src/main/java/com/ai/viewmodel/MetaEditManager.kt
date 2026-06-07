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
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.extractTopRankedIds
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.temperatureRangeForProvider
import com.ai.data.withTraceFilenameSink
import com.ai.data.withTracerTags
import com.ai.model.Agent
import com.ai.model.Settings
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    private val _temperatureSweepStates = MutableStateFlow<Map<String, TemperatureSweepState>>(emptyMap())
    val temperatureSweepStates: StateFlow<Map<String, TemperatureSweepState>> = _temperatureSweepStates.asStateFlow()
    private val _reasoningEffortSweepStates = MutableStateFlow<Map<String, ReasoningEffortSweepState>>(emptyMap())
    val reasoningEffortSweepStates: StateFlow<Map<String, ReasoningEffortSweepState>> = _reasoningEffortSweepStates.asStateFlow()
    private val _webSearchReplayStates = MutableStateFlow<Map<String, WebSearchReplayState>>(emptyMap())
    val webSearchReplayStates: StateFlow<Map<String, WebSearchReplayState>> = _webSearchReplayStates.asStateFlow()
    private val _promptEditReplayStates = MutableStateFlow<Map<String, PromptEditReplayState>>(emptyMap())
    val promptEditReplayStates: StateFlow<Map<String, PromptEditReplayState>> = _promptEditReplayStates.asStateFlow()

    private val temperatureSweepJobs = ConcurrentHashMap<String, Job>()
    private val reasoningEffortSweepJobs = ConcurrentHashMap<String, Job>()
    private val webSearchReplayJobs = ConcurrentHashMap<String, Job>()
    private val promptEditReplayJobs = ConcurrentHashMap<String, Job>()

    private companion object {
        const val TEMPERATURE_KIND = "meta/temperature"
        const val REASONING_KIND = "meta/reasoning"
        const val WEB_SEARCH_KIND = "meta/web-search"
        const val PROMPT_EDIT_KIND = "meta/prompt-edit"
        const val MODEL_SWITCH_KIND = "meta/model-switch"
        const val WEB_SEARCH_SUFFIX = "Give the most actual information, do a websearch for this."
    }

    // ----- state helpers (mirror FanOutEngine) -----
    private fun updateTemperatureSweepState(key: String, transform: (TemperatureSweepState) -> TemperatureSweepState) {
        _temperatureSweepStates.update { it[key]?.let { s -> it + (key to transform(s)) } ?: it }
    }
    private fun setTemperatureSweepCandidate(key: String, index: Int, candidate: TemperatureSweepCandidate) {
        updateTemperatureSweepState(key) { s -> s.copy(candidates = s.candidates.mapIndexed { i, old -> if (i == index) candidate else old }) }
    }
    fun clearTemperatureSweep(reportId: String, resultId: String) {
        val key = TemperatureSweepState.key(reportId, resultId)
        temperatureSweepJobs.remove(key)?.cancel(); _temperatureSweepStates.update { it - key }
    }
    private fun updateReasoningEffortSweepState(key: String, transform: (ReasoningEffortSweepState) -> ReasoningEffortSweepState) {
        _reasoningEffortSweepStates.update { it[key]?.let { s -> it + (key to transform(s)) } ?: it }
    }
    private fun setReasoningEffortCandidate(key: String, index: Int, candidate: ReasoningEffortCandidate) {
        updateReasoningEffortSweepState(key) { s -> s.copy(candidates = s.candidates.mapIndexed { i, old -> if (i == index) candidate else old }) }
    }
    fun clearReasoningEffortSweep(reportId: String, resultId: String) {
        val key = ReasoningEffortSweepState.key(reportId, resultId)
        reasoningEffortSweepJobs.remove(key)?.cancel(); _reasoningEffortSweepStates.update { it - key }
    }
    private fun updateWebSearchReplayState(key: String, transform: (WebSearchReplayState) -> WebSearchReplayState) {
        _webSearchReplayStates.update { it[key]?.let { s -> it + (key to transform(s)) } ?: it }
    }
    fun clearWebSearchReplay(reportId: String, resultId: String) {
        val key = WebSearchReplayState.key(reportId, resultId)
        webSearchReplayJobs.remove(key)?.cancel(); _webSearchReplayStates.update { it - key }
    }
    private fun updatePromptEditReplayState(key: String, transform: (PromptEditReplayState) -> PromptEditReplayState) {
        _promptEditReplayStates.update { it[key]?.let { s -> it + (key to transform(s)) } ?: it }
    }
    fun clearPromptEditReplay(reportId: String, resultId: String) {
        val key = PromptEditReplayState.key(reportId, resultId)
        promptEditReplayJobs.remove(key)?.cancel(); _promptEditReplayStates.update { it - key }
    }

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
        val aiSettings: Settings
    )

    internal data class MetaVariationCallResult(
        val response: AnalysisResponse,
        val cost: Double?,
        val durationMs: Long,
        val traceFile: String?
    )

    /** Rebuild a single META row's call (resolved prompt + model + params).
     *  Plain meta rows resolve against the report's answers (mirroring
     *  SecondaryRunManager.resumeStaleMetaPlaceholder); fan-in (combine-
     *  reports) rows resolve against the fan-out matrix via the shared
     *  SecondaryRunManager.buildFanInResolution so a sweep / prompt-edit
     *  replays the SAME call the row was produced by. */
    /** When [overrideProvider] is non-null the task is rebuilt against a
     *  DIFFERENT provider/model (+ that selection's param presets / system
     *  prompt) — the "Switch model / agent" flow — instead of the row's own.
     *  The prompt resolution (scope / language / fan-in matrix) still comes
     *  from the row, so only the model changes. */
    private suspend fun buildMetaReplayTask(
        context: Context, reportId: String, resultId: String,
        overrideProvider: AppService? = null, overrideModel: String? = null,
        overrideParamsIds: List<String>? = null, overrideSystemPromptId: String? = null
    ): MetaReplayTask {
        val row = SecondaryResultStorage.get(context, reportId, resultId) ?: error("This result no longer exists")
        val aiSettings = appViewModel.uiState.value.aiSettings
        val promptId = row.metaPromptId ?: error("This result has no meta prompt")
        val metaPrompt = aiSettings.getInternalPromptById(promptId)
            ?: row.metaPromptName?.let { aiSettings.getInternalPromptByName(it) }
            ?: error("Meta prompt no longer exists")
        val provider = overrideProvider ?: AppService.findById(row.providerId)
            ?: error("Provider ${row.providerId} is not registered")
        val model = overrideModel ?: row.model
        val paramsIds = if (overrideProvider != null) (overrideParamsIds ?: emptyList()) else row.secondaryParameterPresetIds.orEmpty()
        val systemPromptId = if (overrideProvider != null) overrideSystemPromptId else row.secondarySystemPromptId
        val report = ReportStorage.getReport(context, reportId) ?: error("Report not found")
        val resolvedPrompt = if (row.fanInOf != null) {
            reportViewModel.secondary.buildFanInResolution(context, reportId, metaPrompt, report, row.targetLanguage)
                ?.resolvedPrompt
                ?: error("No fan-out responses available to rebuild this combined report")
        } else {
            val allSecondaries = SecondaryResultStorage.listForReport(context, reportId)
            val scope = SecondaryScope.decodeOrAllReports(row.secondaryScope)
            val lang = row.targetLanguage
            val includeIds: Set<Int>? = when (scope) {
                SecondaryScope.AllReports -> null
                is SecondaryScope.TopRanked -> {
                    val rerank = SecondaryResultStorage.get(context, reportId, scope.rerankResultId)
                    val ids = extractTopRankedIds(rerank?.content, scope.count)
                    // Clamp stale 1-based positions to the current successful
                    // count (same as runMetaPrompt) so a removed agent can't
                    // leave an out-of-range id that inflates @COUNT@.
                    val nSuccess = report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    val valid = ids?.filter { it in 1..nSuccess }
                    if (valid.isNullOrEmpty()) null else valid.toSet()
                }
                is SecondaryScope.Manual -> {
                    val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    val ids = successful.mapIndexedNotNull { idx, a -> if (a.agentId in scope.agentIds) idx + 1 else null }
                    if (ids.isEmpty()) null else ids.toSet()
                }
            }
            val successfulCount = if (includeIds != null) includeIds.size
                else report.agents.count { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
            val (translatedPrompt, resultsBlock) = buildLanguageInputs(report, allSecondaries, lang, includeIds)
            resolveSecondaryPrompt(
                metaPrompt.text, question = translatedPrompt, results = resultsBlock,
                count = successfulCount, title = report.title
            )
        }
        val agent = Agent(
            id = "meta:${row.id}", name = row.agentName,
            provider = provider, model = model, apiKey = aiSettings.getApiKey(provider)
        )
        val params = resolveSecondaryParams(
            appViewModel.uiState.value.generalSettings, aiSettings,
            paramsIds, systemPromptId, metaPrompt
        )
        return MetaReplayTask(
            reportId = reportId, resultId = resultId, provider = provider, model = model,
            agent = agent, prompt = resolvedPrompt, resolvedParams = params,
            baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent), aiSettings = aiSettings
        )
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
            appViewModel.settingsPrefs.updateUsageStatsAsync(task.provider, task.model, u, kind = kind)
        }
        MetaVariationCallResult(response, cost, durationMs, traceSink.get())
    }

    /** Commit a chosen candidate / chat reply onto the meta row. */
    suspend fun applyMetaContent(
        context: Context, reportId: String, resultId: String,
        content: String, changeSource: String, changeValue: String? = null
    ) {
        withContext(Dispatchers.IO) {
            val source = changeSource.takeIf { it.isNotBlank() } ?: return@withContext
            SecondaryResultStorage.updateContent(context, reportId, resultId, content, source, changeValue)
            ReportStorage.bumpReportTimestamp(context, reportId)
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
        provider: AppService, model: String, paramsIds: List<String>, systemPromptId: String?
    ): MetaVariationCallResult {
        val task = buildMetaReplayTask(context, reportId, resultId, provider, model, paramsIds, systemPromptId)
        return runMetaVariationCall(context, task, MODEL_SWITCH_KIND)
    }

    // ----- Temperature sweep -----
    fun startTemperatureSweep(context: Context, reportId: String, resultId: String, temperatures: List<Float>): Job {
        val key = TemperatureSweepState.key(reportId, resultId)
        val temps = temperatures.take(3)
        temperatureSweepJobs.remove(key)?.cancel()
        _temperatureSweepStates.update {
            it + (key to TemperatureSweepState(reportId, resultId, temps.map { t -> TemperatureSweepCandidate.Pending(t) }, isRunning = true))
        }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildMetaReplayTask(context, reportId, resultId)
                val supported = PricingCache.getSupportedParameters(context, task.provider, task.model)
                if (supported != null && supported.none { it.equals("temperature", ignoreCase = true) }) {
                    val msg = "${task.provider.id}/${task.model} does not report temperature support."
                    updateTemperatureSweepState(key) { s -> s.copy(isRunning = false, unavailableMessage = msg, candidates = s.candidates.map { TemperatureSweepCandidate.Error(it.temperature, msg, null, null, null) }) }
                    return@launch
                }
                val range = temperatureRangeForProvider(task.provider)
                val invalid = temps.firstOrNull { !range.contains(it) }
                if (temps.isEmpty() || invalid != null) {
                    val msg = if (temps.isEmpty()) "Choose at least one temperature."
                        else "${task.provider.id}/${task.model} allows temperature ${formatSweepTemperature(range.min)}..${formatSweepTemperature(range.max)}."
                    updateTemperatureSweepState(key) { s -> s.copy(isRunning = false, unavailableMessage = msg, candidates = s.candidates.map { TemperatureSweepCandidate.Error(it.temperature, msg, null, null, null) }) }
                    return@launch
                }
                val canReason = task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)
                val canWeb = task.aiSettings.isWebSearchCapable(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(
                    webSearchTool = task.resolvedParams.webSearchTool && canWeb,
                    reasoningEffort = if (canReason) task.resolvedParams.reasoningEffort else null
                )
                temps.forEachIndexed { index, temp ->
                    setTemperatureSweepCandidate(key, index, TemperatureSweepCandidate.Running(temp))
                    val result = runMetaVariationCall(context, task, TEMPERATURE_KIND, resolvedParams = baseParams, overrideParams = AgentParameters(temperature = temp))
                    val r = result.response
                    setTemperatureSweepCandidate(key, index,
                        if (r.isSuccess && !r.analysis.isNullOrBlank())
                            TemperatureSweepCandidate.Success(temp, r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile)
                        else TemperatureSweepCandidate.Error(temp, r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
                updateTemperatureSweepState(key) { it.copy(isRunning = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTemperatureSweepState(key) { it.copy(isRunning = false, unavailableMessage = (e.message ?: "Temperature sweep failed").take(2000)) }
            }
        }
        temperatureSweepJobs[key] = job
        job.invokeOnCompletion { temperatureSweepJobs.remove(key, job) }
        return job
    }

    fun applyTemperatureCandidate(context: Context, reportId: String, resultId: String, candidateIndex: Int) {
        val key = TemperatureSweepState.key(reportId, resultId)
        val c = _temperatureSweepStates.value[key]?.candidates?.getOrNull(candidateIndex) as? TemperatureSweepCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, c.response, RESPONSE_CHANGE_SOURCE_TEMPERATURE, formatSweepTemperature(c.temperature))
            _temperatureSweepStates.update { it - key }
        }
    }

    // ----- Reasoning-effort sweep -----
    fun startReasoningEffortSweep(context: Context, reportId: String, resultId: String, efforts: List<String?>): Job {
        val key = ReasoningEffortSweepState.key(reportId, resultId)
        reasoningEffortSweepJobs.remove(key)?.cancel()
        _reasoningEffortSweepStates.update {
            it + (key to ReasoningEffortSweepState(reportId, resultId, efforts.map { e -> ReasoningEffortCandidate.Pending(e) }, isRunning = true))
        }
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
                val canWeb = task.aiSettings.isWebSearchCapable(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(webSearchTool = task.resolvedParams.webSearchTool && canWeb, reasoningEffort = null)
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
                            ReasoningEffortCandidate.Success(effort, r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile)
                        else ReasoningEffortCandidate.Error(effort, r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
                updateReasoningEffortSweepState(key) { it.copy(isRunning = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateReasoningEffortSweepState(key) { it.copy(isRunning = false, unavailableMessage = (e.message ?: "Reasoning effort sweep failed").take(2000)) }
            }
        }
        reasoningEffortSweepJobs[key] = job
        job.invokeOnCompletion { reasoningEffortSweepJobs.remove(key, job) }
        return job
    }

    fun applyReasoningEffortCandidate(context: Context, reportId: String, resultId: String, candidateIndex: Int) {
        val key = ReasoningEffortSweepState.key(reportId, resultId)
        val c = _reasoningEffortSweepStates.value[key]?.candidates?.getOrNull(candidateIndex) as? ReasoningEffortCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, c.response, RESPONSE_CHANGE_SOURCE_REASONING_EFFORT, formatSweepReasoningEffort(c.effort))
            _reasoningEffortSweepStates.update { it - key }
        }
    }

    // ----- Web-search replay -----
    fun startWebSearchReplay(context: Context, reportId: String, resultId: String): Job {
        val key = WebSearchReplayState.key(reportId, resultId)
        webSearchReplayJobs.remove(key)?.cancel()
        _webSearchReplayStates.update { it + (key to WebSearchReplayState(reportId, resultId, WebSearchReplayResult.Running, isRunning = true)) }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildMetaReplayTask(context, reportId, resultId)
                if (!task.aiSettings.isWebSearchCapable(task.provider, task.model)) {
                    val msg = "${task.provider.id}/${task.model} does not report web-search support."
                    updateWebSearchReplayState(key) { it.copy(isRunning = false, result = WebSearchReplayResult.Error(msg, null, null, null), unavailableMessage = msg) }
                    return@launch
                }
                val canReason = task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(reasoningEffort = if (canReason) task.resolvedParams.reasoningEffort else null)
                val result = runMetaVariationCall(context, task, WEB_SEARCH_KIND, prompt = webSearchPrompt(task.prompt), resolvedParams = baseParams, overrideParams = AgentParameters(webSearchTool = true))
                val r = result.response
                updateWebSearchReplayState(key) {
                    it.copy(isRunning = false, result = if (r.isSuccess && !r.analysis.isNullOrBlank())
                        WebSearchReplayResult.Success(r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile)
                    else WebSearchReplayResult.Error(r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: "Web search replay failed").take(2000)
                updateWebSearchReplayState(key) { it.copy(isRunning = false, result = WebSearchReplayResult.Error(msg, null, null, null), unavailableMessage = msg) }
            }
        }
        webSearchReplayJobs[key] = job
        job.invokeOnCompletion { webSearchReplayJobs.remove(key, job) }
        return job
    }

    fun applyWebSearchReplay(context: Context, reportId: String, resultId: String) {
        val key = WebSearchReplayState.key(reportId, resultId)
        val result = _webSearchReplayStates.value[key]?.result as? WebSearchReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, result.response, RESPONSE_CHANGE_SOURCE_WEB_SEARCH)
            _webSearchReplayStates.update { it - key }
        }
    }

    // ----- Prompt-edit replay -----
    fun startPromptEditReplay(context: Context, reportId: String, resultId: String, prompt: String, parameterPresetIds: List<String>, systemPromptId: String?): Job {
        val key = PromptEditReplayState.key(reportId, resultId)
        val editedPrompt = prompt.trim()
        promptEditReplayJobs.remove(key)?.cancel()
        _promptEditReplayStates.update { it + (key to PromptEditReplayState(reportId, resultId, PromptEditReplayResult.Running, isRunning = true)) }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                if (editedPrompt.isBlank()) {
                    updatePromptEditReplayState(key) { it.copy(isRunning = false, result = PromptEditReplayResult.Error("Prompt is empty", null, null, null), unavailableMessage = "Prompt is empty") }
                    return@launch
                }
                val task = buildMetaReplayTask(context, reportId, resultId)
                val canReason = task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)
                val canWeb = task.aiSettings.isWebSearchCapable(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(
                    webSearchTool = task.resolvedParams.webSearchTool && canWeb,
                    reasoningEffort = if (canReason) task.resolvedParams.reasoningEffort else null
                )
                val screenOverride = promptEditOverrideParams(task.aiSettings, parameterPresetIds, systemPromptId)
                val finalParams = overlayAgentParameters(baseParams, screenOverride)!!.let { merged ->
                    merged.copy(webSearchTool = merged.webSearchTool && canWeb, reasoningEffort = if (canReason) merged.reasoningEffort else null)
                }
                val result = runMetaVariationCall(context, task, PROMPT_EDIT_KIND, prompt = editedPrompt, resolvedParams = finalParams, overrideParams = null)
                val r = result.response
                updatePromptEditReplayState(key) {
                    it.copy(isRunning = false, result = if (r.isSuccess && !r.analysis.isNullOrBlank())
                        PromptEditReplayResult.Success(r.analysis, r.tokenUsage, result.cost, result.durationMs, result.traceFile)
                    else PromptEditReplayResult.Error(r.error ?: "No response body", r.httpStatusCode, result.durationMs, result.traceFile))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: "Prompt edit replay failed").take(2000)
                updatePromptEditReplayState(key) { it.copy(isRunning = false, result = PromptEditReplayResult.Error(msg, null, null, null), unavailableMessage = msg) }
            }
        }
        promptEditReplayJobs[key] = job
        job.invokeOnCompletion { promptEditReplayJobs.remove(key, job) }
        return job
    }

    fun applyPromptEditReplay(context: Context, reportId: String, resultId: String) {
        val key = PromptEditReplayState.key(reportId, resultId)
        val result = _promptEditReplayStates.value[key]?.result as? PromptEditReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyMetaContent(context, reportId, resultId, result.response, RESPONSE_CHANGE_SOURCE_EDIT)
            _promptEditReplayStates.update { it - key }
        }
    }

    /** Resolve the editable prompt seed for the prompt-edit screen. */
    suspend fun resolveMetaPrompt(context: Context, reportId: String, resultId: String): String? =
        withContext(Dispatchers.IO) { runCatching { buildMetaReplayTask(context, reportId, resultId).prompt }.getOrNull() }

    fun cancelAllForReport(reportId: String) {
        val prefix = "$reportId|"
        listOf(temperatureSweepJobs, reasoningEffortSweepJobs, webSearchReplayJobs, promptEditReplayJobs).forEach { jobs ->
            jobs.keys.filter { it.startsWith(prefix) }.forEach { jobs.remove(it)?.cancel() }
        }
        _temperatureSweepStates.update { it.filterKeys { k -> !k.startsWith(prefix) } }
        _reasoningEffortSweepStates.update { it.filterKeys { k -> !k.startsWith(prefix) } }
        _webSearchReplayStates.update { it.filterKeys { k -> !k.startsWith(prefix) } }
        _promptEditReplayStates.update { it.filterKeys { k -> !k.startsWith(prefix) } }
    }
}
