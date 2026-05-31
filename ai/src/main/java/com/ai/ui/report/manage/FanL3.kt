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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    actions: FanOutActions,
    mode: FanOutMode = FanOutMode.MAIN,
    iconRefreshTick: Int = 0,
    onStepSource: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val (activePid, activeMdl) = answererKey.split("|").let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }

    // The pair we're viewing. For Responder mode: active model is
    // answerer, sourceAgentId identifies the source. For Initiator
    // mode the L2 row's "other" agent is the answerer, and this
    // sourceAgentId is actually the answererAgentId in our model.
    val pair = remember(run, answererKey, sourceAgentId, role) {
        if (role == "Responder") {
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
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
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
                title = when (mode) {
                    FanOutMode.META -> "Fan Meta - pair"
                    else -> "Fan out - pair"
                },
                subject = "This pair no longer exists",
                onOpenView = onOpenViewEmptyJump,
                onBackClick = onBack
            )
            Text("Pair no longer exists.", color = AppColors.TextTertiary)
        }
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }

    // L2 scope for prev/next stepping — same ordering as L2.
    val l2Rows = remember(run, answererKey, role) {
        when (role) {
            "Initiator" -> run.pairs.values.filter {
                run.pairs.values.any { other ->
                    other.answererAgentId == it.sourceAgentId &&
                        "${other.providerId}|${other.model}" == answererKey
                }
            }
            else -> run.pairs.values.filter { "${it.providerId}|${it.model}" == answererKey }
        }.sortedBy { it.timestamp }
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

    // Fan Meta L3 is a purpose-built metadata screen (big icon + green
    // title + the two model lines + the two Find-alt buttons), not the
    // source/answerer split the MAIN mode renders. Branch out before
    // the trace lookups + split-pane body below.
    if (mode == FanOutMode.META) {
        FanOutL3MetaBody(
            run = run,
            pair = pair,
            answererLabel = answererLabel,
            answererProviderService = answererProviderService,
            prev = prev,
            next = next,
            role = role,
            actions = actions,
            iconRefreshTick = iconRefreshTick,
            onStepSource = onStepSource,
            onBack = onBack
        )
        return
    }

    // ✍️ user notes for this fan-out response (the pair = one SecondaryResult).
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(run.reportId, "SECONDARY", pair.id, noteEdit!!) { noteEdit = null }
        return
    }
    val noteDataVersion by ReportDataVersion.version.collectAsState()
    val pairNotes by produceState(emptyList<UserNote>(), run.reportId, pair.id, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, run.reportId)?.notesFor("SECONDARY", pair.id) ?: emptyList()
        }
    }

    // 🗣️ refine-in-chat overlay for this fan-out pair's answer.
    val aiSettings = com.ai.ui.shared.LocalAiSettings.current
    val secDataVersion by com.ai.data.SecondaryDataVersion.version.collectAsState()
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
                    icon = "🔄",
                    title = "Reload",
                    description = "Rerun this fan-out pair with the saved fan-out prompt and settings.",
                    onClick = {
                        showResponseChangeActions = false
                        actions.onRerunPair(run.key, pair.key)
                    }
                ),
                ResponseChangeAction(
                    icon = "✏️",
                    title = "Edit prompt",
                    description = "Edit the resolved fan-out prompt only for this pair replay.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showPromptEditReplay = true
                    }
                ),
                ResponseChangeAction(
                    icon = "🗣️",
                    title = "Chat",
                    description = "Refine the pair response in a chat and apply a chosen assistant reply.",
                    enabled = canChangePairResponse,
                    onClick = {
                        showResponseChangeActions = false
                        showAgentChat = true
                    }
                ),
                ResponseChangeAction(
                    icon = "🌡️",
                    title = "Temperature sweep",
                    description = "Run one to three temperature variants and select the best pair response.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showTemperatureSweep = true
                    }
                ),
                ResponseChangeAction(
                    icon = "🧠",
                    title = "Reasoning Effort",
                    description = "Compare selected reasoning-effort levels for this pair when supported.",
                    enabled = canRunVariation,
                    onClick = {
                        showResponseChangeActions = false
                        showReasoningEffortSweep = true
                    }
                ),
                ResponseChangeAction(
                    icon = "🧭",
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

    // Trace lookups — answerer trace = closest-timestamp trace for
    // this pair's reportId + model. Source trace = most-recent trace
    // for the source agent's reportId + model.
    val answererTrace by produceState<String?>(initialValue = null, pair.id, pair.model, pair.timestamp) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == run.reportId && it.model == pair.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - pair.timestamp) }?.filename
        }
    }
    val sourceTrace by produceState<String?>(initialValue = null, run.reportId, sourceAgent?.model) {
        value = if (sourceAgent == null) null else withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == run.reportId && it.model == sourceAgent.model }
                .maxByOrNull { it.timestamp }?.filename
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                title = when (mode) {
                    FanOutMode.META -> "Fan Meta - pair"
                    else -> "Fan out - pair"
                },
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
                onTrace = if (ApiTracer.isTracingEnabled && answererTrace != null) {
                    { actions.onNavigateToTraceFile(answererTrace!!) }
                } else null,
                onDelete = { confirmDelete = true },
                onAddNote = { noteEdit = NoteEdit.Add },
                onEdit = { showResponseChangeActions = true }
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
                            modifier = Modifier.background(MaterialTheme.colorScheme.background)
                                .padding(end = 6.dp)
                        )
                    }
                    Text(
                        sourceLabel, fontSize = 14.sp, color = AppColors.Blue,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (sourcePaneIsOther && sourceProviderService != null && sourceAgent != null) {
                        Text(
                            "ℹ️", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToModelInfo(sourceProviderService, sourceAgent.model) }
                        )
                    }
                    if (sourcePaneIsOther && ApiTracer.isTracingEnabled && sourceTrace != null) {
                        Text(
                            "🐞", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToTraceFile(sourceTrace!!) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val body = sourceBody
                    if (body.isNullOrBlank()) {
                        Text("(source content not found)", color = AppColors.TextTertiary, fontSize = 13.sp)
                    } else {
                        Text(body, fontSize = 13.sp, color = Color.White)
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
                        // for this pair (6th adapter). Only wired in
                        // MAIN mode — ICONS-mode L3 is itself an
                        // icon-focused view.
                        val iconModifier = Modifier
                            .background(MaterialTheme.colorScheme.background)
                            .padding(end = 6.dp)
                            .let { base ->
                                if (mode == FanOutMode.MAIN) {
                                    base.clickable { actions.onOpenPairIconLookup(pair.id) }
                                } else base
                            }
                        Text(
                            pair.icon, fontSize = 16.sp,
                            modifier = iconModifier
                        )
                    }
                    Text(
                        answererLabel, fontSize = 14.sp, color = AppColors.Green,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (responsePaneIsOther && answererProviderService != null) {
                        Text(
                            "ℹ️", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToModelInfo(answererProviderService, pair.model) }
                        )
                    }
                    if (responsePaneIsOther && ApiTracer.isTracingEnabled && answererTrace != null) {
                        Text(
                            "🐞", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToTraceFile(answererTrace!!) }
                        )
                    }
                    if (pair.totalCost > 0.0) {
                        Text(
                            "${formatCents(pair.totalCost)} ¢", fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                responseChangeLabel?.let { label ->
                    Text(
                        text = label,
                        color = AppColors.Yellow,
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
                            "❌ ${pair.errorMessage}",
                            color = AppColors.Red, fontSize = 13.sp
                        )
                        PairStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedHourglass(fontSize = 16.sp)
                            Text("  Running…", color = AppColors.Orange, fontSize = 13.sp)
                        }
                        PairStatus.PENDING -> Text(
                            "🕓 Queued",
                            color = AppColors.TextTertiary, fontSize = 13.sp
                        )
                        PairStatus.DONE -> {
                            val body = pairFresh?.content ?: pair.content
                            if (body.isNullOrBlank()) {
                                Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                            } else {
                                Text(body, fontSize = 13.sp, color = Color.White)
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
                Button(
                    onClick = {
                        prev?.let {
                            if (role == "Responder") onStepSource(it.sourceAgentId)
                            else onStepSource(it.answererAgentId)
                        }
                    },
                    enabled = prev != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("← Prev", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        next?.let {
                            if (role == "Responder") onStepSource(it.sourceAgentId)
                            else onStepSource(it.answererAgentId)
                        }
                    },
                    enabled = next != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
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
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

/**
 * Fan Meta L3 — the single-pair metadata screen. Foregrounds the
 * generated icon + title and exposes the two per-pair Find-alt
 * buttons. Re-reads the pair from disk on [iconRefreshTick] so a
 * picked icon/title shows immediately (the engine snapshot stays
 * stale after a Find-alt pick; it only bumps the tick + writes disk).
 */
@Composable
internal fun FanOutL3MetaBody(
    run: FanOutRunState,
    pair: PairState,
    answererLabel: String,
    answererProviderService: AppService?,
    prev: PairState?,
    next: PairState?,
    role: String,
    actions: FanOutActions,
    iconRefreshTick: Int,
    onStepSource: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    // Re-read the row from disk on each refresh tick so picked
    // icon/title/titleModel land here without leaving the screen.
    val fresh by produceState(initialValue = pair, pair.id, run.reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.get(context, run.reportId, pair.id)
                ?.toPairState(pair.answererAgentId) ?: pair
        }
    }
    val icon = fresh.icon?.takeIf { it.isNotBlank() }
    val title = fresh.title?.takeIf { it.isNotBlank() }
    val metaModel = fresh.titleModel?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }?.let { shortModelName(it) } ?: "—"

    // Horizontal swipe steps to the prev / next pair (replaces the old
    // Prev/Next buttons): swipe right → previous, swipe left → next —
    // same direction convention as the Manage-hub report swipe.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    val swipeDragX = remember { mutableFloatStateOf(0f) }
    val goPrev: () -> Unit = {
        prev?.let { if (role == "Responder") onStepSource(it.sourceAgentId) else onStepSource(it.answererAgentId) }
    }
    val goNext: () -> Unit = {
        next?.let { if (role == "Responder") onStepSource(it.sourceAgentId) else onStepSource(it.answererAgentId) }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
            .pointerInput(prev, next, role) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDragX.floatValue = 0f },
                    onDragEnd = {
                        val dx = swipeDragX.floatValue
                        when {
                            dx > swipeThresholdPx -> goPrev()
                            dx < -swipeThresholdPx -> goNext()
                        }
                        swipeDragX.floatValue = 0f
                    },
                    onDragCancel = { swipeDragX.floatValue = 0f },
                    onHorizontalDrag = { _, d -> swipeDragX.floatValue += d }
                )
            }
    ) {
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            {
                holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                    ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                    ?: com.ai.ui.shared.ViewJump.Main
            }
        }
        TitleBar(
            helpTopic = "fan_meta",
            title = "Fan Meta - pair",
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = answererLabel,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            onInfo = answererProviderService?.let { svc ->
                { actions.onNavigateToModelInfo(svc, pair.model) }
            },
            onReload = { actions.onRerunPair(run.key, pair.key) },
            onDelete = { confirmDelete = true }
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Big, centered, the found icon.
            Text(icon ?: "🏷️", fontSize = 72.sp, color = Color.White)
            Spacer(Modifier.height(20.dp))
            // Green, big, the found title.
            Text(
                title ?: "(no title yet)",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (title != null) AppColors.Green else AppColors.TextTertiary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            // Two model lines.
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Fan-out model:  ${shortModelName(pair.model)}",
                    fontSize = 14.sp, color = Color.White,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Meta model:     $metaModel",
                    fontSize = 14.sp, color = Color.White,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        // Two Find-alt buttons.
        Button(
            onClick = { actions.onFindAlternativePairIcon(pair.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text("Find alternative icon", maxLines = 1, softWrap = false) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { actions.onFindAlternativePairTitle(pair.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple)
        ) { Text("Find alternative title", maxLines = 1, softWrap = false) }

        // Swipe ← / → steps through the L2-scoped pair list (the small
        // hint replaces the old Prev/Next buttons). Greyed ends show
        // there's nothing further that way.
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "← swipe   ·   swipe →",
                fontSize = 12.sp, color = AppColors.TextTertiary,
                maxLines = 1, softWrap = false
            )
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
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
