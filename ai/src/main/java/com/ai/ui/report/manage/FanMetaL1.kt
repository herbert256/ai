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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import com.ai.ui.shared.ReloadConfirmationDialog
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
    actions: FanOutActions,
    /** Cross-link back to the Fan Out (responses) screen. */
    onShowResponses: () -> Unit,
    onOpenModel: (String) -> Unit,
    /** Open the Meta-models L2 for one meta-worker model (the "Meta
     *  models" grouping). Argument is `PairState.titleModel`. */
    onOpenMetaModel: (String) -> Unit,
    /** Open the flat "Fan Meta - All" title list. */
    onOpenTitles: () -> Unit,
    onBack: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    // L1 grouping preset. Survives the L1↔L2 drill round trip + rotation.
    // Meta models = group by the meta-worker model; Report models =
    // group by the answerer model.
    var metaGroupMode by rememberSaveable { mutableStateOf(FanMetaGroupMode.META_MODELS) }
    var confirmRerunComplete by remember { mutableStateOf(false) }
    var confirmRemoveFailed by remember { mutableStateOf(false) }
    var confirmRemoveBenched by remember { mutableStateOf(false) }
    var confirmRestartFailed by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name

    // Status lens — titleStatus folds in the "finished but no content"
    // ERROR case the raw field misses. Used by the per-group rows below;
    // the L1 stat counts go through deriveBatchCounts.
    fun lens(p: PairState, set: Set<String>): PairStatus = p.titleStatus(set)
    fun metaDone(p: PairState): Boolean = !p.title.isNullOrBlank()
    // Per-pair Fan Meta spend (title + icon worker calls).
    fun pairCost(p: PairState): Double =
        p.titleInputCost + p.titleOutputCost + p.iconInputCost + p.iconOutputCost

    // Benched = errored AND the pair's model is on a >1h-429 cooldown.
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    fun benched(p: String?, m: String?): Boolean =
        p != null && m != null && (cooldowns["$p:$m"] ?: 0L) > System.currentTimeMillis()

    // 🐞 deep-link target: the most recent Fan Meta sweep on these pairs.
    val l1RunId = run.pairs.values.firstNotNullOfOrNull { it.titleRunId }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "fan_meta_l1",
            title = "Fan Meta",
            subject = subject,
            onBackClick = onBack,
            onReload = { confirmRerunComplete = true },
            onTrace = if (l1RunId != null && com.ai.data.ApiTracer.ladybugLinksEnabled)
                { { actions.onNavigateToTraceRunList(l1RunId) } } else null,
            onDelete = { confirmDelete = true }
        )

        // Status counts + cost — title-batch status lens.
        // Counters via the shared single-pass helper. Worker-swarm batch
        // (category B, ERRORED): status is the title-batch lens; benched
        // errors fold into Error below (no separate Bench column).
        val counts = deriveBatchCounts(
            items = run.pairs.values,
            idOf = { it.id },
            statusOf = { it.titleStatus(runningSet) },
            throttledIds = throttledSet,
            benchedOf = { benched(it.providerId, it.model) },
            benchMode = BenchMode.ERRORED,
        )
        val doneCount = counts.done
        val errorCount = counts.error
        val benchCount = counts.bench
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
            Triple("Error", (errorCount + benchCount).toString(), AppColors.DangerAccent),
            Triple("Run", runningCount.toString(), AppColors.WarningAccent),
            Triple("Wait", throttledHere.toString(), AppColors.CautionAccent),
            Triple("Queue", queuedCount.toString(), AppColors.QueueAccent),
            Triple("Costs", "${formatCents(run.pairs.values.sumOf { pairCost(it) }, decimals = 2)} ¢", AppColors.InfoAccent)
        ))

        // Grouping preset — Meta models (per meta-worker model) vs
        // Report models (per answerer model).
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                FanMetaGroupMode.META_MODELS to "Meta models",
                FanMetaGroupMode.REPORT_MODELS to "Report models"
            ).forEach { (gm, label) ->
                FilterChip(
                    selected = metaGroupMode == gm,
                    onClick = { metaGroupMode = gm },
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

        val hasTitles = remember(run) { run.pairs.values.any { !it.title.isNullOrBlank() } }

        // Per-failure controls for the underlying fan-out responses — a
        // pair whose response errored or is benched can never get a
        // title, so the same Remove/Restart controls surface here.
        val mainBenched = run.pairs.values.count {
            it.status == PairStatus.ERROR && benched(it.providerId, it.model)
        }
        val mainErrored = run.pairs.values.count {
            it.status == PairStatus.ERROR && !benched(it.providerId, it.model)
        }
        if (mainErrored > 0 || mainBenched > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mainErrored > 0) {
                    OutlinedButton(
                        onClick = { confirmRemoveFailed = true },
                        modifier = Modifier.weight(1f),
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Remove failed", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    Button(
                        onClick = { confirmRestartFailed = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Restart failed", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
                if (mainBenched > 0) {
                    OutlinedButton(
                        onClick = { confirmRemoveBenched = true },
                        modifier = Modifier.weight(1f),
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Remove benched", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
            }
        }

        // Fan-Meta error controls — clear / restart the title+icon
        // sentinel + emoji state on errored pairs, not the pair rows.
        val metaHasErrors = errorCount > 0
        var showIconErrorsDialog by remember { mutableStateOf(false) }
        if (metaHasErrors) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tightPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                OutlinedButton(
                    onClick = { actions.onClearFanMetaErrors(run.key) },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding,
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Remove errors", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { actions.onRestartFanMetaErrors(run.key) },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding
                ) { Text("Restart errors", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = { showIconErrorsDialog = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding,
                    colors = AppColors.outlinedButtonColors()
                ) { Text("View errors", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        if (showIconErrorsDialog) {
            val errored = remember(run, errorCount) {
                run.pairs.values
                    .filter {
                        val msg = it.titleErrorMessage ?: it.iconErrorMessage
                        !msg.isNullOrBlank() && !benched(it.providerId, it.model)
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

        // Top progress bar — the title batch. Keep it up while throttled or
        // benched pairs are still outstanding (carved out of Run/Queue), else
        // it hides with work remaining and the run reads as complete.
        // Mirrors TranslationL1.
        val pending = queuedCount + runningCount + throttledHere
        if ((pending > 0 || benchCount > 0) && run.totalPairs > 0) {
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
        val l1Rows: List<FanMetaL1RowModel> = remember(run, metaGroupMode) {
            if (metaGroupMode == FanMetaGroupMode.META_MODELS) {
                run.pairs.values
                    .filter { !it.titleModel.isNullOrBlank() }
                    .groupBy { it.titleModel!! }
                    .entries
                    .sortedBy { it.key.substringAfterLast('/').lowercase() }
                    .map { (metaKey, pairs) ->
                        FanMetaL1RowModel(
                            key = "meta:$metaKey",
                            label = com.ai.ui.shared.shortModelName(metaKey.substringAfterLast('/')),
                            pairs = pairs,
                            onClick = { onOpenMetaModel(metaKey) },
                            metaDone = pairs.size
                        )
                    }
            } else {
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
        }
        val isMetaModels = metaGroupMode == FanMetaGroupMode.META_MODELS
        val metaMaxDone = (l1Rows.maxOfOrNull { it.metaDone } ?: 0).coerceAtLeast(1)
        val metaShowBars = isMetaModels && (queuedCount + runningCount + throttledHere > 0 || benchCount > 0)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(l1Rows, key = { it.key }) { row ->
              if (isMetaModels) {
                FanMetaModelsL1Row(
                    calls = row.metaDone,
                    name = row.label,
                    cost = row.pairs.sumOf { pairCost(it) },
                    barFrac = row.metaDone.toFloat() / metaMaxDone,
                    showBar = metaShowBars,
                    onClick = row.onClick
                )
                HorizontalDivider(color = AppColors.DividerDark)
              } else {
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

    // -----------------------------------------------------------------
    // Confirmation dialogs
    // -----------------------------------------------------------------
    if (confirmRerunComplete) {
        ReloadConfirmationDialog(
            target = "",
            title = "Rerun the complete Fan out?",
            message = "Delete every fan-out row and start a fresh run. Combined-report follow-ups for this prompt will also be dropped.",
            confirmLabel = "Rerun",
            onConfirm = {
                confirmRerunComplete = false
                actions.onRerunComplete(run.key)
            },
            onDismiss = { confirmRerunComplete = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Fan Meta?") },
            text = {
                Text("Drop every title + icon (and their cost) for this run's ${run.totalPairs} pair${if (run.totalPairs == 1) "" else "s"}. The fan-out responses themselves are kept. Can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    scope.launch {
                        actions.onClearFanMeta(run.key)?.join()
                        deleting = false
                        onBack()
                    }
                }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Deleting Fan Meta") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedHourglass(fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clearing the Fan Meta — this can take a moment.", fontSize = 13.sp)
                }
            },
            confirmButton = { }
        )
    }

    if (confirmRestartFailed) {
        ReloadConfirmationDialog(
            target = "",
            title = "Restart failed items?",
            message = "Re-fires ${run.errorCount} failed fan-out call${if (run.errorCount == 1) "" else "s"} for this prompt. The runner's concurrency cap still applies, so larger failure sets surface as a mix of running and queued rows. Successful pairs are kept.",
            confirmLabel = "Restart",
            onConfirm = {
                confirmRestartFailed = false
                actions.onRestartFailedPairs(run.key)
            },
            onDismiss = { confirmRestartFailed = false }
        )
    }
    if (confirmRemoveFailed) {
        val n = run.pairs.values.count { it.status == PairStatus.ERROR && !benched(it.providerId, it.model) }
        AlertDialog(
            onDismissRequest = { confirmRemoveFailed = false },
            title = { Text("Remove failed items?") },
            text = { Text("Drops $n failed fan-out row${if (n == 1) "" else "s"} for this prompt. Benched (rate-limited) rows are kept — use Remove benched for those. No API calls are made. Successful pairs are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveFailed = false
                    actions.onRemoveFailedPairs(run.key)
                }) { Text("Remove", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveFailed = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
    if (confirmRemoveBenched) {
        val n = run.pairs.values.count { it.status == PairStatus.ERROR && benched(it.providerId, it.model) }
        AlertDialog(
            onDismissRequest = { confirmRemoveBenched = false },
            title = { Text("Remove benched items?") },
            text = { Text("Drops $n benched fan-out row${if (n == 1) "" else "s"} — pairs whose model is on a rate-limit cooldown. No API calls are made. Genuine errors and successful pairs are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveBenched = false
                    actions.onRemoveBenchedPairs(run.key)
                }) { Text("Remove", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveBenched = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
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
