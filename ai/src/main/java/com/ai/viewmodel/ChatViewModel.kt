package com.ai.viewmodel

import android.content.Context
import com.ai.data.*
import com.ai.data.local.LocalLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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
        sessionParams: ChatParameters,
        baseUrl: String? = null,
        webSearchTool: Boolean = false,
        reasoningEffort: String? = null,
        context: android.content.Context? = null,
        knowledgeBaseIds: List<String> = emptyList()
    ): Flow<String> {
        AppLog.d("Chat", "sendChatMessageStream ${service.id}/$model msgs=${messages.size} kbs=${knowledgeBaseIds.size} web=$webSearchTool reasoning=$reasoningEffort")
        // sessionParams is the source of truth for this turn — agent
        // chats pass the agent's preset, resumed chats pass the
        // persisted ChatSession.parameters, configure-on-the-fly
        // chats pass UiState.chatParameters explicitly. The earlier
        // global-uiState read silently shadowed all three with
        // whatever the last configure-on-the-fly chat had set.
        val withWeb = if (webSearchTool && !sessionParams.webSearchTool) sessionParams.copy(webSearchTool = true) else sessionParams
        // Per-turn reasoning override, when supplied. Empty string clears
        // back to "no hint"; null leaves whatever the chat-screen pulldown
        // sent last time (which is also its initial value from the
        // configure-on-the-fly Parameters preset).
        val params = if (reasoningEffort != null) withWeb.copy(reasoningEffort = reasoningEffort.ifBlank { null }) else withWeb
        // RAG retrieval lives inside the cold flow (on Dispatchers.IO)
        // so the embedding call doesn't run on the caller's main-scope
        // coroutine. Pre-RAG path also runs on IO so the SSE
        // charstream-reading inside sendChatStream doesn't run on the
        // collector thread (typically main, when the chat session
        // collects the flow into UI state). Without flowOn(IO) the
        // session's scrolling jittered during high-throughput streams
        // because reader.readLine() blocked the UI dispatcher thread.
        return if (knowledgeBaseIds.isNotEmpty() && context != null) {
            flow {
                val withRag = messagesWithRag(context, knowledgeBaseIds, messages)
                emitAll(appViewModel.repository.sendChatStream(
                    service = service, apiKey = apiKey, model = model,
                    messages = withRag, params = params,
                    baseUrl = baseUrl
                ))
            }.flowOn(Dispatchers.IO)
        } else {
            appViewModel.repository.sendChatStream(
                service = service, apiKey = apiKey, model = model,
                messages = messages, params = params,
                baseUrl = baseUrl
            ).flowOn(Dispatchers.IO)
        }
    }

    /** Build a copy of [messages] with a system-message RAG context
     *  block prepended (or merged into an existing system message)
     *  when retrieval finds matches for the latest user turn.
     *  Suspends on the embedder + cosine sweep; both call sites are
     *  inside flow { } blocks with flowOn(Dispatchers.IO). */
    private suspend fun messagesWithRag(
        context: android.content.Context,
        knowledgeBaseIds: List<String>,
        messages: List<ChatMessage>
    ): List<ChatMessage> {
        val lastUserMessage = messages.lastOrNull { it.role == "user" } ?: return messages
        val lastUser = lastUserMessage.content.takeIf { it.isNotBlank() } ?: run {
            if (!lastUserMessage.imageBase64.isNullOrBlank()) {
                AppLog.i("Chat.RAG", "Skipping KB retrieval for image-only chat turn; text embedder requires a text query")
            }
            return messages
        }
        AppLog.d("Chat.RAG", "retrieving for kbs=${knowledgeBaseIds.joinToString(",")} queryLen=${lastUser.length}")
        val hits = runCatching {
            val retrieved = KnowledgeService.retrieve(context, appViewModel.repository, appViewModel.uiState.value.aiSettings,
                knowledgeBaseIds, lastUser)
            recordRagEmbeddingUsage(context, knowledgeBaseIds, lastUser)
            retrieved
        }.onFailure { e ->
            // Surface retrieval failures (network, auth, dim mismatch,
            // embedder model not available) instead of silently
            // falling back to "no context" — without the log a user
            // sees a perfectly good chat reply and never knows the
            // attached KB didn't contribute.
            AppLog.w("Chat.RAG",
                "Retrieval failed for kbs=$knowledgeBaseIds: ${e.javaClass.simpleName}: ${e.message}")
        }.getOrDefault(emptyList())
        AppLog.d("Chat.RAG", "retrieved ${hits.size} hit(s)")
        if (hits.isEmpty()) return messages
        val ctx = KnowledgeService.formatContextBlock(hits)
        // Merge into the existing system message (preserve user's
        // original system prompt) or insert a new one at the head.
        val existingSystemIndex = messages.indexOfFirst { it.role == "system" }
        return if (existingSystemIndex >= 0) {
            val existing = messages[existingSystemIndex]
            messages.toMutableList().also {
                it[existingSystemIndex] = existing.copy(content = existing.content + "\n\n" + ctx)
            }
        } else {
            listOf(ChatMessage(role = "system", content = ctx)) + messages
        }
    }

    private suspend fun recordRagEmbeddingUsage(
        context: android.content.Context,
        knowledgeBaseIds: List<String>,
        query: String
    ) {
        val kb = knowledgeBaseIds.asSequence()
            .mapNotNull { KnowledgeStore.loadKnowledgeBase(context, it) }
            .firstOrNull()
            ?: return
        val service = AppService.findById(kb.embedderProviderId) ?: return
        if (service != AppService.LOCAL && appViewModel.uiState.value.aiSettings.getApiKey(service).isBlank()) {
            return
        }
        val inputTokens = AppViewModel.estimateTokens(query)
        appViewModel.settingsPrefs.updateUsageStatsAsync(
            service,
            kb.embedderModel,
            inputTokens,
            0,
            inputTokens,
            kind = "chat/rag"
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
        AppLog.d("Chat", "sendDualChatMessage ${service.id}/$model msgs=${messages.size}")
        val response = appViewModel.repository.sendChatResponse(
            service = service, apiKey = apiKey, model = model,
            messages = messages, params = params
        )
        val text = response.analysis ?: throw Exception(response.error ?: "No response content")
        val usage = response.tokenUsage
        if (usage != null && usage.totalTokens > 0) {
            appViewModel.settingsPrefs.updateUsageStatsAsync(service, model, usage, kind = "Dual chat")
        } else {
            val inputTokens = messages.sumOf { AppViewModel.estimateTokens(it.content) }
            val outputTokens = AppViewModel.estimateTokens(text)
            appViewModel.settingsPrefs.updateUsageStatsAsync(
                service,
                model,
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                kind = "Dual chat"
            )
        }
        return text
    }

    /**
     * Send a chat turn to an on-device MediaPipe LLM. The whole
     * conversation is flattened into a single prompt (LlmInference
     * has no built-in turn memory) and the response is emitted as
     * one big chunk — the simple TextEmbedder-grade Tasks API
     * doesn't expose the streaming-token callback. Recompute fits
     * the existing chat-screen flow which collects a Flow<String>.
     */
    fun sendLocalLlmStream(
        context: Context,
        modelName: String,
        messages: List<ChatMessage>,
        knowledgeBaseIds: List<String> = emptyList()
    ): Flow<String> = flow {
        val withRag = if (knowledgeBaseIds.isNotEmpty()) messagesWithRag(context, knowledgeBaseIds, messages) else messages
        // Most chat-tuned local models (Gemma, Phi, Llama) accept a
        // system prefix but require the chat-template wrapper, which
        // is model-specific. Plain user/assistant transcript is the
        // safest fallback. RAG context, when present, comes through
        // as an injected system message and we surface it as a
        // single Context: prefix.
        val prompt = buildString {
            withRag.firstOrNull { it.role == "system" }?.let {
                append(it.content).append("\n\n")
            }
            withRag.filter { it.role != "system" }.forEach { msg ->
                append(when (msg.role) {
                    "user" -> "User: "
                    "assistant" -> "Assistant: "
                    else -> ""
                })
                append(msg.content).append("\n\n")
            }
            append("Assistant: ")
        }
        val out = LocalLlm.generate(context, modelName, prompt)
            ?: throw IllegalStateException("Local LLM \"$modelName\" failed — verify it loaded in Housekeeping → Local LLMs.")
        emit(cleanLocalChatOutput(out))
    }.flowOn(Dispatchers.IO)

    private fun cleanLocalChatOutput(raw: String): String =
        raw
            .substringBefore("\nUser:")
            .substringBefore("\nUSER:")
            .removePrefix("Assistant:")
            .removePrefix("ASSISTANT:")
            .trim()

    /**
     * Record usage statistics for streaming chat (call after stream completes).
     */
    suspend fun recordChatStatistics(
        service: AppService,
        model: String,
        inputTokens: Int,
        outputTokens: Int
    ) {
        appViewModel.settingsPrefs.updateUsageStatsAsync(
            service,
            model,
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            kind = "Chat"
        )
    }
}
