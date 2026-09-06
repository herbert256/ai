package com.ai.ui.report.manage.view
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.data.UserNote
import com.ai.data.barTitle
import com.ai.data.notesFor
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ModelSwitchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated detail screen for a **RERANK** [SecondaryResult] — the 1..N
 * best-first ranking a rerank run produces (chat-prompt path or the native
 * `callRerankApi` path, both emit the same `[{id, rank, score, reason}, …]`
 * JSON). Split out of the shared [SecondaryResultDetailScreen] so reranks get
 * their own purpose-built surface (no language tabs, chat, refine or moderation
 * drill-in — none of which apply to a structured ranking).
 *
 * Renders the ranking as a [RerankTable] (with the report's id → model labels),
 * falling back to raw markdown when the model's output deviated from the
 * requested schema. Keeps the management actions reranks actually use: 🐞
 * trace, ℹ️ model info, 👁 View Rerank, copy / share, ✍️ notes, 🗑 delete.
 */
@Composable
internal fun RerankDetailScreen(
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
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val aiSettings = com.ai.ui.shared.LocalAiSettings.current
    val modelSwitch = com.ai.ui.shared.LocalSecondaryModelSwitch.current
    // "second-rerank" → "rerank"; a user-renamed rerank prompt passes through.
    val title = result.metaPromptName?.takeIf { it.isNotBlank() }
        ?.let { com.ai.data.secondaryPromptDisplayName(it) }
        ?: com.ai.data.legacyKindDisplayName(result.kind)
    var confirmDelete by remember { mutableStateOf(false) }

    val parentReportState = produceState<com.ai.data.Report?>(initialValue = null, result.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, result.reportId) }
    }
    val parentReport = parentReportState.value

    // Fresh on-disk row so a re-run / edit reflects here even though `result`
    // arrives stale from the list mount.
    val secDataVersion by com.ai.data.SecondaryDataVersion.versionFor(result.reportId, result.kind).collectAsState()
    val resultFresh by produceState<SecondaryResult?>(null, result.id, secDataVersion) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.get(context, result.reportId, result.id) }
    }
    // Render everything a model switch can change from the fresh on-disk row
    // (provider/model included) so the switch reflects without a remount —
    // otherwise the stale provider/model from the list mount would keep
    // pointing the title bar's Model Info / trace / delete dialog at the
    // pre-switch model. Mirrors ModerationDetailScreen's `eff`.
    val eff = resultFresh ?: result
    val providerService = AppService.findById(eff.providerId)
    val provider = providerService?.id ?: eff.providerId

    // Trace file for this rerank call: same report + model, timestamp closest
    // to the row. Resolved from the FRESH row (eff) and re-keyed on
    // secDataVersion so 🐞 follows a model switch — keying on result.model
    // kept opening the pre-switch model's trace. Null when tracing was off.
    val traceFilenameState = produceState<String?>(initialValue = null, result.id, secDataVersion, eff.model) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == eff.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - eff.timestamp) }?.filename
        }
    }
    val traceFilename = traceFilenameState.value
    val displayContent = eff.content

    // id → "provider / model" map (success-ordered, 1-based) — the rerank table
    // resolves each bracketed [N] back to a real model name through the row's
    // run-time sourceAgentIds snapshot (current-set fallback for legacy rows),
    // so an agent removal / regenerate can't shift the labels onto the wrong
    // models. Keyed on the snapshot so a reload's re-stamp re-resolves.
    val agentLabelsState = produceState(initialValue = emptyMap<Int, String>(), result.reportId, eff.sourceAgentIds) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            com.ai.data.sourceAgentLabels(report, eff)
        }
    }
    val agentLabels = agentLabelsState.value

    // ✍️ user notes for this rerank row.
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(result.reportId, "SECONDARY", result.id, noteEdit!!) { noteEdit = null }
        return
    }

    // ✏️ → "Change result": Reload (re-run in place), Switch model / agent,
    // or — for chat-type reranks — Edit prompt (per-run criterion tweak;
    // native rerank models take no prompt, so the action hides there).
    var showChangeActions by remember { mutableStateOf(false) }
    var showModelSwitchPick by remember { mutableStateOf(false) }
    var showPromptEditReplay by remember { mutableStateOf(false) }
    val metaEditManager = com.ai.ui.shared.LocalMetaEditManager.current
    val isNativeRerank = providerService != null &&
        aiSettings.getModelType(providerService, eff.model) == com.ai.data.ModelType.RERANK
    val switchStates by (modelSwitch?.states ?: emptyModelSwitchStatesFlow).collectAsState()
    val switchState = switchStates[ModelSwitchState.key(result.reportId, result.id)]
    if (modelSwitch != null && showChangeActions) {
        ResponseChangeActionsScreen(
            title = "Change result",
            subject = com.ai.ui.shared.modelLabel(provider, eff.model, separator = " / "),
            actions = listOf(
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.reload,
                    title = "Reload",
                    description = "Regenerate this result with its saved model and settings.",
                    onClick = { showChangeActions = false; modelSwitch.reloadSecondary(context, result.reportId, result.id) }
                ),
                ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.reportModelIcon,
                    title = "Switch model / agent",
                    description = "Re-run this result against another model or agent, then keep or discard it.",
                    onClick = { showChangeActions = false; showModelSwitchPick = true }
                )
            ) + listOfNotNull(
                if (!isNativeRerank && metaEditManager != null) ResponseChangeAction(
                    icon = com.ai.data.MetadataIconsHolder.current.edit,
                    title = "Edit prompt",
                    description = "Re-rank by a different criterion for THIS run only — the global rerank prompt is untouched.",
                    onClick = { showChangeActions = false; showPromptEditReplay = true }
                ) else null
            ),
            onBack = { showChangeActions = false }
        )
        return
    }
    val promptEditReplayStatesR by (metaEditManager?.promptEditReplayStates
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, com.ai.viewmodel.PromptEditReplayState>()) }).collectAsState()
    val resolvedRerankPrompt by produceState<String?>(initialValue = null, result.reportId, result.id, secDataVersion) {
        value = metaEditManager?.resolveMetaPrompt(context, result.reportId, result.id)
    }
    if (showPromptEditReplay && metaEditManager != null) {
        PromptEditReplayScreen(
            reportId = result.reportId,
            targetId = result.id,
            title = "Edit prompt replay",
            modelLabel = com.ai.ui.shared.modelLabel(provider, eff.model, separator = " / "),
            initialPrompt = resolvedRerankPrompt ?: "",
            state = promptEditReplayStatesR[com.ai.viewmodel.PromptEditReplayState.key(result.reportId, result.id)],
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
    if (modelSwitch != null && showModelSwitchPick) {
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
    if (modelSwitch != null && switchState != null) {
        SecondaryModelSwitchPreviewScreen(
            state = switchState,
            onUse = { modelSwitch.applyModelSwitch(context, result.reportId, result.id) },
            onDiscard = { modelSwitch.clear(result.reportId, result.id) },
            onTrace = onNavigateToTraceFile,
            onBack = { modelSwitch.clear(result.reportId, result.id) }
        ) { content ->
            val rows = parseRerankRows(content)
            if (rows == null) ContentWithThinkSections(analysis = content)
            else RerankTable(rows = rows, agentLabels = agentLabels)
        }
        return
    }

    val noteDataVersion by ReportDataVersion.versionFor(result.reportId).collectAsState()
    val secondaryNotes by produceState(emptyList<UserNote>(), result.reportId, result.id, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, result.reportId)?.notesFor("SECONDARY", result.id) ?: emptyList()
        }
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
        // 👁 → View Rerank sub-screen. State lives in ReportsScreenNav via
        // LocalPendingViewOverManage; ReportPrimaryOverlays consumes it.
        val pendingViewHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingViewHolder?.let { holder ->
            { holder.value = com.ai.ui.shared.ViewJump.Rerank(result.id) }
        }
        // Orange subject line = the report's long title (falls back to the
        // short title via barTitle, then to the rerank label before the
        // report loads).
        val reportTitle = parentReport?.barTitle?.takeIf { it.isNotBlank() } ?: title
        TitleBar(
            helpTopic = "rerank_detail",
            title = "Rerank",
            reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon,
            subject = reportTitle,
            onBackClick = onBack,
            onEdit = if (modelSwitch != null) { { showChangeActions = true } } else null,
            onTrace = if (traceEnabled) { { onNavigateToTraceFile(traceFilename) } } else null,
            onDelete = { confirmDelete = true },
            onOpenView = onOpenViewJump,
            onInfo = if (providerService != null) { { onNavigateToModelInfo(providerService, eff.model) } } else null,
            onCopy = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "rerank result") }
            },
            onShare = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.shareText(context, body, "Rerank — $title") }
            },
            onAddNote = { noteEdit = NoteEdit.Add }
        )
        UserNotesSection(
            reportId = result.reportId,
            notes = secondaryNotes,
            onEdit = { noteEdit = NoteEdit.Edit(it.id, it.text) }
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.ai.ui.shared.shortModelName(eff.model), fontSize = 13.sp, color = AppColors.InfoAccent,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            com.ai.ui.shared.ReportSourceNotice(eff)
            InternalPromptCard(result, aiSettings)
            // Read the error from the fresh row so a model switch (which clears it)
            // stops hiding the replaced result.
            val freshError = (resultFresh ?: result).errorMessage
            when {
                freshError != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.DangerAccent, fontWeight = FontWeight.SemiBold)
                    Text(freshError, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                displayContent.isNullOrBlank() -> {
                    Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                else -> {
                    // Structured JSON → ranked table; deviating output → raw markdown.
                    val rows = remember(displayContent) { parseRerankRows(displayContent) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = displayContent)
                    } else {
                        RerankTable(rows = rows, agentLabels = agentLabels)
                    }
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
}
