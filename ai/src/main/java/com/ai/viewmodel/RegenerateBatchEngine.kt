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
import com.ai.data.ReportStorage
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.shortModelName
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            orchestratorJobs[reportId]?.cancel()
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
    fun restart(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            val job = RegenerateBatchStorage.get(context, reportId) ?: return@launch
            if (job.status == RegenerateJobStatus.DONE) return@launch
            if (job.status == RegenerateJobStatus.RUNNING &&
                orchestratorJobs[reportId]?.isActive == true) {
                return@launch  // already running
            }
            if (job.status == RegenerateJobStatus.PAUSED_ON_ERROR) {
                // Only resume if the row that paused us is no longer
                // in an error state on disk. The user may have hit
                // Restart prematurely — in that case the orchestrator
                // would just hit the same error again, so bail.
                val pausedRowId = job.pausedOnRowId
                if (pausedRowId != null && isRowStillErrored(context, reportId, job, pausedRowId)) {
                    AppLog.d("RegenBatch", "restart no-op: row $pausedRowId still errored")
                    return@launch
                }
            }
            val resumed = job.copy(
                status = RegenerateJobStatus.RUNNING,
                pausedOnRowId = null,
                updatedAt = System.currentTimeMillis()
            )
            persist(context, resumed)
            startOrchestrator(context, reportId)
        }
    }

    /** User clicked Cancel on the detail screen. Stops the
     *  orchestrator — already-in-flight HTTP calls finish
     *  themselves and persist as normal. */
    fun cancel(context: Context, reportId: String) {
        appViewModel.viewModelScope.launch(reportViewModel.reportLogContext(reportId)) {
            orchestratorJobs.remove(reportId)?.cancel()
            val job = RegenerateBatchStorage.get(context, reportId) ?: return@launch
            if (job.status == RegenerateJobStatus.DONE ||
                job.status == RegenerateJobStatus.CANCELLED) return@launch
            persist(context, job.copy(
                status = RegenerateJobStatus.CANCELLED,
                updatedAt = System.currentTimeMillis()
            ))
            appViewModel.updateUiState {
                it.copy(activeSecondaryBatches = (it.activeSecondaryBatches - 1).coerceAtLeast(0))
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
                val resumed = job.copy(
                    status = RegenerateJobStatus.RUNNING,
                    pausedOnRowId = null,
                    updatedAt = System.currentTimeMillis()
                )
                persist(context, resumed)
                startOrchestrator(context, reportId)
            }
        }
    }

    /** Drop the persisted job + in-memory entry. Used by the
     *  detail screen's "delete" action (future). */
    fun deleteJob(context: Context, reportId: String) {
        orchestratorJobs.remove(reportId)?.cancel()
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
            // 1. Reset every WAITING task in this phase to RUNNING
            //    (and reset the underlying row on disk so the
            //    Manage UI shows ⏳).
            mutateJob(context, reportId) { j ->
                j.copy(
                    tasks = j.tasks.map { t ->
                        if (t.phase == phase && t.state == RegenerateTaskState.WAITING) {
                            t.copy(state = RegenerateTaskState.RUNNING, startedAt = System.currentTimeMillis())
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
                pauseOnError(context, reportId, rowIds.first(), "Phase timed out")
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
                s is RowStatus.Success || s is RowStatus.Error
            }
            if (allTerminal) return PhaseOutcome.SUCCESS
            delay(1500)
        }
    }

    private sealed class RowStatus {
        object Pending : RowStatus()
        object Success : RowStatus()
        data class Error(val message: String?) : RowStatus()
    }

    private fun readRowStatuses(
        context: Context, reportId: String,
        phase: RegeneratePhase, rowIds: Set<String>
    ): Map<String, RowStatus> {
        if (rowIds.isEmpty()) return emptyMap()
        return when (phase) {
            RegeneratePhase.ICON -> readReportIconStatus(context, reportId, rowIds)
            RegeneratePhase.LANGUAGE -> readReportLanguageStatus(context, reportId, rowIds)
            RegeneratePhase.AGENTS -> readAgentStatuses(context, reportId, rowIds)
            RegeneratePhase.FAN_ICONS -> readFanIconsStatuses(context, reportId, rowIds)
            else -> readSecondaryStatuses(context, reportId, rowIds)
        }
    }

    private fun readReportIconStatus(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val report = ReportStorage.getReport(context, reportId) ?: return emptyMap()
        return rowIds.associateWith { _ ->
            when {
                !report.iconErrorMessage.isNullOrBlank() -> RowStatus.Error(report.iconErrorMessage)
                !report.icon.isNullOrBlank() -> RowStatus.Success
                else -> RowStatus.Pending
            }
        }
    }

    private fun readReportLanguageStatus(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val report = ReportStorage.getReport(context, reportId) ?: return emptyMap()
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
        val report = ReportStorage.getReport(context, reportId) ?: return emptyMap()
        return rowIds.associateWith { id ->
            val agent = report.agents.firstOrNull { it.agentId == id }
                ?: return@associateWith RowStatus.Pending
            when (agent.reportStatus) {
                ReportStatus.SUCCESS ->
                    if (!agent.responseBody.isNullOrBlank()) RowStatus.Success
                    else RowStatus.Pending
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
            val row = rows[id] ?: return@associateWith RowStatus.Pending
            when {
                row.errorMessage != null -> RowStatus.Error(row.errorMessage)
                !row.content.isNullOrBlank() -> RowStatus.Success
                else -> RowStatus.Pending
            }
        }
    }

    private fun readFanIconsStatuses(
        context: Context, reportId: String, rowIds: Set<String>
    ): Map<String, RowStatus> {
        val rows = SecondaryResultStorage.listForReport(context, reportId)
            .filter { it.id in rowIds }
            .associateBy { it.id }
        return rowIds.associateWith { id ->
            val row = rows[id] ?: return@associateWith RowStatus.Pending
            when {
                !row.iconErrorMessage.isNullOrBlank() -> RowStatus.Error(row.iconErrorMessage)
                !row.icon.isNullOrBlank() -> RowStatus.Success
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
            RegeneratePhase.FAN_ICONS -> {
                // Per-pair icon state, not main row state.
                phaseTasks.forEach {
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


    private fun dispatchPhase(
        context: Context, reportId: String,
        phase: RegeneratePhase, phaseTasks: List<RegenerateTask>
    ) {
        when (phase) {
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
                rows.forEach { reportViewModel.resumeStaleMetaPlaceholder(context, reportId, it) }
            }
            RegeneratePhase.FAN_OUT -> {
                val rows = SecondaryResultStorage.listForReport(context, reportId)
                    .filter { it.id in phaseTasks.map { t -> t.rowId }.toSet() }
                val aiSettings = appViewModel.uiState.value.aiSettings
                val byPrompt = rows.mapNotNull { it.metaPromptId }.distinct()
                byPrompt.forEach { promptId ->
                    val prompt = aiSettings.internalPrompts.firstOrNull { it.id == promptId }
                        ?: return@forEach
                    reportViewModel.resumeStaleFanOutPairs(context, reportId, prompt)
                }
            }
            RegeneratePhase.TRANSLATIONS -> {
                val rows = SecondaryResultStorage.listForReport(context, reportId)
                    .filter { it.id in phaseTasks.map { t -> t.rowId }.toSet() }
                val runIds = rows.mapNotNull { it.translationRunId }.distinct()
                runIds.forEach {
                    reportViewModel.translation.startMissingTranslations(context, reportId, it)
                }
            }
            RegeneratePhase.FAN_ICONS -> {
                // Skip relaunchFanIconsBatch — it would re-zero the
                // icon cost via clearFanOutIconState. The engine
                // already cleared icon+tier (preserving cost) in
                // resetRowsForPhase; runFanIconsBatch reads the
                // now-icon-less rows and dispatches the chain
                // additively (bumpFanOutIconCost is already
                // additive at the storage layer).
                val rows = SecondaryResultStorage.listForReport(context, reportId)
                    .filter { it.id in phaseTasks.map { t -> t.rowId }.toSet() }
                val byPrompt = rows.mapNotNull { it.metaPromptId }.distinct()
                byPrompt.forEach { promptId ->
                    reportViewModel.iconGen.runFanIconsBatch(context, reportId, promptId)
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // State mutation helpers
    // -----------------------------------------------------------------

    private fun mutateJob(
        context: Context, reportId: String,
        mutator: (RegenerateJob) -> RegenerateJob
    ): RegenerateJob? {
        val current = RegenerateBatchStorage.get(context, reportId) ?: return null
        val updated = mutator(current).copy(updatedAt = System.currentTimeMillis())
        persist(context, updated)
        return updated
    }

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
            RegeneratePhase.FAN_ICONS -> {
                val row = SecondaryResultStorage.listForReport(context, reportId)
                    .firstOrNull { it.id == rowId }
                row?.iconErrorMessage != null
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

        // ICON — main report icon (only when iconGenEnabled is on
        // AND the report has a prompt — same prerequisite the
        // existing kickOffIconGeneration call has). Skipped
        // otherwise so the engine doesn't spin on a row that's
        // never going to land.
        if (appViewModel.uiState.value.generalSettings.iconGenEnabled &&
            !report.prompt.isNullOrBlank()
        ) {
            tasks += RegenerateTask(
                rowId = REPORT_ICON_ROW_ID,
                phase = RegeneratePhase.ICON,
                label = "Report icon",
                state = RegenerateTaskState.WAITING
            )
        }

        // LANGUAGE — language detection + language-icon flow.
        // Same gate as ICON; both are driven by iconGenEnabled.
        if (appViewModel.uiState.value.generalSettings.iconGenEnabled &&
            !report.prompt.isNullOrBlank()
        ) {
            tasks += RegenerateTask(
                rowId = REPORT_LANGUAGE_ROW_ID,
                phase = RegeneratePhase.LANGUAGE,
                label = "Language detection",
                state = RegenerateTaskState.WAITING
            )
        }

        // AGENTS — one task per ReportAgent.
        for (agent in report.agents) {
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

        // FAN_IN — combined-report rows (fanInOf != null) AND
        // model-scoped fan-in (scopeProviderId != null).
        val fanInRows = all.filter {
            it.kind == SecondaryKind.META &&
                it.fanOutSourceAgentId == null &&
                (it.fanInOf != null || it.scopeProviderId != null)
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

        // FAN_ICONS — fan-out pair rows that previously had an
        // icon (or icon error). Skip pairs that never produced
        // content (the icon chain can't run on them).
        val fanIconsRows = fanOutRows.filter {
            !it.icon.isNullOrBlank() || !it.iconErrorMessage.isNullOrBlank()
        }
        for (row in fanIconsRows) {
            tasks += RegenerateTask(
                rowId = row.id,
                phase = RegeneratePhase.FAN_ICONS,
                label = "icon: " + shortModelName(row.model),
                state = RegenerateTaskState.WAITING
            )
        }

        tasks
    }

    private fun isMetaPhaseRow(r: SecondaryResult): Boolean =
        r.kind != SecondaryKind.TRANSLATE &&
            r.fanOutSourceAgentId == null &&
            r.fanInOf == null &&
            r.scopeProviderId == null &&
            r.scopeModel == null

    private fun labelForSecondary(r: SecondaryResult): String {
        val name = r.metaPromptName?.takeIf { it.isNotBlank() }
            ?: r.kind.name.lowercase()
        return name
    }
}
