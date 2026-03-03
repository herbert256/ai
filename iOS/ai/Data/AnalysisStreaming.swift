import Foundation

// MARK: - SSE Streaming Parsers

extension AnalysisRepository {

    // MARK: - Dispatcher

    func sendChatMessageStream(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        baseUrl: String? = nil
    ) -> AsyncThrowingStream<String, Error> {
        let effectiveUrl = baseUrl ?? service.baseUrl
        switch service.apiFormat {
        case .anthropic:
            return streamChatClaude(apiKey: apiKey, model: model, messages: messages, params: params, baseUrl: effectiveUrl)
        case .google:
            return streamChatGemini(apiKey: apiKey, model: model, messages: messages, params: params, baseUrl: effectiveUrl)
        case .openaiCompatible:
            return streamChatOpenAiCompatible(service: service, apiKey: apiKey, model: model, messages: messages, params: params, baseUrl: effectiveUrl)
        }
    }

    // MARK: - OpenAI Compatible Streaming

    func streamChatOpenAiCompatible(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        baseUrl: String
    ) -> AsyncThrowingStream<String, Error> {
        let systemPrompt = params.systemPrompt.isEmpty ? nil : params.systemPrompt

        if service.usesResponsesApi(model: model) {
            return streamResponsesApi(service: service, apiKey: apiKey, model: model,
                                      messages: messages, systemPrompt: systemPrompt, baseUrl: baseUrl)
        }

        var openAiMessages: [OpenAiMessage] = []
        if let sp = systemPrompt {
            openAiMessages.append(OpenAiMessage(role: "system", content: sp))
        }
        openAiMessages.append(contentsOf: messages.filter { $0.role != ChatMessage.roleSystem }.map {
            OpenAiMessage(role: $0.role, content: $0.content)
        })

        let request = OpenAiStreamRequest(
            model: model,
            messages: openAiMessages,
            stream: true,
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

        let url = ApiClient.chatUrl(for: service, customBaseUrl: baseUrl)
        let headers = ApiClient.openAiHeaders(apiKey: apiKey)
        guard let body = try? ApiClient.encode(request) else {
            return AsyncThrowingStream { $0.finish(throwing: ApiError.streamError("Failed to encode request")) }
        }

        return parseOpenAiSseStream(url: url, headers: headers, body: body)
    }

    private func streamResponsesApi(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        systemPrompt: String?,
        baseUrl: String
    ) -> AsyncThrowingStream<String, Error> {
        let inputMessages = messages.filter { $0.role != ChatMessage.roleSystem }
            .map { OpenAiResponsesInputMessage(role: $0.role, content: $0.content) }

        let request = OpenAiResponsesStreamRequest(
            model: model,
            input: inputMessages,
            instructions: systemPrompt,
            stream: true
        )

        let normalizedBase = ApiClient.normalizeBaseUrl(baseUrl)
        let url = "\(normalizedBase)v1/responses"
        let headers = ApiClient.openAiHeaders(apiKey: apiKey)
        guard let body = try? ApiClient.encode(request) else {
            return AsyncThrowingStream { $0.finish(throwing: ApiError.streamError("Failed to encode request")) }
        }

        return parseOpenAiResponsesSseStream(url: url, headers: headers, body: body)
    }

    // MARK: - Claude Streaming

    func streamChatClaude(
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        baseUrl: String
    ) -> AsyncThrowingStream<String, Error> {
        let systemPrompt = params.systemPrompt.isEmpty ? nil : params.systemPrompt
        let claudeMessages = messages.filter { $0.role != ChatMessage.roleSystem }
            .map { ClaudeMessage(role: $0.role, content: $0.content) }

        let request = ClaudeStreamRequest(
            model: model,
            messages: claudeMessages,
            stream: true,
            max_tokens: params.maxTokens ?? 4096,
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            system: systemPrompt,
            stop_sequences: nil,
            frequency_penalty: params.frequencyPenalty,
            presence_penalty: params.presencePenalty,
            search: params.searchEnabled ? true : nil
        )

        let normalizedBase = ApiClient.normalizeBaseUrl(baseUrl)
        let url = "\(normalizedBase)v1/messages"
        let headers = ApiClient.anthropicHeaders(apiKey: apiKey)
        guard let body = try? ApiClient.encode(request) else {
            return AsyncThrowingStream { $0.finish(throwing: ApiError.streamError("Failed to encode request")) }
        }

        return parseClaudeSseStream(url: url, headers: headers, body: body)
    }

    // MARK: - Gemini Streaming

    func streamChatGemini(
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        baseUrl: String
    ) -> AsyncThrowingStream<String, Error> {
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

        let url = ApiClient.geminiGenerateUrl(baseUrl: baseUrl, model: model, apiKey: apiKey, stream: true)
        let headers = ApiClient.geminiHeaders()
        guard let body = try? ApiClient.encode(request) else {
            return AsyncThrowingStream { $0.finish(throwing: ApiError.streamError("Failed to encode request")) }
        }

        return parseGeminiSseStream(url: url, headers: headers, body: body)
    }

    // MARK: - SSE Parsers

    private func parseOpenAiSseStream(url: String, headers: [String: String], body: Data) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let stream = await ApiClient.shared.streamSSE(url: url, headers: headers, body: body)
                    for try await line in stream {
                        if Task.isCancelled { break }
                        guard let data = line.data(using: .utf8),
                              let chunk = try? JSONDecoder().decode(OpenAiStreamChunk.self, from: data) else { continue }
                        if let content = chunk.choices?.first?.delta?.content, !content.isEmpty {
                            continuation.yield(content)
                        } else if let reasoning = chunk.choices?.first?.delta?.reasoning_content, !reasoning.isEmpty {
                            continuation.yield(reasoning)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in task.cancel() }
        }
    }

    private func parseOpenAiResponsesSseStream(url: String, headers: [String: String], body: Data) -> AsyncThrowingStream<String, Error> {
        // Responses API uses full SSE with event: and data: lines
        // We need the raw line stream to track event types
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard let requestURL = URL(string: url) else {
                        continuation.finish(throwing: ApiError.invalidURL(url))
                        return
                    }
                    var request = URLRequest(url: requestURL)
                    request.httpMethod = "POST"
                    for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
                    request.httpBody = body
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")

                    let config = URLSessionConfiguration.default
                    config.timeoutIntervalForResource = 600
                    let session = URLSession(configuration: config)

                    let (bytes, response) = try await session.bytes(for: request)
                    guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                        let http = response as? HTTPURLResponse
                        continuation.finish(throwing: ApiError.httpError(http?.statusCode ?? 0, "Stream error"))
                        return
                    }

                    var eventType = ""
                    for try await line in bytes.lines {
                        if Task.isCancelled { break }
                        let trimmed = line.trimmingCharacters(in: .whitespaces)
                        if trimmed.isEmpty { continue }

                        if trimmed.hasPrefix("event: ") {
                            eventType = String(trimmed.dropFirst(7))
                            continue
                        }

                        if trimmed.hasPrefix("data: ") || trimmed.hasPrefix("data:") {
                            let payload = trimmed.hasPrefix("data: ") ? String(trimmed.dropFirst(6)) : String(trimmed.dropFirst(5))
                            if payload == "[DONE]" { break }

                            if eventType == "response.output_text.delta" {
                                if let data = payload.data(using: .utf8),
                                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                                   let delta = json["delta"] as? String, !delta.isEmpty {
                                    continuation.yield(delta)
                                }
                            }
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in task.cancel() }
        }
    }

    private func parseClaudeSseStream(url: String, headers: [String: String], body: Data) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    guard let requestURL = URL(string: url) else {
                        continuation.finish(throwing: ApiError.invalidURL(url))
                        return
                    }
                    var request = URLRequest(url: requestURL)
                    request.httpMethod = "POST"
                    for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
                    request.httpBody = body

                    let config = URLSessionConfiguration.default
                    config.timeoutIntervalForResource = 600
                    let session = URLSession(configuration: config)

                    let (bytes, response) = try await session.bytes(for: request)
                    guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                        let http = response as? HTTPURLResponse
                        continuation.finish(throwing: ApiError.httpError(http?.statusCode ?? 0, "Stream error"))
                        return
                    }

                    var eventType = ""
                    for try await line in bytes.lines {
                        if Task.isCancelled { break }
                        let trimmed = line.trimmingCharacters(in: .whitespaces)
                        if trimmed.isEmpty { continue }

                        if trimmed.hasPrefix("event: ") {
                            eventType = String(trimmed.dropFirst(7))
                            if eventType == "message_stop" { break }
                            continue
                        }

                        if trimmed.hasPrefix("data: ") || trimmed.hasPrefix("data:") {
                            let payload = trimmed.hasPrefix("data: ") ? String(trimmed.dropFirst(6)) : String(trimmed.dropFirst(5))
                            if payload == "[DONE]" { break }

                            if eventType == "content_block_delta" {
                                if let data = payload.data(using: .utf8),
                                   let event = try? JSONDecoder().decode(ClaudeStreamEvent.self, from: data),
                                   let text = event.delta?.text, !text.isEmpty {
                                    continuation.yield(text)
                                }
                            }
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in task.cancel() }
        }
    }

    private func parseGeminiSseStream(url: String, headers: [String: String], body: Data) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let stream = await ApiClient.shared.streamSSE(url: url, headers: headers, body: body)
                    for try await line in stream {
                        if Task.isCancelled { break }
                        guard let data = line.data(using: .utf8),
                              let chunk = try? JSONDecoder().decode(GeminiStreamChunk.self, from: data) else { continue }
                        if let text = chunk.candidates?.first?.content?.parts.first?.text, !text.isEmpty {
                            continuation.yield(text)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { @Sendable _ in task.cancel() }
        }
    }
}
