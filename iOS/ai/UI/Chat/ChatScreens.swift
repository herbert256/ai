import SwiftUI

// MARK: - Chats Hub Screen

struct ChatsHubScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SectionHeader(title: "Start New Chat")
                    .padding(.horizontal)

                // Provider selection
                let providers = viewModel.getAllProviders()
                let settings = viewModel.uiState.aiSettings

                ForEach(providers.filter { !settings.getApiKey($0.id).isEmpty }, id: \.id) { service in
                    NavigationLink(destination: ChatModelSelectScreen(service: service)) {
                        SettingsListItemCard(
                            title: service.displayName,
                            subtitle: settings.getModel(service.id),
                            icon: "bubble.left.fill"
                        )
                    }
                    .padding(.horizontal)
                }

                // Agent chat
                if !settings.agents.isEmpty {
                    SectionHeader(title: "Chat with Agent")
                        .padding(.horizontal)

                    ForEach(settings.agents) { agent in
                        NavigationLink(destination: ChatSessionScreen(
                            providerId: agent.providerId,
                            model: settings.getEffectiveModelForAgent(agent),
                            agentName: agent.name
                        )) {
                            SettingsListItemCard(
                                title: agent.name,
                                subtitle: "\(agent.providerId) / \(agent.model)",
                                icon: "person.circle.fill"
                            )
                        }
                        .padding(.horizontal)
                    }
                }

                Divider().background(AppColors.outlineVariant)

                // Dual Chat
                NavigationLink(destination: DualChatSetupScreen()) {
                    SettingsListItemCard(title: "Dual AI Chat", subtitle: "Two AIs in conversation", icon: "bubble.left.and.bubble.right.fill")
                }
                .padding(.horizontal)

                // Chat History
                NavigationLink(destination: ChatHistoryScreen()) {
                    SettingsListItemCard(title: "Chat History", icon: "clock.fill")
                }
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Chat Model Select Screen

struct ChatModelSelectScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    let service: AppService

    var body: some View {
        let settings = viewModel.uiState.aiSettings
        let models = settings.getModels(service.id)

        List {
            if models.isEmpty {
                Text("No models available")
                    .foregroundStyle(AppColors.dimText)
            } else {
                ForEach(models, id: \.self) { model in
                    NavigationLink(destination: ChatSessionScreen(providerId: service.id, model: model)) {
                        Text(model)
                            .foregroundStyle(AppColors.onSurface)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle(service.displayName)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Chat Session Screen

struct ChatSessionScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    let providerId: String
    let model: String
    var agentName: String? = nil

    @State private var messages: [ChatMessage] = []
    @State private var inputText = ""
    @State private var isStreaming = false
    @State private var streamedContent = ""
    @State private var streamTask: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 0) {
            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(messages) { msg in
                            ChatBubble(message: msg)
                                .id(msg.id)
                        }
                        if isStreaming && !streamedContent.isEmpty {
                            ChatBubble(message: ChatMessage(role: ChatMessage.roleAssistant, content: streamedContent))
                                .id("streaming")
                        }
                    }
                    .padding()
                }
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }

            Divider()

            // Input
            HStack(spacing: 8) {
                TextField("Message...", text: $inputText, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...5)

                Button {
                    sendMessage()
                } label: {
                    Image(systemName: isStreaming ? "stop.circle.fill" : "arrow.up.circle.fill")
                        .font(.title2)
                        .foregroundStyle(AppColors.primary)
                }
                .disabled(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isStreaming)
            }
            .padding()
            .background(AppColors.cardBackground)
        }
        .background(AppColors.background)
        .navigationTitle(agentName ?? "\(providerId) / \(UiFormatting.formatModelName(model))")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func sendMessage() {
        if isStreaming {
            streamTask?.cancel()
            isStreaming = false
            if !streamedContent.isEmpty {
                messages.append(ChatMessage(role: ChatMessage.roleAssistant, content: streamedContent))
                streamedContent = ""
            }
            return
        }

        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let userMessage = ChatMessage(role: ChatMessage.roleUser, content: text)
        messages.append(userMessage)
        inputText = ""
        isStreaming = true
        streamedContent = ""

        guard let service = viewModel.lookupService(providerId) else {
            messages.append(ChatMessage(role: ChatMessage.roleAssistant, content: "Error: Provider not found"))
            isStreaming = false
            return
        }

        let apiKey = viewModel.uiState.aiSettings.getApiKey(providerId)
        guard !apiKey.isEmpty else {
            messages.append(ChatMessage(role: ChatMessage.roleAssistant, content: "Error: No API key configured"))
            isStreaming = false
            return
        }

        streamTask = Task {
            do {
                let stream = viewModel.sendChatMessageStream(
                    service: service, apiKey: apiKey, model: model, messages: messages
                )
                for try await chunk in stream {
                    streamedContent += chunk
                }
                messages.append(ChatMessage(role: ChatMessage.roleAssistant, content: streamedContent))
            } catch {
                let errorMsg = error.localizedDescription
                if streamedContent.isEmpty {
                    messages.append(ChatMessage(role: ChatMessage.roleAssistant, content: "Error: \(errorMsg)"))
                } else {
                    messages.append(ChatMessage(role: ChatMessage.roleAssistant, content: streamedContent + "\n\n[Error: \(errorMsg)]"))
                }
            }
            streamedContent = ""
            isStreaming = false

            // Save session
            let session = ChatSession(
                providerId: providerId, model: model,
                messages: messages, parameters: viewModel.uiState.chatParameters
            )
            Task { await ChatHistoryManager.shared.saveSession(session) }
        }
    }
}

// MARK: - Chat Bubble

struct ChatBubble: View {
    let message: ChatMessage

    var body: some View {
        HStack {
            if message.role == ChatMessage.roleUser { Spacer(minLength: 40) }

            VStack(alignment: message.role == ChatMessage.roleUser ? .trailing : .leading, spacing: 4) {
                Text(message.content)
                    .font(.body)
                    .foregroundStyle(AppColors.onSurface)
                    .textSelection(.enabled)

                Text(UiFormatting.formatTimestamp(message.timestamp))
                    .font(.caption2)
                    .foregroundStyle(AppColors.dimText)
            }
            .padding(10)
            .background(message.role == ChatMessage.roleUser ? AppColors.primaryContainer : AppColors.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            if message.role != ChatMessage.roleUser { Spacer(minLength: 40) }
        }
    }
}

// MARK: - Chat History Screen

struct ChatHistoryScreen: View {
    @State private var sessions: [ChatSession] = []

    var body: some View {
        List {
            if sessions.isEmpty {
                EmptyStateView(icon: "bubble.left.and.bubble.right", title: "No chat history")
            } else {
                ForEach(sessions) { session in
                    NavigationLink(destination: ChatContinueScreen(sessionId: session.id)) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(session.preview)
                                .foregroundStyle(AppColors.onSurface)
                            Text("\(session.providerId) / \(session.model) - \(UiFormatting.formatRelativeTime(session.updatedAt))")
                                .font(.caption)
                                .foregroundStyle(AppColors.dimText)
                        }
                    }
                }
                .onDelete { indexSet in
                    for idx in indexSet {
                        let session = sessions[idx]
                        Task { await ChatHistoryManager.shared.deleteSession(session.id) }
                    }
                    sessions.remove(atOffsets: indexSet)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Chat History")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            sessions = await ChatHistoryManager.shared.getAllSessions()
        }
    }
}

// MARK: - Chat Continue Screen

struct ChatContinueScreen: View {
    let sessionId: String
    @State private var session: ChatSession?

    var body: some View {
        if let session = session {
            ChatSessionScreen(providerId: session.providerId, model: session.model)
        } else {
            ProgressView()
                .task {
                    session = await ChatHistoryManager.shared.loadSession(sessionId)
                }
        }
    }
}
