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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.R
import com.ai.data.AnalysisRepository
import com.ai.data.KnowledgeService
import com.ai.data.KnowledgeStore
import com.ai.data.MetadataDefaults
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.local.LocalEmbedder
import com.ai.data.ReportStorage
import com.ai.model.Settings
import com.ai.ui.knowledge.displayNameForUri
import com.ai.ui.knowledge.pickTypeForUri
import com.ai.ui.search.supportedEmbeddingChoices
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalMetadataIcons
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
    onNavigateToMonitor: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToReportsHub: () -> Unit,
    onNavigateToChatsHub: () -> Unit,
    onNavigateToAiSetup: () -> Unit,
    onNavigateToHousekeeping: () -> Unit,
    onNavigateToKnowledge: () -> Unit = {},
    onNavigateToExamples: () -> Unit = {},
    onOpenLatestReport: () -> Unit = {},
    viewModel: AppViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    BackHandler { (context as? Activity)?.moveTaskToBack(true) }

    val hasAnyAgent = remember(uiState.aiSettings.agents) { uiState.aiSettings.agents.isNotEmpty() }
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

    val cardHeight = 50.dp
    val cardSpacing = 12.dp
    // homeCardsExtra contributes 1 row-equivalent per visible new
    // card. ReportListCard is actually taller (variable rows
    // inside), but we keep the math simple — logo just shrinks a
    // touch when the cards are showing, still bounded by the
    // coerceIn(100, 220) below.
    val cardCount = 10
    val cardsHeight = (cardHeight * cardCount) + (cardSpacing * (cardCount - 1)) + 32.dp

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(horizontal = 16.dp)
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
                    // Tapping the logo opens the most recent report; with
                    // no report yet it falls back to the Reports hub.
                    .clickable(
                        interactionSource = logoInteractionSource,
                        indication = null
                    ) { if (hasAnyReport) onOpenLatestReport() else onNavigateToReportsHub() }
            )
            // Inactive cards are hidden entirely (rather than rendered
            // grayed-out + non-clickable as in earlier builds). Each
            // visibility-gated card carries its own trailing Spacer
            // inside the `if`, so the gap goes with it and the layout
            // stays compact.
            if (hasAnyAgent) {
                HubCard(icon = MetadataDefaults.REPORT_ICON, title = "AI Reports", onClick = onNavigateToReportsHub)
                Spacer(modifier = Modifier.height(12.dp))
                HubCard(icon = MetadataDefaults.CHAT, title = "AI Chat", onClick = onNavigateToChatsHub)
                Spacer(modifier = Modifier.height(12.dp))
            } else {
                // No agents yet \u2192 the AI Reports hub is hidden. Offer the
                // bundled example reports so a first-run user can open a
                // real report without configuring a provider.
                HubCard(icon = MetadataDefaults.TIP, title = "AI Examples", onClick = onNavigateToExamples)
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (uiState.generalSettings.experimentalFeaturesEnabled && uiState.generalSettings.showKnowledgeCard) {
                HubCard(icon = MetadataDefaults.LIBRARY, title = "AI Knowledge", onClick = onNavigateToKnowledge)
                Spacer(modifier = Modifier.height(12.dp))
            }
            // Models moved to Setup \u2192 Workers (a model is the raw material
            // agents / swarms are built from), so it sits with them now.
            HubCard(icon = MetadataDefaults.LIVE_DASHBOARD, title = "AI Monitor", onClick = onNavigateToMonitor)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = MetadataDefaults.AGENT, title = "AI Setup", onClick = onNavigateToAiSetup)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = MetadataDefaults.HOUSEKEEPING, title = "AI Housekeeping", onClick = onNavigateToHousekeeping)
            Spacer(modifier = Modifier.height(12.dp))
            Spacer(modifier = Modifier.height(32.dp))
            HubCard(icon = MetadataDefaults.SETTINGS, title = "Settings", onClick = onNavigateToSettings)
            Spacer(modifier = Modifier.height(12.dp))
            HubCard(icon = MetadataDefaults.HELP, title = "Help", onClick = onNavigateToHelp)
            Spacer(modifier = Modifier.height(12.dp))
            // \u2139\uFE0F About \u2014 replaces the old Documentation card. The About
            // screen surfaces the AI logo + version + build date and
            // hosts the two documentation hubs (Manual + Technical) as
            // its own cards.
            HubCard(icon = MetadataDefaults.INFO, title = "About", onClick = onNavigateToAbout)
        }
    }
}

@Composable
internal fun HubCard(icon: String, title: String, onClick: () -> Unit, iconTint: Color = Color.Unspecified) {
    val mi = LocalMetadataIcons.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mi.forFactoryGlyph(icon),
                fontSize = 28.sp,
                color = iconTint,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(42.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
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
 *  Centralised here so the two screens stay in sync.
 *
 *  - **Running**: a not-yet-completed report with a PENDING / RUNNING
 *    agent, or an in-flight translation targeting it.
 *  - **Problems**: exactly the reports the Broken-work scan flagged —
 *    the same list ([reportViewModel.brokenBatches]) that lights the
 *    top-bar ⚠️ badge, so the card and the badge can never disagree.
 *    One routine, two surfaces.
 *
 *  Both `translationRuns` and `brokenBatches` are pulled live from
 *  [reportViewModel]. The 5 s [cardsTick] catches background
 *  running-state changes; [refreshTick] keys onto the screen's resume
 *  lifecycle (and kicks a fresh Broken-work scan so Problems is current
 *  immediately rather than waiting for the 30 s background tick). */
@Composable
fun rememberHomeReportLists(
    refreshTick: Int,
    reportViewModel: ReportViewModel
): State<HomeReportLists> {
    val context = LocalContext.current
    val translationRuns by reportViewModel.translation.translationRuns.collectAsState()
    val brokenBatches by reportViewModel.brokenBatches.collectAsState()
    val problemReportIds = remember(brokenBatches) {
        brokenBatches.mapTo(HashSet()) { it.reportId }
    }
    LaunchedEffect(refreshTick) {
        reportViewModel.secondary.refreshBrokenBatches(context)
    }
    val cardsTick by produceState(initialValue = 0) {
        while (true) {
            kotlinx.coroutines.delay(5_000L)
            value = value + 1
        }
    }
    return produceState(
        initialValue = HomeReportLists(emptyList(), emptyList()),
        refreshTick, cardsTick, translationRuns, problemReportIds
    ) {
        value = withContext(Dispatchers.IO) {
            computeHomeReportLists(context, translationRuns, problemReportIds)
        }
    }
}

/** Pure-IO computation that produces the Running + Problems splits for
 *  the home / hub list cards. Running is derived from agent / translation
 *  state; Problems is simply the reports the Broken-work scan flagged
 *  ([problemReportIds]). Run on [Dispatchers.IO] by
 *  [rememberHomeReportLists]. */
internal fun computeHomeReportLists(
    context: android.content.Context,
    translationRuns: Map<String, TranslationRunState>,
    problemReportIds: Set<String> = emptySet()
): HomeReportLists {
    val all = ReportStorage.getAllReports(context)
    val activeTranslationReportIds = translationRuns.values
        .filter { !it.isFinished && !it.cancelled }
        .map { it.sourceReportId }
        .toSet()
    val running = all.filter { reportIsRunning(it, activeTranslationReportIds) }
    val problems = all.filter { it.id in problemReportIds }
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
    val homeReportLists by rememberHomeReportLists(refreshTick, reportViewModel)
    // No separate Running card any more — running reports appear under Latest
    // (with the spinning hourglass instead of their own icon), so don't exclude
    // them. Broken-work reports likewise stay in their normal card with the
    // warning icon.
    val latestReports = remember(allReports) {
        allReports.filter { !it.pinned }.take(10)
    }
    val bumpDelete: (String) -> Unit = { rid ->
        reportViewModel.deleteReport(context, rid)
        deleteTick++
    }
    // Bundled sample reports from assets/examples/index.xml.
    val examples by produceState(initialValue = emptyList<com.ai.data.ExampleEntry>(), Unit) {
        value = withContext(Dispatchers.IO) { com.ai.data.loadExampleIndex(context) }
    }
    // Shared example-open action (handles the exists/overwrite + import
    // spinner dialogs itself); same opener the standalone AI Examples
    // screen uses.
    val openExample = rememberExampleOpener(onOpenReportManage, onOpenReportView)
    // Wire the per-row 🔧 / 👁 / 🗑 icons to the navigation +
    // delete behaviour the hub wants on every list card. Replaces
    // the bundle the host installs at AI_REPORTS_HUB so the dash-
    // board's four cards all share one source of truth.
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalReportListIconBundle provides com.ai.ui.shared.ReportListIconBundle(
            onOpenManage = onOpenReportManage,
            onOpenView = onOpenReportView,
            onDelete = bumpDelete,
            runningIds = homeReportLists.running.mapTo(HashSet()) { it.id },
            brokenIds = homeReportLists.problems.mapTo(HashSet()) { it.id }
        )
    ) {
    Column(modifier = Modifier
        .fillMaxSize()
        .background(AppColors.AppBackground)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)) {
        // New / Search / All now live as icons in the bottom icon bar (not as
        // top buttons); housekeeping is intentionally not surfaced here.
        TitleBar(
            helpTopic = "reports_hub", title = "Reports",
            subject = "Create, browse and search your reports",
            onBackClick = onNavigateBack,
            onNewReport = onNavigateToNewAiReport,
            onSearchReports = onNavigateToSearchAiReports,
            onAllReports = onNavigateToAllReports
        )
        // Pinned leads when there are any pinned reports (hidden otherwise);
        // Latest always follows. Examples stays last.
        val hubCards = buildList {
            if (pinnedReports.isNotEmpty()) {
                add(Triple(MetadataDefaults.PIN, AppColors.CautionAccent, "Pinned AI Reports") to pinnedReports)
            }
            add(Triple(MetadataDefaults.CLOCK_RECENT, AppColors.InfoAccent, "Latest AI Reports") to latestReports)
        }
        hubCards.forEachIndexed { i, (meta, reports) ->
            if (i > 0) Spacer(modifier = Modifier.height(10.dp))
            ReportsHubListCard(
                accentEmoji = meta.first, accentColor = meta.second,
                label = meta.third, reports = reports, showEmptyHint = false
            )
        }
        if (examples.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            ExampleReportsCard(
                examples = examples,
                onOpenManage = { openExample(it, false) },
                onOpenView = { openExample(it, true) }
            )
        }
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
    val mi = LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (empty) 0.35f else 1f)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = mi.forFactoryGlyph(accentEmoji), fontSize = 18.sp)
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
