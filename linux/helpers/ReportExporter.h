#pragma once
#include <QString>
#include "data/DataModels.h"

namespace ReportExporter {

// Generate HTML report and open in default browser
void exportAndOpen(const StoredReport &report);

// Generate HTML string from a report
QString generateHtml(const StoredReport &report);

// Save HTML to temp file and open with xdg-open
void openHtml(const QString &html, const QString &title);

} // namespace ReportExporter
