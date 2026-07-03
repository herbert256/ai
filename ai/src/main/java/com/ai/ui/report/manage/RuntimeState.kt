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
    val agentModelTitles: Map<String, AgentModelTitle>,
    val infoEnabled: Boolean,
    val infoState: InfoJobState,
    val infoMetaTotal: Double,
    val secondEnabled: Boolean,
    val secondState: InfoJobState,
    val secondTotal: Double,
    /** Combined cost of every model's main response (the "report/prompt"
     *  calls) — the "report" cross-link row's cost on the Get-info /
     *  second-results screens. Same per-agent input+output costs the
     *  Manage hub's per-model "report" rows show, summed. */
    val mainResponseTotal: Double,
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
    deletingTranslationRunIds: Set<String> = emptySet(),
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
    // Aggregate of every secondary result, for the Manage "second" row + the
    // "Report - second results" screen — recomputed on each secondary reload.
    var secondEnabled by remember { mutableStateOf(false) }
    var secondState by remember { mutableStateOf(InfoJobState.DONE) }
    var secondTotal by remember { mutableStateOf(0.0) }
    // Combined main-response cost of every model (report/prompt calls).
    var mainResponseTotal by remember { mutableStateOf(0.0) }
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
    var agentModelTitles by remember { mutableStateOf<Map<String, AgentModelTitle>>(emptyMap()) }
    // Aggregate of the "Report - Get info" metadata jobs, recomputed on
    // every iconRefreshTick so the Manage info row + grand total stay live.
    var infoEnabled by remember { mutableStateOf(false) }
    var infoState by remember { mutableStateOf(InfoJobState.DONE) }
    var infoMetaTotal by remember { mutableStateOf(0.0) }
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
            agentModelTitles = emptyMap()
            infoEnabled = false
            infoState = InfoJobState.DONE
            infoMetaTotal = 0.0
            mainResponseTotal = 0.0
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
            agentModelTitles = r?.agents?.associate { ra ->
                ra.agentId to AgentModelTitle(ra.modelTitle, ra.modelTitleInputCost + ra.modelTitleOutputCost)
            } ?: emptyMap()
            agentRecordsByAgentId = r?.agents?.associate { ra -> ra.agentId to ra } ?: emptyMap()
            // "report" row cost = sum of every model's persisted main-response
            // input+output cost (matches the Manage hub's per-model rows).
            mainResponseTotal = r?.agents?.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } ?: 0.0
            val infoJobs = if (r != null) buildInfoJobs(
                r, uiState.aiSettings, iconGenEnabled,
                uiState.generalSettings.reportLanguageOn(),
                uiState.generalSettings.reportTitleAiOn(),
                uiState.generalSettings.perModelIconOn(),
                uiState.generalSettings.perModelTitleOn(),
                uiState.generalSettings.metadataIcons
            ) else emptyList()
            infoEnabled = infoJobs.isNotEmpty()
            infoState = aggregateInfoState(infoJobs)
            // The info row's COST is the actual metadata spend, summed straight
            // from the persisted fields — NOT infoJobs.sumOf{cost}, which is
            // toggle-gated (a toggled-off category drops its already-spent cost,
            // making the info row and the grand total disagree with the
            // Report-costs screen). infoEnabled / infoState stay job-derived so
            // the row's status still reflects the active jobs. Same per-field
            // costs buildInfoJobs uses: report icon, language detect + icon,
            // report title short + long, per-model title, per-model icon, and
            // title alternative searches that live only in iconCalls.
            val titleAltTotal = r?.iconCalls
                ?.filter {
                    it.attributedToSecondaryId == null &&
                        it.type in setOf("alt/report_title", "alt/report_title_long", "alt/model_title")
                }
                ?.sumOf { it.inputCost + it.outputCost } ?: 0.0
            infoMetaTotal = reportIconCost + languageDetectCost + languageIconCost +
                ((r?.titleInputCost ?: 0.0) + (r?.titleOutputCost ?: 0.0) +
                    (r?.titleLongInputCost ?: 0.0) + (r?.titleLongOutputCost ?: 0.0)) +
                titleAltTotal + agentModelTitles.values.sumOf { it.cost } +
                agentIconRows.values.sumOf { it.cost }
            loadedReportPrompt = r?.prompt.orEmpty()
            loadedReportTitle = r?.barTitle
            loadedReportTimestamp = r?.timestamp ?: 0L
            loadedReportId = rid
        }
    }

    var secondaryRefreshTick by remember { mutableStateOf(0) }
    val onSecondaryRefresh: () -> Unit = { secondaryRefreshTick++ }

    // NOTE: opening a report no longer auto-resumes its interrupted work.
    // The old on-open passes — [onResumeStaleRuns] (translation / fan-out /
    // tournament / judges / single-meta) and a Fan Meta auto-relaunch — were
    // removed so the app only ever *detects* broken batches (the read-only
    // background scan publishes them to the ⚠️ top-bar badge); fixing is
    // always a manual action now (Regenerate / retry). The resume engines
    // stay available for those explicit actions. The reload LaunchedEffect
    // below (keyed on currentReportId) still refreshes the row list on open.

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
    // Fan-out runs whose delete is mid-flight — filtered out of the summaries
    // below so the row disappears the instant the user confirms, before the
    // (slow) per-pair disk deletes finish.
    val deletingFanOutRuns = fanOutEngine?.deletingRuns?.collectAsState()?.value ?: emptySet()
    // iconRefreshTick is in the key set so a per-row icon pick
    // (pickMetaRowIcon → setRowIcon writes to disk + bumps the tick)
    // reloads secondaryRuns from disk; without it the in-memory list
    // keeps the old SecondaryResult.icon value and the View tile +
    // Manage row never reflect the user's pick.
    LaunchedEffect(currentReportId, isComplete, uiState.activeSecondaryBatches, finishedSignature, secondaryRefreshTick, uiState.iconRefreshTick, deletingFanOutRuns, deletingTranslationRunIds) {
        val rid = currentReportId ?: run {
            secondaryCounts = SecondaryResultStorage.Counts(0, 0, 0, 0)
            secondaryRuns = emptyList()
            translateRows = emptyList()
            translationRunSummaries = emptyList()
            fanOutSummaries = emptyList()
            secondaryTotals = SecondaryTotals.ZERO
            secondEnabled = false
            secondState = InfoJobState.DONE
            secondTotal = 0.0
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
                    // Tournament matches + aggregate are collapsed into the
                    // single TournamentManageRow (→ Fan-Meta-style L1 drill-in),
                    // so they don't render as per-match rows here — mirrors how
                    // fan-out pairs are excluded below.
                    .filter { it.kind != SecondaryKind.TOURNAMENT }
                    // Judge-the-judges cells + aggregate collapse into the single
                    // JudgeEvalManageRow drill-in — same as tournament above.
                    .filter { it.kind != SecondaryKind.JUDGES }
                    // Compare-with-meta cells collapse into the single
                    // CompareManageRow drill-in — same as tournament above.
                    .filter { it.kind != SecondaryKind.COMPARE }
                    // Translator-rank cells + aggregate collapse into the single
                    // (per-language) TranslatorRankManageRow drill-in.
                    .filter { it.kind != SecondaryKind.TRANSRANK }
                    .filter { it.fanOutSourceAgentId == null }
                    .sortedByDescending { it.timestamp }
                translateRows = all.filter { it.kind == SecondaryKind.TRANSLATE }
                translationRunSummaries = buildTranslationRunSummaries(translateRows)
                    // Hide runs whose delete is in flight (rows still on disk).
                    .filter { it.runId !in deletingTranslationRunIds }
                fanOutSummaries = buildFanOutSummaries(
                    all.filter { row ->
                        if (row.fanOutSourceAgentId == null) return@filter false
                        // Hide runs whose delete is in flight (rows still on disk).
                        val pid = row.metaPromptId
                        pid == null || com.ai.data.runKey(rid, pid) !in deletingFanOutRuns
                    }
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
                    // Fan-meta bills title + icon to each pair's row; include
                    // both so the bar total matches the Report-costs screen
                    // (which counts the title half as its fan/meta rows).
                    fanOutMetaCost = all.sumOf {
                        it.iconInputCost + it.iconOutputCost + it.titleInputCost + it.titleOutputCost
                    }
                )
                // "second" row aggregate + summed cost — disk-based, like the
                // info row. Any unfinished cell (blank content, no error, no
                // duration) → ⏳; any error → ❌; else ✅. Cost = all secondary
                // spend (incl. fan-meta title+icon).
                secondEnabled = all.isNotEmpty()
                secondTotal = secondaryTotals.inputCost + secondaryTotals.outputCost +
                    secondaryTotals.fanOutMetaCost
                // A live (unfinished) translation run keeps the row at ⏳ even
                // in the windows where its rows aren't blank placeholders yet
                // (mid-build, or a restart about to clear errored rows).
                secondState = secondAggregate(
                    all,
                    liveTranslations = translationRuns.any { it.sourceReportId == rid && !it.isFinished }
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
        if (iconGenEnabled && currentReportId != null) reportIcon?.takeIf { it.isNotEmpty() } ?: com.ai.data.MetadataIconsHolder.current.reportIcon
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
        agentModelTitles = agentModelTitles,
        infoEnabled = infoEnabled,
        infoState = infoState,
        infoMetaTotal = infoMetaTotal,
        secondEnabled = secondEnabled,
        secondState = secondState,
        secondTotal = secondTotal,
        mainResponseTotal = mainResponseTotal,
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
    onGenerate: (List<ReportModel>, List<String>, ReportType, ReportWorkerConfig, Map<String, List<String>>) -> Unit,
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
            // External-intent reports auto-generate and skip the
            // select-workers step — they run with the default config.
            onGenerate(updatedModels, selectedParametersIds, type, ReportWorkerConfig(), emptyMap())
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
