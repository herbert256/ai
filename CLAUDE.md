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

### Android

```bash
# Build debug APK (requires Java 17)
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug

# Build release APK
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease

# Deploy to device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk && \
adb shell am start -n com.ai/.MainActivity

# Deploy to both device and cloud
adb install -r android/app/build/outputs/apk/debug/app-debug.apk && \
cp android/app/build/outputs/apk/debug/app-debug.apk /Users/herbert/cloud/ai.apk

# View logs
adb logcat | grep -E "(AiAnalysis|AiHistory|ApiTracer|PricingCache|AiReport)"
```

### macOS

```bash
# Generate Xcode project (after changing project.yml)
cd mac && xcodegen generate

# Build
xcodebuild -project mac/macAI.xcodeproj -scheme macAI -configuration Debug build
```

## Project Summary

Android app for AI-powered reports and chat using 31 AI services. Kotlin 2.2.10, Jetpack Compose (BOM 2026.01.01), Material 3 dark theme. MVVM with StateFlow. Retrofit + OkHttp for networking. SSE streaming via Kotlin Flow. SharedPreferences + file storage for persistence. Namespace: `com.ai`, minSdk 26, targetSdk 36. Build: AGP 9.0.1, Gradle 9.1.0, Java 17.

## Package Structure

Android source lives under `android/app/src/main/java/com/ai/`:

```
com.ai/
├── MainActivity.kt                    # Entry point
├── data/                              # Data layer (12 files)
│   ├── AnalysisApi.kt                # Retrofit interfaces, AppService data class, request/response models
│   ├── AnalysisRepository.kt         # Repository facade with retry logic
│   ├── AnalysisProviders.kt          # Analysis methods (1 unified + 3 format-specific)
│   ├── AnalysisStreaming.kt          # Streaming + SSE parsers
│   ├── AnalysisChat.kt              # Chat methods
│   ├── AnalysisModels.kt            # Model fetching + API connection testing
│   ├── DataModels.kt                # Chat/agent models (ChatMessage, ChatSession, etc.)
│   ├── ReportStorage.kt              # Thread-safe report persistence (ReentrantLock)
│   ├── ChatHistoryManager.kt         # Chat session storage
│   ├── PricingCache.kt               # Six-tier pricing cache
│   ├── ProviderRegistry.kt           # Provider definitions loaded from setup.json
│   └── ApiTracer.kt                  # API request/response logging
└── ui/                                # UI layer (34 files)
    ├── AppViewModel.kt               # Central state management
    ├── Models.kt                     # UI state classes
    ├── SettingsModels.kt             # Settings, ProviderConfig, Agent, Flock, Swarm, Parameters
    ├── ReportScreen.kt               # Report creation + progress
    ├── ReportSelectionDialogs.kt     # Agent/flock/swarm selection dialogs
    ├── ReportParametersScreen.kt     # Report parameter configuration
    ├── ChatScreens.kt                # Chat UI with streaming + history
    ├── DualChatScreen.kt             # Side-by-side dual chat
    ├── ContentDisplay.kt             # Response rendering with think sections
    ├── HistoryScreen.kt              # Report history
    ├── ReportExport.kt               # HTML report generation
    ├── ModelScreens.kt               # Model search/info
    ├── StatisticsScreen.kt           # Combined AI Usage (statistics + costs)
    ├── HubScreens.kt                 # Main hub screen
    ├── ServiceSettingsScreens.kt     # Provider settings + defaultEndpointsForProvider()
    ├── SetupScreens.kt               # AI settings hub
    ├── SettingsComponents.kt         # Reusable settings UI components
    ├── PromptsAgentsScreens.kt       # Agents CRUD
    ├── FlocksScreen.kt               # Flock CRUD
    ├── SwarmsScreen.kt               # Swarm CRUD
    ├── ParametersScreen.kt           # Parameter presets
    ├── PromptsScreen.kt              # Prompts CRUD
    ├── SystemPromptsScreen.kt        # System prompts management
    ├── PromptHistoryScreen.kt        # Prompt history
    ├── HousekeepingScreen.kt         # Housekeeping tools
    ├── DeveloperScreens.kt           # Developer mode screens
    ├── SettingsExport.kt             # Export/import configuration (v21)
    ├── SettingsScreen.kt             # Settings hub + two-tier navigation (SettingsSubScreen enum)
    ├── SettingsPreferences.kt        # SharedPreferences load/save
    ├── UiFormatting.kt               # Shared formatting utilities
    ├── HelpScreen.kt                 # In-app docs
    ├── TraceScreen.kt                # API trace viewer
    ├── Navigation.kt                 # Jetpack Navigation routes
    ├── SharedComponents.kt           # TitleBar, common widgets
    └── theme/Theme.kt                # Material3 dark theme
```

## Key Architecture

- **AppService** is a data class with companion object registry. `AppService.entries` returns all providers. `AppService.findById(id)` for lookup.
- **ApiFormat** enum: `OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. All dispatch uses `service.apiFormat`, never provider identity.
- **28 of 31 providers** are OpenAI-compatible sharing unified code paths. Only Anthropic and Google have unique implementations.
- **Two-tier navigation**: Main screens use Jetpack Navigation. Settings sub-screens use `SettingsSubScreen` enum with `when` inside `SettingsScreen`.
- **Full-screen overlay pattern**: `if (showOverlay) { OverlayScreen(...); return }` preserves parent `remember` state.
- **Agent inheritance**: Agents inherit API key, model, endpoint from provider when fields are empty. Use `getEffectiveModelForAgent()` / `getEffectiveApiKeyForAgent()`.
- **Parameter merging**: Multiple `Parameters` presets merged via `reduce`; later non-null values win. Booleans are "sticky true".
- **Settings** uses `Map<AppService, ProviderConfig>` with accessor methods. `SettingsPreferences` iterates `AppService.entries` using `service.prefsKey`.

## Important Gotchas

- **SharedPreferences flock/swarm keys are historically swapped**: `"ai_flocks"` stores swarms, `"ai_swarms"` stores flocks. Selected IDs use `_v2` suffix keys.
- **Anthropic max_tokens**: Required (defaults to 4096), unlike OpenAI where it's optional.
- **Google auth**: Uses `?key=` query parameter, not Bearer token.
- **OpenAI dual API**: Chat Completions for gpt-4o etc., Responses API for gpt-5.x/o3/o4. Auto-routed via `usesResponsesApi()`.
- **Export version**: Currently v21. Import accepts 11-21.
- **First-run setup**: `assets/setup.json` uses `ConfigExport` format, loaded via `ProviderRegistry` and `importAiConfigFromAsset()`. Can include providers, agents, parameters, etc.

## Adding a New AI Service (OpenAI-compatible)

1. Add entry to `AppService` companion object in `AnalysisApi.kt`
2. Add to `ProviderRegistry` in `ProviderRegistry.kt` or include in `assets/setup.json`
3. Add hardcoded models in `SettingsModels.kt` (only if no model list API)
4. Add default endpoints in `defaultEndpointsForProvider()` in `ServiceSettingsScreens.kt` (only if multiple endpoints)
5. Add OpenRouter prefix mapping in `PricingCache.kt` if applicable

For a non-OpenAI-compatible provider, also add: new `ApiFormat` value, Retrofit interface, format-specific methods in Providers/Streaming/Chat files, and SSE parser.

## Adding a New Agent Parameter

1. Add to `Parameter` enum and `AgentParameters`/`Parameters` data classes in `SettingsModels.kt`
2. Add UI in `AgentEditScreen` (`PromptsAgentsScreens.kt`) and `ParametersEditScreen` (`ParametersScreen.kt`)
3. Update `AgentParametersExport` in `SettingsExport.kt`
4. Update request model in `AnalysisApi.kt`
5. Pass parameter in `AnalysisProviders.kt` and streaming methods
