# Secondary Results: Meta prompts, Rerank, Moderate, Translate, Fan-out / Fan-in

A "secondary result" is a meta-result that operates on a finished
report's per-agent outputs. Four kinds exist (`SecondaryKind`):

| Kind | Purpose | Default prompt asks for |
|---|---|---|
| `RERANK` | Rank the responses 1..N | Strict JSON: `[{id, rank, score, reason}, ...]` |
| `META` | Any user-defined chat-type Meta prompt — "Compare", "Critique", "Synthesize", anything the user names in the Meta-prompt CRUD. Also covers Fan-out per-pair rows and Fan-in combined-report rows | Free-form prose; the prompt body is whatever the user wrote |
| `MODERATION` | Per-response policy classification | Structured JSON from a provider's `/moderations` endpoint (no chat prompt) |
| `TRANSLATE` | Translate prompt + responses to one or more languages | Free-form prose, one row per (source × language) — see [translation.md](translation.md) |

Every chat-type prompt routes through the single `META` kind; the
user-given prompt name carried on the row (`metaPromptName`) is what
the UI and exports bucket by. The kind decides which API path
handles the call (rerank endpoint / moderation endpoint / chat); the
name decides how the result is grouped, labelled, and exported. The
Fan-out per-pair rows and Fan-in combined-report row also carry
`kind = META` but are distinguished by `fanOutSourceAgentId != null`
(Fan-out) and `fanInOf != null` (Fan-in).

Each result is the work of a single chosen model and is persisted
independently — a report can accumulate any combination, and each
entry is independently viewable and deletable.

## Internal Prompt CRUD

Every chat-type meta / fan-out / fan-in / fixed-internal prompt
lives as an `InternalPrompt` row, keyed by `category` + `name`.
Settings → AI Setup → **Prompt management** is the CRUD surface,
broken into category buckets:

- **Meta prompts** (`category = "meta"`) — runs on the full report
  (or a SecondaryScope). `Compare`, `Critique`, `Synthesize`, etc.
- **Fan-out prompts** (`category = "fan_out"`) — runs across
  every (answerer × source) pair.
- **Fan-in prompts** (`category = "fan_in"`) — combines fan-out
  responses back into a single combined-report row.
- **Model-scoped fan-in prompts** (`category = "initiator"` /
  `"requester"` / `"model"`) — produces per-(provider, model)
  rows that the Fan-out L2 page surfaces under each model's own
  bucket. Distinct from the legacy total `fan_in` (which combines
  the whole report and shows on L1's combined-rows section).
  Resolver is `resolveModelFanInPrompt` with placeholders
  `@INITIATOR@`, `@RESPONDERS@`, `@RESPONDER_PAIRS@`. Rows carry
  `scopeProviderId` / `scopeModel` so the L2 page can filter to
  the active model.
- **Other internal** (`category = "internal"`) — fixed list of
  five fixed-name templates: `intro`, `model_info`, `translate`,
  `rerank`, `moderation`. No Add / Delete in this bucket.

Each entry has:

- **name** — user-facing label that ends up as `metaPromptName` on
  every row it produces. Unique within `(category, name)` so a
  "Compare" can exist under both `meta` and `fan_in` without
  collision.
- **title** — one-line description shown alongside `name` on the
  Fan out card and the prompt-edit screen.
- **reference** — when true, the run appends a deterministic
  `## References` legend mapping `[N]` to provider / model. See
  "Reference legend" below.
- **agent** — `"*select"` (the default — ask the user which model
  to run on) or the literal `Agent.name`.
- **text** — the prompt template, with placeholders.

> Routing of meta prompts to a `SecondaryKind` is name-driven, not
> a separate `type` field. `metaTypeToKind` resolves
> `name == "rerank"` → `RERANK`, `name == "moderation"` →
> `MODERATION`, anything else → `META`. Unknown names log a warning
> and fall back to META so the run still surfaces somewhere.

## Lifecycle

```
[ Report finishes ]
        │
        ▼
[ Tap a Meta button on the result page                      ]
[ (one button per Meta prompt, plus Translate, Rerank,      ]
[  and the Fan out card)                                    ]
        │
        ├── Rerank / Moderation: skip the scope screen
        │   (always operate on the full set)
        │
        ├── chat-type Meta / Translate / Fan-out:
        │   ▼
        │  [ SecondaryScopeScreen ]
        │     • All model reports
        │     • Only top ranked reports → Number + Rank dropdown
        │     • Manual selection → checklist of agents
        │     • chat-type Meta and Translate also: language scope
        │       (All present / Selected) when translations exist
        │     ▼
        ▼
[ Model picker (multi-select) ]
   • Rerank picker has a "rerank models only" toggle
   • Pre-checks the previous run's selection (per-(reportId, prompt))
   • Includes the synthetic Local provider when at least one .task
     LLM is installed
        │
        ▼
[ Run — N independent calls in parallel,                    ]
[ gated by ProviderThrottle per provider host               ]
[ (default 30/min + 3 concurrent; see throttle.md)          ]
   • Multi-language fan-out for chat-type META and TRANSLATE:
     one batch per (language, model-pick)
   • Multiple TRANSLATE batches can run concurrently (one runId each)
        │
        ▼
[ Each result saved as <filesDir>/secondary/<reportId>/<id>.json ]
   • metaPromptId / metaPromptName stamped on every chat-type META row
   • secondaryScope encoded onto the row at save time
   • For META rows with reference=true: a deterministic
     "## References" legend mapping [N] = Provider / Model is
     appended at storage time
   • Fan-out rows carry fanOutSourceAgentId
   • Fan-in rows carry fanInOf = <metaPromptId>
```

## Prompt resolution

Every prompt — Rerank, every chat-type Meta entry, Fan-out, Fan-in,
Translate, plus the fixed internal templates (Intro, Model info) —
lives as an `InternalPrompt` row seeded from `assets/prompts.json`
on first launch. The CRUD lets the user add / rename / delete
freely after that; the JSON only gates fresh-install seeding (no
in-code `DEFAULT_*` constants exist).

For a chat-type Meta run the prompt template is the
`InternalPrompt.text` of whichever Meta-prompt button the user
tapped. For RERANK runs the template is the `InternalPrompt.text`
of the rerank-typed Meta entry the user picked (defaults to the
seeded "rerank" entry). For TRANSLATE runs the runtime looks up
the `InternalPrompt` named `"translate"` in the `internal`
category. MODERATION runs through a provider's `/moderations`
endpoint which takes no chat prompt — there's nothing to
substitute.

Templates substitute these variables:

| Variable | Substituted with |
|---|---|
| `@QUESTION@` | Original report prompt (or its translation when fan-out language ≠ original) |
| `@RESULTS@` | Pre-formatted block, see below |
| `@COUNT@` | Number of results being processed |
| `@TITLE@` | Report title (or empty) |
| `@DATE@` | Current date/time, `yyyy-MM-dd HH:mm` |
| `@LANGUAGE@` | TRANSLATE only — target language English name |
| `@TEXT@` | TRANSLATE only — the source text being translated |
| `@RESPONSE@` | Fan-out only — the source agent's response body |
| `@FAN_OUT_COUNT@` | Fan-in only — the number of fan-out source agents |
| `***Report*** @REPORT@@RESPONSES@` | Fan-in only — iterable block; expands once per source agent, with `@RESPONSES@` populated by every fan-out response for that source |
| `@INITIATOR@` | Model-scoped fan-in only — active model's own report response. Used by `initiator` / `model` categories; empty for `requester` (where the active model is the answerer, not the source) |
| `@RESPONDERS@` | Model-scoped fan-in only — block of fan-out responses where the active model is the source (other models responded TO active's report). One `***Response*** {body}` line per responder. Used by `initiator` / `model` |
| `@RESPONDER_PAIRS@` | Model-scoped fan-in only — iterable list of pairs where the active model is the answerer. Each pair renders as `***Report*** {other's report body}\n\n***Response*** {active's fan-out response}`. Used by `requester` / `model` |

## The @RESULTS@ block

`buildResultsBlock` produces:

```
[1] provider=openai model=gpt-5
<full response text from agent #1>

[2] provider=anthropic model=claude-opus-4
<full response text from agent #2>

…
```

The bracketed `[N]` is the **stable id** rerank models echo back in
their JSON output. It also matches the `result-N` HTML anchors in the
report export, so chat-type Meta references like "as [3] noted"
auto-link back to that agent's card.

When a TopRanked or Manual scope filters the input, the original
`[N]` numbering is preserved — the result block becomes sparse
(e.g. `[1]`, `[4]`, `[7]` only) so the export anchors still resolve.

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
includeIds)` so it honours the same Manual / TopRanked filter as the
results block, and is written before save so subsequent renders /
exports include it without further work.

## Scope encoding

The chosen `SecondaryScope` is encoded as a string and stored on
the row's `secondaryScope` field at run time:

| Scope | Encoded |
|---|---|
| `AllReports` | `"ALL"` |
| `TopRanked(count, rerankResultId)` | `"TOP:<rerankResultId>:<count>"` |
| `Manual(agentIds)` | `"MANUAL:<id1>,<id2>,..."` |

The cascade-on-prompt-change path reads this and re-runs at the
same scope rather than silently widening to `AllReports`. Legacy
rows (no `secondaryScope` set) fall back to `AllReports`.

## Storage

```
<filesDir>/secondary/<reportId>/<resultId>.json   ← SecondaryResult
```

Cascades on parent-report deletion: `ReportStorage.deleteReport`
calls `SecondaryResultStorage.deleteAllForReport` so the directory
goes with the parent. Per-(reportId, kind) cache validity is
fingerprinted by `(name, mtime, length)` joined across every JSON
file, so an in-place edit to one file invalidates the cache.

`SecondaryResultStorage.save` validates that the resolved
`<resultId>.json` file stays inside the configured directory — a
defence against `..`-traversal in a corrupted id.

## Cost tracking

Every secondary call is tagged in `usage-stats.json` with the
`kind` it ran under: `"rerank"`, `"meta"`, `"moderation"`, or
`"translate"`. The AI Usage screen shows the kind as a small pill
on the per-model row.

In the Report cost summary and HTML export the **Type** column
prefers the `metaPromptName` (lowercased) over the kind, so a
"Compare" row reads `compare`, a "Critique" row reads `critique`,
etc. Rerank / Moderation / Translate keep their fixed labels.
Fan-out per-pair rows surface as `cross-out` or the
prompt's lowercased name; Fan-in combined-report rows as
`cross-in`.

## Fan-out / Fan-in / Fan-icons

### `FanOutEngine` — the runtime

Fan-out execution lives on the dedicated
[`FanOutEngine`](../ai/src/main/java/com/ai/viewmodel/FanOutEngine.kt)
view model. It owns the canonical
`_runs: MutableStateFlow<Map<FanOutRunKey, FanOutRunState>>` and
mutates it atomically: every per-pair transition (queued, permit
acquired, HTTP completed, error stamped, row deleted) is a single
`_runs.update { … }` call, so subscribers see exactly one value per
transition with no flicker — no polling loop, no `recentlySettled`
grace window, no merging of disk + StateFlow.

- The per-run snapshot type is
  [`FanOutRunState`](../ai/src/main/java/com/ai/data/FanOutRunModel.kt):
  `pairs: Map<PairKey, PairState>`, `combinedReports`, `scope`,
  `metaPrompt`, plus the explicit `PairStatus` enum
  (PENDING / RUNNING / DONE / ERROR) on every `PairState` so the
  UI's classifier reads one field instead of merging three loosely
  coupled views. See [datastructures.md](datastructures.md#fanoutrunstate--pairstate--combinedreportstate).

- **Per-pair Job map** (`fanOutPairJobs`) keyed by `PairState.id`
  lets destructive paths `cancelAndJoin` a specific pair before
  deleting its disk row — closes the "result lands after delete"
  race. Registration happens BEFORE `start()` via
  `CoroutineStart.LAZY` so concurrent deletes always find the Job.

- **Concurrency** is nested. Per host, the
  [`ProviderThrottle`](throttle.md) gates 30 calls / min × 3
  concurrent (defaults; per-provider overrides apply). Across hosts,
  an engine-level IO semaphore sized from
  `GeneralSettings.maxConcurrentFanOutCalls` (default 15) bounds
  total in-flight pair coroutines. Permits are pre-acquired on the
  coroutine side and the `ProviderThrottle.permitPreAcquired`
  thread-local stops the inline interceptor from double-counting.

- A 429 with a Retry-After hint ≥ 1 h calls
  `ModelCooldownStore.markUnavailable`; the engine skips the benched
  (provider, model) for the rest of the run. See
  [cooldowns.md](cooldowns.md).

- Hydration is lazy: `engine.hydrate(context, reportId)` re-reads
  every `SecondaryResult` row for the report and rebuilds the
  in-memory map via `toPairState` / `toCombinedReportState`. HTTP +
  save is still delegated to
  `ReportViewModel.executeSecondaryTask` so the persistence layer
  stays one canonical writer.

### Fan-out screens (L1 / L2 / L3 + Icons)

The drill-in lives in
[`FanOutScreen.kt`](../ai/src/main/java/com/ai/ui/report/FanOutScreen.kt)
(parent + nav) plus `FanOutL1Screen.kt`, `FanOutL2Screen.kt`,
`FanOutL3Screen.kt`, and the icons-mode siblings in
`FanOutIconsScreens.kt`. A `FanOutNav` sealed-class state in
`rememberSaveable` survives rotation; every level subscribes to
`engine.runs.collectAsState()`.

A `FanOutMode` enum (`MAIN` vs `ICONS`) flips the screen tree
between standard fan-out and **fan-icons mode** — a parallel
3-tier emoji chain across every completed pair. Same screen
files, separate budget (`maxConcurrentFanIconsCalls`),
separate Costs row tagged `fan-icons`, and a **Clear fan-icons**
button wipes icon state without deleting pairs. When the user opens
a report mid-batch the icons batch auto-resumes on screen entry.

- **L1** — pinned stats panel as a grid: **Total / Running /
  Queued / Throttled / Done / Errors / Costs**, with the row
  background acting as a progress bar replacing the legacy
  "6 / 9" line. Stats counters reconcile by construction
  (Queued + Running + Done + Errors = Total). Action buttons live
  on a single row — "Show icons" flips into ICONS mode, the
  one-page view button drills into the per-pair flat list. The
  Running stat goes yellow → green on activity; Queued pink/brown
  by mode; Throttled always shows even at zero. Per-mode costs
  show in the list header rather than a separate banner.
- **L2** — one row per (answerer, source) pair, virtualised. Two
  role flavours via a Switch role button: **Responder mode** (drill
  into one answerer) vs **Source mode** (drill into one source). In
  ICONS mode the row is a focused icon list — big glyphs, the
  per-tier hit count in the stats panel.
- **L3** — single-pair detail. Subject is the answerer or source
  model name (HARDCODED green subject row); the ℹ️ / 🐞 icons
  follow the "other" model in the pair so a single tap goes to the
  right Model Info / trace; the trailing divider sits tight
  against the response.

### Fan-in

Fan-in still runs through `ReportViewModel.runFanInPrompt`. It runs
the chosen `category="fan_in"` Internal Prompt **once per source
agent** (NOT once per answerer × source pair). The
`***Report*** @REPORT@@RESPONSES@` iterable block is matched
whitespace-tolerantly and expanded once per source agent, with
`@RESPONSES@` populated by every fan-out response for that source.
Output rows carry `fanInOf = <metaPromptId>` so the drill-in
distinguishes them from per-pair rows.

Model-scoped fan-in (categories `initiator` / `requester` / `model`)
produces per-(provider, model) rows that the L2 page surfaces under
each model's own bucket distinct from the legacy total `fan_in`.

### Recovery + delete

`resumeStaleFanOutPairs` re-reads each row before stamping
"Interrupted" — a cold launch in the middle of a run recovers
genuinely-stuck placeholders without losing in-flight work.
`rerunCompleteFanOut` / `rerunFailedFanOutPairs` dedupe in-flight
job keys (so one tap doesn't fork two batches) and cancel before
resetting. Fan-out delete blocks on a "Deleting Fan Out" popup
while the engine cancels every per-pair Job before the disk rows
go.

## HTML export

After the main report sections, the view-picker offers one tab per
content type, in this order (when present):

1. **Reports** — agents in either One-by-one or All-together layout.
2. **One tab per chat-type Meta prompt name** — one tab per unique
   `metaPromptName` on the report. The tab label IS the user-given
   name. Multiple Meta prompts on the same report each get their own
   tab; tab order matches first-seen chronological order.
3. **Reranks** — each entry rendered as a linked rank table
   (`Rank | Result | Score | Reason`). The Result column is a
   `<a href="#result-N">[N]</a>` link back to that agent's card.
4. **Moderations** — flagged-categories table, one row per source
   response with category pills coloured by severity.
5. **Prompt / Costs / JSON** — original prompt, per-call cost
   table (with By type and By model rollup tables), captured
   API traces (Original-only).

`[N]` references inside chat-type Meta content are linkified back
to the corresponding agent anchor.

## Zipped HTML export

The Zipped HTML export builds a self-contained site with one
directory per content type. Inside each language directory:

```
original/
  index.html
  Reports/
    index.html
    01_provider_model.html
    …
  <metaPromptName>/      ← one folder per chat-type Meta prompt name
    index.html
    01_provider_model.html
    …
  Reranks/, Moderations/  (Original only — structured JSON)
  Prompt/, Costs/, JSON/  (Original only)
```

The directory name is the `metaPromptName` filtered through the
filesystem-safe regex (so a name like "Pro/Con" becomes "Pro_Con").
Trace-link lookup keys off `"Report meta: <name>"` — the same
category tag `runMetaPrompt` writes — so each row's 🐞 link
points at the right captured API trace.

## Concurrency and gating

- The Meta / Translate / Fan-out / Rerank buttons are **disabled**
  while the parent report is still streaming (the button row only
  shows on the result-phase UI, after `isComplete = true`).
- chat-type Meta / Rerank / Moderation / Translate are gated by
  the per-provider throttle (`ProviderThrottle` — defaults 30
  calls / min, 3 concurrent per provider host). Across hosts every
  flow goes through one of the six `ApiCallCaps` ceilings
  (`global` / `report` / `translation` / `fanOut` / `fanIcons`,
  plus the standalone `maxTestApiCalls` for the model-test sweep).
  See [throttle.md](throttle.md).
- Fan-out pre-acquires the per-provider permit on the coroutine
  side so the UI can distinguish queued vs running rows; the
  inline interceptor skips its own acquire via the
  `ProviderThrottle.permitPreAcquired` context element.
- Multiple chat-type Meta batches AND multiple Translate batches
  can be in flight concurrently — each batch has its own
  `metaPromptId` (chat-type) or `translationRunId` (translate) and
  its rows surface independently in the UI.
- Per-(report, prompt-name) last-selection is persisted under
  `secondary_last_<promptId>_<reportId>` in `eval_prefs` so the
  next click on the same Meta button pre-checks the same models.
- runFanOutPrompt / runFanInPrompt **dedupes against an in-flight
  job key** so a fast double-tap doesn't fork two batches.

## Native rerank / moderation endpoints

Providers that declare `nativeRerankUrl` (Cohere `/v2/rerank`)
take rerank dispatches through `callRerankApi` instead of building
a chat prompt. The provider's `(index, relevance_score)` array is
re-shaped into the same `[{id, rank, score, reason}, ...]` JSON
the chat-rerank flow produces — score rescaled to 0-100 — so the
rest of the pipeline (HTML export, Top-Ranked scope, anchor links)
needs no second code path. `RerankApiResult.billedSearchUnits`
captures Cohere's per-call billing units.

Providers that declare `nativeModerationUrl` (Mistral
`/v1/moderations`) take moderation dispatches through
`callModerationApi`. The structured per-input result list is
re-encoded into the `[{id, flagged, categories, scores}, ...]`
JSON the detail screen parses. Mistral's response includes
`prompt_tokens` / `completion_tokens` / `total_tokens` on the
moderation call; `callNativeModeration` lifts these into
`TokenUsage` so cost attribution matches chat-driven Meta runs.

Both fall through with an explanatory error when the provider
doesn't declare the URL — the user is told which provider to pick
instead.

## Adding a fifth kind

The `when (kind)` blocks throughout the codebase are exhaustive, so
the Kotlin compiler will list every site you need to touch. See
[development.md](development.md) → "A new SecondaryKind". For most
new behaviour you don't need a new kind — adding an Internal Prompt
in a new category covers any new chat-type analysis without code
changes.
