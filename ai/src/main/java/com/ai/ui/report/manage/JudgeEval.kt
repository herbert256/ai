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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.JudgeCellState
import com.ai.data.JudgeCellStatus
import com.ai.data.JudgeEvalRunState
import com.ai.data.JudgeStats
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStorage
import com.ai.data.analyzeJudges
import com.ai.data.barTitle
import com.ai.data.consensusForMatch
import com.ai.data.consensusStrength
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.ui.shared.shortModelName2
import com.ai.viewmodel.JudgeEvalEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ===================================================================
// Manage row — appears only once a judge-eval run exists for the report.
// ===================================================================

@Composable
fun JudgeEvalManageRow() {
    val engine = com.ai.ui.shared.LocalJudgeEvalEngine.current ?: return
    val openState = com.ai.ui.shared.LocalJudgeEvalOpenState.current
    val reportId = com.ai.ui.shared.LocalCurrentReportIdForSwipe.current ?: return
    val context = LocalContext.current
    LaunchedEffect(reportId) {
        if (engine.runByKey(reportId) == null) {
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
    }
    val runs by engine.runs.collectAsState()
    val run = runs[reportId] ?: return // no judge-eval on this report → no row

    // Label = the title of the prompt this batch ran; while cells are
    // still settling a live done/total counter ticks next to the cost.
    val rowText = run.prompt.title.takeIf { it.isNotBlank() } ?: run.prompt.name
    val judgesIcon = com.ai.ui.shared.LocalMetadataIcons.current.judges
        .takeIf { it.isNotBlank() } ?: com.ai.data.MetadataDefaults.JUDGES
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { openState?.value = reportId },
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                !run.allTerminal -> Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    AnimatedHourglass(fontSize = 16.sp)
                }
                run.errorCount > 0 -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                else -> Text(judgesIcon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
            }
            RowTypeCell("judges")
            Text(
                text = rowText, color = AppColors.TextPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (!run.allTerminal && run.totalCells > 0) {
                Text("${run.doneCount}/${run.totalCells}", fontSize = 10.sp,
                    color = AppColors.WarningAccent, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp))
            }
            if (run.totalCost > 0.0) {
                Text(formatCents(run.totalCost), fontSize = 10.sp,
                    color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
        }
        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
    }
}

/** Overlay-mount helper called by ReportsScreenNav when the open-state var
 *  is non-null (mirrors TournamentOverlay). */
@Composable
fun JudgeEvalOverlay(reportId: String, engine: JudgeEvalEngine, onClose: () -> Unit) {
    CompositionLocalProvider(com.ai.ui.shared.LocalNavigateToCurrentReport provides onClose) {
        JudgeEvalScreen(engine, reportId, onClose)
    }
}

// ===================================================================
// Drill-in: L1 (stats + agreement leaderboard) → L2 (a judge's verdicts
// vs consensus) → L3 (one match detail).
// ===================================================================

@Composable
fun JudgeEvalScreen(engine: JudgeEvalEngine, reportId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navigateToRoute = com.ai.ui.shared.LocalNavigateToRoute.current
    val aiSettings = com.ai.ui.shared.LocalAiSettings.current
    val navigateHome = com.ai.ui.shared.LocalNavigateHome.current
    val runs by engine.runs.collectAsState()
    val throttled by engine.throttledCells.collectAsState()
    val run = runs[reportId]

    // One-shot seed from disk on entry; the runner mirrors every cell
    // result into the engine flow in a NonCancellable finally (runOneCell),
    // so the L1 counters stay live without a 3s disk poll.
    LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }

    val report by produceState<Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val agents = report?.agents?.associateBy { it.agentId }.orEmpty()
    val localReportTitle = com.ai.ui.shared.LocalReportTitle.current
    val reportTitle = report?.barTitle?.takeIf { it.isNotBlank() }
        ?: localReportTitle?.takeIf { it.isNotBlank() }
        ?: "Report"
    val reportIcon = report?.icon?.takeIf { it.isNotBlank() }
        ?: com.ai.ui.shared.LocalMetadataIcons.current.reportIcon

    var level by rememberSaveable { mutableStateOf(1) }       // 1 = L1, 2 = L2, 3 = L3
    // false = L2 is a judge's matches; true = L2 is a match's judges.
    var byMatch by rememberSaveable { mutableStateOf(false) }
    var judgeKey by rememberSaveable { mutableStateOf("") }
    var matchKey by rememberSaveable { mutableStateOf("") }
    var confirmDeleteJudge by rememberSaveable { mutableStateOf<String?>(null) }
    // Set by the 🆕 icon (Judges mode) → opens the model picker to add a judge.
    var showAddJudge by rememberSaveable { mutableStateOf(false) }
    // Set when the user taps ✏️ to edit the swarm; checked on the way back
    // (the overlay is disposed on nav-out and recomposed on nav-in) to offer a
    var confirmRedo by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteRun by rememberSaveable { mutableStateOf(false) }
    var l1Mode by rememberSaveable { mutableStateOf(JudgeEvalL1Mode.JUDGES) }
    // NOTE: no "swarm changed → rerun?" offer. Judge-the-judges derives its
    // judge panel from the Tournament's actual MATCH rows, not the
    // configurable swarm (startRun ignores overrideWorkers/swarm), so a
    // rerun always uses the SAME judges — the old offer fired spuriously
    // whenever the swarm-resolved set differed from the tournament set
    // (normal under REPORT_MODELS) and re-billed the batch for no change.
    // Debounce navigation: a single tap whose target navigates away can have
    // its release land on the freshly-composed destination's clickable at the
    // same spot (the L1 match row → by-match list "tap-through"). Ignore any
    // second nav within 350 ms of the first.
    var lastNavMs by remember { mutableStateOf(0L) }
    val navOk: () -> Boolean = {
        val now = System.currentTimeMillis()
        if (now - lastNavMs < 350L) false else { lastNavMs = now; true }
    }

    BackHandler {
        when {
            level == 3 -> level = 2
            level == 2 -> level = 1
            else -> onBack()
        }
    }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(16.dp)) {
            TitleBar(
                helpTopic = "judge_eval_l1", title = "Judge the judges",
                subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack
            )
            Spacer(Modifier.height(20.dp))
            Text("One moment, collecting information…", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    // 🆕 add-a-judge → the same full-screen model picker the Edit-swarm screen
    // uses. On pick: add the model to the judges' swarm AND judge only the new
    // model across the run's existing matches (its row alone is added/updated).
    // Judges already in the run are dimmed so they can't be double-added.
    if (showAddJudge) {
        // Dim judges already in the run — keyed off each cell's real
        // (judgeProviderId, judgeModel) rather than splitting the "prov/model"
        // key, since a model id can itself contain '/'.
        val already = remember(run.cells) {
            run.cells.values.mapNotNull { c ->
                com.ai.data.AppService.findById(c.judgeProviderId)?.let { it to c.judgeModel }
            }.toSet()
        }
        com.ai.ui.other.ReportSelectModelsScreen(
            aiSettings = aiSettings,
            alreadyAdded = already,
            titleText = "Add a judge model",
            onConfirm = { (provider, model) ->
                engine.addJudgeToSwarm(provider, model)
                engine.addJudgeToRun(context, reportId, provider, model)
                showAddJudge = false
            },
            onBack = { showAddJudge = false },
            onNavigateHome = navigateHome
        )
        return
    }

    when (level) {
        2 -> if (byMatch) {
            JudgeEvalMatchScreen(run, agents, reportTitle, reportIcon, matchKey,
                openJudge = { jk -> if (navOk()) { judgeKey = jk; level = 3 } },
                onBack = { level = 1 })
        } else {
            JudgeEvalL2(run, agents, reportTitle, reportIcon, judgeKey,
                openMatch = { mk -> if (navOk()) { matchKey = mk; level = 3 } },
                onDelete = { confirmDeleteJudge = judgeKey },
                onBack = { level = 1 })
        }
        3 -> JudgeEvalL3(run, agents, reportTitle, reportIcon, judgeKey, matchKey,
            onBack = { level = 2 },
            onRerun = { scope.launch { engine.rerunCell(context, reportId, "$judgeKey|$matchKey") } })
        else -> JudgeEvalL1(run, agents, throttled, reportTitle, reportIcon,
            openJudge = { jk -> if (navOk()) { judgeKey = jk; byMatch = false; level = 2 } },
            openMatch = { mk -> if (navOk()) { matchKey = mk; byMatch = true; level = 2 } },
            mode = l1Mode, setMode = { l1Mode = it },
            onAddJudge = { showAddJudge = true },
            onDeleteJudge = { confirmDeleteJudge = it },
            onEditSwarm = {
                // Swarm edits affect FUTURE tournaments only — this batch's
                // judges are fixed to the tournament that ran. No rerun offer
                // on return (see the note at the top of this composable).
                engine.activeSwarmId()?.let {
                    navigateToRoute(com.ai.ui.navigation.NavRoutes.settingsSwarmEdit(it))
                }
            },
            onRedo = { confirmRedo = true },
            onRestartFailed = { scope.launch { engine.restartFailedCells(context, reportId) } },
            onRemoveFailed = { scope.launch { engine.removeFailedCells(context, reportId) } },
            onDeleteRun = { confirmDeleteRun = true },
            onBack = onBack)
    }

    if (confirmDeleteRun) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteRun = false },
            title = { Text("Delete Judge-the-judges?") },
            text = {
                Text("Drops every judge cell and the consensus analysis for this run. Can't be undone.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDeleteRun = false
                    // Call deleteRun DIRECTLY (not suspend — it self-schedules
                    // on viewModelScope). Wrapping it in scope.launch raced
                    // with onBack() cancelling this screen's scope, so the
                    // delete sometimes never fired (same shipped bug as
                    // TranslatorRank).
                    engine.deleteRun(context, reportId)
                    onBack()
                }) { Text("Delete", color = AppColors.DangerAccent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteRun = false }) { Text("Cancel") }
            }
        )
    }

    confirmDeleteJudge?.let { jk ->
        val cell = run.cells.values.firstOrNull { it.judgeKey == jk }
        val prov = cell?.judgeProviderId ?: jk.substringBeforeLast('/')
        val mdl = cell?.judgeModel ?: jk.substringAfterLast('/')
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteJudge = null },
            title = { Text("Remove judge?") },
            text = {
                Text("Remove ${shortModelName2(mdl)}? Its verdicts are deleted from this run. If this report uses its own models, it's also removed from the report and all its batches; otherwise it's dropped from the judges' swarm so future Tournaments / Judge-the-judges runs won't use it.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    engine.onUserRemoveJudge(context, reportId, prov, mdl)
                    confirmDeleteJudge = null
                    level = 1
                }) { Text("Remove") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteJudge = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmRedo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRedo = false },
            title = { Text("Redo the batch?") },
            text = {
                Text("Delete the current results and re-judge all matches from scratch with the current swarm?")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRedo = false
                    level = 1
                    engine.rerunBatch(context, reportId)
                }) { Text("Redo") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRedo = false }) { Text("Cancel") }
            }
        )
    }

}

// ---------- L1 ----------

/** L1 view modes, switched by the top toggle (like Fan Meta). */
enum class JudgeEvalL1Mode { JUDGES, MATCHES }

@Composable
private fun JudgeEvalL1(
    run: JudgeEvalRunState,
    agents: Map<String, ReportAgent>,
    throttled: Set<String>,
    reportTitle: String,
    reportIcon: String,
    openJudge: (String) -> Unit,
    openMatch: (String) -> Unit,
    mode: JudgeEvalL1Mode,
    setMode: (JudgeEvalL1Mode) -> Unit,
    onAddJudge: () -> Unit,
    onDeleteJudge: (String) -> Unit,
    onEditSwarm: () -> Unit,
    onRedo: () -> Unit,
    onRestartFailed: () -> Unit,
    onRemoveFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onBack: () -> Unit
) {
    // Each judge cell is a fixed-model call (category A) — a benched judge
    // can't be substituted. Bench = cells whose judge model is short-benched
    // (parked on a 429/529 backoff, waiting to re-queue); it takes precedence
    // over Run / Wait / Queue so a parked cell shows there.
    val shortBenches by com.ai.data.ModelCooldownStore.shortBenches.collectAsState()
    fun shortBenched(p: String, m: String): Boolean =
        (shortBenches["$p:$m"] ?: 0L) > System.currentTimeMillis()
    // Fixed-model batch (category A): any non-done cell of a
    // short-benched judge is parked in Bench.
    val summary = deriveBatchSummary(
        items = run.cells.values,
        idOf = { it.id },
        statusOf = { it.status },
        throttledIds = throttled,
        family = BatchFamily.FIXED_MODEL,
        benchedOf = { shortBenched(it.judgeProviderId, it.judgeModel) },
    )
    val counts = summary.counts
    val errorCount = summary.displayError
    val runningCount = counts.running
    val benchCount = counts.bench
    val throttledCount = counts.wait
    val queuedCount = counts.queued
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_l1", title = "Judge the judges",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack, onEdit = onEditSwarm, onReload = onRedo, onDelete = onDeleteRun,
            // 🆕 add a judge — Judges mode only (the picker adds it to the swarm
            // and judges only the new model across the existing matches).
            onAdd = if (mode == JudgeEvalL1Mode.JUDGES) onAddJudge else null
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            com.ai.ui.shared.ReportRunEvidenceNotice(run.reportId, com.ai.data.SecondaryKind.JUDGES, run.runId)
            BatchStatsRow(buildList {
                add(Triple("Total", run.totalCells.toString(), AppColors.InfoAccent))
                add(Triple("Done", run.doneCount.toString(), AppColors.SuccessAccent))
                add(Triple("Error", errorCount.toString(), AppColors.DangerAccent))
                add(Triple("Run", runningCount.toString(), AppColors.WarningAccent))
                if (summary.showBenchColumn) {
                    add(Triple("Bench", benchCount.toString(), AppColors.PrimaryAccent))
                }
                add(Triple("Wait", throttledCount.toString(), AppColors.CautionAccent))
                add(Triple("Queue", queuedCount.toString(), AppColors.QueueAccent))
                add(Triple("Costs", "${formatCents(run.totalCost, 2)}", AppColors.InfoAccent))
            })
            // Run-level progress bar while work is still outstanding (parity
            // with Fan Out / Fan Meta / Translation).
            if (summary.activeOutstanding && run.totalCells > 0 && !run.cancelled) {
                val finished = (run.doneCount + errorCount).toFloat() / run.totalCells
                LinearProgressIndicator(
                    progress = { finished },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.WarningAccent,
                    trackColor = AppColors.DividerDark
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${run.judgeCount} judges · ${run.matchCount} matches",
                color = AppColors.TextSecondary, fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // Failed-cell recovery: restart just the errored judge cells (or
            // drop them) instead of the destructive whole-run Redo.
            BatchFailedControls(
                erroredCount = errorCount,
                singular = "judge cell", plural = "judge cells",
                onRestartFailed = onRestartFailed,
                onRemoveFailed = onRemoveFailed
            )

            // Mode toggle — Judges (default) vs Matches, like Fan Meta.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == JudgeEvalL1Mode.JUDGES,
                    onClick = { setMode(JudgeEvalL1Mode.JUDGES) },
                    label = { Text("Judges", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = mode == JudgeEvalL1Mode.MATCHES,
                    onClick = { setMode(JudgeEvalL1Mode.MATCHES) },
                    label = { Text("Matches", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))

            if (mode == JudgeEvalL1Mode.JUDGES) {
                if (run.allTerminal) {
                    val stats2 = analyzeJudges(run.cells.values.toList())
                    Text(
                        "Independent agreement ${if (stats2.any { it.agreementCount > 0 }) pct(stats2.consensusStrength()) else "unavailable (need ≥3 judges)"}",
                        color = AppColors.SuccessAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    // One-line-per-judge table, in a card: # / Model / Cost(¢) /
                    // API time / Agreement-with-consensus.
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                            Text("Model", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("¢", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
                            Text("Time", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(50.dp))
                            Text("Indep.", color = AppColors.InfoAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                            Spacer(Modifier.width(28.dp))   // aligns with the per-row ✗ remove column
                        }
                        HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.35f), thickness = 0.5.dp)
                        stats2.forEachIndexed { i, s ->
                            JudgeLeaderRow(rank = i + 1, s = s, onDelete = { onDeleteJudge(s.judgeKey) }) { openJudge(s.judgeKey) }
                            if (i < stats2.lastIndex) HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.2f), thickness = 0.5.dp)
                        }
                    }
                } else {
                    // Per-judge progress while running — the green bar fills to the
                    // judge's own percentage done (DONE + ERROR) / total, like Fan Out.
                    val byJudge = run.cells.values.groupBy { it.judgeKey }
                        .toList().sortedBy { it.first }
                    byJudge.forEach { (jk, cells) ->
                        val done = cells.count { it.status == JudgeCellStatus.DONE || it.status == JudgeCellStatus.ERROR }
                        JudgeProgressRow(
                            label = shortModelName2(jk.substringAfterLast('/')),
                            done = done, total = cells.size,
                            barFrac = if (cells.isNotEmpty()) done.toFloat() / cells.size else 0f
                        ) { openJudge(jk) }
                    }
                }
            } else {
                // Matches mode: one row per match in a card; each → the per-match
                // list of judges (JudgeEvalMatchScreen → L3).
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground)) {
                    val matches = buildMatchSummaries(run, agents)
                    matches.forEachIndexed { i, m ->
                        MatchSummaryRow(m, allDone = run.allTerminal) { openMatch(m.matchKey) }
                        if (i < matches.lastIndex) HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.2f), thickness = 0.5.dp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun JudgeProgressRow(label: String, done: Int, total: Int, barFrac: Float, onClick: () -> Unit) {
    val barColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBehind {
                if (barFrac > 0f) drawRect(color = barColor, size = Size(size.width * barFrac, size.height))
            }
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$done/$total", color = AppColors.TextSecondary, fontSize = 13.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp).width(56.dp))
        Text(label, color = AppColors.TextPrimary, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
    }
}

/** One-line judge row: # / Model / Cost(¢) / API time / Agreement. */
@Composable
private fun JudgeLeaderRow(rank: Int, s: JudgeStats, onDelete: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 10.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$rank", color = AppColors.TextTertiary, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(22.dp))
        Text(shortModelName2(s.judgeModel), color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 4.dp))
        Text(formatCents(s.totalCost, 2), color = AppColors.TextSecondary, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
        Text(fmtSecs(s.totalMs), color = AppColors.TextSecondary, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(50.dp))
        Text(if (s.agreementCount > 0) "${pct(s.agreement)} (${s.agreementCount})" else "—", color = agreementColor(s.agreement), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        // ✗ remove this judge — from the batch AND the swarm (confirm dialog).
        // A plain glyph (not emoji) so it honours the red tint.
        Text(
            com.ai.data.MetadataIconsHolder.current.crossMark,
            color = AppColors.DangerAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp).clickable { onDelete() }.padding(vertical = 2.dp)
        )
    }
}

// ---------- L2: a judge's verdicts vs consensus ----------

@Composable
private fun JudgeEvalL2(
    run: JudgeEvalRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    judgeKey: String,
    openMatch: (String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    val cells = run.cells.values.filter { it.judgeKey == judgeKey }
        .sortedWith(compareBy({ it.responseAId }, { it.responseBId }))
    val consensus = run.cells.values.groupBy { it.matchKey }
        .mapValues { (_, cs) -> consensusForMatch(cs.mapNotNull { it.verdict }) }
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_l2", title = "Judge",
            subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack,
            onDelete = onDelete
        )
        Text(
            shortModelName2(judgeKey.substringAfterLast('/')),
            color = AppColors.SuccessAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("Match", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Verdict", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                    Text("Indep.", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.width(30.dp))
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.45f), thickness = 0.5.dp)
            }
            items(cells, key = { it.key }) { c ->
                val cons = consensus[c.matchKey]
                val agree = c.verdict != null && cons != null && c.verdict == cons
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { openMatch(c.matchKey) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${shortModelName2(agents[c.responseAId]?.model ?: "?")} vs ${shortModelName2(agents[c.responseBId]?.model ?: "?")}",
                        color = AppColors.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(verdictGlyph(c.verdict, c.status), color = AppColors.TextPrimary, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                    Text(
                        if (c.verdict == null) "—" else if (agree) com.ai.data.MetadataIconsHolder.current.checkMark else com.ai.data.MetadataIconsHolder.current.crossMark,
                        color = if (c.verdict == null) AppColors.TextTertiary else if (agree) AppColors.SuccessAccent else AppColors.DangerAccent,
                        fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp)
                    )
                    JudgeTraceBug(c.traceFile)
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.25f), thickness = 0.5.dp)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------- L3: one match detail ----------

@Composable
private fun JudgeEvalL3(
    run: JudgeEvalRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    judgeKey: String,
    matchKey: String,
    onBack: () -> Unit,
    onRerun: () -> Unit
) {
    val cell = run.cells.values.firstOrNull { it.judgeKey == judgeKey && it.matchKey == matchKey }
    val consensus = run.cells.values.filter { it.matchKey == matchKey }.mapNotNull { it.verdict }
        .let { consensusForMatch(it) }
    val navigateToRoute = com.ai.ui.shared.LocalNavigateToRoute.current
    val traceCtx = LocalContext.current
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_l3", title = "Match",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack, onReload = onRerun,
            onTrace = {
                val tf = cell?.traceFile
                if (!tf.isNullOrBlank()) navigateToRoute(com.ai.ui.navigation.NavRoutes.traceDetail(tf))
                else android.widget.Toast.makeText(traceCtx, "No trace (enable tracing in Settings)", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
        if (cell == null) {
            Spacer(Modifier.height(20.dp))
            Text("Match not found.", color = AppColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val aAgent = agents[cell.responseAId]
        val bAgent = agents[cell.responseBId]
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Judge: ${shortModelName2(judgeKey.substringAfterLast('/'))}",
                color = AppColors.SuccessAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            // Verdict block.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(AppColors.CardBackground).padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Verdict: ", color = AppColors.TextSecondary, fontSize = 13.sp)
                    Text(verdictLabel(cell.verdict), color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    cell.confidence?.let {
                        Text("conf ${String.format(Locale.US, "%.2f", it)}", color = AppColors.TextTertiary, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text("Consensus: ${verdictLabel(consensus)}", color = AppColors.TextTertiary, fontSize = 12.sp)
                cell.reason?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = AppColors.TextPrimary, fontSize = 13.sp)
                }
                cell.errorMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = AppColors.DangerAccent, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            ResponsePane("A — ${shortModelName2(aAgent?.model ?: "?")}", aAgent?.responseBody.orEmpty(),
                highlight = cell.verdict == "A")
            Spacer(Modifier.height(10.dp))
            ResponsePane("B — ${shortModelName2(bAgent?.model ?: "?")}", bAgent?.responseBody.orEmpty(),
                highlight = cell.verdict == "B")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResponsePane(header: String, body: String, highlight: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (highlight) AppColors.SuccessAccent.copy(alpha = 0.12f) else AppColors.CardBackground)
            .padding(12.dp)
    ) {
        Text(header, color = if (highlight) AppColors.SuccessAccent else AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body.ifBlank { "(empty)" }, color = AppColors.TextPrimary, fontSize = 13.sp)
    }
}

// ---------- helpers ----------

private fun pct(v: Double): String = "${(v * 100).toInt()}%"

/** Total API time, compact: "12.3s" or "1.5m". */
private fun fmtSecs(ms: Long): String {
    val s = ms / 1000.0
    return if (s >= 60) String.format(java.util.Locale.US, "%.1fm", s / 60.0) else String.format(java.util.Locale.US, "%.1fs", s)
}

private fun agreementColor(v: Double): Color = when {
    v >= 0.7 -> AppColors.SuccessAccent
    v >= 0.5 -> AppColors.CautionAccent
    else -> AppColors.WarningAccent
}

private fun verdictGlyph(verdict: String?, status: JudgeCellStatus): String = when {
    verdict == "A" -> "A"
    verdict == "B" -> "B"
    verdict == "tie" -> "tie"
    status == JudgeCellStatus.ERROR -> com.ai.data.MetadataIconsHolder.current.statusFailed
    status == JudgeCellStatus.RUNNING -> "…"
    else -> "·"
}

private fun verdictLabel(verdict: String?): String = when (verdict) {
    "A" -> "A wins"
    "B" -> "B wins"
    "tie" -> "Tie"
    else -> "—"
}

// ---------- matches table (L1, second table) ----------

private data class MatchSummary(
    val matchKey: String,
    val aLabel: String,
    val bLabel: String,
    val consensus: String?,
    val agreeCount: Int,
    val votedCount: Int,
    /** Terminal cells (judged + errored) for this match — the bar numerator. */
    val done: Int,
    /** Total cells (judges) for this match — the bar denominator. */
    val total: Int
)

private fun buildMatchSummaries(run: JudgeEvalRunState, agents: Map<String, ReportAgent>): List<MatchSummary> =
    run.cells.values.groupBy { it.matchKey }
        .map { (mk, cs) ->
            val first = cs.first()
            val verdicts = cs.mapNotNull { it.verdict }
            val cons = consensusForMatch(verdicts)
            MatchSummary(
                matchKey = mk,
                aLabel = shortModelName2(agents[first.responseAId]?.model ?: "?"),
                bLabel = shortModelName2(agents[first.responseBId]?.model ?: "?"),
                consensus = cons,
                agreeCount = cs.count { it.verdict != null && cons != null && it.verdict == cons },
                votedCount = verdicts.size,
                done = cs.count { it.status == JudgeCellStatus.DONE || it.status == JudgeCellStatus.ERROR },
                total = cs.size
            )
        }
        .sortedWith(compareBy({ it.aLabel }, { it.bLabel }))

@Composable
private fun MatchSummaryRow(m: MatchSummary, allDone: Boolean, onClick: () -> Unit) {
    // Green progress fill = this match's judged fraction, like Fan Meta's
    // "Report models" rows. Hidden once the whole run is done.
    val progressFraction = if (m.total > 0) m.done.toFloat() / m.total else 0f
    val barColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBehind {
                if (!allDone && progressFraction > 0f) {
                    drawRect(color = barColor, size = Size(size.width * progressFraction, size.height))
                }
            }
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${m.aLabel} vs ${m.bLabel}", color = AppColors.TextPrimary, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(verdictLabel(m.consensus), color = AppColors.TextSecondary, fontSize = 12.sp,
            modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
        Text("${m.agreeCount}/${m.votedCount}", color = AppColors.TextTertiary, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}

// ---------- by-match screen: one match → the list of judges → L3 ----------

@Composable
private fun JudgeEvalMatchScreen(
    run: JudgeEvalRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    matchKey: String,
    openJudge: (String) -> Unit,
    onBack: () -> Unit
) {
    val cells = run.cells.values.filter { it.matchKey == matchKey }.sortedBy { it.judgeKey }
    val cons = consensusForMatch(cells.mapNotNull { it.verdict })
    val first = cells.firstOrNull()
    val pairLabel = if (first != null)
        "${shortModelName2(agents[first.responseAId]?.model ?: "?")} vs ${shortModelName2(agents[first.responseBId]?.model ?: "?")}"
    else "Match"
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_match", title = "Match",
            subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack
        )
        Text(pairLabel, color = AppColors.SuccessAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text("Consensus: ${verdictLabel(cons)}", color = AppColors.TextTertiary, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("Judge", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Verdict", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                    Text("Indep.", color = AppColors.InfoAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.width(30.dp))
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.45f), thickness = 0.5.dp)
            }
            items(cells, key = { it.key }) { c ->
                val agree = c.verdict != null && cons != null && c.verdict == cons
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { openJudge(c.judgeKey) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(shortModelName2(c.judgeModel), color = AppColors.TextPrimary, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(verdictGlyph(c.verdict, c.status), color = AppColors.TextPrimary, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                    Text(
                        if (c.verdict == null) "—" else if (agree) com.ai.data.MetadataIconsHolder.current.checkMark else com.ai.data.MetadataIconsHolder.current.crossMark,
                        color = if (c.verdict == null) AppColors.TextTertiary else if (agree) AppColors.SuccessAccent else AppColors.DangerAccent,
                        fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp)
                    )
                    JudgeTraceBug(c.traceFile)
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.25f), thickness = 0.5.dp)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Per-row 🐞 trace deep-link — opens the judging call's API trace when one
 *  was recorded (tracing on), else a toast. Mirrors the View Tournament's. */
@Composable
private fun JudgeTraceBug(traceFile: String?) {
    val navigateToRoute = com.ai.ui.shared.LocalNavigateToRoute.current
    val context = LocalContext.current
    Text(
        com.ai.data.MetadataIconsHolder.current.traces, fontSize = 13.sp,
        color = if (traceFile.isNullOrBlank()) AppColors.TextDisabled else AppColors.TextPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                if (!traceFile.isNullOrBlank()) navigateToRoute(com.ai.ui.navigation.NavRoutes.traceDetail(traceFile))
                else android.widget.Toast.makeText(context, "No trace (enable tracing in Settings)", android.widget.Toast.LENGTH_SHORT).show()
            }
    )
}
