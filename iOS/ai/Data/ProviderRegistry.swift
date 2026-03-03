import Foundation

// MARK: - Provider Registry

actor ProviderRegistry {
    static let shared = ProviderRegistry()

    private var providers: [AppService] = []
    private var initialized = false

    // MARK: - Initialization

    func initialize() {
        guard !initialized else { return }

        let saved = loadFromUserDefaults()
        if !saved.isEmpty {
            providers = saved
        } else {
            loadFromBundledSetup()
        }

        if providers.isEmpty {
            loadFromBundledSetup()
        }

        initialized = true
    }

    private func loadFromBundledSetup() {
        guard let url = Bundle.main.url(forResource: "setup", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return }

        let decoder = JSONDecoder()
        guard let config = try? decoder.decode(SetupConfig.self, from: data) else { return }

        let defs = config.providerDefinitions ?? []
        providers = defs.map { $0.toAppService() }
        save()
    }

    private func loadFromUserDefaults() -> [AppService] {
        guard let json = UserDefaults.standard.string(forKey: "provider_registry_json"),
              let data = json.data(using: .utf8) else { return [] }

        let decoder = JSONDecoder()
        guard let defs = try? decoder.decode([ProviderDefinition].self, from: data) else { return [] }
        return defs.map { $0.toAppService() }
    }

    private func save() {
        let defs = providers.map { ProviderDefinition.fromAppService($0) }
        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(defs),
              let json = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(json, forKey: "provider_registry_json")
    }

    // MARK: - CRUD

    func getAll() -> [AppService] {
        return providers
    }

    func findById(_ id: String) -> AppService? {
        return providers.first { $0.id == id }
    }

    func add(_ service: AppService) {
        guard !providers.contains(where: { $0.id == service.id }) else { return }
        providers.append(service)
        save()
    }

    func update(_ service: AppService) {
        if let idx = providers.firstIndex(where: { $0.id == service.id }) {
            providers[idx] = service
            save()
        }
    }

    func remove(_ id: String) {
        providers.removeAll { $0.id == id }
        save()
    }

    func resetToDefaults() {
        UserDefaults.standard.removeObject(forKey: "provider_registry_json")
        providers = []
        initialized = false
        loadFromBundledSetup()
        initialized = true
    }

    func ensureProviders(_ definitions: [ProviderDefinition]) {
        var changed = false
        for def in definitions {
            if let idx = providers.firstIndex(where: { $0.id == def.id }) {
                providers[idx] = def.toAppService()
                changed = true
            } else {
                providers.append(def.toAppService())
                changed = true
            }
        }
        if changed { save() }
    }
}

// MARK: - Setup Config (for reading setup.json)

struct SetupConfig: Codable {
    let version: Int?
    var providers: [String: ProviderConfigExport]?
    var agents: [AgentExport]?
    var providerDefinitions: [ProviderDefinition]?
    var flocks: [FlockExport]?
    var swarms: [SwarmExport]?
    var parameters: [ParametersExport]?
    var systemPrompts: [SystemPromptExport]?
    var aiPrompts: [PromptExport]?
    var manualPricing: [ManualPricingExport]?
    var providerEndpoints: [ProviderEndpointsExport]?
    var huggingFaceApiKey: String?
    var openRouterApiKey: String?
    var providerStates: [String: String]?
}

// Forward declarations for export types (defined in SettingsExport.swift)
// These are minimal Codable structs used during import

struct ProviderConfigExport: Codable, Sendable {
    var modelSource: String?
    var models: [String]?
    var apiKey: String?
    var defaultModel: String?
    var adminUrl: String?
    var modelListUrl: String?
    var parametersIds: [String]?
    var displayName: String?
    var baseUrl: String?
    var apiFormat: String?
    var chatPath: String?
    var modelsPath: String?
    var openRouterName: String?
    var endpointRules: [EndpointRule]?
}

struct AgentExport: Codable, Sendable {
    let id: String
    let name: String
    let provider: String
    let model: String
    let apiKey: String
    var parametersIds: [String]?
    var endpointId: String?
    var systemPromptId: String?
}

struct FlockExport: Codable, Sendable {
    let id: String
    let name: String
    let agentIds: [String]
    var parametersIds: [String]?
    var systemPromptId: String?
}

struct SwarmMemberExport: Codable, Sendable {
    let provider: String
    let model: String
}

struct SwarmExport: Codable, Sendable {
    let id: String
    let name: String
    let members: [SwarmMemberExport]
    var parametersIds: [String]?
    var systemPromptId: String?
}

struct PromptExport: Codable, Sendable {
    let id: String
    let name: String
    let agentId: String
    let promptText: String
}

struct ParametersExport: Codable, Sendable {
    let id: String
    let name: String
    var temperature: Float?
    var maxTokens: Int?
    var topP: Float?
    var topK: Int?
    var frequencyPenalty: Float?
    var presencePenalty: Float?
    var systemPrompt: String?
    var stopSequences: [String]?
    var seed: Int?
    var responseFormatJson: Bool?
    var searchEnabled: Bool?
    var returnCitations: Bool?
    var searchRecency: String?
}

struct SystemPromptExport: Codable, Sendable {
    let id: String
    let name: String
    let prompt: String
}

struct ManualPricingExport: Codable, Sendable {
    let key: String
    let promptPrice: Double
    let completionPrice: Double
}

struct EndpointExport: Codable, Sendable {
    let id: String
    let name: String
    let url: String
    var isDefault: Bool?
}

struct ProviderEndpointsExport: Codable, Sendable {
    let provider: String
    let endpoints: [EndpointExport]
}
