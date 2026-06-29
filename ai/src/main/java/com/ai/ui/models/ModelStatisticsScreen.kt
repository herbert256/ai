package com.ai.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.data.AppService
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.LocalNavigateToModelInfo
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One aggregated group of the Model statistics screen: a canonical base
 *  model name with every concrete (provider, modelId) pair that folds into
 *  it. The Versions / Providers counts and the drill-downs are derived from
 *  [entries]. */
data class ModelStatGroup(
    val baseName: String,
    val entries: List<Pair<AppService, String>>
) {
    /** Distinct versions = model ids with the namespace prefix stripped +
     *  lower-cased, so `z-ai/glm-5.1` and the bare `glm-5.1` count once. */
    val versionCount: Int get() = entries.mapTo(HashSet()) { versionKey(it.second) }.size
    /** Distinct providers (AppService id) carrying any version. */
    val providerCount: Int get() = entries.mapTo(HashSet()) { it.first.id }.size
}

/** The grouping key for a single version: model id, namespace-stripped +
 *  lower-cased. */
private fun versionKey(modelId: String): String = modelId.substringAfterLast('/').lowercase().trim()

/** Word-shaped tokens that, when they appear as a `-`/`_`/`:`/space
 *  segment, mark the start of size / variant / qualifier information
 *  rather than the model name — everything from there on is dropped when
 *  computing the base name. Numeric size + precision tags (70b, a3b,
 *  fp8, int4) and dates all carry a digit and are caught separately, so
 *  this set only needs the word-shaped variants. Extend freely. */
private val VARIANT_TOKENS = setOf(
    "instruct", "instruction", "chat", "it", "hf", "base", "turbo",
    "mini", "nano", "micro", "small", "medium", "large", "xl", "xxl",
    "pro", "plus", "air", "flash", "lite", "thinking", "reasoning",
    "reasoner", "preview", "exp", "experimental", "beta", "latest",
    "coder", "code", "vl", "vlm", "vision", "next", "max", "ultra",
    "fast", "distill", "moe", "online", "tools", "tee", "awq", "gptq",
    "gguf", "bnb"
)

/** Hard-coded family collapses applied AFTER the generic [baseModelName]
 *  pass, for families the token heuristic can't fold on its own. "all X* →
 *  X": if the generic base has [PREFIX_RULES] as a word-boundary prefix it
 *  collapses to that prefix (so claude-haiku / claude-opus / claude-3-5 all
 *  → claude; gpt-oss → gpt). [SEGMENT_RULES]: if a `-`/`.`/`_` segment of
 *  the generic base equals the token it collapses to that token (so
 *  zai.glm / zai-glm → glm; tiny-aya-earth → aya), while autoglm-phone is
 *  left alone (its segment is `autoglm`, not `glm`). Plain editable lists —
 *  add entries to condense further. */
private val PREFIX_RULES = listOf("wan", "command", "flux", "gemini", "claude", "gpt", "grok", "sonar", "qwen", "glm")
private val SEGMENT_RULES = listOf("glm", "aya", "nemotron")

/** Bedrock-style "vendor.model" namespaces: a dot-joined creator prefix
 *  (mistral.ministral, nvidia.nemotron, openai.gpt-oss, amazon.nova,
 *  qwen.qwen3, zai.glm, …). Stripped so the model — not the routing vendor —
 *  drives the grouping. Only these known creators and only the dot form, so
 *  a model whose name itself carries a dot (flux.1) is left untouched. */
private val VENDOR_NAMESPACES = setOf(
    "amazon", "anthropic", "mistral", "meta", "cohere", "ai21", "stability", "deepseek",
    "qwen", "minimax", "nvidia", "openai", "writer", "zai", "twelvelabs", "luma",
    "bytedance", "moonshotai", "alibaba", "microsoft", "ibm", "google", "perplexity",
    "xai", "baidu", "tencent", "nous", "allenai", "upstage"
)

private fun canonicalBaseName(base: String): String {
    for (p in PREFIX_RULES)
        if (base == p || (base.length > p.length && base.startsWith(p) && !base[p.length].isLetter())) return p
    // OpenAI reasoning line o1 / o3 / o4 (incl. o3-mini, o4-mini@region) → one row.
    if (base.length >= 2 && base[0] == 'o' && base[1].isDigit()) return "o-series"
    val segs = base.split('-', '.', '_')
    for (s in SEGMENT_RULES) if (s in segs) return s
    return base
}

/** A token starts version / size / variant info when it carries a digit
 *  (5.2, k2.6, 70b, v4, r1, 2024, 4o, a3b, fp8) or is a known variant
 *  word ([VARIANT_TOKENS]). */
private fun isVersionOrVariantToken(token: String): Boolean =
    token.any(Char::isDigit) || token in VARIANT_TOKENS

/** Generic pass: strip a model id to its base name — namespace, version
 *  and size/variant qualifiers removed.
 *
 *  Steps:
 *   1. Drop the namespace prefix (everything up to and including the last
 *      `/`), lower-case (`z-ai/glm-5.2` → `glm-5.2`).
 *   2. Split on `-` `_` `:` and whitespace (a `.` is NOT a separator, so a
 *      version like `5.2` / `k2.6` stays one token).
 *   3. Keep the first token as the name root (an attached version like
 *      `qwen3` / `phi3` is left intact), append following tokens until the
 *      first version-or-variant token, stop there.
 *
 *  Examples: `glm-5.2` → `glm`, `kimi-k2.7-code` → `kimi`,
 *  `qwen3-coder` → `qwen3`, `llama-3.3-70b-instruct` → `llama`. */
internal fun baseModelName(modelId: String): String {
    val afterSlash = modelId.substringAfterLast('/').lowercase().trim()
    if (afterSlash.isEmpty()) return ""
    val tokens = afterSlash.split('-', '_', ':', ' ').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return afterSlash
    val base = StringBuilder(tokens.first())
    for (tok in tokens.drop(1)) {
        if (isVersionOrVariantToken(tok)) break
        base.append('-').append(tok)
    }
    return base.toString()
}

/** Generic base name + the hard-coded family collapses. This is the key
 *  every (provider, model) pair is grouped under on the screen. First strips
 *  a Bedrock-style "vendor." prefix: if what follows starts with a
 *  version-like token the vendor IS the family (deepseek.r1 → deepseek),
 *  otherwise the model name follows it (mistral.ministral → ministral). */
internal fun canonicalModelName(modelId: String): String {
    var id = modelId.substringAfterLast('/').lowercase().trim()
    val dot = id.indexOf('.')
    if (dot > 0) {
        val vendor = id.substring(0, dot)
        val rest = id.substring(dot + 1)
        if (vendor in VENDOR_NAMESPACES && rest.isNotEmpty()) {
            val restFirst = rest.split('-', '_', ':', ' ').firstOrNull().orEmpty()
            id = if (isVersionOrVariantToken(restFirst)) vendor else rest
        }
    }
    return canonicalBaseName(baseModelName(id))
}

/** Aggregate every (provider, model) pair across all active providers into
 *  per-canonical-base groups, retaining the underlying pairs so the
 *  drill-downs have data. Sorted alphabetically by base name. */
internal fun computeModelStatistics(aiSettings: Settings): List<ModelStatGroup> {
    val groups = LinkedHashMap<String, MutableList<Pair<AppService, String>>>()
    for (service in aiSettings.getActiveServices()) {
        for (model in aiSettings.getModels(service)) {
            if (model.isBlank()) continue
            val base = canonicalModelName(model)
            if (base.isBlank()) continue
            groups.getOrPut(base) { mutableListOf() }.add(service to model)
        }
    }
    return groups.map { (base, entries) -> ModelStatGroup(base, entries) }.sortedBy { it.baseName }
}

private fun plural(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

/** A drill-down frame. The stack only ever grows by one (column tap or a
 *  deeper row tap) and shrinks by one (back), so the back-stack layers
 *  rather than replaces. */
private sealed interface Drill {
    val group: ModelStatGroup
    data class Models(override val group: ModelStatGroup) : Drill
    data class Versions(override val group: ModelStatGroup) : Drill
    data class Providers(override val group: ModelStatGroup) : Drill
    data class ProvidersForVersion(override val group: ModelStatGroup, val versionKey: String) : Drill
    data class VersionsForProvider(override val group: ModelStatGroup, val providerId: String) : Drill
}

/** AI Setup → Models → Model statistics. Table grouping the full model
 *  catalog by canonical base name; each column drills into a relevant
 *  sub-screen, ending at the existing Model Info page. */
@Composable
fun ModelStatisticsScreen(
    aiSettings: Settings,
    onBack: () -> Unit
) {
    val stats by produceState<List<ModelStatGroup>?>(initialValue = null, aiSettings) {
        value = withContext(Dispatchers.IO) { computeModelStatistics(aiSettings) }
    }
    var drill by remember { mutableStateOf<List<Drill>>(emptyList()) }
    if (drill.isNotEmpty()) {
        ModelStatDrill(
            frame = drill.last(),
            onBack = { drill = drill.dropLast(1) },
            onPush = { drill = drill + it }
        )
        return
    }
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "model_statistics",
            title = "Model statistics",
            subject = "Base models, versions and provider coverage",
            onBackClick = onBack
        )
        val list = stats
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Computing…", fontSize = 13.sp, color = AppColors.TextTertiary)
            }
            return@Column
        }
        Text(
            "${plural(list.size, "base model")} across all active providers · tap a column to drill in",
            fontSize = 11.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Model", modifier = Modifier.weight(1f), fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary)
            Text("Versions", modifier = Modifier.width(78.dp), textAlign = TextAlign.End,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary)
            Text("Providers", modifier = Modifier.width(88.dp), textAlign = TextAlign.End,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextSecondary)
        }
        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No models yet — activate a provider and refresh its model list.",
                    fontSize = 13.sp, color = AppColors.TextTertiary)
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(list, key = { it.baseName }) { g ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Three independently tappable columns → three drill modes.
                    Text(g.baseName,
                        modifier = Modifier.weight(1f).clickable { drill = listOf(Drill.Models(g)) },
                        fontSize = 14.sp, color = AppColors.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${g.versionCount}",
                        modifier = Modifier.width(78.dp).clickable { drill = listOf(Drill.Versions(g)) },
                        textAlign = TextAlign.End, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace, color = AppColors.InfoAccent)
                    Text("${g.providerCount}",
                        modifier = Modifier.width(88.dp).clickable { drill = listOf(Drill.Providers(g)) },
                        textAlign = TextAlign.End, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace, color = AppColors.InfoAccent)
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.4f), thickness = 1.dp)
            }
        }
    }
}

/** One row of a drill-down list. */
private data class DrillRowSpec(
    val key: String,
    val primary: String,
    val secondary: String?,
    val trailing: String?,
    val onTap: () -> Unit
)

/** Renders one drill-down [frame]: a TitleBar + a list whose rows either
 *  open Model Info (concrete provider·model) or push the next drill level
 *  (versions↔providers). */
@Composable
private fun ModelStatDrill(
    frame: Drill,
    onBack: () -> Unit,
    onPush: (Drill) -> Unit
) {
    BackHandler { onBack() }
    val navToModelInfo = LocalNavigateToModelInfo.current
    val g = frame.group

    val subject: String = when (frame) {
        is Drill.Models -> "${g.baseName} · models"
        is Drill.Versions -> "${g.baseName} · versions"
        is Drill.Providers -> "${g.baseName} · providers"
        is Drill.ProvidersForVersion -> "${frame.versionKey} · providers"
        is Drill.VersionsForProvider -> "${frame.providerId} · ${g.baseName} versions"
    }

    val rows: List<DrillRowSpec> = when (frame) {
        is Drill.Models -> g.entries
            .sortedWith(compareBy({ it.second.lowercase() }, { it.first.id.lowercase() }))
            .map { (prov, model) ->
                DrillRowSpec("${prov.id}|$model", model, prov.id, null) { navToModelInfo(prov, model) }
            }
        is Drill.Versions -> g.entries.groupBy { versionKey(it.second) }
            .entries.sortedBy { it.key }
            .map { (vk, es) ->
                val label = es.first().second.substringAfterLast('/').trim()
                val provs = es.mapTo(HashSet()) { it.first.id }.size
                DrillRowSpec("v|$vk", label, null, plural(provs, "provider")) {
                    onPush(Drill.ProvidersForVersion(g, vk))
                }
            }
        is Drill.Providers -> g.entries.groupBy { it.first.id }
            .entries.sortedBy { it.key.lowercase() }
            .map { (pid, es) ->
                val vers = es.mapTo(HashSet()) { versionKey(it.second) }.size
                DrillRowSpec("p|$pid", pid, null, plural(vers, "version")) {
                    onPush(Drill.VersionsForProvider(g, pid))
                }
            }
        is Drill.ProvidersForVersion -> g.entries
            .filter { versionKey(it.second) == frame.versionKey }
            .sortedBy { it.first.id.lowercase() }
            .map { (prov, model) ->
                DrillRowSpec("pv|${prov.id}|$model", prov.id, model, null) { navToModelInfo(prov, model) }
            }
        is Drill.VersionsForProvider -> g.entries
            .filter { it.first.id == frame.providerId }
            .sortedBy { it.second.lowercase() }
            .map { (prov, model) ->
                DrillRowSpec("vp|$model", model, null, null) { navToModelInfo(prov, model) }
            }
    }

    val noun = when (frame) {
        is Drill.Versions, is Drill.VersionsForProvider -> "version"
        is Drill.Providers, is Drill.ProvidersForVersion -> "provider"
        is Drill.Models -> "model"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppColors.AppBackground)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
    ) {
        TitleBar(
            helpTopic = "model_statistics",
            title = "Model statistics",
            subject = subject,
            onBackClick = onBack
        )
        Text(
            plural(rows.size, noun),
            fontSize = 11.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        HorizontalDivider(color = AppColors.TextDisabled, thickness = 1.dp)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(rows, key = { it.key }) { r ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { r.onTap() }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.primary, fontSize = 14.sp, color = AppColors.TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (r.secondary != null) {
                            Text(r.secondary, fontSize = 11.sp, color = AppColors.InfoAccent,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (r.trailing != null) {
                        Text(r.trailing, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            color = AppColors.TextSecondary, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.4f), thickness = 1.dp)
            }
        }
    }
}
