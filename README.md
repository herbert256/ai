# AI - Multi-Provider AI Report Generator & Chat

An Android app that generates AI-powered reports and enables conversations using 13 different AI services. Compare responses from OpenAI, Anthropic, Google, and 10 other AI providers, chat with real-time streaming responses, configure custom API endpoints, track costs, and explore models across all providers.

## Features

### Core Features
- **13 AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI, plus DUMMY for testing
- **AI Chat**: Multi-turn conversations with real-time streaming responses (word-by-word), auto-saved history
- **Multi-Agent Reports**: Query multiple AI providers in parallel, compare responses side-by-side
- **AI Swarms**: Group agents together for quick selection during report generation
- **Advanced Parameters**: Configure temperature, max tokens, system prompts, and more per agent
- **Real-time Progress**: Watch as each agent completes, with token counts and costs per agent

### Provider Endpoints
- **Multiple Endpoints**: Configure multiple API endpoints per provider
- **Pre-configured Defaults**: OpenAI (Chat/Responses API), DeepSeek (Standard/Beta), Mistral (Standard/Codestral), SiliconFlow (OpenAI/Anthropic), Z.AI (General/Coding)
- **Custom Endpoints**: Add your own endpoints for self-hosted or proxy servers
- **Model List URLs**: Configure custom URLs for fetching available models

### Agent Configuration
- **Flexible Inheritance**: Agents can inherit API key, model, and endpoint from provider
- **Quick Setup**: "Create default agents" button creates one agent per configured provider
- **Per-Agent Endpoints**: Override provider endpoint for specific agents
- **Test Connectivity**: Test button verifies API key and endpoint work correctly

### Model Discovery
- **Model Search**: Search all models across all configured providers in one place
- **Model Info**: View detailed model information from OpenRouter (pricing, context length) and Hugging Face (downloads, likes, tags)
- **Quick Actions**: Start a chat or create an agent directly from model search results

### Cost Tracking
- **AI Costs**: Track estimated costs for all API usage
- **Four-Tier Pricing**: Manual overrides > OpenRouter API (weekly cached) > LiteLLM (bundled) > Fallback
- **Cost Configuration**: Set manual price overrides per model in dollars per million tokens
- **Per-Agent Costs**: See input/output token counts and cost in cents for each agent response

### History & Organization
- **Chat History**: Automatically saved conversations with streaming support, tap to continue
- **Report History**: Browse, view, share (JSON or HTML), and delete generated reports
- **Prompt History**: Automatically saves prompts (up to 100), one-tap reuse
- **AI Statistics**: Track API calls, input/output tokens per provider and model
- **Swarm Selection Memory**: Remembers your last selected swarms for convenience

### Export & Sharing
- **Share Reports**: Export as JSON (raw data) or HTML (formatted report)
- **View in Browser**: Open interactive HTML report with collapsible sections
- **Markdown Rendering**: AI responses rendered with formatting
- **Collapsible Think Sections**: AI reasoning hidden behind expandable buttons
- **Citations & Sources**: Perplexity citations, xAI/Perplexity search results displayed
- **Configuration Export**: Backup/restore your complete setup as JSON (v11 format)

### Developer Features
- **API Tracing**: Log all API requests and responses
- **Trace Viewer**: Inspect request/response details with header masking
- **Token Usage**: See input/output token counts in reports
- **HTTP Headers**: View response headers in developer mode
- **DUMMY Provider**: Local test server for development

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

### 1. Configure a Provider

1. Open app → Settings (gear icon) → AI Setup
2. Tap "AI Providers"
3. Select a provider (e.g., OpenAI)
4. Enter your API key
5. (Optional) Configure endpoints if needed
6. Save

### 2. Create Your First Agent

**Quick Method:**
1. Go to AI Setup → AI Agents
2. Tap "Create default agents" to auto-create agents for all configured providers

**Manual Method:**
1. Go to AI Setup → AI Agents
2. Tap "+ Add Agent"
3. Enter:
   - **Name**: e.g., "My GPT-4"
   - **Provider**: Select OpenAI
   - **Model**: (Optional) Leave empty to use provider default, or tap Select to choose
   - **API Key**: (Optional) Leave empty to use provider key
   - **Endpoint**: (Optional) Tap Select to choose specific endpoint
4. Optionally expand "Advanced Parameters" to configure:
   - Temperature, Max Tokens, Top P, etc.
   - System Prompt for custom instructions
5. Tap Test to verify connectivity
6. Save

### 3. Create a Swarm (Optional)

1. Go to AI Setup → AI Swarms
2. Tap "Add Swarm"
3. Enter a name and select agents to include
4. Save - now you can select this swarm for quick agent selection

### 4. Start Using the App

**For Chat (Recommended Start):**
1. From AI Hub, tap "AI Chat"
2. Choose "New chat" or "Continue previous"
3. Select a provider with a configured API key
4. Select a model
5. Start chatting - responses stream in real-time, word by word
6. Conversations are automatically saved

**For Reports:**
1. From AI Hub, tap "AI Report"
2. Enter a title and your prompt
3. Tap "Generate" button at top
4. Select individual agents or swarms in the dialog
5. Watch progress with token counts and costs
6. View results, share as JSON/HTML, or open in browser

**For Model Discovery:**
1. From AI Hub, tap "AI Models"
2. Search across all providers
3. Tap a model to view info, start chat, or create an agent

## Supported AI Services

| Service | Website | Get API Key |
|---------|---------|-------------|
| **OpenAI** | OpenAI | [platform.openai.com](https://platform.openai.com/api-keys) |
| **Anthropic** | Anthropic | [console.anthropic.com](https://console.anthropic.com/) |
| **Google** | Google | [aistudio.google.com](https://aistudio.google.com/app/apikey) |
| **xAI** | xAI | [console.x.ai](https://console.x.ai/) |
| **Groq** | Groq | [console.groq.com](https://console.groq.com/) |
| **DeepSeek** | DeepSeek | [platform.deepseek.com](https://platform.deepseek.com/) |
| **Mistral** | Mistral AI | [console.mistral.ai](https://console.mistral.ai/) |
| **Perplexity** | Perplexity | [perplexity.ai/settings/api](https://www.perplexity.ai/settings/api) |
| **Together** | Together AI | [api.together.xyz](https://api.together.xyz/settings/api-keys) |
| **OpenRouter** | OpenRouter | [openrouter.ai/keys](https://openrouter.ai/keys) |
| **SiliconFlow** | SiliconFlow | [siliconflow.cn](https://siliconflow.cn/) |
| **Z.AI** | Z.AI | [z.ai](https://z.ai/) |

### Default Models

| Service | Default Model |
|---------|---------------|
| OpenAI | gpt-4o-mini |
| Anthropic | claude-sonnet-4-20250514 |
| Google | gemini-2.0-flash |
| xAI | grok-3-mini |
| Groq | llama-3.3-70b-versatile |
| DeepSeek | deepseek-chat |
| Mistral | mistral-small-latest |
| Perplexity | sonar |
| Together | meta-llama/Llama-3.3-70B-Instruct-Turbo |
| OpenRouter | anthropic/claude-3.5-sonnet |
| SiliconFlow | Qwen/Qwen2.5-7B-Instruct |
| Z.AI | glm-4.7-flash |

### Service-Specific Features

| Service | Special Features |
|---------|-----------------|
| **OpenAI** | JSON response format, Chat Completions API (gpt-4o), Responses API (gpt-5.x/o3/o4), streaming |
| **Anthropic** | Top-K parameter, content blocks, streaming |
| **Google** | System instruction, generation config, streaming |
| **xAI** | Web search toggle, returns search results, streaming |
| **Perplexity** | Citations, search results, related questions, recency filter, streaming |
| **DeepSeek** | Reasoning content support (think sections), Standard + Beta endpoints, streaming |
| **Mistral** | Standard + Codestral endpoints (code generation), streaming |
| **SiliconFlow** | OpenAI + Anthropic compatible endpoints, Chinese AI models, streaming |
| **Z.AI** | General + Coding endpoints, GLM models, streaming |

### Provider Endpoints

Some providers have multiple API endpoints for different use cases:

| Provider | Endpoints |
|----------|-----------|
| **OpenAI** | Chat Completions (gpt-4o, gpt-4, gpt-3.5), Responses API (gpt-5.x, o3, o4) |
| **DeepSeek** | Standard (chat/completions), Beta (FIM/prefix completion) |
| **Mistral** | Standard (api.mistral.ai), Codestral (codestral.mistral.ai for code) |
| **SiliconFlow** | Chat Completions (OpenAI compatible), Messages (Anthropic compatible) |
| **Z.AI** | General (chat completions), Coding (GLM Coding Plan) |

## Agent Parameters

Each agent can be configured with advanced parameters (availability varies by provider):

| Parameter | Description | Providers |
|-----------|-------------|-----------|
| **Temperature** | Randomness (0.0-2.0). Lower = focused, higher = creative | All |
| **Max Tokens** | Maximum response length | All |
| **Top P** | Nucleus sampling threshold (0.0-1.0) | All |
| **Top K** | Limits vocabulary choices per token | Anthropic, Google, Together, OpenRouter, SiliconFlow, Z.AI |
| **Frequency Penalty** | Reduces repetition of frequent tokens (-2.0 to 2.0) | OpenAI, xAI, Groq, DeepSeek, Perplexity, Together, OpenRouter, SiliconFlow, Z.AI |
| **Presence Penalty** | Encourages discussing new topics (-2.0 to 2.0) | OpenAI, xAI, Groq, DeepSeek, Perplexity, Together, OpenRouter, SiliconFlow, Z.AI |
| **System Prompt** | Instructions for AI behavior | All |
| **Stop Sequences** | Strings that stop generation | Most |
| **Seed** | For reproducible outputs | OpenAI, Groq, Mistral, OpenRouter |
| **JSON Format** | Force JSON response | OpenAI |
| **Web Search** | Enable web search | xAI |
| **Return Citations** | Include source URLs | Perplexity |
| **Search Recency** | Filter by time (day/week/month/year) | Perplexity |

### Agent Inheritance

Agents can inherit settings from their provider:
- **API Key**: Leave empty to use provider's API key
- **Model**: Leave empty to use provider's default model
- **Endpoint**: Leave empty to use provider's default endpoint

This allows quick setup while maintaining flexibility for specific configurations.

## Features in Detail

### AI Chat with Streaming

Have real-time conversations with any AI:
1. Select a provider (shows only configured ones)
2. Select a model (search to filter)
3. Optionally configure parameters (system prompt, temperature, etc.)
4. Start chatting - responses appear word by word as they're generated
5. Conversations are automatically saved to Chat History
6. Continue any previous conversation from Chat History

### AI Reports

Generate comparative reports from multiple AI agents:
1. Enter a title (optional) and your prompt
2. Use `@DATE@` to insert current date automatically
3. Select agents or swarms to query
4. Watch real-time progress with:
   - Agent name, provider, model
   - Status (pending, running, success, error)
   - Input/output token counts
   - Cost in cents per agent
5. When complete:
   - **View** - See formatted responses in-app
   - **Share** - Export as JSON or HTML
   - **Browser** - Open interactive HTML report

### AI Costs

Track your AI spending:
- **Total cost** across all models
- **Expandable provider groups** showing per-model breakdown
- **Pricing sources** (color-coded): Manual, OpenRouter, LiteLLM, Fallback
- **Refresh button** to update OpenRouter pricing (cached weekly)
- **Cost Configuration** in Settings for manual price overrides

### AI Swarms

Group your agents for convenient selection:
1. Create swarms in AI Setup → AI Swarms
2. Add any configured agents to a swarm
3. When generating a report, select a swarm to include all its agents
4. Your swarm selections are remembered for next time

### AI Prompts

Create internal prompts for app features:
1. Go to AI Setup → AI Prompts
2. Create a prompt with a unique name (e.g., "model_info")
3. Assign an agent to execute the prompt
4. Use variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@
5. The app uses these prompts for features like auto-generating model descriptions

### Model Search

Search and explore models across all providers:
1. Unified search across all configured providers
2. Filter by model name
3. Color-coded by provider
4. When you tap a model, choose:
   - **Start AI Chat** - Begin chatting with this model
   - **Create AI Agent** - Create an agent using this model
   - **Model Info** - View detailed model information

### Model Info

View detailed information about any model:
- **From OpenRouter** (if API key configured): Context length, max tokens, pricing per token, architecture
- **From Hugging Face** (if API key configured): Author, downloads, likes, tags, license
- **AI-generated description**: Create an AI Prompt named "model_info" to auto-generate introductions

### AI Statistics

Track your AI usage:
- Total API calls made
- Input and output token counts
- Usage per provider and model
- Clear statistics button

### Think Sections

Some AI models (like DeepSeek reasoning models) include `<think>...</think>` sections showing their reasoning process:
- In-app viewer: Click the "Think" button to expand/collapse reasoning
- HTML reports: JavaScript-powered collapsible sections
- Helps understand AI decision-making while keeping responses clean

### Prompt Templates

Use placeholders in your prompts:
- `@DATE@` - Current date (e.g., "Saturday, January 25th")

### HTML Reports

Generated reports include:
- Report title and timestamp
- Clickable buttons to toggle each agent's response
- Collapsible think sections for AI reasoning
- Markdown-rendered AI responses
- Citations and sources (when provided)
- Search results and related questions (when provided)
- Token usage and HTTP headers (developer mode only)
- Original prompt at bottom

### Export & Share

- **View in Chrome**: Opens HTML report in browser
- **Share as JSON**: Raw report data for processing
- **Share as HTML**: Formatted report for viewing
- **Export Config**: Share your complete setup as JSON (v11 format)
- **Import Config**: Import from JSON file or clipboard

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
3. User selects which AI agents or swarms to use
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
├── AI Report
│   ├── Enter title + prompt
│   ├── Generate → Select agents/swarms
│   ├── Progress with token counts & costs
│   └── Results → View/Share (JSON/HTML)/Browser
├── AI Chat
│   ├── Select provider + model
│   ├── Configure parameters
│   └── Streaming conversation (auto-saved)
├── AI Models
│   ├── Search across all providers
│   └── View info / Start chat / Create agent
├── AI History
│   └── Browse → View/Share/Browser/Delete reports
├── AI Statistics
│   └── API calls, token usage per provider/model
├── AI Costs
│   └── Estimated costs with expandable provider groups
└── Settings
    ├── General
    │   ├── Username for chat display
    │   ├── Full screen mode toggle
    │   ├── Developer mode toggle
    │   └── API tracing toggle
    ├── Cost Configuration
    │   └── Manual price overrides per model
    ├── AI Setup
    │   ├── AI Providers (API keys, endpoints, models)
    │   ├── AI Agents (with parameters, endpoint selection)
    │   ├── AI Swarms (agent groups)
    │   ├── AI Prompts (internal app prompts)
    │   ├── Create default agents
    │   ├── Refresh model lists
    │   └── Export/Import configuration (v11)
    ├── External Services
    │   └── Hugging Face API Key
    └── Help
```

## Privacy & Security

- **Local Storage Only**: All data stored on device
- **No Analytics**: No tracking or telemetry
- **Secure Keys**: API keys in app's private storage
- **Masked Traces**: Sensitive headers masked in API logs (shows first 4 + last 4 chars)

## Troubleshooting

### "API key not configured"
Add your API key in Settings → AI Setup → AI Providers

### "Network error"
- Check internet connection
- The app automatically retries once on failure (500ms delay)

### "Model not found"
- The model may have been deprecated
- Use "Refresh model lists" in AI Setup to update
- For Claude/Perplexity: models are hardcoded (no list API)

### Wrong endpoint being used
- Check provider settings for correct default endpoint
- OpenAI: gpt-4o uses Chat Completions, gpt-5.x/o3/o4 use Responses API
- Override at agent level if needed

### Slow responses
- AI APIs can take several minutes for complex prompts
- Read timeout is set to 7 minutes
- You can tap STOP to end early

### Agent parameters not working
- Some parameters are provider-specific
- Check the parameter availability table above
- Parameters left empty use provider defaults

### Agent using wrong API key/model
- Empty fields inherit from provider settings
- Fill in specific values to override
- Check provider has correct defaults configured

### Streaming not working
- All 13 providers support streaming
- If issues occur, check your API key and network connection
- Falls back gracefully if partial content received

### No pricing data
- Add OpenRouter API key for best pricing coverage
- Use Cost Configuration to set manual prices
- LiteLLM bundled data provides fallback pricing

### Debug API issues
1. Enable Developer Mode (Settings → General)
2. Enable "Track API calls" in the same card
3. Reproduce the issue
4. Check trace viewer (bug icon in title bar)
5. Inspect request/response details
6. Share trace file for support

## Technical Details

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3 (dark theme)
- **Architecture**: MVVM with StateFlow
- **Networking**: Retrofit 2 with OkHttp
- **Streaming**: Server-Sent Events (SSE) with Kotlin Flow
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Codebase**: ~26,000 lines across 25+ files

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

Version 11 JSON format:
```json
{
  "version": 11,
  "huggingFaceApiKey": "hf_...",
  "providers": {
    "OPENAI": {
      "modelSource": "API",
      "manualModels": [],
      "apiKey": "sk-...",
      "defaultModel": "gpt-4o-mini",
      "adminUrl": "https://platform.openai.com/usage",
      "modelListUrl": null
    }
  },
  "agents": [
    {
      "id": "uuid",
      "name": "My Agent",
      "provider": "OPENAI",
      "model": "gpt-4o",
      "apiKey": "sk-...",
      "endpointId": "openai-chat-completions",
      "parameters": {
        "temperature": 0.7,
        "maxTokens": 2048,
        "systemPrompt": "You are helpful."
      }
    }
  ],
  "swarms": [
    {
      "id": "uuid",
      "name": "My Swarm",
      "agentIds": ["agent-uuid-1", "agent-uuid-2"]
    }
  ],
  "aiPrompts": [
    {
      "id": "uuid",
      "name": "model_info",
      "agentId": "agent-uuid",
      "promptText": "Describe @MODEL@ from @PROVIDER@"
    }
  ],
  "manualPricing": [
    {
      "key": "OPENAI:gpt-4o",
      "promptPrice": 0.0000025,
      "completionPrice": 0.00001
    }
  ],
  "providerEndpoints": [
    {
      "provider": "OPENAI",
      "endpoints": [
        {
          "id": "openai-chat-completions",
          "name": "Chat Completions (gpt-4o, gpt-4, gpt-3.5)",
          "url": "https://api.openai.com/v1/chat/completions",
          "isDefault": true
        }
      ]
    }
  ]
}
```

**Note:** Import requires version 11 format.

## License

Private use only. Not for redistribution.

## Acknowledgments

- **AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI
- **Model Data**: OpenRouter API, Hugging Face API, LiteLLM pricing data
- **UI Framework**: Jetpack Compose, Material 3
- **Networking**: Retrofit, OkHttp, Gson

---

*AI - Compare AI providers, chat with streaming, configure endpoints, track costs, generate insights.*
