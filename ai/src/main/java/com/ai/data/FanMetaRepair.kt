package com.ai.data

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Network-free upgrade of metadata produced before per-attempt records existed.
 * A payload/model/title match must be unique; repeated answers are never guessed.
 * Unattributed rejected spend remains visible at run level. */
object FanMetaRepair {
    private val lock = Any()

    fun repair(context: Context, report: Report): Report = synchronized(lock) {
        val current = ReportStorage.getReport(context, report.id) ?: return report
        if (current.fanMetaRepairVersion >= 1) return current
        val rows = SecondaryResultStorage.listForReport(context, report.id, SecondaryKind.META)
            .filter { it.fanOutSourceAgentId != null && it.fanInOf == null && it.titlePromptUsed == "fan-meta" }
        val byRun = rows.filter { it.titleRunId != null }.groupBy { it.titleRunId!! }
        val knownTraces = rows.flatMap { it.fanMetaAttempts.orEmpty() }.mapNotNull { it.traceFile }.toSet() +
            current.unattributedFanMetaAttempts.orEmpty().mapNotNull { it.traceFile }
        val assignments = mutableMapOf<String, MutableList<FanMetaAttempt>>()
        val unassigned = mutableListOf<FanMetaAttempt>()
        ApiTracer.init(context)
        for (cost in current.apiCallCosts) {
            val filename = cost.traceFile ?: continue
            if (filename in knownTraces || cost.type !in setOf("title", "worker/rejected")) continue
            val trace = ApiTracer.readTraceFile(filename) ?: continue
            if (trace.reportId != report.id || trace.category != "fan/meta" || trace.partial) continue
            val runId = trace.runId ?: continue
            val runRows = byRun[runId] ?: continue
            val request = requestText(trace.request.body) ?: continue
            val payloadMatches = runRows.filter { !it.content.isNullOrBlank() && request.endsWith("TEXT:\n" + it.content) }
            val rejected = cost.type == "worker/rejected"
            val parsed = FanMetaFormat.parse(responseText(trace.response.body))
            val matches = if (rejected) payloadMatches else payloadMatches.filter {
                it.titleModel == "${cost.provider}/${cost.model}" && parsed != null &&
                    FanMetaFormat.cleanTitle(it.title) == parsed.title && it.icon == parsed.icon
            }
            val promptId = runRows.mapNotNull { it.metaPromptId }.distinct().singleOrNull() ?: continue
            val attempt = FanMetaAttempt(cost.id, runId, promptId, cost.provider, cost.model,
                filename, !rejected, if (rejected) "Rejected metadata response (historical)" else null,
                cost.inputTokens, cost.outputTokens, cost.inputCost, cost.outputCost, cost.durationMs)
            if (matches.size == 1) assignments.getOrPut(matches.single().id) { mutableListOf() }.add(attempt)
            else if (rejected) unassigned.add(attempt)
        }
        rows.forEach { row -> SecondaryResultStorage.repairFanMetaRow(context, row, assignments[row.id].orEmpty()) }
        val updated = ReportStorage.finishFanMetaRepair(context, report.id, unassigned) ?: current
        AppLog.i("FanMeta", "Repaired saved metadata: ${rows.size} rows, ${assignments.values.sumOf { it.size }} exact attempt links, ${unassigned.size} unattributed rejected attempts")
        updated
    }

    private fun json(body: String?) = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    private fun text(node: JsonElement?): String = when {
        node == null || node.isJsonNull -> ""
        node.isJsonPrimitive -> if (node.asJsonPrimitive.isString) node.asString else ""
        node.isJsonArray -> node.asJsonArray.joinToString("\n") { text(it) }
        node.isJsonObject -> node.asJsonObject.let { text(it["text"] ?: it["content"] ?: it["parts"]) }
        else -> ""
    }
    private fun requestText(body: String?): String? = json(body)?.let { root ->
        val messages = root["messages"] ?: root["contents"] ?: root["input"]
        if (messages?.isJsonArray == true) messages.asJsonArray.lastOrNull {
            it.isJsonObject && it.asJsonObject["role"]?.asString == "user"
        }?.let(::text) else text(messages)
    }
    private fun responseText(body: String?): String? = json(body)?.let { root ->
        when {
            root.has("choices") -> root.getAsJsonArray("choices").firstOrNull()?.asJsonObject?.get("message")?.let(::text)
            root.has("candidates") -> root.getAsJsonArray("candidates").firstOrNull()?.asJsonObject?.get("content")?.let(::text)
            root.has("output") -> text(root["output"])
            else -> text(root["content"])
        }
    }
}
