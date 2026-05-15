package com.ai.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.FanOutRunState
import com.ai.data.PairState
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L1-scoped "Fan out - icons" overview. One row per source model:
 * the source's report icon, an arrow, then every responder's
 * fan-out icon for that source — so the whole fan-out structure
 * reads as "source → all its answers" rather than a flat wall of
 * repeated source glyphs. Tap any responder glyph to drill into
 * that pair's L3 detail (Responder role).
 */
@Composable
internal fun FanOutL1IconsScreen(
    run: FanOutRunState,
    onOpenPair: (answererKey: String, sourceAgentId: String, role: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val subject = run.metaPrompt.title.takeIf { it.isNotBlank() }
        ?.let { "${run.metaPrompt.name} — $it" } ?: run.metaPrompt.name

    val report by produceState<Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val sourceIconBySource: Map<String, String> = remember(report) {
        report?.agents?.associate {
            it.agentId to (it.icon?.takeIf { e -> e.isNotBlank() } ?: "📝")
        } ?: emptyMap()
    }

    // Pairs with a fan-out icon, grouped by their source model —
    // the screen renders one row per source. Groups ordered by the
    // source's earliest pair; pairs within a group by timestamp.
    val groups = remember(run) {
        run.pairs.values
            .filter { !it.icon.isNullOrBlank() }
            .groupBy { it.sourceAgentId }
            .entries
            .sortedBy { e -> e.value.minOf { it.timestamp } }
            .map { it.key to it.value.sortedBy { p -> p.timestamp } }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "secondary_fan_out_l1",
            title = "Fan out - icons",
            subject = subject,
            onBackClick = onBack
        )
        com.ai.ui.shared.HardcodedSubjectRow(subject)
        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No icons yet", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
            return@Column
        }

        // One row per source model: the source's report icon, an
        // arrow, then every responder's fan-out icon for that
        // source. Each responder glyph drills into its pair (L3,
        // Responder role).
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groups.forEach { (sourceAgentId, pairs) ->
                val srcGlyph = sourceIconBySource[sourceAgentId] ?: "📝"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CardBackground)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(srcGlyph, fontSize = 28.sp, color = Color.White)
                    Text(
                        "→", fontSize = 18.sp, color = AppColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        pairs.forEach { p ->
                            Text(
                                p.icon!!, fontSize = 28.sp, color = Color.White,
                                modifier = Modifier.clickable {
                                    onOpenPair("${p.providerId}|${p.model}", p.sourceAgentId, "Responder")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * L2-scoped icons grid. Filtered to the active model's pairs at
 * the current role (Responder = pairs the active model answered;
 * Initiator = pairs where the active model was the source).
 * Carries the same "Switch role" affordance the L2 list view
 * does. Tap an icon to open L3 for that pair.
 */
@Composable
internal fun FanOutL2IconsScreen(
    run: FanOutRunState,
    answererKey: String,
    role: String,
    onSwitchRole: (String) -> Unit,
    onOpenPair: (sourceAgentId: String) -> Unit,
    onBack: () -> Unit
) {
    val (activePid, activeMdl) = answererKey.split("|").let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }
    val canonPid = AppService.findById(activePid)?.id ?: activePid
    val subject = "$canonPid / $activeMdl"

    val rows: List<PairState> = remember(run, role, answererKey) {
        when (role) {
            "Initiator" -> run.pairs.values.filter {
                run.pairs.values.any { other ->
                    other.answererAgentId == it.sourceAgentId &&
                        "${other.providerId}|${other.model}" == answererKey
                }
            }
            else -> run.pairs.values.filter { "${it.providerId}|${it.model}" == answererKey }
        }
            .filter { !it.icon.isNullOrBlank() }
            .sortedBy { it.timestamp }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "secondary_fan_out_l2",
            title = "Fan out - model icons",
            subject = subject,
            onBackClick = onBack
        )
        com.ai.ui.shared.HardcodedSubjectRow(subject)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(
                "Role: $role", fontSize = 12.sp, color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { onSwitchRole(if (role == "Responder") "Initiator" else "Responder") },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) { Text("Switch role", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (role == "Responder") "No icons for this model yet"
                    else "No icons from pairs where this model is the source",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
            return@Column
        }

        IconsFlow(
            cellCount = rows.size,
            cellW = 80.dp,
            cellH = 80.dp
        ) {
            rows.forEach { p ->
                val targetSrc = if (role == "Responder") p.sourceAgentId else p.answererAgentId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CardBackground)
                        .clickable { onOpenPair(targetSrc) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        p.icon!!, fontSize = 48.sp, color = Color.White,
                        fontFamily = FontFamily.Default
                    )
                }
            }
        }
    }
}

/** Adaptive-gap FlowRow wrapper that mirrors [ReportIconsGridScreen]'s
 *  layout (`ui/report/ReportScreen.kt:3288-3334`): shrink the gap
 *  until the grid fits; fall back to a vertical scroll when even
 *  the tightest gap overflows. The icons stay centred when they
 *  fit, top-aligned otherwise. */
@Composable
private fun IconsFlow(
    cellCount: Int,
    cellW: androidx.compose.ui.unit.Dp,
    cellH: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val n = cellCount.coerceAtLeast(1)
        val gapOptions = listOf(16.dp, 12.dp, 8.dp, 4.dp, 2.dp, 0.dp)
        val fittingGap = gapOptions.firstOrNull { g ->
            val perRow = ((maxWidth + g).value / (cellW + g).value).toInt().coerceAtLeast(1)
            val rows = (n + perRow - 1) / perRow
            val totalH = rows * cellH.value + (rows - 1).coerceAtLeast(0) * g.value
            totalH <= maxHeight.value
        }
        val gap = fittingGap ?: 0.dp
        val fits = fittingGap != null
        val flow: @Composable () -> Unit = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) { content() }
        }
        if (fits) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { flow() }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { flow() }
        }
    }
}
