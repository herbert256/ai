package com.ai.ui.report.manage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.ai.data.AgentParameters
import com.ai.ui.helpers.*
import com.ai.ui.other.ReportSelectInternalPromptScreen
import com.ai.ui.report.manage.view.buildLangTabs
import com.ai.ui.shared.LocalCurrentReportIdForSwipe
import com.ai.ui.shared.LocalMainViewResetTick
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.shortModelName
import com.ai.viewmodel.UiState

@Composable
internal fun ReportManageActionOverlays(
    st: ReportsScreenState,
    uiState: UiState,
    runtime: ReportRuntimeState,
    iconGenEnabled: Boolean,
    onNavigateHome: () -> Unit,
    onDeleteReport: (String) -> Unit,
    onExport: suspend (String, ReportExportFormat, ReportExportDetail, ReportExportAction, ExportLanguage, (Int, Int) -> Unit) -> Unit,
    onExportAll: suspend (String, ExportLanguage, (Int, Int) -> Unit) -> Unit,
    onAdvancedParametersChange: (AgentParameters?) -> Unit,
    onParametersIdsChange: (List<String>) -> Unit,
    onMarkParametersChanged: () -> Unit,
    onUpdatePrompt: (String, String) -> Unit,
    onUpdateTitle: (String, String, String) -> Unit,
    onUpdateModelTitle: (String, String, String) -> Unit,
    onUpdatePairTitle: (String, String, String) -> Unit,
    onDeleteSecondaryWithRefresh: (String, String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (com.ai.data.AppService, String) -> Unit,
    onNavigateToInternalPromptsByCategory: (String) -> Unit,
): Boolean {
    val currentReportId = uiState.currentReportId
    val aiSettings = uiState.aiSettings
    // 🗂️ "pick another report (filtered)" navigation for the overlays
    // below; each provides it with its own kind so the bottom-bar icon
    // returns to this same screen for the chosen report.
    val managePick = com.ai.ui.shared.LocalNavigateToManagePicker.current

    if (st.showMetaScreen.value && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showMetaScreen.value = false },
            com.ai.ui.shared.LocalManagePickReport provides
                { managePick(com.ai.ui.navigation.ManagePickKind.META.arg) }
        ) {
            ReportMetaScreen(
                reportId = rid,
                isRunning = uiState.activeSecondaryBatches > 0,
                metaPrompts = aiSettings.internalPrompts.filter { it.category.equals("meta", ignoreCase = true) },
                onLaunchMetaPrompt = { st.secondaryScopeMetaPrompt.value = it },
                onDelete = { resultId -> onDeleteSecondaryWithRefresh(rid, resultId) },
                onBack = { st.showMetaScreen.value = false },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToModelInfo = onNavigateToModelInfo
            )
        }
        return true
    }

    if (st.showDeleteConfirm.value && currentReportId != null) {
        DeleteReportConfirmDialog(
            reportId = currentReportId,
            onDismiss = { st.showDeleteConfirm.value = false },
            onDelete = { rid ->
                st.showDeleteConfirm.value = false
                onDeleteReport(rid)
            }
        )
    }

    if (st.showExport.value && currentReportId != null) {
        val rid = currentReportId
        val exportLangTabs = remember(runtime.translateRows) { buildLangTabs(runtime.translateRows) }
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showExport.value = false }
        ) {
            ReportExportScreen(
                onBack = { st.showExport.value = false },
                onNavigateHome = onNavigateHome,
                onExport = { fmt, det, act, lang, onProgress -> onExport(rid, fmt, det, act, lang, onProgress) },
                onExportAll = { lang, onProgress -> onExportAll(rid, lang, onProgress) },
                onViewInApp = { det, lang ->
                    st.showExport.value = false
                    st.htmlPreviewLanguage.value = lang
                    st.htmlPreviewDetail.value = det
                },
                availableLanguages = exportLangTabs,
                sourceLanguageIcon = runtime.languageIcon
            )
        }
        return true
    }

    val previewDetail = st.htmlPreviewDetail.value
    if (previewDetail != null && currentReportId != null) {
        val previewLanguage = st.htmlPreviewLanguage.value
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                st.htmlPreviewDetail.value = null
                st.htmlPreviewLanguage.value = ExportLanguage.All
            }
        ) {
            HtmlPreviewScreen(
                reportId = currentReportId,
                detail = previewDetail,
                language = previewLanguage,
                onBack = {
                    st.htmlPreviewDetail.value = null
                    st.htmlPreviewLanguage.value = ExportLanguage.All
                }
            )
        }
        return true
    }

    if (st.showEditParameters.value) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showEditParameters.value = false }
        ) {
            com.ai.ui.shared.ParametersSelectScreen(
                aiSettings = aiSettings,
                selectedIds = uiState.reportParametersIds,
                onConfirm = { onParametersIdsChange(it); onMarkParametersChanged() },
                onBack = { st.showEditParameters.value = false },
                onNavigateHome = onNavigateHome
            )
        }
        return true
    }

    if (st.showEditPrompt.value && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showEditPrompt.value = false },
            com.ai.ui.shared.LocalManagePickReport provides
                { managePick(com.ai.ui.navigation.ManagePickKind.EDIT_PROMPT.arg) }
        ) {
            ReportEditPromptScreen(
                reportId = rid,
                initialPrompt = uiState.genericPromptText,
                onBack = { st.showEditPrompt.value = false },
                onNavigateHome = onNavigateHome,
                onUpdate = { newPrompt ->
                    st.showEditPrompt.value = false
                    onUpdatePrompt(rid, newPrompt)
                }
            )
        }
        return true
    }

    // The report title is two independent fields edited on two screens —
    // short (list cards, Report.title) and long (top-bar line,
    // Report.titleLong; barTitle = long ?: short). Each saves only its own
    // field, passing the sibling through unchanged so the other isn't lost.
    if (st.showEditShortTitle.value && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showEditShortTitle.value = false },
            com.ai.ui.shared.LocalManagePickReport provides
                { managePick(com.ai.ui.navigation.ManagePickKind.EDIT_TITLE.arg) }
        ) {
            ReportEditShortTitleScreen(
                reportId = rid,
                initialTitle = uiState.genericPromptTitle,
                aiSettings = aiSettings,
                onBack = { st.showEditShortTitle.value = false },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onFindAlternativeTitle = {
                    st.findTitlesFor.value = "report"
                    st.findTitlesLong.value = false
                    st.findIconsModels.value = emptyList()
                    st.showFindIconsPicker.value = true
                },
                injectedTitle = st.altPickedTitle.value,
                onConsumeInjectedTitle = { st.altPickedTitle.value = null },
                onUpdate = { newTitle ->
                    st.showEditShortTitle.value = false
                    onUpdateTitle(rid, newTitle, uiState.genericPromptTitleLong)
                }
            )
        }
        return true
    }

    if (st.showEditLongTitle.value && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showEditLongTitle.value = false }
        ) {
            ReportEditLongTitleScreen(
                reportId = rid,
                initialTitle = uiState.genericPromptTitleLong,
                aiSettings = aiSettings,
                onBack = { st.showEditLongTitle.value = false },
                onNavigateHome = onNavigateHome,
                onNavigateToTraceFile = onNavigateToTraceFile,
                onFindAlternativeTitle = {
                    st.findTitlesFor.value = "report"
                    st.findTitlesLong.value = true
                    st.findIconsModels.value = emptyList()
                    st.showFindIconsPicker.value = true
                },
                injectedTitle = st.altPickedTitleLong.value,
                onConsumeInjectedTitle = { st.altPickedTitleLong.value = null },
                onUpdate = { newTitleLong ->
                    st.showEditLongTitle.value = false
                    onUpdateTitle(rid, uiState.genericPromptTitle, newTitleLong)
                }
            )
        }
        return true
    }

    val editModelTitleFor = st.editModelTitleFor.value
    if (editModelTitleFor != null) {
        val agent = runtime.agentRecordsByAgentId[editModelTitleFor]
        if (currentReportId != null && agent != null) {
            val rid = currentReportId
            CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
                com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
                LocalNavigateToCurrentReport provides { st.editModelTitleFor.value = null }
            ) {
                ReportEditModelTitleScreen(
                    reportId = rid,
                    agentId = editModelTitleFor,
                    modelName = "${agent.provider} · ${shortModelName(agent.model)}",
                    initialTitle = agent.modelTitle.orEmpty(),
                    traceFilename = agent.modelTitleTraceFile,
                    onNavigateToTraceFile = onNavigateToTraceFile,
                    onBack = { st.editModelTitleFor.value = null },
                    onFindAlternativeTitles = {
                        st.findTitlesFor.value = editModelTitleFor
                        st.findTitlesLong.value = false
                        st.findIconsModels.value = emptyList()
                        st.showFindIconsPicker.value = true
                    },
                    injectedTitle = st.altPickedTitle.value,
                    onConsumeInjectedTitle = { st.altPickedTitle.value = null },
                    onUpdate = { newTitle ->
                        st.editModelTitleFor.value = null
                        onUpdateModelTitle(rid, editModelTitleFor, newTitle)
                    }
                )
            }
            return true
        }
    }

    val pairTitleEditFor = st.pairTitleEditFor.value
    if (pairTitleEditFor != null && currentReportId != null) {
        val rid = currentReportId
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.pairTitleEditFor.value = null }
        ) {
            ReportEditPairTitleScreen(
                reportId = rid,
                pairId = pairTitleEditFor,
                iconRefreshTick = uiState.iconRefreshTick,
                onBack = { st.pairTitleEditFor.value = null },
                onFindAlternativeTitles = {
                    st.pairTitleDetailFor.value = pairTitleEditFor
                    st.findIconsModels.value = emptyList()
                    st.showFindIconsPicker.value = true
                },
                onUpdate = { newTitle ->
                    st.pairTitleEditFor.value = null
                    onUpdatePairTitle(rid, pairTitleEditFor, newTitle)
                }
            )
        }
        return true
    }

    // NOTE: "Report - Get info" is no longer an early-return overlay —
    // it renders as a visual layer on top of the still-composed Manage
    // hub (see ReportRunScreen), so it inherits the hub's full bottom
    // bar instead of publishing its own minimal one.

    if (st.showMetaPicker.value) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showMetaPicker.value = false },
            LocalCurrentReportIdForSwipe provides null
        ) {
            ReportSelectInternalPromptScreen(
                titleText = "Run a Meta prompt",
                category = "meta",
                prompts = aiSettings.internalPrompts.filter { it.category.equals("meta", ignoreCase = true) },
                onSelectPrompt = {
                    st.showMetaPicker.value = false
                    st.secondaryScopeMetaPrompt.value = it
                },
                onBack = { st.showMetaPicker.value = false },
                onEditPrompts = {
                    st.showMetaPicker.value = false
                    onNavigateToInternalPromptsByCategory("meta")
                }
            )
        }
        return true
    }

    if (st.showFanOutPicker.value &&
        st.secondaryScopeMetaPrompt.value == null &&
        st.fanOutConfirmMetaPrompt.value == null
    ) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides { st.showFanOutPicker.value = false },
            LocalCurrentReportIdForSwipe provides null
        ) {
            ReportSelectInternalPromptScreen(
                titleText = "Fan Out - prompt",
                category = "fan_out",
                prompts = aiSettings.internalPrompts.filter { it.category == "fan_out" },
                onSelectPrompt = { st.secondaryScopeMetaPrompt.value = it },
                onBack = { st.showFanOutPicker.value = false },
                onEditPrompts = {
                    st.showFanOutPicker.value = false
                    onNavigateToInternalPromptsByCategory("fan_out")
                }
            )
        }
        return true
    }

    return false
}

@Composable
internal fun rememberOpenViewReportFromManage(st: ReportsScreenState): () -> Unit {
    val mainViewResetTickHolder = LocalMainViewResetTick.current
    return remember(st, mainViewResetTickHolder) {
        {
            st.showViewer.value = false
            st.showIconsView.value = false
            st.htmlPreviewDetail.value = null
            st.openMetaResultId.value = null
            st.openTranslationRunId.value = null
            st.listKind.value = null
            st.singleResultAgentId.value = null
            mainViewResetTickHolder?.let { it.value = it.value + 1 }
            st.showViewReportScreen.value = true
        }
    }
}
