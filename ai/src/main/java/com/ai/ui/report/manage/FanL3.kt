package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.FanOutRunState
import com.ai.data.PairState
import com.ai.data.PairStatus
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStorage
import com.ai.data.RESPONSE_CHANGE_SOURCE_CHAT
import com.ai.data.SecondaryResultStorage
import com.ai.data.TemperatureRange
import com.ai.data.UserNote
import com.ai.data.notesFor
import com.ai.data.toPairState
import com.ai.data.temperatureRangeForProvider
import com.ai.ui.report.manage.view.ReasoningEffortSweepScreen
import com.ai.ui.report.manage.view.ResponseChangeAction
import com.ai.ui.report.manage.view.ResponseChangeActionsScreen
import com.ai.ui.report.manage.view.TemperatureSweepScreen
import com.ai.ui.report.manage.view.WebSearchReplayScreen
import com.ai.ui.report.manage.view.PromptEditReplayScreen
import androidx.compose.runtime.collectAsState
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelLabel
import com.ai.ui.shared.shortModelName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.clickable
import androidx.compose.runtime.produceState
import com.ai.viewmodel.PromptEditReplayState
import com.ai.viewmodel.ReasoningEffortSweepState
import com.ai.viewmodel.TemperatureSweepState
import com.ai.viewmodel.WebSearchReplayState
import kotlinx.coroutines.withContext

/**
 * L3: single pair detail. Source / answerer side-by-side.
 * Reload + delete icons in the title bar; prev/next arrows step
 * through the same L2-scoped row list.
 */
@Composable
internal fun FanOutL3Screen(
    engine: FanOutEngine,
    run: FanOutRunState,
    answererKey: String,
    sourceAgentId: String,
    role: String,
    /** Exact pair row to show. Null only on a restored pre-field nav
     *  save — the agent-id resolution below then applies (ambiguous for
     *  duplicate-model answerers, but better than nothing). */
    pairId: String? = null,
    actions: FanOutActions,
    onStepSource: (String, String?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val (activePid, activeMdl) = answererKey.split("|", limit = 2).let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }

    // The pair we're viewing. For Responder mode: active model is
    // answerer, sourceAgentId identifies the source. For Initiator
    // mode the L2 row's "other" agent is the answerer, and this
    // sourceAgentId is actually the answererAgentId in our model.
    val pair = remember(run, answererKey, sourceAgentId, role, pairId) {
        // Exact id first — the agent-id fallbacks below are ambiguous in
        // Initiator mode when the same model answers as two agents (both
        // pairs share answererAgentId, so the second was unreachable).
        pairId?.let { pid -> run.pairs.values.firstOrNull { it.id == pid } }
            ?: if (role == "Responder") {
            run.pairs.values.firstOrNull {
                "${it.providerId}|${it.model}" == answererKey && it.sourceAgentId == sourceAgentId
            }
        } else {
            // Initiator: active is source. We display the pair where
            // the OTHER agent is answerer. sourceAgentId here is
            // actually the answerer's agent id (named for symmetry
            // with Responder mode).
            run.pairs.values.firstOrNull {
                it.answererAgentId == sourceAgentId &&
                    run.pairs.values.any { other ->
                        other.answererAgentId == it.sourceAgentId &&
                            "${other.providerId}|${other.model}" == answererKey
                    }
            }
        }
    }

    // Load source body from the report's agent list, lazily.
    val report by produceState<com.ai.data.Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val sourceBody = remember(report, pair) {
        val srcId = pair?.sourceAgentId ?: return@remember null
        report?.agents?.firstOrNull { it.agentId == srcId }?.responseBody
    }

    if (pair == null) {
        Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            val pendingHolderEmpty = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewEmptyJump: (() -> Unit)? = pendingHolderEmpty?.let { holder ->
                {
                    holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                        ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                        ?: com.ai.ui.shared.ViewJump.Main
                }
            }
            TitleBar(
                helpTopic = "secondary_fan_out_l3",
                title = "Fan out - pair",
                subject = "This pair no longer exists",
                onOpenView = onOpenViewEmptyJump,
                onBackClick = onBack
            )
            Text("Pair no longer exists.", color = AppColors.TextTertiary)
        }
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }

    // L2 scope for prev/next stepping — must match L2's VISIBLE order
    // (label, not timestamp), or Prev/Next jumps to a seemingly random
    // pair that isn't the adjacent row in the list.
    val l3AgentLabels: Map<String, String> = remember(report) {
        report?.agents?.associate { it.agentId to resolveModelLabel("${it.provider}|${it.model}") }
            ?: emptyMap()
    }
    fun l2RowLabel(p: PairState): String = if (role == "Responder") {
        l3AgentLabels[p.sourceAgentId] ?: p.sourceAgentId
    } else {
        resolveModelLabel("${p.providerId}|${p.model}")
    }
    val l2Rows = remember(run, answererKey, role, l3AgentLabels) {
        when (role) {
            "Initiator" -> run.pairs.values.filter {
                run.pairs.values.any { other ->
                    other.answererAgentId == it.sourceAgentId &&
                        "${other.providerId}|${other.model}" == answererKey
                }
            }
            else -> run.pairs.values.filter { "${it.providerId}|${it.model}" == answererKey }
        }.sortedWith(compareBy { p -> l2RowLabel(p).lowercase() })
    }
    val curIdx = l2Rows.indexOfFirst { it.key == pair.key }
    val prev = if (curIdx > 0) l2Rows[curIdx - 1] else null
    val next = if (curIdx in 0 until l2Rows.size - 1) l2Rows[curIdx + 1] else null

    // "The other model" in the pair — show only the model name, no
    // provider. The green subject already names the model this screen
    // is about; the source pane just needs the counterpart's model.
    val sourceLabel = remember(report, pair.sourceAgentId) {
        report?.agents?.firstOrNull { it.agentId == pair.sourceAgentId }
            ?.model
            ?: pair.sourceAgentId
    }
    val sourceAgent = remember(report, pair.sourceAgentId) {
        report?.agents?.firstOrNull { it.agentId == pair.sourceAgentId }
    }
    val sourceProviderService = remember(sourceAgent) {
        sourceAgent?.provider?.let { AppService.findById(it) }
    }
    val answererLabel = modelLabel(pair.providerId, pair.model)
    val answererProviderService = remember(pair.providerId) {
        AppService.findById(pair.providerId)
    }

    // ✍️ user notes for this fan-out response (the pair = one SecondaryResult).
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(run.reportId, "SECONDARY", pair.id, noteEdit!!) { noteEdit = null }
        return
    }
    val noteDataVersion by ReportDataVersion.versionFor(run.reportId).collectAsState()
    val pairNotes by produceState(emptyList<UserNote>(), run.reportId, pair.id, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, run.reportId)?.notesFor("SECONDARY", pair.id) ?: emptyList()
        }
    }

    // 🗣️ refine-in-chat overlay for this fan-out pair's answer.
    val aiSettings = com.ai.ui.shared.LocalAiSettings.current
    val secDataVersion by com.ai.data.SecondaryDataVersion.versionFor(run.reportId, com.ai.data.SecondaryKind.META).collectAsState()
    // Fresh on-disk row (re-read on every secondary save) so the 🗣️ Apply
    // — which rewrites content — reflects here even though `pair` comes
    // from the in-memory run snapshot. Drives both the displayed content
    // and the refine-chat's persisted conversation.
    val pairFresh by produceState<com.ai.data.SecondaryResult?>(null, pair.id, secDataVersion) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.get(context, run.reportId, pair.id) }
    }
    val pairBody = pairFresh?.content ?: pair.content
    val responseChangeLabel = (pairFresh?.responseChangeSource ?: pair.responseChangeSource)
        ?.takeIf { it.isNotBlank() }
        ?.let { source ->
            (pairFresh?.responseChangeValue ?: pair.responseChangeValue)
                ?.takeIf { it.isNotBlank() }
                ?.let { value -> "Changed by $source: $value" }
                ?: "Changed by $source"
        }
    val canChangePairResponse = answererProviderService != null && !pairBody.isNullOrBlank()
    val temperatureSweepStates by engine.temperatureSweepStates.collectAsState()
    val reasoningEffortSweepStates by engine.reasoningEffortSweepStates.collectAsState()
    val webSearchReplayStates by engine.webSearchReplayStates.collectAsState()
    val promptEditReplayStates by engine.promptEditReplayStates.collectAsState()
    val temperatureSweepKey = TemperatureSweepState.key(run.reportId, pair.id)
    val reasoningEffortSweepKey = ReasoningEffortSweepState.key(run.reportId, pair.id)
    val webSearchReplayKey = WebSearchReplayState.key(run.reportId, pair.id)
    val promptEditReplayKey = PromptEditReplayState.key(run.reportId, pair.id)
    var showTemperatureSweep by remember { mutableStateOf(false) }
    var showReasoningEffortSweep by remember { mutableStateOf(false) }
    var showWebSearchReplay by remember { mutableStateOf(false) }
    var showPromptEditReplay by remember { mutableStateOf(false) }
    var showResponseChangeActions by remember { mutableStateOf(false) }
    var showAgentChat by remember { mutableStateOf(false) }
    val resolvedFanOutPrompt by produceState<String?>(initialValue = null, run.key, pair.id, secDataVersion) {
        value = engine.resolveFanOutPairPrompt(context, run.key, pair.id)
    }
    if (showResponseChangeActions) {
        val canRunVariation = answererProviderService != null
        ResponseChangeActionsScreen(
            title = "Change response",
            subject = answererLabel,
            actions = listOf(
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.reload,
                    title = "Reload",
                    description = "Rerun this fan-out pair with the saved fan-out prompt and settings.",
                    onClick = {
                        showResponseChangeActions = false
                        actions.onRerunPair(run.key, pair.key)
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.edit,
                    title = "Edit prompt",
                    description = "Edit the resolved fan-out prompt only for this pair replay.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showPromptEditReplay = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.agentChat,
                    title = "Chat",
                    description = "Refine the pair response in a chat and apply a chosen assistant reply.",
                    enabled = canChangePairResponse,
                    onClick = {
                        showResponseChangeActions = false
                        showAgentChat = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.parameters,
                    title = "Temperature sweep",
                    description = "Run one to three temperature variants and select the best pair response.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showTemperatureSweep = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.reportModelIcon,
                    title = "Reasoning Effort",
                    description = "Compare selected reasoning-effort levels for this pair when supported.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showReasoningEffortSweep = true
                    }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.webSearchReplay,
                    title = "Web search",
                    description = "Replay this pair once with web search enabled and apply the result.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showWebSearchReplay = true
                    }
                )
            ),
            onBack = { showResponseChangeActions = false }
        )
        return
    }
    if (showPromptEditReplay) {
        PromptEditReplayScreen(
            reportId = run.reportId,
            targetId = pair.id,
            title = "Edit prompt replay",
            modelLabel = answererLabel,
            initialPrompt = resolvedFanOutPrompt ?: run.metaPrompt.text.replace("@RESPONSE@", sourceBody ?: ""),
            state = promptEditReplayStates[promptEditReplayKey],
            aiSettings = aiSettings,
            onCallModel = { prompt, paramsIds, systemPromptId ->
                engine.startFanOutPromptEditReplay(context, run.key, pair.id, prompt, paramsIds, systemPromptId)
            },
            onUseResponse = {
                engine.applyFanOutPromptEditReplay(context, run.key, pair.id)
                engine.clearFanOutPromptEditReplay(run.reportId, pair.id)
                showPromptEditReplay = false
            },
            onTrace = actions.onNavigateToTraceFile,
            onBack = {
                engine.clearFanOutPromptEditReplay(run.reportId, pair.id)
                showPromptEditReplay = false
            }
        )
        return
    }
    if (showTemperatureSweep) {
        TemperatureSweepScreen(
            reportId = run.reportId,
            agentId = pair.id,
            modelLabel = answererLabel,
            temperatureRange = answererProviderService?.let(::temperatureRangeForProvider) ?: TemperatureRange.Default,
            state = temperatureSweepStates[temperatureSweepKey],
            onSubmit = { temps -> engine.startFanOutTemperatureSweep(context, run.key, pair.id, temps) },
            onUseCandidate = { index -> engine.applyFanOutTemperatureCandidate(context, run.key, pair.id, index) },
            onTrace = actions.onNavigateToTraceFile,
            onBack = {
                engine.clearFanOutTemperatureSweep(run.reportId, pair.id)
                showTemperatureSweep = false
            }
        )
        return
    }
    if (showReasoningEffortSweep) {
        ReasoningEffortSweepScreen(
            reportId = run.reportId,
            agentId = pair.id,
            modelLabel = answererLabel,
            state = reasoningEffortSweepStates[reasoningEffortSweepKey],
            onSubmit = { efforts -> engine.startFanOutReasoningEffortSweep(context, run.key, pair.id, efforts) },
            onUseCandidate = { index -> engine.applyFanOutReasoningEffortCandidate(context, run.key, pair.id, index) },
            onTrace = actions.onNavigateToTraceFile,
            onBack = {
                engine.clearFanOutReasoningEffortSweep(run.reportId, pair.id)
                showReasoningEffortSweep = false
            }
        )
        return
    }
    if (showWebSearchReplay) {
        WebSearchReplayScreen(
            reportId = run.reportId,
            agentId = pair.id,
            modelLabel = answererLabel,
            originalResponse = pairBody,
            state = webSearchReplayStates[webSearchReplayKey],
            onStart = { engine.startFanOutWebSearchReplay(context, run.key, pair.id) },
            onUseResponse = { engine.applyFanOutWebSearchReplay(context, run.key, pair.id) },
            onTrace = actions.onNavigateToTraceFile,
            onBack = {
                engine.clearFanOutWebSearchReplay(run.reportId, pair.id)
                showWebSearchReplay = false
            }
        )
        return
    }
    if (showAgentChat) {
        answererProviderService?.let { svc ->
            val seed = buildList {
                add(com.ai.data.ChatMessage(role = "user", content = run.metaPrompt.text.replace("@RESPONSE@", sourceBody ?: "")))
                pairBody?.takeIf { it.isNotBlank() }?.let { add(com.ai.data.ChatMessage(role = "assistant", content = it)) }
            }
            AgentChatScreen(
                titleBarSubject = answererLabel,
                service = svc,
                model = pair.model,
                agentIdForKey = null,
                initialMessages = (pairFresh?.chatMessages ?: emptyList()).ifEmpty { seed },
                initialParams = com.ai.data.ChatParameters(),
                aiSettings = aiSettings,
                onSaveMessages = { SecondaryResultStorage.updateChatMessages(context, run.reportId, pair.id, it) },
                onApply = {
                    engine.applyFanOutPairContent(
                        context = context,
                        runKey = run.key,
                        pairId = pair.id,
                        content = it,
                        changeSource = RESPONSE_CHANGE_SOURCE_CHAT
                    )
                },
                onBack = { showAgentChat = false }
            )
            return
        }
    }

    // Use the persisted attempt references. Timestamp/model guesses can open
    // another pair's call (or its later Fan Meta call) under concurrency.
    val answererTrace = pairFresh?.traceFile ?: pairFresh?.tokenUsage?.traceFile
    val sourceTrace = sourceAgent?.traceFile ?: sourceAgent?.tokenUsage?.traceFile

    BoxWithConstraints(Modifier.fillMaxSize().background(AppColors.AppBackground)) {
        val halfMax = maxHeight / 2
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // 👁 → matching View Fan-out for this metaPromptName.
            val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
                {
                    holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                        ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                        ?: com.ai.ui.shared.ViewJump.Main
                }
            }
            TitleBar(
                helpTopic = "secondary_fan_out_l3",
                title = "Fan out - pair",
                subject = answererLabel,
                subjectTrailing = {
                    Text(
                        text = role,
                        fontSize = 13.sp, color = AppColors.TextSecondary,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                onBackClick = onBack,
                onOpenView = onOpenViewJump,
                onInfo = answererProviderService?.let { svc ->
                    { actions.onNavigateToModelInfo(svc, pair.model) }
                },
                onTrace = if (ApiTracer.ladybugLinksEnabled && answererTrace != null) {
                    { actions.onNavigateToTraceFile(answererTrace) }
                } else null,
                onDelete = { confirmDelete = true },
                onAddNote = { noteEdit = NoteEdit.Add },
                onEdit = { showResponseChangeActions = true },
                // 📋/📤 — the pair's response body, mirroring the sibling
                // detail screens (MetaDetail / SingleResult).
                onCopy = (pairFresh?.content ?: pair.content)?.takeIf { it.isNotBlank() }?.let { body ->
                    { com.ai.ui.shared.copyToClipboard(context, body, "fan-out response") }
                },
                onShare = (pairFresh?.content ?: pair.content)?.takeIf { it.isNotBlank() }?.let { body ->
                    { com.ai.ui.shared.shareText(context, body, "Fan-out response — $answererLabel") }
                }
            )
            UserNotesSection(
                reportId = run.reportId,
                notes = pairNotes,
                onEdit = { noteEdit = NoteEdit.Edit(it.id, it.text) }
            )
            // Bespoke row instead of the shared HardcodedSubjectRow —
            // this screen also surfaces the role next to the answerer
            // label. top = 4.dp matches HardcodedSubjectRow so the y-
            // position lines up with every other HARDCODED screen.
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

            // Source pane — header row carries the source's "provider /
            // model" label plus info / trace icons for the source
            // agent's own run (peeking out of the pair view into the
            // upstream call that produced the body shown below). The
            // source agent's per-model report icon (if generated) is
            // shown as a leading emoji.
            // Source = "the prompt this pair received". In Responder
            // mode the source agent is the OTHER model (the
            // counterpart we're exploring), so its info / trace icons
            // live here. In Initiator mode the source agent IS the
            // active model — the user came from its L2 view — so the
            // peek-into-other-model icons move down to the response
            // pane instead (see below).
            val sourcePaneIsOther = role == "Responder"
            Column(Modifier.fillMaxWidth().heightIn(max = halfMax).padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sourceIcon = sourceAgent?.icon
                    if (!sourceIcon.isNullOrBlank()) {
                        Text(
                            sourceIcon, fontSize = 16.sp,
                            modifier = Modifier.background(AppColors.AppBackground)
                                .padding(end = 6.dp)
                        )
                    }
                    Text(
                        sourceLabel, fontSize = 14.sp, color = AppColors.InfoAccent,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (sourcePaneIsOther && sourceProviderService != null && sourceAgent != null) {
                        Text(
                            com.ai.data.MetadataIconsHolder.current.info, fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToModelInfo(sourceProviderService, sourceAgent.model) }
                        )
                    }
                    if (sourcePaneIsOther && ApiTracer.ladybugLinksEnabled && sourceTrace != null) {
                        Text(
                            com.ai.data.MetadataIconsHolder.current.traces, fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToTraceFile(sourceTrace) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val body = sourceBody
                    if (body.isNullOrBlank()) {
                        Text("(source content not found)", color = AppColors.TextTertiary, fontSize = 13.sp)
                    } else {
                        // Markdown + <think> handling like the sibling prose
                        // viewers — raw Text showed literal ** and tags.
                        ContentWithThinkSections(analysis = body)
                    }
                }
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

            // Answerer / response pane. Leading emoji is the icon
            // produced by the fan-out icon chain (fanOutIconGenEnabled).
            // In Initiator mode the answerer IS the OTHER model, so
            // the peek-into-other-model icons (info + trace) live
            // here. In Responder mode they live up on the source pane.
            val responsePaneIsOther = role == "Initiator"
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!pair.icon.isNullOrBlank()) {
                        // Tap → opens the unified Icon-lookup screen
                        // for this pair (6th adapter).
                        Text(
                            pair.icon, fontSize = 16.sp,
                            modifier = Modifier
                                .background(AppColors.AppBackground)
                                .padding(end = 6.dp)
                                .clickable { actions.onOpenPairIconLookup(pair.id) }
                        )
                    }
                    Text(
                        answererLabel, fontSize = 14.sp, color = AppColors.SuccessAccent,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (responsePaneIsOther && answererProviderService != null) {
                        Text(
                            com.ai.data.MetadataIconsHolder.current.info, fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToModelInfo(answererProviderService, pair.model) }
                        )
                    }
                    // Keep the result's own trace reachable in either role,
                    // including when the customizable title-bar action is hidden.
                    if (ApiTracer.ladybugLinksEnabled && answererTrace != null) {
                        Text(
                            com.ai.data.MetadataIconsHolder.current.traces, fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToTraceFile(answererTrace) }
                        )
                    }
                    if (pair.responseCost > 0.0) {
                        Text(
                            "${formatCents(pair.responseCost)}", fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                responseChangeLabel?.let { label ->
                    Text(
                        text = label,
                        color = AppColors.CautionAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(AppColors.CardBackgroundAlt)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when (pair.status) {
                        PairStatus.ERROR -> Text(
                            "${com.ai.data.MetadataIconsHolder.current.statusFailed} ${pair.errorMessage}",
                            color = AppColors.DangerAccent, fontSize = 13.sp
                        )
                        PairStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedHourglass(fontSize = 16.sp)
                            Text("  Running…", color = AppColors.WarningAccent, fontSize = 13.sp)
                        }
                        PairStatus.PENDING -> Text(
                            "${com.ai.data.MetadataIconsHolder.current.clockQueued} Queued",
                            color = AppColors.TextTertiary, fontSize = 13.sp
                        )
                        PairStatus.DONE -> {
                            val body = pairFresh?.content ?: pair.content
                            if (body.isNullOrBlank()) {
                                Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                            } else {
                                ContentWithThinkSections(analysis = body)
                            }
                        }
                    }
                    // Trailing divider — mirrors the one between source
                    // and answerer above so the response pane has a
                    // clean closing edge tight against the content,
                    // not floating at the bottom of the screen.
                    HorizontalDivider(
                        color = AppColors.DividerDark, thickness = 2.dp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Prev / Next arrow row at the bottom.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        prev?.let {
                            if (role == "Responder") onStepSource(it.sourceAgentId, it.id)
                            else onStepSource(it.answererAgentId, it.id)
                        }
                    },
                    enabled = prev != null,
                    modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("← Prev", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(
                    onClick = {
                        next?.let {
                            if (role == "Responder") onStepSource(it.sourceAgentId, it.id)
                            else onStepSource(it.answererAgentId, it.id)
                        }
                    },
                    enabled = next != null,
                    modifier = Modifier.weight(1f),
                    colors = AppColors.outlinedButtonColors()
                ) { Text("Next →", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this pair?") },
            text = { Text("Drops the pair row from the run. The API cost stays counted in the report total.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onCancelPair(run.key, pair.key)
                    onBack()
                }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
