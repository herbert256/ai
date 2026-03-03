import Foundation
import os

/// Repository for making AI analysis requests to various AI services.
/// Handles retry logic, parameter merging, prompt templating, and cost extraction.
class AnalysisRepository {
    static let shared = AnalysisRepository()

    private static let logger = Logger(subsystem: "com.ai.macAI", category: "AnalysisRepository")

    static let retryDelayMs: UInt64 = 500_000_000  // 500ms in nanoseconds
    static let testPrompt = "Reply with exactly: OK"
    static let xaiCostTicksDivisor = 10_000_000_000.0

    // MARK: - Cost Extraction

    static func extractApiCost(usage: OpenAiUsage?, provider: AppService? = nil) -> Double? {
        guard let usage else { return nil }

        if provider?.extractApiCost == true, let cost = usage.cost?.value {
            return cost
        }

        if let divisor = provider?.costTicksDivisor, let ticks = usage.cost_in_usd_ticks {
            return Double(ticks) / divisor
        }

        if provider?.costTicksDivisor == nil, let ticks = usage.cost_in_usd_ticks {
            return Double(ticks) / xaiCostTicksDivisor
        }

        return nil
    }

    static func extractApiCost(usage: ClaudeUsage?) -> Double? {
        guard let usage else { return nil }
        if let cost = usage.cost { return cost }
        if let ticks = usage.cost_in_usd_ticks { return Double(ticks) / 10_000_000_000.0 }
        if let cost = usage.cost_usd?.total_cost { return cost }
        return nil
    }

    static func extractApiCost(usage: GeminiUsageMetadata?) -> Double? {
        guard let usage else { return nil }
        if let cost = usage.cost { return cost }
        if let ticks = usage.cost_in_usd_ticks { return Double(ticks) / 10_000_000_000.0 }
        if let cost = usage.cost_usd?.total_cost { return cost }
        return nil
    }

    // MARK: - Prompt Building

    /// Formats the current date as "Saturday, January 24th".
    func formatCurrentDate() -> String {
        let today = Date()
        let calendar = Calendar.current
        let dayOfWeek = today.formatted(.dateTime.weekday(.wide))
        let month = today.formatted(.dateTime.month(.wide))
        let day = calendar.component(.day, from: today)

        let suffix: String
        switch day {
        case 11, 12, 13: suffix = "th"
        default:
            switch day % 10 {
            case 1: suffix = "st"
            case 2: suffix = "nd"
            case 3: suffix = "rd"
            default: suffix = "th"
            }
        }

        return "\(dayOfWeek), \(month) \(day)\(suffix)"
    }

    /// Builds the final prompt by replacing placeholders.
    func buildPrompt(_ template: String, content: String, agent: Agent? = nil) -> String {
        var result = template
            .replacingOccurrences(of: "@FEN@", with: content)
            .replacingOccurrences(of: "@DATE@", with: formatCurrentDate())
        if let agent {
            result = result
                .replacingOccurrences(of: "@MODEL@", with: agent.model)
                .replacingOccurrences(of: "@PROVIDER@", with: agent.provider?.displayName ?? "")
                .replacingOccurrences(of: "@AGENT@", with: agent.name)
        }
        return result
    }

    // MARK: - Parameter Merging

    /// Merge agent parameters with optional override parameters.
    func mergeParameters(_ agentParams: AgentParameters, _ overrideParams: AgentParameters?) -> AgentParameters {
        guard let over = overrideParams else { return agentParams }
        return AgentParameters(
            temperature: over.temperature ?? agentParams.temperature,
            maxTokens: over.maxTokens ?? agentParams.maxTokens,
            topP: over.topP ?? agentParams.topP,
            topK: over.topK ?? agentParams.topK,
            frequencyPenalty: over.frequencyPenalty ?? agentParams.frequencyPenalty,
            presencePenalty: over.presencePenalty ?? agentParams.presencePenalty,
            systemPrompt: (over.systemPrompt?.isEmpty == false) ? over.systemPrompt : agentParams.systemPrompt,
            stopSequences: (over.stopSequences?.isEmpty == false) ? over.stopSequences : agentParams.stopSequences,
            seed: over.seed ?? agentParams.seed,
            responseFormatJson: over.responseFormatJson || agentParams.responseFormatJson,
            searchEnabled: over.searchEnabled || agentParams.searchEnabled,
            returnCitations: over.returnCitations || agentParams.returnCitations,
            searchRecency: over.searchRecency ?? agentParams.searchRecency
        )
    }

    /// Clamp parameter values to valid API ranges.
    func validateParams(_ params: AgentParameters) -> AgentParameters {
        var p = params
        if let t = p.temperature { p.temperature = min(max(t, 0), 2) }
        if let t = p.topP { p.topP = min(max(t, 0), 1) }
        if let t = p.topK { p.topK = max(t, 1) }
        if let t = p.maxTokens { p.maxTokens = max(t, 1) }
        if let t = p.frequencyPenalty { p.frequencyPenalty = min(max(t, -2), 2) }
        if let t = p.presencePenalty { p.presencePenalty = min(max(t, -2), 2) }
        return p
    }

    /// Check if a model requires the Responses API.
    func usesResponsesApi(_ service: AppService, model: String) -> Bool {
        let lowerModel = model.lowercased()
        guard !service.endpointRules.isEmpty else { return false }
        return service.endpointRules.contains {
            lowerModel.hasPrefix($0.modelPrefix.lowercased()) && $0.endpointType == "responses"
        }
    }

    // MARK: - Retry Logic

    /// Execute an API call with one retry on failure.
    func withRetry<T>(
        label: String,
        makeCall: () async throws -> T,
        isSuccess: (T) -> Bool,
        errorResult: (Error) -> T
    ) async -> T {
        do {
            let result = try await makeCall()
            if isSuccess(result) { return result }
            Self.logger.warning("\(label) first attempt failed, retrying...")
            try await Task.sleep(nanoseconds: Self.retryDelayMs)
            return (try? await makeCall()) ?? errorResult(NSError(domain: "macAI", code: -1))
        } catch {
            Self.logger.warning("\(label) first exception: \(error.localizedDescription), retrying...")
            do {
                try await Task.sleep(nanoseconds: Self.retryDelayMs)
                return try await makeCall()
            } catch {
                return errorResult(error)
            }
        }
    }

    // MARK: - Format Helpers

    func formatUsageJson(_ usage: Any?) -> String? {
        guard let usage else { return nil }
        if let encodable = usage as? Encodable {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            if let data = try? encoder.encode(AnyEncodable(encodable)),
               let str = String(data: data, encoding: .utf8) {
                return str
            }
        }
        return nil
    }

    func formatHeaders(_ headers: [AnyHashable: Any]) -> String {
        headers.map { "\($0.key): \($0.value)" }.joined(separator: "\n")
    }
}

// MARK: - AnyEncodable helper

private struct AnyEncodable: Encodable {
    let value: Encodable
    init(_ value: Encodable) { self.value = value }
    func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
}
