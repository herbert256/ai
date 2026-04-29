# Documentation

This folder contains the full documentation for the AI app — a
multi-provider Android client for running prompts against many AI
models in parallel and analysing the results.

The project is a single Activity, Kotlin 2.2.10 + Jetpack Compose,
~22k LOC across 76 Kotlin files, MVVM with three view models, 38
default providers across three API formats, and seven external
metadata repositories layered into one resolved view per
`(provider, model)` pair.

## Index

### For end users
- **[manual.md](manual.md)** — Functional walkthrough of every screen
  and feature, from first-run setup through Reports, Chat, Dual Chat,
  Rerank/Summarize/Compare, exports, and Housekeeping.

### For developers
- **[architecture.md](architecture.md)** — Big-picture map of the app:
  navigation, view models, data layer, layered lookups, concurrency,
  state recovery.
- **[development.md](development.md)** — Build/deploy commands,
  project layout, how to add a provider / parameter / pricing tier /
  secondary kind, common gotchas.
- **[api-formats.md](api-formats.md)** — The three API dispatch paths
  (`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`) and what's quirky about
  each.
- **[datastructures.md](datastructures.md)** — Every data class with
  every field, grouped by domain.
- **[secondary-results.md](secondary-results.md)** — Deep dive on the
  Rerank / Summarize / Compare meta-result flow, including prompt
  resolution, the `@RESULTS@` block, the scope step, storage layout,
  and HTML export.

### Reference data
- **[providers.md](providers.md)** — All 38 providers from
  `assets/setup.json` with base URL, admin URL, and non-default
  fields.
- **[repositories.md](repositories.md)** — The seven external metadata
  repositories (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace) with endpoints, auth, what they
  provide, and where the cached data lives.
- **[persistent.md](persistent.md)** — Exact contents of every
  SharedPreferences file and every persistent JSON file under
  `<filesDir>`.

## Reading order

If you're new to the codebase, the recommended path is:

1. **manual.md** — what the app does, from a user's perspective.
2. **architecture.md** — how the code is organised at a high level.
3. **datastructures.md** — what the runtime objects look like.
4. **development.md** — practical guide for making a change.

The reference docs (providers, repositories, persistent, api-formats,
secondary-results) are indexed by topic; pull them up when a specific
question lands in your lap.

## Authoritative sources

The documentation is hand-written — the code is the ultimate source of
truth. When in doubt, the relevant files are:

- `assets/setup.json` — provider definitions
- `data/AppService.kt` — provider runtime model
- `data/ApiFormat.kt` + `data/ApiDispatch.kt` — dispatch routing
- `data/PricingCache.kt` — six tiers of pricing + capability lookup
- `data/SecondaryResult.kt` — Rerank / Summarize / Compare data + prompts
- `model/SettingsModels.kt` — every settings data class
- `viewmodel/AppViewModel.kt` — `UiState`, `GeneralSettings`,
  bootstrap, model fetching
- `viewmodel/ReportViewModel.kt` — report and secondary-result generation
- `ui/settings/SettingsPreferences.kt` — every prefs key
- `data/BackupManager.kt` — what gets backed up
