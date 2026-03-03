import SwiftUI

// MARK: - Swarms List

struct SwarmsListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var editingSwarm: Swarm?
    @State private var showEditor = false
    @State private var deleteTarget: Swarm?

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var sortedSwarms: [Swarm] {
        settings.swarms.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Add Swarm") {
                    editingSwarm = nil
                    showEditor = true
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            if sortedSwarms.isEmpty {
                EmptyStateView(
                    icon: "ant",
                    title: "No Swarms",
                    message: "Create swarms to group provider/model pairs for reports"
                )
            } else {
                List(sortedSwarms) { swarm in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(swarm.name)
                                .font(.subheadline.bold())
                            let memberDescs = swarm.members.prefix(3).map { "\($0.provider?.displayName ?? $0.providerId)/\($0.model)" }
                            Text("\(swarm.members.count) members: \(memberDescs.joined(separator: ", "))\(swarm.members.count > 3 ? "..." : "")")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button {
                            deleteTarget = swarm
                        } label: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        editingSwarm = swarm
                        showEditor = true
                    }
                }
            }

            HStack {
                Text("\(sortedSwarms.count) swarms")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Swarms")
        .sheet(isPresented: $showEditor) {
            SwarmEditView(viewModel: viewModel, swarm: editingSwarm) {
                showEditor = false
            }
            .frame(minWidth: 500, minHeight: 500)
        }
        .alert("Delete Swarm", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let swarm = deleteTarget {
                    var s = settings
                    s.swarms.removeAll { $0.id == swarm.id }
                    viewModel.updateSettings(s)
                }
                deleteTarget = nil
            }
        } message: {
            Text("Are you sure you want to delete \"\(deleteTarget?.name ?? "")\"?")
        }
    }
}

// MARK: - Swarm Edit

struct SwarmEditView: View {
    @Bindable var viewModel: AppViewModel
    let swarm: Swarm?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var selectedMembers: Set<String> = []  // "providerId::model" keys
    @State private var selectedParamsIds: [String] = []
    @State private var selectedSystemPromptId: String?
    @State private var searchText = ""
    @State private var validationError: String?

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var isEditing: Bool { swarm != nil }

    private func memberKey(_ providerId: String, _ model: String) -> String {
        "\(providerId)::\(model)"
    }

    private var availableMembers: [(key: String, providerId: String, model: String, providerName: String)] {
        var members: [(key: String, providerId: String, model: String, providerName: String)] = []
        for service in AppService.entries {
            guard settings.isProviderActive(service) || !settings.getApiKey(service).isEmpty else { continue }
            let models = settings.getModels(service)
            if models.isEmpty {
                let model = settings.getModel(service)
                if !model.isEmpty {
                    members.append((memberKey(service.id, model), service.id, model, service.displayName))
                }
            } else {
                for model in models {
                    members.append((memberKey(service.id, model), service.id, model, service.displayName))
                }
            }
        }

        // Sort: selected first, then by provider/model
        members.sort { a, b in
            let aSelected = selectedMembers.contains(a.key)
            let bSelected = selectedMembers.contains(b.key)
            if aSelected != bSelected { return aSelected }
            if a.providerName != b.providerName {
                return a.providerName.localizedCaseInsensitiveCompare(b.providerName) == .orderedAscending
            }
            return a.model.localizedCaseInsensitiveCompare(b.model) == .orderedAscending
        }

        if searchText.isEmpty { return members }
        let lower = searchText.lowercased()
        return members.filter {
            $0.providerName.lowercased().contains(lower) || $0.model.lowercased().contains(lower)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(isEditing ? "Edit Swarm" : "New Swarm")
                    .font(.headline)
                Spacer()
                Button("Cancel") { onDismiss() }
                    .keyboardShortcut(.escape, modifiers: [])
                Button(isEditing ? "Save" : "Create") { save() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.return, modifiers: .command)
            }
            .padding()

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    AppTextField(label: "Name", text: $name)

                    if let error = validationError {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }

                    Divider()

                    SectionHeader(title: "Members (\(selectedMembers.count) selected)", icon: "ant")

                    TextField("Search providers/models...", text: $searchText)
                        .textFieldStyle(.roundedBorder)

                    ForEach(availableMembers, id: \.key) { member in
                        let isSelected = selectedMembers.contains(member.key)
                        Button {
                            if isSelected {
                                selectedMembers.remove(member.key)
                            } else {
                                selectedMembers.insert(member.key)
                            }
                        } label: {
                            HStack {
                                Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(isSelected ? AppColors.primary : .secondary)
                                Text(member.providerName)
                                    .font(.subheadline)
                                    .foregroundStyle(AppColors.primary)
                                Text(member.model)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                    }

                    Divider()

                    SectionHeader(title: "Parameters", icon: "slider.horizontal.3")
                    ParametersSelector(settings: settings, selectedIds: $selectedParamsIds)

                    SectionHeader(title: "System Prompt", icon: "text.alignleft")
                    SystemPromptSelector(settings: settings, selectedId: $selectedSystemPromptId)
                }
                .padding()
            }
        }
        .onAppear { loadFields() }
    }

    private func loadFields() {
        guard let swarm else { return }
        name = swarm.name
        selectedMembers = Set(swarm.members.map { memberKey($0.providerId, $0.model) })
        selectedParamsIds = swarm.paramsIds
        selectedSystemPromptId = swarm.systemPromptId
    }

    private func save() {
        validationError = nil
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            validationError = "Name is required"
            return
        }
        guard !selectedMembers.isEmpty else {
            validationError = "Select at least one member"
            return
        }

        let existing = settings.swarms.first { $0.name == trimmed && $0.id != swarm?.id }
        if existing != nil {
            validationError = "A swarm with this name already exists"
            return
        }

        let members = selectedMembers.compactMap { key -> SwarmMember? in
            let parts = key.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
            guard parts.count >= 3 else { return nil }
            let providerId = String(parts[0])
            let model = String(parts[2])
            return SwarmMember(providerId: providerId, model: model)
        }

        let updated = Swarm(
            id: swarm?.id ?? UUID().uuidString,
            name: trimmed,
            members: members,
            paramsIds: selectedParamsIds,
            systemPromptId: selectedSystemPromptId
        )

        var s = settings
        if let idx = s.swarms.firstIndex(where: { $0.id == updated.id }) {
            s.swarms[idx] = updated
        } else {
            s.swarms.append(updated)
        }
        viewModel.updateSettings(s)
        onDismiss()
    }
}
