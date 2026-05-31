package com.ai.data

internal const val MODEL_TEMPERATURE_CALL_KIND = "model/temperature"

internal fun normalizeApiCallCategory(category: String?): String? =
    when {
        category == null -> null
        category.equals("Temperature sweep", ignoreCase = true) -> MODEL_TEMPERATURE_CALL_KIND
        else -> category
    }

internal fun normalizeUsageKind(kind: String?): String =
    normalizeApiCallCategory(kind) ?: "report"
