package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.FanOutRunState
import com.ai.data.PairStatus
import com.ai.data.effectiveStatus
import com.ai.data.ReportStorage
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelLabel
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.clickable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.withContext

/**
 * L3: single pair detail. Source / answerer side-by-side.
 * Reload + delete icons in the title bar; prev/next arrows step
 * through the same L2-scoped row list.
 */
@Composable
internal fun FanOutL3Screen(
    engine: FanOutEngine,
    run: FanOutRunState,
    runningSet: Set<String>,
    answererKey: String,
    sourceAgentId: String,
    role: String,
    actions: FanOutActions,
    mode: FanOutMode = FanOutMode.MAIN,
    onStepSource: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val (activePid, activeMdl) = answererKey.split("|").let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }

    // The pair we're viewing. For Responder mode: active model is
    // answerer, sourceAgentId identifies the source. For Initiator
    // mode the L2 row's "other" agent is the answerer, and this
    // sourceAgentId is actually the answererAgentId in our model.
    val pair = remember(run, answererKey, sourceAgentId, role) {
        if (role == "Responder") {
            run.pairs.values.firstOrNull {
                "${it.providerId}|${it.model}" == answererKey && it.sourceAgentId == sourceAgentId
            }
        } else {
            // Initiator: active is source. We display the pair where
            // the OTHER agent is answerer. sourceAgentId here is
            // actually the answerer's agent id (named for symmetry
            // with Responder mode).
            run.pairs.values.firstOrNull {
                it.answererAgentId == sourceAgentId &&
                    run.pairs.values.any { other ->
                        other.answererAgentId == it.sourceAgentId &&
                            "${other.providerId}|${other.model}" == answererKey
                    }
            }
        }
    }

    // Load source body from the report's agent list, lazily.
    val report by produceState<com.ai.data.Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val sourceBody = remember(report, pair) {
        val srcId = pair?.sourceAgentId ?: return@remember null
        report?.agents?.firstOrNull { it.agentId == srcId }?.responseBody
    }

    if (pair == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            val pendingHolderEmpty = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewEmptyJump: (() -> Unit)? = pendingHolderEmpty?.let { holder ->
                {
                    holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                        ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                        ?: com.ai.ui.shared.ViewJump.Main
                }
            }
            TitleBar(
                helpTopic = "secondary_fan_out_l3",
                title = if (mode == FanOutMode.ICONS) "Fan icons - pair" else "Fan out - pair",
                onOpenView = onOpenViewEmptyJump,
                onBackClick = onBack
            )
            Text("Pair no longer exists.", color = AppColors.TextTertiary)
        }
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }

    // L2 scope for prev/next stepping — same ordering as L2.
    val l2Rows = remember(run, answererKey, role) {
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
    val curIdx = l2Rows.indexOfFirst { it.key == pair.key }
    val prev = if (curIdx > 0) l2Rows[curIdx - 1] else null
    val next = if (curIdx in 0 until l2Rows.size - 1) l2Rows[curIdx + 1] else null

    // "The other model" in the pair — show only the model name, no
    // provider. The green subject already names the model this screen
    // is about; the source pane just needs the counterpart's model.
    val sourceLabel = remember(report, pair.sourceAgentId) {
        report?.agents?.firstOrNull { it.agentId == pair.sourceAgentId }
            ?.model
            ?: pair.sourceAgentId
    }
    val sourceAgent = remember(report, pair.sourceAgentId) {
        report?.agents?.firstOrNull { it.agentId == pair.sourceAgentId }
    }
    val sourceProviderService = remember(sourceAgent) {
        sourceAgent?.provider?.let { AppService.findById(it) }
    }
    val answererLabel = modelLabel(pair.providerId, pair.model)
    val answererProviderService = remember(pair.providerId) {
        AppService.findById(pair.providerId)
    }

    // Trace lookups — answerer trace = closest-timestamp trace for
    // this pair's reportId + model. Source trace = most-recent trace
    // for the source agent's reportId + model.
    val answererTrace by produceState<String?>(initialValue = null, pair.id, pair.model, pair.timestamp) {
        value = withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == run.reportId && it.model == pair.model }
                .minByOrNull { kotlin.math.abs(it.timestamp - pair.timestamp) }?.filename
        }
    }
    val sourceTrace by produceState<String?>(initialValue = null, run.reportId, sourceAgent?.model) {
        value = if (sourceAgent == null) null else withContext(Dispatchers.IO) {
            ApiTracer.getTraceFiles()
                .filter { it.reportId == run.reportId && it.model == sourceAgent.model }
                .maxByOrNull { it.timestamp }?.filename
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val halfMax = maxHeight / 2
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // 👁 → matching View Fan-out for this metaPromptName.
            val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
                {
                    holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                        ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                        ?: com.ai.ui.shared.ViewJump.Main
                }
            }
            TitleBar(
                helpTopic = "secondary_fan_out_l3",
                title = if (mode == FanOutMode.ICONS) "Fan icons - pair" else "Fan out - pair",
                subject = answererLabel,
                onBackClick = onBack,
                onOpenView = onOpenViewJump,
                onInfo = answererProviderService?.let { svc ->
                    { actions.onNavigateToModelInfo(svc, pair.model) }
                },
                onTrace = if (ApiTracer.isTracingEnabled && answererTrace != null) {
                    { actions.onNavigateToTraceFile(answererTrace!!) }
                } else null,
                onReload = { actions.onRerunPair(run.key, pair.key) },
                onDelete = { confirmDelete = true }
            )
            // Bespoke row instead of the shared HardcodedSubjectRow —
            // this screen also surfaces the role next to the answerer
            // label. top = 4.dp matches HardcodedSubjectRow so the y-
            // position lines up with every other HARDCODED screen.
            com.ai.ui.shared.HardcodedSubjectRow(answererLabel) {
                Text(
                    text = role,
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

            // Source pane — header row carries the source's "provider /
            // model" label plus info / trace icons for the source
            // agent's own run (peeking out of the pair view into the
            // upstream call that produced the body shown below). The
            // source agent's per-model report icon (if generated) is
            // shown as a leading emoji.
            // Source = "the prompt this pair received". In Responder
            // mode the source agent is the OTHER model (the
            // counterpart we're exploring), so its info / trace icons
            // live here. In Initiator mode the source agent IS the
            // active model — the user came from its L2 view — so the
            // peek-into-other-model icons move down to the response
            // pane instead (see below).
            val sourcePaneIsOther = role == "Responder"
            Column(Modifier.fillMaxWidth().heightIn(max = halfMax).padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sourceIcon = sourceAgent?.icon
                    if (!sourceIcon.isNullOrBlank()) {
                        Text(
                            sourceIcon, fontSize = 16.sp,
                            modifier = Modifier.background(MaterialTheme.colorScheme.background)
                                .padding(end = 6.dp)
                        )
                    }
                    Text(
                        sourceLabel, fontSize = 14.sp, color = AppColors.Blue,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (sourcePaneIsOther && sourceProviderService != null && sourceAgent != null) {
                        Text(
                            "ℹ️", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToModelInfo(sourceProviderService, sourceAgent.model) }
                        )
                    }
                    if (sourcePaneIsOther && ApiTracer.isTracingEnabled && sourceTrace != null) {
                        Text(
                            "🐞", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToTraceFile(sourceTrace!!) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val body = sourceBody
                    if (body.isNullOrBlank()) {
                        Text("(source content not found)", color = AppColors.TextTertiary, fontSize = 13.sp)
                    } else {
                        Text(body, fontSize = 13.sp, color = Color.White)
                    }
                }
            }
            HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)

            // Answerer / response pane. Leading emoji is the icon
            // produced by the fan-out icon chain (fanOutIconGenEnabled).
            // In Initiator mode the answerer IS the OTHER model, so
            // the peek-into-other-model icons (info + trace) live
            // here. In Responder mode they live up on the source pane.
            val responsePaneIsOther = role == "Initiator"
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!pair.icon.isNullOrBlank()) {
                        // Tap → opens the unified Icon-lookup screen
                        // for this pair (6th adapter). Only wired in
                        // MAIN mode — ICONS-mode L3 is itself an
                        // icon-focused view.
                        val iconModifier = Modifier
                            .background(MaterialTheme.colorScheme.background)
                            .padding(end = 6.dp)
                            .let { base ->
                                if (mode == FanOutMode.MAIN) {
                                    base.clickable { actions.onOpenPairIconLookup(pair.id) }
                                } else base
                            }
                        Text(
                            pair.icon, fontSize = 16.sp,
                            modifier = iconModifier
                        )
                    }
                    Text(
                        answererLabel, fontSize = 14.sp, color = AppColors.Green,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (responsePaneIsOther && answererProviderService != null) {
                        Text(
                            "ℹ️", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToModelInfo(answererProviderService, pair.model) }
                        )
                    }
                    if (responsePaneIsOther && ApiTracer.isTracingEnabled && answererTrace != null) {
                        Text(
                            "🐞", fontSize = 16.sp,
                            modifier = Modifier.padding(start = 6.dp)
                                .clickable { actions.onNavigateToTraceFile(answererTrace!!) }
                        )
                    }
                    if (pair.totalCost > 0.0) {
                        Text(
                            "${formatCents(pair.totalCost)} ¢", fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    when (pair.effectiveStatus(runningSet)) {
                        PairStatus.ERROR -> Text(
                            "❌ ${pair.errorMessage}",
                            color = AppColors.Red, fontSize = 13.sp
                        )
                        PairStatus.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedHourglass(fontSize = 16.sp)
                            Text("  Running…", color = AppColors.Orange, fontSize = 13.sp)
                        }
                        PairStatus.PENDING -> Text(
                            "🕓 Queued",
                            color = AppColors.TextTertiary, fontSize = 13.sp
                        )
                        PairStatus.DONE -> {
                            val body = pair.content
                            if (body.isNullOrBlank()) {
                                Text("(no result)", color = AppColors.TextTertiary, fontSize = 13.sp)
                            } else {
                                Text(body, fontSize = 13.sp, color = Color.White)
                            }
                        }
                    }
                    // Trailing divider — mirrors the one between source
                    // and answerer above so the response pane has a
                    // clean closing edge tight against the content,
                    // not floating at the bottom of the screen.
                    HorizontalDivider(
                        color = AppColors.DividerDark, thickness = 2.dp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Prev / Next arrow row at the bottom.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(
                    onClick = {
                        prev?.let {
                            if (role == "Responder") onStepSource(it.sourceAgentId)
                            else onStepSource(it.answererAgentId)
                        }
                    },
                    enabled = prev != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("← Prev", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        next?.let {
                            if (role == "Responder") onStepSource(it.sourceAgentId)
                            else onStepSource(it.answererAgentId)
                        }
                    },
                    enabled = next != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo)
                ) { Text("Next →", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this pair?") },
            text = { Text("Drops the pair row from the run. The API cost stays counted in the report total.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onCancelPair(run.key, pair.key)
                    onBack()
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
