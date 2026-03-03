import Foundation

// MARK: - Analysis Providers

extension AnalysisRepository {

    // MARK: - Dispatch by Format

    func analyze(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters? = nil,
        baseUrl: String? = nil
    ) async -> AnalysisResponse {
        switch service.apiFormat {
        case .anthropic:
            return await analyzeWithClaude(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params)
        case .google:
            return await analyzeWithGemini(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params)
        case .openaiCompatible:
            return await analyzeWithOpenAiCompatible(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params, baseUrl: baseUrl ?? service.baseUrl)
        }
    }

    // MARK: - OpenAI Compatible

    func analyzeWithOpenAiCompatible(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters? = nil,
        baseUrl: String = ""
    ) async -> AnalysisResponse {
        let effectiveBaseUrl = baseUrl.isEmpty ? service.baseUrl : baseUrl

        // Check Responses API
        if service.usesResponsesApi(model: model) {
            return await analyzeWithResponsesApi(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params)
        }

        // Build messages
        var messages: [OpenAiMessage] = []
        if let sp = params?.systemPrompt, !sp.isEmpty {
            messages.append(OpenAiMessage(role: "system", content: sp))
        }
        messages.append(OpenAiMessage(role: "user", content: prompt))

        // Build seed fields
        let seedValue = params?.seed
        let isMistralSeed = service.seedFieldName == "random_seed"

        let request = OpenAiRequest(
            model: model,
            messages: messages,
            max_tokens: params?.maxTokens,
            temperature: params?.temperature,
            top_p: params?.topP,
            top_k: params?.topK,
            frequency_penalty: params?.frequencyPenalty,
            presence_penalty: params?.presencePenalty,
            stop: params?.stopSequences?.isEmpty == false ? params?.stopSequences : nil,
            seed: isMistralSeed ? nil : seedValue,
            random_seed: isMistralSeed ? seedValue : nil,
            response_format: params?.responseFormatJson == true ? OpenAiResponseFormat(type: "json_object") : nil,
            return_citations: service.supportsCitations ? params?.returnCitations : nil,
            search_recency_filter: service.supportsSearchRecency ? params?.searchRecency : nil,
            search: params?.searchEnabled == true ? true : nil
        )

        let url = ApiClient.chatUrl(for: service, customBaseUrl: effectiveBaseUrl)
        let headers = ApiClient.openAiHeaders(apiKey: apiKey)

        do {
            let body = try ApiClient.encode(request)
            let (response, statusCode, respHeaders): (OpenAiResponse, Int, [String: String]) =
                try await ApiClient.shared.sendRequest(url: url, headers: headers, body: body, responseType: OpenAiResponse.self)

            // Extract content with multi-strategy
            let content = response.choices?.first?.message.content
                ?? response.choices?.first?.message.reasoning_content
                ?? response.choices?.compactMap({ $0.message.content }).first
                ?? response.choices?.compactMap({ $0.message.reasoning_content }).first

            guard let content = content else {
                return AnalysisResponse(service: service, error: "No response content (choices: \(response.choices?.count ?? 0))",
                                        httpStatusCode: statusCode)
            }

            let tokenUsage = response.usage.map {
                TokenUsage(
                    inputTokens: $0.prompt_tokens ?? $0.input_tokens ?? 0,
                    outputTokens: $0.completion_tokens ?? $0.output_tokens ?? 0,
                    apiCost: PricingCache.extractApiCost(openAiUsage: $0, service: service.extractApiCost ? service : nil)
                )
            }

            return AnalysisResponse(
                service: service,
                analysis: content,
                tokenUsage: tokenUsage,
                citations: response.citations,
                searchResults: response.search_results,
                relatedQuestions: response.related_questions,
                rawUsageJson: response.usage != nil ? Self.formatUsageJson(response.usage) : nil,
                httpHeaders: Self.formatHeaders(respHeaders),
                httpStatusCode: statusCode
            )
        } catch let error as ApiError {
            return AnalysisResponse(service: service, error: error.localizedDescription)
        } catch {
            return AnalysisResponse(service: service, error: "Error: \(error.localizedDescription)")
        }
    }

    // MARK: - OpenAI Responses API

    private func analyzeWithResponsesApi(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters? = nil
    ) async -> AnalysisResponse {
        let request = OpenAiResponsesRequest(
            model: model,
            input: .text(prompt),
            instructions: params?.systemPrompt?.isEmpty == false ? params?.systemPrompt : nil
        )

        let normalizedBase = ApiClient.normalizeBaseUrl(service.baseUrl)
        let url = "\(normalizedBase)v1/responses"
        let headers = ApiClient.openAiHeaders(apiKey: apiKey)

        do {
            let body = try ApiClient.encode(request)
            let (data, statusCode, respHeaders) = try await ApiClient.shared.sendRawRequest(url: url, headers: headers, body: body)

            guard (200...299).contains(statusCode) else {
                let errorBody = String(data: data, encoding: .utf8) ?? "Unknown error"
                return AnalysisResponse(service: service, error: "API error: \(statusCode) - \(errorBody)", httpStatusCode: statusCode)
            }

            let response = try ApiClient.decode(OpenAiResponsesApiResponse.self, from: data)

            // Multi-strategy content extraction
            let firstOutput = response.output?.first
            let firstContent = firstOutput?.content
            let outputText: String? = firstContent?.first(where: { $0.type == "output_text" })?.text
            let textType: String? = firstContent?.first(where: { $0.type == "text" })?.text
            let anyText: String? = firstContent?.compactMap({ $0.text }).first
            let messageOutput = response.output?.first(where: { $0.type == "message" })
            let messageText: String? = messageOutput?.content?.compactMap({ $0.text }).first
            let flatText: String? = response.output?.flatMap({ $0.content ?? [] }).compactMap({ $0.text }).first
            let content: String? = outputText ?? textType ?? anyText ?? messageText ?? flatText

            guard let content = content else {
                return AnalysisResponse(service: service, error: "No response content from Responses API", httpStatusCode: statusCode)
            }

            let tokenUsage = response.usage.map {
                TokenUsage(
                    inputTokens: $0.prompt_tokens ?? $0.input_tokens ?? 0,
                    outputTokens: $0.completion_tokens ?? $0.output_tokens ?? 0,
                    apiCost: PricingCache.extractApiCost(openAiUsage: $0, service: service.extractApiCost ? service : nil)
                )
            }

            return AnalysisResponse(
                service: service,
                analysis: content,
                tokenUsage: tokenUsage,
                rawUsageJson: response.usage != nil ? Self.formatUsageJson(response.usage) : nil,
                httpHeaders: Self.formatHeaders(respHeaders),
                httpStatusCode: statusCode
            )
        } catch {
            return AnalysisResponse(service: service, error: "Error: \(error.localizedDescription)")
        }
    }

    // MARK: - Anthropic / Claude

    func analyzeWithClaude(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters? = nil
    ) async -> AnalysisResponse {
        let request = ClaudeRequest(
            model: model,
            max_tokens: params?.maxTokens ?? 4096,
            messages: [ClaudeMessage(role: "user", content: prompt)],
            temperature: params?.temperature,
            top_p: params?.topP,
            top_k: params?.topK,
            system: params?.systemPrompt?.isEmpty == false ? params?.systemPrompt : nil,
            stop_sequences: params?.stopSequences?.isEmpty == false ? params?.stopSequences : nil,
            frequency_penalty: params?.frequencyPenalty,
            presence_penalty: params?.presencePenalty,
            seed: params?.seed,
            search: params?.searchEnabled == true ? true : nil
        )

        let normalizedBase = ApiClient.normalizeBaseUrl(service.baseUrl)
        let url = "\(normalizedBase)v1/messages"
        let headers = ApiClient.anthropicHeaders(apiKey: apiKey)

        do {
            let body = try ApiClient.encode(request)
            let (response, statusCode, respHeaders): (ClaudeResponse, Int, [String: String]) =
                try await ApiClient.shared.sendRequest(url: url, headers: headers, body: body, responseType: ClaudeResponse.self)

            if let error = response.error {
                return AnalysisResponse(service: service, error: "Claude error: \(error.message ?? error.type ?? "Unknown")",
                                        httpStatusCode: statusCode)
            }

            let content = response.content?.first(where: { $0.type == "text" })?.text
                ?? response.content?.compactMap({ $0.text }).first

            guard let content = content else {
                return AnalysisResponse(service: service, error: "No response content from Claude", httpStatusCode: statusCode)
            }

            let tokenUsage = response.usage.map {
                TokenUsage(
                    inputTokens: $0.input_tokens ?? 0,
                    outputTokens: $0.output_tokens ?? 0,
                    apiCost: PricingCache.extractApiCost(claudeUsage: $0)
                )
            }

            return AnalysisResponse(
                service: service,
                analysis: content,
                tokenUsage: tokenUsage,
                rawUsageJson: response.usage != nil ? Self.formatUsageJson(response.usage) : nil,
                httpHeaders: Self.formatHeaders(respHeaders),
                httpStatusCode: statusCode
            )
        } catch let error as ApiError {
            return AnalysisResponse(service: service, error: error.localizedDescription)
        } catch {
            return AnalysisResponse(service: service, error: "Error: \(error.localizedDescription)")
        }
    }

    // MARK: - Google Gemini

    func analyzeWithGemini(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters? = nil
    ) async -> AnalysisResponse {
        let request = GeminiRequest(
            contents: [GeminiContent(parts: [GeminiPart(text: prompt)])],
            generationConfig: GeminiGenerationConfig(
                temperature: params?.temperature,
                topP: params?.topP,
                topK: params?.topK,
                maxOutputTokens: params?.maxTokens,
                stopSequences: params?.stopSequences?.isEmpty == false ? params?.stopSequences : nil,
                frequencyPenalty: params?.frequencyPenalty,
                presencePenalty: params?.presencePenalty,
                seed: params?.seed,
                search: params?.searchEnabled == true ? true : nil
            ),
            systemInstruction: params?.systemPrompt?.isEmpty == false ? GeminiContent(parts: [GeminiPart(text: params!.systemPrompt!)]) : nil
        )

        let url = ApiClient.geminiGenerateUrl(baseUrl: service.baseUrl, model: model, apiKey: apiKey)
        let headers = ApiClient.geminiHeaders()

        do {
            let body = try ApiClient.encode(request)
            let (response, statusCode, respHeaders): (GeminiResponse, Int, [String: String]) =
                try await ApiClient.shared.sendRequest(url: url, headers: headers, body: body, responseType: GeminiResponse.self)

            if let error = response.error {
                return AnalysisResponse(service: service, error: "Gemini error: \(error.message ?? "Unknown") (\(error.status ?? ""))",
                                        httpStatusCode: statusCode)
            }

            let content = response.candidates?.first?.content?.parts.first?.text
                ?? response.candidates?.first?.content?.parts.compactMap({ $0.text }).first
                ?? response.candidates?.flatMap({ $0.content?.parts ?? [] }).compactMap({ $0.text }).first

            guard let content = content else {
                return AnalysisResponse(service: service, error: "No response content from Gemini", httpStatusCode: statusCode)
            }

            let tokenUsage = response.usageMetadata.map {
                TokenUsage(
                    inputTokens: $0.promptTokenCount ?? 0,
                    outputTokens: $0.candidatesTokenCount ?? 0,
                    apiCost: PricingCache.extractApiCost(geminiUsage: $0)
                )
            }

            return AnalysisResponse(
                service: service,
                analysis: content,
                tokenUsage: tokenUsage,
                rawUsageJson: response.usageMetadata != nil ? Self.formatUsageJson(response.usageMetadata) : nil,
                httpHeaders: Self.formatHeaders(respHeaders),
                httpStatusCode: statusCode
            )
        } catch let error as ApiError {
            return AnalysisResponse(service: service, error: error.localizedDescription)
        } catch {
            return AnalysisResponse(service: service, error: "Error: \(error.localizedDescription)")
        }
    }
}
