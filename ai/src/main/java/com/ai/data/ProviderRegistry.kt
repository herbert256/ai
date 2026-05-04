package com.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * Mutable registry of AI service providers.
 *
 * Persisted to SharedPreferences. A fresh install starts with an empty
 * registry — the bundled `assets/providers.json` catalog is loaded on
 * demand by [importFromAsset], wired to a button on the Providers
 * screen. Existing installs upgrade transparently because their
 * registry prefs already carry the providers seeded by older builds.
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
                    }
                }
            }
            // Field-level fix-ups for known shipped-bad values from the
            // legacy setup.json bundle. Idempotent — the condition
            // fails on subsequent boots once the fix has been applied,
            // and on a fresh install it never matches.
            applyFieldBugfixMigrations()
            initialized = true
        }
    }

    /** Patch persisted ProviderDefinition fields for known shipped-bad
     *  values that the user can't fix via the UI. Each migration runs
     *  every startup but only writes when the condition matches, so
     *  re-applying is harmless once a user has corrected the field
     *  manually (the condition won't match anymore). */
    private fun applyFieldBugfixMigrations() {
        var changed = false
        // Perplexity: the original bundle shipped modelsPath="models",
        // which 404s. The actual endpoint is /v1/models (verified by
        // 401 vs 404 probe). Patch any persisted entry that still has
        // the bad value.
        val pp = providers.indexOfFirst { it.id == "PERPLEXITY" }
        if (pp >= 0 && providers[pp].modelsPath == "models") {
            val def = ProviderDefinition.fromAppService(providers[pp]).copy(modelsPath = "v1/models")
            providers[pp] = def.toAppService()
            changed = true
        }
        if (changed) save()
    }

    /** On-demand import of `assets/providers.json` (or any asset with
     *  the same `{ "providers": [<ProviderDefinition>] }` shape). New
     *  entries — by id, case-sensitive — are appended; existing rows
     *  are left strictly alone, no field overwrites. Returns the count
     *  of newly added providers, or `-1` on parse / read failure so
     *  the UI can distinguish "nothing new" from "broken bundle". */
    fun importFromAsset(context: Context, filename: String = "providers.json"): Int {
        return try {
            val json = context.assets.open(filename).bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(json) as? JsonObject ?: return -1
            val arr = root.getAsJsonArray("providers") ?: return -1
            val gson = createAppGson()
            val defs: List<ProviderDefinition> = gson.fromJson(arr, providerListType)
            synchronized(lock) {
                var added = 0
                for (def in defs) {
                    if (providers.none { it.id == def.id }) {
                        try {
                            providers.add(def.toAppService())
                            added++
                        } catch (e: Exception) {
                            android.util.Log.w("ProviderRegistry", "Skipped bundled provider ${def.id}: ${e.message}")
                        }
                    }
                }
                if (added > 0) save()
                added
            }
        } catch (e: Exception) {
            android.util.Log.w("ProviderRegistry", "importFromAsset($filename) failed: ${e.message}")
            -1
        }
    }

    /** Upsert from a user-picked JSON blob shaped like the bundled
     *  `assets/providers.json` (top-level `{ "providers": [...] }`).
     *  For each entry: replace the existing provider with the same id,
     *  or append if absent. Per-provider API keys / model lists live
     *  in [com.ai.model.Settings], not here, so they stay untouched.
     *  Returns the count of (added + updated) rows, or `-1` on parse
     *  failure. */
    fun upsertFromJson(json: String): Int {
        return try {
            val root = JsonParser.parseString(json) as? JsonObject ?: return -1
            val arr = root.getAsJsonArray("providers") ?: return -1
            val gson = createAppGson()
            val defs: List<ProviderDefinition> = gson.fromJson(arr, providerListType)
            synchronized(lock) {
                var changed = 0
                for (def in defs) {
                    val service = try { def.toAppService() } catch (e: Exception) {
                        android.util.Log.w("ProviderRegistry", "Skipped imported provider ${def.id}: ${e.message}")
                        continue
                    }
                    val i = providers.indexOfFirst { it.id == service.id }
                    if (i >= 0) providers[i] = service else providers.add(service)
                    changed++
                }
                if (changed > 0) save()
                changed
            }
        } catch (e: Exception) {
            android.util.Log.w("ProviderRegistry", "upsertFromJson failed: ${e.message}")
            -1
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
        // Drop the in-memory list as well as the persisted prefs.
        // Otherwise importFromAsset's "skip if id already present"
        // check sees the old entries and adds nothing — the registry
        // keeps the pre-reset providers, just dissociated from disk.
        synchronized(lock) {
            providers.clear()
            initialized = false
        }
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
    /** Canonical per-type path map. */
    val typePaths: Map<String, String>? = null,
    /** Legacy field — folded into typePaths during deserialization for existing prefs / setup.json. */
    val chatPath: String? = null,
    /** Legacy field — folded into typePaths during deserialization. */
    val responsesPath: String? = null,
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
    /** Deprecated — kept on the deserialization shape so old prefs / setup.json
     *  files with the field still parse, but ignored at dispatch time
     *  (ModelType.infer drives Responses-vs-Chat routing now). Will be
     *  removed in a future export-version bump. */
    @Suppress("unused")
    val endpointRules: List<Map<String, String>>? = null
) {
    fun toAppService(): AppService {
        // Migrate legacy chatPath/responsesPath into the typePaths map. Explicit
        // map entries always win; legacy fields only fill in if the corresponding
        // type isn't already declared.
        val paths = (typePaths ?: emptyMap()).toMutableMap()
        chatPath?.takeIf { it.isNotBlank() }?.let { paths.putIfAbsent(ModelType.CHAT, it) }
        responsesPath?.takeIf { it.isNotBlank() }?.let { paths.putIfAbsent(ModelType.RESPONSES, it) }
        return AppService(
            id = id, displayName = displayName, baseUrl = baseUrl, adminUrl = adminUrl ?: "",
            defaultModel = defaultModel, openRouterName = openRouterName,
            apiFormat = try { ApiFormat.valueOf(apiFormat ?: "OPENAI_COMPATIBLE") } catch (_: Exception) { ApiFormat.OPENAI_COMPATIBLE },
            typePaths = paths,
            modelsPath = modelsPath,
            prefsKey = prefsKey ?: id.lowercase(), seedFieldName = seedFieldName ?: "seed",
            supportsCitations = supportsCitations ?: false, supportsSearchRecency = supportsSearchRecency ?: false,
            extractApiCost = extractApiCost ?: false, costTicksDivisor = costTicksDivisor,
            modelListFormat = modelListFormat ?: "object", modelFilter = modelFilter,
            litellmPrefix = litellmPrefix, hardcodedModels = hardcodedModels,
            defaultModelSource = defaultModelSource
        )
    }

    companion object {
        fun fromAppService(s: AppService) = ProviderDefinition(
            id = s.id, displayName = s.displayName, baseUrl = s.baseUrl, adminUrl = s.adminUrl,
            defaultModel = s.defaultModel, openRouterName = s.openRouterName,
            apiFormat = s.apiFormat.name,
            typePaths = s.typePaths.takeIf { it.isNotEmpty() },
            // Legacy fields no longer written — typePaths is canonical now.
            chatPath = null, responsesPath = null,
            modelsPath = s.modelsPath,
            prefsKey = s.prefsKey, seedFieldName = s.seedFieldName,
            supportsCitations = s.supportsCitations, supportsSearchRecency = s.supportsSearchRecency,
            extractApiCost = s.extractApiCost, costTicksDivisor = s.costTicksDivisor,
            modelListFormat = s.modelListFormat, modelFilter = s.modelFilter,
            litellmPrefix = s.litellmPrefix, hardcodedModels = s.hardcodedModels,
            defaultModelSource = s.defaultModelSource
        )
    }
}
