import SwiftUI

/// Housekeeping tools - refresh models, test providers, cleanup data.
struct HousekeepingView: View {
    @Bindable var viewModel: AppViewModel
    @State private var refreshProgress = ""
    @State private var isRefreshing = false
    @State private var testResults: [String: String] = [:]
    @State private var isTesting = false
    @State private var cleanupDays = "30"
    @State private var showCleanup = false
    @State private var importStatus = ""
    @State private var importStatusIsError = false

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var generalSettings: GeneralSettings { viewModel.uiState.generalSettings }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Refresh
                SectionHeader(title: "Refresh", icon: "arrow.clockwise")

                HStack(spacing: 12) {
                    Button("Refresh All Models") {
                        refreshAllModels()
                    }
                    .buttonStyle(.bordered)
                    .disabled(isRefreshing)

                    if isRefreshing {
                        ProgressView()
                            .controlSize(.small)
                        Text(refreshProgress)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Divider()

                // Test Providers
                SectionHeader(title: "Test Providers", icon: "antenna.radiowaves.left.and.right")

                Button("Test All Providers") {
                    testAllProviders()
                }
                .buttonStyle(.bordered)
                .disabled(isTesting)

                if isTesting {
                    InlineLoadingView(message: "Testing providers...")
                }

                if !testResults.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(testResults.keys.sorted()), id: \.self) { key in
                            let result = testResults[key] ?? ""
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(result == "OK" ? AppColors.statusOk : AppColors.statusError)
                                    .frame(width: 8, height: 8)
                                Text(key)
                                    .font(.caption)
                                Text(result)
                                    .font(.caption)
                                    .foregroundStyle(result == "OK" ? AppColors.statusOk : AppColors.statusError)
                                    .lineLimit(1)
                            }
                        }
                    }
                    .padding(8)
                    .background(AppColors.cardBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }

                Divider()

                // Generate Default Agents
                SectionHeader(title: "Default Agents", icon: "person.3")

                Button("Generate Default Agents") {
                    generateDefaultAgents()
                }
                .buttonStyle(.bordered)

                Text("Creates one agent per active provider and a 'Default Agents' flock")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Divider()

                // Export
                SectionHeader(title: "Export", icon: "square.and.arrow.up")

                HStack(spacing: 12) {
                    Button("AI Configuration") {
                        SettingsExporter.exportSettings(settings, generalSettings)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!settings.hasAnyApiKey() || settings.agents.isEmpty)

                    Button("API Keys") {
                        SettingsExporter.exportApiKeys(settings, generalSettings)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!settings.hasAnyApiKey())
                }

                Divider()

                // Import
                SectionHeader(title: "Import", icon: "square.and.arrow.down")

                HStack(spacing: 12) {
                    Button("AI Configuration") {
                        importConfiguration()
                    }
                    .buttonStyle(.bordered)

                    Button("API Keys") {
                        importApiKeys()
                    }
                    .buttonStyle(.bordered)
                }

                if !importStatus.isEmpty {
                    Text(importStatus)
                        .font(.caption)
                        .foregroundStyle(importStatusIsError ? AppColors.statusError : AppColors.statusOk)
                }

                Divider()

                // Cleanup
                SectionHeader(title: "Cleanup", icon: "trash")

                HStack {
                    Text("Keep data newer than")
                        .font(.subheadline)
                    TextField("days", text: $cleanupDays)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 60)
                    Text("days")
                        .font(.subheadline)
                }

                HStack(spacing: 12) {
                    Button("Clear Chat History") {
                        Task {
                            await ChatHistoryManager.shared.deleteAll()
                        }
                    }
                    .foregroundStyle(.red)
                    .controlSize(.small)

                    Button("Clear Reports") {
                        Task {
                            await ReportStorage.shared.deleteAll()
                        }
                    }
                    .foregroundStyle(.red)
                    .controlSize(.small)

                    Button("Clear Traces") {
                        Task {
                            await ApiTracer.shared.clearTraces()
                        }
                    }
                    .foregroundStyle(.red)
                    .controlSize(.small)

                    Button("Clear Statistics") {
                        viewModel.uiState.usageStats = [:]
                        SettingsPreferences.saveUsageStats([:])
                    }
                    .foregroundStyle(.red)
                    .controlSize(.small)

                    Button("Clear Prompt History") {
                        SettingsPreferences.savePromptHistory([])
                    }
                    .foregroundStyle(.red)
                    .controlSize(.small)
                }
            }
            .padding()
        }
        .navigationTitle("Housekeeping")
    }

    private func refreshAllModels() {
        isRefreshing = true
        refreshProgress = "Starting..."
        Task {
            await viewModel.refreshAllModelLists(settings, true) { [self] progress in
                refreshProgress = progress
            }
            isRefreshing = false
            refreshProgress = "Done"
        }
    }

    private func testAllProviders() {
        isTesting = true
        testResults = [:]
        Task {
            let services = AppService.entries.filter { !settings.getApiKey($0).isEmpty }
            for service in services {
                let apiKey = settings.getApiKey(service)
                let model = settings.getModel(service)
                let error = await viewModel.testAiModel(service, apiKey, model)
                testResults[service.displayName] = error ?? "OK"
                if error == nil {
                    viewModel.updateProviderState(service, "ok")
                } else {
                    viewModel.updateProviderState(service, "error")
                }
            }
            isTesting = false
        }
    }

    private func importConfiguration() {
        importStatus = ""
        SettingsExporter.importSettings { imported, general in
            guard let imported else {
                importStatus = "Import cancelled or failed"
                importStatusIsError = true
                return
            }
            viewModel.updateSettings(imported)
            if let general { viewModel.updateGeneralSettings(general) }
            let counts = "\(imported.agents.count) agents, \(imported.flocks.count) flocks, \(imported.swarms.count) swarms"
            importStatus = "Imported: \(counts)"
            importStatusIsError = false
        }
    }

    private func importApiKeys() {
        importStatus = ""
        SettingsExporter.importApiKeys(currentSettings: settings) { updated, general in
            guard let updated else {
                importStatus = "Import cancelled or failed"
                importStatusIsError = true
                return
            }
            viewModel.updateSettings(updated)
            if let general { viewModel.updateGeneralSettings(general) }
            let keyCount = AppService.entries.filter { !updated.getApiKey($0).isEmpty }.count
            importStatus = "Imported API keys for \(keyCount) providers"
            importStatusIsError = false
        }
    }

    private func generateDefaultAgents() {
        var s = settings
        let activeServices = settings.getActiveServices()
        var newAgents: [Agent] = []

        for service in activeServices {
            let model = settings.getModel(service)
            guard !model.isEmpty else { continue }

            // Skip if agent already exists for this provider
            let exists = s.agents.contains { $0.providerId == service.id }
            if exists { continue }

            newAgents.append(Agent(
                name: "\(service.displayName) - \(model)",
                providerId: service.id,
                model: model
            ))
        }

        if !newAgents.isEmpty {
            s.agents.append(contentsOf: newAgents)

            // Create or update default flock
            let flockName = "Default Agents"
            let newAgentIds = newAgents.map(\.id)
            if let idx = s.flocks.firstIndex(where: { $0.name == flockName }) {
                s.flocks[idx].agentIds.append(contentsOf: newAgentIds)
            } else {
                s.flocks.append(Flock(name: flockName, agentIds: newAgentIds))
            }

            viewModel.updateSettings(s)
        }
    }
}
