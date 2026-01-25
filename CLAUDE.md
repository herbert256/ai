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

**AI** is an Android app for creating AI-powered reports using 10 different AI services. Users can configure multiple AI agents, submit custom prompts, and generate comparative reports from multiple AI providers simultaneously.

**Key Features:**
- Support for 10 AI services (ChatGPT, Claude, Gemini, Grok, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter)
- Three-tier architecture: Providers → Prompts → Agents
- Parallel multi-agent report generation
- Prompt history and report history
- Developer mode with API tracing
- HTML report export with share functionality

**Technical Stack:**
- **Language:** Kotlin
- **UI:** Jetpack Compose with Material 3
- **Architecture:** MVVM with StateFlow
- **Networking:** Retrofit 2 with OkHttp
- **Android SDK:** minSdk 26, targetSdk 34, compileSdk 34
- **Namespace:** `com.ai`

## Architecture

### Package Structure

```
com.ai/
├── MainActivity.kt                    # Entry point (33 lines)
├── data/                              # Data layer
│   ├── AiAnalysisApi.kt              # Retrofit interfaces for 10 AI services (485 lines)
│   ├── AiAnalysisRepository.kt       # API logic, retry handling, model fetching (1,043 lines)
│   ├── AiHistoryManager.kt           # HTML report storage (156 lines)
│   └── ApiTracer.kt                  # Debug API request/response logging (308 lines)
└── ui/                                # UI layer
    ├── AiViewModel.kt                # Central state management (316 lines)
    ├── AiModels.kt                   # Core domain models (55 lines)
    ├── AiScreens.kt                  # Main screens: Hub, NewReport, History (1,833 lines)
    ├── AiSettingsScreen.kt           # AI settings navigation (443 lines)
    ├── AiSettingsModels.kt           # AI settings data structures (192 lines)
    ├── AiSettingsComponents.kt       # Reusable AI settings UI (721 lines)
    ├── AiServiceSettingsScreens.kt   # Individual service config screens (644 lines)
    ├── AiPromptsAgentsScreens.kt     # Prompts and agents CRUD (557 lines)
    ├── AiSettingsExport.kt           # Configuration export/import (463 lines)
    ├── SettingsScreen.kt             # Settings hub (301 lines)
    ├── GeneralSettingsScreen.kt      # General app settings (99 lines)
    ├── DeveloperSettingsScreen.kt    # Developer debugging settings (118 lines)
    ├── HelpScreen.kt                 # In-app documentation (224 lines)
    ├── TraceScreen.kt                # API trace viewer (566 lines)
    ├── ColorPickerDialog.kt          # HSV color picker (254 lines)
    ├── SettingsPreferences.kt        # SharedPreferences persistence (294 lines)
    ├── SharedComponents.kt           # Reusable components: AiTitleBar (106 lines)
    ├── Navigation.kt                 # Jetpack Navigation routes (203 lines)
    └── theme/Theme.kt                # Material3 dark theme (32 lines)
```

**Total:** 23 Kotlin files, ~8,500 lines of code

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

// AI Agent configuration
data class AiAgent(
    val id: String,           // UUID
    val name: String,         // User-defined name
    val provider: AiService,  // Service to use
    val model: String,        // Model name
    val apiKey: String        // API key for this agent
)

// AI Analysis response (unified format for all services)
data class AiAnalysisResponse(
    val service: AiService,
    val analysis: String?,              // AI-generated text
    val error: String?,                 // Error message if failed
    val tokenUsage: TokenUsage?,        // Input/output token counts
    val agentName: String?,             // Agent name
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

2. **Repository Pattern**: `AiAnalysisRepository` handles all API interactions, retry logic, and response normalization

3. **Singleton Helpers**: `AiHistoryManager` and `ApiTracer` are singletons initialized in ViewModel

4. **Factory Pattern**: `AiApiFactory` creates and caches Retrofit instances per base URL using `ConcurrentHashMap`

5. **Thread Safety**:
   - `ConcurrentHashMap` for Retrofit cache
   - Synchronized access to `isTracingEnabled` flag
   - Coroutines with `Dispatchers.IO` for network calls

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
| OpenRouter | claude-3.5-sonnet | OpenAI-compatible | Bearer token |
| DUMMY | dummy-model | N/A | For testing |

### Service-Specific Features

- **ChatGPT**: Supports Chat Completions API (gpt-4o, etc.) and Responses API (gpt-5.x, o3, o4)
- **Claude**: Hardcoded model list (8 models), Anthropic-specific format
- **DeepSeek**: Handles `reasoning_content` field for reasoning models
- **Perplexity**: Returns `citations`, `search_results`, `related_questions`
- **Grok**: May return `search_results`
- **Together AI**: Custom response parsing (raw array format)

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
├── New AI Report → Agent Selection → Progress → Results Dialog
├── Prompt History → Click to reuse → New AI Report (pre-filled)
├── AI History → View/Share/Delete reports
├── Settings → General / AI Setup / Developer
│   └── AI Setup → Service configs / Agents / Prompts / Export
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

// Agents and prompts
"ai_agents"               // JSON List<AiAgent>
"ai_prompts"              // JSON List<AiPrompt>

// History
"prompt_history"          // JSON List<PromptHistoryEntry>
"ai_report_email"         // String (remembered email)

// Last report (for restoration)
"last_ai_report_title"    // String
"last_ai_report_prompt"   // String
```

### Constants (SettingsPreferences)

```kotlin
const val MIN_PAGINATION_PAGE_SIZE = 5
const val MAX_PAGINATION_PAGE_SIZE = 50
const val DEFAULT_PAGINATION_PAGE_SIZE = 25
const val MAX_PROMPT_HISTORY = 100
```

## API Tracing (Developer Feature)

### Enable Tracing
Settings → Developer → Track API calls (toggle)

### Storage
- Directory: `/files/trace/`
- Filename: `{hostname}_{yyyyMMdd_HHmmss_SSS}.json`
- Format:
```json
{
  "timestamp": 1672531200000,
  "hostname": "api.openai.com",
  "request": { "url": "...", "method": "POST", "headers": {...}, "body": "..." },
  "response": { "statusCode": 200, "headers": {...}, "body": "..." }
}
```

### Header Masking (Security)
Sensitive headers are masked (first 4 + last 4 chars shown):
- Authorization, x-api-key, x-goog-api-key, api-key, api_key, apikey
- bearer, token, secret, password, anthropic-api-key

## HTML Report Generation

### File Storage
- Directory: `/files/ai-history/`
- Filename: `ai_{yyyyMMdd_HHmmss}.html`

### Report Contents
- Title and timestamp
- Agent buttons (toggle visibility)
- AI responses with markdown rendering
- Citations, search results, related questions (if available)
- API usage and HTTP headers (developer mode only)
- Prompt text at bottom

### Sharing
- View in Chrome (via Intent)
- Share via email (FileProvider)

## Common Tasks

### Adding a New AI Service

1. Add enum value to `AiService` in `AiAnalysisApi.kt`
2. Create request/response data classes if format differs
3. Create Retrofit interface in `AiAnalysisApi.kt`
4. Add factory method in `AiApiFactory`
5. Add analysis method in `AiAnalysisRepository.kt`
6. Add settings fields to `AiSettings` in `AiSettingsModels.kt`
7. Add UI in `AiServiceSettingsScreens.kt`
8. Add SharedPreferences keys in `SettingsPreferences.kt`
9. Update load/save methods
10. Update export/import in `AiSettingsExport.kt`

### Adding a New Setting

1. Add field to data class in `AiModels.kt` or `AiSettingsModels.kt`
2. Add key constant in `SettingsPreferences.kt` companion object
3. Update load function in `SettingsPreferences.kt`
4. Update save function in `SettingsPreferences.kt`
5. Add UI control in appropriate settings screen
6. Connect to ViewModel state

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
</paths>
```

## Testing Checklist

After making changes:
- [ ] Build succeeds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- [ ] App launches without crash
- [ ] AI Hub displays correctly as home page
- [ ] New AI Report flow works (prompt → agents → results)
- [ ] Prompt History shows entries and allows reuse
- [ ] AI History lists reports, view/share/delete work
- [ ] Settings navigation works (General, AI Setup, Developer)
- [ ] AI service configuration saves correctly
- [ ] API tracing captures requests when enabled
- [ ] HTML reports render correctly in browser
- [ ] Export/import configuration works

## Code Quality Notes

### Error Handling
- All API calls use try-catch with retry logic
- Errors logged via `android.util.Log` (not `printStackTrace()`)
- Empty catch blocks include warning logs

### Thread Safety
- `ConcurrentHashMap` for Retrofit cache
- Synchronized `isTracingEnabled` flag
- Coroutines with appropriate dispatchers

### Null Safety
- Avoid `!!` operator - use `?.let`, `?:`, or safe calls
- Capture nullable values before lambdas

### Constants
- Magic numbers extracted to companion object constants
- Timeout values, retry delays, limits all named
