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
    /** Mistral surfaces an `ocr` capability flag on its mistral-ocr-*
     *  entries; the dispatcher tags those with this constant so the
     *  picker can group / surface them as a distinct model kind. */
    const val OCR = "ocr"
    const val UNKNOWN = "unknown"

    /** Every type the user can configure paths for, in display order. UNKNOWN is
     *  intentionally excluded — it's a runtime fallback, not a configurable kind. */
    val ALL: List<String> = listOf(CHAT, RESPONSES, EMBEDDING, RERANK, IMAGE, TTS, STT, MODERATION, CLASSIFY, OCR)

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
            "dall-e" in id || id.startsWith("gpt-image-") || "imagen" in id || "flux" in id || "stable-diffusion" in id || "sdxl" in id -> IMAGE
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

    /** Heuristic for "does this (provider, model) accept a thinking /
     *  reasoning-effort parameter?". Conservative fallback used only
     *  when the provider's own /models response and the LiteLLM /
     *  models.dev catalogs don't carry an explicit reasoning flag.
     *  False is harmless (no badge, no parameter sent); true would
     *  drive the dispatcher to attach a thinking block, so the patterns
     *  stay tight. Provider-aware so an open-source `qwq` shipped via
     *  Anthropic doesn't accidentally hit the OpenAI-style branch. */
    fun inferReasoning(provider: AppService, modelId: String): Boolean {
        val id = modelId.lowercase()
        // Hard negatives win first — providers (notably xAI) ship
        // explicit "-non-reasoning" variants that share their family
        // prefix with reasoning siblings. Without this gate the
        // generic "reasoning" / "grok-4" matches below would flip
        // them positive.
        if ("non-reasoning" in id || "non_reasoning" in id || "no-reasoning" in id) return false
        // Family markers — apply across providers (open-source models ride
        // through OpenRouter, Together, SiliconFlow, Groq with the same
        // base name).
        if (id.endsWith(":thinking") || ":thinking-" in id) return true
        if ("deepseek-r1" in id || id.startsWith("r1-") || "-r1-" in id) return true
        if ("qwq" in id) return true
        if (id.startsWith("qwen3") || "-qwen3-" in id || ":qwen3" in id) return true
        if ("nemotron" in id && ("reasoning" in id || "ultra" in id || "nano" in id)) return true
        if ("magistral" in id) return true
        if ("kimi-thinking" in id || "kimi-k1.5" in id || id.startsWith("kimi-k2")) return true
        if ("gpt-oss" in id) return true
        if ("phi-4-reasoning" in id) return true
        if ("reasoning" in id || "thinking" in id) return true
        return when (provider.apiFormat) {
            ApiFormat.ANTHROPIC -> {
                // Claude 3.7 (initial extended thinking) + every 4.x family.
                "claude-3-7" in id || "claude-3.7" in id ||
                    "opus-4" in id || "sonnet-4" in id || "haiku-4" in id ||
                    Regex("""claude-(opus|sonnet|haiku)-4""").containsMatchIn(id)
            }
            ApiFormat.GOOGLE -> {
                // Gemini 2.5 family (Pro, Flash) ships with thinking; the
                // top-level `thinking` field on the /models response is
                // the authoritative signal — this catches name-only fits.
                id.startsWith("gemini-2.5") || ("gemini" in id && "-2.5" in id)
            }
            ApiFormat.OPENAI_COMPATIBLE -> when (provider.id) {
                "OPENAI" -> {
                    // gpt-5 family + o-series reasoning models.
                    id.startsWith("gpt-5") ||
                        id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
                }
                "XAI" -> {
                    // Most current xAI models perform reasoning. The
                    // explicit "-non-reasoning" variants are filtered by
                    // the early hard-negative gate, so anything in the
                    // grok-3 / grok-4.x families counts here. The
                    // "always-on, no parameter" subset (grok-4.3,
                    // grok-4.20-multi-agent, grok-code-fast-…) still
                    // gets a 🧠 badge — they reason, they just don't
                    // expose `reasoning_effort`. Whether the request
                    // sends the parameter is decided separately by
                    // [inferAcceptsReasoningEffortParam].
                    id.startsWith("grok-3") || id.startsWith("grok-4") ||
                        id.startsWith("grok-code")
                }
                "MOONSHOT" -> id.startsWith("kimi-k1.5") || id.startsWith("kimi-k2") || "thinking" in id
                "DEEPSEEK" -> "r1" in id || "reasoner" in id
                "MISTRAL" -> "magistral" in id
                else -> false
            }
        }
    }

    /** Providers whose "is reasoning model" signal from external
     *  metadata can't be reused to decide "accepts the
     *  `reasoning_effort` parameter". xAI ships always-on reasoning
     *  models (grok-4.3, grok-4.20-multi-agent, grok-code-fast-…) that
     *  reason internally but reject the parameter — we still want the
     *  🧠 badge for these (per [inferReasoning]) but the dispatcher
     *  must skip the parameter (per [inferAcceptsReasoningEffortParam]). */
    fun externalReasoningSignalUntrusted(provider: AppService): Boolean =
        provider.id == "XAI"

    /** Narrow companion to [inferReasoning]: returns true only when the
     *  model exposes a controllable reasoning parameter we can attach
     *  (`reasoning_effort` for OpenAI-compat, the `thinking` block for
     *  Anthropic, `thinkingConfig` for Gemini). For most providers this
     *  matches [inferReasoning]; xAI is the exception — its always-on
     *  variants reason but reject the parameter, so we narrow the xAI
     *  branch to the controllable subset (grok-3 family, grok-4 /
     *  grok-4-0… dated builds, anything ending in "-reasoning"). */
    fun inferAcceptsReasoningEffortParam(provider: AppService, modelId: String): Boolean {
        val id = modelId.lowercase()
        if ("non-reasoning" in id || "non_reasoning" in id || "no-reasoning" in id) return false
        if (provider.id == "XAI") {
            // Controllable: grok-3* (incl. mini), grok-4 / grok-4-0709,
            // any *-reasoning suffix (e.g. grok-4-fast-reasoning,
            // grok-4.20-0309-reasoning). Excludes grok-4.3, grok-4.20
            // multi-agent, grok-code-fast-… — those are always-on.
            return id.startsWith("grok-3") || id == "grok-4" ||
                id.startsWith("grok-4-0") || id.endsWith("-reasoning")
        }
        return inferReasoning(provider, modelId)
    }
}

/** Result type for model-list fetches: ids in their native order, a type
 *  map, the subset known to accept image input (auto-flagged from list-API
 *  metadata where available, e.g. OpenRouter input_modalities), and a
 *  per-model capability bundle the fetcher built from any provider-native
 *  fields it surfaced (Mistral `capabilities`, Gemini token limits, Cohere
 *  context_length, etc.). */
data class FetchedModels(
    val ids: List<String>,
    val types: Map<String, String>,
    val visionModels: Set<String> = emptySet(),
    val capabilities: Map<String, ModelCapabilities> = emptyMap(),
    /** Raw JSON body of the provider's /models response, captured at fetch
     *  time. Persisted to eval_prefs alongside the parsed model list so a
     *  later parser revision can extract additional fields without
     *  re-hitting the provider. */
    val rawResponse: String? = null
)

/** Per-model capability bundle derived from a provider's own /models
 *  endpoint. Authoritative when populated since it's the provider's
 *  self-report. Empty fields fall through to LiteLLM / models.dev /
 *  heuristic in the lookup chain. */
data class ModelCapabilities(
    val supportsVision: Boolean? = null,
    val supportsFunctionCalling: Boolean? = null,
    val contextLength: Int? = null,
    val maxOutputTokens: Int? = null,
    /** Provider-self-reported "this model exposes a thinking /
     *  reasoning_effort parameter". Surfaces from each provider's
     *  /models response: Anthropic `capabilities.thinking.supported`,
     *  Gemini top-level `thinking`, Mistral `capabilities.reasoning`,
     *  xAI / OpenRouter `supported_parameters` containing "reasoning"
     *  or "include_reasoning". Null when the response doesn't carry
     *  the field — the lookup chain then falls through to LiteLLM /
     *  models.dev / the [ModelType.inferReasoning] heuristic. */
    val supportsReasoning: Boolean? = null,
    /** Subset of "low", "medium", "high", "max" the model accepts
     *  on the reasoning_effort parameter. Currently populated from
     *  Anthropic's `capabilities.effort.{low,medium,high,max}` —
     *  Claude 3.7 / 4.x report different sets per tier. Empty / null
     *  means "no per-level info; the 🧠 dropdown falls back to all
     *  four options". */
    val reasoningEffortLevels: List<String>? = null,
    /** Native PDF ingestion — the model accepts a document content
     *  block with raw PDF bytes (Claude 3.5+). Distinct from the
     *  vision flag because PDFs are not images: Anthropic parses
     *  page text + embedded images server-side, no client-side OCR
     *  needed. Currently populated from Anthropic
     *  `capabilities.pdf_input.supported`. */
    val supportsPdfInput: Boolean? = null
)
