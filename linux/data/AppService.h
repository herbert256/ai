#pragma once
#include <QString>
#include <QStringList>
#include <QList>
#include <QJsonObject>
#include <QJsonArray>
#include <QRegularExpression>
#include <optional>

// API format used by a provider.
enum class ApiFormat {
    OpenAiCompatible,  // 28 providers using OpenAI-compatible chat/completions
    Anthropic,         // Anthropic Messages API
    Google             // Google Gemini GenerativeAI API
};

QString apiFormatToString(ApiFormat f);    // "OPENAI_COMPATIBLE", "ANTHROPIC", "GOOGLE"
ApiFormat apiFormatFromString(const QString &s);

// Rule mapping model prefixes to endpoint types.
// Used to route models to different API endpoints (e.g., OpenAI Responses API for gpt-5.x).
struct EndpointRule {
    QString modelPrefix;
    QString endpointType;  // "responses" or "chat" (default)

    QJsonObject toJson() const;
    static EndpointRule fromJson(const QJsonObject &obj);
    bool operator==(const EndpointRule &) const = default;
};

// Data class representing a supported AI service provider.
// Identity is based on `id` only.
class AppService {
public:
    QString id;
    QString displayName;
    QString baseUrl;
    QString adminUrl;
    QString defaultModel;
    std::optional<QString> openRouterName;
    ApiFormat apiFormat = ApiFormat::OpenAiCompatible;
    QString chatPath = QStringLiteral("v1/chat/completions");
    std::optional<QString> modelsPath = QStringLiteral("v1/models");
    QString prefsKey;
    // Provider-specific quirks
    QString seedFieldName = QStringLiteral("seed");
    bool supportsCitations = false;
    bool supportsSearchRecency = false;
    bool extractApiCost = false;
    std::optional<double> costTicksDivisor;
    QString modelListFormat = QStringLiteral("object");
    std::optional<QString> modelFilter;
    std::optional<QString> litellmPrefix;
    std::optional<QStringList> hardcodedModels;
    std::optional<QString> defaultModelSource;
    QList<EndpointRule> endpointRules;

    // Cached compiled regex (mutable for lazy init in const context)
    mutable std::optional<QRegularExpression> modelFilterRegex_;
    const QRegularExpression *modelFilterRegex() const;

    bool operator==(const AppService &other) const { return id == other.id; }

    // Static registry
    static QList<AppService> entries;
    static AppService *findById(const QString &id);
};

// JSON-serializable representation of an AppService provider definition.
// Used for setup.json and settings storage.
struct ProviderDefinition {
    // All fields for flexible deserialization
    QString id, displayName, baseUrl, adminUrl, defaultModel;
    std::optional<QString> openRouterName, chatPath, modelsPath, prefsKey;
    std::optional<QString> seedFieldName, modelFilter, litellmPrefix, defaultModelSource;
    std::optional<QString> modelListFormat;
    QString apiFormat = QStringLiteral("OPENAI_COMPATIBLE");
    bool supportsCitations = false, supportsSearchRecency = false, extractApiCost = false;
    std::optional<double> costTicksDivisor;
    std::optional<QStringList> hardcodedModels;
    QList<EndpointRule> endpointRules;

    AppService toAppService() const;
    static ProviderDefinition fromAppService(const AppService &s);

    QJsonObject toJson() const;
    static ProviderDefinition fromJson(const QJsonObject &obj);
};
