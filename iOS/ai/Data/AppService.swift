import Foundation

// MARK: - API Format

enum ApiFormat: String, Codable, Sendable {
    case openaiCompatible = "OPENAI_COMPATIBLE"
    case anthropic = "ANTHROPIC"
    case google = "GOOGLE"
}

// MARK: - Endpoint Rule

struct EndpointRule: Codable, Equatable, Sendable {
    let modelPrefix: String
    let endpointType: String
}

// MARK: - App Service (Provider)

final class AppService: Identifiable, Sendable {
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

    // Computed
    var modelFilterRegex: NSRegularExpression? {
        guard let filter = modelFilter else { return nil }
        return try? NSRegularExpression(pattern: filter, options: .caseInsensitive)
    }

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
        self.prefsKey = prefsKey
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

    func matchesFilter(_ modelId: String) -> Bool {
        guard let regex = modelFilterRegex else { return true }
        let range = NSRange(modelId.startIndex..., in: modelId)
        return regex.firstMatch(in: modelId, range: range) != nil
    }

    func usesResponsesApi(model: String) -> Bool {
        let lower = model.lowercased()
        return endpointRules.contains { rule in
            lower.hasPrefix(rule.modelPrefix.lowercased()) && rule.endpointType == "responses"
        }
    }
}

extension AppService: Equatable {
    static func == (lhs: AppService, rhs: AppService) -> Bool { lhs.id == rhs.id }
}

extension AppService: Hashable {
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

// MARK: - Provider Definition (JSON-serializable)

struct ProviderDefinition: Codable, Sendable {
    let id: String
    let displayName: String
    let baseUrl: String
    var adminUrl: String?
    let defaultModel: String
    var openRouterName: String?
    var apiFormat: String?
    var chatPath: String?
    var modelsPath: String?
    var prefsKey: String?
    var seedFieldName: String?
    var supportsCitations: Bool?
    var supportsSearchRecency: Bool?
    var extractApiCost: Bool?
    var costTicksDivisor: Double?
    var modelListFormat: String?
    var modelFilter: String?
    var litellmPrefix: String?
    var hardcodedModels: [String]?
    var defaultModelSource: String?
    var endpointRules: [EndpointRule]?

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
            prefsKey: prefsKey ?? "",
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

    static func fromAppService(_ service: AppService) -> ProviderDefinition {
        ProviderDefinition(
            id: service.id,
            displayName: service.displayName,
            baseUrl: service.baseUrl,
            adminUrl: service.adminUrl,
            defaultModel: service.defaultModel,
            openRouterName: service.openRouterName,
            apiFormat: service.apiFormat.rawValue,
            chatPath: service.chatPath,
            modelsPath: service.modelsPath,
            prefsKey: service.prefsKey,
            seedFieldName: service.seedFieldName,
            supportsCitations: service.supportsCitations,
            supportsSearchRecency: service.supportsSearchRecency,
            extractApiCost: service.extractApiCost,
            costTicksDivisor: service.costTicksDivisor,
            modelListFormat: service.modelListFormat,
            modelFilter: service.modelFilter,
            litellmPrefix: service.litellmPrefix,
            hardcodedModels: service.hardcodedModels,
            defaultModelSource: service.defaultModelSource,
            endpointRules: service.endpointRules
        )
    }
}
