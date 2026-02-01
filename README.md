# AI - Multi-Provider AI Report Generator & Chat

An Android app that generates AI-powered reports and enables conversations using 31 different AI services. Compare responses from OpenAI, Anthropic, Google, and 28 other providers, chat with real-time streaming responses, organize agents into flocks and swarms, configure custom API endpoints, track costs, and explore models across all providers.

## Features

- **31 AI Services** with real-time SSE streaming
- **AI Chat** with multi-turn conversations, auto-saved history
- **Multi-Agent Reports** querying providers in parallel, comparing responses side-by-side
- **AI Flocks** (agent groups) and **AI Swarms** (provider/model groups) for organizing configurations
- **AI Parameters** as reusable presets assignable to agents, flocks, or swarms
- **Model Search** across all providers with pricing info from OpenRouter and Hugging Face
- **Six-Tier Cost Tracking** (API > Manual > OpenRouter > LiteLLM > Fallback > Default)
- **HTML Reports** with cost tables, think sections, citations, markdown rendering
- **Configuration Export/Import** for backup and restore
- **Developer Mode** with API tracing, trace viewer, and API test tool
- **External App Integration** via Intent (see `CALL_AI.md`)

## Requirements

- Android 8.0 (API 26) or higher
- Internet connection
- API keys for desired AI services

## Installation

Download the latest APK from releases, or build from source:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Documentation

- **[MANUAL.md](MANUAL.md)** — User guide: setup, features, supported services, troubleshooting
- **[DEVELOP.md](DEVELOP.md)** — Developer guide: architecture, building, internals, data formats
- **[CALL_AI.md](CALL_AI.md)** — External app integration

## Privacy & Security

- **Local Storage Only**: All data stored on device
- **No Analytics**: No tracking or telemetry
- **Secure Keys**: API keys in app's private storage
- **Masked Traces**: Sensitive headers masked in API logs

## License

Private use only. Not for redistribution.

## Acknowledgments

- **AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI, Moonshot, Cohere, AI21, DashScope, Fireworks, Cerebras, SambaNova, Baichuan, StepFun, MiniMax, NVIDIA, Replicate, Hugging Face, Lambda, Lepton, 01.AI, Doubao, Reka, Writer
- **Model Data**: OpenRouter API, Hugging Face API, LiteLLM pricing data
- **UI Framework**: Jetpack Compose, Material 3
- **Networking**: Retrofit, OkHttp, Gson
