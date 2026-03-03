// ApiTracer.cpp - API request/response tracing to individual JSON files

#include "ApiTracer.h"
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutexLocker>
#include <QStandardPaths>
#include <QUrl>
#include <algorithm>

// ===========================================================================
// TraceRequest
// ===========================================================================

QJsonObject TraceRequest::toJson() const {
    QJsonObject obj;
    obj[QStringLiteral("url")]    = url;
    obj[QStringLiteral("method")] = method;

    QJsonObject hdrs;
    for (auto it = headers.cbegin(); it != headers.cend(); ++it)
        hdrs[it.key()] = it.value();
    obj[QStringLiteral("headers")] = hdrs;

    obj[QStringLiteral("body")] = body;
    return obj;
}

TraceRequest TraceRequest::fromJson(const QJsonObject &obj) {
    TraceRequest req;
    req.url    = obj[QStringLiteral("url")].toString();
    req.method = obj[QStringLiteral("method")].toString();

    const QJsonObject hdrs = obj[QStringLiteral("headers")].toObject();
    for (auto it = hdrs.constBegin(); it != hdrs.constEnd(); ++it)
        req.headers[it.key()] = it.value().toString();

    req.body = obj[QStringLiteral("body")].toString();
    return req;
}

// ===========================================================================
// TraceResponse
// ===========================================================================

QJsonObject TraceResponse::toJson() const {
    QJsonObject obj;
    obj[QStringLiteral("statusCode")] = statusCode;

    QJsonObject hdrs;
    for (auto it = headers.cbegin(); it != headers.cend(); ++it)
        hdrs[it.key()] = it.value();
    obj[QStringLiteral("headers")] = hdrs;

    obj[QStringLiteral("body")] = body;
    return obj;
}

TraceResponse TraceResponse::fromJson(const QJsonObject &obj) {
    TraceResponse resp;
    resp.statusCode = obj[QStringLiteral("statusCode")].toInt();

    const QJsonObject hdrs = obj[QStringLiteral("headers")].toObject();
    for (auto it = hdrs.constBegin(); it != hdrs.constEnd(); ++it)
        resp.headers[it.key()] = it.value().toString();

    resp.body = obj[QStringLiteral("body")].toString();
    return resp;
}

// ===========================================================================
// ApiTrace
// ===========================================================================

QJsonObject ApiTrace::toJson() const {
    QJsonObject obj;
    obj[QStringLiteral("timestamp")] = timestamp.toMSecsSinceEpoch();
    obj[QStringLiteral("hostname")]  = hostname;
    obj[QStringLiteral("reportId")]  = reportId;
    obj[QStringLiteral("model")]     = model;
    obj[QStringLiteral("request")]   = request.toJson();
    obj[QStringLiteral("response")]  = response.toJson();
    return obj;
}

ApiTrace ApiTrace::fromJson(const QJsonObject &obj) {
    ApiTrace trace;
    trace.timestamp = QDateTime::fromMSecsSinceEpoch(
        obj[QStringLiteral("timestamp")].toDouble());
    trace.hostname  = obj[QStringLiteral("hostname")].toString();
    trace.reportId  = obj[QStringLiteral("reportId")].toString();
    trace.model     = obj[QStringLiteral("model")].toString();
    trace.request   = TraceRequest::fromJson(
        obj[QStringLiteral("request")].toObject());
    trace.response  = TraceResponse::fromJson(
        obj[QStringLiteral("response")].toObject());
    return trace;
}

// ===========================================================================
// Singleton
// ===========================================================================

ApiTracer& ApiTracer::instance() {
    static ApiTracer tracer;
    return tracer;
}

ApiTracer::ApiTracer(QObject *parent)
    : QObject(parent)
    , m_storageDir(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                   + QStringLiteral("/trace/"))
{
}

// ===========================================================================
// Public setters
// ===========================================================================

void ApiTracer::setTracingEnabled(bool enabled) {
    QMutexLocker locker(&m_mutex);
    m_enabled = enabled;
}

void ApiTracer::setCurrentReportId(const QString &id) {
    QMutexLocker locker(&m_mutex);
    m_currentReportId = id;
}

// ===========================================================================
// Private helpers
// ===========================================================================

void ApiTracer::ensureDir() {
    QDir().mkpath(m_storageDir);
}

QString ApiTracer::extractHostname(const QString &url) {
    const QUrl parsed(url);
    const QString host = parsed.host();
    return host.isEmpty() ? QStringLiteral("unknown") : host;
}

QString ApiTracer::extractModel(const QString &requestBody) {
    if (requestBody.isEmpty())
        return {};

    const QJsonDocument doc = QJsonDocument::fromJson(requestBody.toUtf8());
    if (doc.isNull() || !doc.isObject())
        return {};

    return doc.object()[QStringLiteral("model")].toString();
}

// ===========================================================================
// Trace if enabled
// ===========================================================================

void ApiTracer::traceIfEnabled(const QString &url, const QString &method,
                               const QMap<QString, QString> &requestHeaders,
                               const QString &requestBody,
                               int statusCode,
                               const QMap<QString, QString> &responseHeaders,
                               const QString &responseBody)
{
    QMutexLocker locker(&m_mutex);
    if (!m_enabled)
        return;

    ensureDir();

    const QDateTime now = QDateTime::currentDateTime();
    const QString hostname = extractHostname(url);
    const QString model = extractModel(requestBody);

    // Build trace
    ApiTrace trace;
    trace.timestamp = now;
    trace.hostname  = hostname;
    trace.reportId  = m_currentReportId;
    trace.model     = model;

    trace.request.url     = url;
    trace.request.method  = method;
    trace.request.headers = requestHeaders;
    trace.request.body    = requestBody;

    trace.response.statusCode = statusCode;
    trace.response.headers    = responseHeaders;
    trace.response.body       = responseBody;

    // Build filename: {hostname}_{yyyyMMdd_HHmmss_SSS}_{sequence}.json
    ++m_sequence;
    const QString ts  = now.toString(QStringLiteral("yyyyMMdd_HHmmss_zzz"));
    const QString seq = QStringLiteral("%1").arg(m_sequence, 4, 10, QLatin1Char('0'));
    const QString filename = hostname + QStringLiteral("_") + ts
                           + QStringLiteral("_") + seq + QStringLiteral(".json");

    // Write to file
    const QJsonDocument doc(trace.toJson());
    QFile file(m_storageDir + filename);
    if (file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        file.write(doc.toJson(QJsonDocument::Indented));
        file.close();
    }
}

// ===========================================================================
// Get trace files (sorted by timestamp descending)
// ===========================================================================

QList<TraceFileInfo> ApiTracer::getTraceFiles() {
    QMutexLocker locker(&m_mutex);
    ensureDir();

    QList<TraceFileInfo> result;
    const QDir dir(m_storageDir);
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files);

    for (const QString &fileName : files) {
        QFile file(m_storageDir + fileName);
        if (!file.open(QIODevice::ReadOnly))
            continue;

        const QByteArray data = file.readAll();
        file.close();

        const QJsonDocument doc = QJsonDocument::fromJson(data);
        if (doc.isNull() || !doc.isObject())
            continue;

        const QJsonObject obj = doc.object();

        TraceFileInfo info;
        info.id         = fileName;
        info.filename   = fileName;
        info.hostname   = obj[QStringLiteral("hostname")].toString();
        info.timestamp  = QDateTime::fromMSecsSinceEpoch(
            obj[QStringLiteral("timestamp")].toDouble());
        info.reportId   = obj[QStringLiteral("reportId")].toString();
        info.model      = obj[QStringLiteral("model")].toString();

        // Peek into response for statusCode
        const QJsonObject respObj = obj[QStringLiteral("response")].toObject();
        info.statusCode = respObj[QStringLiteral("statusCode")].toInt();

        result.append(info);
    }

    // Sort by timestamp descending (newest first)
    std::sort(result.begin(), result.end(),
              [](const TraceFileInfo &a, const TraceFileInfo &b) {
                  return a.timestamp > b.timestamp;
              });

    return result;
}

// ===========================================================================
// Read a single trace
// ===========================================================================

std::optional<ApiTrace> ApiTracer::readTrace(const QString &filename) {
    QMutexLocker locker(&m_mutex);

    QFile file(m_storageDir + filename);
    if (!file.exists() || !file.open(QIODevice::ReadOnly))
        return std::nullopt;

    const QByteArray data = file.readAll();
    file.close();

    const QJsonDocument doc = QJsonDocument::fromJson(data);
    if (doc.isNull() || !doc.isObject())
        return std::nullopt;

    return ApiTrace::fromJson(doc.object());
}

// ===========================================================================
// Clear all traces
// ===========================================================================

void ApiTracer::clearTraces() {
    QMutexLocker locker(&m_mutex);

    const QDir dir(m_storageDir);
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files);

    for (const QString &fileName : files) {
        QFile::remove(m_storageDir + fileName);
    }
}

// ===========================================================================
// Count trace files
// ===========================================================================

int ApiTracer::getTraceCount() {
    QMutexLocker locker(&m_mutex);

    const QDir dir(m_storageDir);
    return dir.entryList(
        QStringList() << QStringLiteral("*.json"), QDir::Files).count();
}
