package com.ai.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.reflect.TypeToken
import org.junit.Test

class PricingTierAdaptersTest {
    private val reflective = createReflectiveAppGson()
    private val fast = createAppGson()

    private inline fun <reified T> roundTrips(value: T) {
        val type = object : TypeToken<Map<String, T>>() {}.type
        val map = mapOf("a/b" to value)
        // Files written by the old reflective path read back identically…
        assertThat(fast.fromJson<Map<String, T>>(reflective.toJson(map, type), type)).isEqualTo(map)
        // …and the new writer produces the exact same JSON.
        assertThat(fast.toJson(map, type)).isEqualTo(reflective.toJson(map, type))
    }

    @Test fun modelPricingMatchesTheReflectiveFormat() {
        roundTrips(PricingCache.ModelPricing("m", 1.5e-6, 6.0e-6, "LITELLM", cachedReadPrice = 1.5e-7))
        roundTrips(PricingCache.ModelPricing("r", 0.0, 0.0, "LITELLM", perQueryPrice = 0.002))
        roundTrips(PricingCache.ModelPricing("x", 1.0, 2.0, "OVERRIDE", 0.1, 0.2, 3.0, 4.0, 0.3, 0.4, 0.0))
    }

    @Test fun metaRecordsMatchTheReflectiveFormat() {
        roundTrips(PricingCache.ModelsDevMeta(true, null, false, 128000, null))
        roundTrips(PricingCache.RequestyMeta(null, true, false, true, null, 1000, 2000))
        roundTrips(PricingCache.TrueFoundryMeta(true, true, null, null, 4096))
        roundTrips(PricingCache.LiteLLMMeta(mode = "chat", supportsVision = true, supportedEndpoints = listOf("/v1/chat/completions", "/v1/responses"), toolUseSystemPromptTokens = 346))
        roundTrips(PricingCache.ArtificialAnalysisMeta(61.2, 180.5, 0.42, "OpenAI"))
        roundTrips(PricingCache.GenaiPricesMeta(1_000_000))
        roundTrips(ModelCapabilities(supportsVision = true, contextLength = 200000, maxOutputTokens = 64000,
            supportsReasoning = true, reasoningEffortLevels = listOf("low", "high"), aliases = emptyList(),
            deprecationDate = "2026-12-01", defaultTemperature = 0.7f, defaultStopSequences = listOf("END")))
    }

    @Test fun missingListsReadAsEmptyLikeTheNullSafeFactory() {
        val caps = object : TypeToken<Map<String, ModelCapabilities>>() {}.type
        val json = """{"m":{"supportsVision":true}}"""
        assertThat(createAppGson().fromJson<Map<String, ModelCapabilities>>(json, caps))
            .isEqualTo(createReflectiveAppGson().fromJson<Map<String, ModelCapabilities>>(json, caps))
        val lite = object : TypeToken<Map<String, PricingCache.LiteLLMMeta>>() {}.type
        val liteJson = """{"m":{"mode":"chat","supportedEndpoints":null}}"""
        assertThat(createAppGson().fromJson<Map<String, PricingCache.LiteLLMMeta>>(liteJson, lite))
            .isEqualTo(createReflectiveAppGson().fromJson<Map<String, PricingCache.LiteLLMMeta>>(liteJson, lite))
    }

    @Test fun lenientValuesAreCoercedLikeGson() {
        val type = object : TypeToken<Map<String, PricingCache.ModelsDevMeta>>() {}.type
        val parsed: Map<String, PricingCache.ModelsDevMeta> =
            fast.fromJson("""{"m":{"supportsVision":"true","maxInputTokens":"4096","unknownField":{"x":[1,2]}}}""", type)
        assertThat(parsed["m"]).isEqualTo(PricingCache.ModelsDevMeta(supportsVision = true, maxInputTokens = 4096))
    }

    /** parseTierMap + the record readers against the reflective Gson on the
     *  real bundled catalogs (on the JVM parseTierMap tokenizes with Gson;
     *  the device's framework tokenizer is checked once on the emulator). */
    @Test fun bundledCatalogsParseLikeTheReflectiveGson() {
        val dir = java.io.File("src/main/assets/info-providers")
        fun <T> check(name: String, type: java.lang.reflect.Type, read: (TierJson) -> T) {
            val file = java.io.File(dir, "$name.json")
            val expected: Map<String, T> = reflective.fromJson(file.readText(), type)
            val actual = file.bufferedReader().use { parseTierMap(it, read) }
            assertThat(actual).isEqualTo(expected)
            assertThat(actual).isNotEmpty()
        }
        val pricing = object : TypeToken<Map<String, PricingCache.ModelPricing>>() {}.type
        listOf("litellm_pricing", "models_dev_pricing", "helicone_pricing", "llmprices_pricing", "aa_pricing_v2",
            "requesty_pricing", "llmstats_pricing", "genaiprices_pricing", "truefoundry_pricing", "openrouter_pricing"
        ).forEach { check(it, pricing, ::readModelPricing) }
        check("litellm_meta", object : TypeToken<Map<String, PricingCache.LiteLLMMeta>>() {}.type, ::readLiteLLMMeta)
        check("models_dev_meta", object : TypeToken<Map<String, PricingCache.ModelsDevMeta>>() {}.type, ::readModelsDevMeta)
        check("aa_meta_v2", object : TypeToken<Map<String, PricingCache.ArtificialAnalysisMeta>>() {}.type, ::readArtificialAnalysisMeta)
        check("requesty_meta", object : TypeToken<Map<String, PricingCache.RequestyMeta>>() {}.type, ::readRequestyMeta)
        check("genaiprices_meta", object : TypeToken<Map<String, PricingCache.GenaiPricesMeta>>() {}.type, ::readGenaiPricesMeta)
        check("truefoundry_meta", object : TypeToken<Map<String, PricingCache.TrueFoundryMeta>>() {}.type, ::readTrueFoundryMeta)
    }
}
