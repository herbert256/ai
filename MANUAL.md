# AI - User Manual

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

### Provider Endpoints

Providers with multiple API endpoints:

| Provider | Endpoints |
|----------|-----------|
| **OpenAI** | Chat Completions (gpt-4o, gpt-4, gpt-3.5), Responses API (gpt-5.x, o3, o4) |
| **DeepSeek** | Standard (chat/completions), Beta (FIM/prefix completion) |
| **Mistral** | Standard (api.mistral.ai), Codestral (codestral.mistral.ai) |
| **SiliconFlow** | Chat Completions (OpenAI compatible), Messages (Anthropic compatible) |
| **Z.AI** | General (chat completions), Coding (GLM Coding Plan) |

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

## AI Chat

Have real-time conversations with any AI:
1. Select a provider or pre-configured agent
2. Select a model (search to filter)
3. Optionally configure parameters
4. Start chatting - responses appear word by word
5. Conversations are automatically saved to Chat History
6. Continue any previous conversation from Chat History

## AI Reports

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
- Token usage and HTTP headers (developer mode only)

## AI Costs

Track your AI spending:
- **Total cost** across all models
- **Expandable provider groups** showing per-model breakdown
- **Pricing sources** (color-coded): API, Manual, OpenRouter, LiteLLM, Fallback
- **Refresh button** to update OpenRouter pricing (cached weekly)
- **Cost Configuration** in Settings for manual price overrides
- **Selection pricing**: Input/output per-M-token costs shown on all agent and model selection screens

## AI Prompts

Create internal prompts for app features:
1. Go to AI Setup -> AI Prompts
2. Create a prompt with a unique name (e.g., "model_info")
3. Assign an agent to execute the prompt
4. Use variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@
5. The app uses these prompts for features like auto-generating model descriptions

## Think Sections

Some AI models (like DeepSeek reasoning models) include `<think>...</think>` sections showing their reasoning process:
- In-app viewer: Click the "Think" button to expand/collapse reasoning
- HTML reports: JavaScript-powered collapsible sections
- Helps understand AI decision-making while keeping responses clean

## Integration with Other Apps

The AI app can be launched from other Android applications to generate reports. See `CALL_AI.md` for complete documentation.

```kotlin
val intent = Intent().apply {
    action = "com.ai.ACTION_NEW_REPORT"
    setPackage("com.ai")
    putExtra("title", "My Analysis")
    putExtra("prompt", "Analyze this...")
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
    |   +-- Export/Import configuration
    |   +-- Housekeeping (test connections, refresh, cleanup)
    +-- External Services
    |   +-- Hugging Face API Key
    +-- Help
```

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
