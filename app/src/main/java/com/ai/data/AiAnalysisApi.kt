package com.ai.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Custom deserializer for cost field that can be either a Double (OpenRouter)
 * or an object with total_cost (Perplexity).
 */
class FlexibleCostDeserializer : JsonDeserializer<Double?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Double? {
        if (json == null || json.isJsonNull) return null
        return try {
            when {
                json.isJsonPrimitive && json.asJsonPrimitive.isNumber -> json.asDouble
                json.isJsonObject -> json.asJsonObject.get("total_cost")?.asDouble
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * API format used by a provider.
 */
enum class ApiFormat {
    OPENAI_COMPATIBLE,  // 28 providers using OpenAI-compatible chat/completions
    ANTHROPIC,          // Anthropic Messages API
    GOOGLE              // Google Gemini GenerativeAI API
}

/**
 * Data class representing a supported AI service provider.
 * Identity is based on `id` only — equality, hashing, and serialization all use `id`.
 */
class AiService(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val adminUrl: String,
    val defaultModel: String,
    val openRouterName: String? = null,
    val apiFormat: ApiFormat = ApiFormat.OPENAI_COMPATIBLE,
    val chatPath: String = "v1/chat/completions",
    val modelsPath: String? = "v1/models",
    val prefsKey: String = "",
    // Provider-specific quirks
    val seedFieldName: String = "seed",
    val supportsCitations: Boolean = false,
    val supportsSearchRecency: Boolean = false,
    val extractApiCost: Boolean = false,
    val costTicksDivisor: Double? = null,
    val modelListFormat: String = "object",
    val modelFilter: String? = null,
    val litellmPrefix: String? = null,
    val apiModelsLegacyKey: String? = null,
    val hardcodedModels: List<String>? = null,
    val defaultModelSource: String? = null
) {
    override fun equals(other: Any?): Boolean = other is AiService && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        val OPENAI = AiService(id = "OPENAI", displayName = "OpenAI", baseUrl = "https://api.openai.com/", adminUrl = "https://platform.openai.com/settings/organization/api-keys", defaultModel = "gpt-4o-mini", openRouterName = "openai", prefsKey = "ai_openai", apiModelsLegacyKey = "api_models_openai", defaultModelSource = "API")
        val ANTHROPIC = AiService(id = "ANTHROPIC", displayName = "Anthropic", baseUrl = "https://api.anthropic.com/", adminUrl = "https://console.anthropic.com/settings/keys", defaultModel = "claude-sonnet-4-20250514", openRouterName = "anthropic", apiFormat = ApiFormat.ANTHROPIC, chatPath = "v1/messages", modelsPath = "v1/models", prefsKey = "ai_anthropic", apiModelsLegacyKey = "api_models_claude", hardcodedModels = listOf("claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229", "claude-3-sonnet-20240229", "claude-3-haiku-20240307"))
        val GOOGLE = AiService(id = "GOOGLE", displayName = "Google", baseUrl = "https://generativelanguage.googleapis.com/", adminUrl = "https://aistudio.google.com/app/apikey", defaultModel = "gemini-2.0-flash", openRouterName = "google", apiFormat = ApiFormat.GOOGLE, chatPath = "v1beta/models/{model}:generateContent", modelsPath = "v1beta/models", prefsKey = "ai_google", litellmPrefix = "gemini", apiModelsLegacyKey = "api_models_google", defaultModelSource = "API")
        val XAI = AiService(id = "XAI", displayName = "xAI", baseUrl = "https://api.x.ai/", adminUrl = "https://console.x.ai/", defaultModel = "grok-3-mini", openRouterName = "x-ai", prefsKey = "ai_xai", costTicksDivisor = 10_000_000_000.0, modelFilter = "grok", litellmPrefix = "xai", apiModelsLegacyKey = "api_models_xai", defaultModelSource = "API")
        val GROQ = AiService(id = "GROQ", displayName = "Groq", baseUrl = "https://api.groq.com/openai/", adminUrl = "https://console.groq.com/keys", defaultModel = "llama-3.3-70b-versatile", prefsKey = "ai_groq", litellmPrefix = "groq", apiModelsLegacyKey = "api_models_groq", defaultModelSource = "API")
        val DEEPSEEK = AiService(id = "DEEPSEEK", displayName = "DeepSeek", baseUrl = "https://api.deepseek.com/", adminUrl = "https://platform.deepseek.com/api_keys", defaultModel = "deepseek-chat", openRouterName = "deepseek", chatPath = "chat/completions", modelsPath = "models", prefsKey = "ai_deepseek", modelFilter = "deepseek", litellmPrefix = "deepseek", apiModelsLegacyKey = "api_models_deepseek", defaultModelSource = "API")
        val MISTRAL = AiService(id = "MISTRAL", displayName = "Mistral", baseUrl = "https://api.mistral.ai/", adminUrl = "https://console.mistral.ai/api-keys/", defaultModel = "mistral-small-latest", openRouterName = "mistralai", prefsKey = "ai_mistral", seedFieldName = "random_seed", modelFilter = "mistral|open-mistral|codestral|pixtral", apiModelsLegacyKey = "api_models_mistral", defaultModelSource = "API")
        val PERPLEXITY = AiService(id = "PERPLEXITY", displayName = "Perplexity", baseUrl = "https://api.perplexity.ai/", adminUrl = "https://www.perplexity.ai/settings/api", defaultModel = "sonar", openRouterName = "perplexity", chatPath = "chat/completions", modelsPath = "models", prefsKey = "ai_perplexity", supportsCitations = true, supportsSearchRecency = true, modelFilter = "sonar|llama", apiModelsLegacyKey = "api_models_perplexity", hardcodedModels = listOf("sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research"))
        val TOGETHER = AiService(id = "TOGETHER", displayName = "Together", baseUrl = "https://api.together.xyz/", adminUrl = "https://api.together.xyz/settings/api-keys", defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo", prefsKey = "ai_together", modelListFormat = "array", modelFilter = "chat|instruct|llama", litellmPrefix = "together_ai", apiModelsLegacyKey = "api_models_together", defaultModelSource = "API")
        val OPENROUTER = AiService(id = "OPENROUTER", displayName = "OpenRouter", baseUrl = "https://openrouter.ai/api/", adminUrl = "https://openrouter.ai/keys", defaultModel = "anthropic/claude-3.5-sonnet", prefsKey = "ai_openrouter", extractApiCost = true, apiModelsLegacyKey = "api_models_openrouter", defaultModelSource = "API")
        val SILICONFLOW = AiService(id = "SILICONFLOW", displayName = "SiliconFlow", baseUrl = "https://api.siliconflow.cn/", adminUrl = "https://cloud.siliconflow.cn/account/ak", defaultModel = "Qwen/Qwen2.5-7B-Instruct", prefsKey = "ai_siliconflow", apiModelsLegacyKey = "api_models_siliconflow", defaultModelSource = "API", hardcodedModels = listOf("Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-14B-Instruct", "Qwen/Qwen2.5-32B-Instruct", "Qwen/Qwen2.5-72B-Instruct", "Qwen/QwQ-32B", "deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "THUDM/glm-4-9b-chat", "meta-llama/Llama-3.3-70B-Instruct"))
        val ZAI = AiService(id = "ZAI", displayName = "Z.AI", baseUrl = "https://api.z.ai/api/paas/v4/", adminUrl = "https://open.bigmodel.cn/usercenter/apikeys", defaultModel = "glm-4.7-flash", openRouterName = "z-ai", chatPath = "chat/completions", modelsPath = "models", prefsKey = "ai_zai", modelFilter = "glm|codegeex|charglm", apiModelsLegacyKey = "api_models_zai", defaultModelSource = "API", hardcodedModels = listOf("glm-4.7-flash", "glm-4.7", "glm-4.5-flash", "glm-4.5", "glm-4-plus", "glm-4-long", "glm-4-flash"))
        val MOONSHOT = AiService(id = "MOONSHOT", displayName = "Moonshot", baseUrl = "https://api.moonshot.ai/", adminUrl = "https://platform.moonshot.ai/console/api-keys", defaultModel = "kimi-latest", openRouterName = "moonshot", prefsKey = "ai_moonshot", apiModelsLegacyKey = "api_models_moonshot", defaultModelSource = "API", hardcodedModels = listOf("kimi-latest", "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"))
        val COHERE = AiService(id = "COHERE", displayName = "Cohere", baseUrl = "https://api.cohere.ai/compatibility/", adminUrl = "https://dashboard.cohere.com/", defaultModel = "command-a-03-2025", openRouterName = "cohere", prefsKey = "ai_cohere", apiModelsLegacyKey = "api_models_cohere", hardcodedModels = listOf("command-a-03-2025", "command-r-plus-08-2024", "command-r-08-2024", "command-r7b-12-2024"))
        val AI21 = AiService(id = "AI21", displayName = "AI21", baseUrl = "https://api.ai21.com/", adminUrl = "https://studio.ai21.com/", defaultModel = "jamba-mini", openRouterName = "ai21", prefsKey = "ai_ai21", apiModelsLegacyKey = "api_models_ai21", hardcodedModels = listOf("jamba-mini", "jamba-large", "jamba-mini-1.7", "jamba-large-1.7"))
        val DASHSCOPE = AiService(id = "DASHSCOPE", displayName = "DashScope", baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/", adminUrl = "https://dashscope.console.aliyun.com/", defaultModel = "qwen-plus", prefsKey = "ai_dashscope", apiModelsLegacyKey = "api_models_dashscope", hardcodedModels = listOf("qwen-plus", "qwen-max", "qwen-turbo", "qwen-long", "qwen3-max", "qwen3-235b-a22b"))
        val FIREWORKS = AiService(id = "FIREWORKS", displayName = "Fireworks", baseUrl = "https://api.fireworks.ai/inference/", adminUrl = "https://app.fireworks.ai/", defaultModel = "accounts/fireworks/models/llama-v3p3-70b-instruct", prefsKey = "ai_fireworks", apiModelsLegacyKey = "api_models_fireworks", hardcodedModels = listOf("accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/deepseek-r1-0528", "accounts/fireworks/models/qwen3-235b-a22b", "accounts/fireworks/models/llama-v3p1-8b-instruct"))
        val CEREBRAS = AiService(id = "CEREBRAS", displayName = "Cerebras", baseUrl = "https://api.cerebras.ai/", adminUrl = "https://cloud.cerebras.ai/", defaultModel = "llama-3.3-70b", prefsKey = "ai_cerebras", apiModelsLegacyKey = "api_models_cerebras", hardcodedModels = listOf("llama-3.3-70b", "llama-4-scout-17b-16e-instruct", "llama3.1-8b", "qwen-3-32b", "deepseek-r1-distill-llama-70b"))
        val SAMBANOVA = AiService(id = "SAMBANOVA", displayName = "SambaNova", baseUrl = "https://api.sambanova.ai/", adminUrl = "https://cloud.sambanova.ai/", defaultModel = "Meta-Llama-3.3-70B-Instruct", prefsKey = "ai_sambanova", apiModelsLegacyKey = "api_models_sambanova", hardcodedModels = listOf("Meta-Llama-3.3-70B-Instruct", "DeepSeek-R1", "DeepSeek-V3-0324", "Qwen3-32B", "Meta-Llama-3.1-8B-Instruct"))
        val BAICHUAN = AiService(id = "BAICHUAN", displayName = "Baichuan", baseUrl = "https://api.baichuan-ai.com/", adminUrl = "https://platform.baichuan-ai.com/", defaultModel = "Baichuan4-Turbo", prefsKey = "ai_baichuan", apiModelsLegacyKey = "api_models_baichuan", hardcodedModels = listOf("Baichuan4-Turbo", "Baichuan4", "Baichuan4-Air", "Baichuan3-Turbo", "Baichuan3-Turbo-128k"))
        val STEPFUN = AiService(id = "STEPFUN", displayName = "StepFun", baseUrl = "https://api.stepfun.com/", adminUrl = "https://platform.stepfun.com/", defaultModel = "step-2-16k", prefsKey = "ai_stepfun", apiModelsLegacyKey = "api_models_stepfun", hardcodedModels = listOf("step-3", "step-2-16k", "step-2-mini", "step-1-8k", "step-1-32k", "step-1-128k"))
        val MINIMAX = AiService(id = "MINIMAX", displayName = "MiniMax", baseUrl = "https://api.minimax.io/", adminUrl = "https://platform.minimax.io/", defaultModel = "MiniMax-M2.1", openRouterName = "minimax", prefsKey = "ai_minimax", apiModelsLegacyKey = "api_models_minimax", hardcodedModels = listOf("MiniMax-M2.1", "MiniMax-M2", "MiniMax-M1", "MiniMax-Text-01"))
        val NVIDIA = AiService(id = "NVIDIA", displayName = "NVIDIA", baseUrl = "https://integrate.api.nvidia.com/", adminUrl = "https://build.nvidia.com/", defaultModel = "nvidia/llama-3.1-nemotron-70b-instruct", prefsKey = "ai_nvidia", apiModelsLegacyKey = "api_models_nvidia", defaultModelSource = "API")
        val REPLICATE = AiService(id = "REPLICATE", displayName = "Replicate", baseUrl = "https://api.replicate.com/v1/", adminUrl = "https://replicate.com/account/api-tokens", defaultModel = "meta/meta-llama-3-70b-instruct", chatPath = "chat/completions", modelsPath = null, prefsKey = "ai_replicate", apiModelsLegacyKey = "api_models_replicate", hardcodedModels = listOf("meta/meta-llama-3-70b-instruct", "meta/meta-llama-3-8b-instruct", "mistralai/mistral-7b-instruct-v0.2"))
        val HUGGINGFACE = AiService(id = "HUGGINGFACE", displayName = "Hugging Face", baseUrl = "https://api-inference.huggingface.co/", adminUrl = "https://huggingface.co/settings/tokens", defaultModel = "meta-llama/Llama-3.1-70B-Instruct", modelsPath = null, prefsKey = "ai_huggingface_inference", apiModelsLegacyKey = "api_models_huggingface_inference", hardcodedModels = listOf("meta-llama/Llama-3.1-70B-Instruct", "meta-llama/Llama-3.1-8B-Instruct", "mistralai/Mistral-7B-Instruct-v0.3", "Qwen/Qwen2.5-72B-Instruct"))
        val LAMBDA = AiService(id = "LAMBDA", displayName = "Lambda", baseUrl = "https://api.lambdalabs.com/", adminUrl = "https://cloud.lambdalabs.com/api-keys", defaultModel = "hermes-3-llama-3.1-405b-fp8", prefsKey = "ai_lambda", apiModelsLegacyKey = "api_models_lambda", defaultModelSource = "API")
        val LEPTON = AiService(id = "LEPTON", displayName = "Lepton", baseUrl = "https://api.lepton.ai/", adminUrl = "https://dashboard.lepton.ai/", defaultModel = "llama3-1-70b", modelsPath = null, prefsKey = "ai_lepton", apiModelsLegacyKey = "api_models_lepton", hardcodedModels = listOf("llama3-1-70b", "llama3-1-8b", "mistral-7b", "gemma2-9b"))
        val YI = AiService(id = "YI", displayName = "01.AI", baseUrl = "https://api.01.ai/", adminUrl = "https://platform.01.ai/", defaultModel = "yi-lightning", prefsKey = "ai_yi", apiModelsLegacyKey = "api_models_yi", defaultModelSource = "API", hardcodedModels = listOf("yi-lightning", "yi-large", "yi-medium", "yi-spark"))
        val DOUBAO = AiService(id = "DOUBAO", displayName = "Doubao", baseUrl = "https://ark.cn-beijing.volces.com/api/", adminUrl = "https://console.volcengine.com/", defaultModel = "doubao-pro-32k", chatPath = "v3/chat/completions", modelsPath = null, prefsKey = "ai_doubao", apiModelsLegacyKey = "api_models_doubao", hardcodedModels = listOf("doubao-pro-32k", "doubao-pro-128k", "doubao-lite-32k", "doubao-lite-128k"))
        val REKA = AiService(id = "REKA", displayName = "Reka", baseUrl = "https://api.reka.ai/", adminUrl = "https://platform.reka.ai/", defaultModel = "reka-flash", modelsPath = null, prefsKey = "ai_reka", apiModelsLegacyKey = "api_models_reka", hardcodedModels = listOf("reka-core", "reka-flash", "reka-edge"))
        val WRITER = AiService(id = "WRITER", displayName = "Writer", baseUrl = "https://api.writer.com/", adminUrl = "https://app.writer.com/", defaultModel = "palmyra-x-004", prefsKey = "ai_writer", apiModelsLegacyKey = "api_models_writer", hardcodedModels = listOf("palmyra-x-004", "palmyra-x-003-instruct"))

        /** All built-in providers, in display order. */
        val builtIn: List<AiService> = listOf(
            OPENAI, ANTHROPIC, GOOGLE, XAI, GROQ, DEEPSEEK, MISTRAL, PERPLEXITY,
            TOGETHER, OPENROUTER, SILICONFLOW, ZAI, MOONSHOT, COHERE, AI21,
            DASHSCOPE, FIREWORKS, CEREBRAS, SAMBANOVA, BAICHUAN, STEPFUN,
            MINIMAX, NVIDIA, REPLICATE, HUGGINGFACE, LAMBDA, LEPTON, YI,
            DOUBAO, REKA, WRITER
        )

        /** Replaces enum's entries property — returns all built-in providers. */
        val entries: List<AiService> get() = builtIn

        /** Find a provider by its ID string, or null if not found. */
        fun findById(id: String): AiService? = builtIn.find { it.id == id }

        /** Find a provider by its ID string, throwing if not found (like enum valueOf). */
        fun valueOf(id: String): AiService = findById(id)
            ?: throw IllegalArgumentException("Unknown AiService: $id")
    }
}

/**
 * Gson serializer/deserializer for AiService.
 * Serializes as the string ID, deserializes by looking up in companion object.
 */
class AiServiceAdapter : JsonDeserializer<AiService>, JsonSerializer<AiService> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): AiService {
        val id = json?.asString ?: throw JsonParseException("Null AiService")
        return AiService.findById(id) ?: throw JsonParseException("Unknown AiService: $id")
    }
    override fun serialize(src: AiService?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src?.id ?: "")
    }
}

/**
 * Create a Gson instance with AiService type adapter registered.
 * Use this instead of Gson() when serializing/deserializing objects that contain AiService.
 */
fun createAiGson(prettyPrint: Boolean = false): Gson = GsonBuilder()
    .registerTypeAdapter(AiService::class.java, AiServiceAdapter())
    .apply { if (prettyPrint) setPrettyPrinting() }
    .create()

// OpenAI models
data class OpenAiMessage(
    val role: String,
    val content: String?,
    // DeepSeek reasoning models return reasoning in this field
    val reasoning_content: String? = null
)

data class OpenAiRequest(
    val model: String = AiService.OPENAI.defaultModel,
    val messages: List<OpenAiMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null,
    val random_seed: Int? = null,  // Mistral uses random_seed instead of seed
    val response_format: OpenAiResponseFormat? = null,
    val return_citations: Boolean? = null,  // Perplexity
    val search_recency_filter: String? = null,  // Perplexity: "day", "week", "month", "year"
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

data class OpenAiResponseFormat(
    val type: String = "text"  // "text" or "json_object"
)

data class OpenAiChoice(
    val message: OpenAiMessage,
    val index: Int
)

// Cost object structure (used by Perplexity)
data class UsageCost(
    val total_cost: Double? = null
)

data class OpenAiUsage(
    // Chat Completions API uses prompt_tokens/completion_tokens
    val prompt_tokens: Int?,
    val completion_tokens: Int?,
    val total_tokens: Int?,
    // Responses API uses input_tokens/output_tokens
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
    // Cost variations from different providers
    // cost can be a Double (OpenRouter) or object with total_cost (Perplexity) - use custom deserializer
    @JsonAdapter(FlexibleCostDeserializer::class)
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,   // xAI: cost in billionths of a dollar (despite the name)
    val cost_usd: UsageCost? = null        // Alternative Perplexity cost field
)

// Search result from AI services that perform web searches
data class SearchResult(
    val name: String?,      // Title of the search result
    val url: String?,       // URL of the result
    val snippet: String?    // Text snippet/description
)

data class OpenAiResponse(
    val id: String?,
    val choices: List<OpenAiChoice>?,
    val usage: OpenAiUsage?,
    val error: OpenAiError?,
    val citations: List<String>? = null,  // Perplexity returns citations as URLs
    val search_results: List<SearchResult>? = null,  // Some services return search results
    val related_questions: List<String>? = null  // Perplexity returns follow-up questions
)

data class OpenAiError(
    val message: String?,
    val type: String?
)

// OpenAI Responses API models (for GPT-5.x and newer models)
data class OpenAiResponsesRequest(
    val model: String,
    val input: String,
    val instructions: String? = null
)

// OpenAI Responses API streaming request
data class OpenAiResponsesStreamRequest(
    val model: String,
    val input: List<OpenAiResponsesInputMessage>,
    val instructions: String? = null,
    val stream: Boolean = true
)

data class OpenAiResponsesInputMessage(
    val role: String,
    val content: String
)

data class OpenAiResponsesOutputContent(
    val type: String?,
    val text: String?,
    val annotations: List<Any>? = null
)

data class OpenAiResponsesOutputMessage(
    val type: String?,
    val id: String?,
    val status: String?,
    val role: String?,
    val content: List<OpenAiResponsesOutputContent>?
)

data class OpenAiResponsesApiResponse(
    val id: String?,
    val status: String?,
    val error: OpenAiResponsesError?,
    val output: List<OpenAiResponsesOutputMessage>?,
    val usage: OpenAiUsage?
)

data class OpenAiResponsesError(
    val message: String?,
    val type: String?,
    val code: String?
)

// Anthropic models
data class ClaudeMessage(
    val role: String,
    val content: String
)

data class ClaudeRequest(
    val model: String = AiService.ANTHROPIC.defaultModel,
    val max_tokens: Int? = null,
    val messages: List<ClaudeMessage>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val system: String? = null,
    val stop_sequences: List<String>? = null,
    // Additional parameters (may be ignored by API)
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

data class ClaudeContentBlock(
    val type: String,
    val text: String?
)

data class ClaudeUsage(
    val input_tokens: Int?,
    val output_tokens: Int?,
    // Cost variations (in case API provides them)
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,
    val cost_usd: UsageCost? = null
)

data class ClaudeResponse(
    val id: String?,
    val content: List<ClaudeContentBlock>?,
    val usage: ClaudeUsage?,
    val error: ClaudeError?
)

data class ClaudeError(
    val type: String?,
    val message: String?
)

// Google Gemini models
data class GeminiPart(
    val text: String
)

data class GeminiContent(
    val parts: List<GeminiPart>,
    val role: String? = null  // "user" or "model" for multi-turn chat
)

data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

data class GeminiGenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String>? = null,
    // Additional parameters (may be ignored by API)
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val seed: Int? = null,
    val search: Boolean? = null  // Web search (may be ignored by provider)
)

data class GeminiCandidate(
    val content: GeminiContent?
)

data class GeminiUsageMetadata(
    val promptTokenCount: Int?,
    val candidatesTokenCount: Int?,
    val totalTokenCount: Int?,
    // Cost variations (in case API provides them)
    val cost: Double? = null,
    val cost_in_usd_ticks: Long? = null,
    val cost_usd: UsageCost? = null
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?,
    val usageMetadata: GeminiUsageMetadata?,
    val error: GeminiError?
)

data class GeminiError(
    val code: Int?,
    val message: String?,
    val status: String?
)

/**
 * Retrofit interface for OpenAI API.
 * Uses Chat Completions API for older models (gpt-4o, etc.)
 * and Responses API for newer models (gpt-5.x, etc.)
 */
interface OpenAiApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @POST("v1/responses")
    suspend fun createResponse(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesRequest
    ): Response<OpenAiResponsesApiResponse>

    @retrofit2.http.GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>
}

/**
 * Retrofit interface for Anthropic API.
 */
interface ClaudeApi {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>

    @GET("v1/models")
    suspend fun listModels(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01"
    ): Response<ClaudeModelsResponse>
}

// Response for listing Claude/Anthropic models
data class ClaudeModelsResponse(
    val data: List<ClaudeModelInfo>?
)

data class ClaudeModelInfo(
    val id: String?,
    val display_name: String?,
    val type: String?
)

// Response for listing Gemini models
data class GeminiModelsResponse(
    val models: List<GeminiModel>?
)

data class GeminiModel(
    val name: String?,
    val displayName: String?,
    val supportedGenerationMethods: List<String>?
)

/**
 * Retrofit interface for Google Gemini API.
 */
interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>

    @retrofit2.http.GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String
    ): Response<GeminiModelsResponse>
}

// Response for listing models (OpenAI-compatible format, used by Grok)
data class OpenAiModelsResponse(
    val data: List<OpenAiModel>?
)

data class OpenAiModel(
    val id: String?,
    val owned_by: String?
)

/**
 * Unified Retrofit interface for all OpenAI-compatible providers.
 * Uses @Url to support different chat and models paths per provider.
 */
interface OpenAiCompatibleApi {
    @POST
    suspend fun createChatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiRequest
    ): Response<OpenAiResponse>

    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<OpenAiModelsResponse>

    @GET
    suspend fun listModelsArray(
        @Url url: String,
        @Header("Authorization") authorization: String
    ): Response<List<OpenAiModel>>
}

/**
 * Unified streaming interface for all OpenAI-compatible providers.
 */
interface OpenAiCompatibleStreamApi {
    @retrofit2.http.Streaming
    @POST
    suspend fun createChatCompletionStream(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Factory for creating API instances.
 */
object AiApiFactory {
    private val retrofitCache = ConcurrentHashMap<String, Retrofit>()

    // OkHttpClient with extended timeouts for AI API calls
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(TracingInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(420, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun getRetrofit(baseUrl: String): Retrofit {
        // Retrofit requires base URL to end with /
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return retrofitCache.getOrPut(normalizedUrl) {
            Retrofit.Builder()
                .baseUrl(normalizedUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
    }

    /**
     * Create a unified OpenAI-compatible API for any provider.
     * Uses @Url for dynamic paths, so the caller must provide full path.
     */
    fun createOpenAiCompatibleApi(baseUrl: String): OpenAiCompatibleApi {
        return getRetrofit(baseUrl).create(OpenAiCompatibleApi::class.java)
    }

    /**
     * Create a unified OpenAI-compatible streaming API for any provider.
     */
    fun createOpenAiCompatibleStreamApi(baseUrl: String): OpenAiCompatibleStreamApi {
        return getRetrofit(baseUrl).create(OpenAiCompatibleStreamApi::class.java)
    }

    fun createOpenAiApi(): OpenAiApi {
        return getRetrofit(AiService.OPENAI.baseUrl).create(OpenAiApi::class.java)
    }

    fun createOpenAiApiWithBaseUrl(baseUrl: String): OpenAiApi {
        return getRetrofit(baseUrl).create(OpenAiApi::class.java)
    }

    /**
     * Create OpenAI streaming API with a custom base URL.
     */
    fun createOpenAiStreamApiWithBaseUrl(baseUrl: String): OpenAiStreamApi {
        return getRetrofit(baseUrl).create(OpenAiStreamApi::class.java)
    }

    /**
     * Create Claude API with a custom base URL.
     */
    fun createClaudeApiWithBaseUrl(baseUrl: String): ClaudeApi {
        return getRetrofit(baseUrl).create(ClaudeApi::class.java)
    }

    /**
     * Create Claude streaming API with a custom base URL.
     */
    fun createClaudeStreamApiWithBaseUrl(baseUrl: String): ClaudeStreamApi {
        return getRetrofit(baseUrl).create(ClaudeStreamApi::class.java)
    }

    /**
     * Create Gemini API with a custom base URL.
     */
    fun createGeminiApiWithBaseUrl(baseUrl: String): GeminiApi {
        return getRetrofit(baseUrl).create(GeminiApi::class.java)
    }

    /**
     * Create Gemini streaming API with a custom base URL.
     */
    fun createGeminiStreamApiWithBaseUrl(baseUrl: String): GeminiStreamApi {
        return getRetrofit(baseUrl).create(GeminiStreamApi::class.java)
    }

    fun createClaudeApi(): ClaudeApi {
        return getRetrofit(AiService.ANTHROPIC.baseUrl).create(ClaudeApi::class.java)
    }

    fun createGeminiApi(): GeminiApi {
        return getRetrofit(AiService.GOOGLE.baseUrl).create(GeminiApi::class.java)
    }

    fun createHuggingFaceApi(): HuggingFaceApi {
        return getRetrofit("https://huggingface.co/api/").create(HuggingFaceApi::class.java)
    }

    fun createOpenRouterModelsApi(): OpenRouterModelsApi {
        return getRetrofit(AiService.OPENROUTER.baseUrl).create(OpenRouterModelsApi::class.java)
    }

    // ========== Streaming API Factories ==========

    fun createOpenAiStreamApi(): OpenAiStreamApi {
        return getRetrofit(AiService.OPENAI.baseUrl).create(OpenAiStreamApi::class.java)
    }

    fun createClaudeStreamApi(): ClaudeStreamApi {
        return getRetrofit(AiService.ANTHROPIC.baseUrl).create(ClaudeStreamApi::class.java)
    }

    fun createGeminiStreamApi(): GeminiStreamApi {
        return getRetrofit(AiService.GOOGLE.baseUrl).create(GeminiStreamApi::class.java)
    }

}

// ============================================================================
// Model Info APIs - for fetching detailed model information
// ============================================================================

/**
 * OpenRouter detailed model info response.
 */
data class OpenRouterModelInfo(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val context_length: Int? = null,
    val pricing: OpenRouterPricing? = null,
    val top_provider: OpenRouterTopProvider? = null,
    val architecture: OpenRouterArchitecture? = null,
    val per_request_limits: OpenRouterLimits? = null,
    val supported_parameters: List<String>? = null
)

data class OpenRouterPricing(
    val prompt: String? = null,  // Cost per token (as string)
    val completion: String? = null,
    val image: String? = null,
    val request: String? = null
)

data class OpenRouterTopProvider(
    val context_length: Int? = null,
    val max_completion_tokens: Int? = null,
    val is_moderated: Boolean? = null
)

data class OpenRouterArchitecture(
    val modality: String? = null,  // "text->text", "text+image->text", etc.
    val tokenizer: String? = null,
    val instruct_type: String? = null
)

data class OpenRouterLimits(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null
)

data class OpenRouterModelsDetailedResponse(
    val data: List<OpenRouterModelInfo>
)

/**
 * Hugging Face model info response.
 */
data class HuggingFaceModelInfo(
    val id: String? = null,
    val modelId: String? = null,
    val author: String? = null,
    val sha: String? = null,
    val downloads: Long? = null,
    val likes: Int? = null,
    val tags: List<String>? = null,
    val pipeline_tag: String? = null,
    val library_name: String? = null,
    val createdAt: String? = null,
    val lastModified: String? = null,
    val private: Boolean? = null,
    val gated: Boolean? = null,
    val disabled: Boolean? = null,
    val cardData: HuggingFaceCardData? = null,
    val siblings: List<HuggingFaceSibling>? = null,
    val config: Map<String, Any>? = null
)

data class HuggingFaceCardData(
    val license: String? = null,
    val language: List<String>? = null,
    val datasets: List<String>? = null,
    val base_model: String? = null,
    val model_type: String? = null,
    val pipeline_tag: String? = null,
    val tags: List<String>? = null
)

data class HuggingFaceSibling(
    val rfilename: String? = null
)

/**
 * Retrofit interface for OpenRouter Models API with detailed info.
 */
interface OpenRouterModelsApi {
    @retrofit2.http.GET("v1/models")
    suspend fun listModelsDetailed(
        @Header("Authorization") authorization: String
    ): Response<OpenRouterModelsDetailedResponse>
}

/**
 * Retrofit interface for Hugging Face API.
 * Requires Bearer token authentication.
 */
interface HuggingFaceApi {
    @retrofit2.http.GET("models/{modelId}")
    suspend fun getModelInfo(
        @Path("modelId", encoded = true) modelId: String,
        @Header("Authorization") authorization: String
    ): Response<HuggingFaceModelInfo>
}

// ============================================================================
// Streaming API Models - for SSE (Server-Sent Events) streaming responses
// ============================================================================

/**
 * OpenAI streaming chunk response (used by most providers).
 * Format: data: {"id":"...","choices":[{"delta":{"content":"..."}}]}
 */
data class OpenAiStreamChunk(
    val id: String?,
    val choices: List<StreamChoice>?,
    val created: Long?
)

data class StreamChoice(
    val index: Int?,
    val delta: StreamDelta?,
    val finish_reason: String?
)

data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    val reasoning_content: String? = null  // DeepSeek reasoning models
)

/**
 * Anthropic streaming event format.
 * Events: message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop
 */
data class ClaudeStreamEvent(
    val type: String,
    val index: Int? = null,
    val delta: ClaudeStreamDelta? = null,
    val content_block: ClaudeStreamContentBlock? = null
)

data class ClaudeStreamDelta(
    val type: String? = null,
    val text: String? = null,
    val stop_reason: String? = null
)

data class ClaudeStreamContentBlock(
    val type: String? = null,
    val text: String? = null
)

/**
 * Google Gemini streaming response.
 * Returns array of candidates with partial content.
 */
data class GeminiStreamChunk(
    val candidates: List<GeminiStreamCandidate>?
)

data class GeminiStreamCandidate(
    val content: GeminiContent?,
    val finishReason: String?
)

// ============================================================================
// Streaming Request Models - with stream: true parameter
// ============================================================================

/**
 * OpenAI streaming request (adds stream: true).
 */
data class OpenAiStreamRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val frequency_penalty: Float? = null,
    val presence_penalty: Float? = null,
    val stop: List<String>? = null,
    val seed: Int? = null
)

/**
 * Claude streaming request (adds stream: true).
 */
data class ClaudeStreamRequest(
    val model: String,
    val messages: List<ClaudeMessage>,
    val stream: Boolean = true,
    val max_tokens: Int = 4096,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val system: String? = null,
    val stop_sequences: List<String>? = null
)

/**
 * Gemini doesn't use a separate stream request - uses different endpoint.
 */

// ============================================================================
// Streaming API Interfaces - using ResponseBody for raw stream access
// ============================================================================

/**
 * Streaming API interface for OpenAI-compatible providers.
 */
interface OpenAiStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/chat/completions")
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiStreamRequest
    ): Response<okhttp3.ResponseBody>

    @retrofit2.http.Streaming
    @POST("v1/responses")
    suspend fun createResponseStream(
        @Header("Authorization") authorization: String,
        @Body request: OpenAiResponsesStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Anthropic.
 */
interface ClaudeStreamApi {
    @retrofit2.http.Streaming
    @POST("v1/messages")
    suspend fun createMessageStream(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeStreamRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Streaming API interface for Google Gemini.
 */
interface GeminiStreamApi {
    @retrofit2.http.Streaming
    @POST("v1beta/models/{model}:streamGenerateContent")
    suspend fun streamGenerateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Query("alt") alt: String = "sse",
        @Body request: GeminiRequest
    ): Response<okhttp3.ResponseBody>
}

