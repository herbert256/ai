package com.eval.ui

import com.eval.chess.ChessBoard
import com.eval.chess.PieceType
import com.eval.chess.Square
import com.eval.audio.MoveSoundPlayer

/**
 * Handles board navigation and move exploration.
 */
internal class BoardNavigationManager(
    private val getUiState: () -> GameUiState,
    private val updateUiState: (GameUiState.() -> GameUiState) -> Unit,
    private val getBoardHistory: () -> MutableList<ChessBoard>,
    private val getExploringLineHistory: () -> MutableList<ChessBoard>,
    private val analysisOrchestrator: AnalysisOrchestrator,
    private val moveSoundPlayer: MoveSoundPlayer
) {
    /**
     * Check if navigation is allowed in the current stage.
     */
    fun canNavigate(): Boolean {
        return getUiState().currentStage != AnalysisStage.PREVIEW
    }

    /**
     * Handle navigation during Analyse stage - interrupts analysis and switches to Manual stage.
     */
    fun handleNavigationInterrupt(): Boolean {
        when (getUiState().currentStage) {
            AnalysisStage.PREVIEW -> return false
            AnalysisStage.ANALYSE -> {
                analysisOrchestrator.enterManualStageAtCurrentPosition()
                return false
            }
            AnalysisStage.MANUAL -> return true
        }
    }

    fun goToStart() {
        if (!handleNavigationInterrupt()) return

        val state = getUiState()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()

        if (state.isExploringLine) {
            val newBoard = exploringLineHistory.firstOrNull()?.copy() ?: ChessBoard()
            updateUiState {
                copy(
                    currentBoard = newBoard,
                    exploringLineMoveIndex = -1
                )
            }
            analysisOrchestrator.analyzePosition(newBoard)
        } else {
            if (state.currentStage == AnalysisStage.MANUAL) {
                analysisOrchestrator.restartAnalysisAtMove(-1)
            } else {
                val newBoard = boardHistory.firstOrNull()?.copy() ?: ChessBoard()
                updateUiState {
                    copy(
                        currentBoard = newBoard,
                        currentMoveIndex = -1
                    )
                }
                analysisOrchestrator.analyzePosition(newBoard)
            }
        }
    }

    fun goToEnd() {
        if (!handleNavigationInterrupt()) return

        val state = getUiState()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()

        if (state.isExploringLine) {
            val moves = state.exploringLineMoves
            if (moves.isEmpty()) {
                analysisOrchestrator.analyzePosition(state.currentBoard)
                return
            }
            val newBoard = exploringLineHistory.lastOrNull()?.copy() ?: ChessBoard()
            updateUiState {
                copy(
                    currentBoard = newBoard,
                    exploringLineMoveIndex = moves.size - 1
                )
            }
            analysisOrchestrator.analyzePosition(newBoard)
        } else {
            val moves = state.moves
            if (moves.isEmpty()) return
            if (state.currentStage == AnalysisStage.MANUAL) {
                analysisOrchestrator.restartAnalysisAtMove(moves.size - 1)
            } else {
                val newBoard = boardHistory.lastOrNull()?.copy() ?: ChessBoard()
                updateUiState {
                    copy(
                        currentBoard = newBoard,
                        currentMoveIndex = moves.size - 1
                    )
                }
                analysisOrchestrator.analyzePosition(newBoard)
            }
        }
    }

    fun goToMove(index: Int) {
        if (!handleNavigationInterrupt()) return

        val state = getUiState()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()
        val newBoard: ChessBoard

        if (state.isExploringLine) {
            val moves = state.exploringLineMoves
            if (index < -1 || index >= moves.size) return
            newBoard = exploringLineHistory.getOrNull(index + 1)?.copy() ?: ChessBoard()
            updateUiState {
                copy(
                    currentBoard = newBoard,
                    exploringLineMoveIndex = index
                )
            }
            playMoveSound()
        } else {
            val moves = state.moves
            if (index < -1 || index >= moves.size) return
            newBoard = boardHistory.getOrNull(index + 1)?.copy() ?: ChessBoard()
            updateUiState {
                copy(
                    currentBoard = newBoard,
                    currentMoveIndex = index
                )
            }
            playMoveSound(index)
        }
        analysisOrchestrator.analyzePosition(newBoard)
    }

    fun nextMove() {
        if (!handleNavigationInterrupt()) return

        val state = getUiState()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()

        if (state.isExploringLine) {
            val currentIndex = state.exploringLineMoveIndex
            val moves = state.exploringLineMoves
            if (currentIndex >= moves.size - 1) return
            val newIndex = currentIndex + 1
            val newBoard = exploringLineHistory.getOrNull(newIndex + 1)?.copy() ?: state.currentBoard
            updateUiState {
                copy(
                    currentBoard = newBoard,
                    exploringLineMoveIndex = newIndex
                )
            }
            playMoveSound()
            analysisOrchestrator.restartAnalysisForExploringLine()
        } else {
            val currentIndex = state.currentMoveIndex
            val moves = state.moves
            if (currentIndex >= moves.size - 1) return
            val newIndex = currentIndex + 1
            playMoveSound(newIndex)
            if (state.currentStage == AnalysisStage.MANUAL) {
                analysisOrchestrator.restartAnalysisAtMove(newIndex)
            } else {
                val newBoard = boardHistory.getOrNull(newIndex + 1)?.copy() ?: state.currentBoard
                updateUiState {
                    copy(
                        currentBoard = newBoard,
                        currentMoveIndex = newIndex
                    )
                }
                analysisOrchestrator.analyzePosition(newBoard)
            }
        }
    }

    fun prevMove() {
        if (!handleNavigationInterrupt()) return

        val state = getUiState()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()

        if (state.isExploringLine) {
            val currentIndex = state.exploringLineMoveIndex
            if (currentIndex < 0) return
            val newIndex = currentIndex - 1
            val newBoard = exploringLineHistory.getOrNull(newIndex + 1)?.copy() ?: ChessBoard()
            updateUiState {
                copy(
                    currentBoard = newBoard,
                    exploringLineMoveIndex = newIndex
                )
            }
            playMoveSound()
            analysisOrchestrator.restartAnalysisForExploringLine()
        } else {
            val currentIndex = state.currentMoveIndex
            if (currentIndex < 0) return
            val newIndex = currentIndex - 1
            playMoveSound(newIndex)
            if (state.currentStage == AnalysisStage.MANUAL) {
                analysisOrchestrator.restartAnalysisAtMove(newIndex)
            } else {
                val newBoard = boardHistory.getOrNull(newIndex + 1)?.copy() ?: ChessBoard()
                updateUiState {
                    copy(
                        currentBoard = newBoard,
                        currentMoveIndex = newIndex
                    )
                }
                analysisOrchestrator.analyzePosition(newBoard)
            }
        }
    }

    fun exploreLine(pv: String, moveIndex: Int = 0) {
        if (pv.isBlank()) return

        val state = getUiState()
        val exploringLineHistory = getExploringLineHistory()
        val savedMoveIndex = state.currentMoveIndex
        val startBoard = state.currentBoard.copy()

        val uciMoves = pv.split(" ").filter { it.isNotBlank() }
        exploringLineHistory.clear()
        exploringLineHistory.add(startBoard)

        val tempBoard = startBoard.copy()
        for (uciMove in uciMoves) {
            if (tempBoard.makeUciMove(uciMove)) {
                exploringLineHistory.add(tempBoard.copy())
            } else {
                break
            }
        }

        val targetIndex = moveIndex.coerceIn(-1, exploringLineHistory.size - 2)

        updateUiState {
            copy(
                isExploringLine = true,
                exploringLineMoves = uciMoves.take(exploringLineHistory.size - 1),
                exploringLineMoveIndex = targetIndex,
                savedGameMoveIndex = savedMoveIndex,
                currentBoard = exploringLineHistory.getOrNull(targetIndex + 1)?.copy() ?: startBoard
            )
        }

        analysisOrchestrator.restartAnalysisForExploringLine()
    }

    fun backToOriginalGame() {
        val state = getUiState()
        val boardHistory = getBoardHistory()
        val exploringLineHistory = getExploringLineHistory()
        val savedIndex = state.savedGameMoveIndex
        exploringLineHistory.clear()

        updateUiState {
            copy(
                isExploringLine = false,
                exploringLineMoves = emptyList(),
                exploringLineMoveIndex = -1,
                savedGameMoveIndex = -1,
                currentBoard = boardHistory.getOrNull(savedIndex + 1)?.copy() ?: ChessBoard(),
                currentMoveIndex = savedIndex
            )
        }

        analysisOrchestrator.restartAnalysisForExploringLine()
    }

    fun flipBoard() {
        updateUiState { copy(flippedBoard = !flippedBoard) }
    }

    /**
     * Make a manual move on the board (from user drag-and-drop).
     */
    fun makeManualMove(from: Square, to: Square) {
        val state = getUiState()
        if (state.currentStage != AnalysisStage.MANUAL) return

        val currentBoard = state.currentBoard
        val exploringLineHistory = getExploringLineHistory()

        if (!currentBoard.isLegalMove(from, to)) return

        val promotion = if (currentBoard.needsPromotion(from, to)) {
            PieceType.QUEEN
        } else null

        val newBoard = currentBoard.copy()
        if (!newBoard.makeMoveFromSquares(from, to, promotion)) return

        if (state.isExploringLine) {
            exploringLineHistory.add(newBoard.copy())
            val newMoveIndex = state.exploringLineMoveIndex + 1
            val uciMove = from.toAlgebraic() + to.toAlgebraic() + (promotion?.let {
                when (it) {
                    PieceType.QUEEN -> "q"
                    PieceType.ROOK -> "r"
                    PieceType.BISHOP -> "b"
                    PieceType.KNIGHT -> "n"
                    else -> ""
                }
            } ?: "")

            updateUiState {
                copy(
                    currentBoard = newBoard,
                    exploringLineMoves = exploringLineMoves + uciMove,
                    exploringLineMoveIndex = newMoveIndex
                )
            }
        } else {
            exploringLineHistory.clear()
            exploringLineHistory.add(currentBoard.copy())
            exploringLineHistory.add(newBoard.copy())

            val uciMove = from.toAlgebraic() + to.toAlgebraic() + (promotion?.let {
                when (it) {
                    PieceType.QUEEN -> "q"
                    PieceType.ROOK -> "r"
                    PieceType.BISHOP -> "b"
                    PieceType.KNIGHT -> "n"
                    else -> ""
                }
            } ?: "")

            updateUiState {
                copy(
                    isExploringLine = true,
                    exploringLineMoves = listOf(uciMove),
                    exploringLineMoveIndex = 0,
                    savedGameMoveIndex = currentMoveIndex,
                    currentBoard = newBoard
                )
            }
        }

        analysisOrchestrator.restartAnalysisForExploringLine()
    }

    /**
     * Play move sound if enabled in settings.
     */
    private fun playMoveSound(moveIndex: Int = -1) {
        val state = getUiState()
        if (!state.generalSettings.moveSoundsEnabled) return

        val moveDetails = state.moveDetails.getOrNull(moveIndex)
        if (moveDetails != null) {
            val isCastle = moveDetails.pieceType == "K" &&
                kotlin.math.abs(moveDetails.from[0] - moveDetails.to[0]) > 1
            moveSoundPlayer.playMove(
                isCapture = moveDetails.isCapture,
                isCheck = false,
                isCastle = isCastle
            )
        } else {
            moveSoundPlayer.playMoveSound()
        }
    }
}
