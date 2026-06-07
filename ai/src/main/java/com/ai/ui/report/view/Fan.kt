package com.ai.ui.report.view

import com.ai.data.barTitle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.viewBodySwipe
import com.ai.ui.report.view.helpers.rememberWrapPager
import com.ai.ui.report.view.helpers.wrapTo
import com.ai.ui.report.view.helpers.wrapCenterPage
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
    /** When set, the initiator pager opens on the page for this agent
     *  (the model the user was reading on "Model reports"). Falls back
     *  to page 0 when that agent isn't an initiator of this run. */
    initialInitiatorAgentId: String? = null,
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

    val reportDataVersion by ReportDataVersion.version.collectAsState()
    val secondaryDataVersion by SecondaryDataVersion.version.collectAsState()
    val loadedState = produceState(
        initialValue = Loaded(null, emptyList(), emptyList()),
        currentReportId, currentPromptName, reportDataVersion, secondaryDataVersion
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
                        it.translateSourceKind == "AGENT" ||
                        it.translateSourceKind == "AGENT_TITLE" ||
                        it.translateSourceKind == "FANOUT_TITLE") &&
                    !it.content.isNullOrBlank()
            }
            Loaded(rep, pairs, translates)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val pairs = loaded.pairs
    val translates = loaded.translates

    // In-screen language cycling. The `language` parameter seeds the
    // initial selection; tapping the flag on the initiator card cycles
    // to the next available translation, wrapping past the last back to
    // Original ("") — no edge stop. Only the languages this run actually
    // carries translations for are offered (plus Original).
    val availableLanguages = remember(translates) {
        val seen = linkedSetOf("")
        translates.forEach { it.targetLanguage?.takeIf { l -> l.isNotBlank() }?.let { l -> seen += l } }
        seen.toList()
    }
    val initialLangIdx = remember(availableLanguages, language) {
        availableLanguages.indexOf(language ?: "").coerceAtLeast(0)
    }
    var langIdx by rememberSaveable(currentReportId, currentPromptName) { mutableStateOf(initialLangIdx) }
    // langIdx is initialised before `translates` has loaded, so its seed sees
    // only Original ("") and locks to 0 even when a launch language was asked
    // for. Re-seek once the real language set arrives (once per report/prompt);
    // manual flag taps afterwards stand.
    var seededLang by rememberSaveable(currentReportId, currentPromptName) { mutableStateOf(false) }
    LaunchedEffect(availableLanguages) {
        if (!seededLang && availableLanguages.size > 1) {
            langIdx = availableLanguages.indexOf(language ?: "").coerceAtLeast(0)
            seededLang = true
        }
    }
    val activeLanguage = availableLanguages.getOrNull(langIdx.coerceIn(0, (availableLanguages.size - 1).coerceAtLeast(0))) ?: ""
    val advanceLanguage: () -> Unit = {
        if (availableLanguages.size > 1) langIdx = (langIdx + 1) % availableLanguages.size
    }

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

    val initiatorPagerState = rememberWrapPager(initiatorIds.size, 0)
    // Land on the requested initiator (the model that was on screen in
    // "Model reports") once the run has loaded and the pager has its
    // real count. Keyed so it fires only when the target arrives.
    LaunchedEffect(initiatorIds, initialInitiatorAgentId) {
        if (initialInitiatorAgentId != null && initiatorIds.isNotEmpty()) {
            val idx = initiatorIds.indexOf(initialInitiatorAgentId)
            if (idx >= 0 && idx != initiatorPagerState.currentPage.wrapTo(initiatorIds.size)) {
                initiatorPagerState.scrollToPage(wrapCenterPage(initiatorIds.size, idx))
            }
        }
    }
    val activeInitiatorId: String? = initiatorIds.getOrNull(
        initiatorPagerState.currentPage.wrapTo(initiatorIds.size)
    )
    val activeInitiator: ReportAgent? = remember(report, activeInitiatorId) {
        report?.agents?.firstOrNull { it.agentId == activeInitiatorId }
    }
    val responders: List<SecondaryResult> = remember(pairsByInitiator, activeInitiatorId) {
        activeInitiatorId?.let { pairsByInitiator[it] }.orEmpty()
    }

    val responderPagerState = rememberWrapPager(responders.size, 0)
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
        if (responderPagerState.currentPage.wrapTo(responders.size) != targetIdx) {
            isAutoScrolling.value = true
            try {
                responderPagerState.scrollToPage(wrapCenterPage(responders.size, targetIdx))
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
                responders.getOrNull(idx.wrapTo(responders.size))?.let { r ->
                    preferredResponderKey = responderKeyOf(r)
                }
            }
    }
    val activeResponder = responders.getOrNull(responderPagerState.currentPage.wrapTo(responders.size))

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
    // ☝️ (one responder per page, swipe) vs ✋ (all responders to the
    // active initiator as collapsible cards). Keyed per report / prompt
    // so it resets when the fan-out run changes.
    var showAll by rememberSaveable(currentReportId, currentPromptName) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
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
            reportTitle = report?.barTitle,
            reportId = currentReportId,
            activeLanguage = activeLanguage,
            screenTitle = titleText,
            subject = null,
            helpTopic = "fan_out_view",
            onOpenManage = onOpenManageJump,
            onBack = onBack,
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction,
            oneOrAll = showAll,
            onToggleOneOrAll = { showAll = !showAll }
        )
        // Notes pinned to the whole fan-out run. The run key matches the
        // Manage side: runKey(reportId, metaPromptId) — the metaPromptId is
        // read off any loaded pair (they all share this run's prompt).
        pairs.firstOrNull()?.metaPromptId?.let { mid ->
            com.ai.ui.report.manage.ViewUserNotes(currentReportId, "FANOUT_RUN", com.ai.data.runKey(currentReportId, mid))
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
                        counter = if (initiatorIds.isEmpty()) "0 / 0"
                            else "${initiatorPagerState.currentPage.wrapTo(initiatorIds.size) + 1} / ${initiatorIds.size}",
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
                            val agentId = initiatorIds[page.wrapTo(initiatorIds.size)]
                            val agent = report.agents.firstOrNull { it.agentId == agentId }
                            val originalBody = agent?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
                                ?.responseBody?.takeIf { !it.isNullOrBlank() }
                            // When a translated language is active,
                            // surface the AGENT translate for this
                            // initiator. Falls back to the original
                            // body when no translate row exists for
                            // this (agent, language).
                            val translatedBody = if (activeLanguage.isNotEmpty()) {
                                translates.firstOrNull {
                                    it.translateSourceKind == "AGENT" &&
                                        it.translateSourceTargetId == agentId &&
                                        it.targetLanguage == activeLanguage
                                }?.content?.takeIf { it.isNotBlank() }
                            } else null
                            val body = translatedBody ?: originalBody
                                ?: "(initiator response no longer available)"
                            // Initiator's model-response title (orange,
                            // top of the card), swapped to the active
                            // language via its AGENT_TITLE translate.
                            val initiatorTitle = run {
                                val t = if (activeLanguage.isNotEmpty()) translates.firstOrNull {
                                    it.translateSourceKind == "AGENT_TITLE" &&
                                        it.translateSourceTargetId == agentId &&
                                        it.targetLanguage == activeLanguage
                                }?.content?.takeIf { it.isNotBlank() } else null
                                t ?: agent?.modelTitle?.takeIf { it.isNotBlank() }
                            }
                            // Language flag (top-right). Shown only when
                            // the run carries translations; tapping it
                            // cycles to the next language (wrapping).
                            val initiatorFlag = if (availableLanguages.size > 1) when {
                                activeLanguage.isBlank() -> report.languageIcon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.languageIcon
                                else -> com.ai.data.InternalPromptIconCache.get("translation_icon", activeLanguage) ?: com.ai.data.MetadataIconsHolder.current.world
                            } else null
                            FanOutBodyCard(
                                reportIcon = agent?.icon?.takeIf { it.isNotBlank() } ?: com.ai.ui.shared.LocalMetadataIcons.current.reportModelIcon,
                                body = body,
                                borderColor = AppColors.PrimaryAccent.copy(alpha = 0.35f),
                                overrideTitle = initiatorTitle,
                                languageIcon = initiatorFlag,
                                onLanguageClick = advanceLanguage
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
                            val agentId = initiatorIds[page.wrapTo(initiatorIds.size)]
                            val agent = report.agents.firstOrNull { it.agentId == agentId }
                            val translatedBody = if (activeLanguage.isNotEmpty()) {
                                translates.firstOrNull {
                                    it.translateSourceKind == "AGENT" &&
                                        it.translateSourceTargetId == agentId &&
                                        it.targetLanguage == activeLanguage
                                }?.content?.takeIf { it.isNotBlank() }
                            } else null
                            val originalBody = agent?.takeIf { it.reportStatus == ReportStatus.SUCCESS }
                                ?.responseBody?.takeIf { !it.isNullOrBlank() }
                            val previewBody = (translatedBody ?: originalBody)
                                ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                                ?: "(initiator response no longer available)"
                            CollapsedInitiatorRow(
                                icon = agent?.icon?.takeIf { it.isNotBlank() } ?: com.ai.ui.shared.LocalMetadataIcons.current.reportModelIcon,
                                preview = previewBody,
                                onToggle = { initiatorExpanded = true }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                // ☝️ mode names the single active responder; ✋ mode
                // lists every responder so the per-responder counter
                // has no meaning there.
                if (!showAll) {
                    CounterRow(
                        counter = if (responders.isEmpty()) "0 / 0"
                            else "${responderPagerState.currentPage.wrapTo(responders.size) + 1} / ${responders.size}",
                        modelLabel = activeResponder?.let { shortModelName(it.model) }.orEmpty(),
                        providerService = activeResponder?.let { com.ai.data.AppService.findById(it.providerId) },
                        modelId = activeResponder?.model.orEmpty()
                    )
                }
                // Resolve a responder pair's body, honouring the active
                // translation language (META translates keyed on pair.id).
                fun responderBody(pair: SecondaryResult): String {
                    val translated = if (activeLanguage.isNotEmpty()) {
                        translates.firstOrNull {
                            it.translateSourceKind == "META" &&
                                it.translateSourceTargetId == pair.id &&
                                it.targetLanguage == activeLanguage
                        }?.content?.takeIf { it.isNotBlank() }
                    } else null
                    return translated ?: pair.content.orEmpty()
                }
                // The fan-out pair's title (SecondaryResult.title) swapped
                // to the active language via its FANOUT_TITLE row (keyed on
                // pair.id, same as the META body but distinct kind).
                fun responderTitle(pair: SecondaryResult): String? {
                    val translated = if (activeLanguage.isNotEmpty()) {
                        translates.firstOrNull {
                            it.translateSourceKind == "FANOUT_TITLE" &&
                                it.translateSourceTargetId == pair.id &&
                                it.targetLanguage == activeLanguage
                        }?.content?.takeIf { it.isNotBlank() }
                    } else null
                    return translated ?: pair.title
                }
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
                } else if (!showAll) {
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
                            val pair = responders[page.wrapTo(responders.size)]
                            FanOutBodyCard(
                                reportIcon = pair.icon?.takeIf { it.isNotBlank() } ?: com.ai.ui.shared.LocalMetadataIcons.current.reportModelIcon,
                                body = responderBody(pair),
                                borderColor = AppColors.InfoAccent.copy(alpha = 0.35f),
                                overrideTitle = responderTitle(pair)
                            )
                        }
                    }
                } else {
                    // ✋ — every responder to the active initiator as a
                    // default-collapsed card (mirrors Model reports' ✋).
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = respCap)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        responders.forEach { pair ->
                            FanOutResponderCard(pair = pair, body = responderBody(pair), overrideTitle = responderTitle(pair))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
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
            color = AppColors.SuccessAccent,
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
            .border(1.dp, AppColors.PrimaryAccent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
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
    borderColor: androidx.compose.ui.graphics.Color,
    /** Response title shown in orange, centred at the top of the card.
     *  Null / blank → no title row. */
    overrideTitle: String? = null,
    /** When set, a small language flag is pinned to the card's top-right
     *  corner. Null / blank → no flag. */
    languageIcon: String? = null,
    /** When set, tapping the top-right language flag advances to the
     *  next language (wrapping). Null → the flag is a static indicator. */
    onLanguageClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Response title (orange) at the top of the card, centred.
            overrideTitle?.takeIf { it.isNotBlank() }?.let { t ->
                Text(
                    text = t,
                    color = AppColors.WarningAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
            Text(
                text = reportIcon?.takeIf { it.isNotBlank() } ?: com.ai.ui.shared.LocalMetadataIcons.current.reportIcon,
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
        // Language flag pinned to the card's top-right corner, outside
        // the scrolling column so it stays put as long bodies scroll.
        if (!languageIcon.isNullOrBlank()) {
            Text(
                text = languageIcon,
                fontSize = 24.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 8.dp)
                    .then(if (onLanguageClick != null) Modifier.clickable(onClick = onLanguageClick) else Modifier)
            )
        }
    }
}

/** ✋-mode card: one per responder of the active initiator, default
 *  collapsed. Collapsed shows the responder's fan-out icon + its
 *  generated title (or model name) on one line; tapping expands to add
 *  the model name and the full reply. Mirrors `ModelReportCard` on the
 *  Model reports screen but for a fan-out pair ([SecondaryResult]). */
@Composable
private fun FanOutResponderCard(pair: SecondaryResult, body: String, overrideTitle: String? = null) {
    var expanded by rememberSaveable(pair.id) { mutableStateOf(false) }
    val emoji = pair.icon?.takeIf { it.isNotBlank() } ?: com.ai.ui.shared.LocalMetadataIcons.current.reportModelIcon
    val title = overrideTitle?.takeIf { it.isNotBlank() }
        ?: pair.title?.takeIf { it.isNotBlank() }
        ?: shortModelName(pair.model)
    val provider = com.ai.data.AppService.findById(pair.providerId)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.InfoAccent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = emoji, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = shortModelName(pair.model),
                color = AppColors.SuccessAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.modelInfoViewClickable(provider, pair.model)
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (body.isBlank()) {
                Text(text = "(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
            } else {
                ContentWithThinkSections(analysis = body)
            }
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
            Text(text = com.ai.data.MetadataIconsHolder.current.cyclone, fontSize = 40.sp)
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
