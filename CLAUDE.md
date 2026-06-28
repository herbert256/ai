# CLAUDE.md

Operational notes for Claude Code working in this repo. The deep
documentation lives in **[doc/](doc/)** — read that for anything
beyond the basics. This file is the short list of things worth
having in the prompt window from the first turn.

## Project at a glance

Android multi-provider AI app — reports, chat, dual chat, RAG
knowledge bases, on-device LLM and embedder, share-target ingest.
**36 cloud providers** across four API formats
(`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`, `REPLICATE`); 33 share unified
code paths via the format dispatch, only Anthropic, Google and Replicate
have format-specific code.

| | |
|---|---|
| Language | Kotlin 2.4.0 |
| UI | Jetpack Compose, Compose BOM 2026.05.01, Material 3 dark |
| Build | AGP 9.2.1, Gradle 9.5.1, build-tools 37.0.0, Java 25, JVM target 25 |
| SDK | `namespace = com.ai`, `minSdk = 36`, `compileSdk = 37`, `targetSdk = 36` |
| Persistence | SharedPreferences + JSON files in `<filesDir>` |
| Networking | Retrofit + OkHttp + custom interceptors (tracing, 429 retry) |
| Streaming | Kotlin Flow over SSE |
| Size | ~159,000 LOC across 404 Kotlin files (96 data, 278 ui, 27 viewmodel, 2 model, 1 entry) |

## Documentation

Anything operational beyond this file is in `doc/`:

- `doc/manual.md` — end-user walkthrough of every screen
- `doc/architecture.md` — high-level code map, navigation tree, layered lookups
- `doc/development.md` — build/deploy/test, how to add a provider / parameter / pricing tier / source type / SecondaryKind, common gotchas
- `doc/datastructures.md` — every non-trivial data class
- `doc/api-formats.md` — the three dispatch paths
- `doc/secondary-results.md` — Rerank / Meta (Compare/Critique/Synthesize/…) / Moderate / Translate / Fan-out / Fan-in
- `doc/parameters.md` — how generation parameters resolve (precedence per call site)
- `doc/system-prompts.md` — how the system prompt resolves per call site
- `doc/workers.md` — Agents / Flocks / Swarms
- `doc/knowledge.md` — RAG: KBs, nine extractors, embedding, retrieval
- `doc/local-runtime.md` — `LocalLlm` + `LocalEmbedder` (synthetic `AppService.LOCAL`)
- `doc/experimental.md` — the master Experimental-features toggle and what it hides
- `doc/model-states.md` — Blocked / Cooldowns / Test-excluded / Inaccessible + type overrides
- `doc/regenerate.md` — Get-info + the regenerate-batch orchestration engine
- `doc/report-icons.md` — per-report emoji + per-agent 3-tier icon chain
- `doc/costs.md` — cost tracking, AI Usage, manual price overrides, maintenance
- `doc/throttle.md` — per-provider rate-limit + concurrency caps, 429 retry
- `doc/translation.md` — TRANSLATE secondary kind + multi-language fan-out
- `doc/share-target.md` — `ACTION_SEND` plumbing
- `doc/backup-restore.md` — backup zip format, validate-then-write restore, exclude/preserve list
- `doc/persistent.md` — every prefs key, every file under `<filesDir>`
- `doc/providers.md` — all 35 providers
- `doc/repositories.md` — the nine external metadata repos
- `doc/help.md` — in-app Help system (per-screen topics, per-provider pages)
- `doc/applog.md` + `doc/log-details.md` — the in-app file logger + every call site
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
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleDebug

# Deploy to device + cloud copy + launch
adb install -r ai/build/outputs/apk/debug/ai-debug.apk \
  && cp ai/build/outputs/apk/debug/ai-debug.apk /Users/herbert/cloud/ai.apk \
  && adb shell am start -n com.ai/.MainActivity

# Release variant
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleRelease

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

- `data/` (88 files) — provider model (`AppService`,
  `ApiFormat`), dispatch (`ApiDispatch`, `ApiStreaming`,
  `ApiClient`), tracing (`ApiTracer` + the in-memory
  `cachedTraceFiles` cache), retry interceptor, repository
  façade (`AnalysisRepository`), `PricingCache` (tier blobs in
  `<filesDir>/pricing/`, timestamps in `pricing_cache.xml`),
  storage (`ReportStorage`, `ChatHistoryManager`,
  `SecondaryResultStorage`, `PromptCache`, `ModelListCache`,
  `EmbeddingsStore`, `ApiTracer`), RAG layer (`Knowledge*`,
  `KnowledgeService`, `KnowledgeExtractors`), on-device runtime
  (`LocalLlm`, `LocalEmbedder`), `BackupManager`,
  `SharedContent`.
- `model/` (2 files) — settings data classes.
- `viewmodel/` (25 files) — `AppViewModel`, `ChatViewModel`,
  `ReportViewModel` plus extracted engines/managers
  (`RegenerateBatchEngine`, `SecondaryRunManager`,
  `IconGenerationManager`, …). `SecondaryBatchEngine` is the shared
  template for the Tournament / JudgeEval / Compare / TranslatorRank
  engines (finalize / resume / remove / rerun / continue-broken flows).
  Other view models delegate state to `AppViewModel`.
- `ui/` (273 files) — Compose screens grouped by domain
  (`report/` ×98, `cruds/` ×48, `admin/` ×35, `settings/` ×22,
  `helpers/`, `shared/`, `navigation/`, `other/`, `chat/`,
  `search/`, `hub/`, `history/`, `share/`, `models/`,
  `knowledge/`, `theme/`).

Two non-obvious conventions:

- **Two-tier navigation** — top-level routes use Jetpack
  Navigation; sub-screens inside `SettingsScreen` are routed
  via the `SettingsSubScreen` enum + a `when` block.
- **Full-screen overlay pattern** —
  `if (showOverlay) { OverlayScreen(...); return }` inside a
  Composable. The `return` preserves the parent's `remember`
  state, which the user has explicitly relied on.

## Critical gotchas (the rest are in `doc/development.md`)

- **`AppService.LOCAL` is synthetic.** Its id is `"Local"`. Not in
  `ProviderRegistry`, reachable only via `AppService.findById`
  (which special-cases `LOCAL.id` before delegating). Routes the
  dispatch to `LocalLlm.generate` / `LocalEmbedder.embed` instead of
  Retrofit. Surfaces as a normal "Local" provider in every picker.
  See `doc/local-runtime.md`.
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
  Together when caller is Together) → manual override → LiteLLM →
  models.dev → llm-prices → Artificial Analysis → OpenRouter
  cross-provider fallback → Helicone → DEFAULT. Manual override
  comes **before** the curated tiers — a user adding a manual
  override specifically to correct a stale catalog entry would
  otherwise be silently ignored.
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
- **Export version is `1`** (`EXPORT_VERSION` in
  `data/ReportBundle.kt`). Import accepts `1..1`. Bump only when
  adding/removing a top-level field.

## Memory & plans

- Session-persistent memory:
  `/Users/herbert/.claude/projects/-Users-herbert-ai/memory/`.
  Index lives in `MEMORY.md`. Notable entries: cycle
  conventions, commit-per-prompt rule, deploy-to-both-targets
  rule.
- Plan files:
  `/Users/herbert/.claude/plans/`. Plan-mode plan files end up
  here — useful for picking work back up across sessions.
