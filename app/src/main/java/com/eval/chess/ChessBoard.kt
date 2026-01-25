package com.eval.chess

enum class PieceColor { WHITE, BLACK }

enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class Piece(val type: PieceType, val color: PieceColor)

data class Square(val file: Int, val rank: Int) {
    companion object {
        fun fromAlgebraic(notation: String): Square? {
            if (notation.length != 2) return null
            val file = notation[0] - 'a'
            val rank = notation[1] - '1'
            if (file !in 0..7 || rank !in 0..7) return null
            return Square(file, rank)
        }
    }

    fun toAlgebraic(): String = "${('a' + file)}${rank + 1}"

    val index: Int get() = rank * 8 + file
}

data class Move(
    val from: Square,
    val to: Square,
    val promotion: PieceType? = null,
    val san: String = ""
)

class ChessBoard {
    private val board = arrayOfNulls<Piece>(64)
    private var turn: PieceColor = PieceColor.WHITE
    private var castlingRights = mutableSetOf('K', 'Q', 'k', 'q')
    private var enPassantSquare: Square? = null
    private var halfMoveClock = 0
    private var fullMoveNumber = 1
    private var lastMove: Move? = null

    init {
        reset()
    }

    fun reset() {
        // Clear board
        for (i in 0..63) board[i] = null

        // Set up white pieces
        board[0] = Piece(PieceType.ROOK, PieceColor.WHITE)
        board[1] = Piece(PieceType.KNIGHT, PieceColor.WHITE)
        board[2] = Piece(PieceType.BISHOP, PieceColor.WHITE)
        board[3] = Piece(PieceType.QUEEN, PieceColor.WHITE)
        board[4] = Piece(PieceType.KING, PieceColor.WHITE)
        board[5] = Piece(PieceType.BISHOP, PieceColor.WHITE)
        board[6] = Piece(PieceType.KNIGHT, PieceColor.WHITE)
        board[7] = Piece(PieceType.ROOK, PieceColor.WHITE)
        for (i in 8..15) board[i] = Piece(PieceType.PAWN, PieceColor.WHITE)

        // Set up black pieces
        board[56] = Piece(PieceType.ROOK, PieceColor.BLACK)
        board[57] = Piece(PieceType.KNIGHT, PieceColor.BLACK)
        board[58] = Piece(PieceType.BISHOP, PieceColor.BLACK)
        board[59] = Piece(PieceType.QUEEN, PieceColor.BLACK)
        board[60] = Piece(PieceType.KING, PieceColor.BLACK)
        board[61] = Piece(PieceType.BISHOP, PieceColor.BLACK)
        board[62] = Piece(PieceType.KNIGHT, PieceColor.BLACK)
        board[63] = Piece(PieceType.ROOK, PieceColor.BLACK)
        for (i in 48..55) board[i] = Piece(PieceType.PAWN, PieceColor.BLACK)

        turn = PieceColor.WHITE
        castlingRights = mutableSetOf('K', 'Q', 'k', 'q')
        enPassantSquare = null
        halfMoveClock = 0
        fullMoveNumber = 1
        lastMove = null
    }

    fun getPiece(square: Square): Piece? = board[square.index]

    fun getPiece(file: Int, rank: Int): Piece? {
        if (file !in 0..7 || rank !in 0..7) return null
        return board[rank * 8 + file]
    }

    fun getTurn(): PieceColor = turn

    fun getLastMove(): Move? = lastMove

    fun getFen(): String {
        val sb = StringBuilder()

        // Board position
        for (rank in 7 downTo 0) {
            var emptyCount = 0
            for (file in 0..7) {
                val piece = getPiece(file, rank)
                if (piece == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        sb.append(emptyCount)
                        emptyCount = 0
                    }
                    sb.append(pieceToChar(piece))
                }
            }
            if (emptyCount > 0) sb.append(emptyCount)
            if (rank > 0) sb.append('/')
        }

        // Turn
        sb.append(if (turn == PieceColor.WHITE) " w " else " b ")

        // Castling rights
        if (castlingRights.isEmpty()) {
            sb.append('-')
        } else {
            if ('K' in castlingRights) sb.append('K')
            if ('Q' in castlingRights) sb.append('Q')
            if ('k' in castlingRights) sb.append('k')
            if ('q' in castlingRights) sb.append('q')
        }

        // En passant
        sb.append(' ')
        sb.append(enPassantSquare?.toAlgebraic() ?: "-")

        // Half-move clock and full move number
        sb.append(" $halfMoveClock $fullMoveNumber")

        return sb.toString()
    }

    /**
     * Set the board position from a FEN string.
     * Returns true if the FEN was valid and successfully applied.
     */
    fun setFen(fen: String): Boolean {
        try {
            val parts = fen.trim().split(" ")
            if (parts.isEmpty()) return false

            // Clear the board first
            for (i in 0..63) board[i] = null

            // Parse board position (first part)
            val ranks = parts[0].split("/")
            if (ranks.size != 8) return false

            for ((rankIndex, rankStr) in ranks.withIndex()) {
                val rank = 7 - rankIndex  // FEN starts from rank 8 (index 7)
                var file = 0
                for (c in rankStr) {
                    if (c.isDigit()) {
                        file += c.digitToInt()
                    } else {
                        val piece = charToPiece(c) ?: return false
                        if (file > 7) return false
                        board[rank * 8 + file] = piece
                        file++
                    }
                }
                if (file != 8) return false
            }

            // Parse turn (second part)
            turn = if (parts.size > 1) {
                when (parts[1].lowercase()) {
                    "w" -> PieceColor.WHITE
                    "b" -> PieceColor.BLACK
                    else -> PieceColor.WHITE
                }
            } else {
                PieceColor.WHITE
            }

            // Parse castling rights (third part)
            castlingRights.clear()
            if (parts.size > 2 && parts[2] != "-") {
                for (c in parts[2]) {
                    if (c in "KQkq") castlingRights.add(c)
                }
            }

            // Parse en passant square (fourth part)
            enPassantSquare = if (parts.size > 3 && parts[3] != "-") {
                Square.fromAlgebraic(parts[3])
            } else {
                null
            }

            // Parse half-move clock (fifth part)
            halfMoveClock = if (parts.size > 4) {
                parts[4].toIntOrNull() ?: 0
            } else {
                0
            }

            // Parse full move number (sixth part)
            fullMoveNumber = if (parts.size > 5) {
                parts[5].toIntOrNull() ?: 1
            } else {
                1
            }

            lastMove = null
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun charToPiece(c: Char): Piece? {
        val color = if (c.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK
        val type = when (c.lowercaseChar()) {
            'k' -> PieceType.KING
            'q' -> PieceType.QUEEN
            'r' -> PieceType.ROOK
            'b' -> PieceType.BISHOP
            'n' -> PieceType.KNIGHT
            'p' -> PieceType.PAWN
            else -> return null
        }
        return Piece(type, color)
    }

    private fun pieceToChar(piece: Piece): Char {
        val c = when (piece.type) {
            PieceType.KING -> 'k'
            PieceType.QUEEN -> 'q'
            PieceType.ROOK -> 'r'
            PieceType.BISHOP -> 'b'
            PieceType.KNIGHT -> 'n'
            PieceType.PAWN -> 'p'
        }
        return if (piece.color == PieceColor.WHITE) c.uppercaseChar() else c
    }

    fun makeMove(san: String): Boolean {
        val move = parseSanMove(san) ?: return false
        return executeMove(move.copy(san = san))
    }

    fun makeUciMove(uci: String): Boolean {
        if (uci.length < 4) return false

        val from = Square.fromAlgebraic(uci.substring(0, 2)) ?: return false
        val to = Square.fromAlgebraic(uci.substring(2, 4)) ?: return false

        val promotion = if (uci.length == 5) {
            when (uci[4].lowercaseChar()) {
                'q' -> PieceType.QUEEN
                'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP
                'n' -> PieceType.KNIGHT
                else -> null
            }
        } else null

        return executeMove(Move(from, to, promotion, uci))
    }

    private fun parseSanMove(san: String): Move? {
        var s = san.replace("+", "").replace("#", "").replace("x", "")

        // Castling
        if (s == "O-O" || s == "0-0") {
            val rank = if (turn == PieceColor.WHITE) 0 else 7
            return Move(Square(4, rank), Square(6, rank))
        }
        if (s == "O-O-O" || s == "0-0-0") {
            val rank = if (turn == PieceColor.WHITE) 0 else 7
            return Move(Square(4, rank), Square(2, rank))
        }

        // Check for promotion
        var promotion: PieceType? = null
        if (s.contains('=')) {
            val parts = s.split('=')
            s = parts[0]
            promotion = when (parts[1].firstOrNull()?.uppercaseChar()) {
                'Q' -> PieceType.QUEEN
                'R' -> PieceType.ROOK
                'B' -> PieceType.BISHOP
                'N' -> PieceType.KNIGHT
                else -> PieceType.QUEEN
            }
        }

        // Determine piece type
        // Piece letters (K, Q, R, B, N) are UPPERCASE in SAN, file letters (a-h) are lowercase
        // So only check for piece type if first char is uppercase
        val firstChar = s.firstOrNull()
        val pieceType = if (firstChar != null && firstChar.isUpperCase()) {
            when (firstChar) {
                'K' -> PieceType.KING
                'Q' -> PieceType.QUEEN
                'R' -> PieceType.ROOK
                'B' -> PieceType.BISHOP
                'N' -> PieceType.KNIGHT
                else -> PieceType.PAWN
            }
        } else {
            PieceType.PAWN
        }

        // For non-pawn pieces, remove the piece letter
        if (pieceType != PieceType.PAWN && s.isNotEmpty() && s[0].isUpperCase()) {
            s = s.substring(1)
        }

        // Get target square (last 2 characters)
        if (s.length < 2) return null
        val targetNotation = s.takeLast(2)
        val to = Square.fromAlgebraic(targetNotation) ?: return null

        // Get disambiguation (everything before target)
        val disambiguation = s.dropLast(2)

        // Find the piece that can make this move
        val from = findPiece(pieceType, to, disambiguation) ?: return null

        return Move(from, to, promotion)
    }

    private fun findPiece(type: PieceType, to: Square, disambiguation: String): Square? {
        val candidates = mutableListOf<Square>()

        for (rank in 0..7) {
            for (file in 0..7) {
                val piece = getPiece(file, rank) ?: continue
                if (piece.color != turn || piece.type != type) continue

                val from = Square(file, rank)
                if (canMove(from, to, piece)) {
                    candidates.add(from)
                }
            }
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]

        // Apply disambiguation
        return candidates.find { square ->
            val fileChar = ('a' + square.file)
            val rankChar = ('1' + square.rank)
            when {
                disambiguation.length == 2 -> square.toAlgebraic() == disambiguation
                disambiguation.length == 1 && disambiguation[0].isDigit() -> disambiguation[0] == rankChar
                disambiguation.length == 1 -> disambiguation[0] == fileChar
                else -> true
            }
        }
    }

    private fun canMove(from: Square, to: Square, piece: Piece): Boolean {
        val df = to.file - from.file
        val dr = to.rank - from.rank
        val adf = kotlin.math.abs(df)
        val adr = kotlin.math.abs(dr)

        // Check target square
        val targetPiece = getPiece(to)
        if (targetPiece != null && targetPiece.color == piece.color) return false

        when (piece.type) {
            PieceType.PAWN -> {
                val direction = if (piece.color == PieceColor.WHITE) 1 else -1
                val startRank = if (piece.color == PieceColor.WHITE) 1 else 6

                // Normal move
                if (df == 0 && dr == direction && targetPiece == null) return true

                // Double move from start
                if (df == 0 && dr == 2 * direction && from.rank == startRank && targetPiece == null) {
                    val middlePiece = getPiece(from.file, from.rank + direction)
                    if (middlePiece == null) return true
                }

                // Capture
                if (adf == 1 && dr == direction) {
                    if (targetPiece != null) return true
                    // En passant
                    if (enPassantSquare == to) return true
                }

                return false
            }

            PieceType.KNIGHT -> return (adf == 2 && adr == 1) || (adf == 1 && adr == 2)

            PieceType.BISHOP -> {
                if (adf != adr) return false
                return isPathClear(from, to)
            }

            PieceType.ROOK -> {
                if (df != 0 && dr != 0) return false
                return isPathClear(from, to)
            }

            PieceType.QUEEN -> {
                if (df != 0 && dr != 0 && adf != adr) return false
                return isPathClear(from, to)
            }

            PieceType.KING -> {
                if (adf <= 1 && adr <= 1) return true
                // Castling is handled separately
                return false
            }
        }
    }

    private fun isPathClear(from: Square, to: Square): Boolean {
        val df = Integer.signum(to.file - from.file)
        val dr = Integer.signum(to.rank - from.rank)

        var file = from.file + df
        var rank = from.rank + dr

        while (file != to.file || rank != to.rank) {
            if (getPiece(file, rank) != null) return false
            file += df
            rank += dr
        }

        return true
    }

    private fun executeMove(move: Move): Boolean {
        val piece = getPiece(move.from) ?: return false

        // Check if this is a capture (must check BEFORE moving the piece)
        val isCapture = getPiece(move.to) != null ||
            (piece.type == PieceType.PAWN && move.to == enPassantSquare)

        // Remove piece from source
        board[move.from.index] = null

        // Handle castling
        if (piece.type == PieceType.KING && kotlin.math.abs(move.to.file - move.from.file) == 2) {
            if (move.to.file == 6) { // Kingside
                board[move.from.rank * 8 + 5] = board[move.from.rank * 8 + 7]
                board[move.from.rank * 8 + 7] = null
            } else { // Queenside
                board[move.from.rank * 8 + 3] = board[move.from.rank * 8 + 0]
                board[move.from.rank * 8 + 0] = null
            }
        }

        // Handle en passant capture
        if (piece.type == PieceType.PAWN && move.to == enPassantSquare) {
            val capturedRank = if (piece.color == PieceColor.WHITE) move.to.rank - 1 else move.to.rank + 1
            board[capturedRank * 8 + move.to.file] = null
        }

        // Handle promotion
        val finalPiece = if (move.promotion != null) {
            Piece(move.promotion, piece.color)
        } else {
            piece
        }

        // Place piece at destination
        board[move.to.index] = finalPiece

        // Update en passant square
        enPassantSquare = if (piece.type == PieceType.PAWN &&
            kotlin.math.abs(move.to.rank - move.from.rank) == 2
        ) {
            Square(move.from.file, (move.from.rank + move.to.rank) / 2)
        } else {
            null
        }

        // Update castling rights
        if (piece.type == PieceType.KING) {
            if (piece.color == PieceColor.WHITE) {
                castlingRights.remove('K')
                castlingRights.remove('Q')
            } else {
                castlingRights.remove('k')
                castlingRights.remove('q')
            }
        }
        // Remove castling rights if rook moves from its starting square
        if (piece.type == PieceType.ROOK) {
            when (move.from) {
                Square(0, 0) -> castlingRights.remove('Q')
                Square(7, 0) -> castlingRights.remove('K')
                Square(0, 7) -> castlingRights.remove('q')
                Square(7, 7) -> castlingRights.remove('k')
            }
        }
        // Also remove castling rights if a piece captures on a rook's starting square
        if (isCapture) {
            when (move.to) {
                Square(0, 0) -> castlingRights.remove('Q')
                Square(7, 0) -> castlingRights.remove('K')
                Square(0, 7) -> castlingRights.remove('q')
                Square(7, 7) -> castlingRights.remove('k')
            }
        }

        // Update counters (reset on pawn move or capture)
        if (piece.type == PieceType.PAWN || isCapture) {
            halfMoveClock = 0
        } else {
            halfMoveClock++
        }

        if (turn == PieceColor.BLACK) {
            fullMoveNumber++
        }

        // Switch turn
        turn = if (turn == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE

        lastMove = move

        return true
    }

    fun copy(): ChessBoard {
        val newBoard = ChessBoard()
        for (i in 0..63) {
            newBoard.board[i] = this.board[i]
        }
        newBoard.turn = this.turn
        newBoard.castlingRights = this.castlingRights.toMutableSet()
        newBoard.enPassantSquare = this.enPassantSquare
        newBoard.halfMoveClock = this.halfMoveClock
        newBoard.fullMoveNumber = this.fullMoveNumber
        newBoard.lastMove = this.lastMove
        return newBoard
    }

    /**
     * Check if a move from one square to another is legal
     */
    fun isLegalMove(from: Square, to: Square): Boolean {
        val piece = getPiece(from) ?: return false
        if (piece.color != turn) return false

        // Check for castling
        if (piece.type == PieceType.KING && kotlin.math.abs(to.file - from.file) == 2) {
            return canCastle(from, to)
        }

        return canMove(from, to, piece)
    }

    /**
     * Check if castling is possible
     */
    private fun canCastle(from: Square, to: Square): Boolean {
        val piece = getPiece(from) ?: return false
        if (piece.type != PieceType.KING) return false

        val isKingside = to.file == 6
        val rank = from.rank

        // Check castling rights
        if (piece.color == PieceColor.WHITE) {
            if (isKingside && 'K' !in castlingRights) return false
            if (!isKingside && 'Q' !in castlingRights) return false
        } else {
            if (isKingside && 'k' !in castlingRights) return false
            if (!isKingside && 'q' !in castlingRights) return false
        }

        // Check path is clear
        if (isKingside) {
            if (getPiece(5, rank) != null || getPiece(6, rank) != null) return false
        } else {
            if (getPiece(1, rank) != null || getPiece(2, rank) != null || getPiece(3, rank) != null) return false
        }

        return true
    }

    /**
     * Get all legal target squares for a piece at the given square
     */
    fun getLegalMoves(from: Square): List<Square> {
        val piece = getPiece(from) ?: return emptyList()
        if (piece.color != turn) return emptyList()

        val legalMoves = mutableListOf<Square>()

        for (rank in 0..7) {
            for (file in 0..7) {
                val to = Square(file, rank)
                if (isLegalMove(from, to)) {
                    legalMoves.add(to)
                }
            }
        }

        // Add castling moves for king
        if (piece.type == PieceType.KING) {
            val rank = from.rank
            if (canCastle(from, Square(6, rank))) {
                legalMoves.add(Square(6, rank))
            }
            if (canCastle(from, Square(2, rank))) {
                legalMoves.add(Square(2, rank))
            }
        }

        return legalMoves
    }

    /**
     * Check if a pawn move requires promotion
     */
    fun needsPromotion(from: Square, to: Square): Boolean {
        val piece = getPiece(from) ?: return false
        if (piece.type != PieceType.PAWN) return false
        return (piece.color == PieceColor.WHITE && to.rank == 7) ||
               (piece.color == PieceColor.BLACK && to.rank == 0)
    }

    /**
     * Make a move given from and to squares, with optional promotion
     */
    fun makeMoveFromSquares(from: Square, to: Square, promotion: PieceType? = null): Boolean {
        if (!isLegalMove(from, to)) return false
        val uci = from.toAlgebraic() + to.toAlgebraic() + (promotion?.let {
            when (it) {
                PieceType.QUEEN -> "q"
                PieceType.ROOK -> "r"
                PieceType.BISHOP -> "b"
                PieceType.KNIGHT -> "n"
                else -> ""
            }
        } ?: "")
        return executeMove(Move(from, to, promotion, uci))
    }
}
