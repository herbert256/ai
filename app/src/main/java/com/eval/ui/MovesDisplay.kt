package com.eval.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Chess piece Unicode symbols for move display
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
 * Displays the list of moves in a two-column format (white/black pairs).
 */
@Composable
fun MovesList(
    moveDetails: List<MoveDetails>,
    currentMoveIndex: Int,
    moveScores: Map<Int, MoveScore>,
    moveQualities: Map<Int, MoveQuality>,
    currentStage: AnalysisStage,
    autoAnalysisIndex: Int,
    userPlayedBlack: Boolean,
    onMoveClick: (Int) -> Unit
) {
    val movePairs = remember(moveDetails) { moveDetails.chunked(2) }
    val isAutoAnalyzing = currentStage != AnalysisStage.MANUAL

    Column(modifier = Modifier.fillMaxWidth()) {
        movePairs.forEachIndexed { pairIndex, pair ->
            key(pairIndex) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Move number
                    Text(
                        text = "${pairIndex + 1}.",
                        color = Color(0xFF666666),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        modifier = Modifier.width(32.dp)
                    )

                    // White move
                    val whiteIndex = pairIndex * 2
                    MoveChip(
                        moveDetails = pair[0],
                        isWhite = true,
                        isActive = whiteIndex == currentMoveIndex,
                        isAnalyzing = isAutoAnalyzing && autoAnalysisIndex == whiteIndex,
                        score = moveScores[whiteIndex],
                        quality = moveQualities[whiteIndex],
                        userPlayedBlack = userPlayedBlack,
                        onClick = { onMoveClick(whiteIndex) },
                        modifier = Modifier.weight(1f)
                    )

                    // Black move
                    if (pair.size > 1) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val blackIndex = pairIndex * 2 + 1
                        MoveChip(
                            moveDetails = pair[1],
                            isWhite = false,
                            isActive = blackIndex == currentMoveIndex,
                            isAnalyzing = isAutoAnalyzing && autoAnalysisIndex == blackIndex,
                            score = moveScores[blackIndex],
                            quality = moveQualities[blackIndex],
                            userPlayedBlack = userPlayedBlack,
                            onClick = { onMoveClick(blackIndex) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Individual move chip displaying piece symbol, move coordinates, clock time, score, and quality.
 */
@Composable
private fun MoveChip(
    moveDetails: MoveDetails,
    isWhite: Boolean,
    isActive: Boolean,
    isAnalyzing: Boolean = false,
    score: MoveScore? = null,
    quality: MoveQuality? = null,
    userPlayedBlack: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Background color based on move quality for blunders/mistakes
    val qualityBackground = when (quality) {
        MoveQuality.BLUNDER -> Color(0x40F44336)  // Semi-transparent red
        MoveQuality.MISTAKE -> Color(0x30FF9800)  // Semi-transparent orange
        else -> Color.Transparent
    }

    val backgroundColor = when {
        isActive -> MaterialTheme.colorScheme.primary
        isAnalyzing -> Color(0xFFFFE082) // Light yellow when analyzing
        quality == MoveQuality.BLUNDER -> qualityBackground
        quality == MoveQuality.MISTAKE -> qualityBackground
        else -> Color.Transparent
    }

    // Get piece symbol
    val pieceSymbol = when (moveDetails.pieceType) {
        "K" -> if (isWhite) BLACK_KING else WHITE_KING
        "Q" -> if (isWhite) BLACK_QUEEN else WHITE_QUEEN
        "R" -> if (isWhite) BLACK_ROOK else WHITE_ROOK
        "B" -> if (isWhite) BLACK_BISHOP else WHITE_BISHOP
        "N" -> if (isWhite) BLACK_KNIGHT else WHITE_KNIGHT
        else -> if (isWhite) BLACK_PAWN else WHITE_PAWN
    }

    // Build move text: piece from-to or piece fromxto
    val separator = if (moveDetails.isCapture) "x" else "-"
    val moveText = "$pieceSymbol ${moveDetails.from}$separator${moveDetails.to}"

    // Quality symbol and color
    val qualitySymbol = quality?.symbol ?: ""
    val qualityColor = quality?.let { Color(it.color.toInt()) } ?: Color.Transparent

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = moveText,
            fontSize = 15.sp,
            color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface
        )

        // Show quality symbol if available and not NORMAL
        if (qualitySymbol.isNotEmpty()) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = qualitySymbol,
                fontSize = 13.sp,
                color = if (isActive) Color.White else qualityColor
            )
        }

        // Show clock time if available
        if (moveDetails.clockTime != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = moveDetails.clockTime,
                fontSize = 11.sp,
                color = if (isActive) Color.White.copy(alpha = 0.7f) else Color(0xFF888888)
            )
        }

        // Show score if available (from player's perspective)
        if (score != null) {
            Spacer(modifier = Modifier.width(4.dp))
            // Convert score to player's perspective
            val playerScore = if (userPlayedBlack) -score.score else score.score
            val playerMateIn = if (userPlayedBlack) -score.mateIn else score.mateIn
            val scoreText = if (score.isMate) {
                "M${kotlin.math.abs(playerMateIn)}"
            } else {
                "%.1f".format(kotlin.math.abs(playerScore))
            }
            val scoreColor = when {
                isActive -> Color.White.copy(alpha = 0.9f)
                score.isMate && playerMateIn > 0 -> Color(0xFF00E676) // Green for player winning mate
                score.isMate && playerMateIn < 0 -> Color(0xFFFF5252) // Red for player losing mate
                playerScore > 0.1f -> Color(0xFF00E676) // Green for player better
                playerScore < -0.1f -> Color(0xFFFF5252) // Red for player worse
                else -> Color(0xFF2196F3) // Blue for equal (0)
            }
            Text(
                text = scoreText,
                fontSize = 15.sp,
                color = scoreColor
            )
        } else if (isAnalyzing) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "...",
                fontSize = 15.sp,
                color = Color(0xFF666666)
            )
        }
    }
}
