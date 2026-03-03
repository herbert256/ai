#include "AnalysisModels.h"
#include "ApiClient.h"
#include "AnalysisChat.h"
#include "AnalysisRepository.h"
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QRegularExpression>
#include <QDebug>
#include <algorithm>

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

AnalysisModels& AnalysisModels::instance() {
    static AnalysisModels s_instance;
    return s_instance;
}

AnalysisModels::AnalysisModels(QObject *parent)
    : QObject(parent)
{
}

// ---------------------------------------------------------------------------
// fetchModels - dispatch by apiFormat
// ---------------------------------------------------------------------------

void AnalysisModels::fetchModels(const AppService &service, const QString &apiKey,
                                  const QString &customUrl,
                                  std::function<void(QStringList)> callback) {
    // Providers with hardcoded models and no model list API endpoint
    if (service.hardcodedModels.has_value() && !service.modelsPath.has_value()) {
        if (callback)
            callback(service.hardcodedModels.value());
        return;
    }

    switch (service.apiFormat) {
    case ApiFormat::Google:
        fetchGeminiModels(service, apiKey, callback);
        break;
    case ApiFormat::Anthropic:
        fetchClaudeModels(service, apiKey, callback);
        break;
    case ApiFormat::OpenAiCompatible:
        fetchOpenAiCompatibleModels(service, apiKey, customUrl, callback);
        break;
    }
}

// ---------------------------------------------------------------------------
// fetchOpenAiCompatibleModels
// ---------------------------------------------------------------------------

void AnalysisModels::fetchOpenAiCompatibleModels(const AppService &service, const QString &apiKey,
                                                   const QString &customUrl,
                                                   std::function<void(QStringList)> callback) {
    // Return hardcoded models if no models API path is set
    if (!service.modelsPath.has_value()) {
        if (callback)
            callback(service.hardcodedModels.value_or(QStringList()));
        return;
    }

    const QString url = ApiClient::modelsUrl(service, customUrl);
    if (url.isEmpty()) {
        if (callback)
            callback(service.hardcodedModels.value_or(QStringList()));
        return;
    }

    const bool isArrayFormat = (service.modelListFormat == QStringLiteral("array"));
    // Capture service by value for the lambda (id needed for filter)
    const AppService svc = service;

    ApiClient::instance().get(url, service, apiKey,
        [this, callback, isArrayFormat, svc](const QJsonObject &json, int statusCode) {
            Q_UNUSED(statusCode)

            QStringList modelIds;

            if (isArrayFormat) {
                // Some providers return a raw JSON array, which ApiClient wraps in {"data": [...]}
                const QJsonArray arr = json[QStringLiteral("data")].toArray();
                for (const auto &v : arr) {
                    const QString id = v.toObject()[QStringLiteral("id")].toString();
                    if (!id.isEmpty())
                        modelIds.append(id);
                }
            } else {
                // Standard OpenAI format: { "data": [ { "id": "..." }, ... ] }
                const QJsonArray arr = json[QStringLiteral("data")].toArray();
                for (const auto &v : arr) {
                    const QString id = v.toObject()[QStringLiteral("id")].toString();
                    if (!id.isEmpty())
                        modelIds.append(id);
                }
            }

            QStringList filtered = filterModels(modelIds, svc);
            if (callback)
                callback(filtered);
        });
}

// ---------------------------------------------------------------------------
// fetchClaudeModels
// ---------------------------------------------------------------------------

void AnalysisModels::fetchClaudeModels(const AppService &service, const QString &apiKey,
                                         std::function<void(QStringList)> callback) {
    const QString base = service.baseUrl.endsWith(QLatin1Char('/'))
                             ? service.baseUrl
                             : service.baseUrl + QLatin1Char('/');
    const QString url = base + QStringLiteral("v1/models");

    ApiClient::instance().get(url, service, apiKey,
        [callback](const QJsonObject &json, int statusCode) {
            Q_UNUSED(statusCode)

            QStringList modelIds;
            const QJsonArray arr = json[QStringLiteral("data")].toArray();
            for (const auto &v : arr) {
                const QString id = v.toObject()[QStringLiteral("id")].toString();
                if (!id.isEmpty() && id.startsWith(QStringLiteral("claude")))
                    modelIds.append(id);
            }

            modelIds.sort();
            if (callback)
                callback(modelIds);
        });
}

// ---------------------------------------------------------------------------
// fetchGeminiModels
// ---------------------------------------------------------------------------

void AnalysisModels::fetchGeminiModels(const AppService &service, const QString &apiKey,
                                         std::function<void(QStringList)> callback) {
    const QString base = service.baseUrl.endsWith(QLatin1Char('/'))
                             ? service.baseUrl
                             : service.baseUrl + QLatin1Char('/');
    const QString url = QStringLiteral("%1v1beta/models?key=%2").arg(base, apiKey);

    // Google auth uses query param, so create a temp service to avoid adding Bearer header
    AppService tempService;
    tempService.id = QStringLiteral("gemini_temp");
    tempService.apiFormat = ApiFormat::Google;

    ApiClient::instance().get(url, tempService, apiKey,
        [callback](const QJsonObject &json, int statusCode) {
            Q_UNUSED(statusCode)

            QStringList modelIds;
            const QJsonArray modelsArr = json[QStringLiteral("models")].toArray();
            for (const auto &v : modelsArr) {
                const QJsonObject modelObj = v.toObject();

                // Filter to models that support generateContent
                const QJsonArray methods = modelObj[QStringLiteral("supportedGenerationMethods")].toArray();
                bool supportsGenerate = false;
                for (const auto &m : methods) {
                    if (m.toString() == QStringLiteral("generateContent")) {
                        supportsGenerate = true;
                        break;
                    }
                }
                if (!supportsGenerate)
                    continue;

                // Strip "models/" prefix from the name field
                QString name = modelObj[QStringLiteral("name")].toString();
                if (name.startsWith(QStringLiteral("models/")))
                    name = name.mid(7); // len("models/") == 7
                if (!name.isEmpty())
                    modelIds.append(name);
            }

            modelIds.sort();
            if (callback)
                callback(modelIds);
        });
}

// ---------------------------------------------------------------------------
// filterModels - apply provider-specific regex filter and sort
// ---------------------------------------------------------------------------

QStringList AnalysisModels::filterModels(const QStringList &models, const AppService &service) {
    const QRegularExpression *regex = service.modelFilterRegex();

    QStringList result;
    if (regex) {
        for (const auto &model : models) {
            if (regex->match(model).hasMatch())
                result.append(model);
        }
    } else {
        result = models;
    }

    result.sort();
    return result;
}

// ---------------------------------------------------------------------------
// testModel - send test prompt and return error (empty = success)
// ---------------------------------------------------------------------------

void AnalysisModels::testModel(const AppService &service, const QString &apiKey,
                                const QString &model,
                                std::function<void(QString error)> callback) {
    const QString prompt = AnalysisRepository::testPrompt;

    QList<ChatMessage> messages;
    messages.append(ChatMessage(QStringLiteral("user"), prompt));

    ChatParameters params;

    AnalysisChat::instance().sendChatMessage(service, apiKey, model, messages, params, QString(),
        [callback](const QString &response, const QString &error) {
            Q_UNUSED(response)
            if (callback)
                callback(error);
        });
}

// ---------------------------------------------------------------------------
// testModelWithPrompt - send custom prompt and return response + error
// ---------------------------------------------------------------------------

void AnalysisModels::testModelWithPrompt(const AppService &service, const QString &apiKey,
                                           const QString &model, const QString &prompt,
                                           std::function<void(QString response, QString error)> callback) {
    QList<ChatMessage> messages;
    messages.append(ChatMessage(QStringLiteral("user"), prompt));

    ChatParameters params;

    AnalysisChat::instance().sendChatMessage(service, apiKey, model, messages, params, QString(),
        [callback](const QString &response, const QString &error) {
            if (callback)
                callback(response, error);
        });
}
