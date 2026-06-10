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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.ai.ui.shared.TitleBar

/**
 * "Report - select workers" — the step between "Report - select models" and
 * "Manage a report". Three cards decide, per report, who does the worker
 * jobs: report info (icon / titles / language), model info (per-model icons
 * & titles), and the type-B worker batches (Fan Meta, Translation,
 * Tournament, Judges, Compare, TransRank, Rerank, Moderation, Meta, Fan-in).
 *
 * One stateless composable serves two hosts: pre-generation (the
 * [onGenerate] "Generate report" button is the primary action) and the
 * Manage 👷 re-edit ([onSave] instead, no Generate). [config] is fully
 * hoisted — the caller owns persistence.
 */
@Composable
internal fun ReportSelectWorkersScreen(
    aiSettings: Settings,
    config: ReportWorkerConfig,
    onConfigChange: (ReportWorkerConfig) -> Unit,
    onGenerate: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    androidx.activity.compose.BackHandler { onDismiss() }
    val agentNames = remember(aiSettings) { aiSettings.agents.map { it.name } }
    // A CUSTOM report-info pick with no resolvable worker would strand the
    // icon / title / language calls — gate the primary action on it, the
    // same pre-flight the runtime worker picker applies.
    val canProceed = config.reportInfo != ReportInfoMode.CUSTOM ||
        config.reportInfoWorkers.any { aiSettings.resolveWorker(it) != null }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        TitleBar(
            helpTopic = "report_select_workers",
            title = "Report - select workers",
            subject = "Workers for report info, model info and batches",
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

        // ── Card 1: Report info ────────────────────────────────────────
        SectionCard {
            Text("Report info", fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Icon, titles, language detection", fontSize = 12.sp, color = AppColors.TextTertiary)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = config.reportInfo == ReportInfoMode.PROMPT,
                    onClick = { onConfigChange(config.copy(reportInfo = ReportInfoMode.PROMPT)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Prompt configuration", fontSize = 13.sp) }
                SegmentedButton(
                    selected = config.reportInfo == ReportInfoMode.CUSTOM,
                    onClick = { onConfigChange(config.copy(reportInfo = ReportInfoMode.CUSTOM)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Specify model or agent", fontSize = 13.sp) }
            }
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
            } else {
                Text(
                    "The report-info worker prompts run on their configured chains.",
                    fontSize = 11.sp, color = AppColors.TextDim
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── Card 2: Model info ─────────────────────────────────────────
        SectionCard {
            Text("Model info", fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Per-model icons & titles", fontSize = 12.sp, color = AppColors.TextTertiary)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = config.modelInfo == ModelInfoMode.PROMPT,
                    onClick = { onConfigChange(config.copy(modelInfo = ModelInfoMode.PROMPT)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Prompt configuration", fontSize = 13.sp) }
                SegmentedButton(
                    selected = config.modelInfo == ModelInfoMode.OWN_MODEL,
                    onClick = { onConfigChange(config.copy(modelInfo = ModelInfoMode.OWN_MODEL)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Own model", fontSize = 13.sp) }
            }
            Text(
                if (config.modelInfo == ModelInfoMode.OWN_MODEL)
                    "Own model: each report model writes its own title and icon."
                else "The model-info worker prompts run on their configured chains.",
                fontSize = 11.sp, color = AppColors.TextDim
            )
        }
        Spacer(Modifier.height(12.dp))

        // ── Card 3: Worker batches ─────────────────────────────────────
        SectionCard {
            Text("Worker batches", fontSize = 14.sp, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "Fan Meta, Translation, Tournament, Judges, Compare, Rerank, Moderation, Meta, Fan-in",
                fontSize = 12.sp, color = AppColors.TextTertiary
            )
            OptionRow(
                selected = config.batches == BatchWorkerMode.PROMPT,
                label = "Prompt configuration",
                sublabel = "Each batch uses its Internal Prompt's workers (or its run-time picker).",
                onSelect = { onConfigChange(config.copy(batches = BatchWorkerMode.PROMPT)) }
            )
            OptionRow(
                selected = config.batches == BatchWorkerMode.REPORT_MODELS,
                label = "Report models",
                sublabel = "Workers are this report's own answer models.",
                onSelect = { onConfigChange(config.copy(batches = BatchWorkerMode.REPORT_MODELS)) }
            )
            if (config.batches == BatchWorkerMode.REPORT_MODELS) {
                Text("Worker selection", fontSize = 12.sp, color = AppColors.TextTertiary)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = config.workerSelection == WorkerSelectionMode.WHEN_AVAILABLE,
                        onClick = { onConfigChange(config.copy(workerSelection = WorkerSelectionMode.WHEN_AVAILABLE)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("When available", fontSize = 13.sp) }
                    SegmentedButton(
                        selected = config.workerSelection == WorkerSelectionMode.ROUND_ROBIN,
                        onClick = { onConfigChange(config.copy(workerSelection = WorkerSelectionMode.ROUND_ROBIN)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("Round robin", fontSize = 13.sp) }
                }
                Text(
                    if (config.workerSelection == WorkerSelectionMode.ROUND_ROBIN)
                        "Each worker gets the same share of work; failures fall through to the next worker."
                    else "Fast models pick up more work.",
                    fontSize = 11.sp, color = AppColors.TextDim
                )
            }
            OptionRow(
                selected = config.batches == BatchWorkerMode.SELECT_EACH,
                label = "User selectable for each batch",
                sublabel = "Pick the workers every time a batch starts.",
                onSelect = { onConfigChange(config.copy(batches = BatchWorkerMode.SELECT_EACH)) }
            )
            OptionRow(
                selected = config.batches == BatchWorkerMode.SELECT_ONCE,
                label = "One time selectable, use for each batch",
                sublabel = "Pick once at the first batch; reused for all later batches.",
                onSelect = { onConfigChange(config.copy(batches = BatchWorkerMode.SELECT_ONCE)) }
            )
            if (config.batches == BatchWorkerMode.SELECT_ONCE) {
                if (config.batchWorkers.isEmpty()) {
                    Text(
                        "You'll pick the workers when the first batch starts.",
                        fontSize = 11.sp, color = AppColors.TextDim
                    )
                } else {
                    // The group picked at the first batch — reviewable and
                    // editable from the Manage 👷 re-edit.
                    Text("Workers picked at the first batch", fontSize = 12.sp, color = AppColors.TextTertiary)
                    config.batchWorkers.forEachIndexed { idx, w ->
                        WorkerRowEditor(
                            index = idx,
                            worker = w,
                            agentNames = agentNames,
                            aiSettings = aiSettings,
                            onChange = { nw ->
                                onConfigChange(config.copy(
                                    batchWorkers = config.batchWorkers.toMutableList().also { it[idx] = nw }
                                ))
                            },
                            onRemove = {
                                onConfigChange(config.copy(
                                    batchWorkers = config.batchWorkers.toMutableList().also { it.removeAt(idx) }
                                ))
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = { onConfigChange(config.copy(batchWorkers = config.batchWorkers + Worker(agent = "*select"))) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("+ Add worker", fontSize = 13.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
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
