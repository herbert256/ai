package com.ai.ui.report.manage
import com.ai.ui.report.view.*
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import com.ai.data.iconStatus
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
    mode: FanOutMode = FanOutMode.MAIN,
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
    val isIconsMode = mode == FanOutMode.ICONS
    val rawRows: List<PairState> = remember(run, role, answererKey) {
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

    val erroredHere = rawRows.count { it.status == PairStatus.ERROR }

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
    // agentId → agent-level icon (the icon-gen result for that agent's
    // original report response). Drives the big icon-list rendering
    // in Fan icons L2.
    val agentIcons: Map<String, String?> = remember(report) {
        report?.agents?.associate { it.agentId to it.icon } ?: emptyMap()
    }
    // Active model's own agent-level icon, looked up by matching the
    // (provider, model) key. Shown big at the top of the Initiator view.
    val activeAgentIcon: String? = remember(report, answererKey) {
        report?.agents?.firstOrNull { "${it.provider}|${it.model}" == answererKey }?.icon
    }

    // Display order: Running / Queued first, then Errored, then
    // Done at the bottom — each group sorted by the row's model
    // name. The label is role-dependent (source label in
    // Responder mode, answerer's provider/model in Initiator
    // mode), so the sort re-derives once the report's agent
    // labels load.
    fun rowLabel(p: PairState): String = if (role == "Responder") {
        agentLabels[p.sourceAgentId] ?: p.sourceAgentId
    } else {
        resolveModelLabel("${p.providerId}|${p.model}")
    }
    val rows: List<PairState> = remember(rawRows, runningSet, isIconsMode, agentLabels, role) {
        rawRows.sortedWith(
            compareBy(
                { p ->
                    when (if (isIconsMode) p.iconStatus(runningSet) else p.effectiveStatus(runningSet)) {
                        PairStatus.RUNNING, PairStatus.PENDING -> 0
                        PairStatus.ERROR -> 1
                        PairStatus.DONE -> 2
                    }
                },
                { p -> rowLabel(p).lowercase() }
            )
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
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
            helpTopic = "secondary_fan_out_l2",
            title = if (isIconsMode) "Fan icons - model" else "Fan out - model",
            subject = subject,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            onDelete = { confirmModelDelete = true },
            onInfo = AppService.findById(activePid)?.let { svc -> { actions.onNavigateToModelInfo(svc, activeMdl) } },
            // 👯 takes over the in-page "Report" button — fires the
            // same onCreateReportFromFanOut handler that the inline
            // button used to call. Icons mode never had the button
            // (action row is hidden there); hide the icon there too.
            onCopyReport = if (!isIconsMode) {
                { actions.onCreateReportFromFanOut(run.key, activePid, activeMdl) }
            } else null
        )
        com.ai.ui.shared.HardcodedSubjectRow(subject)

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

        // In ICONS mode the screen is a focused icon overview — drop
        // the action buttons (Create Report, New Fan In, Remove /
        // Restart failed, One Page View, Icons), keep just "Switch
        // role" rendered above this comment. Fan out's MAIN mode
        // still shows them all.
        if (isIconsMode) {
            Spacer(modifier = Modifier.height(8.dp))
        } else {

        Spacer(modifier = Modifier.height(6.dp))

        // Action buttons all packed in one row — Fan In is always
        // shown; Remove + Restart appear when there are errored
        // pairs; onepage when rows exist; Icons when at least one
        // pair has a generated icon. Report is gone — moved to the
        // bottom-bar 👯 icon. equalWeight keeps each button at the
        // same width and labels stay short so a 3–5 button row still
        // fits on a phone.
        val hasIcons = remember(rawRows) { rawRows.any { !it.icon.isNullOrBlank() } }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { actions.onRunModelFanIn(run.key, activePid, activeMdl) },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Indigo),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).heightIn(min = 32.dp)
            ) { Text("Fan in", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            if (erroredHere > 0) {
                Button(
                    onClick = { confirmRemoveFailed = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDark),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("Remove", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                Button(
                    onClick = { confirmRestartFailed = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("Restart", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
            if (rawRows.isNotEmpty()) {
                Button(
                    onClick = onOpenOnePage,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("onepage", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            }
            if (hasIcons) {
                Button(
                    onClick = onOpenIcons,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Blue),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp)
                ) { Text("Icons", fontSize = 12.sp, maxLines = 1, softWrap = false) }
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
                        Text(
                            icon, fontSize = 16.sp,
                            modifier = Modifier.width(20.dp)
                                .background(MaterialTheme.colorScheme.background)
                        )
                    } else {
                        Box(
                            Modifier.width(20.dp)
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
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
                }
                HorizontalDivider(color = AppColors.DividerDark)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        } // end of MAIN-mode buttons block (paired with `if (isIconsMode)` above)

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (role == "Responder") "No responses for this model yet"
                    else "No other model has responded to this one yet",
                    color = AppColors.TextTertiary, fontSize = 13.sp
                )
            }
        } else if (isIconsMode) {
            // Fan icons — focused icon list. Big emoji glyphs only;
            // no labels, no status icons, no progress fills. Tapping
            // a row opens the L3 Fan icons - pair detail.
            val rowsTotalCost = rows.sumOf { it.totalCost }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (role == "Initiator" && !activeAgentIcon.isNullOrBlank()) {
                    item(key = "icon-initiator-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                activeAgentIcon,
                                fontSize = 56.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        HorizontalDivider(color = AppColors.DividerDark, thickness = 2.dp)
                    }
                }
                items(rows, key = { it.key }) { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clickable {
                                onOpenPair(if (role == "Responder") p.sourceAgentId else p.answererAgentId)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (role == "Responder") {
                            // Initiator's agent-level icon, then the
                            // pair's responder icon (this model's reply
                            // to that source).
                            val initiatorIcon = agentIcons[p.sourceAgentId] ?: "⬜"
                            Text(initiatorIcon, fontSize = 40.sp, modifier = Modifier.padding(start = 8.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(p.icon ?: "⬜", fontSize = 40.sp)
                        } else {
                            // Initiator mode: this row's responder is
                            // pair.icon (the answerer's reply icon).
                            Text(p.icon ?: "⬜", fontSize = 40.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                        Spacer(modifier = Modifier.weight(1f))
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
                    item(key = "l2-icons-total-footer") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Total", fontSize = 14.sp, color = AppColors.Blue,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            Text(
                                formatCents(rowsTotalCost), fontSize = 12.sp,
                                color = AppColors.Blue, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                    }
                }
            }
        } else {
            val rowsTotalCost = rows.sumOf { it.totalCost }
            // Whole L2 list finished — every row would show ✅ on a
            // full green fill. Drop both per row (mirrors L1's allDone).
            val allDone = rows.isNotEmpty() && rows.all {
                (if (isIconsMode) it.iconStatus(runningSet) else it.effectiveStatus(runningSet)) == PairStatus.DONE
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.key }) { p ->
                    val otherAgentId = if (role == "Responder") p.sourceAgentId else p.answererAgentId
                    val effStatus = if (isIconsMode) p.iconStatus(runningSet)
                        else p.effectiveStatus(runningSet)
                    // Per-pair rows are binary — full green when the
                    // pair is DONE, empty otherwise. Mirrors the L1
                    // row-background progress bar.
                    val progressFraction = if (effStatus == PairStatus.DONE) 1f else 0f
                    val progressColor = AppColors.Green.copy(alpha = 0.30f)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .drawBehind {
                                if (!allDone && progressFraction > 0f) {
                                    drawRect(
                                        color = progressColor,
                                        size = Size(size.width * progressFraction, size.height)
                                    )
                                }
                            }
                            .padding(vertical = 6.dp)
                            .clickable { onOpenPair(if (role == "Responder") p.sourceAgentId else p.answererAgentId) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Leading cell:
                        //   - When the pair has its own icon (the
                        //     optional Fan Icons sweep landed one),
                        //     always show it — even after every row is
                        //     done, so the user can long-press to open
                        //     the unified Icon-lookup screen (the 6th
                        //     adapter). Tap forwards to the L3 open
                        //     like the rest of the row.
                        //   - Otherwise, fall back to the legacy
                        //     status glyph (✅ / ❌ / ⏳ / 🕓), dropped
                        //     entirely once every row is DONE to free
                        //     horizontal space.
                        val pairIcon = p.icon
                        if (!pairIcon.isNullOrBlank()) {
                            // Tap on the icon cell opens the unified
                            // Icon-lookup screen for this pair (6th
                            // adapter). The rest of the row stays
                            // tappable to open L3 — the icon cell's
                            // clickable shadows the row's clickable
                            // only on its own 20.dp width. Background
                            // stays transparent so the row's
                            // drawBehind progress fill shows through.
                            Box(
                                modifier = Modifier
                                    .width(20.dp)
                                    .clickable { actions.onOpenPairIconLookup(p.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(pairIcon, fontSize = 16.sp)
                            }
                        } else if (!allDone) {
                            val icon = when (effStatus) {
                                PairStatus.ERROR -> "❌"
                                PairStatus.DONE -> "✅"
                                PairStatus.RUNNING -> "⏳"
                                PairStatus.PENDING -> "🕓"
                            }
                            if (icon == "⏳") {
                                Box(
                                    Modifier.width(20.dp).background(progressColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AnimatedHourglass(fontSize = 16.sp)
                                }
                            } else {
                                Text(
                                    icon, fontSize = 16.sp,
                                    modifier = Modifier.width(20.dp).background(progressColor)
                                )
                            }
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
                            // Same trailing 8dp padding as the per-pair
                            // cost above so the totals column lines up
                            // pixel-perfect with the row costs.
                            Text(
                                formatCents(rowsTotalCost), fontSize = 11.sp,
                                color = AppColors.Blue, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 8.dp)
                            )
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
    onSwitchRole: (String) -> Unit,
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
    // agentId -> the agent's original report response. Drives the
    // initiator's text shown above (Initiator mode) or inline per
    // pair (Responder mode).
    val agentBodies: Map<String, String?> = remember(report) {
        report?.agents?.associate { it.agentId to it.responseBody } ?: emptyMap()
    }
    val isInitiator = role == "Initiator"
    // Initiator mode: the active model IS the source — its single
    // original response is shared by every pair, so show it once at
    // the top instead of repeating it on every row.
    val initiatorBody: String? = remember(report, answererKey) {
        report?.agents?.firstOrNull { "${it.provider}|${it.model}" == answererKey }?.responseBody
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        // 👁 → matching View Fan-out for this metaPromptName.
        val pendingOnePageHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewOnePageJump: (() -> Unit)? = pendingOnePageHolder?.let { holder ->
            {
                holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                    ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                    ?: com.ai.ui.shared.ViewJump.Main
            }
        }
        TitleBar(
            helpTopic = "secondary_fan_out_l2",
            title = "Fan out - one page",
            subject = subject,
            onOpenView = onOpenViewOnePageJump,
            onBackClick = onBack
        )
        com.ai.ui.shared.HardcodedSubjectRow(subject)
        // Active role + Switch role — mirrors the L2 list screen so
        // the one-page view can flip Initiator ⇄ Responder in place.
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
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (isInitiator) {
                // The active model's original response, once at the top.
                item(key = "initiator-response") {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            "$canonPid / $activeMdl — original response",
                            fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            initiatorBody ?: "(original response not found)",
                            fontSize = 13.sp, color = Color.White
                        )
                        HorizontalDivider(
                            color = AppColors.DividerDark, thickness = 2.dp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                // Then each answerer's reply to it.
                items(rows, key = { it.key }) { p ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            resolveModelLabel("${p.providerId}|${p.model}"),
                            fontSize = 12.sp, color = AppColors.Green, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            p.content ?: p.errorMessage?.let { "❌ $it" } ?: "⏳ pending",
                            fontSize = 13.sp, color = Color.White
                        )
                        HorizontalDivider(
                            color = AppColors.DividerDark, thickness = 2.dp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                // Responder mode: each row is a full pair — the
                // initiator's original response, then the active
                // model's reply to it.
                items(rows, key = { it.key }) { p ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            agentLabels[p.sourceAgentId] ?: p.sourceAgentId,
                            fontSize = 12.sp, color = AppColors.Blue, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            agentBodies[p.sourceAgentId] ?: "(original response not found)",
                            fontSize = 13.sp, color = AppColors.TextSecondary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "↳ $canonPid / $activeMdl",
                            fontSize = 12.sp, color = AppColors.Green, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            p.content ?: p.errorMessage?.let { "❌ $it" } ?: "⏳ pending",
                            fontSize = 13.sp, color = Color.White
                        )
                        HorizontalDivider(
                            color = AppColors.DividerDark, thickness = 2.dp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
