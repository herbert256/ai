package com.ai.ui.share

/**
 * The decision a parsed external `ACTION_NEW_REPORT` intent resolves to.
 * Navigation/side effects live in the caller (AppNavHost); this just says
 * which path applies.
 */
sealed interface ExternalReportCommand {
    /** Bare prompt — no `<instructions>` block and no `-- end prompt --`
     *  marker. Only pre-fills the new-report editor; the user still picks
     *  models and taps Generate, so no API credits move without consent. */
    data class Prefill(val title: String, val prompt: String, val systemPrompt: String? = null) : ExternalReportCommand

    /** Instruction-bearing — must pass through [ExternalIntentConfirmScreen]
     *  before any auto-generate / email / share / finish side effect runs. */
    data class Confirm(val staged: PendingExternalReport) : ExternalReportCommand
}

/**
 * Pure parser for external `ACTION_NEW_REPORT` intents. Separates the AI prompt
 * from its instructions (an explicit extra, else the `-- end prompt --` marker
 * inside the prompt), extracts the per-tag fields, and decides between a
 * no-side-effect [ExternalReportCommand.Prefill] and an
 * [ExternalReportCommand.Confirm]. Deliberately free of Compose/Android so it
 * can be unit-tested in isolation (see `ExternalAppCommandParserTest`).
 */
object ExternalAppCommandParser {
    private const val MARKER = "-- end prompt --"
    private val PRESENTATION_TAGS = Regex("<(open|close)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)

    fun parse(
        prompt: String,
        instructions: String?,
        title: String?,
        systemPrompt: String?
    ): ExternalReportCommand {
        val aiPrompt: String
        val instr: String
        when {
            instructions != null -> {
                aiPrompt = prompt.trim(); instr = instructions
            }
            prompt.contains(MARKER) -> {
                val parts = prompt.split(MARKER, limit = 2)
                aiPrompt = parts[0].trim(); instr = parts.getOrElse(1) { "" }
            }
            else -> return ExternalReportCommand.Prefill(title ?: "", prompt, systemPrompt)
        }

        // HTML forms and scripts may contain instruction-looking tags, such
        // as <select>. Only tags outside the presentation bodies are commands.
        val presentation = PRESENTATION_TAGS.findAll(instr).toList()
        val commands = PRESENTATION_TAGS.replace(instr, "")
        fun presentationBody(tag: String): String? = presentation
            .firstOrNull { it.groupValues[1] == tag }?.groupValues?.get(2)?.trim()

        return ExternalReportCommand.Confirm(
            PendingExternalReport(
                title = title,
                systemPrompt = systemPrompt,
                aiPrompt = aiPrompt,
                openHtml = presentationBody("open"),
                closeHtml = presentationBody("close"),
                reportType = extractTag("type", commands),
                email = extractTag("email", commands),
                nextAction = extractTag("next", commands),
                hasReturn = hasTag("return", commands),
                hasEdit = hasTag("edit", commands),
                hasSelect = hasTag("select", commands),
                agentNames = extractAllTags("agent", commands),
                flockNames = extractAllTags("flock", commands),
                swarmNames = extractAllTags("swarm", commands),
                modelSpecs = extractAllTags("model", commands)
            )
        )
    }

    /** First `<tag>…</tag>` body, trimmed, or null. */
    private fun extractTag(tag: String, text: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)?.trim()

    /** Every non-empty `<tag>…</tag>` body, trimmed, in order. */
    private fun extractAllTags(tag: String, text: String): List<String> =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    /** True if a bare `<tag>` marker is present (case-insensitive). */
    private fun hasTag(tag: String, text: String): Boolean =
        Regex("<$tag>", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
