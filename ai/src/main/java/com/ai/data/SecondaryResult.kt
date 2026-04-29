package com.ai.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class SecondaryKind { RERANK, SUMMARIZE, COMPARE }

/**
 * A meta-result that operates on a parent Report's per-agent outputs:
 * a Rerank ranks them, a Summarize fuses them into one. Each is the work
 * of a single chosen model, persisted independently so a report can
 * accumulate many of either kind over time.
 */
data class SecondaryResult(
    val id: String,
    val reportId: String,
    val kind: SecondaryKind,
    val providerId: String,
    val model: String,
    val agentName: String,
    val timestamp: Long,
    val content: String?,
    val errorMessage: String? = null,
    val tokenUsage: TokenUsage? = null,
    val inputCost: Double? = null,
    val outputCost: Double? = null,
    val durationMs: Long? = null
)

/**
 * Persists [SecondaryResult]s as <filesDir>/secondary/<reportId>/<resultId>.json.
 * Mirrors [ReportStorage]'s init/lock pattern. The per-report subdirectory keeps
 * deletion of the parent report (and its meta-results) a single rmdir.
 */
object SecondaryResultStorage {
    private const val SECONDARY_DIR = "secondary"
    private val gson = createAppGson()
    private val lock = ReentrantLock()
    @Volatile private var rootDir: File? = null

    fun init(context: Context) {
        if (rootDir == null) lock.withLock {
            if (rootDir == null) {
                val dir = File(context.filesDir, SECONDARY_DIR)
                if (!dir.exists()) dir.mkdirs()
                rootDir = dir
            }
        }
    }

    private fun reportDir(reportId: String): File? {
        val root = rootDir ?: return null
        val dir = File(root, reportId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun save(context: Context, result: SecondaryResult): SecondaryResult {
        init(context)
        lock.withLock {
            val dir = reportDir(result.reportId) ?: return result
            File(dir, "${result.id}.json").writeTextAtomic(gson.toJson(result))
        }
        return result
    }

    fun create(
        context: Context, reportId: String, kind: SecondaryKind,
        providerId: String, model: String, agentName: String
    ): SecondaryResult {
        val r = SecondaryResult(
            id = UUID.randomUUID().toString(),
            reportId = reportId, kind = kind,
            providerId = providerId, model = model, agentName = agentName,
            timestamp = System.currentTimeMillis(), content = null
        )
        return save(context, r)
    }

    fun listForReport(context: Context, reportId: String, kind: SecondaryKind? = null): List<SecondaryResult> {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock emptyList()
            if (!dir.exists()) return@withLock emptyList()
            dir.listFiles { f -> f.extension == "json" }?.mapNotNull { file ->
                try { gson.fromJson(file.readText(), SecondaryResult::class.java) } catch (_: Exception) { null }
            }?.let { items ->
                if (kind != null) items.filter { it.kind == kind } else items
            }?.sortedBy { it.timestamp } ?: emptyList()
        }
    }

    fun get(context: Context, reportId: String, resultId: String): SecondaryResult? {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock null
            val file = File(dir, "$resultId.json")
            if (!file.exists()) return@withLock null
            try { gson.fromJson(file.readText(), SecondaryResult::class.java) } catch (_: Exception) { null }
        }
    }

    fun delete(context: Context, reportId: String, resultId: String) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            File(dir, "$resultId.json").delete()
        }
    }

    fun deleteAllForReport(context: Context, reportId: String) {
        init(context)
        lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    /** Counts persisted across all kinds for a report. Used by the Report
     *  result screen to decide whether to surface the View Reranks /
     *  Summaries / Compares buttons without paying for the full file parse
     *  on every recomposition. */
    data class Counts(val rerank: Int, val summarize: Int, val compare: Int)
    fun countForReport(context: Context, reportId: String): Counts {
        init(context)
        return lock.withLock {
            val dir = rootDir?.let { File(it, reportId) } ?: return@withLock Counts(0, 0, 0)
            if (!dir.exists()) return@withLock Counts(0, 0, 0)
            var rerank = 0; var summarize = 0; var compare = 0
            dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                try {
                    val r = gson.fromJson(file.readText(), SecondaryResult::class.java)
                    when (r.kind) {
                        SecondaryKind.RERANK -> rerank++
                        SecondaryKind.SUMMARIZE -> summarize++
                        SecondaryKind.COMPARE -> compare++
                    }
                } catch (_: Exception) {}
            }
            Counts(rerank, summarize, compare)
        }
    }
}

/**
 * Hardcoded fallback prompts. Used when the user hasn't supplied an override
 * via GeneralSettings.rerankPrompt / summarizePrompt. Placeholders match the
 * existing @VAR@ convention used elsewhere — substituted by [resolveSecondaryPrompt].
 */
object SecondaryPrompts {
    const val DEFAULT_RERANK = """You are an impartial judge ranking @COUNT@ AI responses to the same question.

QUESTION:
@QUESTION@

RESPONSES:
@RESULTS@

Evaluate each response on accuracy, completeness, clarity, and usefulness.
Output ONLY a JSON array — no prose, no code fences — of the form:

[
  {"id": 1, "rank": 1, "score": 92, "reason": "..."},
  {"id": 2, "rank": 2, "score": 78, "reason": "..."}
]

`id` must match the bracketed [N] in RESPONSES.
`rank` is 1 for the best response, 2 for second, etc. — every id appears exactly once.
`score` is 0-100. `reason` is one sentence."""

    const val DEFAULT_SUMMARIZE = """The following are @COUNT@ AI responses to the same question. Synthesize a single,
authoritative answer that captures the best of all responses.

QUESTION:
@QUESTION@

RESPONSES:
@RESULTS@

Guidelines:
- Combine the strongest, most accurate points from each response.
- Resolve contradictions explicitly; if responses disagree on a fact, say so.
- Drop redundancy. Do not repeat the question.
- Match the tone and depth of the original responses.
- Do not mention that you are summarizing or reference the source responses by number."""

    const val DEFAULT_COMPARE = """You are comparing @COUNT@ AI responses to the same question. Identify where they agree and where they diverge.

QUESTION:
@QUESTION@

RESPONSES:
@RESULTS@

Produce a comparative analysis with these sections:

## Points of agreement
Claims, conclusions, or recommendations that most or all responses share. Reference responses by [N] where relevant.

## Points of disagreement
Where responses diverge. For each disagreement, describe each side and which response(s) hold which view, referencing them by [N]. Where possible, indicate which view is better supported and why.

## Unique contributions
Facts, framings, or arguments that only one response raised. Note the source [N] and whether the contribution adds genuine value.

## Overall takeaway
One paragraph synthesising what a careful reader should conclude given the points of agreement and disagreement above."""
}

/** Substitutes placeholders in [template] using the values for the
 *  current secondary-result run. `@RESULTS@` arrives pre-formatted from
 *  the caller — we only do plain string replace here. */
fun resolveSecondaryPrompt(
    template: String, question: String, results: String, count: Int,
    title: String? = null
): String {
    val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
    return template
        .replace("@QUESTION@", question)
        .replace("@RESULTS@", results)
        .replace("@COUNT@", count.toString())
        .replace("@DATE@", now)
        .replace("@TITLE@", title ?: "")
}

/** Build the @RESULTS@ block: per-agent text, prefixed with `[N]
 *  provider=<id> model=<id>`. The bracketed N is the stable id rerank
 *  models echo back — and the anchor target HTML export wires up
 *  links to. */
fun buildResultsBlock(report: Report): String {
    val sb = StringBuilder()
    val successful = report.agents.filter { it.reportStatus == ReportStatus.SUCCESS && !it.responseBody.isNullOrBlank() }
    successful.forEachIndexed { idx, agent ->
        val provider = AppService.findById(agent.provider)?.id ?: agent.provider
        sb.append("[").append(idx + 1).append("] provider=").append(provider)
            .append(" model=").append(agent.model).append('\n')
        sb.append(agent.responseBody?.trim() ?: "")
        if (idx != successful.lastIndex) sb.append("\n\n")
    }
    return sb.toString()
}
