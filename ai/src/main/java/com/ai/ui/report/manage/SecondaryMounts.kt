package com.ai.ui.report.manage

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
    listIsFanIcons: Boolean,
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
     *  / runModelFanInPrompt. Null when the fan-out ran on the original. */
    onFanInPickerSourceLanguageChange: (String?) -> Unit,
    onModelFanInActiveChange: (String?, String?) -> Unit,
    onModelFanInPickerPromptChange: (InternalPrompt?) -> Unit,
    onCloseList: () -> Unit,
    onShowFanIcons: () -> Unit,
    onShowResponses: () -> Unit,
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
    onClearFanIconErrors: (reportId: String, metaPromptId: String) -> Unit = { _, _ -> },
    onRestartFanIconErrors: (reportId: String, metaPromptId: String) -> Unit = { _, _ -> }
) {
    val rid = reportId
    val fanInList = internalPrompts.filter { it.category == "fan_in" }
    val fanInModelList = internalPrompts.filter { it.category == "fan-in-model" }
    val fanOutPrompt = if (openListKind == SecondaryKind.META && listFilterByName != null) {
        internalPrompts.firstOrNull {
            it.category == "fan_out" && it.name == listFilterByName
        }
    } else null
    // Parent fan-out's source language (null = Original). Read from
    // the engine's hydrated state so the language survives report
    // re-open. Forwarded to the parent at every fan-in trigger so
    // runFanInPrompt / runModelFanInPrompt fire in the same language
    // as the fan-out being combined.
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
            onShowFanIcons = onShowFanIcons,
            onShowResponses = onShowResponses,
            isFanIconsDrillIn = listIsFanIcons,
            fanOutEngine = fanOutEngine,
            fanInPrompts = fanInList,
            fanInModelPrompts = fanInModelList,
            fanOutPrompt = fanOutPrompt,
            onRunFanIn = if (fanInList.isNotEmpty()) {
                {
                    onFanInPickerSourceLanguageChange(parentSourceLanguage)
                    if (fanInList.size == 1) onFanInPickerPromptChange(fanInList.first())
                    else onShowFanInPromptPickerChange(true)
                }
            } else null,
            onRunModelFanIn = { activePid, activeMdl ->
                onFanInPickerSourceLanguageChange(parentSourceLanguage)
                onModelFanInActiveChange(activePid, activeMdl)
                if (fanInModelList.size == 1) onModelFanInPickerPromptChange(fanInModelList.first())
            },
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
            onClearFanIconErrors = onClearFanIconErrors,
            onRestartFanIconErrors = onRestartFanIconErrors
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
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_meta_run",
            title = "Run ${metaPrompt.name}",
            onBackClick = onCancel
        )
        // Primary CTA hoisted to the top — one tap to advance
        // regardless of how far the editable prompt has scrolled.
        // Cancel is dropped: the existing BackHandler at the top of
        // this Composable routes Android back to onCancel, so a
        // separate button isn't pulling weight.
        Button(
            onClick = { onContinue(metaPrompt.copy(text = editablePrompt)) },
            enabled = editablePrompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
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
            Text("Prompt (edit for this run)", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = editablePrompt,
                onValueChange = { editablePrompt = it },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                minLines = 8
            )
        }
        // (Cancel / Continue hoisted to the top — see above.)
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
    onCancel: () -> Unit,
    onRun: (InternalPrompt, Set<String>, Set<String>) -> Unit
) {
    val successfulState = produceState<List<com.ai.data.ReportAgent>?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) {
            com.ai.data.ReportStorage.getReport(context, reportId)?.agents?.filter {
                it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
            }
        }
    }
    val successful = successfulState.value
    // Initiator / responder sets — both default to every successful
    // agent so the natural "everything-against-everything" run is one
    // tap away. Self-pairs are skipped at run time.
    val allIds = remember(successful) { successful?.map { it.agentId }?.toSet() ?: emptySet() }
    var selectedInitiators by remember(allIds) { mutableStateOf(allIds) }
    var selectedResponders by remember(allIds) { mutableStateOf(allIds) }
    // Per-run prompt edit — never written back to the InternalPrompt
    // store. Keyed on fanOutMp.id so switching prompts reseeds the
    // field with the new template.
    var editablePrompt by remember(fanOutMp.id) { mutableStateOf(fanOutMp.text) }
    val pairCount = selectedInitiators.sumOf { init ->
        selectedResponders.count { resp -> resp != init }
    }
    fun agentLabel(a: com.ai.data.ReportAgent): String =
        a.agentName.takeIf { it.isNotBlank() } ?: "${a.provider} · ${a.model}"
    BackHandler { onCancel() }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "report_fan_out_confirm",
            title = "Fan Out - run",
            onBackClick = onCancel
        )
        // Primary CTA hoisted to the top — pairCount-gated Run sits
        // immediately under the TitleBar so it's reachable without
        // scrolling past the initiator / responder cards and the
        // editable per-run prompt. Cancel is dropped: the
        // BackHandler at the top of this Composable already routes
        // Android back to onCancel.
        Button(
            onClick = {
                onRun(fanOutMp.copy(text = editablePrompt), selectedInitiators, selectedResponders)
            },
            enabled = pairCount > 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) { Text("Run", maxLines = 1, softWrap = false) }
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Running ${fanOutMp.name} fires the prompt once per (responder, initiator) pair. Each call substitutes the initiator's response into @RESPONSE@ and sends the assembled prompt to the responder. Self-pairs are skipped.",
                fontSize = 13.sp, color = AppColors.TextSecondary
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (successful == null) {
                        Text("Loading…", fontSize = 13.sp, color = AppColors.TextTertiary)
                    } else {
                        val gridText = "${selectedInitiators.size} initiator${if (selectedInitiators.size == 1) "" else "s"} × ${selectedResponders.size} responder${if (selectedResponders.size == 1) "" else "s"} = $pairCount call${if (pairCount == 1) "" else "s"}"
                        Text(
                            gridText, fontSize = 15.sp, color = Color.White,
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
                    title = "Initiator models for this Fan-Out (${selectedInitiators.size})"
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
                                agentLabel(agent), fontSize = 12.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                com.ai.ui.shared.CollapsibleCard(
                    title = "Responder models for this Fan-out (${selectedResponders.size})"
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
                                agentLabel(agent), fontSize = 12.sp, color = Color.White,
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
            Text("Fan-out prompt (edit for this run)", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = editablePrompt,
                onValueChange = { editablePrompt = it },
                modifier = Modifier.fillMaxWidth(),
                colors = AppColors.outlinedFieldColors(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
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

