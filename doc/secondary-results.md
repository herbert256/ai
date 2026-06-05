# Secondary Results: Meta prompts, Rerank, Moderate, Translate, Fan-out / Fan-in, Tournament, Judges, Compare

A "secondary result" operates on a finished report's per-agent
outputs. Exactly seven kinds exist — `SecondaryKind`
(`data/SecondaryModels.kt:9`):

```kotlin
enum class SecondaryKind { RERANK, META, MODERATION, TRANSLATE, TOURNAMENT, JUDGES, COMPARE }
```

| Kind | Purpose | What the call produces |
|---|---|---|
| `RERANK` | Rank the responses 1..N | Strict JSON: `[{id, rank, score, reason}, ...]` |
| `META` | Any user-defined chat-type Meta prompt — "Compare", "Critique", "Summarize", anything the user names in the Internal-prompt CRUD. **Also** covers Fan-out per-pair rows and Fan-in combined-report rows | Free-form prose; the body is whatever the prompt template asked for |
| `MODERATION` | Per-response policy classification | Structured JSON from a provider's `/moderations` endpoint (no chat prompt) |
| `TRANSLATE` | Translate prompt + responses to one or more languages | One row per (source × language) — see [translation.md](translation.md) |
| `TOURNAMENT` | Worker-judged head-to-head answer tournament | `N(N-1)` MATCH rows + one AGGREGATE ranking row |
| `JUDGES` | Judge-the-judges agreement analysis | Every judge scores the same random answer-pairs; one AGGREGATE row stores agreement |
| `COMPARE` | Compare-with-meta similarity grid | A 0..100 similarity score per (answer × the one chosen Meta row) cell; no aggregate row |

Every chat-type prompt routes through the single `META` kind; the
user-given prompt name carried on the row (`metaPromptName`) is what
the UI and exports bucket by. The **kind** decides which API path
handles the call (rerank endpoint / moderation endpoint / chat); the
**name** decides how the result is grouped, labelled, and exported.
The Fan-out per-pair rows and the Fan-in combined-report row also
carry `kind = META` but are distinguished by structure:
`fanOutSourceAgentId != null` (a Fan-out pair) and `fanInOf != null`
(a Fan-in combined report).

`SecondaryResult` (`data/SecondaryModels.kt:48`) is a single flat row
type shared by all seven kinds. Common fields: `id`, `reportId`,
`kind`, `providerId`, `model`, `agentName`, `timestamp`, `content`,
`errorMessage`, `tokenUsage`, `inputCost` / `outputCost`,
`durationMs`, `traceFile`. Kind-specific clusters layer on top
(TRANSLATE language fields, META `metaPromptId` / `metaPromptName` /
`fanOutSourceAgentId` / `fanInOf` / `secondaryScope`, per-pair
Fan-Meta `icon` / `title` fields, TOURNAMENT `tournamentRole` /
`tournamentMatrix`, COMPARE `compareRunId` / `compareToResultId`).

Each result is the work of a single chosen model and is persisted
independently — a report can accumulate any combination, and each
entry is independently viewable and deletable.

Tournament, Judge-the-judges, and Compare are grid-shaped
worker-judged batches; their cell/aggregate plumbing lives in
[tournament-judges-compare.md](tournament-judges-compare.md). This
doc covers the single-call kinds (Rerank, Meta, Moderation,
Translate) and Fan-out / Fan-in in detail, and links out for the
grid kinds.

## How a kind is routed

There is **no** name→kind lookup function. Three explicit entry
methods on `SecondaryRunManager` (`viewmodel/SecondaryRunManager.kt`)
set the kind directly, all wired from
`ui/report/manage/Nav.kt`:

| UI action | Entry method | Resulting kind |
|---|---|---|
| Rerank | `secondary.runRerank` | `RERANK` |
| Any Meta button | `secondary.runMetaPrompt` | `META` |
| Moderation | `secondary.runModeration` | `MODERATION` |
| Fan out card | `fanOutEngine.startRun` | `META` (per-pair) |
| Fan in | `secondary.runFanInPrompt` | `META` (`fanInOf` set) |
| Translate | `rvm.translation` (`TranslationRunManager`) | `TRANSLATE` |
| Tournament | `tournamentEngine` | `TOURNAMENT` |
| Judge the judges | `judgeEvalEngine` | `JUDGES` |
| Compare with meta | `compareEngine` (`CompareEngine`) | `COMPARE` |

`runRerank`, `runMetaPrompt`, and `runModeration` all funnel into the
shared `executeSecondaryTask`. Moderation short-circuits to
`com.ai.data.callModerationApi` before the chat path. Rerank only
takes the dedicated rerank endpoint when
`isRerankApiPath = kind == RERANK && getModelType(provider, model)
== ModelType.RERANK` (`SecondaryRunManager.kt:1240`) — a *chat* model
picked for rerank goes through the normal analyse path and is
expected to emit the rerank JSON itself. For provider `Local`,
`runRerank` delegates to `runLocalRerank` (MediaPipe `TextEmbedder`,
cosine of each response to the prompt; rows saved with
`providerId = "LOCAL"`).

Every public runner bumps `UiState.activeSecondaryBatches` on entry
and decrements it in a `finally` block — that count drives the
result-screen poll / hourglass.

## Internal Prompt CRUD

Every chat-type meta / fan-out / fan-in / worker / fixed-internal
prompt lives as an `InternalPrompt` row, keyed by `category` +
`name`. Settings → AI Setup → **Prompt management** is the CRUD
surface, broken into category buckets. The current bundled seed set
under `assets/internal-prompts/<language>/<category>/` is:

- **Meta prompts** (`category = "meta"`) — run on the full report
  (or a `SecondaryScope`). Bundled seeds: `compare`, `summarize`.
  Users add their own ("Critique", "Synthesize", …) freely.
- **Fan-out prompts** (`category = "fan_out"`) — run across every
  (answerer × source) pair. Bundled seeds: `response`, `factcheck`.
- **Fan-in prompts** (`category = "fan_in"`) — combine fan-out
  responses back into a single combined-report row. Bundled seeds:
  `test`, `factcheck`.
- **Compare prompts** (`category = "meta_compare"`) — worker-judged
  prompts used by Compare-with-meta. Bundled seed: `equivalent`
  (asks for a 0..100 similarity percentage plus a reason).
- **Worker prompts** (`category = "workers"`) — prompt +
  fallback-worker chains for generated metadata and worker-judged
  batches. Bundled seeds: `tournament`, `fan-meta`, `model-icons`,
  `model-titles`, `report-icon`, `report-language-name`,
  `report-language-icon`, `report-title-short`, `report-title-long`,
  `second-meta`, `translation-icon`, `user-note`.
- **Alternative prompts** (`category = "alt"`) — prompts used by the
  Find-alternative icon / title / language flows. Bundled seeds:
  `main`, `report`, `fan_out`, `language`, `meta`, `translation`,
  `report_title`, `report_title_long`, `model_title`.
- **Other internal** (`category = "internal"`) — a fixed list of
  eight templates: `chat-title`, `model-info`, `model-intro`,
  `translate-text`, `translate-title`, `second-rerank`,
  `second-moderation`, `test-model`. No Add / Delete in this bucket.

Each entry has:

- **name** — user-facing label that ends up as `metaPromptName` on
  every row it produces. Unique within `(category, name)`, so a
  "compare" can exist under both `meta` and `fan_in` without
  collision.
- **title** — one-line description shown alongside `name` on the
  Fan out card and the prompt-edit screen.
- **reference** — when true, the run appends a deterministic
  `## References` legend mapping `[N]` to provider / model. See
  "Reference legend" below.
- **agent** — `"*select"` (the default — ask the user which model to
  run on) or a literal `Agent.name`.
- **text** — the prompt template, with placeholders.

The bundled `*.json` files only gate **fresh-install seeding**;
there are no in-code `DEFAULT_*` prompt constants. After first
launch the CRUD is the source of truth.

## Lifecycle

```
[ Report finishes ]
        │
        ▼
[ Tap a button on the result page                           ]
[ (one button per Meta prompt, plus Rerank, Moderation,     ]
[  Translate, and the Fan out / Tournament / Compare cards) ]
        │
        ├── Rerank / Moderation: skip the scope screen
        │   (always operate on the full set)
        │
        ├── chat-type Meta / Translate / Fan-out:
        │   ▼
        │  [ SecondaryScopeScreen ]
        │     • All model reports
        │     • Only top ranked reports → Number + Rerank-row dropdown
        │     • Manual selection → checklist of agents
        │     • chat-type Meta and Translate also: language scope
        │       (All present / Selected) when translations exist
        │     ▼
        ▼
[ Model picker (multi-select) ]
   • Rerank picker has a "rerank models only" toggle
        │
        ▼
[ Run — N independent calls in parallel,                    ]
[ gated by ProviderThrottle per provider host               ]
[ (default 60/min + 5 concurrent; see throttle.md)          ]
[ plus the per-flow ApiCallCaps sub-cap                     ]
   • Multi-language: chat-type META runs once in a seed language,
     then appends cross-translations to the other languages'
     translation runs (it does NOT fan out N×M independent rows)
   • Multiple TRANSLATE batches can run concurrently (one runId each)
        │
        ▼
[ Each result saved as <filesDir>/secondary/<reportId>/<id>.json ]
   • metaPromptId / metaPromptName stamped on every chat-type META row
   • secondaryScope encoded onto the row at save time
   • For META rows with reference = true: a deterministic
     "## References" legend is appended at storage time
   • Fan-out rows carry fanOutSourceAgentId
   • Fan-in rows carry fanInOf = <metaPromptId>
   • Tournament / Judges / Compare rows carry run + cell metadata
     described in tournament-judges-compare.md
```

## Prompt resolution

For a chat-type Meta run the template is the `InternalPrompt.text`
of whichever Meta button the user tapped. For RERANK runs that take
the chat path, the template is the rerank-typed entry the user
picked (defaults to the seeded `second-rerank` entry). For TRANSLATE
runs the runtime looks up the `internal`-category `translate-text`
(bodies) / `translate-title` (titles) prompts. MODERATION runs
through a provider's `/moderations` endpoint, which takes no chat
prompt — there is nothing to substitute.

Placeholder substitution lives in `data/SecondaryResult.kt`:
`resolveSecondaryPrompt` for single-call kinds, `resolveFanInPrompt`
for Fan-in.

| Variable | Substituted with | Resolver |
|---|---|---|
| `@QUESTION@` | Original report prompt (or its translation when the run language ≠ original) | both |
| `@RESULTS@` | Pre-formatted block, see below | `resolveSecondaryPrompt` |
| `@COUNT@` | Number of results being processed | both |
| `@TITLE@` | Report title (or empty) | both |
| `@DATE@` | Current date/time | `resolveSecondaryPrompt` |
| `@LANGUAGE@` / `@TEXT@` | TRANSLATE only — target language name / source text (`translate-text`) | translation flow |
| `@RESPONSE@` | Fan-out / Compare cell — the source agent's response body | fan-out / `CompareEngine` |
| `@META_RESPONSE@` | Compare cell — the Meta content (its `## References` legend stripped) | `CompareEngine` |
| `@FAN_OUT_COUNT@` | Fan-in only — number of fan-out source agents | `resolveFanInPrompt` |
| `***Report*** @REPORT@@RESPONSES@` | Fan-in only — iterable block (whitespace-tolerant), expanded once per source agent; each `@RESPONSE@` inside `@RESPONSES@` becomes one fan-out response for that source | `resolveFanInPrompt` |

## The @RESULTS@ block

`buildResultsBlock(report, includeIds?)` (`SecondaryResult.kt:1013`)
emits one block per **success** agent, prefixed only with the
bracketed `[N]` id — no provider / model identifiers inline (those
reach the user via the appended Reference legend, not the prompt):

```
[1]
<full response text from agent #1>

[2]
<full response text from agent #2>

…
```

`N` is **1-based success order** — the stable id rerank models echo
back in their JSON, and the same id the `result-N` HTML anchors in
the report export use, so chat-type Meta references like "as [3]
noted" auto-link back to that agent's card.

When a TopRanked or Manual scope filters the input via `includeIds`,
the original `[N]` numbering is **preserved** — the block becomes
sparse (e.g. `[1]`, `[4]`, `[7]` only) so the export anchors still
resolve.

For multi-language chat-type Meta batches the agent response bodies
are pulled from the matching TRANSLATE rows when present (so each
language's batch sees translated content), with the prompt-side
`@QUESTION@` likewise translated.

## Reference legend (chat-type Meta only)

When the Meta prompt's `reference` flag is true, after the model
returns its output `executeSecondaryTask` appends a deterministic
reference legend to the persisted content:

```
…model output…

---

## References

[1] = OpenAI / gpt-5
[2] = Anthropic / claude-opus-4
[3] = Google / gemini-2.5-pro
```

The legend is built once per batch via `buildReferenceLegend(report,
includeIds)` (`SecondaryResult.kt:1035`), so it honours the same
Manual / TopRanked filter as the results block and uses the same
1-based ids. It is written before save, so subsequent renders /
exports include it without further work.

## Scope encoding

The chosen `SecondaryScope` (`data/SecondaryScopes.kt`) is encoded as
a string and stored on the row's `secondaryScope` field at run time:

| Scope | Encoded |
|---|---|
| `AllReports` | `"ALL"` |
| `TopRanked(count, rerankResultId)` | `"TOP:<rerankResultId>:<count>"` |
| `Manual(agentIds)` | `"MANUAL:<id1>,<id2>,..."` |

A `TopRanked` `count` is clamped to the current successful count, so
a stale out-of-range position cannot inflate `@COUNT@`. The
cascade-on-prompt-change path reads `secondaryScope` and re-runs at
the same scope rather than silently widening to `AllReports`. Legacy
rows (no `secondaryScope` set) fall back to `AllReports`.

## Tournament / Judges / Compare

The grid-shaped secondary kinds are **not** launched through the
single-call Meta picker — each has its own engine, sentinel
placeholders, and L1/L2/L3 drill-in:

- **Tournament** (`TournamentEngine`) — for `N` successful answers it
  creates `N(N-1)` ordered MATCH placeholders (each unordered pair
  judged twice, A-vs-B and B-vs-A, to cancel position bias) plus one
  AGGREGATE row. Matches are worker-judged via the bundled
  `workers/tournament` swarm (round-robin), so the judging model is
  unknown until the chain returns. MATCH placeholders start at
  `providerId = "*workers"` / `model = "*pending"`; the AGGREGATE row
  uses `providerId = "*tournament"` / `model = "aggregate"`. Ranking
  methods (Copeland / ELO / Davidson / Tideman / Markov) recompute
  locally from the stored win-matrix sidecar with no API calls.
- **Judge the judges** (`JudgeEvalEngine`) — reuses the same
  `workers/tournament` prompt, but **every** concrete judge model in
  the swarm scores the **same** random set of `25` answer-pairs
  (capped by available distinct pairs). Each cell is a fixed-model
  call; the AGGREGATE row stores each judge's agreement with the
  consensus.
- **Compare with meta** (`CompareEngine`) — the user picks **one**
  existing plain Meta row (single tap) plus a `meta_compare` prompt
  (bundled `equivalent`). The grid is (successful answers × 1 chosen
  Meta row); each cell is worker-judged, starting at
  `providerId = "*workers"` / `model = "*pending"` and overwritten
  with the winning worker. The cell prompt substitutes `@RESPONSE@`
  (answer body) and `@META_RESPONSE@` (Meta content, legend
  stripped). There is **no** aggregate row — per-answer averages are
  computed from the cells.

See [tournament-judges-compare.md](tournament-judges-compare.md) for
the full cell/aggregate model, resume orchestration, and ranking
math.

## Storage

```
<filesDir>/secondary/<reportId>/<resultId>.json   ← one SecondaryResult per file
```

`SecondaryResultStorage` (an `object` in `data/SecondaryResult.kt`,
**not** a separate `…Storage.kt` file) owns all of it. It uses a
`ReentrantLock`, a shared Gson instance, and a per-file parse cache
(`listCache`) keyed by `(name, mtime, length)` and invalidated
per-filename on every save / delete, so an in-place edit to one file
invalidates only that entry. Every mutating op calls
`SecondaryDataVersion.bump()` so Compose observers reload.

Path-traversal defence runs on **both** sides: writes go through
`reportDir` (mkdirs + flat-id + canonical-containment checks), reads
through `resolveReportDirForRead` (same checks, no mkdirs). Both
reject blank ids, `.`, `..`, and ids containing `/` or `\`, and
`save()` additionally skips if the parent report no longer exists.

Cascades on parent-report deletion: `ReportStorage.deleteReport`
calls `SecondaryResultStorage.deleteAllForReport`, which removes the
per-report directory and drops its whole cache bucket.

Useful methods beyond the basics:

- `create(extras)` — seeds a placeholder row, with a lambda to set
  kind-specific fields atomically.
- `saveIfStillPresent` — TOCTOU-safe re-dispatch write; for
  Regenerate it **adds** the prior input/output cost+tokens onto the
  incoming row (additive accumulation, not overwrite).
- `listForReport(reportId, kind?)`, `get`, `updateContent`, `delete`.
- `countForReport` → `Counts(rerank, meta, moderation, translate,
  tournament, judges, compare)`. TOURNAMENT and JUDGES count **only**
  `tournamentRole == "AGGREGATE"` rows (the MATCH rows are inspection
  detail); COMPARE counts cells flat. `countByMetaName` groups the
  remaining chat-type Meta rows by `metaPromptName`.
- Tournament / Compare cell commits (`recordTournamentMatch`,
  `recordCompareCell`, and their `reset*` siblings) and the Fan-out
  icon/title setters live here too.

Tournament / Judges / Compare runs are stored in the same directory;
their in-memory run maps are disposable and hydrate from rows grouped
by run id on reopen (newest run group wins).

## Display labels

`legacyKindDisplayName(kind)` (`SecondaryResult.kt:914`) is the
fallback label when a row has no `metaPromptName`:

| Kind | Label |
|---|---|
| `RERANK` | Rerank |
| `META` | Meta |
| `MODERATION` | Moderation |
| `TRANSLATE` | Translate |
| `TOURNAMENT` | Tournament |
| `JUDGES` | Judge the judges |
| `COMPARE` | Compare |

`secondaryPromptDisplayName(name)` collapses the internal asset names
back to friendly labels: `second-rerank → rerank`,
`second-moderation → moderation`, `second-tournament → tournament`;
user-authored names pass through unchanged.

## Per-row icons (every kind)

Every `SecondaryResult` row carries its own `icon` field, so a
Rerank, Moderation, Meta, or fan-out-pair row can each show a
generated emoji. The Find-alternative-icon flow
(`IconGenerationManager.startPairIconFanOut`) is **generalised to
ANY row** — keyed on the row id — so a Rerank or Moderation icon is
re-findable through exactly the same flow as a fan-out pair (the
`@SOURCE_RESPONSE@` / `@META_PROMPT@` tokens just resolve empty for a
sourceless row). All of these surface on the **Edit icons** list and
open the unified **Icon lookup** detail (Find alternative / Manual
edit / Select icon). See [report-icons.md](report-icons.md).

## Fan-out / Fan-in

Fan-out is owned by **`FanOutEngine`**
(`viewmodel/FanOutEngine.kt`); Fan-in by
`SecondaryRunManager.runFanInPrompt`. The legacy
`ReportViewModel.runFanOutPrompt` is gone — fan-out now launches via
`reportViewModel.fanOutEngine.startRun(...)` from
`ui/report/manage/Nav.kt`.

- **Fan-out** runs the chosen `category = "fan_out"` Internal Prompt
  once per (answerer × source) pair. Each `@RESPONSE@` in the
  template is replaced by the source agent's response body.
  Concurrency is gated first by `ApiCallCaps.fanOut` (the per-flow
  sub-cap), then by the per-provider throttle, so both per-flow and
  provider-host limits apply. Hot per-pair UI state lives outside
  `UiState` so 5–15 Hz updates don't ripple through the rest of the
  composition.

### Fan-out runtime model

- **`FanOutRunState`** (`data/FanOutRunModel.kt`) is the canonical
  per-run snapshot — `pairs: Map<PairKey, PairState>`, plus
  `combinedReports`, scope, and the meta-prompt. Each `PairState`
  carries an explicit `PairStatus` (PENDING / RUNNING / DONE /
  ERROR), so the UI classifier reads one field instead of merging
  three loosely-coupled views.

- **`FanOutEngine`** owns the authoritative `runs:
  StateFlow<Map<FanOutRunKey, FanOutRunState>>`. Every state
  transition (pair queued, permit acquired, HTTP completed, error
  stamped, row deleted) is one atomic `_runs.update { … }`, so
  subscribers see exactly one value per transition. The engine
  hydrates from disk on demand (`hydrate(context, reportId)`) and
  delegates the actual HTTP + save to
  `ReportViewModel.executeSecondaryTask`.

- A **per-pair Job map** keyed by `PairState.id` lets destructive
  paths `cancelAndJoin` a specific pair before deleting its disk row,
  closing the "result lands after delete" race. Registration happens
  before `start()` (via `CoroutineStart.LAZY`) so concurrent deletes
  always find the Job.

- The **UI** lives under `ui/report/manage/`: `Fan.kt`
  (`FanOutScreen` parent + nav + the `enum class FanOutMode { MAIN,
  META }`) plus `FanL1.kt` / `FanL2.kt` / `FanL3.kt`. The parent
  holds a `FanOutNav` sealed-class state in `rememberSaveable`; the
  back-stack survives rotation. Each level subscribes to
  `engine.runs.collectAsState()` and renders the current snapshot —
  no polling, no merging of disk + StateFlow.

- The same `Fan*` screens render two **modes** off one nav tree:
  `FanOutMode.MAIN` (the per-pair fan-out responses) and
  `FanOutMode.META` (the **Fan Meta** drill-in over the per-pair
  title + icon metadata — see "Fan Meta drill-in").

- **Fan-in** runs the chosen `category = "fan_in"` Internal Prompt
  once per **source agent** (NOT once per answerer × source pair).
  The `***Report*** @REPORT@@RESPONSES@` iterable block is matched
  whitespace-tolerantly and expanded once per source agent, with
  `@RESPONSES@` populated by every fan-out response for that source.
  Output rows carry `fanInOf = <metaPromptId>` so the drill-in
  distinguishes them from per-pair rows.

The Fan-out drill-in is three levels deep:
- **L1** — one row per (answerer, prompt). `✅` when done, `❌` when
  any pair errored, `⏳` while a new combined-report row arrives. Per-
  row cost + total banner. Empty-body successes count as Done.
- **L2** — one row per (answerer, source) pair, virtualised so long
  lists scroll smoothly.
- **L3** — single response detail with a 🐞 link to the original
  report-model trace.

`resumeStaleRunsForReport` (delegated to `fanOutEngine`) re-reads
each row before stamping "Interrupted", so a cold launch mid-run
recovers genuinely-stuck placeholders without losing in-flight work.
Re-run paths rebuild the per-pair set and dedupe in-flight job keys
so one tap doesn't fork two batches.

### Fan Meta drill-in

The same `Fan*` screens in `FanOutMode.META` give each fan-out pair a
generated **title + icon** — one `workers/fan-meta` worker call per
pair returns both (see [report-icons.md](report-icons.md)):

- **L1** (titled **Fan Meta**) has two grouping modes — **Meta
  models** (group by the meta-worker model that produced the
  title+icon, `PairState.titleModel`) and **Report models** (group by
  the answerer model) — plus a flat **Fan Meta - All** list of every
  pair's title.
- **L2** is titled **Fan Meta - model** (or **Fan Meta - meta model**
  in the Meta-models grouping).
- **L3** (titled **Fan Meta - pair**) is a purpose-built metadata
  screen: big centred icon, big title, the two model lines (fan-out
  model / meta model), and per-pair **Find alternative icon** / **Find
  alternative title** buttons. A horizontal **swipe** (not Prev/Next
  buttons) steps between pairs — right = previous, left = next.

The Fan Meta batch auto-starts when a fan-out run finishes with no
errored pairs (`autostartFanMeta`, default on).

## HTML export

After the main report sections, the view-picker offers one tab per
content type, in this order (when present):

1. **Reports** — agents in either One-by-one or All-together layout.
2. **One tab per chat-type Meta prompt name** — one tab per unique
   `metaPromptName` on the report. The tab label *is* the user-given
   name; multiple Meta prompts each get their own tab in first-seen
   order.
3. **Reranks** — each entry rendered as a linked rank table
   (`Rank | Result | Score | Reason`). The Result column is an
   `<a href="#result-N">[N]</a>` link back to that agent's card.
4. **Moderations** — flagged-categories table, one row per source
   response with category pills coloured by severity.
5. **Prompt / Costs / JSON** — original prompt, per-call cost table
   (with By-type and By-model rollups), captured API traces
   (Original-only).

`[N]` references inside chat-type Meta content are linkified back to
the corresponding agent anchor.

The **Zipped HTML export** builds a self-contained site with one
directory per content type per language. The per-Meta-prompt
directory name is the `metaPromptName` filtered through a
filesystem-safe regex (so `Pro/Con` becomes `Pro_Con`).

## Cost tracking

Every secondary call is tagged in `usage-stats.json` with the `kind`
it ran under: `"rerank"`, `"meta"`, `"moderation"`, `"translate"`,
`"tournament"`, `"judges"`, or `"compare"`. The AI Usage screen shows
the kind as a small pill on the per-model row.

In the Report cost summary and HTML export the **Type** column is the
hierarchical type assigned in `ReportStorage` (the cost ledger), not
a bare kind. For a META-family row it is built from `metaPromptName`:

| Row | Type string |
|---|---|
| Rerank | `after/rerank` |
| Moderation | `after/moderation` |
| Plain Meta `<name>` | `meta/<name>` (e.g. `meta/Compare`) |
| Fan-out pair | `fan_out/<name>` (default `fan_out/response`) |
| Fan-in combined | `fan_in/<name>` (default `fan_in/meta`) |
| Tournament | `after/tournament` |
| Judge the judges | `after/judges` |
| Compare cell | `meta/compare` |
| Translate | `translate/...` per source kind — see `translateTraceType` |

The same string is used as the **trace category** the run writes
(`runMetaPrompt` tags its calls `"<category>/<name>"`, e.g.
`meta/Compare`), so each row's 🐞 link points at the right captured
API trace. `SecondaryResult.fullCost()` (`SecondaryModels.kt:250`)
rolls the per-pair Fan-Meta icon + title spend into the
delete / re-run cost accounting so it isn't silently dropped.

## Concurrency and gating

- The Meta / Translate / Fan-out / Rerank buttons are **disabled**
  while the parent report is still streaming (the button row only
  shows on the result phase, after `isComplete = true`).
- Single-call kinds (chat Meta / Rerank / Moderation / Translate) are
  gated by the per-provider throttle (`ProviderThrottle` — defaults
  **60 calls/min, 5 concurrent** per provider host). Limits hold
  across every overlapping flow on the same provider host.
  See [throttle.md](throttle.md).
- Tournament, Judges, Compare, Fan-out, Fan Meta, translation, and
  primary report generation also pass through **`ApiCallCaps`**
  per-flow coroutine sub-caps (defaults: global 100, report 50,
  translation 50, fanOut 50, fanMeta 50, workers 50). The canonical
  acquisition order is sub-cap → `ApiCallCaps.global` → per-host
  gate; permits parked on a busy host gate release the outer two
  while waiting. Tune these under
  Settings → Network → Maximal API calls.
- Fan-out / Fan Meta / report-primary flows pre-acquire the
  per-provider permit on the coroutine side so the UI can distinguish
  queued vs running rows; the inline OkHttp interceptor skips its own
  acquire via the `ProviderThrottle.permitPreAcquired` context
  element.
- Multiple chat-type Meta batches **and** multiple Translate batches
  can be in flight concurrently — each batch has its own
  `metaPromptId` (chat Meta) or `translationRunId` (translate) and
  its rows surface independently in the UI.
- The fan-out / fan-in launch paths **dedupe against an in-flight job
  key** so a fast double-tap doesn't fork two batches.

## Resume orchestration

> **Detect-only by default.** The orchestrator below is **no longer
> run automatically.** A read-only 30 s background scan
> (`SecondaryRunManager.startBackgroundBrokenScan`) only *detects*
> interrupted work and publishes it to `AppViewModel.brokenReports`,
> which surfaces as the top-bar ⚠️ + the Broken-work screen
> (`BrokenWorkScreen`). The full resume orchestrator is retained for
> **explicit/manual** use (Regenerate / retry, regenerate
> orchestration) — see `detectBrokenForReport` / `classifyBrokenRow`
> for the read-only counterpart and each engine's `inFlightRowIds()` /
> `detectBroken` for the in-flight-exclusion + regenerate predicates.

`SecondaryRunManager.resumeStaleRunsForReport` is the cross-kind
resume orchestrator (manual use only). In order it: reconciles
stalled translation runs, starts missing translations, resumes
stale fan-out / tournament / judge runs (delegated to their
engines), relaunches interrupted Fan-Meta batches, re-issues
single-call Meta / Rerank / Moderation placeholders (bounded by
`BatchResume.capForRetry`), reconciles the Regenerate batch engine,
and finally marks any unrecoverable rows `❌ No data yet`. Only rows
interrupted by app death (blank content, null `errorMessage`, null
`durationMs`) are touched; TOURNAMENT / JUDGES rows are owned by
their engines and explicitly skipped in the single-call and legacy
branches. (This orchestrator now runs only on an explicit/manual
fix — the 30 s app-wide background pass is detect-only, see the note
above.)

## Native rerank / moderation endpoints

Providers that declare `nativeRerankUrl` — **SiliconFlow**
(`/v1/rerank`) and **Cohere** (`/v2/rerank`) — take rerank dispatches
through `callRerankApi` (`data/RerankModerationApi.kt`) instead of
building a chat prompt. The provider's `(index, relevance_score)`
array is re-shaped into the same `[{id, rank, score, reason}, ...]`
JSON the chat-rerank flow produces — score rescaled to 0..100 — so
the rest of the pipeline (HTML export, Top-Ranked scope, anchor
links) needs no second code path. `RerankApiResult.billedSearchUnits`
captures Cohere's per-call billing units.

Providers that declare `nativeModerationUrl` — **Mistral**
(`/v1/moderations`) — take moderation dispatches through
`callModerationApi`. The structured per-input result list is
re-encoded into the `[{id, flagged, categories, scores}, ...]` JSON
the detail screen parses. Mistral's response includes
`prompt_tokens` / `completion_tokens` / `total_tokens`, which
`callNativeModeration` lifts into `TokenUsage` so cost attribution
matches chat-driven Meta runs.

Both fall through with an explanatory error when the chosen provider
doesn't declare the URL — the user is told which provider to pick
instead.

## Adding another kind

The `when (kind)` blocks throughout the codebase are exhaustive, so
the Kotlin compiler lists every site you need to touch. See
[development.md](development.md) → "A new SecondaryKind". For most
new behaviour you don't need a new kind — adding an Internal Prompt
in a new category covers any new chat-type analysis without code
changes.
