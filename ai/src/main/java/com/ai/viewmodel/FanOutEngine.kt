package com.ai.viewmodel

import android.content.Context
import com.ai.data.AgentParameters
import com.ai.data.AnalysisResponse
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.AuditLog
import com.ai.data.FAN_OUT_PROMPT_EDIT_CALL_KIND
import com.ai.data.CombinedReportState
import com.ai.data.FAN_OUT_REASONING_CALL_KIND
import com.ai.data.FAN_OUT_TEMPERATURE_CALL_KIND
import com.ai.data.FAN_OUT_WEB_SEARCH_CALL_KIND
import com.ai.data.FanOutRunKey
import com.ai.data.FanOutRunState
import com.ai.data.PairKey
import com.ai.data.PairState
import com.ai.data.PairStatus
import com.ai.data.PricingCache
import com.ai.data.NetworkSettings
import com.ai.data.ProviderThrottle
import com.ai.data.ModelCooldownStore
import com.ai.data.RESPONSE_CHANGE_SOURCE_EDIT
import com.ai.data.RESPONSE_CHANGE_SOURCE_REASONING_EFFORT
import com.ai.data.RESPONSE_CHANGE_SOURCE_TEMPERATURE
import com.ai.data.RESPONSE_CHANGE_SOURCE_WEB_SEARCH
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.extractTopRankedIds
import com.ai.data.fullCost
import com.ai.data.pairKey
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.runKey
import com.ai.data.temperatureRangeForProvider
import com.ai.data.toCombinedReportState
import com.ai.data.toPairState
import com.ai.data.withTraceFilenameSink
import com.ai.data.withTracerTags
import com.ai.model.Agent
import com.ai.model.InternalPrompt
import com.ai.ui.shared.shortModelName
import androidx.lifecycle.viewModelScope
import com.ai.model.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

private const val FAN_OUT_WEB_SEARCH_PROMPT_SUFFIX =
    "Give the most actual information, do a websearch for this."

/**
 * Authoritative runtime owner for every Fan Out run on every
 * report. Publishes a single [StateFlow] keyed by [FanOutRunKey]
 * that the UI subscribes to; every state transition (pair queued,
 * permit acquired, HTTP completed, error stamped, row deleted) is
 * an atomic update to that flow.
 *
 * The UI subscribes to this engine's flow directly; the legacy
 * per-pair maps and polling loop ReportViewModel used to own for
 * fan-out have been removed.
 *
 * The engine delegates the actual HTTP call + disk persistence
 * to [ReportViewModel.executeSecondaryTask] — that function
 * already handles every cost / token / error path correctly, so
 * the engine just brackets it with state-flow transitions plus
 * per-pair Job bookkeeping.
 *
 * - The engine uses the shared BatchEngine job registries.
 * - The engine hydrates from disk on demand via [hydrate]; the
 *   UI's `LaunchedEffect(currentReportId)` calls it on report
 *   open so the flow is populated before any drill-in.
 */
class FanOutEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) : BatchEngine<FanOutRunKey, PairKey, PairState, FanOutRunState>() {
    override fun copyWithItems(run: FanOutRunState, items: Map<PairKey, PairState>) =
        run.copy(pairs = items)

    private val _temperatureSweepStates = MutableStateFlow<Map<String, TemperatureSweepState>>(emptyMap())
    val temperatureSweepStates: StateFlow<Map<String, TemperatureSweepState>> = _temperatureSweepStates.asStateFlow()

    private val _reasoningEffortSweepStates = MutableStateFlow<Map<String, ReasoningEffortSweepState>>(emptyMap())
    val reasoningEffortSweepStates: StateFlow<Map<String, ReasoningEffortSweepState>> = _reasoningEffortSweepStates.asStateFlow()

    private val _webSearchReplayStates = MutableStateFlow<Map<String, WebSearchReplayState>>(emptyMap())
    val webSearchReplayStates: StateFlow<Map<String, WebSearchReplayState>> = _webSearchReplayStates.asStateFlow()

    private val _promptEditReplayStates = MutableStateFlow<Map<String, PromptEditReplayState>>(emptyMap())
    val promptEditReplayStates: StateFlow<Map<String, PromptEditReplayState>> = _promptEditReplayStates.asStateFlow()

    // Pair (item) coroutines, the top-level run Job per FanOutRunKey, and the
    // resume-scan dedup now live in the shared BatchEngine base (registerItemJob
    // / registerRunJob / beginResumeScan + itemJobOf / runJobOf / runJobKeys /
    // activeRunJobKeys / hasItemJob). FanOut keys runs by "reportId|metaPromptId",
    // so the per-report prefix cancel/join uses runJobKeys().filter { … }.
    //
    // The four replay/sweep job maps below are FanOut-specific (no base
    // equivalent) and stay here.
    private val temperatureSweepJobs = ConcurrentHashMap<String, Job>()
    private val reasoningEffortSweepJobs = ConcurrentHashMap<String, Job>()
    private val webSearchReplayJobs = ConcurrentHashMap<String, Job>()
    private val promptEditReplayJobs = ConcurrentHashMap<String, Job>()

    // -----------------------------------------------------------------
    // Hydration — disk → StateFlow
    // -----------------------------------------------------------------

    /** Walk every SecondaryResult on disk for [reportId], group
     *  fan-out pair rows + combined-report rows by metaPromptId
     *  into [FanOutRunState]s, and publish them. Idempotent:
     *  hydrating the same report twice produces the same state
     *  (any in-flight pairs we own remain RUNNING because their
     *  disk row hasn't been updated yet — the engine's per-pair
     *  transitions override the disk view on the next state
     *  update).
     *
     *  Called once on report open and once after every disk-
     *  visible mutation (delete-run, delete-model, hard reload).
     *  For per-pair transitions the engine updates the StateFlow
     *  directly without re-reading disk. */
    suspend fun hydrate(context: Context, reportId: String) {
        val state = appViewModel.uiState.value
        val aiSettings = state.aiSettings
        val report = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, reportId)
        } ?: return
        val all = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
        }

        // Group fan-out pair rows by metaPromptId
        val pairRowsByPrompt = all
            .filter { it.fanOutSourceAgentId != null && it.fanInOf == null }
            .groupBy { it.metaPromptId.orEmpty() }
            .filterKeys { it.isNotBlank() }

        // Group fan-in (combined report) rows by metaPromptId — note
        // these reference the fan-OUT prompt's id via metaPromptId
        // (the SecondaryResult.fanInOf carries the FAN-IN prompt id;
        // historically there's no explicit fan-out↔fan-in link).
        // We attach every fan-in row whose metaPromptName matches one
        // of the fan-out runs — best-effort grouping that matches
        // the old buildFanOutSummaries behaviour.
        val fanInRowsByName = all
            .filter { it.fanInOf != null && it.fanOutSourceAgentId == null }
            .groupBy { it.metaPromptName.orEmpty() }

        // (provider, model) → agentId index for the per-row answerer
        // lookup below. First agent wins on duplicate (provider, model)
        // swarm members, matching the old firstOrNull scan.
        val agentIdByProviderModel = HashMap<Pair<String, String>, String>()
        for (agent in report.agents) {
            agentIdByProviderModel.putIfAbsent(agent.provider.lowercase() to agent.model, agent.agentId)
        }

        val newRuns = mutableMapOf<FanOutRunKey, FanOutRunState>()
        // Snapshot of the current in-memory runs so a re-hydrate (the
        // periodic 3 s tick) doesn't clobber the precise RUNNING status
        // the runner set: an in-flight pair's disk row is still a blank
        // placeholder (reads PENDING), so without this merge the UI would
        // flip a running pair back to "queued" mid-call.
        val currentRuns = _runs.value
        for ((metaPromptId, rows) in pairRowsByPrompt) {
            val prompt = aiSettings.internalPrompts.firstOrNull { it.id == metaPromptId }
                ?: continue
            val key = runKey(reportId, metaPromptId)
            val currentPairs = currentRuns[key]?.pairs

            // Build PairState map. For each row we need the answerer
            // agent id (not stored on the row — derive from the agent
            // whose (provider, model) matches). Multi-agent swarm
            // members with identical (provider, model) all match the
            // same answerer key set; arbitrarily pick the first.
            val pairs = mutableMapOf<PairKey, PairState>()
            for (row in rows) {
                val answererAgentId = agentIdByProviderModel[row.providerId.lowercase() to row.model]
                    // The answerer model may have been removed from the report
                    // since the fan-out ran. Hydrate the pair anyway under a
                    // stable synthetic id: L1 groups by the pair's OWN
                    // provider|model, so its rows stay visible, deletable and
                    // counted — skipping them left invisible rows on disk that
                    // still showed up in the cost tables and exports.
                    ?: "removed:${row.providerId.lowercase()}|${row.model}"
                val diskPair = row.toPairState(answererAgentId) ?: continue
                // Preserve a live RUNNING status the disk can't yet show.
                val pair = if (diskPair.status == PairStatus.PENDING &&
                    currentPairs?.get(diskPair.key)?.status == PairStatus.RUNNING) {
                    diskPair.copy(status = PairStatus.RUNNING)
                } else diskPair
                pairs[pair.key] = pair
            }

            // Resolve scope: take it from the first row that has it
            // (every row in a run shares the same scope encoding).
            val scopeEncoded = rows.firstOrNull { !it.secondaryScope.isNullOrBlank() }?.secondaryScope
            val scope = SecondaryScope.decodeOrAllReports(scopeEncoded)
            // Same trick for the source language so rerunComplete
            // re-fires against the same translation.
            val sourceLanguage = rows.firstNotNullOfOrNull { it.targetLanguage }

            // Combined-report rows attached to this run. We match by
            // metaPromptName since fan-in rows don't carry the fan-out
            // prompt id. Best-effort; legacy data may not group
            // perfectly, but the UI section can tolerate that.
            val combinedRows = fanInRowsByName[prompt.name].orEmpty()
                .mapNotNull { it.toCombinedReportState() }

            newRuns[key] = FanOutRunState(
                key = key,
                reportId = reportId,
                metaPrompt = prompt,
                scope = scope,
                responderIds = null,    // not persisted; lost across hydration
                pairs = pairs,
                combinedReports = combinedRows,
                sourceLanguage = sourceLanguage
            )
        }

        // Keep runs for other reports + overwrite this report's entries.
        _runs.update { current ->
            val keep = current.filterKeys { !it.startsWith("$reportId|") }
            keep + newRuns
        }
    }

    fun runByKey(key: FanOutRunKey): FanOutRunState? = _runs.value[key]

    // -----------------------------------------------------------------
    // State-flow transition helpers
    // -----------------------------------------------------------------

    /** Atomic state transition for one pair. Returns the new run
     *  state so callers can chain. */
    private fun transitionPair(runKey: FanOutRunKey, pairKey: PairKey, update: (PairState) -> PairState) =
        transitionItem(runKey, pairKey, update)

    /** Drop a pair from a run (used by removeFailedPairs / delete-model paths). */
    private fun dropPair(runKey: FanOutRunKey, pairKey: PairKey) = dropItem(runKey, pairKey)

    private fun transitionPairById(runKey: FanOutRunKey, pairId: String, update: (PairState) -> PairState) =
        transitionItemById(runKey, pairId, update)

    /** Mirror a single pair's persisted title / icon / fan-meta-cost
     *  fields from disk into the in-memory [PairState] immediately, so
     *  the Fan Meta L1 Done / Error / Costs counters advance live
     *  instead of waiting for the next [hydrate] re-read. The fan-meta
     *  batch ([IconGenerationManager.runFanMetaForPair]) writes title +
     *  icon straight to disk and has no transition of its own, so this
     *  is the bridge that keeps memory == disk in real time. No-op when
     *  the run / pair isn't loaded — the one-shot entry [hydrate] covers
     *  that case. */
    internal fun refreshPairFromDisk(context: Context, reportId: String, pairId: String) {
        val row = SecondaryResultStorage.get(context, reportId, pairId) ?: return
        // The pair row carries its fan-out prompt id, so the run key is
        // direct — no scan over every loaded run's whole pair set. A
        // blank metaPromptId never hydrates into a run, so bail like
        // the old scan's no-match path did.
        val key = row.metaPromptId?.takeIf { it.isNotBlank() }?.let { runKey(reportId, it) } ?: return
        _runs.update { runs ->
            val run = runs[key] ?: return@update runs
            val cur = run.pairs.values.firstOrNull { it.id == pairId } ?: return@update runs
            val next = cur.copy(
                title = row.title,
                titleInputCost = row.titleInputCost,
                titleOutputCost = row.titleOutputCost,
                titleErrorMessage = row.titleErrorMessage,
                titleRunId = row.titleRunId,
                titlePromptUsed = row.titlePromptUsed,
                titleModel = row.titleModel,
                icon = row.icon,
                iconWinningTier = row.iconWinningTier,
                iconInputCost = row.iconInputCost,
                iconOutputCost = row.iconOutputCost,
                iconErrorMessage = row.iconErrorMessage,
                iconRunId = row.iconRunId,
                iconPromptUsed = row.iconPromptUsed
            )
            if (next == cur) runs
            else runs + (key to run.copy(pairs = run.pairs + (cur.key to next)))
        }
    }

    suspend fun applyFanOutPairContent(
        context: Context,
        runKey: FanOutRunKey,
        pairId: String,
        content: String,
        changeSource: String,
        changeValue: String? = null
    ) {
        withContext(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@withContext
            val source = changeSource.takeIf { it.isNotBlank() } ?: return@withContext
            SecondaryResultStorage.updateContent(
                context = context,
                reportId = run.reportId,
                resultId = pairId,
                content = content,
                changeSource = source,
                changeValue = changeValue
            )
            ReportStorage.bumpReportTimestamp(context, run.reportId)
            transitionPairById(runKey, pairId) {
                it.copy(
                    status = PairStatus.DONE,
                    content = content,
                    errorMessage = null,
                    responseChangeSource = source,
                    responseChangeValue = changeValue?.takeIf { value -> value.isNotBlank() }
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // Run launch
    // -----------------------------------------------------------------

    /** Per-pair language context for a single-language fan-out run.
     *  [bodies] maps a source agentId → its translated response; a
     *  missing entry falls back to the original body for that pair. */
    private data class LangCtx(val native: String?, val prompt: String, val bodies: Map<String, String>)

    /** Build the translation context for [lang] once per run, lifting
     *  the TRANSLATE rows so each pair doesn't re-scan disk. Null when
     *  [lang] is null (run on the original untranslated text). */
    private fun resolveLangCtx(context: Context, reportId: String, report: Report, lang: String?): LangCtx? {
        if (lang == null) return null
        val translates = SecondaryResultStorage.listForReport(context, reportId)
            .filter {
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
        return LangCtx(native, translatedPrompt, bodies)
    }

    private fun updateTemperatureSweepState(key: String, transform: (TemperatureSweepState) -> TemperatureSweepState) {
        _temperatureSweepStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

    private fun setTemperatureSweepCandidate(key: String, index: Int, candidate: TemperatureSweepCandidate) {
        updateTemperatureSweepState(key) { state ->
            state.copy(candidates = state.candidates.mapIndexed { i, old -> if (i == index) candidate else old })
        }
    }

    fun clearFanOutTemperatureSweep(reportId: String, pairId: String) {
        val key = TemperatureSweepState.key(reportId, pairId)
        temperatureSweepJobs.remove(key)?.cancel()
        _temperatureSweepStates.update { it - key }
    }

    private fun updateReasoningEffortSweepState(
        key: String,
        transform: (ReasoningEffortSweepState) -> ReasoningEffortSweepState
    ) {
        _reasoningEffortSweepStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

    private fun setReasoningEffortCandidate(key: String, index: Int, candidate: ReasoningEffortCandidate) {
        updateReasoningEffortSweepState(key) { state ->
            state.copy(candidates = state.candidates.mapIndexed { i, old -> if (i == index) candidate else old })
        }
    }

    fun clearFanOutReasoningEffortSweep(reportId: String, pairId: String) {
        val key = ReasoningEffortSweepState.key(reportId, pairId)
        reasoningEffortSweepJobs.remove(key)?.cancel()
        _reasoningEffortSweepStates.update { it - key }
    }

    private fun updateWebSearchReplayState(key: String, transform: (WebSearchReplayState) -> WebSearchReplayState) {
        _webSearchReplayStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

    fun clearFanOutWebSearchReplay(reportId: String, pairId: String) {
        val key = WebSearchReplayState.key(reportId, pairId)
        webSearchReplayJobs.remove(key)?.cancel()
        _webSearchReplayStates.update { it - key }
    }

    private fun updatePromptEditReplayState(key: String, transform: (PromptEditReplayState) -> PromptEditReplayState) {
        _promptEditReplayStates.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to transform(existing))
        }
    }

    fun clearFanOutPromptEditReplay(reportId: String, pairId: String) {
        val key = PromptEditReplayState.key(reportId, pairId)
        promptEditReplayJobs.remove(key)?.cancel()
        _promptEditReplayStates.update { it - key }
    }

    private data class FanOutPairReplayTask(
        val reportId: String,
        val provider: AppService,
        val model: String,
        val agent: Agent,
        val prompt: String,
        val resolvedParams: AgentParameters,
        val baseUrl: String,
        val aiSettings: Settings
    )

    private data class FanOutVariationCallResult(
        val response: AnalysisResponse,
        val cost: Double?,
        val durationMs: Long,
        val traceFile: String?
    )

    private fun resolveFanOutSourceCount(context: Context, report: Report, row: SecondaryResult): Int {
        val successful = report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
        }
        val scoped = when (val scope = SecondaryScope.decodeOrAllReports(row.secondaryScope)) {
            SecondaryScope.AllReports -> successful
            is SecondaryScope.Manual -> successful.filter { it.agentId in scope.agentIds }
            is SecondaryScope.TopRanked -> {
                val rerank = SecondaryResultStorage.get(context, report.id, scope.rerankResultId)
                val topIds = extractTopRankedIds(rerank?.content, scope.count)
                if (topIds.isNullOrEmpty()) successful
                else topIds.mapNotNull { idx -> successful.getOrNull(idx - 1) }
            }
        }
        return scoped.size.takeIf { it > 0 } ?: successful.size.coerceAtLeast(1)
    }

    private fun buildFanOutPairReplayTask(context: Context, runKey: FanOutRunKey, pairId: String): FanOutPairReplayTask {
        val run = _runs.value[runKey] ?: error("Fan-out run no longer exists")
        val row = SecondaryResultStorage.get(context, run.reportId, pairId)
            ?: error("Fan-out pair no longer exists")
        val sourceId = row.fanOutSourceAgentId ?: error("This row is not a fan-out pair")
        val report = ReportStorage.getReport(context, run.reportId) ?: error("Report not found")
        val aiSettings = appViewModel.uiState.value.aiSettings
        val provider = AppService.findById(row.providerId) ?: error("Provider ${row.providerId} is not registered")
        val metaPrompt = row.metaPromptId?.let { aiSettings.getInternalPromptById(it) }
            ?: row.metaPromptName?.let { aiSettings.getInternalPromptByName(it) }
            ?: aiSettings.getInternalPromptById(run.metaPrompt.id)
            ?: error("Fan-out prompt no longer exists")
        val source = report.agents.firstOrNull { it.agentId == sourceId }
            ?: error("Source model response no longer exists")
        val langCtx = resolveLangCtx(context, run.reportId, report, row.targetLanguage)
        val sourceBody = langCtx?.bodies?.get(source.agentId) ?: source.responseBody.orEmpty()
        if (sourceBody.isBlank()) error("Source model response is empty")
        val question = langCtx?.prompt ?: report.prompt
        val resolvedBase = resolveSecondaryPrompt(
            metaPrompt.text,
            question = question,
            results = "",
            count = resolveFanOutSourceCount(context, report, row),
            title = report.title
        )
        val resolvedPrompt = resolvedBase.replace("@RESPONSE@", sourceBody)
        val agent = Agent(
            id = "fanout:${row.id}",
            name = row.agentName,
            provider = provider,
            model = row.model,
            apiKey = aiSettings.getApiKey(provider)
        )
        val params = resolveSecondaryParams(
            appViewModel.uiState.value.generalSettings,
            aiSettings,
            row.secondaryParameterPresetIds.orEmpty(),
            row.secondarySystemPromptId,
            metaPrompt
        )
        return FanOutPairReplayTask(
            reportId = run.reportId,
            provider = provider,
            model = row.model,
            agent = agent,
            prompt = resolvedPrompt,
            resolvedParams = params,
            baseUrl = aiSettings.getEffectiveEndpointUrlForAgent(agent),
            aiSettings = aiSettings
        )
    }

    suspend fun resolveFanOutPairPrompt(context: Context, runKey: FanOutRunKey, pairId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { buildFanOutPairReplayTask(context, runKey, pairId).prompt }.getOrNull()
        }

    private fun fanOutWebSearchPrompt(prompt: String): String =
        if (prompt.isBlank()) FAN_OUT_WEB_SEARCH_PROMPT_SUFFIX
        else prompt.trimEnd() + "\n\n" + FAN_OUT_WEB_SEARCH_PROMPT_SUFFIX

    private suspend fun runFanOutVariationCall(
        context: Context,
        task: FanOutPairReplayTask,
        kind: String,
        prompt: String = task.prompt,
        resolvedParams: AgentParameters = task.resolvedParams,
        overrideParams: AgentParameters? = null
    ): FanOutVariationCallResult = withTracerTags(reportId = task.reportId, category = kind) {
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
                                    task.agent,
                                    "",
                                    prompt,
                                    resolvedParams,
                                    overrideParams,
                                    context,
                                    task.baseUrl,
                                    aiSettings = task.aiSettings
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
            AnalysisResponse(
                service = task.provider,
                analysis = null,
                error = (e.message ?: "Unknown error").take(2000),
                agentName = task.agent.name
            )
        }
        val durationMs = System.currentTimeMillis() - startTime
        val cost = calculateResponseCost(context, task.provider, task.model, response.tokenUsage)
        if (response.error == null && response.tokenUsage != null) {
            val usage = response.tokenUsage
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                task.provider,
                task.model,
                usage,
                kind = kind,
                durationMs = durationMs
            )
        }
        FanOutVariationCallResult(response, cost, durationMs, traceSink.get())
    }

    fun startFanOutTemperatureSweep(
        context: Context,
        runKey: FanOutRunKey,
        pairId: String,
        temperatures: List<Float>
    ): Job {
        val run = _runs.value[runKey]
        val reportId = run?.reportId ?: runKey.substringBefore('|')
        val key = TemperatureSweepState.key(reportId, pairId)
        val temps = temperatures.take(3)
        temperatureSweepJobs.remove(key)?.cancel()
        _temperatureSweepStates.update {
            it + (key to TemperatureSweepState(
                reportId = reportId,
                agentId = pairId,
                candidates = temps.map { temp -> TemperatureSweepCandidate.Pending(temp) },
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildFanOutPairReplayTask(context, runKey, pairId)
                val supportedParams = PricingCache.getSupportedParameters(context, task.provider, task.model)
                if (supportedParams != null && supportedParams.none { it.equals("temperature", ignoreCase = true) }) {
                    val msg = "${task.provider.id}/${task.model} does not report temperature support."
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
                val range = temperatureRangeForProvider(task.provider)
                val invalidTemp = temps.firstOrNull { !range.contains(it) }
                if (temps.isEmpty() || invalidTemp != null) {
                    val msg = if (temps.isEmpty()) {
                        "Choose at least one temperature."
                    } else {
                        "${task.provider.id}/${task.model} allows temperature " +
                            "${formatSweepTemperature(range.min)}..${formatSweepTemperature(range.max)}."
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
                val canReason = task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)
                val canWeb = task.aiSettings.isWebSearchCapable(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(
                    webSearchTool = task.resolvedParams.webSearchTool && canWeb,
                    reasoningEffort = if (canReason) task.resolvedParams.reasoningEffort else null
                )
                temps.forEachIndexed { index, temp ->
                    setTemperatureSweepCandidate(key, index, TemperatureSweepCandidate.Running(temp))
                    val result = runFanOutVariationCall(
                        context = context,
                        task = task,
                        kind = FAN_OUT_TEMPERATURE_CALL_KIND,
                        resolvedParams = baseParams,
                        overrideParams = AgentParameters(temperature = temp)
                    )
                    val response = result.response
                    val candidate = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                        TemperatureSweepCandidate.Success(
                            temperature = temp,
                            response = response.analysis,
                            tokenUsage = response.tokenUsage,
                            cost = result.cost,
                            durationMs = result.durationMs,
                            traceFile = result.traceFile
                        )
                    } else {
                        TemperatureSweepCandidate.Error(
                            temperature = temp,
                            message = response.error ?: "No response body",
                            httpStatusCode = response.httpStatusCode,
                            durationMs = result.durationMs,
                            traceFile = result.traceFile
                        )
                    }
                    setTemperatureSweepCandidate(key, index, candidate)
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

    fun applyFanOutTemperatureCandidate(context: Context, runKey: FanOutRunKey, pairId: String, candidateIndex: Int) {
        val run = _runs.value[runKey] ?: return
        val key = TemperatureSweepState.key(run.reportId, pairId)
        val candidate = _temperatureSweepStates.value[key]?.candidates
            ?.getOrNull(candidateIndex) as? TemperatureSweepCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyFanOutPairContent(
                context = context,
                runKey = runKey,
                pairId = pairId,
                content = candidate.response,
                changeSource = RESPONSE_CHANGE_SOURCE_TEMPERATURE,
                changeValue = formatSweepTemperature(candidate.temperature)
            )
            _temperatureSweepStates.update { it - key }
        }
    }

    fun startFanOutReasoningEffortSweep(
        context: Context,
        runKey: FanOutRunKey,
        pairId: String,
        efforts: List<String?>
    ): Job {
        val run = _runs.value[runKey]
        val reportId = run?.reportId ?: runKey.substringBefore('|')
        val key = ReasoningEffortSweepState.key(reportId, pairId)
        val requestedEfforts = efforts
        reasoningEffortSweepJobs.remove(key)?.cancel()
        _reasoningEffortSweepStates.update {
            it + (key to ReasoningEffortSweepState(
                reportId = reportId,
                agentId = pairId,
                candidates = requestedEfforts.map { effort -> ReasoningEffortCandidate.Pending(effort) },
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildFanOutPairReplayTask(context, runKey, pairId)
                if (!task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)) {
                    val msg = "${task.provider.id}/${task.model} does not accept controllable reasoning effort."
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
                val supportedLevels = task.aiSettings.getProvider(task.provider)
                    .modelCapabilities[task.model]
                    ?.reasoningEffortLevels
                    ?.map { it.lowercase(Locale.US) }
                    ?.toSet()
                val canWeb = task.aiSettings.isWebSearchCapable(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(
                    webSearchTool = task.resolvedParams.webSearchTool && canWeb,
                    reasoningEffort = null
                )
                requestedEfforts.forEachIndexed { index, effort ->
                    if (effort != null && supportedLevels != null && effort !in supportedLevels) {
                        val msg = "${formatSweepReasoningEffort(effort)} reasoning effort is not reported as supported by " +
                            "${task.provider.id}/${task.model}."
                        setReasoningEffortCandidate(
                            key,
                            index,
                            ReasoningEffortCandidate.Error(effort, msg, null, null, null)
                        )
                        return@forEachIndexed
                    }
                    setReasoningEffortCandidate(key, index, ReasoningEffortCandidate.Running(effort))
                    val result = runFanOutVariationCall(
                        context = context,
                        task = task,
                        kind = FAN_OUT_REASONING_CALL_KIND,
                        resolvedParams = if (effort == null) baseParams else baseParams.copy(reasoningEffort = task.resolvedParams.reasoningEffort),
                        overrideParams = effort?.let { AgentParameters(reasoningEffort = it) }
                    )
                    val response = result.response
                    val candidate = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                        ReasoningEffortCandidate.Success(
                            effort = effort,
                            response = response.analysis,
                            tokenUsage = response.tokenUsage,
                            cost = result.cost,
                            durationMs = result.durationMs,
                            traceFile = result.traceFile
                        )
                    } else {
                        ReasoningEffortCandidate.Error(
                            effort = effort,
                            message = response.error ?: "No response body",
                            httpStatusCode = response.httpStatusCode,
                            durationMs = result.durationMs,
                            traceFile = result.traceFile
                        )
                    }
                    setReasoningEffortCandidate(key, index, candidate)
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

    fun applyFanOutReasoningEffortCandidate(context: Context, runKey: FanOutRunKey, pairId: String, candidateIndex: Int) {
        val run = _runs.value[runKey] ?: return
        val key = ReasoningEffortSweepState.key(run.reportId, pairId)
        val candidate = _reasoningEffortSweepStates.value[key]?.candidates
            ?.getOrNull(candidateIndex) as? ReasoningEffortCandidate.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyFanOutPairContent(
                context = context,
                runKey = runKey,
                pairId = pairId,
                content = candidate.response,
                changeSource = RESPONSE_CHANGE_SOURCE_REASONING_EFFORT,
                changeValue = formatSweepReasoningEffort(candidate.effort)
            )
            _reasoningEffortSweepStates.update { it - key }
        }
    }

    fun startFanOutWebSearchReplay(context: Context, runKey: FanOutRunKey, pairId: String): Job {
        val run = _runs.value[runKey]
        val reportId = run?.reportId ?: runKey.substringBefore('|')
        val key = WebSearchReplayState.key(reportId, pairId)
        webSearchReplayJobs.remove(key)?.cancel()
        _webSearchReplayStates.update {
            it + (key to WebSearchReplayState(
                reportId = reportId,
                agentId = pairId,
                result = WebSearchReplayResult.Running,
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val task = buildFanOutPairReplayTask(context, runKey, pairId)
                if (!task.aiSettings.isWebSearchCapable(task.provider, task.model)) {
                    val msg = "${task.provider.id}/${task.model} does not report web-search support."
                    updateWebSearchReplayState(key) {
                        it.copy(
                            isRunning = false,
                            result = WebSearchReplayResult.Error(msg, null, null, null),
                            unavailableMessage = msg
                        )
                    }
                    return@launch
                }
                val canReason = task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(
                    reasoningEffort = if (canReason) task.resolvedParams.reasoningEffort else null
                )
                val result = runFanOutVariationCall(
                    context = context,
                    task = task,
                    kind = FAN_OUT_WEB_SEARCH_CALL_KIND,
                    prompt = fanOutWebSearchPrompt(task.prompt),
                    resolvedParams = baseParams,
                    overrideParams = AgentParameters(webSearchTool = true)
                )
                val response = result.response
                val replay = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                    WebSearchReplayResult.Success(
                        response = response.analysis,
                        tokenUsage = response.tokenUsage,
                        cost = result.cost,
                        durationMs = result.durationMs,
                        traceFile = result.traceFile
                    )
                } else {
                    WebSearchReplayResult.Error(
                        message = response.error ?: "No response body",
                        httpStatusCode = response.httpStatusCode,
                        durationMs = result.durationMs,
                        traceFile = result.traceFile
                    )
                }
                updateWebSearchReplayState(key) { it.copy(isRunning = false, result = replay) }
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

    fun applyFanOutWebSearchReplay(context: Context, runKey: FanOutRunKey, pairId: String) {
        val run = _runs.value[runKey] ?: return
        val key = WebSearchReplayState.key(run.reportId, pairId)
        val result = _webSearchReplayStates.value[key]?.result as? WebSearchReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyFanOutPairContent(
                context = context,
                runKey = runKey,
                pairId = pairId,
                content = result.response,
                changeSource = RESPONSE_CHANGE_SOURCE_WEB_SEARCH
            )
            _webSearchReplayStates.update { it - key }
        }
    }

    fun startFanOutPromptEditReplay(
        context: Context,
        runKey: FanOutRunKey,
        pairId: String,
        prompt: String,
        parameterPresetIds: List<String>,
        systemPromptId: String?
    ): Job {
        val run = _runs.value[runKey]
        val reportId = run?.reportId ?: runKey.substringBefore('|')
        val key = PromptEditReplayState.key(reportId, pairId)
        val editedPrompt = prompt.trim()
        promptEditReplayJobs.remove(key)?.cancel()
        _promptEditReplayStates.update {
            it + (key to PromptEditReplayState(
                reportId = reportId,
                agentId = pairId,
                result = PromptEditReplayResult.Running,
                isRunning = true
            ))
        }
        val job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
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
                val task = buildFanOutPairReplayTask(context, runKey, pairId)
                val canReason = task.aiSettings.acceptsReasoningEffortParam(task.provider, task.model)
                val canWeb = task.aiSettings.isWebSearchCapable(task.provider, task.model)
                val baseParams = task.resolvedParams.copy(
                    webSearchTool = task.resolvedParams.webSearchTool && canWeb,
                    reasoningEffort = if (canReason) task.resolvedParams.reasoningEffort else null
                )
                val screenOverride = promptEditOverrideParams(task.aiSettings, parameterPresetIds, systemPromptId)
                val finalParams = overlayAgentParameters(baseParams, screenOverride)!!.let { merged ->
                    merged.copy(
                        webSearchTool = merged.webSearchTool && canWeb,
                        reasoningEffort = if (canReason) merged.reasoningEffort else null
                    )
                }
                val result = runFanOutVariationCall(
                    context = context,
                    task = task,
                    kind = FAN_OUT_PROMPT_EDIT_CALL_KIND,
                    prompt = editedPrompt,
                    resolvedParams = finalParams,
                    overrideParams = null
                )
                val response = result.response
                val replay = if (response.isSuccess && !response.analysis.isNullOrBlank()) {
                    PromptEditReplayResult.Success(
                        response = response.analysis,
                        tokenUsage = response.tokenUsage,
                        cost = result.cost,
                        durationMs = result.durationMs,
                        traceFile = result.traceFile
                    )
                } else {
                    PromptEditReplayResult.Error(
                        message = response.error ?: "No response body",
                        httpStatusCode = response.httpStatusCode,
                        durationMs = result.durationMs,
                        traceFile = result.traceFile
                    )
                }
                updatePromptEditReplayState(key) { it.copy(isRunning = false, result = replay) }
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

    fun applyFanOutPromptEditReplay(context: Context, runKey: FanOutRunKey, pairId: String) {
        val run = _runs.value[runKey] ?: return
        val key = PromptEditReplayState.key(run.reportId, pairId)
        val result = _promptEditReplayStates.value[key]?.result as? PromptEditReplayResult.Success ?: return
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            applyFanOutPairContent(
                context = context,
                runKey = runKey,
                pairId = pairId,
                content = result.response,
                changeSource = RESPONSE_CHANGE_SOURCE_EDIT
            )
            _promptEditReplayStates.update { it - key }
        }
    }

    /** Launch a brand-new fan-out run. The single canonical launch
     *  path (replaces the legacy `SecondaryRunManager.runFanOutPrompt`):
     *  resolves the scope + responder set, pre-creates every
     *  (answerer, source) placeholder row on disk, publishes the
     *  [FanOutRunState] with all pairs PENDING (so the UI's stats read
     *  the full expected work immediately), then runs each pair through
     *  the shared throttled batch + [runOnePair]. The per-pair coroutine
     *  Job lands in the shared item-job registry (cancel/delete target it) and the outer
     *  batch Job in the shared run-job registry (rerunComplete / deleteRun join it).
     *  Dedupes against an already-running launch for the same
     *  (report, prompt) so a UI double-tap can't double the pairs. */
    fun startRun(
        context: Context,
        reportId: String,
        metaPrompt: InternalPrompt,
        scopeChoice: SecondaryScope = SecondaryScope.AllReports,
        responderAgentIds: Set<String>? = null,
        sourceLanguage: String? = null,
        paramsIds: List<String> = emptyList(),
        systemPromptId: String? = null,
        /** When true, a model is also paired with its OWN answer (the
         *  self-pair the matrix normally skips). Default false. */
        includeSelfResponses: Boolean = false,
        /** Build-stage key for the blocking "Preparing…" popup; null on
         *  resume / rerun paths (no popup). The build phase = creating the
         *  per-pair placeholders below. */
        buildKey: String? = null
    ): Job? {
        val rk = runKey(reportId, metaPrompt.id)
        runJobOf(rk)?.let { if (it.isActive) return it }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            val cat = "${metaPrompt.category}/${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat, runId = runId) {
                    val aiSettings = appViewModel.uiState.value.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags
                    AuditLog.append(reportId, "Start Fan Out '${metaPrompt.name}' — ${successful.size} source model(s)")
                    val sources = when (scopeChoice) {
                        SecondaryScope.AllReports -> successful
                        is SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                            val topIds = com.ai.data.extractTopRankedIds(rerank?.content, scopeChoice.count)
                            if (topIds.isNullOrEmpty()) successful
                            else topIds.mapNotNull { idx -> successful.getOrNull(idx - 1) }
                        }
                        is SecondaryScope.Manual -> successful.filter { it.agentId in scopeChoice.agentIds }
                    }
                    if (sources.isEmpty()) return@withTracerTags
                    val answerers = if (responderAgentIds == null) successful
                        else successful.filter { it.agentId in responderAgentIds }
                    if (answerers.isEmpty()) return@withTracerTags
                    val langCtx = resolveLangCtx(context, reportId, report, sourceLanguage)
                    val langSuffix = sourceLanguage?.let { " [$it]" } ?: ""
                    val scopeEncoded = scopeChoice.encode()

                    // Pre-create every (answerer, source) placeholder + its
                    // PENDING PairState so the L1/L2 counts read the full
                    // expected work the instant the run starts.
                    data class PendingPair(val answerer: ReportAgent, val source: ReportAgent, val placeholder: SecondaryResult)
                    val pending = mutableListOf<PendingPair>()
                    val newPairs = mutableMapOf<PairKey, PairState>()
                    // Build stage: construct every row first, then persist the
                    // full placeholder set with one data-version bump.
                    val totalPairs = answerers.filter { AppService.findById(it.provider) != null }
                        .sumOf { a -> sources.count { s -> includeSelfResponses || s.agentId != a.agentId } }
                    appViewModel.runBatchBuild(buildKey, totalPairs, "Building fan-out", updateEvery = 10) {
                        for (answerer in answerers) {
                            val provider = AppService.findById(answerer.provider) ?: continue
                            for (source in sources) {
                                if (source.agentId == answerer.agentId && !includeSelfResponses) continue
                                val agentName = "${provider.id} / ${shortModelName(answerer.model)}$langSuffix"
                                val placeholder = SecondaryResult(
                                    id = java.util.UUID.randomUUID().toString(),
                                    reportId = reportId,
                                    kind = SecondaryKind.META,
                                    providerId = provider.id,
                                    model = answerer.model,
                                    agentName = agentName,
                                    timestamp = System.currentTimeMillis(),
                                    content = null,
                                    metaPromptId = metaPrompt.id,
                                    metaPromptName = metaPrompt.name,
                                    fanOutSourceAgentId = source.agentId,
                                    runId = runId,
                                    targetLanguage = sourceLanguage,
                                    targetLanguageNative = langCtx?.native,
                                    secondaryScope = scopeEncoded,
                                    secondaryParameterPresetIds = paramsIds,
                                    secondarySystemPromptId = systemPromptId
                                )
                                pending.add(PendingPair(answerer, source, placeholder))
                            }
                        }
                        // Batched save so a large fan-out bumps storage observers
                        // once; drop any row that failed to persist. The build
                        // counter tracks these writes — the in-memory pair
                        // construction above is instant.
                        val savedIds = SecondaryResultStorage.saveAll(
                            context, pending.map { it.placeholder },
                            onProgress = { n -> set(n) }
                        ).mapTo(HashSet()) { it.id }
                        pending.removeAll { it.placeholder.id !in savedIds }
                        pending.forEach { item ->
                            item.placeholder.toPairState(item.answerer.agentId)?.let { newPairs[it.key] = it }
                        }
                    }
                    // Publish the run state — preserve any existing
                    // combined-report rows already attached to this run.
                    val existingCombined = _runs.value[rk]?.combinedReports.orEmpty()
                    _runs.update { runs ->
                        runs + (rk to FanOutRunState(
                            key = rk,
                            reportId = reportId,
                            metaPrompt = metaPrompt,
                            scope = scopeChoice,
                            responderIds = responderAgentIds,
                            pairs = newPairs,
                            combinedReports = existingCombined,
                            sourceLanguage = sourceLanguage
                        ))
                    }
                    runThrottledBatch(
                        items = pending,
                        hostOf = { AppService.findById(it.answerer.provider)?.let { s -> providerHost(s) } },
                        subCap = ApiCallCaps.fanOut,
                        onThrottled = { item -> appViewModel.updateThrottledFanOutPairs { it + item.placeholder.id } },
                        onCleared = { item -> appViewModel.updateThrottledFanOutPairs { it - item.placeholder.id } },
                        register = { item, d ->
                            registerItemJob(item.placeholder.id, d)
                        },
                        // Type-A fixed-model batch: a 429/529 short-benches the
                        // answerer model and re-queues the pair (and parks its
                        // same-model siblings) instead of erroring outright.
                        benchEnabled = com.ai.data.ModelCooldownStore.typeABenchEnabled,
                        benchKey = { item -> item.answerer.provider to item.answerer.model },
                        onBenchRetry = { item ->
                            restoreBenchedPairForRequeue(
                                context, rk, item.placeholder.id, item.answerer.agentId, item.source.agentId
                            )
                        }
                    ) { item ->
                        val question = langCtx?.prompt ?: report.prompt
                        val body = langCtx?.bodies?.get(item.source.agentId)
                            ?: (item.source.responseBody ?: "")
                        runOnePair(
                            context, rk, item.placeholder.id,
                            item.answerer.agentId, item.source.agentId,
                            item.answerer.provider, item.answerer.model,
                            metaPrompt, report, aiSettings,
                            sourceCount = sources.size,
                            question = question, sourceBody = body,
                            targetLanguage = sourceLanguage,
                            targetLanguageNative = langCtx?.native,
                            paramsIds = paramsIds, systemPromptId = systemPromptId,
                            placeholder = item.placeholder
                        )
                    }
                    AuditLog.append(reportId, "End Fan Out '${metaPrompt.name}' — ${pending.size} pair(s)")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverPairs(context, reportId, metaPrompt.id)
                // Dismiss a stuck build popup if the job returned early before
                // finishBuild (too few sources/answerers, report gone). On the
                // success path the UI already cleared it on `done`.
                if (buildKey != null) {
                    val p = appViewModel.batchBuildProgress.value[buildKey]
                    if (p != null && !p.done) appViewModel.clearBuild(buildKey)
                }
            }
        }
        registerRunJob(rk, job)
        return job
    }

    /** Run-end finalizer: terminalize (❌) every pair for [metaPromptId]
     *  still PENDING on disk (no content / error / durationMs) and not in
     *  flight, and mirror the error into the flow. Fires on normal
     *  completion (pairs skipped because a provider didn't resolve) and
     *  on in-app cancellation; a process kill skips it (the background
     *  resume sweep is the safety net there). NonCancellable so the disk
     *  writes land even when the run coroutine was cancelled. */
    private suspend fun finalizeLeftoverPairs(context: Context, reportId: String, metaPromptId: String) {
        withContext(kotlinx.coroutines.NonCancellable) {
            val leftover = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.metaPromptId == metaPromptId && it.fanOutSourceAgentId != null &&
                        it.fanInOf == null && it.content.isNullOrBlank() &&
                        it.errorMessage == null && it.durationMs == null &&
                        !hasItemJob(it.id)
                }
            val rk = runKey(reportId, metaPromptId)
            BatchResume.finalizeLeftover(leftover) { row ->
                markRowInterrupted(context, reportId, row.id, "Interrupted — run stopped before this pair finished")
                _runs.value[rk]?.pairs?.values?.firstOrNull { it.id == row.id }?.let { p ->
                    transitionPair(rk, p.key) {
                        if (it.status == PairStatus.PENDING || it.status == PairStatus.RUNNING)
                            it.copy(status = PairStatus.ERROR, errorMessage = "Interrupted — run stopped before this pair finished", durationMs = 0)
                        else it
                    }
                }
            }
        }
    }

    /** TOCTOU-safe flip of a stuck placeholder into a terminal errored
     *  row. Re-reads right before saving so an in-flight completion isn't
     *  clobbered. */
    private fun markRowInterrupted(context: Context, reportId: String, rowId: String, message: String) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null) return
        if (!current.content.isNullOrBlank()) return
        if (current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(errorMessage = message, durationMs = 0))
    }

    /** Best-effort cancel of every in-flight run + pair coroutine for
     *  [reportId] (called from the synchronous [ReportViewModel.deleteReport]
     *  before the rows are deleted). Cooperative cancel, no join — the
     *  per-pair runner's `exists()` / saveIfStillPresent guards drop any
     *  write that lands after the row is gone. */
    fun cancelAllForReport(reportId: String) {
        val prefix = "$reportId|"
        runJobKeys().filter { it.startsWith(prefix) }.forEach { runJobOf(it)?.cancel() }
        _runs.value.filterKeys { it.startsWith(prefix) }
            .values.flatMap { it.pairs.values.map { p -> p.id } }
            .forEach {
                itemJobOf(it)?.cancel()
                temperatureSweepJobs[TemperatureSweepState.key(reportId, it)]?.cancel()
                reasoningEffortSweepJobs[ReasoningEffortSweepState.key(reportId, it)]?.cancel()
                webSearchReplayJobs[WebSearchReplayState.key(reportId, it)]?.cancel()
                promptEditReplayJobs[PromptEditReplayState.key(reportId, it)]?.cancel()
            }
        temperatureSweepJobs.keys.filter { it.startsWith(prefix) }.forEach { temperatureSweepJobs.remove(it)?.cancel() }
        reasoningEffortSweepJobs.keys.filter { it.startsWith(prefix) }.forEach { reasoningEffortSweepJobs.remove(it)?.cancel() }
        webSearchReplayJobs.keys.filter { it.startsWith(prefix) }.forEach { webSearchReplayJobs.remove(it)?.cancel() }
        promptEditReplayJobs.keys.filter { it.startsWith(prefix) }.forEach { promptEditReplayJobs.remove(it)?.cancel() }
        _temperatureSweepStates.update { it.filterKeys { key -> !key.startsWith(prefix) } }
        _reasoningEffortSweepStates.update { it.filterKeys { key -> !key.startsWith(prefix) } }
        _webSearchReplayStates.update { it.filterKeys { key -> !key.startsWith(prefix) } }
        _promptEditReplayStates.update { it.filterKeys { key -> !key.startsWith(prefix) } }
        dropRunsForReport(reportId)
    }

    private fun dropRunsForReport(reportId: String) {
        _runs.update { it.filterKeys { k -> !k.startsWith("$reportId|") } }
    }

    /** Join every in-flight run Job for [reportId] — used by fan-in so a
     *  combine reads a complete set of pair rows rather than a partial
     *  in-flight subset. */
    suspend fun joinActiveRunsForReport(reportId: String) {
        val prefix = "$reportId|"
        // Exclude the fan-meta batch jobs (namespaced "|meta|") — fan-in
        // combines the fan-out RESPONSES and must not block on the title/icon
        // pass, which can run long after the responses have landed.
        runJobKeys().filter { it.startsWith(prefix) && !it.contains("|meta|") }
            .forEach { runJobOf(it)?.join() }
    }

    // -----------------------------------------------------------------
    // Per-pair runner — single canonical path
    // -----------------------------------------------------------------

    /** PENDING → RUNNING → DONE/ERROR. The per-pair HTTP call (via
     *  [ReportViewModel.executeSecondaryTask]) + the engine's state-flow
     *  transitions. Throttle permit acquisition (global → fan-out →
     *  per-host) + permitPreAcquired are owned by [runThrottledBatch],
     *  which calls this as its body — so this runs with the permits held.
     *  Keeps its own local 60s withTimeout (caught here so a runaway
     *  model fails just this pair). */
    /** Reset a pair the bench loop is about to re-queue: clear the error the
     *  failed attempt persisted so the row re-reads as a fresh placeholder,
     *  and put the in-memory pair back to PENDING (it shows as Bench while its
     *  model is short-benched, then Queue once the bench lifts). */
    private fun restoreBenchedPairForRequeue(
        context: Context, runKey: FanOutRunKey,
        placeholderId: String, answererAgentId: String, sourceAgentId: String
    ) {
        val reportId = runKey.substringBefore('|')
        val pk = pairKey(answererAgentId, sourceAgentId)
        SecondaryResultStorage.get(context, reportId, placeholderId)?.let { saved ->
            SecondaryResultStorage.save(
                context,
                saved.copy(
                    content = null,
                    errorMessage = null,
                    durationMs = null,
                    httpStatusCode = null,
                    tokenUsage = null
                )
            )
        }
        transitionPair(runKey, pk) {
            it.copy(
                status = PairStatus.PENDING,
                content = null,
                errorMessage = null,
                durationMs = null,
                httpStatusCode = null
            )
        }
    }

    private suspend fun runOnePair(
        context: Context,
        runKey: FanOutRunKey,
        placeholderId: String,
        answererAgentId: String,
        sourceAgentId: String,
        answererProviderId: String,
        answererModel: String,
        metaPrompt: InternalPrompt,
        report: Report,
        aiSettings: Settings,
        sourceCount: Int,
        question: String,
        sourceBody: String,
        targetLanguage: String?,
        targetLanguageNative: String?,
        paramsIds: List<String>,
        systemPromptId: String?,
        placeholder: SecondaryResult
    ) {
        val pk = pairKey(answererAgentId, sourceAgentId)
        val provider = AppService.findById(answererProviderId) ?: run {
            transitionPair(runKey, pk) {
                it.copy(status = PairStatus.ERROR, errorMessage = "Provider $answererProviderId not registered")
            }
            return
        }
        AppLog.d("FanOut", "queued pair ans=$answererAgentId src=$sourceAgentId ${provider.id}/$answererModel")
        if (!SecondaryResultStorage.exists(context, runKey.substringBefore('|'), placeholderId)) {
            AppLog.d("FanOut", "skip pair $placeholderId — deleted before launch")
            dropPair(runKey, pk)
            return
        }
        run {
                    transitionPair(runKey, pk) { it.copy(status = PairStatus.RUNNING) }
                    val pairStart = System.currentTimeMillis()
                    try {
                        val resolvedBase = resolveSecondaryPrompt(
                            metaPrompt.text,
                            question = question,
                            results = "",
                            count = sourceCount,
                            title = report.title
                        )
                        val resolved = resolvedBase.replace("@RESPONSE@", sourceBody)
                        // Per-pair wall-clock ceiling (the user-tunable
                        // "Batch item" timeout, default 180 s). Stops a
                        // single runaway model (the Qwen2.5-7B word-salad
                        // case that produced 4096 tokens of nonsense in
                        // ~108s) from pinning its per-host slot for
                        // most of the wall clock, while leaving slow
                        // reasoning answerers room to finish a long
                        // legitimate reply. On timeout we persist an
                        // errorMessage so the row counts as ERROR for
                        // the progress bar.
                        val ceilingSec = NetworkSettings.batchItemTimeoutSec
                        try {
                            withTimeout(NetworkSettings.batchItemTimeoutMs) {
                                reportViewModel.secondary.executeSecondaryTask(
                                    context, report.id, SecondaryKind.META, metaPrompt,
                                    provider, answererModel, resolved, aiSettings, report,
                                    targetLanguage = targetLanguage,
                                    targetLanguageNative = targetLanguageNative,
                                    fanOutSourceAgentId = sourceAgentId,
                                    existingPlaceholder = placeholder,
                                    paramsIds = paramsIds, systemPromptId = systemPromptId
                                )
                            }
                        } catch (e: TimeoutCancellationException) {
                            // Stamp the placeholder so the post-call
                            // re-read picks it up as ERROR and the
                            // progress bar advances.
                            val timedOut = (SecondaryResultStorage.get(context, report.id, placeholderId) ?: placeholder)
                                .copy(
                                    errorMessage = "Fan-out pair timed out after ${ceilingSec}s",
                                    durationMs = System.currentTimeMillis() - pairStart
                                )
                            SecondaryResultStorage.save(context, timedOut)
                            AppLog.w("FanOut", "pair ans=$answererAgentId src=$sourceAgentId timed out after ${ceilingSec}s")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One poisoned pair (a throw that escaped
                        // executeSecondaryTask's own handling) must fail just
                        // this pair — an escape would cancel every in-flight
                        // sibling via the batch's coroutineScope. Guarded
                        // stamp: a row that already settled keeps its result.
                        val cur = SecondaryResultStorage.get(context, report.id, placeholderId)
                        if (cur != null && cur.errorMessage == null &&
                            cur.content.isNullOrBlank() && cur.durationMs == null
                        ) {
                            SecondaryResultStorage.save(context, cur.copy(
                                errorMessage = "Fan-out pair failed: ${e.javaClass.simpleName}: ${e.message}",
                                durationMs = System.currentTimeMillis() - pairStart
                            ))
                        }
                        AppLog.w("FanOut", "pair ans=$answererAgentId src=$sourceAgentId threw ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        // Re-read the now-persisted row + mirror it into the
                        // in-memory PairState in a NonCancellable block, so a
                        // stop / cancel mid-call still lands the pair on its
                        // disk-truth status instead of leaving it stuck at
                        // RUNNING — the per-screen 3s re-hydrate that used to
                        // recover that case has been removed.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            val saved = SecondaryResultStorage.get(context, report.id, placeholderId)
                            if (saved != null) {
                                val ns = when {
                                    saved.errorMessage != null -> PairStatus.ERROR
                                    !saved.content.isNullOrBlank() || saved.durationMs != null -> PairStatus.DONE
                                    else -> PairStatus.PENDING
                                }
                                transitionPair(runKey, pk) {
                                    it.copy(
                                        status = ns,
                                        content = saved.content,
                                        errorMessage = saved.errorMessage,
                                        inputCost = saved.inputCost,
                                        outputCost = saved.outputCost,
                                        durationMs = saved.durationMs,
                                        httpStatusCode = saved.httpStatusCode,
                                        tokenUsage = saved.tokenUsage,
                                        responseChangeSource = saved.responseChangeSource,
                                        responseChangeValue = saved.responseChangeValue
                                    )
                                }
                            } else {
                                // Row vanished mid-call (user deleted) — drop
                                // the pair from state.
                                dropPair(runKey, pk)
                            }
                        }
                        AppLog.d("FanOut", "← pair ans=$answererAgentId src=$sourceAgentId ${System.currentTimeMillis() - pairStart}ms")
                    }
                }
    }

    // -----------------------------------------------------------------
    // Failure handling
    // -----------------------------------------------------------------

    /** Drop every errored pair row from this run without re-firing. */
    fun removeFailedPairs(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            // Benched pairs are kept — they're cleared by
            // removeBenchedPairs instead, so the two are complementary.
            val failed = run.pairs.values.filter {
                it.status == PairStatus.ERROR &&
                    !ModelCooldownStore.isUnavailable(it.providerId, it.model)
            }
            if (failed.isEmpty()) return@launch
            val costDelta = failed.sumOf {
                SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
            }
            failed.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, failed.map { it.id }.toSet())
            _runs.update { runs ->
                val cur = runs[runKey] ?: return@update runs
                val keepKeys = failed.map { it.key }.toSet()
                runs + (runKey to cur.copy(pairs = cur.pairs - keepKeys))
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    /** Drop every unfinished (stranded, never-ran) pair without re-firing —
     *  the Broken-work "delete unfinished" action. Mirror of
     *  [removeFailedPairs], narrowed to PENDING rows. */
    fun removeUnfinishedPairs(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val stranded = run.pairs.values.filter { it.status == PairStatus.PENDING }
            if (stranded.isEmpty()) return@launch
            val costDelta = stranded.sumOf {
                SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
            }
            stranded.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, stranded.map { it.id }.toSet())
            _runs.update { runs ->
                val cur = runs[runKey] ?: return@update runs
                val keepKeys = stranded.map { it.key }.toSet()
                runs + (runKey to cur.copy(pairs = cur.pairs - keepKeys))
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    /** Drop only the errored pairs whose model is currently benched
     *  (>1h-429 cooldown). Mirror of [removeFailedPairs] — same
     *  delete + state-update + cost/timestamp bump — narrowed to the
     *  benched subset so the user can clear the will-recover failures
     *  without touching the genuine ones. */
    fun removeBenchedPairs(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val benched = run.pairs.values.filter {
                it.status == PairStatus.ERROR &&
                    ModelCooldownStore.isUnavailable(it.providerId, it.model)
            }
            if (benched.isEmpty()) return@launch
            val costDelta = benched.sumOf {
                SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
            }
            benched.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, benched.map { it.id }.toSet())
            _runs.update { runs ->
                val cur = runs[runKey] ?: return@update runs
                val dropKeys = benched.map { it.key }.toSet()
                runs + (runKey to cur.copy(pairs = cur.pairs - dropKeys))
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    /** L2-scoped: drop errored pairs where (provider, model) is
     *  the ANSWERER. */
    fun removeFailedPairsForModel(
        context: Context,
        runKey: FanOutRunKey,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val run = _runs.value[runKey] ?: return@launch
        val failed = run.pairs.values.filter {
            it.status == PairStatus.ERROR &&
                it.providerId.equals(providerId, ignoreCase = true) &&
                it.model == model
        }
        if (failed.isEmpty()) return@launch
        val costDelta = failed.sumOf {
                SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
            }
        failed.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
        ReportStorage.removeFanOutIconCalls(context, run.reportId, failed.map { it.id }.toSet())
        _runs.update { runs ->
            val cur = runs[runKey] ?: return@update runs
            val keepKeys = failed.map { it.key }.toSet()
            runs + (runKey to cur.copy(pairs = cur.pairs - keepKeys))
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, run.reportId)
    }

    /** Re-fire every errored pair in this run, in one parallel batch. */
    fun restartFailedPairs(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            // Genuine errors only — benched (cooldown) pairs are left for
            // removeBenchedPairs / auto-recovery; re-firing them just bounces
            // off the live cooldown. Matches removeFailedPairs and the L1
            // "Restart failed" button gating (mainErrored).
            val keys = run.pairs.values.filter {
                it.status == PairStatus.ERROR &&
                    !ModelCooldownStore.isUnavailable(it.providerId, it.model)
            }.map { it.key }
            rerunPairsBlocking(context, runKey, keys)
        }

    fun restartPairsByIds(context: Context, runKey: FanOutRunKey, pairIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val keys = run.pairs.values.filter { it.id in pairIds }.map { it.key }
            rerunPairsBlocking(context, runKey, keys)
        }

    /** Broken-work "Continue": stop this run's in-flight pairs (keeping the
     *  run + its finished pairs), then re-queue every broken pair — stranded
     *  PENDING placeholders plus genuine ERRORs (cooldown-benched ones are
     *  left for auto-recovery, matching [restartFailedPairs]) — and
     *  re-dispatch in one batch, driving the build-stage popup off [buildKey].
     *  Finished pairs are untouched. */
    fun continueBrokenBatch(context: Context, runKey: FanOutRunKey, buildKey: String?): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                stopInFlightKeepingState(runKey)
                hydrate(context, runKey.substringBefore('|'))
                _runs.value[runKey]?.let { run ->
                    val keys = run.pairs.values.filter {
                        it.status == PairStatus.PENDING ||
                            (it.status == PairStatus.ERROR &&
                                !ModelCooldownStore.isUnavailable(it.providerId, it.model))
                    }.map { it.key }
                    rerunPairsBlocking(context, runKey, keys, buildKey)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                buildKey?.let { appViewModel.clearBuild(it) }
                throw e
            } catch (e: Exception) {
                AppLog.w("FanOut", "continue broken batch failed runKey=$runKey: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // Release the popup so the overlay still opens when there was
                // nothing to re-queue or the run vanished (the normal path
                // already finished the build before dispatch).
                buildKey?.let {
                    if (appViewModel.batchBuildProgress.value[it]?.done != true) appViewModel.finishBuild(it)
                }
            }
        }

    /** Cancel this run's outer Job + every per-pair coroutine and JOIN them
     *  (so no in-flight write lands after we re-queue), WITHOUT deleting rows
     *  or dropping the run from the flow — the keep-state counterpart of
     *  [cancelAllForReport], used by [continueBrokenBatch]. */
    private suspend fun stopInFlightKeepingState(runKey: FanOutRunKey) {
        runJobOf(runKey)?.cancelAndJoin()
        _runs.value[runKey]?.pairs?.values?.forEach { itemJobOf(it.id)?.cancelAndJoin() }
    }

    fun removePairsByIds(context: Context, runKey: FanOutRunKey, pairIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val victims = run.pairs.values.filter { it.id in pairIds }
            if (victims.isEmpty()) return@launch
            victims.forEach { itemJobOf(it.id)?.cancelAndJoin() }
            val costDelta = victims.sumOf {
                SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
            }
            victims.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, victims.map { it.id }.toSet())
            _runs.update { runs ->
                val cur = runs[runKey] ?: return@update runs
                val dropKeys = victims.map { it.key }.toSet()
                runs + (runKey to cur.copy(pairs = cur.pairs - dropKeys))
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    /** L2-scoped restart — one parallel batch of the model's errored pairs. */
    fun restartFailedPairsForModel(
        context: Context,
        runKey: FanOutRunKey,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val run = _runs.value[runKey] ?: return@launch
        val keys = run.pairs.values
            .filter {
                it.status == PairStatus.ERROR &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
            .map { it.key }
        rerunPairsBlocking(context, runKey, keys)
    }

    // -----------------------------------------------------------------
    // Per-pair rerun / cancel / delete
    // -----------------------------------------------------------------

    /** Re-fire a single pair. Re-uses the same placeholder id so the
     *  L3 detail row keeps its identity. */
    fun rerunPair(context: Context, runKey: FanOutRunKey, pairKey: PairKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            rerunPairBlocking(context, runKey, pairKey)
        }

    /** Re-fire a single pair identified by its on-disk SecondaryResult
     *  id (the [PairState.id]) — used by call sites that hold the row,
     *  not the [PairKey]. */
    fun rerunPairById(context: Context, runKey: FanOutRunKey, pairId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val pair = run.pairs.values.firstOrNull { it.id == pairId } ?: return@launch
            rerunPairBlocking(context, runKey, pair.key)
        }

    private suspend fun rerunPairBlocking(
        context: Context,
        runKey: FanOutRunKey,
        pairKey: PairKey
    ) = rerunPairsBlocking(context, runKey, listOf(pairKey))

    /** Re-fire a set of pairs in one throttled batch (parallel, same
     *  canonical global → fan-out → per-host order + permitPreAcquired
     *  as a fresh run). Each pair's on-disk row is cleared to PENDING
     *  first (so saveIfStillPresent doesn't keep the old content) and
     *  its flow status reset; the per-pair Jobs land in the shared item-job registry so
     *  cancel/delete can target them. Drives the restart-failed +
     *  single-pair rerun + app-kill resume paths. */
    private suspend fun rerunPairsBlocking(
        context: Context,
        runKey: FanOutRunKey,
        pairKeys: List<PairKey>,
        buildKey: String? = null
    ) {
        if (pairKeys.isEmpty()) { buildKey?.let { appViewModel.finishBuild(it) }; return }
        val run = _runs.value[runKey] ?: return
        val report = ReportStorage.getReport(context, run.reportId) ?: return
        val aiSettings = appViewModel.uiState.value.aiSettings
        val cat = "${run.metaPrompt.category}/${run.metaPrompt.name}"
        val sourceCount = report.agents.count {
            it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
        }
        // Resolve the run's language context once so a translated
        // fan-out's reruns fire against the translated body + prompt
        // (matching the original batch), not the untranslated source.
        val langCtx = resolveLangCtx(context, run.reportId, report, run.sourceLanguage)
        val question = langCtx?.prompt ?: report.prompt

        // Clear each row to a PENDING shape on disk + in the flow, and
        // keep the cleared placeholder so the runner writes against it.
        data class Reset(val pair: PairState, val cleared: SecondaryResult, val body: String)
        val resets = mutableListOf<Reset>()
        var clearedCostDelta = 0.0
        val agentsById = report.agents.associateBy { it.agentId }
        // Build stage: clearing each broken row to a PENDING placeholder is
        // the "Preparing N / M…" phase the Broken-work Continue popup covers.
        if (buildKey != null) appViewModel.beginBuild(buildKey, pairKeys.size, "Re-queuing fan-out")
        for (pk in pairKeys) {
            val pair = run.pairs[pk] ?: continue
            val source = agentsById[pair.sourceAgentId] ?: continue
            val current = SecondaryResultStorage.get(context, run.reportId, pair.id) ?: continue
            clearedCostDelta += current.fullCost()
            val cleared = current.copy(
                content = null, errorMessage = null, inputCost = null, outputCost = null,
                durationMs = null, httpStatusCode = null, tokenUsage = null, timestamp = System.currentTimeMillis(),
                responseChangeSource = null,
                responseChangeValue = null
            )
            transitionPair(runKey, pk) {
                it.copy(
                    status = PairStatus.PENDING, content = null, errorMessage = null,
                    inputCost = null, outputCost = null, durationMs = null,
                    httpStatusCode = null, tokenUsage = null, timestamp = cleared.timestamp,
                    responseChangeSource = null,
                    responseChangeValue = null
                )
            }
            val body = langCtx?.bodies?.get(pair.sourceAgentId) ?: source.responseBody.orEmpty()
            resets.add(Reset(pair, cleared, body))
        }
        // One batched write for all cleared rows (single storage lock +
        // data-version bump) — a restart over hundreds of pairs otherwise
        // pays a save round-trip and observer wake-up per row. The build
        // counter tracks these writes — the slow part of the reset.
        if (resets.isNotEmpty()) SecondaryResultStorage.saveAll(
            context, resets.map { it.cleared },
            onProgress = { n -> if (buildKey != null) appViewModel.updateBuild(buildKey, n) }
        )
        if (clearedCostDelta > 0.0) {
            ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, clearedCostDelta)
        }
        // Build phase complete — release the popup so the UI navigates to the
        // batch screen while the dispatch below keeps running in the background.
        if (buildKey != null) appViewModel.finishBuild(buildKey)
        if (resets.isEmpty()) return
        withTracerTags(reportId = run.reportId, category = cat) {
            runThrottledBatch(
                items = resets,
                hostOf = { AppService.findById(it.pair.providerId)?.let { s -> providerHost(s) } },
                subCap = ApiCallCaps.fanOut,
                register = { r, d ->
                    registerItemJob(r.pair.id, d)
                }
            ) { r ->
                runOnePair(
                    context, runKey, r.pair.id, r.pair.answererAgentId, r.pair.sourceAgentId,
                    r.pair.providerId, r.pair.model, run.metaPrompt, report, aiSettings,
                    sourceCount = sourceCount,
                    question = question, sourceBody = r.body,
                    targetLanguage = run.sourceLanguage,
                    targetLanguageNative = langCtx?.native,
                    paramsIds = r.cleared.secondaryParameterPresetIds.orEmpty(),
                    systemPromptId = r.cleared.secondarySystemPromptId,
                    placeholder = r.cleared
                )
            }
        }
    }

    /** Cancel one pair's in-flight coroutine + delete its disk row +
     *  drop it from the state flow. */
    fun cancelPair(context: Context, runKey: FanOutRunKey, pairKey: PairKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val pair = run.pairs[pairKey] ?: return@launch
            itemJobOf(pair.id)?.cancelAndJoin()
            // The L3 delete dialog promises "The API cost stays counted in
            // the report total" — roll the pair's disk-truth spend
            // (response + title + icon) into costsFromDeletedItems like
            // every sibling delete path; this was the one that didn't.
            val costDelta = SecondaryResultStorage.get(context, run.reportId, pair.id)?.fullCost()
                ?: pair.totalCost
            SecondaryResultStorage.delete(context, run.reportId, pair.id)
            ReportStorage.removeFanOutIconCalls(context, run.reportId, setOf(pair.id))
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            dropPair(runKey, pairKey)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    // -----------------------------------------------------------------
    // Run-level: complete rerun + delete-run + delete-model-from-run
    // -----------------------------------------------------------------

    /** Cancel any in-flight outer job + per-pair coroutines, delete
     *  every persisted pair row, then start a fresh run with the same
     *  scope + responder set. The deleted pairs' spend is rolled into
     *  the report's Deleted-items tally so the lifetime cost view stays
     *  whole. Combined-report rows are left alone (no explicit
     *  fan-out↔fan-in link). */
    fun rerunComplete(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            runJobOf(runKey)?.cancelAndJoin()
            run.pairs.values.forEach { pair -> itemJobOf(pair.id)?.cancelAndJoin() }
            // Disk-truth fullCost (in/out + Fan-Meta icon + title) so a pair
            // that settled while we were cancelling still rolls its spend in;
            // the in-memory totalCost is the fallback for a missing row.
            val costDelta = run.pairs.values.sumOf {
                SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
            }
            val pairIds = run.pairs.values.map { it.id }.toSet()
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.delete(context, run.reportId, pair.id)
            }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, pairIds)
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            // Reconstruct the responder subset from the run's own pairs when
            // it wasn't persisted: run.responderIds is in-memory only and
            // hydrate() nulls it, so a Rerun-complete after a report reopen
            // (or any re-hydrate) fell back to ALL successful answerers and
            // ballooned a hand-picked 3-of-12 run into the full 12×11 matrix.
            // The distinct answerers of the existing pairs ARE that subset.
            val responderIds = run.responderIds
                ?: run.pairs.values.map { it.answererAgentId }.toSet().takeIf { it.isNotEmpty() }
            dropRun(runKey)
            // Re-fire through the engine's own launch path, reproducing the
            // original run's scope + responder set.
            startRun(context, run.reportId, run.metaPrompt, run.scope, responderIds, run.sourceLanguage)
        }

    /** Drop every pair row in the run + the run itself. Combined-
     *  reports for the prompt are also dropped (this is the title-
     *  bar 🗑 — the user wants the whole run gone). Cancels the outer
     *  batch Job + every per-pair coroutine before the disk deletes so
     *  no zombie write lands on a just-deleted row. */
    /** Delete an entire fan-out run. Returns immediately after the cheap,
     *  user-visible part (stop the batch, hide the row, drop the in-memory
     *  run); the expensive per-pair disk deletes for a big run run in the
     *  background so the UI can navigate away at once. The returned Job
     *  completes when the background disk work is done. */
    fun deleteRun(context: Context, runKey: FanOutRunKey): Job {
        val run = _runs.value[runKey]
            ?: return appViewModel.viewModelScope.launch { }
        // Capture the live coroutines before the deferred delete drops the run.
        val runJob = runJobOf(runKey)
        val pairJobs = run.pairs.values.mapNotNull { itemJobOf(it.id) }
        return deleteRunDeferred(appViewModel.viewModelScope, runKey, runJob, pairJobs) {
            // Roll the whole run's spend into the report's Deleted-items tally
            // before the disk deletes — disk-truth fullCost per pair (in/out +
            // Fan-Meta icon + title; the run was dropped from _runs before the
            // join, so a pair that settled during the cancel never mirrored
            // into this snapshot) plus each combined fan-in row. Without this,
            // trashing an entire fan-out run erased all of its API spend from
            // the lifetime cost view.
            val costDelta = run.pairs.values.sumOf {
                SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
            } + run.combinedReports.sumOf { cr ->
                SecondaryResultStorage.get(context, run.reportId, cr.id)?.fullCost() ?: cr.totalCost
            }
            val pairIds = run.pairs.values.map { it.id }.toSet()
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.delete(context, run.reportId, pair.id)
            }
            run.combinedReports.forEach { cr ->
                SecondaryResultStorage.delete(context, run.reportId, cr.id)
            }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, pairIds)
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }
    }

    /** Clear the Fan Meta for this run — wipes each pair's title AND
     *  icon (text / tier / error / token / cost) and drops the fan-out
     *  entries from the report's iconCalls audit log, leaving the
     *  fan-out pairs (and their main responses) intact. Backs the
     *  Fan-Meta 🗑 button. */
    fun clearFanMeta(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            // Stop any in-flight fan-meta batch first so a call returning
            // mid-flight doesn't write back onto a row we're about to
            // clear. join() (not just cancel()) is load-bearing: a pair
            // whose HTTP call already returned would otherwise run its
            // write AFTER the clear, leaving a marker the resume sweep
            // treats as "in progress" and relaunches.
            reportViewModel.iconGen.cancelFanMetaBatch(run.reportId, run.metaPrompt.id)?.join()
            // Roll the title + icon spend we're about to wipe into the
            // report's Deleted-items tally so the cost view stays whole.
            val costDelta = run.pairs.values.sumOf {
                it.iconInputCost + it.iconOutputCost + it.titleInputCost + it.titleOutputCost
            }
            val pairIds = run.pairs.values.map { it.id }.toSet()
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.clearFanOutIconState(context, run.reportId, pair.id)
                SecondaryResultStorage.clearFanOutTitleState(context, run.reportId, pair.id)
            }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, pairIds)
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
            // Re-hydrate so the engine's in-memory pairs lose their
            // title+icon too — unlike deleteRun, the run itself stays.
            hydrate(context, run.reportId)
        }

    /** Drop every pair where the given (provider, model) is the
     *  answerer OR the source. Cancels per-pair coroutines first
     *  so no zombie writes land after the delete. */
    fun deleteModelFromRun(
        context: Context,
        runKey: FanOutRunKey,
        providerId: String,
        model: String,
        /** Agent ids for (providerId, model) as they existed BEFORE the
         *  report's matching agents were removed — pass this from a cascade
         *  that already deleted them (re-deriving from disk would find
         *  nothing, since the agents are already gone). Null (the manual
         *  per-pair removal UI) re-derives from the live report, which
         *  still has the agents at that point. */
        knownAgentIds: Set<String>? = null
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val run = _runs.value[runKey] ?: return@launch
        val matchingAgentIds = knownAgentIds ?: run {
            val report = ReportStorage.getReport(context, run.reportId) ?: return@launch
            report.agents
                .filter { it.provider.equals(providerId, ignoreCase = true) && it.model == model }
                .map { it.agentId }
                .toSet()
        }
        val victims = run.pairs.values.filter {
            (it.providerId.equals(providerId, ignoreCase = true) && it.model == model) ||
                (it.sourceAgentId in matchingAgentIds)
        }
        if (victims.isEmpty()) return@launch
        // Cancel the per-pair coroutines first so no zombie write lands
        // after the delete.
        victims.forEach { itemJobOf(it.id)?.cancelAndJoin() }
        val costDelta = victims.sumOf {
            SecondaryResultStorage.get(context, run.reportId, it.id)?.fullCost() ?: it.totalCost
        }
        victims.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
        ReportStorage.removeFanOutIconCalls(context, run.reportId, victims.map { it.id }.toSet())
        _runs.update { runs ->
            val cur = runs[runKey] ?: return@update runs
            val keepKeys = victims.map { it.key }.toSet()
            runs + (runKey to cur.copy(pairs = cur.pairs - keepKeys))
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, run.reportId)
    }

    /** Drop [providerId]/[model] (as answerer OR source agent) from every
     *  fan-out run of [reportId] — the FanOut half of the "Use report models"
     *  unified model removal. Awaits each per-run delete. [agentIds], when
     *  passed, are the matching agent ids as they existed before the caller
     *  removed them from the report (see [deleteModelFromRun]). */
    suspend fun deleteModelFromReport(
        context: Context, reportId: String, providerId: String, model: String,
        agentIds: Set<String>? = null
    ) {
        val keys = _runs.value.filterValues { it.reportId == reportId }.keys.toList()
        keys.forEach { deleteModelFromRun(context, it, providerId, model, agentIds).join() }
    }

    // -----------------------------------------------------------------
    // Resume on report open
    // -----------------------------------------------------------------

    /** Row ids whose per-pair worker Job is live in THIS process. The
     *  read-only broken-work scan unions this across engines so a pair
     *  that's legitimately mid-flight isn't mistaken for an interrupted
     *  placeholder. Empty after a process kill — exactly when every blank
     *  pair really is stale. */
    fun inFlightRowIds(): Set<String> = itemJobIds()

    /** Top-level Fan Out runs currently alive in this process. Covers rows
     *  created during the build stage before their per-pair Job is registered.
     *  Excludes the fan-meta batch jobs (see [registerFanMetaJob]) which share
     *  this run-job registry under a namespaced "|meta|" key. */
    fun activeRunKeys(): Set<FanOutRunKey> =
        activeRunJobKeys().filterNot { it.contains("|meta|") }.toSet()

    // ===== Fan-meta batch job (the title/icon pass over this run's pairs) =====
    //
    // Fan Meta has no run-state of its own — it decorates the fan-out pairs in
    // place. Its single per-(report, metaPrompt) batch job lives in the shared
    // BatchEngine run-job registry under a namespaced key so it reuses the base
    // register/auto-clean/cancel machinery without colliding with the fan-out
    // response run job ("$reportId|$metaPromptId" vs "$reportId|meta|…"). The
    // namespaced key is filtered out of [activeRunKeys] and
    // [joinActiveRunsForReport] so the broken-work scan and fan-in don't see it;
    // [cancelAllForReport] intentionally DOES cancel it (report delete).

    private fun fanMetaRunKey(reportId: String, metaPromptId: String): FanOutRunKey =
        "$reportId|meta|$metaPromptId"

    /** Register the fan-meta batch job; supersedes any prior job for the same
     *  (report, metaPrompt) and self-cleans on completion. */
    fun registerFanMetaJob(reportId: String, metaPromptId: String, job: Job) =
        registerRunJob(fanMetaRunKey(reportId, metaPromptId), job)

    /** The live fan-meta batch job for this (report, metaPrompt), or null. */
    fun fanMetaJobOf(reportId: String, metaPromptId: String): Job? =
        runJobOf(fanMetaRunKey(reportId, metaPromptId))

    /** Fan-meta batches alive in this process for [reportId], mapped back to the
     *  fan-out run-key form ("$reportId|$metaPromptId") the broken-work scan
     *  uses to exclude in-flight fan-meta from the stranded/errored tally. */
    fun activeFanMetaRunKeys(reportId: String): Set<String> {
        val marker = "$reportId|meta|"
        return activeRunJobKeys()
            .filter { it.startsWith(marker) }
            .map { "$reportId|${it.removePrefix(marker)}" }
            .toSet()
    }

    /** Resume every stale fan-out pair (a blank placeholder on disk with
     *  no live per-pair Job) across every run on this report — the
     *  app-kill recovery path. Called by the report-open + 30 s
     *  background orchestrators. Idempotent per (runKey) via
     *  the shared resume-scan dedup. Bounds re-dispatch via [BatchResume] (a pair that
     *  can never complete is terminalized after MAX_ATTEMPTS instead of
     *  re-billed every cycle), and terminalizes rows the flow can't
     *  locate (prompt deleted / answerer agent gone) so they stop
     *  spinning. */
    fun resumeStaleRunsForReport(context: Context, reportId: String, resetAttempts: Boolean = false): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
          // Fire-and-forget on viewModelScope with no global exception
          // handler — an uncaught throw here crashes the app (the startup
          // resume sweep only join()s this Job, it can't catch it). Contain.
          try {
            hydrate(context, reportId)
            val diskStale = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.fanOutSourceAgentId != null && it.fanInOf == null &&
                        it.content.isNullOrBlank() && it.errorMessage == null &&
                        it.durationMs == null && !hasItemJob(it.id)
                }
            if (diskStale.isEmpty()) return@launch
            val diskById = diskStale.associateBy { it.id }
            val runsForReport = _runs.value.filterKeys { it.startsWith("$reportId|") }
            // Locate each stale disk row in the hydrated flow.
            val dispatchable = mutableMapOf<FanOutRunKey, MutableList<PairKey>>()
            val locatedIds = mutableSetOf<String>()
            for ((rk, run) in runsForReport) {
                for (p in run.pairs.values) {
                    if (p.status == PairStatus.PENDING && p.id in diskById) {
                        dispatchable.getOrPut(rk) { mutableListOf() }.add(p.key)
                        locatedIds.add(p.id)
                    }
                }
            }
            for ((rk, pairKeys) in dispatchable) {
                if (!beginResumeScan(rk)) continue
                val run = runsForReport[rk]
                if (run == null) { endResumeScan(rk); continue }
                val rows = pairKeys.mapNotNull { pk -> run.pairs[pk]?.id?.let { diskById[it] } }
                // Explicit user Regenerate → wipe any retry counts the 30s
                // sweep ran up, so these pairs get a fresh budget instead of
                // being terminalized on the spot.
                if (resetAttempts) BatchResume.resetAttempts(rows.map { it.id })
                val retryRows = BatchResume.capForRetry(rows) { row ->
                    markRowInterrupted(context, reportId, row.id,
                        "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts")
                    run.pairs.values.firstOrNull { it.id == row.id }?.let { p ->
                        transitionPair(rk, p.key) {
                            it.copy(status = PairStatus.ERROR,
                                errorMessage = "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts",
                                durationMs = 0)
                        }
                    }
                }
                val retryKeys = retryRows.mapNotNull { row -> run.pairs.values.firstOrNull { it.id == row.id }?.key }
                if (retryKeys.isEmpty()) { endResumeScan(rk); continue }
                appViewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rerunPairsBlocking(context, rk, retryKeys)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("FanOut", "rerun pairs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        endResumeScan(rk)
                    }
                }
            }
            // Stale rows the flow can't place (prompt deleted, or the
            // answerer agent was removed so hydrate dropped the pair).
            diskStale.filter { it.id !in locatedIds }.forEach {
                markRowInterrupted(context, reportId, it.id, "Interrupted — fan-out can't be resumed")
            }
          } catch (e: kotlinx.coroutines.CancellationException) {
              throw e
          } catch (e: Exception) {
              AppLog.w("FanOut", "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
          }
        }

    // -----------------------------------------------------------------
    // Fan-in passthrough — delegates to ReportViewModel.
    // -----------------------------------------------------------------

    /** Standard fan-in: combine the whole run via the picked prompt.
     *  Currently delegates to the existing
     *  [ReportViewModel.runFanInPrompt] which already writes a
     *  combined-report row to disk; the next [hydrate] picks it up
     *  into [FanOutRunState.combinedReports]. */
    fun runFanIn(
        context: Context,
        runKey: FanOutRunKey,
        fanInPrompt: InternalPrompt
    ): Job? {
        val run = _runs.value[runKey] ?: return null
        val job = reportViewModel.secondary.runFanInPrompt(context, run.reportId, fanInPrompt, run.sourceLanguage)
        // Re-hydrate after the call completes to surface the new combined-report row.
        job?.invokeOnCompletion {
            appViewModel.viewModelScope.launch(Dispatchers.IO) { hydrate(context, run.reportId) }
        }
        return job
    }
}
