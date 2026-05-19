package com.ai.ui.hub

import android.app.Activity
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R
import com.ai.data.AnalysisRepository
import com.ai.data.ApiTracer
import com.ai.data.KnowledgeService
import com.ai.data.KnowledgeStore
import com.ai.data.ModelCooldownStore
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.data.local.LocalEmbedder
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.ai.ui.knowledge.displayNameForUri
import com.ai.ui.knowledge.pickTypeForUri
import com.ai.ui.search.supportedEmbeddingChoices
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HubScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToTraces: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToReportsHub: () -> Unit,
    onNavigateToUsage: () -> Unit,
    onNavigateToChatsHub: () -> Unit,
    onNavigateToAiSetup: () -> Unit,
    onNavigateToHousekeeping: () -> Unit,
    onNavigateToModelSearch: () -> Unit,
    onNavigateToKnowledge: () -> Unit = {},
    onOpenLatestReport: () -> Unit = {},
    viewModel: AppViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    BackHandler { (context as? Activity)?.moveTaskToBack(true) }

    val hasAnyAgent = remember(uiState.aiSettings.agents) { uiState.aiSettings.agents.isNotEmpty() }
    // Re-fire whenever the agent list changes. Was keyed on the whole
    // uiState, which churned ~30 times during refreshAllModelLists (each
    // model-list fetch touches aiSettings.providers but not agents) and
    // dragged the main thread through repeated disk work for nothing. The
    // narrower key still picks up the once-per-bootstrap transition that
    // ensureUsageStatsCache needs to retry past a ProviderRegistry-init
    // race, plus any agent edit during the session.
    val hasStatisticsData by produceState(initialValue = false, uiState.aiSettings.agents) {
        value = withContext(Dispatchers.IO) {
            val sp = SettingsPreferences(context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE), context.filesDir)
            sp.loadUsageStats().isNotEmpty()
        }
    }
    val hasTraces by produceState(initialValue = false, uiState.aiSettings.agents) {
        // hasAnyTraceFile only enumerates filenames — no JSON parse, vs the
        // ~250-file parse getTraceFiles() does for the full list.
        value = withContext(Dispatchers.IO) { ApiTracer.hasAnyTraceFile() }
    }
    // Drives the logo's clickability — tapping the logo opens the
    // most recent report's result page. Re-fires on resume so a
    // freshly-finished report is reachable without a process restart.
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    val hasAnyReport by produceState(initialValue = false, refreshTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context).isNotEmpty() }
    }

    // "Running reports" + "Reports with problems" cards — shared
    // loader so the Reports hub renders the exact same buckets.
    // (home no longer renders Running / Problems cards — they live
    // on ReportsHubScreen now.)

    // The AI API Traces card disappears entirely when tracing is off \u2014
    // adjust the card count so the logo sizing math still works.
    val tracingEnabled = uiState.generalSettings.tracingEnabled
    val cardHeight = 50.dp
    val cardSpacing = 12.dp
    // homeCardsExtra contributes 1 row-equivalent per visible new
    // card. ReportListCard is actually taller (variable rows
    // inside), but we keep the math simple — logo just shrinks a
    // touch when the cards are showing, still bounded by the
    // coerceIn(100, 220) below.
    val cardCount = (if (tracingEnabled) 11 else 10)
    val cardsHeight = (cardHeight * cardCount) + (cardSpacing * (cardCount - 1)) + 32.dp

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)
    ) {
        // Cap calibrated for the tight-cropped ai_logo (content fills
        // the viewport, no internal padding). Bumping above ~160 dp
        // makes the logo crowd into the cards beneath it.
        val logoSize = (maxHeight - cardsHeight).coerceIn(80.dp, 160.dp)
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(40.dp))
            val logoInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Image(
                painter = painterResource(id = R.drawable.brand_glyph),
                contentDescription = "AI App Logo",
                // Lift the logo up 32 dp AND shrink its measured slot
                // by 24 dp so the "AI Reports" card below sits closer.
                // Plain Modifier.offset only shifted the paint and
                // left a 32 dp visual gap below the logo; the layout
                // block here also drops most of that gap from the
                // measured layout so the cards move up with the
                // visual logo.
                modifier = Modifier
                    .size(logoSize)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val visualShift = 32.dp.roundToPx()
                        val heightTrim = 24.dp.roundToPx()
                        layout(
                            placeable.width,
                            (placeable.height - heightTrim).coerceAtLeast(0)
                        ) {
                            placeable.place(0, -visualShift)
                        }
                    }
                    .then(
                        if (hasAnyReport) Modifier.clickable(
                            interactionSource = logoInteractionSource,
                            indication = null
                        ) { onOpenLatestReport() } else Modifier
                    )
            )
            // Inactive cards are hidden entirely (rather than rendered
            // grayed-out + non-clickable as in earlier builds). Each
            // visibility-gated card carries its own trailing Spacer
            // inside the `if`, so the gap goes with it and the layout
            // stays compact.
            if (hasAnyAgent) {
                HubCard(icon = "\uD83D\uDCDD", title = "AI Reports", onClick = onNavigateToReportsHub)
                Spacer(modifier = Modifier.height(12.dp))
                HubCard(icon = "\uD83D\uDCAC", title = "AI Chat", onClick = onNavigateToChatsHub)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (uiState.generalSettings.experimentalFeaturesEnabled && uiState.generalSettings.showKnowledgeCard) {
                HubCard(icon = "\uD83D\uDCDA", title = "AI Knowledge", onClick = onNavigateToKnowledge)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (hasAnyAgent) {
                HubCard(icon = "\uD83E\uDDE0", title = "AI Models", onClick = onNavigateToModelSearch)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (hasStatisticsData) {
                HubCard(icon = "\uD83D\uDCC8", title = "AI Usage", onClick = onNavigateToUsage)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (tracingEnabled && hasTraces) {
                HubCard(icon = "\uD83D\uDC1E", title = "AI API Traces", onClick = onNavigateToTraces)
                Spacer(modifier = Modifier.height(12.dp))
            }
            HubCard(icon = "\uD83E\uDD16", title = "AI Setup", onClick = onNavigateToAiSetup)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\uD83E\uDDF9", title = "AI Housekeeping", onClick = onNavigateToHousekeeping)
            Spacer(modifier = Modifier.height(12.dp))
            Spacer(modifier = Modifier.height(32.dp))
            HubCard(icon = "\u2699\uFE0F", title = "Settings", onClick = onNavigateToSettings)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = "\u2753", title = "Help", onClick = onNavigateToHelp)
            Spacer(modifier = Modifier.height(12.dp))
            // \u2139\uFE0F About \u2014 replaces the old Documentation card. The About
            // screen surfaces the AI logo + version + build date and
            // hosts the two documentation hubs (Manual + Technical) as
            // its own cards.
            HubCard(icon = "\u2139\uFE0F", title = "About", onClick = onNavigateToAbout)
        }
    }
}

@Composable
internal fun HubCard(icon: String, title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 26.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/** Carries the two report lists the home-screen "Running reports"
 *  / "Reports with problems" cards consume. Produced by the
 *  HubScreen's `produceState` block; both lists naturally land
 *  newest-first because [ReportStorage.getAllReports] returns
 *  sorted-by-timestamp-descending. */
data class HomeReportLists(
    val running: List<Report>,
    val problems: List<Report>
)

/** Disk scan that powers both the home screen's Running / Problems
 *  cards and the Reports hub's Problems / Running list cards.
 *  Centralised here so the two screens stay in sync — the filter
 *  rules (active translations counted as Running, errored agents +
 *  stuck-placeholder secondaries counted as Problems, cooled-down
 *  TRANSLATE rows excluded, Running reports excluded from Problems)
 *  used to live inline in HubScreen and were a copy-paste risk.
 *
 *  Pulls the live `translationRuns` StateFlow from the supplied
 *  [reportViewModel] so an in-flight translation flips a report
 *  into Running sub-second. The 5 s [cardsTick] catches background
 *  state changes (resume-from-disk, cooldown clear, etc.). The
 *  [refreshTick] keys onto the screen's resume lifecycle so
 *  navigating back from a delete / reload picks the fresh state. */
@Composable
fun rememberHomeReportLists(
    refreshTick: Int,
    reportViewModel: ReportViewModel
): State<HomeReportLists> {
    val context = LocalContext.current
    val translationRuns by reportViewModel.translationRuns.collectAsState()
    val cardsTick by produceState(initialValue = 0) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            value = value + 1
        }
    }
    return produceState(
        initialValue = HomeReportLists(emptyList(), emptyList()),
        refreshTick, cardsTick, translationRuns
    ) {
        value = withContext(Dispatchers.IO) {
            computeHomeReportLists(context, translationRuns)
        }
    }
}

/** Pure-IO computation that produces the Running + Problems splits
 *  for the home / hub list cards. Run on [Dispatchers.IO] by
 *  [rememberHomeReportLists] — kept as a top-level so the same
 *  filter logic can be invoked from any screen-scoped coroutine
 *  without dragging the Compose plumbing along. */
internal fun computeHomeReportLists(
    context: android.content.Context,
    translationRuns: Map<String, ReportViewModel.TranslationRunState>
): HomeReportLists {
    val all = ReportStorage.getAllReports(context)
    val activeTranslationReportIds = translationRuns.values
        .filter { !it.isFinished && !it.cancelled }
        .map { it.sourceReportId }
        .toSet()
    val running = all.filter { reportIsRunning(it, activeTranslationReportIds) }
    val runningIds = running.map { it.id }.toSet()
    val problems = all.filter { r ->
        // Skip reports that are already showing in the Running card
        // — disk-side red crosses that are actively being healed
        // (in-flight retry / resume) shouldn't double up as
        // "problems". When the running state clears, any persistent
        // red cross resurfaces here on the next 5 s tick.
        if (r.id in runningIds) return@filter false
        reportHasProblems(r, SecondaryResultStorage.listForReport(context, r.id))
    }
    return HomeReportLists(running, problems)
}

/** True when [report] is still actively producing output — at
 *  least one PENDING / RUNNING agent on a not-yet-completed report,
 *  OR an in-flight translation run targeting this report id. Shared
 *  by the AI Reports hub's "Running" card and the Main View screen's
 *  bottom-of-screen "Report still running" notice. */
fun reportIsRunning(
    report: Report,
    activeTranslationReportIds: Set<String>
): Boolean = (report.completedAt == null && report.agents.any {
    it.reportStatus == ReportStatus.PENDING ||
        it.reportStatus == ReportStatus.RUNNING
}) || report.id in activeTranslationReportIds

/** True when [report] has at least one persisted problem — an
 *  ERROR agent, an errored secondary (excluding TRANSLATE rows
 *  whose model is cooled down), or a stuck-placeholder secondary
 *  (no content, no error, no duration). Mirrors the predicate the
 *  AI Reports hub's "Problems" card uses. */
fun reportHasProblems(
    report: Report,
    secondaries: List<com.ai.data.SecondaryResult>
): Boolean {
    if (report.agents.any { it.reportStatus == ReportStatus.ERROR }) return true
    return secondaries.any { sec ->
        val hasError = sec.errorMessage != null &&
            !(sec.kind == SecondaryKind.TRANSLATE &&
                ModelCooldownStore.isUnavailable(sec.providerId, sec.model))
        val stuckPlaceholder = sec.content.isNullOrBlank() &&
            sec.errorMessage == null && sec.durationMs == null
        hasError || stuckPlaceholder
    }
}

@Composable
fun ReportsHubScreen(
    onNavigateBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onOpenReportManage: (String) -> Unit,
    onOpenReportView: (String) -> Unit,
    onNavigateToNewAiReport: () -> Unit,
    onNavigateToSearchAiReports: () -> Unit,
    onNavigateToAllReports: () -> Unit,
    reportViewModel: ReportViewModel
) {
    val context = LocalContext.current
    // Re-fetch on every ON_RESUME — without this, navigating into a
    // detail screen and popping back left a stale cached list (the
    // composable is preserved across the trip and remember{} would
    // never re-evaluate). Keys all the disk reads through one tick.
    val refreshTick = com.ai.ui.shared.resumeRefreshTick()
    // Bump after a row 🗑 delete completes so the four cards re-load.
    var deleteTick by remember { mutableStateOf(0) }
    val allReports by produceState(initialValue = emptyList<Report>(), refreshTick, deleteTick) {
        value = withContext(Dispatchers.IO) { ReportStorage.getAllReports(context) }
    }
    val pinnedReports = remember(allReports) {
        allReports.filter { it.pinned }.sortedByDescending { it.timestamp }.take(5)
    }
    val latestReports = remember(allReports) { allReports.take(5) }
    val homeReportLists by rememberHomeReportLists(refreshTick, reportViewModel)
    val scope = rememberCoroutineScope()
    val bumpDelete: (String) -> Unit = { rid ->
        scope.launch(Dispatchers.IO) {
            ReportStorage.deleteReport(context, rid)
            deleteTick++
        }
    }
    // Wire the per-row 🔧 / 👁 / 🗑 icons to the navigation +
    // delete behaviour the hub wants on every list card. Replaces
    // the bundle the host installs at AI_REPORTS_HUB so the dash-
    // board's four cards all share one source of truth.
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalReportListIconBundle provides com.ai.ui.shared.ReportListIconBundle(
            onOpenManage = onOpenReportManage,
            onOpenView = onOpenReportView,
            onDelete = bumpDelete
        )
    ) {
    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)) {
        TitleBar(helpTopic = "reports_hub", title = "AI Reports", onBackClick = onNavigateBack)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onNavigateToNewAiReport,
                modifier = Modifier.weight(1f)
            ) { Text("New", maxLines = 1, softWrap = false) }
            Button(
                onClick = onNavigateToSearchAiReports,
                modifier = Modifier.weight(1f)
            ) { Text("Search", maxLines = 1, softWrap = false) }
            Button(
                onClick = onNavigateToAllReports,
                modifier = Modifier.weight(1f)
            ) { Text("All", maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ReportsHubListCard(
            accentEmoji = "⚠️", accentColor = AppColors.Red,
            label = "AI Reports with problems", reports = homeReportLists.problems,
            showEmptyHint = false
        )
        Spacer(modifier = Modifier.height(10.dp))
        ReportsHubListCard(
            accentEmoji = "⏳", accentColor = AppColors.Orange,
            label = "Running AI reports", reports = homeReportLists.running,
            showEmptyHint = false
        )
        Spacer(modifier = Modifier.height(10.dp))
        ReportsHubListCard(
            accentEmoji = "📌", accentColor = AppColors.Yellow,
            label = "Pinned AI Reports", reports = pinnedReports,
            showEmptyHint = false
        )
        Spacer(modifier = Modifier.height(10.dp))
        ReportsHubListCard(
            accentEmoji = "🕘", accentColor = AppColors.Blue,
            label = "Latest AI Reports", reports = latestReports
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
    }
}

/** One of the four list cards on the rewritten Reports hub
 *  dashboard. Shows a header (accent emoji + label + count badge)
 *  and up to 5 [com.ai.ui.shared.ReportListRow]s. Empty cards
 *  render dimmed at `alpha = 0.35f`. With [showEmptyHint] true the
 *  card spells the absence out with an italic "(none)" line —
 *  used by Pinned / Latest where the user might still want to act
 *  on the slot. The top two cards (Problems / Running) pass false
 *  so an empty state quietly shows only the dimmed header — those
 *  categories are noise when empty. */
@Composable
private fun ReportsHubListCard(
    accentEmoji: String,
    accentColor: Color,
    label: String,
    reports: List<Report>,
    showEmptyHint: Boolean = true
) {
    val empty = reports.isEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (empty) 0.35f else 1f)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = accentEmoji, fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier.weight(1f)
                )
            }
            if (empty) {
                if (showEmptyHint) {
                    Text(
                        text = "(none)",
                        fontSize = 12.sp,
                        color = AppColors.TextTertiary,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 2.dp)
                    )
                }
            } else {
                reports.take(5).forEach { r ->
                    com.ai.ui.shared.ReportListRow(
                        report = r,
                        onOpenManage = com.ai.ui.shared.LocalReportListIconBundle.current.onOpenManage,
                        onOpenView = com.ai.ui.shared.LocalReportListIconBundle.current.onOpenView,
                        onDelete = com.ai.ui.shared.LocalReportListIconBundle.current.onDelete
                    )
                }
            }
        }
    }
}

private val userTagRegex = Regex("""<user>.*?</user>""", RegexOption.DOT_MATCHES_ALL)

@Composable
fun NewReportScreen(
    viewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onNavigateToReports: () -> Unit = {},
    onNavigateToTraceFile: (String) -> Unit = {},
    initialTitle: String = "",
    initialPrompt: String = ""
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val prefs = context.getSharedPreferences(SettingsPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    var title by remember {
        mutableStateOf(initialTitle.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, "") ?: "" })
    }
    val rawPrompt = remember { initialPrompt.ifEmpty { prefs.getString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, "") ?: "" } }
    val userTagBlock = remember { userTagRegex.find(rawPrompt)?.value ?: "" }
    var prompt by remember { mutableStateOf(rawPrompt.replace(userTagRegex, "").trim()) }
    // (mime, base64) of an optional image attached to the prompt — passed
    // through to every agent in the report. Seeded from UiState when the
    // share-target chooser staged an image into reportImageBase64/Mime
    // before navigating here; we clear those fields on first composition
    // so a later return to this screen doesn't re-stage the same image.
    var attachedImage by remember {
        mutableStateOf<Pair<String, String>?>(
            uiState.reportImageMime?.let { mime ->
                uiState.reportImageBase64?.let { b64 -> mime to b64 }
            }
        )
    }
    LaunchedEffect(Unit) {
        if (uiState.reportImageBase64 != null || uiState.reportImageMime != null) {
            viewModel.updateUiState { it.copy(reportImageBase64 = null, reportImageMime = null) }
        }
    }

    // Snapshot of any non-image URIs the share-target chooser routed
    // here as "files attach as Knowledge". The banner below offers a
    // one-tap auto-attach: create a fresh KB, ingest the URIs, append
    // the KB id to attachedKnowledgeBaseIds so the report run uses
    // RAG against them. Drained on attach / skip / discard so a later
    // return to this screen doesn't re-stage the same files.
    var sharedKbState by remember { mutableStateOf<SharedKbBannerState>(SharedKbBannerState.Idle) }
    val sharedKbUris = remember { uiState.pendingReportKnowledgeUris }
    LaunchedEffect(Unit) {
        if (uiState.pendingReportKnowledgeUris.isNotEmpty()) {
            viewModel.updateUiState { it.copy(pendingReportKnowledgeUris = emptyList()) }
        }
    }
    var attachError by remember { mutableStateOf<String?>(null) }
    var useWebSearch by remember { mutableStateOf(false) }
    // Per-report reasoning level. "" = none; one of low/medium/high
    // gets OR'd onto every agent's params at dispatch (non-thinking
    // models drop the field).
    var reasoningEffort by remember { mutableStateOf("") }
    var reasoningMenuExpanded by remember { mutableStateOf(false) }
    // Optional moderation pre-check — when set, the prompt runs through
    // the chosen moderation model before any agent fires. Mirrors the
    // chat session screen.
    var moderationModel by remember { mutableStateOf<Pair<com.ai.data.AppService, String>?>(null) }
    var showModerationPicker by remember { mutableStateOf(false) }
    var pendingFlagged by remember { mutableStateOf<Triple<String, com.ai.data.ModerationInputResult, String?>?>(null) }
    var moderationError by remember { mutableStateOf<String?>(null) }
    var isModerating by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val pair = withContext(Dispatchers.IO) {
                    runCatching { com.ai.data.loadImageAsBase64(context, uri) }.getOrNull()
                }
                if (pair != null) {
                    attachedImage = pair
                    attachError = null
                } else {
                    attachError = "Failed to attach image"
                }
            }
        }
    }
    // 📎 paperclip is a two-step entry: open a small chooser so the
    // user picks "Take photo" or "Use existing photo", then fire the
    // matching launcher. Replaces the previous Reports-hub "Start with
    // photo" row plus the gallery-only paperclip with a single
    // attach surface.
    val launchCameraForAttach = com.ai.ui.shared.rememberCameraCaptureLauncher(
        onCaptured = { mime, b64 ->
            attachedImage = mime to b64
            attachError = null
        },
        onError = { attachError = it }
    )
    var showAttachChooser by remember { mutableStateOf(false) }
    if (showAttachChooser) {
        AlertDialog(
            onDismissRequest = { showAttachChooser = false },
            title = { Text("Attach image") },
            text = { Text("Take a new photo or pick one from your gallery?") },
            confirmButton = {
                TextButton(onClick = {
                    showAttachChooser = false
                    launchCameraForAttach()
                }) { Text("Take photo") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAttachChooser = false
                    pickImageLauncher.launch("image/*")
                }) { Text("Use existing photo") }
            }
        )
    }

    LaunchedEffect(uiState.showGenericAgentSelection) {
        if (uiState.showGenericAgentSelection) {
            reportViewModel.dismissGenericAgentSelection()
            onNavigateToReports()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(helpTopic = "report_new", title = "New AI Report", onBackClick = onNavigateBack)

        if (sharedKbUris.isNotEmpty() && sharedKbState !is SharedKbBannerState.Skipped) {
            SharedKbBanner(
                uris = sharedKbUris,
                title = title,
                aiSettings = uiState.aiSettings,
                state = sharedKbState,
                onAttach = {
                    sharedKbState = SharedKbBannerState.Working("Preparing…")
                    coroutineScope.launch {
                        sharedKbState = ingestSharedKb(
                            context = context,
                            repository = viewModel.repository,
                            aiSettings = uiState.aiSettings,
                            reportTitle = title,
                            uris = sharedKbUris
                        ) { msg -> sharedKbState = SharedKbBannerState.Working(msg) }
                        val s = sharedKbState
                        if (s is SharedKbBannerState.Done) {
                            viewModel.updateUiState { st ->
                                st.copy(attachedKnowledgeBaseIds = (st.attachedKnowledgeBaseIds + s.kbId).distinct())
                            }
                        }
                    }
                },
                onSkip = { sharedKbState = SharedKbBannerState.Skipped }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Primary CTA hoisted into its own full-width row so the
        // "advance" affordance is always reachable without picking
        // it out of the Clear / 📎 row.
        Button(
            onClick = next@{
                    if (title.isBlank() || prompt.isBlank() || isModerating) return@next
                    val fullPrompt = if (userTagBlock.isNotEmpty()) "$prompt\n$userTagBlock" else prompt
                    prefs.edit().putString(SettingsPreferences.KEY_LAST_AI_REPORT_TITLE, title)
                        .putString(SettingsPreferences.KEY_LAST_AI_REPORT_PROMPT, fullPrompt).apply()
                    SettingsPreferences(prefs, context.filesDir).savePromptToHistory(title, fullPrompt)

                    fun proceed() {
                        reportViewModel.showGenericAgentSelection(
                            title, fullPrompt,
                            imageBase64 = attachedImage?.second,
                            imageMime = attachedImage?.first,
                            webSearchTool = useWebSearch,
                            reasoningEffort = reasoningEffort.ifBlank { null }
                        )
                    }

                    val mod = moderationModel
                    if (mod == null) { proceed(); return@next }
                    coroutineScope.launch {
                        isModerating = true
                        try {
                            com.ai.data.withTraceCategory("Hub validate input") {
                                val (modProvider, modModelId) = mod
                                val apiKey = uiState.aiSettings.getApiKey(modProvider)
                                val callStart = System.currentTimeMillis()
                                val (results, apiResult) = com.ai.data.callModerationApi(modProvider, apiKey, modModelId, listOf(fullPrompt))
                                val r = results?.firstOrNull()
                                if (apiResult.errorMessage != null || r == null) {
                                    moderationError = apiResult.errorMessage ?: "No moderation result"
                                    proceed()
                                } else if (r.flagged) {
                                    val traceFn = withContext(Dispatchers.IO) {
                                        ApiTracer.getTraceFiles()
                                            .filter { it.reportId == null && it.model == modModelId && it.timestamp >= callStart }
                                            .minByOrNull { it.timestamp }?.filename
                                    }
                                    pendingFlagged = Triple(fullPrompt, r, traceFn)
                                } else {
                                    proceed()
                                }
                            }
                        } finally {
                            isModerating = false
                        }
                    }
                },
            enabled = title.isNotBlank() && prompt.isNotBlank() && !isModerating,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
        ) {
            if (isModerating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("Next", fontSize = 16.sp, maxLines = 1, softWrap = false)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Secondary actions — Clear (wipes the form) and 📎 (attach
        // image). Kept in their own row below the primary CTA so the
        // green Next button stays visually distinct.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { title = ""; prompt = ""; attachedImage = null },
                modifier = Modifier.weight(1f),
                colors = AppColors.outlinedButtonColors()
            ) { Text("Clear", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { showAttachChooser = true }, colors = AppColors.outlinedButtonColors()) {
                Text("📎", fontSize = 16.sp, maxLines = 1, softWrap = false)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = useWebSearch,
                onClick = { useWebSearch = !useWebSearch },
                label = { Text("🌐 Web search", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Blue.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Blue
                )
            )
            // 🧠 Thinking pulldown — same low/medium/high set as chat.
            // Always shown on the report screen since the report fans out
            // to many models with mixed reasoning support; the dispatch
            // layer's per-format helper drops the field on non-thinking
            // models so showing the chip universally is harmless.
            Box {
                val levelLabel = if (reasoningEffort.isBlank()) "none"
                    else reasoningEffort.replaceFirstChar { it.uppercase() }
                FilterChip(
                    selected = reasoningEffort.isNotBlank(),
                    onClick = { reasoningMenuExpanded = true },
                    label = { Text("🧠 $levelLabel", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.Purple.copy(alpha = 0.2f),
                        selectedLabelColor = AppColors.Purple
                    )
                )
                DropdownMenu(
                    expanded = reasoningMenuExpanded,
                    onDismissRequest = { reasoningMenuExpanded = false },
                    modifier = Modifier.background(Color(0xFF2D2D2D))
                ) {
                    listOf("" to "None", "low" to "Low", "medium" to "Medium", "high" to "High").forEach { (value, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(label, fontSize = 13.sp,
                                    color = if (reasoningEffort == value) AppColors.Blue else Color.White)
                            },
                            onClick = { reasoningEffort = value; reasoningMenuExpanded = false }
                        )
                    }
                }
            }
            // 🛡 Moderation chip — tap when off opens the model picker;
            // tap when on clears the selection. With a model set, the
            // prompt is validated before generation kicks off.
            FilterChip(
                selected = moderationModel != null,
                onClick = {
                    if (moderationModel == null) showModerationPicker = true
                    else moderationModel = null
                },
                label = {
                    val label = moderationModel?.let { (_, m) -> "🛡 $m" } ?: "🛡 Validate prompt"
                    Text(label, fontSize = 12.sp, maxLines = 1)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppColors.Orange.copy(alpha = 0.2f),
                    selectedLabelColor = AppColors.Orange
                )
            )
        }
        if (moderationError != null) {
            Text("Moderation: ${moderationError}", fontSize = 11.sp, color = AppColors.Orange,
                modifier = Modifier.padding(top = 4.dp))
        }

        attachError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = AppColors.Red, fontSize = 12.sp)
        }

        attachedImage?.let { (mime, b64) ->
            val bmp = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(), contentDescription = "Attached image",
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Image attached (${mime.substringAfter('/')})", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1f))
                TextButton(onClick = { attachedImage = null }) { Text("Remove", color = AppColors.Red, fontSize = 12.sp) }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Title") },
            placeholder = { Text("Enter a title for the report") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, colors = AppColors.outlinedFieldColors()
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it }, label = { Text("AI Prompt") },
            placeholder = { Text("Enter your prompt for the AI...") },
            modifier = Modifier.fillMaxWidth().weight(1f), minLines = 10, colors = AppColors.outlinedFieldColors()
        )
    }

    // Moderation model picker — overlay. Single-select; tap → set the
    // session's moderation model and close.
    if (showModerationPicker) {
        com.ai.ui.report.ReportSelectModelsScreen(
            aiSettings = uiState.aiSettings,
            titleText = "Pick moderation model",
            modelTypeFilter = com.ai.data.ModelType.MODERATION,
            onConfirm = { pick ->
                moderationModel = pick
                showModerationPicker = false
            },
            onBack = { showModerationPicker = false },
            onNavigateHome = onNavigateHome
        )
        return
    }

    // Flagged-prompt dialog — same Proceed-anyway / Cancel choices the
    // chat screen offers, with a 🐞 trace icon when the call left a
    // recording. Proceed continues to model selection; Cancel drops the
    // pending state and the user stays on the entry screen.
    val flagged = pendingFlagged
    if (flagged != null) {
        val (input, result, traceFn) = flagged
        AlertDialog(
            onDismissRequest = { pendingFlagged = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Prompt flagged by moderation", modifier = Modifier.weight(1f))
                    if (ApiTracer.isTracingEnabled && traceFn != null) {
                        Text("🐞", fontSize = 18.sp,
                            modifier = Modifier
                                .clickable { onNavigateToTraceFile(traceFn) }
                                .padding(start = 8.dp, end = 4.dp))
                    }
                }
            },
            text = {
                Column {
                    Text(
                        "The chosen moderation model flagged this prompt under: " +
                            result.firedCategories.joinToString(", "),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Sending it to the report's models anyway may produce unsafe output or violate provider policies.",
                        fontSize = 12.sp, color = AppColors.TextTertiary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingFlagged = null
                    reportViewModel.showGenericAgentSelection(
                        title, input,
                        imageBase64 = attachedImage?.second,
                        imageMime = attachedImage?.first,
                        webSearchTool = useWebSearch,
                        reasoningEffort = reasoningEffort.ifBlank { null }
                    )
                }) { Text("Proceed anyway", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFlagged = null }) {
                    Text("Cancel", maxLines = 1, softWrap = false)
                }
            }
        )
    }
}

/** State machine for the share-target "files queued as Knowledge"
 *  banner on [NewReportScreen]. Idle = banner shown with the
 *  Attach / Skip buttons; Working = ingestion in flight, status
 *  message live; Done = KB created and attached, success summary
 *  shown; Failed = error message + retry; Skipped = dismissed,
 *  banner gone. */
private sealed class SharedKbBannerState {
    object Idle : SharedKbBannerState()
    data class Working(val message: String) : SharedKbBannerState()
    data class Done(val kbId: String, val kbName: String, val sources: Int, val chunks: Int) : SharedKbBannerState()
    data class Failed(val message: String) : SharedKbBannerState()
    object Skipped : SharedKbBannerState()
}

@Composable
private fun SharedKbBanner(
    uris: List<String>,
    title: String,
    aiSettings: Settings,
    state: SharedKbBannerState,
    onAttach: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    // Resolve the embedder we'd use so the banner can show it (and
    // surface "no embedder available" up front instead of failing
    // mid-ingest). Local default wins when installed; otherwise the
    // first remote (provider, model) marked EMBEDDING.
    val embedderLabel = remember(aiSettings, context) {
        when {
            LocalEmbedder.isDefaultModelInstalled(context) ->
                "Local · ${LocalEmbedder.DEFAULT_MODEL_DISPLAY_NAME}"
            else -> supportedEmbeddingChoices(aiSettings).firstOrNull()
                ?.let { (svc, model) -> "${svc.id} · $model" }
        }
    }
    val canAttach = embedderLabel != null && state is SharedKbBannerState.Idle
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceDark)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val nFiles = uris.size
            Text(
                text = if (nFiles == 1) "1 file shared — attach as a knowledge base?"
                    else "$nFiles files shared — attach as a knowledge base?",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Green
            )
            embedderLabel?.let {
                Text("Embedder: $it", fontSize = 11.sp, color = AppColors.TextTertiary)
            } ?: Text(
                "No embedder available — install a local .tflite under Housekeeping → Local Models, or activate a provider with an embedding model.",
                fontSize = 11.sp, color = AppColors.Red
            )
            when (state) {
                is SharedKbBannerState.Working -> Text(state.message, fontSize = 12.sp, color = AppColors.TextSecondary)
                is SharedKbBannerState.Done -> Text(
                    "Indexed ${state.sources} source(s), ${state.chunks} chunk(s). Attached as 📚.",
                    fontSize = 12.sp, color = AppColors.Green
                )
                is SharedKbBannerState.Failed -> Text("Failed: ${state.message}", fontSize = 12.sp, color = AppColors.Red)
                else -> { /* Idle — nothing extra */ }
            }
            if (state !is SharedKbBannerState.Done) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAttach,
                        enabled = canAttach,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green)
                    ) { Text("Attach as KB", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                    OutlinedButton(
                        onClick = onSkip,
                        enabled = state !is SharedKbBannerState.Working,
                        colors = AppColors.outlinedButtonColors()
                    ) { Text("Skip", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** Create a fresh KB from [uris], pick an embedder, ingest each
 *  source via [KnowledgeService]. Returns [SharedKbBannerState.Done]
 *  on success ([Failed] otherwise). Run on [Dispatchers.IO] —
 *  callers should not block the main thread. */
private suspend fun ingestSharedKb(
    context: android.content.Context,
    repository: AnalysisRepository,
    aiSettings: Settings,
    reportTitle: String,
    uris: List<String>,
    onProgress: (String) -> Unit
): SharedKbBannerState = withContext(Dispatchers.IO) {
    val (embedderProviderId, embedderModel) = when {
        LocalEmbedder.isDefaultModelInstalled(context) ->
            "LOCAL" to LocalEmbedder.DEFAULT_MODEL_NAME
        else -> {
            val choice = supportedEmbeddingChoices(aiSettings).firstOrNull()
                ?: return@withContext SharedKbBannerState.Failed(
                    "No embedder available."
                )
            choice.first.id to choice.second
        }
    }
    val kbName = "Shared with ${reportTitle.ifBlank { "report" }} — ${
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    }"
    val kb = runCatching {
        KnowledgeStore.createKnowledgeBase(context, kbName, embedderProviderId, embedderModel)
    }.getOrElse { return@withContext SharedKbBannerState.Failed(it.message ?: "Could not create KB") }
    var totalChunks = 0
    var sourcesIndexed = 0
    uris.forEachIndexed { idx, raw ->
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return@forEachIndexed
        val isHttp = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
        onProgress("Ingesting ${idx + 1}/${uris.size}…")
        val result = if (isHttp) {
            KnowledgeService.indexUrl(context, repository, aiSettings, kb.id, trimmed) { msg, _, _ ->
                onProgress("(${idx + 1}/${uris.size}) $msg")
            }
        } else {
            val u = android.net.Uri.parse(trimmed)
            val type = pickTypeForUri(context, u)
            val displayName = displayNameForUri(context, u) ?: "shared_${System.currentTimeMillis()}"
            KnowledgeService.indexFile(context, repository, aiSettings, kb.id, type, u, displayName) { msg, _, _ ->
                onProgress("(${idx + 1}/${uris.size}) $displayName: $msg")
            }
        }
        result.onSuccess { src ->
            totalChunks += src.chunkCount
            sourcesIndexed++
        }
    }
    if (sourcesIndexed == 0) {
        // Drop the empty KB so it doesn't leak into the user's list.
        KnowledgeStore.deleteKnowledgeBase(context, kb.id)
        SharedKbBannerState.Failed("Nothing indexed.")
    } else {
        SharedKbBannerState.Done(kb.id, kbName, sourcesIndexed, totalChunks)
    }
}
