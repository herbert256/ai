import SwiftUI

// MARK: - Prompt History Screen

struct PromptHistoryScreen: View {
    var onSelectEntry: ((PromptHistoryEntry) -> Void)? = nil

    @State private var allEntries: [PromptHistoryEntry] = []
    @State private var searchText = ""

    var body: some View {
        VStack(spacing: 0) {
            // History list
            List {
                if filteredEntries.isEmpty {
                    EmptyStateView(
                        icon: "text.bubble",
                        title: allEntries.isEmpty ? "No prompt history yet" : "No matches found"
                    )
                } else {
                    ForEach(filteredEntries, id: \.timestamp) { entry in
                        Button {
                            onSelectEntry?(entry)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(entry.title)
                                        .foregroundStyle(AppColors.onSurface)
                                        .lineLimit(1)
                                    Text(entry.prompt)
                                        .font(.caption)
                                        .foregroundStyle(AppColors.dimText)
                                        .lineLimit(2)
                                }
                                Spacer()
                                Text(UiFormatting.formatRelativeTime(entry.timestamp))
                                    .font(.caption2)
                                    .foregroundStyle(AppColors.dimText)
                            }
                            .padding(.vertical, 2)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(AppColors.background)

            // Clear button
            Button(role: .destructive) {
                SettingsPreferences.shared.clearPromptHistory()
                allEntries = []
                searchText = ""
            } label: {
                Text("Clear history")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.error)
            .disabled(allEntries.isEmpty)
            .padding()
        }
        .background(AppColors.background)
        .searchable(text: $searchText, prompt: "Search in title or prompt...")
        .navigationTitle("Prompt History")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            allEntries = SettingsPreferences.shared.loadPromptHistory()
        }
    }

    private var filteredEntries: [PromptHistoryEntry] {
        guard !searchText.isEmpty else { return allEntries }
        return allEntries.filter { entry in
            entry.title.localizedCaseInsensitiveContains(searchText) ||
            entry.prompt.localizedCaseInsensitiveContains(searchText)
        }
    }
}
