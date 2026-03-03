import Foundation

// MARK: - Model Pricing

struct ModelPricing: Codable, Sendable {
    let modelId: String
    let promptPrice: Double
    let completionPrice: Double
    let source: String

    static let sourceApi = "API"
    static let sourceOverride = "OVERRIDE"
    static let sourceOpenRouter = "OPENROUTER"
    static let sourceLiteLLM = "LITELLM"
    static let sourceFallback = "FALLBACK"
    static let sourceDefault = "DEFAULT"

    static let defaultPromptPrice = 25.0 / 1_000_000
    static let defaultCompletionPrice = 75.0 / 1_000_000
}

// MARK: - Pricing Cache

actor PricingCache {
    static let shared = PricingCache()

    private let cacheDuration: TimeInterval = 7 * 24 * 60 * 60 // 7 days
    private let prefsKey = "pricing_cache"

    private var openRouterCache: [String: ModelPricing] = [:]
    private var litellmCache: [String: ModelPricing] = [:]
    private var manualPricing: [String: ModelPricing] = [:]
    private var supportedParametersCache: [String: [String]]? = nil
    private var loaded = false

    // MARK: - Initialization

    private func ensureLoaded() {
        guard !loaded else { return }
        loadCaches()
        loaded = true
    }

    private func loadCaches() {
        let prefs = UserDefaults.standard
        if let json = prefs.string(forKey: "\(prefsKey)_openrouter"),
           let data = json.data(using: .utf8),
           let map = try? JSONDecoder().decode([String: ModelPricing].self, from: data) {
            openRouterCache = map
        }
        if let json = prefs.string(forKey: "\(prefsKey)_litellm"),
           let data = json.data(using: .utf8),
           let map = try? JSONDecoder().decode([String: ModelPricing].self, from: data) {
            litellmCache = map
        }
        if let json = prefs.string(forKey: "\(prefsKey)_manual"),
           let data = json.data(using: .utf8),
           let map = try? JSONDecoder().decode([String: ModelPricing].self, from: data) {
            manualPricing = map
        }
    }

    private func saveCache(key: String, cache: [String: ModelPricing]) {
        guard let data = try? JSONEncoder().encode(cache),
              let json = String(data: data, encoding: .utf8) else { return }
        UserDefaults.standard.set(json, forKey: "\(prefsKey)_\(key)")
    }

    // MARK: - Six-Tier Pricing Lookup

    func getPricing(providerId: String, model: String) -> ModelPricing {
        ensureLoaded()
        let key = "\(providerId):\(model)"

        // Tier 2: Manual override
        if let manual = manualPricing[key] { return manual }

        // Tier 3: OpenRouter cache
        if let or = lookupOpenRouter(providerId: providerId, model: model) { return or }

        // Tier 4: LiteLLM bundled
        if let ll = lookupLiteLLM(providerId: providerId, model: model) { return ll }

        // Tier 5: Hardcoded fallback
        if let fb = Self.fallbackPricing[model] { return fb }

        // Tier 6: Default
        return ModelPricing(modelId: model, promptPrice: ModelPricing.defaultPromptPrice,
                            completionPrice: ModelPricing.defaultCompletionPrice, source: ModelPricing.sourceDefault)
    }

    func getPricingWithoutOverride(providerId: String, model: String) -> ModelPricing {
        ensureLoaded()
        if let or = lookupOpenRouter(providerId: providerId, model: model) { return or }
        if let ll = lookupLiteLLM(providerId: providerId, model: model) { return ll }
        if let fb = Self.fallbackPricing[model] { return fb }
        return ModelPricing(modelId: model, promptPrice: ModelPricing.defaultPromptPrice,
                            completionPrice: ModelPricing.defaultCompletionPrice, source: ModelPricing.sourceDefault)
    }

    private func lookupOpenRouter(providerId: String, model: String) -> ModelPricing? {
        // Try direct model name, then with provider prefix
        if let p = openRouterCache[model] { return p }
        return nil
    }

    private func lookupLiteLLM(providerId: String, model: String) -> ModelPricing? {
        if let p = litellmCache[model] { return p }
        return nil
    }

    // MARK: - Manual Pricing CRUD

    func setManualPricing(providerId: String, model: String, promptPrice: Double, completionPrice: Double) {
        ensureLoaded()
        let key = "\(providerId):\(model)"
        manualPricing[key] = ModelPricing(modelId: model, promptPrice: promptPrice,
                                          completionPrice: completionPrice, source: ModelPricing.sourceOverride)
        saveCache(key: "manual", cache: manualPricing)
    }

    func removeManualPricing(providerId: String, model: String) {
        ensureLoaded()
        manualPricing.removeValue(forKey: "\(providerId):\(model)")
        saveCache(key: "manual", cache: manualPricing)
    }

    func getManualPricing(providerId: String, model: String) -> ModelPricing? {
        ensureLoaded()
        return manualPricing["\(providerId):\(model)"]
    }

    func getAllManualPricing() -> [String: ModelPricing] {
        ensureLoaded()
        return manualPricing
    }

    func setAllManualPricing(_ pricing: [String: ModelPricing]) {
        manualPricing = pricing
        saveCache(key: "manual", cache: manualPricing)
    }

    // MARK: - OpenRouter Cache

    func saveOpenRouterPricing(_ pricing: [String: ModelPricing]) {
        openRouterCache = pricing
        saveCache(key: "openrouter", cache: pricing)
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "\(prefsKey)_openrouter_timestamp")
    }

    func needsOpenRouterRefresh() -> Bool {
        let timestamp = UserDefaults.standard.double(forKey: "\(prefsKey)_openrouter_timestamp")
        if timestamp == 0 { return true }
        return Date().timeIntervalSince1970 - timestamp > cacheDuration
    }

    func getOpenRouterCacheAge() -> String {
        let timestamp = UserDefaults.standard.double(forKey: "\(prefsKey)_openrouter_timestamp")
        if timestamp == 0 { return "never" }
        let age = Date().timeIntervalSince1970 - timestamp
        let days = Int(age / 86400)
        let hours = Int(age.truncatingRemainder(dividingBy: 86400) / 3600)
        if days > 0 { return "\(days)d \(hours)h ago" }
        return "\(hours)h ago"
    }

    func getOpenRouterPricing() -> [String: ModelPricing] {
        ensureLoaded()
        return openRouterCache
    }

    // MARK: - LiteLLM Cache

    func refreshLiteLLMPricing() {
        guard let url = Bundle.main.url(forResource: "model_prices_and_context_window", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else { return }

        var cache: [String: ModelPricing] = [:]
        for (modelId, info) in json {
            let prompt = (info["input_cost_per_token"] as? Double) ?? 0
            let completion = (info["output_cost_per_token"] as? Double) ?? 0
            if prompt > 0 || completion > 0 {
                cache[modelId] = ModelPricing(modelId: modelId, promptPrice: prompt,
                                              completionPrice: completion, source: ModelPricing.sourceLiteLLM)
            }
        }
        litellmCache = cache
        saveCache(key: "litellm", cache: cache)
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "\(prefsKey)_litellm_timestamp")
    }

    func getLiteLLMPricing() -> [String: ModelPricing] {
        ensureLoaded()
        return litellmCache
    }

    // MARK: - Supported Parameters

    func getSupportedParameters(providerId: String, model: String) -> [String]? {
        if supportedParametersCache == nil {
            loadSupportedParametersFromFile()
        }
        let key = "\(providerId):\(model)"
        return supportedParametersCache?[key]
    }

    func clearSupportedParametersCache() {
        supportedParametersCache = nil
    }

    private func loadSupportedParametersFromFile() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let file = dir.appendingPathComponent("model_supported_parameters.json")
        guard let data = try? Data(contentsOf: file),
              let entries = try? JSONDecoder().decode([ModelSupportedParametersEntry].self, from: data) else {
            supportedParametersCache = [:]
            return
        }
        var cache: [String: [String]] = [:]
        for entry in entries {
            let key = "\(entry.provider):\(entry.model)"
            cache[key] = entry.supported_parameters ?? []
        }
        supportedParametersCache = cache
    }

    // MARK: - Stats

    func getPricingStats() -> String {
        ensureLoaded()
        var parts: [String] = []
        if !manualPricing.isEmpty { parts.append("Manual (\(manualPricing.count))") }
        if !openRouterCache.isEmpty { parts.append("OpenRouter (\(openRouterCache.count))") }
        if !litellmCache.isEmpty { parts.append("LiteLLM (\(litellmCache.count))") }
        parts.append("Fallback (\(Self.fallbackPricing.count))")
        return parts.joined(separator: " + ")
    }

    // MARK: - Cost Extraction Helpers

    static func extractApiCost(openAiUsage: OpenAiUsage?, service: AppService?) -> Double? {
        guard let usage = openAiUsage else { return nil }
        if let service = service, service.extractApiCost, let cost = usage.cost { return cost }
        if let ticks = usage.cost_in_usd_ticks {
            let divisor = service?.costTicksDivisor ?? 10_000_000_000.0
            return Double(ticks) / divisor
        }
        if let cost = usage.cost_usd?.total_cost { return cost }
        return nil
    }

    static func extractApiCost(claudeUsage: ClaudeUsage?) -> Double? {
        guard let usage = claudeUsage else { return nil }
        if let cost = usage.cost { return cost }
        if let ticks = usage.cost_in_usd_ticks { return Double(ticks) / 10_000_000_000.0 }
        if let cost = usage.cost_usd?.total_cost { return cost }
        return nil
    }

    static func extractApiCost(geminiUsage: GeminiUsageMetadata?) -> Double? {
        guard let usage = geminiUsage else { return nil }
        if let cost = usage.cost { return cost }
        if let ticks = usage.cost_in_usd_ticks { return Double(ticks) / 10_000_000_000.0 }
        if let cost = usage.cost_usd?.total_cost { return cost }
        return nil
    }

    // MARK: - Hardcoded Fallback Pricing

    static let fallbackPricing: [String: ModelPricing] = {
        var m: [String: ModelPricing] = [:]
        func add(_ model: String, _ prompt: Double, _ completion: Double) {
            m[model] = ModelPricing(modelId: model, promptPrice: prompt / 1_000_000,
                                    completionPrice: completion / 1_000_000, source: ModelPricing.sourceFallback)
        }
        // OpenAI
        add("gpt-4o", 2.50, 10.0)
        add("gpt-4o-mini", 0.15, 0.60)
        add("gpt-4-turbo", 10.0, 30.0)
        add("o1", 15.0, 60.0)
        add("o1-mini", 3.0, 12.0)
        add("o3-mini", 1.10, 4.40)
        // Anthropic
        add("claude-sonnet-4-20250514", 3.0, 15.0)
        add("claude-opus-4-20250514", 15.0, 75.0)
        add("claude-3-7-sonnet-20250219", 3.0, 15.0)
        add("claude-3-5-sonnet-20241022", 3.0, 15.0)
        add("claude-3-5-haiku-20241022", 0.80, 4.0)
        add("claude-3-opus-20240229", 15.0, 75.0)
        add("claude-3-haiku-20240307", 0.25, 1.25)
        // Google
        add("gemini-2.0-flash", 0.10, 0.40)
        add("gemini-1.5-pro", 1.25, 5.0)
        add("gemini-1.5-flash", 0.075, 0.30)
        // xAI
        add("grok-3-mini", 0.30, 0.50)
        add("grok-3", 3.0, 15.0)
        add("grok-2", 2.0, 10.0)
        // DeepSeek
        add("deepseek-chat", 0.27, 1.10)
        add("deepseek-reasoner", 0.55, 2.19)
        // Mistral
        add("mistral-small-latest", 0.10, 0.30)
        add("mistral-large-latest", 2.0, 6.0)
        // Perplexity
        add("sonar", 1.0, 1.0)
        add("sonar-pro", 3.0, 15.0)
        return m
    }()
}

// MARK: - Support types

struct ModelPricingEntry: Codable {
    let provider: String
    let model: String
    let pricing: OpenRouterPricing?
}

struct ModelSupportedParametersEntry: Codable {
    let provider: String
    let model: String
    let supported_parameters: [String]?
}
