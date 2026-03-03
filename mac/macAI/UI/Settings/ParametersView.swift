import SwiftUI

// MARK: - Parameters List

struct ParametersListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var editingParams: Parameters?
    @State private var showEditor = false
    @State private var deleteTarget: Parameters?

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var sortedParams: [Parameters] {
        settings.parameters.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Add Parameters") {
                    editingParams = nil
                    showEditor = true
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            if sortedParams.isEmpty {
                EmptyStateView(
                    icon: "slider.horizontal.3",
                    title: "No Parameters",
                    message: "Create parameter presets to reuse across agents and reports"
                )
            } else {
                List(sortedParams) { params in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(params.name)
                                .font(.subheadline.bold())
                            Text(paramsSummary(params))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button {
                            deleteTarget = params
                        } label: {
                            Image(systemName: "trash")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        editingParams = params
                        showEditor = true
                    }
                }
            }

            HStack {
                Text("\(sortedParams.count) presets")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Parameters")
        .sheet(isPresented: $showEditor) {
            ParametersEditView(viewModel: viewModel, params: editingParams) {
                showEditor = false
            }
            .frame(minWidth: 500, minHeight: 550)
        }
        .alert("Delete Parameters", isPresented: Binding(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let params = deleteTarget {
                    var s = settings
                    s.removeParameters(params.id)
                    viewModel.updateSettings(s)
                }
                deleteTarget = nil
            }
        } message: {
            Text("Are you sure you want to delete \"\(deleteTarget?.name ?? "")\"? References will be removed from agents, flocks, and swarms.")
        }
    }

    private func paramsSummary(_ p: Parameters) -> String {
        var parts: [String] = []
        if p.temperature != nil { parts.append("temp") }
        if p.maxTokens != nil { parts.append("maxTok") }
        if p.topP != nil { parts.append("topP") }
        if p.topK != nil { parts.append("topK") }
        if p.frequencyPenalty != nil { parts.append("freqP") }
        if p.presencePenalty != nil { parts.append("presP") }
        if p.seed != nil { parts.append("seed") }
        if p.systemPrompt != nil { parts.append("sysPrompt") }
        if p.responseFormatJson { parts.append("JSON") }
        if p.searchEnabled { parts.append("search") }
        if parts.isEmpty { return "No parameters configured" }
        return "\(parts.count) params: \(parts.joined(separator: ", "))"
    }
}

// MARK: - Parameters Edit

struct ParametersEditView: View {
    @Bindable var viewModel: AppViewModel
    let params: Parameters?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var tempEnabled = false
    @State private var temperature = ""
    @State private var maxTokEnabled = false
    @State private var maxTokens = ""
    @State private var topPEnabled = false
    @State private var topP = ""
    @State private var topKEnabled = false
    @State private var topK = ""
    @State private var freqPEnabled = false
    @State private var frequencyPenalty = ""
    @State private var presPEnabled = false
    @State private var presencePenalty = ""
    @State private var seedEnabled = false
    @State private var seed = ""
    @State private var sysPromptEnabled = false
    @State private var systemPrompt = ""
    @State private var responseFormatJson = false
    @State private var searchEnabled = false
    @State private var returnCitations = true
    @State private var searchRecency: String?
    @State private var validationError: String?

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var isEditing: Bool { params != nil }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(isEditing ? "Edit Parameters" : "New Parameters")
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

                    SectionHeader(title: "Parameters", icon: "slider.horizontal.3")

                    ParameterToggleField(label: "Temperature", enabled: $tempEnabled, value: $temperature, placeholder: "0.0 - 2.0")
                    ParameterToggleField(label: "Max Tokens", enabled: $maxTokEnabled, value: $maxTokens, placeholder: "1 - 128000")
                    ParameterToggleField(label: "Top P", enabled: $topPEnabled, value: $topP, placeholder: "0.0 - 1.0")
                    ParameterToggleField(label: "Top K", enabled: $topKEnabled, value: $topK, placeholder: "1 - 100")
                    ParameterToggleField(label: "Frequency Penalty", enabled: $freqPEnabled, value: $frequencyPenalty, placeholder: "-2.0 - 2.0")
                    ParameterToggleField(label: "Presence Penalty", enabled: $presPEnabled, value: $presencePenalty, placeholder: "-2.0 - 2.0")
                    ParameterToggleField(label: "Seed", enabled: $seedEnabled, value: $seed, placeholder: "Integer")

                    Divider()

                    // System Prompt
                    Toggle("System Prompt", isOn: $sysPromptEnabled)
                    if sysPromptEnabled {
                        TextEditor(text: $systemPrompt)
                            .font(.body)
                            .frame(minHeight: 60, maxHeight: 120)
                            .border(Color.secondary.opacity(0.3))
                        Text("\(systemPrompt.count) characters")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }

                    Divider()

                    SectionHeader(title: "Options", icon: "gearshape")

                    Toggle("JSON Response Format", isOn: $responseFormatJson)
                    Toggle("Search Enabled", isOn: $searchEnabled)
                    Toggle("Return Citations", isOn: $returnCitations)

                    if searchEnabled {
                        HStack(spacing: 8) {
                            Text("Search Recency:")
                                .font(.caption)
                            ForEach(["day", "week", "month", "year"], id: \.self) { period in
                                Button(period.capitalized) {
                                    searchRecency = (searchRecency == period) ? nil : period
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                                .tint(searchRecency == period ? AppColors.primary : .secondary)
                            }
                        }
                    }
                }
                .padding()
            }
        }
        .onAppear { loadFields() }
    }

    private func loadFields() {
        guard let params else { return }
        name = params.name
        if let v = params.temperature { tempEnabled = true; temperature = String(v) }
        if let v = params.maxTokens { maxTokEnabled = true; maxTokens = String(v) }
        if let v = params.topP { topPEnabled = true; topP = String(v) }
        if let v = params.topK { topKEnabled = true; topK = String(v) }
        if let v = params.frequencyPenalty { freqPEnabled = true; frequencyPenalty = String(v) }
        if let v = params.presencePenalty { presPEnabled = true; presencePenalty = String(v) }
        if let v = params.seed { seedEnabled = true; seed = String(v) }
        if let v = params.systemPrompt { sysPromptEnabled = true; systemPrompt = v }
        responseFormatJson = params.responseFormatJson
        searchEnabled = params.searchEnabled
        returnCitations = params.returnCitations
        searchRecency = params.searchRecency
    }

    private func save() {
        validationError = nil
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            validationError = "Name is required"
            return
        }

        let existing = settings.parameters.first { $0.name == trimmed && $0.id != params?.id }
        if existing != nil {
            validationError = "A parameter preset with this name already exists"
            return
        }

        let updated = Parameters(
            id: params?.id ?? UUID().uuidString,
            name: trimmed,
            temperature: tempEnabled ? Float(temperature) : nil,
            maxTokens: maxTokEnabled ? Int(maxTokens) : nil,
            topP: topPEnabled ? Float(topP) : nil,
            topK: topKEnabled ? Int(topK) : nil,
            frequencyPenalty: freqPEnabled ? Float(frequencyPenalty) : nil,
            presencePenalty: presPEnabled ? Float(presencePenalty) : nil,
            systemPrompt: sysPromptEnabled ? systemPrompt : nil,
            seed: seedEnabled ? Int(seed) : nil,
            responseFormatJson: responseFormatJson,
            searchEnabled: searchEnabled,
            returnCitations: returnCitations,
            searchRecency: searchEnabled ? searchRecency : nil
        )

        var s = settings
        if let idx = s.parameters.firstIndex(where: { $0.id == updated.id }) {
            s.parameters[idx] = updated
        } else {
            s.parameters.append(updated)
        }
        viewModel.updateSettings(s)
        onDismiss()
    }
}

// MARK: - Parameter Toggle Field

struct ParameterToggleField: View {
    let label: String
    @Binding var enabled: Bool
    @Binding var value: String
    var placeholder: String = ""

    var body: some View {
        HStack {
            Toggle(label, isOn: $enabled)
                .frame(width: 200, alignment: .leading)
            if enabled {
                TextField(placeholder, text: $value)
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 200)
            }
        }
    }
}
