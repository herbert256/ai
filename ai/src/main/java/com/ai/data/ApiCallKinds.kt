package com.ai.data

internal const val MODEL_TEMPERATURE_CALL_KIND = "model/temperature"
internal const val MODEL_REASONING_CALL_KIND = "model/reasoning"
internal const val MODEL_WEB_SEARCH_CALL_KIND = "model/web-search"
internal const val MODEL_PROMPT_EDIT_CALL_KIND = "model/prompt-edit"
internal const val FAN_OUT_TEMPERATURE_CALL_KIND = "fan/out/temperature"
internal const val FAN_OUT_REASONING_CALL_KIND = "fan/out/reasoning"
internal const val FAN_OUT_WEB_SEARCH_CALL_KIND = "fan/out/web-search"
internal const val FAN_OUT_PROMPT_EDIT_CALL_KIND = "fan/out/prompt-edit"

/** The Default-icons "AI" icon finder (Settings → Default icons → 🤖). Its
 *  per-model emoji probes are tagged with this so they group under their own
 *  bucket in the API Traces and AI Usage / Costs. */
internal const val SETTINGS_ICONS_CALL_KIND = "settings/icons"

internal fun normalizeApiCallCategory(category: String?): String? =
    when {
        category == null -> null
        category.equals("Temperature sweep", ignoreCase = true) -> MODEL_TEMPERATURE_CALL_KIND
        category.equals("Reasoning Effort", ignoreCase = true) ||
            category.equals("Reasoning effort", ignoreCase = true) -> MODEL_REASONING_CALL_KIND
        category.equals("Web search replay", ignoreCase = true) -> MODEL_WEB_SEARCH_CALL_KIND
        category.equals("Prompt edit replay", ignoreCase = true) -> MODEL_PROMPT_EDIT_CALL_KIND
        else -> category
    }

internal fun normalizeUsageKind(kind: String?): String =
    normalizeApiCallCategory(kind) ?: "report"
