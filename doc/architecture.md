# Architecture

## At a glance

A single-Activity Android app written in Kotlin + Jetpack Compose, MVVM
with three view models on top of `StateFlow`. Networking goes through
Retrofit + OkHttp (with custom interceptors for tracing and rate-limit
retry). Persistence is split between `SharedPreferences` (user-curated
config, caches) and JSON files under `filesDir` (reports, traces,
chat history, usage stats).

```
┌─────────────────────────────────────────────────────────────────────┐
│  MainActivity                                                       │
│  └── AppNavHost  (Jetpack Navigation)                               │
│       ├── HubScreen                                                 │
│       ├── ReportsScreen / ReportsViewerScreen                       │
│       ├── ChatScreen / ChatHistory / DualChatScreen                 │
│       ├── ModelInfoScreen / ModelListScreen                         │
│       ├── SettingsScreen (two-tier: enum-driven sub-screens)        │
│       ├── HousekeepingScreen / StatisticsScreen                     │
│       └── HelpScreen / TraceScreen                                  │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ViewModels                                                         │
│  ├── AppViewModel       — settings, prefs, model fetching           │
│  ├── ChatViewModel      — chat state and streaming                  │
│  └── ReportViewModel    — report + secondary-result generation      │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Data layer (com.ai.data)                                           │
│  ├── AnalysisRepository  — façade with retry / fallback logic       │
│  ├── ApiDispatch         — selects ApiFormat-specific code path     │
│  ├── ApiStreaming        — SSE parser + Flow emission               │
│  ├── ApiClient           — Retrofit interfaces, ApiFactory          │
│  ├── ApiTracer           — OkHttp interceptor + JSON file storage   │
│  ├── ProviderRegistry    — runtime registry of AppService instances │
│  ├── PricingCache        — six-tier pricing + capability lookup     │
│  ├── ReportStorage       — per-report JSON file persistence         │
│  ├── SecondaryResultStorage — rerank/summarize/compare persistence  │
│  ├── ChatHistoryManager  — chat session persistence                 │
│  ├── HuggingFaceCache    — HF model-info cache                      │
│  └── BackupManager       — zip-based backup/restore                 │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
            ┌──────────────────────────────────────────┐
            │  External APIs (38 providers)            │
            │  + 7 metadata repositories (see          │
            │  repositories.md)                        │
            └──────────────────────────────────────────┘
```

## Key concepts

### `AppService` and `ApiFormat`

Every provider is an `AppService` with an `apiFormat` field — one of
`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. Dispatch always keys off
the format, never off provider identity, so 36 of 38 default providers
share unified code paths. Adding an OpenAI-compatible provider is a
one-line entry in `setup.json` (see [development.md](development.md)).

`AppService.entries` returns the live list from `ProviderRegistry`,
which loads `setup.json` at first run and then merges any custom
provider definitions the user imports.

### Three ViewModels

- **`AppViewModel`** — owns `UiState` (a single bag of every UI-relevant
  field) and `Settings`. Handles bootstrap, model-list refresh,
  external intents, and persistence. Other view models delegate to it
  for shared state.
- **`ChatViewModel`** — chat session state and streaming.
- **`ReportViewModel`** — report generation and the rerank / summarize /
  compare meta-result flow. Holds an in-memory `_agentResults` flow
  separate from `UiState` so per-task completions don't ripple
  equality checks across the rest of the UiState.

### Two-tier navigation

Top-level navigation uses Jetpack Navigation. Inside `SettingsScreen`,
sub-screens are routed via the `SettingsSubScreen` enum and a `when`
block — this keeps deep links into a single Settings overlay simple
and lets back-navigation be a single state mutation.

### Layered lookups

Two of the most important data flows are layered in fixed order:

- **Pricing** for `(provider, model)`:
  manual override → LiteLLM → models.dev → Helicone → llm-prices →
  Artificial Analysis → OpenRouter → default.
- **Capabilities** (`isVisionCapable`, `isWebSearchCapable`):
  user override (per-provider visionModels / webSearchModels) →
  manual ModelTypeOverride → provider's own `/models` capabilities →
  LiteLLM → models.dev → naming heuristic.

Both are precomputed into `ProviderConfig.visionCapableComputed`,
`webSearchCapableComputed`, and `modelPricing` after a refresh, so the
hot path on list-render screens is a `Set` membership check rather
than the full layered scan.

### Generic CRUD list

The `CrudListScreen<T>` composable backs every list-of-things screen
(Agents, Flocks, Swarms, Parameters, Internal Prompts, System Prompts).
Each consumer plugs in `itemTitle`, `itemSubtitle`, `onAdd`, `onEdit`,
`onDelete` and the rest is shared.

### Full-screen overlay pattern

Many flows (model picker, scope picker, viewer screens, edit screens)
follow:

```kotlin
if (showOverlay) { OverlayScreen(...); return }
```

The `return` inside `@Composable` preserves the parent's `remember`
state, so backing out of the overlay leaves the parent's local state
intact — a UX the user has explicitly relied on.

### Two-step Summarize / Compare scope

When a parent report has at least one rerank, Summarize and Compare
route through `SecondaryScopeScreen` first, where the user can narrow
the input set to the top-N entries of a chosen rerank. Rerank itself
always runs on the full set. See
[secondary-results.md](secondary-results.md) for the full flow.

## Concurrency

- Network calls happen on `Dispatchers.IO`. `AnalysisRepository.analyzeWithAgent`
  runs each report agent concurrently up to `REPORT_CONCURRENCY_LIMIT = 4`,
  controlled with a `Semaphore`.
- `ApiTracer` and `ReportStorage` use `ReentrantLock` for thread-safe
  file writes.
- `usageStatsCache` is a `ConcurrentHashMap` with a 2-second debounced
  flush, so heavy concurrent updates don't serialize on disk I/O.
- `RateLimitRetryInterceptor` retries 429s up to five times with a
  3-second back-off; it has an explicit main-thread guard so it can
  never ANR the UI.

## Streaming

Server-Sent Events flow through `ApiStreaming`, parsed into
`Flow<String>` chunks. Each `ApiFormat` has its own SSE parser
(OpenAI's `data: {...}\n\n` framing, Anthropic's `event:` + `data:`
pairs, Gemini's chunked-JSON format).

## State recovery

Two recovery mechanisms keep the app robust to process death:

1. `restoreCompletedReport` and `hydrateAgentResultsFromStorage` rebuild
   the in-memory `_agentResults` flow from `ReportStorage` files when
   the user comes back to a finished report whose StateFlow was lost.
2. `rememberSaveable` on key UI state (e.g. AI Usage's expanded
   provider list) survives navigation away and back.

## Auto-restart

After a "Refresh all" run on the Refresh screen, the app restarts itself
to pick up freshly-persisted caches without forcing the user to swipe
the app away:

```kotlin
val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
launch?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
context.startActivity(launch)
Runtime.getRuntime().exit(0)
```
