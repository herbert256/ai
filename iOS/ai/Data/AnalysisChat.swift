import Foundation

// MARK: - Non-Streaming Chat Methods

extension AnalysisRepository {

    // MARK: - Dispatch

    func sendChatMessage(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        switch service.apiFormat {
        case .anthropic:
            return try await sendChatMessageClaude(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        case .google:
            return try await sendChatMessageGemini(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        case .openaiCompatible:
            return try await sendChatMessageOpenAiCompatible(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        }
    }

    // MARK: - OpenAI Compatible

    private func sendChatMessageOpenAiCompatible(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        // Check Responses API
        if service.usesResponsesApi(model: model) {
            return try await sendChatMessageResponsesApi(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        }

        var openAiMessages: [OpenAiMessage] = []
        if !params.systemPrompt.isEmpty {
            openAiMessages.append(OpenAiMessage(role: "system", content: params.systemPrompt))
        }
        openAiMessages.append(contentsOf: messages.filter { $0.role != ChatMessage.roleSystem }.map {
            OpenAiMessage(role: $0.role, content: $0.content)
        })

        let request = OpenAiRequest(
            model: model,
            messages: openAiMessages,
            max_tokens: params.maxTokens,
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            frequency_penalty: params.frequencyPenalty,
            presence_penalty: params.presencePenalty,
            search: params.searchEnabled ? true : nil
        )

        let url = ApiClient.chatUrl(for: service)
        let headers = ApiClient.openAiHeaders(apiKey: apiKey)
        let body = try ApiClient.encode(request)

        let (response, _, _): (OpenAiResponse, Int, [String: String]) =
            try await ApiClient.shared.sendRequest(url: url, headers: headers, body: body, responseType: OpenAiResponse.self)

        guard let content = response.choices?.first?.message.content else {
            throw ApiError.streamError("No response content")
        }
        return content
    }

    // MARK: - Responses API

    private func sendChatMessageResponsesApi(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        let systemPrompt = params.systemPrompt.isEmpty ? nil : params.systemPrompt
        let inputMessages = messages.filter { $0.role != ChatMessage.roleSystem }
            .map { OpenAiResponsesInputMessage(role: $0.role, content: $0.content) }

        let request: OpenAiResponsesRequest
        if inputMessages.count == 1, inputMessages.first?.role == "user" {
            request = OpenAiResponsesRequest(model: model, input: .text(inputMessages.first!.content), instructions: systemPrompt)
        } else {
            request = OpenAiResponsesRequest(model: model, input: .messages(inputMessages), instructions: systemPrompt)
        }

        let normalizedBase = ApiClient.normalizeBaseUrl(service.baseUrl)
        let url = "\(normalizedBase)v1/responses"
        let headers = ApiClient.openAiHeaders(apiKey: apiKey)
        let body = try ApiClient.encode(request)

        let (data, statusCode, _) = try await ApiClient.shared.sendRawRequest(url: url, headers: headers, body: body)

        guard (200...299).contains(statusCode) else {
            let errorBody = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw ApiError.httpError(statusCode, errorBody)
        }

        let response = try ApiClient.decode(OpenAiResponsesApiResponse.self, from: data)

        let firstOutput = response.output?.first
        let firstContent = firstOutput?.content
        let content: String? = firstContent?.first(where: { $0.type == "output_text" })?.text
            ?? firstContent?.first(where: { $0.type == "text" })?.text
            ?? firstContent?.compactMap({ $0.text }).first
            ?? response.output?.flatMap({ $0.content ?? [] }).compactMap({ $0.text }).first

        guard let content = content else {
            throw ApiError.streamError("No response content from Responses API")
        }
        return content
    }

    // MARK: - Claude

    private func sendChatMessageClaude(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        let systemPrompt = params.systemPrompt.isEmpty ? nil : params.systemPrompt
        let claudeMessages = messages.filter { $0.role != ChatMessage.roleSystem }
            .map { ClaudeMessage(role: $0.role, content: $0.content) }

        let request = ClaudeRequest(
            model: model,
            max_tokens: params.maxTokens ?? 4096,
            messages: claudeMessages,
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            system: systemPrompt,
            search: params.searchEnabled ? true : nil
        )

        let normalizedBase = ApiClient.normalizeBaseUrl(service.baseUrl)
        let url = "\(normalizedBase)v1/messages"
        let headers = ApiClient.anthropicHeaders(apiKey: apiKey)
        let body = try ApiClient.encode(request)

        let (response, _, _): (ClaudeResponse, Int, [String: String]) =
            try await ApiClient.shared.sendRequest(url: url, headers: headers, body: body, responseType: ClaudeResponse.self)

        guard let content = response.content?.first(where: { $0.type == "text" })?.text else {
            throw ApiError.streamError("No response content from Claude")
        }
        return content
    }

    // MARK: - Gemini

    private func sendChatMessageGemini(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        let contents = messages.filter { $0.role != ChatMessage.roleSystem }
            .map { msg in
                GeminiContent(
                    parts: [GeminiPart(text: msg.content)],
                    role: msg.role == ChatMessage.roleUser ? "user" : "model"
                )
            }

        let systemInstruction = messages.first(where: { $0.role == ChatMessage.roleSystem }).map {
            GeminiContent(parts: [GeminiPart(text: $0.content)])
        } ?? (params.systemPrompt.isEmpty ? nil : GeminiContent(parts: [GeminiPart(text: params.systemPrompt)]))

        let request = GeminiRequest(
            contents: contents,
            generationConfig: GeminiGenerationConfig(
                temperature: params.temperature,
                topP: params.topP,
                topK: params.topK,
                maxOutputTokens: params.maxTokens,
                search: params.searchEnabled ? true : nil
            ),
            systemInstruction: systemInstruction
        )

        let url = ApiClient.geminiGenerateUrl(baseUrl: service.baseUrl, model: model, apiKey: apiKey)
        let headers = ApiClient.geminiHeaders()
        let body = try ApiClient.encode(request)

        let (response, _, _): (GeminiResponse, Int, [String: String]) =
            try await ApiClient.shared.sendRequest(url: url, headers: headers, body: body, responseType: GeminiResponse.self)

        guard let content = response.candidates?.first?.content?.parts.first?.text else {
            throw ApiError.streamError("No response content from Gemini")
        }
        return content
    }
}
