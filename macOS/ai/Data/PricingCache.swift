import Foundation
import os

/// Six-tier pricing cache for cost calculations.
/// Priority: API > OVERRIDE > OPENROUTER > LITELLM > FALLBACK > DEFAULT
actor PricingCache {
    static let shared = PricingCache()

    private static let logger = Logger(subsystem: "com.ai.macAI", category: "PricingCache")

    // UserDefaults keys
    private let openRouterPricingKey = "openrouter_pricing_json"
    private let openRouterTimestampKey = "openrouter_pricing_timestamp"
    private let manualPricingKey = "manual_pricing_json"

    private let cacheDuration: TimeInterval = 7 * 24 * 60 * 60  // 7 days

    // In-memory caches
    private var manualPricing: [String: ModelPricing] = [:]
    private var openRouterPricing: [String: ModelPricing] = [:]
    private var openRouterTimestamp: Date = .distantPast
    private var loaded = false

    private init() {}

    // MARK: - Model Pricing

    struct ModelPricing: Codable {
        let modelId: String
        let promptPrice: Double      // Price per token
        let completionPrice: Double  // Price per token
        let source: String
    }

    // MARK: - Lookup

    func getPricing(provider: AppService, model: String) -> ModelPricing {
        ensureLoaded()

        let isOpenRouter = provider.id == "OPENROUTER"

        // For OpenRouter, try OpenRouter pricing first
        if isOpenRouter, let p = findOpenRouterPricing(provider: provider, model: model) {
            return p
        }

        // Manual overrides
        let overrideKey = "\(provider.id):\(model)"
        if let p = manualPricing[overrideKey] { return p }

        // OpenRouter API
        if !isOpenRouter, let p = findOpenRouterPricing(provider: provider, model: model) {
            return p
        }

        // Fallback
        if let p = Self.fallbackPricing[model] { return p }

        // Default
        return Self.defaultPricing
    }

    private func findOpenRouterPricing(provider: AppService, model: String) -> ModelPricing? {
        if let p = openRouterPricing[model] { return p }
        if let prefix = provider.openRouterName, let p = openRouterPricing["\(prefix)/\(model)"] { return p }
        return openRouterPricing.first { $0.key.hasSuffix("/\(model)") }?.value
    }

    // MARK: - Manual Pricing

    func setManualPricing(provider: AppService, model: String, promptPrice: Double, completionPrice: Double) {
        let key = "\(provider.id):\(model)"
        manualPricing[key] = ModelPricing(modelId: model, promptPrice: promptPrice, completionPrice: completionPrice, source: "OVERRIDE")
        saveManualPricing()
    }

    func removeManualPricing(provider: AppService, model: String) {
        manualPricing.removeValue(forKey: "\(provider.id):\(model)")
        saveManualPricing()
    }

    // MARK: - OpenRouter Pricing

    func needsOpenRouterRefresh() -> Bool {
        ensureLoaded()
        if openRouterPricing.isEmpty { return true }
        return Date().timeIntervalSince(openRouterTimestamp) > cacheDuration
    }

    func saveOpenRouterPricing(_ pricing: [String: ModelPricing]) {
        openRouterPricing = pricing
        openRouterTimestamp = Date()
        if let data = try? JSONEncoder().encode(pricing),
           let json = String(data: data, encoding: .utf8) {
            UserDefaults.standard.set(json, forKey: openRouterPricingKey)
            UserDefaults.standard.set(openRouterTimestamp.timeIntervalSince1970, forKey: openRouterTimestampKey)
        }
    }

    // MARK: - Persistence

    private func ensureLoaded() {
        guard !loaded else { return }
        loaded = true

        // Load manual pricing
        if let json = UserDefaults.standard.string(forKey: manualPricingKey),
           let data = json.data(using: .utf8),
           let pricing = try? JSONDecoder().decode([String: ModelPricing].self, from: data) {
            manualPricing = pricing
        }

        // Load OpenRouter pricing
        if let json = UserDefaults.standard.string(forKey: openRouterPricingKey),
           let data = json.data(using: .utf8),
           let pricing = try? JSONDecoder().decode([String: ModelPricing].self, from: data) {
            openRouterPricing = pricing
            let ts = UserDefaults.standard.double(forKey: openRouterTimestampKey)
            openRouterTimestamp = ts > 0 ? Date(timeIntervalSince1970: ts) : .distantPast
        }
    }

    private func saveManualPricing() {
        if let data = try? JSONEncoder().encode(manualPricing),
           let json = String(data: data, encoding: .utf8) {
            UserDefaults.standard.set(json, forKey: manualPricingKey)
        }
    }

    // MARK: - Default / Fallback Pricing

    // Default: $25/M input, $75/M output
    private static let defaultPricing = ModelPricing(modelId: "default", promptPrice: 25.0e-6, completionPrice: 75.0e-6, source: "DEFAULT")

    private static let fallbackPricing: [String: ModelPricing] = [
        "deepseek-chat": ModelPricing(modelId: "deepseek-chat", promptPrice: 0.14e-6, completionPrice: 0.28e-6, source: "FALLBACK"),
        "grok-3": ModelPricing(modelId: "grok-3", promptPrice: 3.0e-6, completionPrice: 15.0e-6, source: "FALLBACK"),
        "grok-3-mini": ModelPricing(modelId: "grok-3-mini", promptPrice: 0.30e-6, completionPrice: 0.50e-6, source: "FALLBACK"),
        "mistral-small-latest": ModelPricing(modelId: "mistral-small-latest", promptPrice: 0.1e-6, completionPrice: 0.3e-6, source: "FALLBACK"),
        "sonar": ModelPricing(modelId: "sonar", promptPrice: 1.0e-6, completionPrice: 1.0e-6, source: "FALLBACK"),
        "sonar-pro": ModelPricing(modelId: "sonar-pro", promptPrice: 3.0e-6, completionPrice: 15.0e-6, source: "FALLBACK"),
        "llama-3.3-70b-versatile": ModelPricing(modelId: "llama-3.3-70b-versatile", promptPrice: 0.59e-6, completionPrice: 0.79e-6, source: "FALLBACK"),
        "command-a-03-2025": ModelPricing(modelId: "command-a-03-2025", promptPrice: 2.5e-6, completionPrice: 10.0e-6, source: "FALLBACK"),
        "jamba-mini": ModelPricing(modelId: "jamba-mini", promptPrice: 0.2e-6, completionPrice: 0.4e-6, source: "FALLBACK"),
        "qwen-plus": ModelPricing(modelId: "qwen-plus", promptPrice: 0.8e-6, completionPrice: 2.0e-6, source: "FALLBACK"),
        "glm-4.7-flash": ModelPricing(modelId: "glm-4.7-flash", promptPrice: 0.007e-6, completionPrice: 0.007e-6, source: "FALLBACK"),
        "kimi-latest": ModelPricing(modelId: "kimi-latest", promptPrice: 0.55e-6, completionPrice: 2.19e-6, source: "FALLBACK"),
        "palmyra-x-004": ModelPricing(modelId: "palmyra-x-004", promptPrice: 5.0e-6, completionPrice: 15.0e-6, source: "FALLBACK"),
    ]
}
