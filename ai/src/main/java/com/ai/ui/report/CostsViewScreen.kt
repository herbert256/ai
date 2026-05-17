package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ViewScreenTitleBar
import com.ai.ui.shared.formatUsd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Content-focused "View" variant of the report cost breakdown. Reached
 * from the Costs tile on Report - view; the management-heavy
 * [ReportsViewerScreen] (initialSection = "costs") path remains for
 * everything in Report - manage.
 *
 * Fancy layout choice: a giant "💰 Total" hero card at the top, then a
 * stacked block of horizontal-bar rows — one per cost bucket (Reports
 * / Meta / Fan-out / Fan-in / Translate / Moderation / Rerank /
 * Icons / Language). Each row's bar length is proportional to that
 * bucket's share of the grand total, so a glance shows where spend
 * went; the row also surfaces the bucket's percentage and absolute
 * dollar amount. Tiny zero-cost buckets are skipped entirely so the
 * list reads as the actual spending profile rather than the catalog.
 *
 * Deliberately omitted: sortable columns, the "All API calls"
 * drill-in, per-row tap popups, the per-row trace icon, the deleted-
 * items line item (the user goes back to Report - manage when they
 * want to inspect / forensically attribute individual calls).
 */
@Composable
fun CostsViewScreen(
    reportId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val reportState = produceState<Report?>(initialValue = null, reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, reportId) }
    }
    val report = reportState.value

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewScreenTitleBar(
            reportTitle = report?.title,
            screenTitle = "Costs - view",
            subject = null,
            helpTopic = "costs_view",
            onBack = onBack
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "💰",
                fontSize = 28.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "Costs",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Yellow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (report == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    "Loading…",
                    color = AppColors.TextTertiary, fontSize = 14.sp
                )
            }
            return@Column
        }
        // rememberReportCostData is composable but does its own IO via
        // produceState; we delegate the heavy lifting to it so a future
        // change to the cost-aggregation logic flows through both
        // screens with no drift.
        val data = rememberReportCostData(report)
        if (data == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CardBackground)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "💰", fontSize = 40.sp)
                    Text(
                        text = "No cost data yet",
                        color = AppColors.TextPrimary, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Run the report (or a follow-up Meta / Translate / Fan-out) to populate the spend breakdown.",
                        color = AppColors.TextTertiary,
                        fontSize = 13.sp, lineHeight = 18.sp
                    )
                }
            }
            return@Column
        }

        val totalCents = data.totalInC + data.totalOutC
        // Collapse the row-level granular labels into a small set of
        // human-readable buckets. Icon variants all collapse to "Icons"
        // (matching the by-type roll-up rule used by ReportCostTable);
        // language detect + language icon collapse to "Language".
        val bucketed = remember(data) {
            val buckets = LinkedHashMap<String, BucketTotal>()
            data.rows.forEach { row ->
                val key = bucketFor(row.type)
                val total = row.inputCents + row.outputCents
                val cur = buckets[key]
                if (cur == null) {
                    buckets[key] = BucketTotal(key, total, 1)
                } else {
                    buckets[key] = cur.copy(cents = cur.cents + total, calls = cur.calls + 1)
                }
            }
            buckets.values
                .filter { it.cents > 0.0001 }
                .sortedByDescending { it.cents }
        }

        // Hero total card — big USD readout in Yellow, with a subtle
        // gradient so it visually dominates without competing with the
        // bar block below.
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(AppColors.Yellow.copy(alpha = 0.32f), AppColors.Yellow.copy(alpha = 0.06f))
                    )
                )
                .border(1.dp, AppColors.Yellow.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "💰", fontSize = 30.sp, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = "Total",
                    color = AppColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Cents to dollars for the hero — formatUsd takes dollars.
            Text(
                text = formatUsd(totalCents / 100.0, decimals = 6),
                color = AppColors.Yellow,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${data.rows.size} call${if (data.rows.size == 1) "" else "s"} across ${bucketed.size} bucket${if (bucketed.size == 1) "" else "s"}",
                color = AppColors.TextTertiary, fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (bucketed.isEmpty()) {
            Text(
                "No billable calls recorded.",
                color = AppColors.TextTertiary, fontSize = 13.sp
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            items(bucketed) { b ->
                BucketBar(bucket = b, totalCents = totalCents)
            }
        }
    }
}

/** Roll-up label for one cost bucket on the Costs view screen. */
private data class BucketTotal(val key: String, val cents: Double, val calls: Int)

/** Map a [CostRow.type] to a human-readable bucket label + emoji. The
 *  bucket order in the LinkedHashMap is the iteration order of the
 *  rows (which the rest of the aggregator sorted by absolute spend),
 *  and we re-sort by spend after grouping anyway. */
private fun bucketFor(type: String): String = when {
    type == "report" -> "Reports 📊"
    type.startsWith("icon_") -> "Icons 🖼"
    type == "language" -> "Language 🌐"
    type == "fan-out" -> "Fan-out 🌀"
    type == "fan-in" -> "Fan-in 🪢"
    type == "rerank" -> "Rerank 🏆"
    type == "moderation" -> "Moderation 🚩"
    type == "translate" -> "Translate 🌍"
    type == "meta" -> "Meta 🧠"
    else -> "${type.replaceFirstChar { it.titlecase() }} 🧠"
}

/** Per-bucket horizontal bar — bar length is fraction of total. */
@Composable
private fun BucketBar(bucket: BucketTotal, totalCents: Double) {
    val pct = if (totalCents > 0.0) bucket.cents / totalCents else 0.0
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = bucket.key,
                color = AppColors.TextPrimary, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${(pct * 100).toInt()}%",
                color = AppColors.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = formatUsd(bucket.cents / 100.0, decimals = 6),
                color = AppColors.TextSecondary, fontSize = 13.sp
            )
        }
        // Bar — full-width track with the filled portion as a Yellow
        // gradient. Minimum visible fill at 1% so a sub-percent bucket
        // still surfaces a sliver rather than vanishing entirely.
        Box(
            modifier = Modifier.fillMaxWidth().height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color(0x33000000))
        ) {
            val frac = pct.coerceAtLeast(0.01f.toDouble()).coerceAtMost(1.0)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = frac.toFloat())
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(AppColors.Yellow.copy(alpha = 0.95f), AppColors.Orange.copy(alpha = 0.65f))
                        )
                    )
            )
        }
        Text(
            text = "${bucket.calls} call${if (bucket.calls == 1) "" else "s"}",
            color = AppColors.TextTertiary, fontSize = 11.sp
        )
    }
}
