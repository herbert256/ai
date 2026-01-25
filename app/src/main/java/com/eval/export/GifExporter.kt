package com.eval.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.eval.chess.ChessBoard
import com.eval.chess.Piece
import com.eval.chess.PieceColor
import com.eval.chess.PieceType
import com.eval.ui.MoveScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Exports a chess game as an animated GIF.
 */
object GifExporter {

    private const val BOARD_SIZE = 400
    private const val SQUARE_SIZE = BOARD_SIZE / 8
    private const val EVAL_BAR_WIDTH = 20
    private const val TOTAL_WIDTH = BOARD_SIZE + EVAL_BAR_WIDTH

    // Colors
    private const val WHITE_SQUARE = 0xFFF0D9B5.toInt()
    private const val BLACK_SQUARE = 0xFFB58863.toInt()
    private const val WHITE_PIECE = 0xFFFFFFFF.toInt()
    private const val BLACK_PIECE = 0xFF000000.toInt()
    private const val EVAL_WHITE = 0xFFFFFFFF.toInt()
    private const val EVAL_BLACK = 0xFF333333.toInt()

    // Piece unicode characters
    private val PIECE_SYMBOLS = mapOf(
        'K' to "\u2654", 'Q' to "\u2655", 'R' to "\u2656",
        'B' to "\u2657", 'N' to "\u2658", 'P' to "\u2659",
        'k' to "\u265A", 'q' to "\u265B", 'r' to "\u265C",
        'b' to "\u265D", 'n' to "\u265E", 'p' to "\u265F"
    )

    /**
     * Convert a Piece to its FEN character representation.
     */
    private fun pieceToChar(piece: Piece): Char {
        val baseChar = when (piece.type) {
            PieceType.KING -> 'K'
            PieceType.QUEEN -> 'Q'
            PieceType.ROOK -> 'R'
            PieceType.BISHOP -> 'B'
            PieceType.KNIGHT -> 'N'
            PieceType.PAWN -> 'P'
        }
        return if (piece.color == PieceColor.WHITE) baseChar else baseChar.lowercaseChar()
    }

    /**
     * Progress callback for GIF export.
     */
    interface ProgressCallback {
        fun onProgress(current: Int, total: Int)
    }

    /**
     * Export a game as an animated GIF.
     *
     * @param context Android context
     * @param boards List of ChessBoard states for each position
     * @param scores Map of move index to evaluation score
     * @param frameDelay Delay between frames in milliseconds
     * @param callback Progress callback
     * @return File containing the exported GIF
     */
    suspend fun exportAsGif(
        context: Context,
        boards: List<ChessBoard>,
        scores: Map<Int, MoveScore> = emptyMap(),
        frameDelay: Int = 800,
        callback: ProgressCallback? = null
    ): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "game_${System.currentTimeMillis()}.gif")
        val encoder = AnimatedGifEncoder()

        FileOutputStream(file).use { fos ->
            encoder.start(fos)
            encoder.setDelay(frameDelay)
            encoder.setRepeat(0) // Loop forever
            encoder.setQuality(10)

            boards.forEachIndexed { index, board ->
                val score = scores[index]
                val bitmap = renderBoard(board, score)
                encoder.addFrame(bitmap)
                bitmap.recycle()
                callback?.onProgress(index + 1, boards.size)
            }

            encoder.finish()
        }

        file
    }

    /**
     * Render a chess position to a bitmap.
     */
    private fun renderBoard(board: ChessBoard, score: MoveScore?): Bitmap {
        val bitmap = Bitmap.createBitmap(TOTAL_WIDTH, BOARD_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw board squares
        val squarePaint = Paint()
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val isLight = (row + col) % 2 == 0
                squarePaint.color = if (isLight) WHITE_SQUARE else BLACK_SQUARE
                canvas.drawRect(
                    (col * SQUARE_SIZE).toFloat(),
                    (row * SQUARE_SIZE).toFloat(),
                    ((col + 1) * SQUARE_SIZE).toFloat(),
                    ((row + 1) * SQUARE_SIZE).toFloat(),
                    squarePaint
                )
            }
        }

        // Draw pieces
        val piecePaint = Paint().apply {
            textSize = SQUARE_SIZE * 0.85f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            typeface = Typeface.DEFAULT
        }

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val piece = board.getPiece(col, 7 - row) // file, rank (rank 7 is top row)
                if (piece != null) {
                    val pieceChar = pieceToChar(piece)
                    val symbol = PIECE_SYMBOLS[pieceChar] ?: continue
                    val isWhite = piece.color == PieceColor.WHITE

                    // Draw piece with outline for visibility
                    val x = col * SQUARE_SIZE + SQUARE_SIZE / 2f
                    val y = row * SQUARE_SIZE + SQUARE_SIZE * 0.75f

                    // Draw outline
                    piecePaint.style = Paint.Style.STROKE
                    piecePaint.strokeWidth = 2f
                    piecePaint.color = if (isWhite) BLACK_PIECE else WHITE_PIECE
                    canvas.drawText(symbol, x, y, piecePaint)

                    // Draw fill
                    piecePaint.style = Paint.Style.FILL
                    piecePaint.color = if (isWhite) WHITE_PIECE else BLACK_PIECE
                    canvas.drawText(symbol, x, y, piecePaint)
                }
            }
        }

        // Draw evaluation bar
        drawEvalBar(canvas, score)

        return bitmap
    }

    /**
     * Draw the evaluation bar on the right side.
     */
    private fun drawEvalBar(canvas: Canvas, score: MoveScore?) {
        val paint = Paint()
        val barX = BOARD_SIZE.toFloat()

        // Background (black side)
        paint.color = EVAL_BLACK
        canvas.drawRect(barX, 0f, barX + EVAL_BAR_WIDTH, BOARD_SIZE.toFloat(), paint)

        // Calculate white portion
        val whiteHeight = if (score == null) {
            BOARD_SIZE / 2f // 50% when no score
        } else if (score.isMate) {
            if (score.mateIn > 0) BOARD_SIZE.toFloat() else 0f
        } else {
            // Convert score to percentage (clamp to -10 to +10)
            val clampedScore = score.score.coerceIn(-10f, 10f)
            val percentage = (clampedScore + 10f) / 20f
            BOARD_SIZE * percentage
        }

        // Draw white portion from bottom
        paint.color = EVAL_WHITE
        canvas.drawRect(
            barX,
            BOARD_SIZE - whiteHeight,
            barX + EVAL_BAR_WIDTH,
            BOARD_SIZE.toFloat(),
            paint
        )

        // Draw score text
        if (score != null) {
            val textPaint = Paint().apply {
                textSize = 12f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }

            val scoreText = if (score.isMate) {
                if (score.mateIn > 0) "M${score.mateIn}" else "M${abs(score.mateIn)}"
            } else {
                val s = score.score
                if (s >= 0) "+%.1f".format(s) else "%.1f".format(s)
            }

            // Draw at center of bar
            val textX = barX + EVAL_BAR_WIDTH / 2
            val textY = BOARD_SIZE / 2f + 4f

            // Background for readability
            textPaint.color = Color.WHITE
            canvas.drawText(scoreText, textX, textY, textPaint)
            textPaint.color = Color.BLACK
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = 0.5f
            canvas.drawText(scoreText, textX, textY, textPaint)
        }
    }

    /**
     * Export with move annotations overlay.
     */
    suspend fun exportAsGifWithAnnotations(
        context: Context,
        boards: List<ChessBoard>,
        moves: List<String>, // SAN notation
        scores: Map<Int, MoveScore> = emptyMap(),
        frameDelay: Int = 800,
        callback: ProgressCallback? = null
    ): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "game_annotated_${System.currentTimeMillis()}.gif")
        val encoder = AnimatedGifEncoder()

        FileOutputStream(file).use { fos ->
            encoder.start(fos)
            encoder.setDelay(frameDelay)
            encoder.setRepeat(0)
            encoder.setQuality(10)

            boards.forEachIndexed { index, board ->
                val score = scores[index]
                val moveText = if (index > 0 && index <= moves.size) {
                    val moveNum = (index + 1) / 2
                    val isWhite = index % 2 == 1
                    if (isWhite) "$moveNum. ${moves[index - 1]}" else "${moves[index - 1]}"
                } else null

                val bitmap = renderBoardWithAnnotation(board, score, moveText)
                encoder.addFrame(bitmap)
                bitmap.recycle()
                callback?.onProgress(index + 1, boards.size)
            }

            encoder.finish()
        }

        file
    }

    /**
     * Render board with move annotation at the top.
     */
    private fun renderBoardWithAnnotation(
        board: ChessBoard,
        score: MoveScore?,
        moveText: String?
    ): Bitmap {
        val annotationHeight = if (moveText != null) 30 else 0
        val totalHeight = BOARD_SIZE + annotationHeight

        val bitmap = Bitmap.createBitmap(TOTAL_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw annotation bar if needed
        if (moveText != null) {
            val bgPaint = Paint().apply { color = 0xFF2D2D2D.toInt() }
            canvas.drawRect(0f, 0f, TOTAL_WIDTH.toFloat(), annotationHeight.toFloat(), bgPaint)

            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 18f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(moveText, TOTAL_WIDTH / 2f, 22f, textPaint)
        }

        // Translate canvas down for board
        canvas.save()
        canvas.translate(0f, annotationHeight.toFloat())

        // Draw board (same as renderBoard but inline)
        val squarePaint = Paint()
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val isLight = (row + col) % 2 == 0
                squarePaint.color = if (isLight) WHITE_SQUARE else BLACK_SQUARE
                canvas.drawRect(
                    (col * SQUARE_SIZE).toFloat(),
                    (row * SQUARE_SIZE).toFloat(),
                    ((col + 1) * SQUARE_SIZE).toFloat(),
                    ((row + 1) * SQUARE_SIZE).toFloat(),
                    squarePaint
                )
            }
        }

        val piecePaint = Paint().apply {
            textSize = SQUARE_SIZE * 0.85f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val piece = board.getPiece(col, 7 - row) // file, rank (rank 7 is top row)
                if (piece != null) {
                    val pieceChar = pieceToChar(piece)
                    val symbol = PIECE_SYMBOLS[pieceChar] ?: continue
                    val isWhite = piece.color == PieceColor.WHITE
                    val x = col * SQUARE_SIZE + SQUARE_SIZE / 2f
                    val y = row * SQUARE_SIZE + SQUARE_SIZE * 0.75f

                    piecePaint.style = Paint.Style.STROKE
                    piecePaint.strokeWidth = 2f
                    piecePaint.color = if (isWhite) BLACK_PIECE else WHITE_PIECE
                    canvas.drawText(symbol, x, y, piecePaint)

                    piecePaint.style = Paint.Style.FILL
                    piecePaint.color = if (isWhite) WHITE_PIECE else BLACK_PIECE
                    canvas.drawText(symbol, x, y, piecePaint)
                }
            }
        }

        drawEvalBar(canvas, score)
        canvas.restore()

        return bitmap
    }
}
