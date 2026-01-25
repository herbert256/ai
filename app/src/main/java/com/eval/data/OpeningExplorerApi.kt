package com.eval.data

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Data classes for Lichess Opening Explorer API responses.
 */
data class OpeningExplorerResponse(
    val white: Int,
    val draws: Int,
    val black: Int,
    val moves: List<OpeningExplorerMove>,
    val topGames: List<TopGame>? = null,
    val opening: OpeningInfo? = null
)

data class OpeningExplorerMove(
    val uci: String,
    val san: String,
    val white: Int,
    val draws: Int,
    val black: Int,
    val averageRating: Int? = null
)

data class TopGame(
    val id: String,
    val white: PlayerRef,
    val black: PlayerRef,
    val winner: String?,
    val year: Int
)

data class PlayerRef(
    val name: String,
    val rating: Int
)

data class OpeningInfo(
    val eco: String,
    val name: String
)

/**
 * Retrofit interface for Lichess Opening Explorer API.
 */
interface OpeningExplorerApi {
    @GET("lichess")
    suspend fun getLichessOpeningExplorer(
        @Query("fen") fen: String,
        @Query("speeds") speeds: String = "bullet,blitz,rapid,classical",
        @Query("ratings") ratings: String = "1600,1800,2000,2200,2500"
    ): Response<OpeningExplorerResponse>

    @GET("masters")
    suspend fun getMastersOpeningExplorer(
        @Query("fen") fen: String
    ): Response<OpeningExplorerResponse>
}
