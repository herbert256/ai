import SwiftUI

// MARK: - Hub Screen

struct HubScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Header
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("AI")
                            .font(.largeTitle)
                            .fontWeight(.bold)
                            .foregroundStyle(AppColors.primary)
                        Text("Multi-provider AI reports and chat")
                            .font(.subheadline)
                            .foregroundStyle(AppColors.dimText)
                    }
                    Spacer()
                }
                .padding(.horizontal)

                // Quick Actions
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    HubCard(icon: "doc.text.fill", title: "New Report", subtitle: "Create AI analysis", color: AppColors.primary) {
                        // Navigate to new report
                    }
                    HubCard(icon: "bubble.left.fill", title: "Chat", subtitle: "Start conversation", color: AppColors.secondary) {
                        // Navigate to chat
                    }
                    HubCard(icon: "cpu", title: "Models", subtitle: "Search & explore", color: AppColors.tertiary) {
                        // Navigate to models
                    }
                    HubCard(icon: "clock.fill", title: "History", subtitle: "Past reports", color: AppColors.info) {
                        // Navigate to history
                    }
                }
                .padding(.horizontal)

                // Provider Status
                SectionHeader(title: "Providers")
                    .padding(.horizontal)

                ProviderStatusGrid()
                    .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("AI Hub")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Hub Card

struct HubCard: View {
    let icon: String
    let title: String
    let subtitle: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundStyle(color)
                Text(title)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AppColors.dimText)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(AppColors.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(AppColors.cardBorder, lineWidth: 0.5)
            )
        }
    }
}

// MARK: - Provider Status Grid

struct ProviderStatusGrid: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        let providers = viewModel.getAllProviders()
        let settings = viewModel.uiState.aiSettings

        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
            ForEach(providers, id: \.id) { service in
                let state = settings.getProviderState(service.id)
                HStack(spacing: 6) {
                    ProviderStateIndicator(state: state)
                    Text(service.displayName)
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

// MARK: - Reports Hub Screen

struct ReportsHubScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @State private var title = ""
    @State private var prompt = ""
    @State private var navigateToReport = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SectionHeader(title: "New Report")
                    .padding(.horizontal)

                VStack(spacing: 12) {
                    TextField("Report title", text: $title)
                        .textFieldStyle(.roundedBorder)

                    TextEditor(text: $prompt)
                        .frame(minHeight: 120)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .stroke(AppColors.cardBorder, lineWidth: 1)
                        )
                        .scrollContentBackground(.hidden)
                        .background(AppColors.cardBackground)

                    Button("Generate Report") {
                        viewModel.showGenericAgentSelection(title: title, prompt: prompt)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.primary)
                    .disabled(title.isEmpty || prompt.isEmpty)
                }
                .padding(.horizontal)

                Divider().background(AppColors.outlineVariant)

                // Quick links
                NavigationLink(destination: HistoryScreen()) {
                    SettingsListItemCard(title: "Report History", icon: "clock.fill")
                }
                .padding(.horizontal)

                NavigationLink(destination: PromptHistoryScreen()) {
                    SettingsListItemCard(title: "Prompt History", icon: "text.bubble.fill")
                }
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("Reports")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: .init(
            get: { viewModel.uiState.showGenericAgentSelection },
            set: { if !$0 { viewModel.dismissGenericAgentSelection() } }
        )) {
            ReportSelectionDialog()
        }
        .sheet(isPresented: .init(
            get: { viewModel.uiState.showGenericReportsDialog },
            set: { if !$0 { viewModel.dismissGenericReportsDialog() } }
        )) {
            ReportProgressScreen()
        }
    }
}
