package com.eval.data

/**
 * Chess opening book containing common openings identified by their move sequences.
 * Moves are in UCI format (e.g., "e2e4" for 1.e4).
 */
object OpeningBook {

    /**
     * Map of UCI move sequences to opening names.
     * Longer sequences are more specific openings.
     */
    private val openings: Map<List<String>, String> = mapOf(
        // King's Pawn Openings (1.e4)
        listOf("e2e4") to "King's Pawn Opening",
        listOf("e2e4", "e7e5") to "Open Game",
        listOf("e2e4", "e7e5", "g1f3") to "King's Knight Opening",
        listOf("e2e4", "e7e5", "g1f3", "b8c6") to "Italian Game / Ruy Lopez Setup",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5") to "Ruy Lopez",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6") to "Ruy Lopez: Morphy Defense",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4") to "Ruy Lopez: Morphy Defense",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6") to "Ruy Lopez: Morphy Defense, Closed",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "g8f6") to "Ruy Lopez: Berlin Defense",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "f7f5") to "Ruy Lopez: Schliemann Defense",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4") to "Italian Game",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5") to "Italian Game: Giuoco Piano",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6") to "Italian Game: Two Knights Defense",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "d2d4") to "Scotch Game",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "d2d4", "e5d4") to "Scotch Game",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "b1c3") to "Four Knights Game",
        listOf("e2e4", "e7e5", "g1f3", "b8c6", "b1c3", "g8f6") to "Four Knights Game",
        listOf("e2e4", "e7e5", "g1f3", "g8f6") to "Petrov's Defense",
        listOf("e2e4", "e7e5", "g1f3", "g8f6", "f3e5") to "Petrov's Defense: Classical Attack",
        listOf("e2e4", "e7e5", "g1f3", "d7d6") to "Philidor Defense",
        listOf("e2e4", "e7e5", "f1c4") to "Bishop's Opening",
        listOf("e2e4", "e7e5", "b1c3") to "Vienna Game",
        listOf("e2e4", "e7e5", "f2f4") to "King's Gambit",
        listOf("e2e4", "e7e5", "f2f4", "e5f4") to "King's Gambit Accepted",
        listOf("e2e4", "e7e5", "f2f4", "f8c5") to "King's Gambit Declined",
        listOf("e2e4", "e7e5", "d2d4") to "Center Game",

        // Sicilian Defense (1.e4 c5)
        listOf("e2e4", "c7c5") to "Sicilian Defense",
        listOf("e2e4", "c7c5", "g1f3") to "Sicilian Defense: Open",
        listOf("e2e4", "c7c5", "g1f3", "d7d6") to "Sicilian Defense: Open",
        listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4") to "Sicilian Defense: Open",
        listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3") to "Sicilian Defense: Najdorf Variation",
        listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "a7a6") to "Sicilian Defense: Najdorf Variation",
        listOf("e2e4", "c7c5", "g1f3", "b8c6") to "Sicilian Defense: Old Sicilian",
        listOf("e2e4", "c7c5", "g1f3", "e7e6") to "Sicilian Defense: French Variation",
        listOf("e2e4", "c7c5", "g1f3", "e7e6", "d2d4", "c5d4", "f3d4", "a7a6") to "Sicilian Defense: Paulsen Variation",
        listOf("e2e4", "c7c5", "g1f3", "e7e6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "b8c6") to "Sicilian Defense: Scheveningen Variation",
        listOf("e2e4", "c7c5", "b1c3") to "Sicilian Defense: Closed",
        listOf("e2e4", "c7c5", "c2c3") to "Sicilian Defense: Alapin Variation",
        listOf("e2e4", "c7c5", "d2d4") to "Sicilian Defense: Smith-Morra Gambit",
        listOf("e2e4", "c7c5", "g1f3", "d7d6", "d2d4", "c5d4", "f3d4", "g8f6", "b1c3", "g7g6") to "Sicilian Defense: Dragon Variation",

        // French Defense (1.e4 e6)
        listOf("e2e4", "e7e6") to "French Defense",
        listOf("e2e4", "e7e6", "d2d4") to "French Defense",
        listOf("e2e4", "e7e6", "d2d4", "d7d5") to "French Defense",
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1c3") to "French Defense: Paulsen Variation",
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "g8f6") to "French Defense: Classical Variation",
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1c3", "f8b4") to "French Defense: Winawer Variation",
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "e4e5") to "French Defense: Advance Variation",
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "e4d5") to "French Defense: Exchange Variation",
        listOf("e2e4", "e7e6", "d2d4", "d7d5", "b1d2") to "French Defense: Tarrasch Variation",

        // Caro-Kann Defense (1.e4 c6)
        listOf("e2e4", "c7c6") to "Caro-Kann Defense",
        listOf("e2e4", "c7c6", "d2d4") to "Caro-Kann Defense",
        listOf("e2e4", "c7c6", "d2d4", "d7d5") to "Caro-Kann Defense",
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "b1c3") to "Caro-Kann Defense: Classical Variation",
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "b1c3", "d5e4", "c3e4") to "Caro-Kann Defense: Classical Variation",
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "e4e5") to "Caro-Kann Defense: Advance Variation",
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "e4d5") to "Caro-Kann Defense: Exchange Variation",
        listOf("e2e4", "c7c6", "d2d4", "d7d5", "b1d2") to "Caro-Kann Defense: Two Knights Variation",

        // Pirc Defense (1.e4 d6)
        listOf("e2e4", "d7d6") to "Pirc Defense",
        listOf("e2e4", "d7d6", "d2d4") to "Pirc Defense",
        listOf("e2e4", "d7d6", "d2d4", "g8f6") to "Pirc Defense",
        listOf("e2e4", "d7d6", "d2d4", "g8f6", "b1c3") to "Pirc Defense: Classical Variation",
        listOf("e2e4", "d7d6", "d2d4", "g8f6", "b1c3", "g7g6") to "Pirc Defense: Classical Variation",

        // Scandinavian Defense (1.e4 d5)
        listOf("e2e4", "d7d5") to "Scandinavian Defense",
        listOf("e2e4", "d7d5", "e4d5") to "Scandinavian Defense",
        listOf("e2e4", "d7d5", "e4d5", "d8d5") to "Scandinavian Defense: Mieses-Kotroc Variation",
        listOf("e2e4", "d7d5", "e4d5", "g8f6") to "Scandinavian Defense: Modern Variation",

        // Alekhine's Defense (1.e4 Nf6)
        listOf("e2e4", "g8f6") to "Alekhine's Defense",
        listOf("e2e4", "g8f6", "e4e5") to "Alekhine's Defense",
        listOf("e2e4", "g8f6", "e4e5", "f6d5") to "Alekhine's Defense",

        // Queen's Pawn Openings (1.d4)
        listOf("d2d4") to "Queen's Pawn Opening",
        listOf("d2d4", "d7d5") to "Closed Game",
        listOf("d2d4", "d7d5", "c2c4") to "Queen's Gambit",
        listOf("d2d4", "d7d5", "c2c4", "d5c4") to "Queen's Gambit Accepted",
        listOf("d2d4", "d7d5", "c2c4", "e7e6") to "Queen's Gambit Declined",
        listOf("d2d4", "d7d5", "c2c4", "e7e6", "b1c3") to "Queen's Gambit Declined",
        listOf("d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6") to "Queen's Gambit Declined",
        listOf("d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6", "c1g5") to "Queen's Gambit Declined: Orthodox Defense",
        listOf("d2d4", "d7d5", "c2c4", "c7c6") to "Slav Defense",
        listOf("d2d4", "d7d5", "c2c4", "c7c6", "g1f3") to "Slav Defense",
        listOf("d2d4", "d7d5", "c2c4", "c7c6", "g1f3", "g8f6") to "Slav Defense",
        listOf("d2d4", "d7d5", "c2c4", "c7c6", "g1f3", "g8f6", "b1c3") to "Slav Defense: Main Line",
        listOf("d2d4", "d7d5", "g1f3") to "Queen's Pawn Game",
        listOf("d2d4", "d7d5", "c1f4") to "London System",

        // Indian Defenses (1.d4 Nf6)
        listOf("d2d4", "g8f6") to "Indian Game",
        listOf("d2d4", "g8f6", "c2c4") to "Indian Game",
        listOf("d2d4", "g8f6", "c2c4", "e7e6") to "Indian Game",
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "g1f3") to "Indian Game",
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "g1f3", "b7b6") to "Queen's Indian Defense",
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "b1c3") to "Nimzo-Indian Setup",
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "b1c3", "f8b4") to "Nimzo-Indian Defense",
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "b1c3", "f8b4", "e2e3") to "Nimzo-Indian Defense: Rubinstein Variation",
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "b1c3", "f8b4", "d1c2") to "Nimzo-Indian Defense: Classical Variation",
        listOf("d2d4", "g8f6", "c2c4", "g7g6") to "King's Indian Defense",
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3") to "King's Indian Defense",
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7") to "King's Indian Defense",
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7", "e2e4") to "King's Indian Defense: Classical Variation",
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "f8g7", "e2e4", "d7d6") to "King's Indian Defense: Classical Variation",
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "d7d5") to "Grunfeld Defense",
        listOf("d2d4", "g8f6", "c2c4", "g7g6", "b1c3", "d7d5", "c4d5") to "Grunfeld Defense: Exchange Variation",
        listOf("d2d4", "g8f6", "c2c4", "c7c5") to "Benoni Defense",
        listOf("d2d4", "g8f6", "c2c4", "c7c5", "d4d5") to "Modern Benoni",
        listOf("d2d4", "g8f6", "c2c4", "c7c5", "d4d5", "e7e6") to "Modern Benoni",
        listOf("d2d4", "g8f6", "g1f3") to "Indian Game",
        listOf("d2d4", "g8f6", "c1f4") to "London System",
        listOf("d2d4", "g8f6", "c1g5") to "Trompowsky Attack",

        // Dutch Defense (1.d4 f5)
        listOf("d2d4", "f7f5") to "Dutch Defense",
        listOf("d2d4", "f7f5", "c2c4") to "Dutch Defense",
        listOf("d2d4", "f7f5", "g2g3") to "Dutch Defense: Leningrad Variation",

        // English Opening (1.c4)
        listOf("c2c4") to "English Opening",
        listOf("c2c4", "e7e5") to "English Opening: Reversed Sicilian",
        listOf("c2c4", "g8f6") to "English Opening: Anglo-Indian Defense",
        listOf("c2c4", "c7c5") to "English Opening: Symmetrical Variation",
        listOf("c2c4", "e7e6") to "English Opening",
        listOf("c2c4", "g7g6") to "English Opening: King's Indian Setup",

        // Reti Opening (1.Nf3)
        listOf("g1f3") to "Reti Opening",
        listOf("g1f3", "d7d5") to "Reti Opening",
        listOf("g1f3", "d7d5", "c2c4") to "Reti Opening",
        listOf("g1f3", "g8f6") to "Reti Opening: King's Indian Attack Setup",

        // Catalan Opening
        listOf("d2d4", "g8f6", "c2c4", "e7e6", "g2g3") to "Catalan Opening",
        listOf("d2d4", "d7d5", "c2c4", "e7e6", "g1f3", "g8f6", "g2g3") to "Catalan Opening",

        // Bird's Opening (1.f4)
        listOf("f2f4") to "Bird's Opening",
        listOf("f2f4", "d7d5") to "Bird's Opening",

        // King's Fianchetto Opening (1.g3)
        listOf("g2g3") to "King's Fianchetto Opening",

        // Larsen's Opening (1.b3)
        listOf("b2b3") to "Larsen's Opening",

        // Grob's Attack (1.g4)
        listOf("g2g4") to "Grob's Attack",

        // Polish Opening (1.b4)
        listOf("b2b4") to "Polish Opening",

        // Uncommon Black responses
        listOf("e2e4", "g7g6") to "Modern Defense",
        listOf("e2e4", "b7b6") to "Owen's Defense",
        listOf("e2e4", "b8c6") to "Nimzowitsch Defense",
        listOf("d2d4", "e7e6") to "Horwitz Defense",
        listOf("d2d4", "b7b6") to "English Defense"
    )

    /**
     * Get the opening name for a sequence of moves.
     * Finds the longest matching opening in the book.
     *
     * @param moves List of UCI moves from the start of the game
     * @param upToIndex Find opening up to this move index (inclusive). Use -1 for starting position.
     * @return Opening name if found, null otherwise
     */
    fun getOpeningName(moves: List<String>, upToIndex: Int = moves.size - 1): String? {
        if (upToIndex < 0 || moves.isEmpty()) return null

        val sequence = moves.take(upToIndex + 1)
        var bestMatch: String? = null
        var bestMatchLength = 0

        for ((moveSeq, name) in openings) {
            if (moveSeq.size <= sequence.size && moveSeq.size > bestMatchLength) {
                // Check if the opening moves match the start of the sequence
                var matches = true
                for (i in moveSeq.indices) {
                    if (moveSeq[i] != sequence[i]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    bestMatch = name
                    bestMatchLength = moveSeq.size
                }
            }
        }

        return bestMatch
    }

    /**
     * Get the opening name from a list of moves, considering all moves played.
     */
    fun getOpeningName(moves: List<String>): String? {
        return getOpeningName(moves, moves.size - 1)
    }
}
