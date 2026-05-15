package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.ModelTestRunState
import com.ai.data.TestStatus
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents

/**
 * L1 of the Test-all-models drill-in: every active provider, the
 * stats panel + progress bar (both mirror Fan Out L1), and the
 * bottom button — "Test all models" (opens the provider picker) when
 * idle, "Cancel test" (stops the run) while one is in flight. Tapping
 * a provider row opens L2.
 *
 * On open, [run] is the last persisted run (or null when none has
 * been run yet).
 */
@Composable
internal fun ModelTestL1Screen(
    run: ModelTestRunState?,
    throttledKeys: Set<String>,
    actions: ModelTestActions,
    onOpenProvider: (String) -> Unit,
    onOpenSelect: () -> Unit,
    onBack: () -> Unit
) {
    // Benched = FAIL AND the model is on a >1h-429 cooldown. Observed
    // reactively; expiry checked from the snapshot.
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    fun benched(p: String, m: String): Boolean =
        (cooldowns["$p:$m"] ?: 0L) > System.currentTimeMillis()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "test_all_models_l1",
            title = "Test all models",
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (run == null) {
            // No run persisted yet — empty state + the launch button.
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No test run yet — tap Test all models to start.",
                    color = AppColors.TextTertiary, fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val benchCount = run.items.values.count {
                it.status == TestStatus.FAIL && benched(it.providerId, it.model)
            }
            val errorCount = run.errorCount - benchCount
            val throttledHere = remember(run, throttledKeys) {
                run.items.values.count { it.key in throttledKeys }
            }
            val queuedCount = (run.queuedCount - throttledHere).coerceAtLeast(0)

            // Stats panel — bleeds ~14dp into each side's 16dp padding
            // so the 8 columns get more width (mirrors Fan Out L1).
            Column(
                modifier = Modifier.layout { measurable, constraints ->
                    val extra = 28.dp.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = constraints.maxWidth + extra,
                            maxWidth = constraints.maxWidth + extra
                        )
                    )
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.place(-extra / 2, 0)
                    }
                }
            ) {
                val stats = buildList {
                    add(Triple("Total", run.total.toString(), AppColors.Blue))
                    add(Triple("Done", run.doneCount.toString(), AppColors.Green))
                    add(Triple("Errors", errorCount.toString(), AppColors.Red))
                    add(Triple("Bench", benchCount.toString(), AppColors.Purple))
                    add(Triple("Run", run.runningCount.toString(), AppColors.Orange))
                    add(Triple("Throttled", throttledHere.toString(), AppColors.Yellow))
                    add(Triple("Queue", queuedCount.toString(), AppColors.Brown))
                    add(Triple("Costs", formatCents(run.totalCost, decimals = 2), AppColors.Blue))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    stats.forEach { (label, _, color) ->
                        Text(label, fontSize = 11.sp, color = color, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    stats.forEach { (_, value, color) ->
                        Text(value, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
            }

            // Progress bar — shown while items are still pending or
            // running. Counts every PASS/FAIL as finished.
            val pending = run.queuedCount + run.runningCount
            if (pending > 0 && run.total > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                val finished = (run.doneCount + run.errorCount).toFloat() / run.total
                LinearProgressIndicator(
                    progress = { finished },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.Orange,
                    trackColor = AppColors.DividerDark
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Provider list. One row per active provider, sorted
            // running/queued → errored → done, each with a mini
            // per-provider progress fill.
            val sortedProviders = remember(run) {
                run.providerIds.sortedWith(
                    compareBy(
                        { pid ->
                            val items = run.itemsForProvider(pid)
                            val total = items.size
                            val ok = items.count { it.status == TestStatus.PASS }
                            val err = items.count { it.status == TestStatus.FAIL }
                            val running = items.count { it.status == TestStatus.RUNNING }
                            when {
                                running > 0 -> 0
                                total == 0 -> 0
                                ok == total -> 2
                                err > 0 -> 1
                                else -> 0
                            }
                        },
                        { pid -> (AppService.findById(pid)?.id ?: pid).lowercase() }
                    )
                )
            }
            // The run is finished once nothing is pending or running —
            // not when every model passed (that almost never happens,
            // there are always some dead models). When finished, drop
            // the per-row progress chrome for a calm final list.
            val allDone = run.total > 0 && (run.queuedCount + run.runningCount) == 0
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sortedProviders, key = { it }) { pid ->
                    val items = run.itemsForProvider(pid)
                    val total = items.size
                    val ok = items.count { it.status == TestStatus.PASS }
                    val err = items.count { it.status == TestStatus.FAIL }
                    val running = items.count { it.status == TestStatus.RUNNING }
                    val cost = items.sumOf { it.totalCost }
                    val label = AppService.findById(pid)?.id ?: pid
                    // Green fill = how much of this provider is *tested*
                    // (pass + fail), i.e. progress — not the pass rate.
                    val tested = ok + err
                    val progressFraction = if (total > 0) tested / total.toFloat() else 0f
                    val progressColor = AppColors.Green.copy(alpha = 0.30f)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .drawBehind {
                                if (!allDone && progressFraction > 0f) {
                                    drawRect(
                                        color = progressColor,
                                        size = Size(size.width * progressFraction, size.height)
                                    )
                                }
                            }
                            .padding(vertical = 6.dp)
                            .clickable { onOpenProvider(pid) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!allDone) {
                            val icon = when {
                                running > 0 -> "⏳"
                                total == 0 -> "🆕"
                                err > 0 && err == total -> "❌"
                                ok == total -> "✅"
                                err > 0 -> "❌"
                                else -> "🕓"
                            }
                            if (icon == "⏳") {
                                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                    AnimatedHourglass(fontSize = 16.sp)
                                }
                            } else {
                                Text(icon, fontSize = 16.sp, modifier = Modifier.width(20.dp))
                            }
                        }
                        Text(
                            label,
                            fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                        if (cost > 0.0) {
                            Text(
                                formatCents(cost), fontSize = 11.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
            }
        }

        // Bottom buttons — while a run looks in flight: "Check current
        // test run" (re-dispatches it if the coroutine actually died)
        // + "Cancel test" (red). Otherwise "Test all models" (blue,
        // opens the provider picker).
        val running = run != null && (run.queuedCount + run.runningCount) > 0
        Spacer(modifier = Modifier.height(8.dp))
        if (running) {
            Button(
                onClick = { actions.onCheckRun() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) {
                Text("Check current test run", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { actions.onCancelRun() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Red)
            ) {
                Text("Cancel test", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        } else {
            Button(
                onClick = onOpenSelect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) {
                Text("Test all models", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            // Re-probe just the previously-errored models — shown only
            // when there's an idle run with errors.
            if (run != null && run.errorCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { actions.onRerunErrors() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Orange)
                ) {
                    Text("Rerun Errors again (${run.errorCount})", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
