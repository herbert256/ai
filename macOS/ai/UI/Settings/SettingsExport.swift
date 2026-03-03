import SwiftUI
import AppKit
import os

/// Export/import configuration in v21 format, compatible with Android.
enum SettingsExporter {
    private static let logger = Logger(subsystem: "com.ai.macAI", category: "SettingsExporter")
    private static let currentVersion = 21

    // MARK: - Export Models

    struct ConfigExport: Codable {
        let version: Int
        let providers: [String: ProviderConfigExport]
        let agents: [AgentExport]
        var flocks: [FlockExport]?
        var swarms: [SwarmExport]?
        var parameters: [ParametersExport]?
        var systemPrompts: [SystemPromptExport]?
        var huggingFaceApiKey: String?
        var aiPrompts: [PromptExport]?
        var openRouterApiKey: String?
        var providerStates: [String: String]?
    }

    struct ProviderConfigExport: Codable {
        let modelSource: String
        let models: [String]
        var apiKey: String = ""
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
    }

    struct AgentExport: Codable {
        let id: String
        let name: String
        let provider: String
        let model: String
        let apiKey: String
        var parametersIds: [String]?
        var endpointId: String?
        var systemPromptId: String?
    }

    struct FlockExport: Codable {
        let id: String
        let name: String
        let agentIds: [String]
        var parametersIds: [String]?
        var systemPromptId: String?
    }

    struct SwarmMemberExport: Codable {
        let provider: String
        let model: String
    }

    struct SwarmExport: Codable {
        let id: String
        let name: String
        let members: [SwarmMemberExport]
        var parametersIds: [String]?
        var systemPromptId: String?
    }

    struct ParametersExport: Codable {
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
        var responseFormatJson: Bool = false
        var searchEnabled: Bool = false
        var returnCitations: Bool = false
        var searchRecency: String?
    }

    struct SystemPromptExport: Codable {
        let id: String
        let name: String
        let prompt: String
    }

    struct PromptExport: Codable {
        let id: String
        let name: String
        let agentId: String
        let promptText: String
    }

    struct EndpointExport: Codable {
        let id: String
        let name: String
        let url: String
        var isDefault: Bool = false
    }

    struct ProviderEndpointsExport: Codable {
        let provider: String
        let endpoints: [EndpointExport]
    }

    // MARK: - Export

    static func exportSettings(_ aiSettings: Settings, _ generalSettings: GeneralSettings) {
        let providers = Dictionary(uniqueKeysWithValues: AppService.entries.map { service -> (String, ProviderConfigExport) in
            let config = aiSettings.getProvider(service)
            let export = ProviderConfigExport(
                modelSource: config.modelSource.rawValue,
                models: config.models,
                apiKey: config.apiKey,
                defaultModel: config.model.isEmpty ? nil : config.model,
                adminUrl: config.adminUrl.isEmpty ? nil : config.adminUrl,
                modelListUrl: config.modelListUrl.isEmpty ? nil : config.modelListUrl,
                parametersIds: config.parametersIds.isEmpty ? nil : config.parametersIds,
                displayName: service.displayName,
                baseUrl: service.baseUrl,
                apiFormat: service.apiFormat.rawValue,
                chatPath: service.chatPath,
                modelsPath: service.modelsPath,
                openRouterName: service.openRouterName
            )
            return (service.id, export)
        })

        let agents = aiSettings.agents.map { agent in
            AgentExport(
                id: agent.id,
                name: agent.name,
                provider: agent.providerId,
                model: agent.model,
                apiKey: agent.apiKey,
                parametersIds: agent.paramsIds.isEmpty ? nil : agent.paramsIds,
                endpointId: agent.endpointId,
                systemPromptId: agent.systemPromptId
            )
        }

        let flocks = aiSettings.flocks.map { flock in
            FlockExport(
                id: flock.id,
                name: flock.name,
                agentIds: flock.agentIds,
                parametersIds: flock.paramsIds.isEmpty ? nil : flock.paramsIds,
                systemPromptId: flock.systemPromptId
            )
        }

        let swarms = aiSettings.swarms.map { swarm in
            SwarmExport(
                id: swarm.id,
                name: swarm.name,
                members: swarm.members.map { SwarmMemberExport(provider: $0.providerId, model: $0.model) },
                parametersIds: swarm.paramsIds.isEmpty ? nil : swarm.paramsIds,
                systemPromptId: swarm.systemPromptId
            )
        }

        let parameters = aiSettings.parameters.map { p in
            ParametersExport(
                id: p.id,
                name: p.name,
                temperature: p.temperature,
                maxTokens: p.maxTokens,
                topP: p.topP,
                topK: p.topK,
                frequencyPenalty: p.frequencyPenalty,
                presencePenalty: p.presencePenalty,
                systemPrompt: p.systemPrompt,
                stopSequences: p.stopSequences,
                seed: p.seed,
                responseFormatJson: p.responseFormatJson,
                searchEnabled: p.searchEnabled,
                returnCitations: p.returnCitations,
                searchRecency: p.searchRecency
            )
        }

        let sysPrompts = aiSettings.systemPrompts.map { sp in
            SystemPromptExport(id: sp.id, name: sp.name, prompt: sp.prompt)
        }

        let aiPrompts = aiSettings.prompts.map { p in
            PromptExport(id: p.id, name: p.name, agentId: p.agentId, promptText: p.promptText)
        }

        let export = ConfigExport(
            version: currentVersion,
            providers: providers,
            agents: agents,
            flocks: flocks.isEmpty ? nil : flocks,
            swarms: swarms.isEmpty ? nil : swarms,
            parameters: parameters.isEmpty ? nil : parameters,
            systemPrompts: sysPrompts.isEmpty ? nil : sysPrompts,
            huggingFaceApiKey: generalSettings.huggingFaceApiKey.isEmpty ? nil : generalSettings.huggingFaceApiKey,
            aiPrompts: aiPrompts.isEmpty ? nil : aiPrompts,
            openRouterApiKey: generalSettings.openRouterApiKey.isEmpty ? nil : generalSettings.openRouterApiKey,
            providerStates: aiSettings.providerStates.isEmpty ? nil : aiSettings.providerStates
        )

        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(export)

            let panel = NSSavePanel()
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyyMMdd_HHmmss"
            panel.nameFieldStringValue = "ai_config_\(formatter.string(from: Date())).json"
            panel.allowedContentTypes = [.json]
            panel.canCreateDirectories = true

            guard panel.runModal() == .OK, let url = panel.url else { return }
            try data.write(to: url)
            logger.info("Exported configuration to \(url.path)")
        } catch {
            logger.error("Export failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Import

    static func importSettings(completion: @escaping (Settings?, GeneralSettings?) -> Void) {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false

        guard panel.runModal() == .OK, let url = panel.url else {
            completion(nil, nil)
            return
        }

        do {
            let data = try Data(contentsOf: url)
            let decoder = JSONDecoder()
            let export = try decoder.decode(ConfigExport.self, from: data)

            guard (11...currentVersion).contains(export.version) else {
                logger.warning("Unsupported version: \(export.version)")
                completion(nil, nil)
                return
            }

            let result = processImport(export)
            completion(result.settings, result.general)
        } catch {
            logger.error("Import failed: \(error.localizedDescription)")
            completion(nil, nil)
        }
    }

    private static func processImport(_ export: ConfigExport) -> (settings: Settings, general: GeneralSettings?) {
        var settings = Settings()

        // Import agents
        settings.agents = export.agents.compactMap { ae in
            guard AppService.findById(ae.provider) != nil else { return nil }
            return Agent(
                id: ae.id,
                name: ae.name,
                providerId: ae.provider,
                model: ae.model,
                apiKey: ae.apiKey,
                endpointId: ae.endpointId,
                paramsIds: ae.parametersIds ?? [],
                systemPromptId: ae.systemPromptId
            )
        }

        // Import flocks
        settings.flocks = export.flocks?.map { fe in
            Flock(
                id: fe.id,
                name: fe.name,
                agentIds: fe.agentIds,
                paramsIds: fe.parametersIds ?? [],
                systemPromptId: fe.systemPromptId
            )
        } ?? []

        // Import swarms
        settings.swarms = export.swarms?.map { se in
            Swarm(
                id: se.id,
                name: se.name,
                members: se.members.compactMap { me in
                    guard AppService.findById(me.provider) != nil else { return nil }
                    return SwarmMember(providerId: me.provider, model: me.model)
                },
                paramsIds: se.parametersIds ?? [],
                systemPromptId: se.systemPromptId
            )
        } ?? []

        // Import parameters
        settings.parameters = export.parameters?.map { pe in
            Parameters(
                id: pe.id,
                name: pe.name,
                temperature: pe.temperature,
                maxTokens: pe.maxTokens,
                topP: pe.topP,
                topK: pe.topK,
                frequencyPenalty: pe.frequencyPenalty,
                presencePenalty: pe.presencePenalty,
                systemPrompt: pe.systemPrompt,
                stopSequences: pe.stopSequences,
                seed: pe.seed,
                responseFormatJson: pe.responseFormatJson,
                searchEnabled: pe.searchEnabled,
                returnCitations: pe.returnCitations,
                searchRecency: pe.searchRecency
            )
        } ?? []

        // Import system prompts
        settings.systemPrompts = export.systemPrompts?.map { spe in
            SystemPrompt(id: spe.id, name: spe.name, prompt: spe.prompt)
        } ?? []

        // Import prompts
        settings.prompts = export.aiPrompts?.map { pe in
            Prompt(id: pe.id, name: pe.name, agentId: pe.agentId, promptText: pe.promptText)
        } ?? []

        // Import provider states
        if let states = export.providerStates {
            settings.providerStates = states
        }

        // Import provider configs
        for (providerId, pe) in export.providers {
            guard let service = AppService.findById(providerId) else { continue }
            let importedSource = ModelSource(rawValue: pe.modelSource) ?? defaultProviderConfig(service).modelSource
            let config = ProviderConfig(
                apiKey: pe.apiKey,
                model: pe.defaultModel ?? "",
                modelSource: importedSource,
                models: pe.models,
                adminUrl: pe.adminUrl ?? "",
                modelListUrl: pe.modelListUrl ?? "",
                parametersIds: pe.parametersIds ?? []
            )
            settings.setProvider(service, config)
        }

        // General settings
        var general: GeneralSettings?
        if export.huggingFaceApiKey != nil || export.openRouterApiKey != nil {
            general = GeneralSettings(
                huggingFaceApiKey: export.huggingFaceApiKey ?? "",
                openRouterApiKey: export.openRouterApiKey ?? ""
            )
        }

        return (settings, general)
    }

    // MARK: - API Keys Export/Import

    struct ApiKeyEntry: Codable {
        let service: String
        let apiKey: String
    }

    struct ApiKeysExport: Codable {
        var type: String = "api_keys"
        let keys: [ApiKeyEntry]
        var huggingFaceApiKey: String?
        var openRouterApiKey: String?
    }

    static func exportApiKeys(_ aiSettings: Settings, _ generalSettings: GeneralSettings) {
        let keys = AppService.entries.compactMap { service -> ApiKeyEntry? in
            let apiKey = aiSettings.getApiKey(service)
            guard !apiKey.isEmpty else { return nil }
            return ApiKeyEntry(service: service.id, apiKey: apiKey)
        }

        let export = ApiKeysExport(
            keys: keys,
            huggingFaceApiKey: generalSettings.huggingFaceApiKey.isEmpty ? nil : generalSettings.huggingFaceApiKey,
            openRouterApiKey: generalSettings.openRouterApiKey.isEmpty ? nil : generalSettings.openRouterApiKey
        )

        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(export)

            let panel = NSSavePanel()
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyyMMdd_HHmmss"
            panel.nameFieldStringValue = "api_keys_\(formatter.string(from: Date())).json"
            panel.allowedContentTypes = [.json]
            panel.canCreateDirectories = true

            guard panel.runModal() == .OK, let url = panel.url else { return }
            try data.write(to: url)
            logger.info("Exported API keys to \(url.path)")
        } catch {
            logger.error("API keys export failed: \(error.localizedDescription)")
        }
    }

    static func importApiKeys(currentSettings: Settings, completion: @escaping (Settings?, GeneralSettings?) -> Void) {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false

        guard panel.runModal() == .OK, let url = panel.url else {
            completion(nil, nil)
            return
        }

        do {
            let data = try Data(contentsOf: url)
            let decoder = JSONDecoder()
            let export = try decoder.decode(ApiKeysExport.self, from: data)

            guard export.type == "api_keys" else {
                logger.warning("Not an API keys file")
                completion(nil, nil)
                return
            }

            var settings = currentSettings
            for entry in export.keys {
                guard !entry.apiKey.isEmpty, AppService.findById(entry.service) != nil else { continue }
                let service = AppService.findById(entry.service)!
                settings.setApiKey(service, entry.apiKey)
            }

            var general: GeneralSettings?
            if export.huggingFaceApiKey != nil || export.openRouterApiKey != nil {
                general = GeneralSettings(
                    huggingFaceApiKey: export.huggingFaceApiKey ?? "",
                    openRouterApiKey: export.openRouterApiKey ?? ""
                )
            }

            completion(settings, general)
        } catch {
            logger.error("API keys import failed: \(error.localizedDescription)")
            completion(nil, nil)
        }
    }
}
