# Documentation

This folder contains the full documentation for the AI app — a
multi-provider Android client for running prompts against many AI
models in parallel, fanning one model's response into another's
prompt, chatting with them, attaching documents as a RAG knowledge
base, and running everything offline against an on-device model
when you want to.

The project is a single Activity, Kotlin 2.2.10 + Jetpack Compose,
~52,300 LOC across 112 Kotlin files, MVVM with three view models,
42 cloud providers across three API formats plus a synthetic
on-device `Local` provider, seven external metadata repositories
layered into one resolved view per `(provider, model)` pair, and a
RAG layer that chunks documents and either embeds them on-device or
against any provider's `/v1/embeddings`.

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
  TRANSLATE, and the Fan-out / Fan-in flow with its three-level
  drill-in.
- **[help.md](help.md)** — The in-app Help system: per-screen
  topics, per-provider pages, per-repository pages, icon legend.

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

## Reading order

If you're new to the codebase, the recommended path is:

1. **manual.md** — what the app does, from a user's perspective.
2. **architecture.md** — how the code is organised at a high level.
3. **datastructures.md** — what the runtime objects look like.
4. **development.md** — practical guide for making a change.

Pull up a subsystem doc (knowledge, local-runtime, translation,
share-target, secondary-results, help) when a specific question
lands in your lap.

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
- `data/Knowledge.kt` + `data/KnowledgeService.kt` + `data/KnowledgeExtractors.kt` — RAG layer
- `data/LocalLlm.kt` + `data/LocalEmbedder.kt` — on-device runtime
- `data/SharedContent.kt` — share-target snapshot
- `data/InternalPromptSeed.kt` + `data/ExamplePromptSeed.kt` — bundled-asset loaders
- `model/SettingsModels.kt` — every settings data class
- `viewmodel/AppViewModel.kt` — `UiState`, `GeneralSettings`,
  bootstrap, model fetching, hot per-pair fan-out flow
- `viewmodel/ReportViewModel.kt` — report and secondary-result generation, Fan-out / Fan-in
- `ui/settings/SettingsPreferences.kt` — every prefs key
- `ui/admin/HelpScreen.kt` — per-screen / per-provider / per-repository help topics
- `data/BackupManager.kt` — what gets backed up
