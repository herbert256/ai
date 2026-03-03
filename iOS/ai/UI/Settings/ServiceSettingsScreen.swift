import SwiftUI

// MARK: - Service Settings Screen

struct ServiceSettingsScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    let service: AppService

    @State private var apiKey = ""
    @State private var defaultModel = ""
    @State private var modelSource: ModelSource = .api
    @State private var models: [String] = []
    @State private var modelListUrl = ""
    @State private var selectedParametersIds: [String] = []
    @State private var endpoints: [Endpoint] = []
    @State private var initialized = false
    @State private var showDeleteConfirm = false

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                // Provider state
                CardView {
                    HStack {
                        Text("Provider State")
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurface)
                        Spacer()
                        ProviderStateIndicator(state: viewModel.uiState.aiSettings.getProviderState(service.id))
                        Text(viewModel.uiState.aiSettings.getProviderState(service.id))
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                    }
                }
                .padding(.horizontal)

                // API Key
                ApiKeyInputSection(
                    apiKey: $apiKey,
                    onTestApiKey: {
                        await viewModel.testAiModel(service: service, apiKey: apiKey, model: defaultModel)
                    }
                )
                .padding(.horizontal)

                // Parameters
                ParametersSelector(
                    aiSettings: viewModel.uiState.aiSettings,
                    selectedIds: $selectedParametersIds
                )
                .padding(.horizontal)

                // Endpoints
                let defaultEndpoints = ServiceSettingsScreen.defaultEndpointsForProvider(serviceId: service.id)
                EndpointsSection(
                    endpoints: $endpoints,
                    defaultEndpointUrl: service.baseUrl
                )
                .padding(.horizontal)

                // Models
                ModelsSection(
                    defaultModel: $defaultModel,
                    modelSource: $modelSource,
                    models: $models,
                    isLoadingModels: viewModel.uiState.loadingModelsFor.contains(service.id),
                    onFetchModels: {
                        viewModel.fetchModels(for: service, apiKey: apiKey)
                    },
                    modelListUrl: $modelListUrl,
                    defaultModelListUrl: viewModel.uiState.aiSettings.getEffectiveModelListUrl(service)
                )
                .padding(.horizontal)

                // Delete provider
                Button(role: .destructive) {
                    showDeleteConfirm = true
                } label: {
                    Text("Delete Provider")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.error)
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle(service.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !initialized else { return }
            let settings = viewModel.uiState.aiSettings
            let config = settings.getProvider(service.id)
            apiKey = config.apiKey
            defaultModel = config.model
            modelSource = config.modelSource
            models = config.models
            modelListUrl = config.modelListUrl
            selectedParametersIds = config.parametersIds
            endpoints = settings.getEndpointsForProvider(service.id).isEmpty
                ? ServiceSettingsScreen.defaultEndpointsForProvider(serviceId: service.id)
                : settings.getEndpointsForProvider(service.id)
            initialized = true
        }
        .onChange(of: apiKey) { saveConfig() }
        .onChange(of: defaultModel) { saveConfig() }
        .onChange(of: modelSource) { saveConfig() }
        .onChange(of: models) { saveConfig() }
        .onChange(of: modelListUrl) { saveConfig() }
        .onChange(of: selectedParametersIds) { saveConfig() }
        .onChange(of: endpoints) { saveConfig() }
        .deleteConfirmation(
            isPresented: $showDeleteConfirm,
            title: "Delete Provider",
            message: "Delete \"\(service.displayName)\"? This will also remove agents using this provider."
        ) {
            Task {
                await ProviderRegistry.shared.remove(service.id)
                viewModel.refreshProviderCache()
            }
        }
    }

    private func saveConfig() {
        guard initialized else { return }
        let config = ProviderConfig(
            apiKey: apiKey,
            model: defaultModel,
            modelSource: modelSource,
            models: models,
            modelListUrl: modelListUrl,
            parametersIds: selectedParametersIds
        )
        viewModel.uiState.aiSettings.withProvider(service.id, config)
        viewModel.uiState.aiSettings.withEndpoints(service.id, endpoints)
        viewModel.saveAiSettings()
    }

    // MARK: - Default Endpoints

    static func defaultEndpointsForProvider(serviceId: String) -> [Endpoint] {
        switch serviceId {
        case "OPENAI":
            return [
                Endpoint(id: "openai-chat-completions", name: "Chat Completions (gpt-4o, gpt-4)", url: "https://api.openai.com/v1/chat/completions", isDefault: true),
                Endpoint(id: "openai-responses", name: "Responses (gpt-5.x, o3, o4)", url: "https://api.openai.com/v1/responses")
            ]
        case "DEEPSEEK":
            return [
                Endpoint(id: "deepseek-chat", name: "Chat Completions", url: "https://api.deepseek.com/chat/completions", isDefault: true),
                Endpoint(id: "deepseek-beta", name: "Beta (FIM)", url: "https://api.deepseek.com/beta/completions")
            ]
        case "MISTRAL":
            return [
                Endpoint(id: "mistral-chat", name: "Chat Completions", url: "https://api.mistral.ai/v1/chat/completions", isDefault: true),
                Endpoint(id: "mistral-codestral", name: "Codestral", url: "https://codestral.mistral.ai/v1/chat/completions")
            ]
        case "SILICONFLOW":
            return [
                Endpoint(id: "siliconflow-chat", name: "Chat (OpenAI)", url: "https://api.siliconflow.com/v1/chat/completions", isDefault: true),
                Endpoint(id: "siliconflow-messages", name: "Messages (Anthropic)", url: "https://api.siliconflow.com/messages")
            ]
        case "ZAI":
            return [
                Endpoint(id: "zai-chat", name: "Chat (General)", url: "https://api.z.ai/api/paas/v4/chat/completions", isDefault: true),
                Endpoint(id: "zai-coding", name: "Coding", url: "https://api.z.ai/api/coding/paas/v4/chat/completions")
            ]
        default:
            return []
        }
    }
}
