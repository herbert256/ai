package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.FanOutRunKey
import com.ai.data.FanOutRunState
import com.ai.data.PairState
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
    /** Switch the screen to ICONS mode without launching anything —
     *  used by the mode-toggle "Icons" button when a fan-icons run
     *  already exists for this fan-out. */
    onShowFanIcons: () -> Unit = {},
    /** Switch the screen back to MAIN mode — the mode-toggle
     *  "Responses" button in ICONS mode. */
    onShowResponses: () -> Unit = {},
    onOpenModel: (String) -> Unit,
    onOpenIcons: () -> Unit,
    onBack: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRerunComplete by remember { mutableStateOf(false) }
    var confirmRemoveFailed by remember { mutableStateOf(false) }
    var confirmRemoveBenched by remember { mutableStateOf(false) }
    var confirmRestartFailed by remember { mutableStateOf(false) }
    var confirmStartIcons by remember { mutableStateOf(false) }
    // True while a delete-run is in flight — drives the blocking
    // "Deleting Fan Out" popup so the screen stays put until the
    // run is really gone, then navigates back.
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name
    val isIconsMode = mode == FanOutMode.ICONS

    // Per-pair cost for the active mode only: MAIN counts the
    // fan-out response call, ICONS the icon-chain spend. (PairState
    // .totalCost lumps both, so it can't be used as-is here.)
    fun pairCost(p: PairState): Double = if (isIconsMode)
        p.iconInputCost + p.iconOutputCost
    else (p.inputCost ?: 0.0) + (p.outputCost ?: 0.0)

    // Benched = errored AND the pair's model is on a >1h-429
    // cooldown. Observed reactively so the Bench count updates as
    // cooldowns lift; expiry is checked from the snapshot rather than
    // the lazily-mutating ModelCooldownStore.isUnavailable. Hoisted
    // to composable scope so the confirm dialogs can use it too.
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    fun benched(p: String?, m: String?): Boolean =
        p != null && m != null && (cooldowns["$p:$m"] ?: 0L) > System.currentTimeMillis()

    // 🐞 deep-link target: in ICONS mode the L1 spans the most
    // recent fan-icons sweep on these pairs (iconRunId); in MAIN
    // mode the run is the fan-out that created the rows (runId).
    // First non-null wins so a sparse / legacy run still surfaces
    // the icon for sibling rows that were stamped.
    val l1RunId = if (isIconsMode)
        run.pairs.values.firstNotNullOfOrNull { it.iconRunId }
        else run.pairs.values.firstNotNullOfOrNull { it.runId }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "secondary_fan_out_l1",
            title = if (isIconsMode) "Fan icons" else "Fan out",
            subject = subject,
            onBackClick = onBack,
            onReload = { confirmRerunComplete = true },
            onTrace = if (l1RunId != null && com.ai.data.ApiTracer.isTracingEnabled)
                { { actions.onNavigateToTraceRunList(l1RunId) } } else null,
            onDelete = { confirmDelete = true }
        )
        com.ai.ui.shared.HardcodedSubjectRow(subject)

        // Status counts + cost — pinned at the top of the page so
        // they stay put as the model list scrolls; kept visible even
        // once every pair is done. Counts use the active mode's
        // status lens (iconStatus in ICONS, effectiveStatus in MAIN).
        val doneCount = if (isIconsMode)
            run.pairs.values.count { !it.icon.isNullOrBlank() }
            else run.doneCount
        // Errors and Bench split the errored set — a benched entry
        // will recover once its cooldown lifts, so it's counted
        // separately instead of under Errors.
        // Use iconStatus() in ICONS mode — it folds in the "no
        // content but finished response" case (durationMs set,
        // content blank) which lands as ERROR for icon purposes
        // even though no iconErrorMessage was written. Counting on
        // iconErrorMessage alone would silently drop those pairs
        // from every counter and leave them unaccounted in the L1
        // stats row (Total - Done - Errors - ... mismatch).
        val errorCount = if (isIconsMode)
            run.pairs.values.count { it.iconStatus(runningSet) == PairStatus.ERROR && !benched(it.providerId, it.model) }
            else run.pairs.values.count { it.status == PairStatus.ERROR && !benched(it.providerId, it.model) }
        val benchCount = if (isIconsMode)
            run.pairs.values.count { it.iconStatus(runningSet) == PairStatus.ERROR && benched(it.providerId, it.model) }
            else run.pairs.values.count { it.status == PairStatus.ERROR && benched(it.providerId, it.model) }
        val runningCount = if (isIconsMode)
            run.pairs.values.count { it.iconStatus(runningSet) == PairStatus.RUNNING }
            else run.effectiveRunningCount(runningSet)
        val throttledHere = remember(run, throttledSet) { run.pairs.values.count { it.id in throttledSet } }
        // Queue excludes pairs that are actively blocked on a host
        // rate-limit cap — those are reported in the Throttled column
        // instead, so the two columns don't double-count the same
        // pair (a throttled pair is still PENDING by status).
        val queuedCount = if (isIconsMode)
            run.pairs.values.count { it.iconStatus(runningSet) == PairStatus.PENDING && it.id !in throttledSet }
            else run.pairs.values.count { it.effectiveStatus(runningSet) == PairStatus.PENDING && it.id !in throttledSet }
        // Whole run finished cleanly — every row would otherwise show
        // ✅ on a full green fill. Drop both per row so a completed
        // run reads calmly instead of as a wall of check marks.
        val allDone = run.totalPairs > 0 && doneCount == run.totalPairs
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            // Bleed the stats block ~14dp into the screen's 16dp side
            // padding on each side — with 8 columns the cells are tight,
            // so the stats get the extra width while the rest of the
            // page keeps its margins. Reports the un-widened width to
            // the parent Column so siblings still stack normally.
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
            // One row of labels, one row of values below. Total is
            // the first column; Throttled is always shown (dimmed at
            // zero) so columns don't shift as pairs wait on a cap;
            // Costs is the run's total spend in cents, 2 decimals.
            val stats = buildList {
                add(Triple("Total", run.totalPairs.toString(), AppColors.Blue))
                add(Triple("Done", doneCount.toString(), AppColors.Green))
                add(Triple("Errors", errorCount.toString(), AppColors.Red))
                add(Triple("Bench", benchCount.toString(), AppColors.Purple))
                add(Triple("Run", runningCount.toString(), AppColors.Orange))
                add(Triple("Throttled", throttledHere.toString(), AppColors.Yellow))
                add(Triple("Queue", queuedCount.toString(), AppColors.Brown))
                add(Triple("Costs", formatCents(run.pairs.values.sumOf { pairCost(it) }, decimals = 2), AppColors.Blue))
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

        // Mode-toggle moved to the bottom button row (next to Run a
        // Fan in prompt / Show icons) so the stats area stays tight.
        val hasFanIcons = remember(run) {
            run.pairs.values.any { !it.icon.isNullOrBlank() || !it.iconErrorMessage.isNullOrBlank() }
        }

        // Per-failure controls. The remove/restart buttons act on the
        // MAIN-mode errored pairs (status == ERROR) regardless of view
        // mode — same as removeFailedPairs / removeBenchedPairs — so
        // they're gated on MAIN-scoped counts, split into genuine
        // errors vs. benched (will-recover) pairs.
        val mainBenched = run.pairs.values.count {
            it.status == PairStatus.ERROR && benched(it.providerId, it.model)
        }
        val mainErrored = run.pairs.values.count {
            it.status == PairStatus.ERROR && !benched(it.providerId, it.model)
        }
        if (mainErrored > 0 || mainBenched > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mainErrored > 0) {
                    Button(
                        onClick = { confirmRemoveFailed = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                    ) { Text("Remove failed", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    Button(
                        onClick = { confirmRestartFailed = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Restart failed", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
                if (mainBenched > 0) {
                    Button(
                        onClick = { confirmRemoveBenched = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
                    ) { Text("Remove benched", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
            }
        }

        // ICONS-mode error controls. Surface whenever there's at
        // least one errored pair, regardless of in-flight state —
        // the user can clean up errored pairs while other pairs are
        // still being processed. Three buttons mirror the MAIN-mode
        // controls but operate on the icon-chain only — dropping
        // just the iconError sentinel + emoji state, not the
        // underlying pair rows.
        val iconHasErrors = isIconsMode && errorCount > 0
        var showIconErrorsDialog by remember { mutableStateOf(false) }
        if (iconHasErrors) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Three buttons share the row width via weight(1f).
                // The Material 3 default ContentPadding of 24 dp
                // horizontal eats too much room — on narrow phones
                // the inner Text was clipped mid-word. Drop to 6 dp
                // so the labels always render in full.
                val tightPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                Button(
                    onClick = { actions.onClearFanIconErrors(run.key) },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark)
                ) { Text("Remove errors", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { actions.onRestartFanIconErrors(run.key) },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding
                ) { Text("Restart errors", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                OutlinedButton(
                    onClick = { showIconErrorsDialog = true },
                    modifier = Modifier.weight(1f),
                    contentPadding = tightPadding,
                    colors = AppColors.outlinedButtonColors()
                ) { Text("View errors", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        if (showIconErrorsDialog) {
            val errored = remember(run, errorCount) {
                run.pairs.values
                    .filter { !it.iconErrorMessage.isNullOrBlank() && !benched(it.providerId, it.model) }
                    .sortedWith(compareBy({ it.providerId }, { it.model }))
            }
            AlertDialog(
                onDismissRequest = { showIconErrorsDialog = false },
                title = { Text("Fan icons — errors (${errored.size})") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())) {
                        errored.forEach { p ->
                            Text(
                                "${p.providerId} / ${com.ai.ui.shared.shortModelName(p.model)}",
                                fontSize = 13.sp, color = AppColors.Blue,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                p.iconErrorMessage.orEmpty(),
                                fontSize = 12.sp, color = AppColors.TextTertiary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showIconErrorsDialog = false }) {
                        Text("Close", maxLines = 1, softWrap = false)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Top progress bar — for the active mode (fan-out responses
        // in MAIN, the icon chain in ICONS). Uses the mode-aware
        // counts so it isn't stuck at 100% in ICONS mode just
        // because every fan-out response already landed.
        val pending = queuedCount + runningCount
        if (pending > 0 && run.totalPairs > 0) {
            val finished = (doneCount + errorCount).toFloat() / run.totalPairs
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
                        "${cr.fanInPromptName} · ${cr.providerId} / ${com.ai.ui.shared.shortModelName(cr.model)}",
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
                    { ak -> ak.substringAfter('|').lowercase() }
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
                val cost = pairs.sumOf { pairCost(it) }
                // Failed pairs count toward the bar too — without
                // them the row would stall at < 100 % when every
                // remaining pair errored out.
                val progressFraction = if (total > 0) (ok + err).toFloat() / total else 0f
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
                        .clickable { onOpenModel(ak) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status glyph — skipped entirely once the whole
                    // run is done (every row would be ✅; see allDone).
                    // No explicit background on the glyph either — the
                    // row's drawBehind progress fill already paints it
                    // (the icon sits at x=0); a second .background
                    // doubled the green.
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
                            Box(
                                Modifier.width(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AnimatedHourglass(fontSize = 16.sp)
                            }
                        } else {
                            Text(
                                icon, fontSize = 16.sp,
                                modifier = Modifier.width(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            // Model name only — no provider prefix.
                            com.ai.ui.shared.shortModelName(ak.substringAfter('|')),
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
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
        }

        // Bottom button row — mode toggle + the action button for the
        // current mode share a Row at the foot of the screen so the
        // L1 page leads with the model list and ends with everything
        // the user might tap next.
        Spacer(modifier = Modifier.height(8.dp))
        val hasIcons = remember(run) { run.pairs.values.any { !it.icon.isNullOrBlank() } }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (isIconsMode) onShowResponses()
                    else if (hasFanIcons) onShowFanIcons()
                    else confirmStartIcons = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
            ) {
                Text(
                    if (isIconsMode) "Responses" else "Icons",
                    fontSize = 12.sp, maxLines = 1, softWrap = false
                )
            }
            if (!isIconsMode) {
                Button(
                    onClick = { actions.onRunFanIn(run.key) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Run a Fan in prompt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            } else if (hasIcons) {
                Button(
                    onClick = onOpenIcons,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue)
                ) { Text("Show icons", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
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

    // "Icons" tapped in MAIN mode with no fan-icons run yet — confirm
    // before starting the job. "Yes" launches it and switches to
    // ICONS mode; "No" stays put in MAIN mode.
    if (confirmStartIcons) {
        AlertDialog(
            onDismissRequest = { confirmStartIcons = false },
            title = { Text("Start Icons job") },
            text = { Text("No fan-icons have been generated for this fan-out yet. Start the icons job now?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStartIcons = false
                    onLaunchFanIcons(run.key)
                }) { Text("Yes", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartIcons = false }) { Text("No", maxLines = 1, softWrap = false) }
            }
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
        val n = run.pairs.values.count { it.status == PairStatus.ERROR && !benched(it.providerId, it.model) }
        AlertDialog(
            onDismissRequest = { confirmRemoveFailed = false },
            title = { Text("Remove failed items?") },
            text = { Text("Drops $n failed fan-out row${if (n == 1) "" else "s"} for this prompt. Benched (rate-limited) rows are kept — use Remove benched for those. No API calls are made. Successful pairs are kept.") },
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
    if (confirmRemoveBenched) {
        val n = run.pairs.values.count { it.status == PairStatus.ERROR && benched(it.providerId, it.model) }
        AlertDialog(
            onDismissRequest = { confirmRemoveBenched = false },
            title = { Text("Remove benched items?") },
            text = { Text("Drops $n benched fan-out row${if (n == 1) "" else "s"} — pairs whose model is on a rate-limit cooldown. No API calls are made. Genuine errors and successful pairs are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveBenched = false
                    actions.onRemoveBenchedPairs(run.key)
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveBenched = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
