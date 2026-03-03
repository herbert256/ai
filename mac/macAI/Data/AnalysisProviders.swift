import Foundation
import os

/// Format-specific API implementations for analysis (reports).
extension AnalysisRepository {

    // MARK: - OpenAI Compatible (28 providers)

    func analyzeWithOpenAiCompatible(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters,
        customBaseUrl: String? = nil
    ) async -> AnalysisResponse {
        // Check if model uses Responses API
        if usesResponsesApi(service, model: model) {
            return await analyzeWithResponsesApi(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params, customBaseUrl: customBaseUrl)
        }

        let messages = buildOpenAiMessages(prompt: prompt, systemPrompt: params.systemPrompt)

        let request = OpenAiRequest(
            model: model,
            messages: messages,
            max_tokens: params.maxTokens,
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            frequency_penalty: params.frequencyPenalty,
            presence_penalty: params.presencePenalty,
            stop: params.stopSequences,
            seed: service.seedFieldName == "seed" ? params.seed : nil,
            random_seed: service.seedFieldName == "random_seed" ? params.seed : nil,
            response_format: params.responseFormatJson ? OpenAiResponseFormat(type: "json_object") : nil,
            return_citations: service.supportsCitations ? params.returnCitations : nil,
            search_recency_filter: service.supportsSearchRecency ? params.searchRecency : nil,
            search: params.searchEnabled ? true : nil
        )

        let url = ApiClient.chatUrl(for: service, customBaseUrl: customBaseUrl)

        do {
            let (response, httpResponse): (OpenAiResponse, HTTPURLResponse) = try await ApiClient.shared.request(
                url: url, body: request, service: service, apiKey: apiKey
            )

            if let error = response.error {
                return AnalysisResponse(service: service, error: error.message ?? "Unknown error",
                                       httpStatusCode: httpResponse.statusCode)
            }

            let content = response.choices?.first?.message.content
                ?? response.choices?.first?.message.reasoning_content

            let usage = response.usage
            let tokenUsage = TokenUsage(
                inputTokens: usage?.effectiveInputTokens ?? 0,
                outputTokens: usage?.effectiveOutputTokens ?? 0,
                apiCost: Self.extractApiCost(usage: usage, provider: service)
            )

            return AnalysisResponse(
                service: service,
                analysis: content,
                error: content == nil ? "No content in response" : nil,
                tokenUsage: tokenUsage,
                citations: response.citations,
                searchResults: response.search_results,
                relatedQuestions: response.related_questions,
                rawUsageJson: formatUsageJson(usage),
                httpStatusCode: httpResponse.statusCode
            )
        } catch {
            return AnalysisResponse(service: service, error: error.localizedDescription)
        }
    }

    // MARK: - OpenAI Responses API

    private func analyzeWithResponsesApi(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters,
        customBaseUrl: String? = nil
    ) async -> AnalysisResponse {
        let request = OpenAiResponsesRequest(model: model, input: prompt, instructions: params.systemPrompt)

        let base = customBaseUrl ?? service.baseUrl
        let normalizedBase = base.hasSuffix("/") ? base : "\(base)/"
        let url = "\(normalizedBase)v1/responses"

        do {
            let (response, httpResponse): (OpenAiResponsesApiResponse, HTTPURLResponse) = try await ApiClient.shared.request(
                url: url, body: request, service: service, apiKey: apiKey
            )

            if let error = response.error {
                return AnalysisResponse(service: service, error: error.message ?? "Unknown error",
                                       httpStatusCode: httpResponse.statusCode)
            }

            // Extract content from output blocks
            let content = response.output?
                .compactMap { msg in msg.content?.first(where: { $0.type == "output_text" })?.text ?? msg.content?.first?.text }
                .first

            let usage = response.usage
            let tokenUsage = TokenUsage(
                inputTokens: usage?.effectiveInputTokens ?? 0,
                outputTokens: usage?.effectiveOutputTokens ?? 0,
                apiCost: Self.extractApiCost(usage: usage, provider: service)
            )

            return AnalysisResponse(
                service: service,
                analysis: content,
                error: content == nil ? "No content in response" : nil,
                tokenUsage: tokenUsage,
                rawUsageJson: formatUsageJson(usage),
                httpStatusCode: httpResponse.statusCode
            )
        } catch {
            return AnalysisResponse(service: service, error: error.localizedDescription)
        }
    }

    // MARK: - Anthropic Claude

    func analyzeWithClaude(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters
    ) async -> AnalysisResponse {
        let request = ClaudeRequest(
            model: model,
            max_tokens: params.maxTokens ?? 4096,
            messages: [ClaudeMessage(role: "user", content: prompt)],
            temperature: params.temperature,
            top_p: params.topP,
            top_k: params.topK,
            system: params.systemPrompt,
            stop_sequences: params.stopSequences,
            search: params.searchEnabled ? true : nil
        )

        let base = service.baseUrl.hasSuffix("/") ? service.baseUrl : "\(service.baseUrl)/"
        let url = "\(base)v1/messages"

        do {
            let (response, httpResponse): (ClaudeResponse, HTTPURLResponse) = try await ApiClient.shared.request(
                url: url, body: request, service: service, apiKey: apiKey
            )

            if let error = response.error {
                return AnalysisResponse(service: service, error: error.message ?? "Unknown error",
                                       httpStatusCode: httpResponse.statusCode)
            }

            let content = response.content?.first(where: { $0.type == "text" })?.text

            let tokenUsage = TokenUsage(
                inputTokens: response.usage?.input_tokens ?? 0,
                outputTokens: response.usage?.output_tokens ?? 0,
                apiCost: Self.extractApiCost(usage: response.usage)
            )

            return AnalysisResponse(
                service: service,
                analysis: content,
                error: content == nil ? "No content in response" : nil,
                tokenUsage: tokenUsage,
                rawUsageJson: formatUsageJson(response.usage),
                httpStatusCode: httpResponse.statusCode
            )
        } catch {
            return AnalysisResponse(service: service, error: error.localizedDescription)
        }
    }

    // MARK: - Google Gemini

    func analyzeWithGemini(
        service: AppService,
        apiKey: String,
        prompt: String,
        model: String,
        params: AgentParameters
    ) async -> AnalysisResponse {
        let config = GeminiGenerationConfig(
            temperature: params.temperature,
            topP: params.topP,
            topK: params.topK,
            maxOutputTokens: params.maxTokens,
            stopSequences: params.stopSequences
        )

        let systemInstruction = params.systemPrompt.map {
            GeminiContent(parts: [GeminiPart(text: $0)])
        }

        let request = GeminiRequest(
            contents: [GeminiContent(parts: [GeminiPart(text: prompt)], role: "user")],
            generationConfig: config,
            systemInstruction: systemInstruction
        )

        let url = ApiClient.geminiGenerateUrl(baseUrl: service.baseUrl, model: model, apiKey: apiKey)

        do {
            // Gemini uses a dummy service for auth (query param handled in URL)
            let dummyService = AppService(id: "gemini_temp", displayName: "", baseUrl: "", defaultModel: "", apiFormat: .google)
            let (response, httpResponse): (GeminiResponse, HTTPURLResponse) = try await ApiClient.shared.request(
                url: url, body: request, service: dummyService, apiKey: apiKey
            )

            if let error = response.error {
                return AnalysisResponse(service: service, error: error.message ?? "Unknown error",
                                       httpStatusCode: httpResponse.statusCode)
            }

            let content = response.candidates?.first?.content?.parts.first?.text

            let usage = response.usageMetadata
            let tokenUsage = TokenUsage(
                inputTokens: usage?.promptTokenCount ?? 0,
                outputTokens: usage?.candidatesTokenCount ?? 0,
                apiCost: Self.extractApiCost(usage: usage)
            )

            return AnalysisResponse(
                service: service,
                analysis: content,
                error: content == nil ? "No content in response" : nil,
                tokenUsage: tokenUsage,
                rawUsageJson: formatUsageJson(usage),
                httpStatusCode: httpResponse.statusCode
            )
        } catch {
            return AnalysisResponse(service: service, error: error.localizedDescription)
        }
    }

    // MARK: - Helpers

    private func buildOpenAiMessages(prompt: String, systemPrompt: String?) -> [OpenAiMessage] {
        var messages: [OpenAiMessage] = []
        if let sys = systemPrompt, !sys.isEmpty {
            messages.append(OpenAiMessage(role: "system", content: sys))
        }
        messages.append(OpenAiMessage(role: "user", content: prompt))
        return messages
    }

    // MARK: - Agent Analysis Entry Point

    func analyzeWithAgent(
        agent: Agent,
        content: String,
        prompt: String,
        settings: Settings,
        overrideParams: AgentParameters? = nil
    ) async -> AnalysisResponse {
        guard let provider = agent.provider else {
            return AnalysisResponse(service: AppService.entries.first!, error: "Provider not found for agent \(agent.name)")
        }

        let effectiveApiKey = settings.getEffectiveApiKeyForAgent(agent)
        guard !effectiveApiKey.isEmpty else {
            return AnalysisResponse(service: provider, error: "API key not configured for agent \(agent.name)", agentName: agent.name)
        }

        let finalPrompt = buildPrompt(prompt, content: content, agent: agent)
        let agentResolvedParams = settings.resolveAgentParameters(agent)
        let params = validateParams(mergeParameters(agentResolvedParams, overrideParams))
        let customBaseUrl = settings.getEffectiveEndpointUrlForAgent(agent)
        let effectiveModel = settings.getEffectiveModelForAgent(agent)

        let makeApiCall: () async throws -> AnalysisResponse = {
            var result: AnalysisResponse
            switch provider.apiFormat {
            case .anthropic:
                result = await self.analyzeWithClaude(service: provider, apiKey: effectiveApiKey, prompt: finalPrompt, model: effectiveModel, params: params)
            case .google:
                result = await self.analyzeWithGemini(service: provider, apiKey: effectiveApiKey, prompt: finalPrompt, model: effectiveModel, params: params)
            case .openaiCompatible:
                result = await self.analyzeWithOpenAiCompatible(service: provider, apiKey: effectiveApiKey, prompt: finalPrompt, model: effectiveModel, params: params, customBaseUrl: customBaseUrl)
            }
            result.agentName = agent.name
            result.promptUsed = finalPrompt
            return result
        }

        return await withRetry(
            label: "Agent \(agent.name)",
            makeCall: makeApiCall,
            isSuccess: { $0.isSuccess },
            errorResult: { error in
                AnalysisResponse(service: provider, error: "Network error after retry: \(error.localizedDescription)", agentName: agent.name)
            }
        )
    }
}
