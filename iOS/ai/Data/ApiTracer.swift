import Foundation

// MARK: - Trace Models

struct TraceRequest: Codable, Sendable {
    let url: String
    let method: String
    let headers: [String: String]
    let body: String?
}

struct TraceResponse: Codable, Sendable {
    let statusCode: Int
    let headers: [String: String]
    let body: String?
}

struct ApiTrace: Codable, Sendable {
    let timestamp: Int64
    let hostname: String
    var reportId: String?
    var model: String?
    let request: TraceRequest
    let response: TraceResponse
}

struct TraceFileInfo: Identifiable, Sendable {
    let filename: String
    let hostname: String
    let timestamp: Int64
    let statusCode: Int
    var reportId: String?
    var model: String?

    var id: String { filename }
}

// MARK: - API Tracer

actor ApiTracer {
    static let shared = ApiTracer()

    private(set) var isTracingEnabled = false
    var currentReportId: String?
    private var traceDir: URL?
    private var sequence: Int64 = 0

    private func ensureDir() -> URL {
        if let dir = traceDir { return dir }
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("trace", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        traceDir = dir
        return dir
    }

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()

    private let decoder = JSONDecoder()

    // MARK: - Control

    func setTracingEnabled(_ enabled: Bool) {
        isTracingEnabled = enabled
    }

    func setReportId(_ id: String?) {
        currentReportId = id
    }

    // MARK: - Save

    func saveTrace(_ trace: ApiTrace) {
        guard isTracingEnabled else { return }
        let dir = ensureDir()
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let dateStr = dateFormatter.string(from: Date(timeIntervalSince1970: Double(trace.timestamp) / 1000))
        sequence += 1
        let seqStr = String(sequence, radix: 36)
        let filename = "\(trace.hostname)_\(dateStr)_\(seqStr).json"
        let file = dir.appendingPathComponent(filename)
        guard let data = try? encoder.encode(trace) else { return }
        try? data.write(to: file, options: .atomic)
    }

    func saveTrace(url: String, method: String, requestHeaders: [String: String],
                   requestBody: String?, statusCode: Int, responseHeaders: [String: String],
                   responseBody: String?, model: String? = nil) {
        let hostname = URL(string: url)?.host ?? "unknown"
        let trace = ApiTrace(
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            hostname: hostname,
            reportId: currentReportId,
            model: model,
            request: TraceRequest(url: url, method: method, headers: requestHeaders, body: requestBody),
            response: TraceResponse(statusCode: statusCode, headers: responseHeaders, body: responseBody)
        )
        saveTrace(trace)
    }

    // MARK: - Query

    func getTraceFiles() -> [TraceFileInfo] {
        let dir = ensureDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return [] }
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { url -> TraceFileInfo? in
                guard let data = try? Data(contentsOf: url),
                      let trace = try? decoder.decode(ApiTrace.self, from: data) else { return nil }
                return TraceFileInfo(
                    filename: url.lastPathComponent,
                    hostname: trace.hostname,
                    timestamp: trace.timestamp,
                    statusCode: trace.response.statusCode,
                    reportId: trace.reportId,
                    model: trace.model
                )
            }
            .sorted { $0.timestamp > $1.timestamp }
    }

    func getTraceFilesForReport(_ reportId: String) -> [TraceFileInfo] {
        getTraceFiles().filter { $0.reportId == reportId }
    }

    func getTraceCount() -> Int {
        let dir = ensureDir()
        let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
        return files?.filter { $0.pathExtension == "json" }.count ?? 0
    }

    func readTraceFile(_ filename: String) -> ApiTrace? {
        let file = ensureDir().appendingPathComponent(filename)
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? decoder.decode(ApiTrace.self, from: data)
    }

    func readTraceFileRaw(_ filename: String) -> String? {
        let file = ensureDir().appendingPathComponent(filename)
        return try? String(contentsOf: file, encoding: .utf8)
    }

    // MARK: - Cleanup

    func clearTraces() {
        let dir = ensureDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for file in files { try? FileManager.default.removeItem(at: file) }
    }

    func deleteTracesOlderThan(_ cutoffTimestamp: Int64) -> Int {
        let dir = ensureDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return 0 }
        var count = 0
        for url in files where url.pathExtension == "json" {
            if let data = try? Data(contentsOf: url),
               let trace = try? decoder.decode(ApiTrace.self, from: data),
               trace.timestamp < cutoffTimestamp {
                try? FileManager.default.removeItem(at: url)
                count += 1
            }
        }
        return count
    }

    // MARK: - Convenience for ApiClient

    func traceIfEnabled(url: String, method: String, requestHeaders: [String: String],
                        requestBody: String?, statusCode: Int, responseHeaders: [String: String],
                        responseBody: String?, timestamp: Date? = nil) {
        guard isTracingEnabled else { return }
        let hostname = URL(string: url)?.host ?? "unknown"
        let ts = Int64((timestamp ?? Date()).timeIntervalSince1970 * 1000)
        // Extract model from request body JSON
        var model: String?
        if let bodyStr = requestBody, let data = bodyStr.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            model = json["model"] as? String
        }
        let trace = ApiTrace(
            timestamp: ts,
            hostname: hostname,
            reportId: currentReportId,
            model: model,
            request: TraceRequest(url: url, method: method, headers: requestHeaders, body: requestBody),
            response: TraceResponse(statusCode: statusCode, headers: responseHeaders, body: responseBody)
        )
        saveTrace(trace)
    }

    // MARK: - Utility

    static func prettyPrintJson(_ json: String) -> String {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: obj, options: .prettyPrinted),
              let result = String(data: pretty, encoding: .utf8) else { return json }
        return result
    }
}
