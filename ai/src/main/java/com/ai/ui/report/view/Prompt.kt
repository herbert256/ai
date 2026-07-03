package com.ai.ui.report.view

import com.ai.data.barTitle
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.viewBodySwipe
import com.ai.ui.report.view.helpers.rememberWrapPager
import com.ai.ui.report.view.helpers.wrapCenterPage
import com.ai.ui.report.view.helpers.wrapTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-only "View" variant of the report prompt. Reached from the
 * 📝 Prompt tile on Report - view.
 *
 * Layout: a single hero typography card with a soft Purple-to-Indigo
 * gradient. When the report carries more than one language, the card
 * lives inside a [HorizontalPager] that wraps end-to-end so the user
 * can swipe through every language without hitting a hard stop. The
 * active language is reflected in the title bar's green subject row
 * and is propagated back to [onBack] so the parent View screen can
 * adopt the new active language.
 *
 * [availableLanguages] is the ordered list of language display names
 * the parent View screen tracks. "" represents Original (the report's
 * own prompt). Non-empty entries are the targetLanguage values of the
 * report's TRANSLATE rows. If the list is empty or only contains
 * Original, the pager degenerates to a single page.
 */
@Composable
fun PromptViewScreen(
    reportId: String,
    availableLanguages: List<String>,
    initialLanguage: String?,
    onBack: (activeLanguage: String?) -> Unit
) {
    val context = LocalContext.current
    // Title-bar swipe targets — `currentReportId` shadows the prop so
    // a swipe can hot-swap the displayed report without unmounting.
    var currentReportId by androidx.compose.runtime.saveable.rememberSaveable(reportId) {
        androidx.compose.runtime.mutableStateOf(reportId)
    }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current

    data class Loaded(val report: Report?, val translatedByLang: Map<String, String>)

    val reportDataVersion by ReportDataVersion.versionFor(currentReportId).collectAsState()
    val secondaryDataVersion by SecondaryDataVersion.versionFor(currentReportId, SecondaryKind.TRANSLATE).collectAsState()
    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap()),
        currentReportId, reportDataVersion, secondaryDataVersion
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = com.ai.ui.report.view.helpers.ViewReportCache.get(context, currentReportId)
            val translated = SecondaryResultStorage
                .listForReport(context, currentReportId, SecondaryKind.TRANSLATE)
                .filter {
                    it.translateSourceKind == "PROMPT" &&
                        !it.content.isNullOrBlank() &&
                        !it.targetLanguage.isNullOrBlank()
                }
                .associate { it.targetLanguage!! to it.content!! }
            Loaded(rep, translated)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report

    // Normalise / dedupe. Original ("") is always available since
    // the report.prompt itself is shown for it. Falls back to the
    // parent-supplied [availableLanguages] until the loaded
    // translations arrive, then tracks the loaded set so a swiped-in
    // new report's pager carries that report's own languages.
    val languages = remember(availableLanguages, loaded.translatedByLang, loaded.report) {
        val seen = linkedSetOf<String>()
        seen += ""
        when {
            loaded.translatedByLang.isNotEmpty() -> loaded.translatedByLang.keys.forEach { seen += it }
            // Only fall back to the mount-time availableLanguages while STILL
            // loading (loaded.report == null); once loaded, a translation-less
            // report must not keep the previous report's tabs. See Agent.kt.
            loaded.report == null -> availableLanguages.forEach { seen += it }
        }
        seen.toList()
    }
    val initialIndex = remember(languages, initialLanguage) {
        val target = initialLanguage ?: ""
        languages.indexOf(target).coerceAtLeast(0)
    }

    // Wrapping language pager — swipe past the first / last wraps around
    // (the user wants every View swipe to cycle forever, both ways).
    val pagerState = rememberWrapPager(languages.size, initialIndex)
    // Latch only once the LOADED data is for the current report: on a
    // title-bar swipe this effect fires while produceState still holds the
    // previous report's translations — centring against the stale list and
    // latching left the pager wrap-aligned to the wrong modulus (opening on
    // the last translation instead of Original, or dead-ending backward
    // swipes at the span edge — the exact symptom this effect fixes).
    var centeredFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentReportId, languages, initialLanguage) {
        val key = "$currentReportId|${initialLanguage.orEmpty()}"
        if (centeredFor != key && loaded.report?.id == currentReportId) {
            val target = languages.indexOf(initialLanguage ?: "").coerceAtLeast(0)
            pagerState.scrollToPage(wrapCenterPage(languages.size, target))
            centeredFor = key
        }
    }
    val currentLanguage = if (languages.isEmpty()) ""
        else languages[pagerState.currentPage.wrapTo(languages.size)]

    // Android back returns to the parent View screen AND tells it
    // which language ended up active here, so the parent's picker
    // matches what the user was last reading.
    val activeLangState = rememberUpdatedState(currentLanguage)
    BackHandler { onBack(activeLangState.value.ifBlank { null }) }

    val onSwipePrevAction: () -> Boolean = {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev, ViewSwipeFilter.Any)
        if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
    }
    val onSwipeNextAction: () -> Boolean = {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next, ViewSwipeFilter.Any)
        if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .viewBodySwipe(currentReportId, onPrev = { onSwipePrevAction() }, onNext = { onSwipeNextAction() })
    ) {
        // No per-prompt Manage screen — 🔧 dispatches ManageJump.Main
        // which closes the View overlay and reveals the main
        // "Manage report" screen. Going through LocalOpenManage (not
        // LocalNavigateToCurrentReport) matters because the latter is
        // overridden by ViewAiReportScreen's sub-View block to "back
        // to View grid" — using it here would land the user back on
        // the View grid instead of Manage.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.Main) }
        }
        ViewTitleBar(
            reportTitle = report?.barTitle,
            reportId = currentReportId,
            activeLanguage = currentLanguage,
            screenTitle = "Prompt",
            // Language flag moved to the top-right of the prompt card
            // (see PromptPageCard). Leaving the title bar's subject
            // slot null keeps the bar uncluttered.
            subject = null,
            helpTopic = "prompt_view_screen",
            onOpenManage = onOpenManageJump,
            onBack = { onBack(activeLangState.value.ifBlank { null }) },
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction,
            // 📋 / 📤 — the prompt in the ACTIVE language, matching what's
            // on screen (Manage's Prompt screen has carried copy all along).
            onCopy = report?.prompt?.takeIf { it.isNotBlank() }?.let {
                {
                    val lang = activeLangState.value
                    val body = if (lang.isBlank()) report.prompt.orEmpty()
                        else loaded.translatedByLang[lang].orEmpty().ifBlank { report.prompt.orEmpty() }
                    com.ai.ui.shared.copyToClipboard(context, body, "prompt")
                }
            },
            onExport = report?.prompt?.takeIf { it.isNotBlank() }?.let {
                {
                    val lang = activeLangState.value
                    val body = if (lang.isBlank()) report.prompt.orEmpty()
                        else loaded.translatedByLang[lang].orEmpty().ifBlank { report.prompt.orEmpty() }
                    com.ai.ui.shared.shareText(context, body, "Prompt — ${report.barTitle}")
                }
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
        com.ai.ui.shared.SwipeEdgeNoMoreOverlay(
            pagerState = pagerState,
            noMoreLabel = "No more translations",
            modifier = Modifier.fillMaxSize()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val lang = if (languages.isEmpty()) ""
                    else languages[page.wrapTo(languages.size)]
                val body = if (lang.isBlank()) report.prompt.orEmpty()
                    else loaded.translatedByLang[lang].orEmpty().ifBlank { report.prompt.orEmpty() }
                // Pass the per-page language icon so each card carries its
                // own indicator — re-derived inside the lambda so the
                // off-screen pre-rendered pages show the right flag too,
                // not the currently-active page's flag.
                val perPageIcon = when {
                    languages.size <= 1 -> null
                    lang.isBlank() -> report.languageIcon?.takeIf { it.isNotBlank() }
                    else -> com.ai.data.InternalPromptIconCache.get("translation_icon", lang)
                }
                // Prefer the live LocalReportIcon (refreshed via
                // iconRefreshTick by the report-overlay parent) so a
                // fresh icon-gen result lands without remount; fall
                // back to the persisted icon on the loaded report.
                val liveReportIcon = com.ai.ui.shared.LocalReportIcon.current?.takeIf { it.isNotBlank() }
                // Pager page = a Box that fills the page slot. Inside:
                //   1) a verticalScroll Column with the card — the card
                //      wraps its content so short prompts sit at the
                //      TOP instead of inflating to fill the page.
                //   2) the language flag pinned to the Box's TopEnd —
                //      anchored to the page (not the card), so it
                //      stays put while the inner Column scrolls long
                //      prompts under it.
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        PromptPageCard(
                            body = body,
                            reportIcon = liveReportIcon ?: report.icon
                        )
                        // Resolved report-level system prompt — until now it
                        // survived only inside the HTTP trace, so reproducing
                        // a run copied the question but silently omitted the
                        // system instruction. Copyable via 📋 on the card.
                        val aiSettingsForSys = com.ai.ui.shared.LocalAiSettings.current
                        val systemPromptText = report.reportSystemPromptId
                            ?.let { aiSettingsForSys.getSystemPromptById(it) }
                            ?.prompt?.takeIf { it.isNotBlank() }
                        if (systemPromptText != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(bottom = 24.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AppColors.CardBackground)
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "System prompt", fontSize = 13.sp,
                                        color = AppColors.TextSecondary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        com.ai.ui.shared.LocalMetadataIcons.current.copy,
                                        fontSize = 16.sp,
                                        modifier = Modifier
                                            .clickable {
                                                com.ai.ui.shared.copyToClipboard(context, systemPromptText, "system prompt")
                                            }
                                            .padding(4.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(systemPromptText, fontSize = 13.sp, color = AppColors.TextPrimary)
                            }
                        }
                    }
                    if (!perPageIcon.isNullOrBlank()) {
                        Text(
                            text = perPageIcon,
                            fontSize = 28.sp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 10.dp, end = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptPageCard(body: String, reportIcon: String?) {
    // Card wraps its own content — small prompts sit at the top of
    // the pager instead of the card inflating to fill the page.
    // The language flag is handled by the caller as a Box-anchored
    // overlay so it stays put when long prompts scroll under it.
    Box(
        modifier = Modifier.fillMaxWidth()
            .padding(top = 4.dp, bottom = 24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        AppColors.PrimaryAccent.copy(alpha = 0.32f),
                        AppColors.SecondaryAccent.copy(alpha = 0.08f)
                    )
                )
            )
            .border(1.dp, AppColors.PrimaryAccent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Report icon strip — bigger now so the page reads as
            // its report at a glance instead of needing a squint.
            if (!reportIcon.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text = reportIcon, fontSize = 56.sp)
                }
            }
            if (body.isBlank()) {
                Text(
                    text = "(no prompt recorded)",
                    color = AppColors.TextTertiary, fontSize = 14.sp
                )
            } else {
                // Body via the shared markdown pipeline so the
                // prompt's own formatting (fences, tables, lists)
                // renders properly.
                ContentWithThinkSections(analysis = body)
            }
        }
    }
}
