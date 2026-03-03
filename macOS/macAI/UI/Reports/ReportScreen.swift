import SwiftUI

// MARK: - Reports Hub

/// Entry point for reports - shows new report, history, prompt history.
struct ReportsHubView: View {
    @Bindable var viewModel: AppViewModel
    @State private var selectedSubView: ReportSubView = .newReport

    enum ReportSubView: String, CaseIterable {
        case newReport = "New Report"
        case history = "Report History"
        case promptHistory = "Prompt History"
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedSubView) {
                ForEach(ReportSubView.allCases, id: \.self) { sub in
                    Text(sub.rawValue).tag(sub)
                }
            }
            .pickerStyle(.segmented)
            .padding()

            switch selectedSubView {
            case .newReport:
                NewReportView(viewModel: viewModel)
            case .history:
                ReportHistoryListView(viewModel: viewModel)
            case .promptHistory:
                PromptHistoryListView()
            }
        }
        .navigationTitle("Reports")
    }
}

// MARK: - New Report

/// Create a new AI report with title, prompt, and agent selection.
struct NewReportView: View {
    @Bindable var viewModel: AppViewModel
    @State private var title = ""
    @State private var prompt = ""
    @State private var showAgentSelection = false
    @State private var selectedAgentIds: Set<String> = []
    @State private var selectedSwarmIds: Set<String> = []
    @State private var directModelIds: Set<String> = []
    @State private var parametersIds: [String] = []
    @State private var isGenerating = false

    private var settings: Settings { viewModel.uiState.aiSettings }
    private var canGenerate: Bool {
        !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        (!selectedAgentIds.isEmpty || !selectedSwarmIds.isEmpty || !directModelIds.isEmpty)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Title field
                AppTextField(label: "Report Title", text: $title)

                // Prompt field
                VStack(alignment: .leading, spacing: 4) {
                    Text("Prompt")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextEditor(text: $prompt)
                        .font(.body)
                        .frame(minHeight: 120)
                        .padding(4)
                        .background(Color(.textBackgroundColor))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .overlay(
                            RoundedRectangle(cornerRadius: 6)
                                .stroke(Color.secondary.opacity(0.3), lineWidth: 1)
                        )
                }

                // Selection summary
                selectionSummary

                // Action buttons
                HStack(spacing: 12) {
                    Button("Select Agents") {
                        showAgentSelection = true
                    }
                    .buttonStyle(.bordered)

                    Spacer()

                    if isGenerating {
                        ProgressView()
                            .controlSize(.small)
                    }

                    Button("Generate Report") {
                        generateReport()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canGenerate || isGenerating)
                }

                // Show progress/results if generating
                if viewModel.uiState.showGenericReportsDialog {
                    ReportProgressView(viewModel: viewModel)
                }
            }
            .padding()
        }
        .sheet(isPresented: $showAgentSelection) {
            ReportSelectionSheet(
                settings: settings,
                selectedAgentIds: $selectedAgentIds,
                selectedSwarmIds: $selectedSwarmIds,
                directModelIds: $directModelIds,
                parametersIds: $parametersIds
            )
        }
    }

    @ViewBuilder
    private var selectionSummary: some View {
        let totalSelected = selectedAgentIds.count + directModelIds.count +
            selectedSwarmIds.reduce(0) { $0 + (settings.getSwarmById($1)?.members.count ?? 0) }

        if totalSelected > 0 {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(totalSelected) model(s) selected")
                    .font(.subheadline.bold())

                if !selectedAgentIds.isEmpty {
                    let names = selectedAgentIds.compactMap { settings.getAgentById($0)?.name }.joined(separator: ", ")
                    Text("Agents: \(names)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if !selectedSwarmIds.isEmpty {
                    let names = selectedSwarmIds.compactMap { settings.getSwarmById($0)?.name }.joined(separator: ", ")
                    Text("Swarms: \(names)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if !directModelIds.isEmpty {
                    Text("Direct models: \(directModelIds.count)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }

    private func generateReport() {
        isGenerating = true

        // Save to prompt history
        SettingsPreferences.addPromptHistoryEntry(title: title, prompt: prompt)

        viewModel.showGenericAgentSelection(title, prompt)
        viewModel.generateGenericReports(
            selectedAgentIds: selectedAgentIds,
            selectedSwarmIds: selectedSwarmIds,
            directModelIds: directModelIds,
            parametersIds: parametersIds
        )
    }
}

// MARK: - Report Progress

/// Shows real-time progress of report generation.
struct ReportProgressView: View {
    @Bindable var viewModel: AppViewModel

    private var progress: Int { viewModel.uiState.genericReportsProgress }
    private var total: Int { viewModel.uiState.genericReportsTotal }
    private var results: [String: AnalysisResponse] { viewModel.uiState.genericReportsAgentResults }
    private var isComplete: Bool { progress >= total && total > 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Progress header
            HStack {
                Text(isComplete ? "Report Complete" : "Generating Report...")
                    .font(.headline)
                Spacer()
                Text("\(progress)/\(total)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            // Progress bar
            if total > 0 {
                ProgressView(value: Double(progress), total: Double(total))
                    .tint(isComplete ? AppColors.statusOk : AppColors.primary)
            }

            // Control buttons
            HStack {
                if !isComplete {
                    Button("Stop") {
                        viewModel.stopGenericReports()
                    }
                    .foregroundStyle(.red)
                }

                Spacer()

                if isComplete, let reportId = viewModel.uiState.currentReportId {
                    Button("Export HTML") {
                        Task { await exportReport(reportId: reportId) }
                    }
                    .buttonStyle(.bordered)
                }
            }

            // Results
            if !results.isEmpty {
                Divider()
                ForEach(Array(results.keys.sorted()), id: \.self) { key in
                    if let response = results[key] {
                        ReportAgentResultCard(agentId: key, response: response, settings: viewModel.uiState.aiSettings)
                    }
                }
            }
        }
        .padding()
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(AppColors.cardBorder, lineWidth: 1)
        )
    }

    private func exportReport(reportId: String) async {
        guard let report = await ReportStorage.shared.load(reportId) else { return }
        let html = ReportExporter.generateHtml(from: report)
        ReportExporter.saveAndOpen(html: html, title: report.title)
    }
}

// MARK: - Agent Result Card

/// Expandable card showing a single agent's analysis result.
struct ReportAgentResultCard: View {
    let agentId: String
    let response: AnalysisResponse
    let settings: Settings
    @State private var isExpanded = false

    private var displayName: String {
        if let agent = settings.getAgentById(agentId) {
            return agent.name
        }
        return response.displayName
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            Button(action: { withAnimation { isExpanded.toggle() } }) {
                HStack {
                    Image(systemName: response.isSuccess ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .foregroundStyle(response.isSuccess ? AppColors.statusOk : AppColors.statusError)

                    Text(displayName)
                        .font(.subheadline.bold())
                        .foregroundStyle(.white)

                    Spacer()

                    if let usage = response.tokenUsage {
                        TokenCountDisplay(inputTokens: usage.inputTokens, outputTokens: usage.outputTokens)
                    }

                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            // Expanded content
            if isExpanded {
                Divider()
                    .padding(.horizontal, 12)

                if let error = response.error {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(12)
                } else if let analysis = response.analysis {
                    ResponseContentView(content: analysis)
                        .padding(12)
                }
            }
        }
        .background(Color(.windowBackgroundColor).opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Report History List

struct ReportHistoryListView: View {
    @Bindable var viewModel: AppViewModel
    @State private var reports: [StoredReport] = []
    @State private var selectedReportId: String?

    var body: some View {
        Group {
            if reports.isEmpty {
                EmptyStateView(
                    icon: "clock.arrow.circlepath",
                    title: "No Reports Yet",
                    message: "Generated reports will appear here"
                )
            } else {
                List(reports, selection: $selectedReportId) { report in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(report.title)
                            .font(.subheadline.bold())
                        Text(formatDate(report.timestamp))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text("\(report.results.count) results")
                            .font(.caption)
                            .foregroundStyle(.tertiary)
                    }
                    .tag(report.id)
                }
            }
        }
        .task {
            reports = await ReportStorage.shared.loadAll()
        }
    }
}

// MARK: - Prompt History List

struct PromptHistoryListView: View {
    @State private var entries: [PromptHistoryEntry] = []

    var body: some View {
        Group {
            if entries.isEmpty {
                EmptyStateView(
                    icon: "text.bubble",
                    title: "No Prompt History",
                    message: "Previously used prompts will appear here"
                )
            } else {
                List(entries) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.title)
                            .font(.subheadline.bold())
                        Text(entry.prompt.prefix(100))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                        Text(formatDate(entry.timestamp))
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .onAppear {
            entries = SettingsPreferences.loadPromptHistory()
        }
    }
}
