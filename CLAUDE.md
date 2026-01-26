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

**AI** is an Android app for creating AI-powered reports and having conversations using 13 different AI services. Users can configure multiple AI agents with advanced parameters, organize them into swarms, submit custom prompts, generate comparative reports, search and explore models across all providers, chat with streaming responses, and track usage costs.

**Key Features:**
- Support for 13 AI services (OpenAI, Anthropic, Google, xAI, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI) + DUMMY for testing
- AI Chat with streaming responses (real-time token-by-token) and auto-save
- AI Agents with configurable parameters (temperature, max_tokens, system prompt, etc.)
- AI Swarms for organizing agents into named groups
- Parallel multi-agent report generation with persistent storage
- AI Reports stored as JSON objects with View/Share/Browser actions
- Model Search across all configured providers with one-tap chat or agent creation
- Model Info with OpenRouter pricing/specs and Hugging Face metadata
- AI Statistics tracking API calls and token usage
- AI Costs with four-tier pricing (Manual > OpenRouter > LiteLLM > Fallback)
- Collapsible `<think>` sections in AI responses
- Prompt history (up to 100 entries)
- Chat history with automatic conversation saving
- Developer mode with comprehensive API tracing
- HTML report export with markdown rendering, citations, and search results
- Configuration export/import (JSON format, version 7)
- External app integration via Intent (see CALL_AI.md)

**Technical Stack:**
- **Language:** Kotlin
- **UI:** Jetpack Compose with Material 3 (dark theme)
- **Architecture:** MVVM with StateFlow
- **Networking:** Retrofit 2 with OkHttp, Gson serialization
- **Streaming:** SSE (Server-Sent Events) parsing with Kotlin Flow
- **Persistence:** SharedPreferences (JSON) + File storage
- **Android SDK:** minSdk 26, targetSdk 34, compileSdk 34
- **Namespace:** `com.ai`

## Architecture

### Package Structure

```
com.ai/
├── MainActivity.kt                    # Entry point, sets up Compose theme
├── data/                              # Data layer
│   ├── AiAnalysisApi.kt              # Retrofit interfaces, request/response models (~1,036 lines)
│   ├── AiAnalysisRepository.kt       # API logic, streaming, retry handling (~2,402 lines)
│   ├── AiReportStorage.kt            # Persistent report storage with thread-safe ops (~315 lines)
│   ├── AiHistoryManager.kt           # Legacy HTML report storage (~160 lines)
│   ├── ChatHistoryManager.kt         # Chat session file storage (~123 lines)
│   ├── PricingCache.kt               # Four-tier pricing cache (~439 lines)
│   ├── ApiTracer.kt                  # Debug API request/response logging (~308 lines)
│   └── DummyApiServer.kt             # Local test HTTP server (~155 lines)
└── ui/                                # UI layer
    ├── AiViewModel.kt                # Central state management (~715 lines)
    ├── AiModels.kt                   # Core UI state model (~63 lines)
    ├── AiScreens.kt                  # Report screens: Hub, NewReport, History, Results (~3,780 lines)
    ├── ChatScreens.kt                # AI Chat: conversation UI, streaming (~1,074 lines)
    ├── AiStatisticsScreen.kt         # Statistics and Costs screens (~1,069 lines)
    ├── AiSettingsScreen.kt           # AI settings navigation (~1,714 lines)
    ├── AiSettingsModels.kt           # AI settings data: agents, swarms, parameters (~542 lines)
    ├── AiSettingsComponents.kt       # Reusable AI settings UI components (~1,073 lines)
    ├── AiServiceSettingsScreens.kt   # Per-service config screens (~1,022 lines)
    ├── AiPromptsAgentsScreens.kt     # Agents CRUD with parameter editing (~1,001 lines)
    ├── AiPromptsScreen.kt            # Prompt history screen (~391 lines)
    ├── AiSwarmsScreen.kt             # Swarm management CRUD (~375 lines)
    ├── AiSettingsExport.kt           # Configuration export/import v7 (~785 lines)
    ├── SettingsScreen.kt             # Settings hub + Cost Configuration (~825 lines)
    ├── GeneralSettingsScreen.kt      # General + Developer settings (~200 lines)
    ├── HelpScreen.kt                 # In-app documentation (~497 lines)
    ├── TraceScreen.kt                # API trace list and detail viewer (~575 lines)
    ├── SettingsPreferences.kt        # SharedPreferences persistence (~552 lines)
    ├── SharedComponents.kt           # AiTitleBar, common widgets (~127 lines)
    ├── Navigation.kt                 # Jetpack Navigation routes (~569 lines)
    └── theme/Theme.kt                # Material3 dark theme (~32 lines)
```

**Total:** ~22,000 lines of Kotlin code

### Key Data Classes

```kotlin
// AI Services enum (13 services + DUMMY for testing)
enum class AiService(val displayName: String, val baseUrl: String, val adminUrl: String, val defaultModel: String) {
    OPENAI("OpenAI", "https://api.openai.com/", "...", "gpt-4o-mini"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/", "...", "claude-sonnet-4-20250514"),
    GOOGLE("Google", "https://generativelanguage.googleapis.com/", "...", "gemini-2.0-flash"),
    XAI("xAI", "https://api.x.ai/", "...", "grok-3-mini"),
    GROQ("Groq", "https://api.groq.com/openai/", "...", "llama-3.3-70b-versatile"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/", "...", "deepseek-chat"),
    MISTRAL("Mistral", "https://api.mistral.ai/", "...", "mistral-small-latest"),
    PERPLEXITY("Perplexity", "https://api.perplexity.ai/", "...", "sonar"),
    TOGETHER("Together", "https://api.together.xyz/", "...", "meta-llama/Llama-3.3-70B-Instruct-Turbo"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/", "...", "anthropic/claude-3.5-sonnet"),
    SILICONFLOW("SiliconFlow", "https://api.siliconflow.com/", "...", "Qwen/Qwen2.5-7B-Instruct"),
    ZAI("Z.AI", "https://api.z.ai/api/paas/v4/", "...", "glm-4.7-flash"),
    DUMMY("Dummy", "http://localhost:54321/", "", "dummy-model")
}

// AI Agent configuration with advanced parameters
data class AiAgent(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val provider: AiService,           // Service to use
    val model: String,                 // Model name
    val apiKey: String,                // API key for this agent
    val parameters: AiAgentParameters = AiAgentParameters()
)

// AI Swarm - groups of agents for collaborative analysis
data class AiSwarm(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val agentIds: List<String>         // References to AiAgent IDs
)

// Agent parameters (all optional - null means use provider default)
data class AiAgentParameters(
    val temperature: Float? = null,           // Randomness (0.0-2.0)
    val maxTokens: Int? = null,               // Maximum response length
    val topP: Float? = null,                  // Nucleus sampling (0.0-1.0)
    val topK: Int? = null,                    // Vocabulary limit
    val frequencyPenalty: Float? = null,      // Reduces repetition (-2.0 to 2.0)
    val presencePenalty: Float? = null,       // Encourages new topics (-2.0 to 2.0)
    val systemPrompt: String? = null,         // System instruction
    val stopSequences: List<String>? = null,  // Stop generation sequences
    val seed: Int? = null,                    // For reproducibility
    val responseFormatJson: Boolean = false,  // JSON mode (OpenAI)
    val searchEnabled: Boolean = false,       // Web search (xAI)
    val returnCitations: Boolean = true,      // Return citations (Perplexity)
    val searchRecency: String? = null         // Search recency: "day", "week", "month", "year"
)

// Persistent AI Report storage
data class AiReport(
    val id: String,
    val timestamp: Long,
    val title: String,
    val prompt: String,
    val agents: MutableList<AiReportAgent>,
    var totalCost: Double = 0.0,
    var completedAt: Long? = null
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
    var relatedQuestions: List<String>? = null
)

// Pricing cache for cost calculations
data class ModelPricing(
    val modelId: String,
    val promptPrice: Double,     // Price per token
    val completionPrice: Double, // Price per token
    val source: String           // "manual", "openrouter", "litellm", "fallback"
)
```

### Design Patterns

1. **MVVM with StateFlow**: `AiViewModel` exposes `StateFlow<AiUiState>`, UI recomposes reactively via `collectAsState()`

2. **Repository Pattern**: `AiAnalysisRepository` handles all API interactions, streaming, retry logic, parameter passing, and response normalization

3. **Singleton Storage Managers**:
   - `AiReportStorage` - Thread-safe report persistence with ReentrantLock
   - `ChatHistoryManager` - Chat session file storage
   - `AiHistoryManager` - Legacy HTML report storage
   - `PricingCache` - Four-tier pricing with weekly caching
   - `ApiTracer` - Debug API logging
   - `DummyApiServer` - Local test HTTP server

4. **Factory Pattern**: `AiApiFactory` creates and caches Retrofit instances per base URL using `ConcurrentHashMap`

5. **Flow-based Streaming**: SSE parsing returns `Flow<String>` for real-time token emission

6. **Thread Safety**:
   - `ConcurrentHashMap` for Retrofit cache
   - `ReentrantLock` for AiReportStorage
   - Synchronized access to `isTracingEnabled` flag
   - Coroutines with `Dispatchers.IO` for network calls
   - StateFlow for thread-safe state updates

## Streaming Implementation

### Architecture

Chat uses Server-Sent Events (SSE) for real-time streaming:

```kotlin
// Repository returns Flow<String> for streaming
fun sendChatMessageStream(
    service: AiService,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    params: ChatParameters
): Flow<String>

// SSE parsing for OpenAI-compatible format
private fun parseOpenAiSseStream(responseBody: ResponseBody): Flow<String> = flow {
    val reader = responseBody.charStream().buffered()
    while (reader.readLine().also { line = it } != null) {
        if (line.startsWith("data: ")) {
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break
            val chunk = gson.fromJson(data, OpenAiStreamChunk::class.java)
            chunk?.choices?.firstOrNull()?.delta?.content?.let { emit(it) }
        }
    }
}
```

### Provider-Specific Parsers

| Provider | Stream Format | Parser |
|----------|--------------|--------|
| OpenAI, xAI, Groq, DeepSeek, Mistral, Perplexity, Together, OpenRouter, SiliconFlow, Z.AI, DUMMY | OpenAI SSE | `parseOpenAiSseStream()` |
| Anthropic | Claude SSE with events | `parseClaudeSseStream()` |
| Google | Gemini SSE | `parseGeminiSseStream()` |

### UI Collection

```kotlin
// ChatSessionScreen collects stream and updates UI
onSendMessageStream(messages).collect { chunk ->
    streamingContent += chunk
}
```

## Pricing System

### Four-Tier Lookup (PricingCache.kt)

1. **Manual overrides** - User-set prices via Cost Configuration screen
2. **OpenRouter API** - Weekly-cached pricing from OpenRouter
3. **LiteLLM JSON** - Bundled `model_prices_and_context_window.json` in assets
4. **Hardcoded fallback** - Built-in prices for common models

```kotlin
fun getPricing(context: Context, provider: AiService, model: String): ModelPricing? {
    // 1. Manual overrides (highest priority)
    manualPricing?.get("${provider.name}:$model")?.let { return it }
    // 2. OpenRouter cache
    openRouterPricing?.get(model)?.let { return it }
    // 3. LiteLLM
    litellmPricing?.get(model)?.let { return it }
    // 4. Fallback
    return FALLBACK_PRICING[model]
}
```

### Cost Calculation

Costs are calculated per-agent during report generation:
```kotlin
val cost = pricing?.let {
    (tokenUsage.inputTokens * it.promptPrice) + (tokenUsage.outputTokens * it.completionPrice)
}
```

## Report Storage (AiReportStorage.kt)

### Thread-Safe Operations

```kotlin
object AiReportStorage {
    private val lock = ReentrantLock()

    fun updateAgentStatus(...) {
        lock.withLock {
            val reports = loadAllReports(context).toMutableMap()
            // Update agent status
            saveReports(reports)
        }
    }
}
```

### Report Lifecycle

1. `createReport()` - Creates new report with pending agents
2. `markAgentRunning()` - Agent starts API call
3. `markAgentSuccess()` or `markAgentError()` - Agent completes
4. Report auto-completes when all agents finish

### Export Options

- **JSON** - Raw AiReport object via `shareAiReportAsJson()`
- **HTML** - Generated on-demand via `convertAiReportToHtml()`

## AI Services

### Supported Services (13 + DUMMY)

| Service | Default Model | API Format | Auth Method | Streaming |
|---------|--------------|------------|-------------|-----------|
| OpenAI | gpt-4o-mini | OpenAI Chat/Responses | Bearer token | SSE |
| Anthropic | claude-sonnet-4-20250514 | Anthropic Messages | x-api-key header | SSE |
| Google | gemini-2.0-flash | Google GenerativeAI | Query parameter | SSE |
| xAI | grok-3-mini | OpenAI-compatible | Bearer token | SSE |
| Groq | llama-3.3-70b-versatile | OpenAI-compatible | Bearer token | SSE |
| DeepSeek | deepseek-chat | OpenAI-compatible | Bearer token | SSE |
| Mistral | mistral-small-latest | OpenAI-compatible | Bearer token | SSE |
| Perplexity | sonar | OpenAI-compatible | Bearer token | SSE |
| Together | Llama-3.3-70B-Instruct-Turbo | OpenAI-compatible | Bearer token | SSE |
| OpenRouter | anthropic/claude-3.5-sonnet | OpenAI-compatible | Bearer token | SSE |
| SiliconFlow | Qwen/Qwen2.5-7B-Instruct | OpenAI-compatible | Bearer token | SSE |
| Z.AI | glm-4.7-flash | OpenAI-compatible | Bearer token | SSE |
| DUMMY | dummy-model | OpenAI-compatible | Local server | SSE |

### Service-Specific Features

- **OpenAI**: Supports Chat Completions API (gpt-4o, etc.) and Responses API (gpt-5.x, o3, o4). JSON response format option.
- **Anthropic**: Hardcoded model list (8 models - no list API), Anthropic-specific format with content blocks
- **Google**: Uses generationConfig object for parameters, systemInstruction for system prompt
- **DeepSeek**: Handles `reasoning_content` field for reasoning models, displayed in think sections
- **Perplexity**: Returns `citations`, `search_results`, `related_questions`. Supports search recency filter.
- **xAI**: Optional web search via `search` parameter. May return `search_results`.
- **SiliconFlow**: Cost-effective Chinese AI provider with Qwen and DeepSeek models
- **Z.AI**: ZhipuAI GLM models (Chinese provider)
- **DUMMY**: Local test server on port 54321, auto-starts in developer mode

### Hardcoded Models

```kotlin
// Claude (no list API)
val CLAUDE_MODELS = listOf(
    "claude-sonnet-4-20250514", "claude-opus-4-20250514",
    "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022",
    "claude-3-5-haiku-20241022", "claude-3-opus-20240229",
    "claude-3-sonnet-20240229", "claude-3-haiku-20240307"
)

// Perplexity (no list API)
val PERPLEXITY_MODELS = listOf("sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research")

// SiliconFlow
val SILICONFLOW_MODELS = listOf(
    "Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-14B-Instruct",
    "Qwen/Qwen2.5-32B-Instruct", "Qwen/Qwen2.5-72B-Instruct",
    "Qwen/QwQ-32B", "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
    "THUDM/glm-4-9b-chat", "meta-llama/Llama-3.3-70B-Instruct"
)

// Z.AI (ZhipuAI GLM)
val ZAI_MODELS = listOf(
    "glm-4.7-flash", "glm-4.7", "glm-4.5-flash", "glm-4.5",
    "glm-4-plus", "glm-4-long", "glm-4-flash"
)
```

### API Configuration

```kotlin
// OkHttpClient configuration (AiApiFactory)
private val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(TracingInterceptor())
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(420, TimeUnit.SECONDS)  // 7 min for long requests
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

// Retry logic (AiAnalysisRepository)
companion object {
    private const val RETRY_DELAY_MS = 500L
    private const val TEST_PROMPT = "Reply with exactly: OK"
}
```

## Navigation

### Routes

```kotlin
object NavRoutes {
    const val AI = "ai"                                    // Home (AI Hub)
    const val AI_HISTORY = "ai_history"                    // Report history
    const val AI_NEW_REPORT = "ai_new_report"              // New report
    const val AI_NEW_REPORT_WITH_PARAMS = "ai_new_report/{title}/{prompt}"
    const val AI_PROMPT_HISTORY = "ai_prompt_history"      // Prompt history
    const val AI_CHAT = "ai_chat"                          // Chat interface
    const val AI_CHAT_HISTORY = "ai_chat_history"          // Chat history
    const val AI_MODEL_SEARCH = "ai_model_search"          // Model search
    const val AI_MODEL_INFO = "ai_model_info/{provider}/{model}"
    const val AI_STATISTICS = "ai_statistics"              // Usage statistics
    const val AI_COSTS = "ai_costs"                        // Cost tracking
    const val SETTINGS = "settings"                        // Settings hub
    const val COST_CONFIG = "cost_config"                  // Cost configuration
    const val HELP = "help"                                // Help screen
    const val TRACE_LIST = "trace_list"                    // API traces
    const val TRACE_DETAIL = "trace_detail/{filename}"     // Trace detail
}
```

### Screen Flow

```
AI Hub (Home)
├── AI Report → New Report → Swarm Selection → Progress → Results
│   └── Results: View, Share (JSON/HTML), Browser, toggle agents
├── AI Chat → Provider Selection → Model Selection → Chat (streaming)
│   └── Chat: Multi-turn, parameters, auto-save
├── AI Models → Search → Model Info / Start Chat / Create Agent
├── AI History → View/Share/Browser/Delete reports (from AiReportStorage)
├── AI Statistics → API calls, token usage per provider/model
├── AI Costs → Expandable provider groups, per-model costs, refresh pricing
├── Settings → General / Cost Config / AI Setup / Help
│   ├── General: Username, Full screen, Developer mode, API tracing
│   ├── Cost Config: Manual price overrides per model
│   └── AI Setup → Providers / Agents / Swarms / Export / Refresh
└── Help → Comprehensive in-app documentation
```

## Settings & Persistence

### SharedPreferences Keys (`eval_prefs`)

```kotlin
// General settings
"pagination_page_size"    // Int (5-50, default 25)
"developer_mode"          // Boolean
"track_api_calls"         // Boolean
"username"                // String (default "You")
"full_screen_mode"        // Boolean
"hugging_face_api_key"    // String (for model info)

// Per-service AI settings (×13 services)
"ai_{service}_api_key"        // String
"ai_{service}_model"          // String
"ai_{service}_model_source"   // Enum: API or MANUAL
"ai_{service}_manual_models"  // JSON List<String>

// Agents and Swarms (JSON)
"ai_agents"               // JSON List<AiAgent>
"ai_swarms"               // JSON List<AiSwarm>

// Report generation
"ai_report_agents"        // Set<String> - last selected agent IDs
"ai_report_swarms"        // Set<String> - last selected swarm IDs

// History
"prompt_history"          // JSON List<PromptHistoryEntry>

// Statistics
"ai_statistics"           // JSON Map<provider:model, ApiCallStats>
```

### Additional SharedPreferences (`ai_reports_storage`)

```kotlin
"reports"                 // JSON Map<String, AiReport> - persistent reports
```

### Additional SharedPreferences (`pricing_cache`)

```kotlin
"openrouter_pricing"      // JSON Map<String, ModelPricing>
"openrouter_timestamp"    // Long - cache timestamp
"litellm_pricing"         // JSON Map<String, ModelPricing>
"litellm_timestamp"       // Long
"manual_pricing"          // JSON Map<String, ModelPricing>
```

## File Storage

### Directories

```
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

1. Add enum value to `AiService` in `AiAnalysisApi.kt`
2. Create request/response data classes if format differs
3. Create Retrofit interface in `AiAnalysisApi.kt`
4. Add factory methods in `AiApiFactory` (regular + streaming)
5. Add analysis method in `AiAnalysisRepository.kt` (with parameter support)
6. Add streaming method in `AiAnalysisRepository.kt`
7. Add to `sendChatMessageStream()` switch statement
8. Add to `PROVIDER_SUPPORTED_PARAMETERS` map in `AiSettingsModels.kt`
9. Add settings fields to `AiSettings` in `AiSettingsModels.kt`
10. Add UI in `AiServiceSettingsScreens.kt`
11. Add SharedPreferences keys in `SettingsPreferences.kt`
12. Update load/save methods
13. Update export/import in `AiSettingsExport.kt`
14. Update SettingsScreen.kt navigation enum and when block
15. Add OpenRouter prefix mapping in `PricingCache.kt` if applicable

### Adding a New Agent Parameter

1. Add to `AiParameter` enum in `AiSettingsModels.kt`
2. Add field to `AiAgentParameters` data class
3. Update `PROVIDER_SUPPORTED_PARAMETERS` for each provider that supports it
4. Add UI control in `AgentEditScreen` in `AiPromptsAgentsScreens.kt`
5. Update request data class in `AiAnalysisApi.kt`
6. Pass parameter in `analyzeWith*` method in `AiAnalysisRepository.kt`
7. Pass parameter in streaming method if applicable

### Modifying HTML Reports

Edit `convertAiReportToHtml()` in `AiScreens.kt`:
- CSS styles in `<style>` block (includes .think-btn and .think-content for collapsible think sections)
- HTML structure with agent divs
- JavaScript for toggle buttons and think section visibility
- Developer mode sections (usage, headers)

### Think Section Handling

AI responses containing `<think>...</think>` tags are processed specially:
- **HTML Reports**: Converted to collapsible button + hidden div with JavaScript toggle
- **In-App Viewer**: `ContentWithThinkSections()` composable renders with `ThinkSection()` buttons
- Helper function: `processThinkSectionsForHtml()` for HTML, `parseContentWithThinkSections()` for Compose

## External App Integration

The AI app can be launched from other Android apps to generate reports. See `CALL_AI.md` for complete integration documentation.

### Intent Configuration

**AndroidManifest.xml:**
```xml
<intent-filter>
    <action android:name="com.ai.ACTION_NEW_REPORT" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

### Intent Extras

| Extra | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | String | No | Report title |
| `prompt` | String | Yes | Prompt text to send to AI agents |

### Testing via ADB

```bash
adb shell am start -a com.ai.ACTION_NEW_REPORT \
    --es title "Test Report" \
    --es prompt "What is 2+2?"
```

## Export/Import Configuration

### Version 7 Format (Current)

```json
{
  "version": 7,
  "huggingFaceApiKey": "hf_...",
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
      "parameters": { ... }
    }
  ],
  "swarms": [
    {
      "id": "uuid",
      "name": "Expert Team",
      "agentIds": ["agent-1-id", "agent-2-id"]
    }
  ]
}
```

**Backward Compatibility**: Imports versions 3, 4, 5, 6, and 7.

## Testing Checklist

After making changes:
- [ ] Build succeeds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- [ ] App launches without crash
- [ ] AI Hub displays correctly as home page
- [ ] New AI Report flow works (prompt → swarms → results)
- [ ] Report progress shows input/output tokens and costs
- [ ] AI Chat works with streaming (real-time responses)
- [ ] Chat History shows and continues conversations
- [ ] Model Search finds models across providers
- [ ] Model Info shows OpenRouter/HuggingFace data
- [ ] AI Statistics tracks usage correctly
- [ ] AI Costs shows per-model costs with expandable groups
- [ ] Agent parameters save and apply correctly
- [ ] Swarm creation and selection works
- [ ] Think sections collapse/expand properly
- [ ] Prompt History shows entries and allows reuse
- [ ] AI History lists reports, View/Share/Browser/Delete work
- [ ] Settings navigation works (General, Cost Config, AI Setup)
- [ ] Cost Configuration allows manual price overrides
- [ ] Refresh model lists button works
- [ ] API tracing captures requests when enabled
- [ ] HTML reports render correctly in browser
- [ ] Export/import configuration works (v7)
- [ ] DUMMY provider hidden when not in developer mode
- [ ] All 13 providers show in provider list

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
android.util.Log.e("AiHistoryManager", "Failed to save: ${e.message}")
android.util.Log.d("PricingCache", "Saved ${pricing.size} OpenRouter prices")
android.util.Log.d("AiReportStorage", "Updated agent status: $agentId")
android.util.Log.d("ChatHistoryManager", "Saved chat session: ${session.id}")
```
