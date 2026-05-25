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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiCallCaps
import com.ai.data.ApiTracer
import com.ai.data.AppLog
import com.ai.data.AppService
import com.ai.data.KnowledgeData
import com.ai.data.ModelCooldownStore
import com.ai.data.ModelTestRunState
import com.ai.data.NetworkSettings
import com.ai.data.PricingCache
import com.ai.data.ProviderModelData
import com.ai.data.ProviderThrottle
import com.ai.data.ReportSectionData
import com.ai.data.ReportStats
import com.ai.data.SecondaryKind
import com.ai.data.UsageGroupsResult
import com.ai.data.computeKnowledgeStats
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
 *  - [AiStatisticsScreen] — a hub of **lifetime aggregate** pages. Knowledge
 *    totals inline; Reports/secondaries ([AiStatReportsScreen]),
 *    providers/models ([AiStatProvidersScreen]), spend & usage
 *    ([AiSpendUsageScreen]) and cost tiers ([AiCostsTierScreen]) each on their
 *    own page so they compute only when opened.
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
    val thrIcons by appViewModel.throttledFanIconsPairs.collectAsState()
    val thrTitles by appViewModel.throttledFanTitlesPairs.collectAsState()
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
            title = "AI Live Dashboard",
            subject = "What's happening right now",
            onBackClick = onBack,
            reportIcon = "📡", reportIconGoesHome = true
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item { LiveActivitySection(caps, thrFanOut, thrIcons, thrTitles) }
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

@Composable
fun AiStatisticsScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToSpendUsage: () -> Unit = {},
    onNavigateToCostsTier: () -> Unit = {},
    onNavigateToReports: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
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
            title = "AI Statistics",
            subject = "Lifetime totals",
            onBackClick = onBack,
            reportIcon = "📈", reportIconGoesHome = true,
            onHousekeeping = onHousekeeping
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { LinkCard("📋", "Statistics - Reports", "Reports + secondary results totals", onNavigateToReports) }
            item { LinkCard("🔌", "Statistics - Providers / Models", "Providers, models and catalog freshness", onNavigateToProviders) }
            item { LinkCard("💰", "Spend & usage", "Calls, tokens and cost per provider", onNavigateToSpendUsage) }
            item { LinkCard("🧮", "Costs tiers", "Pricing tier per model + catalog freshness", onNavigateToCostsTier) }
            kb?.let { if (it.kbCount > 0) item { KnowledgeSection(it) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Statistics - Reports — report + secondary-result lifetime totals. */
@Composable
fun AiStatReportsScreen(
    reportViewModel: ReportViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
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
            title = "Statistics - Reports",
            subject = "Reports and secondary results",
            onBackClick = onBack,
            reportIcon = "📋", reportIconGoesHome = true
        )
        val d = data
        if (d == null) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item { ReportsSection(d.reports) }
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
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val uiState by appViewModel.uiState.collectAsState()
    val refreshTick = resumeRefreshTick()
    val data by produceState<ProviderModelData?>(null, refreshTick) {
        value = computeProviderModelStats(context, uiState.aiSettings)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "ai_stat_providers",
            title = "Statistics - Providers / Models",
            subject = "Providers, models and catalog freshness",
            onBackClick = onBack,
            reportIcon = "🔌", reportIconGoesHome = true
        )
        val d = data
        if (d == null) {
            Text("Loading…", color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                item { ProvidersSection(d) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Spend & usage — own screen (per-model getPricing). Reached from AI
 *  Statistics. Computes its breakdown only on open. */
@Composable
fun AiSpendUsageScreen(
    openRouterApiKey: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
    onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
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
    var expandedProvidersList by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }

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
            reportIcon = "💰", reportIconGoesHome = true,
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
                        Text("Cost: ${money(d.totalCost)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppColors.Green)
                        Text("Pricing: ${d.pricingStats}", fontSize = 10.sp, color = AppColors.TextTertiary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(d.groups, key = { it.provider.id }) { group ->
                        val isExpanded = group.provider.id in expandedProvidersList
                        UsageProviderCard(
                            group = group,
                            isExpanded = isExpanded,
                            onToggle = {
                                expandedProvidersList =
                                    if (isExpanded) expandedProvidersList - group.provider.id
                                    else expandedProvidersList + group.provider.id
                            },
                            onModelClick = { model -> onNavigateToModelInfo(group.provider, model) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
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

/** Costs tier — own screen (per-model getPricing for the whole catalog).
 *  Reached from AI Statistics; computes only on open. */
@Composable
fun AiCostsTierScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateHome: () -> Unit,
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
            reportIcon = "🧮", reportIconGoesHome = true
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
            pricing?.let { cats -> item { PricingSection(cats) } }
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
    thrFanOut: Set<String>, thrIcons: Set<String>, thrTitles: Set<String>,
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
        CapBar("Fan-icons", caps.fanIconsInFlight, caps.fanIconsMax)
        CapBar("Fan-titles", caps.fanTitlesInFlight, caps.fanTitlesMax)

        val throttled = thrFanOut.size + thrIcons.size + thrTitles.size
        if (throttled > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Throttled — waiting on a provider rate-limit", fontSize = 11.sp, color = AppColors.Orange)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (thrFanOut.isNotEmpty()) StatChip("🌫️", "Fan-out", thrFanOut.size, AppColors.Orange)
                if (thrIcons.isNotEmpty()) StatChip("🎨", "Icons", thrIcons.size, AppColors.Orange)
                if (thrTitles.isNotEmpty()) StatChip("🏷️", "Titles", thrTitles.size, AppColors.Orange)
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
private fun ProvidersSection(d: ProviderModelData) {
    SectionCard("🔌", "Providers & models", AppColors.Indigo) {
        KeyVal("Providers configured", "${d.providersConfigured}")
        KeyVal("With API key", "${d.providersWithKey}", AppColors.Green)
        KeyVal("Models (total)", "${d.totalModels}")
        KeyVal("Model lists cached", "${d.modelsCached}")
        if (d.modelCacheStale > 0) KeyVal("Stale (>7d)", "${d.modelCacheStale}", AppColors.Orange)
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
private fun PricingSection(catalogStats: List<com.ai.data.PricingCache.CatalogStat>) {
    SectionCard("🏷️", "Pricing cache", AppColors.Purple) {
        // Header
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text("Source", fontSize = 10.sp, color = AppColors.TextTertiary, modifier = Modifier.weight(1.5f))
            Text("Entries", fontSize = 10.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
            Text("Retrieved", fontSize = 10.sp, color = AppColors.TextTertiary, textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
        }
        catalogStats.forEach { c ->
            val dim = c.entries == 0
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, fontSize = 12.sp, color = if (dim) AppColors.TextDim else Color.White, maxLines = 1, modifier = Modifier.weight(1.5f))
                Text("${c.entries}", fontSize = 12.sp, color = if (dim) AppColors.TextDim else AppColors.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
                Text(
                    fmtFetched(c.fetchedAt), fontSize = 11.sp,
                    color = if (c.fetchedAt == 0L) AppColors.TextDim else AppColors.TextSecondary,
                    textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.weight(1.2f)
                )
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

/** Tappable card that links to a sub-screen (used by AI Statistics for the
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
private fun SectionCard(emoji: String, title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
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

private fun money(v: Double): String =
    if (v > 0 && v < 0.01) String.format(Locale.US, "$%.6f", v)
    else String.format(Locale.US, "$%.4f", v)

/** "1:05", "12m", "3h 20m" — compact remaining/elapsed. */
private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 600 -> "${s / 60}m"
        else -> String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
