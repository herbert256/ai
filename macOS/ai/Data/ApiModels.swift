import Foundation

// MARK: - OpenAI Models

struct OpenAiMessage: Codable {
    let role: String
    let content: String?
    let reasoning_content: String?

    init(role: String, content: String?, reasoning_content: String? = nil) {
        self.role = role
        self.content = content
        self.reasoning_content = reasoning_content
    }
}

struct OpenAiRequest: Codable {
    let model: String
    let messages: [OpenAiMessage]
    let max_tokens: Int?
    let temperature: Float?
    let top_p: Float?
    let top_k: Int?
    let frequency_penalty: Float?
    let presence_penalty: Float?
    let stop: [String]?
    let seed: Int?
    let random_seed: Int?
    let response_format: OpenAiResponseFormat?
    let return_citations: Bool?
    let search_recency_filter: String?
    let search: Bool?

    init(
        model: String,
        messages: [OpenAiMessage],
        max_tokens: Int? = nil,
        temperature: Float? = nil,
        top_p: Float? = nil,
        top_k: Int? = nil,
        frequency_penalty: Float? = nil,
        presence_penalty: Float? = nil,
        stop: [String]? = nil,
        seed: Int? = nil,
        random_seed: Int? = nil,
        response_format: OpenAiResponseFormat? = nil,
        return_citations: Bool? = nil,
        search_recency_filter: String? = nil,
        search: Bool? = nil
    ) {
        self.model = model
        self.messages = messages
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.top_p = top_p
        self.top_k = top_k
        self.frequency_penalty = frequency_penalty
        self.presence_penalty = presence_penalty
        self.stop = stop
        self.seed = seed
        self.random_seed = random_seed
        self.response_format = response_format
        self.return_citations = return_citations
        self.search_recency_filter = search_recency_filter
        self.search = search
    }
}

struct OpenAiResponseFormat: Codable {
    let type: String

    init(type: String = "text") {
        self.type = type
    }
}

struct OpenAiChoice: Codable {
    let message: OpenAiMessage
    let index: Int
}

/// Cost object structure (used by Perplexity)
struct UsageCost: Codable {
    let total_cost: Double?
}

struct OpenAiUsage: Codable {
    let prompt_tokens: Int?
    let completion_tokens: Int?
    let total_tokens: Int?
    let input_tokens: Int?
    let output_tokens: Int?
    let cost: FlexibleCost?
    let cost_in_usd_ticks: Int64?
    let cost_usd: UsageCost?

    /// Effective input token count (Chat Completions or Responses API)
    var effectiveInputTokens: Int? { input_tokens ?? prompt_tokens }
    /// Effective output token count
    var effectiveOutputTokens: Int? { output_tokens ?? completion_tokens }
}

/// Flexible cost that can be either a Double or an object with total_cost.
enum FlexibleCost: Codable {
    case double(Double)
    case object(UsageCost)

    var value: Double? {
        switch self {
        case .double(let v): return v
        case .object(let obj): return obj.total_cost
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let d = try? container.decode(Double.self) {
            self = .double(d)
        } else if let obj = try? container.decode(UsageCost.self) {
            self = .object(obj)
        } else {
            throw DecodingError.typeMismatch(FlexibleCost.self,
                DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "Expected Double or UsageCost"))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .double(let v): try container.encode(v)
        case .object(let obj): try container.encode(obj)
        }
    }
}

/// Search result from AI services that perform web searches
struct SearchResult: Codable {
    let name: String?
    let url: String?
    let snippet: String?
}

struct OpenAiResponse: Codable {
    let id: String?
    let choices: [OpenAiChoice]?
    let usage: OpenAiUsage?
    let error: OpenAiError?
    let citations: [String]?
    let search_results: [SearchResult]?
    let related_questions: [String]?
}

struct OpenAiError: Codable {
    let message: String?
    let type: String?
}

// MARK: - OpenAI Responses API Models (for GPT-5.x and newer)

struct OpenAiResponsesRequest: Codable {
    let model: String
    let input: AnyCodable  // Can be String or [OpenAiResponsesInputMessage]
    let instructions: String?

    init(model: String, input: String, instructions: String? = nil) {
        self.model = model
        self.input = AnyCodable(input)
        self.instructions = instructions
    }

    init(model: String, input: [OpenAiResponsesInputMessage], instructions: String? = nil) {
        self.model = model
        self.input = AnyCodable(input)
        self.instructions = instructions
    }
}

struct OpenAiResponsesStreamRequest: Codable {
    let model: String
    let input: [OpenAiResponsesInputMessage]
    let instructions: String?
    let stream: Bool

    init(model: String, input: [OpenAiResponsesInputMessage], instructions: String? = nil, stream: Bool = true) {
        self.model = model
        self.input = input
        self.instructions = instructions
        self.stream = stream
    }
}

struct OpenAiResponsesInputMessage: Codable {
    let role: String
    let content: String
}

struct OpenAiResponsesOutputContent: Codable {
    let type: String?
    let text: String?
}

struct OpenAiResponsesOutputMessage: Codable {
    let type: String?
    let id: String?
    let status: String?
    let role: String?
    let content: [OpenAiResponsesOutputContent]?
}

struct OpenAiResponsesApiResponse: Codable {
    let id: String?
    let status: String?
    let error: OpenAiResponsesError?
    let output: [OpenAiResponsesOutputMessage]?
    let usage: OpenAiUsage?
}

struct OpenAiResponsesError: Codable {
    let message: String?
    let type: String?
    let code: String?
}

// MARK: - Anthropic Models

struct ClaudeMessage: Codable {
    let role: String
    let content: String
}

struct ClaudeRequest: Codable {
    let model: String
    let max_tokens: Int?
    let messages: [ClaudeMessage]
    let temperature: Float?
    let top_p: Float?
    let top_k: Int?
    let system: String?
    let stop_sequences: [String]?
    let frequency_penalty: Float?
    let presence_penalty: Float?
    let seed: Int?
    let search: Bool?

    init(
        model: String,
        max_tokens: Int? = 4096,
        messages: [ClaudeMessage],
        temperature: Float? = nil,
        top_p: Float? = nil,
        top_k: Int? = nil,
        system: String? = nil,
        stop_sequences: [String]? = nil,
        frequency_penalty: Float? = nil,
        presence_penalty: Float? = nil,
        seed: Int? = nil,
        search: Bool? = nil
    ) {
        self.model = model
        self.max_tokens = max_tokens
        self.messages = messages
        self.temperature = temperature
        self.top_p = top_p
        self.top_k = top_k
        self.system = system
        self.stop_sequences = stop_sequences
        self.frequency_penalty = frequency_penalty
        self.presence_penalty = presence_penalty
        self.seed = seed
        self.search = search
    }
}

struct ClaudeContentBlock: Codable {
    let type: String
    let text: String?
}

struct ClaudeUsage: Codable {
    let input_tokens: Int?
    let output_tokens: Int?
    let cost: Double?
    let cost_in_usd_ticks: Int64?
    let cost_usd: UsageCost?
}

struct ClaudeResponse: Codable {
    let id: String?
    let content: [ClaudeContentBlock]?
    let usage: ClaudeUsage?
    let error: ClaudeError?
}

struct ClaudeError: Codable {
    let type: String?
    let message: String?
}

// MARK: - Google Gemini Models

struct GeminiPart: Codable {
    let text: String
}

struct GeminiContent: Codable {
    let parts: [GeminiPart]
    let role: String?

    init(parts: [GeminiPart], role: String? = nil) {
        self.parts = parts
        self.role = role
    }
}

struct GeminiRequest: Codable {
    let contents: [GeminiContent]
    let generationConfig: GeminiGenerationConfig?
    let systemInstruction: GeminiContent?

    init(contents: [GeminiContent], generationConfig: GeminiGenerationConfig? = nil, systemInstruction: GeminiContent? = nil) {
        self.contents = contents
        self.generationConfig = generationConfig
        self.systemInstruction = systemInstruction
    }
}

struct GeminiGenerationConfig: Codable {
    let temperature: Float?
    let topP: Float?
    let topK: Int?
    let maxOutputTokens: Int?
    let stopSequences: [String]?
    let frequencyPenalty: Float?
    let presencePenalty: Float?
    let seed: Int?

    init(
        temperature: Float? = nil,
        topP: Float? = nil,
        topK: Int? = nil,
        maxOutputTokens: Int? = nil,
        stopSequences: [String]? = nil,
        frequencyPenalty: Float? = nil,
        presencePenalty: Float? = nil,
        seed: Int? = nil
    ) {
        self.temperature = temperature
        self.topP = topP
        self.topK = topK
        self.maxOutputTokens = maxOutputTokens
        self.stopSequences = stopSequences
        self.frequencyPenalty = frequencyPenalty
        self.presencePenalty = presencePenalty
        self.seed = seed
    }
}

struct GeminiCandidate: Codable {
    let content: GeminiContent?
}

struct GeminiUsageMetadata: Codable {
    let promptTokenCount: Int?
    let candidatesTokenCount: Int?
    let totalTokenCount: Int?
    let cost: Double?
    let cost_in_usd_ticks: Int64?
    let cost_usd: UsageCost?
}

struct GeminiResponse: Codable {
    let candidates: [GeminiCandidate]?
    let usageMetadata: GeminiUsageMetadata?
    let error: GeminiError?
}

struct GeminiError: Codable {
    let code: Int?
    let message: String?
    let status: String?
}

// MARK: - Model List Responses

struct OpenAiModelsResponse: Codable {
    let data: [OpenAiModel]?
}

struct OpenAiModel: Codable {
    let id: String?
    let owned_by: String?
}

struct ClaudeModelsResponse: Codable {
    let data: [ClaudeModelInfo]?
}

struct ClaudeModelInfo: Codable {
    let id: String?
    let display_name: String?
    let type: String?
}

struct GeminiModelsResponse: Codable {
    let models: [GeminiModel]?
}

struct GeminiModel: Codable {
    let name: String?
    let displayName: String?
    let supportedGenerationMethods: [String]?
}

// MARK: - Streaming Models

struct OpenAiStreamChunk: Codable {
    let id: String?
    let choices: [StreamChoice]?
    let created: Int64?
}

struct StreamChoice: Codable {
    let index: Int?
    let delta: StreamDelta?
    let finish_reason: String?
}

struct StreamDelta: Codable {
    let role: String?
    let content: String?
    let reasoning_content: String?
}

struct ClaudeStreamEvent: Codable {
    let type: String
    let index: Int?
    let delta: ClaudeStreamDelta?
    let content_block: ClaudeStreamContentBlock?
}

struct ClaudeStreamDelta: Codable {
    let type: String?
    let text: String?
    let stop_reason: String?
}

struct ClaudeStreamContentBlock: Codable {
    let type: String?
    let text: String?
}

struct GeminiStreamChunk: Codable {
    let candidates: [GeminiStreamCandidate]?
}

struct GeminiStreamCandidate: Codable {
    let content: GeminiContent?
    let finishReason: String?
}

// MARK: - Streaming Request Models

struct OpenAiStreamRequest: Codable {
    let model: String
    let messages: [OpenAiMessage]
    let stream: Bool
    let max_tokens: Int?
    let temperature: Float?
    let top_p: Float?
    let top_k: Int?
    let frequency_penalty: Float?
    let presence_penalty: Float?
    let stop: [String]?
    let seed: Int?
    let random_seed: Int?
    let response_format: OpenAiResponseFormat?
    let return_citations: Bool?
    let search_recency_filter: String?
    let search: Bool?

    init(
        model: String,
        messages: [OpenAiMessage],
        stream: Bool = true,
        max_tokens: Int? = nil,
        temperature: Float? = nil,
        top_p: Float? = nil,
        top_k: Int? = nil,
        frequency_penalty: Float? = nil,
        presence_penalty: Float? = nil,
        stop: [String]? = nil,
        seed: Int? = nil,
        random_seed: Int? = nil,
        response_format: OpenAiResponseFormat? = nil,
        return_citations: Bool? = nil,
        search_recency_filter: String? = nil,
        search: Bool? = nil
    ) {
        self.model = model
        self.messages = messages
        self.stream = stream
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.top_p = top_p
        self.top_k = top_k
        self.frequency_penalty = frequency_penalty
        self.presence_penalty = presence_penalty
        self.stop = stop
        self.seed = seed
        self.random_seed = random_seed
        self.response_format = response_format
        self.return_citations = return_citations
        self.search_recency_filter = search_recency_filter
        self.search = search
    }
}

struct ClaudeStreamRequest: Codable {
    let model: String
    let messages: [ClaudeMessage]
    let stream: Bool
    let max_tokens: Int
    let temperature: Float?
    let top_p: Float?
    let top_k: Int?
    let system: String?
    let stop_sequences: [String]?
    let frequency_penalty: Float?
    let presence_penalty: Float?
    let seed: Int?
    let search: Bool?

    init(
        model: String,
        messages: [ClaudeMessage],
        stream: Bool = true,
        max_tokens: Int = 4096,
        temperature: Float? = nil,
        top_p: Float? = nil,
        top_k: Int? = nil,
        system: String? = nil,
        stop_sequences: [String]? = nil,
        frequency_penalty: Float? = nil,
        presence_penalty: Float? = nil,
        seed: Int? = nil,
        search: Bool? = nil
    ) {
        self.model = model
        self.messages = messages
        self.stream = stream
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.top_p = top_p
        self.top_k = top_k
        self.system = system
        self.stop_sequences = stop_sequences
        self.frequency_penalty = frequency_penalty
        self.presence_penalty = presence_penalty
        self.seed = seed
        self.search = search
    }
}

// MARK: - OpenRouter Model Info

struct OpenRouterModelInfo: Codable {
    let id: String
    let name: String?
    let description: String?
    let context_length: Int?
    let pricing: OpenRouterPricing?
    let top_provider: OpenRouterTopProvider?
    let architecture: OpenRouterArchitecture?
    let per_request_limits: OpenRouterLimits?
    let supported_parameters: [String]?
}

struct OpenRouterPricing: Codable {
    let prompt: String?
    let completion: String?
    let image: String?
    let request: String?
}

struct OpenRouterTopProvider: Codable {
    let context_length: Int?
    let max_completion_tokens: Int?
    let is_moderated: Bool?
}

struct OpenRouterArchitecture: Codable {
    let modality: String?
    let tokenizer: String?
    let instruct_type: String?
}

struct OpenRouterLimits: Codable {
    let prompt_tokens: Int?
    let completion_tokens: Int?
}

struct OpenRouterModelsDetailedResponse: Codable {
    let data: [OpenRouterModelInfo]
}

// MARK: - Hugging Face Model Info

struct HuggingFaceModelInfo: Codable {
    let id: String?
    let modelId: String?
    let author: String?
    let downloads: Int64?
    let likes: Int?
    let tags: [String]?
    let pipeline_tag: String?
    let library_name: String?
    let createdAt: String?
    let lastModified: String?
}

// MARK: - AnyCodable helper

/// A type-erased Codable wrapper for encoding flexible types.
struct AnyCodable: Codable {
    let value: Any

    init(_ value: Any) {
        self.value = value
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let str = try? container.decode(String.self) {
            value = str
        } else if let arr = try? container.decode([OpenAiResponsesInputMessage].self) {
            value = arr
        } else {
            throw DecodingError.typeMismatch(AnyCodable.self,
                DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "Unsupported type"))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        if let str = value as? String {
            try container.encode(str)
        } else if let arr = value as? [OpenAiResponsesInputMessage] {
            try container.encode(arr)
        } else if let encodable = value as? Encodable {
            try encodable.encode(to: encoder)
        }
    }
}
