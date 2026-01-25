package com.eval.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eval.chess.ChessBoard
import com.eval.chess.PieceColor
import com.eval.chess.Square
import com.eval.stockfish.PvLine

// Chess piece Unicode symbols for analysis display
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
 * Evaluation graph showing position scores over time.
 */
@Composable
fun EvaluationGraph(
    previewScores: Map<Int, MoveScore>,
    analyseScores: Map<Int, MoveScore>,
    moveQualities: Map<Int, MoveQuality>,
    totalMoves: Int,
    currentMoveIndex: Int,
    currentStage: AnalysisStage,
    userPlayedBlack: Boolean,
    graphSettings: GraphSettings,
    onMoveSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Score multiplier: invert scores when user played black so positive = good for user
    val scorePerspective = if (userPlayedBlack) -1f else 1f
    val greenColor = Color(graphSettings.plusScoreColor.toInt())
    val redColor = Color(graphSettings.negativeScoreColor.toInt())
    val lineColor = Color(0xFF666666)
    val currentMoveColor = Color(graphSettings.verticalLineColor.toInt())
    val analyseColor = Color(graphSettings.analyseLineColor.toInt())
    val blunderMarkerColor = Color(0xFFFF5252)  // Red for blunders
    val mistakeMarkerColor = Color(0xFFFF9800)  // Orange for mistakes

    // Track the graph width for calculating move index from drag position
    var graphWidth by remember { mutableStateOf(0f) }
    val isManualStage = currentStage == AnalysisStage.MANUAL

    Canvas(
        modifier = modifier
            .background(Color(graphSettings.backgroundColor.toInt()), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .pointerInput(totalMoves, currentStage) {
                // Only allow horizontal drag navigation in manual stage
                if (totalMoves > 0 && isManualStage) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val x = change.position.x.coerceIn(0f, graphWidth)
                        val moveIndex = if (totalMoves > 1) {
                            ((x / graphWidth) * (totalMoves - 1) + 0.5f).toInt().coerceIn(0, totalMoves - 1)
                        } else {
                            0
                        }
                        onMoveSelected(moveIndex)
                    }
                }
            }
            .pointerInput(totalMoves, currentStage) {
                // Allow taps in Analyse and Manual stages (not Preview)
                if (totalMoves > 0 && currentStage != AnalysisStage.PREVIEW) {
                    detectTapGestures(
                        onTap = { offset ->
                            val x = offset.x.coerceIn(0f, graphWidth)
                            val moveIndex = if (totalMoves > 1) {
                                ((x / graphWidth) * (totalMoves - 1) + 0.5f).toInt().coerceIn(0, totalMoves - 1)
                            } else {
                                0
                            }
                            onMoveSelected(moveIndex)
                        }
                    )
                }
            }
    ) {
        if (totalMoves == 0) return@Canvas

        val width = size.width
        val height = size.height
        graphWidth = width
        val centerY = height / 2
        val maxScore = graphSettings.lineGraphRange.toFloat() // Range from settings

        // Draw center line (x-axis)
        drawLine(
            color = lineColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        // Calculate point spacing
        val pointSpacing = if (totalMoves > 1) width / (totalMoves - 1) else width / 2

        // Build list of points with their scores
        data class GraphPoint(val x: Float, val y: Float, val score: Float)
        val points = mutableListOf<GraphPoint>()

        for (moveIndex in 0 until totalMoves) {
            val score = previewScores[moveIndex]
            if (score != null) {
                val x = if (totalMoves > 1) moveIndex * pointSpacing else width / 2
                // Use raw Stockfish score, but for mate use +/- lineGraphRange
                val rawScore = if (score.isMate) {
                    if (score.mateIn > 0) maxScore else -maxScore
                } else {
                    score.score
                }
                val adjustedScore = rawScore * scorePerspective
                val clampedScore = adjustedScore.coerceIn(-maxScore, maxScore)
                val y = centerY - (clampedScore / maxScore) * (height / 2 - 4)
                points.add(GraphPoint(x, y, adjustedScore))
            }
        }

        // Draw filled areas between consecutive points
        // Use slight overlap (1px) to prevent anti-aliasing gaps
        val overlap = 1f

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            // Extend left edge back and right edge forward to overlap with neighbors
            val leftX = if (i == 0) p1.x else p1.x - overlap
            val rightX = if (i == points.size - 2) p2.x else p2.x + overlap

            // Check if the line crosses the x-axis (scores have different signs)
            val crossesAxis = (p1.score >= 0 && p2.score < 0) || (p1.score < 0 && p2.score >= 0)

            if (crossesAxis) {
                // Find the x-coordinate where the line crosses the x-axis
                val t = kotlin.math.abs(p1.score) / (kotlin.math.abs(p1.score) + kotlin.math.abs(p2.score))
                val crossX = p1.x + (p2.x - p1.x) * t

                // Draw first segment (from p1 to crossing point)
                val color1 = if (p1.score >= 0) greenColor else redColor
                val path1 = androidx.compose.ui.graphics.Path().apply {
                    moveTo(leftX, p1.y)
                    lineTo(crossX, centerY)
                    lineTo(leftX, centerY)
                    close()
                }
                drawPath(path1, color1)

                // Draw second segment (from crossing point to p2)
                val color2 = if (p2.score >= 0) greenColor else redColor
                val path2 = androidx.compose.ui.graphics.Path().apply {
                    moveTo(crossX, centerY)
                    lineTo(rightX, p2.y)
                    lineTo(rightX, centerY)
                    close()
                }
                drawPath(path2, color2)

                // Draw solid line on top (two segments with different colors)
                drawLine(color1, Offset(p1.x, p1.y), Offset(crossX, centerY), strokeWidth = 2f)
                drawLine(color2, Offset(crossX, centerY), Offset(p2.x, p2.y), strokeWidth = 2f)
            } else {
                // No crossing - draw single colored area
                val color = if (p1.score >= 0) greenColor else redColor

                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(leftX, p1.y)
                    lineTo(rightX, p2.y)
                    lineTo(rightX, centerY)
                    lineTo(leftX, centerY)
                    close()
                }
                drawPath(path, color)

                // Draw solid line on top
                drawLine(color, Offset(p1.x, p1.y), Offset(p2.x, p2.y), strokeWidth = 2f)
            }
        }

        // Build list of points for analyse stage scores
        val pointsAnalyse = mutableListOf<GraphPoint>()
        for (moveIndex in 0 until totalMoves) {
            val score = analyseScores[moveIndex]
            if (score != null) {
                val x = if (totalMoves > 1) moveIndex * pointSpacing else width / 2
                // Use raw Stockfish score, but for mate use +/- lineGraphRange
                val rawScore = if (score.isMate) {
                    if (score.mateIn > 0) maxScore else -maxScore
                } else {
                    score.score
                }
                val adjustedScore = rawScore * scorePerspective
                val clampedScore = adjustedScore.coerceIn(-maxScore, maxScore)
                val y = centerY - (clampedScore / maxScore) * (height / 2 - 4)
                pointsAnalyse.add(GraphPoint(x, y, adjustedScore))
            }
        }

        // Draw analyse stage as white line
        for (i in 0 until pointsAnalyse.size - 1) {
            val p1 = pointsAnalyse[i]
            val p2 = pointsAnalyse[i + 1]
            drawLine(analyseColor, Offset(p1.x, p1.y), Offset(p2.x, p2.y), strokeWidth = 7f)
        }

        // Draw blunder/mistake markers (only in manual stage)
        if (isManualStage) {
            moveQualities.forEach { (index, quality) ->
                if (quality == MoveQuality.BLUNDER || quality == MoveQuality.MISTAKE) {
                    val markerX = if (totalMoves > 1) index * pointSpacing else width / 2
                    val markerColor = if (quality == MoveQuality.BLUNDER) blunderMarkerColor else mistakeMarkerColor
                    // Draw a thin vertical line at the blunder/mistake position
                    drawLine(
                        color = markerColor.copy(alpha = 0.6f),
                        start = Offset(markerX, 0f),
                        end = Offset(markerX, height),
                        strokeWidth = 2f
                    )
                }
            }
        }

        // Draw current move indicator (only in manual stage)
        if (isManualStage && currentMoveIndex >= 0 && currentMoveIndex < totalMoves) {
            val x = if (totalMoves > 1) currentMoveIndex * pointSpacing else width / 2
            drawLine(
                color = currentMoveColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 5f
            )
        }
    }
}

/**
 * Time usage graph showing remaining clock time for both players.
 */
@Composable
fun TimeUsageGraph(
    moveDetails: List<MoveDetails>,
    currentMoveIndex: Int,
    currentStage: AnalysisStage,
    graphSettings: GraphSettings,
    onMoveSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val whiteTimeColor = Color(0xFFFFFFFF)  // White for white's time
    val blackTimeColor = Color(0xFF888888)  // Gray for black's time
    val lineColor = Color(0xFF444444)
    val currentMoveColor = Color(graphSettings.verticalLineColor.toInt())

    var graphWidth by remember { mutableStateOf(0f) }
    val isManualStage = currentStage == AnalysisStage.MANUAL

    // Parse clock times to seconds
    val whiteTimes = mutableListOf<Pair<Int, Int>>()  // (moveIndex, seconds)
    val blackTimes = mutableListOf<Pair<Int, Int>>()

    moveDetails.forEachIndexed { index, detail ->
        val seconds = parseClockTimeToSeconds(detail.clockTime)
        if (seconds != null) {
            if (index % 2 == 0) {
                whiteTimes.add(index to seconds)
            } else {
                blackTimes.add(index to seconds)
            }
        }
    }

    // If no clock data, don't show the graph
    if (whiteTimes.isEmpty() && blackTimes.isEmpty()) {
        return
    }

    val maxTime = maxOf(
        whiteTimes.maxOfOrNull { it.second } ?: 0,
        blackTimes.maxOfOrNull { it.second } ?: 0
    ).toFloat().coerceAtLeast(60f)

    Canvas(
        modifier = modifier
            .background(Color(graphSettings.backgroundColor.toInt()), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .pointerInput(moveDetails.size, currentStage) {
                if (moveDetails.isNotEmpty() && isManualStage) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val x = change.position.x.coerceIn(0f, graphWidth)
                        val moveIndex = ((x / graphWidth) * (moveDetails.size - 1) + 0.5f)
                            .toInt().coerceIn(0, moveDetails.size - 1)
                        onMoveSelected(moveIndex)
                    }
                }
            }
            .pointerInput(moveDetails.size, currentStage) {
                if (moveDetails.isNotEmpty() && currentStage != AnalysisStage.PREVIEW) {
                    detectTapGestures(
                        onTap = { offset ->
                            val x = offset.x.coerceIn(0f, graphWidth)
                            val moveIndex = ((x / graphWidth) * (moveDetails.size - 1) + 0.5f)
                                .toInt().coerceIn(0, moveDetails.size - 1)
                            onMoveSelected(moveIndex)
                        }
                    )
                }
            }
    ) {
        val width = size.width
        val height = size.height
        graphWidth = width
        val totalMoves = moveDetails.size
        if (totalMoves == 0) return@Canvas

        val pointSpacing = if (totalMoves > 1) width / (totalMoves - 1) else width / 2

        // Draw horizontal grid lines
        for (i in 1..3) {
            val y = height * i / 4
            drawLine(lineColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
        }

        // Draw white's time line
        if (whiteTimes.size > 1) {
            for (i in 0 until whiteTimes.size - 1) {
                val (idx1, t1) = whiteTimes[i]
                val (idx2, t2) = whiteTimes[i + 1]
                val x1 = idx1 * pointSpacing
                val x2 = idx2 * pointSpacing
                val y1 = height - (t1 / maxTime) * height
                val y2 = height - (t2 / maxTime) * height
                drawLine(whiteTimeColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
            }
        }

        // Draw black's time line
        if (blackTimes.size > 1) {
            for (i in 0 until blackTimes.size - 1) {
                val (idx1, t1) = blackTimes[i]
                val (idx2, t2) = blackTimes[i + 1]
                val x1 = idx1 * pointSpacing
                val x2 = idx2 * pointSpacing
                val y1 = height - (t1 / maxTime) * height
                val y2 = height - (t2 / maxTime) * height
                drawLine(blackTimeColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
            }
        }

        // Draw current move indicator (only in manual stage)
        if (isManualStage && currentMoveIndex >= 0 && currentMoveIndex < totalMoves) {
            val x = currentMoveIndex * pointSpacing
            drawLine(
                color = currentMoveColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 3f
            )
        }
    }
}

/**
 * Parse clock time string (e.g., "10:30" or "1:30:45") to total seconds.
 */
private fun parseClockTimeToSeconds(time: String?): Int? {
    if (time.isNullOrBlank()) return null
    val parts = time.split(":")
    return try {
        when (parts.size) {
            3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
            2 -> parts[0].toInt() * 60 + parts[1].toInt()
            1 -> parts[0].toInt()
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * Bar graph showing the score difference between consecutive moves.
 * Highlights blunders (big negative bars) and good moves (positive bars).
 * Uses analyse scores when available, otherwise preview scores.
 */
@Composable
fun ScoreDifferenceGraph(
    previewScores: Map<Int, MoveScore>,
    analyseScores: Map<Int, MoveScore>,
    totalMoves: Int,
    currentMoveIndex: Int,
    currentStage: AnalysisStage,
    userPlayedBlack: Boolean,
    graphSettings: GraphSettings,
    onMoveSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Score multiplier: invert scores when user played black so positive = good for user
    val scorePerspective = if (userPlayedBlack) -1f else 1f
    val goodMoveColor = Color(graphSettings.plusScoreColor.toInt())
    val blunderColor = Color(graphSettings.negativeScoreColor.toInt())
    val lineColor = Color(0xFF666666)
    val currentMoveColor = Color(graphSettings.verticalLineColor.toInt())

    var graphWidth by remember { mutableStateOf(0f) }
    val isManualStage = currentStage == AnalysisStage.MANUAL

    Canvas(
        modifier = modifier
            .background(Color(graphSettings.backgroundColor.toInt()), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .pointerInput(totalMoves, currentStage) {
                if (totalMoves > 0 && isManualStage) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val x = change.position.x.coerceIn(0f, graphWidth)
                        val moveIndex = if (totalMoves > 0) {
                            ((x / graphWidth) * totalMoves).toInt().coerceIn(0, totalMoves - 1)
                        } else {
                            0
                        }
                        onMoveSelected(moveIndex)
                    }
                }
            }
            .pointerInput(totalMoves, currentStage) {
                if (totalMoves > 0 && currentStage != AnalysisStage.PREVIEW) {
                    detectTapGestures(
                        onTap = { offset ->
                            val x = offset.x.coerceIn(0f, graphWidth)
                            val moveIndex = if (totalMoves > 0) {
                                ((x / graphWidth) * totalMoves).toInt().coerceIn(0, totalMoves - 1)
                            } else {
                                0
                            }
                            onMoveSelected(moveIndex)
                        }
                    )
                }
            }
    ) {
        if (totalMoves == 0) return@Canvas

        val width = size.width
        val height = size.height
        graphWidth = width
        val centerY = height / 2
        val maxDiff = graphSettings.barGraphRange.toFloat() // Range from settings

        // Draw center line (x-axis at 0 difference)
        drawLine(
            color = lineColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f
        )

        // Calculate bar width based on number of moves
        val barWidth = if (totalMoves > 0) (width / totalMoves) * 0.8f else width * 0.1f
        val barSpacing = if (totalMoves > 0) width / totalMoves else width

        val isAnalyseStage = currentStage == AnalysisStage.ANALYSE

        // Draw bars for each move
        // Compare with the previous move of the SAME COLOR (2 plies back, not 1)
        // This shows how much the position changed after opponent's move and your response
        for (moveIndex in 0 until totalMoves) {
            val currentScore: MoveScore?
            val prevSameColorScore: MoveScore?

            // Previous move of same color is 2 plies back
            val prevSameColorIndex = moveIndex - 2

            if (isAnalyseStage) {
                // During Analyse stage: use analyse scores if BOTH are available,
                // otherwise fall back to preview scores
                val hasAnalyseCurrent = analyseScores.containsKey(moveIndex)
                val hasAnalysePrev = prevSameColorIndex < 0 || analyseScores.containsKey(prevSameColorIndex)

                if (hasAnalyseCurrent && hasAnalysePrev) {
                    // Both analyse scores available - use them
                    currentScore = analyseScores[moveIndex]
                    prevSameColorScore = if (prevSameColorIndex >= 0) analyseScores[prevSameColorIndex] else null
                } else {
                    // Fall back to preview scores
                    currentScore = previewScores[moveIndex]
                    prevSameColorScore = if (prevSameColorIndex >= 0) previewScores[prevSameColorIndex] else null
                }
            } else {
                // Preview/Manual stage: prefer analyse scores, fall back to preview
                currentScore = analyseScores[moveIndex] ?: previewScores[moveIndex]
                prevSameColorScore = if (prevSameColorIndex >= 0) {
                    analyseScores[prevSameColorIndex] ?: previewScores[prevSameColorIndex]
                } else null
            }

            if (currentScore != null && prevSameColorScore != null) {
                // Calculate difference based on mate handling rules
                val prevIsMate = prevSameColorScore.isMate
                val currIsMate = currentScore.isMate

                // M value is the absolute value of mateIn (ignoring sign)
                val prevMValue = kotlin.math.abs(prevSameColorScore.mateIn)
                val currMValue = kotlin.math.abs(currentScore.mateIn)

                // Check if winning (+M*) or losing (-M*) mate
                val prevIsPositiveMate = prevIsMate && prevSameColorScore.mateIn > 0
                val prevIsNegativeMate = prevIsMate && prevSameColorScore.mateIn < 0
                val currIsPositiveMate = currIsMate && currentScore.mateIn > 0
                val currIsNegativeMate = currIsMate && currentScore.mateIn < 0

                val rawDiff: Float = when {
                    // Both +M* (winning mate for both)
                    prevIsPositiveMate && currIsPositiveMate -> when {
                        currMValue == prevMValue -> -1f
                        currMValue == prevMValue - 1 -> 0f
                        currMValue > prevMValue -> -(1 + (currMValue - prevMValue)).toFloat().coerceAtMost(maxDiff)
                        currMValue < prevMValue -> ((prevMValue - currMValue) + 1).toFloat().coerceAtMost(3f)
                        else -> 0f
                    }

                    // Both -M* (losing mate for both)
                    prevIsNegativeMate && currIsNegativeMate -> when {
                        currMValue == prevMValue -> -1f
                        currMValue == prevMValue - 1 -> 0f
                        currMValue > prevMValue -> (1 + (currMValue - prevMValue)).toFloat().coerceAtMost(maxDiff)
                        currMValue < prevMValue -> -((prevMValue - currMValue) + 1).toFloat().coerceAtLeast(-maxDiff)
                        else -> 0f
                    }

                    // +M* to -M* (lost winning mate, now losing)
                    prevIsPositiveMate && currIsNegativeMate -> -maxDiff

                    // -M* to +M* (escaped losing mate, now winning)
                    prevIsNegativeMate && currIsPositiveMate -> maxDiff

                    // Previous normal, current +M*
                    !prevIsMate && currIsPositiveMate -> maxDiff

                    // Previous normal, current -M*
                    !prevIsMate && currIsNegativeMate -> -maxDiff

                    // Previous +M*, current normal
                    prevIsPositiveMate && !currIsMate -> -maxDiff

                    // Previous -M*, current normal
                    prevIsNegativeMate && !currIsMate -> maxDiff

                    // Both normal scores
                    !prevIsMate && !currIsMate -> when {
                        currentScore.score == prevSameColorScore.score -> 0f
                        currentScore.score > prevSameColorScore.score ->
                            (currentScore.score - prevSameColorScore.score).coerceAtMost(maxDiff)
                        else ->
                            (currentScore.score - prevSameColorScore.score).coerceAtLeast(-maxDiff)
                    }

                    else -> 0f
                }

                val diff = rawDiff * scorePerspective
                val clampedDiff = diff.coerceIn(-maxDiff, maxDiff)
                val barHeight = kotlin.math.abs(clampedDiff / maxDiff) * (height / 2 - 4)

                val barX = moveIndex * barSpacing + (barSpacing - barWidth) / 2
                val color = if (diff >= 0) goodMoveColor else blunderColor

                if (diff >= 0) {
                    // Bar goes up from center
                    drawRect(
                        color = color,
                        topLeft = Offset(barX, centerY - barHeight),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                    )
                } else {
                    // Bar goes down from center
                    drawRect(
                        color = color,
                        topLeft = Offset(barX, centerY),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                    )
                }
            }
        }

        // Draw current move indicator (only in manual stage)
        if (isManualStage && currentMoveIndex >= 0 && currentMoveIndex < totalMoves) {
            val x = currentMoveIndex * barSpacing + barSpacing / 2
            drawLine(
                color = currentMoveColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 3f
            )
        }
    }
}

/**
 * Panel displaying Stockfish analysis results with multiple PV lines.
 */
@Composable
fun AnalysisPanel(
    uiState: GameUiState,
    onExploreLine: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val result = uiState.analysisResult
    val turn = uiState.currentBoard.getTurn()
    val isWhiteTurn = turn == PieceColor.WHITE

    // Show if analysis is enabled, ready, and has results
    // Keep showing even if result is stale (waiting for new position analysis) to avoid UI jumping
    val currentFen = uiState.currentBoard.getFen()
    val isResultForCurrentPosition = uiState.analysisResultFen == currentFen
    if (!uiState.analysisEnabled || !uiState.stockfishReady || result == null) {
        return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title row with depth and nodes info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stockfish 17.1",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFAAAAAA)
                )
                // Format nodes with K or M suffix
                val nodesFormatted = when {
                    result.nodes >= 1_000_000 -> "${result.nodes / 1_000_000}M"
                    result.nodes >= 1_000 -> "${result.nodes / 1_000}K"
                    else -> "${result.nodes}"
                }
                Text(
                    text = "Depth: ${result.depth}  Nodes: $nodesFormatted",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF888888)
                )
            }
            result.lines.forEach { line ->
                PvLineRow(
                    line = line,
                    board = uiState.currentBoard,
                    isWhiteTurn = isWhiteTurn,
                    userPlayedBlack = uiState.userPlayedBlack,
                    onMoveClick = { moveIndex ->
                        onExploreLine(line.pv, moveIndex)
                    }
                )
            }
        }
    }
}

/**
 * Row displaying a single principal variation line with score and clickable moves.
 */
@Composable
private fun PvLineRow(
    line: PvLine,
    board: ChessBoard,
    isWhiteTurn: Boolean,
    userPlayedBlack: Boolean,
    onMoveClick: (Int) -> Unit
) {
    // Score display: from player's perspective (positive = good for player)
    // First convert score to WHITE's perspective (Stockfish gives score from side-to-move's view)
    val whiteScore = if (isWhiteTurn) line.score else -line.score
    val whiteMateIn = if (isWhiteTurn) line.mateIn else -line.mateIn
    // Then convert to player's perspective
    val adjustedScore = if (userPlayedBlack) -whiteScore else whiteScore
    val adjustedMateIn = if (userPlayedBlack) -whiteMateIn else whiteMateIn

    val displayScore = if (line.isMate) {
        if (adjustedMateIn > 0) "+M${adjustedMateIn}" else "-M${kotlin.math.abs(adjustedMateIn)}"
    } else {
        if (adjustedScore >= 0) "+%.1f".format(adjustedScore)
        else "%.1f".format(adjustedScore)
    }

    val scoreColor = when {
        line.isMate -> if (adjustedMateIn > 0) Color(0xFF00E676) else Color(0xFFFF5252)
        else -> {
            when {
                adjustedScore > 0.3f -> Color(0xFF00E676)  // Green - good for player
                adjustedScore < -0.3f -> Color(0xFFFF5252)  // Red - bad for player
                else -> Color(0xFF6B9BFF)  // Blue - equal
            }
        }
    }

    // Format UCI moves with piece symbols and - or x for captures
    val formattedMoves = remember(line.pv, board) {
        formatUciMovesWithCaptures(line.pv, board, isWhiteTurn)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Score box - consistent styling for all lines
        Box(
            modifier = Modifier
                .width(50.dp)
                .background(Color(0xFF151D30), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayScore,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = scoreColor
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // PV moves with piece symbols - clickable
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .background(Color(0xFF0F1629), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            formattedMoves.forEachIndexed { index, formattedMove ->
                Text(
                    text = formattedMove,
                    fontSize = 14.sp,
                    color = Color(0xFFCCCCCC),
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { onMoveClick(index) }
                        .background(Color(0xFF1A2744))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/**
 * Format UCI moves with piece symbols and capture notation.
 */
private fun formatUciMovesWithCaptures(pv: String, startBoard: ChessBoard, isWhiteTurn: Boolean): List<String> {
    if (pv.isBlank()) return emptyList()

    val moves = pv.split(" ").filter { it.isNotBlank() }
    val result = mutableListOf<String>()
    val tempBoard = startBoard.copy()
    var currentIsWhite = isWhiteTurn

    for (uciMove in moves) {
        if (uciMove.length < 4) continue

        val fromStr = uciMove.substring(0, 2)
        val toStr = uciMove.substring(2, 4)
        val promotion = if (uciMove.length > 4) uciMove.substring(4).uppercase() else ""

        val fromSquare = Square.fromAlgebraic(fromStr)
        val toSquare = Square.fromAlgebraic(toStr)

        // Get the piece on the from square to determine the correct symbol
        val piece = fromSquare?.let { tempBoard.getPiece(it) }
        val symbol = if (piece != null) {
            val isWhitePiece = piece.color == com.eval.chess.PieceColor.WHITE
            getPieceSymbol(piece.type, isWhitePiece)
        } else {
            // Fallback - inverted because Unicode symbols are visually inverted
            if (currentIsWhite) BLACK_PAWN else WHITE_PAWN
        }

        // Check for capture: either there's a piece on target square, or it's en passant
        val targetPiece = toSquare?.let { tempBoard.getPiece(it) }
        val isPawn = piece?.type == com.eval.chess.PieceType.PAWN
        val isEnPassant = isPawn && fromSquare != null && toSquare != null &&
            fromSquare.file != toSquare.file && targetPiece == null
        val isCapture = targetPiece != null || isEnPassant

        val separator = if (isCapture) "x" else "-"
        val formatted = "$symbol $fromStr$separator$toStr$promotion"

        result.add(formatted)

        // Make the move on temp board for next iteration
        tempBoard.makeUciMove(uciMove)
        currentIsWhite = !currentIsWhite
    }

    return result
}

/**
 * Get the correct piece symbol based on piece type and color.
 * Note: Unicode chess symbols are inverted - "white" symbols appear filled, "black" appear hollow
 */
private fun getPieceSymbol(pieceType: com.eval.chess.PieceType, isWhite: Boolean): String {
    return when (pieceType) {
        com.eval.chess.PieceType.KING -> if (isWhite) BLACK_KING else WHITE_KING
        com.eval.chess.PieceType.QUEEN -> if (isWhite) BLACK_QUEEN else WHITE_QUEEN
        com.eval.chess.PieceType.ROOK -> if (isWhite) BLACK_ROOK else WHITE_ROOK
        com.eval.chess.PieceType.BISHOP -> if (isWhite) BLACK_BISHOP else WHITE_BISHOP
        com.eval.chess.PieceType.KNIGHT -> if (isWhite) BLACK_KNIGHT else WHITE_KNIGHT
        com.eval.chess.PieceType.PAWN -> if (isWhite) BLACK_PAWN else WHITE_PAWN
    }
}
