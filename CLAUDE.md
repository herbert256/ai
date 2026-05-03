# CLAUDE.md

Operational notes for Claude Code working in this repo. The deep
documentation lives in **[doc/](doc/)** — read that for anything
beyond the basics. This file is the short list of things worth
having in the prompt window from the first turn.

## Project at a glance

Android multi-provider AI app — reports, chat, dual chat, RAG
knowledge bases, on-device LLM and embedder, share-target ingest.
**39 cloud providers** across three API formats
(`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`); 37 share unified
code paths via the format dispatch, only Anthropic and Google
have format-specific code.

| | |
|---|---|
| Language | Kotlin 2.2.10 |
| UI | Jetpack Compose, Compose BOM 2026.04.01, Material 3 dark |
| Build | AGP 9.2.0, Gradle 9.5.0, Java 17, JVM target 17 |
| SDK | `namespace = com.ai`, `minSdk = 26`, `targetSdk = 36` |
| Persistence | SharedPreferences + JSON files in `<filesDir>` + Jetpack DataStore |
| Networking | Retrofit + OkHttp + custom interceptors (tracing, 429 retry) |
| Streaming | Kotlin Flow over SSE |
| Size | ~36,800 LOC across 103 Kotlin files (28 data, 68 ui, 3 viewmodel, 3 model, 1 entry) |

## Documentation

Anything operational beyond this file is in `doc/`:

- `doc/manual.md` — end-user walkthrough of every screen
- `doc/architecture.md` — high-level code map, navigation tree, layered lookups
- `doc/development.md` — build/deploy/test, how to add a provider / parameter / pricing tier / source type / SecondaryKind, common gotchas
- `doc/datastructures.md` — every non-trivial data class
- `doc/api-formats.md` — the three dispatch paths
- `doc/secondary-results.md` — Rerank / Summarize / Compare / Moderate / Translate
- `doc/knowledge.md` — RAG: KBs, ten extractors, embedding, retrieval
- `doc/local-runtime.md` — `LocalLlm` + `LocalEmbedder`
- `doc/translation.md` — TRANSLATE secondary kind + multi-language fan-out
- `doc/share-target.md` — `ACTION_SEND` plumbing
- `doc/backup-restore.md` — backup zip format, validate-then-write restore, exclude/preserve list
- `doc/persistent.md` — every prefs key, every file under `<filesDir>`
- `doc/providers.md` — all 39 providers
- `doc/repositories.md` — the seven external metadata repos
- `doc/README.md` — index with reading order

## Session start

Check the emulator. If nothing is connected, start one and wait:

```bash
adb devices | grep -E "emulator|device$"

# If empty:
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36.1 -no-snapshot-load &
adb wait-for-device
```

## Build & deploy

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :ai:assembleDebug

# Deploy to device + cloud copy + launch
adb install -r ai/build/outputs/apk/debug/ai-debug.apk \
  && cp ai/build/outputs/apk/debug/ai-debug.apk /Users/herbert/cloud/ai.apk \
  && adb shell am start -n com.ai/.MainActivity

# Release variant
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :ai:assembleRelease

# Logcat — current tag set
adb logcat | grep -E "AiAnalysis|ApiDispatch|ApiTracer|AppViewModel|AtomicFileWrite|BackupManager|ChatHistoryManager|ImportExport|KnowledgeService|LocalEmbedder|LocalLlm|LocalRuntime|ModelListCache|PricingCache|ProviderRegistry|ReportExport|ReportStorage|SettingsExport"
```

## Cycle convention (load-bearing)

Two cycles, **default for every change, extended only on
explicit request**. The full procedures live in
`/Users/herbert/.claude/projects/-Users-herbert-ai/memory/feedback_run_test_suites.md`
— summary:

- **Default cycle** (every source change): build → deploy to
  device + cloud → launch → confirm foreground → commit. No
  unit tests, no instrumented tests, no snapshot/restore.
- **Extended cycle** (only when the user says "run the extended
  cycle" / "with full tests" / similar): adds `./gradlew test`,
  the host-mediated app-data snapshot via
  `adb exec-out run-as com.ai tar -cf -`, then
  `connectedDebugAndroidTest` (which uninstalls the package),
  then reinstall + push the snapshot back + `am force-stop
  com.ai` + relaunch.

## Commit rules

- "commit" means **all** current changes (modified + untracked),
  not just the latest prompt's diff.
- Any source-code change from a prompt gets committed before
  ending the turn — don't wait for an explicit "commit".
- After a successful commit, build + deploy to **both** targets
  (device install **and** `cp` to `/Users/herbert/cloud/ai.apk`),
  not only after explicit-commit prompts.

## Code layout pointers

Top-level under `ai/src/main/java/com/ai/`:

- `data/` (28 files) — provider model (`AppService`,
  `ApiFormat`), dispatch (`ApiDispatch`, `ApiStreaming`,
  `ApiClient`), tracing (`ApiTracer` + the in-memory
  `cachedTraceFiles` cache), retry interceptor, repository
  façade (`AnalysisRepository`), `PricingCache` (tier blobs in
  `<filesDir>/pricing/`, timestamps in `pricing_cache.xml`),
  storage (`ReportStorage`, `ChatHistoryManager`,
  `SecondaryResultStorage`, `PromptCache`, `ModelListCache`,
  `EmbeddingsStore`, `ApiTracer`), RAG layer (`Knowledge*`,
  `KnowledgeService`, `KnowledgeExtractors`), on-device runtime
  (`LocalLlm`, `LocalEmbedder`), `BackupManager`, `AppDataStore`,
  `SharedContent`.
- `model/` (3 files) — settings + export data classes.
- `viewmodel/` (3 files) — `AppViewModel`, `ChatViewModel`,
  `ReportViewModel`. Other view models delegate state to
  `AppViewModel`.
- `ui/` (68 files) — Compose screens grouped by domain
  (`hub/`, `report/`, `chat/`, `knowledge/`, `models/`,
  `search/`, `history/`, `settings/`, `admin/`, `shared/`,
  `theme/`, `navigation/`).

Two non-obvious conventions:

- **Two-tier navigation** — top-level routes use Jetpack
  Navigation; sub-screens inside `SettingsScreen` are routed
  via the `SettingsSubScreen` enum + a `when` block.
- **Full-screen overlay pattern** —
  `if (showOverlay) { OverlayScreen(...); return }` inside a
  Composable. The `return` preserves the parent's `remember`
  state, which the user has explicitly relied on.

## Critical gotchas (the rest are in `doc/development.md`)

- **`AppService.LOCAL` is synthetic.** Not in `ProviderRegistry`,
  reachable only via `findById("LOCAL")`. Routes the dispatch to
  `LocalLlm.generate` / `LocalEmbedder.embed` instead of
  Retrofit. Surfaces as a normal "Local" provider in every
  picker.
- **Anthropic `max_tokens` is required** (defaults to 4096).
  OpenAI treats it as optional.
- **Google auth uses `?key=` query param**, not `Authorization`.
- **OpenAI dual API**: `gpt-4o`-class uses Chat Completions;
  `gpt-5.x` / `o3` / `o4` / `gpt-4.1` use Responses API.
  Auto-routed via `usesResponsesApi()` / `endpointRules`. The
  Chat path forwards image content blocks; don't forget if a
  bug appears in vision regen.
- **Pricing layered lookup precedence** (in `PricingCache.getPricing`):
  provider self-report (OpenRouter when caller is OpenRouter,
  Together when caller is Together) → LiteLLM → models.dev →
  llm-prices → Artificial Analysis → manual override →
  OpenRouter cross-provider fallback → Helicone → DEFAULT.
  Manual override comes **after** the curated tiers.
- **`PricingCache.ensureLoaded` short-circuits on the main
  thread** when called before `preloadCompleted`. UI callers
  get `DEFAULT_PRICING` during the cold window — recomposition
  picks up real values once the preload finishes. Don't try to
  "fix" it by removing the guard.
- **Backup excludes `local_llms/` + `local_models/`** via
  `FILES_DIR_BACKUP_EXCLUDES`. The same set is preserved
  through `clearFilesDirForRestore`. `doc/backup-restore.md`
  has the full design.
- **`KnowledgeChunk.embedding` is `FloatArray`**, not
  `List<Double>`. Storage on disk is unchanged (Gson serialises
  both as JSON arrays of numbers); the type matters for in-memory
  heap and the primitive `EmbeddingsStore.cosine(FloatArray)`
  hot path used by RAG retrieval.
- **`RateLimitRetryInterceptor` retries 429s up to 5× with 3s
  back-off** and has an explicit `Looper.myLooper() ==
  getMainLooper()` guard. Don't remove the guard — it prevents
  the retry from ANR-ing the UI.
- **Export version is `21`.** Import accepts `11..21`. Bump only
  when adding/removing a top-level field.

## Memory & plans

- Session-persistent memory:
  `/Users/herbert/.claude/projects/-Users-herbert-ai/memory/`.
  Index lives in `MEMORY.md`. Notable entries: cycle
  conventions, commit-per-prompt rule, deploy-to-both-targets
  rule.
- Plan files:
  `/Users/herbert/.claude/plans/`. Plan-mode plan files end up
  here — useful for picking work back up across sessions.
