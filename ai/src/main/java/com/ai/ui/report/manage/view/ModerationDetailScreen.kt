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
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ModelSwitchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated detail screen for a **MODERATION** [SecondaryResult] — the
 * per-response policy classification a moderation run produces (chat-prompt
 * path or the native `callModerationApi` path, both emit the same
 * `[{id, flagged, categories, scores}, …]` JSON). Split out of the shared
 * [SecondaryResultDetailScreen] so moderations get their own purpose-built
 * surface (no language tabs, chat, refine — none of which apply).
 *
 * Renders the classifications as a [ModerationTable] (with the report's
 * id → model labels); tapping a row drills into [ModerationCallDetailScreen]
 * for that response (every category + score + the exact moderated text).
 * Falls back to raw markdown when the model's output deviated from the schema.
 * Keeps the management actions moderations actually use: 🐞 trace, ℹ️ model
 * info, 👁 View Moderation, copy / share, ✍️ notes, 🗑 delete.
 */
@Composable
internal fun ModerationDetailScreen(
    result: SecondaryResult,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val aiSettings = com.ai.ui.shared.LocalAiSettings.current
    val modelSwitch = com.ai.ui.shared.LocalSecondaryModelSwitch.current
    val providerService = AppService.findById(result.providerId)
    val provider = providerService?.id ?: result.providerId
    // "second-moderation" → "moderation"; a renamed prompt passes through.
    val title = result.metaPromptName?.takeIf { it.isNotBlank() }
        ?.let { com.ai.data.secondaryPromptDisplayName(it) }
        ?: com.ai.data.legacyKindDisplayName(result.kind)
    var confirmDelete by remember { mutableStateOf(false) }

    // Trace file for this moderation call: same report + model, timestamp
    // closest to the row. Null when tracing was off at call time.
    val traceFilenameState = produceState<String?>(initialValue = null, result.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == result.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - result.timestamp) }?.filename
        }
    }
    val traceFilename = traceFilenameState.value

    val parentReportState = produceState<com.ai.data.Report?>(initialValue = null, result.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, result.reportId) }
    }
    val parentReport = parentReportState.value

    // Fresh on-disk row so a re-run reflects here even though `result`
    // arrives stale from the list mount.
    val secDataVersion by com.ai.data.SecondaryDataVersion.version.collectAsState()
    val resultFresh by produceState<SecondaryResult?>(null, result.id, secDataVersion) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.get(context, result.reportId, result.id) }
    }
    val displayContent = resultFresh?.content ?: result.content

    // id → "provider / model" and id → response-body maps (success-ordered,
    // 1-based) — the table resolves each [N] to a real model name, and the
    // per-row drill-in shows the exact text that was moderated.
    val agentLabelsState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent ->
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    (idx + 1) to "$provDisplay / ${agent.model}"
                }.toMap()
        }
    }
    val agentLabels = agentLabelsState.value
    val agentResponsesState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent -> (idx + 1) to (agent.responseBody ?: "") }
                .toMap()
        }
    }
    val agentResponses = agentResponsesState.value

    // Per-row moderation drill-in — clicking a table row sets this; the
    // call-detail screen renders full screen until back clears it.
    var openModerationRow by remember { mutableStateOf<ModerationRow?>(null) }
    val activeModRow = openModerationRow
    if (activeModRow != null) {
        ModerationCallDetailScreen(
            row = activeModRow,
            agentLabel = agentLabels[activeModRow.id] ?: "[${activeModRow.id}]",
            agentResponse = agentResponses[activeModRow.id].orEmpty(),
            moderationModelLabel = com.ai.ui.shared.modelLabel(provider, result.model, separator = " / "),
            onBack = { openModerationRow = null }
        )
        return
    }

    // ✍️ user notes for this moderation row.
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(result.reportId, "SECONDARY", result.id, noteEdit!!) { noteEdit = null }
        return
    }

    // ✏️ → "Change result": Reload (re-run in place) or Switch model / agent.
    var showChangeActions by remember { mutableStateOf(false) }
    var showModelSwitchPick by remember { mutableStateOf(false) }
    val switchStates by (modelSwitch?.states ?: emptyModelSwitchStatesFlow).collectAsState()
    val switchState = switchStates[ModelSwitchState.key(result.reportId, result.id)]
    if (modelSwitch != null && showChangeActions) {
        ResponseChangeActionsScreen(
            title = "Change result",
            subject = com.ai.ui.shared.modelLabel(provider, result.model, separator = " / "),
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
            ),
            onBack = { showChangeActions = false }
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
            val rows = parseModerationRows(content)
            if (rows == null) ContentWithThinkSections(analysis = content)
            else ModerationTable(rows = rows, agentLabels = agentLabels, onRowClick = {})
        }
        return
    }
    val noteDataVersion by ReportDataVersion.version.collectAsState()
    val secondaryNotes by produceState(emptyList<UserNote>(), result.reportId, result.id, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, result.reportId)?.notesFor("SECONDARY", result.id) ?: emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val traceEnabled = ApiTracer.ladybugLinksEnabled && traceFilename != null
        // 👁 → View Moderation sub-screen. State lives in ReportsScreenNav via
        // LocalPendingViewOverManage; ReportPrimaryOverlays consumes it.
        val pendingViewHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingViewHolder?.let { holder ->
            { holder.value = com.ai.ui.shared.ViewJump.Moderation(result.id) }
        }
        // Orange subject line = the report's long title (falls back to the
        // short title via barTitle, then to the moderation label before the
        // report loads).
        val reportTitle = parentReport?.barTitle?.takeIf { it.isNotBlank() } ?: title
        TitleBar(
            helpTopic = "moderation_detail",
            title = "Moderation",
            reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon,
            subject = reportTitle,
            onBackClick = onBack,
            onEdit = if (modelSwitch != null) { { showChangeActions = true } } else null,
            onTrace = if (traceEnabled) { { onNavigateToTraceFile(traceFilename!!) } } else null,
            onDelete = { confirmDelete = true },
            onOpenView = onOpenViewJump,
            onInfo = if (providerService != null) { { onNavigateToModelInfo(providerService, result.model) } } else null,
            onCopy = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "moderation result") }
            },
            onShare = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.shareText(context, body, "Moderation — $title") }
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
            Text(com.ai.ui.shared.shortModelName(result.model), fontSize = 13.sp, color = AppColors.InfoAccent,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                result.errorMessage != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.DangerAccent, fontWeight = FontWeight.SemiBold)
                    Text(result.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                displayContent.isNullOrBlank() -> {
                    Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                else -> {
                    // Structured JSON → moderation table; deviating output → raw markdown.
                    val rows = remember(displayContent) { parseModerationRows(displayContent) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = displayContent)
                    } else {
                        ModerationTable(
                            rows = rows,
                            agentLabels = agentLabels,
                            onRowClick = { openModerationRow = it }
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = { Text(com.ai.ui.shared.modelLabel(provider, result.model)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}
