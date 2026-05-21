package com.ai.ui.report.manage.view
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.CollapsibleCard
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.horizontalSwipeNavigation
import com.ai.ui.shared.modelInfoClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext

/**
 * Lists every persisted [SecondaryResult] of a given [kind] for [reportId].
 * Tapping a row opens [SecondaryResultDetailScreen]; the row also exposes a
 * trash icon for direct deletion. When the list reaches zero entries the
 * screen pops itself back to the report.
 */
@Composable
internal fun SecondaryResultsScreen(
    reportId: String,
    kind: SecondaryKind,
    nameFilter: String? = null,
    isBatching: Boolean = false,
    runningFanOutPairs: Set<String> = emptySet(),
    /** Bundle of fan-out throttled state + fan-icons batch state
     *  + the launcher callback. See [FanRuntimeBundle]. */
    fanRuntime: FanRuntimeBundle = FanRuntimeBundle(),
    /** Flip the drill-in into ICONS mode — fired right after the
     *  "Find icons" button kicks off a fan-icons batch so the user
     *  watches the icon progress instead of the static MAIN page. */
    onShowFanIcons: () -> Unit = {},
    /** Flip the drill-in back to MAIN mode — the L1 "Responses"
     *  mode-toggle. */
    onShowResponses: () -> Unit = {},
    /** When true, the fan-out drill-in mounts in ICONS mode —
     *  L1 / L2 / L3 classify pairs by their icon-chain status.
     *  Wired by the main report's "Fan-icons" View button. */
    isFanIconsDrillIn: Boolean = false,
    /** Authoritative Fan Out runtime. When non-null and the screen
     *  is in fan-out drill-in mode, the redesigned FanOutScreen
     *  takes over; legacy FanOutDrillInView remains only for the
     *  edge case where the engine isn't wired (back-compat for
     *  legacy callers that haven't been migrated). Phase F removes
     *  the legacy path entirely. */
    fanOutEngine: com.ai.viewmodel.FanOutEngine? = null,
    fanInPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    /** Per-model fan-in prompt list driving the L2 "New Fan In"
     *  button. Filtered to category="fan-in-model". */
    fanInModelPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    fanOutPrompt: com.ai.model.InternalPrompt? = null,
    onRunFanIn: (() -> Unit)? = null,
    /** Open the per-model fan-in prompt picker scoped to the L2
     *  active (provider, model). Wired by the L2 "New Fan In"
     *  button. */
    onRunModelFanIn: ((activeProviderId: String, activeModel: String) -> Unit)? = null,
    /** Promote the L2 active model's fan-out conversation into a
     *  fresh AI Report. Wired by the "Create Report" button next to
     *  "Switch role" on the L2 header. */
    onCreateReportFromFanOut: ((activeProviderId: String, activeModel: String) -> Unit)? = null,
    onDelete: (String) -> Unit,
    /** Bulk variant — fires off all deletes at once on Dispatchers.IO
     *  rather than calling onDelete() in a tight main-thread loop.
     *  Used by the "Delete this Fan out" confirm dialog where N can be
     *  several hundred (≈ N(N-1) per-pair rows + combined rows). */
    onBulkDelete: (List<String>) -> Unit = { ids -> ids.forEach(onDelete) },
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToTraceRunList: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToInternalPromptEdit: (String) -> Unit = {},
    onResumeStaleFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    onRestartFailedFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    /** Drop every errored fan-out pair without re-firing. */
    onRemoveFailedFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    /** L2-scoped restart: re-fire only the errored pair rows where
     *  the (providerId, model) is the answerer. */
    onRestartFailedFanOutForModel: (com.ai.model.InternalPrompt, String, String) -> Unit = { _, _, _ -> },
    /** L2-scoped remove. */
    onRemoveFailedFanOutForModel: (com.ai.model.InternalPrompt, String, String) -> Unit = { _, _, _ -> },
    onRerunCompleteFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    /** Re-run a single fan-out pair from the L3 "Fan out - pair"
     *  TitleBar's 🔄 reload icon. */
    onRerunFanOutPair: (com.ai.model.InternalPrompt, SecondaryResult) -> Unit = { _, _ -> },
    onDeleteFanOutModel: (String, String, String) -> Unit = { _, _, _ -> },
    /** When non-null, the per-screen language picker is suppressed and
     *  every content lookup is locked to this language. Same convention
     *  as ReportsViewerScreen: null = picker mode (Report - Manage
     *  path), "" = locked to Original, non-empty = locked displayName. */
    forcedLanguage: String? = null,
    /** Open the unified Icon-lookup screen for a fan-out pair.
     *  Plumbed through to [FanOutActions.onOpenPairIconLookup]
     *  for the L2 long-press / L3 big-icon entry points. */
    onOpenPairIconLookup: (String) -> Unit = {},
    /** ICONS-mode "Remove errors" — bridged into
     *  [FanOutActions.onClearFanIconErrors] by splitting the
     *  runKey. (reportId, metaPromptId). */
    onClearFanIconErrors: (String, String) -> Unit = { _, _ -> },
    /** ICONS-mode "Restart errors". (reportId, metaPromptId). */
    onRestartFanIconErrors: (String, String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    // rememberSaveable so the user can drill into a row, jump out to a
    // trace, and return to the same row instead of the list root.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // Disk refresh is event-driven, not poll-based. Three signals
    // cover every meaningful disk transition:
    //   (1) The runningFanOutPairs LaunchedEffect below bumps
    //       refreshTick whenever a pair leaves the in-flight set —
    //       so every "queued → running" or "running → done /
    //       errored" transition lands a fresh disk read within
    //       milliseconds.
    //   (2) Manual deletes / restarts wire their own
    //       secondaryRefreshTick bumps via the action callbacks.
    //   (3) The brief poll burst at the start of `isBatching`
    //       below catches the synchronous placeholder writes that
    //       happen BEFORE any pair acquires a permit — 5 ticks of
    //       500 ms = ~2.5 s, plenty for ~100 placeholder file
    //       writes to settle. Beyond that the loop exits and we
    //       stop wasting disk reads on idle batches (e.g. the
    //       throttle saturated, all pairs queued, no transitions
    //       to observe).
    var sawBatching by remember { mutableStateOf(false) }
    LaunchedEffect(isBatching) {
        if (isBatching) {
            sawBatching = true
            repeat(5) {
                delay(500)
                refreshTick++
            }
        } else if (sawBatching) {
            // One last read after the batch ends so the final
            // completion (which landed on disk after the last burst
            // tick but before isBatching flipped) is reflected.
            refreshTick++
            sawBatching = false
        }
    }
    // When a pair leaves runningFanOutPairs (its semaphore-permitted
    // coroutine finished and dropped its id) the on-disk row already
    // has content/durationMs, but Compose's recomposition fires from
    // the StateFlow change BEFORE produceState's IO block has re-read
    // disk — so the classifier briefly sees `id !in running` and
    // `content == null` and renders "queued" until the next tick.
    // Two fixes layered:
    //   (1) Bump refreshTick immediately so the IO re-read kicks off
    //       on the same frame.
    //   (2) Keep the just-finished ids in `recentlySettled` for ~1 s
    //       — long enough for produceState's IO block to complete
    //       even on slow storage / under heavy I/O load (the original
    //       600 ms was tight on cold caches). The classifier treats
    //       those ids as still in-flight, so a settling pair reads
    //       as "running" until the disk row catches up to "done".
    //       Imperceptible to the user once a row has truly settled
    //       (done/error wins over running in the classifier).
    var prevRunning by remember { mutableStateOf(emptySet<String>()) }
    var recentlySettled by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(runningFanOutPairs) {
        val finished = prevRunning - runningFanOutPairs
        prevRunning = runningFanOutPairs
        if (finished.isNotEmpty()) {
            recentlySettled = recentlySettled + finished
            refreshTick++
            kotlinx.coroutines.delay(1000)
            recentlySettled = recentlySettled - finished
        }
    }
    // Same disk-refresh trigger for the fan-icons batch. During a
    // fan-icons run `runningFanOutPairs` never changes, and the
    // `isBatching` poll burst above only covers the first ~2.5 s — so
    // without this the icon progress page froze on "queued" mid-batch
    // and only re-read disk once the batch ended (jumping straight to
    // the final state). `commitFanOutIconResult` writes the emoji to
    // disk *before* the pair leaves `runningFanIconsPairs`, so a bump
    // on every removal lands a fresh read with the icon already there.
    var prevRunningIcons by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(fanRuntime.runningFanIconsPairs) {
        val finished = prevRunningIcons - fanRuntime.runningFanIconsPairs
        prevRunningIcons = fanRuntime.runningFanIconsPairs
        if (finished.isNotEmpty()) refreshTick++
    }
    // Effective in-flight set passed downstream — the classifier
    // treats anything here as "still running". `done` (content set
    // or durationMs stamped) takes precedence either way, so a row
    // that landed cleanly on disk still reads as ✅ even while it's
    // briefly in `recentlySettled`.
    val effectiveRunningFanOutPairs = remember(runningFanOutPairs, recentlySettled) {
        if (recentlySettled.isEmpty()) runningFanOutPairs
        else runningFanOutPairs + recentlySettled
    }
    // Throttled mirror of refreshTick. Two updaters:
    //   (a) snapshotFlow + sample(500) — for refreshTick bumps that
    //       happen OUTSIDE a batch (user actions, restarts, etc).
    //   (b) Polling loop while isBatching — fires every 500 ms
    //       regardless of refreshTick. The bump-on-pair-leave plumbing
    //       is unreliable during fan-icons bursts (the set churns
    //       faster than the LaunchedEffect can react and ticks get
    //       coalesced); a fixed-period poll guarantees the L1 stats
    //       update at least 2× per second while a batch is in flight.
    var throttledTick by remember { mutableIntStateOf(refreshTick) }
    @OptIn(FlowPreview::class)
    LaunchedEffect(reportId) {
        androidx.compose.runtime.snapshotFlow { refreshTick }
            .sample(500L)
            .collect { throttledTick = it }
    }
    LaunchedEffect(reportId, isBatching) {
        if (isBatching) {
            while (true) {
                throttledTick++
                delay(500)
            }
        } else {
            // Trailing tail — the very last pairs of a batch often
            // commit their disk writes a few hundred ms AFTER
            // isBatching has flipped false. Poll 4 more times so the
            // L1 stats catch those stragglers instead of leaving them
            // stuck in "Queued".
            repeat(4) {
                delay(500)
                throttledTick++
            }
        }
    }
    // Single disk pass per throttledTick. Previously three separate
    // produceStates each called SecondaryResultStorage.listForReport,
    // which re-parses every JSON file in the report's secondary dir
    // regardless of the requested kind (kind filtering is post-parse).
    // For an N-agent Fan out run that's 3 × N(N-1) re-parses every
    // 500 ms while batching. One read, three derived views.
    val allRows by produceState(initialValue = emptyList<SecondaryResult>(), reportId, throttledTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, reportId, kind = null)
        }
    }
    // Parent report — needed only for its emoji icon, prepended to
    // every TitleBar in this screen for parity with the other
    // report-scoped screens.
    val parentReport by produceState<com.ai.data.Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, reportId) }
    }
    val results = remember(allRows, kind, nameFilter) {
        val sameKind = allRows.filter { it.kind == kind }
        if (nameFilter == null) sameKind
        else sameKind.filter {
            val rowName = it.metaPromptName?.takeIf { n -> n.isNotBlank() }
                ?: com.ai.data.legacyKindDisplayName(it.kind)
            rowName == nameFilter
        }
    }
    // Fan_in rows on this report regardless of nameFilter — the
    // filter targets the fan out prompt's name, but fan_in rows
    // carry the (different) fan_in prompt's name. Loaded
    // unconditionally so the fan out detail screen can list every
    // combine-reports follow-up that this report has spawned.
    val fanInRows = remember(allRows) {
        allRows.filter { it.kind == SecondaryKind.META && it.fanInOf != null }
    }
    // TRANSLATE rows on this report — drives the language picker for
    // chat-type META views. Languages not seen on TRANSLATE rows never
    // get a tab even if a per-language batch row exists, since the
    // spec is "show the picker iff there are translations."
    val translates = remember(allRows) {
        allRows.filter { it.kind == SecondaryKind.TRANSLATE && !it.targetLanguage.isNullOrBlank() }
    }
    val showLanguagePicker = kind == SecondaryKind.META && translates.isNotEmpty()
    // Restrict language tabs to languages that have actual content for
    // the meta rows currently on this picker — either a cross-
    // translate TRANSLATE row pointing at one of them, or the seed-
    // language version of one of them. Original is gated on at least
    // one displayed meta having no targetLanguage.
    val languages = remember(translates, results) {
        val displayedIds = results.map { it.id }.toSet()
        val mine = translates.filter {
            it.translateSourceKind == "META" && it.translateSourceTargetId in displayedIds
        }
        val seedTabs = results.mapNotNull { meta ->
            meta.targetLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
                meta.copy(
                    kind = SecondaryKind.TRANSLATE,
                    targetLanguage = lang,
                    targetLanguageNative = meta.targetLanguageNative ?: lang
                )
            }
        }
        val hasOriginal = results.any { it.targetLanguage.isNullOrBlank() }
        buildLangTabs(mine + seedTabs, includeOriginal = hasOriginal)
    }
    // pickerLangKey holds the local picker state used in non-locked
    // mode. When forcedLanguage is non-null the View screen is
    // locking us; we derive selectedLangKey from the forced value
    // and suppress the per-screen picker. See ContentDisplay.kt's
    // ReportsViewerScreenLoaded for the matching convention.
    var pickerLangKey by remember { mutableStateOf(LangTab.ORIGINAL_KEY) }
    LaunchedEffect(languages, forcedLanguage) {
        if (forcedLanguage == null && languages.none { it.key == pickerLangKey }) {
            pickerLangKey = LangTab.ORIGINAL_KEY
        }
    }
    val selectedLangKey: String = if (forcedLanguage != null) {
        if (forcedLanguage.isEmpty()) LangTab.ORIGINAL_KEY
        else forcedLanguage.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
    } else pickerLangKey
    val selectedLanguageName: String? = remember(selectedLangKey, languages, forcedLanguage) {
        when {
            selectedLangKey == LangTab.ORIGINAL_KEY -> null
            // When locked, prefer the forcedLanguage displayName even
            // if the per-screen languages list doesn't include a tab
            // for it — content lookups still want the human-readable
            // name to match SecondaryResult.targetLanguage.
            forcedLanguage != null && forcedLanguage.isNotEmpty() -> forcedLanguage
            else -> languages.firstOrNull { it.key == selectedLangKey }?.displayName
        }
    }

    // For chat-type META: a non-Original language view shows two
    // sources of translated content side by side:
    //   1. Per-language rows tagged with targetLanguage = X.
    //   2. Rows in a DIFFERENT language (Original or a different
    //      seed) whose content has been overlaid by a TRANSLATE row
    //      pointing at them. The overlay copies the translated
    //      content onto the source row so the user sees the
    //      translated text without losing the row's metadata.
    //      Covers both the translate-only flow (Original → X) and
    //      the multi-language meta cross-translate flow (French
    //      seed → Dutch translation).
    // The Original view shows untagged rows only; non-META kinds skip
    // the overlay path entirely (rerank / moderation are structured
    // JSON, not narrative translatable text).
    val filteredResults = remember(results, selectedLangKey, showLanguagePicker, selectedLanguageName, translates) {
        if (!showLanguagePicker) return@remember results
        if (selectedLanguageName == null) return@remember results.filter { it.targetLanguage == null }
        val perLang = results.filter { it.targetLanguage == selectedLanguageName }
        if (kind != SecondaryKind.META) return@remember perLang
        val txByTarget = translates
            .filter {
                it.targetLanguage == selectedLanguageName &&
                    it.translateSourceKind == "META" &&
                    !it.content.isNullOrBlank()
            }
            .associateBy { it.translateSourceTargetId ?: "" }
        val overlaid = results.mapNotNull { s ->
            if (s.targetLanguage == selectedLanguageName) return@mapNotNull null
            val tx = txByTarget[s.id] ?: return@mapNotNull null
            s.copy(content = tx.content)
        }
        perLang + overlaid
    }

    val openResult = openId?.let { id ->
        // filteredResults takes precedence over `results` so the
        // translation language overlay's synthetic copies (same id,
        // translated content) win over the original (untranslated)
        // row. Without this priority, opening a meta row from a
        // translated-language tab surfaced the untranslated source
        // text instead of the translation.
        filteredResults.firstOrNull { it.id == id }
            ?: results.firstOrNull { it.id == id }
            ?: fanInRows.firstOrNull { it.id == id }
    }
    if (openResult != null) {
        SecondaryResultDetailScreen(
            result = openResult,
            onDelete = {
                onDelete(openResult.id)
                openId = null
                refreshTick++
            },
            onBack = { openId = null },
            onNavigateHome = onNavigateHome,
            forcedLanguage = forcedLanguage,
            onDeleteRowById = { rid ->
                onDelete(rid)
                refreshTick++
            }
        )
        return
    }

    // Always entered via the View card buckets, which pass the
    // user-given Meta-prompt name (or the legacy kind label for rows
    // pre-dating the Meta-prompt CRUD). No hardcoded plural labels —
    // the screen is driven entirely by what the bucket button said.
    val baseTitle = nameFilter ?: com.ai.data.legacyKindDisplayName(kind)
    // Fan out drill-in paints its own per-level TitleBar (L1: prompt
    // name + title; L2: active model name with info / delete in the
    // menu bar; L3: "Fan out level 3"). The parent TitleBar is
    // suppressed in that case so we don't render two stacked headers.
    val fanOutRowsAll = filteredResults.filter { it.fanInOf == null }
    // A non-null fanOutPrompt means the route targets a specific
    // fan_out prompt by name — that's a definitive "this is the
    // fan-out drill-in" signal, true even before the run's first
    // placeholder rows land on disk (so a just-started Fan Out opens
    // straight onto the FanOutScreen instead of the meta picker).
    val isFanOutDrillIn = kind == SecondaryKind.META &&
        (fanOutPrompt != null || fanOutRowsAll.any { it.fanOutSourceAgentId != null })
    // META rows surface inside MetaResultsPickerView (the picker
    // buttons + inline body). Hoist the selected-id state up here so
    // the parent's TitleBar can offer the trace icon for the
    // currently-selected secondary — the picker view used to carry
    // its own inline 🐞.
    val isMetaPickerMode = kind == SecondaryKind.META && !isFanOutDrillIn
    val pickerSelectedId = if (isMetaPickerMode) {
        rememberSaveable(filteredResults.map { it.id }) { mutableStateOf(filteredResults.firstOrNull()?.id) }
    } else null
    val pickerSelected = pickerSelectedId?.value?.let { id ->
        filteredResults.firstOrNull { it.id == id }
    } ?: filteredResults.firstOrNull()
    val pickerTraceFilename by produceState<String?>(initialValue = null, pickerSelected?.id) {
        val sel = pickerSelected ?: return@produceState
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == sel.reportId && it.model == sel.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - sel.timestamp) }?.filename
        }
    }
    var pickerConfirmDelete by remember { mutableStateOf(false) }
    // No padding for the fan-out drill-in branch — FanOutScreen
    // brings its own padding(start/end/top = 16.dp). Stacking the
    // wrapper's padding on top would double the inset and push the
    // top title bar 16dp lower than every other screen.
    val outerPadding = if (isFanOutDrillIn) Modifier else Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(outerPadding)) {
        if (!isFanOutDrillIn) {
            val tfTop = pickerTraceFilename
            val pickerProviderService = pickerSelected?.providerId?.let { AppService.findById(it) }
            // 👁 → Main View grid (the list view has no per-row context).
            val pendingListHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewListJump: (() -> Unit)? = pendingListHolder?.let {
                { it.value = com.ai.ui.shared.ViewJump.Main }
            }
            TitleBar(
                helpTopic = "secondary_list",
                title = "Secondary results",
                reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: "📝",
                subject = baseTitle,
                onBackClick = onBack,
                onTrace = if (isMetaPickerMode && ApiTracer.isTracingEnabled && tfTop != null) {
                    { onNavigateToTraceFile(tfTop) }
                } else null,
                onOpenView = onOpenViewListJump,
                onInfo = if (isMetaPickerMode && pickerSelected != null && pickerProviderService != null) {
                    { onNavigateToModelInfo(pickerProviderService, pickerSelected.model) }
                } else null,
                onDelete = if (isMetaPickerMode && pickerSelected != null) {
                    { pickerConfirmDelete = true }
                } else null
            )
            com.ai.ui.shared.HardcodedSubjectRow(baseTitle)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Picker delete confirm — moved up to the parent so the
        // title-bar 🗑 icon (above) can drive it; the META picker
        // view used to host the bottom Delete / Model buttons.
        if (pickerConfirmDelete && pickerSelected != null) {
            val sel = pickerSelected
            val noun = (sel.metaPromptName?.takeIf { it.isNotBlank() }
                ?: com.ai.data.legacyKindDisplayName(sel.kind)).lowercase()
            val provDisplay = AppService.findById(sel.providerId)?.id ?: sel.providerId
            AlertDialog(
                onDismissRequest = { pickerConfirmDelete = false },
                title = { Text("Delete this $noun?") },
                text = { Text(com.ai.ui.shared.modelLabel(provDisplay, sel.model)) },
                confirmButton = {
                    TextButton(onClick = {
                        pickerConfirmDelete = false
                        onDelete(sel.id)
                        refreshTick++
                    }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
                },
                dismissButton = { TextButton(onClick = { pickerConfirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
            )
        }

        // Gated on !isFanOutDrillIn for the same reason the parent
        // TitleBar above is: the fan-out drill-in paints its own
        // L1/L2/L3 chrome and has no per-language content selection,
        // so a stray language-picker row would just float above its
        // title bar.
        if (showLanguagePicker && !isFanOutDrillIn && forcedLanguage == null) {
            LanguagePickerRow(
                languages = languages,
                selectedKey = selectedLangKey,
                onSelect = { pickerLangKey = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (filteredResults.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val msg = if (showLanguagePicker && selectedLanguageName != null)
                    "No ${baseTitle.lowercase()} for $selectedLanguageName yet"
                else "No ${baseTitle.lowercase()} for this report"
                Text(msg, color = AppColors.TextSecondary, fontSize = 14.sp)
            }
            return@Column
        }

        // Fan-out META: every fan out row carries a fanOutSourceAgentId
        // pointing at the source whose response the answerer reacted to.
        // The L1 → L2 → L3 drill-in below replaces the flat picker. The
        // fan_in combine-reports rows live in fanInRows
        // (loaded unconditionally above so the nameFilter doesn't hide
        // them) and surface as the second list above the answerers.
        if (isFanOutDrillIn && fanOutEngine != null && fanOutPrompt != null) {
            // Redesigned fan-out drill-in. Resolves the runKey from
            // (reportId, prompt.id) and hands off to FanOutScreen,
            // which subscribes to fanOutEngine.runs and renders L1/L2/L3
            // reactively. Hydration on entry seeds the StateFlow from
            // disk; subsequent disk changes (via the existing
            // ReportViewModel runners) re-hydrate on each refresh tick.
            val runKey = com.ai.data.runKey(reportId, fanOutPrompt.id)
            // One hydrate per ~500 ms regardless of refreshTick rate.
            // Re-keying on refreshTick (the old pattern) cancelled the
            // in-flight 1k-file hydrate mid-read on every burst tick,
            // so the L1 stats froze for the whole batch then jumped
            // 100+ in one frame at the end. throttledTick (defined
            // above) is the shared lag-once-per-window signal —
            // allRows below sees the same throttle so we don't double
            // up on disk reads.
            LaunchedEffect(reportId, runKey, throttledTick) {
                withContext(Dispatchers.IO) { fanOutEngine.hydrate(context, reportId) }
            }
            val actions = FanOutActions(
                onDeleteRun = { rk ->
                    // Return the Job — FanOutL1Screen awaits it behind a
                    // "Deleting Fan Out" popup, then navigates back. No
                    // premature refreshTick: the row would otherwise
                    // linger half-deleted on the report screen.
                    fanOutEngine.deleteRun(context, rk)
                },
                onClearFanIcons = { rk ->
                    // ICONS-mode 🗑 — clears the fan-icons only, keeps
                    // the fan-out. Job awaited behind the same popup.
                    fanOutEngine.clearFanIcons(context, rk)
                },
                onRerunComplete = { rk ->
                    fanOutEngine.rerunComplete(context, rk)
                    refreshTick++
                },
                onRemoveFailedPairs = { rk ->
                    fanOutEngine.removeFailedPairs(context, rk)
                    refreshTick++
                },
                onRemoveBenchedPairs = { rk ->
                    fanOutEngine.removeBenchedPairs(context, rk)
                    refreshTick++
                },
                onRestartFailedPairs = { _ ->
                    // Route through the legacy path (rerunFailedFanOutPairs
                    // → resetAndRelaunch → rerunFanOutPlaceholders) which
                    // fires every errored pair in parallel via async /
                    // awaitAll, populates runningFanOutPairs for the
                    // in-flight overlay, and applies per-host throttle
                    // caps. The disk rows are reset synchronously before
                    // the dispatch, so the refreshTick bump re-hydrates
                    // engine state to PENDING for those pairs.
                    onRestartFailedFanOut(fanOutPrompt)
                    refreshTick++
                },
                onRemoveFailedPairsForModel = { rk, prov, mdl ->
                    fanOutEngine.removeFailedPairsForModel(context, rk, prov, mdl)
                    refreshTick++
                },
                onRestartFailedPairsForModel = { _, prov, mdl ->
                    // L2-scoped — same rationale as onRestartFailedPairs.
                    onRestartFailedFanOutForModel(fanOutPrompt, prov, mdl)
                    refreshTick++
                },
                onRerunPair = { rk, pk ->
                    fanOutEngine.rerunPair(context, rk, pk)
                    refreshTick++
                },
                onCancelPair = { rk, pk ->
                    fanOutEngine.cancelPair(context, rk, pk)
                    refreshTick++
                },
                onDeleteModelFromRun = { rk, prov, mdl ->
                    fanOutEngine.deleteModelFromRun(context, rk, prov, mdl)
                    refreshTick++
                },
                onRunFanIn = { _ -> onRunFanIn?.invoke() },
                onRunModelFanIn = { _, prov, mdl -> onRunModelFanIn?.invoke(prov, mdl) },
                onCreateReportFromFanOut = { _, prov, mdl -> onCreateReportFromFanOut?.invoke(prov, mdl) },
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToTraceRunList = onNavigateToTraceRunList,
                onNavigateToModelInfo = onNavigateToModelInfo,
                onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
                onOpenSecondary = { id -> openId = id },
                onOpenPairIconLookup = onOpenPairIconLookup,
                onClearFanIconErrors = { rk ->
                    val parts = rk.split("|", limit = 2)
                    if (parts.size == 2) onClearFanIconErrors(parts[0], parts[1])
                },
                onRestartFanIconErrors = { rk ->
                    val parts = rk.split("|", limit = 2)
                    if (parts.size == 2) onRestartFanIconErrors(parts[0], parts[1])
                }
            )
            FanOutScreen(
                engine = fanOutEngine,
                reportId = reportId,
                runKey = runKey,
                actions = actions,
                runningSet = effectiveRunningFanOutPairs,
                throttledSet = fanRuntime.throttledFanOutPairs,
                mode = if (isFanIconsDrillIn) FanOutMode.ICONS else FanOutMode.MAIN,
                runningIconsSet = fanRuntime.runningFanIconsPairs,
                throttledIconsSet = fanRuntime.throttledFanIconsPairs,
                onLaunchFanIcons = { _ ->
                    fanRuntime.onLaunchFanIconsBatch(reportId, fanOutPrompt.id)
                    onShowFanIcons()
                },
                onShowFanIcons = onShowFanIcons,
                onShowResponses = onShowResponses,
                onBack = onBack
            )
            return@Column
        }
        if (isFanOutDrillIn) {
            // Legacy path — kept only for callers that haven't been
            // migrated to pass `fanOutEngine`. Phase F deletes both
            // the call and the FanOutDrillInView Composable.
            FanOutDrillInView(
                reportId = reportId,
                results = fanOutRowsAll,
                combinedRows = fanInRows,
                fanInPrompts = fanInPrompts,
                fanInModelPrompts = fanInModelPrompts,
                fanOutPrompt = fanOutPrompt,
                runningFanOutPairs = effectiveRunningFanOutPairs,
                onRunFanIn = onRunFanIn,
                onRunModelFanIn = onRunModelFanIn,
                onCreateReportFromFanOut = onCreateReportFromFanOut,
                onDelete = { id -> onDelete(id); refreshTick++ },
                onBulkDelete = { ids -> onBulkDelete(ids); refreshTick++ },
                onOpen = { id -> openId = id },
                onNavigateToTraceFile = onNavigateToTraceFile,
                onNavigateToModelInfo = onNavigateToModelInfo,
                onNavigateToInternalPromptEdit = onNavigateToInternalPromptEdit,
                onResumeStaleFanOut = onResumeStaleFanOut,
                onRestartFailedFanOut = { mp ->
                    onRestartFailedFanOut(mp); refreshTick++
                },
                onRemoveFailedFanOut = { mp ->
                    onRemoveFailedFanOut(mp); refreshTick++
                },
                onRestartFailedFanOutForModel = { mp, prov, mdl ->
                    onRestartFailedFanOutForModel(mp, prov, mdl); refreshTick++
                },
                onRemoveFailedFanOutForModel = { mp, prov, mdl ->
                    onRemoveFailedFanOutForModel(mp, prov, mdl); refreshTick++
                },
                onRerunCompleteFanOut = onRerunCompleteFanOut,
                onRerunFanOutPair = onRerunFanOutPair,
                onDeleteFanOutModel = { mpid, prov, model ->
                    onDeleteFanOutModel(mpid, prov, model); refreshTick++
                },
                onBack = onBack
            )
            return@Column
        }

        // Chat-type META: mirror the Reports viewer — a row of picker
        // buttons (one per filtered result) followed by the selected
        // result's body inline. RERANK / MODERATION / TRANSLATE keep
        // the row-list-and-drill-in flow since each item is a distinct
        // table or per-input classification.
        if (kind == SecondaryKind.META) {
            MetaResultsPickerView(
                results = filteredResults,
                selectedIdState = pickerSelectedId!!,
                onDelete = { id -> onDelete(id); refreshTick++ },
                onNavigateToModelInfo = onNavigateToModelInfo
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filteredResults, key = { it.id }) { r ->
                SecondaryRow(
                    r,
                    onClick = { openId = r.id },
                    onDelete = { onDelete(r.id); refreshTick++ }
                )
                HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
            }
        }
    }
}

/** Reports-viewer-style screen for chat-type META results: a FlowRow
 *  of picker buttons (one per result) plus the selected result's
 *  content inline. Picker labels show the result's provider · model.
 *  The trailing action row mirrors [SecondaryResultDetailScreen]:
 *  Delete / Model Info / Trace. */
@Composable
private fun ColumnScope.MetaResultsPickerView(
    results: List<SecondaryResult>,
    selectedIdState: androidx.compose.runtime.MutableState<String?>,
    onDelete: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit
) {
    var selectedId by selectedIdState
    LaunchedEffect(results) {
        if (results.none { it.id == selectedId }) selectedId = results.firstOrNull()?.id
    }
    val selected = results.firstOrNull { it.id == selectedId } ?: results.firstOrNull() ?: return

    // Picker buttons — one per result, FlowRow so wide rows wrap
    // gracefully on narrow viewports.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        results.forEach { r ->
            val isSelected = r.id == selectedId
            val provider = AppService.findById(r.providerId)?.id ?: r.providerId
            Button(
                onClick = { selectedId = r.id },
                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) AppColors.Orange else Color(0xFF3A3A4A)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 36.dp)
            ) {
                Text(
                    com.ai.ui.shared.modelLabel(provider, r.model),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, softWrap = false
                )
            }
        }
    }

    // The provider / model / timestamp header that used to live
    // here is gone — the highlighted picker button above already
    // names the active model, the title-bar carries trace / model
    // info / delete, and the timestamp wasn't load-bearing.

    // Selected item body (scrolls independently of the picker row).
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        when {
            selected.errorMessage != null -> {
                // Translate the legacy "Interrupted by app restart"
                // marker (any persisted row stamped before commit
                // d2cbf97c carries it) into the user-friendly
                // "No data yet" so the user no longer sees system
                // language on a row that just hasn't been re-run yet.
                val msg = com.ai.ui.shared.friendlyErrorMessage(selected.errorMessage)
                Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                Text(msg, fontSize = 13.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            selected.content.isNullOrBlank() -> {
                Text("(no content)", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
            else -> {
                ContentWithThinkSections(analysis = selected.content)
            }
        }
    }
    // The bottom Delete / Model action row collapsed into the
    // parent's title-bar 🗑 / ℹ️ icons; the trace 🐞 lives there
    // too. The confirm dialog moved up alongside.
}

@Composable
private fun SecondaryRow(r: SecondaryResult, onClick: () -> Unit, onDelete: () -> Unit) {
    val provider = AppService.findById(r.providerId)?.id ?: r.providerId
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val rowRunning = r.errorMessage == null && r.content.isNullOrBlank() && r.durationMs == null
        // Status cell: ⏳ while running, ❌ on error (both convey
        // important status — never replaced), else the cached meta
        // emoji when present (replaces ✅), else ✅.
        if (rowRunning) {
            com.ai.ui.shared.AnimatedHourglass(fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        } else if (r.errorMessage != null) {
            Text("❌", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        } else {
            val cachedEmoji = remember(r.metaPromptName) {
                r.metaPromptName?.takeIf { it.isNotBlank() }
                    ?.let { com.ai.data.InternalPromptIconCache.getByName(it) }
            }
            Text(
                cachedEmoji ?: "✅",
                fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(com.ai.ui.shared.modelLabel(provider, r.model), fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { confirmDelete = true }) {
            Text("🗑", fontSize = 16.sp, color = AppColors.Red)
        }
    }

    if (confirmDelete) {
        val noun = (r.metaPromptName?.takeIf { it.isNotBlank() }
            ?: com.ai.data.legacyKindDisplayName(r.kind)).lowercase()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this $noun?") },
            text = { Text(com.ai.ui.shared.modelLabel(provider, r.model)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

