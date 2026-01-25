package com.eval.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eval.chess.ChessBoard
import com.eval.chess.Square
import com.eval.data.AiAnalysisRepository
import com.eval.data.AiAnalysisResponse
import com.eval.data.AiHistoryManager
import com.eval.data.AiService
import com.eval.data.ApiTracer
import com.eval.data.BroadcastInfo
import com.eval.data.BroadcastRoundInfo
import com.eval.data.ChessRepository
import com.eval.data.ChessServer
import com.eval.data.LichessGame
import com.eval.data.OpeningBook
import com.eval.data.Result
import com.eval.data.StreamerInfo
import com.eval.data.TournamentInfo
import com.eval.data.TvChannelInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.eval.stockfish.StockfishEngine
import org.json.JSONObject
import com.eval.audio.MoveSoundPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChessRepository()
    private val stockfish = StockfishEngine(application)
    private val aiAnalysisRepository = AiAnalysisRepository()
    private val prefs = application.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // Helper classes for settings and game storage
    private val settingsPrefs = SettingsPreferences(prefs)
    private val gameStorage = GameStorageManager(prefs, gson)

    // Move sound player for audio feedback
    private val moveSoundPlayer = MoveSoundPlayer(application)

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private var boardHistory = mutableListOf<ChessBoard>()
    private var exploringLineHistory = mutableListOf<ChessBoard>()

    // Track settings when dialog opens to detect changes
    private var settingsOnDialogOpen: SettingsSnapshot? = null

    private data class SettingsSnapshot(
        val previewStageSettings: PreviewStageSettings,
        val analyseStageSettings: AnalyseStageSettings,
        val manualStageSettings: ManualStageSettings
    )

    // Helper classes for better organization
    private val analysisOrchestrator: AnalysisOrchestrator
    private val gameLoader: GameLoader
    private val boardNavigationManager: BoardNavigationManager
    private val contentSourceManager: ContentSourceManager
    private val liveGameManager: LiveGameManager

    // Opening explorer job
    private var openingExplorerJob: Job? = null

    val savedLichessUsername: String
        get() = settingsPrefs.savedLichessUsername

    private fun loadStockfishSettings(): StockfishSettings = settingsPrefs.loadStockfishSettings()
    private fun saveStockfishSettings(settings: StockfishSettings) = settingsPrefs.saveStockfishSettings(settings)
    private fun loadBoardLayoutSettings(): BoardLayoutSettings = settingsPrefs.loadBoardLayoutSettings()
    private fun saveBoardLayoutSettings(settings: BoardLayoutSettings) = settingsPrefs.saveBoardLayoutSettings(settings)
    private fun loadGraphSettings(): GraphSettings = settingsPrefs.loadGraphSettings()
    private fun saveGraphSettings(settings: GraphSettings) = settingsPrefs.saveGraphSettings(settings)
    private fun loadInterfaceVisibilitySettings(): InterfaceVisibilitySettings = settingsPrefs.loadInterfaceVisibilitySettings()
    private fun saveInterfaceVisibilitySettings(settings: InterfaceVisibilitySettings) = settingsPrefs.saveInterfaceVisibilitySettings(settings)
    private fun loadGeneralSettings(): GeneralSettings = settingsPrefs.loadGeneralSettings()
    private fun saveGeneralSettings(settings: GeneralSettings) = settingsPrefs.saveGeneralSettings(settings)
    private fun loadAiSettings(): AiSettings = settingsPrefs.loadAiSettingsWithMigration()
    private fun saveAiSettings(settings: AiSettings) = settingsPrefs.saveAiSettings(settings)

    private fun getAppVersionCode(): Long {
        return try {
            val packageInfo = getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun isFirstRun(): Boolean {
        val savedVersionCode = settingsPrefs.getFirstGameRetrievedVersion()
        val currentVersion = getAppVersionCode()
        // If stored version code is 0 (legacy bug), check if there's a stored game
        // If there's a stored game, this isn't truly a first run
        if (savedVersionCode == 0L && gameStorage.hasAnalysedGames()) {
            // Fix the stored version code for future runs
            settingsPrefs.setFirstGameRetrievedVersion(currentVersion)
            return false
        }
        return savedVersionCode != currentVersion
    }

    private fun markFirstRunComplete() {
        settingsPrefs.setFirstGameRetrievedVersion(getAppVersionCode())
    }

    private fun resetSettingsToDefaults() {
        settingsPrefs.resetAllSettingsToDefaults()
    }

    init {
        // Initialize helper classes first
        analysisOrchestrator = AnalysisOrchestrator(
            stockfish = stockfish,
            getUiState = { _uiState.value },
            updateUiState = { transform -> _uiState.value = _uiState.value.transform() },
            viewModelScope = viewModelScope,
            getBoardHistory = { boardHistory },
            storeAnalysedGame = { storeAnalysedGame() },
            fetchOpeningExplorer = { fetchOpeningExplorer() }
        )

        gameLoader = GameLoader(
            repository = repository,
            getUiState = { _uiState.value },
            updateUiState = { transform -> _uiState.value = _uiState.value.transform() },
            viewModelScope = viewModelScope,
            getBoardHistory = { boardHistory },
            getExploringLineHistory = { exploringLineHistory },
            settingsPrefs = settingsPrefs,
            gameStorage = gameStorage,
            analysisOrchestrator = analysisOrchestrator,
            fetchOpeningExplorer = { fetchOpeningExplorer() },
            restartStockfishAndAnalyze = { fen -> restartStockfishAndAnalyze(fen) },
            getAppVersionCode = { getAppVersionCode() }
        )

        boardNavigationManager = BoardNavigationManager(
            getUiState = { _uiState.value },
            updateUiState = { transform -> _uiState.value = _uiState.value.transform() },
            getBoardHistory = { boardHistory },
            getExploringLineHistory = { exploringLineHistory },
            analysisOrchestrator = analysisOrchestrator,
            moveSoundPlayer = moveSoundPlayer
        )

        contentSourceManager = ContentSourceManager(
            repository = repository,
            getUiState = { _uiState.value },
            updateUiState = { transform -> _uiState.value = _uiState.value.transform() },
            viewModelScope = viewModelScope,
            loadGame = { game, server, username -> gameLoader.loadGame(game, server, username) }
        )

        liveGameManager = LiveGameManager(
            repository = repository,
            getUiState = { _uiState.value },
            updateUiState = { transform -> _uiState.value = _uiState.value.transform() },
            viewModelScope = viewModelScope,
            moveSoundPlayer = moveSoundPlayer
        )

        // Initialize ApiTracer for debugging
        ApiTracer.init(application)

        // Initialize AiHistoryManager for AI report storage
        AiHistoryManager.init(application)

        // Check if Stockfish is installed first
        val stockfishInstalled = stockfish.isStockfishInstalled()
        _uiState.value = _uiState.value.copy(stockfishInstalled = stockfishInstalled)

        if (stockfishInstalled) {
            if (isFirstRun()) {
                resetSettingsToDefaults()
            }

            val settings = loadStockfishSettings()
            val boardSettings = loadBoardLayoutSettings()
            val graphSettings = loadGraphSettings()
            val interfaceVisibility = loadInterfaceVisibilitySettings()
            val generalSettings = loadGeneralSettings()

            // Configure ApiTracer based on settings (requires both developer mode and track API calls)
            ApiTracer.isTracingEnabled = generalSettings.developerMode && generalSettings.trackApiCalls
            val aiSettings = loadAiSettings()
            val lichessMaxGames = settingsPrefs.lichessMaxGames
            val retrievesList = gameStorage.loadRetrievesList()
            val hasPreviousRetrieves = retrievesList.isNotEmpty()
            val hasAnalysedGames = gameStorage.hasAnalysedGames()

            // Load cached AI models
            val cachedChatGptModels = settingsPrefs.loadCachedChatGptModels()
            val cachedGeminiModels = settingsPrefs.loadCachedGeminiModels()
            val cachedGrokModels = settingsPrefs.loadCachedGrokModels()
            val cachedGroqModels = settingsPrefs.loadCachedGroqModels()
            val cachedDeepSeekModels = settingsPrefs.loadCachedDeepSeekModels()
            val cachedMistralModels = settingsPrefs.loadCachedMistralModels()
            val cachedPerplexityModels = settingsPrefs.loadCachedPerplexityModels()
            val cachedTogetherModels = settingsPrefs.loadCachedTogetherModels()
            val cachedOpenRouterModels = settingsPrefs.loadCachedOpenRouterModels()

            _uiState.value = _uiState.value.copy(
                stockfishSettings = settings,
                boardLayoutSettings = boardSettings,
                graphSettings = graphSettings,
                interfaceVisibility = interfaceVisibility,
                generalSettings = generalSettings,
                aiSettings = aiSettings,
                lichessMaxGames = lichessMaxGames,
                hasPreviousRetrieves = hasPreviousRetrieves,
                previousRetrievesList = retrievesList,
                hasAnalysedGames = hasAnalysedGames,
                playerGamesPageSize = generalSettings.paginationPageSize,
                gameSelectionPageSize = generalSettings.paginationPageSize,
                availableChatGptModels = cachedChatGptModels,
                availableGeminiModels = cachedGeminiModels,
                availableGrokModels = cachedGrokModels,
                availableGroqModels = cachedGroqModels,
                availableDeepSeekModels = cachedDeepSeekModels,
                availableMistralModels = cachedMistralModels,
                availablePerplexityModels = cachedPerplexityModels,
                availableTogetherModels = cachedTogetherModels,
                availableOpenRouterModels = cachedOpenRouterModels
            )

            viewModelScope.launch {
                val ready = stockfish.initialize()
                if (ready) {
                    analysisOrchestrator.configureForManualStage()
                }
                _uiState.value = _uiState.value.copy(stockfishReady = ready)

                if (ready && !isFirstRun()) {
                    gameLoader.autoLoadLastGame()
                }
            }

            viewModelScope.launch {
                stockfish.analysisResult.collect { result ->
                    if (_uiState.value.currentStage != AnalysisStage.MANUAL) {
                        if (result != null) {
                            val expectedFen = analysisOrchestrator.currentAnalysisFen
                            if (expectedFen != null && expectedFen == _uiState.value.currentBoard.getFen()) {
                                _uiState.value = _uiState.value.copy(
                                    analysisResult = result,
                                    analysisResultFen = expectedFen
                                )
                            }
                        } else {
                            _uiState.value = _uiState.value.copy(
                                analysisResult = null,
                                analysisResultFen = null
                            )
                        }
                    }
                }
            }

            viewModelScope.launch {
                stockfish.isReady.collect { ready ->
                    _uiState.value = _uiState.value.copy(stockfishReady = ready)
                }
            }
        }
    }

    fun checkStockfishInstalled(): Boolean = stockfish.isStockfishInstalled()

    fun initializeStockfish() {
        val installed = stockfish.isStockfishInstalled()
        if (!installed) return

        _uiState.value = _uiState.value.copy(stockfishInstalled = true)

        if (isFirstRun()) {
            resetSettingsToDefaults()
        }

        val settings = loadStockfishSettings()
        val boardSettings = loadBoardLayoutSettings()
        val graphSettings = loadGraphSettings()
        val interfaceVisibility = loadInterfaceVisibilitySettings()
        val generalSettings = loadGeneralSettings()
        val aiSettings = loadAiSettings()
        val lichessMaxGames = settingsPrefs.lichessMaxGames

        // Load cached AI models
        val cachedChatGptModels = settingsPrefs.loadCachedChatGptModels()
        val cachedGeminiModels = settingsPrefs.loadCachedGeminiModels()
        val cachedGrokModels = settingsPrefs.loadCachedGrokModels()
        val cachedGroqModels = settingsPrefs.loadCachedGroqModels()
        val cachedDeepSeekModels = settingsPrefs.loadCachedDeepSeekModels()
        val cachedMistralModels = settingsPrefs.loadCachedMistralModels()
        val cachedPerplexityModels = settingsPrefs.loadCachedPerplexityModels()
        val cachedTogetherModels = settingsPrefs.loadCachedTogetherModels()
        val cachedOpenRouterModels = settingsPrefs.loadCachedOpenRouterModels()

        _uiState.value = _uiState.value.copy(
            stockfishSettings = settings,
            boardLayoutSettings = boardSettings,
            graphSettings = graphSettings,
            interfaceVisibility = interfaceVisibility,
            generalSettings = generalSettings,
            aiSettings = aiSettings,
            lichessMaxGames = lichessMaxGames,
            playerGamesPageSize = generalSettings.paginationPageSize,
            gameSelectionPageSize = generalSettings.paginationPageSize,
            availableChatGptModels = cachedChatGptModels,
            availableGeminiModels = cachedGeminiModels,
            availableGrokModels = cachedGrokModels,
            availableGroqModels = cachedGroqModels,
            availableDeepSeekModels = cachedDeepSeekModels,
            availableMistralModels = cachedMistralModels,
            availablePerplexityModels = cachedPerplexityModels,
            availableTogetherModels = cachedTogetherModels,
            availableOpenRouterModels = cachedOpenRouterModels
        )

        viewModelScope.launch {
            val ready = stockfish.initialize()
            if (ready) {
                analysisOrchestrator.configureForManualStage()
            }
            _uiState.value = _uiState.value.copy(stockfishReady = ready)

            if (ready && !isFirstRun()) {
                gameLoader.autoLoadLastGame()
            }
        }

        viewModelScope.launch {
            stockfish.analysisResult.collect { result ->
                if (_uiState.value.currentStage != AnalysisStage.MANUAL) {
                    if (result != null) {
                        val expectedFen = analysisOrchestrator.currentAnalysisFen
                        if (expectedFen != null && expectedFen == _uiState.value.currentBoard.getFen()) {
                            _uiState.value = _uiState.value.copy(
                                analysisResult = result,
                                analysisResultFen = expectedFen
                            )
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            analysisResult = null,
                            analysisResultFen = null
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            stockfish.isReady.collect { ready ->
                _uiState.value = _uiState.value.copy(stockfishReady = ready)
            }
        }
    }

    // ===== GAME LOADING DELEGATION =====
    fun reloadLastGame() = gameLoader.reloadLastGame()
    fun fetchGames(server: ChessServer, username: String) = gameLoader.fetchGames(server, username)
    fun selectGame(game: LichessGame) = gameLoader.selectGame(game)
    fun dismissGameSelection() = gameLoader.dismissGameSelection()
    fun clearGame() = gameLoader.clearGame()
    fun selectGameFromRetrieve(game: LichessGame) = gameLoader.selectGameFromRetrieve(game)
    fun selectAnalysedGame(game: AnalysedGame) = gameLoader.selectAnalysedGame(game)
    fun showPreviousRetrieves() = gameLoader.showPreviousRetrieves()
    fun dismissPreviousRetrievesSelection() = gameLoader.dismissPreviousRetrievesSelection()
    fun selectPreviousRetrieve(entry: RetrievedGamesEntry) = gameLoader.selectPreviousRetrieve(entry)
    fun dismissSelectedRetrieveGames() = gameLoader.dismissSelectedRetrieveGames()
    fun showAnalysedGames() = gameLoader.showAnalysedGames()
    fun dismissAnalysedGamesSelection() = gameLoader.dismissAnalysedGamesSelection()
    fun nextGameSelectionPage() = gameLoader.nextGameSelectionPage()
    fun previousGameSelectionPage() = gameLoader.previousGameSelectionPage()
    fun setLichessMaxGames(max: Int) = gameLoader.setLichessMaxGames(max)

    // PGN file loading
    fun loadGamesFromPgnContent(pgnContent: String, onMultipleEvents: ((Boolean) -> Unit)? = null) =
        gameLoader.loadGamesFromPgnContent(pgnContent, onMultipleEvents)
    fun selectPgnEvent(event: String) = gameLoader.selectPgnEvent(event)
    fun backToPgnEventList() = gameLoader.backToPgnEventList()
    fun dismissPgnEventSelection() = gameLoader.dismissPgnEventSelection()
    fun selectPgnGameFromEvent(game: LichessGame) = gameLoader.selectPgnGameFromEvent(game)
    fun selectPgnGame(game: LichessGame) = gameLoader.selectPgnGame(game)

    // ===== NAVIGATION DELEGATION =====
    fun goToStart() = boardNavigationManager.goToStart()
    fun goToEnd() = boardNavigationManager.goToEnd()
    fun goToMove(index: Int) = boardNavigationManager.goToMove(index)
    fun nextMove() = boardNavigationManager.nextMove()
    fun prevMove() = boardNavigationManager.prevMove()
    fun exploreLine(pv: String, moveIndex: Int = 0) = boardNavigationManager.exploreLine(pv, moveIndex)
    fun backToOriginalGame() = boardNavigationManager.backToOriginalGame()
    fun flipBoard() = boardNavigationManager.flipBoard()
    fun makeManualMove(from: Square, to: Square) = boardNavigationManager.makeManualMove(from, to)

    fun restartAnalysisAtMove(moveIndex: Int) = analysisOrchestrator.restartAnalysisAtMove(moveIndex)

    fun setAnalysisEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(analysisEnabled = enabled)
        if (enabled) {
            analysisOrchestrator.restartAnalysisForExploringLine()
        } else {
            stockfish.stop()
        }
    }

    // ===== ANALYSIS DELEGATION =====
    fun enterManualStageAtBiggestChange() = analysisOrchestrator.enterManualStageAtBiggestChange()
    fun enterManualStageAtMove(moveIndex: Int) = analysisOrchestrator.enterManualStageAtMove(moveIndex)

    // ===== CONTENT SOURCE DELEGATION =====
    fun showTournaments(server: ChessServer) = contentSourceManager.showTournaments(server)
    fun selectTournament(tournament: TournamentInfo) = contentSourceManager.selectTournament(tournament)
    fun backToTournamentList() = contentSourceManager.backToTournamentList()
    fun dismissTournaments() = contentSourceManager.dismissTournaments()
    fun selectTournamentGame(game: LichessGame) = contentSourceManager.selectTournamentGame(game)

    fun showBroadcasts() = contentSourceManager.showBroadcasts()
    fun selectBroadcast(broadcast: BroadcastInfo) = contentSourceManager.selectBroadcast(broadcast)
    fun selectBroadcastRound(round: BroadcastRoundInfo) = contentSourceManager.selectBroadcastRound(round)
    fun backToBroadcastList() = contentSourceManager.backToBroadcastList()
    fun dismissBroadcasts() = contentSourceManager.dismissBroadcasts()
    fun selectBroadcastGame(game: LichessGame) = contentSourceManager.selectBroadcastGame(game)

    fun showLichessTv() = contentSourceManager.showLichessTv()
    fun selectTvGame(channel: TvChannelInfo) = contentSourceManager.selectTvGame(channel)
    fun dismissLichessTv() = contentSourceManager.dismissLichessTv()

    fun showDailyPuzzle() = contentSourceManager.showDailyPuzzle()
    fun dismissDailyPuzzle() = contentSourceManager.dismissDailyPuzzle()

    fun showStreamers() = contentSourceManager.showStreamers()
    fun selectStreamer(streamer: StreamerInfo) = contentSourceManager.selectStreamer(streamer) { username, server ->
        contentSourceManager.showPlayerInfoWithServer(username, server)
    }
    fun dismissStreamers() = contentSourceManager.dismissStreamers()

    fun showPlayerInfo(username: String) = contentSourceManager.showPlayerInfo(username, null)
    fun showPlayerInfo(username: String, server: ChessServer) = contentSourceManager.showPlayerInfoWithServer(username, server)
    fun nextPlayerGamesPage() = contentSourceManager.nextPlayerGamesPage()
    fun previousPlayerGamesPage() = contentSourceManager.previousPlayerGamesPage()
    fun selectGameFromPlayerInfo(game: LichessGame) = contentSourceManager.selectGameFromPlayerInfo(game)
    fun dismissPlayerInfo() = contentSourceManager.dismissPlayerInfo()

    fun showTopRankings(server: ChessServer) = contentSourceManager.showTopRankings(server)
    fun dismissTopRankings() = contentSourceManager.dismissTopRankings()
    fun selectTopRankingPlayer(username: String, server: ChessServer) = contentSourceManager.selectTopRankingPlayer(username, server)

    // ===== LIVE GAME DELEGATION =====
    fun startLiveFollow(gameId: String) = liveGameManager.startLiveFollow(gameId)
    fun stopLiveFollow() = liveGameManager.stopLiveFollow()
    fun toggleAutoFollowLive() = liveGameManager.toggleAutoFollowLive()

    // ===== STOCKFISH HELPERS =====
    private suspend fun restartStockfishAndAnalyze(fen: String) {
        analysisOrchestrator.stop()
        val ready = stockfish.restart()
        _uiState.value = _uiState.value.copy(stockfishReady = ready)
        if (ready) {
            stockfish.newGame()
            analysisOrchestrator.configureForManualStage()
            delay(100)
            val thisRequestId = analysisOrchestrator.analysisRequestId.incrementAndGet()
            analysisOrchestrator.currentAnalysisFen = fen
            analysisOrchestrator.ensureStockfishAnalysis(fen, thisRequestId)
        }
    }

    // ===== GAME STORAGE =====
    private fun storeAnalysedGame() {
        val game = _uiState.value.game ?: return
        val moves = _uiState.value.moves
        if (moves.isEmpty()) return

        gameStorage.storeAnalysedGame(
            game = game,
            moves = moves,
            moveDetails = _uiState.value.moveDetails,
            previewScores = _uiState.value.previewScores,
            analyseScores = _uiState.value.analyseScores,
            openingName = _uiState.value.openingName
        )

        _uiState.value = _uiState.value.copy(hasAnalysedGames = true)
    }

    // ===== OPENING EXPLORER =====
    fun fetchOpeningExplorer() {
        val manualSettings = _uiState.value.interfaceVisibility.manualStage
        if (!manualSettings.showOpeningExplorer && !manualSettings.showOpeningName) return

        openingExplorerJob?.cancel()
        openingExplorerJob = viewModelScope.launch {
            delay(500)
            _uiState.value = _uiState.value.copy(openingExplorerLoading = true)

            val fen = _uiState.value.currentBoard.getFen()
            when (val result = repository.getOpeningExplorer(fen)) {
                is Result.Success -> {
                    _uiState.value = _uiState.value.copy(
                        openingExplorerData = result.data,
                        openingExplorerLoading = false
                    )
                }
                is Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        openingExplorerData = null,
                        openingExplorerLoading = false
                    )
                }
            }
        }
    }

    fun toggleOpeningExplorer() {
        _uiState.value = _uiState.value.copy(showOpeningExplorer = !_uiState.value.showOpeningExplorer)
    }

    // ===== SHARE/EXPORT =====
    fun showSharePositionDialog() {
        _uiState.value = _uiState.value.copy(showSharePositionDialog = true)
    }

    fun hideSharePositionDialog() {
        _uiState.value = _uiState.value.copy(showSharePositionDialog = false)
    }

    fun getCurrentFen(): String = _uiState.value.currentBoard.getFen()

    fun copyFenToClipboard(context: android.content.Context) {
        val fen = getCurrentFen()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Chess FEN", fen)
        clipboard.setPrimaryClip(clip)
    }

    fun sharePositionAsText(context: android.content.Context) {
        val fen = getCurrentFen()
        val moveIndex = _uiState.value.currentMoveIndex
        val game = _uiState.value.game
        val analysis = _uiState.value.analysisResult

        val shareText = buildString {
            if (game != null) {
                appendLine("${game.players.white.user?.name ?: "White"} vs ${game.players.black.user?.name ?: "Black"}")
                appendLine()
            }
            appendLine("Position after move ${(moveIndex + 2) / 2}${if (moveIndex % 2 == 0) "." else "..."}")
            appendLine()
            appendLine("FEN: $fen")
            if (analysis != null && analysis.lines.isNotEmpty()) {
                val topLine = analysis.lines.first()
                val evalText = if (topLine.isMate) {
                    "Mate in ${kotlin.math.abs(topLine.mateIn)}"
                } else {
                    "%.2f".format(topLine.score)
                }
                appendLine()
                appendLine("Evaluation: $evalText (depth ${analysis.depth})")
                appendLine("Best move: ${topLine.pv.split(" ").firstOrNull() ?: "N/A"}")
            }
            appendLine()
            appendLine("Analyze at: https://lichess.org/analysis/$fen")
        }

        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        val shareIntent = android.content.Intent.createChooser(sendIntent, "Share position")
        context.startActivity(shareIntent)
    }

    fun exportAnnotatedPgn(context: android.content.Context) {
        val game = _uiState.value.game ?: return
        val moveDetails = _uiState.value.moveDetails
        val analyseScores = _uiState.value.analyseScores
        val moveQualities = _uiState.value.moveQualities
        val openingName = _uiState.value.currentOpeningName ?: _uiState.value.openingName

        val pgn = com.eval.export.PgnExporter.exportAnnotatedPgn(
            game = game,
            moveDetails = moveDetails,
            analyseScores = analyseScores,
            moveQualities = moveQualities,
            openingName = openingName
        )

        val sendIntent = android.content.Intent().apply {
            action = android.content.Intent.ACTION_SEND
            putExtra(android.content.Intent.EXTRA_TEXT, pgn)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Chess Game PGN - ${game.players.white.user?.name} vs ${game.players.black.user?.name}")
            type = "text/plain"
        }
        val shareIntent = android.content.Intent.createChooser(sendIntent, "Export PGN")
        context.startActivity(shareIntent)
    }

    fun copyPgnToClipboard(context: android.content.Context) {
        val game = _uiState.value.game ?: return
        val moveDetails = _uiState.value.moveDetails
        val analyseScores = _uiState.value.analyseScores
        val moveQualities = _uiState.value.moveQualities
        val openingName = _uiState.value.currentOpeningName ?: _uiState.value.openingName

        val pgn = com.eval.export.PgnExporter.exportAnnotatedPgn(
            game = game,
            moveDetails = moveDetails,
            analyseScores = analyseScores,
            moveQualities = moveQualities,
            openingName = openingName
        )

        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Chess PGN", pgn)
        clipboard.setPrimaryClip(clip)
    }

    fun exportAsGif(context: android.content.Context) {
        if (_uiState.value.game == null) return
        val moveDetails = _uiState.value.moveDetails
        // Merge scores: use analyseScores where available, otherwise previewScores
        val analyseScores = if (_uiState.value.analyseScores.isNotEmpty()) {
            _uiState.value.previewScores + _uiState.value.analyseScores
        } else {
            _uiState.value.previewScores
        }

        _uiState.value = _uiState.value.copy(
            showGifExportDialog = true,
            gifExportProgress = 0f
        )

        viewModelScope.launch {
            try {
                val boards = mutableListOf<ChessBoard>()
                var board = ChessBoard()
                boards.add(board.copy())

                for (i in moveDetails.indices) {
                    val move = moveDetails[i]
                    val success = board.makeMove(move.san)
                    if (success) {
                        boards.add(board.copy())
                    }
                }

                val boardScores = mutableMapOf<Int, MoveScore>()
                analyseScores.forEach { (moveIndex, score) ->
                    boardScores[moveIndex + 1] = score
                }

                val moves = moveDetails.map { it.san }
                val file = com.eval.export.GifExporter.exportAsGifWithAnnotations(
                    context = context,
                    boards = boards,
                    moves = moves,
                    scores = boardScores,
                    frameDelay = 1000,
                    callback = object : com.eval.export.GifExporter.ProgressCallback {
                        override fun onProgress(current: Int, total: Int) {
                            _uiState.value = _uiState.value.copy(
                                gifExportProgress = current.toFloat() / total
                            )
                        }
                    }
                )

                _uiState.value = _uiState.value.copy(
                    showGifExportDialog = false,
                    gifExportProgress = null
                )

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/gif"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share GIF"))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    showGifExportDialog = false,
                    gifExportProgress = null,
                    errorMessage = "GIF export failed: ${e.message}"
                )
            }
        }
    }

    fun cancelGifExport() {
        _uiState.value = _uiState.value.copy(
            showGifExportDialog = false,
            gifExportProgress = null
        )
    }

    // ===== SETTINGS =====
    fun showSettingsDialog() {
        settingsOnDialogOpen = SettingsSnapshot(
            previewStageSettings = _uiState.value.stockfishSettings.previewStage,
            analyseStageSettings = _uiState.value.stockfishSettings.analyseStage,
            manualStageSettings = _uiState.value.stockfishSettings.manualStage
        )
        _uiState.value = _uiState.value.copy(showSettingsDialog = true)
    }

    fun hideSettingsDialog() {
        _uiState.value = _uiState.value.copy(showSettingsDialog = false)

        val originalSettings = settingsOnDialogOpen
        val currentPreviewStageSettings = _uiState.value.stockfishSettings.previewStage
        val currentAnalyseStageSettings = _uiState.value.stockfishSettings.analyseStage
        val currentManualStageSettings = _uiState.value.stockfishSettings.manualStage

        val previewStageSettingsChanged = originalSettings?.previewStageSettings != currentPreviewStageSettings
        val analyseStageSettingsChanged = originalSettings?.analyseStageSettings != currentAnalyseStageSettings
        val manualStageSettingsChanged = originalSettings?.manualStageSettings != currentManualStageSettings

        settingsOnDialogOpen = null

        if (_uiState.value.game == null) return
        if (!previewStageSettingsChanged && !analyseStageSettingsChanged && !manualStageSettingsChanged) return

        viewModelScope.launch {
            analysisOrchestrator.stop()

            _uiState.value = _uiState.value.copy(stockfishReady = false)

            val ready = stockfish.restart()

            if (ready) {
                kotlinx.coroutines.delay(200)
                val confirmedReady = stockfish.isReady.value
                _uiState.value = _uiState.value.copy(stockfishReady = confirmedReady)

                if (!confirmedReady) return@launch
            } else {
                _uiState.value = _uiState.value.copy(stockfishReady = false)
                return@launch
            }

            if (previewStageSettingsChanged || analyseStageSettingsChanged) {
                _uiState.value = _uiState.value.copy(
                    currentStage = AnalysisStage.PREVIEW,
                    previewScores = emptyMap(),
                    analyseScores = emptyMap()
                )
                analysisOrchestrator.startAnalysis()
            } else if (manualStageSettingsChanged) {
                if (_uiState.value.currentStage == AnalysisStage.MANUAL) {
                    analysisOrchestrator.configureForManualStage()
                    analysisOrchestrator.restartAnalysisForExploringLine()
                } else {
                    analysisOrchestrator.enterManualStageAtCurrentPosition()
                }
            }
        }
    }

    fun showHelpScreen() {
        _uiState.value = _uiState.value.copy(showHelpScreen = true)
    }

    fun hideHelpScreen() {
        _uiState.value = _uiState.value.copy(showHelpScreen = false)
    }

    fun showRetrieveScreen() {
        _uiState.value = _uiState.value.copy(showRetrieveScreen = true)
    }

    fun hideRetrieveScreen() {
        _uiState.value = _uiState.value.copy(showRetrieveScreen = false)
    }

    // ===== API TRACE SCREEN =====
    fun showTraceScreen() {
        _uiState.value = _uiState.value.copy(showTraceScreen = true)
    }

    fun hideTraceScreen() {
        _uiState.value = _uiState.value.copy(
            showTraceScreen = false,
            showTraceDetailScreen = false,
            traceDetailFilename = null
        )
    }

    fun showTraceDetail(filename: String) {
        _uiState.value = _uiState.value.copy(
            showTraceDetailScreen = true,
            traceDetailFilename = filename
        )
    }

    fun hideTraceDetail() {
        _uiState.value = _uiState.value.copy(
            showTraceDetailScreen = false,
            traceDetailFilename = null
        )
    }

    fun clearTraces() {
        ApiTracer.clearTraces()
    }

    // ===== ECO OPENING SELECTION =====
    fun loadEcoOpenings() {
        if (_uiState.value.ecoOpenings.isNotEmpty()) return // Already loaded

        _uiState.value = _uiState.value.copy(ecoOpeningsLoading = true)

        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val jsonString = context.assets.open("eco_codes.json").bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(jsonString)

                val openings = mutableListOf<EcoOpening>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val fen = keys.next()
                    val entry = jsonObject.getJSONObject(fen)
                    openings.add(
                        EcoOpening(
                            fen = fen,
                            eco = entry.getString("eco"),
                            name = entry.getString("name"),
                            moves = entry.getString("moves")
                        )
                    )
                }

                // Sort by ECO code
                openings.sortBy { it.eco }

                _uiState.value = _uiState.value.copy(
                    ecoOpenings = openings,
                    ecoOpeningsLoading = false
                )
            } catch (e: Exception) {
                android.util.Log.e("GameViewModel", "Error loading ECO openings: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    ecoOpeningsLoading = false,
                    errorMessage = "Failed to load openings: ${e.message}"
                )
            }
        }
    }

    fun startWithOpening(opening: EcoOpening) {
        // Create a simple PGN with the opening moves
        val pgn = """
[Event "Opening Study"]
[Site "Eval App"]
[Date "????.??.??"]
[Round "?"]
[White "White"]
[Black "Black"]
[Result "*"]
[Opening "${opening.name}"]
[ECO "${opening.eco}"]

${opening.moves} *
        """.trimIndent()

        // Hide the retrieve screen and load the game
        _uiState.value = _uiState.value.copy(showRetrieveScreen = false)

        // Load the game from PGN content
        gameLoader.loadGamesFromPgnContent(pgn) { _ ->
            // No multiple events expected for a single opening
        }
    }

    /**
     * Start Manual stage directly from a FEN position.
     * No PGN, no move list, no graphs - just board analysis.
     */
    fun startFromFen(fen: String) {
        // Validate FEN by trying to set up the board
        val board = com.eval.chess.ChessBoard()
        if (!board.setFen(fen)) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Invalid FEN position"
            )
            return
        }

        // Create a minimal game object for FEN analysis
        val lichessGame = com.eval.data.LichessGame(
            id = "fen_${System.currentTimeMillis()}",
            rated = false,
            variant = "standard",
            speed = "classical",
            perf = null,
            status = "*",  // Ongoing/study
            winner = null,
            players = com.eval.data.Players(
                white = com.eval.data.Player(
                    user = com.eval.data.User(name = "White", id = "white"),
                    rating = null,
                    aiLevel = null
                ),
                black = com.eval.data.Player(
                    user = com.eval.data.User(name = "Black", id = "black"),
                    rating = null,
                    aiLevel = null
                )
            ),
            pgn = "[FEN \"$fen\"]\n\n*",
            moves = null,
            clock = null,
            createdAt = System.currentTimeMillis(),
            lastMoveAt = null
        )

        // Determine if it's white or black to move from FEN
        val isWhiteToMove = board.getTurn() == com.eval.chess.PieceColor.WHITE

        // Hide retrieve screen and go directly to Manual stage
        _uiState.value = _uiState.value.copy(
            showRetrieveScreen = false,
            isLoading = false,
            game = lichessGame,
            openingName = null,
            currentOpeningName = null,
            moves = emptyList(),
            moveDetails = emptyList(),
            currentBoard = board,
            currentMoveIndex = -1,
            flippedBoard = !isWhiteToMove,  // Flip board if black to move
            userPlayedBlack = !isWhiteToMove,
            previewScores = emptyMap(),
            analyseScores = emptyMap(),
            currentStage = AnalysisStage.MANUAL,
            autoAnalysisIndex = -1,
            isExploringLine = false,
            exploringLineMoves = emptyList(),
            exploringLineMoveIndex = -1,
            savedGameMoveIndex = -1,
            analysisResult = null,
            analysisResultFen = null
        )

        // Configure Stockfish for Manual stage and start analysis
        viewModelScope.launch {
            if (_uiState.value.stockfishReady) {
                analysisOrchestrator.configureForManualStage()
                analysisOrchestrator.restartAnalysisForExploringLine()
            }
        }
    }

    fun updateStockfishSettings(settings: StockfishSettings) {
        saveStockfishSettings(settings)
        _uiState.value = _uiState.value.copy(stockfishSettings = settings)
        if (_uiState.value.stockfishReady) {
            when (_uiState.value.currentStage) {
                AnalysisStage.PREVIEW -> analysisOrchestrator.configureForPreviewStage()
                AnalysisStage.ANALYSE -> analysisOrchestrator.configureForAnalyseStage()
                AnalysisStage.MANUAL -> analysisOrchestrator.configureForManualStage()
            }
            if (_uiState.value.currentStage == AnalysisStage.MANUAL) {
                analysisOrchestrator.restartAnalysisForExploringLine()
            }
        }
    }

    fun updateBoardLayoutSettings(settings: BoardLayoutSettings) {
        saveBoardLayoutSettings(settings)
        _uiState.value = _uiState.value.copy(boardLayoutSettings = settings)
    }

    fun updateGraphSettings(settings: GraphSettings) {
        saveGraphSettings(settings)
        _uiState.value = _uiState.value.copy(graphSettings = settings)
    }

    fun updateInterfaceVisibilitySettings(settings: InterfaceVisibilitySettings) {
        val currentSettings = _uiState.value.interfaceVisibility

        val previewChanged = currentSettings.previewStage != settings.previewStage
        val analyseChanged = currentSettings.analyseStage != settings.analyseStage

        saveInterfaceVisibilitySettings(settings)
        _uiState.value = _uiState.value.copy(interfaceVisibility = settings)

        if ((previewChanged || analyseChanged) && _uiState.value.game != null) {
            analysisOrchestrator.stop()

            _uiState.value = _uiState.value.copy(
                currentStage = AnalysisStage.PREVIEW,
                previewScores = emptyMap(),
                analyseScores = emptyMap(),
                autoAnalysisIndex = -1
            )

            viewModelScope.launch {
                val ready = stockfish.restart()
                _uiState.value = _uiState.value.copy(stockfishReady = ready)
                if (ready) {
                    stockfish.newGame()
                    analysisOrchestrator.startAnalysis()
                }
            }
        }
    }

    fun updateGeneralSettings(settings: GeneralSettings) {
        saveGeneralSettings(settings)
        _uiState.value = _uiState.value.copy(
            generalSettings = settings,
            playerGamesPageSize = settings.paginationPageSize,
            gameSelectionPageSize = settings.paginationPageSize
        )
        // Update ApiTracer when developer mode changes
        ApiTracer.isTracingEnabled = settings.developerMode && settings.trackApiCalls
        if (!settings.developerMode) {
            // Clear traces when developer mode is disabled
            ApiTracer.clearTraces()
        }
    }

    fun updateTrackApiCalls(enabled: Boolean) {
        // Only enable tracing if both developer mode and track API calls are enabled
        val developerMode = _uiState.value.generalSettings.developerMode
        ApiTracer.isTracingEnabled = developerMode && enabled
        if (!enabled) {
            // Clear traces when tracking is disabled
            ApiTracer.clearTraces()
        }
    }

    /**
     * Reset the app to the homepage (logo only), clearing all game state.
     * Used when developer mode changes to ensure a clean slate.
     */
    fun resetToHomepage() {
        // Stop any ongoing analysis
        analysisOrchestrator.stop()

        // Clear board histories
        boardHistory.clear()
        exploringLineHistory.clear()

        // Reset UI state to show only the homepage with logo
        _uiState.value = _uiState.value.copy(
            game = null,
            gameList = emptyList(),
            showGameSelection = false,
            showRetrieveScreen = false,  // Show homepage with logo only
            currentBoard = ChessBoard(),
            moves = emptyList(),
            moveDetails = emptyList(),
            currentMoveIndex = -1,
            analysisResult = null,
            analysisResultFen = null,
            flippedBoard = false,
            userPlayedBlack = false,
            isExploringLine = false,
            exploringLineMoves = emptyList(),
            exploringLineMoveIndex = -1,
            savedGameMoveIndex = -1,
            currentStage = AnalysisStage.PREVIEW,
            previewScores = emptyMap(),
            analyseScores = emptyMap(),
            autoAnalysisIndex = -1,
            openingName = null,
            currentOpeningName = null
        )
    }

    fun updateAiSettings(settings: AiSettings) {
        saveAiSettings(settings)
        _uiState.value = _uiState.value.copy(aiSettings = settings)
    }

    // ===== AI ANALYSIS =====
    fun requestAiAnalysis(service: AiService) {
        val aiSettings = _uiState.value.aiSettings
        val apiKey = aiSettings.getApiKey(service)
        val model = aiSettings.getModel(service)
        val serviceNameWithModel = if (model.isNotBlank()) {
            "${service.displayName} ($model)"
        } else {
            service.displayName
        }

        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                showAiAnalysisDialog = true,
                aiAnalysisLoading = false,
                aiAnalysisServiceName = serviceNameWithModel,
                aiAnalysisResult = AiAnalysisResponse(
                    service = service,
                    analysis = null,
                    error = "API key not configured for ${service.displayName}. Please configure it in Settings > AI Analysis."
                )
            )
            return
        }

        val fen = _uiState.value.currentBoard.getFen()

        _uiState.value = _uiState.value.copy(
            showAiAnalysisDialog = true,
            aiAnalysisLoading = true,
            aiAnalysisServiceName = serviceNameWithModel,
            aiAnalysisResult = null
        )

        viewModelScope.launch {
            val prompt = when (service) {
                AiService.CHATGPT -> aiSettings.chatGptPrompt
                AiService.CLAUDE -> aiSettings.claudePrompt
                AiService.GEMINI -> aiSettings.geminiPrompt
                AiService.GROK -> aiSettings.grokPrompt
                AiService.GROQ -> aiSettings.groqPrompt
                AiService.DEEPSEEK -> aiSettings.deepSeekPrompt
                AiService.MISTRAL -> aiSettings.mistralPrompt
                AiService.PERPLEXITY -> aiSettings.perplexityPrompt
                AiService.TOGETHER -> aiSettings.togetherPrompt
                AiService.OPENROUTER -> aiSettings.openRouterPrompt
                AiService.DUMMY -> ""
            }
            val result = aiAnalysisRepository.analyzePosition(
                service = service,
                fen = fen,
                apiKey = apiKey,
                prompt = prompt,
                chatGptModel = aiSettings.chatGptModel,
                claudeModel = aiSettings.claudeModel,
                geminiModel = aiSettings.geminiModel,
                grokModel = aiSettings.grokModel,
                groqModel = aiSettings.groqModel,
                deepSeekModel = aiSettings.deepSeekModel,
                mistralModel = aiSettings.mistralModel,
                perplexityModel = aiSettings.perplexityModel,
                togetherModel = aiSettings.togetherModel,
                openRouterModel = aiSettings.openRouterModel
            )
            _uiState.value = _uiState.value.copy(
                aiAnalysisLoading = false,
                aiAnalysisResult = result
            )
        }
    }

    fun dismissAiAnalysisDialog() {
        _uiState.value = _uiState.value.copy(
            showAiAnalysisDialog = false,
            aiAnalysisResult = null,
            aiAnalysisLoading = false
        )
    }

    // ===== AI REPORTS (MULTI-SERVICE) =====

    fun showAiReportsSelectionDialog() {
        _uiState.value = _uiState.value.copy(showAiReportsSelectionDialog = true)
    }

    fun dismissAiReportsSelectionDialog() {
        _uiState.value = _uiState.value.copy(showAiReportsSelectionDialog = false)
    }

    fun loadAiReportProviders(): Set<String> {
        return settingsPrefs.loadAiReportProviders()
    }

    fun saveAiReportProviders(providers: Set<String>) {
        settingsPrefs.saveAiReportProviders(providers)
    }

    // Agent-based AI Reports methods
    fun loadAiReportAgents(): Set<String> {
        return settingsPrefs.loadAiReportAgents()
    }

    fun saveAiReportAgents(agentIds: Set<String>) {
        settingsPrefs.saveAiReportAgents(agentIds)
    }

    fun generateAiReportsWithAgents(selectedAgentIds: Set<String>) {
        val aiSettings = _uiState.value.aiSettings

        // Get agents with configured API keys
        val agentsToCall = selectedAgentIds.mapNotNull { agentId ->
            aiSettings.agents.find { it.id == agentId && it.apiKey.isNotBlank() }
        }

        if (agentsToCall.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No configured agents selected. Please configure agents in Settings > AI Setup > AI Agents."
            )
            return
        }

        val fen = _uiState.value.currentBoard.getFen()

        _uiState.value = _uiState.value.copy(
            showAiReportsSelectionDialog = false,
            showAiReportsDialog = true,
            aiReportsProgress = 0,
            aiReportsTotal = agentsToCall.size,
            aiReportsAgentResults = emptyMap(),
            aiReportsSelectedAgents = selectedAgentIds
        )

        viewModelScope.launch {
            val results = mutableMapOf<String, AiAnalysisResponse>()
            val mutex = Mutex()
            var completedCount = 0

            // Launch all API calls in parallel
            val deferredResults = agentsToCall.map { agent ->
                async {
                    val prompt = aiSettings.getAgentGamePrompt(agent)

                    var result = aiAnalysisRepository.analyzePositionWithAgent(
                        agent = agent,
                        fen = fen,
                        prompt = prompt
                    )

                    // If failed, retry once after 1 second delay
                    if (!result.isSuccess) {
                        kotlinx.coroutines.delay(1000)
                        result = aiAnalysisRepository.analyzePositionWithAgent(
                            agent = agent,
                            fen = fen,
                            prompt = prompt
                        )
                    }

                    mutex.withLock {
                        results[agent.id] = result
                        completedCount++
                        _uiState.value = _uiState.value.copy(
                            aiReportsProgress = completedCount,
                            aiReportsAgentResults = results.toMap()
                        )
                    }
                }
            }

            deferredResults.awaitAll()
        }
    }

    fun startPlayerAiReportsWithAgents(selectedAgentIds: Set<String>) {
        val aiSettings = _uiState.value.aiSettings
        val playerName = _uiState.value.playerAiReportsPlayerName
        val serverName = _uiState.value.playerAiReportsServer

        // Get agents with configured API keys
        val agentsToCall = selectedAgentIds.mapNotNull { agentId ->
            aiSettings.agents.find { it.id == agentId && it.apiKey.isNotBlank() }
        }

        if (agentsToCall.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No configured agents selected."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            showPlayerAiReportsSelectionDialog = false,
            showPlayerAiReportsDialog = true,
            playerAiReportsProgress = 0,
            playerAiReportsTotal = agentsToCall.size,
            playerAiReportsAgentResults = emptyMap(),
            playerAiReportsSelectedAgents = selectedAgentIds
        )

        viewModelScope.launch {
            val results = mutableMapOf<String, AiAnalysisResponse>()
            val mutex = Mutex()
            var completedCount = 0

            val deferredResults = agentsToCall.map { agent ->
                async {
                    // Select prompt based on server presence
                    val prompt = if (serverName != null) {
                        aiSettings.getAgentServerPlayerPrompt(agent)
                    } else {
                        aiSettings.getAgentOtherPlayerPrompt(agent)
                    }

                    // Replace placeholders in prompt
                    val finalPrompt = prompt
                        .replace("@PLAYER@", playerName)
                        .replace("@SERVER@", serverName ?: "unknown")

                    var result = aiAnalysisRepository.analyzePlayerWithAgent(
                        agent = agent,
                        prompt = finalPrompt
                    )

                    // If failed, retry once
                    if (!result.isSuccess) {
                        kotlinx.coroutines.delay(1000)
                        result = aiAnalysisRepository.analyzePlayerWithAgent(
                            agent = agent,
                            prompt = finalPrompt
                        )
                    }

                    mutex.withLock {
                        results[agent.id] = result
                        completedCount++
                        _uiState.value = _uiState.value.copy(
                            playerAiReportsProgress = completedCount,
                            playerAiReportsAgentResults = results.toMap()
                        )
                    }
                }
            }

            deferredResults.awaitAll()
        }
    }

    fun generateAiReports(selectedProviders: Set<AiService>) {
        val aiSettings = _uiState.value.aiSettings

        // Filter to only selected providers that have API keys configured
        val servicesToCall = selectedProviders.filter { service ->
            aiSettings.getApiKey(service).isNotBlank()
        }

        if (servicesToCall.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No AI services selected or configured. Please add API keys in Settings > AI Analysis."
            )
            return
        }

        val fen = _uiState.value.currentBoard.getFen()

        _uiState.value = _uiState.value.copy(
            showAiReportsSelectionDialog = false,
            showAiReportsDialog = true,
            aiReportsProgress = 0,
            aiReportsTotal = servicesToCall.size,
            aiReportsResults = emptyMap(),
            aiReportsSelectedServices = servicesToCall.toSet()
        )

        viewModelScope.launch {
            val results = mutableMapOf<AiService, AiAnalysisResponse>()
            val mutex = Mutex()
            var completedCount = 0

            // Launch all API calls in parallel
            val deferredResults = servicesToCall.map { service ->
                async {
                    val apiKey = aiSettings.getApiKey(service)
                    val prompt = aiSettings.getPrompt(service)

                    var result = aiAnalysisRepository.analyzePosition(
                        service = service,
                        fen = fen,
                        apiKey = apiKey,
                        prompt = prompt,
                        chatGptModel = aiSettings.chatGptModel,
                        claudeModel = aiSettings.claudeModel,
                        geminiModel = aiSettings.geminiModel,
                        grokModel = aiSettings.grokModel,
                        deepSeekModel = aiSettings.deepSeekModel,
                        mistralModel = aiSettings.mistralModel,
                        perplexityModel = aiSettings.perplexityModel,
                        togetherModel = aiSettings.togetherModel
                    )

                    // If failed, retry once after 1 second delay
                    if (!result.isSuccess) {
                        kotlinx.coroutines.delay(1000)
                        result = aiAnalysisRepository.analyzePosition(
                            service = service,
                            fen = fen,
                            apiKey = apiKey,
                            prompt = prompt,
                            chatGptModel = aiSettings.chatGptModel,
                            claudeModel = aiSettings.claudeModel,
                            geminiModel = aiSettings.geminiModel,
                            grokModel = aiSettings.grokModel,
                            deepSeekModel = aiSettings.deepSeekModel,
                            mistralModel = aiSettings.mistralModel,
                            perplexityModel = aiSettings.perplexityModel,
                            togetherModel = aiSettings.togetherModel
                        )
                    }

                    // Update progress as each call completes
                    mutex.withLock {
                        results[service] = result
                        completedCount++
                        _uiState.value = _uiState.value.copy(
                            aiReportsProgress = completedCount,
                            aiReportsResults = results.toMap()
                        )
                    }

                    service to result
                }
            }

            // Wait for all calls to complete
            deferredResults.awaitAll()

            // All done - keep dialog open until user exports
        }
    }

    fun dismissAiReportsDialog() {
        _uiState.value = _uiState.value.copy(
            showAiReportsDialog = false,
            aiReportsResults = emptyMap(),
            aiReportsProgress = 0,
            aiReportsTotal = 0,
            aiReportsSelectedServices = emptySet()
        )
    }

    // ===== PLAYER AI REPORTS =====

    /**
     * Show the provider selection dialog for player AI reports.
     * @param playerName The name of the player to analyze
     * @param server The chess server (e.g., "lichess.org", "chess.com") or null for other players
     * @param playerInfo The full player info for HTML generation
     */
    fun showPlayerAiReportsSelectionDialog(playerName: String, server: String?, playerInfo: com.eval.data.PlayerInfo?) {
        _uiState.value = _uiState.value.copy(
            showPlayerAiReportsSelectionDialog = true,
            playerAiReportsPlayerName = playerName,
            playerAiReportsServer = server,
            playerAiReportsPlayerInfo = playerInfo
        )
    }

    fun dismissPlayerAiReportsSelectionDialog() {
        _uiState.value = _uiState.value.copy(
            showPlayerAiReportsSelectionDialog = false
        )
    }

    /**
     * Start player AI reports with selected providers.
     */
    fun startPlayerAiReports(selectedProviders: Set<AiService>) {
        val aiSettings = _uiState.value.aiSettings
        val playerName = _uiState.value.playerAiReportsPlayerName
        val server = _uiState.value.playerAiReportsServer

        // Filter to only selected providers that have API keys configured
        val servicesToCall = selectedProviders.filter { service ->
            aiSettings.getApiKey(service).isNotBlank()
        }

        if (servicesToCall.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No AI services selected or configured. Please add API keys in Settings > AI Analysis."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            showPlayerAiReportsSelectionDialog = false,
            showPlayerAiReportsDialog = true,
            playerAiReportsProgress = 0,
            playerAiReportsTotal = servicesToCall.size,
            playerAiReportsResults = emptyMap(),
            playerAiReportsSelectedServices = servicesToCall.toSet()
        )

        viewModelScope.launch {
            val results = mutableMapOf<AiService, AiAnalysisResponse>()
            val mutex = Mutex()
            var completedCount = 0

            // Launch all API calls in parallel
            val deferredResults = servicesToCall.map { service ->
                async {
                    val apiKey = aiSettings.getApiKey(service)
                    // Choose prompt based on whether we have a server (lichess/chess.com) or not
                    val prompt = if (server != null) {
                        aiSettings.getServerPlayerPrompt(service)
                    } else {
                        aiSettings.getOtherPlayerPrompt(service)
                    }

                    var result = aiAnalysisRepository.analyzePlayer(
                        service = service,
                        playerName = playerName,
                        server = server,
                        apiKey = apiKey,
                        prompt = prompt,
                        chatGptModel = aiSettings.chatGptModel,
                        claudeModel = aiSettings.claudeModel,
                        geminiModel = aiSettings.geminiModel,
                        grokModel = aiSettings.grokModel,
                        deepSeekModel = aiSettings.deepSeekModel,
                        mistralModel = aiSettings.mistralModel,
                        perplexityModel = aiSettings.perplexityModel,
                        togetherModel = aiSettings.togetherModel
                    )

                    // If failed, retry once after 1 second delay
                    if (!result.isSuccess) {
                        kotlinx.coroutines.delay(1000)
                        result = aiAnalysisRepository.analyzePlayer(
                            service = service,
                            playerName = playerName,
                            server = server,
                            apiKey = apiKey,
                            prompt = prompt,
                            chatGptModel = aiSettings.chatGptModel,
                            claudeModel = aiSettings.claudeModel,
                            geminiModel = aiSettings.geminiModel,
                            grokModel = aiSettings.grokModel,
                            deepSeekModel = aiSettings.deepSeekModel,
                            mistralModel = aiSettings.mistralModel,
                            perplexityModel = aiSettings.perplexityModel,
                            togetherModel = aiSettings.togetherModel
                        )
                    }

                    // Update progress as each call completes
                    mutex.withLock {
                        results[service] = result
                        completedCount++
                        _uiState.value = _uiState.value.copy(
                            playerAiReportsProgress = completedCount,
                            playerAiReportsResults = results.toMap()
                        )
                    }

                    service to result
                }
            }

            // Wait for all calls to complete
            deferredResults.awaitAll()

            // All done - keep dialog open until user exports
        }
    }

    fun dismissPlayerAiReportsDialog() {
        _uiState.value = _uiState.value.copy(
            showPlayerAiReportsDialog = false,
            playerAiReportsResults = emptyMap(),
            playerAiReportsProgress = 0,
            playerAiReportsTotal = 0,
            playerAiReportsSelectedServices = emptySet(),
            playerAiReportsPlayerName = "",
            playerAiReportsServer = null,
            playerAiReportsPlayerInfo = null
        )
    }

    // ===== AI SCREENS =====

    fun showAiScreen() {
        _uiState.value = _uiState.value.copy(
            showAiScreen = true
        )
    }

    fun hideAiScreen() {
        _uiState.value = _uiState.value.copy(
            showAiScreen = false
        )
    }

    fun showNewAiReportScreen() {
        _uiState.value = _uiState.value.copy(
            showAiScreen = false,
            showNewAiReportScreen = true,
            genericAiPromptTitle = "",
            genericAiPromptText = ""
        )
    }

    fun hideNewAiReportScreen() {
        _uiState.value = _uiState.value.copy(
            showNewAiReportScreen = false
        )
    }

    fun showAiHistoryScreen() {
        _uiState.value = _uiState.value.copy(
            showAiScreen = false,
            showAiHistoryScreen = true
        )
    }

    fun hideAiHistoryScreen() {
        _uiState.value = _uiState.value.copy(
            showAiHistoryScreen = false
        )
    }

    fun showGenericAiAgentSelection(title: String, prompt: String) {
        _uiState.value = _uiState.value.copy(
            showNewAiReportScreen = false,
            showGenericAiAgentSelection = true,
            genericAiPromptTitle = title,
            genericAiPromptText = prompt
        )
    }

    fun dismissGenericAiAgentSelection() {
        _uiState.value = _uiState.value.copy(
            showGenericAiAgentSelection = false
        )
    }

    fun generateGenericAiReports(selectedAgentIds: Set<String>) {
        val aiSettings = _uiState.value.aiSettings
        val prompt = _uiState.value.genericAiPromptText

        // Get agents with configured API keys
        val agentsToCall = selectedAgentIds.mapNotNull { agentId ->
            aiSettings.agents.find { it.id == agentId && it.apiKey.isNotBlank() }
        }

        if (agentsToCall.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No configured agents selected. Please configure agents in Settings > AI Setup > AI Agents."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            showGenericAiAgentSelection = false,
            showGenericAiReportsDialog = true,
            genericAiReportsProgress = 0,
            genericAiReportsTotal = agentsToCall.size,
            genericAiReportsAgentResults = emptyMap(),
            genericAiReportsSelectedAgents = selectedAgentIds
        )

        viewModelScope.launch {
            val results = mutableMapOf<String, AiAnalysisResponse>()
            val mutex = Mutex()
            var completedCount = 0

            // Launch all API calls in parallel
            val deferredResults = agentsToCall.map { agent ->
                async {
                    var result = aiAnalysisRepository.analyzePlayerWithAgent(
                        agent = agent,
                        prompt = prompt
                    )

                    // If failed, retry once after 1 second delay
                    if (!result.isSuccess) {
                        kotlinx.coroutines.delay(1000)
                        result = aiAnalysisRepository.analyzePlayerWithAgent(
                            agent = agent,
                            prompt = prompt
                        )
                    }

                    mutex.withLock {
                        results[agent.id] = result
                        completedCount++
                        _uiState.value = _uiState.value.copy(
                            genericAiReportsProgress = completedCount,
                            genericAiReportsAgentResults = results.toMap()
                        )
                    }
                }
            }

            deferredResults.awaitAll()
        }
    }

    fun dismissGenericAiReportsDialog() {
        _uiState.value = _uiState.value.copy(
            showGenericAiReportsDialog = false,
            genericAiReportsProgress = 0,
            genericAiReportsTotal = 0,
            genericAiReportsSelectedAgents = emptySet(),
            genericAiReportsAgentResults = emptyMap(),
            genericAiPromptTitle = "",
            genericAiPromptText = ""
        )
    }

    // ===== AI MODEL FETCHING =====
    fun fetchChatGptModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingChatGptModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchChatGptModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedChatGptModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableChatGptModels = models,
                isLoadingChatGptModels = false
            )
        }
    }

    fun fetchGeminiModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingGeminiModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchGeminiModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedGeminiModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableGeminiModels = models,
                isLoadingGeminiModels = false
            )
        }
    }

    fun fetchGrokModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingGrokModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchGrokModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedGrokModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableGrokModels = models,
                isLoadingGrokModels = false
            )
        }
    }

    fun fetchGroqModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingGroqModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchGroqModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedGroqModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableGroqModels = models,
                isLoadingGroqModels = false
            )
        }
    }

    fun fetchDeepSeekModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingDeepSeekModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchDeepSeekModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedDeepSeekModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableDeepSeekModels = models,
                isLoadingDeepSeekModels = false
            )
        }
    }

    fun fetchMistralModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingMistralModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchMistralModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedMistralModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableMistralModels = models,
                isLoadingMistralModels = false
            )
        }
    }

    fun fetchPerplexityModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingPerplexityModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchPerplexityModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedPerplexityModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availablePerplexityModels = models,
                isLoadingPerplexityModels = false
            )
        }
    }

    fun fetchTogetherModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingTogetherModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchTogetherModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedTogetherModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableTogetherModels = models,
                isLoadingTogetherModels = false
            )
        }
    }

    fun fetchOpenRouterModels(apiKey: String) {
        if (apiKey.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingOpenRouterModels = true)
        viewModelScope.launch {
            val models = aiAnalysisRepository.fetchOpenRouterModels(apiKey)
            if (models.isNotEmpty()) {
                settingsPrefs.saveCachedOpenRouterModels(models)
            }
            _uiState.value = _uiState.value.copy(
                availableOpenRouterModels = models,
                isLoadingOpenRouterModels = false
            )
        }
    }

    /**
     * Test if a model is accessible with the given API key.
     * @return null if successful, error message if failed
     */
    suspend fun testAiModel(service: AiService, apiKey: String, model: String): String? {
        return aiAnalysisRepository.testModel(service, apiKey, model)
    }

    // ===== MISC =====
    fun cycleArrowMode() {
        val currentSettings = _uiState.value.stockfishSettings
        val currentMode = currentSettings.manualStage.arrowMode
        val newMode = when (currentMode) {
            ArrowMode.NONE -> ArrowMode.MAIN_LINE
            ArrowMode.MAIN_LINE -> ArrowMode.MULTI_LINES
            ArrowMode.MULTI_LINES -> ArrowMode.NONE
        }
        val newSettings = currentSettings.copy(
            manualStage = currentSettings.manualStage.copy(arrowMode = newMode)
        )
        saveStockfishSettings(newSettings)
        _uiState.value = _uiState.value.copy(stockfishSettings = newSettings)
    }

    fun toggleFullScreen() {
        val currentSettings = _uiState.value.generalSettings
        val newSettings = currentSettings.copy(
            longTapForFullScreen = !currentSettings.longTapForFullScreen
        )
        _uiState.value = _uiState.value.copy(generalSettings = newSettings)
    }

    override fun onCleared() {
        super.onCleared()
        analysisOrchestrator.autoAnalysisJob?.cancel()
        analysisOrchestrator.manualAnalysisJob?.cancel()
        openingExplorerJob?.cancel()
        liveGameManager.cancel()
        stockfish.shutdown()
        moveSoundPlayer.release()
    }
}
