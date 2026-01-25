package com.eval.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eval.chess.PieceColor
import com.eval.chess.Square
import com.eval.data.AiService

// Chess piece Unicode symbols for game display
private const val WHITE_KING = "♔"
private const val WHITE_QUEEN = "♕"
private const val WHITE_ROOK = "♖"
private const val WHITE_BISHOP = "♗"
private const val WHITE_KNIGHT = "♘"
private const val WHITE_PAWN = "♙"
private const val BLACK_KING = "♚"
private const val BLACK_QUEEN = "♛"
private const val BLACK_ROOK = "♜"
private const val BLACK_BISHOP = "♝"
private const val BLACK_KNIGHT = "♞"
private const val BLACK_PAWN = "♟"

/**
 * Get just the piece symbol from a SAN move (for use with separate coordinates).
 */
private fun getPieceSymbolFromSan(move: String, isWhite: Boolean): String {
    if (move.isEmpty()) return ""

    // Handle castling
    if (move == "O-O" || move == "O-O-O") {
        return if (isWhite) WHITE_KING else BLACK_KING
    }

    val pieceChar = move.first()
    return when {
        pieceChar == 'K' -> if (isWhite) WHITE_KING else BLACK_KING
        pieceChar == 'Q' -> if (isWhite) WHITE_QUEEN else BLACK_QUEEN
        pieceChar == 'R' -> if (isWhite) WHITE_ROOK else BLACK_ROOK
        pieceChar == 'B' -> if (isWhite) WHITE_BISHOP else BLACK_BISHOP
        pieceChar == 'N' -> if (isWhite) WHITE_KNIGHT else BLACK_KNIGHT
        pieceChar.isLowerCase() -> if (isWhite) WHITE_PAWN else BLACK_PAWN // Pawn move
        else -> if (isWhite) WHITE_PAWN else BLACK_PAWN
    }
}

/**
 * Main game content composable displaying the chess board, player bars, analysis, and moves list.
 */
@Composable
fun GameContent(
    uiState: GameUiState,
    viewModel: GameViewModel
) {
    val game = uiState.game ?: return

    // Determine whose turn it is
    val turn = uiState.currentBoard.getTurn()
    val isWhiteTurn = turn == PieceColor.WHITE

    // Format initial clock time from game settings (e.g., "10:00" for 10 minutes)
    val initialClockTime = remember(game.clock) {
        game.clock?.let { clock ->
            val totalSeconds = clock.initial
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (seconds > 0) "%d:%02d".format(minutes, seconds) else "%d:00".format(minutes)
        }
    }

    // Get clock times for each player (find most recent clock for each color)
    val whiteClockTime = remember(uiState.currentMoveIndex, uiState.moveDetails, initialClockTime) {
        // White moves are at even indices (0, 2, 4, ...)
        // Start from most recent even index
        val startIdx = if (uiState.currentMoveIndex % 2 == 0) uiState.currentMoveIndex else uiState.currentMoveIndex - 1
        if (startIdx >= 0) {
            (startIdx downTo 0 step 2)
                .firstNotNullOfOrNull { idx -> uiState.moveDetails.getOrNull(idx)?.clockTime }
        } else {
            // At start position, use initial time from game settings
            initialClockTime
        }
    }
    val blackClockTime = remember(uiState.currentMoveIndex, uiState.moveDetails, initialClockTime) {
        // Black moves are at odd indices (1, 3, 5, ...)
        // Start from most recent odd index
        val startIdx = if (uiState.currentMoveIndex % 2 == 1) uiState.currentMoveIndex else uiState.currentMoveIndex - 1
        if (startIdx >= 1) {
            (startIdx downTo 1 step 2)
                .firstNotNullOfOrNull { idx -> uiState.moveDetails.getOrNull(idx)?.clockTime }
        } else {
            // At start position, use initial time from game settings
            initialClockTime
        }
    }

    // Player names and ratings
    val whiteName = game.players.white.user?.name
        ?: game.players.white.aiLevel?.let { "Stockfish $it" }
        ?: "Anonymous"
    val blackName = game.players.black.user?.name
        ?: game.players.black.aiLevel?.let { "Stockfish $it" }
        ?: "Anonymous"
    val whiteRating = game.players.white.rating
    val blackRating = game.players.black.rating

    // Result bar composable - shows move info, depth/nodes, and score
    val ResultBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF555555), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val moveIndex = uiState.currentMoveIndex
            val currentMove = uiState.moves.getOrNull(moveIndex)
            val isWhiteMove = moveIndex % 2 == 0
            val lastMove = uiState.currentBoard.getLastMove()

            // Determine which score/analysis to show - prefer analyse scores, fall back to preview
            val storedScore = uiState.analyseScores[moveIndex] ?: uiState.previewScores[moveIndex]
            val liveResult = uiState.analysisResult
            val isManualMode = uiState.currentStage == AnalysisStage.MANUAL

            if (currentMove != null && moveIndex >= 0) {
                val completeMoveNumber = (moveIndex / 2) + 1
                val totalCompleteMoves = (uiState.moves.size + 1) / 2

                val pieceSymbol = getPieceSymbolFromSan(currentMove, isWhiteMove)
                val fromSquare = lastMove?.from?.toAlgebraic() ?: ""
                val toSquare = lastMove?.to?.toAlgebraic() ?: ""
                val moveColor = if (isWhiteMove) Color.White else Color.Black
                val currentMoveDetails = uiState.moveDetails.getOrNull(moveIndex)
                val separator = if (currentMoveDetails?.isCapture == true) "x" else "-"

                // LEFT section - move number
                Text(
                    text = "Move: $completeMoveNumber/$totalCompleteMoves",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                // MIDDLE section - piece and coordinates
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pieceSymbol,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        color = moveColor
                    )
                    Text(
                        text = " $fromSquare$separator$toSquare",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = moveColor
                    )
                }

                // RIGHT: Score (from player's perspective) + delta from previous move
                val isCurrentlyAnalyzing = uiState.currentStage != AnalysisStage.MANUAL && uiState.autoAnalysisIndex == moveIndex
                // Only use live result if it's for the current position (FEN matches)
                val currentFen = uiState.currentBoard.getFen()
                val isResultForCurrentPosition = uiState.analysisResultFen == currentFen
                // Get score in WHITE's perspective first
                val whiteScore: MoveScore? = if ((isManualMode || isCurrentlyAnalyzing) && liveResult != null && isResultForCurrentPosition) {
                    val bestLine = liveResult.bestLine
                    if (bestLine != null) {
                        // Convert score to WHITE's perspective (Stockfish gives score from side-to-move's view)
                        val adjustedScore = if (isWhiteTurn) bestLine.score else -bestLine.score
                        val adjustedMateIn = if (isWhiteTurn) bestLine.mateIn else -bestLine.mateIn
                        MoveScore(adjustedScore, bestLine.isMate, adjustedMateIn)
                    } else null
                } else {
                    storedScore
                }
                // Convert to PLAYER's perspective
                val displayScore: MoveScore? = if (whiteScore != null && uiState.userPlayedBlack) {
                    MoveScore(-whiteScore.score, whiteScore.isMate, -whiteScore.mateIn)
                } else {
                    whiteScore
                }

                // Get previous move's score for delta calculation
                val prevStoredScore = if (moveIndex > 0) {
                    uiState.analyseScores[moveIndex - 1] ?: uiState.previewScores[moveIndex - 1]
                } else null
                val prevWhiteScore: MoveScore? = prevStoredScore
                val prevDisplayScore: MoveScore? = if (prevWhiteScore != null && uiState.userPlayedBlack) {
                    MoveScore(-prevWhiteScore.score, prevWhiteScore.isMate, -prevWhiteScore.mateIn)
                } else {
                    prevWhiteScore
                }

                if (displayScore != null) {
                    val scoreText = if (displayScore.isMate) {
                        if (displayScore.mateIn > 0) "+M${displayScore.mateIn}" else "-M${kotlin.math.abs(displayScore.mateIn)}"
                    } else {
                        if (displayScore.score >= 0) "+%.1f".format(displayScore.score) else "%.1f".format(displayScore.score)
                    }
                    val scoreColor = when {
                        displayScore.isMate && displayScore.mateIn > 0 -> Color(0xFF00E676)  // Green for player winning mate
                        displayScore.isMate && displayScore.mateIn < 0 -> Color(0xFFFF5252)  // Red for player losing mate
                        displayScore.score > 0.1f -> Color(0xFF00E676)  // Green for player better
                        displayScore.score < -0.1f -> Color(0xFFFF5252)  // Red for player worse
                        else -> Color(0xFF64B5F6)  // Bright blue for equal
                    }

                    // Calculate delta from previous move
                    val deltaText: String?
                    val deltaColor: Color
                    if (prevDisplayScore != null && !displayScore.isMate && !prevDisplayScore.isMate) {
                        val delta = displayScore.score - prevDisplayScore.score
                        deltaText = if (delta >= 0) "+%.1f".format(delta) else "%.1f".format(delta)
                        deltaColor = when {
                            delta > 0.1f -> Color(0xFF00E676)  // Green - gained advantage
                            delta < -0.1f -> Color(0xFFFF5252)  // Red - lost advantage
                            else -> Color(0xFF64B5F6)  // Blue - neutral
                        }
                    } else {
                        deltaText = null
                        deltaColor = Color.Transparent
                    }

                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = scoreText,
                            color = scoreColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            textAlign = TextAlign.End
                        )
                        if (deltaText != null) {
                            Text(
                                text = " / ",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp
                            )
                            Text(
                                text = deltaText,
                                color = deltaColor,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                Text(
                    text = "Start position",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // Get visibility settings for current stage
    val visibilitySettings = uiState.interfaceVisibility
    val showScoreLineGraph = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> true  // Always show line graph in preview
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showScoreLineGraph
        AnalysisStage.MANUAL -> visibilitySettings.manualStage.showScoreLineGraph
    }
    val showScoreBarsGraph = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> visibilitySettings.previewStage.showScoreBarsGraph
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showScoreBarsGraph
        AnalysisStage.MANUAL -> visibilitySettings.manualStage.showScoreBarsGraph
    }
    val showBoard = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> visibilitySettings.previewStage.showBoard
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showBoard
        AnalysisStage.MANUAL -> true  // Always show board in manual
    }
    val showMoveList = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> visibilitySettings.previewStage.showMoveList
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showMoveList
        AnalysisStage.MANUAL -> visibilitySettings.manualStage.showMoveList
    }
    val showGameInfo = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> true  // Always show game info in preview
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showGameInfo
        AnalysisStage.MANUAL -> visibilitySettings.manualStage.showGameInfo
    }
    val showPgn = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> visibilitySettings.previewStage.showPgn
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showPgn
        AnalysisStage.MANUAL -> visibilitySettings.manualStage.showPgn
    }
    val showResultBar = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> visibilitySettings.previewStage.showResultBar
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showResultBar
        AnalysisStage.MANUAL -> visibilitySettings.manualStage.showResultBar
    }
    val showStockfishAnalyse = when (uiState.currentStage) {
        AnalysisStage.PREVIEW -> false  // Never show in preview
        AnalysisStage.ANALYSE -> visibilitySettings.analyseStage.showStockfishAnalyse
        AnalysisStage.MANUAL -> false  // Not used in manual (Stockfish panel always shown)
    }
    // Player bars visibility: never in preview, always in analyse/manual (actual display controlled by playerBarMode)
    val showPlayersBarsFromVisibility = uiState.currentStage != AnalysisStage.PREVIEW
    val playerBarMode = uiState.boardLayoutSettings.playerBarMode
    val showRedBorderForPlayerToMove = uiState.boardLayoutSettings.showRedBorderForPlayerToMove

    // Conditional graph content
    val ConditionalGraphContent: @Composable () -> Unit = {
        Column(modifier = Modifier.offset(y = (-8).dp)) {
            if (uiState.moveDetails.isNotEmpty()) {
                // Line graph
                if (showScoreLineGraph) {
                    val lineGraphHeight = (120 * uiState.graphSettings.lineGraphScale / 100).dp
                    key(uiState.previewScores.size, uiState.analyseScores.size) {
                        EvaluationGraph(
                            previewScores = uiState.previewScores,
                            analyseScores = uiState.analyseScores,
                            moveQualities = uiState.moveQualities,
                            totalMoves = uiState.moveDetails.size,
                            currentMoveIndex = uiState.currentMoveIndex,
                            currentStage = uiState.currentStage,
                            userPlayedBlack = uiState.userPlayedBlack,
                            graphSettings = uiState.graphSettings,
                            onMoveSelected = { moveIndex ->
                                when (uiState.currentStage) {
                                    AnalysisStage.PREVIEW -> { /* Not interruptible - ignore clicks */ }
                                    AnalysisStage.ANALYSE -> viewModel.enterManualStageAtMove(moveIndex)
                                    AnalysisStage.MANUAL -> viewModel.restartAnalysisAtMove(moveIndex)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(lineGraphHeight)
                        )
                    }
                }

                // Score difference graph (bars) - only show during Analyse and Manual stages when we have scores
                if (showScoreBarsGraph && uiState.currentStage != AnalysisStage.PREVIEW) {
                    if (showScoreLineGraph) Spacer(modifier = Modifier.height(8.dp))
                    val barGraphHeight = (120 * uiState.graphSettings.barGraphScale / 100).dp
                    key(uiState.previewScores.size, uiState.analyseScores.size) {
                        ScoreDifferenceGraph(
                            previewScores = uiState.previewScores,
                            analyseScores = uiState.analyseScores,
                            totalMoves = uiState.moveDetails.size,
                            currentMoveIndex = uiState.currentMoveIndex,
                            currentStage = uiState.currentStage,
                            userPlayedBlack = uiState.userPlayedBlack,
                            graphSettings = uiState.graphSettings,
                            onMoveSelected = { moveIndex ->
                                when (uiState.currentStage) {
                                    AnalysisStage.PREVIEW -> { /* Not interruptible - ignore clicks */ }
                                    AnalysisStage.ANALYSE -> viewModel.enterManualStageAtMove(moveIndex)
                                    AnalysisStage.MANUAL -> viewModel.restartAnalysisAtMove(moveIndex)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barGraphHeight)
                        )
                    }
                }

                // Time usage graph - only show in Manual stage when clock data is available
                val showTimeGraph = uiState.currentStage == AnalysisStage.MANUAL &&
                    uiState.interfaceVisibility.manualStage.showTimeGraph &&
                    uiState.moveDetails.any { it.clockTime != null }
                if (showTimeGraph) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TimeUsageGraph(
                        moveDetails = uiState.moveDetails,
                        currentMoveIndex = uiState.currentMoveIndex,
                        currentStage = uiState.currentStage,
                        graphSettings = uiState.graphSettings,
                        onMoveSelected = { moveIndex ->
                            viewModel.restartAnalysisAtMove(moveIndex)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                    )
                }
            }
        }
    }

    // Game info card - shows graph and result bar in analyse mode
    val GameInfoCard: @Composable () -> Unit = {
        // Add extra space before graphs in Analyse stage
        if (uiState.currentStage == AnalysisStage.ANALYSE) {
            Spacer(modifier = Modifier.height(8.dp))
        }
        ConditionalGraphContent()
        if (uiState.currentStage == AnalysisStage.ANALYSE && showResultBar) {
            ResultBar()
        }
    }

    // Show game info card at top during preview and analyse stages
    if (uiState.currentStage != AnalysisStage.MANUAL && (showScoreLineGraph || showScoreBarsGraph)) {
        GameInfoCard()
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Result bar above board in manual stage
    if (uiState.currentStage == AnalysisStage.MANUAL && showResultBar) {
        ResultBar()
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Opening name display (Manual stage only, when enabled in settings)
    val showOpeningName = uiState.interfaceVisibility.manualStage.showOpeningName
    if (uiState.currentStage == AnalysisStage.MANUAL && showOpeningName) {
        // Prefer opening explorer data (has ECO code + full name)
        val explorerOpening = uiState.openingExplorerData?.opening
        val displayOpeningText = if (explorerOpening != null) {
            "${explorerOpening.eco}: ${explorerOpening.name}"
        } else {
            // Fall back to currentOpeningName (from OpeningBook) or openingName (from PGN)
            val baseName = uiState.currentOpeningName ?: uiState.openingName
            // Expand common abbreviations
            baseName?.let { expandOpeningAbbreviation(it) }
        }

        if (displayOpeningText != null) {
            Text(
                text = displayOpeningText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFAAAAAA),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    // Show the board based on visibility settings
    if (showBoard) {
        // Calculate game results for each player
        // Don't show results for ongoing games (status "*", "started", "unknown", etc.)
        val isOngoingGame = game.status == "*" || game.status == "started" ||
            game.status == "unknown" || game.status.isNullOrBlank()
        val whiteResult = if (isOngoingGame) null else when (game.winner) {
            "white" -> "won"
            "black" -> "lost"
            null -> if (game.status == "draw" || game.status == "stalemate") "draw" else null
            else -> null
        }
        val blackResult = if (isOngoingGame) null else when (game.winner) {
            "black" -> "won"
            "white" -> "lost"
            null -> if (game.status == "draw" || game.status == "stalemate") "draw" else null
            else -> null
        }

        // Player bar handling based on playerBarMode
        val topIsBlack = !uiState.flippedBoard

        // Show combined bar at TOP if mode is TOP
        if (showPlayersBarsFromVisibility && playerBarMode == PlayerBarMode.TOP) {
            CombinedPlayerBar(
                whiteName = whiteName,
                blackName = blackName,
                whiteResult = whiteResult,
                blackResult = blackResult,
                isWhiteTurn = isWhiteTurn,
                showRedBorder = showRedBorderForPlayerToMove,
                onPlayerClick = { playerName -> viewModel.showPlayerInfo(playerName) },
                modifier = Modifier
            )
        }

        // Show separate top bar if mode is BOTH
        if (showPlayersBarsFromVisibility && playerBarMode == PlayerBarMode.BOTH) {
            PlayerBar(
                isWhite = !topIsBlack,
                playerName = if (topIsBlack) blackName else whiteName,
                rating = if (topIsBlack) blackRating else whiteRating,
                clockTime = if (topIsBlack) blackClockTime else whiteClockTime,
                isToMove = if (topIsBlack) !isWhiteTurn else isWhiteTurn,
                gameResult = if (topIsBlack) blackResult else whiteResult,
                showRedBorder = showRedBorderForPlayerToMove,
                onPlayerClick = { playerName -> viewModel.showPlayerInfo(playerName) },
                modifier = Modifier
            )
        }

        // Chess board - drag to make moves during manual replay (no interaction during other stages)
        // Get move arrows based on arrow mode (only in Manual stage, and only if result is for current position)
        val arrowMode = uiState.stockfishSettings.manualStage.arrowMode
        val numArrows = uiState.stockfishSettings.manualStage.numArrows
        val arrowResultMatchesPosition = uiState.analysisResultFen == uiState.currentBoard.getFen()
        val isWhiteTurnNow = uiState.currentBoard.getTurn() == PieceColor.WHITE

        val moveArrows: List<MoveArrow> = if (uiState.currentStage == AnalysisStage.MANUAL && arrowMode != ArrowMode.NONE && arrowResultMatchesPosition) {
            when (arrowMode) {
                ArrowMode.NONE -> emptyList()
                ArrowMode.MAIN_LINE -> {
                    // Draw arrows from PV line (existing behavior)
                    val pvLine = uiState.analysisResult?.pv ?: ""
                    val pvMoves = pvLine.split(" ").filter { it.length >= 4 }.take(numArrows)

                    pvMoves.mapIndexedNotNull { index, uciMove ->
                        val fromFile = uciMove[0] - 'a'
                        val fromRank = uciMove[1] - '1'
                        val toFile = uciMove[2] - 'a'
                        val toRank = uciMove[3] - '1'
                        if (fromFile in 0..7 && fromRank in 0..7 && toFile in 0..7 && toRank in 0..7) {
                            // First move is by current turn, then alternates
                            val isWhiteMove = if (index % 2 == 0) isWhiteTurnNow else !isWhiteTurnNow
                            MoveArrow(
                                from = Square(fromFile, fromRank),
                                to = Square(toFile, toRank),
                                isWhiteMove = isWhiteMove,
                                index = index
                            )
                        } else null
                    }
                }
                ArrowMode.MULTI_LINES -> {
                    // Draw one arrow per Stockfish line with score displayed
                    // Use same color logic as Stockfish card (green/red/blue based on score)
                    val analysisLines = uiState.analysisResult?.lines ?: emptyList()

                    analysisLines.mapIndexedNotNull { index, line ->
                        val firstMove = line.pv.split(" ").firstOrNull { it.length >= 4 }
                        if (firstMove != null) {
                            val fromFile = firstMove[0] - 'a'
                            val fromRank = firstMove[1] - '1'
                            val toFile = firstMove[2] - 'a'
                            val toRank = firstMove[3] - '1'
                            if (fromFile in 0..7 && fromRank in 0..7 && toFile in 0..7 && toRank in 0..7) {
                                // Convert score to WHITE's perspective, then to player's perspective
                                val whiteScore = if (isWhiteTurnNow) line.score else -line.score
                                val whiteMateIn = if (isWhiteTurnNow) line.mateIn else -line.mateIn
                                val adjustedScore = if (uiState.userPlayedBlack) -whiteScore else whiteScore
                                val adjustedMateIn = if (uiState.userPlayedBlack) -whiteMateIn else whiteMateIn

                                // Format score for display
                                val scoreText = if (line.isMate) {
                                    if (adjustedMateIn > 0) "+M${adjustedMateIn}" else "-M${kotlin.math.abs(adjustedMateIn)}"
                                } else {
                                    if (adjustedScore >= 0) "+%.1f".format(adjustedScore) else "%.1f".format(adjustedScore)
                                }

                                // Gray color for multi-line arrows
                                val arrowColor = Color(0xCC888888)

                                MoveArrow(
                                    from = Square(fromFile, fromRank),
                                    to = Square(toFile, toRank),
                                    isWhiteMove = isWhiteTurnNow,
                                    index = index,
                                    scoreText = scoreText,
                                    overrideColor = arrowColor
                                )
                            } else null
                        } else null
                    }
                }
            }
        } else emptyList()

        // Chess board with optional evaluation bar
        val evalBarPosition = uiState.boardLayoutSettings.evalBarPosition
        val showEvalBar = evalBarPosition != EvalBarPosition.NONE

        // Get current score for evaluation bar from WHITE's perspective (positive = white winning)
        // The flipped parameter on EvaluationBar handles display when user played black
        val evalBarScore = run {
            uiState.analysisResult?.lines?.firstOrNull()?.let { line ->
                val rawScore = if (line.isMate) {
                    if (line.mateIn > 0) 100f else -100f  // Mate for white/black
                } else {
                    line.score
                }
                // Flip for black's turn since Stockfish gives score from side-to-move's perspective
                if (isWhiteTurn) rawScore else -rawScore
            } ?: run {
                // Fall back to preview/analyse scores - these are already adjusted to white's perspective
                val moveIndex = uiState.currentMoveIndex
                val analyseScore = uiState.analyseScores[moveIndex]
                val previewScore = uiState.previewScores[moveIndex]
                (analyseScore ?: previewScore)?.let { score ->
                    if (score.isMate) {
                        if (score.mateIn > 0) 100f else -100f
                    } else {
                        score.score
                    }
                } ?: 0f
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            // Left evaluation bar
            if (showEvalBar && evalBarPosition == EvalBarPosition.LEFT) {
                EvaluationBar(
                    score = evalBarScore,
                    range = uiState.boardLayoutSettings.evalBarRange,
                    color1 = Color(uiState.boardLayoutSettings.evalBarColor1.toInt()),
                    color2 = Color(uiState.boardLayoutSettings.evalBarColor2.toInt()),
                    flipped = uiState.userPlayedBlack,
                    modifier = Modifier.fillMaxHeight()
                )
            }

            ChessBoardView(
                board = uiState.currentBoard,
                flipped = uiState.flippedBoard,
                interactionEnabled = uiState.currentStage == AnalysisStage.MANUAL,
                onMove = { from, to -> viewModel.makeManualMove(from, to) },
                moveArrows = moveArrows,
                showArrowNumbers = uiState.stockfishSettings.manualStage.showArrowNumbers,
                whiteArrowColor = Color(uiState.stockfishSettings.manualStage.whiteArrowColor.toInt()),
                blackArrowColor = Color(uiState.stockfishSettings.manualStage.blackArrowColor.toInt()),
                showCoordinates = uiState.boardLayoutSettings.showCoordinates,
                showLastMove = uiState.boardLayoutSettings.showLastMove,
                whiteSquareColor = Color(uiState.boardLayoutSettings.whiteSquareColor.toInt()),
                blackSquareColor = Color(uiState.boardLayoutSettings.blackSquareColor.toInt()),
                whitePieceColor = Color(uiState.boardLayoutSettings.whitePieceColor.toInt()),
                blackPieceColor = Color(uiState.boardLayoutSettings.blackPieceColor.toInt()),
                modifier = Modifier.weight(1f)
            )

            // Right evaluation bar
            if (showEvalBar && evalBarPosition == EvalBarPosition.RIGHT) {
                EvaluationBar(
                    score = evalBarScore,
                    range = uiState.boardLayoutSettings.evalBarRange,
                    color1 = Color(uiState.boardLayoutSettings.evalBarColor1.toInt()),
                    color2 = Color(uiState.boardLayoutSettings.evalBarColor2.toInt()),
                    flipped = uiState.userPlayedBlack,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }

        // Show separate bottom bar if mode is BOTH
        if (showPlayersBarsFromVisibility && playerBarMode == PlayerBarMode.BOTH) {
            PlayerBar(
                isWhite = topIsBlack,
                playerName = if (topIsBlack) whiteName else blackName,
                rating = if (topIsBlack) whiteRating else blackRating,
                clockTime = if (topIsBlack) whiteClockTime else blackClockTime,
                isToMove = if (topIsBlack) isWhiteTurn else !isWhiteTurn,
                gameResult = if (topIsBlack) whiteResult else blackResult,
                showRedBorder = showRedBorderForPlayerToMove,
                onPlayerClick = { playerName -> viewModel.showPlayerInfo(playerName) },
                modifier = Modifier
            )
        }

        // Show combined bar at BOTTOM if mode is BOTTOM
        if (showPlayersBarsFromVisibility && playerBarMode == PlayerBarMode.BOTTOM) {
            CombinedPlayerBar(
                whiteName = whiteName,
                blackName = blackName,
                whiteResult = whiteResult,
                blackResult = blackResult,
                isWhiteTurn = isWhiteTurn,
                showRedBorder = showRedBorderForPlayerToMove,
                onPlayerClick = { playerName -> viewModel.showPlayerInfo(playerName) },
                modifier = Modifier
            )
        }
    }

    // Stockfish Analyse card - only shown during Analyse stage based on visibility setting
    if (uiState.currentStage == AnalysisStage.ANALYSE && showStockfishAnalyse) {
        Spacer(modifier = Modifier.height(8.dp))
        StockfishAnalyseCard(uiState = uiState)
    }

    // Controls - hide during auto-analysis
    if (uiState.currentStage == AnalysisStage.MANUAL) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Use exploring line indices when exploring, otherwise use main game indices
            val isAtStart = if (uiState.isExploringLine) {
                uiState.exploringLineMoveIndex < 0
            } else {
                uiState.currentMoveIndex < 0
            }
            val isAtEnd = if (uiState.isExploringLine) {
                uiState.exploringLineMoveIndex >= uiState.exploringLineMoves.size - 1
            } else {
                uiState.currentMoveIndex >= uiState.moveDetails.size - 1
            }

            // Left part: Navigation buttons with fixed positions (slots maintain size even when hidden)
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val buttonWidth = 44.dp
                val spacerWidth = 2.dp

                // Button 1: Go to start (hidden when exploring)
                Box(modifier = Modifier.width(buttonWidth)) {
                    if (!uiState.isExploringLine) {
                        ControlButton("⏮", enabled = !isAtStart) { viewModel.goToStart() }
                    }
                }
                Spacer(modifier = Modifier.width(spacerWidth))

                // Button 2: Previous move (hidden when exploring and at start)
                Box(modifier = Modifier.width(buttonWidth)) {
                    val showPrevButton = !uiState.isExploringLine || !isAtStart
                    if (showPrevButton) {
                        ControlButton("◀", enabled = !isAtStart) { viewModel.prevMove() }
                    }
                }
                Spacer(modifier = Modifier.width(spacerWidth))

                // Button 3: Next move (hidden when exploring and at end)
                Box(modifier = Modifier.width(buttonWidth)) {
                    val showNextButton = !uiState.isExploringLine || !isAtEnd
                    if (showNextButton) {
                        ControlButton("▶", enabled = !isAtEnd) { viewModel.nextMove() }
                    }
                }
                Spacer(modifier = Modifier.width(spacerWidth))

                // Button 4: Go to end (hidden when exploring)
                Box(modifier = Modifier.width(buttonWidth)) {
                    if (!uiState.isExploringLine) {
                        ControlButton("⏭", enabled = !isAtEnd) { viewModel.goToEnd() }
                    }
                }
            }

            // Right part: Back to game button (when exploring) or flip board icon
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isExploringLine) {
                    // Back to game button - same style as navigation buttons
                    Button(
                        onClick = { viewModel.backToOriginalGame() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Back to game", fontSize = 18.sp)
                    }
                } else {
                    // LIVE badge and auto-follow toggle for live games
                    if (uiState.isLiveGame) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (uiState.liveStreamConnected) Color(0xFFE53935) else Color(0xFF757575),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "LIVE",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        // Auto-follow toggle
                        Button(
                            onClick = { viewModel.toggleAutoFollowLive() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.autoFollowLive) Color(0xFF4CAF50) else Color(0xFF404040)
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (uiState.autoFollowLive) "Follow" else "Manual",
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    // Share button - circular with bold icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF404040), CircleShape)
                            .clickable { viewModel.showSharePositionDialog() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "⤴", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (-2).dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Arrow mode toggle button - circular with bold icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF404040), CircleShape)
                            .clickable { viewModel.cycleArrowMode() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "↗", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (-2).dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    // Flip board button - circular with bold icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF404040), CircleShape)
                            .clickable { viewModel.flipBoard() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "↻", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (-2).dp))
                    }
                }
            }
        }
    }

    // Stockfish analysis panel - hide during auto-analysis
    if (uiState.currentStage == AnalysisStage.MANUAL) {
        AnalysisPanel(
            uiState = uiState,
            onExploreLine = { pv, moveIndex -> viewModel.exploreLine(pv, moveIndex) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        )

        // Opening Explorer panel
        if (uiState.interfaceVisibility.manualStage.showOpeningExplorer) {
            Spacer(modifier = Modifier.height(8.dp))
            OpeningExplorerPanel(
                explorerData = uiState.openingExplorerData,
                isLoading = uiState.openingExplorerLoading,
                onMoveClick = { uciMove ->
                    // Make the move on the current board
                    viewModel.exploreLine(uciMove, 0)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Show graph cards between Stockfish panel and moves list during manual stage
    if (uiState.currentStage == AnalysisStage.MANUAL && (showScoreLineGraph || showScoreBarsGraph)) {
        Spacer(modifier = Modifier.height(16.dp))
        ConditionalGraphContent()
    }

    // Moves list - based on visibility settings (hide if no moves, e.g., FEN-only start)
    if (showMoveList && uiState.moveDetails.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "Moves",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                // Use analyse scores if available (they override preview scores)
                val displayScores = if (uiState.analyseScores.isNotEmpty()) {
                    // Merge: use analyse score if available, otherwise preview
                    uiState.previewScores + uiState.analyseScores
                } else {
                    uiState.previewScores
                }
                MovesList(
                    moveDetails = uiState.moveDetails,
                    currentMoveIndex = uiState.currentMoveIndex,
                    moveScores = displayScores,
                    moveQualities = uiState.moveQualities,
                    currentStage = uiState.currentStage,
                    autoAnalysisIndex = uiState.autoAnalysisIndex,
                    userPlayedBlack = uiState.userPlayedBlack,
                    onMoveClick = { viewModel.goToMove(it) }
                )
            }
        }
    }

    // Game Information Card - based on visibility settings
    if (showGameInfo) {
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF2A4A6A)  // Lighter blue background
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Centered title
                Text(
                    text = "Game Information",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Table rows with aligned labels and values
                val labelWidth = 105.dp

                // White player
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("White:", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(labelWidth))
                    Text("$whiteName${whiteRating?.let { " ($it)" } ?: ""}", fontSize = 13.sp, color = Color.White)
                }
                // Black player
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Black:", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(labelWidth))
                    Text("$blackName${blackRating?.let { " ($it)" } ?: ""}", fontSize = 13.sp, color = Color.White)
                }
                // Format
                val formatText = buildString {
                    append(game.speed.replaceFirstChar { it.uppercase() })
                    game.clock?.let { clock ->
                        val minutes = clock.initial / 60
                        val increment = clock.increment
                        append(" $minutes+$increment")
                    }
                    if (game.rated) append(" • Rated") else append(" • Casual")
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Format:", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(labelWidth))
                    Text(formatText, fontSize = 13.sp, color = Color.White)
                }
                // Opening
                uiState.openingName?.let { opening ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Opening:", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(labelWidth))
                        Text(opening, fontSize = 13.sp, color = Color.White)
                    }
                }
                // Date
                game.createdAt?.let { timestamp ->
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(timestamp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Date:", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(labelWidth))
                        Text(date, fontSize = 13.sp, color = Color.White)
                    }
                }
                // Result - don't show for ongoing games
                val isOngoingStatus = game.status == "*" || game.status == "started" ||
                    game.status == "unknown" || game.status.isNullOrBlank()
                if (!isOngoingStatus) {
                    val resultText = when (game.winner) {
                        "white" -> "1-0"
                        "black" -> "0-1"
                        else -> if (game.status == "draw" || game.status == "stalemate") "½-½" else game.status
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Result:", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(labelWidth))
                        Text(resultText, fontSize = 13.sp, color = Color.White)
                    }
                }
                // Termination - extract from PGN headers
                game.pgn?.let { pgn ->
                    val terminationMatch = Regex("\\[Termination \"([^\"]+)\"\\]").find(pgn)
                    terminationMatch?.groupValues?.get(1)?.let { termination ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("Termination:", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.width(labelWidth))
                            Text(termination, fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // PGN Card - based on visibility settings
    if (showPgn) {
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF3A3A3A)  // Dark gray background
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "PGN",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                game.pgn?.let { pgn ->
                    // Format PGN with each move on a new line
                    val formattedPgn = pgn.replace(Regex("(\\d+\\.)")) { match ->
                        "\n${match.value}"
                    }.trimStart()
                    Text(
                        text = formattedPgn,
                        fontSize = 11.sp,
                        color = Color(0xFFCCCCCC),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                } ?: Text(
                    text = "No PGN data available",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }

    // Raw Stockfish scores cards (Manual stage only, developer mode only)
    val showRawStockfishScore = uiState.interfaceVisibility.manualStage.showRawStockfishScore
    val developerMode = uiState.generalSettings.developerMode

    // Raw Stockfish Scores - Preview stage
    if (uiState.currentStage == AnalysisStage.MANUAL && showRawStockfishScore && developerMode && uiState.previewScores.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        RawStockfishScoresCard(
            title = "Raw Stockfish Scores - Preview stage",
            titleColor = Color(0xFF90CAF9),
            scores = uiState.previewScores,
            moves = uiState.moves,
            currentMoveIndex = uiState.currentMoveIndex
        )
    }

    // Raw Stockfish Scores - Analyse stage
    if (uiState.currentStage == AnalysisStage.MANUAL && showRawStockfishScore && developerMode && uiState.analyseScores.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        RawStockfishScoresCard(
            title = "Raw Stockfish Scores - Analyse stage",
            titleColor = Color(0xFFFFD700),
            scores = uiState.analyseScores,
            moves = uiState.moves,
            currentMoveIndex = uiState.currentMoveIndex
        )
    }
}

/**
 * Reusable card for displaying raw Stockfish scores.
 */
@Composable
private fun RawStockfishScoresCard(
    title: String,
    titleColor: Color,
    scores: Map<Int, MoveScore?>,
    moves: List<String>,
    currentMoveIndex: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2D2D3D)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))

            moves.forEachIndexed { index, move ->
                val score = scores[index]
                val isWhiteMove = index % 2 == 0
                val moveNumber = (index / 2) + 1
                val moveNotation = if (isWhiteMove) "$moveNumber. $move" else "$moveNumber... $move"

                val scoreText = score?.let { s ->
                    if (s.isMate) {
                        if (s.mateIn > 0) "M${s.mateIn}" else "-M${kotlin.math.abs(s.mateIn)}"
                    } else {
                        if (s.score >= 0) "+%.2f".format(s.score) else "%.2f".format(s.score)
                    }
                } ?: "—"

                val isCurrentMove = index == currentMoveIndex
                val backgroundColor = if (isCurrentMove) Color(0xFF4A4A5A) else Color.Transparent
                val textColor = if (isCurrentMove) Color.White else Color(0xFFBBBBBB)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = moveNotation,
                        fontSize = 12.sp,
                        color = textColor,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Text(
                        text = scoreText,
                        fontSize = 12.sp,
                        color = if (score != null) titleColor else Color(0xFF666666),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Navigation control button for move navigation.
 */
@Composable
fun ControlButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
            disabledContentColor = Color.Gray
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text = text, fontSize = 16.sp)
    }
}

/**
 * Player bar showing player name, rating, clock time, and game result.
 * @param gameResult "won", "lost", "draw", or null if game not finished
 * @param showRedBorder if true and isToMove is true, show red border around the bar
 * @param onPlayerClick callback when player name is tapped, receives the player name
 */
@Composable
fun PlayerBar(
    isWhite: Boolean,
    playerName: String,
    rating: Int?,
    clockTime: String?,
    isToMove: Boolean,
    gameResult: String? = null,
    showRedBorder: Boolean = false,
    onPlayerClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isWhite) Color.White else Color.Black
    val textColor = if (isWhite) Color.Black else Color.White
    val shouldShowBorder = isToMove && showRedBorder

    // Build player text: "Name (1234)"
    val playerText = buildString {
        append(playerName)
        if (rating != null) {
            append(" ($rating)")
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .then(
                if (shouldShowBorder) {
                    Modifier.border(2.dp, Color.Red)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left side: game result + player name
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Game result indicator
            if (gameResult != null) {
                val (resultText, resultColor) = when (gameResult) {
                    "won" -> "1" to Color(0xFF00C853)  // Green
                    "lost" -> "0" to Color(0xFFFF1744)  // Red
                    "draw" -> "½" to Color(0xFF64B5F6)  // Blue
                    else -> "" to Color.Transparent
                }
                if (resultText.isNotEmpty()) {
                    Text(
                        text = resultText,
                        color = resultColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            Text(
                text = playerText,
                color = textColor,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = if (onPlayerClick != null) {
                    Modifier.clickable { onPlayerClick(playerName) }
                } else {
                    Modifier
                }
            )
        }

        // Clock time on the right
        if (clockTime != null) {
            Text(
                text = clockTime,
                color = textColor,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Combined player bar showing both players in a single row.
 * Left: White player (white background, black text, score before name)
 * Right: Black player (black background, white text, score after name)
 * No ELO rating or clocks shown.
 * @param isWhiteTurn true if it's white's turn to move
 * @param showRedBorder if true, show red border around the half of the player to move
 * @param onPlayerClick callback when player name is tapped, receives the player name
 */
@Composable
fun CombinedPlayerBar(
    whiteName: String,
    blackName: String,
    whiteResult: String?,
    blackResult: String?,
    isWhiteTurn: Boolean = true,
    showRedBorder: Boolean = false,
    onPlayerClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val showWhiteBorder = showRedBorder && isWhiteTurn
    val showBlackBorder = showRedBorder && !isWhiteTurn

    Row(
        modifier = modifier.fillMaxWidth()
    ) {
        // Left half: White player (white background)
        Row(
            modifier = Modifier
                .weight(1f)
                .background(Color.White)
                .then(
                    if (showWhiteBorder) {
                        Modifier.border(2.dp, Color.Red)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            // Score before name for white
            if (whiteResult != null) {
                val (resultText, resultColor) = when (whiteResult) {
                    "won" -> "1" to Color(0xFF00C853)  // Green
                    "lost" -> "0" to Color(0xFFFF1744)  // Red
                    "draw" -> "½" to Color(0xFF64B5F6)  // Blue
                    else -> "" to Color.Transparent
                }
                if (resultText.isNotEmpty()) {
                    Text(
                        text = resultText,
                        color = resultColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            Text(
                text = whiteName,
                color = Color.Black,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = if (onPlayerClick != null) {
                    Modifier.clickable { onPlayerClick(whiteName) }
                } else {
                    Modifier
                }
            )
        }

        // Right half: Black player (black background)
        Row(
            modifier = Modifier
                .weight(1f)
                .background(Color.Black)
                .then(
                    if (showBlackBorder) {
                        Modifier.border(2.dp, Color.Red)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = blackName,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = if (onPlayerClick != null) {
                    Modifier.clickable { onPlayerClick(blackName) }
                } else {
                    Modifier
                }
            )
            // Score after name for black
            if (blackResult != null) {
                val (resultText, resultColor) = when (blackResult) {
                    "won" -> "1" to Color(0xFF00C853)  // Green
                    "lost" -> "0" to Color(0xFFFF1744)  // Red
                    "draw" -> "½" to Color(0xFF64B5F6)  // Blue
                    else -> "" to Color.Transparent
                }
                if (resultText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = resultText,
                        color = resultColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

/**
 * Evaluation bar component that displays the current position evaluation.
 * Shows a vertical bar with two colors representing the score.
 *
 * @param score The evaluation score in pawns (positive = white advantage, negative = black advantage)
 * @param range The maximum score range to display (e.g., 5 means -5 to +5 pawns)
 * @param color1 The color for white's advantage (score portion)
 * @param color2 The color for black's advantage (filler portion)
 * @param flipped Whether the board is flipped (affects which color is on top)
 */
@Composable
fun EvaluationBar(
    score: Float,
    range: Int,
    color1: Color,
    color2: Color,
    flipped: Boolean,
    modifier: Modifier = Modifier
) {
    // Clamp score to range and calculate proportion
    val clampedScore = score.coerceIn(-range.toFloat(), range.toFloat())
    // Convert score to a 0-1 scale where 0.5 is equal position
    // Positive score = more color1 (from bottom), negative = more color2 (from top)
    val scoreRatio = ((clampedScore / range) + 1f) / 2f

    // When board is flipped, swap the colors
    val bottomColor = if (flipped) color2 else color1
    val topColor = if (flipped) color1 else color2
    val bottomRatio = (if (flipped) 1f - scoreRatio else scoreRatio).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight()
    ) {
        // Draw top color as background (full height)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(topColor)
        )
        // Draw bottom color from the bottom, overlaying the top
        if (bottomRatio > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(bottomRatio)
                    .align(Alignment.BottomCenter)
                    .background(bottomColor)
            )
        }
    }
}

/**
 * Stockfish Analyse card - shows live Stockfish analysis info during Analyse stage.
 */
@Composable
private fun StockfishAnalyseCard(uiState: GameUiState) {
    val totalMoves = uiState.moves.size
    val currentMoveIndex = uiState.autoAnalysisIndex
    val currentScore = uiState.autoAnalysisCurrentScore
    val analyzedCount = uiState.analyseScores.size
    val analyseSettings = uiState.stockfishSettings.analyseStage

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A3A5A)  // Dark blue background
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title
            Text(
                text = "Stockfish Analyse",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            // Progress row: "Analyzing move X of Y"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (currentMoveIndex >= 0) "Analyzing move ${currentMoveIndex + 1} of $totalMoves" else "Starting...",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp
                )
                Text(
                    text = "$analyzedCount analyzed",
                    color = Color(0xFF90CAF9),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Runtime info section
            if (currentScore != null) {
                // Score display row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val scoreText = if (currentScore.isMate) {
                        if (currentScore.mateIn > 0) "+M${currentScore.mateIn}" else "-M${kotlin.math.abs(currentScore.mateIn)}"
                    } else {
                        if (currentScore.score >= 0) "+%.2f".format(currentScore.score) else "%.2f".format(currentScore.score)
                    }
                    val scoreColor = when {
                        currentScore.isMate && currentScore.mateIn > 0 -> Color(0xFF00E676)
                        currentScore.isMate && currentScore.mateIn < 0 -> Color(0xFFFF5252)
                        currentScore.score > 0.5f -> Color(0xFF00E676)
                        currentScore.score < -0.5f -> Color(0xFFFF5252)
                        else -> Color(0xFF64B5F6)
                    }
                    Text(
                        text = "Score: $scoreText",
                        color = scoreColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 120.dp)
                    )
                }

                // Runtime stats row (depth, nodes, nps)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Depth: ${currentScore.depth}",
                        color = Color(0xFF81D4FA),
                        fontSize = 12.sp
                    )
                    Text(
                        text = formatNodes(currentScore.nodes),
                        color = Color(0xFF81D4FA),
                        fontSize = 12.sp
                    )
                    Text(
                        text = formatNps(currentScore.nps),
                        color = Color(0xFF81D4FA),
                        fontSize = 12.sp
                    )
                }
            }

            // Divider between runtime and config
            HorizontalDivider(
                color = Color(0xFF37474F),
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            // Configuration info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Config: ${analyseSettings.secondsForMove}s/move",
                    color = Color(0xFF607D8B),
                    fontSize = 11.sp
                )
                Text(
                    text = "${analyseSettings.threads} threads  ${analyseSettings.hashMb}MB hash",
                    color = Color(0xFF607D8B),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Format node count for display (e.g., 1234567 -> "1.2M nodes")
 */
private fun formatNodes(nodes: Long): String {
    return when {
        nodes >= 1_000_000_000 -> "%.1fB nodes".format(nodes / 1_000_000_000.0)
        nodes >= 1_000_000 -> "%.1fM nodes".format(nodes / 1_000_000.0)
        nodes >= 1_000 -> "%.1fK nodes".format(nodes / 1_000.0)
        else -> "$nodes nodes"
    }
}

/**
 * Format nodes per second for display (e.g., 1234567 -> "1.2M nps")
 */
private fun formatNps(nps: Long): String {
    return when {
        nps >= 1_000_000_000 -> "%.1fB nps".format(nps / 1_000_000_000.0)
        nps >= 1_000_000 -> "%.1fM nps".format(nps / 1_000_000.0)
        nps >= 1_000 -> "%.1fK nps".format(nps / 1_000.0)
        else -> "$nps nps"
    }
}

/**
 * Expand common chess opening abbreviations to their full names.
 * Returns "Abbreviation: Full Name" format, or original if no expansion found.
 */
private fun expandOpeningAbbreviation(name: String): String {
    val abbreviations = mapOf(
        // Queen's Gambit variations
        "QG" to "Queen's Gambit",
        "QGA" to "Queen's Gambit Accepted",
        "QGD" to "Queen's Gambit Declined",
        "QGT" to "Queen's Gambit Tarrasch",

        // Indian Defenses
        "KID" to "King's Indian Defense",
        "QID" to "Queen's Indian Defense",
        "NID" to "Nimzo-Indian Defense",
        "NI" to "Nimzo-Indian Defense",
        "BID" to "Bogo-Indian Defense",
        "OID" to "Old Indian Defense",
        "GI" to "Grunfeld Indian",
        "GR" to "Grunfeld Defense",
        "GD" to "Grunfeld Defense",

        // King's Pawn Openings
        "KP" to "King's Pawn Opening",
        "KPO" to "King's Pawn Opening",
        "KGA" to "King's Gambit Accepted",
        "KGD" to "King's Gambit Declined",
        "KG" to "King's Gambit",
        "KIA" to "King's Indian Attack",

        // Queen's Pawn Openings
        "QP" to "Queen's Pawn Opening",
        "QPO" to "Queen's Pawn Opening",
        "QPG" to "Queen's Pawn Game",

        // Sicilian variations
        "SC" to "Sicilian Defense",
        "SIC" to "Sicilian Defense",
        "SN" to "Sicilian Najdorf",
        "SD" to "Sicilian Dragon",
        "SS" to "Sicilian Scheveningen",
        "SSV" to "Sicilian Sveshnikov",
        "SKK" to "Sicilian Kan",
        "STA" to "Sicilian Taimanov",
        "SAC" to "Sicilian Accelerated Dragon",
        "SMO" to "Sicilian Moscow",
        "SRO" to "Sicilian Rossolimo",

        // French Defense variations
        "FD" to "French Defense",
        "FR" to "French Defense",
        "FT" to "French Tarrasch",
        "FW" to "French Winawer",
        "FC" to "French Classical",
        "FA" to "French Advance",
        "FE" to "French Exchange",

        // Caro-Kann variations
        "CK" to "Caro-Kann Defense",
        "CD" to "Caro-Kann Defense",
        "CKA" to "Caro-Kann Advance",
        "CKC" to "Caro-Kann Classical",
        "CKE" to "Caro-Kann Exchange",

        // Ruy Lopez variations
        "RL" to "Ruy Lopez",
        "SP" to "Spanish Game (Ruy Lopez)",
        "RLM" to "Ruy Lopez Marshall",
        "RLB" to "Ruy Lopez Berlin",

        // Italian Game
        "IT" to "Italian Game",
        "IG" to "Italian Game",
        "GP" to "Giuoco Piano",
        "EG" to "Evans Gambit",
        "TK" to "Two Knights Defense",

        // Other 1.e4 openings
        "SC" to "Scotch Game",
        "SCO" to "Scotch Game",
        "PET" to "Petroff Defense",
        "RD" to "Russian Defense (Petroff)",
        "PH" to "Philidor Defense",
        "AL" to "Alekhine Defense",
        "AD" to "Alekhine Defense",
        "PI" to "Pirc Defense",
        "PK" to "Pirc Defense",
        "MO" to "Modern Defense",
        "MD" to "Modern Defense",
        "SCD" to "Scandinavian Defense",
        "SD" to "Scandinavian Defense",
        "CN" to "Center Game",
        "VG" to "Vienna Game",
        "BG" to "Bishop's Opening",

        // Benoni variations
        "BE" to "Benoni Defense",
        "BD" to "Benoni Defense",
        "MB" to "Modern Benoni",
        "CB" to "Czech Benoni",

        // Dutch Defense
        "DU" to "Dutch Defense",
        "DD" to "Dutch Defense",
        "DR" to "Dutch Defense",
        "DSW" to "Dutch Stonewall",
        "DL" to "Dutch Leningrad",

        // Slav variations
        "SL" to "Slav Defense",
        "SLA" to "Slav Defense",
        "SSL" to "Semi-Slav Defense",
        "SM" to "Slav Meran",

        // English Opening
        "EN" to "English Opening",
        "ENG" to "English Opening",
        "EO" to "English Opening",

        // Catalan
        "CAT" to "Catalan Opening",
        "CA" to "Catalan Opening",

        // London System
        "LO" to "London System",
        "LDN" to "London System",
        "LS" to "London System",

        // Other d4 openings
        "TR" to "Trompowsky Attack",
        "TRO" to "Trompowsky Attack",
        "TO" to "Torre Attack",
        "TA" to "Torre Attack",
        "CO" to "Colle System",
        "CS" to "Colle System",
        "ZU" to "Zukertort Opening",
        "BL" to "Blackmar-Diemer Gambit",
        "BDG" to "Blackmar-Diemer Gambit",
        "VE" to "Veresov Opening",
        "VO" to "Veresov Opening",
        "BA" to "Barry Attack",
        "JO" to "Jobava London",

        // Flank Openings
        "RE" to "Reti Opening",
        "BI" to "Bird's Opening",
        "BO" to "Bird's Opening",
        "LA" to "Larsen's Opening",
        "NF" to "Nimzowitsch-Larsen Attack",
        "SO" to "Sokolsky Opening (Polish)",
        "PO" to "Polish Opening",

        // Misc
        "TN" to "Tarrasch Defense",
        "TD" to "Tarrasch Defense",
        "RG" to "Ragozin Defense",
        "WA" to "Wade Defense",
        "OW" to "Owen Defense",
        "ST" to "Steinitz Defense"
    )

    val trimmed = name.trim()
    val fullName = abbreviations[trimmed.uppercase()]
    return if (fullName != null) {
        "$trimmed: $fullName"
    } else {
        trimmed
    }
}
