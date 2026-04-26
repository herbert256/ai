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
 * Rule mapping model prefixes to endpoint types.
 * Used to route models to different API endpoints (e.g., OpenAI Responses API for gpt-5.x).
 */
data class EndpointRule(
    val modelPrefix: String,
    val endpointType: String  // "responses" or "chat" (default)
)

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
    val chatPath: String = "v1/chat/completions",
    val responsesPath: String? = null,
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
    val defaultModelSource: String? = null,
    val endpointRules: List<EndpointRule> = emptyList()
) {
    val modelFilterRegex: Regex? by lazy { modelFilter?.toRegex(RegexOption.IGNORE_CASE) }

    override fun equals(other: Any?): Boolean = other is AppService && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        val entries: List<AppService> get() = ProviderRegistry.getAll()
        fun findById(id: String): AppService? = ProviderRegistry.findById(id)
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
