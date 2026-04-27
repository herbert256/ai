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

    /** Naming-based fallback for "does this chat model accept image input?".
     *  Conservative: only flags families known to support vision today. False
     *  on a vision-capable model is harmless (the user can tick the override
     *  on Model Info); true on a non-vision model would be a regression we
     *  want to avoid, so the patterns stay tight. */
    fun inferVision(modelId: String): Boolean {
        val id = modelId.lowercase()
        return when {
            // OpenAI vision-capable chat families
            "gpt-4o" in id || "gpt-4-vision" in id || "gpt-4-turbo" in id -> true
            "gpt-5" in id || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> true
            "chatgpt-4o" in id -> true
            // Anthropic — every Claude 3.x and 4.x is vision-capable; 2.x is not.
            Regex("""claude-(3|4|opus-4|sonnet-4|haiku-4)""").containsMatchIn(id) -> true
            // Google
            "gemini-1.5" in id || "gemini-2" in id || "gemini-pro-vision" in id -> true
            // Open-source vision/multimodal
            "llava" in id || "pixtral" in id || "qwen2-vl" in id || "qwen2.5-vl" in id -> true
            "internvl" in id || "minicpm-v" in id || "molmo" in id || "phi-3-vision" in id -> true
            "phi-3.5-vision" in id || "phi-4-multimodal" in id -> true
            // Meta vision (e.g. llama-3.2-90b-vision)
            "llama-3.2" in id && "vision" in id -> true
            // Mistral
            "pixtral" in id -> true
            // Generic markers
            "vision" in id || "-vl-" in id || id.endsWith("-vl") || "multimodal" in id -> true
            else -> false
        }
    }

    /** OpenRouter `architecture.input_modalities` includes "image" iff the
     *  model accepts image input. The detailed model list populates this. */
    fun fromOpenRouterInputModalities(modalities: List<String>?): Boolean =
        modalities?.any { it.equals("image", ignoreCase = true) } == true

    /** Heuristic for "does this (provider, model) support the web-search
     *  tool descriptor?". Mirrors what the dispatch layer actually injects
     *  per ApiFormat in ApiDispatch.kt:
     *
     *   - ANTHROPIC: web_search_20250305 was introduced for Claude 3.5/3.7
     *     and the 4.x family.
     *   - GOOGLE: google_search tool works on Gemini 1.5+ and 2.x.
     *   - OPENAI_COMPATIBLE: only the Responses-API models (gpt-5, o-series,
     *     gpt-4.1) get a web_search_preview tool — Chat Completions skips.
     *
     *  Conservative — false on miss; the user can pin via Model Info. */
    fun inferWebSearch(provider: AppService, modelId: String): Boolean {
        val id = modelId.lowercase()
        return when (provider.apiFormat) {
            ApiFormat.ANTHROPIC -> {
                "claude-3-5" in id || "claude-3.5" in id ||
                    "claude-3-7" in id || "claude-3.7" in id ||
                    "sonnet-4" in id || "opus-4" in id || "haiku-4" in id ||
                    Regex("""claude-(opus|sonnet|haiku)-4""").containsMatchIn(id)
            }
            ApiFormat.GOOGLE -> {
                "gemini-1.5" in id || "gemini-2" in id || "gemini-pro" in id
            }
            ApiFormat.OPENAI_COMPATIBLE -> {
                // Responses API path (matches infer() above).
                id.startsWith("gpt-5") || id.startsWith("o1") ||
                    id.startsWith("o3") || id.startsWith("o4") ||
                    id.startsWith("gpt-4.1")
            }
        }
    }
}

/** Result type for model-list fetches: ids in their native order, a type
 *  map, and the subset known to accept image input (auto-flagged from
 *  list-API metadata where available, e.g. OpenRouter input_modalities). */
data class FetchedModels(
    val ids: List<String>,
    val types: Map<String, String>,
    val visionModels: Set<String> = emptySet()
)
