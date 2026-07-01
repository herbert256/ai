package com.ai.viewmodel

import android.content.Context
import com.ai.data.AppLog
import com.ai.data.RegenerateJob
import com.ai.data.RegenerateJobStatus
import com.ai.data.RegeneratePhase
import com.ai.data.RegenerateTask
import com.ai.data.RegenerateTaskState
import com.ai.data.RegenerateBatchStorage
import com.ai.data.REPORT_ICON_ROW_ID
import com.ai.data.REPORT_LANGUAGE_ROW_ID
import com.ai.data.REPORT_TITLE_ROW_ID
import com.ai.data.ReportStorage
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.shortModelName
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative runtime owner for the "Regenerate report" batch
 * job. Replaces the legacy one-shot
 * [ReportViewModel.regenerateReport] call with a phased,
 * app-restart-survivable orchestrator. See the plan file at
 * `/Users/herbert/.claude/plans/meta-items-and-fa-in-flickering-piglet.md`.
 *
 * Per-report state lives on disk via [RegenerateBatchStorage]
 * (one JSON file per report); the in-memory [StateFlow] mirrors
 * that for live UI subscription. The orchestrator is a single
 * coroutine per report, scoped to AppViewModel.viewModelScope
 * (so it survives the user navigating away from Manage) and
 * tracked in [orchestratorJobs] so a cancel can `.cancel()` it.
 *
 * Phase order is fixed (see [RegeneratePhase] declaration). The
 * orchestrator halts on the first ❌ row in the current phase and
 * persists `status = PAUSED_ON_ERROR`. A later [restart] call —
 * either user-driven or from the 30 s background sweep — re-enters
 * at `currentPhase` once the errored row is no longer ❌.
 */
class RegenerateBatchEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel
) {
    private val _jobs = MutableStateFlow<Map<String, RegenerateJob>>(emptyMap())
    val jobs: StateFlow<Map<String, RegenerateJob>> = _jobs.asStateFlow()

    /** Per-report orchestrator coroutine. Cancel + replace on
     *  restart / cancel. */
    private val orchestratorJobs = ConcurrentHashMap<String, Job>()

    /** True once a job exists in either memory or disk for this
     *  report. Cheap UI guard used by the Manage screen's row
     *  composable. */
    fun hasJob(reportId: String): Boolean = _jobs.value.containsKey(reportId)

    // -----------------------------------------------------------------
    // Hydration — disk → StateFlow
    // -----------------------------------------------------------------

    /** Read any persisted [RegenerateJob] for [reportId] off disk
     *  and publish it to the flow. Idempotent. Called from the
     *  Manage screen's LaunchedEffect on first composition and
     *  by [reconcile] on every background sweep. */
    fun hydrate(context: Context, reportId: String) {
        val job = RegenerateBatchStorage.get(context, reportId) ?: run {
            _jobs.update { it - reportId }
            return
        }
        _jobs.update { it + (reportId to job) }
    }

    // -----------------------------------------------------------------
    // Public actions
    // -----------------------------------------------------------------

    /** User clicked the 🔁 icon on Manage and confirmed. Builds
     *  a fresh task list from the report's current contents,
     *  persists the job, and kicks off the orchestrator. If a
     *  job already exists for this report the existing
     *  orchestrator is cancelled and replaced. */
    fun enqueueAndStart(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            orchestratorJobs.remove(reportId)?.cancelAndJoin()
            val tasks = buildTaskList(context, reportId)
            val now = System.currentTimeMillis()
            // Start at the FIRST phase the enum declares — not a
            // hardcoded one. Otherwise prepending a new phase
            // (ICON / LANGUAGE) silently skips it because the
            // orchestrator's advanceToNextPhase walks forward
            // from currentPhase.ordinal.
            val firstPhase = RegeneratePhase.values().firstOrNull()
                ?: return@launch
            val job = RegenerateJob(
                reportId = reportId,
                createdAt = now,
                updatedAt = now,
                status = RegenerateJobStatus.RUNNING,
                currentPhase = firstPhase,
                tasks = tasks
            )
            persist(context, job)
            startOrchestrator(context, reportId)
        }
    }

    /** User clicked Restart on the detail screen OR the
     *  background sweep wants to resume a PAUSED job. If the
     *  paused row's errorMessage is now cleared the orchestrator
     *  is restarted at `currentPhase`; otherwise this is a
     *  no-op. CANCELLED jobs always restart at `currentPhase`. */
    fun restart(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            var shouldStart = false
            mutateJob(context, reportId, allowTerminalMutation = true) { job ->
                when {
                    job.status == RegenerateJobStatus.DONE -> job
                    job.status == RegenerateJobStatus.RUNNING &&
                        orchestratorJobs[reportId]?.isActive == true -> job
                    job.status == RegenerateJobStatus.PAUSED_ON_ERROR -> {
                        // Only resume if the row that paused us is no longer
                        // in an error state on disk. The user may have hit
                        // Restart prematurely — in that case the orchestrator
                        // would just hit the same error again, so bail.
                        val pausedRowId = job.pausedOnRowId
                        if (pausedRowId != null && isRowStillErrored(context, reportId, job, pausedRowId)) {
                            AppLog.d("RegenBatch", "restart no-op: row $pausedRowId still errored")
                            job
                        } else {
                            shouldStart = true
                            job.copy(status = RegenerateJobStatus.RUNNING, pausedOnRowId = null)
                        }
                    }
                    else -> {
                        shouldStart = true
                        job.copy(status = RegenerateJobStatus.RUNNING, pausedOnRowId = null)
                    }
                }
            }
            if (shouldStart) startOrchestrator(context, reportId)
        }

    /** Synchronously cancel the orchestrator coroutine for [reportId]
     *  (no status persist). Used by deleteReport, which must stop the
     *  batch BEFORE deleting the report — the async [cancel] returns
     *  before its launch body runs, so the orchestrator could still be
     *  dispatching when the dir is removed. */
    fun cancelJobNow(reportId: String) {
        orchestratorJobs.remove(reportId)?.cancel()
    }

    /** User clicked Cancel on the detail screen. Stops the
     *  orchestrator — already-in-flight HTTP calls finish
     *  themselves and persist as normal. */
    fun cancel(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            // Just cancel the orchestrator coroutine and let ITS finally
            // block decrement activeSecondaryBatches (Bug 80). Decrementing
            // here too double-counted the same logical batch end, drifting
            // the "batches running" badge below the real count.
            orchestratorJobs.remove(reportId)?.cancel()
            mutateJob(context, reportId) { job ->
                if (job.status == RegenerateJobStatus.DONE ||
                    job.status == RegenerateJobStatus.CANCELLED) {
                    job
                } else {
                    job.copy(status = RegenerateJobStatus.CANCELLED)
                }
            }
        }
    }

    /** Called from [ReportViewModel.resumeStaleRunsForReport] on
     *  every 30 s sweep tick. Idempotent:
     *   - DONE / CANCELLED → no-op.
     *   - RUNNING + orchestrator alive → no-op.
     *   - RUNNING + orchestrator dead (app kill) → restart.
     *   - PAUSED_ON_ERROR + paused row now ok → restart.
     *   - PAUSED_ON_ERROR + paused row still errored → no-op. */
    fun reconcile(context: Context, reportId: String) {
        val job = RegenerateBatchStorage.get(context, reportId) ?: return
        _jobs.update { it + (reportId to job) }
        when (job.status) {
            RegenerateJobStatus.DONE,
            RegenerateJobStatus.CANCELLED -> return
            RegenerateJobStatus.RUNNING -> {
                if (orchestratorJobs[reportId]?.isActive == true) return
                AppLog.i("RegenBatch", "reviving stale RUNNING orchestrator for $reportId")
                startOrchestrator(context, reportId)
            }
            RegenerateJobStatus.PAUSED_ON_ERROR -> {
                val pausedRowId = job.pausedOnRowId
                if (pausedRowId != null && isRowStillErrored(context, reportId, job, pausedRowId)) {
                    return
                }
                AppLog.i("RegenBatch", "auto-resuming PAUSED batch for $reportId — error cleared")
                var shouldStart = false
                mutateJob(context, reportId) { current ->
                    if (current.status != RegenerateJobStatus.PAUSED_ON_ERROR) {
                        current
                    } else {
                        val currentPausedRowId = current.pausedOnRowId
                        if (currentPausedRowId != null && isRowStillErrored(context, reportId, current, currentPausedRowId)) {
                            current
                        } else {
                            shouldStart = true
                            current.copy(status = RegenerateJobStatus.RUNNING, pausedOnRowId = null)
                        }
                    }
                }
                if (!shouldStart) return
                startOrchestrator(context, reportId)
            }
        }
    }

    /** Read-only counterpart to [reconcile] for the broken-work scan:
     *  returns true when this report carries a regenerate job that needs
     *  attention but is NOT progressing on its own —
     *   - RUNNING with no live orchestrator (app-kill interrupted), or
     *   - PAUSED_ON_ERROR (stopped on a failing row, awaiting the user).
     *  Mutates nothing — DONE / CANCELLED and a genuinely-running
     *  orchestrator (a manual regenerate in flight) report false. */
    fun detectBroken(context: Context, reportId: String): Boolean {
        val job = RegenerateBatchStorage.get(context, reportId) ?: return false
        return when (job.status) {
            RegenerateJobStatus.DONE,
            RegenerateJobStatus.CANCELLED -> false
            RegenerateJobStatus.RUNNING -> orchestratorJobs[reportId]?.isActive != true
            RegenerateJobStatus.PAUSED_ON_ERROR -> true
        }
    }

    /** True while this report's regenerate-batch is actively progressing —
     *  a live orchestrator coroutine in THIS process. The Broken-work scan
     *  reads this so a mid-batch report's RUNNING agents aren't mistaken for
     *  app-kill-interrupted ones (the inverse of [detectBroken]'s
     *  RUNNING-but-dead case). */
    fun isActivelyRunning(reportId: String): Boolean =
        orchestratorJobs[reportId]?.isActive == true

    /** Drop the persisted job + in-memory entry. Used by the
     *  detail screen's "delete" action (future). */
    fun deleteJob(context: Context, reportId: String): Job =
        appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            orchestratorJobs.remove(reportId)?.cancelAndJoin()
            RegenerateBatchStorage.delete(context, reportId)
            _jobs.update { it - reportId }
        }

    // -----------------------------------------------------------------
    // Orchestrator
    // -----------------------------------------------------------------

    private fun startOrchestrator(context: Context, reportId: String) {
        orchestratorJobs[reportId]?.cancel()
        appViewModel.updateUiState {
            it.copy(activeSecondaryBatches = it.activeSecondaryBatches + 1)
        }
        val job = appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            try {
                orchestrate(context, reportId)
            } catch (e: Exception) {
                AppLog.w("RegenBatch", "orchestrator crashed for $reportId: ${e.message}")
            } finally {
                appViewModel.updateUiState {
                    it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
                }
            }
        }
        orchestratorJobs[reportId] = job
    }

    private suspend fun orchestrate(context: Context, reportId: String) {
        val phases = RegeneratePhase.values().toList()
        while (true) {
            val current = RegenerateBatchStorage.get(context, reportId) ?: return
            if (current.status != RegenerateJobStatus.RUNNING) return
            val phase = current.currentPhase ?: run {
                // currentPhase is null when DONE — already handled
                // above by the status check, but be defensive.
                markDone(context, reportId)
                return
            }
            val phaseTasks = current.tasks.filter { it.phase == phase }
            if (phaseTasks.isEmpty()) {
                advanceToNextPhase(context, reportId, phase, phases)
                continue
            }
            // 1. Reset EVERY task in this phase to RUNNING (not just WAITING)
            //    — resetRowsForPhase clears every phase row on disk and
            //    dispatchPhase re-fires the whole phase, so on a restart from
            //    PAUSED_ON_ERROR the previously-ERROR task (and already-SUCCESS
            //    siblings) must be re-armed too; otherwise awaitPhaseCompletion
            //    (which only transitions RUNNING tasks) would leave them stuck
            //    showing ❌ / ✅ even after the rerun settles. Clear the prior
            //    end state so the Manage UI shows ⏳.
            mutateJob(context, reportId) { j ->
                j.copy(
                    tasks = j.tasks.map { t ->
                        if (t.phase == phase) {
                            t.copy(
                                state = RegenerateTaskState.RUNNING,
                                startedAt = System.currentTimeMillis(),
                                endedAt = null,
                                errorMessage = null
                            )
                        } else t
                    }
                )
            }
            resetRowsForPhase(context, reportId, phaseTasks, phase)

            // 2. Fire the appropriate dispatcher for this phase.
            dispatchPhase(context, reportId, phase, phaseTasks)

            // 3. Poll disk for each row's terminal state. Halt on
            //    first ERROR.
            val outcome = awaitPhaseCompletion(context, reportId, phase, phaseTasks)
            if (outcome == PhaseOutcome.ERROR) {
                AppLog.i("RegenBatch", "phase $phase paused on error for $reportId")
                return
            }

            // 4. Move to the next phase.
            advanceToNextPhase(context, reportId, phase, phases)
        }
    }

    private enum class PhaseOutcome { SUCCESS, ERROR }

    /** Polls disk every 1500 ms; flips each RUNNING task to
     *  SUCCESS or ERROR based on the underlying row's current
     *  content / errorMessage. Returns ERROR as soon as any
     *  task ends ERROR (also flips the job to PAUSED_ON_ERROR
     *  and persists). Returns SUCCESS when every task in the
     *  phase is terminal (SUCCESS or CANCELLED). */
    private suspend fun awaitPhaseCompletion(
        context: Context, reportId: String,
        phase: RegeneratePhase, phaseTasks: List<RegenerateTask>
    ): PhaseOutcome {
        val rowIds = phaseTasks.map { it.rowId }.toSet()
        val timeoutMs = 30L * 60L * 1000L  // 30 min per phase safety net
        val startedAt = System.currentTimeMillis()
        while (true) {
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                AppLog.w("RegenBatch", "phase $phase timed out for $reportId — pausing")
                pauseOnError(context, reportId, timedOutRowId(context, reportId, phase, rowIds), "Phase timed out")
                return PhaseOutcome.ERROR
            }
            val statuses = readRowStatuses(context, reportId, phase, rowIds)
            mutateJob(context, reportId) { j ->
                j.copy(
                    tasks = j.tasks.map { t ->
                        if (t.phase != phase) return@map t
                        when (val s = statuses[t.rowId]) {
                            is RowStatus.Success -> if (t.state == RegenerateTaskState.RUNNING) {
                                t.copy(state = RegenerateTaskState.SUCCESS,
                                    endedAt = System.currentTimeMillis())
                            } else t
                            is RowStatus.Cancelled -> if (t.state == RegenerateTaskState.RUNNING) {
                                t.copy(state = RegenerateTaskState.CANCELLED,
                                    endedAt = System.currentTimeMillis())
                            } else t
                            is RowStatus.Error -> if (t.state == RegenerateTaskState.RUNNING) {
                                t.copy(state = RegenerateTaskState.ERROR,
                                    endedAt = System.currentTimeMillis(),
                                    errorMessage = s.message)
                            } else t
                            else -> t
                        }
                    }
                )
            }
            val erroredRowId = statuses.entries.firstOrNull { it.value is RowStatus.Error }?.key
            if (erroredRowId != null) {
                pauseOnError(context, reportId, erroredRowId,
                    (statuses[erroredRowId] as? RowStatus.Error)?.message)
                return PhaseOutcome.ERROR
            }
            val allTerminal = rowIds.all { id ->
                val s = statuses[id]
                s is RowStatus.Success || s is RowStatus.Error || s is RowStatus.Cancelled
            }
            if (allTerminal) return PhaseOutcome.SUCCESS
            delay(1500)
        }
    }

    private fun timedOutRowId(
        context: Context,
        reportId: String,
        phase: RegeneratePhase,
        fallbackRowIds: Set<String>
    ): String {
        val job = RegenerateBatchStorage.get(context, reportId)
        return job?.tasks
            ?.firstOrNull { it.phase == phase && it.state == RegenerateTaskState.RUNNING }
            ?.rowId
            ?: fallbackRowIds.first()
    }

    private sealed class RowStatus {
        object Pending : RowStatus()
        object Success : RowStatus()
        object Cancelled : RowStatus()
        data class Error(val message: String?) : RowStatus()
    }

    private fun readRowStatuses(
        context: Context, reportId: String,
        phase: RegeneratePhase, rowIds: Set<String>
    ): Map<String, RowStatus> {
        if (rowIds.isEmpty()) return emptyMap()
        return when (phase) {
            RegeneratePhase.TITLE -> readReportTitleStatus(context, reportId, rowIds)
            RegeneratePhase.ICON -> readReportIconStatus(context, reportId, rowIds)
            RegeneratePhase.LANGUAGE -> readReportLanguageStatus(context, reportId, rowIds)
            RegeneratePhase.AGENTS -> readAgentStatuses(context, reportId, rowIds)
            RegeneratePhase.FAN_META -> readFanMetaStatuses(context, reportId, rowIds)
            else -> readSecondaryStatuses(context, reportId, rowIds)
        }
    }

    private fun readReportIconStatus(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val report = ReportStorage.getReport(context, reportId)
            ?: return rowIds.associateWith { RowStatus.Cancelled }
        return rowIds.associateWith { _ ->
            when {
                !report.iconErrorMessage.isNullOrBlank() -> RowStatus.Error(report.iconErrorMessage)
                !report.icon.isNullOrBlank() -> RowStatus.Success
                else -> RowStatus.Pending
            }
        }
    }

    private fun readReportTitleStatus(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val report = ReportStorage.getReport(context, reportId)
            ?: return rowIds.associateWith { RowStatus.Cancelled }
        return rowIds.associateWith { _ ->
            when {
                !report.titleErrorMessage.isNullOrBlank() -> RowStatus.Error(report.titleErrorMessage)
                !report.titlePromptUsed.isNullOrBlank() -> RowStatus.Success
                else -> RowStatus.Pending
            }
        }
    }

    private fun readReportLanguageStatus(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val report = ReportStorage.getReport(context, reportId)
            ?: return rowIds.associateWith { RowStatus.Cancelled }
        // Language flow is a 2-call chain — the second call sets
        // languageIcon. Treat the row as SUCCESS only after the
        // language-icon call lands (so the user's "everything in
        // this phase is done" reads correctly).
        return rowIds.associateWith { _ ->
            when {
                !report.languageIconErrorMessage.isNullOrBlank() ->
                    RowStatus.Error(report.languageIconErrorMessage)
                !report.languageIcon.isNullOrBlank() -> RowStatus.Success
                else -> RowStatus.Pending
            }
        }
    }

    private fun readAgentStatuses(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val report = ReportStorage.getReport(context, reportId)
            ?: return rowIds.associateWith { RowStatus.Cancelled }
        return rowIds.associateWith { id ->
            val agent = report.agents.firstOrNull { it.agentId == id }
                ?: return@associateWith RowStatus.Cancelled
            when (agent.reportStatus) {
                ReportStatus.SUCCESS ->
                    if (!agent.responseBody.isNullOrBlank()) RowStatus.Success
                    // SUCCESS with a blank body is a state the rest of the app
                    // refuses to mint (SecondaryRunManager maps a blank reply
                    // to STOPPED); treating it as Pending parked the phase for
                    // the full 30-minute timeout. Settle it as a terminal
                    // error so pause-on-error surfaces the row instead.
                    else RowStatus.Error("Empty response")
                ReportStatus.ERROR -> RowStatus.Error(agent.errorMessage)
                else -> RowStatus.Pending
            }
        }
    }

    private fun readSecondaryStatuses(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val rows = SecondaryResultStorage.listForReport(context, reportId)
            .filter { it.id in rowIds }
            .associateBy { it.id }
        return rowIds.associateWith { id ->
            val row = rows[id] ?: return@associateWith RowStatus.Cancelled
            when {
                row.errorMessage != null -> RowStatus.Error(row.errorMessage)
                !row.content.isNullOrBlank() -> RowStatus.Success
                else -> RowStatus.Pending
            }
        }
    }

    /** Per-pair Fan Meta status: one worker call fills both the title
     *  and the icon, so a pair is Error if either errored, Success once
     *  both are present, else Pending. */
    private fun readFanMetaStatuses(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val rows = SecondaryResultStorage.listForReport(context, reportId)
            .filter { it.id in rowIds }
            .associateBy { it.id }
        return rowIds.associateWith { id ->
            val row = rows[id] ?: return@associateWith RowStatus.Cancelled
            when {
                // An emoji landed → usable (partial) success, even if the
                // title call came back empty. Flagging title-missing as a
                // blocking Error here while isRowStillErrored re-checks the
                // same titleErrorMessage made Restart a permanent no-op.
                !row.icon.isNullOrBlank() -> RowStatus.Success
                !row.iconErrorMessage.isNullOrBlank() -> RowStatus.Error(row.iconErrorMessage)
                !row.titleErrorMessage.isNullOrBlank() -> RowStatus.Error(row.titleErrorMessage)
                !row.title.isNullOrBlank() -> RowStatus.Success
                else -> RowStatus.Pending
            }
        }
    }

    // -----------------------------------------------------------------
    // Phase reset + dispatch
    // -----------------------------------------------------------------

    private fun resetRowsForPhase(
        context: Context, reportId: String,
        phaseTasks: List<RegenerateTask>, phase: RegeneratePhase
    ) {
        // NOTE: the reset / clear* helpers below preserve cost
        // fields. The storage layer's additive-cost write on the
        // dispatcher's completion call adds the new call's cost
        // onto whatever's already on disk, so prior runs'
        // expenditure shows up alongside the new run's in the
        // per-row + total cost displays.
        when (phase) {
            RegeneratePhase.TITLE -> {
                ReportStorage.clearReportTitleKeepingCost(context, reportId)
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
            RegeneratePhase.ICON -> {
                ReportStorage.clearReportIconKeepingCost(context, reportId)
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
            RegeneratePhase.LANGUAGE -> {
                ReportStorage.clearReportLanguage(context, reportId)
                appViewModel.updateUiState {
                    it.copy(iconRefreshTick = it.iconRefreshTick + 1)
                }
            }
            RegeneratePhase.AGENTS -> {
                // Reset every agent SYNCHRONOUSLY before the
                // orchestrator starts polling. forceRegenerateAllAgents
                // also resets each agent, but inside an async
                // viewModelScope.launch — so without this
                // synchronous pass the first poll iteration sees
                // the previous run's SUCCESS state and marks every
                // task done in ~2 ms, before the real LLM call has
                // even fired. The *KeepingCost variant preserves
                // prior cost counters so the dispatcher's additive
                // write adds the new call's cost onto the prior.
                phaseTasks.forEach {
                    ReportStorage.resetAgentToPendingKeepingCost(context, reportId, it.rowId)
                }
            }
            RegeneratePhase.FAN_META -> {
                // Per-pair title + icon state (not main row state); keep
                // the pair's content and accrued cost.
                phaseTasks.forEach {
                    SecondaryResultStorage.clearFanOutTitleStateKeepingCost(context, reportId, it.rowId)
                    SecondaryResultStorage.clearFanOutIconStateKeepingCost(context, reportId, it.rowId)
                }
            }
            else -> {
                phaseTasks.forEach {
                    SecondaryResultStorage.resetRowToPlaceholder(context, reportId, it.rowId)
                }
            }
        }
    }


    private suspend fun dispatchPhase(
        context: Context, reportId: String,
        phase: RegeneratePhase, phaseTasks: List<RegenerateTask>
    ) {
        when (phase) {
            RegeneratePhase.TITLE -> {
                val report = ReportStorage.getReport(context, reportId) ?: return
                val ai = appViewModel.uiState.value.aiSettings
                reportViewModel.iconGen.kickOffReportTitleGeneration(
                    context, reportId, report.prompt, ai, thenIcon = false
                )
            }
            RegeneratePhase.ICON -> {
                val report = ReportStorage.getReport(context, reportId) ?: return
                val ai = appViewModel.uiState.value.aiSettings
                reportViewModel.iconGen.kickOffIconGeneration(context, reportId, report.prompt, ai)
            }
            RegeneratePhase.LANGUAGE -> {
                val report = ReportStorage.getReport(context, reportId) ?: return
                val ai = appViewModel.uiState.value.aiSettings
                reportViewModel.iconGen.kickOffLanguageGeneration(context, reportId, report.prompt, ai)
            }
            RegeneratePhase.AGENTS -> {
                reportViewModel.forceRegenerateAllAgents(context, reportId)
            }
            RegeneratePhase.META, RegeneratePhase.FAN_IN -> {
                val rows = SecondaryResultStorage.listForReport(context, reportId)
                    .filter { it.id in phaseTasks.map { t -> t.rowId }.toSet() }
                // RERANK first, JOINED, before everything else: TopRanked-
                // scoped metas resolve their agent subset from the rerank
                // row's content at dispatch time — resetRowsForPhase just
                // blanked it, so firing everything concurrently made
                // extractTopRankedIds(null) fall back to AllReports and
                // silently widen the meta's scope. Same ordering the
                // pre-batch cascade documented ("RERANK first because
                // chat-type META runs may consume it as Top-Ranked scope").
                val (reranks, rest) = rows.partition { it.kind == SecondaryKind.RERANK }
                reranks.mapNotNull { reportViewModel.secondary.resumeStaleMetaPlaceholder(context, reportId, it) }
                    .forEach { it.join() }
                rest.forEach { reportViewModel.secondary.resumeStaleMetaPlaceholder(context, reportId, it) }
            }
            RegeneratePhase.FAN_OUT -> {
                // The engine re-dispatches every stale fan-out pair on the
                // report (the placeholders this phase just reset to PENDING)
                // in one idempotent pass. resetAttempts: this is an explicit
                // user Regenerate, so clear the session retry counts the 30s
                // sweep may have already maxed out — otherwise the pair is
                // terminalized instantly and never re-fires.
                reportViewModel.fanOutEngine.resumeStaleRunsForReport(context, reportId, resetAttempts = true)
            }
            RegeneratePhase.TRANSLATIONS -> {
                val rows = SecondaryResultStorage.listForReport(context, reportId)
                    .filter { it.id in phaseTasks.map { t -> t.rowId }.toSet() }
                val runIds = rows.mapNotNull { it.translationRunId }.distinct()
                runIds.forEach {
                    reportViewModel.translation.startMissingTranslations(context, reportId, it)
                }
            }
            RegeneratePhase.FAN_META -> {
                // resetRowsForPhase already cleared title+icon (keeping
                // cost), so runFanMetaBatch reads the now-empty pairs and
                // re-dispatches additively (one worker call → title+icon).
                val rows = SecondaryResultStorage.listForReport(context, reportId)
                    .filter { it.id in phaseTasks.map { t -> t.rowId }.toSet() }
                val byPrompt = rows.mapNotNull { it.metaPromptId }.distinct()
                byPrompt.forEach { promptId ->
                    reportViewModel.iconGen.runFanMetaBatch(context, reportId, promptId)
                }
            }
            RegeneratePhase.TOURNAMENT -> {
                // resetRowsForPhase cleared every match row to a placeholder;
                // the engine re-dispatches them in one idempotent pass and
                // recomputes the aggregate ranking once they settle.
                reportViewModel.tournamentEngine.resumeStaleRunsForReport(context, reportId, resetAttempts = true)
            }
        }
    }

    // -----------------------------------------------------------------
    // State mutation helpers
    // -----------------------------------------------------------------

    private fun mutateJob(
        context: Context, reportId: String,
        allowTerminalMutation: Boolean = false,
        mutator: (RegenerateJob) -> RegenerateJob
    ): RegenerateJob? {
        // Atomic get→mutate→save under the storage lock so a concurrent
        // cancel/restart can't be clobbered by an orchestrator update built
        // from a stale RUNNING snapshot.
        val updated = RegenerateBatchStorage.update(context, reportId) { current ->
            if (!allowTerminalMutation && current.status.isTerminal()) {
                current
            } else {
                val next = mutator(current)
                if (next == current) current else next.copy(updatedAt = System.currentTimeMillis())
            }
        } ?: return null
        _jobs.update { it + (updated.reportId to updated) }
        return updated
    }

    private fun RegenerateJobStatus.isTerminal(): Boolean =
        this == RegenerateJobStatus.DONE || this == RegenerateJobStatus.CANCELLED

    private fun persist(context: Context, job: RegenerateJob) {
        RegenerateBatchStorage.save(context, job)
        _jobs.update { it + (job.reportId to job) }
    }

    private fun advanceToNextPhase(
        context: Context, reportId: String,
        completed: RegeneratePhase, phases: List<RegeneratePhase>
    ) {
        val next = phases.firstOrNull { it.ordinal > completed.ordinal }
        if (next == null) {
            markDone(context, reportId)
        } else {
            mutateJob(context, reportId) { it.copy(currentPhase = next) }
        }
    }

    private fun markDone(context: Context, reportId: String) {
        mutateJob(context, reportId) {
            it.copy(status = RegenerateJobStatus.DONE, currentPhase = null)
        }
    }

    private fun pauseOnError(
        context: Context, reportId: String,
        rowId: String, message: String?
    ) {
        mutateJob(context, reportId) { j ->
            j.copy(
                status = RegenerateJobStatus.PAUSED_ON_ERROR,
                pausedOnRowId = rowId,
                tasks = j.tasks.map { t ->
                    if (t.rowId == rowId && t.state == RegenerateTaskState.RUNNING) {
                        t.copy(state = RegenerateTaskState.ERROR,
                            endedAt = System.currentTimeMillis(),
                            errorMessage = message)
                    } else t
                }
            )
        }
    }

    private fun isRowStillErrored(
        context: Context, reportId: String,
        job: RegenerateJob, rowId: String
    ): Boolean {
        val task = job.tasks.firstOrNull { it.rowId == rowId } ?: return false
        return when (task.phase) {
            RegeneratePhase.TITLE -> {
                val report = ReportStorage.getReport(context, reportId) ?: return false
                !report.titleErrorMessage.isNullOrBlank()
            }
            RegeneratePhase.ICON -> {
                val report = ReportStorage.getReport(context, reportId) ?: return false
                !report.iconErrorMessage.isNullOrBlank()
            }
            RegeneratePhase.LANGUAGE -> {
                val report = ReportStorage.getReport(context, reportId) ?: return false
                !report.languageIconErrorMessage.isNullOrBlank()
            }
            RegeneratePhase.AGENTS -> {
                val agent = ReportStorage.getReport(context, reportId)
                    ?.agents?.firstOrNull { it.agentId == rowId }
                agent?.reportStatus == ReportStatus.ERROR
            }
            RegeneratePhase.FAN_META -> {
                val row = SecondaryResultStorage.listForReport(context, reportId)
                    .firstOrNull { it.id == rowId }
                // Matches readFanMetaStatuses: an icon present = usable
                // (partial) success, so the row is "still errored" only when
                // there's no icon and a genuine error.
                row != null && row.icon.isNullOrBlank() &&
                    (row.iconErrorMessage != null || row.titleErrorMessage != null)
            }
            else -> {
                val row = SecondaryResultStorage.listForReport(context, reportId)
                    .firstOrNull { it.id == rowId }
                row?.errorMessage != null
            }
        }
    }

    // -----------------------------------------------------------------
    // Task-list construction
    // -----------------------------------------------------------------

    private suspend fun buildTaskList(context: Context, reportId: String): List<RegenerateTask> = withContext(Dispatchers.IO) {
        val report = ReportStorage.getReport(context, reportId) ?: return@withContext emptyList()
        val all = SecondaryResultStorage.listForReport(context, reportId)
        val tasks = mutableListOf<RegenerateTask>()

        val uiState = appViewModel.uiState.value
        val generalSettings = uiState.generalSettings
        val aiSettings = uiState.aiSettings

        // TITLE — short + long report title workers. Run before the
        // icon phase so report/icon can derive from the fresh long title.
        val titlePrompts = aiSettings.internalPrompts.filter {
            it.category == "workers" &&
                (it.name == "report-title-short" || it.name == "report-title-long")
        }
        val titleRunnable = titlePrompts.any { prompt ->
            prompt.workers.any { aiSettings.resolveWorker(it) != null }
        }
        if (generalSettings.reportTitleAiOn() && !report.prompt.isNullOrBlank() && titleRunnable) {
            tasks += RegenerateTask(
                rowId = REPORT_TITLE_ROW_ID,
                phase = RegeneratePhase.TITLE,
                label = "Report title",
                state = RegenerateTaskState.WAITING
            )
        }

        // ICON — main report icon. Match the worker dispatch gates so
        // regenerate doesn't wait on a row that cannot be started.
        val iconPrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-icon"
        }
        val iconRunnable = iconPrompt?.workers?.any { aiSettings.resolveWorker(it) != null } == true
        if (generalSettings.reportIconOn() && !report.prompt.isNullOrBlank() && iconRunnable) {
            tasks += RegenerateTask(
                rowId = REPORT_ICON_ROW_ID,
                phase = RegeneratePhase.ICON,
                label = "Report icon",
                state = RegenerateTaskState.WAITING
            )
        }

        // LANGUAGE — language detection + language-icon flow. Match
        // the language worker's independent dispatch gate.
        val languagePrompt = aiSettings.internalPrompts.firstOrNull {
            it.category == "workers" && it.name == "report-language-name"
        }
        val languageRunnable = languagePrompt?.workers?.any { aiSettings.resolveWorker(it) != null } == true
        if (generalSettings.reportLanguageOn() && !report.prompt.isNullOrBlank() && languageRunnable) {
            tasks += RegenerateTask(
                rowId = REPORT_LANGUAGE_ROW_ID,
                phase = RegeneratePhase.LANGUAGE,
                label = "Language detection",
                state = RegenerateTaskState.WAITING
            )
        }

        // AGENTS — one task per ReportAgent that can actually be
        // re-dispatched. forceRegenerateAllAgents builds its work set via
        // reportToModels, which drops any agent whose provider no longer
        // resolves (removed / renamed). Creating a task for such an agent
        // here would reset its row to PENDING but never re-fire it, hanging
        // the phase to the 30-minute safety-net timeout. Skip them, matching
        // reportToModels.
        for (agent in report.agents) {
            if (com.ai.data.AppService.findById(agent.provider) == null) continue
            tasks += RegenerateTask(
                rowId = agent.agentId,
                phase = RegeneratePhase.AGENTS,
                label = shortModelName(agent.model),
                state = RegenerateTaskState.WAITING
            )
        }

        // META — single-call meta + rerank + moderation (no
        // fan-out source, no fan-in scope).
        val metaRows = all.filter { isMetaPhaseRow(it) }
        for (row in metaRows) {
            tasks += RegenerateTask(
                rowId = row.id,
                phase = RegeneratePhase.META,
                label = labelForSecondary(row),
                state = RegenerateTaskState.WAITING
            )
        }

        // FAN_OUT — fan-out pair rows.
        val fanOutRows = all.filter { it.kind == SecondaryKind.META && it.fanOutSourceAgentId != null }
        for (row in fanOutRows) {
            tasks += RegenerateTask(
                rowId = row.id,
                phase = RegeneratePhase.FAN_OUT,
                label = labelForSecondary(row) + " ← " + shortModelName(row.model),
                state = RegenerateTaskState.WAITING
            )
        }

        // FAN_IN — combined-report rows (fanInOf != null).
        val fanInRows = all.filter {
            it.kind == SecondaryKind.META &&
                it.fanOutSourceAgentId == null &&
                it.fanInOf != null
        }
        for (row in fanInRows) {
            tasks += RegenerateTask(
                rowId = row.id,
                phase = RegeneratePhase.FAN_IN,
                label = labelForSecondary(row),
                state = RegenerateTaskState.WAITING
            )
        }

        // TRANSLATIONS — every TRANSLATE row.
        val translateRows = all.filter { it.kind == SecondaryKind.TRANSLATE }
        for (row in translateRows) {
            tasks += RegenerateTask(
                rowId = row.id,
                phase = RegeneratePhase.TRANSLATIONS,
                label = (row.targetLanguage ?: "translation") + " — " +
                    (row.translateSourceKind?.lowercase() ?: "?"),
                state = RegenerateTaskState.WAITING
            )
        }

        // FAN_META — fan-out pair rows that previously had a title
        // and/or icon (or an error). One task per pair; one worker
        // call regenerates both. Gate on fanMetaOn() (== the master
        // metadata switch) exactly like the dispatcher runFanMetaBatch,
        // which returns null when it's off: without this gate the phase
        // would blank every pair's good title/icon in resetRowsForPhase,
        // never dispatch a worker, and hang to the 30-minute safety-net
        // timeout — the same failure the AGENTS skip-comment guards against.
        val fanMetaRows = fanOutRows.filter {
            !it.icon.isNullOrBlank() || !it.iconErrorMessage.isNullOrBlank() ||
                !it.title.isNullOrBlank() || !it.titleErrorMessage.isNullOrBlank()
        }
        if (generalSettings.fanMetaOn()) {
            for (row in fanMetaRows) {
                tasks += RegenerateTask(
                    rowId = row.id,
                    phase = RegeneratePhase.FAN_META,
                    label = "meta: " + shortModelName(row.model),
                    state = RegenerateTaskState.WAITING
                )
            }
        }

        // TOURNAMENT — one task per per-match row. The AGGREGATE row is
        // recomputed by the dispatcher (no API call), so it isn't a task.
        val tournamentMatchRows = all.filter {
            it.kind == SecondaryKind.TOURNAMENT && it.tournamentRole == "MATCH"
        }
        for (row in tournamentMatchRows) {
            tasks += RegenerateTask(
                rowId = row.id,
                phase = RegeneratePhase.TOURNAMENT,
                label = "tournament match",
                state = RegenerateTaskState.WAITING
            )
        }

        tasks
    }

    private fun isMetaPhaseRow(r: SecondaryResult): Boolean =
        r.kind != SecondaryKind.TRANSLATE &&
            r.kind != SecondaryKind.TOURNAMENT &&
            // JUDGES cells are owned by JudgeEvalEngine, not the regenerate batch.
            r.kind != SecondaryKind.JUDGES &&
            // COMPARE cells are a worker-judged grid owned by CompareEngine
            // (compareAgentId/compareToResultId, no meta-prompt call) — they
            // must NOT be swept into the single-call META resume path.
            r.kind != SecondaryKind.COMPARE &&
            // TRANSRANK score cells + the aggregate ranking row are owned by
            // TranslatorRankEngine. They carry a resolvable metaPromptId, but
            // the rank prompt's @ORIGINAL@/@TRANSLATION@/@LANGUAGE_*@ tokens
            // are only substituted by that engine — the single-call META path
            // would wipe the scores and re-issue the raw template verbatim.
            r.kind != SecondaryKind.TRANSRANK &&
            r.fanOutSourceAgentId == null &&
            r.fanInOf == null

    private fun labelForSecondary(r: SecondaryResult): String {
        val name = r.metaPromptName?.takeIf { it.isNotBlank() }
            ?: r.kind.name.lowercase()
        return name
    }
}
