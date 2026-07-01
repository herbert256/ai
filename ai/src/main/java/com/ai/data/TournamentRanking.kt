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

enum class TournamentMethod { COPELAND, ELO, DAVIDSON, MARKOV, SCHULZE, COLLEY, TRUESKILL2 }

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
    TournamentMethod.MARKOV -> markov(m)
    TournamentMethod.SCHULZE -> schulze(m)
    TournamentMethod.COLLEY -> colley(m)
    TournamentMethod.TRUESKILL2 -> trueskill2(m)
}

/** Win-count / Copeland: rank by total fractional wins; score = win-rate
 *  scaled 0-100. Order-independent and robust for a full round-robin. */
fun copeland(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    // Lone participant scores 100, matching the other six methods (a bare
    // n==1 would otherwise give 100·0/1 = 0, making the same model read 0
    // under Copeland and 100 everywhere else — inconsistent in the Combined
    // fold and the podium's cross-method table).
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))
    val scored = (0 until n).map { i ->
        val w = (0 until n).sumOf { j -> m.wins[i][j] }
        // Denominator = opponents this model actually played (contested
        // pairs), not a fixed n-1. With a missing/errored match a fixed n-1
        // scores the uncontested pair like a loss and the reason overstates
        // the games played; per-model played-count makes it a true win-rate
        // (identical to n-1 for a complete round-robin). Locale.US so the
        // reason renders a dot, not a comma, on comma-decimal locales.
        val played = (0 until n).count { j -> j != i && m.games[i][j] > 0.0 }.coerceAtLeast(1)
        RankScored(m.ids[i], 100.0 * w / played,
            String.format(java.util.Locale.US, "Won %.1f of %d head-to-heads", w, played))
    }
    return assignRanks(scored)
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
            reason = "Davidson strength %.3f · tie %.2f".format(strengths[i], tieTendency),
            sortKey = raw
        )
    }
    return assignRanks(scored)
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
            reason = "Stationary share %.1f%%".format(dist[i] * 100.0),
            sortKey = raw
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
        RankScored(m.ids[i], Math.round(r[i]).toDouble(), "Elo %d".format(Math.round(r[i])), sortKey = r[i])
    }
    return assignRanks(scored)
}

/** Directed pairwise points — the total credit response i earned against j
 *  across both judged orientations (0 for an uncontested pair). The shared
 *  building block for the Condorcet methods below. */
private fun WinMatrix.points(i: Int, j: Int): Double =
    wins[i][j] * (games.getOrNull(i)?.getOrNull(j) ?: 0.0)

/** Schulze method (beatpaths). Seeds each ordered pair with the winner's
 *  pairwise points, then computes the strongest (widest) path between every
 *  pair via Floyd–Warshall; response i outranks j when its strongest path to
 *  j beats j's path back. It's an ordering method, so the visible score is
 *  rank-based and the beatpath win count is the reason. A respected
 *  Condorcet method (used by Debian / Wikimedia), distinct from Tideman's
 *  ranked pairs in how it resolves cycles. */
fun schulze(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))
    // p[i][j] = strength of the strongest path i→j; seed with the direct
    // winning support (the winner's points; 0 when i didn't win the pair).
    val p = Array(n) { i ->
        DoubleArray(n) { j -> if (i != j && m.points(i, j) > m.points(j, i)) m.points(i, j) else 0.0 }
    }
    for (k in 0 until n) for (i in 0 until n) if (i != k) for (j in 0 until n) if (j != i && j != k) {
        val widen = minOf(p[i][k], p[k][j])
        if (widen > p[i][j]) p[i][j] = widen
    }
    val beatWins = IntArray(n) { i -> (0 until n).count { j -> j != i && p[i][j] > p[j][i] } }
    val strength = DoubleArray(n) { i -> (0 until n).sumOf { j -> p[i][j] } }
    val order = (0 until n).sortedWith(
        compareByDescending<Int> { beatWins[it] }
            .thenByDescending { strength[it] }
            .thenBy { m.ids[it] }
    )
    val denom = (n - 1).coerceAtLeast(1)
    return order.mapIndexed { rank, i ->
        val score = 100.0 * (n - 1 - rank) / denom
        RankRow(
            id = m.ids[i],
            rank = rank + 1,
            score = Math.round(score * 10.0) / 10.0,
            reason = String.format(java.util.Locale.US, "Beats %d via strongest paths", beatWins[i])
        )
    }
}

/** Colley's bias-free rating (the sports/BCS method). Solves the Colley
 *  system C·r = b, where C_ii = 2 + games_i, C_ij = −games_ij, and
 *  b_i = 1 + (wins_i − losses_i)/2. Ratings centre near 0.5 and fold in
 *  schedule strength without margin bias; the visible score rescales them so
 *  the strongest response is 100. */
fun colley(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))
    val games = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else (m.games.getOrNull(i)?.getOrNull(j) ?: 0.0) } }
    val totalGames = DoubleArray(n) { i -> (0 until n).sumOf { j -> games[i][j] } }
    // Fractional wins (a tie counts 0.5 each); losses are the complement.
    val wins = DoubleArray(n) { i -> (0 until n).sumOf { j -> m.wins[i][j] * games[i][j] } }
    val losses = DoubleArray(n) { i -> totalGames[i] - wins[i] }
    val c = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 2.0 + totalGames[i] else -games[i][j] } }
    val b = DoubleArray(n) { i -> 1.0 + 0.5 * (wins[i] - losses[i]) }
    val r = solveLinearSystem(c, b)
    val scored = if (r == null || r.any { !it.isFinite() }) {
        // C is symmetric positive-definite so this shouldn't happen; fall back
        // to a plain win share if the solve degenerates.
        (0 until n).map { i ->
            val share = if (totalGames[i] > 0.0) 100.0 * wins[i] / totalGames[i] else 0.0
            RankScored(m.ids[i], Math.round(share * 10.0) / 10.0, "Win share %.0f%%".format(share), sortKey = share)
        }
    } else {
        val maxR = r.maxOrNull() ?: 1.0
        (0 until n).map { i ->
            val raw = if (maxR > 0.0) 100.0 * r[i] / maxR else 0.0
            RankScored(m.ids[i], Math.round(raw * 10.0) / 10.0,
                String.format(java.util.Locale.US, "Colley rating %.3f", r[i]), sortKey = raw)
        }
    }
    return assignRanks(scored)
}

/** Solve A·x = b for a small dense system via Gauss–Jordan elimination with
 *  partial pivoting. Returns null if the system is singular. A and b are
 *  copied internally, not mutated. */
private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
    val n = b.size
    if (n == 0) return DoubleArray(0)
    val mtx = Array(n) { i -> DoubleArray(n + 1) { col -> if (col < n) a[i][col] else b[i] } }
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) if (kotlin.math.abs(mtx[r][col]) > kotlin.math.abs(mtx[pivot][col])) pivot = r
        if (kotlin.math.abs(mtx[pivot][col]) < 1e-12) return null
        if (pivot != col) { val tmp = mtx[pivot]; mtx[pivot] = mtx[col]; mtx[col] = tmp }
        for (r in 0 until n) {
            if (r == col) continue
            val factor = mtx[r][col] / mtx[col][col]
            if (factor == 0.0) continue
            for (c in col..n) mtx[r][c] -= factor * mtx[col][c]
        }
    }
    return DoubleArray(n) { i -> mtx[i][n] / mtx[i][i] }
}

/** TrueSkill (the 1-v-1 core of Microsoft's TrueSkill2). A Bayesian skill
 *  belief — mean μ and uncertainty σ per response — updated through every
 *  contested pair (processed in id order, winner = higher pairwise points,
 *  equal points = a draw, draw margin estimated from the observed tie rate).
 *  TrueSkill2's extra factors (team make-up, player experience, time) have no
 *  analogue in 1-v-1 answer judging, so they drop out. The visible score is
 *  the conservative skill estimate μ − 3σ, TrueSkill's own leaderboard
 *  convention. */
fun trueskill2(m: WinMatrix): List<RankRow> {
    val n = m.n
    if (n == 0) return emptyList()
    if (n == 1) return listOf(RankRow(m.ids[0], 1, 100.0, "Only response"))
    val mu = DoubleArray(n) { 25.0 }
    val sigma2 = DoubleArray(n) { (25.0 / 3.0) * (25.0 / 3.0) }
    val beta = (25.0 / 3.0) / 2.0
    val tau2 = ((25.0 / 3.0) / 100.0).let { it * it }
    // Draw rate uses the SAME even-pair definition the per-match update below
    // uses (points equal → processed as a draw), not the explicit ties array.
    // The old code fed drawProb from m.ties, which excludes a 1-1 orientation
    // split (ties=0, points equal) — so a split was processed as a draw but
    // NOT counted as one, an internal inconsistency. This also sidesteps the
    // legacy-sidecar tie synthesis (which mislabels every 0.5 pair fully tied),
    // since the draw decision no longer reads m.ties at all.
    var totalGames = 0.0; var drawnGames = 0.0
    for (i in 0 until n) for (j in i + 1 until n) {
        val cnt = m.games.getOrNull(i)?.getOrNull(j) ?: 0.0
        totalGames += cnt
        if (cnt > 0.0 && kotlin.math.abs(m.points(i, j) - m.points(j, i)) < 1e-9) drawnGames += cnt
    }
    val drawProb = if (totalGames > 0.0) (drawnGames / totalGames).coerceIn(0.001, 0.8) else 0.1
    val drawMargin = invNormCdf((drawProb + 1.0) / 2.0) * kotlin.math.sqrt(2.0) * beta

    for (i in 0 until n) for (j in i + 1 until n) {
        val cnt = m.games.getOrNull(i)?.getOrNull(j) ?: 0.0
        if (cnt <= 0.0) continue
        val pi = m.points(i, j); val pj = m.points(j, i)
        val draw = kotlin.math.abs(pi - pj) < 1e-9
        val a = if (pi >= pj) i else j   // winner
        val b = if (pi >= pj) j else i   // loser
        sigma2[a] += tau2; sigma2[b] += tau2
        val c2 = 2.0 * beta * beta + sigma2[a] + sigma2[b]
        val c = kotlin.math.sqrt(c2)
        val t = (mu[a] - mu[b]) / c
        val eps = drawMargin / c
        val v = if (draw) tsVDraw(t, eps) else tsVWin(t, eps)
        val w = if (draw) tsWDraw(t, eps) else tsWWin(t, eps)
        mu[a] += (sigma2[a] / c) * v
        mu[b] -= (sigma2[b] / c) * v
        sigma2[a] *= (1.0 - (sigma2[a] / c2) * w).coerceAtLeast(1e-4)
        sigma2[b] *= (1.0 - (sigma2[b] / c2) * w).coerceAtLeast(1e-4)
    }
    val scored = (0 until n).map { i ->
        val sigma = kotlin.math.sqrt(sigma2[i])
        RankScored(m.ids[i], Math.round((mu[i] - 3.0 * sigma) * 10.0) / 10.0,
            String.format(java.util.Locale.US, "TrueSkill μ %.1f σ %.1f", mu[i], sigma), sortKey = mu[i] - 3.0 * sigma)
    }
    return assignRanks(scored)
}

// --- Gaussian helpers for TrueSkill (no erf/quantile in the stdlib) ---

/** erf via Abramowitz & Stegun 7.1.26 (|error| < 1.5e-7). */
private fun erf(x: Double): Double {
    val t = 1.0 / (1.0 + 0.3275911 * kotlin.math.abs(x))
    val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) *
        t * kotlin.math.exp(-x * x)
    return if (x >= 0.0) y else -y
}

private fun stdCdf(x: Double): Double = 0.5 * (1.0 + erf(x / kotlin.math.sqrt(2.0)))
private fun stdPdf(x: Double): Double = kotlin.math.exp(-0.5 * x * x) / kotlin.math.sqrt(2.0 * Math.PI)

/** Inverse standard-normal CDF via Acklam's rational approximation. */
private fun invNormCdf(p: Double): Double {
    val pp = p.coerceIn(1e-9, 1.0 - 1e-9)
    val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
    val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
    val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
    val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
    val plow = 0.02425; val phigh = 1.0 - plow
    return when {
        pp < plow -> {
            val q = kotlin.math.sqrt(-2.0 * kotlin.math.ln(pp))
            (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
        }
        pp <= phigh -> {
            val q = pp - 0.5; val r = q * q
            (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
        }
        else -> {
            val q = kotlin.math.sqrt(-2.0 * kotlin.math.ln(1.0 - pp))
            -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
        }
    }
}

private fun tsVWin(t: Double, eps: Double): Double = stdPdf(t - eps) / stdCdf(t - eps).coerceAtLeast(1e-10)
private fun tsWWin(t: Double, eps: Double): Double {
    val v = tsVWin(t, eps)
    return (v * (v + t - eps)).coerceIn(0.0, 1.0)
}
private fun tsVDraw(t: Double, eps: Double): Double {
    val denom = (stdCdf(eps - t) - stdCdf(-eps - t)).coerceAtLeast(1e-10)
    return (stdPdf(-eps - t) - stdPdf(eps - t)) / denom
}
private fun tsWDraw(t: Double, eps: Double): Double {
    val denom = (stdCdf(eps - t) - stdCdf(-eps - t)).coerceAtLeast(1e-10)
    val v = tsVDraw(t, eps)
    return (v * v + ((eps - t) * stdPdf(eps - t) - (-eps - t) * stdPdf(-eps - t)) / denom).coerceIn(0.0, 1.0)
}

/** [sortKey] is the FULL-precision value used to order the ranking;
 *  [score] is the (possibly rounded-for-display) number shown. They default
 *  to equal, so a method that already passes a full-precision score
 *  (Copeland) needs no change. Methods that round the display score to 0.1
 *  pass the raw value as [sortKey] so near-ties (< 0.05 apart) don't collapse
 *  to the same rounded value and get resolved by the id tiebreak — which
 *  could invert their true order, inconsistently with Copeland/Schulze. */
private data class RankScored(val id: Int, val score: Double, val reason: String, val sortKey: Double = score)

/** Sort by full-precision sortKey desc (tiebreak id asc for determinism) and
 *  assign rank 1..N. */
private fun assignRanks(scored: List<RankScored>): List<RankRow> {
    val sorted = scored.sortedWith(compareByDescending<RankScored> { it.sortKey }.thenBy { it.id })
    return sorted.mapIndexed { idx, s -> RankRow(s.id, idx + 1, s.score, s.reason) }
}

/** Serialize ranks into the rerank-compatible `[{id,rank,score,reason}]`
 *  JSON the rest of the system already parses. Scores are rounded to 4
 *  decimals to match the rerank tolerance. */
fun List<RankRow>.toRerankJson(): String {
    val arr = JsonArray()
    forEach { row ->
        // A divergent iterative method (Davidson / Elo / Markov on a big or
        // degenerate run) can produce a NaN / Infinity score. Gson is left
        // strict (no serializeSpecialFloatingPointValues), so emitting one
        // throws and — because the aggregate recompute runs in a background
        // resume coroutine — used to crash the whole app. Coerce non-finite
        // scores to 0 so serialization can never throw.
        val safeScore = if (row.score.isFinite()) row.score else 0.0
        arr.add(JsonObject().apply {
            addProperty("id", row.id)
            addProperty("rank", row.rank)
            // Integer-valued scores serialise clean; fractional keep 2dp.
            // Round numerically — NOT via "%.2f".format(x).toDouble(): the
            // format step honours the device locale, so on a comma-decimal
            // locale (e.g. nl-NL) it yields "72,14", which toDouble() can't
            // parse → NumberFormatException (the real tournament crash).
            val s = if (safeScore == Math.floor(safeScore)) safeScore.toInt() else
                Math.round(safeScore * 100.0) / 100.0
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
    val matches = SecondaryResultStorage.listForReport(context, reportId, SecondaryKind.TOURNAMENT)
        .filter { it.tournamentRole == "MATCH" && it.tournamentJudgeRunId == runId }
        .mapNotNull { it.toMatchState() }
    // Number participants by their stable position in report.agents (the
    // match participant set), NOT by the current SUCCESS set: a participant
    // that transiently dips out of SUCCESS (mid-regenerate) would otherwise
    // be dropped — shrinking the matrix — and the surviving ids would shift,
    // mapping ranks to the wrong models. Matches TournamentEngine's
    // recomputeAndPersistAggregate and the podium loader's numbering.
    val participantIds = matches.flatMapTo(HashSet()) { listOf(it.responseAId, it.responseBId) }
    val idByAgent = report.agents
        .filter { it.agentId in participantIds }
        .withIndex().associate { (i, a) -> a.agentId to (i + 1) }
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
