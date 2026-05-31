package com.ai.ui.report.manage.view
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.TemperatureRange
import com.ai.data.UserNote
import com.ai.data.notesFor
import com.ai.data.temperatureRangeForProvider
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.modelInfoClickable
import com.ai.viewmodel.ReasoningEffortSweepState
import com.ai.viewmodel.TemperatureSweepState
import com.ai.viewmodel.WebSearchReplayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val singleConclusionTagRegex = Regex("<conclusion>.*?</conclusion>", RegexOption.DOT_MATCHES_ALL)
private val singleMotivationTagRegex = Regex("<motivation>.*?</motivation>", RegexOption.DOT_MATCHES_ALL)

/**
 * Per-model result viewer reached by tapping a model row on the Report
 * result screen. Shows just the chosen agent's rendered response
 * (conclusion / motivation extraction, citations, search results,
 * related questions) followed by a three-button action row: Remove
 * model from report, Open Model Info, Show trace entry.
 *
 * Distinct from [ReportsViewerScreen], which is the all-agents picker
 * reached from the View → Results button.
 */
@Composable
fun ReportModelScreen(
    reportId: String,
    agentId: String,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onRemoveAgent: (String, String) -> Unit,
    /** Delete a specific SecondaryResult by id — used by the
     *  multi-language delete popup's "Active language only" path to
     *  drop just the active-language AGENT TRANSLATE row while
     *  keeping the agent and its other translations. Default no-op
     *  so legacy callers still work for the "All languages" path. */
    onDeleteRowById: (String) -> Unit = { _ -> },
    onRegenerateAgent: (String, String) -> Unit = { _, _ -> },
    /** Pre-seed a fresh chat session with the report prompt + this
     *  agent's response and the agent's resolved system prompt /
     *  parameters from current settings, then open it against the
     *  same provider/model. */
    onContinueWithCurrent: (String, String) -> Unit = { _, _ -> },
    /** Stash this agent's response as the next chat's input-box
     *  starter and route to the agent picker (same flow as
     *  AI Chat → New Chat with Agent). */
    onContinueWithAgentPicker: (String, String) -> Unit = { _, _ -> },
    /** Stash this agent's response as the next chat's input-box
     *  starter and route to the configure-on-the-fly chain
     *  (provider → model → params → session). */
    onContinueWithOnTheFly: (String, String) -> Unit = { _, _ -> },
    /** Wired by the parent (ReportScreen) to set its
     *  agentIconDetailFor state. The big agent-icon glyph rendered
     *  below the response taps through here so a user can land
     *  directly on the Agent Icon screen without backing out to
     *  the report's agent grid. */
    onOpenAgentIcon: (String) -> Unit = {},
    /** Real-route 👁 target: open the View "Model reports" screen for this
     *  report seeded at the given agent. Used when there's no overlay
     *  [LocalPendingViewOverManage] holder (the standalone route). */
    onNavigateToViewReports: (String) -> Unit = {},
    temperatureSweepStates: Map<String, TemperatureSweepState> = emptyMap(),
    onStartTemperatureSweep: (String, String, List<Float>) -> Unit = { _, _, _ -> },
    onApplyTemperatureCandidate: (String, String, Int) -> Unit = { _, _, _ -> },
    onClearTemperatureSweep: (String, String) -> Unit = { _, _ -> },
    reasoningEffortSweepStates: Map<String, ReasoningEffortSweepState> = emptyMap(),
    onStartReasoningEffortSweep: (String, String, List<String?>) -> Unit = { _, _, _ -> },
    onApplyReasoningEffortCandidate: (String, String, Int) -> Unit = { _, _, _ -> },
    onClearReasoningEffortSweep: (String, String) -> Unit = { _, _ -> },
    webSearchReplayStates: Map<String, WebSearchReplayState> = emptyMap(),
    onStartWebSearchReplay: (String, String) -> Unit = { _, _ -> },
    onApplyWebSearchReplay: (String, String) -> Unit = { _, _ -> },
    onClearWebSearchReplay: (String, String) -> Unit = { _, _ -> }
) {
    // Track which agent is currently shown locally so the Previous /
    // Next buttons at the bottom can step through report.agents
    // without going back to the report screen. Resets if the parent
    // re-enters this screen with a different agentId param.
    var currentAgentId by rememberSaveable(reportId, agentId) { mutableStateOf(agentId) }

    var showContinuePicker by remember { mutableStateOf(false) }
    if (showContinuePicker) {
        ContinueInChatPickerScreen(
            onPickCurrent = {
                showContinuePicker = false
                onContinueWithCurrent(reportId, currentAgentId)
            },
            onPickAgentPicker = {
                showContinuePicker = false
                onContinueWithAgentPicker(reportId, currentAgentId)
            },
            onPickOnTheFly = {
                showContinuePicker = false
                onContinueWithOnTheFly(reportId, currentAgentId)
            },
            onBack = { showContinuePicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }
    var showTemperatureSweep by remember { mutableStateOf(false) }
    var showReasoningEffortSweep by remember { mutableStateOf(false) }
    var showWebSearchReplay by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    val context = LocalContext.current
    // Re-key on the report data version so an in-place edit (🗣️ refine
    // Apply, regenerate, icon/title write) re-reads the report and the
    // body / chatMessages refresh. ViewReportCache is mtime-staleness-safe,
    // so the re-read returns the fresh parse.
    val reportDataVersion by ReportDataVersion.version.collectAsState()
    // Loaded asynchronously: getReport reads + parses the report JSON
    // (which can be MB-sized for image-attached reports). The Loading
    // → Loaded transition keeps the UI thread free while reading.
    val reportState = produceState<com.ai.data.Report?>(initialValue = null, reportId, reportDataVersion) {
        value = withContext(Dispatchers.IO) { com.ai.ui.report.view.helpers.ViewReportCache.get(context, reportId) }
    }
    val report = reportState.value
    val agent = report?.agents?.find { it.agentId == currentAgentId }
    val provider = agent?.let { AppService.findById(it.provider) }

    if (showTemperatureSweep) {
        val sweepAgentId = currentAgentId
        TemperatureSweepScreen(
            reportId = reportId,
            agentId = sweepAgentId,
            modelLabel = agent?.let {
                provider?.let { p -> com.ai.ui.shared.modelLabel(p.id, it.model, separator = " — ") }
                    ?: it.model
            } ?: "Model response",
            temperatureRange = provider?.let(::temperatureRangeForProvider) ?: TemperatureRange.Default,
            state = temperatureSweepStates[TemperatureSweepState.key(reportId, sweepAgentId)],
            onSubmit = { temps -> onStartTemperatureSweep(reportId, sweepAgentId, temps) },
            onUseCandidate = { index ->
                onApplyTemperatureCandidate(reportId, sweepAgentId, index)
                onClearTemperatureSweep(reportId, sweepAgentId)
                showTemperatureSweep = false
            },
            onTrace = onNavigateToTraceFile,
            onBack = {
                onClearTemperatureSweep(reportId, sweepAgentId)
                showTemperatureSweep = false
            }
        )
        return
    }

    if (showReasoningEffortSweep) {
        val sweepAgentId = currentAgentId
        ReasoningEffortSweepScreen(
            reportId = reportId,
            agentId = sweepAgentId,
            modelLabel = agent?.let {
                provider?.let { p -> com.ai.ui.shared.modelLabel(p.id, it.model, separator = " — ") }
                    ?: it.model
            } ?: "Model response",
            state = reasoningEffortSweepStates[ReasoningEffortSweepState.key(reportId, sweepAgentId)],
            onSubmit = { efforts -> onStartReasoningEffortSweep(reportId, sweepAgentId, efforts) },
            onUseCandidate = { index ->
                onApplyReasoningEffortCandidate(reportId, sweepAgentId, index)
                onClearReasoningEffortSweep(reportId, sweepAgentId)
                showReasoningEffortSweep = false
            },
            onTrace = onNavigateToTraceFile,
            onBack = {
                onClearReasoningEffortSweep(reportId, sweepAgentId)
                showReasoningEffortSweep = false
            }
        )
        return
    }

    if (showWebSearchReplay) {
        val replayAgentId = currentAgentId
        WebSearchReplayScreen(
            reportId = reportId,
            agentId = replayAgentId,
            modelLabel = agent?.let {
                provider?.let { p -> com.ai.ui.shared.modelLabel(p.id, it.model, separator = " — ") }
                    ?: it.model
            } ?: "Model response",
            originalResponse = agent?.responseBody,
            state = webSearchReplayStates[WebSearchReplayState.key(reportId, replayAgentId)],
            onStart = { onStartWebSearchReplay(reportId, replayAgentId) },
            onUseResponse = {
                onApplyWebSearchReplay(reportId, replayAgentId)
                onClearWebSearchReplay(reportId, replayAgentId)
                showWebSearchReplay = false
            },
            onTrace = onNavigateToTraceFile,
            onBack = {
                onClearWebSearchReplay(reportId, replayAgentId)
                showWebSearchReplay = false
            }
        )
        return
    }

    if (report == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TitleBar(helpTopic = "report_single_result", title = "Model response", subject = "Conclusion, motivation and full reply", onBackClick = onBack,
                modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp))
        }
        return
    }
    if (agent == null || provider == null) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TitleBar(helpTopic = "report_single_result", title = "Model response", subject = "Conclusion, motivation and full reply", onBackClick = onBack,
                modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp))
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Result not found", color = AppColors.TextSecondary, fontSize = 16.sp)
            }
        }
        return
    }

    // Trace filenames stored on the agent at call time (no fragile
    // ApiTracer time-based guessing): primary response, per-model icon
    // chain, per-model title.
    val agentTraceFilename = agent.traceFile?.takeIf { it.isNotBlank() }
    val iconTraceFilename = agent.iconTraceFile?.takeIf { it.isNotBlank() }
        ?: rememberAgentIconTrace(
            reportId = reportId,
            agentModel = agent.model,
            iconKey = agent.icon,
            winningTier = agent.iconWinningTier,
            promptUsed = agent.iconPromptUsed
        )?.takeIf { it.isNotBlank() }
    val titleTraceFilename = agent.modelTitleTraceFile?.takeIf { it.isNotBlank() }

    // Load this report's TRANSLATE secondaries — drives the language
    // icon picker below (same UX as View → Prompt / Model response).
    // The picker is suppressed when there are no translations.
    val translatesState = produceState(initialValue = emptyList<SecondaryResult>(), reportId) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
        }
    }
    val translates = translatesState.value
    // Only show language tabs that actually carry a translation of
    // THIS agent — the report-wide TRANSLATE list may include prompt
    // / meta / other-agent rows whose languages don't necessarily
    // have a sibling for this particular agent. Original always
    // applies (the agent's responseBody is the original-language body).
    val langTabs = remember(translates, currentAgentId) {
        buildLangTabs(translates.filter {
            it.translateSourceKind == "AGENT" && it.translateSourceTargetId == currentAgentId
        })
    }
    var selectedLangKey by rememberSaveable(reportId) { mutableStateOf(LangTab.ORIGINAL_KEY) }
    LaunchedEffect(langTabs) {
        if (langTabs.none { it.key == selectedLangKey }) selectedLangKey = LangTab.ORIGINAL_KEY
    }
    // Pick out the TRANSLATE row that targets the current agent for
    // the active language. Used both for body substitution and to
    // re-point 🐞 / 📋 / 📤 at the translation when a non-Original
    // language is selected.
    val activeAgentTranslateRow: SecondaryResult? = remember(translates, selectedLangKey, currentAgentId, langTabs) {
        if (selectedLangKey == LangTab.ORIGINAL_KEY) null
        else {
            val tab = langTabs.firstOrNull { it.key == selectedLangKey }
            val langName = tab?.displayName ?: return@remember null
            translates.firstOrNull {
                it.targetLanguage == langName &&
                    it.translateSourceKind == "AGENT" &&
                    it.translateSourceTargetId == currentAgentId
            }
        }
    }
    val displayBody = activeAgentTranslateRow?.content ?: agent.responseBody
    val traceFilename = activeAgentTranslateRow?.traceFile?.takeIf { it.isNotBlank() } ?: agentTraceFilename

    // For translated reports: load the source agent's response so the
    // "Translation info" button can pop a split-screen original-vs-
    // translation viewer. The translated copy preserves agentId, so a
    // direct match by agentId works.
    val sourceAgentBodyState = produceState<String?>(initialValue = null, report.sourceReportId, currentAgentId) {
        val sid = report.sourceReportId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, sid)?.agents
                ?.firstOrNull { it.agentId == currentAgentId }?.responseBody
        }
    }
    val sourceAgentBody = sourceAgentBodyState.value
    val canShowTranslation = sourceAgentBody != null && !agent.responseBody.isNullOrBlank()
    var showTranslationCompare by remember { mutableStateOf(false) }

    if (showTranslationCompare && sourceAgentBody != null && agent.responseBody != null) {
        TranslationCompareScreen(
            title = "Translation info — ${com.ai.ui.shared.modelLabel(provider.id, agent.model, separator = " / ")}",
            originalLabel = "Original",
            originalContent = sourceAgentBody,
            translatedLabel = "Translation",
            translatedContent = agent.responseBody!!,
            onBack = { showTranslationCompare = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // New-style translation compare: fires when the active picker
    // language renders an AGENT TRANSLATE overlay on top of this
    // agent's responseBody. Source = the original responseBody,
    // translation = the overlay's content.
    var showLiveTranslationCompare by remember { mutableStateOf(false) }
    val liveAgentTranslate = activeAgentTranslateRow
    if (showLiveTranslationCompare && liveAgentTranslate != null && !agent.responseBody.isNullOrBlank() && !liveAgentTranslate.content.isNullOrBlank()) {
        val translatedLangLabel = liveAgentTranslate.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translation"
        val tf = liveAgentTranslate.traceFile
        val translatedIcon = liveAgentTranslate.targetLanguage?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
        TranslationCompareScreen(
            title = "Translation — ${com.ai.ui.shared.modelLabel(provider.id, agent.model, separator = " / ")}",
            originalLabel = "Original",
            originalContent = agent.responseBody!!,
            translatedLabel = translatedLangLabel,
            translatedContent = liveAgentTranslate.content!!,
            onBack = { showLiveTranslationCompare = false },
            onNavigateHome = onNavigateHome,
            onTrace = tf?.let { fn -> { onNavigateToTraceFile(fn) } },
            onDelete = {
                onDeleteRowById(liveAgentTranslate.id)
                showLiveTranslationCompare = false
            },
            originalIcon = report?.languageIcon,
            translatedIcon = translatedIcon
        )
        return
    }

    var confirmRemove by remember { mutableStateOf(false) }
    var confirmLangChoice by remember { mutableStateOf(false) }
    var confirmReload by remember { mutableStateOf(false) }
    val canContinueInChat = !agent.responseBody.isNullOrBlank() && agent.errorMessage.isNullOrBlank()

    // ✍️ user notes for THIS agent (follows prev/next via currentAgentId).
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(reportId, "AGENT", currentAgentId, noteEdit!!) { noteEdit = null }
        return
    }
    val noteDataVersion by ReportDataVersion.version.collectAsState()
    val agentNotes by produceState(emptyList<UserNote>(), reportId, currentAgentId, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, reportId)?.notesFor("AGENT", currentAgentId) ?: emptyList()
        }
    }

    // 🗣️ refine-in-chat overlay for THIS agent's answer.
    val aiSettings = com.ai.ui.shared.LocalAiSettings.current
    var showAgentChat by remember { mutableStateOf(false) }
    if (showAgentChat) {
        val settingsAgent = aiSettings.getAgentById(currentAgentId)
        val initialParams = if (settingsAgent != null) {
            val rp = aiSettings.resolveAgentParameters(settingsAgent)
            com.ai.data.ChatParameters(
                temperature = rp.temperature, maxTokens = rp.maxTokens, topP = rp.topP, topK = rp.topK,
                frequencyPenalty = rp.frequencyPenalty, presencePenalty = rp.presencePenalty,
                systemPrompt = rp.systemPrompt ?: "",
                searchEnabled = rp.searchEnabled, returnCitations = rp.returnCitations,
                searchRecency = rp.searchRecency, webSearchTool = rp.webSearchTool
            )
        } else com.ai.data.ChatParameters()
        val seed = buildList {
            add(com.ai.data.ChatMessage(role = "user", content = report.prompt, imageBase64 = report.imageBase64, imageMime = report.imageMime))
            agent.responseBody?.takeIf { it.isNotBlank() }?.let { add(com.ai.data.ChatMessage(role = "assistant", content = it)) }
        }
        AgentChatScreen(
            titleBarSubject = com.ai.ui.shared.modelLabel(provider.id, agent.model, separator = " — "),
            service = provider,
            model = agent.model,
            agentIdForKey = currentAgentId,
            initialMessages = agent.chatMessages.ifEmpty { seed },
            initialParams = initialParams,
            aiSettings = aiSettings,
            onSaveMessages = { ReportStorage.saveAgentChatMessages(context, reportId, currentAgentId, it) },
            onApply = { ReportStorage.applyAgentChatResponse(context, reportId, currentAgentId, it) },
            onBack = { showAgentChat = false }
        )
        return
    }

    val agentLabel = com.ai.ui.shared.modelLabel(provider.id, agent.model, separator = " — ")
    // Pre-computed so the swipe handler (in the content Box below) can
    // close over the same ordering the Previous / Next buttons use.
    val orderedAgents = remember(report.agents) {
        report.agents.sortedWith(
            compareBy({ it.model.lowercase() }, { it.provider.lowercase() })
        )
    }
    val agentIdx = orderedAgents.indexOfFirst { it.agentId == currentAgentId }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 👁 → the View "Model reports" screen at this agent. Overlay
        // path uses the pending-jump holder; the standalone route (holder
        // null) navigates via onNavigateToViewReports.
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            { holder.value = com.ai.ui.shared.ViewJump.Reports(currentAgentId) }
        } ?: { onNavigateToViewReports(currentAgentId) }
        TitleBar(
            helpTopic = "report_single_result",
            title = "Model response",
            reportIcon = report.icon?.takeIf { it.isNotBlank() } ?: "📝",
            subject = agentLabel,
            subjectProviderService = provider,
            subjectModel = agent.model,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            // Response-trace 🐞 lives next to the big icon at the top now,
            // not in the bottom icon bar.
            onDelete = {
                // Multi-language agents get the 3-button popup so the
                // user can drop just one language's AGENT TRANSLATE
                // row vs. removing the agent and every translation.
                // Single-language agents keep the existing remove
                // confirm dialog.
                if (langTabs.size > 1) confirmLangChoice = true
                else confirmRemove = true
            },
            onInfo = { onNavigateToModelInfo(provider, agent.model) },
            onReload = { confirmReload = true },
            onChat = if (canContinueInChat) { { showContinuePicker = true } } else null,
            onAgentChat = if (canContinueInChat) { { showAgentChat = true } } else null,
            onTemperatureSweep = { showTemperatureSweep = true },
            onReasoningEffortSweep = { showReasoningEffortSweep = true },
            onWebSearchReplay = { showWebSearchReplay = true },
            onTranslationCompare = if (liveAgentTranslate != null && !agent.responseBody.isNullOrBlank() && !liveAgentTranslate.content.isNullOrBlank()) {
                { showLiveTranslationCompare = true }
            } else null,
            onCopy = displayBody?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "model response") }
            },
            onShare = displayBody?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.shareText(context, body, "Model response — $agentLabel") }
            },
            onAddNote = { noteEdit = NoteEdit.Add },
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp)
        )

        if (agentNotes.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                UserNotesSection(
                    reportId = reportId,
                    notes = agentNotes,
                    onEdit = { noteEdit = NoteEdit.Edit(it.id, it.text) }
                )
            }
        }

        if (langTabs.size > 1) {
            LanguagePickerRow(
                langTabs, selectedLangKey,
                onSelect = { selectedLangKey = it },
                useIcons = true,
                originalIcon = report.languageIcon
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
            .horizontalSwipeNavigation(
                key1 = currentAgentId,
                key2 = orderedAgents,
                // Wrap both ways — swipe agents forever, no edge stop.
                atFirst = false,
                atLast = false,
                onSwipeLeft = {
                    val n = orderedAgents.size
                    if (n > 0 && agentIdx >= 0) currentAgentId = orderedAgents[(agentIdx + 1) % n].agentId
                },
                onSwipeRight = {
                    val n = orderedAgents.size
                    if (n > 0 && agentIdx >= 0) currentAgentId = orderedAgents[(agentIdx - 1 + n) % n].agentId
                }
            )
        ) {
            val rawBody = displayBody
            val errorMessage = agent.errorMessage
            when {
                !errorMessage.isNullOrBlank() -> {
                    Column {
                        Text("Error", fontSize = 16.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary)
                    }
                }
                rawBody.isNullOrBlank() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No analysis available", color = AppColors.TextSecondary, fontSize = 16.sp)
                    }
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // Big centred model-response icon (the per-model
                        // chain's emoji) with a 🐞 to its trace (best-effort).
                        agent.icon?.takeIf { it.isNotBlank() }?.let { glyph ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(glyph, fontSize = 80.sp, color = Color.White)
                                // 🐞 → the trace of the call that produced THIS
                                // icon (the per-model icon-chain trace).
                                iconTraceFilename?.let { fn ->
                                    Text("🐞", fontSize = 20.sp,
                                        modifier = Modifier.padding(start = 12.dp)
                                            .clickable { onNavigateToTraceFile(fn) })
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        // Centred model-response title with a 🐞 to its trace.
                        agent.modelTitle?.takeIf { it.isNotBlank() }?.let { mt ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(mt, fontSize = 18.sp, color = AppColors.Green,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                titleTraceFilename?.let { fn ->
                                    Text("🐞", fontSize = 16.sp,
                                        modifier = Modifier.padding(start = 10.dp)
                                            .clickable { onNavigateToTraceFile(fn) })
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        // The response in a card; corner 🐞 → response trace.
                        Box(modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AppColors.CardBackground)
                            .padding(14.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(end = 22.dp)) {
                                val conclusion = extractTagContent(rawBody, "conclusion")
                                val motivation = extractTagContent(rawBody, "motivation")
                                val strippedBody = if (conclusion != null || motivation != null)
                                    rawBody.replace(singleConclusionTagRegex, "").replace(singleMotivationTagRegex, "").trim()
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
                            }
                            traceFilename?.let { fn ->
                                Text("🐞", fontSize = 16.sp,
                                    modifier = Modifier.align(Alignment.TopEnd).clickable { onNavigateToTraceFile(fn) })
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }

        // Step between agents via the horizontalSwipeNavigation handler
        // on the response Box above — swipe left advances, right goes
        // back. Updating currentAgentId rekeys the trace lookup and the
        // source-agent body produceState so they re-fetch.

        if (canShowTranslation) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showTranslationCompare = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Translation info", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
        }
        // The "💬 Continue in chat" body button collapsed into the
        // title-bar 💬 icon (gated on canContinueInChat) — same
        // destination, single entry point.
    }

    if (confirmReload) {
        com.ai.ui.shared.ReloadConfirmationDialog(
            target = "${provider.id} / ${com.ai.ui.shared.shortModelName(agent.model)}",
            onConfirm = {
                confirmReload = false
                onRegenerateAgent(reportId, currentAgentId)
                onBack()
            },
            onDismiss = { confirmReload = false }
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from report?") },
            text = {
                Text(
                    "Drop ${provider.id} / ${agent.model} from this report. " +
                        "Removes the saved response and recomputes totals; can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemoveAgent(reportId, currentAgentId)
                    onBack()
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    if (confirmLangChoice) {
        val activeLabel = if (selectedLangKey == LangTab.ORIGINAL_KEY) "Original"
            else langTabs.firstOrNull { it.key == selectedLangKey }?.displayName ?: "Original"
        AlertDialog(
            onDismissRequest = { confirmLangChoice = false },
            title = { Text("Remove from report?") },
            text = {
                Text("Active language: $activeLabel.\n\n" +
                    "\"Active language only\" drops just this language's translation. " +
                    "\"All languages\" removes the agent and every translation.")
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        confirmLangChoice = false
                        // Active is a per-language AGENT TRANSLATE
                        // overlay → drop just that row. Active is
                        // Original (no overlay) → fall through to
                        // "All languages" since the only-source IS
                        // the agent itself.
                        val tr = activeAgentTranslateRow
                        if (tr != null) {
                            onDeleteRowById(tr.id)
                            onBack()
                        } else {
                            onRemoveAgent(reportId, currentAgentId)
                            onBack()
                        }
                    }) {
                        Text("Active language only", color = AppColors.Red, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = {
                        confirmLangChoice = false
                        onRemoveAgent(reportId, currentAgentId)
                        onBack()
                    }) {
                        Text("All languages", color = AppColors.Red, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = { confirmLangChoice = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            }
        )
    }
}

/** Full-screen overlay picker for the three "Continue in chat …" flows
 *  (current history & model / agent picker / configure on the fly).
 *  Replaces the inline three-row card on [ReportSingleResultScreen] —
 *  one tap opens this, second tap on a row navigates to the chat
 *  flow. Mirrors the project's full-screen overlay pattern: invoked
 *  from the parent via early-return so the parent's remember state
 *  survives the round-trip. */
@Composable
internal fun ContinueInChatPickerScreen(
    onPickCurrent: () -> Unit,
    onPickAgentPicker: () -> Unit,
    onPickOnTheFly: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TitleBar(helpTopic = "report_continue_in_chat", title = "Continue in chat", subject = "Send this answer into a new chat", onBackClick = onBack,
            modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Pick how you want to continue in chat:",
                fontSize = 12.sp, color = AppColors.TextTertiary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            ContinueRow(
                icon = "📜",
                title = "with current history and model",
                enabled = true,
                onClick = onPickCurrent
            )
            ContinueRow(
                icon = "🤖",
                title = "with this response only and select an agent",
                enabled = true,
                onClick = onPickAgentPicker
            )
            ContinueRow(
                icon = "🛠️",
                title = "with this response only and configure on the fly",
                enabled = true,
                onClick = onPickOnTheFly
            )
        }
    }
}

@Composable
private fun ContinueRow(icon: String, title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon, fontSize = 20.sp,
            modifier = if (enabled) Modifier else Modifier.alpha(0.4f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else AppColors.TextDim
        )
    }
}
