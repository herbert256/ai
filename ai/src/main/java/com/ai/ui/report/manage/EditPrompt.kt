package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Edit the report's prompt body. Saving sets `hasPendingPromptChange`
 * so the result screen surfaces a "regenerate to apply" hint — the
 * model output is stale until the user re-runs.
 */
@Composable
fun ReportEditPromptScreen(
    initialPrompt: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onUpdate: (newPrompt: String) -> Unit
) {
    BackHandler { onBack() }
    // Key the saver on initialPrompt so that re-opening the overlay
    // with a different starting value doesn't restore the old draft
    // out of the SaveableStateRegistry. Without the key, an external
    // edit between two openings would silently re-surface the prior
    // text.
    var prompt by rememberSaveable(initialPrompt) { mutableStateOf(initialPrompt) }
    val canUpdate = prompt.trim().isNotBlank()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "report_edit_prompt", title = "Edit prompt", onBackClick = onBack)

        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onUpdate(prompt.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update prompt", maxLines = 1, softWrap = false) }
    }
}

/**
 * Edit just the report title. Title changes don't affect any outbound
 * API call, so saving updates the persisted report + UiState in place
 * without flagging the report as needing a regenerate.
 */
@Composable
fun ReportEditTitleScreen(
    reportId: String,
    initialTitle: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onFindAlternativeTitles: () -> Unit = {},
    injectedTitle: String? = null,
    onConsumeInjectedTitle: () -> Unit = {},
    onUpdate: (newTitle: String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    // Same caveat as ReportEditPromptScreen above — key on
    // initialTitle so a stale draft doesn't outlive an external edit.
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    // A picked "Find alternative titles" candidate fills the field.
    LaunchedEffect(injectedTitle) { injectedTitle?.let { title = it; onConsumeInjectedTitle() } }
    val canUpdate = title.trim().isNotBlank()

    // The report title is filled in dynamically by a one-shot API call
    // (IconGenerationManager.kickOffReportTitleGeneration, traced under
    // category "report_title"). Surface that call's trace via the 🐞 icon
    // when it exists. Read off the main thread — getTraceFiles parses
    // every trace file.
    val titleTraceFilenameState = produceState<String?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == reportId && it.category == "report_title" }
                .maxByOrNull { it.timestamp }?.filename
        }
    }
    val titleTraceFilename = titleTraceFilenameState.value

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_edit_title", title = "Edit title", onBackClick = onBack,
            onTrace = titleTraceFilename?.let { fn -> { onNavigateToTraceFile(fn) } }
        )

        Button(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update title", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onFindAlternativeTitles,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Find alternative titles", maxLines = 1, softWrap = false) }
    }
}

/**
 * Edit one model's per-model title (the title generated from that model's
 * response). Like [ReportEditTitleScreen] but per-agent: saving updates the
 * [com.ai.data.ReportAgent.modelTitle] in place — it doesn't re-run anything.
 * The 🐞 trace icon opens this agent's model_title call trace, looked up from
 * the stored [com.ai.data.ReportAgent.modelTitleTraceFile] (reliable per-agent,
 * unlike scanning the shared "model_title" trace category).
 */
@Composable
fun ReportEditModelTitleScreen(
    reportId: String,
    agentId: String,
    modelName: String,
    initialTitle: String,
    traceFilename: String? = null,
    onNavigateToTraceFile: (String) -> Unit = {},
    onBack: () -> Unit,
    onFindAlternativeTitles: () -> Unit = {},
    injectedTitle: String? = null,
    onConsumeInjectedTitle: () -> Unit = {},
    onUpdate: (newTitle: String) -> Unit
) {
    BackHandler { onBack() }
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    LaunchedEffect(injectedTitle) { injectedTitle?.let { title = it; onConsumeInjectedTitle() } }
    val canUpdate = title.trim().isNotBlank()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_edit_model_title", title = "Edit model title", onBackClick = onBack,
            onTrace = traceFilename?.takeIf { it.isNotBlank() }?.let { fn -> { onNavigateToTraceFile(fn) } }
        )

        Button(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Update title", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        Text(modelName, fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )
        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onFindAlternativeTitles,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Find alternative titles", maxLines = 1, softWrap = false) }
    }
}
