import Foundation

// MARK: - Config Import Result

struct ConfigImportResult: Sendable {
    let aiSettings: Settings
    let huggingFaceApiKey: String?
    let openRouterApiKey: String?
}

// MARK: - Settings Importer

struct SettingsImporter {

    static func processImportedConfig(config: SetupConfig, currentSettings: Settings, silent: Bool) async -> ConfigImportResult {
        var settings = currentSettings

        // 1. Register provider definitions
        if let defs = config.providerDefinitions {
            await ProviderRegistry.shared.ensureProviders(defs)
        }

        // 2. Import agents
        if let agents = config.agents {
            let providers = await ProviderRegistry.shared.getAll()
            let providerIds = Set(providers.map(\.id))
            let validAgents = agents.filter { providerIds.contains($0.provider) }.map {
                Agent(id: $0.id, name: $0.name, providerId: $0.provider, model: $0.model,
                      apiKey: $0.apiKey, endpointId: $0.endpointId,
                      paramsIds: $0.parametersIds ?? [], systemPromptId: $0.systemPromptId)
            }
            // Merge: add new, update existing
            for agent in validAgents {
                if let idx = settings.agents.firstIndex(where: { $0.id == agent.id }) {
                    settings.agents[idx] = agent
                } else {
                    settings.agents.append(agent)
                }
            }
        }

        // 3. Import flocks
        if let flocks = config.flocks {
            let imported = flocks.map {
                Flock(id: $0.id, name: $0.name, agentIds: $0.agentIds,
                      paramsIds: $0.parametersIds ?? [], systemPromptId: $0.systemPromptId)
            }
            for flock in imported {
                if let idx = settings.flocks.firstIndex(where: { $0.id == flock.id }) {
                    settings.flocks[idx] = flock
                } else {
                    settings.flocks.append(flock)
                }
            }
        }

        // 4. Import swarms
        if let swarms = config.swarms {
            let providers = await ProviderRegistry.shared.getAll()
            let providerIds = Set(providers.map(\.id))
            let imported = swarms.map {
                Swarm(id: $0.id, name: $0.name,
                      members: $0.members.filter { providerIds.contains($0.provider) }
                          .map { SwarmMember(providerId: $0.provider, model: $0.model) },
                      paramsIds: $0.parametersIds ?? [], systemPromptId: $0.systemPromptId)
            }
            for swarm in imported {
                if let idx = settings.swarms.firstIndex(where: { $0.id == swarm.id }) {
                    settings.swarms[idx] = swarm
                } else {
                    settings.swarms.append(swarm)
                }
            }
        }

        // 5. Import prompts
        if let prompts = config.aiPrompts {
            let imported = prompts.map {
                Prompt(id: $0.id, name: $0.name, agentId: $0.agentId, promptText: $0.promptText)
            }
            for prompt in imported {
                if let idx = settings.prompts.firstIndex(where: { $0.id == prompt.id }) {
                    settings.prompts[idx] = prompt
                } else {
                    settings.prompts.append(prompt)
                }
            }
        }

        // 6. Import parameters
        if let params = config.parameters {
            let imported = params.map {
                Parameters(id: $0.id, name: $0.name, temperature: $0.temperature,
                           maxTokens: $0.maxTokens, topP: $0.topP, topK: $0.topK,
                           frequencyPenalty: $0.frequencyPenalty, presencePenalty: $0.presencePenalty,
                           systemPrompt: $0.systemPrompt, stopSequences: $0.stopSequences,
                           seed: $0.seed, responseFormatJson: $0.responseFormatJson ?? false,
                           searchEnabled: $0.searchEnabled ?? false,
                           returnCitations: $0.returnCitations ?? true,
                           searchRecency: $0.searchRecency)
            }
            for p in imported {
                if let idx = settings.parameters.firstIndex(where: { $0.id == p.id }) {
                    settings.parameters[idx] = p
                } else {
                    settings.parameters.append(p)
                }
            }
        }

        // 7. Import system prompts
        if let sps = config.systemPrompts {
            let imported = sps.map {
                SystemPrompt(id: $0.id, name: $0.name, prompt: $0.prompt)
            }
            for sp in imported {
                if let idx = settings.systemPrompts.firstIndex(where: { $0.id == sp.id }) {
                    settings.systemPrompts[idx] = sp
                } else {
                    settings.systemPrompts.append(sp)
                }
            }
        }

        // 8. Import provider configs (API keys, models, etc.)
        if let providerConfigs = config.providers {
            for (key, export) in providerConfigs {
                var config = settings.getProvider(key)
                if let apiKey = export.apiKey, !apiKey.isEmpty { config.apiKey = apiKey }
                if let source = export.modelSource { config.modelSource = ModelSource(rawValue: source) ?? .api }
                if let models = export.models, !models.isEmpty { config.models = models }
                if let url = export.modelListUrl { config.modelListUrl = url }
                if let ids = export.parametersIds { config.parametersIds = ids }
                settings.providers[key] = config
            }
        }

        // 9. Import manual pricing
        if let pricing = config.manualPricing {
            var manualMap: [String: ModelPricing] = [:]
            for p in pricing {
                manualMap[p.key] = ModelPricing(modelId: p.key, promptPrice: p.promptPrice,
                                                completionPrice: p.completionPrice, source: ModelPricing.sourceOverride)
            }
            await PricingCache.shared.setAllManualPricing(manualMap)
        }

        // 10. Import endpoints
        if let epList = config.providerEndpoints {
            for pe in epList {
                let eps = pe.endpoints.map {
                    Endpoint(id: $0.id, name: $0.name, url: $0.url, isDefault: $0.isDefault ?? false)
                }
                settings.endpoints[pe.provider] = eps
            }
        }

        // 11. Import provider states
        if let states = config.providerStates {
            for (key, value) in states {
                settings.providerStates[key] = value
            }
        }

        return ConfigImportResult(
            aiSettings: settings,
            huggingFaceApiKey: config.huggingFaceApiKey,
            openRouterApiKey: config.openRouterApiKey
        )
    }

    // MARK: - Export

    static func exportConfig(settings: Settings, generalSettings: GeneralSettings) async -> SetupConfig {
        let providers = await ProviderRegistry.shared.getAll()

        // Build provider configs export
        var providerExports: [String: ProviderConfigExport] = [:]
        for service in providers {
            let config = settings.getProvider(service.id)
            providerExports[service.id] = ProviderConfigExport(
                modelSource: config.modelSource.rawValue,
                models: config.models,
                apiKey: config.apiKey,
                modelListUrl: config.modelListUrl.isEmpty ? nil : config.modelListUrl,
                parametersIds: config.parametersIds.isEmpty ? nil : config.parametersIds
            )
        }

        // Build exports
        let agentExports = settings.agents.map {
            AgentExport(id: $0.id, name: $0.name, provider: $0.providerId, model: $0.model,
                        apiKey: $0.apiKey, parametersIds: $0.paramsIds.isEmpty ? nil : $0.paramsIds,
                        endpointId: $0.endpointId, systemPromptId: $0.systemPromptId)
        }

        let flockExports = settings.flocks.map {
            FlockExport(id: $0.id, name: $0.name, agentIds: $0.agentIds,
                        parametersIds: $0.paramsIds.isEmpty ? nil : $0.paramsIds,
                        systemPromptId: $0.systemPromptId)
        }

        let swarmExports = settings.swarms.map {
            SwarmExport(id: $0.id, name: $0.name,
                        members: $0.members.map { SwarmMemberExport(provider: $0.providerId, model: $0.model) },
                        parametersIds: $0.paramsIds.isEmpty ? nil : $0.paramsIds,
                        systemPromptId: $0.systemPromptId)
        }

        let paramExports = settings.parameters.map {
            ParametersExport(id: $0.id, name: $0.name, temperature: $0.temperature,
                             maxTokens: $0.maxTokens, topP: $0.topP, topK: $0.topK,
                             frequencyPenalty: $0.frequencyPenalty, presencePenalty: $0.presencePenalty,
                             systemPrompt: $0.systemPrompt, stopSequences: $0.stopSequences,
                             seed: $0.seed, responseFormatJson: $0.responseFormatJson,
                             searchEnabled: $0.searchEnabled, returnCitations: $0.returnCitations,
                             searchRecency: $0.searchRecency)
        }

        let spExports = settings.systemPrompts.map {
            SystemPromptExport(id: $0.id, name: $0.name, prompt: $0.prompt)
        }

        let promptExports = settings.prompts.map {
            PromptExport(id: $0.id, name: $0.name, agentId: $0.agentId, promptText: $0.promptText)
        }

        // Manual pricing
        let manualPricing = await PricingCache.shared.getAllManualPricing()
        let pricingExports = manualPricing.map {
            ManualPricingExport(key: $0.key, promptPrice: $0.value.promptPrice, completionPrice: $0.value.completionPrice)
        }

        // Endpoints
        let epExports = settings.endpoints.compactMap { (key, eps) -> ProviderEndpointsExport? in
            guard !eps.isEmpty else { return nil }
            return ProviderEndpointsExport(
                provider: key,
                endpoints: eps.map { EndpointExport(id: $0.id, name: $0.name, url: $0.url, isDefault: $0.isDefault) }
            )
        }

        // Provider definitions
        let provDefs = providers.map { ProviderDefinition.fromAppService($0) }

        return SetupConfig(
            version: 21,
            providers: providerExports,
            agents: agentExports,
            providerDefinitions: provDefs,
            flocks: flockExports.isEmpty ? nil : flockExports,
            swarms: swarmExports.isEmpty ? nil : swarmExports,
            parameters: paramExports.isEmpty ? nil : paramExports,
            systemPrompts: spExports.isEmpty ? nil : spExports,
            aiPrompts: promptExports.isEmpty ? nil : promptExports,
            manualPricing: pricingExports.isEmpty ? nil : pricingExports,
            providerEndpoints: epExports.isEmpty ? nil : epExports,
            huggingFaceApiKey: generalSettings.huggingFaceApiKey.isEmpty ? nil : generalSettings.huggingFaceApiKey,
            openRouterApiKey: generalSettings.openRouterApiKey.isEmpty ? nil : generalSettings.openRouterApiKey,
            providerStates: settings.providerStates.isEmpty ? nil : settings.providerStates
        )
    }

    static func importFromFile(data: Data, currentSettings: Settings) async -> ConfigImportResult? {
        guard let config = try? JSONDecoder().decode(SetupConfig.self, from: data) else { return nil }
        guard let version = config.version, version >= 11, version <= 21 else { return nil }
        return await processImportedConfig(config: config, currentSettings: currentSettings, silent: false)
    }
}
