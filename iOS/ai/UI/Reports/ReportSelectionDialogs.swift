import SwiftUI

// MARK: - Report Selection Dialog

struct ReportSelectionDialog: View {
    @Environment(AppViewModel.self) private var viewModel
    @State private var selectedAgentIds: Set<String> = []
    @State private var selectedSwarmIds: Set<String> = []
    @State private var selectedModelIds: Set<String> = []
    @State private var parametersIds: [String] = []
    @State private var reportType: ReportType = .classic

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    let settings = viewModel.uiState.aiSettings

                    // Agents Section
                    if !settings.agents.isEmpty {
                        SectionHeader(title: "Agents", count: settings.agents.count)
                            .padding(.horizontal)

                        ForEach(settings.agents) { agent in
                            let isSelected = selectedAgentIds.contains(agent.id)
                            Button {
                                if isSelected { selectedAgentIds.remove(agent.id) }
                                else { selectedAgentIds.insert(agent.id) }
                            } label: {
                                HStack {
                                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(isSelected ? AppColors.primary : AppColors.dimText)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(agent.name)
                                            .foregroundStyle(AppColors.onSurface)
                                        Text("\(agent.providerId) / \(agent.model)")
                                            .font(.caption)
                                            .foregroundStyle(AppColors.dimText)
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal)
                                .padding(.vertical, 6)
                            }
                        }
                    }

                    // Swarms Section
                    if !settings.swarms.isEmpty {
                        SectionHeader(title: "Swarms", count: settings.swarms.count)
                            .padding(.horizontal)

                        ForEach(settings.swarms) { swarm in
                            let isSelected = selectedSwarmIds.contains(swarm.id)
                            Button {
                                if isSelected { selectedSwarmIds.remove(swarm.id) }
                                else { selectedSwarmIds.insert(swarm.id) }
                            } label: {
                                HStack {
                                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(isSelected ? AppColors.primary : AppColors.dimText)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(swarm.name)
                                            .foregroundStyle(AppColors.onSurface)
                                        Text("\(swarm.members.count) members")
                                            .font(.caption)
                                            .foregroundStyle(AppColors.dimText)
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal)
                                .padding(.vertical, 6)
                            }
                        }
                    }

                    // Report Type
                    SectionHeader(title: "Report Type")
                        .padding(.horizontal)

                    Picker("Type", selection: $reportType) {
                        Text("Classic").tag(ReportType.classic)
                        Text("Table").tag(ReportType.table)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                }
                .padding(.vertical)
            }
            .background(AppColors.background)
            .navigationTitle("Select Models")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        viewModel.dismissGenericAgentSelection()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Generate") {
                        viewModel.generateGenericReports(
                            selectedAgentIds: selectedAgentIds,
                            selectedSwarmIds: selectedSwarmIds,
                            directModelIds: selectedModelIds,
                            parametersIds: parametersIds,
                            selectionParamsById: [:],
                            reportType: reportType
                        )
                    }
                    .disabled(selectedAgentIds.isEmpty && selectedSwarmIds.isEmpty && selectedModelIds.isEmpty)
                }
            }
        }
        .onAppear {
            selectedAgentIds = viewModel.loadReportAgents()
            selectedSwarmIds = viewModel.loadReportModels()
        }
    }
}
