import SwiftUI

// MARK: - Parameters Screen

struct ParametersScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        let params = viewModel.uiState.aiSettings.parameters.sorted { $0.name.lowercased() < $1.name.lowercased() }

        List {
            ForEach(params) { p in
                NavigationLink(destination: ParametersEditScreen(parametersId: p.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.name)
                            .foregroundStyle(AppColors.onSurface)
                        Text("\(configuredCount(p)) parameters configured")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                    }
                }
            }
            .onDelete { indexSet in
                let sorted = params
                for idx in indexSet {
                    viewModel.uiState.aiSettings.removeParameters(sorted[idx].id)
                }
                viewModel.saveAiSettings()
            }

            if params.isEmpty {
                EmptyStateView(icon: "slider.horizontal.3", title: "No parameter presets")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Parameters")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(destination: ParametersEditScreen(parametersId: nil)) {
                    Image(systemName: "plus")
                }
            }
        }
    }

    private func configuredCount(_ p: Parameters) -> Int {
        var count = 0
        if p.temperature != nil { count += 1 }
        if p.maxTokens != nil { count += 1 }
        if p.topP != nil { count += 1 }
        if p.topK != nil { count += 1 }
        if p.frequencyPenalty != nil { count += 1 }
        if p.presencePenalty != nil { count += 1 }
        if p.systemPrompt != nil { count += 1 }
        if p.seed != nil { count += 1 }
        if p.responseFormatJson { count += 1 }
        if p.searchEnabled { count += 1 }
        if p.searchRecency != nil { count += 1 }
        return count
    }
}

// MARK: - Parameters Edit Screen

struct ParametersEditScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @Environment(\.dismiss) private var dismiss

    let parametersId: String?

    // Name
    @State private var name = ""

    // Toggle + value pairs
    @State private var tempEnabled = false
    @State private var temperature: String = ""
    @State private var maxTokensEnabled = false
    @State private var maxTokens: String = ""
    @State private var topPEnabled = false
    @State private var topP: String = ""
    @State private var topKEnabled = false
    @State private var topK: String = ""
    @State private var freqPenEnabled = false
    @State private var frequencyPenalty: String = ""
    @State private var presPenEnabled = false
    @State private var presencePenalty: String = ""
    @State private var seedEnabled = false
    @State private var seed: String = ""
    @State private var sysPromptEnabled = false
    @State private var systemPrompt: String = ""

    // Boolean options
    @State private var responseFormatJson = false
    @State private var searchEnabled = false
    @State private var returnCitations = true

    // Search recency
    @State private var searchRecencyEnabled = false
    @State private var searchRecency = "month"

    @State private var initialized = false

    private var isNew: Bool { parametersId == nil }

    private var canSave: Bool { !name.isEmpty }

    var body: some View {
        Form {
            Section("Name") {
                TextField("Preset name", text: $name)
            }

            Section("Numeric Parameters") {
                ParameterToggleRow(label: "Temperature", description: "0.0-2.0", enabled: $tempEnabled, value: $temperature, keyboard: .decimalPad)
                ParameterToggleRow(label: "Max Tokens", description: "Maximum output tokens", enabled: $maxTokensEnabled, value: $maxTokens, keyboard: .numberPad)
                ParameterToggleRow(label: "Top P", description: "0.0-1.0", enabled: $topPEnabled, value: $topP, keyboard: .decimalPad)
                ParameterToggleRow(label: "Top K", description: "Integer", enabled: $topKEnabled, value: $topK, keyboard: .numberPad)
                ParameterToggleRow(label: "Frequency Penalty", description: "-2.0 to 2.0", enabled: $freqPenEnabled, value: $frequencyPenalty, keyboard: .decimalPad)
                ParameterToggleRow(label: "Presence Penalty", description: "-2.0 to 2.0", enabled: $presPenEnabled, value: $presencePenalty, keyboard: .decimalPad)
                ParameterToggleRow(label: "Seed", description: "Integer", enabled: $seedEnabled, value: $seed, keyboard: .numberPad)
            }

            Section("System Prompt") {
                Toggle("Enable System Prompt", isOn: $sysPromptEnabled)
                    .tint(AppColors.primary)
                if sysPromptEnabled {
                    TextEditor(text: $systemPrompt)
                        .frame(minHeight: 80)
                        .font(.caption)
                }
            }

            Section("Options") {
                Toggle("JSON Response Format", isOn: $responseFormatJson)
                    .tint(AppColors.primary)
                Toggle("Web Search (xAI, Perplexity)", isOn: $searchEnabled)
                    .tint(AppColors.primary)
                Toggle("Return Citations (Perplexity)", isOn: $returnCitations)
                    .tint(AppColors.primary)
            }

            Section("Search Recency") {
                Toggle("Enable Search Recency", isOn: $searchRecencyEnabled)
                    .tint(AppColors.primary)
                if searchRecencyEnabled {
                    Picker("Recency", selection: $searchRecency) {
                        Text("Day").tag("day")
                        Text("Week").tag("week")
                        Text("Month").tag("month")
                        Text("Year").tag("year")
                    }
                    .pickerStyle(.segmented)
                }
            }

            Section {
                Button(isNew ? "Create Preset" : "Save Preset") {
                    saveParameters()
                }
                .disabled(!canSave)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle(isNew ? "New Parameters" : "Edit Parameters")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !initialized, let p = parametersId.flatMap({ viewModel.uiState.aiSettings.getParametersById($0) }) else {
                initialized = true
                return
            }
            name = p.name
            if let v = p.temperature { tempEnabled = true; temperature = String(v) }
            if let v = p.maxTokens { maxTokensEnabled = true; maxTokens = String(v) }
            if let v = p.topP { topPEnabled = true; topP = String(v) }
            if let v = p.topK { topKEnabled = true; topK = String(v) }
            if let v = p.frequencyPenalty { freqPenEnabled = true; frequencyPenalty = String(v) }
            if let v = p.presencePenalty { presPenEnabled = true; presencePenalty = String(v) }
            if let v = p.seed { seedEnabled = true; seed = String(v) }
            if let v = p.systemPrompt, !v.isEmpty { sysPromptEnabled = true; systemPrompt = v }
            responseFormatJson = p.responseFormatJson
            searchEnabled = p.searchEnabled
            returnCitations = p.returnCitations
            if let v = p.searchRecency { searchRecencyEnabled = true; searchRecency = v }
            initialized = true
        }
    }

    private func saveParameters() {
        let params = Parameters(
            id: parametersId ?? UUID().uuidString,
            name: name,
            temperature: tempEnabled ? Float(temperature) : nil,
            maxTokens: maxTokensEnabled ? Int(maxTokens) : nil,
            topP: topPEnabled ? Float(topP) : nil,
            topK: topKEnabled ? Int(topK) : nil,
            frequencyPenalty: freqPenEnabled ? Float(frequencyPenalty) : nil,
            presencePenalty: presPenEnabled ? Float(presencePenalty) : nil,
            systemPrompt: sysPromptEnabled ? systemPrompt : nil,
            seed: seedEnabled ? Int(seed) : nil,
            responseFormatJson: responseFormatJson,
            searchEnabled: searchEnabled,
            returnCitations: returnCitations,
            searchRecency: searchRecencyEnabled ? searchRecency : nil
        )

        if isNew {
            viewModel.uiState.aiSettings.parameters.append(params)
        } else if let idx = viewModel.uiState.aiSettings.parameters.firstIndex(where: { $0.id == params.id }) {
            viewModel.uiState.aiSettings.parameters[idx] = params
        }
        viewModel.saveAiSettings()
        dismiss()
    }
}

// MARK: - Parameter Toggle Row

private struct ParameterToggleRow: View {
    let label: String
    let description: String
    @Binding var enabled: Bool
    @Binding var value: String
    var keyboard: UIKeyboardType = .decimalPad

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                VStack(alignment: .leading) {
                    Text(label)
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurface)
                    Text(description)
                        .font(.caption2)
                        .foregroundStyle(AppColors.dimText)
                }
                Spacer()
                Toggle("", isOn: $enabled)
                    .labelsHidden()
                    .tint(AppColors.primary)
            }
            if enabled {
                TextField(label, text: $value)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(keyboard)
                    .font(.caption)
            }
        }
    }
}
