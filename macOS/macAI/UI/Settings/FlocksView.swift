import SwiftUI

// MARK: - Flocks List

struct FlocksListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var editingFlock: Flock?
    @State private var showEditor = false
    @State private var deleteTarget: Flock?

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var sortedFlocks: [Flock] {
        settings.flocks.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Add Flock") {
                    editingFlock = nil
                    showEditor = true
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            if sortedFlocks.isEmpty {
                EmptyStateView(
                    icon: "bird",
                    title: "No Flocks",
                    message: "Create flocks to group agents for reports"
                )
            } else {
                List(sortedFlocks) { flock in
                    let agents = settings.getAgentsForFlock(flock)
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(flock.name)
                                .font(.subheadline.bold())
                            Text("\(agents.count) agents: \(agents.prefix(3).map(\.name).joined(separator: ", "))\(agents.count > 3 ? "..." : "")")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button {
                            deleteTarget = flock
                        } label: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        editingFlock = flock
                        showEditor = true
                    }
                }
            }

            HStack {
                Text("\(sortedFlocks.count) flocks")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Flocks")
        .sheet(isPresented: $showEditor) {
            FlockEditView(viewModel: viewModel, flock: editingFlock) {
                showEditor = false
            }
            .frame(minWidth: 500, minHeight: 450)
        }
        .alert("Delete Flock", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let flock = deleteTarget {
                    var s = settings
                    s.flocks.removeAll { $0.id == flock.id }
                    viewModel.updateSettings(s)
                }
                deleteTarget = nil
            }
        } message: {
            Text("Are you sure you want to delete \"\(deleteTarget?.name ?? "")\"?")
        }
    }
}

// MARK: - Flock Edit

struct FlockEditView: View {
    @Bindable var viewModel: AppViewModel
    let flock: Flock?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var selectedAgentIds: Set<String> = []
    @State private var selectedParamsIds: [String] = []
    @State private var selectedSystemPromptId: String?
    @State private var searchText = ""
    @State private var validationError: String?

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var isEditing: Bool { flock != nil }

    private var availableAgents: [Agent] {
        let agents = settings.agents.sorted { a, b in
            // Selected first, then alphabetical
            let aSelected = selectedAgentIds.contains(a.id)
            let bSelected = selectedAgentIds.contains(b.id)
            if aSelected != bSelected { return aSelected }
            return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
        if searchText.isEmpty { return agents }
        let lower = searchText.lowercased()
        return agents.filter {
            $0.name.lowercased().contains(lower) ||
            ($0.provider?.displayName.lowercased().contains(lower) ?? false)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text(isEditing ? "Edit Flock" : "New Flock")
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

                    // Agent selection
                    SectionHeader(title: "Agents (\(selectedAgentIds.count) selected)", icon: "person.3")

                    TextField("Search agents...", text: $searchText)
                        .textFieldStyle(.roundedBorder)

                    ForEach(availableAgents) { agent in
                        let isSelected = selectedAgentIds.contains(agent.id)
                        Button {
                            if isSelected {
                                selectedAgentIds.remove(agent.id)
                            } else {
                                selectedAgentIds.insert(agent.id)
                            }
                        } label: {
                            HStack {
                                Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(isSelected ? AppColors.primary : .secondary)
                                VStack(alignment: .leading) {
                                    Text(agent.name)
                                        .font(.subheadline)
                                    Text("\(agent.provider?.displayName ?? "?") / \(settings.getEffectiveModelForAgent(agent))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                    }

                    Divider()

                    // Parameters
                    SectionHeader(title: "Parameters", icon: "slider.horizontal.3")
                    ParametersSelector(settings: settings, selectedIds: $selectedParamsIds)

                    // System Prompt
                    SectionHeader(title: "System Prompt", icon: "text.alignleft")
                    SystemPromptSelector(settings: settings, selectedId: $selectedSystemPromptId)
                }
                .padding()
            }
        }
        .onAppear { loadFields() }
    }

    private func loadFields() {
        guard let flock else { return }
        name = flock.name
        selectedAgentIds = Set(flock.agentIds)
        selectedParamsIds = flock.paramsIds
        selectedSystemPromptId = flock.systemPromptId
    }

    private func save() {
        validationError = nil
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            validationError = "Name is required"
            return
        }
        guard !selectedAgentIds.isEmpty else {
            validationError = "Select at least one agent"
            return
        }

        let existing = settings.flocks.first { $0.name == trimmed && $0.id != flock?.id }
        if existing != nil {
            validationError = "A flock with this name already exists"
            return
        }

        let updated = Flock(
            id: flock?.id ?? UUID().uuidString,
            name: trimmed,
            agentIds: Array(selectedAgentIds),
            paramsIds: selectedParamsIds,
            systemPromptId: selectedSystemPromptId
        )

        var s = settings
        if let idx = s.flocks.firstIndex(where: { $0.id == updated.id }) {
            s.flocks[idx] = updated
        } else {
            s.flocks.append(updated)
        }
        viewModel.updateSettings(s)
        onDismiss()
    }
}
