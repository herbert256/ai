import SwiftUI

// MARK: - Dual Chat Setup Screen

struct DualChatSetupScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @State private var model1ProviderId = ""
    @State private var model1Name = ""
    @State private var model2ProviderId = ""
    @State private var model2Name = ""
    @State private var subject = ""
    @State private var interactionCount = 10
    @State private var navigateToSession = false

    var body: some View {
        Form {
            Section("Model 1") {
                Picker("Provider", selection: $model1ProviderId) {
                    Text("Select...").tag("")
                    ForEach(activeProviders, id: \.id) { service in
                        Text(service.displayName).tag(service.id)
                    }
                }
                if !model1ProviderId.isEmpty {
                    TextField("Model name", text: $model1Name)
                }
            }

            Section("Model 2") {
                Picker("Provider", selection: $model2ProviderId) {
                    Text("Select...").tag("")
                    ForEach(activeProviders, id: \.id) { service in
                        Text(service.displayName).tag(service.id)
                    }
                }
                if !model2ProviderId.isEmpty {
                    TextField("Model name", text: $model2Name)
                }
            }

            Section("Conversation") {
                TextField("Subject", text: $subject)
                Stepper("Interactions: \(interactionCount)", value: $interactionCount, in: 2...50)
            }

            Section {
                Button("Start Dual Chat") {
                    let config = DualChatConfig(
                        model1ProviderId: model1ProviderId,
                        model1Name: model1Name,
                        model2ProviderId: model2ProviderId,
                        model2Name: model2Name,
                        subject: subject,
                        interactionCount: interactionCount
                    )
                    viewModel.setDualChatConfig(config)
                    navigateToSession = true
                }
                .disabled(model1ProviderId.isEmpty || model1Name.isEmpty || model2ProviderId.isEmpty || model2Name.isEmpty || subject.isEmpty)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Dual AI Chat")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $navigateToSession) {
            DualChatSessionScreen()
        }
    }

    private var activeProviders: [AppService] {
        viewModel.getAllProviders().filter { !viewModel.uiState.aiSettings.getApiKey($0.id).isEmpty }
    }
}

// MARK: - Dual Chat Session Screen

struct DualChatSessionScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @State private var messages: [(sender: Int, content: String)] = []
    @State private var isRunning = false
    @State private var runTask: Task<Void, Never>?

    var body: some View {
        let config = viewModel.uiState.dualChatConfig

        VStack(spacing: 0) {
            if let config = config {
                // Header
                HStack {
                    VStack(alignment: .leading) {
                        Text(config.model1Name)
                            .font(.caption)
                            .foregroundStyle(AppColors.primary)
                    }
                    Spacer()
                    Text("vs")
                        .font(.caption)
                        .foregroundStyle(AppColors.dimText)
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text(config.model2Name)
                            .font(.caption)
                            .foregroundStyle(AppColors.secondary)
                    }
                }
                .padding()
                .background(AppColors.cardBackground)

                // Messages
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(messages.indices, id: \.self) { idx in
                            let msg = messages[idx]
                            HStack {
                                if msg.sender == 2 { Spacer(minLength: 40) }
                                Text(msg.content)
                                    .font(.body)
                                    .foregroundStyle(AppColors.onSurface)
                                    .padding(10)
                                    .background(msg.sender == 1 ? AppColors.primaryContainer : AppColors.secondaryContainer)
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                    .textSelection(.enabled)
                                if msg.sender == 1 { Spacer(minLength: 40) }
                            }
                        }
                    }
                    .padding()
                }

                // Controls
                HStack {
                    if isRunning {
                        Button("Stop") {
                            runTask?.cancel()
                            isRunning = false
                        }
                        .buttonStyle(.bordered)
                    } else {
                        Button("Start") {
                            startConversation(config: config)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.primary)
                        .disabled(messages.count >= config.interactionCount * 2)
                    }

                    Text("\(messages.count / 2) / \(config.interactionCount)")
                        .font(.caption)
                        .foregroundStyle(AppColors.dimText)
                }
                .padding()
            } else {
                EmptyStateView(icon: "bubble.left.and.bubble.right", title: "No config")
            }
        }
        .background(AppColors.background)
        .navigationTitle("Dual Chat")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func startConversation(config: DualChatConfig) {
        isRunning = true
        runTask = Task {
            guard let service1 = viewModel.lookupService(config.model1ProviderId),
                  let service2 = viewModel.lookupService(config.model2ProviderId) else {
                isRunning = false
                return
            }

            let apiKey1 = viewModel.uiState.aiSettings.getApiKey(config.model1ProviderId)
            let apiKey2 = viewModel.uiState.aiSettings.getApiKey(config.model2ProviderId)

            let firstPrompt = config.firstPrompt.replacingOccurrences(of: "%subject%", with: config.subject)

            do {
                // First message
                let response1 = try await AnalysisRepository.shared.sendChatMessage(
                    service: service1, apiKey: apiKey1, model: config.model1Name,
                    messages: [ChatMessage(role: ChatMessage.roleUser, content: firstPrompt)],
                    params: config.model1Params
                )
                await MainActor.run { messages.append((sender: 1, content: response1)) }

                // Subsequent interactions
                for _ in 1..<config.interactionCount {
                    if Task.isCancelled { break }

                    let lastResponse = messages.last?.content ?? ""
                    let prompt2 = config.secondPrompt.replacingOccurrences(of: "%answer%", with: lastResponse)

                    let response2 = try await AnalysisRepository.shared.sendChatMessage(
                        service: service2, apiKey: apiKey2, model: config.model2Name,
                        messages: [ChatMessage(role: ChatMessage.roleUser, content: prompt2)],
                        params: config.model2Params
                    )
                    await MainActor.run { messages.append((sender: 2, content: response2)) }

                    if Task.isCancelled { break }

                    let prompt1 = config.secondPrompt.replacingOccurrences(of: "%answer%", with: response2)
                    let nextResponse1 = try await AnalysisRepository.shared.sendChatMessage(
                        service: service1, apiKey: apiKey1, model: config.model1Name,
                        messages: [ChatMessage(role: ChatMessage.roleUser, content: prompt1)],
                        params: config.model1Params
                    )
                    await MainActor.run { messages.append((sender: 1, content: nextResponse1)) }
                }
            } catch {
                await MainActor.run {
                    messages.append((sender: 0, content: "Error: \(error.localizedDescription)"))
                }
            }

            await MainActor.run { isRunning = false }
        }
    }
}
