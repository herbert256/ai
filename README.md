# AI - Multi-Provider AI Report Generator

An Android app that generates AI-powered reports using multiple AI services simultaneously. Compare responses from ChatGPT, Claude, Gemini, and 7 other AI providers in a single report.

## Features

- **10 AI Services**: ChatGPT, Claude, Gemini, Grok, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter
- **Multi-Agent Reports**: Generate reports from multiple AI providers in parallel
- **Custom Prompts**: Create and save reusable prompt templates
- **Prompt History**: Quickly reuse previously submitted prompts
- **Report History**: Browse, view, and share previously generated reports
- **HTML Export**: View reports in browser or share via email
- **Developer Mode**: Debug API calls with full request/response tracing

## Screenshots

The app uses a dark Material 3 theme:

| AI Hub | New Report | Results |
|--------|------------|---------|
| Main menu with quick access cards | Enter prompt and select agents | Toggle between agent responses |

## Requirements

- Android 8.0 (API 26) or higher
- Internet connection
- API keys for desired AI services

## Installation

### From APK
1. Download the latest APK from releases
2. Enable "Install from unknown sources" if prompted
3. Install the APK

### Build from Source
```bash
# Requires Java 17
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Quick Start

### 1. Add Your First AI Agent

1. Open app → Settings (gear icon) → AI Setup
2. Tap "AI Agents" → "+ Add Agent"
3. Enter:
   - **Name**: e.g., "My GPT-4"
   - **Provider**: Select ChatGPT
   - **Model**: Select gpt-4o-mini (or fetch models)
   - **API Key**: Your OpenAI API key
4. Save

### 2. Generate a Report

1. From AI Hub, tap "New AI Report"
2. Enter a title and your prompt
3. Tap "Select AI Agents" and choose your agent(s)
4. Tap "Generate"
5. View results, share via browser or email

## Supported AI Services

| Service | Website | Get API Key |
|---------|---------|-------------|
| **ChatGPT** | OpenAI | [platform.openai.com](https://platform.openai.com/api-keys) |
| **Claude** | Anthropic | [console.anthropic.com](https://console.anthropic.com/) |
| **Gemini** | Google | [aistudio.google.com](https://aistudio.google.com/app/apikey) |
| **Grok** | xAI | [console.x.ai](https://console.x.ai/) |
| **Groq** | Groq | [console.groq.com](https://console.groq.com/) |
| **DeepSeek** | DeepSeek | [platform.deepseek.com](https://platform.deepseek.com/) |
| **Mistral** | Mistral AI | [console.mistral.ai](https://console.mistral.ai/) |
| **Perplexity** | Perplexity | [perplexity.ai/settings/api](https://www.perplexity.ai/settings/api) |
| **Together** | Together AI | [api.together.xyz](https://api.together.xyz/settings/api-keys) |
| **OpenRouter** | OpenRouter | [openrouter.ai/keys](https://openrouter.ai/keys) |

### Default Models

| Service | Default Model |
|---------|---------------|
| ChatGPT | gpt-4o-mini |
| Claude | claude-sonnet-4-20250514 |
| Gemini | gemini-2.0-flash |
| Grok | grok-3-mini |
| Groq | llama-3.3-70b-versatile |
| DeepSeek | deepseek-chat |
| Mistral | mistral-small-latest |
| Perplexity | sonar |
| Together | meta-llama/Llama-3.3-70B-Instruct-Turbo |
| OpenRouter | anthropic/claude-3.5-sonnet |

## Features in Detail

### Multi-Agent Reports

Query multiple AI providers simultaneously:
1. Create agents for different services
2. Select multiple agents when generating a report
3. Compare responses side-by-side
4. Toggle visibility of individual responses

### Prompt Templates

Use placeholders in your prompts:
- `@DATE@` - Current date (e.g., "Saturday, January 25th")

### Prompt History

- Automatically saves every submitted prompt
- Browse and search past prompts
- One-tap reuse
- Stores up to 100 entries

### HTML Reports

Generated reports include:
- Report title and timestamp
- Clickable buttons to toggle each agent's response
- Markdown-rendered AI responses
- Citations and sources (when provided by Perplexity)
- Search results (when provided by Grok, Perplexity)
- Related questions (when provided by Perplexity)

### Developer Mode

Enable in Settings → Developer:
- **API Tracing**: Log all API requests and responses
- **Trace Viewer**: Browse and inspect API calls
- **Usage Stats**: See token counts in reports
- **HTTP Headers**: View response headers

### Export & Share

- **View in Chrome**: Opens HTML report in browser
- **Share via Email**: Attaches report as HTML file
- **Export Config**: Share your agent setup as JSON
- **Import Config**: Import agents from clipboard

## App Structure

```
AI Hub (Home)
├── New AI Report
│   ├── Enter prompt
│   ├── Select agents
│   └── Generate → Results
├── Prompt History
│   └── Browse → Tap to reuse
├── AI History
│   └── Browse → View/Share/Delete
└── Settings
    ├── General
    │   └── Pagination size
    ├── AI Setup
    │   ├── Service configs
    │   ├── AI Agents (CRUD)
    │   └── Export/Import
    ├── Developer
    │   └── API tracing
    └── Help
```

## Privacy & Security

- **Local Storage Only**: All data stored on device
- **No Analytics**: No tracking or telemetry
- **Secure Keys**: API keys in app's private storage
- **Masked Traces**: Sensitive headers masked in API logs

## Troubleshooting

### "API key not configured"
Add your API key in Settings → AI Setup → AI Agents

### "Network error"
- Check internet connection
- The app automatically retries once on failure

### "Model not found"
- The model may have been deprecated
- Try fetching fresh models or select a different one

### Slow responses
- AI APIs can take several minutes for complex prompts
- Read timeout is set to 7 minutes

### Debug API issues
1. Enable Developer Mode (Settings → Developer)
2. Enable "Track API calls"
3. Reproduce the issue
4. Check trace viewer (bug icon in title bar)
5. Inspect request/response details

## Technical Details

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3
- **Architecture**: MVVM with StateFlow
- **Networking**: Retrofit 2 with OkHttp
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Codebase**: ~8,500 lines across 23 files

## Building

### Prerequisites
- Android Studio Hedgehog or newer
- Java 17
- Android SDK 34

### Debug Build
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

### Release Build
Create `local.properties`:
```properties
KEYSTORE_FILE=/path/to/keystore.jks
KEYSTORE_PASSWORD=your_password
KEY_ALIAS=your_alias
KEY_PASSWORD=your_key_password
```

Build:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

## License

Private use only. Not for redistribution.

## Acknowledgments

- **AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter
- **UI Framework**: Jetpack Compose, Material 3
- **Networking**: Retrofit, OkHttp, Gson

---

*AI - Compare AI providers, generate insights.*
