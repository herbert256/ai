package com.ai.data

/**
 * Single-string taxonomy for what a model does. A model gets one type per the
 * dominant capability — vision-capable chat is still CHAT, dated chat snapshots
 * routed via OpenAI's Responses API are RESPONSES, and so on.
 *
 * Detection happens in two passes:
 *   1. Native list-API fields where the provider exposes them
 *      (`fromOpenRouterModality`, `fromCohereEndpoints`, `fromGeminiMethods`).
 *   2. Naming heuristic on the model id (`infer`) for everyone else.
 *
 * Each type also has a default path on the provider — see GeneralSettings
 * defaultTypePaths and the per-provider typePaths map.
 */
object ModelType {
    const val CHAT = "chat"
    const val RESPONSES = "responses"
    const val EMBEDDING = "embedding"
    const val RERANK = "rerank"
    const val IMAGE = "image"
    const val TTS = "tts"
    const val STT = "stt"
    const val MODERATION = "moderation"
    const val CLASSIFY = "classify"
    const val UNKNOWN = "unknown"

    /** Every type the user can configure paths for, in display order. UNKNOWN is
     *  intentionally excluded — it's a runtime fallback, not a configurable kind. */
    val ALL: List<String> = listOf(CHAT, RESPONSES, EMBEDDING, RERANK, IMAGE, TTS, STT, MODERATION, CLASSIFY)

    /** User-supplied global defaults from AI Setup → Model Types. Sits between the
     *  per-provider override and the hardcoded DEFAULT_PATHS. AppViewModel keeps
     *  this in sync with GeneralSettings.defaultTypePaths on every settings save. */
    @Volatile var userDefaults: Map<String, String> = emptyMap()

    /** Sensible default path for each type; used as the last-resort fallback if
     *  neither the per-provider override nor the GeneralSettings default is set. */
    val DEFAULT_PATHS: Map<String, String> = mapOf(
        CHAT to "v1/chat/completions",
        RESPONSES to "v1/responses",
        EMBEDDING to "v1/embeddings",
        RERANK to "v1/rerank",
        IMAGE to "v1/images/generations",
        TTS to "v1/audio/speech",
        STT to "v1/audio/transcriptions",
        MODERATION to "v1/moderations",
        CLASSIFY to "v1/classify"
    )

    /**
     * Naming-based fallback. Conservative: anything not matching a non-chat pattern
     * is assumed CHAT, which is the right default for the long tail of LLM names.
     *
     * The gpt-5 / o3 / o4 prefixes that used to live in OpenAI's endpointRules now
     * live here too — those families ship without a chat-completions endpoint and
     * have to dispatch through the Responses API.
     */
    fun infer(modelId: String): String {
        val id = modelId.lowercase()
        return when {
            id.startsWith("gpt-5") || id.startsWith("o3") || id.startsWith("o4") -> RESPONSES
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

    /** Map an OpenRouter `architecture.modality` value to a type. */
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

    /** Pick the most specific type from a Cohere `endpoints` array. */
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

    /** Map Gemini `supportedGenerationMethods` to a type. */
    fun fromGeminiMethods(methods: List<String>?): String? {
        if (methods.isNullOrEmpty()) return null
        return when {
            "embedContent" in methods -> EMBEDDING
            "generateContent" in methods -> CHAT
            else -> null
        }
    }
}

/** Result type for model-list fetches: ids in their native order plus a type map. */
data class FetchedModels(val ids: List<String>, val types: Map<String, String>)
