package com.ai.data

/**
 * State machine driving the per-report "Regenerate everything"
 * batch job that replaces the legacy one-shot regenerateReport
 * call. See plan file:
 *   /Users/herbert/.claude/plans/meta-items-and-fa-in-flickering-piglet.md
 *
 * The batch walks the report's contents in fixed phase order
 * ([RegeneratePhase.values]) and restarts every row in the
 * current phase before moving to the next. It halts on the first
 * row that ends ERROR and stays paused until either the user
 * fixes the row + clicks Restart on the detail screen, or the
 * 30 s background sweep (see ReportViewModel.startBackgroundResumeSweep)
 * sees the error cleared and auto-resumes.
 *
 * Persisted as one JSON file per report under
 * <filesDir>/regenerate/<reportId>.json via [RegenerateBatchStorage].
 */
enum class RegeneratePhase {
    /** Re-runs the report title workers before icon generation so
     *  report/icon can derive from the fresh long title. Single
     *  synthetic task — rowId == [REPORT_TITLE_ROW_ID]. */
    TITLE,

    /** Re-runs the report's main 🎯 icon-gen call
     *  ([com.ai.viewmodel.ReportViewModel.kickOffIconGeneration]).
     *  Single synthetic task — rowId == [REPORT_ICON_ROW_ID]. */
    ICON,

    /** Re-runs the report's language-detection + language-icon
     *  flow ([com.ai.viewmodel.ReportViewModel.kickOffLanguageGeneration]).
     *  Single synthetic task — rowId == [REPORT_LANGUAGE_ROW_ID]. */
    LANGUAGE,

    /** report.agents — one task per ReportAgent. */
    AGENTS,

    /** Single-call meta rows (kind == META, fanOutSourceAgentId
     *  == null, fanInOf == null) + RERANK + MODERATION. The user
     *  groups all of these under "meta" for this batch. */
    META,

    /** Fan-out per-pair rows (kind == META,
     *  fanOutSourceAgentId != null). Re-dispatched per distinct
     *  metaPromptId via resumeStaleFanOutPairs. */
    FAN_OUT,

    /** Fan-in combined-report rows (kind == META, fanInOf != null).
     *  Each is a single call. */
    FAN_IN,

    /** Translation rows (kind == TRANSLATE). Re-dispatched per
     *  distinct translationRunId via startMissingTranslations. */
    TRANSLATIONS,

    /** Per-fan-out-pair Fan Meta re-runs. One task per fan-out pair
     *  row that previously carried a title and/or icon (or an error).
     *  One worker call regenerates both the title and the icon. */
    FAN_META
}

enum class RegenerateTaskState {
    /** Task hasn't been touched yet — its phase still pending. */
    WAITING,
    /** Phase started, dispatcher fired, awaiting the underlying
     *  row's success / failure. */
    RUNNING,
    /** The underlying row now has content (or an icon for the
     *  Fan Meta phase) and no errorMessage. */
    SUCCESS,
    /** The underlying row carries an errorMessage / iconErrorMessage
     *  after the dispatch fired. */
    ERROR,
    /** Phase didn't get to fire because the user clicked Cancel. */
    CANCELLED
}

enum class RegenerateJobStatus {
    /** Orchestrator coroutine is alive and stepping through phases. */
    RUNNING,
    /** At least one task ended ERROR; no further phases will fire
     *  until restart() succeeds. */
    PAUSED_ON_ERROR,
    /** Every task ended SUCCESS / CANCELLED. */
    DONE,
    /** User clicked Cancel. Restart re-enters from currentPhase. */
    CANCELLED
}

/** Synthetic rowId for the report-level icon task — there's no
 *  persistent row to match against, so the engine reads
 *  [com.ai.data.Report.icon] / [com.ai.data.Report.iconErrorMessage]
 *  off disk directly. */
const val REPORT_ICON_ROW_ID = "__report_icon__"

/** Synthetic rowId for the report-level title task. The engine reads
 *  [com.ai.data.Report.titlePromptUsed] /
 *  [com.ai.data.Report.titleErrorMessage] off disk directly. */
const val REPORT_TITLE_ROW_ID = "__report_title__"

/** Synthetic rowId for the report-level language task. The engine
 *  reads [com.ai.data.Report.languageName] /
 *  [com.ai.data.Report.languageIcon] /
 *  [com.ai.data.Report.languageIconErrorMessage] off disk directly. */
const val REPORT_LANGUAGE_ROW_ID = "__report_language__"

data class RegenerateTask(
    /** Stable id of the underlying row — agent.agentId for AGENTS,
     *  SecondaryResult.id for every other phase. The orchestrator
     *  polls this row off disk to detect success / failure. For
     *  FAN_META the rowId is still the SecondaryResult.id of the
     *  fan-out pair row whose title+icon is being regenerated. */
    val rowId: String,
    val phase: RegeneratePhase,
    /** Human-readable label rendered in the detail screen
     *  (e.g. "claude-3.5-sonnet", "Compare", "Dutch translation"). */
    val label: String,
    val state: RegenerateTaskState,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val errorMessage: String? = null
)

data class RegenerateJob(
    val reportId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: RegenerateJobStatus,
    /** Null when DONE / CANCELLED; otherwise the phase whose
     *  tasks are currently RUNNING or about to be dispatched. */
    val currentPhase: RegeneratePhase?,
    val tasks: List<RegenerateTask>,
    /** First rowId that flipped to ERROR in the current phase.
     *  Restart watches this row off disk and only resumes when
     *  the row's errorMessage cleared. Null when not paused. */
    val pausedOnRowId: String? = null
)
