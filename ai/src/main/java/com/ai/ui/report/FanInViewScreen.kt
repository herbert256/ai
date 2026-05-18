package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.ai.data.Report
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
 * Content-only "View" variant of a fan-in META row. Reached from the
 * 🪢 Fan-in tile on Report - view; the management-heavy
 * [SecondaryResultDetailScreen] is still served from Report - manage.
 *
 * Layout: hero "🪢 synthesis" card at the top showing the synthesised
 * output rendered through the shared markdown pipeline, then a
 * "Synthesised from" credits strip below as a FlowRow of compact
 * agent pills — one per source agent that fed into the run. Each pill
 * uses a small Green accent so the eye groups the contributors as a
 * unit distinct from the synthesis itself.
 */
@Composable
fun FanInViewScreen(
    reportId: String,
    resultId: String,
    language: String? = null,
    /** Receives the active language ("" = Original, non-blank =
     *  displayName) at navigate-back time so the parent View
     *  screen can adopt it on return. */
    onBack: (activeLanguage: String?) -> Unit
) {
    val context = LocalContext.current

    data class Loaded(
        val result: SecondaryResult?,
        /** language displayName → translated body for this row. ""
         *  is reserved for Original (which uses [result].content). */
        val translatedByLanguage: Map<String, String>,
        val report: Report?
    )

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap(), null),
        reportId, resultId
    ) {
        value = withContext(Dispatchers.IO) {
            val r = SecondaryResultStorage.get(context, reportId, resultId)
            val translates = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter {
                    it.translateSourceKind == "META" &&
                        it.translateSourceTargetId == resultId &&
                        !it.content.isNullOrBlank() &&
                        !it.targetLanguage.isNullOrBlank()
                }
                .associate { it.targetLanguage!! to it.content!! }
            val rep = ReportStorage.getReport(context, reportId)
            Loaded(r, translates, rep)
        }
    }
    val loaded = loadedState.value
    val result = loaded.result
    val report = loaded.report

    val title = result?.metaPromptName?.takeIf { it.isNotBlank() } ?: "Fan-in"
    val providerDisplay = result?.let { AppService.findById(it.providerId)?.id ?: it.providerId }.orEmpty()

    // Available languages for this row: Original ("") first, then
    // every targetLanguage with a non-blank META TRANSLATE row.
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

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        // 🔧 → Manage's SecondaryResultDetail for this fan-in row.
        val openManage = com.ai.ui.shared.LocalOpenManage.current
        val navToManageMain = com.ai.ui.shared.LocalNavigateToCurrentReport.current
        val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
            { dispatch(com.ai.ui.shared.ManageJump.MetaResult(resultId)) }
        } ?: navToManageMain
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Fan-in",
            subject = result?.metaPromptName?.takeIf { it.isNotBlank() },
            helpTopic = "fan_in_view",
            onOpenManage = onOpenManageJump,
            onBack = { onBack(activeLangState.value.ifBlank { null }) }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🪢", fontSize = 28.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Green,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (result == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("Loading…", color = AppColors.TextTertiary, fontSize = 14.sp)
            }
            return@Column
        }
        // Language pager — one page per available language. With
        // only Original the pager degenerates to a single page.
        // The CreditsStrip stays outside the pager so it's shared
        // across languages.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            val lang = if (languages.isEmpty()) ""
                else languages[page.coerceIn(0, languages.size - 1)]
            val body = if (lang.isBlank()) result.content.orEmpty()
                else loaded.translatedByLanguage[lang]?.takeIf { it.isNotBlank() }
                    ?: result.content.orEmpty()
            // Per-page language flag — re-derived inside the
            // lambda so off-screen pre-rendered pages bind to
            // their own page's flag, not the active page's.
            val pageFlag = when {
                languages.size <= 1 -> null
                lang.isBlank() -> report?.languageIcon?.takeIf { it.isNotBlank() } ?: "🌐"
                else -> com.ai.data.InternalPromptIconCache.get("translation_icon", lang)
                    ?: "🌍"
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
            ) {
                item {
                    SynthesisHero(
                        providerDisplay = providerDisplay,
                        model = result.model,
                        body = body,
                        languageIcon = pageFlag
                    )
                }
            }
        }
        // Credits strip sits below the language pager — same set
        // of contributing agents regardless of which translation
        // the user is reading.
        CreditsStrip(report)
    }
}

@Composable
private fun SynthesisHero(providerDisplay: String, model: String, body: String, languageIcon: String?) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(AppColors.Green.copy(alpha = 0.28f), AppColors.Green.copy(alpha = 0.05f))
                )
            )
            .border(1.dp, AppColors.Green.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "🪢", fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Synthesis",
                        color = AppColors.TextPrimary,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$providerDisplay / ${shortModelName(model)}",
                        color = AppColors.TextTertiary,
                        fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.modelInfoViewClickable(
                            com.ai.data.AppService.findById(providerDisplay), model
                        )
                    )
                }
            }
            if (body.isBlank()) {
                Text(
                    text = "No data yet",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            } else {
                ContentWithThinkSections(analysis = body)
            }
        }
        // Per-language flag overlay in the top-right corner.
        // Only shown when the row carries multiple languages.
        if (!languageIcon.isNullOrBlank()) {
            Text(
                text = languageIcon,
                fontSize = 24.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 10.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditsStrip(report: Report?) {
    val agents = report?.agents
        ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
        .orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Synthesised from",
            color = AppColors.Green,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
        if (agents.isEmpty()) {
            Text(
                text = "(no source agents)",
                color = AppColors.TextTertiary, fontSize = 12.sp
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                agents.forEach { a ->
                    val prov = AppService.findById(a.provider)?.id ?: a.provider
                    val icon = a.icon?.takeIf { it.isNotBlank() } ?: "🤖"
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppColors.Green.copy(alpha = 0.18f))
                            .border(1.dp, AppColors.Green.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = icon, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                        Text(
                            text = "$prov / ${shortModelName(a.model)}",
                            color = AppColors.TextPrimary,
                            fontSize = 11.sp,
                            maxLines = 1, softWrap = false
                        )
                    }
                }
            }
        }
    }
}
