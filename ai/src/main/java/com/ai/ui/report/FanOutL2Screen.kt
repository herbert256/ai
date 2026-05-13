package com.ai.ui.report

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.FanOutRunState
import com.ai.data.PairState
import com.ai.data.PairStatus
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.effectiveStatus
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ReloadConfirmationDialog
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L2: shows every pair where the active model is the answerer
 * (Responder mode) or the source (Initiator mode). Tapping a row
 * opens L3.
 *
 * "Switch role" flips between Responder and Initiator views.
 * "Create Report" and "New Fan In" sit above the list.
 * "Remove / Restart failed items" appear only when this model's
 * pairs have errors.
 */
@Composable
internal fun FanOutL2Screen(
    engine: FanOutEngine,
    run: FanOutRunState,
    runningSet: Set<String>,
    answererKey: String,
    role: String,
    actions: FanOutActions,
    onSwitchRole: (String) -> Unit,
    onOpenPair: (String) -> Unit,
    onOpenOnePage: () -> Unit,
    onOpenIcons: () -> Unit,
    onBack: () -> Unit
) {
    val (activePid, activeMdl) = answererKey.split("|").let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }
    val canonPid = AppService.findById(activePid)?.id ?: activePid
    val subject = "$canonPid / $activeMdl"

    var confirmModelDelete by remember { mutableStateOf(false) }
    var confirmRestartFailed by remember { mutableStateOf(false) }
    var confirmRemoveFailed by remember { mutableStateOf(false) }

    // Pairs scoped to L2. Responder = pairs where active model is
    // ANSWERER. Initiator = pairs where active model is SOURCE.
    // (Without report-agent info we approximate "is source" by
    // matching the source's (provider, model) — that requires
    // engine.runs's hydration to have populated answererAgentId
    // for every pair.)
    val rows: List<PairState> = remember(run, role, answererKey) {
        when (role) {
            "Initiator" -> run.pairs.values.filter {
                // We treat any pair whose source agent shares the
                // active (provider, model) as belonging to this L2.
                // The pair's `providerId` / `model` describe the
                // answerer, so we need to look up the source by
                // agentId. Without the report in scope we fall back
                // to "source has matching answerer key in another
                // pair" — best-effort, mirrors the old behaviour for
                // single-agent (non-swarm) setups.
                run.pairs.values.any { other ->
                    other.answererAgentId == it.sourceAgentId &&
                        "${other.providerId}|${other.model}" == answererKey
                }
            }
            else -> run.pairs.values.filter { "${it.providerId}|${it.model}" == answererKey }
        }.sortedBy { it.timestamp }
    }

    val erroredHere = rows.count { it.status == PairStatus.ERROR }

    // Load report lazily so source agent ids can resolve to model
    // labels in the row list (mirrors the L1 "provider / model"
    // format). Falls back to the UUID before the report has loaded.
    val context = LocalContext.current
    val report by produceState<Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val agentLabels: Map<String, String> = remember(report) {
        report?.agents?.associate { it.agentId to resolveModelLabel("${it.provider}|${it.model}") }
            ?: emptyMap()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "secondary_fan_out_l2",
            title = "Fan out - model",
            subject = subject,
            onBackClick = onBack,
            onDelete = { confirmModelDelete = true },
            onInfo = AppService.findById(activePid)?.let { svc -> { actions.onNavigateToModelInfo(svc, activeMdl) } }
        )

        // Row 1: role label + Switch role button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text("Role: $role", fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.weight(1f))
            Button(
                onClick = { onSwitchRole(if (role == "Responder") "Initiator" else "Responder") },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Purple),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 32.dp)
            ) { Text("Switch role", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: Create Report + New Fan In on their own row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { actions.onCreateReportFromFanOut(run.key, activePid, activeMdl) },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp)
            ) { Text("Create Report", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Button(
                onClick = { actions.onRunModelFanIn(run.key, activePid, activeMdl) },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp)
            ) { Text("New Fan In", fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }

        // Per-failure buttons (L2-scoped).
        if (erroredHere > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { confirmRemoveFailed = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("Remove failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { confirmRestartFailed = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("Restart failed items", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Combined-report rows scoped to this model (model-fan-in).
        val modelScopedFanIn = run.combinedReports.filter {
            it.isModelScoped &&
                it.scopeProviderId.equals(activePid, ignoreCase = true) &&
                it.scopeModel == activeMdl
        }
        if (modelScopedFanIn.isNotEmpty()) {
            Text("Model fan-ins", fontSize = 13.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold)
            for (cr in modelScopedFanIn.sortedByDescending { it.timestamp }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clickable { actions.onOpenSecondary(cr.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (cr.status) {
                        PairStatus.ERROR -> "❌"
                        PairStatus.DONE -> "✅"
                        else -> null
                    }
                    if (icon != null) {
                        Text(icon, fontSize = 16.sp, modifier = Modifier.width(20.dp))
                    } else {
                        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                            AnimatedHourglass(fontSize = 16.sp)
                        }
                    }
                    Text(
                        cr.fanInPromptName,
                        fontSize = 13.sp, color = Color.White,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (cr.totalCost > 0.0) {
                        Text(
                            formatCents(cr.totalCost), fontSize = 11.sp,
                            color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                        Text(">", fontSize = 16.sp, color = AppColors.Blue)
                    }
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // "One Page View" button — only when rows exist.
        if (rows.isNotEmpty()) {
            OutlinedButton(
                onClick = onOpenOnePage,
                modifier = Modifier.fillMaxWidth()
            ) { Text("One Page View", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Icons overview — gated on the role-scoped rows having at
        // least one non-blank fan-out icon. Opens the L2 Icons
        // grid for the current (answerer, role).
        val hasIcons = remember(rows) { rows.any { !it.icon.isNullOrBlank() } }
        if (hasIcons) {
            OutlinedButton(
                onClick = onOpenIcons,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Icons", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (role == "Responder") "No responses for this model yet"
                    else "No other model has responded to this one yet",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
        } else {
            val rowsTotalCost = rows.sumOf { it.totalCost }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { p ->
                    val otherAgentId = if (role == "Responder") p.sourceAgentId else p.answererAgentId
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable { onOpenPair(if (role == "Responder") p.sourceAgentId else p.answererAgentId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = when (p.effectiveStatus(runningSet)) {
                            PairStatus.ERROR -> "❌"
                            PairStatus.DONE -> "✅"
                            PairStatus.RUNNING -> "⏳"
                            PairStatus.PENDING -> "🕓"
                        }
                        if (icon == "⏳") {
                            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                                AnimatedHourglass(fontSize = 16.sp)
                            }
                        } else {
                            Text(icon, fontSize = 16.sp, modifier = Modifier.width(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                            // Responder mode: row is a SOURCE — resolve
                            // its agentId to "provider / model" via the
                            // report's agent list (same format L1 uses).
                            // Initiator mode: row is the ANSWERER and the
                            // pair already carries provider / model.
                            val label = if (role == "Responder") {
                                agentLabels[otherAgentId] ?: otherAgentId
                            } else {
                                resolveModelLabel("${p.providerId}|${p.model}")
                            }
                            Text(
                                label,
                                fontSize = 14.sp, color = Color.White,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (p.totalCost > 0.0) {
                            Text(
                                formatCents(p.totalCost), fontSize = 11.sp,
                                color = AppColors.TextTertiary, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                            Text(">", fontSize = 16.sp, color = AppColors.Blue)
                        }
                    }
                    HorizontalDivider(color = AppColors.DividerDark)
                }
                if (rowsTotalCost > 0.0) {
                    item(key = "l2-total-footer") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(20.dp))
                            Text(
                                "Total", fontSize = 14.sp, color = AppColors.Blue,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(start = 4.dp)
                            )
                            Text(
                                formatCents(rowsTotalCost), fontSize = 11.sp,
                                color = AppColors.Blue, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Box(modifier = Modifier.width(16.dp))
                        }
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Confirmation dialogs
    // -----------------------------------------------------------------
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
                }) { Text("Delete", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmModelDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }

    if (confirmRestartFailed) {
        ReloadConfirmationDialog(
            target = "",
            title = "Restart failed items for this model?",
            message = "Re-fires $erroredHere failed pair${if (erroredHere == 1) "" else "s"} where $subject is the answerer. Other models' rows are kept.",
            confirmLabel = "Restart",
            onConfirm = {
                confirmRestartFailed = false
                actions.onRestartFailedPairsForModel(run.key, activePid, activeMdl)
            },
            onDismiss = { confirmRestartFailed = false }
        )
    }
    if (confirmRemoveFailed) {
        AlertDialog(
            onDismissRequest = { confirmRemoveFailed = false },
            title = { Text("Remove failed items for this model?") },
            text = { Text("Drops $erroredHere failed pair${if (erroredHere == 1) "" else "s"} where $subject is the answerer. Other models' rows are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveFailed = false
                    actions.onRemoveFailedPairsForModel(run.key, activePid, activeMdl)
                }) { Text("Remove", color = AppColors.Red, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveFailed = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}

/**
 * The L2 OnePageView — renders every pair's source + response in a
 * single scrollable column for read-only review.
 */
@Composable
internal fun FanOutL2OnePageScreen(
    engine: FanOutEngine,
    run: FanOutRunState,
    answererKey: String,
    role: String,
    onBack: () -> Unit
) {
    val (activePid, activeMdl) = answererKey.split("|").let {
        if (it.size == 2) it[0] to it[1] else "" to ""
    }
    val canonPid = AppService.findById(activePid)?.id ?: activePid
    val subject = "$canonPid / $activeMdl (one page)"

    val rows = when (role) {
        "Initiator" -> run.pairs.values.filter {
            run.pairs.values.any { other ->
                other.answererAgentId == it.sourceAgentId &&
                    "${other.providerId}|${other.model}" == answererKey
            }
        }
        else -> run.pairs.values.filter { "${it.providerId}|${it.model}" == answererKey }
    }.sortedBy { it.timestamp }

    val context = LocalContext.current
    val report by produceState<Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val agentLabels: Map<String, String> = remember(report) {
        report?.agents?.associate { it.agentId to resolveModelLabel("${it.provider}|${it.model}") }
            ?: emptyMap()
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TitleBar(
            helpTopic = "secondary_fan_out_l2",
            title = "Fan out - one page",
            subject = subject,
            onBackClick = onBack
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rows, key = { it.key }) { p ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        agentLabels[p.sourceAgentId] ?: p.sourceAgentId,
                        fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    val body = p.content
                        ?: p.errorMessage?.let { "❌ $it" }
                        ?: "⏳ pending"
                    Text(body, fontSize = 13.sp, color = Color.White)
                    HorizontalDivider(
                        color = AppColors.DividerDark,
                        thickness = 2.dp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
