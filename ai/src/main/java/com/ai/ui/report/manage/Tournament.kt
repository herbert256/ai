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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.MatchState
import com.ai.data.MatchStatus
import com.ai.data.ReportAgent
import com.ai.data.ReportStorage
import com.ai.data.TournamentMethod
import com.ai.data.TournamentRunState
import com.ai.ui.report.view.TournamentViewScreen
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.viewmodel.TournamentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val rowText = buildString {
        append("${run.doneCount} / ${run.totalMatches}")
        if (run.errorCount > 0) append(" · ${run.errorCount} failed")
        else if (run.allTerminal) append(" · done")
    }
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
                else -> Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
            }
            RowTypeCell("tournament")
            Text(
                text = rowText, color = Color.White, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
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

    // Hydrate on entry + re-poll disk while the batch is still running.
    androidx.compose.runtime.LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }
    androidx.compose.runtime.LaunchedEffect(reportId, run?.allTerminal) {
        while (true) {
            val r = engine.runByKey(reportId)
            if (r != null && r.allTerminal) break
            delay(3000)
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
    }

    val agents by produceState(initialValue = emptyMap<String, ReportAgent>(), reportId) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, reportId)?.agents?.associateBy { it.agentId } ?: emptyMap()
        }
    }

    var groupMode by rememberSaveable { mutableStateOf(TournamentGroupMode.TOURNAMENT_MODELS) }
    var level by rememberSaveable { mutableStateOf(1) }       // 1 = L1, 2 = L2, 3 = L3
    var groupKey by rememberSaveable { mutableStateOf("") }
    var matchKey by rememberSaveable { mutableStateOf("") }
    var showResults by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        when {
            showResults -> showResults = false
            level == 3 -> level = 2
            level == 2 -> level = 1
            else -> onBack()
        }
    }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(helpTopic = "tournament_l1", title = "Tournament", subject = null, onBackClick = onBack)
            Spacer(Modifier.height(20.dp))
            Text("No tournament on this report.", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    if (showResults && run.aggregateRowId != null) {
        TournamentViewScreen(reportId = reportId, resultId = run.aggregateRowId!!, onBack = { showResults = false })
        return
    }

    when (level) {
        2 -> TournamentL2(run, agents, groupKey, groupMode,
            openMatch = { mk -> matchKey = mk; level = 3 },
            onBack = { level = 1 })
        3 -> TournamentL3(run, agents, matchKey, groupKey, groupMode,
            onBack = { level = 2 },
            onRerun = { scope.launch { engine.rerunMatch(context, reportId, matchKey) } },
            onStep = { mk -> matchKey = mk })
        else -> TournamentL1(run, agents, groupMode, throttled,
            setGroupMode = { groupMode = it },
            openGroup = { gk -> groupKey = gk; level = 2 },
            onViewResults = { showResults = true },
            onRestartFailed = { scope.launch { engine.restartFailedMatches(context, reportId) } },
            onDeleteRun = { scope.launch { engine.deleteRun(context, reportId) }; onBack() },
            onBack = onBack)
    }
}

// ---------- grouping helpers ----------

private fun agentLabel(agents: Map<String, ReportAgent>, agentId: String?): String =
    agents[agentId]?.let { shortModelName(it.model) } ?: "?"

/** L1 group rows for the active mode. groupKey encodes the mode. */
private data class GroupRow(val key: String, val label: String, val matches: List<MatchState>) {
    val done get() = matches.count { it.status == MatchStatus.DONE }
    val total get() = matches.size
    val cost get() = matches.sumOf { it.totalCost }
}

private fun buildGroups(run: TournamentRunState, agents: Map<String, ReportAgent>, mode: TournamentGroupMode): List<GroupRow> =
    when (mode) {
        TournamentGroupMode.TOURNAMENT_MODELS ->
            run.matches.values.filter { it.judgeModel != null }
                .groupBy { it.judgeModel!! }
                .map { (judge, ms) -> GroupRow("judge:$judge", shortModelName(judge.substringAfterLast('/')), ms) }
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
    groupMode: TournamentGroupMode,
    throttled: Set<String>,
    setGroupMode: (TournamentGroupMode) -> Unit,
    openGroup: (String) -> Unit,
    onViewResults: () -> Unit,
    onRestartFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onBack: () -> Unit
) {
    val throttledCount = run.matches.values.count { it.id in throttled }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "tournament_l1", title = "Tournament",
            subject = "${run.doneCount}/${run.totalMatches}",
            onBackClick = onBack,
            onDelete = onDeleteRun
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            // Stats row.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell("Total", run.totalMatches.toString())
                StatCell("Done", run.doneCount.toString())
                StatCell("Run", run.runningCount.toString())
                StatCell("Wait", throttledCount.toString())
                StatCell("Queue", run.queuedCount.toString())
                StatCell("Err", run.errorCount.toString(), if (run.errorCount > 0) AppColors.Red else AppColors.TextSecondary)
                StatCell("Cost", "${formatCents(run.totalCost, 2)} ¢")
            }
            Spacer(Modifier.height(12.dp))

            // Grouping mode toggle.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = groupMode == TournamentGroupMode.TOURNAMENT_MODELS,
                    onClick = { setGroupMode(TournamentGroupMode.TOURNAMENT_MODELS) },
                    label = { Text("Judge models", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = groupMode == TournamentGroupMode.REPORT_MODELS,
                    onClick = { setGroupMode(TournamentGroupMode.REPORT_MODELS) },
                    label = { Text("Report models", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))

            val groups = buildGroups(run, agents, groupMode)
            if (groups.isEmpty()) {
                Text(
                    if (groupMode == TournamentGroupMode.TOURNAMENT_MODELS) "No matches judged yet." else "No matches yet.",
                    color = AppColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            groups.forEach { g ->
                // Judge models: lead with the number of calls this judge made
                // (= matches it judged). Report models: no count cell at all.
                val leading = if (groupMode == TournamentGroupMode.TOURNAMENT_MODELS) g.total.toString() else null
                GroupRowItem(g, leading) { openGroup(g.key) }
            }

            Spacer(Modifier.height(16.dp))
            // Action buttons.
            if (run.allTerminal && run.errorCount == 0 && run.aggregateRowId != null) {
                Button(
                    onClick = onViewResults, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                ) { Text("View results", fontSize = 14.sp) }
            }
            if (run.errorCount > 0) {
                Spacer(Modifier.height(8.dp))
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
private fun StatCell(label: String, value: String, valueColor: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = AppColors.TextTertiary, fontSize = 10.sp)
    }
}

@Composable
private fun GroupRowItem(g: GroupRow, leadingText: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.CardBackground)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .padding(bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingText != null) {
            Text(leadingText, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(48.dp))
        }
        Text(g.label, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (g.cost > 0) Text("${formatCents(g.cost)} ¢", color = AppColors.TextTertiary, fontSize = 11.sp)
    }
    Spacer(Modifier.height(6.dp))
}

// ---------- L2 ----------

@Composable
private fun TournamentL2(
    run: TournamentRunState,
    agents: Map<String, ReportAgent>,
    groupKey: String,
    groupMode: TournamentGroupMode,
    openMatch: (String) -> Unit,
    onBack: () -> Unit
) {
    val matches = matchesForGroup(run, groupKey)
    val title = groupKey.substringAfter(":").let { if (groupKey.startsWith("judge:")) shortModelName(it.substringAfterLast('/')) else agentLabel(agents, it) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "tournament_l2", title = "Tournament", subject = title, onBackClick = onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            items(matches, key = { it.key }) { m -> MatchRowItem(m, agents) { openMatch(m.key) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MatchRowItem(m: MatchState, agents: Map<String, ReportAgent>, onClick: () -> Unit) {
    val labelA = agentLabel(agents, m.responseAId)
    val labelB = agentLabel(agents, m.responseBId)
    val glyph = when (m.status) {
        MatchStatus.DONE -> "✅"
        MatchStatus.ERROR -> "❌"
        MatchStatus.RUNNING -> "⏳"
        MatchStatus.PENDING -> "🕓"
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(glyph, fontSize = 14.sp, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text("$labelA  vs  $labelB", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when {
                m.errorMessage != null -> "⚠ ${m.errorMessage.take(48)}"
                m.verdict == "A" -> "winner: $labelA"
                m.verdict == "B" -> "winner: $labelB"
                m.verdict == "tie" -> "tie"
                else -> "…"
            }
            Text(sub, color = AppColors.TextTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        m.judgeModel?.let {
            Text(shortModelName(it.substringAfterLast('/')), color = AppColors.TextTertiary, fontSize = 10.sp, maxLines = 1)
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
}

// ---------- L3 ----------

@Composable
private fun TournamentL3(
    run: TournamentRunState,
    agents: Map<String, ReportAgent>,
    matchKey: String,
    groupKey: String,
    groupMode: TournamentGroupMode,
    onBack: () -> Unit,
    onRerun: () -> Unit,
    onStep: (String) -> Unit
) {
    val scoped = matchesForGroup(run, groupKey)
    val idx = scoped.indexOfFirst { it.key == matchKey }
    val m = scoped.getOrNull(idx) ?: run.matches[matchKey]
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "tournament_l3", title = "Tournament", subject = "match", onBackClick = onBack, onReload = onRerun)
        if (m == null) {
            Text("Match not found.", color = AppColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val labelA = agentLabel(agents, m.responseAId)
        val labelB = agentLabel(agents, m.responseBId)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(6.dp))
            // Verdict block.
            val winnerLabel = when (m.verdict) { "A" -> labelA; "B" -> labelB; "tie" -> "tie"; else -> "—" }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground).padding(12.dp)
            ) {
                Text("Winner: $winnerLabel", color = AppColors.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                m.confidence?.let { Text("Confidence: ${"%.0f".format(it * 100)}%", color = AppColors.TextSecondary, fontSize = 12.sp) }
                if (!m.reason.isNullOrBlank()) Text(m.reason!!, color = AppColors.TextSecondary, fontSize = 12.sp)
                Text("Orientation: ${if (m.orientation == 0) "A-vs-B" else "B-vs-A (swapped)"}", color = AppColors.TextTertiary, fontSize = 11.sp)
                m.judgeModel?.let { Text("Judged by: ${it}", color = AppColors.TextTertiary, fontSize = 11.sp) }
                m.errorMessage?.let { Text("⚠ $it", color = AppColors.Red, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(12.dp))
            ResponsePane("A · $labelA", agents[m.responseAId]?.responseBody)
            Spacer(Modifier.height(12.dp))
            ResponsePane("B · $labelB", agents[m.responseBId]?.responseBody)
            Spacer(Modifier.height(16.dp))
            // Prev / next within the group.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scoped.getOrNull(idx - 1)?.let { onStep(it.key) } },
                    enabled = idx > 0, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.CardBackground)
                ) { Text("← Prev", fontSize = 13.sp) }
                Button(
                    onClick = { scoped.getOrNull(idx + 1)?.let { onStep(it.key) } },
                    enabled = idx in 0 until scoped.size - 1, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.CardBackground)
                ) { Text("Next →", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResponsePane(header: String, body: String?) {
    Column(Modifier.fillMaxWidth()) {
        Text(header, color = AppColors.Blue, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(body?.trim().orEmpty(), color = AppColors.TextSecondary, fontSize = 12.sp)
    }
}
