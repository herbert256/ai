package com.ai.ui.report.view

import com.ai.data.barTitle
import com.ai.ui.report.manage.view.*
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.report.view.helpers.viewBodySwipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    // Title-bar swipe targets — `currentReportId` shadows the prop so
    // a successful swipe can hot-swap the displayed report without
    // unmounting the screen. `rememberSaveable(reportId)` re-seeds on
    // any parent-driven prop change.
    var currentReportId by rememberSaveable(reportId) { mutableStateOf(reportId) }
    val reportIdsList = com.ai.ui.shared.LocalReportIdsNewestFirst.current
    val switchReport = com.ai.ui.shared.LocalReportSwitchHandler.current
    val reportState = produceState<Report?>(initialValue = null, currentReportId) {
        value = withContext(Dispatchers.IO) { com.ai.ui.report.view.helpers.ViewReportCache.get(context, currentReportId) }
    }
    val report = reportState.value

    // Mode toggle hoisted above the Column so the body swipe can flip it.
    var mode by rememberSaveable { mutableStateOf(CostsMode.Buckets) }
    val flipMode = { mode = if (mode == CostsMode.Buckets) CostsMode.Models else CostsMode.Buckets }
    // Drill state: l2Key = the L1 row tapped (a bucket key in Buckets
    // mode, a model key in Models mode); l3Key = the L2 row tapped (the
    // cross dimension). Reset whenever the displayed report changes.
    var l2Key by rememberSaveable(currentReportId) { mutableStateOf<String?>(null) }
    var l3Key by rememberSaveable(currentReportId) { mutableStateOf<String?>(null) }

    // 🔧 → Manage's per-agent ReportsViewer scrolled to its Costs
    // section. Hoisted so L1 / L2 / L3 all carry the same manage jump.
    val openManage = com.ai.ui.shared.LocalOpenManage.current
    val onOpenManageJump: (() -> Unit)? = openManage?.let { dispatch ->
        { dispatch(com.ai.ui.shared.ManageJump.ReportsViewer(null, "costs")) }
    }

    // Drill overlays (full-screen, layered above L1). Each carries its
    // own BackHandler so back steps L3 → L2 → L1 before leaving Costs.
    if (report != null && l2Key != null) {
        val drillData = rememberReportCostData(report)
        if (drillData != null) {
            val l2 = l2Key!!
            val l3 = l3Key
            if (l3 != null) {
                CostsDrillL3Screen(
                    reportTitle = report.barTitle, mode = mode, l2Key = l2, l3Key = l3,
                    data = drillData, onOpenManage = onOpenManageJump, onBack = { l3Key = null }
                )
                return
            }
            CostsDrillL2Screen(
                reportTitle = report.barTitle, mode = mode, l2Key = l2,
                data = drillData, onOpenManage = onOpenManageJump,
                onPick = { l3Key = it }, onBack = { l2Key = null }
            )
            return
        }
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            // Body swipe flips Buckets ⇄ Models (both directions, wraps).
            // The title-bar swipe still changes report.
            .viewBodySwipe(mode, onPrev = flipMode, onNext = flipMode)
    ) {
        ViewTitleBar(
            reportTitle = report?.barTitle,
            screenTitle = "Costs",
            subject = null,
            helpTopic = "costs_view",
            onOpenManage = onOpenManageJump,
            onBack = onBack,
            onSwipePrev = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Prev, ViewSwipeFilter.Any)
                if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
            },
            onSwipeNext = {
                val m = findSwipeMatch(context, reportIdsList, currentReportId, SwipeDirection.Next, ViewSwipeFilter.Any)
                if (m != null) { currentReportId = m.reportId; switchReport?.invoke(m.reportId); true } else false
            }
        )
        // (The "💰 Costs" duplicate-label row that used to sit
        // here was removed per the user — the orange "Costs"
        // screen-title in the bar above already names the page.)
        // Mode toggle — fancy pill that flips the bar block below
        // between bucket-roll-ups (the original behaviour) and
        // per-(provider, model) rows. (State hoisted above the Column.)
        CostsModeToggle(mode = mode, onPick = { mode = it })
        Spacer(modifier = Modifier.height(12.dp))
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
        // Per-model roll-up. Same shape as `bucketed` above so the
        // same BucketBar renderer paints both modes. Provider name
        // is dropped per the user's spec — when the same model is
        // served by multiple providers their costs collapse into one
        // row.
        val modeled = remember(data) {
            val byModel = LinkedHashMap<String, BucketTotal>()
            data.rows.forEach { row ->
                val key = com.ai.ui.shared.shortModelName(row.model)
                    .ifBlank { row.type }
                val total = row.inputCents + row.outputCents
                val cur = byModel[key]
                byModel[key] = if (cur == null) BucketTotal(key, total, 1)
                               else cur.copy(cents = cur.cents + total, calls = cur.calls + 1)
            }
            byModel.values
                .filter { it.cents > 0.0001 }
                .sortedByDescending { it.cents }
        }

        // Pick the active roll-up by mode. The hero card + the bar
        // list both bind to the same `active` list so flipping the
        // toggle just swaps datasets — no second composable tree.
        val active = if (mode == CostsMode.Buckets) bucketed else modeled
        val unitLabel = if (mode == CostsMode.Buckets) "bucket" else "model"

        // Hero total card — big cent readout in Yellow, with a subtle
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
                    // This screen deliberately omits the "costs from deleted
                    // items" line (see header doc), so the figure is the
                    // total for the *current* items only — label it as such
                    // so it doesn't read as the report's grand total (which
                    // the Manage bottom bar / HTML export include deleted in).
                    text = "Current items total",
                    color = AppColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Cents directly — the row-level cost halves already
            // live in this unit. ¢ suffix replaces the USD prefix
            // per the user's "show in cents, with the cent char"
            // request.
            Text(
                text = formatCentsValue(totalCents, decimals = 4),
                color = AppColors.Yellow,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${data.rows.size} call${if (data.rows.size == 1) "" else "s"} across ${active.size} $unitLabel${if (active.size == 1) "" else "s"}",
                color = AppColors.TextTertiary, fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (active.isEmpty()) {
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
            items(active) { b ->
                // Tap drills in: a bucket → its models, a model → its
                // buckets (resolved by `mode` on the L2 screen).
                BucketBar(bucket = b, totalCents = totalCents, onClick = { l2Key = b.key })
            }
        }
    }
}

/** Two roll-up modes — by cost-type bucket vs by (provider, model). */
private enum class CostsMode { Buckets, Models }

/** Fancy pill toggle between [CostsMode.Buckets] and [CostsMode.Models].
 *  Mirrors the per-row pill aesthetic used elsewhere in the View
 *  family — soft surface track, the active half fills with the
 *  Costs yellow, the inactive half stays ghosted. */
@Composable
private fun CostsModeToggle(mode: CostsMode, onPick: (CostsMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppColors.CardBackground)
            .border(1.dp, AppColors.Yellow.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CostsModeChip(label = "Buckets", selected = mode == CostsMode.Buckets,
            modifier = Modifier.weight(1f)) { onPick(CostsMode.Buckets) }
        CostsModeChip(label = "Models", selected = mode == CostsMode.Models,
            modifier = Modifier.weight(1f)) { onPick(CostsMode.Models) }
    }
}

@Composable
private fun CostsModeChip(
    label: String, selected: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val bg = if (selected) Brush.horizontalGradient(
        listOf(AppColors.Yellow.copy(alpha = 0.95f), AppColors.Orange.copy(alpha = 0.65f))
    ) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else AppColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/** Cents-as-a-value formatter — the row-level cost halves are stored
 *  in cents (Double), so this just rounds and appends the cent
 *  character. Mirrors the [com.ai.ui.shared.formatUsd] shape used
 *  elsewhere; lives here because no other screen uses the cent
 *  display today. */
private fun formatCentsValue(cents: Double, decimals: Int = 4): String =
    String.format(Locale.US, "%.${decimals}f ¢", cents)

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
    type == "model_title" -> "Model titles 🏷"
    type == "fan-out" -> "Fan-out 🌀"
    type == "fan-in" -> "Fan-in 🪢"
    type == "rerank" -> "Rerank 🏆"
    type == "moderation" -> "Moderation 🚩"
    type == "translate" -> "Translate 🌍"
    type == "meta" -> "Meta 🧠"
    else -> "${type.replaceFirstChar { it.titlecase() }} 🧠"
}

/** Per-bucket horizontal bar — bar length is fraction of total.
 *  [onClick] (when set) drills into the next level. */
@Composable
private fun BucketBar(bucket: BucketTotal, totalCents: Double, onClick: (() -> Unit)? = null) {
    val pct = if (totalCents > 0.0) bucket.cents / totalCents else 0.0
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.CardBackground)
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
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
                text = formatCentsValue(bucket.cents, decimals = 4),
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
            val frac = pct.coerceAtLeast(0.01).coerceAtMost(1.0)
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

/**
 * L2 drill. In Buckets mode [l2Key] is a bucket and the screen lists the
 * models inside it; in Models mode [l2Key] is a model and the screen
 * lists the buckets it appears in. Tapping a row drills to L3 for that
 * bucket+model pair.
 */
@Composable
private fun CostsDrillL2Screen(
    reportTitle: String?,
    mode: CostsMode,
    l2Key: String,
    data: ReportCostData,
    onOpenManage: (() -> Unit)?,
    onPick: (String) -> Unit,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val crossItems = remember(data, mode, l2Key) {
        val acc = LinkedHashMap<String, BucketTotal>()
        data.rows.forEach { row ->
            val rowBucket = bucketFor(row.type)
            val rowModel = com.ai.ui.shared.shortModelName(row.model).ifBlank { row.type }
            val matches = if (mode == CostsMode.Buckets) rowBucket == l2Key else rowModel == l2Key
            if (!matches) return@forEach
            val key = if (mode == CostsMode.Buckets) rowModel else rowBucket
            val total = row.inputCents + row.outputCents
            val cur = acc[key]
            acc[key] = if (cur == null) BucketTotal(key, total, 1)
                       else cur.copy(cents = cur.cents + total, calls = cur.calls + 1)
        }
        acc.values.filter { it.cents > 0.0001 }.sortedByDescending { it.cents }
    }
    val drillTotal = crossItems.sumOf { it.cents }
    val crossLabel = if (mode == CostsMode.Buckets) "model" else "bucket"
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewTitleBar(
            reportTitle = reportTitle, screenTitle = "Costs",
            subject = l2Key, helpTopic = "costs_view",
            onOpenManage = onOpenManage, onBack = onBack
        )
        Text(
            text = "${crossItems.size} $crossLabel${if (crossItems.size == 1) "" else "s"} · ${formatCentsValue(drillTotal, 4)}",
            color = AppColors.TextTertiary, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (crossItems.isEmpty()) {
            Text("No billable calls.", color = AppColors.TextTertiary, fontSize = 13.sp)
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            items(crossItems) { b ->
                BucketBar(bucket = b, totalCents = drillTotal, onClick = { onPick(b.key) })
            }
        }
    }
}

/**
 * L3 drill. Every individual call for one bucket+model combination,
 * paged to fit the screen height — swipe left/right to move between
 * pages.
 */
@Composable
private fun CostsDrillL3Screen(
    reportTitle: String?,
    mode: CostsMode,
    l2Key: String,
    l3Key: String,
    data: ReportCostData,
    onOpenManage: (() -> Unit)?,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val bucketKey = if (mode == CostsMode.Buckets) l2Key else l3Key
    val modelKey = if (mode == CostsMode.Buckets) l3Key else l2Key
    val entries = remember(data, bucketKey, modelKey) {
        data.rows.filter {
            bucketFor(it.type) == bucketKey &&
                com.ai.ui.shared.shortModelName(it.model).ifBlank { it.type } == modelKey
        }
    }
    val entriesTotal = entries.sumOf { it.inputCents + it.outputCents }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewTitleBar(
            reportTitle = reportTitle, screenTitle = "Costs",
            subject = null, helpTopic = "costs_view",
            onOpenManage = onOpenManage, onBack = onBack
        )
        // Bucket + model named once, as two green lines — every card
        // below shares them, so they're not repeated per card.
        Text(
            text = bucketKey, color = AppColors.Green, fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            text = modelKey, color = AppColors.Green, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (entries.isEmpty()) {
            Text("No calls.", color = AppColors.TextTertiary, fontSize = 13.sp)
            return@Column
        }
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val perPage = (maxHeight.value / 52f).toInt().coerceAtLeast(1)
            val pages = entries.chunked(perPage)
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pages.size })
            Column(modifier = Modifier.fillMaxSize()) {
                // "N calls · total" left-aligned; page indicator right.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${entries.size} call${if (entries.size == 1) "" else "s"} · ${formatCentsValue(entriesTotal, 4)}",
                        color = AppColors.TextTertiary, fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (pages.size > 1) {
                        Text(
                            text = "Page ${pagerState.currentPage + 1} / ${pages.size}  ·  swipe ⇄",
                            color = AppColors.TextTertiary, fontSize = 11.sp,
                            textAlign = TextAlign.End
                        )
                    }
                }
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pages[page].forEach { row -> CostEntryRow(row) }
                    }
                }
            }
        }
    }
}

/** One raw call on the L3 page. Bucket + model are named once at the
 *  top of the screen, so each card is a single compact line: the token
 *  / tier detail on the left, the call's cost on the right. */
@Composable
private fun CostEntryRow(row: CostRow) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.CardBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "in ${row.inputTokens} / out ${row.outputTokens} tok" +
                (row.tier.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            color = AppColors.TextTertiary, fontSize = 12.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatCentsValue(row.inputCents + row.outputCents, 4),
            color = AppColors.Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
    }
}
