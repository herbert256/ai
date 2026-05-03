package com.ai.data

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Data class representing a supported AI service provider.
 * Identity is based on `id` only — equality, hashing, and serialization all use `id`.
 */
class AppService(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val adminUrl: String,
    val defaultModel: String,
    val openRouterName: String? = null,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE,
    /** Per-type API paths the provider exposes ("chat" → "v1/chat/completions",
     *  "embedding" → "v1/embeddings", etc.). `chatPath` and `responsesPath` are
     *  computed views over this map for back-compat. */
    val typePaths: Map<String, String> = emptyMap(),
    val modelsPath: String? = "v1/models",
    val prefsKey: String = "",
    val seedFieldName: String = "seed",
    val supportsCitations: Boolean = false,
    val supportsSearchRecency: Boolean = false,
    val extractApiCost: Boolean = false,
    val costTicksDivisor: Double? = null,
    val modelListFormat: String = "object",
    val modelFilter: String? = null,
    val litellmPrefix: String? = null,
    val hardcodedModels: List<String>? = null,
    val defaultModelSource: String? = null
) {
    val modelFilterRegex: Regex? by lazy { modelFilter?.toRegex(RegexOption.IGNORE_CASE) }

    /** Path used for the /chat/completions style endpoint. */
    val chatPath: String get() = pathFor(ModelType.CHAT) ?: ModelType.DEFAULT_PATHS[ModelType.CHAT]!!

    /** Path used for OpenAI-style Responses API. Null when neither the provider
     *  nor the user-supplied defaults declare one. */
    val responsesPath: String? get() = typePaths[ModelType.RESPONSES] ?: ModelType.userDefaults[ModelType.RESPONSES]

    /** Resolve a path for any model type. Per-provider override → user-supplied
     *  global default (from AI Setup → Model Types) → hardcoded ModelType.DEFAULT_PATHS. */
    fun pathFor(type: String): String? =
        typePaths[type] ?: ModelType.userDefaults[type] ?: ModelType.DEFAULT_PATHS[type]

    override fun equals(other: Any?): Boolean = other is AppService && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        /** Sentinel AppService used to route chat / report flows
         *  through the on-device MediaPipe LLM Inference runtime
         *  ([com.ai.data.LocalLlm]). Not registered in
         *  [ProviderRegistry] so it doesn't show up in provider
         *  lists / pickers / settings; surfaces only by id "LOCAL"
         *  through [findById] so persisted ChatSessions whose
         *  provider was Local can be reloaded after restart. */
        val LOCAL = AppService(
            id = "LOCAL",
            displayName = "Local",
            baseUrl = "local://",
            adminUrl = "",
            defaultModel = ""
        )
        val entries: List<AppService> get() = ProviderRegistry.getAll()
        fun findById(id: String): AppService? = if (id == "LOCAL") LOCAL else ProviderRegistry.findById(id)
        fun valueOf(id: String): AppService = findById(id)
            ?: throw IllegalArgumentException("Unknown AppService: $id")
    }
}

/**
 * Gson serializer/deserializer for AppService — serializes as string ID.
 */
class AppServiceAdapter : JsonDeserializer<AppService>, JsonSerializer<AppService> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): AppService {
        val id = json?.asString ?: throw JsonParseException("Null AppService")
        return ProviderRegistry.findById(id) ?: throw JsonParseException("Unknown AppService: $id")
    }
    override fun serialize(src: AppService?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.id ?: "")
    }
}
