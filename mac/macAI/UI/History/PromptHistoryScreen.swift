import SwiftUI

/// Full prompt history screen with search and reuse.
struct PromptHistoryView: View {
    @State private var entries: [PromptHistoryEntry] = []
    @State private var searchText = ""
    var onSelectEntry: ((PromptHistoryEntry) -> Void)? = nil

    private var filteredEntries: [PromptHistoryEntry] {
        if searchText.isEmpty { return entries }
        let lower = searchText.lowercased()
        return entries.filter {
            $0.title.lowercased().contains(lower) ||
            $0.prompt.lowercased().contains(lower)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search prompts...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding()

            if filteredEntries.isEmpty {
                EmptyStateView(
                    icon: "text.bubble",
                    title: "No Prompt History",
                    message: entries.isEmpty ? "Previously used prompts will appear here" : "No matches found"
                )
            } else {
                List(filteredEntries) { entry in
                    Button {
                        onSelectEntry?(entry)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(entry.title.isEmpty ? "Untitled" : entry.title)
                                    .font(.subheadline.bold())
                                Spacer()
                                Text(formatDate(entry.timestamp))
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                            Text(entry.prompt.prefix(200).description)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(3)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }

            HStack {
                Text("\(filteredEntries.count) entries")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                if !entries.isEmpty {
                    Button("Clear All") {
                        entries = []
                        // Clear from UserDefaults
                        SettingsPreferences.savePromptHistory([])
                    }
                    .foregroundStyle(.red)
                    .font(.caption)
                }
            }
            .padding()
        }
        .navigationTitle("Prompt History")
        .onAppear {
            entries = SettingsPreferences.loadPromptHistory()
        }
    }
}
