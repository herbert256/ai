package com.ai.data

/**
 * API format used by a provider.
 */
enum class ApiFormat {
    OPENAI_COMPATIBLE,  // most providers — OpenAI-compatible chat/completions
    ANTHROPIC,          // Anthropic Messages API
    GOOGLE,             // Google Gemini GenerativeAI API
    REPLICATE           // Replicate async predictions API (run via Prefer: wait)
}
