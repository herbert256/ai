import Foundation
import Observation

// MARK: - App View Model

@Observable
@MainActor
final class AppViewModel {

    // MARK: - State

    var uiState = UiState()
    var isBootstrapped = false

    // MARK: - Private

    private let repository = AnalysisRepository.shared
    private let settingsPrefs = SettingsPreferences.shared
    private var reportJob: Task<Void, Never>?

    static let reportConcurrencyLimit = 4
    static let aiReportAgentsKey = "ai_report_agents_v2"
    static let aiReportModelsKey = "ai_report_models_v2"
    static let userTagRegex = try! NSRegularExpression(pattern: "<user>(.*?)</user>", options: .dotMatchesLineSeparators)

    // MARK: - Bootstrap

    func bootstrap() async {
        guard !isBootstrapped else { return }

        // Initialize singletons
        await ProviderRegistry.shared.initialize()
        // Load settings
        let general = settingsPrefs.loadGeneralSettings()
        var settings = await settingsPrefs.loadSettings()

        // First-run setup import
        if !UserDefaults.standard.bool(forKey: "setup_imported") {
            if let result = await importSetupFromBundle(currentSettings: settings) {
                settings = result.aiSettings
                if let hfKey = result.huggingFaceApiKey {
                    var gen = general
                    gen.huggingFaceApiKey = hfKey
                    settingsPrefs.saveGeneralSettings(gen)
                    uiState.generalSettings = gen
                }
            }
            UserDefaults.standard.set(true, forKey: "setup_imported")
        }

        uiState.generalSettings = general
        uiState.aiSettings = settings
        await refreshProviderCacheAsync()
        isBootstrapped = true

        // Refresh model lists in background
        Task { await refreshAllModelLists() }
    }

    // MARK: - Settings Management

    func updateGeneralSettings(_ settings: GeneralSettings) {
        uiState.generalSettings = settings
        Task.detached { [settingsPrefs] in
            settingsPrefs.saveGeneralSettings(settings)
        }
    }

    func updateSettings(_ settings: Settings) {
        uiState.aiSettings = settings
        Task.detached { [settingsPrefs] in
            await settingsPrefs.saveSettings(settings)
        }
    }

    func updateProviderState(_ serviceId: String, state: String) {
        uiState.aiSettings.withProviderState(serviceId, state)
        Task.detached { [settingsPrefs, settings = uiState.aiSettings] in
            await settingsPrefs.saveSettings(settings)
        }
    }

    // MARK: - Report Agent/Model Selection

    func loadReportAgents() -> Set<String> {
        let array = UserDefaults.standard.stringArray(forKey: Self.aiReportAgentsKey) ?? []
        return Set(array)
    }

    func saveReportAgents(_ ids: Set<String>) {
        UserDefaults.standard.set(Array(ids), forKey: Self.aiReportAgentsKey)
    }

    func loadReportModels() -> Set<String> {
        let array = UserDefaults.standard.stringArray(forKey: Self.aiReportModelsKey) ?? []
        return Set(array)
    }

    func saveReportModels(_ ids: Set<String>) {
        UserDefaults.standard.set(Array(ids), forKey: Self.aiReportModelsKey)
    }

    // MARK: - Generic AI Reports

    func showGenericAgentSelection(title: String, prompt: String) {
        uiState.genericPromptTitle = title
        uiState.genericPromptText = prompt
        uiState.showGenericAgentSelection = true
    }

    func dismissGenericAgentSelection() {
        uiState.showGenericAgentSelection = false
    }

    func generateGenericReports(
        selectedAgentIds: Set<String>,
        selectedSwarmIds: Set<String>,
        directModelIds: Set<String>,
        parametersIds: [String],
        selectionParamsById: [String: [String]],
        reportType: ReportType
    ) {
        // Cancel previous
        reportJob?.cancel()
        reportJob = nil

        let settings = uiState.aiSettings
        let prompt = uiState.genericPromptText
        let title = uiState.genericPromptTitle

        // Build report models
        var reportModels: [ReportModel] = []

        // Expand agents
        for agentId in selectedAgentIds {
            if let agent = settings.getAgentById(agentId) {
                let resolvedParams = settings.resolveAgentParameters(agent)
                if let service = lookupService(agent.providerId) {
                    reportModels.append(ReportModel(
                        provider: service,
                        model: settings.getEffectiveModelForAgent(agent),
                        type: "agent",
                        sourceType: "agent",
                        sourceName: agent.name,
                        agentId: agent.id,
                        endpointId: agent.endpointId,
                        agentApiKey: agent.apiKey.isEmpty ? nil : agent.apiKey,
                        paramsIds: agent.paramsIds
                    ))
                }
            }
        }

        // Expand swarms
        for swarmId in selectedSwarmIds {
            if let swarm = settings.getSwarmById(swarmId) {
                for member in swarm.members {
                    if let service = lookupService(member.providerId) {
                        reportModels.append(ReportModel(
                            provider: service,
                            model: member.model,
                            type: "model",
                            sourceType: "swarm",
                            sourceName: swarm.name,
                            paramsIds: swarm.paramsIds
                        ))
                    }
                }
            }
        }

        // Direct models
        for spec in directModelIds {
            let parts = spec.split(separator: "/", maxSplits: 1)
            if parts.count == 2, let service = lookupService(String(parts[0])) {
                reportModels.append(ReportModel(
                    provider: service,
                    model: String(parts[1]),
                    sourceType: "model",
                    sourceName: service.displayName
                ))
            }
        }

        // Deduplicate
        var seen = Set<String>()
        reportModels = reportModels.filter { seen.insert($0.deduplicationKey).inserted }

        guard !reportModels.isEmpty else { return }

        // Extract rapport text
        let rapportText = extractUserTag(from: prompt)

        // Setup UI state
        uiState.showGenericReportsDialog = true
        uiState.showGenericAgentSelection = false
        uiState.genericReportsProgress = 0
        uiState.genericReportsTotal = reportModels.count
        uiState.genericReportsSelectedAgents = Set(reportModels.map(\.deduplicationKey))
        uiState.genericReportsAgentResults = [:]

        // Create report storage entry
        let reportAgents = reportModels.map {
            ReportAgent(agentId: $0.deduplicationKey, agentName: $0.sourceName.isEmpty ? $0.provider.displayName : $0.sourceName,
                        provider: $0.provider.id, model: $0.model)
        }

        reportJob = Task {
            let report = await ReportStorage.shared.createReport(
                title: title, prompt: prompt, agents: reportAgents,
                rapportText: rapportText, reportType: reportType
            )

            uiState.currentReportId = report.id
            await ApiTracer.shared.setReportId(report.id)

            // Execute concurrently with semaphore
            await withTaskGroup(of: Void.self) { group in
                let semaphore = AsyncSemaphore(limit: Self.reportConcurrencyLimit)

                for rm in reportModels {
                    group.addTask { [weak self] in
                        await semaphore.wait()
                        defer { Task { await semaphore.signal() } }
                        await self?.executeReportTask(rm: rm, prompt: prompt, settings: settings,
                                                      reportId: report.id, parametersIds: parametersIds,
                                                      selectionParamsById: selectionParamsById)
                    }
                }
            }

            await ApiTracer.shared.setReportId(nil)
        }
    }

    private func executeReportTask(rm: ReportModel, prompt: String, settings: Settings,
                                   reportId: String, parametersIds: [String],
                                   selectionParamsById: [String: [String]]) async {
        let key = rm.deduplicationKey
        let startTime = Date()

        // Mark running
        await ReportStorage.shared.markAgentRunning(reportId: reportId, agentId: key)

        // Resolve API key
        let apiKey = rm.agentApiKey ?? settings.getApiKey(rm.provider.id)
        guard !apiKey.isEmpty else {
            await ReportStorage.shared.markAgentError(reportId: reportId, agentId: key, errorMessage: "No API key")
            uiState.genericReportsProgress += 1
            uiState.genericReportsAgentResults[key] = AnalysisResponse(service: rm.provider, error: "No API key")
            return
        }

        // Resolve parameters
        var allParamIds = rm.paramsIds
        allParamIds.append(contentsOf: parametersIds)
        if let sel = selectionParamsById[key] { allParamIds.append(contentsOf: sel) }
        let mergedParams = settings.mergeParameters(allParamIds)

        // Merge with report advanced parameters
        let finalParams = repository.mergeParameters(base: mergedParams, override: uiState.reportAdvancedParameters)

        // Resolve endpoint
        let baseUrl = rm.endpointId.flatMap { settings.getEndpointById(rm.provider.id, $0)?.url }
            ?? settings.getEffectiveEndpointUrl(rm.provider)

        // Execute
        let response = await repository.analyze(
            service: rm.provider, apiKey: apiKey, prompt: prompt,
            model: rm.model, params: finalParams, baseUrl: baseUrl
        )

        let durationMs = Int64(Date().timeIntervalSince(startTime) * 1000)

        // Calculate cost
        var cost = response.tokenUsage?.apiCost
        if cost == nil, let usage = response.tokenUsage {
            let pricing = await PricingCache.shared.getPricing(providerId: rm.provider.id, model: rm.model)
            cost = Double(usage.inputTokens) * pricing.promptPrice + Double(usage.outputTokens) * pricing.completionPrice
        }

        // Update storage
        if response.isSuccess {
            await ReportStorage.shared.markAgentSuccess(
                reportId: reportId, agentId: key,
                httpStatus: response.httpStatusCode, responseHeaders: response.httpHeaders,
                responseBody: response.analysis, tokenUsage: response.tokenUsage,
                cost: cost, citations: response.citations, searchResults: response.searchResults,
                relatedQuestions: response.relatedQuestions, durationMs: durationMs
            )
            // Record statistics
            if let usage = response.tokenUsage {
                await settingsPrefs.updateUsageStats(
                    providerId: rm.provider.id, model: rm.model,
                    inputTokens: usage.inputTokens, outputTokens: usage.outputTokens
                )
            }
        } else {
            await ReportStorage.shared.markAgentError(
                reportId: reportId, agentId: key,
                httpStatus: response.httpStatusCode, errorMessage: response.error,
                durationMs: durationMs
            )
        }

        // Update UI
        uiState.genericReportsProgress += 1
        uiState.genericReportsAgentResults[key] = response
    }

    func stopGenericReports() {
        reportJob?.cancel()
        reportJob = nil

        if let reportId = uiState.currentReportId {
            let results = uiState.genericReportsAgentResults
            let agents = uiState.genericReportsSelectedAgents
            Task {
                for key in agents where results[key] == nil {
                    await ReportStorage.shared.markAgentStopped(reportId: reportId, agentId: key)
                }
            }
        }
    }

    func dismissGenericReportsDialog() {
        uiState.showGenericReportsDialog = false
        uiState.genericReportsAgentResults = [:]
        uiState.genericReportsSelectedAgents = []
        uiState.genericReportsProgress = 0
        uiState.genericReportsTotal = 0
        uiState.currentReportId = nil
        uiState.reportAdvancedParameters = nil
    }

    func setReportAdvancedParameters(_ params: AgentParameters?) {
        uiState.reportAdvancedParameters = params
    }

    // MARK: - Model Management

    func fetchModels(service: AppService, apiKey: String) async {
        uiState.loadingModelsFor.insert(service.id)
        defer { uiState.loadingModelsFor.remove(service.id) }

        do {
            let models = try await repository.fetchModels(service: service, apiKey: apiKey)
            uiState.aiSettings.withModels(service.id, models)
            await settingsPrefs.saveSettings(uiState.aiSettings)
        } catch {
            // Silently fail - UI shows error via empty model list
        }
    }

    func refreshAllModelLists(forceRefresh: Bool = false) async {
        let providers = await ProviderRegistry.shared.getAll()
        let settings = uiState.aiSettings

        for service in providers {
            let config = settings.getProvider(service.id)
            guard config.modelSource == .api, !config.apiKey.isEmpty else { continue }

            if !forceRefresh && settingsPrefs.isModelListCacheValid(serviceId: service.id) { continue }

            do {
                let models = try await repository.fetchModels(service: service, apiKey: config.apiKey)
                uiState.aiSettings.withModels(service.id, models)
                settingsPrefs.updateModelListTimestamp(serviceId: service.id)
            } catch {
                // Continue with next provider
            }
        }

        await settingsPrefs.saveSettings(uiState.aiSettings)
    }

    func testAiModel(service: AppService, apiKey: String, model: String) async -> String? {
        return await repository.testModel(service: service, apiKey: apiKey, model: model)
    }

    // MARK: - Chat

    func setChatParameters(_ params: ChatParameters) {
        uiState.chatParameters = params
    }

    func setDualChatConfig(_ config: DualChatConfig?) {
        uiState.dualChatConfig = config
    }

    func sendChatMessage(service: AppService, apiKey: String, model: String, messages: [ChatMessage]) async throws -> ChatMessage {
        let content = try await repository.sendChatMessage(
            service: service, apiKey: apiKey, model: model,
            messages: messages, params: uiState.chatParameters
        )
        return ChatMessage(role: ChatMessage.roleAssistant, content: content)
    }

    func sendChatMessageStream(service: AppService, apiKey: String, model: String,
                                messages: [ChatMessage], baseUrl: String? = nil) -> AsyncThrowingStream<String, Error> {
        repository.sendChatMessageStream(
            service: service, apiKey: apiKey, model: model,
            messages: messages, params: uiState.chatParameters, baseUrl: baseUrl
        )
    }

    func recordChatStatistics(service: AppService, model: String, inputTokens: Int, outputTokens: Int) {
        Task {
            await settingsPrefs.updateUsageStats(
                providerId: service.id, model: model,
                inputTokens: inputTokens, outputTokens: outputTokens
            )
        }
    }

    // MARK: - Save Convenience

    func saveAiSettings() {
        Task.detached { [settingsPrefs, settings = uiState.aiSettings] in
            await settingsPrefs.saveSettings(settings)
        }
    }

    /// Non-async convenience for fetchModels (launches task)
    func fetchModels(for service: AppService, apiKey: String) {
        Task { await fetchModels(service: service, apiKey: apiKey) }
    }

    /// Non-async convenience for refreshProviderCache (launches task)
    func refreshProviderCache() {
        Task { await refreshProviderCacheAsync() }
    }

    // MARK: - Helpers

    func lookupService(_ id: String) -> AppService? {
        // This is synchronous lookup - we need to cache providers
        // For now, use a blocking approach (providers are loaded at bootstrap)
        let task = Task { await ProviderRegistry.shared.findById(id) }
        // Since we're @MainActor, we can't easily await here without restructuring.
        // Workaround: use a cached list
        return _cachedProviders?.first { $0.id == id }
    }

    private var _cachedProviders: [AppService]?

    func refreshProviderCacheAsync() async {
        _cachedProviders = await ProviderRegistry.shared.getAll()
    }

    func getAllProviders() -> [AppService] {
        _cachedProviders ?? []
    }

    private func extractUserTag(from prompt: String) -> String? {
        let range = NSRange(prompt.startIndex..., in: prompt)
        guard let match = Self.userTagRegex.firstMatch(in: prompt, range: range) else { return nil }
        guard let tagRange = Range(match.range(at: 1), in: prompt) else { return nil }
        return String(prompt[tagRange])
    }

    private func importSetupFromBundle(currentSettings: Settings) async -> ConfigImportResult? {
        guard let url = Bundle.main.url(forResource: "setup", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let config = try? JSONDecoder().decode(SetupConfig.self, from: data) else { return nil }

        return await SettingsImporter.processImportedConfig(config: config, currentSettings: currentSettings, silent: true)
    }
}

// MARK: - Async Semaphore (simple implementation)

actor AsyncSemaphore {
    private let limit: Int
    private var count: Int
    private var waiters: [CheckedContinuation<Void, Never>] = []

    init(limit: Int) {
        self.limit = limit
        self.count = limit
    }

    func wait() async {
        if count > 0 {
            count -= 1
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func signal() {
        if waiters.isEmpty {
            count = min(count + 1, limit)
        } else {
            let waiter = waiters.removeFirst()
            waiter.resume()
        }
    }
}
