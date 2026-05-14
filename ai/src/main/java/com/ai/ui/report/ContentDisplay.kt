package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ai.data.*
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val conclusionTagRegex = Regex("<conclusion>.*?</conclusion>", RegexOption.DOT_MATCHES_ALL)
private val motivationTagRegex = Regex("<motivation>.*?</motivation>", RegexOption.DOT_MATCHES_ALL)

@Composable
fun ReportsViewerScreen(
    reportId: String,
    initialSelectedAgentId: String? = null,
    initialSection: String? = null,  // "prompt" / "costs" — driven from the Report Result View buttons
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit = onDismiss,
    onNavigateToTraceFile: (String) -> Unit = {},
    onContinueWithCurrent: (String, String) -> Unit = { _, _ -> },
    onContinueWithAgentPicker: (String, String) -> Unit = { _, _ -> },
    onContinueWithOnTheFly: (String, String) -> Unit = { _, _ -> },
    onRemoveAgent: (String, String) -> Unit = { _, _ -> },
    onRegenerateAgent: (String, String) -> Unit = { _, _ -> }
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    // IO load → keeps the UI thread free while reading JSON. The
    // initial Loading sentinel distinguishes "still reading from disk"
    // from "read finished, file genuinely missing" so the empty-state
    // text only appears after the load completes.
    val reportState = produceState<ReportLoadState>(initialValue = ReportLoadState.Loading, reportId) {
        val r = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
        value = if (r != null) ReportLoadState.Loaded(r) else ReportLoadState.NotFound
    }

    when (val s = reportState.value) {
        ReportLoadState.Loading -> {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TitleBar(helpTopic = "content_model_response", title = "View Reports", onBackClick = onDismiss,
                    modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp))
            }
            return
        }
        ReportLoadState.NotFound -> {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TitleBar(helpTopic = "content_model_response", title = "View Reports", onBackClick = onDismiss,
                    modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp))
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Report not found", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            }
            return
        }
        is ReportLoadState.Loaded -> {
            ReportsViewerScreenLoaded(s.report, initialSelectedAgentId, initialSection,
                onDismiss, onNavigateHome, onNavigateToTraceFile,
                onContinueWithCurrent, onContinueWithAgentPicker, onContinueWithOnTheFly,
                onRemoveAgent, onRegenerateAgent)
        }
    }
}

private sealed interface ReportLoadState {
    data object Loading : ReportLoadState
    data object NotFound : ReportLoadState
    data class Loaded(val report: Report) : ReportLoadState
}

/** A single tab in a report's language picker. Built from the
 *  TRANSLATE secondaries on the report — Original always present;
 *  Dutch / German / … added per distinct [SecondaryResult.targetLanguage].
 *  Used by the in-app report viewer (ContentDisplay) and the per-kind
 *  per-Meta-prompt result lists (SecondaryResultsScreen). [key] is a
 *  filesystem-safe lowercase id; [displayName] is the human English
 *  name and matches [SecondaryResult.targetLanguage]. */
internal data class LangTab(val key: String, val displayName: String, val nativeName: String?) {
    companion object { const val ORIGINAL_KEY = "original" }
}

@Composable
internal fun LanguagePickerRow(
    languages: List<LangTab>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
) {
    if (languages.size <= 1) return
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        languages.forEach { lang ->
            val isSelected = lang.key == selectedKey
            Button(
                onClick = { onSelect(lang.key) },
                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Green else Color(0xFF3A3A4A)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    lang.displayName,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, softWrap = false
                )
            }
        }
    }
}

/** Two-segment toggle for the model picker style on the Reports
 *  viewer: "Buttons" (a button per model) vs "Pulldown" (the
 *  collapsed dropdown). Mirrors the LanguagePickerRow look. */
@Composable
private fun PickerStyleSwitch(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("buttons" to "Buttons", "dropdown" to "Pulldown").forEach { (key, label) ->
            val isSelected = key == selected
            Button(
                onClick = { onSelect(key) },
                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Indigo else Color(0xFF3A3A4A)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, softWrap = false
                )
            }
        }
    }
}

/** Group TRANSLATE secondaries by language and produce the picker
 *  list. "Original" is always first. */
internal fun buildLangTabs(translates: List<SecondaryResult>): List<LangTab> {
    val out = mutableListOf(LangTab(LangTab.ORIGINAL_KEY, "Original", null))
    val seen = LinkedHashMap<String, String?>()
    translates.forEach { t ->
        val lang = t.targetLanguage?.takeIf { it.isNotBlank() } ?: return@forEach
        if (lang !in seen) seen[lang] = t.targetLanguageNative
    }
    seen.forEach { (lang, native) ->
        val key = lang.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
        out += LangTab(key, lang, native?.takeIf { it != lang })
    }
    return out
}

@Composable
private fun ReportsViewerScreenLoaded(
    report: Report,
    initialSelectedAgentId: String?,
    initialSection: String?,
    onDismiss: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onContinueWithCurrent: (String, String) -> Unit,
    onContinueWithAgentPicker: (String, String) -> Unit,
    onContinueWithOnTheFly: (String, String) -> Unit,
    onRemoveAgent: (String, String) -> Unit,
    onRegenerateAgent: (String, String) -> Unit
) {
    val context = LocalContext.current
    var showContinuePicker by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }

    // Load TRANSLATE secondaries up front; the picker / overlay both
    // key on this list. Empty list → no picker shown, viewer behaves
    // as before.
    val translatesState = produceState(initialValue = emptyList<SecondaryResult>(), report.id) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, report.id, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
        }
    }
    val translates = translatesState.value
    val langTabs = remember(translates) { buildLangTabs(translates) }
    var selectedLangKey by rememberSaveable { mutableStateOf(LangTab.ORIGINAL_KEY) }
    // Keep the selection valid if translations finish loading after
    // first composition — if the previously chosen key dropped off the
    // list (e.g. a translation was deleted) snap back to Original.
    LaunchedEffect(langTabs) {
        if (langTabs.none { it.key == selectedLangKey }) selectedLangKey = LangTab.ORIGINAL_KEY
    }
    // Quick lookups for the active language: by-targetId → translated
    // content. Recomputed only when the selected language or the
    // translation list changes.
    val translationByTarget = remember(translates, selectedLangKey) {
        if (selectedLangKey == LangTab.ORIGINAL_KEY) emptyMap()
        else {
            val tab = langTabs.firstOrNull { it.key == selectedLangKey }
            val langName = tab?.displayName ?: return@remember emptyMap()
            translates
                .filter { it.targetLanguage == langName }
                .associate {
                    val k = (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "")
                    k to (it.content ?: "")
                }
        }
    }

    // Single-section variants: just the prompt, or just the cost table — no agent picker,
    // no per-agent body. These come from the View row's Prompt / Costs buttons.
    if (initialSection == "prompt" || initialSection == "costs") {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val title = if (initialSection == "prompt") "Prompt" else "Cost summary"
            val sectionHelpTopic = if (initialSection == "prompt") "prompt_view" else "cost_view"
            // Resolve the prompt text up front so the title-bar 📋
            // icon and the body Text below render the same string.
            // Costs view has no copy target — the helper renders a
            // table, not a copyable string.
            val displayPrompt = translationByTarget["PROMPT:prompt"] ?: report.prompt
            TitleBar(helpTopic = sectionHelpTopic,
                title = title,
                reportIcon = report.icon?.takeIf { it.isNotBlank() } ?: "📝",
                onBackClick = onDismiss,
                onCopy = if (initialSection == "prompt") {
                    displayPrompt.takeIf { it.isNotBlank() }?.let {
                        { com.ai.ui.shared.copyToClipboard(context, displayPrompt, "prompt") }
                    }
                } else null,
                onShare = if (initialSection == "prompt") {
                    displayPrompt.takeIf { it.isNotBlank() }?.let {
                        { com.ai.ui.shared.shareText(context, displayPrompt, "Prompt — ${report.title}") }
                    }
                } else null,
                modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp))
            // Costs aggregate every API call (including translation
            // calls) so the language picker doesn't apply — only the
            // prompt screen shows the picker.
            if (initialSection == "prompt") {
                LanguagePickerRow(langTabs, selectedLangKey, onSelect = { selectedLangKey = it })
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                if (initialSection == "prompt") {
                    if (displayPrompt.isBlank()) {
                        Text("(no prompt recorded)", color = AppColors.TextTertiary, fontSize = 14.sp)
                    } else {
                        Text(displayPrompt, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
                    }
                } else {
                    val hasAgentCosts = report.agents.any { it.tokenUsage != null && (it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR) }
                    val hasSecondaryCostsState = produceState(initialValue = false, report.id) {
                        value = withContext(Dispatchers.IO) {
                            SecondaryResultStorage.listForReport(context, report.id).any { it.tokenUsage != null }
                        }
                    }
                    if (!hasAgentCosts && !hasSecondaryCostsState.value) {
                        Text("(no usage recorded)", color = AppColors.TextTertiary, fontSize = 14.sp)
                    } else {
                        ReportCostTable(report = report)
                    }
                }
            }
        }
        return
    }

    val agentsWithResults = remember(report) {
        report.agents.filter { it.reportStatus == ReportStatus.SUCCESS }.sortedBy { it.agentName.lowercase() }
    }
    var selectedAgentId by rememberSaveable { mutableStateOf(initialSelectedAgentId ?: agentsWithResults.firstOrNull()?.agentId) }
    var showOnePage by rememberSaveable { mutableStateOf(false) }
    if (showOnePage) {
        OnePageReportView(
            report = report,
            agentsWithResults = agentsWithResults,
            translationByTarget = translationByTarget,
            langTabs = langTabs,
            selectedLangKey = selectedLangKey,
            onSelectLang = { selectedLangKey = it },
            onBack = { showOnePage = false }
        )
        return
    }
    val activeChatAgentId = selectedAgentId
    if (showContinuePicker && activeChatAgentId != null) {
        ContinueInChatPickerScreen(
            onPickCurrent = { showContinuePicker = false; onContinueWithCurrent(report.id, activeChatAgentId) },
            onPickAgentPicker = { showContinuePicker = false; onContinueWithAgentPicker(report.id, activeChatAgentId) },
            onPickOnTheFly = { showContinuePicker = false; onContinueWithOnTheFly(report.id, activeChatAgentId) },
            onBack = { showContinuePicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }
    val selectedReportAgent = selectedAgentId?.let { id -> report.agents.find { it.agentId == id } }
    val scrollState = rememberScrollState()
    LaunchedEffect(selectedAgentId) { scrollState.scrollTo(0) }

    // Trace lookup for the currently-selected agent \u2014 hoisted above
    // the TitleBar so the bar's \ud83d\udc1e slot can open it. Re-fires whenever
    // the user picks a different agent button.
    val headerTraceFilenameState = produceState<String?>(
        initialValue = null,
        report.id, selectedReportAgent?.model, selectedReportAgent?.agentId
    ) {
        val agent = selectedReportAgent
        value = if (agent == null) null else withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == report.id && it.model == agent.model }
                .maxByOrNull { it.timestamp }?.filename
        }
    }
    val headerTraceFilename = headerTraceFilenameState.value
    val navToModelInfo = com.ai.ui.shared.LocalNavigateToModelInfo.current
    val selectedProviderService = selectedReportAgent?.let { AppService.findById(it.provider) }
    // Display body for the selected agent — translated copy when a
    // language is active, otherwise the original response. Hoisted
    // here so the title-bar 📋 / 💬 actions and the body renderer
    // share one definition.
    val selectedDisplayBody = selectedReportAgent?.let {
        translationByTarget["AGENT:${it.agentId}"] ?: it.responseBody
    }
    val selectedAgentLabel = selectedReportAgent?.let { agent ->
        val agentProv = AppService.findById(agent.provider)?.id ?: agent.provider
        com.ai.ui.shared.modelLabel(agentProv, agent.model, separator = " — ")
    }
    val canContinueInChat = selectedReportAgent != null
        && !selectedReportAgent.responseBody.isNullOrBlank()
        && selectedReportAgent.errorMessage.isNullOrBlank()
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Static page title; the agent picker dropdown below shows
        // the active model. ℹ️ on the title bar opens Model Info for
        // the selected agent's model.
        TitleBar(
            helpTopic = "content_model_response",
            title = "Model response",
            reportIcon = report.icon?.takeIf { it.isNotBlank() } ?: "📝",
            onBackClick = onDismiss,
            onTrace = headerTraceFilename?.let { fn -> { onNavigateToTraceFile(fn) } },
            onInfo = if (selectedReportAgent != null && selectedProviderService != null) {
                { navToModelInfo(selectedProviderService, selectedReportAgent.model) }
            } else null,
            onChat = if (canContinueInChat) { { showContinuePicker = true } } else null,
            onCopy = selectedDisplayBody?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "model response") }
            },
            onShare = selectedDisplayBody?.takeIf { it.isNotBlank() }?.let { body ->
                val shareSubject = selectedAgentLabel?.let { "Model response — $it" } ?: "Model response"
                { com.ai.ui.shared.shareText(context, body, shareSubject) }
            },
            onDelete = if (selectedReportAgent != null) { { confirmRemove = true } } else null,
            onReload = if (selectedReportAgent != null) { { confirmReload = true } } else null,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )

        LanguagePickerRow(langTabs, selectedLangKey, onSelect = { selectedLangKey = it })

        // Agent picker — user-toggled between a FlowRow of buttons
        // (one per model, click to view) and a single dropdown whose
        // label IS the active model. The dropdown collapses to one
        // row on dense reports; buttons keep every model one tap
        // away.
        if (agentsWithResults.isNotEmpty()) {
            var pickerStyle by rememberSaveable { mutableStateOf("dropdown") }
            PickerStyleSwitch(
                selected = pickerStyle,
                onSelect = { pickerStyle = it },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
            )
            if (pickerStyle == "buttons") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    agentsWithResults.forEach { agent ->
                        val agentProv = AppService.findById(agent.provider)?.id ?: agent.provider
                        val label = com.ai.ui.shared.modelLabel(agentProv, agent.model, separator = " / ")
                        val isSelected = agent.agentId == selectedAgentId
                        Button(
                            onClick = { selectedAgentId = agent.agentId },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Purple else Color(0xFF3A3A4A)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 36.dp)
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, softWrap = false
                            )
                        }
                    }
                }
            } else {
                var pickerOpen by remember { mutableStateOf(false) }
                val selectedLabel = selectedReportAgent?.let { agent ->
                    val agentProv = AppService.findById(agent.provider)?.id ?: agent.provider
                    com.ai.ui.shared.modelLabel(agentProv, agent.model, separator = " / ")
                } ?: "Pick a model"
                Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    OutlinedButton(
                        onClick = { pickerOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text(
                            selectedLabel,
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(" ▾", fontSize = 14.sp, color = AppColors.TextTertiary)
                    }
                    DropdownMenu(
                        expanded = pickerOpen, onDismissRequest = { pickerOpen = false },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        agentsWithResults.forEach { agent ->
                            val agentProv = AppService.findById(agent.provider)?.id ?: agent.provider
                            val label = com.ai.ui.shared.modelLabel(agentProv, agent.model, separator = " / ")
                            val isSelected = agent.agentId == selectedAgentId
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AppColors.Purple else Color.White,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = { selectedAgentId = agent.agentId; pickerOpen = false }
                            )
                        }
                    }
                }
            }
        }

        // Content
        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            if (agentsWithResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No successful reports to display", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            } else if (selectedReportAgent?.responseBody != null) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                    val translated = translationByTarget["AGENT:${selectedReportAgent.agentId}"]
                    val rawBody = translated ?: (selectedReportAgent.responseBody ?: "")
                    val conclusion = extractTagContent(rawBody, "conclusion")
                    val motivation = extractTagContent(rawBody, "motivation")
                    val strippedBody = if (conclusion != null || motivation != null)
                        rawBody.replace(conclusionTagRegex, "").replace(motivationTagRegex, "").trim()
                    else rawBody

                    if (conclusion != null) {
                        Text("Conclusion", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        ContentWithThinkSections(analysis = conclusion)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (motivation != null) {
                        Text("Motivation", fontSize = 18.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        ContentWithThinkSections(analysis = motivation)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    if (strippedBody.isNotBlank()) {
                        if (conclusion != null || motivation != null) {
                            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        ContentWithThinkSections(analysis = strippedBody)
                    }

                    selectedReportAgent.citations?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); CitationsSection(it) }
                    selectedReportAgent.searchResults?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); SearchResultsSection(it) }
                    selectedReportAgent.relatedQuestions?.takeIf { it.isNotEmpty() }?.let { Spacer(modifier = Modifier.height(16.dp)); RelatedQuestionsSection(it) }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No analysis available", color = AppColors.TextSecondary, fontSize = 16.sp)
                }
            }
        }
        if (agentsWithResults.isNotEmpty()) {
            Button(
                onClick = { showOnePage = true },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) { Text("View in one page", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }
    }

    // Reload / Remove confirmation dialogs for the selected agent.
    // Both dismiss the viewer on confirm: the on-disk report is
    // mutated by the parent's callback, but this screen's read-once
    // produceState wouldn't refresh, so dropping back to the report
    // screen avoids showing stale data.
    if (confirmReload && selectedReportAgent != null && selectedProviderService != null) {
        com.ai.ui.shared.ReloadConfirmationDialog(
            target = "${selectedProviderService.id} / ${selectedReportAgent.model}",
            onConfirm = {
                confirmReload = false
                onRegenerateAgent(report.id, selectedReportAgent.agentId)
                onDismiss()
            },
            onDismiss = { confirmReload = false }
        )
    }

    if (confirmRemove && selectedReportAgent != null && selectedProviderService != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from report?") },
            text = {
                Text(
                    "Drop ${selectedProviderService.id} / ${selectedReportAgent.model} from this report. " +
                        "Removes the saved response and recomputes totals; can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemoveAgent(report.id, selectedReportAgent.agentId)
                    onDismiss()
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

/** Full-screen overlay launched from the "View in one page" button
 *  on the Model response view. Stitches the prompt + every
 *  successful agent's response into one scrollable page so the user
 *  can read the whole report without flipping the dropdown. Honors
 *  the language picker — when a translation is selected the prompt
 *  and per-agent bodies render their translated copies.
 *
 *  LazyColumn (not verticalScroll) — each agent body can be tens of
 *  KB on long reports, and verticalScroll doesn't virtualise. */
@Composable
private fun OnePageReportView(
    report: Report,
    agentsWithResults: List<ReportAgent>,
    translationByTarget: Map<String, String>,
    langTabs: List<LangTab>,
    selectedLangKey: String,
    onSelectLang: (String) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val foldSubject = com.ai.ui.shared.LocalSubjectToTitleBarMode.current != com.ai.viewmodel.SubjectToTitleBarMode.HARDCODED
    val titleText = report.title.ifBlank { "Report" }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(
            helpTopic = "content_one_page",
            title = "View in one page",
            reportIcon = report.icon?.takeIf { it.isNotBlank() } ?: "📝",
            subject = titleText,
            onBackClick = onBack,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )
        if (!foldSubject) {
            Text(
                text = titleText,
                fontSize = 18.sp, color = AppColors.Green,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 4.dp)
            )
        }
        LanguagePickerRow(langTabs, selectedLangKey, onSelect = onSelectLang)
        val displayPrompt = translationByTarget["PROMPT:prompt"] ?: report.prompt
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            item(key = "prompt") {
                Text("Prompt", fontSize = 14.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                if (displayPrompt.isBlank()) {
                    Text("(no prompt recorded)", color = AppColors.TextTertiary, fontSize = 13.sp)
                } else {
                    Text(displayPrompt, fontSize = 13.sp, color = Color.White, lineHeight = 18.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = AppColors.DividerDark)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(agentsWithResults, key = { it.agentId }) { agent ->
                val provName = AppService.findById(agent.provider)?.id ?: agent.provider
                val label = com.ai.ui.shared.modelLabel(provName, agent.model, separator = " / ")
                val translated = translationByTarget["AGENT:${agent.agentId}"]
                val rawBody = translated ?: agent.responseBody.orEmpty()
                val conclusion = extractTagContent(rawBody, "conclusion")
                val motivation = extractTagContent(rawBody, "motivation")
                val strippedBody = if (conclusion != null || motivation != null)
                    rawBody.replace(conclusionTagRegex, "").replace(motivationTagRegex, "").trim()
                else rawBody

                Text(label, fontSize = 14.sp, color = AppColors.Green, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                if (conclusion != null) {
                    Text("Conclusion", fontSize = 13.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    ContentWithThinkSections(analysis = conclusion)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (motivation != null) {
                    Text("Motivation", fontSize = 13.sp, color = AppColors.Green, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    ContentWithThinkSections(analysis = motivation)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (strippedBody.isNotBlank()) {
                    ContentWithThinkSections(analysis = strippedBody)
                } else if (conclusion == null && motivation == null) {
                    Text("(no response)", color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = AppColors.DividerDark)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

// ===== Cost Table =====

@Composable
fun ReportCostTable(report: Report) {
    val context = LocalContext.current
    val agentsWithCosts = remember(report) {
        report.agents.filter { it.tokenUsage != null && (it.reportStatus == ReportStatus.SUCCESS || it.reportStatus == ReportStatus.ERROR) }
    }
    val secondaryState = produceState(initialValue = emptyList<SecondaryResult>(), report.id) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.listForReport(context, report.id) }
    }
    val secondary = secondaryState.value
    val hasIconCost = report.iconInputCost > 0.0 || report.iconOutputCost > 0.0
    val hasIconCalls = report.iconCalls.isNotEmpty()
    if (agentsWithCosts.isEmpty() && secondary.isEmpty() && !hasIconCost && !hasIconCalls) return

    data class CostRow(val type: String, val providerDisplay: String, val model: String, val tier: String, val durationMs: Long?, val inputTokens: Int, val outputTokens: Int, val inputCents: Double, val outputCents: Double)

    val agentRows = agentsWithCosts.map { agent ->
        val providerEnum = AppService.findById(agent.provider)
        val tu = agent.tokenUsage!!
        val pricing = providerEnum?.let { PricingCache.getPricing(context, it, agent.model) }
        val inCents = (pricing?.let { tu.inputTokens * it.promptPrice } ?: 0.0) * 100
        val outCents = (pricing?.let { tu.outputTokens * it.completionPrice } ?: 0.0) * 100
        CostRow("report", providerEnum?.id ?: agent.provider, agent.model, pricing?.source ?: "", agent.durationMs, tu.inputTokens, tu.outputTokens, inCents, outCents)
    }
    // Icon-gen call surfaces as its own row so the cost table totals
    // match the report total. Hidden when icon-gen wasn't run or the
    // call didn't return token usage. The pinned agent on
    // internal/icon supplies the (provider, model) pair.
    val iconRow: CostRow? = if (report.iconInputCost > 0.0 || report.iconOutputCost > 0.0) {
        val ai = com.ai.model.SettingsHolder.current
        val iconPrompt = ai?.internalPrompts?.firstOrNull {
            it.category == "icons" && it.name == "icon"
        }
        val iconAgent = iconPrompt?.let { p ->
            ai.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) }
        }
        val provider = iconAgent?.provider
        val model = iconAgent?.let { ai.getEffectiveModelForAgent(it) } ?: ""
        val pricing = provider?.let { PricingCache.getPricing(context, it, model) }
        CostRow(
            type = "icon",
            providerDisplay = provider?.id ?: "",
            model = model,
            tier = pricing?.source ?: "",
            durationMs = null,
            inputTokens = report.iconInputTokens,
            outputTokens = report.iconOutputTokens,
            inputCents = report.iconInputCost * 100,
            outputCents = report.iconOutputCost * 100
        )
    } else null
    // Per-tier rows from the 3-tier per-agent icons chain — one row
    // per recorded attempt (including failed earlier tiers) so the
    // All-calls list audits every API hit and the By-type "icon"
    // bucket sums the main report icon AND every agent icon. The
    // recorded provider on each IconCallRecord is the AppService id
    // of the model that actually billed the call (tier 3 = DeepSeek
    // even when the agent itself uses a different model).
    val iconCallRows = report.iconCalls.map { c ->
        val providerEnum = AppService.findById(c.provider)
        CostRow(
            type = "icon",
            providerDisplay = providerEnum?.id ?: c.provider,
            model = c.model,
            tier = c.pricingTier,
            durationMs = c.durationMs,
            inputTokens = c.inputTokens,
            outputTokens = c.outputTokens,
            inputCents = c.inputCost * 100,
            outputCents = c.outputCost * 100
        )
    }
    // Rerank / summarize call costs end up alongside the report rows so the
    // user sees one consolidated breakdown \u2014 distinguished by the new Type
    // column.
    val secondaryRows = secondary.mapNotNull { s ->
        val tu = s.tokenUsage ?: return@mapNotNull null
        val providerEnum = AppService.findById(s.providerId)
        val providerDisplay = providerEnum?.id ?: s.providerId
        val pricing = providerEnum?.let { PricingCache.getPricing(context, it, s.model) }
        val inCents = (s.inputCost ?: 0.0) * 100
        val outCents = (s.outputCost ?: 0.0) * 100
        // Cost-table "Type" column: prefer the user-given Meta prompt
        // name so a "Compare" row reads "compare", a "Critique" row
        // reads "critique", etc. Rerank / moderation / translate keep
        // their fixed labels — those routing labels are the user's
        // mental model for those rows.
        // Fan out drill-in rows always read "fan-out" (per-pair
        // responses) or "fan-in" (combine-reports follow-ups),
        // matching the prompt-category labels surfaced on the Report
        // Result page. Other secondaries use the user-given Meta
        // prompt name; rerank / moderation / translate keep their
        // routing labels.
        val type = when {
            s.fanOutSourceAgentId != null -> "fan-out"
            s.fanInOf != null -> "fan-in"
            !s.metaPromptName.isNullOrBlank() -> s.metaPromptName.lowercase()
            else -> when (s.kind) {
                SecondaryKind.RERANK -> "rerank"
                SecondaryKind.META -> "meta"
                SecondaryKind.MODERATION -> "moderation"
                SecondaryKind.TRANSLATE -> "translate"
            }
        }
        CostRow(type, providerDisplay, s.model, pricing?.source ?: "", s.durationMs, tu.inputTokens, tu.outputTokens, inCents, outCents)
    }
    // Fan-out icon-chain cost — each fan-out pair (SecondaryResult)
    // carries the aggregated 3-tier icon-chain spend in
    // iconInput/OutputCost/Tokens. There's no per-call breakdown
    // (unlike the per-agent report icons in report.iconCalls), so it
    // surfaces as one consolidated "fan-icons" row. Provider / model
    // are left blank — the chain spans tiers 1/2/3 across many models
    // and the per-pair record doesn't attribute the spend.
    val fanIconsRow: CostRow? = run {
        val iconPairs = secondary.filter { it.iconInputCost > 0.0 || it.iconOutputCost > 0.0 }
        if (iconPairs.isEmpty()) null
        else CostRow(
            type = "fan-icons",
            providerDisplay = "",
            model = "",
            tier = "",
            durationMs = null,
            inputTokens = iconPairs.sumOf { it.iconInputTokens },
            outputTokens = iconPairs.sumOf { it.iconOutputTokens },
            inputCents = iconPairs.sumOf { it.iconInputCost } * 100,
            outputCents = iconPairs.sumOf { it.iconOutputCost } * 100
        )
    }
    val rows = (agentRows + secondaryRows + listOfNotNull(iconRow, fanIconsRow) + iconCallRows).sortedByDescending { it.inputCents + it.outputCents }

    // GroupTotal carries an optional (provider, model) split so the
    // "By model" table can render the two as separate columns (same
    // shape as the All-calls list below); "By type" keeps the single
    // key column.
    data class GroupTotal(
        val key: String,
        val provider: String?, val model: String?,
        val calls: Int,
        val inputTokens: Int, val outputTokens: Int,
        val inputCents: Double, val outputCents: Double
    )
    fun groupByType(): List<GroupTotal> =
        rows.groupBy { it.type }.map { (k, gs) ->
            var iT = 0; var oT = 0; var iC = 0.0; var oC = 0.0
            gs.forEach { iT += it.inputTokens; oT += it.outputTokens; iC += it.inputCents; oC += it.outputCents }
            GroupTotal(k, null, null, gs.size, iT, oT, iC, oC)
        }.sortedByDescending { it.inputCents + it.outputCents }
    fun groupByModel(): List<GroupTotal> =
        rows.groupBy { it.providerDisplay to it.model }.map { (k, gs) ->
            var iT = 0; var oT = 0; var iC = 0.0; var oC = 0.0
            gs.forEach { iT += it.inputTokens; oT += it.outputTokens; iC += it.inputCents; oC += it.outputCents }
            GroupTotal("${k.first} / ${k.second}", k.first, k.second, gs.size, iT, oT, iC, oC)
        }.sortedByDescending { it.inputCents + it.outputCents }
    val byType = groupByType()
    val byModel = groupByModel()

    var totalIn = 0; var totalOut = 0; var totalInC = 0.0; var totalOutC = 0.0
    rows.forEach { totalIn += it.inputTokens; totalOut += it.outputTokens; totalInC += it.inputCents; totalOutC += it.outputCents }
    // Costs the user dropped from the report via Delete actions —
    // surfaces as its own row above each Total, same pattern as
    // the result-page footer + the HTML export's cost view.
    val deletedCents = report.costsFromDeletedItems * 100

    fun fmtC(v: Double) = "%.2f".format(v)
    fun fmtS(ms: Long?) = if (ms != null) "%.1f".format(ms / 1000.0) else ""
    fun fmtT(n: Int) = "%,d".format(n)

    val hColor = AppColors.Blue; val vColor = AppColors.TextSecondary; val tColor = AppColors.Blue
    val hSize = 11.sp; val vSize = 11.sp

    // Sort-key enum shared by both summary tables. Each table keeps
    // its own (key, ascending) state so the user can sort the type
    // rollup independently from the model rollup.
    val SUMMARY_TOTAL = "TOTAL"; val SUMMARY_KEY = "KEY"
    val SUMMARY_CALLS = "CALLS"
    val SUMMARY_IN_TOK = "IN_TOK"; val SUMMARY_OUT_TOK = "OUT_TOK"
    val SUMMARY_IN_C = "IN_C"; val SUMMARY_OUT_C = "OUT_C"

    @Composable
    fun SummaryTable(
        label: String,
        keyHeader: String,
        groups: List<GroupTotal>,
        // When true: key column splits into Provider + Model (matches
        // the All-calls table). When false: single key column (Type).
        isByModel: Boolean = false
    ) {
        if (groups.isEmpty()) return
        // Default = Total ¢ DESC (matches the previous static order).
        // First click on a column → switches to that column DESC; the
        // second click on the same column flips to ASC. Clicking a
        // different column starts at DESC again.
        var sortKey by remember { mutableStateOf(SUMMARY_TOTAL) }
        var ascending by remember { mutableStateOf(false) }
        fun toggle(k: String) {
            if (sortKey == k) ascending = !ascending
            else { sortKey = k; ascending = false }
        }
        val sortedGroups = remember(groups, sortKey, ascending) {
            val cmp: Comparator<GroupTotal> = when (sortKey) {
                SUMMARY_KEY    -> compareBy { it.key.lowercase() }
                SUMMARY_CALLS  -> compareBy { it.calls }
                SUMMARY_IN_TOK -> compareBy { it.inputTokens }
                SUMMARY_OUT_TOK-> compareBy { it.outputTokens }
                SUMMARY_IN_C   -> compareBy { it.inputCents }
                SUMMARY_OUT_C  -> compareBy { it.outputCents }
                else           -> compareBy { it.inputCents + it.outputCents }
            }
            if (ascending) groups.sortedWith(cmp) else groups.sortedWith(cmp.reversed())
        }
        @Composable
        fun SortHeader(k: String, text: String, mod: Modifier, end: Boolean = false) {
            val active = sortKey == k
            val arrow = if (active) (if (ascending) " ▲" else " ▼") else ""
            Text(
                text = text + arrow,
                fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                modifier = mod.clickable { toggle(k) },
                textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start
            )
        }
        // Column widths. Type stays narrow (short labels); Provider /
        // Model in the "By model" layout get generous widths so the
        // names sit on one line — and the maxLines/ellipsis is gone
        // anyway so even longer names will wrap rather than be
        // trimmed.
        val typeColW = 100.dp
        val providerColW = 120.dp
        val modelColW = 220.dp
        val callsColW = 48.dp
        val totalsWidth = if (isByModel)
            56.dp + providerColW + modelColW + callsColW + 64.dp + 64.dp + 56.dp + 56.dp + 8.dp
        else
            56.dp + typeColW + callsColW + 64.dp + 64.dp + 56.dp + 56.dp + 8.dp

        Spacer(modifier = Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                Row {
                    SortHeader(SUMMARY_TOTAL, "Total ¢", Modifier.width(56.dp), end = true)
                    if (isByModel) {
                        SortHeader(SUMMARY_KEY, "Provider", Modifier.width(providerColW).padding(start = 8.dp))
                        SortHeader(SUMMARY_KEY, "Model", Modifier.width(modelColW).padding(start = 8.dp))
                    } else {
                        SortHeader(SUMMARY_KEY, keyHeader, Modifier.width(typeColW).padding(start = 8.dp))
                    }
                    SortHeader(SUMMARY_CALLS, "Calls", Modifier.width(callsColW).padding(start = 8.dp), end = true)
                    SortHeader(SUMMARY_IN_TOK, "In tok", Modifier.width(64.dp), end = true)
                    SortHeader(SUMMARY_OUT_TOK, "Out tok", Modifier.width(64.dp), end = true)
                    SortHeader(SUMMARY_IN_C, "In ¢", Modifier.width(56.dp), end = true)
                    SortHeader(SUMMARY_OUT_C, "Out ¢", Modifier.width(56.dp), end = true)
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp, modifier = Modifier.width(totalsWidth))
                sortedGroups.forEach { g ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(fmtC(g.inputCents + g.outputCents), fontSize = vSize, color = vColor,
                            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace)
                        if (isByModel) {
                            // No ellipsis — long provider / model names
                            // wrap to a second line rather than getting
                            // chopped off.
                            Text(g.provider ?: "", fontSize = vSize, color = vColor,
                                modifier = Modifier.width(providerColW).padding(start = 8.dp),
                                fontFamily = FontFamily.Monospace)
                            Text(g.model ?: "", fontSize = vSize, color = vColor,
                                modifier = Modifier.width(modelColW).padding(start = 8.dp),
                                fontFamily = FontFamily.Monospace)
                        } else {
                            Text(g.key, fontSize = vSize, color = vColor,
                                modifier = Modifier.width(typeColW).padding(start = 8.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace)
                        }
                        Text(fmtT(g.calls), fontSize = vSize, color = vColor,
                            modifier = Modifier.width(callsColW).padding(start = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace)
                        Text(fmtT(g.inputTokens), fontSize = vSize, color = vColor,
                            modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace)
                        Text(fmtT(g.outputTokens), fontSize = vSize, color = vColor,
                            modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace)
                        Text(fmtC(g.inputCents), fontSize = vSize, color = vColor,
                            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace)
                        Text(fmtC(g.outputCents), fontSize = vSize, color = vColor,
                            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                // Deleted-items row — sits above the Total when the
                // report has any. All numeric cells stay blank
                // except the Total ¢ column on the left, which
                // shows the deleted spend on its own. The Total
                // row's Total ¢ then includes it.
                if (deletedCents > 0.0) {
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(fmtC(deletedCents), fontSize = vSize, color = vColor,
                            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            fontFamily = FontFamily.Monospace)
                        if (isByModel) {
                            Text("deleted", fontSize = vSize, color = vColor,
                                modifier = Modifier.width(providerColW + modelColW).padding(start = 8.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace)
                        } else {
                            Text("deleted", fontSize = vSize, color = vColor,
                                modifier = Modifier.width(typeColW).padding(start = 8.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace)
                        }
                        Text("", modifier = Modifier.width(callsColW))
                        Text("", modifier = Modifier.width(64.dp))
                        Text("", modifier = Modifier.width(64.dp))
                        Text("", modifier = Modifier.width(56.dp))
                        Text("", modifier = Modifier.width(56.dp))
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp, modifier = Modifier.width(totalsWidth))
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    val gIn = groups.sumOf { it.inputTokens }
                    val gOut = groups.sumOf { it.outputTokens }
                    val gInC = groups.sumOf { it.inputCents }
                    val gOutC = groups.sumOf { it.outputCents }
                    val gCalls = groups.sumOf { it.calls }
                    Text(fmtC(gInC + gOutC + deletedCents), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        fontFamily = FontFamily.Monospace)
                    if (isByModel) {
                        Text("Total", fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(providerColW + modelColW).padding(start = 8.dp))
                    } else {
                        Text("Total", fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(typeColW).padding(start = 8.dp))
                    }
                    Text(fmtT(gCalls), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(callsColW).padding(start = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        fontFamily = FontFamily.Monospace)
                    Text(fmtT(gIn), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        fontFamily = FontFamily.Monospace)
                    Text(fmtT(gOut), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        fontFamily = FontFamily.Monospace)
                    Text(fmtC(gInC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        fontFamily = FontFamily.Monospace)
                    Text(fmtC(gOutC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Summary tables — one per type, one per (provider, model) —
        // followed by the full per-call detail. Rendering order
        // matches what the user asked for: glance at the type and
        // model rollups first, drill into the call list when needed.
        SummaryTable("By type", "Type", byType, isByModel = false)
        SummaryTable("By model", "Model", byModel, isByModel = true)
        Spacer(modifier = Modifier.height(8.dp))
        Text("All calls", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp))
        // Sortable headers for the per-call detail table \u2014 first
        // click on a column switches to it DESC, second click on
        // the same column flips to ASC. Default is Total \u00A2 DESC,
        // matching the previous static order.
        val ROW_TOTAL = "TOTAL"; val ROW_TYPE = "TYPE"; val ROW_PROVIDER = "PROVIDER"
        val ROW_MODEL = "MODEL"; val ROW_TIER = "TIER"; val ROW_SEC = "SEC"
        val ROW_IN_TOK = "IN_TOK"; val ROW_OUT_TOK = "OUT_TOK"
        val ROW_IN_C = "IN_C"; val ROW_OUT_C = "OUT_C"
        var rowSortKey by remember { mutableStateOf(ROW_TOTAL) }
        var rowAsc by remember { mutableStateOf(false) }
        fun rowToggle(k: String) {
            if (rowSortKey == k) rowAsc = !rowAsc
            else { rowSortKey = k; rowAsc = false }
        }
        val sortedRows = remember(rows, rowSortKey, rowAsc) {
            val cmp: Comparator<CostRow> = when (rowSortKey) {
                ROW_TYPE     -> compareBy { it.type }
                ROW_PROVIDER -> compareBy { it.providerDisplay.lowercase() }
                ROW_MODEL    -> compareBy { it.model.lowercase() }
                ROW_TIER     -> compareBy { it.tier }
                ROW_SEC      -> compareBy(nullsFirst()) { it.durationMs }
                ROW_IN_TOK   -> compareBy { it.inputTokens }
                ROW_OUT_TOK  -> compareBy { it.outputTokens }
                ROW_IN_C     -> compareBy { it.inputCents }
                ROW_OUT_C    -> compareBy { it.outputCents }
                else         -> compareBy { it.inputCents + it.outputCents }
            }
            if (rowAsc) rows.sortedWith(cmp) else rows.sortedWith(cmp.reversed())
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                @Composable
                fun RowSortHeader(k: String, text: String, mod: Modifier, end: Boolean = false) {
                    val active = rowSortKey == k
                    val arrow = if (active) (if (rowAsc) " \u25B2" else " \u25BC") else ""
                    Text(
                        text = text + arrow,
                        fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                        modifier = mod.clickable { rowToggle(k) },
                        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start
                    )
                }
                Row {
                    RowSortHeader(ROW_TOTAL, "Total \u00A2", Modifier.width(56.dp), end = true)
                    RowSortHeader(ROW_TYPE, "Type", Modifier.width(70.dp).padding(start = 8.dp))
                    RowSortHeader(ROW_PROVIDER, "Provider", Modifier.width(140.dp))
                    RowSortHeader(ROW_MODEL, "Model", Modifier.width(220.dp))
                    RowSortHeader(ROW_TIER, "Tier", Modifier.width(80.dp))
                    RowSortHeader(ROW_SEC, "Sec", Modifier.width(48.dp), end = true)
                    RowSortHeader(ROW_IN_TOK, "In tok", Modifier.width(64.dp), end = true)
                    RowSortHeader(ROW_OUT_TOK, "Out tok", Modifier.width(64.dp), end = true)
                    RowSortHeader(ROW_IN_C, "In \u00A2", Modifier.width(56.dp), end = true)
                    RowSortHeader(ROW_OUT_C, "Out \u00A2", Modifier.width(56.dp), end = true)
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp, modifier = Modifier.width(862.dp))
                sortedRows.forEach { r ->
                    val typeColor = when (r.type) { "rerank" -> AppColors.Orange; "summarize" -> AppColors.Indigo; "compare" -> AppColors.Purple; else -> vColor }
                    val tierColor = when (r.tier) {
                        "OVERRIDE" -> AppColors.Orange; "OPENROUTER" -> AppColors.Blue; "LITELLM" -> AppColors.Purple
                        else -> AppColors.TextDim
                    }
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(fmtC(r.inputCents + r.outputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(r.type, fontSize = vSize, color = typeColor, modifier = Modifier.width(70.dp).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // No ellipsis on Provider / Model — long names
                        // wrap rather than getting truncated.
                        Text(r.providerDisplay, fontSize = vSize, color = vColor, modifier = Modifier.width(140.dp))
                        Text(r.model, fontSize = vSize, color = vColor, modifier = Modifier.width(220.dp), fontFamily = FontFamily.Monospace)
                        Text(r.tier, fontSize = vSize, color = tierColor, modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtS(r.durationMs), fontSize = vSize, color = vColor, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtT(r.inputTokens), fontSize = vSize, color = vColor, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtT(r.outputTokens), fontSize = vSize, color = vColor, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtC(r.inputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text(fmtC(r.outputCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    }
                }
                // Deleted-items row — sits above the Total when the
                // report has any. Only the Total ¢ cell on the left
                // carries a value; everything else stays blank to
                // signal "no per-call data, just a lump sum".
                if (deletedCents > 0.0) {
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(fmtC(deletedCents), fontSize = vSize, color = vColor, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                        Text("deleted", fontSize = vSize, color = vColor, modifier = Modifier.width(510.dp).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                        Text("", modifier = Modifier.width(48.dp))
                        Text("", modifier = Modifier.width(64.dp))
                        Text("", modifier = Modifier.width(64.dp))
                        Text("", modifier = Modifier.width(56.dp))
                        Text("", modifier = Modifier.width(56.dp))
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp, modifier = Modifier.width(862.dp))
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(fmtC(totalInC + totalOutC + deletedCents), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text("Total", fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(510.dp).padding(start = 8.dp))
                    Text("", fontSize = vSize, modifier = Modifier.width(48.dp))
                    Text(fmtT(totalIn), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtT(totalOut), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtC(totalInC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                    Text(fmtC(totalOutC), fontSize = vSize, color = tColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ===== Content Parsing =====

private sealed class ContentSegment {
    data class Text(val content: String) : ContentSegment()
    data class Think(val content: String) : ContentSegment()
    data class Table(val table: MarkdownTable) : ContentSegment()
}

private fun parseContentWithThinkSections(text: String): List<ContentSegment> {
    val segments = mutableListOf<ContentSegment>()
    val thinkPattern = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    var lastEnd = 0
    thinkPattern.findAll(text).forEach { match ->
        if (match.range.first > lastEnd) {
            val before = text.substring(lastEnd, match.range.first).trim()
            if (before.isNotEmpty()) appendTextWithTables(segments, before)
        }
        val thinkContent = match.groupValues[1].trim()
        if (thinkContent.isNotEmpty()) segments.add(ContentSegment.Think(thinkContent))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        val remaining = text.substring(lastEnd).trim()
        if (remaining.isNotEmpty()) appendTextWithTables(segments, remaining)
    }
    if (segments.isEmpty() && text.isNotBlank()) appendTextWithTables(segments, text)
    return segments
}

/** Split a Text chunk further on GFM table blocks. Each table becomes
 *  its own segment so it can render via a Compose Row/Column grid
 *  rather than getting flattened to AnnotatedString (which has no
 *  table primitive). */
private fun appendTextWithTables(segments: MutableList<ContentSegment>, text: String) {
    val (placeheld, tables) = parseGfmTables(text)
    if (tables.isEmpty()) {
        segments.add(ContentSegment.Text(text))
        return
    }
    var pos = 0
    MD_TABLE_PLACEHOLDER_REGEX.findAll(placeheld).forEach { match ->
        // Bounds-check the placeholder index — the regex is a plain
        // alphanumeric token (`MDTBL\d+`), so model output that happens
        // to contain a literal `MDTBL999` alongside one real table
        // matches here too. Treat any out-of-range hit as user text
        // rather than indexing past tables.size and crashing.
        val idx = match.groupValues[1].toIntOrNull()
        if (idx == null || idx !in tables.indices) return@forEach
        if (match.range.first > pos) {
            val chunk = placeheld.substring(pos, match.range.first).trim()
            if (chunk.isNotEmpty()) segments.add(ContentSegment.Text(chunk))
        }
        segments.add(ContentSegment.Table(tables[idx]))
        pos = match.range.last + 1
    }
    if (pos < placeheld.length) {
        val tail = placeheld.substring(pos).trim()
        if (tail.isNotEmpty()) segments.add(ContentSegment.Text(tail))
    }
}

@Composable
internal fun ContentWithThinkSections(analysis: String) {
    val segments = remember(analysis) { parseContentWithThinkSections(analysis) }
    segments.forEach { segment ->
        when (segment) {
            is ContentSegment.Text -> {
                val html = remember(segment.content) { convertMarkdownToSimpleHtml(segment.content) }
                HtmlContentDisplay(htmlContent = html)
            }
            is ContentSegment.Think -> ThinkSection(content = segment.content)
            is ContentSegment.Table -> MarkdownTableSection(segment.table)
        }
    }
}

@Composable
private fun MarkdownTableSection(table: MarkdownTable) {
    val divider = Color(0xFF3A3A3A)
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .background(Color(0xFF222222), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .border(1.dp, divider, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
    ) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A3A), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .padding(8.dp)
        ) {
            for ((i, h) in table.headers.withIndex()) {
                Text(
                    text = stripInlineMarkdown(h),
                    color = Color(0xFF9FCFFF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    textAlign = textAlignFor(table.alignments.getOrNull(i))
                )
            }
        }
        for ((rowIdx, row) in table.rows.withIndex()) {
            HorizontalDivider(color = divider, thickness = 1.dp)
            Row(modifier = Modifier
                .fillMaxWidth()
                .background(if (rowIdx % 2 == 1) Color(0xFF262626) else Color(0xFF222222))
                .padding(8.dp)
            ) {
                for (i in 0 until table.headers.size) {
                    Text(
                        text = stripInlineMarkdown(row.getOrNull(i) ?: ""),
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        textAlign = textAlignFor(table.alignments.getOrNull(i))
                    )
                }
            }
        }
    }
}

private fun textAlignFor(a: TableAlign?) = when (a) {
    TableAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
    TableAlign.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
    else -> androidx.compose.ui.text.style.TextAlign.Start
}

@Composable
private fun ThinkSection(content: String) {
    var isExpanded by remember { mutableStateOf(false) }
    Column {
        Button(
            onClick = { isExpanded = !isExpanded },
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceDark),
            border = BorderStroke(1.dp, AppColors.TextDim),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) { Text(if (isExpanded) "Hide Think" else "Think", color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 1, softWrap = false) }

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525))) {
                Box(modifier = Modifier.width(3.dp).fillMaxHeight().background(AppColors.TextDisabled))
                Text(content, color = Color(0xFF999999), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// ===== HTML Conversion =====

internal fun convertMarkdownToSimpleHtml(markdown: String): String {
    var html = markdown.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n")
    html = html.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        .replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\* (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("^\\d+\\. (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        .replace("\n\n", "</p><p>").replace("\n", "<br>")
    html = html.replace(Regex("(<li>.*?</li>)+")) { "<ul>${it.value}</ul>" }
    if (html.isNotBlank()) html = "<p>$html</p>"
    return html
}

@Composable
private fun HtmlContentDisplay(htmlContent: String) {
    val annotatedString = remember(htmlContent) { parseHtmlToAnnotatedString(htmlContent) }
    Text(text = annotatedString, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.fillMaxWidth())
}

private fun parseHtmlToAnnotatedString(html: String): androidx.compose.ui.text.AnnotatedString {
    val cleanHtml = html.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("<p>", "").replace("</p>", "\n\n").replace("<br>", "\n")
        .replace("<ul>", "\n").replace("</ul>", "\n")
        .replace("<li>", "  \u2022 ").replace("</li>", "\n")
        .replace(Regex("\n{3,}"), "\n\n").trim()

    return buildAnnotatedString {
        val tagPattern = Regex("<(/?)(h[123]|strong|em)>")
        var lastEnd = 0
        val matches = tagPattern.findAll(cleanHtml).toList()
        val styleStack = mutableListOf<Pair<String, Int>>()

        for (match in matches) {
            if (match.range.first > lastEnd) append(cleanHtml.substring(lastEnd, match.range.first))
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2]
            if (!isClosing) {
                styleStack.add(tagName to length)
            } else {
                val idx = styleStack.indexOfLast { it.first == tagName }
                if (idx >= 0) {
                    val (_, startPos) = styleStack.removeAt(idx)
                    val style = when (tagName) {
                        "h1" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White)
                        "h2" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF8BB8FF))
                        "h3" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF9FCFFF))
                        "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "em" -> SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFCCCCCC))
                        else -> null
                    }
                    style?.let { addStyle(it, startPos, length) }
                }
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < cleanHtml.length) append(cleanHtml.substring(lastEnd))
    }
}

// ===== Supplementary Sections =====

@Composable
private fun CitationsSection(citations: List<String>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Sources", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Purple, modifier = Modifier.padding(bottom = 12.dp))
        citations.forEachIndexed { i, url ->
            Row(modifier = Modifier.padding(vertical = 4.dp).clickable {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())) } catch (_: Exception) {}
            }) {
                Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                Text(url, color = Color(0xFF64B5F6), fontSize = 14.sp, modifier = Modifier.weight(1f), textDecoration = TextDecoration.Underline)
            }
        }
    }
}

@Composable
private fun SearchResultsSection(searchResults: List<SearchResult>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Search Results", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Orange, modifier = Modifier.padding(bottom = 12.dp))
        searchResults.forEachIndexed { i, result ->
            if (result.url != null) {
                Column(modifier = Modifier.padding(vertical = 6.dp).clickable {
                    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, result.url.toUri())) } catch (_: Exception) {}
                }) {
                    Row {
                        Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                        Text(result.name ?: result.url, color = Color(0xFF64B5F6), fontSize = 14.sp, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
                    }
                    if (result.name != null && result.name != result.url) Text(result.url, color = AppColors.TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
                    if (!result.snippet.isNullOrBlank()) Text(result.snippet, color = Color(0xFFBBBBBB), fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun RelatedQuestionsSection(relatedQuestions: List<String>) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252525), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(16.dp)) {
        Text("Related Questions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColors.Green, modifier = Modifier.padding(bottom = 12.dp))
        relatedQuestions.forEachIndexed { i, q ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${i + 1}. ", color = AppColors.TextSecondary, fontSize = 14.sp)
                Text(q, color = Color(0xFFE0E0E0), fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ===== Tag Extraction =====

internal fun extractTagContent(text: String, tagName: String): String? {
    val pattern = Regex("<$tagName>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL)
    return pattern.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
