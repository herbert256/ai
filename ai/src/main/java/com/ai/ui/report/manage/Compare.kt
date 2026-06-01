package com.ai.ui.report.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/** Page 1 — multi-select the plain-meta results to score answers against. */
@Composable
fun CompareSelectMetaScreen(
    metaItems: List<SecondaryResult>,
    aiSettings: Settings,
    preselected: List<String>,
    onNext: (List<String>) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    var selected by remember { mutableStateOf(preselected.toSet()) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "compare_select_meta",
            title = "Compare with meta",
            subject = "Pick meta results to score answers against",
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
                    val checked = row.id in selected
                    val preview = row.content?.trim()?.take(90)?.replace("\n", " ").orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - row.id else selected + row.id
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (checked) com.ai.data.MetadataIconsHolder.current.checkboxOn else com.ai.data.MetadataIconsHolder.current.checkboxOff, fontSize = 18.sp,
                            color = if (checked) AppColors.SuccessAccent else AppColors.TextSecondary,
                            modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
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
        Button(
            onClick = { onNext(selected.toList()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.ButtonBackground)
        ) { Text("Next — pick a prompt (${selected.size})", fontSize = 14.sp) }
    }
}

/** Page 2 — pick one of the `meta_compare` prompts; Run launches the grid. */
@Composable
fun CompareSelectPromptScreen(
    prompts: List<InternalPrompt>,
    onRun: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "compare_select_prompt",
            title = "Compare with meta",
            subject = "Pick a comparison prompt",
            reportIcon = com.ai.data.MetadataIconsHolder.current.compare,
            onBackClick = onBack
        )
        if (prompts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No comparison prompts. Add one under AI Setup → Prompt management → Compare prompts.",
                    color = AppColors.TextSecondary, fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(prompts.sortedBy { it.name.lowercase() }, key = { it.id }) { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onRun(p.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(com.ai.data.MetadataIconsHolder.current.compare, fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (p.title.isNotBlank()) {
                                Text(p.title, color = AppColors.TextTertiary, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
                }
            }
        }
    }
}

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

    val rowText = buildString {
        append("${run.doneCount} / ${run.totalCells}")
        if (run.errorCount > 0) append(" · ${run.errorCount} failed")
        else if (run.allTerminal) append(" · done")
    }
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
                text = rowText, color = Color.White, fontSize = 13.sp,
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
// Drill-in: L1 (stats + 2 grouping modes) → L2 (group) → L3 (cell)
// ===================================================================

enum class CompareGroupMode { REPORT_MODELS, META_ITEMS }

@Composable
fun CompareScreen(engine: CompareEngine, reportId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val runs by engine.runs.collectAsState()
    val throttled by engine.throttledCells.collectAsState()
    val run = runs[reportId]

    LaunchedEffect(reportId) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }
    LaunchedEffect(reportId, run?.allTerminal) {
        while (true) {
            val r = engine.runByKey(reportId)
            if (r != null && r.allTerminal) break
            delay(3000)
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
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

    var groupMode by rememberSaveable { mutableStateOf(CompareGroupMode.REPORT_MODELS) }
    var level by rememberSaveable { mutableStateOf(1) }   // 1 = L1, 2 = L2, 3 = L3
    var groupKey by rememberSaveable { mutableStateOf("") }
    var cellKey by rememberSaveable { mutableStateOf("") }
    var confirmRedo by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        when {
            level == 3 -> level = 2
            level == 2 -> level = 1
            else -> onBack()
        }
    }

    if (run == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(helpTopic = "compare_l1", title = "Compare", subject = reportTitle,
                reportIcon = reportIcon, onBackClick = onBack)
            Spacer(Modifier.height(20.dp))
            Text("No compare run on this report.", color = AppColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }

    when (level) {
        2 -> CompareL2(run, agents, metaLabels, reportTitle, reportIcon, groupKey, groupMode,
            openCell = { ck -> cellKey = ck; level = 3 },
            onBack = { level = 1 })
        3 -> CompareL3(run, agents, metaLabels, metaRowsById, reportTitle, reportIcon, cellKey, groupKey, groupMode,
            onBack = { level = 2 },
            onRerun = { scope.launch { engine.rerunCell(context, reportId, cellKey) } },
            onStep = { ck -> cellKey = ck })
        else -> CompareL1(run, agents, metaLabels, reportTitle, reportIcon, groupMode, throttled,
            setGroupMode = { groupMode = it },
            openGroup = { gk -> groupKey = gk; level = 2 },
            onRedo = { confirmRedo = true },
            onRestartFailed = { scope.launch { engine.restartFailedCells(context, reportId) } },
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
    run: CompareRunState, agents: Map<String, ReportAgent>, metaLabels: Map<String, String>,
    mode: CompareGroupMode
): List<CompareGroupRow> = when (mode) {
    CompareGroupMode.REPORT_MODELS ->
        run.cells.values.groupBy { it.agentId }
            .map { (aid, cs) -> CompareGroupRow("agent:$aid", agentLabel(agents, aid), cs) }
            .sortedByDescending { it.avg ?: -1 }
    CompareGroupMode.META_ITEMS ->
        run.cells.values.groupBy { it.metaResultId }
            .map { (mid, cs) -> CompareGroupRow("meta:$mid", metaLabels[mid] ?: "meta", cs) }
            .sortedBy { it.label.lowercase() }
}

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
    metaLabels: Map<String, String>,
    reportTitle: String,
    reportIcon: String,
    groupMode: CompareGroupMode,
    throttled: Set<String>,
    setGroupMode: (CompareGroupMode) -> Unit,
    openGroup: (String) -> Unit,
    onRedo: () -> Unit,
    onRestartFailed: () -> Unit,
    onDeleteRun: () -> Unit,
    onBack: () -> Unit
) {
    val throttledCount = run.cells.values.count { it.id in throttled }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "compare_l1", title = "Compare",
            subject = reportTitle, reportIcon = reportIcon,
            onBackClick = onBack, onReload = onRedo, onDelete = onDeleteRun
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            val stats = listOf(
                Triple("Total", run.totalCells.toString(), AppColors.InfoAccent),
                Triple("Done", run.doneCount.toString(), AppColors.SuccessAccent),
                Triple("Run", run.runningCount.toString(), AppColors.WarningAccent),
                Triple("Wait", throttledCount.toString(), AppColors.CautionAccent),
                Triple("Queue", run.queuedCount.toString(), AppColors.QueueAccent),
                Triple("Err", run.errorCount.toString(), AppColors.DangerAccent),
                Triple("Cost", "${formatCents(run.totalCost, 2)} ¢", AppColors.InfoAccent)
            )
            Row(Modifier.fillMaxWidth()) {
                stats.forEach { (label, _, color) ->
                    Text(label, fontSize = 11.sp, color = color, textAlign = TextAlign.Center,
                        maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                stats.forEach { (_, value, color) ->
                    Text(value, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = groupMode == CompareGroupMode.REPORT_MODELS,
                    onClick = { setGroupMode(CompareGroupMode.REPORT_MODELS) },
                    label = { Text("Report models", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = groupMode == CompareGroupMode.META_ITEMS,
                    onClick = { setGroupMode(CompareGroupMode.META_ITEMS) },
                    label = { Text("Meta items", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))

            val groups = buildGroups(run, agents, metaLabels, groupMode)
            if (groups.isEmpty()) {
                Text("No cells yet.", color = AppColors.TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            groups.forEach { g -> CompareGroupRowItem(g) { openGroup(g.key) } }

            Spacer(Modifier.height(16.dp))
            if (run.errorCount > 0) {
                Button(
                    onClick = onRestartFailed, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.ButtonBackground)
                ) { Text("Restart ${run.errorCount} failed", fontSize = 14.sp) }
            }
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
            Text(group.label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                append("${group.done}/${group.total} scored")
                if (group.errored > 0) append(" · ${group.errored} failed")
            }
            Text(sub, color = AppColors.TextTertiary, fontSize = 11.sp)
        }
        if (group.cost > 0) {
            Text("${formatCents(group.cost)} ¢", color = AppColors.TextTertiary, fontSize = 11.sp)
        }
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
}

// ---------- L2 ----------

@Composable
private fun CompareL2(
    run: CompareRunState,
    agents: Map<String, ReportAgent>,
    metaLabels: Map<String, String>,
    reportTitle: String,
    reportIcon: String,
    groupKey: String,
    groupMode: CompareGroupMode,
    openCell: (String) -> Unit,
    onBack: () -> Unit
) {
    val cells = cellsForGroup(run, groupKey)
    val title = when {
        groupKey.startsWith("agent:") -> agentLabel(agents, groupKey.substringAfter(":"))
        groupKey.startsWith("meta:") -> metaLabels[groupKey.substringAfter(":")] ?: "meta"
        else -> ""
    }
    val screenTitle = when (groupMode) {
        CompareGroupMode.REPORT_MODELS -> "Compare - model"
        CompareGroupMode.META_ITEMS -> "Compare - meta"
    }
    // The opposite axis labels each row: for an agent group the row is the
    // meta item; for a meta group the row is the answer model.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "compare_l2", title = screenTitle, subject = reportTitle,
            reportIcon = reportIcon, onBackClick = onBack)
        CompareGreenSubject(title)
        LazyColumn(Modifier.fillMaxSize()) {
            items(cells.sortedByDescending { it.percent ?: -1 }, key = { it.key }) { c ->
                val rowLabel = if (groupKey.startsWith("agent:")) (metaLabels[c.metaResultId] ?: "meta")
                    else agentLabel(agents, c.agentId)
                CompareCellRow(c, rowLabel) { openCell(c.key) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CompareGreenSubject(text: String) {
    Text(
        text = text, color = AppColors.SuccessAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun CompareCellRow(c: CompareCellState, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val status = when {
            c.status == CompareCellStatus.ERROR -> com.ai.data.MetadataIconsHolder.current.statusFailed
            c.status == CompareCellStatus.RUNNING || c.status == CompareCellStatus.PENDING -> "⏳"
            else -> null
        }
        if (status != null) {
            Text(status, fontSize = 14.sp, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
        } else {
            Text(pctText(c.percent), color = pctColor(c.percent), fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        }
        Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp))
    }
    HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.3f), thickness = 0.5.dp)
}

// ---------- L3 ----------

@Composable
private fun CompareL3(
    run: CompareRunState,
    agents: Map<String, ReportAgent>,
    metaLabels: Map<String, String>,
    metaRowsById: Map<String, SecondaryResult>,
    reportTitle: String,
    reportIcon: String,
    cellKey: String,
    groupKey: String,
    groupMode: CompareGroupMode,
    onBack: () -> Unit,
    onRerun: () -> Unit,
    onStep: (String) -> Unit
) {
    val scoped = cellsForGroup(run, groupKey).sortedByDescending { it.percent ?: -1 }
    val idx = scoped.indexOfFirst { it.key == cellKey }
    val c = scoped.getOrNull(idx) ?: run.cells[cellKey]
    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    var swipeDragX by remember(cellKey) { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "compare_l3", title = "Compare - cell", subject = reportTitle,
            reportIcon = reportIcon, onBackClick = onBack, onReload = onRerun)
        if (c == null) {
            Text("Cell not found.", color = AppColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val answerLabel = agentLabel(agents, c.agentId)
        val metaLabel = metaLabels[c.metaResultId] ?: "meta"
        Column(
            Modifier.fillMaxSize()
                .pointerInput(idx, scoped.size, cellKey) {
                    detectHorizontalDragGestures(
                        onDragStart = { swipeDragX = 0f },
                        onHorizontalDrag = { _, d -> swipeDragX += d },
                        onDragCancel = { swipeDragX = 0f },
                        onDragEnd = {
                            when {
                                swipeDragX > swipeThresholdPx -> scoped.getOrNull(idx - 1)?.let { onStep(it.key) }
                                swipeDragX < -swipeThresholdPx -> scoped.getOrNull(idx + 1)?.let { onStep(it.key) }
                            }
                            swipeDragX = 0f
                        }
                    )
                }
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground).padding(12.dp)) {
                Text(pctText(c.percent), color = pctColor(c.percent), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("$answerLabel  vs  $metaLabel", color = Color.White, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!c.reason.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(c.reason!!, color = AppColors.TextSecondary, fontSize = 12.sp)
                }
                c.judgeModel?.let { Text("Scored by: $it", color = AppColors.TextTertiary, fontSize = 11.sp) }
                c.errorMessage?.let { Text("${com.ai.data.MetadataIconsHolder.current.warningPlain} $it", color = AppColors.DangerAccent, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(12.dp))
            ComparePane("Answer — $answerLabel", AppColors.SuccessAccent, agents[c.agentId]?.responseBody)
            Spacer(Modifier.height(12.dp))
            ComparePane("Meta — $metaLabel", AppColors.InfoAccent,
                metaRowsById[c.metaResultId]?.content?.let { com.ai.data.stripMetaReferenceLegend(it) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ComparePane(header: String, headerColor: Color, body: String?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.CardBackground).padding(12.dp)) {
        Text(header, color = headerColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(body?.trim().orEmpty(), color = AppColors.TextSecondary, fontSize = 12.sp)
    }
}
