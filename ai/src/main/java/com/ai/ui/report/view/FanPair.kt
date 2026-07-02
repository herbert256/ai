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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import androidx.compose.runtime.saveable.rememberSaveable
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.rememberWrapPager
import com.ai.ui.report.view.helpers.viewBodySwipe
import com.ai.ui.report.view.helpers.wrapTo
import com.ai.ui.report.view.helpers.wrapCenterPage
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
    // Title-bar / body swipe to the prev/next report that carries a
    // fan-out of the SAME meta prompt. `currentReportId` shadows the
    // prop so the hop swaps the pair list in place; the deep-link
    // LaunchedEffect below then lands the pager on the same pair when
    // the new report has it, else on the first pair (indexOfFirst
    // misses → coerceAtLeast(0)).
    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current
    val pairFilter = ViewSwipeFilter.HasMeta(metaPromptName, requireFanOut = true)
    val onSwipePrevAction: () -> Boolean = {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev, pairFilter)
        if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
    }
    val onSwipeNextAction: () -> Boolean = {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next, pairFilter)
        if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
    }

    data class Loaded(
        val report: Report?,
        val pairs: List<SecondaryResult>
    )

    val reportDataVersion by ReportDataVersion.versionFor(currentReportId).collectAsState()
    val secondaryDataVersion by SecondaryDataVersion.versionFor(currentReportId, SecondaryKind.META).collectAsState()
    val loadedState = produceState(
        initialValue = Loaded(null, emptyList()),
        currentReportId, metaPromptName, reportDataVersion, secondaryDataVersion
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = com.ai.ui.report.view.helpers.ViewReportCache.get(context, currentReportId)
            val pairs = SecondaryResultStorage.listForReport(context, currentReportId).filter {
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

    val pagerState = rememberWrapPager(pairs.size, 0)
    // Once the pair list has loaded, jump the pager to the tapped pair —
    // always to the CENTRED page (the pager was created while pairs was
    // empty, so it sits on the uncentred page 0; an "already there" guard
    // would leave index-0 pairs uncentred and a backward swipe would hit
    // the span edge, "No more pairs").
    // Centre ONCE per (report, deep-link), not on every pairs reload:
    // produceState re-reads on every Report/SecondaryDataVersion bump —
    // each pair completion, fan-meta title/icon write and translation save
    // — and an unguarded scroll yanked the pager back to the tapped pair
    // every few seconds while a batch was landing (Fan.kt's initCenteredFor
    // is the same guard for its initiator pager). The loaded-report check
    // keeps the latch from firing against the PREVIOUS report's stale list
    // right after a title-bar report swipe.
    var centeredFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairs, currentReportId, sourceAgentId, answererProviderId, answererModel) {
        val centerKey = "$currentReportId|$sourceAgentId|$answererProviderId|$answererModel"
        if (centeredFor != centerKey && pairs.isNotEmpty() && report?.id == currentReportId) {
            val idx = pairs.indexOfFirst {
                it.fanOutSourceAgentId == sourceAgentId &&
                    it.providerId == answererProviderId &&
                    it.model == answererModel
            }.coerceAtLeast(0)
            pagerState.scrollToPage(wrapCenterPage(pairs.size, idx))
            centeredFor = centerKey
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .viewBodySwipe(currentReportId, onPrev = { onSwipePrevAction() }, onNext = { onSwipeNextAction() })
    ) {
        ViewTitleBar(
            reportTitle = report?.barTitle,
            screenTitle = "Fan-out pair",
            subject = metaPromptName.takeIf { it.isNotBlank() },
            helpTopic = "fan_out_pair_view",
            onBack = onBack,
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction
        )
        pairs.getOrNull(pagerState.currentPage.wrapTo(pairs.size))?.let {
            com.ai.ui.report.manage.ViewUserNotes(currentReportId, "SECONDARY", it.id)
        }

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
                    Text(text = com.ai.data.MetadataIconsHolder.current.cyclone, fontSize = 40.sp)
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
            text = if (pairs.isEmpty()) "0 / 0"
                else "${pagerState.currentPage.wrapTo(pairs.size) + 1} / ${pairs.size}",
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
                val pair = pairs[page.wrapTo(pairs.size)]
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
                        color = AppColors.SelectionHighlight,
                        borderColor = AppColors.SecondaryAccent
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
        val window = body.take(previewChars)
        val lastBreak = window.lastIndexOf('\n')
        val cut = if (lastBreak >= previewChars / 2) window.substring(0, lastBreak) else window
        cut.trimEnd() + "…"
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
                color = AppColors.InfoAccent,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { isExpanded = !isExpanded }
                    .padding(top = 4.dp)
            )
        }
    }
}
