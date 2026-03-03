import SwiftUI

// MARK: - Prompts List

struct PromptsListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var editingPrompt: Prompt?
    @State private var showEditor = false
    @State private var deleteTarget: Prompt?

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var sortedPrompts: [Prompt] {
        settings.prompts.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Add Prompt") {
                    editingPrompt = nil
                    showEditor = true
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            // Info card about variables
            HStack(spacing: 8) {
                Image(systemName: "info.circle")
                    .foregroundStyle(AppColors.primary)
                Text("Variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal)

            if sortedPrompts.isEmpty {
                EmptyStateView(
                    icon: "doc.text",
                    title: "No Prompts",
                    message: "Create internal prompts with variable substitution"
                )
            } else {
                List(sortedPrompts) { prompt in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(prompt.name)
                                .font(.subheadline.bold())
                            let agent = settings.getAgentById(prompt.agentId)
                            HStack(spacing: 4) {
                                Text("Agent:")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if let agent {
                                    Text(agent.name)
                                        .font(.caption)
                                        .foregroundStyle(AppColors.statusOk)
                                } else {
                                    Text("Missing")
                                        .font(.caption)
                                        .foregroundStyle(AppColors.statusError)
                                }
                            }
                            Text(String(prompt.promptText.prefix(50)) + (prompt.promptText.count > 50 ? "..." : ""))
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button {
                            deleteTarget = prompt
                        } label: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        editingPrompt = prompt
                        showEditor = true
                    }
                }
            }

            HStack {
                Text("\(sortedPrompts.count) prompts")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Prompts")
        .sheet(isPresented: $showEditor) {
            PromptEditView(viewModel: viewModel, prompt: editingPrompt) {
                showEditor = false
            }
            .frame(minWidth: 500, minHeight: 450)
        }
        .alert("Delete Prompt", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let prompt = deleteTarget {
                    var s = settings
                    s.prompts.removeAll { $0.id == prompt.id }
                    viewModel.updateSettings(s)
                }
                deleteTarget = nil
            }
        } message: {
            Text("Are you sure you want to delete \"\(deleteTarget?.name ?? "")\"?")
        }
    }
}

// MARK: - Prompt Edit

struct PromptEditView: View {
    @Bindable var viewModel: AppViewModel
    let prompt: Prompt?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var selectedAgentId = ""
    @State private var promptText = ""
    @State private var validationError: String?

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var isEditing: Bool { prompt != nil }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(isEditing ? "Edit Prompt" : "New Prompt")
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
                    SectionHeader(title: "Agent", icon: "person")
                    Picker("Agent", selection: $selectedAgentId) {
                        Text("Select an agent...").tag("")
                        ForEach(settings.agents) { agent in
                            Text("\(agent.name) (\(agent.provider?.displayName ?? "?"))").tag(agent.id)
                        }
                    }

                    Divider()

                    // Prompt text
                    SectionHeader(title: "Prompt Text", icon: "doc.text")

                    Text("Supported variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    TextEditor(text: $promptText)
                        .font(.body)
                        .frame(minHeight: 150)
                        .border(Color.secondary.opacity(0.3))

                    Text("\(promptText.count) characters")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                .padding()
            }
        }
        .onAppear { loadFields() }
    }

    private func loadFields() {
        guard let prompt else { return }
        name = prompt.name
        selectedAgentId = prompt.agentId
        promptText = prompt.promptText
    }

    private func save() {
        validationError = nil
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        guard !trimmedName.isEmpty else {
            validationError = "Name is required"
            return
        }
        guard !selectedAgentId.isEmpty else {
            validationError = "Agent is required"
            return
        }
        guard !promptText.trimmingCharacters(in: .whitespaces).isEmpty else {
            validationError = "Prompt text is required"
            return
        }

        // Case-insensitive uniqueness check for prompts
        let existing = settings.prompts.first {
            $0.name.caseInsensitiveCompare(trimmedName) == .orderedSame && $0.id != prompt?.id
        }
        if existing != nil {
            validationError = "A prompt with this name already exists"
            return
        }

        let updated = Prompt(
            id: prompt?.id ?? UUID().uuidString,
            name: trimmedName,
            agentId: selectedAgentId,
            promptText: promptText
        )

        var s = settings
        if let idx = s.prompts.firstIndex(where: { $0.id == updated.id }) {
            s.prompts[idx] = updated
        } else {
            s.prompts.append(updated)
        }
        viewModel.updateSettings(s)
        onDismiss()
    }
}
