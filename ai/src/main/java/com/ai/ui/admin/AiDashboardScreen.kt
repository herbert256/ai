package com.ai.ui.admin

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiCallCaps
import com.ai.data.ApiTracer
import com.ai.data.ApiUsageRates
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.DiskUsageStats
import com.ai.data.HttpStatusStats
import com.ai.data.RetryStats
import com.ai.data.KnowledgeData
import com.ai.data.LogStatsData
import com.ai.data.ModelCooldownStore
import com.ai.data.MODEL_REASONING_CALL_KIND
import com.ai.data.MODEL_TEMPERATURE_CALL_KIND
import com.ai.data.SETTINGS_ICONS_CALL_KIND
import com.ai.data.MODEL_WEB_SEARCH_CALL_KIND
import com.ai.data.ModelTestRunState
import com.ai.data.NetworkSettings
import com.ai.data.PricingCache
import com.ai.data.ProviderModelData
import com.ai.data.ProviderRow
import com.ai.data.ProviderThrottle
import com.ai.data.ReportSectionData
import com.ai.data.ReportStats
import com.ai.data.SecondaryKind
import com.ai.data.TraceStatsData
import com.ai.data.UsageGroupsResult
import com.ai.data.UsageReportRow
import com.ai.data.UsageTypeGroup
import com.ai.data.computeKnowledgeStats
import com.ai.data.computeLogStats
import com.ai.data.computeTraceStats
import com.ai.data.computeProviderModelStats
import com.ai.data.computeReportStats
import com.ai.data.computeTierCounts
import com.ai.data.computeTierCountsRuntime
import com.ai.data.computeUsageGroups
import com.ai.ui.settings.SettingsPreferences
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCompactNumber
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.resumeRefreshTick
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.ReportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Two homepage screens, sharing the section composables + UI helpers below:
 *
 *  - [AiLiveDashboardScreen] — the **live ops monitor** (what's happening right
 *    now): concurrency caps, per-host throttle, model cooldowns, an active
 *    "Test all models" run, and log/trace health. Driven by a 750 ms ticker
 *    that only reads cheap in-memory snapshots; it stops the moment the screen
 *    leaves composition.
 *  - [AiMonitorScreen] — the hub. Below the live + per-call screens it lists
 *    the **lifetime aggregate** pages (Statistics was retired and merged in
 *    here): Knowledge totals inline; Reports/secondaries ([AiStatReportsScreen]),
 *    providers/models ([AiStatProvidersScreen]), spend & usage
 *    ([AiSpendUsageScreen]) and cost tiers ([AiCostsTierScreen]) each on their
 *    own page so they compute only when opened. The per-subject aggregate
 *    pages ([AiTraceStatsScreen], [AiLogStatsScreen]) are reached from the 📈
 *    icon on the API Traces / Application log screens.
 */
@Composable
fun AiLiveDashboardScreen(
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    /** Open the API Traces list filtered to one dimension value — wired
     *  to the 🐞 on each HTTP-responses (status) / provider-throttle (host)
     *  row. Field is "status" or "host". */
    onOpenTraceFilter: (field: String, value: String) -> Unit = { _, _ -> },
) {
    // Full-screen overlay: the ✏️ in the icon bar swaps in the editor (pin /
    // reorder / preview every card). The early return preserves this screen's
    // remember-state, and only the editor's BackHandler is live while it's up,
    // so Back closes the editor before it leaves the dashboard.
    var showEdit by remember { mutableStateOf(false) }
    if (showEdit) {
        AiDashboardEditScreen(appViewModel, reportViewModel, onBack = { showEdit = false }, onOpenTraceFilter)
        return
    }

    BackHandler { onBack() }
    val context = LocalContext.current
    val mi = com.ai.ui.shared.LocalMetadataIcons.current

    val uiState by appViewModel.uiState.collectAsState()
    val gs = uiState.generalSettings
    val pinned = gs.pinnedDashboardCards
    val order = reconcileDashboardOrder(gs.dashboardCardOrder)

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_live_dashboard",
            title = "Live Dashboard",
            subject = "What's happening right now",
            onBackClick = onBack,
            onEdit = { showEdit = true },
            reportIcon = mi.liveDashboard, reportIconGoesHome = true
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Just the pinned cards, in order — each open, no controls. Pin /
            // reorder happens behind the ✏️ editor. Each card body owns its own
            // ticker + data, so keeping this set lean keeps the screen cheap.
            val pinnedOrder = order.filter { it in pinned }
            if (pinnedOrder.isEmpty()) {
                item {
                    Text(
                        mi.iconizedText("No pinned cards yet — tap ✏️ to pin the cards you want here."),
                        fontSize = 13.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            itemsIndexed(pinnedOrder, key = { _, id -> id }) { _, id ->
                val meta = DASH_CARD_META[id] ?: return@itemsIndexed
                DashboardCard(meta.emoji, meta.title, meta.accent) {
                    DashCardBody(id, appViewModel, reportViewModel, context, onOpenTraceFilter)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The Live Dashboard editor — every card in the user's saved order, each with
 *  its 📌 pin (open on the dashboard), ↑ / ↓ reorder and ▾ / ▸ collapse
 *  controls. Reached from the ✏️ on the Live Dashboard. Pins + order persist on
 *  GeneralSettings → eval_prefs, so they survive restart and ride along in
 *  backup/restore automatically. A card fetches + ~0.75 s-refreshes only while
 *  expanded (the body composes only while open), so a collapsed card does zero
 *  work even here. */
@Composable
private fun AiDashboardEditScreen(
    appViewModel: AppViewModel,
    reportViewModel: ReportViewModel,
    onBack: () -> Unit,
    onOpenTraceFilter: (field: String, value: String) -> Unit = { _, _ -> },
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val mi = com.ai.ui.shared.LocalMetadataIcons.current

    val uiState by appViewModel.uiState.collectAsState()
    val gs = uiState.generalSettings
    val pinned = gs.pinnedDashboardCards
    val order = reconcileDashboardOrder(gs.dashboardCardOrder)

    // Expansion state. Initialised from the pinned set so pinned cards open on
    // load and everything else starts collapsed. Plain remember → resets to the
    // pinned baseline each time the editor opens.
    var open by remember { mutableStateOf(pinned) }
    val toggle: (String) -> Unit = { k -> open = if (k in open) open - k else open + k }
    fun togglePin(id: String) {
        val wasPinned = id in gs.pinnedDashboardCards
        appViewModel.updateGeneralSettings(
            gs.copy(pinnedDashboardCards = if (wasPinned) gs.pinnedDashboardCards - id else gs.pinnedDashboardCards + id)
        )
        if (!wasPinned) open = open + id   // newly pinned → show it open now
    }
    fun moveCard(id: String, delta: Int) {
        val cur = reconcileDashboardOrder(gs.dashboardCardOrder).toMutableList()
        val i = cur.indexOf(id); val j = i + delta
        if (i < 0 || j < 0 || j > cur.lastIndex) return
        cur[i] = cur[j]; cur[j] = id
        appViewModel.updateGeneralSettings(gs.copy(dashboardCardOrder = cur))
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_live_dashboard_edit",
            title = "Edit dashboard",
            subject = "Pin, reorder and preview cards",
            onBackClick = onBack,
            reportIcon = mi.liveDashboard
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Every card in the user's saved order, with controls; each
            // collapsed unless pinned (the body composes only while open).
            itemsIndexed(order, key = { _, id -> id }) { index, id ->
                val meta = DASH_CARD_META[id] ?: return@itemsIndexed
                CollapsibleCard(
                    id = id, emoji = meta.emoji, title = meta.title, accent = meta.accent,
                    open = open, pinned = pinned,
                    isFirst = index == 0, isLast = index == order.lastIndex,
                    onToggle = toggle,
                    onTogglePin = { togglePin(it) },
                    onMoveUp = { moveCard(it, -1) },
                    onMoveDown = { moveCard(it, +1) },
                ) {
                    DashCardBody(id, appViewModel, reportViewModel, context, onOpenTraceFilter)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** A pinned-mode card — header (emoji + accent title) above an
 *  always-shown body, no controls. */
@Composable
private fun DashboardCard(
    emoji: String, title: String, accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(com.ai.ui.shared.LocalMetadataIcons.current.forFactoryGlyph(emoji), fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** AI Monitor — the hub for the live + per-call observability streams:
 *  the Live Dashboard, the API Traces and the Application log. The
 *  lifetime-aggregate stat pages live one level down under
 *  [AiStatisticsScreen], reached from the 📊 Statistics card here. */
@Composable
fun AiMonitorScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToLiveDashboard: () -> Unit = {},
    onNavigateToTraces: () -> Unit = {},
    onNavigateToAppLog: () -> Unit = {},
    onNavigateToAudit: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToCrashReports: () -> Unit = {},
    onHousekeeping: (() -> Unit)? = null,
) {
    BackHandler { onBack() }
    // Only surface the Crash reports entry when at least one report exists.
    // Re-checked on every screen-resume so it appears after a fresh crash
    // and disappears once the user clears them.
    val resumeTick = com.ai.ui.shared.resumeRefreshTick()
    val hasCrashReports by produceState(false, resumeTick) {
        value = withContext(Dispatchers.IO) { com.ai.data.CrashReporter.hasReports() }
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_monitor",
            title = "Monitor",
            subject = "Live and historical observability",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.liveDashboard, reportIconGoesHome = true,
            onTitleClick = onNavigateHome,
            onHousekeeping = onHousekeeping
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { LinkCard(com.ai.data.MetadataDefaults.LIVE_DASHBOARD, "Live Dashboard", "In-flight calls, caps and throttle state", onNavigateToLiveDashboard) }
            item { LinkCard(com.ai.data.MetadataDefaults.TRACES, "API Traces", "Per-call request/response records", onNavigateToTraces) }
            item { LinkCard(com.ai.data.MetadataDefaults.APP_LOG, "Application log", "The in-app application log, line by line", onNavigateToAppLog) }
            item { LinkCard(com.ai.data.MetadataDefaults.AUDIT, "Audit", "Per-report trail of actions, batches and API calls", onNavigateToAudit) }
            item { LinkCard(com.ai.data.MetadataDefaults.STATISTICS_MONITOR, "Statistics", "Lifetime totals across reports, providers, models, spend and logs", onNavigateToStatistics) }
            if (hasCrashReports) {
                item { LinkCard("💥", "Crash reports", "Captured app errors — tap to view and share", onNavigateToCrashReports) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Crash reports — browse / view / share / clear the captured error
 *  reports [com.ai.data.CrashReporter] saves. Reached from the Monitor hub
 *  (shown there only when at least one report exists). Each report carries
 *  app version, device, locale and the stack trace. */
@Composable
fun AiCrashReportsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToMonitor: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // Detail overlay — full report text with copy / share / delete. Layers
    // over the list (returns here on back), per the full-screen overlay pattern.
    val sel = selected
    if (sel != null) {
        val body by produceState<String?>(null, sel) {
            value = withContext(Dispatchers.IO) { com.ai.data.CrashReporter.readReport(sel) }
        }
        val text = body
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            TitleBar(
                helpTopic = "ai_crash_reports",
                title = "Crash report",
                subject = sel.removePrefix("report_").removeSuffix(".txt"),
                onBackClick = { selected = null },
                onCopy = text?.let { t -> { com.ai.ui.shared.copyToClipboard(context, t) } },
                onShare = text?.let { t -> { com.ai.ui.shared.shareText(context, t, "AI crash report") } },
                onDelete = {
                    com.ai.data.CrashReporter.deleteReport(sel)
                    selected = null
                    refreshTick++
                }
            )
            if (text == null) {
                Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            } else {
                Text(
                    text, fontSize = 12.sp, color = Color.White,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)
                )
            }
        }
        return
    }

    val reports by produceState<List<com.ai.data.CrashReporter.CrashReportInfo>?>(null, refreshTick) {
        value = withContext(Dispatchers.IO) { com.ai.data.CrashReporter.listReports() }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_crash_reports",
            title = "Crash reports",
            subject = "Captured errors — tap to view & share",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.liveDashboard,
            onReportIconClick = onNavigateToMonitor,
            onTitleClick = onNavigateToMonitor,
            onDelete = { confirmClear = true }
        )
        val list = reports
        when {
            list == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            list.isEmpty() -> Text("No crash reports.", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(list, key = { it.id }) { r ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
                        modifier = Modifier.fillMaxWidth().clickable { selected = r.id }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.whenLabel, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                                Text(
                                    r.kind, fontSize = 10.sp,
                                    color = if (r.kind == "FATAL") AppColors.DangerAccent else AppColors.WarningAccent,
                                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                        .background(AppColors.SurfaceDark).padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(r.summary, fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all crash reports?") },
            text = { Text("Deletes every saved crash report. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    com.ai.data.CrashReporter.clearAllReports()
                    refreshTick++
                    Toast.makeText(context, "Crash reports cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1, softWrap = false) } }
        )
    }
}

/** Statistics — the hub for every lifetime-aggregate stat page. Sits one
 *  level under the Monitor hub (reached from its 📊 Statistics card). Each
 *  row opens its own page so the heavier breakdowns only compute when
 *  opened; the cheap Knowledge totals show inline. The two per-subject
 *  aggregate pages (API trace / App log statistics) are also reachable
 *  from the 📈 icon on the API Traces / Application log screens. */
@Composable
fun AiStatisticsScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToReports: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToSpendUsage: () -> Unit = {},
    onNavigateToCostsTier: () -> Unit = {},
    onNavigateToTraceStats: () -> Unit = {},
    onNavigateToLogStats: () -> Unit = {},
    onHousekeeping: (() -> Unit)? = null,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val refreshTick = resumeRefreshTick()
    // Only the (cheap) Knowledge totals are shown inline; everything heavier
    // is its own page reached via a link card.
    val kb by produceState<KnowledgeData?>(null, refreshTick) {
        value = computeKnowledgeStats(context)
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_statistics",
            title = "Statistics",
            subject = "Lifetime aggregates across the app",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.chart, reportIconGoesHome = true,
            onTitleClick = onNavigateHome,
            onHousekeeping = onHousekeeping
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { LinkCard(com.ai.data.MetadataDefaults.COPY, "Reports", "Reports + secondary results totals", onNavigateToReports) }
            item { LinkCard(com.ai.data.MetadataDefaults.PLUG, "Providers", "Per-provider keys, formats, caches, test runs", onNavigateToProviders) }
            item { LinkCard(com.ai.data.MetadataDefaults.MODEL_ICON, "Models", "Capabilities, types, context, states", onNavigateToModels) }
            item { LinkCard(com.ai.data.MetadataDefaults.COST, "Spend & usage", "Calls, tokens and cost by provider, type and report", onNavigateToSpendUsage) }
            item { LinkCard(com.ai.data.MetadataDefaults.COMPARE, "Costs tiers", "Pricing tier per model + catalog freshness", onNavigateToCostsTier) }
            item { LinkCard(com.ai.data.MetadataDefaults.TRACES, "Trace statistics", "Aggregate stats over the API traces", onNavigateToTraceStats) }
            item { LinkCard(com.ai.data.MetadataDefaults.APP_LOG, "App log statistics", "Aggregate stats over the application log", onNavigateToLogStats) }
            kb?.let { if (it.kbCount > 0) item { KnowledgeSection(it) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** API trace statistics — aggregate view over the API traces. */
@Composable
fun AiTraceStatsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
    onOpenTraceFilter: (field: String, value: String) -> Unit = { _, _ -> },
    onOpenBreakdown: (dim: String) -> Unit = {},
) {
    BackHandler { onBack() }
    val refreshTick = resumeRefreshTick()
    val d by produceState<TraceStatsData?>(null, refreshTick) { value = computeTraceStats() }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_trace_stats", title = "API trace statistics", subject = "What hit the network",
            onBackClick = onBack, reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics, onTitleClick = onNavigateToStatistics
        )
        val s = d
        when {
            s == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            s.total == 0 -> Text(
                if (s.tracingEnabled) "No API traces recorded yet."
                else "No API traces. Tracing is OFF — enable it in Settings → API tracing to collect them.",
                color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionCard("🐞", "Overview", AppColors.SecondaryAccent) {
                        KeyVal("Tracing", if (s.tracingEnabled) "on" else "off", if (s.tracingEnabled) AppColors.SuccessAccent else AppColors.WarningAccent)
                        KeyVal("Total traces", "${s.total}")
                        KeyVal("Distinct runs", "${s.runs}")
                        if (s.partial > 0) KeyVal("Partial (streaming)", "${s.partial}", AppColors.TextSecondary)
                    }
                }
                item {
                    SectionCard("📡", "Status", AppColors.InfoAccent) {
                        StatRow("✅ 2xx", "${s.ok2xx}", AppColors.SuccessAccent) { onOpenTraceFilter("status", "2xx") }
                        StatRow("🚧 429", "${s.rate429}", if (s.rate429 > 0) AppColors.WarningAccent else AppColors.TextDim) { onOpenTraceFilter("status", "429") }
                        StatRow("⚠️ 4xx", "${s.client4xx}", if (s.client4xx > 0) AppColors.WarningAccent else AppColors.TextDim) { onOpenTraceFilter("status", "4xx") }
                        StatRow("🔥 5xx", "${s.server5xx}", if (s.server5xx > 0) AppColors.DangerAccent else AppColors.TextDim) { onOpenTraceFilter("status", "5xx") }
                        StatRow("💥 Failed", "${s.failed0}", if (s.failed0 > 0) AppColors.DangerAccent else AppColors.TextDim) { onOpenTraceFilter("status", "0") }
                        StatRow("▫️ Other", "${s.other}", AppColors.TextDim) { onOpenTraceFilter("status", "other") }
                    }
                }
                if (s.byHost.isNotEmpty()) item {
                    SectionCard("🌐", "Top hosts", AppColors.SuccessAccent, onClick = { onOpenBreakdown("host") }) {
                        s.byHost.take(5).forEach { (h, c) -> KeyVal(h, "$c") }
                        if (s.byHost.size > 5) KeyVal("+${s.byHost.size - 5} more", "→", AppColors.TextTertiary)
                    }
                }
                if (s.byModel.isNotEmpty()) item {
                    SectionCard("🧠", "Top models", AppColors.PrimaryAccent, onClick = { onOpenBreakdown("model") }) {
                        s.byModel.take(5).forEach { (m, c) -> KeyVal(com.ai.ui.shared.shortModelName(m), "$c") }
                        if (s.byModel.size > 5) KeyVal("+${s.byModel.size - 5} more", "→", AppColors.TextTertiary)
                    }
                }
                if (s.byCategory.isNotEmpty()) item {
                    SectionCard("🏷️", "Top categories", AppColors.SecondaryAccent, onClick = { onOpenBreakdown("category") }) {
                        s.byCategory.take(5).forEach { (cat, c) -> KeyVal(cat, "$c") }
                        if (s.byCategory.size > 5) KeyVal("+${s.byCategory.size - 5} more", "→", AppColors.TextTertiary)
                    }
                }
                item {
                    SectionCard("🗓️", "Activity", AppColors.InfoAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("☀️", "Today", s.today, AppColors.SuccessAccent)
                            StatChip("📅", "7 days", s.last7d, AppColors.InfoAccent)
                            StatChip("🗓️", "30 days", s.last30d, AppColors.SecondaryAccent)
                        }
                        s.newest?.let { Spacer(Modifier.height(4.dp)); KeyVal("Newest", fmtFetched(it)) }
                        s.oldest?.let { KeyVal("Oldest", fmtFetched(it)) }
                    }
                }
                item {
                    SectionCard("📋", "Reports", AppColors.WarningAccent) {
                        KeyVal("Traces tied to a report", "${s.withReport}")
                        KeyVal("Distinct reports", "${s.distinctReports}")
                        val avg = if (s.distinctReports > 0) s.withReport.toDouble() / s.distinctReports else 0.0
                        KeyVal("Avg traces / report", String.format(Locale.US, "%.1f", avg), AppColors.TextSecondary)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Full breakdown of API traces by one dimension (host / model /
 *  category). Reached by tapping a "Top …" card on the API-trace-
 *  statistics screen; every row drills into the trace list filtered
 *  to that value. */
@Composable
fun AiTraceBreakdownScreen(
    dim: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
    onOpenTraceFilter: (field: String, value: String) -> Unit = { _, _ -> },
) {
    BackHandler { onBack() }
    val refreshTick = resumeRefreshTick()
    val d by produceState<TraceStatsData?>(null, refreshTick) { value = computeTraceStats() }
    val (emoji, title, accent) = when (dim) {
        "host" -> Triple(com.ai.data.MetadataDefaults.WEB, "Trace hosts", AppColors.SuccessAccent)
        "model" -> Triple(com.ai.data.MetadataDefaults.MODEL_ICON, "Trace models", AppColors.PrimaryAccent)
        else -> Triple(com.ai.data.MetadataDefaults.LABEL, "Trace categories", AppColors.SecondaryAccent)
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_trace_breakdown", title = title, subject = "Every $dim, by trace count",
            onBackClick = onBack, reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics, onTitleClick = onNavigateToStatistics
        )
        val s = d
        val rows = when (dim) { "host" -> s?.byHost; "model" -> s?.byModel; else -> s?.byCategory } ?: emptyList()
        when {
            s == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            rows.isEmpty() -> Text("No traces.", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionCard(emoji, "$title (${rows.size})", accent) {
                        rows.forEach { (k, c) ->
                            val label = if (dim == "model") com.ai.ui.shared.shortModelName(k) else k
                            StatRow(label, "$c") { onOpenTraceFilter(dim, k) }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** App log statistics — aggregate view over the in-app application log. */
@Composable
fun AiLogStatsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
) {
    BackHandler { onBack() }
    val refreshTick = resumeRefreshTick()
    val d by produceState<LogStatsData?>(null, refreshTick) { value = computeLogStats() }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_log_stats", title = "App log statistics", subject = "The in-app log",
            onBackClick = onBack, reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics, onTitleClick = onNavigateToStatistics
        )
        val s = d
        when {
            s == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            s.fileCount == 0 -> Text("No log files yet.", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionCard("🩺", "Health", AppColors.SuccessAccent) {
                        KeyVal("Log level", s.level)
                        KeyVal("Writer", if (s.writerError == null) "OK" else "ERROR", if (s.writerError == null) AppColors.SuccessAccent else AppColors.DangerAccent)
                        if (s.writerError != null) Text(s.writerError, fontSize = 11.sp, color = AppColors.DangerAccent)
                        KeyVal("Dropped lines", "${s.droppedLines}", if (s.droppedLines > 0) AppColors.WarningAccent else Color.White)
                    }
                }
                item {
                    SectionCard("📊", "By level", AppColors.SecondaryAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("❌", "Error", s.byLevel["ERROR"] ?: 0, if ((s.byLevel["ERROR"] ?: 0) > 0) AppColors.DangerAccent else AppColors.TextDim)
                            StatChip("⚠️", "Warn", s.byLevel["WARN"] ?: 0, if ((s.byLevel["WARN"] ?: 0) > 0) AppColors.WarningAccent else AppColors.TextDim)
                            StatChip("ℹ️", "Info", s.byLevel["INFO"] ?: 0, AppColors.SuccessAccent)
                            StatChip("🔧", "Debug", s.byLevel["DEBUG"] ?: 0, AppColors.InfoAccent)
                            StatChip("🔬", "Trace", s.byLevel["TRACE"] ?: 0, AppColors.TextSecondary)
                        }
                        Spacer(Modifier.height(4.dp))
                        KeyVal("Total entries", "${s.totalEntries}")
                    }
                }
                if (s.topTags.isNotEmpty()) item {
                    SectionCard("🏷️", "Top tags", AppColors.PrimaryAccent) { s.topTags.forEach { (tag, c) -> KeyVal(tag, "$c") } }
                }
                item {
                    SectionCard("🗂️", "Files", AppColors.InfoAccent) {
                        KeyVal("Log files", "${s.fileCount}")
                        KeyVal("Total size", fmtBytes(s.totalBytes))
                        if (s.oldestDate != null && s.newestDate != null) KeyVal("Date range", "${s.oldestDate} → ${s.newestDate}", AppColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        s.files.forEach { (date, bytes) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(date, fontSize = 12.sp, color = Color.White)
                                Text(fmtBytes(bytes), fontSize = 12.sp, color = AppColors.TextSecondary)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Compact byte size — "12 KB", "3.4 MB". */
private fun fmtBytes(b: Long): String = when {
    b >= 1_000_000 -> String.format(Locale.US, "%.1f MB", b / 1_000_000.0)
    b >= 1_000 -> "${b / 1_000} KB"
    else -> "$b B"
}

/** Statistics - Reports — report + secondary-result lifetime totals. */
@Composable
fun AiStatReportsScreen(
    reportViewModel: ReportViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val refreshTick = resumeRefreshTick()
    val slowTick by produceState(0) { while (true) { delay(10_000); value++ } }
    val translationRuns by reportViewModel.translation.translationRuns.collectAsState()
    val data by produceState<ReportSectionData?>(null, refreshTick, slowTick, translationRuns) {
        value = computeReportStats(context, translationRuns)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_stat_reports",
            title = "Reports",
            subject = "Reports and secondary results",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics
        )
        val d = data
        if (d == null) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item { ReportsSection(d.reports) }

                item {
                    SectionCard("🤖", "Agent calls", AppColors.InfoAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("✅", "Success", d.agentSuccess, AppColors.SuccessAccent)
                            StatChip("❌", "Error", d.reports.erroredCalls, if (d.reports.erroredCalls > 0) AppColors.DangerAccent else AppColors.TextDim)
                            StatChip("⏹️", "Stopped", d.reports.stopped, AppColors.TextSecondary)
                            StatChip("⏳", "In flight", d.agentPending, if (d.agentPending > 0) AppColors.WarningAccent else AppColors.TextDim)
                        }
                        Spacer(Modifier.height(6.dp))
                        val errRate = if (d.reports.agentCalls > 0) d.reports.erroredCalls * 100.0 / d.reports.agentCalls else 0.0
                        KeyVal("Error rate", String.format(Locale.US, "%.1f%%", errRate),
                            if (errRate >= 10.0) AppColors.DangerAccent else if (errRate > 0) AppColors.WarningAccent else AppColors.SuccessAccent)
                        Bar(if (d.reports.agentCalls > 0) d.reports.erroredCalls.toFloat() / d.reports.agentCalls else 0f, AppColors.DangerAccent)
                        val avgAgents = if (d.reports.total > 0) d.reports.agentCalls.toDouble() / d.reports.total else 0.0
                        KeyVal("Avg models / report", String.format(Locale.US, "%.1f", avgAgents))
                    }
                }

                item {
                    SectionCard("💵", "Tokens & spend", AppColors.SuccessAccent) {
                        KeyVal("Input tokens", formatCompactNumber(d.inputTokens))
                        KeyVal("Output tokens", formatCompactNumber(d.outputTokens))
                        KeyVal("Total tokens", formatCompactNumber(d.inputTokens + d.outputTokens))
                        KeyVal("Secondary tokens", formatCompactNumber(d.secondaryTokens), AppColors.TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        KeyVal("Report spend", money(d.reports.spend), AppColors.SuccessAccent)
                        KeyVal("Secondary spend", money(d.secondaryCost), AppColors.SuccessAccent)
                        KeyVal("Total spend", money(d.reports.spend + d.secondaryCost), AppColors.SuccessAccent)
                        val avgPerReport = if (d.reports.total > 0) d.reports.spend / d.reports.total else 0.0
                        val avgPerCall = if (d.reports.agentCalls > 0) d.reports.spend / d.reports.agentCalls else 0.0
                        KeyVal("Avg / report", money(avgPerReport), AppColors.TextSecondary)
                        KeyVal("Avg / call", money(avgPerCall), AppColors.TextSecondary)
                        KeyVal("Total compute", fmtDuration(d.totalDurationMs))
                    }
                }

                item {
                    SectionCard("🗓️", "Activity", AppColors.SecondaryAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("☀️", "Today", d.createdToday, AppColors.SuccessAccent)
                            StatChip("📅", "7 days", d.created7d, AppColors.InfoAccent)
                            StatChip("🗓️", "30 days", d.created30d, AppColors.SecondaryAccent)
                            StatChip("📌", "Pinned", d.pinned, AppColors.CautionAccent)
                        }
                        d.oldestCreatedAt?.let {
                            Spacer(Modifier.height(4.dp))
                            KeyVal("Oldest report", fmtDuration(System.currentTimeMillis() - it) + " ago", AppColors.TextSecondary)
                        }
                    }
                }

                item {
                    SectionCard("✨", "Features used", AppColors.PrimaryAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("👁", "Vision", d.withImage, AppColors.InfoAccent)
                            StatChip("🌐", "Web search", d.withWebSearch, AppColors.SuccessAccent)
                            StatChip("🧠", "Reasoning", d.withReasoning, AppColors.PrimaryAccent)
                            StatChip("📚", "Knowledge", d.withKnowledge, AppColors.CautionAccent)
                            StatChip("🌍", "Translated", d.translated, AppColors.InfoAccent)
                            StatChip("📊", "Table", d.tableReports, AppColors.TextSecondary)
                        }
                    }
                }

                if (d.topModels.isNotEmpty()) item {
                    SectionCard("🏆", "Top models (by calls)", AppColors.WarningAccent) {
                        d.topModels.forEach { (model, calls) -> KeyVal(com.ai.ui.shared.shortModelName(model), "$calls") }
                    }
                }
                if (d.topProviders.isNotEmpty()) item {
                    SectionCard("🔌", "Top providers (by calls)", AppColors.InfoAccent) {
                        d.topProviders.forEach { (provider, calls) -> KeyVal(provider, "$calls") }
                    }
                }

                item { SecondariesSection(d.secondaries, d.metaByName) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Statistics - Providers / Models — provider/model counts + cache freshness. */
@Composable
fun AiStatProvidersScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val uiState by appViewModel.uiState.collectAsState()
    val refreshTick = resumeRefreshTick()
    val data by produceState<ProviderModelData?>(null, refreshTick) {
        value = computeProviderModelStats(context, uiState.aiSettings)
    }
    var expanded by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_stat_providers",
            title = "Providers / Models",
            subject = "The whole model fleet",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics
        )
        val d = data
        if (d == null) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }

                item {
                    SectionCard("🔌", "Providers", AppColors.SecondaryAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🔢", "Configured", d.providersConfigured, Color.White)
                            StatChip("🟢", "Active", d.providersActive, AppColors.SuccessAccent)
                            StatChip("🔑", "With key", d.providersWithKey, AppColors.InfoAccent)
                            StatChip("⚪", "Inactive", d.providersConfigured - d.providersActive, AppColors.TextDim)
                        }
                    }
                }
                item {
                    SectionCard("🔣", "API formats", AppColors.InfoAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🤝", "OpenAI-compatible", d.byFormat["OPENAI_COMPATIBLE"] ?: 0, AppColors.SuccessAccent)
                            StatChip("🅰️", "Anthropic", d.byFormat["ANTHROPIC"] ?: 0, AppColors.WarningAccent)
                            StatChip("🔷", "Google", d.byFormat["GOOGLE"] ?: 0, AppColors.InfoAccent)
                        }
                    }
                }
                item {
                    SectionCard("🗂️", "Catalog cache", AppColors.SuccessAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("✅", "Cached", d.cached, AppColors.SuccessAccent)
                            StatChip("⏳", "Stale >7d", d.stale, if (d.stale > 0) AppColors.WarningAccent else AppColors.TextDim)
                            StatChip("➖", "Never", d.neverCached, AppColors.TextDim)
                        }
                    }
                }
                item {
                    SectionCard("👥", "Workers", AppColors.InfoAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🤖", "Agents", d.agents, AppColors.InfoAccent)
                            StatChip("🐦", "Flocks", d.flocks, AppColors.SuccessAccent)
                            StatChip("🐝", "Swarms", d.swarms, AppColors.WarningAccent)
                        }
                    }
                }
                d.lastTest?.let { t ->
                    item {
                        SectionCard("🧪", "Last test-all-models", AppColors.PrimaryAccent) {
                            KeyVal("For testing", "${t.forTesting}")
                            KeyVal("Passed", "${t.passed}", AppColors.SuccessAccent)
                            KeyVal("Failed", "${t.failed}", if (t.failed > 0) AppColors.DangerAccent else Color.White)
                            KeyVal("Cost", money(t.cost), AppColors.SuccessAccent)
                            KeyVal("When", fmtFetched(t.startedAt))
                        }
                    }
                }

                item {
                    Text("Per provider", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
                items(d.providers, key = { it.id }) { row ->
                    ProviderStatCard(
                        row = row,
                        isExpanded = row.id in expanded,
                        onToggle = { expanded = if (row.id in expanded) expanded - row.id else expanded + row.id }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Models — capability / type / context / state stats over the whole catalog. */
@Composable
fun AiStatModelsScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val uiState by appViewModel.uiState.collectAsState()
    val refreshTick = resumeRefreshTick()
    val data by produceState<ProviderModelData?>(null, refreshTick) {
        value = computeProviderModelStats(context, uiState.aiSettings)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_stat_models", title = "Models", subject = "The whole catalog",
            onBackClick = onBack, reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics, onTitleClick = onNavigateToStatistics
        )
        val d = data
        if (d == null) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionCard("🧠", "Models", AppColors.PrimaryAccent) {
                        KeyVal("Total configured", "${d.totalModels}")
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("👁", "Vision", d.vision, AppColors.InfoAccent)
                            StatChip("🌐", "Web search", d.webSearch, AppColors.SuccessAccent)
                            StatChip("🧠", "Reasoning", d.reasoning, AppColors.PrimaryAccent)
                            StatChip("🔢", "Embedding", d.embedding, AppColors.SecondaryAccent)
                        }
                    }
                }
                item {
                    SectionCard("🛠️", "Capabilities", AppColors.InfoAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🧰", "Function calling", d.fnCalling, AppColors.SuccessAccent)
                            StatChip("📄", "PDF input", d.pdfInput, AppColors.InfoAccent)
                            StatChip("🎚️", "Reasoning levels", d.reasoningLevels, AppColors.PrimaryAccent)
                            StatChip("🗒️", "With metadata", d.withCaps, AppColors.TextSecondary)
                        }
                    }
                }
                if (d.modelsByType.isNotEmpty()) item {
                    SectionCard("🏷️", "By type", AppColors.SecondaryAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.modelsByType.forEach { (type, count) -> StatChip("•", type, count, AppColors.TextSecondary) }
                        }
                    }
                }
                item {
                    SectionCard("📏", "Context length", AppColors.SuccessAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.contextBuckets.forEach { (bucket, count) -> StatChip("•", bucket, count, AppColors.TextSecondary) }
                        }
                        if (d.maxContextModel != null) {
                            Spacer(Modifier.height(4.dp))
                            KeyVal("Largest", "${com.ai.ui.shared.shortModelName(d.maxContextModel!!)} (${formatCompactNumber(d.maxContextTokens.toLong())})", AppColors.TextSecondary)
                        }
                    }
                }
                item {
                    SectionCard("🚦", "States", AppColors.WarningAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("⛔", "Blocked", d.blocked, if (d.blocked > 0) AppColors.DangerAccent else AppColors.TextDim)
                            StatChip("🚫", "Inaccessible", d.inaccessible, if (d.inaccessible > 0) AppColors.WarningAccent else AppColors.TextDim)
                            StatChip("⏭️", "Test-excluded", d.testExcluded, AppColors.TextSecondary)
                            StatChip("❄️", "Cooling", d.cooling, if (d.cooling > 0) AppColors.WarningAccent else AppColors.TextDim)
                        }
                    }
                }
                if (d.deprecated > 0) item {
                    SectionCard("⚰️", "Deprecated", AppColors.DangerAccent) {
                        KeyVal("Models flagged deprecated", "${d.deprecated}", AppColors.WarningAccent)
                    }
                }
                item {
                    SectionCard("🔌", "Models per provider", AppColors.InfoAccent) {
                        val withModels = d.providers.filter { it.models > 0 }
                        val avg = if (withModels.isNotEmpty()) withModels.sumOf { it.models }.toDouble() / withModels.size else 0.0
                        KeyVal("Avg per provider", String.format(Locale.US, "%.1f", avg))
                        KeyVal("Max", "${withModels.maxOfOrNull { it.models } ?: 0}")
                        Spacer(Modifier.height(4.dp))
                        withModels.sortedByDescending { it.models }.take(6).forEach { KeyVal(it.id, "${it.models}") }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ProviderStatCard(row: ProviderRow, isExpanded: Boolean, onToggle: () -> Unit) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (row.active) mi.greenCircle else mi.whiteCircle, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(row.id, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    formatTag(row.format), fontSize = 9.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AppColors.SurfaceDark).padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("${row.models} models", fontSize = 12.sp, color = AppColors.TextSecondary)
                Spacer(Modifier.width(6.dp))
                Text(if (isExpanded) mi.caretExpanded else mi.caretCollapsed, color = AppColors.TextTertiary)
            }
            // Compact signal chips — only the non-zero ones.
            val signals = buildList {
                if (row.vision > 0) add(mi.view to row.vision)
                if (row.webSearch > 0) add(mi.web to row.webSearch)
                if (row.reasoning > 0) add(mi.reportModelIcon to row.reasoning)
                if (row.cooling > 0) add(mi.snowflake to row.cooling)
                if (row.blocked > 0) add(mi.noEntry to row.blocked)
            }
            if (signals.isNotEmpty() || !row.hasKey) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!row.hasKey) Text("no key", fontSize = 11.sp, color = AppColors.WarningAccent)
                    signals.forEach { (e, n) -> Text("$e $n", fontSize = 11.sp, color = AppColors.TextSecondary) }
                }
            }
            if (isExpanded) {
                HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.padding(vertical = 8.dp))
                KeyVal("Default model", row.defaultModel.ifBlank { "—" })
                KeyVal("Host", row.host.ifBlank { "—" })
                KeyVal("API key", if (row.hasKey) "yes" else "no", if (row.hasKey) AppColors.SuccessAccent else AppColors.WarningAccent)
                KeyVal("Concurrency cap", capLabel(row.concCap, NetworkSettings.maxConcurrentCallsPerProvider))
                KeyVal("Per-minute cap", capLabel(row.perMinCap, NetworkSettings.maxCallsPerProviderPerMinute))
                KeyVal("Catalog", row.cacheAgeMs?.let { fmtDuration(it) + " ago" } ?: "never fetched")
                if (row.modelsByType.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("By type", fontSize = 10.sp, color = AppColors.TextTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.modelsByType.forEach { (t, c) -> StatChip("•", t, c, AppColors.TextSecondary) }
                    }
                }
                if (row.blocked + row.inaccessible + row.testExcluded + row.cooling > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("States", fontSize = 10.sp, color = AppColors.TextTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (row.blocked > 0) StatChip("⛔", "Blocked", row.blocked, AppColors.DangerAccent)
                        if (row.inaccessible > 0) StatChip("🚫", "Inaccessible", row.inaccessible, AppColors.WarningAccent)
                        if (row.testExcluded > 0) StatChip("⏭️", "Excluded", row.testExcluded, AppColors.TextSecondary)
                        if (row.cooling > 0) StatChip("❄️", "Cooling", row.cooling, AppColors.WarningAccent)
                    }
                }
                if (row.testPassed + row.testFailed > 0) {
                    Spacer(Modifier.height(4.dp))
                    KeyVal("Last test", "${row.testPassed} passed · ${row.testFailed} failed",
                        if (row.testFailed > 0) AppColors.WarningAccent else AppColors.SuccessAccent)
                }
            }
        }
    }
}

/** Compact API-format tag. */
private fun formatTag(format: String): String = when (format) {
    "OPENAI_COMPATIBLE" -> "OpenAI"
    "ANTHROPIC" -> "Anthropic"
    "GOOGLE" -> "Google"
    else -> format
}

/** Per-provider cap: the override value + "(override)", or the inherited global. */
private fun capLabel(override: Int?, global: Int): String =
    if (override != null) "$override (override)" else "$global (default)"

/** Spend & usage — own screen (per-model getPricing). Reached from AI
 *  Statistics. Computes its breakdown only on open. */
@Composable
fun AiSpendUsageScreen(
    openRouterApiKey: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onOpenProvider: (String) -> Unit = {},
    /** Types tab row → the per-category usage detail screen. */
    onOpenType: (String) -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    /** 🐞 on a provider row → the API Traces scoped to that provider. */
    onNavigateToTraceProvider: (String) -> Unit = {},
    /** 🐞 on a type row → API Traces scoped to that Trace/Costs category. */
    onNavigateToTraceCategory: (String) -> Unit = {},
    /** Report row → selected report's Costs section. */
    onOpenReportCosts: (String) -> Unit = {},
    onHousekeeping: (() -> Unit)? = null,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }

    val refreshTick = resumeRefreshTick()
    var reloadTick by remember { mutableStateOf(0) }
    // One-time OpenRouter pricing refresh so usage costs resolve, then recompute.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (openRouterApiKey.isNotBlank() && PricingCache.needsOpenRouterRefresh(context)) {
                val p = PricingCache.fetchOpenRouterPricing(openRouterApiKey)
                if (p.isNotEmpty()) PricingCache.saveOpenRouterPricing(context, p)
            }
        }
        reloadTick++
    }
    val data by produceState<UsageGroupsResult?>(null, refreshTick, reloadTick) {
        value = computeUsageGroups(context, settingsPrefs)
    }
    var confirmClear by remember { mutableStateOf(false) }
    // Column sort — tap a header to sort by it; tap again to flip
    // direction. Default: cost, descending (the most-asked question).
    var sortCol by rememberSaveable { mutableStateOf(UsageSort.COST) }
    var sortAsc by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(SpendUsageMode.PROVIDERS) }
    // Providers (AppService ids) that have at least one captured trace, so a
    // row only shows its 🐞 when there's something to open. Off the main
    // thread; getTraceFiles is cached after the first parse.
    val tracedProviders by produceState(emptySet<String>(), refreshTick) {
        value = withContext(Dispatchers.IO) { ApiTracer.getTraceFiles().map { providerLabelForHost(it.hostname) }.toSet() }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_spend_usage",
            title = "Spend & usage",
            subject = "Calls, tokens and cost by provider, type, report and model",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics,
            onDelete = { confirmClear = true },
            onHousekeeping = onHousekeeping
        )

        Spacer(Modifier.height(8.dp))
        SpendUsageModeSelector(mode = mode, onPick = { mode = it })

        val d = data
        when {
            d == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            else -> {
                Spacer(Modifier.height(8.dp))
                when (mode) {
                    SpendUsageMode.PROVIDERS -> SpendUsageProvidersTab(
                        data = d,
                        sortCol = sortCol,
                        sortAsc = sortAsc,
                        onSort = { col ->
                            if (sortCol == col) sortAsc = !sortAsc else { sortCol = col; sortAsc = false }
                        },
                        tracedProviders = tracedProviders,
                        onOpenProvider = onOpenProvider,
                        onNavigateToTraceProvider = onNavigateToTraceProvider,
                    )
                    SpendUsageMode.TYPES -> SpendUsageTypesTab(
                        data = d,
                        sortCol = sortCol,
                        sortAsc = sortAsc,
                        onSort = { col ->
                            if (sortCol == col) sortAsc = !sortAsc else { sortCol = col; sortAsc = false }
                        },
                        onOpenType = onOpenType,
                    )
                    SpendUsageMode.REPORTS -> SpendUsageReportsTab(
                        data = d,
                        sortCol = sortCol,
                        sortAsc = sortAsc,
                        onSort = { col ->
                            if (sortCol == col) sortAsc = !sortAsc else { sortCol = col; sortAsc = false }
                        },
                        onOpenReportCosts = onOpenReportCosts,
                    )
                    SpendUsageMode.MODELS -> SpendUsageModelsTab(
                        data = d,
                        sortCol = sortCol,
                        sortAsc = sortAsc,
                        onSort = { col ->
                            if (sortCol == col) sortAsc = !sortAsc else { sortCol = col; sortAsc = false }
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all statistics?") },
            text = { Text("Resets every usage counter back to zero. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    settingsPrefs.clearUsageStats()
                    reloadTick++
                    Toast.makeText(context, "Statistics cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

@Composable
private fun SpendUsageModeSelector(
    mode: SpendUsageMode,
    onPick: (SpendUsageMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SpendUsageMode.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onPick(entry) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = SpendUsageMode.entries.size)
            ) { Text(entry.label, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun SpendUsageSummary(calls: Int, tokens: Long, cost: Double) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Total: $calls calls, ${formatCompactNumber(tokens)} tokens", fontSize = 13.sp, color = Color.White)
            Text("Cost: ${cents(cost)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.SuccessAccent)
        }
    }
}

@Composable
private fun SpendUsageHeaderCell(
    label: String,
    col: UsageSort,
    width: Dp,
    alignEnd: Boolean,
    sortCol: UsageSort,
    sortAsc: Boolean,
    onSort: (UsageSort) -> Unit,
) {
    val arrow = if (sortCol == col) (if (sortAsc) " ▲" else " ▼") else ""
    Text(
        label + arrow,
        fontSize = 10.sp,
        color = if (sortCol == col) AppColors.TextSecondary else AppColors.TextTertiary,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        modifier = Modifier.width(width).clickable { onSort(col) }
    )
}

@Composable
private fun ColumnScope.SpendUsageProvidersTab(
    data: UsageGroupsResult,
    sortCol: UsageSort,
    sortAsc: Boolean,
    onSort: (UsageSort) -> Unit,
    tracedProviders: Set<String>,
    onOpenProvider: (String) -> Unit,
    onNavigateToTraceProvider: (String) -> Unit,
) {
    if (data.groups.isEmpty()) {
        Text(
            "No usage data yet. Generate reports or chat to see spend.",
            color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
        )
        return
    }
    SpendUsageSummary(data.totalCalls, data.totalTokens, data.totalCost)
    Spacer(Modifier.height(8.dp))

    val cName = 150.dp; val cCalls = 56.dp; val cTok = 78.dp
    val cGap = 24.dp; val cCost = 92.dp; val cBugGap = 12.dp; val cBug = 28.dp
    val tableWidth = cName + cCalls + cTok + cGap + cCost + cBugGap + cBug
    val rows = remember(data.groups, sortCol, sortAsc) {
        val withTokens = data.groups.map { it to it.models.sumOf { m -> m.stat.totalTokens } }
        val cmp: Comparator<Pair<ProviderCostGroup, Long>> = when (sortCol) {
            UsageSort.PROVIDER -> compareBy { it.first.provider.id.lowercase() }
            UsageSort.CALLS -> compareBy { it.first.totalCalls }
            UsageSort.TOKENS -> compareBy { it.second }
            UsageSort.COST -> compareBy { it.first.totalCost }
        }
        withTokens.sortedWith(if (sortAsc) cmp else cmp.reversed())
    }

    Column(modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f).verticalScroll(rememberScrollState())) {
        SpendUsageTableHeader("Provider", cName, cCalls, cTok, cGap, cCost, cBugGap, cBug, sortCol, sortAsc, onSort)
        rows.forEach { (group, tokens) ->
            val hasTrace = group.provider.id in tracedProviders
            Row(
                modifier = Modifier.clickable { onOpenProvider(group.provider.id) }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(group.provider.id, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cName))
                Text("${group.totalCalls}", fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cCalls))
                Text(formatCompactNumber(tokens), fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cTok))
                Spacer(Modifier.width(cGap))
                Text(cents(group.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent, textAlign = TextAlign.End, modifier = Modifier.width(cCost))
                Spacer(Modifier.width(cBugGap))
                Box(Modifier.width(cBug), contentAlignment = Alignment.Center) {
                    if (hasTrace) Text(com.ai.ui.shared.LocalMetadataIcons.current.traces, fontSize = 13.sp, modifier = Modifier.clickable { onNavigateToTraceProvider(group.provider.id) })
                }
            }
            HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.width(tableWidth))
        }
        Spacer(Modifier.height(24.dp))
    }
}

private data class TypeCategoryRow(
    val category: String,
    val calls: Int,
    val tokens: Long,
    val searchUnits: Long,
    val totalCost: Double,
    val hasTrace: Boolean,
)

@Composable
private fun ColumnScope.SpendUsageTypesTab(
    data: UsageGroupsResult,
    sortCol: UsageSort,
    sortAsc: Boolean,
    onSort: (UsageSort) -> Unit,
    onOpenType: (String) -> Unit,
) {
    if (data.typeGroups.isEmpty()) {
        Text(
            "No usage types yet. Generate reports or chat to see spend.",
            color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
        )
        return
    }
    SpendUsageSummary(data.totalCalls, data.totalTokens, data.totalCost)
    Spacer(Modifier.height(8.dp))

    val cName = 184.dp; val cCalls = 56.dp; val cTok = 78.dp
    val cGap = 18.dp; val cCost = 92.dp; val cBugGap = 12.dp; val cBug = 28.dp
    val tableWidth = cName + cCalls + cTok + cGap + cCost + cBugGap + cBug
    // Group every category on the part before its first '/', so
    // "translate/foo" and "translate/bar" collapse into one "translate"
    // row. Tapping a group opens the per-group entry list. The grouped
    // name carries no single trace category, so the 🐞 lives one level
    // deeper on the per-entry list.
    val rows = remember(data.typeGroups, sortCol, sortAsc) {
        val grouped = data.typeGroups.groupBy { it.category.substringBefore("/") }
            .map { (prefix, gs) ->
                TypeCategoryRow(
                    category = prefix,
                    calls = gs.sumOf { it.calls },
                    tokens = gs.sumOf { it.tokens },
                    searchUnits = gs.sumOf { it.searchUnits },
                    totalCost = gs.sumOf { it.totalCost },
                    hasTrace = false,
                )
            }
        val cmp: Comparator<TypeCategoryRow> = when (sortCol) {
            UsageSort.PROVIDER -> compareBy { it.category.lowercase() }
            UsageSort.CALLS -> compareBy { it.calls }
            UsageSort.TOKENS -> compareBy { it.tokens + it.searchUnits }
            UsageSort.COST -> compareBy { it.totalCost }
        }
        grouped.sortedWith(if (sortAsc) cmp else cmp.reversed())
    }

    Column(modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f).verticalScroll(rememberScrollState())) {
        SpendUsageTableHeader("Type", cName, cCalls, cTok, cGap, cCost, cBugGap, cBug, sortCol, sortAsc, onSort)
        rows.forEach { row ->
            Row(
                modifier = Modifier.clickable { onOpenType(row.category) }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(row.category, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cName))
                Text("${row.calls}", fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cCalls))
                Text(
                    if (row.searchUnits > 0 && row.tokens == 0L) "${formatCompactNumber(row.searchUnits)} su" else formatCompactNumber(row.tokens),
                    fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cTok)
                )
                Spacer(Modifier.width(cGap))
                Text(cents(row.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent, textAlign = TextAlign.End, modifier = Modifier.width(cCost))
                Spacer(Modifier.width(cBugGap))
                Box(Modifier.width(cBug), contentAlignment = Alignment.Center) { Text("▸", color = AppColors.TextTertiary, fontSize = 13.sp) }
            }
            HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.width(tableWidth))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ColumnScope.SpendUsageReportsTab(
    data: UsageGroupsResult,
    sortCol: UsageSort,
    sortAsc: Boolean,
    onSort: (UsageSort) -> Unit,
    onOpenReportCosts: (String) -> Unit,
) {
    if (data.reportRows.isEmpty()) {
        Text(
            "No saved reports yet.",
            color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
        )
        return
    }
    val totalCalls = data.reportRows.sumOf { it.calls }
    val totalTokens = data.reportRows.sumOf { it.tokens }
    val totalCost = data.reportRows.sumOf { it.totalCost }
    SpendUsageSummary(totalCalls, totalTokens, totalCost)
    Spacer(Modifier.height(8.dp))

    val cName = 184.dp; val cCalls = 56.dp; val cTok = 78.dp
    val cGap = 18.dp; val cCost = 92.dp; val cBugGap = 12.dp; val cBug = 28.dp
    val tableWidth = cName + cCalls + cTok + cGap + cCost + cBugGap + cBug
    val rows = remember(data.reportRows, sortCol, sortAsc) {
        val cmp: Comparator<UsageReportRow> = when (sortCol) {
            UsageSort.PROVIDER -> compareBy { it.title.lowercase() }
            UsageSort.CALLS -> compareBy { it.calls }
            UsageSort.TOKENS -> compareBy { it.tokens }
            UsageSort.COST -> compareBy { it.totalCost }
        }
        data.reportRows.sortedWith(if (sortAsc) cmp else cmp.reversed())
    }

    Column(modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f).verticalScroll(rememberScrollState())) {
        SpendUsageTableHeader("Report", cName, cCalls, cTok, cGap, cCost, cBugGap, cBug, sortCol, sortAsc, onSort)
        rows.forEach { row ->
            Row(
                modifier = Modifier.clickable { onOpenReportCosts(row.reportId) }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(row.title, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cName))
                Text("${row.calls}", fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cCalls))
                Text(formatCompactNumber(row.tokens), fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cTok))
                Spacer(Modifier.width(cGap))
                Text(cents(row.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent, textAlign = TextAlign.End, modifier = Modifier.width(cCost))
                Spacer(Modifier.width(cBugGap + cBug))
            }
            HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.width(tableWidth))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ColumnScope.SpendUsageModelsTab(
    data: UsageGroupsResult,
    sortCol: UsageSort,
    sortAsc: Boolean,
    onSort: (UsageSort) -> Unit,
) {
    // Flatten every provider's per-model stat rows and re-aggregate by
    // model id, so the same model served by two providers shows as one
    // line. Costs/tokens/calls are summed.
    val modelRows = remember(data.groups) {
        data.groups.flatMap { it.models }
            .groupBy { it.stat.model }
            .map { (model, rows) ->
                ModelUsageRow(
                    model = model,
                    calls = rows.sumOf { it.stat.callCount },
                    tokens = rows.sumOf { it.stat.totalTokens },
                    totalCost = rows.sumOf { it.totalCost },
                )
            }
    }
    if (modelRows.isEmpty()) {
        Text(
            "No usage data yet. Generate reports or chat to see spend.",
            color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
        )
        return
    }
    SpendUsageSummary(
        modelRows.sumOf { it.calls },
        modelRows.sumOf { it.tokens },
        modelRows.sumOf { it.totalCost }
    )
    Spacer(Modifier.height(8.dp))

    val cName = 184.dp; val cCalls = 56.dp; val cTok = 78.dp
    val cGap = 18.dp; val cCost = 92.dp; val cBugGap = 12.dp; val cBug = 28.dp
    val tableWidth = cName + cCalls + cTok + cGap + cCost + cBugGap + cBug
    val rows = remember(modelRows, sortCol, sortAsc) {
        val cmp: Comparator<ModelUsageRow> = when (sortCol) {
            UsageSort.PROVIDER -> compareBy { it.model.lowercase() }
            UsageSort.CALLS -> compareBy { it.calls }
            UsageSort.TOKENS -> compareBy { it.tokens }
            UsageSort.COST -> compareBy { it.totalCost }
        }
        modelRows.sortedWith(if (sortAsc) cmp else cmp.reversed())
    }

    Column(modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f).verticalScroll(rememberScrollState())) {
        SpendUsageTableHeader("Model", cName, cCalls, cTok, cGap, cCost, cBugGap, cBug, sortCol, sortAsc, onSort)
        rows.forEach { row ->
            Row(
                modifier = Modifier.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(row.model, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cName))
                Text("${row.calls}", fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cCalls))
                Text(formatCompactNumber(row.tokens), fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cTok))
                Spacer(Modifier.width(cGap))
                Text(cents(row.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent, textAlign = TextAlign.End, modifier = Modifier.width(cCost))
                Spacer(Modifier.width(cBugGap + cBug))
            }
            HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.width(tableWidth))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SpendUsageTableHeader(
    nameLabel: String,
    cName: Dp,
    cCalls: Dp,
    cTok: Dp,
    cGap: Dp,
    cCost: Dp,
    cBugGap: Dp,
    cBug: Dp,
    sortCol: UsageSort,
    sortAsc: Boolean,
    onSort: (UsageSort) -> Unit,
) {
    Row(Modifier.padding(vertical = 4.dp)) {
        SpendUsageHeaderCell(nameLabel, UsageSort.PROVIDER, cName, alignEnd = false, sortCol, sortAsc, onSort)
        SpendUsageHeaderCell("Calls", UsageSort.CALLS, cCalls, alignEnd = true, sortCol, sortAsc, onSort)
        SpendUsageHeaderCell("Tokens", UsageSort.TOKENS, cTok, alignEnd = true, sortCol, sortAsc, onSort)
        Spacer(Modifier.width(cGap))
        SpendUsageHeaderCell("Cost", UsageSort.COST, cCost, alignEnd = true, sortCol, sortAsc, onSort)
        Spacer(Modifier.width(cBugGap))
        Spacer(Modifier.width(cBug))
    }
}

/** Per-provider usage detail — opened by tapping a row on Spend & usage.
 *  By type / by pricing source / by model, all for one provider. */
@Composable
fun AiSpendUsageProviderScreen(
    providerId: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToStatistics: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }
    val refreshTick = resumeRefreshTick()
    val group by produceState<ProviderCostGroup?>(null, refreshTick, providerId) {
        value = computeUsageGroups(context, settingsPrefs).groups.firstOrNull { it.provider.id == providerId }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_usage_provider",
            title = providerId,
            subject = "Usage detail",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics
        )
        val g = group
        if (g == null) {
            Text("Loading… (no usage = nothing here)", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            val tokens = g.models.sumOf { it.stat.totalTokens }
            // Group by call kind and by pricing source.
            val byKind = g.models.groupBy { it.stat.kind }
            val bySource = g.models.groupingBy { it.pricingSource }.eachCount()
            val sortedModels = g.models.sortedByDescending { it.totalCost }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionCard("💰", "Totals", AppColors.SuccessAccent) {
                        KeyVal("Calls", "${g.totalCalls}")
                        KeyVal("Tokens", formatCompactNumber(tokens))
                        KeyVal("Cost", cents(g.totalCost), AppColors.SuccessAccent)
                        val avg = if (g.totalCalls > 0) g.totalCost / g.totalCalls else 0.0
                        KeyVal("Avg / call", cents(avg), AppColors.TextSecondary)
                        KeyVal("Distinct models", "${g.models.size}")
                    }
                }
                item {
                    SectionCard("🏷️", "By type", AppColors.SecondaryAccent) {
                        byKind.entries.sortedByDescending { e -> e.value.sumOf { it.totalCost } }.forEach { (kind, rows) ->
                            val calls = rows.sumOf { it.stat.callCount }
                            val cost = rows.sumOf { it.totalCost }
                            KeyVal(kindLabel(kind), "$calls calls · ${cents(cost)}")
                        }
                    }
                }
                item {
                    SectionCard("📐", "By pricing source", AppColors.PrimaryAccent) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            bySource.entries.sortedByDescending { it.value }.forEach { (src, count) ->
                                StatChip("•", tierLabel(src), count, AppColors.TextSecondary)
                            }
                        }
                    }
                }
                item {
                    Text("By model — tap for Model Info", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                }
                items(sortedModels, key = { it.stat.model + "|" + it.stat.kind }) { swc ->
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onNavigateToModelInfo(g.provider, swc.stat.model) }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(com.ai.ui.shared.shortModelName(swc.stat.model), fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (swc.stat.kind != "report") {
                                Text(swc.stat.kind, fontSize = 9.sp, color = AppColors.TextSecondary,
                                    modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(4.dp)).background(AppColors.SurfaceDark).padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            Text(cents(swc.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent)
                        }
                        Text(
                            "${swc.stat.callCount} calls · ${formatCompactNumber(swc.stat.totalTokens)} tokens · ${tierLabel(swc.pricingSource)}",
                            fontSize = 11.sp, color = AppColors.TextTertiary
                        )
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** One usage entry inside a type-category detail: a (provider, model,
 *  kind) row paired with its provider for the Model-Info tap. */
private data class TypeEntry(val provider: AppService, val swc: StatWithCost)

/** Per-group entry list — opened by tapping a grouped row on the
 *  Spend & usage Types tab. Lists every full category whose part before
 *  the first '/' equals [groupPrefix] (e.g. group "translate" →
 *  "translate", "translate/summary", …). Tapping a row opens the
 *  per-category usage detail; the 🐞 (when a trace exists for that exact
 *  category) opens its scoped API Traces. */
@Composable
fun AiSpendUsageTypeGroupScreen(
    groupPrefix: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onOpenType: (String) -> Unit = {},
    onNavigateToTraceCategory: (String) -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }
    val refreshTick = resumeRefreshTick()
    val tracedCategories by produceState(emptySet<String>(), refreshTick) {
        value = withContext(Dispatchers.IO) { ApiTracer.getTraceFiles().mapNotNull { it.category }.toSet() }
    }
    val rows by produceState<List<TypeCategoryRow>?>(null, refreshTick, groupPrefix, tracedCategories) {
        val typeGroups = computeUsageGroups(context, settingsPrefs).typeGroups
        value = typeGroups
            .filter { it.category.substringBefore("/") == groupPrefix }
            .map { g ->
                TypeCategoryRow(g.category, g.calls, g.tokens, g.searchUnits, g.totalCost, g.category in tracedCategories)
            }
            .sortedByDescending { it.totalCost }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_usage_type",
            title = groupPrefix,
            subject = "Entries in this type group",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics
        )
        val list = rows
        when {
            list == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            list.isEmpty() -> Text("No usage for this group.", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            else -> {
                Spacer(Modifier.height(8.dp))
                SpendUsageSummary(list.sumOf { it.calls }, list.sumOf { it.tokens }, list.sumOf { it.totalCost })
                Spacer(Modifier.height(8.dp))
                val cName = 184.dp; val cCalls = 56.dp; val cTok = 78.dp
                val cGap = 18.dp; val cCost = 92.dp; val cBugGap = 12.dp; val cBug = 28.dp
                val tableWidth = cName + cCalls + cTok + cGap + cCost + cBugGap + cBug
                Column(modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f).verticalScroll(rememberScrollState())) {
                    list.forEach { row ->
                        Row(
                            modifier = Modifier.clickable { onOpenType(row.category) }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(row.category, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cName))
                            Text("${row.calls}", fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cCalls))
                            Text(
                                if (row.searchUnits > 0 && row.tokens == 0L) "${formatCompactNumber(row.searchUnits)} su" else formatCompactNumber(row.tokens),
                                fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cTok)
                            )
                            Spacer(Modifier.width(cGap))
                            Text(cents(row.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent, textAlign = TextAlign.End, modifier = Modifier.width(cCost))
                            Spacer(Modifier.width(cBugGap))
                            Box(Modifier.width(cBug), contentAlignment = Alignment.Center) {
                                if (row.hasTrace) Text(com.ai.ui.shared.LocalMetadataIcons.current.traces, fontSize = 13.sp, modifier = Modifier.clickable { onNavigateToTraceCategory(row.category) })
                            }
                        }
                        HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.width(tableWidth))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Per-category usage detail — opened by tapping a row on the
 *  Spend & usage Types tab. Lists every (provider, model, kind) entry
 *  whose kind matches [typePrefix]. */
@Composable
fun AiSpendUsageTypeScreen(
    typePrefix: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    onNavigateToStatistics: () -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    val settingsPrefs = remember { SettingsPreferences(prefs, context.filesDir) }
    val refreshTick = resumeRefreshTick()
    val entries by produceState<List<TypeEntry>?>(null, refreshTick, typePrefix) {
        val groups = computeUsageGroups(context, settingsPrefs).groups
        value = groups.flatMap { g -> g.models.map { TypeEntry(g.provider, it) } }
            .filter { it.swc.stat.kind == typePrefix }
            .sortedByDescending { it.swc.totalCost }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_usage_type",
            title = typePrefix,
            subject = "Usage detail by entry",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics
        )
        val list = entries
        when {
            list == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            list.isEmpty() -> Text("No usage for this type.", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            else -> {
                val totalCalls = list.sumOf { it.swc.stat.callCount }
                val totalTokens = list.sumOf { it.swc.stat.totalTokens }
                val totalCost = list.sumOf { it.swc.totalCost }
                val byKind = list.groupBy { it.swc.stat.kind }
                val byProvider = list.groupBy { it.provider.id }

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Spacer(Modifier.height(4.dp)) }
                    item {
                        SectionCard("💰", "Totals", AppColors.SuccessAccent) {
                            KeyVal("Calls", "$totalCalls")
                            KeyVal("Tokens", formatCompactNumber(totalTokens))
                            KeyVal("Cost", cents(totalCost), AppColors.SuccessAccent)
                            val avg = if (totalCalls > 0) totalCost / totalCalls else 0.0
                            KeyVal("Avg / call", cents(avg), AppColors.TextSecondary)
                            KeyVal("Entries", "${list.size}")
                        }
                    }
                    if (byKind.size > 1) {
                        item {
                            SectionCard("🏷️", "By type", AppColors.SecondaryAccent) {
                                byKind.entries.sortedByDescending { e -> e.value.sumOf { it.swc.totalCost } }.forEach { (kind, rows) ->
                                    val calls = rows.sumOf { it.swc.stat.callCount }
                                    val cost = rows.sumOf { it.swc.totalCost }
                                    KeyVal(kind, "$calls calls · ${cents(cost)}")
                                }
                            }
                        }
                    }
                    item {
                        SectionCard("🏢", "By provider", AppColors.PrimaryAccent) {
                            byProvider.entries.sortedByDescending { e -> e.value.sumOf { it.swc.totalCost } }.forEach { (pid, rows) ->
                                val calls = rows.sumOf { it.swc.stat.callCount }
                                val cost = rows.sumOf { it.swc.totalCost }
                                KeyVal(pid, "$calls calls · ${cents(cost)}")
                            }
                        }
                    }
                    item {
                        Text("Entries — tap for Model Info", fontSize = 12.sp, color = AppColors.TextTertiary, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
                    }
                    items(list, key = { it.provider.id + "|" + it.swc.stat.model + "|" + it.swc.stat.kind }) { e ->
                        val swc = e.swc
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onNavigateToModelInfo(e.provider, swc.stat.model) }
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(com.ai.ui.shared.shortModelName(swc.stat.model), fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (swc.stat.kind != typePrefix) {
                                    Text(swc.stat.kind, fontSize = 9.sp, color = AppColors.TextSecondary,
                                        modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(4.dp)).background(AppColors.SurfaceDark).padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                                Text(cents(swc.totalCost), fontSize = 13.sp, color = AppColors.SuccessAccent)
                            }
                            val units = if (swc.stat.searchUnits > 0 && swc.stat.totalTokens == 0L) "${formatCompactNumber(swc.stat.searchUnits)} su" else "${formatCompactNumber(swc.stat.totalTokens)} tokens"
                            Text(
                                "${e.provider.id} · ${swc.stat.callCount} calls · $units · ${tierLabel(swc.pricingSource)}",
                                fontSize = 11.sp, color = AppColors.TextTertiary
                            )
                        }
                        HorizontalDivider(color = AppColors.DividerDark)
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

/** Display label for a usage call-kind. */
private fun kindLabel(kind: String): String = when (kind) {
    "report" -> "Report"
    "rerank" -> "Rerank"
    "summarize" -> "Summarize"
    "compare" -> "Compare"
    "moderation" -> "Moderation"
    "translate" -> "Translate"
    "title" -> "Title"
    SETTINGS_ICONS_CALL_KIND -> "Settings · Icons"
    MODEL_TEMPERATURE_CALL_KIND -> MODEL_TEMPERATURE_CALL_KIND
    MODEL_REASONING_CALL_KIND -> MODEL_REASONING_CALL_KIND
    MODEL_WEB_SEARCH_CALL_KIND -> MODEL_WEB_SEARCH_CALL_KIND
    // Per-kind translation types (translate/<what>): render as
    // "Translate · <what>" with separators spaced out.
    else -> if (kind.startsWith("translate/"))
        "Translate · " + kind.removePrefix("translate/").replace('/', ' ').replace('_', ' ')
    else kind.replaceFirstChar { it.uppercase() }
}

/** Costs tier — own screen (per-model getPricing for the whole catalog).
 *  Reached from the Monitor hub; computes only on open. */
@Composable
fun AiCostsTierScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToStatistics: () -> Unit = {},
    /** 🐞 on a Pricing-cache row → the API Traces filtered to that
     *  source's "pricing/<source>" retrieve category. */
    onNavigateToTraceCategory: (String) -> Unit = {},
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val uiState by appViewModel.uiState.collectAsState()
    val refreshTick = resumeRefreshTick()
    // Two columns side by side: Config = every configured model; Runtime =
    // only the models actually called (read from the API traces).
    val tierData by produceState<Pair<Map<String, Int>, Map<String, Int>>?>(null, refreshTick) {
        val config = computeTierCounts(context, uiState.aiSettings)
        val runtime = computeTierCountsRuntime(context)
        value = config to runtime
    }
    val pricing by produceState<List<PricingCache.CatalogStat>?>(null, refreshTick) {
        value = withContext(Dispatchers.IO) { PricingCache.catalogStats(context) }
    }
    // Trace categories actually present, so a Pricing-cache row only shows
    // its 🐞 when that source's retrieve was captured. Off the main thread
    // (getTraceFiles is cached after the first streaming parse).
    val tracedCategories by produceState(emptySet<String>(), refreshTick) {
        value = withContext(Dispatchers.IO) { ApiTracer.getTraceFiles().mapNotNull { it.category }.toSet() }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_costs_tier",
            title = "Costs tiers",
            subject = "Pricing tier per model + catalog freshness",
            onBackClick = onBack,
            reportIcon = com.ai.ui.shared.LocalMetadataIcons.current.statistics,
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                val td = tierData
                if (td == null) {
                    Text(
                        "Loading… (checking every configured model + reading API traces)",
                        color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
                    )
                } else {
                    CostTierSection(config = td.first, runtime = td.second)
                }
            }
            pricing?.let { cats -> item { PricingSection(cats, tracedCategories, onNavigateToTraceCategory) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// =====================================================================
// Live sections
// =====================================================================

/** A 750 ms ticker scoped to this composable's lifetime — a collapsed card's
 *  body isn't composed, so its ticker never starts and it does no work. */
@Composable
private fun rememberLiveTick(periodMs: Long = 750): Int {
    val tick by produceState(0) { while (true) { delay(periodMs); value++ } }
    return tick
}

/** A card whose body is composed (and so fetches + ticks its data) only while
 *  expanded. Tapping the header toggles it; the body does zero work while
 *  collapsed. */
@Composable
private fun CollapsibleCard(
    id: String, emoji: String, title: String, accent: Color,
    open: Set<String>, pinned: Set<String>,
    isFirst: Boolean, isLast: Boolean,
    onToggle: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isOpen = id in open
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // emoji + title = the expand tap target.
                Row(
                    modifier = Modifier.weight(1f).clickable { onToggle(id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mi.forFactoryGlyph(emoji), fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Reorder ↑ ↓ (dimmed at the ends), 📌 pin (open on load), expand ▾/▸.
                Text(mi.arrowUp, fontSize = 15.sp, color = if (isFirst) AppColors.TextDim else AppColors.TextSecondary,
                    modifier = Modifier.clickable(enabled = !isFirst) { onMoveUp(id) }.padding(horizontal = 5.dp))
                Text(mi.arrowDown, fontSize = 15.sp, color = if (isLast) AppColors.TextDim else AppColors.TextSecondary,
                    modifier = Modifier.clickable(enabled = !isLast) { onMoveDown(id) }.padding(horizontal = 5.dp))
                Text(mi.pin, fontSize = 13.sp,
                    modifier = Modifier.alpha(if (id in pinned) 1f else 0.3f).clickable { onTogglePin(id) }.padding(horizontal = 5.dp))
                Text(if (isOpen) mi.caretExpanded else mi.caretCollapsed, fontSize = 15.sp, color = AppColors.TextTertiary,
                    modifier = Modifier.clickable { onToggle(id) }.padding(start = 4.dp))
            }
            if (isOpen) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

/** Card identity (emoji / title / header accent), keyed by stable id. The map
 *  iteration order is the default card order; [reconcileDashboardOrder] folds
 *  the user's saved order over it. */
private data class CardMeta(val emoji: String, val title: String, val accent: Color)
private val DASH_CARD_META: Map<String, CardMeta> = linkedMapOf(
    "live" to CardMeta("🟢", "Live activity", AppColors.SuccessAccent),
    "active" to CardMeta("🏃", "Active runs", AppColors.SuccessAccent),
    "spend" to CardMeta("💸", "Spend & tokens", AppColors.SuccessAccent),
    "http" to CardMeta("📊", "HTTP responses", AppColors.SecondaryAccent),
    "errors" to CardMeta("⚠️", "Recent errors", AppColors.DangerAccent),
    "times" to CardMeta("⏱️", "Response times", AppColors.SecondaryAccent),
    "slow" to CardMeta("🐌", "Slowest calls", AppColors.WarningAccent),
    "throttle" to CardMeta("🌐", "Provider throttle", AppColors.InfoAccent),
    "cooldowns" to CardMeta("❄️", "Model cooldowns", AppColors.WarningAccent),
    "test" to CardMeta("🧪", "Test all models", AppColors.PrimaryAccent),
    "stress" to CardMeta("🔥", "Stress test", AppColors.DangerAccent),
    "local" to CardMeta("🧠", "Local runtime", AppColors.PrimaryAccent),
    "health" to CardMeta("🩺", "System health", AppColors.SuccessAccent),
)
private val DEFAULT_DASH_ORDER: List<String> = DASH_CARD_META.keys.toList()

/** The saved order honoured, unknown ids dropped, and any card not in the saved
 *  order appended in default position (so a newly-added card still appears). */
private fun reconcileDashboardOrder(saved: List<String>): List<String> {
    val known = saved.filter { it in DASH_CARD_META }
    return known + DEFAULT_DASH_ORDER.filter { it !in known }
}

/** Dispatch a card id to its body composable (composed only while expanded). */
@Composable
private fun DashCardBody(
    id: String, appViewModel: AppViewModel, reportViewModel: ReportViewModel,
    context: android.content.Context, onOpenTraceFilter: (String, String) -> Unit,
) {
    when (id) {
        "live" -> LiveActivityBody(appViewModel)
        "active" -> ActiveRunsBody(appViewModel, reportViewModel)
        "spend" -> SpendTokensBody(context)
        "http" -> HttpCodesBody(onOpenTraceFilter)
        "errors" -> RecentErrorsBody()
        "times" -> ResponseTimesBody()
        "slow" -> SlowestCallsBody()
        "throttle" -> ThrottleBody(appViewModel, reportViewModel, onOpenTraceFilter)
        "cooldowns" -> CooldownBody()
        "test" -> TestRunBody(reportViewModel, context)
        "stress" -> StressTestBody(reportViewModel)
        "local" -> LocalRuntimeBody(context)
        "health" -> HealthBody(context)
    }
}

/** Live activity body — global calls-in-flight hero + per-feature cap bars +
 *  throttled chips. Owns its own ticker (like every other card body now). */
@Composable
private fun LiveActivityBody(appViewModel: AppViewModel) {
    val tick = rememberLiveTick()
    val caps = remember(tick) { ApiCallCaps.snapshot() }
    val thrFanOut by appViewModel.throttledFanOutPairs.collectAsState()
    val thrMeta by appViewModel.throttledFanMetaPairs.collectAsState()
    val (statusWord, statusColor) = when {
        caps.globalInFlight == 0 -> "Idle" to AppColors.TextDim
        caps.globalInFlight >= caps.globalMax -> "Saturated" to AppColors.DangerAccent
        else -> "Active" to AppColors.SuccessAccent
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${caps.globalInFlight}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = statusColor)
        Spacer(Modifier.width(6.dp))
        Text("/ ${caps.globalMax} calls in flight", fontSize = 13.sp, color = AppColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            statusWord, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = statusColor,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceDark)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
    CapBar("Global", caps.globalInFlight, caps.globalMax)
    if (caps.reportInFlight > 0) CapBar("Report", caps.reportInFlight, caps.reportMax)
    if (caps.translationInFlight > 0) CapBar("Translation", caps.translationInFlight, caps.translationMax)
    if (caps.fanOutInFlight > 0) CapBar("Fan-out", caps.fanOutInFlight, caps.fanOutMax)
    if (caps.fanMetaInFlight > 0) CapBar("Fan-meta", caps.fanMetaInFlight, caps.fanMetaMax)
    val throttled = thrFanOut.size + thrMeta.size
    if (throttled > 0) {
        Spacer(Modifier.height(8.dp))
        Text("Throttled — waiting on a provider rate-limit", fontSize = 11.sp, color = AppColors.WarningAccent)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (thrFanOut.isNotEmpty()) StatChip("🌫️", "Fan-out", thrFanOut.size, AppColors.WarningAccent)
            if (thrMeta.isNotEmpty()) StatChip("🪄", "Fan-meta", thrMeta.size, AppColors.WarningAccent)
        }
    }
}

/** One active-runs row: a glyph + label and either `done/total` (+ bar) or
 *  `N running` when the total isn't known (fan-meta). */
private data class DashRun(
    val glyph: String, val label: String,
    val done: Int, val total: Int, val running: Int, val pending: Int,
)

/** What's actually running right now — translation / fan-out / regenerate /
 *  fan-meta batches by name, with progress and a parked-on-gate summary.
 *  Reactive (no ticker): updates straight off the run StateFlows. */
@Composable
private fun ActiveRunsBody(appViewModel: AppViewModel, reportViewModel: ReportViewModel) {
    val translationRuns by reportViewModel.translation.translationRuns.collectAsState()
    val fanOutRuns by reportViewModel.fanOutEngine.runs.collectAsState()
    val regenJobs by reportViewModel.regenerateBatchEngine.jobs.collectAsState()
    val runningFanMeta by appViewModel.runningFanMetaPairs.collectAsState()
    val thrFanOut by appViewModel.throttledFanOutPairs.collectAsState()
    val thrMeta by appViewModel.throttledFanMetaPairs.collectAsState()
    val thrTranslation by appViewModel.throttledTranslationItems.collectAsState()
    val thrTest by reportViewModel.modelTestEngine.throttledKeys.collectAsState()
    val parked = thrFanOut.size + thrMeta.size + thrTranslation.size + thrTest.size
    val runs = buildList {
        translationRuns.values.forEach { r ->
            if (!r.cancelled && !r.finished && r.completed < r.total) add(
                DashRun("🌐", "→ ${r.targetLanguageName}", r.completed, r.total,
                    r.items.count { it.status == com.ai.viewmodel.TranslationStatus.RUNNING },
                    r.items.count { it.status == com.ai.viewmodel.TranslationStatus.PENDING })
            )
        }
        fanOutRuns.values.forEach { r ->
            if (!r.cancelled && r.runningCount + r.queuedCount > 0) add(
                DashRun("🍱", r.metaPrompt.name, r.doneCount, r.totalPairs, r.runningCount, r.queuedCount)
            )
        }
        regenJobs.values.forEach { j ->
            if (j.status == com.ai.data.RegenerateJobStatus.RUNNING) add(
                DashRun("🔁", "Regenerate",
                    j.tasks.count { it.state == com.ai.data.RegenerateTaskState.SUCCESS }, j.tasks.size,
                    j.tasks.count { it.state == com.ai.data.RegenerateTaskState.RUNNING },
                    j.tasks.count { it.state == com.ai.data.RegenerateTaskState.WAITING })
            )
        }
        if (runningFanMeta.isNotEmpty()) add(DashRun("🪄", "Fan Meta", 0, 0, runningFanMeta.size, 0))
    }
    if (runs.isEmpty()) {
        Text("No batches running.", fontSize = 12.sp, color = AppColors.TextTertiary)
    } else {
        runs.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(com.ai.ui.shared.LocalMetadataIcons.current.forFactoryGlyph(r.glyph), fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                    Text(r.label, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        if (r.total > 0) "${r.done}/${r.total}" else "${r.running} running",
                        fontSize = 12.sp, color = AppColors.TextSecondary
                    )
                }
                if (r.total > 0) Bar(r.done.toFloat() / r.total, AppColors.SuccessAccent)
            }
        }
    }
    if (parked > 0) {
        Spacer(Modifier.height(4.dp))
        Text("Parked on a provider gate: $parked", fontSize = 11.sp, color = AppColors.WarningAccent)
    }
}

/** Trim a host to its last two dot-labels for a compact dashboard row —
 *  `generativelanguage.googleapis.com` → `googleapis.com`, `api.openai.com`
 *  → `openai.com`. Hosts with two or fewer labels (`openrouter.ai`) are
 *  returned unchanged. */
private fun twoLevelHost(host: String): String {
    val labels = host.split('.').filter { it.isNotBlank() }
    return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
}

@Composable
private fun ThrottleBody(
    appViewModel: AppViewModel, reportViewModel: ReportViewModel,
    onOpenTraceFilter: (String, String) -> Unit,
) {
    val tick = rememberLiveTick()
    val hosts = remember(tick) { ProviderThrottle.snapshot() }
    val retries1m = remember(tick) { RetryStats.retriesWithin(60_000) }
    val retryInFlight = remember(tick) { RetryStats.inFlightCount() }
    val thrFanOut by appViewModel.throttledFanOutPairs.collectAsState()
    val thrMeta by appViewModel.throttledFanMetaPairs.collectAsState()
    val thrTranslation by appViewModel.throttledTranslationItems.collectAsState()
    val thrTest by reportViewModel.modelTestEngine.throttledKeys.collectAsState()
    val parked = thrFanOut.size + thrMeta.size + thrTranslation.size + thrTest.size
    // Pressure summary: items parked on a gate, retry rate, and how many
    // calls are right now sleeping in a retry backoff.
    if (parked > 0 || retries1m > 0 || retryInFlight > 0) {
        Text(
            "Parked $parked · retries 1m $retries1m · backing off $retryInFlight",
            fontSize = 11.sp,
            color = if (retryInFlight > 0 || parked > 0) AppColors.WarningAccent else AppColors.TextSecondary
        )
        Spacer(Modifier.height(6.dp))
    }
    if (hosts.isEmpty()) {
        Text("Idle — no active hosts.", fontSize = 12.sp, color = AppColors.TextTertiary)
    } else {
        val windowCap = NetworkSettings.maxCallsPerProviderPerMinute
        hosts.forEach { h ->
            val concColor = when {
                h.free == 0 -> AppColors.DangerAccent
                h.inUse > 0 -> AppColors.WarningAccent
                else -> AppColors.SuccessAccent
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(twoLevelHost(h.host), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "con ${h.inUse}/${h.limit}  ·  min ${h.windowCount}/$windowCap",
                        fontSize = 11.sp, color = concColor
                    )
                    // 🐞 (trailing) → API Traces filtered to this host (full
                    // host, not the trimmed display label).
                    Spacer(Modifier.width(16.dp))
                    Text(com.ai.ui.shared.LocalMetadataIcons.current.traces, fontSize = 13.sp, modifier = Modifier.clickable { onOpenTraceFilter("host", h.host) })
                }
                Bar(if (h.limit > 0) h.inUse.toFloat() / h.limit else 0f, concColor)
            }
        }
    }
}

/** Compact token formatter: 1234 → "1.2k", 1_500_000 → "1.5M". */
private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1f M", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1f k", n / 1_000.0)
    else -> n.toString()
}

/** Rolling spend + token throughput over the same 1 min / 5 min windows as
 *  the other rate cards, from [ApiUsageRates]. The 1-minute column is the
 *  live per-minute rate; cost is priced through the pricing cache. */
@Composable
private fun SpendTokensBody(context: android.content.Context) {
    val tick = rememberLiveTick()
    val cost1 = remember(tick) { ApiUsageRates.costWithin(context, 60_000) }
    val cost5 = remember(tick) { ApiUsageRates.costWithin(context, 5 * 60_000) }
    val tok1 = remember(tick) { ApiUsageRates.tokensWithin(60_000) }
    val tok5 = remember(tick) { ApiUsageRates.tokensWithin(5 * 60_000) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text("1m", fontSize = 11.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        Text("5m", fontSize = 11.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
    }
    SpendRow("Spend", money(cost1), money(cost5), AppColors.SuccessAccent)
    SpendRow("Tokens in", fmtTokens(tok1.inTok), fmtTokens(tok5.inTok), Color.White)
    SpendRow("Tokens out", fmtTokens(tok1.outTok), fmtTokens(tok5.outTok), Color.White)
}

@Composable
private fun SpendRow(label: String, v1: String, v5: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(v1, fontSize = 12.sp, color = accent, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        Text(v5, fontSize = 12.sp, color = accent, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
    }
}

/** Rolling HTTP response-code tally over two trailing windows (1 min /
 *  5 min), fed by [HttpStatusStats]. 429 is split out from the 4xx family
 *  because it's the rate-limit signal the live view cares about most;
 *  network failures land in "other". */
@Composable
private fun HttpCodesBody(onOpenTraceFilter: (String, String) -> Unit) {
    val tick = rememberLiveTick()
    val min1 = remember(tick) { HttpStatusStats.countsWithin(60_000) }
    val min5 = remember(tick) { HttpStatusStats.countsWithin(5 * 60_000) }
    // Derived throughput + success-rate over the last minute.
    val total1 = min1.ok2xx + min1.r429 + min1.c4xx + min1.s5xx + min1.other
    val okPct = if (total1 > 0) 100 * min1.ok2xx / total1 else 0
    Text(
        if (total1 > 0) "$total1 calls/min · $okPct% ok" else "idle — no calls in the last minute",
        fontSize = 11.sp, color = if (total1 > 0 && okPct < 90) AppColors.WarningAccent else AppColors.TextSecondary
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text("1m", fontSize = 11.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        Text("5m", fontSize = 11.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        // Reserve the trailing 🐞 slot so the column headers line up.
        Spacer(Modifier.width(16.dp))
        Spacer(Modifier.width(22.dp))
    }
    HttpCodeRow("✅ 2xx", min1.ok2xx, min5.ok2xx, AppColors.SuccessAccent) { onOpenTraceFilter("status", "2xx") }
    HttpCodeRow("🚧 429", min1.r429, min5.r429, AppColors.WarningAccent) { onOpenTraceFilter("status", "429") }
    HttpCodeRow("⚠️ 4xx", min1.c4xx, min5.c4xx, AppColors.WarningAccent) { onOpenTraceFilter("status", "4xx") }
    HttpCodeRow("🔥 5xx", min1.s5xx, min5.s5xx, AppColors.DangerAccent) { onOpenTraceFilter("status", "5xx") }
    HttpCodeRow("▫️ other", min1.other, min5.other, AppColors.TextDim) { onOpenTraceFilter("status", "other") }
}

@Composable
private fun HttpCodeRow(label: String, c1: Int, c5: Int, accent: Color, onTrace: () -> Unit) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(mi.iconizedText(label), fontSize = 12.sp, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text("$c1", fontSize = 12.sp, color = if (c1 > 0) accent else AppColors.TextDim, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        Text("$c5", fontSize = 12.sp, color = if (c5 > 0) accent else AppColors.TextDim, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        // 🐞 (trailing) → API Traces filtered to this status class. Fixed-
        // width slot + matching header spacer keeps the 1m/5m columns aligned.
        Spacer(Modifier.width(16.dp))
        Text(mi.traces, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.width(22.dp).clickable { onTrace() })
    }
}

/** Compact ms / s formatter for the response-times rows. */
private fun fmtMs(ms: Int): String =
    if (ms >= 1000) String.format(Locale.US, "%.1f s", ms / 1000.0) else "$ms ms"

/** Rolling API response-time stats over the same 1 min / 5 min windows as
 *  the HTTP-responses card, drawn from [HttpStatusStats.timingWithin] (every
 *  response that came back; thrown failures carry no time). Each row is an
 *  aggregate — average, fastest, median, p95, slowest — with a dash when the
 *  window has no samples. */
@Composable
private fun ResponseTimesBody() {
    val tick = rememberLiveTick()
    val t1 = remember(tick) { HttpStatusStats.timingWithin(60_000) }
    val t5 = remember(tick) { HttpStatusStats.timingWithin(5 * 60_000) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text("1m", fontSize = 11.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        Text("5m", fontSize = 11.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
    }
    ResponseTimeRow("avg", t1.count, t1.avgMs, t5.count, t5.avgMs)
    ResponseTimeRow("min", t1.count, t1.minMs, t5.count, t5.minMs)
    ResponseTimeRow("median", t1.count, t1.medianMs, t5.count, t5.medianMs)
    ResponseTimeRow("p95", t1.count, t1.p95Ms, t5.count, t5.p95Ms)
    ResponseTimeRow("max", t1.count, t1.maxMs, t5.count, t5.maxMs)
}

@Composable
private fun ResponseTimeRow(label: String, n1: Int, v1: Int, n5: Int, v5: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color.White)
        Spacer(Modifier.weight(1f))
        Text(if (n1 > 0) fmtMs(v1) else "—", fontSize = 12.sp, color = if (n1 > 0) Color.White else AppColors.TextDim, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        Text(if (n5 > 0) fmtMs(v5) else "—", fontSize = 12.sp, color = if (n5 > 0) Color.White else AppColors.TextDim, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
    }
}

/** Short "12s" / "3m" / "1h" age. */
private fun fmtAge(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} m"
        else -> "${s / 3600} h"
    }
}

private fun errCodeLabel(code: Int): String = if (code == 0) "FAIL" else code.toString()

/** Live feed of the most recent error responses / failures — host · model ·
 *  code · message · age. Fed by [HttpStatusStats.recentErrors]; independent
 *  of the tracing toggle. */
@Composable
private fun RecentErrorsBody() {
    val tick = rememberLiveTick()
    val errors = remember(tick) { HttpStatusStats.recentErrors(5) }
    val now = remember(tick) { System.currentTimeMillis() }
    if (errors.isEmpty()) {
        Text("No errors recently.", fontSize = 12.sp, color = AppColors.TextTertiary)
        return
    }
    errors.forEach { e ->
        val codeColor = if (e.code == 0 || e.code >= 500) AppColors.DangerAccent else AppColors.WarningAccent
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(errCodeLabel(e.code), fontSize = 12.sp, color = codeColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                Text(twoLevelHost(e.host ?: "?"), fontSize = 12.sp, color = Color.White, maxLines = 1)
                e.model?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("· ${com.ai.ui.shared.shortModelName(it)}", fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } ?: Spacer(Modifier.weight(1f))
                Text(fmtAge(now - e.t), fontSize = 11.sp, color = AppColors.TextTertiary)
            }
            Text(e.message, fontSize = 11.sp, color = AppColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The slowest calls in the last 5 minutes (host/model · duration), slowest
 *  first — names the call behind the Response-times p95/max. */
@Composable
private fun SlowestCallsBody() {
    val tick = rememberLiveTick()
    val slow = remember(tick) { HttpStatusStats.slowestWithin(5 * 60_000, 3) }
    if (slow.isEmpty()) {
        Text("No calls in the last 5 minutes.", fontSize = 12.sp, color = AppColors.TextTertiary)
        return
    }
    slow.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(twoLevelHost(s.host ?: "?"), fontSize = 12.sp, color = Color.White, maxLines = 1)
            s.model?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(6.dp))
                Text("· ${com.ai.ui.shared.shortModelName(it)}", fontSize = 11.sp, color = AppColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } ?: Spacer(Modifier.weight(1f))
            Text(fmtMs(s.durationMs.toInt()), fontSize = 12.sp, color = AppColors.WarningAccent, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
        }
    }
}

@Composable
private fun CooldownBody() {
    val tick = rememberLiveTick()
    val cooldowns by ModelCooldownStore.cooldowns.collectAsState()
    val now = remember(tick) { System.currentTimeMillis() }
    val active = cooldowns.filterValues { it > now }
    if (active.isEmpty()) {
        Text("No models cooling down.", fontSize = 12.sp, color = AppColors.TextTertiary)
        return
    }
    active.entries.sortedBy { it.value }.take(12).forEach { (key, until) ->
        val provider = key.substringBefore(":")
        val model = key.substringAfter(":")
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$provider · $model", fontSize = 12.sp, color = Color.White, maxLines = 1)
            Text(fmtDuration(until - now) + " left", fontSize = 12.sp, color = AppColors.WarningAccent, fontWeight = FontWeight.Medium)
        }
    }
    if (active.size > 12) {
        Text("+ ${active.size - 12} more", fontSize = 11.sp, color = AppColors.TextTertiary)
    }
}

@Composable
private fun TestRunBody(reportViewModel: ReportViewModel, context: android.content.Context) {
    val tick = rememberLiveTick()
    // The run lives in memory only after startRun (this session) or hydrate
    // (restored from disk). The Test screen hydrates on open; mirror it here
    // so the card reflects a persisted / resumed run even if the user hasn't
    // reopened the Test screen. No-op once a run is in memory.
    LaunchedEffect(Unit) { reportViewModel.modelTestEngine.hydrate(context) }
    val run by reportViewModel.modelTestEngine.run.collectAsState()
    val now = remember(tick) { System.currentTimeMillis() }
    val r = run
    if (r == null) {
        Text("No test run active.", fontSize = 12.sp, color = AppColors.TextTertiary)
        return
    }
    val finished = r.doneCount + r.errorCount
    Bar(if (r.total > 0) finished.toFloat() / r.total else 0f, AppColors.PrimaryAccent)
    Spacer(Modifier.height(6.dp))
    KeyVal("Progress", "$finished / ${r.total}")
    KeyVal("Passed", "${r.doneCount}", AppColors.SuccessAccent)
    KeyVal("Failed", "${r.errorCount}", if (r.errorCount > 0) AppColors.DangerAccent else Color.White)
    KeyVal("Running", "${r.runningCount}", AppColors.WarningAccent)
    KeyVal("Queued", "${r.queuedCount}")
    KeyVal("Cost", money(r.totalCost), AppColors.SuccessAccent)
    KeyVal("Elapsed", fmtDuration(now - r.startedAt))
}

/** Cumulative stress-test activity this session. The stress run is fire-and-
 *  forget — it submits one report per Example Prompt, then those generate in
 *  the background like any report — so this shows the submit status plus how
 *  many runs / reports were launched and how long ago. Lightweight (in-memory
 *  engine state only). */
@Composable
private fun StressTestBody(reportViewModel: ReportViewModel) {
    val tick = rememberLiveTick()
    val engine = reportViewModel.stressTestEngine
    val state by engine.state.collectAsState()
    val tracked by engine.tracked.collectAsState()
    val now = remember(tick) { System.currentTimeMillis() }
    val running = remember(tick) { engine.isRunning }
    if (state == null && tracked.runCount == 0) {
        Text("No stress test this session.", fontSize = 12.sp, color = AppColors.TextTertiary)
        return
    }
    val isError = state?.phase == com.ai.viewmodel.StressTestEngine.Phase.ERROR
    val (statusWord, statusColor) = when {
        running -> "submitting" to AppColors.WarningAccent
        isError -> "error" to AppColors.DangerAccent
        else -> "submitted — generating in background" to AppColors.SuccessAccent
    }
    KeyVal("Status", statusWord, statusColor)
    if (isError) state?.errorMessage?.let { Text(it, fontSize = 11.sp, color = AppColors.DangerAccent) }
    KeyVal("Runs this session", "${tracked.runCount}")
    KeyVal("Reports launched", "${tracked.reportIds.size}")
    if (tracked.firstStartedAt > 0L) KeyVal("Since first run", fmtDuration(now - tracked.firstStartedAt))
    if (tracked.lastStartedAt > 0L && tracked.runCount > 1) KeyVal("Since last run", fmtDuration(now - tracked.lastStartedAt))
}

@Composable
private fun HealthBody(context: android.content.Context) {
    val tick = rememberLiveTick()
    val logErr = remember(tick) { AppLog.lastWriterError }
    val droppedLines = remember(tick) { AppLog.droppedLineCount }
    val busy = remember(tick) { ApiCallCaps.snapshot().globalInFlight > 0 }
    // Disk + trace count are IO — slow 10 s tick (computed only while this
    // card is open).
    val slowTick by produceState(0) { while (true) { delay(10_000); value++ } }
    val traceCount by produceState(0, slowTick) {
        value = withContext(Dispatchers.IO) { ApiTracer.getTraceCount() }
    }
    val disk by produceState(DiskUsageStats.Snapshot(), slowTick) {
        value = withContext(Dispatchers.IO) { DiskUsageStats.snapshot(context) }
    }
    KeyVal("Log writer", if (logErr == null) "OK" else "ERROR", if (logErr == null) AppColors.SuccessAccent else AppColors.DangerAccent)
    if (logErr != null) Text(logErr, fontSize = 11.sp, color = AppColors.DangerAccent)
    KeyVal("Dropped log lines", "$droppedLines", if (droppedLines > 0) AppColors.WarningAccent else Color.White)
    KeyVal("Trace files", "$traceCount (${fmtBytes(disk.traceBytes)})")
    KeyVal("Embeddings on disk", fmtBytes(disk.embeddingsBytes))
    KeyVal("Knowledge on disk", fmtBytes(disk.knowledgeBytes))
    KeyVal("API activity", if (busy) "active" else "idle", if (busy) AppColors.SuccessAccent else AppColors.TextDim)
    KeyVal("Streaming timeout", "${NetworkSettings.streamingReadTimeoutSec} s")
    KeyVal("Non-streaming timeout", "${NetworkSettings.nonStreamingReadTimeoutSec} s")
    KeyVal("Per-minute cap / host", "${NetworkSettings.maxCallsPerProviderPerMinute}")
}

/** On-device runtime activity — which local LLM / embedder models are loaded
 *  and which (if any) is running right now. */
@Composable
private fun LocalRuntimeBody(context: android.content.Context) {
    val tick = rememberLiveTick()
    // Imported-on-disk models rarely change — read once when the card opens.
    // (Loaded-in-memory was the only thing shown before, which is empty unless
    // a local model is actively held, so the card looked permanently empty.)
    val installedLlms = remember { com.ai.data.local.LocalLlm.installedTaskFiles(context) }
    val installedEmbedders = remember { com.ai.data.local.LocalEmbedder.availableModels(context) }
    val runtimeInstalled = remember { com.ai.data.local.LlmRuntime.isInstalled(context) }
    // Live in-memory / activity state — ticks.
    val rt = remember(tick) { com.ai.data.local.LocalRuntime.snapshot() }
    if (installedLlms.isEmpty() && installedEmbedders.isEmpty() && !rt.active) {
        Text("No on-device models installed.", fontSize = 12.sp, color = AppColors.TextTertiary)
        return
    }
    if (installedLlms.isNotEmpty() || rt.llmGenerating != null) {
        KeyVal("LLM runtime", if (runtimeInstalled) "installed" else "not installed",
            if (runtimeInstalled) AppColors.SuccessAccent else AppColors.WarningAccent)
        KeyVal("LLMs imported", installedLlms.joinToString(", ").ifBlank { "—" })
        KeyVal("Loaded in memory", rt.llmLoaded.joinToString(", ").ifBlank { "none" })
        KeyVal("Generating", rt.llmGenerating ?: "idle",
            if (rt.llmGenerating != null) AppColors.SuccessAccent else AppColors.TextDim)
    }
    if (installedEmbedders.isNotEmpty() || rt.embedding != null) {
        if (installedLlms.isNotEmpty() || rt.llmGenerating != null) Spacer(Modifier.height(6.dp))
        KeyVal("Embedders installed", installedEmbedders.joinToString(", ").ifBlank { "—" })
        KeyVal("Loaded in memory", rt.embedderLoaded.joinToString(", ").ifBlank { "none" })
        KeyVal("Embedding", rt.embedding ?: "idle",
            if (rt.embedding != null) AppColors.SuccessAccent else AppColors.TextDim)
    }
}

// =====================================================================
// Aggregate sections
// =====================================================================

@Composable
private fun ReportsSection(rs: ReportStats) {
    SectionCard("📋", "Reports", AppColors.InfoAccent) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatChip("📄", "Total", rs.total, Color.White)
            StatChip("⏳", "Running", rs.running, AppColors.WarningAccent)
            StatChip("⚠️", "Problems", rs.problems, AppColors.DangerAccent)
            StatChip("✅", "Completed", rs.completed, AppColors.SuccessAccent)
        }
        Spacer(Modifier.height(8.dp))
        KeyVal("Agent calls", "${rs.agentCalls}")
        val errRate = if (rs.agentCalls > 0) rs.erroredCalls * 100.0 / rs.agentCalls else 0.0
        KeyVal(
            "Error rate", String.format(Locale.US, "%.1f%%  (%d)", errRate, rs.erroredCalls),
            if (errRate >= 10.0) AppColors.DangerAccent else if (errRate > 0) AppColors.WarningAccent else AppColors.SuccessAccent
        )
        Bar(if (rs.agentCalls > 0) (rs.erroredCalls.toFloat() / rs.agentCalls) else 0f, AppColors.DangerAccent)
        if (rs.stopped > 0) KeyVal("Stopped agents", "${rs.stopped}", AppColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        KeyVal("Report spend", money(rs.spend), AppColors.SuccessAccent)
    }
}

@Composable
private fun SecondariesSection(byKind: Map<SecondaryKind, Int>, metaByName: Map<String, Int>) {
    SectionCard("🔗", "Secondary results", AppColors.SecondaryAccent) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatChip("🔀", "Rerank", byKind[SecondaryKind.RERANK] ?: 0, AppColors.WarningAccent)
            StatChip("🧩", "Meta", byKind[SecondaryKind.META] ?: 0, AppColors.PrimaryAccent)
            StatChip("🛡️", "Moderation", byKind[SecondaryKind.MODERATION] ?: 0, AppColors.DangerAccent)
            StatChip("🌐", "Translate", byKind[SecondaryKind.TRANSLATE] ?: 0, AppColors.InfoAccent)
        }
        if (metaByName.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("By meta prompt", fontSize = 11.sp, color = AppColors.TextTertiary)
            metaByName.entries.take(6).forEach { (name, count) -> KeyVal(name, "$count") }
        }
    }
}

@Composable
private fun KnowledgeSection(d: KnowledgeData) {
    SectionCard("📚", "Knowledge", AppColors.CautionAccent) {
        KeyVal("Knowledge bases", "${d.kbCount}")
        KeyVal("Chunks", formatCompactNumber(d.kbChunks.toLong()))
        KeyVal("Indexed text", "${formatCompactNumber(d.kbChars)} chars")
        if (d.kbFailed > 0) KeyVal("Failed sources", "${d.kbFailed}", AppColors.DangerAccent)
        if (d.kbSourcesByType.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                d.kbSourcesByType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                    StatChip("📄", type.name, count, AppColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun CostTierSection(config: Map<String, Int>, runtime: Map<String, Int>) {
    SectionCard("🧮", "Costs tiers", AppColors.InfoAccent) {
        // Header
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text("Tier", fontSize = 10.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1.6f))
            Text("Config", fontSize = 10.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
            Text("Runtime", fontSize = 10.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
        }
        // Union of keys, config order first (both seed PRICING_TIER_ORDER).
        val keys = (config.keys + runtime.keys)
        keys.forEach { src ->
            val cfg = config[src] ?: 0
            val rt = runtime[src] ?: 0
            // All rows render uniformly (no per-row dimming) so a zero-count
            // tier like "Manual override" matches the rest.
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tierLabel(src), fontSize = 12.sp, color = Color.White, maxLines = 1, modifier = Modifier.weight(1.6f))
                Text("$cfg", fontSize = 12.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
                Text("$rt", fontSize = 12.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("Total models", fontSize = 12.sp, color = AppColors.TextSecondary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1.6f))
            Text("${config.values.sum()}", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
            Text("${runtime.values.sum()}", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
        }
    }
}

/** Display label for a [PricingCache] source tag (DEFAULT is the 25/75
 *  fallback). */
private fun tierLabel(src: String): String = when (src) {
    "API_REPORTED" -> "API-reported"
    "OVERRIDE" -> "Manual override"
    "LITELLM" -> "LiteLLM"
    "MODELSDEV" -> "models.dev"
    "LLMPRICES" -> "llm-prices"
    "ARTIFICIALANALYSIS" -> "Artificial Analysis"
    "OPENROUTER" -> "OpenRouter"
    "TOGETHER" -> "Together"
    "HELICONE" -> "Helicone"
    "DEFAULT" -> "25/75 default"
    else -> src
}

@Composable
private fun PricingSection(
    catalogStats: List<com.ai.data.PricingCache.CatalogStat>,
    tracedCategories: Set<String> = emptySet(),
    onNavigateToTraceCategory: (String) -> Unit = {},
) {
    SectionCard("🏷️", "Pricing cache", AppColors.PrimaryAccent) {
        // Header
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text("Source", fontSize = 10.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1.5f))
            Text("Entries", fontSize = 10.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
            Text("Retrieved", fontSize = 10.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
        }
        catalogStats.forEach { c ->
            val dim = c.entries == 0
            val traceCat = "pricing/${c.name}"
            val hasTrace = c.fetchedAt > 0L && traceCat in tracedCategories
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, fontSize = 12.sp, color = if (dim) AppColors.TextDim else Color.White, maxLines = 1, modifier = Modifier.weight(1.5f))
                Text("${c.entries}", fontSize = 12.sp, color = if (dim) AppColors.TextDim else AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
                Row(modifier = Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    Text(
                        fmtFetched(c.fetchedAt), fontSize = 11.sp,
                        color = if (c.fetchedAt == 0L) AppColors.TextDim else AppColors.TextSecondary,
                        maxLines = 1
                    )
                    // 🐞 → the API Traces filtered to this source's retrieve.
                    // Only when the retrieve was actually captured.
                    if (hasTrace) {
                        Text(
                            com.ai.ui.shared.LocalMetadataIcons.current.traces, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 6.dp).clickable { onNavigateToTraceCategory(traceCat) }
                        )
                    }
                }
            }
        }
    }
}

/** Compact absolute timestamp for the pricing-cache table; "never" at 0. */
private fun fmtFetched(ms: Long): String =
    if (ms <= 0L) "never"
    else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(ms))

// =====================================================================
// Reusable building blocks
// =====================================================================

/** Tappable card that links to a sub-screen (used by the Monitor hub for the
 *  heavy Spend & usage / Costs tier pages). */
@Composable
private fun LinkCard(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                mi.forFactoryGlyph(emoji),
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(42.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, fontSize = 11.sp, color = AppColors.TextTertiary)
            }
            Text("›", fontSize = 22.sp, color = AppColors.TextTertiary)
        }
    }
}

@Composable
private fun SectionCard(emoji: String, title: String, accent: Color, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(mi.forFactoryGlyph(emoji), fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
                if (onClick != null) Text("›", fontSize = 22.sp, color = AppColors.TextTertiary)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun CapBar(label: String, inFlight: Int, max: Int) {
    val frac = if (max > 0) (inFlight.toFloat() / max).coerceIn(0f, 1f) else 0f
    val color = when {
        max > 0 && inFlight >= max -> AppColors.DangerAccent
        frac >= 0.6f -> AppColors.WarningAccent
        inFlight > 0 -> AppColors.SuccessAccent
        else -> AppColors.TextDim
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = AppColors.TextSecondary)
            Text("$inFlight/$max", fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
        }
        Bar(frac, color)
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.SurfaceDark)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(color)
        )
    }
}

@Composable
private fun StatChip(emoji: String, label: String, count: Int, accent: Color) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Row(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceDark)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(mi.forFactoryGlyph(emoji), fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary)
    }
}

@Composable
private fun KeyVal(label: String, value: String, valueColor: Color = Color.White) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(mi.iconizedText(label), fontSize = 12.sp, color = AppColors.TextSecondary)
        Text(mi.iconizedText(value), fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

/** KeyVal that's tappable — label left, count right, a faint "›" to
 *  signal the row drills into a filtered API-trace list. */
@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color.White, onClick: () -> Unit) {
    val mi = com.ai.ui.shared.LocalMetadataIcons.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(mi.iconizedText(label), fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 16.sp, color = AppColors.TextTertiary)
    }
}

private fun money(v: Double): String =
    if (v > 0 && v < 0.01) String.format(Locale.US, "$%.6f", v)
    else String.format(Locale.US, "$%.4f", v)

/** Sortable columns of the Spend & usage table. */
private enum class UsageSort { PROVIDER, CALLS, TOKENS, COST }

private enum class SpendUsageMode(val label: String) {
    PROVIDERS("Providers"),
    TYPES("Types"),
    REPORTS("Reports"),
    MODELS("Models")
}

/** One aggregated row in the Models tab — all usage for a single model
 *  id summed across every provider that served it. */
private data class ModelUsageRow(
    val model: String,
    val calls: Int,
    val tokens: Long,
    val totalCost: Double,
)

private fun cents(v: Double, decimals: Int = 2): String = "${formatCents(v, decimals)} ¢"

/** "1:05", "12m", "3h 20m" — compact remaining/elapsed. */
private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} m"
        s >= 600 -> "${s / 60} m"
        else -> String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
