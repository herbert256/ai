package com.ai.ui.report.manage
import com.ai.ui.report.manage.view.*
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.FanOutRunKey
import com.ai.data.PairStatus
import com.ai.data.titleStatus
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.ReloadConfirmationDialog
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** L1 grouping preset for the Fan Meta screen.
 *  Meta models = group by the meta-worker model that produced the
 *  title+icon ([com.ai.data.PairState.titleModel]); Report models =
 *  group by the answerer/report model. */
enum class FanMetaGroupMode { META_MODELS, REPORT_MODELS }

/** Navigation state inside the Fan Meta drill-in. The Fan-Meta sibling
 *  of [FanOutNav] — a separate back-stack so the two screens are fully
 *  independent. */
sealed class FanMetaNav {
    object L1 : FanMetaNav()
    /** The 🐜 "Fan Meta workers" sub-screen — the per-meta-worker
     *  (model) grouping, moved off L1's old toggle. */
    object Workers : FanMetaNav()
    /** The flat "Fan Meta - All" title list (the L1 "Show all" button). */
    object L1All : FanMetaNav()
    data class L2(val answererKey: String, val role: String) : FanMetaNav()
    /** L2 scoped to one meta-worker model (the workers drill-in). */
    data class L2MetaModel(val metaModelKey: String) : FanMetaNav()
    /** [origin] records which screen opened this pair — "L2" (a Report-
     *  models L2), "L2MM:<metaModelKey>" (a meta-model L2), or "L1ALL"
     *  (the flat title list) — so back returns there instead of always
     *  synthesizing a Report-models L2 the user may never have visited. */
    data class L3(val answererKey: String, val sourceAgentId: String, val role: String, val origin: String = "L2") : FanMetaNav()
}

/** Custom Saver — serialises to a 5-string list so rememberSaveable
 *  survives rotation + process death. */
private val fanMetaNavSaver: Saver<FanMetaNav, Any> = Saver(
    save = { nav ->
        when (nav) {
            is FanMetaNav.L1 -> listOf("L1", "", "", "", "")
            is FanMetaNav.Workers -> listOf("WORKERS", "", "", "", "")
            is FanMetaNav.L1All -> listOf("L1ALL", "", "", "", "")
            is FanMetaNav.L2 -> listOf("L2", nav.answererKey, "", nav.role, "")
            is FanMetaNav.L2MetaModel -> listOf("L2MM", nav.metaModelKey, "", "", "")
            is FanMetaNav.L3 -> listOf("L3", nav.answererKey, nav.sourceAgentId, nav.role, nav.origin)
        }
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val l = list as List<String>
        when (l[0]) {
            "L1" -> FanMetaNav.L1
            "WORKERS" -> FanMetaNav.Workers
            "L1ALL" -> FanMetaNav.L1All
            "L2" -> FanMetaNav.L2(l[1], l[3].ifEmpty { "Responder" })
            "L2MM" -> FanMetaNav.L2MetaModel(l[1])
            "L3" -> FanMetaNav.L3(l[1], l[2], l[3].ifEmpty { "Responder" }, l.getOrNull(4)?.ifEmpty { "L2" } ?: "L2")
            else -> FanMetaNav.L1
        }
    }
)

/** Resolve an L3's [FanMetaNav.L3.origin] back to the screen that opened it. */
private fun fanMetaBackFromL3(n: FanMetaNav.L3): FanMetaNav = when {
    n.origin == "L1ALL" -> FanMetaNav.L1All
    n.origin.startsWith("L2MM:") -> FanMetaNav.L2MetaModel(n.origin.removePrefix("L2MM:"))
    else -> FanMetaNav.L2(n.answererKey, n.role)
}

/**
 * Parent of the Fan Meta drill-in — the per-pair title + icon batch
 * over a finished fan-out. Watches [FanOutEngine.runs], resolves the
 * [runKey] to a run, holds the [FanMetaNav] state, and routes to one
 * of the level Composables.
 *
 * Shares the engine + run state with [FanOutScreen] (one
 * `SecondaryResult` row per pair carries both the response and its
 * title+icon), but is otherwise a fully separate screen — its own nav,
 * back-stack, and help pages.
 */
@Composable
fun FanMetaScreen(
    engine: FanOutEngine,
    reportId: String,
    runKey: FanOutRunKey,
    actions: FanOutActions,
    /** Live in-flight pair ids for the fan-meta title batch. */
    runningMetaSet: Set<String> = emptySet(),
    /** Throttled pair ids for the fan-meta title batch. */
    throttledMetaSet: Set<String> = emptySet(),
    /** Cross-link to the Fan Out (responses) screen. */
    onShowResponses: () -> Unit = {},
    /** Report-wide icon/title refresh counter — bumped by Find-alt
     *  picks. The L3 re-reads the pair from disk on changes so a picked
     *  icon/title shows immediately. */
    iconRefreshTick: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val runs by engine.runs.collectAsState()
    val runState = runs[runKey]

    // One-shot seed from disk on entry. No periodic re-hydrate: the
    // fan-meta runner now mirrors each pair's title / icon / cost into
    // the engine flow live (IconGenerationManager.runFanMetaForPair calls
    // FanOutEngine.refreshPairFromDisk), so the title-status counters
    // advance without a disk poll.
    LaunchedEffect(reportId, runKey) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
    }

    var nav by rememberSaveable(runKey, stateSaver = fanMetaNavSaver) {
        mutableStateOf<FanMetaNav>(FanMetaNav.L1)
    }
    BackHandler {
        nav = when (val n = nav) {
            FanMetaNav.L1 -> { onBack(); return@BackHandler }
            FanMetaNav.Workers -> FanMetaNav.L1
            FanMetaNav.L1All -> FanMetaNav.L1
            is FanMetaNav.L2 -> FanMetaNav.L1
            is FanMetaNav.L2MetaModel -> FanMetaNav.Workers
            is FanMetaNav.L3 -> fanMetaBackFromL3(n)
        }
    }

    if (runState == null) {
        Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewJump: (() -> Unit)? = pendingHolder?.let {
                { it.value = com.ai.ui.shared.ViewJump.Main }
            }
            TitleBar(
                helpTopic = "fan_meta_l1",
                title = "Fan Meta",
                subject = "Loading the fan-out…",
                onOpenView = onOpenViewJump,
                onBackClick = onBack
            )
            Text("Loading…", color = AppColors.TextTertiary)
        }
        return
    }

    // Reload / delete / trace fire from both L1 and the 🐜 workers screen,
    // so they (and their confirm dialogs) are owned here at the router and
    // rendered regardless of which sub-screen is active.
    var confirmRelaunchMeta by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val l1RunId = runState.pairs.values.firstNotNullOfOrNull { it.titleRunId ?: it.iconRunId }
    val onReloadMeta: () -> Unit = { confirmRelaunchMeta = true }
    val onDeleteMeta: () -> Unit = { confirmDelete = true }
    val onTraceMeta: (() -> Unit)? = if (l1RunId != null && com.ai.data.ApiTracer.ladybugLinksEnabled)
        { { actions.onNavigateToTraceRunList(l1RunId) } } else null

    when (val n = nav) {
        FanMetaNav.L1 -> FanMetaL1Screen(
            run = runState,
            runningSet = runningMetaSet,
            throttledSet = throttledMetaSet,
            onShowResponses = onShowResponses,
            // Open L2 in the ANSWERER-scoped role ("Responder" / the else
            // branch) — the L1 model row is grouped and counted by answerer
            // (providerId|model), so it must open the same set. Opening the
            // "Initiator" filter showed a different subset and, when the
            // run's responders ≠ sources (independent Manual/TopRanked
            // subsets), matched nothing → L2 stuck on "collecting
            // information…" forever. The user can still flip the role in L2.
            onOpenModel = { ak -> nav = FanMetaNav.L2(ak, "Responder") },
            onOpenWorkers = { nav = FanMetaNav.Workers },
            onOpenTitles = { nav = FanMetaNav.L1All },
            onReload = onReloadMeta,
            onDelete = onDeleteMeta,
            onTrace = onTraceMeta,
            onBack = onBack
        )
        FanMetaNav.Workers -> FanMetaWorkersScreen(
            run = runState,
            runningSet = runningMetaSet,
            throttledSet = throttledMetaSet,
            onOpenMetaModel = { metaKey -> nav = FanMetaNav.L2MetaModel(metaKey) },
            onReload = onReloadMeta,
            onDelete = onDeleteMeta,
            onTrace = onTraceMeta,
            onBack = { nav = FanMetaNav.L1 }
        )
        FanMetaNav.L1All -> FanMetaAllScreen(
            run = runState,
            onOpenPair = { ak, srcAgentId, r ->
                nav = FanMetaNav.L3(ak, srcAgentId, r, origin = "L1ALL")
            },
            onBack = { nav = FanMetaNav.L1 }
        )
        is FanMetaNav.L2 -> FanMetaL2Screen(
            run = runState,
            answererKey = n.answererKey,
            role = n.role,
            actions = actions,
            onSwitchRole = { newRole -> nav = FanMetaNav.L2(n.answererKey, newRole) },
            onOpenPair = { srcAgentId ->
                nav = FanMetaNav.L3(n.answererKey, srcAgentId, n.role, origin = "L2")
            },
            onBack = { nav = FanMetaNav.L1 }
        )
        is FanMetaNav.L2MetaModel -> FanMetaMetaModelScreen(
            run = runState,
            metaModelKey = n.metaModelKey,
            onOpenPair = { ak, srcAgentId ->
                nav = FanMetaNav.L3(ak, srcAgentId, "Responder", origin = "L2MM:${n.metaModelKey}")
            },
            onBack = { nav = FanMetaNav.Workers }
        )
        is FanMetaNav.L3 -> FanMetaL3Screen(
            engine = engine,
            run = runState,
            answererKey = n.answererKey,
            sourceAgentId = n.sourceAgentId,
            role = n.role,
            actions = actions,
            iconRefreshTick = iconRefreshTick,
            onStepSource = { newSourceAgentId ->
                nav = FanMetaNav.L3(n.answererKey, newSourceAgentId, n.role, n.origin)
            },
            onBack = { nav = fanMetaBackFromL3(n) }
        )
    }

    // -----------------------------------------------------------------
    // Confirmation dialogs — shared by L1 + the 🐜 workers screen.
    // -----------------------------------------------------------------
    if (confirmRelaunchMeta) {
        ReloadConfirmationDialog(
            target = "",
            title = "Clear and re-run Fan Meta?",
            message = "Clear every pair's title + icon and re-run the Fan Meta batch. The fan-out responses are kept.",
            confirmLabel = "Re-run",
            onConfirm = {
                confirmRelaunchMeta = false
                actions.onRelaunchFanMeta(runState.key)
            },
            onDismiss = { confirmRelaunchMeta = false }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Fan Meta?") },
            text = {
                Text("Drop every title + icon (and their cost) for this run's ${runState.totalPairs} pair${if (runState.totalPairs == 1) "" else "s"}. The fan-out responses themselves are kept. Can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // Fire-and-forget: clearFanMeta runs on the engine's scope
                    // (cancels the fan-meta batch, clears the title/icon state and
                    // re-hydrates). Leave at once — no blocking popup; the fan-out
                    // L1 drops the icons when the background clear lands.
                    actions.onClearFanMeta(runState.key)
                    onBack()
                }) { Text("Delete", color = AppColors.DangerAccent, maxLines = 1, softWrap = false) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", maxLines = 1, softWrap = false) }
            }
        )
    }
}
