// SettingsModels.cpp - Settings configuration classes implementation (Linux/Qt6 port)

#include "SettingsModels.h"

#include <QUuid>
#include <QDateTime>
#include <QJsonDocument>

// ===========================================================================
// ModelSource
// ===========================================================================

QString modelSourceToString(ModelSource s)
{
    switch (s) {
    case ModelSource::Api:    return QStringLiteral("API");
    case ModelSource::Manual: return QStringLiteral("MANUAL");
    }
    return QStringLiteral("API");
}

ModelSource modelSourceFromString(const QString &s)
{
    if (s.compare(QStringLiteral("MANUAL"), Qt::CaseInsensitive) == 0)
        return ModelSource::Manual;
    return ModelSource::Api;
}

// ===========================================================================
// ProviderConfig
// ===========================================================================

QJsonObject ProviderConfig::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("apiKey")] = apiKey;
    obj[QStringLiteral("model")] = model;
    obj[QStringLiteral("modelSource")] = modelSourceToString(modelSource);
    QJsonArray arr;
    for (const auto &m : models)
        arr.append(m);
    obj[QStringLiteral("models")] = arr;
    obj[QStringLiteral("adminUrl")] = adminUrl;
    obj[QStringLiteral("modelListUrl")] = modelListUrl;
    QJsonArray pArr;
    for (const auto &pid : parametersIds)
        pArr.append(pid);
    obj[QStringLiteral("parametersIds")] = pArr;
    return obj;
}

ProviderConfig ProviderConfig::fromJson(const QJsonObject &obj)
{
    ProviderConfig c;
    c.apiKey = obj[QStringLiteral("apiKey")].toString();
    c.model = obj[QStringLiteral("model")].toString();
    c.modelSource = modelSourceFromString(obj[QStringLiteral("modelSource")].toString());
    for (const auto &v : obj[QStringLiteral("models")].toArray())
        c.models.append(v.toString());
    c.adminUrl = obj[QStringLiteral("adminUrl")].toString();
    c.modelListUrl = obj[QStringLiteral("modelListUrl")].toString();
    for (const auto &v : obj[QStringLiteral("parametersIds")].toArray())
        c.parametersIds.append(v.toString());
    return c;
}

ProviderConfig defaultProviderConfig(const AppService &service)
{
    ProviderConfig config;
    config.model = service.defaultModel;
    config.adminUrl = service.adminUrl;

    QStringList defaultModels;
    if (service.hardcodedModels.has_value())
        defaultModels = *service.hardcodedModels;

    if (service.defaultModelSource.has_value()) {
        config.modelSource = modelSourceFromString(*service.defaultModelSource);
    } else {
        config.modelSource = defaultModels.isEmpty() ? ModelSource::Api : ModelSource::Manual;
    }
    config.models = defaultModels;
    return config;
}

QMap<QString, ProviderConfig> defaultProvidersMap()
{
    QMap<QString, ProviderConfig> map;
    for (const auto &service : AppService::entries)
        map[service.id] = defaultProviderConfig(service);
    return map;
}

// ===========================================================================
// Endpoint
// ===========================================================================

QJsonObject Endpoint::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("name")] = name;
    obj[QStringLiteral("url")] = url;
    obj[QStringLiteral("isDefault")] = isDefault;
    return obj;
}

Endpoint Endpoint::fromJson(const QJsonObject &obj)
{
    Endpoint e;
    e.id = obj[QStringLiteral("id")].toString();
    e.name = obj[QStringLiteral("name")].toString();
    e.url = obj[QStringLiteral("url")].toString();
    e.isDefault = obj[QStringLiteral("isDefault")].toBool(false);
    return e;
}

QMap<QString, QList<Endpoint>> builtInEndpoints()
{
    static const QMap<QString, QList<Endpoint>> map = {
        {QStringLiteral("OPENAI"), {
            {QStringLiteral("openai-chat"), QStringLiteral("Chat Completions"),
             QStringLiteral("https://api.openai.com/v1/chat/completions"), true},
            {QStringLiteral("openai-responses"), QStringLiteral("Responses API"),
             QStringLiteral("https://api.openai.com/v1/responses"), false}
        }},
        {QStringLiteral("MISTRAL"), {
            {QStringLiteral("mistral-chat"), QStringLiteral("Chat Completions"),
             QStringLiteral("https://api.mistral.ai/v1/chat/completions"), true},
            {QStringLiteral("mistral-codestral"), QStringLiteral("Codestral"),
             QStringLiteral("https://codestral.mistral.ai/v1/chat/completions"), false}
        }},
        {QStringLiteral("DEEPSEEK"), {
            {QStringLiteral("deepseek-chat"), QStringLiteral("Chat Completions"),
             QStringLiteral("https://api.deepseek.com/chat/completions"), true},
            {QStringLiteral("deepseek-beta"), QStringLiteral("Beta (FIM)"),
             QStringLiteral("https://api.deepseek.com/beta/completions"), false}
        }},
        {QStringLiteral("ZAI"), {
            {QStringLiteral("zai-chat"), QStringLiteral("Chat Completions"),
             QStringLiteral("https://api.z.ai/api/paas/v4/chat/completions"), true},
            {QStringLiteral("zai-coding"), QStringLiteral("Coding"),
             QStringLiteral("https://api.z.ai/api/coding/paas/v4/chat/completions"), false}
        }}
    };
    return map;
}

// ===========================================================================
// Agent
// ===========================================================================

QJsonObject Agent::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("name")] = name;
    obj[QStringLiteral("providerId")] = providerId;
    obj[QStringLiteral("model")] = model;
    obj[QStringLiteral("apiKey")] = apiKey;
    if (endpointId.has_value())
        obj[QStringLiteral("endpointId")] = *endpointId;
    QJsonArray arr;
    for (const auto &pid : paramsIds)
        arr.append(pid);
    obj[QStringLiteral("paramsIds")] = arr;
    if (systemPromptId.has_value())
        obj[QStringLiteral("systemPromptId")] = *systemPromptId;
    return obj;
}

Agent Agent::fromJson(const QJsonObject &obj)
{
    Agent a;
    a.id = obj[QStringLiteral("id")].toString();
    if (a.id.isEmpty())
        a.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    a.name = obj[QStringLiteral("name")].toString();
    a.providerId = obj[QStringLiteral("providerId")].toString();
    a.model = obj[QStringLiteral("model")].toString();
    a.apiKey = obj[QStringLiteral("apiKey")].toString();
    if (obj.contains(QStringLiteral("endpointId")))
        a.endpointId = obj[QStringLiteral("endpointId")].toString();
    for (const auto &v : obj[QStringLiteral("paramsIds")].toArray())
        a.paramsIds.append(v.toString());
    if (obj.contains(QStringLiteral("systemPromptId")))
        a.systemPromptId = obj[QStringLiteral("systemPromptId")].toString();
    return a;
}

// ===========================================================================
// Flock
// ===========================================================================

QJsonObject Flock::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("name")] = name;
    QJsonArray aArr;
    for (const auto &aid : agentIds)
        aArr.append(aid);
    obj[QStringLiteral("agentIds")] = aArr;
    QJsonArray pArr;
    for (const auto &pid : paramsIds)
        pArr.append(pid);
    obj[QStringLiteral("paramsIds")] = pArr;
    if (systemPromptId.has_value())
        obj[QStringLiteral("systemPromptId")] = *systemPromptId;
    return obj;
}

Flock Flock::fromJson(const QJsonObject &obj)
{
    Flock f;
    f.id = obj[QStringLiteral("id")].toString();
    if (f.id.isEmpty())
        f.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    f.name = obj[QStringLiteral("name")].toString();
    for (const auto &v : obj[QStringLiteral("agentIds")].toArray())
        f.agentIds.append(v.toString());
    for (const auto &v : obj[QStringLiteral("paramsIds")].toArray())
        f.paramsIds.append(v.toString());
    if (obj.contains(QStringLiteral("systemPromptId")))
        f.systemPromptId = obj[QStringLiteral("systemPromptId")].toString();
    return f;
}

// ===========================================================================
// SwarmMember
// ===========================================================================

QJsonObject SwarmMember::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("providerId")] = providerId;
    obj[QStringLiteral("model")] = model;
    return obj;
}

SwarmMember SwarmMember::fromJson(const QJsonObject &obj)
{
    SwarmMember m;
    m.providerId = obj[QStringLiteral("providerId")].toString();
    m.model = obj[QStringLiteral("model")].toString();
    return m;
}

// ===========================================================================
// Swarm
// ===========================================================================

QJsonObject Swarm::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("name")] = name;
    QJsonArray mArr;
    for (const auto &m : members)
        mArr.append(m.toJson());
    obj[QStringLiteral("members")] = mArr;
    QJsonArray pArr;
    for (const auto &pid : paramsIds)
        pArr.append(pid);
    obj[QStringLiteral("paramsIds")] = pArr;
    if (systemPromptId.has_value())
        obj[QStringLiteral("systemPromptId")] = *systemPromptId;
    return obj;
}

Swarm Swarm::fromJson(const QJsonObject &obj)
{
    Swarm s;
    s.id = obj[QStringLiteral("id")].toString();
    if (s.id.isEmpty())
        s.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    s.name = obj[QStringLiteral("name")].toString();
    for (const auto &v : obj[QStringLiteral("members")].toArray())
        s.members.append(SwarmMember::fromJson(v.toObject()));
    for (const auto &v : obj[QStringLiteral("paramsIds")].toArray())
        s.paramsIds.append(v.toString());
    if (obj.contains(QStringLiteral("systemPromptId")))
        s.systemPromptId = obj[QStringLiteral("systemPromptId")].toString();
    return s;
}

// ===========================================================================
// Parameters
// ===========================================================================

AgentParameters Parameters::toAgentParameters() const
{
    AgentParameters p;
    p.temperature = temperature;
    p.maxTokens = maxTokens;
    p.topP = topP;
    p.topK = topK;
    p.frequencyPenalty = frequencyPenalty;
    p.presencePenalty = presencePenalty;
    p.systemPrompt = systemPrompt;
    p.stopSequences = stopSequences;
    p.seed = seed;
    p.responseFormatJson = responseFormatJson;
    p.searchEnabled = searchEnabled;
    p.returnCitations = returnCitations;
    p.searchRecency = searchRecency;
    return p;
}

QJsonObject Parameters::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("name")] = name;
    if (temperature.has_value())
        obj[QStringLiteral("temperature")] = static_cast<double>(*temperature);
    if (maxTokens.has_value())
        obj[QStringLiteral("maxTokens")] = *maxTokens;
    if (topP.has_value())
        obj[QStringLiteral("topP")] = static_cast<double>(*topP);
    if (topK.has_value())
        obj[QStringLiteral("topK")] = *topK;
    if (frequencyPenalty.has_value())
        obj[QStringLiteral("frequencyPenalty")] = static_cast<double>(*frequencyPenalty);
    if (presencePenalty.has_value())
        obj[QStringLiteral("presencePenalty")] = static_cast<double>(*presencePenalty);
    if (systemPrompt.has_value())
        obj[QStringLiteral("systemPrompt")] = *systemPrompt;
    if (stopSequences.has_value()) {
        QJsonArray arr;
        for (const auto &s : *stopSequences)
            arr.append(s);
        obj[QStringLiteral("stopSequences")] = arr;
    }
    if (seed.has_value())
        obj[QStringLiteral("seed")] = *seed;
    obj[QStringLiteral("responseFormatJson")] = responseFormatJson;
    obj[QStringLiteral("searchEnabled")] = searchEnabled;
    obj[QStringLiteral("returnCitations")] = returnCitations;
    if (searchRecency.has_value())
        obj[QStringLiteral("searchRecency")] = *searchRecency;
    return obj;
}

Parameters Parameters::fromJson(const QJsonObject &obj)
{
    Parameters p;
    p.id = obj[QStringLiteral("id")].toString();
    if (p.id.isEmpty())
        p.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    p.name = obj[QStringLiteral("name")].toString();
    if (obj.contains(QStringLiteral("temperature")))
        p.temperature = static_cast<float>(obj[QStringLiteral("temperature")].toDouble());
    if (obj.contains(QStringLiteral("maxTokens")))
        p.maxTokens = obj[QStringLiteral("maxTokens")].toInt();
    if (obj.contains(QStringLiteral("topP")))
        p.topP = static_cast<float>(obj[QStringLiteral("topP")].toDouble());
    if (obj.contains(QStringLiteral("topK")))
        p.topK = obj[QStringLiteral("topK")].toInt();
    if (obj.contains(QStringLiteral("frequencyPenalty")))
        p.frequencyPenalty = static_cast<float>(obj[QStringLiteral("frequencyPenalty")].toDouble());
    if (obj.contains(QStringLiteral("presencePenalty")))
        p.presencePenalty = static_cast<float>(obj[QStringLiteral("presencePenalty")].toDouble());
    if (obj.contains(QStringLiteral("systemPrompt")))
        p.systemPrompt = obj[QStringLiteral("systemPrompt")].toString();
    if (obj.contains(QStringLiteral("stopSequences"))) {
        QStringList list;
        for (const auto &v : obj[QStringLiteral("stopSequences")].toArray())
            list.append(v.toString());
        p.stopSequences = list;
    }
    if (obj.contains(QStringLiteral("seed")))
        p.seed = obj[QStringLiteral("seed")].toInt();
    p.responseFormatJson = obj[QStringLiteral("responseFormatJson")].toBool(false);
    p.searchEnabled = obj[QStringLiteral("searchEnabled")].toBool(false);
    p.returnCitations = obj[QStringLiteral("returnCitations")].toBool(true);
    if (obj.contains(QStringLiteral("searchRecency")))
        p.searchRecency = obj[QStringLiteral("searchRecency")].toString();
    return p;
}

// ===========================================================================
// SystemPrompt
// ===========================================================================

QJsonObject SystemPrompt::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("name")] = name;
    obj[QStringLiteral("prompt")] = prompt;
    return obj;
}

SystemPrompt SystemPrompt::fromJson(const QJsonObject &obj)
{
    SystemPrompt sp;
    sp.id = obj[QStringLiteral("id")].toString();
    if (sp.id.isEmpty())
        sp.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    sp.name = obj[QStringLiteral("name")].toString();
    sp.prompt = obj[QStringLiteral("prompt")].toString();
    return sp;
}

// ===========================================================================
// Prompt
// ===========================================================================

QString Prompt::resolvePrompt(const QString &model, const QString &provider,
                              const QString &agent, const QString &swarm) const
{
    QString resolved = promptText;
    if (!model.isEmpty())
        resolved.replace(QStringLiteral("@MODEL@"), model);
    if (!provider.isEmpty())
        resolved.replace(QStringLiteral("@PROVIDER@"), provider);
    if (!agent.isEmpty())
        resolved.replace(QStringLiteral("@AGENT@"), agent);
    if (!swarm.isEmpty())
        resolved.replace(QStringLiteral("@SWARM@"), swarm);
    resolved.replace(QStringLiteral("@NOW@"),
                     QDateTime::currentDateTime().toString(QStringLiteral("yyyy-MM-dd HH:mm")));
    return resolved;
}

QJsonObject Prompt::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("name")] = name;
    obj[QStringLiteral("agentId")] = agentId;
    obj[QStringLiteral("promptText")] = promptText;
    return obj;
}

Prompt Prompt::fromJson(const QJsonObject &obj)
{
    Prompt p;
    p.id = obj[QStringLiteral("id")].toString();
    if (p.id.isEmpty())
        p.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    p.name = obj[QStringLiteral("name")].toString();
    p.agentId = obj[QStringLiteral("agentId")].toString();
    p.promptText = obj[QStringLiteral("promptText")].toString();
    return p;
}

// ===========================================================================
// UsageStats
// ===========================================================================

QJsonObject UsageStats::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("providerId")] = providerId;
    obj[QStringLiteral("model")] = model;
    obj[QStringLiteral("callCount")] = callCount;
    obj[QStringLiteral("inputTokens")] = static_cast<double>(inputTokens);
    obj[QStringLiteral("outputTokens")] = static_cast<double>(outputTokens);
    return obj;
}

UsageStats UsageStats::fromJson(const QJsonObject &obj)
{
    UsageStats s;
    s.providerId = obj[QStringLiteral("providerId")].toString();
    s.model = obj[QStringLiteral("model")].toString();
    s.callCount = obj[QStringLiteral("callCount")].toInt(0);
    s.inputTokens = static_cast<qint64>(obj[QStringLiteral("inputTokens")].toDouble(0));
    s.outputTokens = static_cast<qint64>(obj[QStringLiteral("outputTokens")].toDouble(0));
    return s;
}

// ===========================================================================
// PromptHistoryEntry
// ===========================================================================

QJsonObject PromptHistoryEntry::toJson() const
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = id;
    obj[QStringLiteral("timestamp")] = static_cast<double>(timestamp.toMSecsSinceEpoch());
    obj[QStringLiteral("title")] = title;
    obj[QStringLiteral("prompt")] = prompt;
    return obj;
}

PromptHistoryEntry PromptHistoryEntry::fromJson(const QJsonObject &obj)
{
    PromptHistoryEntry e;
    e.id = obj[QStringLiteral("id")].toString();
    if (e.id.isEmpty())
        e.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    e.timestamp = QDateTime::fromMSecsSinceEpoch(
        static_cast<qint64>(obj[QStringLiteral("timestamp")].toDouble()));
    e.title = obj[QStringLiteral("title")].toString();
    e.prompt = obj[QStringLiteral("prompt")].toString();
    return e;
}

// ===========================================================================
// Settings - Provider access
// ===========================================================================

QString Settings::getProviderState(const AppService &service) const
{
    auto it = providerStates.constFind(service.id);
    if (it != providerStates.constEnd() && *it == QStringLiteral("inactive"))
        return QStringLiteral("inactive");
    if (getApiKey(service).isEmpty())
        return QStringLiteral("not-used");
    if (it != providerStates.constEnd())
        return *it;
    return QStringLiteral("ok");
}

bool Settings::isProviderActive(const AppService &service) const
{
    return getProviderState(service) == QStringLiteral("ok");
}

QList<AppService> Settings::getActiveServices() const
{
    QList<AppService> result;
    for (const auto &service : AppService::entries) {
        if (isProviderActive(service))
            result.append(service);
    }
    return result;
}

ProviderConfig Settings::getProvider(const AppService &service) const
{
    auto it = providers.constFind(service.id);
    if (it != providers.constEnd())
        return *it;
    return defaultProviderConfig(service);
}

void Settings::setProvider(const AppService &service, const ProviderConfig &config)
{
    providers[service.id] = config;
}

QString Settings::getApiKey(const AppService &service) const
{
    return getProvider(service).apiKey;
}

void Settings::setApiKey(const AppService &service, const QString &key)
{
    auto config = getProvider(service);
    config.apiKey = key;
    setProvider(service, config);
}

QString Settings::getModel(const AppService &service) const
{
    return getProvider(service).model;
}

void Settings::setModel(const AppService &service, const QString &model)
{
    auto config = getProvider(service);
    config.model = model;
    setProvider(service, config);
}

QStringList Settings::getModels(const AppService &service) const
{
    return getProvider(service).models;
}

void Settings::setModels(const AppService &service, const QStringList &models)
{
    auto config = getProvider(service);
    config.models = models;
    setProvider(service, config);
}

// ===========================================================================
// Settings - Entity lookup
// ===========================================================================

Agent* Settings::getAgentById(const QString &id)
{
    for (auto &a : agents) {
        if (a.id == id) return &a;
    }
    return nullptr;
}

const Agent* Settings::getAgentById(const QString &id) const
{
    for (const auto &a : agents) {
        if (a.id == id) return &a;
    }
    return nullptr;
}

Flock* Settings::getFlockById(const QString &id)
{
    for (auto &f : flocks) {
        if (f.id == id) return &f;
    }
    return nullptr;
}

const Flock* Settings::getFlockById(const QString &id) const
{
    for (const auto &f : flocks) {
        if (f.id == id) return &f;
    }
    return nullptr;
}

Swarm* Settings::getSwarmById(const QString &id)
{
    for (auto &s : swarms) {
        if (s.id == id) return &s;
    }
    return nullptr;
}

const Swarm* Settings::getSwarmById(const QString &id) const
{
    for (const auto &s : swarms) {
        if (s.id == id) return &s;
    }
    return nullptr;
}

SystemPrompt* Settings::getSystemPromptById(const QString &id)
{
    for (auto &sp : systemPrompts) {
        if (sp.id == id) return &sp;
    }
    return nullptr;
}

const SystemPrompt* Settings::getSystemPromptById(const QString &id) const
{
    for (const auto &sp : systemPrompts) {
        if (sp.id == id) return &sp;
    }
    return nullptr;
}

Parameters* Settings::getParametersById(const QString &id)
{
    for (auto &p : parameters) {
        if (p.id == id) return &p;
    }
    return nullptr;
}

const Parameters* Settings::getParametersById(const QString &id) const
{
    for (const auto &p : parameters) {
        if (p.id == id) return &p;
    }
    return nullptr;
}

Prompt* Settings::getPromptById(const QString &id)
{
    for (auto &p : prompts) {
        if (p.id == id) return &p;
    }
    return nullptr;
}

const Prompt* Settings::getPromptById(const QString &id) const
{
    for (const auto &p : prompts) {
        if (p.id == id) return &p;
    }
    return nullptr;
}

// ===========================================================================
// Settings - Agent resolution
// ===========================================================================

AgentParameters Settings::resolveAgentParameters(const Agent &agent) const
{
    auto merged = mergeParameters(agent.paramsIds);
    return merged.value_or(AgentParameters{});
}

QString Settings::getEffectiveApiKeyForAgent(const Agent &agent) const
{
    if (!agent.apiKey.isEmpty())
        return agent.apiKey;
    AppService *provider = AppService::findById(agent.providerId);
    if (provider)
        return getApiKey(*provider);
    return QString();
}

QString Settings::getEffectiveModelForAgent(const Agent &agent) const
{
    if (!agent.model.isEmpty())
        return agent.model;
    AppService *provider = AppService::findById(agent.providerId);
    if (provider)
        return getModel(*provider);
    return QString();
}

QList<Agent> Settings::getAgentsForFlock(const Flock &flock) const
{
    QList<Agent> result;
    for (const auto &agentId : flock.agentIds) {
        const Agent *agent = getAgentById(agentId);
        if (agent)
            result.append(*agent);
    }
    return result;
}

// ===========================================================================
// Settings - Endpoint resolution
// ===========================================================================

QList<Endpoint> Settings::getEndpointsForProvider(const AppService &provider) const
{
    auto it = endpoints.constFind(provider.id);
    if (it != endpoints.constEnd() && !it->isEmpty())
        return *it;

    // Fall back to built-in endpoints
    auto builtIn = builtInEndpoints();
    auto bit = builtIn.constFind(provider.id);
    if (bit != builtIn.constEnd())
        return *bit;

    // Generate a single default endpoint from baseUrl + chatPath
    QString base = provider.baseUrl;
    if (!base.endsWith(QLatin1Char('/')))
        base.append(QLatin1Char('/'));
    QString url = base + provider.chatPath;
    QString idPrefix = provider.id.toLower();
    return {{idPrefix + QStringLiteral("-chat"), QStringLiteral("Chat Completions"), url, true}};
}

Endpoint* Settings::getDefaultEndpoint(const AppService &provider)
{
    auto it = endpoints.find(provider.id);
    if (it == endpoints.end() || it->isEmpty())
        return nullptr;
    for (auto &ep : *it) {
        if (ep.isDefault)
            return &ep;
    }
    return it->isEmpty() ? nullptr : &it->first();
}

const Endpoint* Settings::getDefaultEndpoint(const AppService &provider) const
{
    auto it = endpoints.constFind(provider.id);
    if (it == endpoints.constEnd() || it->isEmpty())
        return nullptr;
    for (const auto &ep : *it) {
        if (ep.isDefault)
            return &ep;
    }
    return it->isEmpty() ? nullptr : &it->first();
}

QString Settings::getEffectiveEndpointUrl(const AppService &provider) const
{
    auto eps = getEndpointsForProvider(provider);
    for (const auto &ep : eps) {
        if (ep.isDefault)
            return ep.url;
    }
    if (!eps.isEmpty())
        return eps.first().url;
    return provider.baseUrl;
}

QString Settings::getEffectiveEndpointUrlForAgent(const Agent &agent) const
{
    AppService *provider = AppService::findById(agent.providerId);
    if (!provider)
        return QString();

    // If agent has a specific endpoint, use it
    if (agent.endpointId.has_value()) {
        auto eps = getEndpointsForProvider(*provider);
        for (const auto &ep : eps) {
            if (ep.id == *agent.endpointId)
                return ep.url;
        }
    }
    // Otherwise use provider's effective endpoint
    return getEffectiveEndpointUrl(*provider);
}

// ===========================================================================
// Settings - Parameter merging
// ===========================================================================

std::optional<AgentParameters> Settings::mergeParameters(const QStringList &ids) const
{
    if (ids.isEmpty())
        return std::nullopt;

    // Collect valid parameter presets
    QList<AgentParameters> presets;
    for (const auto &id : ids) {
        const Parameters *p = getParametersById(id);
        if (p)
            presets.append(p->toAgentParameters());
    }
    if (presets.isEmpty())
        return std::nullopt;

    // Merge sequentially: later non-null values win, booleans from last preset
    AgentParameters merged = presets.first();
    for (int i = 1; i < presets.size(); ++i) {
        const auto &params = presets[i];
        if (params.temperature.has_value())
            merged.temperature = params.temperature;
        if (params.maxTokens.has_value())
            merged.maxTokens = params.maxTokens;
        if (params.topP.has_value())
            merged.topP = params.topP;
        if (params.topK.has_value())
            merged.topK = params.topK;
        if (params.frequencyPenalty.has_value())
            merged.frequencyPenalty = params.frequencyPenalty;
        if (params.presencePenalty.has_value())
            merged.presencePenalty = params.presencePenalty;
        if (params.systemPrompt.has_value())
            merged.systemPrompt = params.systemPrompt;
        if (params.stopSequences.has_value())
            merged.stopSequences = params.stopSequences;
        if (params.seed.has_value())
            merged.seed = params.seed;
        // Booleans: later preset overrides
        merged.responseFormatJson = params.responseFormatJson;
        merged.searchEnabled = params.searchEnabled;
        merged.returnCitations = params.returnCitations;
        if (params.searchRecency.has_value())
            merged.searchRecency = params.searchRecency;
    }
    return merged;
}

// ===========================================================================
// Settings - Serialization
// ===========================================================================

QJsonObject Settings::toJson() const
{
    QJsonObject obj;

    // providers: { id: config }
    QJsonObject providersObj;
    for (auto it = providers.constBegin(); it != providers.constEnd(); ++it)
        providersObj[it.key()] = it.value().toJson();
    obj[QStringLiteral("providers")] = providersObj;

    // agents
    QJsonArray agentsArr;
    for (const auto &a : agents)
        agentsArr.append(a.toJson());
    obj[QStringLiteral("agents")] = agentsArr;

    // flocks
    QJsonArray flocksArr;
    for (const auto &f : flocks)
        flocksArr.append(f.toJson());
    obj[QStringLiteral("flocks")] = flocksArr;

    // swarms
    QJsonArray swarmsArr;
    for (const auto &s : swarms)
        swarmsArr.append(s.toJson());
    obj[QStringLiteral("swarms")] = swarmsArr;

    // parameters
    QJsonArray paramsArr;
    for (const auto &p : parameters)
        paramsArr.append(p.toJson());
    obj[QStringLiteral("parameters")] = paramsArr;

    // systemPrompts
    QJsonArray spArr;
    for (const auto &sp : systemPrompts)
        spArr.append(sp.toJson());
    obj[QStringLiteral("systemPrompts")] = spArr;

    // prompts
    QJsonArray promptsArr;
    for (const auto &p : prompts)
        promptsArr.append(p.toJson());
    obj[QStringLiteral("prompts")] = promptsArr;

    // endpoints: { providerId: [endpoint, ...] }
    QJsonObject endpointsObj;
    for (auto it = endpoints.constBegin(); it != endpoints.constEnd(); ++it) {
        QJsonArray epArr;
        for (const auto &ep : it.value())
            epArr.append(ep.toJson());
        endpointsObj[it.key()] = epArr;
    }
    obj[QStringLiteral("endpoints")] = endpointsObj;

    // providerStates: { id: state }
    QJsonObject statesObj;
    for (auto it = providerStates.constBegin(); it != providerStates.constEnd(); ++it)
        statesObj[it.key()] = it.value();
    obj[QStringLiteral("providerStates")] = statesObj;

    return obj;
}

Settings Settings::fromJson(const QJsonObject &obj)
{
    Settings s;

    // providers
    const QJsonObject providersObj = obj[QStringLiteral("providers")].toObject();
    for (auto it = providersObj.constBegin(); it != providersObj.constEnd(); ++it)
        s.providers[it.key()] = ProviderConfig::fromJson(it.value().toObject());

    // agents
    for (const auto &v : obj[QStringLiteral("agents")].toArray())
        s.agents.append(Agent::fromJson(v.toObject()));

    // flocks
    for (const auto &v : obj[QStringLiteral("flocks")].toArray())
        s.flocks.append(Flock::fromJson(v.toObject()));

    // swarms
    for (const auto &v : obj[QStringLiteral("swarms")].toArray())
        s.swarms.append(Swarm::fromJson(v.toObject()));

    // parameters
    for (const auto &v : obj[QStringLiteral("parameters")].toArray())
        s.parameters.append(Parameters::fromJson(v.toObject()));

    // systemPrompts
    for (const auto &v : obj[QStringLiteral("systemPrompts")].toArray())
        s.systemPrompts.append(SystemPrompt::fromJson(v.toObject()));

    // prompts
    for (const auto &v : obj[QStringLiteral("prompts")].toArray())
        s.prompts.append(Prompt::fromJson(v.toObject()));

    // endpoints
    const QJsonObject endpointsObj = obj[QStringLiteral("endpoints")].toObject();
    for (auto it = endpointsObj.constBegin(); it != endpointsObj.constEnd(); ++it) {
        QList<Endpoint> epList;
        for (const auto &v : it.value().toArray())
            epList.append(Endpoint::fromJson(v.toObject()));
        s.endpoints[it.key()] = epList;
    }

    // providerStates
    const QJsonObject statesObj = obj[QStringLiteral("providerStates")].toObject();
    for (auto it = statesObj.constBegin(); it != statesObj.constEnd(); ++it)
        s.providerStates[it.key()] = it.value().toString();

    return s;
}
