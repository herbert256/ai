package com.ai.ui.report.manage.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.Settings
import com.ai.ui.helpers.ContentWithThinkSections
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateHome
import com.ai.ui.shared.ParametersSelectScreen
import com.ai.ui.shared.SystemPromptSelectScreen
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.PromptEditReplayResult
import com.ai.viewmodel.PromptEditReplayState
import java.util.Locale

@Composable
internal fun PromptEditReplayScreen(
    reportId: String,
    targetId: String,
    title: String,
    modelLabel: String,
    initialPrompt: String,
    state: PromptEditReplayState?,
    aiSettings: Settings,
    onCallModel: (String, List<String>, String?) -> Unit,
    onUseResponse: () -> Unit,
    onTrace: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val navigateHome = LocalNavigateHome.current
    var promptText by rememberSaveable(reportId, targetId, initialPrompt) { mutableStateOf(initialPrompt) }
    var selectedParamsIds by rememberSaveable(reportId, targetId) { mutableStateOf<List<String>>(emptyList()) }
    var selectedSystemPromptId by rememberSaveable(reportId, targetId) { mutableStateOf<String?>(null) }
    var showParamsPicker by rememberSaveable(reportId, targetId) { mutableStateOf(false) }
    var showSystemPromptPicker by rememberSaveable(reportId, targetId) { mutableStateOf(false) }

    if (showParamsPicker) {
        ParametersSelectScreen(
            aiSettings = aiSettings,
            selectedIds = selectedParamsIds,
            onConfirm = { selectedParamsIds = it },
            onBack = { showParamsPicker = false },
            onNavigateHome = navigateHome
        )
        return
    }
    if (showSystemPromptPicker) {
        SystemPromptSelectScreen(
            aiSettings = aiSettings,
            selectedId = selectedSystemPromptId,
            onSelect = { selectedSystemPromptId = it },
            onBack = { showSystemPromptPicker = false },
            onNavigateHome = navigateHome
        )
        return
    }

    val result = state?.result ?: PromptEditReplayResult.Pending
    val running = state?.isRunning == true || result is PromptEditReplayResult.Running
    val selectedParamNames = aiSettings.parameters
        .filter { it.id in selectedParamsIds }
        .joinToString(", ") { it.name }
    val selectedSystemPromptName = selectedSystemPromptId
        ?.let { id -> aiSettings.systemPrompts.firstOrNull { it.id == id }?.name }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_single_result",
            title = title,
            subject = modelLabel,
            onBackClick = onBack,
            onParameters = { showParamsPicker = true },
            onSystemPrompt = { showSystemPromptPicker = true }
        )

        Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Prompt") },
                    enabled = !running,
                    minLines = 5,
                    maxLines = 10,
                    colors = AppColors.outlinedFieldColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp)
                )
                if (selectedParamNames.isNotBlank() || selectedSystemPromptName != null) {
                    Text(
                        listOfNotNull(
                            selectedParamNames.takeIf { it.isNotBlank() }?.let { "Parameters: $it" },
                            selectedSystemPromptName?.let { "System: $it" }
                        ).joinToString("  ·  "),
                        color = AppColors.TextTertiary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { onCallModel(promptText, selectedParamsIds, selectedSystemPromptId) },
                    enabled = promptText.isNotBlank() && !running,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.SecondaryAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (running) "Running…" else "Call model API", maxLines = 1, softWrap = false)
                }
                state?.unavailableMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = AppColors.DangerAccent, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        PromptEditResultCard(
            result = result,
            onUseResponse = onUseResponse,
            onTrace = onTrace,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
private fun PromptEditResultCard(
    result: PromptEditReplayResult,
    onUseResponse: () -> Unit,
    onTrace: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground), modifier = modifier.padding(bottom = 16.dp)) {
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${com.ai.ui.shared.LocalMetadataIcons.current.edit} Edit", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.SecondaryAccent)
                Spacer(modifier = Modifier.width(8.dp))
                if (result is PromptEditReplayResult.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(promptEditStatus(result), color = AppColors.TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.weight(1f))
                (result as? PromptEditReplayResult.Success)?.cost?.let { cost ->
                    Text("${formatCents(cost)} ¢", color = AppColors.InfoAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                durationMs(result)?.let { ms ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(formatDuration(ms), color = AppColors.TextTertiary, fontSize = 12.sp)
                }
                traceFile(result)?.let { fn ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(com.ai.data.MetadataIconsHolder.current.traces, fontSize = 16.sp, modifier = Modifier.clickable { onTrace(fn) })
                }
            }
            when (result) {
                PromptEditReplayResult.Pending -> Text("No result yet.", color = AppColors.TextTertiary, fontSize = 13.sp)
                PromptEditReplayResult.Running -> Text("Running model call…", color = AppColors.TextTertiary, fontSize = 13.sp)
                is PromptEditReplayResult.Error -> Text(result.message, color = AppColors.DangerAccent, fontSize = 14.sp)
                is PromptEditReplayResult.Success -> {
                    Button(
                        onClick = onUseResponse,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.SuccessAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use this response", maxLines = 1, softWrap = false) }
                    ContentWithThinkSections(analysis = result.response)
                }
            }
        }
    }
}

private fun promptEditStatus(result: PromptEditReplayResult): String = when (result) {
    PromptEditReplayResult.Pending -> "idle"
    PromptEditReplayResult.Running -> "running"
    is PromptEditReplayResult.Success -> "success"
    is PromptEditReplayResult.Error -> "error"
}

private fun durationMs(result: PromptEditReplayResult): Long? = when (result) {
    is PromptEditReplayResult.Success -> result.durationMs
    is PromptEditReplayResult.Error -> result.durationMs
    else -> null
}

private fun traceFile(result: PromptEditReplayResult): String? = when (result) {
    is PromptEditReplayResult.Success -> result.traceFile
    is PromptEditReplayResult.Error -> result.traceFile
    else -> null
}

private fun formatDuration(durationMs: Long): String =
    if (durationMs < 1000) "${durationMs}ms"
    else String.format(Locale.US, "%.1fs", durationMs / 1000.0)
