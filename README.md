# AI - Multi-Provider AI Report Generator

An Android app that generates AI-powered reports using multiple AI services simultaneously. Compare responses from ChatGPT, Claude, Gemini, and 7 other AI providers in a single report.

## Features

### Core Features
- **10 AI Services**: ChatGPT, Claude, Gemini, Grok, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter
- **Multi-Agent Reports**: Query multiple AI providers in parallel, compare responses side-by-side
- **Advanced Parameters**: Configure temperature, max tokens, system prompts, and more per agent
- **Real-time Progress**: Watch as each agent completes, with option to stop early

### History & Organization
- **Prompt History**: Automatically saves prompts (up to 100), one-tap reuse
- **Report History**: Browse, view, and share previously generated HTML reports
- **Paginated Lists**: Configurable page size (5-50 items)

### Export & Sharing
- **HTML Reports**: View in browser or share via email
- **Markdown Rendering**: AI responses rendered with formatting
- **Citations & Sources**: Perplexity citations, Grok/Perplexity search results displayed
- **Configuration Export**: Backup/restore your agents as JSON

### Developer Features
- **API Tracing**: Log all API requests and responses
- **Trace Viewer**: Inspect request/response details with header masking
- **Token Usage**: See input/output token counts in reports
- **HTTP Headers**: View response headers in developer mode

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
   - **Model**: Select gpt-4o-mini (or fetch models with API key)
   - **API Key**: Your OpenAI API key
4. Optionally expand "Advanced Parameters" to configure:
   - Temperature, Max Tokens, Top P, etc.
   - System Prompt for custom instructions
5. Save (tests API automatically)

### 2. Generate a Report

1. From AI Hub, tap "New AI Report"
2. Enter a title and your prompt
3. Tap "Generate" button at top
4. Select your agent(s) in the dialog
5. Watch progress as agents respond
6. View results, toggle between agents, export or share

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

### Service-Specific Features

| Service | Special Features |
|---------|-----------------|
| **ChatGPT** | JSON response format, Responses API for GPT-5.x/o3/o4 |
| **Claude** | Top-K parameter, content blocks |
| **Gemini** | System instruction, generation config |
| **Grok** | Web search toggle, returns search results |
| **Perplexity** | Citations, search results, related questions, recency filter |
| **DeepSeek** | Reasoning content support |

## Agent Parameters

Each agent can be configured with advanced parameters (availability varies by provider):

| Parameter | Description | Providers |
|-----------|-------------|-----------|
| **Temperature** | Randomness (0.0-2.0). Lower = focused, higher = creative | All |
| **Max Tokens** | Maximum response length | All |
| **Top P** | Nucleus sampling threshold (0.0-1.0) | All |
| **Top K** | Limits vocabulary choices per token | Claude, Gemini, Together, OpenRouter |
| **Frequency Penalty** | Reduces repetition of frequent tokens (-2.0 to 2.0) | ChatGPT, Grok, Groq, DeepSeek, Perplexity, Together, OpenRouter |
| **Presence Penalty** | Encourages discussing new topics (-2.0 to 2.0) | ChatGPT, Grok, Groq, DeepSeek, Perplexity, Together, OpenRouter |
| **System Prompt** | Instructions for AI behavior | All |
| **Stop Sequences** | Strings that stop generation | Most |
| **Seed** | For reproducible outputs | ChatGPT, Groq, Mistral, OpenRouter |
| **JSON Format** | Force JSON response | ChatGPT |
| **Web Search** | Enable web search | Grok |
| **Return Citations** | Include source URLs | Perplexity |
| **Search Recency** | Filter by time (day/week/month/year) | Perplexity |

## Features in Detail

### Multi-Agent Reports

Query multiple AI providers simultaneously:
1. Create agents for different services (or same service with different parameters)
2. Select multiple agents when generating a report
3. Compare responses side-by-side
4. Toggle visibility of individual responses
5. Stop generation early if needed (incomplete agents show "Not ready")

### Prompt Templates

Use placeholders in your prompts:
- `@DATE@` - Current date (e.g., "Saturday, January 25th")

### HTML Reports

Generated reports include:
- Report title and timestamp
- Clickable buttons to toggle each agent's response
- Markdown-rendered AI responses
- Citations and sources (when provided)
- Search results and related questions (when provided)
- Token usage and HTTP headers (developer mode only)
- Original prompt at bottom

### Export & Share

- **View in Chrome**: Opens HTML report in browser
- **Share via Email**: Attaches report as HTML file
- **Export Config**: Share your agent setup as JSON (version 4 format)
- **Import Config**: Import agents from JSON file

## Integration with Other Apps

The AI app can be launched from other Android applications to generate reports. This allows apps like chess analyzers, note-taking apps, or any other app to leverage AI analysis without implementing their own AI integrations.

### How to Call the AI App

From your Android app, send an intent:

```kotlin
val intent = Intent().apply {
    action = "com.ai.ACTION_NEW_REPORT"
    setPackage("com.ai")
    putExtra("title", "My Analysis")      // Optional: report title
    putExtra("prompt", "Analyze this...") // Required: the prompt
}
startActivity(intent)
```

### What Happens

1. AI app opens with the New Report screen
2. Title and prompt are pre-filled from the intent
3. User selects which AI agents to use
4. Report is generated and displayed in the AI app
5. User can view, export, or share the results

### Check if AI App is Installed

```kotlin
val intent = Intent("com.ai.ACTION_NEW_REPORT")
intent.setPackage("com.ai")

if (intent.resolveActivity(packageManager) != null) {
    startActivity(intent)
} else {
    // Show message that AI app needs to be installed
}
```

For detailed integration documentation, see `CALL_AI.md`.

## App Structure

```
AI Hub (Home)
├── New AI Report
│   ├── Enter title + prompt
│   ├── Generate → Select agents
│   ├── Progress with real-time updates
│   └── Results → View/Export/Share
├── Prompt History
│   └── Browse → Tap to reuse
├── AI History
│   └── Browse → View/Share/Delete
└── Settings
    ├── General
    │   ├── Pagination size (5-50)
    │   └── Developer mode toggle
    ├── AI Setup
    │   ├── Service configs (API keys, models)
    │   ├── AI Agents (with parameters)
    │   └── Export/Import
    ├── Developer
    │   └── API tracing toggle
    └── Help
```

## Privacy & Security

- **Local Storage Only**: All data stored on device
- **No Analytics**: No tracking or telemetry
- **Secure Keys**: API keys in app's private storage
- **Masked Traces**: Sensitive headers masked in API logs (shows first 4 + last 4 chars)

## Troubleshooting

### "API key not configured"
Add your API key in Settings → AI Setup → AI Agents

### "Network error"
- Check internet connection
- The app automatically retries once on failure (500ms delay)

### "Model not found"
- The model may have been deprecated
- Try fetching fresh models or select a different one
- For Claude/Perplexity: models are hardcoded (no list API)

### Slow responses
- AI APIs can take several minutes for complex prompts
- Read timeout is set to 7 minutes
- You can tap STOP to end early

### Agent parameters not working
- Some parameters are provider-specific
- Check the parameter availability table above
- Parameters left empty use provider defaults

### Debug API issues
1. Enable Developer Mode (Settings → General)
2. Enable "Track API calls" (Settings → Developer)
3. Reproduce the issue
4. Check trace viewer (bug icon in title bar)
5. Inspect request/response details
6. Share trace file for support

## Technical Details

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3 (dark theme)
- **Architecture**: MVVM with StateFlow
- **Networking**: Retrofit 2 with OkHttp
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Codebase**: ~9,300 lines across 23 files

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

## Configuration Export Format

Version 5 JSON format (supports importing versions 3, 4, and 5):
```json
{
  "version": 5,
  "providers": {
    "CHATGPT": {
      "modelSource": "API",
      "manualModels": [],
      "apiKey": "sk-..."
    }
  },
  "agents": [
    {
      "id": "uuid",
      "name": "My Agent",
      "provider": "CHATGPT",
      "model": "gpt-4o",
      "apiKey": "sk-...",
      "parameters": {
        "temperature": 0.7,
        "maxTokens": 2048,
        "topP": null,
        "topK": null,
        "frequencyPenalty": null,
        "presencePenalty": null,
        "systemPrompt": "You are helpful.",
        "stopSequences": null,
        "seed": null,
        "responseFormatJson": false,
        "searchEnabled": false,
        "returnCitations": true,
        "searchRecency": null
      }
    }
  ]
}
```

## License

Private use only. Not for redistribution.

## Acknowledgments

- **AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter
- **UI Framework**: Jetpack Compose, Material 3
- **Networking**: Retrofit, OkHttp, Gson

---

*AI - Compare AI providers, generate insights, configure with precision.*
