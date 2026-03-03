# AI - Multi-Provider AI Report Generator & Chat

Android and macOS apps for AI-powered reports and conversations using 31 different AI services. Compare responses from OpenAI, Anthropic, Google, and 28 other providers, chat with real-time streaming responses, organize agents into flocks and swarms, configure custom API endpoints, track costs, and explore models across all providers.

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
- **External App Integration** via Intent (Android only, see [`android/CALL_AI.md`](android/CALL_AI.md))

## Requirements

### Android
- Android 8.0 (API 26) or higher
- API keys for desired AI services

### macOS
- macOS 14.0 or higher
- API keys for desired AI services

## Installation

### Android
Download the latest APK from releases, or build from source:

```bash
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### macOS
Build from source:

```bash
cd macOS && xcodegen generate
xcodebuild -project macOS/macAI.xcodeproj -scheme macAI -configuration Debug build
```

## Documentation

- **[MANUAL.md](MANUAL.md)** — User guide: setup, features, supported services, troubleshooting
- **[android/DEVELOP.md](android/DEVELOP.md)** — Android developer guide: architecture, building, internals, data formats
- **[android/CALL_AI.md](android/CALL_AI.md)** — Android external app integration via Intents

## Privacy & Security

- **Local Storage Only**: All data stored on device
- **No Analytics**: No tracking or telemetry
- **Secure Keys**: API keys in app's private storage
- **Masked Traces**: Sensitive headers masked in API logs

## License

This project is licensed under the [GNU General Public License v2.0](LICENSE).

## Acknowledgments

- **AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI, Moonshot, Cohere, AI21, DashScope, Fireworks, Cerebras, SambaNova, Baichuan, StepFun, MiniMax, NVIDIA, Replicate, Hugging Face, Lambda, Lepton, 01.AI, Doubao, Reka, Writer
- **Model Data**: OpenRouter API, Hugging Face API, LiteLLM pricing data
- **Android UI**: Jetpack Compose, Material 3
- **Android Networking**: Retrofit, OkHttp, Gson
- **macOS UI**: SwiftUI, native macOS components
