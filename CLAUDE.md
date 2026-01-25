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

# View logs
adb logcat | grep -E "(AiAnalysis|AiHistory|ApiTracer)"
```

## Project Overview

**AI** is an Android app for creating AI-powered reports using 10 different AI services. Users can configure multiple AI agents with advanced parameters, submit custom prompts, and generate comparative reports from multiple AI providers simultaneously.

**Key Features:**
- Support for 10 AI services (ChatGPT, Claude, Gemini, Grok, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter) + DUMMY for testing
- AI Agents with configurable parameters (temperature, max_tokens, system prompt, etc.)
- Parallel multi-agent report generation with real-time progress
- Prompt history (up to 100 entries) and HTML report history
- Developer mode with comprehensive API tracing
- HTML report export with markdown rendering, citations, and search results
- Configuration export/import (JSON format, version 5 with parameters)
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
│   ├── AiAnalysisApi.kt              # Retrofit interfaces, request/response models (540 lines)
│   ├── AiAnalysisRepository.kt       # API logic, retry handling, parameter passing (1,150 lines)
│   ├── AiHistoryManager.kt           # HTML report storage (156 lines)
│   └── ApiTracer.kt                  # Debug API request/response logging (308 lines)
└── ui/                                # UI layer
    ├── AiViewModel.kt                # Central state management (342 lines)
    ├── AiModels.kt                   # Core UI state model (55 lines)
    ├── AiScreens.kt                  # Main screens: Hub, NewReport, History, Results (2,036 lines)
    ├── AiSettingsScreen.kt           # AI settings navigation (443 lines)
    ├── AiSettingsModels.kt           # AI settings data: agents, parameters, providers (343 lines)
    ├── AiSettingsComponents.kt       # Reusable AI settings UI components (721 lines)
    ├── AiServiceSettingsScreens.kt   # Per-service config screens (644 lines)
    ├── AiPromptsAgentsScreens.kt     # Agents CRUD with parameter editing (814 lines)
    ├── AiSettingsExport.kt           # Configuration export/import v5 (590 lines)
    ├── SettingsScreen.kt             # Settings hub navigation (301 lines)
    ├── GeneralSettingsScreen.kt      # General settings: pagination, dev mode (99 lines)
    ├── DeveloperSettingsScreen.kt    # Developer settings: API tracing (118 lines)
    ├── HelpScreen.kt                 # In-app documentation (260 lines)
    ├── TraceScreen.kt                # API trace list and detail viewer (560 lines)
    ├── SettingsPreferences.kt        # SharedPreferences persistence (298 lines)
    ├── SharedComponents.kt           # AiTitleBar, common widgets (106 lines)
    ├── Navigation.kt                 # Jetpack Navigation routes (203 lines)
    └── theme/Theme.kt                # Material3 dark theme (32 lines)
```

**Total:** 22 Kotlin files, ~9,050 lines of code

### Key Data Classes

```kotlin
// AI Services enum (10 services + DUMMY for testing)
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
    DUMMY("Dummy", "")
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

// Provider-supported parameters map (determines which UI options to show)
val PROVIDER_SUPPORTED_PARAMETERS: Map<AiService, Set<AiParameter>> = mapOf(
    AiService.CHATGPT to setOf(TEMPERATURE, MAX_TOKENS, TOP_P, FREQUENCY_PENALTY,
        PRESENCE_PENALTY, SYSTEM_PROMPT, STOP_SEQUENCES, SEED, RESPONSE_FORMAT),
    AiService.CLAUDE to setOf(TEMPERATURE, MAX_TOKENS, TOP_P, TOP_K, SYSTEM_PROMPT, STOP_SEQUENCES),
    AiService.GEMINI to setOf(TEMPERATURE, MAX_TOKENS, TOP_P, TOP_K, SYSTEM_PROMPT, STOP_SEQUENCES),
    AiService.GROK to setOf(TEMPERATURE, MAX_TOKENS, TOP_P, FREQUENCY_PENALTY,
        PRESENCE_PENALTY, SYSTEM_PROMPT, STOP_SEQUENCES, SEARCH_ENABLED),
    AiService.PERPLEXITY to setOf(TEMPERATURE, MAX_TOKENS, TOP_P, FREQUENCY_PENALTY,
        PRESENCE_PENALTY, SYSTEM_PROMPT, RETURN_CITATIONS, SEARCH_RECENCY),
    // ... etc for other providers
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

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)
```

### Design Patterns

1. **MVVM with StateFlow**: `AiViewModel` exposes `StateFlow<AiUiState>`, UI recomposes reactively via `collectAsState()`

2. **Repository Pattern**: `AiAnalysisRepository` handles all API interactions, retry logic, parameter passing, and response normalization

3. **Singleton Helpers**: `AiHistoryManager` and `ApiTracer` are singletons initialized in ViewModel

4. **Factory Pattern**: `AiApiFactory` creates and caches Retrofit instances per base URL using `ConcurrentHashMap`

5. **Thread Safety**:
   - `ConcurrentHashMap` for Retrofit cache
   - Synchronized access to `isTracingEnabled` flag
   - Coroutines with `Dispatchers.IO` for network calls
   - StateFlow for thread-safe state updates

## AI Services

### Supported Services (10 + DUMMY)

| Service | Default Model | API Format | Auth Method |
|---------|--------------|------------|-------------|
| ChatGPT | gpt-4o-mini | OpenAI Chat/Responses | Bearer token |
| Claude | claude-sonnet-4-20250514 | Anthropic Messages | x-api-key header |
| Gemini | gemini-2.0-flash | Google GenerativeAI | Query parameter |
| Grok | grok-3-mini | OpenAI-compatible | Bearer token |
| Groq | llama-3.3-70b-versatile | OpenAI-compatible | Bearer token |
| DeepSeek | deepseek-chat | OpenAI-compatible | Bearer token |
| Mistral | mistral-small-latest | OpenAI-compatible | Bearer token |
| Perplexity | sonar | OpenAI-compatible | Bearer token |
| Together | Llama-3.3-70B-Instruct-Turbo | OpenAI-compatible | Bearer token |
| OpenRouter | anthropic/claude-3.5-sonnet | OpenAI-compatible | Bearer token |
| DUMMY | dummy-model | N/A | For testing (dev mode only) |

### Service-Specific Features

- **ChatGPT**: Supports Chat Completions API (gpt-4o, etc.) and Responses API (gpt-5.x, o3, o4). JSON response format option.
- **Claude**: Hardcoded model list (8 models - no list API), Anthropic-specific format with content blocks
- **Gemini**: Uses generationConfig object for parameters, systemInstruction for system prompt
- **DeepSeek**: Handles `reasoning_content` field for reasoning models
- **Perplexity**: Returns `citations`, `search_results`, `related_questions`. Supports search recency filter.
- **Grok**: Optional web search via `search` parameter. May return `search_results`.
- **Together AI**: Custom response parsing (raw array format for models endpoint)

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

### Request Data Classes with Parameters

All request classes support optional parameters:

```kotlin
// OpenAI-compatible format (ChatGPT, Grok, Groq, DeepSeek, Mistral, Perplexity, Together, OpenRouter)
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val response_format: OpenAiResponseFormat? = null
)

// Claude-specific format
data class ClaudeRequest(
    val model: String,
    val max_tokens: Int? = 1024,
    val messages: List<ClaudeMessage>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val system: String? = null,
    val stop_sequences: List<String>? = null
)

// Gemini-specific format
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)
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
├── New AI Report → Agent Selection → Progress → Results Dialog
│   └── Results: View, Export HTML, Share, toggle agent visibility
├── Prompt History → Click to reuse → New AI Report (pre-filled)
├── AI History → View/Share/Delete HTML reports
├── Settings → General / AI Setup / Developer
│   └── AI Setup → Service configs / Agents (with parameters) / Export
└── Help
```

## Settings & Persistence

### SharedPreferences Keys (`eval_prefs`)

```kotlin
// General settings
"pagination_page_size"    // Int (5-50, default 25)
"developer_mode"          // Boolean
"track_api_calls"         // Boolean

// Per-service AI settings (×10 services)
"ai_{service}_api_key"        // String
"ai_{service}_model"          // String
"ai_{service}_model_source"   // Enum: API or MANUAL
"ai_{service}_manual_models"  // JSON List<String>

// Agents (JSON with all parameters)
"ai_agents"               // JSON List<AiAgent>

// History
"prompt_history"          // JSON List<PromptHistoryEntry>
```

### Constants (SettingsPreferences)

```kotlin
const val MIN_PAGINATION_PAGE_SIZE = 5
const val MAX_PAGINATION_PAGE_SIZE = 50
const val DEFAULT_PAGINATION_PAGE_SIZE = 25
const val MAX_PROMPT_HISTORY = 100
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

### API Trace Format

```json
{
  "timestamp": 1672531200000,
  "hostname": "api.openai.com",
  "request": {
    "url": "https://api.openai.com/v1/chat/completions",
    "method": "POST",
    "headers": { "Authorization": "Bear****xyz" },
    "body": "{...}"
  },
  "response": {
    "statusCode": 200,
    "headers": {...},
    "body": "{...}"
  }
}
```

### Header Masking (Security)

Sensitive headers are masked (first 4 + last 4 chars shown):
- Authorization, x-api-key, x-goog-api-key, api-key, api_key, apikey
- bearer, token, secret, password, anthropic-api-key

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

### Adding a New Agent Parameter

1. Add to `AiParameter` enum in `AiSettingsModels.kt`
2. Add field to `AiAgentParameters` data class
3. Update `PROVIDER_SUPPORTED_PARAMETERS` for each provider that supports it
4. Add UI control in `AgentEditDialog` in `AiPromptsAgentsScreens.kt`
5. Update request data class in `AiAnalysisApi.kt`
6. Pass parameter in `analyzeWith*` method in `AiAnalysisRepository.kt`

### Adding a New Screen

1. Add route constant to `NavRoutes` in `Navigation.kt`
2. Add `composable()` block in `AiNavHost`
3. Create screen composable with `AiTitleBar`
4. Handle back navigation via `onBackClick`
5. Pass navigation callbacks from parent

### Modifying HTML Reports

Edit `convertGenericAiReportsToHtml()` in `AiScreens.kt`:
- CSS styles in `<style>` block
- HTML structure with agent divs
- JavaScript for toggle buttons
- Developer mode sections (usage, headers)

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

### How It Works

1. **MainActivity.kt** extracts `title` and `prompt` from incoming intent
2. **AiNavHost** receives these as `externalTitle` and `externalPrompt` parameters
3. If `externalPrompt` is provided, navigation starts at `AI_NEW_REPORT_WITH_PARAMS` route
4. User sees pre-filled New Report screen, selects agents, generates report

### Calling from Another App

```kotlin
val intent = Intent().apply {
    action = "com.ai.ACTION_NEW_REPORT"
    setPackage("com.ai")
    putExtra("title", "Analysis Report")
    putExtra("prompt", "Analyze this: ...")
}

// Check if AI app is installed
if (intent.resolveActivity(packageManager) != null) {
    startActivity(intent)
} else {
    // Handle AI app not installed
}
```

### Testing via ADB

```bash
adb shell am start -a com.ai.ACTION_NEW_REPORT \
    --es title "Test Report" \
    --es prompt "What is 2+2?"
```

## File Provider Configuration

For sharing files (HTML reports, trace exports):

**AndroidManifest.xml:**
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

**res/xml/file_paths.xml:**
```xml
<paths>
    <cache-path name="ai_analysis" path="ai_analysis/" />
    <cache-path name="shared_traces" path="shared_traces/" />
    <files-path name="ai_history" path="ai-history/" />
</paths>
```

## Testing Checklist

After making changes:
- [ ] Build succeeds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- [ ] App launches without crash
- [ ] AI Hub displays correctly as home page
- [ ] New AI Report flow works (prompt → agents → results)
- [ ] Agent parameters save and apply correctly
- [ ] Prompt History shows entries and allows reuse
- [ ] AI History lists reports, view/share/delete work
- [ ] Settings navigation works (General, AI Setup, Developer)
- [ ] AI service configuration saves correctly
- [ ] API tracing captures requests when enabled
- [ ] HTML reports render correctly in browser
- [ ] Export/import configuration works
- [ ] DUMMY provider hidden when not in developer mode

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

### Constants
- Magic numbers extracted to companion object constants
- Timeout values, retry delays, limits all named
- Page sizes: MIN=5, MAX=50, DEFAULT=25

### Logging Tags
```kotlin
android.util.Log.d("GeminiAPI", "Response code: ${response.code()}")
android.util.Log.w("AiAnalysis", "First attempt failed, retrying...")
android.util.Log.e("AiHistoryManager", "Failed to save: ${e.message}")
```
