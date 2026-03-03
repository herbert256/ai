import Foundation

// MARK: - OpenAI Compatible Models

struct OpenAiMessage: Codable, Sendable {
    let role: String
    let content: String?
    var reasoning_content: String?
}

struct OpenAiRequest: Codable, Sendable {
    let model: String
    let messages: [OpenAiMessage]
    var max_tokens: Int?
    var temperature: Float?
    var top_p: Float?
    var top_k: Int?
    var frequency_penalty: Float?
    var presence_penalty: Float?
    var stop: [String]?
    var seed: Int?
    var random_seed: Int?
    var response_format: OpenAiResponseFormat?
    var return_citations: Bool?
    var search_recency_filter: String?
    var search: Bool?
}

struct OpenAiStreamRequest: Codable, Sendable {
    let model: String
    let messages: [OpenAiMessage]
    var stream: Bool = true
    var max_tokens: Int?
    var temperature: Float?
    var top_p: Float?
    var top_k: Int?
    var frequency_penalty: Float?
    var presence_penalty: Float?
    var stop: [String]?
    var seed: Int?
    var random_seed: Int?
    var response_format: OpenAiResponseFormat?
    var return_citations: Bool?
    var search_recency_filter: String?
    var search: Bool?
}

struct OpenAiResponseFormat: Codable, Sendable {
    var type: String = "text"
}

struct OpenAiChoice: Codable, Sendable {
    let message: OpenAiMessage
    let index: Int?
}

struct OpenAiUsage: Codable, Sendable {
    let prompt_tokens: Int?
    let completion_tokens: Int?
    let total_tokens: Int?
    var input_tokens: Int?
    var output_tokens: Int?
    var cost: Double?
    var cost_in_usd_ticks: Int64?
    var cost_usd: UsageCost?
}

struct UsageCost: Codable, Sendable {
    let total_cost: Double?
}

struct OpenAiResponse: Codable, Sendable {
    let id: String?
    let choices: [OpenAiChoice]?
    let usage: OpenAiUsage?
    let error: OpenAiError?
    var citations: [String]?
    var search_results: [SearchResult]?
    var related_questions: [String]?
}

struct OpenAiError: Codable, Sendable {
    let message: String?
    let type: String?
}

struct OpenAiModel: Codable, Sendable {
    let id: String?
    let owned_by: String?
}

struct OpenAiModelsResponse: Codable, Sendable {
    let data: [OpenAiModel]?
}

// MARK: - OpenAI Responses API (gpt-5.x, o3, o4)

struct OpenAiResponsesInputMessage: Codable, Sendable {
    let role: String
    let content: String
}

struct OpenAiResponsesRequest: Encodable, Sendable {
    let model: String
    let input: ResponsesInput
    var instructions: String?

    enum ResponsesInput: Encodable, Sendable {
        case text(String)
        case messages([OpenAiResponsesInputMessage])

        func encode(to encoder: Encoder) throws {
            var container = encoder.singleValueContainer()
            switch self {
            case .text(let str): try container.encode(str)
            case .messages(let msgs): try container.encode(msgs)
            }
        }
    }
}

struct OpenAiResponsesStreamRequest: Codable, Sendable {
    let model: String
    let input: [OpenAiResponsesInputMessage]
    var instructions: String?
    var stream: Bool = true
}

struct OpenAiResponsesOutputContent: Codable, Sendable {
    let type: String?
    let text: String?
    var annotations: [AnyCodable]?
}

struct OpenAiResponsesOutputMessage: Codable, Sendable {
    let type: String?
    let id: String?
    let status: String?
    let role: String?
    let content: [OpenAiResponsesOutputContent]?
}

struct OpenAiResponsesApiResponse: Codable, Sendable {
    let id: String?
    let status: String?
    let error: OpenAiResponsesError?
    let output: [OpenAiResponsesOutputMessage]?
    let usage: OpenAiUsage?
}

struct OpenAiResponsesError: Codable, Sendable {
    let message: String?
    let type: String?
    let code: String?
}

// MARK: - OpenAI Stream Chunks

struct OpenAiStreamChunk: Codable, Sendable {
    let id: String?
    let choices: [StreamChoice]?
    let created: Int64?
}

struct StreamChoice: Codable, Sendable {
    let index: Int?
    let delta: StreamDelta?
    let finish_reason: String?
}

struct StreamDelta: Codable, Sendable {
    var role: String?
    var content: String?
    var reasoning_content: String?
}

// MARK: - Anthropic/Claude Models

struct ClaudeMessage: Codable, Sendable {
    let role: String
    let content: String
}

struct ClaudeRequest: Codable, Sendable {
    let model: String
    var max_tokens: Int?
    let messages: [ClaudeMessage]
    var temperature: Float?
    var top_p: Float?
    var top_k: Int?
    var system: String?
    var stop_sequences: [String]?
    var frequency_penalty: Float?
    var presence_penalty: Float?
    var seed: Int?
    var search: Bool?
}

struct ClaudeStreamRequest: Codable, Sendable {
    let model: String
    let messages: [ClaudeMessage]
    var stream: Bool = true
    var max_tokens: Int = 4096
    var temperature: Float?
    var top_p: Float?
    var top_k: Int?
    var system: String?
    var stop_sequences: [String]?
    var frequency_penalty: Float?
    var presence_penalty: Float?
    var seed: Int?
    var search: Bool?
}

struct ClaudeContentBlock: Codable, Sendable {
    let type: String
    let text: String?
}

struct ClaudeUsage: Codable, Sendable {
    let input_tokens: Int?
    let output_tokens: Int?
    var cost: Double?
    var cost_in_usd_ticks: Int64?
    var cost_usd: UsageCost?
}

struct ClaudeResponse: Codable, Sendable {
    let id: String?
    let content: [ClaudeContentBlock]?
    let usage: ClaudeUsage?
    let error: ClaudeError?
}

struct ClaudeError: Codable, Sendable {
    let type: String?
    let message: String?
}

struct ClaudeModelInfo: Codable, Sendable {
    let id: String?
    let display_name: String?
    let type: String?
}

struct ClaudeModelsResponse: Codable, Sendable {
    let data: [ClaudeModelInfo]?
}

struct ClaudeStreamEvent: Codable, Sendable {
    let type: String
    var index: Int?
    var delta: ClaudeStreamDelta?
    var content_block: ClaudeStreamContentBlock?
}

struct ClaudeStreamDelta: Codable, Sendable {
    var type: String?
    var text: String?
    var stop_reason: String?
}

struct ClaudeStreamContentBlock: Codable, Sendable {
    var type: String?
    var text: String?
}

// MARK: - Google Gemini Models

struct GeminiPart: Codable, Sendable {
    let text: String
}

struct GeminiContent: Codable, Sendable {
    let parts: [GeminiPart]
    var role: String?
}

struct GeminiRequest: Codable, Sendable {
    let contents: [GeminiContent]
    var generationConfig: GeminiGenerationConfig?
    var systemInstruction: GeminiContent?
}

struct GeminiGenerationConfig: Codable, Sendable {
    var temperature: Float?
    var topP: Float?
    var topK: Int?
    var maxOutputTokens: Int?
    var stopSequences: [String]?
    var frequencyPenalty: Float?
    var presencePenalty: Float?
    var seed: Int?
    var search: Bool?
}

struct GeminiCandidate: Codable, Sendable {
    let content: GeminiContent?
}

struct GeminiUsageMetadata: Codable, Sendable {
    let promptTokenCount: Int?
    let candidatesTokenCount: Int?
    let totalTokenCount: Int?
    var cost: Double?
    var cost_in_usd_ticks: Int64?
    var cost_usd: UsageCost?
}

struct GeminiResponse: Codable, Sendable {
    let candidates: [GeminiCandidate]?
    let usageMetadata: GeminiUsageMetadata?
    let error: GeminiError?
}

struct GeminiError: Codable, Sendable {
    let code: Int?
    let message: String?
    let status: String?
}

struct GeminiModel: Codable, Sendable {
    let name: String?
    let displayName: String?
    let supportedGenerationMethods: [String]?
}

struct GeminiModelsResponse: Codable, Sendable {
    let models: [GeminiModel]?
}

struct GeminiStreamChunk: Codable, Sendable {
    let candidates: [GeminiStreamCandidate]?
}

struct GeminiStreamCandidate: Codable, Sendable {
    let content: GeminiContent?
    let finishReason: String?
}

// MARK: - Model Info APIs

struct OpenRouterModelInfo: Codable, Sendable {
    let id: String
    var name: String?
    var description: String?
    var context_length: Int?
    var pricing: OpenRouterPricing?
    var top_provider: OpenRouterTopProvider?
    var architecture: OpenRouterArchitecture?
    var per_request_limits: OpenRouterLimits?
    var supported_parameters: [String]?
}

struct OpenRouterPricing: Codable, Sendable {
    var prompt: String?
    var completion: String?
    var image: String?
    var request: String?
}

struct OpenRouterTopProvider: Codable, Sendable {
    var context_length: Int?
    var max_completion_tokens: Int?
    var is_moderated: Bool?
}

struct OpenRouterArchitecture: Codable, Sendable {
    var modality: String?
    var tokenizer: String?
    var instruct_type: String?
}

struct OpenRouterLimits: Codable, Sendable {
    var prompt_tokens: Int?
    var completion_tokens: Int?
}

struct OpenRouterModelsDetailedResponse: Codable, Sendable {
    let data: [OpenRouterModelInfo]
}

// MARK: - Hugging Face Model Info

struct HuggingFaceModelInfo: Codable, Sendable {
    var id: String?
    var modelId: String?
    var author: String?
    var sha: String?
    var downloads: Int64?
    var likes: Int?
    var tags: [String]?
    var pipeline_tag: String?
    var library_name: String?
    var createdAt: String?
    var lastModified: String?
    var cardData: HuggingFaceCardData?
}

struct HuggingFaceCardData: Codable, Sendable {
    var license: String?
    var language: [String]?
    var datasets: [String]?
    var base_model: String?
    var model_type: String?
    var pipeline_tag: String?
    var tags: [String]?
}

// MARK: - AnyCodable (for generic JSON)

struct AnyCodable: Codable, Sendable {
    let value: Any

    init(_ value: Any) { self.value = value }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let str = try? container.decode(String.self) { value = str }
        else if let int = try? container.decode(Int.self) { value = int }
        else if let double = try? container.decode(Double.self) { value = double }
        else if let bool = try? container.decode(Bool.self) { value = bool }
        else { value = "null" }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        if let str = value as? String { try container.encode(str) }
        else if let int = value as? Int { try container.encode(int) }
        else if let double = value as? Double { try container.encode(double) }
        else if let bool = value as? Bool { try container.encode(bool) }
        else { try container.encodeNil() }
    }
}
