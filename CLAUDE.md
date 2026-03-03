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
cd macOS && xcodegen generate

# Build
xcodebuild -project macOS/macAI.xcodeproj -scheme macAI -configuration Debug build

# Run
open macOS/build/Build/Products/Debug/macAI.app
```

## Project Summary

Multi-platform AI-powered reports and chat using 31 AI services. Two native apps sharing the same architecture, data formats, and provider ecosystem.

### Android
Kotlin 2.2.10, Jetpack Compose (BOM 2026.01.01), Material 3 dark theme. MVVM with StateFlow. Retrofit + OkHttp for networking. SSE streaming via Kotlin Flow. SharedPreferences + file storage for persistence. Namespace: `com.ai`, minSdk 26, targetSdk 36. Build: AGP 9.0.1, Gradle 9.1.0, Java 17. ~28,700 lines across 47 Kotlin files.

### macOS
Swift 5.9, SwiftUI with NavigationSplitView, dark theme. MVVM with `@Observable`. URLSession for networking. SSE streaming via AsyncSequence. UserDefaults + file storage for persistence. Bundle ID: `com.ai.macAI`, macOS 14.0+. Build: XcodeGen + Xcode 16.0. ~11,800 lines across 49 Swift files.

## Package Structure

### Android

Source lives under `android/app/src/main/java/com/ai/`:

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

### macOS

Source lives under `macOS/ai/`:

```
ai/
├── macAIApp.swift                     # App entry point (@main)
├── Data/                              # Data layer (14 files)
│   ├── AppService.swift              # Provider data class + ProviderDefinition
│   ├── ApiModels.swift               # Request/response models
│   ├── ApiClient.swift               # URLSession-based HTTP client
│   ├── AnalysisRepository.swift      # Repository facade
│   ├── AnalysisProviders.swift       # Analysis methods (format-specific dispatch)
│   ├── AnalysisStreaming.swift       # SSE streaming parsers
│   ├── AnalysisChat.swift            # Chat methods
│   ├── AnalysisModels.swift          # Model fetching + API testing
│   ├── DataModels.swift              # Chat/agent models
│   ├── ReportStorage.swift           # Report persistence
│   ├── ChatHistoryManager.swift      # Chat session storage
│   ├── PricingCache.swift            # Six-tier pricing cache
│   ├── ProviderRegistry.swift        # Provider definitions (actor, loaded from setup.json)
│   └── ApiTracer.swift               # API request/response logging (actor)
└── UI/                                # UI layer (35 files)
    ├── AppViewModel.swift            # Central state (@Observable)
    ├── Models.swift                  # UiState, GeneralSettings, SidebarSection/Group
    ├── SettingsModels.swift          # Settings, ProviderConfig, Agent, Flock, Swarm, Parameters
    ├── ContentView.swift             # Root NavigationSplitView + sidebar
    ├── Reports/
    │   ├── ReportScreen.swift        # Report creation + progress
    │   ├── ReportSelectionDialogs.swift
    │   ├── ReportParametersScreen.swift
    │   ├── ContentDisplay.swift      # Response rendering
    │   └── ReportExport.swift        # HTML report generation
    ├── Chat/
    │   ├── ChatScreens.swift         # Chat UI with streaming
    │   └── DualChatScreen.swift      # Side-by-side dual chat
    ├── Models/
    │   └── ModelScreens.swift        # Model search/info
    ├── History/
    │   ├── HistoryScreen.swift       # Report history
    │   └── PromptHistoryScreen.swift
    ├── Settings/
    │   ├── SettingsView.swift        # Settings hub
    │   ├── SetupView.swift           # AI setup hub
    │   ├── ServiceSettingsView.swift  # Provider settings
    │   ├── AgentsView.swift          # Agents CRUD
    │   ├── FlocksView.swift          # Flock CRUD
    │   ├── SwarmsView.swift          # Swarm CRUD
    │   ├── ParametersView.swift      # Parameter presets
    │   ├── PromptsView.swift         # Prompts CRUD
    │   ├── SystemPromptsView.swift   # System prompts
    │   ├── EndpointsView.swift       # Endpoint management
    │   ├── SettingsExport.swift      # Export/import configuration
    │   └── SettingsPreferences.swift # UserDefaults load/save
    ├── Admin/
    │   ├── StatisticsView.swift      # AI Usage (statistics + costs)
    │   ├── HousekeepingView.swift    # Housekeeping tools
    │   ├── DeveloperView.swift       # Developer mode
    │   ├── TraceView.swift           # API trace viewer
    │   └── HelpView.swift            # In-app docs
    └── Shared/
        ├── AppColors.swift           # Color definitions
        ├── SharedComponents.swift    # Reusable components
        └── UiFormatting.swift        # Shared formatting utilities
```

## Key Architecture

### Shared Concepts (both platforms)

- **AppService** represents a provider. `AppService.entries` returns all providers. `AppService.findById(id)` for lookup.
- **ApiFormat** enum: `OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. All dispatch uses `service.apiFormat`, never provider identity.
- **28 of 31 providers** are OpenAI-compatible sharing unified code paths. Only Anthropic and Google have unique implementations.
- **Agent inheritance**: Agents inherit API key, model, endpoint from provider when fields are empty.
- **Parameter merging**: Multiple parameter presets merged; later non-null values win. Booleans are "sticky true".
- **Settings** uses `Map<AppService/String, ProviderConfig>` with accessor methods.
- **Configuration export/import**: Shared JSON format (v21). Import accepts 11-21.
- **First-run setup**: `setup.json` in assets/resources, loaded via `ProviderRegistry`.

### Android-Specific

- **AppService** is a data class with companion object registry in `AnalysisApi.kt`.
- **Two-tier navigation**: Main screens use Jetpack Navigation. Settings sub-screens use `SettingsSubScreen` enum with `when` inside `SettingsScreen`.
- **Full-screen overlay pattern**: `if (showOverlay) { OverlayScreen(...); return }` preserves parent `remember` state.
- **SettingsPreferences** iterates `AppService.entries` using `service.prefsKey` for SharedPreferences.
- **External Intent**: `com.ai.ACTION_NEW_REPORT` for integration with other Android apps (see `android/CALL_AI.md`).

### macOS-Specific

- **AppService** is a `final class` with `@Observable`-compatible patterns in `AppService.swift`.
- **NavigationSplitView** sidebar with `SidebarSection` enum (15 sections in 5 groups: Main, Reports, Chat, Tools, Admin).
- **ProviderRegistry** and **ApiTracer** are Swift `actor` types for thread safety.
- **SettingsPreferences** uses `UserDefaults` with the same key conventions as Android.
- **XcodeGen**: Project generated from `macOS/project.yml`; regenerate after changes.

## Important Gotchas

- **SharedPreferences/UserDefaults flock/swarm keys are historically swapped**: `"ai_flocks"` stores swarms, `"ai_swarms"` stores flocks. This applies to both platforms.
- **Anthropic max_tokens**: Required (defaults to 4096), unlike OpenAI where it's optional.
- **Google auth**: Uses `?key=` query parameter, not Bearer token.
- **OpenAI dual API**: Chat Completions for gpt-4o etc., Responses API for gpt-5.x/o3/o4. Auto-routed via `usesResponsesApi()` / endpoint rules.
- **Export version**: Currently v21. Import accepts 11-21.

## Adding a New AI Service (OpenAI-compatible)

### Android
1. Add entry to `AppService` companion object in `AnalysisApi.kt`
2. Add to `ProviderRegistry` in `ProviderRegistry.kt` or include in `assets/setup.json`
3. Add hardcoded models in `SettingsModels.kt` (only if no model list API)
4. Add default endpoints in `defaultEndpointsForProvider()` in `ServiceSettingsScreens.kt` (only if multiple endpoints)
5. Add OpenRouter prefix mapping in `PricingCache.kt` if applicable

For a non-OpenAI-compatible provider, also add: new `ApiFormat` value, Retrofit interface, format-specific methods in Providers/Streaming/Chat files, and SSE parser.

### macOS
1. Add to `ProviderRegistry` in `ProviderRegistry.swift` or include in `Resources/setup.json`
2. Add hardcoded models in `SettingsModels.swift` (only if no model list API)
3. Add default endpoints in `ServiceSettingsView.swift` (only if multiple endpoints)
4. Add OpenRouter prefix mapping in `PricingCache.swift` if applicable

For a non-OpenAI-compatible provider, also add: new `ApiFormat` case, format-specific methods in Providers/Streaming/Chat files, and SSE parser.

## Adding a New Agent Parameter

### Android
1. Add to `Parameter` enum and `AgentParameters`/`Parameters` data classes in `SettingsModels.kt`
2. Add UI in `AgentEditScreen` (`PromptsAgentsScreens.kt`) and `ParametersEditScreen` (`ParametersScreen.kt`)
3. Update `AgentParametersExport` in `SettingsExport.kt`
4. Update request model in `AnalysisApi.kt`
5. Pass parameter in `AnalysisProviders.kt` and streaming methods

### macOS
1. Add to parameter types in `SettingsModels.swift`
2. Add UI in `AgentsView.swift` and `ParametersView.swift`
3. Update export in `SettingsExport.swift`
4. Update request model in `ApiModels.swift`
5. Pass parameter in `AnalysisProviders.swift` and streaming methods
