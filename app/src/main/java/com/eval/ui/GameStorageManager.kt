package com.eval.ui

import android.content.SharedPreferences
import com.eval.data.ChessServer
import com.eval.data.LichessGame
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Helper class for managing game storage operations via SharedPreferences.
 * Handles storing and loading retrieved games lists and analysed games.
 */
class GameStorageManager(
    private val prefs: SharedPreferences,
    private val gson: Gson
) {

    // ============================================================================
    // Current Analysed Game Storage
    // ============================================================================

    /**
     * Save the current analysed game to SharedPreferences as JSON.
     */
    fun saveCurrentAnalysedGame(analysedGame: AnalysedGame) {
        val json = gson.toJson(analysedGame)
        prefs.edit().putString(SettingsPreferences.KEY_CURRENT_GAME_JSON, json).apply()
    }

    /**
     * Load the current analysed game from SharedPreferences.
     * Returns null if no game is stored.
     */
    fun loadCurrentAnalysedGame(): AnalysedGame? {
        val json = prefs.getString(SettingsPreferences.KEY_CURRENT_GAME_JSON, null)
        if (json == null) {
            android.util.Log.d("GameStorageManager", "loadCurrentAnalysedGame: No JSON stored")
            return null
        }
        return try {
            val game = gson.fromJson(json, AnalysedGame::class.java)
            android.util.Log.d("GameStorageManager", "loadCurrentAnalysedGame: Loaded game ${game?.whiteName} vs ${game?.blackName}")
            game
        } catch (e: Exception) {
            android.util.Log.e("GameStorageManager", "loadCurrentAnalysedGame: Failed to parse JSON", e)
            null
        }
    }

    // ============================================================================
    // Retrieved Games Storage (List of Lists)
    // ============================================================================

    /**
     * Generate the SharedPreferences key for a specific retrieve entry.
     */
    fun getRetrievedGamesKey(accountName: String, server: ChessServer): String {
        val serverPrefix = "lichess"
        return "${SettingsPreferences.KEY_RETRIEVED_GAMES_PREFIX}${serverPrefix}_${accountName.lowercase()}"
    }

    /**
     * Store retrieved games for a specific account.
     */
    fun storeRetrievedGames(games: List<LichessGame>, username: String, server: ChessServer) {
        // Load existing retrieves list
        val retrievesList = loadRetrievesList().toMutableList()

        // Create new entry
        val newEntry = RetrievedGamesEntry(accountName = username, server = server)

        // Remove any existing entry with same account/server
        retrievesList.removeAll { it.accountName.equals(username, ignoreCase = true) && it.server == server }

        // Add new entry at the beginning
        retrievesList.add(0, newEntry)

        // Trim to max size
        while (retrievesList.size > SettingsPreferences.MAX_RETRIEVES) {
            val removed = retrievesList.removeAt(retrievesList.size - 1)
            // Also remove the stored games for that entry
            val key = getRetrievedGamesKey(removed.accountName, removed.server)
            prefs.edit().remove(key).apply()
        }

        // Save the retrieves list
        val retrievesJson = gson.toJson(retrievesList)
        prefs.edit().putString(SettingsPreferences.KEY_RETRIEVES_LIST, retrievesJson).apply()

        // Save the games for this entry
        val gamesKey = getRetrievedGamesKey(username, server)
        val gamesJson = gson.toJson(games)
        prefs.edit().putString(gamesKey, gamesJson).apply()
    }

    /**
     * Load the list of previous retrieves.
     */
    fun loadRetrievesList(): List<RetrievedGamesEntry> {
        val json = prefs.getString(SettingsPreferences.KEY_RETRIEVES_LIST, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<RetrievedGamesEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Load games for a specific retrieve entry.
     */
    fun loadGamesForRetrieve(entry: RetrievedGamesEntry): List<LichessGame> {
        val key = getRetrievedGamesKey(entry.accountName, entry.server)
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LichessGame>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================================================================
    // Analysed Games Storage
    // ============================================================================

    /**
     * Store an analysed game, maintaining a list of up to MAX_ANALYSED_GAMES.
     */
    fun storeAnalysedGame(
        game: LichessGame,
        moves: List<String>,
        moveDetails: List<MoveDetails>,
        previewScores: Map<Int, MoveScore>,
        analyseScores: Map<Int, MoveScore>,
        openingName: String?
    ) {
        // Load existing analysed games
        val analysedGames = loadAnalysedGames().toMutableList()

        // Create new analysed game entry
        val newGame = AnalysedGame(
            timestamp = System.currentTimeMillis(),
            whiteName = game.players.white.user?.name
                ?: game.players.white.aiLevel?.let { "Stockfish $it" }
                ?: "Anonymous",
            blackName = game.players.black.user?.name
                ?: game.players.black.aiLevel?.let { "Stockfish $it" }
                ?: "Anonymous",
            result = when {
                game.winner == "white" -> "1-0"
                game.winner == "black" -> "0-1"
                game.status == "draw" || game.status == "stalemate" -> "1/2-1/2"
                // For ongoing games (status "*", "started", etc.), keep the result as ongoing
                game.status == "*" || game.status == "started" || game.status == "unknown" -> "*"
                else -> "*"
            },
            pgn = game.pgn ?: "",
            moves = moves,
            moveDetails = moveDetails,
            previewScores = previewScores,
            analyseScores = analyseScores,
            openingName = openingName,
            speed = game.speed
        )

        // Remove any duplicate (same white, black, and PGN)
        analysedGames.removeAll { existing ->
            existing.whiteName == newGame.whiteName &&
                    existing.blackName == newGame.blackName &&
                    existing.pgn == newGame.pgn
        }

        // Add new game at the beginning
        analysedGames.add(0, newGame)

        // Trim to max size
        while (analysedGames.size > SettingsPreferences.MAX_ANALYSED_GAMES) {
            analysedGames.removeAt(analysedGames.size - 1)
        }

        // Save the list
        val json = gson.toJson(analysedGames)
        prefs.edit().putString(SettingsPreferences.KEY_ANALYSED_GAMES, json).apply()

        // Also save as the current game for next app startup
        saveCurrentAnalysedGame(newGame)
    }

    /**
     * Load the list of analysed games.
     */
    fun loadAnalysedGames(): List<AnalysedGame> {
        val json = prefs.getString(SettingsPreferences.KEY_ANALYSED_GAMES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AnalysedGame>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Check if there are any stored analysed games.
     */
    fun hasAnalysedGames(): Boolean {
        return prefs.getString(SettingsPreferences.KEY_ANALYSED_GAMES, null) != null
    }
}
