import SwiftUI
import AppKit

/// API trace viewer - request/response logging.
struct TraceView: View {
    @State private var traceFiles: [TraceFileInfo] = []
    @State private var selectedFile: TraceFileInfo?
    @State private var selectedTrace: ApiTrace?
    @State private var searchText = ""

    private var filteredTraces: [TraceFileInfo] {
        if searchText.isEmpty { return traceFiles }
        let lower = searchText.lowercased()
        return traceFiles.filter {
            $0.hostname.lowercased().contains(lower) ||
            ($0.model ?? "").lowercased().contains(lower)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            TextField("Search traces...", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .padding()

            if filteredTraces.isEmpty {
                EmptyStateView(
                    icon: "network",
                    title: "No Traces",
                    message: traceFiles.isEmpty ? "API traces will appear as requests are made" : "No matching traces"
                )
            } else {
                HSplitView {
                    // Trace list
                    List(filteredTraces, selection: Binding(
                        get: { selectedFile?.id },
                        set: { id in
                            selectedFile = filteredTraces.first { $0.id == id }
                            if let file = selectedFile {
                                Task {
                                    selectedTrace = await ApiTracer.shared.readTrace(file.filename)
                                }
                            }
                        }
                    )) { file in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(file.hostname)
                                    .font(.caption.bold())
                                    .lineLimit(1)
                                Spacer()
                                Text("\(file.statusCode)")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(statusColor(file.statusCode))
                            }
                            HStack {
                                Text(file.model ?? "")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                Spacer()
                                Text(formatDate(file.timestamp))
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        .tag(file.id)
                    }
                    .frame(minWidth: 250)

                    // Trace detail
                    if let trace = selectedTrace {
                        TraceDetailView(trace: trace)
                    } else {
                        Text("Select a trace")
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
            }

            HStack {
                Text("\(filteredTraces.count) traces")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Refresh") {
                    loadTraces()
                }
                .controlSize(.small)
                Button("Clear All") {
                    Task {
                        await ApiTracer.shared.clearTraces()
                        traceFiles = []
                        selectedFile = nil
                        selectedTrace = nil
                    }
                }
                .foregroundStyle(.red)
                .controlSize(.small)
            }
            .padding()
        }
        .navigationTitle("API Traces")
        .task {
            loadTraces()
        }
    }

    private func loadTraces() {
        Task {
            traceFiles = await ApiTracer.shared.getTraceFiles()
        }
    }

    private func statusColor(_ code: Int) -> Color {
        switch code {
        case 200...299: return AppColors.statusOk
        case 400...499: return .orange
        case 500...599: return AppColors.statusError
        default: return .white
        }
    }
}

// MARK: - Trace Detail

struct TraceDetailView: View {
    let trace: ApiTrace
    @State private var selectedTab = "all"

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // URL bar
            HStack {
                Text(trace.request.url)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Spacer()
                Text("\(trace.response.statusCode)")
                    .font(.caption.bold().monospacedDigit())
                    .foregroundStyle(trace.response.statusCode >= 200 && trace.response.statusCode < 300 ? AppColors.statusOk : AppColors.statusError)
                Button {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(traceText(), forType: .string)
                } label: {
                    Image(systemName: "doc.on.doc")
                }
                .buttonStyle(.plain)
                .help("Copy to clipboard")
            }
            .padding(8)

            Divider()

            // Tab bar
            HStack(spacing: 0) {
                ForEach(["all", "reqHdr", "rspHdr", "reqData", "rspData"], id: \.self) { tab in
                    Button(tabLabel(tab)) {
                        selectedTab = tab
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(selectedTab == tab ? AppColors.primary.opacity(0.2) : Color.clear)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                }
            }
            .padding(4)

            Divider()

            // Content
            ScrollView {
                Text(contentForTab())
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(8)
            }
        }
    }

    private func tabLabel(_ tab: String) -> String {
        switch tab {
        case "all": return "All"
        case "reqHdr": return "Req Headers"
        case "rspHdr": return "Rsp Headers"
        case "reqData": return "Req Body"
        case "rspData": return "Rsp Body"
        default: return tab
        }
    }

    private func formatHeaders(_ headers: [String: String]) -> String {
        headers.sorted { $0.key < $1.key }.map { "\($0.key): \($0.value)" }.joined(separator: "\n")
    }

    private func contentForTab() -> String {
        switch selectedTab {
        case "reqHdr": return formatHeaders(trace.request.headers)
        case "rspHdr": return formatHeaders(trace.response.headers)
        case "reqData": return prettyJson(trace.request.body ?? "")
        case "rspData": return prettyJson(trace.response.body ?? "")
        default: return traceText()
        }
    }

    private func traceText() -> String {
        """
        === REQUEST ===
        URL: \(trace.request.url)
        Method: \(trace.request.method)

        --- Headers ---
        \(formatHeaders(trace.request.headers))

        --- Body ---
        \(prettyJson(trace.request.body ?? ""))

        === RESPONSE ===
        Status: \(trace.response.statusCode)

        --- Headers ---
        \(formatHeaders(trace.response.headers))

        --- Body ---
        \(prettyJson(trace.response.body ?? ""))
        """
    }

    private func prettyJson(_ text: String) -> String {
        guard !text.isEmpty,
              let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]),
              let str = String(data: pretty, encoding: .utf8) else {
            return text
        }
        return str
    }
}
