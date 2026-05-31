package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock

data class ReportAgent(
    val agentId: String,
    val agentName: String,
    val provider: String,
    val model: String,
    var reportStatus: ReportStatus = ReportStatus.PENDING,
    var httpStatus: Int? = null,
    var requestHeaders: String? = null,
    var requestBody: String? = null,
    var responseHeaders: String? = null,
    var responseBody: String? = null,
    var errorMessage: String? = null,
    var tokenUsage: TokenUsage? = null,
    var cost: Double? = null,
    /** Persisted USD cost split, captured at run completion using
     *  the [com.ai.data.PricingCache] prices in effect at that
     *  moment. Pinned at run time — the View / Manage cost cards
     *  read these straight from disk so a later catalog re-price
     *  doesn't shift the historical agent cost. Null on legacy
     *  rows written before the freeze landed; the renderer falls
     *  back to live PricingCache recomputation for those. */
    var inputCost: Double? = null,
    var outputCost: Double? = null,
    var citations: List<String>? = null,
    var searchResults: List<SearchResult>? = null,
    var relatedQuestions: List<String>? = null,
    var rawUsageJson: String? = null,
    var durationMs: Long? = null,
    /** Trace filename of THIS agent's primary response call. Stored at
     *  completion so 🐞 affordances point at the exact call instead of
     *  guessing via ApiTracer. Null when tracing was off / legacy rows. */
    var traceFile: String? = null,
    /** Trace filename of the WINNING per-model icon-chain tier (the call
     *  that produced [icon]). Mirrors [modelTitleTraceFile]. */
    var iconTraceFile: String? = null,
    /** Per-agent icon produced by Create → Report icons. Filled
     *  by [ReportViewModel.runReportIcons] which fires the
     *  internal/icon prompt against this agent's own (provider,
     *  model) with @PROMPT@ replaced by [responseBody]. Null until
     *  the user runs Report icons; null too when the call errored
     *  (see [iconErrorMessage]) — the row's ✅ stays unchanged in
     *  that case. */
    var icon: String? = null,
    var iconErrorMessage: String? = null,
    var iconInputTokens: Int = 0,
    var iconOutputTokens: Int = 0,
    var iconInputCost: Double = 0.0,
    var iconOutputCost: Double = 0.0,
    /** Which tier of the 3-tier Create → Report icons chain produced
     *  the agent's current icon. 1 = chat continuation, 2 = one-shot
     *  internal/report, 3 = fixed-agent fallback against
     *  internal/report_3. Null when no tier succeeded and the
     *  icon is the 📝 fallback, or when the icon was set manually
     *  via Find alternative icons. Used by the Icon lookup screen
     *  to derive the bundled prompt name when [iconPromptUsed] is
     *  null (legacy rows). */
    var iconWinningTier: Int? = null,
    /** Bundled prompt name that produced the currently-displayed
     *  emoji on this agent — e.g. "report_1" / "report_2" / "report_3"
     *  for the 3-tier chain or "report_alt" after a Find-alt pick.
     *  Surfaces on the Icon lookup screen as the green subject row.
     *  Null on legacy rows; the screen falls back to deriving the
     *  name from [iconWinningTier]. */
    var iconPromptUsed: String? = null,
    /** Per-agent title produced from this agent's own response by the
     *  internal/model_title prompt (a fixed Anthropic call), when the
     *  "Generate per model titles" setting is on. Once non-blank it
     *  replaces the model name on the Manage-report 'report' row. Null
     *  until generated; null too when the call errored
     *  (see [modelTitleErrorMessage]). */
    var modelTitle: String? = null,
    var modelTitleErrorMessage: String? = null,
    /** "provider/model" of the agent that produced [modelTitle] (the
     *  fixed model_title agent), for the Costs breakdown. */
    var modelTitleModel: String? = null,
    var modelTitleInputTokens: Int = 0,
    var modelTitleOutputTokens: Int = 0,
    var modelTitleInputCost: Double = 0.0,
    var modelTitleOutputCost: Double = 0.0,
    var modelTitleTraceFile: String? = null,
    /** Wall-clock duration (ms) of the per-model title call, for the
     *  Report-info screen's total-API-time tally. Null before it ran. */
    var modelTitleDurationMs: Long? = null,
    /** Bundled prompt name that produced [modelTitle] — "model_title". */
    var modelTitlePromptUsed: String? = null
)

/** One captured API call from the 3-tier Create → Report icons
 *  chain. Stored on [Report.iconCalls] so the per-call All-tab in
 *  the cost export can render every attempt — including the failed
 *  earlier tiers that the chain skipped past. Same field set the
 *  export's renderCostsView Row already shows.
 *
 *  Provider + model identify the actual model that billed the call
 *  (tier 3 = DeepSeek even when the agent row itself uses a
 *  different model), which is what the global UsageStats ledger
 *  attributes too. */
data class IconCallRecord(
    val agentId: String,
    val tier: Int,
    val provider: String,
    val model: String,
    val pricingTier: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val inputCost: Double,
    val outputCost: Double,
    val durationMs: Long? = null,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    /** When non-null, overrides the agentId-based classifier used by
     *  the cost table. Find-alternative-icons fan-out calls set this
     *  to the bundled `_alt` prompt name (`main_alt`, `meta_alt`,
     *  `report_alt`, `language_alt`, `translation_alt`) so each
     *  alt call surfaces as its own per-call row labelled by prompt
     *  name. Null for the legacy 3-tier per-agent / per-pair records
     *  — those keep using the existing agentId classifier. */
    val type: String? = null,
    /** When non-null, the cost on this record is attributed to a
     *  specific [SecondaryResult] on the same report. Used by
     *  meta_alt (the user's tapped secondary-result row) and
     *  translation_alt (the first TRANSLATE row of the language) so
     *  the cost table can subtract the attributed portion from the
     *  SR's own cost row — avoiding double-counting once the per-
     *  call alt rows are added below it. */
    val attributedToSecondaryId: String? = null
)

/** A free-text note the user attaches to something in a report. The
 *  note's [targetKind] + [targetId] identify what it's pinned to:
 *  - "REPORT"     → the whole report ([targetId] = reportId)
 *  - "AGENT"      → one model response ([targetId] = ReportAgent.agentId)
 *  - "SECONDARY"  → a meta / rerank / moderation / fan-out-pair row
 *                   ([targetId] = SecondaryResult.id / PairState.id)
 *  - "FANOUT_RUN" → a whole fan-out run ([targetId] = FanOutRunState.key)
 *  All of a report's notes live on [Report.userNotes] (one JSON file),
 *  so the "all notes" screen is a plain filter. [targetKind] is a String
 *  (not an enum) to match the existing `translateSourceKind` convention
 *  and keep Gson round-trips trivial. */
data class UserNote(
    val id: String,
    val targetKind: String,
    val targetId: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ReportType { CLASSIC, TABLE }

enum class ReportStatus { PENDING, RUNNING, SUCCESS, ERROR, STOPPED }

/** One superseded prompt body, kept so a report carries a revision
 *  timeline instead of losing the earlier wording when the prompt is
 *  edited. [timestamp] is when this text was replaced. */
data class PromptRevision(
    val prompt: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Report(
    val id: String,
    /** Last-changed time — bumped on (almost) every mutation. NOT a
     *  stable creation time; use [createdAt] for that. */
    val timestamp: Long,
    /** Stable creation time, set once when the report is first created
     *  and never bumped. 0 on legacy reports written before this field
     *  existed (the Report info screen falls back to [timestamp]). */
    val createdAt: Long = 0L,
    val title: String,
    val prompt: String,
    val agents: MutableList<ReportAgent>,
    var totalCost: Double = 0.0,
    var completedAt: Long? = null,
    val rapportText: String? = null,
    val reportType: ReportType = ReportType.CLASSIC,
    val closeText: String? = null,
    /** Optional vision attachment captured at submission time. Persisted so
     *  Regenerate can re-run with the same image without forcing the user
     *  to re-attach. base64-encoded bytes; nullable mime type. */
    val imageBase64: String? = null,
    val imageMime: String? = null,
    /** Whether the per-report 🌐 web-search toggle was on at submission.
     *  Re-seeded into UiState by regenerateReport so the rerun gets the
     *  same tool descriptor injected for every agent. */
    val webSearchTool: Boolean = false,
    /** Per-report 🧠 reasoning level (low / medium / high; null = unset).
     *  Persisted alongside webSearchTool so a regenerate uses the same
     *  reasoning hint without forcing the user to re-pick. Non-thinking
     *  models drop the field at dispatch. */
    val reasoningEffort: String? = null,
    /** Set when this report is a translated copy of another report. Lets
     *  the HTML export pull the source's API traces into the JSON view
     *  alongside the translation traces. Null on regular reports. */
    val sourceReportId: String? = null,
    /** Knowledge bases attached to this report. Each agent call has
     *  its prompt prefixed with a context block built from top-K
     *  chunks across these KBs (see KnowledgeService.retrieve /
     *  formatContextBlock). Empty when no RAG is wired. */
    val knowledgeBaseIds: List<String> = emptyList(),
    /** Generation config captured at create time so Regenerate replays the
     *  SAME selections instead of falling back to whatever the live UiState /
     *  Settings hold now (after a restart or a settings edit). Persisted on
     *  the Report file (and so in backup/restore). Empty/null on legacy
     *  reports created before this existed — those regenerate with current
     *  settings, as they did before. */
    val parameterPresetIds: List<String> = emptyList(),
    val advancedParameters: AgentParameters? = null,
    val selectionParamsById: Map<String, List<String>> = emptyMap(),
    val reportSystemPromptId: String? = null,
    /** User-pinned flag. Pinned reports surface as their own group
     *  above the recent rows on the AI Reports hub. Persisted on the
     *  Report file so it survives across launches. */
    var pinned: Boolean = false,
    /** Sum of input + output cost (USD) for every row deleted from
     *  this report — agent rows, secondary results, fan-out pairs,
     *  fan-in rows, translations, etc. Bumped on every delete by the
     *  callers that own the deletion path; surfaced as a dedicated
     *  "Costs from deleted items" line on the result page just above
     *  the total when non-zero. Lets the user see what the API
     *  actually billed for the run even after they've trimmed the
     *  visible rows. */
    var costsFromDeletedItems: Double = 0.0,
    /** Resolved emoji for this report. Filled by a background LLM
     *  call kicked off at the start of generation that runs the
     *  bundled `internal/icon` prompt against its pinned agent. Null
     *  while the call is in flight, while no `internal/icon` prompt
     *  is configured, on call failure (see [iconErrorMessage]), or
     *  on legacy reports created before the feature shipped.
     *  Surfaces everywhere a report is shown — hub Existing reports,
     *  History rows, search hits, the result-screen title bar. */
    var icon: String? = null,
    /** Failure reason from the icon-gen call. Null while running, on
     *  success, or when the call was never kicked off. Set instead of
     *  [icon] when the LLM returned an error or an empty body. */
    var iconErrorMessage: String? = null,
    /** Token usage + USD cost of the icon-gen call, split input vs
     *  output so the call surfaces as a proper row in the per-call
     *  cost tables (in-app View → Costs and the HTML export).
     *  Computed at write time from the call's token usage × the
     *  (provider, model) pricing tier; rolled into the report total
     *  alongside agent and secondary spend. All zero while running,
     *  on missing pricing, or on legacy reports. */
    var iconInputTokens: Int = 0,
    var iconOutputTokens: Int = 0,
    var iconInputCost: Double = 0.0,
    var iconOutputCost: Double = 0.0,
    /** Trace filename captured for the report-icon call (the bundled
     *  internal/icon prompt run). Lets the icon detail screen wire
     *  its 🐞 directly to that trace. Null when tracing is off, on
     *  legacy reports, or during the cold window before the call
     *  returned. */
    var iconTraceFile: String? = null,
    /** When non-null, the model that produced the currently displayed
     *  icon (set by "Find alternative icons" — the user picked a
     *  fan-out result over the bundled-agent default). When null, the
     *  icon row / detail screen fall back to the pinned icon-prompt
     *  agent label. Stored as "<providerId>/<modelId>" so the row
     *  never has to re-resolve through the agent list. */
    var iconModel: String? = null,
    /** Bundled prompt name that produced the currently-displayed
     *  [icon]. "main" on initial gen; "main_alt" after a Find-alt
     *  pick. Surfaces on the Icon lookup screen as the green
     *  subject row. Null on legacy reports — Icon lookup falls
     *  back to "main". */
    var iconPromptUsed: String? = null,
    /** Failure reason from the AI title-gen call (the bundled
     *  `internal/report_title` prompt). Null while running, on
     *  success, on legacy reports, or when the user is in MANUAL
     *  title mode. Set instead of overwriting [title] when the LLM
     *  returned an error / empty body. */
    var titleErrorMessage: String? = null,
    /** Token usage + USD cost of the AI title-gen call, split
     *  input vs output so the call surfaces as a proper row in the
     *  per-call cost tables. Computed at write time × the pricing
     *  tier; rolled into the report total alongside agent + icon +
     *  language spend. All zero while running, in MANUAL mode, on
     *  missing pricing, or on legacy reports. */
    var titleInputTokens: Int = 0,
    var titleOutputTokens: Int = 0,
    var titleInputCost: Double = 0.0,
    var titleOutputCost: Double = 0.0,
    /** Trace filename captured for the AI title-gen call. Null when
     *  tracing is off, on legacy reports, or before the call
     *  returned. */
    var titleTraceFile: String? = null,
    /** Resolved "<providerId>/<modelId>" for the agent that
     *  produced the AI-generated title. Surfaces on the Manage
     *  report `title` row. Null in MANUAL mode, while running, or
     *  on legacy reports. */
    var titleModel: String? = null,
    /** Bundled prompt name that produced the title — currently
     *  always "report_title" on success. Doubles as the "AI gen
     *  succeeded" sentinel that drives the 🏷️ status icon on the
     *  Manage `title` row (null while running, non-null = success). */
    var titlePromptUsed: String? = null,
    /** Longer report title (≤50 chars) for the top-bar orange line. Set by
     *  AI title-gen alongside the short [title]; null/blank for manually-set
     *  titles, which fall back to [title] via [barTitle]. */
    var titleLong: String? = null,
    /** The title is produced by TWO calls (short ≤25 + long ≤50). The
     *  [titleInputTokens] … block above holds the SHORT call's spend; these
     *  mirror it for the LONG call so each surfaces as its own cost row
     *  (report/title-short vs report/title-long). All zero in MANUAL mode,
     *  while running, or on legacy reports. */
    var titleLongInputTokens: Int = 0,
    var titleLongOutputTokens: Int = 0,
    var titleLongInputCost: Double = 0.0,
    var titleLongOutputCost: Double = 0.0,
    var titleLongTraceFile: String? = null,
    var titleLongModel: String? = null,
    var titleLongDurationMs: Long? = null,
    /** Wall-clock duration (ms) of each report-level metadata API call,
     *  for the Report-info screen's total-API-time tally. Null on legacy
     *  reports / before the call ran. The agent + secondary + icon-chain
     *  calls carry their own durationMs already. */
    var iconDurationMs: Long? = null,
    var languageDurationMs: Long? = null,
    var languageIconDurationMs: Long? = null,
    var titleDurationMs: Long? = null,
    /** Per-call audit log for the 3-tier Create → Report icons
     *  chain. Cleared whenever a fresh chain run starts; otherwise
     *  every tier appends one [IconCallRecord]. The export's per-
     *  call All-tab pulls from this list so every attempt (failed
     *  earlier tiers + the one that won) surfaces as its own row. */
    var iconCalls: MutableList<IconCallRecord> = mutableListOf(),
    /** UUID shared by every API trace produced during the initial
     *  generation of this report — the L1 🐞 icon in the title bar
     *  deep-links the trace list to this id so the user sees only
     *  the calls this report's run made. Null on reports persisted
     *  before this field existed. */
    var runId: String? = null,
    /** Detected English name of the prompt's source language
     *  (e.g. "Dutch", "English"), produced by the bundled
     *  `internal/language_icon` prompt running against its pinned
     *  agent (DeepSeek by default) when icon-gen is on. Null while
     *  the detection call is in flight, on failure (see
     *  [languageIconErrorMessage]), or on legacy reports created
     *  before the feature shipped. */
    var languageName: String? = null,
    /** Fitting emoji for [languageName], picked by the same
     *  bundled-agent call. Surfaces as the icon cell on the
     *  Report - manage screen's "language" row. Null whenever
     *  [languageName] is null. */
    var languageIcon: String? = null,
    /** When non-null, the model that produced the currently
     *  displayed [languageIcon] — set by the language-icon "Find
     *  alternative icons" fan-out pick. Null = bundled agent
     *  default; falls back to the pinned `internal/language_icon`
     *  agent label in the detail screen. Stored as
     *  "<providerId>/<modelId>". */
    var languageIconModel: String? = null,
    /** Bundled prompt name that produced the currently-displayed
     *  [languageIcon]. "language" on initial gen (the second-call
     *  emoji prompt); "language_alt" after a Find-alt pick.
     *  Surfaces on the Icon lookup screen as the green subject
     *  row. Null on legacy reports — Icon lookup falls back to
     *  "language". */
    var languageIconPromptUsed: String? = null,
    /** Failure reason from the language-icon call. Null while
     *  running, on success, or when the call was never kicked off.
     *  Surfaces on the manage-screen row as a ❌ + message and on
     *  the detail screen's Response card. */
    var languageIconErrorMessage: String? = null,
    /** Token usage + USD cost of the SECOND language-flow call (the
     *  one that picks an emoji for the already-detected language).
     *  Surfaces as the cost row labelled "language-icon" on the
     *  Report - manage screen and HTML export. Mirrors the icon-gen
     *  cost fields above. All zero while running, on missing pricing,
     *  or on legacy reports written before the two-call split.
     *  Bumped per-call when "Find alternative icons" runs a
     *  language-icon fan-out. */
    var languageIconInputTokens: Int = 0,
    var languageIconOutputTokens: Int = 0,
    var languageIconInputCost: Double = 0.0,
    var languageIconOutputCost: Double = 0.0,
    /** Trace filename of the language-icon API call. Wires 🐞 on
     *  the language detail screen. Null when tracing is off, on
     *  legacy reports, or during the cold window. */
    var languageIconTraceFile: String? = null,
    /** Raw assistant text returned by the language-icon model (the
     *  single emoji). The language detail screen renders this
     *  verbatim so the user sees exactly what the model said. Null
     *  on legacy reports / before the call returned. */
    var languageIconRawResponse: String? = null,
    /** Token usage + USD cost of the FIRST language-flow call (the
     *  one that detects [languageName]). Tracked separately from
     *  the icon call above so the cost table can show two rows:
     *  type = "language" for the detection call, type =
     *  "language-icon" for the icon call. Zero on legacy reports
     *  whose single combined call was logged under
     *  [languageIconInputCost] instead. */
    var languageInputTokens: Int = 0,
    var languageOutputTokens: Int = 0,
    var languageInputCost: Double = 0.0,
    var languageOutputCost: Double = 0.0,
    /** Trace filename of the language-detection API call. Wires 🐞
     *  on the detection row's cost popup. Null when tracing was off
     *  at call time or on legacy reports. */
    var languageTraceFile: String? = null,
    /** Raw assistant text returned by the language-detection model
     *  (typically a single `language: …` line). Null on legacy
     *  reports / before the call returned. */
    var languageRawResponse: String? = null,
    /** Superseded prompt bodies, oldest-first. Each prompt edit pushes
     *  the prior text here, giving a revision timeline within a single
     *  report (Manage → Edit prompt → Previous prompts) instead of
     *  the earlier wording being lost or scattered across disconnected
     *  reports. Empty on legacy reports / reports whose prompt was
     *  never edited. */
    val promptHistory: List<PromptRevision> = emptyList(),
    /** Free-text notes the user attached to this report and its parts.
     *  See [UserNote] for the targetKind/targetId scheme. Empty on
     *  legacy reports / reports the user never annotated. Guarded in
     *  [com.ai.data.ReportStorage]'s normalizeReport against Gson's
     *  default-not-applied null trap, same as [iconCalls]. */
    var userNotes: MutableList<UserNote> = mutableListOf()
)

/** Title for the top-bar orange line: the long title when present, else the
 *  short [Report.title]. Manual edits clear [Report.titleLong], so a
 *  manually-set report shows its short title here. List cards / search /
 *  exports keep using [Report.title] directly. */
val Report.barTitle: String get() = titleLong?.takeIf { it.isNotBlank() } ?: title

/** The user notes pinned to one target ([targetKind] + [targetId]),
 *  newest first. Pure filter over [Report.userNotes] — callers load the
 *  report (e.g. via ReportStorage.getReport) and call this. */
fun Report.notesFor(targetKind: String, targetId: String): List<UserNote> =
    userNotes.filter { it.targetKind == targetKind && it.targetId == targetId }
        .sortedByDescending { it.createdAt }

