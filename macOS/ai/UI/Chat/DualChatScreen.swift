import SwiftUI

// MARK: - Dual Chat Setup

/// Setup screen for configuring a dual chat between two AI models.
struct DualChatSetupView: View {
    @Bindable var viewModel: AppViewModel
    @State private var subject = ""
    @State private var interactionCount = 10
    @State private var model1ProviderId = ""
    @State private var model1Name = ""
    @State private var model1SystemPrompt = ""
    @State private var model2ProviderId = ""
    @State private var model2Name = ""
    @State private var model2SystemPrompt = ""
    @State private var startSession = false

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var activeServices: [AppService] { settings.getActiveServices() }

    private var canStart: Bool {
        !subject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !model1ProviderId.isEmpty && !model1Name.isEmpty &&
        !model2ProviderId.isEmpty && !model2Name.isEmpty
    }

    var body: some View {
        if startSession {
            DualChatSessionView(viewModel: viewModel, onDismiss: { startSession = false })
        } else {
            setupForm
        }
    }

    private var setupForm: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Subject
                AppTextField(label: "Discussion Topic", text: $subject)

                // Interaction count
                HStack {
                    Text("Exchanges:")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Stepper("\(interactionCount)", value: $interactionCount, in: 1...50)
                }

                // Model 1
                modelSelector(
                    title: "Model 1",
                    providerId: $model1ProviderId,
                    modelName: $model1Name,
                    systemPrompt: $model1SystemPrompt
                )

                Divider()

                // Model 2
                modelSelector(
                    title: "Model 2",
                    providerId: $model2ProviderId,
                    modelName: $model2Name,
                    systemPrompt: $model2SystemPrompt
                )

                // Start button
                Button("Start Dual Chat") {
                    let config = DualChatConfig(
                        model1ProviderId: model1ProviderId,
                        model1Name: model1Name,
                        model1SystemPrompt: model1SystemPrompt,
                        model2ProviderId: model2ProviderId,
                        model2Name: model2Name,
                        model2SystemPrompt: model2SystemPrompt,
                        subject: subject,
                        interactionCount: interactionCount
                    )
                    viewModel.setDualChatConfig(config)
                    startSession = true
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canStart)
                .frame(maxWidth: .infinity)
            }
            .padding()
        }
        .navigationTitle("Dual Chat")
    }

    private func modelSelector(title: String, providerId: Binding<String>, modelName: Binding<String>, systemPrompt: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title: title, icon: "cpu")

            // Provider picker
            Picker("Provider", selection: providerId) {
                Text("Select...").tag("")
                ForEach(activeServices) { service in
                    Text(service.displayName).tag(service.id)
                }
            }

            // Model picker
            if let provider = AppService.findById(providerId.wrappedValue) {
                let models = settings.getModels(provider)
                Picker("Model", selection: modelName) {
                    Text("Select...").tag("")
                    ForEach(models, id: \.self) { model in
                        Text(model).tag(model)
                    }
                }
            }

            // System prompt
            TextField("System prompt (optional)", text: systemPrompt, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(2...4)
        }
        .padding(12)
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Dual Chat Session

/// Side-by-side dual chat session where two models converse.
struct DualChatSessionView: View {
    @Bindable var viewModel: AppViewModel
    let onDismiss: () -> Void

    @State private var messages: [(String, String)] = []  // (speaker, content)
    @State private var isRunning = false
    @State private var currentSpeaker = ""
    @State private var streamingContent = ""
    @State private var exchangeCount = 0

    private var config: DualChatConfig? { viewModel.uiState.dualChatConfig }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            if let config {
                HStack {
                    Button("Stop & Back") {
                        isRunning = false
                        onDismiss()
                    }
                    Spacer()
                    Text("\(config.model1Name) vs \(config.model2Name)")
                        .font(.subheadline.bold())
                    Spacer()
                    Text("\(exchangeCount)/\(config.interactionCount)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding()
                .background(AppColors.cardBackground)
            }

            Divider()

            // Conversation
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(messages.enumerated()), id: \.offset) { index, message in
                            DualChatBubble(speaker: message.0, content: message.1, isModel1: message.0 == config?.model1Name)
                                .id(index)
                        }

                        if isRunning && !streamingContent.isEmpty {
                            DualChatBubble(speaker: currentSpeaker, content: streamingContent, isModel1: currentSpeaker == config?.model1Name)
                                .id("streaming")
                        }
                    }
                    .padding()
                }
                .onChange(of: messages.count) { _, _ in
                    withAnimation {
                        proxy.scrollTo(messages.count - 1, anchor: .bottom)
                    }
                }
            }
        }
        .task {
            if let config {
                await runDualChat(config: config)
            }
        }
    }

    private func runDualChat(config: DualChatConfig) async {
        isRunning = true
        let settings = viewModel.uiState.aiSettings

        guard let provider1 = AppService.findById(config.model1ProviderId),
              let provider2 = AppService.findById(config.model2ProviderId) else {
            messages.append(("System", "Error: Could not find providers"))
            isRunning = false
            return
        }

        let apiKey1 = settings.getApiKey(provider1)
        let apiKey2 = settings.getApiKey(provider2)

        var model1Messages: [ChatMessage] = []
        var model2Messages: [ChatMessage] = []

        // Add system prompts
        if !config.model1SystemPrompt.isEmpty {
            model1Messages.append(ChatMessage(role: "system", content: config.model1SystemPrompt))
        }
        if !config.model2SystemPrompt.isEmpty {
            model2Messages.append(ChatMessage(role: "system", content: config.model2SystemPrompt))
        }

        // First prompt
        let firstPrompt = config.firstPrompt.replacingOccurrences(of: "%subject%", with: config.subject)
        model1Messages.append(ChatMessage(role: "user", content: firstPrompt))

        for exchange in 0..<config.interactionCount {
            guard isRunning else { break }

            // Model 1 speaks
            currentSpeaker = config.model1Name
            streamingContent = ""

            do {
                let response1 = try await viewModel.sendDualChatMessage(
                    service: provider1,
                    apiKey: apiKey1,
                    model: config.model1Name,
                    messages: model1Messages,
                    params: config.model1Params
                )

                messages.append((config.model1Name, response1))
                model1Messages.append(ChatMessage(role: "assistant", content: response1))

                // Prepare prompt for model 2
                let secondPrompt = config.secondPrompt.replacingOccurrences(of: "%answer%", with: response1)
                model2Messages.append(ChatMessage(role: "user", content: secondPrompt))

                guard isRunning else { break }

                // Model 2 speaks
                currentSpeaker = config.model2Name
                streamingContent = ""

                let response2 = try await viewModel.sendDualChatMessage(
                    service: provider2,
                    apiKey: apiKey2,
                    model: config.model2Name,
                    messages: model2Messages,
                    params: config.model2Params
                )

                messages.append((config.model2Name, response2))
                model2Messages.append(ChatMessage(role: "assistant", content: response2))

                // Prepare next prompt for model 1
                let nextPrompt = config.secondPrompt.replacingOccurrences(of: "%answer%", with: response2)
                model1Messages.append(ChatMessage(role: "user", content: nextPrompt))

                exchangeCount = exchange + 1
            } catch {
                messages.append(("System", "Error: \(error.localizedDescription)"))
                break
            }
        }

        isRunning = false
    }
}

// MARK: - Dual Chat Bubble

struct DualChatBubble: View {
    let speaker: String
    let content: String
    let isModel1: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(speaker)
                .font(.caption.bold())
                .foregroundStyle(isModel1 ? AppColors.primary : AppColors.statusOk)

            Text(content)
                .font(.body)
                .textSelection(.enabled)
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(isModel1 ? AppColors.primary.opacity(0.1) : AppColors.statusOk.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}
