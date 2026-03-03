import Foundation
import os

/// Thread-safe chat session persistence using actor.
actor ChatHistoryManager {
    static let shared = ChatHistoryManager()

    private static let logger = Logger(subsystem: "com.ai.macAI", category: "ChatHistoryManager")
    private let storageDir: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        storageDir = appSupport.appendingPathComponent("com.ai.macAI/chat-history", isDirectory: true)
        try? FileManager.default.createDirectory(at: storageDir, withIntermediateDirectories: true)

        encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        encoder.dateEncodingStrategy = .millisecondsSince1970

        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
    }

    // MARK: - Save / Load

    func save(_ session: ChatSession) {
        let fileURL = storageDir.appendingPathComponent("\(session.id).json")
        do {
            let data = try encoder.encode(session)
            try data.write(to: fileURL)
        } catch {
            Self.logger.error("Failed to save chat session: \(error.localizedDescription)")
        }
    }

    func load(_ id: String) -> ChatSession? {
        let fileURL = storageDir.appendingPathComponent("\(id).json")
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? decoder.decode(ChatSession.self, from: data)
    }

    func loadAll() -> [ChatSession] {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: storageDir, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }
            return files.compactMap { url -> ChatSession? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? decoder.decode(ChatSession.self, from: data)
            }.sorted { $0.updatedAt > $1.updatedAt }
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
            Self.logger.error("Failed to delete all sessions: \(error.localizedDescription)")
        }
    }

    func count() -> Int {
        (try? FileManager.default.contentsOfDirectory(at: storageDir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }.count) ?? 0
    }

    /// Search sessions by content (messages containing query).
    func search(_ query: String) -> [ChatSession] {
        let lowered = query.lowercased()
        return loadAll().filter { session in
            session.messages.contains { $0.content.lowercased().contains(lowered) }
        }
    }
}
