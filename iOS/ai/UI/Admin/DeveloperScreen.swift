import SwiftUI

// MARK: - Developer Screen

struct DeveloperScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                NavigationLink(destination: ApiTestScreen()) {
                    SettingsListItemCard(title: "API Test", subtitle: "Test API connections", icon: "network")
                }

                NavigationLink(destination: TraceScreen()) {
                    SettingsListItemCard(title: "API Traces", subtitle: "View request/response logs", icon: "doc.text.magnifyingglass")
                }
            }
            .padding()
        }
        .background(AppColors.background)
        .navigationTitle("Developer Tools")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - API Test Screen

struct ApiTestScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    @State private var selectedProviderId = ""
    @State private var apiKey = ""
    @State private var model = ""
    @State private var prompt = "Return only the letter O, nothing more"
    @State private var systemPrompt = ""
    @State private var endpointUrl = ""

    // Parameters
    @State private var temperature = ""
    @State private var maxTokens = ""
    @State private var topP = ""
    @State private var topK = ""
    @State private var seed = ""

    @State private var isTesting = false
    @State private var testResult: String?
    @State private var showParams = false

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let providers = viewModel.getAllProviders().sorted { $0.displayName < $1.displayName }

        Form {
            Section("Provider") {
                Picker("Provider", selection: $selectedProviderId) {
                    Text("Select...").tag("")
                    ForEach(providers, id: \.id) { service in
                        Text(service.displayName).tag(service.id)
                    }
                }
                .onChange(of: selectedProviderId) { _, newValue in
                    guard !newValue.isEmpty else { return }
                    apiKey = settings.getApiKey(newValue)
                    model = settings.getModel(newValue)
                    if let service = viewModel.lookupService(newValue) {
                        endpointUrl = service.baseUrl + service.chatPath
                    }
                }
            }

            Section("Connection") {
                TextField("API Key", text: $apiKey)
                    .font(.caption)
                TextField("Model", text: $model)
                    .font(.caption)
                TextField("Endpoint URL", text: $endpointUrl)
                    .font(.caption)
            }

            Section("Prompt") {
                TextEditor(text: $prompt)
                    .frame(minHeight: 80)
                    .font(.body)
            }

            // Expandable parameters
            Section {
                DisclosureGroup("Parameters", isExpanded: $showParams) {
                    TextField("System Prompt", text: $systemPrompt)
                    TextField("Temperature", text: $temperature)
                        .keyboardType(.decimalPad)
                    TextField("Max Tokens", text: $maxTokens)
                        .keyboardType(.numberPad)
                    TextField("Top P", text: $topP)
                        .keyboardType(.decimalPad)
                    TextField("Top K", text: $topK)
                        .keyboardType(.numberPad)
                    TextField("Seed", text: $seed)
                        .keyboardType(.numberPad)
                }
            }

            Section {
                Button {
                    runTest()
                } label: {
                    HStack {
                        if isTesting { ProgressView().tint(AppColors.primary) }
                        Text(isTesting ? "Testing..." : "Submit")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.primary)
                .disabled(selectedProviderId.isEmpty || model.isEmpty || isTesting)
            }

            if let result = testResult {
                Section("Result") {
                    Text(result)
                        .font(.caption)
                        .foregroundStyle(result.starts(with: "Error") ? AppColors.error : AppColors.success)
                        .textSelection(.enabled)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("API Test")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func runTest() {
        guard let service = viewModel.lookupService(selectedProviderId) else { return }
        isTesting = true
        testResult = nil

        Task {
            let params = AgentParameters(
                temperature: Float(temperature),
                maxTokens: Int(maxTokens),
                topP: Float(topP),
                topK: Int(topK),
                systemPrompt: systemPrompt.isEmpty ? nil : systemPrompt,
                seed: Int(seed)
            )

            let response = await AnalysisRepository.shared.analyze(
                service: service,
                apiKey: apiKey,
                prompt: prompt,
                model: model,
                params: params
            )
            if let error = response.error {
                testResult = "Error: \(error)"
            } else {
                let preview = String((response.analysis ?? "No response").prefix(500))
                testResult = "OK\n\n\(preview)"
            }
            isTesting = false
        }
    }
}
