import SwiftUI

/// Parameter override configuration for reports.
struct ReportParametersView: View {
    @Binding var parameters: AgentParameters?
    let onDismiss: () -> Void

    @State private var temperature: String = ""
    @State private var maxTokens: String = ""
    @State private var topP: String = ""
    @State private var topK: String = ""
    @State private var frequencyPenalty: String = ""
    @State private var presencePenalty: String = ""
    @State private var systemPrompt: String = ""
    @State private var seed: String = ""
    @State private var responseFormatJson = false
    @State private var searchEnabled = false
    @State private var returnCitations = true

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Report Parameters")
                    .font(.title2.bold())
                Spacer()
                Button("Clear") {
                    parameters = nil
                    onDismiss()
                }
                .foregroundStyle(.red)
                Button("Apply") {
                    applyParameters()
                    onDismiss()
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Generation parameters
                    SectionHeader(title: "Generation", icon: "slider.horizontal.3")

                    HStack(spacing: 12) {
                        parameterField("Temperature", text: $temperature, hint: "0.0 - 2.0")
                        parameterField("Max Tokens", text: $maxTokens, hint: "1 - ...")
                    }

                    HStack(spacing: 12) {
                        parameterField("Top P", text: $topP, hint: "0.0 - 1.0")
                        parameterField("Top K", text: $topK, hint: "1 - ...")
                    }

                    HStack(spacing: 12) {
                        parameterField("Frequency Penalty", text: $frequencyPenalty, hint: "-2.0 - 2.0")
                        parameterField("Presence Penalty", text: $presencePenalty, hint: "-2.0 - 2.0")
                    }

                    parameterField("Seed", text: $seed, hint: "Optional integer")

                    // System prompt
                    SectionHeader(title: "System Prompt", icon: "text.alignleft")

                    TextEditor(text: $systemPrompt)
                        .font(.body)
                        .frame(minHeight: 80)
                        .padding(4)
                        .background(Color(.textBackgroundColor))
                        .clipShape(RoundedRectangle(cornerRadius: 6))

                    // Toggles
                    SectionHeader(title: "Options", icon: "switch.2")

                    Toggle("JSON Response Format", isOn: $responseFormatJson)
                    Toggle("Search Enabled", isOn: $searchEnabled)
                    Toggle("Return Citations", isOn: $returnCitations)
                }
                .padding()
            }
        }
        .frame(width: 500, height: 500)
        .onAppear {
            loadFromParameters()
        }
    }

    private func parameterField(_ label: String, text: Binding<String>, hint: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(hint, text: text)
                .textFieldStyle(.roundedBorder)
                .frame(maxWidth: .infinity)
        }
    }

    private func loadFromParameters() {
        guard let params = parameters else { return }
        if let v = params.temperature { temperature = "\(v)" }
        if let v = params.maxTokens { maxTokens = "\(v)" }
        if let v = params.topP { topP = "\(v)" }
        if let v = params.topK { topK = "\(v)" }
        if let v = params.frequencyPenalty { frequencyPenalty = "\(v)" }
        if let v = params.presencePenalty { presencePenalty = "\(v)" }
        if let v = params.systemPrompt { systemPrompt = v }
        if let v = params.seed { seed = "\(v)" }
        responseFormatJson = params.responseFormatJson
        searchEnabled = params.searchEnabled
        returnCitations = params.returnCitations
    }

    private func applyParameters() {
        parameters = AgentParameters(
            temperature: Float(temperature),
            maxTokens: Int(maxTokens),
            topP: Float(topP),
            topK: Int(topK),
            frequencyPenalty: Float(frequencyPenalty),
            presencePenalty: Float(presencePenalty),
            systemPrompt: systemPrompt.isEmpty ? nil : systemPrompt,
            seed: Int(seed),
            responseFormatJson: responseFormatJson,
            searchEnabled: searchEnabled,
            returnCitations: returnCitations
        )
    }
}
