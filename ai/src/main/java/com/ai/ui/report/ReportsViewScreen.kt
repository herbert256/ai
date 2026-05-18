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
import androidx.compose.runtime.remember
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
        val translatedByLang: Map<String, Map<String, String>>
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap()),
        reportId
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val byLang = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter {
                    it.translateSourceKind == "AGENT" &&
                        !it.content.isNullOrBlank() &&
                        !it.translateSourceTargetId.isNullOrBlank() &&
                        !it.targetLanguage.isNullOrBlank()
                }
                .groupBy { it.targetLanguage!! }
                .mapValues { (_, list) ->
                    list.associate { it.translateSourceTargetId!! to it.content!! }
                }
            Loaded(rep, byLang)
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
        // Falls back to main Manage when no dispatcher is provided
        // (shouldn't happen inside a report context, but safe).
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val navToManageMain = com.ai.ui.shared.LocalNavigateToCurrentReport.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.ReportsViewer(activeAgent?.agentId, null)) }
        } ?: navToManageMain
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Model reports",
            subject = null,
            helpTopic = "reports_view",
            onOpenManage = onOpenManageJump,
            onBack = { onBack(activeLangState.value.ifBlank { null }) }
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
        // Prompt card lives in its own HorizontalPager — one page per
        // language. Swipe the card to flip the whole screen (prompt
        // text + agent-response translation set) to the previous /
        // next language. The agent pager below stays for swiping
        // between models on the active language.
        if (languages.size <= 1) {
            PromptCard(report = report, languageIcon = null)
        } else {
            HorizontalPager(
                state = langPagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                val lang = languages[page.coerceIn(0, languages.size - 1)]
                val flag = when {
                    lang.isBlank() -> report.languageIcon?.takeIf { it.isNotBlank() }
                    else -> com.ai.data.InternalPromptIconCache.get("translation_icon", lang)
                }
                PromptCard(report = report, languageIcon = flag)
            }
        }
        // Counter "X / Y" — sits between the prompt card and the
        // green model-name subject so the page index reads as the
        // header for the response below. Generous top padding gives
        // the prompt card breathing room; the gap to the green
        // subject below is intentionally tight (counter + model
        // name read as a single label pair).
        Text(
            text = "${pagerState.currentPage + 1} / ${agents.size}",
            color = AppColors.TextTertiary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
        )
        // Green subject line — the active page's model name. Tap →
        // View Model Info for this (provider, model). Pulled close
        // to the counter so the two read as a paired label.
        val activeProvider = activeAgent?.let { com.ai.data.AppService.findById(it.provider) }
        val activeModelId = activeAgent?.model.orEmpty()
        Text(
            text = activeAgent?.let { shortModelName(it.model) }.orEmpty(),
            color = AppColors.Green,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 16.dp)
                .modelInfoViewClickable(activeProvider, activeModelId)
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
        ) { page ->
            val agent = agents[page]
            AgentResponseCard(
                agent = agent,
                overrideBody = translatedByAgentId[agent.agentId]
            )
        }
    }
}

/** Compact prompt card shown above the per-agent response. The
 *  report icon sits centred at the top; the prompt text below
 *  reminds the user what every model was asked. When the report
 *  carries multiple languages, [languageIcon] is rendered as a
 *  small overlay in the card's top-right corner so the user can
 *  see which language is active. */
@Composable
private fun PromptCard(report: Report, languageIcon: String?) {
    // Prefer the live LocalReportIcon (refreshed via iconRefreshTick
    // by the report-overlay parent) so an in-flight icon-gen
    // completion updates this card without waiting for the screen to
    // remount. Falls back to the report's persisted icon, then "📄".
    val liveIcon = com.ai.ui.shared.LocalReportIcon.current?.takeIf { it.isNotBlank() }
    val displayedIcon = liveIcon ?: report.icon?.takeIf { it.isNotBlank() } ?: "📄"
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Purple.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            // Transparent oversized glyph — no background tint, no Box
            // wrapper, just the emoji centred above the prompt text.
            Text(
                text = displayedIcon,
                fontSize = 44.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 0.dp, bottom = 2.dp)
            )
            if (report.prompt.isBlank()) {
                Text(text = "(no prompt recorded)", color = AppColors.TextTertiary, fontSize = 13.sp)
            } else {
                Text(
                    text = report.prompt,
                    color = AppColors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
        }
        if (!languageIcon.isNullOrBlank()) {
            Text(
                text = languageIcon,
                fontSize = 24.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 8.dp)
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
    // wrapContentHeight so the card sits exactly as tall as its
    // content; verticalScroll kicks in only when the response
    // overflows the pager's remaining slot.
    Column(
        modifier = Modifier.fillMaxWidth()
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
