package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import com.ai.ui.shared.formatCents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fan Meta L2 — one model's pairs as a focused icon + title list (no
 * status glyphs / progress fills; that's the Fan Out L2). "Switch
 * role" flips Responder ⇄ Initiator. Tapping a row opens the pair's
 * L3 detail. Fan-Meta sibling of [FanOutL2Screen].
 */
@Composable
internal fun FanMetaL2Screen(
    run: FanOutRunState,
    answererKey: String,
    role: String,
    actions: FanOutActions,
    onSwitchRole: (String) -> Unit,
    onOpenPair: (String) -> Unit,
    onBack: () -> Unit
) {
    val (activePid, activeMdl) = answererKey.split("|").let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }
    val canonPid = AppService.findById(activePid)?.id ?: activePid
    val subject = "$canonPid / $activeMdl"

    var confirmModelDelete by remember { mutableStateOf(false) }

    val rawRows: List<PairState> = remember(run, role, answererKey) {
        when (role) {
            "Initiator" -> run.pairs.values.filter {
                run.pairs.values.any { other ->
                    other.answererAgentId == it.sourceAgentId &&
                        "${other.providerId}|${other.model}" == answererKey
                }
            }
            else -> run.pairs.values.filter { "${it.providerId}|${it.model}" == answererKey }
        }.sortedBy { it.timestamp }
    }

    val context = LocalContext.current
    val report by produceState<Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val agentLabels: Map<String, String> = remember(report) {
        report?.agents?.associate { it.agentId to resolveModelLabel("${it.provider}|${it.model}") }
            ?: emptyMap()
    }
    fun rowLabel(p: PairState): String = if (role == "Responder") {
        agentLabels[p.sourceAgentId] ?: p.sourceAgentId
    } else {
        resolveModelLabel("${p.providerId}|${p.model}")
    }
    val rows: List<PairState> = remember(rawRows, agentLabels, role) {
        rawRows.sortedWith(compareBy { p -> rowLabel(p).lowercase() })
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            {
                holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                    ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                    ?: com.ai.ui.shared.ViewJump.Main
            }
        }
        TitleBar(
            helpTopic = "fan_meta_l2",
            title = "Fan Meta - model",
            subject = subject,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            onDelete = { confirmModelDelete = true },
            onInfo = AppService.findById(activePid)?.let { svc -> { actions.onNavigateToModelInfo(svc, activeMdl) } }
        )

        // Role label + Switch role button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text("Role: $role", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { onSwitchRole(if (role == "Responder") "Initiator" else "Responder") },
                colors = AppColors.outlinedButtonColors(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) { Text("Switch role", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (role == "Responder") "No responses for this model yet"
                    else "No other model has responded to this one yet",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
        } else {
            // Focused list: the per-pair found icon + its generated
            // title. No status icons / progress fills. Tapping a row
            // opens the L3 pair detail.
            val rowsTotalCost = rows.sumOf { it.totalCost }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clickable {
                                onOpenPair(if (role == "Responder") p.sourceAgentId else p.answererAgentId)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val label = if (role == "Responder")
                            (agentLabels[p.sourceAgentId] ?: p.sourceAgentId)
                            else resolveModelLabel("${p.providerId}|${p.model}")
                        Text(p.icon ?: com.ai.data.MetadataIconsHolder.current.boxBlank, fontSize = 28.sp, modifier = Modifier.padding(start = 8.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.title ?: "—",
                                fontSize = 15.sp, color = AppColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                label, fontSize = 11.sp, color = AppColors.TextTertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (p.totalCost > 0.0) {
                            Text(
                                formatCents(p.totalCost), fontSize = 12.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                if (rowsTotalCost > 0.0) {
                    item(key = "l2-meta-total-footer") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Total", fontSize = 14.sp, color = AppColors.InfoAccent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            Text(
                                formatCents(rowsTotalCost), fontSize = 12.sp,
                                color = AppColors.InfoAccent, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmModelDelete) {
        AlertDialog(
            onDismissRequest = { confirmModelDelete = false },
            title = { Text("Delete this model from the run?") },
            text = { Text("Drops every pair where $subject is the answerer or the source. Can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmModelDelete = false
                    actions.onDeleteModelFromRun(run.key, activePid, activeMdl)
                    onBack()
                }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmModelDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

/**
 * Fan Meta L2 in "Meta models" mode — scoped to one meta-worker model
 * ([com.ai.data.PairState.titleModel]) instead of an answerer model.
 * Lists every pair that meta model titled: found icon + generated
 * title + the answerer/report model that produced the response, plus a
 * cost total. Tapping a row opens that pair's L3 detail.
 */
@Composable
internal fun FanMetaMetaModelScreen(
    run: FanOutRunState,
    metaModelKey: String,
    onOpenPair: (answererKey: String, sourceAgentId: String) -> Unit,
    onBack: () -> Unit
) {
    val subject = com.ai.ui.shared.shortModelName(metaModelKey.substringAfterLast('/'))

    val rows: List<PairState> = remember(run, metaModelKey) {
        run.pairs.values
            .filter { it.titleModel == metaModelKey }
            .sortedBy { it.timestamp }
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            {
                holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                    ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                    ?: com.ai.ui.shared.ViewJump.Main
            }
        }
        TitleBar(
            helpTopic = "fan_meta_l2",
            title = "Fan Meta - meta model",
            subject = subject,
            onBackClick = onBack,
            onOpenView = onOpenViewJump
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No titles from this meta model yet", color = AppColors.TextTertiary, fontSize = 13.sp)
            }
        } else {
            val rowsTotalCost = rows.sumOf { it.totalCost }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clickable { onOpenPair("${p.providerId}|${p.model}", p.sourceAgentId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.icon ?: com.ai.data.MetadataIconsHolder.current.boxBlank, fontSize = 28.sp, modifier = Modifier.padding(start = 8.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.title ?: "—",
                                fontSize = 15.sp, color = AppColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                resolveModelLabel("${p.providerId}|${p.model}"),
                                fontSize = 11.sp, color = AppColors.TextTertiary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (p.totalCost > 0.0) {
                            Text(
                                formatCents(p.totalCost), fontSize = 12.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                if (rowsTotalCost > 0.0) {
                    item(key = "l2-meta-model-total-footer") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Total", fontSize = 14.sp, color = AppColors.InfoAccent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            Text(
                                formatCents(rowsTotalCost), fontSize = 12.sp,
                                color = AppColors.InfoAccent, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
