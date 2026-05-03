# AI — Multi-Provider AI Reports & Chat

Android app for AI-powered reports, conversations, and document Q&A
using 39 cloud AI services plus an on-device LLM runtime. Run the same
prompt against many models at once, compare responses side-by-side,
rerank / summarize / compare / translate them, chat with real-time
streaming, attach documents as a knowledge base, and run everything
offline against a local `.task` model when you want to.

## Features

- **39 Cloud AI Services** across three API formats (OpenAI-compatible,
  Anthropic, Google), all with real-time SSE streaming
- **On-Device LLM** via MediaPipe Tasks GenAI — chat, report, or RAG
  against a `.task` model bundle (Gemma, Phi, Llama …) with no network
- **Multi-Agent Reports** — query providers in parallel, compare
  responses side-by-side, exportable as HTML, JSON, PDF, DOCX, ODT,
  RTF, or a self-contained zipped HTML site
- **Rerank / Summarize / Compare / Moderate / Translate** meta-results
  — turn N model outputs into a ranked list, a synthesised answer, a
  structured agreement/disagreement analysis (with auto-appended
  reference legend), a content-policy verdict, or a multi-language
  translation
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
- **Model Search** across every provider with seven layered metadata
  sources (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices,
  Artificial Analysis, HuggingFace)
- **Six-Tier Cost Tracking** with per-kind breakdown for rerank /
  summarize / compare / moderation / translate API spend
- **API Trace Viewer** — every request and response saved as
  inspectable JSON; local LLM and local embedder calls trace too
- **Backup / Restore** the entire app to a single zip
- **Configuration Export / Import** for sharing setups across devices

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
| [doc/secondary-results.md](doc/secondary-results.md) | Rerank / Summarize / Compare / Moderate / Translate deep dive |
| [doc/knowledge.md](doc/knowledge.md) | RAG: knowledge bases, source extractors, embeddings, retrieval |
| [doc/local-runtime.md](doc/local-runtime.md) | On-device LLM (`LocalLlm`) + on-device embeddings (`LocalEmbedder`) |
| [doc/translation.md](doc/translation.md) | TRANSLATE secondary-kind, multi-language fan-out, translation runs |
| [doc/share-target.md](doc/share-target.md) | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` flow |
| [doc/providers.md](doc/providers.md) | All 39 cloud providers from `setup.json` plus the synthetic LOCAL provider |
| [doc/repositories.md](doc/repositories.md) | The seven external metadata sources |
| [doc/persistent.md](doc/persistent.md) | Every prefs key and every persistent file |

[doc/README.md](doc/README.md) is the index with the same list and a
bit more orientation.

## Privacy & Security

- **Local Storage Only** — all data stored on device
- **No Analytics** — no tracking or telemetry
- **Secure Keys** — API keys in app's private storage; the
  per-provider `Test API Key` flow is the only place they leave the
  device
- **Masked Traces** — sensitive headers masked in API logs
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
  Liquid AI, Llama API, Krutrim
- **On-Device Runtime**: MediaPipe Tasks GenAI (LiteRT) for the local
  LLM, MediaPipe Tasks TextEmbedder for local embeddings, MediaPipe
  Text Recognition (Latin) for OCR
- **Document Extraction**: PDFBox-Android, Jsoup, Apache Commons
  Compress
- **Model Data**: LiteLLM, OpenRouter, models.dev, Helicone,
  llm-prices, Artificial Analysis, HuggingFace
- **Android UI**: Jetpack Compose, Material 3
- **Android Networking**: Retrofit, OkHttp, Gson
