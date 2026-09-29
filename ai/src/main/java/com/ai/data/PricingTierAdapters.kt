package com.ai.data

import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Readers / writers for the pricing and capability tier records — about
 * 5.5 MB of catalog JSON (tens of thousands of records) that [PricingCache]
 * loads at every start, plus each provider's per-model pricing / capability
 * snapshot saved in the Settings.
 *
 * Two speed-ups, both measured on a debuggable build (the one the app ships
 * as), where app code — Gson included — runs in ART's switch interpreter:
 *  - the records are read with a direct name switch instead of Gson's
 *    reflective adapter (registered on the app-wide Gson);
 *  - on a device, [parseTierMap] tokenizes the tier blobs with the
 *    framework's `android.util.JsonReader`, which is precompiled in the boot
 *    image, instead of Gson's interpreted `JsonReader` — the tokenizer was
 *    most of the tens-of-seconds startup catalog load.
 * Both paths share the record readers below through [TierJson].
 *
 * The JSON shape is exactly what the reflective adapter wrote — the Kotlin
 * property names, declaration order, null properties omitted — so files and
 * prefs written before and after stay interchangeable. Value coercion mirrors
 * Gson's built-in adapters (numbers may arrive quoted, booleans as
 * "true"/"false" strings, unknown names are skipped) and the app Gson's
 * NullSafeFieldAdapterFactory (a missing / null list reads as an empty list).
 */
internal fun GsonBuilder.registerPricingTierAdapters(): GsonBuilder = this
    .registerTypeAdapter(PricingCache.ModelPricing::class.java, gsonAdapter(::readModelPricing, ::writeModelPricing))
    .registerTypeAdapter(ModelCapabilities::class.java, gsonAdapter(::readModelCapabilities, ::writeModelCapabilities))
    .registerTypeAdapter(PricingCache.ModelsDevMeta::class.java, gsonAdapter(::readModelsDevMeta, ::writeModelsDevMeta))
    .registerTypeAdapter(PricingCache.RequestyMeta::class.java, gsonAdapter(::readRequestyMeta, ::writeRequestyMeta))
    .registerTypeAdapter(PricingCache.TrueFoundryMeta::class.java, gsonAdapter(::readTrueFoundryMeta, ::writeTrueFoundryMeta))
    .registerTypeAdapter(PricingCache.LiteLLMMeta::class.java, gsonAdapter(::readLiteLLMMeta, ::writeLiteLLMMeta))
    .registerTypeAdapter(PricingCache.ArtificialAnalysisMeta::class.java, gsonAdapter(::readArtificialAnalysisMeta, ::writeArtificialAnalysisMeta))
    .registerTypeAdapter(PricingCache.GenaiPricesMeta::class.java, gsonAdapter(::readGenaiPricesMeta, ::writeGenaiPricesMeta))

/** True on ART (a device), false on the JVM running unit tests, where the
 *  framework JSON classes are stubs. */
private val onAndroidRuntime: Boolean =
    System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true

/** Parse a tier blob — a JSON object of model id → record — with
 *  [readValue]. On a device the framework tokenizer does the work; elsewhere
 *  (JVM tests) Gson's. Null entries are dropped, like the Gson map path. */
internal fun <T> parseTierMap(reader: java.io.Reader, readValue: (TierJson) -> T): Map<String, T> {
    val json: TierJson = if (onAndroidRuntime) AndroidTierJson(android.util.JsonReader(reader)) else GsonTierJson(JsonReader(reader))
    val out = LinkedHashMap<String, T>()
    json.beginObject()
    while (json.hasNext()) {
        val key = json.nextName()
        if (json.peek() == TierJson.Kind.NULL) { json.nextNull(); continue }
        out[key] = readValue(json)
    }
    json.endObject()
    return out
}

/** The few tokenizer calls the record readers need, over either JSON reader. */
internal interface TierJson {
    enum class Kind { NULL, STRING, NUMBER, BOOLEAN, OTHER }
    fun beginObject(); fun endObject(); fun beginArray(); fun endArray()
    fun hasNext(): Boolean; fun nextName(): String; fun peek(): Kind
    fun nextDouble(): Double; fun nextInt(): Int; fun nextString(): String
    fun nextBoolean(): Boolean; fun nextNull(); fun skipValue()
}

private class GsonTierJson(private val r: JsonReader) : TierJson {
    override fun beginObject() = r.beginObject()
    override fun endObject() = r.endObject()
    override fun beginArray() = r.beginArray()
    override fun endArray() = r.endArray()
    override fun hasNext() = r.hasNext()
    override fun nextName(): String = r.nextName()
    override fun peek() = when (r.peek()) {
        JsonToken.NULL -> TierJson.Kind.NULL
        JsonToken.STRING -> TierJson.Kind.STRING
        JsonToken.NUMBER -> TierJson.Kind.NUMBER
        JsonToken.BOOLEAN -> TierJson.Kind.BOOLEAN
        else -> TierJson.Kind.OTHER
    }
    override fun nextDouble() = r.nextDouble()
    override fun nextInt() = r.nextInt()
    override fun nextString(): String = r.nextString()
    override fun nextBoolean() = r.nextBoolean()
    override fun nextNull() = r.nextNull()
    override fun skipValue() = r.skipValue()
}

private class AndroidTierJson(private val r: android.util.JsonReader) : TierJson {
    override fun beginObject() = r.beginObject()
    override fun endObject() = r.endObject()
    override fun beginArray() = r.beginArray()
    override fun endArray() = r.endArray()
    override fun hasNext() = r.hasNext()
    override fun nextName(): String = r.nextName()
    override fun peek() = when (r.peek()) {
        android.util.JsonToken.NULL -> TierJson.Kind.NULL
        android.util.JsonToken.STRING -> TierJson.Kind.STRING
        android.util.JsonToken.NUMBER -> TierJson.Kind.NUMBER
        android.util.JsonToken.BOOLEAN -> TierJson.Kind.BOOLEAN
        else -> TierJson.Kind.OTHER
    }
    override fun nextDouble() = r.nextDouble()
    override fun nextInt() = r.nextInt()
    override fun nextString(): String = r.nextString()
    override fun nextBoolean() = r.nextBoolean()
    override fun nextNull() = r.nextNull()
    override fun skipValue() = r.skipValue()
}

private fun <T> gsonAdapter(read: (TierJson) -> T, write: (JsonWriter, T) -> Unit): TypeAdapter<T> =
    object : TypeAdapter<T>() {
        override fun write(out: JsonWriter, value: T) = write(out, value)
        override fun read(reader: JsonReader): T = read(GsonTierJson(reader))
    }.nullSafe()

private fun TierJson.optDouble(): Double? =
    if (peek() == TierJson.Kind.NULL) { nextNull(); null } else nextDouble()

private fun TierJson.optInt(): Int? =
    if (peek() == TierJson.Kind.NULL) { nextNull(); null } else nextInt()

private fun TierJson.optBoolean(): Boolean? = when (peek()) {
    TierJson.Kind.NULL -> { nextNull(); null }
    TierJson.Kind.STRING -> nextString().toBoolean()
    else -> nextBoolean()
}

private fun TierJson.optString(): String? = when (peek()) {
    TierJson.Kind.NULL -> { nextNull(); null }
    TierJson.Kind.BOOLEAN -> nextBoolean().toString()
    else -> nextString()
}

private fun TierJson.optStringList(): List<String>? {
    if (peek() == TierJson.Kind.NULL) { nextNull(); return null }
    val out = ArrayList<String>()
    beginArray()
    while (hasNext()) optString()?.let(out::add)
    endArray()
    return out
}

/** Reads one JSON object, handing each property name to [field]; [field]
 *  returns false for a name it doesn't know, which is then skipped. */
private inline fun TierJson.readObject(field: (String) -> Boolean) {
    beginObject()
    while (hasNext()) {
        val name = nextName()
        if (!field(name)) skipValue()
    }
    endObject()
}

private fun JsonWriter.opt(name: String, value: Double?) { if (value != null) name(name).value(value) }
private fun JsonWriter.opt(name: String, value: Int?) { if (value != null) name(name).value(value.toLong()) }
private fun JsonWriter.opt(name: String, value: Boolean?) { if (value != null) name(name).value(value) }
private fun JsonWriter.opt(name: String, value: String?) { if (value != null) name(name).value(value) }
private fun JsonWriter.optList(name: String, value: List<String>?) {
    value ?: return
    name(name).beginArray()
    value.forEach { value(it) }
    endArray()
}

internal fun readModelPricing(r: TierJson): PricingCache.ModelPricing {
    var modelId: String? = null
    var promptPrice: Double? = null
    var completionPrice: Double? = null
    var source: String? = null
    var cachedReadPrice: Double? = null
    var cachedWritePrice: Double? = null
    var promptPriceAbove200k: Double? = null
    var completionPriceAbove200k: Double? = null
    var cachedReadPriceAbove200k: Double? = null
    var cachedWritePriceAbove200k: Double? = null
    var perQueryPrice: Double? = null
    r.readObject { name ->
        when (name) {
            "modelId" -> modelId = r.optString()
            "promptPrice" -> promptPrice = r.optDouble()
            "completionPrice" -> completionPrice = r.optDouble()
            "source" -> source = r.optString()
            "cachedReadPrice" -> cachedReadPrice = r.optDouble()
            "cachedWritePrice" -> cachedWritePrice = r.optDouble()
            "promptPriceAbove200k" -> promptPriceAbove200k = r.optDouble()
            "completionPriceAbove200k" -> completionPriceAbove200k = r.optDouble()
            "cachedReadPriceAbove200k" -> cachedReadPriceAbove200k = r.optDouble()
            "cachedWritePriceAbove200k" -> cachedWritePriceAbove200k = r.optDouble()
            "perQueryPrice" -> perQueryPrice = r.optDouble()
            else -> return@readObject false
        }
        true
    }
    return PricingCache.ModelPricing(
        modelId = modelId.orEmpty(),
        promptPrice = promptPrice ?: 0.0,
        completionPrice = completionPrice ?: 0.0,
        source = source ?: "unknown",
        cachedReadPrice = cachedReadPrice,
        cachedWritePrice = cachedWritePrice,
        promptPriceAbove200k = promptPriceAbove200k,
        completionPriceAbove200k = completionPriceAbove200k,
        cachedReadPriceAbove200k = cachedReadPriceAbove200k,
        cachedWritePriceAbove200k = cachedWritePriceAbove200k,
        perQueryPrice = perQueryPrice ?: 0.0
    )
}

private fun writeModelPricing(out: JsonWriter, value: PricingCache.ModelPricing) {
    out.beginObject()
    out.name("modelId").value(value.modelId)
    out.name("promptPrice").value(value.promptPrice)
    out.name("completionPrice").value(value.completionPrice)
    out.name("source").value(value.source)
    out.opt("cachedReadPrice", value.cachedReadPrice)
    out.opt("cachedWritePrice", value.cachedWritePrice)
    out.opt("promptPriceAbove200k", value.promptPriceAbove200k)
    out.opt("completionPriceAbove200k", value.completionPriceAbove200k)
    out.opt("cachedReadPriceAbove200k", value.cachedReadPriceAbove200k)
    out.opt("cachedWritePriceAbove200k", value.cachedWritePriceAbove200k)
    out.name("perQueryPrice").value(value.perQueryPrice)
    out.endObject()
}

private fun readModelCapabilities(r: TierJson): ModelCapabilities {
    var vision: Boolean? = null; var functionCalling: Boolean? = null
    var contextLength: Int? = null; var maxOutput: Int? = null; var reasoning: Boolean? = null
    var effortLevels: List<String>? = null; var pdf: Boolean? = null; var aliases: List<String>? = null
    var deprecationDate: String? = null; var deprecationReplacement: String? = null
    var temperature: Float? = null; var stops: List<String>? = null
    r.readObject { name ->
        when (name) {
            "supportsVision" -> vision = r.optBoolean()
            "supportsFunctionCalling" -> functionCalling = r.optBoolean()
            "contextLength" -> contextLength = r.optInt()
            "maxOutputTokens" -> maxOutput = r.optInt()
            "supportsReasoning" -> reasoning = r.optBoolean()
            "reasoningEffortLevels" -> effortLevels = r.optStringList()
            "supportsPdfInput" -> pdf = r.optBoolean()
            "aliases" -> aliases = r.optStringList()
            "deprecationDate" -> deprecationDate = r.optString()
            "deprecationReplacement" -> deprecationReplacement = r.optString()
            "defaultTemperature" -> temperature = r.optDouble()?.toFloat()
            "defaultStopSequences" -> stops = r.optStringList()
            else -> return@readObject false
        }
        true
    }
    return ModelCapabilities(
        supportsVision = vision, supportsFunctionCalling = functionCalling,
        contextLength = contextLength, maxOutputTokens = maxOutput,
        supportsReasoning = reasoning, reasoningEffortLevels = effortLevels ?: emptyList(),
        supportsPdfInput = pdf, aliases = aliases ?: emptyList(),
        deprecationDate = deprecationDate, deprecationReplacement = deprecationReplacement,
        defaultTemperature = temperature, defaultStopSequences = stops ?: emptyList()
    )
}

private fun writeModelCapabilities(out: JsonWriter, value: ModelCapabilities) {
    out.beginObject()
    out.opt("supportsVision", value.supportsVision)
    out.opt("supportsFunctionCalling", value.supportsFunctionCalling)
    out.opt("contextLength", value.contextLength)
    out.opt("maxOutputTokens", value.maxOutputTokens)
    out.opt("supportsReasoning", value.supportsReasoning)
    out.optList("reasoningEffortLevels", value.reasoningEffortLevels)
    out.opt("supportsPdfInput", value.supportsPdfInput)
    out.optList("aliases", value.aliases)
    out.opt("deprecationDate", value.deprecationDate)
    out.opt("deprecationReplacement", value.deprecationReplacement)
    // As Number: Gson's float adapter writes Float.toString ("0.7"), not
    // the widened double ("0.699999988079071").
    value.defaultTemperature?.let { out.name("defaultTemperature").value(it as Number) }
    out.optList("defaultStopSequences", value.defaultStopSequences)
    out.endObject()
}

internal fun readModelsDevMeta(r: TierJson): PricingCache.ModelsDevMeta {
    var vision: Boolean? = null; var toolCall: Boolean? = null; var reasoning: Boolean? = null
    var maxIn: Int? = null; var maxOut: Int? = null
    r.readObject { name ->
        when (name) {
            "supportsVision" -> vision = r.optBoolean()
            "supportsToolCall" -> toolCall = r.optBoolean()
            "supportsReasoning" -> reasoning = r.optBoolean()
            "maxInputTokens" -> maxIn = r.optInt()
            "maxOutputTokens" -> maxOut = r.optInt()
            else -> return@readObject false
        }
        true
    }
    return PricingCache.ModelsDevMeta(vision, toolCall, reasoning, maxIn, maxOut)
}

private fun writeModelsDevMeta(out: JsonWriter, value: PricingCache.ModelsDevMeta) {
    out.beginObject()
    out.opt("supportsVision", value.supportsVision)
    out.opt("supportsToolCall", value.supportsToolCall)
    out.opt("supportsReasoning", value.supportsReasoning)
    out.opt("maxInputTokens", value.maxInputTokens)
    out.opt("maxOutputTokens", value.maxOutputTokens)
    out.endObject()
}

internal fun readRequestyMeta(r: TierJson): PricingCache.RequestyMeta {
    var vision: Boolean? = null; var reasoning: Boolean? = null; var computerUse: Boolean? = null
    var toolCalling: Boolean? = null; var webSearch: Boolean? = null
    var maxIn: Int? = null; var maxOut: Int? = null
    r.readObject { name ->
        when (name) {
            "supportsVision" -> vision = r.optBoolean()
            "supportsReasoning" -> reasoning = r.optBoolean()
            "supportsComputerUse" -> computerUse = r.optBoolean()
            "supportsToolCalling" -> toolCalling = r.optBoolean()
            "supportsWebSearch" -> webSearch = r.optBoolean()
            "maxInputTokens" -> maxIn = r.optInt()
            "maxOutputTokens" -> maxOut = r.optInt()
            else -> return@readObject false
        }
        true
    }
    return PricingCache.RequestyMeta(vision, reasoning, computerUse, toolCalling, webSearch, maxIn, maxOut)
}

private fun writeRequestyMeta(out: JsonWriter, value: PricingCache.RequestyMeta) {
    out.beginObject()
    out.opt("supportsVision", value.supportsVision)
    out.opt("supportsReasoning", value.supportsReasoning)
    out.opt("supportsComputerUse", value.supportsComputerUse)
    out.opt("supportsToolCalling", value.supportsToolCalling)
    out.opt("supportsWebSearch", value.supportsWebSearch)
    out.opt("maxInputTokens", value.maxInputTokens)
    out.opt("maxOutputTokens", value.maxOutputTokens)
    out.endObject()
}

internal fun readTrueFoundryMeta(r: TierJson): PricingCache.TrueFoundryMeta {
    var vision: Boolean? = null; var toolCalling: Boolean? = null; var reasoning: Boolean? = null
    var maxIn: Int? = null; var maxOut: Int? = null
    r.readObject { name ->
        when (name) {
            "supportsVision" -> vision = r.optBoolean()
            "supportsToolCalling" -> toolCalling = r.optBoolean()
            "supportsReasoning" -> reasoning = r.optBoolean()
            "maxInputTokens" -> maxIn = r.optInt()
            "maxOutputTokens" -> maxOut = r.optInt()
            else -> return@readObject false
        }
        true
    }
    return PricingCache.TrueFoundryMeta(vision, toolCalling, reasoning, maxIn, maxOut)
}

private fun writeTrueFoundryMeta(out: JsonWriter, value: PricingCache.TrueFoundryMeta) {
    out.beginObject()
    out.opt("supportsVision", value.supportsVision)
    out.opt("supportsToolCalling", value.supportsToolCalling)
    out.opt("supportsReasoning", value.supportsReasoning)
    out.opt("maxInputTokens", value.maxInputTokens)
    out.opt("maxOutputTokens", value.maxOutputTokens)
    out.endObject()
}

internal fun readLiteLLMMeta(r: TierJson): PricingCache.LiteLLMMeta {
    var mode: String? = null; var vision: Boolean? = null; var webSearch: Boolean? = null
    var endpoints: List<String>? = null; var systemMessages: Boolean? = null
    var responseSchema: Boolean? = null; var reasoning: Boolean? = null
    var nativeStreaming: Boolean? = null; var toolUseTokens: Int? = null
    r.readObject { name ->
        when (name) {
            "mode" -> mode = r.optString()
            "supportsVision" -> vision = r.optBoolean()
            "supportsWebSearch" -> webSearch = r.optBoolean()
            "supportedEndpoints" -> endpoints = r.optStringList()
            "supportsSystemMessages" -> systemMessages = r.optBoolean()
            "supportsResponseSchema" -> responseSchema = r.optBoolean()
            "supportsReasoning" -> reasoning = r.optBoolean()
            "supportsNativeStreaming" -> nativeStreaming = r.optBoolean()
            "toolUseSystemPromptTokens" -> toolUseTokens = r.optInt()
            else -> return@readObject false
        }
        true
    }
    return PricingCache.LiteLLMMeta(
        mode = mode, supportsVision = vision, supportsWebSearch = webSearch,
        supportedEndpoints = endpoints ?: emptyList(), supportsSystemMessages = systemMessages,
        supportsResponseSchema = responseSchema, supportsReasoning = reasoning,
        supportsNativeStreaming = nativeStreaming, toolUseSystemPromptTokens = toolUseTokens
    )
}

private fun writeLiteLLMMeta(out: JsonWriter, value: PricingCache.LiteLLMMeta) {
    out.beginObject()
    out.opt("mode", value.mode)
    out.opt("supportsVision", value.supportsVision)
    out.opt("supportsWebSearch", value.supportsWebSearch)
    out.optList("supportedEndpoints", value.supportedEndpoints)
    out.opt("supportsSystemMessages", value.supportsSystemMessages)
    out.opt("supportsResponseSchema", value.supportsResponseSchema)
    out.opt("supportsReasoning", value.supportsReasoning)
    out.opt("supportsNativeStreaming", value.supportsNativeStreaming)
    out.opt("toolUseSystemPromptTokens", value.toolUseSystemPromptTokens)
    out.endObject()
}

internal fun readArtificialAnalysisMeta(r: TierJson): PricingCache.ArtificialAnalysisMeta {
    var intelligence: Double? = null; var speed: Double? = null
    var firstChunk: Double? = null; var creator: String? = null
    r.readObject { name ->
        when (name) {
            "intelligenceIndex" -> intelligence = r.optDouble()
            "outputSpeed" -> speed = r.optDouble()
            "firstChunkSeconds" -> firstChunk = r.optDouble()
            "modelCreator" -> creator = r.optString()
            else -> return@readObject false
        }
        true
    }
    return PricingCache.ArtificialAnalysisMeta(intelligence, speed, firstChunk, creator)
}

private fun writeArtificialAnalysisMeta(out: JsonWriter, value: PricingCache.ArtificialAnalysisMeta) {
    out.beginObject()
    out.opt("intelligenceIndex", value.intelligenceIndex)
    out.opt("outputSpeed", value.outputSpeed)
    out.opt("firstChunkSeconds", value.firstChunkSeconds)
    out.opt("modelCreator", value.modelCreator)
    out.endObject()
}

internal fun readGenaiPricesMeta(r: TierJson): PricingCache.GenaiPricesMeta {
    var maxIn: Int? = null
    r.readObject { name ->
        if (name == "maxInputTokens") { maxIn = r.optInt(); true } else false
    }
    return PricingCache.GenaiPricesMeta(maxIn)
}

private fun writeGenaiPricesMeta(out: JsonWriter, value: PricingCache.GenaiPricesMeta) {
    out.beginObject()
    out.opt("maxInputTokens", value.maxInputTokens)
    out.endObject()
}
