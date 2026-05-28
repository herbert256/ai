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
import com.ai.viewmodel.TranslationKind
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
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
                        TranslationStatus.DONE -> AppColors.Green.copy(alpha = 0.30f)
                        TranslationStatus.ERROR -> AppColors.Red.copy(alpha = 0.30f)
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
                        if (isModels) {
                            if (!allDone) {
                                when (item.status) {
                                    TranslationStatus.RUNNING ->
                                        AnimatedHourglass(fontSize = 16.sp, modifier = Modifier.width(24.dp).padding(end = 8.dp))
                                    else -> {
                                        val glyph = when (item.status) {
                                            TranslationStatus.DONE -> "✅"
                                            TranslationStatus.ERROR -> "❌"
                                            else -> "🕓"
                                        }
                                        Text(glyph, fontSize = 16.sp, modifier = Modifier.width(24.dp).padding(end = 8.dp))
                                    }
                                }
                            }
                            // Models mode: source-kind label (the model is
                            // constant down this list) + the item label.
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
                        } else {
                            // Types mode: 3 uniform columns — short model
                            // name | translated value | cost. First + last
                            // render in full on one line; the middle takes
                            // the remaining width and ellipsises. Row
                            // background fill still conveys done/error.
                            val cellSize = 13.sp
                            val cellColor = Color.White
                            Text(
                                item.model?.let { com.ai.ui.shared.shortModelName(it) }.orEmpty(),
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
                                formatCents(item.costDollars),
                                fontSize = cellSize, color = cellColor,
                                maxLines = 1, softWrap = false
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
internal fun translationKindLabel(kind: TranslationKind): String = when (kind) {
    TranslationKind.TITLE -> "title"
    TranslationKind.TITLE_LONG -> "long title"
    TranslationKind.AGENT_TITLE -> "model title"
    TranslationKind.FANOUT_TITLE -> "fan title"
    TranslationKind.PROMPT -> "prompt"
    TranslationKind.AGENT_RESPONSE -> "report"
    TranslationKind.META -> "meta"
}
