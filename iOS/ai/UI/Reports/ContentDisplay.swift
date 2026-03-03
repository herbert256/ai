import SwiftUI

// MARK: - Content Display (Report Viewer with Think Sections)

struct ContentDisplay: View {
    let content: String
    var citations: [String]? = nil
    var searchResults: [SearchResult]? = nil
    var relatedQuestions: [String]? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // Parse and display think sections
                let sections = parseContent(content)
                ForEach(sections.indices, id: \.self) { idx in
                    let section = sections[idx]
                    if section.isThinking {
                        ThinkSection(content: section.text)
                    } else {
                        Text(section.text)
                            .font(.body)
                            .foregroundStyle(AppColors.onSurface)
                            .textSelection(.enabled)
                    }
                }

                // Citations
                if let citations = citations, !citations.isEmpty {
                    Divider().background(AppColors.outlineVariant)
                    SectionHeader(title: "Citations")
                    ForEach(citations.indices, id: \.self) { idx in
                        Text("[\(idx + 1)] \(citations[idx])")
                            .font(.caption)
                            .foregroundStyle(AppColors.accentBlue)
                    }
                }

                // Search Results
                if let results = searchResults, !results.isEmpty {
                    Divider().background(AppColors.outlineVariant)
                    SectionHeader(title: "Search Results")
                    ForEach(results.indices, id: \.self) { idx in
                        VStack(alignment: .leading, spacing: 2) {
                            if let name = results[idx].name {
                                Text(name)
                                    .font(.caption)
                                    .fontWeight(.medium)
                                    .foregroundStyle(AppColors.onSurface)
                            }
                            if let snippet = results[idx].snippet {
                                Text(snippet)
                                    .font(.caption2)
                                    .foregroundStyle(AppColors.dimText)
                            }
                        }
                    }
                }

                // Related Questions
                if let questions = relatedQuestions, !questions.isEmpty {
                    Divider().background(AppColors.outlineVariant)
                    SectionHeader(title: "Related Questions")
                    ForEach(questions, id: \.self) { q in
                        Text(q)
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
            }
            .padding()
        }
    }

    // MARK: - Content Parsing

    private struct ContentSection {
        let text: String
        let isThinking: Bool
    }

    private func parseContent(_ text: String) -> [ContentSection] {
        var sections: [ContentSection] = []
        var remaining = text

        let thinkOpen = "<think>"
        let thinkClose = "</think>"

        while let openRange = remaining.range(of: thinkOpen) {
            // Text before think tag
            let before = String(remaining[remaining.startIndex..<openRange.lowerBound])
            if !before.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                sections.append(ContentSection(text: before, isThinking: false))
            }

            remaining = String(remaining[openRange.upperBound...])

            if let closeRange = remaining.range(of: thinkClose) {
                let thinkContent = String(remaining[remaining.startIndex..<closeRange.lowerBound])
                sections.append(ContentSection(text: thinkContent, isThinking: true))
                remaining = String(remaining[closeRange.upperBound...])
            } else {
                sections.append(ContentSection(text: remaining, isThinking: true))
                remaining = ""
            }
        }

        if !remaining.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            sections.append(ContentSection(text: remaining, isThinking: false))
        }

        if sections.isEmpty {
            sections.append(ContentSection(text: text, isThinking: false))
        }

        return sections
    }
}

// MARK: - Think Section

struct ThinkSection: View {
    let content: String
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                withAnimation { isExpanded.toggle() }
            } label: {
                HStack {
                    Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                        .font(.caption2)
                    Text("Thinking")
                        .font(.caption)
                        .fontWeight(.medium)
                }
                .foregroundStyle(AppColors.dimText)
            }

            if isExpanded {
                Text(content)
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(8)
                    .background(AppColors.surfaceVariant.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .textSelection(.enabled)
            }
        }
        .padding(.vertical, 2)
    }
}
