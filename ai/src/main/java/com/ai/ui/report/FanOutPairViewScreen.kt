package com.ai.ui.report

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.ai.data.Report
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
 * Dedicated View screen for a single fan-out pair (one initiator
 * agent × one answerer agent under a specific metaPromptName).
 * Reached from a responder-icon tap on [IconsViewScreen]. The
 * heavier Manage-flow [FanOutL3Screen] keeps its job — this is a
 * lightweight content-only sibling that matches the rest of the
 * View family's title-bar pattern.
 */
@Composable
fun FanOutPairViewScreen(
    reportId: String,
    metaPromptName: String,
    sourceAgentId: String,
    answererProviderId: String,
    answererModel: String,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current

    data class Loaded(
        val report: Report?,
        val pair: SecondaryResult?
    )

    val loadedState = produceState(
        initialValue = Loaded(null, null),
        reportId, metaPromptName, sourceAgentId, answererProviderId, answererModel
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val match = SecondaryResultStorage.listForReport(context, reportId).firstOrNull {
                it.fanOutSourceAgentId == sourceAgentId &&
                    it.metaPromptName == metaPromptName &&
                    it.providerId == answererProviderId &&
                    it.model == answererModel &&
                    !it.content.isNullOrBlank()
            }
            Loaded(rep, match)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val pair = loaded.pair

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Fan-out pair",
            subject = metaPromptName.takeIf { it.isNotBlank() },
            helpTopic = "fan_out_pair_view",
            onBack = onBack,
            onLogoClick = onBack
        )

        if (pair == null) {
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
                    Text(text = "🌀", fontSize = 40.sp)
                    Text(
                        text = if (report == null) "Loading…" else "Pair not found",
                        color = AppColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            return@Column
        }

        val initiator = report?.agents?.firstOrNull { it.agentId == sourceAgentId }
        val initiatorLabel = initiator?.let {
            val prov = AppService.findById(it.provider)?.id ?: it.provider
            "$prov / ${shortModelName(it.model)}"
        } ?: "Initiator"
        val initiatorBody = initiator?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
            ?.responseBody
            ?.takeIf { !it.isNullOrBlank() }
            ?: "(initiator response no longer available)"

        val answererProvDisplay = AppService.findById(answererProviderId)?.id ?: answererProviderId
        val answererLabel = "$answererProvDisplay / ${shortModelName(answererModel)}"

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PairBubble(
                label = initiatorLabel,
                body = initiatorBody,
                color = AppColors.SurfaceDark,
                borderColor = AppColors.BorderUnfocused,
                isAssistant = false
            )
            Spacer(modifier = Modifier.height(4.dp))
            PairBubble(
                label = answererLabel,
                body = pair.content.orEmpty(),
                color = AppColors.IndigoHighlight,
                borderColor = AppColors.Indigo,
                isAssistant = true
            )
        }
    }
}

/** Chat-style bubble — mirrors the bubble look used by
 *  [FanOutViewScreen] but standalone (no shared expansion map; this
 *  screen only ever shows two bubbles so per-bubble local state is
 *  fine). Long bodies start collapsed with a Read more toggle. */
@Composable
private fun PairBubble(
    label: String,
    body: String,
    color: Color,
    borderColor: Color,
    isAssistant: Boolean
) {
    val collapseThreshold = 600
    val previewChars = 360
    val isLong = body.length > collapseThreshold
    var isExpanded by remember(body) { mutableStateOf(!isLong) }
    val shown = if (isLong && !isExpanded) {
        body.take(previewChars).trimEnd() + "…"
    } else body

    val sidePad = if (isAssistant) PaddingValues(start = 20.dp) else PaddingValues(end = 20.dp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(sidePad),
        horizontalArrangement = if (isAssistant) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                color = AppColors.TextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            ContentWithThinkSections(analysis = shown)
            if (isLong) {
                Text(
                    text = if (isExpanded) "Show less" else "Read more",
                    color = AppColors.Blue,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { isExpanded = !isExpanded }
                        .padding(top = 4.dp)
                )
            }
        }
    }
}
