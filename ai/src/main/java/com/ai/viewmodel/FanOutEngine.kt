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
import com.ai.data.ReportAgent
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
                val answererAgentId = agentsById.values.firstOrNull {
                    it.provider.equals(row.providerId, ignoreCase = true) && it.model == row.model
                }?.agentId ?: continue
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

    /** Launch a brand-new fan-out run. The single canonical launch
     *  path (replaces the legacy `SecondaryRunManager.runFanOutPrompt`):
     *  resolves the scope + responder set, pre-creates every
     *  (answerer, source) placeholder row on disk, publishes the
     *  [FanOutRunState] with all pairs PENDING (so the UI's stats read
     *  the full expected work immediately), then runs each pair through
     *  the shared throttled batch + [runOnePair]. The per-pair coroutine
     *  Job lands in [pairJobs] (cancel/delete target it) and the outer
     *  batch Job in [runJobs] (rerunComplete / deleteRun join it).
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
        systemPromptId: String? = null
    ): Job? {
        val rk = runKey(reportId, metaPrompt.id)
        runJobs[rk]?.let { if (it.isActive) return it }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val fanOutStartMs = System.currentTimeMillis()
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
                    AppLog.i("FanOut", "→ start \"${metaPrompt.name}\" (report=$reportId, ${successful.size} successful agents)")
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
                    for (answerer in answerers) {
                        val provider = AppService.findById(answerer.provider) ?: continue
                        for (source in sources) {
                            if (source.agentId == answerer.agentId) continue
                            val agentName = "${provider.id} / ${shortModelName(answerer.model)}$langSuffix"
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.META, provider.id, answerer.model, agentName
                            ) {
                                it.copy(
                                    metaPromptId = metaPrompt.id,
                                    metaPromptName = metaPrompt.name,
                                    fanOutSourceAgentId = source.agentId,
                                    runId = runId,
                                    targetLanguage = sourceLanguage,
                                    targetLanguageNative = langCtx?.native,
                                    secondaryScope = scopeEncoded
                                )
                            }
                            pending.add(PendingPair(answerer, source, placeholder))
                            placeholder.toPairState(answerer.agentId)?.let { newPairs[it.key] = it }
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
                            pairJobs[item.placeholder.id] = d
                            d.invokeOnCompletion { pairJobs.remove(item.placeholder.id, d) }
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
                    AppLog.i("FanOut", "← end \"${metaPrompt.name}\" (${pending.size} pairs in ${System.currentTimeMillis() - fanOutStartMs}ms)")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverPairs(context, reportId, metaPrompt.id)
            }
        }
        runJobs[rk] = job
        job.invokeOnCompletion { runJobs.remove(rk, job) }
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
                        !pairJobs.containsKey(it.id)
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
        runJobs.filterKeys { it.startsWith(prefix) }.values.forEach { it.cancel() }
        _runs.value.filterKeys { it.startsWith(prefix) }
            .values.flatMap { it.pairs.values.map { p -> p.id } }
            .forEach { pairJobs[it]?.cancel() }
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
        runJobs.filterKeys { it.startsWith(prefix) }.values.forEach { it.join() }
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

    /** Re-fire every errored pair in this run, in one parallel batch. */
    fun restartFailedPairs(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            val keys = run.pairs.values.filter { it.status == PairStatus.ERROR }.map { it.key }
            rerunPairsBlocking(context, runKey, keys)
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
     *  its flow status reset; the per-pair Jobs land in [pairJobs] so
     *  cancel/delete can target them. Drives the restart-failed +
     *  single-pair rerun + app-kill resume paths. */
    private suspend fun rerunPairsBlocking(
        context: Context,
        runKey: FanOutRunKey,
        pairKeys: List<PairKey>
    ) {
        if (pairKeys.isEmpty()) return
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
        for (pk in pairKeys) {
            val pair = run.pairs[pk] ?: continue
            val source = report.agents.firstOrNull { it.agentId == pair.sourceAgentId } ?: continue
            val cleared = SecondaryResultStorage.get(context, run.reportId, pair.id)?.copy(
                content = null, errorMessage = null, inputCost = null, outputCost = null,
                durationMs = null, tokenUsage = null, timestamp = System.currentTimeMillis()
            ) ?: continue
            SecondaryResultStorage.save(context, cleared)
            transitionPair(runKey, pk) {
                it.copy(
                    status = PairStatus.PENDING, content = null, errorMessage = null,
                    inputCost = null, outputCost = null, durationMs = null,
                    tokenUsage = null, timestamp = cleared.timestamp
                )
            }
            val body = langCtx?.bodies?.get(pair.sourceAgentId) ?: source.responseBody.orEmpty()
            resets.add(Reset(pair, cleared, body))
        }
        if (resets.isEmpty()) return
        withTracerTags(reportId = run.reportId, category = cat) {
            runThrottledBatch(
                items = resets,
                hostOf = { AppService.findById(it.pair.providerId)?.let { s -> providerHost(s) } },
                subCap = ApiCallCaps.fanOut,
                register = { r, d ->
                    pairJobs[r.pair.id] = d
                    d.invokeOnCompletion { pairJobs.remove(r.pair.id, d) }
                }
            ) { r ->
                runOnePair(
                    context, runKey, r.pair.id, r.pair.answererAgentId, r.pair.sourceAgentId,
                    r.pair.providerId, r.pair.model, run.metaPrompt, report, aiSettings,
                    sourceCount = sourceCount,
                    question = question, sourceBody = r.body,
                    targetLanguage = run.sourceLanguage,
                    targetLanguageNative = langCtx?.native,
                    paramsIds = emptyList(), systemPromptId = null,
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
     *  scope + responder set. The deleted pairs' spend is rolled into
     *  the report's Deleted-items tally so the lifetime cost view stays
     *  whole. Combined-report rows are left alone (no explicit
     *  fan-out↔fan-in link). */
    fun rerunComplete(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            runJobs[runKey]?.cancelAndJoin()
            run.pairs.values.forEach { pair -> pairJobs[pair.id]?.cancelAndJoin() }
            val costDelta = run.pairs.values.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.delete(context, run.reportId, pair.id)
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, run.reportId, costDelta)
            dropRun(runKey)
            // Re-fire through the engine's own launch path, reproducing the
            // original run's scope + responder set.
            startRun(context, run.reportId, run.metaPrompt, run.scope, run.responderIds, run.sourceLanguage)
        }

    /** Drop every pair row in the run + the run itself. Combined-
     *  reports for the prompt are also dropped (this is the title-
     *  bar 🗑 — the user wants the whole run gone). Cancels the outer
     *  batch Job + every per-pair coroutine before the disk deletes so
     *  no zombie write lands on a just-deleted row. */
    fun deleteRun(context: Context, runKey: FanOutRunKey): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[runKey] ?: return@launch
            runJobs[runKey]?.cancelAndJoin()
            run.pairs.values.forEach { pair -> pairJobs[pair.id]?.cancelAndJoin() }
            // Now disk deletes — safe because no coroutine can still
            // be heading toward a saveIfStillPresent against these ids.
            run.pairs.values.forEach { pair ->
                SecondaryResultStorage.delete(context, run.reportId, pair.id)
            }
            run.combinedReports.forEach { cr ->
                SecondaryResultStorage.delete(context, run.reportId, cr.id)
            }
            dropRun(runKey)
            ReportStorage.bumpReportTimestamp(context, run.reportId)
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
        // Cancel the per-pair coroutines first so no zombie write lands
        // after the delete.
        victims.forEach { pairJobs[it.id]?.cancelAndJoin() }
        val costDelta = victims.sumOf { it.totalCost }
        victims.forEach { SecondaryResultStorage.delete(context, run.reportId, it.id) }
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

    /** Resume every stale fan-out pair (a blank placeholder on disk with
     *  no live per-pair Job) across every run on this report — the
     *  app-kill recovery path. Called by the report-open + 30 s
     *  background orchestrators. Idempotent per (runKey) via
     *  [resumeScans]. Bounds re-dispatch via [BatchResume] (a pair that
     *  can never complete is terminalized after MAX_ATTEMPTS instead of
     *  re-billed every cycle), and terminalizes rows the flow can't
     *  locate (prompt deleted / answerer agent gone) so they stop
     *  spinning. */
    fun resumeStaleRunsForReport(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            hydrate(context, reportId)
            val diskStale = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    it.fanOutSourceAgentId != null && it.fanInOf == null &&
                        it.content.isNullOrBlank() && it.errorMessage == null &&
                        it.durationMs == null && !pairJobs.containsKey(it.id)
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
                if (!resumeScans.add(rk)) continue
                val run = runsForReport[rk]
                if (run == null) { resumeScans.remove(rk); continue }
                val rows = pairKeys.mapNotNull { pk -> run.pairs[pk]?.id?.let { diskById[it] } }
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
                if (retryKeys.isEmpty()) { resumeScans.remove(rk); continue }
                appViewModel.viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rerunPairsBlocking(context, rk, retryKeys)
                    } finally {
                        resumeScans.remove(rk)
                    }
                }
            }
            // Stale rows the flow can't place (prompt deleted, or the
            // answerer agent was removed so hydrate dropped the pair).
            diskStale.filter { it.id !in locatedIds }.forEach {
                markRowInterrupted(context, reportId, it.id, "Interrupted — fan-out can't be resumed")
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
        val job = reportViewModel.secondary.runFanInPrompt(context, run.reportId, fanInPrompt, pick, run.sourceLanguage)
        // Re-hydrate after the call completes to surface the new combined-report row.
        job?.invokeOnCompletion {
            appViewModel.viewModelScope.launch(Dispatchers.IO) { hydrate(context, run.reportId) }
        }
        return job
    }
}
