import Foundation

// MARK: - Model Fetching + API Testing

extension AnalysisRepository {

    // MARK: - Dispatch

    func fetchModels(service: AppService, apiKey: String) async throws -> [String] {
        switch service.apiFormat {
        case .anthropic:
            return try await fetchClaudeModels(service: service, apiKey: apiKey)
        case .google:
            return try await fetchGeminiModels(service: service, apiKey: apiKey)
        case .openaiCompatible:
            return try await fetchModelsOpenAiCompatible(service: service, apiKey: apiKey)
        }
    }

    // MARK: - OpenAI Compatible

    private func fetchModelsOpenAiCompatible(service: AppService, apiKey: String) async throws -> [String] {
        // Handle hardcoded models (no API endpoint)
        if let hardcoded = service.hardcodedModels, service.modelsPath == nil {
            return hardcoded
        }

        let url = ApiClient.modelsUrl(for: service)
        guard !url.isEmpty else {
            return service.hardcodedModels ?? []
        }

        let headers = ApiClient.openAiHeaders(apiKey: apiKey)

        var modelIds: [String]

        if service.modelListFormat == "array" {
            // Some providers return a plain array instead of {data: [...]}
            let (models, _, _): ([OpenAiModel], Int, [String: String]) =
                try await ApiClient.shared.sendRequest(url: url, method: "GET", headers: headers, responseType: [OpenAiModel].self)
            modelIds = models.compactMap(\.id)
        } else {
            let (response, _, _): (OpenAiModelsResponse, Int, [String: String]) =
                try await ApiClient.shared.sendRequest(url: url, method: "GET", headers: headers, responseType: OpenAiModelsResponse.self)
            modelIds = response.data?.compactMap(\.id) ?? []
        }

        // Apply provider-specific filter
        let filtered = service.modelFilter != nil ? modelIds.filter { service.matchesFilter($0) } : modelIds

        return filtered.sorted()
    }

    // MARK: - Claude Models

    private func fetchClaudeModels(service: AppService, apiKey: String) async throws -> [String] {
        let normalizedBase = ApiClient.normalizeBaseUrl(service.baseUrl)
        let url = "\(normalizedBase)v1/models"
        let headers = ApiClient.anthropicHeaders(apiKey: apiKey)

        let (response, _, _): (ClaudeModelsResponse, Int, [String: String]) =
            try await ApiClient.shared.sendRequest(url: url, method: "GET", headers: headers, responseType: ClaudeModelsResponse.self)

        let models = (response.data ?? []).compactMap(\.id).filter { $0.hasPrefix("claude") }
        return models.sorted()
    }

    // MARK: - Gemini Models

    private func fetchGeminiModels(service: AppService, apiKey: String) async throws -> [String] {
        let url = ApiClient.geminiModelsUrl(baseUrl: service.baseUrl, apiKey: apiKey)
        let headers = ApiClient.geminiHeaders()

        let (response, _, _): (GeminiModelsResponse, Int, [String: String]) =
            try await ApiClient.shared.sendRequest(url: url, method: "GET", headers: headers, responseType: GeminiModelsResponse.self)

        let models = response.models?
            .filter { $0.supportedGenerationMethods?.contains("generateContent") ?? false }
            .compactMap { $0.name?.replacingOccurrences(of: "models/", with: "") }
            ?? []

        return models.sorted()
    }

    // MARK: - Test Model

    func testModel(service: AppService, apiKey: String, model: String) async -> String? {
        let response = await analyze(service: service, apiKey: apiKey, prompt: Self.testPrompt, model: model)
        return response.isSuccess ? nil : (response.error ?? "Unknown error")
    }

    func testModelWithPrompt(service: AppService, apiKey: String, model: String, prompt: String) async -> (String?, String?) {
        let response = await analyze(service: service, apiKey: apiKey, prompt: prompt, model: model)
        if response.isSuccess {
            return (response.analysis, nil)
        } else {
            return (nil, response.error ?? "Unknown error")
        }
    }

    // MARK: - Test API with Raw JSON

    func testApiConnectionWithJson(
        service: AppService,
        apiKey: String,
        jsonBody: String,
        customUrl: String? = nil
    ) async -> (String?, String?, Int?) {
        // Force stream: false for testing
        var modifiedJson = jsonBody
        if let data = jsonBody.data(using: .utf8),
           var json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            json["stream"] = false
            if let modified = try? JSONSerialization.data(withJSONObject: json),
               let str = String(data: modified, encoding: .utf8) {
                modifiedJson = str
            }
        }

        let url: String
        if let custom = customUrl, !custom.isEmpty {
            url = custom
        } else {
            url = ApiClient.chatUrl(for: service)
        }

        let headers = ApiClient.headersForService(service, apiKey: apiKey)

        // For Gemini, append key to URL
        var finalUrl = url
        if service.apiFormat == .google, !finalUrl.contains("key=") {
            finalUrl += (finalUrl.contains("?") ? "&" : "?") + "key=\(apiKey)"
        }

        guard let body = modifiedJson.data(using: .utf8) else {
            return (nil, "Invalid JSON", nil)
        }

        do {
            let (data, statusCode, _) = try await ApiClient.shared.sendRawRequest(url: finalUrl, headers: headers, body: body)
            let responseBody = String(data: data, encoding: .utf8) ?? "No response body"

            if (200...299).contains(statusCode) {
                return (responseBody, nil, statusCode)
            } else {
                return (nil, "HTTP \(statusCode): \(responseBody)", statusCode)
            }
        } catch {
            return (nil, error.localizedDescription, nil)
        }
    }
}
