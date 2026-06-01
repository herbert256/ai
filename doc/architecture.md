# Architecture

## At a glance

A single-Activity Android app written in Kotlin + Jetpack Compose, MVVM
with three view models on top of `StateFlow`. Networking goes through
Retrofit + OkHttp (with custom interceptors for tracing and rate-limit
retry). Persistence is split between `SharedPreferences` (user-curated
config, caches) and JSON files under `filesDir` (reports, secondary
results, traces, chat history, embeddings, usage stats, pricing
tier blobs).

```
┌─────────────────────────────────────────────────────────────────────┐
│  MainActivity                                                       │
│  └── AppNavHost  (Jetpack Navigation)                               │
│       ├── HubScreen                                                 │
│       ├── ReportsHubScreen / ReportScreen / ReportSingleResultScreen│
│       ├── ChatsHubScreen / ChatScreens / DualChatScreen             │
│       ├── ModelInfoScreen / ModelListScreen                         │
│       ├── SearchScreens (Quick / Extended local + Remote semantic)  │
│       ├── ShareChooserScreen   (overlay before NavHost)             │
│       ├── SettingsScreen (two-tier: enum-driven sub-screens)        │
│       ├── Monitor / Housekeeping hubs (icon cards + drill-ins)      │
│       ├── Secondary results: Meta / Fan-out / Tournament / Compare  │
│       └── HelpScreen / TraceScreen                                  │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ViewModels                                                         │
│  ├── AppViewModel       — settings, prefs, model fetching          │
│  ├── ChatViewModel      — chat state and streaming                  │
│  └── ReportViewModel    — report + secondary-result generation,     │
│                           multi-language fan-out, translation runs, │
│                           Fan-out/Fan-in + worker-judged batches    │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Data layer (com.ai.data)                                           │
│  ├── AnalysisRepository  — façade with retry / fallback            │
│  ├── ApiDispatch         — selects ApiFormat-specific code path     │
│  ├── ApiStreaming        — SSE parser + Flow emission               │
│  ├── ApiClient           — Retrofit interfaces, ApiFactory          │
│  ├── ApiTracer           — OkHttp interceptor + JSON file storage   │
│  │                         + in-memory metadata cache               │
│  │                         + thread-local (reportId, category) tags │
│  │                         + NetworkSettings + ProviderThrottle     │
│  │                         + 429 retry + read-timeout interceptors  │
│  ├── AppLog              — log4j-style file appender + redaction    │
│  ├── AtomicFileWrite     — fsync + ATOMIC_MOVE atomic writeText     │
│  ├── EmojiExtract        — grapheme-cluster emoji isolation         │
│  ├── ProviderRegistry    — runtime registry of AppService instances │
│  ├── ProviderFieldTimestamps — per-provider per-field user-edit ts  │
│  ├── PricingCache        — seven-tier pricing + capability lookup   │
│  │                         (tier blobs in filesDir/pricing/)        │
│  ├── ReportStorage       — per-report JSON file persistence         │
│  ├── SecondaryResultStorage — RERANK / META / MODERATION /          │
│  │                            TRANSLATE / TOURNAMENT / JUDGES /     │
│  │                            COMPARE persistence                   │
│  ├── ChatHistoryManager  — chat session persistence                 │
│  ├── HuggingFaceCache    — HF model-info cache                      │
│  ├── BackupManager       — zip-based backup/restore                 │
│  ├── ModelListCache      — model-list TTL bookkeeping               │
│  ├── PromptCache         — per-prompt cached responses              │
│  ├── InternalPromptSeed  — assets/internal-prompts/ loader               │
│  ├── ExamplePromptSeed   — assets/examples.json loader              │
│  ├── ImageAttach         — vision-image downscale + JPEG-encode     │
│  ├── EmbeddingsStore     — content-hashed per-doc embedding cache   │
│  │                                                                  │
│  │ — Share-target —                                                 │
│  └── SharedContent       — snapshot of an ACTION_SEND payload       │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
            ┌──────────────────────────────────────────┐
            │  External APIs (42 cloud providers)      │
            │  + 7 metadata repositories               │
            └──────────────────────────────────────────┘
```

## Codebase shape

~133,000 LOC across 355 Kotlin files:
- `data/` — 78 files (HTTP, dispatch, streaming, tracer, rate
  limit / throttle, registry, pricing, storage, in-app file
  logger, atomic-write helpers, bundled-asset seeds, RAG /
  Knowledge, on-device `local/` runtime, regenerate-batch,
  Tournament / Judge-the-judges / Compare run models).
- `model/` — 2 files (`SettingsModels.kt`, `SettingsHolder.kt`)
- `viewmodel/` — 19 files (`AppViewModel` + its extracted
  top-level types in `AppViewModelTypes.kt`, `ChatViewModel`,
  `ReportViewModel` + extracted engines/managers such as
  `RegenerateBatchEngine`, `SecondaryRunManager`,
  `IconGenerationManager`, `TournamentEngine`, `JudgeEvalEngine`,
  `CompareEngine`)
- `ui/` — 255 files across sub-domains (`report` × 87,
  `cruds` × 48, `admin` × 30, `settings` × 22, `shared` × 17,
  `helpers` × 16, `navigation` × 7, `other` × 6, `chat` × 5,
  `search` × 4, `hub` × 4, `history` × 3, `models` × 2,
  `share` × 2, `knowledge` × 1, `theme` × 1)
- `MainActivity.kt`

## Key concepts

### `AppService` and `ApiFormat`

Every cloud provider is an `AppService` with an `apiFormat` field —
one of `OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. Dispatch always
keys off the format, never off provider identity, so 40 of 42 default
providers share unified code paths. Adding an OpenAI-compatible
provider is a one-line entry in `assets/providers.json` (see
[development.md](development.md)).

The `AppService` runtime class carries far more than just the format
field — model-routing patterns
(`responsesApiPatterns`, `reasoningModelPatterns`,
`reasoningEffortAcceptPatterns`, `webSearchModelPatterns`,
`adaptiveThinkingPatterns`), native non-chat endpoints
(`nativeRerankUrl` for Cohere `/v2/rerank`, `nativeModerationUrl` for
Mistral `/v1/moderations`, `nativeCapabilityUrl` for Cohere-style
capability listings), pricing/list-fetch flags
(`pricingFromModelList`, `crossProviderModelList`,
`mergeHardcodedModels`, `externalReasoningSignalUntrusted`),
per-family `max_tokens` defaults (`maxTokensDefaults`), and one or
more `builtInEndpoints` (DeepSeek, Mistral, Z.AI all ship more than
one endpoint out of the box). The full field list is in
[datastructures.md](datastructures.md) under `AppService`.

The id-unification refactor collapsed three name-like fields
(`id` / `displayName` / `prefsKey`) into one. UI shows `id` directly;
SharedPreferences key prefixes use `id` directly (e.g.
`"OpenAI_api_key"`). Bundled values come from
`assets/providers.json`; user-added providers set their own. There is
**no longer** a separate `prefsKey` or `displayName` field on
`AppService`.

`AppService.entries` returns the live list from `ProviderRegistry`,
which loads `providers.json` on first run via `importFromAsset` and
then merges any custom provider definitions the user imports.

### Three ViewModels

- **`AppViewModel`** — owns `UiState` (a single bag of every
  UI-relevant field) and `Settings`. Handles bootstrap, model-list
  refresh, external + share intents, and persistence. Other view
  models delegate to it for shared state. Mutators that race the
  UI (provider state flips on Refresh All, agent test flock
  population) use a CAS-style `compareAndSet` pattern so two
  fan-out updates don't overwrite each other. Also owns the
  in-memory `iconFanOutByReport: Map<reportId,
  List<IconCandidate>>` map that `AlternativeIconsScreen`
  consumes, and the `iconRefreshTick` counter on `UiState` that
  forces icon-dependent recompositions when a background icon
  call settles.
- **`ChatViewModel`** — chat session state and streaming. Also fires
  the bundled `internal/chat-title` prompt asynchronously after
  the first assistant response and stamps `ChatSession.title`
  with the returned label.
- **`ReportViewModel`** — report generation, secondary-result flows
  (RERANK / META / MODERATION / TRANSLATE / TOURNAMENT / JUDGES /
  COMPARE), the multi-language fan-out for chat-type META and
  TRANSLATE, the Fan-out / Fan-in flow, **and** per-model report
  icons (derived from each model's title via the worker engine,
  `workers/model-icons`).
  Holds an in-memory `_agentResults` flow separate from
  `UiState` so per-task completions don't ripple equality checks
  across the rest of the UiState. Holds a `Map<String,
  TranslationRun>` keyed by runId so multiple concurrent Translate
  batches don't overwrite each other. A separate
  `_runningFanOutPairs: StateFlow<Set<String>>` carries the hot-
  mutating per-pair set so 5–15 Hz updates during a fan-out batch
  don't recompose every consumer that reads any other UiState
  field. Long-running flows (initial generate, regenerate,
  secondary launches, report-icon chain) are launched on
  `appViewModel.viewModelScope` rather than the report VM's own
  scope so navigating away from the result screen doesn't cancel
  the work — `_agentResults` and `Report.*` storage keep the
  background results addressable when the screen recomposes
  back. Pure helpers live in `ReportViewModelHelpers.kt`
  (`providerHost`, etc.).

### Two-tier navigation

Top-level navigation uses Jetpack Navigation. Inside `SettingsScreen`,
sub-screens are routed via the `SettingsSubScreen` enum (~32 entries
covering AI Setup hubs, providers, models, model-types, agents,
flocks, swarms, parameters, system prompts, internal-prompt hubs by
category, example prompts, external services, import/export, refresh)
and a `when` block — this keeps
deep links into a single Settings overlay simple and lets
back-navigation be a single state mutation. The top-level Settings
screen itself is split into three sub-pages (Preferences, Privacy
& backup, Logging) and the Reset screen is split into five
dedicated sub-pages — so the user lands on a short list rather
than a wall of cards.

### TitleBar action strip

Every screen's `TitleBar` is a standardised action strip — `< Back`
plus a context-specific subset from {Chat, Info, Copy, Share,
Refresh, Delete, Trace, Memo, Home, Help, …}. The glyphs are read
from `MetadataIcons` / `LocalMetadataIcons`, not hard-coded at the
call site, so Settings → Default icons can override them globally.
Inactive icons hide; Home and Help are always last. The `< Back`
button can be hidden via Settings (the system back / gesture back
still works).

The top title and subtitle colors are `AppColors.MainTitle` and
`AppColors.SubTitle`, both editable in Settings → UI Colors. The
overall app background is `AppColors.AppBackground`, which also
drives the Android system bars in `MainActivity`. See
[ui-customization.md](ui-customization.md) for the full color/icon
contract.

Two master switches drive icon generation:

- `iconGenEnabled` (default true) — kicks off the per-report
  `internal/icon` call on every new report. Its result populates
  `Report.icon`; result page, AI Reports hub, history rows, and
  the title bar's leftmost icon all key off this. Toggling it
  off hides the icon row and the 📝 memo it mirrors; existing
  icons stay on disk for re-enable.
- `perModelIconGenEnabled` (default true) — auto-derives each
  model's icon from its title via the worker engine
  (`workers/model-icons`) whenever an agent's primary call settles to
  SUCCESS — both on initial generation and on regenerate. Toggling it
  off skips that step but leaves any persisted per-model icons in place.

See [report-icons.md](report-icons.md) for the full flow.

### Worker-judged analysis batches

`TournamentEngine`, `JudgeEvalEngine`, and `CompareEngine` are
siblings of the secondary/fan-out engines. They use persisted
`SecondaryResult` rows as their source of truth but keep hot
running/waiting cell ids in dedicated `StateFlow`s so the whole
`UiState` tree does not recompose at batch speed.

- **Tournament** creates ordered head-to-head match rows and an
  aggregate ranking row. Ranking can be recomputed locally with
  Copeland / Elo / Davidson / Tideman / Markov.
- **Judge the judges** gives every judge in the Tournament swarm the
  same random answer pairs and computes agreement with consensus.
- **Compare with meta** scores each report answer against selected
  Meta rows using `meta_compare` worker prompts.

See [tournament-judges-compare.md](tournament-judges-compare.md).

### Layered lookups

Two of the most important data flows are layered in fixed order:

- **Pricing** for `(provider, model)`, in `PricingCache.getPricing`:
  provider-self-report (OpenRouter when caller is OpenRouter,
  Together when caller is Together) → manual override → LiteLLM →
  models.dev → llm-prices → Artificial Analysis → OpenRouter
  cross-provider fallback → Helicone → default. The large tier
  blobs live as files under `filesDir/pricing/` (one per tier);
  only timestamps and the small manual-override map stay in
  `pricing_cache.xml`. Manual user overrides win over every
  curated source — putting them after LITELLM would silently
  ignore corrections users add specifically because LITELLM has
  stale data. `ensureLoaded` short-circuits on the main thread
  before the preload completes — UI callers get `DEFAULT_PRICING`
  during the cold window and pick up real values on the next
  state-driven recompose, instead of blocking Compose on the
  synchronized 1.2 MB LiteLLM parse.
- **Capabilities** (`isVisionCapable`, `isWebSearchCapable`,
  `isReasoningCapable`):
  user override (per-provider visionModels / webSearchModels /
  reasoningModels) → manual ModelTypeOverride → provider's own
  `/models` capabilities → LiteLLM → models.dev → naming
  heuristic.

Both are precomputed into `ProviderConfig.visionCapableComputed`,
`webSearchCapableComputed`, `reasoningCapableComputed`, and
`modelPricing` after a refresh, so the hot path on list-render
screens is a `Set` membership check rather than the full layered
scan.

### Trace storage

`ApiTracer` writes one JSON file per outbound API call under
`<filesDir>/trace/<hostname>_<timestamp>_<seq>.json`. The Trace
list screen needs a `(hostname, timestamp, statusCode, reportId,
model, category)` summary per file but the file itself contains
the full request and response bodies — often tens of KB each.
Hardening / perf measures:

- A streaming-parse helper (`parseTraceFileInfoStreaming`) uses
  Gson's `JsonReader` to read only the seven `TraceFileInfo`
  fields, skipping the request body and stopping inside the
  response object once `statusCode` is captured. No reflective
  full-graph deserialise, no headers map allocation, no body
  string allocation.
- An in-memory `cachedTraceFiles` list is populated on `init`
  (off the UI thread, via `prewarmCache(viewModelScope)` from
  `AppViewModel.init`) and kept in sync by `saveTrace` (re-sort
  after append), `clearTraces` (empty), and
  `deleteTracesOlderThan` (filter). Subsequent reads — including
  the Trace detail screen's prev / next nav — are O(1).
- Trace bodies are capped at 8 MiB to prevent runaway memory on
  giant streaming responses.
- Auth headers are redacted at write time, not just on Copy /
  Share, so a leaked filesystem dump never carries plain keys.
- Trace tags `(reportId, category)` are propagated as
  thread-locals through OkHttp's dispatcher so retries and
  cancellations preserve the originating call's identity.

### Share-target

`MainActivity` extracts incoming `ACTION_SEND` /
`ACTION_SEND_MULTIPLE` intents into a `SharedContent` snapshot
(text + subject + URI list + mime). `AppNavHost` renders
`ShareChooserScreen` as an **overlay before the NavHost** and
routes the user's pick to one of two destinations: Report
(routeShareToReport pre-fills title/prompt + base64s a single
image) or Chat (stages `chatStarterText` in `UiState`). See
[share-target.md](share-target.md).

### Generic CRUD list

The `CrudListScreen<T>` composable backs every list-of-things screen
(Agents, Flocks, Swarms, Parameters, System Prompts, Internal
Prompts per category, Example Prompts). Each consumer plugs in
`itemTitle`, `itemSubtitle`, `onAdd`, `onEdit`, `onDelete` and the
rest is shared.

### Full-screen overlay pattern

Many flows (model picker, scope picker, viewer screens, edit screens)
follow:

```kotlin
if (showOverlay) { OverlayScreen(...); return }
```

The `return` inside `@Composable` preserves the parent's `remember`
state, so backing out of the overlay leaves the parent's local state
intact — a UX the user has explicitly relied on.

### Two-step Meta scope

Chat-type Meta runs (and Translate) route through
`SecondaryScopeScreen` first, where the user can narrow the input
set to the top-N entries of a chosen rerank, manually pick agents,
or (when translations exist) choose which present languages to fan
out across. Rerank-typed and Moderation-typed Meta prompts skip
the scope screen and always run on the full set. Each scope is
encoded onto the row at run time (`secondaryScope` field) so a
cascade-on-prompt-change re-runs at the same scope rather than
silently widening to AllReports. See
[secondary-results.md](secondary-results.md) for the full flow.

### Fan-out / Fan-in

A separate code path under `ReportViewModel.runFanOutPrompt` /
`runFanInPrompt`. Fan-out treats each successful agent's response
as a "source", and runs a configurable Internal Prompt
(`category = "fan_out"`) once per (answerer model × source agent)
pair. `@RESPONSE@` in the prompt template is replaced by the
source response text. Fan-in then combines those per-pair rows
back into a single combined-report row using a different prompt
template (`category = "fan_in"`) with the iterable
`***Report*** @REPORT@@RESPONSES@` block.

The drill-in is three levels deep:
- **Level 1** — one row per (answerer, prompt). Action buttons
  (Resume stale / Restart failed / Rerun complete / Delete) live
  in a collapsed Actions card. Empty-body successes count as
  Done, not Queued.
- **Level 2** — one row per (answerer, source) pair. OnePageView
  virtualisation keeps long lists scrolling smoothly.
- **Level 3** — single response detail with a 🐞 link to the
  original report-model trace.

Concurrency on Fan-out is capped by `ApiCallCaps.fanOut` plus the
shared per-provider throttle, so overlapping report / chat / meta /
fan-out traffic all respect the same host budgets. The hot per-pair
`runningFanOutPairs` flow is separate from `UiState`.

## Concurrency

- Network calls happen on `Dispatchers.IO`.
- Per-provider rate + concurrency caps are enforced by
  `ProviderThrottle` (one `Semaphore` + one sliding-window
  `Deque` per hostname). Replaces the prior per-batch fan-out
  semaphore — limits now hold across overlapping flows (report
  + meta + fan-out + chat on the same provider). Caps come from
  per-provider override → `NetworkSettings` global default
  (defaults: 30 calls/min, 3 concurrent). User-tunable from
  Settings → Network and per provider. See
  [throttle.md](throttle.md).
- Fan-out and the per-agent report-icon chain pre-acquire
  permits on the coroutine side and set
  `ProviderThrottle.permitPreAcquired` so the OkHttp
  interceptor doesn't double-count.
- `ApiTracer` and `ReportStorage` use `ReentrantLock` for thread-safe
  file writes.
- `AtomicFileWrite.writeTextAtomic` uses `Files.move(ATOMIC_MOVE)`
  with an `fsync` of the temp file before the rename, and creates
  the parent dir on demand so call sites don't have to. The
  same stage-as-`.part` + atomic-rename pattern is used by the
  export-share writer and several other "write a complete artifact"
  call sites.
- `usageStatsCache` is a `ConcurrentHashMap` with a 2-second debounced
  flush, so heavy concurrent updates don't serialize on disk I/O.
  The flush is forced from `ViewModel.onCleared` (off the main
  thread, on `NonCancellable`) so a Refresh-all auto-restart can't
  drop in-flight stats.
- `RateLimitRetryInterceptor` retries 429s with a configurable
  back-off (`NetworkSettings.maxRetriesOn429` ×
  `retryBackoffMs429`, both per-provider overridable), bails on
  coroutine cancellation, and has an explicit main-thread guard
  so it can never ANR the UI. `OverloadedRetryInterceptor` is the
  529 (server overloaded) sibling — independent budget
  (`maxRetriesOn529` × `retryBackoffMs529`), same shape.
  `withRetry` treats `408 / 425 / 429` as transient (in addition
  to network errors) and skips retries on permanent 4xx failures;
  5xx (including 529 after the in-line loop) is transient.
- Multiple Translate batches can be in flight at once (one per
  `runId`); they share the per-provider throttle but their
  results land in their own rows.
- Storage write APIs validate flat ids (`isSafeFlatId`: non-blank,
  not `.` / `..`, no `/` or `\`) on `saveReport`, `deleteReport`,
  `saveChatSession`, `saveSecondaryResult`.
- Backup restore caps per-entry and total bytes (large
  attachments truncated) before writing into `filesDir` /
  `cacheDir` from the zip.

## Streaming

Server-Sent Events flow through `ApiStreaming`, parsed into
`Flow<String>` chunks. Each `ApiFormat` has its own SSE parser
(OpenAI's `data: {...}\n\n` framing per the W3C spec — data lines
are buffered before the blank-line dispatch; Anthropic's `event:` +
`data:` pairs; Gemini's chunked-JSON format). Error responses on
streaming endpoints have their body drained and surfaced with the
HTTP status, instead of leaving the stream half-consumed.

## State recovery

Recovery mechanisms keep the app robust to process death:

1. `restoreCompletedReport` and `hydrateAgentResultsFromStorage`
   rebuild the in-memory `_agentResults` flow from `ReportStorage`
   files when the user comes back to a finished report whose
   StateFlow was lost.
2. `resumeStaleFanOutPairs` re-reads each row before stamping
   "Interrupted" so a cold-launch in the middle of a Fan-out
   doesn't lose progress — the placeholder is recovered and only
   genuinely-stuck pairs are flagged.
3. Tournament, Judge-the-judges, and Compare hydrate their newest
   run from `SecondaryResult` rows and mark only genuinely stale
   placeholders as interrupted after resume attempts.
4. `rememberSaveable` on key UI state (e.g. AI Usage's expanded
   provider list, Cross drill-in scope buckets per
   `(report, prompt)`) survives navigation away and back. Chat's
   staged `userInput` and `attachedImage` are preserved across
   process recreation; Dual Chat conversations persist across
   rotation / process recreation.
5. The Report Result screen recovers stale placeholders on entry,
   so the user never lands on a forever-spinning hourglass.

## In-app logging

`com.ai.data.AppLog` is a log4j-style file appender that
mirrors `android.util.Log` and writes every call at or above
`threshold` to `<filesDir>/applog/applog_<yyyyMMdd>.log`. Files
rotate daily; a single `BufferedWriter` is held open and
flushed per line so a process kill never loses the last few
lines. Sensitive headers (`Bearer …`, raw `sk-/xai-/gsk_` keys,
Google `?key=` params) are redacted inline before write. The
viewer (`AppLogScreen`) lives under Hub → AI App log and
supports search / level / time-range / tag filters with a
Copy/Share dialog. Threshold is set from Settings → Logging
(default INFO). See [applog.md](applog.md).

## Auto-restart

After a "Refresh all" run on the Refresh screen, the app restarts
itself to pick up freshly-persisted caches without forcing the user
to swipe the app away:

```kotlin
val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
launch?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
context.startActivity(launch)
Runtime.getRuntime().exit(0)
```

The same restart gate applies after restoring a backup so the
in-memory state matches the freshly-restored on-disk state, and
after the "Reset application" full reset.

## First-run seeding + every-start delta merge

`AppViewModel.bootstrap` runs through a sequence of structured
DEBUG / TRACE log lines (under the `AppLifecycle` tag) so the
AppLog viewer can render the entire start-up sequence for a
support session. The bootstrap log line itself captures the
app name, versionName, versionCode, and the BUILD_TIMESTAMP.

Several seed sources are **delta-merged on every app start**, not
just on fresh install — each via an `ensureAllPresent(...)` pass
that only adds missing entries (by `(category, name)` for
prompts, by stable id for providers) and never overwrites an
existing row. There is no longer a one-shot bundled-prompt
migration block in bootstrap; only the every-start delta-merges
remain.

- `assets/providers.json` — entries import on first run, and
  new entries are appended on subsequent starts (`syncFromAsset`
  + provider append). Per-field timestamps in
  `ProviderFieldTimestamps` decide which fields the every-start
  sync may overwrite — a field the user has edited (timestamp
  non-null) is left alone; an un-edited field tracks the asset.
- `assets/internal-prompts/` — Internal Prompts, organised into
  category sub-dirs: `meta/`, `fan_out/`, `fan_in/`, `workers/`
  (the generation prompts — `report-icon` / `model-icons` /
  `report-title` / `report-language` / `fan-meta` / `second-meta` /
  `second-rerank` / `second-moderation` / …), `alt/` (the
  Find-alternative variants), and `internal/` (fixed templates:
  chat-title / model-info / model-intro / translate-text / test-model /
  …) — seeded into `Settings.internalPrompts`. Same delta-merge
  rule — new bundled entries appear on the next start, user
  edits to existing entries survive.
- `assets/examples.json` — Example Prompts, merged by title
  (case-insensitive).
- `assets/system-prompts.json` — bundled System Prompts.
- `assets/excluded.json` / `assets/inaccessible.json` — appended
  test-excluded / inaccessible `(provider, model)` entries.
- `assets/meta.json` — default Meta items.

The Housekeeping → Reset → "Restore bundled assets" path force-
merges any missing rows back without resetting user-edited ones.

None of the asset files overwrite existing rows on the delta
path, so a re-seed never destroys user edits.
