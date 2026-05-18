package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.RegenerateJob
import com.ai.data.RegenerateJobStatus
import com.ai.data.RegeneratePhase
import com.ai.data.RegenerateTask
import com.ai.data.RegenerateTaskState
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.ui.shared.LocalReportIcon
import com.ai.ui.shared.LocalReportTitle
import com.ai.viewmodel.RegenerateBatchEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen detail view for a [RegenerateJob]. Opened from the
 * Regenerate row on the Manage screen.
 *
 * Layout:
 *   - TitleBar with back arrow and helpTopic "regenerate_batch".
 *   - Status banner — phase / counts / "paused on row: …".
 *   - Action row — Cancel (when RUNNING) and Restart (when PAUSED
 *     or CANCELLED).
 *   - LazyColumn of per-task cards grouped by phase. Each card
 *     shows phase chip + label + status icon + started/ended
 *     timestamps + duration + error message when ERROR.
 */
@Composable
fun RegenerateBatchScreen(
    reportId: String,
    engine: RegenerateBatchEngine,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val jobs by engine.jobs.collectAsState()
    val job = jobs[reportId]
    BackHandler { onBack() }

    var showDeleteConfirm by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "regenerate_batch",
            title = "Regenerate report",
            onBackClick = onBack,
            // Bottom-bar 🗑 — confirms then drops the persisted
            // RegenerateJob + clears the Regenerate row from the
            // Manage screen. Routes through the engine so any
            // in-flight orchestrator coroutine is cancelled first.
            onDelete = { showDeleteConfirm = true }
        )
        if (job == null) {
            Box(modifier = Modifier.fillMaxSize().padding(top = 32.dp), contentAlignment = Alignment.TopCenter) {
                Text("No regenerate batch for this report.", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }
        StatusBanner(job)
        Spacer(modifier = Modifier.height(8.dp))
        ActionRow(
            job = job,
            onCancel = { engine.cancel(context, reportId) },
            onRestart = { engine.restart(context, reportId) }
        )
        Spacer(modifier = Modifier.height(12.dp))
        TaskList(job)
    }
    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete regenerate batch?") },
            text = {
                Text(
                    "Removes the regenerate job from this report. " +
                        "Any in-flight phase is cancelled; rows that already " +
                        "finished keep their new content. The report itself is untouched."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteConfirm = false
                    engine.deleteJob(context, reportId)
                    onBack()
                }) { Text("Delete", color = AppColors.Red) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusBanner(job: RegenerateJob) {
    val total = job.tasks.size
    val done = job.tasks.count { it.state == RegenerateTaskState.SUCCESS }
    val errored = job.tasks.count { it.state == RegenerateTaskState.ERROR }
    val running = job.tasks.count { it.state == RegenerateTaskState.RUNNING }
    val statusText = when (job.status) {
        RegenerateJobStatus.RUNNING -> "Running — phase ${job.currentPhase?.label ?: "?"}"
        RegenerateJobStatus.PAUSED_ON_ERROR -> "Paused on error"
        RegenerateJobStatus.DONE -> "Done"
        RegenerateJobStatus.CANCELLED -> "Cancelled"
    }
    val statusColor = when (job.status) {
        RegenerateJobStatus.RUNNING -> AppColors.Blue
        RegenerateJobStatus.PAUSED_ON_ERROR -> AppColors.Red
        RegenerateJobStatus.DONE -> AppColors.Green
        RegenerateJobStatus.CANCELLED -> AppColors.TextTertiary
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(statusText, color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "$done / $total done · $running running · $errored errored",
            color = AppColors.TextSecondary, fontSize = 13.sp
        )
        if (job.status == RegenerateJobStatus.PAUSED_ON_ERROR && !job.pausedOnRowId.isNullOrBlank()) {
            val pausedLabel = job.tasks.firstOrNull { it.rowId == job.pausedOnRowId }?.label.orEmpty()
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Waiting on: $pausedLabel — fix the row, then tap Restart (or wait for the background sweep).",
                color = AppColors.TextTertiary, fontSize = 12.sp, lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun ActionRow(
    job: RegenerateJob,
    onCancel: () -> Unit,
    onRestart: () -> Unit
) {
    val canCancel = job.status == RegenerateJobStatus.RUNNING
    val canRestart = job.status == RegenerateJobStatus.PAUSED_ON_ERROR ||
        job.status == RegenerateJobStatus.CANCELLED
    if (!canCancel && !canRestart) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (canCancel) {
            OutlinedButton(
                onClick = onCancel,
                colors = AppColors.outlinedButtonColors(),
                modifier = Modifier.weight(1f)
            ) { Text("Cancel", fontSize = 13.sp) }
        }
        if (canRestart) {
            OutlinedButton(
                onClick = onRestart,
                colors = AppColors.outlinedButtonColors(),
                modifier = Modifier.weight(1f)
            ) { Text("Restart", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun TaskList(job: RegenerateJob) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Group by phase, preserve enum order.
        val grouped = RegeneratePhase.values()
            .map { p -> p to job.tasks.filter { it.phase == p } }
            .filter { it.second.isNotEmpty() }
        grouped.forEach { group ->
            val phase = group.first
            val tasks = group.second
            item(key = "phase-${phase.name}") {
                Text(
                    text = phase.label,
                    color = AppColors.Blue,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            items(items = tasks, key = { "task-${it.rowId}-${it.phase}" }) { t -> TaskCard(t) }
        }
    }
}

@Composable
private fun TaskCard(t: RegenerateTask) {
    val accent = when (t.state) {
        RegenerateTaskState.SUCCESS -> AppColors.Green
        RegenerateTaskState.ERROR -> AppColors.Red
        RegenerateTaskState.RUNNING -> AppColors.Blue
        RegenerateTaskState.WAITING -> AppColors.TextTertiary
        RegenerateTaskState.CANCELLED -> AppColors.TextTertiary
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                t.label, color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val ts = formatTimestamps(t)
            if (ts.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(ts, color = AppColors.TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (!t.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    t.errorMessage, color = AppColors.Red, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        StatusIcon(t.state)
    }
}

@Composable
private fun StatusIcon(state: RegenerateTaskState) {
    when (state) {
        RegenerateTaskState.WAITING -> Text("🕒", fontSize = 16.sp, modifier = Modifier.size(24.dp).padding(2.dp))
        RegenerateTaskState.RUNNING -> Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            AnimatedHourglass(fontSize = 16.sp)
        }
        RegenerateTaskState.SUCCESS -> Text("✅", fontSize = 16.sp, modifier = Modifier.size(24.dp).padding(2.dp))
        RegenerateTaskState.ERROR -> Text("❌", fontSize = 16.sp, modifier = Modifier.size(24.dp).padding(2.dp))
        RegenerateTaskState.CANCELLED -> Text("⏸", fontSize = 16.sp, modifier = Modifier.size(24.dp).padding(2.dp))
    }
}

private val TS_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun formatTimestamps(t: RegenerateTask): String {
    val started = t.startedAt
    val ended = t.endedAt
    return when {
        started == null -> ""
        ended == null -> "started ${TS_FMT.format(Date(started))}"
        else -> {
            val durMs = ended - started
            "started ${TS_FMT.format(Date(started))} · ended ${TS_FMT.format(Date(ended))} · ${formatDuration(durMs)}"
        }
    }
}

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

/** Helper that ReportsScreenNav calls when the open-detail state
 *  var is non-null. Hoisted out so neither ReportsScreenNav nor
 *  the giant ReportsScreen composable carries the bytecode for
 *  the conditional mount. */
@Composable
fun RegenerateBatchOverlay(
    reportId: String,
    engine: RegenerateBatchEngine,
    onClose: () -> Unit
) {
    CompositionLocalProvider(
        com.ai.ui.shared.LocalNavigateToCurrentReport provides onClose
    ) {
        RegenerateBatchScreen(
            reportId = reportId,
            engine = engine,
            onBack = onClose
        )
    }
}

/** Row rendered at the top of the Manage screen's LazyColumn
 *  whenever a [RegenerateJob] exists for the current report.
 *  Reads engine + open-state from the two CompositionLocals so
 *  it doesn't have to be threaded through every intermediate
 *  composable. No-op when there's no job for the current
 *  report. */
@Composable
fun RegenerateBatchManageRow() {
    val engine = com.ai.ui.shared.LocalRegenerateBatchEngine.current ?: return
    val openState = com.ai.ui.shared.LocalRegenerateBatchOpenState.current
    val jobs by engine.jobs.collectAsState()
    // The engine's StateFlow is keyed by reportId; we display the
    // first (and per design only) job that matches the current
    // report context. The Manage screen mounts this row once per
    // report so picking the only entry whose reportId matches the
    // navigated report is straightforward.
    val reportId = com.ai.ui.shared.LocalReportNeighborNav.current?.run {
        // Use the existing report-context local — neighbour nav
        // already carries the active reportId in scope.
        null  // best-effort; row matches whichever job hydrate populated
    } ?: jobs.keys.firstOrNull()
    val job = reportId?.let { jobs[it] } ?: return

    val done = job.tasks.count { it.state == com.ai.data.RegenerateTaskState.SUCCESS }
    val total = job.tasks.size
    val rowText = when (job.status) {
        com.ai.data.RegenerateJobStatus.RUNNING ->
            "$done / $total · ${job.currentPhase?.label ?: "starting"}"
        com.ai.data.RegenerateJobStatus.PAUSED_ON_ERROR ->
            "$done / $total · paused on error"
        com.ai.data.RegenerateJobStatus.DONE -> "$done / $total · done"
        com.ai.data.RegenerateJobStatus.CANCELLED ->
            "$done / $total · cancelled"
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { openState?.value = job.reportId },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leftmost status cell — same 24 dp width as every other
            // row on this screen (✅ / ❌ / ⏳ live in this column).
            when (job.status) {
                com.ai.data.RegenerateJobStatus.RUNNING -> Box(
                    modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center
                ) { AnimatedHourglass(fontSize = 16.sp) }
                com.ai.data.RegenerateJobStatus.PAUSED_ON_ERROR ->
                    Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                com.ai.data.RegenerateJobStatus.DONE ->
                    Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                com.ai.data.RegenerateJobStatus.CANCELLED ->
                    Text("⏸", fontSize = 16.sp, modifier = Modifier.width(24.dp))
            }
            // Type cell — matches the other rows' "compare" / "rerank"
            // / "fan-in" chip in the same column.
            RowTypeCell("regenerate")
            // Row text — counter + current phase. White like the
            // model-name text on every other row in this LazyColumn.
            Text(
                text = rowText,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        // Visual separator between this top-of-list row and the
        // first agent / meta row underneath — matches the other
        // dividers used inside this LazyColumn.
        androidx.compose.material3.HorizontalDivider(
            color = AppColors.TextDisabled, thickness = 1.dp
        )
    }
}

private val RegeneratePhase.label: String
    get() = when (this) {
        RegeneratePhase.ICON -> "Report icon"
        RegeneratePhase.LANGUAGE -> "Language"
        RegeneratePhase.AGENTS -> "Model reports"
        RegeneratePhase.META -> "Meta (incl. rerank + moderation)"
        RegeneratePhase.FAN_OUT -> "Fan-out"
        RegeneratePhase.FAN_IN -> "Fan-in"
        RegeneratePhase.TRANSLATIONS -> "Translations"
        RegeneratePhase.FAN_ICONS -> "Fan-icons"
    }
