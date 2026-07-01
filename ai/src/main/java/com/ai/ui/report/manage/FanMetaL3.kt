package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.data.FanOutRunState
import com.ai.data.PairState
import com.ai.data.Report
import com.ai.data.ReportStorage
import com.ai.data.SecondaryResultStorage
import com.ai.data.toPairState
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.modelLabel
import com.ai.ui.shared.shortModelName
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fan Meta L3 — the single-pair metadata screen. Resolves the pair
 * from (answererKey, sourceAgentId, role), then foregrounds the
 * generated icon + title and the two per-pair Find-alt buttons. A
 * horizontal swipe steps between the L2-scoped pairs (right = prev,
 * left = next). Re-reads the pair from disk on [iconRefreshTick] so a
 * picked icon/title shows immediately (the engine snapshot stays stale
 * after a Find-alt pick; it only bumps the tick + writes disk).
 *
 * This is the Fan-Meta sibling of [FanOutL3Screen] — the two no longer
 * share a screen / mode flag.
 */
@Composable
internal fun FanMetaL3Screen(
    engine: FanOutEngine,
    run: FanOutRunState,
    answererKey: String,
    sourceAgentId: String,
    role: String,
    actions: FanOutActions,
    iconRefreshTick: Int = 0,
    onStepSource: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // The pair we're viewing — same resolution as the Fan Out L3.
    val pair = remember(run, answererKey, sourceAgentId, role) {
        if (role == "Responder") {
            run.pairs.values.firstOrNull {
                "${it.providerId}|${it.model}" == answererKey && it.sourceAgentId == sourceAgentId
            }
        } else {
            run.pairs.values.firstOrNull {
                it.answererAgentId == sourceAgentId &&
                    run.pairs.values.any { other ->
                        other.answererAgentId == it.sourceAgentId &&
                            "${other.providerId}|${other.model}" == answererKey
                    }
            }
        }
    }

    if (pair == null) {
        Column(Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            val pendingHolderEmpty = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewEmptyJump: (() -> Unit)? = pendingHolderEmpty?.let { holder ->
                {
                    holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                        ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                        ?: com.ai.ui.shared.ViewJump.Main
                }
            }
            TitleBar(
                helpTopic = "fan_meta_l3",
                title = "Fan Meta - pair",
                subject = "This pair no longer exists",
                onOpenView = onOpenViewEmptyJump,
                onBackClick = onBack
            )
            Text("Pair no longer exists.", color = AppColors.TextTertiary)
        }
        return
    }

    // L2 scope for prev/next stepping — must match L2's VISIBLE order
    // (label, not timestamp), or swipe/Prev-Next jumps to a seemingly
    // random pair that isn't the adjacent row in the list.
    val l3Report by produceState<Report?>(initialValue = null, run.reportId) {
        value = withContext(Dispatchers.IO) { ReportStorage.getReport(context, run.reportId) }
    }
    val l3AgentLabels: Map<String, String> = remember(l3Report) {
        l3Report?.agents?.associate { it.agentId to resolveModelLabel("${it.provider}|${it.model}") }
            ?: emptyMap()
    }
    fun l2RowLabel(p: PairState): String = if (role == "Responder") {
        l3AgentLabels[p.sourceAgentId] ?: p.sourceAgentId
    } else {
        resolveModelLabel("${p.providerId}|${p.model}")
    }
    val l2Rows = remember(run, answererKey, role, l3AgentLabels) {
        when (role) {
            "Initiator" -> run.pairs.values.filter {
                run.pairs.values.any { other ->
                    other.answererAgentId == it.sourceAgentId &&
                        "${other.providerId}|${other.model}" == answererKey
                }
            }
            else -> run.pairs.values.filter { "${it.providerId}|${it.model}" == answererKey }
        }.sortedWith(compareBy { p -> l2RowLabel(p).lowercase() })
    }
    val curIdx = l2Rows.indexOfFirst { it.key == pair.key }
    val prev = if (curIdx > 0) l2Rows[curIdx - 1] else null
    val next = if (curIdx in 0 until l2Rows.size - 1) l2Rows[curIdx + 1] else null

    val answererLabel = modelLabel(pair.providerId, pair.model)
    val answererProviderService = remember(pair.providerId) {
        AppService.findById(pair.providerId)
    }

    var confirmDelete by remember { mutableStateOf(false) }

    // Re-read the row from disk on each refresh tick so picked
    // icon/title/titleModel land here without leaving the screen.
    val fresh by produceState(initialValue = pair, pair.id, run.reportId, iconRefreshTick) {
        value = withContext(Dispatchers.IO) {
            SecondaryResultStorage.get(context, run.reportId, pair.id)
                ?.toPairState(pair.answererAgentId) ?: pair
        }
    }
    val icon = fresh.icon?.takeIf { it.isNotBlank() }
    val title = fresh.title?.takeIf { it.isNotBlank() }
    val metaModel = fresh.titleModel?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }?.let { shortModelName(it) } ?: "—"

    // Horizontal swipe steps to the prev / next pair (replaces the old
    // Prev/Next buttons): swipe right → previous, swipe left → next —
    // same direction convention as the Manage-hub report swipe.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    val swipeDragX = remember { mutableFloatStateOf(0f) }
    val goPrev: () -> Unit = {
        prev?.let { if (role == "Responder") onStepSource(it.sourceAgentId) else onStepSource(it.answererAgentId) }
    }
    val goNext: () -> Unit = {
        next?.let { if (role == "Responder") onStepSource(it.sourceAgentId) else onStepSource(it.answererAgentId) }
    }

    Column(
        Modifier.fillMaxSize().background(AppColors.AppBackground).padding(16.dp)
            .pointerInput(prev, next, role) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDragX.floatValue = 0f },
                    onDragEnd = {
                        val dx = swipeDragX.floatValue
                        when {
                            dx > swipeThresholdPx -> goPrev()
                            dx < -swipeThresholdPx -> goNext()
                        }
                        swipeDragX.floatValue = 0f
                    },
                    onDragCancel = { swipeDragX.floatValue = 0f },
                    onHorizontalDrag = { _, d -> swipeDragX.floatValue += d }
                )
            }
    ) {
        val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
        val onOpenViewJump: (() -> Unit)? = pendingHolder?.let { holder ->
            {
                holder.value = run.metaPrompt.name.takeIf { it.isNotBlank() }
                    ?.let { com.ai.ui.shared.ViewJump.FanOut(it) }
                    ?: com.ai.ui.shared.ViewJump.Main
            }
        }
        TitleBar(
            helpTopic = "fan_meta_l3",
            title = "Fan Meta - pair",
            reportIcon = com.ai.ui.shared.LocalReportIcon.current,
            subject = answererLabel,
            onBackClick = onBack,
            onOpenView = onOpenViewJump,
            onInfo = answererProviderService?.let { svc ->
                { actions.onNavigateToModelInfo(svc, pair.model) }
            },
            onReload = { actions.onRerunPair(run.key, pair.key) },
            onDelete = { confirmDelete = true }
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Big, centered, the found icon.
            Text(icon ?: com.ai.data.MetadataIconsHolder.current.label, fontSize = 72.sp, color = AppColors.TextPrimary)
            Spacer(Modifier.height(20.dp))
            // Green, big, the found title.
            Text(
                title ?: "(no title yet)",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = if (title != null) AppColors.SuccessAccent else AppColors.TextTertiary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            // Two model lines.
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Fan-out model:  ${shortModelName(pair.model)}",
                    fontSize = 14.sp, color = AppColors.TextPrimary,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Meta model:     $metaModel",
                    fontSize = 14.sp, color = AppColors.TextPrimary,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Two Find-alt buttons.
        OutlinedButton(
            onClick = { actions.onFindAlternativePairIcon(pair.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Find alternative icon", maxLines = 1, softWrap = false) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { actions.onFindAlternativePairTitle(pair.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = AppColors.outlinedButtonColors()
        ) { Text("Find alternative title", maxLines = 1, softWrap = false) }

        // Swipe ← / → steps through the L2-scoped pair list (the small
        // hint replaces the old Prev/Next buttons).
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "← swipe   ·   swipe →",
                fontSize = 12.sp, color = AppColors.TextTertiary,
                maxLines = 1, softWrap = false
            )
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
                }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
