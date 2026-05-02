package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.ReportViewModel

/** Progress + result screen for a translation run. Watches the
 *  ReportViewModel.translationRun StateFlow; per-item rows show
 *  PENDING / RUNNING ⏳ / DONE ✅ + cost / ERROR ❌ as the runner ticks
 *  forward. When all rows finish the runner creates the new report and
 *  the "To translated report" button enables.
 */
@Composable
internal fun TranslationProgressScreen(
    runState: ReportViewModel.TranslationRunState?,
    onCancel: () -> Unit,
    onConsume: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onOpenTranslatedReport: (String) -> Unit
) {
    BackHandler { onBack() }
    val run = runState ?: run {
        // No active translation — bounce out. Should rarely happen
        // because the caller only opens this screen when a runner
        // exists, but covers the edge case where it gets cleared
        // while the user is still on the screen.
        BackHandler(enabled = true) { onBack() }
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(title = "Translate", onBackClick = onBack, onAiClick = onNavigateHome)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active translation.", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(title = "Translate → ${run.targetLanguageName}", onBackClick = onBack, onAiClick = onNavigateHome)
        Spacer(modifier = Modifier.height(8.dp))

        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "${run.completed} / ${run.total} done",
                    fontSize = 13.sp, color = Color.White
                )
                LinearProgressIndicator(
                    progress = { if (run.total > 0) run.completed.toFloat() / run.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = if (run.isFinished) AppColors.Green else AppColors.Purple
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Total cost so far: ${formatCents(run.totalCostCents / 100)} ¢",
                    fontSize = 12.sp, color = AppColors.Blue, fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(run.items, key = { it.id }) { item ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        ReportViewModel.TranslationStatus.PENDING -> Text("·", fontSize = 16.sp, color = AppColors.TextDim, modifier = Modifier.width(24.dp))
                        ReportViewModel.TranslationStatus.RUNNING -> {
                            val transition = rememberInfiniteTransition(label = "translate-hourglass")
                            val angle by transition.animateFloat(
                                initialValue = 0f, targetValue = 360f,
                                animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                                label = "translate-hourglass-rot"
                            )
                            Text("⏳", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
                        }
                        ReportViewModel.TranslationStatus.DONE -> Text("✅", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        ReportViewModel.TranslationStatus.ERROR -> Text("❌", fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.label, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when (item.kind) {
                            ReportViewModel.TranslationKind.PROMPT -> "prompt"
                            ReportViewModel.TranslationKind.AGENT_RESPONSE -> "report response"
                            ReportViewModel.TranslationKind.SUMMARY -> "summary"
                            ReportViewModel.TranslationKind.COMPARE -> "compare"
                        }
                        if (item.errorMessage != null) {
                            Text("$sub · ${item.errorMessage}", fontSize = 11.sp, color = AppColors.Red,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            Text(sub, fontSize = 11.sp, color = AppColors.TextTertiary)
                        }
                    }
                    if (item.status == ReportViewModel.TranslationStatus.DONE || item.status == ReportViewModel.TranslationStatus.ERROR) {
                        Text(
                            "${formatCents(item.costCents / 100)} ¢",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            color = AppColors.TextTertiary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run.isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
            val newId = run.newReportId
            Button(
                onClick = {
                    if (newId != null) {
                        onConsume()
                        onOpenTranslatedReport(newId)
                    }
                },
                enabled = newId != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
            ) {
                Text(
                    if (newId != null) "To translated report" else "Translating…",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false
                )
            }
        }
    }
}
