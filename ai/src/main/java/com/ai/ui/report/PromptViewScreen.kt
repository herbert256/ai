package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
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

    // Normalise / dedupe. Original ("") is always available since
    // the report.prompt itself is shown for it.
    val languages = remember(availableLanguages) {
        val seen = linkedSetOf<String>()
        seen += ""
        availableLanguages.forEach { seen += (it ?: "") }
        seen.toList()
    }
    val initialIndex = remember(languages, initialLanguage) {
        val target = initialLanguage ?: ""
        languages.indexOf(target).coerceAtLeast(0)
    }

    data class Loaded(val report: Report?, val translatedByLang: Map<String, String>)

    val loadedState = produceState<Loaded>(
        initialValue = Loaded(null, emptyMap()),
        reportId
    ) {
        value = withContext(Dispatchers.IO) {
            val rep = ReportStorage.getReport(context, reportId)
            val translated = SecondaryResultStorage
                .listForReport(context, reportId, SecondaryKind.TRANSLATE)
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

    // Wrap-around: virtual large page count, modular index against
    // [languages]. Start mid-way so swiping back from page 0 still
    // works.
    val virtualCount = if (languages.isEmpty()) 1 else Int.MAX_VALUE
    val pagerState = rememberPagerState(
        initialPage = if (languages.size <= 1) 0
                      else (virtualCount / 2) - ((virtualCount / 2) % languages.size) + initialIndex
    ) { virtualCount }
    val currentLanguage = if (languages.isEmpty()) ""
        else languages[((pagerState.currentPage % languages.size) + languages.size) % languages.size]
    // Subject = the language's emoji icon (source-language icon for
    // Original, cached translation icon for translated pages). Hidden
    // entirely (null) when the report has no translations — a single-
    // language report doesn't need a per-page indicator.
    val subject = when {
        languages.size <= 1 -> null
        currentLanguage.isBlank() -> report?.languageIcon?.takeIf { it.isNotBlank() }
        else -> com.ai.data.InternalPromptIconCache.get("translation_icon", currentLanguage)
    }

    // Android back returns to the parent View screen AND tells it
    // which language ended up active here, so the parent's picker
    // matches what the user was last reading.
    val activeLangState = rememberUpdatedState(currentLanguage)
    BackHandler { onBack(activeLangState.value.ifBlank { null }) }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Prompt",
            subject = subject,
            helpTopic = "prompt_view_screen",
            onBack = { onBack(activeLangState.value.ifBlank { null }) },
            onLogoClick = { onBack(activeLangState.value.ifBlank { null }) }
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { virtualPage ->
            val lang = if (languages.isEmpty()) ""
                else languages[((virtualPage % languages.size) + languages.size) % languages.size]
            val body = if (lang.isBlank()) report.prompt.orEmpty()
                else loaded.translatedByLang[lang].orEmpty().ifBlank { report.prompt.orEmpty() }
            PromptPageCard(body = body, reportIcon = report.icon)
        }
    }
}

@Composable
private fun PromptPageCard(body: String, reportIcon: String?) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            AppColors.Purple.copy(alpha = 0.32f),
                            AppColors.Indigo.copy(alpha = 0.08f)
                        )
                    )
                )
                .border(1.dp, AppColors.Purple.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The icon strip above the body keeps the report's own
            // emoji so the page still feels anchored to its report
            // even after the previous header row was removed.
            if (!reportIcon.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text = reportIcon, fontSize = 26.sp)
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
        Spacer(modifier = Modifier.height(24.dp))
    }
}
