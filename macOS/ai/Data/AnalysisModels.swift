import Foundation
import os

/// Model fetching + API connection testing.
extension AnalysisRepository {

    // MARK: - Test Model

    /// Tests if a model is accessible by sending a minimal prompt.
    func testModel(service: AppService, apiKey: String, model: String) async -> String? {
        do {
            let params = AgentParameters()
            switch service.apiFormat {
            case .openaiCompatible:
                let result = await analyzeWithOpenAiCompatible(service: service, apiKey: apiKey, prompt: Self.testPrompt, model: model, params: params)
                return result.error
            case .anthropic:
                let result = await analyzeWithClaude(service: service, apiKey: apiKey, prompt: Self.testPrompt, model: model, params: params)
                return result.error
            case .google:
                let result = await analyzeWithGemini(service: service, apiKey: apiKey, prompt: Self.testPrompt, model: model, params: params)
                return result.error
            }
        }
    }

    /// Tests with a custom prompt, returning (responseText, errorMessage).
    func testModelWithPrompt(service: AppService, apiKey: String, model: String, prompt: String) async -> (String?, String?) {
        let params = AgentParameters()
        let result: AnalysisResponse
        switch service.apiFormat {
        case .openaiCompatible:
            result = await analyzeWithOpenAiCompatible(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params)
        case .anthropic:
            result = await analyzeWithClaude(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params)
        case .google:
            result = await analyzeWithGemini(service: service, apiKey: apiKey, prompt: prompt, model: model, params: params)
        }
        return (result.analysis, result.error)
    }

    // MARK: - Fetch Models

    func fetchModels(service: AppService, apiKey: String, customUrl: String? = nil) async -> [String] {
        switch service.apiFormat {
        case .google:
            return await fetchGeminiModels(service: service, apiKey: apiKey)
        case .anthropic:
            return await fetchClaudeModels(service: service, apiKey: apiKey)
        case .openaiCompatible:
            return await fetchModelsOpenAiCompatible(service: service, apiKey: apiKey, customUrl: customUrl)
        }
    }

    // MARK: - Gemini Models

    private func fetchGeminiModels(service: AppService, apiKey: String) async -> [String] {
        let base = service.baseUrl.hasSuffix("/") ? service.baseUrl : "\(service.baseUrl)/"
        let url = "\(base)v1beta/models?key=\(apiKey)"

        do {
            let dummyService = AppService(id: "gemini_temp", displayName: "", baseUrl: "", defaultModel: "", apiFormat: .google)
            let (response, _): (GeminiModelsResponse, HTTPURLResponse) = try await ApiClient.shared.get(
                url: url, service: dummyService, apiKey: apiKey
            )
            return response.models?
                .filter { $0.supportedGenerationMethods?.contains("generateContent") == true }
                .compactMap { $0.name?.replacingOccurrences(of: "models/", with: "") }
                ?? []
        } catch {
            return []
        }
    }

    // MARK: - Claude Models

    private func fetchClaudeModels(service: AppService, apiKey: String) async -> [String] {
        let base = service.baseUrl.hasSuffix("/") ? service.baseUrl : "\(service.baseUrl)/"
        let url = "\(base)v1/models"

        do {
            let (response, _): (ClaudeModelsResponse, HTTPURLResponse) = try await ApiClient.shared.get(
                url: url, service: service, apiKey: apiKey
            )
            return response.data?
                .compactMap { $0.id }
                .filter { $0.hasPrefix("claude") }
                ?? []
        } catch {
            return []
        }
    }

    // MARK: - OpenAI Compatible Models

    private func fetchModelsOpenAiCompatible(service: AppService, apiKey: String, customUrl: String? = nil) async -> [String] {
        // Return hardcoded models if no API endpoint
        if service.modelsPath == nil {
            return service.hardcodedModels ?? []
        }

        let url = ApiClient.modelsUrl(for: service, customUrl: customUrl)
        guard !url.isEmpty else { return service.hardcodedModels ?? [] }

        do {
            if service.modelListFormat == "array" {
                // Some providers return raw array
                let (models, _): ([OpenAiModel], HTTPURLResponse) = try await ApiClient.shared.get(
                    url: url, service: service, apiKey: apiKey
                )
                return filterModels(models.compactMap(\.id), service: service)
            } else {
                let (response, _): (OpenAiModelsResponse, HTTPURLResponse) = try await ApiClient.shared.get(
                    url: url, service: service, apiKey: apiKey
                )
                return filterModels(response.data?.compactMap(\.id) ?? [], service: service)
            }
        } catch {
            return service.hardcodedModels ?? []
        }
    }

    private func filterModels(_ models: [String], service: AppService) -> [String] {
        guard let regex = service.modelFilterRegex else { return models.sorted() }
        let range = NSRange(location: 0, length: 0)
        return models.filter { model in
            let nsRange = NSRange(model.startIndex..<model.endIndex, in: model)
            return regex.firstMatch(in: model, range: nsRange) != nil
        }.sorted()
    }
}
