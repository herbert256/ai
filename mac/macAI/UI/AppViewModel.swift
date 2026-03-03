import SwiftUI
import os

/// Central state management for the app.
/// Uses @Observable (macOS 14+) for automatic SwiftUI updates.
@Observable
final class AppViewModel {
    private static let logger = Logger(subsystem: "com.ai.macAI", category: "AppViewModel")

    // MARK: - Published State

    var uiState = UiState()

    // MARK: - Private

    private let repository = AnalysisRepository.shared
    private var reportGenerationTask: Task<Void, Never>?

    static let reportConcurrencyLimit = 4
    private static let userTagRegex = try! NSRegularExpression(pattern: "<user>(.*?)</user>", options: .dotMatchesLineSeparators)

    // MARK: - Bootstrap

    /// Initialize the app: load provider registry, load settings from UserDefaults.
    func bootstrap() async {
        Self.logger.info("Bootstrapping app...")

        // Initialize actors
        await ProviderRegistry.shared.initialize()
        await ApiTracer.shared.setTracingEnabled(true)

        // Load settings
        uiState.aiSettings = SettingsPreferences.loadSettings()
        uiState.generalSettings = SettingsPreferences.loadGeneralSettings()
        uiState.usageStats = SettingsPreferences.loadUsageStats()

        // First-run setup import
        let setupImported = UserDefaults.standard.bool(forKey: "setup_imported")
        if !setupImported {
            await importSetupIfNeeded()
            UserDefaults.standard.set(true, forKey: "setup_imported")
        }

        Self.logger.info("Bootstrap complete. \(AppService.entries.count) providers loaded.")

        // Refresh model lists in background
        await refreshAllModelLists(uiState.aiSettings, false, { _ in })
    }

    private func importSetupIfNeeded() async {
        // setup.json is loaded by ProviderRegistry during initialize().
        // Agents, flocks, etc. from setup.json are imported here if they
        // come bundled in the ConfigSetup format.
        guard let url = Bundle.main.url(forResource: "setup", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return }
        do {
            let decoder = JSONDecoder()
            let setup = try decoder.decode(FullConfigSetup.self, from: data)
            var settings = uiState.aiSettings

            // Import agents
            if let importedAgents = setup.agents {
                for imported in importedAgents {
                    if !settings.agents.contains(where: { $0.id == imported.id }) {
                        settings.agents.append(imported)
                    }
                }
            }

            // Import flocks
            if let importedFlocks = setup.flocks {
                for imported in importedFlocks {
                    if !settings.flocks.contains(where: { $0.id == imported.id }) {
                        settings.flocks.append(imported)
                    }
                }
            }

            // Import swarms
            if let importedSwarms = setup.swarms {
                for imported in importedSwarms {
                    if !settings.swarms.contains(where: { $0.id == imported.id }) {
                        settings.swarms.append(imported)
                    }
                }
            }

            // Import parameters
            if let importedParams = setup.parameters {
                for imported in importedParams {
                    if !settings.parameters.contains(where: { $0.id == imported.id }) {
                        settings.parameters.append(imported)
                    }
                }
            }

            // Import system prompts
            if let importedPrompts = setup.systemPrompts {
                for imported in importedPrompts {
                    if !settings.systemPrompts.contains(where: { $0.id == imported.id }) {
                        settings.systemPrompts.append(imported)
                    }
                }
            }

            // Import API keys
            if let configs = setup.providers {
                for (id, config) in configs {
                    guard let service = AppService.findById(id) else { continue }
                    var existing = settings.getProvider(service)
                    if existing.apiKey.isEmpty && !config.apiKey.isEmpty {
                        existing.apiKey = config.apiKey
                    }
                    if existing.model.isEmpty && !config.model.isEmpty {
                        existing.model = config.model
                    }
                    settings.setProvider(service, existing)
                }
            }

            uiState.aiSettings = settings
            SettingsPreferences.saveSettings(settings)
        } catch {
            Self.logger.error("Failed to import setup.json: \(error.localizedDescription)")
        }
    }

    // MARK: - Settings Management

    func updateSettings(_ settings: Settings) {
        uiState.aiSettings = settings
        Task.detached { SettingsPreferences.saveSettings(settings) }
    }

    func updateGeneralSettings(_ settings: GeneralSettings) {
        uiState.generalSettings = settings
        Task.detached { SettingsPreferences.saveGeneralSettings(settings) }
    }

    func updateProviderState(_ service: AppService, _ state: String) {
        uiState.aiSettings.setProviderState(service, state)
        let settings = uiState.aiSettings
        Task.detached { SettingsPreferences.saveSettings(settings) }
    }

    // MARK: - Chat Parameters

    func setChatParameters(_ params: ChatParameters) {
        uiState.chatParameters = params
    }

    func setDualChatConfig(_ config: DualChatConfig?) {
        uiState.dualChatConfig = config
    }

    // MARK: - Report Agent/Model Selection

    private static let reportAgentsKey = "ai_report_agents_v2"
    private static let reportModelsKey = "ai_report_models_v2"

    func loadReportAgents() -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: Self.reportAgentsKey) ?? [])
    }

    func saveReportAgents(_ agentIds: Set<String>) {
        UserDefaults.standard.set(Array(agentIds), forKey: Self.reportAgentsKey)
    }

    func loadReportModels() -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: Self.reportModelsKey) ?? [])
    }

    func saveReportModels(_ modelIds: Set<String>) {
        UserDefaults.standard.set(Array(modelIds), forKey: Self.reportModelsKey)
    }

    // MARK: - Report Generation

    func showGenericAgentSelection(_ title: String, _ prompt: String) {
        uiState.genericPromptTitle = title
        uiState.genericPromptText = prompt
        uiState.showGenericAgentSelection = true
        uiState.showGenericReportsDialog = false
        uiState.genericReportsProgress = 0
        uiState.genericReportsTotal = 0
        uiState.genericReportsSelectedAgents = []
        uiState.genericReportsAgentResults = [:]
        uiState.currentReportId = nil
    }

    func dismissGenericAgentSelection() {
        uiState.showGenericAgentSelection = false
    }

    func generateGenericReports(
        selectedAgentIds: Set<String>,
        selectedSwarmIds: Set<String> = [],
        directModelIds: Set<String> = [],
        parametersIds: [String] = [],
        selectionParamsById: [String: [String]] = [:]
    ) {
        reportGenerationTask?.cancel()
        reportGenerationTask = Task { [weak self] in
            guard let self else { return }
            await self.runReportGeneration(
                selectedAgentIds: selectedAgentIds,
                selectedSwarmIds: selectedSwarmIds,
                directModelIds: directModelIds,
                parametersIds: parametersIds,
                selectionParamsById: selectionParamsById
            )
        }
    }

    private func runReportGeneration(
        selectedAgentIds: Set<String>,
        selectedSwarmIds: Set<String>,
        directModelIds: Set<String>,
        parametersIds: [String],
        selectionParamsById: [String: [String]]
    ) async {
        let aiSettings = uiState.aiSettings
        let prompt = uiState.genericPromptText
        let title = uiState.genericPromptTitle
        let mergedParams = aiSettings.mergeParameters(parametersIds)
        let overrideParams = mergedParams ?? uiState.reportAdvancedParameters

        // Build report tasks
        let reportTasks = buildReportTasks(
            aiSettings: aiSettings,
            selectedAgentIds: selectedAgentIds,
            selectedSwarmIds: selectedSwarmIds,
            directModelIds: directModelIds,
            selectionParamsById: selectionParamsById
        )

        // Strip <user>...</user> tag from prompt
        let aiPrompt: String
        let range = NSRange(prompt.startIndex..<prompt.endIndex, in: prompt)
        if let match = Self.userTagRegex.firstMatch(in: prompt, range: range) {
            let matchRange = Range(match.range, in: prompt)!
            aiPrompt = prompt.replacingCharacters(in: matchRange, with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            aiPrompt = prompt
        }

        let allIds = reportTasks.map { $0.resultId }

        // Update UI to show report progress
        uiState.showGenericAgentSelection = false
        uiState.showGenericReportsDialog = true
        uiState.genericReportsProgress = 0
        uiState.genericReportsTotal = reportTasks.count
        uiState.genericReportsSelectedAgents = Set(allIds)
        uiState.genericReportsAgentResults = [:]

        // Create report in storage
        let reportId = UUID().uuidString
        let report = StoredReport(
            id: reportId,
            title: title.isEmpty ? "AI Report" : title,
            prompt: aiPrompt,
            timestamp: Date(),
            results: []
        )
        await ReportStorage.shared.save(report)
        uiState.currentReportId = reportId
        await ApiTracer.shared.setCurrentReportId(reportId)

        // Run tasks with concurrency limit using TaskGroup
        await withTaskGroup(of: Void.self) { group in
            var running = 0
            var taskIndex = 0

            while taskIndex < reportTasks.count {
                if Task.isCancelled { break }

                if running < Self.reportConcurrencyLimit {
                    let task = reportTasks[taskIndex]
                    taskIndex += 1
                    running += 1

                    group.addTask { [weak self] in
                        guard let self else { return }
                        await self.executeReportTask(
                            reportId: reportId,
                            aiPrompt: aiPrompt,
                            overrideParams: overrideParams,
                            task: task
                        )
                    }
                } else {
                    // Wait for one to finish before starting more
                    await group.next()
                    running -= 1
                }
            }

            // Wait for remaining tasks
            for await _ in group {}
        }

        await ApiTracer.shared.setCurrentReportId(nil)
    }

    private struct ReportTask {
        let resultId: String
        let reportAgent: ReportAgent
        let runtimeAgent: Agent
        let resolvedParams: AgentParameters
    }

    private func buildReportTasks(
        aiSettings: Settings,
        selectedAgentIds: Set<String>,
        selectedSwarmIds: Set<String>,
        directModelIds: Set<String>,
        selectionParamsById: [String: [String]]
    ) -> [ReportTask] {
        // Agent tasks
        let agentTasks = selectedAgentIds.compactMap { agentId -> ReportTask? in
            guard let agent = aiSettings.getAgentById(agentId) else { return nil }
            let effectiveAgent = Agent(
                id: agent.id,
                name: agent.name,
                providerId: agent.providerId,
                model: aiSettings.getEffectiveModelForAgent(agent),
                apiKey: aiSettings.getEffectiveApiKeyForAgent(agent),
                endpointId: agent.endpointId,
                paramsIds: agent.paramsIds,
                systemPromptId: agent.systemPromptId
            )

            let selectionParams = aiSettings.mergeParameters(selectionParamsById[agent.id] ?? [])
            let agentParams = selectionParams ?? aiSettings.resolveAgentParameters(agent)

            return ReportTask(
                resultId: agent.id,
                reportAgent: ReportAgent(
                    agentId: agent.id,
                    agentName: agent.name,
                    provider: effectiveAgent.providerId,
                    model: effectiveAgent.model,
                    reportStatus: .pending
                ),
                runtimeAgent: effectiveAgent,
                resolvedParams: agentParams
            )
        }

        // Swarm member tasks
        let swarmMembers = aiSettings.getMembersForSwarms(selectedSwarmIds)
        let modelTasks = swarmMembers.compactMap { member -> ReportTask? in
            guard let provider = member.provider else { return nil }
            let syntheticId = "swarm:\(member.providerId):\(member.model)"
            let modelParams = aiSettings.mergeParameters(selectionParamsById[syntheticId] ?? []) ?? AgentParameters()

            return ReportTask(
                resultId: syntheticId,
                reportAgent: ReportAgent(
                    agentId: syntheticId,
                    agentName: "\(provider.displayName) / \(member.model)",
                    provider: member.providerId,
                    model: member.model,
                    reportStatus: .pending
                ),
                runtimeAgent: Agent(
                    id: syntheticId,
                    name: "\(provider.displayName) / \(member.model)",
                    providerId: member.providerId,
                    model: member.model,
                    apiKey: aiSettings.getApiKey(provider)
                ),
                resolvedParams: modelParams
            )
        }

        // Direct model tasks
        let swarmIds = Set(swarmMembers.map { "swarm:\($0.providerId):\($0.model)" })
        let directTasks = directModelIds.subtracting(swarmIds).compactMap { modelId -> ReportTask? in
            let parts = modelId.replacingOccurrences(of: "swarm:", with: "").split(separator: ":", maxSplits: 1)
            guard parts.count == 2,
                  let provider = AppService.findById(String(parts[0])) else { return nil }
            let model = String(parts[1])
            let modelParams = aiSettings.mergeParameters(selectionParamsById[modelId] ?? []) ?? AgentParameters()

            return ReportTask(
                resultId: modelId,
                reportAgent: ReportAgent(
                    agentId: modelId,
                    agentName: "\(provider.displayName) / \(model)",
                    provider: provider.id,
                    model: model,
                    reportStatus: .pending
                ),
                runtimeAgent: Agent(
                    id: modelId,
                    name: "\(provider.displayName) / \(model)",
                    providerId: provider.id,
                    model: model,
                    apiKey: aiSettings.getApiKey(provider)
                ),
                resolvedParams: modelParams
            )
        }

        return agentTasks + modelTasks + directTasks
    }

    private func executeReportTask(
        reportId: String,
        aiPrompt: String,
        overrideParams: AgentParameters?,
        task: ReportTask
    ) async {
        let startTime = Date()

        // Merge task-level resolved params with override params
        let finalOverride = repository.mergeParameters(task.resolvedParams, overrideParams)

        let response = await repository.analyzeWithAgent(
            agent: task.runtimeAgent,
            content: "",
            prompt: aiPrompt,
            settings: uiState.aiSettings,
            overrideParams: finalOverride
        )

        let durationMs = Int(Date().timeIntervalSince(startTime) * 1000)

        // Calculate cost
        if let provider = task.runtimeAgent.provider {
            let cost = await calculateResponseCost(
                provider: provider,
                model: task.runtimeAgent.model,
                tokenUsage: response.tokenUsage
            )

            // Update usage statistics
            if response.error == nil, let usage = response.tokenUsage {
                await updateUsageStats(
                    provider: provider,
                    model: task.runtimeAgent.model,
                    inputTokens: usage.inputTokens,
                    outputTokens: usage.outputTokens
                )
            }
        }

        // Save result to report
        let result = StoredReport.StoredAnalysisResult(from: response)
        if var report = await ReportStorage.shared.load(reportId) {
            report.results.append(result)
            await ReportStorage.shared.save(report)
        }

        await updateReportResult(reportId: reportId, taskId: task.resultId, response: response)
    }

    @MainActor
    private func updateReportResult(reportId: String, taskId: String, response: AnalysisResponse) {
        uiState.genericReportsProgress += 1
        uiState.genericReportsAgentResults[taskId] = response
    }

    private func calculateResponseCost(
        provider: AppService,
        model: String,
        tokenUsage: TokenUsage?
    ) async -> Double? {
        guard let usage = tokenUsage else { return nil }
        if let apiCost = usage.apiCost { return apiCost }
        let pricing = await PricingCache.shared.getPricing(provider: provider, model: model)
        return Double(usage.inputTokens) * pricing.promptPrice + Double(usage.outputTokens) * pricing.completionPrice
    }

    func stopGenericReports() {
        reportGenerationTask?.cancel()
        reportGenerationTask = nil

        let selected = uiState.genericReportsSelectedAgents
        let current = uiState.genericReportsAgentResults

        // Fill "Not ready" for incomplete agents
        for agentId in selected where current[agentId] == nil {
            let service = AppService.entries.first ?? AppService.findById("OPENAI")!
            uiState.genericReportsAgentResults[agentId] = AnalysisResponse(
                service: service,
                analysis: "Not ready",
                error: nil
            )
        }

        uiState.genericReportsProgress = uiState.genericReportsTotal
        Task { await ApiTracer.shared.setCurrentReportId(nil) }
    }

    func dismissGenericReportsDialog() {
        Task { await ApiTracer.shared.setCurrentReportId(nil) }
        uiState.showGenericReportsDialog = false
        uiState.genericPromptTitle = ""
        uiState.genericPromptText = ""
        uiState.genericReportsProgress = 0
        uiState.genericReportsTotal = 0
        uiState.genericReportsSelectedAgents = []
        uiState.genericReportsAgentResults = [:]
        uiState.currentReportId = nil
        uiState.reportAdvancedParameters = nil
    }

    func setReportAdvancedParameters(_ params: AgentParameters?) {
        uiState.reportAdvancedParameters = params
    }

    // MARK: - Model Fetching

    func fetchModels(_ service: AppService) async {
        let apiKey = uiState.aiSettings.getApiKey(service)
        guard !apiKey.isEmpty else { return }

        uiState.loadingModelsFor.insert(service.id)
        defer { uiState.loadingModelsFor.remove(service.id) }

        let models = await repository.fetchModels(service: service, apiKey: apiKey)
        if !models.isEmpty {
            uiState.aiSettings = uiState.aiSettings.withModels(service, models)
            let settings = uiState.aiSettings
            Task.detached { SettingsPreferences.saveSettings(settings) }
        }
    }

    /// Refresh model lists for all providers with API as model source.
    func refreshAllModelLists(_ settings: Settings, _ forceRefresh: Bool, _ onProgress: @escaping (String) -> Void) async -> [String: Int] {
        let modelCacheKey = "model_list_timestamps"
        let cacheDuration: TimeInterval = 24 * 60 * 60  // 24 hours
        let timestamps = UserDefaults.standard.dictionary(forKey: modelCacheKey) as? [String: Double] ?? [:]

        let servicesToRefresh = AppService.entries.filter { service in
            settings.getModelSource(service) == .api &&
            !settings.getApiKey(service).isEmpty &&
            (forceRefresh || {
                guard let ts = timestamps[service.id] else { return true }
                return Date().timeIntervalSince1970 - ts > cacheDuration
            }())
        }

        guard !servicesToRefresh.isEmpty else { return [:] }

        var results: [String: Int] = [:]

        await withTaskGroup(of: (AppService, Int).self) { group in
            for service in servicesToRefresh {
                group.addTask { [self] in
                    onProgress(service.displayName)
                    let models = await self.repository.fetchModels(
                        service: service,
                        apiKey: settings.getApiKey(service)
                    )
                    return (service, models.count)
                }
            }

            for await (service, count) in group {
                results[service.displayName] = count
                if count > 0 {
                    uiState.aiSettings = uiState.aiSettings.withModels(service, await {
                        // Re-fetch to get the actual model list (already fetched above)
                        return uiState.aiSettings.getModels(service)
                    }())
                }
            }
        }

        // Update cache timestamps
        var updatedTimestamps = timestamps
        let now = Date().timeIntervalSince1970
        for (name, count) in results where count > 0 {
            if let service = AppService.entries.first(where: { $0.displayName == name }) {
                updatedTimestamps[service.id] = now
            }
        }
        UserDefaults.standard.set(updatedTimestamps, forKey: modelCacheKey)

        // Save settings
        let updated = uiState.aiSettings
        Task.detached { SettingsPreferences.saveSettings(updated) }

        return results
    }

    func clearModelListsCache() {
        UserDefaults.standard.removeObject(forKey: "model_list_timestamps")
    }

    // MARK: - Model Testing

    func testAiModel(_ service: AppService, _ apiKey: String, _ model: String) async -> String? {
        let result = await repository.testModel(service: service, apiKey: apiKey, model: model)
        if result == nil {
            // Successful test - record minimal usage
            await updateUsageStats(provider: service, model: model, inputTokens: 10, outputTokens: 2)
        }
        return result
    }

    func testModelWithPrompt(_ service: AppService, _ apiKey: String, _ model: String, _ prompt: String) async -> (Bool, String?) {
        let traceCountBefore = await ApiTracer.shared.getTraceCount()
        let (responseText, _) = await repository.testModelWithPrompt(service: service, apiKey: apiKey, model: model, prompt: prompt)
        let traceCount = await ApiTracer.shared.getTraceCount()
        let traceFile = traceCount > traceCountBefore
            ? await ApiTracer.shared.getTraceFiles().first?.filename
            : nil
        let success = responseText != nil && !(responseText?.isEmpty ?? true)
        return (success, traceFile)
    }

    // MARK: - Chat Operations

    func sendChatMessage(_ provider: AppService, _ apiKey: String, _ model: String, _ messages: [ChatMessage]) async -> ChatMessage {
        do {
            let response = try await repository.sendChatMessage(
                service: provider,
                apiKey: apiKey,
                model: model,
                messages: messages,
                params: uiState.chatParameters
            )

            let inputTokens = messages.reduce(0) { $0 + Self.estimateTokens($1.content) }
            let outputTokens = Self.estimateTokens(response)
            await updateUsageStats(provider: provider, model: model, inputTokens: inputTokens, outputTokens: outputTokens)

            return ChatMessage(role: "assistant", content: response)
        } catch {
            return ChatMessage(role: "assistant", content: "Error: \(error.localizedDescription)")
        }
    }

    func sendDualChatMessage(
        service: AppService,
        apiKey: String,
        model: String,
        messages: [ChatMessage],
        params: ChatParameters
    ) async throws -> String {
        let response = try await repository.sendChatMessage(
            service: service,
            apiKey: apiKey,
            model: model,
            messages: messages,
            params: params
        )

        let inputTokens = messages.reduce(0) { $0 + Self.estimateTokens($1.content) }
        let outputTokens = Self.estimateTokens(response)
        await updateUsageStats(provider: service, model: model, inputTokens: inputTokens, outputTokens: outputTokens)

        return response
    }

    func sendChatMessageStream(_ provider: AppService, _ apiKey: String, _ model: String, _ messages: [ChatMessage], _ customBaseUrl: String? = nil) -> AsyncThrowingStream<String, Error> {
        repository.sendChatMessageStream(
            service: provider,
            apiKey: apiKey,
            model: model,
            messages: messages,
            params: uiState.chatParameters,
            customBaseUrl: customBaseUrl
        )
    }

    func recordChatStatistics(_ provider: AppService, _ model: String, _ inputTokens: Int, _ outputTokens: Int) {
        Task {
            await updateUsageStats(provider: provider, model: model, inputTokens: inputTokens, outputTokens: outputTokens)
        }
    }

    // MARK: - Usage Statistics

    private func updateUsageStats(provider: AppService, model: String, inputTokens: Int, outputTokens: Int) async {
        let key = "\(provider.id)::\(model)"
        var stats = uiState.usageStats[key] ?? UsageStats(providerId: provider.id, model: model)
        stats.callCount += 1
        stats.inputTokens += Int64(inputTokens)
        stats.outputTokens += Int64(outputTokens)
        uiState.usageStats[key] = stats

        let allStats = uiState.usageStats
        Task.detached { SettingsPreferences.saveUsageStats(allStats) }
    }

    // MARK: - Trace Operations

    func clearTraces() {
        Task { await ApiTracer.shared.clearTraces() }
    }

    // MARK: - Token Estimation

    static func estimateTokens(_ text: String) -> Int {
        max(text.count / 4, 1)
    }
}

// MARK: - Full config setup for setup.json import (agents, flocks, etc.)

struct FullConfigSetup: Codable {
    let version: Int?
    let providers: [String: ProviderConfig]?
    let agents: [Agent]?
    let flocks: [Flock]?
    let swarms: [Swarm]?
    let parameters: [Parameters]?
    let systemPrompts: [SystemPrompt]?
    let prompts: [Prompt]?
    let endpoints: [String: [Endpoint]]?
}
