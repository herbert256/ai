import SwiftUI

/// AI Setup hub - configure providers, agents, flocks, swarms, etc.
struct SetupView: View {
    @Bindable var viewModel: AppViewModel
    @State private var selectedSubView: SetupSubView?

    enum SetupSubView: String, CaseIterable, Identifiable {
        case providers = "Providers"
        case agents = "Agents"
        case flocks = "Flocks"
        case swarms = "Swarms"
        case parameters = "Parameters"
        case systemPrompts = "System Prompts"
        case prompts = "Prompts"
        case endpoints = "Endpoints"

        var id: String { rawValue }
        var icon: String {
            switch self {
            case .providers: return "server.rack"
            case .agents: return "person.3"
            case .flocks: return "bird"
            case .swarms: return "ant"
            case .parameters: return "slider.horizontal.3"
            case .systemPrompts: return "text.alignleft"
            case .prompts: return "doc.text"
            case .endpoints: return "link"
            }
        }
    }

    private var settings: Settings { viewModel.uiState.aiSettings }

    var body: some View {
        NavigationStack {
            List(SetupSubView.allCases, selection: $selectedSubView) { sub in
                NavigationLink(value: sub) {
                    Label {
                        HStack {
                            Text(sub.rawValue)
                            Spacer()
                            Text(countFor(sub))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: sub.icon)
                    }
                }
            }
            .navigationTitle("AI Setup")
            .navigationDestination(for: SetupSubView.self) { sub in
                switch sub {
                case .providers:
                    ProviderListView(viewModel: viewModel)
                case .agents:
                    AgentsListView(viewModel: viewModel)
                case .flocks:
                    FlocksListView(viewModel: viewModel)
                case .swarms:
                    SwarmsListView(viewModel: viewModel)
                case .parameters:
                    ParametersListView(viewModel: viewModel)
                case .systemPrompts:
                    SystemPromptsListView(viewModel: viewModel)
                case .prompts:
                    PromptsListView(viewModel: viewModel)
                case .endpoints:
                    EndpointsListView(viewModel: viewModel)
                }
            }
        }
    }

    private func countFor(_ sub: SetupSubView) -> String {
        switch sub {
        case .providers: return "\(settings.getActiveServices().count)/\(AppService.entries.count)"
        case .agents: return "\(settings.agents.count)"
        case .flocks: return "\(settings.flocks.count)"
        case .swarms: return "\(settings.swarms.count)"
        case .parameters: return "\(settings.parameters.count)"
        case .systemPrompts: return "\(settings.systemPrompts.count)"
        case .prompts: return "\(settings.prompts.count)"
        case .endpoints: return "\(settings.endpoints.count)"
        }
    }
}

// MARK: - Provider List

struct ProviderListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var searchText = ""

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var providers: [AppService] {
        let all = AppService.entries
        if searchText.isEmpty { return all }
        let lower = searchText.lowercased()
        return all.filter { $0.displayName.lowercased().contains(lower) }
    }

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search providers...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding()

            List(providers) { service in
                NavigationLink {
                    ServiceSettingsView(viewModel: viewModel, service: service)
                } label: {
                    HStack {
                        ProviderStateBadge(state: settings.getProviderState(service))
                        Text(service.displayName)
                            .font(.subheadline)
                        Spacer()
                        if !settings.getApiKey(service).isEmpty {
                            Text(settings.getModel(service))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        } else {
                            Text("Not configured")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Providers")
    }
}
