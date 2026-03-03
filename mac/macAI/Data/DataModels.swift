import Foundation

// MARK: - Agent Parameters

/// Configuration for AI agent parameters with defaults.
struct AgentParameters: Codable, Equatable {
    var temperature: Float?
    var maxTokens: Int?
    var topP: Float?
    var topK: Int?
    var frequencyPenalty: Float?
    var presencePenalty: Float?
    var systemPrompt: String?
    var stopSequences: [String]?
    var seed: Int?
    var responseFormatJson: Bool
    var searchEnabled: Bool
    var returnCitations: Bool
    var searchRecency: String?  // "day", "week", "month", "year"

    init(
        temperature: Float? = nil,
        maxTokens: Int? = nil,
        topP: Float? = nil,
        topK: Int? = nil,
        frequencyPenalty: Float? = nil,
        presencePenalty: Float? = nil,
        systemPrompt: String? = nil,
        stopSequences: [String]? = nil,
        seed: Int? = nil,
        responseFormatJson: Bool = false,
        searchEnabled: Bool = false,
        returnCitations: Bool = true,
        searchRecency: String? = nil
    ) {
        self.temperature = temperature
        self.maxTokens = maxTokens
        self.topP = topP
        self.topK = topK
        self.frequencyPenalty = frequencyPenalty
        self.presencePenalty = presencePenalty
        self.systemPrompt = systemPrompt
        self.stopSequences = stopSequences
        self.seed = seed
        self.responseFormatJson = responseFormatJson
        self.searchEnabled = searchEnabled
        self.returnCitations = returnCitations
        self.searchRecency = searchRecency
    }
}

// MARK: - Chat Message

/// A single message in a chat conversation.
struct ChatMessage: Codable, Identifiable, Equatable {
    let id: UUID
    let role: String       // "system", "user", or "assistant"
    let content: String
    let timestamp: Date

    init(role: String, content: String, timestamp: Date = Date()) {
        self.id = UUID()
        self.role = role
        self.content = content
        self.timestamp = timestamp
    }
}

// MARK: - Chat Parameters

/// Parameters for a chat session (subset of AgentParameters used for chat).
struct ChatParameters: Codable, Equatable {
    var systemPrompt: String
    var temperature: Float?
    var maxTokens: Int?
    var topP: Float?
    var topK: Int?
    var frequencyPenalty: Float?
    var presencePenalty: Float?
    var searchEnabled: Bool
    var returnCitations: Bool
    var searchRecency: String?

    init(
        systemPrompt: String = "",
        temperature: Float? = nil,
        maxTokens: Int? = nil,
        topP: Float? = nil,
        topK: Int? = nil,
        frequencyPenalty: Float? = nil,
        presencePenalty: Float? = nil,
        searchEnabled: Bool = false,
        returnCitations: Bool = true,
        searchRecency: String? = nil
    ) {
        self.systemPrompt = systemPrompt
        self.temperature = temperature
        self.maxTokens = maxTokens
        self.topP = topP
        self.topK = topK
        self.frequencyPenalty = frequencyPenalty
        self.presencePenalty = presencePenalty
        self.searchEnabled = searchEnabled
        self.returnCitations = returnCitations
        self.searchRecency = searchRecency
    }
}

// MARK: - Chat Session

/// A saved chat session with all messages.
struct ChatSession: Codable, Identifiable {
    let id: String
    let providerId: String
    let model: String
    var messages: [ChatMessage]
    var parameters: ChatParameters
    let createdAt: Date
    var updatedAt: Date

    /// Preview text from first user message
    var preview: String {
        messages.first(where: { $0.role == "user" })?.content.prefix(50).description ?? "Empty chat"
    }

    init(
        id: String = UUID().uuidString,
        providerId: String,
        model: String,
        messages: [ChatMessage] = [],
        parameters: ChatParameters = ChatParameters(),
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.providerId = providerId
        self.model = model
        self.messages = messages
        self.parameters = parameters
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    /// Resolve the provider from the registry.
    var provider: AppService? {
        AppService.findById(providerId)
    }
}

// MARK: - Dual Chat Config

/// Configuration for a dual-chat session where two AI models converse.
struct DualChatConfig: Codable {
    let model1ProviderId: String
    let model1Name: String
    var model1SystemPrompt: String
    var model1Params: ChatParameters
    let model2ProviderId: String
    let model2Name: String
    var model2SystemPrompt: String
    var model2Params: ChatParameters
    let subject: String
    var interactionCount: Int
    var firstPrompt: String
    var secondPrompt: String

    init(
        model1ProviderId: String,
        model1Name: String,
        model1SystemPrompt: String = "",
        model1Params: ChatParameters = ChatParameters(),
        model2ProviderId: String,
        model2Name: String,
        model2SystemPrompt: String = "",
        model2Params: ChatParameters = ChatParameters(),
        subject: String,
        interactionCount: Int = 10,
        firstPrompt: String = "Let's talk about %subject%",
        secondPrompt: String = "What do you think about: %answer%"
    ) {
        self.model1ProviderId = model1ProviderId
        self.model1Name = model1Name
        self.model1SystemPrompt = model1SystemPrompt
        self.model1Params = model1Params
        self.model2ProviderId = model2ProviderId
        self.model2Name = model2Name
        self.model2SystemPrompt = model2SystemPrompt
        self.model2Params = model2Params
        self.subject = subject
        self.interactionCount = interactionCount
        self.firstPrompt = firstPrompt
        self.secondPrompt = secondPrompt
    }
}

// MARK: - Token Usage

struct TokenUsage: Codable {
    let inputTokens: Int
    let outputTokens: Int
    let apiCost: Double?
    var totalTokens: Int { inputTokens + outputTokens }

    init(inputTokens: Int = 0, outputTokens: Int = 0, apiCost: Double? = nil) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.apiCost = apiCost
    }
}

// MARK: - Report Status

enum ReportStatus: String, Codable {
    case pending = "PENDING"
    case running = "RUNNING"
    case success = "SUCCESS"
    case error = "ERROR"
    case stopped = "STOPPED"
}

// MARK: - Report Agent

/// Tracks the state of a single agent within a report generation run.
struct ReportAgent: Identifiable {
    let id: String
    let agentId: String
    let agentName: String
    let providerId: String
    let model: String
    var status: ReportStatus

    init(agentId: String, agentName: String, provider: String, model: String, reportStatus: ReportStatus = .pending) {
        self.id = agentId
        self.agentId = agentId
        self.agentName = agentName
        self.providerId = provider
        self.model = model
        self.status = reportStatus
    }
}

// MARK: - Analysis Response

/// Result of an AI analysis (report or chat).
struct AnalysisResponse: Identifiable {
    let id = UUID()
    let service: AppService
    var analysis: String?
    var error: String?
    var tokenUsage: TokenUsage?
    var agentName: String?
    var promptUsed: String?
    var citations: [String]?
    var searchResults: [SearchResult]?
    var relatedQuestions: [String]?
    var rawUsageJson: String?
    var httpHeaders: String?
    var httpStatusCode: Int?

    var isSuccess: Bool { analysis != nil && error == nil }

    var displayName: String {
        if let name = agentName, !name.isEmpty {
            return "\(name) (\(service.displayName))"
        }
        return service.displayName
    }
}
