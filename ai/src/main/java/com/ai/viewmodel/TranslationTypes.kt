package com.ai.viewmodel

/** Translation-run value types, extracted from ReportViewModel so the
 *  translation UI and the view model share them without the types
 *  being nested in the 8k-line view model. Pure data. */

enum class TranslationStatus { PENDING, RUNNING, DONE, ERROR }
enum class TranslationKind { TITLE, PROMPT, AGENT_RESPONSE, META }

/** Per-run optimisation knob exposed on the L1 screen — switches
 *  the cost-aware hesitation built into [startTranslation]'s
 *  worker loop. Mutable mid-run: each worker re-reads the current
 *  value before pulling the next item, so flipping the toggle
 *  takes effect on the next pull (in-flight calls keep running).
 *  Persisted per-runId via [com.ai.data.TranslationModeStore] so
 *  the choice survives app restarts.
 *  - [COST] (default): full bias — penalty = `(myAvg / cheapest − 1) × 100ms`,
 *    capped at 120 s. Expensive models pull only what cheap ones
 *    can't keep up with.
 *  - [MIXED]: softened bias — multiplier 20, cap 5 s. Still
 *    favours cheap models but expensive ones stay engaged.
 *  - [SPEED]: no hesitation — every model pulls as fast as its
 *    per-host caps allow. Highest throughput, highest spend. */
enum class TranslationMode { COST, MIXED, SPEED }

/** One row on the translation progress screen. [sourceText] is what
 *  gets fed to the model; [translatedText] is filled in on DONE.
 *  [costDollars] is the per-call cost in USD (input + output) for
 *  the running tally — the live result-list row passes it through
 *  formatCents() which multiplies by 100, same convention every
 *  other cost cell in the app uses. [target] is the
 *  SecondaryResult.id when the source is a chat-type META row —
 *  used so the per-language overlay can re-attach the translated
 *  content to the right meta result. */
data class TranslationItem(
    val id: String,
    val label: String,
    val kind: TranslationKind,
    val sourceText: String,
    val target: String? = null,
    val status: TranslationStatus = TranslationStatus.PENDING,
    val translatedText: String? = null,
    val errorMessage: String? = null,
    val costDollars: Double = 0.0,
    /** Token usage from the translation API call. Stored so the new
     *  Report can attribute per-row cost to the translation
     *  operation rather than carrying the source-run figures
     *  through unchanged. Null until the call returns. */
    val tokenUsage: com.ai.data.TokenUsage? = null,
    /** Wall-clock duration of the translation API call. Surfaced
     *  in the Costs table so translate rows aren't blank in the
     *  Seconds column. Null until the call returns. */
    val durationMs: Long? = null,
    /** The model that is handling / handled this item. Null while
     *  the item is unassigned (PENDING, not yet pulled by any
     *  worker — the shared work queue doesn't pre-assign). Stamped
     *  when a worker takes it (RUNNING) and on DONE / terminal
     *  ERROR; cleared again when an item is requeued after a
     *  failed attempt. The 3-level run screen groups by this. */
    val providerId: String? = null,
    val model: String? = null,
    /** SecondaryResult.id — set only when this item was
     *  reconstructed from disk for a finished run, so the leaf
     *  screen can delete / trace that exact persisted row. */
    val persistedRowId: String? = null,
    /** Filename of the API trace produced by this item's call,
     *  captured via [withTraceFilenameSink] so the View → Prompt
     *  (and equivalent per-item) screens can deep-link a 🐞
     *  straight to the translation's trace. Null until the call
     *  returns; stays null when tracing is disabled. */
    val traceFile: String? = null
)

data class TranslationRunState(
    val runId: String,
    val sourceReportId: String,
    val targetLanguageName: String,
    val targetLanguageNative: String,
    val items: List<TranslationItem>,
    val totalCostDollars: Double = 0.0,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /** Cost-vs-speed knob the L1 screen exposes. Mutated via
     *  [setTranslationMode]; workers re-read on every queue pull. */
    val mode: TranslationMode = TranslationMode.COST,
    /** The run's intended (provider, model) set — strings in the
     *  same `"$providerId|$model"` shape as `translationModelKey`.
     *  Surfaced on the L1 screen so every model the user picked
     *  appears immediately, even before its worker has pulled
     *  its first item; otherwise the cost-aware hesitation made
     *  expensive models invisible for the first few seconds.
     *  Populated at every TranslationRunState construction site. */
    val models: List<String> = emptyList()
) {
    val total: Int get() = items.size
    val completed: Int get() = items.count { it.status == TranslationStatus.DONE || it.status == TranslationStatus.ERROR }
    val isRunning: Boolean get() = !finished && !cancelled && completed < total
    val isFinished: Boolean get() = finished
}

/** One item the View screen's "Language missing" popup asked us
 *  to translate from a chosen source language to the View's
 *  active target language. The [sourceText] is whatever the
 *  source language renders for that item — Original's
 *  `report.prompt` / `agent.responseBody` / `meta.content`, or a
 *  non-Original existing TRANSLATE row's content. */
data class TranslateMissingItem(
    val sourceKind: String,    // "PROMPT" | "AGENT" | "META"
    val targetId: String,      // "prompt" / agentId / meta SecondaryResult id
    val sourceText: String,
    val label: String          // surfaced on the placeholder row's agentName
)
