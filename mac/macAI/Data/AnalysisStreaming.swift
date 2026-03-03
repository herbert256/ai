import Foundation
import os

/// SSE streaming parsers -> AsyncThrowingStream.
extension AnalysisRepository {

    // MARK: - Stream Entry Point

    func sendChatMessageStream(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        customBaseUrl: String? = nil
    ) -> AsyncThrowingStream<String, Error> {
        switch service.apiFormat {
        case .openaiCompatible:
            return streamChatOpenAiCompatible(service: service, apiKey: apiKey, model: model, messages: messages, params: params, customBaseUrl: customBaseUrl)
        case .anthropic:
            return streamChatClaude(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        case .google:
            return streamChatGemini(service: service, apiKey: apiKey, model: model, messages: messages, params: params)
        }
    }

    // MARK: - OpenAI Compatible Streaming

    private func streamChatOpenAiCompatible(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        customBaseUrl: String? = nil
    ) -> AsyncThrowingStream<String, Error> {
        // Check for Responses API
        if usesResponsesApi(service, model: model) {
            return streamResponsesApi(service: service, apiKey: apiKey, model: model, messages: messages, params: params, customBaseUrl: customBaseUrl)
        }

        let openAiMessages = convertToOpenAiMessages(messages, systemPrompt: params.systemPrompt)

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
            seed: service.seedFieldName == "seed" ? nil : nil,  // Seed not typically used in chat streaming
            return_citations: service.supportsCitations ? params.returnCitations : nil,
            search_recency_filter: service.supportsSearchRecency ? params.searchRecency : nil,
            search: params.searchEnabled ? true : nil
        )

        let url = ApiClient.chatUrl(for: service, customBaseUrl: customBaseUrl)

        return AsyncThrowingStream { continuation in
            Task {
                do {
                    let (bytes, _) = try await ApiClient.shared.streamRequest(url: url, body: request, service: service, apiKey: apiKey)
                    for try await line in bytes.lines {
                        if line.hasPrefix("data: ") {
                            let data = String(line.dropFirst(6))
                            if data == "[DONE]" { break }
                            if let jsonData = data.data(using: .utf8),
                               let chunk = try? JSONDecoder().decode(OpenAiStreamChunk.self, from: jsonData) {
                                if let content = chunk.choices?.first?.delta?.content {
                                    continuation.yield(content)
                                }
                                if let reasoning = chunk.choices?.first?.delta?.reasoning_content {
                                    continuation.yield(reasoning)
                                }
                            }
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    // MARK: - Responses API Streaming

    private func streamResponsesApi(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters,
        customBaseUrl: String? = nil
    ) -> AsyncThrowingStream<String, Error> {
        let inputMessages = messages.filter { $0.role != "system" }.map {
            OpenAiResponsesInputMessage(role: $0.role, content: $0.content)
        }

        let request = OpenAiResponsesStreamRequest(
            model: model,
            input: inputMessages,
            instructions: params.systemPrompt.isEmpty ? nil : params.systemPrompt,
            stream: true
        )

        let base = customBaseUrl ?? service.baseUrl
        let normalizedBase = base.hasSuffix("/") ? base : "\(base)/"
        let url = "\(normalizedBase)v1/responses"

        return AsyncThrowingStream { continuation in
            Task {
                do {
                    let (bytes, _) = try await ApiClient.shared.streamRequest(url: url, body: request, service: service, apiKey: apiKey)
                    for try await line in bytes.lines {
                        if line.hasPrefix("data: ") {
                            let data = String(line.dropFirst(6))
                            if data == "[DONE]" { break }
                            if let jsonData = data.data(using: .utf8),
                               let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] {
                                // Look for delta text in response.output_text.delta events
                                if let delta = json["delta"] as? String {
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
        }
    }

    // MARK: - Claude Streaming

    private func streamChatClaude(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) -> AsyncThrowingStream<String, Error> {
        let claudeMessages = messages.filter { $0.role != "system" }.map {
            ClaudeMessage(role: $0.role == "assistant" ? "assistant" : "user", content: $0.content)
        }

        let request = ClaudeStreamRequest(
            model: model,
            messages: claudeMessages,
            stream: true,
            max_tokens: params.maxTokens ?? 4096,
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            system: params.systemPrompt.isEmpty ? nil : params.systemPrompt,
            search: params.searchEnabled ? true : nil
        )

        let base = service.baseUrl.hasSuffix("/") ? service.baseUrl : "\(service.baseUrl)/"
        let url = "\(base)v1/messages"

        return AsyncThrowingStream { continuation in
            Task {
                do {
                    let (bytes, _) = try await ApiClient.shared.streamRequest(url: url, body: request, service: service, apiKey: apiKey)
                    var currentEvent = ""
                    for try await line in bytes.lines {
                        if line.hasPrefix("event: ") {
                            currentEvent = String(line.dropFirst(7))
                            if currentEvent == "message_stop" { break }
                        } else if line.hasPrefix("data: "), currentEvent == "content_block_delta" {
                            let data = String(line.dropFirst(6))
                            if let jsonData = data.data(using: .utf8),
                               let event = try? JSONDecoder().decode(ClaudeStreamEvent.self, from: jsonData),
                               let text = event.delta?.text {
                                continuation.yield(text)
                            }
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    // MARK: - Gemini Streaming

    private func streamChatGemini(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) -> AsyncThrowingStream<String, Error> {
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

        let url = ApiClient.geminiGenerateUrl(baseUrl: service.baseUrl, model: model, apiKey: apiKey, stream: true)

        return AsyncThrowingStream { continuation in
            Task {
                do {
                    // Use a dummy service since Gemini auth is in URL
                    let dummyService = AppService(id: "gemini_temp", displayName: "", baseUrl: "", defaultModel: "", apiFormat: .google)
                    let (bytes, _) = try await ApiClient.shared.streamRequest(url: url, body: request, service: dummyService, apiKey: apiKey)
                    for try await line in bytes.lines {
                        if line.hasPrefix("data: ") {
                            let data = String(line.dropFirst(6))
                            if let jsonData = data.data(using: .utf8),
                               let chunk = try? JSONDecoder().decode(GeminiStreamChunk.self, from: jsonData),
                               let text = chunk.candidates?.first?.content?.parts.first?.text {
                                continuation.yield(text)
                            }
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    // MARK: - Message Conversion

    func convertToOpenAiMessages(_ messages: [ChatMessage], systemPrompt: String? = nil) -> [OpenAiMessage] {
        var result: [OpenAiMessage] = []
        if let sys = systemPrompt, !sys.isEmpty {
            result.append(OpenAiMessage(role: "system", content: sys))
        }
        result += messages.map { OpenAiMessage(role: $0.role, content: $0.content) }
        return result
    }
}
