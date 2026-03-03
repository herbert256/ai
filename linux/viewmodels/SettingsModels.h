// SettingsModels.h - Settings configuration classes for the AI app (Linux/Qt6 port)
// Higher-level settings/config: ProviderConfig, Agent, Flock, Swarm, Parameters, etc.
// Data layer structs (AgentParameters, ChatMessage, etc.) are in data/DataModels.h.

#pragma once

#include <QString>
#include <QList>
#include <QMap>
#include <QJsonObject>
#include <QJsonArray>
#include <optional>

#include "data/DataModels.h"
#include "data/AppService.h"

// ---------------------------------------------------------------------------
// ModelSource
// ---------------------------------------------------------------------------

enum class ModelSource { Api, Manual };

QString modelSourceToString(ModelSource s);   // "API", "MANUAL"
ModelSource modelSourceFromString(const QString &s);

// ---------------------------------------------------------------------------
// ProviderConfig - per-provider configuration stored in Settings
// ---------------------------------------------------------------------------

struct ProviderConfig {
    QString apiKey;
    QString model;
    ModelSource modelSource = ModelSource::Api;
    QStringList models;
    QString adminUrl;
    QString modelListUrl;
    QStringList parametersIds;

    QJsonObject toJson() const;
    static ProviderConfig fromJson(const QJsonObject &obj);
    bool operator==(const ProviderConfig &) const = default;
};

/// Create default ProviderConfig for a service, including provider-specific defaults.
ProviderConfig defaultProviderConfig(const AppService &service);

/// Create the default providers map with correct defaults for all services.
QMap<QString, ProviderConfig> defaultProvidersMap();

// ---------------------------------------------------------------------------
// Endpoint - configurable API endpoint for a provider
// ---------------------------------------------------------------------------

struct Endpoint {
    QString id;
    QString name;
    QString url;
    bool isDefault = false;

    QJsonObject toJson() const;
    static Endpoint fromJson(const QJsonObject &obj);
    bool operator==(const Endpoint &) const = default;
};

/// Built-in endpoints map: providerId -> list of endpoints.
QMap<QString, QList<Endpoint>> builtInEndpoints();

// ---------------------------------------------------------------------------
// Agent - user-created configuration combining provider, model, API key, and parameter presets
// ---------------------------------------------------------------------------

struct Agent {
    QString id;                               // UUID
    QString name;
    QString providerId;                       // Reference to AppService.id
    QString model;
    QString apiKey;
    std::optional<QString> endpointId;        // Reference to Endpoint ID
    QStringList paramsIds;                     // References to Parameters IDs
    std::optional<QString> systemPromptId;    // Reference to SystemPrompt ID

    QJsonObject toJson() const;
    static Agent fromJson(const QJsonObject &obj);
    bool operator==(const Agent &) const = default;
};

// ---------------------------------------------------------------------------
// Flock - a named group of AI Agents that work together
// ---------------------------------------------------------------------------

struct Flock {
    QString id;
    QString name;
    QStringList agentIds;
    QStringList paramsIds;
    std::optional<QString> systemPromptId;

    QJsonObject toJson() const;
    static Flock fromJson(const QJsonObject &obj);
    bool operator==(const Flock &) const = default;
};

// ---------------------------------------------------------------------------
// SwarmMember - a provider/model combination within a swarm
// ---------------------------------------------------------------------------

struct SwarmMember {
    QString providerId;
    QString model;

    QJsonObject toJson() const;
    static SwarmMember fromJson(const QJsonObject &obj);
    bool operator==(const SwarmMember &) const = default;
};

// ---------------------------------------------------------------------------
// Swarm - a named group of provider/model combinations
// ---------------------------------------------------------------------------

struct Swarm {
    QString id;
    QString name;
    QList<SwarmMember> members;
    QStringList paramsIds;
    std::optional<QString> systemPromptId;

    QJsonObject toJson() const;
    static Swarm fromJson(const QJsonObject &obj);
    bool operator==(const Swarm &) const = default;
};

// ---------------------------------------------------------------------------
// Parameters - a named parameter preset reusable across agents or reports
// ---------------------------------------------------------------------------

struct Parameters {
    QString id;
    QString name;
    std::optional<float> temperature;
    std::optional<int> maxTokens;
    std::optional<float> topP;
    std::optional<int> topK;
    std::optional<float> frequencyPenalty;
    std::optional<float> presencePenalty;
    std::optional<QString> systemPrompt;
    std::optional<QStringList> stopSequences;
    std::optional<int> seed;
    bool responseFormatJson = false;
    bool searchEnabled = false;
    bool returnCitations = true;
    std::optional<QString> searchRecency;     // "day", "week", "month", "year"

    AgentParameters toAgentParameters() const;
    QJsonObject toJson() const;
    static Parameters fromJson(const QJsonObject &obj);
    bool operator==(const Parameters &) const = default;
};

// ---------------------------------------------------------------------------
// SystemPrompt - a reusable named system prompt
// ---------------------------------------------------------------------------

struct SystemPrompt {
    QString id;
    QString name;
    QString prompt;

    QJsonObject toJson() const;
    static SystemPrompt fromJson(const QJsonObject &obj);
    bool operator==(const SystemPrompt &) const = default;
};

// ---------------------------------------------------------------------------
// Prompt - internal prompts used by the app for AI-powered features
// ---------------------------------------------------------------------------

struct Prompt {
    QString id;
    QString name;
    QString agentId;                          // Reference to Agent ID
    QString promptText;

    /// Replace variables: @MODEL@, @PROVIDER@, @AGENT@, @SWARM@, @NOW@ (current datetime ISO).
    QString resolvePrompt(const QString &model = QString(),
                          const QString &provider = QString(),
                          const QString &agent = QString(),
                          const QString &swarm = QString()) const;

    QJsonObject toJson() const;
    static Prompt fromJson(const QJsonObject &obj);
    bool operator==(const Prompt &) const = default;
};

// ---------------------------------------------------------------------------
// ReportModel - a model entry in the AI Reports selection list
// ---------------------------------------------------------------------------

struct ReportModel {
    QString id;                               // UUID
    QString providerId;
    QString model;
    QString type;                             // "agent" or "model"
    QString sourceType;                       // "flock", "agent", "swarm", "model"
    QString sourceName;
    std::optional<QString> agentId;
    std::optional<QString> endpointId;
    std::optional<QString> agentApiKey;
    QStringList paramsIds;

    QString deduplicationKey() const { return providerId + QStringLiteral(":") + model; }
};

// ---------------------------------------------------------------------------
// UsageStats - statistics for a specific provider+model combination
// ---------------------------------------------------------------------------

struct UsageStats {
    QString providerId;
    QString model;
    int callCount = 0;
    qint64 inputTokens = 0;
    qint64 outputTokens = 0;

    qint64 totalTokens() const { return inputTokens + outputTokens; }
    QString key() const { return providerId + QStringLiteral("::") + model; }

    QJsonObject toJson() const;
    static UsageStats fromJson(const QJsonObject &obj);
};

// ---------------------------------------------------------------------------
// PromptHistoryEntry - a saved prompt from history
// ---------------------------------------------------------------------------

struct PromptHistoryEntry {
    QString id;                               // UUID
    QDateTime timestamp;
    QString title;
    QString prompt;

    QJsonObject toJson() const;
    static PromptHistoryEntry fromJson(const QJsonObject &obj);
};

// ---------------------------------------------------------------------------
// Settings - main configuration container for all providers, agents, etc.
// ---------------------------------------------------------------------------

struct Settings {
    QMap<QString, ProviderConfig> providers;   // keyed by provider ID
    QList<Agent> agents;
    QList<Flock> flocks;
    QList<Swarm> swarms;
    QList<Parameters> parameters;
    QList<SystemPrompt> systemPrompts;
    QList<Prompt> prompts;
    QMap<QString, QList<Endpoint>> endpoints;  // per provider
    QMap<QString, QString> providerStates;     // "ok", "error", "not-used", "inactive"

    // -- Provider access --
    QString getProviderState(const AppService &service) const;
    bool isProviderActive(const AppService &service) const;
    QList<AppService> getActiveServices() const;

    ProviderConfig getProvider(const AppService &service) const;
    void setProvider(const AppService &service, const ProviderConfig &config);

    QString getApiKey(const AppService &service) const;
    void setApiKey(const AppService &service, const QString &key);
    QString getModel(const AppService &service) const;
    void setModel(const AppService &service, const QString &model);
    QStringList getModels(const AppService &service) const;
    void setModels(const AppService &service, const QStringList &models);

    // -- Entity lookup --
    Agent* getAgentById(const QString &id);
    const Agent* getAgentById(const QString &id) const;
    Flock* getFlockById(const QString &id);
    const Flock* getFlockById(const QString &id) const;
    Swarm* getSwarmById(const QString &id);
    const Swarm* getSwarmById(const QString &id) const;
    SystemPrompt* getSystemPromptById(const QString &id);
    const SystemPrompt* getSystemPromptById(const QString &id) const;
    Parameters* getParametersById(const QString &id);
    const Parameters* getParametersById(const QString &id) const;
    Prompt* getPromptById(const QString &id);
    const Prompt* getPromptById(const QString &id) const;

    // -- Agent resolution --
    AgentParameters resolveAgentParameters(const Agent &agent) const;
    QString getEffectiveApiKeyForAgent(const Agent &agent) const;
    QString getEffectiveModelForAgent(const Agent &agent) const;
    QList<Agent> getAgentsForFlock(const Flock &flock) const;

    // -- Endpoint resolution --
    QList<Endpoint> getEndpointsForProvider(const AppService &provider) const;
    Endpoint* getDefaultEndpoint(const AppService &provider);
    const Endpoint* getDefaultEndpoint(const AppService &provider) const;
    QString getEffectiveEndpointUrl(const AppService &provider) const;
    QString getEffectiveEndpointUrlForAgent(const Agent &agent) const;

    // -- Parameter merging --
    std::optional<AgentParameters> mergeParameters(const QStringList &ids) const;

    // -- Serialization --
    QJsonObject toJson() const;
    static Settings fromJson(const QJsonObject &obj);
    bool operator==(const Settings &) const = default;
};
