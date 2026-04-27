package com.ai.viewmodel

import com.ai.data.*
import kotlinx.coroutines.flow.Flow

/**
 * ViewModel for chat operations: send messages, streaming, dual chat, usage statistics.
 * Delegates to AppViewModel for shared state and settings.
 */
class ChatViewModel(private val appViewModel: AppViewModel) {

    /**
     * Send a chat message with streaming response.
     * Returns a Flow that emits content chunks as they arrive.
     */
    fun sendChatMessageStream(
        service: AppService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        baseUrl: String? = null,
        webSearchTool: Boolean = false
    ): Flow<String> {
        val base = appViewModel.uiState.value.chatParameters
        val params = if (webSearchTool && !base.webSearchTool) base.copy(webSearchTool = true) else base
        return appViewModel.repository.sendChatStream(
            service = service, apiKey = apiKey, model = model,
            messages = messages, params = params,
            baseUrl = baseUrl
        )
    }

    /**
     * Send a chat message for dual chat with explicit ChatParameters.
     * Throws on error.
     */
    suspend fun sendDualChatMessage(
        service: AppService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        params: ChatParameters
    ): String {
        val response = appViewModel.repository.sendChat(
            service = service, apiKey = apiKey, model = model,
            messages = messages, params = params
        )
        val inputTokens = messages.sumOf { AppViewModel.estimateTokens(it.content) }
        val outputTokens = AppViewModel.estimateTokens(response)
        appViewModel.settingsPrefs.updateUsageStatsAsync(service, model, inputTokens, outputTokens, inputTokens + outputTokens)
        return response
    }

    /**
     * Record usage statistics for streaming chat (call after stream completes).
     */
    suspend fun recordChatStatistics(
        service: AppService,
        model: String,
        inputTokens: Int,
        outputTokens: Int
    ) {
        appViewModel.settingsPrefs.updateUsageStatsAsync(service, model, inputTokens, outputTokens, inputTokens + outputTokens)
    }
}
