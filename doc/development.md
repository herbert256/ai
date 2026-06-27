# Development Guide

For developers maintaining or extending this app. Pairs with
[architecture.md](architecture.md) (the bigger picture) and
[datastructures.md](datastructures.md) (the data classes).

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleRelease
```

Toolchain: Kotlin 2.4.0, AGP 9.2.1, Gradle 9.5.1, Java 25 (JVM
target 25, source/target compatibility 25), Compose BOM 2026.05.01.
`compileSdk = 37`, `buildToolsVersion = "37.0.0"`, `minSdk = 36`,
`targetSdk = 36`, namespace `com.ai`, `applicationId = "com.ai"`.
Release builds enable `isMinifyEnabled` and `isShrinkResources` and
require `local.properties` to define `KEYSTORE_FILE` /
`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` — the build
fails loudly otherwise rather than emitting an unsigned APK.

Network timeouts ship as `BuildConfig.NETWORK_*_TIMEOUT_SEC` so the
OkHttp client can read them at construction time without a prefs
round-trip:

| BuildConfig field | Value | Used for |
|---|---|---|
| `NETWORK_CONNECT_TIMEOUT_SEC` | 30 | TCP / TLS handshake |
| `NETWORK_READ_TIMEOUT_SEC` | 240 | Streaming reads (SSE) — the `ReadTimeoutInterceptor` streaming branch default |
| `NETWORK_NONSTREAMING_READ_TIMEOUT_SEC` | 120 | Non-streaming reads — the `ReadTimeoutInterceptor` default branch |
| `NETWORK_WRITE_TIMEOUT_SEC` | 30 | Request body upload |
| `TEST_CONNECTION_READ_TIMEOUT_SEC` | 30 | Per-provider Test API Key call |

These seed `NetworkSettings.streamingReadTimeoutSec` (240) /
`nonStreamingReadTimeoutSec` (120) at startup; both are
runtime-tunable from Settings → Network settings afterwards.
(A few in-code comments in `ReadTimeout.kt` / `TestCallTimeout.kt`
still mention an old "10 min" read default — that's stale; the
real static read timeout is 240 s.)

The `versionName` is computed at build time as `YY.DDD.MIN` (last
two digits of the year, day-of-year, minutes-of-day) so two builds
the same minute share a version; `versionCode` stays `1`.

A few notable runtime dependencies (full list in `ai/build.gradle.kts`):
- **PDFBox-Android 2.0.27.0** (`com.tom-roush:pdfbox-android`) — PDF
  text extraction for RAG ingest and report-PDF export.
- **MediaPipe Tasks GenAI / Tasks Text** — on-device LLM + embedder.
  The ~26 MB LLM native `.so` is *not* bundled; it is downloaded on
  demand from the matching AAR (see [local-runtime.md](local-runtime.md)).

> The `androidx.datastore.preferences` dependency is declared in
> `gradle/libs.versions.toml` but **unused** — there are zero
> DataStore API calls in the source tree. Persistence is
> SharedPreferences + JSON files under `filesDir`/`cacheDir` only.
> Don't reach for DataStore expecting an existing pattern.

## Deploy

```bash
adb install -r ai/build/outputs/apk/debug/ai-debug.apk \
  && cp ai/build/outputs/apk/debug/ai-debug.apk /Users/herbert/cloud/ai.apk \
  && adb shell am start -n com.ai/.MainActivity
```

The cloud-copy step is convention in this repo (CLAUDE.md) —
deploy to **both** targets after every successful build, not only
after explicit-commit prompts.

## Logs

```bash
adb logcat | grep -E "App.bootstrap|ApiDispatch|ApiTracer|AppLog|AppViewModel|AtomicFileWrite|BackupManager|ChatHistoryManager|ImportExport|ModelListCache|PricingCache|ProviderRegistry|ProviderFieldTimestamps|RateLimit|ReportExport|ReportStorage|SettingsExport|Throttle"
```

The in-app **API Traces** screen (Hub → AI API Traces) is usually a
faster way to inspect what was sent / received during a report run —
each call gets a pretty-printed JSON file under `<filesDir>/trace/`
while `ApiTracer.isTracingEnabled` is on (it's on by default but can
be toggled in Settings). Secrets are redacted before the trace
hits disk (`Authorization` / `x-api-key` / `?key=` query params /
JSON body key fields), since trace files roll into backups.

There's also the **AI App log** in-app viewer (Hub → AI App log)
— a log4j-style file appender (`com.ai.data.AppLog`) that
mirrors `android.util.Log` and writes everything at or above
`Settings → Logging → Level` to
`<filesDir>/applog/applog_<yyyyMMdd>.log` (rotated daily). Lines
are written through inline redaction (Bearer tokens, raw `sk-` /
`xai-` / `gsk_` / `key-` API keys, Google `?key=` query params) so
a shared log never carries plain secrets. The viewer supports
search, level checkboxes, time-range pickers, tag dropdown, and a
Copy/Share dialog with **Filtered only** + **Last N lines /
Complete** options. The code uses ~80 distinct `AppLog` tags. See
[applog.md](applog.md).

## Project layout

```
ai/src/main/java/com/ai/
├── MainActivity.kt                   # the only Activity (ComponentActivity)
├── data/                             # 88 files, core data layer
│   ├── (HTTP, dispatch, streaming, tracer, throttle, registry, …)
│   ├── AnalysisRepository.kt   ApiClient.kt     ApiDispatch.kt
│   ├── ApiFormat.kt            ApiModels.kt     ApiStreaming.kt
│   ├── ApiTracer.kt            TracingInterceptor.kt   TagPropagation.kt
│   ├── RateLimitRetry.kt       OverloadedRetry.kt
│   ├── ReadTimeout.kt          TestCallTimeout.kt   ProviderThrottling.kt
│   ├── AppLog.kt               AppService.kt    AtomicFileWrite.kt
│   ├── BackupManager.kt        ChatHistoryManager.kt   DataModels.kt
│   ├── EmbeddingsStore.kt      EmojiExtract.kt  ExamplePromptSeed.kt
│   ├── HuggingFaceCache.kt     ImageAttach.kt   InternalPromptSeed.kt
│   ├── Knowledge*.kt           ModelListCache.kt   ModelType.kt
│   ├── PricingCache.kt         PricingParsers.kt   PromptCache.kt
│   ├── ProviderFieldTimestamps.kt    ProviderRegistry.kt
│   ├── ReportStorage.kt        SecondaryResult.kt   SecondaryModels.kt
│   ├── TournamentRunModel.kt   JudgeEvalRunModel.kt CompareRunModel.kt
│   ├── TournamentRanking.kt    JudgeAgreement.kt    SharedContent.kt
│   └── local/  (4)             # LocalLlm / LocalEmbedder / LlmRuntime / LocalRuntime
├── model/                            # 2 files
│   └── SettingsModels.kt + SettingsHolder.kt
├── viewmodel/                        # 24 files
│   ├── AppViewModel.kt  (+ AppViewModelTypes.kt holding GeneralSettings
│   │   and the extracted top-level enums)
│   ├── ChatViewModel.kt + ReportViewModel.kt (+ ReportViewModelHelpers.kt)
│   ├── BatchEngine.kt — abstract base the batch engines extend
│   └── extracted engines/managers: RegenerateBatchEngine,
│       SecondaryRunManager, IconGenerationManager, TournamentEngine,
│       JudgeEvalEngine, CompareEngine, FanOutEngine, ModelTestEngine,
│       StressTestEngine, TranslatorRankEngine, MetaEditManager,
│       TranslationRunManager, SecondaryModelSwitchManager, WorkerRunner,
│       ThrottledBatch, BrokenWorkPolicy, BuildProgress, TranslationTypes
└── ui/                               # 273 files (no files at the ui/ root)
    ├── report/      (98)             # report flows, secondary results,
    │                                 # Fan-out / Fan-in / Tournament / Judges /
    │                                 # Compare / Rank-translators screens,
    │                                 # exports (PDF, DOCX/ODT, RTF, zipped HTML),
    │                                 # translation screens, icon screens,
    │                                 # manage/ overview + edit, Get-info /
    │                                 # regenerate, the Answer matrix
    ├── cruds/       (48)             # generic CRUD framework + per-entity
    │                                 # CRUDs: workers (agents/flocks/swarms),
    │                                 # model-states, prompts, params, cost overrides
    ├── admin/       (35)             # Housekeeping / Backup-Restore / Reset /
    │                                 # Trim by age / Costs / Test / traces / help /
    │                                 # provider admin / developer / docs / AppLog
    ├── settings/    (22)             # SettingsScreen sub-screens + Workers /
    │                                 # Local-runtime setup
    ├── shared/      (17)             # CrudListScreen, TitleBar + BottomIconBar,
    │                                 # AppColors, Badges, Dialogs, Cards, …
    ├── helpers/     (16)             # report export builders + shared helpers
    ├── navigation/  (7)              # AppNavHost, NavRoutes + the 5 route files
    ├── other/       (6)              # Selection picker + misc
    ├── chat/        (5)              # chat + chat history + dual chat
    ├── search/      (4)              # Quick / Extended local + Remote
    │                                 # + Local semantic search screens
    ├── hub/         (5)             # main hub + Reports / Chats hubs
    ├── history/     (3)              # report + prompt history + picker
    ├── models/      (3)              # model search + Model Info
    ├── share/       (2)              # ShareChooserScreen + helpers
    ├── knowledge/   (1)              # RAG Knowledge screens
    └── theme/       (1)              # Material3 dark theme
```

Roughly **388 Kotlin files, ~153,180 LOC** total
(`data` 88 + `model` 2 + `viewmodel` 24 + `ui` 273 + `MainActivity`).

### Navigation, in two systems

1. **Top-level routes** use Jetpack Navigation Compose.
   `NavRoutes.kt` holds ~120 route-template constants plus
   arg-encoding builder helpers (e.g. `aiReportInfo()`,
   `traceDetail()`). `AppNavHost.kt` declares the single `NavHost`
   but does **not** register composables inline — registration is
   split across five `NavGraphBuilder` extension functions, each in
   its own file under `ui/navigation/`: `reportRoutes`,
   `settingsAdminRoutes`, `knowledgeSearchRoutes`, `developerRoutes`,
   `chatRoutes`. All five take the same `(navController, appViewModel,
   reportViewModel, chatViewModel, safePopBack, navigateHome)`.
2. **`SettingsScreen` sub-screens** use an internal `when` block.
   `SettingsScreen.kt` holds an `enum class SettingsSubScreen` (~40
   values: `MAIN`, `AI_SETUP`, `AI_PROVIDERS`, `AI_AGENTS`,
   `AI_PARAMETERS`, `SETTINGS_NETWORK`, `SETTINGS_UI`, …), a
   `rememberSaveable currentSubScreen`, and a large `when` that both
   renders the sub-screen and implements hierarchical back navigation
   via `BackHandler`.

### Full-screen overlay pattern (load-bearing)

Many screens layer a full-screen overlay over their parent with:

```kotlin
if (showOverlay) { OverlayScreen(...); return }
```

The trailing `return` keeps the parent composable from continuing,
which **preserves the parent's `remember` / `rememberSaveable`
state** while the overlay is up. This is used ~60 places across
`ui/` (e.g. the report-View grid's Costs / Icons / Matrix /
Tournament overlays in `ui/report/view/`). Do not "fix" it into a
separate route — the user relies on the in-place state preservation.

### The three "view models"

Only `AppViewModel` is an actual androidx `ViewModel`
(`class AppViewModel(application) : AndroidViewModel(application)`).
`ReportViewModel` and `ChatViewModel` are **plain classes** that
take an `AppViewModel` in their constructor and delegate all state
to it (`UiState` / `StateFlow`); despite the `…ViewModel` name they
do not extend androidx `ViewModel`. The extracted batch engines
(`CompareEngine`, `FanOutEngine`, `TournamentEngine`,
`JudgeEvalEngine`, `RegenerateBatchEngine`, `ModelTestEngine`,
`StressTestEngine`, `TranslatorRankEngine`, `MetaEditManager`,
`SecondaryModelSwitchManager`) use `internal constructor` and most
extend the `abstract class BatchEngine`; the managers
(`SecondaryRunManager`, `IconGenerationManager`,
`TranslationRunManager`, `WorkerRunner`) are public.

## Adding things

### A new OpenAI-compatible provider

1. Add **one new JSON file** under `assets/providers/` — the bundled
   catalog is now one file per provider (48 files), each a bare
   `ProviderDefinition` object (**no** `{"providers": [...]}` wrapper).
   Required: `id`, `baseUrl`, `adminUrl`, `defaultModel`. Optional:
   `apiFormat` (defaults to `OPENAI_COMPATIBLE`), `openRouterName`,
   `litellmPrefix`, `hardcodedModels`, `mergeHardcodedModels`,
   `defaultModelSource`, `modelFilter`, `typePaths`, `modelsPath`,
   `responsesApiPatterns`, `builtInEndpoints`, the per-provider
   throttle/retry overrides (`maxCallsPerProviderPerMinute`,
   `maxConcurrentCallsPerProvider`, `maxRetriesOn529`, …), etc.
   `ProviderRegistry.readBundledProviderDefs` reads every `*.json` in
   the folder, sorted by filename for a deterministic merge; one bad
   file is skipped, not fatal.

   Note: `id` is the **only** name field — there is no separate
   `displayName` or `prefsKey`. The UI shows `id` directly, and
   SharedPreferences key prefixes use it directly (`OpenAI_api_key`).
2. For a non-default chat or models path, set `typePaths` (e.g.
   `typePaths.chat`) and `modelsPath`. `chatPath` / `responsesPath`
   are computed getters over `typePaths`, not stored fields.
3. The user-facing import/export wire format (the "Import providers.json"
   button + "Share providers", via `ProviderRegistry.upsertFromJson`)
   is still the flat `{"providers": [...]}` shape with **no top-level
   `version` field** — only the on-disk bundled layout changed to
   per-file.

`AppService` is a plain `class` (not a `data class`): its
`equals`/`hashCode`/`toString` are **id-only**, and it hand-writes a
`copy(...)` funnel covering all of its fields so a newly added field
can't be silently dropped. Add the field to the constructor *and* to
the `copy(...)` body, and to `ProviderDefinition` (the Gson DTO in
`ProviderRegistry.kt`) plus its `toAppService` / `fromAppService`.

`ProviderRegistry` starts **empty** on a fresh install. The first
run reads the `assets/providers/` folder via `importFromAsset(context)`
and persists each provider into the `provider_registry`
SharedPreferences file. To force a re-import on an existing install:
AI Setup → Providers → Refresh providers (or Housekeeping → Reset →
Refresh bundled assets, which re-pulls the `assets/providers/` set
*and* the `internal-prompts/` tree).

`importFromAsset` is append-only (it never overwrites an existing
id, returns the count of newly-added providers, `-1` on failure) and
drops malformed entries (null/blank `id`/`baseUrl`) instead of
NPEing later. The every-start delta-merge uses `syncFromAsset`,
which only touches fields the user has *not* edited (gated by
`ProviderFieldTimestamps`) and never appends new providers.

### A non-OpenAI-compatible provider

`ApiFormat` has exactly four values: `OPENAI_COMPATIBLE` (45 of the
48 bundled providers), `ANTHROPIC` (just `Anthropic`), `GOOGLE`
(just `Google`), and `REPLICATE` (just `Replicate`). All 45 OpenAI-compatible providers share one set
of code paths; only Anthropic and Google have format-specific
branches. To add a genuinely different format:

1. Add an `ApiFormat` enum value.
2. Add format-specific branches everywhere the dispatch switches on
   `service.apiFormat` (the compiler enforces exhaustiveness on the
   `when`s): analyze / sendChat / fetchModels / embed and the
   streaming-report dispatch in `ApiDispatch.kt`, the chat-stream
   dispatch in `ApiStreaming.kt`, per-format auth/URL building
   (`ApiDispatch.kt` endpoint URLs + header setup), and the
   per-format usage parser in `ReportStorage.kt`.
3. Mirror the Anthropic/Google request builders, SSE content +
   usage extractors (`ApiStreaming.kt`), and the Retrofit interface
   in `ApiClient.kt`.

For reference, per-format auth: Anthropic sends
`x-api-key` + `anthropic-version: 2023-06-01` to `baseUrl/v1/messages`;
Google appends a URL-encoded `?key=` query param to
`v1beta/models/$model:generateContent`; OpenAI-compatible uses
`Authorization: Bearer`.

### A new agent parameter

1. Add it to `AgentParameters` (and `ChatParameters` if it should
   also appear in chat) in `data/DataModels.kt`, **and** to the
   `Parameters` preset class in `model/SettingsModels.kt`.
2. Wire the field into **both** parameter-fold sites — each is its own
   exhaustive constructor call, so a missing field just drops silently:
   `Settings.mergeParameters(ids)` in `model/SettingsModels.kt` (folds
   the preset chain) **and** `AnalysisRepository.mergeParameters`
   (folds the per-call override over the agent params) plus its
   `filterParametersBySupported`. Most scalar/string fields use
   "later non-null wins"; boolean toggles are OR-ed (or AND-ed, for
   opt-outs like `returnCitations`).
3. Add UI inputs in `AgentEditScreen` (`settings/AgentsScreen.kt`)
   and `ParametersEditScreen` (`settings/ParametersScreen.kt`).
4. Update the request model in `ApiModels.kt` (per `ApiFormat`).
5. Pass it through in `ApiDispatch.kt` and the streaming methods.
6. If it should be settable per-turn from a chat session (like
   reasoning effort), wire it in `ChatSessionScreen`
   (`ui/chat/ChatScreens.kt`).

> For how an added parameter is *resolved* at call time (agent /
> flock / swarm / per-call precedence), see
> [parameters.md](parameters.md); for the system-prompt equivalent,
> [system-prompts.md](system-prompts.md).

### A new pricing tier

The lookup precedence lives in the single private
`PricingCache.findPricingMatch` chain (which `getPricing` /
`getPricingWithoutOverride` / `lookupPricing` all delegate to), first
hit wins: (1) **OpenRouter self-report** (only when the caller
provider has `crossProviderModelList`) → (2) **Together self-report**
(only when `pricingFromModelList`) → (3) **manual OVERRIDE** →
(4) LiteLLM → (5) models.dev → (6) llm-prices → (7) Artificial
Analysis → (8) OpenRouter cross-provider fallback (for non-OpenRouter
callers) → (9) Helicone → (10) `DEFAULT_PRICING` (returned by
`getPricing` when `findPricingMatch` misses). The class-level KDoc in
`PricingCache.kt` now matches this order (OVERRIDE ahead of the
curated tiers), so a user's manual correction can't be silently
overridden by a stale catalog. `getPricingWithoutOverride` runs the
same chain with `includeOverride = false`.

To add a tier:

1. Add a fetch function in `PricingCache.kt` (mirror
   `fetchHeliconeOnline` or `fetchLLMPricesOnline`).
2. Add timestamp + manual handling, plus the on-disk blob under
   `<filesDir>/pricing/<tier>_pricing.json` (and a `_meta.json`
   sidecar for capability tiers), with a bundled fallback at
   `assets/info-providers/<key>.json`. Bump to a `_v2` key if a
   parser revision must invalidate stale data.
3. Add a `find<Tier>Pricing` helper, then insert it into
   `findPricingMatch` (the single precedence-ordered chain) in the
   right slot, and into `getTierBreakdown` (+ the `pricesConflict`
   tier list). Keep manual OVERRIDE above the curated tiers.
4. Wire a card on `RefreshScreen` and (if a free API key is
   required) a field on `ExternalServicesScreen`.
5. Add the tier as a new column in the layered-cost CSV export.
6. Add the tier as a Source button on the Model Info screen.
7. Add an info-provider help page (`InfoProviderHelp.kt`) with an
   ℹ deep link from the Source button — and an `INFO_PROVIDERS`
   entry in `HelpScreen.kt` so trace ℹ deep-links resolve.

`DEFAULT_PRICING` is **not** zero — it is
`ModelPricing("default", 25e-6, 75e-6)` ($25/M in, $75/M out), so an
unknown model is conservatively over-priced rather than free.

### A new SecondaryKind (after RERANK / META / MODERATION / TRANSLATE / TOURNAMENT / JUDGES / COMPARE / TRANSRANK)

`SecondaryKind` (`data/SecondaryModels.kt`) has exactly these **eight**
values, in this order (the most recent addition is `TRANSRANK` —
"Rank the translators", a grid-shaped kind driven by
`TranslatorRankEngine`). Most "I want a new analysis on report
outputs" cases need **no** new kind — add a Meta-prompt entry under
Settings → AI Setup → Prompt management instead. Add a new
`SecondaryKind` only when the flow has fundamentally different
routing (different endpoint shape, result schema, or rendering).

Note that there is **no** `metaTypeToKind` function — Rerank, Meta,
and Moderation are dispatched by three separate entry methods on
`SecondaryRunManager` (`runRerank` → `RERANK`, `runModeration` →
`MODERATION`, `runMetaPrompt` → `META`), all funneling through the
shared `executeSecondaryTask`. The grid-shaped kinds have dedicated
engines (`TournamentEngine`, `JudgeEvalEngine`, `CompareEngine`,
`TranslatorRankEngine`) and Fan-out / Fan-in / Translate have their
own managers.

Add the enum value, then walk the compiler's exhaustive-`when`
errors:

- `legacyKindDisplayName` mapping (`data/SecondaryResult.kt`)
- `SecondaryResultStorage.Counts` + `countForReport`
  (`data/SecondaryResult.kt`)
- The `SecondaryRunManager` entry method + `executeSecondaryTask`
  (or a new `BatchEngine` subclass if grid-shaped, à la
  `TranslatorRankEngine`)
- `RegeneratePhase` + `RegenerateBatchEngine` if the kind should be
  regenerable (the grid kinds JUDGES / COMPARE / TRANSRANK are **not**
  regenerable phases — only TOURNAMENT among them is)
- The `SecondaryResultsScreen` routing in `ui/report/manage/SecondResults.kt`
  (picker / table / drill-in list) — the trace/usage "Type" string and
  the AI Usage `kind`
- `ContentDisplay.ReportCostTable` row mapping
  (`ui/report/manage/view/ContentDisplay.kt`)
- The report-export builders (`ui/helpers/`): `ReportExport`
  (view-picker tabs + card rendering + costs Type column),
  `WordOdtExport`, `ZippedHtmlExport`, and the `PdfExport` filter
- A help topic for any new screen and a default glyph for the dashboard
  / cost-table StatChip

The pattern documents itself; lean on the compiler.

### A new Internal Prompt category

Internal prompts carry a free-form `category` string. Current
seeded categories (folders under `assets/internal-prompts/<lang>/`):
`meta`, `meta_compare`, `fan_out`, `fan_in`, `workers`, `alt`,
`internal`. To add one:

1. Seed default rows under `assets/internal-prompts/<Language>/<cat>/`
   — two files per prompt: a `<name>.json` metadata sidecar (name,
   title, reference, agent, optional provider/model/parameters/
   systemPrompt) plus a `<name>.txt` body (real line breaks). The
   folder name is the authoritative category. They are delta-merged in
   on the next cold start by `InternalPromptSeed` (existing rows are
   never overwritten).
2. Add a sub-hub entry in `SetupScreens.kt` → `InternalPromptsHubScreen`
   (which counts entries by category and routes to the per-category
   list).
3. Add UI in `InternalPromptsScreen.kt` if the title / Add label
   needs to differ.
4. The runtime only acts on a `category` if a runner inspects it.
   `SecondaryRunManager` (`runMetaPrompt` / `runFanInPrompt`),
   `FanOutEngine` (`runFanOutPrompt`), and `CompareEngine`
   (`meta_compare`) own the existing categories; a new category with
   new behaviour needs its own entry point.

### A new Help topic / help page

`ui/admin/HelpContent.kt` assembles `HELP_TOPICS` from 12 per-domain
maps (`ReportsHelp.kt` 112, `SettingsAdminHelp.kt` 90,
`ProviderCatalogHelp.kt` 44, `developerHelp` 21, `glossaryHelp` 18,
`crudHelp` 15, …; ~359 base entries) plus 22 auto-built
`<topic>_icons` pages → **~381 topics total**. Each full-screen
overlay has its own entry. To add one:

1. Add a `"<topicId>" to HelpContent(title, cards)` entry to the
   relevant per-domain map.
2. Point the screen's `TitleBar` (or `ViewTitleBar`) `helpTopic`
   string at it. The red ❓ resolves it to the entry.
3. If the screen is in the report-Manage family and should show the
   live icon-legend overlay, add it to `LEGEND_OVERLAY_TOPICS`
   (`ui/shared/SharedComponents.kt`). If it has >3 bar icons,
   add it to `ICON_HELP_AS_PAGE` (`ui/admin/IconHelp.kt`) so an
   `<topic>_icons` page is auto-built.

Trace ℹ deep-links resolve through `infoProviderForTrace(url,
category)` in `HelpScreen.kt` (backed by `infoProviderForUrl` + the
canonical 7-entry `INFO_PROVIDERS` list) — there is **no**
`HelpResolver` class.

> Memory rule: **every new screen needs a help topic.** Reusing an
> existing topic (as the Answer matrix does with `view_ai_report`)
> is acceptable, but never ship a screen with no `helpTopic`.

There are also two WebView-served documentation bundles
(`DocumentationScreen.kt`): `assets/docs/manual/` (helpTopic
`manual`) and `assets/docs/technical/` (helpTopic
`technical_documentation`, an HTML render of this `doc/` tree),
reached from `AboutScreen`. JavaScript is disabled in that WebView.

## Testing

Two layered suites:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew test                         # unit (fast)
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew connectedDebugAndroidTest    # instrumented (~40s on emulator)
```

Both layers use Truth. Roughly **59 unit-test files** under
`ai/src/test/java/com/ai/` (representative: `ApiDispatchHelpersTest`,
`ApiMockWebServerTest`, `BuildChatUrlTest`, `ResponsesUrlTest`,
`TracerTagsTest`, `DefaultClaudeMaxTokensTest`, `ModelTypeTest`,
`EmbeddingsStoreTest`, `EmbeddingsCosineTest`,
`SecondaryResultHelpersTest`, `ApiModelsUsageTest`,
`BackupManagerRestoreTest`, `AtomicFileWriteTest`,
`SettingsGraphTest`, `SettingsAiSetupGraphTest`,
`ProviderDefinitionTest`, `AppServiceLocalTest`,
`DualChatParametersTest`, `ParametersMergeTest`,
`AnalysisRepositoryParametersTest`, `ExportRedactionTest`,
`ReportMarkdownExportTest`, `WordOdtMarkdownTest`,
`ZippedHtmlNamingTest`, `SemanticSearchChoicesTest`,
`BulkExportUnpackTest`, `BrokenWorkPolicyTest`,
`TranslationGroupingTest`, `MarkdownTablesTest`); and roughly
**27 instrumented files** under `ai/src/androidTest/java/com/ai/`
(several are shared harnesses — `BottomBarHarness`,
`PersistentStateGuard`, `TestProvider`) covering the Compose UI
(`ChatsHubScreenTest`, `ReportsHubScreenTest`, `ContentDisplayTest`,
`SharedComponentsTest`, `TitleBarTest`, `HelpScreenTest`,
`HousekeepingScreenTest`, `ReportExportScreenTest`,
`TranslationCompareScreenTest`, `HomeBarModeScreenTest`,
`ReportLauncherScreensInstrumentedTest`,
`ReportChangeResultScreensInstrumentedTest`) and device-side data
plumbing (`ChatHistoryManagerInstrumentedTest`,
`ProviderRegistryInstrumentedTest`, `ReportStorageInstrumentedTest`,
`SecondaryResultStorageInstrumentedTest`,
`ApiTracerInstrumentedTest`, `PricingCacheInstrumentedTest`, plus the
export builders `ZippedHtmlBuildInstrumentedTest`,
`DocxOdtBuildInstrumentedTest`, `BulkExportInstrumentedTest`,
`BuildHtmlReportDataInstrumentedTest`,
`JsonTraceZipInstrumentedTest`).

> **Important:** AGP's `connectedDebugAndroidTest` **uninstalls
> `com.ai`** after running, deleting the data dir. If the emulator
> has user data you want to preserve, snapshot it first:
> ```
> adb exec-out run-as com.ai tar -cf - shared_prefs files > /tmp/com.ai.snapshot.tar
> ```
> and push it back after the test run + reinstall. This is the
> "extended cycle" referenced in `CLAUDE.md`. The default cycle is
> build → deploy to both targets → launch → confirm foreground →
> commit, with **no** tests or snapshot/restore.

When changing a flow (especially generation, retry, or persistence),
**run an actual report** before declaring success. Type-checking and
unit tests verify code correctness, not feature correctness here.

## Common gotchas

- **Anthropic `max_tokens` is supplied at dispatch, not hardcoded.**
  The `ClaudeRequest` field is nullable; `defaultMaxTokens` resolves
  in order: `service.maxTokensDefaults.resolveMaxTokens(model)` (the
  per-provider config) → the model's models.dev max-output window
  (capped against its context window minus `INPUT_HEADROOM`) → a
  static **4096** fallback. That default is also applied to
  OpenAI-compatible calls (to avoid OpenRouter balance-gating 402s),
  not Anthropic-only. `claudeReasoningBundle` bumps `max_tokens` above
  the thinking `budget_tokens` when needed and logs the override.
- **Google auth uses a `?key=` query param**, URL-encoded, not a
  Bearer token.
- **OpenAI dual API**: `gpt-4o`-class uses Chat Completions; `gpt-5.x`
  / `o3` / `o4` / `gpt-4.1` (and `o1` via config) use the Responses
  API. Routed via `usesResponsesApi(service, model)` =
  `service.responsesApiPatterns.anyMatches(model)` **or**
  `ModelType.infer(model) == RESPONSES`. There is no `endpointRules`
  field — the old prefix list now lives in `ModelType.infer`. On the
  Responses path the system prompt goes to `instructions`, not a
  message, and multi-text blocks are concatenated by the dispatch
  layer.
- **OpenAI's `/v1/models` omits moderation / TTS / image / STT
  models.** OpenAI carries `mergeHardcodedModels: true` in its
  `assets/providers/OpenAI.json` definition; `Settings.withModels` unions
  `service.hardcodedModels` into the fetched list **only** for
  providers with that flag, so those picker entries survive a
  refresh. (If you need a specific OpenAI model to reappear in a
  picker, add it to that provider's `hardcodedModels` array — OpenAI
  currently ships none, relying on the catalog.)
- **Rate-limit retry is OFF main thread.** Both
  `RateLimitRetryInterceptor` (429) and `OverloadedRetryInterceptor`
  (529, Anthropic `overloaded_error`) have an explicit
  `Looper.myLooper() == getMainLooper()` guard — don't remove it.
  They have **independent budgets**. Defaults are **3 retries with
  1000 ms** base backoff (`NetworkSettings.maxRetriesOn429 /
  retryBackoffMs429`, and the `…529` siblings), exponential with
  ±50% jitter, capped at 30 s, honoring server `Retry-After`. Caps
  resolve per host via `ProviderThrottle.retryLimitsFor429(host)` /
  `retryLimitsFor529(host)` (per-provider override → global default).
  Anthropic ships a stricter 529 override (5 retries, 5000 ms) in
  `assets/providers/Anthropic.json`. Setting retries to 0 disables the
  in-line loop;
  the outer `AnalysisRepository.withRetry` still gets one more
  attempt and treats 408 / 425 / 429 as transient.
  (CLAUDE.md's "5× with 3 s back-off" is stale.)
- **Two throttle layers.** `ProviderThrottle` is per-**hostname**:
  one semaphore (concurrency) + sliding-window deque (per-minute
  rate) per host, caps = per-provider override → global default.
  Separately, `ApiCallCaps` (`ApiTracer.kt`) is a set of
  coroutine-level `Semaphore` pools, each with its own knob —
  `global` (init 100), `report` / `translation` / `fanOut` /
  `fanMeta` / `workers` (init 50 each). `resetForNewLimits(globalMax)`
  resizes **every** sub-cap to the global cap, so in practice only the
  single "Concurrent API calls" global limit binds. Batch flows
  acquire in the canonical **subCap → global → per-host** order via
  `runThrottledBatch` (`ThrottledBatch.kt`) / `acquireThrottledPermits`
  (`ReportViewModelHelpers.kt`). The key
  invariant (commit *report-primary caps*): while **parked** on a
  saturated per-host gate, the `PermitHold` releases both subCap and
  global and re-takes them on the next poll — so a flow's cap counts
  only items holding a live provider slot, not items queued behind a
  busy host. All 7 report-primary dispatch sites use this path now.
- **Pre-acquired flows must set `permitPreAcquired`.** Flows that
  pre-acquire the host gate (report, fan-out, fan-meta, translation)
  set `ProviderThrottle.permitPreAcquired` on the coroutine so the
  inline `ProviderThrottleInterceptor` skips its own acquire and
  doesn't double-count. Tag/permit context propagates across
  coroutine dispatcher hops via `kotlinx.coroutines.asContextElement`
  and onto OkHttp workers via `TagPropagatingExecutor`
  (`TagPropagation.kt`). Provider edits go through
  `ProviderRegistry.save`, which rebuilds the host index and calls
  `ProviderThrottle.resetForNewLimits()` — overrides take effect on
  the next acquire. See [throttle.md](throttle.md).
- **Read-timeout interceptor split.** `ReadTimeoutInterceptor` picks
  `streamingReadTimeoutSec` (default 240 s) vs
  `nonStreamingReadTimeoutSec` (default 120 s) based on the request
  (`:streamGenerateContent`, or `"stream":true` in the POST body);
  `TestCallTimeoutInterceptor` overrides to 30 s for the
  `Provider test` trace category. Without the split, every call
  would inherit the streaming timeout and a hung provider would gate
  a whole batch.
- **DNS-hang guard.** `withApiCallTimeout` (`ApiDispatch.kt`) wraps
  each single request / stream-open (not the SSE read loop) in a
  `withTimeout` so a DNS hang — which OkHttp's connect/read/write
  timeouts miss, since they start after DNS — surfaces as a plain
  `IOException`. `withHostGate` sits *outside* it so a legitimate
  per-minute wait doesn't trip the guard.
- **Storage flat-id validation.** `isSafeFlatId` (non-blank, not
  `.` / `..`, no `/` or `\`) gates the write side of
  `ReportStorage.saveReport / deleteReport` and
  `ChatHistoryManager.saveChatSession`. `SecondaryResultStorage`
  applies the equivalent flat-id + canonical-containment check in
  its `reportDir` (write) / `resolveReportDirForRead` (read) helpers
  so a malformed/restored id can't escape `secondary/`. `Knowledge`
  storage applies `isSafeKbId` / `isSafeSourceId` + containment.
- **ProviderFieldTimestamps.** Per-provider per-field user-edit
  timestamps live in a separate prefs file
  (`provider_field_timestamps`). `ProviderRegistry.update` bumps a
  field's timestamp when its value changes; the every-start
  asset-sync (`syncFromAsset`) skips fields whose timestamp is
  non-null, so a bundled-asset refresh never overwrites a user edit.
  This prefs file is **not** backed up — it's a recomputable cache.
- **Backup zip** mirrors `filesDir` (incl. `reports/`, `secondary/`,
  `trace/`, `embeddings/`, `pricing/`, `model_lists/`,
  `prompt_cache/`, `regenerate/`, `knowledge/`, `audit/`, `crash/`)
  minus the **four** `FILES_DIR_BACKUP_EXCLUDES` subdirs
  (`local_llms/`, `local_models/`, `native/`, `applog/`), plus the
  **7** SharedPreferences files in `PREFS_TO_BACKUP` (`eval_prefs`,
  `provider_registry`, `pricing_cache`, `dual_chat_prefs`,
  `huggingface_cache`, `model_cooldowns`, `view_screen_prefs`), plus
  a `cacheDir` mirror minus the in-flight temp prefixes
  (`ai-restore-`, `reset_keys_`, `ai-backup-` — `reset_keys_` holds
  plaintext keys and must never be archived). A new prefs file
  won't survive a restore unless added to `PREFS_TO_BACKUP`. Restore
  is **validate-then-write**: read the whole zip into memory
  (zip-bomb caps: 256 MB/entry, 1 GB total), commit prefs, *then*
  wipe + rewrite `filesDir`/`cacheDir`, so a crash mid-restore
  leaves a re-restorable state. See
  [backup-restore.md](backup-restore.md).
- **Manifest version is `1`.** `BackupManager.MANIFEST_VERSION = 1`;
  restore accepts exactly version 1. Bump only when the backup
  format changes in a way an old restore can't read. (This is
  separate from per-report `EXPORT_VERSION = 1` in `ReportBundle.kt`,
  which governs the single-report import/export zip.)
- **No single bundled catalog file.** The bundled assets are now split
  into per-item directories, each read by its own `*Seed` object:
  the provider catalog is `assets/providers/` (one bare
  `ProviderDefinition` JSON per provider, 48 files); System Prompts
  are `assets/prompts/system/` (`SystemPromptSeed`); Example Prompts
  are `assets/prompts/examples/` (`ExamplePromptSeed`); Internal
  Prompts are `assets/internal-prompts/<Language>/<category>/`
  (`<name>.json` sidecar + `<name>.txt` body, `InternalPromptSeed`);
  swarms are `assets/workers/swarms/` (`SwarmSeed`) and flocks
  `assets/workers/flocks/` (`FlockSeed`). The remaining single-file
  seeds are `assets/excluded.json` (`TestExcludedSeed`),
  `assets/inaccessible.json` (`InaccessibleSeed`) and
  `assets/meta.json` (`DefaultMetaItemSeed`). None carry a top-level
  version. (Stale in-code comments / KDoc still mention `setup.json`,
  `providers.json`, `examples.json` — ignore them; only the wire/import
  format kept those flat shapes, not the on-disk layout.)
- **`ApiFactory.fetchUrlAsString` is preferred over `URL.openStream`**
  for ad-hoc HTTP gets so the call goes through `TracingInterceptor`
  and the retry interceptors like everything else.
- **Two `huggingface` keys**: `huggingface_api_key` (External
  Services → HuggingFace, for HF model-info lookups, with its own
  `huggingface_cache` prefs + 7-day TTL) and the optional
  `HuggingFace` provider in `assets/providers/HuggingFace.json` (HF
  Inference API, used as a chat provider). Separate things even though
  both prompt for an HF token.
- **`AppService.id` is the only name field.** The legacy
  `displayName` / `prefsKey` collapsed into `id` in the
  id-unification refactor; there are no backwards-compat migrations.
- **Atomic writes are required for prefs / pricing / secondary / file
  writes.** Use `AtomicFileWrite.writeTextAtomic` (tmp file fsync +
  `Files.move ATOMIC_MOVE` + parent-dir auto-mkdir). Bare
  `File.writeText` leaves a half-written file on crash. The report
  export builders (`PdfExport`, `ReportExport`, `ZippedHtmlExport`,
  `WordOdtExport`) and the local-runtime downloads use the same
  stage-as-`.part` + atomic-rename pattern so a process kill can't
  surface a torn artifact.
- **Icons come from the worker engine only.** Per-report icon
  (`Report.icon`, from `workers/report-icon`, derived from the long
  title) and per-model icon (`ReportAgent.icon`, from
  `workers/model-icons`, derived from that model's *title*). The
  legacy response-based 3-tier per-agent fallback chain has been
  **removed**; `ReportAgent.iconWinningTier` is now always null. Some
  surviving KDoc/comments in `IconGenerationManager.kt` still
  describe a "3-tier chain" — they are stale. Find-alternative
  variants live under category `alt/`. See
  [report-icons.md](report-icons.md).
- **Background continuation.** Initial report generation, regenerate
  (the phased `RegenerateBatchEngine`), secondary launches (rerank /
  meta / moderation / translate / tournament / judges / compare /
  rank-translators), and the icon flows are all launched on
  `appViewModel.viewModelScope`
  (not a report-VM scope) so navigating away doesn't cancel the work.
  The result screen recovers stale placeholders on entry via
  `restoreCompletedReport` / `hydrateAgentResultsFromStorage`. A 30 s
  read-only background scan (`startBackgroundBrokenScan`) *detects*
  interrupted secondary/regenerate runs and surfaces them on the ⚠️
  Broken-work screen — it no longer auto-resumes them; the user
  Restarts / retries manually. `deleteReport` cancels every job
  registered under its `reportId`.
- **Removed-agent progress bump.** `executeReportTask` counts each
  launch-time agent slot into a fixed `genericReportsTotal`; every
  early-return branch (benched-model skip, **and** the
  removed-mid-run agent branch) must still bump
  `genericReportsProgress`, or the report hangs at "generating"
  forever (KEEP_SCREEN_ON held, no completion toast).

## First-run seeding + every-start delta-merge

`AppViewModel.bootstrap` (log tag `App.bootstrap`) runs a delta
merge of bundled assets on **every** app start, not just fresh
install. The first-run gate is `KEY_FIRST_RUN_BOOTSTRAPPED` in
`eval_prefs`.

- `assets/providers/` — new entries are appended on every start
  (`importFromAsset`). Per-field updates are gated by
  `ProviderFieldTimestamps`: a field the user has edited (non-null
  timestamp) is left alone; an un-edited field tracks the asset
  (`syncFromAsset`).
- `assets/internal-prompts/<Language>/<category>/` — bundled rows
  missing by `(category, name)` are added; existing rows are never
  overwritten (`InternalPromptSeed.ensureAllPresent`). So shipping new
  bundled prompts reaches existing installs on the next cold start.
- `assets/prompts/examples/` — delta-merged by title
  (`ExamplePromptSeed.ensureAllPresent`).
- `assets/prompts/system/`, `assets/workers/swarms/`,
  `assets/workers/flocks/`, `assets/excluded.json`,
  `assets/inaccessible.json`, `assets/meta.json` — each delta-merged
  the same way (`SystemPromptSeed` / `SwarmSeed` / `FlockSeed` /
  `TestExcludedSeed` / `InaccessibleSeed` / `DefaultMetaItemSeed`,
  all `ensureAllPresent`), appending only missing rows.

There is no one-shot bundled-prompt migration block — only the
every-start delta-merges above remain.

Each bootstrap action is bracketed by DEBUG `→` / `←` (start +
end+duration) log lines under the `App.bootstrap` tag, with the
per-action detail at TRACE so a default WARN/ERROR threshold stays
quiet. Tail the AppLog viewer (Hub → AI App log) at TRACE during
development to see the full startup path. (The build timestamp is
read at runtime from `assets/build-timestamp.txt`, not a
`BuildConfig` constant — the old `BUILD_TIMESTAMP` field was removed
because the config cache made it go stale.)

## Capability precompute

`Settings` keeps precomputed vision / web-search / reasoning sets
(`recomputeAllCapabilities` / `recomputeCapabilities`), refreshed
after a LiteLLM or models.dev catalog refresh so the picker badges
pick up new catalog answers (Helicone is pricing-only — no
recompute). `withModels` funnels every model list through
`List.distinct` so duplicates can't crash the keyed LazyColumns.
There is **no** `CAPS_PRECOMPUTED_VERSION` migration flag in the
current code — capability sets are recomputed eagerly on the
relevant refresh, not gated behind a version constant.
