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
import androidx.compose.ui.draw.alpha
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
    onRegenerateAgent: (String, String) -> Unit = { _, _ -> },
    /** When non-null, the per-screen language picker is suppressed and
     *  every content lookup is locked to this language. Set by the View
     *  page when it hoists the picker to its own header — the sub-screen
     *  is then a passive renderer of the picked language. Convention:
     *  null = picker mode (Report - Manage path, unchanged behavior),
     *  "" = locked to Original, non-empty = locked to that displayName
     *  (matching [SecondaryResult.targetLanguage]). */
    forcedLanguage: String? = null
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
                onRemoveAgent, onRegenerateAgent, forcedLanguage)
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
    /** When true, each non-Original tab renders the cached
     *  `translation_icon` emoji for its language instead of the
     *  English name (falls back to the name when no cached emoji
     *  exists). The Original tab uses [originalIcon] when supplied
     *  (typically Report.languageIcon, the detected source-language
     *  emoji); otherwise falls back to the plain "Original" text. */
    useIcons: Boolean = false,
    originalIcon: String? = null,
    // Icon-mode children carry their own 6dp left padding (tap-area
    // headroom), so the FlowRow's own start padding is dropped to
    // 10dp — the first glyph then lands at 10 + 6 = 16dp, lined up
    // with the body text below. Button mode keeps the full 16dp so
    // buttons themselves start at 16dp.
    modifier: Modifier = Modifier.fillMaxWidth().padding(
        start = if (useIcons) 10.dp else 16.dp,
        end = 16.dp,
        top = 8.dp, bottom = 8.dp
    )
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
            val emoji = if (useIcons) {
                if (lang.key == LangTab.ORIGINAL_KEY) originalIcon?.takeIf { it.isNotBlank() }
                else com.ai.data.InternalPromptIconCache.get("translation_icon", lang.displayName)
            } else null
            if (useIcons) {
                // Icon mode: no Button chrome — just the emoji (or
                // the fallback name) with brightness signalling
                // active vs inactive. Full alpha on the selected tab,
                // dimmed on the others.
                val content = emoji ?: lang.displayName
                val isEmoji = emoji != null
                Text(
                    content,
                    fontSize = if (isEmoji) 28.sp else 12.sp,
                    color = if (isEmoji) Color.Unspecified else Color.White,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier
                        .alpha(if (isSelected) 1f else 0.4f)
                        .clickable { onSelect(lang.key) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            } else {
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
 *  list. "Original" is included only when [includeOriginal] is true —
 *  set it to false on screens where the displayed content has no
 *  original-language version (e.g. a French-seed META detail with no
 *  Original-language sibling) so the picker doesn't surface a tab
 *  that resolves to "(no content)". */
internal fun buildLangTabs(
    translates: List<SecondaryResult>,
    includeOriginal: Boolean = true,
    /** Display name of the report's detected source language (e.g.
     *  "English"). When non-null, TRANSLATE rows whose
     *  `targetLanguage` matches this string fold into the Original
     *  tab instead of getting their own duplicate tab — they're
     *  back-translations TO the report's own source language. */
    originalAlias: String? = null
): List<LangTab> {
    val out = mutableListOf<LangTab>()
    if (includeOriginal) out += LangTab(LangTab.ORIGINAL_KEY, "Original", null)
    val seen = LinkedHashMap<String, String?>()
    translates.forEach { t ->
        val lang = t.targetLanguage?.takeIf { it.isNotBlank() } ?: return@forEach
        if (originalAlias != null && lang == originalAlias) return@forEach
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
    onRegenerateAgent: (String, String) -> Unit,
    forcedLanguage: String?
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
    // pickerLangKey holds the local picker state used in non-locked
    // mode (Report - Manage path). When forcedLanguage is non-null
    // the View page is locking us to a specific language and the
    // local pickers are suppressed; we derive selectedLangKey from
    // the forced value instead. Empty string = Original; non-empty
    // displayName → matching LangTab.key via the same derivation
    // buildLangTabs uses.
    var pickerLangKey by rememberSaveable(report.id) { mutableStateOf(LangTab.ORIGINAL_KEY) }
    LaunchedEffect(langTabs, forcedLanguage) {
        if (forcedLanguage == null && langTabs.none { it.key == pickerLangKey }) {
            pickerLangKey = LangTab.ORIGINAL_KEY
        }
    }
    val selectedLangKey: String = if (forcedLanguage != null) {
        if (forcedLanguage.isEmpty()) LangTab.ORIGINAL_KEY
        else forcedLanguage.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
    } else pickerLangKey
    // Quick lookups for the active language: by-targetId → the
    // TRANSLATE SecondaryResult row that holds the translated content
    // plus the per-call metadata (trace file, runId, …). Recomputed
    // only when the selected language or the translation list changes.
    val translationRowByTarget = remember(translates, selectedLangKey) {
        if (selectedLangKey == LangTab.ORIGINAL_KEY) emptyMap()
        else {
            val tab = langTabs.firstOrNull { it.key == selectedLangKey }
            val langName = tab?.displayName ?: return@remember emptyMap()
            translates
                .filter { it.targetLanguage == langName }
                .associateBy {
                    (it.translateSourceKind ?: "") + ":" + (it.translateSourceTargetId ?: "")
                }
        }
    }
    val translationByTarget = remember(translationRowByTarget) {
        translationRowByTarget.mapValues { (_, row) -> row.content ?: "" }
    }

    // Single-section variants: just the prompt, or just the cost table — no agent picker,
    // no per-agent body. These come from the View row's Prompt / Costs buttons.
    if (initialSection == "prompt" || initialSection == "costs") {
        // Layered overlay: tapping "All API calls" inside the Cost
        // view opens the per-call drill-in screen. Leave the Cost
        // view's flag (initialSection) intact and gate render on
        // !showAllApi so Android back returns to Costs, not the
        // View tile screen. See feedback_overlay_back_stack.md.
        var showAllApi by rememberSaveable { mutableStateOf(false) }
        if (initialSection == "costs" && showAllApi) {
            ReportApiCallsScreen(report = report, onBack = { showAllApi = false }, onNavigateToTraceFile = onNavigateToTraceFile)
            return
        }
        // Translation compare overlay for the Prompt section. Fires
        // when the active picker language renders a PROMPT TRANSLATE
        // overlay on top of report.prompt. Source = the original
        // report.prompt, translation = the overlay row's content.
        var showPromptCompare by remember { mutableStateOf(false) }
        val promptTranslateRow = translationRowByTarget["PROMPT:prompt"]
        if (showPromptCompare && initialSection == "prompt" && promptTranslateRow != null && !report.prompt.isNullOrBlank() && !promptTranslateRow.content.isNullOrBlank()) {
            val translatedLabel = promptTranslateRow.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translation"
            val tf = promptTranslateRow.traceFile
            val translatedIcon = promptTranslateRow.targetLanguage?.takeIf { it.isNotBlank() }
                ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
            TranslationCompareScreen(
                title = "Translation — Prompt",
                originalLabel = "Original",
                originalContent = report.prompt,
                translatedLabel = translatedLabel,
                translatedContent = promptTranslateRow.content,
                onBack = { showPromptCompare = false },
                onNavigateHome = onNavigateHome,
                onTrace = tf?.let { fn -> { onNavigateToTraceFile(fn) } },
                originalIcon = report.languageIcon,
                translatedIcon = translatedIcon
            )
            return
        }
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val title = if (initialSection == "prompt") "Prompt" else "Report - costs"
            val sectionHelpTopic = if (initialSection == "prompt") "prompt_view" else "cost_view"
            // Resolve the prompt text up front so the title-bar 📋
            // icon and the body Text below render the same string.
            // Costs view has no copy target — the helper renders a
            // table, not a copyable string.
            val displayPrompt = translationByTarget["PROMPT:prompt"] ?: report.prompt
            // 🐞 → trace of the prompt's translation call, but only
            // when a non-Original language is selected AND that
            // translation row carries a captured trace filename
            // (legacy translation rows written before traceFile was
            // wired don't). Hidden on the Cost view and on Original.
            val promptTraceFile = if (initialSection == "prompt") {
                translationRowByTarget["PROMPT:prompt"]?.traceFile?.takeIf { it.isNotBlank() }
            } else null
            TitleBar(helpTopic = sectionHelpTopic,
                title = title,
                reportIcon = report.icon?.takeIf { it.isNotBlank() } ?: "📝",
                onBackClick = onDismiss,
                onTrace = promptTraceFile?.let { tf -> { onNavigateToTraceFile(tf) } },
                onTranslationCompare = if (initialSection == "prompt" && promptTranslateRow != null && !report.prompt.isNullOrBlank() && !promptTranslateRow.content.isNullOrBlank()) {
                    { showPromptCompare = true }
                } else null,
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
            if (initialSection == "prompt" && forcedLanguage == null) {
                // Only show language tabs that actually have a prompt
                // translation. Original is always available — the
                // source prompt is what the report was started from.
                val promptLangTabs = remember(translates) {
                    buildLangTabs(translates.filter { it.translateSourceKind == "PROMPT" })
                }
                LaunchedEffect(promptLangTabs) {
                    if (promptLangTabs.none { it.key == pickerLangKey }) pickerLangKey = LangTab.ORIGINAL_KEY
                }
                LanguagePickerRow(
                    promptLangTabs, selectedLangKey,
                    onSelect = { pickerLangKey = it },
                    useIcons = true,
                    originalIcon = report.languageIcon
                )
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
                        ReportCostTable(report = report, onShowAllApi = { showAllApi = true })
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
            onSelectLang = { pickerLangKey = it },
            forcedLanguage = forcedLanguage,
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

    // Translation compare overlay for the selected agent. Fires when
    // the active picker language renders an AGENT TRANSLATE overlay
    // on top of selectedReportAgent.responseBody.
    var showAgentCompare by remember { mutableStateOf(false) }
    val agentTranslateRow = selectedReportAgent?.let { translationRowByTarget["AGENT:${it.agentId}"] }
    if (showAgentCompare && selectedReportAgent != null && agentTranslateRow != null && !selectedReportAgent.responseBody.isNullOrBlank() && !agentTranslateRow.content.isNullOrBlank()) {
        val translatedLabel = agentTranslateRow.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translation"
        val titleLabel = selectedAgentLabel?.let { "Translation — $it" } ?: "Translation"
        val tf = agentTranslateRow.traceFile
        val translatedIcon = agentTranslateRow.targetLanguage?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
        TranslationCompareScreen(
            title = titleLabel,
            originalLabel = "Original",
            originalContent = selectedReportAgent.responseBody!!,
            translatedLabel = translatedLabel,
            translatedContent = agentTranslateRow.content!!,
            onBack = { showAgentCompare = false },
            onNavigateHome = onNavigateHome,
            onTrace = tf?.let { fn -> { onNavigateToTraceFile(fn) } },
            originalIcon = report.languageIcon,
            translatedIcon = translatedIcon
        )
        return
    }
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
            onTranslationCompare = if (selectedReportAgent != null && agentTranslateRow != null && !selectedReportAgent.responseBody.isNullOrBlank() && !agentTranslateRow.content.isNullOrBlank()) {
                { showAgentCompare = true }
            } else null,
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

        if (forcedLanguage == null) {
            // Only show language tabs that actually have at least one
            // agent-response translation. Original is always present —
            // each successful agent's responseBody is the original-
            // language body.
            val agentLangTabs = remember(translates) {
                buildLangTabs(translates.filter { it.translateSourceKind == "AGENT" })
            }
            LaunchedEffect(agentLangTabs) {
                if (agentLangTabs.none { it.key == pickerLangKey }) pickerLangKey = LangTab.ORIGINAL_KEY
            }
            LanguagePickerRow(
                agentLangTabs, selectedLangKey,
                onSelect = { pickerLangKey = it },
                useIcons = true,
                originalIcon = report.languageIcon
            )
        }

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
            target = "${selectedProviderService.id} / ${com.ai.ui.shared.shortModelName(selectedReportAgent.model)}",
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
                    "Drop ${selectedProviderService.id} / ${com.ai.ui.shared.shortModelName(selectedReportAgent.model)} from this report. " +
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
    forcedLanguage: String?,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
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
        com.ai.ui.shared.HardcodedSubjectRow(titleText, horizontalPadding = 16.dp, maxLines = 2)
        if (forcedLanguage == null) {
            LanguagePickerRow(langTabs, selectedLangKey, onSelect = onSelectLang)
        }
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

internal data class ReportCostData(
    val rows: List<CostRow>,
    val byType: List<GroupTotal>,
    val byModel: List<GroupTotal>,
    val totalInC: Double,
    val totalOutC: Double,
    val deletedCents: Double,
)

/** Loads + groups every call recorded against [report] (agents +
 *  secondaries + icon-gen + fan-out / fan-in icon chain). Returns
 *  null when no usage has been recorded — caller renders an empty
 *  state. Shared by [ReportCostTable] and [ReportApiCallsScreen]. */
@Composable
internal fun rememberReportCostData(report: Report): ReportCostData? {
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
    val hasLanguageDetectCost = report.languageInputCost > 0.0 || report.languageOutputCost > 0.0
    val hasLanguageIconCost = report.languageIconInputCost > 0.0 || report.languageIconOutputCost > 0.0
    val hasLanguageCost = hasLanguageDetectCost || hasLanguageIconCost
    if (agentsWithCosts.isEmpty() && secondary.isEmpty() && !hasIconCost && !hasIconCalls && !hasLanguageCost) return null

    val agentRows = agentsWithCosts.map { agent ->
        val providerEnum = AppService.findById(agent.provider)
        val tu = agent.tokenUsage!!
        val pricing = providerEnum?.let { PricingCache.getPricing(context, it, agent.model) }
        val inCents = (pricing?.let { tu.inputTokens * it.promptPrice } ?: 0.0) * 100
        val outCents = (pricing?.let { tu.outputTokens * it.completionPrice } ?: 0.0) * 100
        CostRow("report", providerEnum?.id ?: agent.provider, agent.model, pricing?.source ?: "", agent.durationMs, tu.inputTokens, tu.outputTokens, inCents, outCents)
    }
    // Find-alternative-icons fan-out cost subtraction. Every alt
    // call is recorded as its own IconCallRecord with `type` set to
    // the bundled `_alt` prompt name AND its cost is also added to
    // the aggregate row that owns the icon (Report.iconInputCost for
    // main_alt, Report.languageIconInputCost for language_alt,
    // SecondaryResult.inputCost for meta_alt / translation_alt). The
    // per-call rows below would double-count if we didn't strip
    // that alt portion out of the aggregate rows here. Sums are in
    // *cents* to match the CostRow inputs.
    val altByType: Map<String, Pair<Double, Double>> = report.iconCalls
        .filter { !it.type.isNullOrBlank() }
        .groupBy { it.type!! }
        .mapValues { (_, list) ->
            list.sumOf { it.inputCost } * 100 to list.sumOf { it.outputCost } * 100
        }
    val altBySecondary: Map<String, Pair<Double, Double>> = report.iconCalls
        .filter { !it.attributedToSecondaryId.isNullOrBlank() }
        .groupBy { it.attributedToSecondaryId!! }
        .mapValues { (_, list) ->
            list.sumOf { it.inputCost } * 100 to list.sumOf { it.outputCost } * 100
        }
    val mainAltInCents = altByType["icon_main_alt"]?.first ?: 0.0
    val mainAltOutCents = altByType["icon_main_alt"]?.second ?: 0.0
    val languageAltInCents = altByType["icon_language_alt"]?.first ?: 0.0
    val languageAltOutCents = altByType["icon_language_alt"]?.second ?: 0.0

    // Icon-gen call surfaces as its own row so the cost table totals
    // match the report total. Hidden when icon-gen wasn't run or the
    // call didn't return token usage. The pinned agent on
    // internal/icon supplies the (provider, model) pair. Subtracts
    // the main_alt portion so per-call alt rows below don't double.
    val iconRow: CostRow? = if (report.iconInputCost > 0.0 || report.iconOutputCost > 0.0) {
        val ai = com.ai.model.SettingsHolder.current
        val iconPrompt = ai?.internalPrompts?.firstOrNull {
            it.category == "icons" && it.name == "main"
        }
        val iconAgent = iconPrompt?.let { p ->
            ai.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) }
        }
        val provider = iconAgent?.provider
        val model = iconAgent?.let { ai.getEffectiveModelForAgent(it) } ?: ""
        val pricing = provider?.let { PricingCache.getPricing(context, it, model) }
        CostRow(
            type = "icon_main",
            providerDisplay = provider?.id ?: "",
            model = model,
            tier = pricing?.source ?: "",
            durationMs = null,
            inputTokens = report.iconInputTokens,
            outputTokens = report.iconOutputTokens,
            inputCents = (report.iconInputCost * 100) - mainAltInCents,
            outputCents = (report.iconOutputCost * 100) - mainAltOutCents
        )
    } else null
    // Two-call language flow surfaces as two rows. The first call
    // (detection) uses the bundled `internal/language` prompt and
    // pumps cost into `languageInputCost/OutputCost` — surfaces as
    // type = "language". The second call (icon) uses the bundled
    // `internal/language_icon` prompt and pumps cost into the
    // existing `languageIconInputCost/OutputCost` — surfaces as
    // type = "language-icon". The icon row's (provider, model)
    // shows the user's alt-pick from Report.languageIconModel when
    // set; the detection row always shows the bundled `language`
    // agent's default (no alt-pick mechanism for detection).
    val ai = com.ai.model.SettingsHolder.current
    val languageDetectRow: CostRow? = if (hasLanguageDetectCost) {
        val detectPrompt = ai?.internalPrompts?.firstOrNull {
            it.category == "internal" && it.name == "language"
        }
        val agent = detectPrompt?.let { p ->
            ai.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) }
        }
        val provider = agent?.provider
        val model = agent?.let { ai.getEffectiveModelForAgent(it) } ?: ""
        val pricing = provider?.let { PricingCache.getPricing(context, it, model) }
        CostRow(
            type = "language",
            providerDisplay = provider?.id ?: "",
            model = model,
            tier = pricing?.source ?: "",
            durationMs = null,
            inputTokens = report.languageInputTokens,
            outputTokens = report.languageOutputTokens,
            inputCents = report.languageInputCost * 100,
            outputCents = report.languageOutputCost * 100
        )
    } else null
    val languageIconRow: CostRow? = if (hasLanguageIconCost) {
        val iconPrompt = ai?.internalPrompts?.firstOrNull {
            it.category == "icons" && it.name == "language"
        }
        val iconAgent = iconPrompt?.let { p ->
            ai.agents.firstOrNull { it.name.equals(p.agent, ignoreCase = true) }
        }
        val pickedParts = report.languageIconModel?.split("/", limit = 2)
        val provider = pickedParts?.firstOrNull()?.let { AppService.findById(it) } ?: iconAgent?.provider
        val model = pickedParts?.getOrNull(1) ?: iconAgent?.let { ai?.getEffectiveModelForAgent(it) } ?: ""
        val pricing = provider?.let { PricingCache.getPricing(context, it, model) }
        CostRow(
            type = "icon_language",
            providerDisplay = provider?.id ?: "",
            model = model,
            tier = pricing?.source ?: "",
            durationMs = null,
            inputTokens = report.languageIconInputTokens,
            outputTokens = report.languageIconOutputTokens,
            inputCents = (report.languageIconInputCost * 100) - languageAltInCents,
            outputCents = (report.languageIconOutputCost * 100) - languageAltOutCents
        )
    } else null
    // report.iconCalls holds two distinct tier-by-tier chains — one
    // IconCallRecord per recorded attempt (including failed earlier
    // tiers). They're told apart by agentId:
    //   - per-agent report icons → agentId is a real ReportAgent id
    //     → "report-icons" bucket
    //   - fan-out pair icons     → agentId is the pair's UUID (not a
    //     report agent) → "fan-icons" bucket
    // The report-level internal/icon prompt is NOT in iconCalls — it
    // surfaces separately as `iconRow` (the "icon" bucket, 1 row).
    // The recorded provider on each record is the model that actually
    // billed the call (tier 3 = DeepSeek even when the agent / pair
    // uses a different model).
    val reportAgentIds = report.agents.map { it.agentId }.toSet()
    val iconCallRows = report.iconCalls.map { c ->
        val providerEnum = AppService.findById(c.provider)
        // `c.type` (set by Find-alt fan-out launchers to the
        // bundled `_alt` prompt name like `icon_main_alt`) takes
        // precedence. Otherwise we infer the bundled prompt name
        // from `c.tier` (1=_2 chat-continuation / 2=base / 3=_3
        // fixed-agent) and the `c.agentId`-based discriminator
        // (real ReportAgent id → report chain; anything else → fan-
        // out pair chain).
        val resolvedType = c.type ?: run {
            val isAgentChain = c.agentId in reportAgentIds
            val base = if (isAgentChain) "icon_report" else "icon_fan_out"
            when (c.tier) {
                1 -> "${base}_2"
                3 -> "${base}_3"
                else -> base
            }
        }
        CostRow(
            type = resolvedType,
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
    // user sees one consolidated breakdown \u2014 distinguished by the Type column.
    val secondaryRows = secondary.mapNotNull { s ->
        val tu = s.tokenUsage ?: return@mapNotNull null
        val providerEnum = AppService.findById(s.providerId)
        val providerDisplay = providerEnum?.id ?: s.providerId
        val pricing = providerEnum?.let { PricingCache.getPricing(context, it, s.model) }
        // Subtract any Find-alt cost attributed to this SR — that
        // portion already appears as a `<prompt>_alt` per-call row
        // below. Without the subtraction the alt cost would be
        // counted twice (once here on the SR row, once in the
        // per-call row).
        val srAlt = altBySecondary[s.id]
        val inCents = ((s.inputCost ?: 0.0) * 100) - (srAlt?.first ?: 0.0)
        val outCents = ((s.outputCost ?: 0.0) * 100) - (srAlt?.second ?: 0.0)
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
    // Fan-out icon-chain cost is already captured per-call in
    // report.iconCalls (split into the "fan-icons" bucket by
    // iconCallRows above) — no separate pass over SecondaryResult
    // .iconInputCost, which would double-count.
    val rows = (agentRows + secondaryRows + listOfNotNull(iconRow, languageDetectRow, languageIconRow) + iconCallRows).sortedByDescending { it.inputCents + it.outputCents }

    // GroupTotal carries an optional (provider, model) split so the
    // "By model" table can render the two as separate columns (same
    // shape as the All-calls list below); "By type" keeps the single
    // key column. (GroupTotal hoisted to top-level — see below.)
    fun groupByType(): List<GroupTotal> =
        // Every icon flow (initial gen + per-tier chain + Find-alt
        // fan-out) carries an `icon_<prompt>` type on its per-call
        // rows. The By-type view collapses every variant into a
        // single "icons" group so the user sees one summary line for
        // all icon spend; the All API calls view keeps the granular
        // per-prompt labels. Non-icon types group on their own key.
        rows.groupBy { if (it.type.startsWith("icon_")) "icons" else it.type }.map { (k, gs) ->
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

    var totalInC = 0.0; var totalOutC = 0.0
    rows.forEach { totalInC += it.inputCents; totalOutC += it.outputCents }
    // Costs the user dropped from the report via Delete actions —
    // surfaces as its own row above the Total, same pattern as
    // the result-page footer + the HTML export's cost view.
    val deletedCents = report.costsFromDeletedItems * 100

    return ReportCostData(rows, byType, byModel, totalInC, totalOutC, deletedCents)
}

@Composable
fun ReportCostTable(report: Report, onShowAllApi: () -> Unit = {}) {
    val data = rememberReportCostData(report) ?: return
    val tColor = AppColors.Blue
    var popup by remember { mutableStateOf<CostPopup?>(null) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Two sortable lists. One line per row; tap = full breakdown
        // in a dialog. "By type" carries the bold grand-totals row
        // (and the deleted-items orange line) so we don't double-
        // count across the two sections. Per-call detail lives on a
        // dedicated screen reached via the button below.
        CostRowSection(
            label = "By type",
            rows = data.byType,
            columnLabels = listOf("Type", "Calls", "Tokens", "Total"),
            columnWeights = listOf(1.7f, 0.8f, 1.3f, 1.2f),
            keyExtractors = listOf(
                { (it as GroupTotal).key },
                { (it as GroupTotal).calls },
                { (it as GroupTotal).let { g -> g.inputTokens + g.outputTokens } },
                { (it as GroupTotal).let { g -> g.inputCents + g.outputCents } },
            ),
            renderCells = { g ->
                listOf(
                    CostCell(g.key, costTypeColor(g.key), mono = false, end = false, weight = 1.7f),
                    CostCell(g.calls.toString(), Color.White, mono = true, end = true, weight = 0.8f),
                    CostCell("%,d".format(g.inputTokens + g.outputTokens), Color.White, mono = true, end = true, weight = 1.3f),
                    CostCell("%.2f ¢".format(g.inputCents + g.outputCents), tColor, mono = true, end = true, weight = 1.2f),
                )
            },
            onRowTap = { g -> popup = CostPopup.TypeGroup(g) },
            defaultSortColumn = 3,
            defaultSortDescending = true,
            totalsCells = listOf(
                CostCell("Total", tColor, mono = false, end = false, weight = 1.7f, bold = true),
                CostCell(data.rows.size.toString(), tColor, mono = true, end = true, weight = 0.8f, bold = true),
                CostCell("%,d".format(data.rows.sumOf { it.inputTokens + it.outputTokens }), tColor, mono = true, end = true, weight = 1.3f, bold = true),
                CostCell("%.2f ¢".format(data.totalInC + data.totalOutC + data.deletedCents), tColor, mono = true, end = true, weight = 1.2f, bold = true),
            ),
            deletedCents = data.deletedCents,
        )
        CostRowSection(
            label = "By model",
            rows = data.byModel,
            columnLabels = listOf("Model", "Calls", "Total"),
            columnWeights = listOf(2f, 1f, 1f),
            keyExtractors = listOf(
                { com.ai.ui.shared.shortModelName((it as GroupTotal).model ?: "") },
                { (it as GroupTotal).calls },
                { (it as GroupTotal).let { g -> g.inputCents + g.outputCents } },
            ),
            renderCells = { g ->
                listOf(
                    CostCell(com.ai.ui.shared.shortModelName(g.model ?: ""), Color.White, mono = true, end = false, weight = 2f),
                    CostCell(g.calls.toString(), Color.White, mono = true, end = true, weight = 1f),
                    CostCell("%.2f ¢".format(g.inputCents + g.outputCents), tColor, mono = true, end = true, weight = 1f),
                )
            },
            onRowTap = { g -> popup = CostPopup.ModelGroup(g) },
            defaultSortColumn = 2,
            defaultSortDescending = true,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = onShowAllApi,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("All API calls (${data.rows.size})", color = AppColors.Blue, fontSize = 14.sp)
        }
    }

    popup?.let { p -> CostDetailDialog(p, onDismiss = { popup = null }) }
}

/** Full-screen drill-in opened from the cost view's "All API calls"
 *  button. Renders every single call (agents + secondaries + icon-gen
 *  + fan-out / fan-in) as one sortable list, tap-to-detail. */
@Composable
fun ReportApiCallsScreen(report: Report, onBack: () -> Unit, onNavigateToTraceFile: (String) -> Unit = {}) {
    BackHandler { onBack() }
    val data = rememberReportCostData(report)
    val tColor = AppColors.Blue
    var popup by remember { mutableStateOf<CostPopup?>(null) }
    // Filter dropdowns for the two non-cost columns. "All" leaves the
    // list unfiltered. Filters compose (Type AND Model must match);
    // the values list is rebuilt from the visible rows so a Type pick
    // narrows the Model options to those still present, and vice-versa.
    var typeFilter by rememberSaveable { mutableStateOf("All") }
    var modelFilter by rememberSaveable { mutableStateOf("All") }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(
            helpTopic = "cost_view",
            title = "Report - API",
            reportIcon = report.icon?.takeIf { it.isNotBlank() } ?: "📝",
            onBackClick = onBack,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            if (data == null || data.rows.isEmpty()) {
                Text("(no API calls recorded)", color = AppColors.TextTertiary, fontSize = 14.sp)
            } else {
                val allRows = data.rows
                val typeOptions = remember(allRows) {
                    listOf("All") + allRows.map { it.type }.distinct().sorted()
                }
                val modelOptions = remember(allRows, typeFilter) {
                    val pool = if (typeFilter == "All") allRows else allRows.filter { it.type == typeFilter }
                    listOf("All") + pool.map { com.ai.ui.shared.shortModelName(it.model) }.distinct().sorted()
                }
                // Reset model filter if a Type change made it stale.
                LaunchedEffect(typeFilter) {
                    if (modelFilter !in modelOptions) modelFilter = "All"
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterDropdown(
                        label = "Type",
                        selected = typeFilter,
                        options = typeOptions,
                        onSelect = { typeFilter = it },
                        modifier = Modifier.weight(1f)
                    )
                    FilterDropdown(
                        label = "Model",
                        selected = modelFilter,
                        options = modelOptions,
                        onSelect = { modelFilter = it },
                        modifier = Modifier.weight(2f)
                    )
                }
                val filtered = remember(allRows, typeFilter, modelFilter) {
                    allRows.filter { r ->
                        (typeFilter == "All" || r.type == typeFilter) &&
                            (modelFilter == "All" || com.ai.ui.shared.shortModelName(r.model) == modelFilter)
                    }
                }
                if (filtered.isEmpty()) {
                    Text("(no calls match the filter)", color = AppColors.TextTertiary, fontSize = 14.sp)
                } else {
                    CostRowSection(
                        label = "All API calls",
                        rows = filtered,
                        columnLabels = listOf("Type", "Model", "Cost"),
                        columnWeights = listOf(1f, 2f, 1f),
                        keyExtractors = listOf(
                            { (it as CostRow).type },
                            { com.ai.ui.shared.shortModelName((it as CostRow).model) },
                            { (it as CostRow).let { r -> r.inputCents + r.outputCents } },
                        ),
                        renderCells = { r ->
                            listOf(
                                CostCell(r.type, costTypeColor(r.type), mono = false, end = false, weight = 1f),
                                CostCell(com.ai.ui.shared.shortModelName(r.model), Color.White, mono = true, end = false, weight = 2f),
                                CostCell("%.2f ¢".format(r.inputCents + r.outputCents), tColor, mono = true, end = true, weight = 1f),
                            )
                        },
                        onRowTap = { r -> popup = CostPopup.Call(r) },
                        defaultSortColumn = 2,
                        defaultSortDescending = true,
                    )
                }
            }
        }
    }
    popup?.let { p ->
        CostDetailDialog(
            popup = p,
            onDismiss = { popup = null },
            onNavigateToTraceFile = { tf ->
                popup = null
                onNavigateToTraceFile(tf)
            },
            reportId = report.id
        )
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text(
                "$label: $selected",
                modifier = Modifier.weight(1f),
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(" ▾", fontSize = 12.sp, color = AppColors.TextTertiary)
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false }
        ) {
            options.forEach { opt ->
                val isSel = opt == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            opt,
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) AppColors.Blue else Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(opt); open = false }
                )
            }
        }
    }
}

/** One row in the per-call detail list — hoisted to top-level
 *  so the mobile card renderers below can reference it. */
internal data class CostRow(
    val type: String, val providerDisplay: String, val model: String,
    val tier: String, val durationMs: Long?,
    val inputTokens: Int, val outputTokens: Int,
    val inputCents: Double, val outputCents: Double
)

/** Per-group rollup for the By type / By model summaries —
 *  hoisted alongside [CostRow]. */
internal data class GroupTotal(
    val key: String,
    val provider: String?, val model: String?,
    val calls: Int,
    val inputTokens: Int, val outputTokens: Int,
    val inputCents: Double, val outputCents: Double
)

// ===== Row-based cost view (V3) =====
//
// V3 replaces V2's card stack with a true list: one short line per
// row, sortable columns, tap-to-popup for the full breakdown.
// Three sections (By type / By model / All calls) all go through
// the same generic [CostRowSection]; the dialog is shared too.

private data class CostCell(
    val text: String,
    val color: Color,
    val mono: Boolean,
    val end: Boolean,
    val weight: Float,
    val bold: Boolean = false,
)

private sealed class CostPopup {
    data class TypeGroup(val g: GroupTotal) : CostPopup()
    data class ModelGroup(val g: GroupTotal) : CostPopup()
    data class Call(val r: CostRow) : CostPopup()
}

private fun costTypeColor(type: String): Color = when (type) {
    "rerank" -> AppColors.Orange
    "summarize" -> AppColors.Indigo
    "compare" -> AppColors.Purple
    "moderation" -> AppColors.Red
    "fan-out" -> AppColors.Indigo
    "fan-in" -> AppColors.Green
    "language" -> AppColors.Yellow
    // Every icon-related per-call row (initial gen, per-tier chain,
    // Find-alt fan-out) uses the same hue — they collapse into a
    // single "icons" group on the By-type view, so a uniform
    // colour reads as one visual cluster on the All API calls list.
    "icons" -> AppColors.Brown
    else -> if (type.startsWith("icon_")) AppColors.Brown else AppColors.TextSecondary
}

@Composable
private fun <T> CostRowSection(
    label: String,
    rows: List<T>,
    columnLabels: List<String>,
    columnWeights: List<Float>,
    keyExtractors: List<(T) -> Comparable<*>>,
    renderCells: (T) -> List<CostCell>,
    onRowTap: (T) -> Unit,
    defaultSortColumn: Int,
    defaultSortDescending: Boolean,
    totalsCells: List<CostCell>? = null,
    deletedCents: Double = 0.0,
) {
    if (rows.isEmpty()) return
    var sortColumn by rememberSaveable(label) { mutableStateOf(defaultSortColumn) }
    var sortDescending by rememberSaveable(label) { mutableStateOf(defaultSortDescending) }
    val sorted = remember(rows, sortColumn, sortDescending) {
        @Suppress("UNCHECKED_CAST")
        val extract = keyExtractors[sortColumn] as (T) -> Comparable<Any>
        val cmp = compareBy<T> { extract(it) }
        rows.sortedWith(if (sortDescending) cmp.reversed() else cmp)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label · ${rows.size}",
            fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            columnLabels.forEachIndexed { i, lbl ->
                val active = i == sortColumn
                val arrow = if (active) (if (sortDescending) " ▼" else " ▲") else ""
                Text(
                    text = lbl + arrow,
                    fontSize = 11.sp,
                    color = if (active) AppColors.Blue else AppColors.TextSecondary,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    textAlign = if (i == 0) androidx.compose.ui.text.style.TextAlign.Start
                                else androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier
                        .weight(columnWeights[i])
                        .clickable {
                            if (sortColumn == i) sortDescending = !sortDescending
                            else { sortColumn = i; sortDescending = true }
                        }
                        .padding(vertical = 3.dp)
                )
            }
        }
        HorizontalDivider(color = AppColors.TextDim.copy(alpha = 0.25f))
        sorted.forEachIndexed { idx, item ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onRowTap(item) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                renderCells(item).forEach { c -> CostCellText(c) }
            }
            if (idx < sorted.lastIndex) {
                HorizontalDivider(color = AppColors.TextDim.copy(alpha = 0.15f))
            }
        }
        if (totalsCells != null) {
            HorizontalDivider(color = AppColors.TextDim.copy(alpha = 0.25f))
            if (deletedCents > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "deleted",
                        fontSize = 11.sp, color = AppColors.Orange,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.weight(columnWeights.first())
                    )
                    val middleSum = columnWeights.drop(1).dropLast(1).sum()
                    if (middleSum > 0f) Spacer(modifier = Modifier.weight(middleSum))
                    Text(
                        "+%.2f ¢".format(deletedCents),
                        fontSize = 11.sp, color = AppColors.Orange,
                        fontFamily = FontFamily.Monospace,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(columnWeights.last())
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                totalsCells.forEach { c -> CostCellText(c) }
            }
        }
    }
}

@Composable
private fun RowScope.CostCellText(c: CostCell) {
    Text(
        text = c.text,
        color = c.color,
        fontSize = 13.sp,
        fontFamily = if (c.mono) FontFamily.Monospace else FontFamily.Default,
        fontWeight = if (c.bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = if (c.end) androidx.compose.ui.text.style.TextAlign.End
                    else androidx.compose.ui.text.style.TextAlign.Start,
        modifier = Modifier.weight(c.weight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CostDetailDialog(
    popup: CostPopup,
    onDismiss: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    reportId: String? = null
) {
    val titleText: String
    val titleColor: Color
    val titleMono: Boolean
    val body: String
    when (popup) {
        is CostPopup.TypeGroup -> {
            titleText = popup.g.key
            titleColor = costTypeColor(popup.g.key)
            titleMono = false
            body = buildGroupBody(popup.g)
        }
        is CostPopup.ModelGroup -> {
            titleText = listOfNotNull(popup.g.provider, popup.g.model).joinToString(" / ")
            titleColor = AppColors.Blue
            titleMono = true
            body = buildGroupBody(popup.g)
        }
        is CostPopup.Call -> {
            titleText = popup.r.type
            titleColor = costTypeColor(popup.r.type)
            titleMono = false
            body = buildCallBody(popup.r)
        }
    }
    // 🐞 for per-call popups: find the trace file matching this call's
    // (reportId, model). When multiple match (e.g. multiple fan-out
    // pairs on the same model) prefer the most recent — best-effort
    // since CostRow doesn't carry a per-call trace id.
    val traceFile: String? = if (popup is CostPopup.Call && reportId != null && ApiTracer.isTracingEnabled) {
        remember(popup.r.model, reportId) {
            ApiTracer.getTraceFiles()
                .asSequence()
                .filter { it.reportId == reportId && (it.model == popup.r.model || it.model == null) }
                .maxByOrNull { it.timestamp }
                ?.filename
        }
    } else null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.CardBackground,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    titleText,
                    color = titleColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (titleMono) FontFamily.Monospace else FontFamily.Default,
                    modifier = Modifier.weight(1f)
                )
                if (traceFile != null) {
                    Text(
                        "🐞", fontSize = 20.sp,
                        modifier = Modifier
                            .clickable { onNavigateToTraceFile(traceFile) }
                            .padding(horizontal = 6.dp)
                    )
                }
            }
        },
        text = {
            Text(
                text = body,
                color = AppColors.TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AppColors.Blue)
            }
        },
    )
}

private fun buildGroupBody(g: GroupTotal): String {
    val total = g.inputCents + g.outputCents
    val callsStr = if (g.calls == 1) "1 call" else "${g.calls} calls"
    return buildString {
        appendLine(callsStr)
        appendLine()
        appendLine("in  tokens: %,d".format(g.inputTokens))
        appendLine("out tokens: %,d".format(g.outputTokens))
        appendLine()
        appendLine("in  ¢: %.4f".format(g.inputCents))
        appendLine("out ¢: %.4f".format(g.outputCents))
        append("total: %.4f ¢".format(total))
    }
}

private fun buildCallBody(r: CostRow): String {
    val total = r.inputCents + r.outputCents
    val durStr = r.durationMs?.let { "%.1fs".format(it / 1000.0) } ?: "—"
    val tierStr = if (r.tier.isBlank()) "—" else r.tier
    return buildString {
        appendLine("provider: ${r.providerDisplay}")
        appendLine("model:    ${r.model}")
        appendLine("tier:     $tierStr")
        appendLine("duration: $durStr")
        appendLine()
        appendLine("in  tokens: %,d".format(r.inputTokens))
        appendLine("out tokens: %,d".format(r.outputTokens))
        appendLine()
        appendLine("in  ¢: %.4f".format(r.inputCents))
        appendLine("out ¢: %.4f".format(r.outputCents))
        append("total: %.4f ¢".format(total))
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
