package com.ai.ui.report.manage.view
import com.ai.ui.report.view.*
import com.ai.ui.report.manage.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.FanOutRunState
import com.ai.data.PairState
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.shortModelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L1-scoped "Fan out - titles" overview — the text sibling of
 * [FanOutL1IconsScreen]. One card per source model: the source's
 * model name as a header, then every responder's generated fan-out
 * title as a tappable text row. Tap a title to drill into that
 * pair's L3 detail (Responder role).
 */
@Composable
internal fun FanOutL1MetaScreen(
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
    // agentId → "provider / model" label for the source header.
    val sourceLabelBySource: Map<String, String> = remember(report) {
        report?.agents?.associate {
            it.agentId to resolveModelLabel("${it.provider}|${it.model}")
        } ?: emptyMap()
    }

    // Pairs with a title, grouped by source model. Groups ordered by
    // the source's earliest pair; pairs within a group by timestamp.
    val groups = remember(run) {
        run.pairs.values
            .filter { !it.title.isNullOrBlank() }
            .groupBy { it.sourceAgentId }
            .entries
            .sortedBy { e -> e.value.minOf { it.timestamp } }
            .map { it.key to it.value.sortedBy { p -> p.timestamp } }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "fan_meta",
            title = "Fan out - titles",
            subject = subject,
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No titles yet", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groups.forEach { (sourceAgentId, pairs) ->
                val srcLabel = sourceLabelBySource[sourceAgentId] ?: sourceAgentId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CardBackground)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        srcLabel, fontSize = 12.sp, color = AppColors.Blue,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    pairs.forEachIndexed { idx, p ->
                        if (idx > 0) HorizontalDivider(color = AppColors.DividerDark)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    onOpenPair("${p.providerId}|${p.model}", p.sourceAgentId, "Responder")
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🏷️", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                p.title.orEmpty(), fontSize = 14.sp, color = Color.White,
                                modifier = Modifier.weight(1f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * L2-scoped titles list — the text sibling of [FanOutL2IconsScreen].
 * Filtered to the active model's pairs at the current role; one
 * tappable text row per pair. Tap a row to open L3 for that pair.
 */
@Composable
internal fun FanOutL2MetaScreen(
    run: FanOutRunState,
    answererKey: String,
    role: String,
    onSwitchRole: (String) -> Unit,
    onOpenPair: (sourceAgentId: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val (activePid, activeMdl) = answererKey.split("|").let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }
    val canonPid = AppService.findById(activePid)?.id ?: activePid
    val subject = "$canonPid / $activeMdl"

    val report by produceState<Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val agentLabels: Map<String, String> = remember(report) {
        report?.agents?.associate { it.agentId to resolveModelLabel("${it.provider}|${it.model}") }
            ?: emptyMap()
    }

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
            .filter { !it.title.isNullOrBlank() }
            .sortedBy { it.timestamp }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        TitleBar(
            helpTopic = "fan_meta",
            title = "Fan out - model titles",
            subject = subject,
            onBackClick = onBack
        )

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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (role == "Responder") "No titles for this model yet"
                    else "No titles from pairs where this model is the source",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            rows.forEach { p ->
                val targetSrc = if (role == "Responder") p.sourceAgentId else p.answererAgentId
                val label = if (role == "Responder")
                    (agentLabels[p.sourceAgentId] ?: p.sourceAgentId)
                    else resolveModelLabel("${p.providerId}|${p.model}")
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.CardBackground)
                        .clickable { onOpenPair(targetSrc) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🏷️", fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.title.orEmpty(), fontSize = 15.sp, color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            label, fontSize = 11.sp, color = AppColors.TextTertiary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
