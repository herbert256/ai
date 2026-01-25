package com.eval.export

import com.eval.data.LichessGame
import com.eval.ui.MoveDetails
import com.eval.ui.MoveQuality
import com.eval.ui.MoveScore

/**
 * Exports games as annotated PGN with evaluations and move quality symbols.
 */
object PgnExporter {

    /**
     * Export a game as annotated PGN string.
     *
     * @param game The game data
     * @param moveDetails List of move details
     * @param analyseScores Map of move index to score
     * @param moveQualities Map of move index to quality
     * @param openingName Optional opening name
     * @return Formatted PGN string
     */
    fun exportAnnotatedPgn(
        game: LichessGame,
        moveDetails: List<MoveDetails>,
        analyseScores: Map<Int, MoveScore>,
        moveQualities: Map<Int, MoveQuality>,
        openingName: String?
    ): String {
        val sb = StringBuilder()

        // PGN Headers
        sb.appendLine("[Event \"${game.perf ?: "Game"}\"]")
        sb.appendLine("[Site \"${if (game.id.length > 8) "Chess.com" else "Lichess.org"}\"]")
        sb.appendLine("[Date \"${formatDate(game.createdAt)}\"]")
        sb.appendLine("[White \"${game.players.white.user?.name ?: "Unknown"}\"]")
        sb.appendLine("[Black \"${game.players.black.user?.name ?: "Unknown"}\"]")
        sb.appendLine("[Result \"${formatResult(game.winner, game.status)}\"]")
        game.players.white.rating?.let { sb.appendLine("[WhiteElo \"$it\"]") }
        game.players.black.rating?.let { sb.appendLine("[BlackElo \"$it\"]") }
        openingName?.let { sb.appendLine("[Opening \"$it\"]") }
        sb.appendLine("[Annotator \"Eval App - Stockfish 17.1\"]")
        sb.appendLine()

        // Moves with annotations
        val moves = mutableListOf<String>()

        for (i in moveDetails.indices) {
            val detail = moveDetails[i]
            val moveNum = (i / 2) + 1
            val isWhite = i % 2 == 0

            val moveText = StringBuilder()

            // Add move number
            if (isWhite) {
                moveText.append("$moveNum. ")
            } else if (i == 0 || moves.isEmpty()) {
                moveText.append("$moveNum... ")
            }

            // Add move notation
            moveText.append(detail.san)

            // Add quality symbol (NAG)
            val quality = moveQualities[i]
            when (quality) {
                MoveQuality.BRILLIANT -> moveText.append("!!")
                MoveQuality.GOOD -> moveText.append("!")
                MoveQuality.INTERESTING -> moveText.append("!?")
                MoveQuality.DUBIOUS -> moveText.append("?!")
                MoveQuality.MISTAKE -> moveText.append("?")
                MoveQuality.BLUNDER -> moveText.append("??")
                else -> {}
            }

            // Add evaluation comment
            val score = analyseScores[i]
            if (score != null) {
                val evalText = if (score.isMate) {
                    if (score.mateIn > 0) "+M${score.mateIn}" else "-M${kotlin.math.abs(score.mateIn)}"
                } else {
                    if (score.score >= 0) "+%.2f".format(score.score) else "%.2f".format(score.score)
                }
                moveText.append(" {[%eval $evalText]}")
            }

            // Add clock comment if available
            detail.clockTime?.let { clock ->
                moveText.append(" {[%clk $clock]}")
            }

            moves.add(moveText.toString())
        }

        // Format moves with line wrapping
        var lineLength = 0
        val maxLineLength = 80

        for (move in moves) {
            if (lineLength + move.length + 1 > maxLineLength && lineLength > 0) {
                sb.appendLine()
                lineLength = 0
            }
            if (lineLength > 0) {
                sb.append(" ")
                lineLength++
            }
            sb.append(move)
            lineLength += move.length
        }

        // Add result
        sb.append(" ${formatResult(game.winner, game.status)}")
        sb.appendLine()

        return sb.toString()
    }

    private fun formatDate(timestamp: Long?): String {
        if (timestamp == null) return "????.??.??"
        val date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US)
        return date.format(java.util.Date(timestamp))
    }

    private fun formatResult(winner: String?, status: String?): String {
        return when {
            winner == "white" -> "1-0"
            winner == "black" -> "0-1"
            status == "draw" || status == "stalemate" -> "1/2-1/2"
            else -> "*"
        }
    }
}
