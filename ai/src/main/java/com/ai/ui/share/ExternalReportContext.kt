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
    val ref = request.promptReference ?: run {
        val systemRef = request.systemReference ?: return request
        val system = settings.getSystemPromptByIdOrName(systemRef)
            ?: return request.copy(needsStoredPrompt = true)
        return request.copy(systemPrompt = system.prompt, selectedSystemPromptId = system.id)
    }
    val prompt = externalPromptChoices(settings).firstOrNull { it.id == ref }
        ?: externalPromptChoices(settings).filter { it.name.equals(ref, ignoreCase = true) }.singleOrNull()
        ?: return request.copy(needsStoredPrompt = true)
    val systemRef = request.systemReference ?: prompt.system
    if (!request.systemReference.isNullOrBlank() && settings.getSystemPromptByIdOrName(request.systemReference) == null)
        return request.copy(needsStoredPrompt = true)
    return selectExternalPrompt(request, settings, prompt, systemRef)
}
