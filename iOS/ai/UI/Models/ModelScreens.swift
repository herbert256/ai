import SwiftUI

// MARK: - Model Search Screen

struct ModelSearchScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @State private var searchText = ""

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let providers = viewModel.getAllProviders()

        List {
            ForEach(providers, id: \.id) { service in
                let models = settings.getModels(service.id).filter {
                    searchText.isEmpty || $0.localizedCaseInsensitiveContains(searchText)
                }

                if !models.isEmpty {
                    Section(service.displayName) {
                        ForEach(models, id: \.self) { model in
                            NavigationLink(destination: ModelInfoScreen(service: service, model: model)) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(model)
                                        .foregroundStyle(AppColors.onSurface)
                                        .lineLimit(1)
                                    if model == settings.getModel(service.id) {
                                        Text("Default")
                                            .font(.caption2)
                                            .foregroundStyle(AppColors.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if allFilteredModels(settings: settings, providers: providers).isEmpty {
                EmptyStateView(icon: "magnifyingglass", title: "No models found", message: "Try a different search term or configure providers")
            }
        }
        .searchable(text: $searchText, prompt: "Search models...")
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Models")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func allFilteredModels(settings: Settings, providers: [AppService]) -> [String] {
        providers.flatMap { service in
            settings.getModels(service.id).filter {
                searchText.isEmpty || $0.localizedCaseInsensitiveContains(searchText)
            }
        }
    }
}

// MARK: - Model Info Screen

struct ModelInfoScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    let service: AppService
    let model: String

    @State private var testResult: String?
    @State private var isTesting = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Model header
                CardView {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(model)
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurface)
                            .textSelection(.enabled)
                        Text(service.displayName)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.dimText)
                        Text("Format: \(service.apiFormat.rawValue)")
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .padding(.horizontal)

                // Pricing
                CardView {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Pricing")
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundStyle(AppColors.onSurface)

                        HStack {
                            Text("Loading...")
                                .font(.caption)
                                .foregroundStyle(AppColors.dimText)
                        }
                    }
                }
                .padding(.horizontal)
                .task {
                    // Pricing is loaded asynchronously from PricingCache
                }

                // Test button
                CardView {
                    VStack(spacing: 12) {
                        Button {
                            testModel()
                        } label: {
                            HStack {
                                if isTesting {
                                    ProgressView()
                                        .tint(AppColors.primary)
                                }
                                Text(isTesting ? "Testing..." : "Test Model")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.primary)
                        .disabled(isTesting)

                        if let result = testResult {
                            Text(result)
                                .font(.caption)
                                .foregroundStyle(result.contains("Error") ? AppColors.error : AppColors.success)
                        }
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("Model Info")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func testModel() {
        isTesting = true
        testResult = nil
        Task {
            let apiKey = viewModel.uiState.aiSettings.getApiKey(service.id)
            let error = await viewModel.testAiModel(service: service, apiKey: apiKey, model: model)
            testResult = error ?? "OK - Model responds correctly"
            isTesting = false
        }
    }
}
