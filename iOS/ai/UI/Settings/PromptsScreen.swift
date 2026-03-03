import SwiftUI

// MARK: - Prompts Screen

struct PromptsScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let prompts = settings.prompts.sorted { $0.name.lowercased() < $1.name.lowercased() }

        List {
            // Info card
            CardView {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Available Variables")
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundStyle(AppColors.onSurface)
                    Text("@MODEL@ @PROVIDER@ @AGENT@ @SWARM@ @NOW@")
                        .font(.caption2)
                        .foregroundStyle(AppColors.accentBlue)
                }
            }

            ForEach(prompts) { prompt in
                NavigationLink(destination: PromptEditScreen(promptId: prompt.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(prompt.name)
                            .foregroundStyle(AppColors.onSurface)
                        if let agent = settings.getAgentById(prompt.agentId) {
                            Text(agent.name)
                                .font(.caption)
                                .foregroundStyle(AppColors.dimText)
                        } else {
                            Text("Agent not found")
                                .font(.caption)
                                .foregroundStyle(AppColors.error)
                        }
                        Text(String(prompt.promptText.prefix(50)))
                            .font(.caption2)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
            }
            .onDelete { indexSet in
                let sorted = prompts
                for idx in indexSet {
                    viewModel.uiState.aiSettings.prompts.removeAll { $0.id == sorted[idx].id }
                }
                viewModel.saveAiSettings()
            }

            if prompts.isEmpty {
                EmptyStateView(icon: "doc.text", title: "No prompts configured")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Internal Prompts")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(destination: PromptEditScreen(promptId: nil)) {
                    Image(systemName: "plus")
                }
            }
        }
    }
}

// MARK: - Prompt Edit Screen

struct PromptEditScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @Environment(\.dismiss) private var dismiss

    let promptId: String?

    @State private var name = ""
    @State private var agentId = ""
    @State private var promptText = ""
    @State private var initialized = false

    private var isNew: Bool { promptId == nil }

    private var canSave: Bool {
        !name.isEmpty && !agentId.isEmpty && !promptText.isEmpty
    }

    var body: some View {
        let settings = viewModel.uiState.aiSettings

        Form {
            Section("Prompt Name") {
                TextField("Name", text: $name)
            }

            Section("Agent") {
                Picker("Agent", selection: $agentId) {
                    Text("Select...").tag("")
                    ForEach(settings.agents.sorted { $0.name < $1.name }) { agent in
                        Text(agent.name).tag(agent.id)
                    }
                }
            }

            Section("Prompt Text") {
                TextEditor(text: $promptText)
                    .frame(minHeight: 150)
                    .font(.body)

                Text("Variables: @MODEL@ @PROVIDER@ @AGENT@ @SWARM@ @NOW@")
                    .font(.caption2)
                    .foregroundStyle(AppColors.dimText)
            }

            Section {
                Button(isNew ? "Create Prompt" : "Save Prompt") {
                    savePrompt()
                }
                .disabled(!canSave)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle(isNew ? "New Prompt" : "Edit Prompt")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !initialized, let prompt = promptId.flatMap({ viewModel.uiState.aiSettings.getPromptById($0) }) else {
                initialized = true
                return
            }
            name = prompt.name
            agentId = prompt.agentId
            promptText = prompt.promptText
            initialized = true
        }
    }

    private func savePrompt() {
        let prompt = Prompt(
            id: promptId ?? UUID().uuidString,
            name: name,
            agentId: agentId,
            promptText: promptText
        )
        if isNew {
            viewModel.uiState.aiSettings.prompts.append(prompt)
        } else if let idx = viewModel.uiState.aiSettings.prompts.firstIndex(where: { $0.id == prompt.id }) {
            viewModel.uiState.aiSettings.prompts[idx] = prompt
        }
        viewModel.saveAiSettings()
        dismiss()
    }
}
