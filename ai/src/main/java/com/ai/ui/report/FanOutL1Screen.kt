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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    // True while a delete-run is in flight — drives the blocking
    // "Deleting Fan Out" popup so the screen stays put until the
    // run is really gone, then navigates back.
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
        if (com.ai.ui.shared.LocalSubjectToTitleBarMode.current == com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED) {
            Text(
                text = subject,
                fontSize = 18.sp, color = AppColors.Green,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Per-failure controls — visible only when at least one pair
        // errored. Both buttons follow the engine's throttle.
        if (run.errorCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { confirmRemoveFailed = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                ) { Text("Remove failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { confirmRestartFailed = true },
                    modifier = Modifier.weight(1f)
                ) { Text("Restart failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }

        // Icons row — "Icons" (opens the L1 Icons grid, gated on at
        // least one pair having a non-blank fan-out icon) and "Find
        // icons" (MAIN mode only, launches the fan-icons batch when
        // at least one DONE pair still lacks an icon). Both share a
        // single row; whichever is shown alone spans full width.
        val hasIcons = remember(run) { run.pairs.values.any { !it.icon.isNullOrBlank() } }
        val needsFindIcons = remember(run) {
            !isIconsMode && run.pairs.values.any {
                it.status == PairStatus.DONE && it.icon.isNullOrBlank()
            }
        }
        if (hasIcons || needsFindIcons) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (hasIcons) {
                    Button(
                        onClick = onOpenIcons,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                    ) { Text("Show icons", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
                if (needsFindIcons) {
                    Button(
                        onClick = { onLaunchFanIcons(run.key) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                    ) { Text("Find icons", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
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
                        Text(
                            icon, fontSize = 16.sp,
                            modifier = Modifier.width(24.dp)
                                .background(MaterialTheme.colorScheme.background)
                        )
                    } else {
                        Box(
                            Modifier.width(24.dp)
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
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
        // Row order: Running / Queued first, then Errored, then
        // Done at the bottom — each group sorted by model name.
        // Re-derives on every runningSet change so a row sinks the
        // moment its last pair lands.
        val answererKeys = run.answererKeys
        val sortedKeys = remember(run, runningSet, isIconsMode) {
            answererKeys.sortedWith(
                compareBy(
                    { ak ->
                        val pairs = run.pairs.values.filter { "${it.providerId}|${it.model}" == ak }
                        val total = pairs.size
                        val ok = if (isIconsMode) pairs.count { !it.icon.isNullOrBlank() }
                            else pairs.count { it.status == PairStatus.DONE }
                        val err = if (isIconsMode) pairs.count { !it.iconErrorMessage.isNullOrBlank() }
                            else pairs.count { it.status == PairStatus.ERROR }
                        val running = if (isIconsMode) pairs.count { it.iconStatus(runningSet) == PairStatus.RUNNING }
                            else pairs.count { it.effectiveStatus(runningSet) == PairStatus.RUNNING }
                        when {
                            running > 0 -> 0
                            total == 0 -> 0
                            ok == total -> 2
                            err > 0 -> 1
                            else -> 0
                        }
                    },
                    { ak -> resolveModelLabel(ak).lowercase() }
                )
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sortedKeys, key = { it }) { ak ->
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
                val progressFraction = if (total > 0) ok / total.toFloat() else 0f
                val progressColor = AppColors.Green.copy(alpha = 0.30f)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .drawBehind {
                            if (progressFraction > 0f) {
                                drawRect(
                                    color = progressColor,
                                    size = Size(size.width * progressFraction, size.height)
                                )
                            }
                        }
                        .padding(vertical = 6.dp)
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
                        Box(
                            Modifier.width(20.dp).background(progressColor),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedHourglass(fontSize = 16.sp)
                        }
                    } else {
                        Text(
                            icon, fontSize = 16.sp,
                            modifier = Modifier.width(20.dp).background(progressColor)
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            resolveModelLabel(ak),
                            fontSize = 14.sp, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
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
                // Status counts as a compact grid — one row of labels,
                // one row of numbers below. Total is the first column;
                // Throttled is always shown (dimmed at zero) so the
                // column doesn't shift in/out as pairs wait on a cap.
                val throttledHere = remember(run, throttledSet) { run.pairs.values.count { it.id in throttledSet } }
                val stats = buildList {
                    add(Triple("Total", run.totalPairs, AppColors.Blue))
                    add(Triple("Done", doneCount, AppColors.Green))
                    add(Triple("Errors", errorCount, AppColors.Red))
                    add(Triple("Running", runningCount, AppColors.Orange))
                    add(Triple("Throttled", throttledHere, if (throttledHere > 0) AppColors.Purple else AppColors.TextTertiary))
                    add(Triple("Queued", queuedCount, AppColors.TextTertiary))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    stats.forEach { (label, _, color) ->
                        Text(label, fontSize = 11.sp, color = color, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    stats.forEach { (_, value, color) ->
                        Text(value.toString(), fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                // Icon-chain tier tally — one compact row (the three
                // counts sum to Done).
                if (isIconsMode) {
                    val tier1 = run.pairs.values.count { it.iconWinningTier == 1 }
                    val tier2 = run.pairs.values.count { it.iconWinningTier == 2 }
                    val tier3 = run.pairs.values.count { it.iconWinningTier == 3 }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Tiers — chat $tier1 · one-shot $tier2 · fixed $tier3",
                        fontSize = 11.sp, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace
                    )
                }
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
            // ICONS mode wipes only the fan-icons; MAIN mode deletes
            // the whole fan-out run (which takes its icons with it).
            title = { Text(if (isIconsMode) "Delete fan-icons?" else "Delete fan-out run?") },
            text = {
                if (isIconsMode) {
                    Text("Drop every emoji and icon-chain cost for this run's ${run.totalPairs} pair${if (run.totalPairs == 1) "" else "s"}. The fan-out responses themselves are kept. Can't be undone.")
                } else {
                    val suffix = if (run.combinedReports.isNotEmpty()) " plus the combined-report follow-up" else ""
                    Text("Drop every per-pair response for this fan-out run$suffix — $totalRows rows. Can't be undone.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    // Await the Job before navigating — a big run's
                    // disk work takes a moment, and leaving early
                    // would show a half-done row on the report screen.
                    scope.launch {
                        (if (isIconsMode) actions.onClearFanIcons(run.key)
                         else actions.onDeleteRun(run.key))?.join()
                        deleting = false
                        onBack()
                    }
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }

    // Blocking progress popup shown while the run is being deleted.
    // Not dismissable (onDismissRequest is a no-op, no buttons) so
    // the user can't navigate away mid-delete.
    if (deleting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (isIconsMode) "Deleting Fan Icons" else "Deleting Fan Out") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedHourglass(fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isIconsMode) "Clearing the icons — this can take a moment."
                        else "Removing every row — this can take a moment.",
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = { }
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
