package com.ai.viewmodel

import android.content.Context
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.CombinedReportState
import com.ai.data.FanOutRunKey
import com.ai.data.FanOutRunState
import com.ai.data.PairKey
import com.ai.data.PairState
import com.ai.data.PairStatus
import com.ai.data.ProviderThrottle
import com.ai.data.ModelCooldownStore
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.pairKey
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.runKey
import com.ai.data.toCombinedReportState
import com.ai.data.toPairState
import com.ai.data.withTracerTags
import com.ai.model.InternalPrompt
import com.ai.ui.shared.shortModelName
import androidx.lifecycle.viewModelScope
import com.ai.model.Settings
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

/**
 * Authoritative runtime owner for every Fan Out run on every
 * report. Publishes a single [StateFlow] keyed by [FanOutRunKey]
 * that the UI subscribes to; every state transition (pair queued,
 * permit acquired, HTTP completed, error stamped, row deleted) is
 * an atomic update to that flow.
 *
 * Phase C scope: engine compiles + builds the StateFlow + owns
 * the runner; the existing UI does NOT yet read from it. Phases
 * D / E switch the UI and report-result page over to the engine
 * and delete the duplicate code in ReportViewModel.
 *
 * The engine delegates the actual HTTP call + disk persistence
 * to [ReportViewModel.executeSecondaryTask] — that function
 * already handles every cost / token / error path correctly, so
 * the engine just brackets it with state-flow transitions plus
 * per-pair Job bookkeeping.
 *
 * Co-existence rules during the transition window:
 * - The engine maintains its own `pairJobs` and `runJobs` maps,
 *   independent of the existing [ReportViewModel] maps (which
 *   stay alive until Phase E deletes them).
 * - The engine hydrates from disk on demand via [hydrate]; the
 *   UI's `LaunchedEffect(currentReportId)` calls it on report
 *   open so the flow is populated before any drill-in.
 */
class FanOutEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) {
    private val _runs = MutableStateFlow<Map<FanOutRunKey, FanOutRunState>>(emptyMap())
    val runs: StateFlow<Map<FanOutRunKey, FanOutRunState>> = _runs.asStateFlow()

    /** Per-pair coroutines, keyed by [PairState.id] (= on-disk
     *  SecondaryResult id). Registered before the coroutine starts
     *  via [CoroutineStart.LAZY] so concurrent deletes can always
     *  find the Job to cancel. */
    private val pairJobs = ConcurrentHashMap<String, Job>()

    /** Top-level batch Job per [FanOutRunKey]. Used by
     *  [rerunComplete] / [deleteRun] to cancelAndJoin a whole
     *  batch atomically. */
    private val runJobs = ConcurrentHashMap<FanOutRunKey, Job>()

    /** Per-run dedup for resume scans — same role as the old
     *  `staleResumeScans` set but scoped to this engine's
     *  lifecycle. Key released only after the dispatched rerun
     *  Job actually completes. */
    private val resumeScans = ConcurrentHashMap.newKeySet<FanOutRunKey>()

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

        val agentsById = report.agents.associateBy { it.agentId }

        val newRuns = mutableMapOf<FanOutRunKey, FanOutRunState>()
        for ((metaPromptId, rows) in pairRowsByPrompt) {
            val prompt = aiSettings.internalPrompts.firstOrNull { it.id == metaPromptId }
                ?: continue
            val key = runKey(reportId, metaPromptId)

            // Build PairState map. For each row we need the answerer
            // agent id (not stored on the row — derive from the agent
            // whose (provider, model) matches). Multi-agent swarm
            // members with identical (provider, model) all match the
            // same answerer key set; arbitrarily pick the first.
            val pairs = mutableMapOf<PairKey, PairState>()
            for (row in rows) {
                val answererAgentId = agentsById.values.firstOrNull {
                    it.provider.equals(row.providerId, ignoreCase = true) && it.model == row.model
                }?.agentId ?: continue
                val pair = row.toPairState(answererAgentId) ?: continue
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
    private fun transitionPair(
        runKey: FanOutRunKey,
        pairKey: PairKey,
        update: (PairState) -> PairState
    ) {
        _runs.update { runs ->
            val run = runs[runKey] ?: return@update runs
            val cur = run.pairs[pairKey] ?: return@update runs
            val next = update(cur)
            if (next == cur) runs
            else runs + (runKey to run.copy(pairs = run.pairs + (pairKey to next)))
        }
    }

    /** Drop a pair from a run (used by removeFailedPairs / delete-
     *  model paths). */
    private fun dropPair(runKey: FanOutRunKey, pairKey: PairKey) {
        _runs.update { runs ->
            val run = runs[runKey] ?: return@update runs
            if (pairKey !in run.pairs) runs
            else runs + (runKey to run.copy(pairs = run.pairs - pairKey))
        }
    }

    /** Drop an entire run from the flow (delete-run path). */
    private fun dropRun(runKey: FanOutRunKey) {
        _runs.update { it - runKey }
    }

    // -----------------------------------------------------------------
    // Run launch
    // -----------------------------------------------------------------

    /** Pre-create placeholder SecondaryResult rows on disk + the
     *  in-memory [FanOutRunState] + launch one pair coroutine per
     *  (answerer, source) pair. Returns the [FanOutRunKey] so the
     *  caller can drill into the run's detail screen immediately —
     *  the StateFlow will fill in as placeholders land + complete. */
    fun startRun(
        context: Context,
        reportId: String,
        metaPrompt: InternalPrompt,
        scope: SecondaryScope = SecondaryScope.AllReports,
        responderAgentIds: Set<String>? = null
    ): FanOutRunKey? {
        val key = runKey(reportId, metaPrompt.id)

        // Dedupe: if a run for this key is already active, return its
        // key without launching a parallel batch.
        runJobs[key]?.let { existing ->
            if (existing.isActive) return key
        }

        AppLog.i("FanOut", "→ engine.startRun \"${metaPrompt.name}\" report=$reportId")
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val outer = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val cat = "Report meta: ${metaPrompt.name}"
            try {
                withTracerTags(reportId = reportId, category = cat) {
                    val state = appViewModel.uiState.value
                    val aiSettings = state.aiSettings
                    val report = ReportStorage.getReport(context, reportId)
                        ?: return@withTracerTags
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    if (successful.size < 2) return@withTracerTags

                    // Apply scope to derive the SOURCE agent set.
                    val sources = when (scope) {
                        SecondaryScope.AllReports -> successful
                        is SecondaryScope.TopRanked -> {
                            val rerank = SecondaryResultStorage.get(context, reportId, scope.rerankResultId)
                            val topIds = com.ai.data.extractTopRankedIds(rerank?.content, scope.count)
                            if (topIds.isNullOrEmpty()) successful
                            else topIds.mapNotNull { idx -> successful.getOrNull(idx - 1) }
                        }
                        is SecondaryScope.Manual -> successful.filter { it.agentId in scope.agentIds }
                    }
                    if (sources.isEmpty()) return@withTracerTags

                    val answerers = if (responderAgentIds == null) successful
                        else successful.filter { it.agentId in responderAgentIds }
                    if (answerers.isEmpty()) return@withTracerTags

                    // Pre-stage placeholder SecondaryResult rows + PairState
                    // entries. The state-flow gets one batched update at
                    // the end of the loop so subscribers see N pairs
                    // appear together rather than N individual notifications.
                    data class Pending(val answerer: com.ai.data.ReportAgent, val source: com.ai.data.ReportAgent, val placeholder: SecondaryResult)
                    val pending = mutableListOf<Pending>()
                    val newPairs = LinkedHashMap<PairKey, PairState>()
                    for (answerer in answerers) {
                        val provider = AppService.findById(answerer.provider) ?: continue
                        for (source in sources) {
                            if (source.agentId == answerer.agentId) continue
                            val agentName = "${provider.id} / ${shortModelName(answerer.model)}"
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.META, provider.id, answerer.model, agentName
                            ) {
                                it.copy(
                                    metaPromptId = metaPrompt.id,
                                    metaPromptName = metaPrompt.name,
                                    fanOutSourceAgentId = source.agentId,
                                    secondaryScope = scope.encode()
                                )
                            }
                            pending.add(Pending(answerer, source, placeholder))
                            val pk = pairKey(answerer.agentId, source.agentId)
                            newPairs[pk] = PairState(
                                id = placeholder.id,
                                answererAgentId = answerer.agentId,
                                sourceAgentId = source.agentId,
                                providerId = provider.id,
                                model = answerer.model,
                                status = PairStatus.PENDING,
                                timestamp = placeholder.timestamp
                            )
                        }
                    }

                    // Publish the run with every pair as PENDING.
                    _runs.update { runs ->
                        runs + (key to FanOutRunState(
                            key = key,
                            reportId = reportId,
                            metaPrompt = metaPrompt,
                            scope = scope,
                            responderIds = responderAgentIds,
                            pairs = newPairs,
                            combinedReports = runs[key]?.combinedReports.orEmpty()
                        ))
                    }

                    // Concurrent in-flight pair cap — see commit 865fb443
                    // for why this is necessary to avoid Dispatchers.IO
                    // starvation. The blocking ProviderThrottle.acquire
                    // is gated by the suspending Semaphore here.
                    val ioCap = Semaphore(8)

                    coroutineScope {
                        pending.map { item ->
                            val deferred = async(start = CoroutineStart.LAZY) {
                                runOnePair(
                                    context, key, item.placeholder.id, item.answerer.agentId,
                                    item.source.agentId, item.answerer.provider, item.answerer.model,
                                    metaPrompt, report, aiSettings, sources.size,
                                    sourceResponseBody = item.source.responseBody.orEmpty(),
                                    placeholder = item.placeholder,
                                    ioCap = ioCap
                                )
                            }
                            pairJobs[item.placeholder.id] = deferred
                            deferred.invokeOnCompletion {
                                pairJobs.remove(item.placeholder.id, deferred)
                            }
                            deferred.start()
                            deferred
                        }.awaitAll()
                    }
                    AppLog.i("FanOut", "← engine.startRun done \"${metaPrompt.name}\" (${pending.size} pairs)")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                runJobs.remove(key)
            }
        }
        runJobs[key] = outer
        return key
    }

    // -----------------------------------------------------------------
    // Per-pair runner — single canonical path
    // -----------------------------------------------------------------

    /** PENDING → RUNNING → DONE/ERROR. Owns the per-pair throttle
     *  permit acquisition + the HTTP call (via [ReportViewModel
     *  .executeSecondaryTask]) + the state-flow transitions. */
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
        sourceResponseBody: String,
        placeholder: SecondaryResult,
        ioCap: Semaphore
    ) {
        val pk = pairKey(answererAgentId, sourceAgentId)
        val provider = AppService.findById(answererProviderId) ?: run {
            transitionPair(runKey, pk) {
                it.copy(status = PairStatus.ERROR, errorMessage = "Provider $answererProviderId not registered")
            }
            return
        }
        ApiCallCaps.global.withPermit {
        ApiCallCaps.fanOut.withPermit {
        ioCap.withPermit {
            val host = providerHost(provider)
            AppLog.d("FanOut", "queued pair ans=$answererAgentId src=$sourceAgentId ${provider.id}/$answererModel")
            // Non-blocking gate — a capped host yields the coroutine
            // (delay, not Thread.sleep) so other pairs proceed.
            val releaser = acquireOrRequeue(host)
            try {
                if (!SecondaryResultStorage.exists(context, runKey.substringBefore('|'), placeholderId)) {
                    AppLog.d("FanOut", "skip pair $placeholderId — deleted before launch")
                    dropPair(runKey, pk)
                    return@withPermit
                }
                withContext(ProviderThrottle.permitPreAcquired.asContextElement(true)) {
                    transitionPair(runKey, pk) { it.copy(status = PairStatus.RUNNING) }
                    val pairStart = System.currentTimeMillis()
                    try {
                        val resolvedBase = resolveSecondaryPrompt(
                            metaPrompt.text,
                            question = report.prompt,
                            results = "",
                            count = sourceCount,
                            title = report.title
                        )
                        val resolved = resolvedBase.replace("@RESPONSE@", sourceResponseBody)
                        // Per-pair 60s ceiling — same cap the
                        // Test-all-models engine uses. Stops a single
                        // runaway model (the Qwen2.5-7B word-salad case
                        // that produced 4096 tokens of nonsense in
                        // ~108s) from pinning its per-host slot for
                        // most of the wall clock. On timeout we persist
                        // an errorMessage so the row counts as ERROR
                        // for the progress bar.
                        try {
                            withTimeout(60_000) {
                                reportViewModel.executeSecondaryTask(
                                    context, report.id, SecondaryKind.META, metaPrompt,
                                    provider, answererModel, resolved, aiSettings, report,
                                    fanOutSourceAgentId = sourceAgentId,
                                    existingPlaceholder = placeholder
                                )
                            }
                        } catch (e: TimeoutCancellationException) {
                            // Stamp the placeholder so the post-call
                            // re-read picks it up as ERROR and the
                            // progress bar advances.
                            val timedOut = (SecondaryResultStorage.get(context, report.id, placeholderId) ?: placeholder)
                                .copy(
                                    errorMessage = "Fan-out pair timed out after 60s",
                                    durationMs = System.currentTimeMillis() - pairStart
                                )
                            SecondaryResultStorage.save(context, timedOut)
                            AppLog.w("FanOut", "pair ans=$answererAgentId src=$sourceAgentId timed out after 60s")
                        }
                        // Re-read the now-persisted row to pick up the
                        // result + cost + tokens stamped by executeSecondaryTask.
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
                                    tokenUsage = saved.tokenUsage
                                )
                            }
                        } else {
                            // Row vanished mid-call (user deleted) — drop
                            // the pair from state.
                            dropPair(runKey, pk)
                        }
                    } finally {
                        AppLog.d("FanOut", "← pair ans=$answererAgentId src=$sourceAgentId ${System.currentTimeMillis() - pairStart}ms")
                    }
                }
            } finally {
                releaser.release()
            }
        }
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
            val costDelta = failed.sumOf { it.totalCost }
            failed.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
            _runs.update { runs ->
                val cur = runs[runKey] ?: return@update runs
                val keepKeys = failed.map { it.key }.toSet()
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
            val costDelta = benched.sumOf { it.totalCost }
            benched.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
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
        val costDelta = failed.sumOf { it.totalCost }
        failed.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
        _runs.update { runs ->
            val cur = runs[runKey] ?: return@update runs
            val keepKeys = failed.map { it.key }.toSet()
            runs + (runKey to cur.copy(pairs = cur.pairs - keepKeys))
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, run.reportId)
    }

    /** Re-fire every errored pair in this run via [rerunPair]. */
    fun restartFailedPairs(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            run.pairs.values.filter { it.status == PairStatus.ERROR }.forEach {
                rerunPairBlocking(context, runKey, it.key)
            }
        }

    /** L2-scoped restart. */
    fun restartFailedPairsForModel(
        context: Context,
        runKey: FanOutRunKey,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val run = _runs.value[runKey] ?: return@launch
        run.pairs.values
            .filter {
                it.status == PairStatus.ERROR &&
                    it.providerId.equals(providerId, ignoreCase = true) &&
                    it.model == model
            }
            .forEach { rerunPairBlocking(context, runKey, it.key) }
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

    private suspend fun rerunPairBlocking(
        context: Context,
        runKey: FanOutRunKey,
        pairKey: PairKey
    ) {
        val run = _runs.value[runKey] ?: return
        val pair = run.pairs[pairKey] ?: return
        // Clear on-disk row to PENDING shape so the runner produces
        // a fresh result (otherwise saveIfStillPresent's content
        // overrides).
        val cleared = SecondaryResultStorage.get(context, run.reportId, pair.id)?.copy(
            content = null,
            errorMessage = null,
            inputCost = null,
            outputCost = null,
            durationMs = null,
            tokenUsage = null,
            timestamp = System.currentTimeMillis()
        ) ?: return
        SecondaryResultStorage.save(context, cleared)
        transitionPair(runKey, pairKey) {
            it.copy(
                status = PairStatus.PENDING,
                content = null,
                errorMessage = null,
                inputCost = null,
                outputCost = null,
                durationMs = null,
                tokenUsage = null,
                timestamp = cleared.timestamp
            )
        }
        val report = ReportStorage.getReport(context, run.reportId) ?: return
        val source = report.agents.firstOrNull { it.agentId == pair.sourceAgentId } ?: return
        val aiSettings = appViewModel.uiState.value.aiSettings
        val cat = "Report meta: ${run.metaPrompt.name}"
        val ioCap = Semaphore(8)
        withTracerTags(reportId = run.reportId, category = cat) {
            runOnePair(
                context, runKey, pair.id, pair.answererAgentId, pair.sourceAgentId,
                pair.providerId, pair.model, run.metaPrompt, report, aiSettings,
                sourceCount = report.agents.count {
                    it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                },
                sourceResponseBody = source.responseBody.orEmpty(),
                placeholder = cleared,
                ioCap = ioCap
            )
        }
    }

    /** Cancel one pair's in-flight coroutine + delete its disk row +
     *  drop it from the state flow. */
    fun cancelPair(context: Context, runKey: FanOutRunKey, pairKey: PairKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val pair = run.pairs[pairKey] ?: return@launch
            pairJobs[pair.id]?.cancelAndJoin()
            SecondaryResultStorage.delete(context, run.reportId, pair.id)
            dropPair(runKey, pairKey)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    // -----------------------------------------------------------------
    // Run-level: complete rerun + delete-run + delete-model-from-run
    // -----------------------------------------------------------------

    /** Cancel any in-flight outer job + per-pair coroutines, delete
     *  every persisted pair row, then start a fresh run with the same
     *  scope + responder set. Combined-report rows are left alone
     *  (legacy: no explicit fan-out↔fan-in link). */
    fun rerunComplete(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            // Cancel both engine and legacy in-flight Jobs (see
            // deleteRun for the parallel-maps rationale).
            runJobs[runKey]?.cancelAndJoin()
            reportViewModel.fanOutJobs[runKey]?.cancelAndJoin()
            run.pairs.values.forEach { pair ->
                pairJobs[pair.id]?.cancelAndJoin()
                reportViewModel.fanOutPairJobs[pair.id]?.cancelAndJoin()
            }
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.delete(context, run.reportId, pair.id)
            }
            val deletedIds = run.pairs.values.map { it.id }.toSet()
            if (deletedIds.isNotEmpty()) {
                appViewModel.updateRunningFanOutPairs { it - deletedIds }
            }
            dropRun(runKey)
            // Re-fire via the legacy launch path so an in-flight
            // launch via runFanOutPrompt and our rerun share the
            // same fanOutJobs dedupe key.
            reportViewModel.runFanOutPrompt(context, run.reportId, run.metaPrompt, run.scope, run.responderIds, run.sourceLanguage)
        }

    /** Drop every pair row in the run + the run itself. Combined-
     *  reports for the prompt are also dropped (this is the title-
     *  bar 🗑 — the user wants the whole run gone).
     *
     *  Cancels in-flight coroutines from BOTH the engine's own Job
     *  maps AND the legacy [ReportViewModel.fanOutJobs] /
     *  [ReportViewModel.fanOutPairJobs] (populated by the still-
     *  active launch path `runFanOutPrompt`). Without consulting
     *  the legacy maps, the engine's own maps are empty (engine
     *  .startRun isn't wired anywhere yet) and the destructive
     *  path completed without actually killing the running API
     *  calls — they kept burning tokens after the row was deleted. */
    fun deleteRun(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            // Cancel the outer batch Job — both engine-owned and the
            // legacy one keyed by the same "$reportId|$metaPromptId"
            // string.
            runJobs[runKey]?.cancelAndJoin()
            reportViewModel.fanOutJobs[runKey]?.cancelAndJoin()
            // Cancel every per-pair coroutine in flight. Engine and
            // legacy maps both key by SecondaryResult id so we look
            // up in both.
            run.pairs.values.forEach { pair ->
                pairJobs[pair.id]?.cancelAndJoin()
                reportViewModel.fanOutPairJobs[pair.id]?.cancelAndJoin()
            }
            // Now disk deletes — safe because no coroutine can still
            // be heading toward a saveIfStillPresent against these ids.
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.delete(context, run.reportId, pair.id)
            }
            run.combinedReports.forEach { cr ->
                SecondaryResultStorage.delete(context, run.reportId, cr.id)
            }
            // Drop from the legacy in-flight set so the report-page
            // summary classifier doesn't think a deleted pair is
            // still running.
            val deletedIds = run.pairs.values.map { it.id }.toSet()
            if (deletedIds.isNotEmpty()) {
                appViewModel.updateRunningFanOutPairs { it - deletedIds }
            }
            dropRun(runKey)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
        }

    /** Clear the fan-icons for this run — wipes each pair's icon /
     *  tier / error / token / cost fields and drops the fan-out
     *  entries from the report's iconCalls audit log, leaving the
     *  fan-out pairs (and their main responses) intact. Backs the
     *  ICONS-mode 🗑 button, where the user wants only the icons
     *  gone, not the fan-out. */
    fun clearFanIcons(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            // Stop any in-flight fan-icons batch first so a tier call
            // mid-flight doesn't write an icon back onto a row we're
            // about to clear.
            reportViewModel.cancelFanIconsBatch(run.reportId, run.metaPrompt.id)
            val pairIds = run.pairs.values.map { it.id }.toSet()
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.clearFanOutIconState(context, run.reportId, pair.id)
            }
            ReportStorage.removeFanOutIconCalls(context, run.reportId, pairIds)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
            // Re-hydrate so the engine's in-memory pairs lose their
            // icons too — unlike deleteRun, the run itself stays.
            hydrate(context, run.reportId)
        }

    /** Drop every pair where the given (provider, model) is the
     *  answerer OR the source. Cancels per-pair coroutines first
     *  so no zombie writes land after the delete. */
    fun deleteModelFromRun(
        context: Context,
        runKey: FanOutRunKey,
        providerId: String,
        model: String
    ): Job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
        val run = _runs.value[runKey] ?: return@launch
        val report = ReportStorage.getReport(context, run.reportId) ?: return@launch
        val matchingAgentIds = report.agents
            .filter { it.provider.equals(providerId, ignoreCase = true) && it.model == model }
            .map { it.agentId }
            .toSet()
        val victims = run.pairs.values.filter {
            (it.providerId.equals(providerId, ignoreCase = true) && it.model == model) ||
                (it.sourceAgentId in matchingAgentIds)
        }
        if (victims.isEmpty()) return@launch
        // Cancel from both engine and legacy per-pair Job maps —
        // see deleteRun for why.
        victims.forEach { pairJobs[it.id]?.cancelAndJoin() }
        victims.forEach { reportViewModel.fanOutPairJobs[it.id]?.cancelAndJoin() }
        val costDelta = victims.sumOf { it.totalCost }
        victims.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
        val victimIds = victims.map { it.id }.toSet()
        if (victimIds.isNotEmpty()) {
            appViewModel.updateRunningFanOutPairs { it - victimIds }
        }
        _runs.update { runs ->
            val cur = runs[runKey] ?: return@update runs
            val keepKeys = victims.map { it.key }.toSet()
            runs + (runKey to cur.copy(pairs = cur.pairs - keepKeys))
        }
        if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
        ReportStorage.bumpReportTimestamp(context, run.reportId)
    }

    // -----------------------------------------------------------------
    // Resume on report open
    // -----------------------------------------------------------------

    /** Resume every stale pair (PENDING with no disk progress) across
     *  every run on this report. Caller is the report-open
     *  orchestrator. Idempotent per (runKey) via [resumeScans]. */
    fun resumeStaleRunsForReport(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            hydrate(context, reportId)
            val runsForReport = _runs.value.filterKeys { it.startsWith("$reportId|") }
            for ((runKey, run) in runsForReport) {
                if (!resumeScans.add(runKey)) continue
                val staleKeys = run.pairs.values
                    .filter { it.status == PairStatus.PENDING && !pairJobs.containsKey(it.id) }
                    .map { it.key }
                if (staleKeys.isEmpty()) {
                    resumeScans.remove(runKey)
                    continue
                }
                appViewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        staleKeys.forEach { pk -> rerunPairBlocking(context, runKey, pk) }
                    } finally {
                        resumeScans.remove(runKey)
                    }
                }
            }
        }

    // -----------------------------------------------------------------
    // Fan-in passthrough — delegate to ReportViewModel for Phase C.
    // Phase E inlines if useful.
    // -----------------------------------------------------------------

    /** Standard fan-in: combine the whole run via the picked prompt.
     *  Currently delegates to the existing
     *  [ReportViewModel.runFanInPrompt] which already writes a
     *  combined-report row to disk; the next [hydrate] picks it up
     *  into [FanOutRunState.combinedReports]. */
    fun runFanIn(
        context: Context,
        runKey: FanOutRunKey,
        fanInPrompt: InternalPrompt,
        pick: Pair<AppService, String>
    ): Job? {
        val run = _runs.value[runKey] ?: return null
        val job = reportViewModel.runFanInPrompt(context, run.reportId, fanInPrompt, pick, run.sourceLanguage)
        // Re-hydrate after the call completes to surface the new combined-report row.
        job?.invokeOnCompletion {
            appViewModel.viewModelScope.launch(Dispatchers.IO) { hydrate(context, run.reportId) }
        }
        return job
    }

    /** Per-model fan-in: combine the L2 active model's view via the
     *  picked prompt. Delegates as above. */
    fun runModelFanIn(
        context: Context,
        runKey: FanOutRunKey,
        providerId: String,
        model: String,
        fanInPrompt: InternalPrompt,
        pick: Pair<AppService, String>
    ): Job? {
        val run = _runs.value[runKey] ?: return null
        val job = reportViewModel.runModelFanInPrompt(context, run.reportId, fanInPrompt, pick, providerId, model, run.sourceLanguage)
        job?.invokeOnCompletion {
            appViewModel.viewModelScope.launch(Dispatchers.IO) { hydrate(context, run.reportId) }
        }
        return job
    }
}
