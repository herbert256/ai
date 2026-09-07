package com.ai.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private val scoreFractionRegex = Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)""")
private val scoreNumberRegex = Regex("""\d+(?:\.\d+)?""")

/** Extract a 0–100 score from a text fragment, normalising an "N/M" reply to
 *  N/M·100 (8/10 → 80, 85/100 → 85) so scores on different scales aren't
 *  averaged at face value (8 vs 80). A bare number is taken as-is (the prompt
 *  asks for 0–100); the fraction form is the only unambiguous scale signal. */
fun scoreOnScale(fragment: String): Int? {
    scoreFractionRegex.find(fragment)?.let { mr ->
        val num = mr.groupValues[1].toDoubleOrNull()
        val den = mr.groupValues[2].toDoubleOrNull()
        if (num != null && den != null && den > 0.0) return (num / den * 100.0).toInt().coerceIn(0, 100)
    }
    return scoreNumberRegex.find(fragment)?.value?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)
}

/**
 * Value types for the "Rank the translators" batch (SecondaryKind.TRANSRANK).
 *
 * The batch reuses an existing translation run: every long-form translated
 * item (agent answers + fan-out / meta responses) is scored 0–100 by a panel
 * of judge models — every model in the `translate-rank` worker swarm EXCEPT
 * the model that produced that item. One CELL row per (item × judge); one
 * AGGREGATE row holds the per-translator-model ranking.
 *
 * Field reuse on [SecondaryResult] for a CELL (no new fields):
 *  - `providerId`/`model`     = the JUDGE
 *  - `tournamentRole`         = [TRANSRANK_ROLE_CELL] / [TRANSRANK_ROLE_AGGREGATE]
 *  - `tournamentJudgeRunId`/`runId` = the transrank run id
 *  - `translationRunId`       = the SOURCE translation run being ranked
 *  - `compareToResultId`      = the scored TRANSLATE row id
 *  - `matchResponseAId`/`matchResponseBId` = the translator's provider / model
 *  - `targetLanguage`/`targetLanguageNative` = the language
 *  - `content`                = the judge's two-line reply (score + reason)
 */

const val TRANSRANK_ROLE_CELL = "MATCH"
const val TRANSRANK_ROLE_AGGREGATE = "AGGREGATE"

/** Max scoring cells (item × judge pairs) per translator model — caps the
 *  whole batch at (#translators × this). With 10 translator models → ≤ 250
 *  cells. Not every translation of a big run is judged by every model. */
const val TRANSRANK_CELLS_PER_TRANSLATOR = 25

/** "$judge|$translationRowId" — unique per cell. */
fun transRankCellKey(judgeProviderId: String, judgeModel: String, translationRowId: String): String =
    "$judgeProviderId/$judgeModel|$translationRowId"

typealias TransRankCellStatus = BatchItemStatus

/** One judge's 0–100 score of one translated item. */
data class TransRankCellState(
    override val id: String,                 // SecondaryResult.id
    val judgeProviderId: String,             // the scoring judge
    val judgeModel: String,
    val translatorProviderId: String,        // the model that produced the translation
    val translatorModel: String,
    val translationRowId: String,            // the scored TRANSLATE row id
    val sourceTranslationRunId: String,      // the translation run being ranked
    val targetLanguage: String?,
    override val status: TransRankCellStatus,
    /** Parsed from content: 0–100, or null. */
    val score: Int? = null,
    val reason: String? = null,
    val content: String? = null,
    val errorMessage: String? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null,
    val tokenUsage: TokenUsage? = null,
    val traceFile: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) : BatchItem<String> {
    val judgeKey: String get() = "$judgeProviderId/$judgeModel"
    val translatorKey: String get() = "$translatorProviderId/$translatorModel"
    override val key: String get() = transRankCellKey(judgeProviderId, judgeModel, translationRowId)
    override val totalCost: Double get() = (inputCost ?: 0.0) + (outputCost ?: 0.0)
}

/** Run key = "$reportId|$sourceTranslationRunId" — one ranking per language. */
typealias TransRankRunKey = String

fun transRankRunKey(reportId: String, sourceTranslationRunId: String): TransRankRunKey =
    "$reportId|$sourceTranslationRunId"

data class TransRankRunState(
    val key: TransRankRunKey,
    override val reportId: String,
    /** Per-run UUID on [SecondaryResult.tournamentJudgeRunId]. */
    val runId: String,
    val sourceTranslationRunId: String,
    val targetLanguageName: String,
    val targetLanguageNative: String,
    val prompt: com.ai.model.InternalPrompt,
    val cells: Map<String, TransRankCellState> = emptyMap(),
    val aggregateRowId: String? = null,
    val cancelled: Boolean = false
) : BatchRun<String, TransRankCellState> {
    override val items: Map<String, TransRankCellState> get() = cells
    val translatorKeys: List<String> get() = cells.values.map { it.translatorKey }.distinct()
}

/** Reverse-map a persisted TRANSRANK CELL row into a [TransRankCellState].
 *  Returns null for rows that aren't transrank cells. */
fun SecondaryResult.toTransRankCellState(): TransRankCellState? {
    if (kind != SecondaryKind.TRANSRANK || tournamentRole != TRANSRANK_ROLE_CELL) return null
    val translationRowId = compareToResultId ?: return null
    val translatorProvider = matchResponseAId ?: return null
    val translatorModel = matchResponseBId ?: return null
    val status = when {
        errorMessage != null -> TransRankCellStatus.ERROR
        !content.isNullOrBlank() -> TransRankCellStatus.DONE
        else -> TransRankCellStatus.PENDING
    }
    val parsed = parseScoreAndReason(content)
    return TransRankCellState(
        id = id,
        judgeProviderId = providerId,
        judgeModel = model,
        translatorProviderId = translatorProvider,
        translatorModel = translatorModel,
        translationRowId = translationRowId,
        sourceTranslationRunId = translationRunId.orEmpty(),
        targetLanguage = targetLanguage,
        status = status,
        score = parsed?.first,
        reason = parsed?.second,
        content = content,
        errorMessage = errorMessage,
        inputCost = inputCost,
        outputCost = outputCost,
        durationMs = durationMs,
        tokenUsage = tokenUsage,
        traceFile = traceFile,
        timestamp = timestamp
    )
}

/** Parse the two-line judge reply: line 1 = a 0–100 score, line 2 = a short
 *  motivation. Tolerant of a "Score:" label, a "85/100" form, or a JSON
 *  `{"score","reason"}` fallback. Returns null when nothing parses. */
fun parseScoreAndReason(content: String?): Pair<Int?, String?>? {
    if (content.isNullOrBlank()) return null
    val cleaned = content.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    // JSON form first.
    runCatching {
        val obj = JsonParser.parseString(cleaned).asJsonObject
        if (obj.has("score")) {
            // Type-check before reading: a structured score (`{"value":82}`)
            // or array would make `.asString` throw, dropping us into the
            // plaintext parser, which could misread a number out of the
            // reason. Once we're in a valid JSON object we commit to it —
            // a structured score just yields a missing score, never a guess.
            val scoreEl = obj.get("score")
            val s = when {
                scoreEl != null && scoreEl.isJsonPrimitive && scoreEl.asJsonPrimitive.isNumber ->
                    scoreEl.asInt.coerceIn(0, 100)
                scoreEl != null && scoreEl.isJsonPrimitive ->
                    scoreEl.asString.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)
                else -> null
            }
            val r = obj.get("reason")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            return s to r
        }
    }
    val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val firstLine = lines[0].substringAfter(":", lines[0])
    // Take the score from the first line, or from an explicitly "score"-labelled
    // line — NEVER from an arbitrary number elsewhere in the reply. Scanning the
    // whole body would read a reason like "2 strong points" as the score. See
    // audit bug 9. scoreOnScale normalises an "N/M" reply (8/10 → 80, 85/100 →
    // 85) instead of grabbing the bare numerator 8 and comparing it against
    // another judge's 80 as-is.
    val score = scoreOnScale(firstLine)
        ?: lines.firstOrNull { it.contains("score", ignoreCase = true) }
            ?.substringAfter(":", "")
            ?.let { scoreOnScale(it) }
    val reason = lines.getOrNull(1)?.let { it.substringAfter(":", it) }?.trim()?.takeIf { it.isNotBlank() }
        ?: lines.drop(1).joinToString(" ").takeIf { it.isNotBlank() }
    if (score == null && reason == null) return null
    return score to reason
}

/** One row of the translator ranking — a translator model with its average
 *  score across the items it produced (judged by the OTHER models). */
data class TranslatorRankRow(
    val providerId: String,
    val model: String,
    val avgScore: Double,
    val itemCount: Int,    // distinct translated items this model produced (that got ≥1 score)
    val judgedCount: Int   // total scores received
) {
    val translatorKey: String get() = "$providerId/$model"
}

/** Average each translator's scores (over cells where judge ≠ translator),
 *  sorted best-first. */
fun aggregateTranslatorRanks(cells: Collection<TransRankCellState>): List<TranslatorRankRow> =
    cells.groupBy { it.translatorKey }
        .mapNotNull { (_, cs) ->
            // Scores come only from OTHER models scoring this translator's items.
            val scores = cs.filter { it.judgeKey != it.translatorKey }.mapNotNull { it.score }
            // No usable score yet → no rank to show for this translator.
            if (scores.isEmpty()) return@mapNotNull null
            val first = cs.first()
            TranslatorRankRow(
                providerId = first.translatorProviderId,
                model = first.translatorModel,
                avgScore = cs.filter { it.judgeKey != it.translatorKey && it.score != null }
                    .groupBy { it.translationRowId }.values.map { group -> group.mapNotNull { it.score }.average() }.average(),
                // Distinct translated items this model produced — counted over
                // ALL its cells (matching the L2 "Item N" list), not just the
                // ones already carrying a parsed score, so the count doesn't
                // dip while a judge cell is pending or errored.
                itemCount = cs.map { it.translationRowId }.distinct().size,
                judgedCount = scores.size
            )
        }
        .sortedWith(compareBy<TranslatorRankRow> { it.providerId.lowercase() }.thenBy { it.model.lowercase() })

fun List<TranslatorRankRow>.toTransRankJson(): String {
    val arr = JsonArray()
    forEachIndexed { i, r ->
        arr.add(JsonObject().apply {
            addProperty("interpretation", "Translation review; not a comparable model rank")
            addProperty("provider", r.providerId)
            addProperty("model", r.model)
            addProperty("avgScore", r.avgScore)
            addProperty("items", r.itemCount)
            addProperty("judged", r.judgedCount)
        })
    }
    return arr.toString()
}
