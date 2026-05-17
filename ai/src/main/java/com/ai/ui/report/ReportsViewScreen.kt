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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
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

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "reports_view",
            title = "Reports - view",
            onBackClick = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "📊", fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = "Reports",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Blue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!language.isNullOrEmpty()) {
                Text(
                    text = "🌍 $language",
                    fontSize = 12.sp, color = AppColors.Orange,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.SurfaceDark)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (report == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("Loading…", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }
        val agents = report.agents.filter {
            it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            items(agents) { agent ->
                AgentResponseCard(
                    agent = agent,
                    overrideBody = translatedByAgentId[agent.agentId],
                    language = language?.takeIf { it.isNotEmpty() }
                )
            }
        }
    }
}

@Composable
private fun AgentResponseCard(
    agent: ReportAgent,
    overrideBody: String?,
    language: String?
) {
    val provider = AppService.findById(agent.provider)?.id ?: agent.provider
    val body = overrideBody ?: agent.responseBody.orEmpty()
    val emoji = agent.icon?.takeIf { it.isNotBlank() } ?: "🤖"
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Blue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(AppColors.Blue.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 26.sp)
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = provider,
                color = AppColors.Blue,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = shortModelName(agent.model),
                color = AppColors.TextTertiary,
                fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (language != null) {
                Text(
                    text = "🌍 $language",
                    color = AppColors.Orange,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.SurfaceDark)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.size(2.dp))
            if (body.isBlank()) {
                Text(text = "(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
            } else {
                ContentWithThinkSections(analysis = body)
            }
        }
    }
}
