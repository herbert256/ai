package com.ai.ui.report.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ReportStatus
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.TournamentMethod
import com.ai.data.applyTournamentMethod
import com.ai.data.barTitle
import com.ai.data.decodeTournamentMatrix
import com.ai.data.parseMatchVerdict
import com.ai.ui.helpers.SwipeDirection
import com.ai.ui.helpers.formatRerankScore
import com.ai.ui.helpers.parseRerankRows
import com.ai.ui.report.view.helpers.ViewReportCache
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.viewBodySwipe
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalOpenManage
import com.ai.ui.shared.LocalTournamentOpenState
import com.ai.ui.shared.ManageJump
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * View-hub-only tournament page. Manage keeps its existing tournament
 * result screen; this one is the read-oriented View variant with a
 * medal leaderboard and model drill-ins.
 */
@Composable
fun TournamentPodiumViewScreen(
    reportId: String,
    resultId: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    var currentResultId by rememberSaveable(resultId) { mutableStateOf(resultId) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current

    val secondaryDataVersion by SecondaryDataVersion.version.collectAsState()
    val loadedState = produceState(
        initialValue = TournamentPodiumLoaded(),
        currentReportId, currentResultId, secondaryDataVersion
    ) {
        value = withContext(Dispatchers.IO) {
            loadTournamentPodium(context, currentReportId, currentResultId)
        }
    }
    val loaded = loadedState.value
    val currentMethod = decodeTournamentMatrix(loaded.row?.tournamentMatrix)?.second ?: TournamentMethod.COPELAND
    val openManage = LocalOpenManage.current
    val tournamentOpenState = LocalTournamentOpenState.current
    val onOpenTournamentManage: (() -> Unit)? = openManage?.let { dispatch ->
        {
            tournamentOpenState?.value = currentReportId
            dispatch(ManageJump.Main)
        }
    }
    var headToHeadAgentId by rememberSaveable(currentReportId, currentResultId) { mutableStateOf<String?>(null) }
    val selectedHeadToHeadAgentId = headToHeadAgentId
    if (selectedHeadToHeadAgentId != null) {
        val selectedAgent = loaded.rankings
            .firstOrNull { it.agent?.agentId == selectedHeadToHeadAgentId }
            ?.agent
        TournamentModelHeadToHeadViewScreen(
            reportTitle = loaded.reportTitle,
            modelLabel = selectedAgent?.label ?: "Model",
            matches = loaded.matches.filter {
                it.firstAgentId == selectedHeadToHeadAgentId || it.secondAgentId == selectedHeadToHeadAgentId
            },
            agentId = selectedHeadToHeadAgentId,
            onOpenManage = onOpenTournamentManage,
            onBack = { headToHeadAgentId = null }
        )
        return
    }

    val onSwipePrevAction: () -> Boolean = {
        val m = findTournamentAggregateSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev)
        if (m != null) {
            currentReportId = m.reportId
            currentResultId = m.resultId
            switchReport?.invoke(m.reportId)
            true
        } else false
    }
    val onSwipeNextAction: () -> Boolean = {
        val m = findTournamentAggregateSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next)
        if (m != null) {
            currentReportId = m.reportId
            currentResultId = m.resultId
            switchReport?.invoke(m.reportId)
            true
        } else false
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .viewBodySwipe(currentReportId, onPrev = { onSwipePrevAction() }, onNext = { onSwipeNextAction() })
    ) {
        ViewTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Tournament",
            subject = "${methodLabel(currentMethod)} ranking",
            helpTopic = "view_tournament",
            onOpenManage = onOpenTournamentManage,
            onBack = onBack,
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction
        )

        if (loaded.row == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("Loading...", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            item {
                MethodSelector(
                    selected = currentMethod,
                    onSelect = { method ->
                        scope.launch(Dispatchers.IO) {
                            applyTournamentMethod(context, currentReportId, currentResultId, method)
                        }
                    }
                )
            }
            item {
                TournamentStatsStrip(
                    models = loaded.rankings.size,
                    done = loaded.doneMatches,
                    total = loaded.totalMatches,
                    ties = loaded.tieCount
                )
            }
            if (loaded.rankings.isEmpty()) {
                item { EmptyTournamentCard(loaded.doneMatches, loaded.totalMatches) }
            } else {
                item {
                    Text(
                        "Leaderboard",
                        color = AppColors.Blue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                items(loaded.rankings) { ranking ->
                    TournamentRankCard(
                        ranking = ranking,
                        method = currentMethod,
                        onClick = ranking.agent?.agentId?.let { agentId ->
                            { headToHeadAgentId = agentId }
                        }
                    )
                }
            }
        }
    }
}

private data class TournamentPodiumLoaded(
    val row: SecondaryResult? = null,
    val reportTitle: String? = null,
    val rankings: List<TournamentRanking> = emptyList(),
    val doneMatches: Int = 0,
    val totalMatches: Int = 0,
    val tieCount: Int = 0,
    val matches: List<TournamentViewMatch> = emptyList()
)

private data class TournamentAgent(
    val rankId: Int,
    val agentId: String,
    val label: String
)

private data class TournamentRecord(
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0
)

private data class TournamentRanking(
    val rank: Int?,
    val score: Double?,
    val agent: TournamentAgent?,
    val record: TournamentRecord
)

private data class TournamentViewSide(
    val winnerAgentId: String? = null,
    val tie: Boolean = false,
    val reason: String? = null,
    val error: String? = null,
    val judge: String? = null
)

private data class TournamentViewMatch(
    val firstAgentId: String,
    val firstLabel: String,
    val firstResponse: String?,
    val secondAgentId: String,
    val secondLabel: String,
    val secondResponse: String?,
    val firstPass: TournamentViewSide,
    val swappedPass: TournamentViewSide
)

private data class TournamentSwipeMatch(val reportId: String, val resultId: String)

private fun loadTournamentPodium(
    context: android.content.Context,
    reportId: String,
    resultId: String
): TournamentPodiumLoaded {
    val row = SecondaryResultStorage.get(context, reportId, resultId)
    val report = ViewReportCache.get(context, reportId)
    val successful = report?.agents
        ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        ?: emptyList()
    val agentsByRankId = successful.mapIndexed { index, agent ->
        (index + 1) to TournamentAgent(
            rankId = index + 1,
            agentId = agent.agentId,
            label = shortModelName(agent.model)
        )
    }.toMap()
    val agentIdToLabel = successful.associate { it.agentId to shortModelName(it.model) }
    val agentIdToResponse = successful.associate { it.agentId to it.responseBody }
    val matchRows = row?.tournamentJudgeRunId?.let { runId ->
        SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
            .filter { it.tournamentRole == "MATCH" && it.tournamentJudgeRunId == runId }
    }.orEmpty()
    val records = buildTournamentRecords(matchRows)
    val rankings = row?.content?.let { parseRerankRows(it) }.orEmpty().map { rankRow ->
        val agent = agentsByRankId[rankRow.id]
        TournamentRanking(
            rank = rankRow.rank,
            score = rankRow.score,
            agent = agent,
            record = agent?.let { records[it.agentId] } ?: TournamentRecord()
        )
    }
    return TournamentPodiumLoaded(
        row = row,
        reportTitle = report?.barTitle,
        rankings = rankings,
        doneMatches = matchRows.count { !it.content.isNullOrBlank() || it.durationMs != null },
        totalMatches = matchRows.size,
        tieCount = records.values.sumOf { it.draws } / 2,
        matches = buildTournamentViewMatches(matchRows, agentIdToLabel, agentIdToResponse)
    )
}

private fun buildTournamentViewMatches(
    rows: List<SecondaryResult>,
    agentIdToLabel: Map<String, String>,
    agentIdToResponse: Map<String, String?>
): List<TournamentViewMatch> {
    val byPair = rows
        .filter { !it.matchResponseAId.isNullOrBlank() && !it.matchResponseBId.isNullOrBlank() }
        .groupBy {
            listOf(it.matchResponseAId.orEmpty(), it.matchResponseBId.orEmpty())
                .sorted()
                .joinToString("\u0000")
        }
    return byPair.values.mapNotNull { pairRows ->
        val firstPassRow = pairRows.firstOrNull { (it.matchOrientation ?: 0) == 0 }
        val swappedPassRow = pairRows.firstOrNull { (it.matchOrientation ?: 0) == 1 }
        val canonical = firstPassRow ?: swappedPassRow ?: pairRows.firstOrNull() ?: return@mapNotNull null
        val firstId = canonical.matchResponseAId ?: return@mapNotNull null
        val secondId = canonical.matchResponseBId ?: return@mapNotNull null
        TournamentViewMatch(
            firstAgentId = firstId,
            firstLabel = agentIdToLabel[firstId] ?: "?",
            firstResponse = agentIdToResponse[firstId],
            secondAgentId = secondId,
            secondLabel = agentIdToLabel[secondId] ?: "?",
            secondResponse = agentIdToResponse[secondId],
            firstPass = tournamentViewSideFrom(firstPassRow),
            swappedPass = tournamentViewSideFrom(swappedPassRow)
        )
    }.sortedWith(compareBy<TournamentViewMatch> { it.firstLabel }.thenBy { it.secondLabel })
}

private fun tournamentViewSideFrom(row: SecondaryResult?): TournamentViewSide {
    val parsed = row?.let { parseMatchVerdict(it.content) }
    val winnerAgentId = when (parsed?.verdict) {
        "A" -> row?.matchResponseAId
        "B" -> row?.matchResponseBId
        else -> null
    }
    val judge = row?.model
        ?.takeIf { it.isNotBlank() && !it.startsWith("*") }
        ?.let { shortModelName(it) }
    return TournamentViewSide(
        winnerAgentId = winnerAgentId,
        tie = parsed?.verdict == "tie",
        reason = parsed?.reason,
        error = row?.errorMessage,
        judge = judge
    )
}

private fun buildTournamentRecords(rows: List<SecondaryResult>): Map<String, TournamentRecord> {
    val records = LinkedHashMap<String, TournamentRecord>()
    fun bump(id: String?, win: Int = 0, draw: Int = 0, loss: Int = 0) {
        if (id.isNullOrBlank()) return
        val old = records[id] ?: TournamentRecord()
        records[id] = TournamentRecord(old.wins + win, old.draws + draw, old.losses + loss)
    }
    rows.forEach { row ->
        if (!row.errorMessage.isNullOrBlank()) return@forEach
        val parsed = parseMatchVerdict(row.content) ?: return@forEach
        when (parsed.verdict) {
            "A" -> {
                bump(row.matchResponseAId, win = 1)
                bump(row.matchResponseBId, loss = 1)
            }
            "B" -> {
                bump(row.matchResponseBId, win = 1)
                bump(row.matchResponseAId, loss = 1)
            }
            "tie" -> {
                bump(row.matchResponseAId, draw = 1)
                bump(row.matchResponseBId, draw = 1)
            }
        }
    }
    return records
}

private fun findTournamentAggregateSwipeMatch(
    context: android.content.Context,
    reportIdsNewestFirst: List<String>,
    currentReportId: String,
    direction: SwipeDirection
): TournamentSwipeMatch? {
    val n = reportIdsNewestFirst.size
    if (n == 0) return null
    val currentIdx = reportIdsNewestFirst.indexOf(currentReportId)
    if (currentIdx < 0) return null
    val step = if (direction == SwipeDirection.Prev) +1 else -1
    for (k in 1 until n) {
        val i = (((currentIdx + step * k) % n) + n) % n
        val rid = reportIdsNewestFirst[i]
        val row = SecondaryResultStorage.listForReport(context, rid, SecondaryKind.TOURNAMENT)
            .filter { it.tournamentRole == "AGGREGATE" }
            .maxByOrNull { it.timestamp }
        if (row != null) return TournamentSwipeMatch(rid, row.id)
    }
    return null
}

@Composable
private fun MethodSelector(selected: TournamentMethod, onSelect: (TournamentMethod) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            TournamentMethod.COPELAND,
            TournamentMethod.ELO,
            TournamentMethod.DAVIDSON,
            TournamentMethod.TIDEMAN,
            TournamentMethod.MARKOV
        ).forEach { method ->
            TournamentMethodChip(methodLabel(method), selected == method) {
                onSelect(method)
            }
        }
    }
}

@Composable
private fun TournamentMethodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        color = if (selected) Color.Black else Color.White,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) AppColors.Green else AppColors.CardBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun TournamentStatsStrip(models: Int, done: Int, total: Int, ties: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatTile("Models", models.toString(), AppColors.Blue, Modifier.weight(1f))
        StatTile("Matches", "$done/$total", AppColors.Green, Modifier.weight(1f))
        StatTile("Ties", ties.toString(), AppColors.Orange, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.17f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = AppColors.TextTertiary, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun TournamentRankCard(
    ranking: TournamentRanking,
    method: TournamentMethod,
    onClick: (() -> Unit)?
) {
    val medal = medalForRank(ranking.rank)
    val accent = medalColor(ranking.rank)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.58f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                medal ?: "#${ranking.rank ?: "-"}",
                color = accent,
                fontSize = if (medal != null) 22.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                ranking.agent?.label ?: "(unknown model)",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${ranking.record.wins}W ${ranking.record.draws}D ${ranking.record.losses}L",
                color = AppColors.TextTertiary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Text(
            scoreText(ranking.score, method),
            color = AppColors.Green,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun TournamentModelHeadToHeadViewScreen(
    reportTitle: String?,
    modelLabel: String,
    matches: List<TournamentViewMatch>,
    agentId: String,
    onOpenManage: (() -> Unit)?,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val sides = matches.flatMap { listOf(it.firstPass, it.swappedPass) }
    val won = sides.count { tournamentResultFor(agentId, it) == "won" }
    val drew = sides.count { tournamentResultFor(agentId, it) == "draw" }
    val lost = sides.count { tournamentResultFor(agentId, it) == "lost" }
    var currentMatchIndex by rememberSaveable(agentId) { mutableStateOf(0) }
    LaunchedEffect(matches.size, agentId) {
        currentMatchIndex = when {
            matches.isEmpty() -> 0
            currentMatchIndex in matches.indices -> currentMatchIndex
            else -> currentMatchIndex.coerceIn(0, matches.lastIndex)
        }
    }
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var swipeDragX by remember(matches.size, agentId) { mutableStateOf(0f) }
    val safeMatchIndex = if (matches.isEmpty()) 0 else currentMatchIndex.coerceIn(0, matches.lastIndex)
    val swipeModifier = if (matches.size > 1) {
        Modifier.pointerInput(matches.size, safeMatchIndex) {
            detectHorizontalDragGestures(
                onDragStart = { swipeDragX = 0f },
                onHorizontalDrag = { _, dragAmount -> swipeDragX += dragAmount },
                onDragCancel = { swipeDragX = 0f },
                onDragEnd = {
                    when {
                        swipeDragX > swipeThresholdPx -> currentMatchIndex = steppedMatchIndex(safeMatchIndex, matches.size, -1)
                        swipeDragX < -swipeThresholdPx -> currentMatchIndex = steppedMatchIndex(safeMatchIndex, matches.size, 1)
                    }
                    swipeDragX = 0f
                }
            )
        }
    } else {
        Modifier
    }
    val activeMatch = matches.getOrNull(safeMatchIndex)
    val activeModelRole = activeMatch?.let { tournamentRoleFor(it, agentId) }
    val subjectLabel = activeModelRole?.let { "$it - $modelLabel" } ?: modelLabel
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewTitleBar(
            reportTitle = reportTitle,
            screenTitle = "Tournament",
            subject = subjectLabel,
            helpTopic = "view_tournament",
            onOpenManage = onOpenManage,
            onBack = onBack
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(swipeModifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item {
                TournamentHeadToHeadSummary(won = won, drew = drew, lost = lost)
            }
            if (matches.isEmpty()) {
                item {
                    Text(
                        "No head-to-heads for this model.",
                        color = AppColors.TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                val match = activeMatch ?: matches[safeMatchIndex]
                item {
                    TournamentHeadToHeadCounter(current = safeMatchIndex + 1, total = matches.size)
                }
                item {
                    TournamentHeadToHeadCard(match = match, agentId = agentId)
                }
                item {
                    TournamentResponseCard(
                        title = "A - ${match.firstLabel}",
                        titleColor = tournamentResponseColor(match, match.firstAgentId),
                        body = match.firstResponse
                    )
                }
                item {
                    TournamentResponseCard(
                        title = "B - ${match.secondLabel}",
                        titleColor = tournamentResponseColor(match, match.secondAgentId),
                        body = match.secondResponse
                    )
                }
            }
        }
    }
}

@Composable
private fun TournamentHeadToHeadSummary(won: Int, drew: Int, lost: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatTile("Won", won.toString(), AppColors.Green, Modifier.weight(1f))
        StatTile("Drawn", drew.toString(), AppColors.Blue, Modifier.weight(1f))
        StatTile("Lost", lost.toString(), AppColors.Red, Modifier.weight(1f))
    }
}

@Composable
private fun TournamentHeadToHeadCounter(current: Int, total: Int) {
    Text(
        "$current / $total",
        color = AppColors.TextTertiary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
    )
}

@Composable
private fun TournamentHeadToHeadCard(match: TournamentViewMatch, agentId: String) {
    val opponent = if (match.firstAgentId == agentId) {
        "B - ${match.secondLabel}"
    } else {
        "A - ${match.firstLabel}"
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Blue.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "vs $opponent",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        TournamentOrientationLine(
            label = "A <> B",
            side = match.firstPass,
            agentId = agentId
        )
        TournamentOrientationLine(
            label = "B <> A",
            side = match.swappedPass,
            agentId = agentId
        )
    }
}

@Composable
private fun TournamentOrientationLine(label: String, side: TournamentViewSide, agentId: String) {
    val result = tournamentResultFor(agentId, side)
    val (resultLabel, resultColor) = tournamentResultStyle(result)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                label,
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(56.dp),
                maxLines = 1
            )
            Text(
                resultLabel,
                color = resultColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Judge: ${side.judge ?: "-"}",
            color = AppColors.TextTertiary,
            fontSize = 11.sp
        )
        val detail = side.error ?: side.reason
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                color = AppColors.TextTertiary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TournamentResponseCard(title: String, titleColor: Color, body: String?) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, titleColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            color = titleColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            body?.trim()?.takeIf { it.isNotEmpty() } ?: "(empty response)",
            color = AppColors.TextSecondary,
            fontSize = 12.sp
        )
    }
}

private fun tournamentResultFor(agentId: String, side: TournamentViewSide): String = when {
    !side.error.isNullOrBlank() -> "error"
    side.tie -> "draw"
    side.winnerAgentId.isNullOrBlank() -> "pending"
    side.winnerAgentId == agentId -> "won"
    else -> "lost"
}

private fun tournamentResultStyle(result: String): Pair<String, Color> = when (result) {
    "won" -> "won" to AppColors.Green
    "lost" -> "lost" to AppColors.Red
    "draw" -> "draw" to AppColors.Blue
    "error" -> "error" to AppColors.Red
    else -> "pending" to AppColors.TextTertiary
}

private fun tournamentResponseColor(match: TournamentViewMatch, agentId: String): Color {
    val sides = listOf(match.firstPass, match.swappedPass)
    val wins = sides.count { it.error.isNullOrBlank() && !it.tie && it.winnerAgentId == agentId }
    val losses = sides.count {
        it.error.isNullOrBlank() && !it.tie && !it.winnerAgentId.isNullOrBlank() && it.winnerAgentId != agentId
    }
    val draws = sides.count { it.error.isNullOrBlank() && it.tie }
    return when {
        wins > losses -> AppColors.Green
        losses > wins -> AppColors.Red
        wins > 0 || draws > 0 -> AppColors.Blue
        else -> AppColors.TextSecondary
    }
}

private fun tournamentRoleFor(match: TournamentViewMatch, agentId: String): String? = when (agentId) {
    match.firstAgentId -> "A"
    match.secondAgentId -> "B"
    else -> null
}

private fun steppedMatchIndex(current: Int, size: Int, delta: Int): Int {
    if (size <= 1) return current
    return (current + delta + size) % size
}

@Composable
private fun EmptyTournamentCard(done: Int, total: Int) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🥊", fontSize = 42.sp)
        Text("Tournament is still forming", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Judged $done/$total matches",
            color = AppColors.TextSecondary,
            fontSize = 13.sp
        )
    }
}

private fun methodLabel(method: TournamentMethod): String = when (method) {
    TournamentMethod.COPELAND -> "Copeland"
    TournamentMethod.ELO -> "Elo"
    TournamentMethod.DAVIDSON -> "Davidson"
    TournamentMethod.TIDEMAN -> "Tideman"
    TournamentMethod.MARKOV -> "Markov"
}

private fun scoreText(score: Double?, method: TournamentMethod): String {
    if (score == null) return "-"
    return when (method) {
        TournamentMethod.COPELAND,
        TournamentMethod.DAVIDSON,
        TournamentMethod.TIDEMAN,
        TournamentMethod.MARKOV -> String.format(java.util.Locale.US, "%.1f", score)
        else -> formatRerankScore(score)
    }
}

private fun medalForRank(rank: Int?): String? = when (rank) {
    1 -> "🥇"
    2 -> "🥈"
    3 -> "🥉"
    else -> null
}

private fun medalColor(rank: Int?): Color = when (rank) {
    1 -> Color(0xFFFFD54F)
    2 -> Color(0xFFB0BEC5)
    3 -> Color(0xFFCD7F32)
    else -> AppColors.Blue
}
