package com.ai.ui.report.manage

import com.ai.data.SecondaryKind
import com.ai.model.InternalPrompt
import com.ai.ui.helpers.ExportLanguage
import com.ai.ui.helpers.ReportExportDetail

internal fun buildGenerationPhaseHandlers(
    st: ReportsScreenState,
    currentReportId: String?,
    loadedReportTimestamp: Long,
    promptIconCallbacks: InternalPromptIconCallbacks,
    translationIconCallbacks: TranslationIconCallbacks,
    translationLifecycle: TranslationLifecycleCallbacks,
    onNavigateToTrace: (String) -> Unit,
    onNavigateToAppLog: (String, String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToTraceListFiltered: (String, String) -> Unit,
    onEditModels: (String) -> Unit,
    onCopyReport: (String) -> Unit,
    onTogglePinReport: (String) -> Unit,
    onPrevReport: () -> Unit,
    onNextReport: () -> Unit
): GenerationPhaseHandlers {
    return GenerationPhaseHandlers(
        // The 'report' row tap is handled inside GenerationPhase (it opens
        // the standalone Report-model route via LocalNavigateToReportModel)
        // — kept out of ReportsScreen, which sits at the 64 KB ceiling.
        onViewAgent = {},
        onShare = { st.showExport.value = true },
        onTrace = { currentReportId?.let(onNavigateToTrace) },
        onDelete = { st.showDeleteConfirm.value = true },
        onCopy = { currentReportId?.let(onCopyReport) },
        onTogglePin = { currentReportId?.let(onTogglePinReport) },
        onTranslate = { st.showTranslateLanguagePicker.value = true },
        onOpenMetaPicker = { st.showMetaPicker.value = true },
        onOpenFanOutPicker = { st.showFanOutPicker.value = true },
        onOpenRerankPicker = {
            st.pendingSecondaryScope.value = com.ai.data.SecondaryScope.AllReports
            st.pendingLanguageScope.value = com.ai.data.SecondaryLanguageScope.AllPresent
            st.showRerankPicker.value = true
        },
        onOpenModerationPicker = {
            st.pendingSecondaryScope.value = com.ai.data.SecondaryScope.AllReports
            st.pendingLanguageScope.value = com.ai.data.SecondaryLanguageScope.AllPresent
            st.showModerationPicker.value = true
        },
        onOpenHtmlPreview = {
            st.htmlPreviewLanguage.value = ExportLanguage.All
            st.htmlPreviewDetail.value = ReportExportDetail.COMPLETE
        },
        onViewReports = {
            st.selectedAgentForViewer.value = null
            st.viewerSection.value = null
            st.viewerLockedLanguage.value = null
            st.showViewer.value = true
        },
        onViewPrompt = {
            st.selectedAgentForViewer.value = null
            st.viewerSection.value = "prompt"
            st.viewerLockedLanguage.value = null
            st.showViewer.value = true
        },
        onViewCosts = {
            st.selectedAgentForViewer.value = null
            st.viewerSection.value = "costs"
            st.showViewer.value = true
        },
        onViewIcons = { st.showIconsView.value = true },
        onViewLog = {
            val rid = currentReportId
            if (rid != null) {
                val day = java.time.Instant.ofEpochMilli(loadedReportTimestamp)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val filename = "applog_" +
                    day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".log"
                onNavigateToAppLog(filename, "#$rid")
            }
        },
        onEditTitle = { st.showEditTitle.value = true },
        onGetInfo = { st.showGetInfo.value = true },
        onEditPromptInline = { st.showEditPrompt.value = true },
        onEditModelsInline = { currentReportId?.let { onEditModels(it) } },
        onEditParametersInline = { st.showEditParameters.value = true },
        onRequestRegenerate = { st.showRegenerateConfirm.value = true },
        onRequestDelete = { st.showDeleteConfirm.value = true },
        onRequestExport = { st.showExport.value = true },
        onCancelTranslation = translationLifecycle.onCancelRun,
        onViewSecondaryName = { name, kind ->
            st.listLockedLanguage.value = null
            st.listKind.value = kind
            st.listFilterByName.value = name
            st.listIsFanIcons.value = false
            st.listIsFanTitles.value = false
        },
        onViewFanIcons = { name ->
            st.listKind.value = SecondaryKind.META
            st.listFilterByName.value = name
            st.listIsFanIcons.value = true
            st.listIsFanTitles.value = false
        },
        onViewFanTitles = { name ->
            st.listKind.value = SecondaryKind.META
            st.listFilterByName.value = name
            st.listIsFanIcons.value = false
            st.listIsFanTitles.value = true
        },
        onOpenSecondaryRun = { id ->
            st.secondaryLockedLanguage.value = null
            st.openMetaResultId.value = id
        },
        onOpenTranslationRun = { runId -> st.openTranslationRunId.value = runId },
        onOpenMeta = { st.showMetaScreen.value = true },
        onNavigateToTraceFile = onNavigateToTraceFile,
        onNavigateToTraceListFiltered = onNavigateToTraceListFiltered,
        onOpenIconDetail = { st.showIconDetail.value = true },
        onOpenLanguageDetail = {
            st.showIconDetail.value = true
            st.targetLanguageIcon.value = true
        },
        onOpenAgentIconDetail = { agentId -> st.agentIconDetailFor.value = agentId },
        onPrevReport = onPrevReport,
        onNextReport = onNextReport,
        onMissingPromptIcon = promptIconCallbacks.onKickoff,
        onOpenInternalPromptIconDetail = { prompt: InternalPrompt ->
            st.metaRowIdForPromptIcon.value = null
            st.promptIconDetailForId.value = prompt.id
        },
        onOpenInternalPromptIconDetailForRow = { prompt, rowId ->
            st.metaRowIdForPromptIcon.value = rowId
            st.promptIconDetailForId.value = prompt.id
        },
        onMissingTranslationIcon = translationIconCallbacks.onKickoff,
        onOpenTranslationIconDetail = { language -> st.translationIconLanguageFor.value = language },
        onReconcileStalledTranslation = translationLifecycle.onReconcileStalled
    )
}
