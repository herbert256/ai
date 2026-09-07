package com.ai.data.preferences

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ai.data.ReportCostJournal
import com.ai.data.AppLog
import com.ai.data.ApiTracer
import com.ai.data.AppService
import com.ai.data.PricingCache
import com.ai.data.ReportApiCallCost
import com.ai.data.ReportStorage
import com.ai.data.TokenUsage
import com.ai.data.barTitle
import com.ai.data.backfillCachedTokenLimits
import com.ai.data.createAppGson
import com.ai.data.normalizeUsageKind
import com.ai.data.writeTextAtomic
import com.ai.model.*
import com.ai.ui.shared.AppColors
import com.ai.viewmodel.AppHomeMode
import com.ai.viewmodel.GeneralSettings
import com.ai.viewmodel.ModelNameLayout
import com.ai.viewmodel.PromptHistoryEntry
import com.ai.viewmodel.DEFAULT_UI_BUTTON_BACKGROUND_ARGB
import com.ai.viewmodel.DEFAULT_UI_CARD_BACKGROUND_ARGB
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages all settings persistence via SharedPreferences and file-based storage.
 */
class SettingsPreferences(private val prefs: SharedPreferences, private val filesDir: File? = null) {

    private val gson = createAppGson()
    init { if (filesDir != null) scheduleUsageStatsFlush() }

    // Per-domain persistence is being split out of this class (audit D01). The
    // prompt-history list now lives in its own store; the methods below delegate
    // so callers are unchanged.
    private val promptHistoryStore = PromptHistoryStore(filesDir)

    private object TypeTokens {
        val listStringType: Type = object : TypeToken<List<String>>() {}.type
        val listAgentType: Type = object : TypeToken<List<Agent>>() {}.type
        val listFlockType: Type = object : TypeToken<List<Flock>>() {}.type
        val listSwarmType: Type = object : TypeToken<List<Swarm>>() {}.type
        val listParametersType: Type = object : TypeToken<List<Parameters>>() {}.type
        val listSystemPromptType: Type = object : TypeToken<List<SystemPrompt>>() {}.type
        val listInternalPromptType: Type = object : TypeToken<List<InternalPrompt>>() {}.type
        val listExamplePromptType: Type = object : TypeToken<List<ExamplePrompt>>() {}.type
        val listModelTypeOverrideType: Type = object : TypeToken<List<ModelTypeOverride>>() {}.type
        val listBlockedModelType: Type = object : TypeToken<List<BlockedModel>>() {}.type
        val listTestExcludedModelType: Type = object : TypeToken<List<TestExcludedModel>>() {}.type
        val listInaccessibleModelType: Type = object : TypeToken<List<InaccessibleModel>>() {}.type
        val listDefaultMetaItemType: Type = object : TypeToken<List<DefaultMetaItem>>() {}.type
        val listUsageCategoryStatsType: Type = object : TypeToken<List<UsageCategoryStats>>() {}.type
        val listUsageReportStatsType: Type = object : TypeToken<List<UsageReportStats>>() {}.type
        val mapEndpointsType: Type = object : TypeToken<Map<String, List<Endpoint>>>() {}.type
        val mapStringStringType: Type = object : TypeToken<Map<String, String>>() {}.type
        val mapStringIntType: Type = object : TypeToken<Map<String, Int>>() {}.type
        val listUsageStatsType: Type = object : TypeToken<List<UsageStats>>() {}.type
    }

    // ===== General Settings =====

    fun loadGeneralSettings(): GeneralSettings {
        val typePathsJson = prefs.getString(KEY_DEFAULT_TYPE_PATHS, null)
        val defaultTypePaths: Map<String, String> = typePathsJson?.let {
            try {
                // Use a concrete TypeToken<Map<String,String>> so Gson
                // coerces values to String at parse time, instead of the
                // unchecked Map::class.java cast that defers a possible
                // ClassCastException to first use.
                gson.fromJson<Map<String, String>>(it, TypeTokens.mapStringStringType) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        } ?: emptyMap()
        val layoutName = prefs.getString(KEY_MODEL_NAME_LAYOUT, null)
        val modelNameLayout = layoutName?.let {
            try { ModelNameLayout.valueOf(it) } catch (_: Exception) { null }
        } ?: ModelNameLayout.MODEL_ONLY
        val homeModeName = prefs.getString(KEY_APP_HOME, null)
        val appHomeMode = homeModeName?.let {
            try { AppHomeMode.valueOf(it) } catch (_: Exception) { null }
        } ?: AppHomeMode.HOME_BAR
        val titleModeName = prefs.getString(KEY_REPORT_TITLE_MODE, null)
        val reportTitleMode = titleModeName?.let {
            try { com.ai.viewmodel.ReportTitleMode.valueOf(it) } catch (_: Exception) { null }
        } ?: com.ai.viewmodel.ReportTitleMode.AI
        // sanitized(): newer MetadataIcons fields are absent from older stored
        // JSON; Gson leaves them null (it bypasses the Kotlin constructor), so
        // backfill the factory defaults before the bars read them.
        val metadataIcons: com.ai.data.MetadataIcons = (prefs.getString(KEY_METADATA_ICONS, null)?.let {
            try { gson.fromJson(it, com.ai.data.MetadataIcons::class.java) } catch (_: Exception) { null }
        } ?: com.ai.data.MetadataIcons()).sanitized()
        val uiColorOverrides = loadUiColorOverrides()
        // Absent-key fallbacks for the network caps reference the GeneralSettings
        // data-class defaults so a fresh install matches an in-app reset (these
        // used to diverge: load → 30/3/50, reset/data-class → 60/5/100).
        val defaults = GeneralSettings()
        return GeneralSettings(
            userName = prefs.getString(KEY_USER_NAME, "user") ?: "user",
            huggingFaceApiKey = prefs.getString(KEY_HUGGINGFACE_API_KEY, "") ?: "",
            openRouterApiKey = prefs.getString(KEY_OPENROUTER_API_KEY, "") ?: "",
            artificialAnalysisApiKey = prefs.getString(KEY_AA_API_KEY, "") ?: "",
            llmStatsApiKey = prefs.getString(KEY_LLMSTATS_API_KEY, "") ?: "",
            defaultEmail = prefs.getString(KEY_DEFAULT_EMAIL, "") ?: "",
            defaultTypePaths = defaultTypePaths,
            loggingMasterEnabled = prefs.getBoolean(KEY_LOGGING_MASTER_ENABLED, true),
            tracingEnabled = prefs.getBoolean(KEY_TRACING_ENABLED, true),
            auditLogEnabled = prefs.getBoolean(KEY_AUDIT_LOG_ENABLED, false),
            usageStatsEnabled = prefs.getBoolean(KEY_USAGE_STATS_ENABLED, true),
            fullScreen = prefs.getBoolean(KEY_FULL_SCREEN, true),
            modelNameLayout = modelNameLayout,
            appHomeMode = appHomeMode,
            uiCardBackgroundArgb = uiColorOverrides["CardBackgroundAlt"] ?: DEFAULT_UI_CARD_BACKGROUND_ARGB,
            uiButtonBackgroundArgb = uiColorOverrides["ButtonBackground"] ?: DEFAULT_UI_BUTTON_BACKGROUND_ARGB,
            uiColorOverrides = uiColorOverrides,
            uiColorOverridesDay = prefs.getString(KEY_UI_COLOR_OVERRIDES_DAY, null)?.let {
                try { gson.fromJson<Map<String, Int>>(it, TypeTokens.mapStringIntType) } catch (_: Exception) { null }
            }.orEmpty(),
            uiColorMode = prefs.getString(KEY_UI_COLOR_MODE, null)?.let {
                try { com.ai.viewmodel.UiColorMode.valueOf(it) } catch (_: Exception) { null }
            } ?: com.ai.viewmodel.UiColorMode.NIGHT,
            metadataEnabled = prefs.getBoolean(KEY_METADATA_ENABLED, true),
            iconGenEnabled = prefs.getBoolean(KEY_ICON_GEN_ENABLED, true),
            reportLanguageGenEnabled = prefs.getBoolean(KEY_REPORT_LANGUAGE_GEN_ENABLED, true),
            reportTitleMode = reportTitleMode,
            perModelIconGenEnabled = prefs.getBoolean(KEY_PER_MODEL_ICON_GEN_ENABLED, true),
            perModelTitleGenEnabled = prefs.getBoolean(KEY_PER_MODEL_TITLE_GEN_ENABLED, true),
            useInternalPromptsIcons = prefs.getBoolean(KEY_USE_INTERNAL_PROMPTS_ICONS, true),
            autostartItemsEnabled = prefs.getBoolean(KEY_AUTOSTART_ITEMS_ENABLED, false),
            autostartFanMeta = prefs.getBoolean(KEY_AUTOSTART_FAN_META, true),
            autoCreateRerankAndModeration = prefs.getBoolean(KEY_AUTO_CREATE_RERANK_MODERATION, true),
            metadataIcons = metadataIcons,
            appWideSystemPromptId = prefs.getString(KEY_APP_WIDE_SYSTEM_PROMPT_ID, null),
            appWideParametersIds = loadJsonList(KEY_APP_WIDE_PARAMETERS_IDS) ?: emptyList(),
            reportModelSystemPromptId = prefs.getString(KEY_REPORT_MODEL_SYSTEM_PROMPT_ID, null),
            reportModelParametersIds = loadJsonList(KEY_REPORT_MODEL_PARAMETERS_IDS) ?: emptyList(),
            showKnowledgeCard = prefs.getBoolean(KEY_SHOW_KNOWLEDGE_CARD, false),
            experimentalFeaturesEnabled = prefs.getBoolean(KEY_EXPERIMENTAL_FEATURES, false),
            // Key absent = fresh install → seed the default pin set; present
            // (incl. "[]" after the user unpins everything) = honour the stored
            // value, so "no pinned cards" stays empty rather than re-seeding.
            pinnedDashboardCards = if (prefs.contains(KEY_PINNED_DASHBOARD_CARDS)) loadJsonStringSet(KEY_PINNED_DASHBOARD_CARDS)
                else GeneralSettings().pinnedDashboardCards,
            dashboardCardOrder = loadJsonList(KEY_DASHBOARD_CARD_ORDER) ?: emptyList(),
            recentReportModels = prefs.getString(KEY_RECENT_REPORT_MODELS, null)
                ?.split("\n")?.filter { it.isNotBlank() }
                ?: emptyList(),
            streamingReadTimeoutSec = prefs.getInt(
                KEY_STREAMING_READ_TIMEOUT_SEC, com.ai.BuildConfig.NETWORK_READ_TIMEOUT_SEC
            ),
            nonStreamingReadTimeoutSec = prefs.getInt(
                KEY_NONSTREAMING_READ_TIMEOUT_SEC, com.ai.BuildConfig.NETWORK_NONSTREAMING_READ_TIMEOUT_SEC
            ),
            batchItemTimeoutSec = prefs.getInt(
                KEY_BATCH_ITEM_TIMEOUT_SEC, com.ai.BuildConfig.BATCH_ITEM_TIMEOUT_SEC
            ),
            maxCallsPerProviderPerMinute = prefs.getInt(KEY_MAX_CALLS_PER_PROVIDER_PER_MINUTE, defaults.maxCallsPerProviderPerMinute),
            maxConcurrentCallsPerProvider = prefs.getInt(KEY_MAX_CONCURRENT_CALLS_PER_PROVIDER, defaults.maxConcurrentCallsPerProvider),
            maxConcurrentApiCalls = prefs.getInt(KEY_MAX_CONCURRENT_API_CALLS, defaults.maxConcurrentApiCalls),
            maxRetriesOn429 = prefs.getInt(KEY_MAX_RETRIES_ON_429, 3),
            retryBackoffMs429 = prefs.getLong(KEY_RETRY_BACKOFF_MS_429, 1_000L),
            maxRetriesOn529 = prefs.getInt(KEY_MAX_RETRIES_ON_529, 3),
            retryBackoffMs529 = prefs.getLong(KEY_RETRY_BACKOFF_MS_529, 1_000L),
            typeABenchEnabled = prefs.getBoolean(KEY_TYPE_A_BENCH_ENABLED, true),
            typeABenchSeconds = prefs.getInt(KEY_TYPE_A_BENCH_SECONDS, 10),
            typeABenchMaxAttempts = prefs.getInt(KEY_TYPE_A_BENCH_MAX_ATTEMPTS, 5),
            showLadybugIcons = prefs.getBoolean(KEY_SHOW_LADYBUG_ICONS, true),
            rankingWeights = prefs.getString(KEY_RANKING_WEIGHTS, null)?.let {
                try { gson.fromJson<Map<String, Int>>(it, TypeTokens.mapStringIntType) } catch (_: Exception) { null }
            }.orEmpty(),
            logLevel = prefs.getString(KEY_LOG_LEVEL, null)?.let {
                try { com.ai.data.LogLevel.valueOf(it) } catch (_: Exception) { null }
            } ?: com.ai.data.LogLevel.WARN
        ).also {
            com.ai.data.AppLog.d(
                "SettingsPrefs",
                "loadGeneralSettings logLevel=${it.logLevel} tracing=${it.tracingEnabled} " +
                    "streamRT=${it.streamingReadTimeoutSec}s nonStreamRT=${it.nonStreamingReadTimeoutSec}s " +
                    "maxPerMin=${it.maxCallsPerProviderPerMinute} maxConc=${it.maxConcurrentCallsPerProvider} " +
                    "recentReportModels=${it.recentReportModels.size}"
            )
        }
    }

    fun saveGeneralSettings(settings: GeneralSettings) {
        val uiColorOverrides = AppColors.normalizeUiColorOverrides(settings.uiColorOverrides).toMutableMap()
        val cardBackgroundArgb = uiColorOverrides["CardBackgroundAlt"] ?: settings.uiCardBackgroundArgb
        val buttonBackgroundArgb = uiColorOverrides["ButtonBackground"] ?: settings.uiButtonBackgroundArgb
        prefs.edit {
            putString(KEY_USER_NAME, settings.userName.ifBlank { "user" })
            putString(KEY_HUGGINGFACE_API_KEY, settings.huggingFaceApiKey)
            putString(KEY_OPENROUTER_API_KEY, settings.openRouterApiKey)
            putString(KEY_AA_API_KEY, settings.artificialAnalysisApiKey)
            putString(KEY_LLMSTATS_API_KEY, settings.llmStatsApiKey)
            putString(KEY_DEFAULT_EMAIL, settings.defaultEmail)
            putString(KEY_DEFAULT_TYPE_PATHS, gson.toJson(settings.defaultTypePaths))
            putBoolean(KEY_LOGGING_MASTER_ENABLED, settings.loggingMasterEnabled)
            putBoolean(KEY_TRACING_ENABLED, settings.tracingEnabled)
            putBoolean(KEY_AUDIT_LOG_ENABLED, settings.auditLogEnabled)
            putBoolean(KEY_USAGE_STATS_ENABLED, settings.usageStatsEnabled)
            putBoolean(KEY_FULL_SCREEN, settings.fullScreen)
            putString(KEY_MODEL_NAME_LAYOUT, settings.modelNameLayout.name)
            putString(KEY_APP_HOME, settings.appHomeMode.name)
            putInt(KEY_UI_CARD_BACKGROUND_ARGB, cardBackgroundArgb)
            putInt(KEY_UI_BUTTON_BACKGROUND_ARGB, buttonBackgroundArgb)
            putString(KEY_UI_COLOR_OVERRIDES, gson.toJson(uiColorOverrides))
            putString(KEY_UI_COLOR_OVERRIDES_DAY, gson.toJson(settings.uiColorOverridesDay))
            putString(KEY_UI_COLOR_MODE, settings.uiColorMode.name)
            putBoolean(KEY_METADATA_ENABLED, settings.metadataEnabled)
            putBoolean(KEY_ICON_GEN_ENABLED, settings.iconGenEnabled)
            putBoolean(KEY_REPORT_LANGUAGE_GEN_ENABLED, settings.reportLanguageGenEnabled)
            putString(KEY_REPORT_TITLE_MODE, settings.reportTitleMode.name)
            putBoolean(KEY_PER_MODEL_ICON_GEN_ENABLED, settings.perModelIconGenEnabled)
            putBoolean(KEY_PER_MODEL_TITLE_GEN_ENABLED, settings.perModelTitleGenEnabled)
            putBoolean(KEY_USE_INTERNAL_PROMPTS_ICONS, settings.useInternalPromptsIcons)
            putBoolean(KEY_AUTOSTART_ITEMS_ENABLED, settings.autostartItemsEnabled)
            putBoolean(KEY_AUTOSTART_FAN_META, settings.autostartFanMeta)
            putBoolean(KEY_AUTO_CREATE_RERANK_MODERATION, settings.autoCreateRerankAndModeration)
            putString(KEY_METADATA_ICONS, gson.toJson(settings.metadataIcons))
            putString(KEY_APP_WIDE_SYSTEM_PROMPT_ID, settings.appWideSystemPromptId)
            putString(KEY_APP_WIDE_PARAMETERS_IDS, if (settings.appWideParametersIds.isEmpty()) null else gson.toJson(settings.appWideParametersIds))
            putString(KEY_REPORT_MODEL_SYSTEM_PROMPT_ID, settings.reportModelSystemPromptId)
            putString(KEY_REPORT_MODEL_PARAMETERS_IDS, if (settings.reportModelParametersIds.isEmpty()) null else gson.toJson(settings.reportModelParametersIds))
            putBoolean(KEY_SHOW_KNOWLEDGE_CARD, settings.showKnowledgeCard)
            putBoolean(KEY_EXPERIMENTAL_FEATURES, settings.experimentalFeaturesEnabled)
            // Always write the list (even "[]") so an explicit "unpin all" is
            // distinguishable from a fresh install (key absent → default seed).
            putString(KEY_PINNED_DASHBOARD_CARDS, gson.toJson(settings.pinnedDashboardCards.toList()))
            putString(KEY_DASHBOARD_CARD_ORDER, if (settings.dashboardCardOrder.isEmpty()) null else gson.toJson(settings.dashboardCardOrder))
            // Newline-joined: entries are "providerId|model" so newline
            // is a safe delimiter — neither side can contain it.
            putString(KEY_RECENT_REPORT_MODELS, settings.recentReportModels.joinToString("\n"))
            putInt(KEY_STREAMING_READ_TIMEOUT_SEC, settings.streamingReadTimeoutSec)
            putInt(KEY_NONSTREAMING_READ_TIMEOUT_SEC, settings.nonStreamingReadTimeoutSec)
            putInt(KEY_BATCH_ITEM_TIMEOUT_SEC, settings.batchItemTimeoutSec)
            putInt(KEY_MAX_CALLS_PER_PROVIDER_PER_MINUTE, settings.maxCallsPerProviderPerMinute)
            putInt(KEY_MAX_CONCURRENT_CALLS_PER_PROVIDER, settings.maxConcurrentCallsPerProvider)
            putInt(KEY_MAX_CONCURRENT_API_CALLS, settings.maxConcurrentApiCalls)
            putInt(KEY_MAX_RETRIES_ON_429, settings.maxRetriesOn429)
            putLong(KEY_RETRY_BACKOFF_MS_429, settings.retryBackoffMs429)
            putInt(KEY_MAX_RETRIES_ON_529, settings.maxRetriesOn529)
            putLong(KEY_RETRY_BACKOFF_MS_529, settings.retryBackoffMs529)
            putBoolean(KEY_TYPE_A_BENCH_ENABLED, settings.typeABenchEnabled)
            putInt(KEY_TYPE_A_BENCH_SECONDS, settings.typeABenchSeconds)
            putInt(KEY_TYPE_A_BENCH_MAX_ATTEMPTS, settings.typeABenchMaxAttempts)
            putBoolean(KEY_SHOW_LADYBUG_ICONS, settings.showLadybugIcons)
            putString(KEY_RANKING_WEIGHTS, if (settings.rankingWeights.isEmpty()) null else gson.toJson(settings.rankingWeights))
            putString(KEY_LOG_LEVEL, settings.logLevel.name)
        }
        com.ai.data.AppLog.d(
            "SettingsPrefs",
            "saveGeneralSettings logLevel=${settings.logLevel} tracing=${settings.tracingEnabled} " +
                "streamRT=${settings.streamingReadTimeoutSec}s nonStreamRT=${settings.nonStreamingReadTimeoutSec}s " +
                "maxPerMin=${settings.maxCallsPerProviderPerMinute} maxConc=${settings.maxConcurrentCallsPerProvider}"
        )
    }

    // ===== AI Settings =====

    fun loadSettings(): Settings {
        val providerSettings = loadProviderSettings()
        val rawAgents = loadList<Agent>(KEY_AI_AGENTS, TypeTokens.listAgentType)
        val providersWithMigratedAgentKeys = rawAgents.fold(providerSettings.providers) { providers, agent ->
            if (agent.apiKey.isBlank()) {
                providers
            } else {
                val current = providers[agent.provider] ?: defaultProviderConfig(agent.provider)
                if (current.apiKey.isBlank()) {
                    providers + (agent.provider to current.copy(apiKey = agent.apiKey))
                } else {
                    providers
                }
            }
        }
        return providerSettings.copy(
            providers = providersWithMigratedAgentKeys,
            agents = scrubAgentApiKeys(rawAgents),
            flocks = loadList(KEY_AI_FLOCKS, TypeTokens.listFlockType),
            swarms = loadList(KEY_AI_SWARMS, TypeTokens.listSwarmType),
            parameters = loadList(KEY_AI_PARAMETERS, TypeTokens.listParametersType),
            systemPrompts = loadList(KEY_AI_SYSTEM_PROMPTS, TypeTokens.listSystemPromptType),
            internalPrompts = loadList<InternalPrompt>(KEY_AI_INTERNAL_PROMPTS, TypeTokens.listInternalPromptType).map { raw ->
                // Gson allocates without the constructor, so a non-null Kotlin
                // field is left null when older stored JSON predates it (e.g.
                // `parameters` / `systemPrompt`, added later — old Meta prompts
                // crashed the CRUD view with that null). Reassert the data-class
                // invariant for every non-null String field before anything reads it.
                @Suppress("USELESS_CAST")
                val p = raw.copy(
                    id = (raw.id as String?) ?: java.util.UUID.randomUUID().toString(),
                    name = (raw.name as String?) ?: "",
                    category = (raw.category as String?) ?: "internal",
                    agent = (raw.agent as String?) ?: "*select",
                    text = (raw.text as String?) ?: "",
                    title = (raw.title as String?) ?: "",
                    parameters = (raw.parameters as String?) ?: "*NONE",
                    systemPrompt = (raw.systemPrompt as String?) ?: "*NONE",
                    modelSelection = (raw.modelSelection as String?) ?: com.ai.model.MODEL_SELECTION_CONFIGURED,
                    // Same Gson-no-constructor hazard for the nested workers:
                    // the flock / swarm fields, added later, land null on JSON
                    // saved before they existed. Reassert the "*N/A" sentinel
                    // so isFlock/isSwarm (and .isNotBlank()) don't trip / NPE.
                    workers = (raw.workers as List<Worker>?).orEmpty().map { w ->
                        w.copy(
                            flock = (w.flock as String?) ?: "*N/A",
                            swarm = (w.swarm as String?) ?: "*N/A"
                        )
                    }
                )
                p
            },
            examplePrompts = loadList(KEY_AI_EXAMPLE_PROMPTS, TypeTokens.listExamplePromptType),
            endpoints = loadEndpoints(),
            providerStates = loadMap(KEY_PROVIDER_STATES),
            modelTypeOverrides = loadList(KEY_AI_MODEL_TYPE_OVERRIDES, TypeTokens.listModelTypeOverrideType),
            blockedModels = loadList(KEY_AI_BLOCKED_MODELS, TypeTokens.listBlockedModelType),
            testExcludedModels = loadList(KEY_AI_TEST_EXCLUDED_MODELS, TypeTokens.listTestExcludedModelType),
            inaccessibleModels = loadList(KEY_AI_INACCESSIBLE_MODELS, TypeTokens.listInaccessibleModelType),
            defaultMetaItems = loadList(KEY_AI_DEFAULT_META_ITEMS, TypeTokens.listDefaultMetaItemType),
            disabledInfoProviders = loadJsonStringSet(KEY_AI_DISABLED_INFO_PROVIDERS)
        )
    }

    private fun scrubAgentApiKeys(agents: List<Agent>): List<Agent> =
        agents.map { agent -> if (agent.apiKey.isBlank()) agent else agent.copy(apiKey = "") }

    private fun loadProviderSettings(): Settings {
        val providers = AppService.entries.associateWith { service ->
            val key = service.id
            val defaults = defaultProviderConfig(service)
            val models = if (defaults.models.isNotEmpty())
                loadJsonList("${key}_manual_models") ?: defaults.models
            else
                loadJsonList("${key}_manual_models") ?: emptyList()
            val storedTypes: Map<String, String> = prefs.getString("${key}_model_types", null)?.let {
                try {
                    gson.fromJson<Map<String, String>>(it, TypeTokens.mapStringStringType)
                } catch (_: Exception) { null }
            } ?: emptyMap()
            val types = models.associateWith { id -> storedTypes[id] ?: com.ai.data.ModelType.infer(id) }

            val visionModels = loadJsonStringSet("${key}_vision_models")
            val webSearchModels = loadJsonStringSet("${key}_web_search_models")
            val reasoningModels = loadJsonStringSet("${key}_reasoning_models")
            val visionCapableComputed = loadJsonStringSet("${key}_vision_capable_computed")
            val webSearchCapableComputed = loadJsonStringSet("${key}_web_search_capable_computed")
            val reasoningCapableComputed = loadJsonStringSet("${key}_reasoning_capable_computed")
            val modelPricing: Map<String, com.ai.data.PricingCache.ModelPricing> = prefs.getString("${key}_model_pricing", null)?.let {
                try {
                    val mapType = object : com.google.gson.reflect.TypeToken<Map<String, com.ai.data.PricingCache.ModelPricing>>() {}.type
                    gson.fromJson(it, mapType) ?: emptyMap()
                } catch (_: Exception) { null }
            } ?: emptyMap()
            val modelCapabilities: Map<String, com.ai.data.ModelCapabilities> = prefs.getString("${key}_model_capabilities", null)?.let {
                try {
                    val mapType = object : com.google.gson.reflect.TypeToken<Map<String, com.ai.data.ModelCapabilities>>() {}.type
                    gson.fromJson(it, mapType) ?: emptyMap()
                } catch (_: Exception) { null }
            } ?: emptyMap()
            val modelListRawJson = prefs.getString("${key}_models_response_raw", null)

            ProviderConfig(
                apiKey = prefs.getString("${key}_api_key", "") ?: "",
                models = models, modelTypes = types,
                visionModels = visionModels, webSearchModels = webSearchModels,
                reasoningModels = reasoningModels,
                visionCapableComputed = visionCapableComputed,
                webSearchCapableComputed = webSearchCapableComputed,
                reasoningCapableComputed = reasoningCapableComputed,
                modelPricing = modelPricing,
                modelCapabilities = if (service.apiFormat == com.ai.data.ApiFormat.OPENAI_COMPATIBLE && !service.crossProviderModelList)
                    backfillCachedTokenLimits(modelListRawJson, modelCapabilities, gson)
                else modelCapabilities,
                modelListRawJson = modelListRawJson,
                parametersIds = loadJsonList("${key}_parameters_id") ?: emptyList(),
                systemPromptId = prefs.getString("${key}_system_prompt_id", null)
            )
        }
        return Settings(providers = providers)
    }

    private fun loadJsonStringSet(key: String): Set<String> {
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            gson.fromJson<List<String>>(json, TypeTokens.listStringType)?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    fun saveSettings(settings: Settings) {
        val providerKeyFallbacks = settings.agents
            .filter { it.apiKey.isNotBlank() }
            .associate { it.provider to it.apiKey }
        val agentsToStore = scrubAgentApiKeys(settings.agents)
        prefs.edit {
            for (service in AppService.entries) {
                val key = service.id
                val config = settings.providers[service] ?: defaultProviderConfig(service)
                putString("${key}_api_key", config.apiKey.ifBlank { providerKeyFallbacks[service].orEmpty() })
                putString("${key}_manual_models", gson.toJson(config.models))
                putString("${key}_model_types", gson.toJson(config.modelTypes))
                // User-curated vision / web-search overrides + the per-fetch
                // capability sidecar. Without these the in-memory state was
                // dropping on every app restart, and the backup zip never
                // saw it either.
                putString("${key}_vision_models", if (config.visionModels.isEmpty()) null else gson.toJson(config.visionModels.toList()))
                putString("${key}_web_search_models", if (config.webSearchModels.isEmpty()) null else gson.toJson(config.webSearchModels.toList()))
                putString("${key}_reasoning_models", if (config.reasoningModels.isEmpty()) null else gson.toJson(config.reasoningModels.toList()))
                // Pre-computed result of the layered isVisionCapable /
                // isWebSearchCapable / isReasoningCapable lookup — stored
                // so list-render code can short-circuit through a Set
                // membership check instead of re-running ~1k-entry
                // catalog scans on every row.
                putString("${key}_vision_capable_computed", if (config.visionCapableComputed.isEmpty()) null else gson.toJson(config.visionCapableComputed.toList()))
                putString("${key}_web_search_capable_computed", if (config.webSearchCapableComputed.isEmpty()) null else gson.toJson(config.webSearchCapableComputed.toList()))
                putString("${key}_reasoning_capable_computed", if (config.reasoningCapableComputed.isEmpty()) null else gson.toJson(config.reasoningCapableComputed.toList()))
                putString("${key}_model_pricing", if (config.modelPricing.isEmpty()) null else gson.toJson(config.modelPricing))
                putString("${key}_model_capabilities", if (config.modelCapabilities.isEmpty()) null else gson.toJson(config.modelCapabilities))
                // Raw /models response — kept verbatim so a later parser
                // revision can pull out new fields without forcing a refetch.
                putString("${key}_models_response_raw", config.modelListRawJson)
                putString("${key}_parameters_id", if (config.parametersIds.isEmpty()) null else gson.toJson(config.parametersIds))
                putString("${key}_system_prompt_id", config.systemPromptId)
            }
            putString(KEY_AI_AGENTS, gson.toJson(agentsToStore))
            putString(KEY_AI_FLOCKS, gson.toJson(settings.flocks))
            putString(KEY_AI_SWARMS, gson.toJson(settings.swarms))
            putString(KEY_AI_PARAMETERS, gson.toJson(settings.parameters))
            putString(KEY_AI_SYSTEM_PROMPTS, gson.toJson(settings.systemPrompts))
            putString(KEY_AI_INTERNAL_PROMPTS, gson.toJson(settings.internalPrompts))
            putString(KEY_AI_EXAMPLE_PROMPTS, gson.toJson(settings.examplePrompts))
            putString(KEY_AI_ENDPOINTS, gson.toJson(settings.endpoints.mapKeys { it.key.id }))
            putString(KEY_PROVIDER_STATES, gson.toJson(settings.providerStates))
            putString(KEY_AI_MODEL_TYPE_OVERRIDES, gson.toJson(settings.modelTypeOverrides))
            putString(KEY_AI_BLOCKED_MODELS, if (settings.blockedModels.isEmpty()) null else gson.toJson(settings.blockedModels))
            putString(KEY_AI_TEST_EXCLUDED_MODELS, if (settings.testExcludedModels.isEmpty()) null else gson.toJson(settings.testExcludedModels))
            putString(KEY_AI_INACCESSIBLE_MODELS, if (settings.inaccessibleModels.isEmpty()) null else gson.toJson(settings.inaccessibleModels))
            putString(KEY_AI_DEFAULT_META_ITEMS, if (settings.defaultMetaItems.isEmpty()) null else gson.toJson(settings.defaultMetaItems))
            putString(KEY_AI_DISABLED_INFO_PROVIDERS, if (settings.disabledInfoProviders.isEmpty()) null else gson.toJson(settings.disabledInfoProviders.toList()))
        }
    }

    /** Refresh checkpoints must not rewrite unrelated model catalogs from
     *  an older snapshot while other providers are still downloading. */
    fun saveProviderWorkers(settings: Settings) {
        prefs.edit {
            putString(KEY_AI_AGENTS, gson.toJson(scrubAgentApiKeys(settings.agents)))
            putString(KEY_AI_FLOCKS, gson.toJson(settings.flocks))
            putString(KEY_PROVIDER_STATES, gson.toJson(settings.providerStates))
        }
    }

    fun saveModelsForProvider(
        service: AppService, models: List<String>, types: Map<String, String> = emptyMap(),
        visionModels: Set<String>? = null,
        modelCapabilities: Map<String, com.ai.data.ModelCapabilities>? = null,
        modelListRawJson: String? = null,
        refreshedFromApi: Boolean = false
    ) {
        prefs.edit {
            putString("${service.id}_manual_models", gson.toJson(models))
            putString("${service.id}_model_types", gson.toJson(types))
            if (visionModels != null) {
                putString("${service.id}_vision_models", if (visionModels.isEmpty()) null else gson.toJson(visionModels.toList()))
            }
            if (modelCapabilities != null) {
                putString("${service.id}_model_capabilities", if (modelCapabilities.isEmpty()) null else gson.toJson(modelCapabilities))
            }
            if (modelListRawJson != null) {
                putString("${service.id}_models_response_raw", modelListRawJson)
            }
            // Only an actual nonempty API fetch renews freshness. Metadata
            // propagation and manual edits must not extend another list's TTL.
            if (refreshedFromApi && models.isNotEmpty()) {
                putLong(KEY_MODEL_LIST_TIMESTAMP_PREFIX + service.id, System.currentTimeMillis())
            }
        }
    }

    // ===== Prompt History =====

    fun loadPromptHistory(): List<PromptHistoryEntry> = promptHistoryStore.load()

    fun savePromptToHistory(title: String, prompt: String) = promptHistoryStore.add(title, prompt)

    fun savePromptHistoryList(entries: List<PromptHistoryEntry>) = promptHistoryStore.saveList(entries)

    /** Wipe the prompt-history file and return how many entries it held before
     *  the wipe. Callers that don't need the count can ignore the return value. */
    fun clearPromptHistory(): Int = promptHistoryStore.clear()

    fun clearLastReportPrompt() { prefs.edit { remove(KEY_LAST_AI_REPORT_TITLE); remove(KEY_LAST_AI_REPORT_PROMPT) } }

    // ===== Usage Statistics =====

    fun loadUsageStats(): Map<String, UsageStats> = HashMap(ensureUsageStatsCache())

    private fun ensureUsageStatsCache(): java.util.concurrent.ConcurrentHashMap<String, UsageStats> {
        usageStatsCache?.let { return it }
        return synchronized(usageStatsLock) {
            usageStatsCache?.let { return@synchronized it }
            val file = filesDir?.let { File(it, FILE_USAGE_STATS) }
            val cache = java.util.concurrent.ConcurrentHashMap<String, UsageStats>()
            if (file == null || !file.exists()) {
                usageStatsCache = cache
                return@synchronized cache
            }
            // Parse entries individually so a single unresolvable provider id (e.g. a custom
            // provider that's been deleted) doesn't drop the whole list. Only commit the cache
            // when the JSON shape itself parsed — otherwise leave usageStatsCache null so the
            // next read retries (covers the race where ProviderRegistry is still initialising).
            val arr = try { gson.fromJson(file.readText(), com.google.gson.JsonArray::class.java) } catch (_: Exception) { null }
            if (arr == null) return@synchronized cache
            arr.forEach { el ->
                try {
                    val raw = gson.fromJson(el, UsageStats::class.java)
                    // Gson bypasses Kotlin's default-value constructors via
                    // Unsafe, so rows written before `kind` was added have
                    // a runtime-null `kind` even though the data class
                    // declares `String = "report"`. Backfill to keep the
                    // non-null contract — downstream code (the kind pill
                    // on UsageModelRow) assumes a non-null String and
                    // would NPE on the missing rows when the provider is
                    // expanded.
                    @Suppress("USELESS_CAST")
                    val stat = raw.copy(kind = normalizeUsageKind(raw.kind as String?))
                    cache.compute(stat.key) { _, existing -> mergeUsageStats(existing, stat) }
                } catch (_: Exception) { /* skip rows that reference an unknown provider id */ }
            }
            // If the file had rows but every single one failed to deserialise — most likely
            // ProviderRegistry hasn't initialised yet so AppServiceAdapter throws on every
            // provider id — refuse to commit the empty cache and let the next call retry.
            if (cache.isEmpty() && arr.size() > 0) return@synchronized cache
            usageStatsCache = cache
            cache
        }
    }

    fun saveUsageStats(stats: Map<String, UsageStats>) {
        val file = filesDir?.let { File(it, FILE_USAGE_STATS) } ?: return
        file.writeTextAtomic(gson.toJson(stats.values.toList()))
    }

    fun loadUsageCategoryStats(): Map<String, UsageCategoryStats> {
        val fileExists = filesDir?.let { File(it, FILE_USAGE_CATEGORY_STATS).exists() } == true
        val cache = ensureUsageCategoryStatsCache()
        if (cache.isEmpty() && !fileExists) {
            rebuildUsageCategoryStatsFromUsageStats()
        }
        return HashMap(ensureUsageCategoryStatsCache())
    }

    private fun ensureUsageCategoryStatsCache(): java.util.concurrent.ConcurrentHashMap<String, UsageCategoryStats> {
        usageCategoryStatsCache?.let { return it }
        return synchronized(usageStatsLock) {
            usageCategoryStatsCache?.let { return@synchronized it }
            val file = filesDir?.let { File(it, FILE_USAGE_CATEGORY_STATS) }
            val cache = java.util.concurrent.ConcurrentHashMap<String, UsageCategoryStats>()
            if (file == null || !file.exists()) {
                usageCategoryStatsCache = cache
                return@synchronized cache
            }
            val list = try {
                gson.fromJson<List<UsageCategoryStats>>(file.readText(), TypeTokens.listUsageCategoryStatsType)
            } catch (_: Exception) {
                null
            }.orEmpty()
            list.forEach { row ->
                val category = normalizeUsageKind(row.category)
                cache.compute(category) { _, existing ->
                    mergeUsageCategoryStats(existing, row.copy(category = category))
                }
            }
            usageCategoryStatsCache = cache
            cache
        }
    }

    private fun saveUsageCategoryStats(stats: Map<String, UsageCategoryStats>) {
        val file = filesDir?.let { File(it, FILE_USAGE_CATEGORY_STATS) } ?: return
        file.writeTextAtomic(gson.toJson(stats.values.toList()))
    }

    fun loadUsageReportStats(context: android.content.Context): Map<String, UsageReportStats> {
        val fileExists = filesDir?.let { File(it, FILE_USAGE_REPORT_STATS).exists() } == true
        val cache = ensureUsageReportStatsCache()
        if (cache.isEmpty() && !fileExists) {
            rebuildUsageReportStatsFromReports(context)
        }
        return HashMap(ensureUsageReportStatsCache())
    }

    fun reconcileReportCostLedgers(context: android.content.Context): Boolean {
        val reports = ReportStorage.getAllReports(context)
        val deltas = reports.mapNotNull { ReportStorage.reconcileApiCallCostLedger(context, it.id) }
        if (deltas.isEmpty()) return false
        val usageStats = ensureUsageStatsCache()
        val categoryStats = ensureUsageCategoryStatsCache()
        deltas.filter { it.adjustAggregateStats }.forEach { delta ->
            delta.oldRows.forEach { row -> adjustUsageStatsForApiRow(usageStats, row, -1) }
            delta.newRows.forEach { row -> adjustUsageStatsForApiRow(usageStats, row, 1) }
            delta.oldRows.forEach { row -> adjustCategoryStatsForApiRow(categoryStats, row, -1) }
            delta.newRows.forEach { row -> adjustCategoryStatsForApiRow(categoryStats, row, 1) }
        }
        saveUsageStats(usageStats)
        saveUsageCategoryStats(categoryStats)
        rebuildUsageReportStatsFromReports(context)
        flushUsageStats()
        return true
    }

    private fun ensureUsageReportStatsCache(): java.util.concurrent.ConcurrentHashMap<String, UsageReportStats> {
        usageReportStatsCache?.let { return it }
        return synchronized(usageStatsLock) {
            usageReportStatsCache?.let { return@synchronized it }
            val file = filesDir?.let { File(it, FILE_USAGE_REPORT_STATS) }
            val cache = java.util.concurrent.ConcurrentHashMap<String, UsageReportStats>()
            if (file == null || !file.exists()) {
                usageReportStatsCache = cache
                return@synchronized cache
            }
            val list = try {
                gson.fromJson<List<UsageReportStats>>(file.readText(), TypeTokens.listUsageReportStatsType)
            } catch (_: Exception) {
                null
            }.orEmpty()
            list.forEach { row ->
                if (row.reportId.isNotBlank()) {
                    cache.compute(row.reportId) { _, existing -> mergeUsageReportStats(existing, row) }
                }
            }
            usageReportStatsCache = cache
            cache
        }
    }

    private fun saveUsageReportStats(stats: Map<String, UsageReportStats>) {
        val file = filesDir?.let { File(it, FILE_USAGE_REPORT_STATS) } ?: return
        file.writeTextAtomic(gson.toJson(stats.values.toList()))
    }

    private data class UsageCostSnapshot(
        val inputCost: Double,
        val outputCost: Double,
        val pricingSource: String
    )

    private fun computeUsageCostSnapshot(
        provider: AppService,
        model: String,
        usage: TokenUsage,
        searchUnits: Int
    ): UsageCostSnapshot {
        val pricing = PricingCache.lookupPricing(provider, model)
        val (tokenInputCost, tokenOutputCost) = PricingCache.computeInOutCost(usage, pricing)
        val searchCost = if (searchUnits > 0 && pricing.perQueryPrice > 0.0) {
            searchUnits * pricing.perQueryPrice
        } else {
            0.0
        }
        return UsageCostSnapshot(
            inputCost = tokenInputCost + searchCost,
            outputCost = tokenOutputCost,
            pricingSource = if (provider.extractApiCost || provider.costTicksDivisor != null) "API_REPORTED" else pricing.source
        )
    }

    /**
     * updateUsageStats used to hold a lock, re-read the whole JSON file, mutate, and re-write
     * on every API call. Under concurrent report generation that serialized every worker and
     * allocated a new Map-of-all-stats per token-usage event. Now we keep stats in an in-memory
     * ConcurrentHashMap and debounce disk writes to once per USAGE_STATS_FLUSH_MS window.
     */
    fun updateUsageStats(provider: AppService, model: String, inputTokens: Int, outputTokens: Int, totalTokens: Int = inputTokens + outputTokens, kind: String = "report", searchUnits: Int = 0, durationMs: Long? = null) {
        updateUsageStats(provider, model, TokenUsage(inputTokens = inputTokens, outputTokens = outputTokens), kind, searchUnits, durationMs)
    }

    fun updateUsageStats(provider: AppService, model: String, usage: TokenUsage, kind: String = "report", searchUnits: Int = 0, durationMs: Long? = null) {
        // Report accounting is durable even when optional aggregate statistics are off.
        // Feed the Live Dashboard's rolling spend/token rate (in-memory, 5-min
        // window) — this is the single chokepoint every token site funnels through.
        // Billed totals (uncached+cached+cache-creation / output+reasoning) so
        // the persisted ledger's token counts match what the cost prices —
        // storing only the uncached inputTokens understated them.
        val inputTokens = usage.billedInputTokens
        val outputTokens = usage.billedOutputTokens
        val normalizedKind = normalizeUsageKind(kind)
        val category = if (normalizedKind == "report") {
            normalizeUsageKind(ApiTracer.currentCategory ?: normalizedKind)
        } else {
            normalizedKind
        }
        val costs = computeUsageCostSnapshot(provider, model, usage, searchUnits)
        try { recordReportApiCallCost(provider, model, inputTokens, outputTokens, category, searchUnits, costs, durationMs, usage.estimated, usage.traceFile) }
        catch (e: com.ai.data.ReportSaveException) { AppLog.e("ReportCosts", "Cost awaiting save retry", e) }
        scheduleUsageStatsFlush()
        if (!usageStatsEnabled) return
        com.ai.data.ApiUsageRates.record(provider, model, inputTokens, outputTokens)
        val stats = ensureUsageStatsCache()
        val key = "${provider.id}::$model::$category"
        stats.compute(key) { _, existing ->
            val base = existing ?: UsageStats(provider, model, kind = category)
            base.copy(
                callCount = base.callCount + 1,
                inputTokens = base.inputTokens + inputTokens,
                outputTokens = base.outputTokens + outputTokens,
                searchUnits = base.searchUnits + searchUnits,
                inputCost = (base.inputCost ?: 0.0) + costs.inputCost,
                outputCost = (base.outputCost ?: 0.0) + costs.outputCost,
                pricingSource = costs.pricingSource
            )
        }
        recordUsageCategoryStats(category, inputTokens, outputTokens, searchUnits, costs)
        scheduleUsageStatsFlush()
    }

    private fun recordUsageCategoryStats(
        category: String,
        inputTokens: Int,
        outputTokens: Int,
        searchUnits: Int,
        costs: UsageCostSnapshot
    ) {
        val categories = ensureUsageCategoryStatsCache()
        categories.compute(category) { _, existing ->
            val base = existing ?: UsageCategoryStats(category)
            base.copy(
                callCount = base.callCount + 1,
                inputTokens = base.inputTokens + inputTokens,
                outputTokens = base.outputTokens + outputTokens,
                searchUnits = base.searchUnits + searchUnits,
                inputCost = base.inputCost + costs.inputCost,
                outputCost = base.outputCost + costs.outputCost
            )
        }
    }

    private fun recordReportApiCallCost(
        provider: AppService,
        model: String,
        inputTokens: Int,
        outputTokens: Int,
        category: String,
        searchUnits: Int,
        costs: UsageCostSnapshot,
        durationMs: Long? = null,
        estimated: Boolean = false,
        traceFile: String? = null
    ) {
        val reportId = ApiTracer.currentReportId?.takeIf { it.isNotBlank() } ?: return
        if (inputTokens <= 0 && outputTokens <= 0 && searchUnits <= 0 && costs.inputCost <= 0.0 && costs.outputCost <= 0.0) return
        val record = ReportApiCallCost(
            type = category,
            provider = provider.id,
            model = model,
            pricingTier = costs.pricingSource,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            inputCost = costs.inputCost,
            outputCost = costs.outputCost,
            searchUnits = searchUnits,
            durationMs = durationMs,
            estimatedUsage = estimated,
            traceFile = traceFile
        )
        ReportCostJournal.enqueue(filesDir, reportId, record)
        synchronized(usageStatsLock) {
            if (!usageStatsEnabled) return
            val reports = ensureUsageReportStatsCache()
            reports.compute(reportId) { _, existing ->
                val base = existing ?: UsageReportStats(
                    reportId = reportId,
                    title = reportId,
                    timestamp = record.timestamp
                )
                base.copy(
                    timestamp = if (record.timestamp > 0L) record.timestamp else base.timestamp,
                    callCount = base.callCount + 1,
                    inputTokens = base.inputTokens + inputTokens,
                    outputTokens = base.outputTokens + outputTokens,
                    searchUnits = base.searchUnits + searchUnits,
                    inputCost = base.inputCost + costs.inputCost,
                    outputCost = base.outputCost + costs.outputCost
                )
            }
        }
    }

    private fun mergeUsageStats(existing: UsageStats?, incoming: UsageStats): UsageStats =
        existing?.copy(
            callCount = existing.callCount + incoming.callCount,
            inputTokens = existing.inputTokens + incoming.inputTokens,
            outputTokens = existing.outputTokens + incoming.outputTokens,
            searchUnits = existing.searchUnits + incoming.searchUnits,
            inputCost = mergeNullableCosts(existing.inputCost, incoming.inputCost),
            outputCost = mergeNullableCosts(existing.outputCost, incoming.outputCost),
            pricingSource = incoming.pricingSource ?: existing.pricingSource
        ) ?: incoming

    private fun mergeUsageCategoryStats(existing: UsageCategoryStats?, incoming: UsageCategoryStats): UsageCategoryStats =
        existing?.copy(
            callCount = existing.callCount + incoming.callCount,
            inputTokens = existing.inputTokens + incoming.inputTokens,
            outputTokens = existing.outputTokens + incoming.outputTokens,
            searchUnits = existing.searchUnits + incoming.searchUnits,
            inputCost = existing.inputCost + incoming.inputCost,
            outputCost = existing.outputCost + incoming.outputCost
        ) ?: incoming

    private fun mergeUsageReportStats(existing: UsageReportStats?, incoming: UsageReportStats): UsageReportStats =
        existing?.copy(
            title = incoming.title.takeIf { it.isNotBlank() } ?: existing.title,
            timestamp = if (incoming.timestamp > 0L) incoming.timestamp else existing.timestamp,
            callCount = existing.callCount + incoming.callCount,
            inputTokens = existing.inputTokens + incoming.inputTokens,
            outputTokens = existing.outputTokens + incoming.outputTokens,
            searchUnits = existing.searchUnits + incoming.searchUnits,
            inputCost = existing.inputCost + incoming.inputCost,
            outputCost = existing.outputCost + incoming.outputCost
        ) ?: incoming

    private fun mergeNullableCosts(a: Double?, b: Double?): Double? =
        if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)

    private fun adjustUsageStatsForApiRow(
        stats: java.util.concurrent.ConcurrentHashMap<String, UsageStats>,
        row: ReportApiCallCost,
        direction: Int
    ) {
        val provider = AppService.findById(row.provider) ?: return
        val kind = normalizeUsageKind(row.type)
        val key = "${provider.id}::${row.model}::$kind"
        stats.compute(key) { _, existing ->
            if (existing == null && direction < 0) return@compute null
            val base = existing ?: UsageStats(provider, row.model, kind = kind)
            val nextCalls = (base.callCount + direction).coerceAtLeast(0)
            val nextInputTokens = (base.inputTokens + direction * row.inputTokens.toLong()).coerceAtLeast(0)
            val nextOutputTokens = (base.outputTokens + direction * row.outputTokens.toLong()).coerceAtLeast(0)
            val nextSearchUnits = (base.searchUnits + direction * row.searchUnits.toLong()).coerceAtLeast(0)
            val nextInputCost = ((base.inputCost ?: 0.0) + direction * row.inputCost).coerceAtLeast(0.0)
            val nextOutputCost = ((base.outputCost ?: 0.0) + direction * row.outputCost).coerceAtLeast(0.0)
            if (nextCalls == 0 && nextInputTokens == 0L && nextOutputTokens == 0L &&
                nextSearchUnits == 0L && nextInputCost == 0.0 && nextOutputCost == 0.0
            ) {
                null
            } else {
                base.copy(
                    callCount = nextCalls,
                    inputTokens = nextInputTokens,
                    outputTokens = nextOutputTokens,
                    searchUnits = nextSearchUnits,
                    inputCost = nextInputCost,
                    outputCost = nextOutputCost,
                    pricingSource = row.pricingTier.takeIf { it.isNotBlank() } ?: base.pricingSource
                )
            }
        }
    }

    private fun adjustCategoryStatsForApiRow(
        stats: java.util.concurrent.ConcurrentHashMap<String, UsageCategoryStats>,
        row: ReportApiCallCost,
        direction: Int
    ) {
        val category = normalizeUsageKind(row.type)
        stats.compute(category) { _, existing ->
            if (existing == null && direction < 0) return@compute null
            val base = existing ?: UsageCategoryStats(category)
            val nextCalls = (base.callCount + direction).coerceAtLeast(0)
            val nextInputTokens = (base.inputTokens + direction * row.inputTokens.toLong()).coerceAtLeast(0)
            val nextOutputTokens = (base.outputTokens + direction * row.outputTokens.toLong()).coerceAtLeast(0)
            val nextSearchUnits = (base.searchUnits + direction * row.searchUnits.toLong()).coerceAtLeast(0)
            val nextInputCost = (base.inputCost + direction * row.inputCost).coerceAtLeast(0.0)
            val nextOutputCost = (base.outputCost + direction * row.outputCost).coerceAtLeast(0.0)
            if (nextCalls == 0 && nextInputTokens == 0L && nextOutputTokens == 0L &&
                nextSearchUnits == 0L && nextInputCost == 0.0 && nextOutputCost == 0.0
            ) {
                null
            } else {
                base.copy(
                    callCount = nextCalls,
                    inputTokens = nextInputTokens,
                    outputTokens = nextOutputTokens,
                    searchUnits = nextSearchUnits,
                    inputCost = nextInputCost,
                    outputCost = nextOutputCost
                )
            }
        }
    }

    private fun rebuildUsageCategoryStatsFromUsageStats() {
        val rebuilt = java.util.concurrent.ConcurrentHashMap<String, UsageCategoryStats>()
        ensureUsageStatsCache().values.forEach { stat ->
            val costs = if (stat.inputCost != null || stat.outputCost != null) {
                UsageCostSnapshot(stat.inputCost ?: 0.0, stat.outputCost ?: 0.0, stat.pricingSource ?: "")
            } else {
                computeUsageCostSnapshot(
                    stat.provider,
                    stat.model,
                    TokenUsage(stat.inputTokens.toInt(), stat.outputTokens.toInt()),
                    stat.searchUnits.toInt()
                )
            }
            rebuilt.compute(stat.kind) { _, existing ->
                val row = UsageCategoryStats(
                    category = stat.kind,
                    callCount = stat.callCount,
                    inputTokens = stat.inputTokens,
                    outputTokens = stat.outputTokens,
                    searchUnits = stat.searchUnits,
                    inputCost = costs.inputCost,
                    outputCost = costs.outputCost
                )
                mergeUsageCategoryStats(existing, row)
            }
        }
        usageCategoryStatsCache = rebuilt
        saveUsageCategoryStats(rebuilt)
    }

    private fun rebuildUsageReportStatsFromReports(context: android.content.Context) {
        val rebuilt = java.util.concurrent.ConcurrentHashMap<String, UsageReportStats>()
        ReportStorage.getAllReports(context).forEach { report ->
            val currentReport = if (!report.apiCallCostsComplete &&
                ReportStorage.ensureApiCallCostLedger(context, report.id)
            ) {
                ReportStorage.getReport(context, report.id) ?: report
            } else {
                report
            }
            val calls = currentReport.apiCallCosts
            if (calls.isEmpty()) return@forEach
            rebuilt[currentReport.id] = UsageReportStats(
                reportId = currentReport.id,
                title = currentReport.barTitle.takeIf { it.isNotBlank() } ?: currentReport.prompt.take(80),
                timestamp = if (currentReport.createdAt > 0L) currentReport.createdAt else currentReport.timestamp,
                callCount = calls.size,
                inputTokens = calls.sumOf { it.inputTokens.toLong() },
                outputTokens = calls.sumOf { it.outputTokens.toLong() },
                searchUnits = calls.sumOf { it.searchUnits.toLong() },
                inputCost = calls.sumOf { it.inputCost },
                outputCost = calls.sumOf { it.outputCost }
            )
        }
        usageReportStatsCache = rebuilt
        saveUsageReportStats(rebuilt)
    }

    private fun scheduleUsageStatsFlush() {
        synchronized(usageStatsLock) {
            if (scheduledUsageFlush?.isDone == false) return
            scheduledUsageFlush = usageFlushExecutor.schedule({
                synchronized(usageStatsLock) { scheduledUsageFlush = null }
                try { flushUsageStats() }
                catch (e: Exception) {
                    AppLog.e("ReportCosts", "Cost flush failed; durable entries retained", e)
                    scheduleUsageStatsFlush()
                }
            }, USAGE_STATS_FLUSH_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    fun flushUsageStats() {
        var journalFailure: Exception? = null
        try { ReportCostJournal.flush(filesDir) }
        catch (e: Exception) { journalFailure = e }
        synchronized(usageStatsLock) {
            usageStatsCache?.let { cache ->
                lastUsageStatsFlush = System.currentTimeMillis()
                saveUsageStats(HashMap(cache))
                usageCategoryStatsCache?.let { saveUsageCategoryStats(HashMap(it)) }
                usageReportStatsCache?.let { saveUsageReportStats(HashMap(it)) }
            }
        }
        journalFailure?.let { throw it }
    }

    suspend fun updateUsageStatsAsync(provider: AppService, model: String, inputTokens: Int, outputTokens: Int, totalTokens: Int = inputTokens + outputTokens, kind: String = "report", searchUnits: Int = 0, durationMs: Long? = null) =
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { updateUsageStats(provider, model, inputTokens, outputTokens, totalTokens, kind, searchUnits, durationMs) }

    suspend fun updateUsageStatsAsync(provider: AppService, model: String, usage: TokenUsage, kind: String = "report", searchUnits: Int = 0, durationMs: Long? = null) =
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { updateUsageStats(provider, model, usage, kind, searchUnits, durationMs) }

    fun clearUsageStats() = synchronized(usageStatsLock) {
        usageStatsCache?.clear()
        usageCategoryStatsCache?.clear()
        usageReportStatsCache?.clear()
        // Reset the flush timestamp so the next updateUsageStats
        // doesn't skip the disk flush against a 2-second debounce
        // window inherited from a recent pre-clear write — that
        // window made the post-clear cache hold writes invisible
        // on disk for the rest of the debounce period.
        lastUsageStatsFlush = 0L
        filesDir?.let { File(it, FILE_USAGE_STATS) }?.let { if (it.exists()) it.delete() }
        filesDir?.let { File(it, FILE_USAGE_CATEGORY_STATS) }?.let { if (it.exists()) it.delete() }
        filesDir?.let { File(it, FILE_USAGE_REPORT_STATS) }?.let { if (it.exists()) it.delete() }
    }

    // ===== Model Lists Cache =====

    fun isModelListCacheValid(provider: AppService): Boolean {
        val ts = prefs.getLong(KEY_MODEL_LIST_TIMESTAMP_PREFIX + provider.id, 0L)
        return System.currentTimeMillis() - ts < MODEL_LISTS_CACHE_DURATION_MS
    }

    // ===== Private helpers =====

    private fun loadJsonList(key: String): List<String>? {
        val json = prefs.getString(key, null) ?: return null
        return try { gson.fromJson(json, TypeTokens.listStringType) } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadList(key: String, type: Type, transform: ((Any?) -> List<T>)? = null): List<T> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val raw = gson.fromJson<Any>(json, type)
            if (transform != null) transform(raw) else (raw as? List<T>) ?: emptyList()
        } catch (_: Exception) {
            // A single malformed element shouldn't drop the WHOLE list (e.g. all
            // agents/swarms/prompts vanish). Re-parse the array element by
            // element, keeping the good ones (audit data#82). Only for the plain
            // List<T> path — the transform path handles its own shape.
            if (transform != null) return emptyList()
            val elemType = (type as? java.lang.reflect.ParameterizedType)
                ?.actualTypeArguments?.firstOrNull() ?: return emptyList()
            runCatching {
                com.google.gson.JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                    runCatching { gson.fromJson<T>(el, elemType) }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun loadMap(key: String): Map<String, String> {
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try { gson.fromJson(json, TypeTokens.mapStringStringType) ?: emptyMap() } catch (_: Exception) { emptyMap() }
    }

    private fun loadUiColorOverrides(): Map<String, Int> {
        val stored = prefs.getString(KEY_UI_COLOR_OVERRIDES, null)?.let {
            try { gson.fromJson<Map<String, Int>>(it, TypeTokens.mapStringIntType) } catch (_: Exception) { null }
        }.orEmpty()
        val colors = AppColors.normalizeUiColorOverrides(stored).toMutableMap()
        if ("CardBackgroundAlt" !in stored) {
            colors["CardBackgroundAlt"] = prefs.getInt(KEY_UI_CARD_BACKGROUND_ARGB, DEFAULT_UI_CARD_BACKGROUND_ARGB)
        }
        if ("ButtonBackground" !in stored) {
            colors["ButtonBackground"] = prefs.getInt(KEY_UI_BUTTON_BACKGROUND_ARGB, DEFAULT_UI_BUTTON_BACKGROUND_ARGB)
        }
        return colors
    }

    private fun loadEndpoints(): Map<AppService, List<Endpoint>> {
        val json = prefs.getString(KEY_AI_ENDPOINTS, null) ?: return emptyMap()
        return try {
            val rawMap: Map<String, List<Endpoint>>? = gson.fromJson(json, TypeTokens.mapEndpointsType)
            rawMap?.mapKeys { AppService.findById(it.key) }?.entries?.mapNotNull { (k, v) -> k?.let { it to v } }?.toMap() ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    companion object {
        /** Master switch for usage-statistics recording. When false,
         *  [updateUsageStats] is a no-op — no per-provider / per-model token
         *  or cost rows accumulate and the Live Dashboard rolling rate stays
         *  idle. Mirrored from [GeneralSettings.usageStatsEnabled] by
         *  AppViewModel so the non-UI [updateUsageStats] chokepoint consults a
         *  single global. */
        @Volatile var usageStatsEnabled: Boolean = true
        private val usageStatsLock = Any()
        @Volatile private var usageStatsCache: java.util.concurrent.ConcurrentHashMap<String, UsageStats>? = null
        @Volatile private var usageCategoryStatsCache: java.util.concurrent.ConcurrentHashMap<String, UsageCategoryStats>? = null
        @Volatile private var usageReportStatsCache: java.util.concurrent.ConcurrentHashMap<String, UsageReportStats>? = null
        private val usageFlushExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "report-cost-flush").apply { isDaemon = true }
        }
        private var scheduledUsageFlush: java.util.concurrent.ScheduledFuture<*>? = null
        @Volatile private var lastUsageStatsFlush: Long = 0L
        private const val USAGE_STATS_FLUSH_MS = 2_000L
        const val PREFS_NAME = "eval_prefs"

        private const val KEY_USER_NAME = "user_name"
        private const val KEY_HUGGINGFACE_API_KEY = "huggingface_api_key"
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_AA_API_KEY = "artificial_analysis_api_key"
        private const val KEY_LLMSTATS_API_KEY = "llmstats_api_key"
        private const val KEY_DEFAULT_EMAIL = "default_email"
        private const val KEY_DEFAULT_TYPE_PATHS = "default_type_paths"
        private const val KEY_TRACING_ENABLED = "tracing_enabled"
        private const val KEY_AUDIT_LOG_ENABLED = "audit_log_enabled"
        private const val KEY_USAGE_STATS_ENABLED = "usage_stats_enabled"
        private const val KEY_LOGGING_MASTER_ENABLED = "logging_master_enabled"
        private const val KEY_FULL_SCREEN = "full_screen"
        private const val KEY_MODEL_NAME_LAYOUT = "model_name_layout"
        private const val KEY_APP_HOME = "app_home"
        private const val KEY_UI_CARD_BACKGROUND_ARGB = "ui_card_background_argb"
        private const val KEY_UI_BUTTON_BACKGROUND_ARGB = "ui_button_background_argb"
        private const val KEY_UI_COLOR_OVERRIDES = "ui_color_overrides"
        private const val KEY_UI_COLOR_OVERRIDES_DAY = "ui_color_overrides_day"
        private const val KEY_UI_COLOR_MODE = "ui_color_mode"
        private const val KEY_METADATA_ENABLED = "metadata_enabled"
        private const val KEY_ICON_GEN_ENABLED = "icon_gen_enabled"
        private const val KEY_REPORT_LANGUAGE_GEN_ENABLED = "report_language_gen_enabled"
        private const val KEY_REPORT_TITLE_MODE = "report_title_mode"
        private const val KEY_PER_MODEL_ICON_GEN_ENABLED = "per_model_icon_gen_enabled"
        private const val KEY_PER_MODEL_TITLE_GEN_ENABLED = "per_model_title_gen_enabled"
        private const val KEY_USE_INTERNAL_PROMPTS_ICONS = "use_internal_prompts_icons"
        private const val KEY_AUTOSTART_ITEMS_ENABLED = "autostart_items_enabled"
        private const val KEY_AUTOSTART_FAN_META = "autostart_fan_meta"
        private const val KEY_AUTO_CREATE_RERANK_MODERATION = "auto_create_rerank_moderation"
        private const val KEY_METADATA_ICONS = "metadata_icons"
        private const val KEY_APP_WIDE_SYSTEM_PROMPT_ID = "app_wide_system_prompt_id"
        private const val KEY_APP_WIDE_PARAMETERS_IDS = "app_wide_parameters_ids"
        private const val KEY_REPORT_MODEL_SYSTEM_PROMPT_ID = "report_model_system_prompt_id"
        private const val KEY_REPORT_MODEL_PARAMETERS_IDS = "report_model_parameters_ids"
        private const val KEY_SHOW_KNOWLEDGE_CARD = "show_knowledge_card"
        private const val KEY_EXPERIMENTAL_FEATURES = "experimental_features"
        private const val KEY_PINNED_DASHBOARD_CARDS = "pinned_dashboard_cards"
        private const val KEY_DASHBOARD_CARD_ORDER = "dashboard_card_order"
        private const val KEY_RECENT_REPORT_MODELS = "recent_report_models"
        private const val KEY_STREAMING_READ_TIMEOUT_SEC = "streaming_read_timeout_sec"
        private const val KEY_NONSTREAMING_READ_TIMEOUT_SEC = "nonstreaming_read_timeout_sec"
        private const val KEY_BATCH_ITEM_TIMEOUT_SEC = "batch_item_timeout_sec"
        private const val KEY_MAX_CALLS_PER_PROVIDER_PER_MINUTE = "max_calls_per_provider_per_minute"
        private const val KEY_MAX_CONCURRENT_CALLS_PER_PROVIDER = "max_concurrent_calls_per_provider"
        private const val KEY_MAX_CONCURRENT_API_CALLS = "max_concurrent_api_calls"
        private const val KEY_MAX_RETRIES_ON_429 = "max_retries_on_429"
        private const val KEY_RETRY_BACKOFF_MS_429 = "retry_backoff_ms_429"
        private const val KEY_MAX_RETRIES_ON_529 = "max_retries_on_529"
        private const val KEY_RETRY_BACKOFF_MS_529 = "retry_backoff_ms_529"
        private const val KEY_TYPE_A_BENCH_ENABLED = "type_a_bench_enabled"
        private const val KEY_TYPE_A_BENCH_SECONDS = "type_a_bench_seconds"
        private const val KEY_TYPE_A_BENCH_MAX_ATTEMPTS = "type_a_bench_max_attempts"
        private const val KEY_LOG_LEVEL = "log_level"
        private const val KEY_SHOW_LADYBUG_ICONS = "show_ladybug_icons"
        private const val KEY_RANKING_WEIGHTS = "ranking_weights"
        private const val KEY_AI_AGENTS = "ai_agents"
        private const val KEY_AI_FLOCKS = "ai_flocks"
        private const val KEY_AI_SWARMS = "ai_swarms"
        private const val KEY_AI_PARAMETERS = "ai_parameters"
        private const val KEY_AI_SYSTEM_PROMPTS = "ai_system_prompts"
        // Persisted under the legacy "ai_meta_prompts" key so users
        // who already have seeded entries from the previous build don't
        // lose them across the rename to InternalPrompt.
        private const val KEY_AI_INTERNAL_PROMPTS = "ai_meta_prompts"
        private const val KEY_AI_EXAMPLE_PROMPTS = "ai_example_prompts"
        private const val KEY_AI_ENDPOINTS = "ai_endpoints"
        private const val KEY_PROVIDER_STATES = "provider_states"
        private const val KEY_AI_MODEL_TYPE_OVERRIDES = "ai_model_type_overrides"
        private const val KEY_AI_BLOCKED_MODELS = "ai_blocked_models"
        private const val KEY_AI_TEST_EXCLUDED_MODELS = "ai_test_excluded_models"
        private const val KEY_AI_INACCESSIBLE_MODELS = "ai_inaccessible_models"
        private const val KEY_AI_DEFAULT_META_ITEMS = "ai_default_meta_items"
        private const val KEY_AI_DISABLED_INFO_PROVIDERS = "ai_disabled_info_providers"
        const val KEY_LAST_AI_REPORT_TITLE = "last_ai_report_title"
        const val KEY_LAST_AI_REPORT_PROMPT = "last_ai_report_prompt"
        private const val FILE_USAGE_STATS = "usage-stats.json"
        private const val FILE_USAGE_CATEGORY_STATS = "usage-category-stats.json"
        private const val FILE_USAGE_REPORT_STATS = "usage-report-stats.json"
        private const val KEY_MODEL_LIST_TIMESTAMP_PREFIX = "model_list_timestamp_"
        private const val MODEL_LISTS_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}
