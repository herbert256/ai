package com.ai.ui.report

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
import com.ai.data.AppService
import com.ai.data.FanOutRunState
import com.ai.data.PairStatus
import com.ai.data.ReportStorage
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.modelLabel
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
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
    answererKey: String,
    sourceAgentId: String,
    role: String,
    actions: FanOutActions,
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
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            TitleBar(helpTopic = "secondary_fan_out_l3", title = "Fan out - pair", onBackClick = onBack)
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

    val sourceLabel = "source: ${pair.sourceAgentId}"
    val answererLabel = modelLabel(pair.providerId, pair.model)

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val halfMax = maxHeight / 2
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TitleBar(
                helpTopic = "secondary_fan_out_l3",
                title = "Fan out - pair",
                subject = answererLabel,
                onBackClick = onBack,
                onReload = { actions.onRerunPair(run.key, pair.key) },
                onDelete = { confirmDelete = true }
            )
            Spacer(Modifier.height(8.dp))

            // Source pane
            Column(Modifier.fillMaxWidth().heightIn(max = halfMax).padding(vertical = 8.dp)) {
                Text(sourceLabel, fontSize = 14.sp, color = AppColors.Blue, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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

            // Answerer / response pane
            Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        answererLabel, fontSize = 14.sp, color = AppColors.Green,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    if (pair.totalCost > 0.0) {
                        Text(
                            "${formatCents(pair.totalCost)} ¢", fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (pair.status) {
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
