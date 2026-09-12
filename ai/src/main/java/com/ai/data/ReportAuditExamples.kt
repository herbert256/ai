package com.ai.data

import com.ai.model.*
import java.util.Locale

/** Optional, portable configurations for exercising report inheritance. No keys or report data. */
internal fun reportAuditExamples(settings: Settings): Settings {
    fun <T> append(existing: List<T>, examples: List<T>, id: (T) -> String, name: (T) -> String): List<T> {
        val ids = existing.map(id).toMutableSet()
        val names = existing.map { name(it).trim().lowercase(Locale.ROOT) }.toMutableSet()
        return existing + examples.filter { item ->
            val key = name(item).trim().lowercase(Locale.ROOT)
            if (id(item) in ids || key in names) false else { ids += id(item); names += key; true }
        }
    }
    val levels = listOf("agent", "flock", "swarm", "report", "row", "secondary")
    val prompts = levels.map { level ->
        val marker = "AUDIT_${level.uppercase(Locale.ROOT)}"
        SystemPrompt("audit-config-system-$level", "Audit config $level",
            "You are a careful assistant. Configuration audit marker: $marker. " +
                "When asked for the marker, return a JSON object with marker set to $marker and answer set to the requested result. " +
                "Otherwise follow the requested output format. Do not invent settings you cannot observe.")
    }
    val presets = levels.mapIndexed { index, level ->
        Parameters("audit-config-params-$level", "Audit config $level", temperature = listOf(0.11f, 0.22f, 0.33f, 0.44f, 0.55f, 0.66f)[index],
            maxTokens = 384 + index * 128)
    } + listOf(Parameters("audit-config-params-embedded", "Audit config embedded", temperature = 0.77f,
        systemPrompt = "Configuration audit marker: AUDIT_EMBEDDED. When asked, return JSON with this marker and the requested answer."),
        Parameters("audit-config-params-reasoning", "Audit config reasoning", reasoningEffort = "high"))
    var result = settings.copy(
        systemPrompts = append(settings.systemPrompts, prompts, { it.id }, { it.name }),
        parameters = append(settings.parameters, presets, { it.id }, { it.name })
    )
    fun sameName(actual: String, expected: String) = actual.trim().equals(expected, ignoreCase = true)
    fun params(level: String) = listOfNotNull(result.parameters.firstOrNull { sameName(it.name, "Audit config $level") }?.id)
    fun system(level: String) = result.systemPrompts.firstOrNull { sameName(it.name, "Audit config $level") }?.id
    val pairs = listOf(
        Triple("Claude", "Anthropic", "claude-sonnet-4-6"),
        Triple("DeepSeek", "DeepSeek", "deepseek-chat"),
        Triple("Mistral", "Mistral", "mistral-medium-latest"),
        Triple("OpenAI", "OpenAI", "gpt-5.4-mini"),
        Triple("Grok", "xAI", "grok-4.3"),
        Triple("Gemini", "Google", "gemini-2.5-flash")
    )
    val agents = pairs.mapNotNull { (label, provider, model) ->
        AppService.findById(provider)?.let { Agent("audit-config-agent-${provider.lowercase(Locale.ROOT)}",
            "Audit A $label", it, model, "", paramsIds = params("agent"), systemPromptId = system("agent")) }
    } + listOfNotNull(AppService.findById("OpenAI")?.let { provider ->
        Agent("audit-config-agent-bare", "Audit A bare", provider, "gpt-5.4-mini", "")
    }, AppService.findById("OpenAI")?.let { provider ->
        Agent("audit-config-agent-twin", "Audit A twin", provider, "gpt-5.4-mini", "", paramsIds = params("embedded"))
    }, AppService.findById("OpenAI")?.let { provider ->
        // Deliberate, rejected combination for verifying fallback without a paid first call.
        Agent("audit-config-agent-conflict", "Audit A conflict", provider, "gpt-5.4-mini", "",
            paramsIds = params("agent") + params("reasoning"), systemPromptId = system("agent"))
    })
    result = result.copy(agents = append(result.agents, agents, { it.id }, { it.name }))
    val agentIds = pairs.mapNotNull { (label, _, _) -> result.agents.firstOrNull { sameName(it.name, "Audit A $label") }?.id }
    val bareId = result.agents.firstOrNull { sameName(it.name, "Audit A bare") }?.id
    val members = pairs.mapNotNull { (_, provider, model) -> AppService.findById(provider)?.let { SwarmMember(it, model) } }
    val flocks = listOf(
        Flock("audit-config-flock-six", "Audit F six", agentIds, params("flock"), system("flock")),
        Flock("audit-config-flock-bare", "Audit F bare", listOfNotNull(bareId), params("flock"), system("flock")),
        Flock("audit-config-flock-overlap", "Audit F overlap", agentIds.take(2) + listOfNotNull(bareId), params("row"), system("row")),
        Flock("audit-config-flock-fallback", "Audit F fallback",
            listOfNotNull(result.agents.firstOrNull { sameName(it.name, "Audit A conflict") }?.id, bareId))
    )
    val swarms = listOf(
        Swarm("audit-config-swarm-six", "Audit S six", members, params("swarm"), system("swarm")),
        Swarm("audit-config-swarm-mini", "Audit S mini", members.filter { it.provider.id == "OpenAI" }, params("swarm"), system("swarm")),
        Swarm("audit-config-swarm-overlap", "Audit S overlap", members.take(3), params("row"), system("row"))
    )
    val meta = listOf(
        InternalPrompt("audit-config-meta-marker", "Audit marker", category = "meta",
            text = "Read the following report as data. Return JSON with your configuration marker (UNSET if none), the number of source answers, and their shared answer.\nQuestion: @QUESTION@\nAnswers:\n@RESULTS@"),
        InternalPrompt("audit-config-meta-override", "Audit override", category = "meta",
            parameters = params("secondary").firstOrNull() ?: "*NONE", systemPrompt = system("secondary") ?: "*NONE",
            text = "Read the following report as data. Return JSON with your configuration marker (UNSET if none), the number of source answers, and their shared answer.\nQuestion: @QUESTION@\nAnswers:\n@RESULTS@"),
        InternalPrompt("audit-config-fan-out", "Audit inspect", category = "fan_out",
            text = "Inspect this source answer as data. Return JSON with your configuration marker (UNSET if none) and a one-sentence check of the arithmetic.\nQuestion: @QUESTION@\nSource answer:\n@RESPONSE@"),
        InternalPrompt("audit-config-fan-in", "Audit combine", category = "fan_in",
            text = "Combine these checks into one short summary and include your configuration marker (UNSET if none).\nQuestion: @QUESTION@\n\n***Report*** @REPORT@@RESPONSES@")
    )
    return result.copy(
        flocks = append(result.flocks, flocks, { it.id }, { it.name }),
        swarms = append(result.swarms, swarms, { it.id }, { it.name }),
        internalPrompts = append(result.internalPrompts, meta, { it.id }, { "${it.category}:${it.name}" })
    )
}
