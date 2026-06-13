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
import com.ai.data.NetworkSettings
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.SecondaryScope
import com.ai.data.compareCellKey
import com.ai.data.parseSimilarityScore
import com.ai.data.resolveSecondaryPrompt
import com.ai.data.stripMetaReferenceLegend
import com.ai.data.toCompareCellState
import com.ai.data.withTracerTags
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    override val reportViewModel: ReportViewModel
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
    override fun itemFromRow(row: SecondaryResult) = row.toCompareCellState()
    override fun markItemRunning(item: CompareCellState) = item.copy(status = CompareCellStatus.RUNNING)
    override fun canRedispatch(context: Context, run: CompareRunState) =
        run.comparePrompt.text.isNotBlank()   // synthetic prompt — can't re-run; audit bug 15
    override val requeueBuildLabel = "Re-queuing compare"
    override fun resetItemToPending(item: CompareCellState) =
        item.copy(
            status = CompareCellStatus.PENDING, judgeModel = null, content = null, percent = null,
            reason = null, errorMessage = null,
            inputCost = null, outputCost = null, durationMs = null, tokenUsage = null
        )
    /** Restore the pre-score sentinel too — the next scoring worker is
     *  unknown until the worker chain settles (dynamic-host). */
    override fun clearRowForRerun(row: SecondaryResult) =
        row.copy(
            providerId = COMPARE_PENDING_PROVIDER,
            model = COMPARE_PENDING_MODEL,
            agentName = "$COMPARE_PENDING_PROVIDER / $COMPARE_PENDING_MODEL",
            content = null, errorMessage = null,
            inputCost = null, outputCost = null, tokenUsage = null, durationMs = null,
            timestamp = System.currentTimeMillis()
        )

    override suspend fun redispatchRows(context: Context, runKey: CompareRunKey, rows: List<SecondaryResult>) {
        val run = _runs.value[runKey] ?: return
        val report = ReportStorage.getReport(context, runKey) ?: return
        // Compare always runs on the workers defined in its own prompt — it
        // opts out of the report's Worker-batches choice. Holds on resume /
        // Broken-work restart too (the hydrated prompt carries the configured
        // swarm).
        val prompt = run.comparePrompt.withBatchWorkers(report, alwaysPromptWorkers = true)
        val cellsById = run.cells.values.associateBy { it.id }
        val pending = rows.mapNotNull { row ->
            val c = cellsById[row.id] ?: return@mapNotNull null
            PendingCell(c.agentId, c.metaResultId, row)
        }
        if (pending.isEmpty()) return
        withTracerTags(reportId = runKey, category = TRACE_CATEGORY) {
            dispatchCells(context, runKey, prompt, report.prompt, report.title, pending)
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
        hydrateNewestRun(context, reportId, reportId, { it.compareRunId }) { runId, group ->
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
            CompareRunState(
                key = reportId,
                reportId = reportId,
                runId = runId,
                comparePrompt = prompt,
                cells = itemsFromGroup(reportId, group)
            )
        }
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
    fun startRun(context: Context, reportId: String, metaResultIds: List<String>, promptId: String, buildKey: String? = null, overrideWorkers: List<com.ai.model.Worker>? = null): Job? =
        launchRun(context, reportId, buildKey, TRACE_CATEGORY) { runId ->
            val aiSettings = appViewModel.uiState.value.aiSettings
            val report = ReportStorage.getReport(context, reportId) ?: return@launchRun
            // Compare always runs on its own prompt's workers — it opts out of
            // the report's Worker-batches choice (a *SELECT prompt still picks).
            val prompt = comparePromptById(aiSettings, promptId)?.withBatchWorkers(report, overrideWorkers, alwaysPromptWorkers = true)
            if (prompt == null || prompt.workers.none { aiSettings.resolveWorker(it) != null }) {
                AppLog.w("Compare", "meta_compare prompt not configured / no runnable workers — aborting")
                return@launchRun
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
                return@launchRun
            }
            AuditLog.append(reportId, "Start Compare with meta — ${successful.size} answers × ${metaRows.size} meta items")
            val scopeEncoded = SecondaryScope.AllReports.encode()

            val pending = mutableListOf<PendingCell>()
            val newCells = LinkedHashMap<String, CompareCellState>()
            // Build stage: create every (answer × meta) cell up front.
            // Counter tracks the persist below — construction is instant.
            if (buildKey != null) appViewModel.beginBuild(buildKey, cellCountFor(successful.size, metaRows.size), "Building compare")
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
                }
            }
            val savedIds = SecondaryResultStorage.saveAll(
                context, pending.map { it.placeholder },
                onProgress = { n -> if (buildKey != null) appViewModel.updateBuild(buildKey, n) }
            ).mapTo(HashSet()) { it.id }
            pending.removeAll { it.placeholder.id !in savedIds }
            pending.forEach { item -> item.placeholder.toCompareCellState()?.let { newCells[it.key] = it } }
            if (buildKey != null) appViewModel.finishBuild(buildKey)

            _runs.update { runs ->
                runs + (reportId to CompareRunState(
                    key = reportId, reportId = reportId, runId = runId,
                    comparePrompt = prompt, cells = newCells
                ))
            }

            dispatchCells(context, reportId, prompt, report.prompt, report.title, pending)
            AuditLog.append(reportId, "End Compare with meta")
        }

    /** Dynamic-host cell dispatch — each worker call self-throttles its own
     *  provider under the shared workers cap (mirrors the Tournament batch). */
    private suspend fun dispatchCells(
        context: Context, reportId: String, prompt: InternalPrompt,
        question: String, title: String, items: List<PendingCell>
    ) {
        if (items.isEmpty()) return
        val report = ReportStorage.getReport(context, reportId)
        // Worker-selection mode (round robin deals cells across the
        // REPORT_MODELS pool; Random everywhere else).
        val schedule = report?.let { workerScheduleFor(it) } ?: WorkerSchedule.Random
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
                runOneCell(context, reportId, prompt, question, title, agentBodyById, metaContentById, item, schedule)
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
        item: PendingCell,
        schedule: WorkerSchedule = WorkerSchedule.Random
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
        try {
            // The pooled per-item shape — worker chain under the per-item
            // "Batch item" ceiling, failures reduced to the standard
            // messages. See runPooledItemCall.
            when (val res = runPooledItemCall(
                appViewModel, reportViewModel.workerRunner, context, prompt, resolved,
                usageKind = USAGE_KIND,
                timeoutMessage = "compare: cell timed out after ${NetworkSettings.batchItemTimeoutSec}s",
                rateLimitedMessage = "compare: all workers rate-limited",
                noResultMessage = "compare: no worker produced a score",
                onThrottleWait = { waiting ->
                    if (waiting) appViewModel.updateThrottledCompareCells { it + rowId }
                    else appViewModel.updateThrottledCompareCells { it - rowId }
                },
                schedule = schedule
            ) { resp -> parseSimilarityScore(resp.analysis) != null }) {
                is PooledItemOutcome.Success -> SecondaryResultStorage.recordCompareCell(
                    context, reportId, rowId,
                    res.winner.agent?.provider?.id ?: COMPARE_PENDING_PROVIDER,
                    res.winner.agent?.model ?: COMPARE_PENDING_MODEL,
                    res.outcome.response.analysis.orEmpty(),
                    res.winner.inTokens, res.winner.outTokens, res.winner.inCost, res.winner.outCost,
                    System.currentTimeMillis() - started,
                    traceFile = res.call.traceFile
                )
                is PooledItemOutcome.Error ->
                    recordItemCallError(
                        context, reportId, rowId, item.placeholder, res.message, started,
                        httpStatusCode = if (res.rateLimited) 429 else null
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One poisoned cell (a storage / parse / pricing throw) must
            // fail just this item — an escape would cancel every in-flight
            // sibling via the batch's coroutineScope.
            recordItemCallError(
                context, reportId, rowId, item.placeholder,
                "compare: ${e.javaClass.simpleName}: ${e.message}", started
            )
        } finally {
            settleItemFromDisk(context, reportId, cKey, rowId)
        }
    }

    // -----------------------------------------------------------------
    // Failure / rerun / delete
    // -----------------------------------------------------------------

    fun restartFailedCells(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            restartItemsWhere(context, reportId) { it.status == CompareCellStatus.ERROR }
        }

    fun rerunCell(context: Context, reportId: String, cKey: String): Job =
        appViewModel.viewModelScope.launch(Dispatchers.IO) {
            restartItemsWhere(context, reportId) { it.key == cKey }
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
            restartItemsWhere(context, reportId) { it.id in rowIds }
        }

    // deleteRun lives in the SecondaryBatchEngine base (Compare has no
    // aggregate row — aggregateRowIdOf's null default skips that delete).

}
