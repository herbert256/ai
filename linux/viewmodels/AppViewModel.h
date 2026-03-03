// AppViewModel.h - Central QObject bridging C++ data layer and QML UI (Linux/Qt6 port)
// Manages all application state and exposes it via Q_PROPERTY and Q_INVOKABLE methods.

#pragma once

#include <QObject>
#include <QVariant>
#include <QVariantList>
#include <QVariantMap>
#include <QSet>
#include <QTimer>
#include <QJsonObject>
#include "viewmodels/SettingsModels.h"
#include "viewmodels/UiModels.h"
#include "helpers/SettingsPreferences.h"
#include "data/DataModels.h"
#include "data/AppService.h"
#include "data/ApiTracer.h"

// ---------------------------------------------------------------------------
// AppViewModel
// ---------------------------------------------------------------------------

class AppViewModel : public QObject {
    Q_OBJECT

    // Navigation
    Q_PROPERTY(QString currentSection READ currentSection WRITE setCurrentSection NOTIFY currentSectionChanged)

    // Loading state
    Q_PROPERTY(bool isLoading READ isLoading NOTIFY isLoadingChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorMessageChanged)

    // Provider/settings info for QML
    Q_PROPERTY(int providerCount READ providerCount NOTIFY settingsChanged)
    Q_PROPERTY(int agentCount READ agentCount NOTIFY settingsChanged)
    Q_PROPERTY(int flockCount READ flockCount NOTIFY settingsChanged)
    Q_PROPERTY(int swarmCount READ swarmCount NOTIFY settingsChanged)
    Q_PROPERTY(int activeProviderCount READ activeProviderCount NOTIFY settingsChanged)

    // Report state
    Q_PROPERTY(bool showReportProgress READ showReportProgress NOTIFY reportStateChanged)
    Q_PROPERTY(int reportProgress READ reportProgress NOTIFY reportStateChanged)
    Q_PROPERTY(int reportTotal READ reportTotal NOTIFY reportStateChanged)
    Q_PROPERTY(QString currentReportId READ currentReportId NOTIFY reportStateChanged)

    // Usage stats
    Q_PROPERTY(QVariantList usageStatsList READ usageStatsList NOTIFY usageStatsChanged)

public:
    explicit AppViewModel(QObject *parent = nullptr);

    // Bootstrap
    Q_INVOKABLE void bootstrap();

    // Navigation
    QString currentSection() const;
    void setCurrentSection(const QString &section);

    // Loading
    bool isLoading() const;
    QString errorMessage() const;

    // Counts
    int providerCount() const;
    int agentCount() const;
    int flockCount() const;
    int swarmCount() const;
    int activeProviderCount() const;

    // Report state
    bool showReportProgress() const;
    int reportProgress() const;
    int reportTotal() const;
    QString currentReportId() const;

    // Usage stats
    QVariantList usageStatsList() const;

    // ---- Settings access (Q_INVOKABLE for QML) ----

    // Provider management
    Q_INVOKABLE QVariantList getProviders();
    Q_INVOKABLE QVariantMap getProviderConfig(const QString &providerId);
    Q_INVOKABLE void setProviderApiKey(const QString &providerId, const QString &apiKey);
    Q_INVOKABLE void setProviderModel(const QString &providerId, const QString &model);
    Q_INVOKABLE void setProviderModels(const QString &providerId, const QStringList &models);
    Q_INVOKABLE void setProviderState(const QString &providerId, const QString &state);
    Q_INVOKABLE QStringList getProviderModels(const QString &providerId);
    Q_INVOKABLE void fetchModels(const QString &providerId);
    Q_INVOKABLE void testProvider(const QString &providerId);

    // Agent CRUD
    Q_INVOKABLE QVariantList getAgents();
    Q_INVOKABLE QVariantMap getAgent(const QString &id);
    Q_INVOKABLE void saveAgent(const QVariantMap &data);
    Q_INVOKABLE void deleteAgent(const QString &id);

    // Flock CRUD
    Q_INVOKABLE QVariantList getFlocks();
    Q_INVOKABLE QVariantMap getFlock(const QString &id);
    Q_INVOKABLE void saveFlock(const QVariantMap &data);
    Q_INVOKABLE void deleteFlock(const QString &id);

    // Swarm CRUD
    Q_INVOKABLE QVariantList getSwarms();
    Q_INVOKABLE QVariantMap getSwarm(const QString &id);
    Q_INVOKABLE void saveSwarm(const QVariantMap &data);
    Q_INVOKABLE void deleteSwarm(const QString &id);

    // Parameters CRUD
    Q_INVOKABLE QVariantList getParameters();
    Q_INVOKABLE QVariantMap getParametersById(const QString &id);
    Q_INVOKABLE void saveParameters(const QVariantMap &data);
    Q_INVOKABLE void deleteParameters(const QString &id);

    // System Prompts CRUD
    Q_INVOKABLE QVariantList getSystemPrompts();
    Q_INVOKABLE QVariantMap getSystemPrompt(const QString &id);
    Q_INVOKABLE void saveSystemPrompt(const QVariantMap &data);
    Q_INVOKABLE void deleteSystemPrompt(const QString &id);

    // Prompts CRUD
    Q_INVOKABLE QVariantList getPrompts();
    Q_INVOKABLE void savePrompt(const QVariantMap &data);
    Q_INVOKABLE void deletePrompt(const QString &id);

    // Endpoints
    Q_INVOKABLE QVariantList getEndpoints(const QString &providerId);
    Q_INVOKABLE void saveEndpoints(const QString &providerId, const QVariantList &endpoints);

    // Report generation
    Q_INVOKABLE void generateReports(const QString &title, const QString &prompt,
                                      const QStringList &agentIds, const QStringList &swarmIds,
                                      const QStringList &directModelIds);
    Q_INVOKABLE void stopReports();
    Q_INVOKABLE QVariantMap getReportResult(const QString &agentId);
    Q_INVOKABLE QVariantList getReportAgents();

    // Report history
    Q_INVOKABLE QVariantList getReportHistory();
    Q_INVOKABLE QVariantMap loadReport(const QString &id);
    Q_INVOKABLE void deleteReport(const QString &id);
    Q_INVOKABLE void deleteAllReports();
    Q_INVOKABLE int reportCount();

    // Chat
    Q_INVOKABLE void sendChatMessage(const QString &providerId, const QString &model,
                                      const QVariantList &messages, const QVariantMap &params);
    Q_INVOKABLE void sendChatStream(const QString &providerId, const QString &model,
                                     const QVariantList &messages, const QVariantMap &params);
    Q_INVOKABLE void stopChatStream();

    // Chat history
    Q_INVOKABLE void saveChatSession(const QVariantMap &session);
    Q_INVOKABLE QVariantList getChatHistory();
    Q_INVOKABLE QVariantMap loadChatSession(const QString &id);
    Q_INVOKABLE void deleteChatSession(const QString &id);
    Q_INVOKABLE void deleteAllChatSessions();
    Q_INVOKABLE QVariantList searchChatHistory(const QString &query);

    // Model search
    Q_INVOKABLE void searchModels(const QString &query);

    // Usage stats
    Q_INVOKABLE void updateUsageStats(const QString &providerId, const QString &model,
                                       int inputTokens, int outputTokens);
    Q_INVOKABLE void clearUsageStats();

    // Prompt history
    Q_INVOKABLE QVariantList getPromptHistory();
    Q_INVOKABLE void addPromptHistoryEntry(const QString &title, const QString &prompt);
    Q_INVOKABLE void clearPromptHistory();

    // Export/Import
    Q_INVOKABLE void exportSettings();
    Q_INVOKABLE void importSettings();
    Q_INVOKABLE void exportApiKeys();
    Q_INVOKABLE void importApiKeys();

    // Tracing
    Q_INVOKABLE bool isTracingEnabled();
    Q_INVOKABLE void setTracingEnabled(bool enabled);
    Q_INVOKABLE QVariantList getTraceFiles();
    Q_INVOKABLE QVariantMap readTrace(const QString &filename);
    Q_INVOKABLE void clearTraces();

    // Housekeeping
    Q_INVOKABLE QVariantMap getStorageInfo();
    Q_INVOKABLE void clearReportStorage();
    Q_INVOKABLE void clearChatStorage();
    Q_INVOKABLE void clearAllStorage();

    // General settings
    Q_INVOKABLE QVariantMap getGeneralSettings();
    Q_INVOKABLE void saveGeneralSettings(const QVariantMap &data);

    // Utilities
    Q_INVOKABLE QString copyToClipboard(const QString &text);
    Q_INVOKABLE void openUrl(const QString &url);
    Q_INVOKABLE void openFile(const QString &path);

signals:
    void currentSectionChanged();
    void isLoadingChanged();
    void errorMessageChanged();
    void settingsChanged();
    void reportStateChanged();
    void usageStatsChanged();

    // Chat signals for QML
    void chatResponse(const QString &text);
    void chatStreamChunk(const QString &text);
    void chatStreamFinished();
    void chatStreamError(const QString &error);

    // Model signals
    void modelsLoaded(const QString &providerId, const QStringList &models);
    void modelTestResult(const QString &providerId, const QString &error);
    void modelSearchResults(const QVariantList &results);

    // Report signals
    void reportResultReady(const QString &agentId, const QVariantMap &result);

    // Import/Export signals
    void importCompleted(bool success, const QString &message);
    void exportCompleted(bool success, const QString &message);

private:
    QString m_currentSection = "hub";
    bool m_isLoading = false;
    QString m_errorMessage;

    Settings m_settings;
    GeneralSettings m_generalSettings;
    QMap<QString, UsageStats> m_usageStats;
    QList<PromptHistoryEntry> m_promptHistory;

    // Report state
    bool m_showReportProgress = false;
    int m_reportProgress = 0;
    int m_reportTotal = 0;
    QString m_currentReportId;
    QList<ReportAgent> m_reportAgents;
    QMap<QString, AnalysisResponse> m_reportResults;
    int m_activeReportTasks = 0;
    static constexpr int MAX_CONCURRENT_REPORTS = 4;

    // Pending report tasks
    struct ReportTask {
        QString resultId;
        ReportAgent agent;
        QString prompt;
        AgentParameters params;
        QString customBaseUrl;
    };
    QList<ReportTask> m_pendingTasks;
    bool m_reportsStopped = false;

    void saveSettings();
    void loadSettings();
    void loadUsageStats();
    void saveUsageStats();
    void loadPromptHistory();
    void savePromptHistory();
    void executeNextReportTask();
    void onReportTaskComplete(const QString &taskId, const AnalysisResponse &response);

    // Helper to convert Settings entities to QVariantMaps for QML
    QVariantMap agentToVariant(const Agent &a) const;
    QVariantMap flockToVariant(const Flock &f) const;
    QVariantMap swarmToVariant(const Swarm &s) const;
    QVariantMap parametersToVariant(const Parameters &p) const;
    QVariantMap systemPromptToVariant(const SystemPrompt &sp) const;
    QVariantMap promptToVariant(const Prompt &p) const;
    QVariantMap endpointToVariant(const Endpoint &e) const;
    QVariantMap reportAgentToVariant(const ReportAgent &ra) const;
    QVariantMap analysisResponseToVariant(const AnalysisResponse &r) const;
    QVariantMap storedReportToVariant(const StoredReport &r) const;
    QVariantMap chatSessionToVariant(const ChatSession &s) const;
    QVariantMap traceFileInfoToVariant(const TraceFileInfo &t) const;

    // Variant-to-struct conversions for incoming QML data
    Agent variantToAgent(const QVariantMap &data) const;
    Flock variantToFlock(const QVariantMap &data) const;
    Swarm variantToSwarm(const QVariantMap &data) const;
    Parameters variantToParameters(const QVariantMap &data) const;
    SystemPrompt variantToSystemPrompt(const QVariantMap &data) const;
    Prompt variantToPrompt(const QVariantMap &data) const;
    ChatParameters variantToChatParameters(const QVariantMap &data) const;
    QList<ChatMessage> variantToMessages(const QVariantList &list) const;

    // Storage paths
    QString storagePath() const;
    qint64 directorySize(const QString &path) const;
};
