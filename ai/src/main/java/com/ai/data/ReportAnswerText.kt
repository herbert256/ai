package com.ai.data

/** Removes model thinking blocks, including tags split across streamed chunks. */
internal class ReportAnswerFilter {
    private val tag = StringBuilder()
    private var depth = 0

    fun append(text: String): String = buildString {
        for (char in text) {
            if (tag.isEmpty() && char != '<') {
                if (depth == 0) append(char)
                continue
            }
            tag.append(char)
            val candidate = tag.toString()
            when {
                THINK_TAG.matches(candidate) -> {
                    if (candidate.startsWith("</")) depth = (depth - 1).coerceAtLeast(0)
                    else if (!candidate.endsWith("/>")) depth++
                    tag.clear()
                }
                !couldBeThinkTag(candidate) -> {
                    if (depth == 0) append(candidate)
                    tag.clear()
                }
            }
        }
    }

    fun finish(): String {
        val tail = if (depth == 0 && !couldBeThinkTag(tag.toString())) tag.toString() else ""
        tag.clear()
        return tail
    }

    private fun couldBeThinkTag(value: String): Boolean = listOf("<think", "</think").any { prefix ->
        prefix.startsWith(value, ignoreCase = true) ||
            (value.startsWith(prefix, ignoreCase = true) && value.getOrNull(prefix.length)?.let {
                it.isWhitespace() || it == '>' || it == '/'
            } == true)
    }

    private companion object {
        val THINK_TAG = Regex("</?think(?:\\s[^>]*|/)?>", RegexOption.IGNORE_CASE)
    }
}

internal fun stripThinkSections(text: String): String {
    val filter = ReportAnswerFilter()
    return (filter.append(text) + filter.finish()).trim()
}

/** Keep original usage and traces; thinking-only output is a failed answer. */
internal fun AnalysisResponse.withoutThinkSections(): AnalysisResponse {
    val original = analysis ?: return this
    val answer = stripThinkSections(original)
    return if (answer.isBlank() && original.isNotBlank()) copy(
        analysis = null,
        error = error ?: "No final answer content returned; the response contained only thinking.",
        generationFailed = true
    ) else copy(analysis = answer)
}
