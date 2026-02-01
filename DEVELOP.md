# AI - Developer Guide

## Technical Details

- **Language**: Kotlin 1.9.22
- **UI**: Jetpack Compose with Material 3 (dark theme, black background)
- **Architecture**: MVVM with StateFlow, Repository pattern
- **Networking**: Retrofit 2.9.0 with OkHttp 4.12.0, Gson serialization
- **Streaming**: Server-Sent Events (SSE) with Kotlin Flow (3 format-specific parsers)
- **Persistence**: SharedPreferences (JSON) + file-based storage
- **Min SDK**: 26 (Android 8.0), Target SDK: 34 (Android 14)
- **Build tools**: Gradle 8.5, AGP 8.2.2, Java 17
- **Codebase**: ~25,800 lines across 40 Kotlin files
- **Permissions**: Only INTERNET
- **Provider architecture**: 28 OpenAI-compatible providers share unified code paths; only Anthropic and Google Gemini have unique implementations

## Building

### Prerequisites
- Android Studio Hedgehog or newer
- Java 17
- Android SDK 34

### Debug Build
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

### Release Build
Create `local.properties`:
```properties
KEYSTORE_FILE=/path/to/keystore.jks
KEYSTORE_PASSWORD=your_password
KEY_ALIAS=your_alias
KEY_PASSWORD=your_key_password
```

Build:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

## Design Patterns

1. **MVVM with StateFlow**: `AiViewModel` exposes `StateFlow<AiUiState>`, UI recomposes via `collectAsState()`.
2. **Repository Pattern**: `AiAnalysisRepository` delegates to `AiAnalysisProviders`, `AiAnalysisStreaming`, `AiAnalysisChat`, `AiAnalysisModels`.
3. **Provider Consolidation**: 28 OpenAI-compatible providers share unified code paths via `AiService.apiFormat`, `chatPath`, `modelsPath`, `modelFilter`. Only Anthropic and Google have unique implementations.
4. **Factory Pattern**: `AiApiFactory` uses `ConcurrentHashMap` cache for Retrofit instances.
5. **Flow-based Streaming**: SSE parsing returns `Flow<String>`. Three parsers: `parseOpenAiSseStream()`, `parseClaudeSseStream()`, `parseGeminiSseStream()`.
6. **Full-Screen Overlay**: `if (showOverlay) { OverlayScreen(...); return }` preserves parent `remember` state.
7. **Two-Tier Navigation**: Main screens use Jetpack Navigation (`NavHost`). Settings sub-screens use `SettingsSubScreen` enum with `when` inside `SettingsScreen`.

## API Format Differences

| Aspect | OpenAI-Compatible (28) | Anthropic | Google Gemini |
|--------|----------------------|-----------|---------------|
| Auth | `Authorization: Bearer` | `x-api-key` header | `?key=` query param |
| System prompt | In messages array | Separate `system` field | Separate `systemInstruction` |
| Response content | `choices[].message.content` | `content[].text` blocks | `candidates[].content.parts[].text` |
| Token usage | `prompt_tokens`/`completion_tokens` | `input_tokens`/`output_tokens` | `promptTokenCount`/`candidatesTokenCount` |
| Stream format | `data: {choices:[{delta:{content:""}}]}` | Event-typed: `content_block_delta` | `data: {candidates:[...]}` |
| Stream end | `data: [DONE]` | `event: message_stop` | Stream ends |
| max_tokens | Optional (null) | Required (defaults to 4096) | Optional (`maxOutputTokens`) |

## Data Flow

### Report Generation
1. `generateGenericAiReports()` merges parameter presets, resolves agents/swarm members
2. Synthetic IDs for swarm members: `"swarm:PROVIDER_ID:model_name"`
3. `<user>...</user>` content extracted -> stored in `rapportText`, stripped from AI prompt
4. All agents dispatched in parallel via `async { }` blocks
5. Each agent: resolves effective key/model/endpoint -> API call -> cost calculation -> storage update

### Streaming Chat
1. `sendChatMessageStream()` returns `Flow<String>`
2. Dispatches to format-specific method via `AiAnalysisChat.kt`
3. SSE parser reads line-by-line, emits text deltas
4. UI accumulates in `streamingContent`, auto-scrolls

### Endpoint Resolution Priority
1. Agent's specific `endpointId`
2. Provider's default endpoint
3. Provider's first endpoint
4. Hardcoded `baseUrl` from AiService

## SharedPreferences Reference

```
# Preferences file: eval_prefs
developer_mode                         # Boolean
track_api_calls                        # Boolean
username                               # String (default "You")
full_screen_mode                       # Boolean
hugging_face_api_key                   # String

# Per-provider (x31 services, using service.prefsKey)
ai_{prefsKey}_api_key                  # String
ai_{prefsKey}_model                    # String
ai_{prefsKey}_model_source             # Enum: API or MANUAL
ai_{prefsKey}_manual_models            # JSON List<String>
ai_{prefsKey}_admin_url                # String
ai_{prefsKey}_model_list_url           # String
ai_{prefsKey}_parameters_id            # JSON List<String>

# Collections (JSON)
ai_agents                             # JSON List<AiAgent>
ai_flocks                             # JSON List<AiSwarm> (SWAPPED!)
ai_swarms                             # JSON List<AiFlock> (SWAPPED!)
ai_parameters                         # JSON List<AiParameters>
ai_prompts                            # JSON List<AiPrompt>
ai_endpoints                          # JSON Map<AiService, List<AiEndpoint>>
provider_states                       # JSON Map<String, String>

# Pricing cache (pricing_cache preferences file)
openrouter_pricing                     # JSON Map, 7-day TTL
litellm_pricing                        # JSON Map
manual_pricing                         # JSON Map
```

## File Storage

```
/files/reports/{uuid}.json             # AI Reports
/files/usage-stats.json                # API call counts, token totals
/files/prompt-history.json             # Last 100 prompts
/files/chat-history/{uuid}.json        # Chat sessions
/files/trace/{host}_{timestamp}.json   # API traces (when enabled)
/files/model_pricing.json              # OpenRouter pricing
/files/model_supported_parameters.json # OpenRouter model params
/cache/ai_analysis/                    # Temp sharing files
/cache/shared_traces/                  # Exported traces
/cache/exports/                        # Config export files
/assets/model_prices_and_context_window.json  # Bundled LiteLLM pricing (1.2 MB)
```

## Configuration Export Format

Version 17 JSON format:
```json
{
  "version": 17,
  "huggingFaceApiKey": "hf_...",
  "providers": {
    "OPENAI": {
      "modelSource": "API",
      "models": [],
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
      "parametersIds": ["params-uuid"]
    }
  ],
  "flocks": [
    {
      "id": "uuid",
      "name": "My Flock",
      "agentIds": ["agent-uuid-1", "agent-uuid-2"],
      "parametersIds": []
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
      "id": "params-uuid",
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

Import supports versions 11-18. Legacy versions are auto-migrated.

## Dependencies

| Category | Library | Version |
|----------|---------|---------|
| AndroidX | core-ktx | 1.12.0 |
| AndroidX | lifecycle-runtime-ktx | 2.7.0 |
| AndroidX | activity-compose | 1.8.2 |
| AndroidX | compose-bom | 2024.02.00 |
| AndroidX | lifecycle-viewmodel-compose | 2.7.0 |
| AndroidX | navigation-compose | 2.7.7 |
| Networking | retrofit | 2.9.0 |
| Networking | converter-gson / converter-scalars | 2.9.0 |
| Networking | okhttp / logging-interceptor | 4.12.0 |
| Coroutines | kotlinx-coroutines-core/android | 1.7.3 |
| Markdown | compose-markdown (JitPack) | 0.5.8 |

## Android Manifest

- **Permission**: Only `INTERNET`
- **Backup**: Disabled (`allowBackup=false`) to protect API keys
- **Network security**: Cleartext HTTP only for localhost/127.0.0.1
- **Single activity**: `MainActivity` with `singleTop` launch mode
- **External intent**: `com.ai.ACTION_NEW_REPORT`
- **FileProvider**: `com.ai.fileprovider`

## Legacy Items

The project evolved from a chess evaluation app ("Eval"). Unused legacy resources remain:
- 12 chess piece PNG drawables in `res/drawable/`
- 4 audio WAV files in `res/raw/`
- Chess colors in `res/values/colors.xml`
- `eco_codes.json` (420 KB) chess opening database in assets
- ProGuard rules referencing `com.chessreplay.*`
- `useLegacyPackaging = true` and `extractNativeLibs = true` for former Stockfish JNI
