import Foundation

// MARK: - Analysis Repository

class AnalysisRepository: @unchecked Sendable {
    static let shared = AnalysisRepository()

    static let retryDelayMs: UInt64 = 500_000_000 // 500ms in nanoseconds
    static let testPrompt = "Reply with exactly: OK"
    static let xaiCostTicksDivisor = 10_000_000_000.0

    // MARK: - Retry Logic

    func withRetry<T>(
        maxAttempts: Int = 2,
        operation: () async throws -> T
    ) async throws -> T {
        var lastError: Error?
        for attempt in 0..<maxAttempts {
            do {
                return try await operation()
            } catch {
                lastError = error
                if attempt < maxAttempts - 1 {
                    try? await Task.sleep(nanoseconds: Self.retryDelayMs)
                }
            }
        }
        throw lastError ?? ApiError.streamError("Unknown retry error")
    }

    // MARK: - Parameter Merging

    func mergeParameters(base: AgentParameters?, override: AgentParameters?) -> AgentParameters {
        guard let base = base else { return override ?? AgentParameters() }
        guard let override = override else { return base }

        return AgentParameters(
            temperature: override.temperature ?? base.temperature,
            maxTokens: override.maxTokens ?? base.maxTokens,
            topP: override.topP ?? base.topP,
            topK: override.topK ?? base.topK,
            frequencyPenalty: override.frequencyPenalty ?? base.frequencyPenalty,
            presencePenalty: override.presencePenalty ?? base.presencePenalty,
            systemPrompt: override.systemPrompt?.isEmpty == false ? override.systemPrompt : base.systemPrompt,
            stopSequences: override.stopSequences ?? base.stopSequences,
            seed: override.seed ?? base.seed,
            responseFormatJson: override.responseFormatJson || base.responseFormatJson,
            searchEnabled: override.searchEnabled || base.searchEnabled,
            returnCitations: override.returnCitations,
            searchRecency: override.searchRecency ?? base.searchRecency
        )
    }

    // MARK: - Prompt Building

    func buildPrompt(template: String, content: String? = nil, agent: String? = nil,
                     provider: String? = nil, model: String? = nil, swarm: String? = nil) -> String {
        var prompt = template
        if let content = content { prompt = prompt.replacingOccurrences(of: "@FEN@", with: content) }
        prompt = prompt.replacingOccurrences(of: "@DATE@", with: formatCurrentDate())
        if let model = model { prompt = prompt.replacingOccurrences(of: "@MODEL@", with: model) }
        if let provider = provider { prompt = prompt.replacingOccurrences(of: "@PROVIDER@", with: provider) }
        if let agent = agent { prompt = prompt.replacingOccurrences(of: "@AGENT@", with: agent) }
        if let swarm = swarm { prompt = prompt.replacingOccurrences(of: "@SWARM@", with: swarm) }
        prompt = prompt.replacingOccurrences(of: "@NOW@", with: ISO8601DateFormatter().string(from: Date()))
        return prompt
    }

    private func formatCurrentDate() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "EEEE, MMMM d"
        let base = formatter.string(from: Date())
        let day = Calendar.current.component(.day, from: Date())
        let suffix: String
        switch day {
        case 1, 21, 31: suffix = "st"
        case 2, 22: suffix = "nd"
        case 3, 23: suffix = "rd"
        default: suffix = "th"
        }
        return "\(base)\(suffix)"
    }

    // MARK: - Token Estimation

    static func estimateTokens(_ text: String) -> Int {
        max(1, text.count / 4)
    }

    // MARK: - Usage JSON Formatting

    static func formatUsageJson(_ usage: Any?) -> String? {
        guard let usage = usage else { return nil }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        if let codable = usage as? Encodable,
           let data = try? encoder.encode(AnyEncodable(codable)),
           let json = String(data: data, encoding: .utf8) {
            return json
        }
        return nil
    }

    static func formatHeaders(_ headers: [String: String]) -> String {
        headers.map { "\($0.key): \($0.value)" }.sorted().joined(separator: "\n")
    }
}

// MARK: - AnyEncodable Helper

private struct AnyEncodable: Encodable {
    private let _encode: (Encoder) throws -> Void

    init(_ wrapped: Encodable) {
        self._encode = { encoder in
            try wrapped.encode(to: encoder)
        }
    }

    func encode(to encoder: Encoder) throws {
        try _encode(encoder)
    }
}
