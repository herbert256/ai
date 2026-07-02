package com.ai.ui.report.manage
import com.ai.ui.report.manage.view.*
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
import com.ai.viewmodel.PromptEditReplayState
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.ReasoningEffortSweepState
import com.ai.viewmodel.TemperatureSweepState
import com.ai.viewmodel.WebSearchReplayState
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReportPrimaryOverlays(
    showIconsView: Boolean,
    singleResultAgentId: String?,
    currentReportId: String?,
    effectiveReportIcon: String?,
    loadedReportTitle: String?,
    showViewReportScreen: Boolean,
    showViewer: Boolean,
    htmlPreviewDetail: ReportExportDetail?,
    openMetaResultId: String?,
    openTranslationRunId: String?,
    listKind: SecondaryKind?,
    secondaryRuns: List<com.ai.data.SecondaryResult>,
    translateRows: List<com.ai.data.SecondaryResult>,
    /** Fan-out runs on this report (grouped pair rows). Drives the
     *  per-fan-out tiles on Report - view; pair rows are excluded
     *  from secondaryRuns so they need a separate channel. */
    fanOutSummaries: List<FanOutRunSummary>,
    aiSettings: Settings,
    uiState: UiState,
    promptIconCallbacks: InternalPromptIconCallbacks,
    translationIconCallbacks: TranslationIconCallbacks,
    loadedReportTimestamp: Long,
    selectedAgentForViewer: String?,
    viewerSection: String?,
    agentIconDetailFor: String?,
    /** When non-null, the Edit-model-title overlay
     *  (ReportManageActionOverlays) owns the screen. Gated into the
     *  single-result guard below so tapping the title on the Model
     *  response screen yields to that overlay (and Back returns here),
     *  exactly like [agentIconDetailFor] does for Icon lookup. */
    editModelTitleFor: String?,
    showAdvancedParameters: Boolean,
    advancedParameters: AgentParameters?,
    /** Locked language carried into ReportsViewerScreen when the
     *  View page's top picker is set; null on Report - Manage path. */
    viewerLockedLanguage: String?,
    onViewerLockedLanguageChange: (String?) -> Unit,
    onSecondaryLockedLanguageChange: (String?) -> Unit,
    onListLockedLanguageChange: (String?) -> Unit,
    onShowIconsViewChange: (Boolean) -> Unit,
    onSingleResultAgentIdChange: (String?) -> Unit,
    onShowViewReportScreenChange: (Boolean) -> Unit,
    onShowViewerChange: (Boolean) -> Unit,
    onViewerSectionChange: (String?) -> Unit,
    onSelectedAgentForViewerChange: (String?) -> Unit,
    onHtmlPreviewDetailChange: (ReportExportDetail?) -> Unit,
    onOpenMetaResultIdChange: (String?) -> Unit,
    onOpenTranslationRunIdChange: (String?) -> Unit,
    onListTargetChange: (SecondaryKind?, String?) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTrace: (String) -> Unit,
    onNavigateToAppLog: (String, String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onContinueWithCurrent: (String, String) -> Unit,
    onContinueWithAgentPicker: (String, String) -> Unit,
    onContinueWithOnTheFly: (String, String) -> Unit,
    onRemoveAgent: (String, String) -> Unit,
    onRegenerateAgent: (String, String) -> Unit,
    temperatureSweepStates: Map<String, TemperatureSweepState>,
    onStartTemperatureSweep: (String, String, List<Float>) -> Unit,
    onApplyTemperatureCandidate: (String, String, Int) -> Unit,
    onClearTemperatureSweep: (String, String) -> Unit,
    reasoningEffortSweepStates: Map<String, ReasoningEffortSweepState>,
    onStartReasoningEffortSweep: (String, String, List<String?>) -> Unit,
    onApplyReasoningEffortCandidate: (String, String, Int) -> Unit,
    onClearReasoningEffortSweep: (String, String) -> Unit,
    webSearchReplayStates: Map<String, WebSearchReplayState>,
    onStartWebSearchReplay: (String, String) -> Unit,
    onApplyWebSearchReplay: (String, String) -> Unit,
    onClearWebSearchReplay: (String, String) -> Unit,
    promptEditReplayStates: Map<String, PromptEditReplayState>,
    onStartPromptEditReplay: (String, String, String, List<String>, String?) -> Unit,
    onApplyPromptEditReplay: (String, String) -> Unit,
    onClearPromptEditReplay: (String, String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    onOpenAgentIcon: (String) -> Unit,
    /** Open Edit-model-title for the given agent — sets the manage
     *  flow's editModelTitleFor. The Model response title taps here. */
    onEditModelTitle: (String) -> Unit,
    onSecondaryRefresh: () -> Unit,
    /** Detected source-language display name (e.g. "English") from
     *  the report. Threaded into buildEveryItems so a META back-
     *  translation TO this language counts toward the meta tile's
     *  Original-language availability. Null when language detection
     *  hasn't run yet — the fold then no-ops. */
    reportLanguageName: String?,
    /** Per-SecondaryResult-id deletion used by the multi-language
     *  delete popup's "Active language only" branch on
     *  ReportSingleResultScreen. Wired to onDeleteSecondaryWithRefresh
     *  at the ReportsScreen call site. */
    onDeleteSecondaryRowById: (reportId: String, resultId: String) -> Unit,
    onAdvancedParametersChange: (AgentParameters?) -> Unit,
    onParametersIdsChange: (List<String>) -> Unit,
    onShowAdvancedParametersChange: (Boolean) -> Unit,
    /** Wired by ReportsScreenNav to ReportViewModel.translateMissingItems.
     *  Fired when the View screen's "Language missing" popup picks a
     *  source language; dispatches a one-off translation of the
     *  supplied items into the active target language. */
    onTranslateMissingItems: (reportId: String,
                              items: List<com.ai.viewmodel.TranslateMissingItem>,
                              targetLanguageName: String,
                              targetLanguageNative: String) -> Unit,
    /** When non-null, mounts [FanOutViewScreen] — the content-only
     *  variant of the fan-out drill-in reached from the Report - view
     *  tile. The management-heavy [FanOutScreen] reached from Report
     *  - manage stays unchanged. */
    fanOutViewName: String?,
    fanOutViewLanguage: String?,
    onOpenFanOutView: (metaPromptName: String, language: String?) -> Unit,
    onCloseFanOutView: () -> Unit
): Boolean {
    // Holder for the Manage tournament drill-in — set by a
    // ManageJump.Tournament so a View tournament page's 🔧 lands on the
    // Manage Tournament page. Read here (composable scope) so both
    // LocalOpenManage handlers below can capture it.
    val tournamentOpenHolder = com.ai.ui.shared.LocalTournamentOpenState.current
    // Manage → View 👁 jump. This block runs BEFORE every other
    // overlay so the requested View sub-screen renders on top of
    // whatever Manage overlay is otherwise active. The Manage flag
    // is intentionally NOT cleared by the dispatcher in
    // [ReportsScreen] — back from the View sub-screen clears
    // [pendingViewOverManage] alone, and on the next render the
    // chain falls through to the still-set Manage flag, so the
    // user lands back on the Manage screen they came from.
    // See feedback_overlay_back_stack.md (bug shipped 3× already).
    // State lives in [ReportsScreenNav] (one wrapper level above
    // [ReportsScreen]) so the bytecode-heavy ReportsScreen body
    // doesn't grow. Read via the [LocalPendingViewOverManage]
    // CompositionLocal.
    val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
    val resetTickHolder = com.ai.ui.shared.LocalMainViewResetTick.current
    val jump = pendingHolder?.value
    val viewEveryItems = remember(secondaryRuns, translateRows, fanOutSummaries, aiSettings, reportLanguageName) {
        val base = buildEveryItems(
            secondaryRuns, aiSettings,
            onOpenSecondaryRun = { id, lang ->
                onSecondaryLockedLanguageChange(lang)
                onOpenMetaResultIdChange(id)
            },
            onViewSecondaryName = { name, kind, lang ->
                onListLockedLanguageChange(lang)
                onListTargetChange(kind, name)
            },
            onOpenTranslationRun = { runId -> onOpenTranslationRunIdChange(runId) },
            reportLanguageName = reportLanguageName,
            translates = translateRows
        )
        val fanOutItemsByName = (base["fan_out"].orEmpty())
            .associateBy { it.label }
            .toMutableMap()
        fanOutSummaries.forEach { summary ->
            if (summary.metaPromptName !in fanOutItemsByName) {
                fanOutItemsByName[summary.metaPromptName] = EveryItem(
                    label = summary.metaPromptName,
                    open = { lang ->
                        onOpenFanOutView(summary.metaPromptName, lang)
                    }
                )
            }
        }
        base + ("fan_out" to fanOutItemsByName.values.toList())
    }
    val moderationFlagged = remember(secondaryRuns) {
        anyModerationFlagged(secondaryRuns)
    }
    val viewLanguageTabs = remember(translateRows, reportLanguageName) {
        buildLangTabs(translateRows, originalAlias = reportLanguageName)
    }
    val viewLanguageNames = remember(viewLanguageTabs) {
        buildList {
            add("")
            viewLanguageTabs.forEach { tab ->
                if (tab.key != LangTab.ORIGINAL_KEY) add(tab.displayName)
            }
        }
    }
    // [gotoMainView] is wired into LocalNavigateToCurrentReport on
    // EVERY overlay branch below, i.e. the Report-title tap on every
    // View screen. The rule is "title-tap always lands on the Main
    // View tile grid". From a View screen layered on top of a Manage
    // overlay (pendingViewOverManage, the generation phase's Icons,
    // a Manage-opened fan-out), just clearing the branch's own flag
    // falls through to the underlying Manage overlay — wrong. We
    // clear every Manage sub-overlay flag AND every View overlay
    // branch flag here AND bump the reset tick so
    // ViewAiReportScreen's inner sub-View state resets, dropping the
    // user on the fresh tile grid.
    val gotoMainView: () -> Unit = {
        pendingHolder?.value = null
        onOpenMetaResultIdChange(null)
        onOpenTranslationRunIdChange(null)
        onShowViewerChange(false)
        onShowIconsViewChange(false)
        onCloseFanOutView()
        onHtmlPreviewDetailChange(null)
        onListTargetChange(null, null)
        onSingleResultAgentIdChange(null)
        resetTickHolder?.let { it.value = it.value + 1 }
        onShowViewReportScreenChange(true)
    }
    if (jump != null && currentReportId != null) {
        val rid = currentReportId
        // [close] pops one layer back to the underlying Manage
        // overlay — keeps the back-stack peeling natural.
        val close: () -> Unit = { pendingHolder.value = null }
        // LocalOpenManage for jump-mounted View screens — without it the
        // hub's wrench + centre-title tap (whose contract is 'always land
        // on the main Manage screen') fell back to gotoMainView, which
        // only reset the same tile grid; a second tap on the re-mounted
        // standard hub was needed to actually reach Manage. Pops the jump
        // overlay first (it renders above every Manage overlay), then arms
        // the target Manage state — mirroring the standard hub's handler.
        val openManageFromJump: (com.ai.ui.shared.ManageJump) -> Unit = { mj ->
            pendingHolder.value = null
            when (mj) {
                is com.ai.ui.shared.ManageJump.Main -> {
                    // Land on Report - manage MAIN: sweep the Manage
                    // sub-overlays the jump was layered over (same set as
                    // gotoMainView, minus re-opening the View grid).
                    onOpenMetaResultIdChange(null)
                    onOpenTranslationRunIdChange(null)
                    onShowViewerChange(false)
                    onShowIconsViewChange(false)
                    onCloseFanOutView()
                    onHtmlPreviewDetailChange(null)
                    onListTargetChange(null, null)
                    onSingleResultAgentIdChange(null)
                    onShowViewReportScreenChange(false)
                }
                is com.ai.ui.shared.ManageJump.Tournament -> {
                    tournamentOpenHolder?.value = mj.reportId
                    onShowViewReportScreenChange(false)
                }
                is com.ai.ui.shared.ManageJump.MetaResult -> onOpenMetaResultIdChange(mj.id)
                is com.ai.ui.shared.ManageJump.TranslationRun -> onOpenTranslationRunIdChange(mj.id)
                is com.ai.ui.shared.ManageJump.ReportsViewer -> {
                    if (mj.section == null && mj.initialAgentId != null) {
                        onSingleResultAgentIdChange(mj.initialAgentId)
                    } else {
                        onSelectedAgentForViewerChange(mj.initialAgentId)
                        onViewerSectionChange(mj.section)
                        onShowViewerChange(true)
                    }
                }
            }
        }
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides gotoMainView,
            com.ai.ui.shared.LocalOpenManage provides openManageFromJump
        ) {
            when (jump) {
                is com.ai.ui.shared.ViewJump.Main -> ViewAiReportScreen(
                    reportId = rid,
                    promptTitle = uiState.genericPromptTitle,
                    reportIcon = effectiveReportIcon,
                    perModelIconGenEnabled = uiState.generalSettings.perModelIconGenEnabled,
                    everyItems = viewEveryItems,
                    internalPrompts = aiSettings.internalPrompts,
                    useInternalPromptsIcons = uiState.generalSettings.useInternalPromptsIcons,
                    iconRefreshTick = uiState.iconRefreshTick,
                    onMissingPromptIcon = promptIconCallbacks.onKickoff,
                    moderationFlagged = moderationFlagged,
                    onOpenHtmlPreview = { onHtmlPreviewDetailChange(ReportExportDetail.COMPLETE) },
                    onViewIcons = { onShowIconsViewChange(true) },
                    onTranslateMissingItems = { items, target, targetNative ->
                        onTranslateMissingItems(rid, items, target, targetNative)
                    },
                    onBack = close
                )
                is com.ai.ui.shared.ViewJump.Rerank -> RerankViewScreen(
                    reportId = rid, resultId = jump.id, onBack = close,
                    onOpenReportForAgent = { close() }
                )
                is com.ai.ui.shared.ViewJump.Moderation -> ModerationViewScreen(
                    reportId = rid, resultId = jump.id, onBack = close
                )
                is com.ai.ui.shared.ViewJump.Meta -> MetaViewScreen(
                    reportId = rid, resultId = jump.id, language = null,
                    onBack = { _ -> close() }
                )
                is com.ai.ui.shared.ViewJump.FanIn -> FanInViewScreen(
                    reportId = rid, resultId = jump.id, language = null,
                    onBack = { _ -> close() }
                )
                is com.ai.ui.shared.ViewJump.FanOut -> FanOutViewScreen(
                    reportId = rid, metaPromptName = jump.metaPromptName,
                    language = null, onBack = close
                )
                is com.ai.ui.shared.ViewJump.TranslationRun -> TranslateViewScreen(
                    reportId = rid, translationRunId = jump.runId, onBack = close
                )
                is com.ai.ui.shared.ViewJump.Tournament -> TournamentPodiumViewScreen(
                    reportId = rid, resultId = jump.id, onBack = close
                )
                is com.ai.ui.shared.ViewJump.Reports -> ReportsViewScreen(
                    reportId = rid,
                    availableLanguages = viewLanguageNames,
                    initialLanguage = null,
                    initialAgentId = jump.agentId,
                    onBack = { close() }
                )
                is com.ai.ui.shared.ViewJump.Costs -> CostsViewScreen(
                    reportId = rid,
                    onBack = close
                )
                is com.ai.ui.shared.ViewJump.Prompt -> PromptViewScreen(
                    reportId = rid,
                    availableLanguages = viewLanguageNames,
                    initialLanguage = null,
                    onBack = { _ -> close() }
                )
            }
        }
        return true
    }
    if (fanOutViewName != null && currentReportId != null) {
        val rid = currentReportId
        val name = fanOutViewName
        val lang = fanOutViewLanguage
        // The content-only fan-out view is its own overlay branch
        // (returns before the ViewAiReportScreen block below), so it
        // doesn't inherit that block's LocalOpenManage — without this
        // the FanOutViewScreen's 🔧 manage button has no handler and
        // stays hidden. Provide one here: ManageJump.Main closes both
        // this overlay and the View-report screen to reveal Report -
        // manage; the other jumps mirror the ViewAiReportScreen handler.
        val fanOutOpenManageJump: (com.ai.ui.shared.ManageJump) -> Unit = { jump ->
            when (jump) {
                is com.ai.ui.shared.ManageJump.Main -> {
                    onCloseFanOutView()
                    onShowViewReportScreenChange(false)
                }
                is com.ai.ui.shared.ManageJump.Tournament -> {
                    tournamentOpenHolder?.value = jump.reportId
                    onCloseFanOutView()
                    onShowViewReportScreenChange(false)
                }
                is com.ai.ui.shared.ManageJump.MetaResult -> onOpenMetaResultIdChange(jump.id)
                is com.ai.ui.shared.ManageJump.TranslationRun -> onOpenTranslationRunIdChange(jump.id)
                is com.ai.ui.shared.ManageJump.ReportsViewer -> {
                    // A per-model jump (no section, a specific agent) now
                    // opens the single ReportModelScreen — the all-models
                    // Buttons/Pulldown viewer is gone. costs / onepage
                    // (section set) still use the multi-section viewer.
                    if (jump.section == null && jump.initialAgentId != null) {
                        onSingleResultAgentIdChange(jump.initialAgentId)
                    } else {
                        onSelectedAgentForViewerChange(jump.initialAgentId)
                        onViewerSectionChange(jump.section)
                        onShowViewerChange(true)
                    }
                }
            }
        }
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides gotoMainView,
            com.ai.ui.shared.LocalOpenManage provides fanOutOpenManageJump
        ) {
            FanOutViewScreen(
                reportId = rid,
                metaPromptName = name,
                language = lang?.takeIf { it.isNotEmpty() },
                onBack = { onCloseFanOutView() }
            )
        }
        return true
    }

    if (showIconsView && singleResultAgentId == null && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides gotoMainView
        ) {
            IconsViewScreen(
                reportId = currentReportId,
                onBack = { onShowIconsViewChange(false) }
            )
        }
        return true
    }

    if (showViewReportScreen && currentReportId != null
        && !showViewer && !showIconsView
        && singleResultAgentId == null
        && htmlPreviewDetail == null
        && openMetaResultId == null
        && openTranslationRunId == null
        && listKind == null
    ) {
        // When the user arrived directly on View via the per-row 👁
        // icon on a hub list (initialView == true), back from the
        // main View overlay should pop the AI_REPORTS route entirely
        // — returning to the list. Otherwise (View opened from
        // Manage's ℹ️ / title-click) back just closes the overlay so
        // the user lands back on Manage.
        val reportListBundle = com.ai.ui.shared.LocalReportListIconBundle.current
        val backFromView: () -> Unit = if (reportListBundle.initialView && reportListBundle.onExitToList != null) {
            reportListBundle.onExitToList
        } else {
            { onShowViewReportScreenChange(false) }
        }
        val openManageJump: (com.ai.ui.shared.ManageJump) -> Unit = { jump ->
            // For sub-overlay jumps we LEAVE [showViewReportScreen]
            // true and only flip the Manage state var: the existing
            // condition at ReportScreen.kt:3195 hides View whenever
            // any of the Manage sub-overlay flags is non-null, so
            // Manage renders on top. When the Manage overlay's
            // BackHandler clears its flag, View matches the
            // condition again and remounts — ViewAiReportScreen's
            // own [rememberSaveable] sub-overlay state (e.g.
            // rerankViewRowId) is restored via the standard Compose
            // SaveableStateRegistry, so the user lands back on the
            // exact sub-View they came from.
            //
            // [ManageJump.Main] is the exception: there's no Manage
            // sub-overlay to render on top, so we must close View
            // to reveal the Report - manage main screen.
            when (jump) {
                is com.ai.ui.shared.ManageJump.Main -> {
                    onShowViewReportScreenChange(false)
                }
                is com.ai.ui.shared.ManageJump.Tournament -> {
                    // Open the Manage tournament drill-in; closing View reveals
                    // it (ReportsScreenNav early-returns the tournament overlay).
                    tournamentOpenHolder?.value = jump.reportId
                    onShowViewReportScreenChange(false)
                }
                is com.ai.ui.shared.ManageJump.MetaResult -> onOpenMetaResultIdChange(jump.id)
                is com.ai.ui.shared.ManageJump.TranslationRun -> onOpenTranslationRunIdChange(jump.id)
                is com.ai.ui.shared.ManageJump.ReportsViewer -> {
                    // A per-model jump (no section, a specific agent) now
                    // opens the single ReportModelScreen — the all-models
                    // Buttons/Pulldown viewer is gone. costs / onepage
                    // (section set) still use the multi-section viewer.
                    if (jump.section == null && jump.initialAgentId != null) {
                        onSingleResultAgentIdChange(jump.initialAgentId)
                    } else {
                        onSelectedAgentForViewerChange(jump.initialAgentId)
                        onViewerSectionChange(jump.section)
                        onShowViewerChange(true)
                    }
                }
            }
        }
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides { onShowViewReportScreenChange(false) },
            com.ai.ui.shared.LocalOpenManage provides openManageJump
        ) {
            ViewAiReportScreen(
                reportId = currentReportId,
                promptTitle = uiState.genericPromptTitle,
                reportIcon = effectiveReportIcon,
                perModelIconGenEnabled = uiState.generalSettings.perModelIconGenEnabled,
                everyItems = viewEveryItems,
                internalPrompts = aiSettings.internalPrompts,
                useInternalPromptsIcons = uiState.generalSettings.useInternalPromptsIcons,
                iconRefreshTick = uiState.iconRefreshTick,
                onMissingPromptIcon = promptIconCallbacks.onKickoff,
                moderationFlagged = moderationFlagged,
                onOpenHtmlPreview = { onHtmlPreviewDetailChange(ReportExportDetail.COMPLETE) },
                onViewIcons = { onShowIconsViewChange(true) },
                onTranslateMissingItems = { items, target, targetNative ->
                    onTranslateMissingItems(currentReportId, items, target, targetNative)
                },
                onBack = backFromView
            )
        }
        return true
    }

    if (showViewer && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                onShowViewerChange(false)
                onViewerSectionChange(null)
            }
        ) {
            ReportsViewerScreen(
                reportId = currentReportId,
                initialSelectedAgentId = selectedAgentForViewer,
                initialSection = viewerSection,
                onDismiss = {
                    onShowViewerChange(false)
                    onViewerSectionChange(null)
                    onViewerLockedLanguageChange(null)
                },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onContinueWithCurrent = onContinueWithCurrent,
                onContinueWithAgentPicker = onContinueWithAgentPicker,
                onContinueWithOnTheFly = onContinueWithOnTheFly,
                onRemoveAgent = { rid, aid ->
                    onRemoveAgent(rid, aid)
                    onSecondaryRefresh()
                },
                onRegenerateAgent = onRegenerateAgent,
                forcedLanguage = viewerLockedLanguage
            )
        }
        return true
    }

    if (singleResultAgentId != null && currentReportId != null && agentIconDetailFor == null && editModelTitleFor == null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                onSingleResultAgentIdChange(null)
                onShowIconsViewChange(false)
            }
        ) {
            ReportModelScreen(
                reportId = currentReportId,
                agentId = singleResultAgentId,
                onBack = { onSingleResultAgentIdChange(null) },
                onNavigateHome = onNavigateHome,
                onNavigateToModelInfo = onNavigateToModelInfo,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onRemoveAgent = { rid, aid ->
                    onRemoveAgent(rid, aid)
                    onSecondaryRefresh()
                },
                onDeleteRowById = { resultId ->
                    onDeleteSecondaryRowById(currentReportId, resultId)
                },
                onRegenerateAgent = onRegenerateAgent,
                onContinueWithCurrent = onContinueWithCurrent,
                onContinueWithAgentPicker = onContinueWithAgentPicker,
                onContinueWithOnTheFly = onContinueWithOnTheFly,
                onOpenAgentIcon = onOpenAgentIcon,
                onEditModelTitle = onEditModelTitle,
                temperatureSweepStates = temperatureSweepStates,
                onStartTemperatureSweep = onStartTemperatureSweep,
                onApplyTemperatureCandidate = onApplyTemperatureCandidate,
                onClearTemperatureSweep = onClearTemperatureSweep,
                reasoningEffortSweepStates = reasoningEffortSweepStates,
                onStartReasoningEffortSweep = onStartReasoningEffortSweep,
                onApplyReasoningEffortCandidate = onApplyReasoningEffortCandidate,
                onClearReasoningEffortSweep = onClearReasoningEffortSweep,
                webSearchReplayStates = webSearchReplayStates,
                onStartWebSearchReplay = onStartWebSearchReplay,
                onApplyWebSearchReplay = onApplyWebSearchReplay,
                onClearWebSearchReplay = onClearWebSearchReplay,
                promptEditReplayStates = promptEditReplayStates,
                onStartPromptEditReplay = onStartPromptEditReplay,
                onApplyPromptEditReplay = onApplyPromptEditReplay,
                onClearPromptEditReplay = onClearPromptEditReplay
            )
        }
        return true
    }

    if (showAdvancedParameters) {
        com.ai.ui.shared.ParametersSelectScreen(
            aiSettings = aiSettings,
            selectedIds = uiState.reportParametersIds,
            onConfirm = { onParametersIdsChange(it) },
            onBack = { onShowAdvancedParametersChange(false) },
            onNavigateHome = onNavigateHome
        )
        return true
    }

    return false
}
