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
import com.ai.data.AppService
import com.ai.data.FanOutRunKey
import com.ai.data.SecondaryResult
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.FanOutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation state inside a Fan Out detail flow.
 * One sealed class replaces the five flat `rememberSaveable` flags
 * the old FanOutDrillInView used (selectedModelKey, selectedRole,
 * l3AnswererKey, l3SourceAgentId, showOnePageView).
 *
 * Role is stringly-typed for save-bundle compatibility (the saver
 * stores it as String). Valid values: "Responder", "Initiator".
 */
/** Top-level mode the FanOutScreen runs in.
 *  - [MAIN]: the existing fan-out drill-in (per-pair response,
 *    fan-in, etc.). Status / counters derive from `pair.status`
 *    + the running/throttled sets for the main response.
 *  - [META]: the Fan Meta batch drill-in. Status / counters
 *    derive from `pair.titleStatus(...)` over the
 *    runningFanMetaPairs / throttledFanMetaPairs sets. The
 *    button drives [com.ai.viewmodel.IconGenerationManager.runFanMetaBatch]
 *    instead of the fan-out runner. */
enum class FanOutMode { MAIN, META }

/** L1 grouping preset for the Fan Meta screen (META mode only).
 *  Meta models = group by the meta-worker model that produced the
 *  title+icon ([com.ai.data.PairState.titleModel]); Report models =
 *  the historical grouping by the answerer/report model. */
enum class FanMetaGroupMode { META_MODELS, REPORT_MODELS }

sealed class FanOutNav {
    object L1 : FanOutNav()
    data class L2(val answererKey: String, val role: String) : FanOutNav()
    data class L3(val answererKey: String, val sourceAgentId: String, val role: String) : FanOutNav()
    data class L2OnePage(val answererKey: String, val role: String) : FanOutNav()
    object L1Meta : FanOutNav()
    data class L2Meta(val answererKey: String, val role: String) : FanOutNav()
    /** Fan Meta L2 in "Meta models" mode — scoped to one meta-worker
     *  model (`provider/model`), listing the pairs it titled. */
    data class L2MetaModel(val metaModelKey: String) : FanOutNav()
}

/** Custom Saver — serialises to a 4-string list so rememberSaveable
 *  survives rotation + process death. */
private val fanOutNavSaver: Saver<FanOutNav, Any> = Saver(
    save = { nav ->
        when (nav) {
            is FanOutNav.L1 -> listOf("L1", "", "", "")
            is FanOutNav.L2 -> listOf("L2", nav.answererKey, "", nav.role)
            is FanOutNav.L3 -> listOf("L3", nav.answererKey, nav.sourceAgentId, nav.role)
            is FanOutNav.L2OnePage -> listOf("L2OP", nav.answererKey, "", nav.role)
            is FanOutNav.L1Meta -> listOf("L1TI", "", "", "")
            is FanOutNav.L2Meta -> listOf("L2TI", nav.answererKey, "", nav.role)
            is FanOutNav.L2MetaModel -> listOf("L2MM", nav.metaModelKey, "", "")
        }
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val l = list as List<String>
        when (l[0]) {
            "L1" -> FanOutNav.L1
            "L2" -> FanOutNav.L2(l[1], l[3].ifEmpty { "Responder" })
            "L3" -> FanOutNav.L3(l[1], l[2], l[3].ifEmpty { "Responder" })
            "L2OP" -> FanOutNav.L2OnePage(l[1], l[3].ifEmpty { "Responder" })
            "L1TI" -> FanOutNav.L1Meta
            "L2TI" -> FanOutNav.L2Meta(l[1], l[3].ifEmpty { "Responder" })
            "L2MM" -> FanOutNav.L2MetaModel(l[1])
            else -> FanOutNav.L1
        }
    }
)

/**
 * Bundle of every callback the L1/L2/L3 screens need, packed into
 * one parameter so each screen's signature stays under the JVM
 * 64 KB per-method limit. Mirrors the GenerationPhaseHandlers /
 * TranslationLifecycleCallbacks pattern.
 */
data class FanOutActions(
    /** Returns the delete Job so the caller can show a "Deleting…"
     *  popup and only navigate back once the run is really gone. */
    val onDeleteRun: (FanOutRunKey) -> kotlinx.coroutines.Job? = { null },
    /** Fan-Meta 🗑 — clears each pair's title + icon state (and the
     *  fan-out iconCalls audit rows), keeping the fan-out itself. */
    val onClearFanMeta: (FanOutRunKey) -> kotlinx.coroutines.Job? = { null },
    val onRerunComplete: (FanOutRunKey) -> Unit = {},
    val onRemoveFailedPairs: (FanOutRunKey) -> Unit = {},
    /** Drop only the errored pairs whose model is currently benched. */
    val onRemoveBenchedPairs: (FanOutRunKey) -> Unit = {},
    val onRestartFailedPairs: (FanOutRunKey) -> Unit = {},
    val onRemoveFailedPairsForModel: (FanOutRunKey, String, String) -> Unit = { _, _, _ -> },
    val onRestartFailedPairsForModel: (FanOutRunKey, String, String) -> Unit = { _, _, _ -> },
    val onRerunPair: (FanOutRunKey, String) -> Unit = { _, _ -> },        // (runKey, pairKey)
    val onCancelPair: (FanOutRunKey, String) -> Unit = { _, _ -> },       // (runKey, pairKey)
    val onDeleteModelFromRun: (FanOutRunKey, String, String) -> Unit = { _, _, _ -> },
    val onRunFanIn: (FanOutRunKey) -> Unit = {},                          // opens fan-in picker
    val onCreateNewFanOut: () -> Unit = {},
    val onCreateReportFromFanOut: (FanOutRunKey, String, String) -> Unit = { _, _, _ -> },
    val onNavigateToTraceFile: (String) -> Unit = {},
    /** Open the trace list filtered to one batch's runId. Wired
     *  on the L1 🐞 icon so the user can jump from a run overview
     *  straight to the API calls that produced it. */
    val onNavigateToTraceRunList: (String) -> Unit = {},
    val onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    val onNavigateToInternalPromptEdit: (String) -> Unit = {},
    val onOpenSecondary: (String) -> Unit = {},                            // open arbitrary SecondaryResult detail
    /** Open the unified Icon-lookup screen for a fan-out pair —
     *  the 6th adapter. Wired on the L2 row long-press and the
     *  L3 big centred icon. Argument is the pair's
     *  [com.ai.data.SecondaryResult.id]. */
    val onOpenPairIconLookup: (String) -> Unit = {},
    /** L3 META "Find alternative icon" — opens the big model picker
     *  straight onto the per-pair icon Find-alt flow. Argument is the
     *  pair's [com.ai.data.SecondaryResult.id]. */
    val onFindAlternativePairIcon: (String) -> Unit = {},
    /** L3 META "Find alternative title" — opens the big model picker
     *  straight onto the per-pair title Find-alt flow. */
    val onFindAlternativePairTitle: (String) -> Unit = {},
    /** Clear the title+icon error state on every errored pair in this
     *  fan-out, so they read as fresh-pending without dropping the row. */
    val onClearFanMetaErrors: (FanOutRunKey) -> Unit = {},
    /** Clear the errored pairs' state, then re-fire the fan-meta batch
     *  (its pending filter picks them up). */
    val onRestartFanMetaErrors: (FanOutRunKey) -> Unit = {}
)

/**
 * Parent of the redesigned Fan Out drill-in. Watches
 * [FanOutEngine.runs], resolves the [runKey] to a [FanOutRunState],
 * holds the [FanOutNav] state, and routes to one of the three
 * level Composables.
 *
 * Replaces FanOutDrillInView in SecondaryResultsScreen.kt. Phase E
 * deletes the old composable; this is the only entry point now.
 */
@Composable
fun FanOutScreen(
    engine: FanOutEngine,
    reportId: String,
    runKey: FanOutRunKey,
    actions: FanOutActions,
    /** Pair ids currently blocked inside
     *  [com.ai.data.ProviderThrottle.acquire] (per-minute rate
     *  limit). Surfaces as the L1 Throttled counter. */
    throttledSet: Set<String> = emptySet(),
    /** When non-MAIN, the L1 / L2 / L3 screens read pair status
     *  from a different lens (e.g. icon-chain lifecycle for
     *  ICONS). Title bars and action buttons swap accordingly. */
    mode: FanOutMode = FanOutMode.MAIN,
    /** Switch the drill-in back to MAIN mode — the L1 "Responses"
     *  mode-toggle. */
    onShowResponses: () -> Unit = {},
    /** Live in-flight pair ids for the fan-meta batch. Empty
     *  unless [mode] is [FanOutMode.META]. */
    runningMetaSet: Set<String> = emptySet(),
    /** Throttled pair ids for the fan-meta batch. */
    throttledMetaSet: Set<String> = emptySet(),
    /** Called when the user taps "Fan Meta" on the L1 main screen with
     *  no fan-meta run yet (after the confirm). Wires to
     *  [com.ai.viewmodel.IconGenerationManager.runFanMetaBatch] plus a
     *  switch to META mode. */
    onLaunchFanMeta: (FanOutRunKey) -> Unit = {},
    /** Switch the drill-in to META mode without launching a batch. */
    onShowFanMeta: () -> Unit = {},
    /** Report-wide icon/title refresh counter — bumped by Find-alt
     *  picks. The META L3 re-reads the pair from disk on changes so a
     *  picked icon/title shows immediately (the engine snapshot stays
     *  stale after a pick). */
    iconRefreshTick: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val runs by engine.runs.collectAsState()
    val runState = runs[runKey]

    // Hydrate on first entry so a freshly-opened drill-in (after a
    // process restart, say) picks up the persisted rows even if the
    // report-screen orchestrator hasn't fired yet. Plus a periodic
    // re-hydrate every 3s — the runner's in-memory transitionPair
    // can miss a pair (cancellation race, etc.), leaving a pair
    // stuck at status=RUNNING / PENDING in-memory while the disk
    // row reads DONE. Re-reading disk on a tick keeps the L1 stats
    // honest. Stops once we're certain the runner is idle:
    // every pair is DONE / ERROR on the in-memory snapshot.
    LaunchedEffect(reportId, runKey) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        while (true) {
            kotlinx.coroutines.delay(3_000)
            val current = engine.runs.value[runKey] ?: continue
            val settled = current.pairs.values.all {
                it.status == com.ai.data.PairStatus.DONE ||
                    it.status == com.ai.data.PairStatus.ERROR
            }
            // Runner is idle — stop the 3 s re-hydrate ticker (was `continue`,
            // which left the timer spinning forever despite the comment above).
            if (settled) break
            withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
        }
    }

    var nav by rememberSaveable(runKey, stateSaver = fanOutNavSaver) {
        mutableStateOf<FanOutNav>(FanOutNav.L1)
    }
    BackHandler {
        nav = when (val n = nav) {
            FanOutNav.L1 -> { onBack(); return@BackHandler }
            is FanOutNav.L2 -> FanOutNav.L1
            is FanOutNav.L3 -> FanOutNav.L2(n.answererKey, n.role)
            is FanOutNav.L2OnePage -> FanOutNav.L2(n.answererKey, n.role)
            FanOutNav.L1Meta -> FanOutNav.L1
            is FanOutNav.L2Meta -> FanOutNav.L2(n.answererKey, n.role)
            is FanOutNav.L2MetaModel -> FanOutNav.L1
        }
    }

    if (runState == null) {
        // Either still hydrating, or no persisted rows exist yet
        // (run launched ms ago). Show a minimal title bar so back
        // works, then a "loading" sentinel.
        Column(modifier = Modifier.fillMaxSize().background(AppColors.AppBackground).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            // 👁 → Main View (no run state yet → no metaPromptName).
            val pendingHolder = com.ai.ui.shared.LocalPendingViewOverManage.current
            val onOpenViewJump: (() -> Unit)? = pendingHolder?.let {
                { it.value = com.ai.ui.shared.ViewJump.Main }
            }
            TitleBar(
                helpTopic = "secondary_fan_out_l1",
                title = when (mode) {
                    FanOutMode.META -> "Fan Meta"
                    else -> "Fan out"
                },
                subject = "Loading the fan-out…",
                onOpenView = onOpenViewJump,
                onBackClick = onBack
            )
            Text("Loading…", color = AppColors.TextTertiary)
        }
        return
    }

    // In ICONS / TITLES mode, the L1 / L2 / L3 classifiers read the
    // icon- / title-batch status off pair.iconStatus(...) /
    // pair.titleStatus(...) — fed by these sets.
    // MAIN mode reads PairStatus directly from the engine flow, so it
    // needs no running-set; only META's title-status lens uses one.
    val effectiveRunningSet = when (mode) {
        FanOutMode.META -> runningMetaSet
        else -> emptySet()
    }
    val effectiveThrottledSet = when (mode) {
        FanOutMode.META -> throttledMetaSet
        else -> throttledSet
    }

    when (val n = nav) {
        is FanOutNav.L1 -> FanOutL1Screen(
            engine = engine,
            run = runState,
            runningSet = effectiveRunningSet,
            throttledSet = effectiveThrottledSet,
            actions = actions,
            mode = mode,
            onShowResponses = onShowResponses,
            onLaunchFanMeta = onLaunchFanMeta,
            onShowFanMeta = onShowFanMeta,
            // Fan Meta starts at the Initiator view (active model is the
            // source) so the user sees their model's result up top and a
            // list of what every other model produced in reply. Fan out
            // starts at Responder (the active model answers every row).
            onOpenModel = { ak -> nav = FanOutNav.L2(ak, if (mode != FanOutMode.MAIN) "Initiator" else "Responder") },
            onOpenMetaModel = { metaKey -> nav = FanOutNav.L2MetaModel(metaKey) },
            onOpenTitles = { nav = FanOutNav.L1Meta },
            onBack = onBack
        )
        is FanOutNav.L2 -> FanOutL2Screen(
            engine = engine,
            run = runState,
            answererKey = n.answererKey,
            role = n.role,
            actions = actions,
            mode = mode,
            onSwitchRole = { newRole -> nav = FanOutNav.L2(n.answererKey, newRole) },
            onOpenPair = { sourceAgentId ->
                nav = FanOutNav.L3(n.answererKey, sourceAgentId, n.role)
            },
            onOpenOnePage = { nav = FanOutNav.L2OnePage(n.answererKey, n.role) },
            // L2's "Fan Meta" button flips the top-level mode to META;
            // the FanOutScreen nav state is preserved across the mode
            // change (single composition), so the user lands on Fan Meta
            // L2 for the same model.
            onOpenTitles = onShowFanMeta,
            onBack = { nav = FanOutNav.L1 }
        )
        is FanOutNav.L3 -> FanOutL3Screen(
            engine = engine,
            run = runState,
            answererKey = n.answererKey,
            sourceAgentId = n.sourceAgentId,
            role = n.role,
            actions = actions,
            mode = mode,
            iconRefreshTick = iconRefreshTick,
            onStepSource = { newSourceAgentId ->
                nav = FanOutNav.L3(n.answererKey, newSourceAgentId, n.role)
            },
            onBack = { nav = FanOutNav.L2(n.answererKey, n.role) }
        )
        is FanOutNav.L2OnePage -> FanOutL2OnePageScreen(
            engine = engine,
            run = runState,
            answererKey = n.answererKey,
            role = n.role,
            onSwitchRole = { newRole -> nav = FanOutNav.L2OnePage(n.answererKey, newRole) },
            onBack = { nav = FanOutNav.L2(n.answererKey, n.role) }
        )
        FanOutNav.L1Meta -> FanOutL1MetaScreen(
            run = runState,
            onOpenPair = { ak, srcAgentId, r ->
                nav = FanOutNav.L3(ak, srcAgentId, r)
            },
            onBack = { nav = FanOutNav.L1 }
        )
        is FanOutNav.L2Meta -> FanOutL2MetaScreen(
            run = runState,
            answererKey = n.answererKey,
            role = n.role,
            onSwitchRole = { newRole -> nav = FanOutNav.L2Meta(n.answererKey, newRole) },
            onOpenPair = { srcAgentId ->
                nav = FanOutNav.L3(n.answererKey, srcAgentId, n.role)
            },
            onBack = { nav = FanOutNav.L2(n.answererKey, n.role) }
        )
        is FanOutNav.L2MetaModel -> FanOutL2MetaModelScreen(
            run = runState,
            metaModelKey = n.metaModelKey,
            actions = actions,
            onOpenPair = { ak, srcAgentId ->
                nav = FanOutNav.L3(ak, srcAgentId, "Responder")
            },
            onBack = { nav = FanOutNav.L1 }
        )
    }
}

/** Resolve a (providerId, model) "answerer key" to the human label
 *  the L1 / L2 / L3 rows display — uses the canonical-cased
 *  provider id from [AppService.findById] when registered. */
internal fun resolveModelLabel(answererKey: String): String {
    val parts = answererKey.split("|")
    if (parts.size != 2) return answererKey
    val (pid, model) = parts
    val canon = AppService.findById(pid)?.id ?: pid
    return "$canon / ${com.ai.ui.shared.shortModelName(model)}"
}
