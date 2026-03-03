import SwiftUI

// MARK: - Report Progress Screen

struct ReportProgressScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        NavigationStack {
            let state = viewModel.uiState

            VStack(spacing: 16) {
                // Header
                Text(state.genericPromptTitle)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                    .padding(.top)

                // Progress
                if state.genericReportsTotal > 0 {
                    VStack(spacing: 8) {
                        ProgressView(value: Double(state.genericReportsProgress), total: Double(state.genericReportsTotal))
                            .tint(AppColors.primary)
                        Text("\(state.genericReportsProgress) / \(state.genericReportsTotal) complete")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                    }
                    .padding(.horizontal)
                }

                // Results
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(Array(state.genericReportsSelectedAgents).sorted(), id: \.self) { key in
                            let result = state.genericReportsAgentResults[key]
                            ReportAgentRow(key: key, result: result)
                        }
                    }
                    .padding(.horizontal)
                }

                // Actions
                HStack(spacing: 12) {
                    if state.genericReportsProgress < state.genericReportsTotal {
                        Button("Stop") {
                            viewModel.stopGenericReports()
                        }
                        .buttonStyle(.bordered)
                    }

                    Button("Close") {
                        viewModel.dismissGenericReportsDialog()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.primary)
                }
                .padding()
            }
            .background(AppColors.background)
            .navigationTitle("Report Progress")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: - Report Agent Row

struct ReportAgentRow: View {
    let key: String
    let result: AnalysisResponse?

    var body: some View {
        CardView {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(key)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Spacer()
                    statusIcon
                }

                if let result = result {
                    if let analysis = result.analysis {
                        Text(analysis.prefix(200))
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(4)
                    }
                    if let error = result.error {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(AppColors.error)
                            .lineLimit(2)
                    }
                    if let usage = result.tokenUsage {
                        HStack(spacing: 8) {
                            Text("In: \(UiFormatting.formatTokens(usage.inputTokens))")
                            Text("Out: \(UiFormatting.formatTokens(usage.outputTokens))")
                            if let cost = usage.apiCost {
                                Text(UiFormatting.formatCost(cost))
                            }
                        }
                        .font(.caption2)
                        .foregroundStyle(AppColors.dimText)
                    }
                } else {
                    HStack {
                        ProgressView()
                            .tint(AppColors.primary)
                        Text("Processing...")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var statusIcon: some View {
        if let result = result {
            if result.isSuccess {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(AppColors.success)
            } else {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(AppColors.error)
            }
        } else {
            ProgressView()
                .scaleEffect(0.7)
        }
    }
}
