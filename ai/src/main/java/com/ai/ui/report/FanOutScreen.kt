package com.ai.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
 *  - [ICONS]: the fan-icons batch drill-in. Status / counters
 *    derive from `pair.iconStatus(...)` over the
 *    runningFanIcons / throttledFanIcons sets. Tier-1/2/3
 *    buttons drive [com.ai.viewmodel.ReportViewModel.runFanIconsBatch]
 *    instead of the fan-out runner. */
enum class FanOutMode { MAIN, ICONS }

sealed class FanOutNav {
    object L1 : FanOutNav()
    data class L2(val answererKey: String, val role: String) : FanOutNav()
    data class L3(val answererKey: String, val sourceAgentId: String, val role: String) : FanOutNav()
    data class L2OnePage(val answererKey: String, val role: String) : FanOutNav()
    object L1Icons : FanOutNav()
    data class L2Icons(val answererKey: String, val role: String) : FanOutNav()
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
            is FanOutNav.L1Icons -> listOf("L1IC", "", "", "")
            is FanOutNav.L2Icons -> listOf("L2IC", nav.answererKey, "", nav.role)
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
            "L1IC" -> FanOutNav.L1Icons
            "L2IC" -> FanOutNav.L2Icons(l[1], l[3].ifEmpty { "Responder" })
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
    /** ICONS-mode 🗑 — clears just the fan-icons (each pair's icon
     *  state + the fan-out iconCalls audit rows), keeping the
     *  fan-out itself. Returns the Job for the same "Deleting…"
     *  popup treatment as [onDeleteRun]. */
    val onClearFanIcons: (FanOutRunKey) -> kotlinx.coroutines.Job? = { null },
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
    val onRunModelFanIn: (FanOutRunKey, String, String) -> Unit = { _, _, _ -> },
    val onCreateReportFromFanOut: (FanOutRunKey, String, String) -> Unit = { _, _, _ -> },
    val onNavigateToTraceFile: (String) -> Unit = {},
    val onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    val onNavigateToInternalPromptEdit: (String) -> Unit = {},
    val onOpenSecondary: (String) -> Unit = {}                             // open arbitrary SecondaryResult detail
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
    /** Live in-flight pair ids — passed from the parent to bridge
     *  the legacy `runningFanOutPairs` StateFlow into the new
     *  screens. Each level's classifier consults this set to
     *  promote a disk-derived PENDING into RUNNING for pairs whose
     *  per-pair coroutine has acquired its throttle permit. Empty
     *  set is a valid degenerate case (no in-flight info — every
     *  pair reads as the disk says). */
    runningSet: Set<String> = emptySet(),
    /** Pair ids currently blocked inside
     *  [com.ai.data.ProviderThrottle.acquire] (per-minute rate
     *  limit). Surfaces as the L1 Throttled counter. */
    throttledSet: Set<String> = emptySet(),
    /** When non-MAIN, the L1 / L2 / L3 screens read pair status
     *  from a different lens (e.g. icon-chain lifecycle for
     *  ICONS). Title bars and action buttons swap accordingly. */
    mode: FanOutMode = FanOutMode.MAIN,
    /** Live in-flight pair ids for the fan-icons batch. Empty
     *  unless [mode] is [FanOutMode.ICONS]. */
    runningIconsSet: Set<String> = emptySet(),
    /** Throttled pair ids for the fan-icons batch. Empty unless
     *  [mode] is [FanOutMode.ICONS]. */
    throttledIconsSet: Set<String> = emptySet(),
    /** Called when the user taps "Icons" on the L1 main screen with
     *  no fan-icons run yet (after the "Start Icons job" confirm).
     *  Wires to [com.ai.viewmodel.ReportViewModel.runFanIconsBatch]
     *  plus a switch to ICONS mode. */
    onLaunchFanIcons: (FanOutRunKey) -> Unit = {},
    /** Switch the drill-in to ICONS mode without launching a batch
     *  — the L1 "Icons" mode-toggle uses this when a fan-icons run
     *  already exists. */
    onShowFanIcons: () -> Unit = {},
    /** Switch the drill-in back to MAIN mode — the L1 "Responses"
     *  mode-toggle. */
    onShowResponses: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val runs by engine.runs.collectAsState()
    val runState = runs[runKey]

    // Hydrate on first entry so a freshly-opened drill-in (after a
    // process restart, say) picks up the persisted rows even if the
    // report-screen orchestrator hasn't fired yet.
    LaunchedEffect(reportId, runKey) {
        withContext(Dispatchers.IO) { engine.hydrate(context, reportId) }
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
            FanOutNav.L1Icons -> FanOutNav.L1
            is FanOutNav.L2Icons -> FanOutNav.L2(n.answererKey, n.role)
        }
    }

    if (runState == null) {
        // Either still hydrating, or no persisted rows exist yet
        // (run launched ms ago). Show a minimal title bar so back
        // works, then a "loading" sentinel.
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            TitleBar(
                helpTopic = "secondary_fan_out_l1",
                title = if (mode == FanOutMode.ICONS) "Fan icons" else "Fan out",
                onBackClick = onBack
            )
            Text("Loading…", color = AppColors.TextTertiary)
        }
        return
    }

    // In ICONS mode, the L1 / L2 / L3 classifiers read the icon-
    // chain status off pair.iconStatus(...) — fed by these sets.
    val effectiveRunningSet = if (mode == FanOutMode.ICONS) runningIconsSet else runningSet
    val effectiveThrottledSet = if (mode == FanOutMode.ICONS) throttledIconsSet else throttledSet

    when (val n = nav) {
        is FanOutNav.L1 -> FanOutL1Screen(
            engine = engine,
            run = runState,
            runningSet = effectiveRunningSet,
            throttledSet = effectiveThrottledSet,
            actions = actions,
            mode = mode,
            onLaunchFanIcons = onLaunchFanIcons,
            onShowFanIcons = onShowFanIcons,
            onShowResponses = onShowResponses,
            // Fan icons starts at the Initiator view (active model is
            // the source) so the user sees their model's icon up top
            // and a list of the icons every other model produced in
            // reply. Fan out starts at Responder (the active model
            // is the answerer of every row).
            onOpenModel = { ak -> nav = FanOutNav.L2(ak, if (mode == FanOutMode.ICONS) "Initiator" else "Responder") },
            onOpenIcons = { nav = FanOutNav.L1Icons },
            onBack = onBack
        )
        is FanOutNav.L2 -> FanOutL2Screen(
            engine = engine,
            run = runState,
            runningSet = effectiveRunningSet,
            answererKey = n.answererKey,
            role = n.role,
            actions = actions,
            mode = mode,
            onSwitchRole = { newRole -> nav = FanOutNav.L2(n.answererKey, newRole) },
            onOpenPair = { sourceAgentId ->
                nav = FanOutNav.L3(n.answererKey, sourceAgentId, n.role)
            },
            onOpenOnePage = { nav = FanOutNav.L2OnePage(n.answererKey, n.role) },
            // Fan out L2's "Icons" button flips the top-level mode to
            // ICONS — the FanOutScreen's nav state (rememberSaveable
            // L2(answererKey, role)) is preserved across the mode
            // change since there's only one FanOutScreen composition,
            // so the user lands on Fan icons L2 for the same model
            // instead of an icons-grid sub-screen.
            onOpenIcons = onShowFanIcons,
            onBack = { nav = FanOutNav.L1 }
        )
        is FanOutNav.L3 -> FanOutL3Screen(
            engine = engine,
            run = runState,
            runningSet = effectiveRunningSet,
            answererKey = n.answererKey,
            sourceAgentId = n.sourceAgentId,
            role = n.role,
            actions = actions,
            mode = mode,
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
        FanOutNav.L1Icons -> FanOutL1IconsScreen(
            run = runState,
            onOpenPair = { ak, srcAgentId, r ->
                nav = FanOutNav.L3(ak, srcAgentId, r)
            },
            onBack = { nav = FanOutNav.L1 }
        )
        is FanOutNav.L2Icons -> FanOutL2IconsScreen(
            run = runState,
            answererKey = n.answererKey,
            role = n.role,
            onSwitchRole = { newRole -> nav = FanOutNav.L2Icons(n.answererKey, newRole) },
            onOpenPair = { srcAgentId ->
                nav = FanOutNav.L3(n.answererKey, srcAgentId, n.role)
            },
            onBack = { nav = FanOutNav.L2(n.answererKey, n.role) }
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
