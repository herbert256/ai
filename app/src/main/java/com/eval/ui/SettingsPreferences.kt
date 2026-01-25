package com.eval.ui

import android.content.SharedPreferences
import com.eval.data.ChessServer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Data class for storing prompt history entries.
 */
data class PromptHistoryEntry(
    val timestamp: Long,
    val title: String,
    val prompt: String
)

/**
 * Helper class for managing all settings persistence via SharedPreferences.
 */
class SettingsPreferences(private val prefs: SharedPreferences) {

    private val gson = Gson()

    // ============================================================================
    // Username and Server Properties
    // ============================================================================

    val savedLichessUsername: String
        get() = prefs.getString(KEY_LICHESS_USERNAME, "DrNykterstein") ?: "DrNykterstein"

    val lichessMaxGames: Int
        get() = prefs.getInt(KEY_LICHESS_MAX_GAMES, 10)

    // ============================================================================
    // Username and Server Save Methods
    // ============================================================================

    fun saveLichessUsername(username: String) {
        prefs.edit().putString(KEY_LICHESS_USERNAME, username).apply()
    }

    fun saveLichessMaxGames(max: Int) {
        prefs.edit().putInt(KEY_LICHESS_MAX_GAMES, max).apply()
    }

    // ============================================================================
    // Stockfish Settings
    // ============================================================================

    fun loadStockfishSettings(): StockfishSettings {
        return StockfishSettings(
            previewStage = PreviewStageSettings(
                secondsForMove = prefs.getFloat(KEY_PREVIEW_SECONDS, 0.05f),
                threads = prefs.getInt(KEY_PREVIEW_THREADS, 1),
                hashMb = prefs.getInt(KEY_PREVIEW_HASH, 8),
                useNnue = prefs.getBoolean(KEY_PREVIEW_NNUE, false)
            ),
            analyseStage = AnalyseStageSettings(
                secondsForMove = prefs.getFloat(KEY_ANALYSE_SECONDS, 1.00f),
                threads = prefs.getInt(KEY_ANALYSE_THREADS, 2),
                hashMb = prefs.getInt(KEY_ANALYSE_HASH, 32),
                useNnue = prefs.getBoolean(KEY_ANALYSE_NNUE, true)
            ),
            manualStage = ManualStageSettings(
                depth = prefs.getInt(KEY_MANUAL_DEPTH, 32),
                threads = prefs.getInt(KEY_MANUAL_THREADS, 4),
                hashMb = prefs.getInt(KEY_MANUAL_HASH, 64),
                multiPv = prefs.getInt(KEY_MANUAL_MULTIPV, 3),
                useNnue = prefs.getBoolean(KEY_MANUAL_NNUE, true),
                arrowMode = try {
                    ArrowMode.valueOf(prefs.getString(KEY_MANUAL_ARROW_MODE, ArrowMode.MAIN_LINE.name) ?: ArrowMode.MAIN_LINE.name)
                } catch (e: IllegalArgumentException) {
                    ArrowMode.MAIN_LINE
                },
                numArrows = prefs.getInt(KEY_MANUAL_NUMARROWS, 4),
                showArrowNumbers = prefs.getBoolean(KEY_MANUAL_SHOWNUMBERS, true),
                whiteArrowColor = prefs.getLong(KEY_MANUAL_WHITE_ARROW_COLOR, DEFAULT_WHITE_ARROW_COLOR),
                blackArrowColor = prefs.getLong(KEY_MANUAL_BLACK_ARROW_COLOR, DEFAULT_BLACK_ARROW_COLOR),
                multiLinesArrowColor = prefs.getLong(KEY_MANUAL_MULTILINES_ARROW_COLOR, DEFAULT_MULTI_LINES_ARROW_COLOR)
            )
        )
    }

    fun saveStockfishSettings(settings: StockfishSettings) {
        prefs.edit()
            // Preview stage
            .putFloat(KEY_PREVIEW_SECONDS, settings.previewStage.secondsForMove)
            .putInt(KEY_PREVIEW_THREADS, settings.previewStage.threads)
            .putInt(KEY_PREVIEW_HASH, settings.previewStage.hashMb)
            .putBoolean(KEY_PREVIEW_NNUE, settings.previewStage.useNnue)
            // Analyse stage
            .putFloat(KEY_ANALYSE_SECONDS, settings.analyseStage.secondsForMove)
            .putInt(KEY_ANALYSE_THREADS, settings.analyseStage.threads)
            .putInt(KEY_ANALYSE_HASH, settings.analyseStage.hashMb)
            .putBoolean(KEY_ANALYSE_NNUE, settings.analyseStage.useNnue)
            // Manual stage
            .putInt(KEY_MANUAL_DEPTH, settings.manualStage.depth)
            .putInt(KEY_MANUAL_THREADS, settings.manualStage.threads)
            .putInt(KEY_MANUAL_HASH, settings.manualStage.hashMb)
            .putInt(KEY_MANUAL_MULTIPV, settings.manualStage.multiPv)
            .putBoolean(KEY_MANUAL_NNUE, settings.manualStage.useNnue)
            .putString(KEY_MANUAL_ARROW_MODE, settings.manualStage.arrowMode.name)
            .putInt(KEY_MANUAL_NUMARROWS, settings.manualStage.numArrows)
            .putBoolean(KEY_MANUAL_SHOWNUMBERS, settings.manualStage.showArrowNumbers)
            .putLong(KEY_MANUAL_WHITE_ARROW_COLOR, settings.manualStage.whiteArrowColor)
            .putLong(KEY_MANUAL_BLACK_ARROW_COLOR, settings.manualStage.blackArrowColor)
            .putLong(KEY_MANUAL_MULTILINES_ARROW_COLOR, settings.manualStage.multiLinesArrowColor)
            .apply()
    }

    // ============================================================================
    // Board Layout Settings
    // ============================================================================

    fun loadBoardLayoutSettings(): BoardLayoutSettings {
        val playerBarModeOrdinal = prefs.getInt(KEY_BOARD_PLAYER_BAR_MODE, PlayerBarMode.BOTH.ordinal)
        val playerBarMode = PlayerBarMode.entries.getOrElse(playerBarModeOrdinal) { PlayerBarMode.BOTH }
        val evalBarPositionOrdinal = prefs.getInt(KEY_EVAL_BAR_POSITION, EvalBarPosition.RIGHT.ordinal)
        val evalBarPosition = EvalBarPosition.entries.getOrElse(evalBarPositionOrdinal) { EvalBarPosition.RIGHT }
        return BoardLayoutSettings(
            showCoordinates = prefs.getBoolean(KEY_BOARD_SHOW_COORDINATES, true),
            showLastMove = prefs.getBoolean(KEY_BOARD_SHOW_LAST_MOVE, true),
            playerBarMode = playerBarMode,
            showRedBorderForPlayerToMove = prefs.getBoolean(KEY_BOARD_RED_BORDER_PLAYER_TO_MOVE, false),
            whiteSquareColor = prefs.getLong(KEY_BOARD_WHITE_SQUARE_COLOR, DEFAULT_WHITE_SQUARE_COLOR),
            blackSquareColor = prefs.getLong(KEY_BOARD_BLACK_SQUARE_COLOR, DEFAULT_BLACK_SQUARE_COLOR),
            whitePieceColor = prefs.getLong(KEY_BOARD_WHITE_PIECE_COLOR, DEFAULT_WHITE_PIECE_COLOR),
            blackPieceColor = prefs.getLong(KEY_BOARD_BLACK_PIECE_COLOR, DEFAULT_BLACK_PIECE_COLOR),
            evalBarPosition = evalBarPosition,
            evalBarColor1 = prefs.getLong(KEY_EVAL_BAR_COLOR_1, DEFAULT_EVAL_BAR_COLOR_1),
            evalBarColor2 = prefs.getLong(KEY_EVAL_BAR_COLOR_2, DEFAULT_EVAL_BAR_COLOR_2),
            evalBarRange = prefs.getInt(KEY_EVAL_BAR_RANGE, 5)
        )
    }

    fun saveBoardLayoutSettings(settings: BoardLayoutSettings) {
        prefs.edit()
            .putBoolean(KEY_BOARD_SHOW_COORDINATES, settings.showCoordinates)
            .putBoolean(KEY_BOARD_SHOW_LAST_MOVE, settings.showLastMove)
            .putInt(KEY_BOARD_PLAYER_BAR_MODE, settings.playerBarMode.ordinal)
            .putBoolean(KEY_BOARD_RED_BORDER_PLAYER_TO_MOVE, settings.showRedBorderForPlayerToMove)
            .putLong(KEY_BOARD_WHITE_SQUARE_COLOR, settings.whiteSquareColor)
            .putLong(KEY_BOARD_BLACK_SQUARE_COLOR, settings.blackSquareColor)
            .putLong(KEY_BOARD_WHITE_PIECE_COLOR, settings.whitePieceColor)
            .putLong(KEY_BOARD_BLACK_PIECE_COLOR, settings.blackPieceColor)
            .putInt(KEY_EVAL_BAR_POSITION, settings.evalBarPosition.ordinal)
            .putLong(KEY_EVAL_BAR_COLOR_1, settings.evalBarColor1)
            .putLong(KEY_EVAL_BAR_COLOR_2, settings.evalBarColor2)
            .putInt(KEY_EVAL_BAR_RANGE, settings.evalBarRange)
            .apply()
    }

    // ============================================================================
    // Graph Settings
    // ============================================================================

    fun loadGraphSettings(): GraphSettings {
        return GraphSettings(
            plusScoreColor = prefs.getLong(KEY_GRAPH_PLUS_SCORE_COLOR, DEFAULT_GRAPH_PLUS_SCORE_COLOR),
            negativeScoreColor = prefs.getLong(KEY_GRAPH_NEGATIVE_SCORE_COLOR, DEFAULT_GRAPH_NEGATIVE_SCORE_COLOR),
            backgroundColor = prefs.getLong(KEY_GRAPH_BACKGROUND_COLOR, DEFAULT_GRAPH_BACKGROUND_COLOR),
            analyseLineColor = prefs.getLong(KEY_GRAPH_ANALYSE_LINE_COLOR, DEFAULT_GRAPH_ANALYSE_LINE_COLOR),
            verticalLineColor = prefs.getLong(KEY_GRAPH_VERTICAL_LINE_COLOR, DEFAULT_GRAPH_VERTICAL_LINE_COLOR),
            lineGraphRange = prefs.getInt(KEY_GRAPH_LINE_RANGE, 7),
            barGraphRange = prefs.getInt(KEY_GRAPH_BAR_RANGE, 3),
            lineGraphScale = prefs.getInt(KEY_GRAPH_LINE_SCALE, 100),
            barGraphScale = prefs.getInt(KEY_GRAPH_BAR_SCALE, 100)
        )
    }

    fun saveGraphSettings(settings: GraphSettings) {
        prefs.edit()
            .putLong(KEY_GRAPH_PLUS_SCORE_COLOR, settings.plusScoreColor)
            .putLong(KEY_GRAPH_NEGATIVE_SCORE_COLOR, settings.negativeScoreColor)
            .putLong(KEY_GRAPH_BACKGROUND_COLOR, settings.backgroundColor)
            .putLong(KEY_GRAPH_ANALYSE_LINE_COLOR, settings.analyseLineColor)
            .putLong(KEY_GRAPH_VERTICAL_LINE_COLOR, settings.verticalLineColor)
            .putInt(KEY_GRAPH_LINE_RANGE, settings.lineGraphRange)
            .putInt(KEY_GRAPH_BAR_RANGE, settings.barGraphRange)
            .putInt(KEY_GRAPH_LINE_SCALE, settings.lineGraphScale)
            .putInt(KEY_GRAPH_BAR_SCALE, settings.barGraphScale)
            .apply()
    }

    // ============================================================================
    // Interface Visibility Settings
    // ============================================================================

    fun loadInterfaceVisibilitySettings(): InterfaceVisibilitySettings {
        return InterfaceVisibilitySettings(
            previewStage = PreviewStageVisibility(
                showScoreBarsGraph = prefs.getBoolean(KEY_PREVIEW_VIS_SCOREBARSGRAPH, false),
                showResultBar = prefs.getBoolean(KEY_PREVIEW_VIS_RESULTBAR, false),
                showBoard = prefs.getBoolean(KEY_PREVIEW_VIS_BOARD, false),
                showMoveList = prefs.getBoolean(KEY_PREVIEW_VIS_MOVELIST, false),
                showPgn = prefs.getBoolean(KEY_PREVIEW_VIS_PGN, false)
            ),
            analyseStage = AnalyseStageVisibility(
                showScoreLineGraph = prefs.getBoolean(KEY_ANALYSE_VIS_SCORELINEGRAPH, true),
                showScoreBarsGraph = prefs.getBoolean(KEY_ANALYSE_VIS_SCOREBARSGRAPH, true),
                showBoard = prefs.getBoolean(KEY_ANALYSE_VIS_BOARD, true),
                showStockfishAnalyse = prefs.getBoolean(KEY_ANALYSE_VIS_STOCKFISHANALYSE, true),
                showResultBar = prefs.getBoolean(KEY_ANALYSE_VIS_RESULTBAR, false),
                showMoveList = prefs.getBoolean(KEY_ANALYSE_VIS_MOVELIST, false),
                showGameInfo = prefs.getBoolean(KEY_ANALYSE_VIS_GAMEINFO, false),
                showPgn = prefs.getBoolean(KEY_ANALYSE_VIS_PGN, false)
            ),
            manualStage = ManualStageVisibility(
                showResultBar = prefs.getBoolean(KEY_MANUAL_VIS_RESULTBAR, true),
                showScoreLineGraph = prefs.getBoolean(KEY_MANUAL_VIS_SCORELINEGRAPH, true),
                showScoreBarsGraph = prefs.getBoolean(KEY_MANUAL_VIS_SCOREBARSGRAPH, true),
                showTimeGraph = prefs.getBoolean(KEY_MANUAL_VIS_TIMEGRAPH, false),
                showOpeningExplorer = prefs.getBoolean(KEY_MANUAL_VIS_OPENINGEXPLORER, false),
                showOpeningName = prefs.getBoolean(KEY_MANUAL_VIS_OPENINGNAME, false),
                showRawStockfishScore = prefs.getBoolean(KEY_MANUAL_VIS_RAWSTOCKFISHSCORE, false),
                showMoveList = prefs.getBoolean(KEY_MANUAL_VIS_MOVELIST, true),
                showGameInfo = prefs.getBoolean(KEY_MANUAL_VIS_GAMEINFO, false),
                showPgn = prefs.getBoolean(KEY_MANUAL_VIS_PGN, false)
            )
        )
    }

    fun saveInterfaceVisibilitySettings(settings: InterfaceVisibilitySettings) {
        prefs.edit()
            // Preview stage
            .putBoolean(KEY_PREVIEW_VIS_SCOREBARSGRAPH, settings.previewStage.showScoreBarsGraph)
            .putBoolean(KEY_PREVIEW_VIS_RESULTBAR, settings.previewStage.showResultBar)
            .putBoolean(KEY_PREVIEW_VIS_BOARD, settings.previewStage.showBoard)
            .putBoolean(KEY_PREVIEW_VIS_MOVELIST, settings.previewStage.showMoveList)
            .putBoolean(KEY_PREVIEW_VIS_PGN, settings.previewStage.showPgn)
            // Analyse stage
            .putBoolean(KEY_ANALYSE_VIS_SCORELINEGRAPH, settings.analyseStage.showScoreLineGraph)
            .putBoolean(KEY_ANALYSE_VIS_SCOREBARSGRAPH, settings.analyseStage.showScoreBarsGraph)
            .putBoolean(KEY_ANALYSE_VIS_BOARD, settings.analyseStage.showBoard)
            .putBoolean(KEY_ANALYSE_VIS_STOCKFISHANALYSE, settings.analyseStage.showStockfishAnalyse)
            .putBoolean(KEY_ANALYSE_VIS_RESULTBAR, settings.analyseStage.showResultBar)
            .putBoolean(KEY_ANALYSE_VIS_MOVELIST, settings.analyseStage.showMoveList)
            .putBoolean(KEY_ANALYSE_VIS_GAMEINFO, settings.analyseStage.showGameInfo)
            .putBoolean(KEY_ANALYSE_VIS_PGN, settings.analyseStage.showPgn)
            // Manual stage
            .putBoolean(KEY_MANUAL_VIS_RESULTBAR, settings.manualStage.showResultBar)
            .putBoolean(KEY_MANUAL_VIS_SCORELINEGRAPH, settings.manualStage.showScoreLineGraph)
            .putBoolean(KEY_MANUAL_VIS_SCOREBARSGRAPH, settings.manualStage.showScoreBarsGraph)
            .putBoolean(KEY_MANUAL_VIS_TIMEGRAPH, settings.manualStage.showTimeGraph)
            .putBoolean(KEY_MANUAL_VIS_OPENINGEXPLORER, settings.manualStage.showOpeningExplorer)
            .putBoolean(KEY_MANUAL_VIS_OPENINGNAME, settings.manualStage.showOpeningName)
            .putBoolean(KEY_MANUAL_VIS_RAWSTOCKFISHSCORE, settings.manualStage.showRawStockfishScore)
            .putBoolean(KEY_MANUAL_VIS_MOVELIST, settings.manualStage.showMoveList)
            .putBoolean(KEY_MANUAL_VIS_GAMEINFO, settings.manualStage.showGameInfo)
            .putBoolean(KEY_MANUAL_VIS_PGN, settings.manualStage.showPgn)
            .apply()
    }

    // ============================================================================
    // General Settings
    // ============================================================================

    fun loadGeneralSettings(): GeneralSettings {
        // Full screen mode is not persistent - always starts as false
        return GeneralSettings(
            longTapForFullScreen = false,
            paginationPageSize = prefs.getInt(KEY_PAGINATION_PAGE_SIZE, 25).coerceIn(5, 50),
            moveSoundsEnabled = prefs.getBoolean(KEY_MOVE_SOUNDS_ENABLED, true),
            developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, false),
            trackApiCalls = prefs.getBoolean(KEY_TRACK_API_CALLS, false),
            lichessUsername = prefs.getString(KEY_LICHESS_USERNAME, "") ?: "",
            flipScoreWhenBlack = prefs.getBoolean(KEY_FLIP_SCORE_WHEN_BLACK, true)
        )
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        // Full screen mode is not persistent - do not save
        // But pagination page size, move sounds, developer mode, and track API calls are saved
        prefs.edit()
            .putInt(KEY_PAGINATION_PAGE_SIZE, settings.paginationPageSize.coerceIn(5, 50))
            .putBoolean(KEY_MOVE_SOUNDS_ENABLED, settings.moveSoundsEnabled)
            .putBoolean(KEY_DEVELOPER_MODE, settings.developerMode)
            .putBoolean(KEY_TRACK_API_CALLS, settings.trackApiCalls)
            .putString(KEY_LICHESS_USERNAME, settings.lichessUsername)
            .putBoolean(KEY_FLIP_SCORE_WHEN_BLACK, settings.flipScoreWhenBlack)
            .apply()
    }

    // ============================================================================
    // AI Settings
    // ============================================================================

    /**
     * Load AI settings with prompts and agents, performing migration if needed.
     */
    fun loadAiSettingsWithMigration(): AiSettings {
        val baseSettings = loadAiSettings()
        val (prompts, agents) = migrateToAgentsIfNeeded(baseSettings)
        return baseSettings.copy(prompts = prompts, agents = agents)
    }

    fun loadAiSettings(): AiSettings {
        return AiSettings(
            chatGptApiKey = prefs.getString(KEY_AI_CHATGPT_API_KEY, "") ?: "",
            chatGptModel = prefs.getString(KEY_AI_CHATGPT_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini",
            chatGptPrompt = prefs.getString(KEY_AI_CHATGPT_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            chatGptServerPlayerPrompt = prefs.getString(KEY_AI_CHATGPT_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            chatGptOtherPlayerPrompt = prefs.getString(KEY_AI_CHATGPT_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            chatGptModelSource = loadModelSource(KEY_AI_CHATGPT_MODEL_SOURCE, ModelSource.API),
            chatGptManualModels = loadManualModels(KEY_AI_CHATGPT_MANUAL_MODELS),
            claudeApiKey = prefs.getString(KEY_AI_CLAUDE_API_KEY, "") ?: "",
            claudeModel = prefs.getString(KEY_AI_CLAUDE_MODEL, "claude-sonnet-4-20250514") ?: "claude-sonnet-4-20250514",
            claudePrompt = prefs.getString(KEY_AI_CLAUDE_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            claudeServerPlayerPrompt = prefs.getString(KEY_AI_CLAUDE_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            claudeOtherPlayerPrompt = prefs.getString(KEY_AI_CLAUDE_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            claudeModelSource = loadModelSource(KEY_AI_CLAUDE_MODEL_SOURCE, ModelSource.MANUAL),
            claudeManualModels = loadManualModelsWithDefault(KEY_AI_CLAUDE_MANUAL_MODELS, CLAUDE_MODELS),
            geminiApiKey = prefs.getString(KEY_AI_GEMINI_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_AI_GEMINI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash",
            geminiPrompt = prefs.getString(KEY_AI_GEMINI_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            geminiServerPlayerPrompt = prefs.getString(KEY_AI_GEMINI_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            geminiOtherPlayerPrompt = prefs.getString(KEY_AI_GEMINI_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            geminiModelSource = loadModelSource(KEY_AI_GEMINI_MODEL_SOURCE, ModelSource.API),
            geminiManualModels = loadManualModels(KEY_AI_GEMINI_MANUAL_MODELS),
            grokApiKey = prefs.getString(KEY_AI_GROK_API_KEY, "") ?: "",
            grokModel = prefs.getString(KEY_AI_GROK_MODEL, "grok-3-mini") ?: "grok-3-mini",
            grokPrompt = prefs.getString(KEY_AI_GROK_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            grokServerPlayerPrompt = prefs.getString(KEY_AI_GROK_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            grokOtherPlayerPrompt = prefs.getString(KEY_AI_GROK_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            grokModelSource = loadModelSource(KEY_AI_GROK_MODEL_SOURCE, ModelSource.API),
            grokManualModels = loadManualModels(KEY_AI_GROK_MANUAL_MODELS),
            groqApiKey = prefs.getString(KEY_AI_GROQ_API_KEY, "") ?: "",
            groqModel = prefs.getString(KEY_AI_GROQ_MODEL, "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile",
            groqPrompt = prefs.getString(KEY_AI_GROQ_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            groqServerPlayerPrompt = prefs.getString(KEY_AI_GROQ_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            groqOtherPlayerPrompt = prefs.getString(KEY_AI_GROQ_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            groqModelSource = loadModelSource(KEY_AI_GROQ_MODEL_SOURCE, ModelSource.API),
            groqManualModels = loadManualModels(KEY_AI_GROQ_MANUAL_MODELS),
            deepSeekApiKey = prefs.getString(KEY_AI_DEEPSEEK_API_KEY, "") ?: "",
            deepSeekModel = prefs.getString(KEY_AI_DEEPSEEK_MODEL, "deepseek-chat") ?: "deepseek-chat",
            deepSeekPrompt = prefs.getString(KEY_AI_DEEPSEEK_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            deepSeekServerPlayerPrompt = prefs.getString(KEY_AI_DEEPSEEK_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            deepSeekOtherPlayerPrompt = prefs.getString(KEY_AI_DEEPSEEK_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            deepSeekModelSource = loadModelSource(KEY_AI_DEEPSEEK_MODEL_SOURCE, ModelSource.API),
            deepSeekManualModels = loadManualModels(KEY_AI_DEEPSEEK_MANUAL_MODELS),
            mistralApiKey = prefs.getString(KEY_AI_MISTRAL_API_KEY, "") ?: "",
            mistralModel = prefs.getString(KEY_AI_MISTRAL_MODEL, "mistral-small-latest") ?: "mistral-small-latest",
            mistralPrompt = prefs.getString(KEY_AI_MISTRAL_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            mistralServerPlayerPrompt = prefs.getString(KEY_AI_MISTRAL_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            mistralOtherPlayerPrompt = prefs.getString(KEY_AI_MISTRAL_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            mistralModelSource = loadModelSource(KEY_AI_MISTRAL_MODEL_SOURCE, ModelSource.API),
            mistralManualModels = loadManualModels(KEY_AI_MISTRAL_MANUAL_MODELS),
            perplexityApiKey = prefs.getString(KEY_AI_PERPLEXITY_API_KEY, "") ?: "",
            perplexityModel = prefs.getString(KEY_AI_PERPLEXITY_MODEL, "sonar") ?: "sonar",
            perplexityPrompt = prefs.getString(KEY_AI_PERPLEXITY_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            perplexityServerPlayerPrompt = prefs.getString(KEY_AI_PERPLEXITY_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            perplexityOtherPlayerPrompt = prefs.getString(KEY_AI_PERPLEXITY_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            perplexityModelSource = loadModelSource(KEY_AI_PERPLEXITY_MODEL_SOURCE, ModelSource.MANUAL),
            perplexityManualModels = loadManualModelsWithDefault(KEY_AI_PERPLEXITY_MANUAL_MODELS, PERPLEXITY_MODELS),
            togetherApiKey = prefs.getString(KEY_AI_TOGETHER_API_KEY, "") ?: "",
            togetherModel = prefs.getString(KEY_AI_TOGETHER_MODEL, "meta-llama/Llama-3.3-70B-Instruct-Turbo") ?: "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            togetherPrompt = prefs.getString(KEY_AI_TOGETHER_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            togetherServerPlayerPrompt = prefs.getString(KEY_AI_TOGETHER_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            togetherOtherPlayerPrompt = prefs.getString(KEY_AI_TOGETHER_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            togetherModelSource = loadModelSource(KEY_AI_TOGETHER_MODEL_SOURCE, ModelSource.API),
            togetherManualModels = loadManualModels(KEY_AI_TOGETHER_MANUAL_MODELS),
            openRouterApiKey = prefs.getString(KEY_AI_OPENROUTER_API_KEY, "") ?: "",
            openRouterModel = prefs.getString(KEY_AI_OPENROUTER_MODEL, "anthropic/claude-3.5-sonnet") ?: "anthropic/claude-3.5-sonnet",
            openRouterPrompt = prefs.getString(KEY_AI_OPENROUTER_PROMPT, DEFAULT_GAME_PROMPT) ?: DEFAULT_GAME_PROMPT,
            openRouterServerPlayerPrompt = prefs.getString(KEY_AI_OPENROUTER_SERVER_PLAYER_PROMPT, DEFAULT_SERVER_PLAYER_PROMPT) ?: DEFAULT_SERVER_PLAYER_PROMPT,
            openRouterOtherPlayerPrompt = prefs.getString(KEY_AI_OPENROUTER_OTHER_PLAYER_PROMPT, DEFAULT_OTHER_PLAYER_PROMPT) ?: DEFAULT_OTHER_PLAYER_PROMPT,
            openRouterModelSource = loadModelSource(KEY_AI_OPENROUTER_MODEL_SOURCE, ModelSource.API),
            openRouterManualModels = loadManualModels(KEY_AI_OPENROUTER_MANUAL_MODELS),
            dummyApiKey = prefs.getString(KEY_AI_DUMMY_API_KEY, "") ?: "",
            dummyModel = prefs.getString(KEY_AI_DUMMY_MODEL, "dummy-model") ?: "dummy-model",
            dummyManualModels = loadManualModelsWithDefault(KEY_AI_DUMMY_MANUAL_MODELS, listOf("dummy-model"))
        )
    }

    private fun loadModelSource(key: String, default: ModelSource): ModelSource {
        val sourceName = prefs.getString(key, null) ?: return default
        return try {
            ModelSource.valueOf(sourceName)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    private fun loadManualModels(key: String): List<String> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadManualModelsWithDefault(key: String, default: List<String>): List<String> {
        val json = prefs.getString(key, null) ?: return default
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun saveAiSettings(settings: AiSettings) {
        prefs.edit()
            .putString(KEY_AI_CHATGPT_API_KEY, settings.chatGptApiKey)
            .putString(KEY_AI_CHATGPT_MODEL, settings.chatGptModel)
            .putString(KEY_AI_CHATGPT_PROMPT, settings.chatGptPrompt)
            .putString(KEY_AI_CHATGPT_SERVER_PLAYER_PROMPT, settings.chatGptServerPlayerPrompt)
            .putString(KEY_AI_CHATGPT_OTHER_PLAYER_PROMPT, settings.chatGptOtherPlayerPrompt)
            .putString(KEY_AI_CHATGPT_MODEL_SOURCE, settings.chatGptModelSource.name)
            .putString(KEY_AI_CHATGPT_MANUAL_MODELS, gson.toJson(settings.chatGptManualModels))
            .putString(KEY_AI_CLAUDE_API_KEY, settings.claudeApiKey)
            .putString(KEY_AI_CLAUDE_MODEL, settings.claudeModel)
            .putString(KEY_AI_CLAUDE_PROMPT, settings.claudePrompt)
            .putString(KEY_AI_CLAUDE_SERVER_PLAYER_PROMPT, settings.claudeServerPlayerPrompt)
            .putString(KEY_AI_CLAUDE_OTHER_PLAYER_PROMPT, settings.claudeOtherPlayerPrompt)
            .putString(KEY_AI_CLAUDE_MODEL_SOURCE, settings.claudeModelSource.name)
            .putString(KEY_AI_CLAUDE_MANUAL_MODELS, gson.toJson(settings.claudeManualModels))
            .putString(KEY_AI_GEMINI_API_KEY, settings.geminiApiKey)
            .putString(KEY_AI_GEMINI_MODEL, settings.geminiModel)
            .putString(KEY_AI_GEMINI_PROMPT, settings.geminiPrompt)
            .putString(KEY_AI_GEMINI_SERVER_PLAYER_PROMPT, settings.geminiServerPlayerPrompt)
            .putString(KEY_AI_GEMINI_OTHER_PLAYER_PROMPT, settings.geminiOtherPlayerPrompt)
            .putString(KEY_AI_GEMINI_MODEL_SOURCE, settings.geminiModelSource.name)
            .putString(KEY_AI_GEMINI_MANUAL_MODELS, gson.toJson(settings.geminiManualModels))
            .putString(KEY_AI_GROK_API_KEY, settings.grokApiKey)
            .putString(KEY_AI_GROK_MODEL, settings.grokModel)
            .putString(KEY_AI_GROK_PROMPT, settings.grokPrompt)
            .putString(KEY_AI_GROK_SERVER_PLAYER_PROMPT, settings.grokServerPlayerPrompt)
            .putString(KEY_AI_GROK_OTHER_PLAYER_PROMPT, settings.grokOtherPlayerPrompt)
            .putString(KEY_AI_GROK_MODEL_SOURCE, settings.grokModelSource.name)
            .putString(KEY_AI_GROK_MANUAL_MODELS, gson.toJson(settings.grokManualModels))
            .putString(KEY_AI_GROQ_API_KEY, settings.groqApiKey)
            .putString(KEY_AI_GROQ_MODEL, settings.groqModel)
            .putString(KEY_AI_GROQ_PROMPT, settings.groqPrompt)
            .putString(KEY_AI_GROQ_SERVER_PLAYER_PROMPT, settings.groqServerPlayerPrompt)
            .putString(KEY_AI_GROQ_OTHER_PLAYER_PROMPT, settings.groqOtherPlayerPrompt)
            .putString(KEY_AI_GROQ_MODEL_SOURCE, settings.groqModelSource.name)
            .putString(KEY_AI_GROQ_MANUAL_MODELS, gson.toJson(settings.groqManualModels))
            .putString(KEY_AI_DEEPSEEK_API_KEY, settings.deepSeekApiKey)
            .putString(KEY_AI_DEEPSEEK_MODEL, settings.deepSeekModel)
            .putString(KEY_AI_DEEPSEEK_PROMPT, settings.deepSeekPrompt)
            .putString(KEY_AI_DEEPSEEK_SERVER_PLAYER_PROMPT, settings.deepSeekServerPlayerPrompt)
            .putString(KEY_AI_DEEPSEEK_OTHER_PLAYER_PROMPT, settings.deepSeekOtherPlayerPrompt)
            .putString(KEY_AI_DEEPSEEK_MODEL_SOURCE, settings.deepSeekModelSource.name)
            .putString(KEY_AI_DEEPSEEK_MANUAL_MODELS, gson.toJson(settings.deepSeekManualModels))
            .putString(KEY_AI_MISTRAL_API_KEY, settings.mistralApiKey)
            .putString(KEY_AI_MISTRAL_MODEL, settings.mistralModel)
            .putString(KEY_AI_MISTRAL_PROMPT, settings.mistralPrompt)
            .putString(KEY_AI_MISTRAL_SERVER_PLAYER_PROMPT, settings.mistralServerPlayerPrompt)
            .putString(KEY_AI_MISTRAL_OTHER_PLAYER_PROMPT, settings.mistralOtherPlayerPrompt)
            .putString(KEY_AI_MISTRAL_MODEL_SOURCE, settings.mistralModelSource.name)
            .putString(KEY_AI_MISTRAL_MANUAL_MODELS, gson.toJson(settings.mistralManualModels))
            .putString(KEY_AI_PERPLEXITY_API_KEY, settings.perplexityApiKey)
            .putString(KEY_AI_PERPLEXITY_MODEL, settings.perplexityModel)
            .putString(KEY_AI_PERPLEXITY_PROMPT, settings.perplexityPrompt)
            .putString(KEY_AI_PERPLEXITY_SERVER_PLAYER_PROMPT, settings.perplexityServerPlayerPrompt)
            .putString(KEY_AI_PERPLEXITY_OTHER_PLAYER_PROMPT, settings.perplexityOtherPlayerPrompt)
            .putString(KEY_AI_PERPLEXITY_MODEL_SOURCE, settings.perplexityModelSource.name)
            .putString(KEY_AI_PERPLEXITY_MANUAL_MODELS, gson.toJson(settings.perplexityManualModels))
            .putString(KEY_AI_TOGETHER_API_KEY, settings.togetherApiKey)
            .putString(KEY_AI_TOGETHER_MODEL, settings.togetherModel)
            .putString(KEY_AI_TOGETHER_PROMPT, settings.togetherPrompt)
            .putString(KEY_AI_TOGETHER_SERVER_PLAYER_PROMPT, settings.togetherServerPlayerPrompt)
            .putString(KEY_AI_TOGETHER_OTHER_PLAYER_PROMPT, settings.togetherOtherPlayerPrompt)
            .putString(KEY_AI_TOGETHER_MODEL_SOURCE, settings.togetherModelSource.name)
            .putString(KEY_AI_TOGETHER_MANUAL_MODELS, gson.toJson(settings.togetherManualModels))
            .putString(KEY_AI_OPENROUTER_API_KEY, settings.openRouterApiKey)
            .putString(KEY_AI_OPENROUTER_MODEL, settings.openRouterModel)
            .putString(KEY_AI_OPENROUTER_PROMPT, settings.openRouterPrompt)
            .putString(KEY_AI_OPENROUTER_SERVER_PLAYER_PROMPT, settings.openRouterServerPlayerPrompt)
            .putString(KEY_AI_OPENROUTER_OTHER_PLAYER_PROMPT, settings.openRouterOtherPlayerPrompt)
            .putString(KEY_AI_OPENROUTER_MODEL_SOURCE, settings.openRouterModelSource.name)
            .putString(KEY_AI_OPENROUTER_MANUAL_MODELS, gson.toJson(settings.openRouterManualModels))
            .putString(KEY_AI_DUMMY_API_KEY, settings.dummyApiKey)
            .putString(KEY_AI_DUMMY_MODEL, settings.dummyModel)
            .putString(KEY_AI_DUMMY_MANUAL_MODELS, gson.toJson(settings.dummyManualModels))
            .apply()

        // Also save prompts and agents if they exist
        if (settings.prompts.isNotEmpty()) {
            saveAiPrompts(settings.prompts)
        }
        if (settings.agents.isNotEmpty()) {
            saveAiAgents(settings.agents)
        }
    }

    // ============================================================================
    // AI Prompts and Agents (Three-tier Architecture)
    // ============================================================================

    fun loadAiPrompts(): List<AiPrompt> {
        val json = prefs.getString(KEY_AI_PROMPTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AiPrompt>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAiPrompts(prompts: List<AiPrompt>) {
        val json = gson.toJson(prompts)
        prefs.edit().putString(KEY_AI_PROMPTS, json).apply()
    }

    fun loadAiAgents(): List<AiAgent> {
        val json = prefs.getString(KEY_AI_AGENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AiAgent>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAiAgents(agents: List<AiAgent>) {
        val json = gson.toJson(agents)
        prefs.edit().putString(KEY_AI_AGENTS, json).apply()
    }

    /**
     * Check if AI migration has been performed.
     */
    fun isAiMigrationDone(): Boolean {
        return prefs.getBoolean(KEY_AI_MIGRATION_DONE, false)
    }

    /**
     * Mark AI migration as done.
     */
    fun setAiMigrationDone() {
        prefs.edit().putBoolean(KEY_AI_MIGRATION_DONE, true).apply()
    }

    /**
     * Create default prompts if none exist.
     * Returns the list of prompts (either existing or newly created defaults).
     */
    fun ensureDefaultPrompts(): List<AiPrompt> {
        var prompts = loadAiPrompts()
        if (prompts.isEmpty()) {
            prompts = listOf(
                AiPrompt(
                    id = java.util.UUID.randomUUID().toString(),
                    name = DEFAULT_GAME_PROMPT_NAME,
                    text = DEFAULT_GAME_PROMPT
                ),
                AiPrompt(
                    id = java.util.UUID.randomUUID().toString(),
                    name = DEFAULT_SERVER_PLAYER_PROMPT_NAME,
                    text = DEFAULT_SERVER_PLAYER_PROMPT
                ),
                AiPrompt(
                    id = java.util.UUID.randomUUID().toString(),
                    name = DEFAULT_OTHER_PLAYER_PROMPT_NAME,
                    text = DEFAULT_OTHER_PLAYER_PROMPT
                )
            )
            saveAiPrompts(prompts)
        }
        return prompts
    }

    /**
     * Migrate existing provider configurations to agents.
     * For each provider with a non-empty API key, creates an agent.
     */
    fun migrateToAgentsIfNeeded(aiSettings: AiSettings): Pair<List<AiPrompt>, List<AiAgent>> {
        if (isAiMigrationDone()) {
            return Pair(loadAiPrompts(), loadAiAgents())
        }

        // Ensure we have default prompts
        val prompts = ensureDefaultPrompts()

        // Find default prompt IDs
        val gamePromptId = prompts.find { it.name == DEFAULT_GAME_PROMPT_NAME }?.id ?: ""
        val serverPlayerPromptId = prompts.find { it.name == DEFAULT_SERVER_PLAYER_PROMPT_NAME }?.id ?: ""
        val otherPlayerPromptId = prompts.find { it.name == DEFAULT_OTHER_PLAYER_PROMPT_NAME }?.id ?: ""

        // Create agents for configured providers
        val agents = mutableListOf<AiAgent>()

        if (aiSettings.chatGptApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "ChatGPT",
                provider = com.eval.data.AiService.CHATGPT,
                model = aiSettings.chatGptModel,
                apiKey = aiSettings.chatGptApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.claudeApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "Claude",
                provider = com.eval.data.AiService.CLAUDE,
                model = aiSettings.claudeModel,
                apiKey = aiSettings.claudeApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.geminiApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "Gemini",
                provider = com.eval.data.AiService.GEMINI,
                model = aiSettings.geminiModel,
                apiKey = aiSettings.geminiApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.grokApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "Grok",
                provider = com.eval.data.AiService.GROK,
                model = aiSettings.grokModel,
                apiKey = aiSettings.grokApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.groqApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "Groq",
                provider = com.eval.data.AiService.GROQ,
                model = aiSettings.groqModel,
                apiKey = aiSettings.groqApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.deepSeekApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "DeepSeek",
                provider = com.eval.data.AiService.DEEPSEEK,
                model = aiSettings.deepSeekModel,
                apiKey = aiSettings.deepSeekApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.mistralApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "Mistral",
                provider = com.eval.data.AiService.MISTRAL,
                model = aiSettings.mistralModel,
                apiKey = aiSettings.mistralApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.perplexityApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "Perplexity",
                provider = com.eval.data.AiService.PERPLEXITY,
                model = aiSettings.perplexityModel,
                apiKey = aiSettings.perplexityApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.togetherApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "Together",
                provider = com.eval.data.AiService.TOGETHER,
                model = aiSettings.togetherModel,
                apiKey = aiSettings.togetherApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        if (aiSettings.openRouterApiKey.isNotBlank()) {
            agents.add(AiAgent(
                id = java.util.UUID.randomUUID().toString(),
                name = "OpenRouter",
                provider = com.eval.data.AiService.OPENROUTER,
                model = aiSettings.openRouterModel,
                apiKey = aiSettings.openRouterApiKey,
                gamePromptId = gamePromptId,
                serverPlayerPromptId = serverPlayerPromptId,
                otherPlayerPromptId = otherPlayerPromptId
            ))
        }

        // Save agents and mark migration as done
        saveAiAgents(agents)
        setAiMigrationDone()

        return Pair(prompts, agents)
    }

    // ============================================================================
    // First Run and App Version Tracking
    // ============================================================================

    fun getFirstGameRetrievedVersion(): Long {
        return prefs.getLong(KEY_FIRST_GAME_RETRIEVED_VERSION, 0)
    }

    fun setFirstGameRetrievedVersion(version: Long) {
        prefs.edit().putLong(KEY_FIRST_GAME_RETRIEVED_VERSION, version).apply()
    }

    // ============================================================================
    // Reset Settings to Defaults
    // ============================================================================

    fun resetAllSettingsToDefaults() {
        // Reset stockfish settings
        saveStockfishSettings(StockfishSettings())
        // Reset board layout settings
        saveBoardLayoutSettings(BoardLayoutSettings())
        // Reset graph settings
        saveGraphSettings(GraphSettings())
        // Reset interface visibility settings
        saveInterfaceVisibilitySettings(InterfaceVisibilitySettings())
    }

    companion object {
        const val PREFS_NAME = "eval_prefs"

        // Current game storage
        const val KEY_CURRENT_GAME_JSON = "current_game_json"

        // Lichess settings
        private const val KEY_LICHESS_USERNAME = "lichess_username"
        private const val KEY_LICHESS_MAX_GAMES = "lichess_max_games"

        // Retrieved games storage - list of lists
        const val KEY_RETRIEVES_LIST = "retrieves_list"
        const val KEY_RETRIEVED_GAMES_PREFIX = "retrieved_games_"
        const val MAX_RETRIEVES = 25

        // Analysed games storage
        const val KEY_ANALYSED_GAMES = "analysed_games"
        const val MAX_ANALYSED_GAMES = 50

        // Preview stage settings
        private const val KEY_PREVIEW_SECONDS = "preview_seconds"
        private const val KEY_PREVIEW_THREADS = "preview_threads"
        private const val KEY_PREVIEW_HASH = "preview_hash"
        private const val KEY_PREVIEW_NNUE = "preview_nnue"

        // Analyse stage settings
        private const val KEY_ANALYSE_SECONDS = "analyse_seconds"
        private const val KEY_ANALYSE_THREADS = "analyse_threads"
        private const val KEY_ANALYSE_HASH = "analyse_hash"
        private const val KEY_ANALYSE_NNUE = "analyse_nnue"

        // Manual stage settings
        private const val KEY_MANUAL_DEPTH = "manual_depth"
        private const val KEY_MANUAL_THREADS = "manual_threads"
        private const val KEY_MANUAL_HASH = "manual_hash"
        private const val KEY_MANUAL_MULTIPV = "manual_multipv"
        private const val KEY_MANUAL_NNUE = "manual_nnue"
        private const val KEY_MANUAL_ARROW_MODE = "manual_arrow_mode"
        private const val KEY_MANUAL_NUMARROWS = "manual_numarrows"
        private const val KEY_MANUAL_SHOWNUMBERS = "manual_shownumbers"
        private const val KEY_MANUAL_WHITE_ARROW_COLOR = "manual_white_arrow_color"
        private const val KEY_MANUAL_BLACK_ARROW_COLOR = "manual_black_arrow_color"
        private const val KEY_MANUAL_MULTILINES_ARROW_COLOR = "manual_multilines_arrow_color"

        // Board layout settings
        private const val KEY_BOARD_SHOW_COORDINATES = "board_show_coordinates"
        private const val KEY_BOARD_SHOW_LAST_MOVE = "board_show_last_move"
        private const val KEY_BOARD_PLAYER_BAR_MODE = "board_player_bar_mode"
        private const val KEY_BOARD_RED_BORDER_PLAYER_TO_MOVE = "board_red_border_player_to_move"
        private const val KEY_BOARD_WHITE_SQUARE_COLOR = "board_white_square_color"
        private const val KEY_BOARD_BLACK_SQUARE_COLOR = "board_black_square_color"
        private const val KEY_BOARD_WHITE_PIECE_COLOR = "board_white_piece_color"
        private const val KEY_BOARD_BLACK_PIECE_COLOR = "board_black_piece_color"

        // Evaluation bar settings
        private const val KEY_EVAL_BAR_POSITION = "eval_bar_position"
        private const val KEY_EVAL_BAR_COLOR_1 = "eval_bar_color_1"
        private const val KEY_EVAL_BAR_COLOR_2 = "eval_bar_color_2"
        private const val KEY_EVAL_BAR_RANGE = "eval_bar_range"

        // Graph settings
        private const val KEY_GRAPH_PLUS_SCORE_COLOR = "graph_plus_score_color"
        private const val KEY_GRAPH_NEGATIVE_SCORE_COLOR = "graph_negative_score_color"
        private const val KEY_GRAPH_BACKGROUND_COLOR = "graph_background_color"
        private const val KEY_GRAPH_ANALYSE_LINE_COLOR = "graph_analyse_line_color"
        private const val KEY_GRAPH_VERTICAL_LINE_COLOR = "graph_vertical_line_color"
        private const val KEY_GRAPH_LINE_RANGE = "graph_line_range"
        private const val KEY_GRAPH_BAR_RANGE = "graph_bar_range"
        private const val KEY_GRAPH_LINE_SCALE = "graph_line_scale"
        private const val KEY_GRAPH_BAR_SCALE = "graph_bar_scale"

        // Interface visibility settings - Preview stage
        private const val KEY_PREVIEW_VIS_SCOREBARSGRAPH = "preview_vis_scorebarsgraph"
        private const val KEY_PREVIEW_VIS_RESULTBAR = "preview_vis_resultbar"
        private const val KEY_PREVIEW_VIS_BOARD = "preview_vis_board"
        private const val KEY_PREVIEW_VIS_MOVELIST = "preview_vis_movelist"
        private const val KEY_PREVIEW_VIS_PGN = "preview_vis_pgn"

        // Interface visibility settings - Analyse stage
        private const val KEY_ANALYSE_VIS_SCORELINEGRAPH = "analyse_vis_scorelinegraph"
        private const val KEY_ANALYSE_VIS_SCOREBARSGRAPH = "analyse_vis_scorebarsgraph"
        private const val KEY_ANALYSE_VIS_BOARD = "analyse_vis_board"
        private const val KEY_ANALYSE_VIS_STOCKFISHANALYSE = "analyse_vis_stockfishanalyse"
        private const val KEY_ANALYSE_VIS_RESULTBAR = "analyse_vis_resultbar"
        private const val KEY_ANALYSE_VIS_MOVELIST = "analyse_vis_movelist"
        private const val KEY_ANALYSE_VIS_GAMEINFO = "analyse_vis_gameinfo"
        private const val KEY_ANALYSE_VIS_PGN = "analyse_vis_pgn"

        // Interface visibility settings - Manual stage
        private const val KEY_MANUAL_VIS_RESULTBAR = "manual_vis_resultbar"
        private const val KEY_MANUAL_VIS_SCORELINEGRAPH = "manual_vis_scorelinegraph"
        private const val KEY_MANUAL_VIS_SCOREBARSGRAPH = "manual_vis_scorebarsgraph"
        private const val KEY_MANUAL_VIS_TIMEGRAPH = "manual_vis_timegraph"
        private const val KEY_MANUAL_VIS_OPENINGEXPLORER = "manual_vis_openingexplorer"
        private const val KEY_MANUAL_VIS_OPENINGNAME = "manual_vis_openingname"
        private const val KEY_MANUAL_VIS_RAWSTOCKFISHSCORE = "manual_vis_rawstockfishscore"
        private const val KEY_MANUAL_VIS_MOVELIST = "manual_vis_movelist"
        private const val KEY_MANUAL_VIS_GAMEINFO = "manual_vis_gameinfo"
        private const val KEY_MANUAL_VIS_PGN = "manual_vis_pgn"

        // First run tracking
        private const val KEY_FIRST_GAME_RETRIEVED_VERSION = "first_game_retrieved_version"

        // General settings
        private const val KEY_PAGINATION_PAGE_SIZE = "pagination_page_size"
        private const val KEY_MOVE_SOUNDS_ENABLED = "move_sounds_enabled"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_TRACK_API_CALLS = "track_api_calls"
        private const val KEY_FLIP_SCORE_WHEN_BLACK = "flip_score_when_black"

        // AI Analysis settings
        private const val KEY_AI_REPORT_PROVIDERS = "ai_report_providers"
        private const val KEY_AI_CHATGPT_API_KEY = "ai_chatgpt_api_key"
        private const val KEY_AI_CHATGPT_MODEL = "ai_chatgpt_model"
        private const val KEY_AI_CLAUDE_API_KEY = "ai_claude_api_key"
        private const val KEY_AI_CLAUDE_MODEL = "ai_claude_model"
        private const val KEY_AI_GEMINI_API_KEY = "ai_gemini_api_key"
        private const val KEY_AI_GEMINI_MODEL = "ai_gemini_model"
        private const val KEY_AI_GROK_API_KEY = "ai_grok_api_key"
        private const val KEY_AI_GROK_MODEL = "ai_grok_model"
        private const val KEY_AI_GROQ_API_KEY = "ai_groq_api_key"
        private const val KEY_AI_GROQ_MODEL = "ai_groq_model"
        private const val KEY_AI_DEEPSEEK_API_KEY = "ai_deepseek_api_key"
        private const val KEY_AI_DEEPSEEK_MODEL = "ai_deepseek_model"

        // AI prompts - Game prompts
        private const val KEY_AI_CHATGPT_PROMPT = "ai_chatgpt_prompt"
        private const val KEY_AI_CLAUDE_PROMPT = "ai_claude_prompt"
        private const val KEY_AI_GEMINI_PROMPT = "ai_gemini_prompt"
        private const val KEY_AI_GROK_PROMPT = "ai_grok_prompt"
        private const val KEY_AI_GROQ_PROMPT = "ai_groq_prompt"
        private const val KEY_AI_DEEPSEEK_PROMPT = "ai_deepseek_prompt"
        private const val KEY_AI_MISTRAL_API_KEY = "ai_mistral_api_key"
        private const val KEY_AI_MISTRAL_MODEL = "ai_mistral_model"
        private const val KEY_AI_MISTRAL_PROMPT = "ai_mistral_prompt"
        private const val KEY_AI_PERPLEXITY_API_KEY = "ai_perplexity_api_key"
        private const val KEY_AI_PERPLEXITY_MODEL = "ai_perplexity_model"
        private const val KEY_AI_PERPLEXITY_PROMPT = "ai_perplexity_prompt"
        private const val KEY_AI_TOGETHER_API_KEY = "ai_together_api_key"
        private const val KEY_AI_TOGETHER_MODEL = "ai_together_model"
        private const val KEY_AI_TOGETHER_PROMPT = "ai_together_prompt"
        private const val KEY_AI_OPENROUTER_API_KEY = "ai_openrouter_api_key"
        private const val KEY_AI_OPENROUTER_MODEL = "ai_openrouter_model"
        private const val KEY_AI_OPENROUTER_PROMPT = "ai_openrouter_prompt"
        private const val KEY_AI_DUMMY_API_KEY = "ai_dummy_api_key"
        private const val KEY_AI_DUMMY_MODEL = "ai_dummy_model"
        private const val KEY_AI_DUMMY_MANUAL_MODELS = "ai_dummy_manual_models"

        // AI prompts - Server player prompts
        private const val KEY_AI_CHATGPT_SERVER_PLAYER_PROMPT = "ai_chatgpt_server_player_prompt"
        private const val KEY_AI_CLAUDE_SERVER_PLAYER_PROMPT = "ai_claude_server_player_prompt"
        private const val KEY_AI_GEMINI_SERVER_PLAYER_PROMPT = "ai_gemini_server_player_prompt"
        private const val KEY_AI_GROK_SERVER_PLAYER_PROMPT = "ai_grok_server_player_prompt"
        private const val KEY_AI_GROQ_SERVER_PLAYER_PROMPT = "ai_groq_server_player_prompt"
        private const val KEY_AI_DEEPSEEK_SERVER_PLAYER_PROMPT = "ai_deepseek_server_player_prompt"
        private const val KEY_AI_MISTRAL_SERVER_PLAYER_PROMPT = "ai_mistral_server_player_prompt"
        private const val KEY_AI_PERPLEXITY_SERVER_PLAYER_PROMPT = "ai_perplexity_server_player_prompt"
        private const val KEY_AI_TOGETHER_SERVER_PLAYER_PROMPT = "ai_together_server_player_prompt"
        private const val KEY_AI_OPENROUTER_SERVER_PLAYER_PROMPT = "ai_openrouter_server_player_prompt"

        // AI prompts - Other player prompts
        private const val KEY_AI_CHATGPT_OTHER_PLAYER_PROMPT = "ai_chatgpt_other_player_prompt"
        private const val KEY_AI_CLAUDE_OTHER_PLAYER_PROMPT = "ai_claude_other_player_prompt"
        private const val KEY_AI_GEMINI_OTHER_PLAYER_PROMPT = "ai_gemini_other_player_prompt"
        private const val KEY_AI_GROK_OTHER_PLAYER_PROMPT = "ai_grok_other_player_prompt"
        private const val KEY_AI_GROQ_OTHER_PLAYER_PROMPT = "ai_groq_other_player_prompt"
        private const val KEY_AI_DEEPSEEK_OTHER_PLAYER_PROMPT = "ai_deepseek_other_player_prompt"
        private const val KEY_AI_MISTRAL_OTHER_PLAYER_PROMPT = "ai_mistral_other_player_prompt"
        private const val KEY_AI_PERPLEXITY_OTHER_PLAYER_PROMPT = "ai_perplexity_other_player_prompt"
        private const val KEY_AI_TOGETHER_OTHER_PLAYER_PROMPT = "ai_together_other_player_prompt"
        private const val KEY_AI_OPENROUTER_OTHER_PLAYER_PROMPT = "ai_openrouter_other_player_prompt"

        // AI model source (API or MANUAL)
        private const val KEY_AI_CHATGPT_MODEL_SOURCE = "ai_chatgpt_model_source"
        private const val KEY_AI_CLAUDE_MODEL_SOURCE = "ai_claude_model_source"
        private const val KEY_AI_GEMINI_MODEL_SOURCE = "ai_gemini_model_source"
        private const val KEY_AI_GROK_MODEL_SOURCE = "ai_grok_model_source"
        private const val KEY_AI_GROQ_MODEL_SOURCE = "ai_groq_model_source"
        private const val KEY_AI_DEEPSEEK_MODEL_SOURCE = "ai_deepseek_model_source"
        private const val KEY_AI_MISTRAL_MODEL_SOURCE = "ai_mistral_model_source"
        private const val KEY_AI_PERPLEXITY_MODEL_SOURCE = "ai_perplexity_model_source"
        private const val KEY_AI_TOGETHER_MODEL_SOURCE = "ai_together_model_source"
        private const val KEY_AI_OPENROUTER_MODEL_SOURCE = "ai_openrouter_model_source"

        // AI manual models lists
        private const val KEY_AI_CHATGPT_MANUAL_MODELS = "ai_chatgpt_manual_models"
        private const val KEY_AI_CLAUDE_MANUAL_MODELS = "ai_claude_manual_models"
        private const val KEY_AI_GEMINI_MANUAL_MODELS = "ai_gemini_manual_models"
        private const val KEY_AI_GROK_MANUAL_MODELS = "ai_grok_manual_models"
        private const val KEY_AI_GROQ_MANUAL_MODELS = "ai_groq_manual_models"
        private const val KEY_AI_DEEPSEEK_MANUAL_MODELS = "ai_deepseek_manual_models"
        private const val KEY_AI_MISTRAL_MANUAL_MODELS = "ai_mistral_manual_models"
        private const val KEY_AI_PERPLEXITY_MANUAL_MODELS = "ai_perplexity_manual_models"
        private const val KEY_AI_TOGETHER_MANUAL_MODELS = "ai_together_manual_models"
        private const val KEY_AI_OPENROUTER_MANUAL_MODELS = "ai_openrouter_manual_models"

        // AI report email
        const val KEY_AI_REPORT_EMAIL = "ai_report_email"

        // Last AI report title and prompt (for New AI Report screen)
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"

        // AI prompts and agents (three-tier architecture)
        private const val KEY_AI_PROMPTS = "ai_prompts"
        private const val KEY_AI_AGENTS = "ai_agents"
        private const val KEY_AI_MIGRATION_DONE = "ai_migration_done"
        private const val KEY_AI_REPORT_AGENTS = "ai_report_agents"

        // Cached AI models lists
        private const val KEY_CACHED_CHATGPT_MODELS = "cached_chatgpt_models"
        private const val KEY_CACHED_GEMINI_MODELS = "cached_gemini_models"
        private const val KEY_CACHED_GROK_MODELS = "cached_grok_models"
        private const val KEY_CACHED_GROQ_MODELS = "cached_groq_models"
        private const val KEY_CACHED_DEEPSEEK_MODELS = "cached_deepseek_models"
        private const val KEY_CACHED_MISTRAL_MODELS = "cached_mistral_models"
        private const val KEY_CACHED_PERPLEXITY_MODELS = "cached_perplexity_models"
        private const val KEY_CACHED_TOGETHER_MODELS = "cached_together_models"
        private const val KEY_CACHED_OPENROUTER_MODELS = "cached_openrouter_models"

        // FEN history
        private const val KEY_FEN_HISTORY = "fen_history"
        const val MAX_FEN_HISTORY = 10

        // Prompt history for New AI Report
        private const val KEY_PROMPT_HISTORY = "prompt_history"
        const val MAX_PROMPT_HISTORY = 100
    }

    // ============================================================================
    // FEN History
    // ============================================================================

    fun loadFenHistory(): List<String> {
        val json = prefs.getString(KEY_FEN_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFenToHistory(fen: String) {
        val history = loadFenHistory().toMutableList()
        // Remove if already exists (to move to top)
        history.remove(fen)
        // Add at the beginning
        history.add(0, fen)
        // Keep only the last MAX_FEN_HISTORY entries
        val trimmed = history.take(MAX_FEN_HISTORY)
        val json = gson.toJson(trimmed)
        prefs.edit().putString(KEY_FEN_HISTORY, json).apply()
    }

    // ============================================================================
    // Prompt History (for New AI Report)
    // ============================================================================

    fun loadPromptHistory(): List<PromptHistoryEntry> {
        val json = prefs.getString(KEY_PROMPT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PromptHistoryEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePromptToHistory(title: String, prompt: String) {
        val history = loadPromptHistory().toMutableList()
        // Add at the beginning with current timestamp
        history.add(0, PromptHistoryEntry(
            timestamp = System.currentTimeMillis(),
            title = title,
            prompt = prompt
        ))
        // Keep only the last MAX_PROMPT_HISTORY entries
        val trimmed = history.take(MAX_PROMPT_HISTORY)
        val json = gson.toJson(trimmed)
        prefs.edit().putString(KEY_PROMPT_HISTORY, json).apply()
    }

    // ============================================================================
    // AI Report Providers Selection
    // ============================================================================

    fun loadAiReportProviders(): Set<String> {
        val json = prefs.getString(KEY_AI_REPORT_PROVIDERS, null) ?: return emptySet()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
            gson.fromJson(json, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveAiReportProviders(providers: Set<String>) {
        val json = gson.toJson(providers)
        prefs.edit().putString(KEY_AI_REPORT_PROVIDERS, json).apply()
    }

    fun loadAiReportAgents(): Set<String> {
        val json = prefs.getString(KEY_AI_REPORT_AGENTS, null) ?: return emptySet()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
            gson.fromJson(json, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun saveAiReportAgents(agentIds: Set<String>) {
        val json = gson.toJson(agentIds)
        prefs.edit().putString(KEY_AI_REPORT_AGENTS, json).apply()
    }

    // ============================================================================
    // Cached AI Models
    // ============================================================================

    fun loadCachedChatGptModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_CHATGPT_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedChatGptModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_CHATGPT_MODELS, json).apply()
    }

    fun loadCachedGeminiModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_GEMINI_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedGeminiModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_GEMINI_MODELS, json).apply()
    }

    fun loadCachedGrokModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_GROK_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedGrokModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_GROK_MODELS, json).apply()
    }

    fun loadCachedGroqModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_GROQ_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedGroqModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_GROQ_MODELS, json).apply()
    }

    fun loadCachedDeepSeekModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_DEEPSEEK_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedDeepSeekModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_DEEPSEEK_MODELS, json).apply()
    }

    fun loadCachedMistralModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_MISTRAL_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedMistralModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_MISTRAL_MODELS, json).apply()
    }

    fun loadCachedPerplexityModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_PERPLEXITY_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedPerplexityModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_PERPLEXITY_MODELS, json).apply()
    }

    fun loadCachedTogetherModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_TOGETHER_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedTogetherModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_TOGETHER_MODELS, json).apply()
    }

    fun loadCachedOpenRouterModels(): List<String> {
        val json = prefs.getString(KEY_CACHED_OPENROUTER_MODELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedOpenRouterModels(models: List<String>) {
        val json = gson.toJson(models)
        prefs.edit().putString(KEY_CACHED_OPENROUTER_MODELS, json).apply()
    }
}
