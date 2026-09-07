package com.ai.ui.report.manage

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.InternalPromptIconCache
import com.ai.data.MetadataIconsHolder
import com.ai.data.PricingCache
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.secondaryPromptDisplayName
import com.ai.model.InternalPrompt
import com.ai.model.Settings
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.TranslationRunState
import com.ai.viewmodel.UiState

/** Aggregate status for the collapsed Manage **second** row, mirroring
 *  [aggregateInfoState]: ❌ if any secondary errored, else ⏳ while any cell is
 *  still unfinished (or a translation is live), else ✅. Computed over the FULL
 *  secondary list (every kind, including the per-cell tournament/judges/compare/
 *  transrank/fan-out rows), so a still-running batch keeps the row spinning. */
fun secondAggregate(all: List<SecondaryResult>, liveTranslations: Boolean): InfoJobState = when {
    all.any { it.errorMessage != null } -> InfoJobState.FAILED
    liveTranslations ||
        all.any { it.errorMessage == null && it.content.isNullOrBlank() && it.durationMs == null } ->
        InfoJobState.RUNNING
    else -> InfoJobState.DONE
}

/**
 * The "Report - second results" status screen — the secondary-result analogue of
 * "Report - Get info". Layered over the Manage hub (`publishBottomBar = false`,
 * so Manage keeps its own bottom bar + total); lists every secondary result
 * (Tournament / Judges / Compare / Rank-the-translators batch rows, then the
 * individual Meta / Rerank / Moderation / Fan-in rows, the Fan-out + Fan-meta
 * rows, and the live + finished Translation rows) with the shared Manage icons
 * and per-row costs. Each row opens its existing detail / overlay; Back peels
 * just this layer. The four `*ManageRow` composables are self-contained (they
 * read the engine + open-state CompositionLocals provided by ReportRunScreen).
 */
@Composable
internal fun ReportSecondResultsScreen(
    reportId: String,
    uiState: UiState,
    aiSettings: Settings,
    secondaryRuns: List<SecondaryResult>,
    fanOutSummaries: List<FanOutRunSummary>,
    translationRuns: List<TranslationRunState>,
    translationRunSummaries: List<TranslationRunSummary>,
    languageName: String?,
    /** The report's long title — the orange subtitle line (same as on the
     *  other two report screens) and the "report" row text. */
    reportTitle: String,
    /** The hub's live running total for the stats line (see [ReportStatsLine]). */
    costDollars: Double,
    /** Stats-line tap → the report's costs screen. */
    onViewCosts: (() -> Unit)? = null,
    /** Combined main-response cost of every model — the "report" row's cost. */
    reportCost: Double = 0.0,
    /** Gate + aggregate state + total for the "info" cross-link row (same
     *  gate as the Manage hub's info row). */
    infoEnabled: Boolean = false,
    infoState: InfoJobState = InfoJobState.DONE,
    infoMetaTotal: Double = 0.0,
    /** "report" row tap → the Manage hub. */
    onGoManage: () -> Unit = {},
    /** "info" row tap → the Get-info screen. */
    onGoInfo: () -> Unit = {},
    showModelNamesInReportRows: Boolean,
    onOpenSecondaryRun: (String) -> Unit,
    onMissingPromptIcon: (InternalPrompt) -> Unit,
    onOpenInternalPromptIconDetail: (InternalPrompt) -> Unit,
    onOpenInternalPromptIconDetailForRow: (InternalPrompt, String) -> Unit,
    onViewSecondaryName: (String, SecondaryKind) -> Unit,
    onViewFanMeta: (String) -> Unit,
    onOpenTranslationRun: (String) -> Unit,
    onMissingTranslationIcon: (String) -> Unit,
    onOpenTranslationIconDetail: (String) -> Unit,
    onBack: () -> Unit,
    /** Title tap → next of the three report screens (wraps to Manage). */
    onCycleNext: () -> Unit = {},
    /** Report-icon tap → the View hub. */
    onOpenViewHub: () -> Unit = {},
    /** Bulk-delete the picked standalone secondary rows (the individual
     *  Meta / Rerank / Moderation / Fan-in rows — batch cells have their
     *  own run screens). Wired to the VM's bulkDeleteSecondaryResults. */
    onBulkDelete: (List<String>) -> Unit = {}
) {
    // Multi-select over the standalone secondary rows: long-press a row
    // to enter; Back exits selection first.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    fun exitSelection() { selectionMode = false; selectedIds = emptySet() }
    BackHandler { if (selectionMode) exitSelection() else onBack() }
    val context = LocalContext.current
    // Status filter + sort for the standalone rows (batch rows keep their
    // own screens). "running" mirrors the row renderer's own predicate.
    var statusFilter by remember { mutableStateOf("all") }
    var costSort by remember { mutableStateOf(false) }
    val filteredRuns = remember(secondaryRuns, statusFilter, costSort) {
        val f = when (statusFilter) {
            "error" -> secondaryRuns.filter { it.errorMessage != null }
            "running" -> secondaryRuns.filter {
                it.errorMessage == null && it.content.isNullOrBlank() && it.durationMs == null
            }
            "done" -> secondaryRuns.filter {
                it.errorMessage == null && !(it.content.isNullOrBlank() && it.durationMs == null)
            }
            else -> secondaryRuns
        }
        if (costSort) f.sortedByDescending { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } else f
    }
    val activeTranslationRuns = remember(translationRuns) {
        translationRuns.filter { !it.isFinished && !it.cancelled }
    }
    val activeTranslationRunIds = remember(activeTranslationRuns) {
        activeTranslationRuns.map { it.runId }.toSet()
    }
    val visibleTranslationSummaries = remember(translationRunSummaries, activeTranslationRunIds) {
        translationRunSummaries.filter { it.runId !in activeTranslationRunIds }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_second_results",
            title = "Report - second results",
            // Orange line = the report's long title, same as the other two
            // report screens, so the three flip without the line changing.
            subject = reportTitle,
            onBackClick = onBack,
            onTitleClick = onCycleNext,
            forceTitleClick = true,
            onReportIconClick = onOpenViewHub,
            publishBottomBar = false
        )
        // Statistics line — api calls / total API time / running cost —
        // same numbers as the hub's own line; tap → costs screen.
        ReportStatsLine(
            reportId = reportId,
            costDollars = costDollars,
            refreshKey = costDollars,
            onClick = onViewCosts
        )
        // Status filter chips + cost sort for the standalone rows.
        if (secondaryRuns.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("all" to "All", "done" to "Done", "error" to "Error", "running" to "Run").forEach { (v, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = statusFilter == v,
                        onClick = { statusFilter = if (statusFilter == v) "all" else v },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { costSort = !costSort }) {
                    Text(if (costSort) "Cost ↓" else "Newest", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        // Multi-select header — visible while selection mode is on
        // (entered by long-pressing a standalone secondary row).
        if (selectionMode) {
            var confirmDeleteSelected by remember { mutableStateOf(false) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${selectedIds.size} selected", fontSize = 13.sp, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { selectedIds = secondaryRuns.mapTo(HashSet()) { it.id } }) {
                    Text("All", fontSize = 13.sp, maxLines = 1)
                }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { confirmDeleteSelected = true }
                ) { Text("Delete", fontSize = 13.sp, color = AppColors.DangerAccent, maxLines = 1) }
                TextButton(onClick = { exitSelection() }) { Text("Done", fontSize = 13.sp, maxLines = 1) }
            }
            if (confirmDeleteSelected) {
                AlertDialog(
                    onDismissRequest = { confirmDeleteSelected = false },
                    title = { Text("Delete ${selectedIds.size} secondary result(s)?") },
                    text = { Text("Permanently removes the selected Meta / Rerank / Moderation / Fan-in rows from this report. Their spend moves to the costs-from-deleted-items bank. This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDeleteSelected = false
                            val ids = selectedIds.toList()
                            exitSelection()
                            onBulkDelete(ids)
                        }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel", maxLines = 1) }
                    }
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Top-of-list divider — same 1 dp cap the Manage list paints.
            item(key = "top-divider") {
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
            // Cross-link rows to the other two report screens: the report
            // itself (Manage hub) and the Get-info aggregate (same gate as
            // the Manage hub's info row).
            item(key = "row-reports") {
                ReportsSummaryRow(cost = reportCost, onClick = onGoManage)
            }
            if (infoEnabled || infoMetaTotal > 0.0) {
                item(key = "row-info") {
                    InfoSummaryRow(
                        infoState,
                        doneIcon = com.ai.ui.shared.LocalReportIcon.current,
                        cost = infoMetaTotal,
                        onClick = onGoInfo
                    )
                }
            }
            item(key = "tournament-batch-row") { TournamentManageRow() }
            item(key = "judge-eval-batch-row") { JudgeEvalManageRow() }
            item(key = "compare-batch-row") { CompareManageRow() }
            item(key = "translator-rank-batch-row") { TranslatorRankManageRow() }
            secondaryResultRows(
                secondaryRuns = filteredRuns,
                fanOutSummaries = fanOutSummaries,
                activeTranslationRuns = activeTranslationRuns,
                visibleTranslationSummaries = visibleTranslationSummaries,
                uiState = uiState,
                aiSettings = aiSettings,
                context = context,
                languageName = languageName,
                showModelNamesInReportRows = showModelNamesInReportRows,
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                onToggleSelect = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                onEnterSelection = { id ->
                    selectionMode = true
                    selectedIds = setOf(id)
                },
                onOpenSecondaryRun = onOpenSecondaryRun,
                onMissingPromptIcon = onMissingPromptIcon,
                onOpenInternalPromptIconDetail = onOpenInternalPromptIconDetail,
                onOpenInternalPromptIconDetailForRow = onOpenInternalPromptIconDetailForRow,
                onViewSecondaryName = onViewSecondaryName,
                onViewFanMeta = onViewFanMeta,
                onOpenTranslationRun = onOpenTranslationRun,
                onMissingTranslationIcon = onMissingTranslationIcon,
                onOpenTranslationIconDetail = onOpenTranslationIconDetail
            )
        }
    }
}

/**
 * The individual secondary-result rows — moved verbatim out of GenerationPhase's
 * LazyColumn so both the (now-collapsed) Manage list no longer renders them and
 * the "Report - second results" screen can. Params carry the names the moved
 * body already used, so the row logic is unchanged.
 */
internal fun LazyListScope.secondaryResultRows(
    secondaryRuns: List<SecondaryResult>,
    fanOutSummaries: List<FanOutRunSummary>,
    activeTranslationRuns: List<TranslationRunState>,
    visibleTranslationSummaries: List<TranslationRunSummary>,
    uiState: UiState,
    aiSettings: Settings,
    context: Context,
    languageName: String?,
    showModelNamesInReportRows: Boolean,
    /** Multi-select over the standalone secondary rows ("Report - second
     *  results" only; the collapsed Manage list passes the defaults). */
    selectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (String) -> Unit = {},
    onEnterSelection: ((String) -> Unit)? = null,
    onOpenSecondaryRun: (String) -> Unit,
    onMissingPromptIcon: (InternalPrompt) -> Unit,
    onOpenInternalPromptIconDetail: (InternalPrompt) -> Unit,
    onOpenInternalPromptIconDetailForRow: (InternalPrompt, String) -> Unit,
    onViewSecondaryName: (String, SecondaryKind) -> Unit,
    onViewFanMeta: (String) -> Unit,
    onOpenTranslationRun: (String) -> Unit,
    onMissingTranslationIcon: (String) -> Unit,
    onOpenTranslationIconDetail: (String) -> Unit
) {
    val showSecondaryRuns = secondaryRuns.isNotEmpty()
    val showFanOutSummaries = fanOutSummaries.isNotEmpty()
    val showLiveTranslations = activeTranslationRuns.isNotEmpty()
    val showTranslationSummaries = visibleTranslationSummaries.isNotEmpty()

    // Meta runs — one row per individual rerank / summarize / compare /
    // moderation result. Tapping opens that run's detail screen.
    if (showSecondaryRuns) {
        items(secondaryRuns, key = { "sr-${it.id}" }) { run ->
            val running = run.errorMessage == null &&
                run.content.isNullOrBlank() &&
                run.durationMs == null
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .combinedClickable(
                        onClick = {
                            if (selectionMode) onToggleSelect(run.id)
                            else onOpenSecondaryRun(run.id)
                        },
                        onLongClick = if (onEnterSelection != null && !selectionMode)
                            ({ onEnterSelection(run.id) }) else null
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    Checkbox(checked = run.id in selectedIds, onCheckedChange = { onToggleSelect(run.id) })
                }
                val resolvedPrompt = remember(
                    run.fanInOf, run.metaPromptId,
                    aiSettings.internalPrompts.map { "${it.id}|${it.name}|${it.title}" }
                ) {
                    aiSettings.internalPrompts.firstOrNull {
                        it.id == run.fanInOf || it.id == run.metaPromptId
                    }
                }
                when {
                    run.errorMessage != null -> Text(MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    running -> {
                        val transition = rememberInfiniteTransition(label = "meta-run-${run.id}")
                        val angle by transition.animateFloat(
                            initialValue = 0f, targetValue = 360f,
                            animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing)),
                            label = "meta-run-rot-${run.id}"
                        )
                        Text("⏳", fontSize = 16.sp, modifier = Modifier.width(24.dp).rotate(angle))
                    }
                    else -> {
                        val rowIcon = run.icon?.takeIf { it.isNotBlank() }
                        val emoji = if (
                            uiState.generalSettings.useInternalPromptsIcons &&
                            resolvedPrompt != null &&
                            resolvedPrompt.name.isNotBlank()
                        ) {
                            val tick = uiState.iconRefreshTick
                            remember(
                                resolvedPrompt.id, resolvedPrompt.name,
                                resolvedPrompt.title, tick, rowIcon
                            ) {
                                if (rowIcon != null) {
                                    rowIcon
                                } else {
                                    val cached = InternalPromptIconCache
                                        .get(resolvedPrompt.name, resolvedPrompt.title)
                                    if (cached == null) onMissingPromptIcon(resolvedPrompt)
                                    cached
                                }
                            }
                        } else rowIcon
                        if (emoji != null) {
                            // resolvedPrompt can be null here (e.g. a rowIcon
                            // override survives its source Internal Prompt
                            // being deleted) — only wire the tap when there's
                            // a prompt to navigate to, so a stale override
                            // still renders but never crashes on tap.
                            Text(
                                emoji, fontSize = 16.sp,
                                modifier = Modifier
                                    .width(24.dp)
                                    .let { base ->
                                        if (resolvedPrompt != null) base.clickable {
                                            onOpenInternalPromptIconDetailForRow(resolvedPrompt, run.id)
                                        } else base
                                    }
                            )
                        } else {
                            val mi = com.ai.ui.shared.LocalMetadataIcons.current
                            val fallback = if (run.fanInOf != null) mi.fanInRow else mi.forKind(run.kind)
                            Text(fallback, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        }
                    }
                }
                val typeLabel = when {
                    run.kind == SecondaryKind.TOURNAMENT ->
                        if (run.tournamentRole == "MATCH") "tournament-match" else "tournament"
                    run.fanInOf != null -> "fan-in"
                    run.metaPromptName?.isNotBlank() == true ->
                        secondaryPromptDisplayName(run.metaPromptName).lowercase()
                    else -> when (run.kind) {
                        SecondaryKind.RERANK -> "rerank"
                        SecondaryKind.META -> "meta"
                        SecondaryKind.MODERATION -> "moderate"
                        SecondaryKind.TRANSLATE -> "translate"
                        SecondaryKind.TOURNAMENT -> "tournament"
                        SecondaryKind.JUDGES -> "judges"
                        SecondaryKind.COMPARE -> "compare"
                        SecondaryKind.TRANSRANK -> "transrank"
                    }
                }
                RowTypeCell(typeLabel)
                // secondaryRuns is built with TRANSLATE rows filtered OUT, so
                // `any { TRANSLATE }` was always false (the "· language" suffix
                // never rendered). Use the translation summaries/runs that ARE
                // passed in as the has-translations signal.
                val hasAnyTranslation = visibleTranslationSummaries.isNotEmpty() || activeTranslationRuns.isNotEmpty()
                val langSuffix = when {
                    run.targetLanguage != null -> " · ${run.targetLanguage}"
                    hasAnyTranslation && !languageName.isNullOrBlank() -> " · $languageName"
                    else -> ""
                }
                Column(modifier = Modifier.weight(1f)) {
                    val text = if (run.fanInOf != null) {
                        val prompt = aiSettings.internalPrompts.firstOrNull {
                            it.id == run.fanInOf || it.id == run.metaPromptId
                        }
                        val label = prompt?.title?.takeIf { it.isNotBlank() }
                            ?: prompt?.name?.takeIf { it.isNotBlank() }
                            ?: run.metaPromptName?.takeIf { it.isNotBlank() }
                            ?: run.let {
                                val runProv = AppService.findById(it.providerId)?.id ?: it.providerId
                                com.ai.ui.shared.modelLabel(runProv, it.model)
                            }
                        "$label$langSuffix"
                    } else {
                        val runProv = AppService.findById(run.providerId)?.id ?: run.providerId
                        val togglable = run.kind == SecondaryKind.META ||
                            run.kind == SecondaryKind.RERANK || run.kind == SecondaryKind.MODERATION
                        if (togglable && !showModelNamesInReportRows) {
                            val prompt = aiSettings.internalPrompts.firstOrNull {
                                it.id == run.metaPromptId || it.name == run.metaPromptName
                            }
                            val nativeRerank = run.kind == SecondaryKind.RERANK && AppService.findById(run.providerId)?.let {
                                aiSettings.getModelType(it, run.model) == com.ai.data.ModelType.RERANK
                            } == true
                            val title = (if (nativeRerank) "Rank responses by question relevance" else prompt?.title?.takeIf { it.isNotBlank() })
                                ?: run.metaPromptName?.takeIf { it.isNotBlank() }?.let { secondaryPromptDisplayName(it) }
                                ?: when (run.kind) {
                                    SecondaryKind.RERANK -> "Rerank"
                                    SecondaryKind.MODERATION -> "Moderation"
                                    else -> "Meta"
                                }
                            "$title$langSuffix"
                        } else {
                            "${com.ai.ui.shared.modelLabel(runProv, run.model)}$langSuffix"
                        }
                    }
                    Text(
                        text,
                        fontSize = 13.sp, color = AppColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                val totalCost = remember(
                    run.id, run.inputCost, run.outputCost, run.tokenUsage
                ) {
                    val persisted = (run.inputCost ?: 0.0) + (run.outputCost ?: 0.0)
                    if (persisted > 0.0) persisted else {
                        val tu = run.tokenUsage
                        val prov = AppService.findById(run.providerId)
                        if (tu != null && prov != null) {
                            val pr = PricingCache.getPricing(context, prov, run.model)
                            tu.inputTokens * pr.promptPrice + tu.outputTokens * pr.completionPrice
                        } else 0.0
                    }
                }
                // A finished run with 0 cost was a cache hit — show the 0,
                // like the per-job rows on Get-info. Running / errored rows
                // stay blank (no settled cost yet).
                if (totalCost > 0.0 || (!running && run.errorMessage == null)) {
                    Text(formatCents(totalCost), fontSize = 10.sp,
                        color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }
    }

    // Fan-out summary rows — one per Meta-prompt name with at least one
    // fan-out pair (or fan_in combine-reports) row on disk. Tap →
    // SecondaryResultsScreen, which renders fan-out rows via FanOutDrillInView.
    if (showFanOutSummaries) {
        items(fanOutSummaries, key = { "cm-${it.metaPromptName}" }) { run ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    onViewSecondaryName(run.metaPromptName, run.kind)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fanOutPrompt = remember(
                    run.metaPromptName,
                    aiSettings.internalPrompts.map { "${it.id}|${it.name}|${it.title}" }
                ) {
                    aiSettings.internalPrompts.firstOrNull {
                        it.category == "fan_out" && it.name == run.metaPromptName
                    }
                }
                when {
                    run.pendingCount > 0 -> Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        AnimatedHourglass(fontSize = 16.sp)
                    }
                    run.errorCount > 0 -> Text(MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    else -> {
                        val emoji = if (
                            uiState.generalSettings.useInternalPromptsIcons &&
                            fanOutPrompt != null &&
                            fanOutPrompt.name.isNotBlank()
                        ) {
                            val tick = uiState.iconRefreshTick
                            remember(
                                fanOutPrompt.id, fanOutPrompt.name,
                                fanOutPrompt.title, tick
                            ) {
                                val cached = InternalPromptIconCache
                                    .get(fanOutPrompt.name, fanOutPrompt.title)
                                if (cached == null) onMissingPromptIcon(fanOutPrompt)
                                cached
                            }
                        } else null
                        if (emoji != null) {
                            Text(
                                emoji, fontSize = 16.sp,
                                modifier = Modifier
                                    .width(24.dp)
                                    .clickable { onOpenInternalPromptIconDetail(fanOutPrompt!!) }
                            )
                        } else {
                            Text(com.ai.ui.shared.LocalMetadataIcons.current.fanOutRow, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        }
                    }
                }
                RowTypeCell("fan-out")
                val fanOutLabel = fanOutPrompt?.title?.takeIf { it.isNotBlank() } ?: run.metaPromptName
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        fanOutLabel,
                        fontSize = 13.sp, color = AppColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (run.totalCost > 0.0) {
                    Text(formatCents(run.totalCost), fontSize = 10.sp,
                        color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)

            // Sibling "fan-meta" row — title + icon for the run's pairs.
            if (run.titleCount > 0 || run.iconCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        onViewFanMeta(run.metaPromptName)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        run.titlePendingCount > 0 -> Box(
                            modifier = Modifier.width(24.dp),
                            contentAlignment = Alignment.Center
                        ) { AnimatedHourglass(fontSize = 16.sp) }
                        run.titleErrorCount > 0 -> Text(MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        else -> Text(MetadataIconsHolder.current.label, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    }
                    RowTypeCell("fan-meta")
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${run.metaPromptName} · ${run.titleCount} title${if (run.titleCount == 1) "" else "s"}",
                            fontSize = 13.sp, color = AppColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val fanMetaCost = run.titleCost + run.iconCost
                    if (fanMetaCost > 0.0) {
                        Text(formatCents(fanMetaCost), fontSize = 10.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }

    // Live translation rows — one per active run.
    if (showLiveTranslations) {
        items(activeTranslationRuns, key = { "tr-live-${it.runId}" }) { run ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { onOpenTranslationRun(run.runId) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    AnimatedHourglass(fontSize = 16.sp)
                }
                RowTypeCell("translate")
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        run.targetLanguageName.ifBlank { "Translate" },
                        fontSize = 13.sp, color = AppColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (run.totalCostDollars > 0.0) {
                    Text(formatCents(run.totalCostDollars), fontSize = 10.sp,
                        color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }
    }

    // Translation runs — one row per finished Translate invocation.
    if (showTranslationSummaries) {
        items(visibleTranslationSummaries, key = { "trs-${it.runId}" }) { run ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenTranslationRun(run.runId) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    run.errorCount > 0 -> Text(MetadataIconsHolder.current.statusFailed, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                    else -> {
                        val lang = run.targetLanguage
                        val emoji = if (
                            uiState.generalSettings.useInternalPromptsIcons &&
                            !lang.isNullOrBlank()
                        ) {
                            val tick = uiState.iconRefreshTick
                            remember(lang, tick) {
                                val cached = InternalPromptIconCache
                                    .get("translation_icon", lang)
                                if (cached == null) onMissingTranslationIcon(lang)
                                cached
                            }
                        } else null
                        if (emoji != null && lang != null) {
                            Text(
                                emoji, fontSize = 16.sp,
                                modifier = Modifier
                                    .width(24.dp)
                                    .clickable { onOpenTranslationIconDetail(lang) }
                            )
                        } else {
                            Text(MetadataIconsHolder.current.statusDone, fontSize = 16.sp, modifier = Modifier.width(24.dp))
                        }
                    }
                }
                RowTypeCell("translate")
                val lang = run.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translate"
                val info = "$lang - ${run.callCount} item${if (run.callCount == 1) "" else "s"}"
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        info,
                        fontSize = 13.sp, color = AppColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (run.totalCost > 0.0) {
                    Text(formatCents(run.totalCost), fontSize = 10.sp,
                        color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        }
    }
}
