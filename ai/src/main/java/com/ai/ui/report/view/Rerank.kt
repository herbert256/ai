package com.ai.ui.report.view

import com.ai.data.barTitle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.viewBodySwipe
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
    onOpenReportForAgent: (String) -> Unit = {},
    /** 💎 cross-link: open the Value view (this ranking feeds it). */
    onOpenValueView: (() -> Unit)? = null
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

    val reportDataVersion by ReportDataVersion.versionFor(currentReportId).collectAsState()
    val secondaryDataVersion by SecondaryDataVersion.versionFor(currentReportId, SecondaryKind.RERANK).collectAsState()
    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap(), null),
        currentReportId, currentResultId, reportDataVersion, secondaryDataVersion
    ) {
        value = withContext(Dispatchers.IO) {
            val r = SecondaryResultStorage.get(context, currentReportId, currentResultId)
            val report = com.ai.ui.report.view.helpers.ViewReportCache.get(context, currentReportId)
            // Resolve the [N] ids through the row's run-time sourceAgentIds
            // snapshot, like Manage's RerankDetailScreen — mapping positions
            // into the CURRENT success set attributed every medal to the
            // wrong model (and podium taps opened the wrong agent) once a
            // deletion or a failed→success regenerate shifted the numbering.
            // Legacy rows without a snapshot keep the positional fallback.
            val snapshot = r?.sourceAgentIds
            val agents = report?.agents.orEmpty()
            val labels: Map<Int, AgentLabel> = if (!snapshot.isNullOrEmpty()) {
                val byId = agents.associateBy { it.agentId }
                snapshot.mapIndexed { idx, aid ->
                    val agent = byId[aid]
                    (idx + 1) to AgentLabel(
                        agent?.model?.let { shortModelName(it) } ?: "(removed model)",
                        agent?.agentId
                    )
                }.toMap()
            } else {
                agents
                    .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    .mapIndexed { idx, agent ->
                        (idx + 1) to AgentLabel(shortModelName(agent.model), agent.agentId)
                    }.toMap()
            }
            Loaded(r, labels, report?.barTitle)
        }
    }
    val loaded = loadedState.value
    val result = loaded.result
    val agentLabels = loaded.agentLabels

    val rows = remember(result) {
        result?.content?.let { parseRerankRows(it) } ?: emptyList()
    }
    val onSwipePrevAction: () -> Boolean = {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev,
            ViewSwipeFilter.HasKind(com.ai.data.SecondaryKind.RERANK))
        if (m != null) { currentReportId = m.reportId; m.resultId?.let { currentResultId = it }; switchReport?.invoke(m.reportId); true } else false
    }
    val onSwipeNextAction: () -> Boolean = {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next,
            ViewSwipeFilter.HasKind(com.ai.data.SecondaryKind.RERANK))
        if (m != null) { currentReportId = m.reportId; m.resultId?.let { currentResultId = it }; switchReport?.invoke(m.reportId); true } else false
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .viewBodySwipe(currentReportId, onPrev = { onSwipePrevAction() }, onNext = { onSwipeNextAction() })
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
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction
        )
        if (onOpenValueView != null) {
            Text(
                "${com.ai.data.MetadataIconsHolder.current.gem} Value view — cost × quality for this ranking",
                color = AppColors.SuccessAccent, fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onOpenValueView() }
                    .padding(vertical = 4.dp)
            )
        }
        com.ai.ui.report.manage.ViewUserNotes(currentReportId, "SECONDARY", currentResultId)
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
                    Text(text = com.ai.data.MetadataIconsHolder.current.rerank, fontSize = 40.sp)
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
                    1 -> PodiumCard(row, label, MedalSpec(com.ai.data.MetadataIconsHolder.current.medalGold, AppColors.WarningAccent, "Gold"), onCardClick)
                    2 -> PodiumCard(row, label, MedalSpec(com.ai.data.MetadataIconsHolder.current.medalSilver, AppColors.TextSecondary, "Silver"), onCardClick)
                    3 -> PodiumCard(row, label, MedalSpec(com.ai.data.MetadataIconsHolder.current.medalBronze, AppColors.QueueAccent, "Bronze"), onCardClick)
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
/** [agentId] is null for a snapshot entry whose agent was removed from
 *  the report — the label still names the rank, but there is no card
 *  tap-through. */
private data class AgentLabel(val shortModel: String, val agentId: String?)

/** Medal styling for the top-3 podium cards. */
private data class MedalSpec(val emoji: String, val accent: Color, val name: String)

@Composable
private fun PodiumCard(row: RerankRow, label: AgentLabel?, spec: MedalSpec, onClick: (() -> Unit)?) {
    // Same row layout as [RankRow] (model name + 2-line reason + score
    // on the right) so the whole list reads uniformly; the top three
    // keep their medal accent gradient / border and lead with the
    // 🥇/🥈/🥉 medal in place of the numbered circle badge.
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    listOf(spec.accent.copy(alpha = 0.38f), spec.accent.copy(alpha = 0.08f))
                )
            )
            .border(1.dp, spec.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Text(text = spec.emoji, fontSize = 24.sp)
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
                text = formatRerankScore(row.score),
                color = AppColors.CautionAccent,
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false
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
            modifier = Modifier.size(34.dp).clip(CircleShape).background(AppColors.AppBackground.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${row.rank ?: "-"}",
                color = AppColors.CautionAccent,
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
                text = formatRerankScore(row.score),
                color = AppColors.CautionAccent,
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false
            )
        }
    }
}
