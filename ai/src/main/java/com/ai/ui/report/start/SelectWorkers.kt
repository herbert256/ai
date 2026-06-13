package com.ai.ui.report.start

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.BatchWorkerMode
import com.ai.data.ModelInfoMode
import com.ai.data.ReportInfoMode
import com.ai.data.ReportWorkerConfig
import com.ai.data.WorkerSelectionMode
import com.ai.model.Settings
import com.ai.model.Worker
import com.ai.ui.settings.WorkerRowEditor
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
import com.ai.ui.shared.TitleBar

/**
 * "Report - setup" — the step between "Report - select models" and
 * "Manage a report". A stack of collapsible cards (all collapsed on open)
 * configures, per report: the system prompt override, the API-parameter
 * presets, and who does the worker jobs — report info (icon / titles /
 * language), model info (per-model icons & titles), the type-B worker
 * batches (Fan Meta, Translation, Tournament, Compare), and a separate
 * Meta card for the Meta + Fan-in batches (same option set, its own pool).
 * Rerank, Moderation and Judge-the-judges are intentionally absent — they
 * run on the workers defined in their own prompt, not the Worker-batches
 * pool (Judge-the-judges is a type-A fixed-judge batch).
 *
 * One stateless composable serves two hosts: pre-generation (the
 * [onGenerate] "Generate report" button is the primary action, and the
 * System-prompt / Parameters cards show because their change callbacks are
 * wired) and the Manage 👷 re-edit ([onSave] instead, no Generate; the two
 * extra cards are hidden). [config] is fully hoisted — the caller owns
 * persistence.
 */
@Composable
internal fun ReportSelectWorkersScreen(
    aiSettings: Settings,
    config: ReportWorkerConfig,
    onConfigChange: (ReportWorkerConfig) -> Unit,
    onGenerate: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** Per-report system-prompt override. Wired only by the pre-generation
     *  host; null callback → the System prompt card is hidden. */
    systemPromptId: String? = null,
    onSystemPromptChange: ((String?) -> Unit)? = null,
    /** Per-report API-parameter preset ids. Wired only by the pre-generation
     *  host; null callback → the Parameters card is hidden. */
    parametersIds: List<String> = emptyList(),
    onParametersChange: ((List<String>) -> Unit)? = null
) {
    androidx.activity.compose.BackHandler { onDismiss() }
    val mi = LocalMetadataIcons.current
    val agentNames = remember(aiSettings) { aiSettings.agents.map { it.name } }
    // A CUSTOM report-info pick with no resolvable worker would strand the
    // icon / title / language calls — gate the primary action on it, the
    // same pre-flight the runtime worker picker applies.
    val canProceed = config.reportInfo != ReportInfoMode.CUSTOM ||
        config.reportInfoWorkers.any { aiSettings.resolveWorker(it) != null }

    // The System-prompt / Parameters cards open the existing full-screen
    // preset selectors as nested overlays (early return preserves this
    // screen's remember state underneath — the overlay back-stack rule).
    var showSysPromptPicker by remember { mutableStateOf(false) }
    var showParamsPicker by remember { mutableStateOf(false) }
    if (showSysPromptPicker && onSystemPromptChange != null) {
        com.ai.ui.shared.SystemPromptSelectScreen(
            aiSettings = aiSettings,
            selectedId = systemPromptId,
            onSelect = onSystemPromptChange,
            onBack = { showSysPromptPicker = false }, onNavigateHome = onDismiss
        )
        return
    }
    if (showParamsPicker && onParametersChange != null) {
        com.ai.ui.shared.ParametersSelectScreen(
            aiSettings = aiSettings,
            selectedIds = parametersIds,
            onConfirm = onParametersChange,
            onBack = { showParamsPicker = false }, onNavigateHome = onDismiss
        )
        return
    }

    // Per-card collapse state — every card starts collapsed.
    var sysPromptOpen by remember { mutableStateOf(false) }
    var paramsOpen by remember { mutableStateOf(false) }
    var reportInfoOpen by remember { mutableStateOf(false) }
    var modelInfoOpen by remember { mutableStateOf(false) }
    var batchesOpen by remember { mutableStateOf(false) }
    var metaOpen by remember { mutableStateOf(false) }
    var secondResultsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "report_select_workers",
            title = "Report - setup",
            subject = "System prompt, parameters and workers",
            onBackClick = onDismiss
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { (onGenerate ?: onSave)?.invoke() },
            enabled = canProceed && (onGenerate != null || onSave != null),
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(if (onGenerate != null) "Generate report" else "Save", maxLines = 1, softWrap = false) }
        Spacer(Modifier.height(12.dp))

        // ── Card: System prompt (pre-generation host only) ─────────────
        if (onSystemPromptChange != null) {
            val sysName = systemPromptId
                ?.let { id -> aiSettings.systemPrompts.firstOrNull { it.id == id }?.name }
            CollapsibleCard(
                icon = mi.systemPrompt,
                title = "System prompt",
                summary = sysName ?: "Default (per-agent)",
                expanded = sysPromptOpen,
                onToggle = { sysPromptOpen = !sysPromptOpen }
            ) {
                Text(
                    "Override the system prompt for every answer model in this report. " +
                        "Leave on Default to keep each agent's own system prompt.",
                    fontSize = 11.sp, color = AppColors.TextDim
                )
                OutlinedButton(
                    onClick = { showSysPromptPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text(if (sysName != null) "Change system prompt" else "Choose system prompt", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Card: Parameters (pre-generation host only) ────────────────
        if (onParametersChange != null) {
            val activeParams = aiSettings.parameters
                .filter { it.id in parametersIds }
                .joinToString(", ") { it.name }
            CollapsibleCard(
                icon = mi.parameters,
                title = "Parameters",
                summary = activeParams.ifEmpty { "Default" },
                expanded = paramsOpen,
                onToggle = { paramsOpen = !paramsOpen }
            ) {
                Text(
                    "API parameter presets (temperature, max tokens, …) applied to every call in this report.",
                    fontSize = 11.sp, color = AppColors.TextDim
                )
                OutlinedButton(
                    onClick = { showParamsPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text(if (activeParams.isNotEmpty()) "Change parameters" else "Choose parameters", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Card: Report info ──────────────────────────────────────────
        CollapsibleCard(
            icon = mi.reportIcon,
            title = "Report info",
            summary = if (config.reportInfo == ReportInfoMode.PROMPT) "Prompt configuration" else "Specify model or agent",
            expanded = reportInfoOpen,
            onToggle = { reportInfoOpen = !reportInfoOpen }
        ) {
            Text("Icon, titles, language detection", fontSize = 12.sp, color = AppColors.TextTertiary)
            OptionRow(
                selected = config.reportInfo == ReportInfoMode.PROMPT,
                label = "Prompt configuration",
                sublabel = "The report-info worker prompts run on their configured chains.",
                onSelect = { onConfigChange(config.copy(reportInfo = ReportInfoMode.PROMPT)) }
            )
            OptionRow(
                selected = config.reportInfo == ReportInfoMode.CUSTOM,
                label = "Specify model or agent",
                sublabel = "Run them on your own ordered fallback chain of Model / Agent / Flock / Swarm.",
                onSelect = { onConfigChange(config.copy(reportInfo = ReportInfoMode.CUSTOM)) }
            )
            if (config.reportInfo == ReportInfoMode.CUSTOM) {
                Text("Workers — ordered fallback chain", fontSize = 12.sp, color = AppColors.TextTertiary)
                if (config.reportInfoWorkers.isEmpty()) {
                    Text("No workers yet — add at least one.", fontSize = 12.sp, color = AppColors.TextDim)
                }
                config.reportInfoWorkers.forEachIndexed { idx, w ->
                    WorkerRowEditor(
                        index = idx,
                        worker = w,
                        agentNames = agentNames,
                        aiSettings = aiSettings,
                        onChange = { nw ->
                            onConfigChange(config.copy(
                                reportInfoWorkers = config.reportInfoWorkers.toMutableList().also { it[idx] = nw }
                            ))
                        },
                        onRemove = {
                            onConfigChange(config.copy(
                                reportInfoWorkers = config.reportInfoWorkers.toMutableList().also { it.removeAt(idx) }
                            ))
                        }
                    )
                }
                OutlinedButton(
                    onClick = { onConfigChange(config.copy(reportInfoWorkers = config.reportInfoWorkers + Worker(agent = "*select"))) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("+ Add worker", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── Card: Model info ───────────────────────────────────────────
        CollapsibleCard(
            icon = mi.reportModelIcon,
            title = "Model info",
            summary = if (config.modelInfo == ModelInfoMode.OWN_MODEL) "Own model" else "Prompt configuration",
            expanded = modelInfoOpen,
            onToggle = { modelInfoOpen = !modelInfoOpen }
        ) {
            Text("Per-model icons & titles", fontSize = 12.sp, color = AppColors.TextTertiary)
            OptionRow(
                selected = config.modelInfo == ModelInfoMode.PROMPT,
                label = "Prompt configuration",
                sublabel = "The model-info worker prompts run on their configured chains.",
                onSelect = { onConfigChange(config.copy(modelInfo = ModelInfoMode.PROMPT)) }
            )
            OptionRow(
                selected = config.modelInfo == ModelInfoMode.OWN_MODEL,
                label = "Own model",
                sublabel = "Each report model writes its own title and icon.",
                onSelect = { onConfigChange(config.copy(modelInfo = ModelInfoMode.OWN_MODEL)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── Card: Worker batches ───────────────────────────────────────
        CollapsibleCard(
            icon = mi.ant,
            title = "Worker batches",
            summary = batchModeSummary(config.batches),
            expanded = batchesOpen,
            onToggle = { batchesOpen = !batchesOpen }
        ) {
            Text(
                "Fan Meta, Translation, Tournament, Compare",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            BatchRoutingOptions(
                batches = config.batches,
                workerSelection = config.workerSelection,
                batchWorkers = config.batchWorkers,
                agentNames = agentNames,
                aiSettings = aiSettings,
                onBatchesChange = { onConfigChange(config.copy(batches = it)) },
                onWorkerSelectionChange = { onConfigChange(config.copy(workerSelection = it)) },
                onBatchWorkersChange = { onConfigChange(config.copy(batchWorkers = it)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── Card: Meta ─────────────────────────────────────────────────
        // Meta + Fan-in route on their own pool, separate from Worker
        // batches; same option set.
        CollapsibleCard(
            icon = mi.meta,
            title = "Meta",
            summary = batchModeSummary(config.metaBatches),
            expanded = metaOpen,
            onToggle = { metaOpen = !metaOpen }
        ) {
            Text("Meta, Fan-in", fontSize = 12.sp, color = AppColors.TextTertiary)
            BatchRoutingOptions(
                batches = config.metaBatches,
                workerSelection = config.metaWorkerSelection,
                batchWorkers = config.metaBatchWorkers,
                agentNames = agentNames,
                aiSettings = aiSettings,
                onBatchesChange = { onConfigChange(config.copy(metaBatches = it)) },
                onWorkerSelectionChange = { onConfigChange(config.copy(metaWorkerSelection = it)) },
                onBatchWorkersChange = { onConfigChange(config.copy(metaBatchWorkers = it)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── Card: Second result options ────────────────────────────────
        // Gates the intermediate screens shown when a Meta / Fan-out
        // secondary is launched from the second-results hub. Both default
        // off → the launch runs with the default scope and the prompt as
        // configured, skipping those screens.
        CollapsibleCard(
            icon = mi.settings,
            title = "Second result options",
            summary = "Scope " + (if (config.secondResultSelectScope) "on" else "off") +
                " · Params " + (if (config.secondResultRuntimeParams) "on" else "off"),
            expanded = secondResultsOpen,
            onToggle = { secondResultsOpen = !secondResultsOpen }
        ) {
            Text(
                "When starting a Meta or Fan-out from the second results, show these steps first.",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            ToggleRow(
                label = "Select scope",
                sublabel = "Pick which reports / languages the run covers (else: all reports, all languages).",
                checked = config.secondResultSelectScope,
                onCheckedChange = { onConfigChange(config.copy(secondResultSelectScope = it)) }
            )
            ToggleRow(
                label = "Runtime parameters",
                sublabel = "Edit the prompt (and Fan-out's models) before running (else: run as configured).",
                checked = config.secondResultRuntimeParams,
                onCheckedChange = { onConfigChange(config.copy(secondResultRuntimeParams = it)) }
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A label + sublabel row with a trailing Material3 [Switch] — the boolean
 *  counterpart to [OptionRow], used by the Second result options card. The
 *  whole row toggles. */
@Composable
private fun ToggleRow(label: String, sublabel: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(sublabel, fontSize = 11.sp, color = AppColors.TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Collapsed-header summary for a [BatchWorkerMode] — shared by the
 *  Worker batches and Meta cards. */
private fun batchModeSummary(mode: BatchWorkerMode): String = when (mode) {
    BatchWorkerMode.PROMPT -> "Prompt configuration"
    BatchWorkerMode.REPORT_MODELS -> "Report models"
    BatchWorkerMode.SELECT_EACH -> "User selectable for each batch"
    BatchWorkerMode.SELECT_ONCE -> "One time selectable"
}

/** The four batch-routing radio options (+ the Report-models worker-selection
 *  sub-choice and the SELECT_ONCE worker editor) shared by the Worker batches
 *  and Meta cards. Fully hoisted — the caller maps the chosen values back onto
 *  whichever [ReportWorkerConfig] fields the card owns. */
@Composable
private fun ColumnScope.BatchRoutingOptions(
    batches: BatchWorkerMode,
    workerSelection: WorkerSelectionMode,
    batchWorkers: List<Worker>,
    agentNames: List<String>,
    aiSettings: Settings,
    onBatchesChange: (BatchWorkerMode) -> Unit,
    onWorkerSelectionChange: (WorkerSelectionMode) -> Unit,
    onBatchWorkersChange: (List<Worker>) -> Unit
) {
    OptionRow(
        selected = batches == BatchWorkerMode.PROMPT,
        label = "Prompt configuration",
        sublabel = "Each batch uses its Internal Prompt's workers (or its run-time picker).",
        onSelect = { onBatchesChange(BatchWorkerMode.PROMPT) }
    )
    OptionRow(
        selected = batches == BatchWorkerMode.REPORT_MODELS,
        label = "Report models",
        sublabel = "Workers are this report's own answer models.",
        onSelect = { onBatchesChange(BatchWorkerMode.REPORT_MODELS) }
    )
    if (batches == BatchWorkerMode.REPORT_MODELS) {
        Text("Worker selection", fontSize = 12.sp, color = AppColors.TextTertiary)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = workerSelection == WorkerSelectionMode.WHEN_AVAILABLE,
                onClick = { onWorkerSelectionChange(WorkerSelectionMode.WHEN_AVAILABLE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("When available", fontSize = 13.sp) }
            SegmentedButton(
                selected = workerSelection == WorkerSelectionMode.ROUND_ROBIN,
                onClick = { onWorkerSelectionChange(WorkerSelectionMode.ROUND_ROBIN) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("Round robin", fontSize = 13.sp) }
        }
        Text(
            if (workerSelection == WorkerSelectionMode.ROUND_ROBIN)
                "Each worker gets the same share of work; failures fall through to the next worker."
            else "Fast models pick up more work.",
            fontSize = 11.sp, color = AppColors.TextDim
        )
    }
    OptionRow(
        selected = batches == BatchWorkerMode.SELECT_EACH,
        label = "User selectable for each batch",
        sublabel = "Pick the workers every time a batch starts.",
        onSelect = { onBatchesChange(BatchWorkerMode.SELECT_EACH) }
    )
    OptionRow(
        selected = batches == BatchWorkerMode.SELECT_ONCE,
        label = "One time selectable, use for each batch",
        sublabel = "Pick once at the first batch; reused for all later batches.",
        onSelect = { onBatchesChange(BatchWorkerMode.SELECT_ONCE) }
    )
    if (batches == BatchWorkerMode.SELECT_ONCE) {
        if (batchWorkers.isEmpty()) {
            Text(
                "You'll pick the workers when the first batch starts.",
                fontSize = 11.sp, color = AppColors.TextDim
            )
        } else {
            // The group picked at the first batch — reviewable and
            // editable from the Manage 👷 re-edit.
            Text("Workers picked at the first batch", fontSize = 12.sp, color = AppColors.TextTertiary)
            batchWorkers.forEachIndexed { idx, w ->
                WorkerRowEditor(
                    index = idx,
                    worker = w,
                    agentNames = agentNames,
                    aiSettings = aiSettings,
                    onChange = { nw -> onBatchWorkersChange(batchWorkers.toMutableList().also { it[idx] = nw }) },
                    onRemove = { onBatchWorkersChange(batchWorkers.toMutableList().also { it.removeAt(idx) }) }
                )
            }
            OutlinedButton(
                onClick = { onBatchWorkersChange(batchWorkers + Worker(agent = "*select")) },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("+ Add worker", fontSize = 13.sp) }
        }
    }
}

/** Card with a tappable header (icon + title + current-selection summary +
 *  ▸/▾ chevron) that expands to reveal [content]. All [ReportSelectWorkersScreen]
 *  cards start collapsed so the screen opens as a compact summary. */
@Composable
private fun CollapsibleCard(
    icon: String,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    if (!expanded) {
                        Text(summary, fontSize = 12.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(if (expanded) "▾" else "▸", fontSize = 14.sp, color = AppColors.TextTertiary)
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, label: String, sublabel: String, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = if (selected) AppColors.CardBackgroundAlt else AppColors.CardBackground)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(sublabel, fontSize = 11.sp, color = AppColors.TextTertiary)
            }
        }
    }
}
