import Foundation

// MARK: - Model Source

enum ModelSource: String, Codable, CaseIterable {
    case api = "API"
    case manual = "MANUAL"
}

// MARK: - Provider Config

/// Per-provider configuration stored in Settings.
struct ProviderConfig: Codable, Equatable {
    var apiKey: String
    var model: String
    var modelSource: ModelSource
    var models: [String]
    var adminUrl: String
    var modelListUrl: String
    var parametersIds: [String]

    init(
        apiKey: String = "",
        model: String = "",
        modelSource: ModelSource = .api,
        models: [String] = [],
        adminUrl: String = "",
        modelListUrl: String = "",
        parametersIds: [String] = []
    ) {
        self.apiKey = apiKey
        self.model = model
        self.modelSource = modelSource
        self.models = models
        self.adminUrl = adminUrl
        self.modelListUrl = modelListUrl
        self.parametersIds = parametersIds
    }
}

/// Create default ProviderConfig for a service.
func defaultProviderConfig(_ service: AppService) -> ProviderConfig {
    let defaultModels = service.hardcodedModels ?? []
    let defaultModelSource: ModelSource
    if let source = service.defaultModelSource {
        defaultModelSource = ModelSource(rawValue: source) ?? (defaultModels.isEmpty ? .api : .manual)
    } else {
        defaultModelSource = defaultModels.isEmpty ? .api : .manual
    }
    return ProviderConfig(
        model: service.defaultModel,
        modelSource: defaultModelSource,
        models: defaultModels,
        adminUrl: service.adminUrl
    )
}

/// Create the default providers map with correct defaults for all services.
func defaultProvidersMap() -> [String: ProviderConfig] {
    var map: [String: ProviderConfig] = [:]
    for service in AppService.entries {
        map[service.id] = defaultProviderConfig(service)
    }
    return map
}

// MARK: - Parameter enum

/// AI Parameter types that can be configured per agent.
enum Parameter: String, Codable, CaseIterable {
    case temperature = "TEMPERATURE"
    case maxTokens = "MAX_TOKENS"
    case topP = "TOP_P"
    case topK = "TOP_K"
    case frequencyPenalty = "FREQUENCY_PENALTY"
    case presencePenalty = "PRESENCE_PENALTY"
    case systemPrompt = "SYSTEM_PROMPT"
    case stopSequences = "STOP_SEQUENCES"
    case seed = "SEED"
    case responseFormat = "RESPONSE_FORMAT"
    case searchEnabled = "SEARCH_ENABLED"
    case returnCitations = "RETURN_CITATIONS"
    case searchRecency = "SEARCH_RECENCY"
}

// MARK: - Built-in Endpoints

private let BUILT_IN_ENDPOINTS: [String: [Endpoint]] = [
    "OPENAI": [
        Endpoint(id: "openai-chat", name: "Chat Completions", url: "https://api.openai.com/v1/chat/completions", isDefault: true),
        Endpoint(id: "openai-responses", name: "Responses API", url: "https://api.openai.com/v1/responses")
    ],
    "MISTRAL": [
        Endpoint(id: "mistral-chat", name: "Chat Completions", url: "https://api.mistral.ai/v1/chat/completions", isDefault: true),
        Endpoint(id: "mistral-codestral", name: "Codestral", url: "https://codestral.mistral.ai/v1/chat/completions")
    ],
    "DEEPSEEK": [
        Endpoint(id: "deepseek-chat", name: "Chat Completions", url: "https://api.deepseek.com/chat/completions", isDefault: true),
        Endpoint(id: "deepseek-beta", name: "Beta (FIM)", url: "https://api.deepseek.com/beta/completions")
    ],
    "ZAI": [
        Endpoint(id: "zai-chat", name: "Chat Completions", url: "https://api.z.ai/api/paas/v4/chat/completions", isDefault: true),
        Endpoint(id: "zai-coding", name: "Coding", url: "https://api.z.ai/api/coding/paas/v4/chat/completions")
    ]
]

// MARK: - Endpoint

/// AI Endpoint - configurable API endpoint for a provider.
struct Endpoint: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var name: String
    var url: String
    var isDefault: Bool

    init(id: String = UUID().uuidString, name: String, url: String, isDefault: Bool = false) {
        self.id = id
        self.name = name
        self.url = url
        self.isDefault = isDefault
    }
}

// MARK: - Agent

/// AI Agent - user-created configuration combining provider, model, API key, and parameter presets.
struct Agent: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var name: String
    var providerId: String      // Reference to AppService.id
    var model: String
    var apiKey: String
    var endpointId: String?
    var paramsIds: [String]
    var systemPromptId: String?

    init(
        id: String = UUID().uuidString,
        name: String,
        providerId: String,
        model: String = "",
        apiKey: String = "",
        endpointId: String? = nil,
        paramsIds: [String] = [],
        systemPromptId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.providerId = providerId
        self.model = model
        self.apiKey = apiKey
        self.endpointId = endpointId
        self.paramsIds = paramsIds
        self.systemPromptId = systemPromptId
    }

    /// Resolve the provider from the registry.
    var provider: AppService? { AppService.findById(providerId) }
}

// MARK: - Flock

/// AI Flock - a named group of AI Agents that work together.
struct Flock: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var name: String
    var agentIds: [String]
    var paramsIds: [String]
    var systemPromptId: String?

    init(
        id: String = UUID().uuidString,
        name: String,
        agentIds: [String] = [],
        paramsIds: [String] = [],
        systemPromptId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.agentIds = agentIds
        self.paramsIds = paramsIds
        self.systemPromptId = systemPromptId
    }
}

// MARK: - Swarm Member

/// A provider/model combination within a swarm.
struct SwarmMember: Codable, Equatable, Hashable {
    let providerId: String
    let model: String

    var provider: AppService? { AppService.findById(providerId) }
}

// MARK: - Swarm

/// AI Swarm - a named group of provider/model combinations.
struct Swarm: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var name: String
    var members: [SwarmMember]
    var paramsIds: [String]
    var systemPromptId: String?

    init(
        id: String = UUID().uuidString,
        name: String,
        members: [SwarmMember] = [],
        paramsIds: [String] = [],
        systemPromptId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.members = members
        self.paramsIds = paramsIds
        self.systemPromptId = systemPromptId
    }
}

// MARK: - Parameters

/// AI Parameters - a named parameter preset that can be reused across agents or reports.
struct Parameters: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var name: String
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
        id: String = UUID().uuidString,
        name: String,
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
        self.id = id
        self.name = name
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

    /// Convert to AgentParameters for use with agents.
    func toAgentParameters() -> AgentParameters {
        AgentParameters(
            temperature: temperature,
            maxTokens: maxTokens,
            topP: topP,
            topK: topK,
            frequencyPenalty: frequencyPenalty,
            presencePenalty: presencePenalty,
            systemPrompt: systemPrompt,
            stopSequences: stopSequences,
            seed: seed,
            responseFormatJson: responseFormatJson,
            searchEnabled: searchEnabled,
            returnCitations: returnCitations,
            searchRecency: searchRecency
        )
    }
}

// MARK: - System Prompt

/// A reusable named system prompt.
struct SystemPrompt: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var name: String
    var prompt: String

    init(id: String = UUID().uuidString, name: String, prompt: String) {
        self.id = id
        self.name = name
        self.prompt = prompt
    }
}

// MARK: - Prompt

/// Internal prompts used by the app for AI-powered features.
struct Prompt: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var name: String
    var agentId: String
    var promptText: String

    init(id: String = UUID().uuidString, name: String, agentId: String, promptText: String) {
        self.id = id
        self.name = name
        self.agentId = agentId
        self.promptText = promptText
    }

    /// Replace variables in prompt text with actual values.
    func resolvePrompt(model: String? = nil, provider: String? = nil, agent: String? = nil, swarm: String? = nil) -> String {
        var resolved = promptText
        if let model { resolved = resolved.replacingOccurrences(of: "@MODEL@", with: model) }
        if let provider { resolved = resolved.replacingOccurrences(of: "@PROVIDER@", with: provider) }
        if let agent { resolved = resolved.replacingOccurrences(of: "@AGENT@", with: agent) }
        if let swarm { resolved = resolved.replacingOccurrences(of: "@SWARM@", with: swarm) }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        resolved = resolved.replacingOccurrences(of: "@NOW@", with: formatter.string(from: Date()))
        return resolved
    }
}

// MARK: - Settings

/// AI Settings - main configuration for all providers, agents, flocks, etc.
struct Settings: Codable, Equatable {
    var providers: [String: ProviderConfig]  // keyed by AppService.id
    var agents: [Agent]
    var flocks: [Flock]
    var swarms: [Swarm]
    var parameters: [Parameters]
    var systemPrompts: [SystemPrompt]
    var prompts: [Prompt]
    var endpoints: [String: [Endpoint]]  // keyed by AppService.id
    var providerStates: [String: String]  // "ok", "error", "not-used", "inactive"

    init(
        providers: [String: ProviderConfig] = defaultProvidersMap(),
        agents: [Agent] = [],
        flocks: [Flock] = [],
        swarms: [Swarm] = [],
        parameters: [Parameters] = [],
        systemPrompts: [SystemPrompt] = [],
        prompts: [Prompt] = [],
        endpoints: [String: [Endpoint]] = [:],
        providerStates: [String: String] = [:]
    ) {
        self.providers = providers
        self.agents = agents
        self.flocks = flocks
        self.swarms = swarms
        self.parameters = parameters
        self.systemPrompts = systemPrompts
        self.prompts = prompts
        self.endpoints = endpoints
        self.providerStates = providerStates
    }

    // MARK: - Provider State

    func getProviderState(_ service: AppService) -> String {
        let stored = providerStates[service.id]
        if stored == "inactive" { return "inactive" }
        if getApiKey(service).isEmpty { return "not-used" }
        return stored ?? "ok"
    }

    func isProviderActive(_ service: AppService) -> Bool {
        getProviderState(service) == "ok"
    }

    func getActiveServices() -> [AppService] {
        AppService.entries.filter { isProviderActive($0) }
    }

    mutating func setProviderState(_ service: AppService, _ state: String) {
        providerStates[service.id] = state
    }

    // MARK: - Provider Config

    func getProvider(_ service: AppService) -> ProviderConfig {
        providers[service.id] ?? defaultProviderConfig(service)
    }

    mutating func setProvider(_ service: AppService, _ config: ProviderConfig) {
        providers[service.id] = config
    }

    func getApiKey(_ service: AppService) -> String {
        getProvider(service).apiKey
    }

    mutating func setApiKey(_ service: AppService, _ apiKey: String) {
        var config = getProvider(service)
        config.apiKey = apiKey
        setProvider(service, config)
    }

    func getModel(_ service: AppService) -> String {
        getProvider(service).model
    }

    mutating func setModel(_ service: AppService, _ model: String) {
        var config = getProvider(service)
        config.model = model
        setProvider(service, config)
    }

    func getModelSource(_ service: AppService) -> ModelSource {
        getProvider(service).modelSource
    }

    func getModels(_ service: AppService) -> [String] {
        getProvider(service).models
    }

    mutating func setModels(_ service: AppService, _ models: [String]) {
        var config = getProvider(service)
        config.models = models
        setProvider(service, config)
    }

    func hasAnyApiKey() -> Bool {
        providers.values.contains { !$0.apiKey.isEmpty }
    }

    // MARK: - Agent helpers

    func getAgentById(_ id: String) -> Agent? {
        agents.first { $0.id == id }
    }

    func resolveAgentParameters(_ agent: Agent) -> AgentParameters {
        mergeParameters(agent.paramsIds) ?? AgentParameters()
    }

    func getEffectiveApiKeyForAgent(_ agent: Agent) -> String {
        if !agent.apiKey.isEmpty { return agent.apiKey }
        guard let provider = agent.provider else { return "" }
        return getApiKey(provider)
    }

    func getEffectiveModelForAgent(_ agent: Agent) -> String {
        if !agent.model.isEmpty { return agent.model }
        guard let provider = agent.provider else { return "" }
        return getModel(provider)
    }

    func getConfiguredAgents() -> [Agent] {
        agents.filter { agent in
            !agent.apiKey.isEmpty || (agent.provider.map { !getApiKey($0).isEmpty } ?? false)
        }
    }

    // MARK: - Flock helpers

    func getFlockById(_ id: String) -> Flock? {
        flocks.first { $0.id == id }
    }

    func getAgentsForFlock(_ flock: Flock) -> [Agent] {
        flock.agentIds.compactMap { getAgentById($0) }
    }

    // MARK: - Swarm helpers

    func getSwarmById(_ id: String) -> Swarm? {
        swarms.first { $0.id == id }
    }

    // MARK: - Prompt / SystemPrompt helpers

    func getPromptByName(_ name: String) -> Prompt? {
        prompts.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }
    }

    func getSystemPromptById(_ id: String) -> SystemPrompt? {
        systemPrompts.first { $0.id == id }
    }

    func getParametersById(_ id: String) -> Parameters? {
        parameters.first { $0.id == id }
    }

    func getPromptById(_ id: String) -> Prompt? {
        prompts.first { $0.id == id }
    }

    // MARK: - Endpoint helpers

    func getEndpointsForProvider(_ provider: AppService) -> [Endpoint] {
        let custom = endpoints[provider.id]
        if let custom, !custom.isEmpty { return custom }
        return BUILT_IN_ENDPOINTS[provider.id] ?? defaultEndpointForProvider(provider)
    }

    private func defaultEndpointForProvider(_ provider: AppService) -> [Endpoint] {
        let base = provider.baseUrl.hasSuffix("/") ? provider.baseUrl : "\(provider.baseUrl)/"
        let url = base + provider.chatPath
        let idPrefix = provider.id.lowercased()
        return [Endpoint(id: "\(idPrefix)-chat", name: "Chat Completions", url: url, isDefault: true)]
    }

    func getEndpointById(_ provider: AppService, _ endpointId: String) -> Endpoint? {
        getEndpointsForProvider(provider).first { $0.id == endpointId }
    }

    func getDefaultEndpoint(_ provider: AppService) -> Endpoint? {
        let eps = getEndpointsForProvider(provider)
        return eps.first(where: { $0.isDefault }) ?? eps.first
    }

    func getEffectiveEndpointUrl(_ provider: AppService) -> String {
        getDefaultEndpoint(provider)?.url ?? provider.baseUrl
    }

    func getEffectiveEndpointUrlForAgent(_ agent: Agent) -> String {
        guard let provider = agent.provider else { return "" }
        if let endpointId = agent.endpointId,
           let endpoint = getEndpointById(provider, endpointId) {
            return endpoint.url
        }
        return getEffectiveEndpointUrl(provider)
    }

    mutating func setEndpoints(_ provider: AppService, _ newEndpoints: [Endpoint]) {
        endpoints[provider.id] = newEndpoints
    }

    // MARK: - Model list URL helpers

    func getModelListUrl(_ service: AppService) -> String {
        getProvider(service).modelListUrl
    }

    func getDefaultModelListUrl(_ service: AppService) -> String {
        guard let modelsPath = service.modelsPath else { return "" }
        let base = service.baseUrl.hasSuffix("/") ? service.baseUrl : "\(service.baseUrl)/"
        return base + modelsPath
    }

    func getEffectiveModelListUrl(_ service: AppService) -> String {
        let custom = getModelListUrl(service)
        return custom.isEmpty ? getDefaultModelListUrl(service) : custom
    }

    // MARK: - Parameters helpers

    func getParametersIds(_ service: AppService) -> [String] {
        getProvider(service).parametersIds
    }

    mutating func setParametersIds(_ service: AppService, _ paramsIds: [String]) {
        var config = getProvider(service)
        config.parametersIds = paramsIds
        setProvider(service, config)
    }

    func getParametersByIds(_ ids: [String]) -> [Parameters] {
        ids.compactMap { getParametersById($0) }
    }

    /// Merge multiple parameter presets in order. Later non-null fields override earlier ones.
    func mergeParameters(_ ids: [String]) -> AgentParameters? {
        guard !ids.isEmpty else { return nil }
        let presets = getParametersByIds(ids)
        guard !presets.isEmpty else { return nil }
        return presets.map { $0.toAgentParameters() }.reduce(into: AgentParameters()) { acc, params in
            if let v = params.temperature { acc.temperature = v }
            if let v = params.maxTokens { acc.maxTokens = v }
            if let v = params.topP { acc.topP = v }
            if let v = params.topK { acc.topK = v }
            if let v = params.frequencyPenalty { acc.frequencyPenalty = v }
            if let v = params.presencePenalty { acc.presencePenalty = v }
            if let v = params.systemPrompt { acc.systemPrompt = v }
            if let v = params.stopSequences { acc.stopSequences = v }
            if let v = params.seed { acc.seed = v }
            acc.responseFormatJson = params.responseFormatJson
            acc.searchEnabled = params.searchEnabled
            acc.returnCitations = params.returnCitations
            if let v = params.searchRecency { acc.searchRecency = v }
        }
    }

    // MARK: - Derived copies

    /// Return a copy of settings with updated models for a provider.
    func withModels(_ service: AppService, _ models: [String]) -> Settings {
        var copy = self
        copy.setModels(service, models)
        return copy
    }

    /// Return a copy of settings with updated provider state.
    func withProviderState(_ service: AppService, _ state: String) -> Settings {
        var copy = self
        copy.setProviderState(service, state)
        return copy
    }

    // MARK: - Swarm expansion

    /// Get all SwarmMembers from multiple selected swarm IDs.
    func getMembersForSwarms(_ swarmIds: Set<String>) -> [SwarmMember] {
        swarmIds.flatMap { id in
            getSwarmById(id)?.members ?? []
        }
    }

    // MARK: - Removal helpers

    mutating func removeAgent(_ agentId: String) {
        agents.removeAll { $0.id == agentId }
        for i in flocks.indices {
            flocks[i].agentIds.removeAll { $0 == agentId }
        }
        prompts.removeAll { $0.agentId == agentId }
    }

    mutating func removeSystemPrompt(_ systemPromptId: String) {
        systemPrompts.removeAll { $0.id == systemPromptId }
        for i in agents.indices {
            if agents[i].systemPromptId == systemPromptId { agents[i].systemPromptId = nil }
        }
        for i in flocks.indices {
            if flocks[i].systemPromptId == systemPromptId { flocks[i].systemPromptId = nil }
        }
        for i in swarms.indices {
            if swarms[i].systemPromptId == systemPromptId { swarms[i].systemPromptId = nil }
        }
    }

    mutating func removeParameters(_ parametersId: String) {
        parameters.removeAll { $0.id == parametersId }
        for i in agents.indices {
            agents[i].paramsIds.removeAll { $0 == parametersId }
        }
        for i in flocks.indices {
            flocks[i].paramsIds.removeAll { $0 == parametersId }
        }
        for i in swarms.indices {
            swarms[i].paramsIds.removeAll { $0 == parametersId }
        }
        for key in providers.keys {
            providers[key]?.parametersIds.removeAll { $0 == parametersId }
        }
    }
}

// MARK: - Report Model

/// A model entry in the AI Reports selection list.
struct ReportModel: Identifiable, Equatable, Hashable {
    let id = UUID()
    let providerId: String
    let model: String
    let type: String           // "agent" or "model"
    let sourceType: String     // "flock", "agent", "swarm", "model"
    let sourceName: String
    let agentId: String?
    let endpointId: String?
    let agentApiKey: String?
    let paramsIds: [String]

    var provider: AppService? { AppService.findById(providerId) }
    var deduplicationKey: String { "\(providerId):\(model)" }

    init(
        providerId: String,
        model: String,
        type: String,
        sourceType: String,
        sourceName: String = "",
        agentId: String? = nil,
        endpointId: String? = nil,
        agentApiKey: String? = nil,
        paramsIds: [String] = []
    ) {
        self.providerId = providerId
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

func expandFlockToModels(_ flock: Flock, _ settings: Settings) -> [ReportModel] {
    flock.agentIds.compactMap { agentId in
        guard let agent = settings.getAgentById(agentId),
              let provider = agent.provider,
              settings.isProviderActive(provider) else { return nil }
        return ReportModel(
            providerId: agent.providerId,
            model: settings.getEffectiveModelForAgent(agent),
            type: "agent",
            sourceType: "flock",
            sourceName: flock.name,
            agentId: agent.id,
            endpointId: agent.endpointId,
            agentApiKey: settings.getEffectiveApiKeyForAgent(agent),
            paramsIds: flock.paramsIds + agent.paramsIds
        )
    }
}

func expandSwarmToModels(_ swarm: Swarm, _ settings: Settings) -> [ReportModel] {
    swarm.members.compactMap { member in
        guard let provider = member.provider, settings.isProviderActive(provider) else { return nil }
        return ReportModel(
            providerId: member.providerId,
            model: member.model,
            type: "model",
            sourceType: "swarm",
            sourceName: swarm.name,
            paramsIds: swarm.paramsIds
        )
    }
}

func deduplicateModels(_ models: [ReportModel]) -> [ReportModel] {
    var seen = Set<String>()
    return models.filter { seen.insert($0.deduplicationKey).inserted }
}

// MARK: - Usage Stats

/// Statistics for a specific provider+model combination.
struct UsageStats: Codable, Equatable {
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

// MARK: - Prompt History

struct PromptHistoryEntry: Codable, Identifiable {
    let id = UUID()
    let timestamp: Date
    let title: String
    let prompt: String

    enum CodingKeys: String, CodingKey {
        case timestamp, title, prompt
    }
}
