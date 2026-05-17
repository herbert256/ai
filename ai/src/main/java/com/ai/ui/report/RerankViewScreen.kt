package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.ai.ui.shared.ViewScreenTitleBar
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
    onBack: () -> Unit
) {
    val context = LocalContext.current

    data class Loaded(
        val result: SecondaryResult?,
        val agentLabels: Map<Int, AgentLabel>,
        val reportTitle: String?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap(), null),
        reportId, resultId
    ) {
        value = withContext(Dispatchers.IO) {
            val r = SecondaryResultStorage.get(context, reportId, resultId)
            val report = ReportStorage.getReport(context, reportId)
            val labels = report?.agents
                ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                ?.mapIndexed { idx, agent ->
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    (idx + 1) to AgentLabel(provDisplay, shortModelName(agent.model))
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
    val title = result?.metaPromptName?.takeIf { it.isNotBlank() } ?: "Rerank"

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Rerank - view",
            subject = result?.metaPromptName?.takeIf { it.isNotBlank() },
            helpTopic = "rerank_view",
            onBack = onBack,
            onLogoClick = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🏆", fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Yellow,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
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
                when (row.rank) {
                    1 -> PodiumCard(row, label, MedalSpec("🥇", Color(0xFFFFD54F), "Gold"))
                    2 -> PodiumCard(row, label, MedalSpec("🥈", Color(0xFFB0BEC5), "Silver"))
                    3 -> PodiumCard(row, label, MedalSpec("🥉", Color(0xFFCD7F32), "Bronze"))
                    else -> RankRow(row, label)
                }
            }
        }
    }
}

/** Provider + short-model pair for the agent at row id. */
private data class AgentLabel(val provider: String, val shortModel: String)

/** Medal styling for the top-3 podium cards. */
private data class MedalSpec(val emoji: String, val accent: Color, val name: String)

@Composable
private fun PodiumCard(row: RerankRow, label: AgentLabel?, spec: MedalSpec) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(spec.accent.copy(alpha = 0.38f), spec.accent.copy(alpha = 0.08f))
                )
            )
            .border(1.dp, spec.accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = spec.emoji, fontSize = 36.sp, modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label?.let { "${it.provider} / ${it.shortModel}" } ?: "[${row.id}] (unknown)",
                    color = AppColors.TextPrimary,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Rank ${row.rank} · ${spec.name}",
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp
                )
            }
            if (row.score != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x55000000))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${row.score}/100",
                        color = spec.accent,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false
                    )
                }
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
                text = label?.let { "${it.provider} / ${it.shortModel}" } ?: "[${row.id}] (unknown)",
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
