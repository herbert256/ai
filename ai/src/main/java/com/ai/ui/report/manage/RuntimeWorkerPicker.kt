package com.ai.ui.report.manage

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.Settings
import com.ai.model.Worker
import com.ai.ui.settings.WorkerRowEditor
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

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
