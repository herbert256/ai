package com.ai.ui.report.manage

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ReportStorage
import com.ai.data.ReportWorkerConfig
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.model.Worker
import com.ai.ui.settings.WorkerRowEditor
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.WorkerPlan
import com.ai.viewmodel.workerPlanFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decide what happens the instant a Meta / Fan-out prompt is picked from the
 * second-results hub, honoring the report's "Second result options" toggles
 * (read FRESH from disk — same convention as [launchWithWorkerPlan]).
 *
 * "Select scope" on → set [ReportsScreenState.secondaryScopeMetaPrompt] so the
 * scope screen renders (the existing flow). Off → set the default scope and
 * jump straight past it via [advanceAfterScope]. Branching HERE — rather than
 * auto-skipping the already-rendered scope screen — is deliberate: never set
 * `secondaryScopeMetaPrompt` for a skipped scope, or Android-back would
 * re-render it and the launch would loop (the overlay back-stack rule).
 */
internal fun routeSecondaryPromptLaunch(
    st: ReportsScreenState,
    context: Context,
    scope: CoroutineScope,
    reportId: String,
    prompt: InternalPrompt
) {
    scope.launch(Dispatchers.IO) {
        val cfg = ReportStorage.getReport(context, reportId)?.workerConfig ?: ReportWorkerConfig()
        withContext(Dispatchers.Main) {
            if (cfg.secondResultSelectScope) {
                st.secondaryScopeMetaPrompt.value = prompt
            } else {
                // Scope skipped → there's no scope screen to back into, so
                // drop the fan-out prompt picker (it stays armed for the
                // scope-on path so back returns to it). Harmless for Meta
                // (its picker is already dismissed in onSelectPrompt).
                st.showFanOutPicker.value = false
                st.pendingSecondaryScope.value = com.ai.data.SecondaryScope.AllReports
                st.pendingLanguageScope.value = com.ai.data.SecondaryLanguageScope.AllPresent
                advanceAfterScope(st, prompt, cfg.secondResultRuntimeParams)
            }
        }
    }
}

/**
 * The step after the scope is settled (whether the scope screen ran or was
 * skipped): show the run-time prompt editor when "Runtime parameters" is on,
 * else jump straight to the run. Shared by [routeSecondaryPromptLaunch] and the
 * scope screen's onContinue. Reads only [st]; call on the main thread.
 *
 *  - Meta:    editor → [ReportsScreenState.metaRunScreenPrompt] (MetaRunScreen)
 *             skip   → [ReportsScreenState.secondaryPickerMetaPrompt] (worker plan + run)
 *  - Fan-out: editor → [ReportsScreenState.fanOutConfirmMetaPrompt] (FanOutConfirmScreen)
 *             skip   → [ReportsScreenState.fanOutDirectRunPrompt] (matrix run, engine defaults)
 *
 * The skip targets are exactly the states the editors' own "continue/run"
 * callbacks set, so skipping just runs with the unedited prompt + defaults.
 */
internal fun advanceAfterScope(
    st: ReportsScreenState,
    prompt: InternalPrompt,
    runtimeParams: Boolean
) {
    if (prompt.category == "fan_out") {
        if (runtimeParams) st.fanOutConfirmMetaPrompt.value = prompt
        else st.fanOutDirectRunPrompt.value = prompt
    } else {
        if (runtimeParams) st.metaRunScreenPrompt.value = prompt
        else st.secondaryPickerMetaPrompt.value = prompt
    }
}

/**
 * Type-B batch launch gate shared by every launch site (Meta, Fan-in,
 * Translate, Rerank, Moderation, Tournament, Judges, Compare, TransRank):
 * maps the report's Worker-batches mode (+ the driving prompt's own
 * *SELECT setting) onto either the runtime worker picker or a straight
 * dispatch, via [com.ai.viewmodel.workerPlanFor].
 *
 * The config is read FRESH from disk on every launch — never from a
 * composition-cached snapshot — so a SELECT_ONCE group persisted by an
 * earlier batch (from any screen) is always seen and the picker is not
 * re-opened. [scope] must be a screen-level scope that outlives the
 * picker overlay (the overlay's early return unmounts ReportRunScreen,
 * so a scope remembered there would be cancelled before the user
 * confirms — pass [ReportsScreenState.screenScope]).
 *
 * SELECT_ONCE first pick: the group is persisted via
 * [ReportStorage.setBatchWorkersIfEmpty] BEFORE the dispatch, and [run]
 * receives the setter's return value — first write wins, so two
 * concurrent first launches can never run two different groups. A
 * [WorkerPlan.Resolved] dispatches with null override — the engine-side
 * withBatchWorkers resolves the pool.
 */
internal fun launchWithWorkerPlan(
    runtimeWorkerPick: MutableState<RuntimeWorkerPick?>,
    context: Context,
    scope: CoroutineScope,
    reportId: String,
    driver: InternalPrompt?,
    pickTitle: String,
    /** Rerank / Moderation pass true: they ignore the report's Worker-batches
     *  choice and always follow the prompt's own workers. */
    alwaysPromptWorkers: Boolean = false,
    /** Meta / Fan-in pass true: read + persist the separate meta-* routing. */
    meta: Boolean = false,
    run: (List<Worker>?) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        val cfg = ReportStorage.getReport(context, reportId)?.workerConfig ?: ReportWorkerConfig()
        val plan = workerPlanFor(cfg, driver, alwaysPromptWorkers, meta)
        withContext(Dispatchers.Main) {
            when (plan) {
                is WorkerPlan.NeedsPick -> runtimeWorkerPick.value = RuntimeWorkerPick(
                    titleText = if (plan.persistOnPick) "$pickTitle — used for every batch" else pickTitle,
                    initial = plan.initial,
                    onConfirm = { picked ->
                        if (plan.persistOnPick) {
                            scope.launch(Dispatchers.IO) {
                                val effective = ReportStorage.setBatchWorkersIfEmpty(context, reportId, picked, meta)
                                withContext(Dispatchers.Main) { run(effective) }
                            }
                        } else run(picked)
                    },
                    onCancel = {}
                )
                WorkerPlan.Resolved -> run(null)
            }
        }
    }
}

/**
 * Run-time worker picker shown (full-screen) when a *SELECT Internal Prompt is
 * about to run — the legacy "ask for workers before this starts" behaviour.
 * Reuses the same +Agent/+Flock/+Swarm/+Model row editor as the Internal-Prompt
 * worker editor ([WorkerRowEditor]); pre-seeded with the prompt's configured
 * chain. The choice is never persisted — it drives only this one run.
 */
@Composable
internal fun RuntimeWorkerPickerScreen(
    titleText: String,
    initial: List<Worker>,
    aiSettings: Settings,
    onConfirm: (List<Worker>) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var workers by remember { mutableStateOf(initial) }
    val agentNames = remember(aiSettings) { aiSettings.agents.map { it.name } }
    // At least one worker must resolve to a runnable model — mirrors the
    // engines' pre-flight (e.g. TournamentEngine "no resolvable workers").
    val canRun = workers.any { aiSettings.resolveWorker(it) != null }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "internal_prompt_edit",
            title = titleText,
            subject = "Pick the workers for this run",
            onBackClick = onBack
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onConfirm(workers) },
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Run", maxLines = 1, softWrap = false) }
        Spacer(Modifier.height(8.dp))
        Text(
            "Add one or more workers (Agent / Provider+Model / Flock / Swarm). This run uses these instead of the prompt's configured workers.",
            fontSize = 12.sp, color = AppColors.TextTertiary
        )
        if (workers.isEmpty()) {
            Text("No workers yet — add at least one.", fontSize = 12.sp, color = AppColors.TextDim)
        }
        workers.forEachIndexed { idx, w ->
            WorkerRowEditor(
                index = idx,
                worker = w,
                agentNames = agentNames,
                aiSettings = aiSettings,
                onChange = { nw -> workers = workers.toMutableList().also { it[idx] = nw } },
                onRemove = { workers = workers.toMutableList().also { it.removeAt(idx) } }
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { workers = workers + Worker(agent = "*select") },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("+ Add worker", fontSize = 13.sp) }
        Spacer(Modifier.height(16.dp))
    }
}
