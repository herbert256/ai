import SwiftUI

// MARK: - Collapsible Card

struct CollapsibleCard<Content: View>: View {
    let title: String
    var defaultExpanded: Bool = false
    var summary: String? = nil
    @ViewBuilder let content: Content
    @State private var isExpanded: Bool

    init(title: String, defaultExpanded: Bool = false, summary: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.defaultExpanded = defaultExpanded
        self.summary = summary
        self.content = content()
        self._isExpanded = State(initialValue: defaultExpanded)
    }

    var body: some View {
        CardView {
            VStack(alignment: .leading, spacing: 8) {
                Button {
                    withAnimation { isExpanded.toggle() }
                } label: {
                    HStack {
                        Text(title)
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundStyle(AppColors.onSurface)
                        Spacer()
                        if !isExpanded, let summary = summary {
                            Text(summary)
                                .font(.caption)
                                .foregroundStyle(AppColors.dimText)
                        }
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                            .font(.caption)
                            .foregroundStyle(AppColors.dimText)
                    }
                }
                .buttonStyle(.plain)

                if isExpanded {
                    content
                }
            }
        }
    }
}

// MARK: - API Key Input Section

struct ApiKeyInputSection: View {
    @Binding var apiKey: String
    var onTestApiKey: (() async -> String?)? = nil

    @State private var showKey = false
    @State private var testResult: String?
    @State private var isTesting = false

    var body: some View {
        CollapsibleCard(title: "API Key", defaultExpanded: !apiKey.isEmpty, summary: apiKey.isEmpty ? "Not set" : "Configured") {
            VStack(spacing: 8) {
                HStack {
                    if showKey {
                        TextField("API Key", text: $apiKey)
                            .textFieldStyle(.roundedBorder)
                            .font(.caption)
                    } else {
                        SecureField("API Key", text: $apiKey)
                            .textFieldStyle(.roundedBorder)
                            .font(.caption)
                    }
                    Button {
                        showKey.toggle()
                    } label: {
                        Image(systemName: showKey ? "eye.slash" : "eye")
                            .foregroundStyle(AppColors.dimText)
                    }
                }

                if let onTestApiKey = onTestApiKey {
                    HStack {
                        Button {
                            isTesting = true
                            testResult = nil
                            Task {
                                let error = await onTestApiKey()
                                testResult = error ?? "OK - Connection successful"
                                isTesting = false
                            }
                        } label: {
                            HStack {
                                if isTesting { ProgressView().tint(AppColors.primary) }
                                Text("Test API Key")
                            }
                        }
                        .buttonStyle(.bordered)
                        .disabled(apiKey.isEmpty || isTesting)

                        if let result = testResult {
                            Text(result)
                                .font(.caption2)
                                .foregroundStyle(result.contains("OK") ? AppColors.success : AppColors.error)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Parameters Selector

struct ParametersSelector: View {
    let aiSettings: Settings
    @Binding var selectedIds: [String]

    var body: some View {
        CollapsibleCard(title: "Parameters", summary: selectedIds.isEmpty ? "None" : "\(selectedIds.count) selected") {
            VStack(alignment: .leading, spacing: 6) {
                // Selected parameters with remove buttons
                ForEach(selectedIds, id: \.self) { id in
                    if let params = aiSettings.getParametersById(id) {
                        HStack {
                            Text(params.name)
                                .font(.caption)
                                .foregroundStyle(AppColors.onSurface)
                            Spacer()
                            Button {
                                selectedIds.removeAll { $0 == id }
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.caption)
                                    .foregroundStyle(AppColors.error)
                            }
                        }
                    }
                }

                // Add button
                let available = aiSettings.parameters.filter { !selectedIds.contains($0.id) }
                if !available.isEmpty {
                    Menu {
                        ForEach(available) { p in
                            Button(p.name) {
                                selectedIds.append(p.id)
                            }
                        }
                    } label: {
                        Label("Add Parameter Preset", systemImage: "plus.circle")
                            .font(.caption)
                    }
                }
            }
        }
    }
}

// MARK: - System Prompt Selector

struct SystemPromptSelector: View {
    let aiSettings: Settings
    @Binding var selectedId: String?

    var body: some View {
        Menu {
            Button("None (Clear)") { selectedId = nil }
            ForEach(aiSettings.systemPrompts.sorted(by: { $0.name < $1.name })) { sp in
                Button(sp.name) { selectedId = sp.id }
            }
        } label: {
            HStack {
                Image(systemName: "text.bubble")
                    .foregroundStyle(AppColors.primary)
                Text(selectedId.flatMap { aiSettings.getSystemPromptById($0)?.name } ?? "System Prompt")
                    .font(.caption)
                    .foregroundStyle(selectedId != nil ? AppColors.onSurface : AppColors.dimText)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(AppColors.surfaceVariant)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}

// MARK: - Endpoints Section

struct EndpointsSection: View {
    @Binding var endpoints: [Endpoint]
    let defaultEndpointUrl: String

    @State private var showAddDialog = false
    @State private var editingEndpoint: Endpoint?
    @State private var editName = ""
    @State private var editUrl = ""

    var body: some View {
        CollapsibleCard(title: "Endpoints", summary: endpoints.isEmpty ? "Default" : "\(endpoints.count) configured") {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(endpoints) { ep in
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            HStack(spacing: 4) {
                                Text(ep.name)
                                    .font(.caption)
                                    .foregroundStyle(AppColors.onSurface)
                                if ep.isDefault {
                                    Image(systemName: "star.fill")
                                        .font(.caption2)
                                        .foregroundStyle(AppColors.accentYellow)
                                }
                            }
                            Text(ep.url)
                                .font(.caption2)
                                .foregroundStyle(AppColors.dimText)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button {
                            var updated = endpoints
                            for i in updated.indices { updated[i].isDefault = (updated[i].id == ep.id) }
                            endpoints = updated
                        } label: {
                            Image(systemName: ep.isDefault ? "star.fill" : "star")
                                .font(.caption2)
                                .foregroundStyle(AppColors.accentYellow)
                        }
                        Button {
                            editingEndpoint = ep
                            editName = ep.name
                            editUrl = ep.url
                        } label: {
                            Image(systemName: "pencil")
                                .font(.caption2)
                                .foregroundStyle(AppColors.dimText)
                        }
                        Button {
                            endpoints.removeAll { $0.id == ep.id }
                        } label: {
                            Image(systemName: "trash")
                                .font(.caption2)
                                .foregroundStyle(AppColors.error)
                        }
                    }
                }

                Button {
                    editName = ""
                    editUrl = ""
                    showAddDialog = true
                } label: {
                    Label("Add Endpoint", systemImage: "plus.circle")
                        .font(.caption)
                }
            }
        }
        .alert("Add Endpoint", isPresented: $showAddDialog) {
            TextField("Name", text: $editName)
            TextField("URL", text: $editUrl)
            Button("Add") {
                let isFirst = endpoints.isEmpty
                let ep = Endpoint(name: editName, url: editUrl, isDefault: isFirst)
                endpoints.append(ep)
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Edit Endpoint", isPresented: Binding(get: { editingEndpoint != nil }, set: { if !$0 { editingEndpoint = nil } })) {
            TextField("Name", text: $editName)
            TextField("URL", text: $editUrl)
            Button("Save") {
                if let id = editingEndpoint?.id, let idx = endpoints.firstIndex(where: { $0.id == id }) {
                    endpoints[idx].name = editName
                    endpoints[idx].url = editUrl
                }
                editingEndpoint = nil
            }
            Button("Cancel", role: .cancel) { editingEndpoint = nil }
        }
    }
}

// MARK: - Models Section

struct ModelsSection: View {
    @Binding var defaultModel: String
    @Binding var modelSource: ModelSource
    @Binding var models: [String]
    var isLoadingModels: Bool = false
    var onFetchModels: (() -> Void)? = nil
    var onSelectModel: (() -> Void)? = nil
    @Binding var modelListUrl: String
    var defaultModelListUrl: String = ""

    @State private var showAddModel = false
    @State private var newModelName = ""

    var body: some View {
        CollapsibleCard(title: "Models", defaultExpanded: true, summary: "\(models.count) models") {
            VStack(alignment: .leading, spacing: 8) {
                // Default model
                HStack {
                    TextField("Default model", text: $defaultModel)
                        .textFieldStyle(.roundedBorder)
                        .font(.caption)
                    if let onSelectModel = onSelectModel, !models.isEmpty {
                        Button("Select") { onSelectModel() }
                            .font(.caption)
                            .buttonStyle(.bordered)
                    }
                }

                // Model source toggle
                Picker("Source", selection: $modelSource) {
                    Text("API").tag(ModelSource.api)
                    Text("Manual").tag(ModelSource.manual)
                }
                .pickerStyle(.segmented)

                // Model list URL
                if modelSource == .api {
                    TextField("Model list URL", text: $modelListUrl)
                        .textFieldStyle(.roundedBorder)
                        .font(.caption)

                    HStack {
                        if let onFetchModels = onFetchModels {
                            Button {
                                onFetchModels()
                            } label: {
                                HStack {
                                    if isLoadingModels { ProgressView().controlSize(.small) }
                                    Text("Retrieve models")
                                }
                            }
                            .buttonStyle(.bordered)
                            .disabled(isLoadingModels)
                            .font(.caption)
                        }

                        Text("\(models.count) models")
                            .font(.caption2)
                            .foregroundStyle(AppColors.dimText)
                    }
                }

                // Manual model management
                if modelSource == .manual {
                    Button {
                        newModelName = ""
                        showAddModel = true
                    } label: {
                        Label("Add Model", systemImage: "plus.circle")
                            .font(.caption)
                    }
                }

                // Model list (limited display)
                if !models.isEmpty {
                    let displayModels = Array(models.prefix(20))
                    ForEach(displayModels, id: \.self) { model in
                        HStack {
                            Text(model)
                                .font(.caption2)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                                .lineLimit(1)
                            Spacer()
                            if modelSource == .manual {
                                Button {
                                    models.removeAll { $0 == model }
                                } label: {
                                    Image(systemName: "xmark.circle")
                                        .font(.caption2)
                                        .foregroundStyle(AppColors.error)
                                }
                            }
                        }
                    }
                    if models.count > 20 {
                        Text("... and \(models.count - 20) more")
                            .font(.caption2)
                            .foregroundStyle(AppColors.dimText)
                    }
                }
            }
        }
        .alert("Add Model", isPresented: $showAddModel) {
            TextField("Model name", text: $newModelName)
            Button("Add") {
                if !newModelName.isEmpty {
                    models.append(newModelName)
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}

// MARK: - Service Navigation Card

struct ServiceNavigationCard: View {
    let title: String
    var providerState: String = "not-used"

    var body: some View {
        HStack(spacing: 12) {
            ProviderStateIndicator(state: providerState)
            Text(title)
                .foregroundStyle(providerState == "inactive" ? AppColors.dimText : AppColors.onSurface)
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(AppColors.dimText)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Setup Navigation Card

struct SetupNavigationCard: View {
    let title: String
    let description: String
    let icon: String
    let count: String
    var enabled: Bool = true

    var body: some View {
        HStack(spacing: 12) {
            Text(icon)
                .font(.title2)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundStyle(enabled ? AppColors.onSurface : AppColors.dimText)
                Text(description)
                    .font(.caption)
                    .foregroundStyle(enabled ? AppColors.onSurfaceVariant : AppColors.dimText)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(count)
                    .font(.caption)
                    .foregroundStyle(enabled ? AppColors.success : AppColors.dimText)
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(AppColors.dimText)
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 12)
        .background(enabled ? AppColors.cardBackground : AppColors.surfaceVariant)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .opacity(enabled ? 1.0 : 0.6)
    }
}
