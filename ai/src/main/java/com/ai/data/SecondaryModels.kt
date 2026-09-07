package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class SecondaryKind { RERANK, META, MODERATION, TRANSLATE, TOURNAMENT, JUDGES, COMPARE, TRANSRANK }

/** Per-kind "Type" for a TRANSLATE call — drives the trace category, the
 *  report cost-table Type column, and the AI Usage `kind`, so each kind of
 *  translation breaks out separately instead of one flat `internal/Translate`.
 *
 *  [srcKind] is the TRANSLATE row's `translateSourceKind`. The `META` source
 *  splits three ways by the *source* row's nature ([sourceIsFanOut] /
 *  [sourceIsFanIn]) — those flags live on the row being translated (looked up
 *  by `translateSourceTargetId`), not on the TRANSLATE row itself. RERANK /
 *  MODERATION are never translated, so they have no type here. */
fun translateTraceType(
    srcKind: String?,
    sourceIsFanOut: Boolean = false,
    sourceIsFanIn: Boolean = false
): String = when (srcKind) {
    "PROMPT" -> "translate/report_prompt"
    "TITLE" -> "translate/report_title"
    "TITLE_LONG" -> "translate/report_title_long"
    "AGENT" -> "translate/model_response"
    "AGENT_TITLE" -> "translate/model_title"
    "FANOUT_TITLE" -> "translate/fan/out/title"
    "META" -> when {
        sourceIsFanOut -> "translate/fan/out/response"
        sourceIsFanIn -> "translate/fan/in"
        else -> "translate/meta"
    }
    else -> "translate/translate"
}

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
    /** Final HTTP status code for the API call that produced this row.
     *  Null for legacy rows and non-network failures. */
    val httpStatusCode: Int? = null,
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
    /** Nonblank UUID shared by every TRANSLATE row produced by a single Translate
     *  invocation, so the result page can render one aggregate "run"
     *  row per click and let the user drill into the individual API
     *  calls. Null on non-translate rows and on legacy rows saved
     *  before this field existed (those fall back to grouping-by-
     *  language for the same effect). */
    val translationRunId: String? = null,
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
    /** RERANK / MODERATION only: the success-ordered agentIds whose
     *  1-based positions the row's stored content references as [N],
     *  frozen at run time. The detail screens resolve [N] through this
     *  snapshot — resolving through the report's CURRENT success set
     *  mislabeled every row after an agent removal or a failed→success
     *  regenerate shifted the numbering. Null on rows from other kinds
     *  and on legacy rows (viewers fall back to the current-set map). */
    val sourceAgentIds: List<String>? = null,
    val sourceSnapshotId: String? = null,
    val translationSourceText: String? = null,
    val executionConfig: ReportExecutionConfig? = null,
    /** Secondary-call parameter/system-prompt selections captured when
     *  this row was launched. Fan-out pair variation replays use them
     *  to preserve the original call shape; legacy rows fall back to
     *  secondary defaults when these are null. */
    val secondaryParameterPresetIds: List<String>? = null,
    val secondarySystemPromptId: String? = null,
    /** User-selected replacement marker for [content]. Null for the
     *  original fan-out response or after a normal pair rerun. */
    val responseChangeSource: String? = null,
    val responseChangeValue: String? = null,
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
    /** UUID of the most recent Fan Meta sweep that touched this
     *  row. Stamped at icon commit time so the Fan Meta L1 screen
     *  can deep-link 🐞 to that sweep's traces independently from
     *  the row's own [runId] (which records the fan-out that
     *  created the row). Null when no icon sweep has run, or on
     *  legacy rows. */
    val iconRunId: String? = null,
    /** Per-fan-out-pair title produced by the Fan Meta batch
     *  ([com.ai.viewmodel.IconGenerationManager.runFanMetaBatch]) —
     *  a chat-continuation call to the pair's own model titling its
     *  own response. Parallel to [icon] but single-tier (no fallback
     *  tiers, no fallback glyph). Null until the batch runs; empty /
     *  non-fan-out rows leave this null forever. */
    val title: String? = null,
    val titleErrorMessage: String? = null,
    val titleInputTokens: Int = 0,
    val titleOutputTokens: Int = 0,
    val titleInputCost: Double = 0.0,
    val titleOutputCost: Double = 0.0,
    /** "provider/model" of the worker that produced the Fan Meta title+icon
     *  for this pair, so the Costs table's `fan/meta` row shows the
     *  model that billed. Null until the Fan Meta batch records cost. */
    val titleModel: String? = null,
    /** Durable winning, rejected and failed metadata attempts; unrelated to traceFile. */
    val fanMetaAttempts: List<FanMetaAttempt> = emptyList(),
    /** Wall-clock duration (ms) of the fan-out pair title call, for the
     *  Report-info screen's total-API-time tally. Null before it ran. */
    val titleDurationMs: Long? = null,
    /** Bundled prompt name that produced the currently-displayed
     *  [title]. "fan_out_title" on success. Null on legacy rows. */
    val titlePromptUsed: String? = null,
    /** UUID of the most recent Fan Meta sweep that touched this row
     *  (parallel to [iconRunId]). Null when no title sweep has run. */
    val titleRunId: String? = null,
    /** Trace filename captured for the specific API call that
     *  produced this row, when available. Currently populated only
     *  for TRANSLATE rows (the View → Prompt screen wires a 🐞 from
     *  this so a selected translation jumps straight into its trace).
     *  Null on every other kind and on legacy translate rows written
     *  before this field existed. */
    val traceFile: String? = null,
    /** In-report "refine this answer" chat for a fan-out pair (🗣️ on the
     *  Fan-out-response screen). Seeded on first open from the resolved
     *  fan-out prompt + [content]; each reply is appended. Applying a reply
     *  overwrites [content]. Immutable list (always replaced wholesale).
     *  Empty on legacy rows / pairs never refined. */
    val chatMessages: List<ChatMessage> = emptyList(),
    // -------- TOURNAMENT (kind == TOURNAMENT) --------
    /** Distinguishes the two row shapes a tournament produces within
     *  one kind, the way [fanOutSourceAgentId] / [fanInOf] split fan-out
     *  pairs from fan-in rows. "MATCH" = one ordered head-to-head
     *  judgment; "AGGREGATE" = the per-judge-model rolled-up ranking row.
     *  Null on every non-tournament row. */
    val tournamentRole: String? = null,
    /** Shared by every row (all matches + the aggregate) of one judge
     *  model's tournament on this report: "${reportId}|${providerId}|${model}".
     *  This is the run key — hydration groups rows into a run by it.
     *  Null on non-tournament rows. */
    val tournamentJudgeRunId: String? = null,
    /** MATCH rows: agentId of the response placed in the @RESPONSE_A@ slot. */
    val matchResponseAId: String? = null,
    /** MATCH rows: agentId of the response placed in the @RESPONSE_B@ slot. */
    val matchResponseBId: String? = null,
    /** MATCH rows: 0 for the canonical (lowId-vs-highId) orientation, 1
     *  for the swapped re-judgment. Together (A,B,orientation) uniquely
     *  key the match within a run. Null on non-MATCH rows. */
    val matchOrientation: Int? = null,
    /** AGGREGATE rows only: the win matrix + which method produced the
     *  current [content] array, encoded JSON
     *  (`{"ids":[…],"wins":[[…]],"method":"COPELAND"}`). Lets the result
     *  screen recompute every ranking method locally and re-persist
     *  [content] on a method toggle without re-reading every match row.
     *  Null until the aggregate is first computed. */
    val tournamentMatrix: String? = null,
    // -------- COMPARE (kind == COMPARE) --------
    /** Shared by every CELL row of one "Compare with meta" run on this
     *  report — hydration groups rows into a run by it. Null on
     *  non-compare rows. */
    val compareRunId: String? = null,
    /** agentId of the report answer scored in this cell (its similarity
     *  to [compareToResultId]'s meta content). The cell's own
     *  (providerId, model) is the worker that judged it. Null on
     *  non-compare rows. */
    val compareAgentId: String? = null,
    /** SecondaryResult.id of the meta result this cell's answer was
     *  scored against. Null on non-compare rows. */
    val compareToResultId: String? = null
)

/** Total USD spend captured on this row: the primary in/out cost PLUS
 *  the Fan-Meta icon + title spend that fan-out pair rows accrue. The
 *  delete / re-run paths roll this into [com.ai.data.Report.costsFromDeletedItems]
 *  so lifetime cost stays whole — summing only inputCost + outputCost
 *  (as they used to) silently dropped every pair's icon / title spend. */
fun SecondaryResult.fullCost(): Double =
    (inputCost ?: 0.0) + (outputCost ?: 0.0) +
        iconInputCost + iconOutputCost +
        titleInputCost + titleOutputCost

/** Drop the "## References" legend that `SecondaryRunManager` appends to a
 *  reference-enabled META result's [SecondaryResult.content]
 *  (`…\n\n---\n\n## References\n\n[N] = Provider / Model…`). Used only when
 *  seeding a chat from a meta result — the agent (model-response) chat never
 *  carries that footer, and it's noise in a follow-up conversation. The
 *  stored / displayed content keeps the legend; only the chat seed is
 *  trimmed. Returns the input unchanged when no legend is present. */
fun stripMetaReferenceLegend(content: String): String {
    val idx = content.lastIndexOf("\n\n---\n\n## References")
    return if (idx >= 0) content.substring(0, idx).trimEnd() else content
}
