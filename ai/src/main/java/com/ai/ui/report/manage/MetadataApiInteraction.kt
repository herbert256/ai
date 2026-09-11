package com.ai.ui.report.manage

import com.ai.data.ApiTracer
import com.ai.data.stripThinkSections
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Read the actual non-streaming metadata exchange, including its system role.
 * Never reconstruct an old request from today's editable prompt template. Call on IO. */
internal fun readMetadataApiInteraction(filename: String?): String? = runCatching {
    val trace = filename?.takeIf(String::isNotBlank)?.let(ApiTracer::readTraceFile) ?: return null
    fun content(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return if (element.asJsonPrimitive.isString) element.asString else ""
        if (element.isJsonArray) return element.asJsonArray.map(::content).filter(String::isNotBlank).joinToString("\n")
        val obj = element.asJsonObject
        if (obj.get("type")?.asString in listOf("thinking", "reasoning") || obj.get("thought")?.asBoolean == true) return ""
        return content(obj.get("text") ?: obj.get("content") ?: obj.get("parts"))
    }
    val request = JsonParser.parseString(trace.request.body ?: return null).asJsonObject
    val turns = mutableListOf<String>()
    fun add(role: String, text: String) {
        if (text.isNotBlank()) turns += "[$role]\n$text"
    }
    add("system", content(request.get("system") ?: request.get("systemInstruction") ?: request.get("instructions")))
    val messages = request.get("messages") ?: request.get("contents") ?: request.get("input")
    if (messages?.isJsonArray == true) {
        messages.asJsonArray.forEach { message ->
            val obj = message.takeIf { it.isJsonObject }?.asJsonObject
            add(obj?.get("role")?.asString ?: "user", content(message))
        }
    } else add("user", content(messages ?: request.get("prompt")))
    if (turns.isEmpty()) return null
    val response = trace.response.body?.let { JsonParser.parseString(it).asJsonObject }
    val choice = response?.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
    val candidate = response?.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
    val reply = content(choice?.get("message") ?: choice?.get("text") ?: candidate?.get("content")
        ?: response?.get("content") ?: response?.get("output") ?: response?.get("output_text"))
    add("assistant", stripThinkSections(reply).ifBlank { "(no text response recorded)" })
    turns.joinToString("\n\n")
}.getOrNull()
