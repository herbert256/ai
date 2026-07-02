package com.ai.ui.report.manage
import com.ai.ui.report.manage.view.*

import com.ai.ui.other.*
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
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Extracted from [ReportsScreen] to dodge the JVM 64 KB
 *  per-method bytecode limit. Mounts either the fan-in
 *  prompt picker overlay or the full [SecondaryResultsScreen]
 *  depending on flags. */
@Composable
internal fun SecondaryResultsListMount(
    reportId: String,
    openListKind: SecondaryKind,
    internalPrompts: List<InternalPrompt>,
    listFilterByName: String?,
    listIsFanMeta: Boolean = false,
    isBatching: Boolean,
    runningFanOutPairs: Set<String>,
    fanRuntime: FanRuntimeBundle,
    fanOutEngine: com.ai.viewmodel.FanOutEngine?,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    showFanInPromptPicker: Boolean,
    onShowFanInPromptPickerChange: (Boolean) -> Unit,
    onFanInPickerPromptChange: (InternalPrompt?) -> Unit,
    /** Captured from the parent fan-out run's [FanOutRunState.sourceLanguage]
     *  so the downstream picker can forward the language to runFanInPrompt
     *  Null when the fan-out ran on the original. */
    onFanInPickerSourceLanguageChange: (String?) -> Unit,
    onCloseList: () -> Unit,
    onShowResponses: () -> Unit,
    onShowFanMeta: () -> Unit = {},
    onCreateNewFanOut: () -> Unit = {},
    onSecondaryRefresh: () -> Unit,
    onCreateReportFromFanOut: (String, String, String) -> Unit,
    onDeleteSecondaryWithRefresh: (String, String) -> Unit,
    onBulkDeleteSecondaries: (String, List<String>, () -> Unit) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToTraceRunList: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onNavigateToInternalPromptEdit: (String) -> Unit,
    onNavigateToInternalPromptsByCategory: (String) -> Unit,
    onResumeStaleFanOut: (String, InternalPrompt) -> Unit,
    onRestartFailedFanOut: (String, InternalPrompt) -> Unit,
    onRemoveFailedFanOut: (String, InternalPrompt) -> Unit,
    onRestartFailedFanOutForModel: (String, InternalPrompt, String, String) -> Unit,
    onRemoveFailedFanOutForModel: (String, InternalPrompt, String, String) -> Unit,
    onRerunCompleteFanOut: (String, InternalPrompt) -> Unit,
    onRerunFanOutPair: (String, InternalPrompt, SecondaryResult) -> Unit,
    onDeleteFanOutModel: (String, String, String, String) -> Unit,
    forcedLanguage: String? = null,
    /** Plumbed all the way down to [FanOutActions.onOpenPairIconLookup]
     *  — set by the parent ReportsScreen to flip
     *  `pairIconDetailFor = pairId`. */
    onOpenPairIconLookup: (String) -> Unit = {},
    /** L3 META Find-alt — set by the parent to flip
     *  `pairIconDetailFor` / `pairTitleDetailFor` + open the picker. */
    onFindAlternativePairIcon: (String) -> Unit = {},
    onFindAlternativePairTitle: (String) -> Unit = {},
    /** Report-wide icon/title refresh tick — forwarded to the META L3. */
    iconRefreshTick: Int = 0,
) {
    val rid = reportId
    val fanInList = internalPrompts.filter { it.category == "fan_in" }
    val fanOutPrompt = if (openListKind == SecondaryKind.META && listFilterByName != null) {
        internalPrompts.firstOrNull {
            it.category == "fan_out" && it.name == listFilterByName
        }
    } else null
    // Parent fan-out's source language (null = Original). Read from
    // the engine's hydrated state so the language survives report
    // re-open. Forwarded to the parent at every fan-in trigger so
    // runFanInPrompt fires in the same language as the fan-out
    // being combined.
    val parentSourceLanguage: String? = remember(reportId, fanOutPrompt?.id, fanOutEngine) {
        val mp = fanOutPrompt ?: return@remember null
        val eng = fanOutEngine ?: return@remember null
        eng.runByKey(com.ai.data.runKey(reportId, mp.id))?.sourceLanguage
    }
    if (showFanInPromptPicker && fanInList.isNotEmpty()) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                onShowFanInPromptPickerChange(false)
                onCloseList()
            }
        ) {
            ReportSelectInternalPromptScreen(
                titleText = "Run an fan-in prompt",
                category = "fan_in",
                prompts = fanInList,
                onSelectPrompt = {
                    onShowFanInPromptPickerChange(false)
                    onFanInPickerPromptChange(it)
                },
                onBack = { onShowFanInPromptPickerChange(false) },
                onEditPrompts = {
                    onShowFanInPromptPickerChange(false)
                    onNavigateToInternalPromptsByCategory("fan_in")
                }
            )
        }
        return
    }
    CompositionLocalProvider(
        com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
        com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
        LocalNavigateToCurrentReport provides { onCloseList() }
    ) {
        SecondaryResultsScreen(
            reportId = rid,
            kind = openListKind,
            nameFilter = listFilterByName,
            isBatching = isBatching,
            runningFanOutPairs = runningFanOutPairs,
            fanRuntime = fanRuntime,
            onShowResponses = onShowResponses,
            onShowFanMeta = onShowFanMeta,
            onCreateNewFanOut = onCreateNewFanOut,
            isFanMetaDrillIn = listIsFanMeta,
            fanOutEngine = fanOutEngine,
            fanInPrompts = fanInList,
            fanOutPrompt = fanOutPrompt,
            onRunFanIn = if (fanInList.isNotEmpty()) {
                {
                    onFanInPickerSourceLanguageChange(parentSourceLanguage)
                    if (fanInList.size == 1) onFanInPickerPromptChange(fanInList.first())
                    else onShowFanInPromptPickerChange(true)
                }
            } else null,
            onCreateReportFromFanOut = { activePid, activeMdl ->
                onCloseList()
                onCreateReportFromFanOut(rid, activePid, activeMdl)
            },
            onDelete = { resultId -> onDeleteSecondaryWithRefresh(rid, resultId) },
            onBulkDelete = { ids ->
                onBulkDeleteSecondaries(rid, ids) { onSecondaryRefresh() }
            },
            // Re-scan secondaries on the way out so a fan-out just
            // deleted from L1 is gone from the report list, not
            // lingering until the next poll tick.
            onBack = { onSecondaryRefresh(); onCloseList() },
            onNavigateHome = onNavigateHome,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToTraceRunList = onNavigateToTraceRunList,
            onNavigateToModelInfo = onNavigateToModelInfo,
            onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
            onResumeStaleFanOut = { mp -> onResumeStaleFanOut(rid, mp) },
            onRestartFailedFanOut = { mp -> onRestartFailedFanOut(rid, mp) },
            onRemoveFailedFanOut = { mp ->
                onRemoveFailedFanOut(rid, mp)
                onSecondaryRefresh()
            },
            onRestartFailedFanOutForModel = { mp, prov, mdl ->
                onRestartFailedFanOutForModel(rid, mp, prov, mdl)
            },
            onRemoveFailedFanOutForModel = { mp, prov, mdl ->
                onRemoveFailedFanOutForModel(rid, mp, prov, mdl)
                onSecondaryRefresh()
            },
            onRerunCompleteFanOut = { mp ->
                onRerunCompleteFanOut(rid, mp)
                onSecondaryRefresh()
            },
            onRerunFanOutPair = { mp, pair ->
                onRerunFanOutPair(rid, mp, pair)
                onSecondaryRefresh()
            },
            onDeleteFanOutModel = { mpid, prov, model ->
                onDeleteFanOutModel(rid, mpid, prov, model)
                onSecondaryRefresh()
            },
            forcedLanguage = forcedLanguage,
            onOpenPairIconLookup = onOpenPairIconLookup,
            onFindAlternativePairIcon = onFindAlternativePairIcon,
            onFindAlternativePairTitle = onFindAlternativePairTitle,
            iconRefreshTick = iconRefreshTick
        )
    }
}

/** Meta-flow Run page — full-screen prompt editor between the Scope
 *  screen and the model picker. The InternalPrompt store is left
 *  untouched; the edited body rides along on a copy passed to the
 *  picker via [onContinue]. */
@Composable
internal fun MetaRunScreen(
    metaPrompt: InternalPrompt,
    onCancel: () -> Unit,
    onContinue: (InternalPrompt) -> Unit
) {
    BackHandler { onCancel() }
    var editablePrompt by remember(metaPrompt.id) { mutableStateOf(metaPrompt.text) }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_meta_run",
            title = "Run ${metaPrompt.name}", subject = "Tweak the prompt for this run only",
            onBackClick = onCancel
        )
        // Primary CTA hoisted to the top — one tap to advance
        // regardless of how far the editable prompt has scrolled.
        // Cancel is dropped: the existing BackHandler at the top of
        // this Composable routes Android back to onCancel, so a
        // separate button isn't pulling weight.
        OutlinedButton(
            onClick = { onContinue(metaPrompt.copy(text = editablePrompt)) },
            enabled = editablePrompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Continue", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Tweak the prompt for this run if you want; the saved Internal Prompt template stays untouched. Tap Continue to pick which model the meta runs on.",
                fontSize = 13.sp, color = AppColors.TextSecondary
            )
            Text("Prompt (edit for this run)", fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = editablePrompt,
                onValueChange = { editablePrompt = it },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = AppColors.TextPrimary),
                minLines = 8
            )
        }
        // (Cancel / Continue hoisted to the top — see above.)
    }
}

/** One prompt to edit on [SecondaryRuntimePromptScreen]: a field [label]
 *  and the [InternalPrompt] whose text is being tweaked. */
internal data class EditablePromptSpec(val label: String, val prompt: InternalPrompt)

/** Shared run-time prompt editor for the secondary kinds that honour the
 *  report's "Runtime parameters" toggle but have no dedicated run screen of
 *  their own — Compare, Fan-in, Tournament, Judge-the-judges, Translate and
 *  Rank-the-translators. One editable text field per [specs] entry (Translate
 *  passes its body + title prompts; every other kind passes one).
 *
 *  [onRun] receives the edited prompt copies in [specs] order and whether to
 *  persist them: **Run** edits for this run only (the saved Internal Prompt is
 *  untouched); **Update prompt & run** also writes the edits back permanently.
 *  Each edited copy keeps the original id / category / workers, so the engine's
 *  withBatchWorkers resolution and the on-disk run records are unaffected. */
@Composable
internal fun SecondaryRuntimePromptScreen(
    titleName: String,
    specs: List<EditablePromptSpec>,
    infoLine: String? = null,
    onCancel: () -> Unit,
    onRun: (edited: List<InternalPrompt>, persist: Boolean) -> Unit
) {
    BackHandler { onCancel() }
    // One editable buffer per spec, reseeded when the launch target changes.
    val editKey = specs.joinToString(",") { it.prompt.id }
    val fields = remember(editKey) { specs.map { mutableStateOf(it.prompt.text) } }
    val canRun = fields.all { it.value.isNotBlank() }
    fun edited() = specs.mapIndexed { i, s -> s.prompt.copy(text = fields[i].value) }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_runtime_prompt",
            title = "Run $titleName", subject = "Tweak the prompt for this run",
            onBackClick = onCancel
        )
        // Both CTAs hoisted to the top — one tap to advance regardless of how
        // far the editable prompt(s) have scrolled. Android back = onCancel.
        OutlinedButton(
            onClick = { onRun(edited(), false) },
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Run", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onRun(edited(), true) },
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Update prompt & run", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Edits here apply to this run only — the saved prompt template stays untouched. " +
                    "Tap \"Update prompt & run\" to also save the changes for future runs.",
                fontSize = 13.sp, color = AppColors.TextSecondary
            )
            if (infoLine != null) {
                Text(infoLine, fontSize = 12.sp, color = AppColors.TextTertiary)
            }
            specs.forEachIndexed { i, s ->
                Text("${s.label} (edit for this run)", fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = fields[i].value,
                    onValueChange = { fields[i].value = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AppColors.outlinedFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = AppColors.TextPrimary),
                    minLines = 8
                )
            }
        }
    }
}

/** Extracted from [ReportsScreen] to dodge the JVM 64 KB
 *  per-method bytecode limit. Renders the fan-out Run screen:
 *  call-count summary, initiator / responder model picker cards,
 *  then the editable per-run prompt at the bottom. */
@Composable
internal fun FanOutConfirmScreen(
    fanOutMp: InternalPrompt,
    reportId: String,
    context: android.content.Context,
    aiSettings: com.ai.model.Settings,
    letSelfRespond: Boolean,
    /** The Scope screen's choice — seeds the initiator card. Defaulting the
     *  initiators to ALL successful agents silently discarded a Top-ranked /
     *  Manual subset the user had just authorised (the runtime-params-OFF
     *  path honours the same scope directly). */
    scopeChoice: com.ai.data.SecondaryScope = com.ai.data.SecondaryScope.AllReports,
    onCancel: () -> Unit,
    onRun: (InternalPrompt, Set<String>, Set<String>, List<String>, String?) -> Unit
) {
    var pickedParamsIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickedSystemPromptId by remember { mutableStateOf<String?>(null) }
    var showSecParamsDialog by remember { mutableStateOf(false) }
    var showSecSystemPromptDialog by remember { mutableStateOf(false) }
    if (showSecParamsDialog) {
        com.ai.ui.shared.ParametersSelectScreen(
            aiSettings = aiSettings, selectedIds = pickedParamsIds,
            onConfirm = { pickedParamsIds = it },
            onBack = { showSecParamsDialog = false }, onNavigateHome = onCancel
        )
        return
    }
    if (showSecSystemPromptDialog) {
        com.ai.ui.shared.SystemPromptSelectScreen(
            aiSettings = aiSettings, selectedId = pickedSystemPromptId,
            onSelect = { pickedSystemPromptId = it },
            onBack = { showSecSystemPromptDialog = false }, onNavigateHome = onCancel
        )
        return
    }
    val successfulState = produceState<Pair<List<com.ai.data.ReportAgent>, Set<String>>?>(initialValue = null, reportId, scopeChoice) {
        value = withContext(Dispatchers.IO) {
            val agents = com.ai.data.ReportStorage.getReport(context, reportId)?.agents?.filter {
                it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
            }.orEmpty()
            // Resolve the Scope screen's choice into the initiator seed —
            // same resolution startRun applies (Top-ranked via the rerank
            // row's frozen snapshot).
            val scoped = when (scopeChoice) {
                com.ai.data.SecondaryScope.AllReports -> agents.map { it.agentId }.toSet()
                is com.ai.data.SecondaryScope.Manual ->
                    agents.map { it.agentId }.filter { it in scopeChoice.agentIds }.toSet()
                        .ifEmpty { agents.map { it.agentId }.toSet() }
                is com.ai.data.SecondaryScope.TopRanked -> {
                    val rerank = com.ai.data.SecondaryResultStorage.get(context, reportId, scopeChoice.rerankResultId)
                    com.ai.data.resolveTopRankedAgents(rerank, scopeChoice.count, agents)
                        .map { it.agentId }.toSet()
                        .ifEmpty { agents.map { it.agentId }.toSet() }
                }
            }
            agents to scoped
        }
    }
    val successful = successfulState.value?.first
    // Responders default to every successful agent so the natural
    // "everything-against-everything" run is one tap away; the initiators
    // seed from the chosen scope. Self-pairs are skipped at run time.
    val allIds = remember(successful) { successful?.map { it.agentId }?.toSet() ?: emptySet() }
    val scopedIds = successfulState.value?.second ?: emptySet()
    var selectedInitiators by remember(scopedIds) { mutableStateOf(scopedIds) }
    var selectedResponders by remember(allIds) { mutableStateOf(allIds) }
    // Per-run prompt edit — never written back to the InternalPrompt
    // store. Keyed on fanOutMp.id so switching prompts reseeds the
    // field with the new template.
    var editablePrompt by remember(fanOutMp.id) { mutableStateOf(fanOutMp.text) }
    // Off: skip self-pairs (a model reacting to its own answer). On: the
    // full initiators × responders matrix, self-pairs included.
    val pairCount = if (letSelfRespond) selectedInitiators.size * selectedResponders.size
        else selectedInitiators.sumOf { init ->
            selectedResponders.count { resp -> resp != init }
        }
    fun agentLabel(a: com.ai.data.ReportAgent): String =
        a.agentName.takeIf { it.isNotBlank() } ?: "${a.provider} · ${a.model}"
    BackHandler { onCancel() }
    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_fan_out_confirm",
            title = "Fan Out - run", subject = "Confirm the calls before fanning out",
            onBackClick = onCancel,
            onParameters = { showSecParamsDialog = true },
            onSystemPrompt = { showSecSystemPromptDialog = true }
        )
        // Primary CTA hoisted to the top — pairCount-gated Run sits
        // immediately under the TitleBar so it's reachable without
        // scrolling past the initiator / responder cards and the
        // editable per-run prompt. Cancel is dropped: the
        // BackHandler at the top of this Composable already routes
        // Android back to onCancel.
        OutlinedButton(
            onClick = {
                onRun(fanOutMp.copy(text = editablePrompt), selectedInitiators, selectedResponders, pickedParamsIds, pickedSystemPromptId)
            },
            enabled = pairCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Run", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Running ${fanOutMp.name} fires the prompt once per (responder, initiator) pair. Each call substitutes the initiator's response into @RESPONSE@ and sends the assembled prompt to the responder. " +
                    if (letSelfRespond) "Each model also reacts to its own answer." else "Self-pairs are skipped.",
                fontSize = 13.sp, color = AppColors.TextSecondary
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (successful == null) {
                        Text("Loading…", fontSize = 13.sp, color = AppColors.TextTertiary)
                    } else {
                        val gridText = "${selectedInitiators.size} initiator${if (selectedInitiators.size == 1) "" else "s"} × ${selectedResponders.size} responder${if (selectedResponders.size == 1) "" else "s"} = $pairCount call${if (pairCount == 1) "" else "s"}"
                        Text(
                            gridText, fontSize = 15.sp, color = AppColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Initiator + responder model picker cards — sit above the
            // editable prompt so the user picks WHO runs the prompt
            // before tweaking WHAT the prompt says.
            if (successful != null && successful.isNotEmpty()) {
                com.ai.ui.shared.CollapsibleCard(
                    title = "Initiator models for this Fan-Out (${selectedInitiators.size})",
                    icon = com.ai.data.MetadataDefaults.REPORT_ICON
                ) {
                    successful.forEach { agent ->
                        val checked = agent.agentId in selectedInitiators
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedInitiators = if (checked) selectedInitiators - agent.agentId
                                    else selectedInitiators + agent.agentId
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                agentLabel(agent), fontSize = 12.sp, color = AppColors.TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                com.ai.ui.shared.CollapsibleCard(
                    title = "Responder models for this Fan-out (${selectedResponders.size})",
                    icon = com.ai.data.MetadataDefaults.FAN_OUT
                ) {
                    successful.forEach { agent ->
                        val checked = agent.agentId in selectedResponders
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedResponders = if (checked) selectedResponders - agent.agentId
                                    else selectedResponders + agent.agentId
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                agentLabel(agent), fontSize = 12.sp, color = AppColors.TextPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Editable prompt at the bottom of the scroll body — the
            // edit lives only for this Run; the stored InternalPrompt
            // isn't touched.
            Text("Fan-out prompt (edit for this run)", fontSize = 13.sp, color = AppColors.InfoAccent, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = editablePrompt,
                onValueChange = { editablePrompt = it },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = AppColors.TextPrimary),
                minLines = 4
            )
        }
        // (Cancel / Run hoisted to the top — see above.)
    }
}

// ===== Language icon detail overlay =====
//
// Tiny single-overlay helper. Find Alternative Icons is not wired
// in this v1 cut — adding the picker + results overlays inline in
// ReportsScreen pushes it past the JVM 64 KB per-method bytecode
// limit. Returns true when the overlay rendered (caller early-
// returns); false when prompt / agent isn't configured.
