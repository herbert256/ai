import Foundation

// MARK: - Chat History Manager

actor ChatHistoryManager {
    static let shared = ChatHistoryManager()

    private var historyDir: URL?
    private(set) var version: Int64 = 0

    private func ensureDir() -> URL {
        if let dir = historyDir { return dir }
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat-history", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        historyDir = dir
        return dir
    }

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = .prettyPrinted
        return e
    }()

    private let decoder = JSONDecoder()

    // MARK: - Save

    func saveSession(_ session: ChatSession) -> Bool {
        let file = ensureDir().appendingPathComponent("\(session.id).json")
        guard let data = try? encoder.encode(session) else { return false }
        do {
            try data.write(to: file, options: .atomic)
            version = Int64(Date().timeIntervalSince1970 * 1000)
            return true
        } catch {
            return false
        }
    }

    // MARK: - Load

    func loadSession(_ sessionId: String) -> ChatSession? {
        let file = ensureDir().appendingPathComponent("\(sessionId).json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? decoder.decode(ChatSession.self, from: data)
    }

    func getAllSessions() -> [ChatSession] {
        let dir = ensureDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return [] }
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(ChatSession.self, from: Data(contentsOf: $0)) }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    func getSessionCount() -> Int {
        let dir = ensureDir()
        let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        return files?.filter { $0.pathExtension == "json" }.count ?? 0
    }

    // MARK: - Delete

    func deleteSession(_ sessionId: String) -> Bool {
        let file = ensureDir().appendingPathComponent("\(sessionId).json")
        do {
            try FileManager.default.removeItem(at: file)
            version = Int64(Date().timeIntervalSince1970 * 1000)
            return true
        } catch {
            return false
        }
    }

    func clearHistory() {
        let dir = ensureDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        var deleted = false
        for file in files {
            try? FileManager.default.removeItem(at: file)
            deleted = true
        }
        if deleted {
            version = Int64(Date().timeIntervalSince1970 * 1000)
        }
    }
}
