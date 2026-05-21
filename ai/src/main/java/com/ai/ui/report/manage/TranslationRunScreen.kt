package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.data.AppService
import com.ai.data.SecondaryResult
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.TranslationItem
import com.ai.viewmodel.TranslationMode
import com.ai.viewmodel.TranslationRunState

/** "providerId|model" key for grouping a [TranslationItem]
 *  by the model that handled it. Null while the item is still
 *  unassigned (PENDING, not yet pulled by any worker). */
internal fun translationModelKey(item: TranslationItem): String? {
    val p = item.providerId
    val m = item.model
    return if (p != null && m != null) "$p|$m" else null
}

/** Navigation state inside the 3-level translation run drill-in.
 *  L1 = models, L2 = items for one model, L3 = a single translation.
 *  Mirrors [FanOutNav]. */
sealed class TranslationNav {
    object L1 : TranslationNav()
    data class L2(val modelKey: String) : TranslationNav()
    data class L3(val modelKey: String, val itemId: String) : TranslationNav()
}

/** Serialises to a 3-string list so rememberSaveable survives
 *  rotation + process death. */
private val translationNavSaver: Saver<TranslationNav, Any> = Saver(
    save = { nav ->
        when (nav) {
            is TranslationNav.L1 -> listOf("L1", "", "")
            is TranslationNav.L2 -> listOf("L2", nav.modelKey, "")
            is TranslationNav.L3 -> listOf("L3", nav.modelKey, nav.itemId)
        }
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val l = list as List<String>
        when (l[0]) {
            "L2" -> TranslationNav.L2(l[1])
            "L3" -> TranslationNav.L3(l[1], l[2])
            else -> TranslationNav.L1
        }
    }
)

/** Every callback the L1/L2/L3 translation screens need, packed into
 *  one parameter so each screen's signature stays small. Mirrors
 *  [FanOutActions] / TranslationLifecycleCallbacks. */
data class TranslationActions(
    /** Cancels + joins the runner, then deletes every persisted row.
     *  Returns the Job so L1 can await it behind a "Deleting…" popup. */
    val onDeleteRun: (reportId: String, runId: String) -> kotlinx.coroutines.Job? = { _, _ -> null },
    val onRestartFailed: (reportId: String, runId: String) -> Unit = { _, _ -> },
    val onRemoveFailed: (reportId: String, runId: String) -> Unit = { _, _ -> },
    /** Drop only the errored items whose model is currently benched. */
    val onRemoveBenched: (reportId: String, runId: String) -> Unit = { _, _ -> },
    val onRestartAll: (reportId: String, runId: String) -> Unit = { _, _ -> },
    val onStartMissing: (reportId: String, runId: String) -> Unit = { _, _ -> },
    /** Drop one pending/running item from a live run. */
    val onCancelItem: (runId: String, itemId: String) -> Unit = { _, _ -> },
    /** Delete one persisted TRANSLATE SecondaryResult row. */
    val onDeleteSecondaryRow: (reportId: String, rowId: String) -> Unit = { _, _ -> },
    val onNavigateToTraceFile: (String) -> Unit = {},
    val onNavigateToTraceList: () -> Unit = {},
    /** Open the trace list filtered to one translation run's runId.
     *  Wired on the L1 🐞 icon. */
    val onNavigateToTraceRunList: (String) -> Unit = {},
    val onNavigateToModelInfo: (AppService, String) -> Unit = { _, _ -> },
    val onNavigateHome: () -> Unit = {},
    /** Flip the cost-vs-speed mode on a (possibly in-flight) run.
     *  Workers re-read the mode on every queue pull, so the new bias
     *  kicks in within one chunk (~1s). Persisted per-runId. */
    val onSetMode: (runId: String, mode: TranslationMode) -> Unit = { _, _ -> }
)

/**
 * Parent of the 3-level translation run drill-in. Resolves the run to
 * one [TranslationRunState] — the live in-flight state
 * when available, otherwise reconstructed from the persisted TRANSLATE
 * rows on disk — holds the [TranslationNav] state, and routes to L1 /
 * L2 / L3. Replaces the old flat TranslationRunDetailScreen.
 */
@Composable
internal fun TranslationRunScreen(
    reportId: String,
    runId: String,
    /** Live in-flight state. Null once the run finishes — then the
     *  screen reconstructs the run from disk via [loadPersisted]. */
    liveRun: TranslationRunState?,
    /** Reconstructs the finished run from its persisted TRANSLATE
     *  rows. Wired to ReportViewModel.buildPersistedTranslationRunState. */
    loadPersisted: suspend () -> TranslationRunState?,
    actions: TranslationActions,
    onBack: () -> Unit,
    /** Re-targets the parent's `openTranslationRunId` after a title-bar
     *  swipe lands on a different report. Wired in `ReportScreen`. */
    onChangeRunId: (String) -> Unit = {}
) {
    var nav by rememberSaveable(runId, stateSaver = translationNavSaver) {
        mutableStateOf<TranslationNav>(TranslationNav.L1)
    }
    // Bumped by restart / remove-failed so a *finished* run reloads its
    // persisted state. A run that restart turns live again is picked up
    // automatically via the liveRun param.
    var refreshTick by remember { mutableIntStateOf(0) }
    BackHandler {
        nav = when (val n = nav) {
            TranslationNav.L1 -> { onBack(); return@BackHandler }
            is TranslationNav.L2 -> TranslationNav.L1
            is TranslationNav.L3 -> TranslationNav.L2(n.modelKey)
        }
    }

    val persisted by produceState<TranslationRunState?>(
        initialValue = null, reportId, runId, refreshTick, liveRun == null
    ) {
        value = if (liveRun != null) null else loadPersisted()
    }
    val run = liveRun ?: persisted

    // Per-screen title-bar swipe override. Filter = Translate so the
    // gesture skips reports without any TRANSLATE row. The on-match
    // callback flips the parent's openTranslationRunId to the new
    // report's first translation run *before* the report itself
    // switches, so the screen reloads with a valid runId.
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.ui.shared.LocalManageSwipeFilter provides ViewSwipeFilter.Translate,
        com.ai.ui.shared.LocalManageSwipeOnMatch provides { match: SwipeMatch ->
            match.translationRunId?.let(onChangeRunId)
        }
    ) {
    if (run == null) {
        Column(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background).padding(16.dp)
        ) {
            TitleBar(helpTopic = "translation_run_l1", title = "Translation", onBackClick = onBack)
            Text("Loading…", color = AppColors.TextTertiary)
        }
        return@CompositionLocalProvider
    }

    when (val n = nav) {
        TranslationNav.L1 -> TranslationL1Screen(
            run = run,
            reportId = reportId,
            runId = runId,
            actions = actions,
            onBumpRefresh = { refreshTick++ },
            onOpenModel = { modelKey -> nav = TranslationNav.L2(modelKey) },
            onBack = onBack
        )
        is TranslationNav.L2 -> TranslationL2Screen(
            run = run,
            modelKey = n.modelKey,
            actions = actions,
            onOpenItem = { itemId -> nav = TranslationNav.L3(n.modelKey, itemId) },
            onBack = { nav = TranslationNav.L1 }
        )
        is TranslationNav.L3 -> TranslationL3Screen(
            run = run,
            reportId = reportId,
            runId = runId,
            modelKey = n.modelKey,
            itemId = n.itemId,
            actions = actions,
            onStepItem = { itemId -> nav = TranslationNav.L3(n.modelKey, itemId) },
            onBack = { nav = TranslationNav.L2(n.modelKey) }
        )
    }
    } // close CompositionLocalProvider
}
