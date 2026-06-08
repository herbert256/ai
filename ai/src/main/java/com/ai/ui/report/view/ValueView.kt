package com.ai.ui.report.view

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ai.data.AppService
import com.ai.data.Report
import com.ai.data.barTitle
import com.ai.data.ReportDataVersion
import com.ai.data.ReportStatus
import com.ai.data.SecondaryDataVersion
import com.ai.data.SecondaryKind
import com.ai.data.SecondaryResultStorage
import com.ai.data.TRANSRANK_ROLE_CELL
import com.ai.data.TournamentMethod
import com.ai.data.WinMatrix
import com.ai.data.aggregateTranslatorRanks
import com.ai.data.decodeTournamentMatrix
import com.ai.data.judgesConsensusWinMatrix
import com.ai.data.rankFor
import com.ai.data.toCompareCellState
import com.ai.data.toJudgeCellState
import com.ai.viewmodel.rankingWeight
import com.ai.data.toTransRankCellState
import com.ai.ui.helpers.RerankRow
import com.ai.ui.helpers.parseRerankRows
import com.ai.ui.report.view.helpers.ViewReportCache
import com.ai.ui.report.view.helpers.ViewTitleBar
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.formatCents
import com.ai.ui.shared.shortModelName
import com.ai.ui.shared.shortModelName2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ---------------------------------------------------------------------
// Value view — cost × quality (Pareto) frontier. Pure derivation from
// data already persisted: per-agent cost + a ranking. The ranking source
// is picked with the top switch — the report's Rerank, the Judge-the-judges
// consensus (Copeland over the panel's plurality verdicts), or any of the
// Tournament aggregation methods (all recomputed locally from stored
// verdicts / win matrices). No API calls. Reached from the View hub's
// "Value view" tile (shown when the report has a rerank, a tournament, OR a
// Judge-the-judges run).
// ---------------------------------------------------------------------

/** Which ranking feeds the quality axis. */
private sealed class RankSource(val label: String) {
    /** A single 0–1000 score per model, a weighted blend of every available
     *  ranking (Rerank / Judges / Translations / each Tournament method) using
     *  the weights from Settings → Ranking weights. First in the list. */
    object Combined : RankSource("Combined")
    object Rerank : RankSource("Rerank")
    /** Judge-the-judges: answers ranked by the panel's CONSENSUS verdict
     *  per match (Copeland over the consensus win matrix). One source, like
     *  Rerank — sits between Rerank and the Tournament methods. */
    object Judges : RankSource("Judges")
    /** A "Rank the translators" run — quality = each model's average score as a
     *  translator (matched onto the report's answer models). One per language;
     *  the chip shows the language name. Sits right after Rerank. */
    data class TransRank(val runId: String, val language: String) : RankSource(language)
    /** Compare with meta: quality = each answer's mean match % against the chosen
     *  meta result(s) (the result screen's first column). Sits after Judges. */
    object Compare : RankSource("Compare")
    /** The Tournament "Total" — quality is the inverse of each model's AVERAGE
     *  position across every method (the Tournament Total grid's ordering).
     *  Sits just before the individual Tournament methods. */
    object TournamentTotal : RankSource("Tournament")
    data class Tournament(val method: TournamentMethod) :
        RankSource(method.name.lowercase().replaceFirstChar { it.uppercase() })
}

/** Stable key for [rememberSaveable] selection + chip-selected matching. */
private fun RankSource.key(): String = when (this) {
    is RankSource.Combined -> "combined"
    is RankSource.Rerank -> "rerank"
    is RankSource.Judges -> "judges"
    is RankSource.Compare -> "compare"
    is RankSource.TransRank -> "transrank:$runId"
    is RankSource.TournamentTotal -> "tournament:total"
    is RankSource.Tournament -> "tournament:${method.name}"
}

/** A loaded "Rank the translators" run reduced to per-model quality scores. */
private data class TransRankSource(
    val runId: String,                       // sourceTranslationRunId
    val language: String,                    // native name preferred
    val scoreByModelKey: Map<String, Double> // "providerId|model" → avg score
)

/** Provider-alias-resolved model key, matching the fan-out fold-in. */
private fun vvModelKey(provider: String, model: String): String =
    "${(AppService.findById(provider)?.id ?: provider).lowercase()}|${model.trim().lowercase()}"

/** One model on the cost/quality plane. [costCents] = USD×100,
 *  [quality] = the ranking's REAL score (raw, model/method-scaled — NOT
 *  normalised to a rank position). */
private data class ValuePoint(
    val provider: String,
    val modelShort: String,
    val costCents: Double,
    val quality: Double,
    val dominated: Boolean,
    val bestValue: Boolean
)

/** Pair each SUCCESS agent with its ranking score + cost, then mark the
 *  Pareto-dominated points and the single best-value one. [rows] is the
 *  rerank-shaped ranking (`id` = 1-based SUCCESS position, the same
 *  numbering the Rerank flow and the Tournament view both use). Quality is
 *  the ranking's own score as-is; only when a row carries no score do we
 *  fall back to a rank-derived value.
 *
 *  [fanOutCostByAgentId] adds each agent's fan-out RESPONSE spend (USD) on
 *  top of its main report-answer cost — non-empty only when the fan-out
 *  answerer set matched the report's model set (see the caller). */
private fun buildValuePoints(
    report: Report,
    rows: List<RerankRow>,
    fanOutCostByAgentId: Map<String, Double> = emptyMap()
): List<ValuePoint> {
    val rowsById = rows.associateBy { it.id }
    val success = report.agents.filter {
        it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
    }
    val n = success.size
    // (agent, quality, costCents) for agents that have a ranking entry.
    data class Raw(val agentId: String, val provider: String, val modelShort: String, val quality: Double, val costCents: Double, val costKnown: Boolean)
    val raw = success.mapIndexedNotNull { idx, a ->
        val row = rowsById[idx + 1] ?: return@mapIndexedNotNull null
        val quality = row.score ?: row.rank?.let { (n - it + 1).toDouble() } ?: return@mapIndexedNotNull null
        // `knownBase` is null ONLY when the agent reports no price at all —
        // distinct from a real $0 (free / local) model, which keeps a known
        // cost of 0 and stays eligible for best value.
        val knownBase = a.cost ?: a.inputCost ?: a.outputCost
        val fanOut = fanOutCostByAgentId[a.agentId] ?: 0.0
        val baseCostUsd = a.cost ?: ((a.inputCost ?: 0.0) + (a.outputCost ?: 0.0))
        val costUsd = baseCostUsd + fanOut
        Raw(a.agentId, AppService.findById(a.provider)?.id ?: a.provider, shortModelName(a.model), quality, costUsd * 100.0, knownBase != null || fanOut > 0.0)
    }
    if (raw.isEmpty()) return emptyList()
    val eps = 1e-6
    // Best value = quality-per-cost; an UNKNOWN-cost model would score
    // quality/eps ≈ ∞ and steal the badge, so only priced models (costKnown)
    // are eligible. Unknown-cost points still plot (at cost 0).
    val bestId = raw
        .filter { it.costKnown }
        .filterNot { p -> raw.any { o -> o.agentId != p.agentId && o.quality >= p.quality && o.costCents <= p.costCents && (o.quality > p.quality || o.costCents < p.costCents) } }
        .maxByOrNull { it.quality / maxOf(it.costCents, eps) }
        ?.agentId
    return raw.map { p ->
        val dominated = raw.any { o -> o.agentId != p.agentId && o.quality >= p.quality && o.costCents <= p.costCents && (o.quality > p.quality || o.costCents < p.costCents) }
        ValuePoint(p.provider, p.modelShort, p.costCents, p.quality, dominated, p.agentId == bestId)
    }
}

/** Tournament "Total" → one RerankRow per model whose quality is the inverse of
 *  its AVERAGE position across every tournament method (lower mean position =
 *  better), matching the Tournament Total grid's ordering. */
private fun tournamentTotalRows(matrix: WinMatrix): List<RerankRow> {
    val methods = TournamentMethod.values()
    val rankByMethod = methods.associateWith { m -> rankFor(m, matrix).associate { it.id to it.rank } }
    val n = matrix.n
    return matrix.ids.map { id ->
        val ranks = methods.mapNotNull { rankByMethod[it]?.get(id) }
        val avg = if (ranks.isEmpty()) n.toDouble() else ranks.average()
        RerankRow(id, null, n - avg + 1.0, null)
    }
}

/** The "Combined" ranking: for every available + non-zero-weighted ranking
 *  (Rerank / Judges / Translations / each Tournament method), min-max-normalise
 *  its per-model scores to 0–1, then weight-average per model and scale to
 *  0–1000. Ids are 1-based SUCCESS positions. Empty when nothing is weighted. */
private fun buildCombinedRows(
    report: Report,
    rerankRows: List<RerankRow>,
    judgesMatrix: WinMatrix?,
    transRankRuns: List<TransRankSource>,
    tournamentMatrix: WinMatrix?,
    compareScoreByAgentId: Map<String, Int>,
    weightOf: (String) -> Int
): List<RerankRow> {
    val success = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    val n = success.size
    if (n == 0) return emptyList()
    // (weight, per-id raw score) for each contributing ranking.
    val rankings = mutableListOf<Pair<Int, Map<Int, Double>>>()
    weightOf("rerank").takeIf { it > 0 }?.let { w ->
        val sc = rerankRows.mapNotNull { r -> (r.score ?: r.rank?.let { (n - it + 1).toDouble() })?.let { r.id to it } }.toMap()
        if (sc.isNotEmpty()) rankings.add(w to sc)
    }
    weightOf("judges").takeIf { it > 0 }?.let { w ->
        judgesMatrix?.let { m ->
            rankFor(TournamentMethod.COPELAND, m).associate { it.id to it.score }
                .takeIf { it.isNotEmpty() }?.let { rankings.add(w to it) }
        }
    }
    weightOf("translations").takeIf { it > 0 }?.let { w ->
        if (transRankRuns.isNotEmpty()) {
            val sc = HashMap<Int, Double>()
            success.forEachIndexed { idx, a ->
                val key = vvModelKey(a.provider, a.model)
                val vs = transRankRuns.mapNotNull { it.scoreByModelKey[key] }
                if (vs.isNotEmpty()) sc[idx + 1] = vs.average()
            }
            if (sc.isNotEmpty()) rankings.add(w to sc)
        }
    }
    weightOf("compare").takeIf { it > 0 }?.let { w ->
        if (compareScoreByAgentId.isNotEmpty()) {
            val sc = HashMap<Int, Double>()
            success.forEachIndexed { idx, a ->
                compareScoreByAgentId[a.agentId]?.let { sc[idx + 1] = it.toDouble() }
            }
            if (sc.isNotEmpty()) rankings.add(w to sc)
        }
    }
    tournamentMatrix?.let { m ->
        TournamentMethod.values().forEach { method ->
            val w = weightOf(method.name)
            if (w > 0) {
                val sc = rankFor(method, m).associate { it.id to it.score }
                if (sc.isNotEmpty()) rankings.add(w to sc)
            }
        }
    }
    if (rankings.isEmpty()) return emptyList()
    val normed = rankings.map { (w, scores) ->
        val mn = scores.values.minOrNull() ?: 0.0; val mx = scores.values.maxOrNull() ?: 0.0
        w to scores.mapValues { (_, v) -> if (mx > mn) (v - mn) / (mx - mn) else 0.5 }
    }
    return (1..n).mapNotNull { id ->
        var num = 0.0; var den = 0.0
        normed.forEach { (w, norm) -> norm[id]?.let { num += w * it; den += w } }
        if (den > 0) RerankRow(id, null, (num / den) * 1000.0, null) else null
    }
}

@Composable
fun ValueViewScreen(reportId: String, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val reportDataVersion by ReportDataVersion.version.collectAsState()
    val secondaryDataVersion by SecondaryDataVersion.version.collectAsState()

    data class Loaded(
        val report: Report?,
        val rerankRows: List<RerankRow>,
        val rerankModel: String?,
        val tournamentMatrix: WinMatrix?,
        val tournamentDefaultMethod: TournamentMethod?,
        /** Consensus win matrix from the latest Judge-the-judges run, or null
         *  when none (or too few resolvable answers). */
        val judgesMatrix: WinMatrix?,
        /** Every "Rank the translators" run (one per language), each reduced to
         *  per-model average scores — added as ranking sources after Rerank. */
        val transRankRuns: List<TransRankSource>,
        val reportTitle: String?,
        /** Extra fan-out RESPONSE spend (USD) to fold into each agent's cost,
         *  keyed by agentId. Non-empty only when this report's fan-out
         *  answerer model set equals the report's success-model set; empty
         *  disables the fold. */
        val fanOutCostByAgentId: Map<String, Double>,
        /** True when [fanOutCostByAgentId] is folding fan-out cost in — drives
         *  the caption note so the higher numbers aren't a mystery. */
        val includesFanOut: Boolean,
        /** Compare-with-meta: agentId → mean match % (0..100) over the latest
         *  run — the result screen's first column, used as a quality source. */
        val compareScoreByAgentId: Map<String, Int>
    )
    val loadedState = produceState(
        Loaded(null, emptyList(), null, null, null, null, emptyList(), null, emptyMap(), false, emptyMap()),
        reportId, reportDataVersion, secondaryDataVersion
    ) {
        value = withContext(Dispatchers.IO) {
            val report = ViewReportCache.get(context, reportId)
            val rows = SecondaryResultStorage.listForReport(context, reportId)
            val rerank = rows
                .filter { it.kind == SecondaryKind.RERANK && !it.content.isNullOrBlank() }
                .maxByOrNull { it.timestamp }
            val rerankRows = rerank?.content?.let { parseRerankRows(it) } ?: emptyList()
            val aggRow = rows
                .filter { it.kind == SecondaryKind.TOURNAMENT && it.tournamentRole == "AGGREGATE" }
                .maxByOrNull { it.timestamp }
            val decoded = decodeTournamentMatrix(aggRow?.tournamentMatrix)
            // Judge-the-judges → a per-answer consensus ranking. Pick the
            // latest run (by its AGGREGATE row, else newest cell), fold its
            // judge cells' consensus through the tournament win matrix. The
            // ids are 1-based SUCCESS positions, the same numbering buildValuePoints uses.
            val judgesMatrix = run {
                val judgeCellRows = rows.filter {
                    it.kind == SecondaryKind.JUDGES && it.tournamentRole == "MATCH"
                }
                if (judgeCellRows.isEmpty()) return@run null
                val runId = rows
                    .filter { it.kind == SecondaryKind.JUDGES && it.tournamentRole == "AGGREGATE" }
                    .maxByOrNull { it.timestamp }?.tournamentJudgeRunId
                    ?: judgeCellRows.maxByOrNull { it.timestamp }?.tournamentJudgeRunId
                val cells = judgeCellRows
                    .filter { it.tournamentJudgeRunId == runId }
                    .mapNotNull { it.toJudgeCellState() }
                if (cells.isEmpty()) return@run null
                val successIds = report?.agents
                    ?.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    ?.mapIndexed { idx, a -> a.agentId to (idx + 1) }?.toMap() ?: emptyMap()
                judgesConsensusWinMatrix(cells) { successIds[it] }.takeIf { it.n >= 2 }
            }
            // Every "Rank the translators" run → per-translator-model average
            // scores. Grouped by the source translation run (one per language).
            val transRankRuns: List<TransRankSource> = run {
                val cellRows = rows.filter {
                    it.kind == SecondaryKind.TRANSRANK && it.tournamentRole == TRANSRANK_ROLE_CELL
                }
                if (cellRows.isEmpty()) return@run emptyList()
                cellRows.groupBy { it.translationRunId.orEmpty() }
                    .filterKeys { it.isNotBlank() }
                    .map { (srcRunId, group) ->
                        val ranking = aggregateTranslatorRanks(group.mapNotNull { it.toTransRankCellState() })
                        val first = group.first()
                        val lang = first.targetLanguageNative?.takeIf { it.isNotBlank() }
                            ?: first.targetLanguage?.takeIf { it.isNotBlank() } ?: "Translation"
                        TransRankSource(srcRunId, lang, ranking.associate { vvModelKey(it.providerId, it.model) to it.avgScore })
                    }
                    .filter { it.scoreByModelKey.isNotEmpty() }
                    .sortedBy { it.language.lowercase() }
            }
            // Fan-out cost fold-in. Only when the report's own models were ALSO
            // the fan-out answerers — i.e. the answerer model set equals the
            // report's success-model set — does it make sense to add each
            // model's fan-out RESPONSE spend onto its Value-view cost; a
            // partial-scope fan-out (some models didn't answer) would skew the
            // comparison, so we leave the map empty there. The on-disk pair row
            // stores only the answerer's (provider, model), so we match on that
            // (case-insensitive, alias-resolved), the same way FanOutEngine
            // hydration does. Icon/title Fan-Meta spend is excluded — those
            // aren't "responses".
            val successAgents = report?.agents?.filter {
                it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank()
            } ?: emptyList()
            fun modelKey(provider: String, model: String): String =
                "${(AppService.findById(provider)?.id ?: provider).lowercase()}|${model.trim().lowercase()}"
            val fanOutPairs = rows.filter {
                it.kind == SecondaryKind.META && it.fanOutSourceAgentId != null
            }
            val answererKeys = fanOutPairs.map { modelKey(it.providerId, it.model) }.toSet()
            val successKeyList = successAgents.map { modelKey(it.provider, it.model) }
            val successKeys = successKeyList.toSet()
            // Skip the fold-in when two success agents share a provider/model key:
            // they collapse to one key, so the per-key fan-out total would be
            // double-assigned to both agents. See audit bug 11.
            val noDuplicateModels = successKeyList.size == successKeys.size
            // Compare-with-meta: reduce the latest run's CELL rows to each
            // answer's mean match % (agentId → 0..100), the result screen's
            // first column. No AGGREGATE row exists, so average the cells here.
            val compareScoreByAgentId: Map<String, Int> = run {
                val byRun = rows.filter { it.kind == SecondaryKind.COMPARE && !it.compareRunId.isNullOrBlank() }
                    .groupBy { it.compareRunId!! }
                val group = byRun.maxByOrNull { (_, g) -> g.maxOf { it.timestamp } }?.value ?: return@run emptyMap()
                group.mapNotNull { it.toCompareCellState() }
                    .filter { it.percent != null }
                    .groupBy { it.agentId }
                    .mapValues { (_, cs) -> cs.sumOf { it.percent!! } / cs.size }
            }
            val fanOutCostByAgentId: Map<String, Double> =
                if (fanOutPairs.isNotEmpty() && successKeys.isNotEmpty() && noDuplicateModels && answererKeys == successKeys) {
                    val costByKey = fanOutPairs
                        .groupBy { modelKey(it.providerId, it.model) }
                        .mapValues { (_, list) -> list.sumOf { (it.inputCost ?: 0.0) + (it.outputCost ?: 0.0) } }
                    successAgents.associate { it.agentId to (costByKey[modelKey(it.provider, it.model)] ?: 0.0) }
                } else emptyMap()
            Loaded(
                report = report,
                rerankRows = rerankRows,
                rerankModel = rerank?.let { shortModelName(it.model) },
                tournamentMatrix = decoded?.first,
                tournamentDefaultMethod = decoded?.second,
                judgesMatrix = judgesMatrix,
                transRankRuns = transRankRuns,
                reportTitle = report?.barTitle,
                fanOutCostByAgentId = fanOutCostByAgentId,
                includesFanOut = fanOutCostByAgentId.isNotEmpty(),
                compareScoreByAgentId = compareScoreByAgentId
            )
        }
    }
    val loaded = loadedState.value

    // Ranking weights (Settings → Ranking weights) for the "Combined" source.
    val generalSettings = com.ai.ui.shared.LocalGeneralSettings.current
    val weightOf: (String) -> Int = { k ->
        generalSettings?.rankingWeight(k) ?: (com.ai.viewmodel.RANKING_WEIGHT_DEFAULTS[k] ?: 0)
    }
    // The Tournament "Total" rows + the weighted "Combined" rows, recomputed when
    // the loaded data or the weights change.
    val tournamentTotalRowList = remember(loaded) {
        loaded.tournamentMatrix?.let { tournamentTotalRows(it) } ?: emptyList()
    }
    val combinedRows = remember(loaded, generalSettings) {
        loaded.report?.let {
            buildCombinedRows(it, loaded.rerankRows, loaded.judgesMatrix, loaded.transRankRuns, loaded.tournamentMatrix, loaded.compareScoreByAgentId, weightOf)
        } ?: emptyList()
    }

    // Available ranking sources: Combined (if any weighted ranking exists) first,
    // then Rerank, the translator runs, Judges, the Tournament Total, and every
    // individual Tournament method.
    val sources = remember(loaded, combinedRows) {
        buildList {
            if (combinedRows.isNotEmpty()) add(RankSource.Combined)
            if (loaded.rerankRows.isNotEmpty()) add(RankSource.Rerank)
            // Every "Rank the translators" run sits right after Rerank, labelled
            // with its language.
            loaded.transRankRuns.forEach { add(RankSource.TransRank(it.runId, it.language)) }
            // Judges sits after those, before the Tournament methods.
            if (loaded.judgesMatrix != null) add(RankSource.Judges)
            // Compare with meta (match %) — after Judges.
            if (loaded.compareScoreByAgentId.isNotEmpty()) add(RankSource.Compare)
            if (loaded.tournamentMatrix != null) {
                add(RankSource.TournamentTotal)   // before the first method
                TournamentMethod.values().forEach { add(RankSource.Tournament(it)) }
            }
        }
    }
    var selectedKey by rememberSaveable(reportId) { mutableStateOf<String?>(null) }
    // Effective selection: the user's pick if still available, else Rerank,
    // else the tournament's stored method, else the first source.
    val selected = remember(sources, selectedKey, loaded.tournamentDefaultMethod) {
        sources.firstOrNull { it.key() == selectedKey }
            ?: sources.firstOrNull { it is RankSource.Rerank }
            ?: loaded.tournamentDefaultMethod?.let { dm ->
                sources.firstOrNull { it is RankSource.Tournament && it.method == dm }
            }
            ?: sources.firstOrNull()
    }

    val points = remember(loaded, selected, combinedRows, tournamentTotalRowList) {
        val report = loaded.report ?: return@remember emptyList<ValuePoint>()
        val rows = when (val s = selected) {
            is RankSource.Combined -> combinedRows
            is RankSource.TournamentTotal -> tournamentTotalRowList
            is RankSource.Rerank -> loaded.rerankRows
            is RankSource.Judges -> loaded.judgesMatrix?.let { m ->
                // Copeland (win-rate) over the consensus matrix — the robust,
                // interpretable default for a plurality-of-judges ranking.
                rankFor(TournamentMethod.COPELAND, m).map { rr -> RerankRow(rr.id, rr.rank, rr.score, rr.reason) }
            } ?: emptyList()
            is RankSource.Compare -> {
                // Each SUCCESS answer's mean match % (0..100) against the meta.
                report.agents
                    .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    .mapIndexedNotNull { idx, a ->
                        loaded.compareScoreByAgentId[a.agentId]?.let { RerankRow(idx + 1, null, it.toDouble(), null) }
                    }
            }
            is RankSource.TransRank -> {
                // Map each model's translator average score onto the report's
                // SUCCESS answer models (by provider/model); models that weren't
                // translators get no row and drop off the plot.
                val scoreByKey = loaded.transRankRuns.firstOrNull { it.runId == s.runId }?.scoreByModelKey ?: emptyMap()
                report.agents
                    .filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
                    .mapIndexedNotNull { idx, a ->
                        scoreByKey[vvModelKey(a.provider, a.model)]?.let { RerankRow(idx + 1, null, it, null) }
                    }
            }
            is RankSource.Tournament -> loaded.tournamentMatrix?.let { m ->
                rankFor(s.method, m).map { rr -> RerankRow(rr.id, rr.rank, rr.score, rr.reason) }
            } ?: emptyList()
            null -> emptyList()
        }
        buildValuePoints(report, rows, loaded.fanOutCostByAgentId)
    }
    val best = remember(points) { points.firstOrNull { it.bestValue } }

    val subject = when (val s = selected) {
        is RankSource.Combined -> "Combined · weighted 0–1000"
        is RankSource.Rerank -> loaded.rerankModel?.let { "ranked by $it" }
        is RankSource.Judges -> "Judge the judges · consensus"
        is RankSource.Compare -> "Compare with meta · match %"
        is RankSource.TransRank -> "Rank the translators · ${s.language}"
        is RankSource.TournamentTotal -> "Tournament · total of all methods"
        is RankSource.Tournament -> "Tournament · ${s.label}"
        null -> null
    }
    // Axis names for the chart. X = cost; Y = the active ranking.
    val rankingName = selected?.label ?: "Ranking"

    // Cycle the ranking source by [dir] (-1 prev / +1 next), wrapping with
    // no edge — drives the full-screen graph's left/right taps and keeps the
    // underlying chip selection in step.
    val cycleSource: (Int) -> Unit = { dir ->
        if (sources.isNotEmpty()) {
            val cur = sources.indexOfFirst { it.key() == selected?.key() }.coerceAtLeast(0)
            val next = ((cur + dir) % sources.size + sources.size) % sources.size
            selectedKey = sources[next].key()
        }
    }

    // Tap the chart → truly full-screen, chrome-less, zoomable graph,
    // rendered in its OWN Dialog window so it covers the app's title and
    // bottom icon bars (an early-return overlay would still sit inside
    // them). Left/right half taps cycle the ranking. See [ValueGraphFullScreen].
    var showFullGraph by rememberSaveable(reportId) { mutableStateOf(false) }
    if (showFullGraph && points.isNotEmpty()) {
        ValueGraphFullScreen(
            points = points,
            xAxisTitle = "Cost",
            yAxisTitle = rankingName,
            onPrev = { cycleSource(-1) },
            onNext = { cycleSource(1) },
            onBack = { showFullGraph = false }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        ViewTitleBar(
            reportTitle = loaded.reportTitle,
            screenTitle = "Value view",
            subject = subject,
            helpTopic = "value_view",
            onBack = onBack
        )

        // Ranking-source switch — Rerank + every available Tournament method.
        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sources.forEach { src ->
                    SourceChip(src.label, src.key() == selected?.key()) { selectedKey = src.key() }
                }
            }
        }

        if (points.isEmpty()) {
            Text(
                "No ranking yet. Run a Rerank, Tournament, Judge-the-judges, Rank-the-translators, or Compare-with-meta on this report first.",
                color = AppColors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            return@Column
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val fanOutNote = if (loaded.includesFanOut)
                " Cost includes each model's fan-out responses (every model here also answered the fan-out)." else ""
            Text(
                "Cost × quality. Top-left = cheap & good. ${com.ai.data.MetadataIconsHolder.current.gem} = best value; dimmed = dominated (another model is at least as good for less).$fanOutNote Tap the chart to expand — pinch to zoom, drag to pan.",
                color = AppColors.TextTertiary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
            ValueScatter(
                points, xAxisTitle = "Cost", yAxisTitle = rankingName,
                modifier = Modifier.fillMaxWidth().height(240.dp).padding(bottom = 12.dp),
                onTap = { showFullGraph = true }
            )
            best?.let {
                Text(
                    "${com.ai.data.MetadataIconsHolder.current.gem} Best value: ${it.provider} · ${it.modelShort} — score ${formatScore(it.quality)} at ${formatCents(it.costCents / 100.0)}",
                    color = AppColors.SuccessAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            val sorted = remember(points) {
                points.sortedWith(
                    compareByDescending<ValuePoint> { it.bestValue }
                        .thenBy { it.dominated }
                        .thenByDescending { it.quality }
                )
            }
            sorted.forEach { p -> ValueRow(p) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Chip in the ranking-source switch (Rerank / Copeland / Elo / …).
 *  Mirrors the Tournament view's method chip. */
@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) AppColors.AppBackground else AppColors.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) AppColors.PrimaryAccent else AppColors.CardBackground)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun ValueScatter(
    points: List<ValuePoint>,
    xAxisTitle: String,
    yAxisTitle: String,
    modifier: Modifier,
    onTap: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clickable { onTap() }
    ) {
        ValueScatterCanvas(points, xAxisTitle, yAxisTitle, Modifier.fillMaxSize().padding(12.dp))
    }
}

/** Bare scatter drawing — no Card chrome — so the full-screen view can
 *  render it edge-to-edge under a zoom/pan [graphicsLayer]. Both axes
 *  carry numeric tick values; the axis name sits centered under the X
 *  axis and, for the ranking, as vertical text down the left edge. */
@Composable
private fun ValueScatterCanvas(
    points: List<ValuePoint>,
    xAxisTitle: String,
    yAxisTitle: String,
    modifier: Modifier,
    /** Landscape full-screen: draw whole model names (no 14-char clip). */
    fullModelNames: Boolean = false,
    /** Scales the model-name label text (landscape full-screen bumps it). */
    labelScale: Float = 1f,
    /** Full-screen graph: bigger label font + greedy label-collision avoidance
     *  with leader lines (the inline thumbnail keeps the plain layout). */
    fullScreen: Boolean = false
) {
    if (points.isEmpty()) return
    val axis = AppColors.TextTertiary
    val bestC = AppColors.SuccessAccent
    val domC = AppColors.TextDim
    val regC = AppColors.WarningAccent
    val leaderColor = AppColors.TextSecondary.copy(alpha = 0.5f)
    val labelArgb = AppColors.TextSecondary.toArgb()
    val tickArgb = axis.toArgb()
    val titleArgb = AppColors.TextSecondary.toArgb()
    Canvas(modifier = modifier) {
        // Left/bottom padding leave room for the tick values + axis names.
        val padL = 100f; val padR = 48f; val padT = 16f; val padB = 52f
        val plotW = (size.width - padL - padR).coerceAtLeast(1f)
        val plotH = (size.height - padT - padB).coerceAtLeast(1f)
        val x0 = padL; val y0 = padT

        val minCost = points.minOf { it.costCents }
        val maxCost = points.maxOf { it.costCents }
        val minQ = points.minOf { it.quality }
        val maxQ = points.maxOf { it.quality }
        // Pad each axis by 10% of its range on BOTH ends so no point ever
        // sits on an axis line / edge. The ticks below still show the real
        // data values (positioned inside the padded range).
        val costRange = maxCost - minCost
        val qRange = maxQ - minQ
        val costPad = if (costRange > 1e-9) costRange * 0.10 else (kotlin.math.abs(maxCost) * 0.10).coerceAtLeast(0.5)
        val qPad = if (qRange > 1e-9) qRange * 0.10 else (kotlin.math.abs(maxQ) * 0.10).coerceAtLeast(0.5)
        val plotMinCost = minCost - costPad
        val plotMinQ = minQ - qPad
        val costSpan = (maxCost + costPad - plotMinCost).coerceAtLeast(1e-9)
        val qSpan = (maxQ + qPad - plotMinQ).coerceAtLeast(1e-9)

        // px position: x grows with cost (cheap left), y inverted (high quality at top)
        fun fxOf(costCents: Double) = ((costCents - plotMinCost) / costSpan).toFloat()
        fun fyOf(q: Double) = ((q - plotMinQ) / qSpan).toFloat()
        fun px(p: ValuePoint): Offset =
            Offset(x0 + fxOf(p.costCents) * plotW, y0 + (1f - fyOf(p.quality)) * plotH)

        // axes
        drawLine(axis, Offset(x0, y0), Offset(x0, y0 + plotH), strokeWidth = 2f)
        drawLine(axis, Offset(x0, y0 + plotH), Offset(x0 + plotW, y0 + plotH), strokeWidth = 2f)

        val nc = drawContext.canvas.nativeCanvas

        // Dots first, so the labels (+ their leader lines) draw on top.
        fun dotRadius(p: ValuePoint) = if (p.bestValue) 20f else if (p.dominated) 7f else 9f
        points.forEach { p ->
            val o = px(p)
            when {
                p.bestValue -> {
                    drawCircle(bestC, radius = 12f, center = o)
                    drawCircle(bestC.copy(alpha = 0.35f), radius = 20f, center = o)
                }
                p.dominated -> drawCircle(domC, radius = 7f, center = o)
                else -> drawCircle(regC, radius = 9f, center = o)
            }
        }
        // Model-name labels. Dots that sit almost on top of one another are
        // grouped and their labels FANNED OUT symmetrically around the cluster
        // (each with its own leader line) — so when two dots coincide, one label
        // goes a little up and the other a little down instead of one staying put
        // and the other jumping far. An isolated dot keeps the plain right-of-dot
        // spot and only moves (with a line) when it would overlap something.
        val labelPaint = android.graphics.Paint().apply {
            color = labelArgb; textSize = (if (fullScreen) 27f else 22f) * labelScale; isAntiAlias = true
        }
        val fm = labelPaint.fontMetrics
        val ascent = -fm.ascent; val descent = fm.descent; val lineH = ascent + descent
        val gap = 8f
        val canvasW = size.width; val canvasH = size.height
        val os = points.map { px(it) }
        val rs = points.map { dotRadius(it) }
        // Full-screen graph: trim -latest / date suffixes (shortModelName2), and
        // also drop -preview / -exp channel tags — but only where that stays
        // unique among the plotted points. ~7 catalog pairs (e.g. gemini-2.5-pro
        // vs …-pro-preview) would otherwise collide onto one label, so when both
        // a preview and its GA twin are on the chart each keeps its tag.
        val names = if (!fullScreen) {
            points.map { if (fullModelNames) it.modelShort else it.modelShort.take(14) }
        } else {
            val safe = points.map { shortModelName2(it.modelShort) }
            val agg = safe.map { it.removeSuffix("-preview").removeSuffix("-exp") }
            val aggCount = agg.groupingBy { it }.eachCount()
            safe.indices.map { i ->
                val nm = if (aggCount[agg[i]] == 1) agg[i] else safe[i]
                if (fullModelNames) nm else nm.take(14)
            }
        }
        val ws = names.map { labelPaint.measureText(it) }
        val placed = ArrayList<android.graphics.RectF>()
        // Seed with the dots so labels dodge every marker, not just each other.
        os.forEachIndexed { i, o -> val r = rs[i]; placed.add(android.graphics.RectF(o.x - r, o.y - r, o.x + r, o.y + r)) }
        val finalX = FloatArray(points.size); val finalY = FloatArray(points.size); val finalLeader = BooleanArray(points.size)
        // First free candidate that stays on-canvas and clears every placed rect.
        fun choose(cands: List<Pair<Float, Float>>, w: Float): Int {
            for ((ci, c) in cands.withIndex()) {
                val r = android.graphics.RectF(c.first, c.second - ascent, c.first + w, c.second + descent)
                if (r.left < 2f || r.top < 2f || r.right > canvasW - 2f || r.bottom > canvasH - 2f) continue
                if (placed.none { android.graphics.RectF.intersects(it, r) }) return ci
            }
            return -1
        }
        fun commit(i: Int, lx: Float, ly: Float, leader: Boolean) {
            finalX[i] = lx; finalY[i] = ly; finalLeader[i] = leader
            placed.add(android.graphics.RectF(lx, ly - ascent, lx + ws[i], ly + descent))
        }
        // Union near-coincident dots into clusters.
        val clusterRadius = lineH * 1.4f
        val parent = IntArray(points.size) { it }
        fun root(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in points.indices) for (j in i + 1 until points.size) {
            if ((os[i] - os[j]).getDistance() < clusterRadius) parent[root(i)] = root(j)
        }
        // Larger clusters first so their fanned slots claim space before singletons.
        val groups = points.indices.groupBy { root(it) }.values.sortedByDescending { it.size }
        groups.forEach { idxs ->
            if (idxs.size == 1) {
                val i = idxs[0]; val o = os[i]; val R = rs[i]; val w = ws[i]; val baseY = o.y + ascent * 0.35f
                val cands = listOf(
                    o.x + R + gap to baseY,                  // right (default)
                    o.x + R + gap to o.y - lineH * 0.7f,     // right-up
                    o.x + R + gap to o.y + lineH * 1.0f,     // right-down
                    o.x + R + gap to o.y - lineH * 1.8f,     // right-up2
                    o.x + R + gap to o.y + lineH * 2.1f,     // right-down2
                    o.x - R - gap - w to baseY,              // left
                    o.x - R - gap - w to o.y - lineH * 0.7f, // left-up
                    o.x - R - gap - w to o.y + lineH * 1.0f, // left-down
                    o.x - w * 0.5f to o.y - R - gap,         // above
                    o.x - w * 0.5f to o.y + R + gap + ascent // below
                )
                val ci = choose(cands, w)
                val c = if (ci < 0) cands[0] else cands[ci]
                commit(i, c.first, c.second, leader = ci != 0)   // moved or fallback → line
            } else {
                // Fan the cluster's labels vertically, centered on the centroid,
                // to the right of the rightmost dot; top dot → top label. Every
                // clustered label gets a leader line.
                val members = idxs.sortedBy { os[it].y }
                val k = members.size
                val cy = members.map { os[it].y }.average().toFloat()
                val anchorX = members.maxOf { os[it].x + rs[it] } + gap
                val spacing = lineH * 1.05f
                members.forEachIndexed { slot, i ->
                    val w = ws[i]
                    val targetY = cy + (slot - (k - 1) / 2.0f) * spacing + ascent * 0.35f
                    val cands = listOf(
                        anchorX to targetY,
                        anchorX to targetY - spacing * 0.5f,
                        anchorX to targetY + spacing * 0.5f,
                        anchorX + w * 0.25f to targetY,
                        os[i].x - rs[i] - gap - w to targetY   // left fallback
                    )
                    val ci = choose(cands, w)
                    val c = if (ci < 0) cands[0] else cands[ci]
                    commit(i, c.first, c.second, leader = true)
                }
            }
        }
        // Draw the leader lines + names (dots are already down, so labels sit on top).
        points.indices.forEach { i ->
            if (finalLeader[i]) {
                val target = Offset(finalX[i], finalY[i] - ascent + lineH / 2f)
                val dir = target - os[i]; val len = dir.getDistance()
                val startPt = if (len > rs[i]) os[i] + dir * (rs[i] / len) else os[i]
                drawLine(leaderColor, startPt, target, strokeWidth = 1.5f)
            }
            nc.drawText(names[i], finalX[i], finalY[i], labelPaint)
        }

        // --- X-axis numeric ticks (cost) at left / mid / right ---
        val tickPaint = android.graphics.Paint().apply {
            color = tickArgb; textSize = 20f; isAntiAlias = true
        }
        val costTicks = listOf(minCost, (minCost + maxCost) / 2.0, maxCost)
        costTicks.forEachIndexed { i, v ->
            val cx = x0 + fxOf(v) * plotW
            drawLine(axis, Offset(cx, y0 + plotH), Offset(cx, y0 + plotH + 5f), strokeWidth = 1.5f)
            tickPaint.textAlign = when (i) {
                0 -> android.graphics.Paint.Align.LEFT
                costTicks.lastIndex -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            nc.drawText(formatCents(v / 100.0), cx, y0 + plotH + 24f, tickPaint)
        }

        // --- Y-axis numeric ticks (ranking score) at bottom / mid / top ---
        tickPaint.textAlign = android.graphics.Paint.Align.RIGHT
        listOf(minQ, (minQ + maxQ) / 2.0, maxQ).forEach { v ->
            val cy = y0 + (1f - fyOf(v)) * plotH
            drawLine(axis, Offset(x0 - 5f, cy), Offset(x0, cy), strokeWidth = 1.5f)
            nc.drawText(formatScore(v), x0 - 10f, cy + 7f, tickPaint)
        }

        // --- axis names: X centered under its ticks, Y vertical on the left ---
        val titlePaint = android.graphics.Paint().apply {
            color = titleArgb; textSize = 24f; isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        nc.drawText(xAxisTitle, x0 + plotW / 2f, y0 + plotH + 47f, titlePaint)
        val yTitleX = 20f
        val yTitleY = y0 + plotH / 2f
        nc.save()
        nc.rotate(-90f, yTitleX, yTitleY)
        nc.drawText(yAxisTitle, yTitleX, yTitleY, titlePaint)
        nc.restore()
    }
}

/** Truly full-screen, chrome-less graph reached by tapping the chart.
 *  Rendered in its own Dialog window so it covers the app's title bar and
 *  bottom icon bar (a plain overlay sits inside them). The Android system
 *  bars are hidden for the lifetime of the dialog and restored
 *  automatically when it's dismissed. The whole window is the scatter,
 *  pinch-zoomed and panned. Tapping the left / right half steps the ranking
 *  ([onPrev] / [onNext], wrapping). In landscape the model labels show full
 *  names in a slightly larger font. */
@Composable
private fun ValueGraphFullScreen(
    points: List<ValuePoint>,
    xAxisTitle: String,
    yAxisTitle: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Hide the system bars on the DIALOG's own window so the graph is
        // edge-to-edge; dismissing the dialog restores the activity's bars.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            dialogWindow?.let { w ->
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {
                // Restore the bars on dismiss in case this dialog shared the
                // activity's window — don't rely on the window being torn down.
                dialogWindow?.let { w ->
                    WindowInsetsControllerCompat(w, w.decorView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // Reset zoom/pan whenever the ranking changes (keyed on the Y title)
        // so each ranking opens fresh.
        var scale by remember(yAxisTitle) { mutableStateOf(1f) }
        var offset by remember(yAxisTitle) { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier.fillMaxSize()
                .background(AppColors.AppBackground)
                // One detector disambiguates tap-cycle from pinch/pan so a
                // fumbled zoom can't flip the ranking (which also reset the
                // zoom, since scale/offset are keyed on yAxisTitle). A gesture
                // that crosses touch-slop is a transform; otherwise it's a tap:
                // left half → previous ranking, right half → next (wraps).
                // Keyed on yAxisTitle so it recaptures onPrev/onNext each cycle.
                // See audit report bug 14.
                .pointerInput(yAxisTitle) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var pastSlop = false
                        var canceled = false
                        do {
                            val event = awaitPointerEvent()
                            canceled = event.changes.any { it.isConsumed }
                            if (!canceled) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (!pastSlop) {
                                    val zoomMotion = abs(1f - zoom) * event.calculateCentroidSize(useCurrent = false)
                                    if (zoomMotion > viewConfiguration.touchSlop ||
                                        pan.getDistance() > viewConfiguration.touchSlop) pastSlop = true
                                }
                                if (pastSlop) {
                                    scale = (scale * zoom).coerceIn(1f, 8f)
                                    offset += pan
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (!canceled && event.changes.any { it.pressed })
                        // Never crossed slop and wasn't consumed → a real tap.
                        if (!pastSlop && !canceled) {
                            if (down.position.x < size.width / 2f) onPrev() else onNext()
                        }
                    }
                }
        ) {
            ValueScatterCanvas(
                points, xAxisTitle, yAxisTitle,
                Modifier.fillMaxSize().padding(12.dp).graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y
                ),
                fullModelNames = isLandscape,
                labelScale = if (isLandscape) 1.35f else 1f,
                fullScreen = true
            )
        }
    }
}

@Composable
private fun ValueRow(p: ValuePoint) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBackground),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${p.provider} · ${p.modelShort}", color = AppColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatCents(p.costCents / 100.0)}   ·   score ${formatScore(p.quality)}",
                    color = AppColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            val (badge, color) = when {
                p.bestValue -> "${com.ai.data.MetadataIconsHolder.current.gem} Best value" to AppColors.SuccessAccent
                !p.dominated -> "Pareto" to AppColors.InfoAccent
                else -> "dominated" to AppColors.TextDim
            }
            Text(badge, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Real ranking score, max 1 decimal — whole numbers (e.g. Elo 1500) drop
 *  the decimal, fractional ones round to one place. */
private fun formatScore(q: Double): String =
    if (q == q.toLong().toDouble()) q.toLong().toString() else String.format(java.util.Locale.US, "%.1f", q)
