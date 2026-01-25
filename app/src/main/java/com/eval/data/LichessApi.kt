package com.eval.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

/**
 * Lichess user profile data
 */
data class LichessUserProfile(
    val id: String,
    val username: String,
    val title: String?,
    val online: Boolean?,
    val playing: String?,
    val streaming: Boolean?,
    val createdAt: Long?,
    val seenAt: Long?,
    val profile: LichessProfileInfo?,
    val perfs: Map<String, LichessPerf>?,
    val count: LichessCount?,
    val playTime: LichessPlayTime?,
    val url: String?
)

data class LichessProfileInfo(
    val country: String?,
    val location: String?,
    val bio: String?,
    val firstName: String?,
    val lastName: String?,
    val fideRating: Int?,
    val uscfRating: Int?,
    val ecfRating: Int?,
    val links: String?
)

data class LichessPerf(
    val games: Int?,
    val rating: Int?,
    val rd: Int?,
    val prog: Int?,
    val prov: Boolean?
)

data class LichessCount(
    val all: Int?,
    val rated: Int?,
    val ai: Int?,
    val draw: Int?,
    val drawH: Int?,
    val loss: Int?,
    val lossH: Int?,
    val win: Int?,
    val winH: Int?,
    val bookmark: Int?,
    val playing: Int?,
    val import: Int?,
    val me: Int?
)

data class LichessPlayTime(
    val total: Long?,
    val tv: Long?
)

/**
 * Lichess leaderboard data
 */
data class LichessLeaderboard(
    val bullet: List<LichessLeaderboardPlayer>?,
    val blitz: List<LichessLeaderboardPlayer>?,
    val rapid: List<LichessLeaderboardPlayer>?,
    val classical: List<LichessLeaderboardPlayer>?,
    val ultraBullet: List<LichessLeaderboardPlayer>?,
    val chess960: List<LichessLeaderboardPlayer>?,
    val crazyhouse: List<LichessLeaderboardPlayer>?,
    val antichess: List<LichessLeaderboardPlayer>?,
    val atomic: List<LichessLeaderboardPlayer>?,
    val horde: List<LichessLeaderboardPlayer>?,
    val kingOfTheHill: List<LichessLeaderboardPlayer>?,
    val racingKings: List<LichessLeaderboardPlayer>?,
    val threeCheck: List<LichessLeaderboardPlayer>?
)

data class LichessLeaderboardPlayer(
    val id: String,
    val username: String,
    val title: String?,
    val online: Boolean?,
    val perfs: Map<String, LichessPerf>?
)

/**
 * Lichess tournament data
 */
data class LichessTournament(
    val id: String,
    val fullName: String?,
    val name: String?,
    val createdBy: String?,
    val clock: LichessTournamentClock?,
    val minutes: Int?,
    val nbPlayers: Int?,
    val status: Int?,  // 10=created, 20=started, 30=finished
    val startsAt: Long?,
    val finishesAt: Long?,
    val variant: LichessVariant?,
    val rated: Boolean?,
    val perf: LichessTournamentPerf?,
    val secondsToStart: Int?,
    val secondsToFinish: Int?
)

data class LichessTournamentClock(
    val limit: Int?,
    val increment: Int?
)

data class LichessVariant(
    val key: String?,
    val short: String?,
    val name: String?
)

data class LichessTournamentPerf(
    val key: String?,
    val name: String?,
    val icon: String?
)

data class LichessTournamentList(
    val created: List<LichessTournament>?,
    val started: List<LichessTournament>?,
    val finished: List<LichessTournament>?
)

/**
 * Lichess broadcast data
 */
data class LichessBroadcast(
    val tour: LichessBroadcastTour?,
    val rounds: List<LichessBroadcastRound>?
)

data class LichessBroadcastTour(
    val id: String?,
    val name: String?,
    val slug: String?,
    val description: String?,
    val createdAt: Long?,
    val tier: Int?
)

data class LichessBroadcastRound(
    val id: String?,
    val name: String?,
    val slug: String?,
    val createdAt: Long?,
    val startsAt: Long?,
    val ongoing: Boolean?,
    val finished: Boolean?,
    val url: String?
)

data class LichessBroadcastPage(
    val currentPage: Int?,
    val maxPerPage: Int?,
    val currentPageResults: List<LichessBroadcast>?,
    val nbResults: Int?,
    val previousPage: Int?,
    val nextPage: Int?,
    val nbPages: Int?
)

/**
 * Lichess TV channels
 */
data class LichessTvChannels(
    val bot: LichessTvGame?,
    val blitz: LichessTvGame?,
    val racingKings: LichessTvGame?,
    val ultraBullet: LichessTvGame?,
    val bullet: LichessTvGame?,
    val classical: LichessTvGame?,
    val threeCheck: LichessTvGame?,
    val antichess: LichessTvGame?,
    val computer: LichessTvGame?,
    val horde: LichessTvGame?,
    val rapid: LichessTvGame?,
    val atomic: LichessTvGame?,
    val crazyhouse: LichessTvGame?,
    val chess960: LichessTvGame?,
    val kingOfTheHill: LichessTvGame?,
    val best: LichessTvGame?  // "best" is the top rated game
)

data class LichessTvGame(
    val user: LichessTvUser?,
    val rating: Int?,
    val gameId: String?
)

data class LichessTvUser(
    val id: String?,
    val name: String?,
    val title: String?
)

interface LichessApi {
    @GET("api/user/{username}")
    suspend fun getUser(
        @Path("username") username: String
    ): Response<LichessUserProfile>

    @GET("api/games/user/{username}")
    @Headers("Accept: application/x-ndjson")
    suspend fun getGames(
        @Path("username") username: String,
        @Query("max") max: Int = 10,
        @Query("pgnInJson") pgnInJson: Boolean = true,
        @Query("clocks") clocks: Boolean = true
    ): Response<String>

    @GET("api/player")
    suspend fun getLeaderboard(): Response<LichessLeaderboard>

    // Tournament endpoints
    @GET("api/tournament")
    suspend fun getTournaments(): Response<LichessTournamentList>

    @GET("api/tournament/{id}/games")
    @Headers("Accept: application/x-ndjson")
    suspend fun getTournamentGames(
        @Path("id") tournamentId: String,
        @Query("max") max: Int = 50,
        @Query("pgnInJson") pgnInJson: Boolean = true,
        @Query("clocks") clocks: Boolean = true
    ): Response<String>

    // Broadcast endpoints
    @GET("api/broadcast?nb=20")
    @Headers("Accept: application/x-ndjson")
    suspend fun getBroadcasts(): Response<String>

    @GET("api/broadcast/round/{broadcastRoundId}.pgn")
    suspend fun getBroadcastRoundPgn(
        @Path("broadcastRoundId") roundId: String
    ): Response<String>

    // TV endpoints
    @GET("api/tv/channels")
    @Headers("Accept: application/json")
    suspend fun getTvChannels(): Response<String>

    @GET("api/game/{gameId}")
    @Headers("Accept: application/json")
    suspend fun getGame(
        @Path("gameId") gameId: String,
        @Query("pgnInJson") pgnInJson: Boolean = true,
        @Query("clocks") clocks: Boolean = true
    ): Response<String>

    /**
     * Stream a game - returns NDJSON with all moves from the start.
     * Each line after the first contains: fen, lm (last move in UCI), wc/bc (clocks)
     */
    @GET("api/stream/game/{gameId}")
    @Headers("Accept: application/x-ndjson")
    @Streaming
    suspend fun streamGame(
        @Path("gameId") gameId: String
    ): Response<okhttp3.ResponseBody>

    /**
     * Get live streamers - returns JSON array of streaming users
     */
    @GET("api/streamer/live")
    @Headers("Accept: application/json")
    suspend fun getLiveStreamers(): Response<String>

    companion object {
        private const val BASE_URL = "https://lichess.org/"

        fun create(): LichessApi {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .addInterceptor(TracingInterceptor())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(LichessApi::class.java)
        }
    }
}
