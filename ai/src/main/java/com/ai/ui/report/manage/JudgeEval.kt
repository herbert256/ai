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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.ai.viewmodel.JudgeEvalEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val rowText = buildString {
        append("${run.doneCount} / ${run.totalCells}")
        if (run.errorCount > 0) append(" · ${run.errorCount} failed")
        else if (run.allTerminal) append(" · done")
    }
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
                run.errorCount > 0 -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                else -> Text(judgesIcon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
            }
            RowTypeCell("judges")
            Text(
                text = rowText, color = Color.White, fontSize = 13.sp,
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
    val runs by engine.runs.collectAsState()
    val throttled by engine.throttledCells.collectAsState()
    val run = runs[reportId]

    LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }
    LaunchedEffect(reportId, run?.allTerminal) {
        while (true) {
            val r = engine.runByKey(reportId)
            if (r != null && r.allTerminal) break
            delay(3000)
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
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

    BackHandler {
        when {
            level == 3 -> level = 2
            level == 2 -> level = 1
            else -> onBack()
        }
    }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(
                helpTopic = "judge_eval_l1", title = "Judge the judges",
                subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack
            )
            Spacer(Modifier.height(20.dp))
            Text("No judge-eval on this report.", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    when (level) {
        2 -> if (byMatch) {
            JudgeEvalMatchScreen(run, agents, reportTitle, reportIcon, matchKey,
                openJudge = { jk -> judgeKey = jk; level = 3 },
                onBack = { level = 1 })
        } else {
            JudgeEvalL2(run, agents, reportTitle, reportIcon, judgeKey,
                openMatch = { mk -> matchKey = mk; level = 3 },
                onBack = { level = 1 })
        }
        3 -> JudgeEvalL3(run, agents, reportTitle, reportIcon, judgeKey, matchKey,
            onBack = { level = 2 },
            onRerun = { scope.launch { engine.rerunCell(context, reportId, "$judgeKey|$matchKey") } })
        else -> JudgeEvalL1(run, agents, throttled, reportTitle, reportIcon,
            openJudge = { jk -> judgeKey = jk; byMatch = false; level = 2 },
            openMatch = { mk -> matchKey = mk; byMatch = true; level = 2 },
            onRestartFailed = { scope.launch { engine.restartFailedCells(context, reportId) } },
            onDeleteRun = { scope.launch { engine.deleteRun(context, reportId) }; onBack() },
            onBack = onBack)
    }
}

// ---------- L1 ----------

@Composable
private fun JudgeEvalL1(
    run: JudgeEvalRunState,
    agents: Map<String, ReportAgent>,
    throttled: Set<String>,
    reportTitle: String,
    reportIcon: String,
    openJudge: (String) -> Unit,
    openMatch: (String) -> Unit,
    onRestartFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onBack: () -> Unit
) {
    val throttledCount = run.cells.values.count { it.id in throttled }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_l1", title = "Judge the judges",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack, onDelete = onDeleteRun
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            val stats = listOf(
                Triple("Total", run.totalCells.toString(), AppColors.Blue),
                Triple("Done", run.doneCount.toString(), AppColors.Green),
                Triple("Run", run.runningCount.toString(), AppColors.Orange),
                Triple("Wait", throttledCount.toString(), AppColors.Yellow),
                Triple("Queue", run.queuedCount.toString(), AppColors.Brown),
                Triple("Err", run.errorCount.toString(), AppColors.Red),
                Triple("Cost", "${formatCents(run.totalCost, 2)} ¢", AppColors.Blue)
            )
            Row(Modifier.fillMaxWidth()) {
                stats.forEach { (label, _, color) ->
                    Text(label, fontSize = 11.sp, color = color, textAlign = TextAlign.Center,
                        maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                stats.forEach { (_, value, color) ->
                    Text(value, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${run.judgeCount} judges · ${run.matchCount} matches",
                color = AppColors.TextSecondary, fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (run.allTerminal) {
                val stats2 = analyzeJudges(run.cells.values.toList())
                Text(
                    "Consensus strength  ${pct(stats2.consensusStrength())}",
                    color = AppColors.Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Judges ranked by how often they agree with the others.",
                    color = AppColors.TextTertiary, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                stats2.forEachIndexed { i, s ->
                    JudgeLeaderboardRow(rank = i + 1, s = s) { openJudge(s.judgeKey) }
                }
            } else {
                // Per-judge progress while running — the green bar fills to the
                // judge's own percentage done (DONE + ERROR) / total, like Fan Out.
                val byJudge = run.cells.values.groupBy { it.judgeKey }
                    .toList().sortedBy { it.first }
                byJudge.forEach { (jk, cells) ->
                    val done = cells.count { it.status == JudgeCellStatus.DONE || it.status == JudgeCellStatus.ERROR }
                    JudgeProgressRow(
                        label = shortModelName(jk.substringAfterLast('/')),
                        done = done, total = cells.size,
                        barFrac = if (cells.isNotEmpty()) done.toFloat() / cells.size else 0f
                    ) { openJudge(jk) }
                }
            }

            // Second table: the matches. Each row → the per-match list of
            // judges (JudgeEvalMatchScreen → L3).
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.45f), thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            Text("Matches", color = AppColors.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            buildMatchSummaries(run, agents).forEach { m ->
                MatchSummaryRow(m) { openMatch(m.matchKey) }
            }

            Spacer(Modifier.height(16.dp))
            if (run.errorCount > 0) {
                Button(
                    onClick = onRestartFailed, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                ) { Text("Restart ${run.errorCount} failed", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun JudgeProgressRow(label: String, done: Int, total: Int, barFrac: Float, onClick: () -> Unit) {
    val barColor = AppColors.Green.copy(alpha = 0.30f)
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
        Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
    }
}

@Composable
private fun JudgeLeaderboardRow(rank: Int, s: JudgeStats, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("#$rank", color = AppColors.TextTertiary, fontSize = 13.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(shortModelName(s.judgeModel), color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append("ties ${pct(s.tieRate)} · A-lean ${pct(s.aLean)}")
                    s.avgConfidence?.let { append(" · conf ${"%.2f".format(it)}") }
                    if (s.errors > 0) append(" · ${s.errors} err")
                },
                color = AppColors.TextTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text(pct(s.agreement), color = agreementColor(s.agreement), fontSize = 18.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    }
    Spacer(Modifier.height(6.dp))
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
    onBack: () -> Unit
) {
    val cells = run.cells.values.filter { it.judgeKey == judgeKey }
        .sortedWith(compareBy({ it.responseAId }, { it.responseBId }))
    val consensus = run.cells.values.groupBy { it.matchKey }
        .mapValues { (_, cs) -> consensusForMatch(cs.mapNotNull { it.verdict }) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_l2", title = "Judge",
            subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack
        )
        Text(
            shortModelName(judgeKey.substringAfterLast('/')),
            color = AppColors.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("Match", color = AppColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Verdict", color = AppColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                    Text("Cons.", color = AppColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
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
                        "${shortModelName(agents[c.responseAId]?.model ?: "?")} vs ${shortModelName(agents[c.responseBId]?.model ?: "?")}",
                        color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(verdictGlyph(c.verdict, c.status), color = Color.White, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                    Text(
                        if (c.verdict == null) "—" else if (agree) "✓" else "✗",
                        color = if (c.verdict == null) AppColors.TextTertiary else if (agree) AppColors.Green else AppColors.Red,
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_l3", title = "Match",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack, onReload = onRerun
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
                "Judge: ${shortModelName(judgeKey.substringAfterLast('/'))}",
                color = AppColors.Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
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
                    Text(verdictLabel(cell.verdict), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    cell.confidence?.let { Text("conf ${"%.2f".format(it)}", color = AppColors.TextTertiary, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(2.dp))
                Text("Consensus: ${verdictLabel(consensus)}", color = AppColors.TextTertiary, fontSize = 12.sp)
                cell.reason?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color.White, fontSize = 13.sp)
                }
                cell.errorMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = AppColors.Red, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            ResponsePane("A — ${shortModelName(aAgent?.model ?: "?")}", aAgent?.responseBody.orEmpty(),
                highlight = cell.verdict == "A")
            Spacer(Modifier.height(10.dp))
            ResponsePane("B — ${shortModelName(bAgent?.model ?: "?")}", bAgent?.responseBody.orEmpty(),
                highlight = cell.verdict == "B")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResponsePane(header: String, body: String, highlight: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (highlight) AppColors.Green.copy(alpha = 0.12f) else AppColors.CardBackground)
            .padding(12.dp)
    ) {
        Text(header, color = if (highlight) AppColors.Green else AppColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body.ifBlank { "(empty)" }, color = Color.White, fontSize = 13.sp)
    }
}

// ---------- helpers ----------

private fun pct(v: Double): String = "${(v * 100).toInt()}%"

private fun agreementColor(v: Double): Color = when {
    v >= 0.7 -> AppColors.Green
    v >= 0.5 -> AppColors.Yellow
    else -> AppColors.Orange
}

private fun verdictGlyph(verdict: String?, status: JudgeCellStatus): String = when {
    verdict == "A" -> "A"
    verdict == "B" -> "B"
    verdict == "tie" -> "tie"
    status == JudgeCellStatus.ERROR -> "❌"
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
    val votedCount: Int
)

private fun buildMatchSummaries(run: JudgeEvalRunState, agents: Map<String, ReportAgent>): List<MatchSummary> =
    run.cells.values.groupBy { it.matchKey }
        .map { (mk, cs) ->
            val first = cs.first()
            val verdicts = cs.mapNotNull { it.verdict }
            val cons = consensusForMatch(verdicts)
            MatchSummary(
                matchKey = mk,
                aLabel = shortModelName(agents[first.responseAId]?.model ?: "?"),
                bLabel = shortModelName(agents[first.responseBId]?.model ?: "?"),
                consensus = cons,
                agreeCount = cs.count { it.verdict != null && cons != null && it.verdict == cons },
                votedCount = verdicts.size
            )
        }
        .sortedWith(compareBy({ it.aLabel }, { it.bLabel }))

@Composable
private fun MatchSummaryRow(m: MatchSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${m.aLabel} vs ${m.bLabel}", color = Color.White, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(verdictLabel(m.consensus), color = AppColors.TextSecondary, fontSize = 12.sp,
            modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
        Text("${m.agreeCount}/${m.votedCount}", color = AppColors.TextTertiary, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.25f), thickness = 0.5.dp)
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
        "${shortModelName(agents[first.responseAId]?.model ?: "?")} vs ${shortModelName(agents[first.responseBId]?.model ?: "?")}"
    else "Match"
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "judge_eval_match", title = "Match",
            subject = reportTitle, reportIcon = reportIcon, onBackClick = onBack
        )
        Text(pairLabel, color = AppColors.Green, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Text("Consensus: ${verdictLabel(cons)}", color = AppColors.TextTertiary, fontSize = 12.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("Judge", color = AppColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Verdict", color = AppColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                    Text("Cons.", color = AppColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
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
                    Text(shortModelName(c.judgeModel), color = Color.White, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(verdictGlyph(c.verdict, c.status), color = Color.White, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
                    Text(
                        if (c.verdict == null) "—" else if (agree) "✓" else "✗",
                        color = if (c.verdict == null) AppColors.TextTertiary else if (agree) AppColors.Green else AppColors.Red,
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
        "🐞", fontSize = 13.sp,
        color = if (traceFile.isNullOrBlank()) AppColors.TextDisabled else Color.White,
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
