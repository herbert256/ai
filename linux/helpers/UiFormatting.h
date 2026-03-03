#pragma once
#include <QString>
#include <QDateTime>

namespace UiFormatting {

// Number formatting
QString formatCompactNumber(qint64 value);        // 1500 -> "1.5K", 2300000 -> "2.3M"
QString formatTokenPricePerMillion(double pricePerToken); // "$X.XX / 1M tokens"
QString formatDecimal(double value, int decimals = 2);
QString formatUsd(double value, int decimals = 8); // "$0.00001234"
QString formatCents(double value, int decimals = 4);

// Date/Time formatting
QString formatDate(const QDateTime &date);         // "Jan 15, 2025, 3:30 PM"
QString formatRelativeDate(const QDateTime &date); // "2 hours ago"
QString formatDuration(int ms);                     // "2m 30s"

// Size formatting
QString formatBytes(qint64 bytes);                 // "1.5 MB"

} // namespace UiFormatting
