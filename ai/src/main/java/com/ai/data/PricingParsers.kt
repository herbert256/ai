package com.ai.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Safe numeric reads: `.asDouble` / `.asInt` THROW on a non-numeric
 *  primitive (e.g. `"input":"free"`), and an external catalog row carrying
 *  one would otherwise abort the whole provider's parse. These return null
 *  for anything that isn't a JSON number, so the bad row is skipped instead. */
private fun JsonElement?.numOrNull(): Double? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.runCatching { asDouble }?.getOrNull()
private fun JsonElement?.intOrNull(): Int? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.runCatching { asInt }?.getOrNull()

/** Pure JSON-to-Map parsers for every external pricing tier the
 *  [PricingCache] consults. Each function takes the raw response
 *  body as a String and returns the parsed pricing + (optional)
 *  capability sidecar — no PricingCache state is read or written
 *  here, so the tiers can be unit-tested in isolation and added /
 *  removed without touching the cache singleton.
 *
 *  Calling convention: `internal` so PricingCache picks them up
 *  inside its loadFromX / fetchXOnline paths without exporting the
 *  parsers to other packages.
 *
 *  Sidecar types (LiteLLMMeta, ModelsDevMeta, HeliconePattern,
 *  ArtificialAnalysisMeta) and ModelPricing all live as nested
 *  data classes on PricingCache — referenced through the outer
 *  type from here. */

/** Parse LiteLLM's `model_prices_and_context_window.json` into pricing
 *  + capability metadata. The catalog is keyed by model id directly
 *  (no per-provider scoping); composite keys land in PricingCache
 *  later via `findBestPrefixedMatch`. */
internal fun parseLiteLLMJson(json: String): Pair<Map<String, PricingCache.ModelPricing>, Map<String, PricingCache.LiteLLMMeta>> {
    @Suppress("DEPRECATION")
    val root: JsonObject = JsonParser().parse(json).asJsonObject
    val pricing = mutableMapOf<String, PricingCache.ModelPricing>()
    val meta = mutableMapOf<String, PricingCache.LiteLLMMeta>()
    for (modelId in root.keySet()) {
        val infoEl: JsonElement = root.get(modelId) ?: continue
        if (!infoEl.isJsonObject) continue
        val infoObj: JsonObject = infoEl.asJsonObject
        val flat = mutableMapOf<String, Any>()
        for (k in infoObj.keySet()) {
            val v: JsonElement = infoObj.get(k) ?: continue
            if (v.isJsonNull || !v.isJsonPrimitive) continue
            val p = v.asJsonPrimitive
            flat[k] = when {
                p.isNumber -> p.asDouble
                p.isBoolean -> p.asBoolean
                else -> p.asString
            }
        }
        liteLLMEntry(modelId, flat)?.let { pricing[it.first] = it.second }
        // Capability sidecar — populated even for entries with no
        // input/output cost (some embedding / tts models have flag
        // info but free-of-charge pricing). supported_endpoints is a
        // JSON array, so it's pulled from the raw JsonObject rather
        // than the flat primitive map.
        val mode = flat["mode"] as? String
        val sv = flat["supports_vision"] as? Boolean
        val sw = flat["supports_web_search"] as? Boolean
        val sm = flat["supports_system_messages"] as? Boolean
        val sr = flat["supports_response_schema"] as? Boolean
        val sre = (flat["supports_reasoning"] as? Boolean) ?: (flat["supports_max_reasoning_effort"] as? Boolean)
        val sns = flat["supports_native_streaming"] as? Boolean
        val tu = (flat["tool_use_system_prompt_tokens"] as? Number)?.toInt()
        val seArr = infoObj.get("supported_endpoints")
        val se = if (seArr != null && seArr.isJsonArray) {
            seArr.asJsonArray.mapNotNull { el ->
                if (el.isJsonPrimitive) el.asString else null
            }.takeIf { it.isNotEmpty() }
        } else null
        if (mode != null || sv != null || sw != null || se != null || sm != null || sr != null || sre != null || sns != null || tu != null) {
            meta[modelId] = PricingCache.LiteLLMMeta(mode, sv, sw, se, sm, sr, sre, sns, tu)
        }
    }
    return pricing to meta
}

/** Map a single litellm JSON entry to a ModelPricing, or null when it
 *  has no usable cost field at all. Token-based fields drive
 *  prompt/completion price for chat/embedding/etc.; rerank-mode
 *  models bill per query and are accepted even when both per-token
 *  fields are absent or zero. */
private fun liteLLMEntry(modelId: String, info: Map<String, Any>): Pair<String, PricingCache.ModelPricing>? {
    fun n(key: String) = when (val value = info[key]) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }
    val ic = n("input_cost_per_token")
    val oc = n("output_cost_per_token")
    val perQuery = n("input_cost_per_query") ?: 0.0
    // Drop entries that have no cost signal whatsoever — keeps the
    // cache from filling up with rows that would render as $0/$0
    // and crowd out the layered lookup. Rerank entries with a real
    // per-query price still pass even when per-token is missing.
    if (ic == null && oc == null && perQuery == 0.0) return null
    return modelId to PricingCache.ModelPricing(
        modelId = modelId,
        promptPrice = ic ?: 0.0,
        completionPrice = oc ?: 0.0,
        source = "LITELLM",
        cachedReadPrice = n("cache_read_input_token_cost"),
        cachedWritePrice = n("cache_creation_input_token_cost"),
        promptPriceAbove200k = n("input_cost_per_token_above_200k_tokens")
            ?: n("input_cost_per_token_above_128k_tokens"),
        completionPriceAbove200k = n("output_cost_per_token_above_200k_tokens")
            ?: n("output_cost_per_token_above_128k_tokens"),
        cachedReadPriceAbove200k = n("cache_read_input_token_cost_above_200k_tokens")
            ?: n("cache_read_input_token_cost_above_128k_tokens"),
        cachedWritePriceAbove200k = n("cache_creation_input_token_cost_above_200k_tokens")
            ?: n("cache_creation_input_token_cost_above_128k_tokens"),
        perQueryPrice = perQuery
    )
}

/** Parse models.dev's per-provider catalog into pricing + capability
 *  metadata. Top-level keys are providers; each provider's `models`
 *  object holds the model entries. Composite key
 *  `<provider>/<model>`. */
internal fun parseModelsDevJson(json: String): Pair<Map<String, PricingCache.ModelPricing>, Map<String, PricingCache.ModelsDevMeta>> {
    @Suppress("DEPRECATION")
    val root: JsonObject = JsonParser().parse(json).asJsonObject
    val pricing = mutableMapOf<String, PricingCache.ModelPricing>()
    val meta = mutableMapOf<String, PricingCache.ModelsDevMeta>()
    for (provKey in root.keySet()) {
        val provEl = root.get(provKey) ?: continue
        if (!provEl.isJsonObject) continue
        val provObj = provEl.asJsonObject
        val modelsEl = provObj.get("models") ?: continue
        if (!modelsEl.isJsonObject) continue
        val modelsObj = modelsEl.asJsonObject
        for (modelKey in modelsObj.keySet()) {
            val modelEl = modelsObj.get(modelKey) ?: continue
            if (!modelEl.isJsonObject) continue
            val m = modelEl.asJsonObject
            val composite = "$provKey/$modelKey"
            // Pricing — cost.input / cost.output in $/M tokens, divide
            // to get per-token. Skip when both are missing (some
            // entries are open-weights or free-tier metadata only).
            val costEl = m.get("cost")
            if (costEl != null && costEl.isJsonObject) {
                val cost = costEl.asJsonObject
                val ic = cost.get("input").numOrNull()
                val oc = cost.get("output").numOrNull()
                if (ic != null && oc != null) {
                    val cr = cost.get("cache_read").numOrNull()
                    val cw = cost.get("cache_write").numOrNull()
                    pricing[composite] = PricingCache.ModelPricing(
                        modelId = modelKey,
                        promptPrice = ic / 1_000_000.0,
                        completionPrice = oc / 1_000_000.0,
                        source = "MODELSDEV",
                        cachedReadPrice = cr?.div(1_000_000.0),
                        cachedWritePrice = cw?.div(1_000_000.0)
                    )
                }
            }
            // Capability sidecar.
            val attachment = m.get("attachment")?.takeIf { it.isJsonPrimitive }?.asBoolean
            val toolCall = m.get("tool_call")?.takeIf { it.isJsonPrimitive }?.asBoolean
            val reasoning = m.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asBoolean
            val modalitiesIn = m.get("modalities")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("input")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            val visionFromModalities = modalitiesIn?.any { it.equals("image", ignoreCase = true) }
            val supportsVision = attachment ?: visionFromModalities
            val limit = m.get("limit")?.takeIf { it.isJsonObject }?.asJsonObject
            val ctx = limit?.get("context").intOrNull()
            val out = limit?.get("output").intOrNull()
            if (supportsVision != null || toolCall != null || reasoning != null || ctx != null || out != null) {
                meta[composite] = PricingCache.ModelsDevMeta(supportsVision, toolCall, reasoning, ctx, out)
            }
        }
    }
    return pricing to meta
}

/** Parse Helicone's `data` array. Splits into exact-match entries
 *  (operator=`equals`) keyed `<provider>/<modelId>` lowercased, and a
 *  pattern list for `startsWith` / `includes` rules sorted longest-
 *  first so the most specific match wins. */
internal fun parseHeliconeJson(json: String): Pair<Map<String, PricingCache.ModelPricing>, List<PricingCache.HeliconePattern>> {
    @Suppress("DEPRECATION")
    val root = JsonParser().parse(json).asJsonObject
    val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: return emptyMap<String, PricingCache.ModelPricing>() to emptyList()
    val exact = mutableMapOf<String, PricingCache.ModelPricing>()
    val patterns = mutableListOf<PricingCache.HeliconePattern>()
    for (el in data) {
        if (!el.isJsonObject) continue
        val obj = el.asJsonObject
        val provider = obj.get("provider")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val modelStr = obj.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val op = obj.get("operator")?.takeIf { it.isJsonPrimitive }?.asString ?: "equals"
        val ic = obj.get("input_cost_per_1m").numOrNull() ?: continue
        val oc = obj.get("output_cost_per_1m").numOrNull() ?: continue
        val cr = obj.get("prompt_cache_read_per_1m").numOrNull()
        val cw = obj.get("prompt_cache_write_per_1m").numOrNull()
        val pricing = PricingCache.ModelPricing(
            modelId = modelStr,
            promptPrice = ic / 1_000_000.0,
            completionPrice = oc / 1_000_000.0,
            source = "HELICONE",
            cachedReadPrice = cr?.div(1_000_000.0),
            cachedWritePrice = cw?.div(1_000_000.0)
        )
        val composite = "${provider.lowercase()}/$modelStr"
        if (op == "equals") exact[composite] = pricing
        else patterns.add(PricingCache.HeliconePattern(provider.lowercase(), modelStr, op, pricing))
    }
    // Longest-pattern-first means a more specific rule (e.g. "claude-opus-4-5")
    // wins over a generic one (e.g. "claude") when both could match.
    patterns.sortByDescending { it.pattern.length }
    return exact to patterns
}

/** Parse one vendor's section of llm-prices.com. `price_history` is
 *  reverse-chronological; the first entry with `to_date == null` is
 *  the active price. Composite key `<vendor>/<modelId>`. */
internal fun parseLLMPricesVendorJson(vendor: String, json: String): Map<String, PricingCache.ModelPricing> {
    @Suppress("DEPRECATION")
    val root = JsonParser().parse(json).asJsonObject
    val models = root.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
    val out = mutableMapOf<String, PricingCache.ModelPricing>()
    for (el in models) {
        if (!el.isJsonObject) continue
        val m = el.asJsonObject
        val id = m.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        // price_history is reverse-chronological — first non-archived row
        // is the current price (to_date == null marks the active entry).
        val history = m.get("price_history")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
        val current = history.firstOrNull { e ->
            e.isJsonObject && (e.asJsonObject.get("to_date")?.isJsonNull ?: true)
        }?.asJsonObject ?: history.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val ic = current.get("input").numOrNull() ?: continue
        val oc = current.get("output").numOrNull() ?: continue
        val cached = current.get("input_cached").numOrNull()
        out["$vendor/$id"] = PricingCache.ModelPricing(
            modelId = id,
            promptPrice = ic / 1_000_000.0,
            completionPrice = oc / 1_000_000.0,
            source = "LLMPRICES",
            cachedReadPrice = cached?.div(1_000_000.0)
        )
    }
    return out
}

/** Parse Artificial Analysis's `data` array. Pricing keyed by
 *  `<creator-slug>/<model-slug>` lowercased (or just slug when the
 *  creator is missing); sidecar carries intelligence_index, output
 *  speed, time-to-first-token. */
internal fun parseArtificialAnalysisJson(json: String): Pair<Map<String, PricingCache.ModelPricing>, Map<String, PricingCache.ArtificialAnalysisMeta>> {
    @Suppress("DEPRECATION")
    val rootEl = JsonParser().parse(json)
    val arr = when {
        rootEl.isJsonArray -> rootEl.asJsonArray
        rootEl.isJsonObject -> rootEl.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyMap<String, PricingCache.ModelPricing>() to emptyMap()
        else -> return emptyMap<String, PricingCache.ModelPricing>() to emptyMap()
    }
    val pricing = mutableMapOf<String, PricingCache.ModelPricing>()
    val meta = mutableMapOf<String, PricingCache.ArtificialAnalysisMeta>()
    for (el in arr) {
        if (!el.isJsonObject) continue
        val m = el.asJsonObject
        val slug = m.get("slug")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val creatorObj = m.get("model_creator")?.takeIf { it.isJsonObject }?.asJsonObject
        val creatorSlug = creatorObj?.get("slug")?.takeIf { it.isJsonPrimitive }?.asString
        val creatorName = creatorObj?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        val composite = if (!creatorSlug.isNullOrBlank()) "${creatorSlug.lowercase()}/$slug" else slug

        val priceObj = m.get("pricing")?.takeIf { it.isJsonObject }?.asJsonObject
        val ic = priceObj?.get("price_1m_input_tokens").numOrNull()
        val oc = priceObj?.get("price_1m_output_tokens").numOrNull()
        if (ic != null && oc != null) {
            pricing[composite] = PricingCache.ModelPricing(
                modelId = slug,
                promptPrice = ic / 1_000_000.0,
                completionPrice = oc / 1_000_000.0,
                source = "ARTIFICIALANALYSIS"
            )
        }

        val evals = m.get("evaluations")?.takeIf { it.isJsonObject }?.asJsonObject
        val intelligenceIndex = evals?.get("artificial_analysis_intelligence_index").numOrNull()
        val outputSpeed = m.get("median_output_tokens_per_second").numOrNull()
        val firstChunk = m.get("median_time_to_first_token_seconds").numOrNull()
        if (intelligenceIndex != null || outputSpeed != null || firstChunk != null || creatorName != null) {
            meta[composite] = PricingCache.ArtificialAnalysisMeta(intelligenceIndex, outputSpeed, firstChunk, creatorName)
        }
    }
    return pricing to meta
}

/** Parse Requesty's `/v1/models` response (`{"object":"list","data":[…]}`).
 *  Model ids are `<vendor>/<modelId>` and prices are ALREADY per-token
 *  (e.g. 0.000005 = $5/M), so — unlike the $/M tiers — there is no
 *  divide-by-1M conversion. Pricing keyed by the id verbatim; sidecar
 *  carries the capability flags + token limits. */
internal fun parseRequestyJson(json: String): Pair<Map<String, PricingCache.ModelPricing>, Map<String, PricingCache.RequestyMeta>> {
    @Suppress("DEPRECATION")
    val rootEl = JsonParser().parse(json)
    val arr = when {
        rootEl.isJsonObject -> rootEl.asJsonObject.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyMap<String, PricingCache.ModelPricing>() to emptyMap()
        rootEl.isJsonArray -> rootEl.asJsonArray
        else -> return emptyMap<String, PricingCache.ModelPricing>() to emptyMap()
    }
    val pricing = mutableMapOf<String, PricingCache.ModelPricing>()
    val meta = mutableMapOf<String, PricingCache.RequestyMeta>()
    for (el in arr) {
        if (!el.isJsonObject) continue
        val m = el.asJsonObject
        val id = m.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val ic = m.get("input_price").numOrNull()
        val oc = m.get("output_price").numOrNull()
        if (ic != null && oc != null) {
            pricing[id] = PricingCache.ModelPricing(
                modelId = id,
                promptPrice = ic,
                completionPrice = oc,
                source = "REQUESTY",
                cachedReadPrice = m.get("cached_price").numOrNull(),
                cachedWritePrice = m.get("caching_price").numOrNull()
            )
        }
        fun bool(key: String): Boolean? =
            m.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        val vision = bool("supports_vision")
        val reasoning = bool("supports_reasoning")
        val computerUse = bool("supports_computer_use")
        val toolCalling = bool("supports_tool_calling")
        val webSearch = bool("supports_web_search")
        val ctx = m.get("context_window").intOrNull()
        val out = m.get("max_output_tokens").intOrNull()
        if (vision != null || reasoning != null || computerUse != null || toolCalling != null ||
            webSearch != null || ctx != null || out != null) {
            meta[id] = PricingCache.RequestyMeta(vision, reasoning, computerUse, toolCalling, webSearch, ctx, out)
        }
    }
    return pricing to meta
}

/** Parse one page of llm-stats `/stats/v1/models`
 *  (`{"models":[…],"next_cursor":…,"total":…}`). Returns the page's
 *  pricing + meta plus the `next_cursor` (null when exhausted) so the
 *  caller can paginate.
 *
 *  Pricing is **per-provider** (`providers[].input_price_per_m` /
 *  `output_price_per_m`, in $/M tokens). We collapse to one
 *  representative price: the provider matching the model's own
 *  organization (first-party) when present, else the cheapest by input
 *  rate. Composite key `<org>/<modelId>` (creator-slug style, like AA).
 *  `top_scores` is stored raw — its scales are heterogeneous upstream,
 *  so it is NOT turned into a single quality index. */
internal fun parseLlmStatsJson(
    json: String
): Triple<Map<String, PricingCache.ModelPricing>, Map<String, PricingCache.LlmStatsMeta>, String?> {
    @Suppress("DEPRECATION")
    val root = JsonParser().parse(json)
    val empty = Triple(emptyMap<String, PricingCache.ModelPricing>(), emptyMap<String, PricingCache.LlmStatsMeta>(), null)
    if (!root.isJsonObject) return empty
    val obj = root.asJsonObject
    val arr = obj.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return empty
    val nextCursor = obj.get("next_cursor")?.takeIf { it.isJsonPrimitive }?.asString
    val pricing = mutableMapOf<String, PricingCache.ModelPricing>()
    val meta = mutableMapOf<String, PricingCache.LlmStatsMeta>()
    for (el in arr) {
        if (!el.isJsonObject) continue
        val m = el.asJsonObject
        val id = m.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val orgObj = m.get("organization")?.takeIf { it.isJsonObject }?.asJsonObject
        val orgId = orgObj?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        val orgName = orgObj?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        val composite = if (!orgId.isNullOrBlank()) "${orgId.lowercase()}/$id" else id

        // Collapse the providers[] array to one representative price:
        // first-party (provider_id == org id) wins, else the cheapest.
        val provs = m.get("providers")?.takeIf { it.isJsonArray }?.asJsonArray
        data class Quote(val pid: String?, val inp: Double, val out: Double)
        val quotes = provs?.mapNotNull { pe ->
            if (!pe.isJsonObject) return@mapNotNull null
            val po = pe.asJsonObject
            val ip = po.get("input_price_per_m").numOrNull()
            val op = po.get("output_price_per_m").numOrNull()
            if (ip == null || op == null) null
            else Quote(po.get("provider_id")?.takeIf { it.isJsonPrimitive }?.asString, ip, op)
        }.orEmpty()
        val chosen = quotes.firstOrNull { orgId != null && it.pid.equals(orgId, ignoreCase = true) }
            ?: quotes.minByOrNull { it.inp }
        if (chosen != null) {
            pricing[composite] = PricingCache.ModelPricing(
                modelId = id,
                promptPrice = chosen.inp / 1_000_000.0,
                completionPrice = chosen.out / 1_000_000.0,
                source = "LLMSTATS"
            )
        }

        val modalities = m.get("modalities")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }?.takeIf { it.isNotEmpty() }
        val supportsVision = modalities?.any { it.equals("image", ignoreCase = true) }
        val scores = m.get("top_scores")?.takeIf { it.isJsonObject }?.asJsonObject?.let { so ->
            so.entrySet().mapNotNull { (k, v) -> v.numOrNull()?.let { k to it } }.toMap().takeIf { it.isNotEmpty() }
        }
        if (supportsVision != null || modalities != null || orgName != null || scores != null) {
            meta[composite] = PricingCache.LlmStatsMeta(supportsVision, modalities, orgName, scores)
        }
    }
    return Triple(pricing, meta, nextCursor)
}

/** Parse Pydantic genai-prices' `data_slim.json` — a JSON **array** of
 *  providers, each `{ id, name, models:[ { id, context_window, prices } ] }`.
 *  Prices are in **\$/M tokens** (`input_mtok`, `output_mtok`,
 *  `cache_read_mtok`, `cache_write_mtok`) so we divide by 1M, mirroring
 *  models.dev. Composite key `<provider>/<modelId>`.
 *
 *  `prices` is usually a flat object but can be a **conditional array**
 *  (date / tier variants); we take the unconstrained entry (else the last)
 *  and read its `prices`. Individual `*_mtok` fields can themselves be a
 *  TieredPrices object instead of a number — `numOrNull()` returns null for
 *  those, so a tiered field degrades to "missing" rather than aborting the
 *  row. Sidecar carries the context window for the token-limit chain. */
internal fun parseGenaiPricesJson(json: String): Pair<Map<String, PricingCache.ModelPricing>, Map<String, PricingCache.GenaiPricesMeta>> {
    @Suppress("DEPRECATION")
    val rootEl = JsonParser().parse(json)
    val empty = emptyMap<String, PricingCache.ModelPricing>() to emptyMap<String, PricingCache.GenaiPricesMeta>()
    val providers = when {
        rootEl.isJsonArray -> rootEl.asJsonArray
        rootEl.isJsonObject -> rootEl.asJsonObject.get("providers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return empty
        else -> return empty
    }
    val pricing = mutableMapOf<String, PricingCache.ModelPricing>()
    val meta = mutableMapOf<String, PricingCache.GenaiPricesMeta>()
    for (provEl in providers) {
        if (!provEl.isJsonObject) continue
        val prov = provEl.asJsonObject
        val provId = prov.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val models = prov.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
        for (mEl in models) {
            if (!mEl.isJsonObject) continue
            val m = mEl.asJsonObject
            val id = m.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val composite = "$provId/$id"
            // prices: flat object, or a conditional array (take the
            // unconstrained entry, else the last) — then read its `prices`.
            val pricesEl = m.get("prices")
            val priceObj: JsonObject? = when {
                pricesEl == null -> null
                pricesEl.isJsonObject -> pricesEl.asJsonObject
                pricesEl.isJsonArray -> {
                    val arr = pricesEl.asJsonArray
                    val chosen = arr.lastOrNull { it.isJsonObject && it.asJsonObject.get("constraint")?.isJsonNull != false }
                        ?: arr.lastOrNull { it.isJsonObject }
                    chosen?.asJsonObject?.get("prices")?.takeIf { it.isJsonObject }?.asJsonObject
                }
                else -> null
            }
            if (priceObj != null) {
                val ic = priceObj.get("input_mtok").numOrNull()
                val oc = priceObj.get("output_mtok").numOrNull()
                if (ic != null || oc != null) {
                    pricing[composite] = PricingCache.ModelPricing(
                        modelId = id,
                        promptPrice = (ic ?: 0.0) / 1_000_000.0,
                        completionPrice = (oc ?: 0.0) / 1_000_000.0,
                        source = "GENAIPRICES",
                        cachedReadPrice = priceObj.get("cache_read_mtok").numOrNull()?.div(1_000_000.0),
                        cachedWritePrice = priceObj.get("cache_write_mtok").numOrNull()?.div(1_000_000.0)
                    )
                }
            }
            val ctx = m.get("context_window").intOrNull()
            if (ctx != null) meta[composite] = PricingCache.GenaiPricesMeta(maxInputTokens = ctx)
        }
    }
    return pricing to meta
}

/** Parse one page of CloudPrice's `/api/v1/models`
 *  (`{ "data":[…], "pagination":{ "next_token":…, "has_next":… } }`).
 *  CloudPrice's bulk list carries capabilities + context but **no inline
 *  pricing** (that lives behind per-model endpoints), so this is a
 *  capabilities-only tier — no ModelPricing is produced. Returns the page's
 *  meta map plus the next pagination token (null when exhausted) so the
 *  caller can walk every page. Composite key `<creator>/<name>` (creator-slug
 *  style, like AA / llm-stats). */
internal fun parseCloudPriceJson(json: String): Pair<Map<String, PricingCache.CloudPriceMeta>, String?> {
    @Suppress("DEPRECATION")
    val root = JsonParser().parse(json)
    val empty = emptyMap<String, PricingCache.CloudPriceMeta>() to null
    if (!root.isJsonObject) return empty
    val obj = root.asJsonObject
    val arr = obj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return empty
    val pag = obj.get("pagination")?.takeIf { it.isJsonObject }?.asJsonObject
    val hasNext = pag?.get("has_next")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
    val nextToken = pag?.get("next_token")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { hasNext && it.isNotBlank() }
    val meta = mutableMapOf<String, PricingCache.CloudPriceMeta>()
    for (el in arr) {
        if (!el.isJsonObject) continue
        val m = el.asJsonObject
        val name = m.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val creator = m.get("creator")?.takeIf { it.isJsonPrimitive }?.asString
        val composite = if (!creator.isNullOrBlank()) "${creator.lowercase()}/$name" else name
        val inputModalities = m.get("modalities")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("input")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
        val supportsVision = inputModalities?.any { it.equals("image", ignoreCase = true) }
        val caps = m.get("capabilities")?.takeIf { it.isJsonObject }?.asJsonObject
        fun cap(key: String): Boolean? =
            caps?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        val ctx = m.get("context_window").intOrNull()
        val out = m.get("max_output_tokens").intOrNull()
        meta[composite] = PricingCache.CloudPriceMeta(
            supportsVision = supportsVision,
            supportsReasoning = cap("reasoning"),
            supportsToolCalling = cap("function_calling"),
            supportsWebSearch = cap("web_search"),
            supportsComputerUse = cap("computer_use"),
            maxInputTokens = ctx,
            maxOutputTokens = out
        )
    }
    return meta to nextToken
}
