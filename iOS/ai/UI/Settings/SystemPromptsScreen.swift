import SwiftUI

// MARK: - System Prompts Screen

struct SystemPromptsScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        let systemPrompts = viewModel.uiState.aiSettings.systemPrompts.sorted { $0.name.lowercased() < $1.name.lowercased() }

        List {
            ForEach(systemPrompts) { sp in
                NavigationLink(destination: SystemPromptEditScreen(systemPromptId: sp.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(sp.name)
                            .foregroundStyle(AppColors.onSurface)
                        Text(String(sp.prompt.prefix(60)))
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                            .lineLimit(1)
                    }
                }
            }
            .onDelete { indexSet in
                let sorted = systemPrompts
                for idx in indexSet {
                    viewModel.uiState.aiSettings.removeSystemPrompt(sorted[idx].id)
                }
                viewModel.saveAiSettings()
            }

            if systemPrompts.isEmpty {
                EmptyStateView(icon: "text.bubble", title: "No system prompts configured")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("System Prompts")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(destination: SystemPromptEditScreen(systemPromptId: nil)) {
                    Image(systemName: "plus")
                }
            }
        }
    }
}

// MARK: - System Prompt Edit Screen

struct SystemPromptEditScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @Environment(\.dismiss) private var dismiss

    let systemPromptId: String?

    @State private var name = ""
    @State private var prompt = ""
    @State private var initialized = false

    private var isNew: Bool { systemPromptId == nil }

    private var canSave: Bool { !name.isEmpty }

    var body: some View {
        Form {
            Section("Name") {
                TextField("System prompt name", text: $name)
            }

            Section("System Prompt") {
                TextEditor(text: $prompt)
                    .frame(minHeight: 150)
                    .font(.body)

                Text("\(prompt.count) characters")
                    .font(.caption2)
                    .foregroundStyle(AppColors.dimText)
            }

            Section {
                Button(isNew ? "Create System Prompt" : "Save System Prompt") {
                    saveSystemPrompt()
                }
                .disabled(!canSave)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle(isNew ? "New System Prompt" : "Edit System Prompt")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !initialized, let sp = systemPromptId.flatMap({ viewModel.uiState.aiSettings.getSystemPromptById($0) }) else {
                initialized = true
                return
            }
            name = sp.name
            prompt = sp.prompt
            initialized = true
        }
    }

    private func saveSystemPrompt() {
        let sp = SystemPrompt(
            id: systemPromptId ?? UUID().uuidString,
            name: name,
            prompt: prompt
        )
        if isNew {
            viewModel.uiState.aiSettings.systemPrompts.append(sp)
        } else if let idx = viewModel.uiState.aiSettings.systemPrompts.firstIndex(where: { $0.id == sp.id }) {
            viewModel.uiState.aiSettings.systemPrompts[idx] = sp
        }
        viewModel.saveAiSettings()
        dismiss()
    }
}
