package com.ai.ui.share

import com.ai.model.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Values supplied by an external app, separate from executable report instructions. */
data class ExternalReportContext(val values: Map<String, String> = emptyMap()) {
    fun expand(template: String, presentation: Boolean = false): String {
        if (values.isEmpty()) return template
        return Regex("@(FEN|COLOR|SERVER|PLAYER|PGN|BOARD|DATE)@").replace(template) { match ->
            val key = match.groupValues[1].lowercase(Locale.US)
            when (key) {
                "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                "board" -> if (presentation) values[key].orEmpty() else ""
                else -> values[key].orEmpty()
            }
        }
    }

    companion object {
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
        systemPrompt = system?.prompt?.let { request.context.expand(it) },
        selectedSystemPromptId = system?.id,
        needsStoredPrompt = false
    )
}

fun resolveNamedExternalPrompt(request: PendingExternalReport, settings: Settings): PendingExternalReport {
    val ref = request.promptReference ?: return request
    val prompt = externalPromptChoices(settings).firstOrNull { it.id == ref }
        ?: externalPromptChoices(settings).filter { it.name.equals(ref, ignoreCase = true) }.singleOrNull()
        ?: return request.copy(needsStoredPrompt = true)
    val systemRef = request.systemReference ?: prompt.system
    if (!request.systemReference.isNullOrBlank() && settings.getSystemPromptByIdOrName(request.systemReference) == null)
        return request.copy(needsStoredPrompt = true)
    return selectExternalPrompt(request, settings, prompt, systemRef)
}
