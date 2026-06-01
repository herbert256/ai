package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.PromptRevision
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Edit the report's prompt body. Saving sets `hasPendingPromptChange`
 * so the result screen surfaces a "regenerate to apply" hint — the
 * model output is stale until the user re-runs.
 *
 * Every save pushes the superseded prompt onto [Report.promptHistory]
 * (see [com.ai.data.ReportStorage.updateReportPromptText]); this screen
 * surfaces that timeline as a "Previous prompts" list so an earlier
 * wording can be reviewed or restored into the editor instead of being
 * lost on edit.
 */
@Composable
fun ReportEditPromptScreen(
    reportId: String,
    initialPrompt: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onUpdate: (newPrompt: String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    // Key the saver on initialPrompt so that re-opening the overlay
    // with a different starting value doesn't restore the old draft
    // out of the SaveableStateRegistry. Without the key, an external
    // edit between two openings would silently re-surface the prior
    // text.
    var prompt by rememberSaveable(initialPrompt) { mutableStateOf(initialPrompt) }
    val canUpdate = prompt.trim().isNotBlank()

    // Revision timeline for this report, newest-first. Loaded off the
    // main thread; re-read whenever the report changes.
    val history by produceState<List<PromptRevision>>(emptyList(), reportId) {
        value = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getReport(context, reportId)?.promptHistory.orEmpty().reversed()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "report_edit_prompt", title = "Edit prompt", subject = "Saving needs a regenerate to apply", onBackClick = onBack)

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
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.ButtonBackground)
        ) { Text("Update prompt", maxLines = 1, softWrap = false) }

        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Previous prompts (${history.size})",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            // Bounded so the editor keeps the bulk of the screen; the
            // list scrolls when revisions pile up.
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(history) { rev -> PreviousPromptCard(rev, onRestore = { prompt = rev.prompt }) }
            }
        }
    }
}

/** One row in the Edit-prompt "Previous prompts" timeline. Shows when
 *  the wording was replaced and a 3-line preview; tapping it loads the
 *  text back into the editor (the user can then Update to re-run it,
 *  which itself records the current text as a new revision). */
@Composable
private fun PreviousPromptCard(rev: PromptRevision, onRestore: () -> Unit) {
    val whenLabel = remember(rev.timestamp) {
        java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(rev.timestamp))
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .clickable { onRestore() }
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(whenLabel, fontSize = 11.sp, color = AppColors.TextTertiary)
            Text("↩ Tap to restore", fontSize = 11.sp, color = AppColors.SuccessAccent)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            rev.prompt, fontSize = 13.sp, color = AppColors.TextPrimary,
            maxLines = 3, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Edit the report's **short** title (the ≤25-char line on AI Reports list
 * cards, [com.ai.data.Report.title]). One field; the 🐞 opens this report's
 * short-title generation trace ("report/title-short").
 */
@Composable
fun ReportEditShortTitleScreen(
    reportId: String,
    initialTitle: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onFindAlternativeTitle: () -> Unit = {},
    injectedTitle: String? = null,
    onConsumeInjectedTitle: () -> Unit = {},
    onUpdate: (newTitle: String) -> Unit
) = SingleTitleEditScreen(
    reportId = reportId,
    initialTitle = initialTitle,
    titleBarTitle = "Edit short title",
    helpTopic = "report_edit_short_title",
    fieldLabel = "Short title (list cards)",
    findButtonText = "Find alternative short title",
    traceCategory = "report/title-short",
    // The short title is the primary one (drives barTitle's fallback), so
    // it must not be blanked out.
    allowBlank = false,
    onBack = onBack,
    onNavigateToTraceFile = onNavigateToTraceFile,
    onFindAlternativeTitle = onFindAlternativeTitle,
    injectedTitle = injectedTitle,
    onConsumeInjectedTitle = onConsumeInjectedTitle,
    onUpdate = onUpdate
)

/**
 * Edit the report's **long** title (the ≤50-char top-bar orange line,
 * [com.ai.data.Report.titleLong]; `barTitle = titleLong ?: title`). One
 * field that may be blanked to fall back to the short title; the 🐞 opens
 * this report's long-title generation trace ("report/title-long").
 */
@Composable
fun ReportEditLongTitleScreen(
    reportId: String,
    initialTitle: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onFindAlternativeTitle: () -> Unit = {},
    injectedTitle: String? = null,
    onConsumeInjectedTitle: () -> Unit = {},
    onUpdate: (newTitle: String) -> Unit
) = SingleTitleEditScreen(
    reportId = reportId,
    initialTitle = initialTitle,
    titleBarTitle = "Edit long title",
    helpTopic = "report_edit_long_title",
    fieldLabel = "Long title (top-bar line)",
    findButtonText = "Find alternative long title",
    traceCategory = "report/title-long",
    // Blank long title is valid — barTitle falls back to the short one.
    allowBlank = true,
    onBack = onBack,
    onNavigateToTraceFile = onNavigateToTraceFile,
    onFindAlternativeTitle = onFindAlternativeTitle,
    injectedTitle = injectedTitle,
    onConsumeInjectedTitle = onConsumeInjectedTitle,
    onUpdate = onUpdate
)

/**
 * Shared body for the two report-title editors. Title changes don't affect
 * any outbound API call, so saving updates the persisted report + UiState
 * in place without flagging the report as needing a regenerate.
 *
 * The title is filled in dynamically by a one-shot API call
 * (IconGenerationManager.kickOffReportTitleGeneration runs two: short +
 * long). Each call traces under its own category, so [traceCategory] picks
 * out this field's call for the 🐞 icon. Read off the main thread —
 * getTraceFiles parses every trace file.
 */
@Composable
private fun SingleTitleEditScreen(
    reportId: String,
    initialTitle: String,
    titleBarTitle: String,
    helpTopic: String,
    fieldLabel: String,
    findButtonText: String,
    traceCategory: String,
    allowBlank: Boolean,
    onBack: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onFindAlternativeTitle: () -> Unit,
    injectedTitle: String?,
    onConsumeInjectedTitle: () -> Unit,
    onUpdate: (newTitle: String) -> Unit
) {
    BackHandler { onBack() }
    // Same caveat as ReportEditPromptScreen above — key on the initial
    // value so a stale draft doesn't outlive an external edit.
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    // A picked "Find alternative …" candidate fills the field.
    LaunchedEffect(injectedTitle) { injectedTitle?.let { title = it; onConsumeInjectedTitle() } }
    val canUpdate = allowBlank || title.trim().isNotBlank()

    val titleTraceFilenameState = produceState<String?>(initialValue = null, reportId, traceCategory) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == reportId && it.category == traceCategory }
                .maxByOrNull { it.timestamp }?.filename
        }
    }
    val titleTraceFilename = titleTraceFilenameState.value

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = helpTopic, title = titleBarTitle, subject = "Metadata only — no regenerate needed", onBackClick = onBack,
            onTrace = titleTraceFilename?.let { fn -> { onNavigateToTraceFile(fn) } }
        )

        Button(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.ButtonBackground)
        ) { Text("Update title", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text(fieldLabel) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onFindAlternativeTitle,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text(findButtonText, maxLines = 1, softWrap = false) }
    }
}

/**
 * Edit one model's per-model title (the title generated from that model's
 * response). Like [ReportEditShortTitleScreen] but per-agent: saving updates the
 * [com.ai.data.ReportAgent.modelTitle] in place — it doesn't re-run anything.
 * The 🐞 trace icon opens this agent's model_title call trace, looked up from
 * the stored [com.ai.data.ReportAgent.modelTitleTraceFile] (reliable per-agent,
 * unlike scanning the shared "model/titles" trace category).
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
            helpTopic = "report_edit_model_title", title = "Edit model title", subject = "Rename one model's answer title", onBackClick = onBack,
            onTrace = traceFilename?.takeIf { it.isNotBlank() }?.let { fn -> { onNavigateToTraceFile(fn) } }
        )

        Button(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.ButtonBackground)
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

/**
 * Edit one fan-out response's title — the per-pair sibling of
 * [ReportEditModelTitleScreen]. The pair's title lives on its
 * [com.ai.data.SecondaryResult] row (there's no in-memory editor field), so
 * this screen reads the row off disk, keyed on [iconRefreshTick] so a
 * Find-alternative pick (which persists straight to the row) is reflected when
 * the editor repaints underneath. Saving writes back via
 * [com.ai.viewmodel.ReportViewModel.updateFanOutPairTitle].
 */
@Composable
fun ReportEditPairTitleScreen(
    reportId: String,
    pairId: String,
    iconRefreshTick: Int,
    onBack: () -> Unit,
    onFindAlternativeTitles: () -> Unit = {},
    onUpdate: (newTitle: String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val pair by produceState<com.ai.data.SecondaryResult?>(initialValue = null, reportId, pairId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            com.ai.data.SecondaryResultStorage.listForReport(context, reportId).firstOrNull { it.id == pairId }
        }
    }
    val loadedTitle = pair?.title.orEmpty()
    // Key on the loaded title so a fresh disk value (e.g. after a Find-alt
    // pick) re-seeds the field; same caveat as the editors above.
    var title by rememberSaveable(loadedTitle) { mutableStateOf(loadedTitle) }
    val modelName = pair?.let { "${it.providerId} · ${com.ai.ui.shared.shortModelName(it.model)}" } ?: ""
    val canUpdate = title.trim().isNotBlank()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_edit_pair_title", title = "Edit title", subject = "Rename one fan-out response title", onBackClick = onBack
        )

        Button(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.ButtonBackground)
        ) { Text("Update title", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        if (modelName.isNotBlank()) {
            Text(modelName, fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
        }
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
