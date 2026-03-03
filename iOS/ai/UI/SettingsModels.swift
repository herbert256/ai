import Foundation

// MARK: - Model Source

enum ModelSource: String, Codable, Sendable {
    case api = "API"
    case manual = "MANUAL"
}

// MARK: - Provider Config

struct ProviderConfig: Codable, Equatable, Sendable {
    var apiKey: String = ""
    var model: String = ""
    var modelSource: ModelSource = .api
    var models: [String] = []
    var adminUrl: String = ""
    var modelListUrl: String = ""
    var parametersIds: [String] = []
}

// MARK: - Endpoint

struct Endpoint: Codable, Identifiable, Equatable, Sendable {
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

struct Agent: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var name: String
    var providerId: String
    var model: String
    var apiKey: String
    var endpointId: String?
    var paramsIds: [String]
    var systemPromptId: String?

    init(id: String = UUID().uuidString, name: String, providerId: String, model: String,
         apiKey: String = "", endpointId: String? = nil, paramsIds: [String] = [],
         systemPromptId: String? = nil) {
        self.id = id
        self.name = name
        self.providerId = providerId
        self.model = model
        self.apiKey = apiKey
        self.endpointId = endpointId
        self.paramsIds = paramsIds
        self.systemPromptId = systemPromptId
    }
}

// MARK: - Flock

struct Flock: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var name: String
    var agentIds: [String]
    var paramsIds: [String]
    var systemPromptId: String?

    init(id: String = UUID().uuidString, name: String, agentIds: [String] = [],
         paramsIds: [String] = [], systemPromptId: String? = nil) {
        self.id = id
        self.name = name
        self.agentIds = agentIds
        self.paramsIds = paramsIds
        self.systemPromptId = systemPromptId
    }
}

// MARK: - Swarm Member

struct SwarmMember: Codable, Equatable, Sendable {
    let providerId: String
    let model: String
}

// MARK: - Swarm

struct Swarm: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var name: String
    var members: [SwarmMember]
    var paramsIds: [String]
    var systemPromptId: String?

    init(id: String = UUID().uuidString, name: String, members: [SwarmMember] = [],
         paramsIds: [String] = [], systemPromptId: String? = nil) {
        self.id = id
        self.name = name
        self.members = members
        self.paramsIds = paramsIds
        self.systemPromptId = systemPromptId
    }
}

// MARK: - Parameters (preset)

struct Parameters: Codable, Identifiable, Equatable, Sendable {
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

    init(id: String = UUID().uuidString, name: String = "", temperature: Float? = nil,
         maxTokens: Int? = nil, topP: Float? = nil, topK: Int? = nil,
         frequencyPenalty: Float? = nil, presencePenalty: Float? = nil,
         systemPrompt: String? = nil, stopSequences: [String]? = nil,
         seed: Int? = nil, responseFormatJson: Bool = false,
         searchEnabled: Bool = false, returnCitations: Bool = true,
         searchRecency: String? = nil) {
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

    func toAgentParameters() -> AgentParameters {
        AgentParameters(
            temperature: temperature, maxTokens: maxTokens, topP: topP, topK: topK,
            frequencyPenalty: frequencyPenalty, presencePenalty: presencePenalty,
            systemPrompt: systemPrompt, stopSequences: stopSequences, seed: seed,
            responseFormatJson: responseFormatJson, searchEnabled: searchEnabled,
            returnCitations: returnCitations, searchRecency: searchRecency
        )
    }
}

// MARK: - System Prompt

struct SystemPrompt: Codable, Identifiable, Equatable, Sendable {
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

struct Prompt: Codable, Identifiable, Equatable, Sendable {
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

    func resolvePrompt(model: String? = nil, provider: String? = nil,
                       agent: String? = nil, swarm: String? = nil) -> String {
        var result = promptText
        if let model = model { result = result.replacingOccurrences(of: "@MODEL@", with: model) }
        if let provider = provider { result = result.replacingOccurrences(of: "@PROVIDER@", with: provider) }
        if let agent = agent { result = result.replacingOccurrences(of: "@AGENT@", with: agent) }
        if let swarm = swarm { result = result.replacingOccurrences(of: "@SWARM@", with: swarm) }
        result = result.replacingOccurrences(of: "@NOW@", with: ISO8601DateFormatter().string(from: Date()))
        return result
    }
}

// MARK: - Settings

struct Settings: Equatable, Sendable {
    var providers: [String: ProviderConfig] = [:]  // Keyed by AppService.id
    var agents: [Agent] = []
    var flocks: [Flock] = []
    var swarms: [Swarm] = []
    var parameters: [Parameters] = []
    var systemPrompts: [SystemPrompt] = []
    var prompts: [Prompt] = []
    var endpoints: [String: [Endpoint]] = [:]  // Keyed by provider ID
    var providerStates: [String: String] = [:]

    // MARK: - Provider Access

    func getProviderState(_ serviceId: String) -> String {
        providerStates[serviceId] ?? "not-used"
    }

    func isProviderActive(_ serviceId: String) -> Bool {
        let state = getProviderState(serviceId)
        return state != "inactive"
    }

    func getProvider(_ serviceId: String) -> ProviderConfig {
        providers[serviceId] ?? ProviderConfig()
    }

    mutating func withProvider(_ serviceId: String, _ config: ProviderConfig) {
        providers[serviceId] = config
    }

    func getApiKey(_ serviceId: String) -> String {
        getProvider(serviceId).apiKey
    }

    mutating func withApiKey(_ serviceId: String, _ key: String) {
        var config = getProvider(serviceId)
        config.apiKey = key
        providers[serviceId] = config
    }

    func getModel(_ serviceId: String) -> String {
        getProvider(serviceId).model
    }

    mutating func withModel(_ serviceId: String, _ model: String) {
        var config = getProvider(serviceId)
        config.model = model
        providers[serviceId] = config
    }

    func getModelSource(_ serviceId: String) -> ModelSource {
        getProvider(serviceId).modelSource
    }

    func getModels(_ serviceId: String) -> [String] {
        getProvider(serviceId).models
    }

    mutating func withModels(_ serviceId: String, _ models: [String]) {
        var config = getProvider(serviceId)
        config.models = models
        providers[serviceId] = config
    }

    func hasAnyApiKey() -> Bool {
        providers.values.contains { !$0.apiKey.isEmpty }
    }

    mutating func withProviderState(_ serviceId: String, _ state: String) {
        providerStates[serviceId] = state
    }

    // MARK: - Agent Helpers

    func getAgentById(_ id: String) -> Agent? {
        agents.first { $0.id == id }
    }

    func getConfiguredAgents() -> [Agent] {
        agents.filter { agent in
            let key = getEffectiveApiKeyForAgent(agent)
            return !key.isEmpty
        }
    }

    func getEffectiveApiKeyForAgent(_ agent: Agent) -> String {
        if !agent.apiKey.isEmpty { return agent.apiKey }
        return getApiKey(agent.providerId)
    }

    func getEffectiveModelForAgent(_ agent: Agent) -> String {
        if !agent.model.isEmpty { return agent.model }
        return getModel(agent.providerId)
    }

    func resolveAgentParameters(_ agent: Agent) -> AgentParameters {
        mergeParameters(agent.paramsIds)
    }

    // MARK: - Flock Helpers

    func getFlockById(_ id: String) -> Flock? {
        flocks.first { $0.id == id }
    }

    func getAgentsForFlock(_ flock: Flock) -> [Agent] {
        flock.agentIds.compactMap { getAgentById($0) }
    }

    func getAgentsForFlocks(_ flockIds: Set<String>) -> [Agent] {
        flockIds.flatMap { id in getFlockById(id).map { getAgentsForFlock($0) } ?? [] }
    }

    // MARK: - Swarm Helpers

    func getSwarmById(_ id: String) -> Swarm? {
        swarms.first { $0.id == id }
    }

    func getMembersForSwarm(_ swarm: Swarm) -> [SwarmMember] {
        swarm.members
    }

    func getMembersForSwarms(_ swarmIds: Set<String>) -> [SwarmMember] {
        swarmIds.flatMap { id in getSwarmById(id)?.members ?? [] }
    }

    // MARK: - Prompt Helpers

    func getPromptByName(_ name: String) -> Prompt? {
        prompts.first { $0.name == name }
    }

    func getPromptById(_ id: String) -> Prompt? {
        prompts.first { $0.id == id }
    }

    func getAgentForPrompt(_ prompt: Prompt) -> Agent? {
        getAgentById(prompt.agentId)
    }

    func getSystemPromptById(_ id: String) -> SystemPrompt? {
        systemPrompts.first { $0.id == id }
    }

    func getParametersById(_ id: String) -> Parameters? {
        parameters.first { $0.id == id }
    }

    func getParametersByName(_ name: String) -> Parameters? {
        parameters.first { $0.name == name }
    }

    // MARK: - Endpoint Helpers

    func getEndpointsForProvider(_ serviceId: String) -> [Endpoint] {
        endpoints[serviceId] ?? []
    }

    func getEndpointById(_ serviceId: String, _ endpointId: String) -> Endpoint? {
        endpoints[serviceId]?.first { $0.id == endpointId }
    }

    func getDefaultEndpoint(_ serviceId: String) -> Endpoint? {
        endpoints[serviceId]?.first { $0.isDefault }
    }

    func getEffectiveEndpointUrl(_ service: AppService) -> String {
        if let def = getDefaultEndpoint(service.id) {
            return def.url
        }
        return service.baseUrl
    }

    func getEffectiveEndpointUrlForAgent(_ agent: Agent, service: AppService) -> String {
        if let eid = agent.endpointId, let ep = getEndpointById(agent.providerId, eid) {
            return ep.url
        }
        return getEffectiveEndpointUrl(service)
    }

    mutating func withEndpoints(_ serviceId: String, _ newEndpoints: [Endpoint]) {
        endpoints[serviceId] = newEndpoints
    }

    // MARK: - Parameters Merging

    func getParametersIds(_ serviceId: String) -> [String] {
        getProvider(serviceId).parametersIds
    }

    mutating func withParametersIds(_ serviceId: String, _ ids: [String]) {
        var config = getProvider(serviceId)
        config.parametersIds = ids
        providers[serviceId] = config
    }

    func getParametersByIds(_ ids: [String]) -> [Parameters] {
        ids.compactMap { getParametersById($0) }
    }

    func mergeParameters(_ ids: [String]) -> AgentParameters {
        let paramsList = getParametersByIds(ids)
        guard !paramsList.isEmpty else { return AgentParameters() }

        var merged = AgentParameters()
        for p in paramsList {
            if let v = p.temperature { merged.temperature = v }
            if let v = p.maxTokens { merged.maxTokens = v }
            if let v = p.topP { merged.topP = v }
            if let v = p.topK { merged.topK = v }
            if let v = p.frequencyPenalty { merged.frequencyPenalty = v }
            if let v = p.presencePenalty { merged.presencePenalty = v }
            if let v = p.systemPrompt, !v.isEmpty { merged.systemPrompt = v }
            if let v = p.stopSequences, !v.isEmpty { merged.stopSequences = v }
            if let v = p.seed { merged.seed = v }
            if p.responseFormatJson { merged.responseFormatJson = true }
            if p.searchEnabled { merged.searchEnabled = true }
            merged.returnCitations = p.returnCitations
            if let v = p.searchRecency { merged.searchRecency = v }
        }
        return merged
    }

    // MARK: - Model List URL

    func getModelListUrl(_ serviceId: String) -> String {
        getProvider(serviceId).modelListUrl
    }

    func getEffectiveModelListUrl(_ service: AppService) -> String {
        let custom = getModelListUrl(service.id)
        if !custom.isEmpty { return custom }
        return ApiClient.modelsUrl(for: service)
    }

    // MARK: - Active Services

    func getActiveServiceIds() -> [String] {
        providers.keys.filter { isProviderActive($0) && !getApiKey($0).isEmpty }.sorted()
    }

    // MARK: - Removal

    mutating func removeSystemPrompt(_ id: String) {
        systemPrompts.removeAll { $0.id == id }
        for i in agents.indices {
            if agents[i].systemPromptId == id { agents[i].systemPromptId = nil }
        }
        for i in flocks.indices {
            if flocks[i].systemPromptId == id { flocks[i].systemPromptId = nil }
        }
        for i in swarms.indices {
            if swarms[i].systemPromptId == id { swarms[i].systemPromptId = nil }
        }
    }

    mutating func removeParameters(_ id: String) {
        parameters.removeAll { $0.id == id }
        for i in agents.indices {
            agents[i].paramsIds.removeAll { $0 == id }
        }
        for i in flocks.indices {
            flocks[i].paramsIds.removeAll { $0 == id }
        }
        for i in swarms.indices {
            swarms[i].paramsIds.removeAll { $0 == id }
        }
        for key in providers.keys {
            providers[key]?.parametersIds.removeAll { $0 == id }
        }
    }

    mutating func removeAgent(_ id: String) {
        agents.removeAll { $0.id == id }
        for i in flocks.indices {
            flocks[i].agentIds.removeAll { $0 == id }
        }
        prompts.removeAll { $0.agentId == id }
    }
}
