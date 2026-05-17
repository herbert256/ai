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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-only "View" sibling of [ReportsViewerScreen] (no
 * initialSection — the per-agent response list path). Reached from
 * the 📊 Reports tile on Report - view; the management-heavy viewer
 * stays available from Report - manage.
 *
 * Layout: per-agent response stack. Each agent renders as a card with
 * a large circular emoji on the left (the agent's own report icon
 * when present, 🤖 fallback) and a header carrying provider + short
 * model. Body below is the agent's response (or the matching AGENT
 * TRANSLATE row when [language] is non-blank) rendered through the
 * shared markdown pipeline.
 */
@Composable
fun ReportsViewScreen(
    reportId: String,
    language: String?,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current

    data class Loaded(
        val report: Report?,
        val translatedByAgentId: Map<String, String>
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap()),
        reportId, language
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val translatedMap = if (!language.isNullOrEmpty()) {
                SecondaryResultStorage
                    .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                    .filter {
                        it.translateSourceKind == "AGENT" &&
                            it.targetLanguage == language &&
                            !it.content.isNullOrBlank() &&
                            !it.translateSourceTargetId.isNullOrBlank()
                    }
                    .associate { it.translateSourceTargetId!! to it.content!! }
            } else emptyMap()
            Loaded(rep, translatedMap)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val translatedByAgentId = loaded.translatedByAgentId

    val agents = report?.agents?.filter {
        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
    }.orEmpty()
    val pagerState = rememberPagerState(initialPage = 0) { agents.size.coerceAtLeast(1) }
    val activeAgent = agents.getOrNull(pagerState.currentPage)

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Model reports",
            subject = activeAgent?.let { shortModelName(it.model) },
            helpTopic = "reports_view",
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
        if (agents.isEmpty()) {
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
                    Text(text = "📊", fontSize = 40.sp)
                    Text(
                        text = "No successful agent responses",
                        color = AppColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Generate the report (or fix failed agents) in Report - manage.",
                        color = AppColors.TextTertiary, fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            return@Column
        }
        // Counter "X / Y" — centred above the swipe-able card.
        Text(
            text = "${pagerState.currentPage + 1} / ${agents.size}",
            color = AppColors.TextTertiary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val agent = agents[page]
            AgentResponseCard(
                agent = agent,
                overrideBody = translatedByAgentId[agent.agentId]
            )
        }
    }
}

@Composable
private fun AgentResponseCard(
    agent: ReportAgent,
    overrideBody: String?
) {
    val body = overrideBody ?: agent.responseBody.orEmpty()
    val emoji = agent.icon?.takeIf { it.isNotBlank() } ?: "🤖"
    Column(
        modifier = Modifier.fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Blue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 10.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(AppColors.Blue.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 26.sp)
        }
        if (body.isBlank()) {
            Text(text = "(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
        } else {
            ContentWithThinkSections(analysis = body)
        }
    }
}
