// SettingsExport.cpp - Export/import settings in v21 Android-compatible format (Linux/Qt6 port)
//
// Export format matches Android/macOS ConfigExport (version 21).
// Key differences from internal Settings serialization:
//   - Agents use "provider" (not "providerId") for the provider reference
//   - Swarm members use "provider" (not "providerId")
//   - Prompts are stored under "aiPrompts" (not "prompts")
//   - Provider configs include service metadata (displayName, baseUrl, apiFormat, etc.)
//   - Version field for forward/backward compatibility (accepts 11-21)

#include "helpers/SettingsExport.h"

#include <QFile>
#include <QFileDialog>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QDateTime>
#include <QUuid>

static constexpr int kCurrentVersion = 21;

// ===========================================================================
// Export helpers: build JSON objects for the export format
// ===========================================================================

static QJsonObject buildProviderExport(const AppService &service, const ProviderConfig &config)
{
    QJsonObject obj;
    obj[QStringLiteral("modelSource")] = modelSourceToString(config.modelSource);

    QJsonArray modelsArr;
    for (const auto &m : config.models)
        modelsArr.append(m);
    obj[QStringLiteral("models")] = modelsArr;

    obj[QStringLiteral("apiKey")] = config.apiKey;

    if (!config.model.isEmpty())
        obj[QStringLiteral("defaultModel")] = config.model;
    if (!config.adminUrl.isEmpty())
        obj[QStringLiteral("adminUrl")] = config.adminUrl;
    if (!config.modelListUrl.isEmpty())
        obj[QStringLiteral("modelListUrl")] = config.modelListUrl;

    if (!config.parametersIds.isEmpty()) {
        QJsonArray pArr;
        for (const auto &pid : config.parametersIds)
            pArr.append(pid);
        obj[QStringLiteral("parametersIds")] = pArr;
    }

    // Service metadata
    obj[QStringLiteral("displayName")] = service.displayName;
    obj[QStringLiteral("baseUrl")] = service.baseUrl;
    obj[QStringLiteral("apiFormat")] = apiFormatToString(service.apiFormat);
    obj[QStringLiteral("chatPath")] = service.chatPath;
    if (service.modelsPath.has_value())
        obj[QStringLiteral("modelsPath")] = *service.modelsPath;
    if (service.openRouterName.has_value())
        obj[QStringLiteral("openRouterName")] = *service.openRouterName;

    return obj;
}

static QJsonObject buildAgentExport(const Agent &agent)
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = agent.id;
    obj[QStringLiteral("name")] = agent.name;
    obj[QStringLiteral("provider")] = agent.providerId;  // "provider" not "providerId"
    obj[QStringLiteral("model")] = agent.model;
    obj[QStringLiteral("apiKey")] = agent.apiKey;

    if (!agent.paramsIds.isEmpty()) {
        QJsonArray arr;
        for (const auto &pid : agent.paramsIds)
            arr.append(pid);
        obj[QStringLiteral("parametersIds")] = arr;
    }

    if (agent.endpointId.has_value())
        obj[QStringLiteral("endpointId")] = *agent.endpointId;
    else
        obj[QStringLiteral("endpointId")] = QJsonValue(QJsonValue::Null);

    if (agent.systemPromptId.has_value())
        obj[QStringLiteral("systemPromptId")] = *agent.systemPromptId;
    else
        obj[QStringLiteral("systemPromptId")] = QJsonValue(QJsonValue::Null);

    return obj;
}

static QJsonObject buildFlockExport(const Flock &flock)
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = flock.id;
    obj[QStringLiteral("name")] = flock.name;

    QJsonArray agentArr;
    for (const auto &aid : flock.agentIds)
        agentArr.append(aid);
    obj[QStringLiteral("agentIds")] = agentArr;

    if (!flock.paramsIds.isEmpty()) {
        QJsonArray pArr;
        for (const auto &pid : flock.paramsIds)
            pArr.append(pid);
        obj[QStringLiteral("parametersIds")] = pArr;
    }

    if (flock.systemPromptId.has_value())
        obj[QStringLiteral("systemPromptId")] = *flock.systemPromptId;

    return obj;
}

static QJsonObject buildSwarmExport(const Swarm &swarm)
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = swarm.id;
    obj[QStringLiteral("name")] = swarm.name;

    QJsonArray membersArr;
    for (const auto &member : swarm.members) {
        QJsonObject mObj;
        mObj[QStringLiteral("provider")] = member.providerId;  // "provider" not "providerId"
        mObj[QStringLiteral("model")] = member.model;
        membersArr.append(mObj);
    }
    obj[QStringLiteral("members")] = membersArr;

    if (!swarm.paramsIds.isEmpty()) {
        QJsonArray pArr;
        for (const auto &pid : swarm.paramsIds)
            pArr.append(pid);
        obj[QStringLiteral("parametersIds")] = pArr;
    }

    if (swarm.systemPromptId.has_value())
        obj[QStringLiteral("systemPromptId")] = *swarm.systemPromptId;

    return obj;
}

static QJsonObject buildParametersExport(const Parameters &params)
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = params.id;
    obj[QStringLiteral("name")] = params.name;
    if (params.temperature.has_value())
        obj[QStringLiteral("temperature")] = static_cast<double>(*params.temperature);
    if (params.maxTokens.has_value())
        obj[QStringLiteral("maxTokens")] = *params.maxTokens;
    if (params.topP.has_value())
        obj[QStringLiteral("topP")] = static_cast<double>(*params.topP);
    if (params.topK.has_value())
        obj[QStringLiteral("topK")] = *params.topK;
    if (params.frequencyPenalty.has_value())
        obj[QStringLiteral("frequencyPenalty")] = static_cast<double>(*params.frequencyPenalty);
    if (params.presencePenalty.has_value())
        obj[QStringLiteral("presencePenalty")] = static_cast<double>(*params.presencePenalty);
    if (params.systemPrompt.has_value())
        obj[QStringLiteral("systemPrompt")] = *params.systemPrompt;
    if (params.stopSequences.has_value()) {
        QJsonArray arr;
        for (const auto &s : *params.stopSequences)
            arr.append(s);
        obj[QStringLiteral("stopSequences")] = arr;
    }
    if (params.seed.has_value())
        obj[QStringLiteral("seed")] = *params.seed;
    obj[QStringLiteral("responseFormatJson")] = params.responseFormatJson;
    obj[QStringLiteral("searchEnabled")] = params.searchEnabled;
    obj[QStringLiteral("returnCitations")] = params.returnCitations;
    if (params.searchRecency.has_value())
        obj[QStringLiteral("searchRecency")] = *params.searchRecency;
    return obj;
}

static QJsonObject buildSystemPromptExport(const SystemPrompt &sp)
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = sp.id;
    obj[QStringLiteral("name")] = sp.name;
    obj[QStringLiteral("prompt")] = sp.prompt;
    return obj;
}

static QJsonObject buildPromptExport(const Prompt &prompt)
{
    QJsonObject obj;
    obj[QStringLiteral("id")] = prompt.id;
    obj[QStringLiteral("name")] = prompt.name;
    obj[QStringLiteral("agentId")] = prompt.agentId;
    obj[QStringLiteral("promptText")] = prompt.promptText;
    return obj;
}

// ===========================================================================
// Import helpers: parse export-format JSON back into model objects
// ===========================================================================

static void processImport(const QJsonObject &root, Settings *outSettings, GeneralSettings *outGeneral)
{
    Settings settings;

    // -- Agents (filter by valid provider) --
    for (const auto &v : root[QStringLiteral("agents")].toArray()) {
        const QJsonObject ae = v.toObject();
        const QString providerId = ae[QStringLiteral("provider")].toString();
        if (!AppService::findById(providerId))
            continue;

        Agent agent;
        agent.id = ae[QStringLiteral("id")].toString();
        if (agent.id.isEmpty())
            agent.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        agent.name = ae[QStringLiteral("name")].toString();
        agent.providerId = providerId;
        agent.model = ae[QStringLiteral("model")].toString();
        agent.apiKey = ae[QStringLiteral("apiKey")].toString();
        if (ae.contains(QStringLiteral("endpointId")) && !ae[QStringLiteral("endpointId")].isNull())
            agent.endpointId = ae[QStringLiteral("endpointId")].toString();
        for (const auto &pid : ae[QStringLiteral("parametersIds")].toArray())
            agent.paramsIds.append(pid.toString());
        if (ae.contains(QStringLiteral("systemPromptId")) && !ae[QStringLiteral("systemPromptId")].isNull())
            agent.systemPromptId = ae[QStringLiteral("systemPromptId")].toString();

        settings.agents.append(agent);
    }

    // -- Flocks --
    for (const auto &v : root[QStringLiteral("flocks")].toArray()) {
        const QJsonObject fe = v.toObject();
        Flock flock;
        flock.id = fe[QStringLiteral("id")].toString();
        if (flock.id.isEmpty())
            flock.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        flock.name = fe[QStringLiteral("name")].toString();
        for (const auto &aid : fe[QStringLiteral("agentIds")].toArray())
            flock.agentIds.append(aid.toString());
        for (const auto &pid : fe[QStringLiteral("parametersIds")].toArray())
            flock.paramsIds.append(pid.toString());
        if (fe.contains(QStringLiteral("systemPromptId")) && !fe[QStringLiteral("systemPromptId")].isNull())
            flock.systemPromptId = fe[QStringLiteral("systemPromptId")].toString();
        settings.flocks.append(flock);
    }

    // -- Swarms (filter members by valid provider) --
    for (const auto &v : root[QStringLiteral("swarms")].toArray()) {
        const QJsonObject se = v.toObject();
        Swarm swarm;
        swarm.id = se[QStringLiteral("id")].toString();
        if (swarm.id.isEmpty())
            swarm.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        swarm.name = se[QStringLiteral("name")].toString();
        for (const auto &mv : se[QStringLiteral("members")].toArray()) {
            const QJsonObject me = mv.toObject();
            const QString membProviderId = me[QStringLiteral("provider")].toString();
            if (!AppService::findById(membProviderId))
                continue;
            SwarmMember member;
            member.providerId = membProviderId;
            member.model = me[QStringLiteral("model")].toString();
            swarm.members.append(member);
        }
        for (const auto &pid : se[QStringLiteral("parametersIds")].toArray())
            swarm.paramsIds.append(pid.toString());
        if (se.contains(QStringLiteral("systemPromptId")) && !se[QStringLiteral("systemPromptId")].isNull())
            swarm.systemPromptId = se[QStringLiteral("systemPromptId")].toString();
        settings.swarms.append(swarm);
    }

    // -- Parameters --
    for (const auto &v : root[QStringLiteral("parameters")].toArray()) {
        const QJsonObject pe = v.toObject();
        Parameters params;
        params.id = pe[QStringLiteral("id")].toString();
        if (params.id.isEmpty())
            params.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        params.name = pe[QStringLiteral("name")].toString();
        if (pe.contains(QStringLiteral("temperature")))
            params.temperature = static_cast<float>(pe[QStringLiteral("temperature")].toDouble());
        if (pe.contains(QStringLiteral("maxTokens")))
            params.maxTokens = pe[QStringLiteral("maxTokens")].toInt();
        if (pe.contains(QStringLiteral("topP")))
            params.topP = static_cast<float>(pe[QStringLiteral("topP")].toDouble());
        if (pe.contains(QStringLiteral("topK")))
            params.topK = pe[QStringLiteral("topK")].toInt();
        if (pe.contains(QStringLiteral("frequencyPenalty")))
            params.frequencyPenalty = static_cast<float>(pe[QStringLiteral("frequencyPenalty")].toDouble());
        if (pe.contains(QStringLiteral("presencePenalty")))
            params.presencePenalty = static_cast<float>(pe[QStringLiteral("presencePenalty")].toDouble());
        if (pe.contains(QStringLiteral("systemPrompt")))
            params.systemPrompt = pe[QStringLiteral("systemPrompt")].toString();
        if (pe.contains(QStringLiteral("stopSequences"))) {
            QStringList list;
            for (const auto &sv : pe[QStringLiteral("stopSequences")].toArray())
                list.append(sv.toString());
            params.stopSequences = list;
        }
        if (pe.contains(QStringLiteral("seed")))
            params.seed = pe[QStringLiteral("seed")].toInt();
        params.responseFormatJson = pe[QStringLiteral("responseFormatJson")].toBool(false);
        params.searchEnabled = pe[QStringLiteral("searchEnabled")].toBool(false);
        params.returnCitations = pe[QStringLiteral("returnCitations")].toBool(false);
        if (pe.contains(QStringLiteral("searchRecency")))
            params.searchRecency = pe[QStringLiteral("searchRecency")].toString();
        settings.parameters.append(params);
    }

    // -- System prompts --
    for (const auto &v : root[QStringLiteral("systemPrompts")].toArray()) {
        const QJsonObject spe = v.toObject();
        SystemPrompt sp;
        sp.id = spe[QStringLiteral("id")].toString();
        if (sp.id.isEmpty())
            sp.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        sp.name = spe[QStringLiteral("name")].toString();
        sp.prompt = spe[QStringLiteral("prompt")].toString();
        settings.systemPrompts.append(sp);
    }

    // -- Prompts (stored as "aiPrompts" in export format) --
    for (const auto &v : root[QStringLiteral("aiPrompts")].toArray()) {
        const QJsonObject pe = v.toObject();
        Prompt prompt;
        prompt.id = pe[QStringLiteral("id")].toString();
        if (prompt.id.isEmpty())
            prompt.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        prompt.name = pe[QStringLiteral("name")].toString();
        prompt.agentId = pe[QStringLiteral("agentId")].toString();
        prompt.promptText = pe[QStringLiteral("promptText")].toString();
        settings.prompts.append(prompt);
    }

    // -- Provider states --
    const QJsonObject statesObj = root[QStringLiteral("providerStates")].toObject();
    for (auto it = statesObj.constBegin(); it != statesObj.constEnd(); ++it)
        settings.providerStates[it.key()] = it.value().toString();

    // -- Provider configs --
    const QJsonObject providersObj = root[QStringLiteral("providers")].toObject();
    for (auto it = providersObj.constBegin(); it != providersObj.constEnd(); ++it) {
        const QString providerId = it.key();
        AppService *service = AppService::findById(providerId);
        if (!service)
            continue;

        const QJsonObject pe = it.value().toObject();
        ProviderConfig config;
        config.modelSource = modelSourceFromString(pe[QStringLiteral("modelSource")].toString());
        for (const auto &mv : pe[QStringLiteral("models")].toArray())
            config.models.append(mv.toString());
        config.apiKey = pe[QStringLiteral("apiKey")].toString();
        config.model = pe[QStringLiteral("defaultModel")].toString();
        config.adminUrl = pe[QStringLiteral("adminUrl")].toString();
        config.modelListUrl = pe[QStringLiteral("modelListUrl")].toString();
        for (const auto &pid : pe[QStringLiteral("parametersIds")].toArray())
            config.parametersIds.append(pid.toString());

        settings.providers[providerId] = config;
    }

    *outSettings = settings;

    // -- General settings (HuggingFace + OpenRouter API keys) --
    const QString hfKey = root[QStringLiteral("huggingFaceApiKey")].toString();
    const QString orKey = root[QStringLiteral("openRouterApiKey")].toString();
    if (!hfKey.isEmpty() || !orKey.isEmpty()) {
        outGeneral->huggingFaceApiKey = hfKey;
        outGeneral->openRouterApiKey = orKey;
    }
}

// ===========================================================================
// Public API
// ===========================================================================

void SettingsExport::exportSettings(const Settings &aiSettings, const GeneralSettings &generalSettings)
{
    // Build the root export object
    QJsonObject root;
    root[QStringLiteral("version")] = kCurrentVersion;

    // -- Providers --
    QJsonObject providersObj;
    for (const auto &service : AppService::entries) {
        ProviderConfig config = aiSettings.getProvider(service);
        providersObj[service.id] = buildProviderExport(service, config);
    }
    root[QStringLiteral("providers")] = providersObj;

    // -- Agents --
    QJsonArray agentsArr;
    for (const auto &agent : aiSettings.agents)
        agentsArr.append(buildAgentExport(agent));
    root[QStringLiteral("agents")] = agentsArr;

    // -- Flocks --
    if (!aiSettings.flocks.isEmpty()) {
        QJsonArray flocksArr;
        for (const auto &flock : aiSettings.flocks)
            flocksArr.append(buildFlockExport(flock));
        root[QStringLiteral("flocks")] = flocksArr;
    }

    // -- Swarms --
    if (!aiSettings.swarms.isEmpty()) {
        QJsonArray swarmsArr;
        for (const auto &swarm : aiSettings.swarms)
            swarmsArr.append(buildSwarmExport(swarm));
        root[QStringLiteral("swarms")] = swarmsArr;
    }

    // -- Parameters --
    if (!aiSettings.parameters.isEmpty()) {
        QJsonArray paramsArr;
        for (const auto &p : aiSettings.parameters)
            paramsArr.append(buildParametersExport(p));
        root[QStringLiteral("parameters")] = paramsArr;
    }

    // -- System prompts --
    if (!aiSettings.systemPrompts.isEmpty()) {
        QJsonArray spArr;
        for (const auto &sp : aiSettings.systemPrompts)
            spArr.append(buildSystemPromptExport(sp));
        root[QStringLiteral("systemPrompts")] = spArr;
    }

    // -- AI prompts (stored as "aiPrompts" in export format) --
    if (!aiSettings.prompts.isEmpty()) {
        QJsonArray promptsArr;
        for (const auto &p : aiSettings.prompts)
            promptsArr.append(buildPromptExport(p));
        root[QStringLiteral("aiPrompts")] = promptsArr;
    }

    // -- General API keys --
    if (!generalSettings.huggingFaceApiKey.isEmpty())
        root[QStringLiteral("huggingFaceApiKey")] = generalSettings.huggingFaceApiKey;
    if (!generalSettings.openRouterApiKey.isEmpty())
        root[QStringLiteral("openRouterApiKey")] = generalSettings.openRouterApiKey;

    // -- Provider states --
    if (!aiSettings.providerStates.isEmpty()) {
        QJsonObject statesObj;
        for (auto it = aiSettings.providerStates.constBegin();
             it != aiSettings.providerStates.constEnd(); ++it) {
            statesObj[it.key()] = it.value();
        }
        root[QStringLiteral("providerStates")] = statesObj;
    }

    // Pretty-print with sorted keys
    QJsonDocument doc(root);
    QByteArray json = doc.toJson(QJsonDocument::Indented);

    // Default filename with timestamp
    const QString timestamp = QDateTime::currentDateTime().toString(QStringLiteral("yyyyMMdd_HHmmss"));
    const QString defaultName = QStringLiteral("ai_config_%1.json").arg(timestamp);

    const QString filePath = QFileDialog::getSaveFileName(
        nullptr,
        QStringLiteral("Export AI Configuration"),
        defaultName,
        QStringLiteral("JSON (*.json)")
    );

    if (filePath.isEmpty())
        return;

    QFile file(filePath);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return;

    file.write(json);
    file.close();
}

void SettingsExport::importSettings(std::function<void(Settings*, GeneralSettings*)> callback)
{
    const QString filePath = QFileDialog::getOpenFileName(
        nullptr,
        QStringLiteral("Import AI Configuration"),
        QString(),
        QStringLiteral("JSON (*.json)")
    );

    if (filePath.isEmpty()) {
        callback(nullptr, nullptr);
        return;
    }

    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        callback(nullptr, nullptr);
        return;
    }

    const QByteArray data = file.readAll();
    file.close();

    if (data.isEmpty()) {
        callback(nullptr, nullptr);
        return;
    }

    QJsonParseError parseError;
    const QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
    if (parseError.error != QJsonParseError::NoError || !doc.isObject()) {
        callback(nullptr, nullptr);
        return;
    }

    const QJsonObject root = doc.object();
    const int version = root[QStringLiteral("version")].toInt(0);

    if (version < 11 || version > kCurrentVersion) {
        callback(nullptr, nullptr);
        return;
    }

    Settings settings;
    GeneralSettings general;
    processImport(root, &settings, &general);

    callback(&settings, &general);
}

void SettingsExport::exportApiKeys(const Settings &aiSettings, const GeneralSettings &generalSettings)
{
    QJsonObject root;
    root[QStringLiteral("type")] = QStringLiteral("api_keys");

    QJsonArray keysArr;
    for (const auto &service : AppService::entries) {
        const QString apiKey = aiSettings.getApiKey(service);
        if (apiKey.isEmpty())
            continue;

        QJsonObject entry;
        entry[QStringLiteral("service")] = service.id;
        entry[QStringLiteral("apiKey")] = apiKey;
        keysArr.append(entry);
    }
    root[QStringLiteral("keys")] = keysArr;

    if (!generalSettings.huggingFaceApiKey.isEmpty())
        root[QStringLiteral("huggingFaceApiKey")] = generalSettings.huggingFaceApiKey;
    if (!generalSettings.openRouterApiKey.isEmpty())
        root[QStringLiteral("openRouterApiKey")] = generalSettings.openRouterApiKey;

    QJsonDocument doc(root);
    QByteArray json = doc.toJson(QJsonDocument::Indented);

    const QString timestamp = QDateTime::currentDateTime().toString(QStringLiteral("yyyyMMdd_HHmmss"));
    const QString defaultName = QStringLiteral("api_keys_%1.json").arg(timestamp);

    const QString filePath = QFileDialog::getSaveFileName(
        nullptr,
        QStringLiteral("Export API Keys"),
        defaultName,
        QStringLiteral("JSON (*.json)")
    );

    if (filePath.isEmpty())
        return;

    QFile file(filePath);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return;

    file.write(json);
    file.close();
}

void SettingsExport::importApiKeys(Settings &currentSettings, GeneralSettings &currentGeneralSettings,
                                   std::function<void(bool success)> callback)
{
    const QString filePath = QFileDialog::getOpenFileName(
        nullptr,
        QStringLiteral("Import API Keys"),
        QString(),
        QStringLiteral("JSON (*.json)")
    );

    if (filePath.isEmpty()) {
        callback(false);
        return;
    }

    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        callback(false);
        return;
    }

    const QByteArray data = file.readAll();
    file.close();

    if (data.isEmpty()) {
        callback(false);
        return;
    }

    QJsonParseError parseError;
    const QJsonDocument doc = QJsonDocument::fromJson(data, &parseError);
    if (parseError.error != QJsonParseError::NoError || !doc.isObject()) {
        callback(false);
        return;
    }

    const QJsonObject root = doc.object();

    if (root[QStringLiteral("type")].toString() != QStringLiteral("api_keys")) {
        callback(false);
        return;
    }

    for (const auto &v : root[QStringLiteral("keys")].toArray()) {
        const QJsonObject entry = v.toObject();
        const QString serviceId = entry[QStringLiteral("service")].toString();
        const QString apiKey = entry[QStringLiteral("apiKey")].toString();
        if (apiKey.isEmpty())
            continue;
        AppService *service = AppService::findById(serviceId);
        if (!service)
            continue;
        currentSettings.setApiKey(*service, apiKey);
    }

    const QString hfKey = root[QStringLiteral("huggingFaceApiKey")].toString();
    const QString orKey = root[QStringLiteral("openRouterApiKey")].toString();
    if (!hfKey.isEmpty())
        currentGeneralSettings.huggingFaceApiKey = hfKey;
    if (!orKey.isEmpty())
        currentGeneralSettings.openRouterApiKey = orKey;

    callback(true);
}
