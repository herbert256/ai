package com.eval.ui

import com.eval.chess.ChessBoard
import com.eval.chess.PgnParser
import com.eval.chess.PieceType
import com.eval.data.ChessRepository
import com.eval.data.ChessServer
import com.eval.data.LichessGame
import com.eval.data.Player
import com.eval.data.Players
import com.eval.data.Result
import com.eval.data.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles loading and parsing games from various sources.
 */
internal class GameLoader(
    private val repository: ChessRepository,
    private val getUiState: () -> GameUiState,
    private val updateUiState: (GameUiState.() -> GameUiState) -> Unit,
    private val viewModelScope: CoroutineScope,
    private val getBoardHistory: () -> MutableList<ChessBoard>,
    private val getExploringLineHistory: () -> MutableList<ChessBoard>,
    private val settingsPrefs: SettingsPreferences,
    private val gameStorage: GameStorageManager,
    private val analysisOrchestrator: AnalysisOrchestrator,
    private val fetchOpeningExplorer: () -> Unit,
    private val restartStockfishAndAnalyze: suspend (String) -> Unit = { },
    private val getAppVersionCode: () -> Long = { 0L }
) {
    // Temporary storage for server/username when showing game selection dialog
    private var pendingGameSelectionServer: ChessServer? = null
    private var pendingGameSelectionUsername: String? = null
    private var isPgnFileSelection: Boolean = false

    val savedLichessUsername: String
        get() = settingsPrefs.savedLichessUsername

    /**
     * Automatically load a game and start analysis on app startup.
     */
    suspend fun autoLoadLastGame() {
        android.util.Log.d("GameLoader", "autoLoadLastGame: Starting")
        val storedGame = gameStorage.loadCurrentAnalysedGame()
        if (storedGame != null) {
            android.util.Log.d("GameLoader", "autoLoadLastGame: Found stored game, loading directly")
            loadAnalysedGameDirectly(storedGame)
            return
        }
        android.util.Log.d("GameLoader", "autoLoadLastGame: No stored game found")
    }

    /**
     * Reload the last stored analysed game.
     */
    fun reloadLastGame() {
        viewModelScope.launch {
            val storedGame = gameStorage.loadCurrentAnalysedGame()
            if (storedGame != null) {
                loadAnalysedGameDirectly(storedGame)
            }
        }
    }

    /**
     * Fetch the most recent game from a specific server for a username.
     */
    suspend fun fetchLastGameFromServer(server: ChessServer, username: String) {
        if (username.isBlank()) return

        updateUiState {
            copy(
                isLoading = true,
                errorMessage = null
            )
        }

        val result = repository.getLichessGames(username, 1)

        when (result) {
            is Result.Success -> {
                val games = result.data
                if (games.isNotEmpty()) {
                    updateUiState {
                        copy(
                            isLoading = false,
                            gameList = games,
                            showGameSelection = false
                        )
                    }
                    loadGame(games.first(), server, username)
                } else {
                    val serverName = if (server == ChessServer.LICHESS) "Lichess" else "Chess.com"
                    updateUiState {
                        copy(
                            isLoading = false,
                            errorMessage = "No games found for $username on $serverName"
                        )
                    }
                }
            }
            is Result.Error -> {
                updateUiState {
                    copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    fun fetchGames(server: ChessServer, username: String) {
        settingsPrefs.saveLichessUsername(username)

        settingsPrefs.setFirstGameRetrievedVersion(getAppVersionCode())

        analysisOrchestrator.autoAnalysisJob?.cancel()

        val pageSize = getUiState().gameSelectionPageSize

        viewModelScope.launch {
            updateUiState {
                copy(
                    isLoading = true,
                    errorMessage = null,
                    game = null,
                    gameList = emptyList(),
                    showGameSelection = false,
                    gameSelectionPage = 0,
                    gameSelectionLoading = false,
                    gameSelectionHasMore = true
                )
            }

            val result = repository.getLichessGames(username, pageSize)

            when (result) {
                is Result.Success -> {
                    val games = result.data
                    if (games.isNotEmpty()) {
                        storeRetrievedGames(games, username, server)
                    }
                    if (games.size == 1) {
                        updateUiState {
                            copy(
                                isLoading = false,
                                gameList = games,
                                showGameSelection = false,
                                gameSelectionUsername = username,
                                gameSelectionServer = server,
                                gameSelectionHasMore = false
                            )
                        }
                        loadGame(games.first(), server, username)
                    } else {
                        val entry = RetrievedGamesEntry(accountName = username, server = server)
                        updateUiState {
                            copy(
                                isLoading = false,
                                showRetrieveScreen = false,
                                showSelectedRetrieveGames = true,
                                selectedRetrieveEntry = entry,
                                selectedRetrieveGames = games,
                                gameSelectionPage = 0,
                                gameSelectionHasMore = games.size >= pageSize
                            )
                        }
                    }
                }
                is Result.Error -> {
                    updateUiState {
                        copy(
                            isLoading = false,
                            errorMessage = result.message,
                            gameSelectionHasMore = false
                        )
                    }
                }
            }
        }
    }

    fun selectGame(game: LichessGame) {
        updateUiState { copy(showGameSelection = false, showRetrieveScreen = false) }

        if (isPgnFileSelection) {
            isPgnFileSelection = false
            pendingGameSelectionServer = null
            pendingGameSelectionUsername = null
            val whiteName = game.players.white.user?.name ?: "White"
            loadGame(game, null, whiteName)
            return
        }

        val server = pendingGameSelectionServer
        val username = pendingGameSelectionUsername
        pendingGameSelectionServer = null
        pendingGameSelectionUsername = null
        loadGame(game, server, username)
    }

    fun dismissGameSelection() {
        updateUiState { copy(showGameSelection = false) }
        isPgnFileSelection = false
    }

    fun clearGame() {
        analysisOrchestrator.stop()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()
        boardHistory.clear()
        exploringLineHistory.clear()
        updateUiState {
            copy(
                game = null,
                gameList = emptyList(),
                showGameSelection = false,
                showRetrieveScreen = true,
                currentBoard = ChessBoard(),
                moves = emptyList(),
                moveDetails = emptyList(),
                currentMoveIndex = -1,
                analysisResult = null,
                flippedBoard = false,
                userPlayedBlack = false,
                isExploringLine = false,
                exploringLineMoves = emptyList(),
                exploringLineMoveIndex = -1,
                savedGameMoveIndex = -1,
                currentStage = AnalysisStage.PREVIEW,
                previewScores = emptyMap(),
                analyseScores = emptyMap(),
                autoAnalysisIndex = -1
            )
        }
    }

    fun loadGame(game: LichessGame, server: ChessServer?, username: String?) {
        analysisOrchestrator.autoAnalysisJob?.cancel()
        analysisOrchestrator.manualAnalysisJob?.cancel()
        analysisOrchestrator.stop()

        val pgn = game.pgn
        if (pgn == null) {
            updateUiState {
                copy(
                    isLoading = false,
                    errorMessage = "No PGN data available"
                )
            }
            return
        }

        val pgnHeaders = PgnParser.parseHeaders(pgn)
        val openingName = pgnHeaders["Opening"] ?: pgnHeaders["ECO"]

        val parsedMoves = PgnParser.parseMovesWithClock(pgn)
        val initialBoard = ChessBoard()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()
        boardHistory.clear()
        exploringLineHistory.clear()
        boardHistory.add(initialBoard.copy())

        val tempBoard = ChessBoard()
        val moveDetailsList = mutableListOf<MoveDetails>()
        val validMoves = mutableListOf<String>()

        for ((index, parsedMove) in parsedMoves.withIndex()) {
            val move = parsedMove.san
            val moveNum = (index / 2) + 1
            val isWhite = index % 2 == 0
            val boardBeforeMove = tempBoard.copy()
            val moveSuccess = tempBoard.makeMove(move) || tempBoard.makeUciMove(move)
            if (!moveSuccess) {
                val prefix = if (isWhite) "$moveNum." else "$moveNum..."
                android.util.Log.e("GameLoader", "FAILED to apply move $prefix $move - FEN: ${boardBeforeMove.getFen()}")
                continue
            }
            validMoves.add(move)
            boardHistory.add(tempBoard.copy())

            val lastMove = tempBoard.getLastMove()
            if (lastMove != null) {
                val fromSquare = lastMove.from.toAlgebraic()
                val toSquare = lastMove.to.toAlgebraic()
                val capturedPiece = boardBeforeMove.getPiece(lastMove.to)
                val movedPiece = tempBoard.getPiece(lastMove.to)
                val pieceType = when (movedPiece?.type) {
                    PieceType.KING -> "K"
                    PieceType.QUEEN -> "Q"
                    PieceType.ROOK -> "R"
                    PieceType.BISHOP -> "B"
                    PieceType.KNIGHT -> "N"
                    PieceType.PAWN -> "P"
                    else -> "P"
                }
                val isEnPassant = pieceType == "P" &&
                    lastMove.from.file != lastMove.to.file &&
                    capturedPiece == null
                val isCapture = capturedPiece != null || isEnPassant

                moveDetailsList.add(MoveDetails(
                    san = move,
                    from = fromSquare,
                    to = toSquare,
                    isCapture = isCapture,
                    pieceType = pieceType,
                    clockTime = parsedMove.clockTime
                ))
            }
        }

        val providedUsername = username ?: savedLichessUsername
        val whitePlayerName = game.players.white.user?.name ?: "White"
        val blackPlayerName = game.players.black.user?.name?.lowercase() ?: ""

        val usernameMatchesGame = providedUsername.isNotEmpty() &&
            (providedUsername.lowercase() == whitePlayerName.lowercase() ||
             providedUsername.lowercase() == blackPlayerName)

        val perspectivePlayer = if (usernameMatchesGame) providedUsername else whitePlayerName
        val userPlayedBlack = perspectivePlayer.lowercase() == blackPlayerName

        updateUiState {
            copy(
                isLoading = false,
                game = game,
                openingName = openingName,
                moves = validMoves,
                moveDetails = moveDetailsList,
                currentBoard = initialBoard,
                currentMoveIndex = -1,
                flippedBoard = userPlayedBlack,
                userPlayedBlack = userPlayedBlack,
                showRetrieveScreen = false,
                isExploringLine = false,
                exploringLineMoves = emptyList(),
                exploringLineMoveIndex = -1,
                savedGameMoveIndex = -1,
                currentStage = AnalysisStage.PREVIEW,
                previewScores = emptyMap(),
                analyseScores = emptyMap(),
                autoAnalysisIndex = -1
            )
        }

        analysisOrchestrator.startAnalysis()
    }

    fun loadAnalysedGameDirectly(analysedGame: AnalysedGame) {
        analysisOrchestrator.autoAnalysisJob?.cancel()

        val parsedMoves = PgnParser.parseMoves(analysedGame.pgn)

        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()
        boardHistory.clear()
        exploringLineHistory.clear()
        val tempBoard = ChessBoard()
        boardHistory.add(tempBoard.copy())

        for (move in parsedMoves) {
            val moveSuccess = tempBoard.makeMove(move) || tempBoard.makeUciMove(move)
            if (moveSuccess) {
                boardHistory.add(tempBoard.copy())
            }
        }

        val lichessGame = LichessGame(
            id = "analysed_${analysedGame.timestamp}",
            rated = false,
            variant = "standard",
            speed = analysedGame.speed ?: "unknown",
            perf = null,
            // For opening studies (White vs Black with no real result), treat as ongoing
            // This also handles previously mis-stored games with "1/2-1/2"
            status = when {
                analysedGame.result == "*" -> "*"
                analysedGame.result == "1-0" || analysedGame.result == "0-1" -> "mate"
                // Check if this looks like an opening study (no real winner)
                analysedGame.whiteName == "White" && analysedGame.blackName == "Black" -> "*"
                analysedGame.result == "1/2-1/2" -> "draw"
                else -> "*"  // Default to ongoing for unknown cases
            },
            winner = when (analysedGame.result) {
                "1-0" -> "white"
                "0-1" -> "black"
                else -> null
            },
            players = Players(
                white = Player(
                    user = User(name = analysedGame.whiteName, id = analysedGame.whiteName.lowercase()),
                    rating = null,
                    aiLevel = null
                ),
                black = Player(
                    user = User(name = analysedGame.blackName, id = analysedGame.blackName.lowercase()),
                    rating = null,
                    aiLevel = null
                )
            ),
            pgn = analysedGame.pgn,
            moves = null,
            clock = null,
            createdAt = analysedGame.timestamp,
            lastMoveAt = analysedGame.timestamp
        )

        val biggestChangeMoveIndex = findBiggestScoreChangeInScores(
            analysedGame.analyseScores, analysedGame.previewScores, analysedGame.moves.size
        )
        val validIndex = biggestChangeMoveIndex.coerceIn(-1, boardHistory.size - 2)
        val board = if (validIndex >= 0 && validIndex < boardHistory.size - 1) {
            boardHistory[validIndex + 1]
        } else {
            boardHistory.firstOrNull() ?: ChessBoard()
        }

        // Default to white player's perspective
        val userPlayedBlack = false

        updateUiState {
            copy(
                game = lichessGame,
                moves = analysedGame.moves,
                moveDetails = analysedGame.moveDetails,
                currentMoveIndex = validIndex,
                currentBoard = board.copy(),
                flippedBoard = userPlayedBlack,
                userPlayedBlack = userPlayedBlack,
                openingName = analysedGame.openingName,
                previewScores = analysedGame.previewScores,
                analyseScores = analysedGame.analyseScores,
                currentStage = AnalysisStage.MANUAL,
                autoAnalysisIndex = -1,
                isExploringLine = false,
                exploringLineMoves = emptyList(),
                exploringLineMoveIndex = -1,
                savedGameMoveIndex = -1
            )
        }

        gameStorage.saveCurrentAnalysedGame(analysedGame)

        val fenToAnalyze = board.getFen()

        viewModelScope.launch {
            restartStockfishAndAnalyze(fenToAnalyze)
            fetchOpeningExplorer()
        }
    }

    private fun findBiggestScoreChangeInScores(
        analyseScores: Map<Int, MoveScore>,
        previewScores: Map<Int, MoveScore>,
        totalMoves: Int
    ): Int {
        var biggestChangeIndex = 0
        var biggestChange = 0f

        for (i in 1 until totalMoves) {
            val prevScore = analyseScores[i - 1] ?: previewScores[i - 1]
            val currScore = analyseScores[i] ?: previewScores[i]

            if (prevScore != null && currScore != null) {
                val change = kotlin.math.abs(currScore.score - prevScore.score)
                if (change > biggestChange) {
                    biggestChange = change
                    biggestChangeIndex = i
                }
            }
        }

        return biggestChangeIndex
    }

    fun selectGameFromRetrieve(game: LichessGame) {
        val entry = getUiState().selectedRetrieveEntry ?: return
        updateUiState {
            copy(
                showSelectedRetrieveGames = false,
                showRetrieveScreen = false,
                selectedRetrieveEntry = null,
                selectedRetrieveGames = emptyList()
            )
        }
        loadGame(game, entry.server, entry.accountName)
    }

    fun selectAnalysedGame(game: AnalysedGame) {
        updateUiState { copy(showAnalysedGamesSelection = false, showRetrieveScreen = false) }
        loadAnalysedGameDirectly(game)
    }

    private fun storeRetrievedGames(games: List<LichessGame>, username: String, server: ChessServer) {
        gameStorage.storeRetrievedGames(games, username, server)
        val retrievesList = gameStorage.loadRetrievesList()
        updateUiState {
            copy(
                hasPreviousRetrieves = retrievesList.isNotEmpty(),
                previousRetrievesList = retrievesList
            )
        }
    }

    fun showPreviousRetrieves() {
        val retrievesList = gameStorage.loadRetrievesList()
        if (retrievesList.isEmpty()) return

        if (retrievesList.size == 1) {
            val entry = retrievesList.first()
            val games = gameStorage.loadGamesForRetrieve(entry)
            if (games.isNotEmpty()) {
                updateUiState {
                    copy(
                        showRetrieveScreen = false,
                        showSelectedRetrieveGames = true,
                        selectedRetrieveEntry = entry,
                        selectedRetrieveGames = games
                    )
                }
            }
        } else {
            updateUiState {
                copy(
                    showRetrieveScreen = false,
                    previousRetrievesList = retrievesList,
                    showPreviousRetrievesSelection = true
                )
            }
        }
    }

    fun dismissPreviousRetrievesSelection() {
        updateUiState { copy(showPreviousRetrievesSelection = false) }
    }

    fun selectPreviousRetrieve(entry: RetrievedGamesEntry) {
        val games = gameStorage.loadGamesForRetrieve(entry)
        if (games.isNotEmpty()) {
            updateUiState {
                copy(
                    showPreviousRetrievesSelection = false,
                    showSelectedRetrieveGames = true,
                    selectedRetrieveEntry = entry,
                    selectedRetrieveGames = games
                )
            }
        }
    }

    fun dismissSelectedRetrieveGames() {
        updateUiState {
            copy(
                showSelectedRetrieveGames = false,
                selectedRetrieveEntry = null,
                selectedRetrieveGames = emptyList()
            )
        }
    }

    fun showAnalysedGames() {
        val games = gameStorage.loadAnalysedGames()
        if (games.isNotEmpty()) {
            updateUiState {
                copy(
                    analysedGamesList = games,
                    showAnalysedGamesSelection = true,
                    showRetrieveScreen = false
                )
            }
        }
    }

    fun dismissAnalysedGamesSelection() {
        updateUiState { copy(showAnalysedGamesSelection = false) }
    }

    fun nextGameSelectionPage() {
        val state = getUiState()
        val currentPage = state.gameSelectionPage
        val pageSize = state.gameSelectionPageSize
        val currentGames = state.selectedRetrieveGames
        val hasMore = state.gameSelectionHasMore
        val entry = state.selectedRetrieveEntry ?: return

        val nextPageStartIndex = (currentPage + 1) * pageSize

        if (nextPageStartIndex >= currentGames.size && hasMore) {
            updateUiState { copy(gameSelectionLoading = true) }

            viewModelScope.launch {
                val newCount = currentGames.size + pageSize
                val gamesResult = repository.getLichessGames(entry.accountName, newCount)
                when (gamesResult) {
                    is Result.Success -> {
                        val fetchedGames = gamesResult.data
                        val gotMoreGames = fetchedGames.size > currentGames.size
                        if (fetchedGames.isNotEmpty()) {
                            storeRetrievedGames(fetchedGames, entry.accountName, entry.server)
                        }
                        updateUiState {
                            copy(
                                selectedRetrieveGames = fetchedGames,
                                gameSelectionLoading = false,
                                gameSelectionPage = if (gotMoreGames) currentPage + 1 else currentPage,
                                gameSelectionHasMore = fetchedGames.size >= newCount
                            )
                        }
                    }
                    is Result.Error -> {
                        updateUiState {
                            copy(
                                gameSelectionLoading = false,
                                gameSelectionHasMore = false
                            )
                        }
                    }
                }
            }
        } else if (nextPageStartIndex < currentGames.size) {
            updateUiState { copy(gameSelectionPage = currentPage + 1) }
        }
    }

    fun previousGameSelectionPage() {
        val currentPage = getUiState().gameSelectionPage
        if (currentPage > 0) {
            updateUiState { copy(gameSelectionPage = currentPage - 1) }
        }
    }

    // PGN file loading
    fun loadGamesFromPgnContent(pgnContent: String, onMultipleEvents: ((Boolean) -> Unit)? = null) {
        when (val result = repository.parseGamesFromPgnContent(pgnContent)) {
            is Result.Success -> {
                val games = result.data
                if (games.size == 1) {
                    selectPgnGame(games.first())
                } else {
                    val gamesByEvent = games.groupBy { game ->
                        game.pgn?.let { PgnParser.parseHeaders(it)["Event"] } ?: "Unknown Event"
                    }

                    updateUiState {
                        copy(
                            showPgnEventSelection = true,
                            pgnEvents = gamesByEvent.keys.toList().sorted(),
                            pgnGamesByEvent = gamesByEvent,
                            selectedPgnEvent = if (gamesByEvent.size == 1) gamesByEvent.keys.first() else null,
                            pgnGamesForSelectedEvent = if (gamesByEvent.size == 1) games else emptyList()
                        )
                    }
                    onMultipleEvents?.invoke(true)
                }
            }
            is Result.Error -> {
                updateUiState { copy(errorMessage = result.message) }
            }
        }
    }

    fun selectPgnEvent(event: String) {
        val games = getUiState().pgnGamesByEvent[event] ?: return
        updateUiState {
            copy(
                selectedPgnEvent = event,
                pgnGamesForSelectedEvent = games
            )
        }
    }

    fun backToPgnEventList() {
        updateUiState {
            copy(
                selectedPgnEvent = null,
                pgnGamesForSelectedEvent = emptyList()
            )
        }
    }

    fun dismissPgnEventSelection() {
        updateUiState {
            copy(
                showPgnEventSelection = false,
                pgnEvents = emptyList(),
                pgnGamesByEvent = emptyMap(),
                selectedPgnEvent = null,
                pgnGamesForSelectedEvent = emptyList()
            )
        }
    }

    fun selectPgnGameFromEvent(game: LichessGame) {
        dismissPgnEventSelection()
        val whiteName = game.players.white.user?.name ?: "White"
        loadGame(game, null, whiteName)
    }

    fun selectPgnGame(game: LichessGame) {
        updateUiState {
            copy(
                showGameSelection = false,
                showRetrieveScreen = false,
                showPgnEventSelection = false,
                pgnEvents = emptyList(),
                pgnGamesByEvent = emptyMap()
            )
        }
        val whiteName = game.players.white.user?.name ?: "White"
        loadGame(game, null, whiteName)
    }

    fun setLichessMaxGames(max: Int) {
        val validMax = max.coerceIn(1, 25)
        settingsPrefs.saveLichessMaxGames(validMax)
        updateUiState { copy(lichessMaxGames = validMax) }
    }
}
