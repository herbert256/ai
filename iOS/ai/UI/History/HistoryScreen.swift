import SwiftUI

// MARK: - History Screen

struct HistoryScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    @State private var allReports: [Report] = []
    @State private var searchTitle = ""
    @State private var searchPrompt = ""
    @State private var searchReport = ""
    @State private var isSearchExpanded = false
    @State private var isSearchActive = false
    @State private var selectedReportId: String?

    var body: some View {
        VStack(spacing: 0) {
            // Search section
            if !isSearchExpanded {
                Button {
                    isSearchExpanded = true
                } label: {
                    Text(isSearchActive ? "Search (active)" : "Search")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.accentBlue)
                .padding(.horizontal)
                .padding(.top, 8)
            } else {
                searchPanel
            }

            // Report list
            List {
                if filteredReports.isEmpty {
                    EmptyStateView(
                        icon: "doc.text",
                        title: allReports.isEmpty ? "No AI reports yet" : "No matching reports"
                    )
                } else {
                    ForEach(filteredReports) { report in
                        HistoryReportRow(report: report, onView: {
                            selectedReportId = report.id
                        })
                    }
                    .onDelete { indexSet in
                        let toDelete = indexSet.map { filteredReports[$0] }
                        for report in toDelete {
                            Task { await ReportStorage.shared.deleteReport(report.id) }
                        }
                        allReports.removeAll { r in toDelete.contains(where: { $0.id == r.id }) }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(AppColors.background)

            // Clear button
            Button(role: .destructive) {
                Task {
                    await ReportStorage.shared.deleteAllReports()
                    allReports = []
                    searchTitle = ""
                    searchPrompt = ""
                    searchReport = ""
                    isSearchActive = false
                }
            } label: {
                Text("Clear history")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.error)
            .disabled(allReports.isEmpty)
            .padding()
        }
        .background(AppColors.background)
        .navigationTitle("AI History")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            allReports = await ReportStorage.shared.getAllReports()
        }
        .navigationDestination(item: $selectedReportId) { reportId in
            ReportsViewerScreen(reportId: reportId)
        }
    }

    private var filteredReports: [Report] {
        guard isSearchActive else { return allReports }
        return allReports.filter { report in
            searchInReport(report: report, title: searchTitle, prompt: searchPrompt, reportText: searchReport)
        }
    }

    private var searchPanel: some View {
        CardView {
            VStack(spacing: 8) {
                TextField("Title", text: $searchTitle)
                    .textFieldStyle(.roundedBorder)
                TextField("Prompt", text: $searchPrompt)
                    .textFieldStyle(.roundedBorder)
                TextField("Report content", text: $searchReport)
                    .textFieldStyle(.roundedBorder)

                HStack(spacing: 8) {
                    Button("Search") {
                        isSearchActive = !searchTitle.isEmpty || !searchPrompt.isEmpty || !searchReport.isEmpty
                        isSearchExpanded = false
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.accentBlue)

                    Button("Clear") {
                        searchTitle = ""
                        searchPrompt = ""
                        searchReport = ""
                        isSearchActive = false
                        isSearchExpanded = false
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private func searchInReport(report: Report, title: String, prompt: String, reportText: String) -> Bool {
        if !title.isEmpty && !report.title.localizedCaseInsensitiveContains(title) {
            return false
        }
        if !prompt.isEmpty && !report.prompt.localizedCaseInsensitiveContains(prompt) {
            return false
        }
        if !reportText.isEmpty {
            let hasMatch = report.agents.contains { agent in
                agent.responseBody?.localizedCaseInsensitiveContains(reportText) == true
            }
            if !hasMatch { return false }
        }
        return true
    }
}

// MARK: - History Report Row

private struct HistoryReportRow: View {
    let report: Report
    let onView: () -> Void

    @State private var showActions = false
    @State private var showShareSheet = false
    @State private var showDeleteConfirm = false

    var body: some View {
        VStack(spacing: 0) {
            Button {
                showActions.toggle()
            } label: {
                HStack {
                    Text(report.title)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Spacer()
                    Text(UiFormatting.formatTimestamp(report.timestamp))
                        .font(.caption)
                        .foregroundStyle(AppColors.dimText)
                }
                .padding(.vertical, 6)
            }
            .buttonStyle(.plain)

            if showActions {
                HStack(spacing: 8) {
                    Button("View") { onView() }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.accentBlue)

                    Button("Share") { showShareSheet = true }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.success)

                    Button("Delete") { showDeleteConfirm = true }
                        .buttonStyle(.borderedProminent)
                        .tint(AppColors.error)
                }
                .font(.caption)
                .padding(.bottom, 4)
            }
        }
        .sheet(isPresented: $showShareSheet) {
            ShareReportSheet(report: report)
        }
        .deleteConfirmation(
            isPresented: $showDeleteConfirm,
            title: "Delete Report",
            message: "Are you sure you want to delete \"\(report.title)\"?"
        ) {
            Task { await ReportStorage.shared.deleteReport(report.id) }
        }
    }
}

// MARK: - Share Report Sheet

private struct ShareReportSheet: View {
    let report: Report
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button("Share as HTML") {
                    shareHtml()
                    dismiss()
                }
                Button("Share as JSON") {
                    shareJson()
                    dismiss()
                }
            }
            .navigationTitle("Share Report")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func shareHtml() {
        let html = ReportExport.generateHtml(report: report)
        let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("\(report.title).html")
        try? html.write(to: tempUrl, atomically: true, encoding: .utf8)
        shareFile(tempUrl)
    }

    private func shareJson() {
        if let data = try? JSONEncoder().encode(report) {
            let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent("\(report.title).json")
            try? data.write(to: tempUrl)
            shareFile(tempUrl)
        }
    }

    private func shareFile(_ url: URL) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let window = scene.windows.first else { return }
        let activityVC = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        window.rootViewController?.present(activityVC, animated: true)
    }
}

// MARK: - Reports Viewer Screen

struct ReportsViewerScreen: View {
    let reportId: String
    @State private var report: Report?

    var body: some View {
        Group {
            if let report = report {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        // Header
                        Text(report.title)
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurface)

                        Text("Prompt: \(report.prompt)")
                            .font(.subheadline)
                            .foregroundStyle(AppColors.dimText)

                        if report.totalCost > 0 {
                            Text("Total cost: \(UiFormatting.formatCost(report.totalCost))")
                                .font(.caption)
                                .foregroundStyle(AppColors.accentYellow)
                        }

                        // Agent results
                        ForEach(report.agents) { agent in
                            CardView {
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack {
                                        Text(agent.agentName)
                                            .font(.subheadline)
                                            .fontWeight(.medium)
                                            .foregroundStyle(AppColors.onSurface)
                                        Spacer()
                                        Text(agent.reportStatus == .success ? "OK" : "Error")
                                            .font(.caption)
                                            .foregroundStyle(agent.reportStatus == .success ? AppColors.success : AppColors.error)
                                    }

                                    Text("\(agent.provider) / \(agent.model)")
                                        .font(.caption)
                                        .foregroundStyle(AppColors.dimText)

                                    if let body = agent.responseBody {
                                        ContentDisplay(content: body, citations: agent.citations, searchResults: agent.searchResults)
                                            .frame(maxHeight: 400)
                                    }

                                    if let error = agent.errorMessage {
                                        Text("Error: \(error)")
                                            .font(.caption)
                                            .foregroundStyle(AppColors.error)
                                    }

                                    if let usage = agent.tokenUsage {
                                        HStack {
                                            Text("In: \(UiFormatting.formatTokens(usage.inputTokens)) | Out: \(UiFormatting.formatTokens(usage.outputTokens))")
                                                .font(.caption2)
                                                .foregroundStyle(AppColors.accentBlue)
                                            if let cost = agent.cost {
                                                Text(UiFormatting.formatCost(cost))
                                                    .font(.caption2)
                                                    .foregroundStyle(AppColors.accentYellow)
                                            }
                                            if let duration = agent.durationMs {
                                                Text(UiFormatting.formatDuration(duration))
                                                    .font(.caption2)
                                                    .foregroundStyle(AppColors.dimText)
                                            }
                                        }
                                    }
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                    .padding()
                }
            } else {
                ProgressView()
            }
        }
        .background(AppColors.background)
        .navigationTitle("Report")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            report = await ReportStorage.shared.getReport(reportId)
        }
    }
}

// MARK: - NavigationDestination for optional binding

extension Binding where Value == String? {
    init(item: Binding<String?>) {
        self = item
    }
}

extension View {
    func navigationDestination(item: Binding<String?>, @ViewBuilder destination: @escaping (String) -> some View) -> some View {
        self.navigationDestination(isPresented: Binding<Bool>(
            get: { item.wrappedValue != nil },
            set: { if !$0 { item.wrappedValue = nil } }
        )) {
            if let value = item.wrappedValue {
                destination(value)
            }
        }
    }
}
