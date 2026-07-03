package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.PromptRevision
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelLabel
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

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "report_edit_prompt", title = "Edit prompt", subject = "Saving needs a regenerate to apply", onBackClick = onBack)

        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            label = { Text("Prompt") },
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = AppColors.outlinedFieldColors()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { onUpdate(prompt.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
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
    aiSettings: Settings,
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
    aiSettings = aiSettings,
    titleBarTitle = "Edit short title",
    helpTopic = "report_edit_short_title",
    fieldLabel = "Short title (list cards)",
    findButtonText = "Find alternative short title",
    traceCategory = "report/title-short",
    titlePromptName = "report-title-short",
    isLongTitle = false,
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
    aiSettings: Settings,
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
    aiSettings = aiSettings,
    titleBarTitle = "Edit long title",
    helpTopic = "report_edit_long_title",
    fieldLabel = "Long title (top-bar line)",
    findButtonText = "Find alternative long title",
    traceCategory = "report/title-long",
    titlePromptName = "report-title-long",
    isLongTitle = true,
    // Blank long title is valid — barTitle falls back to the short one.
    allowBlank = true,
    onBack = onBack,
    onNavigateToTraceFile = onNavigateToTraceFile,
    onFindAlternativeTitle = onFindAlternativeTitle,
    injectedTitle = injectedTitle,
    onConsumeInjectedTitle = onConsumeInjectedTitle,
    onUpdate = onUpdate
)

/** The recorded title-generation API call surfaced on the title editors
 *  as Model + API-interaction cards (mirrors the Icon lookup screen). */
private data class TitleApiCard(
    val providerId: String,
    val model: String,
    val cost: Double,
    val apiInteraction: String,
    /** Bundled internal-prompt name that produced this title + its id (for
     *  the edit pencil). Blank id → pencil hidden. */
    val promptName: String,
    val promptId: String
)

/** Model + API-interaction card pair (Icon-lookup style) for a title
 *  editor. Scrollable + weighted so a long interaction doesn't crowd out
 *  the buttons below; renders nothing when [card] is null (manual title /
 *  no AI call recorded). The first card leads with the generating prompt's
 *  name + an edit pencil (→ internal-prompt editor). */
@Composable
private fun ColumnScope.TitleApiCards(card: TitleApiCard?) {
    val editPrompt = com.ai.ui.shared.LocalEditInternalPrompt.current
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        card?.let { c ->
            Spacer(modifier = Modifier.height(12.dp))
            // Model / Prompt (+ edit pencil) / Cost — one uniform text size.
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val label = if (c.model.isNotBlank())
                        modelLabel(c.providerId, c.model)
                    else "(unknown model)"
                    Text("Model: $label", fontSize = 14.sp, color = AppColors.TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Prompt: ${c.promptName.ifBlank { "(unknown)" }}",
                            fontSize = 14.sp, color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f))
                        if (c.promptId.isNotBlank()) {
                            Text(
                                com.ai.ui.shared.LocalMetadataIcons.current.edit,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .clickable { editPrompt(c.promptId) }
                                    .padding(start = 8.dp)
                            )
                        }
                    }
                    Text("Cost: ${formatCents(c.cost)}",
                        fontSize = 14.sp, color = AppColors.TextPrimary)
                }
            }
            // API interaction card — plain monospace, NO markdown.
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("API interaction", fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp))
                    Text(
                        c.apiInteraction.ifBlank { "(no interaction recorded)" },
                        fontSize = 13.sp, color = AppColors.TextPrimary, lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

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
    aiSettings: Settings,
    titleBarTitle: String,
    helpTopic: String,
    fieldLabel: String,
    findButtonText: String,
    traceCategory: String,
    /** Bundled internal-prompt name whose template produced this title
     *  (`report-title-short` / `report-title-long`) — used to rebuild the
     *  API-interaction card's `[user]` turn. */
    titlePromptName: String,
    isLongTitle: Boolean,
    allowBlank: Boolean,
    onBack: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onFindAlternativeTitle: () -> Unit,
    injectedTitle: String?,
    onConsumeInjectedTitle: () -> Unit,
    onUpdate: (newTitle: String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
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

    // The recorded API call that generated this title — Model + API
    // interaction cards, mirroring the Icon lookup screen. Null when the
    // title was set manually / never AI-generated (then the cards are hidden).
    val apiCard by produceState<TitleApiCard?>(initialValue = null, reportId, isLongTitle, initialTitle) {
        value = withContext(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId) ?: return@withContext null
            val model = (if (isLongTitle) r.titleLongModel else r.titleModel).orEmpty()
            val cost = if (isLongTitle) r.titleLongInputCost + r.titleLongOutputCost
                       else r.titleInputCost + r.titleOutputCost
            // Find-alt provenance marker — the title came from the alt/* prompt.
            val isAlt = if (isLongTitle) r.titleLongPromptUsed == "report_title_long_alt"
                        else r.titlePromptUsed == "report_title_alt"
            val aiGenerated = isAlt ||
                if (isLongTitle) model.isNotBlank() || cost > 0.0
                else !r.titlePromptUsed.isNullOrBlank()
            if (!aiGenerated) return@withContext null
            // Alt picks ran the alt/<report_title[_long]> prompt; the initial
            // generation ran the workers prompt. Both substitute @PROMPT@.
            val templateCategory = if (isAlt) "alt" else "workers"
            val templateName = if (isAlt) (if (isLongTitle) "report_title_long" else "report_title")
                               else titlePromptName
            val template = aiSettings.internalPrompts.firstOrNull {
                it.category == templateCategory && it.name == templateName
            }
            val resolved = template?.text?.replace("@PROMPT@", r.prompt).orEmpty()
            val response = if (isLongTitle) r.titleLong else r.title
            TitleApiCard(
                providerId = model.substringBefore('/', ""),
                model = model.substringAfter('/', ""),
                cost = cost,
                apiInteraction = buildOneShotApiInteraction(resolved, response),
                promptName = template?.let { "${it.category}/${it.name}" } ?: "$templateCategory/$templateName",
                promptId = template?.id.orEmpty()
            )
        }
    }
    val regenerate = com.ai.ui.shared.LocalRegenerateMetaItem.current

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = helpTopic, title = titleBarTitle, subject = "Metadata only — no regenerate needed", onBackClick = onBack,
            onReload = {
                regenerate(
                    reportId,
                    if (isLongTitle) com.ai.viewmodel.MetaRegenKind.REPORT_TITLE_LONG
                    else com.ai.viewmodel.MetaRegenKind.REPORT_TITLE_SHORT,
                    null
                )
            },
            onTrace = titleTraceFilename?.let { fn -> { onNavigateToTraceFile(fn) } }
        )

        OutlinedButton(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Update title", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text(fieldLabel) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )

        TitleApiCards(apiCard)

        Spacer(modifier = Modifier.height(8.dp))

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
    aiSettings: Settings,
    traceFilename: String? = null,
    onNavigateToTraceFile: (String) -> Unit = {},
    onBack: () -> Unit,
    onFindAlternativeTitles: () -> Unit = {},
    injectedTitle: String? = null,
    onConsumeInjectedTitle: () -> Unit = {},
    /** Hide "Find alternative titles" when false (the standalone
     *  Report-model route can't host the alternatives picker). */
    showFindAlternatives: Boolean = true,
    onUpdate: (newTitle: String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    LaunchedEffect(injectedTitle) { injectedTitle?.let { title = it; onConsumeInjectedTitle() } }
    val canUpdate = title.trim().isNotBlank()

    // The per-model title-generation call (model-titles worker, @RESPONSE@ =
    // this agent's answer) as Model + API-interaction cards — same as the
    // Icon lookup screen. Null when the title was set manually.
    val apiCard by produceState<TitleApiCard?>(initialValue = null, reportId, agentId, initialTitle) {
        value = withContext(Dispatchers.IO) {
            val r = ReportStorage.getReport(context, reportId) ?: return@withContext null
            val agent = r.agents.firstOrNull { it.agentId == agentId } ?: return@withContext null
            val model = agent.modelTitleModel.orEmpty()
            val cost = agent.modelTitleInputCost + agent.modelTitleOutputCost
            val aiGenerated = !agent.modelTitlePromptUsed.isNullOrBlank() ||
                model.isNotBlank() || cost > 0.0
            if (!aiGenerated) return@withContext null
            // Find-alt picks ran alt/model_title; the initial gen ran the
            // workers model-titles prompt. Both substitute @RESPONSE@.
            val isAlt = agent.modelTitlePromptUsed == "model_title_alt"
            val templateCategory = if (isAlt) "alt" else "workers"
            val templateName = if (isAlt) "model_title" else "model-titles"
            val template = aiSettings.internalPrompts.firstOrNull {
                it.category == templateCategory && it.name == templateName
            }
            val resolved = template?.text?.replace("@RESPONSE@", agent.responseBody.orEmpty()).orEmpty()
            TitleApiCard(
                providerId = model.substringBefore('/', ""),
                model = model.substringAfter('/', ""),
                cost = cost,
                apiInteraction = buildOneShotApiInteraction(resolved, agent.modelTitle),
                promptName = template?.let { "${it.category}/${it.name}" } ?: "$templateCategory/$templateName",
                promptId = template?.id.orEmpty()
            )
        }
    }
    val regenerate = com.ai.ui.shared.LocalRegenerateMetaItem.current
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_edit_model_title", title = "Edit model title", subject = "Rename one model's answer title", onBackClick = onBack,
            onReload = { regenerate(reportId, com.ai.viewmodel.MetaRegenKind.MODEL_TITLE, agentId) },
            onTrace = traceFilename?.takeIf { it.isNotBlank() }?.let { fn -> { onNavigateToTraceFile(fn) } }
        )

        OutlinedButton(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Update title", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))

        Text(modelName, fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedFieldColors()
        )
        TitleApiCards(apiCard)
        if (showFindAlternatives) {
            OutlinedButton(
                onClick = onFindAlternativeTitles,
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Find alternative titles", maxLines = 1, softWrap = false) }
        }
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
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_edit_pair_title", title = "Edit title", subject = "Rename one fan-out response title", onBackClick = onBack
        )

        OutlinedButton(
            onClick = { onUpdate(title.trim()) },
            enabled = canUpdate,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
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
