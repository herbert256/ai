package com.ai.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure aggregation for the Tournament feature — folds a set of pairwise
 * [MatchState] verdicts into a 1..N ranking, interchangeable ways.
 * No Android / coroutine dependencies, so it's trivially unit-testable
 * and cheap enough to recompute on the result-screen method toggle.
 *
 * All methods emit the same `[{id, rank, score, reason}]` shape the
 * single-shot Rerank uses, so a tournament aggregate row drops straight
 * into the existing rerank renderers and the Top-ranked scope
 * (`extractTopRankedIds`).
 */

enum class TournamentMethod { COPELAND, BRADLEY_TERRY, ELO, POINTS, SCHULZE, MARKOV }

/** Square win matrix over the tournament's responses. [ids] are the
 *  1-based `[N]` ids (the same the @RESULTS@ block / rerank JSON use);
 *  `wins[i][j]` is the average fractional credit response i earned
 *  against j, combining the swapped orientations of that pair (see
 *  [computeWinMatrix]). `games[i][j]` is the number of decided ordered
 *  judgments behind that average, so Points can score the actual ordered
 *  matches instead of the collapsed opponent result. */
class WinMatrix(
    val ids: List<Int>,
    val wins: Array<DoubleArray>,
    val games: Array<DoubleArray> = Array(ids.size) { i ->
        DoubleArray(ids.size) { j ->
            if (i != j &&
                (wins.getOrNull(i)?.getOrNull(j) ?: 0.0) +
                (wins.getOrNull(j)?.getOrNull(i) ?: 0.0) > 0.0
            ) 2.0 else 0.0
        }
    }
) {
    val n: Int get() = ids.size
}

/** One ranked row in the rerank-compatible output. */
data class RankRow(val id: Int, val rank: Int, val score: Double, val reason: String)

/** Fold every DONE match into the win matrix. [idForAgentId] maps a
 *  response's agentId to its 1-based `[N]` id; matches whose responses
 *  don't resolve are skipped. The two orientations of a pair are
 *  averaged: agree → 1.0/0.0, disagree or tie → 0.5/0.5, a single
 *  present orientation → that orientation's verdict, none → no contest. */
fun computeWinMatrix(matches: List<MatchState>, idForAgentId: (String) -> Int?): WinMatrix {
    // Every agentId that appears, resolved to its numeric id and sorted.
    val agentIds = LinkedHashSet<String>()
    matches.forEach { agentIds.add(it.responseAId); agentIds.add(it.responseBId) }
    val resolved = agentIds.mapNotNull { aid -> idForAgentId(aid)?.let { aid to it } }
        .sortedBy { it.second }
    val ids = resolved.map { it.second }
    val n = ids.size
    val wins = Array(n) { DoubleArray(n) }
    val games = Array(n) { DoubleArray(n) }
    if (n < 2) return WinMatrix(ids, wins, games)

    // Ordered-verdict lookup over decided matches.
    val verdictByOrdered = HashMap<Pair<String, String>, String>()
    matches.filter { it.status == MatchStatus.DONE && it.verdict != null }
        .forEach { verdictByOrdered[it.responseAId to it.responseBId] = it.verdict!! }

    for (a in 0 until n) {
        for (b in a + 1 until n) {
            val agA = resolved[a].first
            val agB = resolved[b].first
            val votes = mutableListOf<Double>() // 1.0 = agA won, 0.0 = agB won, 0.5 = tie
            verdictByOrdered[agA to agB]?.let { v ->
                votes.add(when (v) { "A" -> 1.0; "B" -> 0.0; else -> 0.5 })
            }
            verdictByOrdered[agB to agA]?.let { v ->
                votes.add(when (v) { "A" -> 0.0; "B" -> 1.0; else -> 0.5 })
            }
            if (votes.isEmpty()) continue
            val creditA = votes.average()
            wins[a][b] = creditA
            wins[b][a] = 1.0 - creditA
            games[a][b] = votes.size.toDouble()
            games[b][a] = votes.size.toDouble()
        }
    }
    return WinMatrix(ids, wins, games)
}

/** Dispatch to the chosen aggregation method. */
fun rankFor(method: TournamentMethod, m: WinMatrix): List<RankRow> = when (method) {
    TournamentMethod.COPELAND -> copeland(m)
    TournamentMethod.BRADLEY_TERRY -> bradleyTerry(m)
    TournamentMethod.ELO -> elo(m)
    TournamentMethod.POINTS -> points(m)
    TournamentMethod.SCHULZE -> schulze(m)
    TournamentMethod.MARKOV -> markov(m)
}

/** Win-count / Copeland: rank by total fractional wins; score = win-rate
 *  scaled 0-100. Order-independent and robust for a full round-robin. */
fun copeland(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    val games = (n - 1).coerceAtLeast(1)
    val scored = (0 until n).map { i ->
        val w = (0 until n).sumOf { j -> m.wins[i][j] }
        Triple(m.ids[i], w, 100.0 * w / games)
    }
    return assignRanks(scored.map { RankScored(it.first, it.third, "Won %.1f of %d head-to-heads".format(it.second, games)) })
}

/** Bradley–Terry: estimate a latent strength p_i per response via the
 *  standard MM iteration `p_i ← W_i / Σ_{j≠i} n_ij/(p_i+p_j)`, then rank
 *  by strength. score = strength rescaled so the strongest is 100. A
 *  small prior pseudo-count keeps an all-win / all-loss response from
 *  diverging. */
fun bradleyTerry(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))
    // Add a tiny symmetric prior (0.5 win each way vs a virtual opponent
    // pool) so totals can't be 0 and strengths stay finite.
    val prior = 0.25
    val wins = Array(n) { i -> DoubleArray(n) { j -> m.wins[i][j] } }
    val totalWins = DoubleArray(n) { i -> (0 until n).sumOf { j -> wins[i][j] } + prior }
    // games between i and j: 1.0 for every contested pair, else 0.
    val games = Array(n) { i ->
        DoubleArray(n) { j -> if (i == j) 0.0 else if (wins[i][j] + wins[j][i] > 0.0) 1.0 else 0.0 }
    }
    var p = DoubleArray(n) { 1.0 }
    repeat(200) {
        val next = DoubleArray(n)
        for (i in 0 until n) {
            var denom = prior / (p[i] + 1.0) // virtual prior opponent at strength 1
            for (j in 0 until n) {
                if (i == j) continue
                val nij = games[i][j]
                if (nij > 0.0) denom += nij / (p[i] + p[j])
            }
            next[i] = if (denom > 0.0) totalWins[i] / denom else p[i]
        }
        // Renormalize to Σp = n to stop drift.
        val sum = next.sum()
        if (sum > 0.0) for (i in 0 until n) next[i] = next[i] * n / sum
        var maxDelta = 0.0
        for (i in 0 until n) maxDelta = maxOf(maxDelta, kotlin.math.abs(next[i] - p[i]) / (p[i] + 1e-9))
        p = next
        if (maxDelta < 1e-6) return@repeat
    }
    val maxP = p.maxOrNull() ?: 1.0
    val scored = (0 until n).map { i ->
        // Score rescaled so the strongest is 100, kept to ONE decimal.
        val raw = if (maxP > 0.0) 100.0 * p[i] / maxP else 0.0
        RankScored(m.ids[i], Math.round(raw * 10.0) / 10.0, "Strength %.3f".format(p[i]))
    }
    return assignRanks(scored)
}

/** Chess-style points over the actual ordered judgments: 1 for a clear
 *  win, 0 for a loss, ½ for a draw. The win matrix stores averaged
 *  pair credit, so multiply by [WinMatrix.games] to recover the total
 *  ordered-match points. */
fun points(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    val scored = (0 until n).map { i ->
        var pts = 0.0
        var played = 0
        for (j in 0 until n) {
            if (i == j) continue
            val games = m.games.getOrNull(i)?.getOrNull(j) ?: 0.0
            if (games <= 0.0) continue
            played += games.toInt()
            pts += m.wins[i][j] * games
        }
        val ptsText = if (pts == Math.floor(pts)) "%.0f".format(pts) else "%.1f".format(pts)
        RankScored(m.ids[i], pts, "$ptsText / $played points")
    }
    return assignRanks(scored)
}

/** Schulze / beatpath ranking. Each pair's ordered-match points become
 *  the pairwise preference strength. The Floyd-Warshall pass then finds
 *  strongest paths, and candidates are ordered by the Schulze pairwise
 *  relation. The visible score is the percentage of opponents beaten by
 *  strongest paths. */
fun schulze(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))

    val d = Array(n) { i ->
        DoubleArray(n) { j ->
            if (i == j) 0.0 else m.wins[i][j] * (m.games.getOrNull(i)?.getOrNull(j) ?: 0.0)
        }
    }
    val p = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0 until n) {
            if (i != j && d[i][j] > d[j][i]) p[i][j] = d[i][j]
        }
    }
    for (i in 0 until n) {
        for (j in 0 until n) {
            if (i == j) continue
            for (k in 0 until n) {
                if (i == k || j == k) continue
                p[j][k] = maxOf(p[j][k], minOf(p[j][i], p[i][k]))
            }
        }
    }

    val pathWins = DoubleArray(n) { i ->
        (0 until n).sumOf { j ->
            when {
                i == j -> 0.0
                p[i][j] > p[j][i] -> 1.0
                p[i][j] == p[j][i] -> 0.5
                else -> 0.0
            }
        }
    }
    val pathMargin = DoubleArray(n) { i -> (0 until n).sumOf { j -> p[i][j] - p[j][i] } }
    val order = (0 until n).sortedWith { a, b ->
        when {
            p[a][b] > p[b][a] -> -1
            p[a][b] < p[b][a] -> 1
            pathWins[a] != pathWins[b] -> -pathWins[a].compareTo(pathWins[b])
            pathMargin[a] != pathMargin[b] -> -pathMargin[a].compareTo(pathMargin[b])
            else -> m.ids[a].compareTo(m.ids[b])
        }
    }
    val denom = (n - 1).coerceAtLeast(1)
    return order.mapIndexed { rank, i ->
        val score = 100.0 * pathWins[i] / denom
        RankRow(
            id = m.ids[i],
            rank = rank + 1,
            score = Math.round(score * 10.0) / 10.0,
            reason = "Beatpath wins %.1f of %d".format(pathWins[i], denom)
        )
    }
}

/** Markov-chain ranking over pairwise results. From each response, the
 *  chain samples an opponent uniformly and moves to that opponent in
 *  proportion to how strongly the opponent beat it; otherwise it stays.
 *  A small teleport keeps the chain ergodic, then the stationary
 *  distribution is rescaled so the strongest response is 100. */
fun markov(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))

    val transition = Array(n) { DoubleArray(n) }
    val denom = (n - 1).toDouble()
    for (i in 0 until n) {
        var stay = 0.0
        for (j in 0 until n) {
            if (i == j) continue
            val games = m.games.getOrNull(i)?.getOrNull(j) ?: 0.0
            if (games <= 0.0) {
                stay += 1.0 / denom
                continue
            }
            val moveToJ = m.wins[j][i].coerceIn(0.0, 1.0) / denom
            transition[i][j] = moveToJ
            stay += (1.0 / denom) - moveToJ
        }
        transition[i][i] = stay.coerceAtLeast(0.0)
    }

    val damping = 0.92
    val teleport = (1.0 - damping) / n
    var dist = DoubleArray(n) { 1.0 / n }
    repeat(500) {
        val next = DoubleArray(n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                next[j] += dist[i] * (damping * transition[i][j] + teleport)
            }
        }
        val sum = next.sum()
        if (sum > 0.0) for (i in 0 until n) next[i] /= sum
        var delta = 0.0
        for (i in 0 until n) delta = maxOf(delta, kotlin.math.abs(next[i] - dist[i]))
        dist = next
        if (delta < 1e-10) return@repeat
    }
    val max = dist.maxOrNull() ?: 1.0
    val scored = (0 until n).map { i ->
        val raw = if (max > 0.0) 100.0 * dist[i] / max else 0.0
        RankScored(
            id = m.ids[i],
            score = Math.round(raw * 10.0) / 10.0,
            reason = "Stationary share %.1f%%".format(dist[i] * 100.0)
        )
    }
    return assignRanks(scored)
}

/** Elo: replay each contested pair once (in deterministic id order) as a
 *  single game scored by the pair's fractional result, updating ratings
 *  K=32 from a 1500 base. NOTE: Elo is order-sensitive; the fixed id
 *  ordering makes the result reproducible but is a weaker fit for a
 *  static round-robin than Copeland / Bradley–Terry. */
fun elo(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    val r = DoubleArray(n) { 1500.0 }
    val k = 32.0
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            if (m.wins[i][j] + m.wins[j][i] <= 0.0) continue // uncontested
            val sI = m.wins[i][j] // fractional score for i vs j
            val eI = 1.0 / (1.0 + Math.pow(10.0, (r[j] - r[i]) / 400.0))
            r[i] += k * (sI - eI)
            r[j] += k * ((1.0 - sI) - (1.0 - eI))
        }
    }
    val scored = (0 until n).map { i ->
        RankScored(m.ids[i], Math.round(r[i]).toDouble(), "Elo %d".format(Math.round(r[i])))
    }
    return assignRanks(scored)
}

private data class RankScored(val id: Int, val score: Double, val reason: String)

/** Sort by score desc (tiebreak id asc for determinism) and assign
 *  rank 1..N. */
private fun assignRanks(scored: List<RankScored>): List<RankRow> {
    val sorted = scored.sortedWith(compareByDescending<RankScored> { it.score }.thenBy { it.id })
    return sorted.mapIndexed { idx, s -> RankRow(s.id, idx + 1, s.score, s.reason) }
}

/** Serialize ranks into the rerank-compatible `[{id,rank,score,reason}]`
 *  JSON the rest of the system already parses. Scores are rounded to 4
 *  decimals to match the rerank tolerance. */
fun List<RankRow>.toRerankJson(): String {
    val arr = JsonArray()
    forEach { row ->
        arr.add(JsonObject().apply {
            addProperty("id", row.id)
            addProperty("rank", row.rank)
            // Integer-valued scores serialise clean; fractional keep 2dp.
            val s = if (row.score == Math.floor(row.score)) row.score.toInt() else
                "%.2f".format(row.score).toDouble()
            addProperty("score", s)
            addProperty("reason", row.reason)
        })
    }
    return createAppGson(prettyPrint = true).toJson(arr)
}

/** Encode the win matrix + the method that produced the aggregate's
 *  current `content`, for the [SecondaryResult.tournamentMatrix] sidecar.
 *  Lets the result screen recompute every ranking method locally without
 *  re-reading every match row. */
fun WinMatrix.encode(method: TournamentMethod): String {
    val obj = JsonObject()
    val idsArr = JsonArray(); ids.forEach { idsArr.add(it) }
    obj.add("ids", idsArr)
    val winsArr = JsonArray()
    wins.forEach { row ->
        val r = JsonArray(); row.forEach { r.add(it) }; winsArr.add(r)
    }
    obj.add("wins", winsArr)
    val gamesArr = JsonArray()
    games.forEach { row ->
        val r = JsonArray(); row.forEach { r.add(it) }; gamesArr.add(r)
    }
    obj.add("games", gamesArr)
    obj.addProperty("method", method.name)
    return obj.toString()
}

/** Re-rank an AGGREGATE tournament row for [method] from its stored win
 *  matrix and persist the new `content` + matrix sidecar. A pure local
 *  recompute (no API calls) the result-screen method toggle uses; the
 *  save bumps [SecondaryDataVersion] so observers reload. No-op when the
 *  row or its matrix sidecar is missing. */
fun applyTournamentMethod(context: android.content.Context, reportId: String, rowId: String, method: TournamentMethod) {
    val row = SecondaryResultStorage.get(context, reportId, rowId) ?: return
    val decoded = decodeTournamentMatrix(row.tournamentMatrix) ?: return
    val ranks = rankFor(method, decoded.first)
    SecondaryResultStorage.save(context, row.copy(
        content = ranks.toRerankJson(),
        tournamentMatrix = decoded.first.encode(method)
    ))
}

/** Inverse of [WinMatrix.encode] — returns the matrix and the stored
 *  method, or null when the sidecar is missing / malformed. */
fun decodeTournamentMatrix(json: String?): Pair<WinMatrix, TournamentMethod>? {
    if (json.isNullOrBlank()) return null
    return try {
        val obj = JsonParser.parseString(json).asJsonObject
        val ids = obj.getAsJsonArray("ids").map { it.asInt }
        val winsArr = obj.getAsJsonArray("wins")
        val wins = Array(winsArr.size()) { i ->
            val row = winsArr[i].asJsonArray
            DoubleArray(row.size()) { j -> row[j].asDouble }
        }
        val games = obj.getAsJsonArray("games")?.let { gamesArr ->
            Array(gamesArr.size()) { i ->
                val row = gamesArr[i].asJsonArray
                DoubleArray(row.size()) { j -> row[j].asDouble }
            }
        } ?: Array(wins.size) { i ->
            DoubleArray(wins.size) { j ->
                if (i != j && wins[i][j] + wins[j][i] > 0.0) 2.0 else 0.0
            }
        }
        val method = try { TournamentMethod.valueOf(obj.get("method").asString) }
            catch (_: Exception) { TournamentMethod.COPELAND }
        WinMatrix(ids, wins, games) to method
    } catch (_: Exception) {
        null
    }
}
