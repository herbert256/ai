package com.eval.ui

import com.eval.data.BroadcastInfo
import com.eval.data.BroadcastRoundInfo
import com.eval.data.ChessRepository
import com.eval.data.ChessServer
import com.eval.data.LichessGame
import com.eval.data.PlayerInfo
import com.eval.data.Result
import com.eval.data.StreamerInfo
import com.eval.data.TournamentInfo
import com.eval.data.TvChannelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages browsing external content sources like tournaments, broadcasts, TV, etc.
 */
internal class ContentSourceManager(
    private val repository: ChessRepository,
    private val getUiState: () -> GameUiState,
    private val updateUiState: (GameUiState.() -> GameUiState) -> Unit,
    private val viewModelScope: CoroutineScope,
    private val loadGame: (LichessGame, ChessServer?, String?) -> Unit
) {
    // ==================== TOURNAMENTS ====================

    fun showTournaments(server: ChessServer) {
        updateUiState {
            copy(
                showTournamentsScreen = true,
                tournamentsServer = server,
                tournamentsLoading = true,
                tournamentsError = null,
                tournamentsList = emptyList(),
                selectedTournament = null,
                tournamentGames = emptyList()
            )
        }

        viewModelScope.launch {
            if (server == ChessServer.LICHESS) {
                when (val result = repository.getLichessTournaments()) {
                    is Result.Success -> {
                        updateUiState {
                            copy(
                                tournamentsLoading = false,
                                tournamentsList = result.data
                            )
                        }
                    }
                    is Result.Error -> {
                        updateUiState {
                            copy(
                                tournamentsLoading = false,
                                tournamentsError = result.message
                            )
                        }
                    }
                }
            } else {
                updateUiState {
                    copy(
                        tournamentsLoading = false,
                        tournamentsError = "Chess.com tournaments not yet supported"
                    )
                }
            }
        }
    }

    fun selectTournament(tournament: TournamentInfo) {
        updateUiState {
            copy(
                selectedTournament = tournament,
                tournamentGamesLoading = true,
                tournamentGames = emptyList()
            )
        }

        viewModelScope.launch {
            when (val result = repository.getLichessTournamentGames(tournament.id)) {
                is Result.Success -> {
                    updateUiState {
                        copy(
                            tournamentGamesLoading = false,
                            tournamentGames = result.data
                        )
                    }
                }
                is Result.Error -> {
                    updateUiState {
                        copy(
                            tournamentGamesLoading = false,
                            tournamentsError = result.message
                        )
                    }
                }
            }
        }
    }

    fun backToTournamentList() {
        updateUiState {
            copy(
                selectedTournament = null,
                tournamentGames = emptyList()
            )
        }
    }

    fun dismissTournaments() {
        updateUiState {
            copy(
                showTournamentsScreen = false,
                tournamentsList = emptyList(),
                selectedTournament = null,
                tournamentGames = emptyList(),
                tournamentsError = null
            )
        }
    }

    fun selectTournamentGame(game: LichessGame) {
        val server = getUiState().tournamentsServer
        dismissTournaments()
        val whiteName = game.players.white.user?.name ?: "White"
        loadGame(game, server, whiteName)
    }

    // ==================== BROADCASTS ====================

    fun showBroadcasts() {
        updateUiState {
            copy(
                showBroadcastsScreen = true,
                broadcastsLoading = true,
                broadcastsError = null,
                broadcastsList = emptyList(),
                selectedBroadcast = null,
                broadcastGames = emptyList()
            )
        }

        viewModelScope.launch {
            when (val result = repository.getLichessBroadcasts()) {
                is Result.Success -> {
                    updateUiState {
                        copy(
                            broadcastsLoading = false,
                            broadcastsList = result.data
                        )
                    }
                }
                is Result.Error -> {
                    updateUiState {
                        copy(
                            broadcastsLoading = false,
                            broadcastsError = result.message
                        )
                    }
                }
            }
        }
    }

    fun selectBroadcast(broadcast: BroadcastInfo) {
        if (broadcast.rounds.isEmpty()) {
            updateUiState {
                copy(broadcastsError = "No rounds available for this broadcast")
            }
            return
        }

        if (broadcast.rounds.size == 1) {
            updateUiState { copy(selectedBroadcast = broadcast) }
            selectBroadcastRound(broadcast.rounds.first())
            return
        }

        updateUiState {
            copy(
                selectedBroadcast = broadcast,
                selectedBroadcastRound = null,
                broadcastGames = emptyList()
            )
        }
    }

    fun selectBroadcastRound(round: BroadcastRoundInfo) {
        val broadcast = getUiState().selectedBroadcast ?: return

        updateUiState {
            copy(
                selectedBroadcastRound = round,
                broadcastGamesLoading = true,
                broadcastGames = emptyList()
            )
        }

        viewModelScope.launch {
            when (val result = repository.getLichessBroadcastGames(broadcast.id, round.id)) {
                is Result.Success -> {
                    updateUiState {
                        copy(
                            broadcastGamesLoading = false,
                            broadcastGames = result.data
                        )
                    }
                }
                is Result.Error -> {
                    updateUiState {
                        copy(
                            broadcastGamesLoading = false,
                            broadcastsError = result.message
                        )
                    }
                }
            }
        }
    }

    fun backToBroadcastList() {
        val state = getUiState()

        if (state.selectedBroadcastRound != null) {
            val broadcast = state.selectedBroadcast
            if (broadcast != null && broadcast.rounds.size > 1) {
                updateUiState {
                    copy(
                        selectedBroadcastRound = null,
                        broadcastGames = emptyList()
                    )
                }
                return
            }
        }

        updateUiState {
            copy(
                selectedBroadcast = null,
                selectedBroadcastRound = null,
                broadcastGames = emptyList()
            )
        }
    }

    fun dismissBroadcasts() {
        updateUiState {
            copy(
                showBroadcastsScreen = false,
                broadcastsList = emptyList(),
                selectedBroadcast = null,
                selectedBroadcastRound = null,
                broadcastGames = emptyList(),
                broadcastsError = null
            )
        }
    }

    fun selectBroadcastGame(game: LichessGame) {
        dismissBroadcasts()
        val whiteName = game.players.white.user?.name ?: "White"
        loadGame(game, null, whiteName)  // No server info for broadcasts
    }

    // ==================== LICHESS TV ====================

    fun showLichessTv() {
        updateUiState {
            copy(
                showTvScreen = true,
                tvLoading = true,
                tvError = null,
                tvChannels = emptyList()
            )
        }

        viewModelScope.launch {
            try {
                when (val result = repository.getLichessTvChannels()) {
                    is Result.Success -> {
                        updateUiState {
                            copy(
                                tvLoading = false,
                                tvChannels = result.data
                            )
                        }
                    }
                    is Result.Error -> {
                        updateUiState {
                            copy(
                                tvLoading = false,
                                tvError = result.message
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                updateUiState {
                    copy(
                        tvLoading = false,
                        tvError = "Exception: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectTvGame(channel: TvChannelInfo) {
        updateUiState { copy(tvLoading = true) }

        viewModelScope.launch {
            when (val result = repository.getLichessGame(channel.gameId)) {
                is Result.Success -> {
                    val game = result.data
                    if (game.pgn == null) {
                        when (val streamResult = repository.streamLichessGame(channel.gameId)) {
                            is Result.Success -> {
                                dismissLichessTv()
                                val streamedGame = streamResult.data
                                val whiteName = streamedGame.players.white.user?.name ?: "White"
                                loadGame(streamedGame, null, whiteName)  // No server info for TV
                            }
                            is Result.Error -> {
                                updateUiState {
                                    copy(
                                        tvLoading = false,
                                        tvError = "Live game: ${streamResult.message}"
                                    )
                                }
                            }
                        }
                        return@launch
                    }
                    dismissLichessTv()
                    val whiteName = game.players.white.user?.name ?: "White"
                    loadGame(game, null, whiteName)  // No server info for TV
                }
                is Result.Error -> {
                    updateUiState {
                        copy(
                            tvLoading = false,
                            tvError = result.message
                        )
                    }
                }
            }
        }
    }

    fun dismissLichessTv() {
        updateUiState {
            copy(
                showTvScreen = false,
                tvLoading = false,
                tvChannels = emptyList(),
                tvError = null
            )
        }
    }

    // ==================== CHESS.COM DAILY PUZZLE ====================

    fun showDailyPuzzle() {
        updateUiState {
            copy(
                showDailyPuzzleScreen = true,
                dailyPuzzleLoading = false,
                dailyPuzzle = null,
                errorMessage = "Daily puzzle feature is not available"
            )
        }
    }

    fun dismissDailyPuzzle() {
        updateUiState {
            copy(
                showDailyPuzzleScreen = false,
                dailyPuzzle = null
            )
        }
    }

    // ==================== LICHESS STREAMERS ====================

    fun showStreamers() {
        updateUiState {
            copy(
                showStreamersScreen = true,
                streamersLoading = true,
                streamersList = emptyList()
            )
        }

        viewModelScope.launch {
            when (val result = repository.getLichessStreamers()) {
                is Result.Success -> {
                    updateUiState {
                        copy(
                            streamersLoading = false,
                            streamersList = result.data
                        )
                    }
                }
                is Result.Error -> {
                    updateUiState {
                        copy(
                            streamersLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun selectStreamer(streamer: StreamerInfo, showPlayerInfo: (String, ChessServer) -> Unit) {
        dismissStreamers()
        showPlayerInfo(streamer.username, ChessServer.LICHESS)
    }

    fun dismissStreamers() {
        updateUiState {
            copy(
                showStreamersScreen = false,
                streamersList = emptyList()
            )
        }
    }

    // ==================== PLAYER INFO & RANKINGS ====================

    fun showPlayerInfo(username: String, server: ChessServer?) {
        // Always use Lichess as default server
        showPlayerInfoWithServer(username, server ?: ChessServer.LICHESS)
    }

    fun showPlayerInfoWithServer(username: String, server: ChessServer) {
        updateUiState {
            copy(
                showPlayerInfoScreen = true,
                playerInfoLoading = true,
                playerInfo = null,
                playerInfoError = null,
                playerGames = emptyList(),
                playerGamesLoading = true,
                playerGamesPage = 0,
                playerGamesHasMore = true
            )
        }

        viewModelScope.launch {
            val result = repository.getPlayerInfo(username, server)
            when (result) {
                is Result.Success -> {
                    updateUiState {
                        copy(
                            playerInfoLoading = false,
                            playerInfo = result.data,
                            playerInfoError = null
                        )
                    }
                    fetchPlayerGames(username, server, 10)
                }
                is Result.Error -> {
                    // Show screen with minimal player info (just username)
                    val minimalPlayerInfo = PlayerInfo(
                        username = username,
                        server = server,
                        title = null,
                        name = null,
                        country = null,
                        location = null,
                        bio = null,
                        online = null,
                        createdAt = null,
                        lastOnline = null,
                        profileUrl = null,
                        bulletRating = null,
                        blitzRating = null,
                        rapidRating = null,
                        classicalRating = null,
                        dailyRating = null,
                        totalGames = null,
                        wins = null,
                        losses = null,
                        draws = null,
                        playTimeSeconds = null,
                        followers = null,
                        isStreamer = null
                    )
                    updateUiState {
                        copy(
                            showPlayerInfoScreen = true,
                            playerInfoLoading = false,
                            playerInfo = minimalPlayerInfo,
                            playerInfoError = "Profile not found on ${server.name.replace("_", ".")}",
                            playerGamesLoading = false,
                            playerGames = emptyList(),
                            playerGamesHasMore = false
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchPlayerGames(username: String, server: ChessServer, count: Int) {
        val gamesResult = repository.getLichessGames(username, count)
        when (gamesResult) {
            is Result.Success -> {
                val fetchedGames = gamesResult.data
                updateUiState {
                    copy(
                        playerGames = fetchedGames,
                        playerGamesLoading = false,
                        playerGamesHasMore = fetchedGames.size >= count
                    )
                }
            }
            is Result.Error -> {
                updateUiState {
                    copy(
                        playerGames = emptyList(),
                        playerGamesLoading = false,
                        playerGamesHasMore = false
                    )
                }
            }
        }
    }

    fun nextPlayerGamesPage() {
        val state = getUiState()
        val currentPage = state.playerGamesPage
        val pageSize = state.playerGamesPageSize
        val currentGames = state.playerGames
        val hasMore = state.playerGamesHasMore
        val playerInfo = state.playerInfo ?: return

        val nextPageStartIndex = (currentPage + 1) * pageSize

        if (nextPageStartIndex >= currentGames.size && hasMore) {
            updateUiState { copy(playerGamesLoading = true) }

            viewModelScope.launch {
                val newCount = currentGames.size + pageSize
                val gamesResult = repository.getLichessGames(playerInfo.username, newCount)
                when (gamesResult) {
                    is Result.Success -> {
                        val fetchedGames = gamesResult.data
                        val gotMoreGames = fetchedGames.size > currentGames.size
                        updateUiState {
                            copy(
                                playerGames = fetchedGames,
                                playerGamesLoading = false,
                                playerGamesPage = if (gotMoreGames) currentPage + 1 else currentPage,
                                playerGamesHasMore = fetchedGames.size >= newCount
                            )
                        }
                    }
                    is Result.Error -> {
                        updateUiState {
                            copy(
                                playerGamesLoading = false,
                                playerGamesHasMore = false
                            )
                        }
                    }
                }
            }
        } else if (nextPageStartIndex < currentGames.size) {
            updateUiState { copy(playerGamesPage = currentPage + 1) }
        }
    }

    fun previousPlayerGamesPage() {
        val currentPage = getUiState().playerGamesPage
        if (currentPage > 0) {
            updateUiState { copy(playerGamesPage = currentPage - 1) }
        }
    }

    fun selectGameFromPlayerInfo(game: LichessGame) {
        val playerInfo = getUiState().playerInfo ?: return
        val server = playerInfo.server

        updateUiState {
            copy(
                showPlayerInfoScreen = false,
                playerInfo = null,
                playerGames = emptyList(),
                playerGamesPage = 0,
                playerGamesHasMore = true
            )
        }

        loadGame(game, server, playerInfo.username)
    }

    fun dismissPlayerInfo() {
        updateUiState {
            copy(
                showPlayerInfoScreen = false,
                playerInfo = null,
                playerInfoLoading = false,
                playerInfoError = null
            )
        }
    }


    // ==================== TOP RANKINGS ====================

    fun showTopRankings(server: ChessServer) {
        updateUiState {
            copy(
                showTopRankingsScreen = true,
                topRankingsServer = server,
                topRankingsLoading = true,
                topRankingsError = null,
                topRankings = emptyMap()
            )
        }

        viewModelScope.launch {
            val result = repository.getLichessLeaderboard()

            when (result) {
                is Result.Success -> {
                    updateUiState {
                        copy(
                            topRankingsLoading = false,
                            topRankings = result.data,
                            topRankingsError = null
                        )
                    }
                }
                is Result.Error -> {
                    updateUiState {
                        copy(
                            topRankingsLoading = false,
                            topRankingsError = result.message
                        )
                    }
                }
            }
        }
    }

    fun dismissTopRankings() {
        updateUiState {
            copy(
                showTopRankingsScreen = false,
                topRankings = emptyMap(),
                topRankingsLoading = false,
                topRankingsError = null
            )
        }
    }

    fun selectTopRankingPlayer(username: String, server: ChessServer) {
        updateUiState {
            copy(
                showTopRankingsScreen = false,
                topRankings = emptyMap()
            )
        }
        showPlayerInfoWithServer(username, server)
    }
}
