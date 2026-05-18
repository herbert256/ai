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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
    var confirmRemoveBenched by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val subject = run.targetLanguageName
    val items = run.items
    val total = items.size
    // Benched = an ERROR item whose model is on a >1h-429 cooldown.
    // Observed reactively so the Bench count updates as cooldowns
    // lift; expiry checked from the snapshot rather than the
    // lazily-mutating ModelCooldownStore.isUnavailable.
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    fun benched(p: String?, m: String?): Boolean =
        p != null && m != null && (cooldowns["$p:$m"] ?: 0L) > System.currentTimeMillis()
    val doneCount = items.count { it.status == ReportViewModel.TranslationStatus.DONE }
    // Errors and Bench split the errored set — a benched item will
    // recover once its cooldown lifts, so it's counted separately.
    val errorCount = items.count {
        it.status == ReportViewModel.TranslationStatus.ERROR && !benched(it.providerId, it.model)
    }
    val benchCount = items.count {
        it.status == ReportViewModel.TranslationStatus.ERROR && benched(it.providerId, it.model)
    }
    val runningCount = items.count { it.status == ReportViewModel.TranslationStatus.RUNNING }
    val queuedCount = items.count { it.status == ReportViewModel.TranslationStatus.PENDING }

    // Group items by the model that handled them. Unassigned PENDING
    // items (providerId/model still null) drop out — they show only
    // in the Queue stat. Sorted by items done, descending — so the
    // busiest model (full bar) stays at the top.
    //
    // We then union the run's intended model set (run.models) so any
    // model that hasn't yet pulled an item — typically the expensive
    // workers held by the cost-aware hesitation — appears as an empty
    // zero-progress row at the bottom instead of staying invisible
    // until its first item lands.
    val runModels = run.models
    val modelRows = remember(items, runModels) {
        val byKey = items.mapNotNull { item -> translationModelKey(item)?.let { it to item } }
            .groupBy({ it.first }, { it.second })
        val seen = byKey.keys.toMutableSet()
        val rows = byKey.map { (key, its) ->
            TranslationModelRow(
                modelKey = key,
                total = its.size,
                done = its.count { it.status == ReportViewModel.TranslationStatus.DONE },
                err = its.count { it.status == ReportViewModel.TranslationStatus.ERROR },
                running = its.count { it.status == ReportViewModel.TranslationStatus.RUNNING },
                cost = its.sumOf { it.costDollars }
            )
        }.toMutableList()
        runModels.forEach { key ->
            if (seen.add(key)) {
                rows += TranslationModelRow(
                    modelKey = key, total = 0, done = 0, err = 0, running = 0, cost = 0.0
                )
            }
        }
        rows.sortedWith(
            compareByDescending<TranslationModelRow> { it.done }
                .thenByDescending { it.total }
                .thenBy { it.modelKey.substringAfter('|').lowercase() }
        )
    }
    // Bar denominator: the busiest model's done count. That model
    // gets a full-width bar; the rest are proportional to it.
    val maxDone = (modelRows.maxOfOrNull { it.done } ?: 0).coerceAtLeast(1)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        // 👁 → matching View Translate screen for this run.
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            { holder.value = com.ai.ui.shared.ViewJump.TranslationRun(run.runId) }
        }
        TitleBar(
            helpTopic = "translation_run_l1",
            title = "Translation",
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = subject,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            onReload = { confirmReload = true },
            // Prefer the run-scoped 🐞 (filter the trace list to
            // exactly this translation run's runId); fall back to the
            // legacy category filter for legacy runs whose row(s)
            // don't carry a runId yet.
            onTrace = if (ApiTracer.isTracingEnabled) ({
                if (run.runId.isNotBlank()) actions.onNavigateToTraceRunList(run.runId)
                else actions.onNavigateToTraceList()
            }) else null,
            onDelete = { confirmDelete = true }
        )
        com.ai.ui.shared.HardcodedSubjectRow(subject)

        // Mode toggle — switches the cost-aware hesitation in the
        // worker loop. Mid-run interactive: workers re-read the
        // selection on every queue pull, so the bias change takes
        // effect within ~1s. Persisted per-runId so a restart lands
        // in the same mode the user picked. See ReportViewModel
        // TranslationMode + setTranslationMode + costPenaltyMs.
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Order: Speed | Mixed | Cost — left-to-right matches the
            // user-facing speed-vs-cost spectrum.
            listOf(
                ReportViewModel.TranslationMode.SPEED to "Speed",
                ReportViewModel.TranslationMode.MIXED to "Mixed",
                ReportViewModel.TranslationMode.COST to "Cost"
            ).forEach { (m, label) ->
                FilterChip(
                    selected = run.mode == m,
                    onClick = { actions.onSetMode(runId, m) },
                    label = {
                        Text(
                            label,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
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
                add(Triple("Bench", benchCount.toString(), AppColors.Purple))
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

        // Per-failure controls — whole-run scope. "Remove failed" and
        // "Remove benched" are complementary (each touches only its
        // own subset of the errored items).
        if (errorCount > 0 || benchCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorCount > 0) {
                    Button(
                        onClick = { confirmRemoveFailed = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                    ) { Text("Remove failed", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    Button(
                        onClick = { confirmRestartFailed = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Restart failed", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
                if (benchCount > 0) {
                    Button(
                        onClick = { confirmRemoveBenched = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                    ) { Text("Remove benched", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
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

        // Per-model rows. While the run is still pending, each model
        // gets a green background bar proportional to its items-done
        // relative to the busiest model. Once the run finishes (no
        // queued or running items) the bars are dropped — a completed
        // run shouldn't keep wearing in-flight progress chrome.
        val showModelBars = pending > 0 && !run.cancelled
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
                    val barFrac = row.done.toFloat() / maxDone
                    val barColor = AppColors.Green.copy(alpha = 0.30f)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .drawBehind {
                                if (showModelBars && barFrac > 0f) {
                                    drawRect(
                                        color = barColor,
                                        size = Size(size.width * barFrac, size.height)
                                    )
                                }
                            }
                            .padding(vertical = 8.dp)
                            .clickable { onOpenModel(row.modelKey) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Finished-run leading count column. While bars
                        // are on, the green fill conveys per-model
                        // throughput visually; once they're gone we need
                        // a number to keep the per-model split legible.
                        // Sorting on row.done (descending — set in
                        // modelRows above) means the densest model
                        // stays at the top.
                        if (!showModelBars) {
                            Text(
                                row.done.toString(),
                                fontSize = 13.sp,
                                color = AppColors.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.End,
                                modifier = Modifier.padding(start = 8.dp).widthIn(min = 32.dp)
                            )
                        }
                        // No status glyph — the proportional bar already
                        // conveys progress, and a finished run shouldn't
                        // read as a wall of check marks.
                        Text(
                            com.ai.ui.shared.shortModelName(row.modelKey.substringAfter('|')),
                            fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        )
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
        // Restart re-fires every errored item including benched ones
        // (a benched item just re-confirms its cooldown — cheap).
        val restartN = errorCount + benchCount
        // Multi-model runs route each failed item to a model OTHER
        // than the one that failed (round-robin over the rest), so
        // the user gets a meaningful retry instead of hitting the
        // same wall twice. Mono-model runs retry on the same model.
        val modelCount = modelRows.size
        val modelNote = if (modelCount > 1)
            " This run uses $modelCount models — failed entries will switch to a different model than the one that failed."
        else ""
        ReloadConfirmationDialog(
            target = "",
            title = "Restart failed items?",
            message = "Re-fires $restartN failed call${if (restartN == 1) "" else "s"}.$modelNote The runner's concurrency cap still applies, so larger failure sets show a mix of running and queued rows. Successful translations on disk are kept.",
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
                Text("Drops $errorCount failed row${if (errorCount == 1) "" else "s"} from this translation. Benched (rate-limited) rows are kept — use Remove benched for those. No API calls are made. Successful translations are kept.")
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

    if (confirmRemoveBenched) {
        AlertDialog(
            onDismissRequest = { confirmRemoveBenched = false },
            title = { Text("Remove benched items?") },
            text = {
                Text("Drops $benchCount benched row${if (benchCount == 1) "" else "s"} — items whose model is on a rate-limit cooldown. No API calls are made. Genuine errors and successful translations are kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveBenched = false
                    actions.onRemoveBenched(reportId, runId)
                    onBumpRefresh()
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveBenched = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
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
