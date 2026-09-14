package com.ai.ui.share

import com.ai.model.Settings
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

    fun expand(template: String, presentation: Boolean = false): String {
        if (values.isEmpty()) return template
        return PLACEHOLDERS.replace(template) { match ->
            val key = match.groupValues[1].lowercase(Locale.US)
            when (key) {
                "date" -> values[key] ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                "board" -> if (presentation) values[key] ?: match.value else ""
                else -> values[key] ?: match.value
            }
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

internal fun selectExternalPrompt(request: PendingExternalReport, settings: Settings, prompt: SavedExternalPrompt, systemRef: String?): PendingExternalReport {
    val system = settings.getSystemPromptByIdOrName(systemRef)
    return request.copy(
        aiPrompt = request.context.expand(prompt.text),
        // Keep the template until final system-prompt precedence is resolved.
        systemPrompt = system?.prompt ?: request.systemPrompt,
        selectedSystemPromptId = system?.id,
        needsStoredPrompt = false
    )
}

fun resolveNamedExternalPrompt(request: PendingExternalReport, settings: Settings): PendingExternalReport {
    val errors = mutableListOf<String>()
    fun <T> resolve(ref: String, items: List<T>, id: (T) -> String, name: (T) -> String, label: String): T? {
        val value = ref.trim()
        items.firstOrNull { id(it) == value }?.let { return it }
        val matches = items.filter { name(it).trim().equals(value, ignoreCase = true) }
        return matches.singleOrNull() ?: run {
            errors += if (matches.isEmpty()) "$label not found: $ref" else "$label name is ambiguous: $ref"
            null
        }
    }
    val parameters = request.parametersReference?.let { ref ->
        resolve(ref, settings.parameters, { it.id }, { it.name }, "Parameters")
    }
    val defaultPrompt = request.defaultReference?.let { ref ->
        resolve(ref, settings.defaultPrompts, { it.id }, { it.name }, "Default prompt")
            ?.also { if (it.prompt.isBlank()) errors += "Default prompt is empty: ${it.name}" }
    }
    var resolved = request.copy(selectedParameters = parameters, selectedDefaultPrompt = defaultPrompt)
    val prompt = request.promptReference?.let { ref ->
        // Preserve the legacy saved-prompt picker for unresolved <prompt> references.
        val choices = externalPromptChoices(settings)
        choices.firstOrNull { it.id == ref }
            ?: choices.filter { it.name.equals(ref, ignoreCase = true) }.singleOrNull()
    }
    if (prompt != null) resolved = selectExternalPrompt(resolved, settings, prompt, request.systemReference ?: prompt.system)
    val system = request.systemReference?.let { ref ->
        resolve(ref, settings.systemPrompts, { it.id }, { it.name }, "System prompt")
    }
    if (system != null) resolved = resolved.copy(systemPrompt = system.prompt, selectedSystemPromptId = system.id)
    return resolved.copy(resolutionErrors = errors)
}
