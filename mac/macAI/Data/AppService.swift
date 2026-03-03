import Foundation

// MARK: - API Format

/// API format used by a provider.
enum ApiFormat: String, Codable, CaseIterable {
    case openaiCompatible = "OPENAI_COMPATIBLE"
    case anthropic = "ANTHROPIC"
    case google = "GOOGLE"
}

// MARK: - Endpoint Rule

/// Rule mapping model prefixes to endpoint types.
/// Used to route models to different API endpoints (e.g., OpenAI Responses API for gpt-5.x).
struct EndpointRule: Codable, Equatable, Hashable {
    let modelPrefix: String
    let endpointType: String  // "responses" or "chat" (default)
}

// MARK: - AppService

/// Data class representing a supported AI service provider.
/// Identity is based on `id` only.
final class AppService: Identifiable, Hashable, Codable {
    let id: String
    let displayName: String
    let baseUrl: String
    let adminUrl: String
    let defaultModel: String
    let openRouterName: String?
    let apiFormat: ApiFormat
    let chatPath: String
    let modelsPath: String?
    let prefsKey: String
    // Provider-specific quirks
    let seedFieldName: String
    let supportsCitations: Bool
    let supportsSearchRecency: Bool
    let extractApiCost: Bool
    let costTicksDivisor: Double?
    let modelListFormat: String
    let modelFilter: String?
    let litellmPrefix: String?
    let hardcodedModels: [String]?
    let defaultModelSource: String?
    let endpointRules: [EndpointRule]

    /// Cached compiled regex for modelFilter.
    lazy var modelFilterRegex: NSRegularExpression? = {
        guard let pattern = modelFilter else { return nil }
        return try? NSRegularExpression(pattern: pattern, options: .caseInsensitive)
    }()

    init(
        id: String,
        displayName: String,
        baseUrl: String,
        adminUrl: String = "",
        defaultModel: String,
        openRouterName: String? = nil,
        apiFormat: ApiFormat = .openaiCompatible,
        chatPath: String = "v1/chat/completions",
        modelsPath: String? = "v1/models",
        prefsKey: String = "",
        seedFieldName: String = "seed",
        supportsCitations: Bool = false,
        supportsSearchRecency: Bool = false,
        extractApiCost: Bool = false,
        costTicksDivisor: Double? = nil,
        modelListFormat: String = "object",
        modelFilter: String? = nil,
        litellmPrefix: String? = nil,
        hardcodedModels: [String]? = nil,
        defaultModelSource: String? = nil,
        endpointRules: [EndpointRule] = []
    ) {
        self.id = id
        self.displayName = displayName
        self.baseUrl = baseUrl
        self.adminUrl = adminUrl
        self.defaultModel = defaultModel
        self.openRouterName = openRouterName
        self.apiFormat = apiFormat
        self.chatPath = chatPath
        self.modelsPath = modelsPath
        self.prefsKey = prefsKey.isEmpty ? id.lowercased() : prefsKey
        self.seedFieldName = seedFieldName
        self.supportsCitations = supportsCitations
        self.supportsSearchRecency = supportsSearchRecency
        self.extractApiCost = extractApiCost
        self.costTicksDivisor = costTicksDivisor
        self.modelListFormat = modelListFormat
        self.modelFilter = modelFilter
        self.litellmPrefix = litellmPrefix
        self.hardcodedModels = hardcodedModels
        self.defaultModelSource = defaultModelSource
        self.endpointRules = endpointRules
    }

    // MARK: - Equatable / Hashable (identity by id)

    static func == (lhs: AppService, rhs: AppService) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }

    // MARK: - Codable (serialize as ID string)

    enum CodingKeys: String, CodingKey {
        case id, displayName, baseUrl, adminUrl, defaultModel, openRouterName
        case apiFormat, chatPath, modelsPath, prefsKey, seedFieldName
        case supportsCitations, supportsSearchRecency, extractApiCost
        case costTicksDivisor, modelListFormat, modelFilter, litellmPrefix
        case hardcodedModels, defaultModelSource, endpointRules
    }

    // MARK: - Static registry accessors

    /// All registered providers - delegates to ProviderRegistry.
    static var entries: [AppService] {
        ProviderRegistry.shared.getAll()
    }

    /// Find a provider by its ID string, or nil if not found.
    static func findById(_ id: String) -> AppService? {
        ProviderRegistry.shared.findById(id)
    }
}

// MARK: - Provider Definition (JSON serializable)

/// JSON-serializable representation of an AppService provider definition.
/// Used for setup.json and UserDefaults storage.
struct ProviderDefinition: Codable {
    let id: String
    let displayName: String
    let baseUrl: String
    let adminUrl: String?
    let defaultModel: String
    let openRouterName: String?
    let apiFormat: String?
    let chatPath: String?
    let modelsPath: String?
    let prefsKey: String?
    let seedFieldName: String?
    let supportsCitations: Bool?
    let supportsSearchRecency: Bool?
    let extractApiCost: Bool?
    let costTicksDivisor: Double?
    let modelListFormat: String?
    let modelFilter: String?
    let litellmPrefix: String?
    let hardcodedModels: [String]?
    let defaultModelSource: String?
    let endpointRules: [EndpointRule]?

    func toAppService() -> AppService {
        AppService(
            id: id,
            displayName: displayName,
            baseUrl: baseUrl,
            adminUrl: adminUrl ?? "",
            defaultModel: defaultModel,
            openRouterName: openRouterName,
            apiFormat: ApiFormat(rawValue: apiFormat ?? "OPENAI_COMPATIBLE") ?? .openaiCompatible,
            chatPath: chatPath ?? "v1/chat/completions",
            modelsPath: modelsPath,
            prefsKey: prefsKey ?? id.lowercased(),
            seedFieldName: seedFieldName ?? "seed",
            supportsCitations: supportsCitations ?? false,
            supportsSearchRecency: supportsSearchRecency ?? false,
            extractApiCost: extractApiCost ?? false,
            costTicksDivisor: costTicksDivisor,
            modelListFormat: modelListFormat ?? "object",
            modelFilter: modelFilter,
            litellmPrefix: litellmPrefix,
            hardcodedModels: hardcodedModels,
            defaultModelSource: defaultModelSource,
            endpointRules: endpointRules ?? []
        )
    }

    static func fromAppService(_ s: AppService) -> ProviderDefinition {
        ProviderDefinition(
            id: s.id,
            displayName: s.displayName,
            baseUrl: s.baseUrl,
            adminUrl: s.adminUrl,
            defaultModel: s.defaultModel,
            openRouterName: s.openRouterName,
            apiFormat: s.apiFormat.rawValue,
            chatPath: s.chatPath,
            modelsPath: s.modelsPath,
            prefsKey: s.prefsKey,
            seedFieldName: s.seedFieldName,
            supportsCitations: s.supportsCitations,
            supportsSearchRecency: s.supportsSearchRecency,
            extractApiCost: s.extractApiCost,
            costTicksDivisor: s.costTicksDivisor,
            modelListFormat: s.modelListFormat,
            modelFilter: s.modelFilter,
            litellmPrefix: s.litellmPrefix,
            hardcodedModels: s.hardcodedModels,
            defaultModelSource: s.defaultModelSource,
            endpointRules: s.endpointRules.isEmpty ? nil : s.endpointRules
        )
    }
}
