import Foundation

// MARK: - Agent Parameters

struct AgentParameters: Codable, Equatable, Sendable {
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
    var searchRecency: String?

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

struct ChatMessage: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let role: String
    let content: String
    let timestamp: Int64

    init(role: String, content: String, timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        self.id = UUID().uuidString
        self.role = role
        self.content = content
        self.timestamp = timestamp
    }

    static let roleSystem = "system"
    static let roleUser = "user"
    static let roleAssistant = "assistant"
}

// MARK: - Chat Parameters

struct ChatParameters: Codable, Equatable, Sendable {
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

struct ChatSession: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let providerId: String
    let model: String
    var messages: [ChatMessage]
    var parameters: ChatParameters
    let createdAt: Int64
    var updatedAt: Int64

    init(
        id: String = UUID().uuidString,
        providerId: String,
        model: String,
        messages: [ChatMessage] = [],
        parameters: ChatParameters = ChatParameters(),
        createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        updatedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.id = id
        self.providerId = providerId
        self.model = model
        self.messages = messages
        self.parameters = parameters
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var preview: String {
        messages.first(where: { $0.role == ChatMessage.roleUser })?.content.prefix(50).description ?? "Empty chat"
    }
}

// MARK: - Dual Chat Config

struct DualChatConfig: Codable, Equatable, Sendable {
    let model1ProviderId: String
    let model1Name: String
    var model1SystemPrompt: String
    var model1Params: ChatParameters
    let model2ProviderId: String
    let model2Name: String
    var model2SystemPrompt: String
    var model2Params: ChatParameters
    var subject: String
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

struct TokenUsage: Codable, Equatable, Sendable {
    let inputTokens: Int
    let outputTokens: Int
    var apiCost: Double?

    var totalTokens: Int { inputTokens + outputTokens }

    init(inputTokens: Int = 0, outputTokens: Int = 0, apiCost: Double? = nil) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.apiCost = apiCost
    }
}

// MARK: - Analysis Response

struct AnalysisResponse: Sendable {
    let service: AppService
    let analysis: String?
    let error: String?
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
    var displayName: String { agentName ?? service.displayName }

    init(
        service: AppService,
        analysis: String? = nil,
        error: String? = nil,
        tokenUsage: TokenUsage? = nil,
        agentName: String? = nil,
        promptUsed: String? = nil,
        citations: [String]? = nil,
        searchResults: [SearchResult]? = nil,
        relatedQuestions: [String]? = nil,
        rawUsageJson: String? = nil,
        httpHeaders: String? = nil,
        httpStatusCode: Int? = nil
    ) {
        self.service = service
        self.analysis = analysis
        self.error = error
        self.tokenUsage = tokenUsage
        self.agentName = agentName
        self.promptUsed = promptUsed
        self.citations = citations
        self.searchResults = searchResults
        self.relatedQuestions = relatedQuestions
        self.rawUsageJson = rawUsageJson
        self.httpHeaders = httpHeaders
        self.httpStatusCode = httpStatusCode
    }
}

// MARK: - Search Result

struct SearchResult: Codable, Equatable, Sendable {
    let name: String?
    let url: String?
    let snippet: String?
}

// MARK: - Usage Stats

struct UsageStats: Codable, Equatable, Sendable {
    let providerId: String
    let model: String
    var callCount: Int
    var inputTokens: Int64
    var outputTokens: Int64

    var totalTokens: Int64 { inputTokens + outputTokens }
    var key: String { "\(providerId)::\(model)" }

    init(providerId: String, model: String, callCount: Int = 0, inputTokens: Int64 = 0, outputTokens: Int64 = 0) {
        self.providerId = providerId
        self.model = model
        self.callCount = callCount
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
    }
}

// MARK: - Report Models

enum ReportType: String, Codable, Sendable {
    case classic = "CLASSIC"
    case table = "TABLE"
}

enum ReportStatus: String, Codable, Sendable {
    case pending = "PENDING"
    case running = "RUNNING"
    case success = "SUCCESS"
    case error = "ERROR"
    case stopped = "STOPPED"

    var isTerminal: Bool {
        self == .success || self == .error || self == .stopped
    }
}

struct ReportAgent: Codable, Identifiable, Sendable {
    let agentId: String
    let agentName: String
    let provider: String
    let model: String
    var reportStatus: ReportStatus
    var httpStatus: Int?
    var requestHeaders: String?
    var requestBody: String?
    var responseHeaders: String?
    var responseBody: String?
    var errorMessage: String?
    var tokenUsage: TokenUsage?
    var cost: Double?
    var citations: [String]?
    var searchResults: [SearchResult]?
    var relatedQuestions: [String]?
    var durationMs: Int64?

    var id: String { agentId }

    init(
        agentId: String,
        agentName: String,
        provider: String,
        model: String,
        reportStatus: ReportStatus = .pending
    ) {
        self.agentId = agentId
        self.agentName = agentName
        self.provider = provider
        self.model = model
        self.reportStatus = reportStatus
    }
}

struct Report: Codable, Identifiable, Sendable {
    let id: String
    let timestamp: Int64
    var title: String
    var prompt: String
    var agents: [ReportAgent]
    var totalCost: Double
    var completedAt: Int64?
    var rapportText: String?
    var reportType: ReportType
    var closeText: String?

    init(
        id: String = UUID().uuidString,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        title: String,
        prompt: String,
        agents: [ReportAgent] = [],
        totalCost: Double = 0.0,
        completedAt: Int64? = nil,
        rapportText: String? = nil,
        reportType: ReportType = .classic,
        closeText: String? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.title = title
        self.prompt = prompt
        self.agents = agents
        self.totalCost = totalCost
        self.completedAt = completedAt
        self.rapportText = rapportText
        self.reportType = reportType
        self.closeText = closeText
    }
}

// MARK: - Report Model (for selection)

struct ReportModel: Identifiable, Equatable, Sendable {
    let provider: AppService
    let model: String
    let type: String
    let sourceType: String
    let sourceName: String
    var agentId: String?
    var endpointId: String?
    var agentApiKey: String?
    var paramsIds: [String]

    var id: String { deduplicationKey }
    var deduplicationKey: String { "\(provider.id):\(model)" }

    init(
        provider: AppService,
        model: String,
        type: String = "model",
        sourceType: String = "model",
        sourceName: String = "",
        agentId: String? = nil,
        endpointId: String? = nil,
        agentApiKey: String? = nil,
        paramsIds: [String] = []
    ) {
        self.provider = provider
        self.model = model
        self.type = type
        self.sourceType = sourceType
        self.sourceName = sourceName
        self.agentId = agentId
        self.endpointId = endpointId
        self.agentApiKey = agentApiKey
        self.paramsIds = paramsIds
    }
}

// MARK: - Prompt History Entry

struct PromptHistoryEntry: Codable, Identifiable, Equatable, Sendable {
    let timestamp: Int64
    let title: String
    let prompt: String

    var id: Int64 { timestamp }
}
