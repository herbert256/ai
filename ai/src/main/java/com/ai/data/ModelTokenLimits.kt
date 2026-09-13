package com.ai.data

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Context aliases used by OpenAI-compatible providers, including Novita. */
internal val OpenAiModel.nativeContextLength: Int?
    get() = listOfNotNull(max_context_length, context_length, context_window, context_size)
        .firstOrNull { it > 0 }

/**
 * Recover newly supported limits from an existing catalog after an app update.
 * Only backfill missing limits; keep every other cached capability and avoid
 * network requests or changing the catalog's refresh timestamp. The fast check
 * skips providers whose catalogs contain neither of the newly supported fields.
 */
internal fun backfillCachedTokenLimits(
    rawJson: String?,
    capabilities: Map<String, ModelCapabilities>,
    @Suppress("UNUSED_PARAMETER") gson: Gson
): Map<String, ModelCapabilities> {
    if (rawJson.isNullOrBlank() ||
        (!rawJson.contains("\"context_size\"") && !rawJson.contains("\"max_output_tokens\""))) {
        return capabilities
    }
    return try {
        val updated = capabilities.toMutableMap()
        JsonReader(StringReader(rawJson)).use { reader ->
            fun text(): String? = when (reader.peek()) {
                JsonToken.NUMBER, JsonToken.STRING -> reader.nextString()
                else -> { reader.skipValue(); null }
            }
            fun positiveInt(): Int? = text()?.toIntOrNull()?.takeIf { it > 0 }
            fun entries() {
                if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return }
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                    var id: String? = null
                    val context = arrayOfNulls<Int>(4)
                    var output: Int? = null
                    reader.beginObject()
                    while (reader.hasNext()) when (reader.nextName()) {
                        "id" -> id = text()
                        "max_context_length" -> context[0] = positiveInt()
                        "context_length" -> context[1] = positiveInt()
                        "context_window" -> context[2] = positiveInt()
                        "context_size" -> context[3] = positiveInt()
                        "max_output_tokens" -> output = positiveInt()
                        else -> reader.skipValue()
                    }
                    reader.endObject()
                    val modelId = id?.takeIf { it.isNotBlank() } ?: continue
                    val previous = capabilities[modelId] ?: ModelCapabilities()
                    val recoveredContext = previous.contextLength?.takeIf { it > 0 } ?: context.firstOrNull { it != null }
                    val recoveredOutput = previous.maxOutputTokens?.takeIf { it > 0 } ?: output
                    if (recoveredContext != null || recoveredOutput != null)
                        updated[modelId] = previous.copy(contextLength = recoveredContext, maxOutputTokens = recoveredOutput)
                }
                reader.endArray()
            }
            if (reader.peek() == JsonToken.BEGIN_ARRAY) entries()
            else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) { if (reader.nextName() == "data") entries() else reader.skipValue() }
                reader.endObject()
            }
        }
        if (updated == capabilities) capabilities else updated
    } catch (e: Exception) {
        AppLog.w("ModelListCache", "Unable to recover cached model token limits: ${e.javaClass.simpleName}")
        capabilities
    }
}
