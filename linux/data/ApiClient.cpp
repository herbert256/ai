#include "ApiClient.h"
#include <QNetworkRequest>
#include <QJsonArray>
#include <QUrlQuery>
#include <QUrl>
#include <QDebug>

// -- Singleton --

ApiClient& ApiClient::instance() {
    static ApiClient s_instance;
    return s_instance;
}

ApiClient::ApiClient(QObject *parent)
    : QObject(parent)
    , m_nam(new QNetworkAccessManager(this))
{
}

// -- Auth headers by API format --

void ApiClient::setAuthHeaders(QNetworkRequest &request, const AppService &service, const QString &apiKey) {
    switch (service.apiFormat) {
    case ApiFormat::OpenAiCompatible:
        request.setRawHeader("Authorization", QStringLiteral("Bearer %1").arg(apiKey).toUtf8());
        request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
        break;
    case ApiFormat::Anthropic:
        request.setRawHeader("x-api-key", apiKey.toUtf8());
        request.setRawHeader("anthropic-version", "2023-06-01");
        request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
        break;
    case ApiFormat::Google:
        // Google uses ?key= query parameter - no auth headers needed.
        // Content-Type still required for POST requests.
        request.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
        break;
    }
}

// -- Normalize base URL --

QString ApiClient::normalizeBaseUrl(const QString &url) {
    if (url.endsWith(QLatin1Char('/')))
        return url;
    return url + QLatin1Char('/');
}

// -- POST request --

void ApiClient::request(const QString &url, const QJsonObject &body,
                         const AppService &service, const QString &apiKey,
                         std::function<void(QJsonObject, int statusCode, QString headers)> callback,
                         const QMap<QString, QString> &extraHeaders) {
    QNetworkRequest req{QUrl(url)};
    setAuthHeaders(req, service, apiKey);

    // Extra headers
    for (auto it = extraHeaders.cbegin(); it != extraHeaders.cend(); ++it) {
        req.setRawHeader(it.key().toUtf8(), it.value().toUtf8());
    }

    // Timeouts: 30s connect, 600s transfer
    req.setTransferTimeout(600 * 1000);

    QByteArray payload = QJsonDocument(body).toJson(QJsonDocument::Compact);
    QNetworkReply *reply = m_nam->post(req, payload);

    connect(reply, &QNetworkReply::finished, this, [reply, callback]() {
        reply->deleteLater();

        int statusCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();

        // Format response headers
        QString headersStr;
        const auto rawHeaders = reply->rawHeaderPairs();
        for (const auto &pair : rawHeaders) {
            if (!headersStr.isEmpty())
                headersStr += QLatin1Char('\n');
            headersStr += QString::fromUtf8(pair.first) + QStringLiteral(": ") + QString::fromUtf8(pair.second);
        }

        QByteArray data = reply->readAll();
        QJsonDocument doc = QJsonDocument::fromJson(data);
        QJsonObject jsonObj;

        if (doc.isObject()) {
            jsonObj = doc.object();
        } else if (doc.isArray()) {
            // Wrap arrays in an object for consistent handling
            jsonObj.insert(QStringLiteral("data"), doc.array());
        } else if (reply->error() != QNetworkReply::NoError) {
            jsonObj.insert(QStringLiteral("error"),
                           QJsonObject{{QStringLiteral("message"), reply->errorString()},
                                       {QStringLiteral("body"), QString::fromUtf8(data).left(500)}});
        }

        if (callback)
            callback(jsonObj, statusCode, headersStr);
    });
}

// -- GET request --

void ApiClient::get(const QString &url, const AppService &service, const QString &apiKey,
                     std::function<void(QJsonObject, int statusCode)> callback) {
    QUrl requestUrl(url);

    // Google: append ?key= query parameter
    if (service.apiFormat == ApiFormat::Google) {
        QUrlQuery query(requestUrl);
        query.addQueryItem(QStringLiteral("key"), apiKey);
        requestUrl.setQuery(query);
    }

    QNetworkRequest req(requestUrl);

    // Set auth headers for non-Google formats
    if (service.apiFormat == ApiFormat::OpenAiCompatible) {
        req.setRawHeader("Authorization", QStringLiteral("Bearer %1").arg(apiKey).toUtf8());
    } else if (service.apiFormat == ApiFormat::Anthropic) {
        req.setRawHeader("x-api-key", apiKey.toUtf8());
        req.setRawHeader("anthropic-version", "2023-06-01");
    }

    req.setTransferTimeout(600 * 1000);

    QNetworkReply *reply = m_nam->get(req);

    connect(reply, &QNetworkReply::finished, this, [reply, callback]() {
        reply->deleteLater();

        int statusCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();

        QByteArray data = reply->readAll();
        QJsonDocument doc = QJsonDocument::fromJson(data);
        QJsonObject jsonObj;

        if (doc.isObject()) {
            jsonObj = doc.object();
        } else if (doc.isArray()) {
            jsonObj.insert(QStringLiteral("data"), doc.array());
        } else if (reply->error() != QNetworkReply::NoError) {
            jsonObj.insert(QStringLiteral("error"),
                           QJsonObject{{QStringLiteral("message"), reply->errorString()},
                                       {QStringLiteral("body"), QString::fromUtf8(data).left(500)}});
        }

        if (callback)
            callback(jsonObj, statusCode);
    });
}

// -- Streaming POST request --

QNetworkReply* ApiClient::streamRequest(const QString &url, const QJsonObject &body,
                                         const AppService &service, const QString &apiKey) {
    QNetworkRequest req{QUrl(url)};
    setAuthHeaders(req, service, apiKey);

    // Timeouts: 30s connect, 600s transfer
    req.setTransferTimeout(600 * 1000);

    QByteArray payload = QJsonDocument(body).toJson(QJsonDocument::Compact);
    return m_nam->post(req, payload);
}

// -- URL building helpers --

QString ApiClient::chatUrl(const AppService &service, const QString &customBaseUrl) {
    const QString &base = customBaseUrl.isEmpty() ? service.baseUrl : customBaseUrl;
    return normalizeBaseUrl(base) + service.chatPath;
}

QString ApiClient::geminiGenerateUrl(const QString &baseUrl, const QString &model,
                                      const QString &apiKey, bool stream) {
    QString base = normalizeBaseUrl(baseUrl);
    QString action = stream ? QStringLiteral("streamGenerateContent") : QStringLiteral("generateContent");
    QString url = QStringLiteral("%1v1beta/models/%2:%3?key=%4").arg(base, model, action, apiKey);
    if (stream)
        url += QStringLiteral("&alt=sse");
    return url;
}

QString ApiClient::modelsUrl(const AppService &service, const QString &customUrl) {
    if (!customUrl.isEmpty())
        return customUrl;
    if (!service.modelsPath.has_value())
        return QString();
    return normalizeBaseUrl(service.baseUrl) + service.modelsPath.value();
}
