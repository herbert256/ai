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
@Composable
fun TournamentViewScreen(
    reportId: String,
    resultId: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    data class MatchRow(val labelA: String, val labelB: String, val verdict: String?, val reason: String?, val error: String?)
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
            // pair (keyed by the sorted agent-id pair); show the canonical
            // orientation's verdict for the snippet.
            val byPair = matchRows.groupBy {
                listOf(it.matchResponseAId, it.matchResponseBId).sortedBy { id -> id ?: "" }
            }
            val display = byPair.values.mapNotNull { rows ->
                val canonical = rows.minByOrNull { it.matchOrientation ?: 0 } ?: return@mapNotNull null
                val v = parseMatchVerdict(canonical.content)
                MatchRow(
                    labelA = agentIdToLabel[canonical.matchResponseAId] ?: "?",
                    labelB = agentIdToLabel[canonical.matchResponseBId] ?: "?",
                    verdict = v?.verdict,
                    reason = v?.reason,
                    error = rows.firstNotNullOfOrNull { it.errorMessage }
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
            // 3-way aggregation method toggle.
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            }

            // Ranking — reuse the shared rerank table.
            val rankRows = row?.content?.let { parseRerankRows(it) }
            if (rankRows != null && rankRows.isNotEmpty()) {
                RerankTable(rankRows, loaded.agentLabels)
            } else if (loaded.totalMatches > 0) {
                Text(
                    "Judging ${loaded.doneMatches}/${loaded.totalMatches} matches…",
                    color = AppColors.TextSecondary, fontSize = 14.sp
                )
            }

            // Per-match inspection list.
            if (loaded.matches.isNotEmpty()) {
                Text(
                    "Head-to-heads (${loaded.doneMatches}/${loaded.totalMatches})",
                    color = AppColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
                loaded.matches.forEach { m -> MatchListRow(m.labelA, m.labelB, m.verdict, m.reason, m.error) }
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

@Composable
private fun MatchListRow(labelA: String, labelB: String, verdict: String?, reason: String?, error: String?) {
    val winner = when (verdict) {
        "A" -> labelA
        "B" -> labelB
        "tie" -> "tie"
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("$labelA  vs  $labelB", color = Color.White, fontSize = 13.sp)
            val sub = when {
                error != null -> "⚠ ${error.take(60)}"
                winner == "tie" -> "tie"
                winner != null -> "winner: $winner"
                else -> "…"
            }
            Text(sub, color = AppColors.TextTertiary, fontSize = 11.sp)
            if (!reason.isNullOrBlank()) Text(reason, color = AppColors.TextTertiary, fontSize = 11.sp)
        }
    }
}
