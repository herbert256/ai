import SwiftUI

// MARK: - Trace Screen

struct TraceScreen: View {
    @State private var traceFiles: [TraceFileInfo] = []
    @State private var searchText = ""

    var body: some View {
        VStack(spacing: 0) {
            List {
                if filteredFiles.isEmpty {
                    EmptyStateView(icon: "doc.text.magnifyingglass", title: "No API traces")
                } else {
                    ForEach(filteredFiles) { info in
                        NavigationLink(destination: TraceDetailScreen(filename: info.filename)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(info.hostname)
                                    .font(.caption)
                                    .foregroundStyle(AppColors.onSurface)
                                    .lineLimit(1)
                                HStack {
                                    Text(UiFormatting.formatTimestamp(info.timestamp))
                                        .font(.caption2)
                                        .foregroundStyle(AppColors.dimText)
                                    if info.statusCode > 0 {
                                        Text("HTTP \(info.statusCode)")
                                            .font(.caption2)
                                            .foregroundStyle(info.statusCode < 400 ? AppColors.success : AppColors.error)
                                    }
                                    if let model = info.model {
                                        Text(model)
                                            .font(.caption2)
                                            .foregroundStyle(AppColors.accentBlue)
                                            .lineLimit(1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(AppColors.background)

            // Clear button
            Button(role: .destructive) {
                Task {
                    await ApiTracer.shared.clearTraces()
                    traceFiles = await ApiTracer.shared.getTraceFiles()
                }
            } label: {
                Text("Clear Traces")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.error)
            .disabled(traceFiles.isEmpty)
            .padding()
        }
        .background(AppColors.background)
        .searchable(text: $searchText, prompt: "Search traces...")
        .navigationTitle("API Traces")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            traceFiles = await ApiTracer.shared.getTraceFiles()
        }
    }

    private var filteredFiles: [TraceFileInfo] {
        guard !searchText.isEmpty else { return traceFiles }
        return traceFiles.filter {
            $0.hostname.localizedCaseInsensitiveContains(searchText) ||
            ($0.model?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }
}

// MARK: - Trace Detail Screen

struct TraceDetailScreen: View {
    let filename: String
    @State private var traceContent: String?

    var body: some View {
        Group {
            if let content = traceContent {
                ScrollView {
                    Text(content)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(AppColors.onSurface)
                        .textSelection(.enabled)
                        .padding()
                }
            } else {
                ProgressView()
            }
        }
        .background(AppColors.background)
        .navigationTitle("Trace Detail")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            traceContent = await ApiTracer.shared.readTraceFileRaw(filename)
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    if let content = traceContent {
                        let tempUrl = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
                        try? content.write(to: tempUrl, atomically: true, encoding: .utf8)
                        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
                              let window = scene.windows.first else { return }
                        let activityVC = UIActivityViewController(activityItems: [tempUrl], applicationActivities: nil)
                        window.rootViewController?.present(activityVC, animated: true)
                    }
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        }
    }
}
