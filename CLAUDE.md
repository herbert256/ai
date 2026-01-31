# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Deploy Commands

```bash
# Build debug APK (requires Java 17)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug

# Build release APK (requires keystore in local.properties)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease

# Clean build
./gradlew clean

# Deploy to device
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
adb shell am start -n com.ai/.MainActivity

# Deploy to both device and cloud
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
cp app/build/outputs/apk/debug/app-debug.apk /Users/herbert/cloud/ai.apk

# View logs
adb logcat | grep -E "(AiAnalysis|AiHistory|ApiTracer|PricingCache|AiReport)"
```

## Project Overview

**AI** is an Android app for creating AI-powered reports and having conversations using 31 different AI services. Users can configure multiple AI agents with advanced parameters and custom endpoints, organize them into flocks (agent groups) and swarms (provider/model groups), apply reusable parameter presets, submit custom prompts, generate comparative reports, search and explore models across all providers, chat with streaming responses, and track usage costs.

**Key Features:**
- Support for 31 AI services (OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together, OpenRouter, SiliconFlow, Z.AI, Moonshot, Cohere, AI21, DashScope, Fireworks, Cerebras, SambaNova, Baichuan, StepFun, MiniMax, NVIDIA, Replicate, Hugging Face, Lambda, Lepton, 01.AI, Doubao, Reka, Writer)
- AI Chat with streaming responses (real-time token-by-token) and auto-save
- AI Agents with configurable parameters, endpoints, and inheritance from providers
- AI Flocks for organizing agents into named groups
- AI Swarms for organizing provider/model pairs into named groups
- AI Parameters as reusable parameter presets (assignable to agents, flocks, swarms)
- AI Prompts for internal app features (model_info auto-descriptions)
- Multiple API endpoints per provider (e.g., OpenAI Chat vs Responses API)
- Parallel multi-agent report generation with persistent storage and duration tracking
- AI Reports stored as JSON objects with View/Share/Browser actions
- Model Search across all configured providers with one-tap chat or agent creation
- Model Info with OpenRouter pricing/specs and Hugging Face metadata
- AI Statistics tracking API calls and token usage
- AI Costs with six-tier pricing (API > Override > OpenRouter > LiteLLM > Fallback > Default)
- Per-M-token pricing display on all selection screens (agents, models, flocks, swarms)
- Collapsible `<think>` sections in AI responses
- `<user>...</user>` tags for embedding user content in HTML reports (not sent to AI)
- HTML reports with cost table, duration tracking, markdown rendering, citations
- Prompt history (up to 100 entries)
- Chat history with automatic conversation saving
- Developer mode with comprehensive API tracing
- Configuration export/import (JSON format, version 16)
- External app integration via Intent (see CALL_AI.md)

**Technical Stack:**
- **Language:** Kotlin
- **UI:** Jetpack Compose with Material 3 (dark theme, black background)
- **Architecture:** MVVM with StateFlow
- **Networking:** Retrofit 2 with OkHttp, Gson serialization
- **Streaming:** SSE (Server-Sent Events) parsing with Kotlin Flow
- **Persistence:** SharedPreferences (JSON) + File storage
- **Android SDK:** minSdk 26, targetSdk 34, compileSdk 34
- **Namespace:** `com.ai`
- **Version format:** `yy.DDD.minutesOfDay` (e.g., `26.30.1200`)

## Architecture

### Package Structure

```
com.ai/
├── MainActivity.kt                    # Entry point, sets up Compose theme (~102 lines)
├── data/                              # Data layer (~4,595 lines)
│   ├── AiAnalysisApi.kt              # Retrofit interfaces, AiService enum with provider config, unified OpenAiCompatibleApi, request/response models (~1,461 lines)
│   ├── AiAnalysisRepository.kt       # Repository facade, TokenUsage, AiAnalysisResponse (~348 lines)
│   ├── AiAnalysisProviders.kt        # 3 unique + 1 unified OpenAI-compatible analysis method (~326 lines)
│   ├── AiAnalysisStreaming.kt        # 3 unique + 1 unified streaming method (~411 lines)
│   ├── AiAnalysisChat.kt            # 3 unique + 1 unified chat method (~166 lines)
│   ├── AiAnalysisModels.kt          # Unified model fetching with provider-specific handling (~351 lines)
│   ├── AiReportStorage.kt            # File-based report storage with thread-safe ops (~404 lines)
│   ├── ChatHistoryManager.kt         # Chat session file storage (~122 lines)
│   ├── PricingCache.kt               # Six-tier pricing cache (~679 lines)
│   └── ApiTracer.kt                  # Debug API request/response logging (~327 lines)
└── ui/                                # UI layer (~24,003 lines)
    ├── AiViewModel.kt                # Central state management (~1,698 lines)
    ├── AiModels.kt                   # Core UI state: AiUiState, GeneralSettings (~107 lines)
    ├── AiSettingsModels.kt           # Settings data: ProviderConfig, AiSettings with Map<AiService, ProviderConfig> (~883 lines)
    ├── AiScreens.kt                  # Report screens: Hub, NewReport, Results, pricing utility (~1,845 lines)
    ├── ChatScreens.kt                # AI Chat: conversation UI, streaming, agent/model selection (~1,610 lines)
    ├── AiContentDisplay.kt           # AI response rendering with think sections (~658 lines)
    ├── AiHistoryScreen.kt            # Report history browser (~681 lines)
    ├── AiReportExport.kt             # HTML report generation with cost table (~847 lines)
    ├── AiModelScreens.kt             # Model search and model info screens (~1,076 lines)
    ├── AiStatisticsScreen.kt         # Statistics and Costs screens (~1,126 lines)
    ├── AiServiceSettingsScreens.kt   # Single unified ProviderSettingsScreen + defaultEndpointsForProvider helper (~277 lines)
    ├── AiSettingsScreen.kt           # AI settings navigation hub (~562 lines)
    ├── AiSettingsComponents.kt       # Reusable AI settings UI components (~1,123 lines)
    ├── AiPromptsAgentsScreens.kt     # Agents CRUD with parameter editing (~1,088 lines)
    ├── AiFlocksScreen.kt             # Flock management CRUD (~396 lines)
    ├── AiSwarmsScreen.kt             # Swarm management CRUD (~406 lines)
    ├── AiParametersScreen.kt         # Parameter preset editor (~760 lines)
    ├── AiPromptsScreen.kt            # AI Prompts CRUD (~391 lines)
    ├── PromptHistoryScreen.kt        # Prompt history browser (~283 lines)
    ├── AiHousekeepingScreen.kt       # Housekeeping: test connections, refresh models, cleanup (~1,479 lines)
    ├── AiDeveloperScreens.kt         # Developer mode: API test, traces, logs (~917 lines)
    ├── AiSettingsExport.kt           # Configuration export/import v16 (~1,040 lines)
    ├── SettingsScreen.kt             # Settings hub + navigation (~1,451 lines)
    ├── SettingsPreferences.kt        # SharedPreferences persistence (~743 lines)
    ├── HelpScreen.kt                 # In-app documentation (~659 lines)
    ├── TraceScreen.kt                # API trace list and detail viewer with JSON tree (~765 lines)
    ├── Navigation.kt                 # Jetpack Navigation routes (~979 lines)
    ├── SharedComponents.kt           # AiTitleBar, common widgets (~121 lines)
    └── theme/Theme.kt                # Material3 dark theme (~32 lines)
```

**Total:** ~28,700 lines of Kotlin code across 40 files

### Key Data Classes

```kotlin
// API format enum - determines which Retrofit interface and parser to use
enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE }

// AI Services enum (31 services) - each entry carries full provider configuration
enum class AiService(
    val displayName: String,
    val apiFormat: ApiFormat,           // Determines analysis/streaming/chat method
    val chatPath: String,              // API path for chat completions (e.g., "v1/chat/completions")
    val modelsPath: String?,           // API path for model listing (null = hardcoded models)
    val modelFilter: String?,          // Regex filter for model list responses
    val baseUrl: String,
    val adminUrl: String,
    val defaultModel: String,
    val openRouterName: String? = null, // Provider prefix for OpenRouter model IDs
    val prefsKey: String               // SharedPreferences key prefix (e.g., "openai", "anthropic")
) {
    OPENAI, ANTHROPIC, GOOGLE, XAI, GROQ, DEEPSEEK, MISTRAL, PERPLEXITY,
    TOGETHER, OPENROUTER, SILICONFLOW, ZAI, MOONSHOT, COHERE, AI21,
    DASHSCOPE, FIREWORKS, CEREBRAS, SAMBANOVA, BAICHUAN, STEPFUN,
    MINIMAX, NVIDIA, REPLICATE, HUGGINGFACE, LAMBDA, LEPTON, YI,
    DOUBAO, REKA, WRITER
}

// Provider configuration - per-service settings stored in a map
data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val modelSource: ModelSource = ModelSource.API,
    val manualModels: List<String> = emptyList(),
    val adminUrl: String = "",
    val modelListUrl: String = "",
    val parametersIds: List<String> = emptyList()
)

// AI Settings - uses Map<AiService, ProviderConfig> instead of per-provider fields
data class AiSettings(
    val providers: Map<AiService, ProviderConfig> = emptyMap(),
    // ... other settings
) {
    // Accessor methods for provider config
    fun getProvider(service: AiService): ProviderConfig
    fun withProvider(service: AiService, config: ProviderConfig): AiSettings
    fun getApiKey(service: AiService): String
    fun withApiKey(service: AiService, key: String): AiSettings
    fun getModel(service: AiService): String
    fun withModel(service: AiService, model: String): AiSettings
    fun getModelSource(service: AiService): ModelSource
    fun getManualModels(service: AiService): List<String>
    fun hasAnyApiKey(): Boolean
    fun getModelListUrl(service: AiService): String
    fun getDefaultModelListUrl(service: AiService): String
    fun getParametersIds(service: AiService): List<String>
    fun withParametersIds(service: AiService, ids: List<String>): AiSettings
}

// AI Agent - references parameter presets instead of inline parameters
data class AiAgent(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val provider: AiService,           // Reference to provider enum
    val model: String,                 // Model name (empty = use provider default)
    val apiKey: String,                // API key (empty = use provider key)
    val endpointId: String? = null,    // Reference to AiEndpoint ID
    val paramsIds: List<String> = emptyList()  // References to AiParameters IDs
)

// AI Flock - named group of agents (for report generation)
data class AiFlock(
    val id: String,
    val name: String,
    val agentIds: List<String> = emptyList(),
    val paramsIds: List<String> = emptyList()
)

// AI Swarm - named group of provider/model pairs (lightweight, no agents needed)
data class AiSwarmMember(
    val provider: AiService,
    val model: String
)

data class AiSwarm(
    val id: String,
    val name: String,
    val members: List<AiSwarmMember> = emptyList(),
    val paramsIds: List<String> = emptyList()
)

// AI Parameters - reusable parameter presets (assigned to agents, flocks, swarms)
data class AiParameters(
    val id: String,
    val name: String,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val systemPrompt: String? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = true,
    val searchRecency: String? = null
)

// AI Prompt - internal prompts with variable support
data class AiPrompt(
    val id: String,
    val name: String,                  // Unique name (e.g., "model_info")
    val agentId: String,               // Reference to AiAgent ID
    val promptText: String             // Template with @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@
)

// Persistent AI Report storage
data class AiReport(
    val id: String,
    val timestamp: Long,
    val title: String,
    val prompt: String,
    val agents: MutableList<AiReportAgent>,
    var totalCost: Double = 0.0,
    var completedAt: Long? = null,
    val rapportText: String? = null    // Content from <user>...</user> tags, shown in HTML export
)

data class AiReportAgent(
    val agentId: String,
    val agentName: String,
    val provider: String,
    val model: String,
    var reportStatus: ReportStatus = ReportStatus.PENDING,
    var httpStatus: Int? = null,
    var requestHeaders: String? = null,
    var requestBody: String? = null,
    var responseHeaders: String? = null,
    var responseBody: String? = null,
    var errorMessage: String? = null,
    var tokenUsage: TokenUsage? = null,
    var cost: Double? = null,
    var citations: List<String>? = null,
    var searchResults: List<SearchResult>? = null,
    var relatedQuestions: List<String>? = null,
    var durationMs: Long? = null       // API request duration
)

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val apiCost: Double? = null        // Cost from API response (highest priority)
)

// Pricing cache
data class ModelPricing(
    val modelId: String,
    val promptPrice: Double,           // Price per token
    val completionPrice: Double,       // Price per token
    val source: String                 // "API", "manual", "openrouter", "litellm", "fallback", "default"
)
```

### Flocks vs Swarms

These are two different ways to group AI configurations for report generation:

- **Flocks**: Groups of **agents** (which have full configuration: provider, model, API key, endpoint, parameters). Use flocks when you need specific agent configurations.
- **Swarms**: Groups of **provider/model pairs** (lightweight, use provider defaults for API key and endpoint). Use swarms when you just need different models without custom configurations.

Both can have parameter presets (paramsIds) attached for overriding agent defaults.

### Design Patterns

1. **MVVM with StateFlow**: `AiViewModel` exposes `StateFlow<AiUiState>`, UI recomposes reactively via `collectAsState()`

2. **Repository Pattern**: `AiAnalysisRepository` facade delegates to specialized files:
   - `AiAnalysisProviders.kt` - Unified analysis (1 generic + 3 format-specific methods)
   - `AiAnalysisStreaming.kt` - Unified streaming (1 generic + 3 format-specific methods)
   - `AiAnalysisChat.kt` - Unified chat (1 generic + 3 format-specific methods)
   - `AiAnalysisModels.kt` - Unified model fetching with provider-specific handling

3. **Provider Consolidation**: 28 OpenAI-compatible providers share a single code path. The `AiService` enum carries `apiFormat`, `chatPath`, `modelsPath`, and `modelFilter` so generic methods can handle any provider without per-provider branching. Only Anthropic (Claude format) and Google (Gemini format) have unique implementations.

4. **Singleton Storage Managers**:
   - `AiReportStorage` - Thread-safe report persistence with ReentrantLock
   - `ChatHistoryManager` - Chat session file storage
   - `PricingCache` - Six-tier pricing with weekly caching
   - `ApiTracer` - Debug API logging

5. **Factory Pattern**: `AiApiFactory` uses `createOpenAiCompatibleApi(baseUrl)` with `ConcurrentHashMap` cache for the 28 OpenAI-compatible providers. Only special factories remain for Claude (`createClaudeApi`), Gemini (`createGeminiApi`), and OpenAI Responses API (`createOpenAiApi`).

6. **Flow-based Streaming**: SSE parsing returns `Flow<String>` for real-time token emission

7. **Inheritance Pattern**: Agents inherit API key, model, and endpoint from provider when left empty

8. **Parameter Merging**: Multiple parameter presets can be applied in order, with later presets overriding earlier ones. `mergeParameters()` in `AiSettingsModels.kt` handles the merge logic.

9. **Unified Settings Map**: `AiSettings` uses `providers: Map<AiService, ProviderConfig>` with accessor methods instead of 217 individual per-provider fields. `SettingsPreferences` loops over `AiService.entries` using `service.prefsKey` for load/save.

10. **Thread Safety**:
    - `ConcurrentHashMap` for Retrofit instance cache
    - `ReentrantLock` for AiReportStorage
    - Synchronized access to `isTracingEnabled` flag
    - Coroutines with `Dispatchers.IO` for network calls
    - StateFlow for thread-safe state updates

## AI Services

### Supported Services (31)

| Service | Default Model | API Format | Auth Method |
|---------|--------------|------------|-------------|
| OpenAI | gpt-4o-mini | OpenAI Chat/Responses | Bearer token |
| Anthropic | claude-sonnet-4-20250514 | Anthropic Messages | x-api-key header |
| Google | gemini-2.0-flash | Google GenerativeAI | Query parameter |
| xAI | grok-3-mini | OpenAI-compatible | Bearer token |
| Groq | llama-3.3-70b-versatile | OpenAI-compatible | Bearer token |
| DeepSeek | deepseek-chat | OpenAI-compatible | Bearer token |
| Mistral | mistral-small-latest | OpenAI-compatible | Bearer token |
| Perplexity | sonar | OpenAI-compatible | Bearer token |
| Together | Llama-3.3-70B-Instruct-Turbo | OpenAI-compatible | Bearer token |
| OpenRouter | anthropic/claude-3.5-sonnet | OpenAI-compatible | Bearer token |
| SiliconFlow | Qwen/Qwen2.5-7B-Instruct | OpenAI-compatible | Bearer token |
| Z.AI | glm-4.7-flash | OpenAI-compatible | Bearer token |
| Moonshot | kimi-latest | OpenAI-compatible | Bearer token |
| Cohere | command-a-03-2025 | OpenAI-compatible | Bearer token |
| AI21 | jamba-mini | OpenAI-compatible | Bearer token |
| DashScope | qwen-plus | OpenAI-compatible | Bearer token |
| Fireworks | llama-v3p3-70b-instruct | OpenAI-compatible | Bearer token |
| Cerebras | llama-3.3-70b | OpenAI-compatible | Bearer token |
| SambaNova | Meta-Llama-3.3-70B-Instruct | OpenAI-compatible | Bearer token |
| Baichuan | Baichuan4-Turbo | OpenAI-compatible | Bearer token |
| StepFun | step-2-16k | OpenAI-compatible | Bearer token |
| MiniMax | MiniMax-M2.1 | OpenAI-compatible | Bearer token |
| NVIDIA | llama-3.1-nemotron-70b-instruct | OpenAI-compatible | Bearer token |
| Replicate | meta/meta-llama-3-70b-instruct | OpenAI-compatible | Bearer token |
| Hugging Face | Llama-3.1-70B-Instruct | OpenAI-compatible | Bearer token |
| Lambda | hermes-3-llama-3.1-405b-fp8 | OpenAI-compatible | Bearer token |
| Lepton | llama3-1-70b | OpenAI-compatible | Bearer token |
| 01.AI | yi-lightning | OpenAI-compatible | Bearer token |
| Doubao | doubao-pro-32k | OpenAI-compatible | Bearer token |
| Reka | reka-flash | OpenAI-compatible | Bearer token |
| Writer | palmyra-x-004 | OpenAI-compatible | Bearer token |

All services support SSE streaming.

### Service-Specific Features

- **OpenAI**: Chat Completions API (gpt-4o, etc.) and Responses API (gpt-5.x, o3, o4). JSON response format option. Auto-routes based on model name.
- **Anthropic**: Hardcoded model list (8 models - no list API), Anthropic-specific format with content blocks
- **Google**: Uses generationConfig for parameters, systemInstruction for system prompt
- **DeepSeek**: Handles `reasoning_content` field for reasoning models, displayed in think sections. Has Beta endpoint for FIM.
- **Perplexity**: Returns `citations`, `search_results`, `related_questions`. Supports search recency filter.
- **xAI**: Optional web search via `search` parameter. May return `search_results`.
- **Mistral**: Has separate Codestral endpoint for code generation
- **SiliconFlow**: Chinese AI provider with Qwen and DeepSeek models. OpenAI and Anthropic-compatible endpoints.
- **Z.AI**: ZhipuAI GLM models (Chinese provider). Has separate Coding endpoint.
- **Moonshot**: Chinese AI provider (Kimi). Hardcoded model list.
- **Cohere**: Command models with compatibility mode. Hardcoded model list.
- **DashScope**: Alibaba Cloud AI. Qwen models.

### Provider Endpoints

Providers with multiple API endpoints:

| Provider | Endpoints |
|----------|-----------|
| OpenAI | Chat Completions (gpt-4o, gpt-4, gpt-3.5), Responses API (gpt-5.x, o3, o4) |
| DeepSeek | Standard (chat/completions), Beta (FIM/prefix completion) |
| Mistral | Standard (api.mistral.ai), Codestral (codestral.mistral.ai) |
| SiliconFlow | Chat Completions (OpenAI compatible), Messages (Anthropic compatible) |
| Z.AI | General (chat completions), Coding (GLM Coding Plan) |

### Endpoint Resolution

```kotlin
// Priority for agent endpoint resolution:
// 1. Agent's specific endpointId
// 2. Provider's default endpoint
// 3. Provider's first endpoint
// 4. Hardcoded baseUrl from AiService enum
```

## Streaming Implementation

Chat uses Server-Sent Events (SSE) for real-time streaming. Implementation is split across:
- `AiAnalysisStreaming.kt` - SSE parsers (OpenAI, Claude, Gemini formats) + unified streaming dispatcher
- `AiAnalysisChat.kt` - Unified chat stream dispatcher + 3 format-specific implementations

### Provider-Specific Parsers

| Provider | Stream Format | Parser |
|----------|--------------|--------|
| 28 OpenAI-compatible providers | OpenAI SSE | `parseOpenAiSseStream()` |
| Anthropic | Claude SSE with events | `parseClaudeSseStream()` |
| Google | Gemini SSE | `parseGeminiSseStream()` |

### Unified Dispatch

All 28 OpenAI-compatible providers are handled by single unified methods that use `AiService.apiFormat`, `AiService.chatPath`, and `OpenAiCompatibleApi` with `@Url` parameter. Only Anthropic and Google require separate code paths.

## Pricing System

### Six-Tier Lookup (PricingCache.kt)

1. **API response** - Cost returned directly from provider API (e.g., OpenRouter `cost` field)
2. **Manual overrides** - User-set prices via Cost Configuration screen
3. **OpenRouter API** - Weekly-cached pricing from OpenRouter
4. **LiteLLM JSON** - Bundled `model_prices_and_context_window.json` in assets
5. **Hardcoded fallback** - Built-in prices for common models
6. **Default** - Zero pricing (returned as non-null with source "default")

```kotlin
// getPricing always returns a non-null ModelPricing (defaults to zero with source "default")
fun getPricing(context: Context, provider: AiService, model: String): ModelPricing
```

### Pricing Display

Per-M-token pricing is displayed on all selection screens using:
```kotlin
// In AiScreens.kt - reusable pricing utility
internal data class FormattedPricing(val text: String, val isDefault: Boolean)

internal fun formatPricingPerMillion(context: Context, provider: AiService, model: String): FormattedPricing
// Returns "0.25/0.50" format (input/output per million tokens)
// isDefault=true when source is "default" (displayed in gray instead of red)
```

## Report Storage (AiReportStorage.kt)

### Thread-Safe Operations

```kotlin
object AiReportStorage {
    private val lock = ReentrantLock()

    fun updateAgentStatus(...) {
        lock.withLock {
            val reports = loadAllReports(context).toMutableMap()
            saveReports(reports)
        }
    }
}
```

### Report Lifecycle

1. `createReport()` - Creates new report with pending agents
2. `markAgentRunning()` - Agent starts API call (timing begins in ViewModel)
3. `markAgentSuccess(durationMs)` or `markAgentError(durationMs)` - Agent completes with duration
4. Report auto-completes when all agents finish

### HTML Reports (AiReportExport.kt)

HTML reports include:
- Report title and timestamp
- Cost summary table (Provider, Model, Secs, In/Out tokens, In/Out cost in cents, Total)
- User content from `<user>...</user>` tags (rapport text)
- Clickable toggle buttons per agent
- Collapsible think sections
- Markdown-rendered responses
- Citations and search results
- Developer mode sections (raw usage JSON, HTTP headers)

### `<user>` Tags

Content between `<user>...</user>` tags in prompts is:
- Extracted via regex in `AiViewModel.kt`
- Stored in `AiReport.rapportText`
- Displayed in HTML reports between prompt and agent responses
- NOT sent to the AI (stripped from the prompt before API call)

## Navigation

### Routes (Navigation.kt)

```kotlin
object NavRoutes {
    const val AI = "ai"                          // Home (AI Hub)
    const val AI_REPORTS_HUB = "ai_reports_hub"  // Reports hub
    const val AI_NEW_REPORT = "ai_new_report"    // New report
    const val AI_REPORTS = "ai_reports"           // Report results
    const val AI_HISTORY = "ai_history"           // Report history
    const val AI_PROMPT_HISTORY = "ai_prompt_history"
    const val AI_CHATS_HUB = "ai_chats_hub"      // Chat hub
    const val AI_CHAT_PROVIDER = "ai_chat_provider"
    const val AI_CHAT_MODEL = "ai_chat_model/{provider}"
    const val AI_CHAT_SESSION = "ai_chat_session/{provider}/{model}"
    const val AI_CHAT_HISTORY = "ai_chat_history"
    const val AI_CHAT_CONTINUE = "ai_chat_continue/{sessionId}"
    const val AI_CHAT_AGENT_SELECT = "ai_chat_agent_select"
    const val AI_CHAT_WITH_AGENT = "ai_chat_with_agent/{agentId}"
    const val AI_MODEL_SEARCH = "ai_model_search"
    const val AI_MODEL_INFO = "ai_model_info/{provider}/{model}"
    const val AI_STATISTICS = "ai_statistics"
    const val AI_COSTS = "ai_costs"
    const val AI_COST_CONFIG = "ai_cost_config"
    const val AI_SETUP = "ai_setup"
    const val AI_HOUSEKEEPING = "ai_housekeeping"
    const val AI_API_TEST = "ai_api_test"
    const val SETTINGS = "settings"
    const val HELP = "help"
    const val TRACE_LIST = "trace_list"
    const val TRACE_DETAIL = "trace_detail/{filename}"
    const val DEVELOPER_OPTIONS = "developer_options"
}
```

### Screen Flow

```
AI Hub (Home)
├── AI Report → New Report → Flock/Swarm Selection → Progress → Results
│   └── Results: View, Share (JSON/HTML), Browser, toggle agents
├── AI Chat → Provider/Agent Selection → Model Selection → Chat (streaming)
│   └── Chat: Multi-turn, parameters, auto-save
├── AI Models → Search → Model Info / Start Chat / Create Agent
├── AI History → View/Share/Browser/Delete reports (from AiReportStorage)
├── AI Statistics → API calls, token usage per provider/model
├── AI Costs → Expandable provider groups, per-model costs, refresh pricing
├── Settings → General / Cost Config / AI Setup / Help
│   ├── General: Username, Full screen, Developer mode, API tracing
│   ├── Cost Config: Manual price overrides per model
│   └── AI Setup → Providers / Agents / Flocks / Swarms / Parameters / Prompts / Export
│       └── Housekeeping → Test connections, Refresh models, Cleanup
└── Help → Comprehensive in-app documentation
```

## Settings & Persistence

### SharedPreferences Keys (`eval_prefs`)

```kotlin
// General settings
"developer_mode"          // Boolean
"track_api_calls"         // Boolean
"username"                // String (default "You")
"full_screen_mode"        // Boolean
"hugging_face_api_key"    // String (for model info)

// Per-service AI settings (×31 services, using service.prefsKey)
"ai_{prefsKey}_api_key"           // String
"ai_{prefsKey}_model"             // String
"ai_{prefsKey}_model_source"      // Enum: API or MANUAL
"ai_{prefsKey}_manual_models"     // JSON List<String>
"ai_{prefsKey}_admin_url"         // String
"ai_{prefsKey}_model_list_url"    // String (custom model list URL)
"ai_{prefsKey}_parameters_id"     // JSON List<String> (parameter preset IDs)

// Collections (JSON)
"ai_agents"               // JSON List<AiAgent>
"ai_flocks"               // JSON List<AiFlock>
"ai_swarms"               // JSON List<AiSwarm>
"ai_parameters"           // JSON List<AiParameters>
"ai_prompts"              // JSON List<AiPrompt>
"ai_endpoints"            // JSON Map<AiService, List<AiEndpoint>>
"provider_states"         // JSON Map<String, String> ("ok"|"error"|"not-used")

// Report generation
"ai_report_agents"        // Set<String> - last selected agent IDs
"ai_report_swarms"        // Set<String> - last selected swarm IDs

```

### Additional SharedPreferences

```kotlin
// pricing_cache
"openrouter_pricing"      // JSON Map<String, ModelPricing>
"openrouter_timestamp"    // Long - cache timestamp (7-day TTL)
"litellm_pricing"         // JSON Map<String, ModelPricing>
"litellm_timestamp"       // Long
"manual_pricing"          // JSON Map<String, ModelPricing>
```

### File Storage

```
/files/reports/              # AI Reports (JSON per report)
  └── {report-uuid}.json

/files/usage-stats.json      # AI usage statistics (call counts, token totals per model)
/files/prompt-history.json   # Prompt history (last 100 entries)

/files/chat-history/         # Chat sessions (JSON per session)
  └── {session-uuid}.json

/files/trace/                # API traces (when enabled)
  └── {hostname}_{yyyyMMdd_HHmmss_SSS}.json

/cache/ai_analysis/          # Temp files for sharing
/cache/shared_traces/        # Exported traces

/assets/
  └── model_prices_and_context_window.json  # LiteLLM pricing data
```

## Common Tasks

### Adding a New AI Service

Adding a new OpenAI-compatible service is now streamlined thanks to the provider consolidation:

1. Add enum value to `AiService` in `AiAnalysisApi.kt` with all properties (`apiFormat`, `chatPath`, `modelsPath`, `modelFilter`, `baseUrl`, `adminUrl`, `defaultModel`, `openRouterName`, `prefsKey`)
2. Add hardcoded models list in `AiSettingsModels.kt` (only if provider has no model list API, i.e., `modelsPath` is null)
3. Add default endpoints in `defaultEndpointsForProvider()` in `AiServiceSettingsScreens.kt` (only if provider has multiple API endpoints)
4. Add OpenRouter prefix mapping in `PricingCache.kt` if applicable

That's it for OpenAI-compatible providers. All analysis, streaming, chat, model fetching, settings UI, preferences load/save, and export/import are handled generically.

For a non-OpenAI-compatible provider (new API format), additional steps would be needed:
1. Add a new `ApiFormat` enum value
2. Create a new Retrofit interface in `AiAnalysisApi.kt`
3. Add format-specific methods in `AiAnalysisProviders.kt`, `AiAnalysisStreaming.kt`, `AiAnalysisChat.kt`
4. Add a new SSE parser in `AiAnalysisStreaming.kt`

### Adding a New Agent Parameter

1. Add to `AiParameter` enum in `AiSettingsModels.kt`
2. Add field to `AiAgentParameters` data class in `AiSettingsModels.kt`
3. Add field to `AiParameters` data class in `AiSettingsModels.kt`
4. Add UI control in `AgentEditScreen` in `AiPromptsAgentsScreens.kt`
5. Add UI control in `ParametersEditScreen` in `AiParametersScreen.kt`
6. Update `AgentParametersExport` in `AiSettingsExport.kt`
7. Update request data class in `AiAnalysisApi.kt`
8. Pass parameter in provider methods in `AiAnalysisProviders.kt`
9. Pass parameter in streaming methods if applicable

### Modifying HTML Reports

Edit `AiReportExport.kt`:
- `HtmlReportData` / `HtmlAgentData` data classes define the template data
- `renderHtmlReport()` generates the full HTML (CSS, JS, template)
- `convertGenericAiReportsToHtml()` maps live results to HtmlReportData
- `convertAiReportToHtml()` maps stored AiReport to HtmlReportData
- CSS includes `.think-btn`, `.think-content`, `.cost-table` styles
- JavaScript handles toggle buttons and think section visibility

### Think Section Handling

AI responses containing `<think>...</think>` tags:
- **HTML Reports**: `processThinkSectionsForHtml()` converts to collapsible button + hidden div
- **In-App Viewer**: `ContentWithThinkSections()` composable in `AiContentDisplay.kt`
- **Compose parsing**: `parseContentWithThinkSections()` for splitting into text/think segments

## Export/Import Configuration

### Version 16 Format (Current)

```json
{
  "version": 16,
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
      "apiKey": "",
      "endpointId": "openai-chat-completions",
      "parametersIds": ["params-uuid-1"]
    }
  ],
  "flocks": [
    {
      "id": "uuid",
      "name": "My Flock",
      "agentIds": ["agent-uuid-1", "agent-uuid-2"],
      "parametersIds": ["params-uuid-1"]
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
      "id": "params-uuid-1",
      "name": "Creative",
      "temperature": 0.9,
      "maxTokens": 4096
    }
  ],
  "aiPrompts": [...],
  "manualPricing": [...],
  "providerEndpoints": [...]
}
```

**Version history:** v11 (endpoints), v13 (swarm members), v14 (params presets + flock/swarm rename), v15 (multi-select params), v16 (agent parametersIds).

## External App Integration

See `CALL_AI.md` for complete documentation.

```bash
# Test via ADB
adb shell am start -a com.ai.ACTION_NEW_REPORT \
    --es title "Test Report" \
    --es prompt "What is 2+2?"
```

## Testing Checklist

After making changes:
- [ ] Build succeeds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- [ ] App launches without crash
- [ ] AI Hub displays correctly as home page
- [ ] New AI Report flow works (prompt -> flock/swarm selection -> results)
- [ ] Report progress shows input/output tokens, costs, and duration
- [ ] HTML reports render with cost table in browser
- [ ] AI Chat works with streaming (real-time responses)
- [ ] Chat History shows and continues conversations
- [ ] Model Search finds models across providers
- [ ] Model Info shows OpenRouter/HuggingFace data
- [ ] AI Statistics tracks usage correctly
- [ ] AI Costs shows per-model costs with expandable groups
- [ ] Pricing display shows on agent/model selection screens
- [ ] Agent parameters save and apply correctly
- [ ] Agent endpoint selection works
- [ ] Agent inherits provider values when fields empty
- [ ] Flock creation and agent selection works
- [ ] Swarm creation with provider/model pairs works
- [ ] Parameter presets create/edit/assign correctly
- [ ] Think sections collapse/expand properly
- [ ] Prompt History shows entries and allows reuse
- [ ] AI History lists reports, View/Share/Browser/Delete work
- [ ] Settings navigation works (General, Cost Config, AI Setup)
- [ ] Provider endpoints can be added/edited/deleted
- [ ] API tracing captures requests when enabled
- [ ] Export/import configuration works (v16)
- [ ] All 31 providers show in provider list

## Code Quality Notes

### Error Handling
- All API calls use try-catch with retry logic (500ms delay, 2 attempts)
- Errors logged via `android.util.Log` (not `printStackTrace()`)
- User-friendly error messages in `AiAnalysisResponse.error`
- Streaming errors handled gracefully with partial content preservation

### Thread Safety
- `ConcurrentHashMap` for Retrofit instance cache
- `ReentrantLock` for AiReportStorage operations
- Synchronized `isTracingEnabled` flag access
- Coroutines with `Dispatchers.IO` for network calls
- StateFlow for thread-safe UI state

### Null Safety
- Avoid `!!` operator - use `?.let`, `?:`, or safe calls
- Capture nullable values before lambdas
- All API parameters are nullable with sensible defaults

### Logging Tags
```kotlin
android.util.Log.d("GeminiAPI", "Response code: ${response.code()}")
android.util.Log.w("AiAnalysis", "First attempt failed, retrying...")
android.util.Log.d("PricingCache", "Saved ${pricing.size} OpenRouter prices")
android.util.Log.d("AiReportStorage", "Updated agent status: $agentId")
android.util.Log.d("ChatHistoryManager", "Saved chat session: ${session.id}")
```
