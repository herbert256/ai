package com.ai.ui.settings

import com.ai.data.AppService
import com.ai.data.createAppGson
import com.ai.model.Settings
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Result of applying a flat keys-JSON payload to a [Settings] snapshot.
 *  External-service keys (HuggingFace / OpenRouter / Artificial Analysis)
 *  live in [com.ai.viewmodel.GeneralSettings] and are surfaced as
 *  optional fields here for the caller to push through whichever
 *  setter wires them up. */
data class ApiKeysImportResult(
    val settings: Settings,
    val huggingFaceApiKey: String? = null,
    val openRouterApiKey: String? = null,
    val artificialAnalysisApiKey: String? = null,
    val imported: Int,
    val skipped: Int
)

/** Marker thrown when the picked file looks like a full ConfigExport
 *  (has top-level "version" + "providers"). The caller can show a
 *  more helpful "use the Config import button instead" toast. */
class ConfigBundleMistakenForKeysException : RuntimeException()

/** Build the JSON payload written by the "Export API keys" button. The
 *  shape is a flat object: `{"OPENAI": "sk-...", "EXT_HUGGINGFACE":
 *  "hf_..."}`. EXT_-prefixed keys are external-service keys (HF /
 *  OpenRouter / Artificial Analysis) — the prefix avoids collision
 *  with the "HUGGINGFACE" provider id. */
fun buildApiKeysJson(
    settings: Settings,
    huggingFaceApiKey: String,
    openRouterApiKey: String,
    artificialAnalysisApiKey: String
): String {
    val keys = mutableMapOf<String, String>()
    for (service in AppService.entries) {
        val apiKey = settings.getApiKey(service)
        if (apiKey.isNotBlank()) keys[service.id] = apiKey
    }
    if (huggingFaceApiKey.isNotBlank()) keys["EXT_HUGGINGFACE"] = huggingFaceApiKey
    if (openRouterApiKey.isNotBlank()) keys["EXT_OPENROUTER"] = openRouterApiKey
    if (artificialAnalysisApiKey.isNotBlank()) keys["EXT_ARTIFICIALANALYSIS"] = artificialAnalysisApiKey
    return createAppGson(prettyPrint = true).toJson(keys)
}

/** Apply a flat keys-JSON payload to [currentSettings], returning the
 *  updated [Settings] plus any external-service keys for the caller
 *  to wire through GeneralSettings. Returns null when the JSON isn't a
 *  plain object. Throws [ConfigBundleMistakenForKeysException] when the
 *  payload looks like a full ConfigExport (version + providers). */
fun applyApiKeysJson(json: String, currentSettings: Settings): ApiKeysImportResult? {
    @Suppress("DEPRECATION")
    val root = JsonParser().parse(json)
    if (!root.isJsonObject) return null
    val obj: JsonObject = root.asJsonObject
    if (obj.has("version") && obj.has("providers")) {
        throw ConfigBundleMistakenForKeysException()
    }
    var updated = currentSettings
    var hf: String? = null
    var or: String? = null
    var aa: String? = null
    var imported = 0
    var skipped = 0
    for (entry in obj.entrySet()) {
        val id = entry.key
        val valueEl = entry.value
        val prim = if (valueEl != null && valueEl.isJsonPrimitive) valueEl.asJsonPrimitive else null
        val key = if (prim != null && prim.isString) prim.asString else { skipped++; continue }
        if (key.isBlank()) { skipped++; continue }
        when (id) {
            "EXT_HUGGINGFACE" -> { hf = key; imported++ }
            "EXT_OPENROUTER" -> { or = key; imported++ }
            "EXT_ARTIFICIALANALYSIS" -> { aa = key; imported++ }
            // Legacy aliases kept for backward-compatible imports of
            // pre-prefix keys.json files.
            "HUGGINGFACE" -> { hf = key; imported++ }
            "OPENROUTER_KEY" -> { or = key; imported++ }
            else -> {
                val service = AppService.findById(id)
                if (service != null) { updated = updated.withApiKey(service, key); imported++ } else skipped++
            }
        }
    }
    return ApiKeysImportResult(updated, hf, or, aa, imported, skipped)
}
