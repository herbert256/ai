import SwiftUI

/// AI Usage statistics - token counts, costs, grouped by provider.
struct StatisticsView: View {
    @Bindable var viewModel: AppViewModel
    @State private var pricingMap: [String: PricingCache.ModelPricing] = [:]

    private var stats: [String: UsageStats] { viewModel.uiState.usageStats }

    private var totalCalls: Int {
        stats.values.reduce(0) { $0 + $1.callCount }
    }

    private var totalInputTokens: Int64 {
        stats.values.reduce(0) { $0 + $1.inputTokens }
    }

    private var totalOutputTokens: Int64 {
        stats.values.reduce(0) { $0 + $1.outputTokens }
    }

    private func costFor(_ stat: UsageStats) -> Double {
        let key = "\(stat.providerId)::\(stat.model)"
        guard let pricing = pricingMap[key] else { return 0 }
        return Double(stat.inputTokens) * pricing.promptPrice + Double(stat.outputTokens) * pricing.completionPrice
    }

    private var totalCost: Double {
        stats.values.reduce(0.0) { $0 + costFor($1) }
    }

    struct ProviderGroup: Identifiable {
        let id: String
        let displayName: String
        let models: [UsageStats]
        let totalCost: Double
        let totalCalls: Int
    }

    private var providerGroups: [ProviderGroup] {
        var grouped: [String: [UsageStats]] = [:]
        for stat in stats.values {
            grouped[stat.providerId, default: []].append(stat)
        }

        return grouped.map { (providerId, models) in
            let provider = AppService.findById(providerId)
            let cost = models.reduce(0.0) { $0 + costFor($1) }
            return ProviderGroup(
                id: providerId,
                displayName: provider?.displayName ?? providerId,
                models: models.sorted { $0.callCount > $1.callCount },
                totalCost: cost,
                totalCalls: models.reduce(0) { $0 + $1.callCount }
            )
        }
        .sorted { $0.totalCost > $1.totalCost }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Summary card
                HStack(spacing: 24) {
                    StatBox(label: "Total Cost", value: formatUsd(totalCost), color: AppColors.statusOk)
                    StatBox(label: "API Calls", value: "\(totalCalls)", color: AppColors.primary)
                    StatBox(label: "Input Tokens", value: formatCompactNumber(totalInputTokens), color: AppColors.primary)
                    StatBox(label: "Output Tokens", value: formatCompactNumber(totalOutputTokens), color: AppColors.statusOk)
                    StatBox(label: "Models", value: "\(stats.count)", color: .white)
                }
                .padding()
                .background(AppColors.cardBackground)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                if stats.isEmpty {
                    EmptyStateView(
                        icon: "chart.bar",
                        title: "No Usage Data",
                        message: "Statistics will appear as you use AI services"
                    )
                } else {
                    ForEach(providerGroups) { group in
                        ProviderUsageCard(group: group, costFor: costFor)
                    }
                }

                HStack {
                    Button("Clear Statistics") {
                        viewModel.uiState.usageStats = [:]
                        SettingsPreferences.saveUsageStats([:])
                    }
                    .foregroundStyle(.red)
                    .controlSize(.small)
                    Spacer()
                }
            }
            .padding()
        }
        .navigationTitle("AI Usage")
        .task {
            await loadPricing()
        }
    }

    private func loadPricing() async {
        var map: [String: PricingCache.ModelPricing] = [:]
        for stat in stats.values {
            guard let provider = AppService.findById(stat.providerId) else { continue }
            let pricing = await PricingCache.shared.getPricing(provider: provider, model: stat.model)
            map["\(stat.providerId)::\(stat.model)"] = pricing
        }
        pricingMap = map
    }
}

// MARK: - Stat Box

private struct StatBox: View {
    let label: String
    let value: String
    let color: Color

    var body: some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.title3.bold().monospacedDigit())
                .foregroundStyle(color)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Provider Usage Card

private struct ProviderUsageCard: View {
    let group: StatisticsView.ProviderGroup
    let costFor: (UsageStats) -> Double
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation { isExpanded.toggle() }
            } label: {
                HStack {
                    Text(group.displayName)
                        .font(.subheadline.bold())
                        .foregroundStyle(AppColors.primary)
                    Spacer()
                    Text(formatUsd(group.totalCost))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.statusOk)
                    Text("\(group.totalCalls) calls")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(12)
            }
            .buttonStyle(.plain)

            if isExpanded {
                Divider()
                ForEach(group.models, id: \.key) { stat in
                    ModelUsageRow(stat: stat, cost: costFor(stat))
                }
            }
        }
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(AppColors.cardBorder, lineWidth: 1)
        )
    }
}

// MARK: - Model Usage Row

private struct ModelUsageRow: View {
    let stat: UsageStats
    let cost: Double

    var body: some View {
        HStack {
            Text(stat.model)
                .font(.caption)
                .lineLimit(1)
            Spacer()
            Text("\(stat.callCount)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            Text("↑\(formatCompactNumber(stat.inputTokens))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.primary)
            Text("↓\(formatCompactNumber(stat.outputTokens))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.statusOk)
            Text(formatUsd(cost))
                .font(.caption.monospacedDigit())
                .foregroundStyle(cost > 0 ? AppColors.statusOk : .secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }
}
