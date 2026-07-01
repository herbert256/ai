package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.FanOutRunKey
import com.ai.data.FanOutRunState
import com.ai.data.PairState
import com.ai.data.PairStatus
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStorage
import com.ai.data.UserNote
import com.ai.data.notesFor
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * mode. The "Fan Meta" button cross-links to the separate Fan Meta
 * screen.
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
    onLaunchFanMeta: (FanOutRunKey) -> Unit = {},
    onShowFanMeta: () -> Unit = {},
    onOpenModel: (String) -> Unit,
    onOpenStats: () -> Unit = {},
    onBack: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRerunComplete by remember { mutableStateOf(false) }
    var confirmStartTitles by remember { mutableStateOf(false) }
    // Closing the Second-results list returns to Manage a Report (the list
    // mount provides this as LocalNavigateToCurrentReport).
    val toManageReport = com.ai.ui.shared.LocalNavigateToCurrentReport.current

    // ✍️ user notes for this fan-out run. Target = the run key.
    val notesContext = LocalContext.current
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(run.reportId, "FANOUT_RUN", run.key, noteEdit!!) { noteEdit = null }
        return
    }
    val noteDataVersion by ReportDataVersion.versionFor(run.reportId).collectAsState()
    val fanRunNotes by produceState(emptyList<UserNote>(), run.reportId, run.key, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(notesContext, run.reportId)?.notesFor("FANOUT_RUN", run.key) ?: emptyList()
        }
    }

    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name

    fun pairCost(p: PairState): Double = (p.inputCost ?: 0.0) + (p.outputCost ?: 0.0)

    // Long bench = the pair's model is on a >1h-429 cooldown (Gemini daily
    // quota / billing). Drives the failure-control buttons (Remove benched).
    val cooldowns by com.ai.data.ModelCooldownStore.cooldowns.collectAsState()
    fun benched(p: String?, m: String?): Boolean =
        p != null && m != null && (cooldowns["$p:$m"] ?: 0L) > System.currentTimeMillis()
    // Short bench = the model is parked on a transient 429/529 backoff and its
    // items will re-queue when it lifts. Drives the live Bench stat column —
    // takes precedence over Run / Wait / Queue so a parked item shows there.
    val shortBenches by com.ai.data.ModelCooldownStore.shortBenches.collectAsState()
    fun shortBenched(p: String, m: String): Boolean =
        (shortBenches["$p:$m"] ?: 0L) > System.currentTimeMillis()

    // 🐞 deep-link target — the fan-out that created the rows (runId).
    val l1RunId = run.pairs.values.firstNotNullOfOrNull { it.runId }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "secondary_fan_out_l1",
            title = "Fan out",
            subject = subject,
            onBackClick = onBack,
            onReload = { confirmRerunComplete = true },
            onTrace = if (l1RunId != null && com.ai.data.ApiTracer.ladybugLinksEnabled)
                { { actions.onNavigateToTraceRunList(l1RunId) } } else null,
            // The HTTP stats are in-memory only (RunHttpStats), so show the icon
            // only when this run has recorded responses this session — after an
            // app restart there's nothing to show.
            onStats = if (l1RunId != null && com.ai.data.RunHttpStats.hasRun(l1RunId)) onOpenStats else null,
            onDelete = { confirmDelete = true },
            onAdd = actions.onCreateNewFanOut,
            onAddNote = { noteEdit = NoteEdit.Add }
        )
        UserNotesSection(
            reportId = run.reportId,
            notes = fanRunNotes,
            onEdit = { noteEdit = NoteEdit.Edit(it.id, it.text) }
        )

        // Status counts + cost — pinned at the top of the page so
        // they stay put as the model list scrolls; kept visible even
        // once every pair is done.
        // Fixed-model batch (category A): any non-done pair of a
        // short-benched model is parked in Bench.
        val summary = deriveBatchSummary(
            items = run.pairs.values,
            idOf = { it.id },
            statusOf = { it.status },
            throttledIds = throttledSet,
            family = BatchFamily.FIXED_MODEL,
            benchedOf = { shortBenched(it.providerId, it.model) },
        )
        val counts = summary.counts
        val doneCount = counts.done
        val errorCount = summary.displayError
        val benchCount = counts.bench
        val runningCount = counts.running
        val throttledHere = counts.wait
        val queuedCount = counts.queued
        // Whole run finished cleanly — every row would otherwise show
        // ✅ on a full green fill. Drop both per row so a completed
        // run reads calmly instead of as a wall of check marks.
        val allDone = run.totalPairs > 0 && doneCount == run.totalPairs
        Spacer(modifier = Modifier.height(8.dp))
        // Fixed-model batch (category A): a benched answerer can't be
        // substituted, so its errored pairs get their own Bench column
        // (between Run and Wait) instead of folding into Error.
        BatchStatsRow(buildList {
            add(Triple("Total", run.totalPairs.toString(), AppColors.InfoAccent))
            add(Triple("Done", doneCount.toString(), AppColors.SuccessAccent))
            add(Triple("Error", errorCount.toString(), AppColors.DangerAccent))
            add(Triple("Run", runningCount.toString(), AppColors.WarningAccent))
            if (summary.showBenchColumn) {
                add(Triple("Bench", benchCount.toString(), AppColors.PrimaryAccent))
            }
            add(Triple("Wait", throttledHere.toString(), AppColors.CautionAccent))
            add(Triple("Queue", queuedCount.toString(), AppColors.QueueAccent))
            add(Triple("Costs", "${formatCents(run.pairs.values.sumOf { pairCost(it) }, decimals = 2)} ¢", AppColors.InfoAccent))
        })

        val hasFanMeta = remember(run) {
            run.pairs.values.any { !it.title.isNullOrBlank() || !it.titleErrorMessage.isNullOrBlank() }
        }

        // Per-failure controls (Remove/Restart/benched) removed — a new
        // failure-handling UX is coming.

        Spacer(modifier = Modifier.height(8.dp))

        // Top progress bar — the fan-out responses. Keep it up while the
        // shared batch policy says work is still outstanding.
        if (summary.activeOutstanding && run.totalPairs > 0 && !run.cancelled) {
            val finished = (doneCount + errorCount).toFloat() / run.totalPairs
            LinearProgressIndicator(
                progress = { finished },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = AppColors.WarningAccent,
                trackColor = AppColors.DividerDark
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Combined-reports section — fan-in / model-fan-in rows attached
        // to this run. Each row is tappable and opens the secondary
        // detail screen via actions.onOpenSecondary.
        if (run.combinedReports.isNotEmpty()) {
            Text(
                "Combined reports", fontSize = 13.sp, color = AppColors.InfoAccent,
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
                        PairStatus.ERROR -> com.ai.data.MetadataIconsHolder.current.statusFailed
                        PairStatus.DONE -> com.ai.data.MetadataIconsHolder.current.statusDone
                        else -> null
                    }
                    if (icon != null) {
                        Text(
                            icon, fontSize = 16.sp,
                            modifier = Modifier.width(24.dp)
                                .background(AppColors.AppBackground)
                        )
                    } else {
                        Box(
                            Modifier.width(24.dp)
                                .background(AppColors.AppBackground),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedHourglass(fontSize = 16.sp)
                        }
                    }
                    Text(
                        "${cr.fanInPromptName} · ${cr.providerId} / ${com.ai.ui.shared.shortModelName(cr.model)}",
                        fontSize = 13.sp, color = AppColors.TextPrimary,
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
                "Models", fontSize = 13.sp, color = AppColors.InfoAccent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Per-model row list. Grouped by (provider, model) so multi-
        // agent swarm members render as one row; per-row stats are
        // derived directly from the run's pairs map. Stable order by
        // model name (NOT status) so a row keeps its place as it
        // finishes instead of jumping to the bottom mid-run.
        val answererKeys = remember(run) {
            run.answererKeys.sortedWith(compareBy { ak -> ak.substringAfter('|').lowercase() })
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (answererKeys.isEmpty()) {
                item {
                    Text(
                        "One moment, collecting information…",
                        color = AppColors.TextSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            items(answererKeys, key = { it }) { ak ->
                val pairs = run.pairs.values.filter { "${it.providerId}|${it.model}" == ak }
                val ok = pairs.count { it.status == PairStatus.DONE }
                val err = pairs.count { it.status == PairStatus.ERROR }
                val running = pairs.count { it.status == PairStatus.RUNNING }
                val total = pairs.size
                val cost = pairs.sumOf { pairCost(it) }
                // Failed pairs count toward the bar too — without
                // them the row would stall at < 100 % when every
                // remaining pair errored out.
                val progressFraction = if (total > 0) (ok + err).toFloat() / total else 0f
                val progressColor = AppColors.SuccessAccent.copy(alpha = 0.30f)
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
                    if (!allDone) {
                        val icon = when {
                            running > 0 -> "⏳"
                            total == 0 -> com.ai.data.MetadataIconsHolder.current.add
                            err > 0 && err == total -> com.ai.data.MetadataIconsHolder.current.statusFailed
                            ok == total -> com.ai.data.MetadataIconsHolder.current.statusDone
                            err > 0 -> com.ai.data.MetadataIconsHolder.current.statusFailed
                            else -> com.ai.data.MetadataIconsHolder.current.clockQueued
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
                            fontSize = 14.sp, color = AppColors.TextPrimary,
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

        // Bottom button row — the Fan Meta cross-link + the fan-in
        // launcher.
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The Fan Meta entry button. Opens the existing batch
            // (cross-link) or confirms a fresh job. Hidden when the
            // grand-master metadata switch is off.
            if (com.ai.ui.shared.LocalMetadataEnabled.current) {
                OutlinedButton(
                    onClick = { if (hasFanMeta) onShowFanMeta() else confirmStartTitles = true },
                    modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Fan Meta", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { actions.onRunFanIn(run.key) },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Run a Fan in prompt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
    }

    // -----------------------------------------------------------------
    // Confirmation dialogs
    // -----------------------------------------------------------------
    if (confirmRerunComplete) {
        ReloadConfirmationDialog(
            target = "",
            title = "Rerun the complete Fan out?",
            message = "Delete every fan-out row and start a fresh run. Combined-report (fan-in) rows for this prompt are kept and re-attached.",
            confirmLabel = "Rerun",
            onConfirm = {
                confirmRerunComplete = false
                actions.onRerunComplete(run.key)
            },
            onDismiss = { confirmRerunComplete = false }
        )
    }

    // "Fan Meta" tapped with no fan-meta run yet — confirm before
    // starting the job. "Yes" launches it and cross-links to the Fan
    // Meta screen; "No" stays put.
    if (confirmStartTitles) {
        AlertDialog(
            onDismissRequest = { confirmStartTitles = false },
            title = { Text("Start Fan Meta job") },
            text = { Text("No Fan Meta (titles + icons) generated for this fan-out yet. Start the job now?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStartTitles = false
                    onLaunchFanMeta(run.key)
                    onShowFanMeta()
                }) { Text("Yes", maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartTitles = false }) { Text("No", maxLines = 1, softWrap = false) }
            }
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
                    // Fire-and-forget: the engine stops the batch, hides the
                    // Second-results row and drops the run immediately, then
                    // does the slow per-pair disk deletes in the background.
                    // Go straight to Manage a Report — no blocking popup.
                    actions.onDeleteRun(run.key)
                    (toManageReport ?: onBack).invoke()
                }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }

}
