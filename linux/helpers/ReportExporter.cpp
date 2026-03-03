#include "ReportExporter.h"
#include "helpers/UiFormatting.h"

#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QRegularExpression>
#include <QUrl>

namespace ReportExporter {

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

static QString escapeHtml(const QString &text)
{
    QString out = text;
    out.replace(QLatin1Char('&'), QStringLiteral("&amp;"));
    out.replace(QLatin1Char('<'), QStringLiteral("&lt;"));
    out.replace(QLatin1Char('>'), QStringLiteral("&gt;"));
    out.replace(QLatin1Char('"'), QStringLiteral("&quot;"));
    return out;
}

/// Simple markdown-to-HTML conversion for analysis text.
/// Escapes HTML entities first, then converts markdown constructs.
static QString convertMarkdownToHtml(const QString &raw)
{
    QString text = escapeHtml(raw);

    // Fenced code blocks: ```lang\n...\n```
    {
        static const QRegularExpression codeBlockRx(
            QStringLiteral("```(\\w*)\\n(.*?)```"),
            QRegularExpression::DotMatchesEverythingOption);
        text.replace(codeBlockRx, QStringLiteral("<pre><code>\\2</code></pre>"));
    }

    // Inline code: `...`
    {
        static const QRegularExpression inlineCodeRx(
            QStringLiteral("`([^`]+)`"));
        text.replace(inlineCodeRx, QStringLiteral("<code>\\1</code>"));
    }

    // Bold: **...**
    {
        static const QRegularExpression boldRx(
            QStringLiteral("\\*\\*(.+?)\\*\\*"));
        text.replace(boldRx, QStringLiteral("<strong>\\1</strong>"));
    }

    // Italic: *...*
    {
        static const QRegularExpression italicRx(
            QStringLiteral("\\*(.+?)\\*"));
        text.replace(italicRx, QStringLiteral("<em>\\1</em>"));
    }

    // Line breaks
    text.replace(QLatin1Char('\n'), QStringLiteral("<br>"));

    return text;
}

// ---------------------------------------------------------------------------
// CSS for the dark-themed HTML report
// ---------------------------------------------------------------------------

static const char *kReportCss = R"CSS(
body { background: #0A0A0A; color: #EEEEEE; font-family: -apple-system, sans-serif; max-width: 900px; margin: 0 auto; padding: 20px; }
.card { background: #121215; border: 1px solid #222228; border-radius: 8px; padding: 16px; margin: 12px 0; }
.provider { color: #4A9EFF; font-weight: bold; }
.error { color: #FF4757; }
.tokens { color: #888888; font-size: 0.85em; }
.cost { color: #2ECC71; }
pre { background: #15151A; border: 1px solid #2A2A35; border-radius: 4px; padding: 12px; overflow-x: auto; }
code { font-family: monospace; }
h1 { color: #4A9EFF; }
h2 { color: #EEEEEE; border-bottom: 1px solid #222228; padding-bottom: 8px; }
.prompt { background: #15151A; border-left: 3px solid #4A9EFF; padding: 12px; margin: 12px 0; }
.meta { color: #888888; font-size: 0.9em; }
)CSS";

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

QString generateHtml(const StoredReport &report)
{
    QString html;
    html.reserve(8192);

    // -- Head
    html += QStringLiteral("<!DOCTYPE html>\n<html>\n<head>\n");
    html += QStringLiteral("<meta charset=\"utf-8\">\n");
    html += QStringLiteral("<title>") + escapeHtml(report.title) + QStringLiteral("</title>\n");
    html += QStringLiteral("<style>") + QString::fromLatin1(kReportCss) + QStringLiteral("</style>\n");
    html += QStringLiteral("</head>\n<body>\n");

    // -- Title + timestamp
    html += QStringLiteral("<h1>") + escapeHtml(report.title) + QStringLiteral("</h1>\n");
    html += QStringLiteral("<p class=\"meta\">Generated: ")
          + escapeHtml(UiFormatting::formatDate(report.timestamp))
          + QStringLiteral("</p>\n");

    // -- Prompt section
    html += QStringLiteral("<h2>Prompt</h2>\n");
    html += QStringLiteral("<div class=\"prompt\">") + escapeHtml(report.prompt) + QStringLiteral("</div>\n");

    // -- Results
    html += QStringLiteral("<h2>Results</h2>\n");

    for (const StoredAnalysisResult &result : report.results) {
        html += QStringLiteral("<div class=\"card\">\n");

        // Provider / model / agent header
        html += QStringLiteral("<div class=\"provider\">");
        html += escapeHtml(result.providerId)
              + QStringLiteral(" / ")
              + escapeHtml(result.model);
        if (result.agentName.has_value() && !result.agentName->isEmpty()) {
            html += QStringLiteral(" &mdash; ") + escapeHtml(*result.agentName);
        }
        html += QStringLiteral("</div>\n");

        // Error (if present)
        if (result.error.has_value() && !result.error->isEmpty()) {
            html += QStringLiteral("<p class=\"error\">") + escapeHtml(*result.error) + QStringLiteral("</p>\n");
        }

        // Analysis text
        if (result.analysis.has_value() && !result.analysis->isEmpty()) {
            html += QStringLiteral("<div class=\"analysis\">") + convertMarkdownToHtml(*result.analysis) + QStringLiteral("</div>\n");
        }

        // Token usage
        if (result.inputTokens > 0 || result.outputTokens > 0) {
            html += QStringLiteral("<p class=\"tokens\">Tokens: ")
                  + QString::number(result.inputTokens) + QStringLiteral(" in / ")
                  + QString::number(result.outputTokens) + QStringLiteral(" out")
                  + QStringLiteral("</p>\n");
        }

        // Cost
        if (result.apiCost.has_value()) {
            html += QStringLiteral("<p class=\"cost\">Cost: $")
                  + QString::number(*result.apiCost, 'f', 8)
                  + QStringLiteral("</p>\n");
        }

        // Citations
        if (result.citations.has_value() && !result.citations->isEmpty()) {
            html += QStringLiteral("<div class=\"meta\"><strong>Citations:</strong><br>\n");
            int idx = 1;
            for (const QString &citation : *result.citations) {
                html += QString::number(idx++) + QStringLiteral(". ") + escapeHtml(citation) + QStringLiteral("<br>\n");
            }
            html += QStringLiteral("</div>\n");
        }

        html += QStringLiteral("</div>\n"); // .card
    }

    // -- Footer
    html += QStringLiteral("<p class=\"meta\" style=\"text-align: center; margin-top: 30px;\">Generated by AI</p>\n");
    html += QStringLiteral("</body>\n</html>\n");

    return html;
}

void openHtml(const QString &html, const QString &title)
{
    Q_UNUSED(title)

    const qint64 timestamp = QDateTime::currentMSecsSinceEpoch();
    const QString path = QStringLiteral("/tmp/ai_report_%1.html").arg(timestamp);

    QFile file(path);
    if (file.open(QIODevice::WriteOnly | QIODevice::Text)) {
        file.write(html.toUtf8());
        file.close();
        QDesktopServices::openUrl(QUrl::fromLocalFile(path));
    }
}

void exportAndOpen(const StoredReport &report)
{
    const QString html = generateHtml(report);
    openHtml(html, report.title);
}

} // namespace ReportExporter
