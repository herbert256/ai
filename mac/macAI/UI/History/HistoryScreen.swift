import SwiftUI

// MARK: - Report History

/// Full report history viewer with search and pagination.
struct ReportHistoryView: View {
    @State private var reports: [StoredReport] = []
    @State private var searchText = ""
    @State private var selectedReport: StoredReport?

    private var filteredReports: [StoredReport] {
        if searchText.isEmpty { return reports }
        let lower = searchText.lowercased()
        return reports.filter {
            $0.title.lowercased().contains(lower) ||
            $0.prompt.lowercased().contains(lower)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Search
            TextField("Search reports...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding()

            if filteredReports.isEmpty {
                EmptyStateView(
                    icon: "clock.arrow.circlepath",
                    title: "No Reports",
                    message: reports.isEmpty ? "Generated reports will appear here" : "No matching reports"
                )
            } else {
                List(filteredReports, selection: Binding(
                    get: { selectedReport?.id },
                    set: { id in selectedReport = filteredReports.first { $0.id == id } }
                )) { report in
                    ReportHistoryRow(report: report) {
                        // View report
                        let html = ReportExporter.generateHtml(from: report)
                        ReportExporter.quickOpen(html: html, title: report.title)
                    } onDelete: {
                        Task {
                            await ReportStorage.shared.delete(report.id)
                            reports = await ReportStorage.shared.loadAll()
                        }
                    }
                    .tag(report.id)
                }
            }

            // Footer
            HStack {
                Text("\(filteredReports.count) reports")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                if !reports.isEmpty {
                    Button("Clear All") {
                        Task {
                            await ReportStorage.shared.deleteAll()
                            reports = []
                        }
                    }
                    .foregroundStyle(.red)
                    .font(.caption)
                }
            }
            .padding()
        }
        .navigationTitle("Report History")
        .task {
            reports = await ReportStorage.shared.loadAll()
        }
    }
}

struct ReportHistoryRow: View {
    let report: StoredReport
    let onView: () -> Void
    let onDelete: () -> Void

    @State private var showActions = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: { showActions.toggle() }) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(report.title)
                            .font(.subheadline.bold())
                            .lineLimit(1)
                        Text(formatDate(report.timestamp))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("\(report.results.count) results")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .buttonStyle(.plain)

            if showActions {
                HStack(spacing: 8) {
                    Button("View in Browser", action: onView)
                        .buttonStyle(.bordered)
                        .controlSize(.small)

                    Button("Export HTML") {
                        let html = ReportExporter.generateHtml(from: report)
                        ReportExporter.saveAndOpen(html: html, title: report.title)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)

                    Spacer()

                    Button("Delete") { onDelete() }
                        .foregroundStyle(.red)
                        .controlSize(.small)
                }
                .padding(.top, 4)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Chat History

/// Chat session history with search.
struct ChatHistoryView: View {
    @Bindable var viewModel: AppViewModel
    @State private var sessions: [ChatSession] = []
    @State private var searchText = ""
    @State private var resumeSession: ChatSession?

    private var filteredSessions: [ChatSession] {
        if searchText.isEmpty { return sessions }
        let lower = searchText.lowercased()
        return sessions.filter {
            $0.preview.lowercased().contains(lower) ||
            $0.model.lowercased().contains(lower)
        }
    }

    var body: some View {
        if let session = resumeSession, let provider = session.provider {
            ChatSessionView(
                viewModel: viewModel,
                provider: provider,
                model: session.model,
                initialParams: session.parameters,
                initialMessages: session.messages,
                sessionId: session.id,
                onDismiss: { resumeSession = nil }
            )
        } else {
            chatHistoryList
        }
    }

    private var chatHistoryList: some View {
        VStack(spacing: 0) {
            TextField("Search chats...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding()

            if filteredSessions.isEmpty {
                EmptyStateView(
                    icon: "archivebox",
                    title: "No Chat History",
                    message: sessions.isEmpty ? "Chat sessions will be saved here" : "No matching sessions"
                )
            } else {
                List(filteredSessions) { session in
                    Button {
                        resumeSession = session
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(session.provider?.displayName ?? session.providerId)
                                    .font(.caption.bold())
                                    .foregroundStyle(AppColors.primary)
                                Text(session.model)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Spacer()
                                Text("\(session.messages.count) msgs")
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                            Text(session.preview)
                                .font(.subheadline)
                                .lineLimit(2)
                            Text(formatRelativeDate(session.updatedAt))
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button("Delete") {
                            Task {
                                await ChatHistoryManager.shared.delete(session.id)
                                sessions = await ChatHistoryManager.shared.loadAll()
                            }
                        }
                    }
                }
            }

            HStack {
                Text("\(filteredSessions.count) sessions")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                if !sessions.isEmpty {
                    Button("Clear All") {
                        Task {
                            await ChatHistoryManager.shared.deleteAll()
                            sessions = []
                        }
                    }
                    .foregroundStyle(.red)
                    .font(.caption)
                }
            }
            .padding()
        }
        .navigationTitle("Chat History")
        .task {
            sessions = await ChatHistoryManager.shared.loadAll()
        }
    }
}
