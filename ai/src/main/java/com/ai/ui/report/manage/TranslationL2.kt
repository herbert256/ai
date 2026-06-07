package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.TranslationStatus

/**
 * L2 of the translation run drill-in: the items one model
 * translated. Header carries the model name; rows are the
 * individual translation items with a status fill. Tapping a row
 * opens L3.
 */
@Composable
internal fun TranslationL2Screen(
    run: TranslationRunState,
    mode: TranslationGroupMode,
    groupKey: String,
    actions: TranslationActions,
    onOpenItem: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val isModels = mode == TranslationGroupMode.MODELS
    // Model-mode header bits — resolved only when grouping by model.
    val parts = groupKey.split("|", limit = 2)
    val providerService = parts.getOrNull(0)?.let { AppService.findById(it) }
    val modelName = parts.getOrNull(1).orEmpty()
    val headerTitle = if (isModels) "Translation - model" else "Translation - type"
    val headerSubject = if (isModels) resolveModelLabel(groupKey)
        else translationTypeLabel(groupKey)

    // Items in this group, ordered running/pending → error → done.
    val rows = remember(run.items, mode, groupKey) {
        run.items.filter { translationGroupKey(it, mode) == groupKey }
            .sortedWith(
                compareBy(
                    { item ->
                        when (item.status) {
                            TranslationStatus.RUNNING,
                            TranslationStatus.PENDING -> 0
                            TranslationStatus.ERROR -> 1
                            TranslationStatus.DONE -> 2
                        }
                    },
                    { it.label.lowercase() },
                    // Stable tiebreaker — keeps L2 order (and the L3
                    // Prev/Next derived from it) stable across recompositions.
                    { it.id }
                )
            )
    }
    val total = rows.size
    val done = rows.count { it.status == TranslationStatus.DONE }
    val cost = rows.sumOf { it.costDollars }
    val allDone = total > 0 && done == total

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        // 👁 → matching View Translate screen for this run.
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            { holder.value = com.ai.ui.shared.ViewJump.TranslationRun(run.runId) }
        }
        TitleBar(
            helpTopic = "translation_run_l2",
            title = headerTitle,
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = headerSubject,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            onInfo = if (isModels && providerService != null && modelName.isNotBlank()) {
                { actions.onNavigateToModelInfo(providerService, modelName) }
            } else null
        )

        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                "$total item${if (total == 1) "" else "s"}",
                fontSize = 11.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f)
            )
            if (cost > 0.0) {
                Text(
                    formatTranslationCost(cost), fontSize = 11.sp,
                    color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                )
            }
        }

        if (rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (isModels) "No items for this model" else "No items for this type",
                    color = AppColors.TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.id }) { item ->
                    val fillColor = when (item.status) {
                        TranslationStatus.DONE -> AppColors.SuccessAccent.copy(alpha = 0.30f)
                        TranslationStatus.ERROR -> AppColors.DangerAccent.copy(alpha = 0.30f)
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
                        // Both modes: 3 uniform columns (same font size +
                        // colour). First column is the varying dimension —
                        // the type (without `translate/`) in Models mode,
                        // the model in Types mode. First + last render in
                        // full on one line; the middle translated value
                        // takes the remaining width and ellipsises. The row
                        // background fill still conveys done/error.
                        val cellSize = 13.sp
                        val cellColor = AppColors.TextPrimary
                        Text(
                            if (isModels) translationTypeLabel(item.traceType)
                            else com.ai.ui.shared.shortModelName(item.model.orEmpty()),
                            fontSize = cellSize, color = cellColor,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Text(
                            item.translatedText.orEmpty(),
                            fontSize = cellSize, color = cellColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 10.dp)
                        )
                        Text(
                            if (item.costDollars > 0.0) formatTranslationCost(item.costDollars) else "",
                            fontSize = cellSize, color = cellColor,
                            maxLines = 1, softWrap = false
                        )
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }
    }
}
