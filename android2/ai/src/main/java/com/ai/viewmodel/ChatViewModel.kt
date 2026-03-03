package com.ai.viewmodel

import com.ai.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * ViewModel for chat operations: send messages, streaming, dual chat, usage statistics.
 * Delegates to AppViewModel for shared state and settings.
 */
class ChatViewModel(private val appViewModel: AppViewModel) {

    suspend fun sendChatMessage(
        service: AppService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>
    ): ChatMessage {
        return try {
            val response = appViewModel.repository.sendChat(
                service = service, apiKey = apiKey, model = model,
                messages = messages, params = appViewModel.uiState.value.chatParameters
            )
            val inputTokens = messages.sumOf { AppViewModel.estimateTokens(it.content) }
            val outputTokens = AppViewModel.estimateTokens(response)
            appViewModel.settingsPrefs.updateUsageStatsAsync(service, model, inputTokens, outputTokens, inputTokens + outputTokens)
            ChatMessage(role = "assistant", content = response)
        } catch (e: Exception) {
            ChatMessage(role = "assistant", content = "Error: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Send a chat message with streaming response.
     * Returns a Flow that emits content chunks as they arrive.
     */
    fun sendChatMessageStream(
        service: AppService,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        baseUrl: String? = null
    ): Flow<String> {
        return appViewModel.repository.sendChatStream(
            service = service, apiKey = apiKey, model = model,
            messages = messages, params = appViewModel.uiState.value.chatParameters,
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
    fun recordChatStatistics(
        scope: kotlinx.coroutines.CoroutineScope,
        service: AppService,
        model: String,
        inputTokens: Int,
        outputTokens: Int
    ) {
        scope.launch(Dispatchers.IO) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(service, model, inputTokens, outputTokens, inputTokens + outputTokens)
        }
    }
}
