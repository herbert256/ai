package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class SecondaryKind { RERANK, META, MODERATION, TRANSLATE }

/**
 * A meta-result that operates on a parent Report's per-agent outputs:
 * a Rerank ranks them, a chat-type Meta prompt narrates / compares /
 * summarises / etc. them. Each is the work of a single chosen model,
 * persisted independently so a report can accumulate many of either
 * kind over time. The user-given Meta prompt name carried on the row
 * (metaPromptName) is what the UI / exports bucket by — the kind is
 * just the routing label for which API path handles the call.
 */
data class SecondaryResult(
    val id: String,
    val reportId: String,
    val kind: SecondaryKind,
    val providerId: String,
    val model: String,
    val agentName: String,
    val timestamp: Long,
    val content: String?,
    val errorMessage: String? = null,
    val tokenUsage: TokenUsage? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    /** When kind == TRANSLATE: identifier of the item this translation
     *  operated on. "prompt" for the report prompt, agent.agentId for
     *  an agent response, secondary.id for a META row. Null on non-
     *  translate rows. */
    val translateSourceTargetId: String? = null,
    /** Companion to [translateSourceTargetId]. One of "PROMPT", "AGENT",
     *  "META". Null on non-translate rows. */
    val translateSourceKind: String? = null,
    /** Target language for a TRANSLATE row, English name (e.g. "Dutch",
     *  "German"). Used to group translated content into per-language
     *  views in the report viewer / Complete HTML / Zipped HTML. Null
     *  on non-translate rows. */
    val targetLanguage: String? = null,
    /** Companion to [targetLanguage] — native rendering for the picker
     *  ("Nederlands", "Deutsch"). Null on non-translate rows. */
    val targetLanguageNative: String? = null,
    /** UUID shared by every TRANSLATE row produced by a single Translate
     *  invocation, so the result page can render one aggregate "run"
     *  row per click and let the user drill into the individual API
     *  calls. Null on non-translate rows and on legacy rows saved
     *  before this field existed (those fall back to grouping-by-
     *  language for the same effect). */
    val translationRunId: String? = null,
    /** Legacy field — set by the old "translation creates a copy" flow
     *  on Summary / Compare secondaries that were duplicated onto a
     *  translated report. New translations don't fork the report so
     *  this stays null on every TRANSLATE row written by the current
     *  code path; preserved on disk so old reports still load. */
    val translatedFromSecondaryId: String? = null,
    /** Id of the [com.ai.model.MetaPrompt] entry that produced this
     *  row. Null on TRANSLATE rows and on legacy rows written before
     *  the Meta-prompt CRUD existed; UI falls back to the legacy
     *  `kind` label in that case. */
    val metaPromptId: String? = null,
    /** Display name of the Meta prompt that produced this row, copied
     *  from the [com.ai.model.MetaPrompt] at run time so the View
     *  card can group results by user-given name even after the user
     *  renames or deletes the Meta prompt. Null on TRANSLATE / legacy
     *  rows. */
    val metaPromptName: String? = null,
    /** For fan-out Meta runs: agentId of the report-model whose
     *  response was substituted into the prompt's `@RESPONSE@` slot.
     *  This row's own (providerId, model) is the answerer that
     *  produced this content. Together they form the (answerer,
     *  source) pair the fan out drill-in keys on. Null on every
     *  non-fan out row. */
    val fanOutSourceAgentId: String? = null,
    /** For fan_in-type Meta runs: id of the [com.ai.model.InternalPrompt]
     *  that produced this combined-report row. Lets the fan out detail
     *  screen distinguish the single combined output from the per-pair
     *  response rows even though both share `metaPromptName`. Null on
     *  every non-fan_in row. */
    val fanInOf: String? = null,
    /** Encoded [SecondaryScope] used when this row was originally
     *  produced — see [SecondaryScope.encode]. The cascade-on-prompt-
     *  change path reads this so a previously-narrowed run (Top-N /
     *  Manual selection) is re-run at the same scope instead of
     *  silently widening to AllReports. Null on legacy rows written
     *  before this field existed; cascade defaults to AllReports
     *  there, matching prior behaviour. */
    val secondaryScope: String? = null,
    /** Set when this row is a model-scoped fan-in (categories
     *  initiator / requester / model). Identifies which (provider,
     *  model) pair the L2 page should surface this row under so the
     *  per-model drill-in can filter to just its own. Null on every
     *  other row including the legacy "total" fan_in (which combines
     *  the whole report and is shown on L1's combinedRows section). */
    val scopeProviderId: String? = null,
    val scopeModel: String? = null,
    /** Per-fan-out-pair emoji produced by the
     *  [com.ai.viewmodel.ReportViewModel.runFanOutIconChain] 3-tier
     *  chain (chat continuation → one-shot fan_out_icon →
     *  fixed-agent fan_out_icon_3th). Null until the chain finishes
     *  (or skipped — feature gated by
     *  [com.ai.viewmodel.GeneralSettings.fanOutIconGenEnabled]).
     *  Empty / non-fan-out rows leave this null forever. */
    val icon: String? = null,
    /** Which tier of the fan-out icon chain produced [icon]. 1 =
     *  chat continuation, 2 = one-shot fan_out_icon, 3 = fixed-
     *  agent fan_out_icon_3th. Null when no tier succeeded and the
     *  icon is the 📝 fallback, or when [icon] is null. */
    val iconWinningTier: Int? = null,
    val iconErrorMessage: String? = null,
    val iconInputTokens: Int = 0,
    val iconOutputTokens: Int = 0,
    val iconInputCost: Double = 0.0,
    val iconOutputCost: Double = 0.0,
    /** Bundled prompt name that produced the currently-displayed
     *  [icon] on this fan-out pair row. "fan_out" / "fan_out_2" /
     *  "fan_out_3" per the winning tier; "fan_out_alt" after a
     *  Find-alt pick (future). Null on legacy rows — the future
     *  Icon lookup screen for fan-out pairs falls back to deriving
     *  the name from [iconWinningTier]. */
    val iconPromptUsed: String? = null,
    /** UUID shared by every secondary result produced by a single
     *  user-launched batch (fan-out, translation, ...). Lets the
     *  run's L1 screen deep-link the 🐞 trace icon to a trace list
     *  filtered to exactly this batch's API calls. Null on legacy
     *  rows written before this field existed. */
    val runId: String? = null,
    /** UUID of the most recent fan-icons sweep that touched this
     *  row. Stamped at icon commit time so the fan-icons L1 screen
     *  can deep-link 🐞 to that sweep's traces independently from
     *  the row's own [runId] (which records the fan-out that
     *  created the row). Null when no icon sweep has run, or on
     *  legacy rows. */
    val iconRunId: String? = null,
    /** Trace filename captured for the specific API call that
     *  produced this row, when available. Currently populated only
     *  for TRANSLATE rows (the View → Prompt screen wires a 🐞 from
     *  this so a selected translation jumps straight into its trace).
     *  Null on every other kind and on legacy translate rows written
     *  before this field existed. */
    val traceFile: String? = null
)

