# Documentation

This folder contains the full documentation for the AI app — a
multi-provider Android client for running prompts against many AI
models in parallel, fanning one model's response into another's
prompt, chatting with them, attaching documents as a RAG knowledge
base, and running everything offline against an on-device model
when you want to.

The project is a single Activity, Kotlin 2.2.10 + Jetpack Compose,
~73,900 LOC across 150 Kotlin files, MVVM with **five** view models
(`AppViewModel`, `ChatViewModel`, `ReportViewModel` + the extracted
helpers file, plus the engine-style `FanOutEngine` and
`ModelTestEngine`), 42 cloud providers across three API formats plus
a synthetic on-device `Local` provider, seven external metadata
repositories layered into one resolved view per `(provider, model)`
pair, and a RAG layer that chunks documents and either embeds them
on-device or against any provider's `/v1/embeddings`.

## Index

### For end users
- **[manual.md](manual.md)** — Functional walkthrough of every screen
  and feature, from first-run setup through Reports, Chat, Dual Chat,
  Knowledge bases, Local LLM, Translation, Fan-out / Fan-in, exports,
  and Housekeeping.

### For developers
- **[architecture.md](architecture.md)** — Big-picture map of the app:
  navigation, view models, data layer, layered lookups, concurrency,
  state recovery, RAG layer, on-device runtime, Fan-out / Fan-in.
- **[development.md](development.md)** — Build/deploy/test commands,
  project layout, how to add a provider / parameter / pricing tier /
  knowledge source type / SecondaryKind / Internal Prompt category,
  common gotchas.
- **[api-formats.md](api-formats.md)** — The three API dispatch paths
  (`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`) and what's quirky about
  each.
- **[datastructures.md](datastructures.md)** — Every non-trivial data
  class with every field, grouped by domain.
- **[secondary-results.md](secondary-results.md)** — Deep dive on the
  meta-result flow: RERANK, the user-driven META kind (every chat-
  type Meta prompt — Compare, Critique, Synthesize, …), MODERATION,
  TRANSLATE, and the Fan-out / Fan-in / Fan-icons flow with its
  three-level drill-in driven by `FanOutEngine`.
- **[model-test.md](model-test.md)** — "Test all models" subsystem
  (`ModelTestEngine`): catalog partitioning, L1/L2/L3 drill-in,
  per-host throttle integration, and the seeded
  `assets/inaccessible.json` + `assets/excluded.json` lists that
  bound the sweep on a clean install.
- **[cooldowns.md](cooldowns.md)** — `ModelCooldownStore`:
  auto-benching on a long 429 Retry-After, the CRUD screen, picker
  dimming with "back HH:mm" captions, and the deep-link to the API
  trace that produced each cooldown.
- **[help.md](help.md)** — The in-app Help system: per-screen
  topics, per-provider pages, per-repository pages, icon legend.
- **[applog.md](applog.md)** — In-app log4j-style file logger
  ([`AppLog`](../ai/src/main/java/com/ai/data/AppLog.kt)),
  daily-rotated files under `<filesDir>/applog/`, the AppLog
  viewer screens, threshold/level settings, filter UX, and
  Copy / Share dialog options.
- **[report-icons.md](report-icons.md)** — The per-report emoji
  + per-agent 3-tier icon chain: bundled prompts
  (`internal/icon`, `internal/report_icon`, `report_icon_chat`,
  `report_icon_3th`), generation flow, the alternative-icons
  picker, the icons grid view, cost surfacing, and the two
  `iconGenEnabled` / `perModelIconGenEnabled` master switches.
- **[throttle.md](throttle.md)** — Per-provider rate-limit +
  concurrency caps (`ProviderThrottle`,
  `ProviderThrottleInterceptor`), the 429-retry interceptor,
  user-tunable read timeouts, and the per-provider override
  hierarchy.

### Subsystem deep dives
- **[knowledge.md](knowledge.md)** — RAG: knowledge base structure,
  ten source-type extractors, local + remote embedding, chunker,
  retrieval, attachment to Reports and Chats.
- **[local-runtime.md](local-runtime.md)** — On-device LLM
  (`LocalLlm`, MediaPipe Tasks GenAI) + on-device embedder
  (`LocalEmbedder`, MediaPipe Tasks TextEmbedder), model gating,
  download flow, `.task` / `.tflite` import, synthetic ApiTrace
  emission.
- **[translation.md](translation.md)** — TRANSLATE secondary-kind,
  multi-language fan-out, translation runs, the side-by-side /
  Translate Run / Translate Call detail screens.
- **[share-target.md](share-target.md)** — `ACTION_SEND` /
  `ACTION_SEND_MULTIPLE` plumbing, the chooser, and the three landing
  routes (Report, Chat, Knowledge).
- **[backup-restore.md](backup-restore.md)** — Full-app zip backup
  format, two-pass validate-then-write restore, the
  local-models exclude/preserve set, and the post-restore provider
  catalog merge.

### Reference data
- **[providers.md](providers.md)** — All 42 cloud providers from
  `assets/providers.json` with base URL, admin URL, and non-default
  fields, plus the synthetic Local provider.
- **[repositories.md](repositories.md)** — The seven external metadata
  repositories (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace) with endpoints, auth, what they
  provide, and where the cached data lives.
- **[persistent.md](persistent.md)** — Exact contents of every
  SharedPreferences file and every persistent JSON file under
  `<filesDir>` including the `knowledge/`, `local_models/`,
  `local_llms/`, `embeddings/`, `secondary/`, `pricing/` and
  `trace/` trees.

### Backlog
- **[TODO.md](TODO.md)** — Future work discussed but not scheduled.
  Currently: a foreground-Service plan so AI Report API calls can
  truly survive process kill (today's `viewModelScope` setup
  survives navigation but not Recents-swipe / OS memory pressure).

## Reading order

If you're new to the codebase, the recommended path is:

1. **manual.md** — what the app does, from a user's perspective.
2. **architecture.md** — how the code is organised at a high level.
3. **datastructures.md** — what the runtime objects look like.
4. **development.md** — practical guide for making a change.

Pull up a subsystem doc (knowledge, local-runtime, translation,
share-target, secondary-results, model-test, cooldowns, throttle,
help) when a specific question lands in your lap.

## Internal QA notes

The repo also carries an `audit/` directory at the root (six
markdown files: `00_summary.md`, `bugs_chat.md`, `bugs_data.md`,
`bugs_reports.md`, `bugs_settings.md`, plus a `README.md`) with a
running list of internal findings — not part of the user-facing
documentation, but useful when picking up where someone left off.

## Authoritative sources

The documentation is hand-written — the code is the ultimate source of
truth. When in doubt, the relevant files are:

- `assets/providers.json` — provider definitions
- `assets/prompts.json` — Internal Prompts (Meta / Fan-out / Fan-in / Other internal)
- `assets/examples.json` — Example Prompts library
- `data/AppService.kt` — provider runtime model + the synthetic LOCAL sentinel
- `data/ApiFormat.kt` + `data/ApiDispatch.kt` — dispatch routing
- `data/PricingCache.kt` — layered pricing + capability lookup
  (LiteLLM, models.dev, llm-prices, Artificial Analysis, manual
  override, OpenRouter, Helicone) plus provider self-report
  (OpenRouter / Together) and `DEFAULT_PRICING`. Tier blobs live
  under `<filesDir>/pricing/`; `pricing_cache.xml` keeps only
  timestamps and the manual-override map
- `data/SecondaryResult.kt` — `SecondaryKind` (RERANK, META, MODERATION, TRANSLATE) + storage + scope / language-scope sealed types + prompt-template helpers + Fan-out / Fan-in scope encoding
- `data/FanOutRunModel.kt` — `FanOutRunState`, `PairState`,
  `CombinedReportState`, `PairStatus` (the canonical per-run snapshot
  the `FanOutEngine` mutates atomically)
- `data/ModelCooldownStore.kt` — auto-bench on long 429s,
  persistent across launches in its own SharedPreferences file
- `data/ModelTestRunModel.kt` + `data/ModelTestRunStore.kt` —
  `ModelTestRunState`, `ModelTestState`, `TestStatus`; single-document
  JSON at `<filesDir>/test_run.json`
- `data/InaccessibleSeed.kt` + `data/TestExcludedSeed.kt` —
  delta-merge loaders for `assets/inaccessible.json` and
  `assets/excluded.json`; ensure tier-gated entries don't waste sweep
  slots on clean installs
- `data/InternalPromptIconCache.kt` — per-Internal-Prompt emoji cache
  keyed on `(name, title)`
- `data/Knowledge.kt` + `data/KnowledgeService.kt` + `data/KnowledgeExtractors.kt` — RAG layer
- `data/local/LocalLlm.kt` + `data/local/LocalEmbedder.kt` — on-device runtime
- `data/SharedContent.kt` — share-target snapshot
- `data/InternalPromptSeed.kt` + `data/ExamplePromptSeed.kt` — bundled-asset loaders
- `model/SettingsModels.kt` — every settings data class
  (including `BlockedModel`, `TestExcludedModel`, `InaccessibleModel`)
- `viewmodel/AppViewModel.kt` — `UiState`, `GeneralSettings`,
  bootstrap, model fetching
- `viewmodel/ReportViewModel.kt` — report and secondary-result generation; delegates Fan-out per-pair execution to `FanOutEngine`
- `viewmodel/FanOutEngine.kt` — authoritative `StateFlow<Map<FanOutRunKey, FanOutRunState>>`, per-pair Job map, fan-icons batch
- `viewmodel/ModelTestEngine.kt` — "Test all models" runner; throttled-keys StateFlow drives the L1 live readout
- `ui/settings/SettingsPreferences.kt` — every prefs key
- `ui/admin/HelpScreen.kt` — per-screen / per-provider / per-repository help topics
- `ui/shared/SharedComponents.kt` — `HardcodedSubjectRow`, the unified green subject row used app-wide
- `data/BackupManager.kt` — what gets backed up
