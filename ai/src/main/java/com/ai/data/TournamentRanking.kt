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

enum class TournamentMethod { COPELAND, ELO, DAVIDSON, TIDEMAN, MARKOV }

/** Square win matrix over the tournament's responses. [ids] are the
 *  1-based `[N]` ids (the same the @RESULTS@ block / rerank JSON use);
 *  `wins[i][j]` is the average fractional credit response i earned
 *  against j, combining the swapped orientations of that pair (see
 *  [computeWinMatrix]). `games[i][j]` is the number of decided ordered
 *  judgments behind that average. `ties[i][j]` is the explicit draw
 *  count for tie-aware methods such as Davidson. */
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
    },
    val ties: Array<DoubleArray> = Array(ids.size) { i ->
        DoubleArray(ids.size) { j ->
            if (i != j &&
                games.getOrNull(i)?.getOrNull(j)?.let { it > 0.0 } == true &&
                kotlin.math.abs((wins.getOrNull(i)?.getOrNull(j) ?: 0.0) - 0.5) < 1e-9
            ) games[i][j] else 0.0
        }
    },
    val hasTieData: Boolean = true
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
    val ties = Array(n) { DoubleArray(n) }
    if (n < 2) return WinMatrix(ids, wins, games, ties)

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
            val tieCount = votes.count { it == 0.5 }.toDouble()
            ties[a][b] = tieCount
            ties[b][a] = tieCount
        }
    }
    return WinMatrix(ids, wins, games, ties)
}

/** Dispatch to the chosen aggregation method. */
fun rankFor(method: TournamentMethod, m: WinMatrix): List<RankRow> = when (method) {
    TournamentMethod.COPELAND -> copeland(m)
    TournamentMethod.ELO -> elo(m)
    TournamentMethod.DAVIDSON -> davidson(m)
    TournamentMethod.TIDEMAN -> tideman(m)
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

/** Davidson tie-aware paired-comparison model. It estimates a latent
 *  strength per response plus a global tie tendency, using the explicit
 *  draw counts preserved in [WinMatrix.ties]. The visible score is the
 *  fitted strength rescaled so the strongest response is 100. */
fun davidson(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))

    val wins = Array(n) { DoubleArray(n) }
    val ties = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val games = m.games.getOrNull(i)?.getOrNull(j) ?: 0.0
            if (games <= 0.0) continue
            val tieCount = (m.ties.getOrNull(i)?.getOrNull(j) ?: 0.0).coerceIn(0.0, games)
            val pointsI = (m.wins[i][j] * games).coerceIn(0.0, games)
            val winsI = (pointsI - 0.5 * tieCount).coerceIn(0.0, games - tieCount)
            val winsJ = (games - tieCount - winsI).coerceIn(0.0, games - tieCount)
            wins[i][j] = winsI
            wins[j][i] = winsJ
            ties[i][j] = tieCount
            ties[j][i] = tieCount
        }
    }

    var theta = DoubleArray(n)
    var gamma = 0.0
    repeat(700) { iter ->
        val grad = DoubleArray(n)
        var gradGamma = 0.0
        var totalGames = 0.0
        val alpha = DoubleArray(n) { kotlin.math.exp(theta[it]) }
        val nu = kotlin.math.exp(gamma)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val wij = wins[i][j]
                val wji = wins[j][i]
                val tij = ties[i][j]
                val nij = wij + wji + tij
                if (nij <= 0.0) continue
                totalGames += nij
                val tieTerm = nu * kotlin.math.sqrt(alpha[i] * alpha[j])
                val denom = alpha[i] + alpha[j] + tieTerm
                grad[i] += wij + 0.5 * tij - nij * (alpha[i] + 0.5 * tieTerm) / denom
                grad[j] += wji + 0.5 * tij - nij * (alpha[j] + 0.5 * tieTerm) / denom
                gradGamma += tij - nij * tieTerm / denom
            }
        }
        if (totalGames <= 0.0) return@repeat
        val meanGrad = grad.average()
        val lr = 0.08 / kotlin.math.sqrt(iter + 1.0)
        for (i in 0 until n) theta[i] += lr * (grad[i] - meanGrad)
        gamma = (gamma + lr * gradGamma).coerceIn(-6.0, 6.0)
        val meanTheta = theta.average()
        theta = DoubleArray(n) { theta[it] - meanTheta }
    }

    val strengths = DoubleArray(n) { kotlin.math.exp(theta[it]) }
    val maxStrength = strengths.maxOrNull() ?: 1.0
    val tieTendency = kotlin.math.exp(gamma)
    val scored = (0 until n).map { i ->
        val raw = if (maxStrength > 0.0) 100.0 * strengths[i] / maxStrength else 0.0
        RankScored(
            id = m.ids[i],
            score = Math.round(raw * 10.0) / 10.0,
            reason = "Davidson strength %.3f · tie %.2f".format(strengths[i], tieTendency)
        )
    }
    return assignRanks(scored)
}

/** Tideman / Ranked Pairs. Each pair's ordered-match points give a directed
 *  strength; the pairwise majorities are sorted strongest-first (by margin,
 *  then winning support) and "locked in" one by one, skipping any edge that
 *  would create a cycle with the already-locked ones. The locked graph is a
 *  DAG; candidates are ordered by how many others they dominate in it (the
 *  source, which dominates all, ranks first). It's an ordering method, so
 *  the visible score is rank-based and the locked record is the reason. */
fun tideman(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))

    // Directed strength: i's total credit over j across both orientations.
    val d = Array(n) { i ->
        DoubleArray(n) { j ->
            if (i == j) 0.0 else m.wins[i][j] * (m.games.getOrNull(i)?.getOrNull(j) ?: 0.0)
        }
    }
    // One majority per unordered pair — winner = higher d. Skip exact ties.
    data class Majority(val w: Int, val l: Int, val margin: Double, val support: Double)
    val majorities = ArrayList<Majority>()
    for (i in 0 until n) for (j in i + 1 until n) {
        val dij = d[i][j]; val dji = d[j][i]
        if (dij == dji) continue
        if (dij > dji) majorities.add(Majority(i, j, dij - dji, dij))
        else majorities.add(Majority(j, i, dji - dij, dji))
    }
    // Strongest first: larger margin, then larger winning support, then a
    // deterministic id tiebreak so the lock order is reproducible.
    majorities.sortWith(
        compareByDescending<Majority> { it.margin }
            .thenByDescending { it.support }
            .thenBy { m.ids[it.w] }
            .thenBy { m.ids[it.l] }
    )
    // Lock each majority unless it would close a cycle (the loser already
    // reaches the winner). reach is the transitive closure of locked edges.
    val reach = Array(n) { BooleanArray(n) }
    for (mj in majorities) {
        if (reach[mj.l][mj.w]) continue // would create a cycle → skip
        val from = (0 until n).filter { it == mj.w || reach[it][mj.w] }
        val to = (0 until n).filter { it == mj.l || reach[mj.l][it] }
        for (a in from) for (b in to) reach[a][b] = true
    }
    // a reaches b ⇒ reachCount[a] > reachCount[b], so ordering by reach count
    // is consistent with the locked DAG; ties (incomparable nodes) fall back
    // to net margin then id.
    val reachCount = IntArray(n) { i -> (0 until n).count { it != i && reach[i][it] } }
    val marginSum = DoubleArray(n) { i -> (0 until n).sumOf { j -> d[i][j] - d[j][i] } }
    val order = (0 until n).sortedWith(
        compareByDescending<Int> { reachCount[it] }
            .thenByDescending { marginSum[it] }
            .thenBy { m.ids[it] }
    )
    val denom = (n - 1).coerceAtLeast(1)
    return order.mapIndexed { rank, i ->
        // Ranked Pairs is an ORDERING method — score by rank position so the
        // column stays monotonic and distinct; the locked dominance is the
        // reason for context.
        val score = 100.0 * (n - 1 - rank) / denom
        RankRow(
            id = m.ids[i],
            rank = rank + 1,
            score = Math.round(score * 10.0) / 10.0,
            reason = "Beats %d via locked pairs".format(reachCount[i])
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
 *  static round-robin than Copeland / Tideman / Markov. */
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
    val tiesArr = JsonArray()
    ties.forEach { row ->
        val r = JsonArray(); row.forEach { r.add(it) }; tiesArr.add(r)
    }
    obj.add("ties", tiesArr)
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
    val matrix = if (method == TournamentMethod.DAVIDSON && !decoded.first.hasTieData) {
        rebuildTournamentMatrixFromRows(context, reportId, row) ?: decoded.first
    } else decoded.first
    val ranks = rankFor(method, matrix)
    SecondaryResultStorage.save(context, row.copy(
        content = ranks.toRerankJson(),
        tournamentMatrix = matrix.encode(method)
    ))
}

private fun rebuildTournamentMatrixFromRows(
    context: android.content.Context,
    reportId: String,
    aggregateRow: SecondaryResult
): WinMatrix? {
    val runId = aggregateRow.tournamentJudgeRunId ?: return null
    val report = ReportStorage.getReport(context, reportId) ?: return null
    val successful = report.agents.filter {
        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
    }
    val idByAgent = successful.withIndex().associate { (i, a) -> a.agentId to (i + 1) }
    val matches = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
        .filter { it.tournamentRole == "MATCH" && it.tournamentJudgeRunId == runId }
        .mapNotNull { it.toMatchState() }
    return computeWinMatrix(matches) { idByAgent[it] }
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
        val tiesArr = obj.getAsJsonArray("ties")
        val ties = tiesArr?.let {
            Array(it.size()) { i ->
                val row = it[i].asJsonArray
                DoubleArray(row.size()) { j -> row[j].asDouble }
            }
        } ?: Array(wins.size) { i ->
            DoubleArray(wins.size) { j ->
                if (i != j && games[i][j] > 0.0 && kotlin.math.abs(wins[i][j] - 0.5) < 1e-9)
                    games[i][j] else 0.0
            }
        }
        val method = try { TournamentMethod.valueOf(obj.get("method").asString) }
            catch (_: Exception) { TournamentMethod.COPELAND }
        WinMatrix(ids, wins, games, ties, hasTieData = tiesArr != null) to method
    } catch (_: Exception) {
        null
    }
}
