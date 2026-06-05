package com.ai.ui.report.manage
import com.ai.ui.report.manage.view.*
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ai.data.FanOutRunKey
import com.ai.data.PairStatus
import com.ai.data.titleStatus
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
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
    /** The flat "Fan Meta - All" title list (the L1 "Show all" button). */
    object L1All : FanMetaNav()
    data class L2(val answererKey: String, val role: String) : FanMetaNav()
    /** L2 scoped to one meta-worker model (the "Meta models" grouping). */
    data class L2MetaModel(val metaModelKey: String) : FanMetaNav()
    data class L3(val answererKey: String, val sourceAgentId: String, val role: String) : FanMetaNav()
}

/** Custom Saver — serialises to a 4-string list so rememberSaveable
 *  survives rotation + process death. */
private val fanMetaNavSaver: Saver<FanMetaNav, Any> = Saver(
    save = { nav ->
        when (nav) {
            is FanMetaNav.L1 -> listOf("L1", "", "", "")
            is FanMetaNav.L1All -> listOf("L1ALL", "", "", "")
            is FanMetaNav.L2 -> listOf("L2", nav.answererKey, "", nav.role)
            is FanMetaNav.L2MetaModel -> listOf("L2MM", nav.metaModelKey, "", "")
            is FanMetaNav.L3 -> listOf("L3", nav.answererKey, nav.sourceAgentId, nav.role)
        }
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val l = list as List<String>
        when (l[0]) {
            "L1" -> FanMetaNav.L1
            "L1ALL" -> FanMetaNav.L1All
            "L2" -> FanMetaNav.L2(l[1], l[3].ifEmpty { "Responder" })
            "L2MM" -> FanMetaNav.L2MetaModel(l[1])
            "L3" -> FanMetaNav.L3(l[1], l[2], l[3].ifEmpty { "Responder" })
            else -> FanMetaNav.L1
        }
    }
)

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
            FanMetaNav.L1All -> FanMetaNav.L1
            is FanMetaNav.L2 -> FanMetaNav.L1
            is FanMetaNav.L2MetaModel -> FanMetaNav.L1
            is FanMetaNav.L3 -> FanMetaNav.L2(n.answererKey, n.role)
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

    when (val n = nav) {
        FanMetaNav.L1 -> FanMetaL1Screen(
            run = runState,
            runningSet = runningMetaSet,
            throttledSet = throttledMetaSet,
            actions = actions,
            onShowResponses = onShowResponses,
            // Fan Meta starts at the Initiator view (active model is the
            // source) so the user sees their model's result up top.
            onOpenModel = { ak -> nav = FanMetaNav.L2(ak, "Initiator") },
            onOpenMetaModel = { metaKey -> nav = FanMetaNav.L2MetaModel(metaKey) },
            onOpenTitles = { nav = FanMetaNav.L1All },
            onBack = onBack
        )
        FanMetaNav.L1All -> FanMetaAllScreen(
            run = runState,
            onOpenPair = { ak, srcAgentId, r ->
                nav = FanMetaNav.L3(ak, srcAgentId, r)
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
                nav = FanMetaNav.L3(n.answererKey, srcAgentId, n.role)
            },
            onBack = { nav = FanMetaNav.L1 }
        )
        is FanMetaNav.L2MetaModel -> FanMetaMetaModelScreen(
            run = runState,
            metaModelKey = n.metaModelKey,
            onOpenPair = { ak, srcAgentId ->
                nav = FanMetaNav.L3(ak, srcAgentId, "Responder")
            },
            onBack = { nav = FanMetaNav.L1 }
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
                nav = FanMetaNav.L3(n.answererKey, newSourceAgentId, n.role)
            },
            onBack = { nav = FanMetaNav.L2(n.answererKey, n.role) }
        )
    }
}
