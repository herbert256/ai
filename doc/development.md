# Development Guide

For developers maintaining or extending this app. Pairs with
[architecture.md](architecture.md) (the bigger picture) and
[datastructures.md](datastructures.md) (the data classes).

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

Toolchain: Kotlin 2.2.10, AGP 9.0.1, Gradle 9.1.0, Java 17, Compose
BOM 2026.01.01. minSdk 26, targetSdk 36, namespace `com.ai`.

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

## Project layout

```
ai/src/main/java/com/ai/
├── MainActivity.kt
├── data/                              # data layer
├── model/                             # data classes for settings + export
├── viewmodel/                         # AppViewModel, ChatViewModel, ReportViewModel
└── ui/
    ├── navigation/                    # AppNavHost, NavRoutes
    ├── hub/                           # main hub screen
    ├── report/                        # report flows + secondary results + export
    ├── chat/                          # chat + chat history + dual chat
    ├── models/                        # model search + Model Info
    ├── history/                       # report history + prompt history
    ├── settings/                      # all settings sub-screens
    ├── admin/                         # statistics, housekeeping, traces, help
    ├── shared/                        # CrudListScreen, TitleBar, AppColors, ...
    └── theme/                         # Material3 dark theme
```

Roughly 60 Kotlin files, ~13k LOC.

## Adding things

### A new OpenAI-compatible provider

1. Add an entry to `assets/setup.json` under `providerDefinitions`.
   Required: `id`, `displayName`, `baseUrl`, `adminUrl`, `defaultModel`,
   `prefsKey`. Optional: `openRouterName`, `litellmPrefix`,
   `hardcodedModels`, `defaultModelSource`.
2. If the provider exposes a different `chatPath` or `modelsPath`, set
   `typePaths.chat` and `modelsPath`.
3. Bump the `version` in `setup.json` only if the schema itself changed
   (currently `21`). Adding providers does **not** require a bump.

The first run after install reads `setup.json` and persists each
provider into `ProviderRegistry`. To force a re-import on an existing
install: Settings → AI Setup → Import/Export → "Import setup".

### A non-OpenAI-compatible provider

1. All of the above, plus `apiFormat` set to a new `ApiFormat` enum
   value.
2. Add format-specific code paths in `ApiDispatch.kt` and
   `ApiStreaming.kt` (request building, response parsing, SSE parser).
3. The 28 OpenAI-compatible providers all share `OPENAI_COMPATIBLE`;
   only `ANTHROPIC` and `GOOGLE` have their own paths. Mirror those if
   the new provider is genuinely different.

### A new agent parameter

1. Add to the `Parameter` enum and to `AgentParameters` /
   `Parameters` data classes (`SettingsModels.kt`).
2. Add UI inputs in `AgentEditScreen` (`AgentsScreen.kt`) and
   `ParametersEditScreen` (`ParametersScreen.kt`).
3. Update `AgentParametersExport` (`ExportModels.kt`).
4. Update the request model in `ApiModels.kt` (per `ApiFormat`).
5. Pass it through in `ApiDispatch.kt` and the streaming methods.

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

### A new SecondaryKind (after Rerank / Summarize / Compare)

Add an enum value to `SecondaryKind` in `data/SecondaryResult.kt`. The
Kotlin compiler will then enforce exhaustive `when` on every site that
maps `kind` to a string / colour / button label / view title /
prompt template. Walk the resulting compile errors:

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

## Testing

There is currently no unit-test scaffold. The app is verified by:
1. Running on the emulator after every change.
2. Checking the API trace files for the recent run.
3. Comparing `adb logcat` for warnings.

When changing a flow (especially generation, retry, or persistence),
**run an actual report** before declaring success. Type-checking and
compile success do not verify feature correctness here.

## Common gotchas

- **Anthropic `max_tokens` is required** (defaults to 4096). OpenAI
  treats it as optional.
- **Google auth uses `?key=` query param**, not Bearer token.
- **OpenAI dual API**: gpt-4o etc. use Chat Completions; gpt-5.x / o3 /
  o4 / gpt-4.1 use Responses API. Auto-routed via `usesResponsesApi()`
  / `endpointRules`.
- **Rate-limit retry is OFF main thread.** The interceptor has an
  explicit `Looper.myLooper() == getMainLooper()` guard. Don't remove it.
- **Backup zip** mirrors `filesDir` and 5 SharedPreferences files; new
  prefs files won't survive a restore unless added to
  `BackupManager.PREFS_TO_BACKUP`.
- **Export version is `21`.** Import accepts `11..21`. Bump the version
  only when adding/removing a top-level field; new optional fields
  don't require a bump if old importers can ignore them safely.
- **`ApiFactory.fetchUrlAsString` is preferred over `URL.openStream`**
  for ad-hoc HTTP gets so the call goes through `TracingInterceptor`
  and `RateLimitRetryInterceptor` like everything else.
- **Two `huggingface` keys**: `huggingFaceApiKey` (External Services →
  HuggingFace, used for HF model-info lookups) and the optional
  `HUGGINGFACE` provider in `setup.json` (HF Inference API, used as a
  chat provider). They are separate things even if both prompt for an
  HF token.
- **Custom providers added by the user** live in the `provider_registry`
  prefs file, separate from `eval_prefs`. The backup zip captures both.
- **Don't load files from `assets/` outside the bootstrap path.** The
  bundled `model_prices_and_context_window.json` is read once at
  install/Refresh, never on the hot path — its 1.2 MB JSON cost
  visibly dragged earlier startup before the precompute migration
  landed.

## Versioning the precompute migration

`AppViewModel` has a `CAPS_PRECOMPUTED_VERSION` constant gated by
`KEY_CAPS_PRECOMPUTED_VERSION` in prefs. Bump the constant when the
precompute logic changes and the existing on-disk data needs to be
recomputed; the bootstrap pass will run once on the next launch and
update the flag. The check is per-provider — providers that already
have populated precomputed data are skipped.
