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
    val searchRecency: String? = null
)

/**
 * A single message in a chat conversation.
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Parameters for a chat session.
 */
data class ChatParameters(
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val searchEnabled: Boolean = false,
    val returnCitations: Boolean = true,
    val searchRecency: String? = null
)

/**
 * A saved chat session with all messages.
 */
data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val provider: AppService,
    val model: String,
    val messages: List<ChatMessage>,
    val parameters: ChatParameters = ChatParameters(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val preview: String
        get() = messages.firstOrNull { it.role == "user" }?.content?.take(50) ?: "Empty chat"
}

/**
 * Configuration for a dual-chat session.
 */
data class DualChatConfig(
    val model1Provider: AppService,
    val model1Name: String,
    val model1SystemPrompt: String = "",
    val model1Params: ChatParameters = ChatParameters(),
    val model2Provider: AppService,
    val model2Name: String,
    val model2SystemPrompt: String = "",
    val model2Params: ChatParameters = ChatParameters(),
    val subject: String,
    val interactionCount: Int = 10,
    val firstPrompt: String = "Let's talk about %subject%",
    val secondPrompt: String = "What do you think about: %answer%"
)
