import Foundation
import os

// MARK: - Trace Models

struct TraceRequest: Codable {
    let url: String
    let method: String
    let headers: [String: String]
    let body: String?
}

struct TraceResponse: Codable {
    let statusCode: Int
    let headers: [String: String]
    let body: String?
}

struct ApiTrace: Codable {
    let timestamp: Date
    let hostname: String
    let reportId: String?
    let model: String?
    let request: TraceRequest
    let response: TraceResponse
}

struct TraceFileInfo: Identifiable {
    let id = UUID()
    let filename: String
    let hostname: String
    let timestamp: Date
    let statusCode: Int
    let reportId: String?
    let model: String?
}

// MARK: - API Tracer Actor

/// Thread-safe API trace storage using actor.
actor ApiTracer {
    static let shared = ApiTracer()

    private static let logger = Logger(subsystem: "com.ai.macAI", category: "ApiTracer")
    private let traceDir: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private var fileSequence: Int64 = 0

    var isTracingEnabled = false
    var currentReportId: String?

    private init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        traceDir = appSupport.appendingPathComponent("com.ai.macAI/trace", isDirectory: true)
        try? FileManager.default.createDirectory(at: traceDir, withIntermediateDirectories: true)

        encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .millisecondsSince1970

        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
    }

    // MARK: - Trace If Enabled

    func traceIfEnabled(
        url: String,
        method: String,
        requestHeaders: [String: String],
        requestBody: String?,
        statusCode: Int,
        responseHeaders: [String: String],
        responseBody: String?,
        timestamp: Date
    ) {
        guard isTracingEnabled else { return }

        let hostname = URL(string: url)?.host ?? "unknown"

        // Extract model from request body
        var model: String?
        if let body = requestBody, let data = body.data(using: .utf8) {
            if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                model = json["model"] as? String
            }
        }

        let trace = ApiTrace(
            timestamp: timestamp,
            hostname: hostname,
            reportId: currentReportId,
            model: model,
            request: TraceRequest(url: url, method: method, headers: requestHeaders, body: requestBody),
            response: TraceResponse(statusCode: statusCode, headers: responseHeaders, body: responseBody)
        )

        saveTrace(trace)
    }

    // MARK: - Save

    private func saveTrace(_ trace: ApiTrace) {
        fileSequence += 1
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let timestamp = formatter.string(from: trace.timestamp)
        let seq = String(fileSequence, radix: 36)
        let filename = "\(trace.hostname)_\(timestamp)_\(seq).json"
        let fileURL = traceDir.appendingPathComponent(filename)

        do {
            let data = try encoder.encode(trace)
            try data.write(to: fileURL)
        } catch {
            Self.logger.error("Failed to save trace: \(error.localizedDescription)")
        }
    }

    // MARK: - Read

    func getTraceFiles() -> [TraceFileInfo] {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: traceDir, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "json" }

            return files.compactMap { fileURL -> TraceFileInfo? in
                guard let data = try? Data(contentsOf: fileURL),
                      let trace = try? decoder.decode(ApiTrace.self, from: data) else { return nil }
                return TraceFileInfo(
                    filename: fileURL.lastPathComponent,
                    hostname: trace.hostname,
                    timestamp: trace.timestamp,
                    statusCode: trace.response.statusCode,
                    reportId: trace.reportId,
                    model: trace.model
                )
            }.sorted { $0.timestamp > $1.timestamp }
        } catch {
            return []
        }
    }

    func readTrace(_ filename: String) -> ApiTrace? {
        let fileURL = traceDir.appendingPathComponent(filename)
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return try? decoder.decode(ApiTrace.self, from: data)
    }

    func setTracingEnabled(_ enabled: Bool) {
        isTracingEnabled = enabled
    }

    func setCurrentReportId(_ id: String?) {
        currentReportId = id
    }

    func clearTraces() {
        do {
            let files = try FileManager.default.contentsOfDirectory(at: traceDir, includingPropertiesForKeys: nil)
            for file in files where file.pathExtension == "json" {
                try FileManager.default.removeItem(at: file)
            }
        } catch {
            Self.logger.error("Failed to clear traces: \(error.localizedDescription)")
        }
    }

    func getTraceCount() -> Int {
        (try? FileManager.default.contentsOfDirectory(at: traceDir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }.count) ?? 0
    }
}
