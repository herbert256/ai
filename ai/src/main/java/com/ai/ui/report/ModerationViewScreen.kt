package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * Content-only "View" variant of a MODERATION row. Reached from the
 * Moderation tile (or its inline-picker for N≥2) on Report - view.
 *
 * Layout: per-agent card with a traffic-light row. Each per-category
 * chip is coloured red (fired), amber (score in the upper-mid range
 * but not boolean-fired), or green (clean). A 🚩 callout banner sits
 * above the chip row when the agent has anything fired so flagged
 * agents are obvious at a glance. The category set is the union of
 * every per-agent category map so the chip layout stays consistent
 * across agents.
 */
@Composable
fun ModerationViewScreen(
    reportId: String,
    resultId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    data class Loaded(
        val result: SecondaryResult?,
        val agentLabels: Map<Int, String>,
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
                    (idx + 1) to "$provDisplay / ${shortModelName(agent.model)}"
                }?.toMap()
                ?: emptyMap()
            Loaded(r, labels, report?.title)
        }
    }
    val loaded = loadedState.value
    val result = loaded.result
    val agentLabels = loaded.agentLabels

    val rows = remember(result) {
        result?.content?.let { parseModerationRows(it) } ?: emptyList()
    }
    val title = result?.metaPromptName?.takeIf { it.isNotBlank() } ?: "Moderation"
    val anyFlagged = rows.any { it.flagged }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Moderation - view",
            subject = result?.metaPromptName?.takeIf { it.isNotBlank() },
            helpTopic = "moderation_view",
            onBack = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🚩", fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (anyFlagged) AppColors.Red else AppColors.Green,
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
                    Text(text = "🚩", fontSize = 40.sp)
                    Text(
                        text = "Moderation produced no parseable rows",
                        color = AppColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Drop back to Report - manage to inspect the raw JSON or re-run.",
                        color = AppColors.TextTertiary, fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            return@Column
        }
        // Build a stable union of categories across every row so the
        // chip row width doesn't jump per agent. Sorted alphabetical
        // for legibility.
        val allCategories = remember(rows) {
            rows.flatMap { it.allCategories.keys }.toSortedSet().toList()
        }
        // Hero summary banner — overall verdict at the top.
        VerdictHero(rows.size, rows.count { it.flagged })
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            items(rows) { r ->
                AgentModerationCard(
                    row = r,
                    label = agentLabels[r.id] ?: "[${r.id}] (unknown)",
                    categories = allCategories
                )
            }
        }
    }
}

@Composable
private fun VerdictHero(total: Int, flagged: Int) {
    val clean = total - flagged
    val accent = if (flagged > 0) AppColors.Red else AppColors.Green
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (flagged > 0) "🚩" else "✅",
            fontSize = 28.sp,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (flagged > 0) "$flagged of $total flagged" else "$total clean",
                color = AppColors.TextPrimary,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (flagged > 0) {
                    "Red chips fired; amber show elevated scores."
                } else "Every agent came back below threshold.",
                color = AppColors.TextTertiary, fontSize = 12.sp
            )
        }
        if (clean > 0 && flagged > 0) {
            Text(
                text = "$clean clean",
                color = AppColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentModerationCard(
    row: ModerationRow,
    label: String,
    categories: List<String>
) {
    val borderColor = if (row.flagged) AppColors.Red.copy(alpha = 0.55f) else AppColors.Green.copy(alpha = 0.4f)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (row.flagged) "🚩" else "✓",
                fontSize = 18.sp,
                color = if (row.flagged) AppColors.Red else AppColors.Green,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = label,
                color = AppColors.TextPrimary,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (row.flagged && row.firedCategories.isNotEmpty()) {
            Text(
                text = "🚩 Fired: ${row.firedCategories.joinToString(", ")}",
                color = AppColors.Red, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            categories.forEach { cat ->
                val fired = row.allCategories[cat] == true
                val score = row.allScores[cat] ?: 0.0
                CategoryChip(cat, fired, score)
            }
        }
    }
}

@Composable
private fun CategoryChip(cat: String, fired: Boolean, score: Double) {
    // Traffic light: red (fired), amber (score >= 0.3 but not fired),
    // green (clean). Score baked into the label so the user reads the
    // intensity without tapping.
    val color = when {
        fired -> AppColors.Red
        score >= 0.3 -> AppColors.Orange
        else -> AppColors.Green
    }
    val text = "$cat ${"%.2f".format(score)}"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.22f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false
        )
    }
}
