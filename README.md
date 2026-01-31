# AI - Multi-Provider AI Report Generator & Chat

An Android app that generates AI-powered reports and enables conversations using 31 different AI services. Compare responses from OpenAI, Anthropic, Google, and 28 other AI providers, chat with real-time streaming responses, organize agents into flocks and swarms, configure custom API endpoints, track costs, and explore models across all providers.

## Features

### Core Features
- **31 AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together, OpenRouter, SiliconFlow, Z.AI, Moonshot, Cohere, AI21, DashScope, Fireworks, Cerebras, SambaNova, Baichuan, StepFun, MiniMax, NVIDIA, Replicate, Hugging Face, Lambda, Lepton, 01.AI, Doubao, Reka, Writer
- **AI Chat**: Multi-turn conversations with real-time streaming responses (word-by-word), auto-saved history
- **Multi-Agent Reports**: Query multiple AI providers in parallel, compare responses side-by-side
- **AI Flocks**: Group agents together for quick selection during report generation
- **AI Swarms**: Group provider/model pairs without needing full agent configurations
- **AI Parameters**: Create reusable parameter presets and assign them to agents, flocks, or swarms
- **Real-time Progress**: Watch as each agent completes, with token counts, costs, and API duration

### Provider Endpoints
- **Multiple Endpoints**: Configure multiple API endpoints per provider
- **Pre-configured Defaults**: OpenAI (Chat/Responses API), DeepSeek (Standard/Beta), Mistral (Standard/Codestral), SiliconFlow (OpenAI/Anthropic), Z.AI (General/Coding)
- **Custom Endpoints**: Add your own endpoints for self-hosted or proxy servers
- **Model List URLs**: Configure custom URLs for fetching available models

### Agent Configuration
- **Flexible Inheritance**: Agents can inherit API key, model, and endpoint from provider
- **Quick Setup**: "Create default agents" button creates one agent per configured provider
- **Per-Agent Endpoints**: Override provider endpoint for specific agents
- **Parameter Presets**: Assign reusable parameter presets to agents
- **Test Connectivity**: Test button verifies API key and endpoint work correctly

### Model Discovery
- **Model Search**: Search all models across all configured providers in one place
- **Model Info**: View detailed model information from OpenRouter (pricing, context length) and Hugging Face (downloads, likes, tags)
- **Quick Actions**: Start a chat or create an agent directly from model search results

### Cost Tracking
- **AI Costs**: Track estimated costs for all API usage
- **Six-Tier Pricing**: API response > Manual overrides > OpenRouter API (weekly cached) > LiteLLM (bundled) > Fallback > Default
- **Cost Configuration**: Set manual price overrides per model in dollars per million tokens
- **Per-Agent Costs**: See input/output token counts, cost in cents, and API duration for each agent response
- **Pricing Display**: Per-M-token input/output pricing shown on all agent and model selection screens
- **HTML Cost Table**: Generated reports include a summary table with provider, model, duration, tokens, and costs

### History & Organization
- **Chat History**: Automatically saved conversations with streaming support, tap to continue
- **Report History**: Browse, view, share (JSON or HTML), and delete generated reports
- **Prompt History**: Automatically saves prompts (up to 100), one-tap reuse
- **AI Statistics**: Track API calls, input/output tokens per provider and model
- **Selection Memory**: Remembers your last selected flocks/swarms for convenience

### Export & Sharing
- **Share Reports**: Export as JSON (raw data) or HTML (formatted report with cost table)
- **View in Browser**: Open interactive HTML report with collapsible sections
- **Markdown Rendering**: AI responses rendered with formatting
- **Collapsible Think Sections**: AI reasoning hidden behind expandable buttons
- **Citations & Sources**: Perplexity citations, xAI/Perplexity search results displayed
- **User Content Tags**: Use `<user>...</user>` tags in prompts to embed content shown in HTML reports but not sent to AI
- **Configuration Export**: Backup/restore your complete setup as JSON (v17 format)

### Developer Features
- **API Tracing**: Log all API requests and responses
- **Trace Viewer**: Inspect request/response details with JSON tree view and header masking
- **API Test**: Send custom JSON requests to any provider endpoint
- **Token Usage**: See input/output token counts and API duration in reports
- **HTTP Headers**: View response headers in developer mode
- **Housekeeping**: Test all provider connections, refresh model lists, cleanup tools

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

1. Open app -> Settings (gear icon) -> AI Setup
2. Tap "AI Providers"
3. Select a provider (e.g., OpenAI)
4. Enter your API key
5. (Optional) Configure endpoints if needed
6. Save

### 2. Create Your First Agent

**Quick Method:**
1. Go to AI Setup -> AI Agents
2. Tap "Create default agents" to auto-create agents for all configured providers

**Manual Method:**
1. Go to AI Setup -> AI Agents
2. Tap "+ Add Agent"
3. Enter name, select provider and model
4. Optionally assign parameter presets
5. Tap Test to verify connectivity
6. Save

### 3. Create a Flock or Swarm (Optional)

**Flock (agent group):**
1. Go to AI Setup -> AI Flocks
2. Tap "Add Flock", name it, select agents
3. Optionally assign parameter presets

**Swarm (provider/model group):**
1. Go to AI Setup -> AI Swarms
2. Tap "Add Swarm", name it, select provider/model pairs
3. Optionally assign parameter presets

### 4. Start Using the App

**For Chat (Recommended Start):**
1. From AI Hub, tap "AI Chat"
2. Choose "New chat" or "Continue previous"
3. Select a provider or a pre-configured agent
4. Select a model
5. Start chatting - responses stream in real-time
6. Conversations are automatically saved

**For Reports:**
1. From AI Hub, tap "AI Report"
2. Enter a title and your prompt
3. Tap "Generate" button at top
4. Select flocks, swarms, or individual agents
5. Watch progress with token counts, costs, and duration
6. View results, share as JSON/HTML, or open in browser

**For Model Discovery:**
1. From AI Hub, tap "AI Models"
2. Search across all providers
3. Tap a model to view info, start chat, or create an agent

## Supported AI Services

| Service | Default Model | Website |
|---------|---------------|---------|
| **OpenAI** | gpt-4o-mini | [platform.openai.com](https://platform.openai.com/api-keys) |
| **Anthropic** | claude-sonnet-4-20250514 | [console.anthropic.com](https://console.anthropic.com/) |
| **Google** | gemini-2.0-flash | [aistudio.google.com](https://aistudio.google.com/app/apikey) |
| **xAI** | grok-3-mini | [console.x.ai](https://console.x.ai/) |
| **Groq** | llama-3.3-70b-versatile | [console.groq.com](https://console.groq.com/) |
| **DeepSeek** | deepseek-chat | [platform.deepseek.com](https://platform.deepseek.com/) |
| **Mistral** | mistral-small-latest | [console.mistral.ai](https://console.mistral.ai/) |
| **Perplexity** | sonar | [perplexity.ai](https://www.perplexity.ai/settings/api) |
| **Together** | Llama-3.3-70B-Instruct-Turbo | [api.together.xyz](https://api.together.xyz/settings/api-keys) |
| **OpenRouter** | anthropic/claude-3.5-sonnet | [openrouter.ai](https://openrouter.ai/keys) |
| **SiliconFlow** | Qwen/Qwen2.5-7B-Instruct | [siliconflow.cn](https://siliconflow.cn/) |
| **Z.AI** | glm-4.7-flash | [open.bigmodel.cn](https://open.bigmodel.cn/) |
| **Moonshot** | kimi-latest | [platform.moonshot.ai](https://platform.moonshot.ai/) |
| **Cohere** | command-a-03-2025 | [dashboard.cohere.com](https://dashboard.cohere.com/) |
| **AI21** | jamba-mini | [studio.ai21.com](https://studio.ai21.com/) |
| **DashScope** | qwen-plus | [dashscope.console.aliyun.com](https://dashscope.console.aliyun.com/) |
| **Fireworks** | llama-v3p3-70b-instruct | [app.fireworks.ai](https://app.fireworks.ai/) |
| **Cerebras** | llama-3.3-70b | [cloud.cerebras.ai](https://cloud.cerebras.ai/) |
| **SambaNova** | Meta-Llama-3.3-70B-Instruct | [cloud.sambanova.ai](https://cloud.sambanova.ai/) |
| **Baichuan** | Baichuan4-Turbo | [platform.baichuan-ai.com](https://platform.baichuan-ai.com/) |
| **StepFun** | step-2-16k | [platform.stepfun.com](https://platform.stepfun.com/) |
| **MiniMax** | MiniMax-M2.1 | [platform.minimax.io](https://platform.minimax.io/) |
| **NVIDIA** | llama-3.1-nemotron-70b-instruct | [build.nvidia.com](https://build.nvidia.com/) |
| **Replicate** | meta/meta-llama-3-70b-instruct | [replicate.com](https://replicate.com/) |
| **Hugging Face** | Llama-3.1-70B-Instruct | [huggingface.co](https://huggingface.co/settings/tokens) |
| **Lambda** | hermes-3-llama-3.1-405b-fp8 | [cloud.lambdalabs.com](https://cloud.lambdalabs.com/) |
| **Lepton** | llama3-1-70b | [dashboard.lepton.ai](https://dashboard.lepton.ai/) |
| **01.AI** | yi-lightning | [platform.01.ai](https://platform.01.ai/) |
| **Doubao** | doubao-pro-32k | [console.volcengine.com](https://console.volcengine.com/) |
| **Reka** | reka-flash | [platform.reka.ai](https://platform.reka.ai/) |
| **Writer** | palmyra-x-004 | [app.writer.com](https://app.writer.com/) |

### Service-Specific Features

| Service | Special Features |
|---------|-----------------|
| **OpenAI** | JSON response format, Chat Completions API (gpt-4o), Responses API (gpt-5.x/o3/o4) |
| **Anthropic** | Top-K parameter, content blocks, 8 hardcoded models |
| **Google** | System instruction, generation config |
| **xAI** | Web search toggle, returns search results |
| **Perplexity** | Citations, search results, related questions, recency filter |
| **DeepSeek** | Reasoning content (think sections), Standard + Beta endpoints |
| **Mistral** | Standard + Codestral endpoints (code generation) |
| **SiliconFlow** | OpenAI + Anthropic compatible endpoints, Chinese AI models |
| **Z.AI** | General + Coding endpoints, GLM models |
| **Moonshot** | Chinese AI provider (Kimi models) |
| **Cohere** | Command models with compatibility mode |
| **DashScope** | Alibaba Cloud AI, Qwen models |

All 31 services support SSE streaming for real-time responses.

## Flocks vs Swarms

The app offers two ways to group AI configurations:

- **Flocks**: Groups of **agents** (full configurations with provider, model, API key, endpoint). Best when you need specific, reusable agent setups.
- **Swarms**: Groups of **provider/model pairs** (lightweight, using provider defaults). Best for quickly comparing multiple models without creating agents first.

Both can have parameter presets attached for overriding defaults.

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

### Parameter Presets

Instead of configuring parameters per-agent, you can create reusable parameter presets:
1. Go to AI Setup -> AI Parameters
2. Create named presets (e.g., "Creative", "Precise", "Code")
3. Assign presets to agents, flocks, or swarms
4. Multiple presets can be assigned - later ones override earlier ones

### Agent Inheritance

Agents can inherit settings from their provider:
- **API Key**: Leave empty to use provider's API key
- **Model**: Leave empty to use provider's default model
- **Endpoint**: Leave empty to use provider's default endpoint

## Features in Detail

### AI Chat with Streaming

Have real-time conversations with any AI:
1. Select a provider or pre-configured agent
2. Select a model (search to filter)
3. Optionally configure parameters
4. Start chatting - responses appear word by word
5. Conversations are automatically saved to Chat History
6. Continue any previous conversation from Chat History

### AI Reports

Generate comparative reports from multiple AI agents:
1. Enter a title (optional) and your prompt
2. Use `@DATE@` to insert current date automatically
3. Use `<user>...</user>` tags to embed content shown in HTML reports but not sent to AI
4. Select flocks, swarms, or individual agents
5. Watch real-time progress with:
   - Agent name, provider, model
   - Status (pending, running, success, error)
   - Input/output token counts
   - Cost in cents per agent
   - API duration in seconds
6. When complete:
   - **View** - See formatted responses in-app
   - **Share** - Export as JSON or HTML
   - **Browser** - Open interactive HTML report

### HTML Reports

Generated reports include:
- Report title and timestamp
- Cost summary table: Provider, Model, Seconds, In/Out tokens, In/Out cost (cents), Total
- User content from `<user>` tags
- Clickable buttons to toggle each agent's response
- Collapsible think sections for AI reasoning
- Markdown-rendered AI responses
- Citations and sources (when provided)
- Search results and related questions (when provided)
- Token usage and HTTP headers (developer mode only)

### AI Costs

Track your AI spending:
- **Total cost** across all models
- **Expandable provider groups** showing per-model breakdown
- **Pricing sources** (color-coded): API, Manual, OpenRouter, LiteLLM, Fallback
- **Refresh button** to update OpenRouter pricing (cached weekly)
- **Cost Configuration** in Settings for manual price overrides
- **Selection pricing**: Input/output per-M-token costs shown on all agent and model selection screens

### AI Prompts

Create internal prompts for app features:
1. Go to AI Setup -> AI Prompts
2. Create a prompt with a unique name (e.g., "model_info")
3. Assign an agent to execute the prompt
4. Use variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@
5. The app uses these prompts for features like auto-generating model descriptions

### Think Sections

Some AI models (like DeepSeek reasoning models) include `<think>...</think>` sections showing their reasoning process:
- In-app viewer: Click the "Think" button to expand/collapse reasoning
- HTML reports: JavaScript-powered collapsible sections
- Helps understand AI decision-making while keeping responses clean

## Integration with Other Apps

The AI app can be launched from other Android applications to generate reports. See `CALL_AI.md` for complete documentation.

### How to Call the AI App

```kotlin
val intent = Intent().apply {
    action = "com.ai.ACTION_NEW_REPORT"
    setPackage("com.ai")
    putExtra("title", "My Analysis")      // Optional: report title
    putExtra("prompt", "Analyze this...") // Required: the prompt
}
startActivity(intent)
```

## App Structure

```
AI Hub (Home)
+-- AI Report
|   +-- Enter title + prompt
|   +-- Generate -> Select flocks/swarms/agents
|   +-- Progress with token counts, costs & duration
|   +-- Results -> View/Share (JSON/HTML)/Browser
+-- AI Chat
|   +-- Select provider/agent + model
|   +-- Configure parameters
|   +-- Streaming conversation (auto-saved)
+-- AI Models
|   +-- Search across all providers
|   +-- View info / Start chat / Create agent
+-- AI History
|   +-- Browse -> View/Share/Browser/Delete reports
+-- AI Statistics
|   +-- API calls, token usage per provider/model
+-- AI Costs
|   +-- Estimated costs with expandable provider groups
+-- Settings
    +-- General
    |   +-- Username, Full screen mode, Developer mode, API tracing
    +-- Cost Configuration
    |   +-- Manual price overrides per model
    +-- AI Setup
    |   +-- AI Providers (API keys, endpoints, models)
    |   +-- AI Agents (with parameter presets, endpoint selection)
    |   +-- AI Flocks (agent groups)
    |   +-- AI Swarms (provider/model groups)
    |   +-- AI Parameters (reusable parameter presets)
    |   +-- AI Prompts (internal app prompts)
    |   +-- Export/Import configuration (v17)
    |   +-- Housekeeping (test connections, refresh, cleanup)
    +-- External Services
    |   +-- Hugging Face API Key
    +-- Help
```

## Privacy & Security

- **Local Storage Only**: All data stored on device
- **No Analytics**: No tracking or telemetry
- **Secure Keys**: API keys in app's private storage
- **Masked Traces**: Sensitive headers masked in API logs (shows first 4 + last 4 chars)

## Troubleshooting

### "API key not configured"
Add your API key in Settings -> AI Setup -> AI Providers

### "Network error"
- Check internet connection
- The app automatically retries once on failure (500ms delay)

### "Model not found"
- The model may have been deprecated
- Use Housekeeping -> "Refresh model lists" to update
- Some providers have hardcoded model lists (no API)

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

### No pricing data
- Add OpenRouter API key for best pricing coverage
- Use Cost Configuration to set manual prices
- LiteLLM bundled data provides fallback pricing

### Debug API issues
1. Enable Developer Mode (Settings -> General)
2. Enable "Track API calls"
3. Reproduce the issue
4. Check trace viewer (bug icon in title bar)
5. Inspect request/response details in JSON tree view
6. Share trace file for support

## Technical Details

- **Language**: Kotlin 1.9.22
- **UI**: Jetpack Compose with Material 3 (dark theme, black background)
- **Architecture**: MVVM with StateFlow, Repository pattern
- **Networking**: Retrofit 2.9.0 with OkHttp 4.12.0, Gson serialization
- **Streaming**: Server-Sent Events (SSE) with Kotlin Flow (3 format-specific parsers)
- **Persistence**: SharedPreferences (JSON) + file-based storage
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Build tools**: Gradle 8.5, AGP 8.2.2, Java 17
- **Codebase**: ~25,800 lines across 40 Kotlin files
- **Permissions**: Only INTERNET (no storage, camera, or other sensitive permissions)
- **Provider architecture**: 28 OpenAI-compatible providers share unified code paths; only Anthropic and Google Gemini have unique implementations

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

Version 17 JSON format:
```json
{
  "version": 17,
  "huggingFaceApiKey": "hf_...",
  "providers": {
    "OPENAI": {
      "modelSource": "API",
      "models": [],
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
      "apiKey": "",
      "endpointId": "openai-chat-completions",
      "parametersIds": ["params-uuid"]
    }
  ],
  "flocks": [
    {
      "id": "uuid",
      "name": "My Flock",
      "agentIds": ["agent-uuid-1", "agent-uuid-2"],
      "parametersIds": []
    }
  ],
  "swarms": [
    {
      "id": "uuid",
      "name": "My Swarm",
      "members": [{"provider": "OPENAI", "model": "gpt-4o"}],
      "parametersIds": []
    }
  ],
  "params": [
    {
      "id": "params-uuid",
      "name": "Creative",
      "temperature": 0.9,
      "maxTokens": 4096
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

**Note:** Import supports versions 11-17. Legacy versions are auto-migrated.

## License

Private use only. Not for redistribution.

## Acknowledgments

- **AI Services**: OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI, Moonshot, Cohere, AI21, DashScope, Fireworks, Cerebras, SambaNova, Baichuan, StepFun, MiniMax, NVIDIA, Replicate, Hugging Face, Lambda, Lepton, 01.AI, Doubao, Reka, Writer
- **Model Data**: OpenRouter API, Hugging Face API, LiteLLM pricing data
- **UI Framework**: Jetpack Compose, Material 3
- **Networking**: Retrofit, OkHttp, Gson

---

*AI - Compare 31 AI providers, chat with streaming, organize agents into flocks and swarms, configure endpoints, track costs, generate insights.*
