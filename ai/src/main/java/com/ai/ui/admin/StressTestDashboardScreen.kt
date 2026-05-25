package com.ai.ui.admin

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiCallCaps
import com.ai.data.ProviderThrottle
import com.ai.data.StressDashboardData
import com.ai.data.computeStressDashboard
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.resumeRefreshTick
import com.ai.ui.shared.shortModelName
import com.ai.viewmodel.StressTestEngine
import java.util.Locale

/** Housekeeping → Test → Stress test → (auto-opens here on Start).
 *  Live, information-rich view of the stress run(s) this session — scoped to
 *  the reports the run launched (cumulative across stacked runs). The one
 *  app-wide section is the live concurrency/throttle strip, since those caps
 *  are global. The 📡 icon jumps to the (global) Live Dashboard. */
@Composable
fun StressTestDashboardScreen(
    engine: StressTestEngine,
    onBack: () -> Unit,
    onNavigateToLiveDashboard: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val tracked by engine.tracked.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }

    // Fast ticker (cheap in-memory snapshots) — stops when off-screen.
    val liveTick by produceState(0) { while (true) { kotlinx.coroutines.delay(750); value++ } }
    val caps = remember(liveTick) { ApiCallCaps.snapshot() }
    val hosts = remember(liveTick) { ProviderThrottle.snapshot() }
    val now = remember(liveTick) { System.currentTimeMillis() }

    // Slower ticker for the disk-read report status + trace scan, off-main.
    val refreshTick = resumeRefreshTick()
    val slowTick by produceState(0) { while (true) { kotlinx.coroutines.delay(1200); value++ } }
    val data by produceState<StressDashboardData?>(null, refreshTick, slowTick, tracked) {
        value = computeStressDashboard(
            context, tracked.reportIds, tracked.runCount,
            tracked.firstStartedAt, tracked.lastStartedAt,
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "stress_test_dashboard",
            title = "Stress Dashboard",
            subject = "This session's stress runs, live",
            onBackClick = onBack,
            reportIcon = "📡",
            onReportIconClick = onNavigateToLiveDashboard,
            onDelete = if (tracked.reportIds.isNotEmpty()) ({ showResetConfirm = true }) else null,
        )

        if (tracked.reportIds.isEmpty()) {
            EmptyState()
        } else {
            val d = data
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                // ----- 1. Stress runs (scoped progress) -----
                item {
                    SectionCard("🔥", "Stress runs", AppColors.Orange) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🚀", "runs", d?.runCount ?: tracked.runCount, AppColors.Orange)
                            StatChip("📄", "reports", tracked.reportIds.size, Color.White)
                            StatChip("✅", "done", d?.done ?: 0, AppColors.Green)
                            StatChip("⏳", "running", d?.running ?: 0, AppColors.Blue)
                            StatChip("❗", "problems", d?.withProblems ?: 0, AppColors.Red)
                        }
                        Spacer(Modifier.height(8.dp))
                        val total = tracked.reportIds.size.coerceAtLeast(1)
                        Bar((d?.done ?: 0).toFloat() / total, AppColors.Green)
                        Spacer(Modifier.height(6.dp))
                        KeyVal("Cost (this run set)", money(d?.totalCost ?: 0.0), AppColors.Green)
                        if (d != null && d.resolved < d.tracked) {
                            KeyVal("Awaiting creation", "${d.tracked - d.resolved}", AppColors.TextDim)
                        }
                    }
                }

                // ----- 2. Throughput -----
                item {
                    SectionCard("📈", "Throughput", AppColors.Purple) {
                        val elapsedMs = if (tracked.firstStartedAt > 0) now - tracked.firstStartedAt else 0L
                        val done = d?.done ?: 0
                        val rpm = if (elapsedMs > 0) done * 60000.0 / elapsedMs else 0.0
                        KeyVal("Elapsed", fmtDuration(elapsedMs))
                        KeyVal("Calls / min (last 60s)", "${d?.callsLastMin ?: 0}", AppColors.Blue)
                        KeyVal("Reports / min", String.format(Locale.US, "%.1f", rpm))
                        KeyVal("Total scoped traces", "${d?.traceTotal ?: 0}")
                    }
                }

                // ----- 3. Calls by status -----
                item {
                    SectionCard("🚦", "Calls by status", AppColors.Green) {
                        if (d?.tracingEnabled == false) {
                            Text("API tracing is OFF — call breakdowns stay empty. Enable it in Settings.", fontSize = 11.sp, color = AppColors.Orange)
                            Spacer(Modifier.height(6.dp))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatChip("🟢", "2xx", d?.ok2xx ?: 0, AppColors.Green)
                            StatChip("⏱️", "429", d?.rate429 ?: 0, AppColors.Orange)
                            StatChip("🟡", "4xx", d?.client4xx ?: 0, AppColors.Yellow)
                            StatChip("🔴", "5xx", d?.server5xx ?: 0, AppColors.Red)
                            StatChip("⚫", "conn", d?.failed0 ?: 0, AppColors.Red)
                            StatChip("✂️", "partial", d?.partial ?: 0, AppColors.Orange)
                        }
                    }
                }

                // ----- 4. Per provider (this run) -----
                item {
                    SectionCard("🔌", "Per provider (this run)", AppColors.Blue) {
                        val rows = d?.byProvider.orEmpty()
                        if (rows.isEmpty()) Text("—", fontSize = 12.sp, color = AppColors.TextDim)
                        rows.take(20).forEach { (host, count) ->
                            val errs = d?.providerErrors?.get(host) ?: 0
                            KeyVal(host, if (errs > 0) "$count  ($errs err)" else "$count",
                                if (errs > 0) AppColors.Orange else Color.White)
                        }
                    }
                }

                // ----- 5. By category -----
                item {
                    SectionCard("🏷️", "By call category", AppColors.Yellow) {
                        val rows = d?.byCategory.orEmpty()
                        if (rows.isEmpty()) Text("—", fontSize = 12.sp, color = AppColors.TextDim)
                        rows.take(20).forEach { (cat, count) -> KeyVal(cat, "$count") }
                    }
                }

                // ----- 6. By model -----
                item {
                    SectionCard("🧠", "By model", AppColors.Purple) {
                        val rows = d?.byModel.orEmpty()
                        if (rows.isEmpty()) Text("—", fontSize = 12.sp, color = AppColors.TextDim)
                        rows.take(20).forEach { (model, count) -> KeyVal(shortModelName(model), "$count") }
                    }
                }

                // ----- 7. Recent errors -----
                item {
                    SectionCard("❗", "Recent errors", AppColors.Red) {
                        val errs = d?.recentErrors.orEmpty()
                        if (errs.isEmpty()) Text("None 🎉", fontSize = 12.sp, color = AppColors.Green)
                        errs.forEach { t ->
                            KeyVal(
                                "${t.hostname}  ${t.category ?: ""}".trim(),
                                "${t.statusCode}",
                                AppColors.Red,
                            )
                        }
                    }
                }

                // ----- 8. Live — app-wide concurrency / throttle -----
                item {
                    SectionCard("📡", "Live — app-wide", AppColors.Blue) {
                        Text("Concurrency caps are global — this reflects ALL current activity, not only the stress run.", fontSize = 11.sp, color = AppColors.TextTertiary)
                        Spacer(Modifier.height(8.dp))
                        CapBar("Global", caps.globalInFlight, caps.globalMax)
                        CapBar("Reports", caps.reportInFlight, caps.reportMax)
                        CapBar("Fan-out", caps.fanOutInFlight, caps.fanOutMax)
                        CapBar("Fan-icons", caps.fanIconsInFlight, caps.fanIconsMax)
                        CapBar("Fan-titles", caps.fanTitlesInFlight, caps.fanTitlesMax)
                        if (hosts.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Active hosts", fontSize = 11.sp, color = AppColors.TextTertiary)
                            hosts.take(12).forEach { h ->
                                KeyVal(h.host, "${h.inUse}/${h.limit} · ${h.windowCount}/min")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset dashboard tracking?") },
            text = { Text("Clears the counters for this session. Reports already launched keep generating in the background — only what this dashboard counts is emptied.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    engine.reset()
                    Toast.makeText(context, "Tracking reset", Toast.LENGTH_SHORT).show()
                }) { Text("Reset", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔥", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text("No stress runs yet this session", color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Start a stress test from Housekeeping → Test and this dashboard fills with live metrics.", fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

// ---------------------------------------------------------------------------
// Tiles — copied from AiDashboardScreen (private there); the user accepts the
// small duplication so the working Live Dashboard stays untouched.
// ---------------------------------------------------------------------------

@Composable
private fun SectionCard(emoji: String, title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackgroundAlt),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
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
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.SurfaceDark)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
private fun StatChip(emoji: String, label: String, count: Int, accent: Color) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceDark).padding(horizontal = 8.dp, vertical = 5.dp),
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
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

private fun money(v: Double): String =
    if (v > 0 && v < 0.01) String.format(Locale.US, "$%.6f", v)
    else String.format(Locale.US, "$%.4f", v)

private fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 600 -> "${s / 60}m"
        else -> String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}
