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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.ai.ui.shared.ViewScreenTitleBar
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
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current

    data class Loaded(
        // Every META row on this report (sorted by timestamp) so the
        // pager can swipe across them. The initially-tapped row sets
        // the starting page.
        val allMetaRows: List<SecondaryResult>,
        // resultId → translated body for the active language. Null
        // map means no translations / Original.
        val translatedByRowId: Map<String, String>,
        val report: Report?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(emptyList(), emptyMap(), null),
        reportId, language
    ) {
        value = withContext(Dispatchers.IO) {
            // Drop fan-out and fan-in flavoured META rows from the
            // swipe set — they get their own dedicated cards on the
            // View tile grid (Fan-out / Fan-in / Fan-in-model), and
            // surfacing them again when paging here only confused
            // the user. The remaining rows are "regular" meta runs
            // (Summarize, Compare, etc.).
            val metas = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.META)
                .filter {
                    !it.content.isNullOrBlank() &&
                        it.fanInOf == null &&
                        it.fanOutSourceAgentId == null &&
                        it.scopeProviderId == null &&
                        it.scopeModel == null
                }
                .sortedBy { it.timestamp }
            val translates = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
            val rep = ReportStorage.getReport(context, reportId)
            val translatedMap: Map<String, String> = if (!language.isNullOrEmpty()) {
                translates
                    .filter {
                        it.translateSourceKind == "META" &&
                            it.targetLanguage == language &&
                            !it.content.isNullOrBlank() &&
                            !it.translateSourceTargetId.isNullOrBlank()
                    }
                    .associate { it.translateSourceTargetId!! to it.content!! }
            } else emptyMap()
            Loaded(metas, translatedMap, rep)
        }
    }
    val loaded = loadedState.value
    val report = loaded.report
    val rows = loaded.allMetaRows

    // Pager — one page per META row. `produceState` starts with an
    // empty rows list and the real list arrives async, so
    // `rememberPagerState(initialPage = …)` would settle on page 0
    // (wrong meta if the user tapped a non-first one). Land on
    // page 0 initially, then jump to the tapped row's index via a
    // LaunchedEffect once the data lands. Keyed on (rows, resultId)
    // so the jump only re-runs when either changes.
    val pagerState = rememberPagerState(initialPage = 0) {
        rows.size.coerceAtLeast(1)
    }
    LaunchedEffect(rows, resultId) {
        if (rows.isNotEmpty()) {
            val idx = rows.indexOfFirst { it.id == resultId }
            if (idx >= 0 && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
    }
    val activeRow = rows.getOrNull(pagerState.currentPage)
    val title = activeRow?.metaPromptName?.takeIf { it.isNotBlank() }
        ?: activeRow?.let { com.ai.data.legacyKindDisplayName(it.kind) }
        ?: "Meta"
    val rowIcon = activeRow?.icon?.takeIf { it.isNotBlank() }
    val cachedIcon = activeRow?.metaPromptName?.let { InternalPromptIconCache.get(it, title) }
    val displayedEmoji = rowIcon ?: cachedIcon ?: "🧠"

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // 🔧 → Manage's SecondaryResultDetail for the active META row.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val navToManageMain = com.ai.ui.shared.LocalNavigateToCurrentReport.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            val targetId = activeRow?.id ?: resultId
            { dispatch(com.ai.ui.shared.ManageJump.MetaResult(targetId)) }
        } ?: navToManageMain
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Meta",
            // Green metaPromptName subject dropped per the user's
            // spec — the purple title row below already shows the
            // active row's prompt name.
            subject = null,
            helpTopic = "meta_view",
            onOpenManage = onOpenManageJump,
            onBack = onBack
        )
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
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Purple,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!language.isNullOrEmpty()) {
                Text(
                    text = "🌍 $language",
                    fontSize = 12.sp,
                    color = AppColors.Orange,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.SurfaceDark)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (rows.isEmpty()) {
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

        // One page per META row — swipe → previous / next meta.
        // The Question card and the per-card header (emoji + title +
        // provider) are gone per the user's spec: the top row above
        // already shows the active meta's emoji + name, so each page
        // renders just the body card.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val row = rows[page]
            val body = loaded.translatedByRowId[row.id]?.takeIf { it.isNotBlank() }
                ?: row.content.orEmpty()
            // LazyColumn for orientation-locked vertical scroll —
            // plays cleanly with the parent HorizontalPager's drag
            // detection so a swipe across long body text still flips
            // the page.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
            ) {
                item { AnswerCard(body = body) }
            }
        }
    }
}

@Composable
private fun AnswerCard(body: String) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Purple.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
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
