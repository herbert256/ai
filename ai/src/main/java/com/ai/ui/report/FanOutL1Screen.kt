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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.data.ApiCallCaps
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.FanOutRunKey
import com.ai.data.FanOutRunState
import com.ai.data.PairStatus
import com.ai.data.effectiveStatus
import com.ai.data.iconStatus
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ReloadConfirmationDialog
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.FanOutEngine

/**
 * L1 of the Fan Out drill-in: lists every answerer (provider,
 * model) that produced pairs, plus combined-reports + stats +
 * failure controls. Tapping a model row opens L2 in Responder
 * mode.
 *
 * Reads directly from the [FanOutRunState] snapshot passed in;
 * no polling, no derived-state caching needed.
 */
@Composable
internal fun FanOutL1Screen(
    engine: FanOutEngine,
    run: FanOutRunState,
    runningSet: Set<String>,
    throttledSet: Set<String>,
    actions: FanOutActions,
    mode: FanOutMode = FanOutMode.MAIN,
    onLaunchFanIcons: (FanOutRunKey) -> Unit = {},
    onOpenModel: (String) -> Unit,
    onOpenIcons: () -> Unit,
    onBack: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRerunComplete by remember { mutableStateOf(false) }
    var confirmRemoveFailed by remember { mutableStateOf(false) }
    var confirmRestartFailed by remember { mutableStateOf(false) }

    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name
    val isIconsMode = mode == FanOutMode.ICONS

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "secondary_fan_out_l1",
            title = if (isIconsMode) "Fan icons" else "Fan out",
            subject = subject,
            onBackClick = onBack,
            onReload = { confirmRerunComplete = true },
            onDelete = { confirmDelete = true }
        )

        // Per-failure controls — visible only when at least one pair
        // errored. Both buttons follow the engine's throttle.
        if (run.errorCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { confirmRemoveFailed = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Remove failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { confirmRestartFailed = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Restart failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }

        // Icons overview button — gated on at least one pair having
        // a non-blank fan-out icon. Opens the L1 Icons grid.
        val hasIcons = remember(run) { run.pairs.values.any { !it.icon.isNullOrBlank() } }
        if (hasIcons) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenIcons,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Icons", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }

        // "Find Icons" — only visible on MAIN mode and only when at
        // least one pair has a DONE response without an icon yet.
        // Launches the separate fan-icons batch.
        if (!isIconsMode) {
            val needsFindIcons = remember(run) {
                run.pairs.values.any {
                    it.status == PairStatus.DONE && it.icon.isNullOrBlank()
                }
            }
            if (needsFindIcons) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onLaunchFanIcons(run.key) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Find icons", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Top progress bar — while any pair is pending or running.
        val pending = run.effectiveQueuedCount(runningSet) + run.effectiveRunningCount(runningSet)
        if (pending > 0 && run.totalPairs > 0) {
            val finished = (run.doneCount + run.errorCount).toFloat() / run.totalPairs
            LinearProgressIndicator(
                progress = { finished },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AppColors.Orange,
                trackColor = AppColors.DividerDark
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Combined-reports section — fan-in / model-fan-in rows attached
        // to this run. Each row is tappable and opens the secondary
        // detail screen via actions.onOpenSecondary.
        if (run.combinedReports.isNotEmpty()) {
            Text(
                "Combined reports", fontSize = 13.sp, color = AppColors.Blue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            for (cr in run.combinedReports.sortedByDescending { it.timestamp }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable { actions.onOpenSecondary(cr.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (cr.status) {
                        PairStatus.ERROR -> "❌"
                        PairStatus.DONE -> "✅"
                        else -> null
                    }
                    if (icon != null) {
                        Text(icon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    } else {
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            AnimatedHourglass(fontSize = 16.sp)
                        }
                    }
                    Text(
                        "${cr.fanInPromptName} · ${cr.providerId} / ${cr.model}",
                        fontSize = 13.sp, color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (cr.totalCost > 0.0) {
                        Text(
                            formatCents(cr.totalCost), fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                        Text(">", fontSize = 16.sp, color = AppColors.Blue)
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Models", fontSize = 13.sp, color = AppColors.Blue,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Per-model row list. Grouped by (provider, model) so multi-
        // agent swarm members render as one row; per-row stats are
        // derived directly from the run's pairs map.
        val answererKeys = run.answererKeys
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(answererKeys, key = { it }) { ak ->
                val pairs = run.pairs.values.filter {
                    "${it.providerId}|${it.model}" == ak
                }
                // ICONS mode: classify by iconStatus (DONE iff
                // emoji landed, ERROR iff iconErrorMessage). MAIN
                // mode: classify by the main response status.
                val ok = if (isIconsMode)
                    pairs.count { !it.icon.isNullOrBlank() }
                    else pairs.count { it.status == PairStatus.DONE }
                val err = if (isIconsMode)
                    pairs.count { !it.iconErrorMessage.isNullOrBlank() }
                    else pairs.count { it.status == PairStatus.ERROR }
                val running = if (isIconsMode)
                    pairs.count { it.iconStatus(runningSet) == PairStatus.RUNNING }
                    else pairs.count { it.effectiveStatus(runningSet) == PairStatus.RUNNING }
                val total = pairs.size
                val cost = pairs.sumOf { it.totalCost }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clickable { onOpenModel(ak) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            resolveModelLabel(ak),
                            fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (running > 0 || (err > 0 && ok < total)) {
                            Text(
                                "$ok / $total · ❌ $err",
                                fontSize = 11.sp, color = AppColors.TextTertiary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (cost > 0.0) {
                        Text(
                            formatCents(cost), fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                        Text(">", fontSize = 16.sp, color = AppColors.Blue)
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
            if (run.totalCost > 0.0) {
                item(key = "l1-total-footer") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(20.dp))
                        Text(
                            "Total", fontSize = 14.sp, color = AppColors.Blue,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).padding(start = 4.dp)
                        )
                        Text(
                            formatCents(run.totalCost), fontSize = 11.sp,
                            color = AppColors.Blue, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Box(modifier = Modifier.width(16.dp))
                    }
                }
            }
        }

        // Stats panel — derives counts from the active mode's
        // status lens (iconStatus in ICONS, effectiveStatus in
        // MAIN). Visible whenever there are still pairs without
        // a final state (DONE / ERROR) in that lens.
        val doneCount = if (isIconsMode)
            run.pairs.values.count { !it.icon.isNullOrBlank() }
            else run.doneCount
        val errorCount = if (isIconsMode)
            run.pairs.values.count { !it.iconErrorMessage.isNullOrBlank() }
            else run.errorCount
        val runningCount = if (isIconsMode)
            run.pairs.values.count { it.iconStatus(runningSet) == PairStatus.RUNNING }
            else run.effectiveRunningCount(runningSet)
        val queuedCount = if (isIconsMode)
            run.pairs.values.count { it.iconStatus(runningSet) == PairStatus.PENDING }
            else run.effectiveQueuedCount(runningSet)
        if (run.totalPairs != doneCount) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row { Text("Total API calls", fontSize = 13.sp, color = AppColors.Blue, modifier = Modifier.weight(1f)); Text(run.totalPairs.toString(), color = AppColors.Blue) }
                Row { Text("Done", fontSize = 13.sp, color = AppColors.Green, modifier = Modifier.weight(1f)); Text(doneCount.toString(), color = AppColors.Green) }
                Row { Text("Errored", fontSize = 13.sp, color = if (errorCount > 0) AppColors.Red else AppColors.TextTertiary, modifier = Modifier.weight(1f)); Text(errorCount.toString(), color = if (errorCount > 0) AppColors.Red else AppColors.TextTertiary) }
                Row { Text("Running", fontSize = 13.sp, color = AppColors.Orange, modifier = Modifier.weight(1f)); Text(runningCount.toString(), color = AppColors.Orange) }
                val throttledHere = remember(run, throttledSet) { run.pairs.values.count { it.id in throttledSet } }
                if (throttledHere > 0) {
                    Row { Text("Throttled", fontSize = 13.sp, color = AppColors.Purple, modifier = Modifier.weight(1f)); Text(throttledHere.toString(), color = AppColors.Purple) }
                }
                Row { Text("Queued", fontSize = 13.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f)); Text(queuedCount.toString(), color = AppColors.TextTertiary) }
            }

            // Live caps panel — polls ApiCallCaps.snapshot() every
            // 500 ms and renders global / fan-out (or fan-icons in
            // ICONS mode) in-flight counts. Tells the user at a
            // glance whether the run is gated on a cap.
            val capsSnapshot by produceState(initialValue = ApiCallCaps.snapshot()) {
                while (true) {
                    value = ApiCallCaps.snapshot()
                    delay(500)
                }
            }
            val g = capsSnapshot
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    "Caps", fontSize = 11.sp, color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f)
                )
                val capsLine = if (isIconsMode)
                    "global ${g.globalInFlight}/${g.globalMax} · fan-icons ${g.fanIconsInFlight}/${g.fanIconsMax}"
                else
                    "global ${g.globalInFlight}/${g.globalMax} · fan-out ${g.fanOutInFlight}/${g.fanOutMax}"
                Text(
                    capsLine,
                    fontSize = 11.sp, color = AppColors.TextTertiary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // "Run a Fan in prompt" button at the bottom — keeps the
        // L1 page leading with the model list. Hidden in ICONS
        // mode (fan-in doesn't apply to the icon batch).
        if (!isIconsMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { actions.onRunFanIn(run.key) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Run a Fan in prompt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }
    }

    // -----------------------------------------------------------------
    // Confirmation dialogs
    // -----------------------------------------------------------------
    if (confirmRerunComplete) {
        ReloadConfirmationDialog(
            target = "",
            title = "Rerun the complete Fan out?",
            message = "Delete every fan-out row and start a fresh run. Combined-report follow-ups for this prompt will also be dropped.",
            confirmLabel = "Rerun",
            onConfirm = {
                confirmRerunComplete = false
                actions.onRerunComplete(run.key)
            },
            onDismiss = { confirmRerunComplete = false }
        )
    }

    if (confirmDelete) {
        val totalRows = run.totalPairs + run.combinedReports.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete fan-out run?") },
            text = {
                val suffix = if (run.combinedReports.isNotEmpty()) " plus the combined-report follow-up" else ""
                Text("Drop every per-pair response for this fan-out run$suffix — $totalRows rows. Can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onDeleteRun(run.key)
                    onBack()
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }

    if (confirmRestartFailed) {
        ReloadConfirmationDialog(
            target = "",
            title = "Restart failed items?",
            message = "Re-fires ${run.errorCount} failed fan-out call${if (run.errorCount == 1) "" else "s"} for this prompt. The runner's concurrency cap still applies, so larger failure sets surface as a mix of running and queued rows. Successful pairs are kept.",
            confirmLabel = "Restart",
            onConfirm = {
                confirmRestartFailed = false
                actions.onRestartFailedPairs(run.key)
            },
            onDismiss = { confirmRestartFailed = false }
        )
    }
    if (confirmRemoveFailed) {
        AlertDialog(
            onDismissRequest = { confirmRemoveFailed = false },
            title = { Text("Remove failed items?") },
            text = { Text("Drops ${run.errorCount} failed fan-out row${if (run.errorCount == 1) "" else "s"} for this prompt. No API calls are made. Successful pairs are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveFailed = false
                    actions.onRemoveFailedPairs(run.key)
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveFailed = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
