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
import com.ai.viewmodel.TranslationRunState
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
    val translationRuns by reportViewModel.translation.translationRuns.collectAsState()
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
    translationRuns: Map<String, TranslationRunState>
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
    reportViewModel: ReportViewModel,
    onHousekeeping: (() -> Unit)? = null
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
    val latestReports = remember(allReports) {
        allReports.filter { !it.pinned }.take(5)
    }
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
        TitleBar(helpTopic = "reports_hub", title = "AI Reports", onBackClick = onNavigateBack, onHousekeeping = onHousekeeping)
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
            accentEmoji = "⏳", accentColor = AppColors.Orange,
            label = "Running AI reports", reports = homeReportLists.running,
            showEmptyHint = false
        )
        Spacer(modifier = Modifier.height(10.dp))
        ReportsHubListCard(
            accentEmoji = "⚠️", accentColor = AppColors.Red,
            label = "AI Reports with problems", reports = homeReportLists.problems,
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
            label = "Latest AI Reports", reports = latestReports,
            showEmptyHint = false
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

