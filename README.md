# AI — Multi-Provider AI Reports & Chat

Android app for AI-powered reports, conversations, and document Q&A
using 42 cloud AI services plus an on-device LLM runtime. Run the
same prompt against many models at once, compare responses
side-by-side, rerank / chat-meta / moderate / translate them, fan
out one model's response into another's prompt, chat with real-time
streaming, attach documents as a knowledge base, and run everything
offline against a local `.task` model when you want to.

## Features

- **42 Cloud AI Services** across three API formats (OpenAI-compatible,
  Anthropic, Google), all with real-time SSE streaming
- **On-Device LLM** via MediaPipe Tasks GenAI — chat, report, or RAG
  against a `.task` model bundle (Gemma, Phi, Llama …) with no network
- **Multi-Agent Reports** — query providers in parallel, compare
  responses side-by-side, exportable as HTML, JSON, PDF, DOCX, ODT,
  RTF, or a self-contained zipped HTML site
- **User-defined Meta prompts** — Compare, Critique, Synthesize,
  anything you name — operate on a finished report's outputs and
  bucket separately by name. CRUD'd under Settings → AI Setup →
  Prompt management
- **Fan-out / Fan-in** — feed one model's response into another's
  prompt (one call per (answerer, source) pair) and combine all
  responses back into one report. Three drill-in levels (L1 / L2 / L3)
  driven by `FanOutEngine` with progress bars, role toggle, per-pair
  regeneration, and a parallel **fan-icons** mode that runs a 3-tier
  emoji chain across every pair
- **Test all models** — `ModelTestEngine` probes every active
  provider's chat / embedding / rerank models with a fixed prompt
  in parallel; L1/L2/L3 drill-in mirrors the Fan-out layout (split
  catalog-stats panel, throttled-keys live readout, per-row latency
  and cost). Auto-feeds the **Blocked** / **Test-excluded** /
  **Inaccessible** lists from probe outcomes
- **Model cooldowns** — a 429 with a long Retry-After (≥ 1 h) benches
  the model in `ModelCooldownStore`; pickers dim it with a
  "rate-limited · back HH:mm" caption; entry is CRUD'd and the
  trace that produced it is one tap away
- **Rerank / Moderate / Translate** structured meta-results — turn
  N model outputs into a ranked list, a content-policy verdict, or
  a multi-language translation; rerank routes through a provider's
  dedicated `/rerank` endpoint when the picked model supports it
- **AI Knowledge (RAG)** — attach PDFs, Office docs, spreadsheets,
  Markdown, plain text, web pages, or images (with OCR) as a
  knowledge base; chunk + embed them locally or remotely; inject
  retrieved context into Reports and Chats
- **AI Chat** with multi-turn conversations, streaming, vision,
  reasoning-effort selection, KB attachment, and auto-saved history
- **AI Dual Chat** — two models in conversation with each other
- **Local + Remote Semantic Search** + **Quick / Extended local search**
  across your saved reports
- **Share-Target** — receive `ACTION_SEND` from any app to start a
  Report, Chat, or Knowledge ingest from the shared payload
- **AI Flocks** (agent groups) and **AI Swarms** (provider/model
  groups) for organising configurations
- **Reusable Parameters** and **System Prompts** assignable to agents,
  flocks, or swarms
- **Example Prompts** — a curated starter library, importable from
  `assets/examples.json`, surfaced as a one-tap entry on the AI
  Reports hub
- **Model Search** across every provider with seven layered metadata
  sources (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace), each with its own per-provider
  help page deep-linked from every entry point
- **Per-(provider, model, kind) Cost Tracking** with breakdown for
  rerank / chat-meta / moderate / translate / fan-out / fan-icons /
  report-icon API spend; split Costs view per chain tier
- **Per-host throttling** — sliding-window rate cap + concurrency
  cap per provider hostname (`ProviderThrottle`) with per-provider
  overrides, six **Maximal API calls** ceilings (global / report /
  translation / fan-out / fan-icons / test-sweep), 429 + 529 retry
  interceptors with configurable budget
- **API Trace Viewer** — every request and response saved as
  inspectable JSON; local LLM and local embedder calls trace too
- **Backup / Restore** the entire app to a single zip
- **Granular Export / Import** — split bundles for Settings, Model
  lists, Parameters, System prompts, Workers (agents + flocks +
  swarms), Costs CSV, and the All bundle (with or without API keys)
- **Refresh All** — chains the seven repositories in dependency
  order on a full-screen progress page, then auto-restarts the app
- **Comprehensive in-app help** — per-screen ❓ topic + per-provider
  ℹ help, with deep links from every Source button on Model Info /
  Trace detail / Costs

## Requirements

- Android 8.0 (API 26) or higher
- API keys for the cloud providers you want to use (none required for
  the on-device LLM and local embedder paths)

## Installation

Download the latest APK from releases, or build from source:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r ai/build/outputs/apk/debug/ai-debug.apk
```

## Documentation

Full documentation lives in **[doc/](doc/)**. Recommended reading
order:

| Document | Purpose |
|---|---|
| [doc/manual.md](doc/manual.md) | End-user walkthrough — every screen and feature |
| [doc/architecture.md](doc/architecture.md) | High-level code map for new contributors |
| [doc/development.md](doc/development.md) | Build, deploy, test, and how to add things |
| [doc/api-formats.md](doc/api-formats.md) | The three API dispatch paths and their quirks |
| [doc/datastructures.md](doc/datastructures.md) | Every data class with every field |
| [doc/secondary-results.md](doc/secondary-results.md) | Rerank / Meta prompts / Moderate / Translate / Fan-out / Fan-in / Fan-icons deep dive |
| [doc/model-test.md](doc/model-test.md) | "Test all models" — `ModelTestEngine`, the L1/L2/L3 drill-in, and the seeded Inaccessible / Test-excluded lists |
| [doc/cooldowns.md](doc/cooldowns.md) | `ModelCooldownStore` — auto-benching from 429s, CRUD, picker dimming, trace deep-links |
| [doc/throttle.md](doc/throttle.md) | Per-host rate + concurrency caps, six per-kind concurrency ceilings, 429 / 529 retry interceptors |
| [doc/knowledge.md](doc/knowledge.md) | RAG: knowledge bases, source extractors, embeddings, retrieval |
| [doc/local-runtime.md](doc/local-runtime.md) | On-device LLM (`LocalLlm`) + on-device embeddings (`LocalEmbedder`) |
| [doc/translation.md](doc/translation.md) | TRANSLATE secondary-kind, multi-language fan-out, translation runs |
| [doc/share-target.md](doc/share-target.md) | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` flow |
| [doc/backup-restore.md](doc/backup-restore.md) | Backup zip format, two-pass validate-then-write restore, exclude/preserve list for on-device model bundles |
| [doc/providers.md](doc/providers.md) | All 42 cloud providers from `providers.json` plus the synthetic Local provider |
| [doc/repositories.md](doc/repositories.md) | The seven external metadata sources |
| [doc/persistent.md](doc/persistent.md) | Every prefs key and every persistent file |
| [doc/help.md](doc/help.md) | The in-app Help system: per-screen topics, per-provider pages, icon legend |
| [doc/TODO.md](doc/TODO.md) | Future-work backlog discussed but not scheduled |

[doc/README.md](doc/README.md) is the index with the same list and a
bit more orientation.

## Privacy & Security

- **Local Storage Only** — all data stored on device
- **No Analytics** — no tracking or telemetry
- **Secure Keys** — API keys in app's private storage; the
  per-provider `Test API Key` flow is the only place they leave the
  device
- **Masked Traces** — sensitive headers masked in API logs (redacted
  at write time, not just on Copy / Share)
- **Fully Offline Path** — chat, report, and RAG against an on-device
  `.task` model + on-device embedder make zero network calls

## License

This project is licensed under the
[GNU General Public License v2.0](LICENSE).

## Acknowledgments

- **Cloud AI Services**: OpenAI, Anthropic, Google, xAI, Groq,
  DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow,
  Z.AI, Moonshot, Cohere, AI21, DashScope, Fireworks, Cerebras,
  SambaNova, Baichuan, StepFun, MiniMax, NVIDIA, Replicate, Hugging
  Face, Lambda, Lepton, 01.AI, Doubao, Reka, Writer, Cloudflare
  Workers AI, DeepInfra, Hyperbolic, Novita.ai, Featherless.ai,
  Liquid AI, Llama API, Krutrim, Nebius AI Studio, Chutes,
  Inference.net
- **On-Device Runtime**: MediaPipe Tasks GenAI (LiteRT) for the local
  LLM, MediaPipe Tasks TextEmbedder for local embeddings, ML Kit
  Latin Text Recognition for OCR
- **Document Extraction**: PDFBox-Android, Jsoup, Apache Commons
  Compress
- **Model Data**: LiteLLM, OpenRouter, models.dev, Helicone,
  llm-prices, Artificial Analysis, HuggingFace
- **Android UI**: Jetpack Compose, Material 3
- **Android Networking**: Retrofit, OkHttp, Gson
