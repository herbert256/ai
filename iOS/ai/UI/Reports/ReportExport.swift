import Foundation

// MARK: - HTML Report Generation

struct ReportExport {

    static func generateHtml(report: Report) -> String {
        var html = """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>\(escapeHtml(report.title))</title>
        <style>
        body { font-family: -apple-system, system-ui, sans-serif; background: #121212; color: #e0e0e0; margin: 0; padding: 16px; }
        h1 { color: #D0C3FF; font-size: 1.5em; }
        h2 { color: #C8BFF8; font-size: 1.2em; border-bottom: 1px solid #333; padding-bottom: 8px; }
        .card { background: #1E1E1E; border-radius: 12px; padding: 16px; margin: 12px 0; border: 1px solid #333; }
        .success { border-left: 3px solid #4CAF50; }
        .error { border-left: 3px solid #F44336; }
        .meta { color: #888; font-size: 0.85em; margin-top: 8px; }
        .prompt { background: #2A2A2A; padding: 12px; border-radius: 8px; margin: 8px 0; white-space: pre-wrap; }
        pre { background: #2A2A2A; padding: 12px; border-radius: 8px; overflow-x: auto; white-space: pre-wrap; }
        .cost { color: #FFD54F; }
        .tokens { color: #81D4FA; }
        </style>
        </head><body>
        <h1>\(escapeHtml(report.title))</h1>
        <div class="meta">Generated: \(UiFormatting.formatTimestamp(report.timestamp))</div>
        """

        if let rapport = report.rapportText, !rapport.isEmpty {
            html += "<div class=\"card\">\(rapport)</div>"
        }

        html += "<div class=\"prompt\"><strong>Prompt:</strong><br>\(escapeHtml(report.prompt))</div>"

        for agent in report.agents {
            let statusClass = agent.reportStatus == .success ? "success" : "error"
            html += """
            <div class="card \(statusClass)">
            <h2>\(escapeHtml(agent.agentName))</h2>
            <div class="meta">\(escapeHtml(agent.provider)) / \(escapeHtml(agent.model))</div>
            """

            if let body = agent.responseBody {
                html += "<pre>\(escapeHtml(body))</pre>"
            }

            if let error = agent.errorMessage {
                html += "<div style=\"color: #F44336;\">Error: \(escapeHtml(error))</div>"
            }

            if let usage = agent.tokenUsage {
                html += "<div class=\"meta\">"
                html += "<span class=\"tokens\">In: \(UiFormatting.formatTokens(usage.inputTokens)) | Out: \(UiFormatting.formatTokens(usage.outputTokens))</span>"
                if let cost = agent.cost {
                    html += " | <span class=\"cost\">\(UiFormatting.formatCost(cost))</span>"
                }
                if let duration = agent.durationMs {
                    html += " | \(UiFormatting.formatDuration(duration))"
                }
                html += "</div>"
            }

            html += "</div>"
        }

        if report.totalCost > 0 {
            html += "<div class=\"meta\">Total cost: <span class=\"cost\">\(UiFormatting.formatCost(report.totalCost))</span></div>"
        }

        if let close = report.closeText, !close.isEmpty {
            html += "<div class=\"card\">\(close)</div>"
        }

        html += "</body></html>"
        return html
    }

    private static func escapeHtml(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }
}
