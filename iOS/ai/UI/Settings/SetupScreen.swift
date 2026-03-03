import SwiftUI

// MARK: - AI Setup Screen

struct SetupScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let hasApiKey = settings.hasAnyApiKey()

        ScrollView {
            VStack(spacing: 12) {
                // Import buttons (only when no provider has an API key)
                if !hasApiKey {
                    HStack(spacing: 8) {
                        Button("Import AI Configuration") {
                            // File import handled via document picker
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.success)
                        .font(.caption)
                    }
                    .padding(.horizontal)
                }

                let configuredAgents = settings.agents.filter { agent in
                    !settings.getEffectiveApiKeyForAgent(agent).isEmpty
                }.count

                // Navigation cards
                NavigationLink(destination: ProvidersScreen()) {
                    SetupNavigationCard(
                        title: "Providers",
                        description: "Configure model sources for each AI service",
                        icon: "gear",
                        count: "\(viewModel.getAllProviders().count) providers"
                    )
                }

                NavigationLink(destination: AgentsScreen()) {
                    SetupNavigationCard(
                        title: "Agents",
                        description: "Configure agents with provider, model, and API key",
                        icon: "person.circle",
                        count: "\(configuredAgents) configured",
                        enabled: hasApiKey
                    )
                }
                .disabled(!hasApiKey)

                NavigationLink(destination: FlocksScreen()) {
                    SetupNavigationCard(
                        title: "Flocks",
                        description: "Group agents into flocks for report generation",
                        icon: "bird",
                        count: "\(settings.flocks.count) configured",
                        enabled: hasApiKey
                    )
                }
                .disabled(!hasApiKey)

                NavigationLink(destination: SwarmsScreen()) {
                    SetupNavigationCard(
                        title: "Swarms",
                        description: "Group provider/model combinations for reports",
                        icon: "ant",
                        count: "\(settings.swarms.count) configured",
                        enabled: hasApiKey
                    )
                }
                .disabled(!hasApiKey)

                NavigationLink(destination: ParametersScreen()) {
                    SetupNavigationCard(
                        title: "Parameters",
                        description: "Reusable parameter presets for agents",
                        icon: "slider.horizontal.3",
                        count: "\(settings.parameters.count) configured"
                    )
                }

                NavigationLink(destination: SystemPromptsScreen()) {
                    SetupNavigationCard(
                        title: "System Prompts",
                        description: "Reusable system prompts for agents and flocks",
                        icon: "text.bubble",
                        count: "\(settings.systemPrompts.count) configured"
                    )
                }

                NavigationLink(destination: PromptsScreen()) {
                    SetupNavigationCard(
                        title: "Internal Prompts",
                        description: "Internal prompts for AI-powered features",
                        icon: "doc.text",
                        count: "\(settings.prompts.count) configured"
                    )
                }
            }
            .padding(.vertical)
            .padding(.horizontal)
        }
        .background(AppColors.background)
        .navigationTitle("AI Setup")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Providers Screen

struct ProvidersScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    @State private var showAll = false
    @State private var showAddDialog = false
    @State private var newProviderName = ""

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let allProviders = viewModel.getAllProviders().sorted { $0.displayName.lowercased() < $1.displayName.lowercased() }
        let visible = showAll ? allProviders : allProviders.filter { settings.getProviderState($0.id) == "ok" }

        ScrollView {
            VStack(spacing: 8) {
                // Add Provider
                Button {
                    newProviderName = ""
                    showAddDialog = true
                } label: {
                    Label("Add Provider", systemImage: "plus.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.horizontal)

                ForEach(visible, id: \.id) { service in
                    NavigationLink(destination: ServiceSettingsScreen(service: service)) {
                        ServiceNavigationCard(
                            title: service.displayName,
                            providerState: settings.getProviderState(service.id)
                        )
                    }
                    .padding(.horizontal)
                }

                let activeCount = allProviders.filter { settings.getProviderState($0.id) == "ok" }.count
                Button {
                    showAll.toggle()
                } label: {
                    Text(showAll ? "Show active providers (\(activeCount))" : "Show all providers (\(allProviders.count))")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("AI Providers")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Add Provider", isPresented: $showAddDialog) {
            TextField("Provider name", text: $newProviderName)
            Button("Create") {
                guard !newProviderName.isEmpty else { return }
                let id = newProviderName.trimmingCharacters(in: .whitespaces)
                    .uppercased()
                    .replacingOccurrences(of: "[^A-Z0-9_]", with: "_", options: .regularExpression)
                let prefsKey = newProviderName.trimmingCharacters(in: .whitespaces)
                    .lowercased()
                    .replacingOccurrences(of: "[^a-z0-9_]", with: "_", options: .regularExpression)
                let service = AppService(
                    id: id,
                    displayName: newProviderName.trimmingCharacters(in: .whitespaces),
                    baseUrl: "https://",
                    adminUrl: "",
                    defaultModel: "",
                    prefsKey: prefsKey
                )
                Task {
                    await ProviderRegistry.shared.add(service)
                    viewModel.refreshProviderCache()
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}
