package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
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
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.modelInfoViewClickable
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-focused "View" variant of the Fan-out drill-in, paired with
 * the existing management-heavy [FanOutScreen]. Reached from the
 * "Report - view" tile for a fan-out run.
 *
 * Layout copied from [ReportsViewScreen] (Model reports): two
 * vertically-stacked pagers, each preceded by an X/Y counter and a
 * green model-name subject line.
 *
 *  - Top pager  → one page per Initiator. Body = the initiator
 *    model's original report response. Swipe → previous / next
 *    initiator.
 *  - Bottom pager → one page per Responder under the active
 *    initiator. Body = the fan-out pair's reply. Swipe → previous /
 *    next responder for the current initiator. When the user swipes
 *    the top pager to a different initiator the bottom pager resets
 *    to page 0.
 *
 * No language flag on the top card (the user asked for that to be
 * absent here — different from Model reports). Translations on the
 * responder body are still picked up when [language] is non-blank.
 */
@Composable
fun FanOutViewScreen(
    reportId: String,
    metaPromptName: String,
    language: String?,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current

    data class Loaded(
        val report: Report?,
        val pairs: List<SecondaryResult>,
        val translates: List<SecondaryResult>
    )

    val loadedState = produceState(
        initialValue = Loaded(null, emptyList(), emptyList()),
        reportId, metaPromptName
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val allSecondary = SecondaryResultStorage.listForReport(context, reportId)
            val pairs = allSecondary.filter {
                it.fanOutSourceAgentId != null &&
                    it.metaPromptName == metaPromptName &&
                    !it.content.isNullOrBlank()
            }
            val translates = allSecondary.filter {
                it.kind == SecondaryKind.TRANSLATE &&
                    it.translateSourceKind == "META" &&
                    !it.content.isNullOrBlank()
            }
            Loaded(rep, pairs, translates)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val pairs = loaded.pairs
    val translates = loaded.translates

    // Group pairs by initiator (sourceAgentId), preserving the order
    // the runtime wrote them so the user reads through Initiators in
    // their natural sequence.
    val pairsByInitiator: LinkedHashMap<String, List<SecondaryResult>> = remember(pairs) {
        val map = LinkedHashMap<String, MutableList<SecondaryResult>>()
        for (p in pairs) {
            val src = p.fanOutSourceAgentId ?: continue
            map.getOrPut(src) { mutableListOf() }.add(p)
        }
        // Map's values are MutableLists; freeze to immutable List for
        // the consumer signature.
        LinkedHashMap(map.mapValues { (_, v) -> v.toList() })
    }
    val initiatorIds: List<String> = remember(pairsByInitiator) { pairsByInitiator.keys.toList() }

    val initiatorPagerState = rememberPagerState(initialPage = 0) {
        initiatorIds.size.coerceAtLeast(1)
    }
    val activeInitiatorId: String? = initiatorIds.getOrNull(
        initiatorPagerState.currentPage.coerceIn(0, (initiatorIds.size - 1).coerceAtLeast(0))
    )
    val activeInitiator: ReportAgent? = remember(report, activeInitiatorId) {
        report?.agents?.firstOrNull { it.agentId == activeInitiatorId }
    }
    val responders: List<SecondaryResult> = remember(pairsByInitiator, activeInitiatorId) {
        activeInitiatorId?.let { pairsByInitiator[it] }.orEmpty()
    }

    val responderPagerState = rememberPagerState(initialPage = 0) {
        responders.size.coerceAtLeast(1)
    }
    // When the user swipes the top pager to a new initiator, snap the
    // bottom pager back to its first responder. Without this the
    // responder pager would keep an index that no longer maps to the
    // new initiator's smaller / different responder list.
    LaunchedEffect(activeInitiatorId) {
        if (responderPagerState.currentPage != 0) {
            responderPagerState.scrollToPage(0)
        }
    }
    val activeResponder = responders.getOrNull(responderPagerState.currentPage)

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // Manage's fan-out lives behind a multi-step picker chain
        // with no clean single-step entry — 🔧 falls back to the
        // main Report - manage screen here.
        val navToManageMain = com.ai.ui.shared.LocalNavigateToCurrentReport.current
        val titleText = "Fan-out" + metaPromptName.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = titleText,
            subject = null,
            helpTopic = "fan_out_view",
            onOpenManage = navToManageMain,
            onBack = onBack
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
        if (initiatorIds.isEmpty()) {
            EmptyFanOutState()
            return@Column
        }

        // Per-card icon = the specific agent's icon. Initiator pages
        // use the initiator's agent.icon; responder pages further
        // down use the responder's matching report-agent.icon.
        // Falls back to 🤖 while the per-agent icon-gen is pending.

        // Initiator collapse toggle. When false, the Initiator
        // section shrinks to a compact one-row preview (icon +
        // first line of the response) and the Responder pager
        // expands to fill the freed space. Default expanded; user
        // can collapse to focus on the responses they're reading.
        var initiatorExpanded by rememberSaveable(activeInitiatorId) { mutableStateOf(true) }

        // ───── Both sections wrapped in BoxWithConstraints ─────
        // The 1/3 vs 2/3 split only applies when content is large;
        // small content makes the body card wrap to its actual
        // height and the sections sit close together at the top.
        // heightIn(max = cap) on each pager caps the body at the
        // fractional split for that section.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            val totalH = maxHeight
            // Reserve approximate chrome (counter rows + spacers +
            // padding) so the per-pager caps don't push the second
            // section off-screen when both sections are at cap.
            val chrome = 96.dp
            val available = (totalH - chrome).coerceAtLeast(0.dp)
            val initCap = if (initiatorExpanded) available / 3 else 0.dp
            val respCap = if (initiatorExpanded) available * 2 / 3 else available

            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))
                if (initiatorExpanded) {
                    CounterRow(
                        counter = "${initiatorPagerState.currentPage + 1} / ${initiatorIds.size}",
                        modelLabel = activeInitiator?.let { shortModelName(it.model) }.orEmpty(),
                        providerService = activeInitiator?.let { com.ai.data.AppService.findById(it.provider) },
                        modelId = activeInitiator?.model.orEmpty(),
                        onToggle = { initiatorExpanded = false },
                        toggleGlyph = "▲"
                    )
                    com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
                        pagerState = initiatorPagerState,
                        noMoreLabel = "No more initiators",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        HorizontalPager(
                            state = initiatorPagerState,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = initCap)
                                .wrapContentHeight()
                        ) { page ->
                            val agentId = initiatorIds[page]
                            val agent = report.agents.firstOrNull { it.agentId == agentId }
                            val body = agent?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
                                ?.responseBody?.takeIf { !it.isNullOrBlank() }
                                ?: "(initiator response no longer available)"
                            FanOutBodyCard(
                                reportIcon = agent?.icon?.takeIf { it.isNotBlank() } ?: "🤖",
                                body = body,
                                borderColor = AppColors.Purple.copy(alpha = 0.35f)
                            )
                        }
                    }
                } else {
                    val agent = activeInitiatorId?.let { aid -> report.agents.firstOrNull { it.agentId == aid } }
                    val previewBody = agent?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
                        ?.responseBody?.takeIf { !it.isNullOrBlank() }
                        ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                        ?: "(initiator response no longer available)"
                    CollapsedInitiatorRow(
                        icon = agent?.icon?.takeIf { it.isNotBlank() } ?: "🤖",
                        preview = previewBody,
                        onToggle = { initiatorExpanded = true }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                CounterRow(
                    counter = if (responders.isEmpty()) "0 / 0"
                        else "${responderPagerState.currentPage + 1} / ${responders.size}",
                    modelLabel = activeResponder?.let { shortModelName(it.model) }.orEmpty(),
                    providerService = activeResponder?.let { com.ai.data.AppService.findById(it.providerId) },
                    modelId = activeResponder?.model.orEmpty()
                )
                if (responders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No responders for this initiator yet",
                            color = AppColors.TextTertiary, fontSize = 13.sp
                        )
                    }
                } else {
                    com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
                        pagerState = responderPagerState,
                        noMoreLabel = "No more responders",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        HorizontalPager(
                            state = responderPagerState,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = respCap)
                                .wrapContentHeight()
                        ) { page ->
                            val pair = responders[page]
                            val translated = if (!language.isNullOrEmpty()) {
                                translates.firstOrNull {
                                    it.translateSourceTargetId == pair.id &&
                                        it.targetLanguage == language
                                }?.content?.takeIf { it.isNotBlank() }
                            } else null
                            val body = translated ?: pair.content.orEmpty()
                            val responderAgent = report.agents.firstOrNull {
                                it.provider == pair.providerId && it.model == pair.model
                            }
                            FanOutBodyCard(
                                reportIcon = responderAgent?.icon?.takeIf { it.isNotBlank() } ?: "🤖",
                                body = body,
                                borderColor = AppColors.Blue.copy(alpha = 0.35f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Single-row header above each fan-out card: the green model name
 *  is left-aligned; the X / Y pager counter is right-aligned on
 *  the same row. The green model name is tappable → View Model Info
 *  when [providerService] is non-null. When [onToggle] is non-null
 *  an extra glyph (▲ / ▼) sits to the right of the counter and
 *  toggles the section's collapsed state — used by the Initiator
 *  section here; the Responder section omits it. */
@Composable
private fun CounterRow(
    counter: String,
    modelLabel: String,
    providerService: com.ai.data.AppService? = null,
    modelId: String = "",
    onToggle: (() -> Unit)? = null,
    toggleGlyph: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = modelLabel,
            color = AppColors.Green,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
                .modelInfoViewClickable(providerService, modelId)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = counter,
            color = AppColors.TextTertiary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (onToggle != null && toggleGlyph.isNotBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = toggleGlyph,
                color = AppColors.TextSecondary, fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onToggle() }
                    .padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
            )
        }
    }
}

/** Compact one-row preview shown in place of the Initiator card
 *  when the user collapses it. The icon shrinks; the first non-
 *  blank line of the initiator's body fills the available width
 *  ellipsised; the ▼ glyph on the right re-expands the card. */
@Composable
private fun CollapsedInitiatorRow(icon: String, preview: String, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Purple.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = preview,
            color = AppColors.TextSecondary, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "▼",
            color = AppColors.TextSecondary, fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Shared body card for both initiator and responder pages. Report
 *  icon centred at the top, markdown body below in a vertically-
 *  scrolling column. Border colour distinguishes the two roles
 *  (purple for initiator, blue for responder). No language flag —
 *  the user asked for that to stay off this screen. */
@Composable
private fun FanOutBodyCard(
    reportIcon: String?,
    body: String,
    borderColor: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = reportIcon?.takeIf { it.isNotBlank() } ?: "📄",
            fontSize = 44.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 0.dp, bottom = 2.dp)
        )
        if (body.isBlank()) {
            Text(text = "(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
        } else {
            ContentWithThinkSections(analysis = body)
        }
    }
}

/** Friendly empty-state card shown when the fan-out run has been
 *  launched but no pair has produced content yet. */
@Composable
private fun EmptyFanOutState() {
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
                text = "No fan-out replies yet",
                color = AppColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Generate them from Report - manage. " +
                    "This screen will fill in as each (Initiator × Responder) pair completes.",
                color = AppColors.TextTertiary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
