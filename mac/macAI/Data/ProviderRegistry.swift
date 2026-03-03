import Foundation
import os

/// Mutable registry of AI service providers.
/// On first launch, loads from bundled Resources/setup.json.
/// Subsequent launches load from UserDefaults.
actor ProviderRegistry {
    static let shared = ProviderRegistry()

    private static let logger = Logger(subsystem: "com.ai.macAI", category: "ProviderRegistry")
    private let userDefaultsKey = "provider_registry_json"
    private let initializedKey = "provider_registry_initialized"

    private var providers: [AppService] = []
    private var initialized = false

    private init() {}

    // MARK: - Nonisolated accessors (for synchronous use)

    /// All registered providers. Thread-safe snapshot.
    nonisolated func getAll() -> [AppService] {
        // Use a blocking wait for synchronous access
        let semaphore = DispatchSemaphore(value: 0)
        var result: [AppService] = []
        Task {
            result = await self.providers
            semaphore.signal()
        }
        semaphore.wait()
        return result
    }

    /// Find a provider by its ID string. Thread-safe.
    nonisolated func findById(_ id: String) -> AppService? {
        let semaphore = DispatchSemaphore(value: 0)
        var result: AppService?
        Task {
            result = await self.providers.first { $0.id == id }
            semaphore.signal()
        }
        semaphore.wait()
        return result
    }

    // MARK: - Async accessors

    /// All registered providers (async).
    func getAllAsync() -> [AppService] {
        providers
    }

    /// Find a provider by ID (async).
    func findByIdAsync(_ id: String) -> AppService? {
        providers.first { $0.id == id }
    }

    // MARK: - Initialization

    /// Initialize the registry. Must be called before any provider access.
    func initialize() {
        guard !initialized else { return }

        let defaults = UserDefaults.standard
        if defaults.bool(forKey: initializedKey),
           let json = defaults.string(forKey: userDefaultsKey) {
            // Load from UserDefaults
            do {
                let defs = try JSONDecoder().decode([ProviderDefinition].self, from: Data(json.utf8))
                providers = defs.map { $0.toAppService() }
            } catch {
                Self.logger.error("Error loading providers from UserDefaults: \(error.localizedDescription)")
                loadFromBundle()
            }
        } else {
            loadFromBundle()
        }

        // Safety net: if providers list is empty, reload from bundle
        if providers.isEmpty {
            Self.logger.warning("No providers loaded, falling back to bundle")
            loadFromBundle()
        }

        initialized = true
        Self.logger.info("Initialized with \(self.providers.count) providers")
    }

    // MARK: - Bundle loading

    private func loadFromBundle() {
        guard let url = Bundle.main.url(forResource: "setup", withExtension: "json") else {
            Self.logger.error("setup.json not found in bundle")
            return
        }

        do {
            let data = try Data(contentsOf: url)
            let export = try JSONDecoder().decode(ConfigSetup.self, from: data)
            guard let defs = export.providerDefinitions, !defs.isEmpty else {
                Self.logger.warning("No providerDefinitions in setup.json")
                return
            }
            providers = defs.map { $0.toAppService() }
            save()
            Self.logger.info("Loaded \(self.providers.count) providers from setup.json")
        } catch {
            Self.logger.error("Error loading providers from bundle: \(error.localizedDescription)")
        }
    }

    // MARK: - CRUD Operations

    func add(_ service: AppService) {
        providers.append(service)
        save()
    }

    func update(_ service: AppService) {
        if let index = providers.firstIndex(where: { $0.id == service.id }) {
            providers[index] = service
            save()
        }
    }

    func remove(_ id: String) {
        providers.removeAll { $0.id == id }
        save()
    }

    /// Ensure providers from a list exist in the registry (used during import).
    func ensureProviders(_ services: [AppService]) {
        var changed = false
        for service in services {
            if !providers.contains(where: { $0.id == service.id }) {
                providers.append(service)
                changed = true
            }
        }
        if changed { save() }
    }

    /// Re-initialize from bundle (for reset/refresh).
    func resetToDefaults() {
        initialized = false
        UserDefaults.standard.removeObject(forKey: userDefaultsKey)
        UserDefaults.standard.removeObject(forKey: initializedKey)
        initialize()
    }

    // MARK: - Persistence

    private func save() {
        let defs = providers.map { ProviderDefinition.fromAppService($0) }
        do {
            let data = try JSONEncoder().encode(defs)
            let json = String(data: data, encoding: .utf8)
            UserDefaults.standard.set(json, forKey: userDefaultsKey)
            UserDefaults.standard.set(true, forKey: initializedKey)
        } catch {
            Self.logger.error("Error saving providers: \(error.localizedDescription)")
        }
    }
}

// MARK: - Setup JSON structure

/// Minimal structure for parsing setup.json (and config export).
struct ConfigSetup: Codable {
    let version: Int?
    let providerDefinitions: [ProviderDefinition]?
}
