package com.eval.data

import com.google.gson.annotations.SerializedName

data class LichessGame(
    val id: String,
    val rated: Boolean,
    val variant: String,
    val speed: String,
    val perf: String?,
    val status: String,
    val winner: String?,
    val players: Players,
    val pgn: String?,
    val moves: String?,
    val clock: Clock?,
    val createdAt: Long?,
    val lastMoveAt: Long?
)

data class Players(
    val white: Player,
    val black: Player
)

data class Player(
    val user: User?,
    val rating: Int?,
    val aiLevel: Int?
)

data class User(
    val name: String,
    val id: String
)

data class Clock(
    val initial: Int,
    val increment: Int
)
