import SwiftUI

/// Renders AI response content with think-section collapsing and basic markdown formatting.
struct ResponseContentView: View {
    let content: String

    // Regex for <think>...</think> sections
    private static let thinkRegex = try! NSRegularExpression(
        pattern: "<think>(.*?)</think>",
        options: .dotMatchesLineSeparators
    )

    private var sections: [ContentSection] {
        parseContent(content)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                switch section {
                case .text(let text):
                    Text(text)
                        .font(.body)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                case .think(let text):
                    ThinkSectionView(content: text)
                case .code(let language, let code):
                    CodeBlockView(language: language, code: code)
                }
            }
        }
    }

    // MARK: - Content Parsing

    private enum ContentSection {
        case text(String)
        case think(String)
        case code(String?, String)
    }

    private func parseContent(_ content: String) -> [ContentSection] {
        var sections: [ContentSection] = []
        // First, extract <think>...</think> sections
        let nsContent = content as NSString
        let matches = Self.thinkRegex.matches(in: content, range: NSRange(location: 0, length: nsContent.length))

        if matches.isEmpty {
            // No think sections - parse code blocks
            return parseCodeBlocks(content)
        }

        var lastEnd = 0
        for match in matches {
            // Text before this think section
            let beforeRange = NSRange(location: lastEnd, length: match.range.location - lastEnd)
            let beforeText = nsContent.substring(with: beforeRange).trimmingCharacters(in: .whitespacesAndNewlines)
            if !beforeText.isEmpty {
                sections.append(contentsOf: parseCodeBlocks(beforeText))
            }

            // Think content
            let thinkRange = match.range(at: 1)
            let thinkText = nsContent.substring(with: thinkRange).trimmingCharacters(in: .whitespacesAndNewlines)
            if !thinkText.isEmpty {
                sections.append(.think(thinkText))
            }

            lastEnd = match.range.location + match.range.length
        }

        // Remaining text after last think section
        let afterText = nsContent.substring(from: lastEnd).trimmingCharacters(in: .whitespacesAndNewlines)
        if !afterText.isEmpty {
            sections.append(contentsOf: parseCodeBlocks(afterText))
        }

        return sections
    }

    private func parseCodeBlocks(_ text: String) -> [ContentSection] {
        var sections: [ContentSection] = []
        let lines = text.components(separatedBy: "\n")
        var currentText = ""
        var inCodeBlock = false
        var codeLanguage: String?
        var codeContent = ""

        for line in lines {
            if line.hasPrefix("```") && !inCodeBlock {
                // Start of code block
                if !currentText.isEmpty {
                    sections.append(.text(currentText.trimmingCharacters(in: .whitespacesAndNewlines)))
                    currentText = ""
                }
                inCodeBlock = true
                let lang = String(line.dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines)
                codeLanguage = lang.isEmpty ? nil : lang
                codeContent = ""
            } else if line.hasPrefix("```") && inCodeBlock {
                // End of code block
                inCodeBlock = false
                sections.append(.code(codeLanguage, codeContent.trimmingCharacters(in: .newlines)))
                codeLanguage = nil
                codeContent = ""
            } else if inCodeBlock {
                if !codeContent.isEmpty { codeContent += "\n" }
                codeContent += line
            } else {
                if !currentText.isEmpty { currentText += "\n" }
                currentText += line
            }
        }

        // Handle unclosed code block
        if inCodeBlock {
            sections.append(.code(codeLanguage, codeContent.trimmingCharacters(in: .newlines)))
        }

        // Remaining text
        if !currentText.isEmpty {
            sections.append(.text(currentText.trimmingCharacters(in: .whitespacesAndNewlines)))
        }

        return sections
    }
}

// MARK: - Think Section

/// Collapsible think/reasoning section.
struct ThinkSectionView: View {
    let content: String
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: { withAnimation { isExpanded.toggle() } }) {
                HStack {
                    Image(systemName: "brain")
                        .foregroundStyle(AppColors.primary.opacity(0.7))
                    Text("Thinking")
                        .font(.caption.bold())
                        .foregroundStyle(AppColors.primary.opacity(0.7))
                    Spacer()
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
            .padding(8)

            if isExpanded {
                Divider()
                Text(content)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .padding(8)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .background(AppColors.primary.opacity(0.05))
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(AppColors.primary.opacity(0.2), lineWidth: 1)
        )
    }
}

// MARK: - Code Block

/// Monospaced code block with optional language label.
struct CodeBlockView: View {
    let language: String?
    let code: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let language, !language.isEmpty {
                HStack {
                    Text(language)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(code, forType: .string)
                    } label: {
                        Image(systemName: "doc.on.doc")
                            .font(.caption2)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 8)
                .padding(.top, 6)
                .padding(.bottom, 2)
            }

            Text(code)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .padding(8)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.windowBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.secondary.opacity(0.2), lineWidth: 1)
        )
    }
}

// MARK: - Citations View

/// Display citations/sources from search-enabled responses.
struct CitationsView: View {
    let citations: [String]

    var body: some View {
        if !citations.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text("Sources")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                ForEach(Array(citations.enumerated()), id: \.offset) { index, citation in
                    HStack(alignment: .top, spacing: 4) {
                        Text("\(index + 1).")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text(citation)
                            .font(.caption)
                            .foregroundStyle(AppColors.primary)
                            .textSelection(.enabled)
                    }
                }
            }
            .padding(8)
            .background(AppColors.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
    }
}
