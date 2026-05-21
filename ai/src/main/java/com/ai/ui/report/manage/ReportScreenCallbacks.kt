package com.ai.ui.report.manage
import com.ai.ui.report.view.*
import com.ai.ui.helpers.*

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.*
import com.ai.model.*
import androidx.compose.runtime.CompositionLocalProvider
import com.ai.ui.shared.AnimatedHourglass
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToCurrentReport
import com.ai.ui.shared.TitleBar
import com.ai.ui.shared.formatCents
import com.ai.viewmodel.AppViewModel
import com.ai.viewmodel.IconCandidate
import com.ai.viewmodel.ReportViewModel
import com.ai.viewmodel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Three translation-run lifecycle callbacks (cancel-run /
 *  cancel-item / consume-run) bundled so [ReportsScreen]'s parameter
 *  list stays under the JVM 64 KB per-method bytecode limit. Wired
 *  by [ReportsScreenNav] to `ReportViewModel.cancelTranslation /
 *  cancelTranslationItem / consumeTranslationRun`. */
data class TranslationLifecycleCallbacks(
    val onCancelRun: (String) -> Unit = { _ -> },
    val onCancelItem: (String, String) -> Unit = { _, _ -> },
    val onConsumeRun: (String) -> Unit = { _ -> },
    /** Delete a whole translation run — cancels + joins the runner,
     *  then deletes every persisted row. Returns the Job so the
     *  detail screen can await it behind a "Deleting…" popup. */
    val onDeleteRun: (sourceReportId: String, runId: String) -> kotlinx.coroutines.Job? = { _, _ -> null },
    /** Flip the cost-vs-speed mode on a (possibly in-flight) run.
     *  Wired by ReportsScreenNav to ReportViewModel.setTranslationMode. */
    val onSetMode: (runId: String, mode: com.ai.viewmodel.ReportViewModel.TranslationMode) -> Unit = { _, _ -> },
    /** Rebuild a stalled run's in-memory state from disk — wired to
     *  ReportViewModel.reconcileStalledTranslationRun. Fires from the
     *  result page's 10-second poll when an hourglass row's
     *  completed-equals-total stall is detected. */
    val onReconcileStalled: (sourceReportId: String, runId: String) -> Unit = { _, _ -> }
)

/** Four translation-icon callbacks plumbed through [ReportsScreen]
 *  as a single parameter so the Composable's parameter list stays
 *  under the JVM 64 KB per-method bytecode limit. Wired by
 *  [ReportsScreenNav] to the matching `ReportViewModel.*TranslationIcon*`
 *  methods. */
data class TranslationIconCallbacks(
    val onKickoff: (String) -> Unit = { _ -> },
    val onStartFanOut: (String, List<ReportModel>) -> Unit = { _, _ -> },
    val onPick: (String, IconCandidate.Done) -> Unit = { _, _ -> },
    val onRestartFanOut: (String) -> Unit = { _ -> }
)

/** Four per-`InternalPrompt` icon callbacks plumbed through
 *  [ReportsScreen] as a single parameter — same rationale as
 *  [TranslationIconCallbacks]. Wired by [ReportsScreenNav] to
 *  `ReportViewModel.kickOffInternalPromptIcon /
 *  startInternalPromptIconFanOut / pickInternalPromptIcon /
 *  restartInternalPromptIconFanOut`. */
data class InternalPromptIconCallbacks(
    val onKickoff: (com.ai.model.InternalPrompt) -> Unit = { _ -> },
    val onStartFanOut: (com.ai.model.InternalPrompt, List<ReportModel>) -> Unit = { _, _ -> },
    val onPick: (com.ai.model.InternalPrompt, IconCandidate.Done) -> Unit = { _, _ -> },
    val onRestartFanOut: (com.ai.model.InternalPrompt) -> Unit = { _ -> },
    /** Per-row override variant of [onPick]. Wired to
     *  [com.ai.viewmodel.ReportViewModel.pickMetaRowIcon] so the picked
     *  emoji lands on a single SecondaryResult row instead of the
     *  shared per-(name,title) [com.ai.data.InternalPromptIconCache]
     *  entry. Used when the icon detail / alt-pick flow was opened
     *  from a per-row Meta tile — two tiles that share a
     *  `metaPromptName` then carry distinct icons. */
    val onPickRow: (reportId: String, rowId: String, emoji: String) -> Unit = { _, _, _ -> }
)

/** Bundle of fan-out + fan-icons runtime state for [ReportsScreen].
 *  Packs the throttled + running pair sets + the fan-icons batch
 *  launch callback so [ReportsScreen]'s parameter count stays
 *  under the JVM 64 KB per-method bytecode limit. */
data class FanRuntimeBundle(
    val throttledFanOutPairs: Set<String> = emptySet(),
    val runningFanIconsPairs: Set<String> = emptySet(),
    val throttledFanIconsPairs: Set<String> = emptySet(),
    val onLaunchFanIconsBatch: (reportId: String, metaPromptId: String) -> Unit = { _, _ -> },
    /** Drop the iconError sentinel + emoji state on every errored
     *  fan-out pair so they read as "no icon yet" rather than ❌.
     *  Wired to the L1 ICONS "Remove errors" button. */
    val onClearFanIconErrors: (reportId: String, metaPromptId: String) -> Unit = { _, _ -> },
    /** Clear errors via [onClearFanIconErrors] and re-fire the
     *  fan-icons batch on the just-cleared pairs. Wired to the L1
     *  ICONS "Restart errors" button. */
    val onRestartFanIconErrors: (reportId: String, metaPromptId: String) -> Unit = { _, _ -> }
)

// ===== Main Reports Screen =====

/** Bundle of the three "Continue in chat" navigation callbacks
 *  ReportsScreen already exposes individually. Bundled into one
 *  slot so the icon detail screens can plumb them through their
 *  helper chain without inflating per-method bytecode. */
data class ContinueChatCallbacks(
    val onCurrent: (reportId: String, agentId: String) -> Unit = { _, _ -> },
    val onAgentPicker: (reportId: String, agentId: String) -> Unit = { _, _ -> },
    val onOnTheFly: (reportId: String, agentId: String) -> Unit = { _, _ -> },
)

/** Bundle of the four language-icon fan-out parameters — passed as
 *  one slot through to the icon routers so the per-method bytecode
 *  of ReportsScreen stays under the JVM 64 KB ceiling. */
data class LanguageIconCallbacks(
    val fanOutByReport: Map<String, List<IconCandidate>> = emptyMap(),
    val onStartFanOut: (reportId: String, promptText: String, models: List<ReportModel>) -> Unit = { _, _, _ -> },
    val onPickAlternative: (reportId: String, emoji: String, iconModel: String) -> Unit = { _, _, _ -> },
    val onRestartFanOut: (reportId: String) -> Unit = { _ -> },
)

