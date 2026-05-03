# Documentation

This folder contains the full documentation for the AI app — a
multi-provider Android client for running prompts against many AI
models in parallel, chatting with them, attaching documents as a
RAG knowledge base, and running everything offline against an
on-device model when you want to.

The project is a single Activity, Kotlin 2.2.10 + Jetpack Compose,
~35k LOC across ~100 Kotlin files, MVVM with three view models, 39
cloud providers across three API formats plus a synthetic on-device
`LOCAL` provider, seven external metadata repositories layered into
one resolved view per `(provider, model)` pair, and a RAG layer that
chunks documents and either embeds them on-device or against any
provider's `/v1/embeddings`.

## Index

### For end users
- **[manual.md](manual.md)** — Functional walkthrough of every screen
  and feature, from first-run setup through Reports, Chat, Dual Chat,
  Knowledge bases, Local LLM, Translation, exports, and Housekeeping.

### For developers
- **[architecture.md](architecture.md)** — Big-picture map of the app:
  navigation, view models, data layer, layered lookups, concurrency,
  state recovery, RAG layer, on-device runtime.
- **[development.md](development.md)** — Build/deploy/test commands,
  project layout, how to add a provider / parameter / pricing tier /
  knowledge source type / SecondaryKind, common gotchas.
- **[api-formats.md](api-formats.md)** — The three API dispatch paths
  (`OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`) and what's quirky about
  each.
- **[datastructures.md](datastructures.md)** — Every non-trivial data
  class with every field, grouped by domain.
- **[secondary-results.md](secondary-results.md)** — Deep dive on the
  Rerank / Summarize / Compare / Moderate / Translate meta-result flow,
  including prompt resolution, scope, language fan-out, and storage.

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
  multi-language fan-out, translation runs, the Translate Compare /
  Translate Run / Translate Call detail screens.
- **[share-target.md](share-target.md)** — `ACTION_SEND` /
  `ACTION_SEND_MULTIPLE` plumbing, the chooser, and the three landing
  routes (Report, Chat, Knowledge).
- **[backup-restore.md](backup-restore.md)** — Full-app zip backup
  format, two-pass validate-then-write restore, the
  local-models exclude/preserve set, and the post-restore provider
  catalog merge.

### Reference data
- **[providers.md](providers.md)** — All 39 cloud providers from
  `assets/setup.json` with base URL, admin URL, and non-default
  fields, plus the synthetic LOCAL provider.
- **[repositories.md](repositories.md)** — The seven external metadata
  repositories (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace) with endpoints, auth, what they
  provide, and where the cached data lives.
- **[persistent.md](persistent.md)** — Exact contents of every
  SharedPreferences file and every persistent JSON file under
  `<filesDir>` including the `knowledge/`, `local_models/`,
  `local_llms/`, `embeddings/`, `secondary/` and `trace/` trees.

## Reading order

If you're new to the codebase, the recommended path is:

1. **manual.md** — what the app does, from a user's perspective.
2. **architecture.md** — how the code is organised at a high level.
3. **datastructures.md** — what the runtime objects look like.
4. **development.md** — practical guide for making a change.

Pull up a subsystem doc (knowledge, local-runtime, translation,
share-target, secondary-results) when a specific question lands in
your lap.

## Authoritative sources

The documentation is hand-written — the code is the ultimate source of
truth. When in doubt, the relevant files are:

- `assets/setup.json` — provider definitions
- `data/AppService.kt` — provider runtime model + the synthetic LOCAL sentinel
- `data/ApiFormat.kt` + `data/ApiDispatch.kt` — dispatch routing
- `data/PricingCache.kt` — layered pricing + capability lookup
  (LiteLLM, models.dev, llm-prices, Artificial Analysis, manual
  override, OpenRouter, Helicone) plus provider self-report
  (OpenRouter / Together) and `DEFAULT_PRICING`. Tier blobs live
  under `<filesDir>/pricing/`; `pricing_cache.xml` keeps only
  timestamps and the manual-override map
- `data/SecondaryResult.kt` — Rerank / Summarize / Compare / Moderate / Translate data + prompts
- `data/Knowledge.kt` + `data/KnowledgeService.kt` + `data/KnowledgeExtractors.kt` — RAG layer
- `data/LocalLlm.kt` + `data/LocalEmbedder.kt` — on-device runtime
- `data/SharedContent.kt` — share-target snapshot
- `model/SettingsModels.kt` — every settings data class
- `viewmodel/AppViewModel.kt` — `UiState`, `GeneralSettings`,
  bootstrap, model fetching
- `viewmodel/ReportViewModel.kt` — report and secondary-result generation
- `ui/settings/SettingsPreferences.kt` — every prefs key
- `data/BackupManager.kt` — what gets backed up
