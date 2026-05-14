package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ReloadConfirmationDialog
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.launch

/** Aggregate stats for one model's slice of a translation run. */
private data class TranslationModelRow(
    val modelKey: String,
    val total: Int,
    val done: Int,
    val err: Int,
    val running: Int,
    val cost: Double
)

/**
 * L1 of the translation run drill-in: the *models* that picked up
 * work in this run, with a stats panel, failure controls, and a
 * top progress bar. Each model row carries a two-segment background
 * bar showing that model's share of the whole run (green = done,
 * red = errored). Tapping a model opens L2.
 *
 * Unassigned PENDING items (the work queue doesn't pre-assign) have
 * no model yet — they're counted only in the "Queue" stat, never a
 * row.
 */
@Composable
internal fun TranslationL1Screen(
    run: ReportViewModel.TranslationRunState,
    reportId: String,
    runId: String,
    actions: TranslationActions,
    onBumpRefresh: () -> Unit,
    onOpenModel: (String) -> Unit,
    onBack: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    var confirmRestartFailed by remember { mutableStateOf(false) }
    var confirmRemoveFailed by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val subject = run.targetLanguageName
    val items = run.items
    val total = items.size
    val doneCount = items.count { it.status == ReportViewModel.TranslationStatus.DONE }
    val errorCount = items.count { it.status == ReportViewModel.TranslationStatus.ERROR }
    val runningCount = items.count { it.status == ReportViewModel.TranslationStatus.RUNNING }
    val queuedCount = items.count { it.status == ReportViewModel.TranslationStatus.PENDING }
    // Whole run finished cleanly — drop the per-row glyph + fill so a
    // completed run reads calmly instead of as a wall of check marks.
    val allDone = total > 0 && doneCount == total

    // Group items by the model that handled them. Unassigned PENDING
    // items (providerId/model still null) drop out — they show only
    // in the Queue stat.
    val modelRows = remember(items) {
        items.mapNotNull { item -> translationModelKey(item)?.let { it to item } }
            .groupBy({ it.first }, { it.second })
            .map { (key, its) ->
                TranslationModelRow(
                    modelKey = key,
                    total = its.size,
                    done = its.count { it.status == ReportViewModel.TranslationStatus.DONE },
                    err = its.count { it.status == ReportViewModel.TranslationStatus.ERROR },
                    running = its.count { it.status == ReportViewModel.TranslationStatus.RUNNING },
                    cost = its.sumOf { it.costDollars }
                )
            }
            .sortedWith(
                compareBy(
                    { r ->
                        when {
                            r.running > 0 -> 0
                            r.done == r.total -> 2
                            r.err > 0 -> 1
                            else -> 0
                        }
                    },
                    { it.modelKey.substringAfter('|').lowercase() }
                )
            )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "translation_run_l1",
            title = "Translation",
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = subject,
            onBackClick = onBack,
            onReload = { confirmReload = true },
            onTrace = if (ApiTracer.isTracingEnabled) actions.onNavigateToTraceList else null,
            onDelete = { confirmDelete = true }
        )
        if (com.ai.ui.shared.LocalSubjectToTitleBarMode.current == com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED) {
            Text(
                text = subject,
                fontSize = 18.sp, color = AppColors.Green,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Stats panel — pinned at the top, kept visible even once the
        // whole run is done. Translations expose no throttled set, so
        // there's no "Throttled" column.
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            val stats = buildList {
                add(Triple("Total", total.toString(), AppColors.Blue))
                add(Triple("Done", doneCount.toString(), AppColors.Green))
                add(Triple("Errors", errorCount.toString(), AppColors.Red))
                add(Triple("Run", runningCount.toString(), AppColors.Orange))
                add(Triple("Queue", queuedCount.toString(), AppColors.Brown))
                add(Triple("Costs", formatCents(run.totalCostDollars, decimals = 2), AppColors.Blue))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                stats.forEach { (label, _, color) ->
                    Text(label, fontSize = 11.sp, color = color, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                stats.forEach { (_, value, color) ->
                    Text(value, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }

        // Per-failure controls — visible only when at least one item
        // errored. Whole-run scope.
        if (errorCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { confirmRemoveFailed = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                ) { Text("Remove failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { confirmRestartFailed = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Restart failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Top progress bar — run-level (done + error) / total, while
        // there's still pending or running work. Hidden on a cancelled
        // run so it doesn't sit stuck.
        val pending = queuedCount + runningCount
        if (pending > 0 && total > 0 && !run.cancelled) {
            val finished = (doneCount + errorCount).toFloat() / total
            LinearProgressIndicator(
                progress = { finished },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AppColors.Orange,
                trackColor = AppColors.DividerDark
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Per-model rows. The background bar is each model's share of
        // the WHOLE run — green for done, red for errored — because a
        // per-model progress bar isn't possible (the work queue
        // doesn't pre-assign, so a model's eventual total is unknown).
        if (modelRows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (queuedCount > 0) "Queued — no model has picked up an item yet"
                    else "No translation items",
                    color = AppColors.TextSecondary, fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(modelRows, key = { it.modelKey }) { row ->
                    val doneFrac = if (total > 0) row.done / total.toFloat() else 0f
                    val errFrac = if (total > 0) row.err / total.toFloat() else 0f
                    val doneColor = AppColors.Green.copy(alpha = 0.30f)
                    val errColor = AppColors.Red.copy(alpha = 0.30f)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .drawBehind {
                                if (!allDone) {
                                    if (doneFrac > 0f) {
                                        drawRect(
                                            color = doneColor,
                                            size = Size(size.width * doneFrac, size.height)
                                        )
                                    }
                                    if (errFrac > 0f) {
                                        drawRect(
                                            color = errColor,
                                            topLeft = Offset(size.width * doneFrac, 0f),
                                            size = Size(size.width * errFrac, size.height)
                                        )
                                    }
                                }
                            }
                            .padding(vertical = 6.dp)
                            .clickable { onOpenModel(row.modelKey) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!allDone) {
                            val icon = when {
                                row.running > 0 -> "⏳"
                                row.err == row.total -> "❌"
                                row.done == row.total -> "✅"
                                row.err > 0 -> "❌"
                                else -> "🕓"
                            }
                            if (icon == "⏳") {
                                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                    AnimatedHourglass(fontSize = 16.sp)
                                }
                            } else {
                                Text(icon, fontSize = 16.sp, modifier = Modifier.width(20.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(
                                row.modelKey.substringAfter('|'),
                                fontSize = 14.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${row.done}/${row.total} done" +
                                    (if (row.err > 0) " · ${row.err} err" else "") +
                                    (if (row.running > 0) " · ${row.running} run" else ""),
                                fontSize = 10.sp, color = AppColors.TextTertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (row.cost > 0.0) {
                            Text(
                                formatCents(row.cost), fontSize = 11.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Confirmation dialogs
    // -----------------------------------------------------------------
    if (confirmReload) {
        ReloadConfirmationDialog(
            target = "",
            title = "Redo every entry?",
            message = "Deletes all $total row${if (total == 1) "" else "s"} for this translation and dispatches the full set fresh (prompt + every successful agent + every Meta result). The runner's concurrency cap still applies, so a large run shows a mix of running and queued rows.",
            confirmLabel = "Redo all",
            onConfirm = {
                confirmReload = false
                actions.onRestartAll(reportId, runId)
                onBumpRefresh()
            },
            onDismiss = { confirmReload = false }
        )
    }

    if (confirmRestartFailed) {
        ReloadConfirmationDialog(
            target = "",
            title = "Restart failed items?",
            message = "Re-fires $errorCount failed call${if (errorCount == 1) "" else "s"}. The runner's concurrency cap still applies, so larger failure sets show a mix of running and queued rows. Successful translations on disk are kept.",
            confirmLabel = "Restart",
            onConfirm = {
                confirmRestartFailed = false
                actions.onRestartFailed(reportId, runId)
                onBumpRefresh()
            },
            onDismiss = { confirmRestartFailed = false }
        )
    }

    if (confirmRemoveFailed) {
        AlertDialog(
            onDismissRequest = { confirmRemoveFailed = false },
            title = { Text("Remove failed items?") },
            text = {
                Text("Drops $errorCount failed row${if (errorCount == 1) "" else "s"} from this translation. No API calls are made. Successful translations are kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveFailed = false
                    actions.onRemoveFailed(reportId, runId)
                    onBumpRefresh()
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveFailed = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    if (confirmDelete) {
        val pendingCount = items.count {
            it.status == ReportViewModel.TranslationStatus.PENDING ||
                it.status == ReportViewModel.TranslationStatus.RUNNING
        }
        val pendingNote = if (pendingCount > 0)
            " Also cancels $pendingCount in-flight / queued call${if (pendingCount == 1) "" else "s"}."
        else ""
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this translation run?") },
            text = {
                Text("Drops every translation call ($total) for $subject from the report.$pendingNote Can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    scope.launch {
                        actions.onDeleteRun(reportId, runId)?.join()
                        deleting = false
                        onBack()
                    }
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    // Blocking progress popup while the run is being deleted — not
    // dismissable so the user can't navigate away mid-delete.
    if (deleting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Deleting translation") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedHourglass(fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancelling the run and removing every row — this can take a moment.", fontSize = 13.sp)
                }
            },
            confirmButton = { }
        )
    }
}
