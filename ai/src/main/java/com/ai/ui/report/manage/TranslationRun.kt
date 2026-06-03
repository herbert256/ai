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
import com.ai.viewmodel.TranslationRunState

/** "providerId|model" key for grouping a [TranslationItem]
 *  by the model that handled it. Null while the item is still
 *  unassigned (PENDING, not yet pulled by any worker). */
internal fun translationModelKey(item: TranslationItem): String? {
    val p = item.providerId
    val m = item.model
    return if (p != null && m != null) "$p|$m" else null
}

/** The dimension the L1 list groups by. Models = per-model rows (the
 *  historical default); Types = per-trace/cost-type rows. */
enum class TranslationGroupMode { MODELS, TYPES }

/** Group key for an item under the active [TranslationGroupMode].
 *  MODELS → the model key (null for an unassigned PENDING item, which
 *  then shows only in the Queue stat). TYPES → the item's traceType,
 *  always present (stamped at item creation), so every status groups. */
internal fun translationGroupKey(item: TranslationItem, mode: TranslationGroupMode): String? =
    when (mode) {
        TranslationGroupMode.MODELS -> translationModelKey(item)
        TranslationGroupMode.TYPES -> item.traceType
    }

/** Display label for a trace/cost type — the raw `translate/…` value
 *  with its `translate/` prefix stripped (e.g. "fan/out/response"). */
internal fun translationTypeLabel(traceType: String): String =
    traceType.removePrefix("translate/")

/** Navigation state inside the 3-level translation run drill-in.
 *  L1 = groups (models or types), L2 = items for one group, L3 = a
 *  single translation. The active [TranslationGroupMode] travels with
 *  L2/L3 so they filter + step within the right dimension. Mirrors
 *  [FanOutNav]. */
sealed class TranslationNav {
    object L1 : TranslationNav()
    data class L2(val mode: TranslationGroupMode, val groupKey: String) : TranslationNav()
    data class L3(val mode: TranslationGroupMode, val groupKey: String, val itemId: String) : TranslationNav()
}

/** Serialises to a 4-string list so rememberSaveable survives
 *  rotation + process death. Slot 1 carries the group mode for L2/L3. */
private val translationNavSaver: Saver<TranslationNav, Any> = Saver(
    save = { nav ->
        when (nav) {
            is TranslationNav.L1 -> listOf("L1", "", "", "")
            is TranslationNav.L2 -> listOf("L2", nav.mode.name, nav.groupKey, "")
            is TranslationNav.L3 -> listOf("L3", nav.mode.name, nav.groupKey, nav.itemId)
        }
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        val l = list as List<String>
        val mode = runCatching { TranslationGroupMode.valueOf(l[1]) }
            .getOrDefault(TranslationGroupMode.MODELS)
        when (l[0]) {
            "L2" -> TranslationNav.L2(mode, l[2])
            "L3" -> TranslationNav.L3(mode, l[2], l[3])
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
    /** Open the "Find alternative translation" flow for one L3 item.
     *  Carries the full target identity so the parent can host the
     *  model picker + candidate screen and apply the pick back to the
     *  right TRANSLATE row. */
    val onFindAlternativeTranslation: (
        itemId: String,
        isTitleKind: Boolean,
        sourceText: String,
        traceType: String,
        targetLanguageName: String,
        persistedRowId: String?
    ) -> Unit = { _, _, _, _, _, _ -> }
)

/** Identity of the translation item a "Find alternative translation" flow
 *  is operating on. Hoisted to the report-manage screen so the shared
 *  [com.ai.ui.report.manage.ModelSelectionScreen] picker + the candidate
 *  screen can render over the (yielded) translation run screen. */
data class AltTranslateTarget(
    val reportId: String,
    val runId: String,
    val itemId: String,
    val isTitleKind: Boolean,
    val sourceText: String,
    val traceType: String,
    val targetLanguageName: String,
    val persistedRowId: String?
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
    onChangeRunId: (String) -> Unit = {},
    /** Bumped by the parent after a "Find alternative translation" pick
     *  overwrites a row on disk — forces the persisted-run reload so a
     *  finished run reflects the new translation. */
    externalRefresh: Int = 0,
    /** Item ids currently parked on a provider rate / concurrency gate;
     *  feeds the L1 "Throttled" stat. */
    throttledItems: Set<String> = emptySet()
) {
    var nav by rememberSaveable(runId, stateSaver = translationNavSaver) {
        mutableStateOf<TranslationNav>(TranslationNav.L1)
    }
    // L1 grouping preset (Models / Types). Survives drill-in round trips
    // and rotation; resets per run.
    var groupMode by rememberSaveable(runId) { mutableStateOf(TranslationGroupMode.MODELS) }
    // Bumped by restart / remove-failed so a *finished* run reloads its
    // persisted state. A run that restart turns live again is picked up
    // automatically via the liveRun param.
    var refreshTick by remember { mutableIntStateOf(0) }
    BackHandler {
        nav = when (val n = nav) {
            TranslationNav.L1 -> { onBack(); return@BackHandler }
            is TranslationNav.L2 -> TranslationNav.L1
            is TranslationNav.L3 -> TranslationNav.L2(n.mode, n.groupKey)
        }
    }

    val persisted by produceState<TranslationRunState?>(
        initialValue = null, reportId, runId, refreshTick, externalRefresh, liveRun == null
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
            TitleBar(helpTopic = "translation_run_l1", title = "Translation", subject = "Per-model progress of this translation", onBackClick = onBack)
            Text("Loading…", color = AppColors.TextTertiary)
        }
        return@CompositionLocalProvider
    }

    when (val n = nav) {
        TranslationNav.L1 -> TranslationL1Screen(
            run = run,
            reportId = reportId,
            runId = runId,
            throttledSet = throttledItems,
            actions = actions,
            groupMode = groupMode,
            onSetGroupMode = { groupMode = it },
            onBumpRefresh = { refreshTick++ },
            onOpenGroup = { groupKey -> nav = TranslationNav.L2(groupMode, groupKey) },
            onBack = onBack
        )
        is TranslationNav.L2 -> TranslationL2Screen(
            run = run,
            mode = n.mode,
            groupKey = n.groupKey,
            actions = actions,
            onOpenItem = { itemId -> nav = TranslationNav.L3(n.mode, n.groupKey, itemId) },
            onBack = { nav = TranslationNav.L1 }
        )
        is TranslationNav.L3 -> TranslationL3Screen(
            run = run,
            reportId = reportId,
            runId = runId,
            mode = n.mode,
            groupKey = n.groupKey,
            itemId = n.itemId,
            actions = actions,
            onStepItem = { itemId -> nav = TranslationNav.L3(n.mode, n.groupKey, itemId) },
            onBack = { nav = TranslationNav.L2(n.mode, n.groupKey) }
        )
    }
    } // close CompositionLocalProvider
}
