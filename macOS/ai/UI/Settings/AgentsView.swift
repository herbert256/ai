import SwiftUI

// MARK: - Agents List

struct AgentsListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var searchText = ""
    @State private var editingAgent: Agent?
    @State private var showEditor = false
    @State private var deleteTarget: Agent?

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var filteredAgents: [Agent] {
        let sorted = settings.agents.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        if searchText.isEmpty { return sorted }
        let lower = searchText.lowercased()
        return sorted.filter {
            $0.name.lowercased().contains(lower) ||
            ($0.provider?.displayName.lowercased().contains(lower) ?? false) ||
            settings.getEffectiveModelForAgent($0).lowercased().contains(lower)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                TextField("Search agents...", text: $searchText)
                    .textFieldStyle(.roundedBorder)
                Button("Add Agent") {
                    editingAgent = nil
                    showEditor = true
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            if filteredAgents.isEmpty {
                EmptyStateView(
                    icon: "person.3",
                    title: "No Agents",
                    message: settings.agents.isEmpty ? "Create agents to use in reports and chats" : "No matching agents"
                )
            } else {
                List(filteredAgents) { agent in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(agent.name)
                                .font(.subheadline.bold())
                            HStack(spacing: 4) {
                                if let provider = agent.provider {
                                    ProviderStateBadge(state: settings.getProviderState(provider))
                                    Text(provider.displayName)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Text(settings.getEffectiveModelForAgent(agent))
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                                    .lineLimit(1)
                            }
                        }
                        Spacer()
                        Button {
                            copyAgent(agent)
                        } label: {
                            Image(systemName: "doc.on.doc")
                                .font(.caption)
                        }
                        .buttonStyle(.plain)
                        .help("Duplicate")
                        Button {
                            deleteTarget = agent
                        } label: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        editingAgent = agent
                        showEditor = true
                    }
                }
            }

            HStack {
                Text("\(filteredAgents.count) agents")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Agents")
        .sheet(isPresented: $showEditor) {
            AgentEditView(viewModel: viewModel, agent: editingAgent) {
                showEditor = false
            }
            .frame(minWidth: 500, minHeight: 500)
        }
        .alert("Delete Agent", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let agent = deleteTarget {
                    var s = settings
                    s.removeAgent(agent.id)
                    viewModel.updateSettings(s)
                }
                deleteTarget = nil
            }
        } message: {
            Text("Are you sure you want to delete \"\(deleteTarget?.name ?? "")\"? This will also remove it from any flocks.")
        }
    }

    private func copyAgent(_ agent: Agent) {
        let copy = Agent(
            name: "\(agent.name) (Copy)",
            providerId: agent.providerId,
            model: agent.model,
            apiKey: agent.apiKey,
            endpointId: agent.endpointId,
            paramsIds: agent.paramsIds,
            systemPromptId: agent.systemPromptId
        )
        var s = settings
        s.agents.append(copy)
        viewModel.updateSettings(s)
    }
}

// MARK: - Agent Edit

struct AgentEditView: View {
    @Bindable var viewModel: AppViewModel
    let agent: Agent?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var selectedProviderId = ""
    @State private var model = ""
    @State private var apiKey = ""
    @State private var selectedEndpointId: String?
    @State private var selectedParamsIds: [String] = []
    @State private var selectedSystemPromptId: String?
    @State private var validationError: String?

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var isEditing: Bool { agent != nil }
    private var selectedProvider: AppService? { AppService.findById(selectedProviderId) }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text(isEditing ? "Edit Agent" : "New Agent")
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
                VStack(alignment: .leading, spacing: 16) {
                    // Name
                    AppTextField(label: "Name", text: $name)

                    if let error = validationError {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }

                    Divider()

                    // Provider
                    SectionHeader(title: "Provider", icon: "server.rack")
                    Picker("Provider", selection: $selectedProviderId) {
                        Text("Select...").tag("")
                        ForEach(AppService.entries) { service in
                            HStack {
                                ProviderStateBadge(state: settings.getProviderState(service))
                                Text(service.displayName)
                            }
                            .tag(service.id)
                        }
                    }

                    // Model
                    AppTextField(label: "Model (empty = use provider default)", text: $model)

                    if let provider = selectedProvider {
                        let models = settings.getModels(provider)
                        if !models.isEmpty {
                            DisclosureGroup("Available Models (\(models.count))") {
                                ForEach(models, id: \.self) { m in
                                    Button {
                                        model = m
                                    } label: {
                                        HStack {
                                            Text(m).font(.caption)
                                            Spacer()
                                            if m == model {
                                                Image(systemName: "checkmark")
                                                    .font(.caption)
                                                    .foregroundStyle(AppColors.primary)
                                            }
                                        }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }

                    Divider()

                    // API Key
                    SectionHeader(title: "API Key", icon: "key")
                    AppTextField(label: "API Key (empty = use provider key)", text: $apiKey, isSecure: true)

                    Divider()

                    // Endpoint
                    if let provider = selectedProvider {
                        SectionHeader(title: "Endpoint", icon: "link")
                        let endpoints = settings.getEndpointsForProvider(provider)
                        Picker("Endpoint", selection: Binding(
                            get: { selectedEndpointId ?? "" },
                            set: { selectedEndpointId = $0.isEmpty ? nil : $0 }
                        )) {
                            Text("Default").tag("")
                            ForEach(endpoints) { ep in
                                Text(ep.name).tag(ep.id)
                            }
                        }

                        Divider()
                    }

                    // Parameters
                    SectionHeader(title: "Parameters", icon: "slider.horizontal.3")
                    ParametersSelector(
                        settings: settings,
                        selectedIds: $selectedParamsIds
                    )

                    Divider()

                    // System Prompt
                    SectionHeader(title: "System Prompt", icon: "text.alignleft")
                    SystemPromptSelector(
                        settings: settings,
                        selectedId: $selectedSystemPromptId
                    )
                }
                .padding()
            }
        }
        .onAppear { loadFields() }
    }

    private func loadFields() {
        guard let agent else { return }
        name = agent.name
        selectedProviderId = agent.providerId
        model = agent.model
        apiKey = agent.apiKey
        selectedEndpointId = agent.endpointId
        selectedParamsIds = agent.paramsIds
        selectedSystemPromptId = agent.systemPromptId
    }

    private func save() {
        validationError = nil
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            validationError = "Name is required"
            return
        }
        guard !selectedProviderId.isEmpty else {
            validationError = "Provider is required"
            return
        }

        // Check uniqueness
        let existing = settings.agents.first { $0.name == trimmed && $0.id != agent?.id }
        if existing != nil {
            validationError = "An agent with this name already exists"
            return
        }

        let updated = Agent(
            id: agent?.id ?? UUID().uuidString,
            name: trimmed,
            providerId: selectedProviderId,
            model: model.trimmingCharacters(in: .whitespaces),
            apiKey: apiKey.trimmingCharacters(in: .whitespaces),
            endpointId: selectedEndpointId,
            paramsIds: selectedParamsIds,
            systemPromptId: selectedSystemPromptId
        )

        var s = settings
        if let idx = s.agents.firstIndex(where: { $0.id == updated.id }) {
            s.agents[idx] = updated
        } else {
            s.agents.append(updated)
        }
        viewModel.updateSettings(s)
        onDismiss()
    }
}

// MARK: - Parameters Selector (Reusable)

struct ParametersSelector: View {
    let settings: Settings
    @Binding var selectedIds: [String]
    @State private var showPicker = false

    var body: some View {
        HStack {
            Text(selectedIds.isEmpty ? "No parameters" : "\(selectedIds.count) preset(s)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer()
            Button("Select...") { showPicker = true }
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
        .sheet(isPresented: $showPicker) {
            ParametersSelectorDialog(
                parameters: settings.parameters,
                selectedIds: $selectedIds,
                onDismiss: { showPicker = false }
            )
            .frame(minWidth: 350, minHeight: 300)
        }
    }
}

struct ParametersSelectorDialog: View {
    let parameters: [Parameters]
    @Binding var selectedIds: [String]
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Select Parameters")
                    .font(.headline)
                Spacer()
                Button("Clear All") { selectedIds = [] }
                    .controlSize(.small)
                Button("Done") { onDismiss() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            }
            .padding()

            Divider()

            if parameters.isEmpty {
                EmptyStateView(icon: "slider.horizontal.3", title: "No Parameters", message: "Create parameter presets first")
            } else {
                List(parameters) { param in
                    let isSelected = selectedIds.contains(param.id)
                    Button {
                        if isSelected {
                            selectedIds.removeAll { $0 == param.id }
                        } else {
                            selectedIds.append(param.id)
                        }
                    } label: {
                        HStack {
                            Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                                .foregroundStyle(isSelected ? AppColors.primary : .secondary)
                            Text(param.name)
                                .font(.subheadline)
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - System Prompt Selector (Reusable)

struct SystemPromptSelector: View {
    let settings: Settings
    @Binding var selectedId: String?
    @State private var showPicker = false

    private var selectedName: String {
        guard let id = selectedId, let sp = settings.getSystemPromptById(id) else { return "None" }
        return sp.name
    }

    var body: some View {
        HStack {
            Text(selectedName)
                .font(.subheadline)
                .foregroundStyle(selectedId == nil ? .secondary : .primary)
            Spacer()
            Button("Select...") { showPicker = true }
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
        .sheet(isPresented: $showPicker) {
            SystemPromptSelectorDialog(
                systemPrompts: settings.systemPrompts,
                selectedId: $selectedId,
                onDismiss: { showPicker = false }
            )
            .frame(minWidth: 350, minHeight: 300)
        }
    }
}

struct SystemPromptSelectorDialog: View {
    let systemPrompts: [SystemPrompt]
    @Binding var selectedId: String?
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Select System Prompt")
                    .font(.headline)
                Spacer()
                Button("Done") { onDismiss() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            }
            .padding()

            Divider()

            List {
                Button {
                    selectedId = nil
                } label: {
                    HStack {
                        Image(systemName: selectedId == nil ? "largecircle.fill.circle" : "circle")
                            .foregroundStyle(selectedId == nil ? AppColors.primary : .secondary)
                        Text("None")
                            .font(.subheadline)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)

                ForEach(systemPrompts) { sp in
                    let isSelected = selectedId == sp.id
                    Button {
                        selectedId = sp.id
                    } label: {
                        HStack {
                            Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(isSelected ? AppColors.primary : .secondary)
                            VStack(alignment: .leading) {
                                Text(sp.name)
                                    .font(.subheadline)
                                Text(String(sp.prompt.prefix(80)))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}
