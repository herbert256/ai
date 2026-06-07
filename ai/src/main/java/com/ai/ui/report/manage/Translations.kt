package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar

/**
 * "Translations" — a plain, static list of the language versions of a report,
 * opened by the 🌐 Translate bottom-bar icon on Manage report *when at least one
 * translation already exists* (with none, the icon opens the create flow
 * directly).
 *
 * Deliberately minimal: an intro line, the **Original** language as the first
 * row, then one row per existing translation. No live progress, no counts, no
 * cost — just the language. The only icon in the bottom bar is 🆕 (start a new
 * translation); the hub's action icons are suppressed while this layer is open.
 *
 * Tapping the Original row returns to the report (its original language);
 * tapping a translation row drills into that run's detail screen.
 *
 * Rendered as a layer-on-top overlay in [ReportRunScreen].
 */
@Composable
internal fun ReportTranslationsScreen(
    reportTitle: String,
    reportIcon: String,
    originalLanguage: String?,
    originalLanguageIcon: String,
    summaries: List<TranslationRunSummary>,
    /** In-progress translation runs for this report — shown above the finished
     *  ones with a green progress bar. */
    activeRuns: List<com.ai.viewmodel.TranslationRunState>,
    onOpenRun: (String) -> Unit,
    onOpenOriginal: () -> Unit,
    onNewTranslation: () -> Unit,
    onBack: () -> Unit
) {
    // A run that's still running has its partial rows on disk too, so it would
    // also appear in [summaries] — drop those so it shows once (as in-progress).
    val activeIds = activeRuns.map { it.runId }.toSet()
    val finishedSummaries = summaries.filter { it.runId !in activeIds }
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_translations",
            title = "Translations",
            subject = reportTitle,
            reportIcon = reportIcon,
            onBackClick = onBack,
            onAdd = onNewTranslation,
            addFirst = true
        )
        Text(
            "Existing translations below, press the new icon to add a new translation",
            color = AppColors.TextSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Original — always the first row, returns to the managed report.
            // Uses the report's detected language icon (not the report icon).
            item(key = "tr-original") {
                // Mark the original item: "<language> (original)", or plain
                // "Original" when no language was detected.
                val originalBase = originalLanguage?.takeIf { it.isNotBlank() }
                TranslationLanguageRow(
                    icon = originalLanguageIcon,
                    label = originalBase?.let { "$it (original)" } ?: "Original",
                    onClick = onOpenOriginal
                )
            }
            // In-progress translations — green progress bar + "done / total".
            items(activeRuns, key = { "tra-${it.runId}" }) { run ->
                val label = run.targetLanguageNative.takeIf { it.isNotBlank() }
                    ?: run.targetLanguageName.takeIf { it.isNotBlank() } ?: "Translate"
                val emoji = run.targetLanguageName.takeIf { it.isNotBlank() }
                    ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
                    ?: com.ai.data.MetadataIconsHolder.current.translationRow
                TranslationLanguageRow(
                    icon = emoji,
                    label = label,
                    progressFraction = if (run.total > 0) run.completed.toFloat() / run.total else 0f,
                    trailing = {
                        Text(
                            "${run.completed} / ${run.total}",
                            fontSize = 12.sp, color = AppColors.TextSecondary,
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp)
                        )
                    },
                    onClick = { onOpenRun(run.runId) }
                )
            }
            items(finishedSummaries, key = { "trs-${it.runId}" }) { run ->
                val label = run.targetLanguageNative?.takeIf { it.isNotBlank() }
                    ?: run.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translate"
                val emoji = run.targetLanguage?.takeIf { it.isNotBlank() }
                    ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
                    ?: com.ai.data.MetadataIconsHolder.current.translationRow
                TranslationLanguageRow(
                    icon = emoji,
                    label = label,
                    onClick = { onOpenRun(run.runId) }
                )
            }
        }
    }
}

/**
 * One language row: a leading icon and the language name. When
 * [progressFraction] > 0 a green fill is drawn behind the row (how much of an
 * in-progress translation is done); [trailing] adds an optional right-aligned
 * slot (the "done / total" count).
 */
@Composable
private fun TranslationLanguageRow(
    icon: String,
    label: String,
    onClick: () -> Unit,
    progressFraction: Float = 0f,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val barColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .drawBehind {
                if (progressFraction > 0f) {
                    drawRect(color = barColor, size = Size(size.width * progressFraction.coerceIn(0f, 1f), size.height))
                }
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 16.sp)
        }
        Text(
            label, fontSize = 14.sp, color = AppColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        if (trailing != null) trailing()
    }
    HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
}
