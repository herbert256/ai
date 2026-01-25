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

# Deploy to both emulator and cloud
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
cp app/build/outputs/apk/debug/app-debug.apk /Users/herbert/cloud/ai.apk

# View logs
adb logcat | grep -E "(AiAnalysis|AiHistory|ApiTracer)"
```

## Project Overview

**AI** is an Android app for creating AI-powered reports using 13 different AI services. Users can configure multiple AI agents with advanced parameters, organize them into swarms, submit custom prompts, and generate comparative reports from multiple AI providers simultaneously.

**Key Features:**
- Support for 13 AI services (ChatGPT, Claude, Gemini, Grok, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter, SiliconFlow, Z.AI) + DUMMY for testing
- AI Agents with configurable parameters (temperature, max_tokens, system prompt, etc.)
- AI Swarms for organizing agents into named groups
- Parallel multi-agent report generation with real-time progress
- Collapsible `<think>` sections in AI responses
- Prompt history (up to 100 entries) and HTML report history
- Developer mode with comprehensive API tracing
- HTML report export with markdown rendering, citations, and search results
- Configuration export/import (JSON format, version 6 with swarms)
- External app integration via Intent (see CALL_AI.md)

**Technical Stack:**
- **Language:** Kotlin
- **UI:** Jetpack Compose with Material 3 (dark theme)
- **Architecture:** MVVM with StateFlow
- **Networking:** Retrofit 2 with OkHttp, Gson serialization
- **Android SDK:** minSdk 26, targetSdk 34, compileSdk 34
- **Namespace:** `com.ai`

## Architecture

### Package Structure

```
com.ai/
├── MainActivity.kt                    # Entry point, sets up Compose theme (33 lines)
├── data/                              # Data layer
│   ├── AiAnalysisApi.kt              # Retrofit interfaces, request/response models (602 lines)
│   ├── AiAnalysisRepository.kt       # API logic, retry handling, parameter passing (1,400 lines)
│   ├── AiHistoryManager.kt           # HTML report storage (160 lines)
│   ├── ApiTracer.kt                  # Debug API request/response logging (308 lines)
│   └── DummyApiServer.kt             # Local test HTTP server (155 lines)
└── ui/                                # UI layer
    ├── AiViewModel.kt                # Central state management (435 lines)
    ├── AiModels.kt                   # Core UI state model (57 lines)
    ├── AiScreens.kt                  # Main screens: Hub, NewReport, History, Results (2,473 lines)
    ├── AiSettingsScreen.kt           # AI settings navigation (887 lines)
    ├── AiSettingsModels.kt           # AI settings data: agents, swarms, parameters, providers (433 lines)
    ├── AiSettingsComponents.kt       # Reusable AI settings UI components (937 lines)
    ├── AiServiceSettingsScreens.kt   # Per-service config screens (906 lines)
    ├── AiPromptsAgentsScreens.kt     # Agents CRUD with parameter editing (1,016 lines)
    ├── AiSwarmsScreen.kt             # Swarm management CRUD (375 lines)
    ├── AiSettingsExport.kt           # Configuration export/import v6 (674 lines)
    ├── SettingsScreen.kt             # Settings hub navigation (587 lines)
    ├── GeneralSettingsScreen.kt      # General + Developer settings (177 lines)
    ├── HelpScreen.kt                 # In-app documentation (380 lines)
    ├── TraceScreen.kt                # API trace list and detail viewer (559 lines)
    ├── SettingsPreferences.kt        # SharedPreferences persistence (386 lines)
    ├── SharedComponents.kt           # AiTitleBar, common widgets (127 lines)
    ├── Navigation.kt                 # Jetpack Navigation routes (232 lines)
    └── theme/Theme.kt                # Material3 dark theme (32 lines)
```

**Total:** 23 Kotlin files, ~9,600 lines of code

### Key Data Classes

```kotlin
// AI Services enum (13 services + DUMMY for testing)
enum class AiService(val displayName: String, val baseUrl: String) {
    CHATGPT("ChatGPT", "https://api.openai.com/"),
    CLAUDE("Claude", "https://api.anthropic.com/"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/"),
    GROK("Grok", "https://api.x.ai/"),
    GROQ("Groq", "https://api.groq.com/openai/"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/"),
    MISTRAL("Mistral", "https://api.mistral.ai/"),
    PERPLEXITY("Perplexity", "https://api.perplexity.ai/"),
    TOGETHER("Together", "https://api.together.xyz/"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/"),
    SILICONFLOW("SiliconFlow", "https://api.siliconflow.cn/"),
    ZAI("Z.AI", "https://open.bigmodel.cn/api/paas/"),
    DUMMY("Dummy", "http://localhost:54321/")
}

// AI Agent configuration with advanced parameters
data class AiAgent(
    val id: String,                    // UUID
    val name: String,                  // User-defined name
    val provider: AiService,           // Service to use
    val model: String,                 // Model name
    val apiKey: String,                // API key for this agent
    val parameters: AiAgentParameters = AiAgentParameters()  // Advanced parameters
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
    val searchEnabled: Boolean = false,       // Web search (Grok)
    val returnCitations: Boolean = true,      // Return citations (Perplexity)
    val searchRecency: String? = null         // Search recency: "day", "week", "month", "year"
)

// AI Analysis response (unified format for all services)
data class AiAnalysisResponse(
    val service: AiService,
    val analysis: String?,              // AI-generated text
    val error: String?,                 // Error message if failed
    val tokenUsage: TokenUsage?,        // Input/output token counts
    val agentName: String?,             // Agent name (for display)
    val promptUsed: String?,            // Actual prompt sent
    val citations: List<String>?,       // URLs (Perplexity)
    val searchResults: List<SearchResult>?,  // Search results (Grok, Perplexity)
    val relatedQuestions: List<String>?,     // Follow-up questions (Perplexity)
    val rawUsageJson: String?,          // Raw usage JSON (developer mode)
    val httpHeaders: String?            // HTTP headers (developer mode)
)

// General settings
data class GeneralSettings(
    val paginationPageSize: Int = 25,   // 5-50
    val developerMode: Boolean = false,
    val trackApiCalls: Boolean = false
)
```

### Design Patterns

1. **MVVM with StateFlow**: `AiViewModel` exposes `StateFlow<AiUiState>`, UI recomposes reactively via `collectAsState()`

2. **Repository Pattern**: `AiAnalysisRepository` handles all API interactions, retry logic, parameter passing, and response normalization

3. **Singleton Helpers**: `AiHistoryManager`, `ApiTracer`, and `DummyApiServer` are singletons initialized in ViewModel

4. **Factory Pattern**: `AiApiFactory` creates and caches Retrofit instances per base URL using `ConcurrentHashMap`

5. **Thread Safety**:
   - `ConcurrentHashMap` for Retrofit cache
   - Synchronized access to `isTracingEnabled` flag
   - Coroutines with `Dispatchers.IO` for network calls
   - StateFlow for thread-safe state updates

## AI Services

### Supported Services (13 + DUMMY)

| Service | Default Model | API Format | Auth Method | Model Source |
|---------|--------------|------------|-------------|--------------|
| ChatGPT | gpt-4o-mini | OpenAI Chat/Responses | Bearer token | API |
| Claude | claude-sonnet-4-20250514 | Anthropic Messages | x-api-key header | Manual (8 models) |
| Gemini | gemini-2.0-flash | Google GenerativeAI | Query parameter | API |
| Grok | grok-3-mini | OpenAI-compatible | Bearer token | API |
| Groq | llama-3.3-70b-versatile | OpenAI-compatible | Bearer token | API |
| DeepSeek | deepseek-chat | OpenAI-compatible | Bearer token | API |
| Mistral | mistral-small-latest | OpenAI-compatible | Bearer token | API |
| Perplexity | sonar | OpenAI-compatible | Bearer token | Manual (4 models) |
| Together | Llama-3.3-70B-Instruct-Turbo | OpenAI-compatible | Bearer token | API |
| OpenRouter | anthropic/claude-3.5-sonnet | OpenAI-compatible | Bearer token | API |
| SiliconFlow | Qwen/Qwen2.5-7B-Instruct | OpenAI-compatible | Bearer token | Manual (9 models) |
| Z.AI | glm-4.7-flash | OpenAI-compatible | Bearer token | Manual (7 models) |
| DUMMY | dummy-model | OpenAI-compatible | Local server | API (dev mode only) |

### Service-Specific Features

- **ChatGPT**: Supports Chat Completions API (gpt-4o, etc.) and Responses API (gpt-5.x, o3, o4). JSON response format option.
- **Claude**: Hardcoded model list (8 models - no list API), Anthropic-specific format with content blocks
- **Gemini**: Uses generationConfig object for parameters, systemInstruction for system prompt
- **DeepSeek**: Handles `reasoning_content` field for reasoning models
- **Perplexity**: Returns `citations`, `search_results`, `related_questions`. Supports search recency filter.
- **Grok**: Optional web search via `search` parameter. May return `search_results`.
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
val PERPLEXITY_MODELS = listOf(
    "sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research"
)

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
    const val SETTINGS = "settings"                        // Settings hub
    const val HELP = "help"                                // Help screen
    const val TRACE_LIST = "trace_list"                    // API traces
    const val TRACE_DETAIL = "trace_detail/{filename}"     // Trace detail
}
```

### Screen Flow

```
AI Hub (Home)
├── New AI Report → Swarm Selection → Progress → Results
│   └── Results: View, Export HTML, Share, toggle agent visibility
├── Prompt History → Click to reuse → New AI Report (pre-filled)
├── AI History → View/Share/Delete HTML reports
├── Settings → General / AI Setup
│   ├── General: Pagination, Developer mode, API tracing
│   └── AI Setup → Providers / Agents / Swarms / Export
└── Help
```

## Settings & Persistence

### SharedPreferences Keys (`eval_prefs`)

```kotlin
// General settings
"pagination_page_size"    // Int (5-50, default 25)
"developer_mode"          // Boolean
"track_api_calls"         // Boolean

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
```

## File Storage

### Directories

```
/files/ai-history/           # HTML reports
  └── ai_{yyyyMMdd_HHmmss}.html

/files/trace/                # API traces (when enabled)
  └── {hostname}_{yyyyMMdd_HHmmss_SSS}.json

/cache/ai_analysis/          # Temp files for sharing
/cache/shared_traces/        # Exported traces
```

## Common Tasks

### Adding a New AI Service

1. Add enum value to `AiService` in `AiAnalysisApi.kt`
2. Create request/response data classes if format differs
3. Create Retrofit interface in `AiAnalysisApi.kt`
4. Add factory method in `AiApiFactory`
5. Add analysis method in `AiAnalysisRepository.kt` (with parameter support)
6. Add to `PROVIDER_SUPPORTED_PARAMETERS` map in `AiSettingsModels.kt`
7. Add settings fields to `AiSettings` in `AiSettingsModels.kt`
8. Add UI in `AiServiceSettingsScreens.kt`
9. Add SharedPreferences keys in `SettingsPreferences.kt`
10. Update load/save methods
11. Update export/import in `AiSettingsExport.kt`
12. Update SettingsScreen.kt navigation enum and when block

### Adding a New Agent Parameter

1. Add to `AiParameter` enum in `AiSettingsModels.kt`
2. Add field to `AiAgentParameters` data class
3. Update `PROVIDER_SUPPORTED_PARAMETERS` for each provider that supports it
4. Add UI control in `AgentEditScreen` in `AiPromptsAgentsScreens.kt`
5. Update request data class in `AiAnalysisApi.kt`
6. Pass parameter in `analyzeWith*` method in `AiAnalysisRepository.kt`

### Modifying HTML Reports

Edit `convertGenericAiReportsToHtml()` in `AiScreens.kt`:
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

### Version 6 Format (Current)

```json
{
  "version": 6,
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

**Backward Compatibility**: Imports versions 3, 4, 5, and 6.

## Testing Checklist

After making changes:
- [ ] Build succeeds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- [ ] App launches without crash
- [ ] AI Hub displays correctly as home page
- [ ] New AI Report flow works (prompt → swarms → results)
- [ ] Agent parameters save and apply correctly
- [ ] Swarm creation and selection works
- [ ] Think sections collapse/expand properly
- [ ] Prompt History shows entries and allows reuse
- [ ] AI History lists reports, view/share/delete work
- [ ] Settings navigation works (General with Developer, AI Setup)
- [ ] API tracing captures requests when enabled
- [ ] HTML reports render correctly in browser
- [ ] Export/import configuration works (v6 with swarms)
- [ ] DUMMY provider hidden when not in developer mode
- [ ] All 13 providers show in provider list

## Code Quality Notes

### Error Handling
- All API calls use try-catch with retry logic (500ms delay, 2 attempts)
- Errors logged via `android.util.Log` (not `printStackTrace()`)
- User-friendly error messages in `AiAnalysisResponse.error`

### Thread Safety
- `ConcurrentHashMap` for Retrofit instance cache
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
```
