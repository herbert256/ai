package com.ai.ui.report.info

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStatus
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResult
import com.ai.data.SecondaryResultStorage
import com.ai.ui.report.manage.view.rememberReportCostData
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone, read-only **Report information** screen — a real Navigation
 * destination (not the full-screen overlay pattern the rest of Manage/View
 * uses), reached from the ℹ️ icon on the "Manage an AI report" hub. Pulls
 * together as much as possible about one report: its titles / icon /
 * language, when it was created vs last changed, and roll-up stats (API
 * calls, total API time, total cost, distinct models, tokens).
 *
 * Total cost reuses [rememberReportCostData] so it always matches the
 * "Report - costs" screen; the call count / models / tokens come from the
 * same aggregator. Total API time sums every persisted `durationMs`
 * (agents + secondaries + icon-chain + the report-level metadata calls).
 */
@Composable
fun ReportInfoScreen(
    reportId: String,
    onBack: () -> Unit,
    onOpenTrace: (String) -> Unit = {},
    onOpenModelInfo: (provider: String, model: String) -> Unit = { _, _ -> }
) {
    BackHandler { onBack() }
    val context = LocalContext.current

    val report by produceState<Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val secondaries by produceState(initialValue = emptyList<SecondaryResult>(), reportId) {
        value = withContext(Dispatchers.IO) { SecondaryResultStorage.listForReport(context, reportId) }
    }
    val r = report

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "report_info",
            title = "Report information",
            subject = r?.title?.takeIf { it.isNotBlank() } ?: "Everything we know about this report",
            onBackClick = onBack
        )

        if (r == null) {
            Spacer(Modifier.height(24.dp))
            Text("Report not found.", color = AppColors.TextTertiary, fontSize = 14.sp)
            return@Column
        }

        // Total cost from the shared aggregator → matches Report - costs.
        val costData = rememberReportCostData(r)
        val totalCents = costData?.let { it.totalInC + it.totalOutC + it.deletedCents } ?: 0.0
        val apiCalls = costData?.rows?.size ?: 0
        val modelCount = costData?.byModel?.size ?: 0
        val inTokens = costData?.rows?.sumOf { it.inputTokens } ?: 0
        val outTokens = costData?.rows?.sumOf { it.outputTokens } ?: 0
        val totalDurationMs = totalApiDurationMs(r, secondaries)

        val dateFmt = remember { SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()) }
        val createdAt = r.createdAt.takeIf { it > 0L } ?: r.timestamp
        val errorCount = r.agents.count { it.reportStatus == ReportStatus.ERROR }
        val status = when {
            r.completedAt != null && errorCount == 0 -> "Completed"
            r.completedAt != null -> "Completed · $errorCount error${if (errorCount == 1) "" else "s"}"
            else -> "In progress / interrupted"
        }
        val secKinds = secondaries.groupingBy { it.kind.name.lowercase() }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.value} ${it.key}" }
            .ifBlank { "none" }

        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))

            // 🐞 next to AI-retrieved fields → the API trace that produced
            // them (only when a trace filename was captured).
            Section("Identity")
            InfoRow("Icon", (r.icon?.takeIf { it.isNotBlank() } ?: "—"),
                traceFile = r.iconTraceFile, onTrace = onOpenTrace)
            InfoRow("Short title", r.title.ifBlank { "—" },
                traceFile = r.titleTraceFile, onTrace = onOpenTrace)
            InfoRow("Long title", r.titleLong?.takeIf { it.isNotBlank() } ?: "—",
                traceFile = r.titleTraceFile, onTrace = onOpenTrace)
            InfoRow("Language", buildString {
                append(r.languageName?.takeIf { it.isNotBlank() } ?: "—")
                r.languageIcon?.takeIf { it.isNotBlank() }?.let { append("  $it") }
            }, traceFile = r.languageTraceFile, onTrace = onOpenTrace)
            InfoRow("Report id", r.id, dim = true)

            Section("Timing")
            InfoRow("Created", dateFmt.format(Date(createdAt)))
            InfoRow("Last changed", dateFmt.format(Date(r.timestamp)))
            InfoRow("Status", status)

            Section("Totals")
            InfoRow("API calls", apiCalls.toString())
            InfoRow("Total API time", formatDuration(totalDurationMs))
            InfoRow("Total cost", formatCents(totalCents, 2))
            InfoRow("Models used", modelCount.toString())
            InfoRow("Tokens", "$inTokens in · $outTokens out")

            Section("Composition")
            InfoRow("Model reports", r.agents.size.toString())
            InfoRow("Secondary results", secKinds)
            InfoRow("Pinned", if (r.pinned) "Yes" else "No")

            if (!costData?.byModel.isNullOrEmpty()) {
                Section("By model")
                costData!!.byModel.forEach { g ->
                    val model = g.model
                    val provider = g.provider
                    InfoRow(
                        if (model != null) shortModelName(model) else g.key,
                        "${g.calls} call${if (g.calls == 1) "" else "s"} · ${formatCents(g.inputCents + g.outputCents, 2)}",
                        onClick = if (provider != null && model != null) {
                            { onOpenModelInfo(provider, model) }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    Text(title, color = AppColors.Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    dim: Boolean = false,
    traceFile: String? = null,
    onTrace: ((String) -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val hasTrace = !traceFile.isNullOrBlank() && onTrace != null
    Row(
        modifier = Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = AppColors.TextTertiary, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
        Text(
            value,
            color = if (dim) AppColors.TextTertiary else androidx.compose.ui.graphics.Color.White,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
            modifier = Modifier.weight(1f)
        )
        if (hasTrace) {
            Text(
                "🐞", fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp).clickable { onTrace!!(traceFile!!) }
            )
        }
    }
}

/** Sum of every persisted per-call duration on the report — agents,
 *  secondaries, the 3-tier icon chain, and the report-level metadata
 *  calls (icon / language / language-icon / title / per-model title /
 *  fan-out pair title). Calls with no recorded duration contribute 0. */
internal fun totalApiDurationMs(report: Report, secondaries: List<SecondaryResult>): Long {
    var ms = 0L
    report.agents.forEach {
        ms += it.durationMs ?: 0L
        ms += it.modelTitleDurationMs ?: 0L
    }
    report.iconCalls.forEach { ms += it.durationMs ?: 0L }
    ms += report.iconDurationMs ?: 0L
    ms += report.languageDurationMs ?: 0L
    ms += report.languageIconDurationMs ?: 0L
    ms += report.titleDurationMs ?: 0L
    secondaries.forEach {
        ms += it.durationMs ?: 0L
        ms += it.titleDurationMs ?: 0L
    }
    return ms
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    if (ms < 1000L) return "$ms ms"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return when {
        h > 0L -> "${h}h ${m}m ${s}s"
        m > 0L -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
