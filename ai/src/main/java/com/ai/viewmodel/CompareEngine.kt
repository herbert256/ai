package com.ai.viewmodel

import android.content.Context
import com.ai.data.ApiCallCaps
import com.ai.data.AppLog
import com.ai.data.AuditLog
import com.ai.data.COMPARE_PENDING_MODEL
import com.ai.data.COMPARE_PENDING_PROVIDER
import com.ai.data.CompareCellState
import com.ai.data.CompareCellStatus
import com.ai.data.CompareRunKey
import com.ai.data.CompareRunState
import com.ai.data.PricingCache
import com.ai.data.ProviderThrottle
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.compareCellKey
import com.ai.data.fullCost
import com.ai.data.parseSimilarityScore
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.stripMetaReferenceLegend
import com.ai.data.toCompareCellState
import com.ai.data.withTracerTags
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative runtime owner for the "Compare with meta" run on every report.
 *
 * The user picks a set of meta results and a comparison prompt; this engine
 * scores how closely EACH report answer matches EACH chosen meta result, as a
 * percentage 0..100. The grid is agents × meta-items: one
 * [com.ai.data.CompareCellState] per cell, judged by the WORKER engine (the
 * round-robin chain named by the chosen `meta_compare` prompt's swarm), exactly
 * like a Tournament match — so a cell's scoring model isn't known until the
 * worker chain returns; the winning worker is recorded on the cell's
 * (providerId, model).
 *
 * Structure mirrors [TournamentEngine] (dynamic-host worker batch, per-cell Job
 * map, disk hydration, app-kill resume) minus the aggregate/ranking row — the
 * UI computes per-agent / per-meta averages from the cells.
 */
class CompareEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) : BatchEngine<CompareRunKey, String, CompareCellState, CompareRunState>() {
    override fun copyWithItems(run: CompareRunState, items: Map<String, CompareCellState>) =
        run.copy(cells = items)

    /** The L1 "Wait" stat — cell ids parked on a provider throttle. */
    val throttledCells: StateFlow<Set<String>> get() = appViewModel.throttledCompareCells

    /** Per-cell coroutines keyed by [CompareCellState.id] (= on-disk row id). */
    private val cellJobs = ConcurrentHashMap<String, Job>()

    /** Top-level batch Job per report. */
    private val runJobs = ConcurrentHashMap<CompareRunKey, Job>()

    /** Per-report dedup for the resume scan. */
    private val resumeScans = ConcurrentHashMap.newKeySet<CompareRunKey>()

    private companion object {
        const val COMPARE_CATEGORY = "meta_compare"
        /** Trace/cost bucket — the slash-separated label the user asked for,
         *  set explicitly here so the on-disk category stays a flat token. */
        const val TRACE_CATEGORY = "meta/compare"
        const val USAGE_KIND = "compare"
    }

    private fun comparePromptById(aiSettings: Settings, promptId: String?): InternalPrompt? =
        aiSettings.internalPrompts.firstOrNull { it.id == promptId && it.category == COMPARE_CATEGORY }
            ?: aiSettings.internalPrompts.firstOrNull { it.category == COMPARE_CATEGORY }

    // -----------------------------------------------------------------
    // Hydration — disk → StateFlow
    // -----------------------------------------------------------------

    suspend fun hydrate(context: Context, reportId: String) {
        val aiSettings = appViewModel.uiState.value.aiSettings
        val rows = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.COMPARE)
        }
        val byRun = rows.filter { !it.compareRunId.isNullOrBlank() }
            .groupBy { it.compareRunId!! }
        if (byRun.isEmpty()) {
            _runs.update { it - reportId }
            return
        }
        // One compare run per report — pick the newest run group.
        val (runId, group) = byRun.maxByOrNull { (_, g) -> g.maxOf { it.timestamp } }!!
        val prompt = aiSettings.internalPrompts.firstOrNull { it.id == group.first().metaPromptId }
            ?: comparePromptById(aiSettings, null) ?: run { _runs.update { it - reportId }; return }
        val currentCells = _runs.value[reportId]?.cells
        val cells = group.mapNotNull { it.toCompareCellState() }
            .associateBy { it.key }
            .mapValues { (k, diskCell) ->
                if (diskCell.status == CompareCellStatus.PENDING &&
                    currentCells?.get(k)?.status == CompareCellStatus.RUNNING
                ) diskCell.copy(status = CompareCellStatus.RUNNING) else diskCell
            }
        val run = CompareRunState(
            key = reportId,
            reportId = reportId,
            runId = runId,
            comparePrompt = prompt,
            cells = cells
        )
        _runs.update { it + (reportId to run) }
    }

    fun runByKey(key: CompareRunKey): CompareRunState? = _runs.value[key]

    // -----------------------------------------------------------------
    // State-flow transition helpers
    // -----------------------------------------------------------------

    private fun transitionCell(reportId: String, cKey: String, update: (CompareCellState) -> CompareCellState) =
        transitionItem(reportId, cKey, update)

    private fun dropCell(reportId: String, cKey: String) = dropItem(reportId, cKey)

    // -----------------------------------------------------------------
    // Run launch
    // -----------------------------------------------------------------

    private data class PendingCell(
        val agentId: String, val metaResultId: String,
        val placeholder: SecondaryResult
    )

    /** agents × meta-items — the number of worker calls a compare run over
     *  [agentCount] answers and [metaCount] meta items makes. */
    fun cellCountFor(agentCount: Int, metaCount: Int): Int = agentCount * metaCount

    /** Launch a brand-new compare run on [reportId]: score every successful
     *  answer against every meta result in [metaResultIds] using [promptId].
     *  Pre-creates agents×meta CELL placeholders (sentinel provider/model),
     *  publishes the run with all cells PENDING, then scores each cell through
     *  the worker batch. */
    fun startRun(context: Context, reportId: String, metaResultIds: List<String>, promptId: String, buildKey: String? = null, overrideWorkers: List<com.ai.model.Worker>? = null): Job? {
        val rk: CompareRunKey = reportId
        runJobs[rk]?.let { if (it.isActive) return it }
        appViewModel.updateUiState { it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1) }
        val runId = java.util.UUID.randomUUID().toString()
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                withTracerTags(reportId = reportId, category = TRACE_CATEGORY, runId = runId) {
                    val aiSettings = appViewModel.uiState.value.aiSettings
                    val report = ReportStorage.getReport(context, reportId) ?: return@withTracerTags
                    // ♻️ report-models as the compare worker pool, winning over a *SELECT pick.
                    val prompt = comparePromptById(aiSettings, promptId)?.let {
                        when {
                            report.useReportModelsAsWorkers -> it.copy(workers = reportModelWorkers(report))
                            overrideWorkers != null -> it.copy(workers = overrideWorkers)
                            else -> it
                        }
                    }
                    if (prompt == null || prompt.workers.none { aiSettings.resolveWorker(it) != null }) {
                        AppLog.w("Compare", "meta_compare prompt not configured / no runnable workers — aborting")
                        return@withTracerTags
                    }
                    ReportStorage.bumpReportTimestamp(context, reportId)
                    val successful = report.agents.filter {
                        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
                    }
                    // Resolve the chosen meta rows, keeping only those that still
                    // exist with non-blank content.
                    val metaRows = metaResultIds.distinct().mapNotNull { mid ->
                        SecondaryResultStorage.get(context, reportId, mid)?.takeIf { !it.content.isNullOrBlank() }
                    }
                    if (successful.isEmpty() || metaRows.isEmpty()) {
                        AppLog.w("Compare", "nothing to compare (answers=${successful.size}, meta=${metaRows.size})")
                        return@withTracerTags
                    }
                    AuditLog.append(reportId, "Start Compare with meta — ${successful.size} answers × ${metaRows.size} meta items")
                    val scopeEncoded = SecondaryScope.AllReports.encode()

                    val pending = mutableListOf<PendingCell>()
                    val newCells = LinkedHashMap<String, CompareCellState>()
                    // Build stage: create every (answer × meta) cell up front.
                    if (buildKey != null) appViewModel.beginBuild(buildKey, cellCountFor(successful.size, metaRows.size), "Building compare")
                    var built = 0
                    for (agent in successful) {
                        for (metaRow in metaRows) {
                            val placeholder = SecondaryResultStorage.create(
                                context, reportId, SecondaryKind.COMPARE,
                                COMPARE_PENDING_PROVIDER, COMPARE_PENDING_MODEL, "Compare cell"
                            ) {
                                it.copy(
                                    compareRunId = runId,
                                    compareAgentId = agent.agentId,
                                    compareToResultId = metaRow.id,
                                    metaPromptId = prompt.id, metaPromptName = prompt.name,
                                    runId = runId, secondaryScope = scopeEncoded
                                )
                            }
                            pending.add(PendingCell(agent.agentId, metaRow.id, placeholder))
                            placeholder.toCompareCellState()?.let { newCells[it.key] = it }
                            if (buildKey != null) { built++; if (built % 5 == 0) appViewModel.updateBuild(buildKey, built) }
                        }
                    }
                    if (buildKey != null) appViewModel.finishBuild(buildKey)

                    _runs.update { runs ->
                        runs + (rk to CompareRunState(
                            key = rk, reportId = reportId, runId = runId,
                            comparePrompt = prompt, cells = newCells
                        ))
                    }

                    dispatchCells(context, reportId, prompt, report.prompt, report.title, pending)
                    AuditLog.append(reportId, "End Compare with meta")
                }
            } finally {
                appViewModel.updateUiState { it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0)) }
                finalizeLeftoverCells(context, reportId)
                if (buildKey != null) {
                    val p = appViewModel.batchBuildProgress.value[buildKey]
                    if (p != null && !p.done) appViewModel.clearBuild(buildKey)
                }
            }
        }
        runJobs[rk] = job
        job.invokeOnCompletion { runJobs.remove(rk, job) }
        return job
    }

    /** Dynamic-host cell dispatch — each worker call self-throttles its own
     *  provider under the shared workers cap (mirrors the Tournament batch). */
    private suspend fun dispatchCells(
        context: Context, reportId: String, prompt: InternalPrompt,
        question: String, title: String, items: List<PendingCell>
    ) {
        if (items.isEmpty()) return
        val report = ReportStorage.getReport(context, reportId)
        val agentBodyById = report?.agents?.associate { it.agentId to it.responseBody.orEmpty() }.orEmpty()
        // Resolve each referenced meta row's content once (strip the appended
        // reference legend so [1]/[2] artifacts don't pollute the judgment).
        val metaContentById = items.map { it.metaResultId }.distinct().associateWith { mid ->
            SecondaryResultStorage.get(context, reportId, mid)?.content
                ?.let { stripMetaReferenceLegend(it) }.orEmpty()
        }
        runThrottledBatch(
            items = items,
            hostOf = { null },
            subCap = ApiCallCaps.workers,
            onThrottled = { appViewModel.updateThrottledCompareCells { s -> s + it.placeholder.id } },
            onCleared = { appViewModel.updateThrottledCompareCells { s -> s - it.placeholder.id } },
            dynamicHost = true,
            register = { item, d ->
                cellJobs[item.placeholder.id] = d
                d.invokeOnCompletion { cellJobs.remove(item.placeholder.id, d) }
            }
        ) { item ->
            if (!SecondaryResultStorage.exists(context, reportId, item.placeholder.id)) return@runThrottledBatch
            try {
                runOneCell(context, reportId, prompt, question, title, agentBodyById, metaContentById, item)
            } finally {
                appViewModel.updateThrottledCompareCells { it - item.placeholder.id }
            }
        }
    }

    // -----------------------------------------------------------------
    // Per-cell worker call (mirrors runOneMatch)
    // -----------------------------------------------------------------

    private suspend fun runOneCell(
        context: Context, reportId: String, prompt: InternalPrompt,
        question: String, title: String,
        agentBodyById: Map<String, String>, metaContentById: Map<String, String>,
        item: PendingCell
    ) {
        val cKey = compareCellKey(item.agentId, item.metaResultId)
        val rowId = item.placeholder.id
        transitionCell(reportId, cKey) { it.copy(status = CompareCellStatus.RUNNING) }
        val started = System.currentTimeMillis()
        val aiSettings = appViewModel.uiState.value.aiSettings
        val resolvedBase = resolveSecondaryPrompt(prompt.text, question = question, results = "", count = 1, title = title)
        val resolved = resolvedBase
            .replace("@RESPONSE@", agentBodyById[item.agentId].orEmpty())
            .replace("@META_RESPONSE@", metaContentById[item.metaResultId].orEmpty())
        val observer: (Boolean) -> Unit = { waiting ->
            if (waiting) appViewModel.updateThrottledCompareCells { it + rowId }
            else appViewModel.updateThrottledCompareCells { it - rowId }
        }
        val traceSink = java.util.concurrent.atomic.AtomicReference<String?>(null)
        try {
            val outcome = withContext(ProviderThrottle.throttleWaitObserver.asContextElement(observer)) {
                com.ai.data.withTraceFilenameSink(traceSink) {
                    reportViewModel.workerRunner.run(prompt, resolved, aiSettings, context) { resp ->
                        parseSimilarityScore(resp.analysis) != null
                    }
                }
            }
            when (outcome) {
                is WorkerOutcome.Success -> {
                    val winAgent = aiSettings.resolveWorker(outcome.worker)?.let {
                        it.copy(model = aiSettings.getEffectiveModelForAgent(it))
                    }
                    val tu = outcome.response.tokenUsage
                    val inT = tu?.inputTokens ?: 0
                    val outT = tu?.outputTokens ?: 0
                    val provId = winAgent?.provider?.id ?: COMPARE_PENDING_PROVIDER
                    val mdl = winAgent?.model ?: COMPARE_PENDING_MODEL
                    var inCost = 0.0; var outCost = 0.0
                    if (winAgent != null && tu != null && (inT > 0 || outT > 0)) {
                        val pricing = PricingCache.getPricing(context, winAgent.provider, winAgent.model)
                        val split = PricingCache.computeInOutCost(tu, pricing)
                        inCost = split.first
                        outCost = split.second
                        appViewModel.settingsPrefs.updateUsageStatsAsync(winAgent.provider, winAgent.model, tu, kind = USAGE_KIND)
                    }
                    SecondaryResultStorage.recordCompareCell(
                        context, reportId, rowId, provId, mdl,
                        outcome.response.analysis.orEmpty(),
                        inT, outT, inCost, outCost, System.currentTimeMillis() - started,
                        traceFile = traceSink.get()
                    )
                }
                else -> {
                    val msg = if (outcome is WorkerOutcome.AllRateLimited) "compare: all workers rate-limited"
                              else "compare: no worker produced a score"
                    val cur = SecondaryResultStorage.get(context, reportId, rowId) ?: item.placeholder
                    SecondaryResultStorage.save(context, cur.copy(errorMessage = msg, durationMs = System.currentTimeMillis() - started))
                }
            }
        } finally {
            // Mirror the saved row into memory in a NonCancellable block so a
            // stop / cancel mid-call still settles the cell on its disk-truth
            // status instead of leaving it stuck at RUNNING — the per-screen
            // 3s re-hydrate that used to recover that case has been removed.
            withContext(kotlinx.coroutines.NonCancellable) {
                val saved = SecondaryResultStorage.get(context, reportId, rowId)
                if (saved == null) dropCell(reportId, cKey)
                else transitionCell(reportId, cKey) {
                    saved.toCompareCellState() ?: it.copy(status = CompareCellStatus.ERROR, errorMessage = "Cell row could not be parsed")
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Failure / rerun / delete
    // -----------------------------------------------------------------

    fun restartFailedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val keys = run.cells.values.filter { it.status == CompareCellStatus.ERROR }.map { it.key }
            rerunCellsBlocking(context, reportId, keys)
        }

    /** Broken-work "Continue": stop this run's in-flight cells (keeping the
     *  run + its finished cells), then re-queue every broken cell (stranded
     *  PENDING + errored) and re-compare in one batch, driving the build-stage
     *  popup off [buildKey]. Finished cells are untouched. Compare has no
     *  aggregate row, so (unlike Tournament / Judges) there is nothing to
     *  recompute. */
    fun continueBrokenBatch(context: Context, reportId: String, buildKey: String?): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                stopInFlightKeepingState(reportId)
                hydrate(context, reportId)
                _runs.value[reportId]?.let { run ->
                    val keys = run.cells.values.filter {
                        it.status == CompareCellStatus.PENDING || it.status == CompareCellStatus.ERROR
                    }.map { it.key }
                    rerunCellsBlocking(context, reportId, keys, buildKey)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                buildKey?.let { appViewModel.clearBuild(it) }
                throw e
            } catch (e: Exception) {
                AppLog.w("Compare", "continue broken batch failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                buildKey?.let {
                    if (appViewModel.batchBuildProgress.value[it]?.done != true) appViewModel.finishBuild(it)
                }
            }
        }

    /** Cancel this run's outer Job + every per-cell coroutine and JOIN them
     *  (so no in-flight write lands after we re-queue) WITHOUT deleting rows
     *  or dropping the run — the keep-state counterpart of [cancelAllForReport],
     *  used by [continueBrokenBatch]. */
    private suspend fun stopInFlightKeepingState(reportId: String) {
        runJobs[reportId]?.cancelAndJoin()
        _runs.value[reportId]?.cells?.values?.forEach { cellJobs[it.id]?.cancelAndJoin() }
    }

    fun rerunCell(context: Context, reportId: String, cKey: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            rerunCellsBlocking(context, reportId, listOf(cKey))
        }

    /** Drop every errored compare cell without re-firing — clears a
     *  permanently-dead failure so the run can settle. Rolls the spend into
     *  deleted-items; if nothing is left, drops the whole run. (Compare has
     *  no aggregate row.) */
    fun removeFailedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val failed = run.cells.values.filter { it.status == CompareCellStatus.ERROR }
            if (failed.isEmpty()) return@launch
            failed.forEach { cellJobs[it.id]?.cancelAndJoin() }
            val costDelta = failed.sumOf { it.totalCost }
            failed.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            val remaining = run.cells - failed.map { it.key }.toSet()
            if (remaining.isEmpty()) {
                dropRun(reportId)
            } else {
                _runs.update { runs ->
                    val cur = runs[reportId] ?: return@update runs
                    runs + (reportId to cur.copy(cells = remaining))
                }
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }

    /** Drop every unfinished (stranded, never-ran) compare cell without
     *  re-firing — the Broken-work "delete unfinished" action. Mirror of
     *  [removeFailedCells], narrowed to PENDING rows. (Compare has no
     *  aggregate row.) */
    fun removeUnfinishedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val stranded = run.cells.values.filter { it.status == CompareCellStatus.PENDING }
            if (stranded.isEmpty()) return@launch
            stranded.forEach { cellJobs[it.id]?.cancelAndJoin() }
            val costDelta = stranded.sumOf { it.totalCost }
            stranded.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            val remaining = run.cells - stranded.map { it.key }.toSet()
            if (remaining.isEmpty()) {
                dropRun(reportId)
            } else {
                _runs.update { runs ->
                    val cur = runs[reportId] ?: return@update runs
                    runs + (reportId to cur.copy(cells = remaining))
                }
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }

    fun removeCellsByIds(context: Context, reportId: String, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val victims = run.cells.values.filter { it.id in rowIds }
            if (victims.isEmpty()) return@launch
            victims.forEach { cellJobs[it.id]?.cancelAndJoin() }
            val costDelta = victims.sumOf {
                SecondaryResultStorage.get(context, reportId, it.id)?.fullCost() ?: it.totalCost
            }
            victims.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            val remaining = run.cells - victims.map { it.key }.toSet()
            if (remaining.isEmpty()) {
                dropRun(reportId)
            } else {
                _runs.update { runs ->
                    val cur = runs[reportId] ?: return@update runs
                    runs + (reportId to cur.copy(cells = remaining))
                }
            }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }

    fun restartCellsByIds(context: Context, reportId: String, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            val keys = run.cells.values.filter { it.id in rowIds }.map { it.key }
            rerunCellsBlocking(context, reportId, keys)
        }

    private suspend fun rerunCellsBlocking(context: Context, reportId: String, cKeys: List<String>, buildKey: String? = null) {
        if (cKeys.isEmpty()) { buildKey?.let { appViewModel.finishBuild(it) }; return }
        val run = _runs.value[reportId] ?: return
        val report = ReportStorage.getReport(context, reportId) ?: return
        val prompt = run.comparePrompt
        val resets = mutableListOf<PendingCell>()
        var clearedCostDelta = 0.0
        // Build stage: resetting each broken cell to a PENDING placeholder is
        // the "Preparing N / M…" phase the Broken-work Continue popup covers.
        if (buildKey != null) appViewModel.beginBuild(buildKey, cKeys.size, "Re-queuing compare")
        for (k in cKeys) {
            val c = run.cells[k] ?: continue
            SecondaryResultStorage.get(context, reportId, c.id)?.let { clearedCostDelta += it.fullCost() }
            SecondaryResultStorage.resetCompareCell(context, reportId, c.id)
            val cleared = SecondaryResultStorage.get(context, reportId, c.id) ?: continue
            transitionCell(reportId, k) {
                it.copy(
                    status = CompareCellStatus.PENDING, judgeModel = null, content = null, percent = null,
                    reason = null, errorMessage = null,
                    inputCost = null, outputCost = null, durationMs = null, tokenUsage = null
                )
            }
            resets.add(PendingCell(c.agentId, c.metaResultId, cleared))
            if (buildKey != null) appViewModel.updateBuild(buildKey, resets.size)
        }
        if (clearedCostDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, clearedCostDelta)
        // Build phase complete — release the popup so the UI navigates to the
        // batch screen while the dispatch below keeps running in the background.
        if (buildKey != null) appViewModel.finishBuild(buildKey)
        if (resets.isEmpty()) return
        withTracerTags(reportId = reportId, category = TRACE_CATEGORY) {
            dispatchCells(context, reportId, prompt, report.prompt, report.title, resets)
        }
    }

    /** Cancel + delete the whole run, rolling the spend into the report's
     *  deleted-items tally. */
    fun deleteRun(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            val run = _runs.value[reportId] ?: return@launch
            runJobs[reportId]?.cancelAndJoin()
            run.cells.values.forEach { cellJobs[it.id]?.cancelAndJoin() }
            val costDelta = run.cells.values.sumOf { it.totalCost }
            run.cells.values.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            dropRun(reportId)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }

    /** Best-effort cancel of every in-flight cell for [reportId] (called from
     *  the synchronous report-delete path). */
    fun cancelAllForReport(reportId: String) {
        runJobs[reportId]?.cancel()
        _runs.value[reportId]?.cells?.values?.forEach { cellJobs[it.id]?.cancel() }
        _runs.update { it - reportId }
    }

    /** Compare cell row ids whose worker Job is live in THIS process. The
     *  read-only Broken-work scan must exclude these the same way it excludes
     *  Fan Out / Tournament / Judges rows; otherwise a legitimate running
     *  Compare batch is advertised as interrupted. */
    fun inFlightRowIds(): Set<String> = cellJobs.keys.toSet()

    /** Top-level Compare runs currently alive in this process. Covers rows
     *  that have been pre-created but have not yet received a per-cell Job. */
    fun activeRunKeys(): Set<CompareRunKey> =
        runJobs.filterValues { it.isActive }.keys.toSet()

    // -----------------------------------------------------------------
    // Resume on report open / app restart
    // -----------------------------------------------------------------

    fun resumeStaleRunsForReport(context: Context, reportId: String, resetAttempts: Boolean = false): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            // hydrate runs before the scan guard, so it needs its own guard —
            // this launch is a direct child of viewModelScope, so an uncaught
            // throw here reaches the global handler and crashes the app.
            try {
                hydrate(context, reportId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Compare", "hydrate failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }
            if (!resumeScans.add(reportId)) return@launch
            try {
                val run = _runs.value[reportId] ?: return@launch
                val prompt = run.comparePrompt
                val report = ReportStorage.getReport(context, reportId) ?: return@launch
                val diskById = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.COMPARE)
                    .filter {
                        it.content.isNullOrBlank() && it.errorMessage == null &&
                            it.durationMs == null && !cellJobs.containsKey(it.id)
                    }.associateBy { it.id }
                if (diskById.isEmpty()) return@launch
                val staleRows = run.cells.values
                    .filter { it.status == CompareCellStatus.PENDING && it.id in diskById }
                    .mapNotNull { diskById[it.id] }
                if (resetAttempts) BatchResume.resetAttempts(staleRows.map { it.id })
                val retryRows = BatchResume.capForRetry(staleRows) { row ->
                    markRowInterrupted(context, reportId, row.id, "Interrupted — no result after ${BatchResume.MAX_ATTEMPTS} resume attempts")
                    run.cells.values.firstOrNull { it.id == row.id }?.let { c ->
                        transitionCell(reportId, c.key) {
                            it.copy(status = CompareCellStatus.ERROR, errorMessage = "Interrupted — no result after resume attempts", durationMs = 0)
                        }
                    }
                }
                val pending = retryRows.mapNotNull { row ->
                    val c = run.cells.values.firstOrNull { it.id == row.id } ?: return@mapNotNull null
                    PendingCell(c.agentId, c.metaResultId, row)
                }
                if (pending.isEmpty()) return@launch
                withTracerTags(reportId = reportId, category = TRACE_CATEGORY) {
                    dispatchCells(context, reportId, prompt, report.prompt, report.title, pending)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Direct child of viewModelScope — contain the throw so a
                // failed resume leaves the run as-is instead of crashing the
                // app (the background sweep only join()s this Job).
                AppLog.w("Compare", "resume stale runs failed report=$reportId: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                resumeScans.remove(reportId)
            }
        }

    private suspend fun finalizeLeftoverCells(context: Context, reportId: String) {
        withContext(kotlinx.coroutines.NonCancellable) {
            val leftover = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.COMPARE)
                .filter {
                    it.content.isNullOrBlank() && it.errorMessage == null &&
                        it.durationMs == null && !cellJobs.containsKey(it.id)
                }
            BatchResume.finalizeLeftover(leftover) { row ->
                markRowInterrupted(context, reportId, row.id, "Interrupted — run stopped before this cell finished")
                _runs.value[reportId]?.cells?.values?.firstOrNull { it.id == row.id }?.let { c ->
                    transitionCell(reportId, c.key) {
                        if (it.status == CompareCellStatus.PENDING || it.status == CompareCellStatus.RUNNING)
                            it.copy(status = CompareCellStatus.ERROR, errorMessage = "Interrupted", durationMs = 0)
                        else it
                    }
                }
            }
        }
    }

    private fun markRowInterrupted(context: Context, reportId: String, rowId: String, message: String) {
        val current = SecondaryResultStorage.get(context, reportId, rowId) ?: return
        if (current.errorMessage != null || !current.content.isNullOrBlank() || current.durationMs != null) return
        SecondaryResultStorage.save(context, current.copy(errorMessage = message, durationMs = 0))
    }
}
