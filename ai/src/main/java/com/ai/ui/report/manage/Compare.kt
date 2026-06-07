package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.CompareCellState
import com.ai.data.CompareCellStatus
import com.ai.data.CompareRunState
import com.ai.data.Report
import com.ai.data.ReportAgent
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.barTitle
import com.ai.data.secondaryPromptDisplayName
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.viewmodel.CompareEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===================================================================
// Selection flow — page 1 (meta items) → page 2 (prompt). Rendered as
// early-return overlays from ReportRunScreen; on Run the engine starts
// and the L1 overlay opens.
// ===================================================================

/** Page 1 — pick ONE plain-meta result to score answers against. Tapping a
 *  row goes straight to the prompt page (1×1: one meta, one prompt). */
@Composable
fun CompareSelectMetaScreen(
    metaItems: List<SecondaryResult>,
    aiSettings: Settings,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "compare_select_meta",
            title = "Compare with meta",
            subject = "Pick a meta result to score answers against",
            reportIcon = com.ai.data.MetadataIconsHolder.current.compare,
            onBackClick = onBack
        )
        if (metaItems.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No meta results to compare against.", color = AppColors.TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(metaItems, key = { it.id }) { row ->
                    val name = secondaryPromptDisplayName(row.metaPromptName ?: "meta")
                    val preview = row.content?.trim()?.take(90)?.replace("\n", " ").orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onPick(row.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(com.ai.data.MetadataIconsHolder.current.compare, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = AppColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(preview, color = AppColors.TextTertiary, fontSize = 11.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
                }
            }
            Text(
                "Tip: similarity reads best against a Summarize / Synthesize meta — a Compare meta is an analysis, not an answer.",
                color = AppColors.TextTertiary, fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

// Compare no longer asks for a comparison prompt — it always runs the
// meta-compare prompt with the same NAME as the picked meta item (resolved in
// Run.kt). The former CompareSelectPromptScreen (page 2) was removed.

// ===================================================================
// Manage row — appears only once a compare run exists for the report.
// ===================================================================

@Composable
fun CompareManageRow() {
    val engine = com.ai.ui.shared.LocalCompareEngine.current ?: return
    val openState = com.ai.ui.shared.LocalCompareOpenState.current
    val reportId = com.ai.ui.shared.LocalCurrentReportIdForSwipe.current ?: return
    val context = LocalContext.current
    LaunchedEffect(reportId) {
        if (engine.runByKey(reportId) == null) {
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
    }
    val runs by engine.runs.collectAsState()
    val run = runs[reportId] ?: return // no compare run on this report → no row

    // Static label — the title of the prompt this batch ran, NOT a live
    // size / done count (the row no longer ticks as cells complete).
    val rowText = run.comparePrompt.title.takeIf { it.isNotBlank() } ?: run.comparePrompt.name
    val compareIcon = com.ai.ui.shared.LocalMetadataIcons.current.compare
        .takeIf { it.isNotBlank() } ?: com.ai.data.MetadataDefaults.COMPARE
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { openState?.value = reportId },
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                !run.allTerminal -> Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    AnimatedHourglass(fontSize = 16.sp)
                }
                run.errorCount > 0 -> Text(com.ai.data.MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                else -> Text(compareIcon, fontSize = 16.sp, modifier = Modifier.width(24.dp))
            }
            RowTypeCell("compare")
            Text(
                text = rowText, color = AppColors.TextPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (run.totalCost > 0.0) {
                Text(formatCents(run.totalCost), fontSize = 10.sp,
                    color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
            }
        }
        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
    }
}

/** Overlay-mount helper called by ReportsScreenNav when the open-state var
 *  is non-null (mirrors TournamentOverlay). */
@Composable
fun CompareOverlay(reportId: String, engine: CompareEngine, onClose: () -> Unit) {
    CompositionLocalProvider(com.ai.ui.shared.LocalNavigateToCurrentReport provides onClose) {
        CompareScreen(engine, reportId, onClose)
    }
}

// ===================================================================
// Drill-in: L1 (stats + per-model list) → L2 (group) → L3 (cell)
// ===================================================================

@Composable
fun CompareScreen(engine: CompareEngine, reportId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val runs by engine.runs.collectAsState()
    val throttled by engine.throttledCells.collectAsState()
    val run = runs[reportId]

    // One-shot seed from disk on entry; the runner mirrors every cell
    // result into the engine flow in a NonCancellable finally (runOneCell),
    // so the L1 counters stay live without a 3s disk poll.
    LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }

    val report by produceState<Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val agents = report?.agents?.associateBy { it.agentId }.orEmpty()
    val metaRowsById by produceState(initialValue = emptyMap<String, SecondaryResult>(), reportId) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.META).associateBy { it.id }
        }
    }
    val localReportTitle = com.ai.ui.shared.LocalReportTitle.current
    val reportTitle = report?.barTitle?.takeIf { it.isNotBlank() }
        ?: localReportTitle?.takeIf { it.isNotBlank() }
        ?: "Report"
    val reportIcon = report?.icon?.takeIf { it.isNotBlank() }
        ?: com.ai.ui.shared.LocalMetadataIcons.current.reportIcon

    // Distinct meta label per selected meta item — disambiguate same-named
    // metas by the producing model.
    val metaLabels: Map<String, String> = remember(metaRowsById, run?.metaResultIds) {
        val ids = run?.metaResultIds ?: emptyList()
        fun base(r: SecondaryResult) = secondaryPromptDisplayName(r.metaPromptName ?: "meta")
        val counts = ids.mapNotNull { metaRowsById[it] }.groupingBy { base(it) }.eachCount()
        ids.associateWith { id ->
            val r = metaRowsById[id] ?: return@associateWith "meta"
            val n = base(r)
            if ((counts[n] ?: 0) > 1) "$n · ${shortModelName(r.model)}" else n
        }
    }

    var level by rememberSaveable { mutableStateOf(1) }   // 1 = L1, 2 = L2 (cell detail)
    var groupKey by rememberSaveable { mutableStateOf("") }
    var confirmRedo by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        when {
            level == 2 -> level = 1
            else -> onBack()
        }
    }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(16.dp)) {
            TitleBar(helpTopic = "compare_l1", title = "Compare with meta", subject = reportTitle,
                reportIcon = reportIcon, onBackClick = onBack)
            Spacer(Modifier.height(20.dp))
            Text("No compare run on this report.", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    when (level) {
        2 -> CompareL2(run, agents, metaLabels, metaRowsById, reportTitle, report?.prompt.orEmpty(), reportIcon, groupKey,
            onRerun = { ck -> scope.launch { engine.rerunCell(context, reportId, ck) } },
            onBack = { level = 1 })
        else -> CompareL1(run, agents, reportTitle, reportIcon, throttled,
            openGroup = { gk -> groupKey = gk; level = 2 },
            onRedo = { confirmRedo = true },
            onRestartFailed = { scope.launch { engine.restartFailedCells(context, reportId) } },
            onRemoveFailed = { scope.launch { engine.removeFailedCells(context, reportId) } },
            onDeleteRun = { scope.launch { engine.deleteRun(context, reportId) }; onBack() },
            onBack = onBack)
    }

    if (confirmRedo) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRedo = false },
            title = { Text("Redo the comparison?") },
            text = { Text("Delete the current scores and re-score every answer against every meta item from scratch?") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRedo = false
                    level = 1
                    scope.launch {
                        engine.deleteRun(context, reportId).join()
                        // Re-launch over the same meta items + prompt.
                        engine.startRun(context, reportId, run.metaResultIds, run.comparePrompt.id)
                    }
                }) { Text("Redo") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRedo = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------- helpers ----------

private fun agentLabel(agents: Map<String, ReportAgent>, agentId: String?): String =
    agents[agentId]?.let { shortModelName(it.model) } ?: "?"

private fun pctColor(p: Int?): Color = when {
    p == null -> AppColors.TextSecondary
    p >= 67 -> AppColors.SuccessAccent
    p >= 34 -> AppColors.CautionAccent
    else -> AppColors.DangerAccent
}

private fun pctText(p: Int?): String = p?.let { "$it%" } ?: "…"

private data class CompareGroupRow(val key: String, val label: String, val cells: List<CompareCellState>) {
    val done get() = cells.count { it.status == CompareCellStatus.DONE }
    val errored get() = cells.count { it.status == CompareCellStatus.ERROR }
    val running get() = cells.count { it.status == CompareCellStatus.RUNNING }
    val total get() = cells.size
    val cost get() = cells.sumOf { it.totalCost }
    val avg: Int? get() {
        val scored = cells.mapNotNull { it.percent }
        return if (scored.isEmpty()) null else scored.sum() / scored.size
    }
}

private fun buildGroups(
    run: CompareRunState, agents: Map<String, ReportAgent>
): List<CompareGroupRow> =
    run.cells.values.groupBy { it.agentId }
        .map { (aid, cs) -> CompareGroupRow("agent:$aid", agentLabel(agents, aid), cs) }
        .sortedByDescending { it.avg ?: -1 }

private fun cellsForGroup(run: CompareRunState, groupKey: String): List<CompareCellState> {
    val (kind, value) = groupKey.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return when (kind) {
        "agent" -> run.cells.values.filter { it.agentId == value }
        "meta" -> run.cells.values.filter { it.metaResultId == value }
        else -> emptyList()
    }
}

// ---------- L1 ----------

@Composable
private fun CompareL1(
    run: CompareRunState,
    agents: Map<String, ReportAgent>,
    reportTitle: String,
    reportIcon: String,
    throttled: Set<String>,
    openGroup: (String) -> Unit,
    onRedo: () -> Unit,
    onRestartFailed: () -> Unit,
    onRemoveFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onBack: () -> Unit
) {
    // Worker-pool batch (category B): no Bench bucket; rate-gated cells
    // count only under Wait, not also Run.
    val summary = deriveBatchSummary(
        items = run.cells.values,
        idOf = { it.id },
        statusOf = { it.status },
        throttledIds = throttled,
        family = BatchFamily.WORKER_POOL,
    )
    val counts = summary.counts
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "compare_l1", title = "Compare with meta",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack, onReload = onRedo, onDelete = onDeleteRun
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            BatchStatsRow(listOf(
                Triple("Total", counts.total.toString(), AppColors.InfoAccent),
                Triple("Done", counts.done.toString(), AppColors.SuccessAccent),
                Triple("Error", summary.displayError.toString(), AppColors.DangerAccent),
                Triple("Run", counts.running.toString(), AppColors.WarningAccent),
                Triple("Wait", counts.wait.toString(), AppColors.CautionAccent),
                Triple("Queue", counts.queued.toString(), AppColors.QueueAccent),
                Triple("Costs", "${formatCents(run.totalCost, 2)} ¢", AppColors.InfoAccent)
            ))
            // Run-level progress bar while work is still outstanding (parity
            // with Fan Out / Fan Meta / Translation).
            if (summary.activeOutstanding && counts.total > 0 && !run.cancelled) {
                val finished = (counts.done + summary.displayError).toFloat() / counts.total
                LinearProgressIndicator(
                    progress = { finished },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = AppColors.WarningAccent,
                    trackColor = AppColors.DividerDark
                )
            }
            Spacer(Modifier.height(12.dp))

            val groups = buildGroups(run, agents)
            if (groups.isEmpty()) {
                Text("One moment, collecting information…", color = AppColors.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            groups.forEach { g -> CompareGroupRowItem(g) { openGroup(g.key) } }

            Spacer(Modifier.height(16.dp))
            // Per-failure controls (Remove/Restart) removed — a new
            // failure-handling UX is coming.
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CompareGroupRowItem(group: CompareGroupRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val avg = group.avg
        Text(
            pctText(avg), color = pctColor(avg), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp).padding(end = 8.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(group.label, color = AppColors.TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (group.errored > 0) {
                Text("${group.errored} failed", color = AppColors.TextTertiary, fontSize = 11.sp)
            }
        }
        if (group.cost > 0) {
            Text("${formatCents(group.cost)} ¢", color = AppColors.TextTertiary, fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
}

// ---------- L2 (cell detail) ----------

@Composable
private fun CompareL2(
    run: CompareRunState,
    agents: Map<String, ReportAgent>,
    metaLabels: Map<String, String>,
    metaRowsById: Map<String, SecondaryResult>,
    reportTitle: String,
    question: String,
    reportIcon: String,
    groupKey: String,
    onRerun: (String) -> Unit,
    onBack: () -> Unit
) {
    // 1×1: each report-model group holds a single cell — show its detail here.
    val c = cellsForGroup(run, groupKey).sortedByDescending { it.percent ?: -1 }.firstOrNull()
    Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "compare_l2", title = "Compare with meta - model", subject = reportTitle,
            reportIcon = reportIcon, onBackClick = onBack, onReload = c?.let { cell -> { onRerun(cell.key) } })
        if (c == null) {
            Text("Cell not found.", color = AppColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val answerLabel = agentLabel(agents, c.agentId)
        val metaLabel = metaLabels[c.metaResultId] ?: "meta"
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground).padding(12.dp)) {
                Text(pctText(c.percent), color = pctColor(c.percent), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("$answerLabel  vs  $metaLabel", color = AppColors.TextPrimary, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!c.reason.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(c.reason!!, color = AppColors.TextSecondary, fontSize = 12.sp)
                }
                c.errorMessage?.let { Text("${com.ai.data.MetadataIconsHolder.current.warningPlain} $it", color = AppColors.DangerAccent, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(12.dp))
            // Card 1 — the models + prompt behind this cell's score. The edit
            // pencil opens the meta_compare prompt (which picks the compare models).
            val editPrompt = com.ai.ui.shared.LocalEditInternalPrompt.current
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground).padding(12.dp)) {
                Text("Report model: $answerLabel", color = AppColors.TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("Compare model: ${c.judgeModel ?: "(pending)"}", color = AppColors.TextPrimary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Prompt used: ${secondaryPromptDisplayName(run.comparePrompt.name)}", color = AppColors.TextPrimary, fontSize = 14.sp)
                    Text(com.ai.ui.shared.LocalMetadataIcons.current.edit, fontSize = 16.sp,
                        modifier = Modifier.clickable { editPrompt(run.comparePrompt.id) }.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            // Card 2 — the API interaction (resolved compare prompt + worker
            // reply), like the Icon-lookup page.
            val apiInteraction = remember(c.id, c.content, c.agentId, c.metaResultId) {
                val resolved = com.ai.data.resolveSecondaryPrompt(
                    run.comparePrompt.text, question = question, results = "", count = 1, title = reportTitle
                )
                    .replace("@RESPONSE@", agents[c.agentId]?.responseBody.orEmpty())
                    .replace("@META_RESPONSE@", metaRowsById[c.metaResultId]?.content?.let { com.ai.data.stripMetaReferenceLegend(it) }.orEmpty())
                buildOneShotApiInteraction(resolved, c.content)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground).padding(12.dp)) {
                Text("API interaction", color = AppColors.TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(apiInteraction, color = AppColors.TextPrimary, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
