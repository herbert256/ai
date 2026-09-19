package com.ai.ui.share

import java.util.Locale

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
    private val ENTRY_BLOCKS = Regex("<([A-Za-z_][A-Za-z0-9_.:-]*)>(.*?)</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val RAW_TAGS = setOf("open", "close", "board")

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

        // Read top-level entries before decoding. Markup inside a value must
        // never become another instruction (including arbitrary custom data).
        val instructionText = Regex("^\\s*<instructions>(.*?)</instructions>\\s*$",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .matchEntire(instr)?.groupValues?.get(1) ?: instr
        val blocks = ENTRY_BLOCKS.findAll(instructionText).toList()
        val context = ExternalReportContext(blocks.associate {
            val tag = it.groupValues[1].lowercase(Locale.US)
            tag to if (tag in RAW_TAGS) it.groupValues[2] else ExternalReportContext.decode(it.groupValues[2])
        })
        val standalone = ENTRY_BLOCKS.replace(instructionText, "")
        fun extractTag(tag: String): String? = blocks
            .firstOrNull { it.groupValues[1].equals(tag, ignoreCase = true) }
            ?.groupValues?.get(2)?.let { ExternalReportContext.decode(it).trim() }
        fun extractAllTags(tag: String): List<String> = blocks
            .filter { it.groupValues[1].equals(tag, ignoreCase = true) }
            .map { ExternalReportContext.decode(it.groupValues[2]).trim() }.filter { it.isNotEmpty() }
        fun hasTag(tag: String): Boolean = blocks.any { it.groupValues[1].equals(tag, ignoreCase = true) } ||
            Regex("<$tag>", RegexOption.IGNORE_CASE).containsMatchIn(standalone)
        fun presentationBody(tag: String): String? = blocks
            .firstOrNull { it.groupValues[1].equals(tag, ignoreCase = true) }?.groupValues?.get(2)?.trim()
        val agentNames = extractAllTags("agent")
        val flockNames = extractAllTags("flock")
        val swarmNames = extractAllTags("swarm")
        val hasWorkers = agentNames.isNotEmpty() || flockNames.isNotEmpty() || swarmNames.isNotEmpty()

        val promptText = extractTag("prompt")
        val systemText = extractTag("system")
        return ExternalReportCommand.Confirm(
            PendingExternalReport(
                title = title,
                systemPrompt = systemText ?: systemPrompt,
                literalSystemPrompt = systemText,
                aiPrompt = context.expand(promptText ?: aiPrompt),
                context = context,
                needsStoredPrompt = promptText == null && aiPrompt.isBlank() && !hasWorkers,
                parametersReference = extractTag("parameters"),
                openHtml = presentationBody("open")?.let { context.expand(it) },
                closeHtml = presentationBody("close")?.let { context.expand(it) },
                reportType = extractTag("type"),
                email = extractTag("email"),
                nextAction = extractTag("next"),
                hasReturn = hasTag("return"),
                hasSelect = hasTag("select"),
                agentNames = agentNames,
                flockNames = flockNames,
                swarmNames = swarmNames
            )
        )
    }

}
