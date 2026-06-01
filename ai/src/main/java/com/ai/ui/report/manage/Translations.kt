package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.TranslationRunState

/**
 * "Translations" — the list of translation runs on a report, opened by the
 * 🌐 Translate bottom-bar icon on Manage report *when at least one translation
 * already exists* (with none, the icon opens the create flow directly). One row
 * per run (live runs first, with a spinning ⏳; then the persisted summaries),
 * newest first. Tapping a row drills into that run's detail screen; the 🆕
 * bottom-bar icon starts a new translation.
 *
 * Rendered as a layer-on-top overlay in [ReportRunScreen] (mirrors
 * [ReportTournamentOverviewScreen]). Row layout matches the translation rows
 * GenerationPhase shows inline so the two read identically.
 */
@Composable
internal fun ReportTranslationsScreen(
    reportTitle: String,
    reportIcon: String,
    summaries: List<TranslationRunSummary>,
    liveRuns: List<TranslationRunState>,
    onOpenRun: (String) -> Unit,
    onNewTranslation: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    // Active runs hide their persisted summary row to avoid a double line —
    // same suppression GenerationPhase applies.
    val liveIds = liveRuns.map { it.runId }.toSet()
    val persisted = summaries.filter { it.runId !in liveIds }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
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
        if (liveRuns.isEmpty() && persisted.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No translations yet — tap 🆕 to add one.", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(liveRuns, key = { "tr-live-${it.runId}" }) { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable { onOpenRun(run.runId) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        AnimatedHourglass(fontSize = 16.sp)
                    }
                    RowTypeCell("translate")
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${run.completed} / ${run.total} · ${run.targetLanguageName.ifBlank { "Translate" }}",
                            fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (run.totalCostDollars > 0.0) {
                        Text(formatCents(run.totalCostDollars), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
            items(persisted, key = { "trs-${it.runId}" }) { run ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable { onOpenRun(run.runId) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val lang = run.targetLanguage
                    val emoji = lang?.takeIf { it.isNotBlank() }
                        ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
                    when {
                        run.errorCount > 0 -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        emoji != null -> Text(emoji, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        else -> Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    }
                    RowTypeCell("translate")
                    val label = run.targetLanguageNative?.takeIf { it.isNotBlank() }
                        ?: run.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translate"
                    val info = "$label - ${run.callCount} item${if (run.callCount == 1) "" else "s"}"
                    Column(modifier = Modifier.weight(1f)) {
                        Text(info, fontSize = 13.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (run.totalCost > 0.0) {
                        Text(formatCents(run.totalCost), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }
}
