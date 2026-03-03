import SwiftUI

// MARK: - Agents Screen

struct AgentsScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @State private var searchText = ""

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let hasApiKey = settings.hasAnyApiKey()

        List {
            if !hasApiKey {
                CardView {
                    Text("No API keys configured. Set up a provider first.")
                        .font(.caption)
                        .foregroundStyle(AppColors.error)
                }
            }

            let filtered = settings.agents.filter {
                searchText.isEmpty ||
                $0.name.localizedCaseInsensitiveContains(searchText) ||
                $0.providerId.localizedCaseInsensitiveContains(searchText) ||
                $0.model.localizedCaseInsensitiveContains(searchText)
            }.sorted { $0.name.lowercased() < $1.name.lowercased() }

            ForEach(filtered) { agent in
                NavigationLink(destination: AgentEditScreen(agentId: agent.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(agent.name)
                            .foregroundStyle(AppColors.onSurface)
                        Text("\(agent.providerId) / \(settings.getEffectiveModelForAgent(agent))")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                    }
                }
            }
            .onDelete { indexSet in
                let sortedAgents = filtered
                for idx in indexSet {
                    let agent = sortedAgents[idx]
                    viewModel.uiState.aiSettings.removeAgent(agent.id)
                }
                viewModel.saveAiSettings()
            }

            if filtered.isEmpty && !settings.agents.isEmpty {
                EmptyStateView(icon: "magnifyingglass", title: "No matching agents")
            } else if settings.agents.isEmpty {
                EmptyStateView(icon: "person.circle", title: "No agents configured")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .searchable(text: $searchText, prompt: "Search agents...")
        .navigationTitle("Agents")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(destination: AgentEditScreen(agentId: nil)) {
                    Image(systemName: "plus")
                }
                .disabled(!hasApiKey)
            }
        }
    }
}

// MARK: - Agent Edit Screen

struct AgentEditScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @Environment(\.dismiss) private var dismiss

    let agentId: String?

    @State private var name = ""
    @State private var providerId = ""
    @State private var model = ""
    @State private var apiKey = ""
    @State private var endpointId: String?
    @State private var paramsIds: [String] = []
    @State private var systemPromptId: String?
    @State private var initialized = false

    private var isNew: Bool { agentId == nil }

    private var existingAgent: Agent? {
        agentId.flatMap { viewModel.uiState.aiSettings.getAgentById($0) }
    }

    private var canSave: Bool {
        !name.isEmpty && !providerId.isEmpty
    }

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let providers = viewModel.getAllProviders().filter { !settings.getApiKey($0.id).isEmpty }

        Form {
            Section("Name") {
                TextField("Agent name", text: $name)
            }

            Section("Provider") {
                Picker("Provider", selection: $providerId) {
                    Text("Select...").tag("")
                    ForEach(providers, id: \.id) { service in
                        Text(service.displayName).tag(service.id)
                    }
                }

                if !providerId.isEmpty {
                    TextField("Model (empty = provider default)", text: $model)
                    TextField("API Key (empty = provider key)", text: $apiKey)

                    // Endpoint selector
                    let eps = settings.getEndpointsForProvider(providerId)
                    if !eps.isEmpty {
                        Picker("Endpoint", selection: Binding(
                            get: { endpointId ?? "" },
                            set: { endpointId = $0.isEmpty ? nil : $0 }
                        )) {
                            Text("Default").tag("")
                            ForEach(eps) { ep in
                                Text(ep.name).tag(ep.id)
                            }
                        }
                    }
                }
            }

            Section("Parameters") {
                ParametersSelector(aiSettings: settings, selectedIds: $paramsIds)
            }

            Section("System Prompt") {
                SystemPromptSelector(aiSettings: settings, selectedId: $systemPromptId)
            }

            Section {
                Button(isNew ? "Create Agent" : "Save Agent") {
                    saveAgent()
                }
                .disabled(!canSave)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle(isNew ? "New Agent" : "Edit Agent")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !initialized, let agent = existingAgent else {
                initialized = true
                return
            }
            name = agent.name
            providerId = agent.providerId
            model = agent.model
            apiKey = agent.apiKey
            endpointId = agent.endpointId
            paramsIds = agent.paramsIds
            systemPromptId = agent.systemPromptId
            initialized = true
        }
    }

    private func saveAgent() {
        let agent = Agent(
            id: agentId ?? UUID().uuidString,
            name: name,
            providerId: providerId,
            model: model,
            apiKey: apiKey,
            endpointId: endpointId,
            paramsIds: paramsIds,
            systemPromptId: systemPromptId
        )

        if isNew {
            viewModel.uiState.aiSettings.agents.append(agent)
        } else if let idx = viewModel.uiState.aiSettings.agents.firstIndex(where: { $0.id == agent.id }) {
            viewModel.uiState.aiSettings.agents[idx] = agent
        }
        viewModel.saveAiSettings()
        dismiss()
    }
}
