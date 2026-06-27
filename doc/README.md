# Documentation

This folder contains the full documentation for the AI app — a
multi-provider Android client for running prompts against many AI
models in parallel, fanning one model's response into another's
prompt, and chatting with them.

The project is a single Activity ([`MainActivity`](../ai/src/main/java/com/ai/MainActivity.kt)),
Kotlin 2.4.0 + Jetpack Compose, ~153,180 LOC across 388 Kotlin files
(`data` 88, `ui` 273, `viewmodel` 24, `model` 2, plus the one entry
file). It is MVVM, but with exactly **one** real Android view model:
[`AppViewModel`](../ai/src/main/java/com/ai/viewmodel/AppViewModel.kt)
(`: AndroidViewModel`). `ReportViewModel` and `ChatViewModel` are plain
wrapper classes constructed with an `AppViewModel` and delegating all
state to it — they carry the `…ViewModel` name but are not androidx
view models. Generation logic is split out into 8 engines and 5
managers (plus a `BatchEngine` base, a `WorkerRunner`, and type/support
files — the 24 files under `viewmodel/`). There are **51
cloud providers** loaded at runtime from one JSON file per provider
under `assets/providers/` (48 `OPENAI_COMPATIBLE`, 1 `ANTHROPIC`, 1
`GOOGLE`, 1 `REPLICATE`) — they are not hardcoded — plus the synthetic on-device
`AppService.LOCAL`. Seven
external metadata repositories plus two provider self-report sources
and a manual override layer into one resolved view per
`(provider, model)` pair.

## Index

### For end users
- **[manual.md](manual.md)** — Functional walkthrough of every screen
  and feature, from first-run setup through Reports, Chat, Dual Chat,
  Translation, Fan-out / Fan-in, exports, and Housekeeping.
- **[screens.md](screens.md)** — Quick reference table of every screen
  title and its subtitle line.

### For developers
- **[architecture.md](architecture.md)** — Big-picture map of the app:
  navigation, view models, data layer, layered lookups, concurrency,
  state recovery, Fan-out / Fan-in.
- **[ownership.md](ownership.md)** — Runtime-state single-writer map:
  who owns each `StateFlow` / job map / Compose-derived bundle, and
  where everyone else reads it from. Read before moving runtime state.
- **[development.md](development.md)** — Build/deploy/test commands,
  project layout, how to add a provider / parameter / pricing tier /
  SecondaryKind / Internal Prompt category, common gotchas.
- **[api-formats.md](api-formats.md)** — The three API dispatch paths
  (`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`) and what's quirky about
  each.
- **[datastructures.md](datastructures.md)** — Every non-trivial data
  class with every field, grouped by domain.
- **[parameters.md](parameters.md)** — How generation parameters
  (temperature, max_tokens, reasoning effort, web search, …) resolve:
  the agent / flock / swarm / per-call precedence at every call site.
- **[system-prompts.md](system-prompts.md)** — How the system prompt is
  chosen for each kind of API call, and the precedence at each site.
- **[secondary-results.md](secondary-results.md)** — Deep dive on the
  meta-result flow: RERANK, user-driven META prompts, MODERATION,
  TRANSLATE, Fan-out / Fan-in, and the worker-judged secondary kinds
  TOURNAMENT / JUDGES / COMPARE / TRANSRANK.
- **[tournament-judges-compare.md](tournament-judges-compare.md)** —
  Worker-judged report analysis: Tournament rankings via eleven methods
  (Copeland win-rate now over per-model contested games, plus ELO,
  Davidson, Tideman, Markov, Schulze, Minimax, Colley, Glicko2, Points,
  TrueSkill2), Judge-the-judges agreement, and Compare-with-meta
  similarity grids.
- **[ui-customization.md](ui-customization.md)** — Settings → UI tweaks
  (Colors mode), UI Colors, and Default icons: `AppColors` Day/Night
  palettes + `UiColorMode`, `MetadataIcons`, persistence, aliases, and
  which UI roles each setting controls.
- **[help.md](help.md)** — The in-app Help system: the white ❔ live
  "&lt;screen&gt; - icons" overlay (report-Manage screens) vs the red ❓
  help page, per-screen topics, per-provider / per-repository pages,
  and the static icon-table legend.
- **[applog.md](applog.md)** — In-app log4j-style file logger
  ([`AppLog`](../ai/src/main/java/com/ai/data/AppLog.kt)),
  daily-rotated files under `<filesDir>/applog/`, the AppLog
  viewer screens, threshold/level settings, filter UX, and
  Copy / Share dialog options.
- **[log-details.md](log-details.md)** — Generated reference of
  every `AppLog` call site that writes to the application log,
  grouped by severity (ERROR / WARN / INFO / DEBUG / TRACE) then
  by source file, with each line, tag, and message.
- **[report-icons.md](report-icons.md)** — The per-report + per-model
  emoji, produced by the worker engine (`workers/report-icon`,
  `workers/model-icons`, …): generation flow, the Find-alternative
  picker (`alt/*` prompts), the Manual edit / Select icon options, the
  icons grid view, cost surfacing, and the `iconGenEnabled` /
  `perModelIconGenEnabled` master switches.
- **[throttle.md](throttle.md)** — The two-layer throttle: the
  per-host `ProviderThrottle` / `ProviderThrottleInterceptor` gate
  and the separate global `ApiCallCaps` coroutine-semaphore (default
  100; the per-flow sub-caps are sized to it); the canonical
  sub→global→host acquisition order that
  releases the outer two caps while parked on a busy host gate; the
  429 + 529 retry interceptors (3 retries / 1 s backoff by default);
  and user-tunable read timeouts and per-provider overrides.

### Subsystem deep dives
- **[workers.md](workers.md)** — AI Workers: **Agents** (named
  provider+model+params+system-prompt configs), **Flocks** (agent
  groups), and **Swarms** (provider/model-pair groups), and how they
  feed report / chat selection.
- **[knowledge.md](knowledge.md)** — RAG: knowledge bases, the nine
  source extractors, the chunk → embed → retrieve → inject pipeline,
  and the `FloatArray` cosine hot path.
- **[local-runtime.md](local-runtime.md)** — The on-device runtime:
  the synthetic `AppService.LOCAL` provider, `LocalLlm` (`.task`) and
  `LocalEmbedder` (`.tflite`), and Local Semantic Search.
- **[experimental.md](experimental.md)** — The master **Experimental
  features** toggle (off by default) and every UI surface it hides:
  on-device models, AI Knowledge / RAG, and Local Semantic Search.
- **[model-states.md](model-states.md)** — The four model-state lists
  (Blocked, Cooldowns, Test-excluded, Inaccessible) plus manual
  model-type overrides: purpose, population, and picker effect.
- **[regenerate.md](regenerate.md)** — The Manage-hub "Get info" status
  board and the multi-phase regenerate-batch orchestration engine
  (pause-on-error, background resume, per-report persistence).
- **[costs.md](costs.md)** — Cost tracking, the AI Usage screen,
  per-report cost breakdown, manual price overrides, and costs
  maintenance.
- **[value-view.md](value-view.md)** — Cost × quality frontier: the
  ranking-source switch, Combined 0–1000 weights, fan-out cost
  fold-in, and the Pareto graph.
- **[translation.md](translation.md)** — TRANSLATE secondary-kind,
  multi-language fan-out, translation runs, the side-by-side /
  Translate Run / Translate Call detail screens.
- **[rank-translators.md](rank-translators.md)** — Rank the translators
  (TRANSRANK): a judge panel scores each translation 0–100 and ranks
  the translator models.
- **[share-target.md](share-target.md)** — `ACTION_SEND` /
  `ACTION_SEND_MULTIPLE` plumbing, the chooser, and the two landing
  routes (Report, Chat).
- **[backup-restore.md](backup-restore.md)** — Full-app zip backup
  format, two-pass validate-then-write restore, and the post-restore
  provider catalog merge.

### Reference data
- **[providers.md](providers.md)** — All 68 cloud providers from the
  per-provider JSON files under `assets/providers/` with base URL,
  admin URL, and non-default fields.
- **[repositories.md](repositories.md)** — The seven external metadata
  repositories (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace) with endpoints, auth, what they
  provide, and where the cached data lives.
- **[persistent.md](persistent.md)** — Exact contents of every
  SharedPreferences file and every persistent JSON file under
  `<filesDir>` including the `embeddings/`, `secondary/`, `pricing/`
  and `trace/` trees.

### Analysis & backlog
- **[audit/reports_section_analysis.md](../audit/reports_section_analysis.md)** —
  In-depth code-level review of the Reports section: current
  capabilities, product / technical gaps, and feature
  recommendations. An analysis artefact (now kept under `audit/`,
  alongside the dated review snapshots), not a live spec.
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

Pull up a subsystem doc (translation, share-target,
secondary-results, help) when a specific question lands in your lap.

## Internal QA notes

The repo also carries an `audit/` directory at the root, holding
date-stamped review snapshots (e.g. `audit/2026-05-08/`,
`audit/2026-05-24/`). Each snapshot is the same six markdown files:
`00_summary.md`, `bugs_chat.md`, `bugs_data.md`, `bugs_reports.md`,
`bugs_settings.md`, plus a `README.md`. These are running lists of
internal findings — not part of the user-facing documentation, but
useful when picking up where someone left off.

## Authoritative sources

The documentation is hand-written — the code is the ultimate source of
truth. When in doubt, the relevant files are:

- `assets/providers/` — provider definitions, one JSON file per
  provider (51 files, each a bare `ProviderDefinition` object — no
  `{ "providers": [...] }` wrapper)
- `assets/internal-prompts/` — Internal Prompts (Meta / Fan-out / Fan-in / Other internal)
- `assets/examples.json` — Example Prompts library
- `data/AppService.kt` — provider runtime model
- `data/ApiFormat.kt` + `data/ApiDispatch.kt` — dispatch routing
- `data/PricingCache.kt` — layered pricing + capability lookup. The
  `getPricing` precedence (first hit wins) is: provider self-report
  (OpenRouter-self, then Together-self) → manual **OVERRIDE** →
  LiteLLM → models.dev → llm-prices → Artificial Analysis →
  OpenRouter cross-provider fallback → Helicone → `DEFAULT_PRICING`
  ($25/M in, $75/M out). Manual override sits **above** all curated
  catalog tiers but below the two provider self-report tiers. (The
  class-level KDoc is now correct; only `getPricing`'s own KDoc still
  says "five-tier lookup" — the code is authoritative.) Tier blobs live
  under `<filesDir>/pricing/`;
  the `pricing_cache` prefs file keeps only timestamps and the
  manual-override map
- `data/SecondaryModels.kt` — `SecondaryKind` (the 8 kinds RERANK,
  META, MODERATION, TRANSLATE, TOURNAMENT, JUDGES, COMPARE, TRANSRANK),
  the
  single flat `SecondaryResult` row used for every kind, and the
  prompt-template helpers (`resolveSecondaryPrompt`,
  `resolveFanInPrompt`, …)
- `data/SecondaryResult.kt` — the `SecondaryResultStorage` object: the
  per-report `<filesDir>/secondary/<reportId>/<resultId>.json` store
  and its Tournament / Compare cell-commit helpers
- `data/SecondaryScopes.kt` — the `SecondaryScope` sealed type
  (AllReports / TopRanked / Manual) used by Meta / Fan-out scope
  selection
- `data/TournamentRunModel.kt`, `data/JudgeEvalRunModel.kt`,
  `data/CompareRunModel.kt`, `data/TranslatorRankModel.kt` (TRANSRANK)
  — worker-judged analysis run state
- `ui/shared/AppColors.kt` + `data/MetadataDefaults.kt` — configurable
  UI colors and Default-icons-backed glyphs
- `data/SharedContent.kt` — share-target snapshot
- `data/InternalPromptSeed.kt` + `data/ExamplePromptSeed.kt` — bundled-asset loaders
- `model/SettingsModels.kt` — every settings data class
- `viewmodel/AppViewModelTypes.kt` — `UiState`, `GeneralSettings`, and
  the `IconCandidate` / `TitleCandidate` / `TranslationCandidate` +
  `Refresh*` top-level types
- `viewmodel/AppViewModel.kt` — the single `AndroidViewModel`:
  bootstrap, model fetching, the shared `UiState`, and the
  `viewModelScope` that all long-running engines run on
- `viewmodel/ReportViewModel.kt` — report-generation orchestration
  (plain wrapper over `AppViewModel`); the secondary kinds are
  delegated to `SecondaryRunManager`, `FanOutEngine`, `CompareEngine`,
  `TournamentEngine`, `JudgeEvalEngine`, `TranslatorRankEngine`, and
  `TranslationRunManager`
- `ui/settings/SettingsPreferences.kt` — every prefs key
- `ui/admin/HelpScreen.kt` — per-screen / per-provider / per-repository help topics
- `data/BackupManager.kt` — what gets backed up
