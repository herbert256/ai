package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * View-flow Icons page. Replaces the old flat-grid
 * `ReportIconsGridScreen`. Surfaces the report's fan-out topology:
 *  - Report icon centered at the top.
 *  - When the report has no fan-out: all Model Report icons below
 *    in a flow grid.
 *  - When the report has one or more fan-out runs: one section per
 *    run, each containing rows of
 *    `[initiator] → [responder1] [responder2] …`.
 *
 * Click handlers:
 *  - Initiator (or any model icon in the no-fan-out grid) opens
 *    [ReportsViewScreen] scrolled to that agent's pager page.
 *  - Responder opens [FanOutPairViewScreen] for that exact pair.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconsViewScreen(reportId: String, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current

    data class Loaded(
        val report: Report?,
        val fanOutRows: List<SecondaryResult>
    )

    val loadedState = produceState(
        initialValue = Loaded(null, emptyList()),
        reportId
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val rows = SecondaryResultStorage.listForReport(context, reportId).filter {
                it.fanOutSourceAgentId != null && !it.content.isNullOrBlank()
            }
            Loaded(rep, rows)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val fanOutRows = loaded.fanOutRows

    // Internal overlay state: tapping an icon mounts ReportsViewScreen
    // or FanOutPairViewScreen as a child of this screen so Android
    // back returns here (LIFO BackHandler stack).
    var openedReportsAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedPairMeta by rememberSaveable { mutableStateOf<String?>(null) }
    var openedPairSource by rememberSaveable { mutableStateOf<String?>(null) }
    var openedPairAnswererProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var openedPairAnswererModel by rememberSaveable { mutableStateOf<String?>(null) }

    if (openedReportsAgentId != null) {
        ReportsViewScreen(
            reportId = reportId,
            language = null,
            initialAgentId = openedReportsAgentId,
            onBack = { openedReportsAgentId = null }
        )
        return
    }
    if (openedPairMeta != null && openedPairSource != null &&
        openedPairAnswererProvider != null && openedPairAnswererModel != null
    ) {
        FanOutPairViewScreen(
            reportId = reportId,
            metaPromptName = openedPairMeta!!,
            sourceAgentId = openedPairSource!!,
            answererProviderId = openedPairAnswererProvider!!,
            answererModel = openedPairAnswererModel!!,
            onBack = {
                openedPairMeta = null
                openedPairSource = null
                openedPairAnswererProvider = null
                openedPairAnswererModel = null
            }
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Icons",
            subject = null,
            helpTopic = "icons_view",
            onBack = onBack,
            onLogoClick = onBack
        )

        if (report == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("Loading…", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalDivider(
                color = AppColors.BorderUnfocused,
                thickness = 1.dp,
                modifier = Modifier.fillMaxWidth()
            )
            // Report icon — large, centred.
            Text(
                text = report.icon?.takeIf { it.isNotBlank() } ?: "📄",
                fontSize = 80.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            if (fanOutRows.isEmpty()) {
                HorizontalDivider(
                    color = AppColors.BorderUnfocused,
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                NoFanOutGrid(
                    agents = report.agents,
                    onAgentClick = { agentId -> openedReportsAgentId = agentId }
                )
                HorizontalDivider(
                    color = AppColors.BorderUnfocused,
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Group rows by metaPromptName (= one section per
                // fan-out run). LinkedHashMap keeps the order they
                // were written in.
                val byRun = remember(fanOutRows) {
                    val map = LinkedHashMap<String, MutableList<SecondaryResult>>()
                    for (row in fanOutRows) {
                        val key = row.metaPromptName.orEmpty().ifBlank { "Fan-out" }
                        map.getOrPut(key) { mutableListOf() }.add(row)
                    }
                    map
                }
                // Hide the per-run header when there's only one
                // fan-out — the screen-level context already makes it
                // unambiguous and the extra row is just noise.
                val showRunHeader = byRun.size > 1
                byRun.forEach { (metaPromptName, rows) ->
                    FanOutSection(
                        metaPromptName = metaPromptName,
                        showHeader = showRunHeader,
                        rows = rows,
                        agents = report.agents,
                        onInitiatorClick = { agentId -> openedReportsAgentId = agentId },
                        onResponderClick = { sourceAgentId, providerId, model ->
                            openedPairMeta = metaPromptName
                            openedPairSource = sourceAgentId
                            openedPairAnswererProvider = providerId
                            openedPairAnswererModel = model
                        }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoFanOutGrid(
    agents: List<ReportAgent>,
    onAgentClick: (String) -> Unit
) {
    val visible = agents.filter {
        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
    }
    if (visible.isEmpty()) {
        Text(
            text = "(no model responses yet)",
            color = AppColors.TextTertiary, fontSize = 13.sp
        )
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        visible.forEach { agent ->
            AgentIconButton(
                glyph = agent.icon?.takeIf { it.isNotBlank() } ?: "🤖",
                onClick = { onAgentClick(agent.agentId) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FanOutSection(
    metaPromptName: String,
    showHeader: Boolean,
    rows: List<SecondaryResult>,
    agents: List<ReportAgent>,
    onInitiatorClick: (String) -> Unit,
    onResponderClick: (sourceAgentId: String, providerId: String, model: String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showHeader) {
            // Section header: 🌀 + the run's meta-prompt name in green
            // to match the rest of the View family's subject style.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🌀", fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = metaPromptName,
                    color = AppColors.Green,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Group this section's rows by initiator agent — one row per
        // initiator with all of its responders to the right.
        val bySource = remember(rows) {
            val map = LinkedHashMap<String, MutableList<SecondaryResult>>()
            for (row in rows) {
                val key = row.fanOutSourceAgentId ?: continue
                map.getOrPut(key) { mutableListOf() }.add(row)
            }
            map
        }
        val entries = bySource.entries.toList()
        entries.forEachIndexed { idx, (sourceAgentId, responderRows) ->
            // Divider above every row, including the first — paired
            // with the trailing divider after the last row so each
            // initiator sits in its own bracketed band.
            HorizontalDivider(color = AppColors.BorderUnfocused, thickness = 1.dp)
            val initiator = agents.firstOrNull { it.agentId == sourceAgentId }
            val initiatorGlyph = initiator?.icon?.takeIf { it.isNotBlank() } ?: "🤖"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AgentIconButton(
                    glyph = initiatorGlyph,
                    onClick = { onInitiatorClick(sourceAgentId) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "→",
                    color = AppColors.TextSecondary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    responderRows.forEach { row ->
                        val glyph = row.icon?.takeIf { it.isNotBlank() } ?: "🤖"
                        AgentIconButton(
                            glyph = glyph,
                            onClick = {
                                onResponderClick(sourceAgentId, row.providerId, row.model)
                            }
                        )
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            HorizontalDivider(color = AppColors.BorderUnfocused, thickness = 1.dp)
        }
    }
}

/** Round emoji tile, sized for comfortable tapping. */
@Composable
private fun AgentIconButton(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(AppColors.SurfaceDark)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = glyph, fontSize = 32.sp)
    }
}
