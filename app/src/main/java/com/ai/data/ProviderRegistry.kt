package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

/**
 * Mutable registry of AI service providers.
 * On first launch, loads from bundled assets/providers.json.
 * Subsequent launches load from SharedPreferences.
 * Supports CRUD operations for fully data-driven provider management.
 */
object ProviderRegistry {
    private const val PREFS_NAME = "provider_registry"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_INITIALIZED = "initialized"

    private val providers = mutableListOf<AiService>()
    private var initialized = false
    private var prefs: SharedPreferences? = null

    /**
     * Initialize the registry. Must be called before any provider access.
     * Loads from SharedPreferences if previously saved, otherwise from assets/providers.json.
     */
    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sp = prefs!!

        if (sp.getBoolean(KEY_INITIALIZED, false)) {
            // Load from SharedPreferences
            val json = sp.getString(KEY_PROVIDERS, null)
            if (json != null) {
                try {
                    val list = parseProvidersJson(json)
                    providers.clear()
                    providers.addAll(list)
                } catch (e: Exception) {
                    android.util.Log.e("ProviderRegistry", "Error loading providers from prefs: ${e.message}")
                    loadFromAssets(context)
                }
            } else {
                loadFromAssets(context)
            }
        } else {
            loadFromAssets(context)
        }
        // Safety net: if providers list is empty (e.g. upgrade from pre-registry version),
        // reload from bundled assets
        if (providers.isEmpty()) {
            android.util.Log.w("ProviderRegistry", "No providers loaded, falling back to assets")
            loadFromAssets(context)
        }
        initialized = true
    }

    private fun loadFromAssets(context: Context) {
        try {
            val json = context.assets.open("providers.json").bufferedReader().use { it.readText() }
            val list = parseProvidersJson(json)
            providers.clear()
            providers.addAll(list)
            save()
            android.util.Log.d("ProviderRegistry", "Loaded ${list.size} providers from assets/providers.json")
        } catch (e: Exception) {
            android.util.Log.e("ProviderRegistry", "Error loading providers from assets: ${e.message}")
        }
    }

    private fun parseProvidersJson(json: String): List<AiService> {
        val gson = Gson()
        val listType = object : TypeToken<List<ProviderDefinition>>() {}.type
        val defs: List<ProviderDefinition> = gson.fromJson(json, listType)
        return defs.map { it.toAiService() }
    }

    /** All registered providers. */
    fun getAll(): List<AiService> = providers.toList()

    /** Find a provider by its ID string, or null if not found. */
    fun findById(id: String): AiService? = providers.find { it.id == id }

    /** Add a new provider. */
    fun add(service: AiService) {
        providers.add(service)
        save()
    }

    /** Update an existing provider (matched by id). */
    fun update(service: AiService) {
        val index = providers.indexOfFirst { it.id == service.id }
        if (index >= 0) {
            providers[index] = service
            save()
        }
    }

    /** Remove a provider by id. */
    fun remove(id: String) {
        providers.removeAll { it.id == id }
        save()
    }

    /** Persist current providers to SharedPreferences. */
    fun save() {
        val sp = prefs ?: return
        val defs = providers.map { ProviderDefinition.fromAiService(it) }
        val gson = GsonBuilder().create()
        val json = gson.toJson(defs)
        sp.edit()
            .putString(KEY_PROVIDERS, json)
            .putBoolean(KEY_INITIALIZED, true)
            .apply()
    }

    /** Re-initialize from assets (for reset/refresh). */
    fun resetToDefaults(context: Context) {
        initialized = false
        prefs?.edit()?.clear()?.apply()
        init(context)
    }

    /**
     * Ensure providers from a list exist in the registry (used during import).
     * Adds any that are missing by id.
     */
    fun ensureProviders(services: List<AiService>) {
        var changed = false
        for (service in services) {
            if (findById(service.id) == null) {
                providers.add(service)
                changed = true
            }
        }
        if (changed) save()
    }
}

/**
 * JSON-serializable representation of an AiService provider definition.
 * Used for providers.json and SharedPreferences storage.
 */
data class ProviderDefinition(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val adminUrl: String? = "",
    val defaultModel: String,
    val openRouterName: String? = null,
    val apiFormat: String? = "OPENAI_COMPATIBLE",
    val chatPath: String? = "v1/chat/completions",
    val modelsPath: String? = "v1/models",
    val prefsKey: String? = "",
    val seedFieldName: String? = "seed",
    val supportsCitations: Boolean? = false,
    val supportsSearchRecency: Boolean? = false,
    val extractApiCost: Boolean? = false,
    val costTicksDivisor: Double? = null,
    val modelListFormat: String? = "object",
    val modelFilter: String? = null,
    val litellmPrefix: String? = null,
    val hardcodedModels: List<String>? = null,
    val defaultModelSource: String? = null
) {
    fun toAiService(): AiService = AiService(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        adminUrl = adminUrl ?: "",
        defaultModel = defaultModel,
        openRouterName = openRouterName,
        apiFormat = try { ApiFormat.valueOf(apiFormat ?: "OPENAI_COMPATIBLE") } catch (e: Exception) { ApiFormat.OPENAI_COMPATIBLE },
        chatPath = chatPath ?: "v1/chat/completions",
        modelsPath = modelsPath,
        prefsKey = prefsKey ?: id.lowercase(),
        seedFieldName = seedFieldName ?: "seed",
        supportsCitations = supportsCitations ?: false,
        supportsSearchRecency = supportsSearchRecency ?: false,
        extractApiCost = extractApiCost ?: false,
        costTicksDivisor = costTicksDivisor,
        modelListFormat = modelListFormat ?: "object",
        modelFilter = modelFilter,
        litellmPrefix = litellmPrefix,
        hardcodedModels = hardcodedModels,
        defaultModelSource = defaultModelSource
    )

    companion object {
        fun fromAiService(s: AiService): ProviderDefinition = ProviderDefinition(
            id = s.id,
            displayName = s.displayName,
            baseUrl = s.baseUrl,
            adminUrl = s.adminUrl,
            defaultModel = s.defaultModel,
            openRouterName = s.openRouterName,
            apiFormat = s.apiFormat.name,
            chatPath = s.chatPath,
            modelsPath = s.modelsPath,
            prefsKey = s.prefsKey,
            seedFieldName = s.seedFieldName,
            supportsCitations = s.supportsCitations,
            supportsSearchRecency = s.supportsSearchRecency,
            extractApiCost = s.extractApiCost,
            costTicksDivisor = s.costTicksDivisor,
            modelListFormat = s.modelListFormat,
            modelFilter = s.modelFilter,
            litellmPrefix = s.litellmPrefix,
            hardcodedModels = s.hardcodedModels,
            defaultModelSource = s.defaultModelSource
        )
    }
}
