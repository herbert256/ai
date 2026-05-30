package com.ai.data

/**
 * Configuration for AI agent parameters with defaults.
 */
data class AgentParameters(
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val systemPrompt: String? = null,
    val stopSequences: List<String>? = null,
    val seed: Int? = null,
    val responseFormatJson: Boolean = false,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = true,
    val searchRecency: String? = null,
    val webSearchTool: Boolean = false,
    /** Reasoning effort hint for models that support it (gpt-5/o-series
     *  via Responses API, Gemini thinking-models). One of "low", "medium",
     *  "high"; null = unset. Only injected at dispatch when LiteLLM
     *  reports the model supports reasoning. */
    val reasoningEffort: String? = null
)

/**
 * A single message in a chat conversation. When [imageBase64] is non-null,
 * the message carries a vision attachment that the dispatch layer turns into
 * a per-format content block (OpenAI image_url, Anthropic image source,
 * Gemini inline_data). [imageMime] is the MIME type of the encoded bytes.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageBase64: String? = null,
    val imageMime: String? = null
)

