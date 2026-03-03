package com.ai.data

/**
 * API format used by a provider.
 */
enum class ApiFormat {
    OPENAI_COMPATIBLE,  // 28 providers using OpenAI-compatible chat/completions
    ANTHROPIC,          // Anthropic Messages API
    GOOGLE              // Google Gemini GenerativeAI API
}
