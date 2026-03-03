import SwiftUI

// MARK: - Flocks Screen

struct FlocksScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let flocks = settings.flocks.sorted { $0.name.lowercased() < $1.name.lowercased() }

        List {
            ForEach(flocks) { flock in
                NavigationLink(destination: FlockEditScreen(flockId: flock.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(flock.name)
                            .foregroundStyle(AppColors.onSurface)
                        let agentNames = flock.agentIds.compactMap { settings.getAgentById($0)?.name }.prefix(3)
                        Text("\(flock.agentIds.count) agents - \(agentNames.joined(separator: ", "))")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                            .lineLimit(1)
                    }
                }
            }
            .onDelete { indexSet in
                let sorted = flocks
                for idx in indexSet {
                    viewModel.uiState.aiSettings.flocks.removeAll { $0.id == sorted[idx].id }
                }
                viewModel.saveAiSettings()
            }

            if flocks.isEmpty {
                EmptyStateView(icon: "bird", title: "No flocks configured")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Flocks")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(destination: FlockEditScreen(flockId: nil)) {
                    Image(systemName: "plus")
                }
            }
        }
    }
}

// MARK: - Flock Edit Screen

struct FlockEditScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @Environment(\.dismiss) private var dismiss

    let flockId: String?

    @State private var name = ""
    @State private var selectedAgentIds: Set<String> = []
    @State private var paramsIds: [String] = []
    @State private var systemPromptId: String?
    @State private var searchText = ""
    @State private var initialized = false

    private var isNew: Bool { flockId == nil }

    private var canSave: Bool {
        !name.isEmpty && !selectedAgentIds.isEmpty
    }

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let allAgents = settings.agents.sorted { a, b in
            let aSelected = selectedAgentIds.contains(a.id)
            let bSelected = selectedAgentIds.contains(b.id)
            if aSelected != bSelected { return aSelected }
            return a.name.lowercased() < b.name.lowercased()
        }
        let filtered = allAgents.filter {
            searchText.isEmpty || $0.name.localizedCaseInsensitiveContains(searchText)
        }

        VStack(spacing: 0) {
            Form {
                Section("Flock Name") {
                    TextField("Name", text: $name)
                }

                Section {
                    HStack {
                        SystemPromptSelector(aiSettings: settings, selectedId: $systemPromptId)
                        Spacer()
                        ParametersSelector(aiSettings: settings, selectedIds: $paramsIds)
                    }
                }

                Section("Agents (\(selectedAgentIds.count) selected)") {
                    TextField("Search agents...", text: $searchText)
                        .textFieldStyle(.roundedBorder)

                    ForEach(filtered) { agent in
                        Button {
                            if selectedAgentIds.contains(agent.id) {
                                selectedAgentIds.remove(agent.id)
                            } else {
                                selectedAgentIds.insert(agent.id)
                            }
                        } label: {
                            HStack {
                                Image(systemName: selectedAgentIds.contains(agent.id) ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(selectedAgentIds.contains(agent.id) ? AppColors.primary : AppColors.dimText)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(agent.name)
                                        .foregroundStyle(AppColors.onSurface)
                                    Text("\(agent.providerId) / \(settings.getEffectiveModelForAgent(agent))")
                                        .font(.caption2)
                                        .foregroundStyle(AppColors.dimText)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section {
                    Button(isNew ? "Create Flock" : "Save Flock") {
                        saveFlock()
                    }
                    .disabled(!canSave)
                }
            }
            .scrollContentBackground(.hidden)
        }
        .background(AppColors.background)
        .navigationTitle(isNew ? "New Flock" : "Edit Flock")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !initialized, let flock = flockId.flatMap({ viewModel.uiState.aiSettings.getFlockById($0) }) else {
                initialized = true
                return
            }
            name = flock.name
            selectedAgentIds = Set(flock.agentIds)
            paramsIds = flock.paramsIds
            systemPromptId = flock.systemPromptId
            initialized = true
        }
    }

    private func saveFlock() {
        let flock = Flock(
            id: flockId ?? UUID().uuidString,
            name: name,
            agentIds: Array(selectedAgentIds),
            paramsIds: paramsIds,
            systemPromptId: systemPromptId
        )
        if isNew {
            viewModel.uiState.aiSettings.flocks.append(flock)
        } else if let idx = viewModel.uiState.aiSettings.flocks.firstIndex(where: { $0.id == flock.id }) {
            viewModel.uiState.aiSettings.flocks[idx] = flock
        }
        viewModel.saveAiSettings()
        dismiss()
    }
}
