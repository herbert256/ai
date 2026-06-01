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
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val providerService = AppService.findById(result.providerId)
    val provider = providerService?.id ?: result.providerId
    // "second-rerank" → "rerank"; a user-renamed rerank prompt passes through.
    val title = result.metaPromptName?.takeIf { it.isNotBlank() }
        ?.let { com.ai.data.secondaryPromptDisplayName(it) }
        ?: com.ai.data.legacyKindDisplayName(result.kind)
    var confirmDelete by remember { mutableStateOf(false) }

    // Trace file for this rerank call: same report + model, timestamp closest
    // to the row. Null when tracing was off at call time.
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

    // Fresh on-disk row so a re-run / edit reflects here even though `result`
    // arrives stale from the list mount.
    val secDataVersion by com.ai.data.SecondaryDataVersion.version.collectAsState()
    val resultFresh by produceState<SecondaryResult?>(null, result.id, secDataVersion) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.get(context, result.reportId, result.id) }
    }
    val displayContent = resultFresh?.content ?: result.content

    // id → "provider / model" map (success-ordered, 1-based) — the rerank table
    // resolves each bracketed [N] back to a real model name through it.
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

    // ✍️ user notes for this rerank row.
    var noteEdit by remember { mutableStateOf<NoteEdit?>(null) }
    if (noteEdit != null) {
        UserNoteEditorOverlay(result.reportId, "SECONDARY", result.id, noteEdit!!) { noteEdit = null }
        return
    }
    val noteDataVersion by ReportDataVersion.version.collectAsState()
    val secondaryNotes by produceState(emptyList<UserNote>(), result.reportId, result.id, noteDataVersion) {
        value = withContext(Dispatchers.IO) {
            ReportStorage.getReport(context, result.reportId)?.notesFor("SECONDARY", result.id) ?: emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val traceEnabled = ApiTracer.isTracingEnabled && traceFilename != null
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
            reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: "📝",
            subject = reportTitle,
            onBackClick = onBack,
            onTrace = if (traceEnabled) { { onNavigateToTraceFile(traceFilename!!) } } else null,
            onDelete = { confirmDelete = true },
            onOpenView = onOpenViewJump,
            onInfo = if (providerService != null) { { onNavigateToModelInfo(providerService, result.model) } } else null,
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
            Text(com.ai.ui.shared.shortModelName(result.model), fontSize = 13.sp, color = AppColors.Blue,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                result.errorMessage != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                    Text(result.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
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
            text = { Text(com.ai.ui.shared.modelLabel(provider, result.model)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}
