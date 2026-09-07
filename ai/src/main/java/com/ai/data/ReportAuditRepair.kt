package com.ai.data

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs

/** Evidence-based repair for version-3 report ledgers. Existing call IDs,
 * frozen prices, rejected attempts and accumulated spend are never rebuilt. */
internal object ReportAuditRepair {
    private val gson = createAppGson()

    private data class Evidence(
        val file: String, val trace: ApiTrace, val provider: String?,
        val usage: TokenUsage?, val answer: String, val reasoning: String,
        val finish: String?
    )

    private fun objects(body: String?): List<JsonObject> {
        if (body.isNullOrBlank()) return emptyList()
        fun parse(text: String): JsonObject? = runCatching {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
        parse(body)?.let { return listOf(it) }
        return body.lineSequence().map { it.trim().removePrefix("data:").trim() }
            .mapNotNull(::parse).toList()
    }

    private fun readEvidence(file: String): Evidence? {
        val trace = ApiTracer.readTraceFile(file) ?: return null
        val provider = ProviderRegistry.findByHost(trace.hostname)
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var usage: TokenUsage? = null
        var finish: String? = null
        for (root in objects(trace.response.body)) runCatching {
            val usageJson = root.getAsJsonObject("usage") ?: root.getAsJsonObject("usageMetadata")
                ?: root.getAsJsonObject("metrics") ?: root.getAsJsonObject("response")?.getAsJsonObject("usage")
            if (usageJson != null && provider != null) {
                val next = when (provider.apiFormat) {
                    ApiFormat.ANTHROPIC -> gson.fromJson(usageJson, ClaudeUsage::class.java).toTokenUsage()
                    ApiFormat.GOOGLE -> gson.fromJson(usageJson, GeminiUsageMetadata::class.java).toTokenUsage()
                    ApiFormat.REPLICATE -> gson.fromJson(usageJson, ReplicateMetrics::class.java).toTokenUsage()
                    ApiFormat.OPENAI_COMPATIBLE -> gson.fromJson(usageJson, OpenAiUsage::class.java).toTokenUsage(provider)
                }
                usage = if (provider.apiFormat == ApiFormat.ANTHROPIC) mergeUsage(usage, next) else next
            }
            val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            if (choice != null) {
                choice.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString?.let { finish = it }
                val message = choice.getAsJsonObject("delta") ?: choice.getAsJsonObject("message")
                message?.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.let(answer::append)
                (message?.get("reasoning_content") ?: message?.get("reasoning"))
                    ?.takeIf { it.isJsonPrimitive }?.asString?.let(reasoning::append)
            } else when (provider?.apiFormat) {
                ApiFormat.GOOGLE -> root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("content")?.getAsJsonArray("parts")?.forEach { part ->
                        part.asJsonObject.get("text")?.asString?.let(answer::append)
                    }
                ApiFormat.ANTHROPIC -> {
                    root.getAsJsonArray("content")?.forEach { part -> part.asJsonObject.get("text")?.asString?.let(answer::append) }
                    root.getAsJsonObject("delta")?.get("text")?.asString?.let(answer::append)
                }
                ApiFormat.REPLICATE -> root.get("output")?.let { output ->
                    if (output.isJsonArray) output.asJsonArray.forEach { answer.append(it.asString) }
                    else if (output.isJsonPrimitive) answer.append(output.asString)
                }
                else -> {
                    if (root.get("type")?.asString == "response.output_text.delta") {
                        root.get("delta")?.asString?.let(answer::append)
                    } else if (answer.isEmpty()) {
                        val response = root.getAsJsonObject("response") ?: root
                        extractResponsesApiContent(gson.fromJson(response, OpenAiResponsesApiResponse::class.java))?.let(answer::append)
                    }
                }
            }
        }
        return Evidence(file, trace, provider?.id, usage, answer.toString(), reasoning.toString(), finish)
    }

    fun repair(context: Context, report: Report): Report {
        ApiTracer.init(context)
        val evidence = ApiTracer.getTraceFilesForReport(report.id).mapNotNull { readEvidence(it.filename) }
        val byFile = evidence.associateBy { it.file }
        val used = report.apiCallCosts.mapNotNullTo(mutableSetOf()) { it.traceFile }
        val rows = report.apiCallCosts.map { row ->
            if (row.traceFile != null) return@map row
            // Match independently recorded usage and completion time, never
            // merely the provider/model. Ambiguous historical links stay blank.
            val matches = evidence.filter { e ->
                e.file !in used && e.provider == row.provider && e.trace.model == row.model &&
                    e.usage?.billedInputTokens == row.inputTokens && e.usage.billedOutputTokens == row.outputTokens &&
                    row.timestamp - e.trace.timestamp in -2_000L..((row.durationMs ?: 120_000L) + 10_000L)
            }
            val match = matches.singleOrNull() ?: matches.filter {
                row.durationMs != null && abs(row.timestamp - it.trace.timestamp - row.durationMs) < 2_000L
            }.singleOrNull()
            if (match == null) row else row.copy(traceFile = match.file).also { used += match.file }
        }.toMutableList()

        fun addMissingTitle(long: Boolean) {
            val type = if (long) "report/title-long" else "report/title-short"
            val modelLabel = if (long) report.titleLongModel else report.titleModel
            val parts = modelLabel?.split("/", limit = 2).orEmpty()
            val inputCost = if (long) report.titleLongInputCost else report.titleInputCost
            val outputCost = if (long) report.titleLongOutputCost else report.titleOutputCost
            val inputTokens = if (long) report.titleLongInputTokens else report.titleInputTokens
            val outputTokens = if (long) report.titleLongOutputTokens else report.titleOutputTokens
            val accounted = rows.filter { it.type == type || byFile[it.traceFile]?.trace?.category == type }
            // Some title regenerations inherited an outer report context and
            // were recorded as generic "title" rows. Respect exact matches too.
            val exactLegacy = if (accounted.isEmpty()) rows.filter { row ->
                row.type == "title" && row.provider == parts.getOrNull(0) && row.model == parts.getOrNull(1) &&
                    row.inputTokens == inputTokens && row.outputTokens == outputTokens &&
                    abs(row.inputCost - inputCost) < 1e-12 && abs(row.outputCost - outputCost) < 1e-12
            }.singleOrNull() else null
            if (exactLegacy != null) return
            val inMissing = (inputCost - accounted.sumOf { it.inputCost }).coerceAtLeast(0.0)
            val outMissing = (outputCost - accounted.sumOf { it.outputCost }).coerceAtLeast(0.0)
            val inTokens = (inputTokens - accounted.sumOf { it.inputTokens }).coerceAtLeast(0)
            val outTokens = (outputTokens - accounted.sumOf { it.outputTokens }).coerceAtLeast(0)
            if (inMissing + outMissing < 1e-12 && inTokens == 0 && outTokens == 0) return
            val trace = evidence.filter { it.trace.category == type && it.file !in used &&
                it.usage?.billedInputTokens == inTokens && it.usage.billedOutputTokens == outTokens }.singleOrNull()
            rows += ReportApiCallCost(
                id = "repair-v4:${report.id}:$type", timestamp = trace?.trace?.timestamp ?: report.createdAt,
                type = type, provider = parts.getOrNull(0).orEmpty(), model = parts.getOrNull(1).orEmpty(),
                pricingTier = "SAVED_COST", inputTokens = inTokens, outputTokens = outTokens,
                inputCost = inMissing, outputCost = outMissing,
                durationMs = if (long) report.titleLongDurationMs else report.titleDurationMs,
                traceFile = trace?.file
            )
            trace?.let { used += it.file }
        }
        addMissingTitle(false)
        addMissingTitle(true)

        val agents = report.agents.map { old ->
            val candidates = evidence.filter { e ->
                e.provider == old.provider && e.trace.model == old.model && (
                    (!old.responseBody.isNullOrBlank() && (old.responseBody == e.answer || old.responseBody == e.reasoning)) ||
                    (!old.errorMessage.isNullOrBlank() && !e.trace.response.body.isNullOrBlank() &&
                        old.errorMessage!!.contains(e.trace.response.body.trim())))
            }
            val match = byFile[old.traceFile] ?: candidates.singleOrNull() ?: return@map old
            val updated = old.copy(traceFile = match.file, finishReason = match.finish ?: old.finishReason)
            // Only correct an old success when the trace proves the saved body
            // was reasoning, not an answer. Preserve the text as history and
            // every usage/cost field, including the wasted metadata calls.
            if (old.reportStatus == ReportStatus.SUCCESS && old.responseChangeSource == null &&
                match.answer.isBlank() && match.reasoning.isNotBlank() && old.responseBody == match.reasoning) {
                updated.answerHistory = old.answerHistory + ReportAnswerRevision(
                    id = "repair-v4:${old.agentId}", savedAt = System.currentTimeMillis(),
                    prompt = old.requestBody ?: report.prompt, body = old.responseBody.orEmpty(),
                    provider = old.provider, model = old.model, source = "Invalid reasoning-only response", cost = old.currentAttemptCost
                )
                updated.reportStatus = ReportStatus.ERROR
                updated.errorMessage = if (match.finish == "length")
                    "Response truncated: output token limit reached (finish_reason=length). No final answer was returned."
                else "No final answer content returned; the previous response contained only reasoning."
                updated.responseBody = null
                updated.modelTitle = null
                updated.icon = null
            }
            updated
        }.toMutableList()
        return report.copy(agents = agents, apiCallCosts = rows,
            titleTraceFile = report.titleTraceFile ?: rows.firstOrNull { it.type == "report/title-short" }?.traceFile,
            titleLongTraceFile = report.titleLongTraceFile ?: rows.firstOrNull { it.type == "report/title-long" }?.traceFile)
    }
}
