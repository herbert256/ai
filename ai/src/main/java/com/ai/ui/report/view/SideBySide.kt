package com.ai.ui.report.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.barTitle
import com.ai.data.ReportAgent
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStatus
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.shared.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Side-by-side A/B answer compare (F55). Two independently scrolling
 * columns, each with its own model picker — evaluating two long answers
 * against each other used to mean swiping back and forth in the pager
 * and holding one response in memory. Reached from the "A/B" tile on
 * Report - view when the report has 2+ successful answers.
 *
 * Deliberately minimal: no diffing, no scoring — the Tournament /
 * Compare analyses do machine judgment; this is for the user's own
 * eyes.
 */
@Composable
fun SideBySideViewScreen(
    reportId: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val reportDataVersion by ReportDataVersion.versionFor(reportId).collectAsState()
    val reportState = produceState<Report?>(initialValue = null, reportId, reportDataVersion) {
        value = withContext(Dispatchers.IO) { com.ai.ui.report.view.helpers.ViewReportCache.get(context, reportId) }
    }
    val report = reportState.value
    val answered = remember(report) {
        report?.agents?.filter {
            it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
        } ?: emptyList()
    }
    // Default: first two answers. Saveable so a Help hop keeps the picks.
    var leftId by rememberSaveable(reportId) { mutableStateOf<String?>(null) }
    var rightId by rememberSaveable(reportId) { mutableStateOf<String?>(null) }
    val left = answered.firstOrNull { it.agentId == leftId } ?: answered.getOrNull(0)
    val right = answered.firstOrNull { it.agentId == rightId } ?: answered.getOrNull(1)

    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 12.dp, end = 12.dp, top = 16.dp)
    ) {
        ViewTitleBar(
            reportTitle = report?.barTitle,
            screenTitle = "A/B compare",
            subject = null,
            helpTopic = "ab_compare_view",
            onOpenManage = null,
            onBack = onBack
        )
        if (answered.size < 2) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Needs at least two successful answers.", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
            return@Column
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideBySidePane(
                agent = left, agents = answered, otherId = right?.agentId,
                onPick = { leftId = it }, modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.width(1.dp).fillMaxSize()
                    .background(AppColors.TextDisabled.copy(alpha = 0.4f))
            )
            SideBySidePane(
                agent = right, agents = answered, otherId = left?.agentId,
                onPick = { rightId = it }, modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun paneLabel(agent: ReportAgent?): String =
    agent?.let { com.ai.ui.shared.modelLabel(com.ai.data.AppService.findById(it.provider)?.id ?: it.provider, it.model, separator = " / ") }
        ?: "(pick a model)"

/** One half: a tap-to-switch model header + the independently scrolling
 *  answer under it. [otherId] is greyed in the picker (already on the
 *  other side) but still selectable — comparing an answer with itself
 *  is pointless but harmless. */
@Composable
private fun SideBySidePane(
    agent: ReportAgent?,
    agents: List<ReportAgent>,
    otherId: String?,
    onPick: (String) -> Unit,
    modifier: Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Box {
            Text(
                "${paneLabel(agent)} ▾",
                color = AppColors.InfoAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { menuOpen = true }
                    .background(AppColors.CardBackground)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                agents.forEach { a ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                paneLabel(a), fontSize = 12.sp,
                                color = when (a.agentId) {
                                    agent?.agentId -> AppColors.InfoAccent
                                    otherId -> AppColors.TextTertiary
                                    else -> AppColors.TextPrimary
                                }
                            )
                        },
                        onClick = { menuOpen = false; onPick(a.agentId) }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(6.dp))
            if (agent?.responseBody != null) {
                ContentWithThinkSections(agent.responseBody!!)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
