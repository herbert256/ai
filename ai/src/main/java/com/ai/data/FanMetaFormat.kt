package com.ai.data

/** The Fan Meta contract, shared by generation and repair of older saved titles. */
object FanMetaFormat {
    data class Result(val title: String, val icon: String)
    private val label = Regex("(?i)^(title|icon)\\s*:")

    private fun unwrap(value: String): String = value.trim()
        .removePrefix("- ").removePrefix("• ").trim()
        .trim('*', '_', '`', '"', '\'', '“', '”').trim()

    fun cleanTitle(value: String?): String {
        var title = value.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        repeat(3) {
            title = unwrap(title)
            title = title.replaceFirst(Regex("(?i)^title\\s*:\\s*"), "")
        }
        if (title.isBlank() || label.containsMatchIn(title)) return ""
        // Bound Unicode code points, never cut a surrogate pair; keep a complete
        // word when practical. Preserve the full model reply in its API trace.
        if (title.codePointCount(0, title.length) > 30) {
            val prefix = title.substring(0, title.offsetByCodePoints(0, 29)).trimEnd()
            val space = prefix.lastIndexOf(' ')
            title = (if (space >= 16) prefix.substring(0, space) else prefix) + "…"
        }
        return title
    }

    fun parse(text: String?): Result? {
        val lines = text.orEmpty().lineSequence().map(::unwrap)
            .filter { it.isNotBlank() && it != "text" && it != "markdown" }.toList()
        val titleLine = lines.firstOrNull { it.startsWith("title:", ignoreCase = true) }
            ?: lines.filterNot { it.startsWith("icon:", ignoreCase = true) || extractFirstEmoji(it) == it }
                .singleOrNull() ?: return null
        val iconLine = lines.firstOrNull { it.startsWith("icon:", ignoreCase = true) }
            ?: lines.lastOrNull { it != titleLine } ?: return null
        val title = cleanTitle(titleLine).takeIf { it.isNotBlank() } ?: return null
        val icon = extractFirstEmoji(iconLine) ?: return null
        return Result(title, icon)
    }
}

/** One worker attempt, distinct from the Fan Out answer's own trace and spend. */
data class FanMetaAttempt(
    val id: String,
    val runId: String,
    val metaPromptId: String,
    val provider: String,
    val model: String,
    val traceFile: String? = null,
    val accepted: Boolean = false,
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val inputCost: Double = 0.0,
    val outputCost: Double = 0.0,
    val durationMs: Long? = null
) {
    val cost: Double get() = inputCost + outputCost
    val modelKey: String get() = "$provider/$model"
}
