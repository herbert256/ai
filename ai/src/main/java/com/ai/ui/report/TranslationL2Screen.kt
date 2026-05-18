package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.ReportViewModel

/**
 * L2 of the translation run drill-in: the items one model
 * translated. Header carries the model name; rows are the
 * individual translation items with a status fill. Tapping a row
 * opens L3.
 */
@Composable
internal fun TranslationL2Screen(
    run: ReportViewModel.TranslationRunState,
    modelKey: String,
    actions: TranslationActions,
    onOpenItem: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val modelLabel = resolveModelLabel(modelKey)
    val parts = modelKey.split("|", limit = 2)
    val providerService = parts.getOrNull(0)?.let { AppService.findById(it) }
    val modelName = parts.getOrNull(1).orEmpty()

    // Items this model handled, ordered running/pending → error → done.
    val rows = remember(run.items, modelKey) {
        run.items.filter { translationModelKey(it) == modelKey }
            .sortedWith(
                compareBy(
                    { item ->
                        when (item.status) {
                            ReportViewModel.TranslationStatus.RUNNING,
                            ReportViewModel.TranslationStatus.PENDING -> 0
                            ReportViewModel.TranslationStatus.ERROR -> 1
                            ReportViewModel.TranslationStatus.DONE -> 2
                        }
                    },
                    { it.label.lowercase() }
                )
            )
    }
    val total = rows.size
    val done = rows.count { it.status == ReportViewModel.TranslationStatus.DONE }
    val cost = rows.sumOf { it.costDollars }
    val allDone = total > 0 && done == total

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        // 👁 → matching View Translate screen for this run.
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            { holder.value = com.ai.ui.shared.ViewJump.TranslationRun(run.runId) }
        }
        TitleBar(
            helpTopic = "translation_run_l2",
            title = "Translation - model",
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = modelLabel,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            onInfo = if (providerService != null && modelName.isNotBlank()) {
                { actions.onNavigateToModelInfo(providerService, modelName) }
            } else null
        )
        com.ai.ui.shared.HardcodedSubjectRow(modelLabel)

        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                "$total item${if (total == 1) "" else "s"}",
                fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f)
            )
            if (cost > 0.0) {
                Text(
                    formatCents(cost), fontSize = 11.sp,
                    color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                )
            }
        }

        if (rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No items for this model", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.id }) { item ->
                    val fillColor = when (item.status) {
                        ReportViewModel.TranslationStatus.DONE -> AppColors.Green.copy(alpha = 0.30f)
                        ReportViewModel.TranslationStatus.ERROR -> AppColors.Red.copy(alpha = 0.30f)
                        else -> null
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .drawBehind {
                                if (!allDone && fillColor != null) {
                                    drawRect(color = fillColor, size = Size(size.width, size.height))
                                }
                            }
                            .clickable { onOpenItem(item.id) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!allDone) {
                            when (item.status) {
                                ReportViewModel.TranslationStatus.RUNNING ->
                                    AnimatedHourglass(fontSize = 16.sp, modifier = Modifier.width(24.dp).padding(end = 8.dp))
                                else -> {
                                    val glyph = when (item.status) {
                                        ReportViewModel.TranslationStatus.DONE -> "✅"
                                        ReportViewModel.TranslationStatus.ERROR -> "❌"
                                        else -> "🕓"
                                    }
                                    Text(glyph, fontSize = 16.sp, modifier = Modifier.width(24.dp).padding(end = 8.dp))
                                }
                            }
                        }
                        Text(
                            translationKindLabel(item.kind),
                            fontSize = 11.sp, color = AppColors.TextSecondary,
                            modifier = Modifier.width(70.dp).padding(end = 8.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.label.ifBlank { item.kind.name.lowercase() },
                                fontSize = 13.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.costDollars > 0.0) {
                            Text(
                                formatCents(item.costDollars), fontSize = 10.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }
    }
}

/** Broad category label for a translation item's source kind — the
 *  ~70dp column on each L2 row. */
internal fun translationKindLabel(kind: ReportViewModel.TranslationKind): String = when (kind) {
    ReportViewModel.TranslationKind.TITLE -> "title"
    ReportViewModel.TranslationKind.PROMPT -> "prompt"
    ReportViewModel.TranslationKind.AGENT_RESPONSE -> "report"
    ReportViewModel.TranslationKind.META -> "meta"
}
