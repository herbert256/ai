import Foundation
import os

/// Non-streaming chat methods.
extension AnalysisRepository {

    // MARK: - Chat Entry Point

    func sendChatMessage(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        customBaseUrl: String? = nil
    ) async throws -> String {
        switch service.apiFormat {
        case .openaiCompatible:
            return try await sendChatMessageOpenAiCompatible(service: service, apiKey: apiKey, model: model, messages: messages, params: params, customBaseUrl: customBaseUrl)
        case .anthropic:
            return try await sendChatMessageClaude(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        case .google:
            return try await sendChatMessageGemini(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        }
    }

    // MARK: - OpenAI Compatible

    private func sendChatMessageOpenAiCompatible(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        customBaseUrl: String? = nil
    ) async throws -> String {
        if usesResponsesApi(service, model: model) {
            return try await sendChatMessageResponsesApi(service: service, apiKey: apiKey, model: model, messages: messages, params: params, customBaseUrl: customBaseUrl)
        }

        let openAiMessages = convertToOpenAiMessages(messages, systemPrompt: params.systemPrompt)

        let request = OpenAiRequest(
            model: model,
            messages: openAiMessages,
            max_tokens: params.maxTokens,
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            frequency_penalty: params.frequencyPenalty,
            presence_penalty: params.presencePenalty,
            return_citations: service.supportsCitations ? params.returnCitations : nil,
            search_recency_filter: service.supportsSearchRecency ? params.searchRecency : nil,
            search: params.searchEnabled ? true : nil
        )

        let url = ApiClient.chatUrl(for: service, customBaseUrl: customBaseUrl)
        let (response, _): (OpenAiResponse, HTTPURLResponse) = try await ApiClient.shared.request(
            url: url, body: request, service: service, apiKey: apiKey
        )

        if let error = response.error {
            throw ApiError.httpError(0, error.message ?? "Unknown error")
        }

        return response.choices?.first?.message.content ?? response.choices?.first?.message.reasoning_content ?? ""
    }

    // MARK: - Responses API

    private func sendChatMessageResponsesApi(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        customBaseUrl: String? = nil
    ) async throws -> String {
        let inputMessages = messages.filter { $0.role != "system" }.map {
            OpenAiResponsesInputMessage(role: $0.role, content: $0.content)
        }

        let request: OpenAiResponsesRequest
        if inputMessages.count == 1, let first = inputMessages.first, first.role == "user" {
            request = OpenAiResponsesRequest(model: model, input: first.content,
                                             instructions: params.systemPrompt.isEmpty ? nil : params.systemPrompt)
        } else {
            request = OpenAiResponsesRequest(model: model, input: inputMessages,
                                             instructions: params.systemPrompt.isEmpty ? nil : params.systemPrompt)
        }

        let base = customBaseUrl ?? service.baseUrl
        let normalizedBase = base.hasSuffix("/") ? base : "\(base)/"
        let url = "\(normalizedBase)v1/responses"

        let (response, _): (OpenAiResponsesApiResponse, HTTPURLResponse) = try await ApiClient.shared.request(
            url: url, body: request, service: service, apiKey: apiKey
        )

        if let error = response.error {
            throw ApiError.httpError(0, error.message ?? "Unknown error")
        }

        return response.output?
            .compactMap { msg in msg.content?.first(where: { $0.type == "output_text" })?.text ?? msg.content?.first?.text }
            .first ?? ""
    }

    // MARK: - Claude

    private func sendChatMessageClaude(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        let claudeMessages = messages.filter { $0.role != "system" }.map {
            ClaudeMessage(role: $0.role == "assistant" ? "assistant" : "user", content: $0.content)
        }

        let request = ClaudeRequest(
            model: model,
            max_tokens: params.maxTokens ?? 4096,
            messages: claudeMessages,
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            system: params.systemPrompt.isEmpty ? nil : params.systemPrompt,
            search: params.searchEnabled ? true : nil
        )

        let base = service.baseUrl.hasSuffix("/") ? service.baseUrl : "\(service.baseUrl)/"
        let url = "\(base)v1/messages"

        let (response, _): (ClaudeResponse, HTTPURLResponse) = try await ApiClient.shared.request(
            url: url, body: request, service: service, apiKey: apiKey
        )

        if let error = response.error {
            throw ApiError.httpError(0, error.message ?? "Unknown error")
        }

        return response.content?.first(where: { $0.type == "text" })?.text ?? ""
    }

    // MARK: - Gemini

    private func sendChatMessageGemini(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        let geminiMessages = messages.filter { $0.role != "system" }.map {
            GeminiContent(parts: [GeminiPart(text: $0.content)], role: $0.role == "assistant" ? "model" : "user")
        }

        let config = GeminiGenerationConfig(
            temperature: params.temperature,
            topP: params.topP,
            topK: params.topK,
            maxOutputTokens: params.maxTokens
        )

        let systemInstruction = params.systemPrompt.isEmpty ? nil : GeminiContent(parts: [GeminiPart(text: params.systemPrompt)])

        let request = GeminiRequest(
            contents: geminiMessages,
            generationConfig: config,
            systemInstruction: systemInstruction
        )

        let url = ApiClient.geminiGenerateUrl(baseUrl: service.baseUrl, model: model, apiKey: apiKey)

        let dummyService = AppService(id: "gemini_temp", displayName: "", baseUrl: "", defaultModel: "", apiFormat: .google)
        let (response, _): (GeminiResponse, HTTPURLResponse) = try await ApiClient.shared.request(
            url: url, body: request, service: dummyService, apiKey: apiKey
        )

        if let error = response.error {
            throw ApiError.httpError(error.code ?? 0, error.message ?? "Unknown error")
        }

        return response.candidates?.first?.content?.parts.first?.text ?? ""
    }
}
