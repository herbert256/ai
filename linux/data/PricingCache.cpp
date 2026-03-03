// PricingCache.cpp - Six-tier pricing lookup for AI model costs

#include "PricingCache.h"
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QMutexLocker>
#include <QStandardPaths>
#include <QDateTime>

// Cache validity period: 7 days in milliseconds
static constexpr qint64 CACHE_DURATION_MS = 7LL * 24 * 60 * 60 * 1000;

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

PricingCache& PricingCache::instance() {
    static PricingCache cache;
    return cache;
}

PricingCache::PricingCache(QObject *parent)
    : QObject(parent)
    , m_storagePath(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                    + QStringLiteral("/pricing/"))
{
}

// ---------------------------------------------------------------------------
// Lazy loading from disk
// ---------------------------------------------------------------------------

void PricingCache::ensureLoaded() {
    if (m_loaded)
        return;
    m_loaded = true;

    QDir().mkpath(m_storagePath);

    // Load manual pricing overrides
    QFile manualFile(m_storagePath + QStringLiteral("manual_pricing.json"));
    if (manualFile.open(QIODevice::ReadOnly)) {
        const QJsonDocument doc = QJsonDocument::fromJson(manualFile.readAll());
        manualFile.close();

        if (doc.isObject()) {
            const QJsonObject obj = doc.object();
            for (auto it = obj.begin(); it != obj.end(); ++it) {
                const QJsonObject entry = it.value().toObject();
                ModelPricing p;
                p.modelId = entry.value(QStringLiteral("modelId")).toString();
                p.promptPrice = entry.value(QStringLiteral("promptPrice")).toDouble();
                p.completionPrice = entry.value(QStringLiteral("completionPrice")).toDouble();
                p.source = QStringLiteral("OVERRIDE");
                m_manualPricing.insert(it.key(), p);
            }
        }
    }

    // Load OpenRouter pricing cache
    QFile orFile(m_storagePath + QStringLiteral("openrouter_pricing.json"));
    if (orFile.open(QIODevice::ReadOnly)) {
        const QJsonDocument doc = QJsonDocument::fromJson(orFile.readAll());
        orFile.close();

        if (doc.isObject()) {
            const QJsonObject root = doc.object();
            m_openRouterTimestamp = static_cast<qint64>(
                root.value(QStringLiteral("timestamp")).toDouble());

            const QJsonObject prices = root.value(QStringLiteral("pricing")).toObject();
            for (auto it = prices.begin(); it != prices.end(); ++it) {
                const QJsonObject entry = it.value().toObject();
                ModelPricing p;
                p.modelId = entry.value(QStringLiteral("modelId")).toString();
                p.promptPrice = entry.value(QStringLiteral("promptPrice")).toDouble();
                p.completionPrice = entry.value(QStringLiteral("completionPrice")).toDouble();
                p.source = QStringLiteral("OPENROUTER");
                m_openRouterPricing.insert(it.key(), p);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Persistence helpers
// ---------------------------------------------------------------------------

void PricingCache::saveManualPricing() {
    QDir().mkpath(m_storagePath);

    QJsonObject obj;
    for (auto it = m_manualPricing.constBegin(); it != m_manualPricing.constEnd(); ++it) {
        QJsonObject entry;
        entry[QStringLiteral("modelId")] = it.value().modelId;
        entry[QStringLiteral("promptPrice")] = it.value().promptPrice;
        entry[QStringLiteral("completionPrice")] = it.value().completionPrice;
        obj[it.key()] = entry;
    }

    QFile file(m_storagePath + QStringLiteral("manual_pricing.json"));
    if (file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        file.write(QJsonDocument(obj).toJson(QJsonDocument::Indented));
        file.close();
    }
}

void PricingCache::saveOpenRouterPricing() {
    QDir().mkpath(m_storagePath);

    QJsonObject prices;
    for (auto it = m_openRouterPricing.constBegin(); it != m_openRouterPricing.constEnd(); ++it) {
        QJsonObject entry;
        entry[QStringLiteral("modelId")] = it.value().modelId;
        entry[QStringLiteral("promptPrice")] = it.value().promptPrice;
        entry[QStringLiteral("completionPrice")] = it.value().completionPrice;
        prices[it.key()] = entry;
    }

    QJsonObject root;
    root[QStringLiteral("timestamp")] = static_cast<double>(m_openRouterTimestamp);
    root[QStringLiteral("pricing")] = prices;

    QFile file(m_storagePath + QStringLiteral("openrouter_pricing.json"));
    if (file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        file.write(QJsonDocument(root).toJson(QJsonDocument::Indented));
        file.close();
    }
}

// ---------------------------------------------------------------------------
// Six-tier pricing lookup
// ---------------------------------------------------------------------------

ModelPricing PricingCache::getPricing(const AppService &provider, const QString &model) {
    QMutexLocker locker(&m_mutex);
    ensureLoaded();

    const bool isOpenRouter = (provider.id == QStringLiteral("OPENROUTER"));

    // For OpenRouter provider, try OpenRouter pricing first (reflects actual aggregator costs)
    if (isOpenRouter) {
        ModelPricing *orp = findOpenRouterPricing(provider, model);
        if (orp)
            return *orp;
    }

    // Tier 1: API (reserved, cost comes from API response at call site)

    // Tier 2: Manual overrides
    const QString overrideKey = provider.id + QStringLiteral(":") + model;
    auto manualIt = m_manualPricing.constFind(overrideKey);
    if (manualIt != m_manualPricing.constEnd())
        return manualIt.value();

    // Tier 3: OpenRouter (for non-OpenRouter providers)
    if (!isOpenRouter) {
        ModelPricing *orp = findOpenRouterPricing(provider, model);
        if (orp)
            return *orp;
    }

    // Tier 4: LiteLLM (reserved, not implemented)

    // Tier 5: Fallback hardcoded pricing
    const QMap<QString, ModelPricing> &fb = fallbackPricing();
    auto fbIt = fb.constFind(model);
    if (fbIt != fb.constEnd())
        return fbIt.value();

    // Tier 6: Default pricing
    return defaultPricing(model);
}

// ---------------------------------------------------------------------------
// OpenRouter pricing lookup with prefix matching
// ---------------------------------------------------------------------------

ModelPricing *PricingCache::findOpenRouterPricing(const AppService &provider, const QString &model) {
    // Exact match
    auto it = m_openRouterPricing.find(model);
    if (it != m_openRouterPricing.end())
        return &it.value();

    // Try with provider prefix (e.g., "anthropic/claude-3-opus")
    if (provider.openRouterName.has_value()) {
        const QString prefixed = provider.openRouterName.value()
                                 + QStringLiteral("/") + model;
        it = m_openRouterPricing.find(prefixed);
        if (it != m_openRouterPricing.end())
            return &it.value();
    }

    // Try partial match (any key ending with "/model")
    const QString suffix = QStringLiteral("/") + model;
    for (auto jt = m_openRouterPricing.begin(); jt != m_openRouterPricing.end(); ++jt) {
        if (jt.key().endsWith(suffix))
            return &jt.value();
    }

    return nullptr;
}

// ---------------------------------------------------------------------------
// Manual pricing overrides
// ---------------------------------------------------------------------------

void PricingCache::setManualPricing(const QString &providerId, const QString &model,
                                    double promptPrice, double completionPrice) {
    QMutexLocker locker(&m_mutex);
    ensureLoaded();

    const QString key = providerId + QStringLiteral(":") + model;
    ModelPricing p;
    p.modelId = model;
    p.promptPrice = promptPrice;
    p.completionPrice = completionPrice;
    p.source = QStringLiteral("OVERRIDE");
    m_manualPricing.insert(key, p);
    saveManualPricing();
}

void PricingCache::removeManualPricing(const QString &providerId, const QString &model) {
    QMutexLocker locker(&m_mutex);
    ensureLoaded();

    const QString key = providerId + QStringLiteral(":") + model;
    m_manualPricing.remove(key);
    saveManualPricing();
}

// ---------------------------------------------------------------------------
// OpenRouter pricing cache
// ---------------------------------------------------------------------------

void PricingCache::updateOpenRouterPricing(const QJsonObject &pricingData) {
    QMutexLocker locker(&m_mutex);
    ensureLoaded();

    m_openRouterPricing.clear();

    for (auto it = pricingData.begin(); it != pricingData.end(); ++it) {
        const QJsonObject entry = it.value().toObject();
        ModelPricing p;
        p.modelId = it.key();
        p.promptPrice = entry.value(QStringLiteral("promptPrice")).toDouble();
        p.completionPrice = entry.value(QStringLiteral("completionPrice")).toDouble();
        p.source = QStringLiteral("OPENROUTER");
        m_openRouterPricing.insert(it.key(), p);
    }

    m_openRouterTimestamp = QDateTime::currentMSecsSinceEpoch();
    saveOpenRouterPricing();
}

bool PricingCache::isOpenRouterCacheValid() const {
    if (m_openRouterPricing.isEmpty() || m_openRouterTimestamp == 0)
        return false;
    return (QDateTime::currentMSecsSinceEpoch() - m_openRouterTimestamp) < CACHE_DURATION_MS;
}

// ---------------------------------------------------------------------------
// Tier 5: Fallback hardcoded pricing
// ---------------------------------------------------------------------------

const QMap<QString, ModelPricing>& PricingCache::fallbackPricing() {
    // Built once on first call; per-million prices converted to per-token.
    static const QMap<QString, ModelPricing> table = {
        // DeepSeek
        { QStringLiteral("deepseek-chat"),
          { QStringLiteral("deepseek-chat"), 0.27e-6, 1.10e-6, QStringLiteral("FALLBACK") } },
        // xAI Grok
        { QStringLiteral("grok-3"),
          { QStringLiteral("grok-3"), 3.00e-6, 15.00e-6, QStringLiteral("FALLBACK") } },
        { QStringLiteral("grok-3-mini"),
          { QStringLiteral("grok-3-mini"), 0.30e-6, 0.50e-6, QStringLiteral("FALLBACK") } },
        // Mistral
        { QStringLiteral("mistral-small-latest"),
          { QStringLiteral("mistral-small-latest"), 0.10e-6, 0.30e-6, QStringLiteral("FALLBACK") } },
        // Perplexity
        { QStringLiteral("sonar"),
          { QStringLiteral("sonar"), 1.00e-6, 1.00e-6, QStringLiteral("FALLBACK") } },
        { QStringLiteral("sonar-pro"),
          { QStringLiteral("sonar-pro"), 3.00e-6, 15.00e-6, QStringLiteral("FALLBACK") } },
        // Groq
        { QStringLiteral("llama-3.3-70b-versatile"),
          { QStringLiteral("llama-3.3-70b-versatile"), 0.59e-6, 0.79e-6, QStringLiteral("FALLBACK") } },
        // Cohere
        { QStringLiteral("command-a-03-2025"),
          { QStringLiteral("command-a-03-2025"), 2.50e-6, 10.00e-6, QStringLiteral("FALLBACK") } },
        // AI21
        { QStringLiteral("jamba-mini"),
          { QStringLiteral("jamba-mini"), 0.20e-6, 0.40e-6, QStringLiteral("FALLBACK") } },
        // DashScope (Qwen)
        { QStringLiteral("qwen-plus"),
          { QStringLiteral("qwen-plus"), 0.80e-6, 2.00e-6, QStringLiteral("FALLBACK") } },
        // ZhipuAI GLM (free)
        { QStringLiteral("glm-4.7-flash"),
          { QStringLiteral("glm-4.7-flash"), 0.0, 0.0, QStringLiteral("FALLBACK") } },
        // Moonshot / Kimi
        { QStringLiteral("kimi-latest"),
          { QStringLiteral("kimi-latest"), 0.20e-6, 0.60e-6, QStringLiteral("FALLBACK") } },
        // Writer
        { QStringLiteral("palmyra-x-004"),
          { QStringLiteral("palmyra-x-004"), 5.00e-6, 15.00e-6, QStringLiteral("FALLBACK") } },
    };
    return table;
}

// ---------------------------------------------------------------------------
// Tier 6: Default pricing ($25/M input, $75/M output)
// ---------------------------------------------------------------------------

ModelPricing PricingCache::defaultPricing(const QString &model) {
    ModelPricing p;
    p.modelId = model;
    p.promptPrice = 25.00e-6;     // $25.00 per 1M tokens = $0.000025 per token
    p.completionPrice = 75.00e-6; // $75.00 per 1M tokens = $0.000075 per token
    p.source = QStringLiteral("DEFAULT");
    return p;
}
