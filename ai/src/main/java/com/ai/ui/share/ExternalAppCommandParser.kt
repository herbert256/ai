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
    private val COMMAND_TAGS = setOf("prompt", "system", "parameters", "default", "type", "email", "next",
        "agent", "flock", "swarm", "model", "return", "edit", "select")
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
        val commands = ENTRY_BLOCKS.replace(instructionText) {
            if (it.groupValues[1].lowercase(Locale.US) in COMMAND_TAGS) it.value else ""
        }
        fun presentationBody(tag: String): String? = blocks
            .firstOrNull { it.groupValues[1].equals(tag, ignoreCase = true) }?.groupValues?.get(2)?.trim()
        val agentNames = extractAllTags("agent", commands)
        val flockNames = extractAllTags("flock", commands)
        val swarmNames = extractAllTags("swarm", commands)
        val hasWorkers = agentNames.isNotEmpty() || flockNames.isNotEmpty() || swarmNames.isNotEmpty()

        return ExternalReportCommand.Confirm(
            PendingExternalReport(
                title = title,
                systemPrompt = systemPrompt,
                aiPrompt = context.expand(aiPrompt),
                context = context,
                needsStoredPrompt = (aiPrompt.isBlank() && !hasWorkers && extractTag("default", commands) == null) || extractTag("prompt", commands) != null,
                promptReference = extractTag("prompt", commands),
                systemReference = extractTag("system", commands),
                parametersReference = extractTag("parameters", commands),
                defaultReference = extractTag("default", commands),
                openHtml = presentationBody("open")?.let { context.expand(it, presentation = true) },
                closeHtml = presentationBody("close")?.let { context.expand(it, presentation = true) },
                reportType = extractTag("type", commands),
                email = extractTag("email", commands),
                nextAction = extractTag("next", commands),
                hasReturn = hasTag("return", commands),
                hasEdit = hasTag("edit", commands),
                hasSelect = hasTag("select", commands),
                agentNames = agentNames,
                flockNames = flockNames,
                swarmNames = swarmNames,
                modelSpecs = extractAllTags("model", commands)
            )
        )
    }

    /** First `<tag>…</tag>` body, trimmed, or null. */
    private fun extractTag(tag: String, text: String): String? =
        Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(text)?.groupValues?.get(1)?.let { ExternalReportContext.decode(it).trim() }

    /** Every non-empty `<tag>…</tag>` body, trimmed, in order. */
    private fun extractAllTags(tag: String, text: String): List<String> =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

    /** True if a bare `<tag>` marker is present (case-insensitive). */
    private fun hasTag(tag: String, text: String): Boolean =
        Regex("<$tag>", RegexOption.IGNORE_CASE).containsMatchIn(text)
}
