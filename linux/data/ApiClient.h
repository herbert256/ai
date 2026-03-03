#pragma once
#include <QObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QJsonObject>
#include <QJsonDocument>
#include <functional>
#include "AppService.h"

class ApiClient : public QObject {
    Q_OBJECT
public:
    static ApiClient& instance();

    // POST request with JSON body, returns parsed JSON response
    void request(const QString &url, const QJsonObject &body,
                 const AppService &service, const QString &apiKey,
                 std::function<void(QJsonObject, int statusCode, QString headers)> callback,
                 const QMap<QString, QString> &extraHeaders = {});

    // GET request, returns parsed JSON
    void get(const QString &url, const AppService &service, const QString &apiKey,
             std::function<void(QJsonObject, int statusCode)> callback);

    // Streaming POST request - returns QNetworkReply* for readyRead connection
    QNetworkReply* streamRequest(const QString &url, const QJsonObject &body,
                                  const AppService &service, const QString &apiKey);

    // URL building helpers
    static QString chatUrl(const AppService &service, const QString &customBaseUrl = "");
    static QString geminiGenerateUrl(const QString &baseUrl, const QString &model,
                                      const QString &apiKey, bool stream = false);
    static QString modelsUrl(const AppService &service, const QString &customUrl = "");

private:
    explicit ApiClient(QObject *parent = nullptr);
    QNetworkAccessManager *m_nam;

    // Set auth headers based on API format
    void setAuthHeaders(QNetworkRequest &request, const AppService &service, const QString &apiKey);

    // Normalize base URL (ensure trailing slash)
    static QString normalizeBaseUrl(const QString &url);
};
