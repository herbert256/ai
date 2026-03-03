import SwiftUI

// MARK: - Endpoints List

struct EndpointsListView: View {
    @Bindable var viewModel: AppViewModel

    private var settings: Settings { viewModel.uiState.aiSettings }

    /// Providers that have custom endpoints configured.
    private var providersWithEndpoints: [(service: AppService, endpoints: [Endpoint])] {
        AppService.entries.compactMap { service in
            let eps = settings.endpoints[service.id]
            guard let eps, !eps.isEmpty else { return nil }
            return (service, eps)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if providersWithEndpoints.isEmpty {
                EmptyStateView(
                    icon: "link",
                    title: "No Custom Endpoints",
                    message: "Configure custom endpoints per provider in the provider settings"
                )
            } else {
                List {
                    ForEach(providersWithEndpoints, id: \.service.id) { item in
                        Section(item.service.displayName) {
                            ForEach(item.endpoints) { ep in
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 4) {
                                            Text(ep.name)
                                                .font(.subheadline.bold())
                                            if ep.isDefault {
                                                Text("DEFAULT")
                                                    .font(.caption2)
                                                    .padding(.horizontal, 4)
                                                    .padding(.vertical, 1)
                                                    .background(AppColors.primary.opacity(0.2))
                                                    .clipShape(RoundedRectangle(cornerRadius: 3))
                                            }
                                        }
                                        Text(ep.url)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }
                                    Spacer()
                                }
                            }
                        }
                    }
                }
            }

            HStack {
                let total = providersWithEndpoints.reduce(0) { $0 + $1.endpoints.count }
                Text("\(total) custom endpoints across \(providersWithEndpoints.count) providers")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Endpoints")
    }
}
