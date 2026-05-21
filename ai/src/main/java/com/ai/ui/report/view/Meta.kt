package com.ai.ui.report.view
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.InternalPromptIconCache
import com.ai.data.Report
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
 * Content-only "View" sibling of [SecondaryResultDetailScreen] for META
 * rows. Reached from a Meta tile on Report - view; the management-heavy
 * detail screen (delete, regenerate, multi-language delete chooser,
 * trace icon, alt-icon picker, …) stays as the Manage path.
 *
 * Layout: a "❓ Question" hero card up top showing the originating
 * report prompt with the matching internal-prompt emoji on the left,
 * then a single large "🧠 <prompt name>" answer card below carrying
 * the meta's content rendered through the shared
 * [ContentWithThinkSections]. A subtle gradient on the question card
 * separates the two visually without competing for attention.
 *
 * Language slicing: when [language] is non-blank we look up a matching
 * META TRANSLATE row (translateSourceKind="META",
 * translateSourceTargetId=resultId, targetLanguage=language) and
 * render its content in place of the original. Null/"" = Original
 * (the meta row's own content).
 */
@Composable
fun MetaViewScreen(
    reportId: String,
    resultId: String,
    language: String?,
    /** Receives the active language ("" = Original, non-blank =
     *  displayName) at navigate-back time so the parent View screen
     *  can adopt it on return. */
    onBack: (activeLanguage: String?) -> Unit
) {
    val context = LocalContext.current
    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    var currentResultId by rememberSaveable(resultId) { mutableStateOf(resultId) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current

    data class Loaded(
        /** The single META row this screen is anchored on — looked up
         *  by [resultId]. Null while produceState is cold or if the
         *  row has been deleted. */
        val row: SecondaryResult?,
        /** language displayName → translated body for this row. ""
         *  is reserved for Original (which uses [row].content). */
        val translatedByLanguage: Map<String, String>,
        val report: Report?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap(), null),
        currentReportId, currentResultId
    ) {
        value = withContext(Dispatchers.IO) {
            val r = SecondaryResultStorage.get(context, currentReportId, currentResultId)
                ?.takeIf { !it.content.isNullOrBlank() }
            val translates = SecondaryResultStorage
                .listForReport(context, currentReportId, SecondaryKind.TRANSLATE)
                .filter {
                    it.translateSourceKind == "META" &&
                        it.translateSourceTargetId == currentResultId &&
                        !it.content.isNullOrBlank() &&
                        !it.targetLanguage.isNullOrBlank()
                }
                .associate { it.targetLanguage!! to it.content!! }
            val rep = com.ai.ui.report.view.helpers.ViewReportCache.get(context, currentReportId)
            Loaded(r, translates, rep)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val row = loaded.row

    // Available languages for this row: Original ("") first, then
    // every targetLanguage with a non-blank META TRANSLATE row.
    // Order is the insertion order of the translates map (which
    // reflects disk order for this report).
    val languages = remember(loaded) {
        val seen = linkedSetOf<String>()
        seen += ""
        loaded.translatedByLanguage.keys.forEach { seen += it }
        seen.toList()
    }
    val initialIndex = remember(languages, language) {
        val target = language ?: ""
        languages.indexOf(target).coerceAtLeast(0)
    }
    val pageCount = languages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, pageCount - 1)
    ) { pageCount }
    val activeLanguage = if (languages.isEmpty()) ""
        else languages[pagerState.currentPage.coerceIn(0, languages.size - 1)]
    val activeLangState = rememberUpdatedState(activeLanguage)
    androidx.activity.compose.BackHandler {
        onBack(activeLangState.value.ifBlank { null })
    }

    val metaPromptName = row?.metaPromptName?.takeIf { it.isNotBlank() }
    val rowIcon = row?.icon?.takeIf { it.isNotBlank() }
    val cachedIcon = metaPromptName?.let { InternalPromptIconCache.getByName(it) }
        ?.takeIf { it.isNotBlank() }
    val displayedEmoji = cachedIcon ?: rowIcon ?: "🧠"
    val modelLabel = row?.model?.let { shortModelName(it) }.orEmpty()

    val metaFilter: ViewSwipeFilter? = metaPromptName?.let { ViewSwipeFilter.HasMeta(metaPromptName = it) }
    val onSwipePrevAction: (() -> Boolean)? = metaFilter?.let { filter -> {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev, filter)
        if (m != null) { currentReportId = m.reportId; m.resultId?.let { currentResultId = it }; switchReport?.invoke(m.reportId); true } else false
    } }
    val onSwipeNextAction: (() -> Boolean)? = metaFilter?.let { filter -> {
        val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next, filter)
        if (m != null) { currentReportId = m.reportId; m.resultId?.let { currentResultId = it }; switchReport?.invoke(m.reportId); true } else false
    } }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .viewBodySwipe(currentReportId, onPrev = { onSwipePrevAction?.invoke() }, onNext = { onSwipeNextAction?.invoke() })
    ) {
        // 🔧 → Manage's SecondaryResultDetail for this META row.
        // When LocalOpenManage is null (standalone View route, no
        // report context) the wrench hides itself — no fallback to
        // LocalNavigateToCurrentReport because that local gets
        // overridden by ViewAiReportScreen's sub-View block to "back
        // to View grid", which is the opposite of what 🔧 should do.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.MetaResult(currentResultId)) }
        }
        val screenTitleLabel = if (metaPromptName != null) "Meta - $metaPromptName" else "Meta"
        ViewTitleBar(
            reportTitle = report?.title,
            screenTitle = screenTitleLabel,
            subject = null,
            helpTopic = "meta_view",
            onOpenManage = onOpenManageJump,
            onBack = { onBack(activeLangState.value.ifBlank { null }) },
            onSwipePrev = onSwipePrevAction,
            onSwipeNext = onSwipeNextAction
        )
        // Header row: dynamic per-prompt icon + the model name that
        // produced this META row. Matches the Fan-in View screen.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayedEmoji,
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = modelLabel,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Green,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
                    .modelInfoViewClickable(
                        row?.let { AppService.findById(it.providerId) },
                        row?.model.orEmpty()
                    )
            )
        }
        if (row == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    "Loading…",
                    color = AppColors.TextTertiary, fontSize = 14.sp
                )
            }
            return@Column
        }

        // Language pager — one page per language for this single
        // META row. With only Original (no translations) the pager
        // degenerates to a single page. The previous meta-row pager
        // is gone per the user's spec: to navigate between meta
        // runs the user goes back to the View tile grid and taps
        // another meta tile.
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
                    else languages[page.coerceIn(0, languages.size - 1)]
                val body = if (lang.isBlank()) row.content.orEmpty()
                    else loaded.translatedByLanguage[lang]?.takeIf { it.isNotBlank() }
                        ?: row.content.orEmpty()
                // Per-page language flag — re-derived inside the lambda
                // so off-screen pre-rendered pages bind to their own
                // page's flag, not the active page's.
                val pageFlag = when {
                    languages.size <= 1 -> null
                    lang.isBlank() -> report?.languageIcon?.takeIf { it.isNotBlank() } ?: "🌐"
                    else -> com.ai.data.InternalPromptIconCache.get("translation_icon", lang)
                        ?: "🌍"
                }
                // Box-anchored layout: the card wraps its content
                // (short rows sit at the top), and the language flag
                // is pinned to the page Box's TopEnd so it stays put
                // when long rows scroll under it.
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        AnswerCard(
                            body = body,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)
                        )
                    }
                    if (!pageFlag.isNullOrBlank()) {
                        Text(
                            text = pageFlag,
                            fontSize = 24.sp,
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
private fun AnswerCard(
    body: String,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    // Card wraps its own content — short rows sit at the top of
    // the pager instead of inflating to fill the page. Caller
    // wraps this in a verticalScroll Column + anchored-flag Box.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Purple.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (body.isBlank()) {
                Text(
                    text = "(no content)",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            } else {
                // Reuse the shared markdown + <think> pipeline so tables,
                // headings, code blocks render the same as everywhere else.
                ContentWithThinkSections(analysis = body)
            }
        }
    }
}
