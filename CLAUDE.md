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

# Deploy to device and cloud folder
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
adb shell am start -n com.eval/.MainActivity && \
cp app/build/outputs/apk/debug/app-debug.apk /Users/herbert/cloud/
```

## Project Overview

AI is an Android app for creating AI-powered reports using 9 different AI services (ChatGPT, Claude, Gemini, Grok, Groq, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter). The app provides a three-tier architecture (Providers → Prompts → Agents) for flexible AI configuration and report generation.

**Key Dependencies:**
- Android SDK: minSdk 26, targetSdk 34, compileSdk 34
- Kotlin with Jetpack Compose for UI
- Retrofit for networking (AI service APIs)

## Architecture

### Package Structure (24 Kotlin files)

```
com.eval/
├── MainActivity.kt - Entry point, sets up Compose theme
├── data/
│   ├── AiAnalysisApi.kt - Retrofit interfaces for 9 AI services + DUMMY
│   ├── AiAnalysisRepository.kt - AI analysis with model fetching
│   ├── AiHistoryManager.kt - HTML report storage and history management
│   └── ApiTracer.kt - API request/response tracing and storage
└── ui/
    ├── AiViewModel.kt - Central state management for AI features
    ├── AiModels.kt - Data classes and enums (core domain models)
    ├── AiScreens.kt - AI hub, history, new report, prompt history screens
    ├── AiSettingsScreen.kt - AI service settings, three-tier architecture
    ├── AiSettingsModels.kt - AI settings data structures
    ├── AiSettingsComponents.kt - Reusable AI settings UI components
    ├── AiServiceSettingsScreens.kt - Individual AI service configuration
    ├── AiPromptsAgentsScreens.kt - Prompts and agents management
    ├── AiSettingsExport.kt - AI configuration export/import
    ├── SettingsScreen.kt - Settings navigation hub
    ├── GeneralSettingsScreen.kt - General app settings
    ├── DeveloperSettingsScreen.kt - Developer debugging settings
    ├── HelpScreen.kt - In-app help documentation
    ├── TraceScreen.kt - API trace log viewer and detail screens
    ├── ColorPickerDialog.kt - HSV color picker for colors
    ├── SettingsPreferences.kt - SharedPreferences persistence layer
    ├── SharedComponents.kt - Reusable Compose components (AiTitleBar, etc.)
    ├── Navigation.kt - Jetpack Navigation routes and composables
    └── theme/Theme.kt - Material3 dark theme
```

### Key Data Classes

```kotlin
// AI Services (9 services + DUMMY for testing)
enum class AiService(displayName, baseUrl) {
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
    DUMMY("Dummy", "")  // For testing
}

// Three-tier AI Architecture
data class AiPrompt(
    val id: String,      // UUID
    val name: String,    // User-defined name
    val text: String     // Template with @DATE@ placeholder
)

data class AiAgent(
    val id: String,
    val name: String,
    val provider: AiService,
    val model: String,
    val apiKey: String,
    val promptId: String
)

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

// General settings
data class GeneralSettings(
    val paginationPageSize: Int = 25,
    val trackApiCalls: Boolean = false
)

// UI State
data class AiUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val generalSettings: GeneralSettings = GeneralSettings(),
    val aiSettings: AiSettings = AiSettings(),
    // Model availability for each provider
    val availableChatGptModels: List<String> = emptyList(),
    val availableGeminiModels: List<String> = emptyList(),
    val availableGrokModels: List<String> = emptyList(),
    val availableGroqModels: List<String> = emptyList(),
    val availableDeepSeekModels: List<String> = emptyList(),
    val availableMistralModels: List<String> = emptyList(),
    val availablePerplexityModels: List<String> = emptyList(),
    val availableTogetherModels: List<String> = emptyList(),
    val availableOpenRouterModels: List<String> = emptyList(),
    // Generic AI Reports
    val showGenericAiAgentSelection: Boolean = false,
    val showGenericAiReportsDialog: Boolean = false,
    val genericAiPromptTitle: String = "",
    val genericAiPromptText: String = "",
    val genericAiReportsProgress: Int = 0,
    val genericAiReportsTotal: Int = 0,
    val genericAiReportsSelectedAgents: Set<String> = emptySet(),
    val genericAiReportsAgentResults: Map<String, AiAnalysisResponse> = emptyMap()
)
```

### Key Design Patterns

1. **MVVM with Jetpack Compose**: `AiViewModel` exposes `StateFlow<AiUiState>`, UI recomposes reactively

2. **Three-Tier AI Architecture**:
   - Providers: AI service definitions (9 services) with model source settings
   - Prompts: Reusable prompt templates with placeholders
   - Agents: User-configured combinations (provider + model + API key + prompt ref)

3. **Result Type Pattern**: `sealed class Result<T> { Success, Error }` for API responses

4. **Jetpack Navigation**: Type-safe navigation with `NavHost` and route definitions

## AI Services

### Supported Services (9 + DUMMY)
| Service | Default Model | API Endpoint | Auth Method |
|---------|--------------|--------------|-------------|
| ChatGPT | gpt-4o-mini | `api.openai.com/v1/chat/completions` or `/v1/responses` | Bearer token |
| Claude | claude-sonnet-4-20250514 | `api.anthropic.com/v1/messages` | x-api-key header |
| Gemini | gemini-2.0-flash | `generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` | Query param |
| Grok | grok-3-mini | `api.x.ai/v1/chat/completions` | Bearer token |
| Groq | llama-3.3-70b-versatile | `api.groq.com/openai/v1/chat/completions` | Bearer token |
| DeepSeek | deepseek-chat | `api.deepseek.com/chat/completions` | Bearer token |
| Mistral | mistral-small-latest | `api.mistral.ai/v1/chat/completions` | Bearer token |
| Perplexity | sonar | `api.perplexity.ai/chat/completions` | Bearer token |
| Together | meta-llama/Llama-3.3-70B-Instruct-Turbo | `api.together.xyz/v1/chat/completions` | Bearer token |
| OpenRouter | anthropic/claude-3.5-sonnet | `openrouter.ai/api/v1/chat/completions` | Bearer token |
| DUMMY | dummy-model | N/A | For testing |

**Special Implementations:**
- **OpenAI**: Supports both Chat Completions API (gpt-4o, etc.) and Responses API (gpt-5.x, o3, o4)
- **DeepSeek**: Handles `reasoning_content` field for o1-style reasoning models
- **Together AI**: Custom response parsing (raw array vs wrapped `{data: [...]}`)

### Features
- **Three-tier Architecture**: Providers → Prompts → Agents for flexible configuration
- **Prompt Placeholders**: @DATE@ for dynamic content
- **Model Sources**: API (fetch dynamically) or Manual (user-maintained list)
- **AI Hub Screen**: Access to New AI Report, Prompt History, AI History
- **Prompt History**: Saves submitted prompts for reuse
- **AI History**: Stores generated HTML reports
- **Custom Prompts**: Template with placeholders
- **Dynamic Models**: Fetches available models from each service
- **View in Chrome**: Opens HTML report
- **Send by Email**: Emails HTML report as attachment (remembers email address)
- **Retry Logic**: Automatic retry with 500ms delay on API failures
- **Export/Import**: JSON export of providers, prompts, agents via share sheet

## Navigation System

### Routes (Navigation.kt)
```
ai                             - AI hub screen (home page)
ai_history                     - View previously generated reports
ai_new_report                  - Create custom AI analysis
ai_new_report/{title}/{prompt} - New AI report with pre-filled values
ai_prompt_history              - View and reuse previous prompts
settings                       - Settings hub
help                           - In-app documentation
trace_list                     - API trace log viewer
trace_detail/{filename}        - Trace detail viewer
```

### Screen Composables
- All screens use `AiTitleBar` for consistent header
- Back navigation handled via `NavController.popBackStack()`
- Parameter passing via URL-encoded route arguments

## UI Components

### AI Hub (Home Screen)
- **New AI Report**: Create custom AI reports with any prompt
- **Prompt History**: Reuse previously submitted prompts
- **AI History**: View previously generated reports

### Title Bar Icons
- **⚙**: Settings
- **?**: Help screen
- **🐛**: API trace viewer (when tracking enabled)

### Settings Structure
```
Settings (main menu)
├── General
│   └── Pagination page size (5-50)
├── AI Setup (three-tier architecture)
│   ├── AI Providers (model source + models per provider)
│   ├── AI Prompts (CRUD - name + template with placeholders)
│   ├── AI Agents (CRUD - provider + model + API key + prompt ref)
│   └── Export/Import configuration
└── Developer
    └── Track API calls (toggle) - API debugging
```

## API Tracing (Developer Feature)

When "Track API calls" is enabled in Developer Settings:
- All API requests (AI services) are logged
- Trace files stored in app's internal storage under "trace" directory
- Filename format: `<hostname>_<timestamp>.json`
- Debug icon (bug emoji) appears in top bar to access trace viewer

### Trace Viewer Features
- List view with pagination (25 per page)
- Columns: Hostname, Date/Time, HTTP Status Code
- Status code color coding (green=2xx, orange=4xx, red=5xx)
- Detail view with pretty-printed JSON
- "Show POST data" and "Show RESPONSE data" buttons (when available)
- "Clear trace container" button

**Note**: Traces are automatically cleared when tracking is disabled.

## Export Features

### AI Reports Export
- HTML report with AI analysis from multiple agents
- View in Chrome or send via email
- Footer: "Generated by AI <version>" with timestamp

### AI Configuration Export
- JSON format (version 3) with providers, prompts, agents
- Export via Android share sheet as .json file
- Import from clipboard

## Settings Persistence

All settings managed via `SettingsPreferences` class with SharedPreferences (`eval_prefs`):

```
// AI settings (9 services)
ai_report_email
ai_{service}_api_key, ai_{service}_model
ai_{service}_model_source, ai_{service}_manual_models

// AI three-tier architecture
ai_prompts (JSON list), ai_agents (JSON list), ai_migration_done

// Prompt history
prompt_history (JSON list of PromptHistoryEntry)

// General
general_pagination_page_size, track_api_calls

// Last AI report
last_ai_report_title, last_ai_report_prompt
```

## Common Tasks

### Adding a New Setting
1. Add field to appropriate settings data class in `AiModels.kt`
2. Add SharedPreferences key constant in `SettingsPreferences.kt` companion object
3. Update corresponding load function in `SettingsPreferences.kt`
4. Update corresponding save function in `SettingsPreferences.kt`
5. Add UI control in appropriate settings screen
6. Use setting value in relevant code

### Adding a New AI Service
1. Add enum value to `AiService` in `AiAnalysisApi.kt`
2. Create request/response data classes if format differs
3. Create Retrofit interface for the service in `AiAnalysisApi.kt`
4. Add factory method in `AiApiFactory`
5. Add analysis method in `AiAnalysisRepository.kt`
6. Add settings fields to `AiSettings` in `AiSettingsModels.kt`
7. Add UI in `AiServiceSettingsScreens.kt` (navigation card + settings screen)
8. Add SharedPreferences keys in `SettingsPreferences.kt`
9. Update load/save methods for AI settings
10. Update AI export/import if needed

### Adding a New Navigation Route
1. Add route constant to `NavRoutes` in `Navigation.kt`
2. Add helper function for parameterized routes if needed
3. Add `composable()` block in `AiNavHost`
4. Create or update screen composable
5. Pass navigation callbacks from parent screens

### Modifying HTML Report (View in Chrome)
1. Update `convertGenericAiReportsToHtml()` in `AiScreens.kt`
2. Footer shows "Generated by AI <version>" with timestamp

## File Provider Configuration

For sharing HTML reports via email and AI config export, the app uses FileProvider configured in:
- `AndroidManifest.xml` - Provider declaration
- `res/xml/file_paths.xml` - Cache directory paths (`ai_analysis/` subdirectory)

## Testing Checklist

After making changes, verify:
- [ ] App builds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- [ ] App installs and launches without crash
- [ ] AI Hub opens as home page
- [ ] New AI Report works
- [ ] Prompt History works
- [ ] AI History works
- [ ] AI analysis works (if API keys configured)
- [ ] AI Agents with three-tier architecture work
- [ ] AI Reports with multiple agents work
- [ ] Settings shows only: General, AI Setup, Developer
- [ ] Settings changes persist
- [ ] API Trace log works (when enabled)
- [ ] Export features work (HTML reports, AI Config)
