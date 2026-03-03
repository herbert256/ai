// ApiTracer.h - API request/response tracing to individual JSON files
// Storage directory: ~/.local/share/AI/trace/

#pragma once

#include <QObject>
#include <QMutex>
#include <QDateTime>
#include <QJsonObject>
#include <QMap>
#include <optional>

// ---------------------------------------------------------------------------
// Trace data structures
// ---------------------------------------------------------------------------

struct TraceRequest {
    QString url;
    QString method;
    QMap<QString, QString> headers;
    QString body;

    QJsonObject toJson() const;
    static TraceRequest fromJson(const QJsonObject &obj);
};

struct TraceResponse {
    int statusCode = 0;
    QMap<QString, QString> headers;
    QString body;

    QJsonObject toJson() const;
    static TraceResponse fromJson(const QJsonObject &obj);
};

struct ApiTrace {
    QDateTime timestamp;
    QString hostname;
    QString reportId;
    QString model;
    TraceRequest request;
    TraceResponse response;

    QJsonObject toJson() const;
    static ApiTrace fromJson(const QJsonObject &obj);
};

struct TraceFileInfo {
    QString id;
    QString filename;
    QString hostname;
    QDateTime timestamp;
    int statusCode = 0;
    QString reportId;
    QString model;
};

// ---------------------------------------------------------------------------
// ApiTracer singleton
// ---------------------------------------------------------------------------

class ApiTracer : public QObject {
    Q_OBJECT
public:
    static ApiTracer& instance();

    bool isTracingEnabled() const { return m_enabled; }
    void setTracingEnabled(bool enabled);

    QString currentReportId() const { return m_currentReportId; }
    void setCurrentReportId(const QString &id);

    void traceIfEnabled(const QString &url, const QString &method,
                        const QMap<QString, QString> &requestHeaders,
                        const QString &requestBody,
                        int statusCode,
                        const QMap<QString, QString> &responseHeaders,
                        const QString &responseBody);

    QList<TraceFileInfo> getTraceFiles();
    std::optional<ApiTrace> readTrace(const QString &filename);
    void clearTraces();
    int getTraceCount();

private:
    explicit ApiTracer(QObject *parent = nullptr);

    QMutex m_mutex;
    bool m_enabled = false;
    QString m_currentReportId;
    int m_sequence = 0;
    QString m_storageDir; // ~/.local/share/AI/trace/

    void ensureDir();
    static QString extractHostname(const QString &url);
    static QString extractModel(const QString &requestBody);
};
