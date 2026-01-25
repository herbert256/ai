package com.eval.ui

import com.eval.audio.MoveSoundPlayer
import com.eval.chess.PieceType
import com.eval.chess.Square
import com.eval.data.ChessRepository
import com.eval.data.LiveGameEvent
import com.eval.data.StreamMoveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Manages live game following functionality.
 */
internal class LiveGameManager(
    private val repository: ChessRepository,
    private val getUiState: () -> GameUiState,
    private val updateUiState: (GameUiState.() -> GameUiState) -> Unit,
    private val viewModelScope: CoroutineScope,
    private val moveSoundPlayer: MoveSoundPlayer
) {
    private var liveGameJob: Job? = null

    /**
     * Start following a live game.
     */
    fun startLiveFollow(gameId: String) {
        stopLiveFollow()

        updateUiState {
            copy(
                isLiveGame = true,
                liveGameId = gameId,
                liveStreamConnected = false
            )
        }

        liveGameJob = viewModelScope.launch {
            repository.streamLiveGame(gameId).collect { event ->
                when (event) {
                    is LiveGameEvent.Connected -> {
                        updateUiState { copy(liveStreamConnected = true) }
                    }
                    is LiveGameEvent.Disconnected -> {
                        updateUiState { copy(liveStreamConnected = false) }
                    }
                    is LiveGameEvent.GameInfo -> {
                        // Initial game info received
                    }
                    is LiveGameEvent.Move -> {
                        handleLiveMove(event.data)
                    }
                    is LiveGameEvent.GameEnd -> {
                        updateUiState {
                            copy(
                                isLiveGame = false,
                                liveStreamConnected = false
                            )
                        }
                        val currentGame = getUiState().game
                        if (currentGame != null) {
                            updateUiState {
                                copy(
                                    game = currentGame.copy(
                                        winner = event.winner,
                                        status = event.status ?: "ended"
                                    )
                                )
                            }
                        }
                    }
                    is LiveGameEvent.Error -> {
                        updateUiState {
                            copy(errorMessage = "Live stream: ${event.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle a new move from the live stream.
     */
    private fun handleLiveMove(moveData: StreamMoveData) {
        val uciMove = moveData.lm ?: return
        val state = getUiState()
        val currentMoves = state.moves.toMutableList()
        val currentMoveDetails = state.moveDetails.toMutableList()

        currentMoves.add(uciMove)

        val board = state.currentBoard.copy()
        val from = uciMove.substring(0, 2)
        val to = uciMove.substring(2, 4)
        val fromSquare = Square.fromAlgebraic(from)
        val toSquare = Square.fromAlgebraic(to)

        if (fromSquare != null && toSquare != null) {
            val piece = board.getPiece(fromSquare)
            val isCapture = board.getPiece(toSquare) != null

            val promotion = if (uciMove.length > 4) {
                when (uciMove[4]) {
                    'q' -> PieceType.QUEEN
                    'r' -> PieceType.ROOK
                    'b' -> PieceType.BISHOP
                    'n' -> PieceType.KNIGHT
                    else -> null
                }
            } else null

            board.makeMoveFromSquares(fromSquare, toSquare, promotion)

            val pieceType = when (piece?.type) {
                PieceType.KING -> "K"
                PieceType.QUEEN -> "Q"
                PieceType.ROOK -> "R"
                PieceType.BISHOP -> "B"
                PieceType.KNIGHT -> "N"
                PieceType.PAWN -> "P"
                else -> "P"
            }

            val clockTime = if (currentMoves.size % 2 == 1) {
                moveData.bc?.let { formatClockSeconds(it) }
            } else {
                moveData.wc?.let { formatClockSeconds(it) }
            }

            val moveDetail = MoveDetails(
                san = uciMove,
                from = from,
                to = to,
                isCapture = isCapture,
                pieceType = pieceType,
                clockTime = clockTime
            )
            currentMoveDetails.add(moveDetail)

            val autoFollow = state.autoFollowLive
            val newMoveIndex = if (autoFollow) currentMoves.size - 1 else state.currentMoveIndex

            updateUiState {
                copy(
                    moves = currentMoves,
                    moveDetails = currentMoveDetails,
                    currentMoveIndex = newMoveIndex,
                    currentBoard = if (autoFollow) board else currentBoard
                )
            }

            if (state.generalSettings.moveSoundsEnabled && autoFollow) {
                moveSoundPlayer.playMove(isCapture = isCapture, isCheck = false, isCastle = false)
            }
        }
    }

    private fun formatClockSeconds(seconds: Int): String {
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, mins, secs)
        } else {
            "%d:%02d".format(mins, secs)
        }
    }

    /**
     * Stop following a live game.
     */
    fun stopLiveFollow() {
        liveGameJob?.cancel()
        liveGameJob = null
        updateUiState {
            copy(
                isLiveGame = false,
                liveGameId = null,
                liveStreamConnected = false
            )
        }
    }

    /**
     * Toggle auto-follow mode for live games.
     */
    fun toggleAutoFollowLive() {
        updateUiState {
            copy(autoFollowLive = !autoFollowLive)
        }
    }

    fun cancel() {
        liveGameJob?.cancel()
        liveGameJob = null
    }
}
