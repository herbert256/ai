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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.modelInfoViewClickable
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
 * TRANSLATE row when a non-Original language is active) rendered
 * through the shared markdown pipeline.
 *
 * When the report carries translations the prompt card lives inside
 * its own HorizontalPager: swipe → previous / next language. The
 * active language is reflected in a small flag overlay on the card's
 * top-right and propagated back to [onBack] so the parent's
 * language picker adopts it.
 */
@Composable
fun ReportsViewScreen(
    reportId: String,
    /** Languages the report carries (Original = ""; non-empty entries
     *  are AGENT/PROMPT translate targetLanguages). The prompt-card
     *  pager renders one page per entry. Single-entry list means no
     *  language switching UI (the picker collapses to nothing). */
    availableLanguages: List<String> = listOf(""),
    /** Which language to land on. null / "" = Original. */
    initialLanguage: String? = null,
    initialAgentId: String? = null,
    /** Bubbled the currently-active language (null = Original) so
     *  the parent's picker can adopt it. Mirrors PromptViewScreen. */
    onBack: (activeLanguage: String?) -> Unit
) {
    val context = LocalContext.current
    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current

    // Normalise / dedupe — Original ("") is always present so the
    // pager has at least one page to render.
    val languages = remember(availableLanguages) {
        val seen = linkedSetOf<String>()
        seen += ""
        availableLanguages.forEach { seen += (it ?: "") }
        seen.toList()
    }
    val initialLangIdx = remember(languages, initialLanguage) {
        languages.indexOf(initialLanguage ?: "").coerceAtLeast(0)
    }
    val langPagerState = rememberPagerState(
        initialPage = initialLangIdx.coerceIn(0, languages.size - 1)
    ) { languages.size.coerceAtLeast(1) }
    val activeLanguage = if (languages.isEmpty()) ""
        else languages[langPagerState.currentPage.coerceIn(0, languages.size - 1)]
    val activeLangState = androidx.compose.runtime.rememberUpdatedState(activeLanguage)
    androidx.activity.compose.BackHandler {
        onBack(activeLangState.value.ifBlank { null })
    }

    data class Loaded(
        val report: Report?,
        // Per-language → per-agent body override. Original ("") not
        // included — the agent's own responseBody is used there.
        val translatedByLang: Map<String, Map<String, String>>,
        // Per-language → translated PROMPT body. Used by the prompt
        // card's language pager so the user sees the translated
        // prompt text on every non-Original page, not the original
        // prompt repeated.
        val translatedPromptByLang: Map<String, String>
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap(), emptyMap()),
        currentReportId
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, currentReportId)
            val translateRows = SecondaryResultStorage
                .listForReport(context, currentReportId, SecondaryKind.TRANSLATE)
                .filter {
                    !it.content.isNullOrBlank() &&
                        !it.targetLanguage.isNullOrBlank()
                }
            val byLang = translateRows
                .filter {
                    it.translateSourceKind == "AGENT" &&
                        !it.translateSourceTargetId.isNullOrBlank()
                }
                .groupBy { it.targetLanguage!! }
                .mapValues { (_, list) ->
                    list.associate { it.translateSourceTargetId!! to it.content!! }
                }
            val promptByLang = translateRows
                .filter { it.translateSourceKind == "PROMPT" }
                .associate { it.targetLanguage!! to it.content!! }
            Loaded(rep, byLang, promptByLang)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val translatedByAgentId = loaded.translatedByLang[activeLanguage].orEmpty()

    val agents = report?.agents?.filter {
        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
    }.orEmpty()
    val pagerState = rememberPagerState(initialPage = 0) { agents.size.coerceAtLeast(1) }
    // When called with an initialAgentId, jump to that agent's page
    // once the report has finished loading and the pager has its real
    // count. Keyed on (agents, initialAgentId) so the jump only fires
    // when the target arrives — not on every recomposition.
    androidx.compose.runtime.LaunchedEffect(agents, initialAgentId) {
        if (initialAgentId != null && agents.isNotEmpty()) {
            val idx = agents.indexOfFirst { it.agentId == initialAgentId }
            if (idx >= 0 && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
    }
    val activeAgent = agents.getOrNull(pagerState.currentPage)

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // Title bar carries the orange screen title only — the per-
        // model name moves below the prompt card as the green subject
        // line, so the user reads top-to-bottom:
        //   title bar → counter → prompt card → model → response.
        // 🔧 → Manage's per-agent ReportsViewer at the active agent.
        // No fallback to LocalNavigateToCurrentReport — that local is
        // overridden by ViewAiReportScreen to "back to View grid",
        // which would land 🔧 back on the grid instead of Manage.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.ReportsViewer(activeAgent?.agentId, null)) }
        }
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Model reports",
            subject = null,
            helpTopic = "reports_view",
            onOpenManage = onOpenManageJump,
            onBack = { onBack(activeLangState.value.ifBlank { null }) },
            onSwipePrev = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev, ViewSwipeFilter.Any)
                if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
            },
            onSwipeNext = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next, ViewSwipeFilter.Any)
                if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
            }
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
        // Prompt-card collapse toggle. When expanded, the prompt
        // section is capped at 1/3 of the available area and the
        // response section at 2/3. Both cards wrap content within
        // their caps — short content sits at natural height with
        // a normal gap between cards; only long content stretches
        // to the cap and scrolls inside. Mirrors the fan-out View
        // screen pattern.
        var promptExpanded by rememberSaveable { mutableStateOf(true) }
        val resolvedActivePrompt: String = run {
            val lang = if (languages.isEmpty()) "" else
                languages[langPagerState.currentPage.coerceIn(0, languages.size - 1)]
            if (lang.isBlank()) report.prompt
            else loaded.translatedPromptByLang[lang]?.takeIf { it.isNotBlank() } ?: report.prompt
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            val totalH = maxHeight
            // Reserve approximate chrome — the response header row
            // and the spacer between sections — so per-section caps
            // don't push the bottom of the response off-screen when
            // both sections sit at cap.
            val chrome = 64.dp
            val available = (totalH - chrome).coerceAtLeast(0.dp)
            val promptCap = if (promptExpanded) available / 3 else 0.dp
            val responseCap = if (promptExpanded) available * 2 / 3 else available

            Column(modifier = Modifier.fillMaxWidth()) {
                if (promptExpanded) {
                    if (languages.size <= 1) {
                        PromptCard(
                            report = report, languageIcon = null, promptOverride = null,
                            onCollapse = { promptExpanded = false },
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = promptCap)
                                .wrapContentHeight()
                        )
                    } else {
                        com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
                            pagerState = langPagerState,
                            noMoreLabel = "No more translations",
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HorizontalPager(
                                state = langPagerState,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(max = promptCap)
                                    .wrapContentHeight()
                            ) { page ->
                                val lang = languages[page.coerceIn(0, languages.size - 1)]
                                val flag = when {
                                    lang.isBlank() -> report.languageIcon?.takeIf { it.isNotBlank() }
                                    else -> com.ai.data.InternalPromptIconCache.get("translation_icon", lang)
                                }
                                val promptOverride = if (lang.isBlank()) null
                                    else loaded.translatedPromptByLang[lang]?.takeIf { it.isNotBlank() }
                                PromptCard(
                                    report = report, languageIcon = flag, promptOverride = promptOverride,
                                    onCollapse = { promptExpanded = false },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                } else {
                    CollapsedPromptRow(
                        report = report,
                        preview = resolvedActivePrompt,
                        onExpand = { promptExpanded = true }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                val activeProvider = activeAgent?.let { com.ai.data.AppService.findById(it.provider) }
                val activeModelId = activeAgent?.model.orEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeAgent?.let { shortModelName(it.model) }.orEmpty(),
                        color = AppColors.Green,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                            .modelInfoViewClickable(activeProvider, activeModelId)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${pagerState.currentPage + 1} / ${agents.size}",
                        color = AppColors.TextTertiary, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
                    pagerState = pagerState,
                    noMoreLabel = "No more models",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = responseCap)
                            .wrapContentHeight()
                    ) { page ->
                        val agent = agents[page]
                        AgentResponseCard(
                            agent = agent,
                            overrideBody = translatedByAgentId[agent.agentId]
                        )
                    }
                }
            }
        }
    }
}

/** Compact one-row preview shown in place of the Prompt card
 *  when the user collapses it. Matches the fan-out View screen's
 *  CollapsedInitiatorRow shape: small report icon + first
 *  non-blank line of the active prompt + ▼ affordance on the
 *  right. Tapping anywhere on the row re-expands the card. */
@Composable
private fun CollapsedPromptRow(report: Report, preview: String, onExpand: () -> Unit) {
    val liveIcon = com.ai.ui.shared.LocalReportIcon.current?.takeIf { it.isNotBlank() }
    val icon = liveIcon ?: report.icon?.takeIf { it.isNotBlank() } ?: "📄"
    val firstLine = preview.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "(no prompt recorded)"
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Purple.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = firstLine,
            color = AppColors.TextSecondary, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = "▼", color = AppColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Compact prompt card shown above the per-agent response. The
 *  report icon sits centred at the top; the prompt text below
 *  reminds the user what every model was asked. When the report
 *  carries multiple languages, [languageIcon] is rendered as a
 *  small overlay in the card's top-right corner so the user can
 *  see which language is active. */
@Composable
private fun PromptCard(
    report: Report,
    languageIcon: String?,
    promptOverride: String?,
    onCollapse: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    // Prefer the live LocalReportIcon (refreshed via iconRefreshTick
    // by the report-overlay parent) so an in-flight icon-gen
    // completion updates this card without waiting for the screen to
    // remount. Falls back to the report's persisted icon, then "📄".
    val liveIcon = com.ai.ui.shared.LocalReportIcon.current?.takeIf { it.isNotBlank() }
    val displayedIcon = liveIcon ?: report.icon?.takeIf { it.isNotBlank() } ?: "📄"
    // Per-page prompt body — caller passes a non-null override on
    // non-Original language pages (the matching TRANSLATE/PROMPT
    // row's content). Original / single-language renders fall back
    // to the report's own prompt.
    val bodyText = promptOverride ?: report.prompt
    Box(
        modifier = modifier
            .wrapContentHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Purple.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
    ) {
        // verticalScroll on the body so a long prompt scrolls
        // inside the card's height cap instead of pushing the
        // response section off the bottom.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Transparent oversized glyph — no background tint, no Box
            // wrapper, just the emoji centred above the prompt text.
            Text(
                text = displayedIcon,
                fontSize = 44.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 0.dp, bottom = 2.dp)
            )
            if (bodyText.isBlank()) {
                Text(text = "(no prompt recorded)", color = AppColors.TextTertiary, fontSize = 13.sp)
            } else {
                Text(
                    text = bodyText,
                    color = AppColors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
        }
        // Top-right corner — language flag (when multi-language)
        // and the ▲ collapse affordance share a single Row so
        // both glyphs read together without overlap. Tapping ▲
        // shrinks the prompt section to a single CollapsedPromptRow
        // above (caller flips the state).
        if (!languageIcon.isNullOrBlank() || onCollapse != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!languageIcon.isNullOrBlank()) {
                    Text(text = languageIcon, fontSize = 24.sp)
                }
                if (onCollapse != null) {
                    if (!languageIcon.isNullOrBlank()) Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "▲",
                        color = AppColors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onCollapse() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
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
    // wrapContentHeight so the card sits exactly as tall as its
    // content; verticalScroll kicks in only when the response
    // overflows the pager's remaining slot.
    Column(
        modifier = Modifier.fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Blue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Transparent oversized glyph — no background tint, no Box
        // wrapper, just the emoji centred above the response body.
        Text(
            text = emoji,
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
