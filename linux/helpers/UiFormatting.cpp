#include "UiFormatting.h"
#include <QLocale>
#include <cmath>

namespace UiFormatting {

QString formatCompactNumber(qint64 value) {
    if (value < 0) return "-" + formatCompactNumber(-value);
    if (value < 1000) return QString::number(value);
    if (value < 1000000) {
        double v = value / 1000.0;
        return QString::number(v, 'f', v < 10 ? 1 : 0) + "K";
    }
    if (value < 1000000000LL) {
        double v = value / 1000000.0;
        return QString::number(v, 'f', v < 10 ? 1 : 0) + "M";
    }
    double v = value / 1000000000.0;
    return QString::number(v, 'f', v < 10 ? 1 : 0) + "B";
}

QString formatTokenPricePerMillion(double pricePerToken) {
    double perMillion = pricePerToken * 1000000.0;
    if (perMillion < 0.01) return "$0.00 / 1M";
    return "$" + QString::number(perMillion, 'f', 2) + " / 1M";
}

QString formatDecimal(double value, int decimals) {
    return QString::number(value, 'f', decimals);
}

QString formatUsd(double value, int decimals) {
    return "$" + QString::number(value, 'f', decimals);
}

QString formatCents(double value, int decimals) {
    return QString::number(value, 'f', decimals) + "\u00A2";
}

QString formatDate(const QDateTime &date) {
    if (!date.isValid()) return "";
    return QLocale(QLocale::English).toString(date, "MMM d, yyyy, h:mm AP");
}

QString formatRelativeDate(const QDateTime &date) {
    if (!date.isValid()) return "";
    qint64 secs = date.secsTo(QDateTime::currentDateTime());
    if (secs < 0) return "just now";
    if (secs < 60) return "just now";
    if (secs < 3600) {
        int mins = static_cast<int>(secs / 60);
        return QString::number(mins) + (mins == 1 ? " minute ago" : " minutes ago");
    }
    if (secs < 86400) {
        int hours = static_cast<int>(secs / 3600);
        return QString::number(hours) + (hours == 1 ? " hour ago" : " hours ago");
    }
    if (secs < 604800) {
        int days = static_cast<int>(secs / 86400);
        return QString::number(days) + (days == 1 ? " day ago" : " days ago");
    }
    if (secs < 2592000) {
        int weeks = static_cast<int>(secs / 604800);
        return QString::number(weeks) + (weeks == 1 ? " week ago" : " weeks ago");
    }
    return formatDate(date);
}

QString formatDuration(int ms) {
    if (ms < 1000) return QString::number(ms) + "ms";
    if (ms < 60000) {
        double secs = ms / 1000.0;
        return QString::number(secs, 'f', 1) + "s";
    }
    int mins = ms / 60000;
    int secs = (ms % 60000) / 1000;
    if (secs == 0) return QString::number(mins) + "m";
    return QString::number(mins) + "m " + QString::number(secs) + "s";
}

QString formatBytes(qint64 bytes) {
    if (bytes < 0) return "0 B";
    if (bytes < 1024) return QString::number(bytes) + " B";
    if (bytes < 1048576) return QString::number(bytes / 1024.0, 'f', 1) + " KB";
    if (bytes < 1073741824LL) return QString::number(bytes / 1048576.0, 'f', 1) + " MB";
    return QString::number(bytes / 1073741824.0, 'f', 1) + " GB";
}

} // namespace UiFormatting
