import SwiftUI

/// Root view with NavigationSplitView sidebar.
struct ContentView: View {
    @State private var selectedSection: SidebarSection? = .hub
    @Bindable var viewModel: AppViewModel

    var body: some View {
        NavigationSplitView {
            sidebar
        } detail: {
            detailView
        }
        .navigationSplitViewStyle(.balanced)
        .frame(minWidth: 1000, minHeight: 700)
        .preferredColorScheme(.dark)
    }

    // MARK: - Sidebar

    private var sidebar: some View {
        List(selection: $selectedSection) {
            ForEach(SidebarGroup.allCases, id: \.self) { group in
                let sections = SidebarSection.allCases.filter { $0.group == group }
                if !sections.isEmpty {
                    Section(group.rawValue) {
                        ForEach(sections) { section in
                            Label(section.rawValue, systemImage: section.icon)
                                .tag(section)
                        }
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("macAI")
        .frame(minWidth: 200)
    }

    // MARK: - Detail View

    @ViewBuilder
    private var detailView: some View {
        switch selectedSection {
        case .hub:
            HubView(viewModel: viewModel)
        case .newReport:
            ReportsHubView(viewModel: viewModel)
        case .reportHistory:
            ReportHistoryView()
        case .promptHistory:
            PromptHistoryView()
        case .chat:
            ChatHubView(viewModel: viewModel)
        case .chatHistory:
            ChatHistoryView(viewModel: viewModel)
        case .dualChat:
            DualChatSetupView(viewModel: viewModel)
        case .modelSearch:
            ModelSearchView(viewModel: viewModel)
        case .statistics:
            StatisticsView(viewModel: viewModel)
        case .settings:
            NavigationStack {
                SettingsView(viewModel: viewModel)
            }
        case .setup:
            SetupView(viewModel: viewModel)
        case .housekeeping:
            HousekeepingView(viewModel: viewModel)
        case .traces:
            TraceView()
        case .developer:
            DeveloperView(viewModel: viewModel)
        case .help:
            HelpView()
        case nil:
            HubView(viewModel: viewModel)
        }
    }
}

// MARK: - Hub View

struct HubView: View {
    @Bindable var viewModel: AppViewModel

    private var settings: Settings { viewModel.uiState.aiSettings }

    private var activeProviders: Int {
        settings.getActiveServices().count
    }

    private var totalProviders: Int {
        AppService.entries.count
    }

    private var totalTokens: Int64 {
        viewModel.uiState.usageStats.values.reduce(0) { $0 + $1.totalTokens }
    }

    private var totalCalls: Int {
        viewModel.uiState.usageStats.values.reduce(0) { $0 + $1.callCount }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Header
                VStack(spacing: 8) {
                    Text("macAI")
                        .font(.largeTitle.bold())
                    Text("AI-Powered Reports & Chat")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 20)

                // Quick stats
                LazyVGrid(columns: [
                    GridItem(.flexible()),
                    GridItem(.flexible()),
                    GridItem(.flexible()),
                    GridItem(.flexible())
                ], spacing: 16) {
                    StatCard(title: "Providers", value: "\(activeProviders)/\(totalProviders)", icon: "server.rack")
                    StatCard(title: "Agents", value: "\(settings.agents.count)", icon: "person.3")
                    StatCard(title: "Flocks", value: "\(settings.flocks.count)", icon: "bird")
                    StatCard(title: "Swarms", value: "\(settings.swarms.count)", icon: "ant")
                }
                .padding(.horizontal)

                // Usage summary (if there are stats)
                if totalCalls > 0 {
                    VStack(alignment: .leading, spacing: 12) {
                        SectionHeader(title: "Usage Summary", icon: "chart.bar")

                        HStack(spacing: 24) {
                            VStack(spacing: 4) {
                                Text(formatCompactNumber(totalTokens))
                                    .font(.title2.bold().monospacedDigit())
                                Text("Total Tokens")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            VStack(spacing: 4) {
                                Text("\(totalCalls)")
                                    .font(.title2.bold().monospacedDigit())
                                Text("API Calls")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            VStack(spacing: 4) {
                                Text("\(viewModel.uiState.usageStats.count)")
                                    .font(.title2.bold().monospacedDigit())
                                Text("Models Used")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.horizontal)
                }

                // Quick actions
                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader(title: "Quick Actions", icon: "bolt")

                    HStack(spacing: 12) {
                        if settings.hasAnyApiKey() {
                            QuickActionButton(title: "New Report", icon: "doc.badge.plus", color: AppColors.primary) {}
                            QuickActionButton(title: "Chat", icon: "bubble.left.and.bubble.right", color: AppColors.statusOk) {}
                        }
                        QuickActionButton(title: "Setup", icon: "wrench.and.screwdriver", color: AppColors.textSecondary) {}
                    }
                }
                .padding(.horizontal)

                Spacer()
            }
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Hub")
    }
}

// MARK: - Stat Card

struct StatCard: View {
    let title: String
    let value: String
    let icon: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(AppColors.primary)
            Text(value)
                .font(.title.bold())
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 100)
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(AppColors.cardBorder, lineWidth: 1)
        )
    }
}

// MARK: - Quick Action Button

struct QuickActionButton: View {
    let title: String
    let icon: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.title3)
                Text(title)
                    .font(.caption)
            }
            .foregroundStyle(color)
            .frame(width: 100, height: 60)
            .background(AppColors.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(color.opacity(0.3), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Placeholder View

struct PlaceholderView: View {
    let title: String
    let icon: String
    let description: String

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: icon)
                .font(.system(size: 48))
                .foregroundStyle(AppColors.primary.opacity(0.5))
            Text(title)
                .font(.title2.bold())
            Text(description)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Text("Coming soon")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .navigationTitle(title)
    }
}
