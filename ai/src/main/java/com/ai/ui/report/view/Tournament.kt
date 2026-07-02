package com.ai.ui.report.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ReportStorage
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.TournamentMethod
import com.ai.data.applyTournamentMethod
import com.ai.data.barTitle
import com.ai.data.decodeTournamentMatrix
import com.ai.data.parseMatchVerdict
import com.ai.ui.helpers.RerankTable
import com.ai.ui.helpers.parseRerankRows
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.shortModelName
import com.ai.ui.shared.shortModelName2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Content-only "View" screen for a tournament's AGGREGATE ranking row.
 * Renders the head-to-head ranking through the shared [RerankTable]
 * (the aggregate `content` is rerank-shaped JSON), with a 3-way method
 * toggle (Copeland / Elo / Davidson / Markov) that recomputes locally from
 * the stored win matrix — no API calls — and a per-match list so the
 * user can inspect every judged pair.
 */
/** One orientation of a head-to-head. [winner] is normalised to the
 *  canonical pair: "A" = labelA won, "B" = labelB won, "tie", or null
 *  (pending) — so the active model's result is orientation-independent to
 *  compute. [trace] is that judging call's trace filename when recorded. */
private data class H2HSide(val winner: String?, val reason: String?, val error: String?, val trace: String?, val judge: String?)

/** One head-to-head pair carrying BOTH orientations: [ab] = A-vs-B
 *  (orientation 0), [ba] = B-vs-A (orientation 1). [agentA]/[agentB] are
 *  the pair's report-agent ids — the ONLY valid match/filter keys; the
 *  labels are shortModelName2 display strings that collapse two snapshots
 *  of one model (or one model via two providers) into the same text. */
private data class MatchRow(
    val agentA: String?, val agentB: String?,
    val labelA: String, val labelB: String,
    val ab: H2HSide, val ba: H2HSide
)

@Composable
fun TournamentViewScreen(
    reportId: String,
    resultId: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    data class Loaded(
        val row: SecondaryResult?,
        val agentLabels: Map<Int, String>,
        /** Rank id → report-agent id, same numbering as [agentLabels] —
         *  the drill-in key (labels are display-only). */
        val rankAgentIds: Map<Int, String>,
        val matches: List<MatchRow>,
        val doneMatches: Int,
        val totalMatches: Int,
        val reportTitle: String?
    )

    val secondaryDataVersion by SecondaryDataVersion.versionFor(reportId, SecondaryKind.TOURNAMENT).collectAsState()
    val state = produceState(
        initialValue = Loaded(null, emptyMap(), emptyMap(), emptyList(), 0, 0, null),
        reportId, resultId, secondaryDataVersion
    ) {
        value = withContext(Dispatchers.IO) {
            val row = SecondaryResultStorage.get(context, reportId, resultId)
            val report = ReportStorage.getReport(context, reportId)
            val matchRows = row?.tournamentJudgeRunId?.let { rk ->
                SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
                    .filter { it.tournamentRole == "MATCH" && it.tournamentJudgeRunId == rk }
            }.orEmpty()
            // Number the ranking's [N] ids by each PARTICIPANT's stable position
            // in report.agents — exactly how TournamentEngine writes them (and
            // how TournamentPodium reads them). Numbering through the CURRENT
            // success set shifted the ids whenever the success set drifted from
            // the participant set (e.g. an errored model regenerated to SUCCESS
            // after the tournament ran), mapping ranks to the wrong models.
            val participantIds = matchRows
                .flatMap { listOf(it.matchResponseAId, it.matchResponseBId) }
                .filterNotNull()
                .toHashSet()
            val participants = (report?.agents ?: emptyList())
                .filter { it.agentId in participantIds }
            val labels = participants
                .mapIndexed { i, a -> (i + 1) to shortModelName2(a.model) }
                .toMap()
            val rankAgentIds = participants
                .mapIndexed { i, a -> (i + 1) to a.agentId }
                .toMap()
            // Labels for every agent (not just SUCCESS) so a participant that
            // dipped out of SUCCESS still names its model in the match cards.
            val agentIdToLabel = (report?.agents ?: emptyList()).associate {
                it.agentId to shortModelName2(it.model)
            }
            // One display row per unordered pair (keyed by the sorted agent-id
            // pair), carrying BOTH orientations separately so the screen's
            // A<>B / B<>A switch can show each one's verdict + reason + trace.
            val byPair = matchRows.groupBy {
                listOf(it.matchResponseAId, it.matchResponseBId).sortedBy { id -> id ?: "" }
            }
            val display = byPair.values.mapNotNull { rows ->
                val canonical = rows.minByOrNull { it.matchOrientation ?: 0 } ?: return@mapNotNull null
                val agA = canonical.matchResponseAId  // labelA's agentId
                // Normalise an orientation's verdict to labelA="A" / labelB="B".
                fun sideFrom(r: SecondaryResult?): H2HSide {
                    val parsed = r?.let { parseMatchVerdict(it.content) }
                    val winner = when (val v = parsed?.verdict) {
                        null -> null
                        "tie" -> "tie"
                        else -> {
                            val winnerAgent = if (v == "A") r.matchResponseAId else r.matchResponseBId
                            if (winnerAgent == agA) "A" else "B"
                        }
                    }
                    // Judging worker model for this orientation — the match row's
                    // model (sentinel "*pending"/"*workers" until judged → null).
                    val judge = r?.model?.takeIf { it.isNotBlank() && !it.startsWith("*") }?.let { shortModelName2(it) }
                    return H2HSide(winner, parsed?.reason, r?.errorMessage, r?.traceFile, judge)
                }
                MatchRow(
                    agentA = agA,
                    agentB = canonical.matchResponseBId,
                    labelA = agentIdToLabel[agA] ?: "?",
                    labelB = agentIdToLabel[canonical.matchResponseBId] ?: "?",
                    ab = sideFrom(rows.firstOrNull { (it.matchOrientation ?: 0) == 0 }),
                    ba = sideFrom(rows.firstOrNull { (it.matchOrientation ?: 0) == 1 })
                )
            }
            val done = matchRows.count { !it.content.isNullOrBlank() || it.durationMs != null }
            Loaded(row, labels, rankAgentIds, display, done, matchRows.size, report?.barTitle)
        }
    }
    val loaded = state.value
    val row = loaded.row
    val currentMethod = decodeTournamentMatrix(row?.tournamentMatrix)?.second ?: TournamentMethod.COPELAND

    // Drill-in: tap a ranking row → that model's head-to-heads (own screen).
    // Keyed by AGENT ID, not display label — shortModelName2 collapses two
    // snapshots of one model (or one model on two providers) to the same
    // string, which merged their match lists and mis-attributed the winner
    // of matches the collapsed pair played against each other.
    var h2hAgent by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)  // (agentId, display label)
    }
    val h2hTarget = h2hAgent
    if (h2hTarget != null) {
        val (h2hId, h2hLabel) = h2hTarget
        ModelHeadToHeadsScreen(
            agentId = h2hId,
            model = h2hLabel,
            matches = loaded.matches.filter { it.agentA == h2hId || it.agentB == h2hId },
            onBack = { h2hAgent = null }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Tournament",
            subject = null,
            helpTopic = "view_tournament",
            onBack = onBack
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            // Aggregation method toggle (scrolls — all methods).
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MethodChip("Copeland", TournamentMethod.COPELAND == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.COPELAND) }
                }
                MethodChip("Elo", TournamentMethod.ELO == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.ELO) }
                }
                MethodChip("Davidson", TournamentMethod.DAVIDSON == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.DAVIDSON) }
                }
                MethodChip("Markov", TournamentMethod.MARKOV == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.MARKOV) }
                }
            }

            // Ranking — reuse the shared rerank table. Tapping a row opens
            // that model's head-to-heads on its own screen.
            val rankRows = row?.content?.let { parseRerankRows(it) }
            if (rankRows != null && rankRows.isNotEmpty()) {
                // Copeland / Davidson / Markov always show one decimal (even
                // 100.0); Elo keeps natural formatting.
                val scoreDecimals = if (currentMethod == TournamentMethod.COPELAND ||
                    currentMethod == TournamentMethod.DAVIDSON ||
                    currentMethod == TournamentMethod.MARKOV) 1 else null
                RerankTable(
                    rankRows, loaded.agentLabels,
                    onRowClick = { r ->
                        loaded.rankAgentIds[r.id]?.let { id ->
                            h2hAgent = id to (loaded.agentLabels[r.id] ?: "?")
                        }
                    },
                    showReason = false,
                    scoreDecimals = scoreDecimals
                )
            } else if (loaded.totalMatches > 0) {
                Text(
                    "Judging ${loaded.doneMatches}/${loaded.totalMatches} matches…",
                    color = AppColors.TextSecondary, fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MethodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) AppColors.AppBackground else AppColors.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AppColors.PrimaryAccent else AppColors.CardBackground)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** The active agent's result for one orientation [side]: won / lost /
 *  draw / error / pending — always from the active agent's viewpoint
 *  (winner is normalised to labelA="A" / labelB="B"). [activeIsA] keys
 *  on the AGENT ID, never the label: a match between two agents whose
 *  labels collapse to the same string used to report "won" for both. */
private fun resultFor(activeIsA: Boolean, side: H2HSide): String = when {
    side.error != null -> "error"
    side.winner == null -> "pending"
    side.winner == "tie" -> "draw"
    (side.winner == "A") == activeIsA -> "won"
    else -> "lost"
}

/** Drill-in opened from a ranking row: every head-to-head [agentId]
 *  played ([model] is its display label). A top A<>B / B<>A switch picks
 *  which orientation each card shows (its reason + single 🐞 trace);
 *  won/drawn/lost stays from the active agent's viewpoint. */
@Composable
private fun ModelHeadToHeadsScreen(agentId: String, model: String, matches: List<MatchRow>, onBack: () -> Unit) {
    BackHandler { onBack() }
    var showBA by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "view_tournament",
            title = "Head-to-heads", subject = model,
            onBackClick = onBack, publishBottomBar = false
        )
        if (matches.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("No head-to-heads for this model.", color = AppColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val orientationLabel = if (showBA) "BA" else "AB"
        fun sideOf(m: MatchRow) = if (showBA) m.ba else m.ab
        fun activeIsA(m: MatchRow) = m.agentA == agentId
        val won = matches.count { resultFor(activeIsA(it), sideOf(it)) == "won" }
        val lost = matches.count { resultFor(activeIsA(it), sideOf(it)) == "lost" }
        val drew = matches.count { resultFor(activeIsA(it), sideOf(it)) == "draw" }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            // Orientation switch — A-vs-B (orientation 0) vs B-vs-A (swapped).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.FilterChip(
                    selected = !showBA, onClick = { showBA = false },
                    label = { Text("A<>B", fontSize = 12.sp) }, modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.FilterChip(
                    selected = showBA, onClick = { showBA = true },
                    label = { Text("B<>A", fontSize = 12.sp) }, modifier = Modifier.weight(1f)
                )
            }
            Text(
                "$won won · $drew drawn · $lost lost",
                color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            matches.forEach { m ->
                val opponent = if (activeIsA(m)) m.labelB else m.labelA
                val side = sideOf(m)
                HeadToHeadRow(opponent, resultFor(activeIsA(m), side), side.reason, side.error, side.trace, side.judge, orientationLabel)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HeadToHeadRow(opponent: String, result: String, reason: String?, error: String?, trace: String?, judge: String?, orientation: String) {
    val (label, color) = when (result) {
        "won" -> "won" to AppColors.SuccessAccent
        "lost" -> "lost" to AppColors.DangerAccent
        "draw" -> "draw" to AppColors.WarningAccent
        "error" -> "error" to AppColors.DangerAccent
        else -> "…" to AppColors.TextTertiary
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("vs  $opponent", color = AppColors.TextPrimary, fontSize = 13.sp)
            when {
                error != null -> Text("${com.ai.data.MetadataIconsHolder.current.warningPlain} ${error.take(60)}", color = AppColors.TextTertiary, fontSize = 11.sp)
                !reason.isNullOrBlank() -> Text(reason, color = AppColors.TextTertiary, fontSize = 11.sp)
            }
            // Last line: the judging worker model, with the 🐞 trace link
            // right of it (for the orientation the card is showing).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text("Judge: ${judge ?: "—"}", color = AppColors.InfoAccent, fontSize = 11.sp)
                TraceBug(orientation, trace)
            }
        }
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp))
    }
}

/** One 🐞 trace deep-link for a match orientation. Opens the judging call's
 *  API trace when one was recorded; otherwise a toast (tracing was off). */
@Composable
private fun TraceBug(orientation: String, traceFile: String?) {
    val navigateToRoute = com.ai.ui.shared.LocalNavigateToRoute.current
    val context = LocalContext.current
    Text(
        com.ai.data.MetadataIconsHolder.current.traces, fontSize = 14.sp,
        color = if (traceFile.isNullOrBlank()) AppColors.TextTertiary else AppColors.TextPrimary,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                if (!traceFile.isNullOrBlank()) navigateToRoute(com.ai.ui.navigation.NavRoutes.traceDetail(traceFile))
                else android.widget.Toast.makeText(context, "No trace for $orientation (enable tracing in Settings)", android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}
