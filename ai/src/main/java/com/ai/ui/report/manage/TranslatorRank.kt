package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.TransRankCellState
import com.ai.data.TransRankCellStatus
import com.ai.data.TransRankRunState
import com.ai.data.aggregateTranslatorRanks
import com.ai.data.barTitle
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.viewmodel.TranslatorRankEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ===================================================================
// Manage row — one self-hiding row PER ranked language; opens its L1.
// ===================================================================

@Composable
fun TranslatorRankManageRow() {
    val engine = com.ai.ui.shared.LocalTranslatorRankEngine.current ?: return
    val openState = com.ai.ui.shared.LocalTransRankOpenState.current
    val reportId = com.ai.ui.shared.LocalCurrentReportIdForSwipe.current ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(reportId) {
        if (engine.runs.value.keys.none { it.startsWith("$reportId|") }) {
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
    }
    val runs by engine.runs.collectAsState()
    val myRuns = runs.filterKeys { it.startsWith("$reportId|") }.values
        .sortedBy { it.targetLanguageName }
    if (myRuns.isEmpty()) return
    val medal = com.ai.ui.shared.LocalMetadataIcons.current.translatorRank
        .takeIf { it.isNotBlank() } ?: com.ai.data.MetadataDefaults.TRANSLATOR_RANK
    Column(Modifier.fillMaxWidth()) {
        myRuns.forEach { run ->
            val lang = run.targetLanguageNative.takeIf { it.isNotBlank() } ?: run.targetLanguageName
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { openState?.value = run.key },
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    !run.allTerminal -> Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { AnimatedHourglass(fontSize = 16.sp) }
                    run.errorCount > 0 -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    else -> Text(medal, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                }
                RowTypeCell("transrank")
                Text(
                    "Rank the translators · $lang", color = AppColors.TextPrimary, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (run.totalCost > 0.0) {
                    Text(formatCents(run.totalCost), fontSize = 10.sp,
                        color = AppColors.TextTertiary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }
    }
}

/** Shared confirm dialog for launching a "Rank the translators" run, showing
 *  the live count of scoring calls. [pending] = (translationRunId, langName,
 *  langNative); [onLaunch] performs the actual start (build-stage handling
 *  differs per call site). Mounted by the Translations list (Run.kt) and the
 *  Translation run screens (Main.kt). Cancel/dismiss creates nothing. */
/** A pending 🏅 launch awaiting the confirm dialog. [overrideWorkers] carries
 *  the runtime-picked judges (MODEL_SELECTION_SELECT) so the displayed call
 *  count is computed against — and the run launches with — exactly that set.
 *  Null = use the prompt's configured / report-model workers. See audit bug 6. */
internal data class PendingRankRequest(
    val runId: String,
    val lang: String,
    val native: String,
    val overrideWorkers: List<com.ai.model.Worker>? = null
)

/** Saver so a pending 🏅 confirm survives a config change (audit bug 21).
 *  Persists the run identity only; a runtime worker pick (overrideWorkers) is
 *  dropped on restore — the rare rotate-mid-confirm case then recomputes the
 *  count and runs against the configured/report-model workers. */
internal val PendingRankRequestSaver =
    androidx.compose.runtime.saveable.listSaver<PendingRankRequest?, String>(
        save = { it?.let { r -> listOf(r.runId, r.lang, r.native) } ?: emptyList() },
        restore = { l -> if (l.size >= 3) PendingRankRequest(l[0], l[1], l[2], null) else null }
    )

@Composable
internal fun RankTranslatorsConfirmHost(
    reportId: String?,
    pending: androidx.compose.runtime.MutableState<PendingRankRequest?>,
    engine: TranslatorRankEngine?,
    onLaunch: (PendingRankRequest) -> Unit
) {
    val p = pending.value ?: return
    val context = LocalContextSafe()
    // Count against the same workers the run will use (the runtime pick, when
    // present), so the dialog can't show a number the run then contradicts.
    val count by produceState<Int?>(null, p.runId, reportId, p.overrideWorkers) {
        value = if (engine != null && reportId != null)
            engine.plannedCellCount(context, reportId, p.runId, p.overrideWorkers) else null
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { pending.value = null },
        title = { Text("Rank the translators?") },
        text = {
            Text(
                "Have the other models score the translated answers in ${p.lang.ifBlank { "this language" }} " +
                    "(0–100) and rank the translator models by average score." +
                    (count?.let { "\n\nThis is about $it scoring call${if (it == 1) "" else "s"}." } ?: "\n\n(counting…)")
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { val req = p; pending.value = null; onLaunch(req) }) { Text("Rank") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { pending.value = null }) { Text("Cancel") }
        }
    )
}

/** Overlay-mount helper called by ReportsScreenNav when the open-state var
 *  (the run key "$reportId|$sourceTranslationRunId") is non-null. */
@Composable
fun TranslatorRankOverlay(runKey: String, engine: TranslatorRankEngine, onClose: () -> Unit) {
    CompositionLocalProvider(com.ai.ui.shared.LocalNavigateToCurrentReport provides onClose) {
        TranslatorRankScreen(engine, runKey, onClose)
    }
}

@Composable
fun TranslatorRankScreen(engine: TranslatorRankEngine, runKey: String, onBack: () -> Unit) {
    val context = LocalContextSafe()
    val scope = rememberCoroutineScope()
    val reportId = runKey.substringBefore("|")
    val runs by engine.runs.collectAsState()
    val throttled by engine.throttledCells.collectAsState()
    val run = runs[runKey]

    LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }
    val report by produceState<Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val localReportTitle = com.ai.ui.shared.LocalReportTitle.current
    val reportTitle = report?.barTitle?.takeIf { it.isNotBlank() }
        ?: localReportTitle?.takeIf { it.isNotBlank() } ?: "Report"
    val reportIcon = report?.icon?.takeIf { it.isNotBlank() }
        ?: com.ai.ui.shared.LocalMetadataIcons.current.reportIcon

    var openTranslatorKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 🐜 second mode — per-judge-model breakdown (like Fan Meta / Tournament).
    var showWorkers by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        when {
            openTranslatorKey != null -> openTranslatorKey = null
            showWorkers -> showWorkers = false
            else -> onBack()
        }
    }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(16.dp)) {
            TitleBar(helpTopic = "translator_rank", title = "Rank the translators",
                subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack)
            Spacer(Modifier.height(20.dp))
            Text("One moment, collecting information…", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    val openKey = openTranslatorKey
    if (openKey != null) {
        TranslatorRankL2(run, openKey, reportTitle, reportIcon) { openTranslatorKey = null }
        return
    }

    if (showWorkers) {
        TranslatorRankWorkersScreen(run, reportTitle, reportIcon) { showWorkers = false }
        return
    }

    var confirmDeleteRun by rememberSaveable { mutableStateOf(false) }
    TranslatorRankL1(
        run = run, throttled = throttled, reportTitle = reportTitle, reportIcon = reportIcon,
        openTranslator = { openTranslatorKey = it },
        onOpenWorkers = { showWorkers = true },
        onRestartFailed = { scope.launch { engine.restartFailedCells(context, runKey) } },
        onDeleteRun = { confirmDeleteRun = true },
        onBack = onBack
    )

    if (confirmDeleteRun) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteRun = false },
            title = { Text("Delete Rank-the-translators?") },
            text = { Text("Drops every score cell and the translator ranking for this run. Can't be undone.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDeleteRun = false
                    // Call deleteRun DIRECTLY (it's not suspend — it drops the
                    // run synchronously and sweeps disk on viewModelScope).
                    // Wrapping it in scope.launch raced with onBack()
                    // cancelling this screen's scope, so the delete sometimes
                    // never fired and the rows stayed on disk.
                    engine.deleteRun(context, runKey)
                    onBack()
                }) { Text("Delete", color = AppColors.DangerAccent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteRun = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LocalContextSafe() = androidx.compose.ui.platform.LocalContext.current

// ---------- L1: leaderboard ----------

@Composable
private fun TranslatorRankL1(
    run: TransRankRunState,
    throttled: Set<String>,
    reportTitle: String,
    reportIcon: String,
    openTranslator: (String) -> Unit,
    onOpenWorkers: () -> Unit,
    onRestartFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onBack: () -> Unit
) {
    val cells = run.cells.values
    val total = cells.size
    val done = cells.count { it.status == TransRankCellStatus.DONE }
    // Each cell is a FIXED-MODEL judge call: a short-benched judge parks its
    // cells in Bench, and rate-gated cells in Wait — without those buckets the
    // queue looked stuck. Same carve as Judge-the-judges.
    val shortBenches by com.ai.data.ModelCooldownStore.shortBenches.collectAsState()
    fun shortBenched(p: String, m: String) = (shortBenches["$p:$m"] ?: 0L) > System.currentTimeMillis()
    val summary = deriveBatchSummary(
        items = cells,
        idOf = { it.id },
        statusOf = { it.status },
        throttledIds = throttled,
        family = BatchFamily.FIXED_MODEL,
        benchedOf = { shortBenched(it.judgeProviderId, it.judgeModel) },
    )
    val error = summary.displayError
    val running = summary.counts.running
    val benchCount = summary.counts.bench
    val throttledCount = summary.counts.wait
    val queued = summary.counts.queued
    val allTerminal = total > 0 && (done + error) == total
    val ranking = aggregateTranslatorRanks(cells)

    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "translator_rank", title = "Rank the translators",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack,
            onBatchWorkers = onOpenWorkers,
            onReload = if (error > 0) onRestartFailed else null,
            onDelete = onDeleteRun
        )
        Text(
            run.targetLanguageNative.takeIf { it.isNotBlank() } ?: run.targetLanguageName,
            color = AppColors.SuccessAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            BatchStatsRow(buildList {
                add(Triple("Total", total.toString(), AppColors.InfoAccent))
                add(Triple("Done", done.toString(), AppColors.SuccessAccent))
                add(Triple("Error", error.toString(), AppColors.DangerAccent))
                add(Triple("Run", running.toString(), AppColors.WarningAccent))
                if (summary.showBenchColumn) add(Triple("Bench", benchCount.toString(), AppColors.PrimaryAccent))
                add(Triple("Wait", throttledCount.toString(), AppColors.CautionAccent))
                add(Triple("Queue", queued.toString(), AppColors.QueueAccent))
                add(Triple("Costs", "${formatCents(run.totalCost, 2)}", AppColors.InfoAccent))
            })
            if (!allTerminal && total > 0 && !run.cancelled) {
                LinearProgressIndicator(
                    progress = { (done + error).toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.WarningAccent, trackColor = AppColors.DividerDark
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(12.dp))

            if (ranking.isEmpty()) {
                Text(
                    if (allTerminal) "No scores yet — the judges produced no usable scores."
                    else "Scoring…",
                    color = AppColors.TextSecondary, fontSize = 13.sp
                )
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                        Text("Translator", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("Items", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
                        Text("Score", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.35f), thickness = 0.5.dp)
                    ranking.forEachIndexed { i, r ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { openTranslator(r.translatorKey) }
                                .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${i + 1}", color = AppColors.TextTertiary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(22.dp))
                            Text(shortModelName(r.model), color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 4.dp))
                            Text("${r.itemCount}", color = AppColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(46.dp))
                            Text(fmtScore(r.avgScore), color = scoreColor(r.avgScore), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
                        }
                        if (i < ranking.lastIndex) HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.2f), thickness = 0.5.dp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- L2: one translator's items, each judge's score + reason ----------

@Composable
private fun TranslatorRankL2(
    run: TransRankRunState,
    translatorKey: String,
    reportTitle: String,
    reportIcon: String,
    onBack: () -> Unit
) {
    val cells = run.cells.values.filter { it.translatorKey == translatorKey }
    // Stable order both live and after hydration: sort item groups by their
    // earliest cell timestamp (creation ≈ source order), tie-broken by row id,
    // so the "Item N" numbering doesn't shuffle on restart. See audit bug 7.
    val byItem = cells.groupBy { it.translationRowId }.toList()
        .sortedWith(compareBy({ (_, cs) -> cs.minOf { it.timestamp } }, { (id, _) -> id }))
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "translator_rank", title = "Translator", subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack)
        Text(
            shortModelName(translatorKey.substringAfterLast('/')),
            color = AppColors.SuccessAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            byItem.forEachIndexed { idx, (grpId, itemCells) ->
                item(key = "h:$grpId") {
                    val scored = itemCells.mapNotNull { it.score }
                    val avg = if (scored.isNotEmpty()) scored.average() else null
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Item ${idx + 1}", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(avg?.let { fmtScore(it) } ?: "—", color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.35f), thickness = 0.5.dp)
                }
                items(itemCells, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(shortModelName(c.judgeModel), color = AppColors.TextPrimary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            when (c.status) {
                                TransRankCellStatus.RUNNING -> Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterEnd) { AnimatedHourglass(fontSize = 13.sp) }
                                TransRankCellStatus.ERROR -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 13.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                                else -> Text(c.score?.toString() ?: "·", color = scoreColor((c.score ?: 0).toDouble()),
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                            }
                        }
                        (c.reason ?: c.errorMessage)?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = if (c.errorMessage != null) AppColors.DangerAccent else AppColors.TextSecondary,
                                fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.18f), thickness = 0.5.dp)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------- 🐜 Workers: per-judge-model breakdown ----------

private data class JudgeBreak(val key: String, val model: String, val total: Int, val done: Int, val cost: Double)

@Composable
private fun TranslatorRankWorkersScreen(
    run: TransRankRunState,
    reportTitle: String,
    reportIcon: String,
    onBack: () -> Unit
) {
    val byJudge = remember(run) {
        run.cells.values.groupBy { it.judgeKey }
            .map { (key, cs) ->
                JudgeBreak(
                    key = key, model = cs.first().judgeModel, total = cs.size,
                    done = cs.count { it.status == TransRankCellStatus.DONE || it.status == TransRankCellStatus.ERROR },
                    cost = cs.sumOf { it.totalCost }
                )
            }
            .sortedBy { it.model.substringAfterLast('/').lowercase() }
    }
    val anyOutstanding = run.cells.values.any { it.status == TransRankCellStatus.RUNNING || it.status == TransRankCellStatus.PENDING }
    val maxDone = (byJudge.maxOfOrNull { it.done } ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "translator_rank_workers", title = "Rank workers",
            subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack,
            onBatchWorkers = onBack, batchWorkersActive = false
        )
        Text(
            run.targetLanguageNative.takeIf { it.isNotBlank() } ?: run.targetLanguageName,
            color = AppColors.SuccessAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)
        )
        Text("The models that scored the translations.", color = AppColors.TextTertiary, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            byJudge.forEach { j ->
                item(key = j.key) {
                    TransRankWorkerRow(j, barFrac = j.done.toFloat() / maxDone, showBar = anyOutstanding)
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TransRankWorkerRow(j: JudgeBreak, barFrac: Float, showBar: Boolean) {
    val barColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBehind { if (showBar && barFrac > 0f) drawRect(color = barColor, size = Size(size.width * barFrac, size.height)) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${j.done}/${j.total}", color = AppColors.TextSecondary, fontSize = 13.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp).width(56.dp))
        Text(shortModelName(j.model), color = AppColors.TextPrimary, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (j.cost > 0.0) Text(formatCents(j.cost), color = AppColors.TextTertiary, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp))
    }
}

private fun fmtScore(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

private fun scoreColor(v: Double) = when {
    v >= 80 -> AppColors.SuccessAccent
    v >= 50 -> AppColors.CautionAccent
    else -> AppColors.WarningAccent
}
