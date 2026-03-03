#pragma once
#include <QObject>
#include <QMutex>
#include <QMap>
#include <QJsonObject>
#include "AppService.h"

struct ModelPricing {
    QString modelId;
    double promptPrice = 0.0;      // per token
    double completionPrice = 0.0;  // per token
    QString source; // "API", "OVERRIDE", "OPENROUTER", "LITELLM", "FALLBACK", "DEFAULT"
};

class PricingCache : public QObject {
    Q_OBJECT
public:
    static PricingCache& instance();

    ModelPricing getPricing(const AppService &provider, const QString &model);

    // Manual overrides
    void setManualPricing(const QString &providerId, const QString &model,
                          double promptPrice, double completionPrice);
    void removeManualPricing(const QString &providerId, const QString &model);

    // OpenRouter pricing cache
    void updateOpenRouterPricing(const QJsonObject &pricingData);
    bool isOpenRouterCacheValid() const; // valid for 7 days

private:
    explicit PricingCache(QObject *parent = nullptr);
    void ensureLoaded();
    void saveManualPricing();
    void saveOpenRouterPricing();

    QMutex m_mutex;
    bool m_loaded = false;

    // Tier 2: Manual overrides (key: "providerId:model")
    QMap<QString, ModelPricing> m_manualPricing;

    // Tier 3: OpenRouter pricing (key: "provider/model")
    QMap<QString, ModelPricing> m_openRouterPricing;
    qint64 m_openRouterTimestamp = 0;

    // Tier 5: Fallback hardcoded pricing
    static const QMap<QString, ModelPricing>& fallbackPricing();

    // Tier 6: Default pricing
    static ModelPricing defaultPricing(const QString &model);

    // Helper: find OpenRouter pricing with prefix matching
    ModelPricing *findOpenRouterPricing(const AppService &provider, const QString &model);

    // Storage paths
    QString m_storagePath; // ~/.local/share/AI/pricing/
};
