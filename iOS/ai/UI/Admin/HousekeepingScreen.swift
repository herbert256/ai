import SwiftUI
import UniformTypeIdentifiers

// MARK: - Housekeeping Screen

struct HousekeepingScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    @State private var showProgress = false
    @State private var progressTitle = ""
    @State private var progressText = ""
    @State private var showCleanConfirm = false
    @State private var showImportPicker = false
    @State private var showResultAlert = false
    @State private var resultMessage = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Refresh section
                CardView {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Refresh")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(AppColors.onSurface)

                        Text("Update model lists and provider states")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)

                        HStack(spacing: 8) {
                            Button("Refresh All") {
                                refreshAll()
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(AppColors.success)
                            .font(.caption)

                            Button("Provider State") {
                                refreshProviderState()
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)

                            Button("Model Lists") {
                                refreshModelLists()
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)
                        }
                    }
                }
                .padding(.horizontal)

                // Export section
                CardView {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Export")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(AppColors.onSurface)

                        HStack(spacing: 8) {
                            Button("AI Configuration") {
                                exportConfiguration()
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)

                            Button("API Keys") {
                                exportApiKeys()
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)
                        }
                    }
                }
                .padding(.horizontal)

                // Import section
                CardView {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Import")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(AppColors.onSurface)

                        Button("AI Configuration") {
                            showImportPicker = true
                        }
                        .buttonStyle(.bordered)
                        .font(.caption)
                    }
                }
                .padding(.horizontal)

                // Clean up section
                CardView {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Clean Up")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(AppColors.onSurface)

                        HStack(spacing: 8) {
                            Button("Clean All") {
                                showCleanConfirm = true
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(AppColors.error)
                            .font(.caption)

                            Button("Clear Chats") {
                                Task {
                                    await ChatHistoryManager.shared.clearHistory()
                                    resultMessage = "Chat history cleared"
                                    showResultAlert = true
                                }
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)

                            Button("Clear Reports") {
                                Task {
                                    await ReportStorage.shared.deleteAllReports()
                                    resultMessage = "Reports cleared"
                                    showResultAlert = true
                                }
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)
                        }

                        HStack(spacing: 8) {
                            Button("Clear Statistics") {
                                SettingsPreferences.shared.clearUsageStats()
                                resultMessage = "Statistics cleared"
                                showResultAlert = true
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)

                            Button("Clear Traces") {
                                Task {
                                    await ApiTracer.shared.clearTraces()
                                    resultMessage = "API traces cleared"
                                    showResultAlert = true
                                }
                            }
                            .buttonStyle(.bordered)
                            .font(.caption)
                        }
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("Housekeeping")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if showProgress {
                LoadingOverlay(message: "\(progressTitle)\n\(progressText)")
            }
        }
        .alert("Result", isPresented: $showResultAlert) {
            Button("OK") {}
        } message: {
            Text(resultMessage)
        }
        .deleteConfirmation(
            isPresented: $showCleanConfirm,
            title: "Clean All",
            message: "This will delete all chats, reports, statistics, and traces. This cannot be undone."
        ) {
            Task {
                await ChatHistoryManager.shared.clearHistory()
                await ReportStorage.shared.deleteAllReports()
                SettingsPreferences.shared.clearUsageStats()
                SettingsPreferences.shared.clearPromptHistory()
                await ApiTracer.shared.clearTraces()
                resultMessage = "All data cleared"
                showResultAlert = true
            }
        }
        .fileImporter(isPresented: $showImportPicker, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url):
                importConfiguration(from: url)
            case .failure:
                resultMessage = "Failed to select file"
                showResultAlert = true
            }
        }
    }

    // MARK: - Refresh Actions

    private func refreshAll() {
        showProgress = true
        progressTitle = "Refreshing All"
        Task {
            progressText = "Provider state..."
            await refreshProviderStateAsync()
            progressText = "Model lists..."
            await refreshModelListsAsync()
            showProgress = false
            resultMessage = "All refreshed"
            showResultAlert = true
        }
    }

    private func refreshProviderState() {
        showProgress = true
        progressTitle = "Provider State"
        Task {
            await refreshProviderStateAsync()
            showProgress = false
        }
    }

    private func refreshProviderStateAsync() async {
        let settings = viewModel.uiState.aiSettings
        let providers = viewModel.getAllProviders()
        for service in providers {
            if settings.getProviderState(service.id) == "inactive" { continue }
            progressText = service.displayName
            let apiKey = settings.getApiKey(service.id)
            if apiKey.isEmpty {
                viewModel.uiState.aiSettings.withProviderState(service.id, "not-used")
            } else {
                let model = settings.getModel(service.id)
                let error = await viewModel.testAiModel(service: service, apiKey: apiKey, model: model)
                viewModel.uiState.aiSettings.withProviderState(service.id, error == nil ? "ok" : "error")
            }
        }
        viewModel.saveAiSettings()
    }

    private func refreshModelLists() {
        showProgress = true
        progressTitle = "Model Lists"
        Task {
            await refreshModelListsAsync()
            showProgress = false
        }
    }

    private func refreshModelListsAsync() async {
        let settings = viewModel.uiState.aiSettings
        let providers = viewModel.getAllProviders()
        for service in providers {
            let apiKey = settings.getApiKey(service.id)
            guard !apiKey.isEmpty else { continue }
            progressText = service.displayName
            viewModel.fetchModels(for: service, apiKey: apiKey)
        }
    }

    // MARK: - Export Actions

    private func exportConfiguration() {
        Task {
            let config = await SettingsImporter.exportConfig(
                settings: viewModel.uiState.aiSettings,
                generalSettings: viewModel.uiState.generalSettings
            )
            guard let data = try? JSONEncoder().encode(config) else { return }
            let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("ai-config.json")
            try? data.write(to: tempUrl)
            shareFile(tempUrl)
        }
    }

    private func exportApiKeys() {
        var keys: [String: String] = [:]
        let settings = viewModel.uiState.aiSettings
        for (serviceId, config) in settings.providers where !config.apiKey.isEmpty {
            keys[serviceId] = config.apiKey
        }
        let gs = viewModel.uiState.generalSettings
        if !gs.huggingFaceApiKey.isEmpty { keys["huggingface"] = gs.huggingFaceApiKey }
        if !gs.openRouterApiKey.isEmpty { keys["openrouter"] = gs.openRouterApiKey }

        guard let data = try? JSONEncoder().encode(keys) else { return }
        let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("ai-keys.json")
        try? data.write(to: tempUrl)
        shareFile(tempUrl)
    }

    // MARK: - Import Actions

    private func importConfiguration(from url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            resultMessage = "Cannot access file"
            showResultAlert = true
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }

        guard let data = try? Data(contentsOf: url) else {
            resultMessage = "Cannot read file"
            showResultAlert = true
            return
        }

        Task {
            if let importResult = await SettingsImporter.importFromFile(data: data, currentSettings: viewModel.uiState.aiSettings) {
                viewModel.uiState.aiSettings = importResult.aiSettings
                if let hf = importResult.huggingFaceApiKey {
                    viewModel.uiState.generalSettings.huggingFaceApiKey = hf
                }
                if let or = importResult.openRouterApiKey {
                    viewModel.uiState.generalSettings.openRouterApiKey = or
                }
                viewModel.saveAiSettings()
                SettingsPreferences.shared.saveGeneralSettings(viewModel.uiState.generalSettings)
                resultMessage = "Configuration imported successfully"
                showResultAlert = true
            } else {
                resultMessage = "Import failed: Invalid or unsupported file"
                showResultAlert = true
            }
        }
    }

    private func shareFile(_ url: URL) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = scene.windows.first else { return }
        let activityVC = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        window.rootViewController?.present(activityVC, animated: true)
    }
}
