import SwiftUI

// MARK: - Statistics Screen (AI Usage)

struct StatisticsScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    @State private var stats: [String: UsageStats] = [:]  // "provider/model" -> UsageStats
    @State private var expandedProviders: Set<String> = []

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if stats.isEmpty {
                    EmptyStateView(icon: "chart.bar", title: "No usage data yet", message: "Generate reports or use chat to see statistics")
                } else {
                    // Summary card
                    summaryCard

                    // Provider groups
                    ForEach(providerGroups, id: \.provider) { group in
                        providerCard(group)
                    }

                    // Clear button
                    Button(role: .destructive) {
                        SettingsPreferences.shared.clearUsageStats()
                        stats = [:]
                    } label: {
                        Text("Clear Statistics")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.error)
                    .padding(.horizontal)
                }
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("AI Usage")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            stats = SettingsPreferences.shared.loadUsageStats()
        }
    }

    // MARK: - Summary

    private var totalCalls: Int {
        stats.values.reduce(0) { $0 + $1.callCount }
    }

    private var totalInputTokens: Int64 {
        stats.values.reduce(Int64(0)) { $0 + $1.inputTokens }
    }

    private var totalOutputTokens: Int64 {
        stats.values.reduce(Int64(0)) { $0 + $1.outputTokens }
    }

    private var summaryCard: some View {
        CardView {
            VStack(alignment: .leading, spacing: 8) {
                Text("Summary")
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundStyle(AppColors.onSurface)

                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Total Calls")
                            .font(.caption2)
                            .foregroundStyle(AppColors.dimText)
                        Text("\(totalCalls)")
                            .font(.headline)
                            .foregroundStyle(AppColors.accentYellow)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 4) {
                        Text("\(stats.count) models")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                    }
                }

                HStack(spacing: 16) {
                    VStack(alignment: .leading) {
                        Text("Input")
                            .font(.caption2)
                            .foregroundStyle(AppColors.dimText)
                        Text(UiFormatting.formatTokens(Int(totalInputTokens)))
                            .font(.caption)
                            .foregroundStyle(AppColors.accentBlue)
                    }
                    VStack(alignment: .leading) {
                        Text("Output")
                            .font(.caption2)
                            .foregroundStyle(AppColors.dimText)
                        Text(UiFormatting.formatTokens(Int(totalOutputTokens)))
                            .font(.caption)
                            .foregroundStyle(AppColors.accentBlue)
                    }
                }
            }
        }
        .padding(.horizontal)
    }

    // MARK: - Provider Groups

    private struct ProviderGroup: Identifiable {
        let provider: String
        let models: [(key: String, model: String, stats: UsageStats)]
        var totalCalls: Int { models.reduce(0) { $0 + $1.stats.callCount } }
        var id: String { provider }
    }

    private var providerGroups: [ProviderGroup] {
        var groups: [String: [(key: String, model: String, stats: UsageStats)]] = [:]
        for (key, stat) in stats {
            let parts = key.split(separator: "/", maxSplits: 1)
            let provider = parts.count > 0 ? String(parts[0]) : "Unknown"
            let model = parts.count > 1 ? String(parts[1]) : key
            groups[provider, default: []].append((key: key, model: model, stats: stat))
        }
        return groups.map { ProviderGroup(provider: $0.key, models: $0.value.sorted(by: { $0.stats.callCount > $1.stats.callCount })) }
            .sorted(by: { $0.totalCalls > $1.totalCalls })
    }

    private func providerCard(_ group: ProviderGroup) -> some View {
        CardView {
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    if expandedProviders.contains(group.provider) {
                        expandedProviders.remove(group.provider)
                    } else {
                        expandedProviders.insert(group.provider)
                    }
                } label: {
                    HStack {
                        Image(systemName: expandedProviders.contains(group.provider) ? "chevron.down" : "chevron.right")
                            .font(.caption2)
                        Text(group.provider)
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundStyle(AppColors.onSurface)
                        Spacer()
                        Text("\(group.totalCalls) calls")
                            .font(.caption2)
                            .foregroundStyle(AppColors.dimText)
                    }
                }
                .buttonStyle(.plain)

                if expandedProviders.contains(group.provider) {
                    ForEach(group.models, id: \.key) { item in
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(item.model)
                                    .font(.caption)
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                                    .lineLimit(1)
                                Text("In: \(UiFormatting.formatTokens(Int(item.stats.inputTokens))) | Out: \(UiFormatting.formatTokens(Int(item.stats.outputTokens)))")
                                    .font(.caption2)
                                    .foregroundStyle(AppColors.accentBlue)
                            }
                            Spacer()
                            Text("\(item.stats.callCount) calls")
                                .font(.caption2)
                                .foregroundStyle(AppColors.dimText)
                        }
                        .padding(.leading, 16)
                    }
                }
            }
        }
        .padding(.horizontal)
    }
}
