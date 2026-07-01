package com.ai.ui.report.manage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.data.AppService
import com.ai.model.ReportModel
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.viewmodel.AltEditPayload
import com.ai.viewmodel.AltPromptFlow
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ResolvedAltPrompt
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
    onStartPairTitleFanOut: (reportId: String, pairId: String, models: List<ReportModel>, paramsIds: List<String>, systemPromptId: String?) -> Unit,
    onPickPairTitle: (reportId: String, pairId: String, title: String, model: String) -> Unit,
    onRestartPairTitleFanOut: (reportId: String, pairId: String) -> Unit,
    onStartReportTitleFanOut: (reportId: String, promptText: String, models: List<ReportModel>, long: Boolean, paramsIds: List<String>, systemPromptId: String?) -> Unit,
    onStartModelTitleFanOut: (reportId: String, agentId: String, models: List<ReportModel>, paramsIds: List<String>, systemPromptId: String?) -> Unit,
    onRestartReportTitleFanOut: (reportId: String) -> Unit,
    onRestartModelTitleFanOut: (agentId: String) -> Unit,
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit,
    continueChat: ContinueChatCallbacks,
    /** Resolve a Find-alternative flow's alt prompt (markers replaced) for
     *  the pre-pick "Edit prompt" screen. */
    onResolveAltPrompt: suspend (AltPromptFlow) -> ResolvedAltPrompt?,
    /** Stash the user's edited prompt (null = clear) so the next
     *  start*FanOut consumes it. */
    onStashAltEdit: (AltEditPayload?) -> Unit,
): Boolean {
    val currentReportId = uiState.currentReportId
    val aiSettings = uiState.aiSettings
    val applyAltReportTitle = com.ai.ui.shared.LocalApplyAltReportTitle.current
    val applyAltModelTitle = com.ai.ui.shared.LocalApplyAltModelTitle.current

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
                st.altPromptEditorPassed.value = false
                st.showIconDetail.value = false
                st.agentIconDetailFor.value = null
                st.fanOutTargetAgentId.value = null
                st.promptIconDetailForId.value = null
                st.metaRowIdForPromptIcon.value = null
                st.translationIconLanguageFor.value = null
                st.pairIconDetailFor.value = null
                st.pairTitleDetailFor.value = null
                st.targetLanguageIcon.value = false
                st.targetLanguageDetect.value = false
            },
            onRestartReopenPicker = {
                st.findIconsModels.value = emptyList()
                st.showAlternativeIcons.value = false
                st.altPromptEditorPassed.value = false
                st.showFindIconsPicker.value = true
            },
            onClose = { st.showAlternativeIcons.value = false }
        )
        return true
    }

    // Pre-pick "Edit prompt" — shown before the model picker for every
    // Find-alternative flow. The user edits the resolved prompt (markers
    // already replaced); Next advances to the picker.
    if (st.showFindIconsPicker.value && !st.altPromptEditorPassed.value && currentReportId != null) {
        val flow = altFlowFor(st, uiState, currentReportId)
        if (flow == null) {
            st.altPromptEditorPassed.value = true
        } else {
            CompositionLocalProvider(
                com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
                com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
                LocalNavigateToCurrentReport provides { cancelFindAltFlow(st, onStashAltEdit) }
            ) {
                FindAltPromptEditorScreen(
                    flow = flow,
                    aiSettings = aiSettings,
                    onResolve = onResolveAltPrompt,
                    onNext = { payload ->
                        onStashAltEdit(payload)
                        st.altPromptEditorPassed.value = true
                    },
                    onBack = { cancelFindAltFlow(st, onStashAltEdit) }
                )
            }
            return true
        }
    }

    if (st.showFindIconsPicker.value && currentReportId != null) {
        // If the active flow's alt prompt has a worker (Model/Agent/Flock/Swarm)
        // configured, skip the model-selection screen and run the fan-out on the
        // resolved worker models; empty ⇒ show the picker as before.
        // *SELECT on the flow's alt prompt forces the model picker (empties the
        // auto-resolved workers); *CONFIGURED keeps the skip-the-picker behaviour.
        val altFlow = altFlowFor(st, uiState, currentReportId)
        val autoModels = altFlow
            ?.takeIf { com.ai.viewmodel.altPromptModelSelection(aiSettings, it) != com.ai.model.MODEL_SELECTION_SELECT }
            ?.let { com.ai.viewmodel.altWorkerModels(aiSettings, it) }
            ?: emptyList()
        CompositionLocalProvider(
            com.ai.ui.shared.LocalReportIcon provides runtime.effectiveReportIcon,
            com.ai.ui.shared.LocalReportTitle provides runtime.loadedReportTitle,
            LocalNavigateToCurrentReport provides {
                st.pickerTarget.value = PickerTarget.NEW_REPORT
                st.altPromptEditorPassed.value = false
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
                st.targetLanguageDetect.value = false
                // Also the title-flow targets — a leaked findTitlesFor
                // ("report") re-routes the NEXT Find-alternative-icons run
                // into the report-title flow, and its pick silently
                // overwrites the persisted report title.
                st.findTitlesFor.value = null
                st.findTitlesLong.value = false
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
                autoDispatchModels = autoModels,
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
                    st.altPromptEditorPassed.value = false
                    if (st.findTitlesFor.value != null || st.pairTitleDetailFor.value != null) {
                        st.showAlternativeTitles.value = true
                    } else {
                        st.showAlternativeIcons.value = true
                    }
                },
                onBack = {
                    st.pickerTarget.value = PickerTarget.NEW_REPORT
                    st.showFindIconsPicker.value = false
                    st.altPromptEditorPassed.value = false
                    st.findTitlesFor.value = null
                    st.findTitlesLong.value = false
                    st.pairTitleDetailFor.value = null
                    // The language-icon flags are owned by the report/
                    // language Icon-lookup detail's onClose. When that
                    // detail is NOT layered beneath this picker, backing
                    // out must clear them here — a leaked (rememberSaveable)
                    // targetLanguageIcon hijacks every later Find-
                    // alternative-icons flow: the pick lands on
                    // Report.languageIcon instead of the agent/pair/meta
                    // icon the user was editing.
                    if (!st.showIconDetail.value) {
                        st.targetLanguageIcon.value = false
                        st.targetLanguageDetect.value = false
                    }
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
                    // Pass the PICKED candidate's model so titleModel reflects
                    // the worker the user chose — not whichever fan-out
                    // candidate happened to complete (and stamp titleModel)
                    // last.
                    onPickPairTitle(currentReportId, pairTitleId, picked.title, "${picked.provider.id}/${picked.model}")
                    st.showAlternativeTitles.value = false
                    st.showFindIconsPicker.value = false
                    st.pairTitleDetailFor.value = null
                },
                onRestart = {
                    onRestartPairTitleFanOut(currentReportId, pairTitleId)
                    st.findIconsModels.value = emptyList()
                    st.showAlternativeTitles.value = false
                    st.altPromptEditorPassed.value = false
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
                val model = "${picked.provider.id}/${picked.model}"
                if (titleTarget == "report") {
                    if (st.findTitlesLong.value) {
                        st.altPickedTitleLong.value = picked.title
                        applyAltReportTitle(currentReportId, true, picked.title, model)
                    } else {
                        st.altPickedTitle.value = picked.title
                        applyAltReportTitle(currentReportId, false, picked.title, model)
                    }
                } else if (titleTarget != null) {
                    // Per-model title: the editor injects via altPickedTitle.
                    st.altPickedTitle.value = picked.title
                    applyAltModelTitle(currentReportId, titleTarget, picked.title, model)
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
                st.altPromptEditorPassed.value = false
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
            targetLanguageDetect = st.targetLanguageDetect.value,
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
                // LAYER, don't replace: the picker (checked earlier in this
                // file) renders on top while showIconDetail stays true, so
                // back returns to the Icon-lookup detail. Clearing it here
                // dropped the detail from the back stack (back skipped two
                // levels) — and left targetLanguageIcon orphaned since the
                // detail's onClose, which owns that flag, never ran.
                st.showFindIconsPicker.value = true
            },
            onOpenAltIcons = { st.showAlternativeIcons.value = true },
            onApplyReportIcon = { emoji -> onPickAlternativeIcon(currentReportId, emoji, "") },
            onApplyLanguageIcon = { emoji -> languageIconCallbacks.onPickAlternative(currentReportId, emoji, "") },
            onClose = {
                st.showIconDetail.value = false
                st.targetLanguageIcon.value = false
                st.targetLanguageDetect.value = false
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

/** Map the active Find-alternative picker flags to the flow whose alt
 *  prompt the pre-pick editor resolves. Mirrors `FindIconsPickerRouter`'s
 *  routing precedence exactly so the editor edits the same call the picker
 *  will fire. */
private fun altFlowFor(st: ReportsScreenState, uiState: UiState, reportId: String): AltPromptFlow? {
    val promptText = uiState.genericPromptText
    return when {
        st.pairTitleDetailFor.value != null ->
            AltPromptFlow.PairTitle(reportId, st.pairTitleDetailFor.value!!)
        st.findTitlesFor.value != null -> {
            val target = st.findTitlesFor.value!!
            if (target == "report") AltPromptFlow.ReportTitle(reportId, promptText, st.findTitlesLong.value)
            else AltPromptFlow.ModelTitle(reportId, target)
        }
        st.promptIconDetailForId.value != null ->
            AltPromptFlow.MetaIcon(st.promptIconDetailForId.value!!)
        st.targetLanguageIcon.value ->
            AltPromptFlow.LanguageIcon(reportId)
        st.translationIconLanguageFor.value != null ->
            AltPromptFlow.TranslationIcon(st.translationIconLanguageFor.value!!)
        st.pairIconDetailFor.value != null ->
            AltPromptFlow.PairIcon(reportId, st.pairIconDetailFor.value!!)
        st.fanOutTargetAgentId.value != null ->
            AltPromptFlow.AgentIcon(reportId, st.fanOutTargetAgentId.value!!)
        else -> AltPromptFlow.ReportIcon(reportId, promptText)
    }
}

/** Cancel the whole Find-alternative flow from the pre-pick editor —
 *  drop the picker + every target flag and clear any stashed edit. */
private fun cancelFindAltFlow(st: ReportsScreenState, onStashAltEdit: (AltEditPayload?) -> Unit) {
    st.showFindIconsPicker.value = false
    st.altPromptEditorPassed.value = false
    st.pickerTarget.value = PickerTarget.NEW_REPORT
    st.findTitlesFor.value = null
    st.findTitlesLong.value = false
    st.pairTitleDetailFor.value = null
    st.fanOutTargetAgentId.value = null
    st.promptIconDetailForId.value = null
    st.metaRowIdForPromptIcon.value = null
    st.translationIconLanguageFor.value = null
    st.pairIconDetailFor.value = null
    st.targetLanguageIcon.value = false
    st.targetLanguageDetect.value = false
    onStashAltEdit(null)
}
