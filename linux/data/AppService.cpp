#include "AppService.h"

// ---------------------------------------------------------------------------
// ApiFormat conversion
// ---------------------------------------------------------------------------

QString apiFormatToString(ApiFormat f) {
    switch (f) {
    case ApiFormat::OpenAiCompatible: return QStringLiteral("OPENAI_COMPATIBLE");
    case ApiFormat::Anthropic:        return QStringLiteral("ANTHROPIC");
    case ApiFormat::Google:           return QStringLiteral("GOOGLE");
    }
    return QStringLiteral("OPENAI_COMPATIBLE");
}

ApiFormat apiFormatFromString(const QString &s) {
    if (s == QLatin1String("ANTHROPIC")) return ApiFormat::Anthropic;
    if (s == QLatin1String("GOOGLE"))    return ApiFormat::Google;
    return ApiFormat::OpenAiCompatible;
}

// ---------------------------------------------------------------------------
// EndpointRule
// ---------------------------------------------------------------------------

QJsonObject EndpointRule::toJson() const {
    QJsonObject obj;
    obj[QStringLiteral("modelPrefix")]  = modelPrefix;
    obj[QStringLiteral("endpointType")] = endpointType;
    return obj;
}

EndpointRule EndpointRule::fromJson(const QJsonObject &obj) {
    EndpointRule rule;
    rule.modelPrefix  = obj[QStringLiteral("modelPrefix")].toString();
    rule.endpointType = obj[QStringLiteral("endpointType")].toString();
    return rule;
}

// ---------------------------------------------------------------------------
// AppService - static registry
// ---------------------------------------------------------------------------

QList<AppService> AppService::entries;

AppService *AppService::findById(const QString &id) {
    for (auto &s : entries) {
        if (s.id == id) return &s;
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// AppService - modelFilterRegex (lazy cached)
// ---------------------------------------------------------------------------

const QRegularExpression *AppService::modelFilterRegex() const {
    if (!modelFilterRegex_.has_value()) {
        if (modelFilter.has_value() && !modelFilter->isEmpty()) {
            modelFilterRegex_ = QRegularExpression(
                *modelFilter,
                QRegularExpression::CaseInsensitiveOption
            );
        } else {
            // Store an invalid regex to signal "no filter" — checked via has_value + isValid
            modelFilterRegex_ = QRegularExpression();
        }
    }
    const QRegularExpression &re = *modelFilterRegex_;
    if (re.pattern().isEmpty()) return nullptr;
    return &re;
}

// ---------------------------------------------------------------------------
// ProviderDefinition - JSON deserialization
// ---------------------------------------------------------------------------

static std::optional<QString> optString(const QJsonObject &obj, const QString &key) {
    if (!obj.contains(key) || obj[key].isNull()) return std::nullopt;
    return obj[key].toString();
}

static std::optional<double> optDouble(const QJsonObject &obj, const QString &key) {
    if (!obj.contains(key) || obj[key].isNull()) return std::nullopt;
    return obj[key].toDouble();
}

static std::optional<QStringList> optStringList(const QJsonObject &obj, const QString &key) {
    if (!obj.contains(key) || !obj[key].isArray()) return std::nullopt;
    QStringList list;
    const QJsonArray arr = obj[key].toArray();
    for (const auto &v : arr) {
        list.append(v.toString());
    }
    return list;
}

ProviderDefinition ProviderDefinition::fromJson(const QJsonObject &obj) {
    ProviderDefinition pd;
    pd.id              = obj[QStringLiteral("id")].toString();
    pd.displayName     = obj[QStringLiteral("displayName")].toString();
    pd.baseUrl         = obj[QStringLiteral("baseUrl")].toString();
    pd.adminUrl        = obj[QStringLiteral("adminUrl")].toString();
    pd.defaultModel    = obj[QStringLiteral("defaultModel")].toString();

    pd.openRouterName     = optString(obj, QStringLiteral("openRouterName"));
    pd.chatPath           = optString(obj, QStringLiteral("chatPath"));
    pd.modelsPath         = optString(obj, QStringLiteral("modelsPath"));
    pd.prefsKey           = optString(obj, QStringLiteral("prefsKey"));
    pd.seedFieldName      = optString(obj, QStringLiteral("seedFieldName"));
    pd.modelFilter        = optString(obj, QStringLiteral("modelFilter"));
    pd.litellmPrefix      = optString(obj, QStringLiteral("litellmPrefix"));
    pd.defaultModelSource = optString(obj, QStringLiteral("defaultModelSource"));
    pd.modelListFormat    = optString(obj, QStringLiteral("modelListFormat"));

    pd.apiFormat = obj[QStringLiteral("apiFormat")].toString(QStringLiteral("OPENAI_COMPATIBLE"));

    pd.supportsCitations    = obj[QStringLiteral("supportsCitations")].toBool(false);
    pd.supportsSearchRecency = obj[QStringLiteral("supportsSearchRecency")].toBool(false);
    pd.extractApiCost       = obj[QStringLiteral("extractApiCost")].toBool(false);

    pd.costTicksDivisor = optDouble(obj, QStringLiteral("costTicksDivisor"));
    pd.hardcodedModels  = optStringList(obj, QStringLiteral("hardcodedModels"));

    // Endpoint rules
    if (obj.contains(QStringLiteral("endpointRules")) && obj[QStringLiteral("endpointRules")].isArray()) {
        const QJsonArray arr = obj[QStringLiteral("endpointRules")].toArray();
        for (const auto &v : arr) {
            pd.endpointRules.append(EndpointRule::fromJson(v.toObject()));
        }
    }

    return pd;
}

// ---------------------------------------------------------------------------
// ProviderDefinition - JSON serialization
// ---------------------------------------------------------------------------

QJsonObject ProviderDefinition::toJson() const {
    QJsonObject obj;
    obj[QStringLiteral("id")]          = id;
    obj[QStringLiteral("displayName")] = displayName;
    obj[QStringLiteral("baseUrl")]     = baseUrl;
    if (!adminUrl.isEmpty())
        obj[QStringLiteral("adminUrl")] = adminUrl;
    obj[QStringLiteral("defaultModel")] = defaultModel;

    if (openRouterName.has_value())
        obj[QStringLiteral("openRouterName")] = *openRouterName;
    if (apiFormat != QLatin1String("OPENAI_COMPATIBLE"))
        obj[QStringLiteral("apiFormat")] = apiFormat;
    if (chatPath.has_value())
        obj[QStringLiteral("chatPath")] = *chatPath;
    if (modelsPath.has_value())
        obj[QStringLiteral("modelsPath")] = *modelsPath;
    if (prefsKey.has_value())
        obj[QStringLiteral("prefsKey")] = *prefsKey;
    if (seedFieldName.has_value() && *seedFieldName != QLatin1String("seed"))
        obj[QStringLiteral("seedFieldName")] = *seedFieldName;
    if (supportsCitations)
        obj[QStringLiteral("supportsCitations")] = true;
    if (supportsSearchRecency)
        obj[QStringLiteral("supportsSearchRecency")] = true;
    if (extractApiCost)
        obj[QStringLiteral("extractApiCost")] = true;
    if (costTicksDivisor.has_value())
        obj[QStringLiteral("costTicksDivisor")] = *costTicksDivisor;
    if (modelListFormat.has_value() && *modelListFormat != QLatin1String("object"))
        obj[QStringLiteral("modelListFormat")] = *modelListFormat;
    if (modelFilter.has_value())
        obj[QStringLiteral("modelFilter")] = *modelFilter;
    if (litellmPrefix.has_value())
        obj[QStringLiteral("litellmPrefix")] = *litellmPrefix;
    if (defaultModelSource.has_value())
        obj[QStringLiteral("defaultModelSource")] = *defaultModelSource;

    if (hardcodedModels.has_value()) {
        QJsonArray arr;
        for (const auto &m : *hardcodedModels) {
            arr.append(m);
        }
        obj[QStringLiteral("hardcodedModels")] = arr;
    }

    if (!endpointRules.isEmpty()) {
        QJsonArray arr;
        for (const auto &rule : endpointRules) {
            arr.append(rule.toJson());
        }
        obj[QStringLiteral("endpointRules")] = arr;
    }

    return obj;
}

// ---------------------------------------------------------------------------
// ProviderDefinition <-> AppService conversion
// ---------------------------------------------------------------------------

AppService ProviderDefinition::toAppService() const {
    AppService s;
    s.id          = id;
    s.displayName = displayName;
    s.baseUrl     = baseUrl;
    s.adminUrl    = adminUrl;
    s.defaultModel = defaultModel;
    s.openRouterName = openRouterName;
    s.apiFormat   = apiFormatFromString(apiFormat);
    s.chatPath    = chatPath.value_or(QStringLiteral("v1/chat/completions"));
    s.modelsPath  = modelsPath;
    s.prefsKey    = prefsKey.value_or(id.toLower());
    s.seedFieldName = seedFieldName.value_or(QStringLiteral("seed"));
    s.supportsCitations    = supportsCitations;
    s.supportsSearchRecency = supportsSearchRecency;
    s.extractApiCost       = extractApiCost;
    s.costTicksDivisor     = costTicksDivisor;
    s.modelListFormat = modelListFormat.value_or(QStringLiteral("object"));
    s.modelFilter        = modelFilter;
    s.litellmPrefix      = litellmPrefix;
    s.hardcodedModels    = hardcodedModels;
    s.defaultModelSource = defaultModelSource;
    s.endpointRules      = endpointRules;
    return s;
}

ProviderDefinition ProviderDefinition::fromAppService(const AppService &s) {
    ProviderDefinition pd;
    pd.id              = s.id;
    pd.displayName     = s.displayName;
    pd.baseUrl         = s.baseUrl;
    pd.adminUrl        = s.adminUrl;
    pd.defaultModel    = s.defaultModel;
    pd.openRouterName  = s.openRouterName;
    pd.apiFormat       = apiFormatToString(s.apiFormat);
    pd.chatPath        = s.chatPath;
    pd.modelsPath      = s.modelsPath;
    pd.prefsKey        = s.prefsKey;
    pd.seedFieldName   = s.seedFieldName;
    pd.supportsCitations    = s.supportsCitations;
    pd.supportsSearchRecency = s.supportsSearchRecency;
    pd.extractApiCost       = s.extractApiCost;
    pd.costTicksDivisor     = s.costTicksDivisor;
    pd.modelListFormat = s.modelListFormat;
    pd.modelFilter        = s.modelFilter;
    pd.litellmPrefix      = s.litellmPrefix;
    pd.hardcodedModels    = s.hardcodedModels;
    pd.defaultModelSource = s.defaultModelSource;
    pd.endpointRules      = s.endpointRules;
    return pd;
}
