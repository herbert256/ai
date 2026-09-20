package com.ai.ui.share

import com.ai.data.AppService
import com.ai.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Values supplied by an external app, separate from executable report instructions. */
data class ExternalReportContext(val values: Map<String, String> = emptyMap()) {
    /** Substitute supplied entries once; values are literal and missing entries stay intact. */
    fun expandPrompt(template: String, fallback: (String) -> String = { it }): String =
        PLACEHOLDERS.replace(template) { match ->
            values[match.groupValues[1].lowercase(Locale.US)] ?: fallback(match.value)
        }

    /** Apply the same supplied-value substitution to questions and report presentation. */
    fun expand(template: String): String {
        if (values.isEmpty()) return template
        return expandPrompt(template) { token ->
            if (token.equals("@date@", ignoreCase = true))
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            else token
        }
    }

    companion object {
        private val PLACEHOLDERS = Regex("@([A-Za-z_][A-Za-z0-9_.:-]*)@")

        /** Decode once: an escaped literal entity must not become a second level of markup. */
        fun decode(text: String): String = Regex("&(amp|lt|gt|quot|#39);").replace(text) {
            when (it.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; else -> "'"
            }
        }
    }
}

internal data class SavedExternalPrompt(val id: String, val name: String, val text: String, val system: String? = null)

internal fun externalPromptChoices(settings: Settings): List<SavedExternalPrompt> =
    (settings.examplePrompts.map { SavedExternalPrompt(it.id, it.title, it.text) } +
        settings.internalPrompts.filter { it.category != "internal" }.map {
            SavedExternalPrompt(it.id, it.name, it.text, it.systemPrompt)
        }).filter { it.text.isNotBlank() }.sortedBy { it.name.lowercase() }

private fun externalSystemPrompt(settings: Settings, ref: String?) = ref?.trim()?.let { value ->
    settings.systemPrompts.firstOrNull { it.id == value }
        ?: settings.systemPrompts.filter { it.name.trim().equals(value, ignoreCase = true) }.singleOrNull()
}

internal fun selectExternalPrompt(request: PendingExternalReport, settings: Settings, prompt: SavedExternalPrompt, systemRef: String?): PendingExternalReport {
    val system = externalSystemPrompt(settings, systemRef)
    return request.copy(
        aiPrompt = request.context.expand(prompt.text),
        // Keep the template until final system-prompt precedence is resolved.
        systemPrompt = system?.prompt ?: request.systemPrompt,
        selectedSystemPromptId = system?.id,
        literalSystemPrompt = request.literalSystemPrompt.takeIf { system == null },
        needsStoredPrompt = false
    )
}

/** Only parameter presets are resolved by name; prompt and system tags are literal text. */
fun resolveExternalParameters(request: PendingExternalReport, settings: Settings): PendingExternalReport {
    val selection = resolveExternalModels(request.modelReferences, request.agentNames, request.flockNames, request.swarmNames, settings)
    val ref = request.parametersReference ?: return request.copy(resolutionErrors = selection.errors)
    val value = ref.trim()
    val byId = settings.parameters.firstOrNull { it.id == value }
    val matches = settings.parameters.filter { it.name.trim().equals(value, ignoreCase = true) }
    val parameters = byId ?: matches.singleOrNull()
    return request.copy(
        selectedParameters = parameters,
        resolutionErrors = selection.errors + if (parameters != null) emptyList() else listOf(
            if (matches.isEmpty()) "Parameters not found: $ref" else "Parameters name is ambiguous: $ref"
        )
    )
}

/** Shared by confirmation validation and the report selection handoff. */
internal data class ExternalModelSelection(val models: List<ReportModel>, val errors: List<String>)

internal fun resolveExternalModels(
    modelReferences: List<String>, agentNames: List<String>, flockNames: List<String>,
    swarmNames: List<String>, settings: Settings
): ExternalModelSelection {
    val models = mutableListOf<ReportModel>()
    val errors = mutableListOf<String>()
    modelReferences.forEach { reference ->
        val separator = reference.lastIndexOf('@')
        val model = if (separator > 0) reference.substring(0, separator).trim() else ""
        val providerName = if (separator >= 0) reference.substring(separator + 1).trim() else ""
        val provider = (AppService.entries + AppService.LOCAL)
            .firstOrNull { it.id.equals(providerName, ignoreCase = true) }
        when {
            model.isBlank() || providerName.isBlank() -> errors.add("Invalid model: $reference. Use model@provider.")
            provider == null -> errors.add("Provider not found: $providerName")
            !settings.isProviderActive(provider) -> errors.add("Provider is inactive: ${provider.id}")
            else -> models.add(toReportModel(provider, model))
        }
    }
    fun <T> addNamed(names: List<String>, entries: List<T>, kind: String, nameOf: (T) -> String, expand: (T) -> List<ReportModel>) {
        names.forEach { name ->
            val matches = entries.filter { nameOf(it).trim().equals(name, ignoreCase = true) }
            if (matches.size != 1) {
                errors.add(if (matches.isEmpty()) "$kind not found: $name" else "$kind name is ambiguous: $name")
            } else {
                val expanded = expand(matches.single())
                if (expanded.isEmpty()) errors.add("$kind has no available models: $name") else models.addAll(expanded)
            }
        }
    }
    addNamed(agentNames, settings.agents, "Agent", { it.name }) { listOfNotNull(expandAgentToModel(it, settings)) }
    addNamed(flockNames, settings.flocks, "Flock", { it.name }) { expandFlockToModels(it, settings) }
    addNamed(swarmNames, settings.swarms, "Swarm", { it.name }) { expandSwarmToModels(it, settings) }
    return ExternalModelSelection(deduplicateModels(models), errors)
}
