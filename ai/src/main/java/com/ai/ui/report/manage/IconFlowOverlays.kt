package com.ai.ui.report.manage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.data.AppService
import com.ai.model.ReportModel
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.TitleCandidate
import com.ai.viewmodel.UiState

@Composable
internal fun ReportIconFlowOverlays(
    st: ReportsScreenState,
    uiState: UiState,
    runtime: ReportRuntimeState,
    iconFanOutByReport: Map<String, List<IconCandidate>>,
    internalPromptIconFanOutByPrompt: Map<String, List<IconCandidate>>,
    agentIconFanOutByAgent: Map<String, List<IconCandidate>>,
    pairIconFanOutByPair: Map<String, List<IconCandidate>>,
    titleFanOutByReport: Map<String, List<TitleCandidate>>,
    titleFanOutByAgent: Map<String, List<TitleCandidate>>,
    promptIconCallbacks: InternalPromptIconCallbacks,
    translationIconCallbacks: TranslationIconCallbacks,
    languageIconCallbacks: LanguageIconCallbacks,
    onStartIconFanOut: (reportId: String, promptText: String, models: List<ReportModel>) -> Unit,
    onPickAlternativeIcon: (reportId: String, emoji: String, iconModel: String) -> Unit,
    onRestartIconFanOut: (reportId: String) -> Unit,
    onStartAgentIconFanOut: (reportId: String, agentId: String, models: List<ReportModel>) -> Unit,
    onPickAgentIcon: (reportId: String, agentId: String, emoji: String) -> Unit,
    onRestartAgentIconFanOut: (reportId: String, agentId: String) -> Unit,
    onStartPairIconFanOut: (reportId: String, pairId: String, models: List<ReportModel>) -> Unit,
    onPickPairIcon: (reportId: String, pairId: String, emoji: String) -> Unit,
    onRestartPairIconFanOut: (reportId: String, pairId: String) -> Unit,
    pairTitleFanOutByPair: Map<String, List<TitleCandidate>>,
    onStartPairTitleFanOut: (reportId: String, pairId: String, models: List<ReportModel>) -> Unit,
    onPickPairTitle: (reportId: String, pairId: String, title: String) -> Unit,
    onRestartPairTitleFanOut: (reportId: String, pairId: String) -> Unit,
    onStartReportTitleFanOut: (reportId: String, promptText: String, models: List<ReportModel>, long: Boolean, paramsIds: List<String>, systemPromptId: String?) -> Unit,
    onStartModelTitleFanOut: (reportId: String, agentId: String, models: List<ReportModel>, paramsIds: List<String>, systemPromptId: String?) -> Unit,
    onRestartReportTitleFanOut: (reportId: String) -> Unit,
    onRestartModelTitleFanOut: (agentId: String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    continueChat: ContinueChatCallbacks,
): Boolean {
    val currentReportId = uiState.currentReportId
    val aiSettings = uiState.aiSettings

    if (st.showAlternativeIcons.value && currentReportId != null) {
        AlternativeIconsOverlayHost(
            reportId = currentReportId,
            aiSettings = aiSettings,
            translationIconLanguageFor = st.translationIconLanguageFor.value,
            promptIconDetailForId = st.promptIconDetailForId.value,
            targetMetaRowId = st.metaRowIdForPromptIcon.value,
            fanOutTargetAgentId = st.fanOutTargetAgentId.value,
            pairIconDetailFor = st.pairIconDetailFor.value,
            targetLanguageIcon = st.targetLanguageIcon.value,
            internalPromptIconFanOutByPrompt = internalPromptIconFanOutByPrompt,
            agentIconFanOutByAgent = agentIconFanOutByAgent,
            pairIconFanOutByPair = pairIconFanOutByPair,
            iconFanOutByReport = iconFanOutByReport,
            translationIconCallbacks = translationIconCallbacks,
            languageIconCallbacks = languageIconCallbacks,
            onPickInternalPromptIcon = promptIconCallbacks.onPick,
            onPickMetaRowIcon = promptIconCallbacks.onPickRow,
            onPickAgentIcon = onPickAgentIcon,
            onPickPairIcon = onPickPairIcon,
            onPickAlternativeIcon = onPickAlternativeIcon,
            onRestartInternalPromptIconFanOut = promptIconCallbacks.onRestartFanOut,
            onRestartAgentIconFanOut = onRestartAgentIconFanOut,
            onRestartPairIconFanOut = onRestartPairIconFanOut,
            onRestartIconFanOut = onRestartIconFanOut,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onCloseAll = {
                st.showAlternativeIcons.value = false
                st.showFindIconsPicker.value = false
                st.showIconDetail.value = false
                st.agentIconDetailFor.value = null
                st.fanOutTargetAgentId.value = null
                st.promptIconDetailForId.value = null
                st.metaRowIdForPromptIcon.value = null
                st.translationIconLanguageFor.value = null
                st.pairIconDetailFor.value = null
                st.pairTitleDetailFor.value = null
                st.targetLanguageIcon.value = false
            },
            onRestartReopenPicker = {
                st.findIconsModels.value = emptyList()
                st.showAlternativeIcons.value = false
                st.showFindIconsPicker.value = true
            },
            onClose = { st.showAlternativeIcons.value = false }
        )
        return true
    }

    if (st.showFindIconsPicker.value && currentReportId != null) {
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                st.pickerTarget.value = PickerTarget.NEW_REPORT
                st.showFindIconsPicker.value = false
                st.showIconDetail.value = false
                st.agentIconDetailFor.value = null
                st.fanOutTargetAgentId.value = null
                st.promptIconDetailForId.value = null
                st.metaRowIdForPromptIcon.value = null
                st.translationIconLanguageFor.value = null
                st.pairIconDetailFor.value = null
                st.pairTitleDetailFor.value = null
                st.targetLanguageIcon.value = false
            }
        ) {
            FindIconsPickerRouter(
                reportId = currentReportId,
                targetLanguage = st.translationIconLanguageFor.value,
                targetPromptId = st.promptIconDetailForId.value,
                targetAgentId = st.fanOutTargetAgentId.value,
                targetPairId = st.pairIconDetailFor.value,
                targetPairTitleId = st.pairTitleDetailFor.value,
                targetLanguageIcon = st.targetLanguageIcon.value,
                internalPrompts = aiSettings.internalPrompts,
                aiSettings = aiSettings,
                models = st.findIconsModels.value,
                genericPromptText = uiState.genericPromptText,
                targetTitleFor = st.findTitlesFor.value,
                onStartTitleFanOut = { target, models, pIds, spId ->
                    if (target == "report") {
                        onStartReportTitleFanOut(currentReportId, uiState.genericPromptText, models, st.findTitlesLong.value, pIds, spId)
                    } else {
                        onStartModelTitleFanOut(currentReportId, target, models, pIds, spId)
                    }
                },
                translationIconCallbacks = translationIconCallbacks,
                languageIconCallbacks = languageIconCallbacks,
                onStartInternalPromptIconFanOut = promptIconCallbacks.onStartFanOut,
                onStartAgentIconFanOut = onStartAgentIconFanOut,
                onStartPairIconFanOut = onStartPairIconFanOut,
                onStartPairTitleFanOut = onStartPairTitleFanOut,
                onStartIconFanOut = onStartIconFanOut,
                onAddAgent = {
                    st.pickerTarget.value = PickerTarget.FIND_ICONS
                    st.showSelectAgent.value = true
                },
                onAddFlock = {
                    st.pickerTarget.value = PickerTarget.FIND_ICONS
                    st.showSelectFlock.value = true
                },
                onAddSwarm = {
                    st.pickerTarget.value = PickerTarget.FIND_ICONS
                    st.showSelectSwarm.value = true
                },
                onAddFromReport = {
                    st.pickerTarget.value = PickerTarget.FIND_ICONS
                    st.showSelectFromReport.value = true
                },
                onAddAllModels = {
                    st.pickerTarget.value = PickerTarget.FIND_ICONS
                    st.showSelectAllModels.value = true
                },
                onRemoveModel = { idx ->
                    st.findIconsModels.value = st.findIconsModels.value.toMutableList().apply { removeAt(idx) }
                },
                onClearAll = { st.findIconsModels.value = emptyList() },
                onConfirm = {
                    st.findIconsModels.value = emptyList()
                    st.pickerTarget.value = PickerTarget.NEW_REPORT
                    st.showFindIconsPicker.value = false
                    if (st.findTitlesFor.value != null || st.pairTitleDetailFor.value != null) {
                        st.showAlternativeTitles.value = true
                    } else {
                        st.showAlternativeIcons.value = true
                    }
                },
                onBack = {
                    st.pickerTarget.value = PickerTarget.NEW_REPORT
                    st.showFindIconsPicker.value = false
                    st.findTitlesFor.value = null
                    st.findTitlesLong.value = false
                    st.pairTitleDetailFor.value = null
                }
            )
        }
        return true
    }

    if (st.showAlternativeTitles.value && currentReportId != null) {
        // Per-fan-out-pair title flow — distinct from the report/agent
        // title flow below. Picks land on the pair's SecondaryResult
        // row, never on the report/agent editor field
        // (findTitlesFor / altPickedTitle*).
        val pairTitleId = st.pairTitleDetailFor.value
        if (pairTitleId != null) {
            AlternativeTitlesScreen(
                candidates = pairTitleFanOutByPair[pairTitleId].orEmpty(),
                onPickTitle = { picked ->
                    onPickPairTitle(currentReportId, pairTitleId, picked)
                    st.showAlternativeTitles.value = false
                    st.showFindIconsPicker.value = false
                    st.pairTitleDetailFor.value = null
                },
                onRestart = {
                    onRestartPairTitleFanOut(currentReportId, pairTitleId)
                    st.findIconsModels.value = emptyList()
                    st.showAlternativeTitles.value = false
                    st.showFindIconsPicker.value = true
                },
                onBack = {
                    st.showAlternativeTitles.value = false
                    st.pairTitleDetailFor.value = null
                }
            )
            return true
        }
        val titleTarget = st.findTitlesFor.value
        val candidates = if (titleTarget == "report") {
            titleFanOutByReport[currentReportId].orEmpty()
        } else {
            titleFanOutByAgent[titleTarget].orEmpty()
        }
        AlternativeTitlesScreen(
            candidates = candidates,
            onPickTitle = { picked ->
                if (st.findTitlesLong.value) {
                    st.altPickedTitleLong.value = picked
                } else {
                    st.altPickedTitle.value = picked
                }
                st.showAlternativeTitles.value = false
                st.showFindIconsPicker.value = false
                st.findTitlesFor.value = null
                st.findTitlesLong.value = false
            },
            onRestart = {
                if (titleTarget == "report") {
                    onRestartReportTitleFanOut(currentReportId)
                } else if (titleTarget != null) {
                    onRestartModelTitleFanOut(titleTarget)
                }
                st.findIconsModels.value = emptyList()
                st.showAlternativeTitles.value = false
                st.showFindIconsPicker.value = true
            },
            onBack = {
                st.showAlternativeTitles.value = false
                st.findTitlesFor.value = null
                st.findTitlesLong.value = false
            }
        )
        return true
    }

    if (st.showIconDetail.value && currentReportId != null) {
        val handled = ReportIconOrLanguageDetailOverlay(
            reportId = currentReportId,
            aiSettings = aiSettings,
            promptText = uiState.genericPromptText,
            effectiveReportIcon = runtime.effectiveReportIcon,
            loadedReportTitle = runtime.loadedReportTitle,
            iconRefreshTick = uiState.iconRefreshTick,
            targetLanguageIcon = st.targetLanguageIcon.value,
            reportIcon = runtime.reportIcon,
            reportIconError = runtime.reportIconError,
            reportIconCost = runtime.reportIconCost,
            reportIconModel = runtime.reportIconModel,
            reportIconTraceFile = runtime.reportIconTraceFile,
            iconFanOutByReport = iconFanOutByReport,
            languageIconCallbacks = languageIconCallbacks,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onNavigateToModelInfo = onNavigateToModelInfo,
            continueChat = continueChat,
            onOpenPicker = {
                st.showIconDetail.value = false
                st.showFindIconsPicker.value = true
            },
            onOpenAltIcons = { st.showAlternativeIcons.value = true },
            onApplyReportIcon = { emoji -> onPickAlternativeIcon(currentReportId, emoji, "") },
            onApplyLanguageIcon = { emoji -> languageIconCallbacks.onPickAlternative(currentReportId, emoji, "") },
            onClose = {
                st.showIconDetail.value = false
                st.targetLanguageIcon.value = false
            }
        )
        if (handled) return true
        st.showIconDetail.value = false
    }

    val agentIconDetailFor = st.agentIconDetailFor.value
    if (agentIconDetailFor != null) {
        val handled = AgentIconDetailOverlay(
            agentId = agentIconDetailFor,
            aiSettings = aiSettings,
            currentReportId = currentReportId,
            loadedReportPrompt = runtime.loadedReportPrompt,
            effectiveReportIcon = runtime.effectiveReportIcon,
            loadedReportTitle = runtime.loadedReportTitle,
            agentRecordsByAgentId = runtime.agentRecordsByAgentId,
            agentIconFanOutByAgent = agentIconFanOutByAgent,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onFindAlternativeIcons = { hasActiveAgentFanOut ->
                st.fanOutTargetAgentId.value = st.agentIconDetailFor.value
                if (hasActiveAgentFanOut) st.showAlternativeIcons.value = true
                else st.showFindIconsPicker.value = true
            },
            onApplyIcon = { emoji ->
                currentReportId?.let { rid -> onPickAgentIcon(rid, agentIconDetailFor, emoji) }
            },
            onClose = {
                st.agentIconDetailFor.value = null
                st.fanOutTargetAgentId.value = null
            }
        )
        if (handled) return true
        st.agentIconDetailFor.value = null
    }

    val pairIconDetailFor = st.pairIconDetailFor.value
    if (pairIconDetailFor != null && currentReportId != null) {
        val handled = PairIconDetailOverlay(
            pairId = pairIconDetailFor,
            reportId = currentReportId,
            aiSettings = aiSettings,
            iconRefreshTick = uiState.iconRefreshTick,
            loadedReportPrompt = runtime.loadedReportPrompt,
            effectiveReportIcon = runtime.effectiveReportIcon,
            loadedReportTitle = runtime.loadedReportTitle,
            agentRecordsByAgentId = runtime.agentRecordsByAgentId,
            pairIconFanOutByPair = pairIconFanOutByPair,
            onNavigateToTraceFile = onNavigateToTraceFile,
            onFindAlternativeIcons = { hasActive ->
                if (hasActive) st.showAlternativeIcons.value = true
                else st.showFindIconsPicker.value = true
            },
            onApplyIcon = { emoji -> onPickPairIcon(currentReportId, pairIconDetailFor, emoji) },
            onClose = { st.pairIconDetailFor.value = null }
        )
        if (handled) return true
        st.pairIconDetailFor.value = null
    }

    val promptIconDetailForId = st.promptIconDetailForId.value
    if (promptIconDetailForId != null) {
        val handled = MetaIconDetailOverlay(
            promptId = promptIconDetailForId,
            iconRefreshTick = uiState.iconRefreshTick,
            internalPrompts = aiSettings.internalPrompts,
            fanOutCandidates = internalPromptIconFanOutByPrompt,
            effectiveReportIcon = runtime.effectiveReportIcon,
            loadedReportTitle = runtime.loadedReportTitle,
            onOpenAlternativeIcons = { hasActive ->
                if (hasActive) st.showAlternativeIcons.value = true
                else st.showFindIconsPicker.value = true
            },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onApplyIcon = { emoji ->
                val rowId = st.metaRowIdForPromptIcon.value
                if (rowId != null && currentReportId != null) {
                    // Per-row override — sets just this Meta result's icon.
                    promptIconCallbacks.onPickRow(currentReportId, rowId, emoji)
                } else {
                    // No specific row → set the shared per-prompt cache entry.
                    aiSettings.internalPrompts.firstOrNull { it.id == promptIconDetailForId }
                        ?.let { promptIconCallbacks.onPick(it, IconCandidate.Done(AppService.LOCAL, "", emoji, 0.0)) }
                }
            },
            onClose = {
                st.promptIconDetailForId.value = null
                st.metaRowIdForPromptIcon.value = null
            }
        )
        if (handled) return true
        st.promptIconDetailForId.value = null
        st.metaRowIdForPromptIcon.value = null
    }

    val translationIconLanguageFor = st.translationIconLanguageFor.value
    if (translationIconLanguageFor != null) {
        TranslationIconDetailOverlay(
            language = translationIconLanguageFor,
            iconRefreshTick = uiState.iconRefreshTick,
            fanOutCandidates = internalPromptIconFanOutByPrompt,
            effectiveReportIcon = runtime.effectiveReportIcon,
            loadedReportTitle = runtime.loadedReportTitle,
            onOpenAlternativeIcons = { hasActive ->
                if (hasActive) st.showAlternativeIcons.value = true
                else st.showFindIconsPicker.value = true
            },
            onNavigateToTraceFile = onNavigateToTraceFile,
            onApplyIcon = { emoji ->
                translationIconCallbacks.onPick(translationIconLanguageFor, IconCandidate.Done(AppService.LOCAL, "", emoji, 0.0))
            },
            onClose = { st.translationIconLanguageFor.value = null }
        )
        return true
    }

    return false
}
