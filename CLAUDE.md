# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Session Start

At the start of each session, check if an emulator is running and start one if needed:

```bash
# Check for connected devices/emulators
adb devices | grep -E "emulator|device$"

# If none connected, start the emulator
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_36.1 -no-snapshot-load &
adb wait-for-device
```

## Commit Rules

When I say "commit", always commit **all** current changes (all modified and untracked files), not just changes from the most recent prompt. After a successful commit, always build and deploy to both targets (device and cloud).

## Build & Deploy Commands

```bash
# Build debug APK (requires Java 17)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug

# Build release APK
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease

# Deploy to device
adb install -r ai/build/outputs/apk/debug/ai-debug.apk && \
adb shell am start -n com.ai/.MainActivity

# Deploy to both device and cloud
adb install -r ai/build/outputs/apk/debug/ai-debug.apk && \
cp ai/build/outputs/apk/debug/ai-debug.apk /Users/herbert/cloud/ai.apk

# View logs
adb logcat | grep -E "(AiAnalysis|AiHistory|ApiTracer|PricingCache|AiReport)"
```

## Project Summary

Android AI-powered reports and chat using 38 AI services. Kotlin 2.2.10, Jetpack Compose (BOM 2026.01.01), Material 3 dark theme. MVVM with StateFlow and three ViewModels. Retrofit + OkHttp for networking. SSE streaming via Kotlin Flow. SharedPreferences + file storage for persistence. Namespace: `com.ai`, minSdk 26, targetSdk 36. Build: AGP 9.0.1, Gradle 9.1.0, Java 17. ~22,200 lines across 76 Kotlin files.

## Package Structure

Source lives under `ai/src/main/java/com/ai/`:

```
com.ai/
├── MainActivity.kt                    # Entry point
├── data/                              # Data layer (14 files)
│   ├── AppService.kt                 # Provider registry, AppService data class
│   ├── ApiModels.kt                  # Request/response models
│   ├── ApiClient.kt                  # Retrofit interfaces, ApiFactory
│   ├── ApiFormat.kt                  # ApiFormat enum (OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE)
│   ├── ApiDispatch.kt               # Format dispatch, test methods
│   ├── AnalysisRepository.kt        # Repository facade with retry logic
│   ├── ApiStreaming.kt              # SSE streaming + parsers
│   ├── DataModels.kt               # Chat/agent models (ChatMessage, ChatSession, etc.)
│   ├── ReportStorage.kt             # Thread-safe report persistence (ReentrantLock)
│   ├── ChatHistoryManager.kt        # Chat session storage
│   ├── PricingCache.kt              # Six-tier pricing cache
│   ├── ProviderRegistry.kt          # Provider definitions loaded from setup.json
│   └── ApiTracer.kt                 # API request/response logging
├── model/                             # Data models (2 files)
│   ├── SettingsModels.kt            # Settings, ProviderConfig, Agent, Flock, Swarm, Parameters
│   └── ExportModels.kt             # Export/import data classes
├── viewmodel/                         # ViewModels (3 files)
│   ├── AppViewModel.kt              # Central state management
│   ├── ChatViewModel.kt             # Chat state and operations
│   └── ReportViewModel.kt           # Report generation state
└── ui/                                # UI layer (37 files)
    ├── navigation/
    │   ├── AppNavHost.kt            # Jetpack Navigation routes
    │   └── NavRoutes.kt             # Route constants
    ├── hub/
    │   └── HubScreens.kt           # Main hub screen
    ├── report/
    │   ├── ReportScreen.kt          # Report creation + progress
    │   ├── ReportSelectionDialogs.kt # Agent/flock/swarm selection
    │   ├── ReportParametersScreen.kt # Report parameter configuration
    │   ├── ContentDisplay.kt        # Response rendering with think sections
    │   └── ReportExport.kt          # HTML report generation
    ├── chat/
    │   ├── ChatScreens.kt          # Chat UI with streaming
    │   ├── ChatHub.kt              # Chat entry point
    │   ├── ChatHistory.kt          # Chat history browser
    │   └── DualChatScreen.kt       # Side-by-side dual chat
    ├── models/
    │   └── ModelScreens.kt         # Model search/info
    ├── history/
    │   ├── HistoryScreen.kt        # Report history
    │   └── PromptHistoryScreen.kt  # Prompt history
    ├── settings/
    │   ├── SettingsScreen.kt       # Settings hub + two-tier navigation (SettingsSubScreen enum)
    │   ├── SetupScreens.kt         # AI settings hub
    │   ├── ServiceSettingsScreens.kt # Provider settings + defaultEndpointsForProvider()
    │   ├── AgentsScreen.kt         # Agents CRUD
    │   ├── FlocksScreen.kt         # Flock CRUD
    │   ├── SwarmsScreen.kt         # Swarm CRUD
    │   ├── ParametersScreen.kt     # Parameter presets
    │   ├── PromptsScreen.kt        # Prompts CRUD
    │   ├── SystemPromptsScreen.kt  # System prompts management
    │   ├── SettingsExport.kt       # Export/import configuration (v21)
    │   └── SettingsPreferences.kt  # SharedPreferences load/save
    ├── admin/
    │   ├── StatisticsScreen.kt     # AI Usage (statistics + costs)
    │   ├── HousekeepingScreen.kt   # Housekeeping tools
    │   ├── DeveloperScreens.kt     # Developer mode screens
    │   ├── TraceScreen.kt          # API trace viewer
    │   └── HelpScreen.kt           # In-app docs
    ├── shared/
    │   ├── SharedComponents.kt     # TitleBar, common widgets
    │   ├── CrudListScreen.kt       # Generic CRUD list composable
    │   ├── SelectionScreens.kt     # Reusable selection screens
    │   ├── AppColors.kt            # Color definitions
    │   └── UiFormatting.kt         # Shared formatting utilities
    └── theme/
        └── Theme.kt                # Material3 dark theme
```

## Key Architecture

- **AppService** represents a provider. `AppService.entries` returns all providers. `AppService.findById(id)` for lookup.
- **ApiFormat** enum: `OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. All dispatch uses `service.apiFormat`, never provider identity.
- **36 of 38 providers** are OpenAI-compatible sharing unified code paths. Only Anthropic and Google have unique implementations.
- **Three ViewModels**: `AppViewModel` (settings, state), `ChatViewModel` (chat), `ReportViewModel` (report generation).
- **Generic CrudListScreen<T>**: Reusable composable for all CRUD screens (agents, flocks, swarms, parameters, prompts).
- **Two-tier navigation**: Main screens use Jetpack Navigation. Settings sub-screens use `SettingsSubScreen` enum with `when` inside `SettingsScreen`.
- **Full-screen overlay pattern**: `if (showOverlay) { OverlayScreen(...); return }` preserves parent `remember` state.
- **Agent inheritance**: Agents inherit API key, model, endpoint from provider when fields are empty.
- **Parameter merging**: Multiple parameter presets merged; later non-null values win. Booleans are "sticky true".
- **Settings** uses `Map<AppService/String, ProviderConfig>` with accessor methods.
- **Configuration export/import**: JSON format (v21). Import accepts 11-21.
- **First-run setup**: `setup.json` in assets, loaded via `ProviderRegistry`.

## Important Gotchas

- **Anthropic max_tokens**: Required (defaults to 4096), unlike OpenAI where it's optional.
- **Google auth**: Uses `?key=` query parameter, not Bearer token.
- **OpenAI dual API**: Chat Completions for gpt-4o etc., Responses API for gpt-5.x/o3/o4. Auto-routed via `usesResponsesApi()` / endpoint rules.
- **Export version**: Currently v21. Import accepts 11-21.

## Adding a New AI Service (OpenAI-compatible)

1. Add entry to `AppService` companion object in `AppService.kt`
2. Add to `ProviderRegistry` in `ProviderRegistry.kt` or include in `assets/setup.json`
3. Add hardcoded models in `SettingsModels.kt` (only if no model list API)
4. Add default endpoints in `defaultEndpointsForProvider()` in `ServiceSettingsScreens.kt` (only if multiple endpoints)
5. Add OpenRouter prefix mapping in `PricingCache.kt` if applicable

For a non-OpenAI-compatible provider, also add: new `ApiFormat` value, format-specific methods in `ApiDispatch.kt` and `ApiStreaming.kt`, and SSE parser.

## Adding a New Agent Parameter

1. Add to `Parameter` enum and `AgentParameters`/`Parameters` data classes in `SettingsModels.kt`
2. Add UI in `AgentEditScreen` (`AgentsScreen.kt`) and `ParametersEditScreen` (`ParametersScreen.kt`)
3. Update `AgentParametersExport` in `ExportModels.kt`
4. Update request model in `ApiModels.kt`
5. Pass parameter in `ApiDispatch.kt` and streaming methods
