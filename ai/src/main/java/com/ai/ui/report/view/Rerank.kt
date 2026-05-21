package com.ai.ui.report.view
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-only "View" variant of a RERANK row. Reached from the Rerank
 * tile (or the inline expansion picker) on Report - view; the
 * management-heavy [SecondaryResultDetailScreen] path stays for
 * Report - manage.
 *
 * Layout: numbered podium. Rank 1 / 2 / 3 each get a large card with
 * 🥇 / 🥈 / 🥉 in the leading slot, an accent gradient (Gold / Silver
 * / Bronze), the agent's provider + short model in the header and the
 * model's `reason` snippet underneath. Rank 4 and beyond render as
 * slimmer numbered rows so the eye sticks to the top three.
 */
@Composable
fun RerankViewScreen(
    reportId: String,
    resultId: String,
    onBack: () -> Unit,
    /** Tap on a podium card jumps to ReportsViewScreen pre-scrolled
     *  to that agent. Caller is responsible for closing this rerank
     *  overlay and mounting the Reports overlay with the supplied
     *  agentId. Default no-op preserves the prior shape (cards are
     *  inert when the caller doesn't wire it). */
    onOpenReportForAgent: (String) -> Unit = {}
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    // Title-bar swipe targets — `currentReportId`/`currentResultId`
    // shadow the props so a swipe can hot-swap the rerank row in
    // place. `rememberSaveable(reportId)` re-seeds on parent-driven
    // prop changes.
    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    var currentResultId by rememberSaveable(resultId) { mutableStateOf(resultId) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current

    data class Loaded(
        val result: SecondaryResult?,
        val agentLabels: Map<Int, AgentLabel>,
        val reportTitle: String?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap(), null),
        currentReportId, currentResultId
    ) {
        value = withContext(Dispatchers.IO) {
            val r = SecondaryResultStorage.get(context, currentReportId, currentResultId)
            val report = ReportStorage.getReport(context, currentReportId)
            val labels = report?.agents
                ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                ?.mapIndexed { idx, agent ->
                    (idx + 1) to AgentLabel(shortModelName(agent.model), agent.agentId)
                }?.toMap()
                ?: emptyMap()
            Loaded(r, labels, report?.title)
        }
    }
    val loaded = loadedState.value
    val result = loaded.result
    val agentLabels = loaded.agentLabels

    val rows = remember(result) {
        result?.content?.let { parseRerankRows(it) } ?: emptyList()
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // 🔧 → Manage's SecondaryResultDetail for this rerank row.
        // No fallback to LocalNavigateToCurrentReport — that local is
        // overridden by ViewAiReportScreen to "back to View grid",
        // which would land 🔧 back on the grid instead of Manage.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.MetaResult(currentResultId)) }
        }
        ViewTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Rerank",
            // Green metaPromptName subject still dropped per the
            // user's spec — the orange "Rerank" line alone is enough.
            subject = null,
            helpTopic = "rerank_view",
            onOpenManage = onOpenManageJump,
            onBack = onBack,
            onSwipePrev = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev,
                    ViewSwipeFilter.HasKind(com.ai.data.SecondaryKind.RERANK))
                if (m != null) {
                    currentReportId = m.reportId
                    m.resultId?.let { currentResultId = it }
                    switchReport?.invoke(m.reportId); true
                } else false
            },
            onSwipeNext = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next,
                    ViewSwipeFilter.HasKind(com.ai.data.SecondaryKind.RERANK))
                if (m != null) {
                    currentReportId = m.reportId
                    m.resultId?.let { currentResultId = it }
                    switchReport?.invoke(m.reportId); true
                } else false
            }
        )
        if (result == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("Loading…", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CardBackground)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "🏆", fontSize = 40.sp)
                    Text(
                        text = "Rerank produced no parseable rows",
                        color = AppColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Drop back to Report - manage to inspect the raw JSON or re-run with a different model.",
                        color = AppColors.TextTertiary, fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            items(rows) { row ->
                val label = agentLabels[row.id]
                val onCardClick: (() -> Unit)? = label?.agentId?.let { aid -> { onOpenReportForAgent(aid) } }
                when (row.rank) {
                    1 -> PodiumCard(row, label, MedalSpec("🥇", Color(0xFFFFD54F), "Gold"), onCardClick)
                    2 -> PodiumCard(row, label, MedalSpec("🥈", Color(0xFFB0BEC5), "Silver"), onCardClick)
                    3 -> PodiumCard(row, label, MedalSpec("🥉", Color(0xFFCD7F32), "Bronze"), onCardClick)
                    else -> RankRow(row, label)
                }
            }
        }
    }
}

/** Short-model label + agent id for the per-rank row. The provider
 *  name is intentionally not surfaced on the View variant (kept off
 *  per the user's spec). agentId backs the card-tap → Reports
 *  navigation. */
private data class AgentLabel(val shortModel: String, val agentId: String)

/** Medal styling for the top-3 podium cards. */
private data class MedalSpec(val emoji: String, val accent: Color, val name: String)

@Composable
private fun PodiumCard(row: RerankRow, label: AgentLabel?, spec: MedalSpec, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(spec.accent.copy(alpha = 0.38f), spec.accent.copy(alpha = 0.08f))
                )
            )
            .border(1.dp, spec.accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = spec.emoji, fontSize = 36.sp, modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Top line: model name on the left, score on the right
                // — score in the same 12sp TextTertiary font as the
                // "Rank N - Medal" line below so it reads as a quiet
                // pair, not a heavy chip. Provider name omitted on
                // the View variant.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label?.shortModel ?: "[${row.id}] (unknown)",
                        color = AppColors.TextPrimary,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (row.score != null) {
                        Text(
                            text = "Relevance score ${row.score}",
                            color = AppColors.TextTertiary,
                            fontSize = 12.sp,
                            maxLines = 1, softWrap = false
                        )
                    }
                }
                Text(
                    text = "Rank ${row.rank} · ${spec.name}",
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp
                )
            }
        }
        if (!row.reason.isNullOrBlank()) {
            Text(
                text = row.reason,
                color = AppColors.TextSecondary,
                fontSize = 13.sp, lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun RankRow(row: RerankRow, label: AgentLabel?) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0x33000000)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${row.rank ?: "-"}",
                color = AppColors.Yellow,
                fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label?.shortModel ?: "[${row.id}] (unknown)",
                color = AppColors.TextPrimary,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!row.reason.isNullOrBlank()) {
                Text(
                    text = row.reason,
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (row.score != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${row.score}",
                color = AppColors.Yellow,
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false
            )
        }
    }
}
