# Development Guide

For developers maintaining or extending this app. Pairs with
[architecture.md](architecture.md) (the bigger picture) and
[datastructures.md](datastructures.md) (the data classes).

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :ai:assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :ai:assembleRelease
```

Toolchain: Kotlin 2.2.10, AGP 9.2.0, Gradle 9.5, Java 17 (JVM
target 17, source/target compatibility 17), Compose BOM 2026.04.01.
`compileSdk = 36`, `buildToolsVersion = "36.1.0"`, `minSdk = 26`,
`targetSdk = 36`, namespace `com.ai`. Release builds enable
`isMinifyEnabled` and `isShrinkResources` and require
`local.properties` to define `KEYSTORE_FILE` /
`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` — the build
fails loudly otherwise rather than emitting an unsigned APK.

Network timeouts ship as `BuildConfig.NETWORK_*_TIMEOUT_SEC` so the
OkHttp client can read them at construction time without a prefs
round-trip:

| BuildConfig field | Value | Used for |
|---|---|---|
| `NETWORK_CONNECT_TIMEOUT_SEC` | 30 | TCP / TLS handshake |
| `NETWORK_READ_TIMEOUT_SEC` | 600 | Streaming reads (10 min cap on long SSE) |
| `NETWORK_WRITE_TIMEOUT_SEC` | 30 | Request body upload |
| `TEST_CONNECTION_READ_TIMEOUT_SEC` | 120 | Per-provider Test API Key call |

The `versionName` is computed at build time as `YY.DDD.MIN` (year
% 100 + day-of-year + minutes-of-day) so two builds the same minute
share a version.

A few notable runtime dependencies (full list in `ai/build.gradle.kts`):
- **MediaPipe Tasks GenAI 0.10.35** — on-device LLM (`LocalLlm`).
  Pinned at 0.10.35 for 16 KB page-size compliance on Android 15+.
- **MediaPipe Tasks Text 0.10.35** — on-device text embedder
  (`LocalEmbedder`). Wraps a LiteRT runtime so the app can run
  `.tflite` text-embedder models for on-device semantic search.
- **ML Kit Text Recognition 16.0.1** — Latin model bundled, so OCR
  works offline without Google Play Services. Used as the fallback
  in the Knowledge ingestion path for image-only PDFs / standalone
  images. (Was MediaPipe Text Recognition; switched for offline +
  bundled-model guarantees.)
- **PDFBox-Android 2.0.27.0** — PDF text extraction
- **Jsoup 1.18.1** — HTML extraction for URL-type Knowledge sources
- **Apache Commons Compress 1.27.1** — `.zip` / `.tar` / `.tar.gz` /
  `.tgz` extraction in the Local LLM archive importer (Kaggle ships
  some Gemma bundles as `.tgz`)

A `constraints { implementation("com.google.guava:guava:33.4.3-android") }`
override pins Guava away from MediaPipe's transitive 27.0.1, which
otherwise breaks Truth at instrumented-test runtime via AGP's
consistent-resolution.

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
adb logcat | grep -E "AiAnalysis|ApiDispatch|ApiTracer|AppViewModel|AtomicFileWrite|BackupManager|ChatHistoryManager|ImportExport|KnowledgeService|LocalEmbedder|LocalLlm|LocalRuntime|ModelListCache|PricingCache|ProviderRegistry|ReportExport|ReportStorage|SettingsExport"
```

The in-app **API Traces** screen (Hub → AI API Traces) is usually a
faster way to inspect what was sent / received during a report run —
each call gets a JSON file under `<filesDir>/trace/` while
`ApiTracer.isTracingEnabled` is on (it's on by default but can be
toggled in Settings). On-device LLM and on-device embedder calls
trace too with hostname `local`.

## Project layout

```
ai/src/main/java/com/ai/
├── MainActivity.kt
├── data/                              # 30 files
│   ├── (HTTP, dispatch, streaming, tracer, registry, pricing, …)
│   ├── AnalysisRepository.kt   ApiClient.kt     ApiDispatch.kt
│   ├── ApiFormat.kt            ApiModels.kt     ApiStreaming.kt
│   ├── ApiTracer.kt            AppService.kt    AtomicFileWrite.kt
│   ├── BackupManager.kt        ChatHistoryManager.kt   DataModels.kt
│   ├── EmbeddingsStore.kt      ExamplePromptSeed.kt
│   ├── HuggingFaceCache.kt     ImageAttach.kt
│   ├── InternalPromptSeed.kt
│   ├── Knowledge.kt + KnowledgeService.kt + KnowledgeExtractors.kt
│   ├── LocalEmbedder.kt + LocalLlm.kt
│   ├── ModelListCache.kt       ModelType.kt     PricingCache.kt
│   ├── PromptCache.kt          ProviderRegistry.kt
│   ├── ReportStorage.kt        SecondaryResult.kt   SharedContent.kt
├── model/                             # 2 files
│   ├── SettingsModels.kt + SettingsHolder.kt
├── viewmodel/                         # 3 files
│   ├── AppViewModel.kt + ChatViewModel.kt + ReportViewModel.kt
└── ui/                                # 75 files
    ├── navigation/  (2)               # AppNavHost, NavRoutes
    ├── hub/         (1)               # main hub + Reports / Chats hubs
    ├── report/      (21)              # report flows, secondary results,
    │                                  # Fan-out / Fan-in screens, exports
    │                                  # (PDF, DOCX/ODT, RTF, zipped HTML),
    │                                  # translation screens
    ├── chat/        (5)               # chat + chat history + dual chat
    ├── knowledge/   (1)               # KB list + detail
    ├── search/      (4)               # Quick / Extended local + Local /
    │                                  # Remote semantic search screens
    ├── share/       (1)               # ShareChooserScreen
    ├── models/      (1)               # model search + Model Info
    ├── history/     (3)               # report history + prompt history
    │                                  # + example-prompt picker
    ├── settings/    (18)              # all AI Setup sub-screens
    ├── admin/       (10)              # Housekeeping / Backup-Restore /
    │                                  # Reset / Trim by age / Usage stats /
    │                                  # statistics / traces / help /
    │                                  # provider admin / developer
    ├── shared/      (7)               # CrudListScreen, TitleBar,
    │                                  # AppColors, CameraCapture,
    │                                  # CsvHelpers, SelectionScreens,
    │                                  # SharedComponents, UiFormatting
    └── theme/       (1)               # Material3 dark theme
```

Roughly **112 Kotlin files, ~52,300 LOC** total.

## Adding things

### A new OpenAI-compatible provider

1. Add an entry to `assets/providers.json` under `providers`.
   Required: `id`, `baseUrl`, `adminUrl`, `defaultModel`. Optional:
   `apiFormat` (defaults to `OPENAI_COMPATIBLE`), `openRouterName`,
   `litellmPrefix`, `hardcodedModels`, `defaultModelSource`,
   `modelFilter`, `typePaths`, `modelsPath`, etc.

   Note: `id` is now the **only** name field — no separate
   `displayName` or `prefsKey`. UI shows `id` directly; SharedPreferences
   key prefixes use `id` directly (e.g. `OpenAI_api_key`).
2. If the provider exposes a different `chatPath` or `modelsPath`,
   set `typePaths.chat` and `modelsPath`.
3. The bundled JSON has no top-level `version` field. The
   provider catalog ships as a flat `{"providers": [...]}` shape.

The first run after install reads `providers.json` and persists each
provider into `ProviderRegistry`. To force a re-import on an existing
install: AI Setup → Providers → Refresh providers (or
Housekeeping → Reset → Refresh bundled assets, which re-pulls
`providers.json` *and* `prompts.json`).

`ProviderRegistry.importFromAsset` rejects duplicate ids, drops
malformed provider entries instead of NPEing later, and case-
insensitively dedups when merging.

### A non-OpenAI-compatible provider

1. All of the above, plus `apiFormat` set to a new `ApiFormat` enum
   value.
2. Add format-specific code paths in `ApiDispatch.kt` and
   `ApiStreaming.kt` (request building, response parsing, SSE
   parser).
3. The 40 OpenAI-compatible providers all share `OPENAI_COMPATIBLE`;
   only `ANTHROPIC` and `GOOGLE` have their own paths. Mirror those
   if the new provider is genuinely different.

### A new agent parameter

1. Add to `AgentParameters` (and `ChatParameters` if it should also
   appear in chat) in `data/DataModels.kt`, and to the `Parameters`
   preset class in `model/SettingsModels.kt`.
2. Add UI inputs in `AgentEditScreen` (`AgentsScreen.kt`) and
   `ParametersEditScreen` (`ParametersScreen.kt`).
3. Update the request model in `ApiModels.kt` (per `ApiFormat`).
4. Pass it through in `ApiDispatch.kt` and the streaming methods.
5. If it should also be settable per-turn from the chat session
   (like reasoning effort), wire it in `ChatSessionScreen`.

### A new pricing tier

1. Add a fetch function in `PricingCache.kt` (mirror
   `fetchHeliconeOnline` or `fetchLLMPricesOnline`).
2. Add SharedPreferences keys for `<tier>_pricing` and
   `<tier>_timestamp`, plus the on-disk file under
   `<filesDir>/pricing/<tier>_pricing.json` (and a `_meta.json`
   sidecar for capability tiers). Bump to a `_v2` key if you need
   to invalidate stale data after a parser revision.
3. Insert the tier into the lookup chain in `lookupPricing` and
   `getTierBreakdown`. Manual user overrides stay above the tier
   chain (in `getPricing`, after provider self-report and before
   the curated tiers).
4. Wire a card on `RefreshScreen` and (if a free API key is required)
   a field on `ExternalServicesScreen`.
5. Add the tier as a new column in the layered-cost CSV export
   (`ImportExportScreen.kt`).
6. Add the tier as a Source button on the Model Info screen.
7. Add a per-provider help page entry under `HelpScreen.kt` with an
   ℹ deep link from the Source button.

### A new SecondaryKind (after RERANK / META / MODERATION / TRANSLATE)

Most "I want a new analysis on report outputs" cases are covered
by adding a Meta-prompt entry under Settings → AI Setup → Prompt
management — no code changes needed. Add a new `SecondaryKind`
enum value only when the new flow has fundamentally different
routing (different API endpoint shape, different result schema,
different rendering).

Add an enum value to `SecondaryKind` in `data/SecondaryResult.kt`.
The Kotlin compiler will then enforce exhaustive `when` on every
site that maps `kind` to a string / colour / button label / view
title / prompt template. Walk the resulting compile errors:

- `legacyKindDisplayName` mapping
- `SecondaryResultStorage.Counts` + `countForReport`
- `ReportViewModel.metaTypeToKind` (logs unknown meta prompt types
  rather than throwing) and `executeSecondaryTask`
- `ReportScreen` running-row "type" cell
- `SecondaryResultsScreen` routing (which view to render —
  picker, table, drill-in list)
- `ContentDisplay.ReportCostTable` row mapping
- `ReportExport.renderLanguageBlock` view-picker tabs +
  `renderMetaCard` rendering + costs Type column
- `WordOdtExport.appendLanguageContent` + `appendCosts` Type
  column
- `ZippedHtmlExport.emitLanguageSections` +
  `languageSections` + `buildReportIndex` + `secondaryPage`
  branches + costs Type column
- `PdfExport.buildShortHtml` filter

The same pattern documents itself; lean on the compiler.

> Note: model-scoped fan-in adds a parallel surface — three
> Internal Prompt categories (`initiator`, `requester`, `model`)
> drive runs whose row carries `scopeProviderId` / `scopeModel`
> so the L2 page surfaces them under their own (provider, model)
> bucket distinct from the legacy total `fan_in`. The resolver is
> `resolveModelFanInPrompt` (separate from `resolveFanInPrompt`)
> with placeholders `@INITIATOR@`, `@RESPONDERS@`,
> `@RESPONDER_PAIRS@`. Adding a new SecondaryKind that needs a
> similar model-scoped flavour means walking those sites too.

### A new Knowledge source type

1. Add an enum value to `KnowledgeSourceType` in
   `data/Knowledge.kt`.
2. Add an extractor function in `KnowledgeExtractors.kt` and a
   dispatch arm in the `extract()` `when`.
3. Update `pickTypeForUri` in `ui/knowledge/KnowledgeScreens.kt` so
   the SAF picker recognises the extension.
4. Add the matching MIME types to the SAF picker's filter array in
   `KnowledgeDetailScreen` and to the share-target filters in
   `AndroidManifest.xml`.
5. (Optional) Add the type to the Source-type icon mapping if you
   want a distinct emoji/icon on the source row.

The chunker, embedder, and retriever are type-agnostic — once an
extractor returns a string, every downstream stage works unchanged.

### A new local model (downloadable embedder)

`LocalEmbedder.downloadable` is a hardcoded list of
`DownloadableModel` entries. Append a new one with `name`,
`displayName`, `url`, `sizeMbHint`, `description`. The model file
must have proper MediaPipe Tasks metadata baked in or the runtime
will refuse to load it — that's why the curated list is short. SAF
"Add model from file" handles user-supplied `.tflite` with stamped
metadata.

`LocalLlm.recommendedLinks` is the equivalent for `.task` LLMs but
ships **hand-off links only** (no in-app downloads) because most
worthwhile models require accepting model-card terms in a browser
first. The user accepts on Kaggle / HuggingFace, downloads, then
imports via the SAF picker (which handles `.task`, `.zip`, `.tar.gz`,
`.tgz`, `.tar`).

### A new Internal Prompt category

Internal prompts have a free-form `category` string —
`"meta"`, `"fan_out"`, `"fan_in"`, `"internal"` are the four in
use today. Adding a new one means:

1. Bump the `assets/prompts.json` to seed a default row.
2. Add a sub-hub entry to `SetupScreens.kt` →
   `InternalPromptsHubScreen` (the place that counts entries by
   category and routes to `settingsInternalPromptsByCategory`).
3. Add UI for the new category to `InternalPromptsScreen.kt` if
   the title / Add label needs to differ.
4. The runtime only inspects `category` from
   `ReportViewModel.runFanOutPrompt` / `runFanInPrompt` /
   `runMetaPrompt`; new categories with new behaviour need their
   own entry point.

### A new Help topic / help page

`ui/admin/HelpScreen.kt` has the full topic catalog (~88 topics).
The TitleBar's ❓ takes a `helpTopic` string; help routes resolve
it to an entry. Trace category → help topic mapping lives in the
same file (`HelpResolver`) so a captured trace can deep-link from
its ℹ to the most relevant page. Per-provider help pages share the
infrastructure.

## Testing

Two layered suites:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test                         # unit (fast)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew connectedDebugAndroidTest    # instrumented (~40s on emulator)
```

Unit tests use Truth; instrumented tests do too. Roughly **26
unit-test files** under `ai/src/test/java/com/ai/` covering
`ApiDispatchHelpers`, `BuildChatUrl`, `ResponsesUrl`,
`TracerTags`, `DefaultClaudeMaxTokens`, `ModelType`,
`EmbeddingsStore`, `SecondaryResultHelpers`, `ApiModelsUsage`,
`BackupManagerRestore`, `AtomicFileWrite`, `SettingsGraph`,
`ProviderDefinition`, `DualChatParameters`,
`SettingsPreferencesUsageStats`, `UiFormatting`,
`TraceTranslationExtraction`, `TraceSharePath`, …; and roughly
**22 instrumented tests** under `ai/src/androidTest/java/com/ai/`
covering the Compose UI (`ChatsHubScreenTest`,
`ReportsHubScreenTest`, `ContentDisplayTest`,
`SharedComponentsTest`, `TitleBarTest`, `HelpScreenTest`,
`HousekeepingScreenTest`, `ReportExportScreenTest`,
`TranslationCompareScreenTest`) and on-device data plumbing
(`ChatHistoryManagerInstrumentedTest`,
`ProviderRegistryInstrumentedTest`,
`ReportStorageInstrumentedTest`,
`SecondaryResultStorageInstrumentedTest`,
`ApiTracerInstrumentedTest`, `PricingCacheInstrumentedTest`,
plus the export builders `ZippedHtmlBuildInstrumentedTest`,
`DocxOdtBuildInstrumentedTest`, `BulkExportInstrumentedTest`).

> **Important:** AGP's `connectedDebugAndroidTest` **uninstalls
> `com.ai`** after running, deleting the data dir. If the emulator
> has user data you want to preserve, snapshot it first:
> ```
> adb exec-out run-as com.ai tar -cf - shared_prefs files > /tmp/com.ai.snapshot.tar
> ```
> and push it back after the test run + reinstall. This is the
> "extended cycle" referenced in `CLAUDE.md`.

When changing a flow (especially generation, retry, persistence, or
RAG injection), **run an actual report** before declaring success.
Type-checking and unit tests verify code correctness, not feature
correctness here.

## Common gotchas

- **Anthropic `max_tokens` is required** (defaults to 4096). OpenAI
  treats it as optional. The dispatcher logs the override when
  reasoning is on.
- **Google auth uses `?key=` query param**, URL-encoded, not Bearer
  token.
- **OpenAI dual API**: gpt-4o etc. use Chat Completions; gpt-5.x /
  o3 / o4 / gpt-4.1 use Responses API. Auto-routed via
  `usesResponsesApi()` / `endpointRules`. Multi-text Responses-API
  blocks are concatenated by the dispatch layer.
- **OpenAI moderation models** aren't returned by `/v1/models`. They
  ship as `hardcodedModels` in `providers.json` so the picker still
  finds them. The OpenAI-only fallback union in `Settings.withModels`
  preserves them across refreshes.
- **Rate-limit retry is OFF main thread.** The interceptor has an
  explicit `Looper.myLooper() == getMainLooper()` guard. Don't
  remove it.
- **Backup zip** mirrors `filesDir` (incl. `knowledge/`,
  `embeddings/`, `secondary/`, `trace/`, `pricing/`, `model_lists/`,
  `prompt_cache/`) and 5 SharedPreferences files. Two top-level
  subdirs are explicitly excluded via
  `BackupManager.FILES_DIR_BACKUP_EXCLUDES` — `local_llms/` and
  `local_models/`, holding user-supplied multi-GB on-device model
  bundles. The same set is **also** preserved through
  `clearFilesDirForRestore`, so a settings restore on a device with
  local models installed doesn't destroy them. New prefs files
  won't survive a restore unless added to
  `BackupManager.PREFS_TO_BACKUP`. The backup zip also mirrors
  `cacheDir` (exports, shared traces) but skips in-flight temp
  files (`ai-restore-`, `reset_keys_`, `ai-backup-`). See
  [backup-restore.md](backup-restore.md).
- **Manifest version is `1`.** The backup format is intentionally
  un-versioned today — bump `MANIFEST_VERSION` only when the format
  changes in a way an old restore can't read.
- **No bundled `setup.json` anymore.** The provider catalog is
  `assets/providers.json` and ships in a flat
  `{"providers": [...]}` shape — the older `version + providerDefinitions`
  schema is gone. Internal Prompts come from `assets/prompts.json`,
  Example Prompts from `assets/examples.json`. None of the three
  carry a top-level version.
- **`ApiFactory.fetchUrlAsString` is preferred over
  `URL.openStream`** for ad-hoc HTTP gets so the call goes through
  `TracingInterceptor` and `RateLimitRetryInterceptor` like
  everything else.
- **Two `huggingface` keys**: `huggingFaceApiKey` (External Services
  → HuggingFace, used for HF model-info lookups) and the optional
  `HuggingFace` provider in `providers.json` (HF Inference API,
  used as a chat provider). They are separate things even if both
  prompt for an HF token.
- **Custom providers added by the user** live in the
  `provider_registry` prefs file, separate from `eval_prefs`. The
  backup zip captures both.
- **`AppService.id` is the only name field.** The legacy
  `displayName` / `prefsKey` collapsed into `id` in the
  id-unification refactor; backwards-compat migrations were
  stripped. Existing installs upgraded transparently because the
  registry already carried the unified ids.
- **`AppService.LOCAL` isn't in `ProviderRegistry`.** It's a
  sentinel singleton found via `findById("Local")` (case-insensitive
  for compat with persisted `"LOCAL"` strings). Persisted
  ChatSessions whose provider was Local can be reloaded across
  launches because the deserializer routes the id to the sentinel.
- **`LocalLlm.generate` is synchronous and not streaming.** The
  MediaPipe API in this version doesn't expose a partial-token
  callback through the Kotlin binding; the chat session shows the
  full reply when the call returns. Per-native-handle calls are
  serialised so two consumers of the same `.task` don't race.
- **Truth + MediaPipe** ship incompatible Guava transitive
  dependencies. The `ai/build.gradle.kts` constraint pinning Guava
  to `33.4.3-android` is required — without it the instrumented
  test suite VerifyError-s on every Truth assertion.
- **Atomic writes are required for prefs / KB / pricing /
  secondary writes.** Use `AtomicFileWrite.writeTextAtomic`
  (`Files.move ATOMIC_MOVE` + tmp file fsync + parent-dir auto-
  mkdir). Bare `File.writeText` leaves a half-written file on
  crash — the sweep across the codebase fixed this in dozens of
  call sites.

## First-run seeding

`AppViewModel.bootstrap` runs once per fresh install (gated by
`first_run_bootstrapped` in eval_prefs):

- Imports `assets/providers.json` into `ProviderRegistry` (only
  if the registry is empty AND `Settings.internalPrompts` is empty,
  i.e. genuinely fresh).
- Loads `assets/prompts.json` and merges any rows missing by
  `(category, name)` into `Settings.internalPrompts`. Existing
  rows are never overwritten — re-seeding is idempotent.

Example prompts are loaded **on demand** (Settings → AI Setup →
Prompt management → Example prompts → Load bundled examples).
Title match is case-insensitive.

## Versioning the precompute migration

`AppViewModel` has a `CAPS_PRECOMPUTED_VERSION` constant gated by
`KEY_CAPS_PRECOMPUTED_VERSION` in prefs. Bump the constant when the
precompute logic changes and the existing on-disk data needs to be
recomputed; the bootstrap pass will run once on the next launch and
update the flag. The check is per-provider — providers that already
have populated precomputed data are skipped.
