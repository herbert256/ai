# Architecture

## At a glance

A single-Activity Android app written in Kotlin + Jetpack Compose, MVVM
with three view models on top of `StateFlow`. Networking goes through
Retrofit + OkHttp (with custom interceptors for tracing and rate-limit
retry). Persistence is split between `SharedPreferences` (user-curated
config, caches) and JSON files under `filesDir` (reports, secondary
results, traces, chat history, knowledge bases, embeddings,
usage stats).

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
│       ├── HousekeepingScreen / StatisticsScreen                     │
│       ├── SecondaryResultsScreen / TranslationCompareScreen / …     │
│       └── HelpScreen / TraceScreen                                  │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ViewModels                                                         │
│  ├── AppViewModel       — settings, prefs, model fetching, RAG attach│
│  ├── ChatViewModel      — chat state and streaming, KB injection    │
│  └── ReportViewModel    — report + secondary-result generation,     │
│                           multi-language fan-out, translation runs  │
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
│  ├── ProviderRegistry    — runtime registry of AppService instances │
│  ├── PricingCache        — seven-tier pricing + capability lookup   │
│  │                         (tier blobs in filesDir/pricing/)        │
│  ├── ReportStorage       — per-report JSON file persistence         │
│  ├── SecondaryResultStorage — rerank/summarize/compare/moderate/    │
│  │                            translate persistence                 │
│  ├── ChatHistoryManager  — chat session persistence                 │
│  ├── HuggingFaceCache    — HF model-info cache                      │
│  ├── BackupManager       — zip-based backup/restore                 │
│  ├── ModelListCache      — model-list TTL bookkeeping               │
│  ├── PromptCache         — per-prompt cached responses              │
│  │                                                                  │
│  │ — RAG —                                                          │
│  ├── Knowledge           — KnowledgeBase, KnowledgeSource,          │
│  │                         KnowledgeChunk, KnowledgeStore           │
│  ├── KnowledgeExtractors — 10 source-type extractors                │
│  ├── KnowledgeService    — index + retrieve pipeline                │
│  ├── EmbeddingsStore     — content-hashed per-doc embedding cache   │
│  │                                                                  │
│  │ — On-device runtime —                                            │
│  ├── LocalLlm            — MediaPipe Tasks GenAI .task runtime      │
│  ├── LocalEmbedder       — MediaPipe Tasks TextEmbedder runtime     │
│  │                                                                  │
│  │ — Share-target —                                                 │
│  └── SharedContent       — snapshot of an ACTION_SEND payload       │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
            ┌──────────────────────────────────────────┐
            │  External APIs (39 cloud providers)      │
            │  + 7 metadata repositories               │
            │  + on-device MediaPipe Tasks (no network)│
            └──────────────────────────────────────────┘
```

## Key concepts

### `AppService` and `ApiFormat`

Every cloud provider is an `AppService` with an `apiFormat` field —
one of `OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. Dispatch always
keys off the format, never off provider identity, so 37 of 39 default
providers share unified code paths. Adding an OpenAI-compatible
provider is a one-line entry in `setup.json` (see
[development.md](development.md)).

`AppService.entries` returns the live list from `ProviderRegistry`,
which loads `setup.json` at first run and then merges any custom
provider definitions the user imports.

In addition there is a **synthetic `LOCAL` `AppService`** —
`AppService.LOCAL`, id `"LOCAL"` — that is **not** registered in
`ProviderRegistry`. It surfaces only via `findById("LOCAL")` and is
the routing sentinel for the on-device runtime: if `agent.provider ==
LOCAL` the dispatch layer skips Retrofit entirely and calls
`LocalLlm.generate` (or `LocalEmbedder.embed` for embeddings). It
appears in every model picker as a normal "Local" provider so the
user sees the on-device path next to cloud providers without bespoke
buttons.

### Three ViewModels

- **`AppViewModel`** — owns `UiState` (a single bag of every
  UI-relevant field) and `Settings`. Handles bootstrap, model-list
  refresh, external + share intents, and persistence. Other view
  models delegate to it for shared state.
- **`ChatViewModel`** — chat session state and streaming, including
  per-turn KB retrieval and context-block injection.
- **`ReportViewModel`** — report generation, secondary-result flows
  (RERANK / META / MODERATION / TRANSLATE), and the multi-language
  fan-out for chat-type META and TRANSLATE. Holds an in-memory
  `_agentResults` flow separate from `UiState` so per-task
  completions don't ripple equality checks across the rest of the
  UiState. Holds a `Map<String, TranslationRun>` keyed by runId so
  multiple concurrent Translate batches don't overwrite each other.

### Two-tier navigation

Top-level navigation uses Jetpack Navigation. Inside `SettingsScreen`,
sub-screens are routed via the `SettingsSubScreen` enum and a `when`
block — this keeps deep links into a single Settings overlay simple
and lets back-navigation be a single state mutation.

### Layered lookups

Two of the most important data flows are layered in fixed order:

- **Pricing** for `(provider, model)`:
  provider-self-report (OpenRouter when the caller is OpenRouter,
  Together AI native when the caller is Together) → LiteLLM →
  models.dev → llm-prices → Artificial Analysis → manual override
  → OpenRouter cross-provider fallback → Helicone → default. The
  large tier blobs live as files under `filesDir/pricing/` (one
  per tier); only timestamps and the small manual-override map
  stay in `pricing_cache.xml`. `ensureLoaded` short-circuits on
  the main thread before the preload completes — UI callers get
  `DEFAULT_PRICING` during the cold window and pick up real values
  on the next state-driven recompose, instead of blocking Compose
  on the synchronized 1.2 MB LiteLLM parse.
- **Capabilities** (`isVisionCapable`, `isWebSearchCapable`):
  user override (per-provider visionModels / webSearchModels) →
  manual ModelTypeOverride → provider's own `/models` capabilities →
  LiteLLM → models.dev → naming heuristic.

Both are precomputed into `ProviderConfig.visionCapableComputed`,
`webSearchCapableComputed`, and `modelPricing` after a refresh, so the
hot path on list-render screens is a `Set` membership check rather
than the full layered scan.

### RAG layer

`com.ai.data.Knowledge*` implements the retrieval-augmented
generation pipeline:

- **Knowledge bases** live under `<filesDir>/knowledge/<kbId>/` with
  a `manifest.json` (the `KnowledgeBase` + `KnowledgeSource[]` list)
  and a `chunks/<sourceId>.json` per source.
- **Sources** are extracted by `KnowledgeExtractors` — ten types:
  TEXT, MARKDOWN, PDF (with OCR fallback for image-only PDFs), DOCX,
  ODT, XLSX, ODS, CSV, IMAGE, URL.
- **Chunking** is paragraph-greedy with overlap (`KnowledgeChunker`).
- **Embeddings** go through either `LocalEmbedder` (when
  `embedderProviderId == "LOCAL"`) or `AnalysisRepository.embed`
  (any provider's `/v1/embeddings`). Per-content cache lives in
  `EmbeddingsStore`.
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
Two optimisations keep the screen responsive on a populated trace
dir:

- A streaming-parse helper (`parseTraceFileInfoStreaming`) uses
  Gson's `JsonReader` to read only the seven `TraceFileInfo`
  fields, skipping the request body and stopping inside the
  response object once `statusCode` is captured. No reflective
  full-graph deserialise, no headers map allocation, no body
  string allocation.
- An in-memory `cachedTraceFiles` list is populated on the first
  `getTraceFiles()` call (off the UI thread, via the streaming
  parse) and kept in sync by `saveTrace` (re-sort after append),
  `clearTraces` (empty), and `deleteTracesOlderThan` (filter).
  Subsequent reads — including the Trace detail screen's prev /
  next nav — are O(1).

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
they share the `AppService.LOCAL` sentinel — chat, report, and RAG
flows route to them when the provider id is `"LOCAL"`. See
[local-runtime.md](local-runtime.md).

### Share-target

`MainActivity` extracts incoming `ACTION_SEND` /
`ACTION_SEND_MULTIPLE` intents into a `SharedContent` snapshot
(text + subject + URI list + mime). `AppNavHost` renders
`ShareChooserScreen` as an **overlay before the NavHost** and
routes the user's pick to one of three destinations: Report
(routeShareToReport pre-fills title/prompt + base64s a single image),
Chat (stages `chatStarterText` in `UiState`), or Knowledge (queues
URIs in `UiState.pendingKnowledgeUris` and the Knowledge list /
detail screens drain the queue). See
[share-target.md](share-target.md).

### Generic CRUD list

The `CrudListScreen<T>` composable backs every list-of-things screen
(Agents, Flocks, Swarms, Parameters, System Prompts). Each consumer
plugs in `itemTitle`, `itemSubtitle`, `onAdd`, `onEdit`, `onDelete`
and the rest is shared.

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
the scope screen and always run on the full set. See
[secondary-results.md](secondary-results.md) for the full flow.

## Concurrency

- Network calls happen on `Dispatchers.IO`.
  `AnalysisRepository.analyzeWithAgent` runs each report agent
  concurrently up to `REPORT_CONCURRENCY_LIMIT = 4`, controlled with
  a `Semaphore`. The same semaphore caps the parallel fan-out inside
  a chat-type Meta or Translate batch.
- `ApiTracer` and `ReportStorage` use `ReentrantLock` for thread-safe
  file writes; `KnowledgeStore` does the same for KB manifest +
  chunk files.
- `usageStatsCache` is a `ConcurrentHashMap` with a 2-second debounced
  flush, so heavy concurrent updates don't serialize on disk I/O.
- `RateLimitRetryInterceptor` retries 429s up to five times with a
  3-second back-off; it has an explicit main-thread guard so it can
  never ANR the UI.
- Multiple Translate batches can be in flight at once (one per
  `runId`); they share the report-concurrency semaphore but their
  results land in their own rows.

## Streaming

Server-Sent Events flow through `ApiStreaming`, parsed into
`Flow<String>` chunks. Each `ApiFormat` has its own SSE parser
(OpenAI's `data: {...}\n\n` framing, Anthropic's `event:` + `data:`
pairs, Gemini's chunked-JSON format). The `LOCAL` provider doesn't
stream — `LocalLlm.generate` returns the full text in one go (the
MediaPipe API doesn't expose a partial-token callback this version
plumbs through).

## State recovery

Two recovery mechanisms keep the app robust to process death:

1. `restoreCompletedReport` and `hydrateAgentResultsFromStorage`
   rebuild the in-memory `_agentResults` flow from `ReportStorage`
   files when the user comes back to a finished report whose
   StateFlow was lost.
2. `rememberSaveable` on key UI state (e.g. AI Usage's expanded
   provider list) survives navigation away and back.

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

The same restart gate applies after restoring a backup so the in-memory
state matches the freshly-restored on-disk state.
