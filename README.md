# AI — Multi-Provider AI Reports & Chat

Android app for AI-powered reports and conversations using 91 cloud
AI services. Run the same prompt against many models at once,
compare responses side-by-side, rerank / chat-meta / moderate /
translate them, run worker-judged tournaments, fan out one model's
response into another's prompt, and chat with real-time streaming.

## Features

- **91 Cloud AI Services** across four API formats — 88
  OpenAI-compatible, 1 Anthropic, 1 Google, 1 Replicate — all with
  real-time SSE streaming, loaded at runtime from per-provider files
  under `assets/providers/` rather than hardcoded, plus a synthetic
  on-device `Local` provider
- **Multi-Agent Reports** — query providers in parallel, compare
  responses side-by-side, exportable as HTML, JSON, PDF, DOCX, ODT,
  RTF, or a self-contained zipped HTML site
- **User-defined Meta prompts** — Compare, Critique, Synthesize,
  anything you name — operate on a finished report's outputs and
  bucket separately by name. CRUD'd under Settings → AI Setup →
  Prompt management
- **Fan-out / Fan-in** — feed one model's response into another's
  prompt (one call per (answerer, source) pair) and combine all
  responses back into one report. Three drill-in levels with progress
  bars, role toggle, and per-pair regeneration
- **Rerank / Moderate / Translate** structured meta-results — turn
  N model outputs into a ranked list, a content-policy verdict, or
  a multi-language translation; rerank routes through a provider's
  dedicated `/rerank` endpoint when the picked model supports it
- **Tournament / Judge the judges / Compare with meta** — worker-judged
  report analysis batches: head-to-head rankings, judge-agreement
  checks, and answer × Meta-row similarity scoring
- **Rank the translators** — a panel of judge models scores each
  translated answer 0–100 and ranks the translator models by their
  average, one run per target language (worker-judged, no
  re-translation)
- **Value view** — plot every model on a cost × quality plane and mark
  the best-value pick and the Pareto frontier; switch the quality
  source between Rerank, Judge-the-judges, any Rank-the-translators run,
  Tournament totals/methods, or a weighted Combined blend (pure local
  derivation, no API calls)
- **Broken-work detection** — worker batches that stalled or need
  attention surface with a ⚠️ marker so partial runs are easy to find
- **AI Chat** with multi-turn conversations, streaming, vision,
  reasoning-effort selection, and auto-saved history
- **AI Dual Chat** — two models in conversation with each other
- **Reports Search** — Quick local search, Extended local search,
  Remote semantic search, and gated local semantic search across
  your saved reports
- **AI Knowledge / RAG** — optional knowledge bases with document
  extractors, embeddings, retrieval, and prompt-context injection
- **Local runtime** — optional on-device LLM and LiteRT embedder support
  through the synthetic `Local` provider
- **Share-Target** — receive `ACTION_SEND` from any app to start a
  Report or Chat from the shared payload
- **AI Flocks** (agent groups) and **AI Swarms** (provider/model
  groups) for organising configurations
- **Reusable Parameters** and **System Prompts** assignable to agents,
  flocks, or swarms
- **Example Prompts** — a curated starter library, importable from
  `assets/examples.json`, surfaced as a one-tap entry on the AI
  Reports hub
- **Model Search** across every provider with twelve layered metadata
  sources (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, Requesty, llm-stats, genai-prices, TrueFoundry,
  CloudPrice, HuggingFace), each with its own per-provider help page
  deep-linked from every entry point
- **Per-(provider, model, kind) Cost Tracking** with breakdown for
  report, rerank, chat-meta, moderate, translate, tournament,
  judges, compare, fan-out, metadata, icon, title, and language spend
- **Monitor / traces / logs** — live observability cards, API Trace
  Viewer, per-report audit log, and daily in-app application logs
- **UI customization** — Settings → UI Colors for `AppColors` roles
  and Default icons for `MetadataIcons`-backed action/fallback glyphs
- **Backup / Restore** the entire app to a single zip
- **Granular Export / Import** — split bundles for Settings, Model
  lists, Parameters, System prompts, Workers (agents + flocks +
  swarms), Costs CSV, and the All bundle (with or without API keys)
- **Refresh All** — refreshes all eleven pricing/capability catalog
  sources in parallel (HuggingFace is excluded — it loads lazily on
  demand) alongside a worker phase on a full-screen progress page,
  then shows a manual "Restart application" banner you tap when it
  finishes
- **Comprehensive in-app help** — on report-Manage screens the help ❔
  opens a live icon overlay (every visible bottom-bar icon, named and
  tappable) and the red ❓ opens the screen's help page; other screens
  keep the ❔ icon-legend page. Plus per-provider ℹ help, deep-linked
  from every Source button on Model Info / Trace detail / Costs

## Requirements

- Android 16 (API 36) or higher
- API keys for the cloud providers you want to use

## Installation

Download the latest APK from releases, or build from source:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :ai:assembleDebug
adb install -r ai/build/outputs/apk/debug/ai-debug.apk
```

## Documentation

Full documentation lives in **[doc/](doc/)** — 33 reference docs, all
verified against the current source. The complete set (see
[doc/README.md](doc/README.md) for the suggested reading order):

| Document | Purpose |
|---|---|
| [doc/manual.md](doc/manual.md) | End-user walkthrough — every screen and feature |
| [doc/architecture.md](doc/architecture.md) | High-level code map for new contributors |
| [doc/ownership.md](doc/ownership.md) | Single-writer runtime-state map: who owns each `StateFlow` / job map |
| [doc/development.md](doc/development.md) | Build, deploy, test, and how to add things |
| [doc/screens.md](doc/screens.md) | Quick reference of screen titles and subtitles |
| [doc/api-formats.md](doc/api-formats.md) | The four API dispatch paths and their quirks |
| [doc/datastructures.md](doc/datastructures.md) | Every data class with every field |
| [doc/parameters.md](doc/parameters.md) | How generation parameters resolve (precedence per call site) |
| [doc/system-prompts.md](doc/system-prompts.md) | How the system prompt resolves per call site |
| [doc/secondary-results.md](doc/secondary-results.md) | Rerank / Meta prompts / Moderate / Translate / Fan-out / Fan-in / Tournament / Judges / Compare deep dive |
| [doc/tournament-judges-compare.md](doc/tournament-judges-compare.md) | Tournament rankings, Judge-the-judges agreement, Compare-with-meta grids |
| [doc/rank-translators.md](doc/rank-translators.md) | Rank the translators (TRANSRANK): judge panel scores each translation and ranks the translator models |
| [doc/value-view.md](doc/value-view.md) | Cost × quality frontier: ranking-source switch, Combined weights, fan-out cost fold-in, Pareto graph |
| [doc/ui-customization.md](doc/ui-customization.md) | UI Colors, Default icons, `AppColors`, `MetadataIcons`, aliases and persistence |
| [doc/workers.md](doc/workers.md) | AI Workers: Agents, Flocks, Swarms |
| [doc/knowledge.md](doc/knowledge.md) | RAG: knowledge bases, nine extractors, embedding, retrieval |
| [doc/local-runtime.md](doc/local-runtime.md) | On-device `LocalLlm` + `LocalEmbedder`, synthetic `AppService.LOCAL` |
| [doc/experimental.md](doc/experimental.md) | The master Experimental-features toggle and what it hides |
| [doc/model-states.md](doc/model-states.md) | Blocked / Cooldowns / Test-excluded / Inaccessible + type overrides |
| [doc/regenerate.md](doc/regenerate.md) | Get-info + the regenerate-batch orchestration engine |
| [doc/report-icons.md](doc/report-icons.md) | Per-report + per-model emoji from the worker engine; Find-alternative / Manual edit / Select icon |
| [doc/costs.md](doc/costs.md) | Cost tracking, the Spend & usage dashboard, manual price overrides, maintenance |
| [doc/throttle.md](doc/throttle.md) | Per-provider rate-limit + concurrency caps, 429 retry, timeouts |
| [doc/translation.md](doc/translation.md) | TRANSLATE secondary-kind, multi-language fan-out, translation runs |
| [doc/share-target.md](doc/share-target.md) | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` flow |
| [doc/backup-restore.md](doc/backup-restore.md) | Backup zip format, two-pass validate-then-write restore |
| [doc/providers.md](doc/providers.md) | All 91 cloud providers from `assets/providers/` |
| [doc/repositories.md](doc/repositories.md) | The twelve external metadata sources |
| [doc/persistent.md](doc/persistent.md) | Every prefs key and every persistent file |
| [doc/help.md](doc/help.md) | The in-app Help system: live icon overlay vs help page, per-screen topics, per-provider pages |
| [doc/applog.md](doc/applog.md) | In-app file logger: levels, rotation, redaction, viewer |
| [doc/log-details.md](doc/log-details.md) | Reference: every `AppLog` call site, grouped by severity |

## Privacy & Security

- **Local Storage Only** — all data stored on device
- **No Analytics** — no tracking or telemetry
- **Secure Keys** — API keys in app's private storage; the
  per-provider `Test API Key` flow is the only place they leave the
  device
- **Masked Traces** — sensitive headers masked in API logs (redacted
  at write time, not just on Copy / Share)

## License

This project is licensed under the
[GNU General Public License v2.0](LICENSE).

## Acknowledgments

- **Cloud AI Services** (91, one JSON file per provider under
  `assets/providers/`): AI-ML-API, AI21, AIHubMix, AbacusAI, Alibaba,
  Amazon, Anthropic, AnyAPI, AtlasCloud, Auriko, Avian, Baseten,
  Cerebras, Chutes, Clarifai, Cohere, Cortecs, CrofAI, Crusoe,
  DeepInfra, DeepSeek, DigitalOcean, Doubleword, Evroc, FastRouter,
  Fireworks, FriendliAI, Frogbot, GMI-Cloud, GitHubModels, Glama,
  Google, Groq, HeliconeGateway, HuggingFace, IONet, Inceptron,
  Jiekou, KiloCode, LLMGateway, Lambda, LibertAI, LlamaGate,
  MegaNova, MergeGateway, MiniMax, Mistral, ModelScope, Moonshot,
  NVIDIA, NanoGPT, NearAI, NebiusAIStudio, NeuralWatt, Novita.ai,
  Nscale, OVHcloud, Ollama, OpenAI, OpenCodeZen, OpenRouter,
  OrcaRouter, Parasail, Perplexity, Poe, Poolside, PublicAI, QiniuAI,
  RegoloAI, Replicate, Requesty, RoutingRun, SambaNova, Scaleway,
  SiliconFlow, Tencent, TensorMesh, ThreeZeroTwoAI (302.AI), Tinfoil,
  Together, Venice, VercelAIGateway, Vivgrid, VolcEngine, Vultr,
  Wafer, WandB, Z.AI, ZenMux, iFlow, xAI
- **Document Extraction**: PDFBox-Android
- **Model Data**: LiteLLM, OpenRouter, models.dev, Helicone,
  llm-prices, Artificial Analysis, Requesty, llm-stats, genai-prices,
  TrueFoundry, CloudPrice, HuggingFace
- **Android UI**: Jetpack Compose, Material 3
- **Android Networking**: Retrofit, OkHttp, Gson
