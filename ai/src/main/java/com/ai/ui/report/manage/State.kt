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
    val editModelTitleFor: MutableState<String?>,
    val findTitlesFor: MutableState<String?>,
    val findTitlesLong: MutableState<Boolean>,
    val showAlternativeTitles: MutableState<Boolean>,
    val altPickedTitle: MutableState<String?>,
    val altPickedTitleLong: MutableState<String?>,
    val showFindIconsPicker: MutableState<Boolean>,
    val showAlternativeIcons: MutableState<Boolean>,
    val targetLanguageIcon: MutableState<Boolean>,
    /** When true, [showIconDetail] renders the language-DETECTION detail
     *  (the `report-language-name` call) instead of the language-icon one.
     *  Mutually exclusive with [targetLanguageIcon]. */
    val targetLanguageDetect: MutableState<Boolean>,
    val promptIconDetailForId: MutableState<String?>,
    val metaRowIdForPromptIcon: MutableState<String?>,
    val translationIconLanguageFor: MutableState<String?>,
    val fanOutTargetAgentId: MutableState<String?>,
    val pairIconDetailFor: MutableState<String?>,
    val pairTitleDetailFor: MutableState<String?>,
    /** Pair id whose fan-out title is being edited MANUALLY (the new
     *  pair-title editor). Distinct from [pairTitleDetailFor], which
     *  drives the Find-alternative picker. */
    val pairTitleEditFor: MutableState<String?>,
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
    val showEditShortTitle: MutableState<Boolean>,
    val showEditLongTitle: MutableState<Boolean>,
    /** Find-alternative flow: false = show the pre-pick "Edit prompt"
     *  screen, true = the user passed it (Next) so the model picker shows.
     *  Reset to false whenever a find-alt flow opens / closes. */
    val altPromptEditorPassed: MutableState<Boolean>,
    val showGetInfo: MutableState<Boolean>,
    /** The "Edit report" overview + its two child list screens, all drawn
     *  as layer-on-top overlays in [ReportRunScreen] (like showGetInfo). */
    val showEditReportOverview: MutableState<Boolean>,
    val showEditIconsList: MutableState<Boolean>,
    val showEditTitlesList: MutableState<Boolean>,
    /** The "Create" launcher (🆕), a layer-on-top overlay like the Edit
     *  overview. Lists the secondary-result create options. */
    val showCreateOverview: MutableState<Boolean>,
    val showEditParameters: MutableState<Boolean>,
    val showAdvancedParameters: MutableState<Boolean>,
    val showTranslateLanguagePicker: MutableState<Boolean>,
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
    /** Fan Out only: "Let models respond to their own answers" — when true,
     *  self-pairs (a model reacting to its own answer) are included. Carried
     *  from the scope screen to the run screen + engine. */
    val fanOutSelfRespond: MutableState<Boolean>,
    val fanOutConfirmMetaPrompt: MutableState<InternalPrompt?>,
    /** Fan-out launched with "Runtime parameters" off: skips
     *  [FanOutConfirmScreen] and runs the matrix with engine defaults. A
     *  one-shot trigger consumed by a LaunchedEffect in ReportsScreen. */
    val fanOutDirectRunPrompt: MutableState<InternalPrompt?>,
    /** Runtime prompt-edit launch (Runtime parameters on) for the 6 kinds with
     *  no dedicated run screen. One state, dispatched by kind across the two
     *  mount blocks (ReportRunScreen / ReportsScreen). */
    val runtimePromptReq: MutableState<RuntimePromptReq?>,
    val fanInPickerPrompt: MutableState<InternalPrompt?>,
    val fanInPickerSourceLanguage: MutableState<String?>,
    val showFanInPromptPicker: MutableState<Boolean>,
    val showMetaScreen: MutableState<Boolean>,
    val listKind: MutableState<SecondaryKind?>,
    val listFilterByName: MutableState<String?>,
    val listIsFanMeta: MutableState<Boolean>,
    val altTranslateTarget: MutableState<AltTranslateTarget?>,
    val showAltTranslatePicker: MutableState<Boolean>,
    /** Build-stage popup: the UUID of the batch currently in its build
     *  phase (null = no popup), the navigation to run once the build
     *  finishes, and the cancel action that aborts + cleans up. Plain
     *  `remember` (not saveable) — the key is transient and the lambdas
     *  aren't Parcelable. */
    val pendingBuildKey: MutableState<String?>,
    val pendingBuildNav: MutableState<(() -> Unit)?>,
    val pendingBuildCancel: MutableState<(() -> Unit)?>,
    /** Run-time worker picker request: shown (full-screen) when a *SELECT
     *  Internal Prompt is about to run, so the user picks the workers first.
     *  Plain `remember` (not saveable) — it carries lambdas. */
    val runtimeWorkerPick: MutableState<RuntimeWorkerPick?>,
    /** "Report - select workers" step between select-models and Generate:
     *  whether the screen is showing, the in-progress worker config draft,
     *  and the report type carried over from the select-models button. */
    val showSelectWorkers: MutableState<Boolean>,
    val workerConfig: MutableState<ReportWorkerConfig>,
    val pendingReportType: MutableState<ReportType>,
    /** ReportsScreen-level scope for work that must survive the runtime
     *  worker picker's early-return overlay (which unmounts
     *  ReportRunScreen and cancels any scope remembered there) — the
     *  fresh-config read + SELECT_ONCE persist in launchWithWorkerPlan. */
    val screenScope: kotlinx.coroutines.CoroutineScope,
    val translationSelection: MutableState<com.ai.viewmodel.TranslationSelection> = mutableStateOf(com.ai.viewmodel.TranslationSelection()),
)

/** A pending "pick workers before running" request (see [InternalPrompt.modelSelection]
 *  == *SELECT). [initial] pre-seeds the picker with the prompt's configured chain. */
internal data class RuntimeWorkerPick(
    val titleText: String,
    val initial: List<Worker>,
    val onConfirm: (List<Worker>) -> Unit,
    val onCancel: () -> Unit
)

/** A pending runtime prompt-edit launch for a secondary kind that honours the
 *  report's "Runtime parameters" toggle but has no dedicated run screen of its
 *  own — Compare / Fan-in / Tournament / Judge-the-judges / Translate /
 *  Rank-the-translators. Carries the driving prompt(s) (one, or body + title for
 *  Translate) plus the per-kind launch context. Set by each launch site when
 *  the flag is on; consumed by a [SecondaryRuntimePromptScreen] mount in
 *  ReportRunScreen (Compare / Tournament / Judges / TransRank) or ReportsScreen
 *  (Fan-in / Translate). Transient launch state — deliberately not saveable
 *  (mirrors the hoisted [com.ai.ui.report.manage.PendingRankRequest]). */
/** Which secondary kind a [RuntimePromptReq] launches. Its own enum (not
 *  [SecondaryKind], which has no Fan-in) so it can also drive the two-mount
 *  split: COMPARE / TOURNAMENT / JUDGES / TRANSRANK mount in ReportRunScreen,
 *  FAN_IN / TRANSLATE in ReportsScreen. */
internal enum class RuntimePromptKind { COMPARE, FAN_IN, TOURNAMENT, JUDGES, TRANSLATE, TRANSRANK }

internal data class RuntimePromptReq(
    val kind: RuntimePromptKind,
    val prompts: List<InternalPrompt>,
    /** Compare: picked meta-result id. TransRank: source translation run id. */
    val ctxId: String? = null,
    /** Translate / TransRank: target language name + native name. */
    val lang: String? = null,
    val langNative: String? = null,
    /** Fan-in: parent fan-out source language (null = Original). */
    val sourceLanguage: String? = null
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
    val editModelTitleFor = rememberSaveable { mutableStateOf<String?>(null) }
    val findTitlesFor = rememberSaveable { mutableStateOf<String?>(null) }
    val findTitlesLong = rememberSaveable { mutableStateOf(false) }
    val showAlternativeTitles = rememberSaveable { mutableStateOf(false) }
    val altPickedTitle = rememberSaveable { mutableStateOf<String?>(null) }
    val altPickedTitleLong = rememberSaveable { mutableStateOf<String?>(null) }
    val showFindIconsPicker = rememberSaveable { mutableStateOf(false) }
    val showAlternativeIcons = rememberSaveable { mutableStateOf(false) }
    val targetLanguageIcon = rememberSaveable { mutableStateOf(false) }
    val targetLanguageDetect = rememberSaveable { mutableStateOf(false) }
    val promptIconDetailForId = rememberSaveable { mutableStateOf<String?>(null) }
    val metaRowIdForPromptIcon = rememberSaveable { mutableStateOf<String?>(null) }
    val translationIconLanguageFor = rememberSaveable { mutableStateOf<String?>(null) }
    val fanOutTargetAgentId = rememberSaveable { mutableStateOf<String?>(null) }
    val pairIconDetailFor = rememberSaveable { mutableStateOf<String?>(null) }
    val pairTitleDetailFor = rememberSaveable { mutableStateOf<String?>(null) }
    val pairTitleEditFor = rememberSaveable { mutableStateOf<String?>(null) }
    val findIconsModels = rememberSaveable(stateSaver = ReportModelListSaver) { mutableStateOf(emptyList<ReportModel>()) }
    val translationModels = rememberSaveable(stateSaver = ReportModelListSaver) { mutableStateOf(emptyList<ReportModel>()) }
    val pickerTarget = rememberSaveable { mutableStateOf(PickerTarget.NEW_REPORT) }
    val selectedAgentForViewer = rememberSaveable { mutableStateOf<String?>(null) }
    val viewerSection = rememberSaveable { mutableStateOf<String?>(null) }
    val singleResultAgentId = rememberSaveable { mutableStateOf<String?>(null) }
    val showExport = rememberSaveable { mutableStateOf(false) }
    val htmlPreviewDetail = rememberSaveable { mutableStateOf<ReportExportDetail?>(null) }
    val htmlPreviewLanguage = rememberSaveable(stateSaver = ExportLanguageSaver) { mutableStateOf<ExportLanguage>(ExportLanguage.All) }
    val fanOutViewName = rememberSaveable { mutableStateOf<String?>(null) }
    val fanOutViewLanguage = rememberSaveable { mutableStateOf<String?>(null) }
    val showEditPrompt = rememberSaveable { mutableStateOf(false) }
    val showEditShortTitle = rememberSaveable { mutableStateOf(false) }
    val showEditLongTitle = rememberSaveable { mutableStateOf(false) }
    val altPromptEditorPassed = rememberSaveable { mutableStateOf(false) }
    val showGetInfo = rememberSaveable { mutableStateOf(false) }
    val showEditReportOverview = rememberSaveable { mutableStateOf(false) }
    val showEditIconsList = rememberSaveable { mutableStateOf(false) }
    val showEditTitlesList = rememberSaveable { mutableStateOf(false) }
    val showCreateOverview = rememberSaveable { mutableStateOf(false) }
    val showEditParameters = rememberSaveable { mutableStateOf(false) }
    val showAdvancedParameters = rememberSaveable { mutableStateOf(false) }
    val showTranslateLanguagePicker = rememberSaveable { mutableStateOf(false) }
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
    val fanOutSelfRespond = rememberSaveable { mutableStateOf(false) }
    val fanOutConfirmMetaPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val fanOutDirectRunPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val runtimePromptReq = remember { mutableStateOf<RuntimePromptReq?>(null) }
    val fanInPickerPrompt = rememberSaveable(stateSaver = InternalPromptSaver) { mutableStateOf<InternalPrompt?>(null) }
    val fanInPickerSourceLanguage = rememberSaveable { mutableStateOf<String?>(null) }
    val showFanInPromptPicker = rememberSaveable { mutableStateOf(false) }
    val showMetaScreen = rememberSaveable { mutableStateOf(false) }
    val listKind = rememberSaveable { mutableStateOf<SecondaryKind?>(null) }
    val listFilterByName = rememberSaveable { mutableStateOf<String?>(null) }
    val listIsFanMeta = rememberSaveable { mutableStateOf(false) }
    val altTranslateTarget = rememberSaveable(stateSaver = AltTranslateTargetSaver) { mutableStateOf<AltTranslateTarget?>(null) }
    val showAltTranslatePicker = rememberSaveable { mutableStateOf(false) }
    val pendingBuildKey = remember { mutableStateOf<String?>(null) }
    val pendingBuildNav = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingBuildCancel = remember { mutableStateOf<(() -> Unit)?>(null) }
    val runtimeWorkerPick = remember { mutableStateOf<RuntimeWorkerPick?>(null) }
    val showSelectWorkers = rememberSaveable { mutableStateOf(false) }
    val workerConfig = rememberSaveable(stateSaver = ReportWorkerConfigSaver) { mutableStateOf(ReportWorkerConfig()) }
    val pendingReportType = rememberSaveable { mutableStateOf(ReportType.CLASSIC) }
    val screenScope = rememberCoroutineScope()
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
        editModelTitleFor,
        findTitlesFor,
        findTitlesLong,
        showAlternativeTitles,
        altPickedTitle,
        altPickedTitleLong,
        showFindIconsPicker,
        showAlternativeIcons,
        targetLanguageIcon,
        targetLanguageDetect,
        promptIconDetailForId,
        metaRowIdForPromptIcon,
        translationIconLanguageFor,
        fanOutTargetAgentId,
        pairIconDetailFor,
        pairTitleDetailFor,
        pairTitleEditFor,
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
        showEditShortTitle,
        showEditLongTitle,
        altPromptEditorPassed,
        showGetInfo,
        showEditReportOverview,
        showEditIconsList,
        showEditTitlesList,
        showCreateOverview,
        showEditParameters,
        showAdvancedParameters,
        showTranslateLanguagePicker,
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
        fanOutSelfRespond,
        fanOutConfirmMetaPrompt,
        fanOutDirectRunPrompt,
        runtimePromptReq,
        fanInPickerPrompt,
        fanInPickerSourceLanguage,
        showFanInPromptPicker,
        showMetaScreen,
        listKind,
        listFilterByName,
        listIsFanMeta,
        altTranslateTarget,
        showAltTranslatePicker,
        pendingBuildKey,
        pendingBuildNav,
        pendingBuildCancel,
        runtimeWorkerPick,
        showSelectWorkers,
        workerConfig,
        pendingReportType,
        screenScope
        )
    }
}
