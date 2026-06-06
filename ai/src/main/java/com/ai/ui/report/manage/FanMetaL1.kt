package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.FanOutRunState
import com.ai.data.PairState
import com.ai.data.PairStatus
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.titleStatus
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L1 of the Fan Meta drill-in: the per-pair title + icon batch over a
 * finished fan-out. Stats + grouping (Meta models / Report models) +
 * failure controls, then the model list. Tapping a model row opens
 * L2; "Show all" opens the flat title list. Fan-Meta sibling of
 * [FanOutL1Screen]; the two no longer share a screen / mode flag.
 */
@Composable
internal fun FanMetaL1Screen(
    run: FanOutRunState,
    /** In-flight pair ids for the fan-meta title batch. */
    runningSet: Set<String>,
    /** Pair ids parked on a provider rate-limit cap. */
    throttledSet: Set<String>,
    /** Cross-link back to the Fan Out (responses) screen. */
    onShowResponses: () -> Unit,
    onOpenModel: (String) -> Unit,
    /** Open the 🐜 Fan Meta workers (meta-model) sub-screen. */
    onOpenWorkers: () -> Unit,
    /** Open the flat "Fan Meta - All" title list. */
    onOpenTitles: () -> Unit,
    /** Reload / delete / trace — owned by the router (shared with the
     *  workers screen); the confirm dialogs render at the router. */
    onReload: () -> Unit,
    onDelete: () -> Unit,
    onTrace: (() -> Unit)?,
    onBack: () -> Unit
) {
    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name

    // Status lens — titleStatus folds in the "finished but no content"
    // ERROR case the raw field misses. Used by the per-group rows below;
    // the L1 stat counts go through deriveBatchSummary.
    fun lens(p: PairState, set: Set<String>): PairStatus = p.titleStatus(set)
    fun metaDone(p: PairState): Boolean = !p.title.isNullOrBlank()
    // Per-pair Fan Meta spend (title + icon worker calls).
    fun pairCost(p: PairState): Double =
        p.titleInputCost + p.titleOutputCost + p.iconInputCost + p.iconOutputCost

    // Benched = errored AND the pair's model is on a >1h-429 cooldown.
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    fun benched(p: String?, m: String?): Boolean =
        p != null && m != null && (cooldowns["$p:$m"] ?: 0L) > System.currentTimeMillis()

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "fan_meta_l1",
            title = "Fan Meta",
            subject = subject,
            onBackClick = onBack,
            onBatchWorkers = onOpenWorkers,
            onReload = onReload,
            onTrace = onTrace,
            onDelete = onDelete
        )

        // Status counts + cost — title-batch status lens. Worker-pool batch
        // (category B): meta workers are replaceable, so there is no Bench
        // bucket and every terminal failure stays in Error.
        val summary = deriveBatchSummary(
            items = run.pairs.values,
            idOf = { it.id },
            statusOf = { it.titleStatus(runningSet) },
            throttledIds = throttledSet,
            family = BatchFamily.WORKER_POOL,
        )
        val counts = summary.counts
        val doneCount = counts.done
        val errorCount = summary.displayError
        val runningCount = counts.running
        val throttledHere = counts.wait
        val queuedCount = counts.queued
        val allDone = run.totalPairs > 0 && doneCount == run.totalPairs
        Spacer(modifier = Modifier.height(8.dp))
        // Worker-swarm batch (category B): a benched meta-worker is
        // skipped and another picked, so benched errors fold into Error
        // and there's no separate Bench column.
        BatchStatsRow(listOf(
            Triple("Total", run.totalPairs.toString(), AppColors.InfoAccent),
            Triple("Done", doneCount.toString(), AppColors.SuccessAccent),
            Triple("Error", errorCount.toString(), AppColors.DangerAccent),
            Triple("Run", runningCount.toString(), AppColors.WarningAccent),
            Triple("Wait", throttledHere.toString(), AppColors.CautionAccent),
            Triple("Queue", queuedCount.toString(), AppColors.QueueAccent),
            Triple("Costs", "${formatCents(run.pairs.values.sumOf { pairCost(it) }, decimals = 2)} ¢", AppColors.InfoAccent)
        ))

        // L1 lists the report (answerer) models. The meta-worker
        // ("workers") grouping lives on the 🐜 Fan Meta workers screen.
        val hasTitles = remember(run) { run.pairs.values.any { !it.title.isNullOrBlank() } }

        // Per-failure controls (Remove/Restart failed/benched + Remove/Restart
        // Fan-Meta errors) removed — a new failure-handling UX is coming. The
        // read-only "View errors" inspector below is kept.
        val metaHasErrors = errorCount > 0
        var showIconErrorsDialog by remember { mutableStateOf(false) }
        if (metaHasErrors) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showIconErrorsDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("View errors", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        if (showIconErrorsDialog) {
            val errored = remember(run, errorCount) {
                run.pairs.values
                    .filter {
                        val msg = it.titleErrorMessage ?: it.iconErrorMessage
                        !msg.isNullOrBlank()
                    }
                    .sortedWith(compareBy({ it.providerId }, { it.model }))
            }
            AlertDialog(
                onDismissRequest = { showIconErrorsDialog = false },
                title = { Text("Fan Meta — errors (${errored.size})") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())) {
                        errored.forEach { p ->
                            Text(
                                "${p.providerId} / ${com.ai.ui.shared.shortModelName(p.model)}",
                                fontSize = 13.sp, color = AppColors.InfoAccent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                (p.titleErrorMessage ?: p.iconErrorMessage).orEmpty(),
                                fontSize = 12.sp, color = AppColors.TextTertiary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showIconErrorsDialog = false }) {
                        Text("Close", maxLines = 1, softWrap = false)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Top progress bar — the title batch. Keep it up while the shared
        // worker-pool policy says work is still outstanding.
        if (summary.activeOutstanding && run.totalPairs > 0 && !run.cancelled) {
            val finished = (doneCount + errorCount).toFloat() / run.totalPairs
            LinearProgressIndicator(
                progress = { finished },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AppColors.WarningAccent,
                trackColor = AppColors.DividerDark
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Row list for the active grouping. "Report models" groups by
        // answerer model; "Meta models" groups by the meta-worker model
        // (titleModel) — pairs whose meta hasn't run yet drop out until
        // their title lands. Stable order by model name.
        val l1Rows: List<FanMetaL1RowModel> = remember(run) {
            run.answererKeys
                .sortedWith(compareBy { ak -> ak.substringAfter('|').lowercase() })
                .map { ak ->
                    FanMetaL1RowModel(
                        key = "rep:$ak",
                        label = com.ai.ui.shared.shortModelName(ak.substringAfter('|')),
                        pairs = run.pairs.values.filter { "${it.providerId}|${it.model}" == ak },
                        onClick = { onOpenModel(ak) }
                    )
                }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (l1Rows.isEmpty()) {
                item {
                    Text(
                        "No titles yet.",
                        color = AppColors.TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            items(l1Rows, key = { it.key }) { row ->
                val pairs = row.pairs
                val ok = pairs.count { metaDone(it) }
                val err = pairs.count { lens(it, runningSet) == PairStatus.ERROR }
                val running = pairs.count { lens(it, runningSet) == PairStatus.RUNNING }
                val total = pairs.size
                val cost = pairs.sumOf { pairCost(it) }
                val progressFraction = if (total > 0) (ok + err).toFloat() / total else 0f
                val progressColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .drawBehind {
                            if (!allDone && progressFraction > 0f) {
                                drawRect(
                                    color = progressColor,
                                    size = Size(size.width * progressFraction, size.height)
                                )
                            }
                        }
                        .padding(vertical = 6.dp)
                        .clickable { row.onClick() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!allDone) {
                        val icon = when {
                            running > 0 -> "⏳"
                            total == 0 -> com.ai.data.MetadataIconsHolder.current.add
                            err > 0 && err == total -> com.ai.data.MetadataIconsHolder.current.statusFailed
                            ok == total -> com.ai.data.MetadataIconsHolder.current.statusDone
                            err > 0 -> com.ai.data.MetadataIconsHolder.current.statusFailed
                            else -> com.ai.data.MetadataIconsHolder.current.clockQueued
                        }
                        if (icon == "⏳") {
                            Box(
                                Modifier.width(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedHourglass(fontSize = 16.sp)
                            }
                        } else {
                            Text(
                                icon, fontSize = 16.sp,
                                modifier = Modifier.width(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            row.label,
                            fontSize = 14.sp, color = AppColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (cost > 0.0) {
                        Text(
                            formatCents(cost), fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
        }

        // Bottom button row — cross-link back to responses + the flat
        // title list.
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onShowResponses,
                modifier = Modifier.weight(1f),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Fan-Out", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            if (hasTitles) {
                OutlinedButton(
                    onClick = onOpenTitles,
                    modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Show all", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** 🐜 Fan Meta workers — the per-meta-worker (model) grouping, moved off
 *  L1's old toggle into its own screen. Reload / delete / trace mirror L1
 *  (the confirm dialogs are owned by [FanMetaScreen]); tapping a
 *  meta-worker row drills into that worker's pairs (L2MetaModel). */
@Composable
internal fun FanMetaWorkersScreen(
    run: FanOutRunState,
    runningSet: Set<String>,
    throttledSet: Set<String>,
    onOpenMetaModel: (String) -> Unit,
    onReload: () -> Unit,
    onDelete: () -> Unit,
    onTrace: (() -> Unit)?,
    onBack: () -> Unit
) {
    fun pairCost(p: PairState): Double =
        p.titleInputCost + p.titleOutputCost + p.iconInputCost + p.iconOutputCost

    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name

    // Worker-pool batch (category B): no Bench bucket — same lens as L1.
    val summary = deriveBatchSummary(
        items = run.pairs.values,
        idOf = { it.id },
        statusOf = { it.titleStatus(runningSet) },
        throttledIds = throttledSet,
        family = BatchFamily.WORKER_POOL,
    )
    val counts = summary.counts

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "fan_meta_workers",
            title = "Fan Meta workers",
            subject = subject,
            onBackClick = onBack,
            onReload = onReload,
            onTrace = onTrace,
            onDelete = onDelete
        )
        Spacer(modifier = Modifier.height(8.dp))
        BatchStatsRow(listOf(
            Triple("Total", run.totalPairs.toString(), AppColors.InfoAccent),
            Triple("Done", counts.done.toString(), AppColors.SuccessAccent),
            Triple("Error", summary.displayError.toString(), AppColors.DangerAccent),
            Triple("Run", counts.running.toString(), AppColors.WarningAccent),
            Triple("Wait", counts.wait.toString(), AppColors.CautionAccent),
            Triple("Queue", counts.queued.toString(), AppColors.QueueAccent),
            Triple("Costs", "${formatCents(run.pairs.values.sumOf { pairCost(it) }, decimals = 2)} ¢", AppColors.InfoAccent)
        ))
        Spacer(modifier = Modifier.height(8.dp))

        // One row per meta-worker model (titleModel); count = pairs it titled.
        val rows = remember(run) {
            run.pairs.values
                .filter { !it.titleModel.isNullOrBlank() }
                .groupBy { it.titleModel!! }
                .entries
                .sortedBy { it.key.substringAfterLast('/').lowercase() }
                .map { (metaKey, pairs) -> Triple(metaKey, pairs.size, pairs.sumOf { pairCost(it) }) }
        }
        val maxDone = (rows.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        val showBars = summary.activeOutstanding
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                item {
                    Text(
                        "No titles yet.",
                        color = AppColors.TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            items(rows, key = { it.first }) { (metaKey, calls, cost) ->
                FanMetaModelsL1Row(
                    calls = calls,
                    name = com.ai.ui.shared.shortModelName(metaKey.substringAfterLast('/')),
                    cost = cost,
                    barFrac = calls.toFloat() / maxDone,
                    showBar = showBars,
                    onClick = { onOpenMetaModel(metaKey) }
                )
                HorizontalDivider(color = AppColors.DividerDark)
            }
        }
    }
}

/** One L1 model-row descriptor — unifies the "Report models" (per
 *  answerer) and "Meta models" (per meta-worker) groupings so both
 *  render through the same row composable. */
private class FanMetaL1RowModel(
    val key: String,
    val label: String,
    val pairs: List<PairState>,
    val onClick: () -> Unit,
    /** Titled-pair count for this meta-worker (Meta-models grouping only). */
    val metaDone: Int = 0
)

/** Translation-style L1 row used only by Fan Meta "Meta models" mode: a
 *  leading numeric [calls] count + a green bar normalized to the busiest
 *  meta-worker ([barFrac] = this row's done / maxDone), no status glyph. */
@Composable
private fun FanMetaModelsL1Row(
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
            .padding(vertical = 6.dp)
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
                formatCents(cost), fontSize = 11.sp,
                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

/**
 * Fan Meta "All" — one card per source model: the source's model name
 * as a header, then every responder's generated fan-out title as a
 * tappable text row. Tap a title to drill into that pair's L3 detail
 * (Responder role). Reached via the L1 "Show all" button.
 */
@Composable
internal fun FanMetaAllScreen(
    run: FanOutRunState,
    onOpenPair: (answererKey: String, sourceAgentId: String, role: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name

    val report by produceState<Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val sourceAgentById = remember(report) {
        report?.agents?.associateBy { it.agentId } ?: emptyMap()
    }

    val groups = remember(run) {
        run.pairs.values
            .filter { !it.title.isNullOrBlank() }
            .groupBy { it.sourceAgentId }
            .entries
            .sortedBy { e -> e.value.minOf { it.timestamp } }
            .map { it.key to it.value.sortedBy { p -> p.timestamp } }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "fan_meta_l1",
            title = "Fan Meta - All",
            subject = subject,
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No titles yet", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groups.forEach { (sourceAgentId, pairs) ->
                val srcAgent = sourceAgentById[sourceAgentId]
                val srcIcon = srcAgent?.icon
                val srcTitle = srcAgent?.modelTitle?.takeIf { it.isNotBlank() }
                    ?: srcAgent?.let { resolveModelLabel("${it.provider}|${it.model}") }
                    ?: sourceAgentId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CardBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!srcIcon.isNullOrBlank()) {
                            Text(srcIcon, fontSize = 18.sp, modifier = Modifier.padding(end = 6.dp))
                        }
                        Text(
                            srcTitle, fontSize = 13.sp, color = AppColors.InfoAccent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    pairs.forEachIndexed { idx, p ->
                        if (idx > 0) HorizontalDivider(color = AppColors.DividerDark)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    onOpenPair("${p.providerId}|${p.model}", p.sourceAgentId, "Responder")
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.icon ?: com.ai.data.MetadataIconsHolder.current.boxBlank, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                p.title.orEmpty(), fontSize = 14.sp, color = AppColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
