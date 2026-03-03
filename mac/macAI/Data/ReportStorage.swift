import Foundation
import os

/// Stored report with all analysis results.
struct StoredReport: Codable, Identifiable {
    let id: String
    let title: String
    let prompt: String
    let timestamp: Date
    var results: [StoredAnalysisResult]

    struct StoredAnalysisResult: Codable, Identifiable {
        let id: String
        let providerId: String
        let model: String
        let agentName: String?
        let analysis: String?
        let error: String?
        let inputTokens: Int
        let outputTokens: Int
        let apiCost: Double?
        let citations: [String]?
        let rawUsageJson: String?

        init(from response: AnalysisResponse) {
            self.id = UUID().uuidString
            self.providerId = response.service.id
            self.model = ""  // TODO: track model in AnalysisResponse
            self.agentName = response.agentName
            self.analysis = response.analysis
            self.error = response.error
            self.inputTokens = response.tokenUsage?.inputTokens ?? 0
            self.outputTokens = response.tokenUsage?.outputTokens ?? 0
            self.apiCost = response.tokenUsage?.apiCost
            self.citations = response.citations
            self.rawUsageJson = response.rawUsageJson
        }
    }
}

/// Thread-safe report persistence using actor.
actor ReportStorage {
    static let shared = ReportStorage()

    private static let logger = Logger(subsystem: "com.ai.macAI", category: "ReportStorage")
    private let storageDir: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        storageDir = appSupport.appendingPathComponent("com.ai.macAI/reports", isDirectory: true)
        try? FileManager.default.createDirectory(at: storageDir, withIntermediateDirectories: true)

        encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        encoder.dateEncodingStrategy = .millisecondsSince1970

        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
    }

    // MARK: - Save / Load

    func save(_ report: StoredReport) {
        let fileURL = storageDir.appendingPathComponent("\(report.id).json")
        do {
            let data = try encoder.encode(report)
            try data.write(to: fileURL)
        } catch {
            Self.logger.error("Failed to save report: \(error.localizedDescription)")
        }
    }

    func load(_ id: String) -> StoredReport? {
        let fileURL = storageDir.appendingPathComponent("\(id).json")
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? decoder.decode(StoredReport.self, from: data)
    }

    func loadAll() -> [StoredReport] {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: storageDir, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
            return files.compactMap { url -> StoredReport? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? decoder.decode(StoredReport.self, from: data)
            }.sorted { $0.timestamp > $1.timestamp }
        } catch {
            return []
        }
    }

    func delete(_ id: String) {
        let fileURL = storageDir.appendingPathComponent("\(id).json")
        try? FileManager.default.removeItem(at: fileURL)
    }

    func deleteAll() {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: storageDir, includingPropertiesForKeys: nil)
            for file in files where file.pathExtension == "json" {
                try FileManager.default.removeItem(at: file)
            }
        } catch {
            Self.logger.error("Failed to delete all reports: \(error.localizedDescription)")
        }
    }

    func count() -> Int {
        (try? FileManager.default.contentsOfDirectory(at: storageDir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }.count) ?? 0
    }
}
