import SwiftUI

// MARK: - Swarms Screen

struct SwarmsScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let swarms = settings.swarms.sorted { $0.name.lowercased() < $1.name.lowercased() }

        List {
            ForEach(swarms) { swarm in
                NavigationLink(destination: SwarmEditScreen(swarmId: swarm.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(swarm.name)
                            .foregroundStyle(AppColors.onSurface)
                        let preview = swarm.members.prefix(3).map { "\($0.providerId)/\($0.model)" }
                        Text("\(swarm.members.count) members - \(preview.joined(separator: ", "))")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                            .lineLimit(1)
                    }
                }
            }
            .onDelete { indexSet in
                let sorted = swarms
                for idx in indexSet {
                    viewModel.uiState.aiSettings.swarms.removeAll { $0.id == sorted[idx].id }
                }
                viewModel.saveAiSettings()
            }

            if swarms.isEmpty {
                EmptyStateView(icon: "ant", title: "No swarms configured")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Swarms")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(destination: SwarmEditScreen(swarmId: nil)) {
                    Image(systemName: "plus")
                }
            }
        }
    }
}

// MARK: - Swarm Edit Screen

struct SwarmEditScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @Environment(\.dismiss) private var dismiss

    let swarmId: String?

    @State private var name = ""
    @State private var selectedMembers: Set<String> = []  // "providerId|model" keys
    @State private var paramsIds: [String] = []
    @State private var systemPromptId: String?
    @State private var searchText = ""
    @State private var initialized = false

    private var isNew: Bool { swarmId == nil }

    private var canSave: Bool {
        !name.isEmpty && !selectedMembers.isEmpty
    }

    private func memberKey(_ providerId: String, _ model: String) -> String {
        "\(providerId)|\(model)"
    }

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let providers = viewModel.getAllProviders().filter { settings.isProviderActive($0.id) && !settings.getApiKey($0.id).isEmpty }

        // Build all possible members
        let allMembers: [(key: String, providerId: String, model: String, providerName: String)] = providers.flatMap { service in
            settings.getModels(service.id).map { model in
                (key: memberKey(service.id, model), providerId: service.id, model: model, providerName: service.displayName)
            }
        }

        let filtered = allMembers.filter {
            searchText.isEmpty ||
            $0.providerName.localizedCaseInsensitiveContains(searchText) ||
            $0.model.localizedCaseInsensitiveContains(searchText)
        }.sorted { a, b in
            let aSelected = selectedMembers.contains(a.key)
            let bSelected = selectedMembers.contains(b.key)
            if aSelected != bSelected { return aSelected }
            return a.providerName < b.providerName
        }

        VStack(spacing: 0) {
            Form {
                Section("Swarm Name") {
                    TextField("Name", text: $name)
                }

                Section {
                    HStack {
                        SystemPromptSelector(aiSettings: settings, selectedId: $systemPromptId)
                        Spacer()
                        ParametersSelector(aiSettings: settings, selectedIds: $paramsIds)
                    }
                }

                Section("Members (\(selectedMembers.count) selected)") {
                    TextField("Search...", text: $searchText)
                        .textFieldStyle(.roundedBorder)

                    ForEach(filtered, id: \.key) { member in
                        Button {
                            if selectedMembers.contains(member.key) {
                                selectedMembers.remove(member.key)
                            } else {
                                selectedMembers.insert(member.key)
                            }
                        } label: {
                            HStack {
                                Image(systemName: selectedMembers.contains(member.key) ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(selectedMembers.contains(member.key) ? AppColors.primary : AppColors.dimText)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(member.model)
                                        .foregroundStyle(AppColors.onSurface)
                                        .lineLimit(1)
                                    Text(member.providerName)
                                        .font(.caption2)
                                        .foregroundStyle(AppColors.dimText)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section {
                    Button(isNew ? "Create Swarm" : "Save Swarm") {
                        saveSwarm()
                    }
                    .disabled(!canSave)
                }
            }
            .scrollContentBackground(.hidden)
        }
        .background(AppColors.background)
        .navigationTitle(isNew ? "New Swarm" : "Edit Swarm")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !initialized, let swarm = swarmId.flatMap({ viewModel.uiState.aiSettings.getSwarmById($0) }) else {
                initialized = true
                return
            }
            name = swarm.name
            selectedMembers = Set(swarm.members.map { memberKey($0.providerId, $0.model) })
            paramsIds = swarm.paramsIds
            systemPromptId = swarm.systemPromptId
            initialized = true
        }
    }

    private func saveSwarm() {
        let members = selectedMembers.compactMap { key -> SwarmMember? in
            let parts = key.split(separator: "|", maxSplits: 1)
            guard parts.count == 2 else { return nil }
            return SwarmMember(providerId: String(parts[0]), model: String(parts[1]))
        }
        let swarm = Swarm(
            id: swarmId ?? UUID().uuidString,
            name: name,
            members: members,
            paramsIds: paramsIds,
            systemPromptId: systemPromptId
        )
        if isNew {
            viewModel.uiState.aiSettings.swarms.append(swarm)
        } else if let idx = viewModel.uiState.aiSettings.swarms.firstIndex(where: { $0.id == swarm.id }) {
            viewModel.uiState.aiSettings.swarms[idx] = swarm
        }
        viewModel.saveAiSettings()
        dismiss()
    }
}
