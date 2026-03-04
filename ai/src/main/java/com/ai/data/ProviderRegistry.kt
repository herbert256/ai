package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.model.ConfigExport
import com.google.gson.reflect.TypeToken

/**
 * Mutable registry of AI service providers.
 * On first launch, loads from bundled assets/setup.json.
 * Subsequent launches load from SharedPreferences.
 */
object ProviderRegistry {
    private const val PREFS_NAME = "provider_registry"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_INITIALIZED = "initialized"

    private val providers = java.util.concurrent.CopyOnWriteArrayList<AppService>()
    @Volatile private var initialized = false
    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private val providerListType = object : TypeToken<List<ProviderDefinition>>() {}.type

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sp = prefs!!
            if (sp.getBoolean(KEY_INITIALIZED, false)) {
                val json = sp.getString(KEY_PROVIDERS, null)
                if (json != null) {
                    try {
                        providers.clear()
                        providers.addAll(parseProvidersJson(json))
                    } catch (e: Exception) {
                        android.util.Log.e("ProviderRegistry", "Error loading from prefs: ${e.message}")
                        loadFromAssets(context)
                    }
                } else loadFromAssets(context)
            } else loadFromAssets(context)
            if (providers.isEmpty()) {
                android.util.Log.w("ProviderRegistry", "No providers loaded, falling back to assets")
                loadFromAssets(context)
            }
            initialized = true
        }
    }

    private fun loadFromAssets(context: Context) {
        try {
            val json = context.assets.open("setup.json").bufferedReader().use { it.readText() }
            val export = createAppGson().fromJson(json, ConfigExport::class.java)
            val defs = export.providerDefinitions
            if (defs.isNullOrEmpty()) return
            providers.clear()
            providers.addAll(defs.map { it.toAppService() })
            save()
        } catch (e: Exception) {
            android.util.Log.e("ProviderRegistry", "Error loading from assets: ${e.message}")
        }
    }

    private fun parseProvidersJson(json: String): List<AppService> {
        val defs: List<ProviderDefinition> = createAppGson().fromJson(json, providerListType)
        return defs.map { it.toAppService() }
    }

    fun getAll(): List<AppService> = providers.toList()
    fun findById(id: String): AppService? = providers.find { it.id == id }
    fun getCustomProviders(): List<ProviderDefinition> = providers.map { ProviderDefinition.fromAppService(it) }

    fun add(service: AppService) = synchronized(lock) { providers.add(service); save() }
    fun update(service: AppService) = synchronized(lock) {
        val i = providers.indexOfFirst { it.id == service.id }
        if (i >= 0) { providers[i] = service; save() }
    }
    fun remove(id: String) = synchronized(lock) { providers.removeAll { it.id == id }; save() }

    fun save() {
        val sp = prefs ?: return
        val json = createAppGson().toJson(providers.map { ProviderDefinition.fromAppService(it) })
        sp.edit { putString(KEY_PROVIDERS, json); putBoolean(KEY_INITIALIZED, true) }
    }

    fun resetToDefaults(context: Context) {
        initialized = false
        prefs?.edit { clear() }
        init(context)
    }

    fun ensureProviders(services: List<AppService>) = synchronized(lock) {
        var changed = false
        for (service in services) {
            if (findById(service.id) == null) { providers.add(service); changed = true }
        }
        if (changed) save()
    }
}

/**
 * JSON-serializable representation of an AppService provider definition.
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
    val defaultModelSource: String? = null,
    val endpointRules: List<EndpointRule>? = null
) {
    fun toAppService(): AppService = AppService(
        id = id, displayName = displayName, baseUrl = baseUrl, adminUrl = adminUrl ?: "",
        defaultModel = defaultModel, openRouterName = openRouterName,
        apiFormat = try { ApiFormat.valueOf(apiFormat ?: "OPENAI_COMPATIBLE") } catch (_: Exception) { ApiFormat.OPENAI_COMPATIBLE },
        chatPath = chatPath ?: "v1/chat/completions", modelsPath = modelsPath,
        prefsKey = prefsKey ?: id.lowercase(), seedFieldName = seedFieldName ?: "seed",
        supportsCitations = supportsCitations ?: false, supportsSearchRecency = supportsSearchRecency ?: false,
        extractApiCost = extractApiCost ?: false, costTicksDivisor = costTicksDivisor,
        modelListFormat = modelListFormat ?: "object", modelFilter = modelFilter,
        litellmPrefix = litellmPrefix, hardcodedModels = hardcodedModels,
        defaultModelSource = defaultModelSource, endpointRules = endpointRules ?: emptyList()
    )

    companion object {
        fun fromAppService(s: AppService) = ProviderDefinition(
            id = s.id, displayName = s.displayName, baseUrl = s.baseUrl, adminUrl = s.adminUrl,
            defaultModel = s.defaultModel, openRouterName = s.openRouterName,
            apiFormat = s.apiFormat.name, chatPath = s.chatPath, modelsPath = s.modelsPath,
            prefsKey = s.prefsKey, seedFieldName = s.seedFieldName,
            supportsCitations = s.supportsCitations, supportsSearchRecency = s.supportsSearchRecency,
            extractApiCost = s.extractApiCost, costTicksDivisor = s.costTicksDivisor,
            modelListFormat = s.modelListFormat, modelFilter = s.modelFilter,
            litellmPrefix = s.litellmPrefix, hardcodedModels = s.hardcodedModels,
            defaultModelSource = s.defaultModelSource, endpointRules = s.endpointRules.ifEmpty { null }
        )
    }
}
