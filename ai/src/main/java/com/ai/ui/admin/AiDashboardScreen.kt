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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.KnowledgeData
import com.ai.data.LogStatsData
import com.ai.data.ModelCooldownStore
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
) {
    BackHandler { onBack() }

    // ---- live ticker: cheap in-memory snapshots only ----
    val liveTick by produceState(0) { while (true) { delay(750); value++ } }
    val caps = remember(liveTick) { ApiCallCaps.snapshot() }
    val hosts = remember(liveTick) { ProviderThrottle.snapshot() }
    val now = remember(liveTick) { System.currentTimeMillis() }
    val logErr = remember(liveTick) { AppLog.lastWriterError }
    val droppedLines = remember(liveTick) { AppLog.droppedLineCount }

    // ---- live reactive flows ----
    val thrFanOut by appViewModel.throttledFanOutPairs.collectAsState()
    val thrMeta by appViewModel.throttledFanMetaPairs.collectAsState()
    val cooldowns by ModelCooldownStore.cooldowns.collectAsState()
    val testRun by reportViewModel.modelTestEngine.run.collectAsState()

    // Trace-file count for the Health card — disk read, so off the 750 ms
    // ticker: refresh on resume + a slow 10 s tick.
    val refreshTick = resumeRefreshTick()
    val slowTick by produceState(0) { while (true) { delay(10_000); value++ } }
    val traceCount by produceState(0, refreshTick, slowTick) {
        value = withContext(Dispatchers.IO) { ApiTracer.getTraceCount() }
    }

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
            reportIcon = "📡", reportIconGoesHome = true
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item { LiveActivitySection(caps, thrFanOut, thrMeta) }
            item { ThrottleSection(hosts) }

            val activeCooldowns = cooldowns.filterValues { it > now }
            if (activeCooldowns.isNotEmpty()) {
                item { CooldownSection(activeCooldowns, now) }
            }

            testRun?.let { run -> item { TestRunSection(run, now) } }

            item {
                HealthSection(
                    logErr = logErr, droppedLines = droppedLines, traceCount = traceCount,
                    busy = caps.globalInFlight > 0
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** AI Monitor — the single hub gathering the live + historical
 *  observability screens. Live Dashboard, API Traces and Application
 *  log sit here, followed by the lifetime-aggregate stat pages that
 *  used to live behind a separate "Statistics" hub (now retired — its
 *  entries were folded in here). The two per-subject aggregate pages
 *  (API trace / App log statistics) are reached from the 📈 icon on
 *  the API Traces / Application log screens, not from a card here.
 *  Knowledge totals (cheap) show inline. */
@Composable
fun AiMonitorScreen(
    onBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToLiveDashboard: () -> Unit = {},
    onNavigateToTraces: () -> Unit = {},
    onNavigateToAppLog: () -> Unit = {},
    onNavigateToSpendUsage: () -> Unit = {},
    onNavigateToCostsTier: () -> Unit = {},
    onNavigateToReports: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
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
            helpTopic = "ai_monitor",
            title = "Monitor",
            subject = "Live and historical observability",
            onBackClick = onBack,
            reportIcon = "📡", reportIconGoesHome = true,
            onTitleClick = onNavigateHome,
            onHousekeeping = onHousekeeping
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { LinkCard("📡", "Live Dashboard", "In-flight calls, caps and throttle state", onNavigateToLiveDashboard) }
            item { LinkCard("🐞", "API Traces", "Per-call request/response records", onNavigateToTraces) }
            item { LinkCard("📜", "Application log", "The in-app application log, line by line", onNavigateToAppLog) }
            item { LinkCard("📋", "Reports", "Reports + secondary results totals", onNavigateToReports) }
            item { LinkCard("🔌", "Providers", "Per-provider keys, formats, caches, test runs", onNavigateToProviders) }
            item { LinkCard("🧠", "Models", "Capabilities, types, context, states", onNavigateToModels) }
            item { LinkCard("💰", "Spend & usage", "Calls, tokens and cost per provider", onNavigateToSpendUsage) }
            item { LinkCard("🧮", "Costs tiers", "Pricing tier per model + catalog freshness", onNavigateToCostsTier) }
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
            onBackClick = onBack, reportIcon = "📈",
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
                    SectionCard("🐞", "Overview", AppColors.Indigo) {
                        KeyVal("Tracing", if (s.tracingEnabled) "on" else "off", if (s.tracingEnabled) AppColors.Green else AppColors.Orange)
                        KeyVal("Total traces", "${s.total}")
                        KeyVal("Distinct runs", "${s.runs}")
                        if (s.partial > 0) KeyVal("Partial (streaming)", "${s.partial}", AppColors.TextSecondary)
                    }
                }
                item {
                    SectionCard("📡", "Status", AppColors.Blue) {
                        StatRow("✅ 2xx", "${s.ok2xx}", AppColors.Green) { onOpenTraceFilter("status", "2xx") }
                        StatRow("🚧 429", "${s.rate429}", if (s.rate429 > 0) AppColors.Orange else AppColors.TextDim) { onOpenTraceFilter("status", "429") }
                        StatRow("⚠️ 4xx", "${s.client4xx}", if (s.client4xx > 0) AppColors.Orange else AppColors.TextDim) { onOpenTraceFilter("status", "4xx") }
                        StatRow("🔥 5xx", "${s.server5xx}", if (s.server5xx > 0) AppColors.Red else AppColors.TextDim) { onOpenTraceFilter("status", "5xx") }
                        StatRow("💥 Failed", "${s.failed0}", if (s.failed0 > 0) AppColors.Red else AppColors.TextDim) { onOpenTraceFilter("status", "0") }
                        StatRow("▫️ Other", "${s.other}", AppColors.TextDim) { onOpenTraceFilter("status", "other") }
                    }
                }
                if (s.byHost.isNotEmpty()) item {
                    SectionCard("🌐", "Top hosts", AppColors.Green, onClick = { onOpenBreakdown("host") }) {
                        s.byHost.take(5).forEach { (h, c) -> KeyVal(h, "$c") }
                        if (s.byHost.size > 5) KeyVal("+${s.byHost.size - 5} more", "→", AppColors.TextTertiary)
                    }
                }
                if (s.byModel.isNotEmpty()) item {
                    SectionCard("🧠", "Top models", AppColors.Purple, onClick = { onOpenBreakdown("model") }) {
                        s.byModel.take(5).forEach { (m, c) -> KeyVal(com.ai.ui.shared.shortModelName(m), "$c") }
                        if (s.byModel.size > 5) KeyVal("+${s.byModel.size - 5} more", "→", AppColors.TextTertiary)
                    }
                }
                if (s.byCategory.isNotEmpty()) item {
                    SectionCard("🏷️", "Top categories", AppColors.Indigo, onClick = { onOpenBreakdown("category") }) {
                        s.byCategory.take(5).forEach { (cat, c) -> KeyVal(cat, "$c") }
                        if (s.byCategory.size > 5) KeyVal("+${s.byCategory.size - 5} more", "→", AppColors.TextTertiary)
                    }
                }
                item {
                    SectionCard("🗓️", "Activity", AppColors.Blue) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("☀️", "Today", s.today, AppColors.Green)
                            StatChip("📅", "7 days", s.last7d, AppColors.Blue)
                            StatChip("🗓️", "30 days", s.last30d, AppColors.Indigo)
                        }
                        s.newest?.let { Spacer(Modifier.height(4.dp)); KeyVal("Newest", fmtFetched(it)) }
                        s.oldest?.let { KeyVal("Oldest", fmtFetched(it)) }
                    }
                }
                item {
                    SectionCard("📋", "Reports", AppColors.Orange) {
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
        "host" -> Triple("🌐", "Trace hosts", AppColors.Green)
        "model" -> Triple("🧠", "Trace models", AppColors.Purple)
        else -> Triple("🏷️", "Trace categories", AppColors.Indigo)
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_trace_breakdown", title = title, subject = "Every $dim, by trace count",
            onBackClick = onBack, reportIcon = "📈",
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
            onBackClick = onBack, reportIcon = "📈",
            onReportIconClick = onNavigateToStatistics, onTitleClick = onNavigateToStatistics
        )
        val s = d
        when {
            s == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            s.fileCount == 0 -> Text("No log files yet.", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionCard("🩺", "Health", AppColors.Green) {
                        KeyVal("Log level", s.level)
                        KeyVal("Writer", if (s.writerError == null) "OK" else "ERROR", if (s.writerError == null) AppColors.Green else AppColors.Red)
                        if (s.writerError != null) Text(s.writerError, fontSize = 11.sp, color = AppColors.Red)
                        KeyVal("Dropped lines", "${s.droppedLines}", if (s.droppedLines > 0) AppColors.Orange else Color.White)
                    }
                }
                item {
                    SectionCard("📊", "By level", AppColors.Indigo) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("❌", "Error", s.byLevel["ERROR"] ?: 0, if ((s.byLevel["ERROR"] ?: 0) > 0) AppColors.Red else AppColors.TextDim)
                            StatChip("⚠️", "Warn", s.byLevel["WARN"] ?: 0, if ((s.byLevel["WARN"] ?: 0) > 0) AppColors.Orange else AppColors.TextDim)
                            StatChip("ℹ️", "Info", s.byLevel["INFO"] ?: 0, AppColors.Green)
                            StatChip("🔧", "Debug", s.byLevel["DEBUG"] ?: 0, AppColors.Blue)
                            StatChip("🔬", "Trace", s.byLevel["TRACE"] ?: 0, AppColors.TextSecondary)
                        }
                        Spacer(Modifier.height(4.dp))
                        KeyVal("Total entries", "${s.totalEntries}")
                    }
                }
                if (s.topTags.isNotEmpty()) item {
                    SectionCard("🏷️", "Top tags", AppColors.Purple) { s.topTags.forEach { (tag, c) -> KeyVal(tag, "$c") } }
                }
                item {
                    SectionCard("🗂️", "Files", AppColors.Blue) {
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
            reportIcon = "📈",
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
                    SectionCard("🤖", "Agent calls", AppColors.Blue) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("✅", "Success", d.agentSuccess, AppColors.Green)
                            StatChip("❌", "Error", d.reports.erroredCalls, if (d.reports.erroredCalls > 0) AppColors.Red else AppColors.TextDim)
                            StatChip("⏹️", "Stopped", d.reports.stopped, AppColors.TextSecondary)
                            StatChip("⏳", "In flight", d.agentPending, if (d.agentPending > 0) AppColors.Orange else AppColors.TextDim)
                        }
                        Spacer(Modifier.height(6.dp))
                        val errRate = if (d.reports.agentCalls > 0) d.reports.erroredCalls * 100.0 / d.reports.agentCalls else 0.0
                        KeyVal("Error rate", String.format(Locale.US, "%.1f%%", errRate),
                            if (errRate >= 10.0) AppColors.Red else if (errRate > 0) AppColors.Orange else AppColors.Green)
                        Bar(if (d.reports.agentCalls > 0) d.reports.erroredCalls.toFloat() / d.reports.agentCalls else 0f, AppColors.Red)
                        val avgAgents = if (d.reports.total > 0) d.reports.agentCalls.toDouble() / d.reports.total else 0.0
                        KeyVal("Avg models / report", String.format(Locale.US, "%.1f", avgAgents))
                    }
                }

                item {
                    SectionCard("💵", "Tokens & spend", AppColors.Green) {
                        KeyVal("Input tokens", formatCompactNumber(d.inputTokens))
                        KeyVal("Output tokens", formatCompactNumber(d.outputTokens))
                        KeyVal("Total tokens", formatCompactNumber(d.inputTokens + d.outputTokens))
                        KeyVal("Secondary tokens", formatCompactNumber(d.secondaryTokens), AppColors.TextSecondary)
                        Spacer(Modifier.height(4.dp))
                        KeyVal("Report spend", money(d.reports.spend), AppColors.Green)
                        KeyVal("Secondary spend", money(d.secondaryCost), AppColors.Green)
                        KeyVal("Total spend", money(d.reports.spend + d.secondaryCost), AppColors.Green)
                        val avgPerReport = if (d.reports.total > 0) d.reports.spend / d.reports.total else 0.0
                        val avgPerCall = if (d.reports.agentCalls > 0) d.reports.spend / d.reports.agentCalls else 0.0
                        KeyVal("Avg / report", money(avgPerReport), AppColors.TextSecondary)
                        KeyVal("Avg / call", money(avgPerCall), AppColors.TextSecondary)
                        KeyVal("Total compute", fmtDuration(d.totalDurationMs))
                    }
                }

                item {
                    SectionCard("🗓️", "Activity", AppColors.Indigo) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("☀️", "Today", d.createdToday, AppColors.Green)
                            StatChip("📅", "7 days", d.created7d, AppColors.Blue)
                            StatChip("🗓️", "30 days", d.created30d, AppColors.Indigo)
                            StatChip("📌", "Pinned", d.pinned, AppColors.Yellow)
                        }
                        d.oldestCreatedAt?.let {
                            Spacer(Modifier.height(4.dp))
                            KeyVal("Oldest report", fmtDuration(System.currentTimeMillis() - it) + " ago", AppColors.TextSecondary)
                        }
                    }
                }

                item {
                    SectionCard("✨", "Features used", AppColors.Purple) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("👁", "Vision", d.withImage, AppColors.Blue)
                            StatChip("🌐", "Web search", d.withWebSearch, AppColors.Green)
                            StatChip("🧠", "Reasoning", d.withReasoning, AppColors.Purple)
                            StatChip("📚", "Knowledge", d.withKnowledge, AppColors.Yellow)
                            StatChip("🌍", "Translated", d.translated, AppColors.Blue)
                            StatChip("📊", "Table", d.tableReports, AppColors.TextSecondary)
                        }
                    }
                }

                if (d.topModels.isNotEmpty()) item {
                    SectionCard("🏆", "Top models (by calls)", AppColors.Orange) {
                        d.topModels.forEach { (model, calls) -> KeyVal(com.ai.ui.shared.shortModelName(model), "$calls") }
                    }
                }
                if (d.topProviders.isNotEmpty()) item {
                    SectionCard("🔌", "Top providers (by calls)", AppColors.Blue) {
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
            reportIcon = "📈",
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
                    SectionCard("🔌", "Providers", AppColors.Indigo) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🔢", "Configured", d.providersConfigured, Color.White)
                            StatChip("🟢", "Active", d.providersActive, AppColors.Green)
                            StatChip("🔑", "With key", d.providersWithKey, AppColors.Blue)
                            StatChip("⚪", "Inactive", d.providersConfigured - d.providersActive, AppColors.TextDim)
                        }
                    }
                }
                item {
                    SectionCard("🔣", "API formats", AppColors.Blue) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🤝", "OpenAI-compatible", d.byFormat["OPENAI_COMPATIBLE"] ?: 0, AppColors.Green)
                            StatChip("🅰️", "Anthropic", d.byFormat["ANTHROPIC"] ?: 0, AppColors.Orange)
                            StatChip("🔷", "Google", d.byFormat["GOOGLE"] ?: 0, AppColors.Blue)
                        }
                    }
                }
                item {
                    SectionCard("🗂️", "Catalog cache", AppColors.Green) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("✅", "Cached", d.cached, AppColors.Green)
                            StatChip("⏳", "Stale >7d", d.stale, if (d.stale > 0) AppColors.Orange else AppColors.TextDim)
                            StatChip("➖", "Never", d.neverCached, AppColors.TextDim)
                        }
                    }
                }
                item {
                    SectionCard("👥", "Workers", AppColors.Blue) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🤖", "Agents", d.agents, AppColors.Blue)
                            StatChip("🐦", "Flocks", d.flocks, AppColors.Green)
                            StatChip("🐝", "Swarms", d.swarms, AppColors.Orange)
                        }
                    }
                }
                d.lastTest?.let { t ->
                    item {
                        SectionCard("🧪", "Last test-all-models", AppColors.Purple) {
                            KeyVal("For testing", "${t.forTesting}")
                            KeyVal("Passed", "${t.passed}", AppColors.Green)
                            KeyVal("Failed", "${t.failed}", if (t.failed > 0) AppColors.Red else Color.White)
                            KeyVal("Cost", money(t.cost), AppColors.Green)
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
            onBackClick = onBack, reportIcon = "📈",
            onReportIconClick = onNavigateToStatistics, onTitleClick = onNavigateToStatistics
        )
        val d = data
        if (d == null) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    SectionCard("🧠", "Models", AppColors.Purple) {
                        KeyVal("Total configured", "${d.totalModels}")
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("👁", "Vision", d.vision, AppColors.Blue)
                            StatChip("🌐", "Web search", d.webSearch, AppColors.Green)
                            StatChip("🧠", "Reasoning", d.reasoning, AppColors.Purple)
                            StatChip("🔢", "Embedding", d.embedding, AppColors.Indigo)
                        }
                    }
                }
                item {
                    SectionCard("🛠️", "Capabilities", AppColors.Blue) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🧰", "Function calling", d.fnCalling, AppColors.Green)
                            StatChip("📄", "PDF input", d.pdfInput, AppColors.Blue)
                            StatChip("🎚️", "Reasoning levels", d.reasoningLevels, AppColors.Purple)
                            StatChip("🗒️", "With metadata", d.withCaps, AppColors.TextSecondary)
                        }
                    }
                }
                if (d.modelsByType.isNotEmpty()) item {
                    SectionCard("🏷️", "By type", AppColors.Indigo) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.modelsByType.forEach { (type, count) -> StatChip("•", type, count, AppColors.TextSecondary) }
                        }
                    }
                }
                item {
                    SectionCard("📏", "Context length", AppColors.Green) {
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
                    SectionCard("🚦", "States", AppColors.Orange) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("⛔", "Blocked", d.blocked, if (d.blocked > 0) AppColors.Red else AppColors.TextDim)
                            StatChip("🚫", "Inaccessible", d.inaccessible, if (d.inaccessible > 0) AppColors.Orange else AppColors.TextDim)
                            StatChip("⏭️", "Test-excluded", d.testExcluded, AppColors.TextSecondary)
                            StatChip("❄️", "Cooling", d.cooling, if (d.cooling > 0) AppColors.Orange else AppColors.TextDim)
                        }
                    }
                }
                if (d.deprecated > 0) item {
                    SectionCard("⚰️", "Deprecated", AppColors.Red) {
                        KeyVal("Models flagged deprecated", "${d.deprecated}", AppColors.Orange)
                    }
                }
                item {
                    SectionCard("🔌", "Models per provider", AppColors.Blue) {
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
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (row.active) "🟢" else "⚪", fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text(row.id, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    formatTag(row.format), fontSize = 9.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AppColors.SurfaceDark).padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("${row.models} models", fontSize = 12.sp, color = AppColors.TextSecondary)
                Spacer(Modifier.width(6.dp))
                Text(if (isExpanded) "▾" else "▸", color = AppColors.TextTertiary)
            }
            // Compact signal chips — only the non-zero ones.
            val signals = buildList {
                if (row.vision > 0) add("👁" to row.vision)
                if (row.webSearch > 0) add("🌐" to row.webSearch)
                if (row.reasoning > 0) add("🧠" to row.reasoning)
                if (row.cooling > 0) add("❄️" to row.cooling)
                if (row.blocked > 0) add("⛔" to row.blocked)
            }
            if (signals.isNotEmpty() || !row.hasKey) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!row.hasKey) Text("no key", fontSize = 11.sp, color = AppColors.Orange)
                    signals.forEach { (e, n) -> Text("$e $n", fontSize = 11.sp, color = AppColors.TextSecondary) }
                }
            }
            if (isExpanded) {
                HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.padding(vertical = 8.dp))
                KeyVal("Default model", row.defaultModel.ifBlank { "—" })
                KeyVal("Host", row.host.ifBlank { "—" })
                KeyVal("API key", if (row.hasKey) "yes" else "no", if (row.hasKey) AppColors.Green else AppColors.Orange)
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
                        if (row.blocked > 0) StatChip("⛔", "Blocked", row.blocked, AppColors.Red)
                        if (row.inaccessible > 0) StatChip("🚫", "Inaccessible", row.inaccessible, AppColors.Orange)
                        if (row.testExcluded > 0) StatChip("⏭️", "Excluded", row.testExcluded, AppColors.TextSecondary)
                        if (row.cooling > 0) StatChip("❄️", "Cooling", row.cooling, AppColors.Orange)
                    }
                }
                if (row.testPassed + row.testFailed > 0) {
                    Spacer(Modifier.height(4.dp))
                    KeyVal("Last test", "${row.testPassed} passed · ${row.testFailed} failed",
                        if (row.testFailed > 0) AppColors.Orange else AppColors.Green)
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
    onNavigateToStatistics: () -> Unit = {},
    /** 🐞 on a provider row → the API Traces scoped to that provider. */
    onNavigateToTraceProvider: (String) -> Unit = {},
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
            subject = "Calls, tokens and cost per provider",
            onBackClick = onBack,
            reportIcon = "📈",
            onReportIconClick = onNavigateToStatistics,
            onTitleClick = onNavigateToStatistics,
            onDelete = { confirmClear = true },
            onHousekeeping = onHousekeeping
        )

        val d = data
        when {
            d == null -> Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            d.groups.isEmpty() -> Text(
                "No usage data yet. Generate reports or chat to see spend.",
                color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp)
            )
            else -> {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Total: ${d.totalCalls} calls, ${formatCompactNumber(d.totalTokens)} tokens", fontSize = 13.sp, color = Color.White)
                        Text("Cost: ${money4(d.totalCost)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Green)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Content-width table, centered (not stretched edge-to-edge).
                // cGap widens the space between Tokens and Cost; cBug is the
                // trailing 🐞 column.
                val cProv = 150.dp; val cCalls = 56.dp; val cTok = 78.dp
                val cGap = 24.dp; val cCost = 92.dp; val cBug = 28.dp
                val tableWidth = cProv + cCalls + cTok + cGap + cCost + cBug
                // Sorted view: provider by id, others numeric; direction from sortAsc.
                val rows = remember(d.groups, sortCol, sortAsc) {
                    val withTokens = d.groups.map { it to it.models.sumOf { m -> m.stat.totalTokens } }
                    val cmp: Comparator<Pair<ProviderCostGroup, Long>> = when (sortCol) {
                        UsageSort.PROVIDER -> compareBy { it.first.provider.id.lowercase() }
                        UsageSort.CALLS -> compareBy { it.first.totalCalls }
                        UsageSort.TOKENS -> compareBy { it.second }
                        UsageSort.COST -> compareBy { it.first.totalCost }
                    }
                    withTokens.sortedWith(if (sortAsc) cmp else cmp.reversed())
                }
                Column(
                    modifier = Modifier.align(Alignment.CenterHorizontally).weight(1f).verticalScroll(rememberScrollState())
                ) {
                    @Composable
                    fun HeaderCell(label: String, col: UsageSort, width: Dp, alignEnd: Boolean) {
                        val arrow = if (sortCol == col) (if (sortAsc) " ▲" else " ▼") else ""
                        Text(
                            label + arrow, fontSize = 10.sp,
                            color = if (sortCol == col) AppColors.TextSecondary else AppColors.TextTertiary,
                            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                            maxLines = 1,
                            modifier = Modifier.width(width).clickable {
                                if (sortCol == col) sortAsc = !sortAsc else { sortCol = col; sortAsc = false }
                            }
                        )
                    }
                    Row(Modifier.padding(vertical = 4.dp)) {
                        HeaderCell("Provider", UsageSort.PROVIDER, cProv, alignEnd = false)
                        HeaderCell("Calls", UsageSort.CALLS, cCalls, alignEnd = true)
                        HeaderCell("Tokens", UsageSort.TOKENS, cTok, alignEnd = true)
                        Spacer(Modifier.width(cGap))
                        HeaderCell("Cost", UsageSort.COST, cCost, alignEnd = true)
                        Spacer(Modifier.width(cBug))
                    }
                    rows.forEach { (group, tokens) ->
                        val hasTrace = group.provider.id in tracedProviders
                        Row(
                            modifier = Modifier.clickable { onOpenProvider(group.provider.id) }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(group.provider.id, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(cProv))
                            Text("${group.totalCalls}", fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cCalls))
                            Text(formatCompactNumber(tokens), fontSize = 13.sp, color = AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(cTok))
                            Spacer(Modifier.width(cGap))
                            Text(money4(group.totalCost), fontSize = 13.sp, color = AppColors.Green, textAlign = TextAlign.End, modifier = Modifier.width(cCost))
                            Box(Modifier.width(cBug), contentAlignment = Alignment.Center) {
                                if (hasTrace) Text("🐞", fontSize = 13.sp, modifier = Modifier.clickable { onNavigateToTraceProvider(group.provider.id) })
                            }
                        }
                        HorizontalDivider(color = AppColors.DividerDark, modifier = Modifier.width(tableWidth))
                    }
                    Spacer(Modifier.height(24.dp))
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
                }) { Text("Clear", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
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
            reportIcon = "📈",
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
                    SectionCard("💰", "Totals", AppColors.Green) {
                        KeyVal("Calls", "${g.totalCalls}")
                        KeyVal("Tokens", formatCompactNumber(tokens))
                        KeyVal("Cost", money(g.totalCost), AppColors.Green)
                        val avg = if (g.totalCalls > 0) g.totalCost / g.totalCalls else 0.0
                        KeyVal("Avg / call", money(avg), AppColors.TextSecondary)
                        KeyVal("Distinct models", "${g.models.size}")
                    }
                }
                item {
                    SectionCard("🏷️", "By type", AppColors.Indigo) {
                        byKind.entries.sortedByDescending { e -> e.value.sumOf { it.totalCost } }.forEach { (kind, rows) ->
                            val calls = rows.sumOf { it.stat.callCount }
                            val cost = rows.sumOf { it.totalCost }
                            KeyVal(kindLabel(kind), "$calls calls · ${money(cost)}")
                        }
                    }
                }
                item {
                    SectionCard("📐", "By pricing source", AppColors.Purple) {
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
                            Text(money(swc.totalCost), fontSize = 13.sp, color = AppColors.Green)
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

/** Display label for a usage call-kind. */
private fun kindLabel(kind: String): String = when (kind) {
    "report" -> "Report"
    "rerank" -> "Rerank"
    "summarize" -> "Summarize"
    "compare" -> "Compare"
    "moderation" -> "Moderation"
    "translate" -> "Translate"
    "title" -> "Title"
    else -> kind.replaceFirstChar { it.uppercase() }
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
            reportIcon = "📈",
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

@Composable
private fun LiveActivitySection(
    caps: ApiCallCaps.Snapshot,
    thrFanOut: Set<String>, thrMeta: Set<String>,
) {
    val (statusWord, statusColor) = when {
        caps.globalInFlight == 0 -> "Idle" to AppColors.TextDim
        caps.globalInFlight >= caps.globalMax -> "Saturated" to AppColors.Red
        else -> "Active" to AppColors.Green
    }
    SectionCard("🟢", "Live activity", statusColor) {
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
        // Bars fill to permits in use (cap saturation).
        CapBar("Global", caps.globalInFlight, caps.globalMax)
        CapBar("Report", caps.reportInFlight, caps.reportMax)
        CapBar("Translation", caps.translationInFlight, caps.translationMax)
        CapBar("Fan-out", caps.fanOutInFlight, caps.fanOutMax)
        CapBar("Fan-meta", caps.fanMetaInFlight, caps.fanMetaMax)

        val throttled = thrFanOut.size + thrMeta.size
        if (throttled > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Throttled — waiting on a provider rate-limit", fontSize = 11.sp, color = AppColors.Orange)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (thrFanOut.isNotEmpty()) StatChip("🌫️", "Fan-out", thrFanOut.size, AppColors.Orange)
                if (thrMeta.isNotEmpty()) StatChip("🪄", "Fan-meta", thrMeta.size, AppColors.Orange)
            }
        }
    }
}

@Composable
private fun ThrottleSection(hosts: List<ProviderThrottle.HostThrottleStat>) {
    SectionCard("🌐", "Provider throttle", AppColors.Blue) {
        if (hosts.isEmpty()) {
            Text("Idle — no active hosts.", fontSize = 12.sp, color = AppColors.TextTertiary)
        } else {
            val windowCap = NetworkSettings.maxCallsPerProviderPerMinute
            hosts.forEach { h ->
                val concColor = when {
                    h.free == 0 -> AppColors.Red
                    h.inUse > 0 -> AppColors.Orange
                    else -> AppColors.Green
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(h.host, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        Text(
                            "con ${h.inUse}/${h.limit}  ·  min ${h.windowCount}/$windowCap",
                            fontSize = 11.sp, color = concColor
                        )
                    }
                    Bar(if (h.limit > 0) h.inUse.toFloat() / h.limit else 0f, concColor)
                }
            }
        }
    }
}

@Composable
private fun CooldownSection(active: Map<String, Long>, now: Long) {
    SectionCard("❄️", "Model cooldowns", AppColors.Orange) {
        active.entries.sortedBy { it.value }.take(12).forEach { (key, until) ->
            val provider = key.substringBefore(":")
            val model = key.substringAfter(":")
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$provider · $model", fontSize = 12.sp, color = Color.White, maxLines = 1)
                Text(fmtDuration(until - now) + " left", fontSize = 12.sp, color = AppColors.Orange, fontWeight = FontWeight.Medium)
            }
        }
        if (active.size > 12) {
            Text("+ ${active.size - 12} more", fontSize = 11.sp, color = AppColors.TextTertiary)
        }
    }
}

@Composable
private fun TestRunSection(run: ModelTestRunState, now: Long) {
    val finished = run.doneCount + run.errorCount
    SectionCard("🧪", "Test all models", AppColors.Purple) {
        Bar(if (run.total > 0) finished.toFloat() / run.total else 0f, AppColors.Purple)
        Spacer(Modifier.height(6.dp))
        KeyVal("Progress", "$finished / ${run.total}")
        KeyVal("Passed", "${run.doneCount}", AppColors.Green)
        KeyVal("Failed", "${run.errorCount}", if (run.errorCount > 0) AppColors.Red else Color.White)
        KeyVal("Running", "${run.runningCount}", AppColors.Orange)
        KeyVal("Queued", "${run.queuedCount}")
        KeyVal("Cost", money(run.totalCost), AppColors.Green)
        KeyVal("Elapsed", fmtDuration(now - run.startedAt))
    }
}

@Composable
private fun HealthSection(logErr: String?, droppedLines: Long, traceCount: Int, busy: Boolean) {
    SectionCard("🩺", "System health", AppColors.Green) {
        KeyVal("Log writer", if (logErr == null) "OK" else "ERROR", if (logErr == null) AppColors.Green else AppColors.Red)
        if (logErr != null) Text(logErr, fontSize = 11.sp, color = AppColors.Red)
        KeyVal("Dropped log lines", "$droppedLines", if (droppedLines > 0) AppColors.Orange else Color.White)
        KeyVal("Trace files", "$traceCount")
        KeyVal("API activity", if (busy) "active" else "idle", if (busy) AppColors.Green else AppColors.TextDim)
        KeyVal("Streaming timeout", "${NetworkSettings.streamingReadTimeoutSec}s")
        KeyVal("Non-streaming timeout", "${NetworkSettings.nonStreamingReadTimeoutSec}s")
        KeyVal("Per-minute cap / host", "${NetworkSettings.maxCallsPerProviderPerMinute}")
    }
}

// =====================================================================
// Aggregate sections
// =====================================================================

@Composable
private fun ReportsSection(rs: ReportStats) {
    SectionCard("📋", "Reports", AppColors.Blue) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatChip("📄", "Total", rs.total, Color.White)
            StatChip("⏳", "Running", rs.running, AppColors.Orange)
            StatChip("⚠️", "Problems", rs.problems, AppColors.Red)
            StatChip("✅", "Completed", rs.completed, AppColors.Green)
        }
        Spacer(Modifier.height(8.dp))
        KeyVal("Agent calls", "${rs.agentCalls}")
        val errRate = if (rs.agentCalls > 0) rs.erroredCalls * 100.0 / rs.agentCalls else 0.0
        KeyVal(
            "Error rate", String.format(Locale.US, "%.1f%%  (%d)", errRate, rs.erroredCalls),
            if (errRate >= 10.0) AppColors.Red else if (errRate > 0) AppColors.Orange else AppColors.Green
        )
        Bar(if (rs.agentCalls > 0) (rs.erroredCalls.toFloat() / rs.agentCalls) else 0f, AppColors.Red)
        if (rs.stopped > 0) KeyVal("Stopped agents", "${rs.stopped}", AppColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        KeyVal("Report spend", money(rs.spend), AppColors.Green)
    }
}

@Composable
private fun SecondariesSection(byKind: Map<SecondaryKind, Int>, metaByName: Map<String, Int>) {
    SectionCard("🔗", "Secondary results", AppColors.Indigo) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatChip("🔀", "Rerank", byKind[SecondaryKind.RERANK] ?: 0, AppColors.Orange)
            StatChip("🧩", "Meta", byKind[SecondaryKind.META] ?: 0, AppColors.Purple)
            StatChip("🛡️", "Moderation", byKind[SecondaryKind.MODERATION] ?: 0, AppColors.Red)
            StatChip("🌐", "Translate", byKind[SecondaryKind.TRANSLATE] ?: 0, AppColors.Blue)
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
    SectionCard("📚", "Knowledge", AppColors.Yellow) {
        KeyVal("Knowledge bases", "${d.kbCount}")
        KeyVal("Chunks", formatCompactNumber(d.kbChunks.toLong()))
        KeyVal("Indexed text", "${formatCompactNumber(d.kbChars)} chars")
        if (d.kbFailed > 0) KeyVal("Failed sources", "${d.kbFailed}", AppColors.Red)
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
    SectionCard("🧮", "Costs tiers", AppColors.Blue) {
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
    SectionCard("🏷️", "Pricing cache", AppColors.Purple) {
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
                            "🐞", fontSize = 12.sp,
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
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
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
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 16.sp)
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
        max > 0 && inFlight >= max -> AppColors.Red
        frac >= 0.6f -> AppColors.Orange
        inFlight > 0 -> AppColors.Green
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
    Row(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceDark)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Text("$count", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = AppColors.TextSecondary)
    }
}

@Composable
private fun KeyVal(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary)
        Text(value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

/** KeyVal that's tappable — label left, count right, a faint "›" to
 *  signal the row drills into a filtered API-trace list. */
@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f),
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

/** Always-4-decimal money for the Spend & usage table (per the screen's
 *  fixed-precision requirement), regardless of magnitude. */
private fun money4(v: Double): String = String.format(Locale.US, "$%.4f", v)

/** "1:05", "12m", "3h 20m" — compact remaining/elapsed. */
private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 600 -> "${s / 60}m"
        else -> String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
