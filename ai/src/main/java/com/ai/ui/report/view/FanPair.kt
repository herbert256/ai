package com.ai.ui.report.view
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
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
 * Dedicated View screen for fan-out pairs in one run
 * (metaPromptName). Reached from a responder-icon tap on
 * [IconsViewScreen]; the heavier Manage-flow [FanOutL3Screen]
 * keeps its job — this is the lightweight content-only sibling.
 *
 * Layout: full-width initiator bubble on top, full-width answerer
 * bubble below. HorizontalPager swipes through every pair in the
 * fan-out; the page lands initially on the pair the user tapped.
 * An "X / Y" counter sits between the title bar and the pager so
 * the user knows where they are in the run.
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
        val pairs: List<SecondaryResult>
    )

    val loadedState = produceState(
        initialValue = Loaded(null, emptyList()),
        reportId, metaPromptName
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val pairs = SecondaryResultStorage.listForReport(context, reportId).filter {
                it.fanOutSourceAgentId != null &&
                    it.metaPromptName == metaPromptName &&
                    !it.content.isNullOrBlank()
            }
            Loaded(rep, pairs)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val pairs = loaded.pairs

    val pagerState = rememberPagerState(initialPage = 0) { pairs.size.coerceAtLeast(1) }
    // Once the pair list has loaded, jump the pager to the tapped
    // pair. Keyed on (pairs, sourceAgentId, answererProviderId,
    // answererModel) so a different deep-link recomputes the target.
    LaunchedEffect(pairs, sourceAgentId, answererProviderId, answererModel) {
        if (pairs.isNotEmpty()) {
            val idx = pairs.indexOfFirst {
                it.fanOutSourceAgentId == sourceAgentId &&
                    it.providerId == answererProviderId &&
                    it.model == answererModel
            }
            if (idx >= 0 && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
    }

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
            onBack = onBack
        )

        if (pairs.isEmpty()) {
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
                        text = if (report == null) "Loading…" else "No pairs in this fan-out",
                        color = AppColors.TextPrimary, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            return@Column
        }

        // Pair counter — directly below the green subject so the
        // user reads "metaPromptName · 3 / 12" as one band.
        Text(
            text = "${pagerState.currentPage + 1} / ${pairs.size}",
            color = AppColors.TextTertiary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp)
        )

        com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
            pagerState = pagerState,
            noMoreLabel = "No more pairs",
            modifier = Modifier.fillMaxSize()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val pair = pairs[page]
                val initiator = report?.agents?.firstOrNull { it.agentId == pair.fanOutSourceAgentId }
                val initiatorLabel = initiator?.let {
                    val prov = AppService.findById(it.provider)?.id ?: it.provider
                    "$prov / ${shortModelName(it.model)}"
                } ?: "Initiator"
                val initiatorBody = initiator?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
                    ?.responseBody
                    ?.takeIf { !it.isNullOrBlank() }
                    ?: "(initiator response no longer available)"
                val answererProvDisplay =
                    AppService.findById(pair.providerId)?.id ?: pair.providerId
                val answererLabel = "$answererProvDisplay / ${shortModelName(pair.model)}"

                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PairBubble(
                        label = initiatorLabel,
                        body = initiatorBody,
                        color = AppColors.SurfaceDark,
                        borderColor = AppColors.BorderUnfocused
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    PairBubble(
                        label = answererLabel,
                        body = pair.content.orEmpty(),
                        color = AppColors.IndigoHighlight,
                        borderColor = AppColors.Indigo
                    )
                }
            }
        }
    }
}

/** Full-width bubble — initiator and answerer both use the entire
 *  screen width on this page (no side padding to differentiate);
 *  the colour and border tell the two apart. Long bodies start
 *  collapsed with a Read more toggle. */
@Composable
private fun PairBubble(
    label: String,
    body: String,
    color: Color,
    borderColor: Color
) {
    val collapseThreshold = 600
    val previewChars = 360
    val isLong = body.length > collapseThreshold
    var isExpanded by remember(body) { mutableStateOf(!isLong) }
    val shown = if (isLong && !isExpanded) {
        body.take(previewChars).trimEnd() + "…"
    } else body

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
