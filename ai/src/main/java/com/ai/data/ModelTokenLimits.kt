package com.ai.data

import com.google.gson.Gson
import com.google.gson.JsonParser

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
    gson: Gson
): Map<String, ModelCapabilities> {
    if (rawJson.isNullOrBlank() ||
        (!rawJson.contains("\"context_size\"") && !rawJson.contains("\"max_output_tokens\""))) {
        return capabilities
    }
    return try {
        val root = JsonParser.parseString(rawJson)
        val entries = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return capabilities
        val updated = capabilities.toMutableMap()
        for (entry in entries) {
            // A malformed entry must not discard limits recovered for other models.
            val model = runCatching { gson.fromJson(entry, OpenAiModel::class.java) }.getOrNull() ?: continue
            val id = model.id?.takeIf { it.isNotBlank() } ?: continue
            val previous = capabilities[id] ?: ModelCapabilities()
            val context = previous.contextLength?.takeIf { it > 0 } ?: model.nativeContextLength
            val output = previous.maxOutputTokens?.takeIf { it > 0 } ?: model.max_output_tokens?.takeIf { it > 0 }
            if (context != null || output != null) {
                updated[id] = previous.copy(contextLength = context, maxOutputTokens = output)
            }
        }
        if (updated == capabilities) capabilities else updated
    } catch (e: Exception) {
        AppLog.w("ModelListCache", "Unable to recover cached model token limits: ${e.javaClass.simpleName}")
        capabilities
    }
}
