package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ReportRuntimeState(
    val secondaryCounts: SecondaryResultStorage.Counts,
    val secondaryRuns: List<com.ai.data.SecondaryResult>,
    /** Raw TRANSLATE rows on this report. secondaryRuns
     *  intentionally excludes them, but buildEveryItems' meta
     *  availability fold (cross-translate, back-translate) needs
     *  to see them — without this list every meta tile would
     *  stay grayed even after a back-translation completes. */
    val translateRows: List<com.ai.data.SecondaryResult>,
    val translationRunSummaries: List<TranslationRunSummary>,
    val fanOutSummaries: List<FanOutRunSummary>,
    val secondaryTotals: SecondaryTotals,
    val costsFromDeletedItems: Double,
    val reportIcon: String?,
    val reportIconError: String?,
    val reportIconCost: Double,
    val reportIconModel: String?,
    val reportIconTraceFile: String?,
    val languageIconCost: Double,
    val languageDetectCost: Double,
    val languageName: String?,
    /** Source-language icon (Report.languageIcon) — fed to the
     *  Export screen's Language picker as the "Original" icon. */
    val languageIcon: String?,
    val agentIconRows: Map<String, AgentIconRow>,
    val agentRecordsByAgentId: Map<String, com.ai.data.ReportAgent>,
    val loadedReportPrompt: String,
    val loadedReportTitle: String?,
    val loadedReportTimestamp: Long,
    val effectiveReportIcon: String?,
    /** True once the report's disk read has completed for the current
     *  report id. Lets status rows tell "data not read yet" apart from
     *  "field genuinely empty / still generating" so a finished row
     *  doesn't flash the running hourglass on screen open. */
    val loaded: Boolean,
    val onSecondaryRefresh: () -> Unit,
    val onDeleteSecondaryWithRefresh: (String, String) -> Unit
)

@Composable
internal fun rememberReportRuntimeState(
    context: Context,
    currentReportId: String?,
    uiState: UiState,
    isComplete: Boolean,
    iconGenEnabled: Boolean,
    translationRuns: List<com.ai.viewmodel.TranslationRunState>,
    fanRuntime: FanRuntimeBundle,
    fanOutEngine: com.ai.viewmodel.FanOutEngine?,
    translationLifecycle: TranslationLifecycleCallbacks,
    onResumeStaleRuns: (String) -> Unit,
    onDeleteSecondary: (String, String) -> Unit
): ReportRuntimeState {
    var secondaryCounts by remember { mutableStateOf(SecondaryResultStorage.Counts(0, 0, 0, 0)) }
    var secondaryRuns by remember { mutableStateOf(emptyList<com.ai.data.SecondaryResult>()) }
    var translateRows by remember { mutableStateOf(emptyList<com.ai.data.SecondaryResult>()) }
    var translationRunSummaries by remember { mutableStateOf(emptyList<TranslationRunSummary>()) }
    var fanOutSummaries by remember { mutableStateOf(emptyList<FanOutRunSummary>()) }
    var secondaryTotals by remember { mutableStateOf(SecondaryTotals.ZERO) }
    var costsFromDeletedItems by remember { mutableStateOf(0.0) }

    var reportIcon by remember { mutableStateOf<String?>(null) }
    var reportIconError by remember { mutableStateOf<String?>(null) }
    var reportIconCost by remember { mutableStateOf(0.0) }
    var reportIconModel by remember { mutableStateOf<String?>(null) }
    var reportIconTraceFile by remember { mutableStateOf<String?>(null) }
    var languageIconCost by remember { mutableStateOf(0.0) }
    var languageDetectCost by remember { mutableStateOf(0.0) }
    var languageName by remember { mutableStateOf<String?>(null) }
    var languageIcon by remember { mutableStateOf<String?>(null) }
    var agentIconRows by remember { mutableStateOf<Map<String, AgentIconRow>>(emptyMap()) }
    var agentRecordsByAgentId by remember { mutableStateOf<Map<String, com.ai.data.ReportAgent>>(emptyMap()) }
    var loadedReportPrompt by remember { mutableStateOf("") }
    var loadedReportTitle by remember { mutableStateOf<String?>(null) }
    var loadedReportTimestamp by remember { mutableStateOf(0L) }
    // Report id whose disk read has completed. Keyed to the id (not a
    // bool) so a report switch re-arms the "loading" state while an
    // iconRefreshTick re-run keeps it loaded → no hourglass flash.
    var loadedReportId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentReportId, uiState.iconRefreshTick) {
        val rid = currentReportId
        if (rid == null) {
            loadedReportId = null
            reportIcon = null
            reportIconError = null
            reportIconCost = 0.0
            reportIconModel = null
            reportIconTraceFile = null
            languageIconCost = 0.0
            languageDetectCost = 0.0
            languageName = null
            languageIcon = null
            agentIconRows = emptyMap()
            agentRecordsByAgentId = emptyMap()
            loadedReportPrompt = ""
            loadedReportTitle = null
            loadedReportTimestamp = 0L
        } else {
            val r = withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, rid) }
            reportIcon = r?.icon
            reportIconError = r?.iconErrorMessage
            reportIconCost = (r?.iconInputCost ?: 0.0) + (r?.iconOutputCost ?: 0.0)
            reportIconModel = r?.iconModel
            reportIconTraceFile = r?.iconTraceFile
            languageIconCost = (r?.languageIconInputCost ?: 0.0) + (r?.languageIconOutputCost ?: 0.0)
            languageDetectCost = (r?.languageInputCost ?: 0.0) + (r?.languageOutputCost ?: 0.0)
            languageName = r?.languageName
            languageIcon = r?.languageIcon
            agentIconRows = r?.agents?.associate { ra ->
                ra.agentId to AgentIconRow(ra.icon, ra.iconInputCost + ra.iconOutputCost)
            } ?: emptyMap()
            agentRecordsByAgentId = r?.agents?.associate { ra -> ra.agentId to ra } ?: emptyMap()
            loadedReportPrompt = r?.prompt.orEmpty()
            loadedReportTitle = r?.title
            loadedReportTimestamp = r?.timestamp ?: 0L
            loadedReportId = rid
        }
    }

    var secondaryRefreshTick by remember { mutableStateOf(0) }
    val onSecondaryRefresh: () -> Unit = { secondaryRefreshTick++ }

    LaunchedEffect(currentReportId) {
        val rid = currentReportId ?: return@LaunchedEffect
        onResumeStaleRuns(rid)
        kotlinx.coroutines.delay(150)
        secondaryRefreshTick++
    }

    LaunchedEffect(currentReportId) {
        val rid = currentReportId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        val pairs = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, rid)
                .filter { it.fanOutSourceAgentId != null }
        }
        pairs.groupBy { it.metaPromptId }
            .forEach { (metaPromptId, rows) ->
                if (metaPromptId == null) return@forEach
                val started = rows.any { !it.icon.isNullOrBlank() || !it.iconErrorMessage.isNullOrBlank() }
                if (started) fanRuntime.onLaunchFanIconsBatch(rid, metaPromptId)
            }
    }

    if (fanOutEngine != null) {
        val engineRuns by fanOutEngine.runs.collectAsState()
        val ridForKeys = currentReportId
        val currentRunKeys = remember(engineRuns, ridForKeys) {
            if (ridForKeys == null) emptySet()
            else engineRuns.keys.filter { it.startsWith("$ridForKeys|") }.toSet()
        }
        LaunchedEffect(currentRunKeys) {
            secondaryRefreshTick++
        }
    }

    val finishedSignature = translationRuns.filter { it.isFinished }.map { it.runId }.toSet()
    // iconRefreshTick is in the key set so a per-row icon pick
    // (pickMetaRowIcon → setRowIcon writes to disk + bumps the tick)
    // reloads secondaryRuns from disk; without it the in-memory list
    // keeps the old SecondaryResult.icon value and the View tile +
    // Manage row never reflect the user's pick.
    LaunchedEffect(currentReportId, isComplete, uiState.activeSecondaryBatches, finishedSignature, secondaryRefreshTick, uiState.iconRefreshTick) {
        val rid = currentReportId ?: run {
            secondaryCounts = SecondaryResultStorage.Counts(0, 0, 0, 0)
            secondaryRuns = emptyList()
            translateRows = emptyList()
            translationRunSummaries = emptyList()
            fanOutSummaries = emptyList()
            secondaryTotals = SecondaryTotals.ZERO
            costsFromDeletedItems = 0.0
            return@LaunchedEffect
        }
        costsFromDeletedItems = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getReport(context, rid)?.costsFromDeletedItems ?: 0.0
        }
        suspend fun reload() {
            withContext(Dispatchers.IO) {
                val all = SecondaryResultStorage.listForReport(context, rid)
                secondaryRuns = all
                    .filter { it.kind != SecondaryKind.TRANSLATE }
                    .filter { it.fanOutSourceAgentId == null }
                    .sortedByDescending { it.timestamp }
                translateRows = all.filter { it.kind == SecondaryKind.TRANSLATE }
                translationRunSummaries = buildTranslationRunSummaries(translateRows)
                fanOutSummaries = buildFanOutSummaries(
                    all.filter { it.fanOutSourceAgentId != null }
                )
                secondaryCounts = SecondaryResultStorage.Counts(
                    rerank = all.count { it.kind == SecondaryKind.RERANK },
                    meta = all.count { it.kind == SecondaryKind.META },
                    moderation = all.count { it.kind == SecondaryKind.MODERATION },
                    translate = all.count { it.kind == SecondaryKind.TRANSLATE }
                )
                secondaryTotals = SecondaryTotals(
                    inputTokens = all.sumOf { it.tokenUsage?.inputTokens ?: 0 },
                    outputTokens = all.sumOf { it.tokenUsage?.outputTokens ?: 0 },
                    inputCost = all.sumOf { it.inputCost ?: 0.0 },
                    outputCost = all.sumOf { it.outputCost ?: 0.0 },
                    fanOutIconCost = all.sumOf { it.iconInputCost + it.iconOutputCost }
                )
            }
        }
        reload()
        if (uiState.activeSecondaryBatches > 0) {
            while (true) {
                delay(500)
                reload()
            }
        }
    }

    LaunchedEffect(finishedSignature) {
        if (finishedSignature.isNotEmpty()) {
            delay(200)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                finishedSignature.forEach { translationLifecycle.onConsumeRun(it) }
            }
        }
    }

    val effectiveReportIcon =
        if (iconGenEnabled && currentReportId != null) reportIcon?.takeIf { it.isNotEmpty() } ?: "📝"
        else null

    return ReportRuntimeState(
        secondaryCounts = secondaryCounts,
        secondaryRuns = secondaryRuns,
        translateRows = translateRows,
        translationRunSummaries = translationRunSummaries,
        fanOutSummaries = fanOutSummaries,
        secondaryTotals = secondaryTotals,
        costsFromDeletedItems = costsFromDeletedItems,
        reportIcon = reportIcon,
        reportIconError = reportIconError,
        reportIconCost = reportIconCost,
        reportIconModel = reportIconModel,
        reportIconTraceFile = reportIconTraceFile,
        languageIconCost = languageIconCost,
        languageDetectCost = languageDetectCost,
        languageName = languageName,
        languageIcon = languageIcon,
        agentIconRows = agentIconRows,
        agentRecordsByAgentId = agentRecordsByAgentId,
        loadedReportPrompt = loadedReportPrompt,
        loadedReportTitle = loadedReportTitle,
        loadedReportTimestamp = loadedReportTimestamp,
        effectiveReportIcon = effectiveReportIcon,
        loaded = currentReportId != null && loadedReportId == currentReportId,
        onSecondaryRefresh = onSecondaryRefresh,
        onDeleteSecondaryWithRefresh = { rid, sid ->
            onDeleteSecondary(rid, sid)
            secondaryRefreshTick++
        }
    )
}

@Composable
internal fun HandleExternalReportInstructions(
    context: Context,
    activity: Activity?,
    uiState: UiState,
    aiSettings: Settings,
    isGenerating: Boolean,
    isComplete: Boolean,
    currentReportId: String?,
    models: List<ReportModel>,
    selectedParametersIds: List<String>,
    onModelsChange: (List<ReportModel>) -> Unit,
    onGenerate: (List<ReportModel>, List<String>, ReportType) -> Unit,
    onOpenView: () -> Unit,
    onClearExternalInstructions: () -> Unit
) {
    data class ExternalResolution(val resolved: List<ReportModel>, val unresolved: List<String>)
    var externalAutoGenerated by rememberSaveable { mutableStateOf(false) }
    val externalRes = remember(
        uiState.externalAgentNames,
        uiState.externalFlockNames,
        uiState.externalSwarmNames,
        uiState.externalModelSpecs
    ) {
        val result = mutableListOf<ReportModel>()
        val missing = mutableListOf<String>()
        uiState.externalAgentNames.forEach { name ->
            val a = aiSettings.agents.find { it.name.equals(name, ignoreCase = true) }
            val rm = a?.let { expandAgentToModel(it, aiSettings) }
            if (rm != null) result.add(rm) else missing.add("agent: $name")
        }
        uiState.externalFlockNames.forEach { name ->
            val f = aiSettings.flocks.find { it.name.equals(name, ignoreCase = true) }
            if (f != null) result.addAll(expandFlockToModels(f, aiSettings)) else missing.add("flock: $name")
        }
        uiState.externalSwarmNames.forEach { name ->
            val s = aiSettings.swarms.find { it.name.equals(name, ignoreCase = true) }
            if (s != null) result.addAll(expandSwarmToModels(s, aiSettings)) else missing.add("swarm: $name")
        }
        uiState.externalModelSpecs.forEach { spec ->
            val parts = spec.split("/", limit = 2)
            val provider = AppService.findById(parts.getOrNull(0) ?: "")
                ?: AppService.entries.find { it.id.equals(parts.getOrNull(0), ignoreCase = true) }
            val model = parts.getOrNull(1)
            if (provider != null && model != null) result.add(toReportModel(provider, model))
            else missing.add("model: $spec")
        }
        ExternalResolution(deduplicateModels(result), missing)
    }
    val externalModels = externalRes.resolved
    LaunchedEffect(externalRes.unresolved) {
        if (externalRes.unresolved.isNotEmpty()) {
            android.widget.Toast.makeText(
                context,
                "Unresolved external entries: ${externalRes.unresolved.joinToString(", ")}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(externalModels, uiState.externalReportType) {
        if (externalModels.isNotEmpty() && !externalAutoGenerated && !isGenerating && uiState.externalReportType != null && !uiState.externalSelect) {
            externalAutoGenerated = true
            val updatedModels = deduplicateModels(models + externalModels)
            onModelsChange(updatedModels)
            val type = if (uiState.externalReportType.equals("table", ignoreCase = true)) ReportType.TABLE else ReportType.CLASSIC
            onGenerate(updatedModels, selectedParametersIds, type)
        }
    }

    LaunchedEffect(externalModels) {
        if (externalModels.isNotEmpty() && !externalAutoGenerated) {
            onModelsChange(deduplicateModels(models + externalModels))
        }
    }

    LaunchedEffect(isComplete, currentReportId) {
        if (isComplete && currentReportId != null) {
            val email = uiState.externalEmail
            if (email != null && email.isNotBlank()) {
                emailReportAsHtml(context, currentReportId, email)
                if (uiState.externalReturn) activity?.finish()
            }
            val next = uiState.externalNextAction
            if (next != null) {
                delay(500)
                when (next.lowercase()) {
                    "view" -> onOpenView()
                    "share" -> shareReportAsHtml(context, currentReportId)
                    "browser" -> openReportInChrome(context, currentReportId)
                    "email" -> if (uiState.generalSettings.defaultEmail.isNotBlank()) {
                        emailReportAsHtml(context, currentReportId, uiState.generalSettings.defaultEmail)
                    }
                }
                if (uiState.externalReturn) {
                    delay(1000)
                    activity?.finish()
                }
            }
            if (
                uiState.externalEmail != null ||
                uiState.externalNextAction != null ||
                uiState.externalReturn ||
                uiState.externalReportType != null ||
                uiState.externalAgentNames.isNotEmpty() ||
                uiState.externalFlockNames.isNotEmpty() ||
                uiState.externalSwarmNames.isNotEmpty() ||
                uiState.externalModelSpecs.isNotEmpty()
            ) {
                onClearExternalInstructions()
            }
        }
    }
}

