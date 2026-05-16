# Architecture

## At a glance

A single-Activity Android app written in Kotlin + Jetpack Compose, MVVM
with three view models on top of `StateFlow`. Networking goes through
Retrofit + OkHttp (with custom interceptors for tracing and rate-limit
retry). Persistence is split between `SharedPreferences` (user-curated
config, caches) and JSON files under `filesDir` (reports, secondary
results, traces, chat history, knowledge bases, embeddings, usage
stats, pricing tier blobs).

```
┌─────────────────────────────────────────────────────────────────────┐
│  MainActivity                                                       │
│  └── AppNavHost  (Jetpack Navigation)                               │
│       ├── HubScreen                                                 │
│       ├── ReportsHubScreen / ReportScreen / ReportSingleResultScreen│
│       ├── ChatsHubScreen / ChatScreens / DualChatScreen             │
│       ├── KnowledgeListScreen / KnowledgeDetailScreen               │
│       ├── ModelInfoScreen / ModelListScreen                         │
│       ├── SearchScreens (Quick / Extended local + Local / Remote    │
│       │                  semantic)                                  │
│       ├── ShareChooserScreen   (overlay before NavHost)             │
│       ├── SettingsScreen (two-tier: enum-driven sub-screens)        │
│       ├── HousekeepingScreen   (full screens per NavCard row)       │
│       ├── SecondaryResultsScreen + FanOutScreen / FanOutL1/L2/L3    │
│       │                            FanOutIconsScreens (icons mode)  │
│       ├── ModelTestScreen + ModelTestSelectScreen +                 │
│       │   ModelTestL1/L2/L3Screen   (Housekeeping → Test)           │
│       ├── TranslationCompareScreen / TranslationL1/L2/L3 / …        │
│       └── HelpScreen / TraceScreen                                  │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ViewModels                                                         │
│  ├── AppViewModel       — settings, prefs, model fetching, RAG attach│
│  ├── ChatViewModel      — chat state and streaming, KB injection    │
│  ├── ReportViewModel    — report + secondary-result generation,     │
│  │                        multi-language fan-out, translation runs, │
│  │                        Fan-in (combine), delegates Fan-out exec  │
│  │                        to FanOutEngine                           │
│  ├── FanOutEngine       — canonical Map<FanOutRunKey, FanOutRunState>│
│  │                        StateFlow; per-pair Job map for safe      │
│  │                        cancel; fan-icons batch + MAIN/ICONS modes│
│  └── ModelTestEngine    — "Test all models" runner; throttled-keys  │
│                           live StateFlow drives the L1 readout      │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Data layer (com.ai.data)                                           │
│  ├── AnalysisRepository  — façade with retry / fallback / RAG inject│
│  ├── ApiDispatch         — selects ApiFormat-specific code path     │
│  ├── ApiStreaming        — SSE parser + Flow emission               │
│  ├── ApiClient           — Retrofit interfaces, ApiFactory          │
│  ├── ApiTracer           — OkHttp interceptor + JSON file storage   │
│  │                         + in-memory metadata cache               │
│  │                         + thread-local (reportId, category) tags │
│  │                         + NetworkSettings + ProviderThrottle     │
│  │                         + 429 / 529 retry + read-timeout         │
│  │                           interceptors + ApiCallCaps semaphores  │
│  ├── AppLog              — log4j-style file appender + redaction    │
│  ├── AtomicFileWrite     — fsync + ATOMIC_MOVE atomic writeText     │
│  ├── EmojiExtract        — grapheme-cluster emoji isolation         │
│  ├── ProviderRegistry    — runtime registry of AppService instances │
│  ├── ProviderFieldTimestamps — per-provider per-field user-edit ts  │
│  ├── PricingCache        — seven-tier pricing + capability lookup   │
│  │                         (tier blobs in filesDir/pricing/)        │
│  ├── ModelCooldownStore  — auto-bench on long-Retry-After 429s      │
│  ├── ReportStorage       — per-report JSON file persistence         │
│  ├── SecondaryResultStorage — RERANK / META / MODERATION /          │
│  │                            TRANSLATE persistence                 │
│  ├── ChatHistoryManager  — chat session persistence                 │
│  ├── HuggingFaceCache    — HF model-info cache                      │
│  ├── BackupManager       — zip-based backup/restore                 │
│  ├── ModelListCache      — model-list TTL bookkeeping               │
│  ├── PromptCache         — per-prompt cached responses              │
│  ├── FanOutRunModel      — PairState / CombinedReportState /        │
│  │                         FanOutRunState (immutable run snapshot)  │
│  ├── ModelTestRunModel   — ModelTestState / TestStatus /            │
│  │                         ModelTestRunState                        │
│  ├── ModelTestRunStore   — <filesDir>/test_run.json single-doc      │
│  ├── InternalPromptSeed  — assets/prompts.json loader               │
│  ├── ExamplePromptSeed   — assets/examples.json loader              │
│  ├── InaccessibleSeed    — assets/inaccessible.json delta-merge     │
│  ├── TestExcludedSeed    — assets/excluded.json delta-merge         │
│  ├── InternalPromptIconCache — per-Meta-prompt emoji cache          │
│  ├── ImageAttach         — vision-image downscale + JPEG-encode     │
│  │                                                                  │
│  │ — RAG —                                                          │
│  ├── Knowledge           — KnowledgeBase, KnowledgeSource,          │
│  │                         KnowledgeChunk, KnowledgeStore           │
│  ├── KnowledgeExtractors — 10 source-type extractors                │
│  ├── KnowledgeService    — index + retrieve pipeline                │
│  ├── EmbeddingsStore     — content-hashed per-doc embedding cache   │
│  │                                                                  │
│  │ — On-device runtime (data/local/) —                              │
│  ├── LocalLlm            — MediaPipe Tasks GenAI .task runtime      │
│  ├── LocalEmbedder       — MediaPipe Tasks TextEmbedder runtime     │
│  │                                                                  │
│  │ — Share-target —                                                 │
│  └── SharedContent       — snapshot of an ACTION_SEND payload       │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
            ┌──────────────────────────────────────────┐
            │  External APIs (42 cloud providers)      │
            │  + 7 metadata repositories               │
            │  + on-device MediaPipe Tasks (no network)│
            └──────────────────────────────────────────┘
```

## Codebase shape

~73,900 LOC across 150 Kotlin files:
- `data/` — 41 files (HTTP, dispatch, streaming, tracer, rate
  limit / throttle, registry, pricing, cooldowns, storage, RAG,
  in-app file logger, atomic-write helpers, bundled-asset seeds),
  including the `data/local/` subpackage (`LocalLlm`, `LocalEmbedder`).
- `model/` — 2 files (`SettingsModels.kt`, `SettingsHolder.kt`)
- `viewmodel/` — 6 files (`AppViewModel`, `ChatViewModel`,
  `ReportViewModel`, `ReportViewModelHelpers`, `FanOutEngine`,
  `ModelTestEngine`)
- `ui/` — 100 files across 13 sub-domains (`hub`, `report` × 37
  including the FanOut L1/L2/L3 / FanOutIcons / ModelTest L1/L2/L3 /
  Translation L1/L2/L3 / Find-alternative-icons screens,
  `chat` × 5, `knowledge`, `models`, `search` × 4, `history` × 3,
  `settings` × 17, `admin` × 11 (incl. AppLog), `share`, `shared`,
  `theme`, `navigation` × 2)
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

In addition there is a **synthetic `Local` `AppService`** —
`AppService.LOCAL`, id `"Local"` — that is **not** registered in
`ProviderRegistry`. It surfaces only via `findById("Local")` (which
also accepts the legacy uppercase `"LOCAL"` case-insensitively for
compat with persisted ChatSessions) and is the routing sentinel for
the on-device runtime: if `agent.provider == LOCAL` the dispatch
layer skips Retrofit entirely and calls `LocalLlm.generate` (or
`LocalEmbedder.embed` for embeddings). It appears in every model
picker as a normal "Local" provider.

### Five ViewModels

- **`AppViewModel`** — owns `UiState` (a single bag of every
  UI-relevant field) and `Settings`. Handles bootstrap, model-list
  refresh, external + share intents, and persistence. Other view
  models delegate to it for shared state. Mutators that race the
  UI (provider state flips on Refresh All, agent test flock
  population) use a CAS-style `compareAndSet` pattern so two
  fan-out updates don't overwrite each other. Also owns the
  in-memory `iconFanOutByReport: Map<reportId,
  List<IconCandidate>>` map that `FindAlternativeIconsScreens`
  consumes, and the `iconRefreshTick` counter on `UiState` that
  forces icon-dependent recompositions when a background icon
  call settles. `GeneralSettings` now carries the six concurrency
  ceilings — `maxConcurrentApiCalls` (50), `maxConcurrentReportCalls`
  (15), `maxConcurrentTranslationCalls` (15),
  `maxConcurrentFanOutCalls` (15), `maxConcurrentFanIconsCalls` (15),
  `maxTestApiCalls` (40) — mirrored into `ApiCallCaps` semaphores on
  every Settings update.
- **`ChatViewModel`** — chat session state and streaming, including
  per-turn KB retrieval and context-block injection. Also fires
  the bundled `internal/chat_title` prompt asynchronously after
  the first assistant response and stamps `ChatSession.title`
  with the returned label.
- **`ReportViewModel`** — report generation, secondary-result flows
  (RERANK / META / MODERATION / TRANSLATE), the multi-language
  fan-out for chat-type META and TRANSLATE, **Fan-in** (combine),
  and the per-agent 3-tier report-icon chain. Holds an in-memory
  `_agentResults` flow separate from `UiState` so per-task
  completions don't ripple equality checks across the rest of the
  UiState. Holds a `Map<String, TranslationRun>` keyed by runId so
  multiple concurrent Translate batches don't overwrite each other.
  Long-running flows (initial generate, regenerate, secondary
  launches, report-icon chain) are launched on
  `appViewModel.viewModelScope` rather than the report VM's own
  scope so navigating away from the result screen doesn't cancel
  the work — `_agentResults` and `Report.*` storage keep the
  background results addressable when the screen recomposes
  back. Pure helpers live in `ReportViewModelHelpers.kt`
  (`providerHost`, `executeSecondaryTask`, etc.).
- **`FanOutEngine`** — owns the authoritative
  `_runs: MutableStateFlow<Map<FanOutRunKey, FanOutRunState>>`
  for every active fan-out run. Each per-pair transition is a single
  atomic `_runs.update { … }` call, so subscribers see exactly one
  value per transition with no flicker. Per-pair `Job` map
  (`fanOutPairJobs`) registered with `CoroutineStart.LAZY` BEFORE
  `start()` so a destructive path can `cancelAndJoin` a specific
  pair before deleting its disk row. Sizes its in-flight IO
  semaphore from `maxConcurrentFanOutCalls`, layered on top of the
  per-host `ProviderThrottle`. Hosts the **fan-icons** batch (a
  parallel emoji chain across every completed pair) — same shape,
  separate budget via `maxConcurrentFanIconsCalls`. Hydrates lazily
  from disk via `hydrate(context, reportId)` and delegates HTTP +
  save to `ReportViewModel.executeSecondaryTask`.
- **`ModelTestEngine`** — "Test all models" runner. Exposes a single
  `StateFlow<ModelTestRunState?>` keyed off `<filesDir>/test_run.json`
  and a sibling `throttledKeys: StateFlow<Set<String>>` so the L1
  page can show which (provider, model) pairs are currently blocked
  in a `ProviderThrottle.acquire`. Auto-feeds the **Blocked**,
  **Test-excluded**, and **Inaccessible** lists from probe outcomes
  (provider-side "Unable to access non-serverless" routes to
  `inaccessibleModels`, model errors above the user threshold land
  in `blockedModels`, cost > 5¢ + manual entries are
  `testExcludedModels`).

### Two-tier navigation

Top-level navigation uses Jetpack Navigation. Inside `SettingsScreen`,
sub-screens are routed via the `SettingsSubScreen` enum (~32 entries
covering AI Setup hubs, providers, models, model-types, agents,
flocks, swarms, parameters, system prompts, internal-prompt hubs by
category, example prompts, external services, local LiteRT models,
local LLMs, import/export, refresh) and a `when` block — this keeps
deep links into a single Settings overlay simple and lets
back-navigation be a single state mutation. The top-level Settings
screen itself is split into three sub-pages (Preferences, Privacy
& backup, Logging) and the Reset screen is split into five
dedicated sub-pages — so the user lands on a short list rather
than a wall of cards.

### TitleBar + green subject row

The top bar is a fixed three-section anatomy:

- **Left** — the **AI** logo (vivid Material A700 blue, alpha 0.75 so
  the bar reads through it; tap → Hub) and the **❓ Help** icon
  (tap → screen's help topic).
- **Centre** — the dynamic report icon (when in scope), centred
  between the left button group and the right title.
- **Right** — the hardcoded screen title (top-aligned).

Directly below the top bar, every screen routes its dynamic subject
through the shared `HardcodedSubjectRow` composable
(`ui/shared/SharedComponents.kt`) — a single green-coloured 26 sp
SemiBold line, clickable into Model Info when a `(provider, model)`
is supplied, with an optional trailing slot (Fan-out L3 uses it for
the answerer / source role indicator). Self-gates on blank text and
applies a `(-8).dp` offset so it sits tight against the title bar.
The unification commit (`3143dbc7`) routed every former bespoke
subject line through this one composable.

Action icons (💬 Chat, ℹ️ Info, 📋 Copy, 📤 Share, 🔄 Refresh,
🗑 Delete, 🐞 Trace, 📝 Memo) live in a **`BottomIconBar`** pinned
at the bottom of the screen. Each screen's `TitleBar` publishes its
callbacks into `LocalBottomIconState`; the global bar at
AppNavHost scope renders them and survives nav transitions.
Inactive icons hide; the model-response swipe-nav (left = next,
right = previous) is wired on top.

Two master switches drive icon generation:

- `iconGenEnabled` (default true) — kicks off the per-report
  `internal/icon` call on every new report. Its result populates
  `Report.icon`; result page, AI Reports hub, history rows, and
  the title bar's leftmost icon all key off this. Toggling it
  off hides the icon row and the 📝 memo it mirrors; existing
  icons stay on disk for re-enable.
- `perModelIconGenEnabled` (default true) — auto-fires the
  per-agent 3-tier chain (`runReportIconsForAgent`) whenever an
  agent's primary call settles to SUCCESS — both on initial
  generation and on regenerate. Toggling it off skips the chain
  but leaves any persisted per-agent icons in place.

See [report-icons.md](report-icons.md) for the full flow.

### Layered lookups

Two of the most important data flows are layered in fixed order:

- **Pricing** for `(provider, model)`, in `PricingCache.getPricing`:
  provider self-report (OpenRouter when caller is OpenRouter,
  Together when caller is Together) → manual override → LiteLLM →
  models.dev → llm-prices → Artificial Analysis → OpenRouter
  cross-provider fallback → Helicone → default. The large tier
  blobs live as files under `filesDir/pricing/` (one per tier);
  only timestamps and the small manual-override map stay in
  `pricing_cache.xml`. Manual user overrides win over every
  curated source — putting them after LiteLLM would silently
  ignore corrections users add specifically because LiteLLM has
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

### RAG layer

`com.ai.data.Knowledge*` implements the retrieval-augmented
generation pipeline:

- **Knowledge bases** live under `<filesDir>/knowledge/<kbId>/` with
  a `manifest.json` (the `KnowledgeBase` + `KnowledgeSource[]` list)
  and a `chunks/<sourceId>.json` per source.
- **Sources** are extracted by `KnowledgeExtractors` — ten types:
  TEXT, MARKDOWN, PDF (with OCR fallback for image-only PDFs), DOCX,
  ODT, XLSX (sheets spooled — no full-zip slurp), ODS (content.xml
  streamed), CSV, IMAGE, URL.
- **Chunking** is paragraph-greedy with overlap (`KnowledgeChunker`).
- **Embeddings** go through either `LocalEmbedder` (when
  `embedderProviderId == "Local"`) or `AnalysisRepository.embed`
  (any provider's `/v1/embeddings`). Per-content cache lives in
  `EmbeddingsStore`. Chunks with empty / mismatched-dim embeddings
  are refused at save time rather than scored as 0 at retrieval.
- **Retrieval** is cosine-similarity top-K across every chunk in
  every attached KB. The query is embedded once and converted to
  `FloatArray`; chunks are streamed per source via
  `KnowledgeStore.forEachChunk` into a bounded
  `PriorityQueue<Scored>` of size `topK*2`, so peak heap is the
  heap itself plus one chunk in flight, regardless of total KB
  size. Survivors are sorted descending and walked under the
  `maxContextChars` budget. The prompt or user message gets a
  `<context>…</context>` block prepended at dispatch time.

See [knowledge.md](knowledge.md) for the full pipeline.

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

### On-device runtime

Two singletons wrap MediaPipe Tasks:

- **`LocalLlm`** holds an `LlmInference` cache keyed by `.task` file
  name. `availableLlms()` lists `<filesDir>/local_llms/*.task`.
  `generate()` runs synchronously and writes a synthetic
  `ApiTrace` (hostname `local`, url
  `local://generate/<modelFile>`).
- **`LocalEmbedder`** holds a `TextEmbedder` cache keyed by
  `.tflite` file name. `availableModels()` lists
  `<filesDir>/local_models/*.tflite` plus the curated
  `downloadable` list. `embed()` writes a similar synthetic trace.

Both are reachable from any provider-agnostic code path because
they share the `AppService.LOCAL` sentinel (id `"Local"`) — chat,
report, RAG, and Fan-out flows route to them when the provider id
matches. Per-native-handle calls are serialised with a per-handle
mutex so two concurrent users of the same `.task` don't race the
shared MediaPipe context. See [local-runtime.md](local-runtime.md).

### Share-target

`MainActivity` extracts incoming `ACTION_SEND` /
`ACTION_SEND_MULTIPLE` intents into a `SharedContent` snapshot
(text + subject + URI list + mime). `AppNavHost` renders
`ShareChooserScreen` as an **overlay before the NavHost** and
routes the user's pick to one of three destinations: Report
(routeShareToReport pre-fills title/prompt + base64s a single
image), Chat (stages `chatStarterText` in `UiState`), or Knowledge
(queues URIs in `UiState.pendingKnowledgeUris` and the Knowledge
list / detail screens drain the queue). See
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

### Fan-out / Fan-in / Fan-icons

Fan-out execution is owned by the **`FanOutEngine`** view model
(`viewmodel/FanOutEngine.kt`). Each pair runs the chosen
`category="fan_out"` Internal Prompt once per (answerer × source)
pair, with `@RESPONSE@` in the template replaced by the source
agent's response body. Fan-in is still routed through
`ReportViewModel.runFanInPrompt` and combines the per-pair rows
back into a single combined-report row using a different prompt
template (`category="fan_in"`) with the iterable
`***Report*** @REPORT@@RESPONSES@` block.

The engine publishes a single
`StateFlow<Map<FanOutRunKey, FanOutRunState>>` and mutates it via
atomic `update { … }` calls; subscribers see exactly one value per
pair transition with no flicker (no polling loop, no
`recentlySettled` grace window).

`FanOutScreen` (`ui/report/FanOutScreen.kt`) is the L1 parent with
a `FanOutMode` enum (`MAIN` vs `ICONS`); each level is its own file:

- **L1** (`FanOutL1Screen.kt`) — pinned stats panel (Total,
  Running, Queued, Throttled, Done, Errors, plus per-mode Costs),
  row per answerer with an in-row progress bar replacing the
  legacy "6 / 9" line. Action buttons collapse under one row;
  "Show icons" flips into ICONS mode.
- **L2** (`FanOutL2Screen.kt`) — one row per (answerer, source)
  pair, virtualised. Two role flavours via a Switch role button:
  Responder mode (drill into one answerer) vs Source mode (drill
  into one source).
- **L3** (`FanOutL3Screen.kt`) — single-pair detail. The ℹ️ / 🐞
  icons follow the "other" model in the pair; the trailing divider
  sits tight against the response.

**Fan-icons mode** runs a parallel 3-tier emoji chain across every
completed pair. Same screen file structure
(`FanOutIconsScreens.kt`), separate semaphore from
`maxConcurrentFanIconsCalls`, separate Costs row tagged
`fan-icons`, and a **Clear fan-icons** button wipes icon state
without deleting pairs. When the user opens a report mid-batch the
icons batch auto-resumes on screen entry.

Concurrency on Fan-out is layered: the per-host `ProviderThrottle`
caps (default 30 / min × 3 concurrent per hostname) plus an
engine-level IO semaphore sized from `maxConcurrentFanOutCalls`
(default 15). 429s with a long Retry-After ≥ 1 h bench the model in
[`ModelCooldownStore`](cooldowns.md) — the engine then skips it for
the rest of the run.

## Concurrency

- Network calls happen on `Dispatchers.IO`.
- Per-provider rate + concurrency caps are enforced by
  `ProviderThrottle` (one `Semaphore` + one sliding-window
  `Deque` per hostname). Limits hold across overlapping flows
  (report + meta + fan-out + chat on the same provider). Caps come
  from per-provider override → `NetworkSettings` global default
  (defaults: 30 calls/min, 3 concurrent). User-tunable from
  Settings → Network and per provider. See
  [throttle.md](throttle.md).
- Six **per-kind concurrency ceilings** live on `ApiCallCaps`
  (mirrored from `GeneralSettings` on every Settings update):
  `global` (50), `report` (15), `translation` (15), `fanOut` (15),
  `fanIcons` (15), and the test sweep's standalone
  `maxTestApiCalls` (40, read directly by `ModelTestEngine`).
  Each request `withPermit`-wraps through the relevant per-kind
  semaphore before going through the per-host
  `ProviderThrottle` — a fan-out launched against six providers
  with 3 in-flight each adds up to 18 in-flight calls, but the
  fan-out cap clamps that to `fanOut` total across hosts.
- A 429 whose Retry-After hint exceeds 1 h (Google's
  "quota exhausted", Cohere's monthly cap, per-day token quotas
  reset at Pacific midnight) routes through
  `ModelCooldownStore.markUnavailable(providerId, model,
  availableAtMs, traceFile)`; pickers dim the row and engines skip
  it. See [cooldowns.md](cooldowns.md).
- `FanOutEngine`, the fan-icons batch, the per-agent
  report-icon chain, and the Find-alternative-icons fan-out all
  pre-acquire permits on the coroutine side and set
  `ProviderThrottle.permitPreAcquired` so the OkHttp interceptor
  doesn't double-count.
- `ApiTracer` and `ReportStorage` use `ReentrantLock` for thread-safe
  file writes; `KnowledgeStore` does the same for KB manifest +
  chunk files (chunks + manifest are also written atomically as a
  batch so a crash mid-write doesn't leave the manifest pointing
  at half a chunk file).
- `AtomicFileWrite.writeTextAtomic` uses `Files.move(ATOMIC_MOVE)`
  with an `fsync` of the temp file before the rename, and creates
  the parent dir on demand so call sites don't have to. The
  same stage-as-`.part` + atomic-rename pattern is used by the
  export-share writer, the local-model import path, and several
  other "write a complete artifact" call sites.
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
  `saveChatSession`, `saveSecondaryResult`, KB
  `saveSource` / `deleteSource`. Knowledge writes also enforce a
  canonical-containment guard around `kbId` path joins — a
  symlink or `..` segment can't escape `<filesDir>/knowledge/`.
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
HTTP status, instead of leaving the stream half-consumed. The
`Local` provider doesn't stream — `LocalLlm.generate` returns the
full text in one go (the MediaPipe API doesn't expose a partial-
token callback this version plumbs through).

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
3. `rememberSaveable` on key UI state (e.g. AI Usage's expanded
   provider list, Cross drill-in scope buckets per
   `(report, prompt)`) survives navigation away and back. Chat's
   staged `userInput` and `attachedImage` are preserved across
   process recreation; Dual Chat conversations persist across
   rotation / process recreation.
4. The Report Result screen recovers stale placeholders on entry,
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

Two seed sources are **delta-merged on every app start**, not
just on fresh install — both via
`InternalPromptSeed.delta(...) {}` / equivalent that only adds
missing entries by `(category, name)` for prompts and by stable
id for providers:

- `assets/providers.json` — entries import on first run, and
  new entries are appended on subsequent starts.
  Per-field timestamps in `ProviderFieldTimestamps` decide
  which fields the every-start sync may overwrite — a field the
  user has edited (timestamp non-null) is left alone; an
  un-edited field tracks the asset.
- `assets/prompts.json` — Internal Prompts (Meta / Fan-out /
  Fan-in / fixed Internal templates: intro / model_info /
  translate / rerank / moderation / icon / report_icon /
  report_icon_chat / report_icon_3th / chat_title / response /
  …) seeded into `Settings.internalPrompts`. Same delta-merge
  rule — new bundled entries appear on the next start, user
  edits to existing entries survive.

`assets/examples.json` is also delta-merged on every start
(Example Prompts merged by title case-insensitively). The
Housekeeping → Reset → "Restore bundled assets" path force-
merges any missing rows back without resetting user-edited
ones.

None of the three asset files overwrite existing rows on the
delta path, so a re-seed never destroys user edits.
