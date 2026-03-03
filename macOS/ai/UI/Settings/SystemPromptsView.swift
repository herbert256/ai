import SwiftUI

// MARK: - System Prompts List

struct SystemPromptsListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var editingPrompt: SystemPrompt?
    @State private var showEditor = false
    @State private var deleteTarget: SystemPrompt?

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var sortedPrompts: [SystemPrompt] {
        settings.systemPrompts.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Add System Prompt") {
                    editingPrompt = nil
                    showEditor = true
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            if sortedPrompts.isEmpty {
                EmptyStateView(
                    icon: "text.alignleft",
                    title: "No System Prompts",
                    message: "Create reusable system prompts for agents and flocks"
                )
            } else {
                List(sortedPrompts) { sp in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(sp.name)
                                .font(.subheadline.bold())
                            Text(String(sp.prompt.prefix(80)) + (sp.prompt.count > 80 ? "..." : ""))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                        Spacer()
                        Button {
                            deleteTarget = sp
                        } label: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        editingPrompt = sp
                        showEditor = true
                    }
                }
            }

            HStack {
                Text("\(sortedPrompts.count) system prompts")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
        }
        .navigationTitle("System Prompts")
        .sheet(isPresented: $showEditor) {
            SystemPromptEditView(viewModel: viewModel, systemPrompt: editingPrompt) {
                showEditor = false
            }
            .frame(minWidth: 500, minHeight: 400)
        }
        .alert("Delete System Prompt", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let sp = deleteTarget {
                    var s = settings
                    s.removeSystemPrompt(sp.id)
                    viewModel.updateSettings(s)
                }
                deleteTarget = nil
            }
        } message: {
            Text("Are you sure you want to delete \"\(deleteTarget?.name ?? "")\"? References will be cleared from agents, flocks, and swarms.")
        }
    }
}

// MARK: - System Prompt Edit

struct SystemPromptEditView: View {
    @Bindable var viewModel: AppViewModel
    let systemPrompt: SystemPrompt?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var prompt = ""
    @State private var validationError: String?

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var isEditing: Bool { systemPrompt != nil }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(isEditing ? "Edit System Prompt" : "New System Prompt")
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

            VStack(alignment: .leading, spacing: 12) {
                AppTextField(label: "Name", text: $name)

                if let error = validationError {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                }

                Text("Prompt")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                TextEditor(text: $prompt)
                    .font(.body)
                    .frame(minHeight: 150)
                    .border(Color.secondary.opacity(0.3))

                Text("\(prompt.count) characters")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .padding()
        }
        .onAppear { loadFields() }
    }

    private func loadFields() {
        guard let systemPrompt else { return }
        name = systemPrompt.name
        prompt = systemPrompt.prompt
    }

    private func save() {
        validationError = nil
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        guard !trimmedName.isEmpty else {
            validationError = "Name is required"
            return
        }
        guard !prompt.trimmingCharacters(in: .whitespaces).isEmpty else {
            validationError = "Prompt is required"
            return
        }

        let existing = settings.systemPrompts.first { $0.name == trimmedName && $0.id != systemPrompt?.id }
        if existing != nil {
            validationError = "A system prompt with this name already exists"
            return
        }

        let updated = SystemPrompt(
            id: systemPrompt?.id ?? UUID().uuidString,
            name: trimmedName,
            prompt: prompt
        )

        var s = settings
        if let idx = s.systemPrompts.firstIndex(where: { $0.id == updated.id }) {
            s.systemPrompts[idx] = updated
        } else {
            s.systemPrompts.append(updated)
        }
        viewModel.updateSettings(s)
        onDismiss()
    }
}
