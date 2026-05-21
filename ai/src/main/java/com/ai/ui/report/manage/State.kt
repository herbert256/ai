package com.ai.ui.report.manage
import com.ai.ui.report.other.TargetLanguage
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

/**
 * Hoisted state for [ReportsScreen] — moves the rememberSaveable
 * inits out of the ReportsScreen method body to keep it under the JVM
 * 64 KB bytecode ceiling. The body re-binds each via `var x by st.x`,
 * so all downstream usage is unchanged.
 */
internal class ReportsScreenState(
    val openMetaResultId: MutableState<String?>,
    val openTranslationRunId: MutableState<String?>,
    val viewerLockedLanguage: MutableState<String?>,
    val secondaryLockedLanguage: MutableState<String?>,
    val listLockedLanguage: MutableState<String?>,
    val showViewer: MutableState<Boolean>,
    val showIconsView: MutableState<Boolean>,
    val showIconDetail: MutableState<Boolean>,
    val agentIconDetailFor: MutableState<String?>,
    val showFindIconsPicker: MutableState<Boolean>,
    val showAlternativeIcons: MutableState<Boolean>,
    val targetLanguageIcon: MutableState<Boolean>,
    val promptIconDetailForId: MutableState<String?>,
    val metaRowIdForPromptIcon: MutableState<String?>,
    val translationIconLanguageFor: MutableState<String?>,
    val fanOutTargetAgentId: MutableState<String?>,
    val pairIconDetailFor: MutableState<String?>,
    val findIconsModels: MutableState<List<ReportModel>>,
    val translationModels: MutableState<List<ReportModel>>,
    val pickerTarget: MutableState<PickerTarget>,
    val selectedAgentForViewer: MutableState<String?>,
    val viewerSection: MutableState<String?>,
    val singleResultAgentId: MutableState<String?>,
    val showExport: MutableState<Boolean>,
    val htmlPreviewDetail: MutableState<ReportExportDetail?>,
    val htmlPreviewLanguage: MutableState<ExportLanguage>,
    val fanOutViewName: MutableState<String?>,
    val fanOutViewLanguage: MutableState<String?>,
    val showEditPrompt: MutableState<Boolean>,
    val showEditTitle: MutableState<Boolean>,
    val showEditParameters: MutableState<Boolean>,
    val showAdvancedParameters: MutableState<Boolean>,
    val showTranslateLanguagePicker: MutableState<Boolean>,
    val showTranslateModelPicker: MutableState<TargetLanguage?>,
    val models: MutableState<List<ReportModel>>,
    val showDeleteConfirm: MutableState<Boolean>,
    val showRegenerateConfirm: MutableState<Boolean>,
    val showViewReportScreen: MutableState<Boolean>,
    val showMetaPicker: MutableState<Boolean>,
    val showFanOutPicker: MutableState<Boolean>,
    val showRerankPicker: MutableState<Boolean>,
    val showModerationPicker: MutableState<Boolean>,
    val showSelectFlock: MutableState<Boolean>,
    val showSelectAgent: MutableState<Boolean>,
    val showSelectSwarm: MutableState<Boolean>,
    val showSelectProvider: MutableState<Boolean>,
    val pendingProvider: MutableState<AppService?>,
    val showSelectAllModels: MutableState<Boolean>,
    val showSelectFromReport: MutableState<Boolean>,
    val selectedParametersIds: MutableState<List<String>>,
    val secondaryPickerMetaPrompt: MutableState<InternalPrompt?>,
    val metaRunScreenPrompt: MutableState<InternalPrompt?>,
    val secondaryScopeMetaPrompt: MutableState<InternalPrompt?>,
    val pendingSecondaryScope: MutableState<SecondaryScope>,
    val pendingLanguageScope: MutableState<SecondaryLanguageScope>,
    val fanOutConfirmMetaPrompt: MutableState<InternalPrompt?>,
    val fanInPickerPrompt: MutableState<InternalPrompt?>,
    val fanInPickerSourceLanguage: MutableState<String?>,
    val showFanInPromptPicker: MutableState<Boolean>,
    val modelFanInActivePid: MutableState<String?>,
    val modelFanInActiveMdl: MutableState<String?>,
    val modelFanInPickerPrompt: MutableState<InternalPrompt?>,
    val showMetaScreen: MutableState<Boolean>,
    val listKind: MutableState<SecondaryKind?>,
    val listFilterByName: MutableState<String?>,
    val listIsFanIcons: MutableState<Boolean>,
)

@Composable
internal fun rememberReportsScreenState(initialModels: List<ReportModel>): ReportsScreenState {
    val openMetaResultId = rememberSaveable { mutableStateOf<String?>(null) }
    val openTranslationRunId = rememberSaveable { mutableStateOf<String?>(null) }
    val viewerLockedLanguage = rememberSaveable { mutableStateOf<String?>(null) }
    val secondaryLockedLanguage = rememberSaveable { mutableStateOf<String?>(null) }
    val listLockedLanguage = rememberSaveable { mutableStateOf<String?>(null) }
    val showViewer = rememberSaveable { mutableStateOf(false) }
    val showIconsView = rememberSaveable { mutableStateOf(false) }
    val showIconDetail = rememberSaveable { mutableStateOf(false) }
    val agentIconDetailFor = rememberSaveable { mutableStateOf<String?>(null) }
    val showFindIconsPicker = rememberSaveable { mutableStateOf(false) }
    val showAlternativeIcons = rememberSaveable { mutableStateOf(false) }
    val targetLanguageIcon = rememberSaveable { mutableStateOf(false) }
    val promptIconDetailForId = rememberSaveable { mutableStateOf<String?>(null) }
    val metaRowIdForPromptIcon = rememberSaveable { mutableStateOf<String?>(null) }
    val translationIconLanguageFor = rememberSaveable { mutableStateOf<String?>(null) }
    val fanOutTargetAgentId = rememberSaveable { mutableStateOf<String?>(null) }
    val pairIconDetailFor = rememberSaveable { mutableStateOf<String?>(null) }
    val findIconsModels = rememberSaveable(stateSaver = ReportModelListSaver) { mutableStateOf(emptyList<ReportModel>()) }
    val translationModels = rememberSaveable(stateSaver = ReportModelListSaver) { mutableStateOf(emptyList<ReportModel>()) }
    val pickerTarget = remember { mutableStateOf(PickerTarget.NEW_REPORT) }
    val selectedAgentForViewer = rememberSaveable { mutableStateOf<String?>(null) }
    val viewerSection = rememberSaveable { mutableStateOf<String?>(null) }
    val singleResultAgentId = rememberSaveable { mutableStateOf<String?>(null) }
    val showExport = rememberSaveable { mutableStateOf(false) }
    val htmlPreviewDetail = rememberSaveable { mutableStateOf<ReportExportDetail?>(null) }
    val htmlPreviewLanguage = remember { mutableStateOf<ExportLanguage>(ExportLanguage.All) }
    val fanOutViewName = rememberSaveable { mutableStateOf<String?>(null) }
    val fanOutViewLanguage = rememberSaveable { mutableStateOf<String?>(null) }
    val showEditPrompt = rememberSaveable { mutableStateOf(false) }
    val showEditTitle = rememberSaveable { mutableStateOf(false) }
    val showEditParameters = rememberSaveable { mutableStateOf(false) }
    val showAdvancedParameters = rememberSaveable { mutableStateOf(false) }
    val showTranslateLanguagePicker = rememberSaveable { mutableStateOf(false) }
    val showTranslateModelPicker = rememberSaveable(stateSaver = TargetLanguageSaver) { mutableStateOf<TargetLanguage?>(null) }
    val models = rememberSaveable(stateSaver = ReportModelListSaver) { mutableStateOf(initialModels) }
    val showDeleteConfirm = remember { mutableStateOf(false) }
    val showRegenerateConfirm = remember { mutableStateOf(false) }
    val showViewReportScreen = rememberSaveable { mutableStateOf(false) }
    val showMetaPicker = rememberSaveable { mutableStateOf(false) }
    val showFanOutPicker = rememberSaveable { mutableStateOf(false) }
    val showRerankPicker = rememberSaveable { mutableStateOf(false) }
    val showModerationPicker = rememberSaveable { mutableStateOf(false) }
    val showSelectFlock = rememberSaveable { mutableStateOf(false) }
    val showSelectAgent = rememberSaveable { mutableStateOf(false) }
    val showSelectSwarm = rememberSaveable { mutableStateOf(false) }
    val showSelectProvider = rememberSaveable { mutableStateOf(false) }
    val pendingProvider = rememberSaveable(stateSaver = AppServiceSaver) { mutableStateOf<AppService?>(null) }
    val showSelectAllModels = rememberSaveable { mutableStateOf(false) }
    val showSelectFromReport = rememberSaveable { mutableStateOf(false) }
    val selectedParametersIds = rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val secondaryPickerMetaPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val metaRunScreenPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val secondaryScopeMetaPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val pendingSecondaryScope = rememberSaveable(stateSaver = SecondaryScopeSaver) { mutableStateOf<SecondaryScope>(SecondaryScope.AllReports) }
    val pendingLanguageScope = rememberSaveable(stateSaver = SecondaryLanguageScopeSaver) { mutableStateOf<SecondaryLanguageScope>(SecondaryLanguageScope.AllPresent) }
    val fanOutConfirmMetaPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val fanInPickerPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val fanInPickerSourceLanguage = rememberSaveable { mutableStateOf<String?>(null) }
    val showFanInPromptPicker = rememberSaveable { mutableStateOf(false) }
    val modelFanInActivePid = rememberSaveable { mutableStateOf<String?>(null) }
    val modelFanInActiveMdl = rememberSaveable { mutableStateOf<String?>(null) }
    val modelFanInPickerPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val showMetaScreen = rememberSaveable { mutableStateOf(false) }
    val listKind = rememberSaveable { mutableStateOf<SecondaryKind?>(null) }
    val listFilterByName = rememberSaveable { mutableStateOf<String?>(null) }
    val listIsFanIcons = rememberSaveable { mutableStateOf(false) }
    return remember {
        ReportsScreenState(
        openMetaResultId,
        openTranslationRunId,
        viewerLockedLanguage,
        secondaryLockedLanguage,
        listLockedLanguage,
        showViewer,
        showIconsView,
        showIconDetail,
        agentIconDetailFor,
        showFindIconsPicker,
        showAlternativeIcons,
        targetLanguageIcon,
        promptIconDetailForId,
        metaRowIdForPromptIcon,
        translationIconLanguageFor,
        fanOutTargetAgentId,
        pairIconDetailFor,
        findIconsModels,
        translationModels,
        pickerTarget,
        selectedAgentForViewer,
        viewerSection,
        singleResultAgentId,
        showExport,
        htmlPreviewDetail,
        htmlPreviewLanguage,
        fanOutViewName,
        fanOutViewLanguage,
        showEditPrompt,
        showEditTitle,
        showEditParameters,
        showAdvancedParameters,
        showTranslateLanguagePicker,
        showTranslateModelPicker,
        models,
        showDeleteConfirm,
        showRegenerateConfirm,
        showViewReportScreen,
        showMetaPicker,
        showFanOutPicker,
        showRerankPicker,
        showModerationPicker,
        showSelectFlock,
        showSelectAgent,
        showSelectSwarm,
        showSelectProvider,
        pendingProvider,
        showSelectAllModels,
        showSelectFromReport,
        selectedParametersIds,
        secondaryPickerMetaPrompt,
        metaRunScreenPrompt,
        secondaryScopeMetaPrompt,
        pendingSecondaryScope,
        pendingLanguageScope,
        fanOutConfirmMetaPrompt,
        fanInPickerPrompt,
        fanInPickerSourceLanguage,
        showFanInPromptPicker,
        modelFanInActivePid,
        modelFanInActiveMdl,
        modelFanInPickerPrompt,
        showMetaScreen,
        listKind,
        listFilterByName,
        listIsFanIcons
        )
    }
}
