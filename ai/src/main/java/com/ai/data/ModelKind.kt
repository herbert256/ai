package com.ai.data

/**
 * Single-string taxonomy for model capabilities. Models can in reality span multiple
 * categories (a vision model is "chat with image input"), but the app uses one kind
 * per model to drive dispatch — pick the dominant capability.
 *
 * Detection happens in two passes:
 *   1. Native list-API fields where the provider exposes them
 *      (`fromOpenRouterModality`, `fromCohereEndpoints`, `fromGeminiMethods`).
 *   2. Naming heuristic on the model id (`infer`) for everyone else.
 */
object ModelKind {
    const val CHAT = "chat"
    const val EMBEDDING = "embedding"
    const val RERANK = "rerank"
    const val IMAGE = "image"
    const val TTS = "tts"
    const val STT = "stt"
    const val MODERATION = "moderation"
    const val CLASSIFY = "classify"
    const val UNKNOWN = "unknown"

    /**
     * Naming-based fallback. Conservative: anything not matching a non-chat pattern
     * is assumed CHAT, which is the right default for the long tail of LLM names.
     */
    fun infer(modelId: String): String {
        val id = modelId.lowercase()
        return when {
            "embed" in id -> EMBEDDING
            "rerank" in id -> RERANK
            "classifier" in id || id.endsWith("classify") -> CLASSIFY
            "moderation" in id -> MODERATION
            "whisper" in id || "transcrib" in id -> STT
            "tts" in id || "speech-2" in id || "text-to-speech" in id -> TTS
            "dall-e" in id || "imagen" in id || "flux" in id || "stable-diffusion" in id || "sdxl" in id -> IMAGE
            else -> CHAT
        }
    }

    /**
     * Map an OpenRouter `architecture.modality` value (e.g. "text->text",
     * "text+image->text", "text->image") to a kind.
     */
    fun fromOpenRouterModality(modality: String?): String? {
        if (modality.isNullOrBlank()) return null
        val output = modality.substringAfter("->", missingDelimiterValue = modality)
        return when {
            "image" in output -> IMAGE
            "audio" in output -> TTS
            "text" in output -> CHAT
            else -> null
        }
    }

    /** Pick the most specific kind from a Cohere `endpoints` array. */
    fun fromCohereEndpoints(endpoints: List<String>?): String? {
        if (endpoints.isNullOrEmpty()) return null
        val lower = endpoints.map { it.lowercase() }
        return when {
            "embed" in lower -> EMBEDDING
            "rerank" in lower -> RERANK
            "classify" in lower -> CLASSIFY
            "chat" in lower || "summarize" in lower || "generate" in lower -> CHAT
            else -> null
        }
    }

    /** Map Gemini `supportedGenerationMethods` to a kind. */
    fun fromGeminiMethods(methods: List<String>?): String? {
        if (methods.isNullOrEmpty()) return null
        return when {
            "embedContent" in methods -> EMBEDDING
            "generateContent" in methods -> CHAT
            else -> null
        }
    }
}

/** Result type for model-list fetches: ids in their native order plus a kind map. */
data class FetchedModels(val ids: List<String>, val kinds: Map<String, String>)
