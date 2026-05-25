package com.ai.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ai.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Orchestrates the Housekeeping → Test "Stress test": SUBMIT one AI report
 *  per Example Prompt using the swarm named "Level 2" — fire-and-forget. The
 *  submit loop returns at once; the reports then generate concurrently in the
 *  background (each on its own coroutine via
 *  [ReportViewModel.submitBackgroundReport]), which is exactly the concurrent
 *  load a stress test wants. The engine itself finishes as soon as everything
 *  is submitted — it does NOT wait for the reports to complete.
 *
 *  Existing runtime data is left untouched — the submitted reports are simply
 *  added alongside whatever is already there. */
class StressTestEngine internal constructor(
    private val appViewModel: AppViewModel,
    private val reportViewModel: ReportViewModel,
) {
    enum class Phase { SUBMITTING, DONE, ERROR }

    data class State(
        val phase: Phase,
        val total: Int = 0,          // number of example prompts submitted
        val errorMessage: String? = null,
    )

    /** Cumulative across every stress run this session — the IDs of every
     *  report any run launched, plus run-count and timing. The Stress Test
     *  Dashboard scopes all its metrics to [reportIds]. Reset clears it. */
    data class TrackedRuns(
        val reportIds: Set<String> = emptySet(),
        val runCount: Int = 0,
        val firstStartedAt: Long = 0L,
        val lastStartedAt: Long = 0L,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _tracked = MutableStateFlow(TrackedRuns())
    val tracked: StateFlow<TrackedRuns> = _tracked.asStateFlow()

    /** Clear cumulative tracking. Reports already launched keep generating —
     *  this only empties what the dashboard counts. */
    fun reset() { _tracked.value = TrackedRuns() }

    @Volatile private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    /** Kick off the stress test. No-op if one is already running. */
    fun start(context: Context) {
        if (isRunning) return
        job?.cancel()
        job = appViewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = appViewModel.uiState.value.aiSettings

                // 1. Validate before submitting anything.
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

                // Record this run in the cumulative tracking the dashboard reads.
                val nowMs = System.currentTimeMillis()
                _tracked.update { t ->
                    t.copy(
                        runCount = t.runCount + 1,
                        firstStartedAt = if (t.firstStartedAt == 0L) nowMs else t.firstStartedAt,
                        lastStartedAt = nowMs,
                    )
                }

                // 2. Submit one report per example prompt — fire-and-forget.
                //    Each runs on its own independent background coroutine;
                //    we do NOT wait for any of them, and existing runtime data
                //    is left untouched. As each report is created we add its id
                //    to the tracked set so the dashboard can scope to this run.
                _state.value = State(Phase.SUBMITTING, total = total)
                AppLog.i("StressTest", "→ start: submitting $total report(s) with swarm '$SWARM_NAME'")
                prompts.forEach { ex ->
                    reportViewModel.submitBackgroundReport(context, ex.text, ex.title, level2.id) { rid ->
                        _tracked.update { it.copy(reportIds = it.reportIds + rid) }
                    }
                }

                _state.value = State(Phase.DONE, total = total)
                AppLog.i("StressTest", "← submitted $total report(s) — generating in the background")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = null
                throw e
            } catch (e: Exception) {
                _state.value = State(Phase.ERROR, errorMessage = e.message ?: "Stress test failed")
                AppLog.w("StressTest", "failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Cancel the (brief) submit loop. Reports already submitted keep
     *  generating in the background — by design they're independent. */
    fun cancel() {
        job?.cancel()
        _state.value = null
    }

    companion object {
        private const val SWARM_NAME = "Level 2"
    }
}
