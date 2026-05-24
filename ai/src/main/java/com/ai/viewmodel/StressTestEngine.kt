package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Orchestrates the Housekeeping → Test "Stress test": wipe all runtime
 *  data, then generate one AI report per Example Prompt using the swarm
 *  named "Level 2", strictly SEQUENTIALLY (each report fully completes
 *  before the next starts). Pure orchestration over existing pieces —
 *  no new pipeline code: [AppViewModel.clearAllRuntimeData],
 *  [ReportViewModel.generateGenericReports] + [ReportViewModel.awaitReportGeneration].
 *
 *  Config (swarm + example prompts) is validated BEFORE the destructive
 *  wipe, so a misconfiguration surfaces an error and leaves runtime data
 *  intact. */
class StressTestEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel,
) {
    enum class Phase { CLEARING, GENERATING, DONE, ERROR }

    data class State(
        val phase: Phase,
        val current: Int = 0,        // 1-based index of the report in flight
        val total: Int = 0,          // number of example prompts
        val currentTitle: String = "",
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    /** Kick off the stress test. No-op if one is already running. */
    fun start(context: Context) {
        if (isRunning) return
        job?.cancel()
        job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = appViewModel.uiState.value.aiSettings

                // 1. Validate BEFORE the destructive wipe.
                val level2 = settings.swarms.find { it.name == SWARM_NAME }
                if (level2 == null || level2.members.none { settings.isProviderActive(it.provider) }) {
                    _state.value = State(
                        Phase.ERROR,
                        errorMessage = "No swarm \"$SWARM_NAME\" with active models — configure it first."
                    )
                    return@launch
                }
                val prompts = settings.examplePrompts
                if (prompts.isEmpty()) {
                    _state.value = State(Phase.ERROR, errorMessage = "No Example Prompts configured.")
                    return@launch
                }
                val total = prompts.size

                // 2. Wipe runtime data (mirrors Housekeeping → Reset → Clear
                //    runtime data, incl. the in-memory test-run reset).
                _state.value = State(Phase.CLEARING, total = total)
                AppLog.i("StressTest", "→ start: clearing runtime data, then $total report(s) with swarm '$SWARM_NAME'")
                appViewModel.clearAllRuntimeData(context)
                reportViewModel.modelTestEngine.clearRun()

                // 3. One report per example prompt, strictly sequential.
                prompts.forEachIndexed { i, ex ->
                    if (!isActive) return@launch
                    _state.value = State(
                        Phase.GENERATING, current = i + 1, total = total, currentTitle = ex.title
                    )
                    appViewModel.updateUiState {
                        it.copy(genericPromptText = ex.text, genericPromptTitle = ex.title)
                    }
                    reportViewModel.generateGenericReports(
                        context, selectedAgentIds = emptySet(), selectedSwarmIds = setOf(level2.id)
                    )
                    reportViewModel.awaitReportGeneration()   // the sequential gate
                    // Drop the standard per-report progress dialog flag so it
                    // doesn't linger over the Stress test screen between runs.
                    appViewModel.updateUiState { it.copy(showGenericReportsDialog = false) }
                    AppLog.i("StressTest", "← report ${i + 1}/$total done: ${ex.title}")
                    // Continue through all: an errored report does not halt the run.
                }

                _state.value = State(Phase.DONE, current = total, total = total)
                AppLog.i("StressTest", "← end: $total report(s) generated")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = null
                throw e
            } catch (e: Exception) {
                _state.value = State(Phase.ERROR, errorMessage = e.message ?: "Stress test failed")
                AppLog.w("StressTest", "failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Stop a running stress test and the report it's currently generating. */
    fun cancel() {
        job?.cancel()
        reportViewModel.cancelReportGeneration()
        _state.value = null
    }

    companion object {
        private const val SWARM_NAME = "Level 2"
    }
}
