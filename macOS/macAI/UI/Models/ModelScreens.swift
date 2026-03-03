import SwiftUI

// MARK: - Model Search

struct ModelSearchView: View {
    @Bindable var viewModel: AppViewModel
    @State private var searchText = ""
    @State private var selectedModel: ModelSearchResult?
    @State private var showAction = false

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var allModels: [ModelSearchResult] {
        var results: [ModelSearchResult] = []
        for service in AppService.entries {
            guard settings.isProviderActive(service) || !settings.getApiKey(service).isEmpty else { continue }
            let models = settings.getModels(service)
            if models.isEmpty {
                let model = settings.getModel(service)
                if !model.isEmpty {
                    results.append(ModelSearchResult(service: service, model: model))
                }
            } else {
                for model in models {
                    results.append(ModelSearchResult(service: service, model: model))
                }
            }
        }
        return results
    }

    private var filteredModels: [ModelSearchResult] {
        if searchText.isEmpty { return allModels }
        let lower = searchText.lowercased()
        return allModels.filter {
            $0.model.lowercased().contains(lower) ||
            $0.service.displayName.lowercased().contains(lower)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search models...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding()

            HStack {
                Text("\(filteredModels.count) models across \(Set(filteredModels.map(\.service.id)).count) providers")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal)

            if filteredModels.isEmpty {
                EmptyStateView(
                    icon: "magnifyingglass",
                    title: "No Models Found",
                    message: allModels.isEmpty ? "Configure providers to see available models" : "No models match your search"
                )
            } else {
                List(filteredModels) { result in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(result.model)
                                .font(.subheadline.bold())
                            HStack(spacing: 4) {
                                ProviderStateBadge(state: settings.getProviderState(result.service))
                                Text(result.service.displayName)
                                    .font(.caption)
                                    .foregroundStyle(AppColors.primary)
                            }
                        }
                        Spacer()
                        Text(result.service.apiFormat.rawValue)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        selectedModel = result
                        showAction = true
                    }
                }
            }
        }
        .navigationTitle("Model Search")
        .sheet(isPresented: $showAction) {
            if let model = selectedModel {
                ModelActionSheet(viewModel: viewModel, result: model) {
                    showAction = false
                }
                .frame(minWidth: 400, minHeight: 300)
            }
        }
    }
}

// MARK: - Model Search Result

struct ModelSearchResult: Identifiable {
    let id = UUID()
    let service: AppService
    let model: String
}

// MARK: - Model Action Sheet

struct ModelActionSheet: View {
    @Bindable var viewModel: AppViewModel
    let result: ModelSearchResult
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(result.model)
                .font(.headline)
            Text(result.service.displayName)
                .font(.subheadline)
                .foregroundStyle(AppColors.primary)

            Divider()

            VStack(alignment: .leading, spacing: 8) {
                InfoRow(label: "Provider", value: result.service.displayName)
                InfoRow(label: "API Format", value: result.service.apiFormat.rawValue)
                InfoRow(label: "Base URL", value: result.service.baseUrl)
                if let openRouterName = result.service.openRouterName {
                    InfoRow(label: "OpenRouter", value: openRouterName)
                }
            }

            Divider()

            HStack(spacing: 12) {
                Button("Close") { onDismiss() }
                    .keyboardShortcut(.escape, modifiers: [])
                Button("Create Agent") {
                    createAgent()
                    onDismiss()
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(20)
    }

    private func createAgent() {
        let agent = Agent(
            name: "\(result.service.displayName) - \(result.model)",
            providerId: result.service.id,
            model: result.model
        )
        var s = viewModel.uiState.aiSettings
        s.agents.append(agent)
        viewModel.updateSettings(s)
    }
}

// MARK: - Info Row

struct InfoRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 100, alignment: .trailing)
            Text(value)
                .font(.caption)
                .lineLimit(1)
        }
    }
}
