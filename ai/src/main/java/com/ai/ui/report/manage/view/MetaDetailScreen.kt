package com.ai.ui.report.manage.view
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.RESPONSE_CHANGE_SOURCE_CHAT
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
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.PromptEditReplayState
import com.ai.viewmodel.ReasoningEffortSweepState
import com.ai.viewmodel.TemperatureSweepState
import com.ai.viewmodel.WebSearchReplayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated detail screen for a **plain META** [SecondaryResult] — a
 * meta-prompt run (Compare / Critique / Summarize / Synthesize / …),
 * i.e. `kind == META && fanOutSourceAgentId == null && fanInOf == null`.
 * Fan-out pairs, fan-in rows, rerank, moderation and translate keep using
 * [SecondaryResultDetailScreen].
 *
 * Mirrors the META rendering path of [SecondaryResultDetailScreen]
 * (markdown content + language tabs + trace / delete / copy / share /
 * continue-in-chat / refine-chat / model-info / notes / view) and adds an
 * ✏️ **edit** action: a full-screen "Change response" list (copied from the
 * fan-out pair L3 edit overlay) reworded for a meta result, wired to the
 * per-report [com.ai.viewmodel.MetaEditManager] via [LocalMetaEditManager].
 */
@Composable
internal fun MetaDetailScreen(
    result: SecondaryResult,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    /** Step to the previous / next sibling row in the list this detail
     *  was opened from (null at the edges → edge toast). Wired by the
     *  Manage mount; horizontal swipe + accessibility actions. */
    onPrevSibling: (() -> Unit)? = null,
    onNextSibling: (() -> Unit)? = null,

    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    /** When non-null, suppress the per-screen language picker and lock
     *  content to this language. Same convention as
     *  [SecondaryResultDetailScreen]. */
    forcedLanguage: String? = null,
    /** Delete a specific SecondaryResult by id — used by the multi-language
     *  delete popup's "Active language only" path. */
    onDeleteRowById: (String) -> Unit = { _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val title = result.metaPromptName?.takeIf { it.isNotBlank() }
        ?: com.ai.data.legacyKindDisplayName(result.kind)
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLangChoice by remember { mutableStateOf(false) }

    val metaEditManager = com.ai.ui.shared.LocalMetaEditManager.current
    val aiSettings = com.ai.ui.shared.LocalAiSettings.current

    // TRANSLATE secondaries → the language icon picker for this META row.
    // Keyed on secDataVersion too, not just reportId: the
    // TranslationCompareScreen overlay below deletes TRANSLATE rows in
    // place and returns here — a version-less list kept serving the
    // deleted row's tab and content, and newly finished translations
    // never appeared while mounted (SecondaryResultDetailScreen keys the
    // identical load this way).
    val secDataVersion by com.ai.data.SecondaryDataVersion.versionFor(result.reportId).collectAsState()
    val translatesState = produceState(initialValue = emptyList<SecondaryResult>(), result.reportId, secDataVersion) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, result.reportId, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
        }
    }
    val translates = translatesState.value
    val reportDataVersion by com.ai.data.ReportDataVersion.versionFor(result.reportId).collectAsState()
    val parentReportState = produceState<com.ai.data.Report?>(initialValue = null, result.reportId, reportDataVersion) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, result.reportId) }
    }
    val parentReport = parentReportState.value
    val reportLanguageName = parentReport?.languageName?.takeIf { it.isNotBlank() }
    // Fresh on-disk row, re-read on every secondary save, so a refine /
    // edit Apply (which rewrites content) reflects here even though
    // `result` arrives stale from the list mount.
    val resultFresh by produceState<SecondaryResult?>(null, result.id, secDataVersion) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.get(context, result.reportId, result.id) }
    }
    val originalContent = resultFresh?.content ?: result.content
    // Provider/model from the fresh on-disk row so a "Switch model / agent"
    // reflects everywhere without a remount — the title bar's Model Info,
    // the refine-in-chat call, and the delete dialog all read this instead
    // of the stale snapshot. Mirrors ModerationDetailScreen/RerankDetailScreen.
    val eff = resultFresh ?: result
    val providerService = AppService.findById(eff.providerId)
    val provider = providerService?.id ?: eff.providerId

    // Find the trace file for this meta call: same report, closest timestamp,
    // matching the FRESH model (eff) and re-keyed on secDataVersion so 🐞
    // follows a "Switch model / agent" — keying on the stale result.model kept
    // opening the pre-switch model's trace. May be null when tracing was off.
    val traceFilenameState = produceState<String?>(initialValue = null, result.id, secDataVersion, eff.model) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == eff.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - eff.timestamp) }?.filename
        }
    }
    val baseTraceFilename = traceFilenameState.value

    val langTabs = remember(translates, result.id, result.targetLanguage, reportLanguageName) {
        val mineTranslates = translates.filter {
            it.translateSourceKind == "META" && it.translateSourceTargetId == result.id
        }
        val seedTab: SecondaryResult? = result.targetLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
            result.copy(
                kind = SecondaryKind.TRANSLATE,
                targetLanguage = lang,
                targetLanguageNative = result.targetLanguageNative ?: lang
            )
        }
        val combined = if (seedTab != null) mineTranslates + seedTab else mineTranslates
        val hasOriginalContent = result.targetLanguage.isNullOrBlank() ||
            (reportLanguageName != null && mineTranslates.any { it.targetLanguage == reportLanguageName })
        buildLangTabs(
            combined,
            includeOriginal = hasOriginalContent,
            originalAlias = reportLanguageName
        )
    }
    var pickerLangKey by rememberSaveable(result.id) {
        val target = result.targetLanguage
        mutableStateOf(
            if (target.isNullOrBlank()) LangTab.ORIGINAL_KEY
            else target.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
        )
    }
    LaunchedEffect(langTabs, forcedLanguage) {
        if (forcedLanguage == null && langTabs.none { it.key == pickerLangKey }) {
            pickerLangKey = LangTab.ORIGINAL_KEY
        }
    }
    val selectedLangKey: String = if (forcedLanguage != null) {
        if (forcedLanguage.isEmpty()) LangTab.ORIGINAL_KEY
        else forcedLanguage.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
    } else pickerLangKey
    val activeLangName: String? = when {
        selectedLangKey == LangTab.ORIGINAL_KEY -> null
        forcedLanguage != null && forcedLanguage.isNotEmpty() -> forcedLanguage
        else -> langTabs.firstOrNull { it.key == selectedLangKey }?.displayName
    }
    val activeTranslateRow: SecondaryResult? = remember(translates, selectedLangKey, result.id, activeLangName, reportLanguageName) {
        if (activeLangName == result.targetLanguage) null
        else if (activeLangName == null) {
            if (reportLanguageName == null) null
            else translates.firstOrNull {
                it.translateSourceKind == "META" &&
                    it.translateSourceTargetId == result.id &&
                    it.targetLanguage == reportLanguageName
            }
        } else translates.firstOrNull {
            it.translateSourceKind == "META" &&
                it.translateSourceTargetId == result.id &&
                it.targetLanguage == activeLangName
        }
    }
    val displayContent: String? = when {
        activeLangName == result.targetLanguage -> originalContent
        else -> activeTranslateRow?.content
    }
    val traceFilename = activeTranslateRow?.traceFile?.takeIf { it.isNotBlank() } ?: baseTraceFilename

    // "Changed by <source>: <value>" badge — surfaces when an edit
    // (Reload / Chat / sweep / replay) rewrote this row.
    val responseChangeLabel = (resultFresh?.responseChangeSource ?: result.responseChangeSource)
        ?.takeIf { it.isNotBlank() }
        ?.let { source ->
            (resultFresh?.responseChangeValue ?: result.responseChangeValue)
                ?.takeIf { it.isNotBlank() }
                ?.let { value -> "Changed by $source: $value" }
                ?: "Changed by $source"
        }

    // New-style translation compare overlay (title-bar ↔ icon).
    var showLiveTranslationCompare by remember { mutableStateOf(false) }
    val liveTranslateActive = activeTranslateRow
    if (showLiveTranslationCompare && liveTranslateActive != null && !result.content.isNullOrBlank() && !liveTranslateActive.content.isNullOrBlank()) {
        val sourceLangLabel = result.targetLanguage?.takeIf { it.isNotBlank() } ?: "Original"
        val translatedLangLabel = liveTranslateActive.targetLanguage?.takeIf { it.isNotBlank() } ?: activeLangName ?: "Translation"
        val tf = liveTranslateActive.traceFile
        val sourceIcon = result.targetLanguage?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
            ?: parentReport?.languageIcon
        val translatedIcon = liveTranslateActive.targetLanguage?.takeIf { it.isNotBlank() }
            ?.let { com.ai.data.InternalPromptIconCache.get("translation_icon", it) }
        TranslationCompareScreen(
            title = "Translation — $title",
            originalLabel = sourceLangLabel,
            originalContent = result.content,
            translatedLabel = translatedLangLabel,
            translatedContent = liveTranslateActive.content,
            onBack = { showLiveTranslationCompare = false },
            onNavigateHome = onNavigateHome,
            onTrace = tf?.let { fn -> { onNavigateToTraceFile(fn) } },
            onDelete = {
                onDeleteRowById(liveTranslateActive.id)
                showLiveTranslationCompare = false
            },
            originalIcon = sourceIcon,
            translatedIcon = translatedIcon
        )
        return
    }

    // ✍️ user notes for this meta row.
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(result.reportId, "SECONDARY", result.id, noteEdit!!) { noteEdit = null }
        return
    }
    val noteDataVersion by ReportDataVersion.versionFor(result.reportId).collectAsState()
    val secondaryNotes by produceState(emptyList<UserNote>(), result.reportId, result.id, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, result.reportId)?.notesFor("SECONDARY", result.id) ?: emptyList()
        }
    }

    val hasContent = !originalContent.isNullOrBlank()
    val continueMetaInChat = com.ai.ui.shared.LocalContinueMetaInChat.current
    // 💬 offers the same three continue-in-chat modes a primary answer has:
    // the meta's own model (chat replay), pick an agent, or configure on
    // the fly — the latter two stash the displayed content as the next
    // chat's starter text.
    val continueTextInChat = com.ai.ui.shared.LocalContinueTextInChat.current
    var showContinuePicker by remember { mutableStateOf(false) }
    if (showContinuePicker) {
        ContinueInChatPickerScreen(
            onPickCurrent = {
                showContinuePicker = false
                continueMetaInChat(result.reportId, result.id, activeLangName)
            },
            onPickAgentPicker = {
                showContinuePicker = false
                displayContent?.takeIf { it.isNotBlank() }?.let { continueTextInChat?.invoke(it, "agent") }
            },
            onPickOnTheFly = {
                showContinuePicker = false
                displayContent?.takeIf { it.isNotBlank() }?.let { continueTextInChat?.invoke(it, "fly") }
            },
            onBack = { showContinuePicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // ----- edit overlays (mirror FanOutL3's Change-response set) -----
    // Fallback empty flows keep the collectAsState calls unconditional when
    // the manager local isn't provided (MetaDetailScreen mounted off the
    // Manage path); the ✏️ edit icon is hidden in that case anyway.
    val emptyTempFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, TemperatureSweepState>()) }
    val emptyReasonFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, ReasoningEffortSweepState>()) }
    val emptyWebFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, WebSearchReplayState>()) }
    val emptyPromptFlow = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, PromptEditReplayState>()) }
    val temperatureSweepStates by (metaEditManager?.temperatureSweepStates ?: emptyTempFlow).collectAsState()
    val reasoningEffortSweepStates by (metaEditManager?.reasoningEffortSweepStates ?: emptyReasonFlow).collectAsState()
    val webSearchReplayStates by (metaEditManager?.webSearchReplayStates ?: emptyWebFlow).collectAsState()
    val promptEditReplayStates by (metaEditManager?.promptEditReplayStates ?: emptyPromptFlow).collectAsState()
    val temperatureSweepKey = TemperatureSweepState.key(result.reportId, result.id)
    val reasoningEffortSweepKey = ReasoningEffortSweepState.key(result.reportId, result.id)
    val webSearchReplayKey = WebSearchReplayState.key(result.reportId, result.id)
    val promptEditReplayKey = PromptEditReplayState.key(result.reportId, result.id)
    var showResponseChangeActions by remember { mutableStateOf(false) }
    var showTemperatureSweep by remember { mutableStateOf(false) }
    var showReasoningEffortSweep by remember { mutableStateOf(false) }
    var showWebSearchReplay by remember { mutableStateOf(false) }
    var showPromptEditReplay by remember { mutableStateOf(false) }
    var showAgentChat by remember { mutableStateOf(false) }
    var showModelSwitchPick by remember { mutableStateOf(false) }
    val modelSwitch = com.ai.ui.shared.LocalSecondaryModelSwitch.current
    val modelSwitchStates by (modelSwitch?.states ?: emptyModelSwitchStatesFlow).collectAsState()
    val modelSwitchState = modelSwitchStates[com.ai.viewmodel.ModelSwitchState.key(result.reportId, result.id)]
    val metaModelLabel = com.ai.ui.shared.modelLabel(provider, eff.model, separator = " / ")
    val resolvedMetaPrompt by produceState<String?>(initialValue = null, result.reportId, result.id, secDataVersion) {
        value = metaEditManager?.resolveMetaPrompt(context, result.reportId, result.id)
    }

    if (showResponseChangeActions && metaEditManager != null) {
        val canRunVariation = providerService != null
        ResponseChangeActionsScreen(
            title = "Change result",
            subject = metaModelLabel,
            actions = listOf(
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.reload,
                    title = "Reload",
                    description = "Regenerate this result with its saved prompt, model and settings.",
                    onClick = {
                        showResponseChangeActions = false
                        metaEditManager.regenerateMeta(context, result.reportId, result.id)
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.edit,
                    title = "Edit prompt",
                    description = "Edit the meta prompt only for this replay (optionally change parameters and system prompt).",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showPromptEditReplay = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.agentChat,
                    title = "Chat",
                    description = "Refine this result in a chat and apply a chosen assistant reply.",
                    enabled = providerService != null && hasContent,
                    onClick = {
                        showResponseChangeActions = false
                        showAgentChat = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.parameters,
                    title = "Temperature sweep",
                    description = "Run one to three temperature variants and select the best result.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showTemperatureSweep = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.reportModelIcon,
                    title = "Reasoning Effort",
                    description = "Compare reasoning-effort levels for this result when supported.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showReasoningEffortSweep = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.webSearchReplay,
                    title = "Web search",
                    description = "Re-run this result once with web search enabled and apply it.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showWebSearchReplay = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.reportModelIcon,
                    title = "Switch model / agent",
                    description = "Re-run this result against another model or agent, then keep or discard it.",
                    enabled = modelSwitch != null,
                    onClick = {
                        showResponseChangeActions = false
                        showModelSwitchPick = true
                    }
                )
            ),
            onBack = { showResponseChangeActions = false }
        )
        return
    }
    if (showModelSwitchPick && modelSwitch != null) {
        SecondaryModelSwitchPickScreen(
            aiSettings = aiSettings,
            rowParamsIds = result.secondaryParameterPresetIds.orEmpty(),
            rowSystemPromptId = result.secondarySystemPromptId,
            onPicked = { sel -> showModelSwitchPick = false; modelSwitch.startModelSwitch(context, result.reportId, result.id, sel) },
            onBack = { showModelSwitchPick = false },
            onNavigateHome = onNavigateHome
        )
        return
    }
    if (modelSwitch != null && modelSwitchState != null) {
        SecondaryModelSwitchPreviewScreen(
            state = modelSwitchState,
            onUse = { modelSwitch.applyModelSwitch(context, result.reportId, result.id) },
            onDiscard = { modelSwitch.clear(result.reportId, result.id) },
            onTrace = onNavigateToTraceFile,
            onBack = { modelSwitch.clear(result.reportId, result.id) }
        ) { content ->
            ContentWithThinkSections(analysis = content)
        }
        return
    }
    if (showPromptEditReplay && metaEditManager != null) {
        PromptEditReplayScreen(
            reportId = result.reportId,
            targetId = result.id,
            title = "Edit prompt replay",
            modelLabel = metaModelLabel,
            initialPrompt = resolvedMetaPrompt ?: "",
            state = promptEditReplayStates[promptEditReplayKey],
            aiSettings = aiSettings,
            onCallModel = { prompt, paramsIds, systemPromptId ->
                metaEditManager.startPromptEditReplay(context, result.reportId, result.id, prompt, paramsIds, systemPromptId)
            },
            onUseResponse = {
                metaEditManager.applyPromptEditReplay(context, result.reportId, result.id)
                metaEditManager.clearPromptEditReplay(result.reportId, result.id)
                showPromptEditReplay = false
            },
            onTrace = onNavigateToTraceFile,
            onBack = {
                metaEditManager.clearPromptEditReplay(result.reportId, result.id)
                showPromptEditReplay = false
            }
        )
        return
    }
    if (showTemperatureSweep && metaEditManager != null) {
        TemperatureSweepScreen(
            reportId = result.reportId,
            agentId = result.id,
            modelLabel = metaModelLabel,
            temperatureRange = providerService?.let(::temperatureRangeForProvider) ?: TemperatureRange.Default,
            state = temperatureSweepStates[temperatureSweepKey],
            onSubmit = { temps -> metaEditManager.startTemperatureSweep(context, result.reportId, result.id, temps) },
            onUseCandidate = { index ->
                // apply → clear → close, matching the prompt-edit-replay
                // handler above and the agent-side SingleResult. Applying
                // alone dropped the sweep track key while the screen stayed
                // mounted, so its optimistic fallback resurrected a synthetic
                // isRunning=true state → an infinite spinner.
                metaEditManager.applyTemperatureCandidate(context, result.reportId, result.id, index)
                metaEditManager.clearTemperatureSweep(result.reportId, result.id)
                showTemperatureSweep = false
            },
            onTrace = onNavigateToTraceFile,
            onBack = {
                metaEditManager.clearTemperatureSweep(result.reportId, result.id)
                showTemperatureSweep = false
            }
        )
        return
    }
    if (showReasoningEffortSweep && metaEditManager != null) {
        ReasoningEffortSweepScreen(
            reportId = result.reportId,
            agentId = result.id,
            modelLabel = metaModelLabel,
            state = reasoningEffortSweepStates[reasoningEffortSweepKey],
            onSubmit = { efforts -> metaEditManager.startReasoningEffortSweep(context, result.reportId, result.id, efforts) },
            onUseCandidate = { index ->
                // apply → clear → close (see the temperature handler).
                metaEditManager.applyReasoningEffortCandidate(context, result.reportId, result.id, index)
                metaEditManager.clearReasoningEffortSweep(result.reportId, result.id)
                showReasoningEffortSweep = false
            },
            onTrace = onNavigateToTraceFile,
            onBack = {
                metaEditManager.clearReasoningEffortSweep(result.reportId, result.id)
                showReasoningEffortSweep = false
            }
        )
        return
    }
    if (showWebSearchReplay && metaEditManager != null) {
        WebSearchReplayScreen(
            reportId = result.reportId,
            agentId = result.id,
            modelLabel = metaModelLabel,
            originalResponse = originalContent,
            state = webSearchReplayStates[webSearchReplayKey],
            onStart = { metaEditManager.startWebSearchReplay(context, result.reportId, result.id) },
            onUseResponse = {
                // apply → clear → close (see the temperature handler).
                metaEditManager.applyWebSearchReplay(context, result.reportId, result.id)
                metaEditManager.clearWebSearchReplay(result.reportId, result.id)
                showWebSearchReplay = false
            },
            onTrace = onNavigateToTraceFile,
            onBack = {
                metaEditManager.clearWebSearchReplay(result.reportId, result.id)
                showWebSearchReplay = false
            }
        )
        return
    }
    if (showAgentChat && providerService != null) {
        val seed = buildList {
            add(com.ai.data.ChatMessage(role = "user", content = parentReport?.prompt?.takeIf { it.isNotBlank() } ?: "Analyse the model responses."))
            originalContent?.takeIf { it.isNotBlank() }
                ?.let { com.ai.data.stripMetaReferenceLegend(it) }
                ?.let { add(com.ai.data.ChatMessage(role = "assistant", content = it)) }
        }
        AgentChatScreen(
            titleBarSubject = title,
            service = providerService,
            model = eff.model,
            agentIdForKey = null,
            initialMessages = (resultFresh?.chatMessages ?: result.chatMessages).ifEmpty { seed },
            initialParams = com.ai.data.ChatParameters(),
            aiSettings = aiSettings,
            onSaveMessages = { SecondaryResultStorage.updateChatMessages(context, result.reportId, result.id, it) },
            onApply = {
                if (metaEditManager != null) {
                    metaEditManager.applyMetaContent(context, result.reportId, result.id, it, RESPONSE_CHANGE_SOURCE_CHAT)
                } else {
                    SecondaryResultStorage.updateContent(context, result.reportId, result.id, it)
                }
            },
            onBack = { showAgentChat = false }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)
        // Swipe left/right (or the matching accessibility actions) steps
        // through the sibling secondary rows without backing out.
        .let { m ->
            if (onPrevSibling == null && onNextSibling == null) m
            else m.horizontalSwipeNavigation(
                key1 = result.id,
                atFirst = onPrevSibling == null,
                atLast = onNextSibling == null,
                onSwipeLeft = { onNextSibling?.invoke() },
                onSwipeRight = { onPrevSibling?.invoke() }
            )
        }
    ) {
        val traceEnabled = ApiTracer.ladybugLinksEnabled && traceFilename != null
        // 👁 → Meta / FanIn View sub-screen.
        val pendingViewHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingViewHolder?.let { holder ->
            {
                holder.value = when {
                    result.fanInOf != null -> com.ai.ui.shared.ViewJump.FanIn(result.id)
                    else -> com.ai.ui.shared.ViewJump.Meta(result.id)
                }
            }
        }
        TitleBar(
            helpTopic = "meta_detail",
            title = "Meta detail",
            reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon,
            subject = title,
            onBackClick = onBack,
            onEdit = if (metaEditManager != null) { { showResponseChangeActions = true } } else null,
            onTrace = if (traceEnabled) { { onNavigateToTraceFile(traceFilename) } } else null,
            onDelete = {
                if (langTabs.size > 1) confirmLangChoice = true
                else confirmDelete = true
            },
            onOpenView = onOpenViewJump,
            onInfo = if (providerService != null) { { onNavigateToModelInfo(providerService, eff.model) } } else null,
            // 🗣️ refine-in-place lives under ✏️ → Chat now; the title bar
            // keeps only 💬 continue-in-chat (separate Chat-section flow).
            onChat = if (hasContent) {
                {
                    if (continueTextInChat != null) showContinuePicker = true
                    else continueMetaInChat(result.reportId, result.id, activeLangName)
                }
            } else null,
            onTranslationCompare = if (liveTranslateActive != null && !result.content.isNullOrBlank() && !liveTranslateActive.content.isNullOrBlank()) {
                { showLiveTranslationCompare = true }
            } else null,
            onCopy = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "meta result") }
            },
            onShare = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.shareText(context, body, title) }
            },
            onAddNote = { noteEdit = NoteEdit.Add }
        )
        UserNotesSection(
            reportId = result.reportId,
            notes = secondaryNotes,
            onEdit = { noteEdit = NoteEdit.Edit(it.id, it.text) }
        )
        if (langTabs.size > 1 && forcedLanguage == null) {
            LanguagePickerRow(
                langTabs, selectedLangKey,
                onSelect = { pickerLangKey = it },
                useIcons = true,
                originalIcon = parentReport?.languageIcon
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.ai.ui.shared.shortModelName(eff.model), fontSize = 13.sp, color = AppColors.InfoAccent,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        responseChangeLabel?.let { label ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                color = AppColors.CautionAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(AppColors.CardBackgroundAlt)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            InternalPromptCard(result, aiSettings)
            // Read the error from the fresh row so a model switch (which clears it) stops hiding the replaced result.
            val freshError = (resultFresh ?: result).errorMessage
            when {
                freshError != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.DangerAccent, fontWeight = FontWeight.SemiBold)
                    Text(freshError, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                displayContent.isNullOrBlank() -> {
                    val msg = if (activeLangName != null && activeLangName != result.targetLanguage)
                        "(no translation for this language yet)"
                    else "(no content)"
                    Text(msg, color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                else -> {
                    ContentWithThinkSections(analysis = displayContent)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = { Text(com.ai.ui.shared.modelLabel(provider, eff.model)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }

    if (confirmLangChoice) {
        val activeLabel = activeLangName ?: "Original"
        AlertDialog(
            onDismissRequest = { confirmLangChoice = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = {
                Text("Active language: $activeLabel.\n\n" +
                    "\"Active language only\" drops just this language's content. " +
                    "\"All languages\" removes the source and every translation.")
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        confirmLangChoice = false
                        val tr = activeTranslateRow
                        if (tr != null) {
                            onDeleteRowById(tr.id)
                            onBack()
                        } else {
                            onDelete()
                        }
                    }) {
                        Text("Active language only", color = AppColors.DangerAccent, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = {
                        confirmLangChoice = false
                        onDelete()
                    }) {
                        Text("All languages", color = AppColors.DangerAccent, maxLines = 1, softWrap = false)
                    }
                    TextButton(onClick = { confirmLangChoice = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            }
        )
    }
}
