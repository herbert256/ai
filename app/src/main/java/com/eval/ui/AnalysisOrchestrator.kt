package com.eval.ui

import com.eval.chess.ChessBoard
import com.eval.chess.PieceColor
import com.eval.stockfish.StockfishEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the three-stage analysis pipeline (Preview → Analyse → Manual).
 * Orchestrates Stockfish analysis across different stages with appropriate settings.
 */
internal class AnalysisOrchestrator(
    private val stockfish: StockfishEngine,
    private val getUiState: () -> GameUiState,
    private val updateUiState: (GameUiState.() -> GameUiState) -> Unit,
    private val viewModelScope: CoroutineScope,
    private val getBoardHistory: () -> MutableList<ChessBoard>,
    private val storeAnalysedGame: () -> Unit,
    private val fetchOpeningExplorer: () -> Unit
) {
    var autoAnalysisJob: Job? = null
    var manualAnalysisJob: Job? = null
    var currentAnalysisFen: String? = null
    val analysisRequestId = AtomicLong(0)

    fun configureForPreviewStage() {
        val settings = getUiState().stockfishSettings.previewStage
        stockfish.configure(settings.threads, settings.hashMb, 1, settings.useNnue)
    }

    fun configureForAnalyseStage() {
        val settings = getUiState().stockfishSettings.analyseStage
        stockfish.configure(settings.threads, settings.hashMb, 1, settings.useNnue)
    }

    fun configureForManualStage() {
        val settings = getUiState().stockfishSettings.manualStage
        stockfish.configure(settings.threads, settings.hashMb, settings.multiPv, settings.useNnue)
    }

    /**
     * Build the list of move indices for analysis based on the current stage.
     * Preview stage: Forward sequence (move 1 to end)
     * Analyse stage: Backwards sequence (end to move 1), unless board is visible then forward
     */
    private fun buildMoveIndices(): List<Int> {
        val state = getUiState()
        val moves = state.moves
        val showBoardInAnalyse = state.interfaceVisibility.analyseStage.showBoard
        return when (state.currentStage) {
            AnalysisStage.PREVIEW -> (0 until moves.size).toList()
            AnalysisStage.ANALYSE -> if (showBoardInAnalyse) {
                (0 until moves.size).toList()
            } else {
                (moves.size - 1 downTo 0).toList()
            }
            AnalysisStage.MANUAL -> emptyList()
        }
    }

    /**
     * Start the three-stage analysis flow: Preview → Analyse → Manual.
     */
    fun startAnalysis() {
        if (!getUiState().stockfishReady) return

        autoAnalysisJob?.cancel()

        autoAnalysisJob = viewModelScope.launch {
            try {
                val moves = getUiState().moves
                if (moves.isEmpty()) {
                    android.util.Log.e("Analysis", "EXIT: moves list is empty")
                    enterManualStageInternal(-1)
                    return@launch
                }

                val boardHistory = getBoardHistory()
                val expectedBoardHistorySize = boardHistory.size
                android.util.Log.d("Analysis", "START: moves=${moves.size}, boardHistory=$expectedBoardHistorySize")

                // ===== PREVIEW STAGE =====
                android.util.Log.d("Analysis", "Starting PREVIEW stage")

                updateUiState {
                    copy(
                        currentStage = AnalysisStage.PREVIEW,
                        previewScores = emptyMap(),
                        analyseScores = emptyMap(),
                        autoAnalysisCurrentScore = null,
                        remainingAnalysisMoves = buildMoveIndices()
                    )
                }

                stockfish.stop()
                var ready = stockfish.restart()
                updateUiState { copy(stockfishReady = ready) }
                if (!ready) {
                    android.util.Log.e("Analysis", "Failed to start Stockfish for Preview stage")
                    enterManualStageInternal(-1)
                    return@launch
                }

                stockfish.newGame()
                configureForPreviewStage()
                delay(50)

                val previewTimeMs = (getUiState().stockfishSettings.previewStage.secondsForMove * 1000).toInt()
                val previewComplete = runStageAnalysis(
                    stageName = "PREVIEW",
                    timePerMoveMs = previewTimeMs,
                    expectedBoardHistorySize = expectedBoardHistorySize,
                    storeScore = { moveIndex, score ->
                        updateUiState {
                            copy(
                                previewScores = previewScores + (moveIndex to score),
                                autoAnalysisCurrentScore = score
                            )
                        }
                    },
                    configureEngine = { configureForPreviewStage() }
                )

                if (!previewComplete) {
                    android.util.Log.d("Analysis", "Preview stage was interrupted or failed")
                    return@launch
                }

                // ===== ANALYSE STAGE =====
                android.util.Log.d("Analysis", "Starting ANALYSE stage")

                updateUiState {
                    copy(
                        currentStage = AnalysisStage.ANALYSE,
                        autoAnalysisCurrentScore = null,
                        remainingAnalysisMoves = buildMoveIndices()
                    )
                }

                stockfish.stop()
                ready = stockfish.restart()
                updateUiState { copy(stockfishReady = ready) }
                if (!ready) {
                    android.util.Log.e("Analysis", "Failed to start Stockfish for Analyse stage")
                    enterManualStageInternal(findBiggestScoreChangeMove())
                    return@launch
                }

                stockfish.newGame()
                configureForAnalyseStage()
                delay(50)

                val analyseTimeMs = (getUiState().stockfishSettings.analyseStage.secondsForMove * 1000).toInt()
                val analyseComplete = runStageAnalysis(
                    stageName = "ANALYSE",
                    timePerMoveMs = analyseTimeMs,
                    expectedBoardHistorySize = expectedBoardHistorySize,
                    storeScore = { moveIndex, score ->
                        updateUiState {
                            copy(
                                analyseScores = analyseScores + (moveIndex to score),
                                autoAnalysisCurrentScore = score
                            )
                        }
                    },
                    configureEngine = { configureForAnalyseStage() }
                )

                if (!analyseComplete) {
                    android.util.Log.d("Analysis", "Analyse stage was interrupted")
                    return@launch
                }

                // ===== MANUAL STAGE =====
                android.util.Log.d("Analysis", "Analysis complete, entering MANUAL stage")

                storeAnalysedGame()

                val biggestChangeMoveIndex = findBiggestScoreChangeMove()
                enterManualStageInternal(biggestChangeMoveIndex)

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Analysis", "Error during analysis: ${e.message}")
                updateUiState {
                    copy(
                        currentStage = AnalysisStage.MANUAL,
                        autoAnalysisIndex = -1
                    )
                }
            }
        }
    }

    /**
     * Run a single stage of analysis (Preview or Analyse).
     * Returns true if completed successfully, false if interrupted or failed.
     */
    private suspend fun runStageAnalysis(
        stageName: String,
        timePerMoveMs: Int,
        expectedBoardHistorySize: Int,
        storeScore: (Int, MoveScore) -> Unit,
        configureEngine: () -> Unit
    ): Boolean {
        val moveIndices = buildMoveIndices()
        android.util.Log.d("Analysis", "$stageName: analyzing ${moveIndices.size} moves, time=${timePerMoveMs}ms")

        val remainingMoves = moveIndices.toMutableList()
        var analyzedCount = 0
        val boardHistory = getBoardHistory()

        for (moveIndex in moveIndices) {
            yield()

            if (boardHistory.size != expectedBoardHistorySize) {
                android.util.Log.e("Analysis", "$stageName EXIT: boardHistory changed")
                return false
            }

            val board = boardHistory.getOrNull(moveIndex + 1) ?: continue

            remainingMoves.remove(moveIndex)

            updateUiState {
                copy(
                    autoAnalysisIndex = moveIndex,
                    currentBoard = board,
                    currentMoveIndex = moveIndex,
                    autoAnalysisCurrentScore = null,
                    analysisResult = null,
                    remainingAnalysisMoves = remainingMoves.toList()
                )
            }

            val fen = board.getFen()

            stockfish.analyzeWithTime(fen, timePerMoveMs)

            val completed = stockfish.waitForCompletion(timePerMoveMs.toLong() + 2000)
            if (!completed) {
                stockfish.stop()
                delay(100)
            }

            if (!stockfish.isReady.value) {
                android.util.Log.w("Analysis", "$stageName: Engine died at move $moveIndex, restarting...")
                val restarted = stockfish.restart()
                if (restarted) {
                    stockfish.newGame()
                    configureEngine()
                    delay(100)

                    stockfish.analyzeWithTime(fen, timePerMoveMs)
                    val retryCompleted = stockfish.waitForCompletion(timePerMoveMs.toLong() + 2000)
                    if (!retryCompleted) {
                        stockfish.stop()
                        delay(100)
                    }
                } else {
                    android.util.Log.e("Analysis", "$stageName: Failed to restart engine")
                    return false
                }
            }

            yield()

            if (boardHistory.size != expectedBoardHistorySize) {
                android.util.Log.e("Analysis", "$stageName EXIT after wait: boardHistory changed")
                return false
            }

            val result = stockfish.analysisResult.value
            if (result != null) {
                val bestLine = result.bestLine
                if (bestLine != null) {
                    analyzedCount++
                    val isWhiteToMove = board.getTurn() == PieceColor.WHITE
                    val adjustedScore = if (isWhiteToMove) bestLine.score else -bestLine.score
                    val adjustedMateIn = if (isWhiteToMove) bestLine.mateIn else -bestLine.mateIn

                    val score = MoveScore(
                        score = adjustedScore,
                        isMate = bestLine.isMate,
                        mateIn = adjustedMateIn,
                        depth = result.depth,
                        nodes = result.nodes,
                        nps = result.nps
                    )
                    storeScore(moveIndex, score)
                }
            }
        }

        android.util.Log.d("Analysis", "$stageName completed: analyzed=$analyzedCount out of ${moveIndices.size} moves")
        return true
    }

    /**
     * Find the move index with the biggest score change compared to the previous move.
     */
    fun findBiggestScoreChangeMove(): Int {
        val state = getUiState()
        // Merge scores: use analyseScores where available, otherwise previewScores
        val scores = if (state.analyseScores.isNotEmpty()) {
            state.previewScores + state.analyseScores
        } else {
            state.previewScores
        }
        if (scores.size < 2) return 0

        var maxChange = 0f
        var maxChangeIndex = 0

        val sortedIndices = scores.keys.sorted()
        for (i in 1 until sortedIndices.size) {
            val currentIndex = sortedIndices[i]
            val prevIndex = sortedIndices[i - 1]
            val currentScore = scores[currentIndex]?.score ?: continue
            val prevScore = scores[prevIndex]?.score ?: continue

            val change = kotlin.math.abs(currentScore - prevScore)
            if (change > maxChange) {
                maxChange = change
                maxChangeIndex = currentIndex
            }
        }

        return maxChangeIndex
    }

    /**
     * Calculate move qualities based on evaluation changes.
     */
    fun calculateMoveQualities(): Map<Int, MoveQuality> {
        val state = getUiState()
        // Merge scores: use analyseScores where available, otherwise previewScores
        val scores = if (state.analyseScores.isNotEmpty()) {
            state.previewScores + state.analyseScores
        } else {
            state.previewScores
        }
        if (scores.isEmpty()) return emptyMap()

        val qualities = mutableMapOf<Int, MoveQuality>()
        val userPlayedBlack = state.userPlayedBlack

        for (moveIndex in scores.keys) {
            val prevSameColorIndex = moveIndex - 2

            if (prevSameColorIndex < 0) {
                qualities[moveIndex] = MoveQuality.NORMAL
                continue
            }

            val currentScore = scores[moveIndex]?.score ?: continue
            val prevScore = scores[prevSameColorIndex]?.score ?: continue

            val isWhiteMove = moveIndex % 2 == 0
            val isUserMove = if (userPlayedBlack) !isWhiteMove else isWhiteMove

            val change = if (userPlayedBlack) {
                -(currentScore - prevScore)
            } else {
                currentScore - prevScore
            }

            val adjustedChange = if (isUserMove) change else -change

            val quality = when {
                adjustedChange <= -MoveQualityThresholds.BLUNDER -> MoveQuality.BLUNDER
                adjustedChange <= -MoveQualityThresholds.MISTAKE -> MoveQuality.MISTAKE
                adjustedChange <= -MoveQualityThresholds.DUBIOUS -> MoveQuality.DUBIOUS
                adjustedChange >= MoveQualityThresholds.BRILLIANT -> MoveQuality.BRILLIANT
                adjustedChange >= MoveQualityThresholds.GOOD -> MoveQuality.GOOD
                else -> MoveQuality.NORMAL
            }

            qualities[moveIndex] = quality
        }

        return qualities
    }

    /**
     * Internal function to enter Manual stage at a specific move.
     */
    fun enterManualStageInternal(moveIndex: Int) {
        val moveQualities = calculateMoveQualities()
        val boardHistory = getBoardHistory()

        viewModelScope.launch {
            autoAnalysisJob?.cancel()
            stockfish.stop()

            val validIndex = moveIndex.coerceIn(-1, boardHistory.size - 2)
            val board = boardHistory.getOrNull(validIndex + 1) ?: ChessBoard()

            val fenToAnalyze = board.getFen()
            currentAnalysisFen = fenToAnalyze
            val thisRequestId = analysisRequestId.incrementAndGet()

            updateUiState {
                copy(
                    currentStage = AnalysisStage.MANUAL,
                    autoAnalysisIndex = -1,
                    currentMoveIndex = validIndex,
                    currentBoard = board.copy(),
                    autoAnalysisCurrentScore = null,
                    remainingAnalysisMoves = emptyList(),
                    stockfishReady = false,
                    analysisResult = null,
                    analysisResultFen = null,
                    moveQualities = moveQualities
                )
            }

            val ready = stockfish.restart()
            updateUiState { copy(stockfishReady = ready) }

            if (ready) {
                delay(200)
                stockfish.newGame()
                delay(100)
                configureForManualStage()
                delay(100)
                ensureStockfishAnalysis(fenToAnalyze, thisRequestId)
                fetchOpeningExplorer()
            }
        }
    }

    /**
     * Enter Manual stage at the current position.
     */
    fun enterManualStageAtCurrentPosition() {
        val currentIndex = getUiState().currentMoveIndex
        enterManualStageInternal(currentIndex)
    }

    /**
     * Enter Manual stage at the move with the biggest score change.
     */
    fun enterManualStageAtBiggestChange() {
        if (getUiState().currentStage != AnalysisStage.ANALYSE) return
        val biggestChangeMoveIndex = findBiggestScoreChangeMove()
        enterManualStageInternal(biggestChangeMoveIndex)
    }

    /**
     * Enter Manual stage at a specific move index.
     */
    fun enterManualStageAtMove(moveIndex: Int) {
        if (getUiState().currentStage == AnalysisStage.PREVIEW) return
        enterManualStageInternal(moveIndex)
    }

    /**
     * Ensure Stockfish analysis is running and producing results in manual stage.
     */
    suspend fun ensureStockfishAnalysis(fen: String, requestId: Long) {
        val maxRetries = 2
        var attempt = 0

        while (attempt < maxRetries) {
            if (!getUiState().stockfishReady) {
                val ready = stockfish.restart()
                updateUiState { copy(stockfishReady = ready) }
                if (!ready) {
                    attempt++
                    continue
                }
                configureForManualStage()
            }

            val depth = getUiState().stockfishSettings.manualStage.depth
            stockfish.analyze(fen, depth)

            var waitTime = 0
            val maxWaitTime = 2000
            val checkInterval = 50L

            var gotFirstResult = false
            while (true) {
                delay(checkInterval)

                if (analysisRequestId.get() != requestId) {
                    return
                }

                if (getUiState().currentStage != AnalysisStage.MANUAL) {
                    return
                }

                val result = stockfish.analysisResult.value
                if (result != null) {
                    if (analysisRequestId.get() == requestId) {
                        updateUiState {
                            copy(
                                analysisResult = result,
                                analysisResultFen = fen
                            )
                        }
                        gotFirstResult = true
                    } else {
                        return
                    }
                }

                if (!gotFirstResult) {
                    waitTime += checkInterval.toInt()
                    if (waitTime >= maxWaitTime) {
                        break
                    }
                }
            }

            android.util.Log.w("Analysis", "No Stockfish results after ${maxWaitTime}ms, restarting (attempt ${attempt + 1})")

            stockfish.stop()
            updateUiState { copy(stockfishReady = false) }

            val ready = stockfish.restart()
            updateUiState { copy(stockfishReady = ready) }

            if (ready) {
                configureForManualStage()
            }

            attempt++
        }
    }

    /**
     * Analyze a specific board position.
     */
    fun analyzePosition(board: ChessBoard) {
        if (!getUiState().analysisEnabled) return

        manualAnalysisJob?.cancel()

        val thisRequestId = analysisRequestId.incrementAndGet()

        val fenToAnalyze = board.getFen()
        currentAnalysisFen = fenToAnalyze
        updateUiState { copy(analysisResult = null, analysisResultFen = null) }

        if (getUiState().currentStage != AnalysisStage.MANUAL) {
            return
        }

        manualAnalysisJob = viewModelScope.launch {
            ensureStockfishAnalysis(fenToAnalyze, thisRequestId)
        }
    }

    /**
     * Restart Stockfish analysis for exploring line moves.
     */
    fun restartAnalysisForExploringLine() {
        manualAnalysisJob?.cancel()

        viewModelScope.launch {
            stockfish.stop()

            val thisRequestId = analysisRequestId.incrementAndGet()

            val board = getUiState().currentBoard
            val fenToAnalyze = board.getFen()
            currentAnalysisFen = fenToAnalyze

            updateUiState {
                copy(analysisResultFen = null)
            }

            delay(50)

            stockfish.newGame()
            delay(50)

            if (getUiState().currentStage == AnalysisStage.MANUAL) {
                ensureStockfishAnalysis(fenToAnalyze, thisRequestId)
            }
        }
    }

    /**
     * Restart analysis at a specific move.
     */
    fun restartAnalysisAtMove(moveIndex: Int) {
        manualAnalysisJob?.cancel()

        val boardHistory = getBoardHistory()
        val validIndex = moveIndex.coerceIn(-1, boardHistory.size - 2)
        val board = boardHistory.getOrNull(validIndex + 1) ?: ChessBoard()

        viewModelScope.launch {
            stockfish.stop()

            val thisRequestId = analysisRequestId.incrementAndGet()

            val fenToAnalyze = board.getFen()
            currentAnalysisFen = fenToAnalyze

            val state = getUiState()
            val openingName = if (validIndex >= 0 && state.moves.isNotEmpty()) {
                com.eval.data.OpeningBook.getOpeningName(state.moves, validIndex)
            } else null

            updateUiState {
                copy(
                    currentMoveIndex = validIndex,
                    currentBoard = board.copy(),
                    currentOpeningName = openingName,
                    analysisResultFen = null
                )
            }

            delay(50)

            stockfish.newGame()
            delay(50)

            if (getUiState().currentStage == AnalysisStage.MANUAL) {
                ensureStockfishAnalysis(fenToAnalyze, thisRequestId)
                fetchOpeningExplorer()
            }
        }
    }

    fun stop() {
        autoAnalysisJob?.cancel()
        manualAnalysisJob?.cancel()
        stockfish.stop()
    }
}
