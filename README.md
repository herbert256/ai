# AI — Multi-Provider AI Reports & Chat

Android app for AI-powered reports and conversations using 38 different AI services. Run the same prompt against many models at once, compare responses side-by-side, rerank/summarise/compare them, chat with real-time streaming, organise agents into flocks and swarms, track costs across six pricing tiers, and explore every model across every provider.

## Features

- **38 AI Services** across three API formats (OpenAI-compatible, Anthropic, Google), all with real-time SSE streaming
- **Multi-Agent Reports** querying providers in parallel, comparing responses side-by-side, exportable as HTML / JSON / PDF
- **Rerank / Summarize / Compare** meta-results — turn N model outputs into a ranked list, a synthesised answer, or a structured agreement/disagreement analysis
- **AI Chat** with multi-turn conversations and auto-saved history
- **AI Dual Chat** — two models in conversation with each other
- **AI Flocks** (agent groups) and **AI Swarms** (provider/model groups) for organising configurations
- **Reusable Parameters** and **System Prompts** assignable to agents, flocks, or swarms
- **Model Search** across every provider with seven layered metadata sources (LiteLLM, OpenRouter, models.dev, Helicone, llm-prices, Artificial Analysis, HuggingFace)
- **Six-Tier Cost Tracking** with type breakdown for rerank/summarize/compare API spend
- **API Trace Viewer** — every request and response saved as inspectable JSON
- **Backup / Restore** the entire app to a single zip
- **Configuration Export / Import** for sharing setups across devices

## Requirements

- Android 8.0 (API 26) or higher
- API keys for the providers you want to use

## Installation

Download the latest APK from releases, or build from source:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r ai/build/outputs/apk/debug/ai-debug.apk
```

## Documentation

Full documentation lives in **[doc/](doc/)**. Recommended reading order:

| Document | Purpose |
|---|---|
| [doc/manual.md](doc/manual.md) | End-user walkthrough — every screen and feature |
| [doc/architecture.md](doc/architecture.md) | High-level code map for new contributors |
| [doc/development.md](doc/development.md) | Build, deploy, and how to add things |
| [doc/api-formats.md](doc/api-formats.md) | The three API dispatch paths and their quirks |
| [doc/datastructures.md](doc/datastructures.md) | Every data class with every field |
| [doc/secondary-results.md](doc/secondary-results.md) | Rerank / Summarize / Compare deep dive |
| [doc/providers.md](doc/providers.md) | All 38 providers from `setup.json` |
| [doc/repositories.md](doc/repositories.md) | The seven external metadata sources |
| [doc/persistent.md](doc/persistent.md) | Every prefs key and every persistent file |

[doc/README.md](doc/README.md) is the index with the same list and a bit more orientation.

## Privacy & Security

- **Local Storage Only**: all data stored on device
- **No Analytics**: no tracking or telemetry
- **Secure Keys**: API keys in app's private storage
- **Masked Traces**: sensitive headers masked in API logs

## License

This project is licensed under the [GNU General Public License v2.0](LICENSE).

## Acknowledgments

- **AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI, Moonshot, Cohere, AI21, DashScope, Fireworks, Cerebras, SambaNova, Baichuan, StepFun, MiniMax, NVIDIA, Replicate, Hugging Face, Lambda, Lepton, 01.AI, Doubao, Reka, Writer, Cloudflare Workers AI, DeepInfra, Hyperbolic, Novita.ai, Featherless.ai, Liquid AI, Llama API, Krutrim
- **Model Data**: LiteLLM, OpenRouter, models.dev, Helicone, llm-prices, Artificial Analysis, HuggingFace
- **Android UI**: Jetpack Compose, Material 3
- **Android Networking**: Retrofit, OkHttp, Gson
