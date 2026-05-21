package com.ai.ui.report.view
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

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
import androidx.compose.runtime.snapshotFlow
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
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.viewBodySwipe
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
    // Title-bar swipe targets — `currentReportId` + `currentPromptName`
    // shadow the props so a swipe can hot-swap the fan-out run in
    // place. The fan-out screen identifies itself by (reportId,
    // metaPromptName) — no resultId.
    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    var currentPromptName by rememberSaveable(metaPromptName) { mutableStateOf(metaPromptName) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current

    data class Loaded(
        val report: Report?,
        val pairs: List<SecondaryResult>,
        val translates: List<SecondaryResult>
    )

    val loadedState = produceState(
        initialValue = Loaded(null, emptyList(), emptyList()),
        currentReportId, currentPromptName
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = com.ai.ui.report.view.helpers.ViewReportCache.get(context, currentReportId)
            val allSecondary = SecondaryResultStorage.listForReport(context, currentReportId)
            val pairs = allSecondary.filter {
                it.fanOutSourceAgentId != null &&
                    it.metaPromptName == currentPromptName &&
                    !it.content.isNullOrBlank()
            }
            // Pull BOTH META and AGENT translates. META rows are
            // keyed off pair.id and feed the responder body; AGENT
            // rows are keyed off the initiator's agentId and feed
            // the initiator body. The previous load only pulled
            // META so the top card always showed the original
            // language while the bottom card respected the user's
            // language picker.
            val translates = allSecondary.filter {
                it.kind == SecondaryKind.TRANSLATE &&
                    (it.translateSourceKind == "META" ||
                        it.translateSourceKind == "AGENT") &&
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
    // Track the active responder by (provider, model) — survives
    // initiator swipes so the bottom pager lands on the same
    // responder model in the new initiator's list. Falls back to
    // page 0 when the new initiator's responder set doesn't include
    // that model.
    val responderKeyOf: (SecondaryResult) -> String = { "${it.providerId}|${it.model}" }
    var preferredResponderKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Guards the latch effect below from clobbering the preferred key
    // mid-auto-scroll. Set before [scrollToPage] suspends, cleared
    // after it returns — the suspend covers the whole animation, so
    // every intermediate settledPage emission is skipped.
    val isAutoScrolling = remember { mutableStateOf(false) }
    // On initiator change: jump to the matching responder for the
    // new initiator (or 0 if the preferred model isn't paired with
    // this initiator).
    LaunchedEffect(activeInitiatorId, responders) {
        if (responders.isEmpty()) return@LaunchedEffect
        val targetIdx = preferredResponderKey?.let { key ->
            responders.indexOfFirst { responderKeyOf(it) == key }.takeIf { it >= 0 }
        } ?: 0
        if (responderPagerState.currentPage != targetIdx) {
            isAutoScrolling.value = true
            try {
                responderPagerState.scrollToPage(targetIdx)
            } finally {
                isAutoScrolling.value = false
            }
        }
    }
    // Whenever the bottom pager settles on a responder via a real
    // USER swipe, latch its (provider, model) key. Keyed on
    // [responders] so the lambda's closure always reads the current
    // initiator's responder list (avoids the stale-capture bug
    // where the latch would write the wrong key after an initiator
    // swipe). The [isAutoScrolling] guard suppresses programmatic
    // scrollToPage updates from the effect above.
    LaunchedEffect(responderPagerState, responders) {
        snapshotFlow { responderPagerState.settledPage }
            .collect { idx ->
                if (isAutoScrolling.value) return@collect
                responders.getOrNull(idx)?.let { r ->
                    preferredResponderKey = responderKeyOf(r)
                }
            }
    }
    val activeResponder = responders.getOrNull(responderPagerState.currentPage)

    val fanOutFilter: ViewSwipeFilter? = currentPromptName.takeIf { it.isNotBlank() }?.let {
        ViewSwipeFilter.HasMeta(metaPromptName = it, requireFanOut = true)
    }
    val onSwipePrevAction: (() -> Boolean)? = fanOutFilter?.let { filter -> {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev, filter)
        if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
    } }
    val onSwipeNextAction: (() -> Boolean)? = fanOutFilter?.let { filter -> {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next, filter)
        if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
    } }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .viewBodySwipe(currentReportId, onPrev = { onSwipePrevAction?.invoke() }, onNext = { onSwipeNextAction?.invoke() })
    ) {
        // Manage's fan-out lives behind a multi-step picker chain
        // with no clean single-step entry — 🔧 dispatches
        // ManageJump.Main which closes the View overlay and reveals
        // the main "Manage report" screen. Going through
        // LocalOpenManage (not LocalNavigateToCurrentReport) matters
        // because the latter is overridden by ViewAiReportScreen's
        // sub-View block to "back to View grid" — using it here would
        // land the user back on the View grid instead of Manage.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.Main) }
        }
        val titleText = "Fan-out" + currentPromptName.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
        ViewTitleBar(
            reportTitle = report?.title,
            screenTitle = titleText,
            subject = null,
            helpTopic = "fan_out_view",
            onOpenManage = onOpenManageJump,
            onBack = onBack,
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction
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
                            val originalBody = agent?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
                                ?.responseBody?.takeIf { !it.isNullOrBlank() }
                            // When the user picked a translated
                            // language on the parent View screen,
                            // surface the AGENT translate for this
                            // initiator. Falls back to the original
                            // body when no translate row exists for
                            // this (agent, language).
                            val translatedBody = if (!language.isNullOrEmpty()) {
                                translates.firstOrNull {
                                    it.translateSourceKind == "AGENT" &&
                                        it.translateSourceTargetId == agentId &&
                                        it.targetLanguage == language
                                }?.content?.takeIf { it.isNotBlank() }
                            } else null
                            val body = translatedBody ?: originalBody
                                ?: "(initiator response no longer available)"
                            FanOutBodyCard(
                                reportIcon = agent?.icon?.takeIf { it.isNotBlank() } ?: "🤖",
                                body = body,
                                borderColor = AppColors.Purple.copy(alpha = 0.35f)
                            )
                        }
                    }
                } else {
                    // Collapsed mode: still a HorizontalPager so the
                    // user can swipe between initiators without
                    // expanding the card. Tap on the row expands; the
                    // pager handles drag gestures first so they don't
                    // race with the tap.
                    com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
                        pagerState = initiatorPagerState,
                        noMoreLabel = "No more initiators",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        HorizontalPager(
                            state = initiatorPagerState,
                            modifier = Modifier.fillMaxWidth().wrapContentHeight()
                        ) { page ->
                            val agentId = initiatorIds[page]
                            val agent = report.agents.firstOrNull { it.agentId == agentId }
                            val translatedBody = if (!language.isNullOrEmpty()) {
                                translates.firstOrNull {
                                    it.translateSourceKind == "AGENT" &&
                                        it.translateSourceTargetId == agentId &&
                                        it.targetLanguage == language
                                }?.content?.takeIf { it.isNotBlank() }
                            } else null
                            val originalBody = agent?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
                                ?.responseBody?.takeIf { !it.isNullOrBlank() }
                            val previewBody = (translatedBody ?: originalBody)
                                ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                                ?: "(initiator response no longer available)"
                            CollapsedInitiatorRow(
                                icon = agent?.icon?.takeIf { it.isNotBlank() } ?: "🤖",
                                preview = previewBody,
                                onToggle = { initiatorExpanded = true }
                            )
                        }
                    }
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
                            FanOutBodyCard(
                                reportIcon = pair.icon?.takeIf { it.isNotBlank() } ?: "🤖",
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
