import SwiftUI

// MARK: - Chat Hub

/// Chat navigation hub - select how to start a chat.
struct ChatHubView: View {
    @Bindable var viewModel: AppViewModel
    @State private var showProviderPicker = false
    @State private var showAgentPicker = false
    @State private var selectedProvider: AppService?
    @State private var selectedModel: String?
    @State private var chatActive = false

    private var settings: Settings { viewModel.uiState.aiSettings }

    var body: some View {
        if chatActive, let provider = selectedProvider, let model = selectedModel {
            ChatSessionView(
                viewModel: viewModel,
                provider: provider,
                model: model,
                onDismiss: { chatActive = false }
            )
        } else if let provider = selectedProvider, selectedModel == nil {
            ChatModelPickerView(
                provider: provider,
                settings: settings,
                onSelectModel: { model in
                    selectedModel = model
                    chatActive = true
                },
                onBack: { selectedProvider = nil }
            )
        } else if showAgentPicker {
            ChatAgentPickerView(
                viewModel: viewModel,
                onBack: { showAgentPicker = false }
            )
        } else {
            chatMenu
        }
    }

    private var chatMenu: some View {
        ScrollView {
            VStack(spacing: 16) {
                // New chat with provider
                SectionHeader(title: "Start New Chat", icon: "bubble.left.and.bubble.right")

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                    ForEach(settings.getActiveServices()) { service in
                        Button {
                            selectedProvider = service
                            selectedModel = nil
                        } label: {
                            HStack {
                                ProviderStateBadge(state: settings.getProviderState(service))
                                Text(service.displayName)
                                    .font(.subheadline)
                                    .lineLimit(1)
                                Spacer()
                            }
                            .padding(8)
                            .background(AppColors.cardBackground)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                        .buttonStyle(.plain)
                    }
                }

                if !settings.getConfiguredAgents().isEmpty {
                    Divider()
                    SectionHeader(title: "Chat with Agent", icon: "person.3")
                    Button("Select Agent") {
                        showAgentPicker = true
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding()
        }
        .navigationTitle("Chat")
    }
}

// MARK: - Model Picker

struct ChatModelPickerView: View {
    let provider: AppService
    let settings: Settings
    let onSelectModel: (String) -> Void
    let onBack: () -> Void

    @State private var searchText = ""

    private var models: [String] {
        let all = settings.getModels(provider)
        if searchText.isEmpty { return all }
        let lower = searchText.lowercased()
        return all.filter { $0.lowercased().contains(lower) }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Back") { onBack() }
                Text("Select Model - \(provider.displayName)")
                    .font(.headline)
                Spacer()
            }
            .padding()

            TextField("Search models...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal)

            List(models, id: \.self) { model in
                Button {
                    onSelectModel(model)
                } label: {
                    HStack {
                        Text(model)
                            .font(.body)
                        Spacer()
                        if model == settings.getModel(provider) {
                            Text("default")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .navigationTitle("Select Model")
    }
}

// MARK: - Agent Picker

struct ChatAgentPickerView: View {
    @Bindable var viewModel: AppViewModel
    let onBack: () -> Void

    @State private var chatAgent: Agent?
    @State private var chatActive = false

    private var settings: Settings { viewModel.uiState.aiSettings }

    var body: some View {
        if chatActive, let agent = chatAgent, let provider = agent.provider {
            let model = settings.getEffectiveModelForAgent(agent)
            let agentParams = settings.resolveAgentParameters(agent)
            let chatParams = ChatParameters(
                systemPrompt: agentParams.systemPrompt ?? "",
                temperature: agentParams.temperature,
                maxTokens: agentParams.maxTokens,
                topP: agentParams.topP,
                topK: agentParams.topK,
                frequencyPenalty: agentParams.frequencyPenalty,
                presencePenalty: agentParams.presencePenalty,
                searchEnabled: agentParams.searchEnabled,
                returnCitations: agentParams.returnCitations,
                searchRecency: agentParams.searchRecency
            )

            ChatSessionView(
                viewModel: viewModel,
                provider: provider,
                model: model,
                initialParams: chatParams,
                apiKeyOverride: settings.getEffectiveApiKeyForAgent(agent),
                onDismiss: { chatActive = false }
            )
        } else {
            VStack(spacing: 0) {
                HStack {
                    Button("Back") { onBack() }
                    Text("Select Agent")
                        .font(.headline)
                    Spacer()
                }
                .padding()

                List(settings.getConfiguredAgents()) { agent in
                    Button {
                        chatAgent = agent
                        chatActive = true
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(agent.name)
                                .font(.subheadline.bold())
                            Text("\(agent.provider?.displayName ?? "Unknown") / \(settings.getEffectiveModelForAgent(agent))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - Chat Session

/// Full chat session with streaming support.
struct ChatSessionView: View {
    @Bindable var viewModel: AppViewModel
    let provider: AppService
    let model: String
    var initialParams: ChatParameters? = nil
    var apiKeyOverride: String? = nil
    var initialMessages: [ChatMessage] = []
    var sessionId: String? = nil
    let onDismiss: () -> Void

    @State private var messages: [ChatMessage] = []
    @State private var inputText = ""
    @State private var isStreaming = false
    @State private var streamingContent = ""
    @State private var chatSessionId: String

    init(
        viewModel: AppViewModel,
        provider: AppService,
        model: String,
        initialParams: ChatParameters? = nil,
        apiKeyOverride: String? = nil,
        initialMessages: [ChatMessage] = [],
        sessionId: String? = nil,
        onDismiss: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.provider = provider
        self.model = model
        self.initialParams = initialParams
        self.apiKeyOverride = apiKeyOverride
        self.initialMessages = initialMessages
        self.sessionId = sessionId
        self.onDismiss = onDismiss
        self._chatSessionId = State(initialValue: sessionId ?? UUID().uuidString)
    }

    private var apiKey: String {
        apiKeyOverride ?? viewModel.uiState.aiSettings.getApiKey(provider)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Button("Back") { onDismiss() }
                Spacer()
                VStack(spacing: 2) {
                    Text(provider.displayName)
                        .font(.subheadline.bold())
                    Text(model)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(messages.count) messages")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(AppColors.cardBackground)

            Divider()

            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(messages) { message in
                            ChatBubble(message: message)
                                .id(message.id)
                        }

                        // Streaming indicator
                        if isStreaming {
                            ChatBubble(message: ChatMessage(role: "assistant", content: streamingContent.isEmpty ? "..." : streamingContent))
                                .id("streaming")
                        }
                    }
                    .padding()
                }
                .onChange(of: messages.count) { _, _ in
                    if let lastId = messages.last?.id {
                        withAnimation {
                            proxy.scrollTo(lastId, anchor: .bottom)
                        }
                    }
                }
                .onChange(of: streamingContent) { _, _ in
                    if isStreaming {
                        withAnimation {
                            proxy.scrollTo("streaming", anchor: .bottom)
                        }
                    }
                }
            }

            Divider()

            // Input area
            HStack(spacing: 8) {
                TextField("Type a message...", text: $inputText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...5)
                    .onSubmit { sendMessage() }
                    .disabled(isStreaming)

                Button(action: sendMessage) {
                    Image(systemName: isStreaming ? "stop.fill" : "arrow.up.circle.fill")
                        .font(.title2)
                        .foregroundStyle(isStreaming ? .red : AppColors.primary)
                }
                .buttonStyle(.plain)
                .disabled(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isStreaming)
            }
            .padding()
        }
        .onAppear {
            if let params = initialParams {
                viewModel.setChatParameters(params)
            }
            if !initialMessages.isEmpty {
                messages = initialMessages
            }
        }
    }

    private func sendMessage() {
        if isStreaming {
            // Stop streaming - not directly cancellable in this implementation
            return
        }

        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let userMessage = ChatMessage(role: "user", content: text)
        messages.append(userMessage)
        inputText = ""

        Task {
            await streamResponse()
        }
    }

    private func streamResponse() async {
        isStreaming = true
        streamingContent = ""

        let stream = viewModel.sendChatMessageStream(provider, apiKey, model, messages)

        var fullContent = ""
        do {
            for try await chunk in stream {
                fullContent += chunk
                streamingContent = fullContent
            }
        } catch {
            if fullContent.isEmpty {
                fullContent = "Error: \(error.localizedDescription)"
            }
        }

        isStreaming = false
        streamingContent = ""

        let assistantMessage = ChatMessage(role: "assistant", content: fullContent)
        messages.append(assistantMessage)

        // Record statistics
        let inputTokens = messages.filter { $0.role == "user" }.reduce(0) { $0 + AppViewModel.estimateTokens($1.content) }
        let outputTokens = AppViewModel.estimateTokens(fullContent)
        viewModel.recordChatStatistics(provider, model, inputTokens, outputTokens)

        // Save session
        await saveSession()
    }

    private func saveSession() async {
        let session = ChatSession(
            id: chatSessionId,
            providerId: provider.id,
            model: model,
            messages: messages,
            parameters: viewModel.uiState.chatParameters,
            updatedAt: Date()
        )
        await ChatHistoryManager.shared.save(session)
    }
}

// MARK: - Chat Bubble

struct ChatBubble: View {
    let message: ChatMessage

    private var isUser: Bool { message.role == "user" }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 60) }

            VStack(alignment: isUser ? .trailing : .leading, spacing: 4) {
                Text(isUser ? "You" : "Assistant")
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                if isUser {
                    Text(message.content)
                        .font(.body)
                        .padding(10)
                        .background(AppColors.primary.opacity(0.2))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .textSelection(.enabled)
                } else {
                    ResponseContentView(content: message.content)
                        .padding(10)
                        .background(AppColors.cardBackground)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }

            if !isUser { Spacer(minLength: 60) }
        }
    }
}
