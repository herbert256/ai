package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.TranslationStatus

/** Aggregate stats for one model's slice of a translation run. */
private data class TranslationModelRow(
    val modelKey: String,
    val total: Int,
    val done: Int,
    val err: Int,
    val running: Int,
    val cost: Double
)

/** Aggregate stats for one trace/cost type's slice of a run. */
private data class TranslationTypeRow(
    val traceType: String,
    val total: Int,
    val done: Int,
    val err: Int,
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
    run: TranslationRunState,
    /** Item ids currently parked on a provider rate / concurrency gate —
     *  surfaced as the "Throttled" stat. Carved out of Queue. */
    throttledSet: Set<String> = emptySet(),
    onOpenGroup: (String) -> Unit,
    /** Open the 🐜 Translation workers (per-model) sub-screen. */
    onOpenWorkers: () -> Unit,
    /** 🏅 Start / open the Rank-the-translators batch for this run. */
    onRankTranslators: (String, String, String) -> Unit = { _, _, _ -> },
    /** Reload / delete / trace / view — owned by the router (shared with
     *  the workers screen); the confirm dialogs render at the router. */
    onReload: () -> Unit,
    onDelete: () -> Unit,
    onTrace: (() -> Unit)?,
    onOpenView: (() -> Unit)?,
    onBack: () -> Unit
) {
    val subject = run.targetLanguageName
    val items = run.items.values
    val total = items.size
    // Worker-pool batch (category B): no Bench bucket. Failed worker-pool
    // items stay normal failed rows and can be removed or restarted.
    val summary = deriveBatchSummary(
        items = items,
        idOf = { it.id },
        statusOf = { it.status },
        throttledIds = throttledSet,
        family = BatchFamily.WORKER_POOL,
    )
    val counts = summary.counts
    val doneCount = counts.done
    val errorCount = summary.displayError
    val runningCount = counts.running
    val throttledCount = counts.wait
    val queuedCount = counts.queued

    // Per-type rows for the Types preset. Every item carries a traceType
    // (stamped at creation), so unlike modelRows nothing drops out —
    // PENDING items group under their eventual type too. Sorted by size
    // desc then label so the layout is stable as statuses flip.
    val typeRows = remember(items) {
        items.groupBy { it.traceType }
            .map { (type, its) ->
                TranslationTypeRow(
                    traceType = type,
                    total = its.size,
                    done = its.count { it.status == TranslationStatus.DONE },
                    err = its.count { it.status == TranslationStatus.ERROR },
                    cost = its.sumOf { it.costDollars }
                )
            }
            .sortedWith(
                compareByDescending<TranslationTypeRow> { it.total }
                    .thenBy { it.traceType }
            )
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "translation_run_l1",
            title = "Translation",
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = subject,
            onBackClick = onBack,
            onOpenView = onOpenView,
            onBatchWorkers = onOpenWorkers,
            onRankTranslators = { onRankTranslators(run.runId, run.targetLanguageName, run.targetLanguageNative) },
            onReload = onReload,
            onTrace = onTrace,
            onDelete = onDelete
        )

        // Stats panel — pinned at the top, kept visible even once the
        // whole run is done. Wait = items parked on a provider gate
        // (carved out of Queue). Worker-pool batch (category B): no
        // Bench bucket and no cooldown-derived Error split.
        Spacer(modifier = Modifier.height(8.dp))
        BatchStatsRow(listOf(
            Triple("Total", total.toString(), AppColors.InfoAccent),
            Triple("Done", doneCount.toString(), AppColors.SuccessAccent),
            Triple("Error", errorCount.toString(), AppColors.DangerAccent),
            Triple("Run", runningCount.toString(), AppColors.WarningAccent),
            Triple("Wait", throttledCount.toString(), AppColors.CautionAccent),
            Triple("Queue", queuedCount.toString(), AppColors.QueueAccent),
            Triple("Costs", formatTranslationCost(run.totalCostDollars), AppColors.InfoAccent)
        ))

        // L1 lists translation *types* (per trace/cost-type rows). The
        // per-model ("workers") grouping lives on the 🐜 Translation
        // workers screen.
        Spacer(modifier = Modifier.height(8.dp))

        // Top progress bar — run-level (done + error) / total, while the
        // shared worker-pool policy says work is still outstanding. Hidden on
        // a cancelled run so it doesn't sit stuck.
        if (summary.activeOutstanding && total > 0 && !run.cancelled) {
            val finished = (doneCount + errorCount).toFloat() / total
            LinearProgressIndicator(
                progress = { finished },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AppColors.WarningAccent,
                trackColor = AppColors.DividerDark
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // While the run is still pending, each row gets a green
        // background fill conveying progress. Once the run finishes (no
        // queued or running items) the bars are dropped — a completed
        // run shouldn't keep wearing in-flight progress chrome.
        val showBars = summary.activeOutstanding && !run.cancelled
        // Both lists share one layout: [calls | name | cost]. calls =
        // the group's entry count; name = the model (workers) or the
        // type (types); the green row-background fill conveys progress
        // while work is in flight. Models bars are relative to the
        // busiest model; type bars are that type's done/total.
        if (typeRows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("One moment, collecting information…", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(typeRows, key = { it.traceType }) { row ->
                    TranslationL1Row(
                        calls = row.total,
                        name = translationTypeLabel(row.traceType),
                        cost = row.cost,
                        barFrac = if (row.total > 0) row.done.toFloat() / row.total else 0f,
                        showBar = showBars,
                        onClick = { onOpenGroup(row.traceType) }
                    )
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }
    }

}

/** 🐜 Translation workers — the per-model grouping, moved off L1's old
 *  toggle into its own screen. Reload / delete / trace / view mirror L1
 *  (the confirm dialogs are owned by [TranslationRunScreen]); tapping a
 *  model row drills into that model's items (L2, MODELS mode). */
@Composable
internal fun TranslationWorkersScreen(
    run: TranslationRunState,
    throttledSet: Set<String> = emptySet(),
    onOpenGroup: (String) -> Unit,
    onRankTranslators: (String, String, String) -> Unit = { _, _, _ -> },
    onReload: () -> Unit,
    onDelete: () -> Unit,
    onTrace: (() -> Unit)?,
    onOpenView: (() -> Unit)?,
    onBack: () -> Unit
) {
    val subject = run.targetLanguageName
    val items = run.items.values
    val total = items.size
    // Worker-pool batch (category B): no Bench bucket — same lens as L1.
    val summary = deriveBatchSummary(
        items = items,
        idOf = { it.id },
        statusOf = { it.status },
        throttledIds = throttledSet,
        family = BatchFamily.WORKER_POOL,
    )
    val counts = summary.counts
    val queuedCount = counts.queued

    // Group items by the model that handled them; union run.models so a
    // worker that hasn't pulled an item yet still shows as a zero row.
    val runModels = run.models
    val modelRows = remember(items, runModels) {
        val byKey = items.mapNotNull { item -> translationModelKey(item)?.let { it to item } }
            .groupBy({ it.first }, { it.second })
        val seen = byKey.keys.toMutableSet()
        val rows = byKey.map { (key, its) ->
            TranslationModelRow(
                modelKey = key,
                total = its.size,
                done = its.count { it.status == TranslationStatus.DONE },
                err = its.count { it.status == TranslationStatus.ERROR },
                running = its.count { it.status == TranslationStatus.RUNNING },
                cost = its.sumOf { it.costDollars }
            )
        }.toMutableList()
        runModels.forEach { key ->
            if (seen.add(key)) {
                rows += TranslationModelRow(modelKey = key, total = 0, done = 0, err = 0, running = 0, cost = 0.0)
            }
        }
        // Sort by total (stable while statuses flip), then model name — NOT by
        // `done`, which changes continuously during a live run and made rows
        // jump/reorder under the user's finger (audit reports#8). Matches the L1
        // types list's stable ordering.
        rows.sortedWith(
            compareByDescending<TranslationModelRow> { it.total }
                .thenBy { it.modelKey.substringAfter('|').lowercase() }
        )
    }
    val maxDone = (modelRows.maxOfOrNull { it.done } ?: 0).coerceAtLeast(1)

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "translation_workers",
            title = "Translation workers",
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = subject,
            onBackClick = onBack,
            // 🐜 grayed here (already in workers mode); clicking returns to models.
            onBatchWorkers = onBack,
            batchWorkersActive = false,
            onRankTranslators = { onRankTranslators(run.runId, run.targetLanguageName, run.targetLanguageNative) },
            onOpenView = onOpenView,
            onReload = onReload,
            onTrace = onTrace,
            onDelete = onDelete
        )
        Spacer(modifier = Modifier.height(8.dp))
        BatchStatsRow(listOf(
            Triple("Total", total.toString(), AppColors.InfoAccent),
            Triple("Done", counts.done.toString(), AppColors.SuccessAccent),
            Triple("Error", summary.displayError.toString(), AppColors.DangerAccent),
            Triple("Run", counts.running.toString(), AppColors.WarningAccent),
            Triple("Wait", counts.wait.toString(), AppColors.CautionAccent),
            Triple("Queue", queuedCount.toString(), AppColors.QueueAccent),
            Triple("Costs", formatTranslationCost(run.totalCostDollars), AppColors.InfoAccent)
        ))
        Spacer(modifier = Modifier.height(8.dp))
        val showBars = summary.activeOutstanding && !run.cancelled
        if (modelRows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (queuedCount > 0) "Queued — no model has picked up an item yet"
                    else "One moment, collecting information…",
                    color = AppColors.TextSecondary, fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(modelRows, key = { it.modelKey }) { row ->
                    TranslationL1Row(
                        calls = row.total,
                        name = com.ai.ui.shared.shortModelName2(row.modelKey.substringAfter('|')),
                        cost = row.cost,
                        barFrac = row.done.toFloat() / maxDone,
                        showBar = showBars,
                        onClick = { onOpenGroup(row.modelKey) }
                    )
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }
    }
}

/** One L1 list row, shared by the Translation-workers and
 *  Translation-types lists so both read identically: the call count
 *  first, then the name (model or type), then the cost. A green
 *  background fill (proportional to [barFrac]) conveys progress while
 *  [showBar] is true; the cost shows only when non-zero. */
@Composable
private fun TranslationL1Row(
    calls: Int,
    name: String,
    cost: Double,
    barFrac: Float,
    showBar: Boolean,
    onClick: () -> Unit
) {
    val barColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBehind {
                if (showBar && barFrac > 0f) {
                    drawRect(color = barColor, size = Size(size.width * barFrac, size.height))
                }
            }
            .padding(vertical = 8.dp)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            calls.toString(),
            fontSize = 13.sp, color = AppColors.TextSecondary,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp).widthIn(min = 32.dp)
        )
        Text(
            name,
            fontSize = 14.sp, color = AppColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        if (cost > 0.0) {
            Text(
                formatTranslationCost(cost), fontSize = 11.sp,
                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}
