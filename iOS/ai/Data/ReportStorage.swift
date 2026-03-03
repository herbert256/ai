import Foundation

// MARK: - Report Storage

actor ReportStorage {
    static let shared = ReportStorage()

    private var reportsDir: URL?

    private func ensureDir() -> URL {
        if let dir = reportsDir { return dir }
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        reportsDir = dir
        return dir
    }

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()

    private let decoder = JSONDecoder()

    // MARK: - Create

    func createReport(title: String, prompt: String, agents: [ReportAgent],
                      rapportText: String? = nil, reportType: ReportType = .classic,
                      closeText: String? = nil) -> Report {
        let report = Report(
            title: title,
            prompt: prompt,
            agents: agents,
            rapportText: rapportText,
            reportType: reportType,
            closeText: closeText
        )
        saveReport(report)
        return report
    }

    // MARK: - Update

    func updateAgentStatus(reportId: String, agentId: String,
                           status: ReportStatus,
                           httpStatus: Int? = nil,
                           requestHeaders: String? = nil,
                           requestBody: String? = nil,
                           responseHeaders: String? = nil,
                           responseBody: String? = nil,
                           errorMessage: String? = nil,
                           tokenUsage: TokenUsage? = nil,
                           cost: Double? = nil,
                           citations: [String]? = nil,
                           searchResults: [SearchResult]? = nil,
                           relatedQuestions: [String]? = nil,
                           durationMs: Int64? = nil) {
        guard var report = getReport(reportId) else { return }
        guard let idx = report.agents.firstIndex(where: { $0.agentId == agentId }) else { return }

        report.agents[idx].reportStatus = status
        if let v = httpStatus { report.agents[idx].httpStatus = v }
        if let v = requestHeaders { report.agents[idx].requestHeaders = v }
        if let v = requestBody { report.agents[idx].requestBody = v }
        if let v = responseHeaders { report.agents[idx].responseHeaders = v }
        if let v = responseBody { report.agents[idx].responseBody = v }
        if let v = errorMessage { report.agents[idx].errorMessage = v }
        if let v = tokenUsage { report.agents[idx].tokenUsage = v }
        if let v = cost { report.agents[idx].cost = v }
        if let v = citations { report.agents[idx].citations = v }
        if let v = searchResults { report.agents[idx].searchResults = v }
        if let v = relatedQuestions { report.agents[idx].relatedQuestions = v }
        if let v = durationMs { report.agents[idx].durationMs = v }

        // Recalculate total cost
        report.totalCost = report.agents.compactMap(\.cost).reduce(0, +)

        // Check if all agents are done
        if report.agents.allSatisfy({ $0.reportStatus.isTerminal }) {
            report.completedAt = Int64(Date().timeIntervalSince1970 * 1000)
        }

        saveReport(report)
    }

    func markAgentRunning(reportId: String, agentId: String,
                          requestHeaders: String? = nil, requestBody: String? = nil) {
        updateAgentStatus(reportId: reportId, agentId: agentId, status: .running,
                          requestHeaders: requestHeaders, requestBody: requestBody)
    }

    func markAgentSuccess(reportId: String, agentId: String,
                          httpStatus: Int?, responseHeaders: String?,
                          responseBody: String?, tokenUsage: TokenUsage?,
                          cost: Double?, citations: [String]? = nil,
                          searchResults: [SearchResult]? = nil,
                          relatedQuestions: [String]? = nil,
                          durationMs: Int64? = nil) {
        updateAgentStatus(reportId: reportId, agentId: agentId, status: .success,
                          httpStatus: httpStatus, responseHeaders: responseHeaders,
                          responseBody: responseBody, tokenUsage: tokenUsage,
                          cost: cost, citations: citations, searchResults: searchResults,
                          relatedQuestions: relatedQuestions, durationMs: durationMs)
    }

    func markAgentError(reportId: String, agentId: String,
                        httpStatus: Int? = nil, errorMessage: String? = nil,
                        responseHeaders: String? = nil, responseBody: String? = nil,
                        durationMs: Int64? = nil) {
        updateAgentStatus(reportId: reportId, agentId: agentId, status: .error,
                          httpStatus: httpStatus, responseHeaders: responseHeaders,
                          responseBody: responseBody, errorMessage: errorMessage,
                          durationMs: durationMs)
    }

    func markAgentStopped(reportId: String, agentId: String) {
        updateAgentStatus(reportId: reportId, agentId: agentId, status: .stopped)
    }

    // MARK: - Query

    func getReport(_ reportId: String) -> Report? {
        let file = ensureDir().appendingPathComponent("\(reportId).json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? decoder.decode(Report.self, from: data)
    }

    func getAllReports() -> [Report] {
        let dir = ensureDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return [] }
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(Report.self, from: Data(contentsOf: $0)) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    func getReportsByDateRange(start: Int64, end: Int64) -> [Report] {
        getAllReports().filter { $0.timestamp >= start && $0.timestamp <= end }
    }

    // MARK: - Delete

    func deleteReport(_ reportId: String) {
        let file = ensureDir().appendingPathComponent("\(reportId).json")
        try? FileManager.default.removeItem(at: file)
    }

    func deleteAllReports() {
        let dir = ensureDir()
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for file in files { try? FileManager.default.removeItem(at: file) }
    }

    // MARK: - Private

    private func saveReport(_ report: Report) {
        let file = ensureDir().appendingPathComponent("\(report.id).json")
        guard let data = try? encoder.encode(report) else { return }
        try? data.write(to: file, options: .atomic)
    }
}
