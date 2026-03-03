import SwiftUI

/// Developer mode tools - API test, raw request editing.
struct DeveloperView: View {
    @Bindable var viewModel: AppViewModel
    @State private var selectedProviderId = ""
    @State private var apiKey = ""
    @State private var model = ""
    @State private var prompt = ""
    @State private var endpointUrl = ""
    @State private var systemPrompt = ""
    @State private var temperature = ""
    @State private var maxTokens = ""
    @State private var isSending = false
    @State private var rawJson = ""
    @State private var showRawEditor = false
    @State private var resultTraceId: String?
    @State private var showTrace = false

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var selectedProvider: AppService? { AppService.findById(selectedProviderId) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "API Test", icon: "terminal")

                // Provider
                Picker("Provider", selection: $selectedProviderId) {
                    Text("Select...").tag("")
                    ForEach(AppService.entries) { service in
                        Text(service.displayName).tag(service.id)
                    }
                }
                .onChange(of: selectedProviderId) { _, _ in loadProviderDefaults() }

                // Endpoint URL
                AppTextField(label: "Endpoint URL", text: $endpointUrl)

                // API Key
                AppTextField(label: "API Key", text: $apiKey, isSecure: true)

                // Model
                HStack {
                    AppTextField(label: "Model", text: $model)
                    if let provider = selectedProvider {
                        let models = settings.getModels(provider)
                        if !models.isEmpty {
                            Menu("Models") {
                                ForEach(models, id: \.self) { m in
                                    Button(m) { model = m }
                                }
                            }
                            .controlSize(.small)
                        }
                    }
                }

                Divider()

                // Prompt
                SectionHeader(title: "Request", icon: "text.alignleft")
                AppTextField(label: "System Prompt (optional)", text: $systemPrompt, axis: .vertical)
                AppTextField(label: "Prompt", text: $prompt, axis: .vertical)

                // Optional parameters
                DisclosureGroup("Parameters") {
                    HStack {
                        AppTextField(label: "Temperature", text: $temperature)
                        AppTextField(label: "Max Tokens", text: $maxTokens)
                    }
                }

                Divider()

                // Actions
                HStack(spacing: 12) {
                    Button("Send Request") {
                        sendRequest()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(selectedProviderId.isEmpty || apiKey.isEmpty || model.isEmpty || prompt.isEmpty || isSending)

                    Button("Edit Raw JSON") {
                        buildRawJson()
                        showRawEditor = true
                    }
                    .buttonStyle(.bordered)
                    .disabled(selectedProviderId.isEmpty)

                    if isSending {
                        ProgressView()
                            .controlSize(.small)
                    }
                }

                if let traceId = resultTraceId {
                    HStack {
                        Text("Request completed - trace: \(traceId)")
                            .font(.caption)
                            .foregroundStyle(AppColors.statusOk)
                        Button("View Trace") {
                            showTrace = true
                        }
                        .controlSize(.small)
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Developer")
        .sheet(isPresented: $showRawEditor) {
            RawJsonEditorView(json: $rawJson) {
                showRawEditor = false
            } onSend: {
                showRawEditor = false
                sendRawRequest()
            }
            .frame(minWidth: 500, minHeight: 400)
        }
    }

    private func loadProviderDefaults() {
        guard let provider = selectedProvider else { return }
        apiKey = settings.getApiKey(provider)
        model = settings.getModel(provider)
        endpointUrl = settings.getEffectiveEndpointUrl(provider)
    }

    private func sendRequest() {
        guard let provider = selectedProvider else { return }
        isSending = true
        resultTraceId = nil
        Task {
            let params = AgentParameters(
                temperature: Float(temperature),
                maxTokens: Int(maxTokens),
                systemPrompt: systemPrompt.isEmpty ? nil : systemPrompt
            )
            let agent = Agent(
                name: "dev-test",
                providerId: provider.id,
                model: model,
                apiKey: apiKey
            )
            _ = await AnalysisRepository.shared.analyzeWithAgent(
                agent: agent,
                content: "",
                prompt: prompt,
                settings: settings,
                overrideParams: params
            )
            isSending = false
            resultTraceId = "latest"
        }
    }

    private func buildRawJson() {
        var dict: [String: Any] = [
            "model": model,
            "messages": [
                ["role": "user", "content": prompt]
            ]
        ]
        if !systemPrompt.isEmpty {
            dict["messages"] = [
                ["role": "system", "content": systemPrompt],
                ["role": "user", "content": prompt]
            ]
        }
        if let temp = Float(temperature) { dict["temperature"] = temp }
        if let maxTok = Int(maxTokens) { dict["max_tokens"] = maxTok }

        if let data = try? JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys]),
           let str = String(data: data, encoding: .utf8) {
            rawJson = str
        }
    }

    private func sendRawRequest() {
        guard let provider = selectedProvider else { return }
        isSending = true
        resultTraceId = nil
        Task {
            let agent = Agent(
                name: "dev-test-raw",
                providerId: provider.id,
                model: model,
                apiKey: apiKey
            )
            _ = await AnalysisRepository.shared.analyzeWithAgent(
                agent: agent,
                content: "",
                prompt: prompt,
                settings: settings
            )
            isSending = false
            resultTraceId = "latest"
        }
    }
}

// MARK: - Raw JSON Editor

struct RawJsonEditorView: View {
    @Binding var json: String
    let onDismiss: () -> Void
    let onSend: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Edit Raw JSON")
                    .font(.headline)
                Spacer()
                Button("Cancel") { onDismiss() }
                    .keyboardShortcut(.escape, modifiers: [])
                Button("Send") { onSend() }
                    .buttonStyle(.borderedProminent)
            }
            .padding()

            Divider()

            TextEditor(text: $json)
                .font(.system(.body, design: .monospaced))
                .padding(4)
        }
    }
}
