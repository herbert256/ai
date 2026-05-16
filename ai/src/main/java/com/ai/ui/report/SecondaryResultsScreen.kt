package com.ai.ui.report

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
    forcedLanguage: String? = null
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
            forcedLanguage = forcedLanguage
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
            TitleBar(
                helpTopic = "secondary_list",
                title = "Secondary results",
                reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: "📝",
                subject = baseTitle,
                onBackClick = onBack,
                onTrace = if (isMetaPickerMode && ApiTracer.isTracingEnabled && tfTop != null) {
                    { onNavigateToTraceFile(tfTop) }
                } else null,
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
                onOpenSecondary = { id -> openId = id }
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
                Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                Text(selected.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary,
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

/** Role-aware row in the L2 list — built by [FanOutDrillInView]
 *  and passed to [OnePageView] / used for prev-next stepping on L3. */
private data class L2Row(
    /** Stable key: source.agentId for Responder, "$pid|$mdl|$srcAgentId"
     *  for Initiator (one row per (answerer, source) pair so
     *  multi-agent active models surface every response). */
    val key: String,
    val provider: String,
    val model: String,
    /** "$answererPid|$answererMdl|$srcAgentId" — the pair the row maps
     *  to on L3. */
    val l3PairKey: String,
    val pair: SecondaryResult?,
    /** In Initiator mode when the active model has more than one
     *  source agent (Swarm), this disambiguates which source the row
     *  responded to. Rendered as a sub-label beneath the answerer
     *  line. Null in Responder mode and in single-agent Initiator
     *  cases (the source is unambiguous). */
    val sourceLabel: String? = null,
    /** Source agent id for Initiator rows; needed by OnePageView to
     *  group rows by source under one shared "source response"
     *  header. Null in Responder mode (the row's `key` is the source
     *  agent id there). */
    val sourceAgentId: String? = null
)

/** L1 → L2 → L3 drill-in for fan-out META results.
 *  L1: list of every successful report-model that produced at least
 *      one response row (the "answerer"), with progress bars + stats
 *      + a button row for restart-failed / show-prompt / edit-prompt
 *      / rerun-complete / delete-fan out.
 *  L2: for the chosen model, list rows according to the active
 *      [Role]: Responder lists every source the model responded to,
 *      Initiator lists every answerer that responded to the model.
 *      A "One page view" button stitches the role-aware content into
 *      a single scrollable page.
 *  L3: split view — top half shows the source's report response,
 *      bottom half shows the answerer's response content (or a
 *      ⏳ / 🕓 / ❌ / "(no result)" placeholder if missing). Previous /
 *      Next at the bottom step through the L2 list in role-aware
 *      order.
 */
@Composable
private fun ColumnScope.FanOutDrillInView(
    reportId: String,
    results: List<SecondaryResult>,
    combinedRows: List<SecondaryResult> = emptyList(),
    fanInPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    /** Per-model fan-in prompt list driving the L2 "New Fan In"
     *  button. Filtered to category="fan-in-model". */
    fanInModelPrompts: List<com.ai.model.InternalPrompt> = emptyList(),
    fanOutPrompt: com.ai.model.InternalPrompt? = null,
    runningFanOutPairs: Set<String> = emptySet(),
    onRunFanIn: (() -> Unit)? = null,
    onRunModelFanIn: ((activeProviderId: String, activeModel: String) -> Unit)? = null,
    /** Promote the L2 active model's fan-out conversation into a
     *  fresh AI Report. */
    onCreateReportFromFanOut: ((activeProviderId: String, activeModel: String) -> Unit)? = null,
    onDelete: (String) -> Unit,
    /** Bulk variant for the per-fan out delete sweep. */
    onBulkDelete: (List<String>) -> Unit = { ids -> ids.forEach(onDelete) },
    /** Open a SecondaryResultDetailScreen for the given row id. Used by
     *  the combined-reports list above the answerers — tapping a row
     *  pops out the full content + delete / model / trace controls. */
    onOpen: (String) -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToInternalPromptEdit: (String) -> Unit = {},
    onResumeStaleFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    onRestartFailedFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    onRemoveFailedFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    onRestartFailedFanOutForModel: (com.ai.model.InternalPrompt, String, String) -> Unit = { _, _, _ -> },
    onRemoveFailedFanOutForModel: (com.ai.model.InternalPrompt, String, String) -> Unit = { _, _, _ -> },
    onRerunCompleteFanOut: (com.ai.model.InternalPrompt) -> Unit = {},
    onRerunFanOutPair: (com.ai.model.InternalPrompt, SecondaryResult) -> Unit = { _, _ -> },
    onDeleteFanOutModel: (String, String, String) -> Unit = { _, _, _ -> },
    /** Exit the fan out drill-in entirely. Wired to the L1 TitleBar's
     *  "< Back" — L2 / L3 back arrows pop one level instead. */
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    // "Subject to title bar" setting — when true, each L1/L2/L3
    // TitleBar carries the dynamic subject (prompt name, model name,
    // source-agent label) in-bar and the matching inline green
    // sub-header is suppressed. HARDCODED keeps the legacy layout:
    // static "Fan out" in the bar, big green subject below.
    // Load the parent report once so L1 / L2 can label rows by the
    // agents' provider/model and L3 can pull source.responseBody.
    val report by produceState<com.ai.data.Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, reportId) }
    }
    val r = report
    if (r == null) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp)
        }
        return
    }

    val successful = remember(r) {
        r.agents.filter { it.reportStatus == com.ai.data.ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    }
    val agentsById = remember(successful) { successful.associateBy { it.agentId } }
    // All-agents map (every status) — only used for label lookups, so
    // an orphan source (agent that has since failed or had its
    // responseBody cleared) still renders as "$prov / $model" instead
    // of falling back to its raw UUID. agentsById stays SUCCESS-only
    // because every read path other than labelling needs the body.
    val agentsByIdAll = remember(r) { r.agents.associateBy { it.agentId } }

    // Latest-by-timestamp result per (answerer.providerId, answerer.model, sourceAgentId).
    // Ties on millisecond timestamp resolve by id so the survivor is
    // deterministic regardless of filesystem listing order — burst
    // retries can stamp two rows in the same ms.
    val latestByPair = remember(results) {
        val byKey = LinkedHashMap<String, SecondaryResult>()
        results.sortedWith(compareBy({ it.timestamp }, { it.id })).forEach { row ->
            val src = row.fanOutSourceAgentId ?: return@forEach
            val key = "${row.providerId}|${row.model}|$src"
            byKey[key] = row // last write wins → newest
        }
        byKey
    }

    // The L1 LaunchedEffect that fired resumeStaleFanOutPairs was
    // retired — the report-open orchestrator (resumeStaleRunsForReport,
    // ReportViewModel.kt) already walks every metaPromptId and calls
    // resumeStaleFanOutPairs for each. Keeping the L1 trigger meant
    // the L1 fired on every aiSettings re-emit (fanOutPrompt re-keys
    // by object identity), which under config-change churn could
    // overlap with the orchestrator's call and slip past the
    // staleResumeScans guard between scan-body-exit and rerun-Job-
    // start. The viewmodel now holds the dedup key through the
    // rerun Job's full lifetime, so even if both call sites fired
    // serially they'd dedup correctly.

    // Scope rememberSaveable buckets per-(report, fan out prompt). Without
    // the key suffix, rotating the device while looking at Fan out
    // prompt B's L2 selection restored prompt A's saved value first,
    // and only the subsequent LaunchedEffect cleared it — but rotation
    // already painted one frame with prompt A's stale selection.
    val savePromptKey = "${reportId}/${fanOutPrompt?.id ?: "none"}"
    var selectedModelKey by rememberSaveable(savePromptKey) { mutableStateOf<String?>(null) }
    // L2 role state — Responder = this model is the answerer (existing
    // behaviour); Initiator = this model is the source. Saved so the
    // user can drill out and back without losing the toggle.
    var selectedRole by rememberSaveable(savePromptKey) { mutableStateOf("Responder") }
    var l3AnswererKey by rememberSaveable(savePromptKey) { mutableStateOf<String?>(null) }
    var l3SourceAgentId by rememberSaveable(savePromptKey) { mutableStateOf<String?>(null) }
    // Switching between Fan out prompts on the same report keeps the
    // composable instance alive — without this, the L2 role + drill-in
    // selection from Prompt A would carry over to a freshly opened
    // Prompt B (whose agent set may not even contain the carried key).
    //
    // The reset must NOT fire on initial composition (e.g. after a
    // device rotation or process-death restoration): rememberSaveable
    // has just restored the prior L2 / L3 state, and an unconditional
    // reset would wipe it. Track the last-applied id and only act on
    // an actual transition.
    var lastResetPromptId by remember { mutableStateOf(fanOutPrompt?.id) }
    LaunchedEffect(fanOutPrompt?.id) {
        val curId = fanOutPrompt?.id
        if (curId != lastResetPromptId) {
            selectedRole = "Responder"
            selectedModelKey = null
            l3AnswererKey = null
            l3SourceAgentId = null
            lastResetPromptId = curId
        }
    }
    // Likewise: opening a different model on the same Fan out should
    // start in Responder mode and with no L3 selection. Without this,
    // the role state set on Model A's L2 carries to Model B's L2 even
    // though the user picked it from a fresh L1 row.
    LaunchedEffect(selectedModelKey) {
        if (selectedModelKey != null) {
            selectedRole = "Responder"
            l3AnswererKey = null
            l3SourceAgentId = null
        }
    }
    // Full-screen overlays inside this view — `if (showX) { X(); return }`
    // pattern documented in CLAUDE.md so the parent's rememberSaveable
    // state survives.
    var showPromptViewer by rememberSaveable { mutableStateOf(false) }
    var showOnePageView by rememberSaveable { mutableStateOf(false) }

    val currentLevel = when {
        l3AnswererKey != null && l3SourceAgentId != null -> 3
        selectedModelKey != null -> 2
        else -> 1
    }

    BackHandler(enabled = showPromptViewer) { showPromptViewer = false }
    BackHandler(enabled = showOnePageView) { showOnePageView = false }
    BackHandler(enabled = !showPromptViewer && !showOnePageView && currentLevel == 3) {
        l3AnswererKey = null; l3SourceAgentId = null
    }
    BackHandler(enabled = !showPromptViewer && !showOnePageView && currentLevel == 2) {
        selectedModelKey = null
    }

    // Read-only prompt viewer overlay
    if (showPromptViewer && fanOutPrompt != null) {
        Text(fanOutPrompt.name, fontSize = 16.sp, color = AppColors.Blue,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        if (fanOutPrompt.title.isNotBlank()) {
            Text(fanOutPrompt.title, fontSize = 12.sp, color = AppColors.TextSecondary)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(fanOutPrompt.text, fontSize = 13.sp, color = AppColors.TextSecondary,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showPromptViewer = false },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("Close", fontSize = 13.sp) }
        return
    }

    // Stat / status helpers shared between L1 / L2. A row with
    // durationMs stamped but blank content is a successful empty-body
    // completion — same classifier fix as the L1 stats counters.
    // Classifier order: errored → done (terminal: any of content set
    // or durationMs stamped) → running (only while still in flight) →
    // queued. Done has to come before running so a row that finished
    // with an empty body (durationMs set, content blank) doesn't
    // briefly read as "running" if the executor's finally hasn't
    // dropped its id from runningFanOutPairs yet.
    fun rowState(row: SecondaryResult?): String = when {
        row == null -> "queued"
        row.errorMessage != null -> "errored"
        !row.content.isNullOrBlank() || row.durationMs != null -> "done"
        row.id in runningFanOutPairs -> "running"
        else -> "queued"
    }

    // Build the role-aware ordered list for L2 / L3 stepping.
    val activeKey = selectedModelKey
    val activePid = activeKey?.split("|")?.getOrNull(0).orEmpty()
    val activeMdl = activeKey?.split("|")?.getOrNull(1).orEmpty()
    val activeAgents = remember(successful, activeKey) {
        if (activeKey == null) emptyList()
        else successful.filter { it.provider == activePid && it.model == activeMdl }
    }
    val activeAgentIds = remember(activeAgents) { activeAgents.map { it.agentId }.toHashSet() }

    // L2 list rows — ordering depends on role.
    val l2Rows: List<L2Row> = remember(activeKey, selectedRole, latestByPair, successful, results, activeAgents) {
        if (activeKey == null) emptyList()
        else if (selectedRole == "Responder") {
            // sources = every successful agent except this model itself
            successful
                .filter { it.agentId !in activeAgentIds }
                .map { src ->
                    val pair = latestByPair["$activeKey|${src.agentId}"]
                    L2Row(
                        key = src.agentId,
                        provider = src.provider, model = src.model,
                        l3PairKey = "$activeKey|${src.agentId}",
                        pair = pair,
                        sourceAgentId = src.agentId
                    )
                }
        } else {
            // Initiator — every (answerer pid+mdl, source agentId)
            // pair where the source is one of this model's agents.
            // For multi-agent active models (Swarm) this surfaces
            // every response instead of collapsing them to one row
            // per answerer (which would hide rows + undercount cost).
            // Order: answerer in successful order, then by source
            // agent in activeAgents order, so consecutive rows of the
            // same answerer cluster together.
            val ansOrder = successful
                .filter { it.agentId !in activeAgentIds }
                .map { "${it.provider}|${it.model}" }
                .distinct()
                .withIndex()
                .associate { (i, k) -> k to i }
            val srcOrder = activeAgents.withIndex().associate { (i, a) -> a.agentId to i }
            val multiAgent = activeAgents.size > 1
            val byPair = results
                .filter {
                    it.fanOutSourceAgentId in activeAgentIds &&
                        it.fanInOf == null
                }
                .groupBy { "${it.providerId}|${it.model}|${it.fanOutSourceAgentId}" }
                .mapValues { (_, rs) -> rs.maxByOrNull { it.timestamp }!! }
                .values
            byPair.sortedWith(
                compareBy(
                    { ansOrder["${it.providerId}|${it.model}"] ?: Int.MAX_VALUE },
                    { srcOrder[it.fanOutSourceAgentId] ?: Int.MAX_VALUE }
                )
            ).map { row ->
                val srcAgentId = row.fanOutSourceAgentId.orEmpty()
                val src = activeAgents.firstOrNull { it.agentId == srcAgentId }
                val sourceLabel = if (multiAgent && src != null) {
                    val pn = AppService.findById(src.provider)?.id ?: src.provider
                    "↤ $pn / ${src.model}"
                } else null
                L2Row(
                    key = "${row.providerId}|${row.model}|$srcAgentId",
                    provider = row.providerId, model = row.model,
                    l3PairKey = "${row.providerId}|${row.model}|$srcAgentId",
                    pair = row,
                    sourceLabel = sourceLabel,
                    sourceAgentId = srcAgentId
                )
            }
        }
    }

    // ===== L3 (split view) — needs l2Rows for prev/next =====
    val srcAgentIdL3 = l3SourceAgentId
    val answererKeyL3 = l3AnswererKey
    if (srcAgentIdL3 != null && answererKeyL3 != null) {
        val pairResult = latestByPair["$answererKeyL3|$srcAgentIdL3"]
        val sourceAgent = agentsById[srcAgentIdL3]
        // Prefer the SUCCESS-only map for label, but fall back to the
        // all-agents map so an orphan source still reads as
        // "$prov / $model" rather than the raw UUID.
        val sourceLabelAgent = sourceAgent ?: agentsByIdAll[srcAgentIdL3]
        val sourceLabel = sourceLabelAgent?.let {
            val pn = AppService.findById(it.provider)?.id ?: it.provider
            com.ai.ui.shared.modelLabel(pn, it.model, separator = " / ")
        } ?: srcAgentIdL3
        val answererLabel = answererKeyL3.split("|").let { parts ->
            val pid = parts.getOrNull(0).orEmpty()
            val mdl = parts.getOrNull(1).orEmpty()
            val pn = AppService.findById(pid)?.id ?: pid
            com.ai.ui.shared.modelLabel(pn, mdl, separator = " / ")
        }
        // Source-side trace — closest report-agent run for the source
        // model. Hoisted above the TitleBar so the bar's 🐞 slot can
        // open it. The previous inline icon above the source body is
        // gone now; the TitleBar names the source and offers its
        // trace, the answerer/response pane below keeps its own 🐞
        // for the fan-out call trace.
        val srcTraceState = produceState<String?>(initialValue = null, reportId, sourceAgent?.model) {
            value = if (sourceAgent == null) null else withContext(Dispatchers.IO) {
                ApiTracer.getTraceFiles()
                    .filter { it.reportId == reportId && it.model == sourceAgent.model }
                    .maxByOrNull { it.timestamp }?.filename
            }
        }
        val srcTrace = srcTraceState.value
        var confirmPairDelete by remember(pairResult?.id) { mutableStateOf(false) }
        TitleBar(
            helpTopic = "secondary_fan_out_l3",
            title = "Fan out - pair",
            subject = sourceLabel,
            reportIcon = report?.icon?.takeIf { it.isNotBlank() } ?: "📝",
            onBackClick = { l3AnswererKey = null; l3SourceAgentId = null },
            onTrace = if (ApiTracer.isTracingEnabled && srcTrace != null) {
                { onNavigateToTraceFile(srcTrace) }
            } else null,
            // Reload re-runs only THIS pair (reset + relaunch the
            // single placeholder). Visible only when both the fan-out
            // prompt and the pair row are resolved — without either
            // there's no placeholder to re-run.
            onReload = if (fanOutPrompt != null && pairResult != null) {
                { onRerunFanOutPair(fanOutPrompt, pairResult) }
            } else null,
            // Trash deletes only THIS pair's placeholder. Pops back to
            // L2 once the row is gone so the user doesn't sit on a
            // dangling detail page for a deleted row.
            onDelete = if (pairResult != null) {
                { confirmPairDelete = true }
            } else null
        )
        if (confirmPairDelete && pairResult != null) {
            AlertDialog(
                onDismissRequest = { confirmPairDelete = false },
                title = { Text("Delete this pair?") },
                text = { Text("Removes the answerer's response for $sourceLabel.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmPairDelete = false
                        onDelete(pairResult.id)
                        l3AnswererKey = null
                        l3SourceAgentId = null
                    }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmPairDelete = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            )
        }
        com.ai.ui.shared.HardcodedSubjectRow(sourceLabel)
        Spacer(modifier = Modifier.height(4.dp))
        // Walk the L2 list in role-aware order via horizontal swipes —
        // left advances to the next pair, right backs up to the
        // previous one. l3PairKey is "$pid|$mdl|$srcAgentId"; split on
        // the LAST `|` so a model name containing a literal pipe
        // doesn't shift the ans/src boundary.
        val currentPairKey = "$answererKeyL3|$srcAgentIdL3"
        val currentIdx = remember(l2Rows, currentPairKey) {
            l2Rows.indexOfFirst { it.l3PairKey == currentPairKey }
        }
        fun gotoPair(row: L2Row?) {
            row ?: return
            val pivot = row.l3PairKey.lastIndexOf('|')
            val ans = if (pivot > 0) row.l3PairKey.substring(0, pivot) else row.l3PairKey
            val src = if (pivot > 0) row.l3PairKey.substring(pivot + 1) else ""
            l3AnswererKey = ans
            l3SourceAgentId = src
        }
        // Source response (top) + answerer response (bottom). Top
        // wraps to its content but is capped at half the available
        // height so a long source body can never push the response
        // pane off screen. Mirrors TranslationCallDetailScreen's
        // split-pane shape.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .horizontalSwipeNavigation(
                    key1 = currentPairKey,
                    key2 = l2Rows,
                    atFirst = currentIdx <= 0,
                    atLast = currentIdx < 0 || currentIdx >= l2Rows.size - 1,
                    onSwipeLeft = {
                        if (currentIdx in 0 until l2Rows.size - 1) gotoPair(l2Rows.getOrNull(currentIdx + 1))
                    },
                    onSwipeRight = {
                        if (currentIdx > 0) gotoPair(l2Rows.getOrNull(currentIdx - 1))
                    }
                )
        ) {
            val halfMax = maxHeight / 2
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = halfMax)
                        .verticalScroll(rememberScrollState())
                ) {
                    val srcBody = sourceAgent?.responseBody
                    if (srcBody.isNullOrBlank()) {
                        Text("(source response missing)", color = AppColors.TextTertiary, fontSize = 13.sp)
                    } else {
                        ContentWithThinkSections(analysis = srcBody)
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 8.dp))
                // Bottom half — answerer's response. Header is the
                // answerer model name (replaces the literal
                // "Response" label), with the same 🐞 lookup as
                // before pinned to the right.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(answererLabel, fontSize = 13.sp, color = AppColors.Green,
                            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val tf by produceState<String?>(initialValue = null, pairResult?.id, pairResult?.model) {
                            val res = pairResult
                            value = if (res == null) null else withContext(Dispatchers.IO) {
                                ApiTracer.getTraceFiles()
                                    .filter { it.reportId == reportId && it.model == res.model }
                                    .minByOrNull { kotlin.math.abs(it.timestamp - res.timestamp) }?.filename
                            }
                        }
                        val tfNonNull = tf
                        if (ApiTracer.isTracingEnabled && tfNonNull != null) {
                            Text("🐞", fontSize = 16.sp,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .clickable { onNavigateToTraceFile(tfNonNull) })
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    when {
                        pairResult == null -> Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                        pairResult.errorMessage != null -> {
                            Text("❌ ${pairResult.errorMessage}", fontSize = 13.sp, color = AppColors.Red)
                        }
                        !pairResult.content.isNullOrBlank() -> ContentWithThinkSections(analysis = pairResult.content)
                        // durationMs is stamped on every successful and errored
                        // save (cleared by resetAndRelaunch). A row with
                        // durationMs set but blank content is a successful
                        // empty-body completion; treat it as terminal so the
                        // view doesn't loop on Running…/Queued forever.
                        pairResult.durationMs != null -> {
                            Text("(empty response)", fontSize = 13.sp, color = AppColors.TextTertiary)
                        }
                        pairResult.id in runningFanOutPairs -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.ai.ui.shared.AnimatedHourglass(fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Running…", fontSize = 13.sp, color = AppColors.TextSecondary)
                            }
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🕓", fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Queued", fontSize = 13.sp, color = AppColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // ===== L2 (one-page view overlay) =====
    if (showOnePageView && activeKey != null) {
        OnePageView(
            reportId = reportId,
            role = selectedRole,
            activePid = activePid,
            activeMdl = activeMdl,
            activeAgents = activeAgents,
            l2Rows = l2Rows,
            agentsById = agentsById,
            onBack = { showOnePageView = false },
            onSwitchRole = {
                selectedRole = if (selectedRole == "Responder") "Initiator" else "Responder"
            },
            onNavigateToModelInfo = onNavigateToModelInfo,
            onNavigateToTraceFile = onNavigateToTraceFile
        )
        return
    }

    // ===== L2 (role-aware list) =====
    if (activeKey != null) {
        val provName = AppService.findById(activePid)?.id ?: activePid
        val activeProviderService = AppService.findById(activePid)
        var confirmModelDelete by remember { mutableStateOf(false) }
        var confirmRestartFailedL2 by remember { mutableStateOf(false) }
        var confirmRemoveFailedL2 by remember { mutableStateOf(false) }
        // Trace filename for the active model's report-agent run.
        // Hoisted out of the `if (selectedRole == "Initiator")` block
        // so toggling role doesn't kill and restart the produceState
        // (which has to walk ApiTracer.getTraceFiles() on cold cache).
        // The 🐞 still only renders in Initiator mode below.
        val activeFirstAgent = activeAgents.firstOrNull()
        val activeModelTrace by produceState<String?>(initialValue = null, reportId, activeFirstAgent?.model) {
            value = if (activeFirstAgent == null) null else withContext(Dispatchers.IO) {
                ApiTracer.getTraceFiles()
                    .filter { it.reportId == reportId && it.model == activeFirstAgent.model }
                    .maxByOrNull { it.timestamp }?.filename
            }
        }
        // Static "Fan out - model" page title in the menu bar; ℹ️ → Model
        // Info, 🗑 → drop the model from this Fan out, 🐞 (Initiator
        // only) → trace for this model's report-agent run. The
        // active model name surfaces as a green sub-header below.
        val l2Trace = activeModelTrace
        val l2Subject = com.ai.ui.shared.modelLabel(provName, activeMdl, separator = " / ")
        TitleBar(
            helpTopic = "secondary_fan_out_l2",
            title = "Fan out - model",
            subject = l2Subject,
            reportIcon = report?.icon?.takeIf { it.isNotBlank() } ?: "📝",
            onBackClick = { selectedModelKey = null },
            onInfo = if (activeProviderService != null) {
                { onNavigateToModelInfo(activeProviderService, activeMdl) }
            } else null,
            onDelete = { confirmModelDelete = true },
            onTrace = if (selectedRole == "Initiator" && ApiTracer.isTracingEnabled && l2Trace != null) {
                { onNavigateToTraceFile(l2Trace) }
            } else null
        )
        com.ai.ui.shared.HardcodedSubjectRow(
            l2Subject,
            providerService = activeProviderService,
            model = activeMdl
        )
        // Row 1: role label + Switch role button.
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)) {
            Text("Role: $selectedRole", fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f))
            Button(
                onClick = { selectedRole = if (selectedRole == "Responder") "Initiator" else "Responder" },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) { Text("Switch role", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Row 2: Create Report + New Fan In on their own row, equal weight.
        // Disabled when the L2 active model has no fan-out rows where
        // it is the source — those rows become the new report's
        // agents (Create Report) or the responder set the fan-in
        // template walks over (New Fan In), so without them there's
        // nothing to feed in.
        val hasInitiatorRows = remember(latestByPair, activeAgentIds) {
            latestByPair.values.any { it.fanOutSourceAgentId in activeAgentIds }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { onCreateReportFromFanOut?.invoke(activePid, activeMdl) },
                enabled = onCreateReportFromFanOut != null && hasInitiatorRows,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp)
            ) { Text("Create Report", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { onRunModelFanIn?.invoke(activePid, activeMdl) },
                enabled = onRunModelFanIn != null && fanInModelPrompts.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp)
            ) { Text("New Fan In", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        // L2-scoped error count: failed pair rows where the active
        // (provider, model) is the answerer. Errors on OTHER models'
        // rows aren't touched by the L2 buttons — the user came here
        // to look at THIS model's slice.
        val l2ErroredCount = remember(results, activePid, activeMdl) {
            results.count {
                it.fanOutSourceAgentId != null &&
                    it.fanInOf == null &&
                    it.errorMessage != null &&
                    it.providerId.equals(activePid, ignoreCase = true) &&
                    it.model == activeMdl
            }
        }
        if (l2ErroredCount > 0 && fanOutPrompt != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { confirmRemoveFailedL2 = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("Remove failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { confirmRestartFailedL2 = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("Restart failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Per-model total cost across the per-pair rows. Memoised so
        // role flip / batching tick recompositions don't re-walk the
        // list. Rendered as a footer item inside the LazyColumn
        // below — matching the L1 page's per-row layout — so the
        // total visually belongs to the rows it sums.
        val totalCost = remember(l2Rows) {
            l2Rows.sumOf { it.pair?.let { p -> (p.inputCost ?: 0.0) + (p.outputCost ?: 0.0) } ?: 0.0 }
        }
        if (confirmRestartFailedL2 && fanOutPrompt != null) {
            com.ai.ui.shared.ReloadConfirmationDialog(
                target = "",
                title = "Restart failed items for this model?",
                message = "Re-fires $l2ErroredCount failed pair${if (l2ErroredCount == 1) "" else "s"} where ${activePid}/${activeMdl} is the answerer. The runner's concurrency cap still applies, so larger failure sets surface as a mix of running and queued rows. Other models' rows are kept.",
                confirmLabel = "Restart",
                onConfirm = {
                    confirmRestartFailedL2 = false
                    onRestartFailedFanOutForModel(fanOutPrompt, activePid, activeMdl)
                },
                onDismiss = { confirmRestartFailedL2 = false }
            )
        }
        if (confirmRemoveFailedL2 && fanOutPrompt != null) {
            AlertDialog(
                onDismissRequest = { confirmRemoveFailedL2 = false },
                title = { Text("Remove failed items for this model?") },
                text = {
                    Text("Drops $l2ErroredCount failed pair${if (l2ErroredCount == 1) "" else "s"} where ${activePid}/${activeMdl} is the answerer. No API calls are made. Other models' rows are kept.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRemoveFailedL2 = false
                        onRemoveFailedFanOutForModel(fanOutPrompt, activePid, activeMdl)
                    }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemoveFailedL2 = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            )
        }

        // Model-scoped fan-in rows for THIS L2 active model. Filtered
        // out of `combinedRows` (which itself is `fanInRows` — every
        // fan_in row on the report) by scopeProviderId / scopeModel.
        // Rendered at the top of the L2 list above the per-pair rows.
        val modelScopedFanIn = remember(combinedRows, activePid, activeMdl) {
            combinedRows.filter { it.scopeProviderId == activePid && it.scopeModel == activeMdl }
                .sortedByDescending { it.timestamp }
        }
        // Auto-scroll the L2 list to the top whenever a new
        // model-scoped fan-in row appears (mirroring L1's
        // combinedRows scroll-on-grow behaviour).
        val l2ListState = androidx.compose.foundation.lazy.rememberLazyListState()
        var lastModelScopedSize by remember { mutableIntStateOf(modelScopedFanIn.size) }
        LaunchedEffect(modelScopedFanIn.size) {
            if (modelScopedFanIn.size > lastModelScopedSize) {
                l2ListState.animateScrollToItem(0)
            }
            lastModelScopedSize = modelScopedFanIn.size
        }

        if (l2Rows.isEmpty() && modelScopedFanIn.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedRole == "Responder") "No responses for this model yet"
                    else "No other model has responded to this one yet",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(state = l2ListState, modifier = Modifier.weight(1f)) {
                // Model-scoped fan-in rows (created by the
                // "Create a model fan in report" button above) sit
                // at the top of the list, before the per-pair rows.
                // Each row's status icon mirrors L1's combinedRows
                // (✅ on success / errored / ⏳ while in flight).
                if (modelScopedFanIn.isNotEmpty()) {
                    item(key = "msfi-header") {
                        Text("Model fan in", fontSize = 12.sp,
                            color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                    items(modelScopedFanIn, key = { "msfi-${it.id}" }) { row ->
                        val cost = (row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)
                        val provLabel = AppService.findById(row.providerId)?.id ?: row.providerId
                        val nameLabel = row.metaPromptName?.takeIf { it.isNotBlank() }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onOpen(row.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.padding(end = 8.dp).width(20.dp),
                                contentAlignment = Alignment.Center) {
                                when {
                                    row.errorMessage != null -> Text("❌", fontSize = 16.sp)
                                    !row.content.isNullOrBlank() || row.durationMs != null ->
                                        Text("✅", fontSize = 16.sp)
                                    else -> com.ai.ui.shared.AnimatedHourglass(fontSize = 16.sp)
                                }
                            }
                            val rowText = if (nameLabel != null) "$nameLabel · ${com.ai.ui.shared.modelLabel(provLabel, row.model)}"
                                else com.ai.ui.shared.modelLabel(provLabel, row.model)
                            Text(
                                rowText, fontSize = 14.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (cost > 0.0) {
                                Text(formatCents(cost), fontSize = 11.sp,
                                    color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(end = 8.dp))
                            }
                            Text(">", fontSize = 16.sp, color = AppColors.Blue)
                        }
                        HorizontalDivider(color = AppColors.DividerDark)
                    }
                    if (l2Rows.isNotEmpty()) {
                        item(key = "msfi-section-gap") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(if (selectedRole == "Responder") "Responses" else "Pairs",
                                fontSize = 12.sp,
                                color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
                items(l2Rows, key = { it.key }) { row ->
                    val rowProv = AppService.findById(row.provider)?.id ?: row.provider
                    val state = rowState(row.pair)
                    val cost = row.pair?.let { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } ?: 0.0
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                // Split off the trailing srcAgentId via
                                // lastIndexOf so a model name with a `|`
                                // doesn't shift the boundary.
                                val pivot = row.l3PairKey.lastIndexOf('|')
                                val ans = if (pivot > 0) row.l3PairKey.substring(0, pivot) else row.l3PairKey
                                val src = if (pivot > 0) row.l3PairKey.substring(pivot + 1) else ""
                                if (src.isNotBlank()) {
                                    l3AnswererKey = ans
                                    l3SourceAgentId = src
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.padding(end = 8.dp).width(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (state) {
                                "running" -> com.ai.ui.shared.AnimatedHourglass(fontSize = 16.sp)
                                "queued" -> Text("🕓", fontSize = 16.sp)
                                "errored" -> Text("❌", fontSize = 16.sp)
                                else -> Text("✅", fontSize = 16.sp)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(com.ai.ui.shared.modelLabel(rowProv, row.model), fontSize = 14.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            row.sourceLabel?.let { label ->
                                Text(label, fontSize = 11.sp, color = AppColors.TextTertiary,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (cost > 0.0) {
                            Text(formatCents(cost), fontSize = 11.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(">", fontSize = 16.sp, color = AppColors.Blue)
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                // Footer row mirroring the per-pair layout — Total
                // label on the left, summed cost on the right. Same
                // status-icon-width spacer + chevron-width spacer as
                // the data rows so the columns line up vertically.
                // Hidden when every row has zero cost.
                if (totalCost > 0.0) {
                    item(key = "l2-total-footer") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.padding(end = 8.dp).width(20.dp))
                            Text("Total", fontSize = 14.sp, color = AppColors.Blue,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(formatCents(totalCost), fontSize = 11.sp,
                                color = AppColors.Blue, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp))
                            // Match the ">" tap chevron's slot on
                            // the data rows so the cost column lines
                            // up vertically.
                            Box(modifier = Modifier.width(16.dp))
                        }
                    }
                }
            }
        }
        // Bottom button — One page view
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { showOnePageView = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("One page view", fontSize = 13.sp, maxLines = 1, softWrap = false) }

        if (confirmModelDelete) {
            AlertDialog(
                onDismissRequest = { confirmModelDelete = false },
                title = { Text("Delete this model from the fan out?") },
                text = {
                    Text("Drop every response row where $provName / $activeMdl is " +
                        "either the answerer or the source. Other Fan out runs on this " +
                        "report are not affected. Can't be undone.")
                },
                confirmButton = {
                    // Disable the Delete button when fanOutPrompt is
                    // null instead of letting the click fall through
                    // and silently no-op. Audit Bug 28: the previous
                    // flow opened the dialog, the user tapped Delete,
                    // nothing happened, the dialog stayed open — and
                    // the user had no signal why.
                    val cp = fanOutPrompt
                    TextButton(
                        enabled = cp != null,
                        onClick = {
                            if (cp != null) {
                                confirmModelDelete = false
                                onDeleteFanOutModel(cp.id, activePid, activeMdl)
                                selectedModelKey = null
                            }
                        }
                    ) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmModelDelete = false }) {
                        Text("Cancel", maxLines = 1, softWrap = false)
                    }
                }
            )
        }
        return
    }

    // ===== L1 — model rows + progress / stats / actions =====
    // Keep `successful` as the only key — the row list shape only
    // depends on which agents are alive, not on every per-pair update.
    // Orphan keys (rows whose answerer's report-agent later failed) are
    // appended below so their responses remain visible.
    val orphanKeys = remember(latestByPair, successful) {
        val live = successful.map { "${it.provider}|${it.model}" }.toHashSet()
        latestByPair.values
            .map { "${it.providerId}|${it.model}" }
            .toSet()
            .minus(live)
            .toList()
    }
    val modelKeys = remember(successful, orphanKeys) {
        // Sort by model name (case-insensitive) — the dominant
        // column the user scans. Provider id is the tiebreaker for
        // models shared across providers (e.g. llama-3.3-70b on
        // both Groq and Together).
        (successful.map { "${it.provider}|${it.model}" }.distinct() + orphanKeys)
            .sortedWith(compareBy(
                { it.substringAfter('|').lowercase(java.util.Locale.ROOT) },
                { it.substringBefore('|').lowercase(java.util.Locale.ROOT) }
            ))
    }

    // Stats — derived from the fan out rows + running set. A row counts
    // as processed once the executor has stamped durationMs (set on
    // every successful and errored save, cleared by resetAndRelaunch).
    // Without that signal, a successful call that returned an empty
    // body (no text, no error) would slip past the content-non-blank
    // check, get dropped from runningFanOutPairs in the finally block,
    // and silently land in Queued instead of Done. Memoized so the
    // four passes only re-run when the inputs actually change instead
    // of on every recomposition (e.g., L2 ↔ L1 navigation, prompt
    // viewer overlay open/close).
    data class Stats(val total: Int, val done: Int, val errored: Int, val running: Int)
    val stats = remember(results, runningFanOutPairs) {
        var done = 0; var errored = 0; var running = 0
        results.forEach { r ->
            when {
                r.errorMessage != null -> errored++
                !r.content.isNullOrBlank() || r.durationMs != null -> done++
                r.id in runningFanOutPairs -> running++
            }
        }
        Stats(results.size, done, errored, running)
    }
    val totalPairs = stats.total
    val doneCount = stats.done
    val erroredCount = stats.errored
    val runningCount = stats.running
    val queuedCount = (totalPairs - doneCount - erroredCount - runningCount).coerceAtLeast(0)
    val pendingCount = runningCount + queuedCount

    // Confirm dialogs hoisted above the TitleBar so the bar's reload
    // and delete icons can drive the same flows the in-card buttons
    // used to.
    var confirmRerunComplete by remember { mutableStateOf(false) }
    var confirmFanOutDelete by remember { mutableStateOf(false) }
    var confirmRestartFailedL1 by remember { mutableStateOf(false) }
    var confirmRemoveFailedL1 by remember { mutableStateOf(false) }
    // Static "Fan out" page title in the menu bar; the dynamic
    // prompt name + title surfaces as a green sub-header in the body
    // (or folded into the TitleBar when "Subject to title bar" is
    // anything other than HARDCODED).
    val l1SubHeader = when {
        fanOutPrompt == null -> ""
        fanOutPrompt.title.isBlank() -> fanOutPrompt.name
        else -> "${fanOutPrompt.name} — ${fanOutPrompt.title}"
    }
    TitleBar(
        helpTopic = "secondary_fan_out_l1",
        title = "Fan out",
        subject = l1SubHeader.takeIf { it.isNotBlank() },
        reportIcon = report?.icon?.takeIf { it.isNotBlank() } ?: "📝",
        onBackClick = onBack,
        onReload = if (fanOutPrompt != null) ({ confirmRerunComplete = true }) else null,
        onDelete = { confirmFanOutDelete = true }
    )
    com.ai.ui.shared.HardcodedSubjectRow(l1SubHeader)
    if (fanInPrompts.isNotEmpty() && onRunFanIn != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onRunFanIn() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
        ) { Text("Run a Fan in prompt", fontSize = 13.sp, maxLines = 1, softWrap = false) }
    }
    // Per-failure controls — only visible when at least one pair row
    // errored. Both buttons follow the runner's throttle, so a big
    // failure set re-fires as a mix of ⏳ RUNNING + 🕓 PENDING rows.
    if (erroredCount > 0 && fanOutPrompt != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { confirmRemoveFailedL1 = true },
                modifier = Modifier.weight(1f)
            ) { Text("Remove failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { confirmRestartFailedL1 = true },
                modifier = Modifier.weight(1f)
            ) { Text("Restart failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    // Top progress bar — total expected pairs.
    if (pendingCount > 0 && totalPairs > 0) {
        val finished = (doneCount + erroredCount).toFloat() / totalPairs
        LinearProgressIndicator(
            progress = { finished },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = AppColors.Orange,
            trackColor = AppColors.DividerDark
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Per-row aggregated counters + cost. Hoisted out of the
    // LazyColumn item body so the O(N²) inner scan only runs when
    // one of its inputs actually changes (latestByPair, successful,
    // runningFanOutPairs, orphan list). Without this, every
    // recomposition — and there are many during a live batch as
    // runningFanOutPairs mutates 2× per pair — re-scanned the full
    // grid for every visible row.
    data class L1RowStats(
        val ok: Int, val err: Int, val run: Int,
        val totalSources: Int, val cost: Double
    )
    val rowStatsByKey = remember(modelKeys, results, runningFanOutPairs) {
        // Group placeholders by answerer key once so the per-row
        // totals derive from the actual on-disk pair count instead of
        // re-deriving from `successful`. This naturally honours
        // Manual / TopRanked scope (fewer placeholders than
        // successful×successful) and the Swarm-with-duplicate-(prov,
        // model)-members case (each answerer agent owns its own
        // placeholder, all of them counted toward the row).
        val pairRows = results.filter { it.fanOutSourceAgentId != null && it.fanInOf == null }
        val byAk = pairRows.groupBy { "${it.providerId}|${it.model}" }
        modelKeys.associateWith { ak ->
            var ok = 0; var err = 0; var run = 0; var cost = 0.0; var total = 0
            byAk[ak]?.forEach { res ->
                total++
                cost += (res.inputCost ?: 0.0) + (res.outputCost ?: 0.0)
                when {
                    res.errorMessage != null -> err++
                    !res.content.isNullOrBlank() || res.durationMs != null -> ok++
                    res.id in runningFanOutPairs -> run++
                }
            }
            L1RowStats(ok, err, run, total, cost)
        }
    }

    // Total cost across every answerer-row + combined-report row on
    // this report. Computed up here so the LazyColumn footer item
    // below can render it; the previous standalone banner above the
    // list moved into the list itself per user request.
    val totalAnswerersCost = remember(latestByPair, combinedRows) {
        latestByPair.values.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } +
            combinedRows.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) }
    }

    // Scroll to the top whenever a new combined-reports row appears
    // (the user just tapped "Combine reports and all fan out responses"
    // — the placeholder row materialises a tick or two later, and we
    // want it visible immediately so it doesn't get lost below the
    // fold). Tracking the count change rather than identity covers
    // both the immediate-render and after-batch-join paths.
    val l1ListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var lastCombinedSize by remember { mutableIntStateOf(combinedRows.size) }
    LaunchedEffect(combinedRows.size) {
        if (combinedRows.size > lastCombinedSize) {
            l1ListState.animateScrollToItem(0)
        }
        lastCombinedSize = combinedRows.size
    }
    LazyColumn(state = l1ListState, modifier = Modifier.weight(1f)) {
        // Fan_in combine-reports follow-ups for this report.
        if (combinedRows.isNotEmpty()) {
            item(key = "ac-header") {
                Text("Combined reports", fontSize = 12.sp,
                    color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            val sortedCombined = combinedRows.sortedByDescending { it.timestamp }
            items(sortedCombined, key = { "ac-${it.id}" }) { row ->
                val acProv = AppService.findById(row.providerId)?.id ?: row.providerId
                val acCost = (row.inputCost ?: 0.0) + (row.outputCost ?: 0.0)
                val acLabel = row.metaPromptName?.takeIf { it.isNotBlank() }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onOpen(row.id) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.padding(end = 8.dp).width(20.dp),
                        contentAlignment = Alignment.Center) {
                        when {
                            row.errorMessage != null -> Text("❌", fontSize = 16.sp)
                            // durationMs is stamped on every successful +
                            // errored save (cleared by resetAndRelaunch).
                            // A row with content set OR durationMs set is
                            // terminal — without the durationMs check, a
                            // successful empty-body fan-in completion
                            // would keep spinning forever. Same classifier
                            // shape as the L1 stats counters / the L3
                            // detail body.
                            !row.content.isNullOrBlank() || row.durationMs != null ->
                                Text("✅", fontSize = 16.sp)
                            else -> com.ai.ui.shared.AnimatedHourglass(fontSize = 16.sp)
                        }
                    }
                    val rowText = if (acLabel != null) "$acLabel · ${com.ai.ui.shared.modelLabel(acProv, row.model)}"
                        else com.ai.ui.shared.modelLabel(acProv, row.model)
                    Text(
                        rowText, fontSize = 14.sp, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (acCost > 0.0) {
                        Text(formatCents(acCost), fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(">", fontSize = 16.sp, color = AppColors.Blue)
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
            item(key = "ac-section-gap") {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Models", fontSize = 12.sp,
                    color = AppColors.Blue, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        val orphanSetForRender = orphanKeys.toHashSet()
        items(modelKeys, key = { it }) { ak ->
            val parts = ak.split("|")
            val pid = parts.getOrNull(0).orEmpty()
            val mdl = parts.getOrNull(1).orEmpty()
            val provName = AppService.findById(pid)?.id ?: pid
            val rs = rowStatsByKey[ak] ?: L1RowStats(0, 0, 0, 0, 0.0)
            val rowFinished = rs.ok + rs.err
            val rowPending = (rs.totalSources - rowFinished).coerceAtLeast(0)
            val isOrphan = ak in orphanSetForRender
            val labelColor = if (isOrphan) AppColors.TextDisabled else Color.White
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { selectedModelKey = ak; selectedRole = "Responder" }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.padding(end = 8.dp).width(20.dp),
                    contentAlignment = Alignment.Center) {
                    when {
                        // Orphan = source-of-truth report-agent has
                        // failed; the row only exists because past
                        // responses survived the agent's regression.
                        isOrphan -> Text("🚫", fontSize = 16.sp)
                        rs.run > 0 -> com.ai.ui.shared.AnimatedHourglass(fontSize = 16.sp)
                        rowPending > 0 -> Text("🕓", fontSize = 16.sp)
                        rs.err > 0 -> Text("❌", fontSize = 16.sp)
                        else -> Text("✅", fontSize = 16.sp)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(com.ai.ui.shared.modelLabel(provName, mdl), fontSize = 14.sp, color = labelColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (rowPending > 0 && rs.totalSources > 0) {
                        // Per-row progress bar replaces the legacy "X / Y" text.
                        LinearProgressIndicator(
                            progress = { rowFinished.toFloat() / rs.totalSources },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(4.dp),
                            color = AppColors.Orange,
                            trackColor = AppColors.DividerDark
                        )
                    } else if (rs.err > 0) {
                        Text("${rs.ok} / ${rs.totalSources} · ❌ ${rs.err}",
                            fontSize = 11.sp, color = AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace)
                    }
                }
                if (rs.cost > 0.0) {
                    Text(formatCents(rs.cost), fontSize = 11.sp,
                        color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 8.dp))
                }
                // Fixed-width chevron slot so the totals footer below
                // (which renders an empty 16dp Box in this column)
                // lines its cost up exactly under the per-row costs.
                Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                    Text(">", fontSize = 16.sp, color = AppColors.Blue)
                }
            }
            HorizontalDivider(color = AppColors.DividerDark)
        }
        // Footer row mirroring the per-row layout — Total label on
        // the left, summed cost (cents) on the right. Hidden when
        // every row has zero cost, since the footer would just read
        // "Total" with no value.
        if (totalAnswerersCost > 0.0) {
            item(key = "l1-total-footer") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.padding(end = 8.dp).width(20.dp))
                    Text("Total", fontSize = 14.sp, color = AppColors.Blue,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(formatCents(totalAnswerersCost), fontSize = 11.sp,
                        color = AppColors.Blue, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 8.dp))
                    // Spacer matching the ">" tap chevron on data rows
                    // so the totals line up vertically with the
                    // per-row cost column above.
                    Box(modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    // Stats panel — only meaningful while pairs are still missing.
    // When every call has finished successfully (total == done) the
    // four-zero rows are noise, so hide the whole block.
    if (totalPairs != doneCount) {
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            StatRow("Total API calls", totalPairs.toString(), AppColors.Blue)
            StatRow("Done", doneCount.toString(), AppColors.Green)
            StatRow("Errored", erroredCount.toString(),
                if (erroredCount > 0) AppColors.Red else AppColors.TextTertiary)
            StatRow("Running", runningCount.toString(), AppColors.Orange)
            StatRow("Queued", queuedCount.toString(), AppColors.TextTertiary)
        }
    }

    // The "Actions" CollapsibleCard (Restart all failed / Show /
    // Edit fan-out prompt) was retired. Per-failure controls live in
    // the dedicated Remove failed items / Restart failed items
    // buttons surfaced above the L1 list when erroredCount > 0;
    // Rerun / Delete actions are on the TitleBar's reload / delete
    // icons; Run-a-Fan-in-prompt sits above the sub-header at the
    // top of the page.

    if (confirmRestartFailedL1) {
        com.ai.ui.shared.ReloadConfirmationDialog(
            target = "",
            title = "Restart failed items?",
            message = "Re-fires $erroredCount failed fan-out call${if (erroredCount == 1) "" else "s"} for this prompt. The runner's concurrency cap still applies, so larger failure sets surface as a mix of running and queued rows. Successful pairs are kept.",
            confirmLabel = "Restart",
            onConfirm = {
                confirmRestartFailedL1 = false
                fanOutPrompt?.let { onRestartFailedFanOut(it) }
            },
            onDismiss = { confirmRestartFailedL1 = false }
        )
    }
    if (confirmRemoveFailedL1) {
        AlertDialog(
            onDismissRequest = { confirmRemoveFailedL1 = false },
            title = { Text("Remove failed items?") },
            text = {
                Text("Drops $erroredCount failed fan-out row${if (erroredCount == 1) "" else "s"} for this prompt. No API calls are made. Successful pairs are kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveFailedL1 = false
                    fanOutPrompt?.let { onRemoveFailedFanOut(it) }
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveFailedL1 = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }

    if (confirmRerunComplete) {
        AlertDialog(
            onDismissRequest = { confirmRerunComplete = false },
            title = { Text("Rerun the complete Fan out?") },
            text = {
                Text("Delete every fan-out row and start a fresh run. " +
                    "Combined-report follow-ups for this prompt will also be dropped.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRerunComplete = false
                    fanOutPrompt?.let { onRerunCompleteFanOut(it) }
                }) { Text("Rerun", color = AppColors.Orange, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRerunComplete = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
    if (confirmFanOutDelete) {
        val totalRows = results.size + combinedRows.size
        AlertDialog(
            onDismissRequest = { confirmFanOutDelete = false },
            title = { Text("Delete fan-out run?") },
            text = {
                Text(
                    "Drop every per-pair response for this fan-out run" +
                        (if (combinedRows.isNotEmpty()) " plus the combined-report follow-up" else "") +
                        " — $totalRows rows. Can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFanOutDelete = false
                    onBulkDelete((results + combinedRows).map { it.id })
                    // Every row that drove this view is gone — leave the
                    // now-empty screen so the parent (which re-derives
                    // its row counts from storage) refreshes on resume.
                    onBack()
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFanOutDelete = false }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, color = valueColor, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold)
    }
}

/** L2 "One page view" overlay. Lays out the role-aware content as a
 *  single scrollable page so the user can read every source response
 *  + response side-by-side without drilling into L3.
 *
 *  Responder (active model is the answerer): for every source on L2,
 *  show the source's report response followed by the active model's
 *  response of it.
 *  Initiator (active model is the source): group L2 rows by source
 *  agent (multi-agent active models surface one block per agent).
 *  Each group prints the source response once, then every answerer's
 *  response of that source.
 *
 *  The list lives in a LazyColumn — Compose's `verticalScroll`
 *  doesn't virtualise, and these blocks each render a
 *  ContentWithThinkSections that can be tens of KB on long fan-out runs. */
private sealed class OnePageItem {
    abstract val key: String
    data class SourceHeader(
        override val key: String,
        val provName: String,
        val model: String,
        val responseBody: String?
    ) : OnePageItem()
    data class Response(
        override val key: String,
        val ansProv: String,
        val ansMdl: String,
        val showAnswererHeader: Boolean,
        val pair: SecondaryResult?
    ) : OnePageItem()
}

@Composable
private fun OnePageView(
    reportId: String,
    role: String,
    activePid: String,
    activeMdl: String,
    activeAgents: List<com.ai.data.ReportAgent>,
    l2Rows: List<L2Row>,
    agentsById: Map<String, com.ai.data.ReportAgent>,
    onBack: () -> Unit,
    onSwitchRole: () -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToTraceFile: (String) -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val provName = AppService.findById(activePid)?.id ?: activePid
    val activeProviderService = AppService.findById(activePid)
    val parentReport by produceState<com.ai.data.Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { com.ai.data.ReportStorage.getReport(context, reportId) }
    }

    // Flatten into a stable item list driven by role. Source bodies
    // appear once per source agent so the multi-agent Initiator view
    // doesn't duplicate large markdown blocks under every answerer.
    val items = remember(role, l2Rows, activeAgents, activePid, activeMdl, provName) {
        if (role == "Initiator") {
            val list = mutableListOf<OnePageItem>()
            // Group rows by their source agent. Each group emits one
            // source header + one response item per answerer.
            val grouped = l2Rows.groupBy { it.sourceAgentId.orEmpty() }
            // Preserve activeAgents ordering for source iteration so
            // the layout stays stable when l2Rows order shifts.
            val sourceAgentIds = if (activeAgents.isEmpty()) grouped.keys.toList()
                else activeAgents.map { it.agentId }.filter { grouped.containsKey(it) }
            sourceAgentIds.forEach { srcAgentId ->
                val rows = grouped[srcAgentId].orEmpty()
                val src = activeAgents.firstOrNull { it.agentId == srcAgentId }
                list += OnePageItem.SourceHeader(
                    key = "src-$srcAgentId",
                    provName = provName,
                    model = activeMdl,
                    responseBody = src?.responseBody
                )
                rows.forEach { row ->
                    val ansProv = AppService.findById(row.provider)?.id ?: row.provider
                    list += OnePageItem.Response(
                        key = "fc-${row.l3PairKey}",
                        ansProv = ansProv,
                        ansMdl = row.model,
                        showAnswererHeader = true,
                        pair = row.pair
                    )
                }
            }
            list
        } else {
            // Responder — one source response + response per L2 row.
            l2Rows.flatMap { row ->
                val src = agentsById[row.key]
                val srcProv = AppService.findById(row.provider)?.id ?: row.provider
                listOf(
                    OnePageItem.SourceHeader(
                        key = "src-${row.key}",
                        provName = srcProv,
                        model = row.model,
                        responseBody = src?.responseBody
                    ),
                    OnePageItem.Response(
                        key = "fc-${row.l3PairKey}",
                        ansProv = "", ansMdl = "",
                        showAnswererHeader = false,
                        pair = row.pair
                    )
                )
            }
        }
    }

    val modelLabel = com.ai.ui.shared.modelLabel(provName, activeMdl, separator = " / ")
    // OnePageView is rendered as an early-return inside the parent
    // `SecondaryResultsScreen`, which already wraps its body in a
    // Column with `.fillMaxSize().background(...).padding(16.dp)`.
    // Adding our own padding here doubled the top inset, pushing the
    // TitleBar 16dp lower than on every other page. Use a plain
    // fillMaxSize so we inherit the parent's padding instead.
    Column(modifier = Modifier.fillMaxSize()) {
        // Full-screen TitleBar replacing the previous inline header.
        // Back arrow / system back closes the page (no separate
        // "Close" button needed). ℹ️ → Model Info for the active
        // model, mirroring the L2 page's TitleBar slot.
        TitleBar(
            helpTopic = "secondary_fan_out_onepage",
            title = "One page view",
            reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: "📝",
            subject = modelLabel,
            onBackClick = onBack,
            onInfo = if (activeProviderService != null) {
                { onNavigateToModelInfo(activeProviderService, activeMdl) }
            } else null
        )
        com.ai.ui.shared.HardcodedSubjectRow(
            modelLabel,
            providerService = activeProviderService,
            model = activeMdl
        )
        // Role label + Switch role button — same shape as the L2
        // list page, hoisted up so the user can toggle without
        // backing out to the list view.
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)) {
            Text("Role: $role", fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f))
            Button(
                onClick = onSwitchRole,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) { Text("Switch role", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = AppColors.DividerDark)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is OnePageItem.SourceHeader -> {
                        Text("${com.ai.ui.shared.modelLabel(item.provName, item.model, separator = " / ")} — report response",
                            fontSize = 13.sp, color = AppColors.Blue,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        val body = item.responseBody
                        if (body.isNullOrBlank()) {
                            Text("(source response missing)", color = AppColors.TextTertiary, fontSize = 13.sp)
                        } else {
                            ContentWithThinkSections(analysis = body)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    is OnePageItem.Response -> {
                        if (item.showAnswererHeader) {
                            Text("${com.ai.ui.shared.modelLabel(item.ansProv, item.ansMdl, separator = " / ")} — response",
                                fontSize = 13.sp, color = AppColors.Green,
                                fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Response", fontSize = 13.sp, color = AppColors.Green,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val pair = item.pair
                        when {
                            pair == null -> Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                            pair.errorMessage != null ->
                                Text("❌ ${pair.errorMessage}", fontSize = 13.sp, color = AppColors.Red)
                            !pair.content.isNullOrBlank() -> ContentWithThinkSections(analysis = pair.content)
                            pair.durationMs != null ->
                                Text("(empty response)", fontSize = 13.sp, color = AppColors.TextTertiary)
                            else -> Text("(pending)", fontSize = 13.sp, color = AppColors.TextSecondary)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = AppColors.DividerDark)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
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
        if (rowRunning) {
            com.ai.ui.shared.AnimatedHourglass(fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
        } else {
            val statusEmoji = if (r.errorMessage != null) "❌" else "✅"
            Text(statusEmoji, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
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

@Composable
internal fun SecondaryResultDetailScreen(
    result: SecondaryResult,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTraceFile: (String) -> Unit = {},
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    /** When non-null, suppress the per-screen language picker and lock
     *  content to this language. Same convention as ReportsViewerScreen
     *  / SecondaryResultsScreen: null = picker mode (Report - Manage
     *  path), "" = locked to Original, non-empty = locked displayName. */
    forcedLanguage: String? = null
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val providerService = AppService.findById(result.providerId)
    val provider = providerService?.id ?: result.providerId
    // Prefer the user-given Meta-prompt name over the legacy kind
    // label — every chat-type Meta runs under kind=META, so the kind
    // alone would surface "Meta" even when the user picked "Compare".
    val title = result.metaPromptName?.takeIf { it.isNotBlank() }
        ?: com.ai.data.legacyKindDisplayName(result.kind)
    var confirmDelete by remember { mutableStateOf(false) }

    // Find the trace file for this meta call: same report, same model,
    // and timestamp closest to the result. Multiple meta runs of the
    // same model on the same report would otherwise alias — the
    // closest-timestamp tiebreak picks the right one. May be null when
    // tracing was off at the time of the call.
    val traceFilenameState = produceState<String?>(initialValue = null, result.id) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == result.reportId && it.model == result.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - result.timestamp) }?.filename
        }
    }
    val baseTraceFilename = traceFilenameState.value

    // Load TRANSLATE secondaries for this report — drives the language
    // icon picker for chat-type META rows (same UX as View → Prompt /
    // Model response). When the user selects a non-original language
    // the picker overlay swaps content, trace, copy/share onto the
    // matching TRANSLATE row.
    val translatesState = produceState(initialValue = emptyList<SecondaryResult>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.listForReport(context, result.reportId, SecondaryKind.TRANSLATE)
                .filter { !it.content.isNullOrBlank() }
        }
    }
    val translates = translatesState.value
    // Only show language tabs where THIS meta actually has content —
    // either the seed-language row itself (result.targetLanguage) or
    // a cross-translate TRANSLATE row pointing back at it. Include
    // Original only when this meta was run in Original (otherwise
    // the Original tab would resolve to "(no content)").
    val langTabs = remember(translates, result.id, result.targetLanguage) {
        val mineTranslates = translates.filter {
            it.translateSourceKind == "META" && it.translateSourceTargetId == result.id
        }
        // Synthesise a fake TRANSLATE row tagged with the result's own
        // language so buildLangTabs surfaces a tab for the seed
        // language even when no one cross-translated INTO this same
        // language.
        val seedTab: SecondaryResult? = result.targetLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
            result.copy(
                kind = SecondaryKind.TRANSLATE,
                targetLanguage = lang,
                targetLanguageNative = result.targetLanguageNative ?: lang
            )
        }
        val combined = if (seedTab != null) mineTranslates + seedTab else mineTranslates
        buildLangTabs(combined, includeOriginal = result.targetLanguage.isNullOrBlank())
    }
    // Default the picker to the tab matching the result's own
    // language so opening a French-seed META highlights "French"
    // immediately rather than defaulting to Original (which would
    // render "(no content)" for a seed-only meta).
    var pickerLangKey by rememberSaveable(result.id) {
        val target = result.targetLanguage
        mutableStateOf(
            if (target.isNullOrBlank()) LangTab.ORIGINAL_KEY
            else target.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
        )
    }
    LaunchedEffect(langTabs, forcedLanguage) {
        if (forcedLanguage == null && langTabs.none { it.key == pickerLangKey }) {
            pickerLangKey = LangTab.ORIGINAL_KEY
        }
    }
    val selectedLangKey: String = if (forcedLanguage != null) {
        if (forcedLanguage.isEmpty()) LangTab.ORIGINAL_KEY
        else forcedLanguage.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "").ifBlank { "x" }
    } else pickerLangKey
    val activeLangName: String? = when {
        selectedLangKey == LangTab.ORIGINAL_KEY -> null
        forcedLanguage != null && forcedLanguage.isNotEmpty() -> forcedLanguage
        else -> langTabs.firstOrNull { it.key == selectedLangKey }?.displayName
    }
    // For META kinds: when the picked language differs from the
    // result's own language, find the TRANSLATE row that targets this
    // meta in the picked language. Non-META kinds (RERANK / MODERATION)
    // have no language variants — pass-through.
    val activeTranslateRow: SecondaryResult? = remember(translates, selectedLangKey, result.id, activeLangName) {
        if (result.kind != SecondaryKind.META) null
        else if (activeLangName == result.targetLanguage) null
        else translates.firstOrNull {
            it.translateSourceKind == "META" &&
                it.translateSourceTargetId == result.id &&
                it.targetLanguage == activeLangName
        }
    }
    // For non-META and same-language tabs, show the result's own
    // content. For a different-language META tab, show only the
    // translation (null if none exists — caller renders a "(no
    // translation)" placeholder rather than falling back to the
    // foreign-language source).
    val displayContent: String? = when {
        result.kind != SecondaryKind.META -> result.content
        activeLangName == result.targetLanguage -> result.content
        else -> activeTranslateRow?.content
    }
    val traceFilename = activeTranslateRow?.traceFile?.takeIf { it.isNotBlank() } ?: baseTraceFilename

    // Pull the parent report just for its emoji icon — the title bar
    // prepends it for parity with every other report-scoped screen.
    val parentReportState = produceState<com.ai.data.Report?>(initialValue = null, result.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, result.reportId) }
    }
    val parentReport = parentReportState.value

    // Build the same id → "provider / model" map the @RESULTS@ block
    // used (success-ordered, 1-based) so the viewer can show real model
    // names instead of the bracketed [N] ids. Also build the parallel
    // id → response body map — used by the per-row moderation detail
    // screen to surface the exact text that was moderated.
    val agentLabelsState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent ->
                    val provDisplay = AppService.findById(agent.provider)?.id ?: agent.provider
                    (idx + 1) to "$provDisplay / ${agent.model}"
                }.toMap()
        }
    }
    val agentLabels = agentLabelsState.value
    val agentResponsesState = produceState(initialValue = emptyMap<Int, String>(), result.reportId) {
        value = withContext(Dispatchers.IO) {
            val report = ReportStorage.getReport(context, result.reportId) ?: return@withContext emptyMap<Int, String>()
            report.agents
                .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                .mapIndexed { idx, agent -> (idx + 1) to (agent.responseBody ?: "") }
                .toMap()
        }
    }
    val agentResponses = agentResponsesState.value

    // Per-row moderation drill-in — clicking a row in the moderation
    // table sets this to that row; the detail screen renders full
    // screen until back / dismiss clears it.
    var openModerationRow by remember { mutableStateOf<ModerationRow?>(null) }
    val activeModRow = openModerationRow
    if (activeModRow != null) {
        ModerationCallDetailScreen(
            row = activeModRow,
            agentLabel = agentLabels[activeModRow.id] ?: "[${activeModRow.id}]",
            agentResponse = agentResponses[activeModRow.id].orEmpty(),
            moderationModelLabel = com.ai.ui.shared.modelLabel(provider, result.model, separator = " / "),
            onBack = { openModerationRow = null }
        )
        return
    }

    // Translation comparison: only meaningful for chat-type META
    // rows, and only when this secondary is a translated copy (its
    // translatedFromSecondaryId points at the source's untranslated
    // counterpart). Load the source's content lazily.
    val sourceContentState = produceState<String?>(
        initialValue = null,
        result.id, result.translatedFromSecondaryId
    ) {
        val srcId = result.translatedFromSecondaryId ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val parent = ReportStorage.getReport(context, result.reportId) ?: return@withContext null
            val srcReportId = parent.sourceReportId ?: return@withContext null
            SecondaryResultStorage.get(context, srcReportId, srcId)?.content
        }
    }
    val sourceContent = sourceContentState.value
    val canShowTranslation =
        result.kind == SecondaryKind.META &&
            !sourceContent.isNullOrBlank() && !result.content.isNullOrBlank()
    var showTranslationCompare by remember { mutableStateOf(false) }

    if (showTranslationCompare && sourceContent != null && result.content != null) {
        TranslationCompareScreen(
            title = "Translation info — $title — ${com.ai.ui.shared.modelLabel(provider, result.model, separator = " / ")}",
            originalLabel = "Original",
            originalContent = sourceContent,
            translatedLabel = "Translation",
            translatedContent = result.content,
            onBack = { showTranslationCompare = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val traceEnabled = ApiTracer.isTracingEnabled && traceFilename != null
        TitleBar(
            helpTopic = "secondary_detail",
            title = "Secondary detail",
            reportIcon = parentReport?.icon?.takeIf { it.isNotBlank() } ?: "📝",
            subject = title,
            onBackClick = onBack,
            onTrace = if (traceEnabled) { { onNavigateToTraceFile(traceFilename!!) } } else null,
            onDelete = { confirmDelete = true },
            onInfo = if (providerService != null) { { onNavigateToModelInfo(providerService, result.model) } } else null,
            onCopy = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.copyToClipboard(context, body, "secondary result") }
            },
            onShare = displayContent?.takeIf { it.isNotBlank() }?.let { body ->
                { com.ai.ui.shared.shareText(context, body, "${result.kind.name} — $title") }
            }
        )
        com.ai.ui.shared.HardcodedSubjectRow(title)
        if (result.kind == SecondaryKind.META && langTabs.size > 1 && forcedLanguage == null) {
            LanguagePickerRow(
                langTabs, selectedLangKey,
                onSelect = { pickerLangKey = it },
                useIcons = true,
                originalIcon = parentReport?.languageIcon
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.ai.ui.shared.shortModelName(result.model), fontSize = 13.sp, color = AppColors.Blue,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when {
                result.errorMessage != null -> {
                    Text("Error", fontSize = 14.sp, color = AppColors.Red, fontWeight = FontWeight.SemiBold)
                    Text(result.errorMessage, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                displayContent.isNullOrBlank() -> {
                    val msg = if (result.kind == SecondaryKind.META && activeLangName != null && activeLangName != result.targetLanguage)
                        "(no translation for this language yet)"
                    else "(no content)"
                    Text(msg, color = AppColors.TextTertiary, fontSize = 13.sp)
                }
                result.kind == SecondaryKind.RERANK -> {
                    // Try to parse the structured JSON the rerank flow
                    // produces (chat-prompt path or callRerankApi path).
                    // Fall back to raw markdown rendering if the model's
                    // output deviated from the requested schema.
                    val rows = remember(displayContent) { parseRerankRows(displayContent) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = displayContent)
                    } else {
                        RerankTable(rows = rows, agentLabels = agentLabels)
                    }
                }
                result.kind == SecondaryKind.MODERATION -> {
                    val rows = remember(displayContent) { parseModerationRows(displayContent) }
                    if (rows == null) {
                        ContentWithThinkSections(analysis = displayContent)
                    } else {
                        ModerationTable(
                            rows = rows,
                            agentLabels = agentLabels,
                            onRowClick = { openModerationRow = it }
                        )
                    }
                }
                else -> {
                    ContentWithThinkSections(analysis = displayContent)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (canShowTranslation) {
            Button(
                onClick = { showTranslationCompare = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
            ) { Text("Translation info", fontSize = 13.sp, maxLines = 1, softWrap = false) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${title.lowercase()}?") },
            text = { Text(com.ai.ui.shared.modelLabel(provider, result.model)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

internal data class RerankRow(val id: Int, val rank: Int?, val score: Int?, val reason: String?)

/** Parse the rerank flow's structured JSON output. Both the chat-prompt
 *  path and the dedicated rerank-API path emit the same
 *  `[{id, rank, score, reason}, ...]` shape. ``` fences are tolerated;
 *  any other shape returns null so the caller can fall back to raw
 *  rendering. */
internal fun parseRerankRows(content: String): List<RerankRow>? {
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        @Suppress("DEPRECATION")
        com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null } ?: return null
    if (arr.size() == 0) return null
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val rank = obj.get("rank")?.takeIf { it.isJsonPrimitive }?.asInt
        val score = obj.get("score")?.takeIf { it.isJsonPrimitive }?.asNumber?.toInt()
        val reason = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        RerankRow(id, rank, score, reason)
    }
    if (rows.isEmpty()) return null
    return rows.sortedBy { it.rank ?: Int.MAX_VALUE }
}

@Composable
internal fun RerankTable(rows: List<RerankRow>, agentLabels: Map<Int, String>) {
    val hColor = AppColors.Blue
    val hSize = 12.sp
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Rank", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Score", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Reason", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            rows.forEach { r ->
                val label = agentLabels[r.id] ?: "[${r.id}] (unknown)"
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(r.rank?.toString() ?: "—", fontSize = 12.sp, color = AppColors.TextSecondary,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(label, fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.score?.toString() ?: "", fontSize = 12.sp, color = AppColors.Green,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(r.reason.orEmpty(), fontSize = 12.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.widthIn(min = 200.dp, max = 360.dp).padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }
    }
}

internal data class ModerationRow(
    val id: Int,
    val flagged: Boolean,
    val firedCategories: List<String>,
    /** All non-zero scores, sorted high → low so the table can show the
     *  top contributors even when nothing crossed the boolean threshold. */
    val topScores: List<Pair<String, Double>>,
    /** Full per-category boolean map as returned by the API — the
     *  per-row detail screen iterates this to render every category
     *  (fired and not) with a check / cross. */
    val allCategories: Map<String, Boolean> = emptyMap(),
    /** Full per-category score map (0.0–1.0), unsorted. The detail
     *  screen renders these sorted high → low alongside [allCategories]. */
    val allScores: Map<String, Double> = emptyMap()
)

/** True when at least one moderation row across [rows] has at
 *  least one fired category. Walks every MODERATION secondary's
 *  content through [parseModerationRows] and short-circuits on
 *  the first flagged hit. Used by the View screen to flip the
 *  moderation tile's accent red ↔ green. */
internal fun anyModerationFlagged(rows: List<SecondaryResult>): Boolean =
    rows.filter { it.kind == SecondaryKind.MODERATION }
        .any { row ->
            val content = row.content ?: return@any false
            parseModerationRows(content)?.any { it.flagged } == true
        }

/** Parse the JSON [callModerationApi] writes into the SecondaryResult.
 *  Same `[{id, flagged, categories, scores}, …]` shape callRerankApi
 *  uses; tolerates ``` fences. Returns null on bad input. */
internal fun parseModerationRows(content: String): List<ModerationRow>? {
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        @Suppress("DEPRECATION")
        com.google.gson.JsonParser().parse(cleaned).takeIf { it.isJsonArray }?.asJsonArray
    } catch (_: Exception) { null } ?: return null
    if (arr.size() == 0) return null
    val rows = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val obj = el.asJsonObject
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
        val flagged = obj.get("flagged")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val cats = obj.getAsJsonObject("categories")
        val scores = obj.getAsJsonObject("scores")
        val allCats = cats?.entrySet()?.associate {
            it.key to (try { it.value.asBoolean } catch (_: Exception) { false })
        } ?: emptyMap()
        val allScores = scores?.entrySet()?.mapNotNull {
            val v = try { it.value.asDouble } catch (_: Exception) { return@mapNotNull null }
            it.key to v
        }?.toMap() ?: emptyMap()
        val fired = allCats.filterValues { it }.keys.toList()
        val scoreList = allScores.entries
            .sortedByDescending { it.value }.take(3).map { it.key to it.value }
        ModerationRow(id, flagged, fired, scoreList, allCats, allScores)
    }
    if (rows.isEmpty()) return null
    return rows
}

@Composable
internal fun ModerationTable(
    rows: List<ModerationRow>,
    agentLabels: Map<Int, String>,
    onRowClick: (ModerationRow) -> Unit = {}
) {
    val hColor = AppColors.Blue
    val hSize = 12.sp
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Flag", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(40.dp))
                Text("Model", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Categories fired", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(220.dp).padding(start = 8.dp))
                Text("Top scores", fontSize = hSize, color = hColor, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            rows.forEach { r ->
                val label = agentLabels[r.id] ?: "[${r.id}] (unknown)"
                val firedText = if (r.firedCategories.isEmpty()) "—" else r.firedCategories.joinToString(", ")
                val scoresText = r.topScores.joinToString(", ") { (k, v) -> "$k=${"%.3f".format(v)}" }
                Row(modifier = Modifier.clickable { onRowClick(r) }.padding(vertical = 6.dp)) {
                    Text(if (r.flagged) "🚩" else "✓", fontSize = 13.sp,
                        color = if (r.flagged) AppColors.Red else AppColors.Green,
                        modifier = Modifier.width(40.dp))
                    Text(label, fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(firedText, fontSize = 12.sp,
                        color = if (r.flagged) AppColors.Red else AppColors.TextTertiary,
                        modifier = Modifier.width(220.dp).padding(start = 8.dp))
                    Text(scoresText, fontSize = 11.sp, color = AppColors.TextTertiary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.widthIn(min = 200.dp).padding(start = 8.dp))
                }
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
            }
        }
    }
}

/**
 * Full-screen drill-in for a single moderation API call's result row.
 * Reached by tapping a row in [ModerationTable]. Shows the moderated
 * agent's label, flag status, full per-category breakdown (every
 * category — fired or not — with its score), and the original text
 * that was moderated.
 */
@Composable
internal fun ModerationCallDetailScreen(
    row: ModerationRow,
    agentLabel: String,
    agentResponse: String,
    moderationModelLabel: String,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val flagColor = if (row.flagged) AppColors.Red else AppColors.Green
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "moderation_call_detail",
            title = "Moderation result",
            subject = agentLabel,
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            onBackClick = onBack,
            onCopy = if (agentResponse.isNotBlank()) {
                { com.ai.ui.shared.copyToClipboard(context, agentResponse, "moderated text") }
            } else null,
            onShare = if (agentResponse.isNotBlank()) {
                { com.ai.ui.shared.shareText(context, agentResponse, "Moderated text — $agentLabel") }
            } else null
        )
        com.ai.ui.shared.HardcodedSubjectRow(agentLabel)

        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // Flag headline + meta line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.flagged) "🚩 Flagged" else "✓ Clean",
                    fontSize = 18.sp, color = flagColor, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Moderated by $moderationModelLabel",
                fontSize = 11.sp, color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
            )

            // Per-category breakdown — every category the API returned,
            // sorted by score (descending). Fired rows are red; the
            // rest stay dim. Score is rendered to 4 decimals; absent
            // scores show "—".
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Categories",
                fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            val categoryOrder = remember(row.allCategories, row.allScores) {
                val names = (row.allCategories.keys + row.allScores.keys).distinct()
                names.sortedWith(
                    compareByDescending<String> { row.allScores[it] ?: -1.0 }
                        .thenBy { it.lowercase() }
                )
            }
            if (categoryOrder.isEmpty()) {
                Text("(no categories returned)", fontSize = 12.sp, color = AppColors.TextTertiary)
            } else {
                HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                categoryOrder.forEach { cat ->
                    val fired = row.allCategories[cat] == true
                    val score = row.allScores[cat]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (fired) "🚩" else "·",
                            fontSize = 13.sp, color = if (fired) AppColors.Red else AppColors.TextTertiary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            cat,
                            fontSize = 13.sp,
                            color = if (fired) AppColors.Red else Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            score?.let { "%.4f".format(it) } ?: "—",
                            fontSize = 12.sp,
                            color = if (fired) AppColors.Red else AppColors.TextTertiary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    HorizontalDivider(color = AppColors.DividerDark, thickness = 1.dp)
                }
            }

            // Original moderated text — what the moderation API actually
            // classified. Sits at the bottom so the verdict + category
            // breakdown stay above the fold.
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Moderated text",
                fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (agentResponse.isBlank()) {
                Text("(source response no longer available)", fontSize = 12.sp, color = AppColors.TextTertiary)
            } else {
                ContentWithThinkSections(analysis = agentResponse)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
