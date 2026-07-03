package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.MatchState
import com.ai.data.MatchStatus
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStorage
import com.ai.data.TournamentMethod
import com.ai.data.TournamentRunState
import com.ai.data.barTitle
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.ui.shared.shortModelName2
import com.ai.viewmodel.TournamentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ===================================================================
// Manage row — appears only once a tournament exists for the report.
// ===================================================================

@Composable
fun TournamentManageRow() {
    val engine = com.ai.ui.shared.LocalTournamentEngine.current ?: return
    val openState = com.ai.ui.shared.LocalTournamentOpenState.current
    val reportId = com.ai.ui.shared.LocalCurrentReportIdForSwipe.current ?: return
    val context = LocalContext.current
    // Hydrate from disk so a completed tournament's row reappears after an
    // app restart — the in-memory run state is lost on relaunch, but the
    // TOURNAMENT rows are still on disk. (Mirrors the L1 screen's hydrate;
    // the Fan-Meta row is disk-derived and so never had this gap.)
    LaunchedEffect(reportId) {
        if (engine.runByKey(reportId) == null) {
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
    }
    val runs by engine.runs.collectAsState()
    val run = runs[reportId] ?: return // no tournament on this report → no row

    // Static label — the title of the prompt this batch ran, NOT a live
    // size / done count (the row no longer ticks as matches complete).
    val rowText = run.tournamentPrompt.title.takeIf { it.isNotBlank() } ?: run.tournamentPrompt.name
    val tournamentIcon = com.ai.ui.shared.LocalMetadataIcons.current.tournament
        .takeIf { it.isNotBlank() } ?: com.ai.data.MetadataDefaults.TOURNAMENT
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
                else -> Text(tournamentIcon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
            }
            RowTypeCell("tournament")
            Text(
                text = rowText, color = AppColors.TextPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (run.totalCost > 0.0) {
                Text(formatCents(run.totalCost), fontSize = 10.sp,
                    color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
        }
        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
    }
}

/** Overlay-mount helper called by ReportsScreenNav when the open-state
 *  var is non-null (mirrors RegenerateBatchOverlay). */
@Composable
fun TournamentOverlay(reportId: String, engine: TournamentEngine, onClose: () -> Unit) {
    CompositionLocalProvider(com.ai.ui.shared.LocalNavigateToCurrentReport provides onClose) {
        TournamentScreen(engine, reportId, onClose)
    }
}

// ===================================================================
// Drill-in: L1 (stats + 2 grouping modes) → L2 (group) → L3 (match)
// ===================================================================

enum class TournamentGroupMode { TOURNAMENT_MODELS, REPORT_MODELS }

@Composable
fun TournamentScreen(engine: TournamentEngine, reportId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs by engine.runs.collectAsState()
    val throttled by engine.throttledMatches.collectAsState()
    val run = runs[reportId]

    // One-shot seed from disk on entry; the runner mirrors every match
    // result into the engine flow in a NonCancellable finally (runOneMatch),
    // so the L1 counters stay live without a 3s disk poll.
    androidx.compose.runtime.LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }

    val report by produceState<Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, reportId)
        }
    }
    val agents = report?.agents?.associateBy { it.agentId }.orEmpty()
    val localReportTitle = com.ai.ui.shared.LocalReportTitle.current
    val reportTitle = report?.barTitle?.takeIf { it.isNotBlank() }
        ?: localReportTitle?.takeIf { it.isNotBlank() }
        ?: "Report"
    val reportIcon = report?.icon?.takeIf { it.isNotBlank() }
        ?: com.ai.ui.shared.LocalMetadataIcons.current.reportIcon

    // REPORT_MODELS is the main L1; TOURNAMENT_MODELS selects the 🐜
    // "Tournament workers" sub-screen (judge-model grouping). The same
    // value is threaded into L2/L3 so a drill keeps the active grouping.
    var groupMode by rememberSaveable { mutableStateOf(TournamentGroupMode.REPORT_MODELS) }
    var level by rememberSaveable { mutableStateOf(1) }       // 1 = L1, 2 = L2, 3 = L3
    var groupKey by rememberSaveable { mutableStateOf("") }
    var matchKey by rememberSaveable { mutableStateOf("") }
    var confirmRedo by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteRun by rememberSaveable { mutableStateOf(false) }
    // The 👁 view icon opens the real View Tournament podium. We can't set
    // LocalPendingViewOverManage and have it observed while this overlay is up
    // (it early-returns above ReportsScreen), so we stage the jump AND close
    // the overlay in one tap: once openTournamentReportId clears, ReportsScreen
    // composes and ReportPrimaryOverlays renders the staged ViewJump.Tournament
    // over Manage (back from there pops to the Manage report screen).
    val pendingViewHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
    val onOpenTournamentView: (() -> Unit)? = run?.aggregateRowId?.let { rowId ->
        {
            pendingViewHolder?.value = com.ai.ui.shared.ViewJump.Tournament(rowId)
            onBack()
        }
    }
    // ⚖️ Judge-the-judges launcher: stash the request, then close this overlay
    // so the Manage screen re-renders and ConsumePendingJudgeJudges fires the
    // batch + the shared build-stage popup (same flow as the Manage Tournament
    // icon's "Judge the judges" row).
    val pendingJudgeJudges = com.ai.ui.shared.LocalPendingJudgeJudges.current
    val closeTournamentOverlay = com.ai.ui.shared.LocalNavigateToCurrentReport.current
    // Only offer ⚖️ once every match has settled — the judge panel is
    // derived from the COMPLETED match rows, so launching mid-run would
    // build it from whichever judges happened to finish so far (a wrong,
    // timing-dependent subset). The Manage-side launcher gates the same way
    // (judgeJudgesEnabled = tournamentDone); the L1 fast-path skipped it.
    val onJudgeJudges: (() -> Unit)? = pendingJudgeJudges?.takeIf { run?.allTerminal == true }?.let { slot ->
        { slot.value = reportId; closeTournamentOverlay?.invoke() }
    }

    BackHandler {
        when {
            level == 3 -> level = 2
            level == 2 -> level = 1
            // On the workers sub-screen's own L1, back returns to the main L1.
            groupMode == TournamentGroupMode.TOURNAMENT_MODELS -> groupMode = TournamentGroupMode.REPORT_MODELS
            else -> onBack()
        }
    }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(16.dp)) {
            TitleBar(
                helpTopic = "tournament_l1",
                title = "Tournament",
                subject = reportTitle,
                reportIcon = reportIcon,
                onBackClick = onBack
            )
            Spacer(Modifier.height(20.dp))
            Text("No tournament on this report.", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    when (level) {
        2 -> TournamentL2(run, agents, reportTitle, reportIcon, groupKey, groupMode,
            onOpenView = onOpenTournamentView,
            openMatch = { mk -> matchKey = mk; level = 3 },
            onBack = { level = 1 })
        3 -> TournamentL3(run, agents, reportTitle, reportIcon, matchKey, groupKey, groupMode,
            onOpenView = onOpenTournamentView,
            onBack = { level = 2 },
            onRerun = { scope.launch { engine.rerunMatch(context, reportId, matchKey) } },
            onStep = { mk -> matchKey = mk })
        else -> if (groupMode == TournamentGroupMode.TOURNAMENT_MODELS) {
            // 🐜 Tournament workers — judge-model grouping in its own screen.
            TournamentWorkersL1(run, agents, reportTitle, reportIcon, throttled,
                openGroup = { gk -> groupKey = gk; level = 2 },
                onRedo = { confirmRedo = true },
                onDeleteRun = { confirmDeleteRun = true },
                onOpenView = onOpenTournamentView,
                onBack = { groupMode = TournamentGroupMode.REPORT_MODELS })
        } else {
            TournamentL1(run, agents, reportTitle, reportIcon, throttled,
                openGroup = { gk -> groupKey = gk; level = 2 },
                onOpenWorkers = { groupMode = TournamentGroupMode.TOURNAMENT_MODELS },
                onRedo = { confirmRedo = true },
                onRestartFailed = { scope.launch { engine.restartFailedMatches(context, reportId) } },
                onRemoveFailed = { scope.launch { engine.removeFailedMatches(context, reportId) } },
                onDeleteRun = { confirmDeleteRun = true },
                onOpenView = onOpenTournamentView,
                onJudgeJudges = onJudgeJudges,
                onBack = onBack)
        }
    }

    if (confirmDeleteRun) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteRun = false },
            title = { androidx.compose.material3.Text("Delete this tournament?") },
            text = {
                androidx.compose.material3.Text("Drops every head-to-head match and the computed rankings for this run. Can't be undone.")
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
                }) { androidx.compose.material3.Text("Delete", color = AppColors.DangerAccent) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteRun = false }) { androidx.compose.material3.Text("Cancel") }
            }
        )
    }

    if (confirmRedo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRedo = false },
            title = { androidx.compose.material3.Text("Redo the tournament?") },
            text = {
                androidx.compose.material3.Text("Delete the current results and re-judge every head-to-head from scratch?")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRedo = false
                    level = 1
                    engine.rerunBatch(context, reportId)
                }) { androidx.compose.material3.Text("Redo") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRedo = false }) { androidx.compose.material3.Text("Cancel") }
            }
        )
    }
}

// ---------- grouping helpers ----------

private fun agentLabel(agents: Map<String, ReportAgent>, agentId: String?): String =
    agents[agentId]?.let { shortModelName2(it.model) } ?: "?"

/** L1 group rows for the active mode. groupKey encodes the mode. */
private data class GroupRow(val key: String, val label: String, val matches: List<MatchState>) {
    val done get() = matches.count { it.status == MatchStatus.DONE }
    val errored get() = matches.count { it.status == MatchStatus.ERROR }
    val running get() = matches.count { it.status == MatchStatus.RUNNING }
    val total get() = matches.size
    val cost get() = matches.sumOf { it.totalCost }
}

private fun buildGroups(run: TournamentRunState, agents: Map<String, ReportAgent>, mode: TournamentGroupMode): List<GroupRow> =
    when (mode) {
        TournamentGroupMode.TOURNAMENT_MODELS ->
            run.matches.values.filter { it.judgeModel != null }
                .groupBy { it.judgeModel!! }
                .map { (judge, ms) -> GroupRow("judge:$judge", shortModelName2(judge.substringAfterLast('/')), ms) }
                .sortedByDescending { it.total }
        TournamentGroupMode.REPORT_MODELS ->
            run.matches.values
                .groupBy { it.responseAId }
                .map { (aid, ms) -> GroupRow("answerer:$aid", agentLabel(agents, aid), ms) }
                .sortedBy { it.label }
    }

private fun matchesForGroup(run: TournamentRunState, groupKey: String): List<MatchState> {
    val (kind, value) = groupKey.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return when (kind) {
        "judge" -> run.matches.values.filter { it.judgeModel == value }
        "answerer" -> run.matches.values.filter { it.responseAId == value }
        else -> emptyList()
    }.sortedWith(compareBy({ it.responseAId }, { it.responseBId }))
}

// ---------- L1 ----------

@Composable
private fun TournamentL1(
    run: TournamentRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    throttled: Set<String>,
    openGroup: (String) -> Unit,
    onOpenWorkers: () -> Unit,
    onRedo: () -> Unit,
    onRestartFailed: () -> Unit,
    onRemoveFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onOpenView: (() -> Unit)?,
    onJudgeJudges: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    // Worker-pool batch (category B): no Bench bucket; rate-gated matches
    // count only under Wait, not also Run.
    val summary = deriveBatchSummary(
        items = run.matches.values,
        idOf = { it.id },
        statusOf = { it.status },
        throttledIds = throttled,
        family = BatchFamily.WORKER_POOL,
    )
    val counts = summary.counts
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "tournament_l1", title = "Tournament",
            subject = reportTitle,
            reportIcon = reportIcon,
            onBackClick = onBack,
            onOpenView = onOpenView,
            onBatchWorkers = onOpenWorkers,
            onJudgeJudges = onJudgeJudges,
            onReload = onRedo,
            onDelete = onDeleteRun
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            BatchStatsRow(listOf(
                Triple("Total", counts.total.toString(), AppColors.InfoAccent),
                Triple("Done", counts.done.toString(), AppColors.SuccessAccent),
                Triple("Error", summary.displayError.toString(), AppColors.DangerAccent),
                Triple("Run", counts.running.toString(), AppColors.WarningAccent),
                Triple("Wait", counts.wait.toString(), AppColors.CautionAccent),
                Triple("Queue", counts.queued.toString(), AppColors.QueueAccent),
                Triple("Costs", "${formatCents(run.totalCost, 2)} ¢", AppColors.InfoAccent)
            ))
            // Run-level progress bar while work is still outstanding (parity
            // with Fan Out / Fan Meta / Translation).
            if (summary.activeOutstanding && counts.total > 0 && !run.cancelled) {
                val finished = (counts.done + summary.displayError).toFloat() / counts.total
                LinearProgressIndicator(
                    progress = { finished },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.WarningAccent,
                    trackColor = AppColors.DividerDark
                )
            }
            Spacer(Modifier.height(12.dp))

            // Failed-row recovery: restart just the errored matches (or drop
            // them) instead of the destructive whole-run Redo.
            BatchFailedControls(
                erroredCount = summary.displayError,
                singular = "match", plural = "matches",
                onRestartFailed = onRestartFailed,
                onRemoveFailed = onRemoveFailed
            )

            // L1 lists the report (answerer) models. The judge-model
            // ("workers") grouping lives on the 🐜 Tournament workers screen.
            val groups = buildGroups(run, agents, TournamentGroupMode.REPORT_MODELS)
            if (groups.isEmpty()) {
                Text(
                    "One moment, collecting information…",
                    color = AppColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            val allSuccessful = run.totalMatches > 0 && run.doneCount == run.totalMatches
            groups.forEach { g ->
                // Per-row fill shows this report model's completed-or-failed matches.
                TournamentReportModelRow(g, allDone = allSuccessful) { openGroup(g.key) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- Workers (judge-model grouping) — the 🐜 sub-screen ----------

@Composable
private fun TournamentWorkersL1(
    run: TournamentRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    throttled: Set<String>,
    openGroup: (String) -> Unit,
    onRedo: () -> Unit,
    onDeleteRun: () -> Unit,
    onOpenView: (() -> Unit)?,
    onBack: () -> Unit
) {
    // Worker-pool batch (category B): no Bench bucket — same lens as L1.
    val summary = deriveBatchSummary(
        items = run.matches.values,
        idOf = { it.id },
        statusOf = { it.status },
        throttledIds = throttled,
        family = BatchFamily.WORKER_POOL,
    )
    val counts = summary.counts
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "tournament_workers", title = "Tournament workers",
            subject = reportTitle,
            reportIcon = reportIcon,
            onBackClick = onBack,
            // 🐜 grayed here (already in workers mode); clicking returns to models.
            onBatchWorkers = onBack,
            batchWorkersActive = false,
            onOpenView = onOpenView,
            onReload = onRedo,
            onDelete = onDeleteRun
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            BatchStatsRow(listOf(
                Triple("Total", counts.total.toString(), AppColors.InfoAccent),
                Triple("Done", counts.done.toString(), AppColors.SuccessAccent),
                Triple("Error", summary.displayError.toString(), AppColors.DangerAccent),
                Triple("Run", counts.running.toString(), AppColors.WarningAccent),
                Triple("Wait", counts.wait.toString(), AppColors.CautionAccent),
                Triple("Queue", counts.queued.toString(), AppColors.QueueAccent),
                Triple("Costs", "${formatCents(run.totalCost, 2)} ¢", AppColors.InfoAccent)
            ))
            if (summary.activeOutstanding && counts.total > 0 && !run.cancelled) {
                val finished = (counts.done + summary.displayError).toFloat() / counts.total
                LinearProgressIndicator(
                    progress = { finished },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.WarningAccent,
                    trackColor = AppColors.DividerDark
                )
            }
            Spacer(Modifier.height(12.dp))

            val groups = buildGroups(run, agents, TournamentGroupMode.TOURNAMENT_MODELS)
            if (groups.isEmpty()) {
                Text(
                    "One moment, collecting information…",
                    color = AppColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            val maxJudged = groups.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
            groups.forEach { g ->
                // One count column + a green row fill normalized to the busiest judge.
                TournamentJudgeModelRow(
                    group = g,
                    barFrac = g.total.toFloat() / maxJudged,
                    showBar = summary.activeOutstanding
                ) { openGroup(g.key) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TournamentJudgeModelRow(
    group: GroupRow,
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
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            group.total.toString(),
            color = AppColors.TextSecondary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp).width(32.dp)
        )
        Text(
            group.label,
            color = AppColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (group.cost > 0) {
            Text("${formatCents(group.cost)} ¢", color = AppColors.TextTertiary, fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
}

@Composable
private fun TournamentReportModelRow(group: GroupRow, allDone: Boolean, onClick: () -> Unit) {
    // Green fill = success fraction only (matches Judge's MatchSummaryRow).
    // Including errored here painted an all-failed group as a full green
    // "done" bar; the per-row ✗ glyph already signals the all-errored case.
    val finished = group.done
    val progressFraction = if (group.total > 0) finished.toFloat() / group.total else 0f
    val barColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .drawBehind {
                if (!allDone && progressFraction > 0f) {
                    drawRect(color = barColor, size = Size(size.width * progressFraction, size.height))
                }
            }
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!allDone) {
            val icon = when {
                group.running > 0 -> "⏳"
                group.total == 0 -> com.ai.data.MetadataIconsHolder.current.add
                group.errored > 0 && group.errored == group.total -> com.ai.data.MetadataIconsHolder.current.statusFailed
                group.done == group.total -> com.ai.data.MetadataIconsHolder.current.statusDone
                group.errored > 0 -> com.ai.data.MetadataIconsHolder.current.statusFailed
                else -> com.ai.data.MetadataIconsHolder.current.clockQueued
            }
            if (icon == "⏳") {
                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                    AnimatedHourglass(fontSize = 16.sp)
                }
            } else {
                Text(icon, fontSize = 16.sp, modifier = Modifier.width(20.dp))
            }
        }
        Text(
            group.label,
            color = AppColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (group.cost > 0) {
            Text("${formatCents(group.cost)} ¢", color = AppColors.TextTertiary, fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
}

// ---------- L2 ----------

@Composable
private fun TournamentL2(
    run: TournamentRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    groupKey: String,
    groupMode: TournamentGroupMode,
    onOpenView: (() -> Unit)?,
    openMatch: (String) -> Unit,
    onBack: () -> Unit
) {
    val matches = matchesForGroup(run, groupKey)
    val title = groupKey.substringAfter(":").let { if (groupKey.startsWith("judge:")) shortModelName2(it.substringAfterLast('/')) else agentLabel(agents, it) }
    val activeReportAgentId = groupKey.takeIf { groupMode == TournamentGroupMode.REPORT_MODELS }
        ?.substringAfter("answerer:", "")
    val screenTitle = when (groupMode) {
        TournamentGroupMode.TOURNAMENT_MODELS -> "Tournament - judge"
        TournamentGroupMode.REPORT_MODELS -> "Tournament - model"
    }
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "tournament_l2",
            title = screenTitle,
            subject = reportTitle,
            reportIcon = reportIcon,
            onOpenView = onOpenView,
            onBackClick = onBack
        )
        TournamentGreenSubject(title)
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") {
                TournamentL2Header(groupMode)
            }
            items(matches, key = { it.key }) { m ->
                MatchRowItem(m, agents, groupMode, activeReportAgentId) { openMatch(m.key) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TournamentGreenSubject(text: String) {
    Text(
        text = text,
        color = AppColors.SuccessAccent,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun TournamentL2Header(groupMode: TournamentGroupMode) {
    val labels = when (groupMode) {
        TournamentGroupMode.REPORT_MODELS -> listOf("Score", "Model", "Judge")
        TournamentGroupMode.TOURNAMENT_MODELS -> listOf("Result", "Model 1", "Model 2")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            TournamentL2Cell(
                text = label,
                color = AppColors.InfoAccent,
                fontWeight = FontWeight.SemiBold,
                columnIndex = index
            )
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.45f), thickness = 0.5.dp)
}

@Composable
private fun RowScope.TournamentL2Cell(
    text: String,
    color: Color = AppColors.TextPrimary,
    fontWeight: FontWeight = FontWeight.Normal,
    columnIndex: Int
) {
    val align = if (columnIndex == 0) TextAlign.Center else TextAlign.Start
    val columnWeight = if (columnIndex == 0) 0.62f else 1.19f
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = Modifier.weight(columnWeight).padding(
            start = if (columnIndex == 0) 0.dp else 2.dp,
            end = if (columnIndex == 0) 0.dp else 2.dp
        )
    )
}

private fun scoreText(m: MatchState, activeReportAgentId: String?): String {
    if (activeReportAgentId == null) return "..."
    return when {
        m.responseAId == activeReportAgentId -> when (m.verdict) {
            "A" -> "1"
            "B" -> "0"
            "tie" -> "1/2"
            else -> "..."
        }
        m.responseBId == activeReportAgentId -> when (m.verdict) {
            "A" -> "0"
            "B" -> "1"
            "tie" -> "1/2"
            else -> "..."
        }
        else -> "..."
    }
}

private fun resultText(m: MatchState): String = when (m.verdict) {
    "A" -> "1 - 0"
    "B" -> "0 - 1"
    "tie" -> "1/2 - 1/2"
    else -> "..."
}

@Composable
private fun MatchRowItem(
    m: MatchState,
    agents: Map<String, ReportAgent>,
    groupMode: TournamentGroupMode,
    activeReportAgentId: String?,
    onClick: () -> Unit
) {
    val labelA = agentLabel(agents, m.responseAId)
    val labelB = agentLabel(agents, m.responseBId)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (groupMode) {
            TournamentGroupMode.REPORT_MODELS -> {
                val opponent = if (m.responseAId == activeReportAgentId) labelB else labelA
                val judge = m.judgeModel?.let { shortModelName2(it.substringAfterLast('/')) } ?: "..."
                TournamentL2Cell(scoreText(m, activeReportAgentId), color = AppColors.SuccessAccent, columnIndex = 0)
                TournamentL2Cell(opponent, columnIndex = 1)
                TournamentL2Cell(judge, color = AppColors.TextSecondary, columnIndex = 2)
            }
            TournamentGroupMode.TOURNAMENT_MODELS -> {
                TournamentL2Cell(resultText(m), color = AppColors.SuccessAccent, columnIndex = 0)
                TournamentL2Cell(labelA, columnIndex = 1)
                TournamentL2Cell(labelB, columnIndex = 2)
            }
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
}

// ---------- L3 ----------

@Composable
private fun TournamentL3(
    run: TournamentRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    matchKey: String,
    groupKey: String,
    groupMode: TournamentGroupMode,
    onOpenView: (() -> Unit)?,
    onBack: () -> Unit,
    onRerun: () -> Unit,
    onStep: (String) -> Unit
) {
    val scoped = matchesForGroup(run, groupKey)
    val idx = scoped.indexOfFirst { it.key == matchKey }
    val m = scoped.getOrNull(idx) ?: run.matches[matchKey]
    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    var swipeDragX by remember(matchKey) { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "tournament_l3",
            title = "Tournament - Match",
            subject = reportTitle,
            reportIcon = reportIcon,
            onBackClick = onBack,
            onOpenView = onOpenView,
            onReload = onRerun
        )
        if (m == null) {
            Text("Match not found.", color = AppColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val labelA = agentLabel(agents, m.responseAId)
        val labelB = agentLabel(agents, m.responseBId)
        val colorA = sideColor(m.verdict, "A")
        val colorB = sideColor(m.verdict, "B")
        Column(
            Modifier.fillMaxSize()
                .pointerInput(idx, scoped.size, matchKey) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipeDragX = 0f },
                        onHorizontalDrag = { _, dragAmount -> swipeDragX += dragAmount },
                        onDragCancel = { swipeDragX = 0f },
                        onDragEnd = {
                            when {
                                swipeDragX > swipeThresholdPx -> scoped.getOrNull(idx - 1)?.let { onStep(it.key) }
                                swipeDragX < -swipeThresholdPx -> scoped.getOrNull(idx + 1)?.let { onStep(it.key) }
                            }
                            swipeDragX = 0f
                        }
                    )
                }
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(6.dp))
            // Verdict block.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground).padding(12.dp)
            ) {
                Text("A - $labelA", color = colorA, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("B - $labelB", color = colorB, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                m.confidence?.let {
                    Text("Confidence: ${String.format(Locale.US, "%.0f", it * 100)}%", color = AppColors.TextSecondary, fontSize = 12.sp)
                }
                if (!m.reason.isNullOrBlank()) Text(m.reason, color = AppColors.TextSecondary, fontSize = 12.sp)
                Text("Orientation: ${if (m.orientation == 0) "A-vs-B" else "B-vs-A (swapped)"}", color = AppColors.TextTertiary, fontSize = 11.sp)
                m.judgeModel?.let { Text("Judged by: ${it}", color = AppColors.TextTertiary, fontSize = 11.sp) }
                m.errorMessage?.let { Text("${com.ai.data.MetadataIconsHolder.current.warningPlain} $it", color = AppColors.DangerAccent, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(12.dp))
            ResponsePane("A - $labelA", colorA, agents[m.responseAId]?.responseBody)
            Spacer(Modifier.height(12.dp))
            ResponsePane("B - $labelB", colorB, agents[m.responseBId]?.responseBody)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun sideColor(verdict: String?, side: String): Color = when (verdict) {
    "tie" -> AppColors.InfoAccent
    side -> AppColors.SuccessAccent
    "A", "B" -> AppColors.DangerAccent
    else -> AppColors.TextSecondary
}

@Composable
private fun ResponsePane(header: String, headerColor: Color, body: String?) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .padding(12.dp)
    ) {
        Text(
            header,
            color = headerColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(body?.trim().orEmpty(), color = AppColors.TextSecondary, fontSize = 12.sp)
    }
}
