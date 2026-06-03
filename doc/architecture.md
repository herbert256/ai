# Architecture

## At a glance

A single-Activity Android app written in Kotlin + Jetpack Compose, MVVM
on top of `StateFlow`. There is one real Android `ViewModel`
(`AppViewModel`); `ReportViewModel` and `ChatViewModel` are plain classes
that wrap it. Networking goes through Retrofit + OkHttp with a stack of
custom interceptors (tracing, per-host throttle, 429 / 529 retry,
per-call read timeout). Persistence is split between `SharedPreferences`
(user-curated config, catalog timestamps, small maps) and JSON files
under `filesDir` (reports, secondary results, traces, chat history,
embeddings, usage stats, pricing tier blobs, RAG knowledge bases).

> There is **no** Jetpack DataStore at runtime. The
> `androidx.datastore.preferences` dependency is declared but unused —
> persistence is exclusively `SharedPreferences` + JSON files. There is
> no `AppDataStore` class.

```
┌─────────────────────────────────────────────────────────────────────┐
│  MainActivity                                                       │
│  └── AppNavHost  (Jetpack Navigation Compose)                      │
│       ├── HubScreen                                                 │
│       ├── ReportsHubScreen / ReportScreen / ViewAiReportScreen      │
│       │     └── AnswerMatrixViewScreen  (overlay tile)              │
│       ├── ChatsHubScreen / ChatScreens / DualChatScreen             │
│       ├── ModelInfoScreen / ModelListScreen                         │
│       ├── SearchScreens (Quick / Extended local + Remote semantic)  │
│       ├── ShareChooserScreen   (overlay before NavHost)             │
│       ├── SettingsScreen (two-tier: enum-driven sub-screens)        │
│       ├── Monitor / Housekeeping hubs (icon cards + drill-ins)      │
│       ├── Secondary results: Meta / Fan-out / Tournament / Compare  │
│       └── HelpScreen / TraceScreen / DocumentationScreen            │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ViewModels (viewmodel/, 19 files)                                  │
│  ├── AppViewModel       — the only AndroidViewModel; owns UiState,  │
│  │                        Settings, prefs, model fetching, bootstrap│
│  ├── ChatViewModel      — plain class wrapping AppViewModel; chat   │
│  │                        state + streaming                         │
│  └── ReportViewModel    — plain class wrapping AppViewModel; report │
│       │                   + secondary-result generation, language   │
│       │                   fan-out, translation, Fan-out/Fan-in      │
│       └── extracted engines/managers (internal constructors):       │
│            RegenerateBatchEngine, SecondaryRunManager,              │
│            IconGenerationManager, FanOutEngine, TournamentEngine,   │
│            JudgeEvalEngine, CompareEngine, MetaEditManager,         │
│            ModelTestEngine, StressTestEngine, TranslationRunManager,│
│            WorkerRunner                                             │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Data layer (com.ai.data, 82 files)                                 │
│  ├── AnalysisRepository  — façade with withRetry / fallback        │
│  ├── ApiDispatch         — selects ApiFormat-specific code path     │
│  ├── ApiStreaming        — SSE parser + Flow emission               │
│  ├── ApiClient           — Retrofit interfaces, ApiFactory          │
│  ├── ApiTracer           — JSON trace files + in-memory metadata    │
│  │                         cache; also hosts NetworkSettings +      │
│  │                         ApiCallCaps                              │
│  ├── TracingInterceptor / RateLimitRetry / OverloadedRetry /        │
│  │     ProviderThrottling / ReadTimeout / TestCallTimeout /         │
│  │     TagPropagation  — the OkHttp interceptor stack               │
│  ├── AppLog              — log4j-style file appender + redaction    │
│  ├── AtomicFileWrite     — fsync + ATOMIC_MOVE atomic writeText     │
│  ├── EmojiExtract        — grapheme-cluster emoji isolation         │
│  ├── ProviderRegistry    — runtime registry of AppService instances │
│  ├── ProviderFieldTimestamps — per-provider per-field user-edit ts  │
│  ├── PricingCache        — ten-tier pricing + capability lookup     │
│  │                         (tier blobs in filesDir/pricing/)        │
│  ├── ReportStorage       — per-report JSON file persistence         │
│  ├── SecondaryResultStorage — RERANK / META / MODERATION /          │
│  │                            TRANSLATE / TOURNAMENT / JUDGES /     │
│  │                            COMPARE persistence                   │
│  ├── ChatHistoryManager  — chat session persistence                 │
│  ├── HuggingFaceCache    — HF model-info cache                      │
│  ├── BackupManager       — zip-based backup/restore                 │
│  ├── ModelListCache / PromptCache / EmbeddingsStore                 │
│  ├── Knowledge* / KnowledgeService / KnowledgeExtractors  — RAG     │
│  ├── local/  — LocalLlm, LocalEmbedder, LlmRuntime, LocalRuntime    │
│  ├── RegenerateBatch + TournamentRunModel / CompareRunModel        │
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

~141,000 LOC across 363 Kotlin files under `ai/src/main/java/com/ai`
(`namespace = "com.ai"`, `applicationId = "com.ai"`, `minSdk = 26`,
`targetSdk = 36`):

- **`data/` — 82 files.** 78 at the top level plus a nested
  `data/local/` (4 files: `LocalLlm`, `LocalEmbedder`, `LlmRuntime`,
  `LocalRuntime`). Covers HTTP/dispatch/streaming, the tracer +
  interceptor stack, the provider registry, pricing, all on-disk
  storage objects, the in-app file logger, atomic-write helpers,
  bundled-asset seeds, the RAG / Knowledge layer, the on-device
  runtime, and the regenerate-batch + Tournament / Judge / Compare run
  models.
- **`model/` — 2 files.** `SettingsModels.kt` (the settings data
  classes — `ProviderConfig`, `Agent`, `Flock`, `Swarm`,
  `SwarmMember`, `Parameters`, `SystemPrompt`, `BlockedModel`, …) and
  `SettingsHolder.kt` (`object SettingsHolder`).
- **`viewmodel/` — 19 files.** `AppViewModel` + `AppViewModelTypes.kt`
  (the `GeneralSettings` data class and UI enums), `ChatViewModel`,
  `ReportViewModel` + `ReportViewModelHelpers.kt`, the extracted
  engines (`CompareEngine`, `FanOutEngine`, `JudgeEvalEngine`,
  `ModelTestEngine`, `RegenerateBatchEngine`, `StressTestEngine`,
  `TournamentEngine`, `MetaEditManager`), the managers/runners
  (`SecondaryRunManager`, `IconGenerationManager`,
  `TranslationRunManager`, `WorkerRunner`), and support/type files
  (`ThrottledBatch.kt`, `TranslationTypes.kt`).
- **`ui/` — 259 files** across sub-domains (`report` × 88,
  `cruds` × 48, `admin` × 33, `settings` × 22, `shared` × 17,
  `helpers` × 16, `navigation` × 7, `other` × 6, `chat` × 5,
  `search` × 4, `hub` × 4, `history` × 3, `models` × 2, `share` × 2,
  `knowledge` × 1, `theme` × 1). No files sit directly at the `ui/`
  root. `report/` is fully nested: `report/manage` × 56,
  `report/view` × 22, `report/start` × 7, `report/other` × 2,
  `report/info` × 1.
- **`MainActivity.kt`** — the single entry file at the `com/ai` root.

## Entry point

`MainActivity` (`class MainActivity : ComponentActivity()`) is the only
Activity / Application class in the codebase. In `onCreate` it:

1. calls `CrashReporter.init(applicationContext)` **before anything
   else**, so an early-startup crash still lands in
   `<filesDir>/crash/last-crash.txt`;
2. calls `enableEdgeToEdge()`;
3. handles a share intent via `handleIntent(intent)` **only when
   `savedInstanceState == null`**, so a rotation doesn't re-import the
   shared payload;
4. sets content to `AppNavHost(...)` wrapped in `AppTheme`.

## Navigation: two systems

Navigation is two independent systems.

### 1. Top-level routes — Jetpack Navigation Compose

`NavRoutes` (`ui/navigation/NavRoutes.kt`) holds ~150 route constants as
String templates plus builder helpers (`aiReportInfo()`,
`traceDetail()`, `helpForTopic()`, …) that URL-encode their args.
`AppNavHost.kt` declares the single `NavHost`, but it does **not**
register every composable inline. Route registration is split across
five `NavGraphBuilder` extension functions, each in its own file under
`ui/navigation/` and all invoked from `AppNavHost`:

| Extension function | File | Domain |
|---|---|---|
| `reportRoutes` | `ReportRoutes.kt` | reports, view, manage |
| `settingsAdminRoutes` | `SettingsAdminRoutes.kt` | settings, monitor, housekeeping |
| `knowledgeSearchRoutes` | `KnowledgeSearchRoutes.kt` | RAG, semantic search |
| `developerRoutes` | `DeveloperRoutes.kt` | trace, about, documentation |
| `chatRoutes` | `ChatRoutes.kt` | chat, dual chat |

Each takes `(navController, appViewModel, reportViewModel,
chatViewModel, safePopBack, navigateHome)`. The `ui/navigation/` folder
is exactly these seven files: `AppNavHost.kt`, `NavRoutes.kt`, and the
five route-group files.

### 2. Settings sub-screens — the `SettingsSubScreen` enum

Inside `SettingsScreen.kt`, sub-screens are routed via the
`enum class SettingsSubScreen` (~48 values: `MAIN`, `AI_SETUP`,
`AI_PROVIDERS`, `AI_PROVIDER_EDIT`, `AI_MODELS_SETUP`, `AI_AGENTS`,
`AI_FLOCKS`, `AI_SWARMS`, `AI_PARAMETERS`, `AI_SYSTEM_PROMPTS`,
`AI_INTERNAL_PROMPTS`, the `*_SETUP` hubs, the model-state lists,
`AI_IMPORT_EXPORT`, the `SETTINGS_*` preference pages, …). The current
sub-screen is held in
`var currentSubScreen by rememberSaveable { mutableStateOf(...) }`, and
one large `when (currentSubScreen)` block both renders the sub-screen
and implements hierarchical back navigation (a `BackHandler` walks each
child back to its setup parent, setup parents back to `AI_SETUP`).
This keeps deep links into a single Settings overlay simple and lets
back-navigation be a single state mutation.

## Key concepts

### `AppService` and `ApiFormat`

Every cloud provider is an `AppService` with an `apiFormat` field —
one of `OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE` (the enum has exactly
these three values). Dispatch always keys off the format, never off
provider identity, so **40 of the 42 bundled providers share unified
code paths**; only the single `Anthropic` provider (`ANTHROPIC`) and the
single `Google` provider (`GOOGLE`) have format-specific branches.
Adding an OpenAI-compatible provider is one entry in
`assets/providers.json` (see [development.md](development.md)).

> The inline comment in `ApiFormat.kt` that says "28 providers using
> OpenAI-compatible" is stale — the real count is 40.

`AppService` is a plain `class`, **not** a Kotlin `data class`: its
`equals` / `hashCode` / `toString` are **id-only** (two `AppService`s
are equal iff their `id`s match). It hand-writes a `copy(...)` funnel
covering all of its fields so newly added fields can't be silently
dropped. Beyond the format field it carries:

- model-routing patterns — `responsesApiPatterns`,
  `reasoningModelPatterns`, `reasoningEffortAcceptPatterns`,
  `webSearchModelPatterns`, `adaptiveThinkingPatterns` (lists of
  `ModelPattern`, matched against the lowercased model id);
- native non-chat endpoints — `nativeRerankUrl` (SiliconFlow, Cohere),
  `nativeModerationUrl` (Mistral), `nativeCapabilityUrl`;
- pricing / list-fetch flags — `pricingFromModelList` (Together),
  `crossProviderModelList` (OpenRouter), `mergeHardcodedModels`,
  `externalReasoningSignalUntrusted`, `extractApiCost`,
  `costTicksDivisor` (xAI = 1e10);
- per-family `max_tokens` defaults (`maxTokensDefaults`, a list of
  `MaxTokensRule`) and one or more `builtInEndpoints` (`Endpoint`s);
  OpenAI, DeepSeek, Mistral, and Z.AI ship more than one endpoint;
- per-provider throttle overrides
  (`maxCallsPerProviderPerMinute`, `maxConcurrentCallsPerProvider`,
  `maxRetriesOn429`, …; `null` = inherit the global default).

The full field list is in [datastructures.md](datastructures.md).

The id-unification refactor collapsed three former name-like fields
(`id` / `displayName` / `prefsKey`) into one. `AppService.id` is now the
stable id **and** the UI label **and** the SharedPreferences key prefix
(e.g. `"OpenAI_api_key"`). There is no longer a separate `prefsKey` or
`displayName` field.

`AppService.entries` returns the live list from `ProviderRegistry`.
`findById(id)` special-cases the synthetic `LOCAL` sentinel
(`AppService.LOCAL`, `id = "Local"`) before delegating, and
`AppServiceAdapter` (the Gson serializer) writes an `AppService` as its
`id` string and reads it back through `findById`, so a persisted "Local"
chat session round-trips.

### `ProviderRegistry` — the 42 providers are an asset, not Kotlin

The 42 cloud providers are **not** hardcoded in Kotlin. They are JSON
entries in the bundled asset `assets/providers.json` (a top-level
`{ "providers": [...] }` with 42 entries). `ProviderRegistry` is a
mutable `object` that starts **empty** on a fresh install; the providers
load on demand from the asset via `importFromAsset(context,
"providers.json")` and persist to the `provider_registry`
SharedPreferences file.

JSON maps to `AppService` via `ProviderDefinition`, whose `apiFormat`
String is parsed by `ApiFormat.valueOf(...)` inside a `try/catch` that
falls back to `OPENAI_COMPATIBLE` on any invalid value; malformed
entries (null/blank `id` or `baseUrl`) are filtered out and logged, not
crashed. Notable registry API: `getAll`, `findById`, `add`, `update`
(bumps per-field `ProviderFieldTimestamps`), `remove`,
`importFromAsset` (append-only), `upsertFromJson`, `syncFromAsset`
(refreshes only un-edited fields, never appends), and `findByHost(host)`
— which resolves a request hostname to its `AppService` via a `hostIndex`
rebuilt from `baseUrl` + `auxHosts` on every `save()`, used by
`ProviderThrottle` for per-provider rate/concurrency overrides.

The 42 ids (each case-sensitive, doubling as the UI label):
OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity,
Together, OpenRouter, SiliconFlow, Z.AI, Moonshot, Cohere, AI21,
DashScope, Fireworks, Cerebras, SambaNova, Baichuan, StepFun, MiniMax,
NVIDIA, Replicate, HuggingFace, Lambda, Lepton, 01.AI, Doubao, Reka,
Writer, CloudflareWorkersAI, DeepInfra, Hyperbolic, Novita.ai,
Featherless.ai, LiquidAI, LlamaAPI, Krutrim, NebiusAIStudio, Chutes,
Inference.net.

### One real ViewModel, two wrappers

- **`AppViewModel`** (`class AppViewModel(application) :
  AndroidViewModel(application)`) — the **only** androidx ViewModel.
  Owns `UiState` (a single bag of every UI-relevant field) and
  `Settings`. Handles bootstrap, model-list refresh, external + share
  intents, and persistence. Mutators that race the UI (provider state
  flips on Refresh All, agent-test flock population) use a CAS-style
  `updateUiState { it.copy(...) }` pattern so two fan-out updates don't
  overwrite each other. Also owns the in-memory icon-fan-out maps
  (`iconFanOutByReport`, `agentIconFanOutByAgent`, `pairIconFanOutByPair`)
  consumed by the alternative-icon pickers, the `iconRefreshTick`
  counter on `UiState`, the hot running/throttled cell-id sets for the
  worker-judged batches, and the `backgroundResumeSweepJob`.

- **`ChatViewModel`** (`class ChatViewModel(private val appViewModel:
  AppViewModel)`) — a plain class, **not** an androidx ViewModel. Chat
  session state and streaming (`sendChatMessageStream`,
  `sendDualChatMessage`). Routes `provider.id == AppService.LOCAL.id`
  chats to `LocalLlm.generate` (`sendLocalLlmStream`); prepends RAG
  context via `messagesWithRag` when knowledge bases are attached. The
  bundled `internal/chat-title` prompt that names a session is fired
  separately by the chat screen (`kickOffChatTitleGeneration` in
  `ChatScreens.kt`), not by this class.

- **`ReportViewModel`** (`class ReportViewModel(private val
  appViewModel: AppViewModel)`) — also a plain wrapper. Report
  generation, the secondary-result flows (RERANK / META / MODERATION /
  TRANSLATE / TOURNAMENT / JUDGES / COMPARE), the multi-language
  fan-out for chat-type META and TRANSLATE, the Fan-out / Fan-in flow,
  **and** per-model report icons (derived from each model's title via
  the worker engine, `workers/model-icons`). It holds an in-memory
  `_agentResults` flow separate from `UiState` so per-task completions
  don't ripple equality checks across the rest of `UiState`, and
  delegates the heavy lifting to the extracted engines
  (`fanOutEngine`, `tournamentEngine`, `judgeEvalEngine`,
  `secondaryRunManager`, `regenerateBatchEngine`, `translation`, …).
  Long-running flows (initial generate, regenerate, secondary launches,
  the report-icon work) are launched on `appViewModel.viewModelScope`
  rather than the report VM's own scope, so navigating away from the
  result screen doesn't cancel the work — `_agentResults` and `Report.*`
  storage keep the background results addressable when the screen
  recomposes back. Pure helpers (`providerHost`, `resolveSystemPromptText`,
  `acquireThrottledPermits`, `interleaveByHost`, …) live in
  `ReportViewModelHelpers.kt`.

### TitleBar action strip

Every screen's `TitleBar` is a standardised action strip — `< Back`
plus a context-specific subset from {Chat, Info, Copy, Share, Refresh,
Delete, Trace, Memo, Home, Help, …}. The glyphs are read from
`MetadataIcons` / `LocalMetadataIcons`, not hard-coded at the call site,
so Settings → Default icons can override them globally. Inactive icons
hide; Home and Help are always last. The `< Back` button can be hidden
via Settings (the system / gesture back still works).

The top title and subtitle colors are `AppColors.MainTitle` and
`AppColors.SubTitle`, both editable in Settings → UI Colors. The overall
app background is `AppColors.AppBackground`, which also drives the
Android system bars in `MainActivity`. See
[ui-customization.md](ui-customization.md) for the full color/icon
contract.

Two master switches drive icon generation:

- `iconGenEnabled` (default true) — kicks off the per-report
  `workers/report-icon` call on every new report; the result populates
  `Report.icon`. Toggling it off skips generation while existing icons
  stay on disk for re-enable.
- `perModelIconGenEnabled` (default true) — auto-derives each model's
  icon from its **title** via the worker engine (`workers/model-icons`)
  whenever an agent's primary call settles to SUCCESS, on both initial
  generation and regenerate. The legacy response-based 3-tier per-agent
  icon fallback chain has been **removed**; `iconWinningTier` on
  `ReportAgent` is now always null. See [report-icons.md](report-icons.md).

### Answer matrix view

`AnswerMatrixViewScreen` (`ui/report/view/AnswerMatrix.kt`) is a
read-only "Answer matrix" view reached from a `doc:Matrix` tile on the
report View grid (between the Reports and Costs tiles), opened as a
full-screen overlay (`if (matrixViewOpen) { …; return }`). It renders a
horizontally-scrollable table — one row per SUCCESS agent — with
columns # / Model / Rank / Stance / Confidence / Recommendation / Risks /
Cost / Latency / Tokens, sorted by rerank rank then ordinal.

It performs **no new API calls and adds no storage**: rerank rank/score
come from the latest `RERANK` `SecondaryResult` row, and Stance /
Confidence / Recommendation / Risks are **regex-mined from the response
body** (`extractMatrixSignals`), not model-reported. It respects the
selected view language via a `translationByTarget` map and reuses the
existing `view_ai_report` help topic.

### Worker-judged analysis batches

`TournamentEngine`, `JudgeEvalEngine`, and `CompareEngine` are siblings
of the secondary / fan-out engines. They use persisted `SecondaryResult`
rows as their source of truth but keep hot running/waiting cell ids in
dedicated `StateFlow`s on `AppViewModel` so the whole `UiState` tree
does not recompose at batch speed.

- **Tournament** creates `N(N-1)` ordered head-to-head match rows (each
  unordered pair judged twice to cancel position bias) plus one
  aggregate ranking row. Ranking is recomputed locally — no API calls —
  with Copeland / Elo / Davidson / Tideman / Markov from a stored win
  matrix. The Copeland win-rate denominator is **per-model contested
  games** (opponents the model actually played), not a fixed `n-1`.
- **Judge the judges** gives every concrete judge in the Tournament
  swarm the same random answer pairs and computes each judge's
  agreement with consensus.
- **Compare with meta** scores each report answer against selected Meta
  rows using `meta_compare` worker prompts.

See [tournament-judges-compare.md](tournament-judges-compare.md).

### Layered lookups

Two of the most important data flows are layered in fixed order.

- **Pricing** for `(provider, model)`, in `PricingCache.getPricing`
  (first hit wins):
  OpenRouter-self (when caller `provider.crossProviderModelList`) →
  Together-self (when caller `provider.pricingFromModelList`) →
  manual override → LiteLLM → models.dev → llm-prices →
  Artificial Analysis → OpenRouter cross-provider fallback →
  Helicone → `DEFAULT_PRICING`. The large tier blobs live as files
  under `filesDir/pricing/` (one per tier); only timestamps and the
  small manual-override map stay in the `pricing_cache`
  SharedPreferences. **Manual overrides win over every curated source**
  (but still sit *below* the two provider-self-report tiers) — putting
  them behind LiteLLM would silently ignore corrections users add
  specifically because LiteLLM is stale. `getPricing` short-circuits to
  `DEFAULT_PRICING` on the main thread before the preload completes — UI
  callers get the default during the cold window and pick up real values
  on the next state-driven recompose, instead of blocking Compose on the
  synchronized LiteLLM parse. `DEFAULT_PRICING` is
  `$25 / M` input, `$75 / M` output (not zero).

  > The `PricingCache` class-level KDoc still describes a stale
  > five-tier "API > LITELLM > OVERRIDE > OPENROUTER > DEFAULT" model
  > with LiteLLM ahead of OVERRIDE — the actual code does the opposite.

- **Capabilities** (`isVisionCapable`, `isWebSearchCapable`,
  `isReasoningCapable`):
  per-provider user override (visionModels / webSearchModels /
  reasoningModels) → manual `ModelTypeOverride` → provider's own
  `/models` capabilities → LiteLLM → models.dev → naming heuristic
  (`ModelType.infer`). An override flag can only **add** a capability,
  never clear one.

Both are precomputed into `ProviderConfig.visionCapableComputed`,
`webSearchCapableComputed`, `reasoningCapableComputed`, and
`modelPricing` after a refresh, so the hot path on list-render screens is
a `Set`-membership check rather than the full layered scan. See
[costs.md](costs.md) and [model-states.md](model-states.md).

### Trace storage

`ApiTracer` writes one pretty-printed JSON file per outbound API call
under `<filesDir>/trace/`. The Trace list screen needs only a
`(hostname, timestamp, statusCode, reportId, model, category)` summary
per file, but the file itself holds the full request and response bodies
— often tens of KB each. Hardening / perf measures:

- A streaming-parse helper (`parseTraceFileInfoStreaming`) uses Gson's
  `JsonReader` to read only the `TraceFileInfo` metadata fields,
  skipping the request body and stopping inside the response object once
  `statusCode` is captured. No reflective full-graph deserialise.
- An in-memory `cachedTraceFiles` list (a `@Volatile` mirror; `null` =
  not built) is prewarmed off the UI thread and kept in sync by
  `saveTrace` / `clearTraces` / `deleteTracesOlderThan` under the lock,
  so subsequent reads — including the Trace detail screen's prev/next
  nav — are O(1).
- The actual OkHttp interceptor is `TracingInterceptor` (a separate
  file, **not** inside `ApiTracer.kt`), gated by
  `ApiTracer.isTracingEnabled`. It caps captured bodies at 8 MiB,
  writes a partial trace for streaming responses up front and tees the
  bytes into the same file on EOF, and **redacts secrets** in headers,
  `?key=` / `token` query params, and JSON body key fields at write
  time — so a leaked filesystem dump or a trace rolled into a backup
  never carries plain keys.
- Trace tags `(reportId, category, runId, model)` ride a
  `ThreadLocal` (`ApiTracer.currentTags`) propagated through OkHttp's
  dispatcher via `TagPropagatingExecutor` (in `TagPropagation.kt`) so
  retries and cancellations preserve the originating call's identity.

### Share-target

`MainActivity` extracts incoming `ACTION_SEND` / `ACTION_SEND_MULTIPLE`
intents into a `SharedContent` snapshot (`text` + `subject` + URI list +
`mime`). `AppNavHost` renders `ShareChooserScreen` as an **overlay
before the NavHost** and routes the user's pick to one of three
landings: **Report** (pre-fills title/prompt, base64s a single image,
stages non-image attachments as `pendingReportKnowledgeUris`), **Chat**
(stages `chatStarterText` + a starter image), or **Knowledge** (only
shown when Experimental features is on). See
[share-target.md](share-target.md).

### Generic CRUD list

`CrudListScreen<T>` backs every list-of-things screen (Agents, Flocks,
Swarms, Parameters, System Prompts, Internal Prompts per category,
Example Prompts). Each consumer plugs in `itemTitle`, `itemSubtitle`,
`onAdd`, `onEdit`, `onDelete`; the rest is shared.

### Full-screen overlay pattern

Many flows (model picker, scope picker, viewer / edit screens, the
Answer-matrix and Costs overlays) follow:

```kotlin
if (showOverlay) { OverlayScreen(...); return }
```

The trailing `return` inside an `@Composable` preserves the parent's
`remember` / `rememberSaveable` state, so backing out of the overlay
leaves the parent's local state intact — a UX the user has explicitly
relied on. This idiom appears in dozens of screens.

### Two-step Meta scope

Chat-type Meta runs (and Translate) route through `SecondaryScopeScreen`
first, where the user can narrow the input set to the top-N entries of a
chosen rerank, manually pick agents, or (when translations exist) choose
which present languages to fan out across. Rerank-typed and
Moderation-typed prompts skip the scope screen and always run on the
full set. Each scope is encoded onto the row at run time
(`secondaryScope` field) so a cascade-on-prompt-change re-runs at the
same scope rather than silently widening to AllReports. See
[secondary-results.md](secondary-results.md).

### Fan-out / Fan-in

A separate code path under `ReportViewModel.runFanOutPrompt` /
`runFanInPrompt`, delegated to `FanOutEngine`. Fan-out treats each
successful agent's response as a "source" and runs a configurable
Internal Prompt (`category = "fan_out"`) once per (answerer model ×
source agent) pair; `@RESPONSE@` in the template is replaced by the
source response text. Fan-in then combines those per-pair rows back into
a single combined-report row using a `category = "fan_in"` template with
the iterable `***Report*** @REPORT@@RESPONSES@` block. Both produce rows
with `kind = META`; a row is a fan-out row when `fanOutSourceAgentId !=
null` and a fan-in row when `fanInOf != null`.

The drill-in is three levels deep:

- **Level 1** — one row per (answerer, prompt). Action buttons (Resume
  stale / Restart failed / Rerun complete / Delete) live in a collapsed
  Actions card. Empty-body successes count as Done, not Queued.
- **Level 2** — one row per (answerer, source) pair. OnePageView
  virtualisation keeps long lists scrolling smoothly.
- **Level 3** — single response detail with a 🐞 link to the original
  report-model trace.

Concurrency is capped by `ApiCallCaps.fanOut` plus the shared
per-provider throttle, so overlapping report / chat / meta / fan-out
traffic all respect the same host budgets. The hot per-pair
`runningFanOutPairs` flow is separate from `UiState`.

## Concurrency

Throttling is **two distinct layers** stacked on top of each other.

- **`ProviderThrottle`** (`ProviderThrottling.kt`) — the per-hostname
  gate: one `Semaphore` (concurrency) + one sliding-window `Deque`
  (per-minute rate) per host. Replaces the prior per-batch fan-out
  semaphore, so limits hold across overlapping flows (report + meta +
  fan-out + chat on the same provider). Caps resolve per host at acquire
  time: per-provider override → `NetworkSettings` global default
  (defaults: **60 calls/min, 5 concurrent**), each `coerceAtLeast(1)`.
  Three acquire methods: blocking `acquire` (used by the OkHttp
  `ProviderThrottleInterceptor`), non-blocking `tryAcquire`, and the
  suspend `acquireOrWait` (used by the coroutine-layer host gate).
- **`ApiCallCaps`** (`ApiTracer.kt`) — the flow-level coroutine
  `Semaphore` pools, independent of the per-host gate. `global` is the
  only user-tunable cap (default 100, set in Settings → Network →
  Maximal API calls). The per-flow sub-cap semaphores
  (`report` / `translation` / `fanOut` / `fanMeta` / `workers`) are
  retained for the acquisition contract but are all sized to the global
  cap at runtime, so only the global ceiling actually binds.

The **canonical batch acquisition order** is sub-cap → `ApiCallCaps.global`
→ per-host gate. The private sub-cap is taken first so a flow queued on
its own cap holds nothing shared; `global` is always taken before the
host gate (the reverse deadlocked report-vs-metadata calls). The key
invariant (hardened in a recent fix): while **parked** on a saturated
per-host gate, the helper **releases both the sub-cap and `global`** and
re-takes them in canonical order on the next poll — so a per-flow cap
counts only items holding a live provider slot, never items queued
behind a busy provider. `acquireThrottledPermits` + `PermitHold`
(`ThrottledBatch.kt`) own this contract; all seven report-primary
dispatch sites now use it, matching the fan-out / fan-meta dispatchers.
Fan-out and the report-icon work pre-acquire permits and set
`ProviderThrottle.permitPreAcquired` so the OkHttp interceptor skips its
own acquire and doesn't double-count. See [throttle.md](throttle.md).

Other concurrency notes:

- Network calls happen on `Dispatchers.IO`.
- `ApiTracer`, `ReportStorage`, and the other storage objects use
  `ReentrantLock` for thread-safe file writes.
- `AtomicFileWrite.writeTextAtomic` uses `Files.move(ATOMIC_MOVE)` with
  an `fsync` of the temp file before the rename, and creates the parent
  dir on demand. The same stage-as-`.part` + atomic-rename pattern backs
  the export writer and several other "write a complete artifact" sites.
- Usage/cost stats live in `ConcurrentHashMap` stores with a 2-second
  debounced flush (`USAGE_STATS_FLUSH_MS`), so heavy concurrent updates
  don't serialize on disk I/O; the flush is forced from `onCleared` so a
  Refresh-all auto-restart can't drop in-flight stats.
- `RateLimitRetryInterceptor` retries **429s** with a configurable
  back-off (`NetworkSettings.maxRetriesOn429` × `retryBackoffMs429`,
  both **default 3 × 1000 ms**, exponential with ±50% jitter capped at
  30 s, honoring server `Retry-After`). It bails on cancellation, has an
  explicit main-thread guard so it can never ANR the UI, and benches a
  model (`ModelCooldownStore`) instead of retrying on long
  `Retry-After`s or billing-cap 429s. `OverloadedRetryInterceptor` is
  the **529** (server overloaded) sibling with an independent budget
  (`maxRetriesOn529` × `retryBackoffMs529`). `AnalysisRepository.withRetry`
  treats `408 / 425 / 429` plus network errors as transient and skips
  permanent 4xx; 5xx is transient.
- `withApiCallTimeout` wraps each single request or stream-open (not the
  SSE read loop) in `withTimeout` to guard against indefinite DNS
  hangs; `withHostGate` acquires the per-host gate at the coroutine
  layer, sitting **outside** the timeout so a legitimate rate-window
  wait doesn't trip it. OkHttp's own `maxRequests` /
  `maxRequestsPerHost` are both set to 512 so OkHttp gates nothing —
  `ProviderThrottle` is the sole intended throttle.
- Multiple Translate batches can be in flight at once (one per `runId`,
  tracked in `TranslationRunManager`'s
  `Map<String, TranslationRunState>`); they share the per-provider
  throttle but their results land in their own rows.
- Storage write APIs validate flat ids (non-blank, not `.` / `..`, no
  `/` or `\`) and apply canonical-containment checks before any file op,
  so a malformed or restored id can't escape its directory.
- Backup restore caps per-entry (256 MB) and total (1 GB) bytes before
  writing into `filesDir` / `cacheDir` from the zip.

## Streaming

Server-Sent Events flow through `ApiStreaming`, parsed into
`Flow<String>` chunks by a single W3C-compliant reader (`parseSseStream`)
that always decodes UTF-8 (ignoring the response charset to dodge
OkHttp's ISO-8859-1 fallback), buffers multiple `data:` lines per event,
and dispatches on a blank line. Per-format content extractors handle
OpenAI Chat (`choices[0].delta.content`), OpenAI Responses
(`response.output_text.delta`), Anthropic (`content_block_delta`), and
Gemini (`candidates[*].content.parts[*].text`). The reader recognizes
each format's terminator (`[DONE]`, `message_stop`, `response.completed`,
Gemini's `finishReason`) and accepts a clean EOF only if at least one
content chunk was emitted; otherwise it raises an IOException so a
truncated stream surfaces as an error rather than a silent empty answer.
Streaming usage is recovered per format (the trailing `include_usage`
chunk, `response.usage`, `message_start` / `message_delta`, or Gemini's
cumulative `usageMetadata`).

## OpenAI dual API

OpenAI routes between two endpoints. `usesResponsesApi(service, model)`
returns true when `service.responsesApiPatterns.anyMatches(model)`
(authoritative, from `providers.json` — for OpenAI the patterns are
prefix `gpt-5`, `o1`, `o3`, `o4`, `gpt-4.1`) **or** when
`ModelType.infer(model) == RESPONSES` (the naming heuristic, which
catches `gpt-5` / `o3` / `o4` by prefix). When true the dispatch uses the
Responses API (system prompt → `instructions`, single text turn passed as
a bare string); otherwise Chat Completions. There is no `endpointRules`
field — those prefixes now live in `ModelType.infer`. See
[api-formats.md](api-formats.md).

## State recovery

Recovery mechanisms keep the app robust to process death:

1. `restoreCompletedReport` and `hydrateAgentResultsFromStorage` rebuild
   the in-memory `_agentResults` flow from `ReportStorage` files when the
   user returns to a finished report whose StateFlow was lost.
2. `resumeStaleRunsForReport` is the cross-kind on-open / background
   resume orchestrator: it reconciles translations, then defers to each
   engine (`fanOutEngine` / `tournamentEngine` / `judgeEvalEngine` /
   `regenerateBatchEngine`) and finally re-issues interrupted single-call
   Meta / Rerank / Moderation placeholders. Only rows interrupted by app
   death (blank content, no error, no duration) are touched, and each row
   is re-read before being stamped "interrupted" so a cold launch
   mid-batch doesn't lose progress.
3. A 30-second app-wide background sweep (`startBackgroundResumeSweep`,
   `Job` on `AppViewModel.backgroundResumeSweepJob`) re-runs that
   orchestrator for every report newer than 7 days and auto-resumes a
   `PAUSED_ON_ERROR` regenerate job once its errored row clears.
4. `rememberSaveable` on key UI state (AI Usage's expanded provider list,
   drill-in scope buckets per `(report, prompt)`, the selected view
   language, the Answer-matrix open flag) survives navigation away and
   back. Chat's staged `userInput` and attached image are preserved
   across process recreation; Dual Chat conversations persist across
   rotation.
5. The Report Result screen recovers stale placeholders on entry, so the
   user never lands on a forever-spinning hourglass.

See [regenerate.md](regenerate.md) and
[secondary-results.md](secondary-results.md).

## In-app logging

`com.ai.data.AppLog` is a log4j-style file appender that mirrors
`android.util.Log` and writes every call at or above `threshold` to
`<filesDir>/applog/applog_<yyyyMMdd>.log`. Levels: `TRACE` (2),
`DEBUG` (3), `INFO` (4), `WARN` (5), `ERROR` (6), `OFF` (99); default
threshold **INFO**. Files rotate daily; a single `BufferedWriter` is
held open and flushed per line so a process kill never loses the last
few lines. Sensitive headers (`Bearer …`, raw `sk-` / `xai-` / `gsk_`
keys, Google `?key=` params) are redacted inline before write. The
viewer (`AppLogScreen`) lives under Hub → AI App log and supports search
/ level / time-range / tag filters with a Copy/Share dialog. Threshold
is set from Settings → Logging. The `applog/` directory is **excluded**
from backups (see below). See [applog.md](applog.md).

## Auto-restart

After a "Refresh all" run, the app restarts itself to pick up
freshly-persisted caches without forcing the user to swipe the app away:

```kotlin
val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
launch?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
context.startActivity(launch)
Runtime.getRuntime().exit(0)
```

The same restart gate applies after restoring a backup (so the in-memory
singletons — `ProviderRegistry`, `ApiTracer`, `PromptCache`,
`ReportStorage`, `ChatHistoryManager` — match the freshly-restored disk
state) and after the "Reset application" full reset.

## Persistence map

Persistence is **`SharedPreferences` + JSON/text files** only (no
DataStore). There are 11 distinct SharedPreferences files; the main one
is `eval_prefs` (`SettingsPreferences.PREFS_NAME`), holding ~66 `KEY_*`
keys (agents, flocks, swarms, parameters, system prompts, internal
prompts, provider states, the model-state lists, throttle settings, API
keys, …). The others: `provider_registry`, `pricing_cache`,
`dual_chat_prefs`, `huggingface_cache`, `model_cooldowns`,
`view_screen_prefs`, `last_report_tracker`, `provider_field_timestamps`,
`translation_modes`, `update_from_cloud`.

`filesDir` holds one subdirectory per storage object — `reports`,
`secondary`, `trace`, `chat-history`, `embeddings`, `knowledge`,
`pricing`, `model_lists`, `prompt_cache`, `regenerate`, `crash`,
`audit`, `applog`, plus the on-device runtime dirs `local_llms`,
`local_models`, `native` — and a handful of top-level files
(`model_pricing.json`, `model_supported_parameters.json`,
`usage-stats.json`, `usage-category-stats.json`,
`usage-report-stats.json`, `prompt-history.json`,
`internal_prompt_icons.json`). See [persistent.md](persistent.md).

## Backup / restore

`BackupManager` streams a single `.zip` to a SAF Uri:
`manifest.json` (version = `MANIFEST_VERSION` = 1), `prefs/<name>.json`
(7 of the 11 prefs files, type-tagged so `Int` doesn't collapse to
`Double`), `files/<mirror of filesDir>/…`, and `cache/<mirror of
cacheDir>/…`. The `filesDir` mirror excludes
`FILES_DIR_BACKUP_EXCLUDES = {"local_llms", "local_models", "native",
"applog"}` — **four** entries (the device-ABI-tied native runtime and
the app-log dir join the two on-device model dirs). The same four are
preserved through `clearFilesDirForRestore`.

Restore is **validate-then-write**: copy the input to a temp zip, check
the manifest version (accepted range is exactly 1), read the whole
archive into memory with per-entry (256 MB) and total (1 GB) byte caps
and path-traversal checks **before** anything destructive, commit all
prefs, wipe `filesDir` (minus the excludes), then write the staged files
with `fsync`. Prefs are committed before `filesDir` is wiped, so a crash
mid-restore leaves a re-restorable state rather than an inconsistent one.
The process is killed and relaunched afterward (restore does not
live-reload). Per-report export/import (`ReportBundle`,
`EXPORT_VERSION` = 1, accepted range 1..1) is a separate, smaller zip
that always lands as a brand-new report with fresh ids. See
[backup-restore.md](backup-restore.md).

## First-run seeding + every-start delta merge

`AppViewModel.bootstrap` runs through a sequence of structured DEBUG /
TRACE log lines (under the `App.bootstrap` tag) so the AppLog viewer can
render the entire start-up sequence for a support session.

Several seed sources are **delta-merged on every app start**, not just
on fresh install — each via an `ensureAllPresent(...)` pass that only
adds missing entries (by `(category, name)` for prompts, by stable id
for providers) and never overwrites an existing row:

- `assets/providers.json` — entries import on first run; new entries
  append on later starts. `ProviderFieldTimestamps` decide which fields
  the every-start `syncFromAsset` may overwrite — a field the user has
  edited (timestamp non-null) is left alone; an un-edited field tracks
  the asset.
- `assets/internal-prompts/<Language>/` — Internal Prompts, organised
  by language folder (`English`, `Dutch`, …) then category sub-dir:
  - `workers/` — the generation prompts: `report-icon`, `model-icons`,
    `report-title-short` / `report-title-long`, `report-language-name` /
    `report-language-icon`, `model-titles`, `fan-meta`, `tournament`,
    `translation-icon`, `second-meta`, `user-note`;
  - `meta/` and `meta_compare/` — the Meta and Compare-with-meta seeds;
  - `fan_out/` and `fan_in/` — the Fan-out / Fan-in templates;
  - `alt/` — the Find-alternative variants (`main`, `report`,
    `fan_out`, `language`, `meta`, `model_title`, `report_title`,
    `report_title_long`, `translation`);
  - `internal/` — fixed templates: `chat-title`, `model-info`,
    `model-intro`, `translate-text`, `translate-title`, `test-model`,
    `second-rerank`, `second-moderation`.
- `assets/examples.json` — Example Prompts, merged by title
  (case-insensitive).
- `assets/system-prompts.json` — bundled System Prompts.
- `assets/excluded.json` / `assets/inaccessible.json` — appended
  test-excluded / inaccessible `(provider, model)` entries.
- `assets/meta.json` — default Meta items.

The Housekeeping → Reset → "Restore bundled assets" path force-merges any
missing rows back without resetting user-edited ones. None of the asset
files overwrite existing rows on the delta path, so a re-seed never
destroys user edits.
