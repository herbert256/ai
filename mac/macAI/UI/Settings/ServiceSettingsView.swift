import SwiftUI

/// Per-provider settings - API key, model, endpoints, state.
struct ServiceSettingsView: View {
    @Bindable var viewModel: AppViewModel
    let service: AppService

    @State private var apiKey = ""
    @State private var model = ""
    @State private var modelSource: ModelSource = .api
    @State private var adminUrl = ""
    @State private var modelListUrl = ""
    @State private var testResult: String?
    @State private var isTesting = false
    @State private var isFetchingModels = false

    private var settings: Settings { viewModel.uiState.aiSettings }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // State toggle
                HStack {
                    Text("Status")
                        .font(.subheadline.bold())
                    Spacer()
                    ProviderStateBadge(state: settings.getProviderState(service))
                    Text(settings.getProviderState(service))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button(settings.getProviderState(service) == "inactive" ? "Activate" : "Deactivate") {
                        let newState = settings.getProviderState(service) == "inactive" ? "ok" : "inactive"
                        viewModel.updateProviderState(service, newState)
                    }
                    .controlSize(.small)
                }

                Divider()

                // API Key
                SectionHeader(title: "Authentication", icon: "key")
                AppTextField(label: "API Key", text: $apiKey, isSecure: true)

                // Model
                SectionHeader(title: "Model", icon: "cpu")

                Picker("Model Source", selection: $modelSource) {
                    Text("API").tag(ModelSource.api)
                    Text("Manual").tag(ModelSource.manual)
                }
                .pickerStyle(.segmented)

                HStack {
                    TextField("Model name", text: $model)
                        .textFieldStyle(.roundedBorder)

                    if isFetchingModels {
                        ProgressView().controlSize(.small)
                    } else {
                        Button("Fetch") {
                            fetchModels()
                        }
                        .disabled(apiKey.isEmpty)
                    }
                }

                // Available models
                let models = settings.getModels(service)
                if !models.isEmpty {
                    DisclosureGroup("Available Models (\(models.count))") {
                        ForEach(models, id: \.self) { m in
                            Button {
                                model = m
                            } label: {
                                HStack {
                                    Text(m)
                                        .font(.caption)
                                    Spacer()
                                    if m == model {
                                        Image(systemName: "checkmark")
                                            .font(.caption)
                                            .foregroundStyle(AppColors.primary)
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Divider()

                // Test connection
                SectionHeader(title: "Test", icon: "antenna.radiowaves.left.and.right")

                HStack {
                    Button("Test Connection") {
                        testConnection()
                    }
                    .buttonStyle(.bordered)
                    .disabled(apiKey.isEmpty || model.isEmpty || isTesting)

                    if isTesting {
                        ProgressView().controlSize(.small)
                    }

                    if let result = testResult {
                        Text(result)
                            .font(.caption)
                            .foregroundStyle(result == "OK" ? AppColors.statusOk : AppColors.statusError)
                    }
                }

                Divider()

                // Advanced
                SectionHeader(title: "Advanced", icon: "gearshape.2")
                AppTextField(label: "Admin URL", text: $adminUrl)
                AppTextField(label: "Model List URL", text: $modelListUrl)
            }
            .padding()
        }
        .navigationTitle(service.displayName)
        .onAppear { loadFields() }
        .onDisappear { saveFields() }
    }

    private func loadFields() {
        let config = settings.getProvider(service)
        apiKey = config.apiKey
        model = config.model
        modelSource = config.modelSource
        adminUrl = config.adminUrl
        modelListUrl = config.modelListUrl
    }

    private func saveFields() {
        var config = settings.getProvider(service)
        config.apiKey = apiKey
        config.model = model
        config.modelSource = modelSource
        config.adminUrl = adminUrl
        config.modelListUrl = modelListUrl

        var updated = settings
        updated.setProvider(service, config)
        viewModel.updateSettings(updated)
    }

    private func testConnection() {
        isTesting = true
        testResult = nil
        Task {
            let error = await viewModel.testAiModel(service, apiKey, model)
            isTesting = false
            testResult = error ?? "OK"
            if error == nil {
                viewModel.updateProviderState(service, "ok")
            } else {
                viewModel.updateProviderState(service, "error")
            }
        }
    }

    private func fetchModels() {
        isFetchingModels = true
        saveFields()
        Task {
            await viewModel.fetchModels(service)
            isFetchingModels = false
        }
    }
}
