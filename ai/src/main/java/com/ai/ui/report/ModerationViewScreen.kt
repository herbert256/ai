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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    var currentResultId by rememberSaveable(resultId) { mutableStateOf(resultId) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current

    data class Loaded(
        val result: SecondaryResult?,
        val agentLabels: Map<Int, String>,
        val agentResponses: Map<Int, String>,
        val reportTitle: String?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap(), emptyMap(), null),
        currentReportId, currentResultId
    ) {
        value = withContext(Dispatchers.IO) {
            val r = SecondaryResultStorage.get(context, currentReportId, currentResultId)
            val report = ReportStorage.getReport(context, currentReportId)
            val successful = report?.agents
                ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .orEmpty()
            // Provider name dropped per the user's spec — just the
            // short model name for the per-card header.
            val labels = successful.mapIndexed { idx, agent ->
                (idx + 1) to shortModelName(agent.model)
            }.toMap()
            // The model's response body — the same text that was
            // moderated. Rendered in a response card below the per-
            // model moderation card so the user can read what the
            // chips refer to without leaving the screen.
            val responses = successful.mapIndexed { idx, agent ->
                (idx + 1) to (agent.responseBody ?: "")
            }.toMap()
            Loaded(r, labels, responses, report?.title)
        }
    }
    val loaded = loadedState.value
    val result = loaded.result
    val agentLabels = loaded.agentLabels
    val agentResponses = loaded.agentResponses

    val rows = remember(result) {
        result?.content?.let { parseModerationRows(it) } ?: emptyList()
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // 🔧 → Manage's SecondaryResultDetail for this moderation row.
        // No fallback to LocalNavigateToCurrentReport — that local is
        // overridden by ViewAiReportScreen to "back to View grid",
        // which would land 🔧 back on the grid instead of Manage.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.MetaResult(currentResultId)) }
        }
        ViewScreenTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Moderation",
            // Green metaPromptName subject + the big 🚩/title row
            // beneath it are gone per the user's spec — the orange
            // "Moderation" screen-title plus the per-card chips read
            // cleanly enough on their own.
            subject = null,
            helpTopic = "moderation_view",
            onOpenManage = onOpenManageJump,
            onBack = onBack,
            onSwipePrev = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev,
                    ViewSwipeFilter.HasKind(com.ai.data.SecondaryKind.MODERATION))
                if (m != null) {
                    currentReportId = m.reportId
                    m.resultId?.let { currentResultId = it }
                    switchReport?.invoke(m.reportId); true
                } else false
            },
            onSwipeNext = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next,
                    ViewSwipeFilter.HasKind(com.ai.data.SecondaryKind.MODERATION))
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
        // Per-model HorizontalPager: one page per agent. Swipe → next
        // model. Counter sits between the verdict hero and the card
        // so the user reads top-to-bottom:
        //   title → verdict → X / Y → moderation card → response card.
        val pagerState = rememberPagerState(initialPage = 0) { rows.size }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "${pagerState.currentPage + 1} / ${rows.size}",
            color = AppColors.TextTertiary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
        )
        com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
            pagerState = pagerState,
            noMoreLabel = "No more models",
            modifier = Modifier.fillMaxSize()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val r = rows[page]
                // LazyColumn (not verticalScroll Column) so the inner
                // vertical scroll cooperates with HorizontalPager's
                // horizontal drag detection — a swipe across the
                // Response card flips the page, same as a swipe across
                // the moderation card above it.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item {
                        AgentModerationCard(
                            row = r,
                            label = agentLabels[r.id] ?: "[${r.id}] (unknown)",
                            categories = allCategories
                        )
                    }
                    item {
                        ResponseCard(body = agentResponses[r.id].orEmpty())
                    }
                }
            }
        }
    }
}

/** Plain card showing the model's response body — the text that was
 *  moderated. Blue accent so it visually pairs with the moderation
 *  chips above without competing with the red/green verdict signal. */
@Composable
private fun ResponseCard(body: String) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Blue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Response",
            color = AppColors.Blue, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (body.isBlank()) {
            Text("(no response)", color = AppColors.TextTertiary, fontSize = 13.sp)
        } else {
            ContentWithThinkSections(analysis = body)
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
