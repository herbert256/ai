package com.ai.ui.report.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.barTitle
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStatus
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.ui.helpers.parseRerankRows
import com.ai.ui.report.view.helpers.ViewReportCache
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------
// Value view — cost × quality (Pareto) frontier. Pure derivation from
// data already persisted: per-agent cost + the report's rerank scores.
// No API calls. Reached from the View hub's "Value view" tile (shown
// only when the report has a rerank).
// ---------------------------------------------------------------------

/** One model on the cost/quality plane. [costCents] = USD×100,
 *  [quality] = the rerank score (raw, model-scaled). */
private data class ValuePoint(
    val provider: String,
    val modelShort: String,
    val costCents: Double,
    val quality: Double,
    val dominated: Boolean,
    val bestValue: Boolean
)

/** Pair each SUCCESS agent with its rerank score + cost, then mark the
 *  Pareto-dominated points and the single best-value one. */
private fun buildValuePoints(report: Report, rerankContent: String?): List<ValuePoint> {
    val rows = rerankContent?.let { parseRerankRows(it) }?.associateBy { it.id } ?: emptyMap()
    val success = report.agents.filter {
        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
    }
    val n = success.size
    // (agent, quality, costCents) for agents that have a rerank entry.
    data class Raw(val agentId: String, val provider: String, val modelShort: String, val quality: Double, val costCents: Double)
    val raw = success.mapIndexedNotNull { idx, a ->
        val row = rows[idx + 1] ?: return@mapIndexedNotNull null
        val quality = row.score ?: row.rank?.let { (n - it + 1).toDouble() } ?: return@mapIndexedNotNull null
        val costUsd = a.cost ?: ((a.inputCost ?: 0.0) + (a.outputCost ?: 0.0))
        Raw(a.agentId, AppService.findById(a.provider)?.id ?: a.provider, shortModelName(a.model), quality, costUsd * 100.0)
    }
    if (raw.isEmpty()) return emptyList()
    val eps = 1e-6
    val bestId = raw
        .filterNot { p -> raw.any { o -> o.agentId != p.agentId && o.quality >= p.quality && o.costCents <= p.costCents && (o.quality > p.quality || o.costCents < p.costCents) } }
        .maxByOrNull { it.quality / maxOf(it.costCents, eps) }
        ?.agentId
    return raw.map { p ->
        val dominated = raw.any { o -> o.agentId != p.agentId && o.quality >= p.quality && o.costCents <= p.costCents && (o.quality > p.quality || o.costCents < p.costCents) }
        ValuePoint(p.provider, p.modelShort, p.costCents, p.quality, dominated, p.agentId == bestId)
    }
}

@Composable
fun ValueViewScreen(reportId: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val reportDataVersion by ReportDataVersion.version.collectAsState()
    val secondaryDataVersion by SecondaryDataVersion.version.collectAsState()

    data class Loaded(val points: List<ValuePoint>, val reportTitle: String?, val rerankModel: String?)
    val loadedState = produceState(Loaded(emptyList(), null, null), reportId, reportDataVersion, secondaryDataVersion) {
        value = withContext(Dispatchers.IO) {
            val report = ViewReportCache.get(context, reportId)
            val rerank = SecondaryResultStorage.listForReport(context, reportId)
                .filter { it.kind == SecondaryKind.RERANK && !it.content.isNullOrBlank() }
                .maxByOrNull { it.timestamp }
            val points = report?.let { buildValuePoints(it, rerank?.content) } ?: emptyList()
            Loaded(points, report?.barTitle, rerank?.let { shortModelName(it.model) })
        }
    }
    val loaded = loadedState.value
    val points = loaded.points
    val best = remember(points) { points.firstOrNull { it.bestValue } }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Value view",
            subject = loaded.rerankModel?.let { "ranked by $it" },
            helpTopic = "value_view",
            onBack = onBack
        )

        if (points.isEmpty()) {
            Text(
                "No rerank scores to compare. Run a Rerank on this report first.",
                color = AppColors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            return@Column
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Cost × quality. Top-left = cheap & good. 💎 = best value; dimmed = dominated (another model is at least as good for less).",
                color = AppColors.TextTertiary, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ValueScatter(points, modifier = Modifier.fillMaxWidth().height(240.dp).padding(bottom = 12.dp))
            best?.let {
                Text(
                    "💎 Best value: ${it.provider} · ${it.modelShort} — score ${formatScore(it.quality)} at ${formatCents(it.costCents)}",
                    color = AppColors.Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            val sorted = remember(points) {
                points.sortedWith(
                    compareByDescending<ValuePoint> { it.bestValue }
                        .thenBy { it.dominated }
                        .thenByDescending { it.quality }
                )
            }
            sorted.forEach { p -> ValueRow(p) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ValueScatter(points: List<ValuePoint>, modifier: Modifier) {
    val axis = AppColors.TextTertiary
    val frontier = AppColors.Blue
    val bestC = AppColors.Green
    val domC = AppColors.TextDim
    val regC = AppColors.Orange
    val labelArgb = AppColors.TextSecondary.toArgb()
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            val padL = 8f; val padR = 48f; val padT = 16f; val padB = 28f
            val plotW = (size.width - padL - padR).coerceAtLeast(1f)
            val plotH = (size.height - padT - padB).coerceAtLeast(1f)
            val x0 = padL; val y0 = padT

            val minCost = points.minOf { it.costCents }
            val maxCost = points.maxOf { it.costCents }
            val minQ = points.minOf { it.quality }
            val maxQ = points.maxOf { it.quality }
            val costSpan = (maxCost - minCost).takeIf { it > 1e-9 } ?: 1.0
            val qSpan = (maxQ - minQ).takeIf { it > 1e-9 } ?: 1.0

            // px position: x grows with cost (cheap left), y inverted (high quality at top)
            fun px(p: ValuePoint): Offset {
                val fx = ((p.costCents - minCost) / costSpan).toFloat()
                val fy = ((p.quality - minQ) / qSpan).toFloat()
                return Offset(x0 + fx * plotW, y0 + (1f - fy) * plotH)
            }

            // axes
            drawLine(axis, Offset(x0, y0), Offset(x0, y0 + plotH), strokeWidth = 2f)
            drawLine(axis, Offset(x0, y0 + plotH), Offset(x0 + plotW, y0 + plotH), strokeWidth = 2f)

            // frontier polyline through non-dominated points, sorted by cost
            val frontierPts = points.filter { !it.dominated }.sortedBy { it.costCents }.map { px(it) }
            if (frontierPts.size >= 2) {
                val path = Path().apply {
                    moveTo(frontierPts.first().x, frontierPts.first().y)
                    frontierPts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, frontier, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            }

            // points + labels
            val paint = android.graphics.Paint().apply {
                color = labelArgb; textSize = 22f; isAntiAlias = true
            }
            points.forEach { p ->
                val o = px(p)
                when {
                    p.bestValue -> {
                        drawCircle(bestC, radius = 12f, center = o)
                        drawCircle(bestC.copy(alpha = 0.35f), radius = 20f, center = o)
                    }
                    p.dominated -> drawCircle(domC, radius = 7f, center = o)
                    else -> drawCircle(regC, radius = 9f, center = o)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    p.modelShort.take(14), o.x + 14f, o.y + 8f, paint
                )
            }
            // axis labels: cheap → pricey
            val small = android.graphics.Paint().apply { color = axis.toArgb(); textSize = 20f; isAntiAlias = true }
            drawContext.canvas.nativeCanvas.drawText("cheap", x0, y0 + plotH + 22f, small)
            val pricey = "pricey"
            val w = small.measureText(pricey)
            drawContext.canvas.nativeCanvas.drawText(pricey, x0 + plotW - w, y0 + plotH + 22f, small)
        }
    }
}

@Composable
private fun ValueRow(p: ValuePoint) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${p.provider} · ${p.modelShort}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatCents(p.costCents)}   ·   score ${formatScore(p.quality)}",
                    color = AppColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            }
            val (badge, color) = when {
                p.bestValue -> "💎 Best value" to AppColors.Green
                !p.dominated -> "Pareto" to AppColors.Blue
                else -> "dominated" to AppColors.TextDim
            }
            Text(badge, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatScore(q: Double): String =
    if (q == q.toLong().toDouble()) q.toLong().toString() else String.format(java.util.Locale.US, "%.2f", q)
