import SwiftUI

/// Sheet for selecting agents, flocks, swarms, and direct models for a report.
struct ReportSelectionSheet: View {
    let settings: Settings
    @Binding var selectedAgentIds: Set<String>
    @Binding var selectedSwarmIds: Set<String>
    @Binding var directModelIds: Set<String>
    @Binding var parametersIds: [String]
    @Environment(\.dismiss) private var dismiss

    @State private var searchText = ""
    @State private var selectedTab = SelectionTab.agents

    enum SelectionTab: String, CaseIterable {
        case agents = "Agents"
        case flocks = "Flocks"
        case swarms = "Swarms"
        case models = "Models"
        case parameters = "Parameters"
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("Select Models")
                    .font(.title2.bold())
                Spacer()
                Button("Done") { dismiss() }
                    .buttonStyle(.borderedProminent)
            }
            .padding()

            // Search
            TextField("Search...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding(.horizontal)

            // Tabs
            Picker("", selection: $selectedTab) {
                ForEach(SelectionTab.allCases, id: \.self) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .padding()

            // Content
            ScrollView {
                switch selectedTab {
                case .agents:
                    AgentSelectionList(settings: settings, selectedIds: $selectedAgentIds, searchText: searchText)
                case .flocks:
                    FlockSelectionList(settings: settings, selectedAgentIds: $selectedAgentIds, searchText: searchText)
                case .swarms:
                    SwarmSelectionList(settings: settings, selectedIds: $selectedSwarmIds, searchText: searchText)
                case .models:
                    DirectModelSelectionList(settings: settings, selectedIds: $directModelIds, searchText: searchText)
                case .parameters:
                    ParametersSelectionList(settings: settings, parametersIds: $parametersIds)
                }
            }

            // Summary footer
            HStack {
                let total = selectedAgentIds.count + directModelIds.count +
                    selectedSwarmIds.reduce(0) { $0 + (settings.getSwarmById($1)?.members.count ?? 0) }
                Text("\(total) model(s) selected")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Clear All") {
                    selectedAgentIds.removeAll()
                    selectedSwarmIds.removeAll()
                    directModelIds.removeAll()
                }
                .foregroundStyle(.red)
            }
            .padding()
        }
        .frame(width: 600, height: 500)
    }
}

// MARK: - Agent Selection

struct AgentSelectionList: View {
    let settings: Settings
    @Binding var selectedIds: Set<String>
    let searchText: String

    private var filteredAgents: [Agent] {
        let configured = settings.getConfiguredAgents()
        if searchText.isEmpty { return configured }
        let lower = searchText.lowercased()
        return configured.filter { $0.name.lowercased().contains(lower) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if filteredAgents.isEmpty {
                Text("No configured agents found")
                    .foregroundStyle(.secondary)
                    .padding()
            } else {
                ForEach(filteredAgents) { agent in
                    SelectionRow(
                        title: agent.name,
                        subtitle: "\(agent.provider?.displayName ?? "Unknown") / \(settings.getEffectiveModelForAgent(agent))",
                        isSelected: selectedIds.contains(agent.id)
                    ) {
                        if selectedIds.contains(agent.id) {
                            selectedIds.remove(agent.id)
                        } else {
                            selectedIds.insert(agent.id)
                        }
                    }
                }
            }
        }
        .padding(.horizontal)
    }
}

// MARK: - Flock Selection

struct FlockSelectionList: View {
    let settings: Settings
    @Binding var selectedAgentIds: Set<String>
    let searchText: String

    private var filteredFlocks: [Flock] {
        if searchText.isEmpty { return settings.flocks }
        let lower = searchText.lowercased()
        return settings.flocks.filter { $0.name.lowercased().contains(lower) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if filteredFlocks.isEmpty {
                Text("No flocks found")
                    .foregroundStyle(.secondary)
                    .padding()
            } else {
                ForEach(filteredFlocks) { flock in
                    let flockAgentIds = Set(flock.agentIds)
                    let allSelected = flockAgentIds.isSubset(of: selectedAgentIds)

                    SelectionRow(
                        title: flock.name,
                        subtitle: "\(flock.agentIds.count) agents",
                        isSelected: allSelected
                    ) {
                        if allSelected {
                            selectedAgentIds.subtract(flockAgentIds)
                        } else {
                            selectedAgentIds.formUnion(flockAgentIds)
                        }
                    }
                }
            }
        }
        .padding(.horizontal)
    }
}

// MARK: - Swarm Selection

struct SwarmSelectionList: View {
    let settings: Settings
    @Binding var selectedIds: Set<String>
    let searchText: String

    private var filteredSwarms: [Swarm] {
        if searchText.isEmpty { return settings.swarms }
        let lower = searchText.lowercased()
        return settings.swarms.filter { $0.name.lowercased().contains(lower) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if filteredSwarms.isEmpty {
                Text("No swarms found")
                    .foregroundStyle(.secondary)
                    .padding()
            } else {
                ForEach(filteredSwarms) { swarm in
                    SelectionRow(
                        title: swarm.name,
                        subtitle: "\(swarm.members.count) models",
                        isSelected: selectedIds.contains(swarm.id)
                    ) {
                        if selectedIds.contains(swarm.id) {
                            selectedIds.remove(swarm.id)
                        } else {
                            selectedIds.insert(swarm.id)
                        }
                    }
                }
            }
        }
        .padding(.horizontal)
    }
}

// MARK: - Direct Model Selection

struct DirectModelSelectionList: View {
    let settings: Settings
    @Binding var selectedIds: Set<String>
    let searchText: String

    private var activeServices: [AppService] {
        settings.getActiveServices()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(activeServices) { service in
                let models = settings.getModels(service)
                if !models.isEmpty {
                    let filteredModels = searchText.isEmpty ? models : models.filter { $0.lowercased().contains(searchText.lowercased()) }
                    if !filteredModels.isEmpty {
                        DisclosureGroup {
                            ForEach(filteredModels, id: \.self) { model in
                                let modelId = "swarm:\(service.id):\(model)"
                                SelectionRow(
                                    title: model,
                                    subtitle: "",
                                    isSelected: selectedIds.contains(modelId)
                                ) {
                                    if selectedIds.contains(modelId) {
                                        selectedIds.remove(modelId)
                                    } else {
                                        selectedIds.insert(modelId)
                                    }
                                }
                            }
                        } label: {
                            Text(service.displayName)
                                .font(.subheadline.bold())
                        }
                    }
                }
            }
        }
        .padding(.horizontal)
    }
}

// MARK: - Parameters Selection

struct ParametersSelectionList: View {
    let settings: Settings
    @Binding var parametersIds: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if settings.parameters.isEmpty {
                Text("No parameter presets configured")
                    .foregroundStyle(.secondary)
                    .padding()
            } else {
                ForEach(settings.parameters) { params in
                    SelectionRow(
                        title: params.name,
                        subtitle: paramsSummary(params),
                        isSelected: parametersIds.contains(params.id)
                    ) {
                        if parametersIds.contains(params.id) {
                            parametersIds.removeAll { $0 == params.id }
                        } else {
                            parametersIds.append(params.id)
                        }
                    }
                }
            }
        }
        .padding(.horizontal)
    }

    private func paramsSummary(_ params: Parameters) -> String {
        var parts: [String] = []
        if let t = params.temperature { parts.append("temp=\(formatDecimal(Double(t)))") }
        if let m = params.maxTokens { parts.append("max=\(m)") }
        if let p = params.topP { parts.append("topP=\(formatDecimal(Double(p)))") }
        if params.searchEnabled { parts.append("search") }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Selection Row

struct SelectionRow: View {
    let title: String
    let subtitle: String
    let isSelected: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? AppColors.primary : .secondary)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline)
                        .foregroundStyle(.white)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
