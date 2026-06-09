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
    override val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) : SecondaryBatchEngine<CompareRunKey, CompareCellState, CompareRunState>() {
    override fun copyWithItems(run: CompareRunState, items: Map<String, CompareCellState>) =
        run.copy(cells = items)

    override val secondaryKind = SecondaryKind.COMPARE
    override val logTag = "Compare"
    override val itemNoun = "cell"
    override fun reportIdOf(runKey: CompareRunKey) = runKey
    override fun runKeysForReport(reportId: String) = listOf(reportId)
    override fun terminalizeItem(item: CompareCellState, message: String) =
        item.copy(status = CompareCellStatus.ERROR, errorMessage = message, durationMs = 0)
    override fun canRedispatch(context: Context, run: CompareRunState) =
        run.comparePrompt.text.isNotBlank()   // synthetic prompt — can't re-run; audit bug 15

    override suspend fun redispatchRows(context: Context, runKey: CompareRunKey, rows: List<SecondaryResult>) {
        val run = _runs.value[runKey] ?: return
        val report = ReportStorage.getReport(context, runKey) ?: return
        val pending = rows.mapNotNull { row ->
            val c = run.cells.values.firstOrNull { it.id == row.id } ?: return@mapNotNull null
            PendingCell(c.agentId, c.metaResultId, row)
        }
        if (pending.isEmpty()) return
        withTracerTags(reportId = runKey, category = TRACE_CATEGORY) {
            dispatchCells(context, runKey, run.comparePrompt, report.prompt, report.title, pending)
        }
    }

    /** The L1 "Wait" stat — cell ids parked on a provider throttle. */
    val throttledCells: StateFlow<Set<String>> get() = appViewModel.throttledCompareCells

    // Run/cell coroutines and the resume-scan dedup now live in the shared
    // BatchEngine base (registerRunJob / registerItemJob / beginResumeScan /
    // runJobOf / itemJobOf / activeRunJobKeys / hasItemJob), so this engine no
    // longer keeps its own runJobs / cellJobs / resumeScans maps.

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

    override suspend fun hydrate(context: Context, reportId: String) {
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
        // Keep the run visible even if the compare prompt was deleted/renamed
        // since it ran: fall back to a synthetic prompt from the row metadata
        // (blank text) so the cells hydrate read-only. Rerun/resume no-op on a
        // synthetic (blank-text) prompt. See audit bug 15.
        val prompt = aiSettings.internalPrompts.firstOrNull { it.id == group.first().metaPromptId }
            ?: comparePromptById(aiSettings, null)
            ?: InternalPrompt(
                id = group.first().metaPromptId ?: "",
                name = group.first().metaPromptName?.takeIf { it.isNotBlank() } ?: "(prompt unavailable)",
                category = COMPARE_CATEGORY
            )
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
        // Don't re-publish a run whose delete is mid-flight (rows still on disk).
        _runs.update { if (isDeleting(reportId)) it else it + (reportId to run) }
    }

    fun runByKey(key: CompareRunKey): CompareRunState? = _runs.value[key]

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
        runJobOf(rk)?.let { if (it.isActive) return it }
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
                            val placeholder = SecondaryResult(
                                id = java.util.UUID.randomUUID().toString(),
                                reportId = reportId,
                                kind = SecondaryKind.COMPARE,
                                providerId = COMPARE_PENDING_PROVIDER,
                                model = COMPARE_PENDING_MODEL,
                                agentName = "Compare cell",
                                timestamp = System.currentTimeMillis(),
                                content = null,
                                compareRunId = runId,
                                compareAgentId = agent.agentId,
                                compareToResultId = metaRow.id,
                                metaPromptId = prompt.id,
                                metaPromptName = prompt.name,
                                runId = runId,
                                secondaryScope = scopeEncoded
                            )
                            pending.add(PendingCell(agent.agentId, metaRow.id, placeholder))
                            if (buildKey != null) { built++; if (built % 5 == 0) appViewModel.updateBuild(buildKey, built) }
                        }
                    }
                    val savedIds = SecondaryResultStorage.saveAll(context, pending.map { it.placeholder })
                        .mapTo(HashSet()) { it.id }
                    pending.removeAll { it.placeholder.id !in savedIds }
                    pending.forEach { item -> item.placeholder.toCompareCellState()?.let { newCells[it.key] = it } }
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
                finalizeLeftoverItems(context, reportId)
                if (buildKey != null) {
                    val p = appViewModel.batchBuildProgress.value[buildKey]
                    if (p != null && !p.done) appViewModel.clearBuild(buildKey)
                }
            }
        }
        registerRunJob(rk, job)
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
                registerItemJob(item.placeholder.id, d)
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
        transitionItem(reportId, cKey) { it.copy(status = CompareCellStatus.RUNNING) }
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
                if (saved == null) dropItem(reportId, cKey)
                else transitionItem(reportId, cKey) {
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

    fun rerunCell(context: Context, reportId: String, cKey: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            rerunCellsBlocking(context, reportId, listOf(cKey))
        }

    /** Drop every errored compare cell without re-firing — clears a
     *  permanently-dead failure so the run can settle. (Compare has no
     *  aggregate row, so there is nothing to recompute.) */
    fun removeFailedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, reportId) { it.status == CompareCellStatus.ERROR }
        }

    /** Drop every unfinished (stranded, never-ran) compare cell without
     *  re-firing — the Broken-work "delete unfinished" action. */
    fun removeUnfinishedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, reportId) { it.status == CompareCellStatus.PENDING }
        }

    fun removeCellsByIds(context: Context, reportId: String, rowIds: Set<String>): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            removeItemsMatching(context, reportId) { it.id in rowIds }
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
        // A synthetic (prompt-unavailable) run carries blank prompt text and
        // can't be re-run. See audit bug 15.
        if (prompt.text.isBlank()) { buildKey?.let { appViewModel.finishBuild(it) }; return }
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
            transitionItem(reportId, k) {
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
    fun deleteRun(context: Context, reportId: String): Job {
        val run = _runs.value[reportId] ?: return appViewModel.viewModelScope.launch { }
        // Capture the live coroutines before the deferred delete drops the run.
        val runJob = runJobOf(reportId)
        val itemJobs = run.cells.values.mapNotNull { itemJobOf(it.id) }
        return deleteRunDeferred(appViewModel.viewModelScope, reportId, runJob, itemJobs) {
            val costDelta = run.cells.values.sumOf { it.totalCost }
            run.cells.values.forEach { SecondaryResultStorage.delete(context, reportId, it.id) }
            if (costDelta > 0.0) ReportStorage.bumpCostsFromDeletedItems(context, reportId, costDelta)
            ReportStorage.bumpReportTimestamp(context, reportId)
        }
    }

}
