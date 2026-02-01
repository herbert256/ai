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

## Build & Deploy Commands

```bash
# Build debug APK (requires Java 17)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug

# Build release APK
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease

# Deploy to device
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
adb shell am start -n com.ai/.MainActivity

# Deploy to both device and cloud
adb install -r app/build/outputs/apk/debug/app-debug.apk && \
cp app/build/outputs/apk/debug/app-debug.apk /Users/herbert/cloud/ai.apk

# View logs
adb logcat | grep -E "(AiAnalysis|AiHistory|ApiTracer|PricingCache|AiReport)"
```

## Project Summary

Android app for AI-powered reports and chat using 31 AI services. Kotlin, Jetpack Compose, Material 3 dark theme. MVVM with StateFlow. Retrofit + OkHttp for networking. SSE streaming via Kotlin Flow. SharedPreferences + file storage for persistence. Namespace: `com.ai`, minSdk 26, targetSdk 34.

## Package Structure

```
com.ai/
├── MainActivity.kt                    # Entry point
├── data/
│   ├── AiAnalysisApi.kt              # Retrofit interfaces, AiService data class, request/response models
│   ├── AiAnalysisRepository.kt       # Repository facade
│   ├── AiAnalysisProviders.kt        # Analysis methods (1 unified + 3 format-specific)
│   ├── AiAnalysisStreaming.kt        # Streaming + SSE parsers
│   ├── AiAnalysisChat.kt            # Chat methods
│   ├── AiAnalysisModels.kt          # Model fetching
│   ├── AiReportStorage.kt            # Thread-safe report persistence (ReentrantLock)
│   ├── ChatHistoryManager.kt         # Chat session storage
│   ├── PricingCache.kt               # Six-tier pricing cache
│   └── ApiTracer.kt                  # API request/response logging
└── ui/
    ├── AiViewModel.kt                # Central state management
    ├── AiModels.kt                   # UI state classes
    ├── AiSettingsModels.kt           # AiSettings, ProviderConfig, AiAgent, AiFlock, AiSwarm, AiParameters
    ├── AiScreens.kt                  # Report screens + pricing utility
    ├── ChatScreens.kt                # Chat UI with streaming
    ├── AiContentDisplay.kt           # Response rendering with think sections
    ├── AiHistoryScreen.kt            # Report history
    ├── AiReportExport.kt             # HTML report generation
    ├── AiModelScreens.kt             # Model search/info
    ├── AiStatisticsScreen.kt         # Statistics and Costs
    ├── AiServiceSettingsScreens.kt   # Provider settings + defaultEndpointsForProvider()
    ├── AiSettingsScreen.kt           # AI settings hub
    ├── AiSettingsComponents.kt       # Reusable settings UI components
    ├── AiPromptsAgentsScreens.kt     # Agents CRUD
    ├── AiFlocksScreen.kt             # Flock CRUD
    ├── AiSwarmsScreen.kt             # Swarm CRUD
    ├── AiParametersScreen.kt         # Parameter presets
    ├── AiPromptsScreen.kt            # Prompts CRUD
    ├── PromptHistoryScreen.kt        # Prompt history
    ├── AiHousekeepingScreen.kt       # Housekeeping tools
    ├── AiDeveloperScreens.kt         # Developer mode screens
    ├── AiSettingsExport.kt           # Export/import configuration
    ├── SettingsScreen.kt             # Settings hub + two-tier navigation (SettingsSubScreen enum)
    ├── SettingsPreferences.kt        # SharedPreferences load/save
    ├── HelpScreen.kt                 # In-app docs
    ├── TraceScreen.kt                # API trace viewer
    ├── Navigation.kt                 # Jetpack Navigation routes
    ├── SharedComponents.kt           # AiTitleBar, common widgets
    └── theme/Theme.kt                # Material3 dark theme
```

## Key Architecture

- **AiService** is a data class with companion object registry. `AiService.entries` returns all providers. `AiService.findById(id)` for lookup.
- **ApiFormat** enum: `OPENAI_COMPATIBLE`, `ANTHROPIC`, `GOOGLE`. All dispatch uses `service.apiFormat`, never provider identity.
- **28 of 31 providers** are OpenAI-compatible sharing unified code paths. Only Anthropic and Google have unique implementations.
- **Two-tier navigation**: Main screens use Jetpack Navigation. Settings sub-screens use `SettingsSubScreen` enum with `when` inside `SettingsScreen`.
- **Full-screen overlay pattern**: `if (showOverlay) { OverlayScreen(...); return }` preserves parent `remember` state.
- **Agent inheritance**: Agents inherit API key, model, endpoint from provider when fields are empty. Use `getEffectiveModelForAgent()` / `getEffectiveApiKeyForAgent()`.
- **Parameter merging**: Multiple `AiParameters` presets merged via `reduce`; later non-null values win. Booleans are "sticky true".
- **AiSettings** uses `Map<AiService, ProviderConfig>` with accessor methods. `SettingsPreferences` iterates `AiService.entries` using `service.prefsKey`.

## Important Gotchas

- **SharedPreferences flock/swarm keys are historically swapped**: `"ai_flocks"` stores swarms, `"ai_swarms"` stores flocks. Selected IDs use `_v2` suffix keys.
- **Anthropic max_tokens**: Required (defaults to 4096), unlike OpenAI where it's optional.
- **Google auth**: Uses `?key=` query parameter, not Bearer token.
- **OpenAI dual API**: Chat Completions for gpt-4o etc., Responses API for gpt-5.x/o3/o4. Auto-routed via `usesResponsesApi()`.
- **Export version**: Currently v18. Import accepts 11-18.
- **Legacy chess resources**: App evolved from chess app "Eval". Unused chess assets remain (drawables, audio, eco_codes.json, ProGuard rules).

## Adding a New AI Service (OpenAI-compatible)

1. Add entry to `AiService` companion object in `AiAnalysisApi.kt`
2. Add hardcoded models in `AiSettingsModels.kt` (only if no model list API)
3. Add default endpoints in `defaultEndpointsForProvider()` in `AiServiceSettingsScreens.kt` (only if multiple endpoints)
4. Add OpenRouter prefix mapping in `PricingCache.kt` if applicable

For a non-OpenAI-compatible provider, also add: new `ApiFormat` value, Retrofit interface, format-specific methods in Providers/Streaming/Chat files, and SSE parser.

## Adding a New Agent Parameter

1. Add to `AiParameter` enum and `AiAgentParameters`/`AiParameters` data classes in `AiSettingsModels.kt`
2. Add UI in `AgentEditScreen` (`AiPromptsAgentsScreens.kt`) and `ParametersEditScreen` (`AiParametersScreen.kt`)
3. Update `AgentParametersExport` in `AiSettingsExport.kt`
4. Update request model in `AiAnalysisApi.kt`
5. Pass parameter in `AiAnalysisProviders.kt` and streaming methods
