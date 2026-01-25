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

Eval is an Android app for fetching and analyzing chess games from Lichess.org and Chess.com using the Stockfish 17.1 chess engine and 9 AI services (ChatGPT, Claude, Gemini, Grok, DeepSeek, Mistral, Perplexity, Together AI, OpenRouter). The app retrieves games via APIs, parses PGN notation, and provides multi-stage computer analysis with an interactive board display.

**Key Dependencies:**
- External app required: "Stockfish 17.1 Chess Engine" (com.stockfish141) from Google Play Store
- Android SDK: minSdk 26, targetSdk 34, compileSdk 34
- Kotlin with Jetpack Compose for UI
- Retrofit for networking (Lichess, Chess.com, and AI service APIs)

## Architecture

### Package Structure (46 Kotlin files, ~33,500 lines)

```
com.eval/
├── MainActivity.kt (33 lines) - Entry point, sets up Compose theme
├── chess/
│   ├── ChessBoard.kt (643 lines) - Board state, move validation, FEN generation/parsing
│   └── PgnParser.kt (100 lines) - PGN parsing with clock time extraction
├── data/
│   ├── LichessApi.kt (308 lines) - Retrofit interface for Lichess API
│   ├── ChessComApi.kt (335 lines) - Retrofit interface for Chess.com API
│   ├── LichessModels.kt (40 lines) - Data classes: LichessGame, Players, Clock
│   ├── LichessRepository.kt (1,420 lines) - Repository with ChessServer enum, dual-server support
│   ├── AiAnalysisApi.kt (444 lines) - Retrofit interfaces for 9 AI services + DUMMY
│   ├── AiAnalysisRepository.kt (925 lines) - AI position analysis with model fetching
│   ├── AiHistoryManager.kt (156 lines) - HTML report storage and history management
│   ├── OpeningBook.kt (225 lines) - ECO opening identification by move sequences
│   ├── OpeningExplorerApi.kt (61 lines) - Opening statistics API
│   └── ApiTracer.kt (290 lines) - API request/response tracing and storage
├── stockfish/
│   └── StockfishEngine.kt (529 lines) - UCI protocol wrapper, process management
├── export/
│   ├── GifExporter.kt (357 lines) - Animated GIF export of game replay
│   ├── PgnExporter.kt (134 lines) - PGN file export
│   └── AnimatedGifEncoder.kt (895 lines) - GIF encoding library
├── audio/
│   └── MoveSoundPlayer.kt (84 lines) - Move sound effects
└── ui/
    ├── GameViewModel.kt (1,850 lines) - Central state management, orchestration hub
    ├── AnalysisOrchestrator.kt (632 lines) - 3-stage analysis pipeline
    ├── GameLoader.kt (787 lines) - Game loading from APIs, files, storage
    ├── BoardNavigationManager.kt (376 lines) - Move navigation and line exploration
    ├── ContentSourceManager.kt (707 lines) - Tournaments, broadcasts, TV, streamers, puzzle
    ├── LiveGameManager.kt (199 lines) - Real-time game following via streaming
    ├── GameScreen.kt (5,116 lines) - Main screen, dialogs, AI analysis, HTML export
    ├── GameContent.kt (1,759 lines) - Game display: board, players, moves, result bar, eval bar
    ├── ChessBoardView.kt (546 lines) - Canvas-based interactive chess board with arrows
    ├── AnalysisComponents.kt (850 lines) - Evaluation graphs, analysis panel
    ├── MovesDisplay.kt (222 lines) - Move list with scores and piece symbols
    ├── OpeningExplorerPanel.kt (235 lines) - Opening statistics display
    ├── GameSelectionDialog.kt (688 lines) - Dialog for selecting from multiple games
    ├── RetrieveScreen.kt (2,379 lines) - Game retrieval UI with all content sources
    ├── SettingsScreen.kt (426 lines) - Settings navigation hub
    ├── StockfishSettingsScreen.kt (441 lines) - Engine settings for all 3 stages
    ├── ArrowSettingsScreen.kt (330 lines) - Arrow display configuration
    ├── BoardLayoutSettingsScreen.kt (501 lines) - Board colors, pieces, coordinates, eval bar
    ├── InterfaceSettingsScreen.kt (406 lines) - UI visibility settings per stage
    ├── GraphSettingsScreen.kt (365 lines) - Evaluation graph color and range settings
    ├── AiSettingsScreen.kt (3,380 lines) - AI service settings, three-tier architecture
    ├── AiScreens.kt (936 lines) - AI hub, history, new report, prompt history screens
    ├── GeneralSettingsScreen.kt (190 lines) - General app settings
    ├── HelpScreen.kt (317 lines) - In-app help documentation
    ├── TraceScreen.kt (562 lines) - API trace log viewer and detail screens
    ├── ColorPickerDialog.kt (254 lines) - HSV color picker for colors
    ├── GameModels.kt (470 lines) - Data classes and enums (core domain models)
    ├── SettingsPreferences.kt (1,176 lines) - SharedPreferences persistence layer
    ├── GameStorageManager.kt (216 lines) - Game persistence and retrieval
    ├── SharedComponents.kt (106 lines) - Reusable Compose components (EvalTitleBar, etc.)
    ├── Navigation.kt (230 lines) - Jetpack Navigation routes and composables
    └── theme/Theme.kt (32 lines) - Material3 dark theme
```

### Key Data Classes

```kotlin
// Chess servers
enum class ChessServer { LICHESS, CHESS_COM }

// Analysis stages
enum class AnalysisStage { PREVIEW, ANALYSE, MANUAL }

// Arrow modes
enum class ArrowMode { NONE, MAIN_LINE, MULTI_LINES }

// Player bar display modes
enum class PlayerBarMode { NONE, TOP, BOTTOM, BOTH }

// Evaluation bar position
enum class EvalBarPosition { NONE, LEFT, RIGHT }

// Move quality assessment
enum class MoveQuality { BRILLIANT, GOOD, INTERESTING, DUBIOUS, MISTAKE, BLUNDER, BOOK, NORMAL }

// AI Services (9 services + DUMMY for testing)
enum class AiService(displayName, baseUrl) {
    CHATGPT("ChatGPT", "https://api.openai.com/"),
    CLAUDE("Claude", "https://api.anthropic.com/"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/"),
    GROK("Grok", "https://api.x.ai/"),
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
    val text: String     // Template with @FEN@, @PLAYER@, @SERVER@, @DATE@ placeholders
)

data class AiAgent(
    val id: String,
    val name: String,
    val provider: AiService,
    val model: String,
    val apiKey: String,
    val gamePromptId: String,
    val serverPlayerPromptId: String,
    val otherPlayerPromptId: String
)

// Prompt history entry
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

// Settings for each analysis stage
data class PreviewStageSettings(
    secondsForMove: Float = 0.05f,  // 10-500ms
    threads: Int = 1,
    hashMb: Int = 8,
    useNnue: Boolean = false
)

data class AnalyseStageSettings(
    secondsForMove: Float = 1.00f,  // 500ms-10s
    threads: Int = 2,
    hashMb: Int = 64,
    useNnue: Boolean = true
)

data class ManualStageSettings(
    depth: Int = 32,                // 16-64
    threads: Int = 4,               // 1-16
    hashMb: Int = 128,              // 32-512
    multiPv: Int = 3,               // 1-32
    useNnue: Boolean = true,
    arrowMode: ArrowMode = ArrowMode.NONE,
    numArrows: Int = 4,             // 1-8
    showArrowNumbers: Boolean = true,
    whiteArrowColor: Long, blackArrowColor: Long, multiLinesArrowColor: Long
)

// Board appearance
data class BoardLayoutSettings(
    showCoordinates, showLastMove, playerBarMode, showRedBorderForPlayerToMove,
    whiteSquareColor, blackSquareColor, whitePieceColor, blackPieceColor,
    evalBarPosition, evalBarColor1, evalBarColor2, evalBarRange
)

// Graph settings
data class GraphSettings(
    plusScoreColor, negativeScoreColor, backgroundColor,
    analyseLineColor, verticalLineColor, lineGraphRange, barGraphRange
)

// Interface visibility per stage
data class PreviewStageVisibility(showScoreBarsGraph, showResultBar, showBoard, showMoveList, showPgn)

data class AnalyseStageVisibility(
    showScoreLineGraph, showScoreBarsGraph, showBoard, showStockfishAnalyse,
    showResultBar, showMoveList, showGameInfo, showPgn
)

data class ManualStageVisibility(
    showResultBar, showScoreLineGraph, showScoreBarsGraph,
    showTimeGraph,           // Clock time graph
    showOpeningExplorer,     // Opening statistics panel
    showOpeningName,         // Current opening name
    showRawStockfishScore,   // Raw Stockfish score display
    showMoveList, showGameInfo, showPgn
)

// General settings
data class GeneralSettings(longTapFullscreen, paginationPageSize, moveSoundsEnabled, trackApiCalls)

// Game storage
data class AnalysedGame(timestamp, whiteName, blackName, result, pgn, moves, moveDetails,
    previewScores, analyseScores, openingName, speed)

// Move analysis
data class MoveScore(score, isMate, mateIn, depth, nodes, nps)
data class MoveDetails(san, from, to, isCapture, pieceType, clockTime)
```

### Key Design Patterns

1. **MVVM with Jetpack Compose**: `GameViewModel` exposes `StateFlow<GameUiState>`, UI recomposes reactively

2. **Three-Stage Analysis System**: Games progress through PREVIEW → ANALYSE → MANUAL stages automatically

3. **Helper Class Pattern**: Large ViewModel split into focused helper classes:
   - `AnalysisOrchestrator` - 3-stage analysis pipeline orchestration
   - `GameLoader` - Game loading from APIs, PGN files, storage
   - `BoardNavigationManager` - Move navigation and line exploration
   - `ContentSourceManager` - Tournaments, broadcasts, TV, streamers, puzzle
   - `LiveGameManager` - Real-time game following
   - `SettingsPreferences` - All settings load/save operations
   - `GameStorageManager` - Game persistence operations

4. **Arrow System with 3 Modes**:
   - NONE: No arrows displayed
   - MAIN_LINE: Multiple arrows from PV line (1-8 arrows, colored by side, numbered)
   - MULTI_LINES: One arrow per Stockfish line with evaluation score displayed

5. **Three-Tier AI Architecture**:
   - Providers: AI service definitions (9 services) with model source settings
   - Prompts: Reusable prompt templates with placeholders
   - Agents: User-configured combinations (provider + model + API key + prompt refs)

6. **Result Type Pattern**: `sealed class Result<T> { Success, Error }` for API responses

7. **Jetpack Navigation**: Type-safe navigation with `NavHost` and route definitions

## Analysis Stages

### 1. Preview Stage
- **Purpose**: Quick initial scan of all positions
- **Timing**: 50ms per move (configurable: 10ms-500ms)
- **Direction**: Forward through game (move 0 → end)
- **Settings**: 1 thread, 8MB hash, NNUE disabled
- **UI**: Board hidden by default, evaluation graph shown
- **Interruptible**: No

### 2. Analyse Stage
- **Purpose**: Deep analysis focusing on critical positions
- **Timing**: 1 second per move (configurable: 500ms-10s)
- **Direction**: Backward through game (end → move 0)
- **Settings**: 2 threads, 64MB hash (default), NNUE enabled
- **UI**: "Analysis running - tap to end" banner (yellow text on blue)
- **Interruptible**: Yes (tap to enter Manual at biggest evaluation change)

### 3. Manual Stage
- **Purpose**: Interactive exploration with real-time analysis
- **Analysis**: Depth-based (default 32), MultiPV support (1-32 lines)
- **Settings**: 4 threads, 128MB hash (default), NNUE enabled
- **Features**:
  - Navigation buttons: ⏮ ◀ ▶ ⏭ ↻ (flip)
  - Three arrow modes (cycle with ↗ icon)
  - AI logos for position analysis (9 services)
  - Line exploration (click PV moves to explore variations)
  - "Back to game" button when exploring
  - Evaluation graph with tap/drag navigation
  - Opening explorer with statistics
  - Clock time graph (optional)

## Content Sources

### Lichess.org
- User games (NDJSON streaming)
- Tournaments (list and games)
- Broadcasts (rounds with PGN)
- TV channels (featured games)
- Top rankings (bullet, blitz, rapid, classical, ultra-bullet, chess960)
- Streamers (active streams)
- Live game following (WebSocket-like streaming)

### Chess.com
- User games (monthly archives)
- Daily puzzle
- Top rankings (leaderboards)

### Local Sources
- PGN file upload (with ZIP support)
- PGN event grouping (multi-game files)
- ECO opening selection (codes A00-E99)
- FEN position entry (with history)
- Previously analysed games

## AI Analysis Feature

### Supported Services (9 + DUMMY)
| Service | Default Model | API Endpoint | Auth Method |
|---------|--------------|--------------|-------------|
| ChatGPT | gpt-4o-mini | `api.openai.com/v1/chat/completions` or `/v1/responses` | Bearer token |
| Claude | claude-sonnet-4-20250514 | `api.anthropic.com/v1/messages` | x-api-key header |
| Gemini | gemini-2.0-flash | `generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` | Query param |
| Grok | grok-3-mini | `api.x.ai/v1/chat/completions` | Bearer token |
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
- **Prompt Placeholders**: @FEN@, @PLAYER@, @SERVER@, @DATE@ for dynamic content
- **Model Sources**: API (fetch dynamically) or Manual (user-maintained list)
- **AI Hub Screen**: Access to New AI Report, Prompt History, AI History
- **Prompt History**: Saves submitted prompts for reuse
- **AI History**: Stores generated HTML reports
- **Custom Prompts**: Template with placeholders for position injection
- **Dynamic Models**: Fetches available models from each service
- **Analysis Dialog**: Shows AI response with markdown rendering
- **View in Chrome**: Opens rich HTML report with chessboard.js, graphs, and move list
- **Send by Email**: Emails HTML report as attachment (remembers email address)
- **Retry Logic**: Automatic retry with 500ms delay on API failures
- **Export/Import**: JSON export of providers, prompts, agents via share sheet

## Navigation System

### Routes (Navigation.kt)
```
game                           - Main game display screen
settings                       - Settings hub
help                           - In-app documentation
trace_list                     - API trace log viewer
trace_detail/{filename}        - Trace detail viewer
retrieve                       - Game retrieval UI
ai                             - AI hub screen
ai_history                     - View previously generated reports
ai_new_report                  - Create custom AI analysis
ai_new_report/{title}/{prompt} - New AI report with pre-filled values
ai_prompt_history              - View and reuse previous prompts
player_info                    - Player information screen
```

### Screen Composables
- All screens use `EvalTitleBar` for consistent header
- Back navigation handled via `NavController.popBackStack()`
- Parameter passing via URL-encoded route arguments

## UI Components

### Title Bar Icons (left to right when game loaded)
- **↻** : Reload last game
- **≡** : Return to game selection / Retrieve screen
- **↗** : Arrow mode toggle (cycles: None → Main line → Multi lines)
- **⚙** : Settings
- **?** : Help screen
- **🐛** : API trace viewer (when tracking enabled)

### Result Bar
- Shows current move with piece symbol and coordinates
- Displays evaluation score + delta from previous move (e.g., "+2.1 / -0.8")
- Delta color indicates if move was good (green), bad (red), or neutral (blue)

### Settings Structure
```
Settings (main menu)
├── Board layout
│   ├── Show coordinates (toggle)
│   ├── Show last move (toggle)
│   ├── Player bar(s) (None / Top / Bottom / Both)
│   ├── Red border for player to move (toggle)
│   ├── White/Black squares color (color pickers)
│   ├── White/Black pieces color (color pickers)
│   ├── Evaluation bar (None / Left / Right)
│   ├── Eval bar colors and range
│   └── Reset to defaults (button)
├── Show interface elements
│   ├── Preview Stage: score bars graph, result bar, board, move list, PGN
│   ├── Analyse Stage: score graphs, board, Stockfish analyse, result bar, move list, game info, PGN
│   └── Manual Stage: result bar, score graphs, time graph, opening explorer, opening name,
│       raw Stockfish score, move list, game info, PGN
├── Graph settings
│   ├── Plus/Negative score colors
│   ├── Background, analyse line, vertical line colors
│   └── Line graph range, bar graph range
├── Arrow settings
│   ├── Draw arrows (None / Main line / Multi lines)
│   ├── Main line: numArrows, showNumbers, white/black colors
│   └── Multi lines: arrow color
├── Stockfish
│   ├── Preview Stage: seconds, threads, hash, NNUE
│   ├── Analyse Stage: seconds, threads, hash, NNUE
│   └── Manual Stage: depth, threads, hash, multiPV, NNUE
├── AI Setup (three-tier architecture)
│   ├── AI Providers (model source + models per provider)
│   ├── AI Prompts (CRUD - name + template with placeholders)
│   ├── AI Agents (CRUD - provider + model + API key + prompt refs)
│   └── Export/Import configuration
└── General
    ├── Long tap for fullscreen (toggle)
    ├── Pagination page size (5-50)
    ├── Move sounds (toggle)
    └── Track API calls (toggle) - Developer debugging
```

## API Tracing (Developer Feature)

When "Track API calls" is enabled in General Settings:
- All API requests (Lichess, Chess.com, AI services, Opening Explorer) are logged
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

## Stockfish Integration

### Engine Management (`StockfishEngine.kt`)
- **Requirement**: External "Stockfish 17.1 Chess Engine" app must be installed
- **Detection**: `isStockfishInstalled()` checks for `com.stockfish141` package
- **Binary Location**: Uses native library from system app (`lib_sf171.so`)
- **Process Control**: Managed via `ProcessBuilder`, UCI protocol communication
- **Restart Sequence**: `stop()` → `newGame()` → `delay(100ms)` → start analysis

### Analysis Output
```kotlin
data class AnalysisResult(
    val depth: Int,
    val nodes: Long,
    val nps: Long,
    val lines: List<PvLine>  // Multiple principal variations
)

data class PvLine(
    val score: Float,      // Centipawns / 100
    val isMate: Boolean,
    val mateIn: Int,
    val pv: String,        // Space-separated UCI moves
    val multipv: Int       // Line number (1-32)
)
```

## Export Features

### PGN Export
- Full PGN with headers (Event, Site, Date, White, Black, Result, etc.)
- Evaluation comments for each move
- Share via Android share sheet

### GIF Export
- Animated replay of the game
- Configurable frame delay
- Board state at each move with evaluation bar
- Progress indicator during generation
- Share via Android share sheet

### AI Reports Export
- HTML report with interactive chessboard (chessboard.js)
- Evaluation graphs (line and bar)
- Move list with scores
- AI analysis for selected positions from multiple agents
- View in Chrome or send via email
- Footer: "Generated by Eval <version>" with timestamp

### AI Configuration Export
- JSON format (version 3) with providers, prompts, agents
- Export via Android share sheet as .json file
- Import from clipboard

## Settings Persistence

All settings managed via `SettingsPreferences` class with SharedPreferences (`eval_prefs`):

```
// Server configuration
lichess_username, lichess_max_games, chesscom_username, chesscom_max_games

// Game storage
current_game_json, retrieves_list, retrieved_games_{server}_{username}
analysed_games, fen_history

// Stockfish settings (per stage)
preview_seconds, preview_threads, preview_hash, preview_nnue
analyse_seconds, analyse_threads, analyse_hash, analyse_nnue
manual_depth, manual_threads, manual_hash, manual_multipv, manual_nnue
manual_arrow_mode, manual_numarrows, manual_shownumbers
manual_white_arrow_color, manual_black_arrow_color, manual_multilines_arrow_color

// Board layout
board_show_coordinates, board_show_last_move, board_player_bar_mode
board_red_border_player_to_move
board_white_square_color, board_black_square_color
board_white_piece_color, board_black_piece_color
eval_bar_position, eval_bar_color_1, eval_bar_color_2, eval_bar_range

// Graph settings
graph_plus_score_color, graph_negative_score_color, graph_background_color
graph_analyse_line_color, graph_vertical_line_color
graph_line_range, graph_bar_range

// Interface visibility (per stage)
preview_vis_*, analyse_vis_*, manual_vis_*

// AI settings (9 services)
ai_report_email
ai_{service}_api_key, ai_{service}_model, ai_{service}_prompt (3 prompt variants)
ai_{service}_model_source, ai_{service}_manual_models

// AI three-tier architecture
ai_prompts (JSON list), ai_agents (JSON list), ai_migration_done

// Prompt history
prompt_history (JSON list of PromptHistoryEntry)

// General
general_long_tap_fullscreen, general_pagination_page_size, general_move_sounds, track_api_calls
```

## Common Tasks

### Adding a New Setting
1. Add field to appropriate settings data class in `GameModels.kt`
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
6. Add settings fields to `AiSettings` in `AiSettingsScreen.kt`
7. Add UI in `AiSettingsScreen.kt` (navigation card + settings screen)
8. Add SharedPreferences keys in `SettingsPreferences.kt`
9. Update load/save methods for AI settings
10. Update AI export/import if needed

### Adding a New Content Source
1. Add API endpoints to `LichessApi.kt` or `ChessComApi.kt`
2. Add data models if needed
3. Add repository methods in `LichessRepository.kt`
4. Add state fields to `GameUiState` in `GameModels.kt`
5. Add methods to `ContentSourceManager.kt`
6. Add UI in `RetrieveScreen.kt`
7. Connect to `GameLoader` if games need to be loaded

### Adding a New Navigation Route
1. Add route constant to `NavRoutes` in `Navigation.kt`
2. Add helper function for parameterized routes if needed
3. Add `composable()` block in `EvalNavHost`
4. Create or update screen composable
5. Pass navigation callbacks from parent screens

### Modifying Arrow Behavior
1. Check `ArrowMode` enum in `GameModels.kt`
2. Update `MoveArrow` data class in `ChessBoardView.kt` if needed
3. Modify arrow generation in `GameContent.kt`
4. Update arrow drawing in `ChessBoardView.kt`

### Changing Board Display
1. `ChessBoardView.kt` - Canvas drawing, gestures, arrows, piece tinting
2. `GameContent.kt` - Layout, player bars, result bar, eval bar, AI logos
3. `AnalysisComponents.kt` - Evaluation graphs, analysis panel

### Modifying HTML Report (View in Chrome)
1. Update `convertMarkdownToHtml()` or `convertAiReportsToHtml()` or `convertGenericAiReportsToHtml()` in `GameScreen.kt`
2. HTML uses chessboard.js and chess.js from CDN
3. Includes: board with eval bar, player bars, AI analysis, graphs, move list, Stockfish analysis
4. Footer shows "Generated by Eval <version>" with timestamp

### Triggering Stockfish Analysis
Use `restartAnalysisForExploringLine()` in `AnalysisOrchestrator` for proper restart sequence:
- Stops current analysis
- Sends newGame command
- Waits 100ms
- Starts fresh analysis

## File Provider Configuration

For sharing HTML reports via email and AI config export, the app uses FileProvider configured in:
- `AndroidManifest.xml` - Provider declaration
- `res/xml/file_paths.xml` - Cache directory paths (`ai_analysis/` subdirectory)

## Testing Checklist

After making changes, verify:
- [ ] App builds: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug`
- [ ] App installs and launches without crash
- [ ] Load game from Lichess and Chess.com
- [ ] Run full analysis (Preview → Analyse → Manual)
- [ ] Navigate through moves
- [ ] Arrow modes work (None → Main line → Multi lines)
- [ ] AI analysis works (if API keys configured)
- [ ] AI Agents with three-tier architecture work
- [ ] AI Reports with multiple agents work
- [ ] AI Hub screens work (New Report, Prompt History, AI History)
- [ ] Settings changes persist
- [ ] Export features work (PGN, GIF, HTML, AI Config)
- [ ] Live game following works
- [ ] Opening explorer displays statistics
- [ ] Player info screen works
