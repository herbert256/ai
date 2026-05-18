package com.ai.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Context
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.SecondaryKind
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar

/**
 * First-version "View AI Report" page — the home for every "look at
 * this report" affordance that used to live under the cramped
 * Row 1 "View" CompactButton. Reached from the bottom-bar ℹ️ icon
 * on the result screen.
 *
 * Layout: two tile sections rendered side-by-side via
 * [LazyVerticalGrid] (adaptive cells, ~150 dp min). The Documents
 * section always renders the always-on views (Prompt / Costs /
 * Reports / HTML / Log + Icons when the per-model icon chain is
 * enabled). The Computed section renders one tile per conditional
 * kind that has at least one row, with a small count badge.
 *
 * Tap behaviour:
 *  - A Documents tile fires its handler then pops the screen.
 *  - A Computed tile with exactly one item opens that item directly.
 *  - A Computed tile with ≥ 2 items toggles an inline list below
 *    the grid; tapping an item opens its detail.
 *
 * All destinations are the existing full-screen Composables wired
 * to the same handlers the old "View" Row 2 buttons fired — the
 * tile grid is purely a launcher. Reverting to the old UI is a
 * one-line re-add of the [CompactButton] in [GenerationPhase].
 */
@Composable
internal fun ViewAiReportScreen(
    reportId: String,
    promptTitle: String,
    reportIcon: String?,
    perModelIconGenEnabled: Boolean,
    everyItems: Map<String, List<EveryItem>>,
    /** Internal prompts the report can run — used to resolve a
     *  meta row's [com.ai.model.InternalPrompt] from its name so the
     *  tile can render the cached per-prompt emoji instead of the
     *  static 🧠 fallback. */
    internalPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    /** Master toggle from GeneralSettings — when off, every meta
     *  tile keeps the static 🧠 even if a cached emoji exists. */
    useInternalPromptsIcons: Boolean = false,
    /** Re-key for the cache lookup — bumped whenever an
     *  internal-prompt icon-gen call lands, so the tile recomposes
     *  with the freshly-cached emoji without a manual subscribe. */
    iconRefreshTick: Int = 0,
    /** Cold-cache trigger — fired when a meta tile's prompt has no
     *  cached emoji yet. Same one-shot generation path the result
     *  list's per-row emoji uses. */
    onMissingPromptIcon: (com.ai.model.InternalPrompt) -> Unit = { _ -> },
    /** True when ANY persisted moderation row on this report has
     *  AT LEAST one fired category. Flips the moderation tile's
     *  accent to red (flag set somewhere) vs the default green
     *  (every run came back clean). The 🚩 emoji is rendered on
     *  both — the tile colour carries the verdict. */
    moderationFlagged: Boolean = false,
    /** Receives the View screen's currently-selected language so the
     *  opened sub-screen can lock itself to that language. null = no
     *  force; "" = force Original; non-empty = displayName. */
    onOpenHtmlPreview: () -> Unit,
    onViewIcons: () -> Unit,
    /** Fired when the "Language missing" popup's row is tapped.
     *  Dispatches a one-off translation of the picked items to the
     *  active target language. Hosted by ReportScreen which routes
     *  to ReportViewModel.translateMissingItems. */
    onTranslateMissingItems: (items: List<com.ai.viewmodel.ReportViewModel.TranslateMissingItem>,
                              targetLanguageName: String,
                              targetLanguageNative: String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit
) {
    // Horizontal swipe → prev / next report uses the
    // [LocalReportNeighborNav] CompositionLocal set in
    // [ReportsScreenNav] rather than threading two more lambdas
    // through ReportsScreen → ReportPrimaryOverlays → here
    // (ReportsScreen sits at the JVM 64 KB per-method bytecode
    // ceiling so each new arg pushes it over).
    val swipeNav = com.ai.ui.shared.LocalReportNeighborNav.current
    // Android back returns to Report - manage. Without this handler
    // the back press would fall through to ReportScreen's own handler,
    // which pops the route — wrong layer. See plan
    // /Users/herbert/.claude/plans/meta-items-and-fa-in-flickering-piglet.md
    // for the LIFO BackHandler model that keeps every View layer
    // popping one step at a time.
    androidx.activity.compose.BackHandler { onBack() }
    // Per-tile content-only "View" overlay state. Owned by
    // ViewAiReportScreen rather than the parent so adding new view
    // overlays one-per-commit doesn't grow ReportsScreen's bytecode
    // past the JVM 64 KB per-method ceiling. When the costs overlay is
    // open we render [CostsViewScreen] in place of the tile grid;
    // tapping back inside that overlay clears the flag and returns to
    // the grid here. rememberSaveable so a rotation while the costs
    // screen is up doesn't snap back to the grid.
    var showCostsView by rememberSaveable { mutableStateOf(false) }
    if (showCostsView) {
        val backToMain: () -> Unit = { showCostsView = false }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            CostsViewScreen(
                reportId = reportId,
                onBack = backToMain
            )
        }
        return
    }
    // Meta "View" overlay — keyed by the META row id so two tiles
    // that share a metaPromptName open the correct row. Language is
    // captured at tap time so the opened MetaViewScreen renders the
    // matching TRANSLATE row for the active picker language.
    var metaViewRowId by rememberSaveable { mutableStateOf<String?>(null) }
    var metaViewLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    val activeMetaViewRowId = metaViewRowId
    if (activeMetaViewRowId != null) {
        val backToMain: () -> Unit = { metaViewRowId = null; metaViewLanguage = null }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            MetaViewScreen(
                reportId = reportId,
                resultId = activeMetaViewRowId,
                language = metaViewLanguage?.takeIf { it.isNotEmpty() },
                onBack = backToMain
            )
        }
        return
    }
    // Reports overlay var declarations hoisted here so the rerank
    // mount block below can flip them — the rerank podium card tap
    // closes Rerank and opens Reports scrolled to that agent's page.
    // The actual ReportsViewScreen mount lives further down (after
    // the language-picker setup) — these vars are shared.
    var reportsViewLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var reportsViewOpen by rememberSaveable { mutableStateOf(false) }
    var reportsViewInitialAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    // Rerank "View" overlay — keyed by the RERANK row id.
    var rerankViewRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeRerankViewRowId = rerankViewRowId
    if (activeRerankViewRowId != null) {
        val backToMain: () -> Unit = { rerankViewRowId = null }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            RerankViewScreen(
                reportId = reportId,
                resultId = activeRerankViewRowId,
                onBack = backToMain,
                onOpenReportForAgent = { agentId ->
                    rerankViewRowId = null
                    reportsViewInitialAgentId = agentId
                    reportsViewOpen = true
                }
            )
        }
        return
    }
    // Moderation "View" overlay — keyed by the MODERATION row id.
    var moderationViewRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeModerationViewRowId = moderationViewRowId
    if (activeModerationViewRowId != null) {
        val backToMain: () -> Unit = { moderationViewRowId = null }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            ModerationViewScreen(
                reportId = reportId,
                resultId = activeModerationViewRowId,
                onBack = backToMain
            )
        }
        return
    }
    // Fan-in "View" overlay — keyed by the fan-in META row id.
    var fanInViewRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeFanInViewRowId = fanInViewRowId
    if (activeFanInViewRowId != null) {
        val backToMain: () -> Unit = { fanInViewRowId = null }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            FanInViewScreen(
                reportId = reportId,
                resultId = activeFanInViewRowId,
                onBack = backToMain
            )
        }
        return
    }
    // Fan-in-model "View" overlay — keyed by the seed fan-in-model
    // META row id; the screen loads every sibling row sharing the
    // same metaPromptName and renders a pager over them.
    var fanInModelViewRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeFanInModelViewRowId = fanInModelViewRowId
    if (activeFanInModelViewRowId != null) {
        val backToMain: () -> Unit = { fanInModelViewRowId = null }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            FanInModelViewScreen(
                reportId = reportId,
                resultId = activeFanInModelViewRowId,
                onBack = backToMain
            )
        }
        return
    }
    // Translate "View" overlay — keyed by translationRunId so all
    // siblings of a single Translate batch render together as a
    // stacked source/translation list.
    var translateViewRunId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeTranslateViewRunId = translateViewRunId
    if (activeTranslateViewRunId != null) {
        val backToMain: () -> Unit = { translateViewRunId = null }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            TranslateViewScreen(
                reportId = reportId,
                translationRunId = activeTranslateViewRunId,
                onBack = backToMain
            )
        }
        return
    }
    // Prompt "View" overlay state — only the var declarations sit
    // here so the tile click below this block (which sets
    // [promptViewOpen] = true) can reach them. The actual early-
    // return + PromptViewScreen mount lives after the language-
    // picker setup further down, because it reads [viewLangTabs] +
    // [selectedViewLangKey] to propagate the user's swiped-to
    // language back up.
    var promptViewLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var promptViewOpen by rememberSaveable { mutableStateOf(false) }
    // Reports overlay vars are declared higher up (hoisted so the
    // rerank mount can flip them) — re-using the same identifiers
    // here.

    // Inline expansion target — which Computed kind's items list is
    // open below the grid. Null = nothing expanded. rememberSaveable
    // so a rotation doesn't snap the list shut mid-read.
    var expandedKind by rememberSaveable { mutableStateOf<String?>(null) }

    // Persisted tile-order — survives reports / restarts. Stored as
    // a comma-separated list of tile identifiers under SharedPreferences
    // 'view_screen_prefs'. Tiles whose id isn't in the list fall to
    // the end of the grid in their default declaration order; user
    // reorders patch the persisted list keeping non-current tiles
    // in their previous relative positions.
    val viewPrefsCtx = LocalContext.current
    val tileOrderPrefs = remember { viewPrefsCtx.getSharedPreferences("view_screen_prefs", Context.MODE_PRIVATE) }
    var savedOrder by remember {
        mutableStateOf(loadTileOrder(tileOrderPrefs))
    }

    // TRANSLATE secondaries on this report — drives the language
    // picker at the top of the View screen. When the user taps a
    // tile we forward the active language to the opened sub-screen
    // as a lock, and that sub-screen suppresses its own picker.
    // Loaded together with the full Report so the popup that fires
    // when a grayed tile is tapped can resolve per-item source text
    // (report.prompt + agents' responseBody) without a second IO
    // round-trip.
    data class TranslatesLoad(
        val list: List<com.ai.data.SecondaryResult>,
        val report: com.ai.data.Report?
    )
    val translatesState = androidx.compose.runtime.produceState(
        initialValue = TranslatesLoad(emptyList(), null), reportId
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val list = com.ai.data.SecondaryResultStorage
                .listForReport(viewPrefsCtx, reportId, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
            val rep = com.ai.data.ReportStorage.getReport(viewPrefsCtx, reportId)
            TranslatesLoad(list, rep)
        }
    }
    val translates = translatesState.value.list
    val loadedReport = translatesState.value.report
    val originalLanguageIcon = loadedReport?.languageIcon
    // The report's detected source-language display name (e.g.
    // "English"). TRANSLATE rows tagged with this language are
    // back-translations TO Original — they fold into the Original
    // tab in the picker and into the "" bucket of per-tile
    // availability. Null when language detection didn't run yet
    // (older reports); the fold then no-ops.
    val reportLanguageName = loadedReport?.languageName?.takeIf { it.isNotBlank() }
    val viewLangTabs = remember(translates, reportLanguageName) {
        buildLangTabs(translates, originalAlias = reportLanguageName)
    }

    // Per-kind availability sets. "" = Original; included only when
    // the underlying Original content actually exists (report.prompt
    // non-blank for Prompt; at least one successful agent has a
    // non-blank responseBody for Reports) OR when the user has back-
    // translated to reportLanguageName (which folds into Original).
    // While the Report is still loading we treat Original as
    // available so the tile doesn't flash grayed during the cold
    // produceState. Non-Original entries = displayName of every
    // language with a non-blank TRANSLATE row, with reportLanguageName
    // folded back into Original (no duplicate tab / availability).
    val promptAvailableLangs = remember(translates, loadedReport, reportLanguageName) {
        buildSet {
            val hasOriginalPrompt = loadedReport == null || loadedReport.prompt.isNotBlank()
            val hasBackTranslatedPrompt = reportLanguageName != null && translates.any {
                it.translateSourceKind == "PROMPT" && it.targetLanguage == reportLanguageName
            }
            if (hasOriginalPrompt || hasBackTranslatedPrompt) add("")
            translates.filter { it.translateSourceKind == "PROMPT" }
                .forEach { tr ->
                    val lang = tr.targetLanguage?.takeIf { l -> l.isNotBlank() } ?: return@forEach
                    if (lang != reportLanguageName) add(lang)
                }
        }
    }
    val reportsAvailableLangs = remember(translates, loadedReport, reportLanguageName) {
        buildSet {
            val hasAnyAgentResponse = loadedReport == null || loadedReport.agents.any {
                it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
            }
            val hasBackTranslatedAgent = reportLanguageName != null && translates.any {
                it.translateSourceKind == "AGENT" && it.targetLanguage == reportLanguageName
            }
            if (hasAnyAgentResponse || hasBackTranslatedAgent) add("")
            translates.filter { it.translateSourceKind == "AGENT" }
                .forEach { tr ->
                    val lang = tr.targetLanguage?.takeIf { l -> l.isNotBlank() } ?: return@forEach
                    if (lang != reportLanguageName) add(lang)
                }
        }
    }
    var selectedViewLangKey by rememberSaveable(reportId) { mutableStateOf(LangTab.ORIGINAL_KEY) }
    androidx.compose.runtime.LaunchedEffect(viewLangTabs) {
        if (viewLangTabs.none { it.key == selectedViewLangKey }) {
            selectedViewLangKey = LangTab.ORIGINAL_KEY
        }
    }
    // Derived from the selected tab; a State holder so the cached
    // tile onClick lambdas read the latest value at click time
    // without invalidating `remember(docTiles)`.
    val currentLanguageState = remember { mutableStateOf<String?>("") }
    androidx.compose.runtime.LaunchedEffect(selectedViewLangKey, viewLangTabs) {
        currentLanguageState.value = if (selectedViewLangKey == LangTab.ORIGINAL_KEY) ""
            else viewLangTabs.firstOrNull { it.key == selectedViewLangKey }?.displayName ?: ""
    }

    // Prompt "View" overlay — language captured at tap time so the
    // opened screen renders the matching PROMPT TRANSLATE row for
    // the active picker language. Hosted here (rather than alongside
    // the other overlays at the top of the function) because the
    // onBack callback needs [viewLangTabs] + [selectedViewLangKey]
    // to propagate the user's swiped-to language back up.
    if (promptViewOpen) {
        val promptLanguages = remember(viewLangTabs) {
            buildList {
                add("")
                viewLangTabs.forEach { tab ->
                    if (tab.key != LangTab.ORIGINAL_KEY) add(tab.displayName)
                }
            }
        }
        val backToMain: () -> Unit = {
            promptViewOpen = false
            promptViewLanguage = null
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            PromptViewScreen(
                reportId = reportId,
                availableLanguages = promptLanguages,
                initialLanguage = promptViewLanguage,
                onBack = { activeLang ->
                    val target = activeLang ?: ""
                    val newKey = if (target.isBlank()) LangTab.ORIGINAL_KEY
                        else viewLangTabs.firstOrNull { it.displayName == target }?.key
                            ?: selectedViewLangKey
                    selectedViewLangKey = newKey
                    promptViewOpen = false
                    promptViewLanguage = null
                }
            )
        }
        return
    }
    // Same shape as the PromptView language plumbing above. The block
    // lives here (not next to the var declarations) because
    // [viewLangTabs] + [selectedViewLangKey] are only in scope after
    // the language-picker setup further up.
    if (reportsViewOpen) {
        val reportsLanguages = remember(viewLangTabs) {
            buildList {
                add("")
                viewLangTabs.forEach { tab ->
                    if (tab.key != LangTab.ORIGINAL_KEY) add(tab.displayName)
                }
            }
        }
        val backToMain: () -> Unit = {
            reportsViewOpen = false
            reportsViewLanguage = null
            reportsViewInitialAgentId = null
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.ai.ui.shared.LocalNavigateToCurrentReport provides backToMain
        ) {
            ReportsViewScreen(
                reportId = reportId,
                availableLanguages = reportsLanguages,
                initialLanguage = reportsViewLanguage,
                initialAgentId = reportsViewInitialAgentId,
                onBack = { activeLang ->
                    val target = activeLang ?: ""
                    val newKey = if (target.isBlank()) LangTab.ORIGINAL_KEY
                        else viewLangTabs.firstOrNull { it.displayName == target }?.key
                            ?: selectedViewLangKey
                    selectedViewLangKey = newKey
                    reportsViewOpen = false
                    reportsViewLanguage = null
                    reportsViewInitialAgentId = null
                }
            )
        }
        return
    }

    // "Language missing" popup state — opened when the user taps a
    // grayed tile, dismissed on Cancel or after the user picks a
    // source language to translate from.
    var missingPopup by remember { mutableStateOf<MissingPopupCtx?>(null) }

    fun nativeNameForLang(langName: String): String =
        viewLangTabs.firstOrNull { it.displayName == langName }?.nativeName?.takeIf { it != langName }
            ?: langName

    fun iconForLang(langName: String): String? = when {
        langName.isEmpty() -> originalLanguageIcon?.takeIf { it.isNotBlank() }
        else -> com.ai.data.InternalPromptIconCache.get("translation_icon", langName)
    }

    fun sourceOption(langName: String): SourceOption =
        SourceOption(
            languageId = langName,
            displayName = if (langName.isEmpty()) "Original" else langName,
            icon = iconForLang(langName)
        )

    // Open the popup for the Prompt tile. Source list = every
    // language where the prompt has content (Original if
    // report.prompt is non-blank, else any PROMPT TRANSLATE
    // language) minus the active target. When the user is on the
    // Original tab we translate INTO reportLanguageName (back-
    // translation); when on a non-Original tab we translate INTO
    // that tab's language.
    fun openPromptMissing() {
        val target = currentLanguageState.value ?: ""
        val effectiveTarget = if (target.isEmpty()) {
            reportLanguageName ?: return
        } else target
        val effectiveTargetNative = nativeNameForLang(effectiveTarget)
        val sourceLangs = promptAvailableLangs.filter { it != effectiveTarget && (target.isNotEmpty() || it.isNotEmpty()) }
        val rep = loadedReport ?: return
        missingPopup = MissingPopupCtx(
            tileLabel = "Prompt",
            targetLanguageName = effectiveTarget,
            targetLanguageNative = effectiveTargetNative,
            sources = sourceLangs.map { sourceOption(it) },
            onPick = { src ->
                val sourceText = if (src.isEmpty()) rep.prompt
                else translates.firstOrNull {
                    it.translateSourceKind == "PROMPT" && it.targetLanguage == src
                }?.content.orEmpty()
                if (sourceText.isNotBlank()) {
                    onTranslateMissingItems(
                        listOf(
                            com.ai.viewmodel.ReportViewModel.TranslateMissingItem(
                                sourceKind = "PROMPT",
                                targetId = "prompt",
                                sourceText = sourceText,
                                label = "Report prompt"
                            )
                        ),
                        effectiveTarget,
                        effectiveTargetNative
                    )
                }
            }
        )
    }

    // Reports tile. Source list = every language where any agent
    // has content (Original = original responseBody exists; else
    // an AGENT TRANSLATE row exists). Picking a source produces
    // one AGENT item per successful agent that has source content
    // in that language; agents without it are skipped.
    fun openReportsMissing() {
        val target = currentLanguageState.value ?: ""
        val effectiveTarget = if (target.isEmpty()) {
            reportLanguageName ?: return
        } else target
        val effectiveTargetNative = nativeNameForLang(effectiveTarget)
        val sourceLangs = reportsAvailableLangs.filter { it != effectiveTarget && (target.isNotEmpty() || it.isNotEmpty()) }
        val rep = loadedReport ?: return
        missingPopup = MissingPopupCtx(
            tileLabel = "Reports",
            targetLanguageName = effectiveTarget,
            targetLanguageNative = effectiveTargetNative,
            sources = sourceLangs.map { sourceOption(it) },
            onPick = { src ->
                val items = rep.agents.mapNotNull { agent ->
                    if (agent.reportStatus != com.ai.data.ReportStatus.SUCCESS) return@mapNotNull null
                    val sourceText = if (src.isEmpty()) agent.responseBody.orEmpty()
                    else translates.firstOrNull {
                        it.translateSourceKind == "AGENT" &&
                            it.translateSourceTargetId == agent.agentId &&
                            it.targetLanguage == src
                    }?.content.orEmpty()
                    if (sourceText.isBlank()) return@mapNotNull null
                    val prov = com.ai.data.AppService.findById(agent.provider)?.id ?: agent.provider
                    com.ai.viewmodel.ReportViewModel.TranslateMissingItem(
                        sourceKind = "AGENT",
                        targetId = agent.agentId,
                        sourceText = sourceText,
                        label = "$prov / ${com.ai.ui.shared.shortModelName(agent.model)}"
                    )
                }
                if (items.isNotEmpty()) {
                    onTranslateMissingItems(items, effectiveTarget, effectiveTargetNative)
                }
            }
        )
    }

    // Meta tile. sources = item.availableLanguages minus active
    // target. Items computed from item.sourceRows + ambient
    // translates (own targetLanguage matches OR META TRANSLATE row
    // pointing back at the meta in the chosen source).
    fun openMetaMissing(item: EveryItem) {
        val target = currentLanguageState.value ?: ""
        // When the View picker is on Original we translate INTO the
        // report's detected source language (e.g. "English"). That
        // back-translation lands as a normal TRANSLATE row tagged
        // with reportLanguageName; the View + Detail screens fold
        // that language back into the Original tab via
        // buildLangTabs(originalAlias=...) and buildEveryItems'
        // reportLanguageName fold. If language detection didn't run
        // there's no sensible Original-as-target to translate into —
        // skip silently.
        val effectiveTarget = if (target.isEmpty()) {
            reportLanguageName ?: return
        } else target
        val effectiveTargetNative = nativeNameForLang(effectiveTarget)
        val avail = item.availableLanguages ?: return
        val rows = item.sourceRows ?: return
        // Source list excludes the active target ("" → reportLanguageName).
        // Also drop "" itself since for an item that's grayed on
        // Original there's no Original content to use as source.
        val sourceLangs = avail.filter { it.isNotEmpty() && it != effectiveTarget }
        missingPopup = MissingPopupCtx(
            tileLabel = item.label,
            targetLanguageName = effectiveTarget,
            targetLanguageNative = effectiveTargetNative,
            sources = sourceLangs.map { sourceOption(it) },
            onPick = { src ->
                val items = rows.mapNotNull { meta ->
                    val sourceText = when {
                        src.isEmpty() ->
                            if (meta.targetLanguage.isNullOrBlank()) meta.content.orEmpty() else ""
                        meta.targetLanguage == src -> meta.content.orEmpty()
                        else -> translates.firstOrNull {
                            it.translateSourceKind == "META" &&
                                it.translateSourceTargetId == meta.id &&
                                it.targetLanguage == src
                        }?.content.orEmpty()
                    }
                    if (sourceText.isBlank()) return@mapNotNull null
                    val name = meta.metaPromptName?.takeIf { it.isNotBlank() }
                        ?: com.ai.data.legacyKindDisplayName(meta.kind)
                    val prov = com.ai.data.AppService.findById(meta.providerId)?.id ?: meta.providerId
                    com.ai.viewmodel.ReportViewModel.TranslateMissingItem(
                        sourceKind = "META",
                        targetId = meta.id,
                        sourceText = sourceText,
                        label = "$name: $prov / ${com.ai.ui.shared.shortModelName(meta.model)}"
                    )
                }
                if (items.isNotEmpty()) {
                    onTranslateMissingItems(items, effectiveTarget, effectiveTargetNative)
                }
            }
        )
    }

    // Documents tiles — fixed set, Icons only when the per-model
    // icon chain is enabled in Settings. The tile only OPENS the
    // destination; the View screen itself stays in the back-stack
    // underneath so Android-back from the destination falls back
    // to the View grid rather than the report page.
    // Re-keyed on currentLanguageState.value so the per-tile
    // `enabled` flag re-evaluates when the View picker changes.
    val currentLang = currentLanguageState.value
    val docTiles = remember(perModelIconGenEnabled, currentLang, promptAvailableLangs, reportsAvailableLangs, loadedReport, reportLanguageName, onOpenHtmlPreview, onViewIcons) {
        val promptEnabled = currentLang in promptAvailableLangs
        val reportsEnabled = currentLang in reportsAvailableLangs
        buildList {
            add(IdentifiedTile("doc:Prompt", ViewTile(
                "Prompt", "📝", AppColors.Purple,
                enabled = promptEnabled,
                onMissingClick = if (!promptEnabled) ({ openPromptMissing() }) else null
            ) {
                promptViewLanguage = currentLanguageState.value
                promptViewOpen = true
            }))
            add(IdentifiedTile("doc:Reports", ViewTile(
                "Reports", "📊", AppColors.Blue,
                enabled = reportsEnabled,
                onMissingClick = if (!reportsEnabled) ({ openReportsMissing() }) else null
            ) {
                reportsViewLanguage = currentLanguageState.value
                reportsViewOpen = true
            }))
            add(IdentifiedTile("doc:Costs", ViewTile("Costs", "💰", AppColors.Yellow) { showCostsView = true }))
            // HTML preview, Log, Trace tiles are deliberately omitted
            // from the View grid — the content-only View surface
            // focuses on the report's outputs, not export views or
            // operational logs / API traces. HTML preview remains
            // reachable from Report - manage; Log + Trace from the
            // result page's bottom-bar icons (📜 App Log, 🐞 Trace
            // list).
            add(IdentifiedTile("doc:Icons", ViewTile("Icons", "🖼", AppColors.Orange) { onViewIcons() }))
        }
    }

    // Meta is special-cased: one tile per persisted Meta row (e.g.
    // a Compare run, a Summary run) so the user can jump straight
    // into a specific result instead of going through an
    // aggregated "Meta (N)" tile and a follow-up picker. Each
    // tile's label = the meta prompt name; tap opens that row's
    // detail directly.
    //
    // Emoji = cached per-prompt emoji (falls back to 🧠 while the
    // prompt-icon cache is cold or the master toggle is off). The
    // active language is shown by the View screen's top picker
    // strip; no per-tile language badge needed.
    val metaTiles = remember(everyItems, internalPrompts, useInternalPromptsIcons, iconRefreshTick, currentLang, loadedReport, reportLanguageName, onBack) {
        everyItems["meta"].orEmpty().map { item ->
            val prompt = item.prompt
            // Per-row icon override wins over the shared per-(name,title)
            // cache entry. The row's `icon` is set by the Find-alternative-
            // icons pick flow on this specific SecondaryResult — without
            // this precedence two tiles that share a metaPromptName would
            // both render the cache entry the last alt pick wrote.
            val sourceRow = item.sourceRows?.firstOrNull()
            val rowIcon = sourceRow?.icon?.takeIf { it.isNotBlank() }
            val promptEmoji = rowIcon ?: if (useInternalPromptsIcons && prompt != null && prompt.name.isNotBlank()) {
                val e = com.ai.data.InternalPromptIconCache.get(prompt.name, prompt.title)
                if (e == null) onMissingPromptIcon(prompt)
                e
            } else null
            val metaEnabled = item.availableLanguages?.contains(currentLang) ?: true
            // sourceRows is now single-element per META item — its id
            // disambiguates two tiles that share a metaPromptName so
            // the persisted tile-order map stays unique.
            val rowId = sourceRow?.id ?: item.label
            // Meta tile click routes through the dedicated content-only
            // MetaViewScreen instead of the legacy management-heavy
            // SecondaryResultDetailScreen — sourceRow.id when present
            // points at the persisted row; absent (legacy aggregate
            // tile) falls back to item.open to keep the old picker
            // path available.
            val openMeta: () -> Unit = if (sourceRow != null) {
                {
                    metaViewLanguage = currentLanguageState.value
                    metaViewRowId = sourceRow.id
                }
            } else {
                { item.open(currentLanguageState.value) }
            }
            IdentifiedTile(
                id = "meta:${item.label}:$rowId",
                tile = ViewTile(
                    label = item.label,
                    emoji = promptEmoji ?: "🧠",
                    accent = AppColors.Purple,
                    enabled = metaEnabled,
                    onMissingClick = if (!metaEnabled) ({ openMetaMissing(item) }) else null,
                    onClick = openMeta
                )
            )
        }
    }

    // Fan-out tiles — one per persisted fan-out run, mirroring the
    // metaTiles pattern. Each fan-out prompt is its own tile so
    // the user can jump straight into a specific run instead of
    // going through an aggregated "Fan-out (N)" tile and a
    // follow-up picker. (Fan_out is excluded from computedTiles
    // below.)
    val fanOutTiles = remember(everyItems, currentLang) {
        everyItems["fan_out"].orEmpty().map { item ->
            val fanOutEnabled = item.availableLanguages?.contains(currentLang) ?: true
            IdentifiedTile(
                id = "fan_out:${item.label}",
                tile = ViewTile(
                    label = item.label,
                    emoji = "🌀",
                    accent = AppColors.Indigo,
                    enabled = fanOutEnabled,
                    onClick = { item.open(currentLanguageState.value) }
                )
            )
        }
    }

    // Other computed kinds — one tile per kind. Tap with N=1
    // opens the only item; N≥2 flips [expandedKind] which renders
    // an inline list below the tiles. Meta + Fan-out are excluded;
    // they get one tile per run via [metaTiles] / [fanOutTiles].
    data class ComputedTile(val key: String, val tile: ViewTile, val items: List<EveryItem>)
    val computedTiles = remember(everyItems, moderationFlagged, currentLang) {
        // Moderation accent flips red ↔ green based on whether any
        // moderation row on this report flagged anything; the 🚩
        // emoji is the same either way so the flag motif stays
        // consistent across both states.
        val moderationColor = if (moderationFlagged) AppColors.Red else AppColors.Green
        val specs = listOf(
            ComputedSpec("rerank", "Rerank", "🏆", AppColors.Yellow),
            ComputedSpec("moderation", "Moderation", "🚩", moderationColor),
            ComputedSpec("fan_in", "Fan-in", "🪢", AppColors.Green),
            ComputedSpec("fan-in-model", "Fan-in-model", "🧩", AppColors.Blue),
            ComputedSpec("translate", "Translate", "🌍", AppColors.Orange)
        )
        specs.mapNotNull { s ->
            val items = everyItems[s.key].orEmpty()
            if (items.isEmpty()) null
            else {
                // Tile is enabled iff at least one of its items is
                // available in the active language. Items with
                // availableLanguages == null (language-agnostic) count
                // as always available, so today's non-meta computed
                // tiles always pass this check.
                val tileEnabled = items.any { it.availableLanguages?.contains(currentLang) ?: true }
                ComputedTile(
                    key = s.key,
                    items = items,
                    tile = ViewTile(s.label, s.emoji, s.color, count = items.size, enabled = tileEnabled) {
                        when (items.size) {
                            1 -> openComputedItem(s.key, items[0], currentLanguageState.value,
                                openRerank = { id -> rerankViewRowId = id },
                                openModeration = { id -> moderationViewRowId = id },
                                openFanIn = { id -> fanInViewRowId = id },
                                openFanInModel = { id -> fanInModelViewRowId = id },
                                openTranslate = { runId -> translateViewRunId = runId })
                            else -> { expandedKind = if (expandedKind == s.key) null else s.key }
                        }
                    }
                )
            }
        }
    }

    // Horizontal swipe → prev / next report. Same axis convention as
    // Report - manage's < / > chevrons: swipe RIGHT (drag→) reveals the
    // older neighbour, swipe LEFT (drag←) reveals the newer one. Touch
    // slop on detectHorizontalDragGestures locks the gesture to the
    // horizontal axis once it claims, so vertical scrolls aren't
    // hijacked. The tile reorder uses a long-press to initiate, so a
    // quick horizontal flick goes here, not into a tile drag.
    val swipeDensity = androidx.compose.ui.platform.LocalDensity.current
    val swipeThresholdPx = with(swipeDensity) { 80.dp.toPx() }
    var swipeDragX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .pointerInput(swipeNav) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDragX = 0f },
                    onDragEnd = {
                        when {
                            swipeDragX > swipeThresholdPx -> swipeNav?.onPrev?.invoke()
                            swipeDragX < -swipeThresholdPx -> swipeNav?.onNext?.invoke()
                        }
                        swipeDragX = 0f
                    },
                    onDragCancel = { swipeDragX = 0f },
                    onHorizontalDrag = { _, dx -> swipeDragX += dx }
                )
            }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = loadedReport?.title ?: promptTitle,
            // Orange screen-title row is suppressed on the View tile
            // grid — the title bar's white report title already
            // makes clear which report this is.
            screenTitle = null,
            subject = null,
            helpTopic = "view_ai_report",
            onBack = onBack
        )

        // One picker for the whole View screen; tile clicks below
        // forward the active language to the opened sub-screen.
        // Hidden when no translations exist (single-language report).
        // Bigger icons + centred on the View tile grid since the
        // picker is the dominant row here, not just a tab strip.
        if (viewLangTabs.size > 1) {
            LanguagePickerRow(
                viewLangTabs, selectedViewLangKey,
                onSelect = { selectedViewLangKey = it },
                useIcons = true,
                originalIcon = originalLanguageIcon,
                iconFontSize = 44.sp,
                centered = true
            )
        }

        // Body fills the remaining vertical space between the
        // title bar and the bottom icons bar — without weight(1f)
        // the body would measure to content height and leave an
        // empty gap below it on tall screens. Grid-only now (the
        // list-mode toggle was removed); the grid never scrolls —
        // if 3 tiles per row don't fit vertically, the layout
        // bumps to 4 per row.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            // Always-on tiles + per-meta-item tiles + other grouped
            // computed tiles all flow together as one continuous
            // grid. The meta items get an entry each per the user
            // request; the other computed kinds stay aggregated
            // with their count badge. Each carries a stable
            // identifier so the persisted tile order survives
            // per-report variability.
            val combinedTiles = docTiles + metaTiles + fanOutTiles +
                computedTiles.map { IdentifiedTile("computed:${it.key}", it.tile) }
            val sortedTiles = remember(combinedTiles, savedOrder) {
                val rankOf = savedOrder.withIndex().associate { it.value to it.index }
                combinedTiles.sortedBy { rankOf[it.id] ?: Int.MAX_VALUE }
            }
            // BoxWithConstraints lets the grid see the height
            // it's been allocated so it can pick 3 or 4 cols.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val spacing = 10.dp
                // Tile aspect = 1.05 (width / height). Compute
                // total layout height for a given column count.
                fun layoutHeight(cols: Int): androidx.compose.ui.unit.Dp {
                    val rows = ((sortedTiles.size + cols - 1) / cols).coerceAtLeast(1)
                    val tileWidth = (maxWidth - spacing * (cols - 1)) / cols
                    val tileHeight = tileWidth / 1.05f
                    return tileHeight * rows + spacing * (rows - 1)
                }
                val chosenCols = if (layoutHeight(3) <= maxHeight) 3 else 4
                ReorderableTileFlow(
                    items = sortedTiles,
                    cols = chosenCols,
                    onReorder = { fromId, toId ->
                        val current = sortedTiles.map { it.id }.toMutableList()
                        val fromIdx = current.indexOf(fromId)
                        val toIdx = current.indexOf(toId)
                        if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                            current.removeAt(fromIdx)
                            current.add(toIdx, fromId)
                            // Patch persisted: replace current-visible
                            // segment with the new local order, keep
                            // any non-current ids (from other reports)
                            // in their previous relative positions at
                            // the tail.
                            val currentSet = current.toSet()
                            val newSaved = current + savedOrder.filter { it !in currentSet }
                            savedOrder = newSaved
                            tileOrderPrefs.edit()
                                .putString("tile_order", newSaved.joinToString(","))
                                .apply()
                        }
                    }
                )
            }

            // Inline expansion — full-width card listing each
            // item for the active non-meta computed kind (rerank /
            // fan_out / fan_in / fan-in-model / translate with
            // N≥2 items). Anchored under the grid so the user
            // keeps the rest of the layout in view.
            val open = expandedKind
            if (open != null) {
                val active = computedTiles.firstOrNull { it.key == open }
                if (active != null && active.items.size >= 2) {
                    ExpandedKindCard(
                        title = active.tile.label,
                        items = active.items,
                        currentLanguage = currentLang,
                        onItemClick = { item ->
                            expandedKind = null
                            openComputedItem(active.key, item, currentLanguageState.value,
                                openRerank = { id -> rerankViewRowId = id },
                                openModeration = { id -> moderationViewRowId = id },
                                openFanIn = { id -> fanInViewRowId = id },
                                openFanInModel = { id -> fanInModelViewRowId = id },
                                openTranslate = { runId -> translateViewRunId = runId })
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // "Language missing" popup — listed sources are languages this
    // tile DOES have content in. Picking one fires the translation
    // dispatch and closes the popup; the tile un-grays once the
    // translation row lands on disk.
    val popup = missingPopup
    if (popup != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { missingPopup = null },
            title = {
                androidx.compose.material3.Text(
                    "Language missing for this item.",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    androidx.compose.material3.Text(
                        "Select below a source language to generate a translation",
                        fontSize = 13.sp, color = AppColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (popup.sources.isEmpty()) {
                        androidx.compose.material3.Text(
                            "(no source language available)",
                            fontSize = 13.sp, color = AppColors.TextTertiary
                        )
                    } else {
                        popup.sources.forEach { src ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        missingPopup = null
                                        popup.onPick(src.languageId)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (src.icon != null) {
                                    androidx.compose.material3.Text(
                                        src.icon, fontSize = 24.sp,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.width(36.dp))
                                }
                                androidx.compose.material3.Text(
                                    src.displayName,
                                    color = Color.White, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { missingPopup = null }) {
                    androidx.compose.material3.Text("Cancel")
                }
            }
        )
    }
}

/** A tile + its stable identifier — used to map the on-screen
 *  tile back to a persisted-order entry. Identifier format:
 *  `doc:<label>` for always-on Documents tiles, `meta:<promptName>`
 *  for individual Meta items, `computed:<key>` for the aggregated
 *  Computed kinds (rerank, moderation, fan_out, fan_in,
 *  fan-in-model, translate). */
private data class IdentifiedTile(val id: String, val tile: ViewTile)

/** Visual descriptor for one launcher tile. */
private data class ViewTile(
    val label: String,
    val emoji: String,
    val accent: Color,
    val count: Int = 0,
    /** False → the tile renders at low alpha and (unless
     *  [onMissingClick] is set) ignores taps. Set by the View
     *  screen when the active picker language has no content
     *  available for the tile. */
    val enabled: Boolean = true,
    /** When the tile is disabled but [onMissingClick] is non-null
     *  the tile stays tappable (still dimmed); the tap opens the
     *  "Language missing" popup instead of firing [onClick]. */
    val onMissingClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

/** Constructor descriptor for the Computed section's six possible
 *  kinds. Kept separate from [ViewTile] so the binding step can
 *  attach the `items` list + the lambda once. */
private data class ComputedSpec(
    val key: String,
    val label: String,
    val emoji: String,
    val color: Color
)

/** A picker row in the "Language missing" popup — one language the
 *  tapped tile DOES have content in. `languageId == ""` is Original;
 *  otherwise displayName matches SecondaryResult.targetLanguage. */
private data class SourceOption(
    val languageId: String,
    val displayName: String,
    val icon: String?
)

/** Context for the AlertDialog rendered when the user taps a grayed
 *  View-screen tile. The dialog title names the tile + target
 *  language; tapping a source row fires [onPick] with the chosen
 *  source's languageId. */
private data class MissingPopupCtx(
    val tileLabel: String,
    val targetLanguageName: String,
    val targetLanguageNative: String,
    val sources: List<SourceOption>,
    val onPick: (sourceLanguageId: String) -> Unit
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = AppColors.Blue,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

/** Read the persisted comma-separated tile-order list from
 *  SharedPreferences. Blank / missing → empty list (every tile
 *  falls back to its declaration order). */
private fun loadTileOrder(prefs: android.content.SharedPreferences): List<String> =
    prefs.getString("tile_order", null)
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?: emptyList()

/** Reorderable tile grid. Identical visual layout to [TileFlow]
 *  (3 cols, fixed tile width, 10 dp spacing) plus per-tile
 *  long-press-and-drag handling. Short taps still fire the tile's
 *  onClick; only long-press (~500 ms) starts a drag.
 *
 *  Drop target = whichever tile's recorded layout rect contains
 *  the dragged tile's translated center point on release. When the
 *  drop hits a different tile, [onReorder] fires with the source
 *  and target identifiers; the parent computes the new ordering
 *  and updates the persisted state. Tiles other than the dragged
 *  one stay in place during the drag — no slide animation in v1. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReorderableTileFlow(
    items: List<IdentifiedTile>,
    cols: Int = 3,
    onReorder: (fromId: String, toId: String) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = 10.dp
        val tileWidth = ((maxWidth - spacing * (cols - 1)) / cols) - 0.5.dp

        var draggedId by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        val positions = remember { mutableStateMapOf<String, Rect>() }
        val haptic = LocalHapticFeedback.current

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            maxItemsInEachRow = cols
        ) {
            items.forEach { item ->
                val id = item.id
                val isDragged = draggedId == id
                // Disabled tiles skip the drag-detect (a tile the user
                // can't tap shouldn't be reorderable either) and the
                // dragged-z-index lift. TileCard handles the dim +
                // tap-block on the rendered side.
                val dragModifier = if (!item.tile.enabled) Modifier
                else Modifier
                    .then(
                        if (isDragged) Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                            }
                        else Modifier
                    )
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedId = id
                                dragOffset = Offset.Zero
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                dragOffset += drag
                            },
                            onDragEnd = {
                                val src = draggedId
                                val srcRect = src?.let { positions[it] }
                                if (src != null && srcRect != null) {
                                    val dropPoint = srcRect.center + dragOffset
                                    val dstId = positions.entries
                                        .firstOrNull { it.value.contains(dropPoint) }
                                        ?.key
                                    if (dstId != null && dstId != src) {
                                        onReorder(src, dstId)
                                    }
                                }
                                draggedId = null
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = {
                                draggedId = null
                                dragOffset = Offset.Zero
                            }
                        )
                    }
                Box(
                    modifier = Modifier
                        .width(tileWidth)
                        .onGloballyPositioned { coords ->
                            positions[id] = coords.boundsInParent()
                        }
                        .then(dragModifier)
                ) { TileCard(item.tile) }
            }
        }
    }
}

/** Compact one-row-per-tile list view — the alternate rendering
 *  toggled from the subject-row icon. Each row carries the tile's
 *  accent as a coloured emoji on the left, label in the middle, the
 *  count badge (when N≥2) and a chevron on the right; the whole row
 *  is clickable and fires the same onClick the grid tile would. No
 *  drag-reorder in list mode (reorder lives in grid mode only). */
@Composable
private fun ListTileColumn(items: List<IdentifiedTile>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                val tile = item.tile
                val effectiveClick: (() -> Unit)? = when {
                    tile.enabled -> tile.onClick
                    tile.onMissingClick != null -> tile.onMissingClick
                    else -> null
                }
                val clickMod = if (effectiveClick != null) Modifier.clickable(onClick = effectiveClick) else Modifier
                val dimMod = if (tile.enabled) Modifier else Modifier.alpha(0.22f)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .then(dimMod)
                        .then(clickMod)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tile.emoji, fontSize = 22.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        tile.label, color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (tile.count >= 2) {
                        Box(
                            modifier = Modifier.padding(end = 8.dp)
                                .size(22.dp).clip(CircleShape)
                                .background(tile.accent.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tile.count.toString(),
                                color = Color.White, fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text("›", color = AppColors.TextTertiary, fontSize = 18.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileFlow(tiles: List<ViewTile>) {
    // Every tile is rendered at the SAME fixed width regardless of
    // how many sit in the last row — previously each tile inside
    // FlowRow used weight(1f), which spread the trailing row's
    // tiles across the full container width and made them visibly
    // larger than tiles in fully-packed rows. Compute the per-tile
    // width once from the container's maxWidth + a target column
    // count (2 on a typical phone, 3 on wider screens), then hand
    // that fixed width to every TileCard.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = 10.dp
        val cols = 3
        // Subtract a sub-pixel safety margin from the ideal tile
        // width: pixel rounding (Dp → px) on an exact-fit 3-up
        // layout can land 1 px over the container on certain
        // densities, which makes FlowRow wrap to 2 tiles per row
        // even though we asked for 3. 0.5 dp of slack absorbs that
        // rounding without being visible.
        val tileWidth = ((maxWidth - spacing * (cols - 1)) / cols) - 0.5.dp
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            maxItemsInEachRow = cols
        ) {
            tiles.forEach { tile ->
                Box(modifier = Modifier.width(tileWidth)) { TileCard(tile) }
            }
        }
    }
}

@Composable
private fun TileCard(tile: ViewTile) {
    val accent = tile.accent
    // Disabled tiles render at low alpha; if onMissingClick is set
    // they STAY tappable and route to that handler (the "Language
    // missing" popup) instead of the normal onClick. Otherwise
    // taps pass through with no ripple.
    val effectiveClick: (() -> Unit)? = when {
        tile.enabled -> tile.onClick
        tile.onMissingClick != null -> tile.onMissingClick
        else -> null
    }
    val clickModifier = if (effectiveClick != null) Modifier.clickable(onClick = effectiveClick) else Modifier
    val dimModifier = if (tile.enabled) Modifier else Modifier.alpha(0.22f)
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(1.05f).then(dimModifier).then(clickModifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = listOf(
                    accent.copy(alpha = 0.55f),
                    accent.copy(alpha = 0.18f)
                )
            )
        )) {
            // Count badge — top-right, only when N ≥ 2. A single-item
            // kind opens that item directly on tap (no chooser
            // expansion), so a "1" badge would tell the user nothing
            // they can't already see from the tile itself.
            if (tile.count >= 2) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tile.count.toString(),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(tile.emoji, fontSize = 36.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    tile.label, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExpandedKindCard(
    title: String,
    items: List<EveryItem>,
    currentLanguage: String?,
    onItemClick: (EveryItem) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title, color = AppColors.Blue,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            items.forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                val itemEnabled = item.availableLanguages?.contains(currentLanguage) ?: true
                val clickMod = if (itemEnabled) Modifier.clickable { onItemClick(item) } else Modifier
                val dimMod = if (itemEnabled) Modifier else Modifier.alpha(0.22f)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .then(dimMod)
                        .then(clickMod)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.label, color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("›", color = AppColors.TextTertiary, fontSize = 18.sp)
                }
            }
        }
    }
}

/** Open an [EveryItem] from a non-meta computed tile. For kinds that
 *  have a dedicated content-only View screen (rerank today; the rest
 *  of the View-screen rollout will extend this) we capture the
 *  underlying SecondaryResult id and route to the local overlay state
 *  setter instead of firing the legacy [EveryItem.open]. Kinds without
 *  a View screen fall back to the legacy open lambda so the tile keeps
 *  working until its dedicated screen lands. */
private fun openComputedItem(
    key: String,
    item: EveryItem,
    language: String?,
    openRerank: (String) -> Unit,
    openModeration: (String) -> Unit,
    openFanIn: (String) -> Unit,
    openFanInModel: (String) -> Unit,
    openTranslate: (String) -> Unit
) {
    val seed = item.sourceRows?.firstOrNull()
    val rowId = seed?.id
    when (key) {
        "rerank" -> if (rowId != null) openRerank(rowId) else item.open(language)
        "moderation" -> if (rowId != null) openModeration(rowId) else item.open(language)
        "fan_in" -> if (rowId != null) openFanIn(rowId) else item.open(language)
        "fan-in-model" -> if (rowId != null) openFanInModel(rowId) else item.open(language)
        "translate" -> {
            // Translate items key by translationRunId (or a synthesised
            // lang:<name> sentinel for legacy rows missing a runId).
            // Same scheme buildEveryItems uses for grouping; the
            // TranslateViewScreen then loads every row sharing this id.
            val runKey = seed?.translationRunId ?: seed?.targetLanguage?.let { "lang:$it" }
            if (runKey != null) openTranslate(runKey) else item.open(language)
        }
        else -> item.open(language)
    }
}
