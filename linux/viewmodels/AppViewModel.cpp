// AppViewModel.cpp - Central QObject implementation (Linux/Qt6 port)

#include "viewmodels/AppViewModel.h"
#include "data/ProviderRegistry.h"
#include "data/AnalysisProviders.h"
#include "data/AnalysisStreaming.h"
#include "data/AnalysisChat.h"
#include "data/AnalysisModels.h"
#include "data/AnalysisRepository.h"
#include "data/ReportStorage.h"
#include "data/ChatHistoryManager.h"
#include "data/ApiTracer.h"
#include "data/PricingCache.h"

#include <QGuiApplication>
#include <QClipboard>
#include <QDesktopServices>
#include <QUrl>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QStandardPaths>
#include <QUuid>
#include <QDateTime>
#include <QDirIterator>
#include <algorithm>

// =============================================================================
// Construction & Bootstrap
// =============================================================================

AppViewModel::AppViewModel(QObject *parent)
    : QObject(parent)
{
}

void AppViewModel::bootstrap()
{
    // Initialize provider registry (loads from setup.json on first run,
    // then from persisted provider-registry.json)
    ProviderRegistry::instance().initialize();

    // Load all settings from disk
    loadSettings();
    loadUsageStats();
    loadPromptHistory();

    emit settingsChanged();
    emit usageStatsChanged();
}

// =============================================================================
// Navigation
// =============================================================================

QString AppViewModel::currentSection() const
{
    return m_currentSection;
}

void AppViewModel::setCurrentSection(const QString &section)
{
    if (m_currentSection != section) {
        m_currentSection = section;
        emit currentSectionChanged();
    }
}

// =============================================================================
// Loading State
// =============================================================================

bool AppViewModel::isLoading() const { return m_isLoading; }
QString AppViewModel::errorMessage() const { return m_errorMessage; }

// =============================================================================
// Counts
// =============================================================================

int AppViewModel::providerCount() const
{
    return AppService::entries.size();
}

int AppViewModel::agentCount() const
{
    return m_settings.agents.size();
}

int AppViewModel::flockCount() const
{
    return m_settings.flocks.size();
}

int AppViewModel::swarmCount() const
{
    return m_settings.swarms.size();
}

int AppViewModel::activeProviderCount() const
{
    int count = 0;
    for (const auto &service : AppService::entries) {
        if (m_settings.isProviderActive(service))
            ++count;
    }
    return count;
}

// =============================================================================
// Report State Properties
// =============================================================================

bool AppViewModel::showReportProgress() const { return m_showReportProgress; }
int AppViewModel::reportProgress() const { return m_reportProgress; }
int AppViewModel::reportTotal() const { return m_reportTotal; }
QString AppViewModel::currentReportId() const { return m_currentReportId; }

// =============================================================================
// Usage Stats Property
// =============================================================================

QVariantList AppViewModel::usageStatsList() const
{
    QVariantList list;
    for (auto it = m_usageStats.cbegin(); it != m_usageStats.cend(); ++it) {
        const auto &us = it.value();
        QVariantMap map;
        map["providerId"] = us.providerId;
        map["model"] = us.model;
        map["callCount"] = us.callCount;
        map["inputTokens"] = static_cast<qlonglong>(us.inputTokens);
        map["outputTokens"] = static_cast<qlonglong>(us.outputTokens);
        map["totalTokens"] = static_cast<qlonglong>(us.totalTokens());
        list.append(map);
    }
    return list;
}

// =============================================================================
// Provider Management
// =============================================================================

QVariantList AppViewModel::getProviders()
{
    QVariantList list;
    for (const auto &service : AppService::entries) {
        QVariantMap map;
        map["id"] = service.id;
        map["displayName"] = service.displayName;
        map["baseUrl"] = service.baseUrl;
        map["adminUrl"] = service.adminUrl;
        map["defaultModel"] = service.defaultModel;
        map["apiFormat"] = apiFormatToString(service.apiFormat);
        map["state"] = m_settings.getProviderState(service);

        const auto config = m_settings.getProvider(service);
        map["apiKey"] = config.apiKey;
        map["model"] = config.model;
        map["models"] = QVariant::fromValue(config.models);
        map["isActive"] = m_settings.isProviderActive(service);
        list.append(map);
    }
    return list;
}

QVariantMap AppViewModel::getProviderConfig(const QString &providerId)
{
    auto *service = AppService::findById(providerId);
    if (!service) return {};

    const auto config = m_settings.getProvider(*service);
    QVariantMap map;
    map["apiKey"] = config.apiKey;
    map["model"] = config.model;
    map["modelSource"] = modelSourceToString(config.modelSource);
    map["models"] = QVariant::fromValue(config.models);
    map["adminUrl"] = config.adminUrl;
    map["modelListUrl"] = config.modelListUrl;
    map["parametersIds"] = QVariant::fromValue(config.parametersIds);
    map["state"] = m_settings.getProviderState(*service);
    return map;
}

void AppViewModel::setProviderApiKey(const QString &providerId, const QString &apiKey)
{
    auto *service = AppService::findById(providerId);
    if (!service) return;
    m_settings.setApiKey(*service, apiKey);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::setProviderModel(const QString &providerId, const QString &model)
{
    auto *service = AppService::findById(providerId);
    if (!service) return;
    m_settings.setModel(*service, model);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::setProviderModels(const QString &providerId, const QStringList &models)
{
    auto *service = AppService::findById(providerId);
    if (!service) return;
    m_settings.setModels(*service, models);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::setProviderState(const QString &providerId, const QString &state)
{
    m_settings.providerStates[providerId] = state;
    saveSettings();
    emit settingsChanged();
}

QStringList AppViewModel::getProviderModels(const QString &providerId)
{
    auto *service = AppService::findById(providerId);
    if (!service) return {};
    return m_settings.getModels(*service);
}

void AppViewModel::fetchModels(const QString &providerId)
{
    auto *service = AppService::findById(providerId);
    if (!service) return;

    const auto config = m_settings.getProvider(*service);
    AnalysisModels::instance().fetchModels(*service, config.apiKey, config.modelListUrl,
        [this, providerId](QStringList models) {
            auto *svc = AppService::findById(providerId);
            if (svc && !models.isEmpty()) {
                m_settings.setModels(*svc, models);
                saveSettings();
                emit settingsChanged();
            }
            emit modelsLoaded(providerId, models);
        });
}

void AppViewModel::testProvider(const QString &providerId)
{
    auto *service = AppService::findById(providerId);
    if (!service) return;

    const auto config = m_settings.getProvider(*service);
    AnalysisModels::instance().testModel(*service, config.apiKey, config.model,
        [this, providerId](QString error) {
            if (error.isEmpty()) {
                m_settings.providerStates[providerId] = QStringLiteral("ok");
            } else {
                m_settings.providerStates[providerId] = QStringLiteral("error");
            }
            saveSettings();
            emit settingsChanged();
            emit modelTestResult(providerId, error);
        });
}

// =============================================================================
// Agent CRUD
// =============================================================================

QVariantList AppViewModel::getAgents()
{
    QVariantList list;
    for (const auto &agent : m_settings.agents)
        list.append(agentToVariant(agent));
    return list;
}

QVariantMap AppViewModel::getAgent(const QString &id)
{
    const auto *agent = m_settings.getAgentById(id);
    if (!agent) return {};
    return agentToVariant(*agent);
}

void AppViewModel::saveAgent(const QVariantMap &data)
{
    Agent agent = variantToAgent(data);
    bool found = false;
    for (int i = 0; i < m_settings.agents.size(); ++i) {
        if (m_settings.agents[i].id == agent.id) {
            m_settings.agents[i] = agent;
            found = true;
            break;
        }
    }
    if (!found)
        m_settings.agents.append(agent);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::deleteAgent(const QString &id)
{
    m_settings.agents.removeIf([&](const Agent &a) { return a.id == id; });
    saveSettings();
    emit settingsChanged();
}

// =============================================================================
// Flock CRUD
// =============================================================================

QVariantList AppViewModel::getFlocks()
{
    QVariantList list;
    for (const auto &flock : m_settings.flocks)
        list.append(flockToVariant(flock));
    return list;
}

QVariantMap AppViewModel::getFlock(const QString &id)
{
    const auto *flock = m_settings.getFlockById(id);
    if (!flock) return {};
    return flockToVariant(*flock);
}

void AppViewModel::saveFlock(const QVariantMap &data)
{
    Flock flock = variantToFlock(data);
    bool found = false;
    for (int i = 0; i < m_settings.flocks.size(); ++i) {
        if (m_settings.flocks[i].id == flock.id) {
            m_settings.flocks[i] = flock;
            found = true;
            break;
        }
    }
    if (!found)
        m_settings.flocks.append(flock);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::deleteFlock(const QString &id)
{
    m_settings.flocks.removeIf([&](const Flock &f) { return f.id == id; });
    saveSettings();
    emit settingsChanged();
}

// =============================================================================
// Swarm CRUD
// =============================================================================

QVariantList AppViewModel::getSwarms()
{
    QVariantList list;
    for (const auto &swarm : m_settings.swarms)
        list.append(swarmToVariant(swarm));
    return list;
}

QVariantMap AppViewModel::getSwarm(const QString &id)
{
    const auto *swarm = m_settings.getSwarmById(id);
    if (!swarm) return {};
    return swarmToVariant(*swarm);
}

void AppViewModel::saveSwarm(const QVariantMap &data)
{
    Swarm swarm = variantToSwarm(data);
    bool found = false;
    for (int i = 0; i < m_settings.swarms.size(); ++i) {
        if (m_settings.swarms[i].id == swarm.id) {
            m_settings.swarms[i] = swarm;
            found = true;
            break;
        }
    }
    if (!found)
        m_settings.swarms.append(swarm);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::deleteSwarm(const QString &id)
{
    m_settings.swarms.removeIf([&](const Swarm &s) { return s.id == id; });
    saveSettings();
    emit settingsChanged();
}

// =============================================================================
// Parameters CRUD
// =============================================================================

QVariantList AppViewModel::getParameters()
{
    QVariantList list;
    for (const auto &p : m_settings.parameters)
        list.append(parametersToVariant(p));
    return list;
}

QVariantMap AppViewModel::getParametersById(const QString &id)
{
    const auto *p = m_settings.getParametersById(id);
    if (!p) return {};
    return parametersToVariant(*p);
}

void AppViewModel::saveParameters(const QVariantMap &data)
{
    Parameters params = variantToParameters(data);
    bool found = false;
    for (int i = 0; i < m_settings.parameters.size(); ++i) {
        if (m_settings.parameters[i].id == params.id) {
            m_settings.parameters[i] = params;
            found = true;
            break;
        }
    }
    if (!found)
        m_settings.parameters.append(params);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::deleteParameters(const QString &id)
{
    m_settings.parameters.removeIf([&](const Parameters &p) { return p.id == id; });
    saveSettings();
    emit settingsChanged();
}

// =============================================================================
// System Prompts CRUD
// =============================================================================

QVariantList AppViewModel::getSystemPrompts()
{
    QVariantList list;
    for (const auto &sp : m_settings.systemPrompts)
        list.append(systemPromptToVariant(sp));
    return list;
}

QVariantMap AppViewModel::getSystemPrompt(const QString &id)
{
    const auto *sp = m_settings.getSystemPromptById(id);
    if (!sp) return {};
    return systemPromptToVariant(*sp);
}

void AppViewModel::saveSystemPrompt(const QVariantMap &data)
{
    SystemPrompt sp = variantToSystemPrompt(data);
    bool found = false;
    for (int i = 0; i < m_settings.systemPrompts.size(); ++i) {
        if (m_settings.systemPrompts[i].id == sp.id) {
            m_settings.systemPrompts[i] = sp;
            found = true;
            break;
        }
    }
    if (!found)
        m_settings.systemPrompts.append(sp);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::deleteSystemPrompt(const QString &id)
{
    m_settings.systemPrompts.removeIf([&](const SystemPrompt &sp) { return sp.id == id; });
    saveSettings();
    emit settingsChanged();
}

// =============================================================================
// Prompts CRUD
// =============================================================================

QVariantList AppViewModel::getPrompts()
{
    QVariantList list;
    for (const auto &p : m_settings.prompts)
        list.append(promptToVariant(p));
    return list;
}

void AppViewModel::savePrompt(const QVariantMap &data)
{
    Prompt p = variantToPrompt(data);
    bool found = false;
    for (int i = 0; i < m_settings.prompts.size(); ++i) {
        if (m_settings.prompts[i].id == p.id) {
            m_settings.prompts[i] = p;
            found = true;
            break;
        }
    }
    if (!found)
        m_settings.prompts.append(p);
    saveSettings();
    emit settingsChanged();
}

void AppViewModel::deletePrompt(const QString &id)
{
    m_settings.prompts.removeIf([&](const Prompt &p) { return p.id == id; });
    saveSettings();
    emit settingsChanged();
}

// =============================================================================
// Endpoints
// =============================================================================

QVariantList AppViewModel::getEndpoints(const QString &providerId)
{
    QVariantList list;
    auto *service = AppService::findById(providerId);
    if (!service) return list;

    const auto endpoints = m_settings.getEndpointsForProvider(*service);
    for (const auto &ep : endpoints)
        list.append(endpointToVariant(ep));
    return list;
}

void AppViewModel::saveEndpoints(const QString &providerId, const QVariantList &endpoints)
{
    QList<Endpoint> epList;
    for (const auto &v : endpoints) {
        const auto map = v.toMap();
        Endpoint ep;
        ep.id = map.value("id").toString();
        if (ep.id.isEmpty())
            ep.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        ep.name = map.value("name").toString();
        ep.url = map.value("url").toString();
        ep.isDefault = map.value("isDefault").toBool();
        epList.append(ep);
    }
    m_settings.endpoints[providerId] = epList;
    saveSettings();
    emit settingsChanged();
}

// =============================================================================
// Report Generation
// =============================================================================

void AppViewModel::generateReports(const QString &title, const QString &prompt,
                                    const QStringList &agentIds, const QStringList &swarmIds,
                                    const QStringList &directModelIds)
{
    // Clear previous state
    m_reportResults.clear();
    m_reportAgents.clear();
    m_pendingTasks.clear();
    m_activeReportTasks = 0;
    m_reportsStopped = false;
    m_reportProgress = 0;

    // Create a new report ID
    m_currentReportId = QUuid::createUuid().toString(QUuid::WithoutBraces);
    ApiTracer::instance().setCurrentReportId(m_currentReportId);

    // Build tasks from agent IDs
    for (const auto &agentId : agentIds) {
        const auto *agent = m_settings.getAgentById(agentId);
        if (!agent) continue;

        QString effectiveApiKey = m_settings.getEffectiveApiKeyForAgent(*agent);
        QString effectiveModel = m_settings.getEffectiveModelForAgent(*agent);
        AgentParameters params = m_settings.resolveAgentParameters(*agent);

        auto *service = AppService::findById(agent->providerId);
        if (!service) continue;

        QString customBaseUrl = m_settings.getEffectiveEndpointUrlForAgent(*agent);

        ReportAgent ra(agentId, agent->name, agent->providerId, effectiveModel, ReportStatus::Pending);
        m_reportAgents.append(ra);

        ReportTask task;
        task.resultId = agentId;
        task.agent = ra;
        task.prompt = prompt;
        task.params = params;
        task.params.systemPrompt = params.systemPrompt;
        task.customBaseUrl = customBaseUrl;

        // Store the API key into params for the execution step
        // (we pass it separately to analyzeWithAgent)
        m_pendingTasks.append(task);
    }

    // Build tasks from swarm IDs - expand swarms into their members
    for (const auto &swarmId : swarmIds) {
        const auto *swarm = m_settings.getSwarmById(swarmId);
        if (!swarm) continue;

        // Resolve swarm-level parameters
        auto swarmParams = m_settings.mergeParameters(swarm->paramsIds);

        // Resolve swarm system prompt
        QString swarmSystemPrompt;
        if (swarm->systemPromptId.has_value()) {
            const auto *sp = m_settings.getSystemPromptById(*swarm->systemPromptId);
            if (sp) swarmSystemPrompt = sp->prompt;
        }

        for (const auto &member : swarm->members) {
            auto *service = AppService::findById(member.providerId);
            if (!service) continue;

            QString syntheticId = QStringLiteral("swarm:%1:%2").arg(member.providerId, member.model);
            QString displayName = QStringLiteral("%1 / %2").arg(service->displayName, member.model);

            AgentParameters params = swarmParams.value_or(AgentParameters{});
            if (!swarmSystemPrompt.isEmpty())
                params.systemPrompt = swarmSystemPrompt;

            ReportAgent ra(syntheticId, displayName, member.providerId, member.model, ReportStatus::Pending);
            m_reportAgents.append(ra);

            ReportTask task;
            task.resultId = syntheticId;
            task.agent = ra;
            task.prompt = prompt;
            task.params = params;
            task.customBaseUrl = m_settings.getEffectiveEndpointUrl(*service);
            m_pendingTasks.append(task);
        }
    }

    // Build tasks from direct model IDs (format: "swarm:providerId:model")
    for (const auto &modelId : directModelIds) {
        QString stripped = modelId;
        stripped.remove(0, QStringLiteral("swarm:").size());
        int colonPos = stripped.indexOf(':');
        if (colonPos < 0) continue;

        QString providerId = stripped.left(colonPos);
        QString model = stripped.mid(colonPos + 1);
        auto *service = AppService::findById(providerId);
        if (!service) continue;

        // Skip if already covered by a swarm
        bool alreadyPresent = false;
        for (const auto &existing : m_reportAgents) {
            if (existing.id == modelId) { alreadyPresent = true; break; }
        }
        if (alreadyPresent) continue;

        QString displayName = QStringLiteral("%1 / %2").arg(service->displayName, model);
        AgentParameters params;

        ReportAgent ra(modelId, displayName, providerId, model, ReportStatus::Pending);
        m_reportAgents.append(ra);

        ReportTask task;
        task.resultId = modelId;
        task.agent = ra;
        task.prompt = prompt;
        task.params = params;
        task.customBaseUrl = m_settings.getEffectiveEndpointUrl(*service);
        m_pendingTasks.append(task);
    }

    // Set up progress tracking
    m_reportTotal = m_pendingTasks.size();
    m_showReportProgress = true;
    emit reportStateChanged();

    // Save the report shell to storage
    StoredReport stored;
    stored.id = m_currentReportId;
    stored.title = title.isEmpty() ? QStringLiteral("AI Report") : title;
    stored.prompt = prompt;
    stored.timestamp = QDateTime::currentDateTimeUtc();
    ReportStorage::instance().save(stored);

    // Launch concurrent tasks
    executeNextReportTask();
}

void AppViewModel::executeNextReportTask()
{
    while (m_activeReportTasks < MAX_CONCURRENT_REPORTS && !m_pendingTasks.isEmpty() && !m_reportsStopped) {
        ReportTask task = m_pendingTasks.takeFirst();
        m_activeReportTasks++;

        // Mark agent as running
        for (auto &ra : m_reportAgents) {
            if (ra.id == task.resultId) {
                ra.status = ReportStatus::Running;
                break;
            }
        }

        auto *service = AppService::findById(task.agent.providerId);
        if (!service) {
            AnalysisResponse errResponse(task.agent.providerId);
            errResponse.error = QStringLiteral("Provider not found: %1").arg(task.agent.providerId);
            onReportTaskComplete(task.resultId, errResponse);
            continue;
        }

        QString apiKey = m_settings.getApiKey(*service);
        QString model = task.agent.model;

        AnalysisProviders::instance().analyzeWithAgent(
            service->id, apiKey, model, task.prompt,
            task.params, task.agent.agentName,
            task.customBaseUrl,
            [this, taskId = task.resultId](AnalysisResponse response) {
                // This callback may arrive on any thread; use QMetaObject::invokeMethod
                // to marshal to the main thread
                QMetaObject::invokeMethod(this, [this, taskId, response = std::move(response)]() {
                    onReportTaskComplete(taskId, response);
                }, Qt::QueuedConnection);
            });
    }
}

void AppViewModel::onReportTaskComplete(const QString &taskId, const AnalysisResponse &response)
{
    m_activeReportTasks--;
    m_reportProgress++;

    // Store result
    m_reportResults[taskId] = response;

    // Update agent status
    for (auto &ra : m_reportAgents) {
        if (ra.id == taskId) {
            ra.status = response.isSuccess() ? ReportStatus::Success : ReportStatus::Error;
            break;
        }
    }

    // Persist result to stored report
    auto stored = ReportStorage::instance().load(m_currentReportId);
    if (stored.has_value()) {
        StoredAnalysisResult sar;
        sar.id = taskId;
        sar.providerId = response.serviceId;
        // Find model from report agents
        for (const auto &ra : m_reportAgents) {
            if (ra.id == taskId) { sar.model = ra.model; break; }
        }
        sar.agentName = response.agentName;
        sar.analysis = response.analysis;
        sar.error = response.error;
        if (response.tokenUsage.has_value()) {
            sar.inputTokens = response.tokenUsage->inputTokens;
            sar.outputTokens = response.tokenUsage->outputTokens;
            sar.apiCost = response.tokenUsage->apiCost;
        }
        sar.citations = response.citations;
        sar.rawUsageJson = response.rawUsageJson;
        stored->results.append(sar);
        ReportStorage::instance().save(*stored);
    }

    // Update usage stats
    if (response.tokenUsage.has_value()) {
        updateUsageStats(response.serviceId,
                         // find model from report agents
                         [&]() -> QString {
                             for (const auto &ra : m_reportAgents)
                                 if (ra.id == taskId) return ra.model;
                             return {};
                         }(),
                         response.tokenUsage->inputTokens,
                         response.tokenUsage->outputTokens);
    }

    emit reportStateChanged();
    emit reportResultReady(taskId, analysisResponseToVariant(response));

    // Check if all done
    if (m_reportProgress >= m_reportTotal) {
        m_showReportProgress = false;
        ApiTracer::instance().setCurrentReportId(QString());
        emit reportStateChanged();
    } else {
        // Launch more tasks
        executeNextReportTask();
    }
}

void AppViewModel::stopReports()
{
    m_reportsStopped = true;
    m_pendingTasks.clear();

    // Mark all pending agents as stopped
    for (auto &ra : m_reportAgents) {
        if (ra.status == ReportStatus::Pending)
            ra.status = ReportStatus::Stopped;
    }

    // Wait for active tasks to finish naturally, then clean up
    if (m_activeReportTasks == 0) {
        m_showReportProgress = false;
        ApiTracer::instance().setCurrentReportId(QString());
    }
    emit reportStateChanged();
}

QVariantMap AppViewModel::getReportResult(const QString &agentId)
{
    auto it = m_reportResults.find(agentId);
    if (it == m_reportResults.end()) return {};
    return analysisResponseToVariant(it.value());
}

QVariantList AppViewModel::getReportAgents()
{
    QVariantList list;
    for (const auto &ra : m_reportAgents)
        list.append(reportAgentToVariant(ra));
    return list;
}

// =============================================================================
// Report History
// =============================================================================

QVariantList AppViewModel::getReportHistory()
{
    QVariantList list;
    const auto reports = ReportStorage::instance().loadAll();
    for (const auto &report : reports)
        list.append(storedReportToVariant(report));
    return list;
}

QVariantMap AppViewModel::loadReport(const QString &id)
{
    auto report = ReportStorage::instance().load(id);
    if (!report.has_value()) return {};
    return storedReportToVariant(*report);
}

void AppViewModel::deleteReport(const QString &id)
{
    ReportStorage::instance().deleteReport(id);
}

void AppViewModel::deleteAllReports()
{
    ReportStorage::instance().deleteAll();
}

int AppViewModel::reportCount()
{
    return ReportStorage::instance().count();
}

// =============================================================================
// Chat
// =============================================================================

void AppViewModel::sendChatMessage(const QString &providerId, const QString &model,
                                    const QVariantList &messages, const QVariantMap &params)
{
    auto *service = AppService::findById(providerId);
    if (!service) {
        emit chatStreamError(QStringLiteral("Provider not found: %1").arg(providerId));
        return;
    }

    QString apiKey = m_settings.getApiKey(*service);
    QString customBaseUrl = m_settings.getEffectiveEndpointUrl(*service);
    QList<ChatMessage> msgList = variantToMessages(messages);
    ChatParameters chatParams = variantToChatParameters(params);

    AnalysisChat::instance().sendChatMessage(*service, apiKey, model, msgList, chatParams, customBaseUrl,
        [this](QString response, QString error) {
            QMetaObject::invokeMethod(this, [this, response, error]() {
                if (!error.isEmpty()) {
                    emit chatStreamError(error);
                } else {
                    emit chatResponse(response);
                }
            }, Qt::QueuedConnection);
        });
}

void AppViewModel::sendChatStream(const QString &providerId, const QString &model,
                                   const QVariantList &messages, const QVariantMap &params)
{
    auto *service = AppService::findById(providerId);
    if (!service) {
        emit chatStreamError(QStringLiteral("Provider not found: %1").arg(providerId));
        return;
    }

    QString apiKey = m_settings.getApiKey(*service);
    QString customBaseUrl = m_settings.getEffectiveEndpointUrl(*service);
    QList<ChatMessage> msgList = variantToMessages(messages);
    ChatParameters chatParams = variantToChatParameters(params);

    auto &streaming = AnalysisStreaming::instance();

    // Disconnect any previous connections
    disconnect(&streaming, nullptr, this, nullptr);

    // Connect streaming signals
    connect(&streaming, &AnalysisStreaming::textChunk,
            this, &AppViewModel::chatStreamChunk);
    connect(&streaming, &AnalysisStreaming::streamFinished,
            this, &AppViewModel::chatStreamFinished);
    connect(&streaming, &AnalysisStreaming::streamError,
            this, &AppViewModel::chatStreamError);

    streaming.streamChat(*service, apiKey, model, msgList, chatParams, customBaseUrl);
}

void AppViewModel::stopChatStream()
{
    // AnalysisStreaming handles stop internally when a new stream is started
    // or we can disconnect signals to stop processing
    auto &streaming = AnalysisStreaming::instance();
    disconnect(&streaming, nullptr, this, nullptr);
}

// =============================================================================
// Chat History
// =============================================================================

void AppViewModel::saveChatSession(const QVariantMap &session)
{
    ChatSession cs;
    cs.id = session.value("id").toString();
    if (cs.id.isEmpty())
        cs.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    cs.providerId = session.value("providerId").toString();
    cs.model = session.value("model").toString();
    cs.messages = variantToMessages(session.value("messages").toList());
    cs.parameters = variantToChatParameters(session.value("parameters").toMap());
    cs.createdAt = QDateTime::fromMSecsSinceEpoch(
        session.value("createdAt", QDateTime::currentDateTimeUtc().toMSecsSinceEpoch()).toLongLong());
    cs.updatedAt = QDateTime::currentDateTimeUtc();
    ChatHistoryManager::instance().save(cs);
}

QVariantList AppViewModel::getChatHistory()
{
    QVariantList list;
    const auto sessions = ChatHistoryManager::instance().loadAll();
    for (const auto &s : sessions)
        list.append(chatSessionToVariant(s));
    return list;
}

QVariantMap AppViewModel::loadChatSession(const QString &id)
{
    auto session = ChatHistoryManager::instance().load(id);
    if (!session.has_value()) return {};
    return chatSessionToVariant(*session);
}

void AppViewModel::deleteChatSession(const QString &id)
{
    ChatHistoryManager::instance().deleteSession(id);
}

void AppViewModel::deleteAllChatSessions()
{
    ChatHistoryManager::instance().deleteAll();
}

QVariantList AppViewModel::searchChatHistory(const QString &query)
{
    QVariantList list;
    const auto sessions = ChatHistoryManager::instance().search(query);
    for (const auto &s : sessions)
        list.append(chatSessionToVariant(s));
    return list;
}

// =============================================================================
// Model Search
// =============================================================================

void AppViewModel::searchModels(const QString &query)
{
    QVariantList results;
    const QString lowerQuery = query.toLower();

    for (const auto &service : AppService::entries) {
        if (!m_settings.isProviderActive(service)) continue;

        const auto models = m_settings.getModels(service);
        for (const auto &model : models) {
            if (model.toLower().contains(lowerQuery)) {
                QVariantMap entry;
                entry["providerId"] = service.id;
                entry["providerName"] = service.displayName;
                entry["model"] = model;
                results.append(entry);
            }
        }
    }
    emit modelSearchResults(results);
}

// =============================================================================
// Usage Stats
// =============================================================================

void AppViewModel::updateUsageStats(const QString &providerId, const QString &model,
                                     int inputTokens, int outputTokens)
{
    QString key = providerId + QStringLiteral("::") + model;
    auto it = m_usageStats.find(key);
    if (it != m_usageStats.end()) {
        it->callCount++;
        it->inputTokens += inputTokens;
        it->outputTokens += outputTokens;
    } else {
        UsageStats us;
        us.providerId = providerId;
        us.model = model;
        us.callCount = 1;
        us.inputTokens = inputTokens;
        us.outputTokens = outputTokens;
        m_usageStats[key] = us;
    }
    saveUsageStats();
    emit usageStatsChanged();
}

void AppViewModel::clearUsageStats()
{
    m_usageStats.clear();
    saveUsageStats();
    emit usageStatsChanged();
}

// =============================================================================
// Prompt History
// =============================================================================

QVariantList AppViewModel::getPromptHistory()
{
    QVariantList list;
    for (const auto &entry : m_promptHistory) {
        QVariantMap map;
        map["id"] = entry.id;
        map["timestamp"] = entry.timestamp.toMSecsSinceEpoch();
        map["title"] = entry.title;
        map["prompt"] = entry.prompt;
        list.append(map);
    }
    return list;
}

void AppViewModel::addPromptHistoryEntry(const QString &title, const QString &prompt)
{
    PromptHistoryEntry entry;
    entry.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    entry.timestamp = QDateTime::currentDateTimeUtc();
    entry.title = title;
    entry.prompt = prompt;
    m_promptHistory.prepend(entry);

    // Keep at most 100 entries
    while (m_promptHistory.size() > 100)
        m_promptHistory.removeLast();

    savePromptHistory();
}

void AppViewModel::clearPromptHistory()
{
    m_promptHistory.clear();
    savePromptHistory();
}

// =============================================================================
// Export/Import
// =============================================================================

void AppViewModel::exportSettings()
{
    QString path = QFileDialog::getSaveFileName(nullptr,
        QStringLiteral("Export Settings"), QStringLiteral("ai-settings.json"),
        QStringLiteral("JSON Files (*.json)"));
    if (path.isEmpty()) {
        emit exportCompleted(false, QStringLiteral("Export cancelled"));
        return;
    }

    QJsonObject root;
    root["version"] = 21;
    root["settings"] = m_settings.toJson();
    root["generalSettings"] = m_generalSettings.toJson();

    QFile file(path);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QJsonDocument(root).toJson(QJsonDocument::Indented));
        file.close();
        emit exportCompleted(true, QStringLiteral("Settings exported to %1").arg(path));
    } else {
        emit exportCompleted(false, QStringLiteral("Failed to write to %1").arg(path));
    }
}

void AppViewModel::importSettings()
{
    QString path = QFileDialog::getOpenFileName(nullptr,
        QStringLiteral("Import Settings"), QString(),
        QStringLiteral("JSON Files (*.json)"));
    if (path.isEmpty()) {
        emit importCompleted(false, QStringLiteral("Import cancelled"));
        return;
    }

    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        emit importCompleted(false, QStringLiteral("Failed to read %1").arg(path));
        return;
    }

    QJsonParseError parseError;
    auto doc = QJsonDocument::fromJson(file.readAll(), &parseError);
    file.close();

    if (parseError.error != QJsonParseError::NoError) {
        emit importCompleted(false, QStringLiteral("Invalid JSON: %1").arg(parseError.errorString()));
        return;
    }

    QJsonObject root = doc.object();
    int version = root["version"].toInt(0);
    if (version < 11 || version > 21) {
        emit importCompleted(false, QStringLiteral("Unsupported settings version: %1 (supported: 11-21)").arg(version));
        return;
    }

    if (root.contains("settings"))
        m_settings = Settings::fromJson(root["settings"].toObject());
    if (root.contains("generalSettings"))
        m_generalSettings = GeneralSettings::fromJson(root["generalSettings"].toObject());

    saveSettings();
    emit settingsChanged();
    emit importCompleted(true, QStringLiteral("Settings imported from %1").arg(path));
}

void AppViewModel::exportApiKeys()
{
    QString path = QFileDialog::getSaveFileName(nullptr,
        QStringLiteral("Export API Keys"), QStringLiteral("ai-api-keys.json"),
        QStringLiteral("JSON Files (*.json)"));
    if (path.isEmpty()) {
        emit exportCompleted(false, QStringLiteral("Export cancelled"));
        return;
    }

    QJsonObject keys;
    for (const auto &service : AppService::entries) {
        QString apiKey = m_settings.getApiKey(service);
        if (!apiKey.isEmpty())
            keys[service.id] = apiKey;
    }
    if (!m_generalSettings.huggingFaceApiKey.isEmpty())
        keys["huggingface"] = m_generalSettings.huggingFaceApiKey;
    if (!m_generalSettings.openRouterApiKey.isEmpty())
        keys["openrouter_global"] = m_generalSettings.openRouterApiKey;

    QJsonObject root;
    root["version"] = 1;
    root["apiKeys"] = keys;

    QFile file(path);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QJsonDocument(root).toJson(QJsonDocument::Indented));
        file.close();
        emit exportCompleted(true, QStringLiteral("API keys exported to %1").arg(path));
    } else {
        emit exportCompleted(false, QStringLiteral("Failed to write to %1").arg(path));
    }
}

void AppViewModel::importApiKeys()
{
    QString path = QFileDialog::getOpenFileName(nullptr,
        QStringLiteral("Import API Keys"), QString(),
        QStringLiteral("JSON Files (*.json)"));
    if (path.isEmpty()) {
        emit importCompleted(false, QStringLiteral("Import cancelled"));
        return;
    }

    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        emit importCompleted(false, QStringLiteral("Failed to read %1").arg(path));
        return;
    }

    auto doc = QJsonDocument::fromJson(file.readAll());
    file.close();

    QJsonObject root = doc.object();
    QJsonObject keys = root["apiKeys"].toObject();

    int imported = 0;
    for (auto it = keys.begin(); it != keys.end(); ++it) {
        QString key = it.value().toString();
        if (key.isEmpty()) continue;

        if (it.key() == "huggingface") {
            m_generalSettings.huggingFaceApiKey = key;
        } else if (it.key() == "openrouter_global") {
            m_generalSettings.openRouterApiKey = key;
        } else {
            auto *service = AppService::findById(it.key());
            if (service) {
                m_settings.setApiKey(*service, key);
                imported++;
            }
        }
    }

    saveSettings();
    emit settingsChanged();
    emit importCompleted(true, QStringLiteral("Imported %1 API keys").arg(imported));
}

// =============================================================================
// Tracing
// =============================================================================

bool AppViewModel::isTracingEnabled()
{
    return ApiTracer::instance().isTracingEnabled();
}

void AppViewModel::setTracingEnabled(bool enabled)
{
    ApiTracer::instance().setTracingEnabled(enabled);
}

QVariantList AppViewModel::getTraceFiles()
{
    QVariantList list;
    const auto files = ApiTracer::instance().getTraceFiles();
    for (const auto &f : files)
        list.append(traceFileInfoToVariant(f));
    return list;
}

QVariantMap AppViewModel::readTrace(const QString &filename)
{
    auto trace = ApiTracer::instance().readTrace(filename);
    if (!trace.has_value()) return {};

    QVariantMap map;
    map["timestamp"] = trace->timestamp.toString(Qt::ISODate);
    map["hostname"] = trace->hostname;
    map["reportId"] = trace->reportId;
    map["model"] = trace->model;

    QVariantMap reqMap;
    reqMap["url"] = trace->request.url;
    reqMap["method"] = trace->request.method;
    reqMap["body"] = trace->request.body;
    QVariantMap reqHeaders;
    for (auto it = trace->request.headers.cbegin(); it != trace->request.headers.cend(); ++it)
        reqHeaders[it.key()] = it.value();
    reqMap["headers"] = reqHeaders;
    map["request"] = reqMap;

    QVariantMap respMap;
    respMap["statusCode"] = trace->response.statusCode;
    respMap["body"] = trace->response.body;
    QVariantMap respHeaders;
    for (auto it = trace->response.headers.cbegin(); it != trace->response.headers.cend(); ++it)
        respHeaders[it.key()] = it.value();
    respMap["headers"] = respHeaders;
    map["response"] = respMap;

    return map;
}

void AppViewModel::clearTraces()
{
    ApiTracer::instance().clearTraces();
}

// =============================================================================
// Housekeeping
// =============================================================================

QVariantMap AppViewModel::getStorageInfo()
{
    QVariantMap info;
    QString base = storagePath();

    info["reportCount"] = ReportStorage::instance().count();
    info["chatCount"] = ChatHistoryManager::instance().count();
    info["traceCount"] = ApiTracer::instance().getTraceCount();

    info["reportSize"] = directorySize(base + QStringLiteral("/reports"));
    info["chatSize"] = directorySize(base + QStringLiteral("/chat-history"));
    info["traceSize"] = directorySize(base + QStringLiteral("/trace"));

    qint64 totalSize = info["reportSize"].toLongLong()
                     + info["chatSize"].toLongLong()
                     + info["traceSize"].toLongLong();
    info["totalSize"] = totalSize;

    return info;
}

void AppViewModel::clearReportStorage()
{
    ReportStorage::instance().deleteAll();
}

void AppViewModel::clearChatStorage()
{
    ChatHistoryManager::instance().deleteAll();
}

void AppViewModel::clearAllStorage()
{
    ReportStorage::instance().deleteAll();
    ChatHistoryManager::instance().deleteAll();
    ApiTracer::instance().clearTraces();
}

// =============================================================================
// General Settings
// =============================================================================

QVariantMap AppViewModel::getGeneralSettings()
{
    QVariantMap map;
    map["userName"] = m_generalSettings.userName;
    map["developerMode"] = m_generalSettings.developerMode;
    map["huggingFaceApiKey"] = m_generalSettings.huggingFaceApiKey;
    map["openRouterApiKey"] = m_generalSettings.openRouterApiKey;
    map["defaultEmail"] = m_generalSettings.defaultEmail;
    return map;
}

void AppViewModel::saveGeneralSettings(const QVariantMap &data)
{
    m_generalSettings.userName = data.value("userName", "user").toString();
    m_generalSettings.developerMode = data.value("developerMode", true).toBool();
    m_generalSettings.huggingFaceApiKey = data.value("huggingFaceApiKey").toString();
    m_generalSettings.openRouterApiKey = data.value("openRouterApiKey").toString();
    m_generalSettings.defaultEmail = data.value("defaultEmail").toString();

    // Persist general settings to file
    QString path = storagePath() + QStringLiteral("/general-settings.json");
    QDir().mkpath(QFileInfo(path).path());
    QFile file(path);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QJsonDocument(m_generalSettings.toJson()).toJson());
        file.close();
    }
}

// =============================================================================
// Utilities
// =============================================================================

QString AppViewModel::copyToClipboard(const QString &text)
{
    auto *clipboard = QGuiApplication::clipboard();
    if (clipboard) {
        clipboard->setText(text);
        return QStringLiteral("Copied to clipboard");
    }
    return QStringLiteral("Clipboard not available");
}

void AppViewModel::openUrl(const QString &url)
{
    QDesktopServices::openUrl(QUrl(url));
}

void AppViewModel::openFile(const QString &path)
{
    QDesktopServices::openUrl(QUrl::fromLocalFile(path));
}

// =============================================================================
// Settings Persistence
// =============================================================================

void AppViewModel::saveSettings()
{
    QString path = storagePath() + QStringLiteral("/settings.json");
    QDir().mkpath(QFileInfo(path).path());
    QFile file(path);
    if (file.open(QIODevice::WriteOnly)) {
        QJsonObject root;
        root["version"] = 21;
        root["settings"] = m_settings.toJson();
        file.write(QJsonDocument(root).toJson());
        file.close();
    }
}

void AppViewModel::loadSettings()
{
    // Load general settings
    QString gsPath = storagePath() + QStringLiteral("/general-settings.json");
    QFile gsFile(gsPath);
    if (gsFile.open(QIODevice::ReadOnly)) {
        auto doc = QJsonDocument::fromJson(gsFile.readAll());
        gsFile.close();
        m_generalSettings = GeneralSettings::fromJson(doc.object());
    }

    // Load AI settings
    QString path = storagePath() + QStringLiteral("/settings.json");
    QFile file(path);
    if (file.open(QIODevice::ReadOnly)) {
        auto doc = QJsonDocument::fromJson(file.readAll());
        file.close();
        QJsonObject root = doc.object();
        m_settings = Settings::fromJson(root["settings"].toObject());
    } else {
        // First run: create default provider configs
        m_settings.providers = defaultProvidersMap();
    }
}

void AppViewModel::loadUsageStats()
{
    QString path = storagePath() + QStringLiteral("/usage-stats.json");
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return;

    auto doc = QJsonDocument::fromJson(file.readAll());
    file.close();

    QJsonArray arr = doc.array();
    for (const auto &v : arr) {
        UsageStats us = UsageStats::fromJson(v.toObject());
        m_usageStats[us.key()] = us;
    }
}

void AppViewModel::saveUsageStats()
{
    QString path = storagePath() + QStringLiteral("/usage-stats.json");
    QDir().mkpath(QFileInfo(path).path());

    QJsonArray arr;
    for (auto it = m_usageStats.cbegin(); it != m_usageStats.cend(); ++it)
        arr.append(it.value().toJson());

    QFile file(path);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QJsonDocument(arr).toJson());
        file.close();
    }
}

void AppViewModel::loadPromptHistory()
{
    QString path = storagePath() + QStringLiteral("/prompt-history.json");
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return;

    auto doc = QJsonDocument::fromJson(file.readAll());
    file.close();

    QJsonArray arr = doc.array();
    for (const auto &v : arr)
        m_promptHistory.append(PromptHistoryEntry::fromJson(v.toObject()));
}

void AppViewModel::savePromptHistory()
{
    QString path = storagePath() + QStringLiteral("/prompt-history.json");
    QDir().mkpath(QFileInfo(path).path());

    QJsonArray arr;
    for (const auto &entry : m_promptHistory)
        arr.append(entry.toJson());

    QFile file(path);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(QJsonDocument(arr).toJson());
        file.close();
    }
}

// =============================================================================
// Storage Path Helpers
// =============================================================================

QString AppViewModel::storagePath() const
{
    return QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
}

qint64 AppViewModel::directorySize(const QString &path) const
{
    qint64 size = 0;
    QDirIterator it(path, QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        it.next();
        size += it.fileInfo().size();
    }
    return size;
}

// =============================================================================
// QVariantMap Conversions: Struct -> QVariantMap (for QML)
// =============================================================================

QVariantMap AppViewModel::agentToVariant(const Agent &a) const
{
    QVariantMap map;
    map["id"] = a.id;
    map["name"] = a.name;
    map["providerId"] = a.providerId;
    map["model"] = a.model;
    map["apiKey"] = a.apiKey;
    if (a.endpointId.has_value())
        map["endpointId"] = *a.endpointId;
    map["paramsIds"] = QVariant::fromValue(a.paramsIds);
    if (a.systemPromptId.has_value())
        map["systemPromptId"] = *a.systemPromptId;

    // Resolved values for display convenience
    auto *service = AppService::findById(a.providerId);
    if (service) {
        map["providerName"] = service->displayName;
        map["effectiveApiKey"] = m_settings.getEffectiveApiKeyForAgent(a);
        map["effectiveModel"] = m_settings.getEffectiveModelForAgent(a);
    }
    return map;
}

QVariantMap AppViewModel::flockToVariant(const Flock &f) const
{
    QVariantMap map;
    map["id"] = f.id;
    map["name"] = f.name;
    map["agentIds"] = QVariant::fromValue(f.agentIds);
    map["paramsIds"] = QVariant::fromValue(f.paramsIds);
    if (f.systemPromptId.has_value())
        map["systemPromptId"] = *f.systemPromptId;
    map["agentCount"] = f.agentIds.size();
    return map;
}

QVariantMap AppViewModel::swarmToVariant(const Swarm &s) const
{
    QVariantMap map;
    map["id"] = s.id;
    map["name"] = s.name;
    map["paramsIds"] = QVariant::fromValue(s.paramsIds);
    if (s.systemPromptId.has_value())
        map["systemPromptId"] = *s.systemPromptId;

    QVariantList members;
    for (const auto &m : s.members) {
        QVariantMap mm;
        mm["providerId"] = m.providerId;
        mm["model"] = m.model;
        auto *svc = AppService::findById(m.providerId);
        if (svc) mm["providerName"] = svc->displayName;
        members.append(mm);
    }
    map["members"] = members;
    map["memberCount"] = s.members.size();
    return map;
}

QVariantMap AppViewModel::parametersToVariant(const Parameters &p) const
{
    QVariantMap map;
    map["id"] = p.id;
    map["name"] = p.name;
    if (p.temperature.has_value())
        map["temperature"] = static_cast<double>(*p.temperature);
    if (p.maxTokens.has_value())
        map["maxTokens"] = *p.maxTokens;
    if (p.topP.has_value())
        map["topP"] = static_cast<double>(*p.topP);
    if (p.topK.has_value())
        map["topK"] = *p.topK;
    if (p.frequencyPenalty.has_value())
        map["frequencyPenalty"] = static_cast<double>(*p.frequencyPenalty);
    if (p.presencePenalty.has_value())
        map["presencePenalty"] = static_cast<double>(*p.presencePenalty);
    if (p.systemPrompt.has_value())
        map["systemPrompt"] = *p.systemPrompt;
    if (p.stopSequences.has_value())
        map["stopSequences"] = QVariant::fromValue(*p.stopSequences);
    if (p.seed.has_value())
        map["seed"] = *p.seed;
    map["responseFormatJson"] = p.responseFormatJson;
    map["searchEnabled"] = p.searchEnabled;
    map["returnCitations"] = p.returnCitations;
    if (p.searchRecency.has_value())
        map["searchRecency"] = *p.searchRecency;
    return map;
}

QVariantMap AppViewModel::systemPromptToVariant(const SystemPrompt &sp) const
{
    QVariantMap map;
    map["id"] = sp.id;
    map["name"] = sp.name;
    map["prompt"] = sp.prompt;
    return map;
}

QVariantMap AppViewModel::promptToVariant(const Prompt &p) const
{
    QVariantMap map;
    map["id"] = p.id;
    map["name"] = p.name;
    map["agentId"] = p.agentId;
    map["promptText"] = p.promptText;
    return map;
}

QVariantMap AppViewModel::endpointToVariant(const Endpoint &e) const
{
    QVariantMap map;
    map["id"] = e.id;
    map["name"] = e.name;
    map["url"] = e.url;
    map["isDefault"] = e.isDefault;
    return map;
}

QVariantMap AppViewModel::reportAgentToVariant(const ReportAgent &ra) const
{
    QVariantMap map;
    map["id"] = ra.id;
    map["agentId"] = ra.agentId;
    map["agentName"] = ra.agentName;
    map["providerId"] = ra.providerId;
    map["model"] = ra.model;
    map["status"] = reportStatusToString(ra.status);
    return map;
}

QVariantMap AppViewModel::analysisResponseToVariant(const AnalysisResponse &r) const
{
    QVariantMap map;
    map["id"] = r.id;
    map["serviceId"] = r.serviceId;
    if (r.analysis.has_value())
        map["analysis"] = *r.analysis;
    if (r.error.has_value())
        map["error"] = *r.error;
    map["isSuccess"] = r.isSuccess();

    if (r.tokenUsage.has_value()) {
        QVariantMap usage;
        usage["inputTokens"] = r.tokenUsage->inputTokens;
        usage["outputTokens"] = r.tokenUsage->outputTokens;
        usage["totalTokens"] = r.tokenUsage->totalTokens();
        if (r.tokenUsage->apiCost.has_value())
            usage["apiCost"] = *r.tokenUsage->apiCost;
        map["tokenUsage"] = usage;
    }

    if (r.agentName.has_value())
        map["agentName"] = *r.agentName;
    if (r.citations.has_value())
        map["citations"] = QVariant::fromValue(*r.citations);
    if (r.httpStatusCode.has_value())
        map["httpStatusCode"] = *r.httpStatusCode;

    return map;
}

QVariantMap AppViewModel::storedReportToVariant(const StoredReport &r) const
{
    QVariantMap map;
    map["id"] = r.id;
    map["title"] = r.title;
    map["prompt"] = r.prompt;
    map["timestamp"] = r.timestamp.toMSecsSinceEpoch();
    map["timestampStr"] = r.timestamp.toLocalTime().toString(QStringLiteral("yyyy-MM-dd hh:mm"));
    map["resultCount"] = r.results.size();

    QVariantList results;
    for (const auto &res : r.results) {
        QVariantMap rm;
        rm["id"] = res.id;
        rm["providerId"] = res.providerId;
        rm["model"] = res.model;
        if (res.agentName.has_value())
            rm["agentName"] = *res.agentName;
        if (res.analysis.has_value())
            rm["analysis"] = *res.analysis;
        if (res.error.has_value())
            rm["error"] = *res.error;
        rm["inputTokens"] = res.inputTokens;
        rm["outputTokens"] = res.outputTokens;
        if (res.apiCost.has_value())
            rm["apiCost"] = *res.apiCost;
        if (res.citations.has_value())
            rm["citations"] = QVariant::fromValue(*res.citations);
        results.append(rm);
    }
    map["results"] = results;
    return map;
}

QVariantMap AppViewModel::chatSessionToVariant(const ChatSession &s) const
{
    QVariantMap map;
    map["id"] = s.id;
    map["providerId"] = s.providerId;
    map["model"] = s.model;
    map["preview"] = s.preview();
    map["createdAt"] = s.createdAt.toMSecsSinceEpoch();
    map["updatedAt"] = s.updatedAt.toMSecsSinceEpoch();
    map["messageCount"] = s.messages.size();

    QVariantList msgs;
    for (const auto &msg : s.messages) {
        QVariantMap mm;
        mm["id"] = msg.id;
        mm["role"] = msg.role;
        mm["content"] = msg.content;
        mm["timestamp"] = msg.timestamp.toMSecsSinceEpoch();
        msgs.append(mm);
    }
    map["messages"] = msgs;

    QVariantMap params;
    params["systemPrompt"] = s.parameters.systemPrompt;
    if (s.parameters.temperature.has_value())
        params["temperature"] = static_cast<double>(*s.parameters.temperature);
    if (s.parameters.maxTokens.has_value())
        params["maxTokens"] = *s.parameters.maxTokens;
    map["parameters"] = params;

    // Resolve provider display name
    auto *service = AppService::findById(s.providerId);
    if (service)
        map["providerName"] = service->displayName;

    return map;
}

QVariantMap AppViewModel::traceFileInfoToVariant(const TraceFileInfo &t) const
{
    QVariantMap map;
    map["id"] = t.id;
    map["filename"] = t.filename;
    map["hostname"] = t.hostname;
    map["timestamp"] = t.timestamp.toString(Qt::ISODate);
    map["statusCode"] = t.statusCode;
    map["reportId"] = t.reportId;
    map["model"] = t.model;
    return map;
}

// =============================================================================
// QVariantMap Conversions: QVariantMap -> Struct (from QML)
// =============================================================================

Agent AppViewModel::variantToAgent(const QVariantMap &data) const
{
    Agent a;
    a.id = data.value("id").toString();
    if (a.id.isEmpty())
        a.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    a.name = data.value("name").toString();
    a.providerId = data.value("providerId").toString();
    a.model = data.value("model").toString();
    a.apiKey = data.value("apiKey").toString();
    if (data.contains("endpointId") && !data.value("endpointId").toString().isEmpty())
        a.endpointId = data.value("endpointId").toString();
    a.paramsIds = data.value("paramsIds").toStringList();
    if (data.contains("systemPromptId") && !data.value("systemPromptId").toString().isEmpty())
        a.systemPromptId = data.value("systemPromptId").toString();
    return a;
}

Flock AppViewModel::variantToFlock(const QVariantMap &data) const
{
    Flock f;
    f.id = data.value("id").toString();
    if (f.id.isEmpty())
        f.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    f.name = data.value("name").toString();
    f.agentIds = data.value("agentIds").toStringList();
    f.paramsIds = data.value("paramsIds").toStringList();
    if (data.contains("systemPromptId") && !data.value("systemPromptId").toString().isEmpty())
        f.systemPromptId = data.value("systemPromptId").toString();
    return f;
}

Swarm AppViewModel::variantToSwarm(const QVariantMap &data) const
{
    Swarm s;
    s.id = data.value("id").toString();
    if (s.id.isEmpty())
        s.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    s.name = data.value("name").toString();
    s.paramsIds = data.value("paramsIds").toStringList();
    if (data.contains("systemPromptId") && !data.value("systemPromptId").toString().isEmpty())
        s.systemPromptId = data.value("systemPromptId").toString();

    QVariantList membersList = data.value("members").toList();
    for (const auto &v : membersList) {
        QVariantMap mm = v.toMap();
        SwarmMember member;
        member.providerId = mm.value("providerId").toString();
        member.model = mm.value("model").toString();
        s.members.append(member);
    }
    return s;
}

Parameters AppViewModel::variantToParameters(const QVariantMap &data) const
{
    Parameters p;
    p.id = data.value("id").toString();
    if (p.id.isEmpty())
        p.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    p.name = data.value("name").toString();

    if (data.contains("temperature"))
        p.temperature = static_cast<float>(data.value("temperature").toDouble());
    if (data.contains("maxTokens"))
        p.maxTokens = data.value("maxTokens").toInt();
    if (data.contains("topP"))
        p.topP = static_cast<float>(data.value("topP").toDouble());
    if (data.contains("topK"))
        p.topK = data.value("topK").toInt();
    if (data.contains("frequencyPenalty"))
        p.frequencyPenalty = static_cast<float>(data.value("frequencyPenalty").toDouble());
    if (data.contains("presencePenalty"))
        p.presencePenalty = static_cast<float>(data.value("presencePenalty").toDouble());
    if (data.contains("systemPrompt"))
        p.systemPrompt = data.value("systemPrompt").toString();
    if (data.contains("stopSequences"))
        p.stopSequences = data.value("stopSequences").toStringList();
    if (data.contains("seed"))
        p.seed = data.value("seed").toInt();

    p.responseFormatJson = data.value("responseFormatJson", false).toBool();
    p.searchEnabled = data.value("searchEnabled", false).toBool();
    p.returnCitations = data.value("returnCitations", true).toBool();
    if (data.contains("searchRecency"))
        p.searchRecency = data.value("searchRecency").toString();
    return p;
}

SystemPrompt AppViewModel::variantToSystemPrompt(const QVariantMap &data) const
{
    SystemPrompt sp;
    sp.id = data.value("id").toString();
    if (sp.id.isEmpty())
        sp.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    sp.name = data.value("name").toString();
    sp.prompt = data.value("prompt").toString();
    return sp;
}

Prompt AppViewModel::variantToPrompt(const QVariantMap &data) const
{
    Prompt p;
    p.id = data.value("id").toString();
    if (p.id.isEmpty())
        p.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    p.name = data.value("name").toString();
    p.agentId = data.value("agentId").toString();
    p.promptText = data.value("promptText").toString();
    return p;
}

ChatParameters AppViewModel::variantToChatParameters(const QVariantMap &data) const
{
    ChatParameters cp;
    cp.systemPrompt = data.value("systemPrompt").toString();
    if (data.contains("temperature"))
        cp.temperature = static_cast<float>(data.value("temperature").toDouble());
    if (data.contains("maxTokens"))
        cp.maxTokens = data.value("maxTokens").toInt();
    if (data.contains("topP"))
        cp.topP = static_cast<float>(data.value("topP").toDouble());
    if (data.contains("topK"))
        cp.topK = data.value("topK").toInt();
    if (data.contains("frequencyPenalty"))
        cp.frequencyPenalty = static_cast<float>(data.value("frequencyPenalty").toDouble());
    if (data.contains("presencePenalty"))
        cp.presencePenalty = static_cast<float>(data.value("presencePenalty").toDouble());
    cp.searchEnabled = data.value("searchEnabled", false).toBool();
    cp.returnCitations = data.value("returnCitations", true).toBool();
    if (data.contains("searchRecency"))
        cp.searchRecency = data.value("searchRecency").toString();
    return cp;
}

QList<ChatMessage> AppViewModel::variantToMessages(const QVariantList &list) const
{
    QList<ChatMessage> messages;
    for (const auto &v : list) {
        QVariantMap map = v.toMap();
        ChatMessage msg;
        msg.id = map.value("id").toString();
        if (msg.id.isEmpty())
            msg.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        msg.role = map.value("role").toString();
        msg.content = map.value("content").toString();
        msg.timestamp = QDateTime::fromMSecsSinceEpoch(
            map.value("timestamp", QDateTime::currentDateTimeUtc().toMSecsSinceEpoch()).toLongLong());
        messages.append(msg);
    }
    return messages;
}
