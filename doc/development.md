# Development Guide

For developers maintaining or extending this app. Pairs with
[architecture.md](architecture.md) (the bigger picture) and
[datastructures.md](datastructures.md) (the data classes).

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

Toolchain: Kotlin 2.2.10, AGP 9.2.0, Gradle 9.5, Java 17, Compose
BOM 2026.04.01. minSdk 26, targetSdk 36, namespace `com.ai`.

A few notable runtime dependencies (full list in `ai/build.gradle.kts`):
- **MediaPipe Tasks GenAI** — on-device LLM (`LocalLlm`)
- **MediaPipe Tasks Text** — on-device text embedder (`LocalEmbedder`)
- **MediaPipe Text Recognition (Latin)** — OCR for image-only PDFs
  and standalone images in the Knowledge ingest path
- **PDFBox-Android** — PDF text extraction
- **Jsoup** — HTML extraction for URL-type Knowledge sources
- **Apache Commons Compress** — `.tar.gz` extraction in the Local LLM
  archive importer

A `constraints { implementation("com.google.guava:guava:33.4.3-android") }`
override pins Guava away from MediaPipe's transitive 27.0.1, which
otherwise breaks Truth at instrumented-test runtime via AGP's
consistent-resolution.

## Deploy

```bash
adb install -r ai/build/outputs/apk/debug/ai-debug.apk
adb shell am start -n com.ai/.MainActivity
```

The convention used in this repo's `CLAUDE.md` is to also copy the APK
to `/Users/herbert/cloud/ai.apk` after every successful build.

## Logs

```bash
adb logcat | grep -E "(AiAnalysis|AiHistory|ApiTracer|PricingCache|AiReport)"
```

The in-app **API Traces** screen (Hub → AI API Traces) is usually a
faster way to inspect what was sent / received during a report run —
each call gets a JSON file under `<filesDir>/trace/` while
`ApiTracer.isTracingEnabled` is on (it always is in debug builds).
On-device LLM and on-device embedder calls trace too with hostname
`local`.

## Project layout

```
ai/src/main/java/com/ai/
├── MainActivity.kt
├── data/                              # data layer
│   ├── (HTTP, dispatch, streaming, tracer, registry, pricing, …)
│   ├── Knowledge.kt + KnowledgeService.kt + KnowledgeExtractors.kt
│   ├── EmbeddingsStore.kt
│   ├── LocalLlm.kt + LocalEmbedder.kt
│   └── SharedContent.kt
├── model/                             # data classes for settings + export
├── viewmodel/                         # AppViewModel, ChatViewModel, ReportViewModel
└── ui/
    ├── navigation/                    # AppNavHost, NavRoutes
    ├── hub/                           # main hub + Reports / Chats hubs
    ├── report/                        # report flows, secondary results,
    │                                  # exports (PDF, DOCX/ODT, RTF,
    │                                  # zipped HTML), translation screens
    ├── chat/                          # chat + chat history + dual chat
    ├── knowledge/                     # KB list + detail
    ├── search/                        # Quick / Extended local + Local /
    │                                  # Remote semantic search screens
    ├── share/                         # ShareChooserScreen
    ├── models/                        # model search + Model Info
    ├── history/                       # report history + prompt history
    ├── settings/                      # all settings sub-screens
    ├── admin/                         # statistics, housekeeping, traces, help
    ├── shared/                        # CrudListScreen, TitleBar, AppColors, ...
    └── theme/                         # Material3 dark theme
```

Roughly 100 Kotlin files, ~35k LOC.

## Adding things

### A new OpenAI-compatible provider

1. Add an entry to `assets/setup.json` under `providerDefinitions`.
   Required: `id`, `displayName`, `baseUrl`, `adminUrl`,
   `defaultModel`, `prefsKey`. Optional: `openRouterName`,
   `litellmPrefix`, `hardcodedModels`, `defaultModelSource`.
2. If the provider exposes a different `chatPath` or `modelsPath`,
   set `typePaths.chat` and `modelsPath`.
3. Bump the `version` in `setup.json` only if the schema itself
   changed (currently `21`). Adding providers does **not** require a
   bump.

The first run after install reads `setup.json` and persists each
provider into `ProviderRegistry`. To force a re-import on an existing
install: Settings → AI Setup → Import/Export → "Import setup".

### A non-OpenAI-compatible provider

1. All of the above, plus `apiFormat` set to a new `ApiFormat` enum
   value.
2. Add format-specific code paths in `ApiDispatch.kt` and
   `ApiStreaming.kt` (request building, response parsing, SSE
   parser).
3. The 37 OpenAI-compatible providers all share `OPENAI_COMPATIBLE`;
   only `ANTHROPIC` and `GOOGLE` have their own paths. Mirror those
   if the new provider is genuinely different.

### A new agent parameter

1. Add to `AgentParameters` (and `ChatParameters` if it should also
   appear in chat) in `data/DataModels.kt`, and to the `Parameters`
   preset class in `model/SettingsModels.kt`.
2. Add UI inputs in `AgentEditScreen` (`AgentsScreen.kt`) and
   `ParametersEditScreen` (`ParametersScreen.kt`).
3. Update `ParametersExport` (`ExportModels.kt`).
4. Update the request model in `ApiModels.kt` (per `ApiFormat`).
5. Pass it through in `ApiDispatch.kt` and the streaming methods.
6. If it should also be settable per-turn from the chat session
   (like reasoning effort), wire it in `ChatSessionScreen`.

### A new pricing tier

1. Add a fetch function in `PricingCache.kt` (mirror
   `fetchHeliconeOnline` or `fetchLLMPricesOnline`).
2. Add SharedPreferences keys for `<tier>_pricing` and
   `<tier>_timestamp`. Bump to a `_v2` key if you need to invalidate
   stale data after a parser revision.
3. Insert the tier into the lookup chain in `lookupPricing` and
   `getTierBreakdown`.
4. Wire a card on `RefreshScreen` and (if a free API key is required)
   a field on `ExternalServicesScreen`.
5. Add the tier as a new column in the layered-cost CSV export
   (`ImportExportScreen.kt`).
6. Add the tier as a Source button on the Model Info screen.

### A new SecondaryKind (after Rerank / Summarize / Compare / Moderate / Translate)

Add an enum value to `SecondaryKind` in `data/SecondaryResult.kt`.
The Kotlin compiler will then enforce exhaustive `when` on every
site that maps `kind` to a string / colour / button label / view
title / prompt template. Walk the resulting compile errors:

- `SecondaryPrompts.DEFAULT_*` constant
- `GeneralSettings.<kind>Prompt` field + prefs key + export field
- `SecondaryPromptsScreen` editor
- `ReportViewModel.resolveTemplate` / `executeSecondaryTask`
- `ReportScreen` action button + view button + scope-screen routing
- `SecondaryResultsScreen` titles
- `StatisticsScreen` kind colour
- `ContentDisplay.ReportCostTable` row mapping + colour
- `ReportExport.appendSecondarySections` rendering + CSS

The same pattern documents itself; lean on the compiler.

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

## Testing

Two layered suites:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test                         # unit (fast)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew connectedDebugAndroidTest    # instrumented (~40s on emulator)
```

Unit tests use Truth; instrumented tests do too. The instrumented
suite covers `ApiTracer`, `ChatHistoryManager`, `PricingCache`,
`SecondaryResultStorage`, the export builders (DocxOdt, ZippedHtml),
plus 25 Compose UI smoke tests under `ai/src/androidTest/java/com/ai/compose/`.

> **Important:** AGP's `connectedDebugAndroidTest` **uninstalls
> `com.ai`** after running, deleting the data dir. If the emulator
> has user data you want to preserve, snapshot it first:
> ```
> adb exec-out run-as com.ai tar -cf - shared_prefs files > /tmp/com.ai.snapshot.tar
> ```
> and push it back after the test run + reinstall.

When changing a flow (especially generation, retry, persistence, or
RAG injection), **run an actual report** before declaring success.
Type-checking and unit tests verify code correctness, not feature
correctness here.

## Common gotchas

- **Anthropic `max_tokens` is required** (defaults to 4096). OpenAI
  treats it as optional.
- **Google auth uses `?key=` query param**, not Bearer token.
- **OpenAI dual API**: gpt-4o etc. use Chat Completions; gpt-5.x /
  o3 / o4 / gpt-4.1 use Responses API. Auto-routed via
  `usesResponsesApi()` / `endpointRules`.
- **OpenAI moderation models** aren't returned by `/v1/models`. They
  ship as `hardcodedModels` in `setup.json` so the picker still finds
  them.
- **Rate-limit retry is OFF main thread.** The interceptor has an
  explicit `Looper.myLooper() == getMainLooper()` guard. Don't
  remove it.
- **Backup zip** mirrors `filesDir` (incl. `knowledge/`,
  `local_models/`, `local_llms/`, `embeddings/`, `secondary/`,
  `trace/`) and 5 SharedPreferences files; new prefs files won't
  survive a restore unless added to `BackupManager.PREFS_TO_BACKUP`.
- **Export version is `21`.** Import accepts `11..21`. Bump the
  version only when adding/removing a top-level field; new optional
  fields don't require a bump if old importers can ignore them
  safely.
- **`ApiFactory.fetchUrlAsString` is preferred over
  `URL.openStream`** for ad-hoc HTTP gets so the call goes through
  `TracingInterceptor` and `RateLimitRetryInterceptor` like
  everything else.
- **Two `huggingface` keys**: `huggingFaceApiKey` (External Services
  → HuggingFace, used for HF model-info lookups) and the optional
  `HUGGINGFACE` provider in `setup.json` (HF Inference API, used as a
  chat provider). They are separate things even if both prompt for an
  HF token.
- **Custom providers added by the user** live in the
  `provider_registry` prefs file, separate from `eval_prefs`. The
  backup zip captures both.
- **Only `assets/setup.json` lives in the APK now.** The previously
  bundled LiteLLM snapshot was removed — the user populates LiteLLM
  pricing by running Refresh → LiteLLM at first install. Reparsing a
  1.2 MB asset on every cold start was the worst startup offender.
- **Internal Prompts have been removed.** The intro / model-info /
  translate templates that used to live as agent-bound `Prompt`
  entries now live as dedicated `GeneralSettings.introPrompt /
  modelInfoPrompt / translatePrompt` fields with their own editor
  cards under AI Setup.
- **`AppService.LOCAL` isn't in `ProviderRegistry`.** It's a
  sentinel singleton found via `findById("LOCAL")`. Persisted
  ChatSessions whose provider was Local can be reloaded across
  launches because the deserializer routes the `"LOCAL"` id to the
  sentinel.
- **`LocalLlm.generate` is synchronous and not streaming.** The
  MediaPipe API in this version doesn't expose a partial-token
  callback through the Kotlin binding; the chat session shows the
  full reply when the call returns.
- **Truth + MediaPipe** ship incompatible Guava transitive
  dependencies. The `ai/build.gradle.kts` constraint pinning Guava
  to `33.4.3-android` is required — without it the instrumented
  test suite VerifyError-s on every Truth assertion.

## Versioning the precompute migration

`AppViewModel` has a `CAPS_PRECOMPUTED_VERSION` constant gated by
`KEY_CAPS_PRECOMPUTED_VERSION` in prefs. Bump the constant when the
precompute logic changes and the existing on-disk data needs to be
recomputed; the bootstrap pass will run once on the next launch and
update the flag. The check is per-provider — providers that already
have populated precomputed data are skipped.
