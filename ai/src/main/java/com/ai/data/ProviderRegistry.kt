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

    /** Hostname → AppService lookup used by
     *  [ProviderThrottle.acquire] to resolve per-provider override
     *  values from a raw OkHttp request hostname. Rebuilt on every
     *  [save] so adding / removing / editing a provider takes effect
     *  on the next request. Both baseUrl and auxHosts contribute keys;
     *  on collision the first provider that claims a host wins. */
    @Volatile private var hostIndex: Map<String, AppService> = emptyMap()

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
                        AppLog.e("ProviderRegistry", "Error loading from prefs: ${e.message}")
                    }
                }
            }
            rebuildHostIndex()
            initialized = true
        }
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
                            AppLog.w("ProviderRegistry", "Skipped bundled provider ${def.id}: ${e.message}")
                        }
                    }
                }
                if (added > 0) save()
                added
            }
        } catch (e: Exception) {
            AppLog.w("ProviderRegistry", "importFromAsset($filename) failed: ${e.message}")
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
                        AppLog.w("ProviderRegistry", "Skipped imported provider ${def.id}: ${e.message}")
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
            AppLog.w("ProviderRegistry", "upsertFromJson failed: ${e.message}")
            -1
        }
    }

    private fun parseProvidersJson(json: String): List<AppService> {
        val defs: List<ProviderDefinition> = createAppGson().fromJson(json, providerListType)
        // Gson reflection bypasses Kotlin's null-safety checks, so a
        // malformed entry can deserialise with a null id / baseUrl.
        // Filter those out instead of letting them surface as an NPE
        // the first time someone reads `service.id.lowercase()` or
        // similar — a single bad entry should not corrupt the whole
        // registry. Logged so the issue is at least visible.
        return defs.mapNotNull { def ->
            @Suppress("USELESS_CAST")
            val id = def.id as String?
            @Suppress("USELESS_CAST")
            val baseUrl = def.baseUrl as String?
            if (id.isNullOrBlank() || baseUrl.isNullOrBlank()) {
                AppLog.w("ProviderRegistry",
                    "Skipping malformed provider entry (id=$id, baseUrl=$baseUrl)")
                null
            } else {
                try { def.toAppService() } catch (e: Exception) {
                    AppLog.w("ProviderRegistry", "Skipping provider $id — toAppService threw: ${e.message}")
                    null
                }
            }
        }
    }

    fun getAll(): List<AppService> = providers.toList()
    fun findById(id: String): AppService? = providers.find { it.id == id }
    fun getCustomProviders(): List<ProviderDefinition> = providers.map { ProviderDefinition.fromAppService(it) }

    /** Resolve a request hostname (from OkHttp's `request.url.host`)
     *  to the AppService that owns it, or null when the host doesn't
     *  belong to any registered provider (catalog fetches, pricing
     *  CDNs, etc.). Used by [ProviderThrottle] to pick up per-provider
     *  rate / concurrency overrides. */
    fun findByHost(host: String): AppService? {
        if (host.isBlank()) return null
        return hostIndex[host] ?: hostIndex[host.lowercase()]
    }

    /** Append [service] to the registry. The UI's Add Provider screen
     *  already checks for duplicate ids; this defensive check covers
     *  programmatic adds (import flows, restore, future callers) so a
     *  duplicate id can't sneak into the registry and shadow the
     *  original on every findById lookup. Returns true on success,
     *  false if an entry with the same id already exists. */
    fun add(service: AppService): Boolean = synchronized(lock) {
        if (providers.any { it.id == service.id }) {
            AppLog.w("ProviderRegistry",
                "Refusing to add duplicate provider id ${service.id}; existing entry kept")
            return@synchronized false
        }
        providers.add(service)
        save()
        AppLog.i("ProviderRegistry", "added ${service.id} (baseUrl=${service.baseUrl})")
        true
    }
    /** User-initiated update: replaces the entry by id and bumps
     *  [ProviderFieldTimestamps] for every field whose value
     *  changed. The Settings UI's edit form, the
     *  withDefaultModel / withModelSource helpers, and any
     *  programmatic per-field edit reach this path. Asset-driven
     *  changes go through [importFromAsset] / [upsertFromJson] /
     *  [syncFromAsset] instead so they don't poison the
     *  "user touched this" markers. */
    fun update(service: AppService) = synchronized(lock) {
        val i = providers.indexOfFirst { it.id == service.id }
        if (i >= 0) {
            val existing = providers[i]
            val changed = diffTrackedFields(existing, service)
            providers[i] = service
            save()
            if (changed.isNotEmpty()) {
                ProviderFieldTimestamps.bump(service.id, changed)
                AppLog.i("ProviderRegistry", "updated ${service.id} changed=${changed.joinToString(",")}")
            }
        }
    }
    fun remove(id: String) = synchronized(lock) {
        val removed = providers.removeAll { it.id == id }
        save()
        ProviderFieldTimestamps.clear(id)
        if (removed) AppLog.i("ProviderRegistry", "removed $id")
    }

    /** Reconcile the registry against bundled `assets/providers.json`.
     *  For each asset entry that already exists in the registry, we
     *  walk the tracked fields and pull the asset value into a field
     *  iff (a) it differs from the in-app value AND (b) the user has
     *  not edited that field (timestamp is null). User-edited fields
     *  are left alone. New asset entries are NOT appended here —
     *  that's [importFromAsset]'s job — so a brand-new bundled
     *  provider still requires the user to opt in.
     *
     *  Designed for app start; safe to call on any thread. Returns
     *  the count of providers that received at least one field
     *  update, or `-1` on parse / read failure. */
    fun syncFromAsset(context: Context, filename: String = "providers.json"): Int {
        return try {
            val json = context.assets.open(filename).bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(json) as? JsonObject ?: return -1
            val arr = root.getAsJsonArray("providers") ?: return -1
            val gson = createAppGson()
            val defs: List<ProviderDefinition> = gson.fromJson(arr, providerListType)
            synchronized(lock) {
                var changedCount = 0
                for (def in defs) {
                    val asset = try { def.toAppService() } catch (_: Exception) { continue }
                    val i = providers.indexOfFirst { it.id == asset.id }
                    if (i < 0) continue
                    val existing = providers[i]
                    val diff = diffTrackedFields(existing, asset)
                    if (diff.isEmpty()) continue
                    val take = diff.filter { ProviderFieldTimestamps.get(asset.id, it) == null }.toSet()
                    if (take.isEmpty()) continue
                    providers[i] = mergeTrackedFields(existing, asset, take)
                    changedCount++
                    AppLog.i(
                        "ProviderRegistry",
                        "syncFromAsset: ${asset.id} pulled ${take.joinToString()}"
                    )
                }
                if (changedCount > 0) save()
                changedCount
            }
        } catch (e: Exception) {
            AppLog.w("ProviderRegistry", "syncFromAsset failed: ${e.message}")
            -1
        }
    }

    fun save() {
        val sp = prefs ?: return
        val json = createAppGson().toJson(providers.map { ProviderDefinition.fromAppService(it) })
        sp.edit { putString(KEY_PROVIDERS, json); putBoolean(KEY_INITIALIZED, true) }
        rebuildHostIndex()
        // A provider's throttle override may have changed — drop any
        // stale per-host semaphores so the next acquire uses the new
        // caps. Cheap (no-op when nothing throttle-related changed).
        ProviderThrottle.resetForNewLimits()
    }

    private fun rebuildHostIndex() {
        val map = LinkedHashMap<String, AppService>()
        for (svc in providers) {
            urlHost(svc.baseUrl)?.let { h -> map.putIfAbsent(h, svc) }
            svc.auxHosts.forEach { aux -> urlHost(aux)?.let { h -> map.putIfAbsent(h, svc) } }
        }
        hostIndex = map
        AppLog.d("ProviderRegistry", "host index rebuilt — ${map.size} host(s) across ${providers.size} provider(s)")
    }

    private fun urlHost(raw: String): String? = try {
        val s = raw.trim()
        if (s.isBlank()) null else {
            val withScheme = if (s.contains("://")) s else "https://$s"
            java.net.URI(withScheme).host?.lowercase()?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) { null }

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
        ProviderFieldTimestamps.clearAll()
        init(context)
    }

    /** Wipe the current registry (in-memory + persisted prefs) and
     *  re-seed from `assets/providers.json`. Driven by the "back to
     *  assets/providers.json" button on Housekeeping → Reset, for when
     *  the user wants to discard hand-edited definitions and pick up
     *  the bundled values verbatim. Returns the count of providers
     *  loaded on success, or `-1` if the asset couldn't be parsed
     *  (registry is left empty in that case — caller should surface
     *  the error). */
    fun restartFromAsset(context: Context, filename: String = "providers.json"): Int {
        synchronized(lock) {
            providers.clear()
            initialized = false
        }
        prefs?.edit { clear() }
        ProviderFieldTimestamps.clearAll()
        init(context)
        return importFromAsset(context, filename)
    }

    fun ensureProviders(services: List<AppService>) = synchronized(lock) {
        var changed = false
        for (service in services) {
            if (findById(service.id) == null) { providers.add(service); changed = true }
        }
        if (changed) save()
    }

    // ===== Field tracking (per-provider, per-field timestamps) =====
    //
    // The set below mirrors every `assets/providers.json` field on
    // AppService — adding a new field to that catalog requires adding
    // it here AND in [appServiceFieldMap] / [mergeTrackedFields] so
    // user edits to the new field stick across asset-sync. The `id` is
    // intentionally excluded — it's an immutable identity, not a
    // value the user edits.
    internal val TRACKED_FIELDS: Set<String> = setOf(
        "baseUrl", "adminUrl", "defaultModel", "openRouterName", "apiFormat",
        "typePaths", "modelsPath", "seedFieldName", "supportsCitations",
        "supportsSearchRecency", "extractApiCost", "costTicksDivisor",
        "modelListFormat", "modelFilter", "litellmPrefix", "hardcodedModels",
        "defaultModelSource", "auxHosts", "nativeRerankUrl",
        "nativeModerationUrl", "nativeCapabilityUrl", "pricingFromModelList",
        "crossProviderModelList", "mergeHardcodedModels",
        "externalReasoningSignalUntrusted", "responsesApiPatterns",
        "reasoningModelPatterns", "reasoningEffortAcceptPatterns",
        "webSearchModelPatterns", "adaptiveThinkingPatterns",
        "maxTokensDefaults", "builtInEndpoints",
        "maxCallsPerProviderPerMinute", "maxConcurrentCallsPerProvider",
        "maxRetriesOn429", "retryBackoffMs429",
        "maxRetriesOn529", "retryBackoffMs529"
    )

    private fun appServiceFieldMap(s: AppService): Map<String, Any?> = mapOf(
        "baseUrl" to s.baseUrl,
        "adminUrl" to s.adminUrl,
        "defaultModel" to s.defaultModel,
        "openRouterName" to s.openRouterName,
        "apiFormat" to s.apiFormat,
        "typePaths" to s.typePaths,
        "modelsPath" to s.modelsPath,
        "seedFieldName" to s.seedFieldName,
        "supportsCitations" to s.supportsCitations,
        "supportsSearchRecency" to s.supportsSearchRecency,
        "extractApiCost" to s.extractApiCost,
        "costTicksDivisor" to s.costTicksDivisor,
        "modelListFormat" to s.modelListFormat,
        "modelFilter" to s.modelFilter,
        "litellmPrefix" to s.litellmPrefix,
        "hardcodedModels" to s.hardcodedModels,
        "defaultModelSource" to s.defaultModelSource,
        "auxHosts" to s.auxHosts,
        "nativeRerankUrl" to s.nativeRerankUrl,
        "nativeModerationUrl" to s.nativeModerationUrl,
        "nativeCapabilityUrl" to s.nativeCapabilityUrl,
        "pricingFromModelList" to s.pricingFromModelList,
        "crossProviderModelList" to s.crossProviderModelList,
        "mergeHardcodedModels" to s.mergeHardcodedModels,
        "externalReasoningSignalUntrusted" to s.externalReasoningSignalUntrusted,
        "responsesApiPatterns" to s.responsesApiPatterns,
        "reasoningModelPatterns" to s.reasoningModelPatterns,
        "reasoningEffortAcceptPatterns" to s.reasoningEffortAcceptPatterns,
        "webSearchModelPatterns" to s.webSearchModelPatterns,
        "adaptiveThinkingPatterns" to s.adaptiveThinkingPatterns,
        "maxTokensDefaults" to s.maxTokensDefaults,
        "builtInEndpoints" to s.builtInEndpoints,
        "maxCallsPerProviderPerMinute" to s.maxCallsPerProviderPerMinute,
        "maxConcurrentCallsPerProvider" to s.maxConcurrentCallsPerProvider,
        "maxRetriesOn429" to s.maxRetriesOn429,
        "retryBackoffMs429" to s.retryBackoffMs429,
        "maxRetriesOn529" to s.maxRetriesOn529,
        "retryBackoffMs529" to s.retryBackoffMs529
    )

    private fun diffTrackedFields(a: AppService, b: AppService): Set<String> {
        val ma = appServiceFieldMap(a)
        val mb = appServiceFieldMap(b)
        return TRACKED_FIELDS.filter { ma[it] != mb[it] }.toSet()
    }

    /** Build a new AppService where the listed [take] fields come
     *  from [asset] and every other tracked field comes from
     *  [existing]. The id stays from [existing] (immutable). */
    private fun mergeTrackedFields(existing: AppService, asset: AppService, take: Set<String>): AppService {
        fun <T> p(name: String, e: T, a: T): T = if (name in take) a else e
        return AppService(
            id = existing.id,
            baseUrl = p("baseUrl", existing.baseUrl, asset.baseUrl),
            adminUrl = p("adminUrl", existing.adminUrl, asset.adminUrl),
            defaultModel = p("defaultModel", existing.defaultModel, asset.defaultModel),
            openRouterName = p("openRouterName", existing.openRouterName, asset.openRouterName),
            apiFormat = p("apiFormat", existing.apiFormat, asset.apiFormat),
            typePaths = p("typePaths", existing.typePaths, asset.typePaths),
            modelsPath = p("modelsPath", existing.modelsPath, asset.modelsPath),
            seedFieldName = p("seedFieldName", existing.seedFieldName, asset.seedFieldName),
            supportsCitations = p("supportsCitations", existing.supportsCitations, asset.supportsCitations),
            supportsSearchRecency = p("supportsSearchRecency", existing.supportsSearchRecency, asset.supportsSearchRecency),
            extractApiCost = p("extractApiCost", existing.extractApiCost, asset.extractApiCost),
            costTicksDivisor = p("costTicksDivisor", existing.costTicksDivisor, asset.costTicksDivisor),
            modelListFormat = p("modelListFormat", existing.modelListFormat, asset.modelListFormat),
            modelFilter = p("modelFilter", existing.modelFilter, asset.modelFilter),
            litellmPrefix = p("litellmPrefix", existing.litellmPrefix, asset.litellmPrefix),
            hardcodedModels = p("hardcodedModels", existing.hardcodedModels, asset.hardcodedModels),
            defaultModelSource = p("defaultModelSource", existing.defaultModelSource, asset.defaultModelSource),
            auxHosts = p("auxHosts", existing.auxHosts, asset.auxHosts),
            nativeRerankUrl = p("nativeRerankUrl", existing.nativeRerankUrl, asset.nativeRerankUrl),
            nativeModerationUrl = p("nativeModerationUrl", existing.nativeModerationUrl, asset.nativeModerationUrl),
            nativeCapabilityUrl = p("nativeCapabilityUrl", existing.nativeCapabilityUrl, asset.nativeCapabilityUrl),
            pricingFromModelList = p("pricingFromModelList", existing.pricingFromModelList, asset.pricingFromModelList),
            crossProviderModelList = p("crossProviderModelList", existing.crossProviderModelList, asset.crossProviderModelList),
            mergeHardcodedModels = p("mergeHardcodedModels", existing.mergeHardcodedModels, asset.mergeHardcodedModels),
            externalReasoningSignalUntrusted = p("externalReasoningSignalUntrusted", existing.externalReasoningSignalUntrusted, asset.externalReasoningSignalUntrusted),
            responsesApiPatterns = p("responsesApiPatterns", existing.responsesApiPatterns, asset.responsesApiPatterns),
            reasoningModelPatterns = p("reasoningModelPatterns", existing.reasoningModelPatterns, asset.reasoningModelPatterns),
            reasoningEffortAcceptPatterns = p("reasoningEffortAcceptPatterns", existing.reasoningEffortAcceptPatterns, asset.reasoningEffortAcceptPatterns),
            webSearchModelPatterns = p("webSearchModelPatterns", existing.webSearchModelPatterns, asset.webSearchModelPatterns),
            adaptiveThinkingPatterns = p("adaptiveThinkingPatterns", existing.adaptiveThinkingPatterns, asset.adaptiveThinkingPatterns),
            maxTokensDefaults = p("maxTokensDefaults", existing.maxTokensDefaults, asset.maxTokensDefaults),
            builtInEndpoints = p("builtInEndpoints", existing.builtInEndpoints, asset.builtInEndpoints),
            maxCallsPerProviderPerMinute = p("maxCallsPerProviderPerMinute", existing.maxCallsPerProviderPerMinute, asset.maxCallsPerProviderPerMinute),
            maxConcurrentCallsPerProvider = p("maxConcurrentCallsPerProvider", existing.maxConcurrentCallsPerProvider, asset.maxConcurrentCallsPerProvider),
            maxRetriesOn429 = p("maxRetriesOn429", existing.maxRetriesOn429, asset.maxRetriesOn429),
            retryBackoffMs429 = p("retryBackoffMs429", existing.retryBackoffMs429, asset.retryBackoffMs429),
            maxRetriesOn529 = p("maxRetriesOn529", existing.maxRetriesOn529, asset.maxRetriesOn529),
            retryBackoffMs529 = p("retryBackoffMs529", existing.retryBackoffMs529, asset.retryBackoffMs529)
        )
    }
}

/** Structured model-name matcher used by the per-provider pattern
 *  fields on [ProviderDefinition] / [AppService]. At least one of
 *  `exact` / `prefix` / `contains` / `suffix` must be non-null; an
 *  all-null spec never matches. Match runs against
 *  `modelId.lowercase()` so JSON values can stay in canonical
 *  lowercase. When multiple parts are set they must ALL match
 *  (intersection — e.g. `prefix:"grok-4-" + contains:"reasoning"`
 *  matches only the grok-4 reasoning variants). */
data class ModelPattern(
    val exact: String? = null,
    val prefix: String? = null,
    val contains: String? = null,
    val suffix: String? = null
) {
    fun matches(modelId: String): Boolean {
        if (exact == null && prefix == null && contains == null && suffix == null) return false
        val id = modelId.lowercase()
        if (exact != null && id != exact) return false
        if (prefix != null && !id.startsWith(prefix)) return false
        if (contains != null && contains !in id) return false
        if (suffix != null && !id.endsWith(suffix)) return false
        return true
    }
}

/** True when any pattern in this list matches [modelId]. Null/empty
 *  list returns false — "no patterns declared" means "feature off
 *  for this provider". */
fun List<ModelPattern>?.anyMatches(modelId: String): Boolean =
    !this.isNullOrEmpty() && this.any { it.matches(modelId) }

/** Per-family default max_tokens entry used by Anthropic dispatch. */
data class MaxTokensRule(val pattern: ModelPattern, val maxTokens: Int)

/** A single built-in endpoint a provider exposes (e.g. OpenAI's
 *  Chat Completions vs Responses API). The user's effective endpoint
 *  list comes from the provider config; this is the wire / persisted
 *  form. */
data class Endpoint(val id: String, val name: String, val url: String, val isDefault: Boolean = false)

/** Resolve the highest-priority [MaxTokensRule.maxTokens] for [modelId],
 *  or null when no rule matches — caller falls back to its own default. */
fun List<MaxTokensRule>?.resolveMaxTokens(modelId: String): Int? =
    this?.firstOrNull { it.pattern.matches(modelId) }?.maxTokens

/**
 * JSON-serializable representation of an AppService provider definition.
 */
data class ProviderDefinition(
    /** Stable identifier + UI label. Pre-unification builds carried
     *  three fields (id / displayName / prefsKey); the id-unification
     *  refactor collapsed them. Legacy persisted entries with a
     *  `displayName` field present are remapped at restore / startup
     *  time by [com.ai.ui.settings.SettingsPreferences.migrateLegacyProviderIds]
     *  before this deserialiser is reached. */
    val id: String,
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
    /** Alternate hostnames the provider's API uses besides its
     *  baseUrl host (e.g. Cohere's `api.cohere.com` for the native
     *  rerank + capability endpoints). Used by trace categorisation. */
    val auxHosts: List<String>? = null,
    /** Full URL the rerank dispatcher POSTs to. Null → provider has
     *  no native rerank API; the user is told to pick a chat model. */
    val nativeRerankUrl: String? = null,
    /** Full URL the moderation dispatcher POSTs to. Null → provider
     *  has no native moderation API; user told to pick a Mistral
     *  moderation model. */
    val nativeModerationUrl: String? = null,
    /** Full URL of a Cohere-shaped `/v1/models` capability listing
     *  (with `endpoints` / `supports_vision` / `context_length`).
     *  Set on providers whose OpenAI-compat shim strips that data
     *  but a separate native host returns it. */
    val nativeCapabilityUrl: String? = null,
    /** When true the provider's `/v1/models` response carries
     *  authoritative pricing (input/output per 1M tokens) and the
     *  fetcher harvests it into PricingCache as a self-report tier. */
    val pricingFromModelList: Boolean? = null,
    /** When true the provider's `/v1/models` response carries enough
     *  metadata (architecture / modality / pricing) to drive pricing
     *  + type fan-out into every other provider via the
     *  openRouterName prefix. OpenRouter is the canonical example. */
    val crossProviderModelList: Boolean? = null,
    /** When true `withModels` unions the persisted hardcodedModels
     *  with the API list so the picker still shows ids the provider's
     *  /models endpoint omits (OpenAI's TTS / image / moderation
     *  endpoints aren't in /v1/models). */
    val mergeHardcodedModels: Boolean? = null,
    /** When true the model fetcher's `reasoning: true` signal from
     *  the provider's metadata is ignored — the model's reasoning
     *  capability is decided exclusively by [reasoningModelPatterns]
     *  + [reasoningEffortAcceptPatterns]. xAI uses this because some
     *  of its always-on reasoning models reject `reasoning_effort`. */
    val externalReasoningSignalUntrusted: Boolean? = null,
    /** Patterns that route a model to the OpenAI-style Responses API
     *  instead of Chat Completions. */
    val responsesApiPatterns: List<ModelPattern>? = null,
    /** Patterns that gate the 🧠 reasoning badge + the thinking
     *  dispatch path. */
    val reasoningModelPatterns: List<ModelPattern>? = null,
    /** Patterns that gate sending `reasoning_effort` in the request
     *  body. When null, falls back to [reasoningModelPatterns]. xAI
     *  narrows it to controllable variants only. */
    val reasoningEffortAcceptPatterns: List<ModelPattern>? = null,
    /** Patterns that gate the 🌐 web-search tool descriptor. */
    val webSearchModelPatterns: List<ModelPattern>? = null,
    /** Patterns that opt in to Anthropic's adaptive-thinking request
     *  shape (claude-opus-4-7 family). */
    val adaptiveThinkingPatterns: List<ModelPattern>? = null,
    /** Per-family default max_tokens, evaluated top-down. First
     *  matching rule wins. Fallback (no match) is provider-specific
     *  and lives in code. */
    val maxTokensDefaults: List<MaxTokensRule>? = null,
    /** Built-in endpoints the user can pick between (e.g. OpenAI's
     *  Chat vs Responses). Replaces the legacy
     *  `Settings.BUILT_IN_ENDPOINTS` map. */
    val builtInEndpoints: List<Endpoint>? = null,
    /** Per-provider override for the global per-minute rate cap.
     *  Null → inherit
     *  [com.ai.viewmodel.GeneralSettings.maxCallsPerProviderPerMinute]. */
    val maxCallsPerProviderPerMinute: Int? = null,
    /** Per-provider override for the global concurrency cap. Null →
     *  inherit
     *  [com.ai.viewmodel.GeneralSettings.maxConcurrentCallsPerProvider]. */
    val maxConcurrentCallsPerProvider: Int? = null,
    /** Per-provider override for the 429-retry cap. Null → inherit
     *  [com.ai.viewmodel.GeneralSettings.maxRetriesOn429]. */
    val maxRetriesOn429: Int? = null,
    /** Per-provider override for the wait between 429 retries
     *  (milliseconds). Null → inherit
     *  [com.ai.viewmodel.GeneralSettings.retryBackoffMs429]. */
    val retryBackoffMs429: Long? = null,
    /** Per-provider override for the 529 (server overloaded) retry
     *  cap. Null → inherit
     *  [com.ai.viewmodel.GeneralSettings.maxRetriesOn529]. */
    val maxRetriesOn529: Int? = null,
    /** Per-provider override for the wait between 529 retries
     *  (milliseconds). Null → inherit
     *  [com.ai.viewmodel.GeneralSettings.retryBackoffMs529]. */
    val retryBackoffMs529: Long? = null,
    /** When true the bundle seeds `providerStates[id]="inactive"` on
     *  first install so the provider shows 💤 by default. Existing
     *  installs with an API key already set are left alone. */
    val defaultInactive: Boolean? = null,
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
            id = id, baseUrl = baseUrl, adminUrl = adminUrl ?: "",
            defaultModel = defaultModel, openRouterName = openRouterName,
            apiFormat = try { ApiFormat.valueOf(apiFormat ?: "OPENAI_COMPATIBLE") } catch (_: Exception) { ApiFormat.OPENAI_COMPATIBLE },
            typePaths = paths,
            modelsPath = modelsPath,
            seedFieldName = seedFieldName ?: "seed",
            supportsCitations = supportsCitations ?: false, supportsSearchRecency = supportsSearchRecency ?: false,
            extractApiCost = extractApiCost ?: false, costTicksDivisor = costTicksDivisor,
            modelListFormat = modelListFormat ?: "object", modelFilter = modelFilter,
            litellmPrefix = litellmPrefix, hardcodedModels = hardcodedModels,
            defaultModelSource = defaultModelSource,
            auxHosts = auxHosts ?: emptyList(),
            nativeRerankUrl = nativeRerankUrl,
            nativeModerationUrl = nativeModerationUrl,
            nativeCapabilityUrl = nativeCapabilityUrl,
            pricingFromModelList = pricingFromModelList ?: false,
            crossProviderModelList = crossProviderModelList ?: false,
            mergeHardcodedModels = mergeHardcodedModels ?: false,
            externalReasoningSignalUntrusted = externalReasoningSignalUntrusted ?: false,
            responsesApiPatterns = responsesApiPatterns ?: emptyList(),
            reasoningModelPatterns = reasoningModelPatterns ?: emptyList(),
            reasoningEffortAcceptPatterns = reasoningEffortAcceptPatterns,
            webSearchModelPatterns = webSearchModelPatterns ?: emptyList(),
            adaptiveThinkingPatterns = adaptiveThinkingPatterns ?: emptyList(),
            maxTokensDefaults = maxTokensDefaults ?: emptyList(),
            builtInEndpoints = builtInEndpoints ?: emptyList(),
            maxCallsPerProviderPerMinute = maxCallsPerProviderPerMinute?.takeIf { it > 0 },
            maxConcurrentCallsPerProvider = maxConcurrentCallsPerProvider?.takeIf { it > 0 },
            maxRetriesOn429 = maxRetriesOn429?.takeIf { it >= 0 },
            retryBackoffMs429 = retryBackoffMs429?.takeIf { it > 0 },
            maxRetriesOn529 = maxRetriesOn529?.takeIf { it >= 0 },
            retryBackoffMs529 = retryBackoffMs529?.takeIf { it > 0 },
            defaultInactive = defaultInactive ?: false
        )
    }

    companion object {
        fun fromAppService(s: AppService) = ProviderDefinition(
            id = s.id, baseUrl = s.baseUrl, adminUrl = s.adminUrl,
            defaultModel = s.defaultModel, openRouterName = s.openRouterName,
            apiFormat = s.apiFormat.name,
            typePaths = s.typePaths.takeIf { it.isNotEmpty() },
            // Legacy fields no longer written — typePaths is canonical now.
            chatPath = null, responsesPath = null,
            modelsPath = s.modelsPath,
            seedFieldName = s.seedFieldName,
            supportsCitations = s.supportsCitations, supportsSearchRecency = s.supportsSearchRecency,
            extractApiCost = s.extractApiCost, costTicksDivisor = s.costTicksDivisor,
            modelListFormat = s.modelListFormat, modelFilter = s.modelFilter,
            litellmPrefix = s.litellmPrefix, hardcodedModels = s.hardcodedModels,
            defaultModelSource = s.defaultModelSource,
            auxHosts = s.auxHosts.takeIf { it.isNotEmpty() },
            nativeRerankUrl = s.nativeRerankUrl,
            nativeModerationUrl = s.nativeModerationUrl,
            nativeCapabilityUrl = s.nativeCapabilityUrl,
            pricingFromModelList = s.pricingFromModelList.takeIf { it },
            crossProviderModelList = s.crossProviderModelList.takeIf { it },
            mergeHardcodedModels = s.mergeHardcodedModels.takeIf { it },
            externalReasoningSignalUntrusted = s.externalReasoningSignalUntrusted.takeIf { it },
            responsesApiPatterns = s.responsesApiPatterns.takeIf { it.isNotEmpty() },
            reasoningModelPatterns = s.reasoningModelPatterns.takeIf { it.isNotEmpty() },
            reasoningEffortAcceptPatterns = s.reasoningEffortAcceptPatterns,
            webSearchModelPatterns = s.webSearchModelPatterns.takeIf { it.isNotEmpty() },
            adaptiveThinkingPatterns = s.adaptiveThinkingPatterns.takeIf { it.isNotEmpty() },
            maxTokensDefaults = s.maxTokensDefaults.takeIf { it.isNotEmpty() },
            builtInEndpoints = s.builtInEndpoints.takeIf { it.isNotEmpty() },
            maxCallsPerProviderPerMinute = s.maxCallsPerProviderPerMinute,
            maxConcurrentCallsPerProvider = s.maxConcurrentCallsPerProvider,
            maxRetriesOn429 = s.maxRetriesOn429,
            retryBackoffMs429 = s.retryBackoffMs429,
            maxRetriesOn529 = s.maxRetriesOn529,
            retryBackoffMs529 = s.retryBackoffMs529,
            defaultInactive = s.defaultInactive.takeIf { it }
        )
    }
}
