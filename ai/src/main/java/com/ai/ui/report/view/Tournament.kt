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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ReportStatus
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
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Content-only "View" screen for a tournament's AGGREGATE ranking row.
 * Renders the head-to-head ranking through the shared [RerankTable]
 * (the aggregate `content` is rerank-shaped JSON), with a 3-way method
 * toggle (Copeland / Bradley–Terry / Elo) that recomputes locally from
 * the stored win matrix — no API calls — and a per-match list so the
 * user can inspect every judged pair.
 */
/** One collapsed head-to-head (the two orientations of a pair merged).
 *  [traceAB] / [traceBA] are the judging-call trace filenames for the
 *  A-vs-B (orientation 0) and B-vs-A (orientation 1) matches, when tracing
 *  recorded them. */
private data class MatchRow(
    val labelA: String, val labelB: String,
    val verdict: String?, val reason: String?, val error: String?,
    val traceAB: String? = null, val traceBA: String? = null
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
        val matches: List<MatchRow>,
        val doneMatches: Int,
        val totalMatches: Int,
        val reportTitle: String?
    )

    val secondaryDataVersion by SecondaryDataVersion.version.collectAsState()
    val state = produceState(
        initialValue = Loaded(null, emptyMap(), emptyList(), 0, 0, null),
        reportId, resultId, secondaryDataVersion
    ) {
        value = withContext(Dispatchers.IO) {
            val row = SecondaryResultStorage.get(context, reportId, resultId)
            val report = ReportStorage.getReport(context, reportId)
            val successful = report?.agents
                ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                ?: emptyList()
            val labels = successful.mapIndexed { i, a -> (i + 1) to shortModelName(a.model) }.toMap()
            val agentIdToLabel = successful.associate {
                it.agentId to shortModelName(it.model)
            }
            val matchRows = row?.tournamentJudgeRunId?.let { rk ->
                SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
                    .filter { it.tournamentRole == "MATCH" && it.tournamentJudgeRunId == rk }
            }.orEmpty()
            // Collapse the two orientations into one display row per unordered
            // pair (keyed by the sorted agent-id pair). The verdict COMBINES
            // both orientations (same clear-winner / draw rule the win matrix
            // + Points use) so the head-to-heads agree with the ranking; the
            // canonical orientation's reason is kept as context.
            val byPair = matchRows.groupBy {
                listOf(it.matchResponseAId, it.matchResponseBId).sortedBy { id -> id ?: "" }
            }
            val display = byPair.values.mapNotNull { rows ->
                val canonical = rows.minByOrNull { it.matchOrientation ?: 0 } ?: return@mapNotNull null
                // Credit for agA (canonical A-slot) across both orientations.
                val votes = rows.mapNotNull { r ->
                    val v = parseMatchVerdict(r.content)?.verdict ?: return@mapNotNull null
                    if ((r.matchOrientation ?: 0) == 0) when (v) { "A" -> 1.0; "B" -> 0.0; else -> 0.5 }
                    else when (v) { "A" -> 0.0; "B" -> 1.0; else -> 0.5 }
                }
                val combined = if (votes.isEmpty()) null else votes.average().let {
                    when { it > 0.5 -> "A"; it < 0.5 -> "B"; else -> "tie" }
                }
                MatchRow(
                    labelA = agentIdToLabel[canonical.matchResponseAId] ?: "?",
                    labelB = agentIdToLabel[canonical.matchResponseBId] ?: "?",
                    verdict = combined,
                    reason = parseMatchVerdict(canonical.content)?.reason,
                    error = rows.firstNotNullOfOrNull { it.errorMessage },
                    traceAB = rows.firstOrNull { (it.matchOrientation ?: 0) == 0 }?.traceFile,
                    traceBA = rows.firstOrNull { (it.matchOrientation ?: 0) == 1 }?.traceFile
                )
            }
            val done = matchRows.count { !it.content.isNullOrBlank() || it.durationMs != null }
            Loaded(row, labels, display, done, matchRows.size, report?.barTitle)
        }
    }
    val loaded = state.value
    val row = loaded.row
    val currentMethod = decodeTournamentMatrix(row?.tournamentMatrix)?.second ?: TournamentMethod.COPELAND
    val judgeLabel = row?.let { "${AppService.findById(it.providerId)?.id ?: it.providerId} / ${shortModelName(it.model)}" }

    // Drill-in: tap a ranking row → that model's head-to-heads (own screen).
    var h2hModel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val h2hTarget = h2hModel
    if (h2hTarget != null) {
        ModelHeadToHeadsScreen(
            model = h2hTarget,
            matches = loaded.matches.filter { it.labelA == h2hTarget || it.labelB == h2hTarget },
            onBack = { h2hModel = null }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "view_tournament",
            title = "Tournament",
            subject = judgeLabel,
            onBackClick = onBack,
            publishBottomBar = false
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            // Aggregation method toggle (scrolls — four methods).
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MethodChip("Copeland", TournamentMethod.COPELAND == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.COPELAND) }
                }
                MethodChip("Bradley–Terry", TournamentMethod.BRADLEY_TERRY == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.BRADLEY_TERRY) }
                }
                MethodChip("Elo", TournamentMethod.ELO == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.ELO) }
                }
                MethodChip("Points", TournamentMethod.POINTS == currentMethod) {
                    if (row != null) scope.launch(Dispatchers.IO) { applyTournamentMethod(context, reportId, resultId, TournamentMethod.POINTS) }
                }
            }

            // Ranking — reuse the shared rerank table. Tapping a row opens
            // that model's head-to-heads on its own screen.
            val rankRows = row?.content?.let { parseRerankRows(it) }
            if (rankRows != null && rankRows.isNotEmpty()) {
                // Points / Bradley–Terry always show one decimal (even 100.0);
                // Copeland / Elo keep their natural formatting. Reason hidden.
                val scoreDecimals = if (currentMethod == TournamentMethod.POINTS ||
                    currentMethod == TournamentMethod.BRADLEY_TERRY) 1 else null
                RerankTable(
                    rankRows, loaded.agentLabels,
                    onRowClick = { r -> h2hModel = loaded.agentLabels[r.id] },
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
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AppColors.Purple else AppColors.CardBackground)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** This model's result in a collapsed head-to-head: won / lost / draw /
 *  error / pending — from [model]'s perspective. */
private fun resultFor(model: String, m: MatchRow): String = when {
    m.error != null -> "error"
    m.verdict == "tie" -> "draw"
    m.verdict == null -> "pending"
    (m.verdict == "A" && m.labelA == model) || (m.verdict == "B" && m.labelB == model) -> "won"
    else -> "lost"
}

/** Drill-in opened from a ranking row: every head-to-head [model] played,
 *  from its own perspective (vs opponent → won / lost / draw + reason). */
@Composable
private fun ModelHeadToHeadsScreen(model: String, matches: List<MatchRow>, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
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
        val won = matches.count { resultFor(model, it) == "won" }
        val lost = matches.count { resultFor(model, it) == "lost" }
        val drew = matches.count { resultFor(model, it) == "draw" }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "$won won · $drew drawn · $lost lost",
                color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            matches.forEach { m ->
                val opponent = if (m.labelA == model) m.labelB else m.labelA
                HeadToHeadRow(opponent, resultFor(model, m), m.reason, m.error, m.traceAB, m.traceBA)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HeadToHeadRow(opponent: String, result: String, reason: String?, error: String?, traceAB: String?, traceBA: String?) {
    val (label, color) = when (result) {
        "won" -> "won" to AppColors.Green
        "lost" -> "lost" to AppColors.Red
        "draw" -> "draw" to AppColors.Orange
        "error" -> "error" to AppColors.Red
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
            Text("vs  $opponent", color = Color.White, fontSize = 13.sp)
            when {
                error != null -> Text("⚠ ${error.take(60)}", color = AppColors.TextTertiary, fontSize = 11.sp)
                !reason.isNullOrBlank() -> Text(reason, color = AppColors.TextTertiary, fontSize = 11.sp)
            }
        }
        // Two trace deep-links: 🐞 for the A-vs-B match, 🐞 for B-vs-A.
        TraceBug("AB", traceAB)
        TraceBug("BA", traceBA)
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                if (!traceFile.isNullOrBlank()) navigateToRoute(com.ai.ui.navigation.NavRoutes.traceDetail(traceFile))
                else android.widget.Toast.makeText(context, "No trace for $orientation (enable tracing in Settings)", android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text("🐞", fontSize = 14.sp, color = if (traceFile.isNullOrBlank()) AppColors.TextTertiary else Color.White)
        Text(orientation, fontSize = 8.sp, color = AppColors.TextTertiary)
    }
}
