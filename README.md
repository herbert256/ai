# AI — Multi-Provider AI Reports & Chat

Android app for AI-powered reports and conversations using 42 cloud
AI services. Run the same prompt against many models at once,
compare responses side-by-side, rerank / chat-meta / moderate /
translate them, fan out one model's response into another's
prompt, and chat with real-time streaming.

## Features

- **42 Cloud AI Services** across three API formats (OpenAI-compatible,
  Anthropic, Google), all with real-time SSE streaming
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
- **AI Chat** with multi-turn conversations, streaming, vision,
  reasoning-effort selection, and auto-saved history
- **AI Dual Chat** — two models in conversation with each other
- **Reports Search** — Quick local search, Extended local search,
  and Remote semantic search across your saved reports
- **Share-Target** — receive `ACTION_SEND` from any app to start a
  Report or Chat from the shared payload
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
  rerank / chat-meta / moderate / translate / fan-out API spend
- **API Trace Viewer** — every request and response saved as
  inspectable JSON
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
- API keys for the cloud providers you want to use

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
| [doc/secondary-results.md](doc/secondary-results.md) | Rerank / Meta prompts / Moderate / Translate / Fan-out / Fan-in deep dive |
| [doc/translation.md](doc/translation.md) | TRANSLATE secondary-kind, multi-language fan-out, translation runs |
| [doc/share-target.md](doc/share-target.md) | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` flow |
| [doc/backup-restore.md](doc/backup-restore.md) | Backup zip format, two-pass validate-then-write restore |
| [doc/providers.md](doc/providers.md) | All 42 cloud providers from `providers.json` |
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
- **Document Extraction**: PDFBox-Android
- **Model Data**: LiteLLM, OpenRouter, models.dev, Helicone,
  llm-prices, Artificial Analysis, HuggingFace
- **Android UI**: Jetpack Compose, Material 3
- **Android Networking**: Retrofit, OkHttp, Gson
