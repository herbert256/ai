package com.eval.ui

import com.eval.chess.ChessBoard
import com.eval.data.AiAnalysisResponse
import com.eval.data.AiService
import com.eval.data.ChessServer
import com.eval.data.TournamentInfo
import com.eval.data.BroadcastInfo
import com.eval.data.TvChannelInfo
import com.eval.data.PuzzleInfo
import com.eval.data.StreamerInfo
import com.eval.data.LichessGame
import com.eval.data.PlayerInfo
import com.eval.stockfish.AnalysisResult

// ECO Opening entry from eco_codes.json
data class EcoOpening(
    val fen: String,
    val eco: String,
    val name: String,
    val moves: String
)

// Analysis stage - the 3 sequential stages of game analysis
enum class AnalysisStage {
    PREVIEW,        // Quick analysis pass - not interruptible
    ANALYSE,        // Deep analysis pass - interruptible
    MANUAL          // Manual exploration - final stage
}

// Settings for Preview Stage (quick analysis during navigation)
data class PreviewStageSettings(
    val secondsForMove: Float = 0.05f,  // 0.01, 0.05, 0.10, 0.25, 0.50
    val threads: Int = 1,               // 1-4
    val hashMb: Int = 8,                // 8, 16, 64
    val useNnue: Boolean = false
)

// Settings for Analyse Stage (auto-analysis)
data class AnalyseStageSettings(
    val secondsForMove: Float = 1.00f,  // 0.50, 0.75, 1.00, 1.50, 2.50, 5.00, 10.00
    val threads: Int = 2,               // 1-8
    val hashMb: Int = 64,               // 16, 64, 96, 128, 192, 256
    val useNnue: Boolean = true
)

// Arrow drawing modes
enum class ArrowMode {
    NONE,        // No arrows
    MAIN_LINE,   // Draw arrows from PV line (current behavior)
    MULTI_LINES  // Draw one arrow per Stockfish line with score
}

// Default arrow colors (with alpha for semi-transparency)
const val DEFAULT_WHITE_ARROW_COLOR = 0xCC3399FFL  // Semi-transparent blue
const val DEFAULT_BLACK_ARROW_COLOR = 0xCC44BB44L  // Semi-transparent green
const val DEFAULT_MULTI_LINES_ARROW_COLOR = 0xCCFFFF00L  // Semi-transparent yellow

// Settings for Manual Stage (interactive deep analysis)
data class ManualStageSettings(
    val depth: Int = 32,                // 16-64
    val threads: Int = 4,               // 1-16
    val hashMb: Int = 128,              // 32, 64, 96, 128, 192, 256, 384, 512
    val multiPv: Int = 3,               // 1-32
    val useNnue: Boolean = true,
    // Main line arrow settings
    val arrowMode: ArrowMode = ArrowMode.NONE,
    val numArrows: Int = 4,             // 1-8 arrows from PV
    val showArrowNumbers: Boolean = true,
    val whiteArrowColor: Long = DEFAULT_WHITE_ARROW_COLOR,
    val blackArrowColor: Long = DEFAULT_BLACK_ARROW_COLOR,
    // Multi lines arrow settings
    val multiLinesArrowColor: Long = DEFAULT_MULTI_LINES_ARROW_COLOR
)

// Combined Stockfish settings for all stages
data class StockfishSettings(
    val previewStage: PreviewStageSettings = PreviewStageSettings(),
    val analyseStage: AnalyseStageSettings = AnalyseStageSettings(),
    val manualStage: ManualStageSettings = ManualStageSettings()
)

// Default board colors
const val DEFAULT_WHITE_SQUARE_COLOR = 0xFFF0D9B5L  // Light brown
const val DEFAULT_BLACK_SQUARE_COLOR = 0xFFB58863L  // Dark brown
const val DEFAULT_WHITE_PIECE_COLOR = 0xFFFFFFFF   // White
const val DEFAULT_BLACK_PIECE_COLOR = 0xFF000000L  // Black

// Evaluation bar defaults
const val DEFAULT_EVAL_BAR_COLOR_1 = 0xFFFFFFFF   // White (score color)
const val DEFAULT_EVAL_BAR_COLOR_2 = 0xFF000000L  // Black (filler color)

// Graph color defaults
const val DEFAULT_GRAPH_PLUS_SCORE_COLOR = 0xFF00E676L    // Bright green
const val DEFAULT_GRAPH_NEGATIVE_SCORE_COLOR = 0xFFFF5252L // Bright red
const val DEFAULT_GRAPH_BACKGROUND_COLOR = 0xFF1A1A1AL    // Dark gray
const val DEFAULT_GRAPH_ANALYSE_LINE_COLOR = 0xFFFFFFFFL  // White
const val DEFAULT_GRAPH_VERTICAL_LINE_COLOR = 0xFF2196F3L // Blue

// Graph settings
data class GraphSettings(
    val plusScoreColor: Long = DEFAULT_GRAPH_PLUS_SCORE_COLOR,
    val negativeScoreColor: Long = DEFAULT_GRAPH_NEGATIVE_SCORE_COLOR,
    val backgroundColor: Long = DEFAULT_GRAPH_BACKGROUND_COLOR,
    val analyseLineColor: Long = DEFAULT_GRAPH_ANALYSE_LINE_COLOR,
    val verticalLineColor: Long = DEFAULT_GRAPH_VERTICAL_LINE_COLOR,
    val lineGraphRange: Int = 7,    // Range for line graph (-7 to +7)
    val barGraphRange: Int = 3,     // Range for bar graph (-3 to +3)
    val lineGraphScale: Int = 100,  // Scale/height for line graph in percent (50-300%)
    val barGraphScale: Int = 100    // Scale/height for bar graph in percent (50-300%)
)

// Player bar display mode
enum class PlayerBarMode {
    NONE,    // No player bars
    TOP,     // Single combined bar at top
    BOTTOM,  // Single combined bar at bottom
    BOTH     // Separate bars above and below board (default)
}

// Evaluation bar position
enum class EvalBarPosition {
    NONE,    // No evaluation bar
    LEFT,    // Evaluation bar on the left of the board
    RIGHT    // Evaluation bar on the right of the board (default)
}

// Board layout settings
data class BoardLayoutSettings(
    val showCoordinates: Boolean = true,
    val showLastMove: Boolean = true,
    val playerBarMode: PlayerBarMode = PlayerBarMode.BOTH,
    val showRedBorderForPlayerToMove: Boolean = false,
    val whiteSquareColor: Long = DEFAULT_WHITE_SQUARE_COLOR,
    val blackSquareColor: Long = DEFAULT_BLACK_SQUARE_COLOR,
    val whitePieceColor: Long = DEFAULT_WHITE_PIECE_COLOR,
    val blackPieceColor: Long = DEFAULT_BLACK_PIECE_COLOR,
    // Evaluation bar settings
    val evalBarPosition: EvalBarPosition = EvalBarPosition.RIGHT,
    val evalBarColor1: Long = DEFAULT_EVAL_BAR_COLOR_1,
    val evalBarColor2: Long = DEFAULT_EVAL_BAR_COLOR_2,
    val evalBarRange: Int = 5
)

// Interface visibility settings for Preview stage
// Note: Score Line graph and Game Information are always shown in Preview
data class PreviewStageVisibility(
    val showScoreBarsGraph: Boolean = false,
    val showResultBar: Boolean = false,
    val showBoard: Boolean = false,
    val showMoveList: Boolean = false,
    val showPgn: Boolean = false
)

// Interface visibility settings for Analyse stage
data class AnalyseStageVisibility(
    val showScoreLineGraph: Boolean = true,
    val showScoreBarsGraph: Boolean = true,
    val showBoard: Boolean = true,
    val showStockfishAnalyse: Boolean = true,
    val showResultBar: Boolean = false,
    val showMoveList: Boolean = false,
    val showGameInfo: Boolean = false,
    val showPgn: Boolean = false
)

// Interface visibility settings for Manual stage
// Note: Board, Navigation bar, and Stockfish panel are always shown in Manual
data class ManualStageVisibility(
    val showResultBar: Boolean = true,
    val showScoreLineGraph: Boolean = true,
    val showScoreBarsGraph: Boolean = true,
    val showTimeGraph: Boolean = false,
    val showOpeningExplorer: Boolean = false,
    val showOpeningName: Boolean = false,
    val showRawStockfishScore: Boolean = false,
    val showMoveList: Boolean = true,
    val showGameInfo: Boolean = false,
    val showPgn: Boolean = false
)

// Combined interface visibility settings
data class InterfaceVisibilitySettings(
    val previewStage: PreviewStageVisibility = PreviewStageVisibility(),
    val analyseStage: AnalyseStageVisibility = AnalyseStageVisibility(),
    val manualStage: ManualStageVisibility = ManualStageVisibility()
)

// General app settings
data class GeneralSettings(
    val longTapForFullScreen: Boolean = false,
    val paginationPageSize: Int = 25,
    val moveSoundsEnabled: Boolean = true,
    val developerMode: Boolean = false,
    val trackApiCalls: Boolean = false,
    val lichessUsername: String = "",
    val flipScoreWhenBlack: Boolean = true
)

// Move quality assessment based on evaluation change
enum class MoveQuality(val symbol: String, val color: Long) {
    BRILLIANT("!!", 0xFF00BCD4),   // Cyan - exceptional move
    GOOD("!", 0xFF4CAF50),         // Green - good move
    INTERESTING("!?", 0xFF8BC34A), // Light green - interesting choice
    DUBIOUS("?!", 0xFFFF9800),     // Orange - questionable move
    MISTAKE("?", 0xFFFF5722),      // Deep orange - mistake
    BLUNDER("??", 0xFFF44336),     // Red - severe error
    BOOK("", 0xFFA0522D),          // Brown - book move
    NORMAL("", 0x00000000)         // No symbol - neutral move
}

// Thresholds for move quality assessment (in pawns)
object MoveQualityThresholds {
    const val BLUNDER = 2.0f      // Loss >= 2 pawns
    const val MISTAKE = 1.0f      // Loss 1-2 pawns
    const val DUBIOUS = 0.5f      // Loss 0.5-1 pawn
    const val GOOD = 0.3f         // Found improvement >= 0.3 pawns
    const val BRILLIANT = 1.0f    // Found very strong move with gain >= 1 pawn
}

data class MoveScore(
    val score: Float,
    val isMate: Boolean,
    val mateIn: Int,
    val depth: Int = 0,
    val nodes: Long = 0,
    val nps: Long = 0
)

data class MoveDetails(
    val san: String,
    val from: String,
    val to: String,
    val isCapture: Boolean,
    val pieceType: String, // K, Q, R, B, N, P
    val clockTime: String? = null  // Format: "H:MM:SS" or "M:SS" or null if not available
)

// Stored analysed game with all analysis data
data class AnalysedGame(
    val timestamp: Long,                      // When the analysis was completed
    val whiteName: String,                    // White player name
    val blackName: String,                    // Black player name
    val result: String,                       // "1-0", "0-1", "1/2-1/2"
    val pgn: String,                          // Original PGN
    val moves: List<String>,                  // Move list in UCI format
    val moveDetails: List<MoveDetails>,       // Detailed move info
    val previewScores: Map<Int, MoveScore>,   // Preview stage scores (graph 1)
    val analyseScores: Map<Int, MoveScore>,   // Analyse stage scores (graph 2)
    val openingName: String? = null,          // Opening name if available
    val speed: String? = null                 // Game speed (bullet, blitz, etc.)
)

// Entry in the list of previous game retrieves
data class RetrievedGamesEntry(
    val accountName: String,
    val server: ChessServer
)

data class GameUiState(
    val stockfishInstalled: Boolean = true,  // Assume true until checked
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // Game list for selection
    val gameList: List<LichessGame> = emptyList(),
    val showGameSelection: Boolean = false,
    // Currently loaded game
    val game: LichessGame? = null,
    val openingName: String? = null,  // Extracted from PGN headers
    val currentOpeningName: String? = null,  // Dynamically calculated from current move position
    val currentBoard: ChessBoard = ChessBoard(),
    val moves: List<String> = emptyList(),
    val moveDetails: List<MoveDetails> = emptyList(),
    val currentMoveIndex: Int = -1,
    val analysisEnabled: Boolean = true,
    val analysisResult: AnalysisResult? = null,
    val analysisResultFen: String? = null,  // FEN for which analysisResult is valid
    val stockfishReady: Boolean = false,
    val flippedBoard: Boolean = false,
    val userPlayedBlack: Boolean = false,  // True if active player played black (for score perspective)
    val stockfishSettings: StockfishSettings = StockfishSettings(),
    val boardLayoutSettings: BoardLayoutSettings = BoardLayoutSettings(),
    val graphSettings: GraphSettings = GraphSettings(),
    val interfaceVisibility: InterfaceVisibilitySettings = InterfaceVisibilitySettings(),
    val showSettingsDialog: Boolean = false,
    val showHelpScreen: Boolean = false,
    // Exploring line state
    val isExploringLine: Boolean = false,
    val exploringLineMoves: List<String> = emptyList(),
    val exploringLineMoveIndex: Int = -1,
    val savedGameMoveIndex: Int = -1,
    // Analysis stage state
    val currentStage: AnalysisStage = AnalysisStage.PREVIEW,
    val autoAnalysisIndex: Int = -1,
    val previewScores: Map<Int, MoveScore> = emptyMap(),     // Preview stage scores
    val analyseScores: Map<Int, MoveScore> = emptyMap(),     // Analyse stage scores
    val moveQualities: Map<Int, MoveQuality> = emptyMap(),   // Move quality assessments
    val autoAnalysisCurrentScore: MoveScore? = null,
    val remainingAnalysisMoves: List<Int> = emptyList(),
    // Lichess settings
    val lichessMaxGames: Int = 10,
    // General settings (fullScreenMode is stored here, not persistent)
    val generalSettings: GeneralSettings = GeneralSettings(),
    // Game selection info for full screen display
    val gameSelectionUsername: String = "",
    val gameSelectionServer: ChessServer = ChessServer.LICHESS,
    // Previous game retrieves (list of lists)
    val hasPreviousRetrieves: Boolean = false,
    val showPreviousRetrievesSelection: Boolean = false,
    val previousRetrievesList: List<RetrievedGamesEntry> = emptyList(),
    // Selected retrieve - for showing games from a previous retrieve
    val showSelectedRetrieveGames: Boolean = false,
    val selectedRetrieveEntry: RetrievedGamesEntry? = null,
    val selectedRetrieveGames: List<LichessGame> = emptyList(),
    // Game selection pagination
    val gameSelectionPage: Int = 0,
    val gameSelectionPageSize: Int = 10,
    val gameSelectionLoading: Boolean = false,
    val gameSelectionHasMore: Boolean = true,
    // Analysed games
    val hasAnalysedGames: Boolean = false,
    val showAnalysedGamesSelection: Boolean = false,
    val analysedGamesList: List<AnalysedGame> = emptyList(),
    // Retrieve screen navigation
    val showRetrieveScreen: Boolean = false,
    // AI Analysis settings and state
    val aiSettings: AiSettings = AiSettings(),
    val showAiAnalysisDialog: Boolean = false,
    // Share position dialog
    val showSharePositionDialog: Boolean = false,
    // Opening Explorer
    val openingExplorerData: com.eval.data.OpeningExplorerResponse? = null,
    val openingExplorerLoading: Boolean = false,
    val showOpeningExplorer: Boolean = true,
    val aiAnalysisResult: AiAnalysisResponse? = null,
    val aiAnalysisLoading: Boolean = false,
    val aiAnalysisServiceName: String = "",
    // ChatGPT model selection
    val availableChatGptModels: List<String> = emptyList(),
    val isLoadingChatGptModels: Boolean = false,
    // Gemini model selection
    val availableGeminiModels: List<String> = emptyList(),
    val isLoadingGeminiModels: Boolean = false,
    // Grok model selection
    val availableGrokModels: List<String> = emptyList(),
    val isLoadingGrokModels: Boolean = false,
    // Groq model selection
    val availableGroqModels: List<String> = emptyList(),
    val isLoadingGroqModels: Boolean = false,
    // DeepSeek model selection
    val availableDeepSeekModels: List<String> = emptyList(),
    val isLoadingDeepSeekModels: Boolean = false,
    // Mistral model selection
    val availableMistralModels: List<String> = emptyList(),
    val isLoadingMistralModels: Boolean = false,
    // Perplexity model selection
    val availablePerplexityModels: List<String> = emptyList(),
    val isLoadingPerplexityModels: Boolean = false,
    // Together model selection
    val availableTogetherModels: List<String> = emptyList(),
    val isLoadingTogetherModels: Boolean = false,
    // OpenRouter model selection
    val availableOpenRouterModels: List<String> = emptyList(),
    val isLoadingOpenRouterModels: Boolean = false,
    // Player info screen
    val showPlayerInfoScreen: Boolean = false,
    val playerInfo: PlayerInfo? = null,
    val playerInfoLoading: Boolean = false,
    val playerInfoError: String? = null,
    val playerGames: List<LichessGame> = emptyList(),
    val playerGamesLoading: Boolean = false,
    val playerGamesPage: Int = 0,
    val playerGamesPageSize: Int = 10,
    val playerGamesHasMore: Boolean = true,  // Whether there might be more games to fetch
    // Top rankings screen
    val showTopRankingsScreen: Boolean = false,
    val topRankingsServer: ChessServer = ChessServer.LICHESS,
    val topRankingsLoading: Boolean = false,
    val topRankingsError: String? = null,
    val topRankings: Map<String, List<com.eval.data.LeaderboardPlayer>> = emptyMap(),
    // Tournaments screen
    val showTournamentsScreen: Boolean = false,
    val tournamentsServer: ChessServer = ChessServer.LICHESS,
    val tournamentsLoading: Boolean = false,
    val tournamentsError: String? = null,
    val tournamentsList: List<TournamentInfo> = emptyList(),
    val selectedTournament: TournamentInfo? = null,
    val tournamentGames: List<LichessGame> = emptyList(),
    val tournamentGamesLoading: Boolean = false,
    // Broadcasts screen (Lichess only)
    val showBroadcastsScreen: Boolean = false,
    val broadcastsLoading: Boolean = false,
    val broadcastsError: String? = null,
    val broadcastsList: List<BroadcastInfo> = emptyList(),
    val selectedBroadcast: BroadcastInfo? = null,
    val selectedBroadcastRound: com.eval.data.BroadcastRoundInfo? = null,
    val broadcastGames: List<LichessGame> = emptyList(),
    val broadcastGamesLoading: Boolean = false,
    // TV screen (Lichess only)
    val showTvScreen: Boolean = false,
    val tvLoading: Boolean = false,
    val tvError: String? = null,
    val tvChannels: List<TvChannelInfo> = emptyList(),
    // Daily puzzle (Chess.com)
    val showDailyPuzzleScreen: Boolean = false,
    val dailyPuzzleLoading: Boolean = false,
    val dailyPuzzle: PuzzleInfo? = null,
    // Streamers (Chess.com)
    val showStreamersScreen: Boolean = false,
    val streamersLoading: Boolean = false,
    val streamersList: List<StreamerInfo> = emptyList(),
    // PGN file event selection
    val showPgnEventSelection: Boolean = false,
    val pgnEvents: List<String> = emptyList(),
    val pgnGamesByEvent: Map<String, List<LichessGame>> = emptyMap(),
    val selectedPgnEvent: String? = null,
    val pgnGamesForSelectedEvent: List<LichessGame> = emptyList(),
    // GIF export state
    val gifExportProgress: Float? = null,
    val showGifExportDialog: Boolean = false,
    // Live game following state
    val isLiveGame: Boolean = false,
    val liveGameId: String? = null,
    val autoFollowLive: Boolean = true,
    val liveStreamConnected: Boolean = false,
    // AI Reports export state
    val showAiReportsSelectionDialog: Boolean = false,
    val showAiReportsDialog: Boolean = false,
    val aiReportsProgress: Int = 0,  // Number of completed calls
    val aiReportsTotal: Int = 0,     // Total number of calls to make
    val aiReportsResults: Map<AiService, AiAnalysisResponse> = emptyMap(),  // Legacy keyed by provider
    val aiReportsAgentResults: Map<String, AiAnalysisResponse> = emptyMap(),  // Keyed by agent ID
    val aiReportsSelectedServices: Set<AiService> = emptySet(),  // Services being called (legacy)
    val aiReportsSelectedAgents: Set<String> = emptySet(),  // Agent IDs being called (new three-tier)
    // Player AI Reports
    val showPlayerAiReportsSelectionDialog: Boolean = false,
    val showPlayerAiReportsDialog: Boolean = false,
    val playerAiReportsProgress: Int = 0,
    val playerAiReportsTotal: Int = 0,
    val playerAiReportsResults: Map<AiService, AiAnalysisResponse> = emptyMap(),  // Legacy
    val playerAiReportsAgentResults: Map<String, AiAnalysisResponse> = emptyMap(),  // Keyed by agent ID
    val playerAiReportsSelectedServices: Set<AiService> = emptySet(),  // Legacy
    val playerAiReportsSelectedAgents: Set<String> = emptySet(),  // Agent IDs (new three-tier)
    val playerAiReportsPlayerName: String = "",
    val playerAiReportsServer: String? = null,  // null for "other player" prompt
    val playerAiReportsPlayerInfo: com.eval.data.PlayerInfo? = null,  // Full player info for HTML
    // AI screens
    val showAiScreen: Boolean = false,  // AI hub screen
    val showAiHistoryScreen: Boolean = false,  // AI history screen
    val showNewAiReportScreen: Boolean = false,  // New AI report screen (formerly GenericAiPromptScreen)
    val showGenericAiAgentSelection: Boolean = false,
    val showGenericAiReportsDialog: Boolean = false,
    val genericAiPromptTitle: String = "",
    val genericAiPromptText: String = "",
    val genericAiReportsProgress: Int = 0,
    val genericAiReportsTotal: Int = 0,
    val genericAiReportsSelectedAgents: Set<String> = emptySet(),
    val genericAiReportsAgentResults: Map<String, com.eval.data.AiAnalysisResponse> = emptyMap(),
    // ECO Opening selection
    val showOpeningSelection: Boolean = false,
    val ecoOpenings: List<EcoOpening> = emptyList(),
    val ecoOpeningsLoading: Boolean = false,
    // API Trace screen
    val showTraceScreen: Boolean = false,
    val showTraceDetailScreen: Boolean = false,
    val traceDetailFilename: String? = null
)
